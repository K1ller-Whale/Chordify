package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoAudioBannerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun playableWithoutMismatchShowsNothing() {
        rule.show { NoAudioBanner(AudioFile.PLAYABLE, false, {}) }
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("noAudio")).fetchSemanticsNodes().size)
    }

    @Test fun checkingShowsNothing() {
        rule.show { NoAudioBanner(AudioFile.CHECKING, false, {}) }
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("noAudio")).fetchSemanticsNodes().size)
    }

    @Test fun missingShowsTextAndPickButton() {
        var picked = 0
        rule.show { NoAudioBanner(AudioFile.MISSING, false, { picked++ }) }
        rule.onNodeWithTag("noAudio").assertIsDisplayed()
        rule.onNodeWithText(FILE_MISSING).assertIsDisplayed()
        rule.onNodeWithText("Pick the file").performClick()
        assertEquals(1, picked)
    }

    @Test fun mismatchShowsItsText() {
        rule.show { NoAudioBanner(AudioFile.PLAYABLE, true, {}) }
        rule.onNodeWithTag("noAudio").assertIsDisplayed()
        rule.onNodeWithText(FILE_MISMATCH).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Pick the file")).fetchSemanticsNodes().size)
    }
}
