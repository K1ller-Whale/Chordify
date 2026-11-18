package com.arbaba.chordifymobile.songwriter

import com.arbaba.chordifymobile.engine.model.ProgressionKey
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class SongwriterTest {
    @Test
    fun typedChordsAreSplitAsOnTheWeb() {
        assertEquals(listOf("F", "G"), splitChords("F G"))
        assertEquals(listOf("F", "G", "Am7", "F/C"), splitChords(" F,G | Am7  F/C "))
        assertEquals(emptyList<String>(), splitChords("  , | "))
    }

    @Test
    fun theKeyIsSaidAsOnTheWeb() {
        fun response(estimated: Boolean, confidence: Double) =
            ProgressionResponse(ProgressionKey("C", "major", confidence, estimated), emptyList(), emptyList(), "progression-ngram@1.0.0")
        assertEquals("Assuming C major (87% sure)", keyLine(response(true, 0.8712)))
        assertEquals("In C major", keyLine(response(false, 1.0)))
        assertEquals(21, KEYS.size)
        assertEquals("", KEYS.first())
    }
}
