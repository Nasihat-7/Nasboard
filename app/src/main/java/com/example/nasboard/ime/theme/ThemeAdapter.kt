package com.example.nasboard.ime.theme

import android.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ThemeAdapter(
    private val onThemeSelected: (ThemeInfo) -> Unit
) : ListAdapter<ThemeAdapter.ThemeItem, ThemeAdapter.ViewHolder>(ThemeDiffCallback()) {

    sealed class ThemeItem {
        data class Header(val title: String) : ThemeItem()
        data class Theme(val themeInfo: ThemeInfo) : ThemeItem()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val themeName: TextView = itemView.findViewById(R.id.text1)
        private val themeType: TextView = itemView.findViewById(R.id.text2)

        fun bind(themeItem: ThemeItem, onThemeSelected: (ThemeInfo) -> Unit) {
            when (themeItem) {
                is ThemeItem.Header -> {
                    themeName.text = themeItem.title
                    themeType.visibility = View.GONE
                    itemView.isClickable = false
                    itemView.setBackgroundColor(
                        ContextCompat.getColor(itemView.context, R.color.transparent)
                    )
                }
                is ThemeItem.Theme -> {
                    themeName.text = themeItem.themeInfo.displayName
                    themeType.text = when (themeItem.themeInfo.type) {
                        ThemeManager.ThemeType.CORE -> "核心主题"
                        ThemeManager.ThemeType.JSON -> "扩展主题"
                    }
                    themeType.visibility = View.VISIBLE
                    itemView.isClickable = true
                    itemView.setOnClickListener {
                        onThemeSelected(themeItem.themeInfo)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position], onThemeSelected)
    }

    fun submitThemes(themes: Map<String, List<ThemeInfo>>) {
        val items = mutableListOf<ThemeItem>()

        themes.forEach { (section, themeList) ->
            items.add(ThemeItem.Header(section))
            items.addAll(themeList.map { ThemeItem.Theme(it) })
        }

        submitList(items)
    }
}

class ThemeDiffCallback : DiffUtil.ItemCallback<ThemeAdapter.ThemeItem>() {
    override fun areItemsTheSame(oldItem: ThemeAdapter.ThemeItem, newItem: ThemeAdapter.ThemeItem): Boolean {
        return when {
            oldItem is ThemeAdapter.ThemeItem.Header && newItem is ThemeAdapter.ThemeItem.Header ->
                oldItem.title == newItem.title
            oldItem is ThemeAdapter.ThemeItem.Theme && newItem is ThemeAdapter.ThemeItem.Theme ->
                oldItem.themeInfo.name == newItem.themeInfo.name && oldItem.themeInfo.type == newItem.themeInfo.type
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: ThemeAdapter.ThemeItem, newItem: ThemeAdapter.ThemeItem): Boolean {
        return oldItem == newItem
    }
}