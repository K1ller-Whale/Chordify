package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.obj
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OnsetGoldenTest {
    private val golden = Goldens.loadGz("beats")

    @Test
    fun melFiltersMatchLibrosa() {
        val mel = golden.obj("mel")
        val filters = melFilters(ONSET_SAMPLE_RATE, ONSET_N_FFT)
        assertEquals(mel.getValue("shape").ints(), listOf(filters.size, filters[0].size))
        val rows = mel.getValue("rows").ints()
        val cols = mel.getValue("cols").ints()
        val values = mel.getValue("values").floats()
        val expected = Array(filters.size) { FloatArray(filters[0].size) }
        for (i in rows.indices) expected[rows[i]!!][cols[i]!!] = values[i]
        assertMatrixClose("mel filters", expected, filters, 1e-9)
    }

    @Test
    fun theFftMatchesNumpy() {
        val fft = golden.obj("fft")
        val (re, im) = Fft(ONSET_N_FFT).rfft(fft.getValue("frame").doubles())
        assertArrayEquals(fft.getValue("re").doubles(), re, 1e-9)
        assertArrayEquals(fft.getValue("im").doubles(), im, 1e-9)
    }

    @Test
    fun onsetStrengthMatchesLibrosa() {
        val case = golden.obj("onset_case")
        val onset = onsetStrength(case.getValue("y").floats())
        assertMatrixClose("onset strength", arrayOf(case.getValue("onset").floats()), arrayOf(onset), 1e-4)
    }

    @Test
    fun streamingTheOnsetInChunksChangesNothing() {
        val y = golden.obj("onset_case").getValue("y").floats()
        val whole = onsetStrength(y)
        for (chunk in listOf(1, 511, 4096)) {
            val onset = OnsetStrength()
            var at = 0
            while (at < y.size) {
                val count = minOf(chunk, y.size - at)
                onset.write(y.copyOfRange(at, at + count), count)
                at += count
            }
            assertTrue("chunks of $chunk", whole.contentEquals(onset.finish()))
        }
        assertTrue(abs(whole.sum()) > 0)
    }
}
