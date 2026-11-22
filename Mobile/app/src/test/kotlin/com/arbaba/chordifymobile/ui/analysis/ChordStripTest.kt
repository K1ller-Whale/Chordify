package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import com.arbaba.chordifymobile.edit.snapToBeat
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordStripTest {
    @get:Rule val rule = createComposeRule()
    private val time = mutableStateOf(0.0)
    private val seeks = mutableListOf<Double>()

    // In a Box, so the strip takes its own height rather than the screen's.
    private fun showStrip() = rule.show(LANDSCAPE) {
        Box(Modifier.fillMaxSize()) { ChordStrip(Fixtures.loop, time, 0, Notation.LETTERS, null, setOf(1), { seeks += it }) }
    }

    @Test fun describesItself() {
        showStrip()
        rule.onNodeWithTag("strip")
            .assertContentDescriptionEquals("Timeline of 4 chords: tap or drag to move through the song")
    }

    @Test fun is72DpTall() {
        showStrip()
        rule.onNodeWithTag("strip").assertHeightIsEqualTo(72.dp)
    }

    @Test fun tapSeeks() {
        showStrip()
        rule.onNodeWithTag("strip").performTouchInput {
            click(Offset(width * 0.33f + 70.dp.toPx(), centerY))
        }
        assertEquals(1, seeks.size)
        assertEquals(1.0, seeks[0], 0.1)
    }

    @Test fun dragSeeks() {
        showStrip()
        rule.onNodeWithTag("strip").performTouchInput { swipeLeft() }
        assertTrue(seeks.isNotEmpty() && seeks.last() > 0)
    }

    @Test fun controlsAreNamed() {
        showStrip()
        rule.assertEveryControlNamed()
    }

    private val selects = mutableListOf<Int>()
    private val moves = mutableListOf<Pair<Int, Double>>()
    private val pps = PIXELS_PER_SECOND.dp
    private val loop = Fixtures.loop

    private fun showEditing(selected: Int?, result: AnalysisResult = loop) = rule.show(LANDSCAPE) {
        Box(Modifier.fillMaxSize()) {
            ChordStrip(result, time, 0, Notation.LETTERS, null, emptySet(), { seeks += it },
                       editing = true, selected = selected, onSelect = { selects += it }, onMoveEdge = { e, t -> moves += e to t })
        }
    }

    @Test fun editingStripIs110DpTall() {
        showEditing(null)
        rule.onNodeWithTag("strip").assertHeightIsEqualTo(110.dp)
    }

    @Test fun editingTapSelectsInsteadOfSeeking() {
        showEditing(null)
        val c = loop.chords[1]
        rule.onNodeWithTag("strip").performTouchInput {
            click(Offset(width * PLAYHEAD + ((c.start + c.end) / 2).toFloat() * pps.toPx(), centerY))
        }
        assertEquals(listOf(1), selects)
        assertTrue(seeks.isEmpty())
    }

    @Test fun draggingTheRightHandleReportsOnceAtTheEnd() {
        // One beat 3 dp past the drop point, so the drop snaps to it exactly.
        val c = loop.chords[1]
        val beat = c.end + 1.0 + 3.0 / PIXELS_PER_SECOND
        showEditing(1, loop.copy(beats = listOf(c.start, beat, loop.chords[2].end)))
        rule.onNodeWithTag("strip").performTouchInput {
            down(Offset(width * PLAYHEAD + c.end.toFloat() * pps.toPx(), centerY))
            moveBy(Offset(35.dp.toPx(), 0f))
            moveBy(Offset(35.dp.toPx(), 0f))
            assertTrue(moves.isEmpty())
            up()
        }
        assertEquals(1, moves.size)
        assertEquals(2, moves[0].first)
        assertEquals(beat, moves[0].second, 1e-6)
        assertTrue(seeks.isEmpty())
    }

    @Test fun aDropFarFromBeatsIsNotSnapped() {
        val c = loop.chords[1]
        showEditing(1, loop.copy(beats = listOf(c.end + 1.0 + 20.0 / PIXELS_PER_SECOND)))
        rule.onNodeWithTag("strip").performTouchInput {
            down(Offset(width * PLAYHEAD + c.end.toFloat() * pps.toPx(), centerY))
            moveBy(Offset(70.dp.toPx(), 0f))
            up()
        }
        assertEquals(c.end + 1.0, moves.single().second, 0.01)
    }

    @Test fun draggingTheLeftHandleMovesEdgeI() {
        showEditing(1, loop.copy(beats = emptyList()))
        val c = loop.chords[1]
        rule.onNodeWithTag("strip").performTouchInput {
            down(Offset(width * PLAYHEAD + c.start.toFloat() * pps.toPx(), centerY))
            moveBy(Offset(-35.dp.toPx(), 0f))
            up()
        }
        assertEquals(1, moves.size)
        assertEquals(1, moves[0].first)
        assertEquals(c.start - 0.5, moves[0].second, 0.01)
    }

    @Test fun theEdgeFollowsTheFingerWhileThePlayheadMoves() {
        val c = loop.chords[1]
        showEditing(1, loop.copy(beats = emptyList()))
        val fingerX = { w: Float -> w * PLAYHEAD + c.end.toFloat() * pps.value }
        var downAt = 0f
        rule.onNodeWithTag("strip").performTouchInput {
            downAt = width * PLAYHEAD + c.end.toFloat() * pps.toPx()
            down(Offset(downAt, centerY))
            moveBy(Offset(35.dp.toPx(), 0f))
        }
        // Playback carries the strip on by half a second while the finger stays put.
        rule.runOnIdle { time.value = 0.5 }
        rule.onNodeWithTag("strip").performTouchInput {
            moveBy(Offset(1f, 0f))
            up()
        }
        // The finger is 35 dp + 1 px right of the playhead-relative edge position; the strip moved 0.5 s.
        val density = rule.density.density
        val expected = c.end + 0.5 + (35f + 1f / density) / PIXELS_PER_SECOND
        assertEquals(2, moves.single().first)
        assertEquals(expected, moves.single().second, 0.01)
    }

    @Test fun aCancelledDragReportsNothing() {
        val c = loop.chords[1]
        showEditing(1)
        rule.onNodeWithTag("strip").performTouchInput {
            down(Offset(width * PLAYHEAD + c.end.toFloat() * pps.toPx(), centerY))
            moveBy(Offset(40.dp.toPx(), 0f))
            cancel()
        }
        assertTrue(moves.isEmpty())
    }

    @Test fun aVerticalSwipeDoesNotSelect() {
        showEditing(null)
        rule.onNodeWithTag("strip").performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
            up()
        }
        assertTrue(selects.isEmpty())
    }

    @Test fun talkBackCanSelectAChord() {
        showEditing(null)
        rule.onNodeWithTag("strip").assertContentDescriptionEquals("Chord strip, double-tap a chord to select it")
        val actions = rule.onNodeWithTag("strip").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        val names = actions.map { it.label }
        assertEquals(names.size, names.toSet().size)
        val pick = actions.first { it.label.startsWith("Select chord 2") }
        rule.runOnIdle { assertTrue(pick.action()) }
        assertEquals(listOf(1), selects)
    }

    @Test fun aTimeNearABeatSnapsToIt() {
        val beats = listOf(1.0, 1.5, 2.0)
        assertEquals(1.5, snapToBeat(1.5 + 5.0 / 70.0, beats, 6.0 / 70.0), 1e-9)
        assertEquals(1.25, snapToBeat(1.25, beats, 6.0 / 70.0), 1e-9)
    }

    @Test fun anEditedChordsNameEndsWithAPencil() {
        val c = loop.chords[0]
        assertEquals(c.display, stripName(c, Notation.LETTERS))
        assertEquals(c.display + " ✎", stripName(c.copy(edited = true), Notation.LETTERS))
    }
}
