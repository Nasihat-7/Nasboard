package com.example.nasboard.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.example.nasboard.R

enum class SetupPage {
    ENABLE,
    SELECT;

    fun getStepText(context: Context) = context.getText(
        when (this) {
            ENABLE -> R.string.setup_step_one
            SELECT -> R.string.setup_step_two
        }
    )

    fun getHintText(context: Context) = context.getText(
        when (this) {
            ENABLE -> R.string.setup_enable_hint
            SELECT -> R.string.setup_select_hint
        }
    )

    fun getButtonText(context: Context) = context.getText(
        when (this) {
            ENABLE -> R.string.setup_enable_button
            SELECT -> R.string.setup_select_button
        }
    )

    fun getButtonAction(context: Context) {
        when (this) {
            ENABLE -> {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context.startActivity(intent)
            }
            SELECT -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
    }

    fun isDone(context: Context): Boolean {
        return when (this) {
            ENABLE -> isInputMethodEnabled(context)
            SELECT -> isInputMethodSelected(context)
        }
    }

    fun isLastPage(): Boolean {
        return this == entries.last()
    }

    private fun isInputMethodEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledInputMethods = imm.enabledInputMethodList
        return enabledInputMethods.any { it.packageName == context.packageName }
    }

    private fun isInputMethodSelected(context: Context): Boolean {
        val defaultIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return defaultIme?.contains(context.packageName) == true
    }

    companion object {
        fun hasUndonePage(context: Context) = entries.any { !it.isDone(context) }

        fun firstUndonePage(context: Context) = entries.firstOrNull { !it.isDone(context) }
    }
}

// 将扩展函数移到伴生对象外部
fun Int.isLastPage(): Boolean {
    return this == SetupPage.entries.size - 1
}