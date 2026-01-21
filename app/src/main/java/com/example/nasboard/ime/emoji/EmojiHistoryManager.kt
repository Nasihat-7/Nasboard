package com.example.nasboard.ime.emoji

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class EmojiHistoryManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: EmojiHistoryManager? = null

        fun getInstance(context: Context): EmojiHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: EmojiHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("emoji_history", Context.MODE_PRIVATE)
    private val maxRecentEmojis = 50
    private val maxFavoriteEmojis = 100
    private lateinit var emojiManager: EmojiManager

    fun setEmojiManager(emojiManager: EmojiManager) {
        this.emojiManager = emojiManager
    }

    // 添加表情到最近使用
    fun addToHistory(emoji: Emoji) {
        val recentValues = getRecentEmojiValues().toMutableList()

        // 移除已存在的相同表情
        recentValues.remove(emoji.value)

        // 添加到开头
        recentValues.add(0, emoji.value)

        // 限制数量
        if (recentValues.size > maxRecentEmojis) {
            recentValues.removeAt(recentValues.size - 1)
        }

        saveRecentEmojiValues(recentValues)
    }

    // 获取最近使用的表情
    fun getRecentEmojis(): List<Emoji> {
        val recentValues = getRecentEmojiValues()
        return recentValues.mapNotNull { value ->
            // 从所有表情中查找对应的 Emoji 对象
            findAllEmojis().find { it.value == value }
        }
    }

    // 添加到收藏
    fun addToFavorites(emoji: Emoji) {
        val favoriteValues = getFavoriteEmojiValues().toMutableSet()
        favoriteValues.add(emoji.value)
        saveFavoriteEmojiValues(favoriteValues)
    }

    // 从收藏中移除
    fun removeFromFavorites(emoji: Emoji) {
        val favoriteValues = getFavoriteEmojiValues().toMutableSet()
        favoriteValues.remove(emoji.value)
        saveFavoriteEmojiValues(favoriteValues)
    }

    // 获取收藏的表情
    fun getFavoriteEmojis(): List<Emoji> {
        val favoriteValues = getFavoriteEmojiValues()
        return favoriteValues.mapNotNull { value ->
            findAllEmojis().find { it.value == value }
        }
    }

    // 检查是否已收藏
    fun isFavorite(emoji: Emoji): Boolean {
        return getFavoriteEmojiValues().contains(emoji.value)
    }

    // 清空最近使用
    fun clearRecentEmojis() {
        sharedPreferences.edit().remove("recent_emoji_values").apply()
    }

    // 清空收藏
    fun clearFavorites() {
        sharedPreferences.edit().remove("favorite_emoji_values").apply()
    }

    private fun getRecentEmojiValues(): List<String> {
        val recentString = sharedPreferences.getString("recent_emoji_values", "") ?: ""
        return if (recentString.isEmpty()) {
            emptyList()
        } else {
            recentString.split(",")
        }
    }

    private fun getFavoriteEmojiValues(): Set<String> {
        val favoriteString = sharedPreferences.getString("favorite_emoji_values", "") ?: ""
        return if (favoriteString.isEmpty()) {
            emptySet()
        } else {
            favoriteString.split(",").toSet()
        }
    }

    private fun saveRecentEmojiValues(values: List<String>) {
        val valuesString = values.joinToString(",")
        sharedPreferences.edit().putString("recent_emoji_values", valuesString).apply()
    }

    private fun saveFavoriteEmojiValues(values: Set<String>) {
        val valuesString = values.joinToString(",")
        sharedPreferences.edit().putString("favorite_emoji_values", valuesString).apply()
    }

    // 从所有类别中获取所有表情
    private fun findAllEmojis(): List<Emoji> {
        return try {
            if (!::emojiManager.isInitialized) {
                emojiManager = EmojiManager(context)
                emojiManager.loadEmojis()
            }

            val allEmojis = mutableListOf<Emoji>()
            EmojiCategory.values().forEach { category ->
                if (category != EmojiCategory.RECENT && category != EmojiCategory.FAVORITE) {
                    allEmojis.addAll(emojiManager.getEmojisByCategory(category))
                }
            }
            allEmojis
        } catch (e: Exception) {
            Log.e("EmojiHistoryManager", "Error finding all emojis: ${e.message}")
            emptyList()
        }
    }
}