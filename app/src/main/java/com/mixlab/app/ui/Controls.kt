package com.mixlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixlab.app.audio.Param

/** Botón rectangular oscuro con borde de color. */
@Composable
fun PadButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (filled) color.copy(alpha = if (enabled) 0.22f else 0.08f) else PanelBg)
            .border(1.dp, color.copy(alpha = if (enabled) 0.85f else 0.25f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = color.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private val ThumbHalf = 9.dp

/** Posición (0..1) del puntero sobre el fader, dejando margen para que la cabeza no se salga. */
private fun faderFraction(o: Offset, size: IntSize, vertical: Boolean, pad: Float): Float {
    val len = (if (vertical) size.height else size.width).toFloat()
    val pos = if (vertical) len - o.y else o.x // en vertical, arriba = máximo
    return ((pos - pad) / (len - 2 * pad)).coerceIn(0f, 1f)
}

/** Fader (deslizador) vertical u horizontal dibujado a mano, con marcas y cabeza tipo mesa de mezclas. */
@Composable
fun Fader(
    value: Float,
    lo: Float,
    hi: Float,
    accent: Color,
    vertical: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(vertical, lo, hi) {
                val pad = ThumbHalf.toPx()
                detectTapGestures { o -> change(lo + faderFraction(o, size, vertical, pad) * (hi - lo)) }
            }
            .pointerInput(vertical, lo, hi) {
                val pad = ThumbHalf.toPx()
                detectDragGestures(
                    onDragStart = { o -> change(lo + faderFraction(o, size, vertical, pad) * (hi - lo)) },
                ) { c, _ ->
                    c.consume()
                    change(lo + faderFraction(c.position, size, vertical, pad) * (hi - lo))
                }
            },
    ) {
        val frac = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
        val pad = ThumbHalf.toPx()
        val thumbLen = pad * 2
        val thumbThick = 34.dp.toPx()
        val groove = 4.dp.toPx()
        val tick = Color(0x33FFFFFF)
        val radius = CornerRadius(4.dp.toPx())

        if (vertical) {
            val cx = size.width / 2f
            val len = size.height - 2 * pad
            val ty = pad + (1f - frac) * len
            drawLine(Color(0xFF2A2A38), Offset(cx, pad), Offset(cx, size.height - pad), strokeWidth = groove, cap = StrokeCap.Round)
            drawLine(accent, Offset(cx, ty), Offset(cx, size.height - pad), strokeWidth = groove, cap = StrokeCap.Round)
            for (i in 0..10) {
                val y = pad + len * i / 10f
                drawLine(tick, Offset(cx - 14.dp.toPx(), y), Offset(cx - 7.dp.toPx(), y), strokeWidth = 1f)
                drawLine(tick, Offset(cx + 7.dp.toPx(), y), Offset(cx + 14.dp.toPx(), y), strokeWidth = 1f)
            }
            val tw = minOf(size.width, thumbThick)
            drawRoundRect(Color(0xFFEDEDED), Offset(cx - tw / 2f, ty - pad), Size(tw, thumbLen), radius)
            drawLine(accent, Offset(cx - tw / 2f + 4.dp.toPx(), ty), Offset(cx + tw / 2f - 4.dp.toPx(), ty), strokeWidth = 2.dp.toPx())
        } else {
            val cy = size.height / 2f
            val len = size.width - 2 * pad
            val tx = pad + frac * len
            drawLine(Color(0xFF2A2A38), Offset(pad, cy), Offset(size.width - pad, cy), strokeWidth = groove, cap = StrokeCap.Round)
            drawLine(accent, Offset(size.width / 2f, cy), Offset(tx, cy), strokeWidth = groove, cap = StrokeCap.Round)
            for (i in 0..20) {
                val x = pad + len * i / 20f
                val h = if (i % 5 == 0) 9.dp.toPx() else 5.dp.toPx()
                drawLine(tick, Offset(x, cy - 12.dp.toPx() - h), Offset(x, cy - 12.dp.toPx()), strokeWidth = 1f)
                drawLine(tick, Offset(x, cy + 12.dp.toPx()), Offset(x, cy + 12.dp.toPx() + h), strokeWidth = 1f)
            }
            val th = minOf(size.height, thumbThick)
            drawRoundRect(Color(0xFFEDEDED), Offset(tx - pad, cy - th / 2f), Size(thumbLen, th), radius)
            drawLine(accent, Offset(tx, cy - th / 2f + 4.dp.toPx()), Offset(tx, cy + th / 2f - 4.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Slider con nombre y valor; tocar el nombre devuelve el control a su valor por defecto. */
@Composable
fun ParamSlider(
    p: Param,
    value: Float,
    accent: Color,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { onChange(p.def) })
            Text(p.fmt(value), style = MaterialTheme.typography.bodySmall, color = accent)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = p.min..p.max,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
        )
    }
}

/** Parámetros en dos columnas para aprovechar el ancho de la pantalla horizontal. */
@Composable
fun ParamGrid(params: List<Param>, ui: DeckUiState, accent: Color, onChange: (Param, Float) -> Unit) {
    for (row in params.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (p in row) {
                ParamSlider(p, ui.params[p] ?: p.def, accent, { onChange(p, it) }, Modifier.weight(1f))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextDim)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
