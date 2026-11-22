package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What results/<id>.edits.json holds (spec §3.1); shapes map a shape label (C6) to six frets E..e, -1 muted, 0 open. */
@Serializable
data class ChordEdits(val version: Int = 1, val chords: List<ChordSpan>, val shapes: Map<String, List<Int>> = emptyMap())

private val EditsJson = Json { encodeDefaults = true }

/** The edits as compact JSON. */
fun encodeEdits(edits: ChordEdits): String = EditsJson.encodeToString(ChordEdits.serializer(), edits)

/** The edits in [text]; throws [IllegalArgumentException] on any damage (bad JSON, version != 1, a shape not 6 ints in -1..24). */
fun decodeEdits(text: String): ChordEdits {
    val edits = try {
        EditsJson.decodeFromString(ChordEdits.serializer(), text)
    } catch (error: Exception) {
        throw IllegalArgumentException("The edits are not readable.", error)
    }
    require(edits.version == 1) { "Unknown edits version ${edits.version}." }
    for ((label, shape) in edits.shapes) {
        require(shape.size == 6 && shape.all { it in -1..24 }) { "The shape of $label is not six frets." }
    }
    return edits
}

/** What a song's edits file turned out to be. */
sealed interface EditsLoad {
    data object None : EditsLoad
    data class Loaded(val edits: ChordEdits) : EditsLoad
    data object Damaged : EditsLoad
}

/** What AnalysisContent needs while editing (null when not editing). preview.chords[i] is spans[i]
 *  rebuilt; both are in sounding labels (no capo). */
data class EditUiState(
    val spans: List<ChordSpan>,
    val shapes: Map<String, List<Int>>,
    val preview: AnalysisResult,
    val selected: Int?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val dirty: Boolean,
    val looping: Boolean,
    val saveError: String? = null,
)

/** Everything the editing UI can ask for; AnalysisViewModel implements it. Indices are into EditUiState.spans. */
interface EditActions {
    fun startEditing()
    fun cancelEditing()
    fun done()
    fun restore()
    fun select(index: Int?)
    /** Edge k is the boundary between spans k-1 and k, 1 <= k < size. */
    fun moveEdge(edge: Int, time: Double)
    fun setStart(index: Int, time: Double)
    fun setEnd(index: Int, time: Double)
    fun split(index: Int, time: Double)
    fun mergeWithNext(index: Int)
    fun rename(index: Int, soundingLabel: String)
    /** A null [shape] sends the label back to the app's default. */
    fun setShape(shapeLabel: String, shape: List<Int>?)
    fun undo()
    fun redo()
    fun toggleLoop()
    /** Up to 6 sounding Harte labels, not the current one. */
    suspend fun suggestions(index: Int): List<String>
}

/** Edit actions that do nothing, for screens and tests that do not edit. */
object NoEditActions : EditActions {
    override fun startEditing() {}
    override fun cancelEditing() {}
    override fun done() {}
    override fun restore() {}
    override fun select(index: Int?) {}
    override fun moveEdge(edge: Int, time: Double) {}
    override fun setStart(index: Int, time: Double) {}
    override fun setEnd(index: Int, time: Double) {}
    override fun split(index: Int, time: Double) {}
    override fun mergeWithNext(index: Int) {}
    override fun rename(index: Int, soundingLabel: String) {}
    override fun setShape(shapeLabel: String, shape: List<Int>?) {}
    override fun undo() {}
    override fun redo() {}
    override fun toggleLoop() {}
    override suspend fun suggestions(index: Int): List<String> = emptyList()
}

const val EDITS_UNREADABLE = "Your edits for this song could not be loaded."
const val SAVE_FAILED = "Could not save your changes: "
const val RESTORE_CONFIRM = "Your chords and shapes for this song will be replaced by the model's. This cannot be undone."
const val DISCARD_CONFIRM = "Discard your changes?"
