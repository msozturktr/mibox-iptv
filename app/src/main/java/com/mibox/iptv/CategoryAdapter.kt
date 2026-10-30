package com.mibox.iptv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Derives category chips from the `group` values of the channels (e.g. "Ulusal & Dizi" -> Ulusal, Dizi). */
object ChannelCategories {
    private const val MAX_CHIPS = 10
    private val SPLIT = Regex("\\s*[&/]\\s*")

    private fun tokens(group: String): List<String> =
        group.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** `token == null` means "Tümü": everything matches. */
    fun matches(channel: Channel, token: String?): Boolean =
        token == null || tokens(channel.group).contains(token)

    /** Tokens used by at least two channels, most common first. */
    fun build(channels: List<Channel>): List<String> {
        val counts = HashMap<String, Int>()
        channels.forEach { ch -> tokens(ch.group).distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.entries
            .filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_CHIPS)
            .map { it.key }
    }
}

class CategoryAdapter(
    private val onSelected: (String?) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ChipHolder>() {

    // Index 0 is always "Tümü" (null token)
    private var items: List<String?> = listOf(null)
    var selectedPosition = 0
        private set

    fun submit(tokens: List<String>) {
        items = listOf<String?>(null) + tokens
        selectedPosition = 0
        notifyDataSetChanged()
    }

    /** Selects "Tümü" without invoking the callback (used when editing modes reset the filter). */
    fun resetSelection() {
        if (selectedPosition == 0) return
        val prev = selectedPosition
        selectedPosition = 0
        notifyItemChanged(prev)
        notifyItemChanged(0)
    }

    private fun select(position: Int) {
        if (position == selectedPosition) return
        val prev = selectedPosition
        selectedPosition = position
        notifyItemChanged(prev)
        notifyItemChanged(position)
        onSelected(items[position])
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ChipHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ChipHolder, position: Int) {
        val chip = holder.chip
        chip.text = items[position] ?: chip.context.getString(R.string.category_all)
        val selected = position == selectedPosition
        chip.isSelected = selected
        chip.setTextColor(if (selected) WHITE else SECONDARY)
    }

    override fun getItemCount(): Int = items.size

    inner class ChipHolder(val chip: TextView) : RecyclerView.ViewHolder(chip) {
        init {
            chip.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) select(pos)
            }
            chip.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val SECONDARY = 0xFFC4C6D0.toInt()
    }
}
