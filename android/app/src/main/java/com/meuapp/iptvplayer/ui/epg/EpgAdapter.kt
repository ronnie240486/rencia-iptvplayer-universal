package com.meuapp.iptvplayer.ui.epg

import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.meuapp.iptvplayer.data.model.EpgListing
import com.meuapp.iptvplayer.databinding.ItemEpgBinding
import com.meuapp.iptvplayer.util.ReminderStore

class EpgAdapter(
    private var streamId: Int = -1,
    private val onReminderClick: (EpgListing, Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<EpgAdapter.ViewHolder>() {

    private val items = mutableListOf<EpgListing>()

    fun setStreamId(value: Int) {
        streamId = value
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<EpgListing>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun decodeBase64(value: String): String = try {
        String(Base64.decode(value, Base64.DEFAULT)).ifBlank { value }
    } catch (_: Exception) {
        value
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val scheduled = streamId != -1 && ReminderStore.isScheduled(holder.itemView.context, streamId, item)
        holder.binding.tvChannelName.visibility = android.view.View.GONE
        holder.binding.tvProgramState.visibility = android.view.View.GONE
        holder.binding.tvTime.text = "${EpgTime.format(item.startValue())} - ${EpgTime.format(item.endValue())}"
        holder.binding.tvProgramTitle.text = decodeBase64(item.titleValue())
        holder.binding.tvProgramDescription.text = decodeBase64(item.descriptionValue())
        holder.binding.btnReminder.alpha = if (scheduled) 1f else 0.45f
        holder.binding.btnReminder.contentDescription = if (scheduled) "Cancelar lembrete" else "Ativar lembrete"
        holder.binding.btnReminder.setOnClickListener { onReminderClick(item, scheduled) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemEpgBinding) : RecyclerView.ViewHolder(binding.root)
}
