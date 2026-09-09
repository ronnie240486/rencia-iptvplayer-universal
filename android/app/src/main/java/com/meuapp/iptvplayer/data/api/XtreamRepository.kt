package com.meuapp.iptvplayer.data.api

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    val playlistUrl: String? = null,
    val activeListNumber: Int = 1
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
        // Canais ao vivo já separados por categoria -- classificar cada
        // canal (é filme? série? ao vivo?) envolve verificações de regex
        // por canal, e refazer isso pra LISTA INTEIRA a cada categoria que
        // o usuário clica (em vez de só uma vez) era o que deixava trocar
        // de categoria lento/travado.
        private val liveStreamsByCategoryCache = mutableMapOf<String, MutableMap<String, List<LiveStream>>>() // cacheKey -> categoryId -> canais (montado SOB DEMANDA, só quando o usuário abre a categoria)
        // Mesma ideia acima, só que pra Filmes -- classificar
        // conteúdo (é filme? série? ao vivo?) é caro (regex por item), e
        // sem isso as telas de Filmes/Séries tinham o mesmo travamento ao
        // trocar de categoria que Canais tinha antes de corrigir.
        private val vodByCategoryCache = mutableMapOf<String, MutableMap<String, List<VodStream>>>() // idem, sob demanda
        // Séries agora TAMBÉM é sob demanda -- ficou seguro de fazer
        // porque a tabela seriesId -> (categoria, nome) que Favoritos
        // precisa (m3uSeriesLookup) passou a ser lida direto do cache em
        // disco (persistida uma vez no download), em vez de depender de
        // todas as categorias de série já terem sido montadas em memória.
        private val seriesByCategoryCache = mutableMapOf<String, MutableMap<String, List<SeriesItem>>>()
        // Índices (não os objetos montados) de Canais, Filmes e Séries por
        // categoria -- só os NOMES das categorias e quais posições da
        // lista pertencem a cada uma. Ler isso é praticamente instantâneo
        // (só chaves de mapa, nenhuma reconstrução de objeto); a lista de
        // exibição de verdade só é montada quando o usuário abre aquela
        // categoria específica (ver liveStreamsByCategoryCache/
        // vodByCategoryCache/seriesByCategoryCache acima).
        private val liveIndicesByCategoryCache = mutableMapOf<String, Map<String, List<Int>>>()
        private val vodIndicesByCategoryCache = mutableMapOf<String, Map<String, List<Int>>>()
        private val seriesIndicesByCategoryCache = mutableMapOf<String, Map<String, List<Int>>>()
        // ---- CAMINHO RÁPIDO (arquivos separados por tipo) ----
        // Mesmo depois de "montagem" virar sob demanda, abrir Canais ainda
        // lia o arquivo INTEIRO (Canais+Filmes+Séries juntos, ~270 mil
        // itens) só pra mostrar os nomes das categorias de Canais -- por
        // isso ainda levava uns 3s mesmo pra uma categoria pequena. Esses
        // caches guardam os canais e índices de CADA TIPO separadamente,
        // lidos de um arquivo PRÓPRIO (bem menor) em disco -- ver
        // ensureLiveFast/ensureVodFast/ensureSeriesFast. Se o arquivo
        // rápido de um tipo não existir ainda (primeira vez depois dessa
        // atualização, ou algo deu errado gravando), cai automaticamente
        // no caminho de sempre (fetchM3uChannels, arquivo unificado) --
        // então na pior das hipóteses fica do jeito que já funcionava,
        // nunca quebra.
        private val liveFastChannels = mutableMapOf<String, List<M3uParser.ParsedChannel>>()
        private val liveFastIndices = mutableMapOf<String, Map<String, List<Int>>>()
        private val vodFastChannels = mutableMapOf<String, List<M3uParser.ParsedChannel>>()
        private val vodFastIndices = mutableMapOf<String, Map<String, List<Int>>>()
        private val seriesFastChannels = mutableMapOf<String, List<M3uParser.ParsedChannel>>()
        private val seriesFastIndices = mutableMapOf<String, Map<String, List<Int>>>()
        // Trava por conta (cacheKey) -- a Home dispara prefetchEpgGuide()
        // sozinha, em segundo plano, assim que abre. Se o usuário entra em
        // Canais/Filmes/Séries logo em seguida, ISSO também chama
        // fetchM3uChannels() -- sem essa trava, as duas chamadas podiam
        // rodar ao mesmo tempo em threads DIFERENTES (Dispatchers.IO usa
        // várias threads), cada uma lendo/montando o cache e escrevendo
        // nos MESMOS mapas ao mesmo tempo -- o que pode corromper o
        // resultado ou lançar uma exceção (silenciosamente engolida por
        // runCatching lá em cima), fazendo a tela ficar esperando pra
        // sempre sem erro nenhum aparecer. Com a trava, a segunda chamada
        // espera a primeira terminar e reaproveita o resultado, em vez de
        // competir por ele.
        private val fetchMutexes = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()
        private fun mutexFor(key: String): kotlinx.coroutines.sync.Mutex =
            synchronized(fetchMutexes) { fetchMutexes.getOrPut(key) { kotlinx.coroutines.sync.Mutex() } }
        // DIAGNÓSTICO TEMPORÁRIO: guarda quanto tempo cada etapa do
        // carregamento do cache levou da ÚLTIMA vez -- pra descobrir se o
        // gasto real é ler/desserializar o arquivo (JSON grande) ou montar
        // os agrupamentos (Canais/Filmes/Séries por categoria). Sem isso,
        // só dá pra adivinhar onde está a demora. Será removido assim que
        // o gargalo real for identificado.
        @Volatile
        var lastLoadTiming: String? = null
            private set
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

    /** Chave de cache ESTÁVEL pra cada conta -- usa o MAC (que não muda
     * nunca pro mesmo aparelho/conta) em vez do endereço da playlist em
     * si. Alguns painéis devolvem uma URL de playlist levemente diferente
     * a cada verificação (ex: com um token de sessão embutido) -- usando
     * a URL como chave, isso fazia o cache "não bater" e forçar
     * baixar/processar tudo de novo mesmo sem nada ter mudado de
     * verdade, mesmo fechando e abrindo o MESMO app sem reinstalar nada. */
    private fun cacheKeyFor(session: Session): String =
        session.mac.trim().ifBlank { session.playlistUrl.orEmpty() }

    /** Apaga o cache (memória e disco) de uma sessão -- usado pelo botão
     * "Atualizar conteúdo" em Ajustes, pra forçar buscar tudo de novo em
     * vez de continuar usando a lista antiga guardada. */
    fun clearM3uCache(session: Session) {
        val key = cacheKeyFor(session).ifBlank { return }
        m3uCache.remove(key)
        epgUrlCache.remove(key)
        // Preexistente: essa função já não limpava os caches por categoria
        // antes -- ficavam "presos" com dados da lista ANTIGA depois de
        // "Atualizar conteúdo" trocar de lista. Agora mais importante
        // ainda, já que os índices de Canais/Filmes precisam ficar
        // sincronizados com a lista de canais correta.
        liveIndicesByCategoryCache.remove(key)
        vodIndicesByCategoryCache.remove(key)
        seriesIndicesByCategoryCache.remove(key)
        liveStreamsByCategoryCache.remove(key)
        vodByCategoryCache.remove(key)
        seriesByCategoryCache.remove(key)
        // Caminho RÁPIDO (arquivos por tipo) -- mesmo motivo acima: sem
        // limpar isso, Canais/Filmes/Séries continuariam mostrando dados
        // da lista ANTIGA (arquivo separado) mesmo depois de trocar de
        // lista em "Atualizar conteúdo".
        liveFastChannels.remove(key)
        liveFastIndices.remove(key)
        vodFastChannels.remove(key)
        vodFastIndices.remove(key)
        seriesFastChannels.remove(key)
        seriesFastIndices.remove(key)
        runCatching { typedCacheFile(key, "live")?.delete() }
        runCatching { typedCacheFile(key, "vod")?.delete() }
        runCatching { typedCacheFile(key, "series")?.delete() }
        runCatching { m3uCacheFile(key)?.delete() }
    }

    private data class CachedPlaylistData(
        val channels: List<M3uParser.ParsedChannel>,
        val epgUrl: String?,
        // Guarda só os ÍNDICES dos canais de cada categoria (não os
        // objetos LiveStream/VodStream/SeriesItem inteiros, duplicados) --
        // a primeira versão dessa correção guardava os objetos completos
        // de novo aqui, o que quase DOBRAVA o tamanho do arquivo e fazia a
        // gravação estourar a memória (OutOfMemoryError) numa lista
        // grande, fazendo o cache NUNCA ser salvo de verdade. Reconstruir
        // os objetos a partir do índice é rápido (não precisa de regex),
        // só a CLASSIFICAÇÃO (saber quem é o quê) que era cara.
        val liveIndicesByCategory: Map<String, List<Int>>? = null,
        val vodIndicesByCategory: Map<String, List<Int>>? = null,
        val seriesIndicesByCategory: Map<String, List<Int>>? = null,
        // Tabela leve (seriesId -> categoria + nome da série) calculada
        // uma vez só (no download) e salva em disco -- é o que permite
        // Séries também ser montada SOB DEMANDA (por categoria) sem
        // quebrar Favoritos: abrir uma série favoritada direto (sem
        // passar pela tela de categorias) só precisa saber em qual
        // categoria/nome ela está pra buscar os episódios, não precisa
        // ter montado a lista de exibição inteira daquela categoria.
        val seriesLookup: Map<Int, Pair<String, String>>? = null
    )

    /** Classifica os canais (índice de cada um -> categoria) de uma vez --
     * bem mais leve que guardar os objetos LiveStream/VodStream/SeriesItem
     * inteiros duplicados. */
    private fun classifyIndices(channels: List<M3uParser.ParsedChannel>): Triple<Map<String, List<Int>>, Map<String, List<Int>>, Map<String, List<Int>>> {
        val liveIndices = mutableMapOf<String, MutableList<Int>>()
        val vodIndices = mutableMapOf<String, MutableList<Int>>()
        val seriesIndices = mutableMapOf<String, MutableList<Int>>()
        channels.forEachIndexed { index, channel ->
            when (M3uParser.contentKindPublic(channel)) {
                "live" -> liveIndices.getOrPut(channel.groupTitle) { mutableListOf() }.add(index)
                "vod" -> vodIndices.getOrPut(channel.groupTitle) { mutableListOf() }.add(index)
                "series" -> seriesIndices.getOrPut(channel.groupTitle) { mutableListOf() }.add(index)
            }
        }
        return Triple(liveIndices, vodIndices, seriesIndices)
    }

    /** Reconstrói os agrupamentos de verdade (LiveStream/VodStream/
     * SeriesItem por categoria) a partir dos ÍNDICES já classificados --
     * rápido (sem regex nenhuma pra Canais/Filmes). Usada só na hora do
     * DOWNLOAD (ou upgrade de cache antigo) pra Séries, uma vez só, pra
     * conseguir derivar a tabela leve de seriesLookup (ver comentário de
     * CachedPlaylistData.seriesLookup) que fica salva em disco -- depois
     * disso, Séries também vira sob demanda (buildSeriesCategoryLazy). */
    private fun buildSeriesGroupsFromIndices(
        channels: List<M3uParser.ParsedChannel>,
        seriesIndices: Map<String, List<Int>>
    ): Map<String, List<SeriesItem>> =
        seriesIndices.mapValues { (categoryName, indices) ->
            M3uParser.toSeriesShowsFromSubset(indices.map { channels[it] }, categoryName)
        }

    // ---- Formato de cache em disco: texto simples, feito à mão -- SEM
    // Gson/JSON pra esse arquivo especificamente. Um teste real (painel
    // com 270.501 canais, arquivo de 68MB) mostrou ~17s só pra LER os
    // bytes do arquivo + ~5,5s pra o Gson (reflection) converter isso em
    // objetos -- toda vez que o app é reaberto do zero. JSON genérico com
    // Gson é conveniente, mas caro demais pra uma lista desse tamanho.
    // Um formato de texto simples (uma linha por canal, campos separados
    // por um caractere reservado) é MUITO mais rápido de ler/escrever
    // porque não tem reflection nem a árvore intermediária de JsonElement
    // que o Gson monta antes de converter pra objeto Kotlin.
    // V3: adiciona a seção de seriesLookup (ver CachedPlaylistData) --
    // um cache V2 salvo por um build anterior não bate com esse marcador
    // de versão, então é tratado como ausente (baixa de novo UMA vez e já
    // salva em V3 dali em diante), igual a migração V1(JSON)->V2 antes.
    // V4: mesma estrutura de arquivo do V3 -- só muda pra forçar uma
    // reclassificação (não é possível herdar de um cache já processado com
    // a lógica antiga de ao-vivo/filme/série, ver ajuste em
    // M3uParser.contentKind). Cache V3 de um build anterior não bate com
    // esse marcador, então é tratado como ausente -- baixa de novo UMA vez
    // e classifica com a lógica corrigida, igual as migrações anteriores.
    private val CACHE_FORMAT_VERSION = "SUPREMUS_CACHE_V4"
    private val FIELD_SEP = '\u0001'
    private val NULL_MARKER = "\u0000"

    private fun sanitizeField(value: String?): String {
        if (value == null) return NULL_MARKER
        // Tira qualquer ocorrência (bem rara) dos separadores do formato
        // de dentro do próprio valor -- sem isso, um nome de canal com
        // esse caractere quebraria a leitura da linha inteira. Só afeta
        // exibição nesse caso raríssimo, não afeta o link do stream.
        return value.replace(FIELD_SEP, ' ').replace('\n', ' ').replace('\r', ' ')
    }

    private fun readField(value: String): String? = if (value == NULL_MARKER) null else value

    /** Lê o cache já PROCESSADO (não o texto M3U bruto) -- ler do disco e
     * so DESSERIALIZAR é rápido; o que demorava de verdade (até 40s numa
     * lista grande) era reprocessar o texto inteiro com regex de novo TODA
     * VEZ que abria o app, mesmo já tendo processado tudo antes. Agora só
     * processa (parse) uma vez: no primeiro download. Depois disso, só
     * lê o resultado já pronto. */
    private fun readParsedCache(cacheKey: String): CachedPlaylistData? =
        m3uCacheFile(cacheKey)?.takeIf { it.exists() }?.let { readCachePlain(it) }

    /** DIAGNÓSTICO TEMPORÁRIO: resultado com os tempos de CADA etapa
     * separados -- ler as linhas do disco é uma coisa (I/O), montar os
     * objetos a partir delas é outra (CPU) -- sem medir separado, não dá
     * pra saber qual das duas é a real vilã quando o total demora mais do
     * que deveria. */
    private data class TimedCacheRead(
        val data: CachedPlaylistData?,
        val fileBytes: Long,
        val readMs: Long,
        val parseMs: Long
    )

    private fun readParsedCacheTimed(cacheKey: String): TimedCacheRead {
        val file = m3uCacheFile(cacheKey)?.takeIf { it.exists() }
            ?: return TimedCacheRead(null, -1L, 0L, 0L)
        val r0 = System.nanoTime()
        val lines = runCatching { file.bufferedReader().use { it.readLines() } }.getOrNull()
        val r1 = System.nanoTime()
        if (lines == null) return TimedCacheRead(null, file.length(), (r1 - r0) / 1_000_000, 0L)
        val data = runCatching { parseCacheLines(lines) }.getOrNull()
        val r2 = System.nanoTime()
        return TimedCacheRead(data, file.length(), (r1 - r0) / 1_000_000, (r2 - r1) / 1_000_000)
    }

    private fun readCachePlain(file: File): CachedPlaylistData? {
        val lines = runCatching { file.bufferedReader().use { it.readLines() } }.getOrNull() ?: return null
        return runCatching { parseCacheLines(lines) }.getOrNull()
    }

    /** Converte as linhas já lidas do arquivo de cache em CachedPlaylistData
     * -- puro processamento de texto (split por caractere, não regex),
     * sem reflection nenhuma. */
    private fun parseCacheLines(lines: List<String>): CachedPlaylistData? {
        if (lines.isEmpty() || lines[0] != CACHE_FORMAT_VERSION) return null
        var i = 1
        val epgUrl = readField(lines[i]); i++
        val channelCount = lines[i].toInt(); i++
        val channels = ArrayList<M3uParser.ParsedChannel>(channelCount)
        repeat(channelCount) {
            val parts = lines[i].split(FIELD_SEP)
            i++
            if (parts.size == 5) {
                channels.add(
                    M3uParser.ParsedChannel(
                        groupTitle = readField(parts[0]) ?: "Geral",
                        name = readField(parts[1]) ?: "",
                        logoUrl = readField(parts[2]),
                        streamUrl = readField(parts[3]) ?: "",
                        tvgId = readField(parts[4])
                    )
                )
            }
        }
        fun readIndexMap(): Map<String, List<Int>> {
            val count = lines[i].toInt(); i++
            val map = LinkedHashMap<String, List<Int>>(count)
            repeat(count) {
                val parts = lines[i].split(FIELD_SEP)
                i++
                if (parts.size == 2) {
                    val categoryName = readField(parts[0]) ?: return@repeat
                    val indices = if (parts[1].isEmpty()) emptyList() else parts[1].split(',').map { it.toInt() }
                    map[categoryName] = indices
                }
            }
            return map
        }
        val liveIdx = readIndexMap()
        val vodIdx = readIndexMap()
        val seriesIdx = readIndexMap()
        // Tabela seriesId -> (categoria, nome) -- usada por Favoritos pra
        // abrir uma série direto sem precisar montar a categoria inteira.
        val seriesLookupCount = lines[i].toInt(); i++
        val seriesLookup = LinkedHashMap<Int, Pair<String, String>>(seriesLookupCount)
        repeat(seriesLookupCount) {
            val parts = lines[i].split(FIELD_SEP)
            i++
            if (parts.size == 3) {
                val seriesId = parts[0].toIntOrNull()
                val categoryName = readField(parts[1])
                val showName = readField(parts[2])
                if (seriesId != null && categoryName != null && showName != null) {
                    seriesLookup[seriesId] = categoryName to showName
                }
            }
        }
        return CachedPlaylistData(channels, epgUrl, liveIdx, vodIdx, seriesIdx, seriesLookup)
    }

    private fun writeParsedCache(cacheKey: String, data: CachedPlaylistData) {
        val diagPrefs = appContext?.getSharedPreferences("supremus_cache_diag", Context.MODE_PRIVATE)
        val file = m3uCacheFile(cacheKey)
        if (file == null) {
            diagPrefs?.edit()?.putString("last_write_result", "m3uCacheFile retornou null (appContext ausente?)")?.apply()
            return
        }
        val result = runCatching { writeCachePlain(file, data) }
        val message = if (result.isFailure) {
            "Falha ao gravar cache: ${result.exceptionOrNull()?.javaClass?.simpleName}: ${result.exceptionOrNull()?.message}"
        } else if (!file.exists()) {
            "writeCachePlain não deu erro, mas o arquivo não existe depois (path=${file.absolutePath})"
        } else {
            "OK: gravado em ${file.absolutePath} (${file.length()} bytes)"
        }
        // Salva o resultado no DISCO (não só na memória) -- assim
        // sobrevive até a próxima abertura do app, quando dá pra checar o
        // que aconteceu na gravação anterior.
        diagPrefs?.edit()?.putString("last_write_result", message)?.apply()
    }

    private fun m3uCacheFile(cacheKey: String): File? {
        // filesDir (não cacheDir) de propósito -- cacheDir é uma pasta
        // "temporária" que o próprio Android pode limpar sozinho pra
        // liberar espaço, sem avisar o app (comum em aparelhos com
        // Android customizado, tipo MIUI/Xiaomi, ColorOS etc.). Isso
        // fazia a lista salva sumir sozinha entre uma abertura e outra,
        // forçando baixar tudo de novo mesmo sem o usuário nunca ter
        // pedido pra limpar nada. filesDir só é apagado se o usuário
        // desinstalar o app ou limpar os dados manualmente.
        val dir = appContext?.filesDir ?: return null
        return File(dir, "m3u_cache_${kotlin.math.abs(cacheKey.hashCode())}.m3u")
    }

    /** Grava em disco de um jeito seguro contra o app ser morto no meio da
     * escrita (ex: usuário apertou "OK" numa mensagem de "não está
     * respondendo" bem nessa hora) -- escreve num arquivo temporário
     * primeiro e só troca pelo definitivo quando termina de escrever tudo.
     * Sem isso, um arquivo cortado pela metade virava um cache "válido mas
     * vazio/quebrado" que nunca mais carregava nada até limpar os dados
     * do app manualmente.
     *
     * Escreve LINHA POR LINHA num BufferedWriter (não monta uma string
     * gigante inteira na memória antes de escrever, como gson.toJson
     * fazia) -- numa lista de 270 mil canais isso evita alocar dezenas de
     * MB extras só pra montar o texto antes de gravar. */
    private fun writeCachePlain(file: File, data: CachedPlaylistData) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.bufferedWriter().use { writer ->
            writer.write(CACHE_FORMAT_VERSION); writer.newLine()
            writer.write(sanitizeField(data.epgUrl)); writer.newLine()
            writer.write(data.channels.size.toString()); writer.newLine()
            for (channel in data.channels) {
                writer.write(sanitizeField(channel.groupTitle))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.name))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.logoUrl))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.streamUrl))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.tvgId))
                writer.newLine()
            }
            fun writeIndexMap(map: Map<String, List<Int>>?) {
                val safe = map.orEmpty()
                writer.write(safe.size.toString()); writer.newLine()
                for ((categoryName, indices) in safe) {
                    writer.write(sanitizeField(categoryName))
                    writer.write(FIELD_SEP.toString())
                    writer.write(indices.joinToString(","))
                    writer.newLine()
                }
            }
            writeIndexMap(data.liveIndicesByCategory)
            writeIndexMap(data.vodIndicesByCategory)
            writeIndexMap(data.seriesIndicesByCategory)
            // Tabela seriesId -> (categoria, nome) -- ver comentário de
            // CachedPlaylistData.seriesLookup.
            val lookup = data.seriesLookup.orEmpty()
            writer.write(lookup.size.toString()); writer.newLine()
            for ((seriesId, catAndName) in lookup) {
                writer.write(seriesId.toString())
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(catAndName.first))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(catAndName.second))
                writer.newLine()
            }
        }
        tempFile.renameTo(file)
    }

    // ---- Arquivos do CAMINHO RÁPIDO (um por tipo: Canais/Filmes/Séries)
    // ---- ver comentário de liveFastChannels acima.
    private data class TypedCacheData(
        val channels: List<M3uParser.ParsedChannel>,
        val indicesByCategory: Map<String, List<Int>>,
        val seriesLookup: Map<Int, Pair<String, String>>? = null
    )

    private fun typedCacheFile(cacheKey: String, type: String): File? {
        val dir = appContext?.filesDir ?: return null
        return File(dir, "m3u_fast_${kotlin.math.abs(cacheKey.hashCode())}_$type.m3u")
    }

    /** Separa os canais de UM tipo (Canais, Filmes ou Séries) da lista
     * unificada -- monta uma lista NOVA só com aquele tipo e reindexa os
     * índices das categorias pra apontar pra essa lista menor (índices
     * LOCAIS, não os originais da lista unificada). */
    private fun splitByType(
        allChannels: List<M3uParser.ParsedChannel>,
        globalIndicesByCategory: Map<String, List<Int>>
    ): TypedCacheData {
        val wantedGlobalIndices = HashSet<Int>()
        for (indices in globalIndicesByCategory.values) wantedGlobalIndices.addAll(indices)
        val remap = HashMap<Int, Int>(wantedGlobalIndices.size)
        val subset = ArrayList<M3uParser.ParsedChannel>(wantedGlobalIndices.size)
        allChannels.forEachIndexed { globalIndex, channel ->
            if (globalIndex in wantedGlobalIndices) {
                remap[globalIndex] = subset.size
                subset.add(channel)
            }
        }
        val localIndicesByCategory = globalIndicesByCategory.mapValues { (_, globalIdxList) ->
            globalIdxList.mapNotNull { remap[it] }
        }
        return TypedCacheData(subset, localIndicesByCategory)
    }

    private fun writeTypedCache(file: File, data: TypedCacheData, versionMarker: String) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.bufferedWriter().use { writer ->
            writer.write(versionMarker); writer.newLine()
            writer.write(data.channels.size.toString()); writer.newLine()
            for (channel in data.channels) {
                writer.write(sanitizeField(channel.groupTitle))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.name))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.logoUrl))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.streamUrl))
                writer.write(FIELD_SEP.toString())
                writer.write(sanitizeField(channel.tvgId))
                writer.newLine()
            }
            writer.write(data.indicesByCategory.size.toString()); writer.newLine()
            for ((categoryName, indices) in data.indicesByCategory) {
                writer.write(sanitizeField(categoryName))
                writer.write(FIELD_SEP.toString())
                writer.write(indices.joinToString(","))
                writer.newLine()
            }
            val lookup = data.seriesLookup
            if (lookup != null) {
                writer.write(lookup.size.toString()); writer.newLine()
                for ((seriesId, catAndName) in lookup) {
                    writer.write(seriesId.toString())
                    writer.write(FIELD_SEP.toString())
                    writer.write(sanitizeField(catAndName.first))
                    writer.write(FIELD_SEP.toString())
                    writer.write(sanitizeField(catAndName.second))
                    writer.newLine()
                }
            }
        }
        tempFile.renameTo(file)
    }

    private fun readTypedCache(file: File, versionMarker: String, withSeriesLookup: Boolean): TypedCacheData? {
        val lines = runCatching { file.bufferedReader().use { it.readLines() } }.getOrNull() ?: return null
        return runCatching {
            if (lines.isEmpty() || lines[0] != versionMarker) return@runCatching null
            var i = 1
            val channelCount = lines[i].toInt(); i++
            val channels = ArrayList<M3uParser.ParsedChannel>(channelCount)
            repeat(channelCount) {
                val parts = lines[i].split(FIELD_SEP)
                i++
                if (parts.size == 5) {
                    channels.add(
                        M3uParser.ParsedChannel(
                            groupTitle = readField(parts[0]) ?: "Geral",
                            name = readField(parts[1]) ?: "",
                            logoUrl = readField(parts[2]),
                            streamUrl = readField(parts[3]) ?: "",
                            tvgId = readField(parts[4])
                        )
                    )
                }
            }
            val categoryCount = lines[i].toInt(); i++
            val indicesByCategory = LinkedHashMap<String, List<Int>>(categoryCount)
            repeat(categoryCount) {
                val parts = lines[i].split(FIELD_SEP)
                i++
                if (parts.size == 2) {
                    val categoryName = readField(parts[0]) ?: return@repeat
                    val indices = if (parts[1].isEmpty()) emptyList() else parts[1].split(',').map { it.toInt() }
                    indicesByCategory[categoryName] = indices
                }
            }
            var seriesLookup: Map<Int, Pair<String, String>>? = null
            if (withSeriesLookup) {
                val lookupCount = lines[i].toInt(); i++
                val lookup = LinkedHashMap<Int, Pair<String, String>>(lookupCount)
                repeat(lookupCount) {
                    val parts = lines[i].split(FIELD_SEP)
                    i++
                    if (parts.size == 3) {
                        val seriesId = parts[0].toIntOrNull()
                        val categoryName = readField(parts[1])
                        val showName = readField(parts[2])
                        if (seriesId != null && categoryName != null && showName != null) {
                            lookup[seriesId] = categoryName to showName
                        }
                    }
                }
                seriesLookup = lookup
            }
            TypedCacheData(channels, indicesByCategory, seriesLookup)
        }.getOrNull()
    }

    // V2: mesma estrutura -- só muda pra forçar reconstrução a partir da
    // reclassificação corrigida (ver CACHE_FORMAT_VERSION acima e o ajuste
    // em M3uParser.contentKind). Sem isso, esses arquivos (que já
    // existiam com a classificação ANTIGA) continuariam sendo lidos
    // direto, sem nunca passar pelo cache unificado que foi corrigido.
    private val FAST_VERSION_LIVE = "SUPREMUS_FAST_V2_LIVE"
    private val FAST_VERSION_VOD = "SUPREMUS_FAST_V2_VOD"
    private val FAST_VERSION_SERIES = "SUPREMUS_FAST_V2_SERIES"

    /** Depois de baixar/classificar a lista (uma vez só), separa e grava os
     * 3 arquivos rápidos -- é o que faz abrir Canais/Filmes/Séries depois
     * não precisar mais ler os 270 mil itens juntos, só a fatia de cada
     * tipo. Chamada tanto no download quanto na primeira vez que um cache
     * unificado antigo (sem esses arquivos ainda) é lido -- depois disso
     * os arquivos já existem e essa função não roda de novo à toa (ver
     * fastCachesReady). */
    private fun persistFastCaches(
        cacheKey: String,
        channels: List<M3uParser.ParsedChannel>,
        liveIdx: Map<String, List<Int>>,
        vodIdx: Map<String, List<Int>>,
        seriesIdx: Map<String, List<Int>>,
        seriesLookup: Map<Int, Pair<String, String>>
    ) {
        val liveSplit = splitByType(channels, liveIdx)
        liveFastChannels[cacheKey] = liveSplit.channels
        liveFastIndices[cacheKey] = liveSplit.indicesByCategory
        typedCacheFile(cacheKey, "live")?.let { runCatching { writeTypedCache(it, liveSplit, FAST_VERSION_LIVE) } }

        val vodSplit = splitByType(channels, vodIdx)
        vodFastChannels[cacheKey] = vodSplit.channels
        vodFastIndices[cacheKey] = vodSplit.indicesByCategory
        typedCacheFile(cacheKey, "vod")?.let { runCatching { writeTypedCache(it, vodSplit, FAST_VERSION_VOD) } }

        val seriesSplit = splitByType(channels, seriesIdx).copy(seriesLookup = seriesLookup)
        seriesFastChannels[cacheKey] = seriesSplit.channels
        seriesFastIndices[cacheKey] = seriesSplit.indicesByCategory
        typedCacheFile(cacheKey, "series")?.let { runCatching { writeTypedCache(it, seriesSplit, FAST_VERSION_SERIES) } }
    }

    private fun fastCachesReady(cacheKey: String): Boolean =
        liveFastIndices.containsKey(cacheKey) || (typedCacheFile(cacheKey, "live")?.exists() == true)

    /** Garante que os canais/índices de Canais (só esse tipo) estão
     * prontos, lendo do arquivo rápido dedicado quando existe (comum,
     * rápido) -- se não existir ainda (primeira vez), cai no caminho de
     * sempre (fetchM3uChannels, arquivo unificado), que já deixa isso
     * pronto como efeito colateral. */
    private suspend fun ensureLiveFast(session: Session): Map<String, List<Int>> {
        val cacheKey = cacheKeyFor(session)
        liveFastIndices[cacheKey]?.let { return it }
        val t0 = System.nanoTime()
        val file = typedCacheFile(cacheKey, "live")
        val typed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file?.let { readTypedCache(it, FAST_VERSION_LIVE, withSeriesLookup = false) }
        }
        if (typed != null) {
            liveFastChannels[cacheKey] = typed.channels
            liveFastIndices[cacheKey] = typed.indicesByCategory
            val t1 = System.nanoTime()
            val timing = "FAST-live | arquivo=${file?.length() ?: -1}B | ler+parse=${(t1 - t0) / 1_000_000}ms | canais=${typed.channels.size}"
            lastLoadTiming = timing
            appContext?.getSharedPreferences("supremus_cache_diag", Context.MODE_PRIVATE)?.edit()?.putString("last_load_timing", timing)?.apply()
            return typed.indicesByCategory
        }
        fetchM3uChannels(session)
        return liveFastIndices[cacheKey].orEmpty()
    }

    /** Mesma ideia de ensureLiveFast, pra Filmes. */
    private suspend fun ensureVodFast(session: Session): Map<String, List<Int>> {
        val cacheKey = cacheKeyFor(session)
        vodFastIndices[cacheKey]?.let { return it }
        val t0 = System.nanoTime()
        val file = typedCacheFile(cacheKey, "vod")
        val typed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file?.let { readTypedCache(it, FAST_VERSION_VOD, withSeriesLookup = false) }
        }
        if (typed != null) {
            vodFastChannels[cacheKey] = typed.channels
            vodFastIndices[cacheKey] = typed.indicesByCategory
            val t1 = System.nanoTime()
            val timing = "FAST-vod | arquivo=${file?.length() ?: -1}B | ler+parse=${(t1 - t0) / 1_000_000}ms | canais=${typed.channels.size}"
            lastLoadTiming = timing
            appContext?.getSharedPreferences("supremus_cache_diag", Context.MODE_PRIVATE)?.edit()?.putString("last_load_timing", timing)?.apply()
            return typed.indicesByCategory
        }
        fetchM3uChannels(session)
        return vodFastIndices[cacheKey].orEmpty()
    }

    /** Mesma ideia de ensureLiveFast, pra Séries -- também lê a tabela
     * seriesLookup direto desse arquivo (bem menor que o unificado),
     * então Favoritos de série também ficam rápidos. */
    private suspend fun ensureSeriesFast(session: Session): Map<String, List<Int>> {
        val cacheKey = cacheKeyFor(session)
        seriesFastIndices[cacheKey]?.let { return it }
        val t0 = System.nanoTime()
        val file = typedCacheFile(cacheKey, "series")
        val typed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file?.let { readTypedCache(it, FAST_VERSION_SERIES, withSeriesLookup = true) }
        }
        if (typed != null) {
            seriesFastChannels[cacheKey] = typed.channels
            seriesFastIndices[cacheKey] = typed.indicesByCategory
            typed.seriesLookup?.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
            val t1 = System.nanoTime()
            val timing = "FAST-series | arquivo=${file?.length() ?: -1}B | ler+parse=${(t1 - t0) / 1_000_000}ms | canais=${typed.channels.size}"
            lastLoadTiming = timing
            appContext?.getSharedPreferences("supremus_cache_diag", Context.MODE_PRIVATE)?.edit()?.putString("last_load_timing", timing)?.apply()
            return typed.indicesByCategory
        }
        fetchM3uChannels(session)
        return seriesFastIndices[cacheKey].orEmpty()
    }

    /** Monta (ou reaproveita) a lista de exibição de UMA categoria de
     * Canais, a partir do caminho rápido (arquivo/lista só de Canais) --
     * substitui a versão antiga que indexava na lista unificada. */
    private fun buildLiveCategoryLazyFast(cacheKey: String, categoryName: String): List<LiveStream> {
        val perCategory = liveStreamsByCategoryCache.getOrPut(cacheKey) { mutableMapOf() }
        perCategory[categoryName]?.let { return it }
        val channels = liveFastChannels[cacheKey] ?: return emptyList()
        val indices = liveFastIndices[cacheKey]?.get(categoryName) ?: return emptyList()
        val built = M3uParser.toLiveStreams(indices.map { channels[it] }, categoryName)
        perCategory[categoryName] = built
        return built
    }

    /** Mesma ideia de buildLiveCategoryLazyFast, pra Filmes. */
    private fun buildVodCategoryLazyFast(cacheKey: String, categoryName: String): List<VodStream> {
        val perCategory = vodByCategoryCache.getOrPut(cacheKey) { mutableMapOf() }
        perCategory[categoryName]?.let { return it }
        val channels = vodFastChannels[cacheKey] ?: return emptyList()
        val indices = vodFastIndices[cacheKey]?.get(categoryName) ?: return emptyList()
        val built = M3uParser.toVodStreams(indices.map { channels[it] }, categoryName)
        perCategory[categoryName] = built
        return built
    }

    /** Mesma ideia de buildLiveCategoryLazyFast, pra Séries. */
    private fun buildSeriesCategoryLazyFast(cacheKey: String, categoryName: String): List<SeriesItem> {
        val perCategory = seriesByCategoryCache.getOrPut(cacheKey) { mutableMapOf() }
        perCategory[categoryName]?.let { return it }
        val channels = seriesFastChannels[cacheKey] ?: return emptyList()
        val indices = seriesFastIndices[cacheKey]?.get(categoryName) ?: return emptyList()
        val built = M3uParser.toSeriesShowsFromSubset(indices.map { channels[it] }, categoryName)
        built.forEach { show -> m3uSeriesLookup[show.seriesId] = categoryName to show.name }
        perCategory[categoryName] = built
        return built
    }

    /** Checagem rápida (só olha se o arquivo existe, não lê nem processa
     * nada) -- usado pra decidir se pula a tela de carregamento inteira e
     * entra direto, sem mostrar barra de progresso nenhuma, quando já tem
     * a lista guardada de uma sessão anterior. */
    fun hasCachedPlaylist(session: Session): Boolean {
        if (session.playlistUrl.isNullOrBlank()) return false
        val key = cacheKeyFor(session).ifBlank { return false }
        if (m3uCache.containsKey(key)) return true
        return m3uCacheFile(key)?.exists() == true
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
        val cacheKey = cacheKeyFor(session)
        m3uCache[cacheKey]?.let { return@runCatching }

        // Cache já PROCESSADO (não o texto bruto) -- ler e desserializar é
        // rápido, bem diferente de reprocessar o texto inteiro com regex
        // de novo (que chegava a levar dezenas de segundos numa lista
        // grande, mesmo já tendo sido processada antes).
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            readParsedCache(cacheKey)
        }
        if (cached != null && cached.channels.isNotEmpty()) {
            m3uCache[cacheKey] = cached.channels
            epgUrlCache[cacheKey] = cached.epgUrl
            // Canais/Filmes/Séries: só guarda os ÍNDICES (nomes de
            // categoria + posições) -- rápido, sem montar objeto nenhum. A
            // lista de exibição de cada categoria só é montada quando o
            // usuário abrir ela de verdade (ver buildLiveCategoryLazy/
            // buildVodCategoryLazy/buildSeriesCategoryLazy). A tabela que
            // Favoritos precisa pra abrir uma série direto (seriesLookup)
            // vem pronta do disco, sem precisar montar categoria nenhuma.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (cached.liveIndicesByCategory != null) {
                    liveIndicesByCategoryCache[cacheKey] = cached.liveIndicesByCategory
                    vodIndicesByCategoryCache[cacheKey] = cached.vodIndicesByCategory.orEmpty()
                    seriesIndicesByCategoryCache[cacheKey] = cached.seriesIndicesByCategory.orEmpty()
                    cached.seriesLookup?.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
                    // Se ainda não existem os arquivos rápidos (por tipo)
                    // pra essa conta -- ex: cache V3 salvo por um build
                    // anterior a essa otimização -- gera eles agora, uma
                    // vez só, a partir do que acabou de ler (sem precisar
                    // baixar de novo). Da próxima vez já lê direto.
                    if (!fastCachesReady(cacheKey)) {
                        persistFastCaches(
                            cacheKey, cached.channels,
                            cached.liveIndicesByCategory, cached.vodIndicesByCategory.orEmpty(), cached.seriesIndicesByCategory.orEmpty(),
                            cached.seriesLookup.orEmpty()
                        )
                    }
                } else {
                    // Cache antigo (de antes dessa correção), sem os índices
                    // salvos ainda -- classifica agora e reescreve o cache já
                    // com tudo pronto pra próxima vez.
                    val (liveIdx, vodIdx, seriesIdx) = classifyIndices(cached.channels)
                    liveIndicesByCategoryCache[cacheKey] = liveIdx
                    vodIndicesByCategoryCache[cacheKey] = vodIdx
                    seriesIndicesByCategoryCache[cacheKey] = seriesIdx
                    val series = buildSeriesGroupsFromIndices(cached.channels, seriesIdx)
                    seriesByCategoryCache[cacheKey] = series.toMutableMap()
                    val seriesLookup = series.flatMap { (cat, shows) -> shows.map { it.seriesId to (cat to it.name) } }.toMap()
                    seriesLookup.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
                    writeParsedCache(cacheKey, CachedPlaylistData(cached.channels, cached.epgUrl, liveIdx, vodIdx, seriesIdx, seriesLookup))
                    persistFastCaches(cacheKey, cached.channels, liveIdx, vodIdx, seriesIdx, seriesLookup)
                }
            }
            onProgress(1, 1)
            return@runCatching
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val diagPrefs = appContext?.getSharedPreferences("supremus_cache_diag", Context.MODE_PRIVATE)
            val dl0 = System.nanoTime()
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
            val dl1 = System.nanoTime()

            epgUrlCache[cacheKey] = M3uParser.extractEpgUrl(text)
            val parsed = M3uParser.parse(text)
            val dl2 = System.nanoTime()
            if (parsed.isNotEmpty()) {
                m3uCache[cacheKey] = parsed
                val (liveIdx, vodIdx, seriesIdx) = classifyIndices(parsed)
                liveIndicesByCategoryCache[cacheKey] = liveIdx
                vodIndicesByCategoryCache[cacheKey] = vodIdx
                seriesIndicesByCategoryCache[cacheKey] = seriesIdx
                val series = buildSeriesGroupsFromIndices(parsed, seriesIdx)
                seriesByCategoryCache[cacheKey] = series.toMutableMap()
                val seriesLookup = series.flatMap { (cat, shows) -> shows.map { it.seriesId to (cat to it.name) } }.toMap()
                seriesLookup.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
                val dl3 = System.nanoTime()
                writeParsedCache(cacheKey, CachedPlaylistData(parsed, epgUrlCache[cacheKey], liveIdx, vodIdx, seriesIdx, seriesLookup))
                persistFastCaches(cacheKey, parsed, liveIdx, vodIdx, seriesIdx, seriesLookup)
                val dl4 = System.nanoTime()
                // DIAGNÓSTICO TEMPORÁRIO: separa quanto tempo o DOWNLOAD em
                // si (rede, ~depende da conexão) levou, vs. processar o
                // texto M3U (regex por linha) vs. classificar+montar
                // séries vs. gravar em disco -- pra saber onde cortar os
                // 40-50s da primeira vez.
                val timing = "MISS-detalhado | bytes=$bytesRead | download=${(dl1 - dl0) / 1_000_000}ms | parseM3U=${(dl2 - dl1) / 1_000_000}ms | classificar=${(dl3 - dl2) / 1_000_000}ms | gravar=${(dl4 - dl3) / 1_000_000}ms | canais=${parsed.size}"
                lastLoadTiming = timing
                diagPrefs?.edit()?.putString("last_load_timing", timing)?.apply()
            }
        }
    }

    private suspend fun fetchM3uChannels(session: Session): List<M3uParser.ParsedChannel> {
        val playlistUrl = session.playlistUrl?.takeIf { it.isNotBlank() }
            ?: error("Esta sessão não tem uma playlist M3U para usar.")
        val cacheKey = cacheKeyFor(session)
        m3uCache[cacheKey]?.let { return it }

        // Só uma chamada de cada vez processa o cache pra essa conta --
        // ver comentário da declaração de fetchMutexes acima.
        return mutexFor(cacheKey).withLock {
            // Re-checa AGORA que já está dentro da trava -- outra chamada
            // pode ter terminado de montar tudo enquanto essa esperava.
            m3uCache[cacheKey]?.let { return@withLock it }

            val diagPrefs = appContext?.getSharedPreferences("supremus_cache_diag", Context.MODE_PRIVATE)
            val t0 = System.nanoTime()

            // Cache já PROCESSADO (não o texto bruto) -- ler e desserializar
            // é rápido; reprocessar o texto inteiro de novo (regex em
            // milhares de linhas) é que demorava até 40s numa lista grande,
            // mesmo já tendo sido processado antes.
            val timedRead = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                readParsedCacheTimed(cacheKey)
            }
            val cached = timedRead.data
            val t1 = System.nanoTime()
            if (cached != null && cached.channels.isNotEmpty()) {
                m3uCache[cacheKey] = cached.channels
                epgUrlCache[cacheKey] = cached.epgUrl
                // Canais/Filmes/Séries: só guarda os ÍNDICES (instantâneo,
                // sem montar objeto nenhum) -- a lista de exibição de cada
                // categoria só é montada quando o usuário abre ela de
                // verdade (buildLiveCategoryLazy/buildVodCategoryLazy/
                // buildSeriesCategoryLazy). A tabela que Favoritos precisa
                // (seriesLookup) vem pronta do disco. Isso é o que elimina
                // a "montagem" do caminho crítico de abrir a tela.
                // CRÍTICO: mesmo sendo mais leve agora, ainda roda em
                // Dispatchers.IO explicitamente -- não pode voltar a rodar
                // na thread principal (era isso que travava a tela antes).
                if (cached.liveIndicesByCategory != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        liveIndicesByCategoryCache[cacheKey] = cached.liveIndicesByCategory
                        vodIndicesByCategoryCache[cacheKey] = cached.vodIndicesByCategory.orEmpty()
                        seriesIndicesByCategoryCache[cacheKey] = cached.seriesIndicesByCategory.orEmpty()
                        cached.seriesLookup?.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
                        if (!fastCachesReady(cacheKey)) {
                            persistFastCaches(
                                cacheKey, cached.channels,
                                cached.liveIndicesByCategory, cached.vodIndicesByCategory.orEmpty(), cached.seriesIndicesByCategory.orEmpty(),
                                cached.seriesLookup.orEmpty()
                            )
                        }
                    }
                } else {
                    // Cache antigo (de antes dessa correção), sem os índices
                    // salvos ainda -- classifica agora e reescreve o cache já
                    // com tudo pronto pra próxima vez.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val (liveIdx, vodIdx, seriesIdx) = classifyIndices(cached.channels)
                        liveIndicesByCategoryCache[cacheKey] = liveIdx
                        vodIndicesByCategoryCache[cacheKey] = vodIdx
                        seriesIndicesByCategoryCache[cacheKey] = seriesIdx
                        val series = buildSeriesGroupsFromIndices(cached.channels, seriesIdx)
                        seriesByCategoryCache[cacheKey] = series.toMutableMap()
                        val seriesLookup = series.flatMap { (cat, shows) -> shows.map { it.seriesId to (cat to it.name) } }.toMap()
                        seriesLookup.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
                        writeParsedCache(cacheKey, CachedPlaylistData(cached.channels, cached.epgUrl, liveIdx, vodIdx, seriesIdx, seriesLookup))
                        persistFastCaches(cacheKey, cached.channels, liveIdx, vodIdx, seriesIdx, seriesLookup)
                    }
                }
                val t2 = System.nanoTime()
                val timing = "V3 | arquivo=${timedRead.fileBytes}B | ler=${timedRead.readMs}ms | parse=${timedRead.parseMs}ms | indices=${(t2 - t1) / 1_000_000}ms | canais=${cached.channels.size}"
                lastLoadTiming = timing
                diagPrefs?.edit()?.putString("last_load_timing", timing)?.apply()
                return@withLock cached.channels
            }

            val body = fetchBody(playlistUrl)
            val epgUrl = M3uParser.extractEpgUrl(body)
            epgUrlCache[cacheKey] = epgUrl
            val parsed = M3uParser.parse(body)
            if (parsed.isEmpty()) {
                error("A playlist M3U não contém nenhum canal reconhecível (recebido: \"${body.take(150).replace("\n", " ")}\").")
            }
            m3uCache[cacheKey] = parsed
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val (liveIdx, vodIdx, seriesIdx) = classifyIndices(parsed)
                liveIndicesByCategoryCache[cacheKey] = liveIdx
                vodIndicesByCategoryCache[cacheKey] = vodIdx
                seriesIndicesByCategoryCache[cacheKey] = seriesIdx
                val series = buildSeriesGroupsFromIndices(parsed, seriesIdx)
                seriesByCategoryCache[cacheKey] = series.toMutableMap()
                val seriesLookup = series.flatMap { (cat, shows) -> shows.map { it.seriesId to (cat to it.name) } }.toMap()
                seriesLookup.forEach { (seriesId, catAndName) -> m3uSeriesLookup[seriesId] = catAndName }
                writeParsedCache(cacheKey, CachedPlaylistData(parsed, epgUrl, liveIdx, vodIdx, seriesIdx, seriesLookup))
                persistFastCaches(cacheKey, parsed, liveIdx, vodIdx, seriesIdx, seriesLookup)
            }
            val t2 = System.nanoTime()
            val timing = "MISS (baixou agora) | total=${(t2 - t0) / 1_000_000}ms | canais=${parsed.size}"
            lastLoadTiming = timing
            diagPrefs?.edit()?.putString("last_load_timing", timing)?.apply()
            parsed
        }
    }

    /** Busca e interpreta o guia XMLTV referenciado na própria playlist M3U
     * (tag url-tvg/x-tvg-url) -- é assim que a maioria dos apps de IPTV
     * mostra a programação real ("Jornal Nacional agora, novela depois")
     * mesmo em painéis sem API Xtream. Baixa e processa só uma vez por
     * sessão (fica em cache), e só guarda os canais que realmente existem
     * na playlist, pra não gastar memória com um guia inteiro à toa.
     *
     * VOLTOU a ser esse jeito simples de propósito -- era assim que
     * funcionava antes de eu ir empilhando "melhorias" (busca em
     * paralelo, cliente com timeout curto, casamento por nome, quarta
     * fonte pela API) que na prática QUEBRARAM o que já funcionava.
     * Simples e comprovado > complexo e quebrado. */
    private suspend fun fetchXmlTvGuide(session: Session): Map<String, List<XmlTvProgramme>> {
        val cacheKey = cacheKeyFor(session)
        val channels = runCatching { fetchM3uChannels(session) }.getOrNull() ?: return emptyMap()
        // Compara tvg-id sem diferenciar maiúscula/minúscula -- é comum o
        // painel mandar "EPTV.Campinas" na playlist M3U e o guia XMLTV usar
        // "eptv.campinas" (ou vice-versa); sem isso, o casamento falhava
        // silenciosamente mesmo quando os dois IDs eram "o mesmo canal".
        val tvgIds = channels.mapNotNull { it.tvgId?.lowercase() }.toSet()
        if (tvgIds.isEmpty()) return emptyMap()

        // 1) URL declarada no cabeçalho da própria playlist M3U (padrão
        //    mais comum). 2) Se não tiver, painéis Xtream Codes quase
        //    sempre também expõem o guia num endereço fixo (xmltv.php),
        //    mesmo sem avisar isso na playlist. 3) Por último, tenta um
        //    guia universal de canais brasileiros (iptv-epg.org) -- cobre
        //    canais comuns quando nem a playlist nem o painel têm guia
        //    próprio nenhum.
        val declaredUrl = epgUrlCache[cacheKey]
        val fallbackUrl = "${normalizeBase(session.serverUrl)}/xmltv.php?username=${session.username}&password=${session.password}"
        val universalFallbackUrl = "http://iptv-epg.org/files/epg-br.xml"
        val candidates = listOfNotNull(declaredUrl, fallbackUrl, universalFallbackUrl).distinct()

        for (epgUrl in candidates) {
            // Guarda em cache mesmo quando o resultado vem vazio (sem
            // canal nenhum batendo) -- sem isso, cada troca de canal
            // tentava baixar as 3 fontes de novo do ZERO, mesmo já
            // sabendo que nenhuma tinha dado certo antes. Só volta a
            // tentar quando é falha de REDE de verdade (não "sem dados").
            val alreadyChecked = xmlTvCache[epgUrl]
            if (alreadyChecked != null) {
                if (alreadyChecked.isNotEmpty()) return alreadyChecked
                continue
            }
            val xml = runCatching { fetchBody(epgUrl) }.getOrNull() ?: continue
            // CRÍTICO: precisa rodar em Dispatchers.IO -- esse parser
            // (XmlPullParser + regex por programa) processa um arquivo que
            // pode ter dezenas de milhares de entradas (principalmente a
            // fonte universal de fallback), e sem isso rodava direto na
            // THREAD PRINCIPAL -- isso é o "Supreme não está respondendo"
            // (ANR) que aparecia na tela de Canais um pouco depois de abrir,
            // exatamente o tempo de baixar+processar esse guia.
            val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { XmlTvParser.parse(xml, tvgIds) }.getOrDefault(emptyMap())
            }
            xmlTvCache[epgUrl] = parsed
            if (parsed.isNotEmpty()) {
                epgUrlCache[cacheKey] = epgUrl
                return parsed
            }
        }
        return emptyMap()
    }

    /** Busca o guia de programação em segundo plano, bem cedo (chamado
     * ainda na tela Home) -- assim, quando o usuário abrir Live TV, o
     * guia já está pronto na memória, sem precisar esperar nada na hora
     * de trocar de canal. */
    suspend fun prefetchEpgGuide(session: Session) {
        runCatching { fetchXmlTvGuide(session) }
    }

    /** Programação (agora + próximos) de UM canal específico, lida do guia
     * XMLTV da playlist -- usado quando o canal veio de M3U (sem stream_id
     * de verdade pra usar o get_short_epg da API Xtream). */
    suspend fun getEpgFromPlaylist(session: Session, tvgId: String?, channelName: String? = null): Result<List<XmlTvProgramme>> = runCatching {
        if (tvgId.isNullOrBlank()) return@runCatching emptyList()
        val guide = fetchXmlTvGuide(session)
        val now = System.currentTimeMillis()
        guide[tvgId.lowercase()].orEmpty()
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
        val epgUrl = if (playlistUrl != null) epgUrlCache[cacheKeyFor(session)] else null
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
            val m3uResult = runCatching {
                // Caminho RÁPIDO: lê só o arquivo de Canais (bem menor que
                // o unificado com Filmes+Séries junto) -- ver
                // ensureLiveFast. Só os NOMES das categorias aqui, sem
                // montar LiveStream nenhum.
                val cacheKey = cacheKeyFor(session)
                val idx = ensureLiveFast(session)
                // SEM ordenar em ordem alfabética de propósito -- mantém a
                // ordem NATURAL da playlist (a ordem que as categorias
                // aparecem no arquivo M3U original), que é como o
                // provedor organizou de verdade (grupos relacionados perto
                // um do outro). Ordem alfabética embaralhava isso.
                idx.keys.map { Category(categoryId = it, categoryName = it) }
            }
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
                val cacheKey = cacheKeyFor(session)
                val idx = ensureLiveFast(session)
                val targetCategory = categoryId ?: idx.keys.firstOrNull()
                if (targetCategory == null) emptyList()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    buildLiveCategoryLazyFast(cacheKey, targetCategory)
                }
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
            val m3uResult = runCatching {
                // Caminho RÁPIDO: lê só o arquivo de Filmes.
                val idx = ensureVodFast(session)
                idx.keys.map { Category(categoryId = it, categoryName = it) }
            }
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
                val cacheKey = cacheKeyFor(session)
                val idx = ensureVodFast(session)
                val targetCategory = categoryId ?: idx.keys.firstOrNull()
                if (targetCategory == null) emptyList()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    buildVodCategoryLazyFast(cacheKey, targetCategory)
                }
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
            val m3uResult = runCatching {
                // Caminho RÁPIDO: lê só o arquivo de Séries (que já traz a
                // tabela de Favoritos junto).
                val idx = ensureSeriesFast(session)
                idx.keys.map { Category(categoryId = it, categoryName = it) }
            }
            m3uResult.getOrNull()?.let { if (it.isNotEmpty()) return@runCatching it }
        }
        val url = "${normalizeBase(session.serverUrl)}/player_api.php" +
                "?username=${session.username}&password=${session.password}" +
                "&action=get_series_categories"
        val type = object : TypeToken<List<Category>>() {}.type
        val categories: List<Category> = parseJsonList(fetchBody(url), type)
        categories.sortedBy { it.categoryName.lowercase() }
    }

    suspend fun getSeries(session: Session, categoryId: String?): Result<List<SeriesItem>> = runCatching {
        if (!session.playlistUrl.isNullOrBlank() && categoryId != null) {
            val m3uResult = runCatching {
                val cacheKey = cacheKeyFor(session)
                ensureSeriesFast(session)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    buildSeriesCategoryLazyFast(cacheKey, categoryId)
                }
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

    /** Séries vindas de M3U são agrupadas pelo nome (removendo o SxxExx do
     * final) -- é o mesmo jeito que outros apps de IPTV leem séries numa
     * playlist M3U simples, já que esse formato não separa formalmente
     * série / temporada / episódio como a API Xtream faz. */

    suspend fun getSeriesInfo(session: Session, seriesId: Int): Result<SeriesInfoResponse> = runCatching {
        if (!session.playlistUrl.isNullOrBlank()) {
            // Garante que a tabela seriesId -> (categoria, nome) já foi
            // carregada ANTES de checar -- sem isso, a primeira chamada do
            // processo (ex: abrir um favorito de série direto) sempre
            // batia com o lookup vazio e caía pra API Xtream (que não
            // existe nesse tipo de painel M3U-only), mesmo a série sendo
            // encontrável. Caminho RÁPIDO: usa só os canais de Séries (não
            // precisa da lista inteira de Canais+Filmes+Séries junto).
            val cacheKey = cacheKeyFor(session)
            runCatching { ensureSeriesFast(session) }
            val channels = seriesFastChannels[cacheKey]
            if (!channels.isNullOrEmpty()) {
                m3uSeriesLookup[seriesId]?.let { (categoryName, showName) ->
                    return@runCatching M3uParser.toSeriesInfo(channels, categoryName, showName)
                }
            }
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
