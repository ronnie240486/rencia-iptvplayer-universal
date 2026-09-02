package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.DeviceCheckResponse
import com.meuapp.iptvplayer.data.model.UltraConfigResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RenciaRepository {
    companion object {
        const val BASE_URL = "https://renciaapp.manus.space/"
        const val APP_ID = "rencia"
    }

    private val api: RenciaApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RenciaApiService::class.java)

    // Login por USUÁRIO/SENHA não existe mais nesse app -- o painel de
    // referência (que o usuário mandou) só ativa por MAC do aparelho.
    // Mantido só pra não quebrar chamadas antigas, mas sempre falha.
    suspend fun authenticateCustomer(login: String, password: String): Result<Session> = runCatching {
        error("Este aplicativo usa login por MAC.")
    }

    suspend fun verifyCustomerAccess(login: String, password: String): Result<Session> =
        authenticateCustomer(login, password)

    /** Fluxo real de ativação: MAC do aparelho -> painel confirma acesso
     * (checkDevice) -> devolve a URL da playlist Xtream já liberada pra
     * esse MAC. Se checkDevice não trouxer a URL, tenta a fonte alternativa
     * (getPlaylistSources) antes de desistir. */
    suspend fun authenticateByMac(rawMac: String): Result<Session> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido. O aparelho deve exibir 12 dígitos hexadecimais.")
        val deviceResponse = api.checkDevice(mac)
        if (!deviceResponse.isSuccessful) error("Não foi possível verificar o acesso (HTTP ${deviceResponse.code()})")
        val deviceCheck = deviceResponse.body() ?: error("Resposta inválida do servidor")
        if (!deviceCheck.found) error("Este MAC não está cadastrado no painel.")
        if (!deviceCheck.allowed) error("Acesso bloqueado para este dispositivo${deviceCheck.status?.let { " ($it)" } ?: ""}.")

        val playlistUrl = deviceCheck.urlM3u8?.takeIf { it.isNotBlank() }
            ?: fetchFallbackPlaylistUrl(mac)
            ?: error("Nenhuma playlist foi liberada para este MAC.")

        sessionFromPlaylistUrl(playlistUrl, mac, deviceCheck.app, deviceCheck.status, deviceCheck.expirationDate)
    }

    /** Alguns dispositivos só têm a playlist cadastrada na fonte alternativa
     * (guim.php), não no checkDevice principal -- tenta essa antes de
     * desistir de vez. */
    private suspend fun fetchFallbackPlaylistUrl(mac: String): String? = runCatching {
        val response = api.getPlaylistSources(mac)
        if (!response.isSuccessful) return null
        response.body()?.data?.firstNotNullOfOrNull { source ->
            source.url?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun sessionFromPlaylistUrl(
        playlistUrl: String,
        mac: String,
        appName: String?,
        status: String?,
        expirationDate: String?,
    ): Session {
        val url = playlistUrl.toHttpUrlOrNull()
            ?: error("A playlist recebida não possui uma URL válida.")
        val username = url.queryParameter("username")?.trim().orEmpty()
        val password = url.queryParameter("password")?.trim().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            error("A playlist deve ser uma URL Xtream com username e password.")
        }
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        val serverUrl = "${url.scheme}://${url.host}$port"
        return Session(
            mac = mac,
            serverUrl = serverUrl,
            username = username,
            password = password,
            status = status ?: "active",
            expirationDate = expirationDate,
            appName = appName ?: "Rencia",
            clientLogin = null,
            clientPassword = null,
            layoutId = "classic",
            playlistUrl = playlistUrl
        )
    }

    /** Confere se a playlist ligada a este MAC mudou no painel (ex: o
     * usuário trocou de lista/servidor) e, se mudou, já devolve a sessão
     * ATUALIZADA pronta pra salvar -- sem isso, o app continuava usando o
     * servidor/usuário/senha antigos pra sempre, mesmo depois de trocar a
     * lista no painel, e toda tela dava erro de "playlist vazia" porque
     * ainda apontava pro servidor errado. Devolve null (dentro do Result de
     * sucesso) quando não muda nada -- não precisa salvar de novo. */
    suspend fun refreshSessionIfChanged(currentSession: Session): Result<Session?> = runCatching {
        val mac = normalizeMac(currentSession.mac) ?: return@runCatching null
        val deviceResponse = api.checkDevice(mac)
        if (!deviceResponse.isSuccessful) error("Não foi possível verificar o acesso (HTTP ${deviceResponse.code()})")
        val deviceCheck = deviceResponse.body() ?: error("Resposta inválida do servidor")
        if (!deviceCheck.found) error("Este MAC não está mais cadastrado no painel.")
        if (!deviceCheck.allowed) error("Acesso bloqueado para este dispositivo${deviceCheck.status?.let { " ($it)" } ?: ""}.")

        val playlistUrl = deviceCheck.urlM3u8?.takeIf { it.isNotBlank() }
            ?: fetchFallbackPlaylistUrl(mac)
            ?: error("Nenhuma playlist está liberada para este MAC.")

        if (playlistUrl == currentSession.playlistUrl) return@runCatching null

        sessionFromPlaylistUrl(playlistUrl, mac, deviceCheck.app, deviceCheck.status, deviceCheck.expirationDate)
    }

    suspend fun verifyAccess(rawMac: String): Result<DeviceCheckResponse> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = api.checkDevice(mac)
        if (!response.isSuccessful) error("Não foi possível verificar o acesso")
        response.body() ?: error("Resposta inválida do servidor")
    }

    suspend fun getUltraConfig(rawMac: String): Result<UltraConfigResponse> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = api.getUltraConfig(mac)
        if (!response.isSuccessful) error("Configuração visual indisponível")
        response.body() ?: error("Configuração visual vazia")
    }

    fun normalizeMac(rawMac: String): String? {
        val compact = rawMac.filter { it.isLetterOrDigit() }.uppercase()
        if (compact.length != 12 || compact.any { it !in "0123456789ABCDEF" }) return null
        return compact.chunked(2).joinToString(":")
    }
}
