package com.example.nasboard.ime.candidate

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import com.example.nasboard.ime.theme.KeyboardTheme
import com.example.nasboard.ime.theme.ThemeManager

class CandidateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), ThemeManager.OnThemeChangeListener {

    // 拼音显示区域（中文模式）
    private var pinyinTextView: TextView? = null

    // 候选词容器
    private var candidateContainer: LinearLayout? = null

    // 扩展按钮
    private var expandButton: TextView? = null

    // 全屏候选词容器（用于展开模式）
    private var expandedCandidateScrollView: ScrollView? = null
    private var expandedCandidateContainer: LinearLayout? = null

    // 当前显示模式
    private var isChineseMode = false
    var isExpanded = false
    private var maxVisibleCandidates = 3 // 正常模式下显示3个候选词

    private var onCandidateClickListener: ((String) -> Unit)? = null
    private var onExpandClickListener: (() -> Unit)? = null

    // 主题管理器
    private lateinit var themeManager: ThemeManager
    private var currentTheme: KeyboardTheme = KeyboardTheme.DEFAULT

    // 当前候选词列表
    private var currentCandidates: List<String> = emptyList()

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)

        // 初始化主题管理器
        themeManager = ThemeManager.getInstance(context)
        currentTheme = themeManager.currentTheme
        themeManager.addThemeChangeListener(this)

        // 应用初始主题
        applyTheme(currentTheme)

        // 创建主布局
        createMainLayout()

        // 设置固定高度，确保有足够空间显示候选词
        minimumHeight = dpToPx(60) // 设置固定高度
    }

    private fun createMainLayout() {
        removeAllViews()

        // 创建主容器 - 使用水平布局
        val mainContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        // 创建候选词容器 - 使用权重填满剩余空间
        candidateContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, // 宽度设为0，使用权重
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                weight = 1f
            }
        }

        // 创建扩展按钮
        expandButton = TextView(context).apply {
            text = "▶" // 保持箭头图标不变
            setTextColor(currentTheme.textColor)
            textSize = 18f
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)

            setOnClickListener {
                onExpandClickListener?.invoke()
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginStart = dpToPx(8)
            }

            // 初始隐藏扩展按钮
            visibility = View.GONE
        }

        mainContainer.addView(candidateContainer)
        mainContainer.addView(expandButton)
        addView(mainContainer)

        // 创建拼音显示区域（初始隐藏）
        pinyinTextView = TextView(context).apply {
            text = ""
            setTextColor(currentTheme.textColor)
            textSize = 18f
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(4))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isClickable = false
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                dpToPx(30)
            )
        }
        addView(pinyinTextView)

        // 创建全屏候选词容器（初始隐藏）- 使用ScrollView支持滚动
        expandedCandidateScrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                0 // 高度设为0，展开时动态设置
            )
            visibility = View.GONE
            setBackgroundColor(currentTheme.backgroundColor)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            // 设置滚动条样式
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // 设置滚动条大小和颜色
            setScrollBarSize(dpToPx(4))
        }

        expandedCandidateContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        expandedCandidateScrollView?.addView(expandedCandidateContainer)
        addView(expandedCandidateScrollView)
    }

    // 设置滚动条大小的方法
    private fun ScrollView.setScrollBarSize(size: Int) {
        try {
            val field = View::class.java.getDeclaredField("mVerticalScrollbarWidth")
            field.isAccessible = true
            field.set(this, size)
        } catch (e: Exception) {
            Log.e("CandidateView", "Failed to set scrollbar size: ${e.message}")
        }
    }

    override fun onThemeChanged(theme: KeyboardTheme) {
        currentTheme = theme
        applyTheme(theme)
    }

    private fun applyTheme(theme: KeyboardTheme) {
        setBackgroundColor(theme.backgroundColor)
        updateCandidatesTheme()
        expandButton?.setTextColor(theme.textColor)
        expandButton?.setBackgroundColor(Color.TRANSPARENT)
        pinyinTextView?.setTextColor(theme.textColor)
        expandedCandidateScrollView?.setBackgroundColor(theme.backgroundColor)
        invalidate()
    }

    private fun updateCandidatesTheme() {
        candidateContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is TextView) {
                    val textColor = if (i == 0) {
                        Color.BLUE // 第一个候选词保持蓝色
                    } else {
                        currentTheme.textColor
                    }
                    child.setTextColor(textColor)
                    child.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
    }

    fun setChineseMode(enabled: Boolean) {
        isChineseMode = enabled
        pinyinTextView?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    fun updatePinyin(pinyin: String) {
        Log.d("CandidateView", "Updating pinyin: '$pinyin'")
        pinyinTextView?.text = pinyin
    }

    fun updateCandidates(candidates: List<String>) {
        Log.d("CandidateView", "Updating candidates: $candidates")
        currentCandidates = candidates

        // 更新扩展按钮可见性
        updateExpandButtonVisibility()

        // 显示候选词
        displayCandidates()
    }

    fun updateKazakhPredictions(currentInput: String, predictions: List<String>) {
        Log.d("CandidateView", "Updating Kazakh predictions - input: '$currentInput', predictions: $predictions")

        // 第一个候选词是当前输入，后面是预测词
        val allCandidates = mutableListOf(currentInput)
        allCandidates.addAll(predictions)

        currentCandidates = allCandidates
        updateExpandButtonVisibility()
        displayCandidates()
    }

    fun updateSimplePreview(previewText: String) {
        Log.d("CandidateView", "Updating simple preview: '$previewText'")
        val candidates = if (previewText.isNotEmpty()) {
            listOf(previewText)
        } else {
            emptyList()
        }
        updateCandidates(candidates)
    }

    private fun updateExpandButtonVisibility() {
        val shouldShowExpand = currentCandidates.size > maxVisibleCandidates
        expandButton?.visibility = if (shouldShowExpand) View.VISIBLE else View.GONE
    }

    private fun displayCandidates() {
        candidateContainer?.removeAllViews()
        expandedCandidateContainer?.removeAllViews()

        if (isExpanded) {
            // 展开模式：显示所有候选词，使用垂直列表布局
            displayExpandedCandidates()
        } else {
            // 正常模式：显示部分候选词
            displayNormalCandidates()
        }
    }

    private fun displayNormalCandidates() {
        val candidatesToShow = currentCandidates.take(maxVisibleCandidates)

        if (candidatesToShow.isNotEmpty()) {
            candidatesToShow.forEachIndexed { index, candidate ->
                createCandidateTextView(candidate, index == 0, candidatesToShow.size)?.let {
                    candidateContainer?.addView(it)
                }

                // 在候选词之间添加分割线（除了最后一个）
                if (index < candidatesToShow.size - 1) {
                    createDividerView()?.let {
                        candidateContainer?.addView(it)
                    }
                }
            }
        } else {
            // 没有候选词时，显示提示文本
            createNoCandidateTextView()?.let {
                candidateContainer?.addView(it)
            }
        }
    }

    private fun displayExpandedCandidates() {
        if (currentCandidates.isEmpty()) {
            createNoCandidateTextView()?.let {
                expandedCandidateContainer?.addView(it)
            }
            return
        }

        // 展开模式：显示所有候选词，每行一个
        Log.d("CandidateView", "Displaying ${currentCandidates.size} candidates in expanded mode")

        currentCandidates.forEachIndexed { index, candidate ->
            createExpandedCandidateTextView(candidate, index == 0)?.let {
                expandedCandidateContainer?.addView(it)
            }
        }
    }

    private fun createCandidateTextView(text: String, isFirst: Boolean = false, totalCandidates: Int = 1): TextView? {
        return TextView(context).apply {
            this.text = text
            setTextColor(if (isFirst) Color.BLUE else currentTheme.textColor)
            textSize = 24f
            setPadding(dpToPx(8), dpToPx(12), dpToPx(8), dpToPx(12))
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)

            setOnClickListener {
                onCandidateClickListener?.invoke(text)
            }

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                weight = 1f
            }
        }
    }

    private fun createExpandedCandidateTextView(text: String, isFirst: Boolean = false): TextView? {
        return TextView(context).apply {
            this.text = text
            setTextColor(if (isFirst) Color.BLUE else currentTheme.textColor)
            textSize = 20f
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isClickable = true
            setBackgroundResource(android.R.drawable.btn_default)

            setOnClickListener {
                onCandidateClickListener?.invoke(text)
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            ).apply {
                bottomMargin = dpToPx(4)
            }
        }
    }

    private fun createDividerView(): View? {
        return View(context).apply {
            setBackgroundColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(1),
                dpToPx(24)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
        }
    }

    private fun createNoCandidateTextView(): TextView? {
        return TextView(context).apply {
            text = ""
            setTextColor(Color.GRAY)
            textSize = 20f
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            gravity = Gravity.CENTER
            isClickable = false
            setBackgroundColor(Color.TRANSPARENT)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    fun expandCandidates(parentHeight: Int) {
        isExpanded = true
        expandButton?.text = "◀" // 收起图标

        // 显示全屏候选词容器，隐藏普通候选词容器
        candidateContainer?.visibility = View.GONE
        pinyinTextView?.visibility = View.GONE
        expandedCandidateScrollView?.visibility = View.VISIBLE

        // 关键修复：确保ScrollView有正确的高度
        val scrollViewHeight = parentHeight - dpToPx(60) // 减去候选栏高度
        Log.d("CandidateView", "Setting scroll view height: $scrollViewHeight, parentHeight: $parentHeight")

        expandedCandidateScrollView?.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            scrollViewHeight
        )

        displayCandidates()

        // 强制刷新布局
        requestLayout()
        invalidate()

        // 延迟检查滚动状态
        post {
            checkScrollViewContent()
        }
    }

    private fun checkScrollViewContent() {
        expandedCandidateScrollView?.let { scrollView ->
            expandedCandidateContainer?.let { container ->
                val canScroll = container.height > scrollView.height
                Log.d("CandidateView", "ScrollView height: ${scrollView.height}, Content height: ${container.height}, Can scroll: $canScroll")

                // 即使内容不足以滚动，也不添加测试候选词
                // 只显示实际候选词
            }
        }
    }

    fun collapseCandidates() {
        isExpanded = false
        expandButton?.text = "▶" // 展开图标

        // 显示普通候选词容器，隐藏全屏候选词容器
        candidateContainer?.visibility = View.VISIBLE
        if (isChineseMode) {
            pinyinTextView?.visibility = View.VISIBLE
        }
        expandedCandidateScrollView?.visibility = View.GONE

        // 恢复滚动视图高度为0
        expandedCandidateScrollView?.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            0
        )

        displayCandidates()

        // 请求重新布局
        requestLayout()
    }

    fun toggleExpanded(parentHeight: Int) {
        if (isExpanded) {
            collapseCandidates()
        } else {
            expandCandidates(parentHeight)
        }
    }

    fun clearAll() {
        if (isChineseMode) {
            pinyinTextView?.text = ""
        }
        currentCandidates = emptyList()
        candidateContainer?.removeAllViews()
        expandedCandidateContainer?.removeAllViews()
        expandButton?.visibility = View.GONE
        isExpanded = false
        expandButton?.text = "▶"

        // 确保恢复正常显示
        candidateContainer?.visibility = View.VISIBLE
        if (isChineseMode) {
            pinyinTextView?.visibility = View.VISIBLE
        }
        expandedCandidateScrollView?.visibility = View.GONE

        // 恢复滚动视图高度为0
        expandedCandidateScrollView?.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            0
        )
    }

    fun setOnCandidateClickListener(listener: (String) -> Unit) {
        this.onCandidateClickListener = listener
    }

    fun setOnExpandClickListener(listener: () -> Unit) {
        this.onExpandClickListener = listener
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