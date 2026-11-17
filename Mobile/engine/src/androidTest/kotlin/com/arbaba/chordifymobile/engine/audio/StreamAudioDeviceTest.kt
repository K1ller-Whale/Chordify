package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StreamAudioDeviceTest {
    private fun stream(
        file: File,
        limits: AudioLimits = AudioLimits(),
        sinks: List<SampleSink> = emptyList(),
        isCancelled: () -> Boolean = { false },
    ) = streamAudio(AudioSource.FromFile(file), sinks, limits, isCancelled)

    private fun assertFails(code: ErrorCode, block: () -> Unit) {
        val error = assertThrows(EngineException::class.java) { block() }
        assertEquals(error.message, code, error.code)
    }

    @Test
    fun reportsTheTrackAndItsLengthAt44k() {
        val input = Goldens.loadGz("features").objects("inputs").single { it.str("file") == "sevenths_stereo_22k.wav" }
        val info = stream(TestAudio.golden("sevenths_stereo_22k.wav"))
        assertEquals(22050, info.sourceSampleRate)
        assertEquals(2, info.sourceChannels)
        assertEquals(input.int("frames")!!.toLong(), info.sourceFrames)
        assertEquals(input.int("samples")!!.toLong(), info.samples)
    }

    @Test
    fun everySinkGetsEverySample() {
        val counts = LongArray(2)
        val info = stream(TestAudio.golden("loop_48k.wav"),
            sinks = listOf(SampleSink { _, count -> counts[0] += count }, SampleSink { _, count -> counts[1] += count }))
        assertEquals(info.samples, counts[0])
        assertEquals(info.samples, counts[1])
    }

    @Test
    fun aFileThatIsNotAudioIsUnsupported() {
        val file = TestAudio.tempFile("notes.wav").apply { writeText("These are not the samples you are looking for.") }
        assertFails(ErrorCode.UNSUPPORTED_MEDIA_TYPE) { stream(file) }
    }

    @Test
    fun audioShorterThanASecondIsRefused() {
        val file = TestAudio.tempFile("short.wav")
        TestAudio.writeWav(file, FloatArray(22050) { (0.25 * sin(2 * PI * 440 * it / 44100)).toFloat() }, 44100)
        assertFails(ErrorCode.AUDIO_TOO_SHORT) { stream(file) }
    }

    @Test
    fun audioLongerThanTheLimitIsRefused() {
        assertFails(ErrorCode.AUDIO_TOO_LONG) { stream(TestAudio.golden("loop.wav"), AudioLimits(maxSeconds = 2.0)) }
    }

    @Test
    fun cancellingStopsTheStream() {
        var checks = 0
        assertThrows(CancelledException::class.java) { stream(TestAudio.golden("loop.wav"), isCancelled = { ++checks > 3 }) }
    }
}
