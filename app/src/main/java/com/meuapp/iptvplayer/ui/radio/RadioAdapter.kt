package com.meuapp.iptvplayer.ui.radio

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.model.RadioStation
import com.meuapp.iptvplayer.databinding.ItemRadioBinding

class RadioAdapter(
    private val onClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<RadioAdapter.ViewHolder>() {

    private val allItems = mutableListOf<RadioStation>()
    private val visibleItems = mutableListOf<RadioStation>()

    fun submitList(items: List<RadioStation>) {
        allItems.clear()
        allItems.addAll(items)
        visibleItems.clear()
        visibleItems.addAll(items)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        visibleItems.clear()
        visibleItems.addAll(
            if (q.isBlank()) allItems else allItems.filter { station ->
                listOf(station.name, station.country, station.tags)
                    .filterNotNull()
                    .joinToString(" ")
                    .lowercase()
                    .contains(q)
            }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRadioBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = visibleItems[position]
        holder.binding.tvName.text = station.name?.takeIf { it.isNotBlank() } ?: "Rádio online"
        val meta = listOfNotNull(
            station.country?.takeIf { it.isNotBlank() },
            station.codec?.takeIf { it.isNotBlank() },
            station.bitrate?.takeIf { it > 0 }?.let { "${it}kbps" }
        ).joinToString(" • ")
        holder.binding.tvMeta.text = meta.ifBlank { "Stream online" }
        holder.binding.ivLogo.load(station.favicon) {
            crossfade(true)
        }
        holder.binding.root.setOnClickListener { onClick(station) }
    }

    override fun getItemCount(): Int = visibleItems.size

    class ViewHolder(val binding: ItemRadioBinding) : RecyclerView.ViewHolder(binding.root)
}
