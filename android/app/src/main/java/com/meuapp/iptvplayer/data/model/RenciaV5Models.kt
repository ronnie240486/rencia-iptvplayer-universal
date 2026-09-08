package com.meuapp.iptvplayer.data.model

import com.google.gson.annotations.SerializedName

/** Resposta de GET /api/v5/apps/{appId}/config?mac={MAC} -- rota
 * oficial/prioritária pra aplicativos novos, no lugar do antigo
 * /api/device/check. Traz tudo de uma vez: status do MAC, mensagens,
 * imagens dinâmicas, ícones, preferências do player e as listas ativas. */
data class AppConfigResponse(
    @SerializedName("registered") val registered: Boolean = false,
    @SerializedName("allowed") val allowed: Boolean = false,
    @SerializedName("status") val status: String? = null,
    @SerializedName("app_id") val appId: String? = null,
    @SerializedName("app_name") val appName: String? = null,
    @SerializedName("message_title") val messageTitle: String? = null,
    @SerializedName("message_text") val messageText: String? = null,
    @SerializedName("server_api_url") val serverApiUrl: String? = null,
    @SerializedName("block_title") val blockTitle: String? = null,
    @SerializedName("block_message") val blockMessage: String? = null,
    @SerializedName("renew_button_text") val renewButtonText: String? = null,
    @SerializedName("renew_button_url") val renewButtonUrl: String? = null,
    @SerializedName("logo_url") val logoUrl: String? = null,
    @SerializedName("banner_url") val bannerUrl: String? = null,
    @SerializedName("background_url") val backgroundUrl: String? = null,
    @SerializedName("message_image_url") val messageImageUrl: String? = null,
    @SerializedName("icons") val icons: AppIcons? = null,
    @SerializedName("player") val player: AppPlayerPrefs? = null,
    @SerializedName("playlist_urls") val playlistUrls: List<String> = emptyList(),
    @SerializedName("expiration_date") val expirationDate: String? = null,
    @SerializedName("expiration_show_modal") val expirationShowModal: Boolean = false,
    @SerializedName("expiration_modal_key") val expirationModalKey: String? = null,
    @SerializedName("expiration_modal_title") val expirationModalTitle: String? = null,
    @SerializedName("expiration_modal_message") val expirationModalMessage: String? = null,
    @SerializedName("playlist_sync_required") val playlistSyncRequired: Boolean = false
)

data class AppIcons(
    @SerializedName("live_tv") val liveTv: String? = null,
    @SerializedName("movies") val movies: String? = null,
    @SerializedName("series") val series: String? = null
)

data class AppPlayerPrefs(
    @SerializedName("autoplay") val autoplay: Boolean? = null,
    @SerializedName("rotation") val rotation: String? = null,
    @SerializedName("quality") val quality: String? = null,
    @SerializedName("retries") val retries: Int? = null,
    @SerializedName("language") val language: String? = null
)

/** Resposta de GET /api/v5/apps/{appId}/update?mac={MAC} */
data class AppUpdateResponse(
    @SerializedName("version") val version: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("apk_link") val apkLink: String? = null,
    @SerializedName("force_update") val forceUpdate: Boolean = false,
    @SerializedName("update_available") val updateAvailable: Boolean = false,
    @SerializedName("release_notes") val releaseNotes: String? = null
)

/** Resposta de GET /api/v5/heartbeat -- chamado ao trocar de conteúdo e a
 * cada 60s enquanto o mesmo conteúdo continua tocando. */
data class HeartbeatResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("contentUpdated") val contentUpdated: Boolean = false,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("command") val command: RemoteCommand? = null
)

/** Resposta de GET /api/v5/list-notifications -- avisos técnicos,
 * vencimento e estado de troca automática de lista (failover). */
data class ListNotificationsResponse(
    @SerializedName("notifications") val notifications: List<ListNotification> = emptyList(),
    @SerializedName("expiration") val expiration: ExpirationInfo? = null,
    @SerializedName("failover_active") val failoverActive: Boolean = false,
    @SerializedName("failover_state") val failoverState: String? = null, // primary | backup_active | primary_restored
    @SerializedName("active_list_name") val activeListName: String? = null,
    @SerializedName("active_list_number") val activeListNumber: Int? = null,
    @SerializedName("playlist_sync_required") val playlistSyncRequired: Boolean = false,
    @SerializedName("playlist_sync_mode") val playlistSyncMode: String? = null,
    @SerializedName("playlist_sync_message") val playlistSyncMessage: String? = null,
    @SerializedName("failover_transition_id") val failoverTransitionId: String? = null
)

data class ListNotification(
    @SerializedName("id") val id: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("severity") val severity: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("acknowledged") val acknowledged: Boolean = false
)

data class ExpirationInfo(
    @SerializedName("date") val date: String? = null,
    @SerializedName("days_remaining") val daysRemaining: Int? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("modal_key") val modalKey: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("message") val message: String? = null
)

/** Resposta de POST /api/v5/playback-failure -- reportado quando o player
 * detecta erro REAL de rede/timeout/indisponibilidade (não erro visual). */
data class PlaybackFailureResponse(
    @SerializedName("switch_applied") val switchApplied: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("failover_active") val failoverActive: Boolean = false,
    @SerializedName("active_list_number") val activeListNumber: Int? = null,
    @SerializedName("playlist_sync_required") val playlistSyncRequired: Boolean = false,
    @SerializedName("failover_transition_id") val failoverTransitionId: String? = null
)

data class RemoteCommand(
    @SerializedName("id") val id: String? = null,
    @SerializedName("command_id") val commandId: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("payload") val payload: String? = null
)

data class RemoteCommandsResponse(
    @SerializedName("commands") val commands: List<RemoteCommand> = emptyList()
)

data class SimpleAckResponse(
    @SerializedName("success") val success: Boolean = false
)
