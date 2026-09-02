package com.meuapp.iptvplayer.ui.epg

import com.meuapp.iptvplayer.data.model.EpgListing
import com.meuapp.iptvplayer.data.model.LiveStream

/** Uma linha do guia de programação com vários canais: qual canal + qual
 * programa daquele canal. Usado pela grade de EPG completa (GuideAdapter),
 * diferente do EpgAdapter simples (que já sabe o canal de antemão). */
data class GuideProgramRow(
    val channel: LiveStream,
    val listing: EpgListing,
)
