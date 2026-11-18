package com.arbaba.chordifymobile.engine.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertFeaturesClose
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NnlsChromaDeviceTest {
    private val golden = Goldens.loadGz("features")

    private fun nnls(samples: FloatArray, chunk: Int, output: String = "bothchroma"): Array<FloatArray> =
        NnlsChroma(output = output).use { nnls ->
            TestAudio.feed(nnls, samples, chunk)
            nnls.finish()
        }

    @Test
    fun bothchromaMatchesPythonForTheInputsAt44k() {
        val inputs = golden.objects("inputs").filter { it.int("sample_rate") == NNLS_BOTHCHROMA.sampleRate }
        assertEquals(3, inputs.size) // loop.wav, loop_padded.wav and the GuitarSet excerpt
        for (input in inputs) {
            val name = input.str("file")!!
            val frames = nnls(TestAudio.readWav(TestAudio.golden(name)).mono, chunk = 4096)
            assertFeaturesClose(name, input.getValue("raw").floatMatrix(), nnlsToCOrder(frames))
        }
    }

    // The host's part alone (framing, Hann window, FFT) before NNLS Chroma's own maths: when the
    // features differ, this tells the host's mistakes from the plugin build's rounding.
    @Test
    fun logFrequencySpectrumMatchesPython() {
        val expected = golden.obj("logfreqspec")
        val samples = TestAudio.readWav(TestAudio.golden(expected.str("file")!!)).mono
        assertFeaturesClose("logfreqspec", expected.getValue("values").floatMatrix(), nnls(samples, 4096, "logfreqspec"))
    }

    @Test
    fun chunkSizeDoesNotChangeTheFeatures() {
        val samples = TestAudio.readWav(TestAudio.golden("loop.wav")).mono
        val whole = nnls(samples, chunk = samples.size)
        for (chunk in listOf(1000, 2048, 16385)) {
            assertTrue("chunks of $chunk", whole.contentDeepEquals(nnls(samples, chunk)))
        }
    }

    @Test
    fun noAudioGivesNoFrames() {
        assertEquals(0, nnls(FloatArray(0), chunk = 1).size)
    }

    @Test
    fun anUnknownOutputIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { NnlsChroma(output = "chords") }
    }
}
