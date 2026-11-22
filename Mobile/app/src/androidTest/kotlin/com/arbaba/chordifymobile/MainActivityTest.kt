package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.data.ScreenOrientation
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theLibraryOpensTheOtherScreens() {
        waitFor("add") // landscape is the default, so the top bar shows its labels as text
        rule.onNodeWithText("Quick chord").performClick()
        waitForText("Record a chord")
        rule.onNodeWithTag("back").performClick()
        waitFor("add")
        rule.onNodeWithText("Songwriter").performClick()
        waitForText("Add chords, e.g. F G")
        rule.onNodeWithTag("back").performClick()
        waitFor("add")
        rule.onNodeWithText("Settings").performClick()
        waitFor("screen-LANDSCAPE")
        rule.onNodeWithTag("about").performScrollTo().performClick()
        waitForText("Source code")
        rule.onNodeWithTag("back").performClick()
        waitFor("about")
        rule.onNodeWithTag("back").performClick()
        waitFor("add")
    }

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
