package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.AuthResponse
import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.data.model.SeriesInfoResponse
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.data.model.ShortEpgResponse
import com.meuapp.iptvplayer.data.model.VodStream
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Interface genérica de consumo da API Xtream Codes (player_api.php).
 * Compatível com qualquer provedor que implemente esse padrão de API
 * (é um padrão de mercado, não específico de nenhum app).
 */
interface XtreamApiService {

    @GET
    suspend fun authenticate(@Url fullUrl: String): Response<AuthResponse>

    @GET
    suspend fun getLiveCategories(@Url fullUrl: String): Response<List<Category>>

    @GET
    suspend fun getLiveStreams(@Url fullUrl: String): Response<List<LiveStream>>

    @GET
    suspend fun getShortEpg(@Url fullUrl: String): Response<ShortEpgResponse>

    @GET
    suspend fun getVodCategories(@Url fullUrl: String): Response<List<Category>>

    @GET
    suspend fun getVodStreams(@Url fullUrl: String): Response<List<VodStream>>

    @GET
    suspend fun getSeriesCategories(@Url fullUrl: String): Response<List<Category>>

    @GET
    suspend fun getSeries(@Url fullUrl: String): Response<List<SeriesItem>>

    @GET
    suspend fun getSeriesInfo(@Url fullUrl: String): Response<SeriesInfoResponse>
}
