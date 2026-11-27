package com.mixlab.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Renderiza la mezcla completa (deck A → transición automática → deck B) a un WAV de 16 bits.
 * Usa exactamente el mismo [Mixer] que el reproductor, sobre una copia con los mismos ajustes.
 */
object MixExporter {

    fun export(live: Mixer, file: File, onProgress: (Float) -> Unit) {
        val m = live.cloneForExport()
        val frames = 1024
        val buf = FloatArray(frames * 2)
        val bytes = ByteArray(frames * 2 * 2)

        val a = m.deckA.track
        val b = m.deckB.track
        val estimate = ((a?.frames ?: 0) / m.deckA.speed + (b?.frames ?: 0) / m.deckB.speed).toDouble()
            .coerceAtLeast(1.0)
        val hardLimit = (estimate * 2 + SAMPLE_RATE * 20).toLong()

        var written = 0L
        file.outputStream().buffered().use { os ->
            os.write(ByteArray(WAV_HEADER)) // se rellena al final

            fun block() {
                m.render(buf, frames)
                for (i in buf.indices) {
                    val v = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                os.write(bytes)
                written += frames
            }

            while (m.anyPlaying() && written < hardLimit) {
                block()
                onProgress((written / estimate).toFloat().coerceIn(0f, 0.99f))
            }
            // Cola final para que el eco y la reverb se apaguen
            repeat(SAMPLE_RATE * 3 / frames) { block() }
        }

        writeHeader(file, written)
        onProgress(1f)
    }

    /** Guarda una pista (por ejemplo, una editada con cortar/pegar) tal cual, sin efectos ni crossfade. */
    fun exportTrack(track: Track, file: File, onProgress: (Float) -> Unit) {
        val total = track.frames
        val chunk = 8192
        val bytes = ByteArray(chunk * 4)
        file.outputStream().buffered().use { os ->
            os.write(ByteArray(WAV_HEADER)) // se rellena al final
            var f = 0
            while (f < total) {
                val n = min(chunk, total - f)
                for (i in 0 until n * 2) {
                    val v = track.pcm[2 * f + i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                os.write(bytes, 0, n * 4)
                f += n
                onProgress(f.toFloat() / total)
            }
        }
        writeHeader(file, total.toLong())
    }

    private fun writeHeader(file: File, totalFrames: Long) {
        val dataBytes = totalFrames * 4 // 2 canales * 2 bytes
        val h = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1) // PCM
        h.putShort(2) // canales
        h.putInt(SAMPLE_RATE)
        h.putInt(SAMPLE_RATE * 4) // byte rate
        h.putShort(4) // block align
        h.putShort(16) // bits
        h.put("data".toByteArray())
        h.putInt(dataBytes.toInt())
        RandomAccessFile(file, "rw").use {
            it.seek(0)
            it.write(h.array())
        }
    }

    private const val WAV_HEADER = 44
}
