package com.mixlab.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mixlab.app.audio.AudioDecoder
import com.mixlab.app.audio.AudioEngine
import com.mixlab.app.audio.BpmDetector
import com.mixlab.app.audio.Deck
import com.mixlab.app.audio.FadeCurve
import com.mixlab.app.audio.MixExporter
import com.mixlab.app.audio.Param
import com.mixlab.app.audio.SAMPLE_RATE
import com.mixlab.app.audio.Track
import com.mixlab.app.audio.TrackEditor
import com.mixlab.app.audio.Waveform
import com.mixlab.app.audio.WaveformAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado observable por Compose de un deck. */
@Stable
class DeckUiState(val deck: Deck) {
    var title by mutableStateOf("Sin canción")
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var loadProgress by mutableFloatStateOf(0f)
    var wave by mutableStateOf<Waveform?>(null)
    var duration by mutableDoubleStateOf(0.0)
    var position by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)

    /** Marcas de la transición (IN = dónde entra, OUT = dónde empieza a salir). -1 = sin marca. */
    var inPoint by mutableDoubleStateOf(-1.0)
    var outPoint by mutableDoubleStateOf(-1.0)

    /** Selección para cortar/copiar: «[» y «]». -1 = sin marcar. */
    var selStart by mutableDoubleStateOf(-1.0)
    var selEnd by mutableDoubleStateOf(-1.0)

    /** Tempo detectado (a velocidad 1.0); 0 = sin detectar. */
    var bpm by mutableDoubleStateOf(0.0)

    /** Instante (s) del primer pulso, entre 0 y la duración de un pulso. */
    var firstBeat by mutableDoubleStateOf(0.0)
    var beatConfidence by mutableFloatStateOf(0f)
    var canUndo by mutableStateOf(false)

    val params = mutableStateMapOf<Param, Float>().apply {
        Param.values().forEach { put(it, it.def) }
    }

    val loaded: Boolean get() = wave != null
    val hasBpm: Boolean get() = bpm > 0.0
    val beatSec: Double get() = if (bpm > 0.0) 60.0 / bpm else 0.0
    val hasSel: Boolean get() = selStart >= 0.0 && selEnd >= 0.0 && abs(selEnd - selStart) > 0.02
    val selLo: Double get() = min(selStart, selEnd)
    val selHi: Double get() = max(selStart, selEnd)

    /** BPM real teniendo en cuenta el control de velocidad. */
    val effectiveBpm: Double get() = bpm * (params[Param.SPEED] ?: 1f)
}

class MixViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = AudioEngine()
    private val mixer = engine.mixer

    val decks = listOf(DeckUiState(mixer.deckA), DeckUiState(mixer.deckB))

    var crossfader by mutableFloatStateOf(0f); private set
    var curve by mutableStateOf(FadeCurve.EQUAL_POWER); private set
    var fadeSeconds by mutableFloatStateOf(8f); private set
    var bassSwap by mutableStateOf(false); private set
    var autoMix by mutableStateOf(false); private set
    var transitioning by mutableStateOf(false); private set
    var exporting by mutableStateOf(false); private set
    var exportProgress by mutableFloatStateOf(0f); private set
    var message by mutableStateOf<String?>(null); private set

    /** Segundos de canción visibles en las ondas grandes. */
    var zoomSec by mutableFloatStateOf(8f); private set
    var snapToBeat by mutableStateOf(true); private set

    /** Fragmento copiado/cortado, listo para pegar en cualquier deck. */
    var clipboard by mutableStateOf<TrackEditor.Clip?>(null); private set

    /** Al pegar, ajusta el tempo del fragmento al de la pista de destino. */
    var adjustTempo by mutableStateOf(true); private set

    private class Snapshot(
        val track: Track,
        val wave: Waveform,
        val bpm: Double,
        val firstBeat: Double,
        val title: String,
    )

    private val undo = listOf(ArrayDeque<Snapshot>(), ArrayDeque<Snapshot>())
    private val scrubPos = DoubleArray(2)
    private val scrubWasPlaying = BooleanArray(2)

    init {
        engine.start()
        viewModelScope.launch {
            while (isActive) {
                sync()
                delay(33)
            }
        }
    }

    /** Copia el estado del hilo de audio a los estados de Compose. */
    private fun sync() {
        for (ui in decks) {
            ui.position = ui.deck.positionSec
            ui.playing = ui.deck.playing
        }
        transitioning = mixer.transitioning
        crossfader = mixer.crossfader
    }

    // ---------- Carga ----------

    fun loadTrack(idx: Int, uri: Uri) {
        val ui = decks[idx]
        if (ui.busy || ui.loading) return
        ui.deck.pause()
        ui.loading = true
        ui.loadProgress = 0f
        ui.status = "Decodificando…"
        viewModelScope.launch {
            try {
                val name = queryName(uri)
                val track = withContext(Dispatchers.Default) {
                    AudioDecoder.decode(getApplication<Application>(), uri, name) { ui.loadProgress = it }
                }
                installTrack(idx, track, null)
            } catch (e: OutOfMemoryError) {
                message = "Memoria insuficiente: prueba con una canción más corta"
            } catch (e: Exception) {
                message = "No se pudo cargar: ${e.message ?: "formato no soportado"}"
            } finally {
                ui.loading = false
                ui.status = ""
            }
        }
    }

    /** Dibuja la onda, detecta el tempo y deja la pista lista en el deck. */
    private suspend fun installTrack(idx: Int, track: Track, knownBpm: Double?) {
        val ui = decks[idx]
        ui.status = "Dibujando onda…"
        val wave = withContext(Dispatchers.Default) { WaveformAnalyzer.analyze(track) }
        ui.status = "Detectando BPM…"
        val beat = withContext(Dispatchers.Default) { BpmDetector.detect(track, knownBpm) }

        ui.deck.load(track)
        ui.title = track.name
        ui.wave = wave
        ui.duration = track.durationSec
        ui.position = 0.0
        ui.bpm = beat?.bpm ?: knownBpm ?: 0.0
        ui.firstBeat = beat?.firstBeatSec ?: 0.0
        ui.beatConfidence = beat?.confidence ?: 0f
        clearMarksAndSel(ui)
        undo[idx].clear()
        ui.canUndo = false
    }

    private fun queryName(uri: Uri): String {
        val n = try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return n ?: "Canción"
    }

    // ---------- Transporte ----------

    fun togglePlay(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        if (ui.deck.playing) ui.deck.pause() else ui.deck.play()
    }

    fun seek(idx: Int, sec: Double) {
        if (!decks[idx].loaded) return
        decks[idx].deck.seekSec(sec)
        decks[idx].position = sec.coerceIn(0.0, decks[idx].duration)
    }

    // Scrub: arrastrar el disco o la onda. Mientras se arrastra el deck se silencia y, al soltar,
    // sigue sonando si lo estaba.
    fun scrubStart(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        scrubWasPlaying[idx] = ui.deck.playing
        scrubPos[idx] = ui.deck.positionSec
        ui.deck.pause()
    }

    fun scrubBy(idx: Int, deltaSec: Double) {
        val ui = decks[idx]
        if (!ui.loaded) return
        scrubPos[idx] = (scrubPos[idx] + deltaSec).coerceIn(0.0, ui.duration)
        ui.deck.seekSec(scrubPos[idx])
        ui.position = scrubPos[idx]
    }

    fun scrubEnd(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        if (scrubWasPlaying[idx]) ui.deck.play()
        scrubWasPlaying[idx] = false
    }

    fun cycleZoom() {
        zoomSec = when {
            zoomSec < 6f -> 8f
            zoomSec < 12f -> 16f
            zoomSec < 24f -> 32f
            else -> 4f
        }
    }

    // ---------- Marcas de transición (IN / OUT) ----------

    fun markIn(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        ui.inPoint = snap(ui, ui.deck.positionSec)
        ui.deck.inPointSec = ui.inPoint
    }

    fun markOut(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        ui.outPoint = snap(ui, ui.deck.positionSec)
        ui.deck.outPointSec = ui.outPoint
    }

    fun clearMarks(idx: Int) {
        val ui = decks[idx]
        ui.inPoint = -1.0
        ui.outPoint = -1.0
        ui.deck.inPointSec = -1.0
        ui.deck.outPointSec = -1.0
    }

    private fun clearMarksAndSel(ui: DeckUiState) {
        clearMarks(ui.deck.index)
        ui.selStart = -1.0
        ui.selEnd = -1.0
    }

    // ---------- Beat / rejilla ----------

    fun toggleSnap(on: Boolean) {
        snapToBeat = on
    }

    /** Ajusta un instante al pulso más cercano de la rejilla (si hay BPM y el ajuste está activo). */
    private fun snap(ui: DeckUiState, t: Double): Double {
        if (!snapToBeat || !ui.hasBpm) return t
        val p = ui.beatSec
        val k = round((t - ui.firstBeat) / p)
        return (ui.firstBeat + k * p).coerceIn(0.0, ui.duration)
    }

    /** Corrige un error de octava: ×2 o ÷2 (la rejilla de beats se reajusta sola). */
    fun scaleBpm(idx: Int, factor: Double) {
        val ui = decks[idx]
        if (!ui.hasBpm) return
        ui.bpm = (ui.bpm * factor).coerceIn(40.0, 300.0)
    }

    /** Coloca la rejilla de modo que un pulso caiga justo en la posición actual del cabezal. */
    fun setGridHere(idx: Int) {
        val ui = decks[idx]
        if (!ui.hasBpm) return
        val p = ui.beatSec
        ui.firstBeat = ((ui.deck.positionSec % p) + p) % p
    }

    /** SYNC del centro: ajusta el deck que no es el principal (según el lado del crossfader). */
    fun syncAuto() {
        syncBpm(if (crossfader < 0.5f) 1 else 0)
    }

    /**
     * Beat-match: iguala el tempo del deck [idx] al del otro deck (cambiando su velocidad) y alinea
     * la fase de los golpes. Si hace falta, usa el doble o la mitad del tempo (128 ↔ 64).
     * Rango admitido: ±15 %.
     */
    fun syncBpm(idx: Int) {
        val me = decks[idx]
        val other = decks[1 - idx]
        if (!me.hasBpm || !other.hasBpm) {
            message = "Hace falta el BPM de las dos canciones"
            return
        }
        val target = other.effectiveBpm
        // (multiplicador del tempo del otro deck, velocidad necesaria en este deck)
        val best = listOf(1.0, 2.0, 0.5)
            .map { it to target * it / me.bpm }
            .filter { it.second in 0.85..1.15 }
            .minByOrNull { abs(it.second - 1.0) }
        if (best == null) {
            message = "Los tempos difieren demasiado para sincronizar (máx. ±15 %)"
            return
        }
        setParam(idx, Param.SPEED, best.second.toFloat())
        alignPhase(idx, best.first)
        message = "Sincronizado a %.1f BPM".format(me.effectiveBpm)
    }

    /**
     * Desplaza el cabezal de este deck (como mucho medio beat) para que sus golpes caigan a la vez
     * que los del otro deck. [mult] = 2 o 0,5 cuando se sincronizó al doble o a la mitad del tempo.
     */
    private fun alignPhase(idx: Int, mult: Double) {
        val me = decks[idx]
        val other = decks[1 - idx]
        val fOther = frac((other.deck.positionSec - other.firstBeat) / other.beatSec)
        val fMe = frac((me.deck.positionSec - me.firstBeat) / me.beatSec)
        val wanted = when (mult) {
            2.0 -> frac(2 * fOther)
            0.5 -> listOf(fOther / 2, (fOther + 1) / 2).minByOrNull { circularDist(it, fMe) } ?: fOther
            else -> fOther
        }
        var d = wanted - fMe
        d -= round(d) // lo lleva al intervalo (-0,5; 0,5]: se mueve por el camino más corto
        seek(idx, me.deck.positionSec + d * me.beatSec)
    }

    private fun frac(x: Double) = x - floor(x)

    private fun circularDist(a: Double, b: Double): Double {
        val d = abs(a - b)
        return min(d, 1.0 - d)
    }

    // ---------- Efectos ----------

    fun setParam(idx: Int, p: Param, v: Float) {
        decks[idx].params[p] = v
        decks[idx].deck.setParam(p, v)
    }

    // ---------- Crossfade ----------

    fun moveCrossfader(v: Float) {
        if (mixer.transitioning) return
        crossfader = v
        mixer.crossfader = v
    }

    fun chooseCurve(c: FadeCurve) {
        curve = c
        mixer.curve = c
    }

    fun changeFadeSeconds(s: Float) {
        fadeSeconds = s
        mixer.fadeSeconds = s
    }

    fun toggleBassSwap(on: Boolean) {
        bassSwap = on
        mixer.bassSwap = on
    }

    fun toggleAutoMix(on: Boolean) {
        autoMix = on
        mixer.autoMix = on
    }

    fun crossfadeNow() {
        if (!decks[0].loaded || !decks[1].loaded) {
            message = "Carga una canción en cada deck"
            return
        }
        mixer.transitionRequest = true
    }

    // ---------- Edición: cortar, copiar, pegar ----------

    fun markSelStart(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        ui.selStart = snap(ui, ui.deck.positionSec)
    }

    fun markSelEnd(idx: Int) {
        val ui = decks[idx]
        if (!ui.loaded) return
        ui.selEnd = snap(ui, ui.deck.positionSec)
    }

    fun clearSel(idx: Int) {
        decks[idx].selStart = -1.0
        decks[idx].selEnd = -1.0
    }

    fun toggleAdjustTempo(on: Boolean) {
        adjustTempo = on
    }

    fun copySel(idx: Int) {
        val ui = decks[idx]
        val t = ui.deck.track ?: return
        if (!ui.hasSel) {
            message = SEL_HELP
            return
        }
        val c = TrackEditor.Clip(
            TrackEditor.slice(t, (ui.selLo * SAMPLE_RATE).toInt(), (ui.selHi * SAMPLE_RATE).toInt()),
            ui.bpm,
            ui.title,
        )
        clipboard = c
        message = "Copiado: %.1f s".format(c.durationSec)
    }

    fun cutSel(idx: Int) = removeSel(idx, keepCopy = true)

    fun deleteSel(idx: Int) = removeSel(idx, keepCopy = false)

    private fun removeSel(idx: Int, keepCopy: Boolean) {
        val ui = decks[idx]
        val t = ui.deck.track ?: return
        if (ui.busy || ui.loading) return
        if (!ui.hasSel) {
            message = SEL_HELP
            return
        }
        val a = (ui.selLo * SAMPLE_RATE).toInt()
        val b = (ui.selHi * SAMPLE_RATE).toInt()
        val at = ui.selLo
        val name = editedName(ui.title)
        if (keepCopy) clipboard = TrackEditor.Clip(TrackEditor.slice(t, a, b), ui.bpm, ui.title)
        runEdit(idx) {
            val nt = withContext(Dispatchers.Default) { TrackEditor.remove(t, a, b, name) }
            commitEdit(idx, nt, at)
            message = if (keepCopy) "Cortado: queda en el portapapeles" else "Parte eliminada"
        }
    }

    /**
     * Pega el portapapeles en la posición del cabezal (ajustada al beat). Con [overlay] el fragmento se
     * mezcla encima en vez de empujar el resto de la pista. En un deck vacío, el fragmento pasa a ser la pista.
     */
    fun paste(idx: Int, overlay: Boolean) {
        val c = clipboard
        if (c == null) {
            message = "El portapapeles está vacío: copia o corta una parte primero"
            return
        }
        val ui = decks[idx]
        if (ui.busy || ui.loading) return
        val t = ui.deck.track
        if (t == null) {
            runEdit(idx) {
                installTrack(idx, Track(editedName(c.name), c.pcm), c.sourceBpm.takeIf { it > 0.0 })
                message = "Fragmento cargado como pista nueva"
            }
            return
        }

        val target = ui.bpm
        val doStretch = adjustTempo && target > 0.0 && c.sourceBpm > 0.0 && abs(c.sourceBpm - target) > 0.05
        val ratio = if (doStretch) c.sourceBpm / target else 1.0
        if (doStretch && ratio !in 0.5..2.0) {
            message = "Tempos demasiado distintos para ajustar (%.0f y %.0f BPM)".format(c.sourceBpm, target)
            return
        }
        val atSec = snap(ui, ui.deck.positionSec)
        val at = (atSec * SAMPLE_RATE).toInt()
        val name = editedName(ui.title)
        runEdit(idx) {
            val nt = withContext(Dispatchers.Default) {
                val data = if (doStretch) TrackEditor.stretch(c.pcm, ratio) else c.pcm
                if (overlay) TrackEditor.overlay(t, at, data, 0.8f, name) else TrackEditor.insert(t, at, data, name)
            }
            commitEdit(idx, nt, atSec)
            message = if (overlay) "Fragmento superpuesto" else "Fragmento pegado"
        }
    }

    fun undo(idx: Int) {
        val ui = decks[idx]
        val st = undo[idx]
        if (st.isEmpty() || ui.busy || ui.loading) return
        val s = st.removeLast()
        ui.deck.replaceTrack(s.track, ui.deck.positionSec)
        ui.wave = s.wave
        ui.duration = s.track.durationSec
        ui.bpm = s.bpm
        ui.firstBeat = s.firstBeat
        ui.title = s.title
        clearMarksAndSel(ui)
        ui.canUndo = st.isNotEmpty()
        message = "Edición deshecha"
    }

    private fun editedName(title: String) = if (title.endsWith(" ✂")) title else "$title ✂"

    /** Ejecuta una edición en segundo plano marcando el deck como ocupado y mostrando errores. */
    private fun runEdit(idx: Int, block: suspend () -> Unit) {
        val ui = decks[idx]
        ui.busy = true
        ui.status = "Editando…"
        viewModelScope.launch {
            try {
                block()
            } catch (e: OutOfMemoryError) {
                message = "Memoria insuficiente para esta edición"
            } catch (e: Exception) {
                message = "Error al editar: ${e.message}"
            } finally {
                ui.busy = false
                ui.status = ""
            }
        }
    }

    /** Pone la pista editada en el deck, recalcula onda y rejilla de beats, y guarda el estado anterior. */
    private suspend fun commitEdit(idx: Int, newTrack: Track, positionSec: Double) {
        val ui = decks[idx]
        val old = ui.deck.track
        val oldWave = ui.wave
        val knownBpm = ui.bpm
        ui.status = "Recalculando onda…"
        val result = withContext(Dispatchers.Default) {
            val w = WaveformAnalyzer.analyze(newTrack)
            val b = if (knownBpm > 0.0) BpmDetector.detect(newTrack, knownBpm) else null
            Pair(w, b)
        }
        if (old != null && oldWave != null) {
            val st = undo[idx]
            if (st.size >= MAX_UNDO) st.removeFirst()
            st.addLast(Snapshot(old, oldWave, ui.bpm, ui.firstBeat, ui.title))
        }
        ui.deck.replaceTrack(newTrack, positionSec)
        ui.wave = result.first
        ui.duration = newTrack.durationSec
        ui.title = newTrack.name
        result.second?.let { ui.firstBeat = it.firstBeatSec }
        ui.position = positionSec.coerceIn(0.0, newTrack.durationSec)
        clearMarksAndSel(ui)
        ui.canUndo = undo[idx].isNotEmpty()
    }

    // ---------- Exportar ----------

    fun exportMix(uri: Uri) {
        if (!decks[0].loaded && !decks[1].loaded) {
            message = "Carga al menos una canción"
            return
        }
        runExport(uri, "Mezcla exportada") { tmp -> MixExporter.export(mixer, tmp) { exportProgress = it } }
    }

    /** Guarda la pista de un deck (por ejemplo, una ya editada) como WAV. */
    fun exportDeck(idx: Int, uri: Uri) {
        val t = decks[idx].deck.track
        if (t == null) {
            message = "Carga una canción en este deck"
            return
        }
        runExport(uri, "Pista guardada") { tmp -> MixExporter.exportTrack(t, tmp) { exportProgress = it } }
    }

    private fun runExport(uri: Uri, okMessage: String, render: (File) -> Unit) {
        exporting = true
        exportProgress = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val app = getApplication<Application>()
                    val tmp = File(app.cacheDir, "mix_export.wav")
                    render(tmp)
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("No se pudo abrir el destino")
                    tmp.delete()
                }
                message = okMessage
            } catch (e: Exception) {
                message = "Error al exportar: ${e.message}"
            } finally {
                exporting = false
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_UNDO = 3
        const val SEL_HELP = "Marca el inicio con « [ » y el final con « ] » (junto al botón de play)"
    }
}
