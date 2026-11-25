package com.mixlab.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Mezcla dos decks con crossfader, curvas, "bass swap" y transiciones automáticas.
 * [render] lo llama tanto el motor en vivo como el exportador (misma lógica = mismo resultado).
 */
class Mixer {
    val deckA = Deck(0)
    val deckB = Deck(1)

    /** 0 = solo A, 1 = solo B. Durante una transición lo escribe el hilo de audio. */
    @Volatile var crossfader = 0f
    @Volatile var curve = FadeCurve.EQUAL_POWER
    @Volatile var fadeSeconds = 8f
    @Volatile var bassSwap = false
    @Volatile var autoMix = false
    @Volatile var master = 0.9f

    @Volatile var transitionRequest = false
    @Volatile var transitioning = false
        private set

    // Estado de la transición (solo hilo de audio)
    private var transActive = false
    private var transFrom = 0f
    private var transTo = 1f
    private var transTotal = 1
    private var transDone = 0
    private var transSrc: Deck? = null

    private var gA0 = 1f
    private var gB0 = 0f
    private var gA1 = 1f
    private var gB1 = 0f
    private val bufA = FloatArray(MAX_FRAMES * 2)
    private val bufB = FloatArray(MAX_FRAMES * 2)

    fun deck(i: Int) = if (i == 0) deckA else deckB

    fun anyPlaying() = deckA.playing || deckB.playing

    /** Mezclador independiente con los mismos ajustes, listo para renderizar la mezcla completa offline. */
    fun cloneForExport(): Mixer {
        val m = Mixer()
        m.deckA.copyFrom(deckA)
        m.deckB.copyFrom(deckB)
        m.curve = curve
        m.fadeSeconds = fadeSeconds
        m.bassSwap = bassSwap
        m.master = master
        m.autoMix = true
        if (m.deckA.track != null) {
            m.crossfader = 0f
            m.deckA.playing = true
        } else {
            m.crossfader = 1f
            m.deckB.playing = true
        }
        m.gA0 = if (m.crossfader < 0.5f) 1f else 0f
        m.gB0 = 1f - m.gA0
        return m
    }

    private fun beginTransition() {
        val toB = crossfader < 0.5f
        val src = if (toB) deckA else deckB
        val dst = if (toB) deckB else deckA
        if (dst.track == null || transActive) return

        if (!dst.playing) {
            if (dst.inPointSec >= 0.0) dst.posFrames = dst.inPointSec * SAMPLE_RATE
            else if (dst.posFrames >= (dst.track?.frames ?: 0) - 2) dst.posFrames = 0.0
            dst.consumed = false
            dst.playing = true
        }
        transFrom = crossfader
        transTo = if (toB) 1f else 0f
        transTotal = (fadeSeconds * SAMPLE_RATE).toInt().coerceAtLeast(1)
        transDone = 0
        transSrc = src
        transActive = true
        transitioning = true
    }

    private fun checkAuto() {
        val aMain = crossfader < 0.5f
        val main = if (aMain) deckA else deckB
        val other = if (aMain) deckB else deckA
        val t = main.track ?: return
        if (!main.playing || other.track == null || other.consumed) return
        val trigger = if (main.outPointSec >= 0.0) {
            main.outPointSec * SAMPLE_RATE
        } else {
            t.frames - fadeSeconds.toDouble() * SAMPLE_RATE * main.speed
        }
        if (main.posFrames >= trigger) beginTransition()
    }

    fun render(out: FloatArray, frames: Int) {
        require(frames <= MAX_FRAMES)

        if (transitionRequest) {
            transitionRequest = false
            beginTransition()
        }
        if (autoMix && !transActive) checkAuto()

        if (transActive) {
            transDone += frames
            val t = min(1f, transDone.toFloat() / transTotal)
            crossfader = transFrom + (transTo - transFrom) * t
            if (transDone >= transTotal) {
                crossfader = transTo
                transActive = false
                transitioning = false
                transSrc?.let {
                    it.playing = false
                    it.consumed = true
                }
                transSrc = null
            }
        }

        val p = crossfader
        if (bassSwap) {
            // A suelta los graves entre 40% y 60% del recorrido; B los recupera en ese mismo tramo
            val s = smooth(((p - 0.4f) / 0.2f).coerceIn(0f, 1f))
            deckA.lowOffsetDb = -40f * s
            deckB.lowOffsetDb = -40f * (1f - s)
        } else {
            deckA.lowOffsetDb = 0f
            deckB.lowOffsetDb = 0f
        }
        computeGains(p)

        deckA.render(bufA, frames)
        deckB.render(bufB, frames)

        val m = master
        for (n in 0 until frames) {
            val t = n.toFloat() / frames
            val ga = gA0 + (gA1 - gA0) * t
            val gb = gB0 + (gB1 - gB0) * t
            out[2 * n] = softClip((bufA[2 * n] * ga + bufB[2 * n] * gb) * m)
            out[2 * n + 1] = softClip((bufA[2 * n + 1] * ga + bufB[2 * n + 1] * gb) * m)
        }
        gA0 = gA1
        gB0 = gB1
    }

    private fun computeGains(p: Float) {
        val a: Float
        val b: Float
        when (curve) {
            FadeCurve.LINEAR -> {
                a = 1f - p; b = p
            }
            FadeCurve.EQUAL_POWER -> {
                a = cos(p * PI.toFloat() / 2f); b = sin(p * PI.toFloat() / 2f)
            }
            FadeCurve.S_CURVE -> {
                val s = smooth(p)
                a = cos(s * PI.toFloat() / 2f); b = sin(s * PI.toFloat() / 2f)
            }
            FadeCurve.CLUB -> {
                a = min(1f, (1f - p) * 5f); b = min(1f, p * 5f)
            }
        }
        gA1 = a
        gB1 = b
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun softClip(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.8f) x else sign(x) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }

    companion object {
        const val MAX_FRAMES = 4096
    }
}
