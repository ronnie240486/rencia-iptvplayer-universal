package com.meuapp.iptvplayer.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.meuapp.iptvplayer.data.model.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RadioRepository {

    companion object {
        private const val API_BASE = "https://de1.api.radio-browser.info"
        val CATEGORY_TAGS: LinkedHashMap<String, List<String>> = linkedMapOf(
            "Gospel" to listOf("gospel", "christian", "worship", "religious", "praise", "contemporary christian"),
            "Sertaneja" to listOf("sertanejo", "sertaneja", "brazilian"),
            "Pop" to listOf("pop"),
            "Rock" to listOf("rock"),
            "Heavy Metal" to listOf("heavy metal", "heavymetal", "metal"),
            "Jazz" to listOf("jazz"),
            "Blues" to listOf("blues"),
            "Esportes" to listOf("sports", "sport")
        )

        val CATEGORY_LIMITS: Map<String, Int> = mapOf(
            "Gospel" to 200,
            "Sertaneja" to 100,
            "Pop" to 100,
            "Rock" to 100,
            "Heavy Metal" to 100,
            "Jazz" to 100,
            "Blues" to 100,
            "Esportes" to 100
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val stationListType = object : TypeToken<List<RadioStation>>() {}.type

    suspend fun getCategory(category: String): Result<List<RadioStation>> = runCatching {
        val tags = CATEGORY_TAGS[category].orEmpty()
        val limit = CATEGORY_LIMITS[category] ?: 100
        val stations = linkedMapOf<String, RadioStation>()
        for (tag in tags) {
            fetchByTag(tag, limit).forEach { station ->
                val key = station.stationUuid?.takeIf { it.isNotBlank() }
                    ?: "${station.name}|${station.urlResolved}"
                if (!stations.containsKey(key) && station.isPlayable()) {
                    stations[key] = station
                }
            }
            if (stations.size >= limit) break
        }
        stations.values.take(limit)
    }

    suspend fun markClick(station: RadioStation) {
        val uuid = station.stationUuid?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$API_BASE/json/url/${java.net.URLEncoder.encode(uuid, "UTF-8")}")
                    .header("User-Agent", "SUPREME/1.0")
                    .get()
                    .build()
                client.newCall(request).execute().use { }
            }
        }
    }

    private suspend fun fetchByTag(tag: String, limit: Int): List<RadioStation> = withContext(Dispatchers.IO) {
        val url = "$API_BASE/json/stations/search".toHttpUrl().newBuilder()
            .addQueryParameter("tag", tag)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("order", "clickcount")
            .addQueryParameter("reverse", "true")
            .addQueryParameter("hidebroken", "true")
            .addQueryParameter("is_https", "true")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SUPREME/1.0")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Radio Browser HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            gson.fromJson<List<RadioStation>>(body, stationListType) ?: emptyList()
        }
    }

    private fun RadioStation.isPlayable(): Boolean =
        !urlResolved.isNullOrBlank() && lastCheckOk != 0
}
