package com.meuapp.iptvplayer.ui.epg

import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.meuapp.iptvplayer.data.model.EpgListing
import com.meuapp.iptvplayer.databinding.ItemMiniGuideBinding

/** Faixa compacta horizontal com os próximos programas do canal selecionado
 * no momento, mostrada logo abaixo do mini player -- diferente do
 * GuideAdapter (grade completa de vários canais na aba EPG). */
class MiniGuideAdapter : RecyclerView.Adapter<MiniGuideAdapter.ViewHolder>() {

    private val items = mutableListOf<EpgListing>()

    fun submitList(newItems: List<EpgListing>) {
        items.clear()
        items.addAll(
            newItems
                .filter { it.startValue().isNotBlank() }
                .sortedBy { EpgTime.millis(it.startValue()) ?: Long.MAX_VALUE }
                .take(6)
        )
        notifyDataSetChanged()
    }

    private fun decodeBase64OrRaw(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "Sem informação"
        return runCatching {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
            val looksValid = decoded.isNotBlank() && decoded.none { it.code == 0xFFFD || (it.code < 9) }
            if (looksValid) decoded else trimmed
        }.getOrDefault(trimmed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMiniGuideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val listing = items[position]
        val startMillis = EpgTime.millis(listing.startValue())
        val endMillis = EpgTime.millis(listing.endValue())
        val now = System.currentTimeMillis()
        val onAir = startMillis != null && endMillis != null && startMillis <= now && endMillis > now

        holder.binding.tvMiniGuideTime.text = EpgTime.format(listing.startValue())
        holder.binding.tvMiniGuideTitle.text = decodeBase64OrRaw(listing.titleValue())
        holder.binding.tvMiniGuideState.visibility = if (onAir) View.VISIBLE else View.GONE
        if (onAir) holder.binding.tvMiniGuideState.text = "AGORA"
        holder.itemView.alpha = if (onAir) 1f else 0.72f
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemMiniGuideBinding) : RecyclerView.ViewHolder(binding.root)
}
