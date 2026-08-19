package com.meuapp.iptvplayer.ui.epg

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EpgTime {
    private val formats = listOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    )

    fun parse(raw: String): Date? {
        raw.toLongOrNull()?.let { numeric ->
            return Date(if (numeric < 1_000_000_000_000L) numeric * 1000 else numeric)
        }
        formats.forEach { format ->
            runCatching { format.parse(raw) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun millis(raw: String): Long? = parse(raw)?.time

    fun format(raw: String): String {
        val date = parse(raw) ?: return raw
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    }
}
