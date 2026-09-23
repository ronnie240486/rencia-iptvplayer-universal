package com.meuapp.iptvplayer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Esconde a barra de status e a barra de navegação em TODAS as telas do
 * app, automaticamente -- sem precisar copiar o mesmo código de
 * "hideSystemBars()" em cada uma das ~17 Activities. Antes disso só
 * existia em HomeActivity e PlayerActivity; o resto (Canais, Filmes,
 * Séries, Ajustes, Login, etc.) ainda mostrava as duas barras. */
class RenciaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = hideSystemBars(activity)
                override fun onActivityResumed(activity: Activity) = hideSystemBars(activity)
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    private fun hideSystemBars(activity: Activity) {
        val window = activity.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
