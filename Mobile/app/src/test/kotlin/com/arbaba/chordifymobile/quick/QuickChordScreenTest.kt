package com.arbaba.chordifymobile.quick

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertAnnounced
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickChordScreenTest {
    @get:Rule val rule = createComposeRule()

    private val am7 = QuickResult("A:min7", "Am7", 0.8712)

    private fun content(
        state: QuickState = QuickState.Idle, result: QuickResult? = null, error: String? = null,
        onRecord: () -> Unit = {}, onPick: () -> Unit = {}, onSettings: (() -> Unit)? = null, onBack: () -> Unit = {},
        size: DpSize = LANDSCAPE,
    ) = rule.show(size) { QuickChordContent(state, result, error, onRecord, onPick, onSettings, onBack) }

    @Test fun idleShowsThePlaceholderAndTheButtonsCallBack() {
        var recorded = 0; var picked = 0; var back = 0
        content(onRecord = { recorded++ }, onPick = { picked++ }, onBack = { back++ })
        rule.onNodeWithText("Your chord will appear here.").assertExists()
        rule.onNodeWithText("Strum one chord for two or three seconds.").assertExists()
        rule.onNodeWithText("Record a chord").performClick()
        rule.onNodeWithText("Or pick a short clip").performClick()
        rule.onNodeWithTag("back").performClick()
        assertEquals(1, recorded); assertEquals(1, picked); assertEquals(1, back)
    }

    @Test fun recordingShowsStopTheTimeAndTheLevel() {
        content(state = QuickState.Recording(1.24, 0.1f))
        rule.onNodeWithText("Stop").assertExists()
        rule.onNodeWithText("1.2 of 3 s").assertExists() // stops by itself at RECORD_SECONDS
        rule.onNode(hasContentDescriptionExactly("Input level")).assertExists()
        rule.onNodeWithText("Or pick a short clip").assertIsNotEnabled()
    }

    @Test fun aResultShowsTheNameTheConfidenceAndTheShape() {
        content(result = am7)
        rule.onNodeWithTag("quickName").assertTextEquals("Am7")
        rule.onNodeWithText("Confidence 87%").assertExists()
        rule.onNode(hasContentDescription("Guitar chord diagram", substring = true)).assertExists()
    }

    @Test fun workingWithAnErrorOffersTheSettings() {
        var opened = 0
        content(state = QuickState.Working, error = "Microphone refused", onSettings = { opened++ })
        rule.onNodeWithText("Listening…").assertIsNotEnabled()
        rule.onNodeWithText("Microphone refused").assertExists()
        rule.onNodeWithText("Open the app's settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun landscapeHasTwoHalves() {
        content(result = am7)
        val record = rule.onNodeWithTag("recordChord").getUnclippedBoundsInRoot()
        val name = rule.onNodeWithTag("quickName").getUnclippedBoundsInRoot()
        assertTrue("record ${record.right} should be left of name ${name.left}", record.right <= name.left)
    }

    @Test fun portraitStacksTheHalves() {
        content(result = am7, size = PORTRAIT)
        val record = rule.onNodeWithTag("recordChord").getUnclippedBoundsInRoot()
        val name = rule.onNodeWithTag("quickName").getUnclippedBoundsInRoot()
        assertTrue("name ${name.top} should be below record ${record.bottom}", name.top >= record.bottom)
    }

    @Test fun everyControlIsNamedAndTheResultIsAnnounced() {
        content(result = am7, error = "Oops", onSettings = {})
        rule.assertEveryControlNamed()
        rule.assertAnnounced()
    }
}
