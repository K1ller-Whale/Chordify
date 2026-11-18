package com.arbaba.chordifymobile.playback

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Media3 plays an analysed file, seeks, changes speed and reports its end. */
@RunWith(AndroidJUnit4::class)
class ExoAudioPlayerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test
    fun itPlaysSeeksAndEnds() {
        val file = File(instrumentation.targetContext.cacheDir, "player-loop.wav")
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/loop.wav")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        var ended = false
        val player = onMain { ExoAudioPlayer(instrumentation.targetContext, Uri.fromFile(file)).also { it.onEnded = { ended = true } } }
        try {
            onMain { player.play() }
            // The player prepares asynchronously, so time the playback from when it has started.
            val started = SystemClock.elapsedRealtime() + 10_000
            while (onMain { player.positionSeconds } < 0.05 && SystemClock.elapsedRealtime() < started) SystemClock.sleep(50)
            val from = onMain { player.positionSeconds }
            SystemClock.sleep(1000)
            val playedFor = onMain { player.positionSeconds } - from
            assertTrue("moved $playedFor s in 1 s", playedFor in 0.5..1.5)

            onMain { player.seekTo(6.0) }
            SystemClock.sleep(300)
            val afterSeek = onMain { player.positionSeconds }
            assertTrue("at $afterSeek s after seeking to 6", afterSeek in 5.9..6.6)

            onMain { player.setSpeed(0.5) }
            val start = onMain { player.positionSeconds }
            SystemClock.sleep(1000)
            val halfSpeed = onMain { player.positionSeconds } - start
            assertTrue("moved $halfSpeed s in 1 s at half speed", halfSpeed in 0.25..0.8)

            onMain { player.setSpeed(1.0); player.seekTo(7.5) }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!ended && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("the end of the file was reported", ended)
        } finally {
            onMain { player.release() }
            file.delete()
        }
    }
}
