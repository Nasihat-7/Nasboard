package com.example.nasboard

import com.google.gson.annotations.SerializedName

sealed class KeyboardKey {
    abstract val type: String
    abstract val code: String
    abstract val label: String
    abstract val width: Float

    data class CharKey(
        @SerializedName("type") override val type: String = "char",
        @SerializedName("code") override val code: String,
        @SerializedName("label") override val label: String,
        @SerializedName("width") override val width: Float = 1.0f
    ) : KeyboardKey()

    data class FunctionKey(
        @SerializedName("type") override val type: String = "function",
        @SerializedName("code") override val code: String,
        @SerializedName("label") override val label: String,
        @SerializedName("width") override val width: Float = 1.0f,
        val repeatable: Boolean = false
    ) : KeyboardKey()
}

data class KeyboardLayoutConfig(
    val name: String,
    val displayName: String,
    val supportsShift: Boolean = true,
    val rtl: Boolean = false,
    val layouts: Map<String, List<List<KeyboardKey>>>
)