package com.example.nasboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import com.example.nasboard.ime.conversion.ConversionBarView
import com.example.nasboard.ime.conversion.ConversionManager
import com.example.nasboard.ime.candidate.CandidateView
import com.example.nasboard.ime.dictionary.PinyinDecoder
import com.example.nasboard.ime.emoji.Emoji
import com.example.nasboard.ime.emoji.EmojiHistoryManager
import com.example.nasboard.ime.emoji.EmojiManager
import com.example.nasboard.ime.emoji.EmojiView
import com.example.nasboard.ime.dictionary.EnglishDictionaryManager
import com.example.nasboard.ime.dictionary.RussianDictionaryManager
import com.example.nasboard.ime.dictionary.KazakhUserDictManager
import com.example.nasboard.ime.theme.ThemeManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException


class NasInputMethodService : InputMethodService() {

    private var keyboardView: NasKeyboardView? = null
    private var candidateView: CandidateView? = null
    private var conversionBarView: ConversionBarView? = null
    private var containerView: LinearLayout? = null
    private var emojiView: EmojiView? = null

    // 当前键盘布局类型
    private var currentKeyboardType: KeyboardType = KeyboardType.CYRILLIC_KAZAKH

    // 当前输入的字符序列（通用）
    private var currentInput = StringBuilder()

    // 中文输入相关状态管理
    private var chineseInputBuffer = StringBuilder() // 中文拼音输入缓冲区
    private var chineseComposingState = ChineseComposingState.IDLE
    private var lastChineseWord: String? = null

    private enum class ChineseComposingState {
        IDLE,          // 空闲状态
        COMPOSING,     // 正在输入拼音
        CANDIDATE,     // 显示候选词
        PREDICT        // 上下文预测
    }

    // 长按删除相关
    private val handler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private var isDeletePressed = false
    private var deleteStartTime: Long = 0
    private var deleteAccelerationThreshold = 1000L // 1秒后开始加速删除
    private var isFastDeleteMode = false
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )
    private val userDictScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )
    private val userDictInitialized = CompletableDeferred<Boolean>()


    // 转换管理器
    private val conversionManager = ConversionManager()

    // 设置管理器
    private lateinit var settingsManager: KeyboardSettingsManager

    // 主题管理器
    private lateinit var themeManager: ThemeManager

    // 拼音解码器（替换原来的SQLite中文词典管理器）
    private lateinit var pinyinDecoder: PinyinDecoder

    // 英文词库管理器
    private lateinit var englishDictionaryManager: EnglishDictionaryManager

    // 俄文词库管理器
    private lateinit var russianDictionaryManager: RussianDictionaryManager

    // 哈萨克语词典管理器
    private lateinit var kazakhDictionaryManager: com.example.nasboard.ime.dictionary.KazakhDictionaryManager

    private lateinit var kazakhUserDictManager: KazakhUserDictManager

    // 表情管理器
    private lateinit var emojiManager: EmojiManager

    // 表情历史管理器
    private lateinit var emojiHistoryManager: EmojiHistoryManager

    // 标点符号和数字列表
    private val punctuationAndDigits = setOf(
        ".", ",", "?", "!", ";", ":", "'", "\"", "(", ")", "[", "]", "{", "}",
        "-", "_", "+", "=", "/", "\\", "|", "@", "#", "$", "%", "^", "&", "*",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    )

    // 是否正在显示表情界面
    private var isShowingEmoji = false

    // 是否在表情搜索模式
    private var isInEmojiSearchMode = false

    // 上下文预测相关（通用）
    private var lastSubmittedWord: String? = null
    private var isShowingContextPredictions = false
    private val candidateUpdateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var candidateUpdateJob: Job? = null
    // 添加辅助变量
    private var actualWordForAsync: String? = null


    override fun onCreate() {
        super.onCreate()

        // ------------------------------
        // 原有初始化逻辑
        // ------------------------------
        settingsManager = KeyboardSettingsManager.getInstance(this)
        themeManager = ThemeManager.getInstance(this)

        // 使用新的拼音解码器
        pinyinDecoder = PinyinDecoder.getInstance(this)

        emojiManager = EmojiManager(this)
        emojiHistoryManager = EmojiHistoryManager.getInstance(this)
        emojiHistoryManager.setEmojiManager(emojiManager)

        // 初始化英文词库管理器
        englishDictionaryManager = EnglishDictionaryManager.getInstance(this)

        // 初始化俄文词库管理器
        russianDictionaryManager = RussianDictionaryManager.getInstance(this)

        // 初始化哈萨克语词库管理器
        kazakhDictionaryManager = com.example.nasboard.ime.dictionary.KazakhDictionaryManager.getInstance(this)
        kazakhUserDictManager = KazakhUserDictManager.getInstance(this, userDictScope)


        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 初始化主词典
                val kazakhSuccess = kazakhDictionaryManager.loadDictionary()
                if (kazakhSuccess) {
                    Log.d("NasInputMethod", "哈萨克语词库初始化成功")
                } else {
                    Log.e("NasInputMethod", "哈萨克语词库初始化失败")
                }

                // 初始化英文、俄文词库
                englishDictionaryManager.loadDictionary()
                russianDictionaryManager.loadDictionary()

                // 初始化拼音解码器
                val pinyinSuccess = pinyinDecoder.initialize()
                if (pinyinSuccess) {
                    Log.d("NasInputMethod", "拼音解码器初始化成功")
                } else {
                    Log.e("NasInputMethod", "拼音解码器初始化失败")
                }

                // 初始化用户词典
                Log.d("NasInputMethod", "开始初始化用户词典...")
                val userDictSuccess = kazakhUserDictManager.initialize()
                userDictInitialized.complete(userDictSuccess)

                if (userDictSuccess) {
                    Log.d("NasInputMethod", "✅ 哈萨克语用户词典初始化成功")
                    kazakhUserDictManager.logDebugInfo()
                    Log.d("NasInputMethod", "用户词典统计:\n${kazakhUserDictManager.getStats()}")

                    // 测试搜索
                    try {
                        val testResults = kazakhUserDictManager.searchPrefix("ал", 2)
                        Log.d("NasInputMethod", "用户词典快速搜索测试 'ал': ${testResults.joinToString(", ")}")
                    } catch (e: Exception) {
                        Log.e("NasInputMethod", "用户词典搜索测试失败: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                userDictInitialized.complete(false)
                Log.e("NasInputMethod", "词库初始化异常: ${e.message}")
            }
        }
    }



    override fun onCreateInputView(): View {
        Log.d("NasInputMethod", "onCreateInputView called - creating keyboard container")

        return try {
            // 创建容器布局，包含候选词栏、转换栏和键盘
            containerView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.TRANSPARENT)
            }

            // 创建候选词栏 - 设置固定高度
            candidateView = CandidateView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(60) // 固定高度
                )
                // 初始设置为非中文模式
                setChineseMode(false)

                setOnCandidateClickListener { candidate ->
                    handleCandidateClick(candidate)
                }

                setOnExpandClickListener {
                    // 切换扩展状态
                    toggleExpanded()
                }
            }
            containerView?.addView(candidateView)

            // 创建转换栏 - 设置固定高度
            conversionBarView = ConversionBarView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(40) // 固定高度
                )
                setOnConversionLanguageSelectedListener(object : ConversionBarView.OnConversionLanguageSelectedListener {
                    override fun onConversionLanguageSelected(language: KeyboardType) {
                        handleConversionLanguageSelected(language)
                    }

                    override fun onConversionModeToggled() {
                        // 切换转换模式时不需要特别处理
                    }

                    override fun onConversionCancelled() {
                        handleConversionCancelled()
                    }

                    override fun onLanguageSelectorCancelled() {
                        // 语言选择器取消，不需要特别处理
                    }
                })
                // 初始设置当前键盘类型和可用语言
                setCurrentKeyboardType(currentKeyboardType, conversionManager.getAvailableTargetLanguages())
                // 初始更新转换状态
                updateConversionState(conversionManager.getCurrentConversionState())
            }
            containerView?.addView(conversionBarView)

            // 创建键盘视图 - 使用权重占据剩余空间
            keyboardView = NasKeyboardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply {
                    weight = 1f // 使用权重占据剩余空间
                }

                setOnKeyPressListener(object : NasKeyboardView.OnKeyPressListener {
                    override fun onKeyPress(key: String) {
                        handleKeyPress(key)
                    }

                    override fun onKeyboardTypeChange(newType: KeyboardType) {
                        handleKeyboardTypeChange(newType)
                    }

                    override fun onKeyLongPress(key: String) {
                        handleKeyLongPress(key)
                    }

                    override fun onKeyRelease(key: String) {
                        handleKeyRelease(key)
                    }
                })

                // 设置初始主题
                setTheme(themeManager.getCurrentThemeName())
            }
            containerView?.addView(keyboardView)

            // 创建表情视图（初始隐藏）
            emojiView = EmojiView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
                setOnEmojiClickListener(object : EmojiView.OnEmojiClickListener {
                    override fun onEmojiClick(emoji: Emoji) {
                        handleEmojiInput(emoji)
                    }

                    override fun onBackToKeyboard() {
                        showKeyboardView()
                    }

                    override fun onSearchKeyPress(key: String) {
                        handleSearchKeyPress(key)
                    }
                })
            }
            containerView?.addView(emojiView)

            containerView!!
        } catch (e: Exception) {
            Log.e("NasInputMethod", "Error creating keyboard container: ${e.message}")
            createSimpleTestView()
        }
    }

    // 候选词点击处理
    private fun handleCandidateClick(candidate: String) {
        when (currentKeyboardType) {
            KeyboardType.CHINESE -> {
                handleChineseCandidateClick(candidate)
            }
            KeyboardType.CYRILLIC_KAZAKH -> {
                handleKazakhCandidateClick(candidate)
            }
            KeyboardType.ARABIC, KeyboardType.LATIN -> {
                handleOtherKazakhCandidateClick(candidate)
            }
            KeyboardType.ENGLISH -> {
                handleEnglishCandidateClick(candidate)
            }
            KeyboardType.RUSSIAN -> {
                handleRussianCandidateClick(candidate)
            }
        }

        // 关键修复：如果是在展开模式下点击候选词，自动收起并返回键盘
        if (candidateView?.isExpanded == true) {
            toggleExpanded()
        }
    }

    private fun handleChineseCandidateClick(candidate: String) {
        if (candidate.isNotEmpty()) {
            // 提交选中的词
            currentInputConnection?.commitText(candidate, 1)

            // 更新中文输入状态
            lastChineseWord = candidate
            pinyinDecoder.commitWord(candidate)

            // 记录最后提交的词用于上下文预测（兼容其他逻辑）
            lastSubmittedWord = candidate

            // 清空输入缓冲区
            chineseInputBuffer.clear()
            currentInput.clear()
            chineseComposingState = ChineseComposingState.IDLE

            // 显示后续词推荐（使用拼音引擎的预测功能）
            val nextWordCandidates = pinyinDecoder.getCandidatesWithBigram(candidate)
            if (nextWordCandidates.isNotEmpty()) {
                candidateView?.updateCandidates(nextWordCandidates)
                chineseComposingState = ChineseComposingState.PREDICT
            } else {
                updateCandidateView()
            }

            Log.d("NasInputMethod", "中文候选词提交: $candidate")
        }
    }

    // 哈萨克语候选词点击处理 - 修复版本
    private fun handleKazakhCandidateClick(candidate: String) {
        Log.d("NasInputMethod", "=== handleKazakhCandidateClick 开始 ===")
        Log.d("NasInputMethod", "候选词点击: '$candidate'")

        if (candidate.isEmpty()) {
            Log.d("NasInputMethod", "候选词为空，跳过")
            return
        }

        // 提取实际单词
        val actualWord = if (candidate.startsWith("[ID:") && candidate.endsWith("]")) {
            candidate.substringAfter("[ID:").substringBefore("]")
        } else {
            candidate
        }

        Log.d("NasInputMethod", "实际单词: '$actualWord'")

        // 根据转换状态提交文本
        val textToCommit = if (conversionManager.getCurrentConversionState().isConversionMode) {
            conversionManager.convertText(actualWord)
        } else {
            actualWord
        }

        Log.d("NasInputMethod", "提交文本: '$textToCommit'")
        currentInputConnection?.commitText("$textToCommit ", 1)

        // 取消之前的候选词更新任务
        candidateUpdateJob?.cancel()
        candidateUpdateJob = null

        // ⭐ 调用独立学习函数
        val previousWord = lastSubmittedWord
        lastSubmittedWord = actualWord
        learnUserWord(actualWord, previousWord)

        // 清空当前输入，更新上下文状态
        currentInput.clear()
        isShowingContextPredictions = true

        Log.d("NasInputMethod", "更新候选词视图")
        updateCandidateView()

        Log.d("NasInputMethod", "=== handleKazakhCandidateClick 完成 ===")
    }

    private fun learnUserWord(word: String, previousWord: String?) {
        userDictScope.launch {
            try {
                // 等待用户词典初始化完成
                val initialized = userDictInitialized.await()
                if (!initialized) {
                    Log.e("UserDict", "❌ 用户词典未初始化，放弃学习: $word")
                    return@launch
                }

                Log.d("UserDict", "🧠 开始学习单词: $word")

                // 通知主词典
                kazakhDictionaryManager.processWordSubmission(word)

                // 添加词频
                kazakhUserDictManager.addWord(word, 1)

                // 添加上下文
                if (!previousWord.isNullOrEmpty()) {
                    kazakhUserDictManager.addWordWithContext(word, previousWord, 1)
                }

                // 可选：短暂延迟 + 测试搜索
                kotlinx.coroutines.delay(50)
                val prefixResults = kazakhUserDictManager.searchPrefix("қота", 5)
                Log.d("UserDict", "搜索前缀 'қота': ${prefixResults.joinToString(", ")}")

                val stats = kazakhUserDictManager.getStats()
                Log.d("UserDict", "用户词典统计:\n$stats")

                Log.d("UserDict", "✅ 学习完成: $word")
            } catch (e: CancellationException) {
                Log.e("UserDict", "⚠️ 学习协程被取消", e)
            } catch (e: Exception) {
                Log.e("UserDict", "❌ 学习异常", e)
            }
        }
    }

    // 添加性能监控
    private fun monitorCandidateUpdatePerformance() {
        val startTime = System.currentTimeMillis()

        // 设置超时检查
        CoroutineScope(Dispatchers.IO).launch {
            delay(100) // 100ms超时

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > 50) {
                Log.w("NasInputMethod", "候选词更新耗时过长: ${elapsed}ms")

                // 强制更新UI
                withContext(Dispatchers.Main) {
                    candidateView?.updateCandidates(emptyList())
                }
            }
        }
    }


    // 修复：添加转换检查
    private fun handleOtherKazakhCandidateClick(candidate: String) {
        if (candidate.isNotEmpty()) {
            // 修复：添加转换检查
            val textToCommit = if (conversionManager.getCurrentConversionState().isConversionMode) {
                conversionManager.convertText(candidate) + " "
            } else {
                candidate + " "
            }
            currentInputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词用于上下文预测
            lastSubmittedWord = candidate

            currentInput.clear()

            // 关键修复：提交后立即显示上下文预测（之前缺失了这一行）
            isShowingContextPredictions = true
            updateCandidateView()
        }
    }

    // 新增：英文候选词点击处理
    private fun handleEnglishCandidateClick(candidate: String) {
        if (candidate.isNotEmpty()) {
            currentInputConnection?.commitText("$candidate ", 1)

            // 记录最后提交的词用于上下文预测
            lastSubmittedWord = candidate
            englishDictionaryManager.processWordSubmission(candidate)

            currentInput.clear()
            isShowingContextPredictions = true
            updateCandidateView()
        }
    }

    // 新增：俄文候选词点击处理
    private fun handleRussianCandidateClick(candidate: String) {
        if (candidate.isNotEmpty()) {
            currentInputConnection?.commitText("$candidate ", 1)

            // 记录最后提交的词用于上下文预测
            lastSubmittedWord = candidate
            russianDictionaryManager.processWordSubmission(candidate)

            currentInput.clear()
            isShowingContextPredictions = true
            updateCandidateView()
        }
    }

    // 新增：简单候选词点击处理（用于英文和俄文）
    private fun handleSimpleCandidateClick(candidate: String) {
        if (candidate.isNotEmpty()) {
            currentInputConnection?.commitText("$candidate ", 1)
            lastSubmittedWord = candidate
            currentInput.clear()
            updateCandidateView()
        }
    }

    private fun handleGeneralCandidateClick(candidate: String) {
        if (candidate.isNotEmpty()) {
            val textToCommit = convertTextForOutput(candidate) + " "
            currentInputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词用于上下文预测
            lastSubmittedWord = candidate

            currentInput.clear()

            // 关键修复：提交后立即显示上下文预测
            isShowingContextPredictions = true
            updateCandidateView()
        }
    }

    // 切换候选词扩展状态
    private fun toggleExpanded() {
        // 获取容器高度，用于计算展开区域大小
        val containerHeight = containerView?.height ?: 0

        candidateView?.toggleExpanded(containerHeight)

        // 根据展开状态调整其他视图的可见性
        if (candidateView?.isExpanded == true) {
            // 展开时隐藏键盘和转换栏，让候选词占据整个空间
            keyboardView?.visibility = View.GONE
            conversionBarView?.visibility = View.GONE

            // 设置候选词视图的高度为MATCH_PARENT，确保覆盖整个区域
            candidateView?.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            // 关键修复：展开后重新更新候选词，获取更多候选词
            updateCandidateViewWithMoreCandidates()
        } else {
            // 收起时显示键盘和转换栏，恢复候选词固定高度
            keyboardView?.visibility = View.VISIBLE
            conversionBarView?.visibility = View.VISIBLE

            // 恢复候选词视图的固定高度
            candidateView?.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(60)
            )

            // 恢复正常候选词显示
            updateCandidateView()
        }

        // 请求重新布局
        containerView?.requestLayout()
    }

    // 新增方法：展开模式下获取更多候选词
    private fun updateCandidateViewWithMoreCandidates() {
        Log.d("NasInputMethod", "Updating candidate view with MORE candidates for expanded mode")

        when (currentKeyboardType) {
            KeyboardType.CHINESE -> {
                // 中文输入模式：分开显示拼音和候选词
                val pinyin = chineseInputBuffer.toString()

                // 更新拼音显示
                candidateView?.updatePinyin(pinyin)

                if (pinyin.isNotEmpty()) {
                    // 使用拼音解码器获取候选词
                    val candidates = pinyinDecoder.getSmartCandidates(pinyin)
                    candidateView?.updateCandidates(candidates)
                    Log.d("NasInputMethod", "拼音解码器找到中文候选词: $candidates for pinyin: $pinyin")
                } else {
                    // 没有拼音输入时，清空候选词
                    candidateView?.updateCandidates(emptyList())
                }
            }

            KeyboardType.CYRILLIC_KAZAKH -> {
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    Log.d("NasInputMethod", "展开模式哈萨克语纯上下文预测 (前词: $lastSubmittedWord)")

                    // 展开模式下获取更多候选词
                    val maxResults = 15

                    // 1. 用户词典上下文搜索
                    val userDictResults = mutableListOf<String>()
                    if (kazakhUserDictManager.isUserDictLoaded()) {
                        userDictResults.addAll(kazakhUserDictManager.searchWithContext(
                            lastSubmittedWord!!, "", 5
                        ))
                        Log.d("NasInputMethod", "展开模式用户词典上下文: ${userDictResults.size} 个结果")
                    }

                    // 2. 主词典上下文预测
                    val mainDictResults = kazakhDictionaryManager.getContextPredictions(
                        lastSubmittedWord!!, "",
                        maxResults - userDictResults.size
                    )

                    // 3. 合并结果
                    val combinedResults = (userDictResults + mainDictResults).take(maxResults)
                    candidateView?.updateCandidates(combinedResults)

                } else if (currentInputText.isNotEmpty()) {
                    Log.d("NasInputMethod", "展开模式哈萨克语前缀搜索 (输入: $currentInputText)")

                    val maxResults = 15

                    // 1. 用户词典前缀搜索
                    val userDictResults = mutableListOf<String>()
                    if (kazakhUserDictManager.isUserDictLoaded()) {
                        userDictResults.addAll(kazakhUserDictManager.searchPrefix(currentInputText, 5))
                        Log.d("NasInputMethod", "展开模式用户词典前缀: ${userDictResults.size} 个结果")
                    }

                    // 2. 用户词典上下文搜索（如果有前一个词）
                    val userDictContextResults = mutableListOf<String>()
                    if (lastSubmittedWord != null && kazakhUserDictManager.isUserDictLoaded()) {
                        userDictContextResults.addAll(kazakhUserDictManager.searchWithContext(
                            lastSubmittedWord!!, currentInputText, 3
                        ))
                        Log.d("NasInputMethod", "展开模式用户词典上下文: ${userDictContextResults.size} 个结果")
                    }

                    // 3. 主词典搜索
                    val mainDictResults = if (lastSubmittedWord != null && kazakhDictionaryManager.isShowingContextPredictions()) {
                        kazakhDictionaryManager.getContextPredictions(
                            lastSubmittedWord!!, currentInputText,
                            maxResults - userDictResults.size - userDictContextResults.size
                        )
                    } else {
                        kazakhDictionaryManager.getPredictions(
                            currentInputText,
                            maxResults - userDictResults.size - userDictContextResults.size
                        )
                    }

                    // 4. 合并所有结果，去重
                    val allResults = mutableListOf<String>()
                    val seen = mutableSetOf<String>()

                    // 添加当前输入
                    if (currentInputText.isNotEmpty() && currentInputText !in seen) {
                        allResults.add(currentInputText)
                        seen.add(currentInputText)
                    }

                    // 添加用户词典上下文结果
                    for (result in userDictContextResults) {
                        if (result !in seen) {
                            allResults.add(result)
                            seen.add(result)
                        }
                    }

                    // 添加用户词典前缀结果
                    for (result in userDictResults) {
                        if (result !in seen) {
                            allResults.add(result)
                            seen.add(result)
                        }
                    }

                    // 添加主词典结果
                    for (result in mainDictResults) {
                        if (result !in seen) {
                            allResults.add(result)
                            seen.add(result)
                        }
                    }

                    candidateView?.updateCandidates(allResults.take(maxResults))

                } else {
                    Log.d("NasInputMethod", "展开模式哈萨克语无输入，返回空列表")
                    candidateView?.updateCandidates(emptyList())
                }
            }

            KeyboardType.ARABIC, KeyboardType.LATIN -> {
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    // 修复：使用 getContextPredictions 代替 getPureContextPredictions
                    val contextPredictions = kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 5)
                    candidateView?.updateCandidates(contextPredictions)
                    Log.d("NasInputMethod", "Expanded${currentKeyboardType}纯上下文预测 (前词: $lastSubmittedWord): ${contextPredictions.take(3)}...")
                } else if (currentInputText.isNotEmpty()) {
                    candidateView?.updateCandidates(emptyList())
                    Log.d("NasInputMethod", "Expanded${currentKeyboardType}预测 '$currentInputText' (上下文: $lastSubmittedWord): 已禁用")
                } else {
                    candidateView?.updateCandidates(emptyList())
                }
            }

            KeyboardType.ENGLISH -> {
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    val contextPredictions = englishDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 20)
                    candidateView?.updateCandidates(contextPredictions)
                    Log.d("NasInputMethod", "Expanded英文纯上下文预测 (前词: $lastSubmittedWord): ${contextPredictions.take(5)}...")
                } else if (currentInputText.isNotEmpty()) {
                    val maxPredictions = 20
                    val predictions = if (lastSubmittedWord != null && currentInputText.isNotEmpty()) {
                        englishDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, maxPredictions)
                    } else {
                        englishDictionaryManager.getPredictions(currentInputText, null, maxPredictions)
                    }
                    candidateView?.updateCandidates(listOf(currentInputText) + predictions)
                    Log.d("NasInputMethod", "Expanded英文预测 '$currentInputText' (上下文: $lastSubmittedWord): ${predictions.take(5)}...")
                } else {
                    candidateView?.updateCandidates(emptyList())
                }
            }

            KeyboardType.RUSSIAN -> {
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    val contextPredictions = russianDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 20)
                    candidateView?.updateCandidates(contextPredictions)
                    Log.d("NasInputMethod", "Expanded俄文纯上下文预测 (前词: $lastSubmittedWord): ${contextPredictions.take(5)}...")
                } else if (currentInputText.isNotEmpty()) {
                    val maxPredictions = 20
                    val predictions = if (lastSubmittedWord != null && currentInputText.isNotEmpty()) {
                        russianDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, maxPredictions)
                    } else {
                        russianDictionaryManager.getPredictions(currentInputText, null, maxPredictions)
                    }
                    candidateView?.updateCandidates(listOf(currentInputText) + predictions)
                    Log.d("NasInputMethod", "Expanded俄文预测 '$currentInputText' (上下文: $lastSubmittedWord): ${predictions.take(5)}...")
                } else {
                    candidateView?.updateCandidates(emptyList())
                }
            }

            else -> {
                val previewText = currentInput.toString()
                candidateView?.updateSimplePreview(previewText)
            }
        }
    }


    // 确保当前键盘类型是启用的类型
    private fun ensureValidKeyboardType() {
        val enabledTypes = settingsManager.getEnabledKeyboardTypes()
        if (enabledTypes.isNotEmpty() && currentKeyboardType !in enabledTypes) {
            currentKeyboardType = enabledTypes.first()
        }
    }

    private fun handleKeyPress(key: String) {
        // 如果正在显示表情界面，将按键传递给表情界面处理搜索
        if (isShowingEmoji) {
            handleSearchKeyPress(key)
            return
        }

        val inputConnection = currentInputConnection

        when (key) {
            "EMOJI" -> {
                showEmojiView()
            }
            "SPACE" -> {
                handleSpaceKey()
            }
            "DEL" -> {
                handleDeleteKey()
            }
            "ENTER" -> {
                handleEnterKey()
            }
            "\n" -> {
                inputConnection?.commitText("\n", 1)
                resetContext()
            }
            else -> {
                // 检查是否是标点符号或数字
                if (key in punctuationAndDigits) {
                    handlePunctuationOrDigit(key)
                } else {
                    // 字母键：添加到当前输入
                    if (key.length == 1) {
                        handleLetterKey(key)
                    } else {
                        // 其他键直接提交
                        inputConnection?.commitText(key, 1)
                    }
                }
            }
        }
    }

    // 处理空格键
    private fun handleSpaceKey() {
        val inputConnection = currentInputConnection

        when (currentKeyboardType) {
            KeyboardType.CHINESE -> {
                handleChineseSpaceKey()
            }
            KeyboardType.CYRILLIC_KAZAKH -> {
                handleKazakhSpaceKey()
            }
            KeyboardType.ARABIC, KeyboardType.LATIN -> {
                handleOtherKazakhSpaceKey()
            }
            KeyboardType.ENGLISH -> {
                handleEnglishSpaceKey()
            }
            KeyboardType.RUSSIAN -> {
                handleRussianSpaceKey()
            }
            else -> {
                handleGeneralSpaceKey()
            }
        }
    }

    private fun handleChineseSpaceKey() {
        val inputConnection = currentInputConnection

        if (chineseInputBuffer.isNotEmpty()) {
            // 如果有输入，提交第一个候选词
            val candidates = pinyinDecoder.getSmartCandidates(chineseInputBuffer.toString())
            if (candidates.isNotEmpty()) {
                inputConnection?.commitText(candidates[0], 1)

                // 更新中文输入状态
                lastChineseWord = candidates[0]
                pinyinDecoder.commitWord(candidates[0])
                lastSubmittedWord = candidates[0]

                chineseInputBuffer.clear()
                chineseComposingState = ChineseComposingState.IDLE

                // 显示后续词推荐
                val nextWordCandidates = pinyinDecoder.getCandidatesWithBigram(candidates[0])
                if (nextWordCandidates.isNotEmpty()) {
                    candidateView?.updateCandidates(nextWordCandidates)
                    chineseComposingState = ChineseComposingState.PREDICT
                } else {
                    updateCandidateView()
                }

                Log.d("NasInputMethod", "空格键提交中文候选词: ${candidates[0]}")
            } else {
                // 没有候选词，直接提交拼音
                inputConnection?.commitText(chineseInputBuffer.toString() + " ", 1)
                chineseInputBuffer.clear()
                chineseComposingState = ChineseComposingState.IDLE
                updateCandidateView()
            }
        } else {
            inputConnection?.commitText(" ", 1)
            resetChineseContext()
        }
    }

    private fun handleKazakhSpaceKey() {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 哈萨克语模式：提交当前输入
            val textToCommit = if (conversionManager.getCurrentConversionState().isConversionMode) {
                conversionManager.convertText(currentInput.toString()) + " "
            } else {
                currentInput.toString() + " "
            }
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()

            currentInput.clear()

            // 关键修复：空格提交后显示上下文预测
            isShowingContextPredictions = true
            updateCandidateView()
        } else {
            inputConnection?.commitText(" ", 1)
            resetContext()
        }
    }

    // 修复：添加转换检查
    private fun handleOtherKazakhSpaceKey() {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 修复：添加转换检查
            val textToCommit = if (conversionManager.getCurrentConversionState().isConversionMode) {
                conversionManager.convertText(currentInput.toString()) + " "
            } else {
                currentInput.toString() + " "
            }
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()

            currentInput.clear()

            // 关键修复：空格提交后显示上下文预测
            isShowingContextPredictions = true
            updateCandidateView()
        } else {
            inputConnection?.commitText(" ", 1)
            resetContext()
        }
    }

    // 新增：英文空格键处理
    private fun handleEnglishSpaceKey() {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 直接提交当前输入
            val textToCommit = currentInput.toString() + " "
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()
            englishDictionaryManager.processWordSubmission(currentInput.toString())

            currentInput.clear()
            isShowingContextPredictions = true
            updateCandidateView()
        } else {
            inputConnection?.commitText(" ", 1)
            resetContext()
        }
    }

    // 新增：俄文空格键处理
    private fun handleRussianSpaceKey() {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 直接提交当前输入
            val textToCommit = currentInput.toString() + " "
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()
            russianDictionaryManager.processWordSubmission(currentInput.toString())

            currentInput.clear()
            isShowingContextPredictions = true
            updateCandidateView()
        } else {
            inputConnection?.commitText(" ", 1)
            resetContext()
        }
    }

    private fun handleGeneralSpaceKey() {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            val textToCommit = convertTextForOutput(currentInput.toString()) + " "
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()

            currentInput.clear()
            updateCandidateView()
        } else {
            inputConnection?.commitText(" ", 1)
            resetContext()
        }
    }

    private fun handleEnterKey() {
        val inputConnection = currentInputConnection

        if (currentKeyboardType == KeyboardType.CHINESE && chineseInputBuffer.isNotEmpty()) {
            // 中文模式：回车提交当前拼音
            inputConnection?.commitText(chineseInputBuffer.toString(), 1)
            chineseInputBuffer.clear()
            chineseComposingState = ChineseComposingState.IDLE
            updateCandidateView()
        } else {
            inputConnection?.commitText("\n", 1)
        }
        resetContext()
    }

    // 处理字母键
    private fun handleLetterKey(key: String) {
        when (currentKeyboardType) {
            KeyboardType.CHINESE -> {
                handleChineseLetterKey(key)
            }
            KeyboardType.CYRILLIC_KAZAKH -> {
                handleKazakhLetterKey(key)
            }
            KeyboardType.ARABIC, KeyboardType.LATIN -> {
                handleOtherKazakhLetterKey(key)
            }
            KeyboardType.ENGLISH -> {
                handleEnglishLetterKey(key)
            }
            KeyboardType.RUSSIAN -> {
                handleRussianLetterKey(key)
            }
            else -> {
                handleGeneralLetterKey(key)
            }
        }
    }

    private fun handleChineseLetterKey(key: String) {
        // 中文模式：添加到拼音输入缓冲区
        chineseInputBuffer.append(key)

        // 关键修复：开始输入新词时，停止显示纯上下文预测
        isShowingContextPredictions = false
        chineseComposingState = ChineseComposingState.COMPOSING

        updateCandidateView()
    }

    private fun handleKazakhLetterKey(key: String) {
        currentInput.append(key)
        // 关键修复：开始输入新词时，停止显示纯上下文预测
        isShowingContextPredictions = false
        updateCandidateView()
    }

    private fun handleOtherKazakhLetterKey(key: String) {
        currentInput.append(key)
        // 关键修复：开始输入新词时，停止显示纯上下文预测
        isShowingContextPredictions = false
        updateCandidateView()
    }

    // 新增：英文字母键处理
    private fun handleEnglishLetterKey(key: String) {
        currentInput.append(key)
        // 关键修复：开始输入新词时，停止显示纯上下文预测
        isShowingContextPredictions = false
        updateCandidateView()
    }

    // 新增：俄文字母键处理
    private fun handleRussianLetterKey(key: String) {
        currentInput.append(key)
        // 关键修复：开始输入新词时，停止显示纯上下文预测
        isShowingContextPredictions = false
        updateCandidateView()
    }

    private fun handleGeneralLetterKey(key: String) {
        currentInput.append(key)
        // 关键修复：开始输入新词时，停止显示纯上下文预测
        isShowingContextPredictions = false
        updateCandidateView()
    }

    // 处理标点符号或数字
    private fun handlePunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        // 根据键盘类型处理
        when (currentKeyboardType) {
            KeyboardType.CHINESE -> {
                handleChinesePunctuationOrDigit(key)
            }
            KeyboardType.CYRILLIC_KAZAKH -> {
                handleKazakhPunctuationOrDigit(key)
            }
            KeyboardType.ARABIC, KeyboardType.LATIN -> {
                handleOtherKazakhPunctuationOrDigit(key)
            }
            KeyboardType.ENGLISH -> {
                handleEnglishPunctuationOrDigit(key)
            }
            KeyboardType.RUSSIAN -> {
                handleRussianPunctuationOrDigit(key)
            }
            else -> {
                handleGeneralPunctuationOrDigit(key)
            }
        }
    }

    private fun handleChinesePunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        // 如果有当前输入，先提交当前输入
        if (chineseInputBuffer.isNotEmpty()) {
            val candidates = pinyinDecoder.getSmartCandidates(chineseInputBuffer.toString())
            if (candidates.isNotEmpty()) {
                inputConnection?.commitText(candidates[0] + key, 1)

                // 更新中文输入状态
                lastChineseWord = candidates[0]
                pinyinDecoder.commitWord(candidates[0])
                lastSubmittedWord = candidates[0]
            } else {
                inputConnection?.commitText(chineseInputBuffer.toString() + key, 1)
                lastSubmittedWord = chineseInputBuffer.toString()
            }
            chineseInputBuffer.clear()
            chineseComposingState = ChineseComposingState.IDLE
        } else {
            // 提交标点符号或数字
            val convertedPunctuation = conversionManager.convertPunctuation(key)
            inputConnection?.commitText(convertedPunctuation, 1)
        }

        // 某些标点符号表示句子结束，重置上下文
        if (key in setOf(".", "!", "?", "。", "！", "？")) {
            resetChineseContext()
        }

        updateCandidateView()
    }

    private fun handleKazakhPunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 哈萨克语模式：直接提交当前输入
            val textToCommit = if (conversionManager.getCurrentConversionState().isConversionMode) {
                conversionManager.convertText(currentInput.toString())
            } else {
                currentInput.toString()
            }
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()

            // 关键修复：标点符号提交后显示上下文预测
            isShowingContextPredictions = true
            currentInput.clear()
            updateCandidateView()
        }

        // 提交标点符号或数字（不添加空格，应用标点符号转换）
        val convertedPunctuation = conversionManager.convertPunctuation(key)
        inputConnection?.commitText(convertedPunctuation, 1)

        // 某些标点符号表示句子结束，重置上下文
        if (key in setOf(".", "!", "?", "。", "！", "？")) {
            resetContext()
        }
    }

    // 修复：添加转换检查
    private fun handleOtherKazakhPunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 修复：添加转换检查
            val textToCommit = if (conversionManager.getCurrentConversionState().isConversionMode) {
                conversionManager.convertText(currentInput.toString())
            } else {
                currentInput.toString()
            }
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()

            // 关键修复：标点符号提交后显示上下文预测
            isShowingContextPredictions = true
            currentInput.clear()
            updateCandidateView()
        }

        // 提交标点符号或数字（不添加空格，应用标点符号转换）
        val convertedPunctuation = conversionManager.convertPunctuation(key)
        inputConnection?.commitText(convertedPunctuation, 1)

        // 某些标点符号表示句子结束，重置上下文
        if (key in setOf(".", "!", "?", "。", "！", "？")) {
            resetContext()
        }
    }

    // 新增：英文标点符号或数字处理
    private fun handleEnglishPunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 直接提交当前输入
            val textToCommit = currentInput.toString()
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()
            englishDictionaryManager.processWordSubmission(currentInput.toString())
            currentInput.clear()
            isShowingContextPredictions = true
            updateCandidateView()
        }

        // 提交标点符号或数字（不添加空格）
        inputConnection?.commitText(key, 1)

        // 某些标点符号表示句子结束，重置上下文
        if (key in setOf(".", "!", "?", "。", "！", "？")) {
            resetContext()
        }
    }

    // 新增：俄文标点符号或数字处理
    private fun handleRussianPunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            // 直接提交当前输入
            val textToCommit = currentInput.toString()
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()
            russianDictionaryManager.processWordSubmission(currentInput.toString())
            currentInput.clear()
            isShowingContextPredictions = true
            updateCandidateView()
        }

        // 提交标点符号或数字（不添加空格）
        inputConnection?.commitText(key, 1)

        // 某些标点符号表示句子结束，重置上下文
        if (key in setOf(".", "!", "?", "。", "！", "？")) {
            resetContext()
        }
    }

    private fun handleGeneralPunctuationOrDigit(key: String) {
        val inputConnection = currentInputConnection

        if (currentInput.isNotEmpty()) {
            val textToCommit = convertTextForOutput(currentInput.toString())
            inputConnection?.commitText(textToCommit, 1)

            // 记录最后提交的词
            lastSubmittedWord = currentInput.toString()
            currentInput.clear()
            updateCandidateView()
        }

        // 提交标点符号或数字（不添加空格，应用标点符号转换）
        val convertedPunctuation = conversionManager.convertPunctuation(key)
        inputConnection?.commitText(convertedPunctuation, 1)

        // 某些标点符号表示句子结束，重置上下文
        if (key in setOf(".", "!", "?", "。", "！", "？")) {
            resetContext()
        }
    }

    // 处理删除键
    private fun handleDeleteKey() {
        val inputConnection = currentInputConnection

        // 首先检查是否有选中的文本
        val selectedText = inputConnection?.getSelectedText(0)
        if (selectedText != null && selectedText.isNotEmpty()) {
            // 如果有选中的文本，删除选中文本
            inputConnection.commitText("", 1)
            return
        }

        if (currentKeyboardType == KeyboardType.CHINESE && chineseInputBuffer.isNotEmpty()) {
            // 中文模式：删除拼音输入缓冲区的一个字符
            chineseInputBuffer.deleteCharAt(chineseInputBuffer.length - 1)
            updateCandidateView()
        } else if (currentInput.isNotEmpty()) {
            // 其他模式：删除当前输入的一个字符
            currentInput.deleteCharAt(currentInput.length - 1)
            updateCandidateView()
        } else {
            // 如果没有当前输入，删除输入框中的字符
            inputConnection?.deleteSurroundingText(1, 0)
        }
    }

    // 处理快速删除（长按删除）
    private fun handleFastDelete() {
        val inputConnection = currentInputConnection

        // 首先检查是否有选中的文本
        val selectedText = inputConnection?.getSelectedText(0)
        if (selectedText != null && selectedText.isNotEmpty()) {
            // 如果有选中的文本，删除选中文本
            inputConnection.commitText("", 1)
            return
        }

        if (currentKeyboardType == KeyboardType.CHINESE && chineseInputBuffer.isNotEmpty()) {
            // 中文模式：清空拼音输入缓冲区
            chineseInputBuffer.clear()
            updateCandidateView()
        } else if (currentInput.isNotEmpty()) {
            // 如果有当前输入，清空整个输入
            currentInput.clear()
            updateCandidateView()
        } else {
            // 删除整个单词或直到遇到空格
            // 这里简化实现：删除到前一个空格或行首
            inputConnection?.deleteSurroundingText(20, 0) // 删除最多20个字符
        }
    }

    private fun handleKeyLongPress(key: String) {
        if (key == "DEL") {
            isDeletePressed = true
            deleteStartTime = System.currentTimeMillis()
            isFastDeleteMode = false

            deleteRunnable = object : Runnable {
                override fun run() {
                    if (isDeletePressed) {
                        val currentTime = System.currentTimeMillis()
                        val pressDuration = currentTime - deleteStartTime

                        // 检查是否应该进入快速删除模式
                        if (!isFastDeleteMode && pressDuration > deleteAccelerationThreshold) {
                            isFastDeleteMode = true
                        }

                        if (isFastDeleteMode) {
                            // 快速删除模式：按单词删除
                            handleFastDelete()
                        } else {
                            // 普通删除模式：按字符删除
                            handleDeleteKey()
                        }

                        handler.postDelayed(this, if (isFastDeleteMode) 50L else 100L)
                    }
                }
            }
            handler.post(deleteRunnable!!)
        }
    }

    private fun handleKeyRelease(key: String) {
        if (key == "DEL") {
            isDeletePressed = false
            isFastDeleteMode = false
            deleteRunnable?.let {
                handler.removeCallbacks(it)
            }
            deleteRunnable = null
        }
    }

    private fun handleKeyboardTypeChange(newType: KeyboardType) {
        currentKeyboardType = newType

        // 更新转换管理器的当前键盘类型
        conversionManager.setCurrentKeyboardType(newType)

        // 更新转换栏的当前键盘类型和可用语言
        conversionBarView?.setCurrentKeyboardType(newType, conversionManager.getAvailableTargetLanguages())

        // 更新转换栏状态
        conversionBarView?.updateConversionState(conversionManager.getCurrentConversionState())

        // 切换语言时清空当前输入
        currentInput.clear()
        chineseInputBuffer.clear()
        updateCandidateView()

        // 根据键盘类型设置候选词视图模式
        candidateView?.setChineseMode(newType == KeyboardType.CHINESE)

        // 隐藏转换栏的语言选择器
        conversionBarView?.hideLanguageSelectorIfVisible()

        // 切换键盘类型时重置上下文
        resetContext()
    }

    private fun handleConversionLanguageSelected(language: KeyboardType) {
        Log.d("NasInputMethod", "Conversion language selected: $language")

        // 启用转换模式并设置目标语言
        conversionManager.enableConversionMode(language)

        // 更新转换栏状态
        conversionBarView?.updateConversionState(conversionManager.getCurrentConversionState())

        // 如果有当前输入，更新候选词显示（虽然显示不变，但提交时会转换）
        updateCandidateView()
    }

    private fun handleConversionCancelled() {
        Log.d("NasInputMethod", "Conversion cancelled")

        // 禁用转换模式
        conversionManager.disableConversionMode()

        // 更新转换栏状态
        conversionBarView?.updateConversionState(conversionManager.getCurrentConversionState())

        // 隐藏语言选择器
        conversionBarView?.hideLanguageSelectorIfVisible()
    }

    private fun convertTextForOutput(text: String): String {
        return conversionManager.convertText(text)
    }

    private fun updateCandidateView() {
        Log.d("NasInputMethod", "Updating candidate view. KeyboardType: $currentKeyboardType, " +
                "Chinese buffer: '$chineseInputBuffer', General buffer: '$currentInput', " +
                "isShowingContextPredictions: $isShowingContextPredictions")

        when (currentKeyboardType) {
            KeyboardType.CHINESE -> {
                updateChineseCandidateView()
            }
            KeyboardType.CYRILLIC_KAZAKH -> {
                // 只修改哈萨克语的更新逻辑，其他保持不变
                updateKazakhCandidateView()
            }
            KeyboardType.ARABIC, KeyboardType.LATIN -> {
                // 恢复其他哈萨克语变体的逻辑
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    // 修复：使用 getContextPredictions 代替 getPureContextPredictions
                    val contextPredictions = kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 5)
                    candidateView?.updateCandidates(contextPredictions)
                } else if (currentInputText.isNotEmpty()) {
                    val predictions = if (lastSubmittedWord != null && kazakhDictionaryManager.isShowingContextPredictions()) {
                        kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, 5)
                    } else {
                        kazakhDictionaryManager.getPredictions(currentInputText, 5)
                    }

                    candidateView?.updateCandidates(listOf(currentInputText) + predictions)
                } else {
                    candidateView?.updateCandidates(emptyList())
                }
            }
            KeyboardType.ENGLISH -> {
                // 恢复英文逻辑
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    val contextPredictions = englishDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 10)
                    candidateView?.updateCandidates(contextPredictions)
                } else if (currentInputText.isNotEmpty()) {
                    val maxPredictions = 5
                    val predictions = if (lastSubmittedWord != null && currentInputText.isNotEmpty()) {
                        englishDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, maxPredictions)
                    } else {
                        englishDictionaryManager.getPredictions(currentInputText, null, maxPredictions)
                    }

                    candidateView?.updateCandidates(listOf(currentInputText) + predictions)
                } else {
                    candidateView?.updateCandidates(emptyList())
                }
            }
            KeyboardType.RUSSIAN -> {
                // 恢复俄文逻辑
                val currentInputText = currentInput.toString()

                if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                    val contextPredictions = russianDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 10)
                    candidateView?.updateCandidates(contextPredictions)
                } else if (currentInputText.isNotEmpty()) {
                    val maxPredictions = 5
                    val predictions = if (lastSubmittedWord != null && currentInputText.isNotEmpty()) {
                        russianDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, maxPredictions)
                    } else {
                        russianDictionaryManager.getPredictions(currentInputText, null, maxPredictions)
                    }

                    candidateView?.updateCandidates(listOf(currentInputText) + predictions)
                } else {
                    candidateView?.updateCandidates(emptyList())
                }
            }
            else -> {
                val previewText = currentInput.toString()
                candidateView?.updateSimplePreview(previewText)
            }
        }
    }

    private fun updateChineseCandidateView() {
        val pinyin = chineseInputBuffer.toString()

        // 更新拼音显示
        candidateView?.updatePinyin(pinyin)

        if (pinyin.isNotEmpty()) {
            // 使用拼音解码器获取候选词
            val candidates = pinyinDecoder.getSmartCandidates(pinyin)
            candidateView?.updateCandidates(candidates)
            Log.d("NasInputMethod", "拼音解码器找到中文候选词: $candidates for pinyin: $pinyin")
            chineseComposingState = if (candidates.isNotEmpty()) {
                ChineseComposingState.CANDIDATE
            } else {
                ChineseComposingState.COMPOSING
            }
        } else {
            // 没有拼音输入时，检查是否需要显示上下文预测
            if (lastChineseWord != null) {
                val predictions = pinyinDecoder.getCandidatesWithBigram(lastChineseWord!!)
                if (predictions.isNotEmpty()) {
                    candidateView?.updateCandidates(predictions)
                    chineseComposingState = ChineseComposingState.PREDICT
                    Log.d("NasInputMethod", "显示中文上下文预测 (前词: $lastChineseWord): $predictions")
                } else {
                    candidateView?.updateCandidates(emptyList())
                    chineseComposingState = ChineseComposingState.IDLE
                }
            } else {
                candidateView?.updateCandidates(emptyList())
                chineseComposingState = ChineseComposingState.IDLE
            }
        }
    }

    // 修正后的哈萨克语候选词视图更新
    private fun updateKazakhCandidateView() {
        val currentInputText = currentInput.toString()
        Log.d("NasInputMethod", "哈萨克语候选词更新: currentInput='$currentInputText', " +
                "isShowingContextPredictions=$isShowingContextPredictions, lastSubmittedWord=$lastSubmittedWord")

        candidateUpdateJob?.cancel()
        candidateUpdateJob = candidateUpdateScope.launch(Dispatchers.IO) {
            try {
                val predictions = withTimeout(50) {  // 增加超时时间到50ms，因为拼写纠错需要更多时间
                    // 情况1: 显示上下文预测（前一个词有，当前输入为空）
                    if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
                        Log.d("NasInputMethod", "哈萨克语纯上下文预测 (前词: $lastSubmittedWord)")

                        // 1. 主词典的上下文预测（纯上下文，当前前缀为空）
                        val mainDictResults = try {
                            kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 5)
                        } catch (e: Exception) {
                            Log.e("NasInputMethod", "主词典上下文预测异常: ${e.message}")
                            emptyList()
                        }

                        // 2. 用户词典的上下文搜索
                        val userDictResults = if (mainDictResults.size < 5) {
                            try {
                                if (kazakhUserDictManager.isUserDictLoaded()) {
                                    withTimeout(10) {
                                        kazakhUserDictManager.searchWithContext(lastSubmittedWord!!, "", 3)
                                    }
                                } else {
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                Log.w("NasInputMethod", "用户词典上下文搜索异常: ${e.message}")
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }

                        val combinedResults = (mainDictResults + userDictResults).distinct().take(5)
                        Log.d("NasInputMethod", "上下文预测合并结果: ${combinedResults.size} 个")
                        combinedResults
                    }
                    // 情况2: 有当前输入 - 关键修复：使用智能预测而不是普通预测
                    else if (currentInputText.isNotEmpty()) {
                        Log.d("NasInputMethod", "哈萨克语智能预测 (输入: $currentInputText, 包含拼写纠错)")

                        // 🔧 关键修复：使用 smartPredict 替代 getPredictions
                        val mainDictResults = try {
                            if (lastSubmittedWord != null && kazakhDictionaryManager.isShowingContextPredictions()) {
                                // 如果有上下文，使用上下文预测
                                kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, 5)
                            } else {
                                // 🌟 使用智能预测（包含拼写纠错）
                                kazakhDictionaryManager.smartPredict(currentInputText, 5)
                            }
                        } catch (e: Exception) {
                            Log.e("NasInputMethod", "主词典智能预测异常: ${e.message}")
                            emptyList()
                        }

                        val allResults = mutableListOf<String>()
                        val seen = mutableSetOf<String>()

                        // 添加当前输入
                        if (currentInputText.isNotEmpty() && currentInputText !in seen) {
                            allResults.add(currentInputText)
                            seen.add(currentInputText)
                        }

                        // 添加主词典结果（现在包含拼写纠错）
                        for (result in mainDictResults) {
                            if (result !in seen) {
                                allResults.add(result)
                                seen.add(result)
                            }
                        }

                        // 如果结果不足，尝试用户词典
                        if (allResults.size < 5) {
                            try {
                                val remainingSlots = 5 - allResults.size
                                if (remainingSlots > 0 && kazakhUserDictManager.isUserDictLoaded()) {
                                    val userDictResults = withTimeout(15) {
                                        val prefixResults = kazakhUserDictManager.searchPrefix(currentInputText, remainingSlots)
                                        val contextResults = if (lastSubmittedWord != null) {
                                            kazakhUserDictManager.searchWithContext(lastSubmittedWord!!, currentInputText, remainingSlots)
                                        } else {
                                            emptyList()
                                        }
                                        (prefixResults + contextResults).distinct()
                                    }

                                    for (result in userDictResults) {
                                        if (result !in seen) {
                                            allResults.add(result)
                                            seen.add(result)
                                            if (allResults.size >= 5) break
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("NasInputMethod", "用户词典搜索异常: ${e.message}")
                            }
                        }

                        Log.d("NasInputMethod", "智能预测合并结果: ${allResults.size} 个")
                        allResults.take(5)
                    }
                    // 情况3: 其他情况
                    else {
                        Log.d("NasInputMethod", "哈萨克语无输入，返回空列表")
                        emptyList()
                    }
                }

                withContext(Dispatchers.Main) {
                    candidateView?.updateCandidates(predictions)
                }

            } catch (e: TimeoutCancellationException) {
                Log.w("NasInputMethod", "哈萨克语预测超时")
                withContext(Dispatchers.Main) {
                    candidateView?.updateCandidates(emptyList())
                }
            } catch (e: CancellationException) {
                Log.d("NasInputMethod", "哈萨克语候选词更新被取消")
            } catch (e: Exception) {
                Log.e("NasInputMethod", "哈萨克语预测异常: ${e.message}")
                withContext(Dispatchers.Main) {
                    candidateView?.updateCandidates(emptyList())
                }
            }
        }
    }

    /**
     * 新增：处理用户词典相关功能
     */
    private fun handleUserDictSpecialFunction(word: String) {
        if (!kazakhUserDictManager.isUserDictLoaded()) {
            return
        }

        // 如果单词不在系统词典中，自动添加到用户词典
        if (!kazakhDictionaryManager.isWord(word)) {
            kazakhUserDictManager.addWord(word, 1)
            Log.d("NasInputMethod", "自动添加到用户词典: '$word'")

            // 显示提示
            showToast("已添加 '$word' 到用户词典")
        }
    }

    /**
     * 新增：显示用户词典统计
     */
    private fun showUserDictStats() {
        if (kazakhUserDictManager.isUserDictLoaded()) {
            val stats = kazakhUserDictManager.getStats()
            Log.d("NasInputMethod", "用户词典统计:\n$stats")
            showToast("用户词典: ${kazakhUserDictManager.getWordCount()} 个词条")
        } else {
            showToast("用户词典未加载")
        }
    }

    /**
     * 新增：清空用户词典
     */
    private fun clearUserDict() {
        if (kazakhUserDictManager.isUserDictLoaded()) {
            val success = kazakhUserDictManager.clearUserDict()
            if (success) {
                showToast("用户词典已清空")
            } else {
                showToast("清空用户词典失败")
            }
        }
    }

    /**
     * 新增：导出用户词典
     */
    private fun exportUserDict() {
        if (kazakhUserDictManager.isUserDictLoaded()) {
            val words = kazakhUserDictManager.exportWords()
            if (words.isNotEmpty()) {
                // 在实际实现中，这里可以将单词保存到文件或分享
                Log.d("NasInputMethod", "导出用户词典: ${words.size} 个单词")
                showToast("导出 ${words.size} 个单词")
            }
        }
    }

    /**
     * 新增：辅助方法 - 显示Toast
     */
    private fun showToast(message: String) {
        // 在实际实现中，这里应该使用Toast或Snackbar显示消息
        Log.d("NasInputMethod", "Toast: $message")
        // 示例：Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun testUserDictFunctionality() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val testWord = "қотақбас"

                // 1. 是否已加载
                val isLoaded = kazakhUserDictManager.isUserDictLoaded()
                Log.d("NasInputMethod", "用户词典已加载: $isLoaded")
                if (!isLoaded) return@launch

                // 2. 是否包含
                val contains = kazakhUserDictManager.containsWord(testWord)
                Log.d("NasInputMethod", "用户词典包含 '$testWord': $contains")

                // 3. 只在不存在时添加（防止污染）
                if (!contains) {
                    val added = kazakhUserDictManager.addWord(testWord, 1)
                    Log.d("NasInputMethod", "添加 '$testWord': $added")
                }

                // 4. 搜索前缀
                val prefixResults =
                    kazakhUserDictManager.searchPrefix("қота", 5)
                Log.d(
                    "NasInputMethod",
                    "搜索前缀 'қота': ${prefixResults.joinToString(", ")}"
                )

                // 5. 统计信息
                val stats = kazakhUserDictManager.getStats()
                Log.d("NasInputMethod", "用户词典统计:\n$stats")

            } catch (e: Exception) {
                Log.e("NasInputMethod", "测试用户词典功能异常", e)
            }
        }
    }


    /**
     * 新增方法：处理阿拉伯文和拉丁文的候选词显示
     */
    private fun updateOtherKazakhCandidateView() {
        val currentInputText = currentInput.toString()

        // 关键修复：如果有上下文且当前输入为空，显示纯上下文预测
        if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
            // 显示上下文预测（使用空的当前前缀）
            val contextPredictions = kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 5)
            candidateView?.updateCandidates(contextPredictions)
            Log.d("NasInputMethod", "${currentKeyboardType}纯上下文预测 (前词: $lastSubmittedWord): ${contextPredictions.take(3)}...")
        } else if (currentInputText.isNotEmpty()) {
            // 智能预测：如果有前一个词，使用上下文预测
            val predictions = if (lastSubmittedWord != null && kazakhDictionaryManager.isShowingContextPredictions()) {
                kazakhDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText,
                    if (candidateView?.isExpanded == true) 15 else 5)
            } else {
                kazakhDictionaryManager.getPredictions(currentInputText,
                    if (candidateView?.isExpanded == true) 15 else 5)
            }

            // 显示预测结果（包含当前输入）
            candidateView?.updateCandidates(listOf(currentInputText) + predictions)
            Log.d("NasInputMethod", "${currentKeyboardType}预测 '$currentInputText' (上下文: $lastSubmittedWord): ${predictions.take(3)}...")
        } else {
            candidateView?.updateCandidates(emptyList())
        }
    }

    // 新增：英文候选词显示
    private fun updateEnglishCandidateView() {
        val currentInputText = currentInput.toString()

        // 关键修复：如果有上下文且当前输入为空，显示纯上下文预测
        if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
            // 显示纯上下文预测（基于前一个词的bigram预测）
            val contextPredictions = englishDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 10)
            candidateView?.updateCandidates(contextPredictions)
            Log.d("NasInputMethod", "英文纯上下文预测 (前词: $lastSubmittedWord): $contextPredictions")
        } else if (currentInputText.isNotEmpty()) {
            // 获取英文预测 - 关键修复：根据展开状态获取不同数量的预测词，使用上下文预测
            val maxPredictions = if (candidateView?.isExpanded == true) 15 else 5

            // 使用智能预测：如果有前一个词，使用上下文预测
            val predictions = if (lastSubmittedWord != null && currentInputText.isNotEmpty()) {
                // 上下文预测：基于前一个词预测当前输入
                englishDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, maxPredictions)
            } else {
                // 普通前缀预测
                englishDictionaryManager.getPredictions(currentInputText, null, maxPredictions)
            }

            // 更新候选词显示，包含当前输入
            candidateView?.updateCandidates(listOf(currentInputText) + predictions)
            Log.d("NasInputMethod", "英文预测 '$currentInputText' (上下文: $lastSubmittedWord): $predictions")
        } else {
            candidateView?.updateCandidates(emptyList())
        }
    }

    // 新增：俄文候选词显示
    private fun updateRussianCandidateView() {
        val currentInputText = currentInput.toString()

        // 关键修复：如果有上下文且当前输入为空，显示纯上下文预测
        if (isShowingContextPredictions && currentInputText.isEmpty() && lastSubmittedWord != null) {
            // 显示纯上下文预测（基于前一个词的bigram预测）
            val contextPredictions = russianDictionaryManager.getContextPredictions(lastSubmittedWord!!, "", 10)
            candidateView?.updateCandidates(contextPredictions)
            Log.d("NasInputMethod", "俄文纯上下文预测 (前词: $lastSubmittedWord): $contextPredictions")
        } else if (currentInputText.isNotEmpty()) {
            // 获取俄文预测 - 关键修复：根据展开状态获取不同数量的预测词，使用上下文预测
            val maxPredictions = if (candidateView?.isExpanded == true) 15 else 5

            // 使用智能预测：如果有前一个词，使用上下文预测
            val predictions = if (lastSubmittedWord != null && currentInputText.isNotEmpty()) {
                // 上下文预测：基于前一个词预测当前输入
                russianDictionaryManager.getContextPredictions(lastSubmittedWord!!, currentInputText, maxPredictions)
            } else {
                // 普通前缀预测
                russianDictionaryManager.getPredictions(currentInputText, null, maxPredictions)
            }

            // 更新候选词显示，包含当前输入
            candidateView?.updateCandidates(listOf(currentInputText) + predictions)
            Log.d("NasInputMethod", "俄文预测 '$currentInputText' (上下文: $lastSubmittedWord): $predictions")
        } else {
            candidateView?.updateCandidates(emptyList())
        }
    }

    // 处理表情搜索模式的按键
    private fun handleSearchKeyPress(key: String) {
        emojiView?.handleSearchKeyPress(key)
    }

    // 设置表情搜索模式状态
    fun setEmojiSearchMode(enabled: Boolean) {
        isInEmojiSearchMode = enabled
    }

    // 重置上下文（开始新句子时调用）
    private fun resetContext() {
        lastSubmittedWord = null
        isShowingContextPredictions = false
        Log.d("NasInputMethod", "通用上下文已重置")
    }

    // 重置中文上下文
    private fun resetChineseContext() {
        lastChineseWord = null
        chineseInputBuffer.clear()
        chineseComposingState = ChineseComposingState.IDLE
        pinyinDecoder.resetInputState()
        candidateView?.updateCandidates(emptyList())
        candidateView?.updatePinyin("")
        Log.d("NasInputMethod", "中文上下文已重置")
    }

    private fun showEmojiView() {
        Log.d("NasInputMethod", "Showing emoji view")
        isShowingEmoji = true

        // 隐藏键盘相关视图，显示表情视图
        keyboardView?.visibility = View.GONE
        candidateView?.visibility = View.GONE
        conversionBarView?.visibility = View.GONE

        // 显示表情视图并设置合适的高度
        emojiView?.visibility = View.VISIBLE
        emojiView?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            600 // 设置固定高度，确保表情界面足够高
        )

        // 加载表情数据（如果还没加载）
        emojiManager.loadEmojis()

        // 通知表情界面已显示
        emojiView?.onEmojiViewShown()
    }

    private fun showKeyboardView() {
        Log.d("NasInputMethod", "Showing keyboard view")
        isShowingEmoji = false

        // 隐藏表情视图，显示键盘相关视图
        emojiView?.visibility = View.GONE

        // 显示键盘相关视图
        keyboardView?.visibility = View.VISIBLE
        candidateView?.visibility = View.VISIBLE
        conversionBarView?.visibility = View.VISIBLE
    }

    private fun handleEmojiInput(emoji: Emoji) {
        Log.d("NasInputMethod", "Inserting emoji: ${emoji.value}")
        currentInputConnection?.commitText(emoji.value, 1)

        // 重要：移除自动返回键盘的逻辑，用户可以继续点击表情
        // showKeyboardView()

        // 记录表情使用历史
        emojiHistoryManager.addToHistory(emoji)
    }

    private fun createSimpleTestView(): View {
        Log.d("NasInputMethod", "Creating simple test view")
        return TextView(this).apply {
            text = "Nasboard Keyboard - TEST VIEW"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                200
            )
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d("NasInputMethod", "onStartInputView called - keyboard should be visible now")

        // 确保显示键盘视图（不是表情视图）
        showKeyboardView()

        // 清空当前输入
        currentInput.clear()
        chineseInputBuffer.clear()
        updateCandidateView()

        // 确保当前键盘类型是启用的
        ensureValidKeyboardType()

        // 确保转换管理器使用正确的键盘类型
        conversionManager.setCurrentKeyboardType(currentKeyboardType)

        // 根据当前键盘类型设置候选词视图模式
        candidateView?.setChineseMode(currentKeyboardType == KeyboardType.CHINESE)

        // 更新转换栏
        conversionBarView?.setCurrentKeyboardType(currentKeyboardType, conversionManager.getAvailableTargetLanguages())
        conversionBarView?.updateConversionState(conversionManager.getCurrentConversionState())

        // 刷新键盘视图设置
        keyboardView?.refreshKeyboardSettings()

        // 开始新的输入时重置上下文
        resetContext()
        if (currentKeyboardType == KeyboardType.CHINESE) {
            resetChineseContext()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d("NasInputMethod", "onStartInput called with attribute: $attribute")
        attribute?.let {
            keyboardView?.setEditorInfo(it)
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d("NasInputMethod", "onFinishInput called")
        // 清空当前输入
        currentInput.clear()
        chineseInputBuffer.clear()
        // 停止长按删除
        isDeletePressed = false
        isFastDeleteMode = false
        deleteRunnable?.let {
            handler.removeCallbacks(it)
        }
        // 确保返回到键盘视图
        showKeyboardView()
        // 结束输入时重置上下文
        resetContext()
        resetChineseContext()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("NasInputMethod", "onDestroy called")

        // 取消所有协程
        candidateUpdateScope.cancel()
        userDictScope.cancel()

        // 清理拼音解码器
        pinyinDecoder.close()

        // 清理英文词库管理器
        englishDictionaryManager.close()

        // 清理俄文词库管理器
        russianDictionaryManager.close()

        // 修改点3：添加哈萨克语词典清理
        // 清理哈萨克语词库管理器（新增）
        kazakhDictionaryManager.close()
        kazakhUserDictManager.close()

        // 清理Handler
        handler.removeCallbacksAndMessages(null)
        keyboardView = null
        candidateView = null
        conversionBarView = null
        containerView = null
        emojiView = null
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}