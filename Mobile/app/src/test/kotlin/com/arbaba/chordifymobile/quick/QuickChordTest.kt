package com.arbaba.chordifymobile.quick

import com.arbaba.chordifymobile.music.guitarShape
import com.arbaba.chordifymobile.record.rms
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickChordTest {
    @Test
    fun theChordIsNamedInFull() {
        assertEquals("Am7", quickChordName("A:min7"))
        assertEquals("C/E", quickChordName("C:maj/3"))
        assertEquals("G", quickChordName("G:maj"))
        assertEquals("No chord", quickChordName("N"))
    }

    @Test
    fun theShapeComesFromTheRootAndQuality() {
        assertEquals(guitarShape("A", "min7"), chordShape("A:min7"))
        assertEquals(guitarShape("C", "maj"), chordShape("C:maj/3"))
        assertEquals(guitarShape("C", "maj"), chordShape("C"))
        assertNull(chordShape("N"))
    }

    @Test
    fun theRecordingTimeShowsWhenItStopsByItself() {
        assertEquals("0.0 of 3 s", recordingTime(0.0))
        assertEquals("1.2 of 3 s", recordingTime(1.24))
        assertEquals("3.0 of 3 s", recordingTime(RECORD_SECONDS))
    }

    @Test
    fun aClipIsCollectedWholeAndItsLevelMeasured() {
        val sink = CollectingSink()
        sink.write(floatArrayOf(1f, 2f, 3f), 2)
        sink.write(FloatArray(70_000) { 0.5f }, 70_000)
        val samples = sink.samples()
        assertEquals(70_002, samples.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 0.5f), samples.copyOfRange(0, 3), 0f)
        assertEquals(0.5f, rms(shortArrayOf(16384, -16384, 16384), 3), 1e-6f)
        assertEquals(0f, rms(shortArrayOf(), 0), 0f)
    }
}
