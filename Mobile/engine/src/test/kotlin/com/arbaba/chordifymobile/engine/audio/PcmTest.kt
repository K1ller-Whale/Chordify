package com.arbaba.chordifymobile.engine.audio

import android.media.AudioFormat
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    /** Little-endian integer samples, each [width] bytes. */
    private fun pcm(width: Int, vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * width).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) for (byte in 0 until width) buffer.put((value shr (8 * byte)).toByte())
        buffer.flip()
        return buffer
    }

    @Test
    fun sixteenBitIsScaledBy32768AndChannelsAreAveraged() {
        val mono = Pcm.toMono(pcm(2, 16384, -32768, 1, 2), AudioFormat.ENCODING_PCM_16BIT, channels = 2)
        assertArrayEquals(floatArrayOf((0.5f + -1f) / 2, (1 / 32768f + 2 / 32768f) / 2), mono, 0f)
    }

    @Test
    fun otherEncodingsAreScaledAsLibsndfileScalesThem() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 127 / 128f), Pcm.toMono(pcm(1, 0, 128, 255), AudioFormat.ENCODING_PCM_8BIT, 1), 0f)
        assertArrayEquals(floatArrayOf(-1f, 8388607 / 8388608f),
            Pcm.toMono(pcm(3, -8388608, 8388607), AudioFormat.ENCODING_PCM_24BIT_PACKED, 1), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0.5f), Pcm.toMono(pcm(4, Int.MIN_VALUE, 1 shl 30), AudioFormat.ENCODING_PCM_32BIT, 1), 0f)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-0.75f)
        floats.flip()
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), Pcm.toMono(floats, AudioFormat.ENCODING_PCM_FLOAT, 1), 0f)
    }

    @Test
    fun onlyTheBuffersWindowIsRead() {
        val buffer = pcm(2, 100, 16384, 300)
        buffer.position(2)
        buffer.limit(4)
        assertArrayEquals(floatArrayOf(0.5f), Pcm.toMono(buffer, AudioFormat.ENCODING_PCM_16BIT, 1), 0f)
    }

    @Test
    fun anUnreadableEncodingIsADecodeFailure() {
        val error = assertThrows(EngineException::class.java) { Pcm.toMono(pcm(2, 0), 999, 1) }
        assertEquals(ErrorCode.DECODE_FAILED, error.code)
    }

    @Test
    fun theTrimmerDropsTheEncoderDelayAndPadding() {
        val trimmer = EdgeTrimmer(delay = 3, padding = 2)
        val chunks = listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f, 5f), floatArrayOf(6f, 7f, 8f))
        assertEquals(listOf(4f, 5f, 6f), chunks.flatMap { trimmer.push(it).toList() }) // 1-3 delay, 7-8 padding
    }

    @Test
    fun aTrimmerWithNothingToDropPassesEverything() {
        val trimmer = EdgeTrimmer(delay = 0, padding = 0)
        assertArrayEquals(floatArrayOf(1f, 2f), trimmer.push(floatArrayOf(1f, 2f)), 0f)
    }
}
