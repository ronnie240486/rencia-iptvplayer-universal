package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.DeviceCheckResponse
import com.meuapp.iptvplayer.data.model.UltraConfigResponse
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RenciaRepository {

    companion object {
        const val BASE_URL = "https://renciaapp.manus.space/"
    }

    private val api: RenciaApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RenciaApiService::class.java)

    suspend fun authenticateMac(rawMac: String): Result<Session> = runCatching {
        val mac = normalizeMac(rawMac)
            ?: error("Digite um MAC válido no formato AA:BB:CC:DD:EE:FF")
        val deviceResponse = api.checkDevice(mac)
        if (!deviceResponse.isSuccessful) error("Não foi possível validar este aparelho")
        val device = deviceResponse.body() ?: error("Resposta inválida do servidor")
        if (!device.found || !device.allowed) {
            error("Acesso indisponível para este aparelho")
        }

        val sourcesResponse = api.getPlaylistSources(mac)
        if (!sourcesResponse.isSuccessful) error("Não foi possível carregar a lista deste aparelho")
        val sources = sourcesResponse.body()?.data.orEmpty()
        val source = sources.firstOrNull { it.type.equals("xtream", ignoreCase = true) }
            ?: sources.firstOrNull()
            ?: error("Nenhuma lista foi atribuída a este aparelho")

        val serverUrl = source.url?.trim()?.trimEnd('/')
            ?: error("A lista atribuída não possui servidor configurado")
        val username = source.username?.trim().orEmpty()
        val password = source.password?.trim().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            error("A lista atribuída não possui credenciais válidas")
        }

        Session(
            mac = mac,
            serverUrl = serverUrl,
            username = username,
            password = password,
            status = device.status,
            expirationDate = device.expirationDate,
            appName = device.app
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
        val compact = rawMac
            .filter { it.isLetterOrDigit() }
            .uppercase()
        if (compact.length != 12 || compact.any { it !in "0123456789ABCDEF" }) return null
        return compact.chunked(2).joinToString(":")
    }
}
