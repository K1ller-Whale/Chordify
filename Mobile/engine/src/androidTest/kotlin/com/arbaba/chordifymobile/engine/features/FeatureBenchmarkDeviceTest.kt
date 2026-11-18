package com.arbaba.chordifymobile.engine.features

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.analysis.Stage
import com.arbaba.chordifymobile.engine.audio.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Times a whole analysis of a 4-minute song (pulse.wav 15 times over), stage by stage: one
 *  decoding pass into NNLS Chroma and the beat tracker, the chord model, then the rest (two
 *  decodings, key, theory and predictions). The spec's budget is 20 s on a mid-range phone. */
@RunWith(AndroidJUnit4::class)
class FeatureBenchmarkDeviceTest {
    @Test
    fun aFourMinuteSong() {
        val pulse = TestAudio.readWav(TestAudio.golden("pulse.wav")).mono
        val song = FloatArray(pulse.size * 15) { pulse[it % pulse.size] }
        val file = TestAudio.tempFile("benchmark.wav").also { TestAudio.writeWav(it, song, 44100) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        ChordifyEngine.fromAssets(context).use { engine ->
            val started = HashMap<Stage, Long>()
            val result = engine.analyse(AudioSource.FromFile(file), SourceInfo("an_benchmark"),
                                        onProgress = { stage, _ -> started.putIfAbsent(stage, SystemClock.elapsedRealtime()) })
            fun seconds(from: Stage, to: Stage) = (started.getValue(to) - started.getValue(from)) / 1000.0
            Log.i("ChordifyBenchmark", String.format(Locale.ROOT,
                "%.0f s song: decode + NNLS + beats %.1f s, chord model %.1f s, the rest %.1f s, in all %.1f s, on %s (%s)",
                song.size / 44100.0, seconds(Stage.DECODE, Stage.ACOUSTIC), seconds(Stage.ACOUSTIC, Stage.KEY),
                seconds(Stage.KEY, Stage.DONE), seconds(Stage.DECODE, Stage.DONE), Build.MODEL, Build.SUPPORTED_ABIS[0]))
            assertEquals(240.0, result.source.duration, 0.01)
            assertEquals(listOf("C", "G", "Am", "F"), result.chords.take(4).map { it.display })
            assertTrue(result.tempo.confidence >= 0.5)
        }
    }
}
