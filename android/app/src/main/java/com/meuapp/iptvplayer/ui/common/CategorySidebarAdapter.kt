package com.meuapp.iptvplayer.ui.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.databinding.ItemSidebarCategoryBinding

/**
 * Sidebar de categorias reutilizada em Live TV, VOD e Séries.
 * A cor da tarja da categoria ativa é configurável (Configurações > Aparência).
 */
class CategorySidebarAdapter(
    private val barEnabled: Boolean,
    private val barColorHex: String,
    private val onSelect: (Category) -> Unit
) : RecyclerView.Adapter<CategorySidebarAdapter.ViewHolder>() {

    private val items = mutableListOf<Category>()
    private var selectedIndex = 0

    fun submitList(newItems: List<Category>, preSelectIndex: Int = 0, autoSelect: Boolean = true) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = preSelectIndex.coerceIn(0, (newItems.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
        if (autoSelect && newItems.isNotEmpty()) onSelect(newItems[selectedIndex])
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSidebarCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = items[position]
        val isActive = position == selectedIndex
        holder.binding.tvCategoryName.text = category.categoryName

        if (isActive && barEnabled) {
            val color = try {
                Color.parseColor(barColorHex)
            } catch (e: IllegalArgumentException) {
                holder.itemView.context.getColor(R.color.accent)
            }
            val bg = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 8f * holder.itemView.resources.displayMetrics.density
            }
            holder.binding.tvCategoryName.background = bg
            holder.binding.tvCategoryName.setTextColor(
                if (isLightColor(color)) Color.BLACK else Color.WHITE
            )
        } else if (isActive) {
            holder.binding.tvCategoryName.setBackgroundResource(0)
            holder.binding.tvCategoryName.setTextColor(
                holder.itemView.context.getColor(R.color.accent)
            )
        } else {
            holder.binding.tvCategoryName.setBackgroundResource(0)
            holder.binding.tvCategoryName.setTextColor(
                holder.itemView.context.getColor(R.color.text_secondary)
            )
        }

        holder.binding.tvCategoryName.setOnClickListener {
            val previous = selectedIndex
            selectedIndex = position
            notifyItemChanged(previous)
            notifyItemChanged(selectedIndex)
            onSelect(category)
        }
    }

    override fun getItemCount() = items.size

    private fun isLightColor(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        return luminance > 150
    }

    class ViewHolder(val binding: ItemSidebarCategoryBinding) : RecyclerView.ViewHolder(binding.root)
}
