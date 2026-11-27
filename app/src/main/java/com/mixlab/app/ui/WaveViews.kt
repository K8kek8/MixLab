package com.mixlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixlab.app.audio.Waveform
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Vista general de toda la canción (franja fina). Toca o arrastra para saltar.
 * Verde/violeta = IN/OUT de la transición; amarillo = selección para cortar.
 */
@Composable
fun OverviewBar(ui: DeckUiState, accent: Color, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val dur by rememberUpdatedState(ui.duration)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(LaneBg)
            .pointerInput(Unit) {
                detectTapGestures { o -> if (dur > 0.0) seek((o.x / size.width * dur).coerceIn(0.0, dur)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (dur > 0.0) seek((change.position.x / size.width * dur).coerceIn(0.0, dur))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val wave = ui.wave
        if (wave == null || ui.duration <= 0.0) {
            drawLine(Color(0xFF2A2A38), Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)
            return@Canvas
        }

        val n = Waveform.OVERVIEW_COLS
        val played = (ui.position / ui.duration * w).toFloat()
        var x = 0f
        while (x < w) {
            val i = (x / w * n).toInt().coerceIn(0, n - 1)
            val a = wave.overviewPeak[i] * (mid - 1f)
            val b = wave.overviewBass[i] * (mid - 1f)
            val done = x <= played
            drawLine(accent.copy(alpha = if (done) 0.55f else 0.28f), Offset(x, mid - a), Offset(x, mid + a), strokeWidth = 1.5f)
            drawLine(accent.copy(alpha = if (done) 1f else 0.55f), Offset(x, mid - b), Offset(x, mid + b), strokeWidth = 1.5f)
            x += 1.5f
        }

        fun xOf(t: Double) = (t / ui.duration * w).toFloat()
        if (ui.hasSel) {
            val x1 = xOf(ui.selLo)
            val x2 = xOf(ui.selHi)
            drawRect(SelColor.copy(alpha = 0.35f), topLeft = Offset(x1, 0f), size = Size(maxOf(2f, x2 - x1), h))
        }
        if (ui.inPoint >= 0.0) drawLine(MarkIn, Offset(xOf(ui.inPoint), 0f), Offset(xOf(ui.inPoint), h), strokeWidth = 2f)
        if (ui.outPoint >= 0.0) drawLine(MarkOut, Offset(xOf(ui.outPoint), 0f), Offset(xOf(ui.outPoint), h), strokeWidth = 2f)
        drawLine(Color.White, Offset(played, 0f), Offset(played, h), strokeWidth = 2f)
    }
}

/**
 * Onda grande que se desplaza con la reproducción; el cabezal (línea roja) está fijo en el centro.
 *  - Silueta tenue = volumen. Núcleo brillante = graves: un drop o un bombo se ven como núcleo ancho,
 *    un break o una parte sin bajo, como silueta hueca.
 *  - Líneas blancas = beats (más largas cada 4, el compás).
 *  - Amarillo = selección; verde y violeta = IN y OUT de la transición.
 *  - Arrastrar desplaza la canción (como mover un vinilo).
 */
@Composable
fun WaveLane(
    ui: DeckUiState,
    label: String,
    accent: Color,
    zoomSec: Float,
    onZoom: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubBy: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vis by rememberUpdatedState(zoomSec)
    val startNow by rememberUpdatedState(onScrubStart)
    val scrubNow by rememberUpdatedState(onScrubBy)
    val endNow by rememberUpdatedState(onScrubEnd)

    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(LaneBg),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { startNow() },
                        onDragEnd = { endNow() },
                        onDragCancel = { endNow() },
                    ) { change, dx ->
                        change.consume()
                        scrubNow(-(dx / size.width * vis).toDouble())
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            val wave = ui.wave
            if (wave == null) {
                drawLine(Color(0xFF2A2A38), Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)
                return@Canvas
            }

            val visSec = zoomSec.toDouble()
            val start = ui.position - visSec / 2.0
            val half = h / 2f - 2f
            fun xOf(t: Double) = ((t - start) / visSec * w).toFloat()

            // Onda: silueta (volumen) + núcleo (graves)
            var px = 0f
            while (px < w) {
                val t = start + px / w * visSec
                val col = floor(t * wave.perSec).toInt()
                if (col >= 0 && col < wave.size) {
                    val a = wave.peak[col] * half
                    val b = wave.bass[col] * half
                    drawLine(accent.copy(alpha = 0.5f), Offset(px, mid - a), Offset(px, mid + a), strokeWidth = 2f)
                    drawLine(accent, Offset(px, mid - b), Offset(px, mid + b), strokeWidth = 2f)
                }
                px += 2f
            }

            // Selección
            if (ui.hasSel) {
                val x1 = xOf(ui.selLo).coerceIn(0f, w)
                val x2 = xOf(ui.selHi).coerceIn(0f, w)
                if (x2 > x1) drawRect(SelColor.copy(alpha = 0.28f), topLeft = Offset(x1, 0f), size = Size(x2 - x1, h))
            }

            // Rejilla de beats
            if (ui.hasBpm) {
                val period = ui.beatSec
                val pxPerBeat = (period / visSec * w).toFloat()
                val every = when {
                    pxPerBeat >= 6f -> 1
                    pxPerBeat * 4f >= 6f -> 4
                    else -> 0 // demasiado denso para dibujarlo
                }
                if (every > 0) {
                    var k = ceil((start - ui.firstBeat) / period).toLong()
                    while (true) {
                        val t = ui.firstBeat + k * period
                        if (t > start + visSec) break
                        if (Math.floorMod(k, every.toLong()) == 0L) {
                            val bar = Math.floorMod(k, 4L) == 0L
                            val x = xOf(t)
                            drawLine(
                                color = Color.White.copy(alpha = if (bar) 0.75f else 0.35f),
                                start = Offset(x, if (bar) 0f else h * 0.2f),
                                end = Offset(x, if (bar) h else h * 0.8f),
                                strokeWidth = if (bar) 2f else 1f,
                            )
                        }
                        k++
                    }
                }
            }

            // Marcas
            fun mark(t: Double, c: Color, sw: Float) {
                if (t < 0.0) return
                val x = xOf(t)
                if (x >= 0f && x <= w) drawLine(c, Offset(x, 0f), Offset(x, h), strokeWidth = sw)
            }
            mark(ui.selStart, SelColor, 3f)
            mark(ui.selEnd, SelColor, 3f)
            mark(ui.inPoint, MarkIn, 2f)
            mark(ui.outPoint, MarkOut, 2f)

            // Cabezal
            drawLine(Playhead, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 3f)
        }

        // Etiqueta del deck y zoom (tocar para cambiar los segundos visibles)
        Text(
            "$label · ${zoomSec.toInt()} s",
            color = accent,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .clickable(onClick = onZoom)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (ui.busy || ui.loading) {
            Text(
                ui.status,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}
