package com.example.nasboard

import com.google.gson.annotations.SerializedName

/**
 * JSON主题数据模型
 */
data class JsonTheme(
    @SerializedName("name") val name: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("backgroundColor") val backgroundColor: String,
    @SerializedName("typingKeyColor") val typingKeyColor: String,
    @SerializedName("functionKeyColor") val functionKeyColor: String,
    @SerializedName("pressedKeyColor") val pressedKeyColor: String,
    @SerializedName("textColor") val textColor: String,
    @SerializedName("specialKeyTextColor") val specialKeyTextColor: String,
    @SerializedName("keyCornerRadius") val keyCornerRadius: Float = 8f,
    @SerializedName("keyGap") val keyGap: Float = 4f,
    @SerializedName("normalTextSize") val normalTextSize: Float = 32f,
    @SerializedName("specialTextSize") val specialTextSize: Float = 28f,
    @SerializedName("shiftTextSize") val shiftTextSize: Float = 24f,
    @SerializedName("shadowEnabled") val shadowEnabled: Boolean = false,
    @SerializedName("shadowColor") val shadowColor: String = "#40000000",
    @SerializedName("shadowOffsetX") val shadowOffsetX: Float = 0f,
    @SerializedName("shadowOffsetY") val shadowOffsetY: Float = 4f,
    @SerializedName("shadowBlur") val shadowBlur: Float = 8f
) {
    /**
     * 转换为KeyboardTheme对象
     */
    fun toKeyboardTheme(): KeyboardTheme {
        return KeyboardTheme(
            backgroundColor = parseColor(backgroundColor),
            typingKeyColor = parseColor(typingKeyColor),
            functionKeyColor = parseColor(functionKeyColor),
            pressedKeyColor = parseColor(pressedKeyColor),
            textColor = parseColor(textColor),
            specialKeyTextColor = parseColor(specialKeyTextColor),
            keyCornerRadius = keyCornerRadius,
            keyGap = keyGap,
            normalTextSize = normalTextSize,
            specialTextSize = specialTextSize,
            shiftTextSize = shiftTextSize,
            shadowEnabled = shadowEnabled,
            shadowColor = parseColor(shadowColor),
            shadowOffsetX = shadowOffsetX,
            shadowOffsetY = shadowOffsetY,
            shadowBlur = shadowBlur
        )
    }

    private fun parseColor(colorString: String): Int {
        return try {
            android.graphics.Color.parseColor(colorString)
        } catch (e: Exception) {
            android.graphics.Color.BLACK
        }
    }
}