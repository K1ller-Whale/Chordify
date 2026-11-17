package com.arbaba.chordifymobile.engine.audio

import android.media.AudioFormat
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interleaved PCM from a decoder -> mono float32, as libsndfile and chordify_core.audio.to_mono
 *  make it: integers scaled by 1 / 2^(bits - 1) (8-bit samples are unsigned), then the mean of the
 *  channels in float32. */
internal object Pcm {
    fun toMono(pcm: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val data = pcm.slice().order(ByteOrder.LITTLE_ENDIAN)
        val width = bytesPerSample(encoding)
        return FloatArray(data.remaining() / (width * channels)) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += sample(data, (frame * channels + channel) * width, encoding)
            sum / channels
        }
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> throw EngineException(ErrorCode.DECODE_FAILED, "The decoder produced PCM encoding $encoding, which the engine cannot read.")
    }

    private fun sample(data: ByteBuffer, at: Int, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> data.getShort(at) / 32768f
        AudioFormat.ENCODING_PCM_FLOAT -> data.getFloat(at)
        AudioFormat.ENCODING_PCM_8BIT -> ((data.get(at).toInt() and 0xFF) - 128) / 128f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> ((data.get(at).toInt() and 0xFF) or
            ((data.get(at + 1).toInt() and 0xFF) shl 8) or (data.get(at + 2).toInt() shl 16)) / 8388608f
        else -> data.getInt(at) / 2147483648f
    }
}

/** Drops what an encoder added to a stream that arrives in chunks: the first [delay] frames and
 *  the last [padding] frames (MediaFormat.KEY_ENCODER_DELAY and KEY_ENCODER_PADDING). MediaCodec
 *  leaves this to the app; ffmpeg and libsndfile do it themselves. */
internal class EdgeTrimmer(private var delay: Int, private val padding: Int) {
    private var held = FloatArray(0)

    /** The part of [chunk] that is neither delay nor (possibly) padding; the newest [padding]
     *  frames wait for the next chunk, and whatever waits when the stream ends is the padding. */
    fun push(chunk: FloatArray): FloatArray {
        var samples = chunk
        if (delay > 0) {
            val skip = minOf(delay, samples.size)
            delay -= skip
            samples = samples.copyOfRange(skip, samples.size)
        }
        if (padding == 0) return samples
        val all = held + samples
        val release = maxOf(0, all.size - padding)
        held = all.copyOfRange(release, all.size)
        return all.copyOf(release)
    }
}
