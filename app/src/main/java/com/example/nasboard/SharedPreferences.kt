package com.example.nasboard

import android.content.Context
import android.content.SharedPreferences

class KeyboardSettingsManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "keyboard_settings"
        private const val KEY_LATIN_ENABLED = "latin_enabled"
        private const val KEY_CYRILLIC_ENABLED = "cyrillic_enabled"
        private const val KEY_ARABIC_ENABLED = "arabic_enabled"
        private const val KEY_CHINESE_ENABLED = "chinese_enabled"
        private const val KEY_ENGLISH_ENABLED = "english_enabled"
        private const val KEY_RUSSIAN_ENABLED = "russian_enabled"

        @Volatile
        private var instance: KeyboardSettingsManager? = null

        fun getInstance(context: Context): KeyboardSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: KeyboardSettingsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 默认所有键盘都启用
    var isLatinEnabled: Boolean
        get() = prefs.getBoolean(KEY_LATIN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LATIN_ENABLED, value).apply()

    var isCyrillicEnabled: Boolean
        get() = prefs.getBoolean(KEY_CYRILLIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CYRILLIC_ENABLED, value).apply()

    var isArabicEnabled: Boolean
        get() = prefs.getBoolean(KEY_ARABIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ARABIC_ENABLED, value).apply()

    var isChineseEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHINESE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CHINESE_ENABLED, value).apply()

    var isEnglishEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENGLISH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENGLISH_ENABLED, value).apply()

    var isRussianEnabled: Boolean
        get() = prefs.getBoolean(KEY_RUSSIAN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RUSSIAN_ENABLED, value).apply()

    // 获取所有启用的键盘类型
    fun getEnabledKeyboardTypes(): List<KeyboardType> {
        val enabledTypes = mutableListOf<KeyboardType>()
        if (isLatinEnabled) enabledTypes.add(KeyboardType.LATIN)
        if (isCyrillicEnabled) enabledTypes.add(KeyboardType.CYRILLIC_KAZAKH)
        if (isArabicEnabled) enabledTypes.add(KeyboardType.ARABIC)
        if (isChineseEnabled) enabledTypes.add(KeyboardType.CHINESE)
        if (isEnglishEnabled) enabledTypes.add(KeyboardType.ENGLISH)
        if (isRussianEnabled) enabledTypes.add(KeyboardType.RUSSIAN)
        return enabledTypes
    }

    // 检查是否有键盘启用
    fun hasEnabledKeyboards(): Boolean {
        return isLatinEnabled || isCyrillicEnabled || isArabicEnabled ||
                isChineseEnabled || isEnglishEnabled || isRussianEnabled
    }

    // 获取下一个可用的键盘类型（用于循环切换）
    fun getNextKeyboardType(currentType: KeyboardType): KeyboardType {
        val enabledTypes = getEnabledKeyboardTypes()
        if (enabledTypes.isEmpty()) return KeyboardType.ENGLISH // 默认回退

        val currentIndex = enabledTypes.indexOf(currentType)
        return if (currentIndex == -1 || currentIndex == enabledTypes.size - 1) {
            enabledTypes.first()
        } else {
            enabledTypes[currentIndex + 1]
        }
    }

    // 重置所有设置为默认值
    fun resetToDefaults() {
        prefs.edit().apply {
            putBoolean(KEY_LATIN_ENABLED, true)
            putBoolean(KEY_CYRILLIC_ENABLED, true)
            putBoolean(KEY_ARABIC_ENABLED, true)
            putBoolean(KEY_CHINESE_ENABLED, true)
            putBoolean(KEY_ENGLISH_ENABLED, true)
            putBoolean(KEY_RUSSIAN_ENABLED, true)
        }.apply()
    }
}