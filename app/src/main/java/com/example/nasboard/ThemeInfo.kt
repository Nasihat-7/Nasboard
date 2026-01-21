package com.example.nasboard

/**
 * 主题信息数据类
 */
data class ThemeInfo(
    val name: String,
    val displayName: String,
    val type: ThemeManager.ThemeType
)