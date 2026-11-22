package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.arbaba.chordifymobile.edit.MIN_SPAN_SECONDS
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
        rule.onNodeWithTag("editStart").assertTextEquals("0:02.04")
        rule.onNodeWithTag("editEnd").assertTextEquals("0:04.04")
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
            "start 1.556", "start 2.554", "end 3.553", "end 4.551"), calls)
        assertEquals(1.556, stepToBeat(c.start, beats, -1), 1e-9)
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

    @Test fun aNoChordHasNoShapeToEdit() {
        val sevenths = Fixtures.sevenths
        val index = sevenths.chords.indexOfFirst { it.label == "N" }
        val chord = sevenths.chords[index]
        rule.show(LANDSCAPE) {
            Box(Modifier.fillMaxSize()) {
                EditPanel(chord, index, sevenths.chords.size, Notation.LETTERS, sevenths.beats, { chord.start },
                    {}, {}, {}, {}, {}, { calls += "shape" })
            }
        }
        rule.onNodeWithText("Edit shape…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Change chord…").performScrollTo().assertIsEnabled()
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

    @Test fun formatPreciseValues() {
        assertEquals("0:59.99", formatPrecise(59.996))
        assertEquals("0:00.00", formatPrecise(0.0))
        assertEquals("12:34.50", formatPrecise(754.5))
        assertEquals("0:00.00", formatPrecise(-1.0))
    }

    /** A middle chord from [start] to [end], with [beats] and a fixed playhead. */
    private fun custom(start: Double, end: Double, beats: List<Double>, now: Double = (start + end) / 2,
                       index: Int = 1, count: Int = 4) {
        val chord = result.chords[1].copy(start = start, end = end)
        rule.show(LANDSCAPE) {
            Box(Modifier.fillMaxSize()) {
                EditPanel(chord, index, count, Notation.LETTERS, beats, { now },
                    { calls += "start $it" }, { calls += "end $it" }, { calls += "split" }, { calls += "merge" },
                    { calls += "change" }, { calls += "shape" })
            }
        }
    }

    @Test fun startLaterThatWouldCrossTheEndIsDisabled() {
        custom(2.0, 4.0, listOf(1.0, 4.0 - MIN_SPAN_SECONDS / 2, 5.0))
        rule.onNodeWithContentDescription("Start one beat later").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Start one beat earlier").assertIsEnabled()
    }

    @Test fun startLaterJustInsideIsEnabled() {
        custom(2.0, 4.0, listOf(1.0, 4.0 - 1.2 * MIN_SPAN_SECONDS, 5.0))
        rule.onNodeWithContentDescription("Start one beat later").assertIsEnabled()
    }

    @Test fun endEarlierThatWouldCrossTheStartIsDisabled() {
        custom(2.0, 4.0, listOf(1.0, 2.0 + MIN_SPAN_SECONDS / 2, 5.0))
        rule.onNodeWithContentDescription("End one beat earlier").assertIsNotEnabled()
        rule.onNodeWithContentDescription("End one beat later").assertIsEnabled()
    }

    @Test fun endEarlierJustInsideIsEnabled() {
        custom(2.0, 4.0, listOf(1.0, 2.0 + 1.2 * MIN_SPAN_SECONDS, 5.0))
        rule.onNodeWithContentDescription("End one beat earlier").assertIsEnabled()
    }

    @Test fun splitNearAnEdgeIsDisabled() {
        custom(2.0, 4.0, listOf(1.0, 5.0), now = 2.0 + MIN_SPAN_SECONDS / 2)
        rule.onNodeWithText("Split here").assertIsNotEnabled()
    }

    @Test fun splitNearTheEndIsDisabled() {
        custom(2.0, 4.0, listOf(1.0, 5.0), now = 4.0 - MIN_SPAN_SECONDS / 2)
        rule.onNodeWithText("Split here").assertIsNotEnabled()
    }

    @Test fun splitJustInsideIsEnabled() {
        custom(2.0, 4.0, listOf(1.0, 5.0), now = 2.0 + 1.2 * MIN_SPAN_SECONDS)
        rule.onNodeWithText("Split here").assertIsEnabled()
    }

    @Test fun setToHereNeedsThePlayheadOnTheRightSide() {
        custom(2.0, 4.0, listOf(1.0, 5.0), now = 4.5)
        rule.onNodeWithText("Set start to here").assertIsNotEnabled()
        rule.onNodeWithText("Set end to here").assertIsEnabled()
    }

    @Test fun setToHereTooCloseToTheOtherEdge() {
        custom(2.0, 4.0, listOf(1.0, 5.0), now = 4.0 - MIN_SPAN_SECONDS / 2)
        rule.onNodeWithText("Set start to here").assertIsNotEnabled()
    }

    @Test fun emptyBeatsStepHalfASecond() {
        custom(2.0, 4.0, emptyList())
        rule.onNodeWithContentDescription("Start one beat earlier").assertIsEnabled().performScrollTo().performClick()
        rule.onNodeWithContentDescription("End one beat later").assertIsEnabled().performScrollTo().performClick()
        assertEquals(listOf("start 1.5", "end 4.5"), calls)
    }

    @Test fun firstChordEndEarlierStaysEnabled() {
        panel(0)
        rule.onNodeWithContentDescription("End one beat earlier").assertIsEnabled()
    }

    @Test fun lastChordStartLaterStaysEnabled() {
        panel(result.chords.size - 1)
        rule.onNodeWithContentDescription("Start one beat later").assertIsEnabled()
    }

    @Test fun splitFollowsThePlayhead() {
        val now = mutableStateOf(1.0)
        val chord = result.chords[1]
        rule.show(LANDSCAPE) {
            Box(Modifier.fillMaxSize()) {
                EditPanel(chord, 1, 4, Notation.LETTERS, beats, { now.value }, {}, {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText("Split here").assertIsNotEnabled()
        now.value = 3.0
        rule.waitForIdle()
        rule.onNodeWithText("Split here").assertIsEnabled()
        now.value = chord.end - MIN_SPAN_SECONDS / 2
        rule.waitForIdle()
        rule.onNodeWithText("Split here").assertIsNotEnabled()
    }

    @Test fun beatButtonIsNamedOnceNotAlsoByItsText() {
        panel(1)
        val node = rule.onNodeWithContentDescription("Start one beat earlier").fetchSemanticsNode()
        assertEquals(null, node.config.getOrNull(SemanticsProperties.Text))
        assertEquals(listOf("Start one beat earlier"), node.config.getOrNull(SemanticsProperties.ContentDescription))
    }
}
