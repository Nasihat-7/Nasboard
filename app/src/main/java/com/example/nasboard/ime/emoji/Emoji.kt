package com.example.nasboard.ime.emoji

data class Emoji(
    val value: String,
    val name: String,
    val keywords: List<String> = emptyList(),
    val hasVariants: Boolean = false,
    val variants: List<Emoji> = emptyList()
) {
    override fun toString(): String {
        return "Emoji { value=$value, name=$name, keywords=$keywords, hasVariants=$hasVariants }"
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is Emoji && value == other.value
    }
}