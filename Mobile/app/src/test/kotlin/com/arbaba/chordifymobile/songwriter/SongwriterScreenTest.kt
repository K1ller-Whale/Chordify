package com.arbaba.chordifymobile.songwriter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.lm.Prediction
import com.arbaba.chordifymobile.engine.model.ProgressionKey
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertAnnounced
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongwriterScreenTest {
    @get:Rule val rule = createComposeRule()

    private val answer = ProgressionResponse(
        ProgressionKey("C", "major", 0.87, true),
        listOf(Prediction("F", "F", "IV", 0.4, "Often follows vi"), Prediction("G", "G", "V", 0.3, "Leads home")),
        emptyList(), "progression-ngram@1.0.0")

    private var suggested = 0
    private var added = 0
    private var picked: String? = null
    private var backs = 0

    private fun showSongwriter(size: DpSize = LANDSCAPE, answer: ProgressionResponse? = null, error: String? = null) = rule.show(size) {
        var chords by remember { mutableStateOf(listOf("C", "G", "Am")) }
        var draft by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        SongwriterContent(chords, draft, key, answer, error,
                          onRemove = { i -> chords = chords.filterIndexed { j, _ -> j != i } },
                          onDraft = { draft = it }, onAdd = { added++ }, onKey = { key = it },
                          onSuggest = { suggested++ }, onPick = { picked = it }, onBack = { backs++ })
    }

    @Test
    fun suggestionsAreAskedForOnOpeningAndAfterEveryChange() {
        showSongwriter()
        rule.waitForIdle()
        assertEquals(1, suggested)
        rule.onNodeWithContentDescription("Remove G").performClick()
        rule.waitForIdle()
        assertEquals(2, suggested)
        rule.onNodeWithTag("key").assertTextEquals("Key: from the chords ▾")
        rule.onNodeWithTag("key").performClick()
        rule.onNodeWithText("Am").performClick()
        rule.waitForIdle()
        assertEquals(3, suggested)
        rule.onNodeWithTag("key").assertTextEquals("Key: Am ▾")
    }

    @Test
    fun typingAndAddingCallsOnAdd() {
        showSongwriter()
        rule.onNodeWithTag("draft").performTextInput("F")
        rule.onNodeWithText("Add").performClick()
        assertEquals(1, added)
    }

    @Test
    fun theTopBarHasBack() {
        showSongwriter()
        rule.onNodeWithTag("back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun theAnswerShowsItsKeyAndReasonsAndAPickAddsAChord() {
        showSongwriter(answer = answer)
        rule.onNodeWithText("Assuming C major (87% sure)").assertIsDisplayed()
        rule.onNodeWithText("Often follows vi").assertIsDisplayed()
        rule.onNodeWithText("Leads home").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add F").performClick()
        assertEquals("F", picked)
    }

    @Test
    fun thereIsNoSuggestButton() {
        showSongwriter(answer = answer)
        rule.onNodeWithText("Suggest the next chord").assertDoesNotExist()
    }

    @Test
    fun landscapePutsTheSuggestionsRightOfTheDraft() {
        showSongwriter(LANDSCAPE, answer)
        val draft = rule.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot
        val heading = rule.onNodeWithText("WHAT COULD COME NEXT").fetchSemanticsNode().boundsInRoot
        assert(heading.left >= draft.right) { "heading $heading draft $draft" }
    }

    @Test
    fun portraitPutsTheSuggestionsBelowTheDraft() {
        showSongwriter(PORTRAIT, answer)
        val draft = rule.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot
        val heading = rule.onNodeWithText("WHAT COULD COME NEXT").fetchSemanticsNode().boundsInRoot
        assert(heading.top >= draft.bottom) { "heading $heading draft $draft" }
    }

    @Test
    fun everyControlIsNamedAndTheAnswerIsAnnounced() {
        showSongwriter(answer = answer)
        rule.assertEveryControlNamed()
        rule.assertAnnounced()
    }
}
