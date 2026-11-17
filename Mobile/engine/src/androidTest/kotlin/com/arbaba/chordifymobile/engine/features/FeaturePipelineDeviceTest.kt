package com.arbaba.chordifymobile.engine.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertFeaturesClose
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The whole of milestone M2: a file in, the server's features out (spec §9). */
@RunWith(AndroidJUnit4::class)
class FeaturePipelineDeviceTest {
    @Test
    fun theFeaturesOfEveryInputMatchPython() {
        val inputs = Goldens.loadGz("features").objects("inputs")
        assertEquals(4, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val features = extractNnlsBothchroma(AudioSource.FromFile(TestAudio.golden(name)))
            assertEquals(name, input.int("samples")!!.toLong(), features.info.samples)
            assertFeaturesClose("$name raw", input.getValue("raw").floatMatrix(), features.raw)
            assertMatrixClose("$name normalised", input.getValue("normalised").floatMatrix(), normaliseBothchroma(features.raw), 1e-4)
        }
    }
}
