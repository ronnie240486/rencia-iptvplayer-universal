package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.DeviceCheckResponse
import com.meuapp.iptvplayer.data.model.PlaylistSourcesResponse
import com.meuapp.iptvplayer.data.model.UltraConfigResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

interface RenciaApiService {

    @GET("/api/device/check")
    suspend fun checkDevice(@Query("mac") mac: String): Response<DeviceCheckResponse>

    @GET("/api/guim.php")
    suspend fun getPlaylistSources(@Query("mac") mac: String): Response<PlaylistSourcesResponse>

    @GET("/api/v5/ultra-config")
    suspend fun getUltraConfig(@Query("mac") mac: String): Response<UltraConfigResponse>

    @GET
    suspend fun getDeviceCheck(@Url fullUrl: String): Response<DeviceCheckResponse>
}
