package com.meuapp.iptvplayer.ui.epg

import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.databinding.ItemEpgBinding
import com.meuapp.iptvplayer.util.ReminderStore
import androidx.recyclerview.widget.RecyclerView

/** Guia de programação com VÁRIOS canais ao mesmo tempo (aba "EPG" da tela
 * de Canais) -- diferente do EpgAdapter simples, que só mostra a programação
 * de um canal selecionado. Cada linha aqui já mostra o nome do canal. */
class GuideAdapter(
    private val onReminderClick: (GuideProgramRow, Boolean) -> Unit,
) : RecyclerView.Adapter<GuideAdapter.ViewHolder>() {

    private val items = mutableListOf<GuideProgramRow>()

    fun submitList(newItems: List<GuideProgramRow>) {
        items.clear()
        val filtered = newItems.filter { it.listing.startValue().isNotBlank() }
        items.addAll(
            filtered.sortedWith(
                compareBy<GuideProgramRow> { it.channel.num }
                    .thenBy { EpgTime.millis(it.listing.startValue()) ?: Long.MAX_VALUE }
            )
        )
        notifyDataSetChanged()
    }

    private fun decodeBase64OrRaw(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "Informação não disponível"
        return runCatching {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
            // Se decodificar como base64 der lixo (caracteres de controle ou
            // de substituição), o texto original já era texto puro, não
            // base64 -- devolve ele mesmo em vez do lixo decodificado.
            val looksValid = decoded.isNotBlank() && decoded.none { it.code == 0xFFFD || (it.code < 9) }
            if (looksValid) decoded else trimmed
        }.getOrDefault(trimmed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = items[position]
        val listing = row.listing
        val context = holder.itemView.context
        val startValue = listing.startValue()
        val endValue = listing.endValue()
        val startMillis = EpgTime.millis(startValue)
        val endMillis = EpgTime.millis(endValue)
        val now = System.currentTimeMillis()
        val onAir = startMillis != null && endMillis != null && startMillis <= now && endMillis > now
        val upcoming = startMillis != null && startMillis > now
        val isScheduled = ReminderStore.isScheduled(context, row.channel.streamId, listing)

        holder.binding.tvChannelName.visibility = View.VISIBLE
        holder.binding.tvChannelName.text = row.channel.name
        holder.binding.tvTime.text = "${EpgTime.format(startValue)} - ${EpgTime.format(endValue)}"
        holder.binding.tvProgramTitle.text = decodeBase64OrRaw(listing.titleValue())
        holder.binding.tvProgramDescription.text = decodeBase64OrRaw(listing.descriptionValue())
        holder.binding.tvProgramState.visibility = if (onAir || upcoming) View.VISIBLE else View.GONE
        holder.binding.tvProgramState.text = if (onAir) "NO AR" else "A SEGUIR"
        holder.binding.tvProgramState.setTextColor(
            context.getColor(if (onAir) android.R.color.white else R.color.accent)
        )
        holder.binding.tvProgramState.setBackgroundResource(
            if (onAir) R.drawable.bg_category_active else R.drawable.bg_tile_highlight
        )
        holder.binding.btnReminder.alpha = if (isScheduled) 1f else 0.45f
        holder.binding.btnReminder.contentDescription =
            (if (isScheduled) "Cancelar lembrete de " else "Ativar lembrete de ") + row.channel.name
        holder.binding.btnReminder.setOnClickListener { onReminderClick(row, isScheduled) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemEpgBinding) : RecyclerView.ViewHolder(binding.root)
}
