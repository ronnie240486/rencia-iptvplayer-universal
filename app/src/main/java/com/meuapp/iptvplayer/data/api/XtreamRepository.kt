package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.AuthResponse
import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.data.model.SeriesInfoResponse
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.data.model.ShortEpgResponse
import com.meuapp.iptvplayer.data.model.VodStream
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Dados de sessão do usuário logado, guardados em memória/DataStore. */
data class Session(
    val mac: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val status: String? = null,
    val expirationDate: String? = null,
    val appName: String? = null,
    val clientLogin: String? = null,
    val clientPassword: String? = null,
    val layoutId: String? = null,
    val playlistUrl: String? = null
)

class XtreamRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        })
        .build()

    private val api: XtreamApiService = Retrofit.Builder()
        .baseUrl("http://localhost/") // sobrescrito por @Url em cada chamada
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(XtreamApiService::class.java)

    private fun normalizeBase(serverUrl: String): String =
        serverUrl.trimEnd('/')

    suspend fun login(session: Session): Result<AuthResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}"
        val response = api.authenticate(url)
        if (!response.isSuccessful) error("HTTP ${response.code()}")
        val body = response.body() ?: error("Resposta vazia do servidor")
        if (body.userInfo?.auth != 1) error("Usuário ou senha inválidos")
        body
    }

    suspend fun getLiveCategories(session: Session): Result<List<Category>> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_live_categories"
        val response = api.getLiveCategories(url)
        response.body() ?: emptyList()
    }

    suspend fun getLiveStreams(session: Session, categoryId: String?): Result<List<LiveStream>> = runCatching {
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_live_streams$catParam"
        val response = api.getLiveStreams(url)
        response.body() ?: emptyList()
    }

    suspend fun getShortEpg(session: Session, streamId: Int): Result<ShortEpgResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_short_epg&stream_id=$streamId&limit=20"
        val response = api.getShortEpg(url)
        response.body() ?: ShortEpgResponse(emptyList())
    }

    /** Monta a URL de stream ao vivo (formato padrão Xtream: .../live/user/pass/id.m3u8) */
    fun buildLiveStreamUrl(session: Session, streamId: Int): String {
        val base = normalizeBase(session.serverUrl)
        return "$base/live/${session.username}/${session.password}/$streamId.m3u8"
    }

    suspend fun getVodCategories(session: Session): Result<List<Category>> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_vod_categories"
        api.getVodCategories(url).body() ?: emptyList()
    }

    suspend fun getVodStreams(session: Session, categoryId: String?): Result<List<VodStream>> = runCatching {
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_vod_streams$catParam"
        api.getVodStreams(url).body() ?: emptyList()
    }

    /** Monta a URL de reprodução de um filme (VOD). */
    fun buildVodStreamUrl(session: Session, streamId: Int, containerExtension: String?): String {
        val base = normalizeBase(session.serverUrl)
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$base/movie/${session.username}/${session.password}/$streamId.$ext"
    }

    suspend fun getSeriesCategories(session: Session): Result<List<Category>> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series_categories"
        api.getSeriesCategories(url).body() ?: emptyList()
    }

    suspend fun getSeries(session: Session, categoryId: String?): Result<List<SeriesItem>> = runCatching {
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series$catParam"
        api.getSeries(url).body() ?: emptyList()
    }

    suspend fun getSeriesInfo(session: Session, seriesId: Int): Result<SeriesInfoResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series_info&series_id=$seriesId"
        val response = api.getSeriesInfo(url)
        if (!response.isSuccessful) error("Não foi possível carregar os episódios")
        response.body() ?: error("Detalhes da série vazios")
    }

    fun buildSeriesStreamUrl(
        session: Session,
        episodeId: String,
        containerExtension: String?
    ): String {
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "${normalizeBase(session.serverUrl)}/series/${session.username}/${session.password}/$episodeId.$ext"
    }
}
