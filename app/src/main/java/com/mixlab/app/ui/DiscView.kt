package com.mixlab.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2

/** 33⅓ rpm = 200 grados por segundo de canción. */
private const val DEG_PER_SEC = 200.0

/**
 * Tocadiscos: el vinilo gira según la posición de la canción (y se frena al pausar).
 *  - Tocar el disco = play / pausa.
 *  - Arrastrar en círculo = avanzar o retroceder la canción (scrub), como mover un vinilo a mano.
 * El brazo se apoya sobre el disco mientras suena.
 */
@Composable
fun DiscView(
    ui: DeckUiState,
    accent: Color,
    onTogglePlay: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubBy: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arm by animateFloatAsState(targetValue = if (ui.playing) 1f else 0f, animationSpec = tween(450), label = "arm")
    val tapNow by rememberUpdatedState(onTogglePlay)
    val startNow by rememberUpdatedState(onScrubStart)
    val scrubNow by rememberUpdatedState(onScrubBy)
    val endNow by rememberUpdatedState(onScrubEnd)
    val angle = ((ui.position * DEG_PER_SEC) % 360.0).toFloat()

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tapNow() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startNow() },
                    onDragEnd = { endNow() },
                    onDragCancel = { endNow() },
                ) { change, _ ->
                    change.consume()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val a1 = atan2(change.previousPosition.y - cy, change.previousPosition.x - cx)
                    val a2 = atan2(change.position.y - cy, change.position.x - cx)
                    var d = Math.toDegrees((a2 - a1).toDouble())
                    if (d > 180.0) d -= 360.0
                    if (d < -180.0) d += 360.0
                    scrubNow(d / DEG_PER_SEC)
                }
            },
    ) {
        val c = center
        val r = size.minDimension / 2f * 0.94f

        // Vinilo
        drawCircle(Color(0xFF050507), radius = r, center = c)
        rotate(angle, pivot = c) {
            // Reflejos que giran con el disco
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0x00FFFFFF), Color(0x2AFFFFFF), Color(0x00FFFFFF),
                        Color(0x00FFFFFF), Color(0x22FFFFFF), Color(0x00FFFFFF),
                    ),
                    center = c,
                ),
                radius = r,
                center = c,
            )
            // Surcos
            var rr = r * 0.40f
            while (rr < r * 0.97f) {
                drawCircle(Color(0x16FFFFFF), radius = rr, center = c, style = Stroke(width = 1f))
                rr += r * 0.04f
            }
            // Etiqueta con el color del deck
            drawCircle(
                brush = Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.55f)), center = c, radius = r * 0.34f),
                radius = r * 0.34f,
                center = c,
            )
            drawCircle(Color.White.copy(alpha = 0.85f), radius = r * 0.34f, center = c, style = Stroke(width = 3f))
            // Marca para ver el giro
            drawLine(
                Color.White,
                Offset(c.x, c.y - r * 0.30f),
                Offset(c.x, c.y - r * 0.18f),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(Color(0xFF0A0A0F), radius = r * 0.04f, center = c) // eje

        // Aro exterior: se ilumina cuando suena
        drawCircle(
            accent.copy(alpha = if (ui.playing) 0.9f else 0.35f),
            radius = r,
            center = c,
            style = Stroke(width = 4f),
        )

        // Brazo: de reposo (fuera del disco) a apoyado sobre el surco
        val pivot = Offset(size.width * 0.93f, size.height * 0.10f)
        val off = Offset(size.width * 0.97f, size.height * 0.50f)
        val on = Offset(size.width * 0.74f, size.height * 0.68f)
        val tip = Offset(off.x + (on.x - off.x) * arm, off.y + (on.y - off.y) * arm)
        drawLine(Color(0xFFDDDDDD), pivot, tip, strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(Color(0xFFDDDDDD), radius = 9f, center = pivot)
        drawCircle(accent, radius = 6f, center = tip)
    }
}
