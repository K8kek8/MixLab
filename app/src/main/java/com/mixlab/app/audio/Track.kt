package com.mixlab.app.audio

/** Frecuencia de muestreo interna de todo el motor. */
const val SAMPLE_RATE = 44100

/**
 * Canción decodificada en memoria: PCM de 16 bits, estéreo entrelazado (L,R,L,R...) a 44.1 kHz.
 * Es inmutable, así que se puede compartir sin problema entre el hilo de UI y el de audio.
 */
class Track(val name: String, val pcm: ShortArray) {
    val frames: Int get() = pcm.size / 2
    val durationSec: Double get() = frames.toDouble() / SAMPLE_RATE
}
