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

        // Cada tela (Canais, Filmes, Séries) confere se a lista mudou no
        // painel toda vez que abre -- isso é bom pra detectar troca de
        // lista rápido, mas se o usuário for de Canais pra Filmes pra
        // Séries em sequência, isso significa 3 chamadas de rede seguidas
        // só pra confirmar "não mudou nada", deixando tudo mais lento sem
        // necessidade. Só faz essa checagem de verdade se already fez uma
        // esse MAC há mais de alguns minutos.
        private const val REFRESH_THROTTLE_MS = 3 * 60_000L
        private val lastRefreshCheckAt = mutableMapOf<String, Long>()
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
        val now = System.currentTimeMillis()
        val lastChecked = lastRefreshCheckAt[mac] ?: 0L
        if (now - lastChecked < REFRESH_THROTTLE_MS) return@runCatching null
        lastRefreshCheckAt[mac] = now

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

    /** Uma lista/playlist disponível para o MAC, com um rótulo legível pra
     * mostrar no seletor de "trocar de lista". */
    data class PlaylistOption(val label: String, val playlistUrl: String)

    /** Alguns paineis cadastram MAIS DE UMA lista pro mesmo MAC (ex: lista
     * principal + listas extras/backup). Junta a lista principal
     * (checkDevice) com as alternativas (getPlaylistSources), sem repetir
     * URLs iguais. */
    suspend fun listAvailablePlaylists(rawMac: String): Result<List<PlaylistOption>> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val options = mutableListOf<PlaylistOption>()

        runCatching { api.checkDevice(mac) }.getOrNull()?.body()?.urlM3u8
            ?.takeIf { it.isNotBlank() }
            ?.let { options.add(PlaylistOption("Lista principal", it)) }

        runCatching { api.getPlaylistSources(mac) }.getOrNull()?.body()?.data
            ?.forEachIndexed { index, source ->
                val url = source.url?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                if (options.none { it.playlistUrl == url }) {
                    val label = source.type?.takeIf { it.isNotBlank() }
                        ?.let { "Lista: $it" } ?: "Lista alternativa ${index + 1}"
                    options.add(PlaylistOption(label, url))
                }
            }

        if (options.isEmpty()) error("Nenhuma lista encontrada para este MAC.")
        options
    }

    /** Troca a sessão ativa pra usar explicitamente a playlist escolhida
     * (em vez de sempre a "principal" que o checkDevice devolve) --
     * usado pelo seletor "trocar de lista" em Ajustes. */
    suspend fun switchToPlaylist(rawMac: String, playlistUrl: String): Result<Session> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val deviceCheck = runCatching { api.checkDevice(mac) }.getOrNull()?.body()
        sessionFromPlaylistUrl(playlistUrl, mac, deviceCheck?.app, deviceCheck?.status, deviceCheck?.expirationDate)
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
