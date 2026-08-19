package com.meuapp.iptvplayer.data.model

import com.google.gson.annotations.SerializedName

data class RadioStation(
    @SerializedName("stationuuid") val stationUuid: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("codec") val codec: String?,
    @SerializedName("bitrate") val bitrate: Int?,
    @SerializedName("favicon") val favicon: String?,
    @SerializedName("url_resolved") val urlResolved: String?,
    @SerializedName("lastcheckok") val lastCheckOk: Int?,
    @SerializedName("tags") val tags: String?
)
