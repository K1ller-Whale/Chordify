package com.arbaba.chordifymobile.engine.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PyTest {
    @Test
    fun roundsLikePython() {
        assertEquals(0.1235, pyRound(0.12345, 4), 0.0) // 0.12345 is stored slightly above the tie
        assertEquals(2.67, pyRound(2.675, 2), 0.0) // 2.675 is stored slightly below the tie
        assertEquals(0.12, pyRound(0.125, 2), 0.0) // an exact tie goes to the even digit
        assertEquals(0.38, pyRound(0.375, 2), 0.0)
        assertEquals(1.0, pyRound(1.0, 4), 0.0)
        assertEquals(2, pyRoundToInt(2.5))
        assertEquals(4, pyRoundToInt(3.5))
        assertEquals(-2, pyRoundToInt(-2.5))
        assertEquals(3, pyRoundToInt(2.51))
    }

    @Test
    fun argmaxReturnsTheFirstLargest() {
        assertEquals(1, argmax(doubleArrayOf(1.0, 3.0, 3.0, 2.0)))
        assertEquals(0, argmax(doubleArrayOf(1.0, 3.0, 3.0, 2.0), until = 1))
        assertEquals(3, argmax(doubleArrayOf(1.0, 3.0, 3.0, 4.0)))
    }

    @Test
    fun searchSortedMatchesNumpy() {
        val values = doubleArrayOf(1.0, 2.0, 2.0, 3.0)
        assertEquals(1, searchSorted(values, 2.0))
        assertEquals(3, searchSorted(values, 2.0, right = true))
        assertEquals(0, searchSorted(values, 0.5))
        assertEquals(4, searchSorted(values, 4.0))
        assertEquals(0, searchSorted(DoubleArray(0), 1.0))
    }

    @Test
    fun medianMatchesNumpy() {
        assertEquals(2.0, median(doubleArrayOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, median(doubleArrayOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertThrows(IllegalArgumentException::class.java) { median(DoubleArray(0)) }
    }

    @Test
    fun partitionSplitsAtTheFirstSeparator() {
        assertEquals(Triple("C:maj", true, "3"), "C:maj/3".partition3('/'))
        assertEquals(Triple("C", false, ""), "C".partition3(':'))
        assertEquals(Triple("a", true, "b:c"), "a:b:c".partition3(':'))
        assertEquals(Triple("", true, ""), "/".partition3('/'))
    }
}
