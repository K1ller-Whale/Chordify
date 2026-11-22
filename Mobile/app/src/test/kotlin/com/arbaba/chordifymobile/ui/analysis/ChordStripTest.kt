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

    private fun showEditing(selected: Int?) = rule.show(LANDSCAPE) {
        Box(Modifier.fillMaxSize()) {
            ChordStrip(Fixtures.loop, time, 0, Notation.LETTERS, null, emptySet(), { seeks += it },
                       editing = true, selected = selected, onSelect = { selects += it }, onMoveEdge = { e, t -> moves += e to t })
        }
    }

    @Test fun editingStripIs110DpTall() {
        showEditing(null)
        rule.onNodeWithTag("strip").assertHeightIsEqualTo(110.dp)
    }

    @Test fun editingTapSelectsInsteadOfSeeking() {
        showEditing(null)
        val c = Fixtures.loop.chords[1]
        rule.onNodeWithTag("strip").performTouchInput {
            click(Offset(width * 0.33f + ((c.start + c.end) / 2).toFloat() * 70.dp.toPx(), centerY))
        }
        assertEquals(listOf(1), selects)
        assertTrue(seeks.isEmpty())
    }

    @Test fun draggingTheRightHandleReportsOnceAtTheEnd() {
        showEditing(1)
        val c = Fixtures.loop.chords[1]
        val beats = Fixtures.loop.beats
        rule.onNodeWithTag("strip").performTouchInput {
            val from = Offset(width * 0.33f + c.end.toFloat() * 70.dp.toPx(), centerY)
            down(from)
            moveBy(Offset(35.dp.toPx(), 0f))
            moveBy(Offset(35.dp.toPx(), 0f))
            assertTrue(moves.isEmpty())
            up()
        }
        assertEquals(1, moves.size)
        assertEquals(2, moves[0].first)
        assertEquals(snapToBeat(c.end + 1.0, beats, 6.0 / 70.0), moves[0].second, 1e-6)
        assertEquals(c.end + 1.0, moves[0].second, 0.1)
        assertTrue(seeks.isEmpty())
    }

    @Test fun draggingTheLeftHandleMovesEdgeI() {
        showEditing(1)
        val c = Fixtures.loop.chords[1]
        rule.onNodeWithTag("strip").performTouchInput {
            down(Offset(width * 0.33f + c.start.toFloat() * 70.dp.toPx(), centerY))
            moveBy(Offset(-35.dp.toPx(), 0f))
            up()
        }
        assertEquals(1, moves.size)
        assertEquals(1, moves[0].first)
        assertEquals(c.start - 0.5, moves[0].second, 0.1)
    }

    @Test fun aTimeNearABeatSnapsToIt() {
        val beats = listOf(1.0, 1.5, 2.0)
        assertEquals(1.5, snapToBeat(1.5 + 5.0 / 70.0, beats, 6.0 / 70.0), 1e-9)
        assertEquals(1.25, snapToBeat(1.25, beats, 6.0 / 70.0), 1e-9)
    }

    @Test fun anEditedChordsNameEndsWithAPencil() {
        val c = Fixtures.loop.chords[0]
        assertEquals(chordNameOf(c, false), stripName(c, Notation.LETTERS))
        assertEquals(chordNameOf(c, true), stripName(c.copy(edited = true), Notation.LETTERS))
    }

    private fun chordNameOf(c: com.arbaba.chordifymobile.engine.model.ChordSegment, edited: Boolean) = c.display + if (edited) " ✎" else ""
}
