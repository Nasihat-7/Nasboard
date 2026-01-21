package com.example.nasboard.ime.conversion

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.nasboard.ime.theme.KeyboardTheme
import com.example.nasboard.KeyboardType
import com.example.nasboard.R
import com.example.nasboard.ime.theme.ThemeManager

class ConversionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), ThemeManager.OnThemeChangeListener {

    interface OnConversionLanguageSelectedListener {
        fun onConversionLanguageSelected(language: KeyboardType)
        fun onConversionModeToggled()
        fun onConversionCancelled()
        fun onLanguageSelectorCancelled()
    }

    private var onConversionLanguageSelectedListener: OnConversionLanguageSelectedListener? = null

    // 按钮容器
    private var buttonsContainer: LinearLayout? = null

    // 所有可能的按钮
    private var conversionButton: ImageView? = null
    private var cancelButton: ImageView? = null
    private var currentLanguageButton: ImageView? = null
    private var altLanguageButton1: ImageView? = null
    private var altLanguageButton2: ImageView? = null

    // 动画
    private var slideInAnimation: Animation? = null
    private var slideOutAnimation: Animation? = null

    // 状态
    private var currentConversionState: ConversionManager.ConversionState? = null
    private var currentKeyboardType: KeyboardType = KeyboardType.LATIN
    private var availableTargetLanguages: List<KeyboardType> = emptyList()

    // 主题管理器
    private lateinit var themeManager: ThemeManager
    private var currentTheme: KeyboardTheme = KeyboardTheme.DEFAULT

    // 图标资源映射
    private val iconResources = mapOf(
        // 主转换按钮
        "conversion" to R.drawable.ic_conversion,

        // 取消按钮
        "cancel" to R.drawable.ic_cancel,

        // 语言图标 - 未选中状态
        "arabic" to R.drawable.ic_arab,
        "cyrillic" to R.drawable.ic_kiri,
        "latin" to R.drawable.ic_latin,

        // 语言图标 - 选中状态
        "arabic_chosen" to R.drawable.ic_arab_chose,
        "cyrillic_chosen" to R.drawable.ic_kiri_chose,
        "latin_chosen" to R.drawable.ic_latin_chose
    )

    // 视图状态
    private enum class ViewState {
        INITIAL, // 初始状态：只显示转换按钮
        LANGUAGE_SELECTION, // 语言选择状态：显示可选语言 + 取消按钮
        CONVERSION_MODE // 转换模式：显示当前语言 + 取消按钮
    }

    private var currentViewState: ViewState = ViewState.INITIAL

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL

        // 初始化主题管理器
        themeManager = ThemeManager.getInstance(context)
        currentTheme = themeManager.currentTheme
        themeManager.addThemeChangeListener(this)

        // 应用初始主题
        applyTheme(currentTheme)

        // 加载动画
        slideInAnimation = AnimationUtils.loadAnimation(context, android.R.anim.slide_in_left)
        slideOutAnimation = AnimationUtils.loadAnimation(context, android.R.anim.slide_out_right)

        // 创建按钮容器
        buttonsContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
        }

        // 创建转换按钮
        conversionButton = createButton(iconResources["conversion"] ?: 0).apply {
            setOnClickListener {
                showLanguageSelection()
                onConversionLanguageSelectedListener?.onConversionModeToggled()
            }
        }

        // 创建取消按钮
        cancelButton = createButton(iconResources["cancel"] ?: 0).apply {
            setOnClickListener {
                resetToInitialState()
                onConversionLanguageSelectedListener?.onConversionCancelled()
            }
        }

        // 创建当前语言显示按钮（在转换模式下显示，点击可重新选择语言）
        currentLanguageButton = createButton(iconResources["conversion"] ?: 0).apply {
            setOnClickListener {
                showAlternativeLanguages()
            }
        }

        // 创建替代语言按钮1
        altLanguageButton1 = createButton(iconResources["conversion"] ?: 0).apply {
            setOnClickListener {
                val targetLanguage = getTargetLanguageForButton(1)
                targetLanguage?.let {
                    onConversionLanguageSelectedListener?.onConversionLanguageSelected(it)
                    setConversionMode(it)
                }
            }
        }

        // 创建替代语言按钮2
        altLanguageButton2 = createButton(iconResources["conversion"] ?: 0).apply {
            setOnClickListener {
                val targetLanguage = getTargetLanguageForButton(2)
                targetLanguage?.let {
                    onConversionLanguageSelectedListener?.onConversionLanguageSelected(it)
                    setConversionMode(it)
                }
            }
        }

        // 初始状态只显示转换按钮
        updateViewForState(ViewState.INITIAL)

        addView(buttonsContainer)

        // 设置固定高度
        minimumHeight = dpToPx(40) // 增加高度，避免被候选词栏覆盖
    }

    override fun onThemeChanged(theme: KeyboardTheme) {
        currentTheme = theme
        applyTheme(theme)
    }

    private fun applyTheme(theme: KeyboardTheme) {
        // 设置背景颜色
        setBackgroundColor(theme.backgroundColor)

        // 按钮容器背景颜色
        buttonsContainer?.setBackgroundColor(theme.backgroundColor)
    }

    private fun createButton(iconRes: Int): ImageView {
        return ImageView(context).apply {
            setImageResource(iconRes)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4)) // 增加内边距
            gravity = Gravity.CENTER
            isClickable = true
            scaleType = ImageView.ScaleType.FIT_CENTER

            layoutParams = LayoutParams(
                dpToPx(40), // 增加按钮尺寸
                dpToPx(40)  // 增加按钮尺寸
            ).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
        }
    }

    private fun updateViewForState(state: ViewState) {
        currentViewState = state
        buttonsContainer?.removeAllViews()

        when (state) {
            ViewState.INITIAL -> {
                // 只显示转换按钮
                conversionButton?.let { buttonsContainer?.addView(it) }
            }
            ViewState.LANGUAGE_SELECTION -> {
                // 显示可选语言 + 取消按钮
                updateAlternativeLanguageButtons()
                altLanguageButton1?.let { buttonsContainer?.addView(it) }
                altLanguageButton2?.let { buttonsContainer?.addView(it) }
                cancelButton?.let { buttonsContainer?.addView(it) }
            }
            ViewState.CONVERSION_MODE -> {
                // 显示当前语言 + 取消按钮
                updateCurrentLanguageButton()
                currentLanguageButton?.let { buttonsContainer?.addView(it) }
                cancelButton?.let { buttonsContainer?.addView(it) }
            }
        }
    }

    private fun showLanguageSelection() {
        updateViewForState(ViewState.LANGUAGE_SELECTION)
    }

    private fun showAlternativeLanguages() {
        // 在转换模式下点击当前语言按钮，显示替代语言选择
        buttonsContainer?.removeAllViews()
        updateAlternativeLanguageButtons()
        altLanguageButton1?.let { buttonsContainer?.addView(it) }
        altLanguageButton2?.let { buttonsContainer?.addView(it) }
        cancelButton?.let { buttonsContainer?.addView(it) }
    }

    private fun setConversionMode(targetLanguage: KeyboardType) {
        currentConversionState = ConversionManager.ConversionState(
            isConversionMode = true,
            targetLanguage = targetLanguage
        )
        updateViewForState(ViewState.CONVERSION_MODE)
    }

    private fun resetToInitialState() {
        currentConversionState = ConversionManager.ConversionState(
            isConversionMode = false,
            targetLanguage = null
        )
        updateViewForState(ViewState.INITIAL)
    }

    private fun getLanguageIcon(language: KeyboardType, isChosen: Boolean = false): Int {
        return when (language) {
            KeyboardType.LATIN -> if (isChosen) iconResources["latin_chosen"] ?: 0 else iconResources["latin"] ?: 0
            KeyboardType.CYRILLIC_KAZAKH -> if (isChosen) iconResources["cyrillic_chosen"] ?: 0 else iconResources["cyrillic"] ?: 0
            KeyboardType.ARABIC -> if (isChosen) iconResources["arabic_chosen"] ?: 0 else iconResources["arabic"] ?: 0
            else -> iconResources["conversion"] ?: 0
        }
    }

    private fun updateCurrentLanguageButton() {
        val conversionState = currentConversionState
        if (conversionState?.isConversionMode == true && conversionState.targetLanguage != null) {
            currentLanguageButton?.setImageResource(getLanguageIcon(conversionState.targetLanguage!!, true))
        }
    }

    private fun updateAlternativeLanguageButtons() {
        if (availableTargetLanguages.size >= 2) {
            val language1 = availableTargetLanguages[0]
            val language2 = availableTargetLanguages[1]

            altLanguageButton1?.setImageResource(getLanguageIcon(language1, false))
            altLanguageButton2?.setImageResource(getLanguageIcon(language2, false))
        }
    }

    private fun getTargetLanguageForButton(buttonIndex: Int): KeyboardType? {
        return when (buttonIndex) {
            1 -> availableTargetLanguages.getOrNull(0)
            2 -> availableTargetLanguages.getOrNull(1)
            else -> null
        }
    }

    fun setCurrentKeyboardType(keyboardType: KeyboardType, availableLanguages: List<KeyboardType>) {
        currentKeyboardType = keyboardType
        availableTargetLanguages = availableLanguages

        // 如果是中文键盘，隐藏整个转换栏
        if (keyboardType == KeyboardType.CHINESE) {
            visibility = GONE
            return
        } else {
            visibility = VISIBLE
        }

        // 更新语言按钮
        updateAlternativeLanguageButtons()

        // 如果当前在转换模式，确保当前语言按钮正确显示
        if (currentViewState == ViewState.CONVERSION_MODE) {
            updateCurrentLanguageButton()
        }
    }

    fun updateConversionState(conversionState: ConversionManager.ConversionState) {
        this.currentConversionState = conversionState

        // 如果是中文键盘，不显示转换状态
        if (currentKeyboardType == KeyboardType.CHINESE) {
            return
        }

        if (conversionState.isConversionMode && conversionState.targetLanguage != null) {
            // 进入转换模式
            setConversionMode(conversionState.targetLanguage!!)
        } else {
            // 退出转换模式
            resetToInitialState()
        }
    }

    fun setOnConversionLanguageSelectedListener(listener: OnConversionLanguageSelectedListener) {
        this.onConversionLanguageSelectedListener = listener
    }

    fun hideLanguageSelectorIfVisible() {
        if (currentViewState == ViewState.LANGUAGE_SELECTION) {
            resetToInitialState()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        themeManager.removeThemeChangeListener(this)
    }

    // dp 转换方法
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}