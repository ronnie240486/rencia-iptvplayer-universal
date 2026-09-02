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

    // Muitos paineis Xtream (PHP/Apache simples) fecham a conexao de um
    // jeito que confunde negociacao HTTP/2 e faz o OkHttp achar que o corpo
    // foi cortado no meio ("unexpected end of stream") mesmo quando o
    // servidor mandou tudo certo -- forcar HTTP/1.1 (mais tolerante a esse
    // tipo de servidor) e permitir nova tentativa em falha de conexao
    // resolve a grande maioria desses casos. Alem disso, muitos paineis
    // IPTV bloqueiam ou redirecionam requisicoes que nao mandam um
    // User-Agent "reconhecido" (aceitam VLC, players de TV, navegador --
    // mas rejeitam o User-Agent padrao do OkHttp) -- por isso forcamos um
    // User-Agent de navegador comum em toda chamada.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
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

    private suspend fun fetchBody(url: String): String {
        val response = api.call(url)
        if (!response.isSuccessful) {
            error("O servidor respondeu com erro HTTP ${response.code()} para esta chamada.")
        }
        val body = try {
            response.body()?.string()?.trim().orEmpty()
        } catch (e: Exception) {
            runCatching {
                val retryResponse = api.call(url)
                retryResponse.body()?.string()?.trim().orEmpty()
            }.getOrNull() ?: error(
                "A conexão com o servidor foi cortada antes de terminar de responder. " +
                    "Isso costuma ser instabilidade do próprio painel/servidor -- tente de novo em alguns segundos."
            )
        }
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

    // Cache em memória da playlist M3U já baixada e interpretada (evita
    // baixar/reprocessar a lista inteira de novo a cada categoria que o
    // usuário clica).
    private val m3uCache = mutableMapOf<String, List<M3uParser.ParsedChannel>>()

    // Cache das séries montadas a partir de M3U, pra "lembrar" categoria +
    // nome da série a partir do ID fake quando o usuário abre os episódios
    // (getSeriesInfo só recebe o ID, não a categoria).
    private val m3uSeriesLookup = mutableMapOf<Int, Pair<String, String>>() // seriesId -> (categoria, nome)

    /** Baixa a playlist M3U completa reportando o progresso de verdade
     * (bytes já baixados / total), usado na tela de ativação por MAC pra
     * mostrar uma barra de progresso com porcentagem em vez de um
     * "carregando" indefinido -- e já deixa em cache, então as telas
     * seguintes (Canais/Filmes/Séries) abrem na hora, sem precisar baixar
     * de novo. */
    suspend fun preloadPlaylistWithProgress(
        session: Session,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<Unit> = kotlin.runCatching {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() } ?: return@runCatching
        m3uCache[playlistUrl]?.let { return@runCatching }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = okhttp3.Request.Builder().url(playlistUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                error("O servidor respondeu com erro HTTP ${response.code} ao baixar a lista.")
            }
            val body = response.body ?: run {
                response.close()
                error("O servidor respondeu vazio ao baixar a lista.")
            }
            val totalBytes = body.contentLength().coerceAtLeast(0)
            val source = body.source()
            val buffer = okio.Buffer()
            var bytesRead = 0L
            val chunkSize = 32 * 1024L
            while (true) {
                val read = source.read(buffer, chunkSize)
                if (read == -1L) break
                bytesRead += read
                onProgress(bytesRead, totalBytes)
            }
            val text = buffer.readString(Charsets.UTF_8)
            response.close()

            val parsed = M3uParser.parse(text)
            if (parsed.isNotEmpty()) {
                m3uCache[playlistUrl] = parsed
            }
        }
    }

    private suspend fun fetchM3uChannels(session: Session): List<M3uParser.ParsedChannel> {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() }
            ?: error("Esta sessão não tem uma playlist M3U para usar.")
        m3uCache[playlistUrl]?.let { return it }
        val body = fetchBody(playlistUrl)
        val parsed = M3uParser.parse(body)
        if (parsed.isEmpty()) {
            error("A playlist M3U não contém nenhum canal reconhecível (recebido: \"${body.take(150).replace("\n", " ")}\").")
        }
        m3uCache[playlistUrl] = parsed
        return parsed
    }

    suspend fun login(session: Session): Result<AuthResponse> = runCatching {
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}"
        val body: AuthResponse = parseJson(fetchBody(url))
        if (body.userInfo?.auth != 1) error("Usuário ou senha inválidos")
        body
    }

    /** Muitos paineis mais simples (principalmente os que só vendem
     * playlist, sem revenda "de verdade") NÃO tem o player_api.php
     * funcionando -- só o get.php (M3U) mesmo. Nesses casos, tentar a API
     * primeiro só atrasa e ainda pode confundir o usuário com um erro que
     * não é o problema real. Quando a sessão já tem uma playlist M3U
     * salva, ela é tentada PRIMEIRO -- só cai pra API se a M3U falhar. */
    suspend fun getLiveCategories(session: Session): Result<List<Category>> = runCatching {
        if (!session.playlistUrl.isNullOrBlank()) {
            val m3uResult = runCatching { M3uParser.toLiveCategories(fetchM3uChannels(session)) }
            m3uResult.getOrNull()?.let { if (it.isNotEmpty()) return@runCatching it }
        }
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_live_categories"
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    suspend fun getLiveStreams(session: Session, categoryId: String?): Result<List<LiveStream>> = runCatching {
        if (!session.playlistUrl.isNullOrBlank()) {
            val m3uResult = runCatching {
                val channels = fetchM3uChannels(session)
                val targetCategory = categoryId ?: M3uParser.toLiveCategories(channels).firstOrNull()?.categoryId
                if (targetCategory == null) emptyList() else M3uParser.toLiveStreamsFiltered(channels, targetCategory)
            }
            m3uResult.getOrNull()?.let { return@runCatching it }
        }
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_live_streams$catParam"
        val type = object : TypeToken<List<LiveStream>>() {}.type
        parseJsonList<List<LiveStream>>(fetchBody(url), type)
    }

    suspend fun getShortEpg(session: Session, streamId: Int): Result<ShortEpgResponse> = runCatching {
        if (streamId == 0) error("Este canal não tem programação disponível (veio de uma playlist M3U simples, sem EPG).")
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
        if (!session.playlistUrl.isNullOrBlank()) {
            val m3uResult = runCatching { M3uParser.toVodCategories(fetchM3uChannels(session)) }
            m3uResult.getOrNull()?.let { if (it.isNotEmpty()) return@runCatching it }
        }
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_vod_categories"
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    suspend fun getVodStreams(session: Session, categoryId: String?): Result<List<VodStream>> = runCatching {
        if (!session.playlistUrl.isNullOrBlank()) {
            val m3uResult = runCatching {
                val channels = fetchM3uChannels(session)
                val targetCategory = categoryId ?: M3uParser.toVodCategories(channels).firstOrNull()?.categoryId
                if (targetCategory == null) emptyList() else M3uParser.toVodStreams(channels, targetCategory)
            }
            m3uResult.getOrNull()?.let { return@runCatching it }
        }
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_vod_streams$catParam"
        val type = object : TypeToken<List<VodStream>>() {}.type
        parseJsonList<List<VodStream>>(fetchBody(url), type)
    }

    /** Monta a URL de reprodução de um filme (VOD). */
    fun buildVodStreamUrl(session: Session, streamId: Int, containerExtension: String?): String {
        val base = normalizeBase(session.serverUrl)
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$base/movie/${session.username}/${session.password}/$streamId.$ext"
    }

    suspend fun getSeriesCategories(session: Session): Result<List<Category>> = runCatching {
        if (!session.playlistUrl.isNullOrBlank()) {
            val m3uResult = runCatching { M3uParser.toSeriesCategories(fetchM3uChannels(session)) }
            m3uResult.getOrNull()?.let { if (it.isNotEmpty()) return@runCatching it }
        }
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series_categories"
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    /** Séries vindas de M3U são agrupadas pelo nome (removendo o SxxExx do
     * final) -- é o mesmo jeito que outros apps de IPTV leem séries numa
     * playlist M3U simples, já que esse formato não separa formalmente
     * série / temporada / episódio como a API Xtream faz. */
    suspend fun getSeries(session: Session, categoryId: String?): Result<List<SeriesItem>> = runCatching {
        if (!session.playlistUrl.isNullOrBlank() && categoryId != null) {
            val m3uResult = runCatching {
                val channels = fetchM3uChannels(session)
                val shows = M3uParser.toSeriesShows(channels, categoryId)
                shows.forEach { m3uSeriesLookup[it.seriesId] = categoryId to it.name }
                shows
            }
            m3uResult.getOrNull()?.let { if (it.isNotEmpty()) return@runCatching it }
        }
        val catParam = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series$catParam"
        val type = object : TypeToken<List<SeriesItem>>() {}.type
        parseJsonList(fetchBody(url), type)
    }

    suspend fun getSeriesInfo(session: Session, seriesId: Int): Result<SeriesInfoResponse> = runCatching {
        m3uSeriesLookup[seriesId]?.let { (categoryName, showName) ->
            val channels = fetchM3uChannels(session)
            return@runCatching M3uParser.toSeriesInfo(channels, categoryName, showName)
        }
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
