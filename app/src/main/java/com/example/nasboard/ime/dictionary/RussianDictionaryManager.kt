package com.example.nasboard.ime.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class RussianDictionaryManager private constructor(private val context: Context) {

    private val trie = RussianTrie()
    private var isLoaded = false
    private var database: SQLiteDatabase? = null

    // 用于存储上下文预测的缓存
    private val bigramCache = mutableMapOf<String, List<String>>()
    private val phraseCache = mutableMapOf<String, List<String>>()
    private var lastProcessedWord: String? = null

    // 短语词库（存储完整短语和它们的组成部分）
    private val phraseDictionary = mutableMapOf<String, MutableList<String>>()

    companion object {
        @Volatile
        private var instance: RussianDictionaryManager? = null

        fun getInstance(context: Context): RussianDictionaryManager {
            return instance ?: synchronized(this) {
                instance ?: RussianDictionaryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun loadDictionary() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        try {
            // 复制数据库文件到应用内部存储
            copyDatabaseFromAssets()

            // 打开数据库
            val dbPath = context.getDatabasePath("russian_dict.db").absolutePath
            database = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

            // 从数据库加载数据到Trie树
            loadWordsFromDatabase()

            // 加载短语数据
            loadPhrasesFromDatabase()

            isLoaded = true
            Log.d("RussianDictionary", "SQLite数据库加载完成，Trie树构建成功，短语数据加载完成")

        } catch (e: Exception) {
            Log.e("RussianDictionary", "Error loading SQLite dictionary: ${e.message}")
        }
    }

    private fun copyDatabaseFromAssets() {
        val dbFile = context.getDatabasePath("russian_dict.db")

        // 如果数据库文件已存在，先删除（确保使用最新版本）
        if (dbFile.exists()) {
            dbFile.delete()
        }

        // 确保目录存在
        dbFile.parentFile?.mkdirs()

        try {
            // 从assets读取数据库文件
            val inputStream: InputStream = context.assets.open("dict/russian_dict.db")
            val outputStream = FileOutputStream(dbFile)

            // 复制文件
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.d("RussianDictionary", "数据库文件复制完成: ${dbFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("RussianDictionary", "复制数据库文件失败: ${e.message}")
            throw e
        }
    }

    private fun loadWordsFromDatabase() {
        val database = database ?: throw IllegalStateException("Database not initialized")

        // 查询unigram表中的所有词语
        val cursor = database.query(
            "unigram",
            arrayOf("ngram", "freq"),
            null,
            null,
            null,
            null,
            "freq DESC" // 按频率降序排列
        )

        val words = mutableSetOf<String>()
        var wordCount = 0

        cursor.use { c ->
            while (c.moveToNext()) {
                val ngram = c.getString(c.getColumnIndexOrThrow("ngram"))
                // 只添加长度大于1的词语到Trie树
                if (ngram.isNotEmpty() && ngram.length > 1) {
                    words.add(ngram)
                    wordCount++

                    // 检查是否是多词短语
                    if (ngram.contains(" ")) {
                        // 这是一个短语，将其拆分为单词并存储
                        val phraseWords = ngram.trim().split("\\s+".toRegex())
                        for (i in 0 until phraseWords.size - 1) {
                            val currentWord = phraseWords[i].trim()
                            val nextWord = phraseWords[i + 1].trim()
                            if (currentWord.isNotEmpty() && nextWord.isNotEmpty()) {
                                // 存储短语内部的bigram关系
                                val key = currentWord
                                val existing = phraseDictionary.getOrPut(key) { mutableListOf() }
                                if (nextWord !in existing) {
                                    existing.add(nextWord)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 构建Trie树
        words.forEach { word ->
            trie.insert(word)
        }

        Log.d("RussianDictionary", "从数据库加载 $wordCount 个词语到Trie树")
    }

    private fun loadPhrasesFromDatabase() {
        val database = database ?: throw IllegalStateException("Database not initialized")

        // 专门加载包含空格的短语（多词短语）
        val cursor = database.query(
            "unigram",
            arrayOf("ngram", "freq"),
            "ngram LIKE '% %'", // 包含空格的短语
            null,
            null,
            null,
            "freq DESC",
            "1000" // 只加载前1000个高频短语
        )

        var phraseCount = 0

        cursor.use { c ->
            while (c.moveToNext()) {
                val ngram = c.getString(c.getColumnIndexOrThrow("ngram"))
                if (ngram.isNotEmpty() && ngram.contains(" ")) {
                    // 这是一个短语
                    val phraseWords = ngram.trim().split("\\s+".toRegex())
                    if (phraseWords.size >= 2) {
                        // 存储短语的首词到完整短语的映射
                        val firstWord = phraseWords[0].trim()
                        val key = "phrase:$firstWord"
                        val existing = phraseDictionary.getOrPut(key) { mutableListOf() }
                        if (ngram !in existing) {
                            existing.add(ngram)
                            phraseCount++
                        }
                    }
                }
            }
        }

        Log.d("RussianDictionary", "加载 $phraseCount 个多词短语到短语词典")
    }

    // 改进的基础前缀预测
    fun getPredictions(prefix: String, contextWord: String? = null, maxPredictions: Int = 20): List<String> {
        if (!isLoaded || prefix.isEmpty()) return emptyList()

        return try {
            // 首先从Trie树获取所有前缀匹配的词语
            val allPredictions = trie.getWordsWithPrefix(prefix)

            // 如果前缀匹配的词语太多，尝试从数据库获取更精确的预测（按频率排序）
            if (allPredictions.size > maxPredictions * 2) {
                getPredictionsFromDatabase(prefix, maxPredictions)
            } else {
                // 对Trie树结果按频率排序（如果有频率信息）
                val sortedPredictions = if (allPredictions.size > 1) {
                    // 尝试按长度和类型排序（单字词优先，然后是短语）
                    allPredictions.sortedWith(compareBy(
                        { if (it.contains(" ")) 1 else 0 }, // 短语排在后面
                        { it.length }, // 短词优先
                        { if (it.contains(prefix)) 0 else 1 } // 完全匹配优先
                    ))
                } else {
                    allPredictions
                }
                sortedPredictions.take(maxPredictions)
            }
        } catch (e: Exception) {
            Log.e("RussianDictionary", "Error getting predictions: ${e.message}")
            emptyList()
        }
    }

    // 改进的上下文预测：基于前一个词预测下一个词
    fun getContextPredictions(previousWord: String, currentPrefix: String = "", maxPredictions: Int = 15): List<String> {
        if (!isLoaded || previousWord.isEmpty()) {
            return if (currentPrefix.isNotEmpty()) {
                getPredictions(currentPrefix, null, maxPredictions)
            } else {
                emptyList()
            }
        }

        return try {
            // 构建缓存键
            val cacheKey = "$previousWord|$currentPrefix"

            // 检查缓存
            if (bigramCache.containsKey(cacheKey)) {
                return bigramCache[cacheKey]!!.take(maxPredictions)
            }

            val database = database ?: return emptyList()

            val predictions = mutableListOf<String>()

            // 1. 首先检查短语词典（多词短语的下一部分）
            if (currentPrefix.isNotEmpty()) {
                // 如果当前前缀不为空，查找以当前前缀开头的短语下一部分
                val phraseNextWords = phraseDictionary[previousWord]
                if (phraseNextWords != null) {
                    val matchingNextWords = phraseNextWords.filter {
                        it.startsWith(currentPrefix)
                    }
                    predictions.addAll(matchingNextWords)
                    Log.d("RussianDictionary", "从短语词典找到匹配: $previousWord -> ${matchingNextWords.take(3)}...")
                }
            } else {
                // 如果当前前缀为空，显示所有可能的短语下一部分
                val phraseNextWords = phraseDictionary[previousWord]
                if (phraseNextWords != null) {
                    predictions.addAll(phraseNextWords)
                    Log.d("RussianDictionary", "从短语词典找到下一词: $previousWord -> ${phraseNextWords.take(3)}...")
                }
            }

            // 2. 查询bigram表，找到基于前一个词的最可能的下一个词
            val cursor = if (currentPrefix.isEmpty()) {
                // 如果没有当前前缀，直接查询基于前一个词的预测
                database.query(
                    "bigram",
                    arrayOf("w2", "freq"),
                    "w1 = ?",
                    arrayOf(previousWord),
                    null,
                    null,
                    "freq DESC",
                    maxPredictions.toString()
                )
            } else {
                // 如果有当前前缀，查询既匹配前一个词又以当前前缀开头的词
                database.query(
                    "bigram",
                    arrayOf("w2", "freq"),
                    "w1 = ? AND w2 LIKE ?",
                    arrayOf(previousWord, "$currentPrefix%"),
                    null,
                    null,
                    "freq DESC",
                    maxPredictions.toString()
                )
            }

            cursor.use { c ->
                while (c.moveToNext()) {
                    val word = c.getString(c.getColumnIndexOrThrow("w2"))
                    if (word.isNotEmpty() && word !in predictions) {
                        predictions.add(word)
                    }
                }
            }

            Log.d("RussianDictionary", "上下文预测: '$previousWord' -> '${predictions.take(3)}...'")

            // 3. 如果bigram预测结果不足，尝试从完整短语中提取
            if (predictions.size < maxPredictions) {
                // 查找以previousWord开头的完整短语
                val phraseKey = "phrase:$previousWord"
                val fullPhrases = phraseDictionary[phraseKey]
                if (fullPhrases != null) {
                    // 从完整短语中提取下一部分
                    val nextWordsFromPhrases = mutableSetOf<String>()
                    fullPhrases.forEach { phrase ->
                        val words = phrase.split("\\s+".toRegex())
                        val index = words.indexOfFirst { it == previousWord }
                        if (index >= 0 && index < words.size - 1) {
                            val nextWord = words[index + 1]
                            if (currentPrefix.isEmpty() || nextWord.startsWith(currentPrefix)) {
                                nextWordsFromPhrases.add(nextWord)
                            }
                        }
                    }
                    predictions.addAll(nextWordsFromPhrases.filter { it !in predictions })
                }
            }

            // 4. 如果预测结果仍然不足，用unigram预测补充
            if (predictions.size < maxPredictions && currentPrefix.isNotEmpty()) {
                val unigramPredictions = getPredictions(currentPrefix, null, maxPredictions - predictions.size)
                predictions.addAll(unigramPredictions.filter { it !in predictions })
            }

            // 5. 最后补充高频词
            if (predictions.size < maxPredictions) {
                val remaining = maxPredictions - predictions.size
                val frequentWords = getMostFrequentWords(remaining)
                predictions.addAll(frequentWords.filter {
                    it !in predictions && (currentPrefix.isEmpty() || it.startsWith(currentPrefix))
                })
            }

            // 更新缓存
            bigramCache[cacheKey] = predictions
            if (bigramCache.size > 100) {
                // 限制缓存大小
                val oldestKey = bigramCache.keys.first()
                bigramCache.remove(oldestKey)
            }

            predictions.take(maxPredictions)
        } catch (e: Exception) {
            Log.e("RussianDictionary", "上下文预测失败: ${e.message}")
            // 回退到普通预测
            getPredictions(currentPrefix, null, maxPredictions)
        }
    }

    // 获取纯上下文预测（没有当前输入前缀）
    fun getPureContextPredictions(previousWord: String, maxPredictions: Int = 10): List<String> {
        if (!isLoaded || previousWord.isEmpty()) return emptyList()

        return try {
            val database = database ?: return emptyList()

            val predictions = mutableListOf<String>()

            // 1. 从短语词典获取下一词
            val phraseNextWords = phraseDictionary[previousWord]
            if (phraseNextWords != null) {
                predictions.addAll(phraseNextWords.take(maxPredictions / 2))
            }

            // 2. 查询bigram表，只基于前一个词预测下一个词
            val cursor = database.query(
                "bigram",
                arrayOf("w2", "freq"),
                "w1 = ?",
                arrayOf(previousWord),
                null,
                null,
                "freq DESC",
                (maxPredictions - predictions.size).toString()
            )

            cursor.use { c ->
                while (c.moveToNext()) {
                    val word = c.getString(c.getColumnIndexOrThrow("w2"))
                    if (word.isNotEmpty() && word !in predictions) {
                        predictions.add(word)
                    }
                }
            }

            // 3. 从完整短语中提取下一部分
            if (predictions.size < maxPredictions) {
                val phraseKey = "phrase:$previousWord"
                val fullPhrases = phraseDictionary[phraseKey]
                if (fullPhrases != null) {
                    val nextWordsFromPhrases = mutableSetOf<String>()
                    fullPhrases.forEach { phrase ->
                        val words = phrase.split("\\s+".toRegex())
                        val index = words.indexOfFirst { it == previousWord }
                        if (index >= 0 && index < words.size - 1) {
                            nextWordsFromPhrases.add(words[index + 1])
                        }
                    }
                    predictions.addAll(nextWordsFromPhrases.filter { it !in predictions })
                }
            }

            // 关键修复：如果bigram预测结果不足，用高频unigram补充
            if (predictions.size < maxPredictions) {
                val remaining = maxPredictions - predictions.size
                val frequentWords = getMostFrequentWords(remaining)
                predictions.addAll(frequentWords.filter { it !in predictions })
            }

            Log.d("RussianDictionary", "纯上下文预测: '$previousWord' -> '${predictions.take(5)}...'")
            predictions.take(maxPredictions)
        } catch (e: Exception) {
            Log.e("RussianDictionary", "纯上下文预测失败: ${e.message}")
            emptyList()
        }
    }

    // 智能短语预测：基于当前输入和上下文预测完整短语
    fun getPhrasePredictions(currentInput: String, contextWords: List<String> = emptyList(), maxPredictions: Int = 10): List<String> {
        if (!isLoaded || currentInput.isEmpty()) return emptyList()

        return try {
            val predictions = mutableListOf<String>()

            // 1. 直接匹配当前输入开头的短语
            val cursor = database?.query(
                "unigram",
                arrayOf("ngram", "freq"),
                "ngram LIKE ? AND ngram LIKE '% %'", // 必须是包含空格的短语
                arrayOf("$currentInput%"),
                null,
                null,
                "freq DESC",
                maxPredictions.toString()
            )

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val ngram = c.getString(c.getColumnIndexOrThrow("ngram"))
                    if (ngram.isNotEmpty() && ngram.contains(" ")) {
                        predictions.add(ngram)
                    }
                }
            }

            // 2. 如果有上下文词，查找包含上下文和当前输入的短语
            if (contextWords.isNotEmpty() && predictions.size < maxPredictions) {
                val lastContextWord = contextWords.last()
                val phraseKey = "phrase:$lastContextWord"
                val fullPhrases = phraseDictionary[phraseKey]
                if (fullPhrases != null) {
                    val matchingPhrases = fullPhrases.filter { phrase ->
                        phrase.contains(" $currentInput") || phrase.startsWith("$currentInput ")
                    }
                    predictions.addAll(matchingPhrases.filter { it !in predictions })
                }
            }

            Log.d("RussianDictionary", "短语预测: '$currentInput' (上下文: $contextWords) -> '${predictions.take(3)}...'")
            predictions.take(maxPredictions)
        } catch (e: Exception) {
            Log.e("RussianDictionary", "短语预测失败: ${e.message}")
            emptyList()
        }
    }

    // 获取高频词
    private fun getMostFrequentWords(maxWords: Int): List<String> {
        val database = database ?: return emptyList()

        val frequentWords = mutableListOf<String>()

        val cursor = database.query(
            "unigram",
            arrayOf("ngram"),
            null,
            null,
            null,
            null,
            "freq DESC",
            maxWords.toString()
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                val ngram = c.getString(c.getColumnIndexOrThrow("ngram"))
                if (ngram.isNotEmpty() && ngram.length > 1 && !ngram.contains(" ")) {
                    frequentWords.add(ngram)
                }
            }
        }

        return frequentWords.take(maxWords)
    }

    // 智能预测：自动检测是否使用上下文
    fun getSmartPredictions(textBeforeCursor: String, maxPredictions: Int = 15): List<String> {
        if (!isLoaded || textBeforeCursor.isEmpty()) return emptyList()

        // 提取最后一个词作为当前输入的前缀
        val words = textBeforeCursor.trim().split("\\s+".toRegex())
        if (words.isEmpty()) return emptyList()

        val currentInput = words.last()

        // 如果有前一个词，使用上下文预测
        if (words.size >= 2) {
            val previousWord = words[words.size - 2]

            // 首先尝试短语预测
            val phrasePredictions = getPhrasePredictions(currentInput, words.take(words.size - 1), maxPredictions / 2)
            if (phrasePredictions.isNotEmpty()) {
                return phrasePredictions
            }

            // 然后使用上下文预测
            return getContextPredictions(previousWord, currentInput, maxPredictions)
        }

        // 否则使用普通前缀预测
        return getPredictions(currentInput, null, maxPredictions)
    }

    // 从数据库获取预测词（按频率排序）
    private fun getPredictionsFromDatabase(prefix: String, maxPredictions: Int): List<String> {
        val database = database ?: return emptyList()

        val predictions = mutableListOf<String>()

        try {
            val cursor = database.query(
                "unigram",
                arrayOf("ngram", "freq"),
                "ngram LIKE ?",
                arrayOf("$prefix%"),
                null,
                null,
                "freq DESC", // 按频率降序排列
                maxPredictions.toString()
            )

            cursor.use { c ->
                while (c.moveToNext()) {
                    val ngram = c.getString(c.getColumnIndexOrThrow("ngram"))
                    if (ngram.isNotEmpty()) {
                        predictions.add(ngram)
                    }
                }
            }

            Log.d("RussianDictionary", "数据库查询 '$prefix' 返回 ${predictions.size} 个预测词")
        } catch (e: Exception) {
            Log.e("RussianDictionary", "数据库查询失败: ${e.message}")
        }

        return predictions
    }

    // 处理文本提交，更新上下文
    fun processWordSubmission(word: String) {
        lastProcessedWord = word
        // 可以在这里添加更多逻辑，比如学习用户输入习惯
    }

    // 获取最后处理的词（用于上下文）
    fun getLastProcessedWord(): String? {
        return lastProcessedWord
    }

    // 检查词语是否存在（使用Trie树快速检查）
    fun isWord(word: String): Boolean {
        return trie.contains(word)
    }

    // 获取词语频率（从数据库查询）
    suspend fun getWordFrequency(word: String): Int = withContext(Dispatchers.IO) {
        val database = database ?: return@withContext 0

        try {
            val cursor = database.query(
                "unigram",
                arrayOf("freq"),
                "ngram = ?",
                arrayOf(word),
                null,
                null,
                null
            )

            var frequency = 0
            cursor.use { c ->
                if (c.moveToFirst()) {
                    frequency = c.getInt(c.getColumnIndexOrThrow("freq"))
                }
            }

            frequency
        } catch (e: Exception) {
            Log.e("RussianDictionary", "查询词语频率失败: ${e.message}")
            0
        }
    }

    // 清理资源
    fun close() {
        database?.close()
        database = null
        isLoaded = false
        bigramCache.clear()
        phraseCache.clear()
        phraseDictionary.clear()
    }
}

// Trie树实现用于高效前缀搜索
class RussianTrie {
    private data class TrieNode(
        val children: MutableMap<Char, TrieNode> = mutableMapOf(),
        var isEndOfWord: Boolean = false
    )

    private val root = TrieNode()

    fun insert(word: String) {
        var current = root
        for (char in word) {
            current = current.children.getOrPut(char) { TrieNode() }
        }
        current.isEndOfWord = true
    }

    fun getWordsWithPrefix(prefix: String): List<String> {
        var current = root

        // 找到前缀的最后一个节点
        for (char in prefix) {
            current = current.children[char] ?: return emptyList()
        }

        val results = mutableListOf<String>()
        collectWords(current, prefix, results)

        return results
    }

    private fun collectWords(node: TrieNode, prefix: String, results: MutableList<String>) {
        if (node.isEndOfWord) {
            results.add(prefix)
        }

        for ((char, childNode) in node.children) {
            collectWords(childNode, prefix + char, results)
        }
    }

    fun contains(word: String): Boolean {
        var current = root
        for (char in word) {
            current = current.children[char] ?: return false
        }
        return current.isEndOfWord
    }
}