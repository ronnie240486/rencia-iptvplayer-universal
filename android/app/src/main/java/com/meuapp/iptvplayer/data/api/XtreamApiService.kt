package com.meuapp.iptvplayer.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Interface genérica de consumo da API Xtream Codes (player_api.php).
 * Compatível com qualquer provedor que implemente esse padrão de API
 * (é um padrão de mercado, não específico de nenhum app).
 *
 * Todas as chamadas retornam o corpo CRU (ResponseBody) em vez de deixar o
 * Retrofit converter automaticamente pra Gson -- se o servidor devolver
 * algo vazio/inesperado (comum quando usuário/senha ou URL do servidor
 * estão errados), a conversão automática falhava com um erro genérico
 * ("unexpected end of stream") sem dizer o que realmente aconteceu. Agora o
 * corpo é lido primeiro e o parse acontece manualmente em XtreamRepository,
 * com uma mensagem de erro que mostra o problema de verdade.
 */
interface XtreamApiService {

    @GET
    suspend fun call(@Url fullUrl: String): Response<ResponseBody>
}
