package com.example.nasboard.ime.emoji

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class EmojiManager(private val context: Context) {

    private var emojis: Map<EmojiCategory, List<Emoji>> = emptyMap()
    private var allEmojis: List<Emoji> = emptyList()
    private var isLoaded = false

    fun loadEmojis(): Boolean {
        if (!isLoaded) {
            emojis = loadEmojisFromAssets()
            allEmojis = emojis.values.flatten()
            isLoaded = true

            // 打印加载的统计信息
            val totalEmojis = emojis.values.sumOf { it.size }
            Log.d("EmojiManager", "Total categories loaded: ${emojis.size}")
            Log.d("EmojiManager", "Total emojis loaded: $totalEmojis")

            return totalEmojis > 0
        }
        return emojis.values.sumOf { it.size } > 0
    }

    private fun loadEmojisFromAssets(): Map<EmojiCategory, MutableList<Emoji>> {
        val emojiMap = mutableMapOf<EmojiCategory, MutableList<Emoji>>()

        try {
            // 先尝试加载英文版本（包含名称和关键词）
            val inputStream = context.assets.open("emoji_en.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))

            var currentCategory: EmojiCategory? = null
            var line: String?
            var currentBaseEmoji: Emoji? = null

            while (reader.readLine().also { line = it } != null) {
                line = line?.trim()
                if (line.isNullOrEmpty()) continue

                // 检查是否是类别行
                if (line!!.startsWith("[") && line!!.endsWith("]")) {
                    val categoryId = line!!.substring(1, line!!.length - 1)
                    currentCategory = EmojiCategory.fromId(categoryId)
                    if (currentCategory != null) {
                        emojiMap[currentCategory] = mutableListOf()
                    }
                    currentBaseEmoji = null
                    continue
                }

                // 解析表情符号行
                if (currentCategory != null && line!!.contains(";")) {
                    val parts = line!!.split(";")
                    val emojiValue = parts[0].trim()

                    if (emojiValue.isNotEmpty()) {
                        // 检查是否是变体（有缩进）
                        val isVariant = line!!.startsWith("    ") || line!!.startsWith("\t")

                        if (isVariant) {
                            // 这是变体表情，添加到当前基础表情的变体列表
                            val name = if (parts.size > 1) parts[1].trim() else "Variant"
                            val keywords = if (parts.size > 2) parts[2].split("|").map { it.trim() } else emptyList()
                            val variantEmoji = Emoji(emojiValue, name, keywords)

                            if (currentBaseEmoji != null) {
                                val updatedVariants = currentBaseEmoji.variants.toMutableList()
                                updatedVariants.add(variantEmoji)
                                val updatedEmoji = currentBaseEmoji.copy(
                                    hasVariants = true,
                                    variants = updatedVariants
                                )
                                // 更新列表中的基础表情
                                val index = emojiMap[currentCategory]!!.indexOfFirst { it.value == currentBaseEmoji.value }
                                if (index >= 0) {
                                    emojiMap[currentCategory]!![index] = updatedEmoji
                                    currentBaseEmoji = updatedEmoji
                                }
                            }
                        } else {
                            // 这是基础表情
                            val name = if (parts.size > 1) parts[1].trim() else ""
                            val keywords = if (parts.size > 2) parts[2].split("|").map { it.trim() } else emptyList()
                            val emoji = Emoji(emojiValue, name, keywords, false, emptyList())
                            emojiMap[currentCategory]?.add(emoji)
                            currentBaseEmoji = emoji
                        }
                    }
                }
            }

            reader.close()
            inputStream.close()

            Log.d("EmojiManager", "Successfully loaded emojis from assets")
        } catch (e: Exception) {
            Log.e("EmojiManager", "Error loading emojis from assets: ${e.message}", e)
        }

        return emojiMap
    }

    fun getEmojisByCategory(category: EmojiCategory): List<Emoji> {
        return emojis[category] ?: emptyList()
    }

    fun getAllCategories(): List<EmojiCategory> {
        return EmojiCategory.values().filter { it != EmojiCategory.RECENT && it != EmojiCategory.FAVORITE }
    }

    fun getFirstFewEmojis(limit: Int = 20): List<Emoji> {
        return allEmojis.take(limit)
    }

    fun searchEmojis(query: String): List<Emoji> {
        if (query.isEmpty()) return emptyList()

        val searchTerm = query.lowercase()
        return allEmojis.filter { emoji ->
            emoji.name.lowercase().contains(searchTerm) ||
                    emoji.keywords.any { it.lowercase().contains(searchTerm) } ||
                    emoji.value.contains(searchTerm)
        }
    }
}