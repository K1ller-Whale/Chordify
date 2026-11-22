# Mobile M8: correcting chords, their timing and their shapes. Implementation plan

> **For agentic workers:** each task below is self-contained. Read §2 "Conventions", the contracts
> your task names in §3, and your task. Nothing else from earlier conversations is needed. Steps
> use checkbox (`- [ ]`) syntax.

## 1. Overview

This plan implements `docs/superpowers/specs/2026-11-22-mobile-chord-editing-design.md`.

A player of the Android app can correct an analysis by hand: change which chord plays when, move
where chords start and end, and change a chord's guitar shape for the whole song. The model's
result file is never changed. Edits live in a small file beside it (`results/<id>.edits.json`).
Opening the song rebuilds the analysis from the edited chords with a new pure engine function,
`rebuild`, extracted from today's `analyseMeasured`. The `.json` export carries the rebuilt
analysis plus an `edits` block (the model's original chords and the custom shapes), and importing
that file on another phone restores both.

Architecture:
- **Engine:** `rebuild`.
- **App, pure model:** `ChordEdits` and the edit operations, in a new package `edit`.
- **Storage and export:** `Library`, `Export.kt`.
- **Editing state machine:** `EditController`, behind `AnalysisViewModel`.
- **UI components:** strip, edit panel, chord picker, shape editor, stage, top bar and transport,
  all wired in `AnalysisScreen`.

Wave 0 lands every shared type, signature and the pure edit operations as real code. Wave 1 is
eleven independent file-owned slices. Wave 2 integrates and verifies everything, on the emulator
too.

## 2. Conventions (every task)

- **Paths and modules.**
  - The repository is `D:\development\python\ai\Chordify`; use Git Bash.
  - Android project `Mobile/`: app module `Mobile/app` (package `com.arbaba.chordifymobile`) and
    engine module `Mobile/engine` (package `com.arbaba.chordifymobile.engine`).
  - Abbreviations:
    - `APP` = `Mobile/app/src/main/kotlin/com/arbaba/chordifymobile`
    - `JT` = `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile` (app JVM tests)
    - `DT` = `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile` (device tests)
    - `ENG` = `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine`
    - `ET` = `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine`
- **Branches and worktrees.**
  - The base branch is `mobile-m8-chord-editing`.
  - Each task works in its own worktree on a branch `m8/<task-id>`, made from the base branch's tip
    at the start of its wave. The orchestrator creates the worktree and copies `local.properties`:

    ```bash
    git worktree add ../chordify-<id> -b m8/<id> mobile-m8-chord-editing
    cp Mobile/local.properties ../chordify-<id>/Mobile/
    ```

  - The orchestrator merges every branch of a wave before the next wave starts. File sets within a
    wave are disjoint.
- **Gradle.**
  - Run `export JAVA_HOME="/c/Program Files/Java/jdk-21" ANDROID_USER_HOME="D:/android-user"`, then
    `cd Mobile && ./gradlew --max-workers=4 ...`.
  - Up to three agents build at once on a 16 GB machine. Never run `./gradlew --stop`, never kill
    java or Gradle processes, and never start an emulator. Only I1 uses the emulator.
- **JVM tests.**
  - App UI tests are Robolectric (SDK 34, `graphicsMode=NATIVE`, a 900×900 dp window):
    `@RunWith(AndroidJUnit4::class)` with `@get:Rule val rule = createComposeRule()`.
  - The helpers in `JT/ui/TestSupport.kt` are:
    - `rule.show(size) { }` (it wraps content in `ChordifyTheme` and passes **tight** constraints of
      the whole size; wrap a component in `Box(Modifier.fillMaxSize()) { }` to give it its natural
      size);
    - `LANDSCAPE` (800×360 dp) and `PORTRAIT` (360×760 dp);
    - `Fixtures.loop` (C G Am F in C major, 8 s, 117.5 BPM, 5 bars, beats present),
      `Fixtures.sevenths` (Cmaj7 Am7 Dm7 G7 then N.C.) and `Fixtures.waltz`;
    - `rule.assertEveryControlNamed()` and `rule.assertAnnounced()`.
  - Run one class with
    `./gradlew --max-workers=4 :app:testDebugUnitTest --tests '<fqcn>'`. Engine tests use
    `:engine:testDebugUnitTest`.
- **Style.**
  - Kotlin with Compose Material 3. Follow the surrounding code: KDoc on public declarations, plain
    English UI text.
  - Colours come from `MaterialTheme.colorScheme` or `LocalChordifyColors.current` (`APP/ui/Theme.kt`).
  - Add no new dependencies.
- **Commits.**
  - Plain imperative messages ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`,
    e.g. `git commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.
  - Never push. Never touch the repository's `.gitignore` or `chordify_backend/src/`.
- **Scope.** Edit only the files your task lists. A helper you need goes, `private`, in one of
  your own files.

## 3. Shared contracts

Every name below exists as real code after wave 0. T0 writes the non-UI contracts and T1 the UI
signatures. Wave-1 tasks implement or call exactly these and must not change them.

### C1. Engine: the edited flag and `rebuild` (T0 writes; E1 implements)

In `ENG/model/AnalysisResult.kt`, `ChordSegment` gains a last field:

```kotlin
@OptIn(ExperimentalSerializationApi::class)
@EncodeDefault(EncodeDefault.Mode.NEVER) val edited: Boolean = false,
```

`NEVER` keeps unedited JSON byte-identical to today's even though `ServerJson` has
`encodeDefaults = true`.

The new file `ENG/analysis/Rebuild.kt`:

```kotlin
/** One chord of an edited analysis: [label] is a Harte label as it sounds ("E:min", "C:maj/3", "N"). */
@Serializable
data class ChordSpan(val start: Double, val end: Double, val label: String, val edited: Boolean = false)

/** The analysis rebuilt from [chords] without audio (spec §3.2). [chords] must be normalised
 *  (see C3: sorted, touching, covering [0, result.source.duration]). Returns exactly one
 *  ChordSegment per span, in order, with index = position. */
fun rebuild(result: AnalysisResult, chords: List<ChordSpan>, models: Models): AnalysisResult
```

The semantics E1 implements:
- **Kept fields.** `key`, `tempo`, `beats`, `downbeats`, `sections`, `source`, `models`,
  `schemaVersion`, `analysisId` and `status` stay unchanged.
- **Per segment.** `display = displayName(label, key.flats)`, `root` and `bass` spelled in the key,
  `beats` counted as `analyseMeasured` counts them, and `roman`, `romanTriad`, `function` and
  `scaleHint` from `describe(label, key)`. The key is
  `Key(noteToPc(result.key.global.tonic), result.key.global.mode)`.
- **Matching spans.** A span with `!edited` whose label equals a segment of `result.chords` and
  whose start and end equal it within 1e-3 keeps that segment's `confidence`, `alternatives` and
  `quality`.
- **Other spans.** Every other span gets `confidence = 1.0`, no alternatives, and
  `quality = LARGE.encode(label)?.let(LARGE::qualityOf)`, or null for N.
- **Predictions.** `next`, `theoryOnly`, `surprise` and `summary.predictability` are recomputed as
  `analyseMeasured` does, but only if `result.chords.any { it.next.isNotEmpty() }`. Otherwise they
  stay empty or null.
- **Derived fields.** `bars` comes from
  `bars(result.downbeats.toDoubleArray(), result.source.duration, chords)` (empty if there are no
  downbeats), and `summary.uniqueChords`, `timeShare` and `patterns` are computed as in
  `analyseMeasured`.
- **The flag.** `edited` is copied from the span.

### C2. App: the edit model (T0, real code, new file `APP/edit/Edits.kt`, package `com.arbaba.chordifymobile.edit`)

```kotlin
/** What results/<id>.edits.json holds (spec §3.1); shapes map a shape label (C6) to six frets E..e, -1 muted, 0 open. */
@Serializable
data class ChordEdits(val version: Int = 1, val chords: List<ChordSpan>, val shapes: Map<String, List<Int>> = emptyMap())

fun encodeEdits(edits: ChordEdits): String            // pretty-free JSON via Json { encodeDefaults = true }
fun decodeEdits(text: String): ChordEdits              // throws IllegalArgumentException on any damage (bad JSON, version != 1, a shape not 6 ints in -1..24)

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
    fun startEditing(); fun cancelEditing(); fun done(); fun restore()
    fun select(index: Int?)
    fun moveEdge(edge: Int, time: Double)                 // edge k = boundary between spans k-1 and k, 1 <= k < size
    fun setStart(index: Int, time: Double); fun setEnd(index: Int, time: Double)
    fun split(index: Int, time: Double); fun mergeWithNext(index: Int)
    fun rename(index: Int, soundingLabel: String)
    fun setShape(shapeLabel: String, shape: List<Int>?)   // null = back to the app's default
    fun undo(); fun redo(); fun toggleLoop()
    suspend fun suggestions(index: Int): List<String>     // up to 6 sounding Harte labels, not the current one
}
object NoEditActions : EditActions { /* every method does nothing; suggestions returns emptyList() */ }

const val EDITS_UNREADABLE = "Your edits for this song could not be loaded."
const val SAVE_FAILED = "Could not save your changes: "                 // + the exception message
const val RESTORE_CONFIRM = "Your chords and shapes for this song will be replaced by the model's. This cannot be undone."
const val DISCARD_CONFIRM = "Discard your changes?"
```

### C3. App: the edit operations (T0, real code with JVM tests, new file `APP/edit/EditOps.kt`)

All are pure. Every function returns a **normalised** list: sorted, touching, the first start 0,
the last end `duration`, and no span shorter than `MIN_SPAN_SECONDS`. Neighbours with the same
label are **allowed**: a split's two halves stay separate until one is renamed or they are
merged. Equal neighbours merge only in the two places listed below (decided 2026-11-22).

```kotlin
const val MIN_SPAN_SECONDS = 0.05
fun spansOf(result: AnalysisResult): List<ChordSpan>                         // one span per chord, label/start/end/edited copied
fun normalise(spans: List<ChordSpan>, duration: Double): List<ChordSpan>
fun moveEdge(spans: List<ChordSpan>, edge: Int, time: Double, duration: Double): List<ChordSpan>
fun setStart(spans: List<ChordSpan>, index: Int, time: Double, duration: Double): List<ChordSpan>  // = moveEdge(index); index 0 unchanged
fun setEnd(spans: List<ChordSpan>, index: Int, time: Double, duration: Double): List<ChordSpan>    // = moveEdge(index + 1); last unchanged
fun split(spans: List<ChordSpan>, index: Int, time: Double, duration: Double): List<ChordSpan>
fun mergeWithNext(spans: List<ChordSpan>, index: Int, duration: Double): List<ChordSpan>
fun rename(spans: List<ChordSpan>, index: Int, label: String, duration: Double): List<ChordSpan>
fun snapToBeat(time: Double, beats: List<Double>, tolerance: Double): Double  // the nearest beat if within tolerance, else time
fun stepToBeat(time: Double, beats: List<Double>, delta: Int): Double        // the previous (-1) or next (+1) beat strictly before/after time; ±0.5 s if no beats
fun soundingLabel(shapeLabel: String, capo: Int, flats: Boolean): String   // transposeLabel(shapeLabel, +capo, flats)
fun shapeLabelOf(soundingLabel: String, capo: Int, flats: Boolean): String // transposeLabel(soundingLabel, -capo, flats); "N" stays "N"
```

The exact rules:
- **`normalise`.**
  - Sort by start and clamp every time into [0, duration].
  - Fill a gap with an `"N"` span (`edited = true`) and resolve an overlap by moving the later
    start to the earlier end.
  - Give the time of a span shorter than `MIN_SPAN_SECONDS` to the previous span, or to the next
    one if it is first.
  - It does **not** merge equal-label neighbours.
- **`moveEdge`.** Clamp `time` into [spans[edge-1].start, spans[edge].end], set
  spans[edge-1].end = spans[edge].start = time, mark both `edited = true`, then normalise. A span
  collapsed to zero length disappears, which is how "dragging an edge onto the next edge removes
  the chord" works. If that removal leaves two spans with the same label touching, they merge into
  one (`edited = true`). Moving an edge between two equal-label spans, such as a split's halves,
  keeps both.
- **`split`.** A no-op unless start + MIN < time < end − MIN. Both halves keep the label and get
  `edited = true`.
- **`mergeWithNext`.** The first span absorbs the next, keeps its own label, and gets
  `edited = true`. A no-op on the last span.
- **`rename`.** Sets the label, `edited = true`, normalises, and then merges the renamed span
  with a neighbour of the same label on either side.

### C4. App: storage (T0 stubs; S1 implements; in `APP/data/Library.kt`)

```kotlin
class Library(
    context: Context, dao: AnalysisDao, jobs: JobScheduler, fingerprint: String,
    clock: () -> Long = System::currentTimeMillis,
    private val rebuild: suspend (AnalysisResult, List<ChordSpan>) -> AnalysisResult = { result, _ -> result },
)
fun editsFile(id: String): File                                  // File(resultsDir, "$id.edits.json")
suspend fun edits(id: String): EditsLoad                         // None if no file; Damaged if decodeEdits throws
suspend fun saveEdits(id: String, edits: ChordEdits)             // atomic: "$id.edits.json.part" then rename; throws IOException
suspend fun clearEdits(id: String)                               // deletes the edits file
internal suspend fun importAnalysisText(text: String, fallbackName: () -> String): String  // importAnalysis(uri) reads text and calls this; fallbackName is called only when the export names no title/filename
```

`delete(id)` also deletes the edits file.

`APP/edit/Rebuilding.kt` (T0, real code):

```kotlin
suspend fun rebuildWith(engine: EngineHolder, result: AnalysisResult, spans: List<ChordSpan>): AnalysisResult =
    withContext(Dispatchers.Default) { rebuild(result, spans, engine.concurrent().models) }
```

### C5. App: export (T0 stubs; X1 implements; in `APP/data/Export.kt`)

```kotlin
data class ExportEdits(val originalChords: List<ChordSegment>, val shapes: Map<String, List<Int>>)
data class ParsedExport(val result: AnalysisResult, val edits: ExportEdits?)
const val EDITS_DAMAGED = "This export's edits are damaged."
fun jsonText(result: AnalysisResult, edits: ExportEdits? = null): String   // no edits: exactly result.toJson(), as today
fun readExport(text: String): ParsedExport                                  // throws IllegalArgumentException(NOT_AN_EXPORT or EDITS_DAMAGED)
fun parseExport(text: String): AnalysisResult = readExport(text).result    // kept for existing callers
fun exportFile(context: Context, displayName: String, result: AnalysisResult, format: ExportFormat, edits: ExportEdits? = null): File
```

The export JSON is the result's JSON with one extra top-level key (only when `edits != null`):

```json
"edits": { "version": 1, "original_chords": [ <ChordSegment JSON as stored> ... ], "shapes": { "G:maj": [3, -1, 0, 0, 3, 3] } }
```

`readExport` removes `edits` before decoding the result, because `ServerJson` rejects unknown keys.
It refuses with `EDITS_DAMAGED` when any of these holds:
- `edits` is not an object, or `version` is not 1;
- `original_chords` is missing, empty or undecodable;
- either chord list (`result.chords` or `original_chords`) does not cover [0, duration]: the first
  start must be 0, each end must equal the next start, and the last end must equal duration, all
  within 1e-3;
- a shape is not 6 ints in −1..24.

### C6. Labels, capo and shapes

- **Labels.** Labels are stored as they **sound**. With a capo on, the screen shows
  `withCapo(result, capo)` (`APP/music/Capo.kt`, which transposes labels by −capo).
- **What the editors show.** The picker and the shape editor show those shown names.
- **Converting a pick.** A picked label is converted back with `soundingLabel(shownLabel, capo, flats)`,
  where `flats = shapeKey(key.tonic, key.mode, capo).flats` (`APP/music/Capo.kt`).
- **Shape keys.** Custom shapes are keyed by the **shown** chord's Harte label, the "shape label",
  e.g. `G:maj`.
- **Which shape the stage draws.** `shapes[shown.label] ?: guitarShape(shown)` (`APP/music/Music.kt`).

### C7. UI component signatures (T1 writes; each wave-1 task implements its own)

All live in package `com.arbaba.chordifymobile.ui.analysis`. New parameters are appended after the
existing ones, with defaults, so today's call sites still compile.

```kotlin
// AnalysisTopBar.kt (U6)
fun AnalysisTopBar(/* existing params */ ..., modifier: Modifier = Modifier,
    editing: Boolean = false, canUndo: Boolean = false, canRedo: Boolean = false, hasEdits: Boolean = false,
    onEdit: () -> Unit = {}, onUndo: () -> Unit = {}, onRedo: () -> Unit = {},
    onCancelEdit: () -> Unit = {}, onDone: () -> Unit = {}, onRestore: () -> Unit = {})
// ChordStrip.kt (U1): onMoveEdge is called ONCE per finished drag, with the snapped time
fun ChordStrip(/* existing params */ ..., modifier: Modifier = Modifier,
    editing: Boolean = false, selected: Int? = null, onSelect: (Int) -> Unit = {},
    onMoveEdge: (edge: Int, time: Double) -> Unit = { _, _ -> })
// Stage.kt (U5)
fun Stage(/* existing params */ ..., modifier: Modifier = Modifier, shape: List<Int>? = guitarShape(chord))
// TransportBar.kt (U6): looping == null hides the Loop button (not editing)
fun TransportBar(/* existing params */ ..., modifier: Modifier = Modifier, looping: Boolean? = null, onLoop: () -> Unit = {})
// EditPanel.kt (new, U2): chord is the SHOWN (capo) segment; times are seconds
@Composable fun EditPanel(chord: ChordSegment, index: Int, count: Int, notation: Notation, beats: List<Double>,
    time: () -> Double, onSetStart: (Double) -> Unit, onSetEnd: (Double) -> Unit, onSplit: () -> Unit,
    onMergeWithNext: () -> Unit, onChangeChord: () -> Unit, onEditShape: () -> Unit, modifier: Modifier = Modifier)
// ChordPicker.kt (new, U3): current and suggestions are SHOWN labels; onPick gets a SHOWN Harte label
@Composable fun ChordPicker(current: String, suggestions: List<String>, key: KeyInfo, flats: Boolean,
    onPick: (String) -> Unit, onCancel: () -> Unit)
// ShapeEditor.kt (new, U4): label is the shape label; onSave(null) = back to the default shape
@Composable fun ShapeEditor(label: String, shape: List<Int>?, defaultShape: List<Int>?, capo: Int,
    onSave: (List<Int>?) -> Unit, onCancel: () -> Unit)
```

`ChordPicker` and `ShapeEditor` are full-screen dialogs:
`Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false))`.

### C8. View model API (T0 stubs; V1 implements; `APP/ui/analysis/AnalysisViewModel.kt`)

`AnalysisViewModel` implements `EditActions` and adds the following. The existing `result` now
means **the shown result**: the rebuilt one when edits are saved, otherwise the model's.

```kotlin
val original: StateFlow<AnalysisResult?>          // the model's result
val hasEdits: StateFlow<Boolean>
val savedShapes: StateFlow<Map<String, List<Int>>>
val editsDamaged: StateFlow<Boolean>
val edit: StateFlow<EditUiState?>                 // null when not editing
```

### C9. Test tags and texts (implement exactly)

| Where | Tag / text |
|---|---|
| Top bar, not editing | ✎ button tag `edit`, contentDescription `Edit chords`. ⋮ menu item `Restore the model's chords`, shown only when `hasEdits`. |
| Top bar, editing | label `✎ Editing`; Undo tag `undo` (contentDescription `Undo`) and Redo tag `redo` (`Redo`), each disabled when it can't act; Cancel tag `cancelEdit` (text `Cancel`); Done tag `done` (text `Done`). The capo chip and ⋮ are hidden. Back calls `onCancelEdit`. |
| Strip, editing | about 110 dp tall; a tap selects (no seek); the selected block has a white outline and handles; an edited block's name ends with ` ✎` |
| Edit panel | root tag `editPanel`; times tags `editStart` and `editEnd` (text `m:ss.cc`, e.g. `0:42.10`); buttons with contentDescriptions `Start one beat earlier`, `Start one beat later`, `End one beat earlier`, `End one beat later`; texts `Set start to here`, `Set end to here`, `Split here`, `Merge with next`, `Change chord…`, `Edit shape…`; labels `Starts` and `Ends` |
| Chord picker | root tag `chordPicker`; heading `Suggested`; root buttons `C C♯ D E♭ E F F♯ G A♭ A B♭ B`; type buttons `major minor 7 maj7 m7 6 m6 sus2 sus4 dim aug 9 add9`; bass row starting `none`, then the twelve root names; `No chord (N.C.)`; confirm button text `Use ` + display name (e.g. `Use Em`) with tag `pickerUse`; `Cancel`; preview tag `pickerPreview` (display name, then numeral · function) |
| Shape editor | root tag `shapeEditor`; cells contentDescription `String E, fret 3` (strings `E A D G B e`); nut toggles contentDescription `String A: open` / `String A: muted`; `Higher up the neck`, `Lower down the neck`; texts `Default shape`, `Save`, `Cancel`, `Strum it`; warning tag `shapeWarning`; starting fret text `Starting fret: N` |
| Transport, editing | tag `loop`, text `Loop chord`, `selected` semantics = looping |
| Analysis screen | damaged banner tag `editsDamaged` (text `EDITS_UNREADABLE`); save error tag `saveError` (text `SAVE_FAILED + message`); confirm dialogs show `RESTORE_CONFIRM` (buttons `Restore` and `Keep my chords`) and `DISCARD_CONFIRM` (buttons `Discard` and `Keep editing`) |
| Lead sheet | an edited chord's name in a bar cell ends with ` ✎` |

### C10. Picker labels

Types map to lead-sheet suffixes, and the label is built with the engine's
`parseDisplay(root + suffix + (if (bass) "/" + bass else ""))` (`ENG/vocab/Display.kt`):

major `""`, minor `"m"`, 7 `"7"`, maj7 `"maj7"`, m7 `"m7"`, 6 `"6"`, m6 `"m6"`, sus2 `"sus2"`,
sus4 `"sus4"`, dim `"dim"`, aug `"aug"`, 9 `"9"`, add9 `"add9"`.

Roots are written in ASCII for `parseDisplay`: `C C# D Eb E F F# G Ab A Bb B`. A bass equal to the
root means "none". `No chord` is `"N"`.

## 4. Task list

### Wave 0

#### T0: Engine and app contracts, and the pure edit operations

- **Goal:** create every non-UI shared contract (C1 stub, C2, C3 implemented, C4 stub, C5 stub,
  C8 stub) so that wave 1 can work in parallel.
- **Context:**
  - Today's model is `ENG/model/AnalysisResult.kt` (`ChordSegment`, `AnalysisResult`, and
    `ServerJson = Json { encodeDefaults = true }`).
  - Engine helpers `bars` and `analyseMeasured` are in `ENG/analysis/Analysis.kt`.
  - `transposeLabel(label, semitones, flats)` is in `APP/music/Capo.kt`.
  - Today's `Library` is `APP/data/Library.kt` (constructor
    `Library(context, dao, jobs, fingerprint, clock)`; result files `results/<id>.json`, written
    atomically in `reportCompleted`; `importAnalysis(uri)` reads the text and calls `parseExport`).
  - Today's export code is `APP/data/Export.kt`.
  - `APP/ui/analysis/AnalysisViewModel.kt` holds `result: MutableStateFlow<AnalysisResult?>`.
  - `EngineHolder` is in `APP/work/AnalysisWorker.kt`; `concurrent()` returns the `ChordifyEngine`,
    whose `models` is public.
- **Files:**
  - Modify: `ENG/model/AnalysisResult.kt`, `APP/data/Library.kt` (stubs only),
    `APP/data/Export.kt` (stubs only), `APP/ui/analysis/AnalysisViewModel.kt` (stubs only).
  - Create: `ENG/analysis/Rebuild.kt`, `APP/edit/Edits.kt`, `APP/edit/EditOps.kt`,
    `APP/edit/Rebuilding.kt`, `JT/edit/EditOpsTest.kt`, `JT/edit/EditsTest.kt`.
- **Contracts provided:** C1 (`edited` field real; `rebuild` body `TODO("E1")`), C2, C3, C4 (stubs:
  `edits()` returns `EditsLoad.None`, `saveEdits`/`clearEdits` do nothing, `editsFile` real, the
  constructor parameter real, `importAnalysisText` = today's import logic moved out of
  `importAnalysis`), C5 (stubs: `readExport` = `ParsedExport(parseExportOld(text), null)`, where
  `parseExportOld` is today's `parseExport` body; `jsonText` ignores `edits`; `exportFile` takes
  `edits` and ignores it), C8 (stub flows holding the defaults; every `EditActions` method a no-op;
  `suggestions` returns empty).
- **Steps:**
  - [ ] Write `EditOpsTest` first: one test per rule of C3, including:
    - a collapse removes the span;
    - an edge clamps to its neighbours;
    - a gap is filled with an edited `"N"`;
    - a <50 ms span gives its time to the previous span (or the next, if first);
    - a rename to a neighbour's label merges;
    - a split near an edge is a no-op;
    - merging the last span is a no-op;
    - `snapToBeat` inside and outside the tolerance;
    - `stepToBeat` both ways and with no beats;
    - `soundingLabel`/`shapeLabelOf` with capo 2 (`G:maj` ↔ `A:maj`, flats false) and `"N"`;
    - `spansOf(Fixtures.loop)` matches its chords.

    Write `EditsTest`: an encode/decode round trip, and decode throwing on bad JSON, version 2 and
    a 5-fret shape.
  - [ ] Implement C1's field and file, C2 and C3. Run the tests to green.
  - [ ] Add the C4, C5 and C8 stubs exactly as written. `Rebuilding.kt` is real.
  - [ ] Run the engine `AnalysisResultJsonTest`, which proves unedited JSON is unchanged.
- **Done when:**
  - `./gradlew --max-workers=4 :engine:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
    passes.
  - Commit: "Add the M8 contracts: the edited flag, the edit model and its operations".
- **Depends on:** none.
- **Size:** M (four short stubs plus two real files and their tests).

#### T1: UI component signatures

- **Goal:** add C7's parameters and the three new composables as stubs (bodies `{}` for the new
  files; existing bodies unchanged apart from accepting the parameters), so the UI slices can
  work in parallel.
- **Context:**
  - Today's signatures:
    - `Stage(chord, notation, capo, sounding, onSwipe, modifier)` in `Stage.kt`;
    - `ChordStrip(result, time, current, notation, peaks, surprising, onSeek, modifier)`;
    - `TransportBar(result, time, playing, rate, visibleSeconds, onToggle, onSeek, onRate, modifier)`;
    - `AnalysisTopBar(title, analysed, capo, suggestion, notation, onBack, onCapo, onNotation, onExport, onPickFile, modifier)`.

    All are in `APP/ui/analysis/`. `guitarShape` is in `APP/music/Music.kt`, and `KeyInfo` in the
    engine model.
  - In `Stage.kt`, replace the body's `guitarShape(chord)` with the new `shape` parameter, which is
    behaviour-neutral.
- **Files:**
  - Modify: `APP/ui/analysis/AnalysisTopBar.kt`, `ChordStrip.kt`, `Stage.kt`, `TransportBar.kt`.
  - Create: `APP/ui/analysis/EditPanel.kt`, `ChordPicker.kt`, `ShapeEditor.kt`.
- **Contracts provided:** C7.
- **Steps:** add the parameters, create the stubs with C7's KDoc, and compile.
- **Done when:**
  - `./gradlew --max-workers=4 :app:compileDebugKotlin :app:testDebugUnitTest` passes, with
    behaviour unchanged.
  - Commit: "Add the M8 UI component signatures".
- **Depends on:** none.
- **Size:** S.

### Wave 1 (all parallel; each starts from the base after wave 0 is merged)

#### E1: Engine `rebuild`

- **Goal:** implement C1's `rebuild` by extracting the audio-free part of `analyseMeasured`, so
  both share one implementation.
- **Context:**
  - `analyseMeasured` (`ENG/analysis/Analysis.kt`) builds segments with `chordEntry` (display,
    spelled root and bass, quality, `describe`), then runs the prediction loop (`predictNext`,
    surprise from `models.lm.distribution`, predictability), then downbeats, `bars(...)` and the
    summary (time share, `findPatterns` on `romanTriad`).
  - Python parity is guarded by `ET/analysis/AnalysisGoldenTest.kt`, which must still pass. It
    shows how to run `analyseMeasured(JVM_MODELS, input.measurements(), options, input.sourceInfo(id))`
    on the goldens (`ET/analysis/JvmModels.kt` loads the models).
  - Chords outside the target vocabulary: `target.encode(label)!!` would crash, hence C1's rule
    `LARGE.encode(label)?.let(LARGE::qualityOf)` for non-matching spans.
- **Files:**
  - Modify: `ENG/analysis/Analysis.kt`, `ENG/analysis/Rebuild.kt`.
  - Create: `ET/analysis/RebuildTest.kt`.
- **Contracts:** implements C1.
- **Steps:**
  - [ ] Write `RebuildTest`:
    - (a) for every golden analysed by `AnalysisGoldenTest`, `rebuild(result, spans(result), JVM_MODELS)`
      equals `result`, with numbers within 1e-6 and `edited` false;
    - (b) renaming chord 1 of the loop golden to `E:min` changes its display, roman and function,
      `bars`, `timeShare` and the neighbours' `next`, and sets confidence 1.0, empty alternatives
      and `edited` true;
    - (c) `C:maj(9)` in a result made with `AnalysisOptions(vocabulary = "majmin")` gets a roman
      numeral and a non-null quality;
    - (d) a result made with `predictions = false` keeps empty `next`;
    - (e) one segment per span, with indices 0..n−1.
  - [ ] Extract private helpers `chordSegmentFor(...)`, `withPredictions(...)` and `summaryOf(...)`
    used by both functions. Implement `rebuild`.
- **Done when:**
  - `./gradlew --max-workers=4 :engine:testDebugUnitTest` passes, including `AnalysisGoldenTest`
    and `RebuildTest`.
  - Commit: "Rebuild an analysis from edited chords without audio".
- **Depends on:** T0.
- **Size:** M.

#### X1: Export and import format

- **Goal:** implement C5. Edited exports carry the rebuilt result plus `edits`, and imports read
  them back.
- **Context:**
  - `APP/data/Export.kt`: `labText`, `jsonText`, `NOT_AN_EXPORT`, `parseExport`
    (status `completed` and non-empty chords required) and `exportFile`.
  - `ServerJson` (engine) has `encodeDefaults = true` and rejects unknown keys.
  - Use `kotlinx.serialization.json` `JsonObject` to add or remove the `edits` key, and
    `ServerJson.encodeToJsonElement(ChordSegment.serializer(), …)` for `original_chords`.
- **Files:** modify `APP/data/Export.kt` and `JT/data/ExportTest.kt` (existing, extend it).
- **Contracts:** implements C5. Uses C1 and C2.
- **Steps:**
  - [ ] Tests:
    - `jsonText(loop)` equals `loop.toJson()` byte for byte;
    - with edits, the text has an `edits` object with `version` 1, `original_chords` and `shapes`;
    - `readExport` round-trips it into an equal `ExportEdits`;
    - `readExport` of an old export gives `edits == null`;
    - `parseExport` still returns the result;
    - each `EDITS_DAMAGED` case of C5 is refused (edits a string; version 2; empty
      original_chords; a gap in original_chords; a gap in result.chords; a 7-int shape);
    - non-JSON gives `NOT_AN_EXPORT`;
    - `labText` of a result with an edited chord lists the edited label.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.data.ExportTest'`
    passes.
  - Commit: "Carry chord edits in the analysis export".
- **Depends on:** T0.
- **Size:** S.

#### S1: Storing edits, importing edited exports, wiring `rebuild`

- **Goal:** implement C4, and give the app's `Library` the real rebuild.
- **Context:**
  - `APP/data/Library.kt`: results under `filesDir/results`; `reportCompleted` shows the atomic
    write pattern; `importAnalysis(uri)` writes the parsed result as a new completed row with
    `modelFingerprint = IMPORTED` and key/bpm from the result; `delete(id)` removes the row and its
    files.
  - `APP/ChordifyApp.kt` `AppContainer` builds `Library(app, database.analyses(), WorkJobScheduler(app), fingerprint)`
    and has `engine: EngineHolder`.
  - `readExport` and `ExportEdits` are C5; they are stubs until X1 merges. In your tests, build the
    import text by hand (the result JSON plus an `edits` object, as C5 shows).
- **Files:**
  - Modify: `APP/data/Library.kt`, `APP/ChordifyApp.kt`.
  - Create: `JT/data/LibraryEditsTest.kt`.
- **Contracts:** implements C4. Uses C2, C5 and `rebuildWith` (C4).
- **Steps:**
  - [ ] Write `LibraryEditsTest` (Robolectric):
    - a `Library` over `Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()`,
      a no-op `JobScheduler`, and a fake `rebuild` that replaces `chords` with one segment per span;
    - `saveEdits` then `edits` gives `Loaded`; garbage in the file gives `Damaged`; no file gives
      `None`;
    - `clearEdits` and `delete` remove the file;
    - no `.part` file is left after `saveEdits`;
    - `importAnalysisText` with an `edits` block stores `<id>.json` = `rebuild(result, original spans)`
      and an edits file whose chords are the export's chords (with their `edited` flags) and its
      shapes;
    - without `edits`, the import is unchanged.

    Note: with `readExport` still the T0 stub in your worktree, the with-edits case cannot pass
    yet. Mark it `@Ignore("needs X1's readExport")` and say so in your report; I1 removes the
    `@Ignore`.
  - [ ] Implement C4, using `readExport` in `importAnalysisText`.
  - [ ] Wire `AppContainer`:
    `Library(..., rebuild = { result, spans -> rebuildWith(engine, result, spans) })`.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.data.LibraryEditsTest'`
    passes, with the one ignore noted.
  - Commit: "Keep chord edits beside the analysis, and import edited exports".
- **Depends on:** T0.
- **Size:** M.

#### V1: The editing state machine and the view model

- **Goal:** implement C8. `AnalysisViewModel` loads, edits, saves, restores and exports, delegating
  to a pure, tested `EditController`.
- **Context:**
  - `APP/ui/analysis/AnalysisViewModel.kt` today loads `library.result(id)` once the row is
    COMPLETED, builds `PlaybackClock(duration)`, and exports with
    `exportFile(app, row.displayName, result, format)`.
  - `PlaybackClock` (`APP/playback/PlaybackClock.kt`) has `time: StateFlow<Double>`,
    `seek(seconds)` and `playing`.
  - The engine's `nextChords(chords: List<String>, key: String?, k: Int)` accepts Harte labels.
    Call it via `container.engine.concurrent()` on `Dispatchers.Default`, as
    `APP/songwriter/SongwriterScreen.kt` does.
- **Files:**
  - Create: `APP/edit/EditController.kt`, `JT/edit/EditControllerTest.kt`.
  - Modify: `APP/ui/analysis/AnalysisViewModel.kt`.
- **Contracts:** implements C8 and `EditActions` (C2). Uses C3, C4 (`edits`, `saveEdits`,
  `clearEdits`), C5 (`exportFile` with `ExportEdits`) and `rebuildWith`.
- **Design (decided):**
  - **The controller.**
    `EditController(duration: Double, original: AnalysisResult, rebuild: suspend (List<ChordSpan>) -> AnalysisResult, save: suspend (ChordEdits) -> Unit, clear: suspend () -> Unit)`
    holds an undo stack of `ChordEdits` (unbounded, cleared on Done or Cancel) and exposes
    `state: StateFlow<EditUiState?>` plus `start(from: ChordEdits)`, every `EditActions` editing op,
    `done()` and `restore()`.
  - **After each op:** push to the undo stack, clear the redo stack, rebuild the preview, set
    `dirty = true`, and keep the selection on the same index (clamped).
  - **Done.**
    - If no span is edited and there are no shapes, it calls `clear()`; otherwise `save(edits)`.
    - On `IOException` the state stays editing, with `saveError = SAVE_FAILED + message`.
    - On success the state becomes null.
  - **Shapes.** `setShape(label, null)` removes the key.
  - **Loop.** In the view model: while `looping` and a chord is selected, collect `clock.time`; when
    `time >= span.end` or `< span.start`, call `clock.seek(span.start)`.
  - **Suggestions.**
    - The selected preview chord's `alternatives` labels come first.
    - Then the labels of `nextChords(previous real chords' labels up to index, "${key.tonic} ${key.mode}", 6).predictions`.
    - Remove duplicates and the current label, and take 6.
  - **Loading.**
    - `Loaded`: `result = rebuildWith(original, edits.chords)`, `savedShapes = edits.shapes`,
      `hasEdits = true`.
    - `Damaged`: `editsDamaged = true`, and `result` stays the original.
  - **Export.** When `hasEdits`, pass
    `ExportEdits(original.chords, savedShapes)` with the shown result; otherwise as today.
- **Steps:**
  - [ ] Write `EditControllerTest`, with a fake rebuild that copies spans into chords, fake
    save/clear, and `Fixtures.loop`. Cover:
    - start, then each op updates spans, preview, dirty and canUndo;
    - undo/redo;
    - Done calls save (or clear when nothing is edited);
    - Done with a throwing save keeps editing with saveError;
    - Cancel discards;
    - Restore calls clear;
    - selection clamps after a merge.
  - [ ] Implement `EditController`, then wire the view model.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.edit.EditControllerTest'`
    passes and `:app:compileDebugKotlin` passes.
  - Commit: "Edit, save, restore and export chord corrections in the analysis view model".
- **Depends on:** T0.
- **Size:** M.

#### U1: Editing on the chord strip

- **Goal:** in editing mode the strip is taller. A tap selects a block, the selected block shows
  handles, dragging a handle moves its edge (snapped), and edited blocks show ` ✎`.
- **Context:**
  - `APP/ui/analysis/ChordStrip.kt` draws blocks at `PIXELS_PER_SECOND` dp/s under a playhead at
    `PLAYHEAD`, moved by `graphicsLayer`. A tap seeks via `detailSeek`; a drag seeks.
  - `snapToBeat` and `MIN_SPAN_SECONDS` come from C3.
  - Snap tolerance = 6 dp in seconds = `6 / PIXELS_PER_SECOND`.
  - Tags and marks are in C9.
- **Files:** modify `APP/ui/analysis/ChordStrip.kt` and `JT/ui/analysis/ChordStripTest.kt` (existing).
- **Contracts:** implements C7 `ChordStrip`. Uses C3.
- **Steps:**
  - [ ] Tests:
    - `editing = true` gives a 110 dp strip (wrap it in `Box(Modifier.fillMaxSize())`);
    - a tap on block 1 calls `onSelect(1)` and not `onSeek`;
    - with `selected = 1`, dragging its right handle 70 dp to the right calls `onMoveEdge(2, ≈end+1.0)`
      once, at the end of the drag;
    - a time near a beat snaps to it;
    - not editing behaves as before (the existing tests still pass);
    - an edited chord's name text ends with ` ✎`, asserted through a pure helper
      `stripName(chord, notation)`.
  - [ ] Implement. During a drag, draw the edge at the finger's position (local state) and call
    `onMoveEdge` on release.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.ChordStripTest'`
    passes.
  - Commit: "Select chords and drag their edges on the strip".
- **Depends on:** T1, plus T0 for C3.
- **Size:** M.

#### U2: Edit panel

- **Goal:** implement C7 `EditPanel`, the side panel shown instead of the tabs while editing.
- **Context:**
  - The panel lists the chord's name (`chordName(chord, notation)` from `APP/music/Music.kt`) with
    `theoryLine(chord, notation)` (`APP/ui/analysis/AnalysisText.kt`).
  - **Starts** and **Ends** are `m:ss.cc` times with −beat/+beat, which call
    `onSetStart(stepToBeat(chord.start, beats, ∓1))` and the same for the end.
  - Set start/end to here: `onSetStart(time())`.
  - Then Split here, Merge with next (disabled when `index == count - 1`), Change chord… and
    Edit shape….
  - The first chord's start and the last chord's end are fixed: their controls are disabled.
  - Plain sections, scrolling vertically, using `SectionTitle` (`APP/ui/Common.kt`). Tags are in C9.
- **Files:** create `JT/ui/analysis/EditPanelTest.kt`; modify `APP/ui/analysis/EditPanel.kt`.
- **Contracts:** implements C7 `EditPanel`. Uses C3 `stepToBeat`.
- **Steps:**
  - [ ] Tests on `Fixtures.loop` chord 1:
    - the time texts read `0:02.04`-style values from the chord;
    - each button calls its callback with the right value;
    - the first chord's start controls and the last chord's Merge are disabled;
    - `assertEveryControlNamed()` passes in LANDSCAPE and PORTRAIT.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.EditPanelTest'`
    passes.
  - Commit: "Add the edit panel: times, beats, split, merge".
- **Depends on:** T1, T0.
- **Size:** S.

#### U3: Chord picker

- **Goal:** implement C7 `ChordPicker` (spec §2.4) with the C10 mapping.
- **Context:**
  - The layout: Suggested chips first (display names via `displayName(label, flats)` from
    `ENG/vocab/Display.kt`), then rows for Root, Type and Bass, and `No chord (N.C.)`.
  - A preview panel shows the chosen chord's display name and `numeral · function`, from the
    engine's `describe(label, parseKey("${key.tonic} ${key.mode}"))`
    (`ENG/theory/Theory.kt`, `ENG/theory/Key.kt`) and `FUNCTION_NAMES` (`APP/music/Music.kt`).
  - The initial selection is parsed from `current`: its root and type, or N.
  - The confirm button is `Use <display>`. A pure helper `pickerLabel(root: String, type: String, bass: String?): String`
    implements C10 and is tested.
- **Files:** modify `APP/ui/analysis/ChordPicker.kt`; create `JT/ui/analysis/ChordPickerTest.kt`.
- **Contracts:** implements C7 `ChordPicker` and C10.
- **Steps:**
  - [ ] Tests:
    - `pickerLabel` for every type with root E (minor → `E:min`, add9 → `E:maj(9)`, 6 → `E:maj6`)
      and with bass G (`E:min/b3`);
    - opening with `current = "E:min"` selects E and minor;
    - tapping G and 7 makes the button read `Use G7`, and tapping it calls `onPick("G:7")`;
    - a suggestion chip picks its label;
    - `No chord (N.C.)` picks `"N"`;
    - `assertEveryControlNamed()`.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.ChordPickerTest'`
    passes.
  - Commit: "Pick a chord by root, type and bass".
- **Depends on:** T1.
- **Size:** M.

#### U4: Shape editor and strum

- **Goal:** implement C7 `ShapeEditor` (spec §2.5): a tappable fretboard, a non-blocking
  missing-note check, and a Karplus–Strong strum.
- **Context:**
  - Shapes are `List<Int>` over strings E A D G B e, with −1 muted and 0 open. They are counted from
    the capo, as `GuitarDiagram(shape, capo)` (`APP/ui/analysis/GuitarDiagram.kt`) draws them.
  - **The window.** The fretboard shows four frets from a starting fret 1..12, adjusted with
    higher/lower. The initial starting fret is the shape's lowest fretted note, or 1.
  - **Taps.**
    - Tapping a cell sets that string to that fret, and tapping it again makes the string open.
    - Tapping the nut cycles open ↔ muted.
  - **The check.** Pure `missingNote(shape: List<Int>, label: String): String?`. Chord tones are the
    pitch classes of `parse(label)` (`ENG/vocab/Chord.kt`: `root`, `degrees`, `bass`). Shape notes
    are the open strings' pitch classes `[4, 9, 2, 7, 11, 4]` plus frets. The message reports the
    first missing tone in the order root, 3rd, 5th, 7th, other:
    `"This shape has no B, the 3rd of G"`, using `displayName` for the chord and the key's
    spelling.
  - **The strum.** In a new `APP/edit/Strum.kt`:
    - pure `strumFrequencies(shape, capo)`, with open strings E2 82.41, A2 110.0, D3 146.83,
      G3 196.0, B3 246.94 and E4 329.63 Hz, times `2^((fret+capo)/12)`, skipping muted strings;
    - pure `pluck(frequencies, sampleRate = 44100, seconds = 1.5, gapSeconds = 0.03, seed = 1): ShortArray`
      (Karplus–Strong, strings started low to high, mixed and normalised);
    - `fun strum(shape, capo)`, which plays it through a static `AudioTrack` on a background thread.
- **Files:**
  - Modify: `APP/ui/analysis/ShapeEditor.kt`.
  - Create: `APP/edit/Strum.kt`, `APP/edit/ShapeCheck.kt`, `JT/edit/ShapeCheckTest.kt`,
    `JT/ui/analysis/ShapeEditorTest.kt`.
- **Contracts:** implements C7 `ShapeEditor`.
- **Steps:**
  - [ ] Tests:
    - `missingNote([3,-1,0,0,3,3], "G:maj")` → `"This shape has no B, the 3rd of G"`, and null for
      `[3,2,0,0,0,3]`;
    - `strumFrequencies` of the open E shape with capo 0 and capo 2 (≈ +2 semitones);
    - `pluck` length = 1.5 s × 44100, no sample clips, and the output is deterministic;
    - UI: tapping `String E, fret 3` then Save calls `onSave` with frets [3,…];
    - the nut toggles; higher/lower change `Starting fret`;
    - `Default shape` calls `onSave(null)`;
    - the warning shows for a bad shape;
    - `assertEveryControlNamed()`.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.edit.ShapeCheckTest' --tests 'com.arbaba.chordifymobile.ui.analysis.ShapeEditorTest'`
    passes.
  - Commit: "Edit a chord's guitar shape, check it, and strum it".
- **Depends on:** T1.
- **Size:** M.

#### U5: Stage and lead sheet marks

- **Goal:** the stage draws the given `shape` (C7), and the lead sheet marks edited chords.
- **Context:**
  - `APP/ui/analysis/Stage.kt`: after T1 it already takes `shape`. Use it for the `GuitarDiagram`
    and for its description (`diagramDescription(shape, capo)`), and hide the diagram when `shape`
    is null.
  - The stage shows no "?" badge for an edited chord, because `unsure()` returns null when
    `alternatives` is empty or `confidence ≥ 0.5`, and edited chords have confidence 1.0. Assert it
    in a test.
  - `APP/ui/analysis/Panels.kt` `LeadSheet`: bars hold display strings only. A bar cell's name
    gets ` ✎` when some chord in `result.chords` with `edited = true` and the same `display`
    overlaps that bar by at least 25 % of the bar (the rule `bars()` uses).
- **Files:** modify `APP/ui/analysis/Stage.kt`, `APP/ui/analysis/Panels.kt`,
  `JT/ui/analysis/StageTest.kt` and `JT/ui/analysis/PanelsTest.kt` (existing).
- **Contracts:** implements C7 `Stage`.
- **Steps:**
  - [ ] Tests:
    - a custom shape changes the diagram description;
    - `shape = null` shows no diagram;
    - an edited chord with confidence 1.0 has no `unsure` badge;
    - a lead sheet over a loop with chord 1 marked edited shows `G ✎` in its bar and plain names
      elsewhere.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.StageTest' --tests 'com.arbaba.chordifymobile.ui.analysis.PanelsTest'`
    passes.
  - Commit: "Draw custom shapes on the stage and mark edited chords on the lead sheet".
- **Depends on:** T1.
- **Size:** S.

#### U6: Top bar and transport while editing

- **Goal:** implement C7's top bar and transport parameters, with the C9 tags.
- **Context:**
  - `APP/ui/analysis/AnalysisTopBar.kt` is built on `ScreenTopBar` (`APP/ui/Common.kt`), with the
    capo chip and the ⋮ menu (Roman numerals, two exports, pick the audio file) as actions.
  - **Not editing.** Add the ✎ `IconButton` before the capo chip (`Icons.Filled.Edit` from
    material-icons-core), and add `Restore the model's chords` to the ⋮ menu when `hasEdits`.
  - **Editing.** The title area shows `✎ Editing`, and the actions are Undo
    (`Icons.AutoMirrored.Filled.Undo` if available in core, otherwise the text `Undo`), Redo, Cancel
    and Done. Hide the capo chip and ⋮. Back calls `onCancelEdit`.
  - `APP/ui/analysis/TransportBar.kt`: when `looping != null`, show a `FilterChip` tagged `loop`,
    with text `Loop chord` and `selected = looping`, after the time text.
- **Files:** modify `APP/ui/analysis/AnalysisTopBar.kt`, `APP/ui/analysis/TransportBar.kt`,
  `JT/ui/analysis/AnalysisTopBarTest.kt` and `JT/ui/analysis/TransportBarTest.kt` (existing).
- **Contracts:** implements C7 for these two.
- **Steps:**
  - [ ] Tests:
    - `edit` calls `onEdit`;
    - the Restore item appears only with `hasEdits` and calls `onRestore`;
    - in editing, `undo`, `redo`, `cancelEdit` and `done` call back, and undo/redo are disabled
      when they can't act;
    - Back calls `onCancelEdit`;
    - the capo chip is absent in editing;
    - `loop` is shown only when `looping != null`, its click calls `onLoop`, and it shows selected
      when true;
    - the existing tests still pass;
    - `assertEveryControlNamed()` in both states.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.AnalysisTopBarTest' --tests 'com.arbaba.chordifymobile.ui.analysis.TransportBarTest'`
    passes.
  - Commit: "Add the editing controls to the top bar and Loop chord to the transport".
- **Depends on:** T1.
- **Size:** S.

#### W1: Analysis screen wiring

- **Goal:** wire editing mode into `AnalysisScreen`/`AnalysisContent` against the contracts.
- **Context:**
  - `APP/ui/analysis/AnalysisScreen.kt`:
    - `AnalysisScreen(id, onBack)` collects the view model's flows.
    - `AnalysisContent(title, analysed, clock, notation, capo, audio, mismatch, onNotation, onCapo, onPickFile, peaks, onExport, onBack)`
      lays out the top bar, a stage column (banner, Stage, ChordStrip) beside or above a panel
      (five tabs), and the transport.
    - `result = withCapo(analysed, capo)`.
    - Keys are handled by `onPreviewKeyEvent`.
  - **The new defaulted parameters of `AnalysisContent`:**
    `shapes: Map<String, List<Int>> = emptyMap(), hasEdits: Boolean = false, editsDamaged: Boolean = false, edit: EditUiState? = null, editActions: EditActions = NoEditActions`.
  - **While `edit != null`:**
    - The screen shows `withCapo(edit.preview, capo)` instead of `withCapo(analysed, capo)`, and
      `edit.shapes` instead of `shapes`.
    - The top bar is in editing state (C7).
    - The strip has `editing = true, selected = edit.selected, onSelect = editActions::select, onMoveEdge = editActions::moveEdge`.
    - The panel area shows `EditPanel` for the selected chord, or a hint
      `Tap a chord in the strip to change it.` when none is selected.
    - The transport has `looping = edit.looping, onLoop = editActions::toggleLoop`.
    - Keyboard shortcuts are off.
  - **Change chord…** opens `ChordPicker`:
    - `current = shownLabel`;
    - `suggestions = editActions.suggestions(i)` mapped with `shapeLabelOf(·, capo, flats)`, loaded
      in a `LaunchedEffect`;
    - `onPick = { editActions.rename(i, soundingLabel(it, capo, flats)) }`.
  - **Edit shape…** opens `ShapeEditor`:
    - `label = shown.label`;
    - `shape = shapes[shown.label]`;
    - `defaultShape = guitarShape(shown)`;
    - `onSave = { editActions.setShape(shown.label, it) }`.
  - **Stage shape.** `shapes[shown.label] ?: guitarShape(shown)`.
  - **Cancel.** With `edit.dirty` it shows the `DISCARD_CONFIRM` dialog; otherwise it calls
    `cancelEditing()` directly. System Back while editing behaves as Cancel (`BackHandler`).
  - **Restore** shows the `RESTORE_CONFIRM` dialog.
  - **Banners.** `editsDamaged` shows a banner (tag `editsDamaged`) above the stage, like
    `NoAudioBanner`. `edit.saveError` shows a line tagged `saveError` in the panel area.
  - **The screen's flows.** `AnalysisScreen` passes the view model's `hasEdits`, `savedShapes`,
    `editsDamaged` and `edit`, and the view model itself as `editActions`.
  - `flats` = `shapeKey(analysed.key.global.tonic, analysed.key.global.mode, capo).flats`.
- **Files:** modify `APP/ui/analysis/AnalysisScreen.kt` and `JT/ui/analysis/AnalysisScreenTest.kt`
  (existing).
- **Contracts:** uses C2, C3 (label helpers), C7, C8 and C9.
- **Steps:**
  - [ ] Tests, with a recording fake `EditActions` and an `EditUiState` built from `Fixtures.loop`
    (`preview = loop`, `spans = spansOf(loop)`):
    - `edit != null` shows `editPanel` or the hint, and no tab row;
    - `done` and `undo` reach the fake;
    - Cancel when dirty shows the discard dialog, and `Discard` calls `cancelEditing`;
    - Restore shows its dialog, and `Restore` calls `restore`;
    - `editsDamaged` shows the banner;
    - `saveError` shows;
    - with capo 2 and the fake picker path triggered through a test hook, the pick is converted
      with `soundingLabel`. Assert through the pure label helpers called by a small internal
      function `pickToRename(...)`;
    - the existing tests still pass.
  - [ ] Implement.
- **Done when:**
  - `./gradlew --max-workers=4 :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.AnalysisScreenTest'`
    passes and `:app:compileDebugKotlin` passes.
  - Commit: "Wire chord editing into the analysis screen".
- **Depends on:** T0, T1.
- **Size:** M.

### Wave 2

#### I1: Integration, end-to-end checks and verification

- **Goal:** merge the seams, prove the whole path works, run every check, and install on the
  phone.
- **Context:**
  - After wave 1 every component is real.
  - Known seams:
    - S1's `@Ignore`d import test, which now has X1's `readExport`;
    - the view model's real rebuild through `EngineHolder`;
    - the picker and shape editor opening from the edit panel;
    - capo conversion end to end.
  - The phone is a Galaxy A30s, id `RZ8MB0JLD3A`, reached through
    `/c/Users/H/AppData/Local/Android/Sdk/platform-tools/adb.exe`. **Never run device tests on
    it**: they wipe the user's library. Install with `./gradlew :app:installDebug`. Skip the phone
    steps if it is not connected.
- **Files:**
  - Modify: `JT/data/LibraryEditsTest.kt` (remove the `@Ignore`), `JT/ui/AccessibilityTest.kt`,
    `JT/ui/analysis/AnalysisFlowTest.kt`, `docs/superpowers/specs/2026-11-22-mobile-chord-editing-design.md`
    (status line → "built, <date>"), `Mobile/README.md` (one line on correcting chords).
  - Create: `DT/ChordEditingDeviceTest.kt`.
  - Fix-ups: small changes in any wave-1 file where a seam breaks. List each in the report.
- **Steps:**
  - [ ] `AnalysisFlowTest`: add a Robolectric test driving `AnalysisContent` with a real
    `EditController`, a fake rebuild and `Fixtures.loop`:
    - enter editing;
    - select chord 1;
    - Change chord… → pick E minor → the stage shows `Em` and the strip `Em ✎`;
    - drag an edge;
    - Undo restores it;
    - Done;
    - the lead sheet shows the mark.
  - [ ] `AccessibilityTest`: run `assertEveryControlNamed()` on the editing screen, the picker and
    the shape editor.
  - [ ] `ChordEditingDeviceTest` (emulator):
    - put a completed analysis in the library through `library.importAnalysisText(jsonText(result)) { "loop" }`.
      Get `result` by analysing `goldens/audio/loop.wav` (androidTest resources come from
      `../engine/src/test/resources`; see how the existing `DT/ExportDeviceTest.kt` or
      `DT/ImportDeviceTest.kt` obtain a result);
    - open it, edit chord 1's name through the UI, press Done;
    - relaunch the activity and confirm the edit persists;
    - export JSON and import it as a new song; its chord 1 is the edit;
    - Restore on the import gives the original chord.
  - [ ] Run the full verification below.
  - [ ] Phone: install and edit one chord by hand. Take screenshots of the editing screen, the
    picker and the shape editor with `adb exec-out screencap -p`, and describe them in the report.
- **Done when:**

  ```bash
  node --experimental-strip-types tools/web_goldens.mjs --check   # from the repository root
  cd Mobile
  ./gradlew --max-workers=4 :engine:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
  ./gradlew --max-workers=4 :app:api34DebugAndroidTest
  ./gradlew --max-workers=4 :app:assembleDebug :app:bundleRelease
  ```

  - All of these pass, and the emulator is stopped afterwards.
  - Commit: "Check chord editing end to end".
- **Depends on:** every wave-1 task.
- **Size:** M.

## 5. Execution waves

```
Wave 0:  T0 (contracts + edit ops)     T1 (UI signatures)
              \                          /
               merge into mobile-m8-chord-editing
                            |
Wave 1:  E1  X1  S1  V1  U1  U2  U3  U4  U5  U6  W1      (11 tasks, 3 at a time on this machine)
   needs: T0 for E1, X1, S1, V1; T1 for U3-U6; T0+T1 for U1, U2, W1
                            |
               merge into mobile-m8-chord-editing; run :app:testDebugUnitTest once
                            |
Wave 2:  I1
```

- **Critical path:** T0 (M) → the longest wave-1 task (E1, V1, S1, U1, U3, U4 and W1 are M) → I1 (M).
- **Wave-1 ordering at three at a time:** start E1, V1 and W1 first, since they hold the most risk;
  then S1, U1 and U3; then U4, X1 and U2; then U5 and U6.
- **Disjoint files in wave 1:** E1 owns the engine. X1 owns `Export.kt`. S1 owns `Library.kt` and
  `ChordifyApp.kt`. V1 owns `AnalysisViewModel.kt` and `EditController.kt`. Each U task owns its
  component file(s). W1 owns `AnalysisScreen.kt`.
- **Merge order:** inside a wave it does not matter.

## 6. Final integration and verification

Task **I1** does this, as described above:
- the end-to-end JVM flow;
- accessibility checks;
- the emulator device test of edit → reopen → export → import → restore;
- the full verification suite;
- the phone check;
- the docs.

## 7. Spec coverage

| Spec requirement (`2026-11-22-mobile-chord-editing-design.md`) | Task(s) |
|---|---|
| §1 change which chord plays when, its start and end, its shape; by hand only | U1, U2, U3, U4, V1, W1 |
| §2.1 ✎ starts Editing; Undo, Redo, Cancel (asks if dirty), Done; Back = Cancel; rotation keeps the session | U6, W1, V1 (configChanges from M7 keeps the activity on rotation) |
| §2.1 playback while editing; Loop chord | U6, V1 |
| §2.1 Restore the model's chords, with confirmation, only when edited | U6, W1, V1, S1 |
| §2.2 taller strip, tap to select, outline and handles | U1 |
| §2.2 side panel: name, numeral/function, times, ±beat, set to here, split, merge, Change chord…, Edit shape… | U2, W1 |
| §2.2 the stage shows the selected chord | W1, U5 |
| §2.3 chords touch, snapping, removal by collapse or <50 ms, merging equal neighbours, clamping | T0 (C3), U1 |
| §2.4 picker: suggestions (alternatives plus what follows), root, type, bass, No chord, preview, Use/Cancel | U3, V1 (suggestions), W1 |
| §2.5 shape editor: frets, open/muted, window, default, strum, non-blocking missing-note check, empty fretboard for no default | U4, W1 |
| §2.5 a shape applies to every chord of that name in the song | V1 (shapes map), W1 (lookup by shown label) |
| §2.6 capo: stored as sounding, edited as shown, shapes keyed by shape label | T0 (C3 helpers), W1 |
| §2.7 recalculation after edits; key kept; edited = certain, no "?", "edited" mark | E1, U1, U5 |
| §3.1 edits file format, atomic save, Restore deletes, loading rebuilds, rows unchanged, reuse ignores edits | T0 (format), S1, V1 |
| §3.2 `rebuild`, shared extraction, confidence rules, out-of-vocabulary chords, predictions only if present, the `edited` field | T0, E1 |
| §3.3 .lab has the edits; .json = rebuilt result plus `edits`; an unedited export is unchanged | X1, V1 |
| §3.3 import with edits restores original plus edits; Restore works after import; damaged edits refused | X1, S1, I1 |
| §4 damaged edits file → banner, file kept; clipping spans; failed save keeps editing with a message | S1, V1, W1, T0 (normalise) |
| §5 testing: engine, operations, UI, export/import, device, phone | T0, E1, X1, S1, V1, U1–U6, W1, I1 |
| §6 out of scope (typing names, repeats, key/tempo edits, a shared shape library, server sync, training) | none (nothing built) |

## 8. Assumptions

1. **The top bar while editing** hides the capo chip and the ⋮ menu. The spec lists only the
   editing controls for that state.
2. **The strip reports a drag once, when it ends**, so one drag is one Undo step. During the drag
   it draws the moving edge locally.
3. **Done with nothing really edited** (no edited span and no custom shape) deletes any edits file
   instead of writing an empty one, so "Restore" and the export stay as for an unedited song.
4. **The `edited` field is omitted from JSON when false** (`@EncodeDefault(NEVER)`). This keeps
   unedited exports and the Python goldens byte-identical. The server and web app are assumed to
   ignore unknown fields (`edited`, `edits`), as the spec states. Nothing is sent to them.
5. **Quality of an edited chord** comes from the large vocabulary's encoding. A 9 or add9 chord
   therefore gets a 7 or major default shape, and the player can draw their own.
6. **The missing-note message** names only the first missing tone in the order root, 3rd, 5th,
   7th, other.
7. **The undo history** lives only for one editing session and is unbounded. It is not saved.
8. **Rebuilding runs on `Dispatchers.Default`** at every edit, about a few tens of milliseconds for
   a few hundred chords. No debouncing is planned. If I1 measures it as noticeably slow on the
   phone, it reports this rather than optimising.
9. **Suggestions** use the engine's `nextChords` with the chords before the selected one, in the
   analysed key. With no real chord before it, only the model's alternatives are offered.
10. **The device test** imports an analysis JSON instead of analysing audio. This keeps it fast and
    deterministic. The engine's `rebuild` is checked against the goldens in E1.
