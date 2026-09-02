package com.meuapp.iptvplayer.data.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private data class TmdbSearchResponse(@SerializedName("results") val results: List<TmdbResult>?)
private data class TmdbResult(@SerializedName("poster_path") val posterPath: String?)

/** Busca a capa "de verdade" (pôster oficial) de uma série no TMDB pelo
 * nome -- muitos painéis/listas M3U só têm uma imagem genérica (ou a
 * mesma logo repetida em todo episódio) como capa de série; o TMDB tem o
 * pôster de divulgação de verdade, igual todo app de streaming usa. */
class TmdbRepository {

    companion object {
        // Mesma chave já usada no projeto "Future" do usuário.
        private const val API_KEY = "aad81d5ba22644702893f3a88f6a08c1"
        // Cache compartilhado entre todas as instâncias/telas -- uma vez
        // encontrado (ou confirmado que não existe) o pôster de uma série,
        // não precisa buscar de novo.
        private val posterCache = mutableMapOf<String, String?>()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /** Limpa sufixos comuns que atrapalham a busca (qualidade, idioma,
     * tags de legenda/dublado) antes de mandar pro TMDB. */
    private fun cleanName(name: String): String =
        name
            .replace(Regex("(?i)\\b(dublado|legendado|dub|leg)\\b"), "")
            .replace(Regex("(?i)\\b(4k|fhd|hd|sd|h265|h264)\\b"), "")
            .replace(Regex("[\\[({].*?[\\])}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    suspend fun findSeriesPosterUrl(rawName: String): String? = withContext(Dispatchers.IO) {
        val name = cleanName(rawName)
        if (name.isBlank()) return@withContext null
        val cacheKey = name.lowercase()
        if (posterCache.containsKey(cacheKey)) return@withContext posterCache[cacheKey]

        val poster = runCatching {
            val query = URLEncoder.encode(name, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$API_KEY&language=pt-BR&query=$query"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (body.isNullOrBlank()) return@runCatching null
            val parsed = gson.fromJson(body, TmdbSearchResponse::class.java)
            parsed.results?.firstOrNull { !it.posterPath.isNullOrBlank() }?.posterPath
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
        }.getOrNull()

        posterCache[cacheKey] = poster
        poster
    }
}
