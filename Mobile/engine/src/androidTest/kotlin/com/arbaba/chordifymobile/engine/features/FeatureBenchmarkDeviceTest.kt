package com.arbaba.chordifymobile.engine.features

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.runFrontEnd
import com.arbaba.chordifymobile.engine.audio.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ceil

/** Times the analysis front end for a 4-minute song (pulse.wav 15 times over): one decoding pass
 *  into NNLS Chroma and the beat tracker, then the chord model. */
@RunWith(AndroidJUnit4::class)
class FeatureBenchmarkDeviceTest {
    @Test
    fun aFourMinuteSong() {
        val pulse = TestAudio.readWav(TestAudio.golden("pulse.wav")).mono
        val song = FloatArray(pulse.size * 15) { pulse[it % pulse.size] }
        val file = TestAudio.tempFile("benchmark.wav").also { TestAudio.writeWav(it, song, 44100) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        var started = SystemClock.elapsedRealtime()
        val front = runFrontEnd(AudioSource.FromFile(file))
        val frontSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        started = SystemClock.elapsedRealtime()
        val output = ChordModel.fromAssets(context).use { it.predict(front.features) }
        val modelSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0

        Log.i("ChordifyBenchmark", String.format(Locale.ROOT,
            "%.0f s song: decode + NNLS + beats %.1f s, chord model %.1f s, on %s (%s)",
            song.size / 44100.0, frontSeconds, modelSeconds, Build.MODEL, Build.SUPPORTED_ABIS[0]))
        assertEquals(ceil(song.size / 2048.0).toInt(), front.features.size)
        assertEquals(front.features.size, output.chord.size)
        assertTrue(front.beats.usable)
    }
}
