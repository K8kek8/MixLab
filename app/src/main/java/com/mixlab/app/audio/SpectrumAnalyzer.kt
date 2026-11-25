package com.mixlab.app.audio

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resultado del análisis de una canción.
 *
 * @param spec    espectrograma: cols * bands valores 0..1 (índice = col * bands + banda; banda 0 = graves)
 * @param peak    amplitud pico normalizada de cada columna (0..1), para dibujar la forma de onda
 * @param waveColors color ARGB por columna según el balance graves/medios/agudos
 */
class Analysis(
    val cols: Int,
    val bands: Int,
    val spec: FloatArray,
    val peak: FloatArray,
    val waveColors: IntArray,
) {
    /** Imagen cols x bands con la banda más aguda arriba. */
    fun toBitmap(): Bitmap {
        val px = IntArray(cols * bands)
        for (y in 0 until bands) {
            val band = bands - 1 - y
            for (x in 0 until cols) {
                px[y * cols + x] = Colormap.at(spec[x * bands + band])
            }
        }
        return Bitmap.createBitmap(px, cols, bands, Bitmap.Config.ARGB_8888)
    }
}

object SpectrumAnalyzer {
    private const val N = 2048
    const val BANDS = 64
    private const val F_MIN = 30.0
    private const val F_MAX = 16000.0

    fun analyze(track: Track): Analysis {
        val frames = track.frames
        val pcm = track.pcm
        val cols = (track.durationSec * 10).toInt().coerceIn(300, 4000) // ~10 columnas por segundo

        val fft = FFT(N)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }

        // Bandas con espaciado logarítmico entre 30 Hz y 16 kHz
        val edges = DoubleArray(BANDS + 1) { F_MIN * (F_MAX / F_MIN).pow(it.toDouble() / BANDS) }
        val binLo = IntArray(BANDS)
        val binHi = IntArray(BANDS)
        for (b in 0 until BANDS) {
            val lo = (edges[b] * N / SAMPLE_RATE).toInt().coerceIn(1, N / 2 - 1)
            val hi = ((edges[b + 1] * N / SAMPLE_RATE).toInt() - 1).coerceIn(lo, N / 2 - 1)
            binLo[b] = lo
            binHi[b] = hi
        }
        // Límites graves / medios / agudos para colorear la onda
        val lowEnd = (0 until BANDS).firstOrNull { edges[it + 1] >= 250.0 } ?: (BANDS / 3)
        val midEnd = (0 until BANDS).firstOrNull { edges[it + 1] >= 4000.0 } ?: (2 * BANDS / 3)

        val spec = FloatArray(cols * BANDS)
        val peak = FloatArray(cols)
        val colors = IntArray(cols)
        val re = FloatArray(N)
        val im = FloatArray(N)

        for (c in 0 until cols) {
            // --- FFT centrada en la columna ---
            val center = ((c + 0.5) * frames / cols).toInt()
            val start = center - N / 2
            for (i in 0 until N) {
                val f = start + i
                val s = if (f in 0 until frames) {
                    (pcm[2 * f] + pcm[2 * f + 1]) * (0.5f / 32768f)
                } else 0f
                re[i] = s * window[i]
                im[i] = 0f
            }
            fft.transform(re, im)

            for (b in 0 until BANDS) {
                var m = 0f
                for (k in binLo[b]..binHi[b]) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    if (mag > m) m = mag
                }
                val amp = m / (N / 4f) // una senoidal a fondo de escala da ~1.0
                val db = 20f * log10(amp + 1e-9f)
                // -80 dB..-15 dB -> 0..1, con una pequeña inclinación para que los agudos no queden apagados
                val v = (db + 80f) / 65f + 0.18f * b / BANDS
                spec[c * BANDS + b] = v.coerceIn(0f, 1f)
            }

            // --- pico de la columna ---
            val s0 = (c.toLong() * frames / cols).toInt()
            val s1 = (((c + 1).toLong() * frames / cols).toInt()).coerceAtMost(frames)
            var p = 0
            for (f in s0 until s1) {
                val a = abs(pcm[2 * f].toInt())
                val bb = abs(pcm[2 * f + 1].toInt())
                if (a > p) p = a
                if (bb > p) p = bb
            }
            peak[c] = p / 32768f

            // --- color según balance de frecuencias ---
            var lo = 0f; var mi = 0f; var hi = 0f
            for (b in 0 until BANDS) {
                val v = spec[c * BANDS + b]
                when {
                    b < lowEnd -> lo += v
                    b < midEnd -> mi += v
                    else -> hi += v
                }
            }
            lo /= max(1, lowEnd)
            mi /= max(1, midEnd - lowEnd)
            hi /= max(1, BANDS - midEnd)
            val mx = max(lo, max(mi, hi)) + 1e-3f
            val bright = 0.55f + 0.45f * mx.coerceAtMost(1f)
            val r = ((mi * 0.9f + hi * 0.5f) / mx * bright).coerceIn(0f, 1f)
            val g = ((mi * 0.55f + hi * 0.9f + lo * 0.1f) / mx * bright).coerceIn(0f, 1f)
            val bl = ((lo * 1.0f + hi * 0.7f) / mx * bright).coerceIn(0f, 1f)
            colors[c] = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
        }

        // Normalizar picos para que la onda use todo el alto disponible
        val maxPeak = peak.max().coerceAtLeast(1e-4f)
        for (i in peak.indices) peak[i] = (peak[i] / maxPeak).pow(0.8f).coerceAtLeast(0.03f)

        return Analysis(cols, BANDS, spec, peak, colors)
    }
}

/** FFT radix-2 iterativa (in-place). */
class FFT(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "n debe ser potencia de 2" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val tr = re[j + half] * cosT[k] + im[j + half] * sinT[k]
                    val ti = im[j + half] * cosT[k] - re[j + half] * sinT[k]
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/** Mapa de color tipo "magma": negro azulado -> violeta -> naranja -> amarillo. */
object Colormap {
    private val stops = intArrayOf(0x05060F, 0x1B2A6B, 0x7B2D8E, 0xF0642C, 0xFFE680)

    fun at(v: Float): Int {
        val t = v.coerceIn(0f, 1f) * (stops.size - 1)
        val i = t.toInt().coerceAtMost(stops.size - 2)
        val f = t - i
        val a = stops[i]
        val b = stops[i + 1]
        val r = lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, f)
        val g = lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, f)
        val bl = lerp(a and 0xFF, b and 0xFF, f)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()
}
