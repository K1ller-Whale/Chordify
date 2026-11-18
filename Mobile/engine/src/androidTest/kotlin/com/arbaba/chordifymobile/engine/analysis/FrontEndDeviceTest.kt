package com.arbaba.chordifymobile.engine.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertFeaturesClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.beats.ONSET_HOP
import com.arbaba.chordifymobile.engine.beats.ONSET_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** One decoding pass gives the same features and beats as the two separately (spec §5). */
@RunWith(AndroidJUnit4::class)
class FrontEndDeviceTest {
    @Test
    fun oneDecodeGivesTheServersFeaturesAndBeats() {
        val features = Goldens.loadGz("features").objects("inputs")
        val beats = Goldens.loadGz("beats").objects("inputs")
        for (name in listOf("loop_48k.wav", "sevenths_stereo_22k.wav")) {
            val front = runFrontEnd(AudioSource.FromFile(TestAudio.golden(name)))
            assertFeaturesClose(name, features.single { it.str("file") == name }.getValue("raw").floatMatrix(), front.features)
            val expected = beats.single { it.str("file") == name }.getValue("beats").doubles()
            assertEquals(name, expected.size, front.beats.beats.size)
            for (i in expected.indices) {
                assertTrue("$name beat $i", abs(expected[i] - front.beats.beats[i]) <= ONSET_HOP / ONSET_SAMPLE_RATE.toDouble() + 1e-9)
            }
        }
    }
}
