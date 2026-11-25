package com.mixlab.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mixlab.app.audio.FadeCurve
import com.mixlab.app.audio.Param
import kotlin.math.abs

private val ColorA = Color(0xFF4FC3F7)
private val ColorB = Color(0xFFFF8A65)

private val effectGroups = listOf(
    "Mezcla" to listOf(Param.VOLUME, Param.SPEED),
    "Ecualizador" to listOf(Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH),
    "Filtro" to listOf(Param.FILTER),
    "Eco" to listOf(Param.ECHO_MIX, Param.ECHO_TIME, Param.ECHO_FEEDBACK),
    "Reverb" to listOf(Param.REVERB_MIX, Param.REVERB_SIZE),
    "Flanger" to listOf(Param.FLANGER_MIX),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixScreen(vm: MixViewModel) {
    var pendingDeck by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadTrack(pendingDeck, uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.exportMix(uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("MixLab") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeckCard("DECK A", ColorA, vm.decks[0], 0, vm) {
                pendingDeck = 0
                picker.launch(arrayOf("audio/*"))
            }
            DeckCard("DECK B", ColorB, vm.decks[1], 1, vm) {
                pendingDeck = 1
                picker.launch(arrayOf("audio/*"))
            }
            CrossfadeCard(vm) { exporter.launch("mezcla.wav") }
            EffectsCard(vm)
        }
    }
}

// ───────────────────────── Deck ─────────────────────────

@Composable
private fun DeckCard(
    title: String,
    accent: Color,
    ui: DeckUiState,
    idx: Int,
    vm: MixViewModel,
    onLoad: () -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onLoad, enabled = !ui.loading) { Text("Cargar canción") }
            }
            Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)

            if (ui.loading) {
                LinearProgressIndicator(progress = { ui.loadProgress }, modifier = Modifier.fillMaxWidth(), color = accent)
                Text(ui.status, style = MaterialTheme.typography.bodySmall)
            }

            SpectrumView(ui = ui, zoom = zoom, onSeek = { vm.seek(idx, it) })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${fmtTime(ui.position)} / ${fmtTime(ui.duration)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "IN ${if (ui.inPoint >= 0) fmtTime(ui.inPoint) else "—"}   OUT ${if (ui.outPoint >= 0) fmtTime(ui.outPoint) else "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val pad = PaddingValues(horizontal = 8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.togglePlay(idx) },
                    enabled = ui.loaded,
                    contentPadding = pad,
                    modifier = Modifier.weight(1.4f),
                ) { Text(if (ui.playing) "⏸ Pausa" else "▶ Play") }
                OutlinedButton(
                    onClick = { vm.seek(idx, 0.0) }, enabled = ui.loaded,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("⏮") }
                OutlinedButton(
                    onClick = { zoom = (zoom / 2f).coerceAtLeast(1f) }, enabled = ui.loaded,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Zoom −") }
                OutlinedButton(
                    onClick = { zoom = (zoom * 2f).coerceAtMost(16f) }, enabled = ui.loaded,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Zoom +") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.markIn(idx) }, enabled = ui.loaded,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Marcar IN") }
                OutlinedButton(
                    onClick = { vm.markOut(idx) }, enabled = ui.loaded,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Marcar OUT") }
                OutlinedButton(
                    onClick = { if (ui.inPoint >= 0) vm.seek(idx, ui.inPoint) },
                    enabled = ui.loaded && ui.inPoint >= 0,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Ir a IN") }
                TextButton(
                    onClick = { vm.clearMarks(idx) }, enabled = ui.loaded,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Borrar") }
            }

            // ---- BPM ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ui.hasBpm) "%.1f BPM".format(ui.bpm) else "BPM —",
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    val speed = ui.params[Param.SPEED] ?: 1f
                    when {
                        ui.hasBpm && abs(speed - 1f) > 0.002f ->
                            Text("→ %.1f con la velocidad actual".format(ui.effectiveBpm), style = MaterialTheme.typography.labelSmall)
                        ui.hasBpm && ui.beatConfidence < 0.3f ->
                            Text("detección dudosa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        !ui.hasBpm && ui.loaded ->
                            Text("no se detectó pulso", style = MaterialTheme.typography.labelSmall)
                    }
                }
                FilterChip(
                    selected = vm.snapToBeat,
                    onClick = { vm.toggleSnap(!vm.snapToBeat) },
                    label = { Text("Marcas al beat") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.scaleBpm(idx, 0.5) }, enabled = ui.hasBpm,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("÷2") }
                OutlinedButton(
                    onClick = { vm.scaleBpm(idx, 2.0) }, enabled = ui.hasBpm,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("×2") }
                OutlinedButton(
                    onClick = { vm.setGridHere(idx) }, enabled = ui.hasBpm,
                    contentPadding = pad, modifier = Modifier.weight(1.3f),
                ) { Text("Rejilla aquí") }
                Button(
                    onClick = { vm.syncBpm(idx) },
                    enabled = ui.hasBpm && vm.decks[1 - idx].hasBpm,
                    contentPadding = pad, modifier = Modifier.weight(1f),
                ) { Text("Sync") }
            }

            Text(
                "Toca el espectro para saltar · arrastra para desplazar con zoom · " +
                    "IN = dónde entra esta canción · OUT = dónde empieza a ceder la mezcla.\n" +
                    "Líneas blancas = beats (más gruesas cada 4). ÷2/×2 corrige el tempo si sale doble o mitad; " +
                    "«Rejilla aquí» alinea los beats con el cabezal; Sync iguala el tempo con el otro deck.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ───────────────────────── Crossfade ─────────────────────────

@Composable
private fun CrossfadeCard(vm: MixViewModel, onExport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Crossfade", fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", color = ColorA, fontWeight = FontWeight.Bold)
                Slider(
                    value = vm.crossfader,
                    onValueChange = { vm.moveCrossfader(it) },
                    enabled = !vm.transitioning,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("B", color = ColorB, fontWeight = FontWeight.Bold)
            }

            Text("Curva", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in FadeCurve.values()) {
                    FilterChip(
                        selected = vm.curve == c,
                        onClick = { vm.chooseCurve(c) },
                        label = { Text(c.label) },
                    )
                }
            }

            Text("Duración del fundido: ${vm.fadeSeconds.toInt()} s", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = vm.fadeSeconds,
                onValueChange = { vm.changeFadeSeconds(it) },
                valueRange = 1f..30f,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bass swap")
                    Text(
                        "Los graves de A salen y los de B entran a mitad del fundido",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.bassSwap, onCheckedChange = { vm.toggleBassSwap(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mezcla automática")
                    Text(
                        "Cuando el deck activo llega a su OUT, entra el otro desde su IN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.autoMix, onCheckedChange = { vm.toggleAutoMix(it) })
            }

            Button(
                onClick = { vm.crossfadeNow() },
                enabled = !vm.transitioning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.transitioning) "Mezclando…" else "Crossfade ahora") }

            if (vm.exporting) {
                LinearProgressIndicator(progress = { vm.exportProgress }, modifier = Modifier.fillMaxWidth())
                Text("Renderizando la mezcla…", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("Exportar mezcla completa (WAV)")
                }
            }
        }
    }
}

// ───────────────────────── Efectos ─────────────────────────

@Composable
private fun EffectsCard(vm: MixViewModel) {
    var sel by remember { mutableIntStateOf(0) }
    val ui = vm.decks[sel]
    val accent = if (sel == 0) ColorA else ColorB

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Efectos por deck", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sel == 0, onClick = { sel = 0 }, label = { Text("Deck A") })
                FilterChip(selected = sel == 1, onClick = { sel = 1 }, label = { Text("Deck B") })
            }
            for ((group, params) in effectGroups) {
                Text(group, style = MaterialTheme.typography.titleSmall, color = accent, modifier = Modifier.padding(top = 8.dp))
                for (p in params) {
                    ParamSlider(p, ui.params[p] ?: p.def, accent) { vm.setParam(sel, p, it) }
                }
            }
            Text(
                "Toca el nombre de un control para volver a su valor por defecto.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParamSlider(p: Param, value: Float, accent: Color, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onChange(p.def) })
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

private fun fmtTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
