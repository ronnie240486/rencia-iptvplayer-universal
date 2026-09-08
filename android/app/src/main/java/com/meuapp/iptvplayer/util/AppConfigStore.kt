package com.meuapp.iptvplayer.util

import android.content.Context
import com.meuapp.iptvplayer.data.model.AppConfigResponse

/** Guarda localmente a configuração visual/de mensagens que vem do painel
 * (logo, banner, fundo, ícones, textos de mensagem/bloqueio/renovação) --
 * assim, mesmo se a rede cair, o app ainda mostra o último visual válido
 * em vez de nada. Atualizado toda vez que a configuração é buscada de
 * novo com sucesso. */
object AppConfigStore {
    private const val PREFS = "supremus_app_config"
    private const val KEY_LOGO = "logo_url"
    private const val KEY_BANNER = "banner_url"
    private const val KEY_BACKGROUND = "background_url"
    private const val KEY_ICON_LIVE_TV = "icon_live_tv"
    private const val KEY_ICON_MOVIES = "icon_movies"
    private const val KEY_ICON_SERIES = "icon_series"
    private const val KEY_MESSAGE_TITLE = "message_title"
    private const val KEY_MESSAGE_TEXT = "message_text"
    private const val KEY_MESSAGE_IMAGE = "message_image_url"
    private const val KEY_RENEW_TEXT = "renew_button_text"
    private const val KEY_RENEW_URL = "renew_button_url"
    private const val KEY_LAST_EXPIRATION_MODAL_KEY = "last_expiration_modal_key"

    fun save(context: Context, config: AppConfigResponse) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOGO, config.logoUrl)
            .putString(KEY_BANNER, config.bannerUrl)
            .putString(KEY_BACKGROUND, config.backgroundUrl)
            .putString(KEY_ICON_LIVE_TV, config.icons?.liveTv)
            .putString(KEY_ICON_MOVIES, config.icons?.movies)
            .putString(KEY_ICON_SERIES, config.icons?.series)
            .putString(KEY_MESSAGE_TITLE, config.messageTitle)
            .putString(KEY_MESSAGE_TEXT, config.messageText)
            .putString(KEY_MESSAGE_IMAGE, config.messageImageUrl)
            .putString(KEY_RENEW_TEXT, config.renewButtonText)
            .putString(KEY_RENEW_URL, config.renewButtonUrl)
            .apply()
    }

    data class SavedVisualConfig(
        val logoUrl: String?,
        val bannerUrl: String?,
        val backgroundUrl: String?,
        val iconLiveTv: String?,
        val iconMovies: String?,
        val iconSeries: String?,
        val messageTitle: String?,
        val messageText: String?,
        val messageImageUrl: String?,
        val renewButtonText: String?,
        val renewButtonUrl: String?
    )

    fun read(context: Context): SavedVisualConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SavedVisualConfig(
            logoUrl = prefs.getString(KEY_LOGO, null),
            bannerUrl = prefs.getString(KEY_BANNER, null),
            backgroundUrl = prefs.getString(KEY_BACKGROUND, null),
            iconLiveTv = prefs.getString(KEY_ICON_LIVE_TV, null),
            iconMovies = prefs.getString(KEY_ICON_MOVIES, null),
            iconSeries = prefs.getString(KEY_ICON_SERIES, null),
            messageTitle = prefs.getString(KEY_MESSAGE_TITLE, null),
            messageText = prefs.getString(KEY_MESSAGE_TEXT, null),
            messageImageUrl = prefs.getString(KEY_MESSAGE_IMAGE, null),
            renewButtonText = prefs.getString(KEY_RENEW_TEXT, null),
            renewButtonUrl = prefs.getString(KEY_RENEW_URL, null)
        )
    }

    /** Modal de vencimento só deve aparecer UMA VEZ por expiration_modal_key
     * -- guarda a última chave já mostrada pra não repetir o mesmo aviso
     * toda hora. */
    fun hasShownExpirationModal(context: Context, modalKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_EXPIRATION_MODAL_KEY, null) == modalKey
    }

    fun markExpirationModalShown(context: Context, modalKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_EXPIRATION_MODAL_KEY, modalKey)
            .apply()
    }

    /** Guarda um CONJUNTO de IDs já mostrados (diferente do vencimento
     * acima, que só guarda o último) -- usado pra avisos técnicos, onde
     * podem existir vários diferentes ao mesmo tempo, e mostrar cada um
     * só uma vez, mesmo que o servidor não confirme a leitura de volta. */
    private const val KEY_SHOWN_ALERT_IDS = "shown_alert_ids"

    fun hasShownAlert(context: Context, alertId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SHOWN_ALERT_IDS, emptySet())?.contains(alertId) == true
    }

    fun markAlertShown(context: Context, alertId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SHOWN_ALERT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(alertId)
        // Limita a 200 IDs guardados, pra não crescer pra sempre.
        val trimmed = if (current.size > 200) current.toList().takeLast(200).toMutableSet() else current
        prefs.edit().putStringSet(KEY_SHOWN_ALERT_IDS, trimmed).apply()
    }
}
