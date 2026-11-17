package com.arbaba.chordifymobile.engine.features

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.audio.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ceil

/** Times decoding and NNLS Chroma for a 4-minute song: loop.wav 30 times over. */
@RunWith(AndroidJUnit4::class)
class FeatureBenchmarkDeviceTest {
    @Test
    fun aFourMinuteSong() {
        val loop = TestAudio.readWav(TestAudio.golden("loop.wav")).mono
        val song = FloatArray(loop.size * 30) { loop[it % loop.size] }
        val file = TestAudio.tempFile("benchmark.wav").also { TestAudio.writeWav(it, song, 44100) }

        val started = SystemClock.elapsedRealtime()
        val features = extractNnlsBothchroma(AudioSource.FromFile(file))
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0

        Log.i("ChordifyBenchmark", String.format(Locale.ROOT, "decode + NNLS features of a %.0f s song: %.1f s on %s (%s)",
            song.size / 44100.0, seconds, Build.MODEL, Build.SUPPORTED_ABIS[0]))
        assertEquals(ceil(song.size / 2048.0).toInt(), features.raw.size)
    }
}
