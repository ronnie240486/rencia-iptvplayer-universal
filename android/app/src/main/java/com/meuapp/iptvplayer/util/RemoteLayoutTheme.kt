package com.meuapp.iptvplayer.util

import android.content.Context

/** Tema recebido do painel; mantém a navegação única e muda a identidade visual por conta. */
object RemoteLayoutTheme {
    private const val PREFS = "iptv_remote_layout"
    private const val KEY_LAYOUT = "layout_id"

    fun save(context: Context, layoutId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAYOUT, layoutId ?: "classic")
            .apply()
        AppearancePrefs.setCategoryBarColor(context, accent(layoutId))
    }

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT, "classic") ?: "classic"

    fun accent(layoutId: String?): String = when (layoutId) {
        "htv" -> "#26B7FF"
        "tv_express" -> "#FF6D3A"
        "cinema" -> "#B897FF"
        "minimal" -> "#D8DEE9"
        "sports" -> "#37D47E"
        "kids" -> "#FF7EAA"
        "compact" -> "#F4C95D"
        else -> "#2E8CFF"
    }
}
