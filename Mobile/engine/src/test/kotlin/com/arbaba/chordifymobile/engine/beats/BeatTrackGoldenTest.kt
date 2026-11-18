package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.bool
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BeatTrackGoldenTest {
    private val golden = Goldens.loadGz("beats")

    @Test
    fun theTempogramAndTempoMatchLibrosa() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val onset = input.getValue("onset").floats()
            assertArrayEquals(name, input.getValue("tempogram").doubles(), tempogramMean(onset), 1e-9)
            assertEquals(name, input.double("tempo"), estimateTempo(onset), 0.0)
        }
    }

    @Test
    fun beatFramesMatchLibrosaExactly() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val frames = trackBeatFrames(input.getValue("onset").floats(), input.double("tempo"))
            assertEquals(name, input.getValue("beat_frames").ints(), frames.toList())
        }
    }

    @Test
    fun beatInfoMatchesTheServer() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val info = trackBeats(input.getValue("onset").floats())
            assertArrayEquals(name, input.getValue("beats").doubles(), info.beats, 1e-12)
            val bpm = input.getValue("bpm")
            if (bpm is JsonNull) assertNull(name, info.bpm) else assertEquals(name, bpm.jsonPrimitive.double, info.bpm!!, 0.0)
            assertEquals(name, input.double("confidence"), info.confidence, 0.0)
            assertEquals(name, input.bool("usable"), info.usable)
        }
    }

    @Test
    fun silenceHasNoBeats() {
        val info = trackBeats(FloatArray(500))
        assertEquals(0, info.beats.size)
        assertNull(info.bpm)
        assertEquals(0.0, info.confidence, 0.0)
        assertFalse(info.usable)
    }
}
