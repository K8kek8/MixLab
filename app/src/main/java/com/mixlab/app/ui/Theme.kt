package com.mixlab.app.ui

import androidx.compose.ui.graphics.Color

internal val ColorA = Color(0xFF29D3E8) // deck A: cian
internal val ColorB = Color(0xFFFF8A65) // deck B: naranja
internal val Bg = Color(0xFF0A0A0F)
internal val PanelBg = Color(0xFF14141D)
internal val LaneBg = Color(0xFF0D0D14)
internal val TextMain = Color(0xFFEDEDF2)
internal val TextDim = Color(0xFF9A9AAE)
internal val MarkIn = Color(0xFF4CAF50) // IN de la transición
internal val MarkOut = Color(0xFFE040FB) // OUT de la transición
internal val SelColor = Color(0xFFFFD54F) // selección para cortar/copiar
internal val Playhead = Color(0xFFFF3B30)

internal fun deckColor(idx: Int): Color = if (idx == 0) ColorA else ColorB

internal fun deckLetter(idx: Int): String = if (idx == 0) "A" else "B"

/** mm:ss */
internal fun fmtTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** m:ss.d (con décimas, para marcar cortes con precisión) */
internal fun fmtTenths(sec: Double): String {
    val t = (sec * 10).toInt().coerceAtLeast(0)
    val s = t / 10
    return "%d:%02d.%d".format(s / 60, s % 60, t % 10)
}
