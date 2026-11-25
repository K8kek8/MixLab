package com.mixlab.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Decodifica cualquier audio que soporte Android (mp3, aac, flac, ogg, wav...) a un [Track]. */
object AudioDecoder {

    private class ShortBuf {
        var data = ShortArray(1 shl 20)
        var size = 0

        fun append(src: ShortBuffer, count: Int) {
            if (size + count > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + count))
            }
            src.get(data, size, count)
            size += count
        }
    }

    suspend fun decode(
        context: Context,
        uri: Uri,
        name: String,
        maxSeconds: Int = 900,
        onProgress: (Float) -> Unit = {},
    ): Track {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i
                    format = f
                    break
                }
            }
            if (trackIdx < 0 || format == null) {
                throw IllegalArgumentException("El archivo no contiene audio")
            }
            extractor.selectTrack(trackIdx)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val maxRawShorts = rate.toLong() * channels * maxSeconds

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val raw = ShortBuf()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0 || raw.size >= maxRawShorts) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = c.getOutputBuffer(outIdx)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        raw.append(shorts, shorts.remaining())
                        c.releaseOutputBuffer(outIdx, false)
                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = c.outputFormat
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            onProgress(1f)
            val pcm = toStereo44k(raw.data, raw.size, channels, rate)
            if (pcm.isEmpty()) throw IllegalStateException("No se pudo decodificar el audio")
            return Track(name, pcm)
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // ya estaba detenido
            }
            codec?.release()
            extractor.release()
        }
    }

    /** Convierte PCM de N canales / cualquier frecuencia a estéreo entrelazado de 44.1 kHz. */
    private fun toStereo44k(raw: ShortArray, rawLen: Int, ch: Int, rate: Int): ShortArray {
        val inFrames = rawLen / ch
        if (inFrames <= 1) return ShortArray(0)

        if (rate == SAMPLE_RATE) {
            val out = ShortArray(inFrames * 2)
            for (i in 0 until inFrames) {
                val l = raw[i * ch]
                val r = if (ch > 1) raw[i * ch + 1] else l
                out[2 * i] = l
                out[2 * i + 1] = r
            }
            return out
        }

        val outFrames = (inFrames.toLong() * SAMPLE_RATE / rate).toInt()
        val out = ShortArray(outFrames * 2)
        val ratio = rate.toDouble() / SAMPLE_RATE
        for (o in 0 until outFrames) {
            val p = o * ratio
            val i0 = p.toInt()
            val i1 = minOf(i0 + 1, inFrames - 1)
            val f = (p - i0).toFloat()
            val l0 = raw[i0 * ch].toFloat()
            val l1 = raw[i1 * ch].toFloat()
            val r0 = if (ch > 1) raw[i0 * ch + 1].toFloat() else l0
            val r1 = if (ch > 1) raw[i1 * ch + 1].toFloat() else l1
            out[2 * o] = (l0 + (l1 - l0) * f).toInt().toShort()
            out[2 * o + 1] = (r0 + (r1 - r0) * f).toInt().toShort()
        }
        return out
    }
}
