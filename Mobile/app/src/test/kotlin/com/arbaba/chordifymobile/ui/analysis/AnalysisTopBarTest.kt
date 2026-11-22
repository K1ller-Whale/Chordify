package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisTopBarTest {
    @get:Rule val rule = createComposeRule()

    private val loop = Fixtures.loop
    private val suggestion = suggestCapo(loop.chords)

    private val capos = mutableListOf<Int>()
    private val notations = mutableListOf<Notation>()
    private val exports = mutableListOf<ExportFormat>()
    private var picked = 0
    private var backed = 0

    private var edits = 0
    private var undos = 0
    private var redos = 0
    private var cancels = 0
    private var dones = 0
    private var restores = 0

    private fun show(size: DpSize = LANDSCAPE, capo: Int = 0, notation: Notation = Notation.LETTERS,
                     title: String = "loop.wav", editing: Boolean = false, canUndo: Boolean = false,
                     canRedo: Boolean = false, hasEdits: Boolean = false) = rule.show(size) {
        AnalysisTopBar(title, loop, capo, suggestion, notation, onBack = { backed++ }, onCapo = { capos += it },
            onNotation = { notations += it }, onExport = { exports += it }, onPickFile = { picked++ },
            editing = editing, canUndo = canUndo, canRedo = canRedo, hasEdits = hasEdits,
            onEdit = { edits++ }, onUndo = { undos++ }, onRedo = { redos++ },
            onCancelEdit = { cancels++ }, onDone = { dones++ }, onRestore = { restores++ })
    }

    @Test fun editButtonCallsOnEdit() {
        show()
        rule.onNodeWithTag("edit").assertContentDescriptionEquals("Edit chords").performClick()
        assertEquals(1, edits)
    }

    @Test fun restoreItemOnlyWithEdits() {
        show()
        rule.onNodeWithTag("more").performClick()
        rule.onAllNodes(hasText("Restore the model's chords")).assertCountEquals(0)
    }

    @Test fun restoreItemCallsOnRestore() {
        show(hasEdits = true)
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Restore the model's chords").performClick()
        assertEquals(1, restores)
    }

    @Test fun editingControlsCallBack() {
        show(editing = true, canUndo = true, canRedo = true)
        rule.onNodeWithText("✎ Editing").assertIsDisplayed()
        rule.onNodeWithTag("undo").assertContentDescriptionEquals("Undo").performClick()
        rule.onNodeWithTag("redo").assertContentDescriptionEquals("Redo").performClick()
        rule.onNodeWithTag("cancelEdit").performClick()
        rule.onNodeWithTag("done").performClick()
        assertEquals(listOf(1, 1, 1, 1), listOf(undos, redos, cancels, dones))
    }

    @Test fun undoAndRedoAreDisabledWhenTheyCannotAct() {
        show(editing = true)
        rule.onNodeWithTag("undo").assertIsNotEnabled()
        rule.onNodeWithTag("redo").assertIsNotEnabled()
        rule.onNodeWithTag("done").assertIsEnabled()
    }

    @Test fun backWhileEditingCancelsTheEdit() {
        show(editing = true)
        rule.onNodeWithTag("back").performClick()
        assertEquals(1, cancels)
        assertEquals(0, backed)
    }

    @Test fun editingHidesTheCapoChipTheMenuAndTheEditButton() {
        show(editing = true)
        rule.onAllNodes(hasTestTag("capo")).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("more")).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("edit")).assertCountEquals(0)
    }

    @Test fun everyControlIsNamedWhileEditing() {
        show(editing = true, canUndo = true, canRedo = true)
        rule.assertEveryControlNamed()
    }

    @Test fun editingFitsPortrait() {
        show(PORTRAIT, editing = true, canUndo = true, canRedo = true)
        rule.onNodeWithTag("done").assertIsDisplayed()
        rule.onNodeWithText("✎ Editing").assertIsDisplayed()
        rule.assertEveryControlNamed()
    }

    @Test fun capoChipListsTheSuggestedFretAndCallsBack() {
        show()
        rule.onNodeWithTag("capo").assertIsDisplayed()
        rule.onNodeWithText("Capo: none ▾").assertIsDisplayed()
        rule.onNodeWithTag("capo").performClick()
        rule.onNodeWithText("fret 5  ★ 100% open chords").performClick()
        assertEquals(listOf(5), capos)
    }

    @Test fun capoChipShowsTheFret() {
        show(capo = 3)
        rule.onNodeWithText("Capo: fret 3 ▾").assertIsDisplayed()
    }

    @Test fun notationItemOffersTheOtherNotation() {
        show()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Show Roman numerals").performClick()
        assertEquals(listOf(Notation.ROMAN), notations)
    }

    @Test fun notationItemReadsShowLettersForRoman() {
        show(notation = Notation.ROMAN)
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Show letters").performClick()
        assertEquals(listOf(Notation.LETTERS), notations)
    }

    @Test fun exportAndPickFileItems() {
        show()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Export chords (.lab)").performClick()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Export analysis (.json)").performClick()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Pick the audio file").performClick()
        assertEquals(listOf(ExportFormat.LAB, ExportFormat.JSON), exports)
        assertEquals(1, picked)
    }

    @Test fun backCallsOnBack() {
        show()
        rule.onNodeWithTag("back").performClick()
        assertEquals(1, backed)
    }

    @Test fun everyControlIsNamed() {
        show()
        rule.assertEveryControlNamed()
    }

    @Test fun keyLineShowsWhenWide() {
        show(LANDSCAPE)
        rule.onNodeWithText(songFacts(loop)).assertIsDisplayed()
        rule.onNodeWithText("loop.wav").assertIsDisplayed()
    }

    @Test fun keyLineIsDroppedBelow480dp() {
        show(DpSize(400.dp, 360.dp))
        rule.onNodeWithText("loop.wav").assertIsDisplayed()
        rule.onNodeWithTag("capo").assertIsDisplayed()
        rule.onNodeWithTag("more").assertIsDisplayed()
        rule.onAllNodesWithTextCount(songFacts(loop), 0)
    }

    @Test fun portraitDropsTheKeyLineToo() {
        show(PORTRAIT)
        rule.onAllNodesWithTextCount(songFacts(loop), 0)
        rule.onNodeWithTag("more").assertIsDisplayed()
    }

    private val longTitle = "Sweet Home Alabama (live at the old theatre, 1974 remaster)"

    private fun titleWidth(): Float =
        rule.onNodeWithText(longTitle).getBoundsInRoot().width.value

    @Test fun longTitleKeepsUsableWidthBesideKeyLineAt480dp() {
        show(DpSize(480.dp, 360.dp), title = longTitle)
        rule.onNodeWithText(songFacts(loop)).assertExists()
        assertTrue("title width ${titleWidth()}dp", titleWidth() >= 96f)
    }

    @Test fun longTitleKeepsUsableWidthAt360dp() {
        show(DpSize(360.dp, 360.dp), title = longTitle)
        assertTrue("title width ${titleWidth()}dp", titleWidth() >= 96f)
    }

    @Test fun keyLineAppearsAt480dpNotAt479dp() {
        show(DpSize(480.dp, 360.dp))
        rule.onAllNodesWithTextCount(songFacts(loop), 1)
    }

    @Test fun keyLineAbsentAt479dp() {
        show(DpSize(479.dp, 360.dp))
        rule.onAllNodesWithTextCount(songFacts(loop), 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, n: Int) {
        assertEquals(n, onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size)
    }
}
