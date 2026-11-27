package com.mixlab.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Onda simplificada de una canción, pensada para decidir dónde cortar:
 *  - [peak]: volumen de cada instante (la silueta exterior de la onda).
 *  - [bass]: parte de graves (< ~150 Hz), el "núcleo" brillante. Un drop o un bombo se ven como
 *    núcleo ancho; un break o una parte sin bajo, como silueta hueca.
 * Ambas van de 0 a 1 con [perSec] valores por segundo.
 */
class Waveform(val perSec: Int, val peak: FloatArray, val bass: FloatArray) {
    val size: Int get() = peak.size
    val durationSec: Double get() = size.toDouble() / perSec

    /** Versión reducida de toda la canción para la franja de vista general. */
    val overviewPeak = FloatArray(OVERVIEW_COLS)
    val overviewBass = FloatArray(OVERVIEW_COLS)

    init {
        for (i in 0 until OVERVIEW_COLS) {
            val a = (i.toLong() * size / OVERVIEW_COLS).toInt()
            val b = min(size, max(a + 1, ((i + 1).toLong() * size / OVERVIEW_COLS).toInt()))
            var p = 0f
            var bs = 0f
            for (j in a until b) {
                if (peak[j] > p) p = peak[j]
                if (bass[j] > bs) bs = bass[j]
            }
            overviewPeak[i] = p
            overviewBass[i] = bs
        }
    }

    companion object {
        const val OVERVIEW_COLS = 900
    }
}

object WaveformAnalyzer {
    const val PER_SEC = 50 // un valor cada 20 ms

    fun analyze(track: Track): Waveform {
        val frames = track.frames
        val pcm = track.pcm
        val block = SAMPLE_RATE / PER_SEC
        val n = max(1, (frames + block - 1) / block)
        val peak = FloatArray(n)
        val bass = FloatArray(n)

        // Filtro pasa-bajos de un polo a ~150 Hz para aislar el bombo y el bajo
        val alpha = (1.0 - exp(-2.0 * PI * 150.0 / SAMPLE_RATE)).toFloat()
        var lp = 0f

        for (c in 0 until n) {
            val s0 = c * block
            val s1 = min(frames, s0 + block)
            var p = 0f
            var b = 0f
            for (f in s0 until s1) {
                val l = pcm[2 * f] / 32768f
                val r = pcm[2 * f + 1] / 32768f
                val a = max(abs(l), abs(r))
                if (a > p) p = a
                lp += alpha * ((l + r) * 0.5f - lp)
                val ab = abs(lp)
                if (ab > b) b = ab
            }
            peak[c] = p
            bass[c] = b
        }

        // Normalizar: la silueta usa todo el alto y la compresión (^0.75) hace visibles las partes suaves
        var maxPeak = 1e-4f
        for (v in peak) if (v > maxPeak) maxPeak = v
        for (i in 0 until n) {
            val p = (peak[i] / maxPeak).pow(0.75f).coerceIn(0.02f, 1f)
            peak[i] = p
            bass[i] = (bass[i] / maxPeak).pow(0.75f).coerceIn(0f, p)
        }
        return Waveform(PER_SEC, peak, bass)
    }
}
