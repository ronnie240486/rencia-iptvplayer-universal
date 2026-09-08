package com.meuapp.iptvplayer.data.api

import com.meuapp.iptvplayer.data.model.AppConfigResponse
import com.meuapp.iptvplayer.data.model.AppUpdateResponse
import com.meuapp.iptvplayer.data.model.HeartbeatResponse
import com.meuapp.iptvplayer.data.model.ListNotificationsResponse
import com.meuapp.iptvplayer.data.model.PlaybackFailureResponse
import com.meuapp.iptvplayer.data.model.RemoteCommand
import com.meuapp.iptvplayer.data.model.UltraConfigResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RenciaRepository {
    companion object {
        const val BASE_URL = "https://renciaapp.manus.space/"
        // "supremus" é o app_id oficial pra esse app (Supreme) no
        // documento de integração universal do painel -- id errado
        // ("rencia") fazia o app cair sempre nas rotas de compatibilidade
        // antigas, em vez da rota oficial nova (mais completa e confiável).
        const val APP_ID = "supremus"

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

    /** Busca a configuração completa oficial (rota prioritária pra apps
     * novos) -- traz status do MAC, mensagens, imagens, ícones e listas
     * ativas, tudo de uma vez. */
    suspend fun fetchAppConfig(mac: String): AppConfigResponse? = kotlinx.coroutines.withTimeoutOrNull(8_000) {
        runCatching {
            val response = api.getAppConfig(APP_ID, mac)
            if (!response.isSuccessful) return@runCatching null
            response.body()
        }.getOrNull()
    }

    /** Fluxo real de ativação: MAC do aparelho -> rota oficial de
     * configuração confirma acesso -> devolve a URL da playlist Xtream já
     * liberada pra esse MAC. Se a rota oficial não tiver a URL, tenta a
     * fonte alternativa (getPlaylistSources) antes de desistir. Se a
     * própria rota oficial não responder (painel antigo, aparelho ainda
     * não migrado), cai pra rota de compatibilidade antiga. */
    suspend fun authenticateByMac(rawMac: String): Result<Session> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido. O aparelho deve exibir 12 dígitos hexadecimais.")

        val config = fetchAppConfig(mac)
        if (config != null) {
            if (!config.registered) error("Este MAC não está cadastrado no painel.")
            if (!config.allowed) error("Acesso bloqueado para este dispositivo${config.status?.let { " ($it)" } ?: ""}.")
            val playlistUrl = config.playlistUrls.firstOrNull { it.isNotBlank() }
                ?: fetchFallbackPlaylistUrl(mac)
                ?: error("Nenhuma playlist foi liberada para este MAC.")
            return@runCatching sessionFromPlaylistUrl(playlistUrl, mac, config.appName, config.status, config.expirationDate)
        }

        // Rota oficial não respondeu -- cai pra rota antiga de
        // compatibilidade (checkDevice), pra não deixar o app sem
        // funcionar em painéis mais antigos.
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
     * (guim.php), não na rota de configuração principal -- tenta essa
     * antes de desistir de vez. */
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
        activeListNumber: Int = 1,
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
            playlistUrl = playlistUrl,
            activeListNumber = activeListNumber
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

        val config = fetchAppConfig(mac)
        if (config != null) {
            if (!config.registered) error("Este MAC não está mais cadastrado no painel.")
            if (!config.allowed) error("Acesso bloqueado para este dispositivo${config.status?.let { " ($it)" } ?: ""}.")
            val playlistUrl = config.playlistUrls.firstOrNull { it.isNotBlank() }
                ?: fetchFallbackPlaylistUrl(mac)
                ?: error("Nenhuma playlist está liberada para este MAC.")
            if (playlistUrl == currentSession.playlistUrl) return@runCatching null
            return@runCatching sessionFromPlaylistUrl(playlistUrl, mac, config.appName, config.status, config.expirationDate)
        }

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

    // ---------------------------------------------------------------
    // Rotas novas do documento de integração universal
    // ---------------------------------------------------------------

    /** Verifica se tem atualização nova do app -- chamar na abertura e
     * quando o usuário abrir a área de atualização manualmente. */
    suspend fun checkForUpdate(rawMac: String): Result<AppUpdateResponse> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = kotlinx.coroutines.withTimeout(10_000) { api.getAppUpdate(APP_ID, mac) }
        if (!response.isSuccessful) error("Não foi possível verificar atualização")
        response.body() ?: error("Resposta de atualização vazia")
    }

    /** Chamar imediatamente ao trocar de canal/filme/série, e de novo a
     * cada 60s enquanto o mesmo conteúdo continuar tocando -- mantém o
     * aparelho "online" no painel e registra o que está sendo assistido. */
    suspend fun sendHeartbeat(rawMac: String, currentContent: String? = null): Result<HeartbeatResponse> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = kotlinx.coroutines.withTimeout(10_000) { api.sendHeartbeat(mac, currentContent) }
        if (!response.isSuccessful) error("Heartbeat falhou (HTTP ${response.code()})")
        response.body() ?: error("Resposta de heartbeat vazia")
    }

    /** Chamar junto do heartbeat, a cada 60s, e quando o app volta pro
     * primeiro plano -- traz avisos técnicos, vencimento da conta e
     * estado de troca automática de lista (failover). */
    suspend fun getListNotifications(rawMac: String): Result<ListNotificationsResponse> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = kotlinx.coroutines.withTimeout(10_000) { api.getListNotifications(mac) }
        if (!response.isSuccessful) error("Não foi possível buscar avisos")
        response.body() ?: error("Resposta de avisos vazia")
    }

    /** Confirma que um aviso já foi mostrado pro usuário -- não apaga o
     * aviso do painel, só registra a leitura no aparelho. */
    suspend fun ackListNotification(rawMac: String, alertId: String): Result<Unit> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        api.ackListNotification(mapOf("mac" to mac, "alert_id" to alertId))
        Unit
    }

    /** Reportar SÓ quando o player detectar erro real de rede/timeout/
     * indisponibilidade -- nunca em pausa do usuário ou erro visual da
     * interface. O painel pode responder trocando a lista ativa
     * automaticamente (failover). */
    suspend fun reportPlaybackFailure(rawMac: String, activeListNumber: Int): Result<PlaybackFailureResponse> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = kotlinx.coroutines.withTimeout(10_000) { api.reportPlaybackFailure(mapOf("mac" to mac, "active_list_number" to activeListNumber)) }
        if (!response.isSuccessful) error("Não foi possível reportar a falha (HTTP ${response.code()})")
        response.body() ?: error("Resposta de falha de reprodução vazia")
    }

    suspend fun getRemoteCommands(rawMac: String): Result<List<RemoteCommand>> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val response = kotlinx.coroutines.withTimeout(10_000) { api.getRemoteCommands(mac) }
        if (!response.isSuccessful) return@runCatching emptyList()
        response.body()?.commands.orEmpty()
    }

    /** Confirma a execução (ou falha) de um comando remoto -- só confirma
     * comando que o app de fato executou. */
    suspend fun ackRemoteCommand(rawMac: String, commandId: String, executed: Boolean, resultMessage: String? = null): Result<Unit> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val body = mutableMapOf(
            "mac" to mac,
            "command_id" to commandId,
            "status" to if (executed) "executed" else "failed"
        )
        resultMessage?.let { body["result_message"] = it }
        api.ackRemoteCommand(body)
        Unit
    }

    /** Uma lista/playlist disponível para o MAC, com um rótulo legível pra
     * mostrar no seletor de "trocar de lista". */
    data class PlaylistOption(val label: String, val playlistUrl: String)

    /** Alguns paineis cadastram MAIS DE UMA lista pro mesmo MAC (ex: lista
     * principal + listas extras/backup). Junta a lista principal (rota
     * oficial de configuração, com fallback pro checkDevice antigo) com as
     * alternativas (getPlaylistSources), sem repetir URLs iguais. */
    suspend fun listAvailablePlaylists(rawMac: String): Result<List<PlaylistOption>> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val options = mutableListOf<PlaylistOption>()

        val config = fetchAppConfig(mac)
        if (config != null) {
            config.playlistUrls.forEachIndexed { index, url ->
                if (url.isNotBlank() && options.none { it.playlistUrl == url }) {
                    options.add(PlaylistOption(if (index == 0) "Lista principal" else "Lista ${index + 1}", url))
                }
            }
        } else {
            runCatching { api.checkDevice(mac) }.getOrNull()?.body()?.urlM3u8
                ?.takeIf { it.isNotBlank() }
                ?.let { options.add(PlaylistOption("Lista principal", it)) }
        }

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
     * (em vez de sempre a "principal" que a configuração devolve) --
     * usado pelo seletor "trocar de lista" em Ajustes. */
    suspend fun switchToPlaylist(rawMac: String, playlistUrl: String): Result<Session> = runCatching {
        val mac = normalizeMac(rawMac) ?: error("MAC inválido")
        val config = fetchAppConfig(mac)
        if (config != null) {
            return@runCatching sessionFromPlaylistUrl(playlistUrl, mac, config.appName, config.status, config.expirationDate)
        }
        val deviceCheck = runCatching { api.checkDevice(mac) }.getOrNull()?.body()
        sessionFromPlaylistUrl(playlistUrl, mac, deviceCheck?.app, deviceCheck?.status, deviceCheck?.expirationDate)
    }

    suspend fun verifyAccess(rawMac: String): Result<com.meuapp.iptvplayer.data.model.DeviceCheckResponse> = runCatching {
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
