package com.meuapp.iptvplayer.util

import android.content.Context

/** Preferências de personalização visual escolhidas pelo usuário em Configurações. */
object AppearancePrefs {
    private const val PREFS = "iptv_appearance_prefs"
    private const val KEY_BAR_ENABLED = "category_bar_enabled"
    private const val KEY_BAR_COLOR = "category_bar_color"
    private const val KEY_BACKDROP_ENABLED = "backdrop_poster_enabled"

    const val DEFAULT_COLOR = "#3DDC97"

    fun isCategoryBarEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BAR_ENABLED, true)

    fun setCategoryBarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BAR_ENABLED, enabled).apply()
    }

    fun getCategoryBarColor(context: Context): String =
        prefs(context).getString(KEY_BAR_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR

    fun setCategoryBarColor(context: Context, colorHex: String) {
        prefs(context).edit().putString(KEY_BAR_COLOR, colorHex).apply()
    }

    fun isBackdropPosterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKDROP_ENABLED, true)

    fun setBackdropPosterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKDROP_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
