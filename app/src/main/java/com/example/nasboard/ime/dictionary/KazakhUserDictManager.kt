package com.example.nasboard.ime.dictionary

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

class KazakhUserDictManager private constructor(
    private val context: Context,
    private val userDictScope: CoroutineScope
) {

    private var isLoaded = false
    private var userDictFile: File? = null
    private val maxUserDictSize = 10000 // 最大用户词典大小
    private val autoSaveThreshold = 50 // 自动保存阈值
    private val initializationMutex = Mutex() // 使用 Mutex 替代 synchronized

    companion object {
        @Volatile
        private var instance: KazakhUserDictManager? = null

        fun getInstance(context: Context, userDictScope: CoroutineScope): KazakhUserDictManager {
            return instance ?: synchronized(this) {
                instance ?: KazakhUserDictManager(context.applicationContext, userDictScope).also { instance = it }
            }
        }
    }

    suspend fun initialize(): Boolean {
        return initializationMutex.withLock {
            // 双重检查锁定
            if (isLoaded) {
                Log.d("KazakhUserDict", "initialize: Already loaded, returning true")
                return@withLock true
            }

            try {
                Log.d("KazakhUserDict", "=== Starting user dictionary initialization ===")

                // 1. 确保文件存在
                ensureUserDictFile()

                // 2. 获取文件路径
                val filePath = userDictFile?.absolutePath ?: ""
                Log.d("KazakhUserDict", "User dict file path: $filePath")

                // 3. 如果文件不存在或为空，创建新的用户词典
                if (userDictFile?.exists() != true || userDictFile?.length() == 0L) {
                    Log.d("KazakhUserDict", "User dict file is empty or doesn't exist, creating new...")

                    // 先创建一个空文件
                    userDictFile?.writeText("")

                    // 重置加载状态
                    isLoaded = false

                    // 4. 初始化Native层
                    Log.d("KazakhUserDict", "Initializing native user dictionary...")
                    val initNative = try {
                        val success = nativeLoadUserDict(filePath)
                        Log.d("KazakhUserDict", "Native init result: $success")
                        success
                    } catch (e: Exception) {
                        Log.e("KazakhUserDict", "Native init failed: ${e.message}")
                        false
                    }

                    if (initNative) {
                        isLoaded = true
                        Log.d("KazakhUserDict", "✅ User dictionary initialized (new empty dict)")
                        return@withLock true
                    } else {
                        Log.e("KazakhUserDict", "❌ Failed to initialize native user dictionary")
                        return@withLock false
                    }
                } else {
                    // 文件存在且有内容，正常加载
                    Log.d("KazakhUserDict", "Loading existing user dictionary...")

                    val startTime = System.currentTimeMillis()
                    val loadSuccess = nativeLoadUserDict(filePath)
                    val elapsed = System.currentTimeMillis() - startTime

                    Log.d("KazakhUserDict", "Native load took ${elapsed}ms, result: $loadSuccess")

                    if (loadSuccess) {
                        isLoaded = true
                        Log.d("KazakhUserDict", "✅ User dictionary loaded successfully")
                        return@withLock true
                    } else {
                        Log.e("KazakhUserDict", "❌ Native load returned false")

                        // 如果加载失败，尝试强制重新初始化
                        Log.d("KazakhUserDict", "Trying to force initialize...")
                        return@withLock performForceInitialize()
                    }
                }

            } catch (e: Exception) {
                Log.e("KazakhUserDict", "Exception during initialization: ${e.message}")
                e.printStackTrace()

                // 即使异常，也检查状态
                if (nativeIsUserDictInitialized()) {
                    Log.d("KazakhUserDict", "Native initialized despite exception")
                    isLoaded = true
                    return@withLock true
                }

                return@withLock false
            }
        }
    }

    private suspend fun performForceInitialize(): Boolean {
        return try {
            Log.w("KazakhUserDict", "Force initializing user dictionary...")

            // 1. 重置状态
            isLoaded = false

            // 2. 关闭现有的（如果存在）
            nativeCloseUserDict()

            // 3. 清理文件
            userDictFile?.delete()

            // 4. 重新初始化 - 注意：这里我们调用内部初始化逻辑，而不是回到initialize()
            ensureUserDictFile()
            val filePath = userDictFile?.absolutePath ?: ""

            val initNative = try {
                val success = nativeLoadUserDict(filePath)
                Log.d("KazakhUserDict", "Force init native result: $success")
                success
            } catch (e: Exception) {
                Log.e("KazakhUserDict", "Force init native failed: ${e.message}")
                false
            }

            if (initNative) {
                isLoaded = true
                Log.d("KazakhUserDict", "✅ User dictionary force initialized")
                true
            } else {
                Log.e("KazakhUserDict", "❌ Failed to force initialize user dictionary")
                false
            }
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Force initialization failed: ${e.message}")
            false
        }
    }

    private fun ensureUserDictFile() {
        val userDictDir = File(context.filesDir, "userdict")
        if (!userDictDir.exists()) {
            userDictDir.mkdirs()
            Log.d("KazakhUserDict", "Created user dict directory")
        }

        userDictFile = File(userDictDir, "kazakh_userdict.dat")

        if (userDictFile?.exists() == false) {
            try {
                userDictFile?.createNewFile()
                Log.d("KazakhUserDict", "Created empty user dict file")
            } catch (e: IOException) {
                Log.e("KazakhUserDict", "Failed to create user dict file: ${e.message}")
            }
        } else {
            Log.d("KazakhUserDict", "User dict file already exists, size: ${userDictFile?.length()} bytes")
        }
    }

    // ==================== 核心功能 ====================

    fun addWord(word: String, frequency: Int = 1): Boolean {
        Log.d("KazakhUserDict", "addWord called: word='$word', frequency=$frequency, isLoaded=$isLoaded")

        if (!isLoaded) {
            Log.d("KazakhUserDict", "User dict not loaded for adding word")
            return false
        }

        if (word.isEmpty()) {
            Log.d("KazakhUserDict", "Word is empty")
            return false
        }

        return try {
            Log.d("KazakhUserDict", "Calling nativeAddWord...")
            val success = nativeAddWord(word, frequency)
            Log.d("KazakhUserDict", "nativeAddWord returned: $success for word '$word'")

            if (success) {
                Log.d("KazakhUserDict", "✅ Word added: '$word' (freq: $frequency)")
                // 检查是否需要自动保存
                checkAutoSave()
            } else {
                Log.w("KazakhUserDict", "❌ Failed to add word: '$word'")
            }
            success
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error adding word: ${e.message}")
            false
        }
    }

    fun addWordWithContext(word: String, contextWord: String, frequency: Int = 1, callback: ((Boolean) -> Unit)? = null) {
        if (!isLoaded || word.isEmpty() || contextWord.isEmpty()) {
            Log.d("KazakhUserDict", "Invalid parameters for addWordWithContext")
            callback?.invoke(false)
            return
        }

        userDictScope.launch {
            try {
                Log.d("KazakhUserDict", "异步添加单词: '$word' -> '$contextWord'")
                val success = nativeAddWordWithContext(word, contextWord, frequency)

                if (success) {
                    Log.d("KazakhUserDict", "单词添加上下文成功: '$word' -> '$contextWord' (freq: $frequency)")
                    checkAutoSave()
                } else {
                    Log.w("KazakhUserDict", "单词添加上下文失败: '$word' -> '$contextWord'")
                }
                callback?.invoke(success)
            } catch (e: Exception) {
                Log.e("KazakhUserDict", "添加单词上下文异常: ${e.message}")
                callback?.invoke(false)
            }
        }
    }

    fun removeWord(word: String): Boolean {
        if (!isLoaded || word.isEmpty()) {
            Log.d("KazakhUserDict", "User dict not loaded or word empty: '$word'")
            return false
        }

        return try {
            val success = nativeRemoveWord(word)
            if (success) {
                Log.d("KazakhUserDict", "Word removed: '$word'")
                checkAutoSave()
            } else {
                Log.d("KazakhUserDict", "Word not found for removal: '$word'")
            }
            success
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error removing word: ${e.message}")
            false
        }
    }

    fun updateWordFrequency(word: String, delta: Int): Boolean {
        if (!isLoaded || word.isEmpty()) {
            Log.d("KazakhUserDict", "User dict not loaded or word empty: '$word'")
            return false
        }

        return try {
            val success = nativeUpdateWordFrequency(word, delta)
            if (success) {
                Log.d("KazakhUserDict", "Word frequency updated: '$word' delta=$delta")
                checkAutoSave()
            }
            success
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error updating word frequency: ${e.message}")
            false
        }
    }

    // ==================== 搜索功能 ====================

    fun searchPrefix(prefix: String, maxResults: Int = 20): List<String> {
        if (prefix.isEmpty() || maxResults <= 0) {
            return emptyList()
        }

        // 检查用户词典是否加载，但不要阻塞
        if (!isUserDictLoaded()) {
            Log.d("KazakhUserDict", "User dict not loaded for search: '$prefix'")
            return emptyList()
        }

        return try {
            val results = nativeUserDictPrefixSearch(prefix, maxResults)?.toList() ?: emptyList()
            if (results.isNotEmpty()) {
                Log.d("KazakhUserDict", "User dict prefix search '$prefix': found ${results.size} results")
            }
            results
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error searching user dict: ${e.message}")
            emptyList()
        }
    }

    fun searchWithContext(previousWord: String, currentPrefix: String = "", maxResults: Int = 15): List<String> {
        if (previousWord.isEmpty() || maxResults <= 0) {
            return emptyList()
        }

        if (!isUserDictLoaded()) {
            Log.d("KazakhUserDict", "User dict not loaded for context search")
            return emptyList()
        }

        return try {
            val results = nativeUserDictContextSearch(previousWord, currentPrefix, maxResults)?.toList() ?: emptyList()
            if (results.isNotEmpty()) {
                Log.d("KazakhUserDict", "User dict context search '$previousWord' -> '$currentPrefix': found ${results.size} results")
            }
            results
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error searching user dict with context: ${e.message}")
            emptyList()
        }
    }

    fun testUserDictSearch(): Boolean {
        return try {
            val results = nativeUserDictPrefixSearch("ал", 1)
            val testResult = results != null && results.isNotEmpty()
            Log.d("KazakhUserDict", "User dict search test: ${if (testResult) "✅ PASSED" else "❌ FAILED"}")
            testResult
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "User dict search test failed: ${e.message}")
            false
        }
    }

    fun containsWord(word: String): Boolean {
        Log.d("KazakhUserDict", "containsWord called: word='$word', isLoaded=$isLoaded")

        if (!isLoaded) {
            Log.d("KazakhUserDict", "User dict not loaded for contains check")
            return false
        }

        if (word.isEmpty()) {
            Log.d("KazakhUserDict", "Word is empty")
            return false
        }

        return try {
            Log.d("KazakhUserDict", "Calling nativeContainsWord...")
            val contains = nativeContainsWord(word)
            Log.d("KazakhUserDict", "User dict contains '$word': $contains")
            contains
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error checking word in user dict: ${e.message}")
            false
        }
    }

    // ==================== 批量操作 ====================

    fun importWords(words: List<String>): Boolean {
        if (!isLoaded || words.isEmpty()) {
            Log.d("KazakhUserDict", "User dict not loaded or words empty")
            return false
        }

        return try {
            val success = nativeImportWords(words.toTypedArray())
            if (success) {
                Log.d("KazakhUserDict", "Imported ${words.size} words to user dict")
                // 异步保存
                userDictScope.launch {
                    saveUserDict()
                }
            }
            success
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error importing words: ${e.message}")
            false
        }
    }

    fun exportWords(): List<String> {
        // 通过获取所有单词前缀为空的结果来导出所有单词
        return searchPrefix("", 10000)
    }

    // ==================== 文件操作 ====================

    suspend fun saveUserDict(): Boolean {
        if (!isLoaded) {
            Log.d("KazakhUserDict", "User dict not loaded for save")
            return false
        }

        return try {
            val success = nativeSaveUserDict(userDictFile?.absolutePath ?: "")
            if (success) {
                Log.d("KazakhUserDict", "User dictionary saved successfully")
                // 创建备份
                createBackup()
            } else {
                Log.e("KazakhUserDict", "Failed to save user dictionary")
            }
            success
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error saving user dictionary: ${e.message}")
            false
        }
    }

    fun saveUserDictAsync(callback: (Boolean) -> Unit = {}) {
        userDictScope.launch {
            val success = saveUserDict()
            callback(success)
        }
    }

    private fun createBackup() {
        try {
            val userDictDir = File(context.filesDir, "userdict")
            val sourceFile = File(userDictDir, "kazakh_userdict.dat")
            val backupFile = File(userDictDir, "kazakh_userdict.dat.bak")

            if (sourceFile.exists()) {
                sourceFile.copyTo(backupFile, overwrite = true)
                Log.d("KazakhUserDict", "Backup created: ${backupFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error creating backup: ${e.message}")
        }
    }

    fun clearUserDict(): Boolean {
        if (!isLoaded) {
            Log.d("KazakhUserDict", "User dict not loaded for clear")
            return false
        }

        return try {
            val success = nativeClearUserDict()
            if (success) {
                isLoaded = false
                Log.d("KazakhUserDict", "User dictionary cleared")
                // 异步重新加载空词典
                userDictScope.launch {
                    initialize()
                }
            }
            success
        } catch (e: Exception) {
            Log.e("KazakhUserDict", "Error clearing user dictionary: ${e.message}")
            false
        }
    }

    // ==================== 统计信息 ====================

    fun getStats(): String {
        if (!isLoaded) {
            return "User dictionary not loaded"
        }

        return try {
            nativeGetUserDictStats()
        } catch (e: Exception) {
            "Error getting stats: ${e.message}"
        }
    }

    fun getWordCount(): Int {
        if (!isLoaded) {
            return 0
        }

        // 通过搜索所有单词前缀为空的结果来获取数量（可能不是最高效的方法，但对于用户词典规模可以接受）
        return searchPrefix("", 10000).size
    }

    // ==================== 工具方法 ====================

    private var modificationCount = 0

    private fun checkAutoSave() {
        modificationCount++
        if (modificationCount >= autoSaveThreshold) {
            modificationCount = 0
            // 在后台保存
            userDictScope.launch {
                saveUserDict()
            }
        }
    }

    fun debugCheckState(): String {
        return buildString {
            append("=== User Dictionary Debug ===\n")
            append("Kotlin isLoaded: $isLoaded\n")
            append("Native initialized: ${nativeIsUserDictInitialized()}\n")
            append("File exists: ${userDictFile?.exists()}\n")
            append("File path: ${userDictFile?.absolutePath}\n")
            append("File size: ${userDictFile?.length()} bytes\n")
            append("isUserDictLoaded(): ${isUserDictLoaded()}\n")
        }
    }

    fun logDebugInfo() {
        Log.d("KazakhUserDict", debugCheckState())
    }

    // 修改isUserDictLoaded函数 - 只读取状态，不修改
    fun isUserDictLoaded(): Boolean {
        return isLoaded
    }

    fun close() {
        if (isLoaded) {
            // 保存更改（同步执行，因为这是关闭操作）
            userDictScope.launch {
                saveUserDict()
                nativeCloseUserDict()
                isLoaded = false
                Log.d("KazakhUserDict", "User dictionary closed")
            }
        }
    }

    // ==================== Native方法声明 ====================

    // 加载用户词典
    private external fun nativeLoadUserDict(filepath: String): Boolean

    // 保存用户词典
    private external fun nativeSaveUserDict(filepath: String): Boolean

    // 添加单词
    private external fun nativeAddWord(word: String, frequency: Int): Boolean

    // 添加上下文单词
    private external fun nativeAddWordWithContext(word: String, contextWord: String, frequency: Int): Boolean

    // 删除单词
    private external fun nativeRemoveWord(word: String): Boolean

    // 更新单词频率
    private external fun nativeUpdateWordFrequency(word: String, delta: Int): Boolean

    // 用户词典前缀搜索
    private external fun nativeUserDictPrefixSearch(prefix: String, maxResults: Int): Array<String>?

    // 用户词典上下文搜索
    private external fun nativeUserDictContextSearch(previousWord: String, currentPrefix: String, maxResults: Int): Array<String>?

    // 检查单词是否存在
    private external fun nativeContainsWord(word: String): Boolean

    // 批量导入单词
    private external fun nativeImportWords(words: Array<String>): Boolean

    // 清空用户词典
    private external fun nativeClearUserDict(): Boolean

    // 获取统计信息
    private external fun nativeGetUserDictStats(): String

    // 检查用户词典是否初始化
    private external fun nativeIsUserDictInitialized(): Boolean

    // 关闭用户词典
    private external fun nativeCloseUserDict()
}