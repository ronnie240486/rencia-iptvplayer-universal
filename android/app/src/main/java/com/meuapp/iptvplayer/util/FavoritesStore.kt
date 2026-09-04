package com.meuapp.iptvplayer.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteItem(
    val kind: String, // "live" | "vod" | "series"
    val title: String,
    val posterUrl: String?,
    val streamUrl: String, // pra live/vod: link direto. pra series: usado só como chave única
    val seriesId: Int? = null,
    val seriesCover: String? = null,
    val addedAt: Long
)

/** Favoritos de verdade -- canais, filmes e séries, guardados localmente.
 * Chave única é o streamUrl (ou "series:<id>" pra séries, que não têm uma
 * URL de stream fixa). */
object FavoritesStore {
    private const val PREFS = "supremus_favorites"
    private const val KEY_ITEMS = "items"

    private fun uniqueKey(kind: String, streamUrl: String, seriesId: Int?): String =
        if (kind == "series") "series:${seriesId ?: streamUrl}" else streamUrl

    fun isFavorite(context: Context, kind: String, streamUrl: String, seriesId: Int? = null): Boolean =
        readAll(context).any { uniqueKey(it.kind, it.streamUrl, it.seriesId) == uniqueKey(kind, streamUrl, seriesId) }

    fun toggle(context: Context, item: FavoriteItem): Boolean {
        val current = readAll(context).toMutableList()
        val key = uniqueKey(item.kind, item.streamUrl, item.seriesId)
        val existingIndex = current.indexOfFirst { uniqueKey(it.kind, it.streamUrl, it.seriesId) == key }
        val nowFavorite: Boolean
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            nowFavorite = false
        } else {
            current.add(0, item)
            nowFavorite = true
        }
        save(context, current)
        return nowFavorite
    }

    fun readAll(context: Context): List<FavoriteItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> fromJson(array.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<FavoriteItem>) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun toJson(item: FavoriteItem): JSONObject = JSONObject().apply {
        put("kind", item.kind)
        put("title", item.title)
        put("posterUrl", item.posterUrl ?: "")
        put("streamUrl", item.streamUrl)
        put("seriesId", item.seriesId ?: -1)
        put("seriesCover", item.seriesCover ?: "")
        put("addedAt", item.addedAt)
    }

    private fun fromJson(obj: JSONObject?): FavoriteItem? {
        obj ?: return null
        return runCatching {
            FavoriteItem(
                kind = obj.getString("kind"),
                title = obj.getString("title"),
                posterUrl = obj.optString("posterUrl").ifBlank { null },
                streamUrl = obj.getString("streamUrl"),
                seriesId = obj.optInt("seriesId", -1).takeIf { it != -1 },
                seriesCover = obj.optString("seriesCover").ifBlank { null },
                addedAt = obj.optLong("addedAt")
            )
        }.getOrNull()
    }
}
