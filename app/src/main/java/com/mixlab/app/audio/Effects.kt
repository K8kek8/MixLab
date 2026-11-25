package com.mixlab.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filtro biquad (forma transpuesta II) con coeficientes de la "Audio EQ Cookbook" de R. Bristow-Johnson. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun copyCoefs(o: Biquad) {
        b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat()
        b1 = (nb1 / na0).toFloat()
        b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat()
        a2 = (na2 / na0).toFloat()
    }

    fun lowShelf(f0: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / SAMPLE_RATE
        val cw = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0) // S = 1
        val sa = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * cw + sa),
            2 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - sa),
            (a + 1) + (a - 1) * cw + sa,
            -2 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - sa,
        )
    }

    fun highShelf(f0: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / SAMPLE_RATE
        val cw = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val sa = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) + (a - 1) * cw + sa),
            -2 * a * ((a - 1) + (a + 1) * cw),
            a * ((a + 1) + (a - 1) * cw - sa),
            (a + 1) - (a - 1) * cw + sa,
            2 * ((a - 1) - (a + 1) * cw),
            (a + 1) - (a - 1) * cw - sa,
        )
    }

    fun peak(f0: Float, gainDb: Float, q: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / SAMPLE_RATE
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
    }

    fun lowPass(f0: Float, q: Float) {
        val w0 = 2.0 * PI * f0 / SAMPLE_RATE
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    fun highPass(f0: Float, q: Float) {
        val w0 = 2.0 * PI * f0 / SAMPLE_RATE
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }
}

/** Eco estéreo "ping-pong" con tiempo suavizado. Lee el resultado en [outL] / [outR]. */
class StereoEcho(maxSeconds: Float = 1.0f) {
    private val size = (maxSeconds * SAMPLE_RATE).toInt() + 4
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var w = 0
    private var curDelay = 0f

    var outL = 0f
        private set
    var outR = 0f
        private set

    fun process(inL: Float, inR: Float, delaySamples: Float, feedback: Float, mix: Float) {
        if (curDelay == 0f) curDelay = delaySamples
        curDelay += (delaySamples - curDelay) * 0.0005f

        var rp = w - curDelay
        if (rp < 0f) rp += size
        val i0 = rp.toInt() % size
        val i1 = if (i0 + 1 >= size) 0 else i0 + 1
        val f = rp - rp.toInt()
        val dl = bufL[i0] * (1f - f) + bufL[i1] * f
        val dr = bufR[i0] * (1f - f) + bufR[i1] * f

        bufL[w] = inL + dr * feedback // ping-pong: cada lado alimenta al otro
        bufR[w] = inR + dl * feedback
        w++
        if (w >= size) w = 0

        outL = inL + dl * mix
        outR = inR + dr * mix
    }
}

/** Reverb tipo Freeverb simplificada: 4 filtros peine + 2 pasa-todo por canal. */
class Reverb {
    private class Comb(n: Int) {
        private val buf = FloatArray(n)
        private var i = 0
        private var store = 0f
        fun process(input: Float, fb: Float, damp: Float): Float {
            val o = buf[i]
            store = o * (1f - damp) + store * damp
            buf[i] = input + store * fb
            if (++i >= buf.size) i = 0
            return o
        }
    }

    private class AllPass(n: Int) {
        private val buf = FloatArray(n)
        private var i = 0
        fun process(input: Float): Float {
            val b = buf[i]
            val o = -input + b
            buf[i] = input + b * 0.5f
            if (++i >= buf.size) i = 0
            return o
        }
    }

    private val combL = arrayOf(Comb(1116), Comb(1188), Comb(1277), Comb(1356))
    private val combR = arrayOf(Comb(1139), Comb(1211), Comb(1300), Comb(1379))
    private val apL = arrayOf(AllPass(556), AllPass(441))
    private val apR = arrayOf(AllPass(579), AllPass(464))

    var outL = 0f
        private set
    var outR = 0f
        private set

    fun process(inL: Float, inR: Float, size: Float, mix: Float) {
        val fb = 0.70f + 0.28f * size
        val damp = 0.35f
        val input = (inL + inR) * 0.5f * 0.2f
        var sl = 0f
        var sr = 0f
        for (k in 0 until 4) {
            sl += combL[k].process(input, fb, damp)
            sr += combR[k].process(input, fb, damp)
        }
        for (k in 0 until 2) {
            sl = apL[k].process(sl)
            sr = apR[k].process(sr)
        }
        outL = inL + sl * mix * 1.5f
        outR = inR + sr * mix * 1.5f
    }
}

/** Flanger: línea de retardo corta modulada por un LFO lento. */
class Flanger {
    private val size = 2048
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var w = 0
    private var phase = 0.0

    var outL = 0f
        private set
    var outR = 0f
        private set

    fun process(inL: Float, inR: Float, mix: Float) {
        phase += LFO_HZ / SAMPLE_RATE
        if (phase >= 1.0) phase -= 1.0
        val lfoL = (0.5 + 0.5 * sin(2.0 * PI * phase)).toFloat()
        val lfoR = (0.5 + 0.5 * sin(2.0 * PI * (phase + 0.25))).toFloat()
        val ms = SAMPLE_RATE / 1000f
        val dl = readDelayed(bufL, (1.0f + 4.0f * lfoL) * ms)
        val dr = readDelayed(bufR, (1.0f + 4.0f * lfoR) * ms)
        bufL[w] = inL + dl * 0.5f
        bufR[w] = inR + dr * 0.5f
        w++
        if (w >= size) w = 0
        outL = inL + dl * mix
        outR = inR + dr * mix
    }

    private fun readDelayed(buf: FloatArray, delay: Float): Float {
        var rp = w - delay
        if (rp < 0f) rp += size
        val i0 = rp.toInt() % size
        val i1 = if (i0 + 1 >= size) 0 else i0 + 1
        val f = rp - rp.toInt()
        return buf[i0] * (1f - f) + buf[i1] * f
    }

    private companion object {
        const val LFO_HZ = 0.3
    }
}
