package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.ui.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EditControllerTest {
    private val original = Fixtures.loop
    private val duration = original.source.duration
    private val saved = mutableListOf<ChordEdits>()
    private var cleared = 0
    private var failSave: Exception? = null
    private var finished: Pair<ChordEdits?, AnalysisResult?>? = null

    /** The fake rebuild: one chord per span, copying start, end, label and the edited flag. */
    private fun fakeRebuild(spans: List<ChordSpan>): AnalysisResult = original.copy(
        chords = spans.mapIndexed { i, s ->
            original.chords[0].copy(index = i, start = s.start, end = s.end, label = s.label, display = s.label, edited = s.edited)
        },
    )

    // Unconfined runs each launch at once, and the fakes never suspend, so everything is synchronous.
    private fun controller() = EditController(
        duration = duration,
        original = original,
        rebuild = { fakeRebuild(it) },
        save = { failSave?.let { error -> throw error }; saved += it },
        clear = { cleared++ },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        onFinished = { edits, shown -> finished = edits to shown },
    )

    private fun started(): EditController = controller().also { it.start(ChordEdits(chords = spansOf(original))) }

    @Test fun startShowsTheModelsChordsUntouched() {
        val s = started().state.value!!
        assertEquals(spansOf(original), s.spans)
        assertEquals(s.spans.size, s.preview.chords.size)
        assertNull(s.selected)
        assertFalse(s.canUndo); assertFalse(s.canRedo); assertFalse(s.dirty); assertFalse(s.looping)
    }

    @Test fun anOpUpdatesSpansPreviewDirtyAndUndo() {
        val c = started()
        c.rename(1, "E:min")
        val s = c.state.value!!
        assertEquals("E:min", s.spans[1].label)
        assertTrue(s.spans[1].edited)
        assertEquals("E:min", s.preview.chords[1].label)
        assertTrue(s.dirty); assertTrue(s.canUndo); assertFalse(s.canRedo)
    }

    @Test fun everyOpChangesTheSpans() {
        val c = started()
        val before = c.state.value!!.spans
        c.split(0, (before[0].start + before[0].end) / 2)
        assertEquals(before.size + 1, c.state.value!!.spans.size)
        c.mergeWithNext(0)
        assertEquals(before.size, c.state.value!!.spans.size)
        c.moveEdge(1, before[1].start + 0.2)
        assertEquals(before[1].start + 0.2, c.state.value!!.spans[0].end, 1e-9)
        c.setStart(2, before[2].start + 0.1)
        assertEquals(before[2].start + 0.1, c.state.value!!.spans[2].start, 1e-9)
        c.setEnd(2, before[2].end - 0.1)
        assertEquals(before[2].end - 0.1, c.state.value!!.spans[2].end, 1e-9)
    }

    @Test fun movingAnEdgeToItsCurrentPlaceIsNotAStep() {
        val c = started()
        c.moveEdge(1, c.state.value!!.spans[0].end)
        assertFalse(c.state.value!!.canUndo)
        assertFalse(c.state.value!!.dirty)
    }

    @Test fun undoAndRedoWalkTheHistory() {
        val c = started()
        val second = original.chords[1].label
        c.rename(0, "D:maj")
        c.rename(1, "E:min")
        c.undo()
        assertEquals(second, c.state.value!!.spans[1].label)
        assertEquals("D:maj", c.state.value!!.spans[0].label)
        assertTrue(c.state.value!!.canRedo)
        c.undo()
        assertFalse(c.state.value!!.canUndo)
        assertFalse(c.state.value!!.dirty)
        c.redo(); c.redo()
        assertEquals("E:min", c.state.value!!.spans[1].label)
        assertFalse(c.state.value!!.canRedo)
        c.undo()
        c.rename(2, "A:maj")
        assertFalse(c.state.value!!.canRedo)
    }

    @Test fun doneSavesTheEditsAndLeavesEditing() {
        val c = started()
        c.rename(1, "E:min")
        c.setShape("E:min", listOf(0, 2, 2, 0, 0, 0))
        c.done()
        assertNull(c.state.value)
        assertEquals(1, saved.size)
        assertEquals("E:min", saved[0].chords[1].label)
        assertEquals(mapOf("E:min" to listOf(0, 2, 2, 0, 0, 0)), saved[0].shapes)
        assertEquals(0, cleared)
        assertEquals(saved[0], finished!!.first)
        assertNotNull(finished!!.second)
    }

    @Test fun doneWithNothingEditedClearsTheFile() {
        val c = started()
        c.rename(1, "E:min")
        c.undo()
        c.done()
        assertNull(c.state.value)
        assertTrue(saved.isEmpty())
        assertEquals(1, cleared)
        assertNull(finished!!.first)
    }

    @Test fun aShapeAloneIsKeptAndSettingItToNullRemovesIt() {
        val c = started()
        c.setShape("G:maj", listOf(3, 2, 0, 0, 0, 3))
        assertEquals(1, c.state.value!!.shapes.size)
        c.setShape("G:maj", null)
        assertTrue(c.state.value!!.shapes.isEmpty())
        c.setShape("G:maj", listOf(3, 2, 0, 0, 0, 3))
        c.done()
        assertEquals(1, saved.size)
    }

    @Test fun aFailedSaveKeepsEditingWithTheError() {
        val c = started()
        c.rename(1, "E:min")
        failSave = IOException("disk full")
        c.done()
        val s = c.state.value!!
        assertEquals(SAVE_FAILED + "disk full", s.saveError)
        assertEquals("E:min", s.spans[1].label)
        assertNull(finished)
        failSave = null
        c.done()
        assertNull(c.state.value)
        assertEquals(1, saved.size)
    }

    @Test fun cancelDiscards() {
        val c = started()
        c.rename(1, "E:min")
        c.cancel()
        assertNull(c.state.value)
        assertTrue(saved.isEmpty())
        assertEquals(0, cleared)
        c.start(ChordEdits(chords = spansOf(original)))
        assertFalse(c.state.value!!.canUndo)
        assertFalse(c.state.value!!.dirty)
    }

    @Test fun restoreClearsTheFile() {
        val c = controller()
        c.restore()
        assertEquals(1, cleared)
        assertNull(finished!!.first)
        assertNull(finished!!.second)
    }

    @Test fun selectionClampsAfterAMerge() {
        val c = started()
        val last = c.state.value!!.spans.lastIndex
        c.select(last)
        assertEquals(last, c.state.value!!.selected)
        c.mergeWithNext(last - 1)
        assertEquals(last - 1, c.state.value!!.selected)
        c.select(99)
        assertNull(c.state.value!!.selected)
    }

    @Test fun selectionStaysOnTheSameIndexThroughAnEdit() {
        val c = started()
        c.select(1)
        c.rename(1, "E:min")
        assertEquals(1, c.state.value!!.selected)
    }

    @Test fun loopToggles() {
        val c = started()
        c.toggleLoop()
        assertTrue(c.state.value!!.looping)
        c.toggleLoop()
        assertFalse(c.state.value!!.looping)
    }

    @Test fun startFromSavedEditsKeepsThemAndIsNotDirty() {
        val edits = ChordEdits(chords = spansOf(original).mapIndexed { i, s -> if (i == 0) s.copy(label = "D:maj", edited = true) else s })
        val c = controller()
        c.start(edits)
        assertEquals("D:maj", c.state.value!!.spans[0].label)
        assertFalse(c.state.value!!.dirty)
    }
}
