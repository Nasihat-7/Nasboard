package com.example.nasboard.ime.emoji

enum class EmojiCategory(val id: String) {
    RECENT("recent"),           // 最近使用
    FAVORITE("favorite"),       // 收藏
    SMILEYS_EMOTION("smileys_emotion"),
    PEOPLE_BODY("people_body"),
    ANIMALS_NATURE("animals_nature"),
    FOOD_DRINK("food_drink"),
    TRAVEL_PLACES("travel_places"),
    ACTIVITIES("activities"),
    OBJECTS("objects"),
    SYMBOLS("symbols"),
    FLAGS("flags");

    companion object {
        fun fromId(id: String): EmojiCategory? {
            return values().find { it.id == id }
        }
    }
}