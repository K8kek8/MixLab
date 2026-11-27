package com.mixlab.app.audio

import kotlin.math.abs
import kotlin.math.pow

/**
 * Un deck: reproduce un [Track] y le aplica EQ → filtro → flanger → eco → reverb → volumen.
 *
 * Los campos @Volatile los escribe la UI y los lee el hilo de audio una vez por bloque.
 * La posición solo la escribe el hilo de audio; la UI pide saltos con [seekRequest].
 */
class Deck(val index: Int) {
    @Volatile var track: Track? = null
        private set
    @Volatile var posFrames = 0.0
    @Volatile var playing = false
    @Volatile var seekRequest = -1.0

    /** true cuando el deck ya "cedió" la mezcla a otro (evita que el modo auto vuelva a él). */
    @Volatile var consumed = false

    @Volatile var inPointSec = -1.0
    @Volatile var outPointSec = -1.0

    /** Ajuste extra de graves que aplica el mezclador en "bass swap". */
    @Volatile var lowOffsetDb = 0f

    @Volatile var volume = 1f
    @Volatile var speed = 1f
    @Volatile var eqLowDb = 0f
    @Volatile var eqMidDb = 0f
    @Volatile var eqHighDb = 0f
    @Volatile var filter = 0f
    @Volatile var echoMix = 0f
    @Volatile var echoTimeMs = 375f
    @Volatile var echoFeedback = 0.4f
    @Volatile var reverbMix = 0f
    @Volatile var reverbSize = 0.6f
    @Volatile var flangerMix = 0f

    // ---- Estado DSP (solo hilo de audio) ----
    private val lowL = Biquad(); private val lowR = Biquad()
    private val midL = Biquad(); private val midR = Biquad()
    private val highL = Biquad(); private val highR = Biquad()
    private val fltL = Biquad(); private val fltR = Biquad()
    private var fltActive = false
    private var lastLow = Float.NaN
    private var lastMid = Float.NaN
    private var lastHigh = Float.NaN
    private var lastFilter = Float.NaN
    private var lastVolume = 1f
    private val echo = StereoEcho(1.0f)
    private val reverb = Reverb()
    private val flanger = Flanger()

    val durationSec: Double get() = track?.durationSec ?: 0.0
    val positionSec: Double get() = posFrames / SAMPLE_RATE

    fun load(t: Track) {
        playing = false
        track = t
        seekRequest = 0.0
        inPointSec = -1.0
        outPointSec = -1.0
        consumed = false
    }

    /**
     * Sustituye la canción conservando el estado de reproducción (se usa al editar la pista).
     * El hilo de audio lee la pista una vez por bloque y es inmutable, así que el cambio es seguro.
     */
    fun replaceTrack(t: Track, positionSec: Double) {
        track = t
        seekRequest = positionSec.coerceIn(0.0, t.durationSec)
        consumed = false
    }

    fun play() {
        val t = track ?: return
        if (posFrames >= t.frames - 2) seekRequest = 0.0
        consumed = false
        playing = true
    }

    fun pause() {
        playing = false
    }

    fun seekSec(sec: Double) {
        val t = track ?: return
        consumed = false
        seekRequest = sec.coerceIn(0.0, t.durationSec)
    }

    fun setParam(p: Param, v: Float) {
        when (p) {
            Param.VOLUME -> volume = v
            Param.SPEED -> speed = v
            Param.EQ_LOW -> eqLowDb = v
            Param.EQ_MID -> eqMidDb = v
            Param.EQ_HIGH -> eqHighDb = v
            Param.FILTER -> filter = v
            Param.ECHO_MIX -> echoMix = v
            Param.ECHO_TIME -> echoTimeMs = v
            Param.ECHO_FEEDBACK -> echoFeedback = v
            Param.REVERB_MIX -> reverbMix = v
            Param.REVERB_SIZE -> reverbSize = v
            Param.FLANGER_MIX -> flangerMix = v
        }
    }

    /** Copia canción, marcas y ajustes (para renderizar la exportación sin tocar el deck en vivo). */
    fun copyFrom(o: Deck) {
        o.track?.let { track = it }
        inPointSec = o.inPointSec
        outPointSec = o.outPointSec
        for (p in Param.values()) {
            setParam(p, o.getParam(p))
        }
        posFrames = 0.0
        playing = false
        consumed = false
    }

    fun getParam(p: Param): Float = when (p) {
        Param.VOLUME -> volume
        Param.SPEED -> speed
        Param.EQ_LOW -> eqLowDb
        Param.EQ_MID -> eqMidDb
        Param.EQ_HIGH -> eqHighDb
        Param.FILTER -> filter
        Param.ECHO_MIX -> echoMix
        Param.ECHO_TIME -> echoTimeMs
        Param.ECHO_FEEDBACK -> echoFeedback
        Param.REVERB_MIX -> reverbMix
        Param.REVERB_SIZE -> reverbSize
        Param.FLANGER_MIX -> flangerMix
    }

    private fun eqDb(db: Float) = if (db <= -29f) -80f else db

    private fun updateCoefs() {
        val low = eqDb(eqLowDb) + lowOffsetDb
        if (low != lastLow) {
            lowL.lowShelf(250f, low); lowR.copyCoefs(lowL); lastLow = low
        }
        val mid = eqDb(eqMidDb)
        if (mid != lastMid) {
            midL.peak(1000f, mid, 0.8f); midR.copyCoefs(midL); lastMid = mid
        }
        val high = eqDb(eqHighDb)
        if (high != lastHigh) {
            highL.highShelf(4000f, high); highR.copyCoefs(highL); lastHigh = high
        }
        val f = filter
        if (f != lastFilter) {
            when {
                abs(f) < 0.02f -> fltActive = false
                f < 0f -> { // pasa-bajos: 18 kHz → 100 Hz
                    val cutoff = 18000f * (100f / 18000f).pow(-f)
                    fltL.lowPass(cutoff, 1.0f); fltR.copyCoefs(fltL); fltActive = true
                }
                else -> { // pasa-altos: 20 Hz → 6 kHz
                    val cutoff = 20f * (6000f / 20f).pow(f)
                    fltL.highPass(cutoff, 1.0f); fltR.copyCoefs(fltL); fltActive = true
                }
            }
            lastFilter = f
        }
    }

    /** Escribe [frames] frames estéreo entrelazados en [buf] (sobrescribe). */
    fun render(buf: FloatArray, frames: Int) {
        val sr = seekRequest
        if (sr >= 0.0) {
            posFrames = sr * SAMPLE_RATE
            seekRequest = -1.0
        }
        updateCoefs()

        val tr = track
        val pcm = tr?.pcm
        val total = tr?.frames ?: 0
        var isPlaying = playing && pcm != null
        var pos = posFrames
        val spd = speed.toDouble()

        val v0 = lastVolume
        val v1 = volume
        val echoSamples = echoTimeMs * SAMPLE_RATE / 1000f
        val eMix = echoMix
        val eFb = echoFeedback
        val rMix = reverbMix
        val rSize = reverbSize
        val fMix = flangerMix
        val filtOn = fltActive

        for (n in 0 until frames) {
            var l = 0f
            var r = 0f

            if (isPlaying && pcm != null) {
                if (pos < total - 1) {
                    val i = pos.toInt()
                    val f = (pos - i).toFloat()
                    val j = i * 2
                    l = (pcm[j] + (pcm[j + 2] - pcm[j]) * f) * (1f / 32768f)
                    r = (pcm[j + 1] + (pcm[j + 3] - pcm[j + 1]) * f) * (1f / 32768f)
                    pos += spd
                } else {
                    isPlaying = false
                    playing = false
                }
            }

            // EQ
            l = highL.process(midL.process(lowL.process(l)))
            r = highR.process(midR.process(lowR.process(r)))
            // Filtro
            if (filtOn) {
                l = fltL.process(l)
                r = fltR.process(r)
            }
            // Flanger
            if (fMix > 0.001f) {
                flanger.process(l, r, fMix)
                l = flanger.outL; r = flanger.outR
            }
            // Eco y reverb (siempre corren para que las colas se apaguen solas)
            echo.process(l, r, echoSamples, eFb, eMix * 0.8f)
            l = echo.outL; r = echo.outR
            reverb.process(l, r, rSize, rMix)
            l = reverb.outL; r = reverb.outR

            // Volumen con rampa para evitar clics
            val g = v0 + (v1 - v0) * (n.toFloat() / frames)
            buf[2 * n] = l * g
            buf[2 * n + 1] = r * g
        }

        posFrames = pos
        lastVolume = v1
    }
}
