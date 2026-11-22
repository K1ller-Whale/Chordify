package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The chord edits of one song as its analysis screen uses them: the result to show, the saved edits, and the
 * editing session ([EditController]) behind [EditActions]. It has no Android or engine dependencies, so
 * AnalysisViewModel makes one with the library and the engine, and tests make one with fakes. [rebuild] gets
 * the model's result and the spans, [load], [save] and [clear] act on the song's edits file, [setAside] moves
 * a damaged one out of the way, and [next] suggests what follows chords (Harte labels) in a key ("C major").
 *
 * Only a file that cannot be read is damaged (spec §4): it stays in place, and the first save or clear sets it
 * aside before replacing it. Edits that read but cannot be rebuilt (a model that fails to load, say) are still the user's:
 * the model's result shows with a message, Restore stays available, and editing starts from them, its own
 * rebuild trying again, so nothing replaces them with the model's chords.
 */
internal class SongEdits(
    private val scope: CoroutineScope,
    private val rebuild: suspend (AnalysisResult, List<ChordSpan>) -> AnalysisResult,
    private val load: suspend () -> EditsLoad,
    private val save: suspend (ChordEdits) -> Unit,
    private val clear: suspend () -> Unit,
    private val setAside: suspend () -> Unit,
    private val next: suspend (chords: List<String>, key: String) -> List<String>,
) : EditActions {
    private val _shown = MutableStateFlow<AnalysisResult?>(null)
    private val _original = MutableStateFlow<AnalysisResult?>(null)
    private val _hasEdits = MutableStateFlow(false)
    private val _savedShapes = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    private val _damaged = MutableStateFlow(false)
    private val _edit = MutableStateFlow<EditUiState?>(null)
    private val _messages = Channel<String>(Channel.BUFFERED)

    /** The result to show: rebuilt from the saved edits, or the model's; null until [open]. */
    val shown: StateFlow<AnalysisResult?> = _shown
    /** The model's result. */
    val original: StateFlow<AnalysisResult?> = _original
    val hasEdits: StateFlow<Boolean> = _hasEdits
    val savedShapes: StateFlow<Map<String, List<Int>>> = _savedShapes
    /** Whether the song's edits file could not be read. */
    val damaged: StateFlow<Boolean> = _damaged
    /** The editing session; null when not editing. */
    val edit: StateFlow<EditUiState?> = _edit
    /** What the screen says once, outside an editing session (a failed restore, or chords that could not be rebuilt). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Whether [shown] is the result rebuilt from the saved edits, which an export then carries. */
    var showsEdits: Boolean = false
        private set

    private var controller: EditController? = null
    private var saved: ChordEdits? = null
    /** The edits file could not be read; it is set aside before anything replaces it. */
    private var unreadable = false

    /** Shows [loaded], the model's result, or the result rebuilt from the song's saved edits. Call it once. */
    suspend fun open(loaded: AnalysisResult) {
        _original.value = loaded
        controller = EditController(
            duration = loaded.source.duration,
            original = loaded,
            rebuild = { spans -> rebuild(loaded, spans) },
            save = { edits -> setAsideIfUnreadable(); save(edits) },
            clear = { setAsideIfUnreadable(); clear() },
            scope = scope,
            onFinished = { edits, result ->
                saved = edits
                _hasEdits.value = edits != null
                _savedShapes.value = edits?.shapes ?: emptyMap()
                _damaged.value = false
                showsEdits = edits != null
                _shown.value = result ?: loaded
            },
            onMessage = { _messages.trySend(it) },
        ).also { made -> scope.launch { made.state.collect { _edit.value = it } } }
        _shown.value = loadEdits(loaded) ?: loaded
    }

    /** The result rebuilt from the saved edits, or null when there are none, they are damaged, or they cannot be rebuilt now. */
    private suspend fun loadEdits(loaded: AnalysisResult): AnalysisResult? {
        when (val found = load()) {
            EditsLoad.None -> {}
            EditsLoad.Damaged -> {
                unreadable = true
                _damaged.value = true
            }
            is EditsLoad.Loaded -> {
                saved = found.edits
                _savedShapes.value = found.edits.shapes
                _hasEdits.value = true
                try {
                    return rebuild(loaded, found.edits.chords).also { showsEdits = true }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _messages.trySend(UPDATE_FAILED + reason(error))
                }
            }
        }
        return null
    }

    private suspend fun setAsideIfUnreadable() {
        if (!unreadable) return
        setAside()
        unreadable = false
    }

    override fun startEditing() {
        val loaded = _original.value ?: return
        if (_edit.value != null) return
        controller?.start(saved ?: ChordEdits(chords = spansOf(loaded)))
    }

    override fun cancelEditing() { controller?.cancel() }
    override fun done() { controller?.done() }
    override fun restore() { controller?.restore() }
    override fun select(index: Int?) { controller?.select(index) }
    override fun moveEdge(edge: Int, time: Double) { controller?.moveEdge(edge, time) }
    override fun setStart(index: Int, time: Double) { controller?.setStart(index, time) }
    override fun setEnd(index: Int, time: Double) { controller?.setEnd(index, time) }
    override fun split(index: Int, time: Double) { controller?.split(index, time) }
    override fun mergeWithNext(index: Int) { controller?.mergeWithNext(index) }
    override fun rename(index: Int, soundingLabel: String) { controller?.rename(index, soundingLabel) }
    override fun setShape(shapeLabel: String, shape: List<Int>?) { controller?.setShape(shapeLabel, shape) }
    override fun undo() { controller?.undo() }
    override fun redo() { controller?.redo() }
    override fun toggleLoop() { controller?.toggleLoop() }

    /** The chord's own runner-up readings first, then what usually follows the chords before it. */
    override suspend fun suggestions(index: Int): List<String> {
        val state = _edit.value ?: return emptyList()
        val chord = state.preview.chords.getOrNull(index) ?: return emptyList()
        val key = state.preview.key.global
        val before = state.preview.chords.take(index).map { it.label }.filter { it != "N" }
        val following = if (before.isEmpty()) emptyList() else try {
            next(before, "${key.tonic} ${key.mode}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
        return (chord.alternatives.map { it.label } + following).distinct().filter { it != chord.label }.take(6)
    }
}
