package com.example.nasboard

import android.graphics.*
import android.graphics.drawable.Drawable
import com.example.nasboard.ime.theme.KeyboardTheme

/**
 * 按键绘制器
 * 负责绘制按键的背景和文本，完全独立于业务逻辑
 */
class KeyDrawer(private val theme: KeyboardTheme) {

    // 绘制工具
    private val keyPath = Path()
    private val keyRectF = RectF()
    private val tempRectF = RectF()

    // 画笔缓存
    private val backgroundPaintCache = mutableMapOf<Int, Paint>()
    private val textPaintCache = mutableMapOf<Pair<Float, Int>, Paint>()

    /**
     * 绘制按键背景
     */
    fun drawKeyBackground(
        canvas: Canvas,
        bounds: Rect,
        isPressed: Boolean,
        isTypingKey: Boolean,
        keyCode: String = ""  // 添加 keyCode 参数
    ) {
        // 如果是空白键，完全不绘制背景
        if (keyCode == "BLANK") {
            return
        }

        // 创建带间距的矩形
        keyRectF.set(
            bounds.left.toFloat() + theme.keyGap,
            bounds.top.toFloat() + theme.keyGap,
            bounds.right.toFloat() - theme.keyGap,
            bounds.bottom.toFloat() - theme.keyGap
        )

        // 绘制圆角矩形
        keyPath.reset()
        keyPath.addRoundRect(keyRectF, theme.keyCornerRadius, theme.keyCornerRadius, Path.Direction.CW)

        // 选择颜色
        val color = when {
            isPressed -> theme.pressedKeyColor
            isTypingKey -> theme.typingKeyColor
            else -> theme.functionKeyColor
        }

        // 获取或创建画笔
        val paint = getBackgroundPaint(color)
        canvas.drawPath(keyPath, paint)
    }

    /**
     * 绘制按键文本
     */
    fun drawKeyText(
        canvas: Canvas,
        bounds: Rect,
        text: String,
        isSpecialKey: Boolean = false,
        isShiftKey: Boolean = false,
        keyCode: String = ""  // 添加 keyCode 参数
    ) {
        // 如果是空白键，不绘制文本
        if (keyCode == "BLANK") {
            return
        }

        val paint = getTextPaint(isSpecialKey, isShiftKey)

        // 计算文本垂直居中位置
        val textY = bounds.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, bounds.centerX().toFloat(), textY, paint)
    }

    /**
     * 获取背景画笔（带缓存）
     */
    private fun getBackgroundPaint(color: Int): Paint {
        return backgroundPaintCache.getOrPut(color) {
            Paint().apply {
                this.color = color
                style = Paint.Style.FILL
                isAntiAlias = true

                // 如果启用了阴影，设置阴影层
                if (theme.shadowEnabled) {
                    setShadowLayer(
                        theme.shadowBlur,
                        theme.shadowOffsetX,
                        theme.shadowOffsetY,
                        theme.shadowColor
                    )
                }
            }
        }
    }

    /**
     * 获取文本画笔（带缓存）
     */
    private fun getTextPaint(isSpecialKey: Boolean, isShiftKey: Boolean): Paint {
        val textSize = when {
            isShiftKey -> theme.shiftTextSize
            isSpecialKey -> theme.specialTextSize
            else -> theme.normalTextSize
        }

        val textColor = if (isSpecialKey) theme.specialKeyTextColor else theme.textColor

        val cacheKey = textSize to textColor
        return textPaintCache.getOrPut(cacheKey) {
            Paint().apply {
                this.color = textColor
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT
                isAntiAlias = true

                // 特殊键使用粗体
                if (isSpecialKey && !isShiftKey) {
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
        }
    }

    /**
     * 清理画笔缓存（在主题改变时调用）
     */
    fun clearCache() {
        backgroundPaintCache.clear()
        textPaintCache.clear()
    }
}