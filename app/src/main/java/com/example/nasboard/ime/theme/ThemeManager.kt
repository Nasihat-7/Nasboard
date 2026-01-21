package com.example.nasboard.ime.theme

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.nasboard.ime.theme.ThemeInfo
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主题管理器 - 单例模式
 * 负责管理键盘主题的加载、保存和切换
 * 现在支持核心主题和JSON主题
 */
class ThemeManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThemeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("keyboard_theme", Context.MODE_PRIVATE)
    }

    private val gson = Gson()
    private val jsonThemeManager = JsonThemeManager.getInstance(context)

    // 当前主题
    var currentTheme: KeyboardTheme = KeyboardTheme.DEFAULT
        private set

    // 当前主题类型和名称
    private var currentThemeType: ThemeType = ThemeType.CORE
    private var currentThemeName: String = "default"

    // 主题变化监听器
    private val themeChangeListeners = mutableListOf<OnThemeChangeListener>()

    init {
        loadSavedTheme()
        loadJsonThemesAsync()
    }

    /**
     * 主题类型枚举
     */
    enum class ThemeType {
        CORE, JSON
    }

    /**
     * 主题变化监听器接口
     */
    interface OnThemeChangeListener {
        fun onThemeChanged(theme: KeyboardTheme)
    }

    /**
     * 从 SharedPreferences 加载保存的主题
     */
    private fun loadSavedTheme() {
        val themeTypeString = sharedPreferences.getString("theme_type", "CORE")
        val themeName = sharedPreferences.getString("theme_name", "default") ?: "default"

        currentThemeType = try {
            ThemeType.valueOf(themeTypeString ?: "CORE")
        } catch (e: Exception) {
            ThemeType.CORE
        }
        currentThemeName = themeName

        when (currentThemeType) {
            ThemeType.CORE -> {
                currentTheme = when (themeName) {
                    "dark" -> KeyboardTheme.DARK
                    "material" -> KeyboardTheme.MATERIAL
                    else -> KeyboardTheme.DEFAULT
                }
            }
            ThemeType.JSON -> {
                val jsonTheme = jsonThemeManager.getJsonTheme(themeName)
                if (jsonTheme != null) {
                    currentTheme = jsonTheme.toKeyboardTheme()
                } else {
                    // 如果JSON主题不存在，回退到默认主题
                    Log.w("ThemeManager", "JSON theme not found: $themeName, falling back to default")
                    currentThemeType = ThemeType.CORE
                    currentThemeName = "default"
                    currentTheme = KeyboardTheme.DEFAULT
                    saveThemePreference()
                }
            }
        }

        Log.d("ThemeManager", "Loaded theme: type=$currentThemeType, name=$currentThemeName")
    }

    /**
     * 异步加载JSON主题
     */
    private fun loadJsonThemesAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            val success = jsonThemeManager.loadJsonThemes()
            Log.d("ThemeManager", "Async JSON themes loading: $success")
        }
    }

    /**
     * 保存主题偏好设置
     */
    private fun saveThemePreference() {
        sharedPreferences.edit()
            .putString("theme_type", currentThemeType.name)
            .putString("theme_name", currentThemeName)
            .apply()
    }

    /**
     * 切换到核心主题
     */
    fun setCoreTheme(themeName: String) {
        currentThemeType = ThemeType.CORE
        currentThemeName = themeName

        currentTheme = when (themeName) {
            "dark" -> KeyboardTheme.DARK
            "material" -> KeyboardTheme.MATERIAL
            else -> KeyboardTheme.DEFAULT
        }

        saveThemePreference()
        notifyThemeChanged()
        Log.d("ThemeManager", "Set core theme: $themeName")
    }

    /**
     * 切换到JSON主题
     */
    fun setJsonTheme(themeName: String): Boolean {
        val jsonTheme = jsonThemeManager.getJsonTheme(themeName)
        if (jsonTheme != null) {
            currentThemeType = ThemeType.JSON
            currentThemeName = themeName
            currentTheme = jsonTheme.toKeyboardTheme()

            saveThemePreference()
            notifyThemeChanged()
            Log.d("ThemeManager", "Set JSON theme: $themeName")
            return true
        }
        Log.e("ThemeManager", "JSON theme not found: $themeName")
        return false
    }

    /**
     * 兼容旧代码的方法
     */
    fun setThemeByName(themeName: String) {
        Log.d("ThemeManager", "setThemeByName called with: $themeName")
        if (themeName.startsWith("json_")) {
            val realThemeName = themeName.removePrefix("json_")
            setJsonTheme(realThemeName)
        } else {
            setCoreTheme(themeName)
        }
    }

    /**
     * 获取所有可用主题（核心+JSON）
     */
    suspend fun getAllThemes(): Map<String, List<ThemeInfo>> {
        // 确保JSON主题已加载
        val loadSuccess = jsonThemeManager.loadJsonThemes()
        Log.d("ThemeManager", "JSON themes loaded: $loadSuccess")

        val coreThemes = listOf(
            ThemeInfo("default", "默认", ThemeType.CORE),
            ThemeInfo("dark", "深色", ThemeType.CORE),
            ThemeInfo("material", "材质", ThemeType.CORE)
        )

        val jsonThemeNames = jsonThemeManager.getJsonThemeNames()
        Log.d("ThemeManager", "Found JSON themes: $jsonThemeNames")

        val jsonThemes = jsonThemeNames.map { themeName ->
            val theme = jsonThemeManager.getJsonTheme(themeName)
            val displayName = theme?.displayName ?: themeName
            Log.d("ThemeManager", "JSON theme: $themeName -> $displayName")
            ThemeInfo(themeName, displayName, ThemeType.JSON)
        }

        val result = mapOf(
            "核心主题" to coreThemes,
            "扩展主题" to jsonThemes
        )

        Log.d("ThemeManager", "Total themes available: ${coreThemes.size + jsonThemes.size}")
        return result
    }

    /**
     * 获取当前主题信息
     */
    fun getCurrentThemeInfo(): ThemeInfo {
        return ThemeInfo(currentThemeName, getCurrentThemeDisplayName(), currentThemeType)
    }

    /**
     * 获取当前主题显示名称
     */
    private fun getCurrentThemeDisplayName(): String {
        return when (currentThemeType) {
            ThemeType.CORE -> when (currentThemeName) {
                "default" -> "默认"
                "dark" -> "深色"
                "material" -> "材质"
                else -> currentThemeName
            }
            ThemeType.JSON -> {
                jsonThemeManager.getJsonTheme(currentThemeName)?.displayName ?: currentThemeName
            }
        }
    }

    /**
     * 通知主题变化
     */
    private fun notifyThemeChanged() {
        themeChangeListeners.forEach { listener ->
            listener.onThemeChanged(currentTheme)
        }
    }

    /**
     * 添加主题变化监听器
     */
    fun addThemeChangeListener(listener: OnThemeChangeListener) {
        themeChangeListeners.add(listener)
    }

    /**
     * 移除主题变化监听器
     */
    fun removeThemeChangeListener(listener: OnThemeChangeListener) {
        themeChangeListeners.remove(listener)
    }

    /**
     * 获取当前主题名称（兼容旧代码）
     */
    fun getCurrentThemeName(): String {
        return when (currentThemeType) {
            ThemeType.CORE -> currentThemeName
            ThemeType.JSON -> "json_$currentThemeName"
        }
    }
}