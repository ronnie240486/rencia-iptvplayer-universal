package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.data.model.SeriesEpisode
import com.meuapp.iptvplayer.data.model.SeriesInfoResponse
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.data.model.VodStream
import java.text.Normalizer

/** Alguns painéis IPTV (principalmente os mais simples/baratos) só
 * oferecem a playlist M3U (get.php) e NÃO implementam a API Xtream
 * completa (player_api.php) -- nesses casos, player_api.php responde algo
 * que não é JSON válido (às vezes nem existe no servidor), e o app não
 * consegue montar categorias/canais do jeito normal. Esse parser lê a
 * playlist M3U diretamente e monta a mesma estrutura de categorias/canais
 * a partir dela. */
object M3uParser {

    data class ParsedChannel(
        val groupTitle: String,
        val name: String,
        val logoUrl: String?,
        val streamUrl: String
    )

    fun parse(content: String): List<ParsedChannel> {
        // Alguns servidores mandam um BOM (marca de ordem de bytes) no
        // começo do arquivo, ou usam quebra de linha \r só -- isso pode
        // fazer a primeira linha não bater com "#EXTM3U"/"#EXTINF". Limpa
        // tudo isso antes de processar linha por linha.
        val cleaned = content.removePrefix("\uFEFF")
        val lines = cleaned.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<ParsedChannel>()
        var pendingGroup: String? = null
        var pendingName: String? = null
        var pendingLogo: String? = null
        var anonymousCounter = 0

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingGroup = extractAttribute(line, "group-title") ?: "Geral"
                    pendingLogo = extractAttribute(line, "tvg-logo")
                    // O nome do canal vem depois da última vírgula do #EXTINF.
                    pendingName = line.substringAfterLast(',').trim().ifBlank { null }
                }
                line.startsWith("#") -> Unit // outras tags M3U (#EXTM3U, #EXTGRP etc) -- ignora
                line.startsWith("http://", ignoreCase = true) ||
                    line.startsWith("https://", ignoreCase = true) ||
                    line.startsWith("rtmp://", ignoreCase = true) -> {
                    // Linha de URL de stream. Normalmente vem logo depois
                    // de um #EXTINF, mas alguns geradores de playlist
                    // "quebrados" pulam o #EXTINF de vez em quando -- em
                    // vez de descartar a URL, dá um nome genérico e mantém
                    // o canal, pra não perder conteúdo à toa.
                    anonymousCounter++
                    result.add(
                        ParsedChannel(
                            groupTitle = pendingGroup ?: "Geral",
                            name = pendingName ?: "Canal $anonymousCounter",
                            logoUrl = pendingLogo,
                            streamUrl = line
                        )
                    )
                    pendingGroup = null
                    pendingName = null
                    pendingLogo = null
                }
                else -> Unit // linha desconhecida (não é #tag nem URL) -- ignora
            }
        }
        return result
    }

    private fun extractAttribute(line: String, key: String): String? {
        val regex = Regex("$key=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun toCategories(channels: List<ParsedChannel>): List<Category> =
        channels.map { it.groupTitle }.distinct()
            .sortedBy { it.lowercase() }
            .map { group -> Category(categoryId = group, categoryName = group) }

    fun toLiveStreams(channels: List<ParsedChannel>, categoryName: String): List<LiveStream> =
        channels.filter { it.groupTitle == categoryName }
            .mapIndexed { index, channel ->
                LiveStream(
                    num = index + 1,
                    name = channel.name,
                    streamId = 0,
                    streamIcon = channel.logoUrl,
                    categoryId = categoryName,
                    epgChannelId = null,
                    directStreamUrl = channel.streamUrl
                )
            }

    /** Numa playlist M3U "tudo junto" (sem separação formal de ao vivo /
     * filme / série como a API Xtream tem), a única pista confiável é o
     * próprio link do stream (/live/, /movie/, /series/) ou palavras no
     * nome do grupo. Por padrão, tudo que não parece claramente
     * filme/série é tratado como "ao vivo". */
    private fun contentKind(channel: ParsedChannel): String {
        val url = channel.streamUrl.lowercase()
        val group = channel.groupTitle.lowercase()
        return when {
            "/movie/" in url -> "vod"
            "/series/" in url -> "series"
            "/live/" in url -> "live"
            listOf("filme", "vod", "movie", "filmes").any { it in group } -> "vod"
            listOf("serie", "series", "séries").any { it in group } -> "series"
            episodeInfo(channel.name) != null -> "series"
            else -> "live"
        }
    }

    fun toLiveCategories(channels: List<ParsedChannel>): List<Category> =
        toCategories(channels.filter { contentKind(it) == "live" })

    fun toLiveStreamsFiltered(channels: List<ParsedChannel>, categoryName: String): List<LiveStream> =
        toLiveStreams(channels.filter { contentKind(it) == "live" }, categoryName)

    fun toVodCategories(channels: List<ParsedChannel>): List<Category> =
        toCategories(channels.filter { contentKind(it) == "vod" })

    fun toVodStreams(channels: List<ParsedChannel>, categoryName: String): List<VodStream> =
        channels.filter { it.groupTitle == categoryName && contentKind(it) == "vod" }
            .mapIndexed { index, channel ->
                VodStream(
                    num = index + 1,
                    name = channel.name,
                    streamId = 0,
                    streamIcon = channel.logoUrl,
                    categoryId = categoryName,
                    rating = null,
                    containerExtension = null,
                    directStreamUrl = channel.streamUrl
                )
            }

    fun toSeriesCategories(channels: List<ParsedChannel>): List<Category> =
        toCategories(channels.filter { contentKind(it) == "series" })

    // ---- Séries: agrupamento por nome + temporada/episódio ----

    private data class EpisodeInfo(val showName: String, val season: Int, val episode: Int)

    // Cobre os formatos mais comuns: "Nome S01E02", "Nome S1 E2",
    // "Nome 1x02", "Nome - Temporada 1 Episódio 2".
    private val seasonEpisodeRegexes = listOf(
        Regex("(?i)^(.*?)[\\s._-]*S(\\d{1,2})[\\s._-]*E(\\d{1,3})\\b.*$"),
        Regex("(?i)^(.*?)[\\s._-]*(\\d{1,2})x(\\d{1,3})\\b.*$"),
        Regex("(?i)^(.*?)[\\s._-]*Temporada\\s*(\\d{1,2})[\\s._-]*Epis[oó]dio\\s*(\\d{1,3}).*$"),
    )

    private fun episodeInfo(name: String): EpisodeInfo? {
        for (regex in seasonEpisodeRegexes) {
            val match = regex.find(name) ?: continue
            val showName = match.groupValues[1].trim(' ', '-', '.', '_').ifBlank { name.trim() }
            val season = match.groupValues[2].toIntOrNull() ?: continue
            val episode = match.groupValues[3].toIntOrNull() ?: continue
            return EpisodeInfo(showName, season, episode)
        }
        return null
    }

    /** ID estável (não muda entre chamadas) derivado do nome da série --
     * usado como "series_id" fake pra série vinda de M3U, já que não existe
     * um ID de verdade nesse formato. */
    private fun stableId(text: String): Int {
        val normalized = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        var hash = 7
        for (c in normalized) hash = hash * 31 + c.code
        // Mantém positivo e num intervalo que não colide com IDs reais da
        // API Xtream (que normalmente são pequenos) -- soma um deslocamento
        // grande e força positivo.
        return (kotlin.math.abs(hash) % 1_000_000_000) + 100_000_000
    }

    /** Agrupa episódios (contentKind == "series") de uma categoria em
     * "séries" (por nome, ignorando o SxxExx) -- é assim que a maioria dos
     * outros apps de IPTV lê séries a partir de playlists M3U simples,
     * já que o formato M3U não tem uma hierarquia formal série > temporada
     * > episódio como a API Xtream. */
    fun toSeriesShows(channels: List<ParsedChannel>, categoryName: String): List<SeriesItem> {
        val seriesEntries = channels.filter { it.groupTitle == categoryName && contentKind(it) == "series" }
        val grouped = seriesEntries.groupBy { episodeInfo(it.name)?.showName ?: it.name.trim() }
        return grouped.entries
            .sortedBy { it.key.lowercase() }
            .map { (showName, entries) ->
                SeriesItem(
                    num = 0,
                    name = showName,
                    seriesId = stableId("$categoryName|$showName"),
                    cover = entries.firstOrNull { it.logoUrl != null }?.logoUrl,
                    categoryId = categoryName,
                    rating = null,
                    lastModified = null
                )
            }
    }

    /** Monta os episódios (agrupados por temporada) de UMA série específica
     * -- usado quando o usuário toca numa série da lista montada por
     * toSeriesShows(). Precisa varrer a playlist inteira de novo porque só
     * temos o ID fake, não sabemos de antemão a categoria/nome originais
     * (por isso quem chama essa função já resolve isso via cache, ver
     * XtreamRepository). */
    fun toSeriesInfo(channels: List<ParsedChannel>, categoryName: String, showName: String): SeriesInfoResponse {
        val entries = channels.filter {
            it.groupTitle == categoryName && contentKind(it) == "series" &&
                (episodeInfo(it.name)?.showName ?: it.name.trim()) == showName
        }
        val bySeason = entries.groupBy { episodeInfo(it.name)?.season ?: 1 }
        val episodesMap = bySeason.mapKeys { it.key.toString() }.mapValues { (_, seasonEntries) ->
            seasonEntries.sortedBy { episodeInfo(it.name)?.episode ?: 0 }
                .mapIndexed { index, channel ->
                    val info = episodeInfo(channel.name)
                    SeriesEpisode(
                        id = "m3u_${stableId(channel.streamUrl)}",
                        episodeNumber = info?.episode ?: (index + 1),
                        title = channel.name,
                        containerExtension = null,
                        info = null,
                        directStreamUrl = channel.streamUrl
                    )
                }
        }
        val cover = entries.firstOrNull { it.logoUrl != null }?.logoUrl
        return SeriesInfoResponse(
            info = com.meuapp.iptvplayer.data.model.SeriesInfo(
                name = showName,
                cover = cover,
                plot = null,
                genre = categoryName,
                releaseDate = null,
                rating = null
            ),
            episodes = episodesMap,
            seasons = null
        )
    }
}
