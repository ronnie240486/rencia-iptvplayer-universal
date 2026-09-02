package com.meuapp.iptvplayer.data.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
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

    // Esse player é "universal" (qualquer provedor Xtream Codes), mas cada
    // painel implementa a API de um jeito ligeiramente diferente -- em
    // especial, campos como category_id/stream_id/exp_date às vezes vêm
    // como texto ("5") e às vezes como número puro (5), dependendo do
    // software do painel. Com Gson padrão, se o app espera String e o
    // painel manda número (ou vice-versa), a conversão da lista INTEIRA
    // falha silenciosamente. Esses adapters aceitam qualquer um dos dois
    // formatos.
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(String::class.java, JsonDeserializer { json, _, _ ->
            if (json == null || json.isJsonNull) null
            else runCatching { json.asJsonPrimitive.asString }.getOrNull()
        })
        .registerTypeAdapter(Int::class.javaPrimitiveType, JsonDeserializer { json, _, _ ->
            if (json == null || json.isJsonNull) 0
            else runCatching { json.asJsonPrimitive.asString.trim().toDoubleOrNull()?.toInt() }.getOrNull() ?: 0
        })
        .registerTypeAdapter(Int::class.javaObjectType, JsonDeserializer { json, _, _ ->
            if (json == null || json.isJsonNull) null
            else runCatching { json.asJsonPrimitive.asString.trim().toDoubleOrNull()?.toInt() }.getOrNull()
        })
        .create()

    private val api: XtreamApiService = Retrofit.Builder()
        .baseUrl("http://localhost/") // sobrescrito por @Url em cada chamada
        .client(client)
        .build()
        .create(XtreamApiService::class.java)

    private fun normalizeBase(serverUrl: String): String =
        serverUrl.trimEnd('/')

    /** Busca a URL e devolve o corpo CRU já validado -- não deixa o Retrofit
     * tentar converter pra Gson sozinho (isso escondia o erro real atrás de
     * uma mensagem genérica tipo "unexpected end of stream" sempre que o
     * corpo vinha vazio ou diferente do esperado). Com isso, qualquer
     * problema (servidor fora do ar, usuário/senha errados, resposta que
     * não é JSON) aparece com uma mensagem que mostra o que aconteceu de
     * verdade. */
    private suspend fun fetchBody(url: String): String {
        val response = api.call(url)
        if (!response.isSuccessful) {
            error("O servidor respondeu com erro HTTP ${response.code()} para esta chamada.")
        }
        val body = response.body()?.string()?.trim().orEmpty()
        if (body.isEmpty()) {
            error("O servidor respondeu vazio. Confira se o usuário/senha/servidor da playlist estão corretos.")
        }
        return body
    }

    private inline fun <reified T> parseJson(body: String): T = try {
        gson.fromJson(body, T::class.java)
    } catch (e: Exception) {
        error("A resposta do servidor não é um JSON válido (início: \"${body.take(120)}\").")
    }

    private fun <T> parseJsonList(body: String, type: java.lang.reflect.Type): T = try {
        gson.fromJson(body, type)
    } catch (e: Exception) {
        error("A resposta do servidor não é um JSON válido (início: \"${body.take(120)}\").")
    }

    suspend fun login(session: Session): Result<AuthResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}"
        val body: AuthResponse = parseJson(fetchBody(url))
        if (body.userInfo?.auth != 1) error("Usuário ou senha inválidos")
        body
    }

    suspend fun getLiveCategories(session: Session): Result<List<Category>> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_live_categories"
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    suspend fun getLiveStreams(session: Session, categoryId: String?): Result<List<LiveStream>> = runCatching {
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_live_streams$catParam"
        val type = object : TypeToken<List<LiveStream>>() {}.type
        parseJsonList(fetchBody(url), type)
    }

    suspend fun getShortEpg(session: Session, streamId: Int): Result<ShortEpgResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_short_epg&stream_id=$streamId&limit=20"
        parseJson<ShortEpgResponse>(fetchBody(url))
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
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    suspend fun getVodStreams(session: Session, categoryId: String?): Result<List<VodStream>> = runCatching {
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_vod_streams$catParam"
        val type = object : TypeToken<List<VodStream>>() {}.type
        parseJsonList(fetchBody(url), type)
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
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    suspend fun getSeries(session: Session, categoryId: String?): Result<List<SeriesItem>> = runCatching {
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series$catParam"
        val type = object : TypeToken<List<SeriesItem>>() {}.type
        parseJsonList(fetchBody(url), type)
    }

    suspend fun getSeriesInfo(session: Session, seriesId: Int): Result<SeriesInfoResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series_info&series_id=$seriesId"
        parseJson<SeriesInfoResponse>(fetchBody(url))
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
