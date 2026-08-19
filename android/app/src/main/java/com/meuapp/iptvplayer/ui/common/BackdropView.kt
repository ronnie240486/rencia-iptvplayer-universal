package com.meuapp.iptvplayer.ui.common

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import coil.load
import com.meuapp.iptvplayer.R

/**
 * Camada de fundo usada nas telas de conteúdo: mostra o pôster do item em destaque,
 * desfocado e escurecido (efeito "backdrop"), com um degradê escuro por cima que garante
 * a leitura do texto/menus na frente. Se não houver imagem, cai para o fundo padrão
 * de ondas de sinal (bg_signal_waves).
 *
 * O usuário pode desligar esse efeito em Configurações (AppearancePrefs.isBackdropPosterEnabled).
 */
class BackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val posterImage: ImageView

    init {
        inflate(context, R.layout.view_backdrop, this)
        posterImage = findViewById(R.id.ivBackdropPoster)
    }

    /** @param posterUrl URL da capa do item em destaque (filme/série/canal). Null = usa fundo padrão. */
    fun setPoster(posterUrl: String?, enabled: Boolean) {
        if (!enabled || posterUrl.isNullOrBlank()) {
            posterImage.setImageDrawable(null)
            posterImage.setBackgroundResource(R.drawable.bg_signal_waves)
            return
        }
        posterImage.background = null
        posterImage.load(posterUrl) {
            crossfade(true)
        }
        applyBlur(posterImage)
    }

    private fun applyBlur(view: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setBlurApi31(view)
        }
        // Em versões anteriores ao Android 12 (API 31), o degradê escuro por cima
        // (definido em view_backdrop.xml) já garante contraste suficiente mesmo sem blur nativo.
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setBlurApi31(view: ImageView) {
        view.setRenderEffect(
            RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
        )
    }
}
