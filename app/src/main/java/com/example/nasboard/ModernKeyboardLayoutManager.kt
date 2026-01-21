package com.example.nasboard

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.lang.reflect.Type

class ModernKeyboardLayoutManager(private val context: Context) {

    private val gson = Gson().newBuilder()
        .registerTypeAdapter(KeyboardKey::class.java, KeyboardKeyDeserializer())
        .create()

    private val layoutCache = mutableMapOf<String, KeyboardLayoutConfig>()

    // 键盘类型到配置文件的映射
    private val keyboardConfigs = mapOf(
        KeyboardType.LATIN to KeyboardConfig(
            type = KeyboardType.LATIN,
            layoutFile = "keyboard_layouts/latin.json",
            supportsShift = true,
            rtl = false
        ),
        KeyboardType.CYRILLIC_KAZAKH to KeyboardConfig(
            type = KeyboardType.CYRILLIC_KAZAKH,
            layoutFile = "keyboard_layouts/cyrillic_kazakh.json",
            supportsShift = true,
            rtl = false
        ),
        KeyboardType.ARABIC to KeyboardConfig(
            type = KeyboardType.ARABIC,
            layoutFile = "keyboard_layouts/arabic.json",
            supportsShift = false,
            rtl = true
        ),
        KeyboardType.CHINESE to KeyboardConfig(
            type = KeyboardType.CHINESE,
            layoutFile = "keyboard_layouts/chinese.json",
            supportsShift = true,
            rtl = false
        ),
        KeyboardType.ENGLISH to KeyboardConfig(
            type = KeyboardType.ENGLISH,
            layoutFile = "keyboard_layouts/english.json",
            supportsShift = true,
            rtl = false
        ),
        KeyboardType.RUSSIAN to KeyboardConfig(
            type = KeyboardType.RUSSIAN,
            layoutFile = "keyboard_layouts/russian.json",
            supportsShift = true,
            rtl = false
        )
    )

    suspend fun getLayoutConfig(type: KeyboardType): KeyboardLayoutConfig? = withContext(Dispatchers.IO) {
        val config = keyboardConfigs[type] ?: return@withContext null

        Log.d("KeyboardLayout", "Loading layout for: $type from ${config.layoutFile}")

        val cachedLayout = layoutCache[config.layoutFile]
        if (cachedLayout != null) {
            Log.d("KeyboardLayout", "Using cached layout")
            return@withContext cachedLayout
        }

        try {
            // 先检查assets目录中有哪些文件
            val assetFiles = context.assets.list("keyboard_layouts")
            Log.d("KeyboardLayout", "Available layout files: ${assetFiles?.joinToString()}")

            context.assets.open(config.layoutFile).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val typeToken = object : TypeToken<KeyboardLayoutConfig>() {}.type
                    val layout = gson.fromJson<KeyboardLayoutConfig>(reader, typeToken)
                    layoutCache[config.layoutFile] = layout

                    Log.d("KeyboardLayout", "Successfully loaded: ${layout.name}")
                    Log.d("KeyboardLayout", "Layout variants: ${layout.layouts.keys}")

                    return@withContext layout
                }
            }
        } catch (e: Exception) {
            Log.e("KeyboardLayout", "Error loading ${config.layoutFile}: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }

    fun getKeyboardConfig(type: KeyboardType): KeyboardConfig? {
        return keyboardConfigs[type]
    }

    fun getLayoutVariant(
        config: KeyboardLayoutConfig,
        shiftState: Int,
        isNumeric: Boolean
    ): List<List<KeyboardKey>> {
        Log.d("KeyboardLayout", "Getting variant - shift: $shiftState, numeric: $isNumeric")

        return when {
            isNumeric -> {
                Log.d("KeyboardLayout", "Loading numeric layout")
                val numericConfig = loadNumericLayout()
                numericConfig?.layouts?.get("default") ?: createFallbackNumericLayout()
            }
            shiftState > 0 && config.supportsShift -> {
                Log.d("KeyboardLayout", "Loading shift layout")
                config.layouts["shift"] ?: config.layouts["default"] ?: createFallbackLayout()
            }
            else -> {
                Log.d("KeyboardLayout", "Loading default layout")
                config.layouts["default"] ?: createFallbackLayout()
            }
        }
    }

    private fun loadNumericLayout(): KeyboardLayoutConfig? {
        return try {
            context.assets.open("keyboard_layouts/numeric.json").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val typeToken = object : TypeToken<KeyboardLayoutConfig>() {}.type
                    gson.fromJson<KeyboardLayoutConfig>(reader, typeToken)
                }
            }
        } catch (e: Exception) {
            Log.e("KeyboardLayout", "Error loading numeric layout: ${e.message}")
            null
        }
    }

    fun calculateKeyBounds(
        rows: List<List<KeyboardKey>>,
        width: Int,
        height: Int
    ): List<KeyBound> {
        Log.d("KeyboardLayout", "Calculating bounds for ${rows.size} rows, screen: ${width}x${height}")

        if (rows.isEmpty()) {
            Log.e("KeyboardLayout", "No rows to calculate!")
            return emptyList()
        }

        val keyBounds = mutableListOf<KeyBound>()
        val usableHeight = (height * 0.92f).toInt()
        val keyHeight = usableHeight / rows.size
        var yPos = (height * 0.01f).toInt()

        rows.forEachIndexed { rowIndex, row ->
            Log.d("KeyboardLayout", "Row $rowIndex: ${row.size} keys")

            // 计算总宽度单位
            val totalWidthUnits = row.sumOf { it.width.toDouble() }.toFloat()
            val unitWidth = width / totalWidthUnits
            var xPos = 0

            row.forEach { key ->
                val keyWidth = (key.width * unitWidth).toInt()
                val bounds = Rect(xPos, yPos, xPos + keyWidth, yPos + keyHeight)
                keyBounds.add(KeyBound(key, bounds))
                xPos += keyWidth
            }

            yPos += keyHeight
        }

        Log.d("KeyboardLayout", "Calculated ${keyBounds.size} key bounds")
        return keyBounds
    }

    private fun createFallbackLayout(): List<List<KeyboardKey>> {
        Log.w("KeyboardLayout", "Creating fallback layout!")
        return listOf(
            listOf(
                KeyboardKey.CharKey(code = "a", label = "A", width = 1.0f),
                KeyboardKey.CharKey(code = "b", label = "B", width = 1.0f),
                KeyboardKey.CharKey(code = "c", label = "C", width = 1.0f)
            ),
            listOf(
                KeyboardKey.FunctionKey(code = "SPACE", label = "SPACE", width = 3.0f)
            )
        )
    }

    private fun createFallbackNumericLayout(): List<List<KeyboardKey>> {
        Log.w("KeyboardLayout", "Creating fallback numeric layout!")
        return listOf(
            listOf(
                KeyboardKey.CharKey(code = "1", label = "1", width = 1.0f),
                KeyboardKey.CharKey(code = "2", label = "2", width = 1.0f),
                KeyboardKey.CharKey(code = "3", label = "3", width = 1.0f)
            ),
            listOf(
                KeyboardKey.FunctionKey(code = "ABC", label = "ABC", width = 3.0f)
            )
        )
    }

    data class KeyBound(val key: KeyboardKey, val bounds: Rect)

    data class KeyboardConfig(
        val type: KeyboardType,
        val layoutFile: String,
        val supportsShift: Boolean,
        val rtl: Boolean
    )
}

// 自定义反序列化器来处理 KeyboardKey 的多态
class KeyboardKeyDeserializer : JsonDeserializer<KeyboardKey> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): KeyboardKey {
        val jsonObject = json.asJsonObject
        val keyType = jsonObject.get("type").asString

        return when (keyType) {
            "char" -> context.deserialize<KeyboardKey.CharKey>(json, KeyboardKey.CharKey::class.java)
            "function" -> context.deserialize<KeyboardKey.FunctionKey>(json, KeyboardKey.FunctionKey::class.java)
            else -> throw JsonParseException("Unknown key type: $keyType")
        }
    }
}