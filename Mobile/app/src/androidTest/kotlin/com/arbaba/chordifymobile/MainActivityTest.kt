package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.data.ScreenOrientation
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test
    fun theScreenSettingSetsTheOrientation() {
        val settings = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ChordifyApp).container.settings
        try {
            runBlocking { settings.setScreenOrientation(ScreenOrientation.PORTRAIT) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, orientationOf(scenario))
                runBlocking { settings.setScreenOrientation(ScreenOrientation.LANDSCAPE) }
                val deadline = System.currentTimeMillis() + 5_000
                while (orientationOf(scenario) != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientationOf(scenario))
            }
        } finally {
            runBlocking { settings.setScreenOrientation(ScreenOrientation.LANDSCAPE) }
        }
    }

    private fun orientationOf(scenario: ActivityScenario<MainActivity>): Int {
        var value = -1
        scenario.onActivity { value = it.requestedOrientation }
        return value
    }

    @Test
    fun sharedAndOpenedAudioIsFound() {
        val song = Uri.parse("content://media/external/audio/media/42")
        assertEquals(song, MainActivity.sharedFile(Intent(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, song)))
        assertEquals(song, MainActivity.sharedFile(Intent(Intent.ACTION_VIEW).setDataAndType(song, "audio/mpeg")))
        assertNull(MainActivity.sharedFile(Intent(Intent.ACTION_MAIN)))
    }
}
