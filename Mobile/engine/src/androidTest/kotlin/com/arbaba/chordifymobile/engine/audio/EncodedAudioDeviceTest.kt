package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EncodedAudioDeviceTest {
    private fun decode(name: String): FloatArray {
        val parts = ArrayList<FloatArray>()
        streamAudio(AudioSource.FromFile(TestAudio.golden(name)), listOf(SampleSink { samples, count -> parts += samples.copyOf(count) }))
        return TestAudio.concat(parts)
    }

    // MP3 and AAC decoders treat encoder delay differently from ffmpeg and libsndfile, so lossy
    // files are compared by timing only: within 30 ms of the WAV they were encoded from (spec §7.1).
    @Test
    fun lossyCopiesLineUpWithTheirWav() {
        val encoded = Goldens.loadGz("features").objects("encoded")
        assertEquals(2, encoded.size)
        for (entry in encoded) {
            val name = entry.str("file")!!
            val reference = decode(entry.str("reference")!!)
            val decoded = decode(name)
            val offset = TestAudio.envelopeOffsetMs(reference, decoded, ANALYSIS_SAMPLE_RATE)
            assertTrue("$name is $offset ms off its WAV", abs(offset) <= 30.0)
            val longer = (decoded.size - reference.size) * 1000.0 / ANALYSIS_SAMPLE_RATE
            assertTrue("$name is $longer ms longer than its WAV", abs(longer) <= 100.0)
        }
    }
}
