package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EditsTest {
    private val edits = ChordEdits(
        chords = listOf(ChordSpan(0.0, 2.0, "C:maj"), ChordSpan(2.0, 4.0, "N", true)),
        shapes = mapOf("G:maj" to listOf(3, -1, 0, 0, 3, 3)),
    )

    @Test
    fun roundTrips() {
        assertEquals(edits, decodeEdits(encodeEdits(edits)))
    }

    @Test
    fun theDefaultsAreWritten() {
        val text = encodeEdits(ChordEdits(chords = emptyList()))
        assertEquals("""{"version":1,"chords":[],"shapes":{}}""", text)
    }

    @Test
    fun badJsonIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { decodeEdits("{nope") }
        assertThrows(IllegalArgumentException::class.java) { decodeEdits("""{"version":1}""") }
    }

    @Test
    fun anotherVersionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { decodeEdits("""{"version":2,"chords":[]}""") }
    }

    @Test
    fun aShapeWithoutSixFretsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeEdits("""{"version":1,"chords":[],"shapes":{"G:maj":[3,-1,0,0,3]}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeEdits("""{"version":1,"chords":[],"shapes":{"G:maj":[3,-1,0,0,3,25]}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeEdits("""{"version":1,"chords":[],"shapes":{"G:maj":[3,-2,0,0,3,3]}}""")
        }
    }
}
