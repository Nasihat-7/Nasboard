// ThemeSettingsActivity.kt
package com.example.nasboard.ime.activity

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nasboard.ThemeAdapter
import com.example.nasboard.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var themeManager: ThemeManager
    private lateinit var currentThemeTextView: TextView
    private lateinit var themesProgressBar: ProgressBar
    private lateinit var themesRecyclerView: RecyclerView
    private lateinit var refreshThemesButton: Button
    private lateinit var backButton: Button

    private lateinit var themeAdapter: ThemeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeManager = ThemeManager.Companion.getInstance(this)
        setupThemeSettingsLayout()
    }

    private fun setupThemeSettingsLayout() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(50, 50, 50, 50)
        }

        // 标题
        val title = TextView(this).apply {
            text = "主题设置"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 50)
        }
        layout.addView(title)

        // 当前主题显示
        currentThemeTextView = TextView(this).apply {
            text = "当前主题: 默认"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
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
            setOnClickListener {
                loadThemes()
            }
        }
        layout.addView(refreshThemesButton)

        // 进度条（初始隐藏）
        themesProgressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        layout.addView(themesProgressBar)

        // 返回按钮
        backButton = Button(this).apply {
            text = "返回"
            setOnClickListener {
                finish()
            }
        }
        layout.addView(backButton)

        setContentView(layout)
        setupThemeSettings()
    }

    private fun setupThemeSettings() {
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
            layoutManager = LinearLayoutManager(this@ThemeSettingsActivity)
            adapter = themeAdapter
        }

        loadThemes()
    }

    private fun loadThemes() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                themesProgressBar.visibility = View.VISIBLE

                val themes = themeManager.getAllThemes()
                themeAdapter.submitThemes(themes)
                themesProgressBar.visibility = View.GONE

                val totalThemes = themes.values.flatten().size
                Toast.makeText(
                    this@ThemeSettingsActivity,
                    "加载了 $totalThemes 个主题",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                themesProgressBar.visibility = View.GONE
                Toast.makeText(
                    this@ThemeSettingsActivity,
                    "加载主题失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateCurrentSettingsDisplay() {
        val currentThemeInfo = themeManager.getCurrentThemeInfo()
        currentThemeTextView.text = "当前主题: ${currentThemeInfo.displayName}"
    }
}