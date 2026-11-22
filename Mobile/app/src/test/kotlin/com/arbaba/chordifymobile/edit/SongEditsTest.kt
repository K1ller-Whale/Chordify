package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.ui.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SongEditsTest {
    private val original = Fixtures.loop
    private val calls = mutableListOf<String>()
    private val saved = mutableListOf<ChordEdits>()
    private var stored: EditsLoad = EditsLoad.None
    private var failRebuilds = 0
    private var failSetAside: Exception? = null
    private var failClear: Exception? = null
    private var asked: Pair<List<String>, String>? = null

    /** The user's edits: chord 1 corrected to E minor, and a shape. */
    private val userEdits = ChordEdits(
        chords = spansOf(original).mapIndexed { i, s -> if (i == 1) s.copy(label = "E:min", edited = true) else s },
        shapes = mapOf("E:min" to listOf(0, 2, 2, 0, 0, 0)),
    )

    /** One chord per span, copying start, end, label and the edited flag; the first [failRebuilds] calls throw. */
    private fun fakeRebuild(result: AnalysisResult, spans: List<ChordSpan>): AnalysisResult {
        if (failRebuilds > 0) {
            failRebuilds--
            throw OutOfMemoryErrorLike("out of memory")
        }
        return result.copy(chords = spans.mapIndexed { i, s ->
            result.chords[0].copy(index = i, start = s.start, end = s.end, label = s.label, display = s.label, edited = s.edited)
        })
    }

    private class OutOfMemoryErrorLike(message: String) : RuntimeException(message)

    // Unconfined runs each launch at once, and the fakes never suspend, so everything is synchronous.
    private fun opened(): SongEdits = SongEdits(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        rebuild = { result, spans -> fakeRebuild(result, spans) },
        load = { stored },
        save = { calls += "save"; saved += it },
        clear = { calls += "clear"; failClear?.let { throw it } },
        setAside = { calls += "setAside"; failSetAside?.let { throw it } },
        next = { chords, key -> asked = chords to key; listOf("F:maj", "G:maj") },
    ).also { runBlocking { it.open(original) } }

    /** Every message said so far. */
    private fun SongEdits.said(): List<String> = runBlocking {
        buildList { while (true) add(withTimeoutOrNull(50) { messages.first() } ?: break) }
    }

    @Test fun withoutEditsTheModelsResultShows() {
        val e = opened()
        assertEquals(original, e.shown.value)
        assertEquals(original, e.original.value)
        assertFalse(e.hasEdits.value)
        assertFalse(e.damaged.value)
        assertTrue(e.said().isEmpty())
    }

    @Test fun savedEditsShowRebuilt() {
        stored = EditsLoad.Loaded(userEdits)
        val e = opened()
        assertEquals("E:min", e.shown.value!!.chords[1].label)
        assertTrue(e.hasEdits.value)
        assertTrue(e.showsEdits)
        assertEquals(userEdits.shapes, e.savedShapes.value)
    }

    // -- edits that load but cannot be rebuilt are the user's, not damage (finding 3) --------------------

    @Test fun aFailedRebuildOnOpenKeepsTheSavedEditsAndSaysSo() {
        stored = EditsLoad.Loaded(userEdits)
        failRebuilds = 1
        val e = opened()
        assertEquals(original, e.shown.value)
        assertFalse("edits that load are not damaged", e.damaged.value)
        assertTrue("Restore stays available", e.hasEdits.value)
        assertFalse("an export does not claim the model's chords are the edits", e.showsEdits)
        assertEquals(listOf(UPDATE_FAILED + "out of memory"), e.said())
    }

    @Test fun editingAfterAFailedOpenStartsFromTheSavedEditsAndKeepsThem() {
        stored = EditsLoad.Loaded(userEdits)
        failRebuilds = 1
        val e = opened()
        e.startEditing() // its own rebuild works this time
        assertEquals(userEdits.chords, e.edit.value!!.spans)
        assertFalse(e.edit.value!!.dirty)
        e.done()
        assertEquals(listOf("save"), calls)
        assertEquals(listOf(userEdits), saved)
        assertEquals("E:min", e.shown.value!!.chords[1].label)
        assertTrue(e.showsEdits)
    }

    @Test fun aSessionWhoseFirstRebuildFailsEndsAndSaysSo() {
        val e = opened()
        failRebuilds = 1
        e.startEditing()
        assertNull(e.edit.value)
        assertEquals(listOf(UPDATE_FAILED + "out of memory"), e.said())
    }

    // -- a damaged file is set aside before anything replaces it (finding 3) ------------------------------

    @Test fun doneWithNothingEditedAfterDamageSetsTheFileAsideFirst() {
        stored = EditsLoad.Damaged
        val e = opened()
        assertTrue(e.damaged.value)
        e.startEditing()
        assertEquals(spansOf(original), e.edit.value!!.spans)
        e.done()
        assertEquals(listOf("setAside", "clear"), calls)
        assertFalse(e.damaged.value)
    }

    @Test fun savingAfterDamageSetsTheFileAsideOnce() {
        stored = EditsLoad.Damaged
        val e = opened()
        e.startEditing()
        e.rename(1, "E:min")
        e.done()
        e.startEditing()
        e.rename(2, "D:min")
        e.done()
        assertEquals(listOf("setAside", "save", "save"), calls)
    }

    @Test fun aFileThatCannotBeSetAsideIsNotReplaced() {
        stored = EditsLoad.Damaged
        val e = opened()
        e.startEditing()
        e.rename(1, "E:min")
        failSetAside = IOException("no space")
        e.done()
        assertEquals(SAVE_FAILED + "no space", e.edit.value!!.saveError)
        assertEquals(listOf("setAside"), calls)
        failSetAside = null
        e.done()
        assertEquals(listOf("setAside", "setAside", "save"), calls)
        assertNull(e.edit.value)
    }

    @Test fun undamagedEditsAreNeverSetAside() {
        stored = EditsLoad.Loaded(userEdits)
        val e = opened()
        e.restore()
        assertEquals(listOf("clear"), calls)
        assertFalse(e.hasEdits.value)
        assertEquals(original, e.shown.value)
    }

    // -- a failed restore says so (finding 6) -----------------------------------------------------------

    @Test fun aFailedRestoreKeepsTheEditsAndSaysSo() {
        stored = EditsLoad.Loaded(userEdits)
        val e = opened()
        failClear = IOException("read-only")
        e.restore()
        assertTrue(e.hasEdits.value)
        assertEquals("E:min", e.shown.value!!.chords[1].label)
        assertEquals(listOf(RESTORE_FAILED + "read-only"), e.said())
    }

    // -- suggestions, as the view model gives them ----------------------------------------------------

    @Test fun suggestionsAskWhatFollowsTheChordsBeforeInTheSongsKey() = runBlocking {
        val e = opened()
        assertTrue("nothing to suggest outside editing", e.suggestions(2).isEmpty())
        e.startEditing()
        val got = e.suggestions(2)
        assertEquals(spansOf(original).take(2).map { it.label } to "C major", asked)
        val chord = e.edit.value!!.preview.chords[2]
        val expected = (chord.alternatives.map { it.label } + listOf("F:maj", "G:maj")).distinct().filter { it != chord.label }.take(6)
        assertEquals(expected, got)
    }
}
