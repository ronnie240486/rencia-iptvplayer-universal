package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.AppConfigResponse
import com.meuapp.iptvplayer.data.model.AppUpdateResponse
import com.meuapp.iptvplayer.data.model.DeviceCheckResponse
import com.meuapp.iptvplayer.data.model.HeartbeatResponse
import com.meuapp.iptvplayer.data.model.ListNotificationsResponse
import com.meuapp.iptvplayer.data.model.PlaybackFailureResponse
import com.meuapp.iptvplayer.data.model.PlaylistSourcesResponse
import com.meuapp.iptvplayer.data.model.RemoteCommandsResponse
import com.meuapp.iptvplayer.data.model.RenciaLoginResponse
import com.meuapp.iptvplayer.data.model.SimpleAckResponse
import com.meuapp.iptvplayer.data.model.UltraConfigResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface RenciaApiService {
    @POST("/api/v5/apps/{appId}/login")
    suspend fun loginCustomer(
        @Path("appId") appId: String,
        @Body credentials: Map<String, String>
    ): Response<RenciaLoginResponse>

    // Rota OFICIAL/prioritária pra apps novos (documento de integração
    // universal) -- traz status do MAC, mensagens, imagens dinâmicas,
    // ícones, preferências do player e as listas ativas, tudo de uma vez.
    @GET("/api/v5/apps/{appId}/config")
    suspend fun getAppConfig(
        @Path("appId") appId: String,
        @Query("mac") mac: String
    ): Response<AppConfigResponse>

    @GET("/api/v5/apps/{appId}/update")
    suspend fun getAppUpdate(
        @Path("appId") appId: String,
        @Query("mac") mac: String
    ): Response<AppUpdateResponse>

    @GET("/api/v5/heartbeat")
    suspend fun sendHeartbeat(
        @Query("mac") mac: String,
        @Query("current_content") currentContent: String?
    ): Response<HeartbeatResponse>

    @GET("/api/v5/list-notifications")
    suspend fun getListNotifications(@Query("mac") mac: String): Response<ListNotificationsResponse>

    @POST("/api/v5/list-notifications/ack")
    suspend fun ackListNotification(@Body body: Map<String, String>): Response<SimpleAckResponse>

    @POST("/api/v5/playback-failure")
    suspend fun reportPlaybackFailure(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<PlaybackFailureResponse>

    @GET("/api/v5/remote-commands")
    suspend fun getRemoteCommands(@Query("mac") mac: String): Response<RemoteCommandsResponse>

    @POST("/api/v5/remote-commands/ack")
    suspend fun ackRemoteCommand(@Body body: Map<String, String>): Response<SimpleAckResponse>

    // Rotas antigas/de compatibilidade -- mantidas como reserva pra
    // paineis/aparelhos que ainda dependem delas.
    @GET("/api/device/check")
    suspend fun checkDevice(@Query("mac") mac: String): Response<DeviceCheckResponse>

    @GET("/api/guim.php")
    suspend fun getPlaylistSources(@Query("mac") mac: String): Response<PlaylistSourcesResponse>

    @GET("/api/v5/ultra-config")
    suspend fun getUltraConfig(@Query("mac") mac: String): Response<UltraConfigResponse>

    @GET
    suspend fun getDeviceCheck(@Url fullUrl: String): Response<DeviceCheckResponse>
}
