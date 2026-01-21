package com.example.nasboard

/**
 * 按键类型分类器
 * 负责判断按键的类型（打字键、功能键、特殊键等）
 */
object KeyTypeClassifier {

    // 功能键列表 - 添加 BLANK
    private val functionKeys = setOf(
        "SHIFT", "DEL", "123", "ABC", "SPACE", "EMOJI", "SWITCH_LANG", "BLANK"
    )

    // 特殊键列表（需要特殊文本样式的键）- 添加 BLANK
    private val specialKeys = setOf(
        "SHIFT", "SPACE", "DEL", "123", "ABC", "EMOJI", "SWITCH_LANG", "BLANK"
    )

    /**
     * 判断是否为打字键（字母、数字、标点符号）
     */
    fun isTypingKey(keyCode: String): Boolean {
        return keyCode !in functionKeys
    }

    /**
     * 判断是否为功能键
     */
    fun isFunctionKey(keyCode: String): Boolean {
        return keyCode in functionKeys
    }

    /**
     * 判断是否为特殊键（需要特殊文本样式）
     */
    fun isSpecialKey(keyCode: String): Boolean {
        return keyCode in specialKeys
    }

    /**
     * 判断是否为 Shift 键
     */
    fun isShiftKey(keyCode: String): Boolean {
        return keyCode == "SHIFT"
    }
}