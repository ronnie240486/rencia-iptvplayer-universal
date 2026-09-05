package com.meuapp.iptvplayer.data.api

import android.content.Context
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
import java.io.File
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

class XtreamRepository(context: Context? = null) {

    // Companion object -- essas caches precisam ser COMPARTILHADAS entre
    // todas as telas do app, não por instância. Antes, cada tela
    // (LoginActivity, ChannelListActivity, VodActivity, SeriesActivity...)
    // criava seu próprio "XtreamRepository()" com seu próprio cache vazio
    // -- então a lista baixada com tanto cuidado (barra de progresso) na
    // tela do MAC era jogada fora assim que o usuário saía dali, e cada
    // tela seguinte baixava tudo de novo do zero. Por isso demorava tanto
    // pra abrir canais/filmes mesmo "depois de já ter carregado".
    companion object {
        private val m3uCache = mutableMapOf<String, List<M3uParser.ParsedChannel>>()
        private val m3uSeriesLookup = mutableMapOf<Int, Pair<String, String>>() // seriesId -> (categoria, nome)
        // URL do guia XMLTV encontrada dentro da própria playlist M3U
        // (tag url-tvg/x-tvg-url) -- null explícito significa "já procurou
        // e não tem" (evita ficar checando de novo sem necessidade).
        private val epgUrlCache = mutableMapOf<String, String?>() // playlistUrl -> epgUrl
        // Contexto do app (não da Activity) -- guardado uma vez, usado só
        // pra ler/escrever o cache em DISCO da playlist M3U, que sobrevive
        // fechar e abrir o app de novo (o cache em memória acima não
        // sobrevive, some quando o processo do app é encerrado).
        private var appContext: Context? = null
        private val xmlTvCache = mutableMapOf<String, Map<String, List<XmlTvProgramme>>>() // epgUrl -> programação por canal
    }

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

    init {
        if (appContext == null && context != null) {
            appContext = context.applicationContext
        }
    }

    /** Apaga o cache (memória e disco) de uma playlist específica -- usado
     * pelo botão "Atualizar conteúdo" em Ajustes, pra forçar buscar tudo
     * de novo em vez de continuar usando a lista antiga guardada. */
    fun clearM3uCache(playlistUrl: String?) {
        if (playlistUrl.isNullOrBlank()) return
        m3uCache.remove(playlistUrl)
        epgUrlCache.remove(playlistUrl)
        runCatching { m3uCacheFile(playlistUrl)?.delete() }
    }

    private data class CachedPlaylistData(
        val channels: List<M3uParser.ParsedChannel>,
        val epgUrl: String?
    )

    /** Lê o cache já PROCESSADO (não o texto M3U bruto) -- ler do disco e
     * so DESSERIALIZAR é rápido; o que demorava de verdade (até 40s numa
     * lista grande) era reprocessar o texto inteiro com regex de novo TODA
     * VEZ que abria o app, mesmo já tendo processado tudo antes. Agora só
     * processa (parse) uma vez: no primeiro download. Depois disso, só
     * lê o resultado já pronto. */
    private fun readParsedCache(playlistUrl: String): CachedPlaylistData? {
        val file = m3uCacheFile(playlistUrl)?.takeIf { it.exists() } ?: return null
        val json = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { gson.fromJson(json, CachedPlaylistData::class.java) }.getOrNull()
    }

    private fun writeParsedCache(playlistUrl: String, data: CachedPlaylistData) {
        val file = m3uCacheFile(playlistUrl) ?: return
        runCatching { writeCacheFileSafely(file, gson.toJson(data)) }
    }

    private fun m3uCacheFile(playlistUrl: String): File? {
        val dir = appContext?.cacheDir ?: return null
        return File(dir, "m3u_cache_${kotlin.math.abs(playlistUrl.hashCode())}.m3u")
    }

    /** Grava em disco de um jeito seguro contra o app ser morto no meio da
     * escrita (ex: usuário apertou "OK" numa mensagem de "não está
     * respondendo" bem nessa hora) -- escreve num arquivo temporário
     * primeiro e só troca pelo definitivo quando termina de escrever tudo.
     * Sem isso, um arquivo cortado pela metade virava um cache "válido mas
     * vazio/quebrado" que nunca mais carregava nada até limpar os dados
     * do app manualmente. */
    private fun writeCacheFileSafely(file: File, content: String) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(content)
        tempFile.renameTo(file)
    }

    /** Checagem rápida (só olha se o arquivo existe, não lê nem processa
     * nada) -- usado pra decidir se pula a tela de carregamento inteira e
     * entra direto, sem mostrar barra de progresso nenhuma, quando já tem
     * a lista guardada de uma sessão anterior. */
    fun hasCachedPlaylist(session: Session): Boolean {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() } ?: return false
        if (m3uCache.containsKey(playlistUrl)) return true
        return m3uCacheFile(playlistUrl)?.exists() == true
    }

    private fun normalizeBase(serverUrl: String): String =
        serverUrl.trimEnd('/')

    private suspend fun fetchBody(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
        body
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

        // Cache já PROCESSADO (não o texto bruto) -- ler e desserializar é
        // rápido, bem diferente de reprocessar o texto inteiro com regex
        // de novo (que chegava a levar dezenas de segundos numa lista
        // grande, mesmo já tendo sido processada antes).
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            readParsedCache(playlistUrl)
        }
        if (cached != null && cached.channels.isNotEmpty()) {
            m3uCache[playlistUrl] = cached.channels
            epgUrlCache[playlistUrl] = cached.epgUrl
            onProgress(1, 1)
            return@runCatching
        }

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

            epgUrlCache[playlistUrl] = M3uParser.extractEpgUrl(text)
            val parsed = M3uParser.parse(text)
            if (parsed.isNotEmpty()) {
                m3uCache[playlistUrl] = parsed
                writeParsedCache(playlistUrl, CachedPlaylistData(parsed, epgUrlCache[playlistUrl]))
            }
        }
    }

    private suspend fun fetchM3uChannels(session: Session): List<M3uParser.ParsedChannel> {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() }
            ?: error("Esta sessão não tem uma playlist M3U para usar.")
        m3uCache[playlistUrl]?.let { return it }

        // Cache já PROCESSADO (não o texto bruto) -- ler e desserializar é
        // rápido; reprocessar o texto inteiro de novo (regex em milhares
        // de linhas) é que demorava até 40s numa lista grande, mesmo já
        // tendo sido processado antes.
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            readParsedCache(playlistUrl)
        }
        if (cached != null && cached.channels.isNotEmpty()) {
            m3uCache[playlistUrl] = cached.channels
            epgUrlCache[playlistUrl] = cached.epgUrl
            return cached.channels
        }

        val body = fetchBody(playlistUrl)
        val epgUrl = M3uParser.extractEpgUrl(body)
        epgUrlCache[playlistUrl] = epgUrl
        val parsed = M3uParser.parse(body)
        if (parsed.isEmpty()) {
            error("A playlist M3U não contém nenhum canal reconhecível (recebido: \"${body.take(150).replace("\n", " ")}\").")
        }
        m3uCache[playlistUrl] = parsed
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            writeParsedCache(playlistUrl, CachedPlaylistData(parsed, epgUrl))
        }
        return parsed
    }

    /** Busca e interpreta o guia XMLTV referenciado na própria playlist M3U
     * (tag url-tvg/x-tvg-url) -- é assim que a maioria dos apps de IPTV
     * mostra a programação real ("Jornal Nacional agora, novela depois")
     * mesmo em painéis sem API Xtream. Baixa e processa só uma vez por
     * sessão (fica em cache), e só guarda os canais que realmente existem
     * na playlist, pra não gastar memória com um guia inteiro à toa. */
    private suspend fun fetchXmlTvGuide(session: Session): Map<String, List<XmlTvProgramme>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() } ?: return@withContext emptyMap()
        val channels = runCatching { fetchM3uChannels(session) }.getOrNull() ?: return@withContext emptyMap()
        // Compara tvg-id sem diferenciar maiúscula/minúscula -- é comum o
        // painel mandar "EPTV.Campinas" na playlist M3U e o guia XMLTV usar
        // "eptv.campinas" (ou vice-versa); sem isso, o casamento falhava
        // silenciosamente mesmo quando os dois IDs eram "o mesmo canal".
        val tvgIds = channels.mapNotNull { it.tvgId?.lowercase() }.toSet()
        // Nomes normalizados dos canais da playlist -- usado como PLANO B
        // quando o tvg-id não bate com nada no guia (muito comum com guias
        // "universais" de terceiros, que usam seu próprio jeito de nomear
        // os canais, diferente do tvg-id que o provedor do usuário usa).
        val normalizedNames = channels.map { M3uParser.stripQualitySuffixPublic(it.name) }
            .map { XmlTvParser.normalizeChannelName(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (tvgIds.isEmpty() && normalizedNames.isEmpty()) return@withContext emptyMap()

        // 1) URL declarada no cabeçalho da própria playlist M3U (padrão
        //    mais comum). 2) Se não tiver, painéis Xtream Codes quase
        //    sempre também expõem o guia num endereço fixo (xmltv.php),
        //    mesmo sem avisar isso na playlist. 3) Por último, tenta um
        //    guia universal de canais brasileiros (iptv-epg.org) -- cobre
        //    canais comuns quando nem a playlist nem o painel têm guia
        //    próprio nenhum.
        val declaredUrl = epgUrlCache[playlistUrl]
        val fallbackUrl = "${normalizeBase(session.serverUrl)}/xmltv.php?username=${session.username}&password=${session.password}"
        val universalFallbackUrl = "http://iptv-epg.org/files/epg-br.xml"
        val candidates = listOfNotNull(declaredUrl, fallbackUrl, universalFallbackUrl).distinct()

        for (epgUrl in candidates) {
            // IMPORTANTE: guarda em cache mesmo quando o resultado vem
            // vazio (sem canal nenhum batendo) -- sem isso, toda vez que
            // trocava de canal/categoria, tentava baixar os 3 endereços de
            // guia de novo do ZERO (incluindo o arquivo grande do guia
            // universal), mesmo já sabendo que nenhum deles tinha dado
            // certo antes. Isso sozinho já causava a demora de vários
            // segundos ao abrir categoria/canal. Só pula pro próximo
            // candidato quando o cache diz "vazio" -- se tiver dado de
            // verdade, usa na hora.
            val alreadyChecked = xmlTvCache[epgUrl]
            if (alreadyChecked != null) {
                if (alreadyChecked.isNotEmpty()) return@withContext alreadyChecked
                continue
            }
            val xml = runCatching { fetchBody(epgUrl) }.getOrNull()
            if (xml == null) {
                // Falha de rede de verdade (não "sem dados") -- não guarda
                // em cache, vale tentar de novo na próxima vez.
                continue
            }

            // Primeiro descobre que IDs o guia usa pra cada canal (lendo só
            // os nomes, rápido) -- resolve pelo tvg-id direto OU pelo nome
            // do canal batendo (normalizado), o que der certo primeiro.
            // Guarda um mapa de volta (id do guia -> nossa chave de busca)
            // pra depois conseguir procurar usando o mesmo tvg-id/nome que
            // o resto do app já usa.
            val guideNameToId = runCatching { XmlTvParser.parseChannelNames(xml) }.getOrDefault(emptyMap())
            val relevantGuideIds = mutableSetOf<String>()
            val guideIdToOurKey = mutableMapOf<String, String>()
            tvgIds.forEach { id ->
                relevantGuideIds.add(id)
                guideIdToOurKey[id] = id
            }
            normalizedNames.forEach { normName ->
                guideNameToId[normName]?.let { guideId ->
                    val guideIdLower = guideId.lowercase()
                    relevantGuideIds.add(guideIdLower)
                    guideIdToOurKey[guideIdLower] = normName
                }
            }

            val parsedByGuideId = runCatching { XmlTvParser.parse(xml, relevantGuideIds) }.getOrDefault(emptyMap())
            val remapped = mutableMapOf<String, List<XmlTvProgramme>>()
            parsedByGuideId.forEach { (guideId, programmes) ->
                remapped[guideIdToOurKey[guideId] ?: guideId] = programmes
            }

            xmlTvCache[epgUrl] = remapped
            if (remapped.isNotEmpty()) {
                epgUrlCache[playlistUrl] = epgUrl
                return@withContext remapped
            }
        }
        return@withContext emptyMap()
    }

    /** Programação (agora + próximos) de UM canal específico, lida do guia
     * XMLTV da playlist -- usado quando o canal veio de M3U (sem stream_id
     * de verdade pra usar o get_short_epg da API Xtream). Tenta primeiro
     * pelo tvg-id (mais preciso); se não achar nada, tenta pelo NOME do
     * canal (mais tolerante a guias de terceiros que nomeiam diferente). */
    suspend fun getEpgFromPlaylist(session: Session, tvgId: String?, channelName: String? = null): Result<List<XmlTvProgramme>> = runCatching {
        if (tvgId.isNullOrBlank() && channelName.isNullOrBlank()) return@runCatching emptyList()
        val guide = fetchXmlTvGuide(session)
        val now = System.currentTimeMillis()
        val byId = tvgId?.lowercase()?.let { guide[it] }
        val byName = if (byId.isNullOrEmpty() && !channelName.isNullOrBlank()) {
            guide[XmlTvParser.normalizeChannelName(M3uParser.stripQualitySuffixPublic(channelName))]
        } else null
        (byId ?: byName).orEmpty()
            .filter { it.stopMillis >= now }
            .sortedBy { it.startMillis }
            .take(6)
    }

    data class EpgDiagnostic(
        val hasTvgId: Boolean,
        val hasEpgUrlDeclared: Boolean,
        val epgUrl: String?,
        val guideChannelCount: Int,
        val hasMatchForThisChannel: Boolean,
        val searchedTvgId: String? = null,
        val searchedNormalizedName: String? = null
    )

    /** Descobre exatamente ONDE a busca de programação está parando --
     * usado só pra diagnóstico (ex: mostrar uma mensagem mais específica
     * do que "não disponível" quando o EPG não aparece). */
    suspend fun diagnoseEpg(session: Session, tvgId: String?, channelName: String? = null): EpgDiagnostic = runCatching {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() }
        // Chama fetchXmlTvGuide PRIMEIRO -- se conseguir usando o endereço
        // padrão xmltv.php (mesmo sem a playlist declarar isso), o cache
        // já fica atualizado com a URL que funcionou de verdade.
        val guide = if (playlistUrl != null) fetchXmlTvGuide(session) else emptyMap()
        val epgUrl = playlistUrl?.let { epgUrlCache[it] }
        val normalizedName = channelName?.let { XmlTvParser.normalizeChannelName(M3uParser.stripQualitySuffixPublic(it)) }
        val matchById = tvgId != null && guide.containsKey(tvgId.lowercase())
        val matchByName = !matchById && !normalizedName.isNullOrBlank() && guide.containsKey(normalizedName)
        EpgDiagnostic(
            hasTvgId = !tvgId.isNullOrBlank(),
            hasEpgUrlDeclared = epgUrl != null,
            epgUrl = epgUrl,
            guideChannelCount = guide.size,
            hasMatchForThisChannel = matchById || matchByName,
            searchedTvgId = tvgId,
            searchedNormalizedName = normalizedName
        )
    }.getOrDefault(EpgDiagnostic(false, false, null, 0, false))

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

    /** Outros links (qualidade/backup) do mesmo canal, pra tentar
     * automaticamente se o principal falhar -- só funciona pra canais
     * vindos de playlist M3U (onde dá pra comparar nome/categoria). */
    suspend fun getFailoverUrls(session: Session, categoryId: String?, channelName: String, excludeUrl: String): List<String> {
        if (session.playlistUrl.isNullOrBlank() || categoryId == null) return emptyList()
        return runCatching {
            val channels = fetchM3uChannels(session)
            M3uParser.siblingStreamUrls(channels, categoryId, channelName).filterNot { it == excludeUrl }
        }.getOrDefault(emptyList())
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

    /** Testa a conexão de uma lista de canais (sem baixar o vídeo inteiro,
     * só confere se o servidor responde) -- usado pelo "Verificar lista"
     * em Ajustes, pra achar canais com problema sem precisar clicar um
     * por um. Roda em paralelo (poucos de cada vez, pra não sobrecarregar)
     * e informa o progresso conforme vai testando. */
    fun buildSeriesStreamUrl(
        session: Session,
        episodeId: String,
        containerExtension: String?
    ): String {
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "${normalizeBase(session.serverUrl)}/series/${session.username}/${session.password}/$episodeId.$ext"
    }

    data class SearchResults(
        val live: List<LiveStream>,
        val vod: List<VodStream>,
        val series: List<SeriesItem>
    )

    /** Busca única (canais + filmes + séries de uma vez), varrendo a
     * playlist M3U já em cache -- rápida, porque não baixa nada de novo.
     * Só funciona pra sessões que têm uma playlist M3U (a maioria dos
     * paineis mais simples); painéis puramente API teriam que buscar
     * categoria por categoria, o que seria lento demais pra uma busca. */
    suspend fun searchAll(session: Session, query: String): Result<SearchResults> = runCatching {
        if (query.isBlank()) return@runCatching SearchResults(emptyList(), emptyList(), emptyList())
        if (session.playlistUrl.isNullOrBlank()) {
            error("Busca disponível apenas para listas M3U por enquanto.")
        }
        val channels = fetchM3uChannels(session)
        val live = M3uParser.searchLive(channels, query).take(60).mapIndexed { index, c ->
            LiveStream(index + 1, c.name, 0, c.logoUrl, c.groupTitle, c.tvgId, c.streamUrl)
        }
        val vod = M3uParser.searchVod(channels, query).take(60).mapIndexed { index, c ->
            VodStream(index + 1, c.name, 0, c.logoUrl, c.groupTitle, null, null, c.streamUrl)
        }
        val series = M3uParser.searchSeriesShows(channels, query).take(60).map { (showName, c) ->
            val seriesId = kotlin.math.abs("${c.groupTitle}|$showName".hashCode()) % 1_000_000_000 + 100_000_000
            m3uSeriesLookup[seriesId] = c.groupTitle to showName
            SeriesItem(0, showName, seriesId, c.logoUrl, c.groupTitle, null, null)
        }
        SearchResults(live, vod, series)
    }
}
