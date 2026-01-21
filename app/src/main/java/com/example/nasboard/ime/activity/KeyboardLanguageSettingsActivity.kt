package com.example.nasboard.ime.activity

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.nasboard.KeyboardSettingsManager

class KeyboardLanguageSettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: KeyboardSettingsManager
    private lateinit var latinSwitch: Switch
    private lateinit var cyrillicSwitch: Switch
    private lateinit var arabicSwitch: Switch
    private lateinit var chineseSwitch: Switch
    private lateinit var englishSwitch: Switch
    private lateinit var russianSwitch: Switch
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = KeyboardSettingsManager.Companion.getInstance(this)
        setupLanguageSettingsLayout()
    }

    private fun setupLanguageSettingsLayout() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(50, 50, 50, 50)
        }

        // 标题
        val title = TextView(this).apply {
            text = "键盘语言设置"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 50)
        }
        layout.addView(title)

        // 拉丁文开关
        val latinLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        val latinLabel = TextView(this).apply {
            text = "拉丁文键盘 (Latin)"
            textSize = 18f
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        latinSwitch = Switch(this).apply {
            isChecked = settingsManager.isLatinEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isLatinEnabled = isChecked
                validateAndUpdateSwitches()
            }
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
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        cyrillicSwitch = Switch(this).apply {
            isChecked = settingsManager.isCyrillicEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isCyrillicEnabled = isChecked
                validateAndUpdateSwitches()
            }
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
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        arabicSwitch = Switch(this).apply {
            isChecked = settingsManager.isArabicEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isArabicEnabled = isChecked
                validateAndUpdateSwitches()
            }
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
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        chineseSwitch = Switch(this).apply {
            isChecked = settingsManager.isChineseEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isChineseEnabled = isChecked
                validateAndUpdateSwitches()
            }
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
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        englishSwitch = Switch(this).apply {
            isChecked = settingsManager.isEnglishEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isEnglishEnabled = isChecked
                validateAndUpdateSwitches()
            }
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
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        russianSwitch = Switch(this).apply {
            isChecked = settingsManager.isRussianEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isRussianEnabled = isChecked
                validateAndUpdateSwitches()
            }
        }

        russianLayout.addView(russianLabel)
        russianLayout.addView(russianSwitch)
        layout.addView(russianLayout)

        // 提示文本
        val hintText = TextView(this).apply {
            text = "关闭不需要的键盘可以让语言切换更简洁"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 30, 0, 30)
        }
        layout.addView(hintText)

        // 返回按钮
        backButton = Button(this).apply {
            text = "返回"
            setOnClickListener {
                finish()
            }
        }
        layout.addView(backButton)

        setContentView(layout)
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
}