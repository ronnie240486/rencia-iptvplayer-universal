package com.meuapp.iptvplayer.data.model

import com.google.gson.annotations.SerializedName

data class SeriesInfoResponse(
    @SerializedName("info") val info: SeriesInfo?,
    @SerializedName("episodes") val episodes: Map<String, List<SeriesEpisode>>?,
    @SerializedName("seasons") val seasons: List<SeriesSeason>?
)

data class SeriesInfo(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?
)

data class SeriesSeason(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("episode_count") val episodeCount: Int?,
    @SerializedName("cover") val cover: String?
)

data class SeriesEpisode(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNumber: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: SeriesEpisodeInfo?,
    // Preenchido só quando o episódio veio de uma playlist M3U (get.php)
    // em vez da API Xtream -- ver LiveStream.directStreamUrl.
    val directStreamUrl: String? = null
)

data class SeriesEpisodeInfo(
    @SerializedName("movie_image") val image: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("duration_secs") val durationSeconds: Long?
)
