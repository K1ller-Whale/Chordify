package com.arbaba.chordifymobile.engine.features

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturesGoldenTest {
    private val golden = Goldens.loadGz("features")

    @Test
    fun theNnlsSpecMatchesPython() {
        val spec = golden.obj("spec")
        assertEquals(spec.str("kind"), NNLS_BOTHCHROMA.kind)
        assertEquals(spec.int("sample_rate"), NNLS_BOTHCHROMA.sampleRate)
        assertEquals(spec.int("hop"), NNLS_BOTHCHROMA.hop)
        assertEquals(spec.int("n_bins"), NNLS_BOTHCHROMA.nBins)
        assertEquals(spec.int("revision"), NNLS_BOTHCHROMA.revision)
        assertEquals(spec.double("offset"), NNLS_BOTHCHROMA.offset, 0.0)
        assertEquals(spec.double("frame_rate"), NNLS_BOTHCHROMA.frameRate, 0.0)
        assertArrayEquals(golden.getValue("frame_times").doubles(), NNLS_BOTHCHROMA.frameTimes(3), 1e-12)
    }

    @Test
    fun nnlsBinsAreRolledToStartAtC() {
        // NNLS bin 0 is A, so C, the fourth bin, comes first in each half.
        val rolled = nnlsToCOrder(arrayOf(FloatArray(24) { it.toFloat() }))[0]
        val expected = (listOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1, 2) + listOf(15, 16, 17, 18, 19, 20, 21, 22, 23, 12, 13, 14))
            .map { it.toFloat() }.toFloatArray()
        assertArrayEquals(expected, rolled, 0f)
    }

    @Test
    fun normalisationMatchesPython() {
        val inputs = golden.objects("inputs")
        assertEquals(5, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val normalised = normaliseBothchroma(input.getValue("raw").floatMatrix())
            assertMatrixClose(name, input.getValue("normalised").floatMatrix(), normalised, 1e-4)
        }
    }

    @Test
    fun silenceAndNoFramesAreHandledAsInPython() {
        assertEquals(0, normaliseBothchroma(emptyArray()).size)
        // All-zero frames: every chroma value 0, and an energy spread of 0 is not divided by.
        for (row in normaliseBothchroma(Array(3) { FloatArray(24) })) assertArrayEquals(FloatArray(25), row, 0f)
    }
}
