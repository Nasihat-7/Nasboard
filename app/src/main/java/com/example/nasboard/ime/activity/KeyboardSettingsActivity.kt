package com.example.nasboard.ime.activity

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nasboard.KeyboardSettingsManager
import com.example.nasboard.ThemeAdapter
import com.example.nasboard.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KeyboardSettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: KeyboardSettingsManager
    private lateinit var themeManager: ThemeManager

    // 原有视图组件
    private lateinit var latinSwitch: Switch
    private lateinit var cyrillicSwitch: Switch
    private lateinit var arabicSwitch: Switch
    private lateinit var chineseSwitch: Switch
    private lateinit var englishSwitch: Switch
    private lateinit var russianSwitch: Switch
    private lateinit var resetButton: Button
    private lateinit var backButton: Button

    // 新主题设置视图组件
    private lateinit var currentThemeTextView: TextView
    private lateinit var themesProgressBar: ProgressBar
    private lateinit var themesRecyclerView: RecyclerView
    private lateinit var refreshThemesButton: Button

    private lateinit var themeAdapter: ThemeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = KeyboardSettingsManager.Companion.getInstance(this)
        themeManager = ThemeManager.Companion.getInstance(this)

        // 创建布局 - 在原有基础上添加主题设置
        createLayout()
        setupViews()
        updateSwitchStates()
        updateCurrentSettingsDisplay()
    }

    private fun createLayout() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        // 标题
        val title = TextView(this).apply {
            text = "键盘设置"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 50)
        }
        layout.addView(title)

        // === 原有键盘布局设置部分 ===
        val keyboardSettingsTitle = TextView(this).apply {
            text = "键盘布局设置"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        layout.addView(keyboardSettingsTitle)

        // 拉丁文开关
        val latinLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val latinLabel = TextView(this).apply {
            text = "拉丁文键盘 (Latin)"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        latinSwitch = Switch(this).apply {
            text = ""
        }

        latinLayout.addView(latinLabel)
        latinLayout.addView(latinSwitch)
        layout.addView(latinLayout)

        // 西里尔文开关
        val cyrillicLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val cyrillicLabel = TextView(this).apply {
            text = "西里尔文键盘 (Cyrillic)"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        cyrillicSwitch = Switch(this).apply {
            text = ""
        }

        cyrillicLayout.addView(cyrillicLabel)
        cyrillicLayout.addView(cyrillicSwitch)
        layout.addView(cyrillicLayout)

        // 阿拉伯文开关
        val arabicLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val arabicLabel = TextView(this).apply {
            text = "阿拉伯文键盘 (Arabic)"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        arabicSwitch = Switch(this).apply {
            text = ""
        }

        arabicLayout.addView(arabicLabel)
        arabicLayout.addView(arabicSwitch)
        layout.addView(arabicLayout)

        // 中文开关
        val chineseLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val chineseLabel = TextView(this).apply {
            text = "中文键盘 (Chinese)"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        chineseSwitch = Switch(this).apply {
            text = ""
        }

        chineseLayout.addView(chineseLabel)
        chineseLayout.addView(chineseSwitch)
        layout.addView(chineseLayout)

        // 英文开关
        val englishLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val englishLabel = TextView(this).apply {
            text = "英文键盘 (English)"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        englishSwitch = Switch(this).apply {
            text = ""
        }

        englishLayout.addView(englishLabel)
        englishLayout.addView(englishSwitch)
        layout.addView(englishLayout)

        // 俄文开关
        val russianLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val russianLabel = TextView(this).apply {
            text = "俄文键盘 (Russian)"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }

        russianSwitch = Switch(this).apply {
            text = ""
        }

        russianLayout.addView(russianLabel)
        russianLayout.addView(russianSwitch)
        layout.addView(russianLayout)

        // 键盘布局提示文本
        val keyboardHintText = TextView(this).apply {
            text = "关闭不需要的键盘可以让语言切换更简洁"
            textSize = 14f
            setPadding(0, 30, 0, 30)
            setTextColor(0xFF666666.toInt())
        }
        layout.addView(keyboardHintText)

        // === 新增主题设置部分 ===
        val themeSettingsTitle = TextView(this).apply {
            text = "主题设置"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
        }
        layout.addView(themeSettingsTitle)

        // 当前主题显示
        currentThemeTextView = TextView(this).apply {
            text = "当前主题: 默认"
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(currentThemeTextView)

        // 主题列表
        themesRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400
            )
            setPadding(0, 0, 0, 20)
        }
        layout.addView(themesRecyclerView)

        // 刷新主题按钮
        refreshThemesButton = Button(this).apply {
            text = "刷新主题列表"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setPadding(0, 0, 0, 30)
        }
        layout.addView(refreshThemesButton)

        // 进度条（初始隐藏）
        themesProgressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
        }
        layout.addView(themesProgressBar)

        // === 按钮布局 ===
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }

        resetButton = Button(this).apply {
            text = "恢复默认"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                marginEnd = 20
            }
        }

        backButton = Button(this).apply {
            text = "返回"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                marginStart = 20
            }
        }

        buttonLayout.addView(resetButton)
        buttonLayout.addView(backButton)
        layout.addView(buttonLayout)

        setContentView(layout)
    }

    private fun setupViews() {
        // 设置原有开关监听器
        latinSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isLatinEnabled = isChecked
            validateAndUpdateSwitches()
        }

        cyrillicSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isCyrillicEnabled = isChecked
            validateAndUpdateSwitches()
        }

        arabicSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isArabicEnabled = isChecked
            validateAndUpdateSwitches()
        }

        chineseSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isChineseEnabled = isChecked
            validateAndUpdateSwitches()
        }

        englishSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isEnglishEnabled = isChecked
            validateAndUpdateSwitches()
        }

        russianSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isRussianEnabled = isChecked
            validateAndUpdateSwitches()
        }

        // 设置主题相关视图
        setupThemeSettings()

        // 重置按钮
        resetButton.setOnClickListener {
            settingsManager.resetToDefaults()
            themeManager.setCoreTheme("default")
            updateSwitchStates()
            updateCurrentSettingsDisplay()
            loadThemes()
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        }

        // 返回按钮
        backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupThemeSettings() {
        // 设置主题列表
        themeAdapter = ThemeAdapter { themeInfo ->
            when (themeInfo.type) {
                ThemeManager.ThemeType.CORE -> {
                    themeManager.setCoreTheme(themeInfo.name)
                    Toast.makeText(
                        this,
                        "已切换到主题: ${themeInfo.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                ThemeManager.ThemeType.JSON -> {
                    val success = themeManager.setJsonTheme(themeInfo.name)
                    if (success) {
                        Toast.makeText(
                            this,
                            "已切换到主题: ${themeInfo.displayName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, "主题切换失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            updateCurrentSettingsDisplay()
        }

        themesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@KeyboardSettingsActivity)
            adapter = themeAdapter
        }

        // 加载主题
        loadThemes()

        refreshThemesButton.setOnClickListener {
            loadThemes()
        }
    }

    private fun loadThemes() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                themesProgressBar.visibility = View.VISIBLE
                Log.d("KeyboardSettings", "开始加载主题...")

                val themes = themeManager.getAllThemes()
                Log.d("KeyboardSettings", "加载的主题数据: $themes")

                themeAdapter.submitThemes(themes)
                themesProgressBar.visibility = View.GONE

                // 显示加载结果
                val totalThemes = themes.values.flatten().size
                Toast.makeText(
                    this@KeyboardSettingsActivity,
                    "加载了 $totalThemes 个主题",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e("KeyboardSettings", "加载主题时出错: ${e.message}", e)
                themesProgressBar.visibility = View.GONE
                Toast.makeText(
                    this@KeyboardSettingsActivity,
                    "加载主题失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateSwitchStates() {
        latinSwitch.isChecked = settingsManager.isLatinEnabled
        cyrillicSwitch.isChecked = settingsManager.isCyrillicEnabled
        arabicSwitch.isChecked = settingsManager.isArabicEnabled
        chineseSwitch.isChecked = settingsManager.isChineseEnabled
        englishSwitch.isChecked = settingsManager.isEnglishEnabled
        russianSwitch.isChecked = settingsManager.isRussianEnabled

        validateAndUpdateSwitches()
    }

    private fun validateAndUpdateSwitches() {
        // 确保至少有一个键盘是启用的
        if (!settingsManager.hasEnabledKeyboards()) {
            // 如果没有启用的键盘，自动启用英文键盘
            settingsManager.isEnglishEnabled = true
            englishSwitch.isChecked = true
            Toast.makeText(this, "必须至少启用一个键盘", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCurrentSettingsDisplay() {
        val currentThemeInfo = themeManager.getCurrentThemeInfo()
        currentThemeTextView.text = "当前主题: ${currentThemeInfo.displayName}"
    }
}