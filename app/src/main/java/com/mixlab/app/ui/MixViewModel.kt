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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mixlab.app.audio.Analysis
import com.mixlab.app.audio.AudioDecoder
import com.mixlab.app.audio.AudioEngine
import com.mixlab.app.audio.BpmDetector
import com.mixlab.app.audio.Deck
import com.mixlab.app.audio.FadeCurve
import com.mixlab.app.audio.MixExporter
import com.mixlab.app.audio.Param
import com.mixlab.app.audio.SpectrumAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
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
    var loadProgress by mutableFloatStateOf(0f)
    var analysis by mutableStateOf<Analysis?>(null)
    var spectrumImage by mutableStateOf<ImageBitmap?>(null)
    var duration by mutableDoubleStateOf(0.0)
    var position by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var inPoint by mutableDoubleStateOf(-1.0)
    var outPoint by mutableDoubleStateOf(-1.0)

    /** Tempo detectado (a velocidad 1.0); 0 = sin detectar. */
    var bpm by mutableDoubleStateOf(0.0)

    /** Instante (s) del primer pulso, entre 0 y la duración de un pulso. */
    var firstBeat by mutableDoubleStateOf(0.0)
    var beatConfidence by mutableFloatStateOf(0f)

    val params = mutableStateMapOf<Param, Float>().apply {
        Param.values().forEach { put(it, it.def) }
    }
    val loaded: Boolean get() = analysis != null
    val hasBpm: Boolean get() = bpm > 0.0
    val beatSec: Double get() = if (bpm > 0.0) 60.0 / bpm else 0.0

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
                ui.status = "Analizando espectro…"
                val analysis = withContext(Dispatchers.Default) { SpectrumAnalyzer.analyze(track) }
                val image = withContext(Dispatchers.Default) { analysis.toBitmap().asImageBitmap() }
                ui.status = "Detectando BPM…"
                val beat = withContext(Dispatchers.Default) { BpmDetector.detect(track) }

                ui.deck.load(track)
                ui.bpm = beat?.bpm ?: 0.0
                ui.firstBeat = beat?.firstBeatSec ?: 0.0
                ui.beatConfidence = beat?.confidence ?: 0f
                ui.title = name
                ui.analysis = analysis
                ui.spectrumImage = image
                ui.duration = track.durationSec
                ui.position = 0.0
                ui.inPoint = -1.0
                ui.outPoint = -1.0
                ui.status = ""
            } catch (e: Exception) {
                ui.status = ""
                message = "No se pudo cargar: ${e.message ?: "formato no soportado"}"
            } finally {
                ui.loading = false
            }
        }
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

    // ---------- Transporte y marcas ----------

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

    // ---------- BPM ----------

    var snapToBeat by mutableStateOf(true); private set

    fun toggleSnap(on: Boolean) {
        snapToBeat = on
    }

    /** Ajusta un instante al pulso más cercano de la rejilla (si hay BPM y el ajuste está activo). */
    private fun snap(ui: DeckUiState, t: Double): Double {
        if (!snapToBeat || !ui.hasBpm) return t
        val p = ui.beatSec
        val k = Math.round((t - ui.firstBeat) / p)
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
     * Si este deck está sonando oirás un pequeño salto: es el "empujón" de beat-match.
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
        return minOf(d, 1.0 - d)
    }

    fun clearMarks(idx: Int) {
        val ui = decks[idx]
        ui.inPoint = -1.0
        ui.outPoint = -1.0
        ui.deck.inPointSec = -1.0
        ui.deck.outPointSec = -1.0
    }

    // ---------- Efectos ----------

    fun setParam(idx: Int, p: Param, v: Float) {
        decks[idx].params[p] = v
        decks[idx].deck.setParam(p, v)
    }

    // ---------- Crossfade ----------

    fun setCrossfader(v: Float) {
        if (mixer.transitioning) return
        crossfader = v
        mixer.crossfader = v
    }

    fun setCurve(c: FadeCurve) {
        curve = c
        mixer.curve = c
    }

    fun setFadeSeconds(s: Float) {
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

    // ---------- Exportar ----------

    fun exportMix(uri: Uri) {
        if (!decks[0].loaded && !decks[1].loaded) {
            message = "Carga al menos una canción"
            return
        }
        exporting = true
        exportProgress = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val app = getApplication<Application>()
                    val tmp = File(app.cacheDir, "mix_export.wav")
                    MixExporter.export(mixer, tmp) { exportProgress = it }
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("No se pudo abrir el destino")
                    tmp.delete()
                }
                message = "Mezcla exportada"
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
}
