package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.ui.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditOpsTest {
    private fun span(start: Double, end: Double, label: String, edited: Boolean = false) = ChordSpan(start, end, label, edited)

    /** C:maj 0-2, G:maj 2-4, A:min 4-6, F:maj 6-8. */
    private val four = listOf(span(0.0, 2.0, "C:maj"), span(2.0, 4.0, "G:maj"), span(4.0, 6.0, "A:min"), span(6.0, 8.0, "F:maj"))

    private fun assertNormal(spans: List<ChordSpan>, duration: Double) {
        assertEquals(0.0, spans.first().start, 1e-9)
        assertEquals(duration, spans.last().end, 1e-9)
        spans.zipWithNext().forEach { (a, b) ->
            assertEquals(a.end, b.start, 1e-9)
            assertTrue(a.label != b.label)
        }
        spans.forEach { assertTrue(it.end - it.start >= MIN_SPAN_SECONDS - 1e-9) }
    }

    @Test
    fun spansOfCopiesTheChords() {
        val spans = spansOf(Fixtures.loop)
        assertEquals(Fixtures.loop.chords.size, spans.size)
        Fixtures.loop.chords.zip(spans).forEach { (c, s) ->
            assertEquals(ChordSpan(c.start, c.end, c.label, c.edited), s)
        }
    }

    @Test
    fun normaliseSortsAndClamps() {
        val out = normalise(listOf(span(4.0, 9.5, "G:maj"), span(-1.0, 4.0, "C:maj")), 8.0)
        assertEquals(listOf(span(0.0, 4.0, "C:maj"), span(4.0, 8.0, "G:maj")), out)
    }

    @Test
    fun aGapIsFilledWithAnEditedNoChord() {
        val out = normalise(listOf(span(0.0, 2.0, "C:maj"), span(3.0, 8.0, "G:maj")), 8.0)
        assertEquals(listOf(span(0.0, 2.0, "C:maj"), span(2.0, 3.0, "N", true), span(3.0, 8.0, "G:maj")), out)
        val tail = normalise(listOf(span(0.0, 6.0, "C:maj")), 8.0)
        assertEquals(span(6.0, 8.0, "N", true), tail.last())
    }

    @Test
    fun anOverlapMovesTheLaterStart() {
        val out = normalise(listOf(span(0.0, 5.0, "C:maj"), span(4.0, 8.0, "G:maj")), 8.0)
        assertEquals(listOf(span(0.0, 5.0, "C:maj"), span(5.0, 8.0, "G:maj")), out)
    }

    @Test
    fun aShortSpanGivesItsTimeToThePreviousOne() {
        val out = normalise(listOf(span(0.0, 4.0, "C:maj"), span(4.0, 4.03, "G:maj"), span(4.03, 8.0, "A:min")), 8.0)
        assertEquals(listOf(span(0.0, 4.03, "C:maj"), span(4.03, 8.0, "A:min")), out)
    }

    @Test
    fun aShortFirstSpanGivesItsTimeToTheNextOne() {
        val out = normalise(listOf(span(0.0, 0.03, "C:maj"), span(0.03, 8.0, "G:maj")), 8.0)
        assertEquals(listOf(span(0.0, 8.0, "G:maj")), out)
    }

    @Test
    fun equalNeighboursMergeAndKeepEitherEditedFlag() {
        val out = normalise(listOf(span(0.0, 2.0, "C:maj"), span(2.0, 8.0, "C:maj", true)), 8.0)
        assertEquals(listOf(span(0.0, 8.0, "C:maj", true)), out)
    }

    @Test
    fun movingAnEdgeMarksBothSidesEdited() {
        val out = moveEdge(four, 1, 2.5, 8.0)
        assertEquals(span(0.0, 2.5, "C:maj", true), out[0])
        assertEquals(span(2.5, 4.0, "G:maj", true), out[1])
        assertEquals(four.drop(2), out.drop(2))
        assertNormal(out, 8.0)
    }

    @Test
    fun anEdgeClampsToItsNeighbours() {
        val late = moveEdge(four, 1, 99.0, 8.0)
        assertEquals(listOf("C:maj", "A:min", "F:maj"), late.map { it.label })
        assertEquals(4.0, late[0].end, 1e-9)
        val early = moveEdge(four, 2, -5.0, 8.0)
        assertEquals(listOf("C:maj", "A:min", "F:maj"), early.map { it.label })
        assertEquals(2.0, early[1].start, 1e-9)
        assertNormal(early, 8.0)
    }

    @Test
    fun draggingAnEdgeOntoTheNextRemovesTheChord() {
        val out = moveEdge(four, 1, 4.0, 8.0)
        assertEquals(listOf("C:maj", "A:min", "F:maj"), out.map { it.label })
        assertEquals(0.0, out[0].start, 1e-9)
        assertEquals(4.0, out[0].end, 1e-9)
        assertNormal(out, 8.0)
    }

    @Test
    fun anInvalidEdgeChangesNothing() {
        assertEquals(four, moveEdge(four, 0, 1.0, 8.0))
        assertEquals(four, moveEdge(four, 4, 7.0, 8.0))
    }

    @Test
    fun setStartAndSetEndMoveTheEdges() {
        assertEquals(moveEdge(four, 2, 3.5, 8.0), setStart(four, 2, 3.5, 8.0))
        assertEquals(moveEdge(four, 3, 5.5, 8.0), setEnd(four, 2, 5.5, 8.0))
        assertEquals(four, setStart(four, 0, 1.0, 8.0))
        assertEquals(four, setEnd(four, 3, 7.0, 8.0))
    }

    @Test
    fun splitMakesTwoEditedHalves() {
        val out = split(four, 1, 3.0, 8.0)
        assertEquals(5, out.size)
        assertEquals(span(2.0, 3.0, "G:maj", true), out[1])
        assertEquals(span(3.0, 4.0, "G:maj", true), out[2])
        assertEquals(four[0], out[0])
        assertEquals(four[2], out[3])
    }

    @Test
    fun splitNearAnEdgeIsANoOp() {
        assertEquals(four, split(four, 1, 2.03, 8.0))
        assertEquals(four, split(four, 1, 3.97, 8.0))
        assertEquals(four, split(four, 1, 2.0, 8.0))
        assertEquals(four, split(four, 1, 9.0, 8.0))
    }

    @Test
    fun mergeWithNextKeepsTheFirstLabel() {
        val out = mergeWithNext(four, 1, 8.0)
        assertEquals(3, out.size)
        assertEquals(span(2.0, 6.0, "G:maj", true), out[1])
        assertNormal(out, 8.0)
    }

    @Test
    fun mergingTheLastSpanIsANoOp() {
        assertEquals(four, mergeWithNext(four, 3, 8.0))
    }

    @Test
    fun renameSetsTheLabelAndMarksItEdited() {
        val out = rename(four, 1, "D:min", 8.0)
        assertEquals(span(2.0, 4.0, "D:min", true), out[1])
        assertEquals(4, out.size)
    }

    @Test
    fun renamingToANeighboursLabelMerges() {
        val out = rename(four, 1, "C:maj", 8.0)
        assertEquals(listOf(span(0.0, 4.0, "C:maj", true), four[2], four[3]), out)
    }

    @Test
    fun snapToBeatWithinAndOutsideTheTolerance() {
        val beats = listOf(0.5, 1.0, 1.5, 2.0)
        assertEquals(1.0, snapToBeat(1.04, beats, 0.06), 1e-9)
        assertEquals(1.5, snapToBeat(1.3, beats, 0.25), 1e-9)
        assertEquals(1.3, snapToBeat(1.3, beats, 0.1), 1e-9)
        assertEquals(1.3, snapToBeat(1.3, emptyList(), 0.1), 1e-9)
    }

    @Test
    fun stepToBeatMovesToTheNeighbouringBeat() {
        val beats = listOf(0.5, 1.0, 1.5, 2.0)
        assertEquals(1.5, stepToBeat(1.2, beats, +1), 1e-9)
        assertEquals(1.0, stepToBeat(1.2, beats, -1), 1e-9)
        assertEquals(1.5, stepToBeat(1.0, beats, +1), 1e-9)
        assertEquals(0.5, stepToBeat(1.0, beats, -1), 1e-9)
    }

    @Test
    fun stepToBeatWithoutBeatsMovesHalfASecond() {
        assertEquals(1.5, stepToBeat(1.0, emptyList(), +1), 1e-9)
        assertEquals(0.5, stepToBeat(1.0, emptyList(), -1), 1e-9)
    }

    @Test
    fun labelsConvertThroughTheCapo() {
        assertEquals("A:maj", soundingLabel("G:maj", 2, false))
        assertEquals("G:maj", shapeLabelOf("A:maj", 2, false))
        assertEquals("N", soundingLabel("N", 2, false))
        assertEquals("N", shapeLabelOf("N", 2, false))
        assertEquals("Bb:min/3", soundingLabel("G:min/3", 3, true))
    }
}
