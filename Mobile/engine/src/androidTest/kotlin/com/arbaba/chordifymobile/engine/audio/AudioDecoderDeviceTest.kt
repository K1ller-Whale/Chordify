package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDecoderDeviceTest {
    @Test
    fun wavFilesDecodeExactlyAsLibsndfileReadsThem() {
        val inputs = Goldens.loadGz("features").objects("inputs")
        assertEquals(5, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val parts = ArrayList<FloatArray>()
            var started = 0
            val track = AudioDecoder(AudioSource.FromFile(TestAudio.golden(name)))
                .decode(isCancelled = { false }, onStart = { started = it }, onSamples = { parts += it })
            assertEquals(name, input.int("sample_rate"), started)
            assertEquals(name, input.int("sample_rate"), track.sampleRate)
            assertEquals(name, input.int("channels"), track.channels)
            assertEquals(name, input.int("frames")!!.toLong(), track.frames)
            val mono = TestAudio.concat(parts)
            val slice = input.obj("decoded")
            val start = slice.int("start")!!
            val expected = slice.getValue("values").floats()
            assertArrayEquals(name, expected, mono.copyOfRange(start, start + expected.size), 0f)
        }
    }
}
