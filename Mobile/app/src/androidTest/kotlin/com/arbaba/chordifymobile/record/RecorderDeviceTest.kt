package com.arbaba.chordifymobile.record

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecorderDeviceTest {
    @get:Rule
    val microphone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRecordingIsAWavFileOfWhatWasRecorded() {
        val file = File(context.cacheDir, "test-recording.wav")
        val started = SystemClock.elapsedRealtime()
        var seconds = 0.0
        val samples = Recorder.record(context, file, isStopped = { SystemClock.elapsedRealtime() - started > 1500 }) { seconds = it }
        assertTrue("$samples samples for 1.5 s", samples in Recorder.SAMPLE_RATE..3L * Recorder.SAMPLE_RATE)
        assertEquals(44 + 2 * samples, file.length())
        assertArrayEquals(WavWriter.header(samples, Recorder.SAMPLE_RATE), file.readBytes().copyOf(44))
        assertEquals(samples.toDouble() / Recorder.SAMPLE_RATE, seconds, 1e-9)
        file.delete()
    }
}
