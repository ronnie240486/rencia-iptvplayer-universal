package com.meuapp.iptvplayer.util

import android.widget.ImageView
import coil.load
import com.meuapp.iptvplayer.R

/** Alguns provedores cadastram ícone/pôster hospedado no Imgur. Quando
 * essa imagem foi apagada (ou o link direto é bloqueado), o Imgur NÃO
 * devolve um erro HTTP -- ele devolve uma imagem de aviso "removida",
 * com HTTP 200 normal. Isso faz o Coil carregar "com sucesso" uma imagem
 * quebrada, sem nunca disparar erro nenhum (então um simples ".error()"
 * não resolve). Por isso, em vez de tentar detectar isso depois de
 * carregado, o jeito confiável é simplesmente nunca tentar carregar nada
 * hospedado no Imgur -- cai direto no ícone padrão. */
fun isUnreliableIconHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "imgur.com" || host.endsWith(".imgur.com")
}

/** Carrega ícone/pôster (Canais, Filmes, Séries) com fallback pro ícone
 * padrão -- tanto quando o link é de um host não confiável (Imgur) quanto
 * quando o carregamento falha de verdade (link quebrado, 404 etc). Antes
 * disso, um ícone quebrado ficava simplesmente em branco/vazio na tela. */
fun ImageView.loadIconSafely(url: String?, crossfade: Boolean = false) {
    if (isUnreliableIconHost(url)) {
        setImageResource(R.drawable.ic_media_placeholder)
        return
    }
    load(url) {
        crossfade(crossfade)
        placeholder(R.drawable.ic_media_placeholder)
        error(R.drawable.ic_media_placeholder)
        fallback(R.drawable.ic_media_placeholder)
    }
}
