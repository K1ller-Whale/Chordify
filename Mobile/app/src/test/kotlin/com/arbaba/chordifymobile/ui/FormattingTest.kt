package com.arbaba.chordifymobile.ui

import com.arbaba.chordifymobile.data.Analysis
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun aCompletedAnalysisIsSummarisedByKeyTempoAndLength() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "00", 0L, modelFingerprint = "f",
                           duration = 240.4, keyName = "C major", bpm = 117.5)
        assertEquals("C major · 117.5 BPM · 4:00", summary(row))
        assertEquals("A minor · 120 BPM · 0:59", summary(row.copy(keyName = "A minor", bpm = 120.0, duration = 59.99)))
        assertEquals("E major · 1:05", summary(row.copy(keyName = "E major", bpm = null, duration = 65.0)))
    }
}
