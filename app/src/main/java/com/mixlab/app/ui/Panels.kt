package com.mixlab.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mixlab.app.audio.FadeCurve
import com.mixlab.app.audio.Param

internal const val TAB_FX = 0
internal const val TAB_EQ = 1
internal const val TAB_PITCH = 2
internal const val TAB_EDIT = 3
internal const val TAB_MARKS = 4

private val tabTitles = listOf("FX", "EQ", "PITCH", "CORTE", "MARCAS")

/** Panel flotante de un deck con pestañas: FX, EQ, PITCH (tempo), CORTE (editar la pista) y MARCAS. */
@Composable
fun DeckPanelDialog(
    vm: MixViewModel,
    idx: Int,
    initialTab: Int,
    onClose: () -> Unit,
    onSaveTrack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val ui = vm.decks[idx]
    val accent = deckColor(idx)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = PanelBg,
            contentColor = TextMain,
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.94f),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DECK ${deckLetter(idx)} · ${ui.title}",
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text("Cerrar") }
                }
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = accent) {
                    tabTitles.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, fontSize = 12.sp) })
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (tab) {
                        TAB_FX -> ParamGrid(
                            listOf(
                                Param.ECHO_MIX, Param.ECHO_TIME, Param.ECHO_FEEDBACK,
                                Param.REVERB_MIX, Param.REVERB_SIZE, Param.FLANGER_MIX,
                            ),
                            ui, accent,
                        ) { p, v -> vm.setParam(idx, p, v) }

                        TAB_EQ -> ParamGrid(
                            listOf(Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH, Param.FILTER, Param.VOLUME),
                            ui, accent,
                        ) { p, v -> vm.setParam(idx, p, v) }

                        TAB_PITCH -> PitchTab(vm, idx, accent)
                        TAB_EDIT -> EditTab(vm, idx, accent, onSaveTrack)
                        else -> MarksTab(vm, idx, accent)
                    }
                }
            }
        }
    }
}

// ───────────────────────── PITCH ─────────────────────────

@Composable
private fun PitchTab(vm: MixViewModel, idx: Int, accent: Color) {
    val ui = vm.decks[idx]
    ParamGrid(listOf(Param.SPEED), ui, accent) { p, v -> vm.setParam(idx, p, v) }

    Text(
        if (ui.hasBpm) {
            "Tempo detectado: %.1f BPM  ·  con la velocidad actual: %.1f BPM".format(ui.bpm, ui.effectiveBpm)
        } else {
            "No se detectó el tempo de esta canción"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("÷2", accent, Modifier.weight(1f).fillMaxHeight(), enabled = ui.hasBpm) { vm.scaleBpm(idx, 0.5) }
        PadButton("×2", accent, Modifier.weight(1f).fillMaxHeight(), enabled = ui.hasBpm) { vm.scaleBpm(idx, 2.0) }
        PadButton("REJILLA AQUÍ", accent, Modifier.weight(1.5f).fillMaxHeight(), enabled = ui.hasBpm) { vm.setGridHere(idx) }
        PadButton(
            "SYNC con el otro deck", accent, Modifier.weight(2f).fillMaxHeight(),
            enabled = ui.hasBpm && vm.decks[1 - idx].hasBpm, filled = true,
        ) { vm.syncBpm(idx) }
    }
    Text(
        "÷2 / ×2: corrige el tempo si sale al doble o a la mitad. REJILLA AQUÍ: alinea los beats con el cabezal " +
            "(úsalo en un bombo claro si las líneas blancas salen desfasadas). SYNC iguala el tempo y los golpes " +
            "con el otro deck (±15 %).",
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
    )
    SwitchRow("Marcas al beat", "Las marcas y selecciones se ajustan al beat más cercano", vm.snapToBeat) {
        vm.toggleSnap(it)
    }
}

// ───────────────────────── CORTE ─────────────────────────

@Composable
private fun EditTab(vm: MixViewModel, idx: Int, accent: Color, onSaveTrack: () -> Unit) {
    val ui = vm.decks[idx]
    val clip = vm.clipboard
    val canEdit = ui.loaded && !ui.busy

    Text(
        "1) Lleva el cabezal al principio de la parte y pulsa « [ ».  2) Al final y pulsa « ] » (están junto al botón de " +
            "play).  3) Copia o corta.  4) En el otro deck, o en este, pon el cabezal donde quieras y pega.",
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
    )
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("[  INICIO AQUÍ", SelColor, Modifier.weight(1f).fillMaxHeight(), enabled = ui.loaded) { vm.markSelStart(idx) }
        PadButton("]  FIN AQUÍ", SelColor, Modifier.weight(1f).fillMaxHeight(), enabled = ui.loaded) { vm.markSelEnd(idx) }
        PadButton("QUITAR SELECCIÓN", TextDim, Modifier.weight(1f).fillMaxHeight(), enabled = ui.loaded) { vm.clearSel(idx) }
    }
    Text(
        if (ui.hasSel) {
            "Selección: ${fmtTenths(ui.selLo)} → ${fmtTenths(ui.selHi)}   (%.1f s)".format(ui.selHi - ui.selLo)
        } else if (ui.selStart >= 0.0 || ui.selEnd >= 0.0) {
            "Falta marcar el otro extremo de la selección"
        } else {
            "Sin selección"
        },
        color = SelColor,
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("COPIAR", accent, Modifier.weight(1f).fillMaxHeight(), enabled = canEdit && ui.hasSel) { vm.copySel(idx) }
        PadButton("CORTAR", accent, Modifier.weight(1f).fillMaxHeight(), enabled = canEdit && ui.hasSel, filled = true) { vm.cutSel(idx) }
        PadButton("ELIMINAR", Playhead, Modifier.weight(1f).fillMaxHeight(), enabled = canEdit && ui.hasSel) { vm.deleteSel(idx) }
    }

    Text(
        if (clip == null) {
            "Portapapeles vacío"
        } else {
            "Portapapeles: %.1f s de «%s»%s".format(
                clip.durationSec,
                clip.name,
                if (clip.sourceBpm > 0.0) " · %.0f BPM".format(clip.sourceBpm) else "",
            )
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton(
            "PEGAR (insertar)", accent, Modifier.weight(1f).fillMaxHeight(),
            enabled = clip != null && !ui.busy && !ui.loading, filled = true,
        ) { vm.paste(idx, overlay = false) }
        PadButton(
            "SUPERPONER", accent, Modifier.weight(1f).fillMaxHeight(),
            enabled = clip != null && ui.loaded && !ui.busy,
        ) { vm.paste(idx, overlay = true) }
    }
    Text(
        "PEGAR mete el fragmento en la posición del cabezal y empuja el resto de la pista. SUPERPONER lo mezcla " +
            "encima sin mover nada. Si este deck está vacío, el fragmento pasa a ser su pista.",
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
    )
    SwitchRow(
        "Ajustar tempo al pegar",
        "Acelera o frena el fragmento para que encaje con el BPM de esta pista (cambia también el tono)",
        vm.adjustTempo,
    ) { vm.toggleAdjustTempo(it) }
    SwitchRow("Marcas al beat", "La selección y el punto de pegado se ajustan al beat más cercano", vm.snapToBeat) {
        vm.toggleSnap(it)
    }

    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("DESHACER", accent, Modifier.weight(1f).fillMaxHeight(), enabled = ui.canUndo && !ui.busy) { vm.undo(idx) }
        PadButton(
            "GUARDAR ESTA PISTA (WAV)", accent, Modifier.weight(2f).fillMaxHeight(),
            enabled = ui.loaded && !vm.exporting,
        ) { onSaveTrack() }
    }
    if (vm.exporting) {
        LinearProgressIndicator(progress = { vm.exportProgress }, modifier = Modifier.fillMaxWidth())
    }
    Text(
        "Se pueden deshacer las últimas 3 ediciones de cada deck. Las pistas editadas siguen usando todos los " +
            "efectos y el crossfade.",
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
    )
}

// ───────────────────────── MARCAS ─────────────────────────

@Composable
private fun MarksTab(vm: MixViewModel, idx: Int, accent: Color) {
    val ui = vm.decks[idx]
    Text(
        "Marcas para la transición automática: IN (verde) es dónde entra esta canción cuando es la que viene; " +
            "OUT (violeta) es dónde empieza a ceder la mezcla cuando es la que suena.",
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
    )
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("MARCAR IN", MarkIn, Modifier.weight(1f).fillMaxHeight(), enabled = ui.loaded) { vm.markIn(idx) }
        PadButton("MARCAR OUT", MarkOut, Modifier.weight(1f).fillMaxHeight(), enabled = ui.loaded) { vm.markOut(idx) }
        PadButton(
            "IR A IN", accent, Modifier.weight(1f).fillMaxHeight(),
            enabled = ui.loaded && ui.inPoint >= 0.0,
        ) { vm.seek(idx, ui.inPoint) }
        PadButton("BORRAR", TextDim, Modifier.weight(1f).fillMaxHeight(), enabled = ui.loaded) { vm.clearMarks(idx) }
    }
    Text(
        "IN: ${if (ui.inPoint >= 0.0) fmtTenths(ui.inPoint) else "—"}     OUT: ${if (ui.outPoint >= 0.0) fmtTenths(ui.outPoint) else "—"}",
        style = MaterialTheme.typography.bodyMedium,
    )
    SwitchRow("Marcas al beat", "IN y OUT se ajustan al beat más cercano", vm.snapToBeat) { vm.toggleSnap(it) }
}

// ───────────────────────── MENÚ DE MEZCLA ─────────────────────────

/** Ajustes del crossfade, mezcla automática y exportación de la mezcla completa. */
@Composable
fun MenuDialog(vm: MixViewModel, onClose: () -> Unit, onExportMix: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = PanelBg,
            contentColor = TextMain,
            modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.94f),
        ) {
            Column(
                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MEZCLA", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Cerrar") }
                }

                Text("Curva del crossfade", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in FadeCurve.values()) {
                        FilterChip(selected = vm.curve == c, onClick = { vm.chooseCurve(c) }, label = { Text(c.label) })
                    }
                }

                Text("Duración del fundido: ${vm.fadeSeconds.toInt()} s", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = vm.fadeSeconds,
                    onValueChange = { vm.changeFadeSeconds(it) },
                    valueRange = 1f..30f,
                )

                SwitchRow("Bass swap", "Los graves de un deck salen y los del otro entran a mitad del fundido", vm.bassSwap) {
                    vm.toggleBassSwap(it)
                }
                SwitchRow(
                    "Mezcla automática",
                    "Cuando el deck activo llega a su OUT (o al final), entra el otro desde su IN",
                    vm.autoMix,
                ) { vm.toggleAutoMix(it) }

                PadButton(
                    if (vm.transitioning) "MEZCLANDO…" else "CROSSFADE AHORA",
                    Color.White,
                    Modifier.fillMaxWidth().height(44.dp),
                    enabled = !vm.transitioning,
                    filled = true,
                    fontSize = 14.sp,
                ) { vm.crossfadeNow() }

                if (vm.exporting) {
                    LinearProgressIndicator(progress = { vm.exportProgress }, modifier = Modifier.fillMaxWidth())
                    Text("Renderizando…", style = MaterialTheme.typography.bodySmall)
                } else {
                    PadButton("EXPORTAR MEZCLA COMPLETA (WAV)", ColorA, Modifier.fillMaxWidth().height(40.dp)) { onExportMix() }
                }
                Text(
                    "La exportación renderiza el deck A, la transición y el deck B con los efectos y marcas actuales.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                )
            }
        }
    }
}
