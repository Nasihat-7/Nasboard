package com.example.nasboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.example.nasboard.ime.theme.JsonThemeManager
import com.example.nasboard.ime.theme.KeyboardTheme
import com.example.nasboard.ime.theme.ThemeManager
import kotlinx.coroutines.*

class NasKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface OnKeyPressListener {
        fun onKeyPress(key: String)
        fun onKeyboardTypeChange(newType: KeyboardType)
        fun onKeyLongPress(key: String)
        fun onKeyRelease(key: String)
    }

    // 现代化组件
    private val modernLayoutManager = ModernKeyboardLayoutManager(context)
    private val themeManager = ThemeManager.getInstance(context)
    private val keyTypeClassifier = KeyTypeClassifier

    // 键盘状态
    private var shiftState = 0
    private var lastShiftTime: Long = 0
    private var isNumeric = false
    private var currentKeyboardType = KeyboardType.CYRILLIC_KAZAKH

    // 视图状态
    private var currentLayoutConfig: KeyboardLayoutConfig? = null
    private var currentKeyBounds = emptyList<ModernKeyboardLayoutManager.KeyBound>()
    private var pressedKeyIndex = -1

    // 绘制组件
    private var keyDrawer = KeyDrawer(themeManager.currentTheme)

    // 监听器
    private var onKeyPressListener: OnKeyPressListener? = null

    // 长按处理
    private var longPressJob: Job? = null
    private val longPressTimeout = 500L

    // 设置管理器
    private lateinit var settingsManager: KeyboardSettingsManager

    // 最小高度
    private val minKeyboardHeight = 700

    init {
        setBackgroundColor(themeManager.currentTheme.backgroundColor)
        isClickable = true
        settingsManager = KeyboardSettingsManager.getInstance(context)
        ensureValidKeyboardType()
        loadKeyboardLayout()
    }

    // 公开API
    fun setOnKeyPressListener(listener: OnKeyPressListener) {
        this.onKeyPressListener = listener
    }

    fun switchKeyboardType(type: KeyboardType) {
        currentKeyboardType = type
        loadKeyboardLayout()
        onKeyPressListener?.onKeyboardTypeChange(type)
    }

    fun setTheme(themeName: String) {
        // 修复：使用新的主题设置方法
        if (themeName.startsWith("json_")) {
            val realThemeName = themeName.removePrefix("json_")
            val jsonThemeManager = JsonThemeManager.getInstance(context)
            val jsonTheme = jsonThemeManager.getJsonTheme(realThemeName)
            if (jsonTheme != null) {
                keyDrawer = KeyDrawer(jsonTheme.toKeyboardTheme())
                setBackgroundColor(jsonTheme.toKeyboardTheme().backgroundColor)
                invalidate()
                return
            }
        } else {
            // 对于核心主题，直接设置
            when (themeName) {
                "dark" -> {
                    keyDrawer = KeyDrawer(KeyboardTheme.DARK)
                    setBackgroundColor(KeyboardTheme.DARK.backgroundColor)
                }
                "material" -> {
                    keyDrawer = KeyDrawer(KeyboardTheme.MATERIAL)
                    setBackgroundColor(KeyboardTheme.MATERIAL.backgroundColor)
                }
                else -> {
                    keyDrawer = KeyDrawer(KeyboardTheme.DEFAULT)
                    setBackgroundColor(KeyboardTheme.DEFAULT.backgroundColor)
                }
            }
            invalidate()
        }
    }

    fun refreshKeyboardSettings() {
        ensureValidKeyboardType()
        loadKeyboardLayout()
    }

    // 私有方法
    private fun loadKeyboardLayout() {
        Log.d("NasKeyboardView", "loadKeyboardLayout called - type: $currentKeyboardType, numeric: $isNumeric, shift: $shiftState")

        viewScope.launch {
            try {
                val config = modernLayoutManager.getLayoutConfig(currentKeyboardType)
                currentLayoutConfig = config

                if (config != null) {
                    Log.d("NasKeyboardView", "Config loaded: ${config.name}, supportsShift: ${config.supportsShift}")

                    val rows = modernLayoutManager.getLayoutVariant(config, shiftState, isNumeric)
                    Log.d("NasKeyboardView", "Got ${rows.size} rows from layout")

                    if (rows.isNotEmpty()) {
                        currentKeyBounds = modernLayoutManager.calculateKeyBounds(rows, width, height)
                        Log.d("NasKeyboardView", "Key bounds calculated: ${currentKeyBounds.size}")
                        invalidate()
                    } else {
                        Log.e("NasKeyboardView", "No rows in layout!")
                        createEmergencyFallback()
                    }
                } else {
                    Log.e("NasKeyboardView", "Failed to load layout config!")
                    createEmergencyFallback()
                }
            } catch (e: Exception) {
                Log.e("NasKeyboardView", "Error in loadKeyboardLayout: ${e.message}")
                e.printStackTrace()
                createEmergencyFallback()
            }
        }
    }

    private fun createEmergencyFallback() {
        Log.w("NasKeyboardView", "Creating emergency fallback layout")
        // 创建一个简单的紧急布局
        val bounds = Rect(0, 0, width, height / 2)
        val key = KeyboardKey.FunctionKey(code = "ERROR", label = "加载失败", width = 3.0f)
        currentKeyBounds = listOf(ModernKeyboardLayoutManager.KeyBound(key, bounds))
        invalidate()
    }

    private fun ensureValidKeyboardType() {
        val enabledTypes = settingsManager.getEnabledKeyboardTypes()
        if (enabledTypes.isNotEmpty() && currentKeyboardType !in enabledTypes) {
            currentKeyboardType = enabledTypes.first()
        }
    }

    private fun getNextEnabledKeyboardType(): KeyboardType {
        return settingsManager.getNextKeyboardType(currentKeyboardType)
    }

    // 视图生命周期
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val desiredHeight = minKeyboardHeight.coerceAtLeast(450)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> widthSize
            else -> widthSize
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("NasKeyboardView", "onSizeChanged: $w x $h")

        if (currentLayoutConfig != null) {
            val rows = modernLayoutManager.getLayoutVariant(currentLayoutConfig!!, shiftState, isNumeric)
            currentKeyBounds = modernLayoutManager.calculateKeyBounds(rows, w, h)
            invalidate()
        } else {
            // 如果布局配置还没有加载，重新加载
            loadKeyboardLayout()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d("NasKeyboardView", "onDraw - drawing ${currentKeyBounds.size} keys")
        drawAllKeys(canvas)
    }

    private fun drawAllKeys(canvas: Canvas) {
        currentKeyBounds.forEachIndexed { index, keyBound ->
            drawSingleKey(canvas, keyBound, index)
        }
    }

    private fun drawSingleKey(canvas: Canvas, keyBound: ModernKeyboardLayoutManager.KeyBound, index: Int) {
        val isPressed = index == pressedKeyIndex
        val isTypingKey = keyTypeClassifier.isTypingKey(keyBound.key.code)
        val isSpecialKey = keyTypeClassifier.isSpecialKey(keyBound.key.code)
        val isShiftKey = keyTypeClassifier.isShiftKey(keyBound.key.code)

        // 绘制按键背景 - 传递 keyCode
        keyDrawer.drawKeyBackground(canvas, keyBound.bounds, isPressed, isTypingKey, keyBound.key.code)

        // 绘制按键文本 - 传递 keyCode
        keyDrawer.drawKeyText(canvas, keyBound.bounds, keyBound.key.label, isSpecialKey, isShiftKey, keyBound.key.code)
    }

    // 触摸事件处理
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(event)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event)
            MotionEvent.ACTION_UP -> handleTouchUp()
            MotionEvent.ACTION_CANCEL -> handleTouchCancel()
        }
        return true
    }

    private fun handleTouchDown(event: MotionEvent) {
        val touchX = event.x.toInt()
        val touchY = event.y.toInt()

        val keyIndex = findKeyAtPosition(touchX, touchY)
        if (keyIndex != pressedKeyIndex) {
            pressedKeyIndex = keyIndex
            invalidate()

            // 启动长按检测
            if (pressedKeyIndex != -1) {
                longPressJob = viewScope.launch {
                    delay(longPressTimeout)
                    val keyCode = currentKeyBounds[pressedKeyIndex].key.code
                    onKeyPressListener?.onKeyLongPress(keyCode)
                }
            }
        }
    }

    private fun handleTouchMove(event: MotionEvent) {
        val touchX = event.x.toInt()
        val touchY = event.y.toInt()

        val keyIndex = findKeyAtPosition(touchX, touchY)
        if (keyIndex != pressedKeyIndex) {
            // 取消长按检测
            cancelCurrentLongPress()
            pressedKeyIndex = keyIndex
            invalidate()

            // 重新启动长按检测
            if (pressedKeyIndex != -1) {
                longPressJob = viewScope.launch {
                    delay(longPressTimeout)
                    val keyCode = currentKeyBounds[pressedKeyIndex].key.code
                    onKeyPressListener?.onKeyLongPress(keyCode)
                }
            }
        }
    }

    private fun handleTouchUp() {
        cancelCurrentLongPress()

        if (pressedKeyIndex != -1) {
            val keyCode = currentKeyBounds[pressedKeyIndex].key.code
            // 如果是空白键，不处理按键
            if (keyCode != "BLANK") {
                handleKeyPress(keyCode)
                onKeyPressListener?.onKeyRelease(keyCode)
            }
        }
        pressedKeyIndex = -1
        invalidate()
    }

    private fun handleTouchCancel() {
        cancelCurrentLongPress()
        pressedKeyIndex = -1
        invalidate()
    }

    private fun cancelCurrentLongPress() {
        longPressJob?.cancel()
        longPressJob = null
    }

    private fun findKeyAtPosition(x: Int, y: Int): Int {
        currentKeyBounds.forEachIndexed { index, keyBound ->
            // 跳过空白键
            if (keyBound.key.code == "BLANK") {
                return@forEachIndexed
            }
            if (keyBound.bounds.contains(x, y)) {
                return index
            }
        }
        return -1
    }

    // 按键处理逻辑
    private fun handleKeyPress(keyCode: String) {
        Log.d("NasKeyboardView", "Key pressed: $keyCode")

        // 如果是空白键，直接返回
        if (keyCode == "BLANK") {
            return
        }

        when (keyCode) {
            "SHIFT" -> handleShiftKey()
            "123" -> handleNumericKey()
            "ABC" -> handleAlphaKey()
            "SWITCH_LANG" -> handleLanguageSwitch()
            "EMOJI" -> handleEmojiKey()
            else -> handleCharacterKey(keyCode)
        }
    }

    private fun handleShiftKey() {
        val config = modernLayoutManager.getKeyboardConfig(currentKeyboardType)
        if (!isNumeric && config?.supportsShift == true) {
            val now = System.currentTimeMillis()
            if (now - lastShiftTime < 400) {
                // 双击切换 Caps Lock
                shiftState = if (shiftState == 2) 0 else 2
            } else {
                // 单击临时切换大小写
                shiftState = if (shiftState == 0) 1 else 0
            }
            lastShiftTime = now
            loadKeyboardLayout()
        }
    }

    private fun handleNumericKey() {
        // 切换到数字键盘
        if (!isNumeric) {
            isNumeric = true
            loadKeyboardLayout()
        }
    }

    private fun handleAlphaKey() {
        // 从数字键盘返回文字键盘
        if (isNumeric) {
            isNumeric = false
            loadKeyboardLayout()
        }
    }

    private fun handleLanguageSwitch() {
        // 语言切换 - 使用设置管理器获取下一个启用的键盘类型
        val nextType = getNextEnabledKeyboardType()
        switchKeyboardType(nextType)
    }

    private fun handleEmojiKey() {
        // 表情按钮处理
        onKeyPressListener?.onKeyPress("EMOJI")
    }

    private fun handleCharacterKey(keyCode: String) {
        // 处理字母键的大小写
        val finalKey = when {
            keyCode.length == 1 && keyCode[0].isLetter() && !isNumeric -> {
                when (shiftState) {
                    1, 2 -> keyCode.uppercase()
                    else -> keyCode.lowercase()
                }.also {
                    // 如果是临时大写，输入一个字符后恢复小写
                    if (shiftState == 1) {
                        shiftState = 0
                        loadKeyboardLayout()
                    }
                }
            }
            else -> keyCode
        }
        onKeyPressListener?.onKeyPress(finalKey)
    }

    fun setEditorInfo(editorInfo: EditorInfo) {
        // 可以根据输入框类型调整键盘
        when (editorInfo.inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_NUMBER -> {
                // 数字输入，可以切换到数字键盘
                isNumeric = true
                loadKeyboardLayout()
            }
            else -> {
                // 默认情况，不需要特别处理
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelCurrentLongPress()
        viewScope.cancel()
    }
}