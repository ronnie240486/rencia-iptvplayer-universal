package com.meuapp.iptvplayer.data.model

import com.google.gson.annotations.SerializedName

data class DeviceCheckResponse(
    @SerializedName("found") val found: Boolean = false,
    @SerializedName("allowed") val allowed: Boolean = false,
    @SerializedName("status") val status: String? = null,
    @SerializedName("app") val app: String? = null,
    @SerializedName("urlM3u8") val urlM3u8: String? = null,
    @SerializedName("urlEpg") val urlEpg: String? = null,
    @SerializedName("dataExpiracao") val expirationDate: String? = null
)

data class PlaylistSourcesResponse(
    @SerializedName("data") val data: List<PlaylistSource> = emptyList(),
    @SerializedName("observador_api_url") val observerApiUrl: String? = null
)

data class PlaylistSource(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("mac") val mac: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("type") val type: String? = null
)

data class RenciaLoginResponse(
    @SerializedName("registered") val registered: Boolean = false,
    @SerializedName("allowed") val allowed: Boolean = false,
    @SerializedName("mac") val mac: String? = null,
    @SerializedName("app_name") val appName: String? = null,
    @SerializedName("layout_id") val layoutId: String? = null,
    @SerializedName("playlist_urls") val playlistUrls: List<String> = emptyList(),
    @SerializedName("block_message") val blockMessage: String? = null,
    @SerializedName("error") val error: String? = null
)

data class UltraConfigResponse(
    @SerializedName("app_name") val appName: String? = null,
    @SerializedName("logo_url") val logoUrl: String? = null,
    @SerializedName("ultra_logo_url") val ultraLogoUrl: String? = null,
    @SerializedName("banner_url") val bannerUrl: String? = null,
    @SerializedName("ultra_banner_url") val ultraBannerUrl: String? = null,
    @SerializedName("background_url") val backgroundUrl: String? = null,
    @SerializedName("ultra_background_url") val ultraBackgroundUrl: String? = null,
    @SerializedName("server_api_url") val serverApiUrl: String? = null,
    @SerializedName("apk_download_url") val apkDownloadUrl: String? = null,
    @SerializedName("apk_version") val apkVersion: String? = null
)
