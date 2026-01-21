package com.example.nasboard

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * JSON主题管理器 - 单例模式
 * 负责管理从JSON文件加载的主题
 */
class JsonThemeManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: JsonThemeManager? = null

        fun getInstance(context: Context): JsonThemeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JsonThemeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val jsonThemes = mutableMapOf<String, JsonTheme>()
    private var isLoaded = false

    /**
     * 从assets加载所有JSON主题
     */
    suspend fun loadJsonThemes(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isLoaded) {
                Log.d("JsonThemeManager", "Themes already loaded, skipping")
                return@withContext true
            }

            Log.d("JsonThemeManager", "Loading JSON themes from assets")

            // 检查主题目录是否存在
            val themeFiles = try {
                context.assets.list("keyboard_themes")?.toList() ?: emptyList()
            } catch (e: Exception) {
                Log.e("JsonThemeManager", "Error listing theme directory: ${e.message}")
                emptyList()
            }

            Log.d("JsonThemeManager", "Found theme files: $themeFiles")

            if (themeFiles.isEmpty()) {
                Log.w("JsonThemeManager", "No theme files found in keyboard_themes directory")
                return@withContext false
            }

            themeFiles.forEach { fileName ->
                if (fileName.endsWith(".json")) {
                    try {
                        val themePath = "keyboard_themes/$fileName"
                        Log.d("JsonThemeManager", "Loading theme: $themePath")

                        context.assets.open(themePath).use { inputStream ->
                            InputStreamReader(inputStream).use { reader ->
                                val theme = gson.fromJson(reader, JsonTheme::class.java)
                                val themeName = fileName.removeSuffix(".json")
                                jsonThemes[themeName] = theme
                                Log.d("JsonThemeManager", "Successfully loaded theme: ${theme.name} -> $themeName")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("JsonThemeManager", "Error loading theme file $fileName: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }

            isLoaded = true
            Log.d("JsonThemeManager", "Total JSON themes loaded: ${jsonThemes.size}")
            Log.d("JsonThemeManager", "Available themes: ${jsonThemes.keys}")
            true
        } catch (e: Exception) {
            Log.e("JsonThemeManager", "Error loading JSON themes: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取所有JSON主题名称
     */
    fun getJsonThemeNames(): List<String> {
        return jsonThemes.keys.toList()
    }

    /**
     * 获取JSON主题
     */
    fun getJsonTheme(themeName: String): JsonTheme? {
        return jsonThemes[themeName]
    }

    /**
     * 获取所有JSON主题
     */
    fun getAllJsonThemes(): Map<String, JsonTheme> {
        return jsonThemes.toMap()
    }

    /**
     * 检查主题是否存在
     */
    fun hasJsonTheme(themeName: String): Boolean {
        return jsonThemes.containsKey(themeName)
    }

    /**
     * 清除所有JSON主题
     */
    fun clearThemes() {
        jsonThemes.clear()
        isLoaded = false
    }

    /**
     * 检查是否已加载主题
     */
    fun isThemesLoaded(): Boolean {
        return isLoaded
    }
}