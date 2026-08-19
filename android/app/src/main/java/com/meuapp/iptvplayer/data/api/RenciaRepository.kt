package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.DeviceCheckResponse
import com.meuapp.iptvplayer.data.model.RenciaLoginResponse
import com.meuapp.iptvplayer.data.model.UltraConfigResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RenciaRepository {
    companion object {
        const val BASE_URL = "https://renciaapp-7uusyuwz.manus.space/"
        const val APP_ID = "rencia"
    }

    private val api: RenciaApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RenciaApiService::class.java)

    suspend fun authenticateCustomer(login: String, password: String): Result<Session> = runCatching {
        require(login.isNotBlank() && password.isNotBlank()) { "Informe usuário e senha." }
        val response = api.loginCustomer(APP_ID, mapOf("login" to login.trim(), "password" to password))
        val config = response.body()
        if (!response.isSuccessful || config == null) error(config?.error ?: "Usuário ou senha inválidos.")
        if (!config.registered || !config.allowed) error(config.blockMessage ?: "Este acesso está indisponível.")
        toSession(config, login.trim(), password)
    }

    suspend fun verifyCustomerAccess(login: String, password: String): Result<Session> =
        authenticateCustomer(login, password)

    private fun toSession(config: RenciaLoginResponse, clientLogin: String, clientPassword: String): Session {
        val playlistUrl = config.playlistUrls.firstOrNull { it.isNotBlank() }
            ?: error("Nenhuma playlist foi liberada para esta conta.")
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
            mac = config.mac.orEmpty(),
            serverUrl = serverUrl,
            username = username,
            password = password,
            status = "active",
            expirationDate = null,
            appName = config.appName ?: "Rencia",
            clientLogin = clientLogin,
            clientPassword = clientPassword,
            layoutId = config.layoutId ?: "classic",
            playlistUrl = playlistUrl
        )
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
