package com.meuapp.iptvplayer.ui.settings

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.meuapp.iptvplayer.databinding.ItemCategoryOrderBinding

/** Lista de nomes de categoria que o usuário pode arrastar pra reordenar --
 * cada arraste já reordena a lista em memória na hora (feedback visual
 * imediato); quem chama decide quando salvar (ver onOrderChanged). */
class CategoryOrderAdapter(
    private val onOrderChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<CategoryOrderAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()
    private var touchHelper: ItemTouchHelper? = null

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun currentOrder(): List<String> = items.toList()

    fun attachTo(recyclerView: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val moved = items.removeAt(from)
                items.add(to, moved)
                notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Só salva quando o dedo solta de vez -- não a cada
                // posição intermediária do arraste.
                onOrderChanged(items.toList())
            }
        }
        touchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.tvCategoryName.text = items[position]
        holder.binding.tvDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchHelper?.startDrag(holder)
            }
            false
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemCategoryOrderBinding) : RecyclerView.ViewHolder(binding.root)
}
