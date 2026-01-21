// PinyinDecoder.kt
package com.example.nasboard.ime.dictionary

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.res.AssetFileDescriptor

class PinyinDecoder private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: PinyinDecoder? = null

        fun getInstance(context: Context): PinyinDecoder {
            return instance ?: synchronized(this) {
                instance ?: PinyinDecoder(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // 在伴生对象中定义TAG常量
        private const val TAG = "PinyinDecoder"
    }

    // ==================== Native方法声明 ====================
    // 注意：这些函数名必须与C++代码中的JNI函数名完全匹配

    /**
     * 打开拼音解码器（通过文件描述符）
     * @param fd 文件描述符
     * @param startOffset 起始偏移
     * @param length 文件长度
     * @param usrDictPath 用户词典路径，如果为空则不需要用户词典
     * @return 初始化是否成功
     */
    private external fun nativeImOpenDecoderFd(fd: java.io.FileDescriptor, startOffset: Long, length: Long, usrDictPath: ByteArray): Boolean

    /**
     * 打开拼音解码器（旧方法，保留但不使用）
     */
    private external fun nativeImOpenDecoder(assetMgr: AssetManager, usrDictPath: String): Boolean

    /**
     * 关闭拼音解码器
     */
    private external fun nativeImCloseDecoder()

    /**
     * 搜索拼音
     * @param pyBuf 拼音字节数组
     * @param pyLen 拼音长度
     * @return 找到的候选词数量
     */
    private external fun nativeImSearch(pyBuf: ByteArray, pyLen: Int): Int

    /**
     * 删除搜索
     * @param pos 位置
     * @param isPosInSplid 是否在音节内
     * @param clearFixedThisStep 是否清除这一步的固定部分
     * @return 删除后的候选词数量
     */
    private external fun nativeImDelSearch(pos: Int, isPosInSplid: Boolean, clearFixedThisStep: Boolean): Int

    /**
     * 重置搜索
     */
    private external fun nativeImResetSearch()

    /**
     * 获取拼音字符串
     * @param decoded 是否获取解码后的拼音
     * @return 拼音字符串
     */
    private external fun nativeImGetPyStr(decoded: Boolean): String

    /**
     * 获取拼音字符串长度
     * @param decoded 是否获取解码后的拼音长度
     * @return 拼音字符串长度
     */
    private external fun nativeImGetPyStrLen(decoded: Boolean): Int

    /**
     * 获取音节分割点
     * @return 分割点数组
     */
    private external fun nativeImGetSplStart(): IntArray

    /**
     * 获取候选词
     * @param choiceId 候选词ID
     * @return 候选词字符串
     */
    private external fun nativeImGetChoice(choiceId: Int): String

    /**
     * 选择候选词
     * @param choiceId 候选词ID
     * @return 选择后的状态
     */
    private external fun nativeImChoose(choiceId: Int): Int

    /**
     * 取消最后的选择
     * @return 取消后的状态
     */
    private external fun nativeImCancelLastChoice(): Int

    /**
     * 获取固定长度
     * @return 固定长度
     */
    private external fun nativeImGetFixedLen(): Int

    /**
     * 取消输入
     * @return 是否成功取消
     */
    private external fun nativeImCancelInput(): Boolean

    /**
     * 刷新缓存
     */
    private external fun nativeImFlushCache()

    /**
     * 设置最大长度
     * @param maxSpsLen 最大拼音长度
     * @param maxHzsLen 最大汉字长度
     */
    private external fun nativeImSetMaxLens(maxSpsLen: Int, maxHzsLen: Int)

    /**
     * 获取预测数量
     * @param fixedStr 固定字符串
     * @return 预测数量
     */
    private external fun nativeImGetPredictsNum(fixedStr: String): Int

    /**
     * 获取预测项
     * @param predictNo 预测项序号
     * @return 预测项字符串
     */
    private external fun nativeImGetPredictItem(predictNo: Int): String

    /**
     * 添加字母
     * @param ch 字母
     * @return 添加后的状态
     */
    private external fun nativeImAddLetter(ch: Byte): Int

    // ==================== 测试方法 ====================

    /**
     * 获取版本信息
     * @return 版本字符串
     */
    private external fun nativeImGetVersion(): String

    /**
     * 检查是否已初始化
     * @return 是否已初始化
     */
    private external fun nativeImIsInitialized(): Boolean

    // ==================== 成员变量 ====================

    private val context: Context = context.applicationContext
    private var initialized = false
    private var initializationError: String? = null

    // ==================== 初始化 ====================

    init {
        try {
            System.loadLibrary("nasboard-pinyin")
            Log.d(TAG, "成功加载原生库: nasboard-pinyin")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "加载原生库失败: ${e.message}")
            initializationError = "加载原生库失败: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "加载原生库时出错: ${e.message}")
            initializationError = "加载原生库时出错: ${e.message}"
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 初始化拼音解码器
     * @return 初始化是否成功
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "开始初始化拼音解码器...")

            // 如果已经初始化，直接返回成功
            if (initialized) {
                Log.d(TAG, "拼音解码器已经初始化")
                return@withContext true
            }

            // 检查是否有先前错误
            if (initializationError != null) {
                Log.e(TAG, "初始化失败（先前错误）: $initializationError")
                return@withContext false
            }

            try {
                // 步骤1：获取资源ID
                val resId = context.resources.getIdentifier("dict_pinyin", "raw", context.packageName)
                if (resId == 0) {
                    Log.e(TAG, "未找到 dict_pinyin 资源文件")
                    initializationError = "未找到 dict_pinyin 资源文件"
                    return@withContext false
                }

                Log.d(TAG, "找到资源文件: dict_pinyin, 资源ID: $resId")

                // 步骤2：打开原始资源文件描述符
                val afd: AssetFileDescriptor
                try {
                    afd = context.resources.openRawResourceFd(resId)
                } catch (e: Resources.NotFoundException) {
                    Log.e(TAG, "打开资源文件失败: ${e.message}")
                    initializationError = "打开资源文件失败: ${e.message}"
                    return@withContext false
                }

                Log.d(TAG, "资源文件信息: fd=${afd.fileDescriptor}, startOffset=${afd.startOffset}, length=${afd.length}")

                // 步骤3：准备初始化参数
                val usrDictPath = "" // 用户词典路径，可以为空
                val usrDictPathBytes = if (usrDictPath.isNotEmpty()) {
                    usrDictPath.toByteArray(StandardCharsets.UTF_8)
                } else {
                    ByteArray(1) // 空路径
                }

                Log.d(TAG, "正在调用原生解码器初始化...")
                val startTime = System.currentTimeMillis()

                // 步骤4：调用原生初始化函数（通过文件描述符）
                val result = try {
                    nativeImOpenDecoderFd(afd.fileDescriptor, afd.startOffset, afd.length, usrDictPathBytes)
                } finally {
                    // 确保关闭文件描述符
                    try {
                        afd.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "关闭文件描述符时出错: ${e.message}")
                    }
                }

                val endTime = System.currentTimeMillis()
                Log.d(TAG, "原生解码器初始化耗时: ${endTime - startTime}ms")

                if (result) {
                    // 步骤5：设置最大拼音和汉字长度
                    nativeImSetMaxLens(80, 80)
                    initialized = true

                    Log.d(TAG, "拼音解码器初始化成功")

                    // 步骤6：测试解码器是否正常工作
                    val testResult = testDecoder()
                    if (testResult) {
                        Log.d(TAG, "拼音解码器测试通过，版本: ${getVersion()}")

                        // 打印调试信息
                        val debugInfo = debugDecoder()
                        Log.d(TAG, debugInfo)

                        return@withContext true
                    } else {
                        Log.e(TAG, "拼音解码器初始化但测试失败")
                        initialized = false
                        initializationError = "解码器测试失败"
                        return@withContext false
                    }
                } else {
                    Log.e(TAG, "拼音解码器初始化失败 (nativeImOpenDecoderFd 返回 false)")
                    initializationError = "原生解码器初始化返回false"
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化拼音解码器时出错: ${e.message}")
                e.printStackTrace()
                initializationError = "初始化时出错: ${e.message}"
                return@withContext false
            }
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 检查资源中是否有词典文件
     */
    private fun checkResources(): Boolean {
        return try {
            val resId = context.resources.getIdentifier("dict_pinyin", "raw", context.packageName)
            val hasDict = resId != 0
            Log.d(TAG, "资源检查: 包含dict_pinyin.dat: $hasDict, 资源ID: $resId")
            hasDict
        } catch (e: Exception) {
            Log.e(TAG, "检查资源失败: ${e.message}")
            false
        }
    }

    /**
     * 测试解码器是否正常工作
     */
    private fun testDecoder(): Boolean {
        return try {
            // 测试一个简单的拼音
            val testPinyin = "nihao"
            Log.d(TAG, "开始测试解码器，拼音: '$testPinyin'")

            val result = search(testPinyin)
            Log.d(TAG, "测试拼音 '$testPinyin' - 找到 $result 个候选词")

            if (result > 0) {
                val candidates = getCandidates(result.coerceAtMost(5))
                Log.d(TAG, "测试拼音 '$testPinyin' - 候选词: $candidates")

                if (candidates.isNotEmpty()) {
                    Log.d(TAG, "解码器测试成功")
                    true
                } else {
                    Log.e(TAG, "解码器测试失败: 有$result 个候选词但获取不到内容")
                    false
                }
            } else {
                Log.e(TAG, "解码器测试失败: 拼音'$testPinyin'没有找到候选词")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "测试解码器时出错: ${e.message}")
            false
        }
    }

    /**
     * 获取版本信息
     */
    fun getVersion(): String {
        return try {
            nativeImGetVersion()
        } catch (e: Exception) {
            Log.e(TAG, "获取版本时出错: ${e.message}")
            "未知版本"
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean {
        val nativeInitialized = try {
            nativeImIsInitialized()
        } catch (e: Exception) {
            Log.e(TAG, "检查原生初始化状态时出错: ${e.message}")
            false
        }

        return initialized && nativeInitialized
    }

    // ==================== 核心功能方法 ====================

    /**
     * 搜索拼音
     * @param pinyin 拼音字符串
     * @return 找到的候选词数量
     */
    fun search(pinyin: String): Int {
        if (!initialized) {
            Log.d(TAG, "搜索失败: 解码器未初始化")
            return 0
        }

        if (pinyin.isEmpty()) {
            Log.d(TAG, "搜索失败: 拼音为空")
            return 0
        }

        Log.d(TAG, "搜索拼音: '$pinyin'")

        val pyBytes = pinyin.toByteArray(StandardCharsets.UTF_8)
        val result = try {
            nativeImSearch(pyBytes, pyBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "搜索拼音时出错: ${e.message}")
            0
        }

        Log.d(TAG, "搜索结果 '$pinyin': $result 个候选词")
        return result
    }

    /**
     * 获取候选词
     * @param index 候选词索引
     * @return 候选词字符串
     */
    fun getCandidate(index: Int): String {
        if (!initialized) {
            Log.d(TAG, "获取候选词失败: 解码器未初始化")
            return ""
        }

        if (index < 0) {
            Log.d(TAG, "获取候选词失败: 索引无效 ($index)")
            return ""
        }

        Log.d(TAG, "获取候选词索引: $index")
        val candidate = try {
            nativeImGetChoice(index)
        } catch (e: Exception) {
            Log.e(TAG, "获取候选词时出错: ${e.message}")
            ""
        }

        Log.d(TAG, "候选词 $index: '$candidate' (长度: ${candidate.length})")
        return candidate
    }

    /**
     * 获取多个候选词
     * @param maxCount 最大候选词数量
     * @return 候选词列表
     */
    fun getCandidates(maxCount: Int): List<String> {
        if (!initialized) {
            Log.d(TAG, "获取候选词列表失败: 解码器未初始化")
            return emptyList()
        }

        if (maxCount <= 0) {
            Log.d(TAG, "获取候选词列表失败: maxCount <= 0")
            return emptyList()
        }

        val candidates = mutableListOf<String>()
        val count = maxCount.coerceAtMost(20) // 限制最大数量

        Log.d(TAG, "获取最多 $count 个候选词")

        for (i in 0 until count) {
            val candidate = getCandidate(i)
            if (candidate.isEmpty()) {
                Log.d(TAG, "候选词 $i 为空，停止获取")
                break
            }
            candidates.add(candidate)
        }

        Log.d(TAG, "获取到 ${candidates.size} 个候选词")
        return candidates
    }

    /**
     * 智能获取候选词
     * @param pinyin 拼音字符串
     * @return 候选词列表
     */
    fun getSmartCandidates(pinyin: String): List<String> {
        if (!initialized) {
            Log.d(TAG, "智能获取候选词失败: 解码器未初始化")
            return listOf("拼音解码器未初始化")
        }

        Log.d(TAG, "智能搜索拼音: '$pinyin'")
        val result = search(pinyin)
        Log.d(TAG, "拼音搜索 '$pinyin': 找到 $result 个候选词")

        return if (result > 0) {
            getCandidates(result.coerceAtMost(10))
        } else {
            Log.d(TAG, "拼音 '$pinyin' 没有找到候选词")
            emptyList()
        }
    }

    /**
     * 获取预测词
     * @param fixedStr 固定字符串
     * @param maxCount 最大预测词数量
     * @return 预测词列表
     */
    fun getPredictions(fixedStr: String, maxCount: Int = 5): List<String> {
        if (!initialized) {
            Log.d(TAG, "获取预测词失败: 解码器未初始化")
            return emptyList()
        }

        try {
            val predictsNum = nativeImGetPredictsNum(fixedStr)
            Log.d(TAG, "预测词数量: $predictsNum")

            val predictions = mutableListOf<String>()
            val count = predictsNum.coerceAtMost(maxCount)

            for (i in 0 until count) {
                val prediction = try {
                    nativeImGetPredictItem(i)
                } catch (e: Exception) {
                    Log.e(TAG, "获取预测项 $i 时出错: ${e.message}")
                    ""
                }

                if (prediction.isNotEmpty()) {
                    predictions.add(prediction)
                }
            }

            Log.d(TAG, "获取到 ${predictions.size} 个预测词: $predictions")
            return predictions
        } catch (e: Exception) {
            Log.e(TAG, "获取预测词时出错: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 获取上下文预测（用于兼容旧的接口）
     * 这是为了保持与NasInputMethodService的兼容性
     */
    fun getCandidatesWithBigram(currentWord: String): List<String> {
        if (!initialized) {
            Log.d(TAG, "获取上下文预测失败: 解码器未初始化")
            return emptyList()
        }

        Log.d(TAG, "获取上下文预测 (兼容接口): 前词='$currentWord'")

        // 调用预测功能
        return getPredictions(currentWord, 5)
    }

    /**
     * 提交选择的词
     * @param word 要提交的词语
     */
    fun commitWord(word: String) {
        if (!initialized) {
            Log.d(TAG, "提交词语失败: 解码器未初始化")
            return
        }

        // 这里可以添加用户词典学习功能
        Log.d(TAG, "提交词语: '$word' (长度: ${word.length})")
    }

    /**
     * 重置搜索状态
     */
    fun resetSearch() {
        if (initialized) {
            try {
                nativeImResetSearch()
                Log.d(TAG, "重置搜索状态")
            } catch (e: Exception) {
                Log.e(TAG, "重置搜索状态时出错: ${e.message}")
            }
        }
    }

    /**
     * 重置输入状态（用于兼容旧的接口）
     * 这个函数已经存在，但在NasInputMethodService中可能被调用了
     */
    fun resetInputState() {
        resetSearch()
    }

    /**
     * 添加字母
     * @param ch 字母
     * @return 添加后的状态
     */
    fun addLetter(ch: Byte): Int {
        if (!initialized) {
            Log.d(TAG, "添加字母失败: 解码器未初始化")
            return 0
        }

        return try {
            nativeImAddLetter(ch)
        } catch (e: Exception) {
            Log.e(TAG, "添加字母时出错: ${e.message}")
            0
        }
    }

    /**
     * 清理资源
     */
    fun close() {
        if (initialized) {
            try {
                nativeImCloseDecoder()
                initialized = false
                Log.d(TAG, "关闭拼音解码器")
            } catch (e: Exception) {
                Log.e(TAG, "关闭解码器时出错: ${e.message}")
            }
        }
    }

    // ==================== 调试方法 ====================

    /**
     * 获取调试信息
     */
    fun debugDecoder(): String {
        return buildString {
            append("=== 拼音解码器调试信息 ===\n")
            append("Kotlin初始化状态: $initialized\n")
            append("原生初始化状态: ${nativeImIsInitialized()}\n")
            append("版本: ${getVersion()}\n")
            append("错误信息: ${initializationError ?: "无"}\n")

            if (initialized) {
                // 测试简单的拼音
                val testCases = listOf("nihao", "zhongguo", "wo", "ni", "xiexie")

                for (pinyin in testCases) {
                    val result = search(pinyin)
                    append("\n测试拼音 '$pinyin': $result 个候选词\n")

                    if (result > 0) {
                        val candidates = getCandidates(result.coerceAtMost(3))
                        for ((index, candidate) in candidates.withIndex()) {
                            append("  [$index] $candidate\n")
                        }
                    } else {
                        append("  没有找到候选词\n")
                    }
                }
            } else {
                append("\n解码器未初始化，无法测试\n")
            }
        }
    }

    /**
     * 获取初始化状态详情
     */
    fun getInitializationStatus(): String {
        return buildString {
            append("初始状态: ")
            if (initialized) {
                append("已初始化\n")
                append("原生库加载: 成功\n")
                append("词典文件: 已加载\n")
                append("版本: ${getVersion()}\n")

                // 测试功能
                val testResult = search("nihao")
                append("功能测试: ${if (testResult > 0) "正常" else "异常"}\n")
            } else {
                append("未初始化\n")
                append("错误: ${initializationError ?: "未知错误"}\n")
                append("原生库加载: ${if (initializationError?.contains("加载原生库") == true) "失败" else "成功"}\n")
            }
        }
    }
}