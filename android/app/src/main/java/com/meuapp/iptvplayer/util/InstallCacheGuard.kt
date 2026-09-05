package com.meuapp.iptvplayer.util

import android.content.Context

/** Limpa o cache da lista (M3U) sempre que detecta uma instalação nova do
 * APK -- usa PackageInfo.lastUpdateTime, que o próprio Android atualiza
 * sozinho toda vez que o app é instalado/reinstalado/atualizado (mesmo
 * quando o usuário só instala por cima, sem desinstalar antes -- o cache
 * do app às vezes sobrevive nesse caso, o que fazia parecer que "instalar
 * de novo" pulava o carregamento completo). Só fechar e abrir o app de
 * novo (sem reinstalar nada) mantém o mesmo valor, então o cache continua
 * valendo nesse caso, entrando instantâneo como deveria. */
object InstallCacheGuard {
    private const val PREFS = "supremus_install_guard"
    private const val KEY_LAST_UPDATE_TIME = "last_update_time"

    fun clearCacheIfNewInstall(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastKnown = prefs.getLong(KEY_LAST_UPDATE_TIME, -1L)
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)
        if (current == -1L || lastKnown == current) return

        runCatching {
            context.cacheDir.listFiles { file -> file.name.startsWith("m3u_cache_") }
                ?.forEach { it.delete() }
        }
        prefs.edit().putLong(KEY_LAST_UPDATE_TIME, current).apply()
    }
}
