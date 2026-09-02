package com.meuapp.iptvplayer.data.api

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
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

    /** Devolve os programas agrupados por ID de canal (o mesmo valor do
     * tvg-id da playlist M3U) -- só mantém canais/`ids` pedidos, pra não
     * gastar memória guardando um guia inteiro (que pode ter centenas de
     * canais) quando só interessam alguns poucos por vez. */
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
                            val channel = currentChannel
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
