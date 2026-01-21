package com.example.nasboard.ime.conversion

import android.util.Log
import com.example.nasboard.KeyboardType

class ConversionManager {

    // 转换状态
    data class ConversionState(
        val isConversionMode: Boolean = false,
        val targetLanguage: KeyboardType? = null
    )

    // 为每个键盘类型保存独立的转换状态
    private val conversionStates = mutableMapOf<KeyboardType, ConversionState>().apply {
        // 初始化所有键盘类型的转换状态
        KeyboardType.values().forEach { type ->
            put(type, ConversionState())
        }
    }

    private var currentKeyboardType = KeyboardType.LATIN

    // 拉丁文到西里尔文映射（用于转换）
    private val latinToCyrillicMap = mapOf(
        "a" to "а", "ä" to "ә", "b" to "б", "v" to "в", "g" to "г", "ğ" to "ғ", "d" to "д", "e" to "е",
        "io" to "ё", "j" to "ж", "z" to "з", "i" to "и", "y" to "й", "k" to "к", "q" to "қ", "l" to "л",
        "m" to "м", "n" to "н", "ñ" to "ң", "o" to "о", "ö" to "ө", "p" to "п", "r" to "р", "s" to "с",
        "t" to "т", "u" to "у", "ū" to "ұ", "ü" to "ү", "f" to "ф", "x" to "х", "h" to "һ", "c" to "ц",
        "ch" to "ч", "ş" to "ш", "şç" to "щ", "ı" to "ы", "i" to "і", "e" to "э", "iu" to "ю", "ia" to "я",
        "A" to "А", "Ä" to "Ә", "B" to "Б", "V" to "В", "G" to "Г", "Ğ" to "Ғ", "D" to "Д", "E" to "Е",
        "Io" to "Ё", "J" to "Ж", "Z" to "З", "I" to "И", "Y" to "Й", "K" to "К", "Q" to "Қ", "L" to "Л",
        "M" to "М", "N" to "Н", "Ñ" to "Ң", "O" to "О", "Ö" to "Ө", "P" to "П", "R" to "Р", "S" to "С",
        "T" to "Т", "U" to "У", "Ū" to "Ұ", "Ü" to "Ү", "F" to "Ф", "X" to "Х", "H" to "Һ", "C" to "Ц",
        "Ch" to "Ч", "Ş" to "Ш", "Şç" to "Щ", "I" to "Ы", "İ" to "І", "E" to "Э", "Iu" to "Ю", "Ia" to "Я"
    )

    // 拉丁文到阿拉伯文映射
    private val latinToArabicMap = mapOf(
        // 基本字母映射
        "a" to "ا", "ä" to "ا", "b" to "ب", "d" to "د", "e" to "ە",
        "f" to "ف", "g" to "گ", "h" to "ھ", "i" to "ى", "j" to "ج",
        "k" to "ك", "l" to "ل", "m" to "م", "n" to "ن", "o" to "و",
        "p" to "پ", "q" to "ق", "r" to "ر", "s" to "س", "t" to "ت",
        "u" to "ۇ", "v" to "ۆ", "w" to "ۋ", "x" to "ح", "y" to "ي",
        "z" to "ز", "ğ" to "ع", "ñ" to "ڭ", "ö" to "ۆ", "ü" to "ۇ",
        "ş" to "ش", "ı" to "ى", "ç" to "چ", "ū" to "ۇ", "c" to "تس",
        "A" to "ا", "Ä" to "ا", "B" to "ب", "D" to "د", "E" to "ە",
        "F" to "ف", "G" to "گ", "H" to "ھ", "I" to "ى", "J" to "ج",
        "K" to "ك", "L" to "ل", "M" to "م", "N" to "ن", "O" to "و",
        "P" to "پ", "Q" to "ق", "R" to "ر", "S" to "س", "T" to "ت",
        "U" to "ۇ", "V" to "ۆ", "W" to "ۋ", "X" to "ح", "Y" to "ي",
        "Z" to "ز", "Ğ" to "ع", "Ñ" to "ڭ", "Ö" to "ۆ", "Ü" to "ۇ",
        "Ş" to "ش", "İ" to "ى", "Ç" to "چ", "Ū" to "ۇ", "C" to "تس",

        // 复合字母
        "io" to "يو", "ch" to "چ", "iu" to "يۋ", "ia" to "يا",
        "Io" to "يو", "Ch" to "چ", "Iu" to "يۋ", "Ia" to "يا"
    )

    // 西里尔文到拉丁文映射
    private val cyrillicToLatinMap = mapOf(
        "а" to "a", "ә" to "ä", "б" to "b", "в" to "v", "г" to "g", "ғ" to "ğ", "д" to "d", "е" to "e",
        "ё" to "io", "ж" to "j", "з" to "z", "и" to "i", "й" to "y", "к" to "k", "қ" to "q", "л" to "l",
        "м" to "m", "н" to "n", "ң" to "ñ", "о" to "o", "ө" to "ö", "п" to "p", "р" to "r", "с" to "s",
        "т" to "t", "у" to "u", "ұ" to "ū", "ү" to "ü", "ф" to "f", "х" to "x", "һ" to "h", "ц" to "c",
        "ч" to "ch", "ш" to "ş", "щ" to "şç", "ы" to "ı", "і" to "i", "э" to "e", "ю" to "iu", "я" to "ia",
        "А" to "A", "Ә" to "Ä", "Б" to "B", "В" to "V", "Г" to "G", "Ғ" to "Ğ", "Д" to "D", "Е" to "Е",
        "Ё" to "Io", "Ж" to "J", "З" to "Z", "И" to "I", "Й" to "Y", "К" to "K", "Қ" to "Q", "Л" to "L",
        "М" to "M", "Н" to "N", "Ң" to "Ñ", "О" to "O", "Ө" to "Ö", "П" to "P", "Р" to "R", "С" to "S",
        "Т" to "T", "У" to "U", "Ұ" to "Ū", "Ү" to "Ü", "Ф" to "F", "Х" to "X", "Һ" to "H", "Ц" to "C",
        "Ч" to "Ch", "Ш" to "Ş", "Щ" to "Şç", "Ы" to "I", "І" to "İ", "Э" to "E", "Ю" to "Iu", "Я" to "Ia"
    )

    // 西里尔文到阿拉伯文映射
    private val cyrillicToArabicMap = mapOf(
        // 第一行特殊字符
        "ә" to "ا", "і" to "ى", "ң" to "ڭ", "ғ" to "ع", "ү" to "ۇ",
        "ұ" to "ۇ", "қ" to "ق", "ө" to "و", "һ" to "ھ", "ё" to "يو",

        // 第二行
        "й" to "ي", "ц" to "تس", "у" to "ۋ", "к" to "ك", "е" to "ە",
        "н" to "ن", "г" to "گ", "ш" to "ش", "щ" to "شش", "з" to "ز", "х" to "ح",

        // 第三行
        "ф" to "ف", "ы" to "ى", "в" to "ۆ", "а" to "ا", "п" to "پ",
        "р" to "ر", "о" to "و", "л" to "ل", "д" to "د", "ж" to "ج", "э" to "ە",

        // 第四行
        "я" to "يا", "ч" to "چ", "с" to "س", "م" to "م", "и" to "ي",
        "т" to "ت", "б" to "ب", "ю" to "يۋ",

        // 大写字母对应
        "А" to "ا", "Б" to "ب", "В" to "ۆ", "Г" to "گ", "Д" to "د",
        "Е" to "ە", "Ё" to "يو", "Ж" to "ج", "З" to "ز", "И" to "ي",
        "Й" to "ي", "К" to "ك", "Л" to "ل", "М" to "م", "Н" to "ن",
        "О" to "و", "П" to "پ", "Р" to "ر", "С" to "س", "Т" to "ت",
        "У" to "ۋ", "Ф" to "ف", "Х" to "ح", "Ц" to "تس", "Ч" to "چ",
        "Ш" to "ش", "Щ" to "شش", "Ъ" to "", "Ы" to "ى", "Ь" to "",
        "Э" to "ە", "Ю" to "يۋ", "Я" to "يا",
        "Ә" to "ە", "І" to "ى", "Ң" to "ڭ", "Ғ" to "ع", "Ү" to "ۇ",
        "Ұ" to "ۇ", "Қ" to "ق", "Ө" to "و", "Һ" to "ھ"
    )

    // 阿拉伯文到拉丁文映射
    private val arabicToLatinMap = mapOf(
        // 单个字符映射
        "ا" to "a", "ە" to "e", "ب" to "b", "د" to "d", "ف" to "f",
        "گ" to "g", "ھ" to "h", "ى" to "i", "ج" to "j", "ك" to "k",
        "ل" to "l", "م" to "m", "ن" to "n", "و" to "o", "پ" to "p",
        "ق" to "q", "ر" to "r", "س" to "s", "ت" to "t", "ۇ" to "u",
        "ۆ" to "ö", "ۋ" to "w", "ح" to "x", "ي" to "y", "ز" to "z",
        "ع" to "ğ", "ڭ" to "ñ", "ش" to "ş", "چ" to "ç", "تس" to "c",

        // 复合字母映射
        "يو" to "io", "چ" to "ch", "يۋ" to "iu", "يا" to "ia"
    )

    // 阿拉伯文到西里尔文映射
    private val arabicToCyrillicMap = mapOf(
        // 单个字符映射
        "ا" to "а", "ە" to "е", "ب" to "б", "د" to "д", "ف" to "ф",
        "گ" to "г", "ھ" to "һ", "ى" to "и", "ج" to "ж", "ك" to "к",
        "ل" to "л", "م" to "м", "ن" to "н", "و" to "о", "پ" to "п",
        "ق" to "қ", "ر" to "р", "س" to "с", "ت" to "т", "ۇ" to "у",
        "ۆ" to "в", "ۋ" to "у", "ح" to "х", "ي" to "й", "ز" to "з",
        "ع" to "ғ", "ڭ" to "ң", "ش" to "ш", "چ" to "ч", "تس" to "ц",

        // 复合字母映射
        "يو" to "ё", "يۋ" to "ю", "يا" to "я", "شش" to "щ"
    )

    fun setCurrentKeyboardType(keyboardType: KeyboardType) {
        Log.d("ConversionManager", "Setting current keyboard type: $keyboardType")
        currentKeyboardType = keyboardType
    }

    fun getCurrentConversionState(): ConversionState {
        return conversionStates[currentKeyboardType] ?: ConversionState()
    }

    fun getAvailableTargetLanguages(): List<KeyboardType> {
        // 中文键盘不支持转换，返回空列表
        if (currentKeyboardType == KeyboardType.CHINESE) {
            return emptyList()
        }

        return when (currentKeyboardType) {
            KeyboardType.LATIN -> listOf(KeyboardType.CYRILLIC_KAZAKH, KeyboardType.ARABIC)
            KeyboardType.CYRILLIC_KAZAKH -> listOf(KeyboardType.LATIN, KeyboardType.ARABIC)
            KeyboardType.ARABIC -> listOf(KeyboardType.LATIN, KeyboardType.CYRILLIC_KAZAKH)
            else -> emptyList()
        }
    }

    fun enableConversionMode(targetLanguage: KeyboardType) {
        Log.d("ConversionManager", "Enabling conversion mode for $currentKeyboardType -> $targetLanguage")

        // 中文键盘不支持转换模式
        if (currentKeyboardType == KeyboardType.CHINESE) {
            return
        }

        conversionStates[currentKeyboardType] = ConversionState(
            isConversionMode = true,
            targetLanguage = targetLanguage
        )
    }

    fun disableConversionMode() {
        Log.d("ConversionManager", "Disabling conversion mode for $currentKeyboardType")
        conversionStates[currentKeyboardType] = ConversionState(
            isConversionMode = false,
            targetLanguage = null
        )
    }

    fun convertText(text: String): String {
        Log.d("ConversionManager", "Converting text '$text' from $currentKeyboardType")

        // 中文键盘不进行任何转换
        if (currentKeyboardType == KeyboardType.CHINESE) {
            return text
        }

        val currentState = getCurrentConversionState()
        if (!currentState.isConversionMode || currentState.targetLanguage == null) {
            return text
        }

        val targetLanguage = currentState.targetLanguage!!

        // 如果源语言和目标语言相同，不需要转换
        if (currentKeyboardType == targetLanguage) {
            return text
        }

        var convertedText = text

        // 根据源语言和目标语言选择正确的映射表
        Log.d("ConversionManager", "Converting from $currentKeyboardType to $targetLanguage")

        when (currentKeyboardType) {
            KeyboardType.LATIN -> {
                when (targetLanguage) {
                    KeyboardType.CYRILLIC_KAZAKH -> {
                        convertedText = convertLatinToCyrillic(text)
                    }
                    KeyboardType.ARABIC -> {
                        convertedText = convertLatinToArabic(text)
                    }
                    else -> {
                        // 不需要转换
                    }
                }
            }
            KeyboardType.CYRILLIC_KAZAKH -> {
                when (targetLanguage) {
                    KeyboardType.LATIN -> {
                        convertedText = convertCyrillicToLatin(text)
                    }
                    KeyboardType.ARABIC -> {
                        convertedText = convertCyrillicToArabic(text)
                    }
                    else -> {
                        // 不需要转换
                    }
                }
            }
            KeyboardType.ARABIC -> {
                when (targetLanguage) {
                    KeyboardType.LATIN -> {
                        convertedText = convertArabicToLatin(text)
                    }
                    KeyboardType.CYRILLIC_KAZAKH -> {
                        convertedText = convertArabicToCyrillic(text)
                    }
                    else -> {
                        // 不需要转换
                    }
                }
            }
            else -> {
                // 不需要转换
            }
        }

        Log.d("ConversionManager", "Converted text: '$convertedText'")
        return convertedText
    }

    // 新增：专门处理拉丁文到西里尔文的转换
    private fun convertLatinToCyrillic(text: String): String {
        var result = text
        // 先处理复合字符
        val compoundMap = latinToCyrillicMap.filter { it.key.length > 1 }
        compoundMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        // 再处理单个字符
        val singleMap = latinToCyrillicMap.filter { it.key.length == 1 }
        singleMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    // 新增：专门处理拉丁文到阿拉伯文的转换
    private fun convertLatinToArabic(text: String): String {
        var result = text
        // 先处理复合字符
        val compoundMap = latinToArabicMap.filter { it.key.length > 1 }
        compoundMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        // 再处理单个字符
        val singleMap = latinToArabicMap.filter { it.key.length == 1 }
        singleMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    // 新增：专门处理西里尔文到拉丁文的转换
    private fun convertCyrillicToLatin(text: String): String {
        var result = text
        // 先处理复合字符
        val compoundMap = cyrillicToLatinMap.filter { it.key.length > 1 }
        compoundMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        // 再处理单个字符
        val singleMap = cyrillicToLatinMap.filter { it.key.length == 1 }
        singleMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    // 新增：专门处理西里尔文到阿拉伯文的转换
    private fun convertCyrillicToArabic(text: String): String {
        var result = text
        // 先处理复合字符
        val compoundMap = cyrillicToArabicMap.filter { it.key.length > 1 }
        compoundMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        // 再处理单个字符
        val singleMap = cyrillicToArabicMap.filter { it.key.length == 1 }
        singleMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    // 新增：专门处理阿拉伯文到拉丁文的转换
    private fun convertArabicToLatin(text: String): String {
        var result = text
        // 先处理复合字符
        val compoundMap = arabicToLatinMap.filter { it.key.length > 1 }
        compoundMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        // 再处理单个字符
        val singleMap = arabicToLatinMap.filter { it.key.length == 1 }
        singleMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    // 新增：专门处理阿拉伯文到西里尔文的转换
    private fun convertArabicToCyrillic(text: String): String {
        var result = text
        // 先处理复合字符
        val compoundMap = arabicToCyrillicMap.filter { it.key.length > 1 }
        compoundMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        // 再处理单个字符
        val singleMap = arabicToCyrillicMap.filter { it.key.length == 1 }
        singleMap.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    fun convertPunctuation(punctuation: String): String {
        // 中文键盘不进行标点符号转换
        if (currentKeyboardType == KeyboardType.CHINESE) {
            return punctuation
        }

        val currentState = getCurrentConversionState()
        if (!currentState.isConversionMode || currentState.targetLanguage == null) {
            return punctuation
        }

        val targetLanguage = currentState.targetLanguage!!

        // 只在转换到阿拉伯文时转换标点符号
        if (targetLanguage == KeyboardType.ARABIC) {
            return when (punctuation) {
                "?" -> "؟"
                "," -> "،"
                ";" -> "؛"
                else -> punctuation
            }
        }

        return punctuation
    }

    fun getTargetLanguageDisplayName(): String {
        val targetLanguage = getCurrentConversionState().targetLanguage
        return when (targetLanguage) {
            KeyboardType.LATIN -> "拉丁文"
            KeyboardType.CYRILLIC_KAZAKH -> "西里尔文"
            KeyboardType.ARABIC -> "阿拉伯文"
            else -> "无"
        }
    }

    fun getTargetLanguageSymbol(): String {
        val targetLanguage = getCurrentConversionState().targetLanguage
        return when (targetLanguage) {
            KeyboardType.LATIN -> "ABC"
            KeyboardType.CYRILLIC_KAZAKH -> "КИР"
            KeyboardType.ARABIC -> "عرب"
            else -> "转换"
        }
    }

    // 检查当前键盘是否支持转换
    fun isConversionSupported(): Boolean {
        return currentKeyboardType != KeyboardType.CHINESE
    }
}