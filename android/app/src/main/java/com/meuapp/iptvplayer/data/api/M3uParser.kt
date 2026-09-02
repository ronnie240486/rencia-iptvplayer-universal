package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.data.model.VodStream

/** Alguns painéis IPTV (principalmente os mais simples/baratos) só
 * oferecem a playlist M3U (get.php) e NÃO implementam a API Xtream
 * completa (player_api.php) -- nesses casos, player_api.php responde algo
 * que não é JSON válido (às vezes nem existe no servidor), e o app não
 * consegue montar categorias/canais do jeito normal. Esse parser lê a
 * playlist M3U diretamente e monta a mesma estrutura de categorias/canais
 * a partir dela, como alternativa. */
object M3uParser {

    data class ParsedChannel(
        val groupTitle: String,
        val name: String,
        val logoUrl: String?,
        val streamUrl: String
    )

    fun parse(content: String): List<ParsedChannel> {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val result = mutableListOf<ParsedChannel>()
        var pendingGroup: String? = null
        var pendingName: String? = null
        var pendingLogo: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingGroup = extractAttribute(line, "group-title") ?: "Geral"
                    pendingLogo = extractAttribute(line, "tvg-logo")
                    // O nome do canal vem depois da última vírgula do #EXTINF.
                    pendingName = line.substringAfterLast(',').trim().ifBlank { "Canal" }
                }
                line.startsWith("#") -> Unit // outras tags M3U (#EXTM3U, #EXTGRP etc) -- ignora
                else -> {
                    // Linha sem # é a URL do stream, referente ao #EXTINF anterior.
                    val name = pendingName
                    if (!name.isNullOrBlank()) {
                        result.add(
                            ParsedChannel(
                                groupTitle = pendingGroup ?: "Geral",
                                name = name,
                                logoUrl = pendingLogo,
                                streamUrl = line
                            )
                        )
                    }
                    pendingGroup = null
                    pendingName = null
                    pendingLogo = null
                }
            }
        }
        return result
    }

    private fun extractAttribute(line: String, key: String): String? {
        val regex = Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
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
     * nome do grupo -- serve só como fallback quando o player_api.php não
     * funciona nesse painel. Por padrão, tudo que não parece claramente
     * filme/série é tratado como "ao vivo" (era o comportamento original,
     * antes de existir essa separação). */
    private fun contentKind(channel: ParsedChannel): String {
        val url = channel.streamUrl.lowercase()
        val group = channel.groupTitle.lowercase()
        return when {
            "/movie/" in url -> "vod"
            "/series/" in url -> "series"
            "/live/" in url -> "live"
            listOf("filme", "vod", "movie", "filmes").any { it in group } -> "vod"
            listOf("serie", "series", "séries").any { it in group } -> "series"
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
}
