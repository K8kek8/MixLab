package com.mixlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val MarkIn = Color(0xFF4CAF50)
private val MarkOut = Color(0xFFFF5252)

/**
 * Espectrograma (arriba) + forma de onda coloreada por graves/medios/agudos (abajo).
 *
 * - Toque: salta a ese punto.
 * - Arrastre horizontal: desplaza la vista (cuando hay zoom).
 * - Marcas verde (IN) y roja (OUT) = dónde empieza/termina la transición.
 */
@Composable
fun SpectrumView(
    ui: DeckUiState,
    zoom: Float,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dur = ui.duration
    val vis = if (dur > 0.0) dur / zoom else 1.0
    val maxStart = (dur - vis).coerceAtLeast(0.0)
    var viewStart by remember { mutableDoubleStateOf(0.0) }

    // Con zoom y reproduciendo, la vista sigue al cabezal; en pausa se puede desplazar a mano.
    val start = when {
        zoom <= 1f -> 0.0
        ui.playing -> (ui.position - vis / 2).coerceIn(0.0, maxStart)
        else -> viewStart.coerceIn(0.0, maxStart)
    }

    val startNow by rememberUpdatedState(start)
    val visNow by rememberUpdatedState(vis)
    val maxStartNow by rememberUpdatedState(maxStart)
    val onSeekNow by rememberUpdatedState(onSeek)
    val durNow by rememberUpdatedState(dur)

    val analysis = ui.analysis
    val image = ui.spectrumImage

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B0B12))
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    if (durNow > 0.0) {
                        onSeekNow((startNow + off.x / size.width * visNow).coerceIn(0.0, durNow))
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    val dSec = dx / size.width * visNow
                    viewStart = (startNow - dSec).coerceIn(0.0, maxStartNow)
                }
            },
    ) {
        if (analysis == null || image == null || dur <= 0.0) return@Canvas

        val w = size.width
        val h = size.height
        val specH = h * 0.62f
        val waveH = h - specH
        val cols = analysis.cols

        // --- Espectrograma ---
        val x0 = (start / dur * cols).toInt().coerceIn(0, cols - 1)
        val xw = max(1, (vis / dur * cols).roundToInt()).coerceAtMost(cols - x0)
        drawImage(
            image = image,
            srcOffset = IntOffset(x0, 0),
            srcSize = IntSize(xw, analysis.bands),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(w.toInt(), specH.toInt()),
            filterQuality = FilterQuality.Low,
        )

        // --- Forma de onda por graves/medios/agudos ---
        val midY = specH + waveH / 2f
        var px = 0f
        while (px < w) {
            val t = start + px / w * vis
            val ci = (t / dur * cols).toInt().coerceIn(0, cols - 1)
            val amp = analysis.peak[ci]
            drawLine(
                color = Color(analysis.waveColors[ci]),
                start = Offset(px, midY - amp * waveH / 2f),
                end = Offset(px, midY + amp * waveH / 2f),
                strokeWidth = 2f,
            )
            px += 2f
        }

        // --- Marcas y cabezal ---
        fun xOf(t: Double) = ((t - start) / vis * w).toFloat()
        fun DrawScope.marker(t: Double, color: Color) {
            if (t < 0.0) return
            val x = xOf(t)
            if (x in 0f..w) drawLine(color, Offset(x, 0f), Offset(x, h), strokeWidth = 4f)
        }

        // --- Rejilla de beats: línea tenue por pulso y más marcada cada 4 (compás) ---
        if (ui.hasBpm) {
            val period = ui.beatSec
            val pxPerBeat = (period / vis * w).toFloat()
            val every = when {
                pxPerBeat >= 7f -> 1
                pxPerBeat * 4f >= 7f -> 4
                else -> 0 // demasiado denso para dibujarlo
            }
            if (every > 0) {
                var k = ceil((start - ui.firstBeat) / period).toLong()
                while (true) {
                    val t = ui.firstBeat + k * period
                    if (t > start + vis) break
                    if (Math.floorMod(k, every.toLong()) == 0L) {
                        val bar = Math.floorMod(k, 4L) == 0L
                        val x = xOf(t)
                        drawLine(
                            color = Color.White.copy(alpha = if (bar) 0.5f else 0.2f),
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = if (bar) 2f else 1f,
                        )
                    }
                    k++
                }
            }
        }

        marker(ui.inPoint, MarkIn)
        marker(ui.outPoint, MarkOut)
        marker(ui.position, Color.White)
    }
}
