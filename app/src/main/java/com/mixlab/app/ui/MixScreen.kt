package com.mixlab.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixlab.app.audio.Param

/** Qué panel está abierto: el deck y la pestaña. */
private class PanelRequest(val deck: Int, val tab: Int)

/**
 * Pantalla horizontal estilo mesa de DJ:
 *  arriba   → BPM, título y vista general de cada canción, y el botón MENU
 *  ondas    → una por deck, con el cabezal rojo fijo en el centro
 *  centro   → disco A | mezclador (cargar, faders de volumen, SYNC) | disco B, con botones laterales
 *  abajo    → play, marcas de selección «[» «]» y crossfader
 */
@Composable
fun MixScreen(vm: MixViewModel) {
    var pendingDeck by remember { mutableIntStateOf(0) }
    var exportDeckIdx by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<PanelRequest?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadTrack(pendingDeck, uri)
    }
    val mixExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.exportMix(uri)
    }
    val trackExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.exportDeck(exportDeckIdx, uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = TextMain) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TopBar(vm, onMenu = { showMenu = true }, onPitch = { panel = PanelRequest(it, TAB_PITCH) })

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (i in 0..1) {
                        WaveLane(
                            ui = vm.decks[i],
                            label = deckLetter(i),
                            accent = deckColor(i),
                            zoomSec = vm.zoomSec,
                            onZoom = { vm.cycleZoom() },
                            onScrubStart = { vm.scrubStart(i) },
                            onScrubBy = { vm.scrubBy(i, it) },
                            onScrubEnd = { vm.scrubEnd(i) },
                        )
                    }
                }

                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SidePanel(0, { panel = PanelRequest(0, it) }, Modifier.width(62.dp).fillMaxHeight())
                    DiscBox(vm, 0, Modifier.weight(1f).fillMaxHeight())
                    CenterMixer(
                        vm,
                        onLoad = {
                            pendingDeck = it
                            picker.launch(arrayOf("audio/*"))
                        },
                        modifier = Modifier.width(150.dp).fillMaxHeight(),
                    )
                    DiscBox(vm, 1, Modifier.weight(1f).fillMaxHeight())
                    SidePanel(1, { panel = PanelRequest(1, it) }, Modifier.width(62.dp).fillMaxHeight())
                }

                BottomBar(vm)
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        }
    }

    panel?.let { req ->
        DeckPanelDialog(
            vm = vm,
            idx = req.deck,
            initialTab = req.tab,
            onClose = { panel = null },
            onSaveTrack = {
                exportDeckIdx = req.deck
                trackExporter.launch("pista_${deckLetter(req.deck)}.wav")
            },
        )
    }
    if (showMenu) {
        MenuDialog(vm, onClose = { showMenu = false }, onExportMix = { mixExporter.launch("mezcla.wav") })
    }
}

// ───────────────────────── Barra superior ─────────────────────────

@Composable
private fun TopBar(vm: MixViewModel, onMenu: () -> Unit, onPitch: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BpmBox(vm.decks[0], ColorA) { onPitch(0) }
        DeckHeader(vm, 0, Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF2A3AA8), Color(0xFF5A2AA8))))
                .clickable(onClick = onMenu)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("MENU", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
        }
        DeckHeader(vm, 1, Modifier.weight(1f))
        BpmBox(vm.decks[1], ColorB) { onPitch(1) }
    }
}

@Composable
private fun BpmBox(ui: DeckUiState, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(54.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (ui.hasBpm) "%.1f".format(ui.effectiveBpm) else "—",
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        Text("BPM", color = TextDim, fontSize = 8.sp)
        Text(
            "PITCH",
            color = accent,
            fontSize = 9.sp,
            modifier = Modifier
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun DeckHeader(vm: MixViewModel, idx: Int, modifier: Modifier) {
    val ui = vm.decks[idx]
    val accent = deckColor(idx)
    val title = when {
        ui.loading -> ui.status + if (ui.loadProgress < 1f) " ${(ui.loadProgress * 100).toInt()}%" else ""
        ui.busy -> ui.status
        else -> ui.title
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(fmtTime(ui.position), color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (ui.loading) {
            LinearProgressIndicator(
                progress = { ui.loadProgress },
                modifier = Modifier.fillMaxWidth().height(14.dp).padding(vertical = 5.dp),
                color = accent,
            )
        } else {
            OverviewBar(ui, accent, onSeek = { vm.seek(idx, it) })
        }
    }
}

// ───────────────────────── Discos y mezclador ─────────────────────────

@Composable
private fun DiscBox(vm: MixViewModel, idx: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        DiscView(
            ui = vm.decks[idx],
            accent = deckColor(idx),
            onTogglePlay = { vm.togglePlay(idx) },
            onScrubStart = { vm.scrubStart(idx) },
            onScrubBy = { vm.scrubBy(idx, it) },
            onScrubEnd = { vm.scrubEnd(idx) },
            modifier = Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
        )
    }
}

@Composable
private fun SidePanel(idx: Int, onOpen: (Int) -> Unit, modifier: Modifier) {
    val accent = deckColor(idx)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("FX" to TAB_FX, "EQ" to TAB_EQ, "CORTE" to TAB_EDIT, "MARCAS" to TAB_MARKS).forEach { (label, tab) ->
            PadButton(label, accent, Modifier.fillMaxWidth().weight(1f), fontSize = 11.sp) { onOpen(tab) }
        }
    }
}

@Composable
private fun CenterMixer(vm: MixViewModel, onLoad: (Int) -> Unit, modifier: Modifier) {
    val a = vm.decks[0]
    val b = vm.decks[1]
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            LoadButton(ColorA) { onLoad(0) }
            LoadButton(ColorB) { onLoad(1) }
        }
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Fader(
                a.params[Param.VOLUME] ?: 1f, 0f, 1.2f, ColorA, true,
                { vm.setParam(0, Param.VOLUME, it) }, Modifier.width(46.dp).fillMaxHeight(),
            )
            Fader(
                b.params[Param.VOLUME] ?: 1f, 0f, 1.2f, ColorB, true,
                { vm.setParam(1, Param.VOLUME, it) }, Modifier.width(46.dp).fillMaxHeight(),
            )
        }
        PadButton(
            "SYNC", Color.White, Modifier.fillMaxWidth().height(38.dp),
            enabled = a.hasBpm && b.hasBpm, filled = true, fontSize = 14.sp,
        ) { vm.syncAuto() }
    }
}

@Composable
private fun LoadButton(color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("♪+", color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

// ───────────────────────── Barra inferior ─────────────────────────

@Composable
private fun BottomBar(vm: MixViewModel) {
    Row(
        Modifier.fillMaxWidth().height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Transport(vm, 0)
        Fader(
            vm.crossfader, 0f, 1f, Color.White, false,
            { vm.moveCrossfader(it) }, Modifier.weight(1f).height(40.dp),
        )
        Transport(vm, 1)
    }
}

/** Play, volver al inicio y marcas de selección «[» «]»; en el deck B van en espejo. */
@Composable
private fun Transport(vm: MixViewModel, idx: Int) {
    val ui = vm.decks[idx]
    val c = deckColor(idx)
    Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (idx == 0) PlayButton(vm, idx)
        if (idx == 0) {
            PadButton("|◀\uFE0E", c, Modifier.width(40.dp).fillMaxHeight(), enabled = ui.loaded) { vm.seek(idx, 0.0) }
        }
        PadButton("[", SelColor, Modifier.width(40.dp).fillMaxHeight(), enabled = ui.loaded, fontSize = 18.sp) {
            vm.markSelStart(idx)
        }
        PadButton("]", SelColor, Modifier.width(40.dp).fillMaxHeight(), enabled = ui.loaded, fontSize = 18.sp) {
            vm.markSelEnd(idx)
        }
        if (idx == 1) {
            PadButton("|◀\uFE0E", c, Modifier.width(40.dp).fillMaxHeight(), enabled = ui.loaded) { vm.seek(idx, 0.0) }
        }
        if (idx == 1) PlayButton(vm, idx)
    }
}

@Composable
private fun PlayButton(vm: MixViewModel, idx: Int) {
    val ui = vm.decks[idx]
    PadButton(
        if (ui.playing) "❚❚" else "▶\uFE0E", // U+FE0E: forzar dibujo como texto, no como emoji
        deckColor(idx),
        Modifier.width(58.dp).fillMaxHeight(),
        enabled = ui.loaded,
        filled = true,
        fontSize = 20.sp,
    ) { vm.togglePlay(idx) }
}
