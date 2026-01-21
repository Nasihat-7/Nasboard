package com.example.nasboard

import android.graphics.Color

/**
 * 键盘主题数据类
 * 包含所有可自定义的视觉属性
 */
data class KeyboardTheme(
    // 背景颜色
    val backgroundColor: Int = Color.parseColor("#d0d3da"),

    // 按键颜色
    val typingKeyColor: Int = Color.parseColor("#ffffff"),
    val functionKeyColor: Int = Color.parseColor("#a9b0ba"),
    val pressedKeyColor: Int = Color.parseColor("#e0e0e0"),

    // 文字颜色
    val textColor: Int = Color.parseColor("#000000"),
    val specialKeyTextColor: Int = Color.parseColor("#000000"),

    // 按键样式
    val keyCornerRadius: Float = 8f,
    val keyGap: Float = 4f,

    // 文本大小
    val normalTextSize: Float = 42f,
    val specialTextSize: Float = 38f,
    val shiftTextSize: Float = 34f,

    // 阴影效果（未来扩展）
    val shadowEnabled: Boolean = false,
    val shadowColor: Int = Color.parseColor("#40000000"),
    val shadowOffsetX: Float = 0f,
    val shadowOffsetY: Float = 4f,
    val shadowBlur: Float = 8f
) {
    companion object {
        // 预定义主题
        val DEFAULT = KeyboardTheme()

        val DARK = KeyboardTheme(
            backgroundColor = Color.parseColor("#2d3748"),
            typingKeyColor = Color.parseColor("#4a5568"),
            functionKeyColor = Color.parseColor("#718096"),
            textColor = Color.parseColor("#ffffff"),
            specialKeyTextColor = Color.parseColor("#ffffff"),
            pressedKeyColor = Color.parseColor("#2d3748")
        )

        val MATERIAL = KeyboardTheme(
            backgroundColor = Color.parseColor("#f5f5f5"),
            typingKeyColor = Color.WHITE,
            functionKeyColor = Color.parseColor("#e0e0e0"),
            textColor = Color.BLACK,
            specialKeyTextColor = Color.BLACK,
            keyCornerRadius = 12f,
            keyGap = 6f
        )
    }
}