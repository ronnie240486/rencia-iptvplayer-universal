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
private data class TmdbResult(
    @SerializedName("id") val id: Int?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("overview") val overview: String?
)
private data class TmdbEpisodeResponse(@SerializedName("overview") val overview: String?)

/** Pôster + sinopse + ID (pra depois buscar sinopse de episódio) de uma
 * série, tudo numa chamada só ao TMDB. */
data class TmdbSeriesDetails(val posterUrl: String?, val overview: String?, val tmdbId: Int?)

/** Busca a capa "de verdade" (pôster oficial) de uma série no TMDB pelo
 * nome -- muitos painéis/listas M3U só têm uma imagem genérica (ou a
 * mesma logo repetida em todo episódio) como capa de série; o TMDB tem o
 * pôster de divulgação de verdade, igual todo app de streaming usa.
 *
 * TAMBÉM busca sinopse (série, filme e episódio) -- listas M3U não têm
 * esse tipo de informação (só nome/categoria/logo/link), então pra
 * mostrar sinopse pro usuário a única fonte possível é buscar por nome
 * numa base externa como o TMDB. */
class TmdbRepository {

    companion object {
        // Mesma chave já usada no projeto "Future" do usuário.
        private const val API_KEY = "aad81d5ba22644702893f3a88f6a08c1"
        // Cache compartilhado entre todas as instâncias/telas -- uma vez
        // encontrado (ou confirmado que não existe) o pôster/sinopse de um
        // título, não precisa buscar de novo.
        private val seriesDetailsCache = mutableMapOf<String, TmdbSeriesDetails?>()
        private val movieOverviewCache = mutableMapOf<String, String?>()
        private val episodeOverviewCache = mutableMapOf<String, String?>() // "tmdbId|temporada|episodio" -> sinopse
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

    /** Pôster + sinopse + ID da série no TMDB, numa chamada só. */
    suspend fun findSeriesDetails(rawName: String): TmdbSeriesDetails? = withContext(Dispatchers.IO) {
        val name = cleanName(rawName)
        if (name.isBlank()) return@withContext null
        val cacheKey = name.lowercase()
        if (seriesDetailsCache.containsKey(cacheKey)) return@withContext seriesDetailsCache[cacheKey]

        val details = runCatching {
            val query = URLEncoder.encode(name, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$API_KEY&language=pt-BR&query=$query"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (body.isNullOrBlank()) return@runCatching null
            val parsed = gson.fromJson(body, TmdbSearchResponse::class.java)
            val best = parsed.results?.firstOrNull()
            best?.let {
                TmdbSeriesDetails(
                    posterUrl = it.posterPath?.takeIf { p -> p.isNotBlank() }?.let { p -> "https://image.tmdb.org/t/p/w500$p" },
                    overview = it.overview?.takeIf { o -> o.isNotBlank() },
                    tmdbId = it.id
                )
            }
        }.getOrNull()

        seriesDetailsCache[cacheKey] = details
        details
    }

    /** Mantida pra quem já chama só a capa (cards da lista de séries) --
     * por baixo já reaproveita o cache de findSeriesDetails, não faz uma
     * segunda chamada à toa. */
    suspend fun findSeriesPosterUrl(rawName: String): String? = findSeriesDetails(rawName)?.posterUrl

    /** Sinopse de um filme, buscada pelo nome. */
    suspend fun findMovieOverview(rawName: String): String? = withContext(Dispatchers.IO) {
        val name = cleanName(rawName)
        if (name.isBlank()) return@withContext null
        val cacheKey = name.lowercase()
        if (movieOverviewCache.containsKey(cacheKey)) return@withContext movieOverviewCache[cacheKey]

        val overview = runCatching {
            val query = URLEncoder.encode(name, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$API_KEY&language=pt-BR&query=$query"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (body.isNullOrBlank()) return@runCatching null
            val parsed = gson.fromJson(body, TmdbSearchResponse::class.java)
            parsed.results?.firstOrNull()?.overview?.takeIf { it.isNotBlank() }
        }.getOrNull()

        movieOverviewCache[cacheKey] = overview
        overview
    }

    /** Sinopse de UM episódio específico -- precisa do ID da série no TMDB
     * (obtido via findSeriesDetails) + número da temporada/episódio, que
     * já vêm do nome do arquivo na playlist M3U (SxxExx). */
    suspend fun findEpisodeOverview(tmdbSeriesId: Int, season: Int, episode: Int): String? = withContext(Dispatchers.IO) {
        val cacheKey = "$tmdbSeriesId|$season|$episode"
        if (episodeOverviewCache.containsKey(cacheKey)) return@withContext episodeOverviewCache[cacheKey]

        val overview = runCatching {
            val url = "https://api.themoviedb.org/3/tv/$tmdbSeriesId/season/$season/episode/$episode?api_key=$API_KEY&language=pt-BR"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (body.isNullOrBlank()) return@runCatching null
            val parsed = gson.fromJson(body, TmdbEpisodeResponse::class.java)
            parsed.overview?.takeIf { it.isNotBlank() }
        }.getOrNull()

        episodeOverviewCache[cacheKey] = overview
        overview
    }
}
