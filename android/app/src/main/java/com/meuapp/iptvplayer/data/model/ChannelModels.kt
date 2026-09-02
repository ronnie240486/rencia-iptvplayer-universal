package com.meuapp.iptvplayer.data.model

import com.google.gson.annotations.SerializedName

data class Category(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String
)

data class LiveStream(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?
)

/** Representa uma entrada (programa) do EPG de um canal específico.
 * Alguns provedores mandam start/end como texto, outros como
 * start_timestamp/stop_timestamp (unix), e outros usam "stop" em vez de
 * "end" -- os campos são opcionais e os métodos *Value() escolhem o
 * primeiro valor disponível, na ordem mais confiável. */
data class EpgListing(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val titleBase64: String? = null,
    @SerializedName("description") val descriptionBase64: String? = null,
    @SerializedName("start") val start: String? = null,
    @SerializedName("end") val end: String? = null,
    @SerializedName("start_timestamp") val startTimestamp: Long? = null,
    @SerializedName("stop_timestamp") val stopTimestamp: Long? = null,
    @SerializedName("stop") val stop: String? = null,
) {
    fun startValue(): String {
        start?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return startTimestamp?.toString() ?: ""
    }

    fun endValue(): String {
        end?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        stop?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return stopTimestamp?.toString() ?: ""
    }

    fun titleValue(): String = titleBase64 ?: ""

    fun descriptionValue(): String = descriptionBase64 ?: ""
}

data class ShortEpgResponse(
    @SerializedName("epg_listings") val listings: List<EpgListing>?
)

data class VodStream(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("container_extension") val containerExtension: String?
)

data class SeriesItem(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("last_modified") val lastModified: String?
)

