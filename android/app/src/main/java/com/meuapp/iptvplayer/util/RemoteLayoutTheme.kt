package com.meuapp.iptvplayer.util

import android.content.Context
import com.meuapp.iptvplayer.R

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

    /** Imagem de referência local exibida para o modelo liberado pelo painel. */
    fun background(layoutId: String?): Int = when (layoutId?.lowercase()) {
        "classic", "ember", "imperio", "imperio_play" -> R.drawable.model_ember
        "htv", "pulse", "fusion" -> R.drawable.model_pulse
        "tv_express", "arena", "infinitus" -> R.drawable.model_arena
        "cinema", "next", "supremus" -> R.drawable.model_next
        "minimal", "prime", "maximus", "maximus_player" -> R.drawable.model_prime
        "sports", "vision", "prestige" -> R.drawable.model_vision
        "kids", "blue", "optimus" -> R.drawable.model_blue
        "compact", "cosmos", "ouropro", "ouro_pro" -> R.drawable.model_cosmos
        else -> R.drawable.model_ember
    }
}
