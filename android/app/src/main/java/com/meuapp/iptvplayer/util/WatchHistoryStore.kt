package com.meuapp.iptvplayer.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Um item recentemente assistido (canal, filme ou episódio de série) --
 * guardado localmente pra montar a fileira "Continuar assistindo" na Home. */
data class WatchHistoryItem(
    val kind: String, // "live" | "vod" | "series"
    val title: String,
    val subtitle: String?, // ex: nome do canal pro episódio, ou vazio
    val posterUrl: String?,
    val streamUrl: String,
    val watchedAt: Long
)

/** Guarda os últimos itens assistidos (SharedPreferences, formato JSON
 * simples) -- usado pela fileira "Continuar assistindo" da Home. */
object WatchHistoryStore {
    private const val PREFS = "supremus_watch_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 15

    fun record(context: Context, item: WatchHistoryItem) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = readAll(context).toMutableList()
        // Remove entradas antigas do mesmo conteúdo (mesmo streamUrl) antes
        // de colocar essa no topo de novo.
        current.removeAll { it.streamUrl == item.streamUrl }
        current.add(0, item)
        val trimmed = current.take(MAX_ITEMS)
        val array = JSONArray()
        trimmed.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    fun readAll(context: Context): List<WatchHistoryItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> fromJson(array.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
    }

    private fun toJson(item: WatchHistoryItem): JSONObject = JSONObject().apply {
        put("kind", item.kind)
        put("title", item.title)
        put("subtitle", item.subtitle ?: "")
        put("posterUrl", item.posterUrl ?: "")
        put("streamUrl", item.streamUrl)
        put("watchedAt", item.watchedAt)
    }

    private fun fromJson(obj: JSONObject?): WatchHistoryItem? {
        obj ?: return null
        return runCatching {
            WatchHistoryItem(
                kind = obj.getString("kind"),
                title = obj.getString("title"),
                subtitle = obj.optString("subtitle").ifBlank { null },
                posterUrl = obj.optString("posterUrl").ifBlank { null },
                streamUrl = obj.getString("streamUrl"),
                watchedAt = obj.optLong("watchedAt")
            )
        }.getOrNull()
    }
}
