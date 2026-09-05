package com.meuapp.iptvplayer.data.api

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Um programa de um canal, lido de um guia XMLTV. */
data class XmlTvProgramme(
    val channelId: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String
)

/** Muitas playlists M3U referenciam um guia de programação em formato
 * XMLTV (padrão de mercado, usado por praticamente todo software de IPTV)
 * -- é assim que a maioria dos apps mostra "o que está passando agora"
 * mesmo em painéis que só oferecem M3U, sem API Xtream. Formato:
 * <tv><channel id="X"><display-name>Nome</display-name></channel>
 * <programme channel="X" start="20240101120000 +0000" stop="...">
 * <title>Jornal Nacional</title></programme></tv> */
object XmlTvParser {

    // Formatos de data mais comuns em arquivos XMLTV -- a maioria usa
    // "yyyyMMddHHmmss Z", mas alguns painéis omitem o fuso horário.
    private val formats = listOf(
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmss"
    )

    private fun parseTime(raw: String): Long? {
        val trimmed = raw.trim()
        for (pattern in formats) {
            val result = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    if (!pattern.contains("Z")) timeZone = TimeZone.getTimeZone("UTC")
                }.parse(trimmed)?.time
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    /** Deixa o texto num formato "neutro" pra comparar nomes de canal
     * mesmo quando escritos de jeitos diferentes (maiúscula/minúscula,
     * acento, espaço, "HD"/"FHD" no final) -- é assim que dá pra achar
     * "SporTV 2" na playlist do usuário batendo com "SPORTV2 HD" dentro
     * do guia, mesmo os dois nomes não sendo idênticos. */
    fun normalizeChannelName(name: String): String {
        val noAccents = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return noAccents
            .replace(Regex("\\b(fhd|hd|sd|4k|fullhd|h265|h264|tv)\\b"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    /** Primeira passagem: só lê os blocos <channel id="X"><display-name>
     * -- rápido e leve (um guia tem poucas centenas de canais, bem menos
     * que a quantidade de programas). Serve pra descobrir qual ID o guia
     * usa pra cada canal, mesmo quando esse ID não bate com o tvg-id da
     * playlist M3U do usuário. */
    fun parseChannelNames(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>() // nome normalizado -> id do canal no guia
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var currentId: String? = null
        var inDisplayName = false
        var currentName: StringBuilder? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> currentId = parser.getAttributeValue(null, "id")
                        "display-name" -> {
                            if (currentId != null) {
                                inDisplayName = true
                                currentName = StringBuilder()
                            }
                        }
                        // No formato XMLTV, todos os <channel> vêm ANTES de
                        // qualquer <programme> -- assim que aparece o
                        // primeiro <programme>, já sabemos todos os canais
                        // que existem, não precisa continuar lendo o
                        // arquivo inteiro (que pode ter dias de programação
                        // de centenas de canais, bem mais pesado que só a
                        // lista de canais). Isso é o que fazia essa etapa
                        // demorar demais com guias grandes.
                        "programme" -> return result
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inDisplayName) currentName?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "display-name" -> {
                            inDisplayName = false
                            val id = currentId
                            val name = currentName?.toString()?.trim()
                            if (id != null && !name.isNullOrBlank()) {
                                val normalized = normalizeChannelName(name)
                                if (normalized.isNotBlank()) result.putIfAbsent(normalized, id)
                            }
                        }
                        "channel" -> currentId = null
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    /** Segunda passagem: lê os programas de verdade, só guardando os que
     * pertencem aos IDs de canal que já sabemos que interessam (achados
     * na primeira passagem, por tvg-id direto ou por nome batendo) -- pra
     * não gastar memória guardando a programação de um guia inteiro à
     * toa quando só interessam alguns canais. */
    fun parse(xml: String, channelIdsFilter: Set<String>? = null): Map<String, List<XmlTvProgramme>> {
        val result = mutableMapOf<String, MutableList<XmlTvProgramme>>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var currentChannel: String? = null
        var currentStart: Long? = null
        var currentStop: Long? = null
        var currentTitle: StringBuilder? = null
        var inTitle = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            currentChannel = parser.getAttributeValue(null, "channel")
                            currentStart = parser.getAttributeValue(null, "start")?.let { parseTime(it) }
                            currentStop = parser.getAttributeValue(null, "stop")?.let { parseTime(it) }
                            currentTitle = null
                        }
                        "title" -> {
                            if (currentChannel != null) {
                                inTitle = true
                                currentTitle = StringBuilder()
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) currentTitle?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title" -> inTitle = false
                        "programme" -> {
                            val channel = currentChannel?.lowercase()
                            val start = currentStart
                            val stop = currentStop
                            if (channel != null && start != null && stop != null &&
                                (channelIdsFilter == null || channel in channelIdsFilter)
                            ) {
                                result.getOrPut(channel) { mutableListOf() }.add(
                                    XmlTvProgramme(
                                        channelId = channel,
                                        startMillis = start,
                                        stopMillis = stop,
                                        title = currentTitle?.toString()?.trim().orEmpty().ifBlank { "Sem título" }
                                    )
                                )
                            }
                            currentChannel = null
                            currentStart = null
                            currentStop = null
                            currentTitle = null
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }
}
