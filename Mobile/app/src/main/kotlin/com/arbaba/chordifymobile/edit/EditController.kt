package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

private const val PREVIEW_FAILED = "Could not update the chords: "

/**
 * The editing session of one song: the spans and shapes being corrected, an undo/redo history, and a
 * preview of the analysis rebuilt after every operation. It has no Android or engine dependencies:
 * [rebuild], [save] and [clear] are lambdas, and [scope] runs them.
 *
 * Operations change the spans at once; [state] is published when the preview for them is rebuilt, and
 * a preview that a later operation has overtaken is dropped. [onFinished] gets the saved edits and the
 * shown analysis after Done (both null when nothing was kept, and after [restore]).
 */
class EditController(
    private val duration: Double,
    @Suppress("unused") private val original: AnalysisResult,
    private val rebuild: suspend (List<ChordSpan>) -> AnalysisResult,
    private val save: suspend (ChordEdits) -> Unit,
    private val clear: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val onFinished: (ChordEdits?, AnalysisResult?) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<EditUiState?>(null)

    /** The editing state; null when not editing. */
    val state: StateFlow<EditUiState?> = _state

    private var active = false
    private var saving = false
    private val editable get() = active && !saving
    private var start = ChordEdits(chords = emptyList())
    private var current = start
    private val undoStack = ArrayDeque<ChordEdits>()
    private val redoStack = ArrayDeque<ChordEdits>()
    private var selected: Int? = null
    private var looping = false
    private var saveError: String? = null
    private var preview: AnalysisResult? = null
    private var fresh = false
    private var stamp = 0

    /** Begins editing from [from] (the saved edits, or the model's chords as spans). */
    fun start(from: ChordEdits) {
        active = true
        start = from
        current = from
        undoStack.clear()
        redoStack.clear()
        selected = null
        looping = false
        saveError = null
        preview = null
        refresh()
    }

    /** Selects span [index] (null clears the selection). */
    fun select(index: Int?) {
        if (!editable) return
        selected = index?.takeIf { it in current.chords.indices }
        publish()
    }

    /** Moves the boundary [edge] to [time]. */
    fun moveEdge(edge: Int, time: Double) = change { it.copy(chords = moveEdge(it.chords, edge, time, duration)) }
    /** Moves the start of span [index] to [time]. */
    fun setStart(index: Int, time: Double) = change { it.copy(chords = setStart(it.chords, index, time, duration)) }
    /** Moves the end of span [index] to [time]. */
    fun setEnd(index: Int, time: Double) = change { it.copy(chords = setEnd(it.chords, index, time, duration)) }
    /** Cuts span [index] in two at [time]. */
    fun split(index: Int, time: Double) = change { it.copy(chords = split(it.chords, index, time, duration)) }
    /** Joins span [index] with the next one. */
    fun mergeWithNext(index: Int) = change { it.copy(chords = mergeWithNext(it.chords, index, duration)) }
    /** Gives span [index] the sounding Harte label [soundingLabel]. */
    fun rename(index: Int, soundingLabel: String) = change { it.copy(chords = rename(it.chords, index, soundingLabel, duration)) }

    /** Sets the custom shape of [shapeLabel]; null sends it back to the app's default. */
    fun setShape(shapeLabel: String, shape: List<Int>?) = change {
        it.copy(shapes = if (shape == null) it.shapes - shapeLabel else it.shapes + (shapeLabel to shape))
    }

    /** Takes back the last change. */
    fun undo() {
        if (!editable || undoStack.isEmpty()) return
        redoStack.addLast(current)
        current = undoStack.removeLast()
        moved()
    }

    /** Re-does the last undone change. */
    fun redo() {
        if (!editable || redoStack.isEmpty()) return
        undoStack.addLast(current)
        current = redoStack.removeLast()
        moved()
    }

    /** Turns looping of the selected chord on or off. */
    fun toggleLoop() {
        if (!editable) return
        looping = !looping
        publish()
    }

    /** Leaves editing and drops the changes (ignored while a save runs). */
    fun cancel() {
        if (editable) finish()
    }

    /** Saves the edits (or clears the file when nothing was edited) and leaves editing; a failed save keeps editing. */
    fun done() {
        if (!editable) return
        saving = true
        val edits = current
        val fromPreview = if (fresh) preview else null
        scope.launch {
            try {
                val kept = edits.chords.any { it.edited } || edits.shapes.isNotEmpty()
                val shown = if (kept) (fromPreview ?: rebuild(edits.chords)).also { save(edits) } else null
                if (!kept) clear()
                saving = false
                finish()
                onFinished(if (kept) edits else null, shown)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saving = false
                saveError = SAVE_FAILED + (error.message ?: error.javaClass.simpleName)
                _state.value = _state.value?.copy(saveError = saveError)
            }
        }
    }

    /** Deletes the saved edits so the model's chords and shapes show again. */
    fun restore() {
        scope.launch {
            try {
                clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                return@launch
            }
            finish()
            onFinished(null, null)
        }
    }

    private fun finish() {
        active = false
        saving = false
        stamp++
        undoStack.clear()
        redoStack.clear()
        selected = null
        looping = false
        saveError = null
        preview = null
        fresh = false
        _state.value = null
    }

    private fun change(op: (ChordEdits) -> ChordEdits) {
        if (!editable) return
        val next = op(current)
        if (next == current) return
        undoStack.addLast(current)
        redoStack.clear()
        current = next
        moved()
    }

    private fun moved() {
        saveError = null
        selected = selected?.coerceAtMost(current.chords.lastIndex)
        refresh()
    }

    private fun refresh() {
        val mine = ++stamp
        fresh = false
        val spans = current.chords
        scope.launch {
            val built = try {
                rebuild(spans)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (active && mine == stamp) {
                    // Keep what is shown; with nothing shown yet, there is no session to keep.
                    val shown = _state.value
                    if (shown == null) finish() else {
                        saveError = PREVIEW_FAILED + (error.message ?: error.javaClass.simpleName)
                        _state.value = shown.copy(saveError = saveError)
                    }
                }
                return@launch
            }
            if (active && mine == stamp) {
                preview = built
                fresh = true
                publish()
            }
        }
    }

    private fun publish() {
        val built = preview
        if (!active || !fresh || built == null) return
        _state.value = EditUiState(
            spans = current.chords,
            shapes = current.shapes,
            preview = built,
            selected = selected,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            dirty = current != start,
            looping = looping,
            saveError = saveError,
        )
    }
}
