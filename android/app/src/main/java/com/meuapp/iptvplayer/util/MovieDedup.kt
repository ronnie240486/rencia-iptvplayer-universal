package com.meuapp.iptvplayer.util

import java.text.Normalizer

/** Chave "de comparação" de um filme -- tira acentos, tags de versão
 * (Legendado/Dublado/Dual Áudio/Nacional) e pontuação do nome, deixando
 * só o essencial pra comparar. Não mexe no nome EXIBIDO, só usada pra
 * detectar duplicata. Mesma lógica usada no Fusion pra parar de mostrar
 * "Filme X" e "Filme X (Legendado)" como se fossem dois filmes
 * diferentes. */
fun movieDedupNameKey(rawName: String): String {
    val noAccents = Normalizer.normalize(rawName, Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .lowercase()
    val noTags = noAccents.replace(
        Regex("\\b(legendado|legendada|leg|dublado|dublada|dub|dual\\s*audio|nacional)\\b"),
        ""
    )
    return noTags.replace(Regex("[^a-z0-9]+"), " ").trim()
}

/** Remove filmes repetidos (mesmo filme, só mudando a versão -- Legendado,
 * Dublado, Dual Áudio) mantendo a PRIMEIRA ocorrência de cada um (ordem
 * do provedor preservada). Nomes que ficam vazios depois de normalizar
 * (raro) não são filtrados, pra nunca sumir um filme por engano. */
fun <T> dedupeByMovieName(items: List<T>, nameOf: (T) -> String): List<T> {
    val seen = HashSet<String>()
    val result = ArrayList<T>(items.size)
    for (item in items) {
        val key = movieDedupNameKey(nameOf(item))
        if (key.isBlank() || seen.add(key)) {
            result.add(item)
        }
    }
    return result
}
