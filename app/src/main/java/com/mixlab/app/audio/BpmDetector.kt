package com.mixlab.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * @param bpm          tempo en pulsos por minuto (a velocidad 1.0)
 * @param firstBeatSec instante del primer pulso, siempre entre 0 y la duración de un pulso
 * @param confidence   0..1; por debajo de ~0.3 la detección es dudosa (música sin pulso claro)
 */
class BeatInfo(val bpm: Double, val firstBeatSec: Double, val confidence: Float)

/**
 * Detección de tempo y fase del pulso:
 *  1. Envolvente de onsets por flujo espectral (cuánta energía nueva aparece en cada instante),
 *     combinando toda la banda con un refuerzo de graves, donde vive el bombo.
 *  2. Autocorrelación para hallar el periodo aproximado.
 *  3. Refinado fino del periodo y búsqueda de fase con un "peine" de pulsos sobre toda la canción.
 *
 * Con [detect] y `knownBpm` se salta el paso 2 y 3 (el tempo ya se conoce, por ejemplo tras editar
 * una pista) y solo se recalcula dónde caen los pulsos.
 *
 * Asume tempo constante (lo normal en música electrónica o de baile). Un tren de pulsos es
 * matemáticamente ambiguo entre un tempo y su doble o su mitad (87 ↔ 174), así que la
 * interfaz ofrece ÷2 y ×2 para corregir la octava.
 *
 * Validado con un port a Python sobre pistas sintéticas de 72 a 174 BPM: error de tempo < 0,03 BPM
 * y de fase < 10 ms cuando la octava es la correcta.
 */
object BpmDetector {
    private const val N = 1024
    private const val HOP = 512
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val FPS = SAMPLE_RATE.toDouble() / HOP // onsets por segundo (~86)
    private const val LOW_WEIGHT = 1.5f
    private const val PRIOR_SIGMA_OCTAVES = 1.5 // preferencia muy suave por ~120 BPM

    fun detect(track: Track, knownBpm: Double? = null): BeatInfo? {
        if (track.durationSec < 8.0) return null
        val onset = onsetEnvelope(track) ?: return null
        val n = onset.size

        val smooth = FloatArray(n)
        for (i in 0 until n) {
            val a = onset[max(0, i - 1)]
            val c = onset[min(n - 1, i + 1)]
            smooth[i] = 0.25f * a + 0.5f * onset[i] + 0.25f * c
        }

        var bestP: Double
        var bestPhase = 0
        var bestComb = -1.0

        if (knownBpm != null && knownBpm > 0.0) {
            // Tempo conocido: solo hay que encontrar la fase
            bestP = FPS * 60.0 / knownBpm
            for (ph in 0 until ceil(bestP).toInt()) {
                val s = combScore(smooth, bestP, ph.toDouble())
                if (s > bestComb) {
                    bestComb = s
                    bestPhase = ph
                }
            }
        } else {
            val bestLag = approximateLag(onset) ?: return null
            bestP = bestLag.toDouble()
            var p = bestLag - 1.5
            while (p <= bestLag + 1.5) {
                val steps = ceil(p).toInt()
                for (ph in 0 until steps) {
                    val s = combScore(smooth, p, ph.toDouble())
                    if (s > bestComb) {
                        bestComb = s
                        bestP = p
                        bestPhase = ph
                    }
                }
                p += 0.02
            }
        }
        if (bestComb <= 0.0) return null

        // ---- Fase con precisión sub-muestra y confianza ----
        val sm = combScore(smooth, bestP, bestPhase - 1.0)
        val s0 = combScore(smooth, bestP, bestPhase.toDouble())
        val sp = combScore(smooth, bestP, bestPhase + 1.0)
        val denom = sm - 2 * s0 + sp
        val delta = if (denom < -1e-12) (0.5 * (sm - sp) / denom).coerceIn(-0.5, 0.5) else 0.0
        val phase = bestPhase + delta

        var mean = 0.0
        val steps = ceil(bestP).toInt()
        for (ph in 0 until steps) mean += combScore(smooth, bestP, ph.toDouble())
        mean /= steps
        val confidence = ((bestComb - mean) / bestComb).toFloat().coerceIn(0f, 1f)

        val bpm = if (knownBpm != null && knownBpm > 0.0) knownBpm else FPS * 60.0 / bestP
        val period = 60.0 / bpm
        val t = (phase * HOP + N / 2.0) / SAMPLE_RATE
        var first = t % period
        if (first < 0) first += period
        return BeatInfo(bpm, first, confidence)
    }

    /** Periodo aproximado (en onsets) por autocorrelación de la envolvente suavizada. */
    private fun approximateLag(onset: FloatArray): Int? {
        val n = onset.size

        // Los picos de onset son muy estrechos; suavizarlos evita que la autocorrelación en lags
        // enteros penalice los periodos cuyo valor real cae entre dos muestras.
        val wide = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            var w = 0f
            for (d in -2..2) {
                val j = i + d
                if (j in 0 until n) {
                    val wt = (3 - abs(d)).toFloat() // 1 2 3 2 1
                    s += onset[j] * wt
                    w += wt
                }
            }
            wide[i] = s / w
        }

        val lagMin = floor(FPS * 60.0 / MAX_BPM).toInt()
        val lagMax = ceil(FPS * 60.0 / MIN_BPM).toInt()
        val maxLag = lagMax * 2 + 1
        if (n <= maxLag * 2) return null
        val acf = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var s = 0.0
            for (i in 0 until n - lag) s += wide[i] * wide[i + lag]
            acf[lag] = s / (n - lag)
        }
        var bestLag = lagMin
        var bestScore = -1.0
        for (lag in lagMin..lagMax) {
            val bpm = FPS * 60.0 / lag
            val octaves = ln(bpm / 120.0) / ln(2.0)
            val prior = exp(-0.5 * (octaves / PRIOR_SIGMA_OCTAVES).pow(2))
            val doubleLag = max(acf[2 * lag - 1], max(acf[2 * lag], acf[2 * lag + 1]))
            val score = (acf[lag] + 0.5 * doubleLag) * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return if (bestScore <= 1e-9) null else bestLag
    }

    /** Media del onset en los pulsos phase, phase+p, phase+2p... (interpolación lineal). */
    private fun combScore(env: FloatArray, p: Double, phase: Double): Double {
        var sum = 0.0
        var cnt = 0
        var pos = phase
        val last = env.size - 1
        while (pos <= last) {
            val i = floor(pos).toInt()
            if (i >= 0) {
                val f = (pos - i).toFloat()
                val v = if (i < last) env[i] * (1f - f) + env[i + 1] * f else env[i]
                sum += v
                cnt++
            }
            pos += p
        }
        return if (cnt > 0) sum / cnt else 0.0
    }

    /**
     * Flujo espectral entre 0 y 8 kHz más el flujo de graves (< 250 Hz) con más peso.
     * Cada banda se normaliza por su media, así el bombo manda sobre los hi-hats a contratiempo
     * a la hora de decidir en qué instante cae el pulso.
     */
    private fun onsetEnvelope(track: Track): FloatArray? {
        val frames = track.frames
        val pcm = track.pcm
        val nHops = (frames - N) / HOP
        if (nHops < FPS * 8) return null

        val fft = FFT(N)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
        val re = FloatArray(N)
        val im = FloatArray(N)
        val prev = FloatArray(N / 2)
        val flux = FloatArray(nHops)
        val lowFlux = FloatArray(nHops)
        val kMax = (8000.0 * N / SAMPLE_RATE).toInt()
        val kLow = (250.0 * N / SAMPLE_RATE).toInt()

        for (h in 0 until nHops) {
            val s0 = h * HOP
            for (i in 0 until N) {
                val f = s0 + i
                re[i] = (pcm[2 * f] + pcm[2 * f + 1]) * (0.5f / 32768f) * window[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            var acc = 0f
            var accLow = 0f
            for (k in 1..kMax) {
                val mag = ln(1f + 30f * sqrt(re[k] * re[k] + im[k] * im[k]))
                val d = mag - prev[k]
                if (d > 0f) {
                    acc += d
                    if (k <= kLow) accLow += d
                }
                prev[k] = mag
            }
            flux[h] = acc
            lowFlux[h] = accLow
        }

        val full = detrend(flux)
        val low = detrend(lowFlux)
        val meanFull = full.average().toFloat() + 1e-9f
        val meanLow = low.average().toFloat() + 1e-9f
        return FloatArray(nHops) { full[it] / meanFull + LOW_WEIGHT * low[it] / meanLow }
    }

    /** Resta la media local (±0,5 s) y rectifica: deja solo los "golpes" sobre el nivel de fondo. */
    private fun detrend(x: FloatArray): FloatArray {
        val n = x.size
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + x[i]
        val half = 21
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n, i + half + 1)
            val mean = (pre[hi] - pre[lo]) / (hi - lo)
            out[i] = max(0f, (x[i] - mean).toFloat())
        }
        return out
    }
}
