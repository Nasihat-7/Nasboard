package com.example.nasboard.ime.emoji

import android.R
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.setPadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class EmojiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnEmojiClickListener {
        fun onEmojiClick(emoji: Emoji)
        fun onBackToKeyboard()
        fun onSearchKeyPress(key: String) // 新增：处理搜索按键
    }

    private var onEmojiClickListener: OnEmojiClickListener? = null
    private lateinit var emojiManager: EmojiManager
    private lateinit var emojiHistoryManager: EmojiHistoryManager

    // UI组件
    private lateinit var topBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var deleteButton: Button
    private lateinit var currentCategoryText: TextView
    private lateinit var emojiRecyclerView: RecyclerView
    private lateinit var categoryRecyclerView: RecyclerView
    private lateinit var searchEditText: EditText
    private lateinit var searchBackButton: Button

    private var currentCategory = EmojiCategory.SMILEYS_EMOTION
    private var isSearchMode = false
    private var searchResults = emptyList<Emoji>()

    // 搜索键盘按键
    private val searchKeyboardKeys = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M", "DEL"),
        listOf("SPACE", "SEARCH", "BACK")
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        initEmojiManager()
        setupUI()
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener) {
        this.onEmojiClickListener = listener
    }

    private fun initEmojiManager() {
        emojiManager = EmojiManager(context)
        emojiHistoryManager = EmojiHistoryManager.getInstance(context)
        emojiHistoryManager.setEmojiManager(emojiManager)
        emojiManager.loadEmojis()
    }

    private fun setupUI() {
        setupTopBar()
        setupSearchSection()
        setupEmojiGrid()
        setupCategoryBar()

        // 默认显示第一个类别的表情
        showCategory(EmojiCategory.SMILEYS_EMOTION)
    }

    private fun setupTopBar() {
        topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 0)
            }
        }

        // 返回键盘按钮
        backButton = Button(context).apply {
            text = "← 键盘"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.BLUE)
            setOnClickListener {
                onEmojiClickListener?.onBackToKeyboard()
            }
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        // 当前类别显示
        currentCategoryText = TextView(context).apply {
            text = "表情情感"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            layoutParams = LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        // 删除按钮 - 现在用于删除搜索文本
        deleteButton = Button(context).apply {
            text = "⌫"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.RED)
            setOnClickListener {
                handleDeleteInSearch()
            }
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            visibility = GONE // 初始隐藏
        }

        topBar.addView(backButton)
        topBar.addView(currentCategoryText)
        topBar.addView(deleteButton)
        addView(topBar)
    }

    private fun setupSearchSection() {
        val searchContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        // 搜索返回按钮（初始隐藏）
        searchBackButton = Button(context).apply {
            text = "←"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.BLUE)
            visibility = GONE
            setOnClickListener {
                exitSearchMode()
            }
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        // 搜索框
        searchEditText = EditText(context).apply {
            hint = "搜索表情..."
            layoutParams = LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            // 重要：启用焦点，这样点击时可以触发输入法
            isFocusable = true
            isFocusableInTouchMode = true
        }

        searchContainer.addView(searchBackButton)
        searchContainer.addView(searchEditText)
        addView(searchContainer)

        setupSearch()
    }

    private fun setupEmojiGrid() {
        emojiRecyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            layoutManager = GridLayoutManager(context, 8) // 8列网格
            setBackgroundColor(Color.WHITE)
        }
        addView(emojiRecyclerView)
    }

    private fun setupCategoryBar() {
        categoryRecyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        addView(categoryRecyclerView)

        setupCategories()
    }

    private fun setupCategories() {
        val categories = emojiManager.getAllCategories()
        // 在类别列表开头添加"最近使用"和"收藏"类别
        val allCategories = mutableListOf<EmojiCategory>()
        allCategories.add(EmojiCategory.RECENT) // 最近使用
        allCategories.add(EmojiCategory.FAVORITE) // 收藏
        allCategories.addAll(categories)

        val categoryAdapter = CategoryAdapter(allCategories) { category ->
            showCategory(category)
        }
        categoryRecyclerView.adapter = categoryAdapter
    }

    private fun setupSearch() {
        searchEditText.setOnClickListener {
            enterSearchMode()
        }

        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                enterSearchMode()
            }
        }

        // 监听文本变化
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.isNotEmpty()) {
                    performSearch(query)
                } else {
                    // 清空搜索结果显示最近使用的表情
                    showCategory(EmojiCategory.RECENT)
                }
            }
        })
    }

    private fun handleDeleteInSearch() {
        val currentText = searchEditText.text.toString()
        if (currentText.isNotEmpty()) {
            val newText = currentText.substring(0, currentText.length - 1)
            searchEditText.setText(newText)
            searchEditText.setSelection(newText.length) // 设置光标位置
        }
    }

    private fun enterSearchMode() {
        isSearchMode = true
        searchBackButton.visibility = VISIBLE
        deleteButton.visibility = VISIBLE // 显示删除按钮
        currentCategoryText.text = "搜索表情"
        categoryRecyclerView.visibility = GONE

        // 请求焦点并显示软键盘
        searchEditText.requestFocus()

        // 显示最近使用的表情作为初始搜索结果
        showCategory(EmojiCategory.RECENT)
    }

    private fun exitSearchMode() {
        isSearchMode = false
        searchBackButton.visibility = GONE
        deleteButton.visibility = GONE // 隐藏删除按钮
        searchEditText.setText("")
        categoryRecyclerView.visibility = VISIBLE
        showCategory(currentCategory)

        // 清除焦点
        searchEditText.clearFocus()
    }

    private fun performSearch(query: String) {
        searchResults = emojiManager.searchEmojis(query)
        showSearchResults()
        Log.d("EmojiView", "Search results: ${searchResults.size} for query: $query")
    }

    private fun showCategory(category: EmojiCategory) {
        currentCategory = category
        currentCategoryText.text = when (category) {
            EmojiCategory.RECENT -> "最近使用"
            EmojiCategory.FAVORITE -> "收藏"
            EmojiCategory.SMILEYS_EMOTION -> "表情情感"
            EmojiCategory.PEOPLE_BODY -> "人物身体"
            EmojiCategory.ANIMALS_NATURE -> "动物自然"
            EmojiCategory.FOOD_DRINK -> "食物饮料"
            EmojiCategory.TRAVEL_PLACES -> "旅行地点"
            EmojiCategory.ACTIVITIES -> "活动"
            EmojiCategory.OBJECTS -> "物品"
            EmojiCategory.SYMBOLS -> "符号"
            EmojiCategory.FLAGS -> "旗帜"
        }

        val emojis = when (category) {
            EmojiCategory.RECENT -> emojiHistoryManager.getRecentEmojis()
            EmojiCategory.FAVORITE -> emojiHistoryManager.getFavoriteEmojis()
            else -> emojiManager.getEmojisByCategory(category)
        }
        showEmojis(emojis)
        Log.d("EmojiView", "Showing category: $category with ${emojis.size} emojis")
    }

    private fun showSearchResults() {
        showEmojis(searchResults)
    }

    private fun showEmojis(emojis: List<Emoji>) {
        // 过滤掉变体表情，只显示基础表情
        val baseEmojis = emojis.filter { emoji ->
            // 只显示没有变体的表情，或者有变体但自己是基础表情（即不在任何其他表情的变体列表中）
            !emoji.hasVariants || emoji.variants.isEmpty()
        }

        val emojiAdapter = EmojiAdapter(baseEmojis) { emoji ->
            handleEmojiSelection(emoji)
        }
        emojiRecyclerView.adapter = emojiAdapter
    }

    private fun handleEmojiSelection(emoji: Emoji) {
        if (emoji.hasVariants && emoji.variants.isNotEmpty()) {
            showEmojiVariants(emoji)
        } else {
            insertEmoji(emoji)
        }
    }

    private fun insertEmoji(emoji: Emoji) {
        onEmojiClickListener?.onEmojiClick(emoji)
        // 记录到历史
        emojiHistoryManager.addToHistory(emoji)
    }

    private fun showEmojiVariants(baseEmoji: Emoji) {
        // 创建变体选择弹出窗口
        val variantDialog = AlertDialog.Builder(context)
            .setTitle("选择肤色")
            .setCancelable(true)
            .create()

        val variantLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(16)
            gravity = Gravity.CENTER
        }

        // 添加基础表情
        val baseView = TextView(context).apply {
            text = baseEmoji.value
            textSize = 24f
            setPadding(16)
            setBackgroundResource(R.drawable.btn_default)
            setOnClickListener {
                insertEmoji(baseEmoji)
                variantDialog.dismiss()
            }
        }
        variantLayout.addView(baseView)

        // 添加所有变体
        baseEmoji.variants.forEach { variant ->
            val variantView = TextView(context).apply {
                text = variant.value
                textSize = 24f
                setPadding(16)
                setBackgroundResource(R.drawable.btn_default)
                setOnClickListener {
                    insertEmoji(variant)
                    variantDialog.dismiss()
                }
            }
            variantLayout.addView(variantView)
        }

        variantDialog.setView(variantLayout)
        variantDialog.window?.setBackgroundDrawableResource(R.color.white)
        variantDialog.show()
    }

    // 处理搜索按键（从主键盘传递过来）
    fun handleSearchKeyPress(key: String) {
        if (isSearchMode) {
            when (key) {
                "DEL" -> {
                    handleDeleteInSearch()
                }
                "SPACE" -> {
                    // 空格键添加空格
                    val currentText = searchEditText.text.toString()
                    searchEditText.setText(currentText + " ")
                    searchEditText.setSelection(currentText.length + 1)
                }
                else -> {
                    if (key.length == 1) {
                        val currentText = searchEditText.text.toString()
                        searchEditText.setText(currentText + key)
                        searchEditText.setSelection(currentText.length + 1)
                    }
                }
            }
        }
    }

    // 当表情界面显示时调用
    fun onEmojiViewShown() {
        // 确保不在搜索模式
        exitSearchMode()
    }

    // Emoji Adapter
    private inner class EmojiAdapter(
        private val emojis: List<Emoji>,
        private val onEmojiClick: (Emoji) -> Unit
    ) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {

        inner class EmojiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val emojiText: TextView = itemView.findViewById(com.example.nasboard.R.id.emoji_text)
            val variantIndicator: View = itemView.findViewById(com.example.nasboard.R.id.variant_indicator)
            val favoriteIndicator: View = itemView.findViewById(com.example.nasboard.R.id.favorite_indicator)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(com.example.nasboard.R.layout.emoji_item, parent, false)
            return EmojiViewHolder(view)
        }

        override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
            val emoji = emojis[position]
            holder.emojiText.text = emoji.value

            // 显示变体指示器
            holder.variantIndicator.visibility = if (emoji.hasVariants && emoji.variants.isNotEmpty()) VISIBLE else GONE

            // 显示收藏指示器
            val isFavorite = emojiHistoryManager.isFavorite(emoji)
            holder.favoriteIndicator.visibility = if (isFavorite) VISIBLE else GONE

            holder.itemView.setOnClickListener {
                onEmojiClick(emoji)
            }

            holder.itemView.setOnLongClickListener {
                // 长按收藏/取消收藏
                if (emojiHistoryManager.isFavorite(emoji)) {
                    emojiHistoryManager.removeFromFavorites(emoji)
                } else {
                    emojiHistoryManager.addToFavorites(emoji)
                }
                notifyItemChanged(position)
                true
            }
        }

        override fun getItemCount(): Int = emojis.size
    }

    // Category Adapter
    private inner class CategoryAdapter(
        private val categories: List<EmojiCategory>,
        private val onCategoryClick: (EmojiCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

        inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val categoryText: TextView = itemView.findViewById(com.example.nasboard.R.id.category_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(com.example.nasboard.R.layout.category_item, parent, false)
            return CategoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            val category = categories[position]
            val emojiIcon = when (category) {
                EmojiCategory.RECENT -> "🕒"
                EmojiCategory.FAVORITE -> "⭐"
                EmojiCategory.SMILEYS_EMOTION -> "😊"
                EmojiCategory.PEOPLE_BODY -> "👋"
                EmojiCategory.ANIMALS_NATURE -> "🐕"
                EmojiCategory.FOOD_DRINK -> "🍎"
                EmojiCategory.TRAVEL_PLACES -> "🚗"
                EmojiCategory.ACTIVITIES -> "⚽"
                EmojiCategory.OBJECTS -> "💡"
                EmojiCategory.SYMBOLS -> "💖"
                EmojiCategory.FLAGS -> "🚩"
            }
            holder.categoryText.text = emojiIcon

            holder.itemView.setOnClickListener {
                onCategoryClick(category)
            }
        }

        override fun getItemCount(): Int = categories.size
    }
}