package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongPanelTest {
    @get:Rule val rule = createComposeRule()
    private val loop = Fixtures.loop
    private val suggestion = suggestCapo(loop.chords)

    @Test fun capoZeroShowsDetailsAndSuggestion() {
        rule.show(LANDSCAPE) { SongPanel(loop, loop, 0, suggestion) }
        rule.onNodeWithTag("songPanel").assertIsDisplayed()
        rule.onNodeWithText(songDetails(loop)).assertExists()
        rule.onNodeWithText(suggestionNotice(suggestion, 0)!!).assertExists()
        for (t in listOf("Tonic", "Subdominant", "Dominant", "Outside the key")) rule.onNodeWithText(t).assertExists()
    }

    @Test fun capoFiveShowsTheNoticeInPortrait() {
        rule.show(PORTRAIT) { SongPanel(loop, loop, 5, suggestion) }
        rule.onNodeWithText(capoNotice(loop, 5)!!).assertExists()
        rule.onNodeWithText("Space plays or pauses, ← and → jump between chords, R toggles Roman numerals.").assertExists()
    }
}
