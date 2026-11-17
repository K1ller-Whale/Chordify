package com.arbaba.chordifymobile.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {
    /** Stands in for libsoxr at twice the rate: every sample twice, then [tail] extra samples when flushed. */
    private class Doubler(private val tail: Int) : ResamplerCore {
        var closed = false
        override fun process(input: FloatArray, count: Int) = FloatArray(2 * count) { input[it / 2] }
        override fun flush() = FloatArray(tail) { -1f }
        override fun close() {
            closed = true
        }
    }

    /** Produces one sample per input sample, whatever the rates: too few for an upsampler. */
    private class Identity : ResamplerCore {
        override fun process(input: FloatArray, count: Int) = input.copyOf(count)
        override fun flush() = FloatArray(0)
        override fun close() = Unit
    }

    private fun run(resampler: Resampler, input: FloatArray, chunk: Int): FloatArray {
        val out = ArrayList<Float>()
        var at = 0
        while (at < input.size) {
            val count = minOf(chunk, input.size - at)
            out += resampler.process(input.copyOfRange(at, at + count)).toList()
            at += count
        }
        out += resampler.finish().toList()
        return out.toFloatArray()
    }

    @Test
    fun extraOutputIsCutToCeilOfTheRatio() {
        val out = run(Resampler(1, 2, Doubler(tail = 5)), FloatArray(101) { it.toFloat() }, chunk = 10)
        assertEquals(202, out.size) // ceil(101 × 2): the 5 flushed samples are dropped, as librosa's fix_length does
        assertArrayEquals(FloatArray(202) { (it / 2).toFloat() }, out, 0f)
    }

    @Test
    fun shortOutputIsZeroPadded() {
        val out = run(Resampler(2, 3, Identity()), FloatArray(10) { 1f }, chunk = 4)
        assertArrayEquals(FloatArray(15) { if (it < 10) 1f else 0f }, out, 0f) // ceil(10 × 1.5) = 15
    }

    @Test
    fun theNewestOutputIsHeldUntilFinish() {
        val resampler = Resampler(1, 2, Doubler(tail = 0))
        assertEquals(0, resampler.process(FloatArray(10)).size) // 20 samples, all inside the hold-back
        assertEquals(20, resampler.finish().size)
    }

    @Test
    fun chunkSizeDoesNotChangeTheOutput() {
        val input = FloatArray(1000) { (it % 17).toFloat() }
        val whole = run(Resampler(1, 2, Doubler(tail = 3)), input, chunk = input.size)
        for (chunk in listOf(1, 7, 300)) assertArrayEquals("chunks of $chunk", whole, run(Resampler(1, 2, Doubler(3)), input, chunk), 0f)
    }

    @Test
    fun theSameRatePassesThroughWithoutNativeCode() {
        val resampler = Resampler(44100, 44100)
        val input = floatArrayOf(0.5f, -0.25f, 0.125f)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), resampler.process(input, 2), 0f)
        assertEquals(0, resampler.finish().size)
    }

    @Test
    fun finishingTwiceIsRefused() {
        val resampler = Resampler(1, 2, Doubler(tail = 0))
        resampler.finish()
        assertThrows(IllegalStateException::class.java) { resampler.finish() }
        assertThrows(IllegalStateException::class.java) { resampler.process(FloatArray(1)) }
    }

    @Test
    fun closingClosesTheCore() {
        val core = Doubler(tail = 0)
        Resampler(1, 2, core).close()
        assertTrue(core.closed)
    }
}
