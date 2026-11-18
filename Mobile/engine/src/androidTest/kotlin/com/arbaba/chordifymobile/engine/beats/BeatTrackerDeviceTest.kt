package com.arbaba.chordifymobile.engine.beats

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.streamAudio
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BeatTrackerDeviceTest {
    // spec §7.1: beats within one onset frame (23 ms) of the server's
    @Test
    fun beatsMatchTheServer() {
        val inputs = Goldens.loadGz("beats").objects("inputs")
        assertEquals(6, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val (onset, info) = BeatTracker().use { tracker ->
                streamAudio(AudioSource.FromFile(TestAudio.golden(name)), listOf(tracker))
                tracker.finish()
            }
            assertMatrixClose("$name onset", arrayOf(input.getValue("onset").floats()), arrayOf(onset), 1e-3)
            val expected = input.getValue("beats").doubles()
            assertEquals("$name beats", expected.size, info.beats.size)
            val frame = ONSET_HOP / ONSET_SAMPLE_RATE.toDouble()
            for (i in expected.indices) assertTrue("$name beat $i", abs(expected[i] - info.beats[i]) <= frame + 1e-9)
            assertEquals("$name bpm", input.double("bpm"), info.bpm!!, 0.0)
        }
    }
}
