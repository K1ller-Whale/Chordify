package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapeEditorTest {
    @get:Rule val rule = createComposeRule()

    private val open = listOf(3, 2, 0, 0, 0, 3)
    private fun saved(size: androidx.compose.ui.unit.DpSize = LANDSCAPE, shape: List<Int>? = open,
                      default: List<Int>? = open, label: String = "G:maj", capo: Int = 0): MutableList<List<Int>?> {
        val out = mutableListOf<List<Int>?>()
        rule.show(size) { ShapeEditor(label, shape, default, capo, onSave = { out += it }, onCancel = {}) }
        return out
    }

    @Test fun tappingACellThenSaveReportsTheFret() {
        val out = saved(shape = listOf(-1, -1, -1, -1, -1, -1), default = null)
        rule.onNodeWithTag("shapeEditor").assertIsDisplayed()
        rule.onNodeWithContentDescription("String E, fret 3").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf<List<Int>?>(listOf(3, -1, -1, -1, -1, -1)), out)
    }

    @Test fun tappingTheSameCellAgainMakesTheStringOpen() {
        val out = saved(shape = listOf(3, 2, 0, 0, 0, 3))
        rule.onNodeWithContentDescription("String E, fret 3").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf<List<Int>?>(listOf(0, 2, 0, 0, 0, 3)), out)
    }

    @Test fun nutTogglesOpenAndMuted() {
        val out = saved()
        rule.onNodeWithContentDescription("String A: muted").assertDoesNotExist()
        rule.onNodeWithContentDescription("String D: open").performClick()
        rule.onNodeWithContentDescription("String D: muted").performClick()
        rule.onNodeWithContentDescription("String D: open").performClick()
        rule.onNodeWithContentDescription("String D: muted").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf<List<Int>?>(listOf(3, 2, -1, 0, 0, 3)), out)
    }

    @Test fun higherAndLowerChangeTheStartingFret() {
        saved(shape = listOf(-1, -1, -1, -1, -1, -1), default = null)
        rule.onNodeWithText("Starting fret: 1").assertIsDisplayed()
        rule.onNodeWithText("Higher up the neck").performClick()
        rule.onNodeWithText("Starting fret: 2").assertIsDisplayed()
        rule.onNodeWithText("Lower down the neck").performClick()
        rule.onNodeWithText("Lower down the neck").performClick()
        rule.onNodeWithText("Starting fret: 1").assertIsDisplayed()
    }

    @Test fun startingFretFollowsTheLowestFrettedNote() {
        saved(shape = listOf(-1, 5, 7, 7, 6, 5), label = "A:maj", default = null)
        rule.onNodeWithText("Starting fret: 5").assertIsDisplayed()
    }

    @Test fun defaultShapeSavesNull() {
        val out = saved()
        rule.onNodeWithText("Default shape").performClick()
        assertEquals(listOf<List<Int>?>(null), out)
        assertNull(out.single())
    }

    @Test fun warningShowsForABadShapeAndNotForAGoodOne() {
        saved(shape = listOf(3, -1, 0, 0, 3, 3))
        rule.onNodeWithTag("shapeWarning").assertIsDisplayed()
        rule.onNodeWithText("This shape has no B, the 3rd of G").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test fun noWarningForAGoodShape() {
        saved()
        assertEquals(0, rule.onAllNodes(hasTestTag("shapeWarning")).fetchSemanticsNodes().size)
    }

    @Test fun everyControlIsNamedInBothSizes() {
        saved()
        rule.assertEveryControlNamed()
    }

    @Test fun portraitHasEverythingToo() {
        saved(PORTRAIT)
        rule.onNodeWithText("Strum it").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.assertEveryControlNamed()
    }

    @Test fun cancelCalls() {
        var cancelled = 0
        rule.show { ShapeEditor("G:maj", open, open, 0, onSave = {}, onCancel = { cancelled++ }) }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }
}

