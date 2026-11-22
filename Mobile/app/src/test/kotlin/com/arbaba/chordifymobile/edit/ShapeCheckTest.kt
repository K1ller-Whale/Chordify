package com.arbaba.chordifymobile.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCheckTest {
    @Test fun barreGShapeMissingThird() =
        assertEquals("This shape has no B, the 3rd of G", missingNote(listOf(3, -1, 0, 0, 3, 3), "G:maj"))

    @Test fun openGIsComplete() = assertNull(missingNote(listOf(3, 2, 0, 0, 0, 3), "G:maj"))

    @Test fun rootComesFirstAndFlatsAreSpelled() =
        assertEquals("This shape has no Bb, the root of Bb", missingNote(listOf(-1, -1, -1, -1, -1, 0), "Bb:maj"))

    @Test fun seventhIsChecked() =
        assertEquals("This shape has no F, the 7th of G7", missingNote(listOf(3, 2, 0, 0, 0, 3), "G:7"))

    @Test fun noChordIsNeverFlagged() = assertNull(missingNote(listOf(-1, -1, -1, -1, -1, -1), "N"))

    @Test fun openEShapeFrequenciesAndCapo() {
        val open = strumFrequencies(listOf(0, 2, 2, 1, 0, 0), 0)
        assertEquals(listOf(82.41, 110.0 * Math.pow(2.0, 2 / 12.0), 146.83 * Math.pow(2.0, 2 / 12.0),
            196.0 * Math.pow(2.0, 1 / 12.0), 246.94, 329.63), open)
        val capo = strumFrequencies(listOf(0, 0, 0, 0, 0, 0), 2)
        assertEquals(82.41 * Math.pow(2.0, 2 / 12.0), capo[0], 1e-9)
        assertEquals(329.63 * Math.pow(2.0, 2 / 12.0), capo[5], 1e-9)
    }

    @Test fun mutedStringsAreSkipped() = assertEquals(2, strumFrequencies(listOf(-1, -1, 0, 0, -1, -1), 0).size)

    @Test fun pluckLengthClippingAndDeterminism() {
        val freqs = strumFrequencies(listOf(0, 2, 2, 1, 0, 0), 0)
        val a = pluck(freqs)
        assertEquals(66150, a.size)
        assertTrue(a.all { it > Short.MIN_VALUE && it < Short.MAX_VALUE })
        assertTrue(a.any { it.toInt() != 0 })
        assertTrue(a.contentEquals(pluck(freqs)))
    }
}
