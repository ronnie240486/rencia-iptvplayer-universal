package com.meuapp.iptvplayer.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Constrói dashboards reais para cada modelo liberado pelo painel. As telas usam
 * componentes Android clicáveis; não dependem de uma captura de tela como fundo.
 */
object RemoteDashboardFactory {
    const val LIVE = "live"
    const val EPG = "epg"
    const val VOD = "vod"
    const val SERIES = "series"
    const val ACCOUNT = "account"
    const val MULTI = "multi"
    const val FAVORITES = "favorites"
    const val RADIO = "radio"
    const val SETTINGS = "settings"

    private data class Model(
        val key: String,
        val title: String,
        val subtitle: String,
        val background: String,
        val surface: String,
        val accent: String,
        val style: Style
    )

    private enum class Style { EMBER, PULSE, ARENA, NEXT, PRIME, VISION, BLUE, COSMOS }

    private fun model(layoutId: String?): Model = when (layoutId?.lowercase()) {
        "htv", "pulse", "fusion" -> Model("pulse", "Rencia Pulse", "Canais e categorias em destaque", "#061B2B", "#0E3148", "#23C4FF", Style.PULSE)
        "tv_express", "arena", "infinitus" -> Model("arena", "Rencia Arena", "Conteúdo ao vivo, esportes e eventos", "#102316", "#173B22", "#63D878", Style.ARENA)
        "cinema", "next", "supremus" -> Model("next", "Rencia Next", "Sua noite começa aqui", "#1C102B", "#321B4A", "#C58DFF", Style.NEXT)
        "minimal", "prime", "maximus", "maximus_player" -> Model("prime", "Rencia Prime", "Uma experiência simples e direta", "#101319", "#1B2029", "#E7EDF6", Style.PRIME)
        "sports", "vision", "prestige" -> Model("vision", "Rencia Vision", "Central de esportes e transmissões", "#031E2A", "#06425A", "#2CE5D3", Style.VISION)
        "kids", "blue", "optimus" -> Model("blue", "Rencia Blue", "Diversão para toda a família", "#11194A", "#2639A6", "#5FD0FF", Style.BLUE)
        "compact", "cosmos", "ouropro", "ouro_pro" -> Model("cosmos", "Rencia Cosmos", "Navegue por todo o universo", "#16152C", "#29244C", "#F6B8FF", Style.COSMOS)
        else -> Model("ember", "Rencia Ember", "TV, filmes e séries em um só lugar", "#29150B", "#4A2410", "#FF9E45", Style.EMBER)
    }

    fun create(context: Context, layoutId: String?, onAction: (String) -> Unit): View {
        val spec = model(layoutId)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            background = verticalGradient(spec.background, spec.surface)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 32), dp(context, 24), dp(context, 32), dp(context, 28))
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        content.addView(header(context, spec, onAction))
        content.addView(space(context, 18))
        when (spec.style) {
            Style.EMBER -> emberDashboard(context, content, spec, onAction)
            Style.PULSE -> pulseDashboard(context, content, spec, onAction)
            Style.ARENA -> arenaDashboard(context, content, spec, onAction)
            Style.NEXT -> nextDashboard(context, content, spec, onAction)
            Style.PRIME -> primeDashboard(context, content, spec, onAction)
            Style.VISION -> visionDashboard(context, content, spec, onAction)
            Style.BLUE -> blueDashboard(context, content, spec, onAction)
            Style.COSMOS -> cosmosDashboard(context, content, spec, onAction)
        }
        return scroll
    }

    private fun header(context: Context, spec: Model, onAction: (String) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(brand(context, spec), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(context, "MINHA CONTA", spec, ACCOUNT, onAction))
        row.addView(space(context, 10, horizontal = true))
        row.addView(chip(context, "AJUSTES", spec, SETTINGS, onAction))
        return row
    }

    private fun brand(context: Context, spec: Model): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(context, spec.title, 24f, Color.WHITE, Typeface.BOLD))
        addView(text(context, spec.subtitle, 12f, Color.parseColor("#C9D6E5"), Typeface.NORMAL))
    }

    private fun emberDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(hero(context, spec, "CONTINUE ASSISTINDO", "Filmes, canais e séries selecionados para você", VOD, onAction))
        root.addView(space(context, 16))
        root.addView(sectionTitle(context, "Explorar agora"))
        root.addView(grid(context, spec, listOf(
            "TV AO VIVO" to LIVE, "GUIA EPG" to EPG, "FILMES" to VOD, "SÉRIES" to SERIES
        ), onAction, 2))
        root.addView(sectionTitle(context, "Acesso rápido"))
        root.addView(actionRow(context, spec, listOf("FAVORITOS" to FAVORITES, "RÁDIO" to RADIO, "MULTITELA" to MULTI), onAction))
    }

    private fun pulseDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(spec.surface, 20, Color.parseColor(spec.accent), 1)
            setPadding(dp(context, 12), dp(context, 16), dp(context, 12), dp(context, 16))
        }
        listOf("INÍCIO" to LIVE, "FILMES" to VOD, "SÉRIES" to SERIES, "RÁDIO" to RADIO).forEach { (label, action) ->
            menu.addView(menuItem(context, label, spec, action, onAction))
        }
        body.addView(menu, LinearLayout.LayoutParams(dp(context, 150), LinearLayout.LayoutParams.MATCH_PARENT))
        body.addView(space(context, 16, horizontal = true))
        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        main.addView(hero(context, spec, "DESTAQUE DO DIA", "Descubra o que está em alta no Rencia Pulse", LIVE, onAction))
        main.addView(sectionTitle(context, "Categorias"))
        main.addView(actionRow(context, spec, listOf("TV" to LIVE, "EPG" to EPG, "FAVORITOS" to FAVORITES), onAction))
        body.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(body)
    }

    private fun arenaDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(sectionTitle(context, "AO VIVO AGORA"))
        root.addView(scoreboard(context, spec, onAction))
        root.addView(sectionTitle(context, "Arquibancada Rencia"))
        root.addView(grid(context, spec, listOf(
            "ESPORTES" to LIVE, "CANAIS" to LIVE, "REPRISES" to VOD, "AGENDA" to EPG
        ), onAction, 4))
        root.addView(sectionTitle(context, "Mais opções"))
        root.addView(actionRow(context, spec, listOf("MULTITELA" to MULTI, "RÁDIO" to RADIO, "CONFIG." to SETTINGS), onAction))
    }

    private fun nextDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(hero(context, spec, "PREMIÈRE EM CASA", "Filmes e séries com uma experiência cinematográfica", VOD, onAction, large = true))
        root.addView(sectionTitle(context, "Trilhas para você"))
        root.addView(rail(context, spec, listOf("FILMES" to VOD, "SÉRIES" to SERIES, "DOCUMENTÁRIOS" to VOD, "FAVORITOS" to FAVORITES), onAction))
        root.addView(sectionTitle(context, "Ao vivo"))
        root.addView(actionRow(context, spec, listOf("TV AO VIVO" to LIVE, "GUIA" to EPG, "MULTITELA" to MULTI), onAction))
    }

    private fun primeDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(text(context, "O que você quer assistir?", 28f, Color.WHITE, Typeface.BOLD))
        root.addView(space(context, 14))
        root.addView(grid(context, spec, listOf(
            "TV AO VIVO" to LIVE, "FILMES" to VOD, "SÉRIES" to SERIES
        ), onAction, 3))
        root.addView(space(context, 20))
        root.addView(actionRow(context, spec, listOf("EPG" to EPG, "FAVORITOS" to FAVORITES, "AJUSTES" to SETTINGS), onAction))
    }

    private fun visionDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(hero(context, spec, "VISÃO ESPORTIVA", "Acompanhe partidas, canais e estatísticas", LIVE, onAction))
        root.addView(sectionTitle(context, "Transmissões"))
        root.addView(grid(context, spec, listOf(
            "AGORA" to LIVE, "AGENDA" to EPG, "REPRISES" to VOD, "CANAIS" to LIVE
        ), onAction, 4))
        root.addView(sectionTitle(context, "Sua biblioteca"))
        root.addView(actionRow(context, spec, listOf("FAVORITOS" to FAVORITES, "MULTITELA" to MULTI, "RÁDIO" to RADIO), onAction))
    }

    private fun blueDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(hero(context, spec, "HORA DA DIVERSÃO", "Conteúdo fácil de encontrar para toda a família", VOD, onAction))
        root.addView(sectionTitle(context, "Escolha uma aventura"))
        root.addView(grid(context, spec, listOf(
            "DESENHOS" to VOD, "FILMES" to VOD, "SÉRIES" to SERIES, "TV" to LIVE
        ), onAction, 2, roundedCards = true))
        root.addView(actionRow(context, spec, listOf("FAVORITOS" to FAVORITES, "RÁDIO" to RADIO, "CONTA" to ACCOUNT), onAction))
    }

    private fun cosmosDashboard(context: Context, root: LinearLayout, spec: Model, onAction: (String) -> Unit) {
        root.addView(text(context, "NAVEGAÇÃO CÓSMICA", 15f, Color.parseColor(spec.accent), Typeface.BOLD))
        root.addView(space(context, 8))
        root.addView(hero(context, spec, "SEU UNIVERSO", "Todos os conteúdos, organizados em um painel compacto", LIVE, onAction))
        root.addView(sectionTitle(context, "Portais"))
        root.addView(grid(context, spec, listOf(
            "TV" to LIVE, "EPG" to EPG, "FILMES" to VOD, "SÉRIES" to SERIES,
            "RÁDIO" to RADIO, "MULTI" to MULTI, "FAVORITOS" to FAVORITES, "AJUSTES" to SETTINGS
        ), onAction, 4))
    }

    private fun hero(context: Context, spec: Model, title: String, description: String, action: String, onAction: (String) -> Unit, large: Boolean = false): View {
        val frame = FrameLayout(context).apply {
            background = rounded(spec.surface, 28, Color.parseColor(spec.accent), 1)
            setPadding(dp(context, 26), dp(context, 22), dp(context, 26), dp(context, 22))
            setOnClickListener { onAction(action) }
            isFocusable = true
        }
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(context, title, if (large) 30f else 22f, Color.WHITE, Typeface.BOLD))
        copy.addView(space(context, 8))
        copy.addView(text(context, description, 14f, Color.parseColor("#D4DFEB"), Typeface.NORMAL))
        copy.addView(space(context, 16))
        copy.addView(text(context, "ABRIR AGORA  ›", 13f, Color.parseColor(spec.accent), Typeface.BOLD))
        frame.addView(copy)
        frame.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, if (large) 220 else 165))
        return frame
    }

    private fun scoreboard(context: Context, spec: Model, onAction: (String) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(spec.surface, 22, Color.parseColor(spec.accent), 1)
            setPadding(dp(context, 24), dp(context, 18), dp(context, 24), dp(context, 18))
            setOnClickListener { onAction(LIVE) }
        }
        row.addView(text(context, "AO VIVO", 14f, Color.parseColor(spec.accent), Typeface.BOLD), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(context, "Rencia FC  1  ×  1  United", 20f, Color.WHITE, Typeface.BOLD), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        row.addView(text(context, "2º TEMPO", 12f, Color.parseColor("#C9D6E5"), Typeface.BOLD))
        return row
    }

    private fun grid(context: Context, spec: Model, entries: List<Pair<String, String>>, onAction: (String) -> Unit, columns: Int, roundedCards: Boolean = false): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        entries.chunked(columns).forEach { line ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { (label, action) ->
                row.addView(card(context, label, spec, action, onAction, roundedCards), LinearLayout.LayoutParams(0, dp(context, if (columns >= 4) 92 else 120), 1f).apply {
                    setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                })
            }
            repeat(columns - line.size) { row.addView(space(context, 1, horizontal = true), LinearLayout.LayoutParams(0, 1, 1f)) }
            container.addView(row)
        }
        return container
    }

    private fun actionRow(context: Context, spec: Model, entries: List<Pair<String, String>>, onAction: (String) -> Unit): View = rail(context, spec, entries, onAction)

    private fun rail(context: Context, spec: Model, entries: List<Pair<String, String>>, onAction: (String) -> Unit): View {
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        entries.forEach { (label, action) ->
            row.addView(card(context, label, spec, action, onAction, false), LinearLayout.LayoutParams(dp(context, 170), dp(context, 86)).apply {
                setMargins(0, 0, dp(context, 10), 0)
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun menuItem(context: Context, label: String, spec: Model, action: String, onAction: (String) -> Unit): View = text(context, label, 13f, Color.WHITE, Typeface.BOLD).apply {
        background = rounded("#00000000", 14, Color.parseColor(spec.accent), 1)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
        setOnClickListener { onAction(action) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 50)).apply { setMargins(0, dp(context, 5), 0, dp(context, 5)) }
    }

    private fun card(context: Context, label: String, spec: Model, action: String, onAction: (String) -> Unit, roundedCards: Boolean): View = text(context, label, 14f, Color.WHITE, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = rounded(spec.surface, if (roundedCards) 30 else 18, Color.parseColor(spec.accent), 1)
        setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        setOnClickListener { onAction(action) }
        isFocusable = true
    }

    private fun chip(context: Context, label: String, spec: Model, action: String, onAction: (String) -> Unit): View = text(context, label, 11f, Color.WHITE, Typeface.BOLD).apply {
        background = rounded(spec.surface, 16, Color.parseColor(spec.accent), 1)
        gravity = Gravity.CENTER
        setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
        setOnClickListener { onAction(action) }
    }

    private fun sectionTitle(context: Context, title: String): View = text(context, title, 17f, Color.WHITE, Typeface.BOLD).apply {
        setPadding(0, dp(context, 22), 0, dp(context, 9))
    }

    private fun text(context: Context, value: String, size: Float, color: Int, typeface: Int): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(null, typeface)
        includeFontPadding = false
    }

    private fun rounded(color: String, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = radius * 1f
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun verticalGradient(top: String, bottom: String): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
    )

    private fun space(context: Context, amount: Int, horizontal: Boolean = false): View = View(context).apply {
        layoutParams = if (horizontal) LinearLayout.LayoutParams(dp(context, amount), 1) else LinearLayout.LayoutParams(1, dp(context, amount))
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
