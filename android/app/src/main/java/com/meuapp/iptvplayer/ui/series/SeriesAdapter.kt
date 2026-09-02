package com.meuapp.iptvplayer.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.databinding.ItemPosterBinding

class SeriesAdapter(
    private val onClick: (SeriesItem) -> Unit,
    private val onFocused: (SeriesItem) -> Unit = {},
) : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {

    private val items = mutableListOf<SeriesItem>()

    fun submitList(newItems: List<SeriesItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvPosterTitle.text = item.name
        holder.binding.tvPosterSub.text = item.rating?.let { "★ $it" } ?: ""
        holder.binding.ivPoster.load(item.cover) { crossfade(true) }
        holder.binding.root.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(item) }
        holder.binding.root.setOnClickListener {
            onFocused(item)
            onClick(item)
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root)
}
