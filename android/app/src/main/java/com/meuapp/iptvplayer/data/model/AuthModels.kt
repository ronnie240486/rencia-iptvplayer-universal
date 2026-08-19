package com.meuapp.iptvplayer.data.model

import com.google.gson.annotations.SerializedName

/**
 * Resposta do endpoint player_api.php (sem parâmetros extras) usado para
 * validar as credenciais do usuário junto ao provedor Xtream Codes.
 */
data class AuthResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    @SerializedName("username") val username: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("is_trial") val isTrial: String?,
    @SerializedName("active_cons") val activeConnections: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("auth") val auth: Int?
)

data class ServerInfo(
    @SerializedName("url") val url: String?,
    @SerializedName("port") val port: String?,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val protocol: String?
)
