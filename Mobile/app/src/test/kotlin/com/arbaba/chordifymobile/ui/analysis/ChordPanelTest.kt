package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.model.Alternative
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordPanelTest {
    @get:Rule val rule = createComposeRule()
    private val result = Fixtures.loop
    private val chord = result.chords[0]

    @Test fun showsNowAndNextInLandscape() {
        rule.show(LANDSCAPE) { ChordPanel(chord, result, { 0.5 }, Notation.LETTERS) }
        check()
    }

    @Test fun showsNowAndNextInPortrait() {
        rule.show(PORTRAIT) { ChordPanel(chord, result, { 0.5 }, Notation.LETTERS) }
        check()
    }

    private fun check() {
        rule.onNodeWithTag("chordPanel").assertIsDisplayed()
        rule.onNodeWithText("NOW").assertIsDisplayed()
        rule.onNodeWithText("WHAT COMES NEXT").assertIsDisplayed()
        rule.onNode(hasText("Tonic in C major", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("Most likely · " + chord.next[0].reason)).assertExists()
        rule.onNodeWithText(countdown(chord, result.beats, 0.5)).assertExists()
    }

    @Test fun showsTheDoubtLine() {
        val unsure = chord.copy(confidence = 0.4, alternatives = listOf(Alternative("Am", "Am", 0.3), Alternative("Em", "Em", 0.2)))
        rule.show { ChordPanel(unsure, result, { 0.5 }, Notation.LETTERS) }
        rule.onNodeWithText(unsure(unsure)!!).assertExists()
    }
}
