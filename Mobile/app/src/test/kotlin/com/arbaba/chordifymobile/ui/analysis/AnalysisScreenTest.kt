package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.edit.DISCARD_CONFIRM
import com.arbaba.chordifymobile.edit.EDITS_UNREADABLE
import com.arbaba.chordifymobile.edit.EditActions
import com.arbaba.chordifymobile.edit.EditUiState
import com.arbaba.chordifymobile.edit.RESTORE_CONFIRM
import com.arbaba.chordifymobile.edit.SAVE_FAILED
import com.arbaba.chordifymobile.edit.shapeLabelOf
import com.arbaba.chordifymobile.edit.soundingLabel
import com.arbaba.chordifymobile.edit.spansOf
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AnalysisScreenTest {
    @get:Rule val rule = createComposeRule()

    private val loop = Fixtures.loop
    private val clock = PlaybackClock(loop.source.duration)
    private var notations = mutableListOf<Notation>()

    private fun showScreen(size: DpSize, audio: AudioFile = AudioFile.PLAYABLE, capo: Int = 0) = rule.show(size) {
        AnalysisContent("Loop", loop, clock, Notation.LETTERS, capo, audio, false,
            onNotation = { notations += it }, onCapo = {}, onPickFile = {})
    }

    /** Every tab's label is laid out whole on one line (real fonts: graphicsMode=NATIVE). */
    private fun tabLabelsFit(size: DpSize) {
        showScreen(size)
        val inATab = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        for (tab in PanelTab.entries) {
            val label = rule.onNode(hasText(tab.label) and inATab, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            val layout = layouts.single()
            val natural = layout.multiParagraph.intrinsics.maxIntrinsicWidth
            val room = layout.layoutInput.constraints.maxWidth
            assertTrue("\"${tab.label}\" needs $natural px at $size, it has $room px", natural <= room)
            assertFalse("\"${tab.label}\" is cut to one line at $size", layout.multiParagraph.didExceedMaxLines)
        }
    }

    @Test fun tabLabelsFitALandscapePhone() = tabLabelsFit(LANDSCAPE)
    @Test fun tabLabelsFitANarrowerLandscapePhone() = tabLabelsFit(DpSize(730.dp, 360.dp))
    @Test fun tabLabelsFitAPortraitPhone() = tabLabelsFit(PORTRAIT)

    @Test fun theNoAudioBannerSitsAboveTheStageNotOverIt() {
        showScreen(DpSize(730.dp, 336.dp), audio = AudioFile.MISSING, capo = 2) // a 360 dp-tall phone, less its status bar
        val banner = rule.onNodeWithTag("noAudio").getUnclippedBoundsInRoot()
        val stage = rule.onNodeWithTag("stage").getUnclippedBoundsInRoot()
        assertTrue("banner ${banner.bottom} must end before the stage ${stage.top}", banner.bottom <= stage.top)
        for (tag in listOf("nowName", "theoryLine", "soundsAs")) {
            val line = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$tag $line must be inside the stage $stage", line.top >= stage.top && line.bottom <= stage.bottom)
        }
        val diagram = rule.onNode(hasContentDescription("Guitar chord diagram", substring = true)).getUnclippedBoundsInRoot()
        assertTrue("diagram $diagram must be inside the stage $stage", diagram.top >= stage.top && diagram.bottom <= stage.bottom)
    }

    @Test fun landscapePutsThePanelBesideTheStage() {
        showScreen(LANDSCAPE)
        val stage = rule.onNodeWithTag("stageArea").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("panelArea").getUnclippedBoundsInRoot()
        assertEquals(stage.top, panel.top)
        assertTrue("panel ${panel.left} must start at or after stage ${stage.right}", panel.left >= stage.right)
    }

    @Test fun portraitStacksThePanelUnderTheStage() {
        showScreen(PORTRAIT)
        val stage = rule.onNodeWithTag("stageArea").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("panelArea").getUnclippedBoundsInRoot()
        assertTrue("panel ${panel.top} must start at or after stage ${stage.bottom}", panel.top >= stage.bottom)
    }

    @Test fun tabsSwitchThePanel() {
        showScreen(LANDSCAPE)
        for (tab in PanelTab.entries) rule.onNodeWithText(tab.label).assertExists()
        rule.onNodeWithText("Chord").assertIsSelected()
        rule.onNodeWithText("Song").performClick()
        rule.onNodeWithText("Song").assertIsSelected()
    }

    @Test fun theArrowJumpsToTheNextChord() {
        showScreen(LANDSCAPE)
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals(loop.chords[1].start + 0.01, clock.time.value, 1e-6) }
    }

    @Test fun rTogglesTheNotation() {
        showScreen(LANDSCAPE)
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.R) }
        rule.runOnIdle { assertEquals(listOf(Notation.ROMAN), notations) }
    }

    @Test fun spaceStartsAndStopsThePlayback() {
        rule.mainClock.autoAdvance = false
        showScreen(LANDSCAPE)
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.Spacebar) }
        rule.runOnIdle { assertTrue(clock.playing.value) }
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.Spacebar) }
        rule.runOnIdle { assertFalse(clock.playing.value) }
    }

    @Test fun aFailedAnalysisOffersToTryAgain() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "00", 0L, status = Status.FAILED,
            errorCode = "decode", errorMessage = "bad", modelFingerprint = "f")
        rule.show { AnalysisStatus(row, onRetry = {}) }
        rule.onNodeWithText("Could not analyse this audio").assertExists()
        rule.onNodeWithText("Try again").assertExists()
    }

    /** An [EditActions] that records the calls the screen makes. */
    private class Recorder : EditActions {
        val calls = mutableListOf<String>()
        override fun startEditing() { calls += "start" }
        override fun cancelEditing() { calls += "cancel" }
        override fun done() { calls += "done" }
        override fun restore() { calls += "restore" }
        override fun select(index: Int?) { calls += "select $index" }
        override fun moveEdge(edge: Int, time: Double) { calls += "moveEdge $edge" }
        override fun setStart(index: Int, time: Double) { calls += "setStart $index" }
        override fun setEnd(index: Int, time: Double) { calls += "setEnd $index" }
        override fun split(index: Int, time: Double) { calls += "split $index" }
        override fun mergeWithNext(index: Int) { calls += "merge $index" }
        override fun rename(index: Int, soundingLabel: String) { calls += "rename $index $soundingLabel" }
        override fun setShape(shapeLabel: String, shape: List<Int>?) { calls += "setShape $shapeLabel" }
        override fun undo() { calls += "undo" }
        override fun redo() { calls += "redo" }
        override fun toggleLoop() { calls += "loop" }
        override suspend fun suggestions(index: Int): List<String> = emptyList()
    }

    private fun editState(selected: Int? = null, dirty: Boolean = false, saveError: String? = null) =
        EditUiState(spansOf(loop), emptyMap(), loop, selected, canUndo = true, canRedo = false, dirty = dirty,
            looping = false, saveError = saveError)

    private fun showEditing(actions: Recorder, edit: EditUiState? = editState(), hasEdits: Boolean = false,
                            damaged: Boolean = false, size: DpSize = LANDSCAPE) = rule.show(size) {
        AnalysisContent("Loop", loop, clock, Notation.LETTERS, 0, AudioFile.PLAYABLE, false,
            onNotation = {}, onCapo = {}, onPickFile = {}, hasEdits = hasEdits, editsDamaged = damaged,
            edit = edit, editActions = actions)
    }

    @Test fun editingShowsTheHintInPlaceOfTheTabs() {
        showEditing(Recorder())
        rule.onNodeWithText("Tap a chord in the strip to change it.").assertExists()
        rule.onNodeWithTag("tab-CHORD").assertDoesNotExist()
    }

    @Test fun aSelectedChordHidesTheHintAndTheTabs() {
        showEditing(Recorder(), editState(selected = 1))
        rule.onNodeWithText("Tap a chord in the strip to change it.").assertDoesNotExist()
        rule.onNodeWithTag("tab-CHORD").assertDoesNotExist()
    }

    @Test fun notEditingKeepsTheTabs() {
        showEditing(Recorder(), edit = null)
        rule.onNodeWithTag("tab-CHORD").assertExists()
        rule.onNodeWithTag("editsDamaged").assertDoesNotExist()
    }

    @Test fun doneAndUndoReachTheActions() {
        val actions = Recorder()
        showEditing(actions)
        rule.onNodeWithTag("undo").performClick()
        rule.onNodeWithTag("done").performClick()
        rule.runOnIdle { assertEquals(listOf("undo", "done"), actions.calls) }
    }

    @Test fun cancelWithoutChangesLeavesAtOnce() {
        val actions = Recorder()
        showEditing(actions)
        rule.onNodeWithTag("cancelEdit").performClick()
        rule.runOnIdle { assertEquals(listOf("cancel"), actions.calls) }
        rule.onNodeWithText(DISCARD_CONFIRM).assertDoesNotExist()
    }

    @Test fun cancelWithChangesAsksFirst() {
        val actions = Recorder()
        showEditing(actions, editState(dirty = true))
        rule.onNodeWithTag("cancelEdit").performClick()
        rule.onNodeWithText(DISCARD_CONFIRM).assertExists()
        rule.runOnIdle { assertTrue(actions.calls.isEmpty()) }
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithText(DISCARD_CONFIRM).assertDoesNotExist()
        rule.runOnIdle { assertTrue(actions.calls.isEmpty()) }
        rule.onNodeWithTag("cancelEdit").performClick()
        rule.onNodeWithText("Discard").performClick()
        rule.runOnIdle { assertEquals(listOf("cancel"), actions.calls) }
    }

    @Test fun restoreAsksFirst() {
        val actions = Recorder()
        showEditing(actions, edit = null, hasEdits = true)
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Restore the model's chords").performClick()
        rule.onNodeWithText(RESTORE_CONFIRM).assertExists()
        rule.onNodeWithText("Keep my chords").performClick()
        rule.runOnIdle { assertTrue(actions.calls.isEmpty()) }
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Restore the model's chords").performClick()
        rule.onNodeWithText("Restore").performClick()
        rule.runOnIdle { assertEquals(listOf("restore"), actions.calls) }
    }

    @Test fun damagedEditsShowABanner() {
        showEditing(Recorder(), edit = null, damaged = true)
        rule.onNodeWithTag("editsDamaged").assertIsDisplayed()
        rule.onNodeWithText(EDITS_UNREADABLE).assertExists()
    }

    @Test fun aSaveErrorShows() {
        showEditing(Recorder(), editState(saveError = "disk full"))
        rule.onNodeWithTag("saveError").assertIsDisplayed()
        rule.onNodeWithText(SAVE_FAILED + "disk full").assertExists()
    }

    @Test fun theKeyboardIsOffWhileEditing() {
        showEditing(Recorder())
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.Spacebar) }
        rule.runOnIdle { assertFalse(clock.playing.value) }
    }

    @Test fun aPickIsConvertedToTheSoundingLabel() {
        // capo 2: the shape G:maj sounds as A:maj; the picker's suggestions go the other way
        assertEquals(soundingLabel("G:maj", 2, false), pickToRename("G:maj", 2, false))
        assertEquals("A:maj", pickToRename("G:maj", 2, false))
        assertEquals("G:maj", shapeLabelOf("A:maj", 2, false))
        assertEquals("N", pickToRename("N", 2, false))
        assertEquals("C:maj", pickToRename("C:maj", 0, false))
    }
}
