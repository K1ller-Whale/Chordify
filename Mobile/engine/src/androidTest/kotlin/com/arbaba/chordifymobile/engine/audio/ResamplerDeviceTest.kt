package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.features.NNLS_BOTHCHROMA
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResamplerDeviceTest {
    private val golden = Goldens.loadGz("features")

    private fun resample(samples: FloatArray, from: Int, chunk: Int): FloatArray =
        Resampler(from, NNLS_BOTHCHROMA.sampleRate).use { resampler ->
            val parts = ArrayList<FloatArray>()
            var at = 0
            while (at < samples.size) {
                val count = minOf(chunk, samples.size - at)
                parts += resampler.process(samples.copyOfRange(at, at + count))
                at += count
            }
            parts += resampler.finish()
            TestAudio.concat(parts)
        }

    @Test
    fun resamplingMatchesLibrosa() {
        val inputs = golden.objects("inputs").filter { it.has("resampled") }
        assertEquals(2, inputs.size) // 48 kHz down, 22.05 kHz up
        for (input in inputs) {
            val name = input.str("file")!!
            val wav = TestAudio.readWav(TestAudio.golden(name))
            val out = resample(wav.mono, wav.sampleRate, chunk = 4096)
            assertEquals(name, input.int("samples"), out.size)
            val slice = input.obj("resampled")
            val start = slice.int("start")!!
            val expected = slice.getValue("values").floats()
            assertMatrixClose(name, arrayOf(expected), arrayOf(out.copyOfRange(start, start + expected.size)), 1e-5)
        }
    }

    @Test
    fun chunkSizeDoesNotChangeTheOutput() {
        val wav = TestAudio.readWav(TestAudio.golden("loop_48k.wav"))
        val whole = resample(wav.mono, wav.sampleRate, chunk = wav.mono.size)
        for (chunk in listOf(1000, 7777)) {
            assertArrayEquals("chunks of $chunk", whole, resample(wav.mono, wav.sampleRate, chunk), 0f)
        }
    }

    @Test
    fun theAnalysisRatePassesThrough() {
        val samples = FloatArray(1000) { it / 1000f }
        assertArrayEquals(samples, resample(samples, NNLS_BOTHCHROMA.sampleRate, chunk = 300), 0f)
    }
}
