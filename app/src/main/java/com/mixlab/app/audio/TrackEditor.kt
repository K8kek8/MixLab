package com.mixlab.app.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Edición de pistas en memoria: cortar, copiar, insertar y superponer.
 * Todas las funciones devuelven una pista nueva; la original no se toca (así el "deshacer" es gratis).
 * Las posiciones son frames (muestras por canal) y se ajustan solas a los límites de la pista.
 */
object TrackEditor {
    /** Mini-fundido de ~2 ms a cada lado de cada empalme para evitar el "clic". */
    private const val FADE = 88

    /** Fragmento copiado: audio + tempo de la canción de la que sale. */
    class Clip(val pcm: ShortArray, val sourceBpm: Double, val name: String) {
        val frames: Int get() = pcm.size / 2
        val durationSec: Double get() = frames.toDouble() / SAMPLE_RATE
    }

    /** Copia el tramo [a, b) sin modificar la pista. */
    fun slice(t: Track, a: Int, b: Int): ShortArray {
        val s = a.coerceIn(0, t.frames)
        val e = b.coerceIn(s, t.frames)
        return t.pcm.copyOfRange(2 * s, 2 * e)
    }

    /** Quita el tramo [a, b) y une los dos lados. */
    fun remove(t: Track, a: Int, b: Int, name: String): Track {
        val s = a.coerceIn(0, t.frames)
        val e = b.coerceIn(s, t.frames)
        if (e <= s) return t
        val out = ShortArray(t.pcm.size - 2 * (e - s))
        System.arraycopy(t.pcm, 0, out, 0, 2 * s)
        System.arraycopy(t.pcm, 2 * e, out, 2 * s, t.pcm.size - 2 * e)
        joinFade(out, s)
        return Track(name, out)
    }

    /** Inserta [clip] en el frame [at]; lo que había después se desplaza hacia atrás. */
    fun insert(t: Track, at: Int, clip: ShortArray, name: String): Track {
        val p = at.coerceIn(0, t.frames)
        val m = clip.size / 2
        val out = ShortArray(t.pcm.size + clip.size)
        System.arraycopy(t.pcm, 0, out, 0, 2 * p)
        System.arraycopy(clip, 0, out, 2 * p, clip.size)
        System.arraycopy(t.pcm, 2 * p, out, 2 * p + clip.size, t.pcm.size - 2 * p)
        joinFade(out, p)
        joinFade(out, p + m)
        return Track(name, out)
    }

    /** Mezcla [clip] encima de la pista a partir del frame [at] (la pista crece si hace falta). */
    fun overlay(t: Track, at: Int, clip: ShortArray, gain: Float, name: String): Track {
        val p = at.coerceIn(0, t.frames)
        val m = clip.size / 2
        val total = max(t.frames, p + m)
        val out = ShortArray(total * 2)
        System.arraycopy(t.pcm, 0, out, 0, t.pcm.size)

        val c = clip.copyOf()
        fadeIn(c, 0)
        fadeOut(c, m)
        val base = 2 * p
        for (i in c.indices) {
            val v = out[base + i] + (c[i] * gain).toInt()
            out[base + i] = v.coerceIn(-32768, 32767).toShort()
        }
        return Track(name, out)
    }

    /**
     * Cambia la duración del fragmento por [ratio] (>1 = más largo y más lento, también más grave).
     * Sirve para encajar el tempo de un fragmento de 128 BPM en una pista de 120 BPM.
     */
    fun stretch(pcm: ShortArray, ratio: Double): ShortArray {
        val inF = pcm.size / 2
        if (inF < 2) return pcm
        val outF = max(1, (inF * ratio).toInt())
        val out = ShortArray(outF * 2)
        for (o in 0 until outF) {
            val p = o / ratio
            val i0 = min(p.toInt(), inF - 1)
            val i1 = min(i0 + 1, inF - 1)
            val f = (p - i0).toFloat()
            out[2 * o] = (pcm[2 * i0] + (pcm[2 * i1] - pcm[2 * i0]) * f).toInt().toShort()
            out[2 * o + 1] = (pcm[2 * i0 + 1] + (pcm[2 * i1 + 1] - pcm[2 * i0 + 1]) * f).toInt().toShort()
        }
        return out
    }

    // ---------- Fundidos en los empalmes ----------

    /** Fundido de salida en los FADE frames anteriores a [frame] y de entrada en los posteriores. */
    private fun joinFade(a: ShortArray, frame: Int) {
        fadeOut(a, frame)
        fadeIn(a, frame)
    }

    private fun fadeOut(a: ShortArray, endFrame: Int) {
        val n = min(FADE, endFrame)
        for (i in 0 until n) {
            val f = endFrame - 1 - i
            val g = (i + 1f) / (FADE + 1f)
            if (2 * f + 1 < a.size) {
                a[2 * f] = (a[2 * f] * g).toInt().toShort()
                a[2 * f + 1] = (a[2 * f + 1] * g).toInt().toShort()
            }
        }
    }

    private fun fadeIn(a: ShortArray, startFrame: Int) {
        val total = a.size / 2
        val n = min(FADE, total - startFrame)
        for (i in 0 until n) {
            val f = startFrame + i
            val g = (i + 1f) / (FADE + 1f)
            a[2 * f] = (a[2 * f] * g).toInt().toShort()
            a[2 * f + 1] = (a[2 * f + 1] * g).toInt().toShort()
        }
    }
}
