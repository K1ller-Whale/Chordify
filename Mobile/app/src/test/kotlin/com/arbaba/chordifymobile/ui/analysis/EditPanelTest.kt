package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.edit.stepToBeat
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditPanelTest {
    @get:Rule val rule = createComposeRule()

    private val result = Fixtures.loop
    private val beats = result.beats
    private val calls = mutableListOf<String>()

    private fun fmt(t: Double): String {
        val c = Math.floor(t * 100 + 1e-6).toLong()
        return "%d:%02d.%02d".format(c / 6000, c / 100 % 60, c % 100)
    }

    private fun panel(index: Int, size: DpSize = LANDSCAPE, now: Double? = null) {
        val chord = result.chords[index]
        val at = now ?: ((chord.start + chord.end) / 2)
        rule.show(size) {
            Box(Modifier.fillMaxSize()) {
                EditPanel(chord, index, result.chords.size, Notation.LETTERS, beats, { at },
                    { calls += "start $it" }, { calls += "end $it" }, { calls += "split" }, { calls += "merge" },
                    { calls += "change" }, { calls += "shape" })
            }
        }
    }

    @Test fun timesShowTheChord() {
        panel(1)
        val c = result.chords[1]
        rule.onNodeWithTag("editStart").assertTextEquals(fmt(c.start))
        rule.onNodeWithTag("editEnd").assertTextEquals(fmt(c.end))
        rule.onNodeWithTag("editPanel").assertExists()
        rule.onNodeWithText("STARTS").assertExists()
        rule.onNodeWithText("ENDS").assertExists()
    }

    @Test fun beatButtonsCallBack() {
        panel(1)
        val c = result.chords[1]
        rule.onNodeWithContentDescription("Start one beat earlier").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Start one beat later").performScrollTo().performClick()
        rule.onNodeWithContentDescription("End one beat earlier").performScrollTo().performClick()
        rule.onNodeWithContentDescription("End one beat later").performScrollTo().performClick()
        assertEquals(listOf(
            "start ${stepToBeat(c.start, beats, -1)}", "start ${stepToBeat(c.start, beats, 1)}",
            "end ${stepToBeat(c.end, beats, -1)}", "end ${stepToBeat(c.end, beats, 1)}"), calls)
    }

    @Test fun actionButtonsCallBack() {
        val c = result.chords[1]
        val at = (c.start + c.end) / 2
        panel(1)
        rule.onNodeWithText("Set start to here").performScrollTo().performClick()
        rule.onNodeWithText("Set end to here").performScrollTo().performClick()
        rule.onNodeWithText("Split here").performScrollTo().performClick()
        rule.onNodeWithText("Merge with next").performScrollTo().performClick()
        rule.onNodeWithText("Change chord…").performScrollTo().performClick()
        rule.onNodeWithText("Edit shape…").performScrollTo().performClick()
        assertEquals(listOf("start $at", "end $at", "split", "merge", "change", "shape"), calls)
    }

    @Test fun firstChordStartIsFixed() {
        panel(0)
        rule.onNodeWithContentDescription("Start one beat earlier").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Start one beat later").assertIsNotEnabled()
        rule.onNodeWithText("Set start to here").assertIsNotEnabled()
        rule.onNodeWithText("Merge with next").assertIsEnabled()
    }

    @Test fun lastChordEndAndMergeAreFixed() {
        panel(result.chords.size - 1)
        rule.onNodeWithText("Merge with next").assertIsNotEnabled()
        rule.onNodeWithContentDescription("End one beat later").assertIsNotEnabled()
        rule.onNodeWithText("Set end to here").assertIsNotEnabled()
    }

    @Test fun splitNeedsThePlayheadInside() {
        panel(1, now = result.chords[1].start - 1.0)
        rule.onNodeWithText("Split here").assertIsNotEnabled()
        rule.onNodeWithText("Set end to here").assertIsNotEnabled()
        rule.onNodeWithText("Set start to here").assertIsEnabled()
    }

    @Test fun everyControlNamedLandscape() { panel(1, LANDSCAPE); rule.assertEveryControlNamed() }
    @Test fun everyControlNamedPortrait() { panel(1, PORTRAIT); rule.assertEveryControlNamed() }
}
