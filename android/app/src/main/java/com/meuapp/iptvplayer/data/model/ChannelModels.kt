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

/** Representa uma entrada (programa) do EPG de um canal específico. */
data class EpgListing(
    @SerializedName("id") val id: String,
    @SerializedName("title") val titleBase64: String,
    @SerializedName("description") val descriptionBase64: String,
    @SerializedName("start") val start: String,
    @SerializedName("end") val end: String
)

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

