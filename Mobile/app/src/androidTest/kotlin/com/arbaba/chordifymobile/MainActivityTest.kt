package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theFourTabsOpenTheirScreens() {
        compose.onNodeWithText("Pick a file").assertIsDisplayed()
        compose.onNodeWithText("Quick chord").performClick()
        compose.onNodeWithText("Record a chord").assertIsDisplayed()
        compose.onNodeWithText("Songwriter").performClick()
        compose.onNodeWithText("Suggest the next chord").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Major and minor chords only").assertIsDisplayed()
        compose.onNodeWithText("About Chordify").performClick()
        compose.onNodeWithText("Source code").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Pick a file").assertIsDisplayed()
    }

    @Test
    fun sharedAndOpenedAudioIsFound() {
        val song = Uri.parse("content://media/external/audio/media/42")
        assertEquals(song, MainActivity.sharedFile(Intent(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, song)))
        assertEquals(song, MainActivity.sharedFile(Intent(Intent.ACTION_VIEW).setDataAndType(song, "audio/mpeg")))
        assertNull(MainActivity.sharedFile(Intent(Intent.ACTION_MAIN)))
    }
}
