package com.example.nasboard.ime.dictionary

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.LinkedHashMap

// 回调接口
interface SpellCorrectCallback {
    fun onHeavyCorrectComplete(results: Array<String>)
}

// 简单的LRU缓存
class LRUCache<K, V>(private val maxSize: Int = 100) {
    private val cache = LinkedHashMap<K, V>(maxSize, 0.75f, true)
    private val lock = Any()

    fun get(key: K): V? = synchronized(lock) {
        cache[key]
    }

    fun put(key: K, value: V) = synchronized(lock) {
        cache[key] = value
        if (cache.size > maxSize) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
        }
    }

    fun clear() = synchronized(lock) {
        cache.clear()
    }

    fun size(): Int = synchronized(lock) {
        cache.size
    }

    fun removeKeysWithPrefix(prefix: String) = synchronized(lock) {
        val keysToRemove = cache.keys.filter { it.toString().contains(prefix) }
        keysToRemove.forEach { cache.remove(it) }
    }

    fun removeKeysMatching(predicate: (K) -> Boolean) = synchronized(lock) {
        val keysToRemove = cache.keys.filter { predicate(it) }
        keysToRemove.forEach { cache.remove(it) }
    }
}

class KazakhDictionaryManager private constructor(private val context: Context) {

    private var isLoaded = false
    private var retryCount = 0
    private val maxRetries = 3

    // 缓存系统（不同大小）
    private val fastCache = LRUCache<String, List<String>>(500)     // 前缀缓存
    private val keyboardCache = LRUCache<String, List<String>>(1000) // 键盘纠错缓存
    private val contextCache = LRUCache<String, List<String>>(2000)  // 上下文缓存
    private val exactMatchCache = LRUCache<String, Boolean>(2000)    // 精确匹配缓存

    // 快速拒绝缓存（不在词典中的词）
    private val rejectCache = LRUCache<String, Boolean>(5000)

    // 上下文状态
    private var lastProcessedWord: String? = null
    private var isShowingContextPredictions = false

    // 任务管理
    private val ioDispatcher = Dispatchers.IO
    private val heavyPredictorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentHeavyTask: Job? = null
    private var lastInputTime: Long = 0

    companion object {
        @Volatile
        private var instance: KazakhDictionaryManager? = null

        fun getInstance(context: Context): KazakhDictionaryManager {
            return instance ?: synchronized(this) {
                instance ?: KazakhDictionaryManager(context.applicationContext).also { instance = it }
            }
        }

        // 加载JNI库
        init {
            try {
                System.loadLibrary("nasboard-pinyin")
                Log.d("KazakhDictionary", "Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("KazakhDictionary", "Failed to load native library: ${e.message}")
            }
        }
    }

    suspend fun loadDictionary(): Boolean = withContext(ioDispatcher) {
        if (isLoaded) {
            Log.d("KazakhDictionary", "Dictionary already loaded")
            return@withContext true
        }

        while (retryCount < maxRetries) {
            retryCount++
            Log.d("KazakhDictionary", "===== Attempt $retryCount to load dictionary =====")

            try {
                val unigramFileName = "unigram_kazakh.dic"
                val bigramFileName = "bigram_kazakh.dic"

                val cacheDir = context.cacheDir
                val unigramTempFile = File.createTempFile("kazakh_unigram", ".dic", cacheDir)
                val bigramTempFile = File.createTempFile("kazakh_bigram", ".dic", cacheDir)
                unigramTempFile.deleteOnExit()
                bigramTempFile.deleteOnExit()

                context.assets.open(unigramFileName).use { inputStream ->
                    FileOutputStream(unigramTempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        outputStream.flush()
                    }
                }

                context.assets.open(bigramFileName).use { inputStream ->
                    FileOutputStream(bigramTempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        outputStream.flush()
                    }
                }

                val unigramSize = unigramTempFile.length()
                val bigramSize = bigramTempFile.length()
                Log.d("KazakhDictionary", "Unigram file size: $unigramSize bytes")
                Log.d("KazakhDictionary", "Bigram file size: $bigramSize bytes")

                if (unigramSize <= 0 || bigramSize <= 0) {
                    Log.e("KazakhDictionary", "Dictionary files are empty")
                    continue
                }

                val unigramSuccess = nativeLoadUnigramDictFromFile(unigramTempFile.absolutePath)
                if (!unigramSuccess) {
                    Log.e("KazakhDictionary", "Failed to load unigram dictionary")
                    continue
                }

                val bigramSuccess = nativeLoadBigramDictFromFile(bigramTempFile.absolutePath)
                if (!bigramSuccess) {
                    Log.e("KazakhDictionary", "Failed to load bigram dictionary")
                    continue
                }

                isLoaded = true
                Log.d("KazakhDictionary", "Kazakh dictionary loaded successfully (attempt $retryCount)")

                // 预热缓存
                prewarmCache()

                return@withContext true

            } catch (e: Exception) {
                Log.e("KazakhDictionary", "Error loading dictionary (attempt $retryCount): ${e.message}")
                delay(1000)
            }
        }

        Log.e("KazakhDictionary", "Failed to load dictionary after $maxRetries attempts")
        return@withContext false
    }

    private fun prewarmCache() {
        heavyPredictorScope.launch {
            val commonPrefixes = listOf("а", "б", "қ", "с", "м", "о", "т", "ү", "і", "ә")
            for (prefix in commonPrefixes) {
                try {
                    withTimeout(5) {
                        val results = nativeFastPredict(prefix, 3)
                        fastCache.put("fast:${prefix}_3", results?.toList() ?: emptyList())
                    }
                } catch (e: Exception) {
                    // 忽略预热错误
                }
            }
            Log.d("KazakhDictionary", "Cache prewarmed with ${commonPrefixes.size} prefixes")
        }
    }

    // ==================== 分级预测系统 ====================

    // Stage 1: 快速预测 (<5ms)
    fun fastPredict(prefix: String, maxPredictions: Int = 5): List<String> {
        if (!isLoaded || prefix.isEmpty()) {
            return emptyList()
        }

        // 更新输入时间戳
        lastInputTime = System.currentTimeMillis()

        // 检查缓存
        val cacheKey = "fast:${prefix}_$maxPredictions"
        fastCache.get(cacheKey)?.let {
            Log.d("KazakhDictionary", "Fast cache hit for '$prefix'")
            return it
        }

        return try {
            val startTime = System.currentTimeMillis()
            val results = runBlocking(ioDispatcher) {
                withTimeout(10) {
                    val nativeResults = nativeFastPredict(prefix, maxPredictions)
                    nativeResults?.toList() ?: emptyList()
                }
            }

            val duration = System.currentTimeMillis() - startTime
            if (duration > 5) {
                Log.w("KazakhDictionary", "Fast predict took ${duration}ms for '$prefix'")
            }

            // 更新缓存
            fastCache.put(cacheKey, results)

            results
        } catch (e: TimeoutCancellationException) {
            Log.w("KazakhDictionary", "Fast predict timeout for '$prefix'")
            emptyList()
        } catch (e: Exception) {
            Log.e("KazakhDictionary", "Fast predict error: ${e.message}")
            emptyList()
        }
    }

    // Stage 2: 键盘邻近纠错 (<15ms)
    fun keyboardCorrect(input: String, maxCorrections: Int = 5): List<String> {
        if (!isLoaded || input.isEmpty()) {
            return emptyList()
        }

        // 更新输入时间戳
        lastInputTime = System.currentTimeMillis()

        val cacheKey = "keyboard:${input}_$maxCorrections"
        keyboardCache.get(cacheKey)?.let {
            Log.d("KazakhDictionary", "Keyboard cache hit for '$input'")
            return it
        }

        return try {
            val startTime = System.currentTimeMillis()
            val results = runBlocking(ioDispatcher) {
                withTimeout(20) {
                    val nativeResults = nativeKeyboardCorrect(input, maxCorrections)
                    nativeResults?.toList() ?: emptyList()
                }
            }

            val duration = System.currentTimeMillis() - startTime
            if (duration > 15) {
                Log.w("KazakhDictionary", "Keyboard correct took ${duration}ms for '$input'")
            }

            // 更新缓存
            keyboardCache.put(cacheKey, results)

            results
        } catch (e: TimeoutCancellationException) {
            Log.w("KazakhDictionary", "Keyboard correct timeout for '$input'")
            emptyList()
        } catch (e: Exception) {
            Log.e("KazakhDictionary", "Keyboard correct error: ${e.message}")
            emptyList()
        }
    }

    // Stage 3: 异步完整拼写纠正
    fun heavySpellCorrectAsync(input: String, callback: SpellCorrectCallback) {
        if (!isLoaded || input.isEmpty()) {
            return
        }

        // 更新输入时间戳
        val currentInputTime = System.currentTimeMillis()
        lastInputTime = currentInputTime

        // 取消之前的任务
        currentHeavyTask?.cancel()

        currentHeavyTask = heavyPredictorScope.launch {
            try {
                nativeHeavySpellCorrectAsync(input, object : SpellCorrectCallback {
                    override fun onHeavyCorrectComplete(results: Array<String>) {
                        // 检查输入是否已更新
                        if (currentInputTime != lastInputTime) {
                            Log.d("KazakhDictionary", "Heavy result outdated, ignoring")
                            return
                        }

                        // 缓存结果
                        val cacheKey = "heavy:${input}_10"
                        keyboardCache.put(cacheKey, results.toList())

                        // 回调
                        callback.onHeavyCorrectComplete(results)
                    }
                })
            } catch (e: CancellationException) {
                Log.d("KazakhDictionary", "Heavy spell correct cancelled for '$input'")
            } catch (e: Exception) {
                Log.e("KazakhDictionary", "Heavy spell correct error: ${e.message}")
            }
        }
    }

    // ==================== 智能候选词获取（分级整合） ====================

    fun getSmartCandidates(prefix: String, maxPredictions: Int = 10): List<String> {
        if (!isLoaded || prefix.isEmpty()) {
            return emptyList()
        }

        val allCandidates = mutableListOf<String>()
        val seenCandidates = mutableSetOf<String>()

        // 检查是否为有效词（使用缓存）
        if (isWord(prefix)) {
            allCandidates.add(prefix)
            seenCandidates.add(prefix)
        }

        // Stage 1: 快速前缀搜索
        val fastResults = fastPredict(prefix, maxPredictions / 2)
        for (candidate in fastResults) {
            if (candidate !in seenCandidates) {
                allCandidates.add(candidate)
                seenCandidates.add(candidate)
            }
        }

        // Stage 2: 键盘邻近纠错
        if (allCandidates.size < maxPredictions) {
            val keyboardResults = keyboardCorrect(prefix, maxPredictions - allCandidates.size)
            for (candidate in keyboardResults) {
                if (candidate !in seenCandidates) {
                    allCandidates.add(candidate)
                    seenCandidates.add(candidate)

                    if (allCandidates.size >= maxPredictions) {
                        break
                    }
                }
            }
        }

        return allCandidates.take(maxPredictions)
    }

    // ==================== 上下文预测（优化版） ====================

    fun getContextPredictions(previousWord: String, currentPrefix: String = "", maxPredictions: Int = 15): List<String> {
        if (!isLoaded || previousWord.isEmpty()) {
            return fastPredict(currentPrefix, maxPredictions)
        }

        val cacheKey = "context:${previousWord}|${currentPrefix}|$maxPredictions"
        contextCache.get(cacheKey)?.let {
            Log.d("KazakhDictionary", "Context cache hit")
            return it
        }

        return try {
            val startTime = System.currentTimeMillis()
            val results = runBlocking(ioDispatcher) {
                withTimeout(30) {
                    val nativeResults = nativeMarisaContextPredict(previousWord, currentPrefix, maxPredictions)
                    nativeResults?.toList() ?: fastPredict(currentPrefix, maxPredictions)
                }
            }

            val duration = System.currentTimeMillis() - startTime
            if (duration > 25) {
                Log.w("KazakhDictionary", "Context predict took ${duration}ms")
            }

            // 更新缓存
            contextCache.put(cacheKey, results)

            results
        } catch (e: TimeoutCancellationException) {
            Log.w("KazakhDictionary", "Context predict timeout")
            fastPredict(currentPrefix, maxPredictions)
        } catch (e: Exception) {
            Log.e("KazakhDictionary", "Context predict error: ${e.message}")
            fastPredict(currentPrefix, maxPredictions)
        }
    }

    // ==================== 兼容旧接口 ====================

    fun getPredictions(prefix: String, maxPredictions: Int = 5): List<String> {
        return fastPredict(prefix, maxPredictions)
    }

    fun spellCorrect(input: String, maxCorrections: Int = 5): List<String> {
        return keyboardCorrect(input, maxCorrections)
    }

    fun smartPredict(prefix: String, maxPredictions: Int = 8): List<String> {
        return getSmartCandidates(prefix, maxPredictions)
    }

    fun isWord(word: String): Boolean {
        if (!isLoaded || word.isEmpty()) {
            return false
        }

        // 检查拒绝缓存
        rejectCache.get(word)?.let {
            if (!it) return false
        }

        // 检查确认缓存
        exactMatchCache.get(word)?.let {
            return it
        }

        return try {
            val result = runBlocking(ioDispatcher) {
                withTimeout(5) {
                    nativeMarisaExactMatch(word)
                }
            }

            // 更新缓存
            if (result) {
                exactMatchCache.put(word, true)
            } else {
                rejectCache.put(word, false)
            }

            result
        } catch (e: Exception) {
            Log.e("KazakhDictionary", "Check word error: ${e.message}")
            false
        }
    }

    fun processWordSubmission(word: String) {
        lastProcessedWord = word
        isShowingContextPredictions = true

        // 清理相关缓存
        fastCache.removeKeysMatching { it.contains(word) }
        keyboardCache.removeKeysMatching { it.contains(word) }
        contextCache.removeKeysMatching { it.contains(word) }

        nativeMarisaProcessWordSubmission(word)
    }

    // ==================== 上下文状态管理 ====================

    fun setShowingContextPredictions(enabled: Boolean) {
        isShowingContextPredictions = enabled
    }

    fun isShowingContextPredictions(): Boolean {
        return isShowingContextPredictions
    }

    fun getLastProcessedWord(): String? {
        return lastProcessedWord
    }

    fun setLastProcessedWord(word: String?) {
        lastProcessedWord = word
    }

    // ==================== 清理资源 ====================

    fun close() {
        if (isLoaded) {
            currentHeavyTask?.cancel()
            heavyPredictorScope.cancel()

            fastCache.clear()
            keyboardCache.clear()
            contextCache.clear()
            exactMatchCache.clear()
            rejectCache.clear()

            nativeCloseMarisaDict()
            isLoaded = false
            lastProcessedWord = null
            isShowingContextPredictions = false
            retryCount = 0
            lastInputTime = 0
            Log.d("KazakhDictionary", "Kazakh dictionary closed")
        }
    }

    fun isDictionaryLoaded(): Boolean {
        return isLoaded && nativeIsMarisaDictInitialized()
    }

    fun getCacheStats(): String {
        return "Fast cache: ${fastCache.size()}, " +
                "Keyboard cache: ${keyboardCache.size()}, " +
                "Context cache: ${contextCache.size()}, " +
                "Exact match cache: ${exactMatchCache.size()}, " +
                "Reject cache: ${rejectCache.size()}"
    }

    fun clearCaches() {
        fastCache.clear()
        keyboardCache.clear()
        contextCache.clear()
        exactMatchCache.clear()
        rejectCache.clear()
        Log.d("KazakhDictionary", "All caches cleared")
    }

    // ==================== Native方法声明 ====================

    // 分级预测接口
    private external fun nativeFastPredict(prefix: String, maxResults: Int): Array<String>?
    private external fun nativeKeyboardCorrect(input: String, maxResults: Int): Array<String>?
    private external fun nativeHeavySpellCorrectAsync(input: String, callback: SpellCorrectCallback)

    // 原有接口
    private external fun nativeLoadUnigramDictFromFile(filename: String): Boolean
    private external fun nativeLoadBigramDictFromFile(filename: String): Boolean
    private external fun nativeMarisaPrefixSearch(prefix: String, maxResults: Int): Array<String>?
    private external fun nativeMarisaContextPredict(previousWord: String, currentPrefix: String, maxResults: Int): Array<String>?
    private external fun nativeMarisaExactMatch(word: String): Boolean
    private external fun nativeMarisaProcessWordSubmission(word: String)
    private external fun nativeIsMarisaDictInitialized(): Boolean
    private external fun nativeCloseMarisaDict()
}