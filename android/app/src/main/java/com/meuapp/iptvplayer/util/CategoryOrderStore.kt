package com.meuapp.iptvplayer.util

import android.content.Context
import com.meuapp.iptvplayer.data.model.Category

/** Guarda a ordem que o usuário escolheu pra cada categoria (arrastando na
 * tela de Configurações > Posições das categorias) -- só pra Canais por
 * enquanto. Sem ordem salva, usa a ordem natural da playlist (comportamento
 * padrão). */
object CategoryOrderStore {
    private const val PREFS = "supremus_category_order"
    private const val KEY_LIVE_ORDER = "live_order"
    private const val SEPARATOR = "\u0001"

    fun saveLiveOrder(context: Context, orderedCategoryNames: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIVE_ORDER, orderedCategoryNames.joinToString(SEPARATOR)).apply()
    }

    private fun readLiveOrder(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIVE_ORDER, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    fun hasCustomLiveOrder(context: Context): Boolean = readLiveOrder(context).isNotEmpty()

    fun clearLiveOrder(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LIVE_ORDER).apply()
    }

    /** Aplica a ordem salva (se tiver) por cima da lista recebida --
     * categorias com posição salva vêm primeiro, na ordem escolhida;
     * qualquer categoria NOVA (que não existia quando a ordem foi salva,
     * ex: painel adicionou uma categoria depois) entra no final, na ordem
     * natural. Categorias "fixas" (Recém Assistidos/Favoritos, que
     * começam com "__") nunca são reordenadas por aqui -- ficam sempre
     * onde já estavam. */
    fun applyLiveOrder(context: Context, categories: List<Category>): List<Category> {
        val savedOrder = readLiveOrder(context)
        if (savedOrder.isEmpty()) return categories
        val pinned = categories.filter { it.categoryId.startsWith("__") }
        val reorderable = categories.filterNot { it.categoryId.startsWith("__") }
        val byName = reorderable.associateBy { it.categoryName }
        val ordered = savedOrder.mapNotNull { byName[it] }
        val remaining = reorderable.filterNot { it.categoryName in savedOrder }
        return pinned + ordered + remaining
    }
}
