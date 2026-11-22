# Mobile M7: a simpler, landscape-first app Implementation Plan

> **For agentic workers:** each task below is self-contained. Read the task, the "Conventions"
> section, and the contracts it names; nothing else from this conversation is needed. Steps use
> checkbox (`- [ ]`) syntax for tracking.

## 1. Overview

This plan implements [`docs/superpowers/specs/2026-11-21-mobile-simple-landscape-ui-design.md`](../specs/2026-11-21-mobile-simple-landscape-ui-design.md):
- the Android app's analysis screen becomes a play-along **stage**, a compact chord **strip**, a **transport** with a seek bar, and **one panel of five tabs**, side by side in landscape and stacked in portrait;
- the bottom bar goes and the Library becomes home;
- Quick chord, the Songwriter and Settings get simple two-pane layouts;
- a **Screen** setting (Landscape by default) sets the activity's orientation.

Only the UI layer changes. The engine, data, playback and `music/` logic are untouched.

The approach is "contracts first, then many small file-owned slices in parallel":
- Wave 0 lands the shared code contracts (stubs, signatures, text helpers, the orientation setting) and a JVM Compose test harness (Robolectric), so that every UI slice can be built and tested on the JVM without fighting over one emulator.
- Wave 1 implements twelve slices in parallel, one file set each.
- Wave 2 deletes the old composables, adds the end-to-end and device checks, and verifies on the emulator and on the phone.

## 2. Conventions (every task)

- **Repository:** `D:\development\python\ai\Chordify` (Git Bash). The Android project is `Mobile/`; the app module is `Mobile/app`, package `com.arbaba.chordifymobile`. Below, `APP` means `Mobile/app/src/main/kotlin/com/arbaba/chordifymobile`, `JT` means `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile` (JVM tests), `DT` means `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile` (device tests).
- **Branches and worktrees:** the base branch is `mobile-m7-simple-ui`. Each task works in its own git worktree on a branch `m7/<task-id>` created from the base branch's tip at the start of its wave:
  ```bash
  git worktree add ../chordify-<task-id> -b m7/<task-id> mobile-m7-simple-ui
  cp Mobile/local.properties ../chordify-<task-id>/Mobile/
  ```
  `local.properties` (the SDK path) is untracked and must be copied. The orchestrator merges every task branch of a wave into `mobile-m7-simple-ui` before the next wave starts; the file sets are disjoint, so the merges are conflict-free.
- **Gradle:** before every Gradle command run `export JAVA_HOME="/c/Program Files/Java/jdk-21" ANDROID_USER_HOME="D:/android-user"`, then `cd Mobile && ./gradlew ...`. The first build of a worktree also builds the engine's native code (a few minutes).
- **JVM Compose tests** (after T0a) are `@RunWith(AndroidJUnit4::class)` classes in `JT` that use `createComposeRule()` and the helpers in `JT/ui/TestSupport.kt` (contract C9). Run one class with `./gradlew :app:testDebugUnitTest --tests '<fully.qualified.Class>'`.
- **Device tests** (`DT`) run on the Gradle-managed emulator: `./gradlew :app:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.Class>`. Only tasks N1 and I1 use the emulator; afterwards stop it with `powershell -NoProfile -Command "Get-Process | Where-Object { \$_.ProcessName -match '^(qemu-system-x86_64-headless|emulator)$' } | Stop-Process -Force"`.
- **Style:** Kotlin, Compose Material 3. Follow the surrounding code: KDoc on public declarations, plain English user-facing text, no new dependencies except in T0a. Colours come from `MaterialTheme.colorScheme` and `LocalChordifyColors.current` (`APP/ui/Theme.kt`: fields `tonic`, `subdominant`, `dominant`, `borrowed`, `muted`, `grid`, `tint: Float`, and `of(hue: FunctionHue): Color`).
- **Commits:** plain imperative messages ending with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (use `git commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`). Never push. Never touch the repository's `.gitignore` or `chordify_backend/src/`.
- **Do not edit files outside your task's "Files" list.** If you need a small helper, make it `private` in one of your own files.

## 3. Shared contracts

Every name below exists as real code after wave 0 (T0b and T0c write them, with stub bodies where a wave-1 task fills them in). Wave-1 tasks implement or call exactly these signatures and must not change them.

### C1. Screen entry points (T0c adds the parameters; wave-1 tasks implement them; N1 calls them)

```kotlin
// APP/ui/library/LibraryScreen.kt
@Composable fun LibraryScreen(onOpen: (String) -> Unit, onQuickChord: () -> Unit = {}, onSongwriter: () -> Unit = {},
                              onSettings: () -> Unit = {}, model: LibraryViewModel = viewModel())
// APP/ui/analysis/AnalysisScreen.kt
@Composable fun AnalysisScreen(id: String, onBack: () -> Unit = {})
// APP/quick/QuickChordScreen.kt
@Composable fun QuickChordScreen(onBack: () -> Unit = {}, model: QuickChordViewModel = viewModel())
// APP/songwriter/SongwriterScreen.kt
@Composable fun SongwriterScreen(onBack: () -> Unit = {}, model: SongwriterViewModel = viewModel())
// APP/ui/settings/SettingsScreen.kt
@Composable fun SettingsScreen(onAbout: () -> Unit, onBack: () -> Unit = {}, model: SettingsViewModel = viewModel())
@Composable fun AboutScreen(onBack: () -> Unit = {})
```

### C2. Screen orientation setting (T0c, real code, in `APP/data/Settings.kt`)

```kotlin
/** Settings > Screen (M7 spec §4.6). */
enum class ScreenOrientation(val label: String) {
    LANDSCAPE("Landscape"), PORTRAIT("Portrait"), FOLLOW_PHONE("Follow the phone");
    /** The value for Activity.requestedOrientation. */
    val activityOrientation: Int get() = when (this) {
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        FOLLOW_PHONE -> ActivityInfo.SCREEN_ORIENTATION_USER
    }
}
// in class Settings:
val screenOrientation: Flow<ScreenOrientation>          // DataStore key "screen_orientation" (the enum's name); default LANDSCAPE
suspend fun setScreenOrientation(value: ScreenOrientation)
```

### C3. Common UI (T0c, real code, new file `APP/ui/Common.kt`, package `com.arbaba.chordifymobile.ui`)

```kotlin
/** A section heading: the text upper-cased, labelMedium, LocalChordifyColors.current.muted (what CardTitle did). */
@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier)

/** A plain top bar, 48 dp tall, surface colour, bottom divider: an optional Back icon button
 *  (Icons.AutoMirrored.Filled.ArrowBack, contentDescription "Back", testTag "back"), the title
 *  (titleMedium, bold, one line, ellipsised), a flexible gap, then [actions]. testTag "topBar". */
@Composable fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier,
                             actions: @Composable RowScope.() -> Unit = {})

/** Gives [content] whether the space it fills is wider than tall (BoxWithConstraints:
 *  maxWidth > maxHeight). The landscape/portrait switch of every M7 screen. */
@Composable fun AdaptiveLayout(modifier: Modifier = Modifier, content: @Composable (landscape: Boolean) -> Unit)

/** Two panes: side by side when [landscape] (first takes [firstFraction] of the width, a 1 dp
 *  outlineVariant divider between, each pane fills the height and scrolls vertically on its own),
 *  else stacked in one vertically scrolling column. Each pane gets 16 dp padding. */
@Composable fun TwoPanes(landscape: Boolean, modifier: Modifier = Modifier, firstFraction: Float = 0.5f,
                         first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit)
```

### C4. Analysis components (T0b writes these files with stub bodies `{}`; wave-1 tasks implement)

All in package `com.arbaba.chordifymobile.ui.analysis`, directory `APP/ui/analysis/`.

```kotlin
// PanelTab.kt (T0b, real code)
enum class PanelTab(val label: String) { CHORD("Chord"), SHEET("Sheet"), CIRCLE("Circle"), STATS("Stats"), SONG("Song") }

// Stage.kt (A1)
/** The play-along stage: [chord] as the notation shows it, its theory and capo lines, the unsure
 *  badge, its guitar shape. A horizontal swipe calls onSwipe(+1) for the next chord (swipe to the
 *  left) or onSwipe(-1) for the previous one. [sounding] is the chord as it sounds without the capo. */
@Composable fun Stage(chord: ChordSegment, notation: Notation, capo: Int, sounding: String?,
                      onSwipe: (delta: Int) -> Unit, modifier: Modifier = Modifier)

// ChordStrip.kt (A2)
/** The chords coming up, sliding toward a fixed playhead (the timeline's detail track, 72 dp tall). */
@Composable fun ChordStrip(result: AnalysisResult, time: State<Double>, current: Int, notation: Notation,
                           peaks: FloatArray?, surprising: Set<Int>, onSeek: (Double) -> Unit, modifier: Modifier = Modifier)

// TransportBar.kt (A3)
/** Play/pause, the time, the seek bar (the whole song in function colours) and the speed chip.
 *  [visibleSeconds] is how many seconds the strip shows, outlined on the seek bar. */
@Composable fun TransportBar(result: AnalysisResult, time: State<Double>, playing: Boolean, rate: Double,
                             visibleSeconds: Double, onToggle: () -> Unit, onSeek: (Double) -> Unit,
                             onRate: (Double) -> Unit, modifier: Modifier = Modifier)

// ChordPanel.kt (A4)
/** The Chord tab: Now (function in key, scale, countdown, doubt) and What comes next. Fills
 *  [modifier]'s space and scrolls vertically itself. */
@Composable fun ChordPanel(chord: ChordSegment, result: AnalysisResult, time: () -> Double, notation: Notation,
                           modifier: Modifier = Modifier)

// SongPanel.kt (A4)
/** The Song tab: facts, capo text, colour legend, models, shortcuts. Fills and scrolls itself.
 *  [analysed] is the result without the capo, [result] with it. */
@Composable fun SongPanel(analysed: AnalysisResult, result: AnalysisResult, capo: Int, suggestion: CapoSuggestion,
                          modifier: Modifier = Modifier)

// AnalysisTopBar.kt (A6)
@Composable fun AnalysisTopBar(title: String, analysed: AnalysisResult, capo: Int, suggestion: CapoSuggestion,
                               notation: Notation, onBack: () -> Unit, onCapo: (Int) -> Unit,
                               onNotation: (Notation) -> Unit, onExport: (ExportFormat) -> Unit,
                               onPickFile: () -> Unit, modifier: Modifier = Modifier)

// NoAudioBanner.kt (A6)
/** Nothing when audio == PLAYABLE (or CHECKING) and !mismatch; otherwise the slim banner. */
@Composable fun NoAudioBanner(audio: AudioFile, mismatch: Boolean, onPickFile: () -> Unit, modifier: Modifier = Modifier)
```

Existing panels in `APP/ui/analysis/Panels.kt` get a `modifier` parameter (T0b adds it; A5 implements). Callers keep their trailing lambdas:

```kotlin
@Composable fun LeadSheet(result: AnalysisResult, notation: Notation, currentBar: Int, modifier: Modifier = Modifier,
                          onSeek: (Double) -> Unit)
@Composable fun CircleOfFifths(result: AnalysisResult, current: ChordSegment, modifier: Modifier = Modifier)
@Composable fun ChordShare(result: AnalysisResult, chordsByDisplay: Map<String, ChordSegment>, modifier: Modifier = Modifier)
```

### C5. Constants and pure helpers (T0b, real code with JVM tests)

In `APP/ui/analysis/Charts.kt` (moved there from `Timeline.kt`): `const val PIXELS_PER_SECOND = 70f` (dp per second of the strip), and `internal fun DrawScope.hatch(color: Color, x: Float, top: Float, w: Float, h: Float, spacing: Float, alpha: Float)` (the diagonal stripes for chords outside the key, moved unchanged from `Timeline.kt`, where it was private; used by the strip and the Song panel's legend). Already in `Charts.kt` and unchanged: `PLAYHEAD = 0.33f`, `detailSeek(time, x, width, pixelsPerSecond)`, `overviewSeek(x, width, duration)`, `chordStep(starts, t, delta)`, `barIndexAt(barStarts, t)`, `surprisingChords(chords)`, `circleNodes`, `circleTransitions`, `circleDescription`.

In `APP/ui/analysis/AnalysisText.kt` (new or changed):

```kotlin
const val FILE_MISSING = "No audio for this song: the chords run silently."            // text changed
const val FILE_MISMATCH = "This file is not the one that was analysed, so chords may not line up."  // unchanged
/** "vi · Tonic" (LETTERS: chord.roman; ROMAN: chord.display; then FUNCTION_NAMES[chord.function]);
 *  "No chord" for N/X; null when it has neither a numeral nor a function. */
fun theoryLine(chord: ChordSegment, notation: Notation): String?
/** "Capo 2 · sounds as F#m" when capo > 0, the chord is not N/X and sounding != null; else null. */
fun soundsAsLine(chord: ChordSegment, capo: Int, sounding: String?): String?
/** "Capo: none" or "Capo: fret 2". */
fun capoLabel(capo: Int): String
/** "none", "fret 3", or "fret 2  ★ 100% open chords" for the suggested fret (suggestion.capo > 0). */
fun capoMenuLabel(fret: Int, suggestion: CapoSuggestion): String
/** "G major · 144 BPM · 4/4" (BPM rounded, left out when null); the song's real key. */
fun songFacts(analysed: AnalysisResult): String
/** songFacts + " · 4:16 · 9 chords" (formatTime of the duration; summary.uniqueChords). */
fun songDetails(analysed: AnalysisResult): String
/** "0.5×", "0.75×", "1×", "1.25×". */
fun speedLabel(rate: Double): String
```

Already there and unchanged: `countdown`, `elapsed`, `functionLine`, `unsure`, `actualNext`, `actualNextLine`, `theoryOnlyLine`, `percent`, `capoNotice`, `suggestionNotice`, `diagramDescription`.

### C6. User-facing strings and test tags

Tests across tasks find nodes by these; implement them exactly.

| Where | Test tag / text |
|---|---|
| `ScreenTopBar` | tag `topBar`; Back: tag `back`, contentDescription `Back` |
| Stage (A1) | root tag `stage` (also the swipe target, polite live region); name tag `nowName`; theory line tag `theoryLine`; capo line tag `soundsAs`; unsure badge tag `unsure` (contentDescription `Show other possible chords`), shown only when `unsure(chord) != null`; tapping it toggles a line with tag `unsureText` and the text `unsure(chord)` |
| Strip (A2) | tag `strip`, contentDescription `Timeline of N chords: tap or drag to move through the song` (N = result.chords.size) |
| Transport (A3) | play button tag `play`, contentDescription `Play` or `Pause`; time tag `time`, text `m:ss / m:ss`; seek bar tag `seekBar`, contentDescription `The whole song: tap or drag to jump`; speed chip tag `speed`, text `speedLabel(rate) + " ▾"`; its menu items' texts `speedLabel(s)` for `s` in `PlaybackClock.SPEEDS` |
| Chord panel (A4) | root tag `chordPanel`; headings `Now` and `What comes next` (via SectionTitle, so shown upper-cased) |
| Song panel (A4) | root tag `songPanel` |
| Panels (A5) | lead sheet tag `leadSheet`, bar cells `bar0`, `bar1`, ...; circle tag `circle` (contentDescription `circleDescription(result)`); stats root tag `statsPanel` |
| Top bar (A6) | Back tag `back`; capo chip tag `capo`, text `capoLabel(capo) + " ▾"`; capo menu items' texts `capoMenuLabel(fret, suggestion)` for frets 0..9; more button tag `more`, contentDescription `More options`; its items: `Show Roman numerals` (when LETTERS) or `Show letters` (when ROMAN), `Export chords (.lab)`, `Export analysis (.json)`, `Pick the audio file` |
| Banner (A6) | tag `noAudio`; text `FILE_MISSING` or (when mismatch) `FILE_MISMATCH`; button text `Pick the file` (only when audio == MISSING) |
| Analysis screen (A7) | root tag `analysis`; stage+strip container tag `stageArea`; panel container tag `panelArea`; tab texts `PanelTab.label` |
| Library (L1) | Add button tag `add`, text `Add`; its items `Pick a song`, `Record`, `Import an analysis (.json)`; recording bar tag `recordingBar` with text `Recording m:ss` and button `Stop and analyse`; top bar actions with text (landscape) or contentDescription (portrait) `Quick chord`, `Songwriter`, `Settings`; rows keep tag `analysis-<id>` |
| Quick chord (Q1) | record button tag `recordChord`; result name tag `quickName` |
| Songwriter (S1) | draft field tag `draft`; key chip tag `key`, text `"Key: " + (key or "from the chords") + " ▾"` |
| Settings (ST1) | switch row tag `majorMinorOnly`; Screen options as radio rows with texts `Landscape`, `Portrait`, `Follow the phone` (tags `screen-LANDSCAPE`, `screen-PORTRAIT`, `screen-FOLLOW_PHONE`); About row tag `about` |

### C7. Layout rule

"Landscape" everywhere means the space the screen fills is wider than tall (`AdaptiveLayout`'s `landscape`), not the device configuration. Tests force it with C9's sizes.

### C8. Songwriter asking (S1)

`SongwriterContent` gets a parameter `onSuggest: () -> Unit` and runs `LaunchedEffect(chords, key) { onSuggest() }`, so suggestions are asked for when the screen opens and after every change. `SongwriterViewModel.update` and `setKey` no longer call `ask()` themselves.

### C9. JVM test support (T0a, new file `JT/ui/TestSupport.kt`, package `com.arbaba.chordifymobile.ui`)

```kotlin
val LANDSCAPE: DpSize = DpSize(800.dp, 360.dp)
val PORTRAIT: DpSize = DpSize(360.dp, 760.dp)
/** loop.wav (C G Am F in C major), sevenths_stereo_22k.wav and waltz.wav: the engine's results
 *  from Mobile/app/src/test/resources/goldens/web.json (results[0..2].result). */
object Fixtures { val loop: AnalysisResult; val sevenths: AnalysisResult; val waltz: AnalysisResult }
/** setContent with ChordifyTheme, at [size] (DeviceConfigurationOverride.ForcedSize). */
fun ComposeContentTestRule.show(size: DpSize = LANDSCAPE, content: @Composable () -> Unit)
/** Every node with a click action has a non-blank text or content description. */
fun ComposeContentTestRule.assertEveryControlNamed()
/** At least one node is a live region (SemanticsProperties.LiveRegion). */
fun ComposeContentTestRule.assertAnnounced()
```

## 4. Task list

### Wave 0

#### T0a: JVM Compose tests with Robolectric (spike)

- **Goal:** let Compose UI tests run as JVM unit tests (Robolectric), so the wave-1 slices can be tested in parallel without sharing one emulator. Move the old device UI tests out of the way.
- **Context:**
  - Today Compose UI tests live in `DT/ui/` and run on the Gradle-managed emulator. Several agents running emulators in parallel would collide.
  - Robolectric 4.14.1 runs `androidx.compose.ui.test.junit4.createComposeRule()` on the JVM. The test activity comes from `androidx.compose.ui:ui-test-manifest`, already a `debugImplementation` dependency.
  - Files to read:
    - `Mobile/gradle/libs.versions.toml`;
    - `Mobile/app/build.gradle`;
    - `JT/music/WebGoldens.kt`: `WebGoldens.json` is the parsed `goldens/web.json`, and `results[i].result` holds the engine's `AnalysisResult` JSON;
    - `DT/ui/AnalysisScreenTest.kt`: how `ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), ...)` is used.
  - The old device UI tests `DT/ui/AnalysisScreenTest.kt`, `DT/ui/LibraryScreenTest.kt`, `DT/ui/M6ScreensTest.kt` and `DT/ui/AccessibilityTest.kt` exercise composables whose signatures wave 1 changes. They are deleted here; each wave-1 task writes the JVM replacement for its own screen. `DT/MainActivityTest.kt` stays (N1 owns it).
- **Files:**
  - Modify: `Mobile/gradle/libs.versions.toml`, `Mobile/app/build.gradle`.
  - Create: `Mobile/app/src/test/resources/robolectric.properties`, `JT/ui/TestSupport.kt`, `JT/ui/RobolectricSmokeTest.kt`.
  - Delete: the four `DT/ui/` test files named above.
- **Contracts provided:** C9.
- **Steps:**
  - [ ] In `libs.versions.toml` add `robolectric = "4.14.1"` under `[versions]` and `robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }` under `[libraries]`.
  - [ ] In `Mobile/app/build.gradle`, add `testOptions.unitTests.includeAndroidResources = true` inside the existing `testOptions { }` block, and these dependencies:
    ```groovy
    testImplementation libs.robolectric
    testImplementation libs.ext.junit
    testImplementation platform(libs.compose.bom)
    testImplementation libs.compose.ui.test.junit4
    ```
  - [ ] Create `robolectric.properties` with the single line `sdk=34`.
  - [ ] Write `TestSupport.kt` exactly as contract C9: use `DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) { ChordifyTheme { content() } }` (needs `@OptIn(ExperimentalTestApi::class)`) and `SemanticsProperties.Text`, `ContentDescription`, `LiveRegion` with `androidx.compose.ui.semantics.getOrNull`.
  - [ ] Write `RobolectricSmokeTest.kt` (`@RunWith(AndroidJUnit4::class)`):
    - (a) `show(LANDSCAPE) { GuitarDiagram(listOf(-1, 3, 2, 0, 1, 0), 0) }`, then assert a node whose contentDescription starts with `Guitar chord diagram` exists;
    - (b) `show(PORTRAIT) { Text("hello", Modifier.clickable {}) }` with `assertEveryControlNamed()`;
    - (c) `Fixtures.loop.chords.map { it.display }.take(4) == listOf("C", "G", "Am", "F")`.
  - [ ] Delete the four `DT/ui/` files.
  - [ ] Run the commands below. If Robolectric cannot render Compose after reasonable effort, stop and report: the plan's fallback is to run wave-1 tests as device tests one task at a time.
- **Done when:** `./gradlew :app:testDebugUnitTest` passes, including `RobolectricSmokeTest`'s 3 tests and the 38 existing JVM tests, and `./gradlew :app:compileDebugAndroidTestKotlin` succeeds. Commit: "Run Compose UI tests on the JVM with Robolectric".
- **Depends on:** none.
- **Size:** M.

#### T0b: Analysis contracts: stubs, panel signatures, text helpers

- **Goal:** create the analysis screen's shared code contracts (C4, C5) as real code, with stub bodies where wave 1 fills them in, so every wave-1 task compiles against them.
- **Context:**
  - Files to read: `APP/ui/analysis/AnalysisText.kt` (style of the text helpers), `APP/ui/analysis/Charts.kt`, `APP/ui/analysis/Timeline.kt` (holds `const val PIXELS_PER_SECOND = 70f` today), `APP/ui/analysis/Panels.kt`, `JT/ui/analysis/AnalysisTextTest.kt`, `APP/music/Music.kt` (`chordName`, `isNoChord`, `FUNCTION_NAMES`, `formatTime`), `APP/music/Capo.kt` (`CapoSuggestion`).
  - `AnalysisResult` is `com.arbaba.chordifymobile.engine.model.AnalysisResult`: `key.global.tonic/mode`, `tempo.bpm` (Double?), `tempo.meter`, `source.duration`, `summary.uniqueChords`. `ChordSegment` fields: `label`, `display`, `roman`, `function`, `confidence`, `alternatives`, `next`, `theoryOnly`, `start`, `end`, `index`.
  - Stub bodies are an empty block `{}`. They must compile, take exactly the C4 signatures, and carry the KDoc from C4.
- **Files:**
  - Create: `APP/ui/analysis/PanelTab.kt`, `Stage.kt`, `ChordStrip.kt`, `TransportBar.kt`, `ChordPanel.kt`, `SongPanel.kt`, `AnalysisTopBar.kt`, `NoAudioBanner.kt` (all in `APP/ui/analysis/`).
  - Modify: `APP/ui/analysis/Panels.kt` (only add the `modifier` parameters of C4; apply `modifier` to each function's outermost element; nothing else changes), `APP/ui/analysis/Charts.kt` (add `PIXELS_PER_SECOND` and `hatch`), `APP/ui/analysis/Timeline.kt` (remove its `PIXELS_PER_SECOND` declaration and its private `hatch`, which it now gets from `Charts.kt`), `APP/ui/analysis/AnalysisText.kt`, `JT/ui/analysis/AnalysisTextTest.kt`.
- **Contracts provided:** C4 (stubs and `PanelTab`), C5.
- **Steps:**
  - [ ] Write the JVM tests first in `AnalysisTextTest.kt`, using `Fixtures`-free data (the test's existing `loop` from `WebGoldens`):
    - `theoryLine(c, LETTERS) == "I · Tonic"`;
    - `theoryLine(c, ROMAN) == "C · Tonic"`;
    - `theoryLine(c.copy(label = "N"), LETTERS) == "No chord"`;
    - `soundsAsLine(c, 2, "Bb") == "Capo 2 · sounds as Bb"`, and null for capo 0;
    - `capoLabel(0) == "Capo: none"` and `capoLabel(3) == "Capo: fret 3"`;
    - `capoMenuLabel(5, CapoSuggestion(5, 1.0)) == "fret 5  ★ 100% open chords"`, `capoMenuLabel(0, CapoSuggestion(5, 1.0)) == "none"`, `capoMenuLabel(5, CapoSuggestion(0, 1.0)) == "fret 5"`;
    - `songFacts(loop) == "C major · 118 BPM · 4/4"` (check the meter in the fixture and assert what it holds);
    - `songDetails(loop) == songFacts(loop) + " · 0:08 · 4 chords"`;
    - `speedLabel(0.5) == "0.5×"`, `speedLabel(1.0) == "1×"`, `speedLabel(1.25) == "1.25×"`.
  - [ ] Run them and see them fail to compile.
  - [ ] Implement the C5 functions in `AnalysisText.kt`, and change `FILE_MISSING`'s text.
  - [ ] Move `PIXELS_PER_SECOND` and `DrawScope.hatch` (made `internal`, body unchanged) to `Charts.kt`.
  - [ ] Add the `modifier` parameters to `Panels.kt`.
  - [ ] Write the eight new files with the C4 signatures, stub bodies and `PanelTab`.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.AnalysisTextTest'` passes and `./gradlew :app:compileDebugKotlin` succeeds. Commit: "Add the M7 analysis contracts: stubs, panel modifiers and text helpers".
- **Depends on:** none.
- **Size:** M.

#### T0c: Screen entry points, orientation setting, common UI

- **Goal:** add contracts C1, C2 and C3 as real code, so that the screen tasks and the navigation task can work in parallel.
- **Context:**
  - Files to read:
    - `APP/data/Settings.kt`: a DataStore `settingsStore`, with `booleanPreferencesKey` used for `MAJOR_MINOR_ONLY`;
    - `APP/ui/analysis/NowAndNext.kt`: `internal fun CardTitle`, the model for `SectionTitle`;
    - the screen files listed below.
  - Parameters added to screen entry points are unused until wave 1; they only need to exist with defaults.
- **Files:**
  - Modify: `APP/data/Settings.kt`, `APP/ui/library/LibraryScreen.kt` (signature of `LibraryScreen` only), `APP/ui/analysis/AnalysisScreen.kt` (signature of `AnalysisScreen` only), `APP/quick/QuickChordScreen.kt` (signature of `QuickChordScreen` only), `APP/songwriter/SongwriterScreen.kt` (signature of `SongwriterScreen` only), `APP/ui/settings/SettingsScreen.kt` (signatures of `SettingsScreen` and `AboutScreen` only).
  - Create: `APP/ui/Common.kt`, `JT/data/ScreenOrientationTest.kt`.
- **Contracts provided:** C1, C2, C3.
- **Steps:**
  - [ ] Write `ScreenOrientationTest` (plain JUnit). For each enum value, assert `activityOrientation` equals the matching `ActivityInfo.SCREEN_ORIENTATION_*` constant, and assert the three labels. Run it and see it fail.
  - [ ] Implement C2 in `Settings.kt`:
    - `stringPreferencesKey("screen_orientation")`;
    - reading maps an unknown or missing value to `LANDSCAPE` (`runCatching { ScreenOrientation.valueOf(it) }`);
    - the setter writes `value.name`.
  - [ ] Write `Common.kt` per C3.
  - [ ] Add the C1 parameters to the six entry points; leave their bodies unchanged.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.data.ScreenOrientationTest'` passes and `./gradlew :app:compileDebugKotlin` succeeds. Commit: "Add the M7 screen entry points, the Screen setting and common UI".
- **Depends on:** none.
- **Size:** S.

### Wave 1 (all parallel; each starts from the base branch after wave 0 is merged)

#### A1: Stage

- **Goal:** implement `Stage` (C4): the play-along view of the chord playing now.
- **Context:**
  - Spec §3.3:
    - the chord's name, large, as the notation shows it, with the capo's shapes;
    - one quiet line under it: numeral and function (`theoryLine`), and with a capo a second quiet line `soundsAsLine`;
    - a small "?" next to the name only when the model is less than half sure and has alternatives; tapping it shows "Unsure: Am or Em also possible";
    - the guitar diagram, counted from the capo;
    - "No chord" shows the words and no diagram;
    - the stage is a polite live region; a swipe goes to the previous or next chord.
  - Read:
    - `APP/ui/analysis/NowAndNext.kt` (`NowPlayingCard`: today's version of this content, and the live-region semantics);
    - `APP/ui/analysis/AnalysisScreen.kt` (the current `swipe` modifier: horizontal drag, threshold 48 dp);
    - `APP/ui/analysis/GuitarDiagram.kt` (`GuitarDiagram(shape, capo, width)`);
    - `APP/music/Music.kt` (`chordName`, `guitarShape(chord)`, `isNoChord`);
    - `APP/ui/analysis/AnalysisText.kt` (`theoryLine`, `soundsAsLine`, `unsure`).
  - Layout: a `Row` centred in the available space: a `Column` (name at 64 sp bold, tag `nowName`, with the "?" badge after it on the same row; then `theoryLine` in bodyMedium, `muted` colour; then `soundsAsLine` in bodySmall; then `unsureText` when toggled), then the diagram at 96 dp width unless no chord.
  - Tags and texts per C6 (Stage row).
- **Files:** Modify `APP/ui/analysis/Stage.kt`. Create `JT/ui/analysis/StageTest.kt`.
- **Contracts used:** C4 `Stage`, C5 helpers, C6, C9.
- **Steps:**
  - [ ] Write `StageTest` (Robolectric, `show(LANDSCAPE)` and `show(PORTRAIT)` from C9, chord = `Fixtures.loop.chords[0]`, which is C):
    - the name shows `C` in LETTERS and `I` in ROMAN;
    - `theoryLine` shows `I · Tonic`;
    - capo 5 with `sounding = "C"` shows `soundsAs` `Capo 5 · sounds as C`;
    - with `confidence = 0.4` and two alternatives, `unsure` exists, and clicking it shows `unsureText` with `unsure(chord)`; with `confidence = 0.9`, there is no `unsure`;
    - a no-chord segment shows `N.C.` and `No chord`, and no node whose contentDescription starts with `Guitar chord diagram`;
    - `performTouchInput { swipeLeft() }` on `stage` calls `onSwipe(1)`, and `swipeRight()` calls `onSwipe(-1)`;
    - `assertEveryControlNamed()` and `assertAnnounced()`.
  - [ ] Implement `Stage`.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.StageTest'` passes. Commit: "Show the chord playing now on a play-along stage".
- **Depends on:** T0a, T0b.
- **Size:** S.

#### A2: Chord strip

- **Goal:** implement `ChordStrip` (C4): the compact timeline of chords sliding toward the playhead.
- **Context:**
  - Spec §3.4: the strip is the detail track of today's timeline, compacted to fit beside the panel:
    - 72 dp tall instead of 196 dp; chord blocks at 70 dp per second under a fixed playhead a third of the way across, moved by a graphics layer;
    - the section labels' own lane goes; sections become thin markers;
    - each block shows the chord's name and, below it, its numeral (its letter name in Roman mode);
    - function colours, the active chord solid and the others tinted; stripes for chords outside the key; a fainter block for low confidence; ✦ on surprising changes;
    - beat and downbeat lines; the waveform, faint, behind;
    - tap or drag to seek.
  - Today's implementation to adapt is `private fun Detail` in `APP/ui/analysis/Timeline.kt`, with its helper `text` (copy it as a private function into `ChordStrip.kt`; `Timeline.kt` is deleted later by I1). For the stripes call the shared `DrawScope.hatch` in `Charts.kt` (C5); do not copy it.
  - Geometry, in dp:
    - beat lines and downbeat lines (`outline` colour) span the full height;
    - section starts are 2 dp-wide `onSurfaceVariant` markers;
    - the chord ribbon is from y = 4 to 50: the name at 16 sp bold, baseline 22, drawn when the block is wider than 34 dp; the numeral at 11 sp, baseline 40, drawn when it is wider than 60 dp and the chord is not N/X; a 4 dp function-colour stripe at the ribbon's bottom;
    - the waveform band is from y = 54 to 70, `outline` colour at alpha 0.5, one line per peak (as `Detail` draws it).
  - Drawing rules:
    - active block: `colors.of(functionHue(chord.function))`; others: `lerp(colorScheme.surface, that colour, colors.tint)`;
    - `function == "borrowed"`: `hatch` in `colors.borrowed`;
    - N/X blocks: `surfaceVariant`;
    - `confidence < 0.35`: alpha 0.6;
    - ✦ at the block's top right for `surprising`.
  - Translation: `graphicsLayer { translationX = width * PLAYHEAD - time.value * pps }`. The playhead is a 2 dp `onSurface` line at `width * PLAYHEAD`.
  - Gestures: tap calls `onSeek(detailSeek(time.value, x, width, pps))`; a horizontal drag calls `onSeek(time.value - dx / pps)`.
  - Tag and description per C6.
  - `pps = PIXELS_PER_SECOND.dp.toPx()`.
- **Files:** Modify `APP/ui/analysis/ChordStrip.kt`. Create `JT/ui/analysis/ChordStripTest.kt`.
- **Contracts used:** C4 `ChordStrip`, C5 (`PIXELS_PER_SECOND`, `PLAYHEAD`, `detailSeek`), C6, C9.
- **Steps:**
  - [ ] Write `ChordStripTest` (`show(LANDSCAPE)`, `Fixtures.loop`, `time = mutableStateOf(0.0)`):
    - `strip` has the contentDescription `Timeline of 4 chords: tap or drag to move through the song`;
    - the strip's height is 72 dp (`assertHeightIsEqualTo(72.dp)`);
    - clicking at `x = width * 0.33 + 70 dp` calls `onSeek` with about `1.0` (±0.1);
    - `swipeLeft` calls `onSeek` with a value greater than 0;
    - `assertEveryControlNamed()`.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.ChordStripTest'` passes. Commit: "Draw the chords coming up as a compact strip".
- **Depends on:** T0a, T0b.
- **Size:** M.

#### A3: Transport bar

- **Goal:** implement `TransportBar` (C4): play/pause, the time, the seek bar (today's overview strip) and the speed chip.
- **Context:**
  - Spec §3.5: play/pause; the time `1:12 / 4:16`; the seek bar, which is today's overview strip:
    - the whole song in function colours, section markers and the playhead;
    - a light outline of what the strip shows;
    - tap or drag to seek;
  - and a speed chip `1× ▾` with 0.5×, 0.75×, 1×, 1.25×.
  - Adapt `private fun Overview` in `APP/ui/analysis/Timeline.kt` and the old private `Transport` and `TimeText` in `APP/ui/analysis/AnalysisScreen.kt` (copy what you need; do not edit those files).
  - The time text must read `time.value` inside its own small composable, so only it recomposes every frame.
  - The seek bar: a `Canvas`, `weight(1f)`, 24 dp tall, drawn as follows:
    - chords as rectangles in `colors.of(functionHue(function))`, `colors.grid` for N/X;
    - section starts as 1 dp `onSurfaceVariant` lines;
    - an outline rectangle (1.5 dp, `onSurface`, alpha 0.6) from `time - visibleSeconds * PLAYHEAD`, `visibleSeconds` wide;
    - a 2 dp playhead.
  - Seek-bar gestures: tap and horizontal drag call `onSeek(overviewSeek(x, width, duration))`.
  - The play button is a filled `FilledIconButton` with a text glyph `▶`/`❚❚` (or Icons.Filled.PlayArrow / a pause icon from `material-icons-core`, if present); contentDescription per C6.
  - The speed chip is `AssistChip` with `DropdownMenu` over `PlaybackClock.SPEEDS` (`APP/playback/PlaybackClock.kt`).
- **Files:** Modify `APP/ui/analysis/TransportBar.kt`. Create `JT/ui/analysis/TransportBarTest.kt`.
- **Contracts used:** C4 `TransportBar`, C5 (`overviewSeek`, `PLAYHEAD`, `speedLabel`), C6, C9.
- **Steps:**
  - [ ] Write `TransportBarTest` (`show(LANDSCAPE)`, `Fixtures.loop`, `time = mutableStateOf(2.0)`):
    - `time` shows `0:02 / 0:08`;
    - `play` has contentDescription `Play` when not playing and `Pause` when playing, and clicking it calls `onToggle`;
    - clicking `seekBar` at 75% of its width calls `onSeek` with about `6.0` (±0.2);
    - `speed` shows `1× ▾`, and clicking it then `0.75×` calls `onRate(0.75)`;
    - `assertEveryControlNamed()`.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.TransportBarTest'` passes. Commit: "Add the transport: play, time, the song as a seek bar, and speed".
- **Depends on:** T0a, T0b.
- **Size:** S.

#### A4: Chord panel and Song panel

- **Goal:** implement `ChordPanel` and `SongPanel` (C4).
- **Context:**
  - Spec §3.6, Chord:
    - *Now*: the chord's function in the key (`functionLine(chord, result)`, e.g. "Em: Tonic in G major"), the scale to play over it (`chord.scaleHint`, as "Scale to play over it: …"), the countdown (`countdown(chord, result.beats, time())`, read inside its own small composable so only it recomposes every frame), and the doubt line (`unsure(chord)`);
    - *What comes next*: the predictions (`chord.next`), each with name, numeral, a bar in the function colour of the predicted chord (look it up by display among `result.chords`), percentage (`percent(p)`) and reason, the first prefixed "Most likely · ";
    - then `actualNextLine(chord, actualNext(result.chords, chord), notation)` and `theoryOnlyLine(chord)` when not null.
    - Today's `NextChordCard` in `APP/ui/analysis/NowAndNext.kt` renders "What comes next"; reuse its row layout, without the Card.
  - Spec §3.6, Song:
    - *This song*: `songDetails(analysed)`;
    - *Capo*: `capoNotice(analysed, capo)` when not null, and `suggestionNotice(suggestion, capo)` when not null; when both are null, "No capo needed: the shapes are the song's own." Note that `suggestionNotice` returns null when the capo is already the suggested one;
    - *Colours*: a legend of small swatches: Tonic, Subdominant, Dominant, "Outside the key" (hatched in `borrowed`), "✦ Surprising change" (today's `Legend` in `Timeline.kt`; draw the hatched swatch with the shared `DrawScope.hatch` from `Charts.kt` (C5), do not copy it);
    - *Models*: `result.models` as "chord chordnet-chroma@3.0.0" lines;
    - *Keyboard*: "Space plays or pauses, ← and → jump between chords, R toggles Roman numerals."
  - Both are plain sections with `SectionTitle` headings (C3), no Card. Each is a `Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))`.
- **Files:** Modify `APP/ui/analysis/ChordPanel.kt`, `APP/ui/analysis/SongPanel.kt`. Create `JT/ui/analysis/ChordPanelTest.kt`, `JT/ui/analysis/SongPanelTest.kt`.
- **Contracts used:** C3 `SectionTitle`, C4, C5, C6, C9.
- **Steps:**
  - [ ] `ChordPanelTest` (`Fixtures.loop`, chord 0):
    - `chordPanel` exists, with `NOW` and `WHAT COMES NEXT` (upper-cased headings);
    - the text `Tonic in C major` (substring) is shown;
    - the first prediction's reason is shown with `Most likely · ` as prefix;
    - the countdown text matching `countdown(...)` for `time = { 0.5 }` is shown;
    - a chord with `confidence = 0.4` and alternatives shows `unsure(chord)`.
  - [ ] `SongPanelTest`:
    - capo 0 with `suggestCapo(loop.chords)` shows `songDetails(loop)` and `suggestionNotice(...)`;
    - capo 5 shows `capoNotice(loop, 5)`;
    - the legend texts `Tonic`, `Subdominant`, `Dominant`, `Outside the key` are shown.
  - [ ] Implement both.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.ChordPanelTest' --tests 'com.arbaba.chordifymobile.ui.analysis.SongPanelTest'` passes. Commit: "Add the Chord and Song panels".
- **Depends on:** T0a, T0b, T0c.
- **Size:** M.

#### A5: Sheet, Circle and Stats panels

- **Goal:** turn `LeadSheet`, `CircleOfFifths` and `ChordShare` into panels: no Card, no title (the tab names them), filling the given space and scrolling themselves.
- **Context:**
  - Spec §3.6:
    - *Sheet*: four bars a row, the current bar lit and kept in view, a tap jumps to the bar; "No steady beat was found, so there is no bar grid." when there are no bars;
    - *Circle*: as today;
    - *Stats*: time per chord (top ten), repeating progressions, predictability.
  - Current code: `APP/ui/analysis/Panels.kt`, whose signatures already take `modifier` after T0b.
  - Changes:
    - remove each `Card` and its `CardTitle`;
    - `LeadSheet`'s grid fills `modifier` (drop its fixed 260 dp height) and keeps its scroll-into-view logic;
    - `CircleOfFifths` centres the canvas in `modifier.fillMaxSize()`, sized to the smaller side;
    - `ChordShare` becomes a scrolling `Column(modifier.fillMaxSize().verticalScroll(...))` with tag `statsPanel`, using `SectionTitle("Time per chord")` and `SectionTitle("Repeating progressions")` (C3).
  - Tags per C6.
- **Files:** Modify `APP/ui/analysis/Panels.kt`. Create `JT/ui/analysis/PanelsTest.kt`.
- **Contracts used:** C3, C4, C6, C9.
- **Steps:**
  - [ ] `PanelsTest` (`Fixtures.loop`):
    - `LeadSheet` shows `bar0`, and clicking `bar1` calls `onSeek(loop.bars[1].start + 0.01)`;
    - with no bars, it shows the no-grid sentence;
    - `circle` has contentDescription `circleDescription(loop)`;
    - `statsPanel` shows `TIME PER CHORD` and `REPEATING PROGRESSIONS`;
    - no node has the text `LEAD SHEET`.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.PanelsTest'` passes. Commit: "Turn the lead sheet, circle and stats into panels".
- **Depends on:** T0a, T0b, T0c.
- **Size:** S.

#### A6: Analysis top bar and no-audio banner

- **Goal:** implement `AnalysisTopBar` and `NoAudioBanner` (C4).
- **Context:**
  - Spec §3.2:
    - Back, the title, and the song's real key · tempo · meter (`songFacts(analysed)`, in `onSurfaceVariant`, bodySmall; drop it when the bar is narrower than 480 dp);
    - the **capo chip** (`capoLabel(capo) + " ▾"`) with a menu of frets 0..9 labelled `capoMenuLabel(fret, suggestion)`, calling `onCapo(fret)`;
    - a **⋮** button (Icons.Filled.MoreVert, contentDescription `More options`) with the menu items of C6: the notation item calls `onNotation` with the other notation; the export items call `onExport(ExportFormat.LAB)` and `onExport(ExportFormat.JSON)` (`APP/data/Export.kt`); `Pick the audio file` calls `onPickFile`.
  - Build it on `ScreenTopBar` (C3), passing the capo chip and ⋮ as `actions`. The key line goes inside the title area or as the first action.
  - Spec §3.7, the banner:
    - when `audio == AudioFile.MISSING`: a slim banner with `FILE_MISSING` and a `Pick the file` button;
    - when `mismatch`: the banner says `FILE_MISMATCH` in `colorScheme.error`;
    - otherwise it renders nothing;
    - style: rounded 8 dp, `surfaceVariant` background, 12 dp horizontal padding, one row; tag `noAudio`.
  - Today's `Header` and `Notices` in `APP/ui/analysis/AnalysisScreen.kt` show the old menus; read them but do not edit that file.
- **Files:** Modify `APP/ui/analysis/AnalysisTopBar.kt`, `APP/ui/analysis/NoAudioBanner.kt`. Create `JT/ui/analysis/AnalysisTopBarTest.kt`, `JT/ui/analysis/NoAudioBannerTest.kt`.
- **Contracts used:** C3, C4, C5, C6, C9.
- **Steps:**
  - [ ] `AnalysisTopBarTest` (`Fixtures.loop`, `suggestion = suggestCapo(loop.chords)`, which is capo 5 at 100%):
    - `capo` shows `Capo: none ▾`, and clicking it lists `fret 5  ★ 100% open chords`; clicking that calls `onCapo(5)`;
    - clicking `more` then `Show Roman numerals` calls `onNotation(ROMAN)`; with ROMAN the item reads `Show letters`;
    - `Export chords (.lab)` and `Export analysis (.json)` call `onExport` with LAB and JSON; `Pick the audio file` calls `onPickFile`;
    - `back` calls `onBack`;
    - `assertEveryControlNamed()`.
  - [ ] `NoAudioBannerTest`:
    - PLAYABLE and no mismatch: no `noAudio` node;
    - MISSING: `FILE_MISSING` is shown, and `Pick the file` calls `onPickFile`;
    - PLAYABLE with mismatch: `FILE_MISMATCH` is shown.
  - [ ] Implement both.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.AnalysisTopBarTest' --tests 'com.arbaba.chordifymobile.ui.analysis.NoAudioBannerTest'` passes. Commit: "Add the analysis top bar with the capo and more menus, and the no-audio banner".
- **Depends on:** T0a, T0b, T0c.
- **Size:** S.

#### A7: Analysis screen assembly

- **Goal:** rewrite `AnalysisContent` and `AnalysisScreen` to the new layout: the stage and strip beside the panel in landscape, stacked in portrait, with the top bar above and the transport below.
- **Context:**
  - Spec §3.1:
    - **Landscape** (C7): top bar; then a `Row`: the left 60% is a `Column` (tag `stageArea`) of `Stage` (weight 1) and `ChordStrip`, with `NoAudioBanner` overlaid at the top of the stage (`Box`, `Alignment.TopCenter`, 8 dp padding); the right 40% is the panel (tag `panelArea`), a tab row of `PanelTab` labels (`PrimaryTabRow`/`Tab`, needs `@OptIn(ExperimentalMaterial3Api::class)`) over the selected panel; then `TransportBar`. The screen itself does not scroll.
    - **Portrait**: top bar, `stageArea` (`Stage` at 40% of the remaining height, then `ChordStrip`, with the banner overlaid), `panelArea` (tabs plus the panel, taking the rest), transport.
  - Wiring, from the current `AnalysisContent` in `APP/ui/analysis/AnalysisScreen.kt` (read it fully; you rewrite this file):
    - `result = withCapo(analysed, capo)`, `suggestion = suggestCapo(analysed.chords)`;
    - `time = clock.time.collectAsState()`, the `playing` and `rate` states;
    - `starts`, `index` (a derived state over `chordIndexAt`), `chord = result.chords[index]`, `sounding = analysed.chords.getOrNull(index)?.display`, `chordsByDisplay`, `surprising`, the `bar` derived state;
    - the frame loop `while (playing) withFrameNanos { clock.tick() }`;
    - `keepScreenOn` while playing; the focus requester and the key handler (Space, ←, →, R);
    - the selected tab as `rememberSaveable { mutableStateOf(PanelTab.CHORD) }`.
  - Calls:
    - `Stage(chord, notation, capo, sounding, onSwipe = { clock.seek(chordStep(starts, clock.time.value, it)) })`;
    - `ChordStrip(result, time, index, notation, peaks, surprising, onSeek = { clock.seek(it) })`;
    - `TransportBar(result, time, playing, rate, visibleSeconds, onToggle = clock::toggle, onSeek = clock::seek, onRate = clock::setRate)`, where `visibleSeconds = stageColumnWidth.value / PIXELS_PER_SECOND` (`BoxWithConstraints` on the stage column);
    - `AnalysisTopBar(title, analysed, capo, suggestion, notation, onBack, onCapo, onNotation, onExport, onPickFile)`;
    - panels:
      - CHORD: `ChordPanel(chord, result, { time.value }, notation, Modifier.fillMaxSize())`;
      - SHEET: `LeadSheet(result, notation, bar, Modifier.fillMaxSize()) { clock.seek(it) }`;
      - CIRCLE: `CircleOfFifths(result, chord, Modifier.fillMaxSize())`;
      - STATS: `ChordShare(result, chordsByDisplay, Modifier.fillMaxSize())`;
      - SONG: `SongPanel(analysed, result, capo, suggestion, Modifier.fillMaxSize())`.
  - The new `AnalysisContent` signature (keep the parameter order; `onBack` is new):
    ```kotlin
    @Composable fun AnalysisContent(title: String, analysed: AnalysisResult, clock: PlaybackClock, notation: Notation, capo: Int,
        audio: AudioFile, mismatch: Boolean, onNotation: (Notation) -> Unit, onCapo: (Int) -> Unit, onPickFile: () -> Unit,
        peaks: FloatArray? = null, onExport: (ExportFormat) -> Unit = {}, onBack: () -> Unit = {})
    ```
  - `AnalysisScreen(id, onBack)` passes `onBack` through. `AnalysisStatus` (the analysing, failed and cancelled screens) stays unchanged.
  - Remove the old private `Header`, `Notices`, `Notice`, `Transport`, `TimeText` and every use of `NowPlayingCard`, `NextChordCard` and `Timeline` from this file. Those definitions stay in their own files until I1 deletes them.
  - Your tests run against whatever the components render at the time: in your worktree they are T0b's empty stubs. Test only what this file owns: the containers, tabs, keys and the clock's wiring.
- **Files:** Modify `APP/ui/analysis/AnalysisScreen.kt`. Create `JT/ui/analysis/AnalysisScreenTest.kt`.
- **Contracts used:** C3 (`AdaptiveLayout`), C4 (all components, `PanelTab`, the panel signatures), C5, C6, C7, C9.
- **Steps:**
  - [ ] Write `AnalysisScreenTest` (`clock = PlaybackClock(Fixtures.loop.source.duration)`):
    - LANDSCAPE: the `stageArea` and `panelArea` tops are equal, and `panelArea.left >= stageArea.right` (`getUnclippedBoundsInRoot`);
    - PORTRAIT: `panelArea.top >= stageArea.bottom`;
    - all five tab labels exist; clicking `Song` selects it (`assertIsSelected`);
    - key input on `analysis`: `Key.DirectionRight` moves `clock.time` to `loop.chords[1].start + 0.01`; `Key.R` calls `onNotation(ROMAN)`; `Key.Spacebar` sets `clock.playing` true (set `mainClock.autoAdvance = false` first, so the frame loop does not run to the end; check the state, press again, see false);
    - `AnalysisStatus` with a FAILED row shows `Could not analyse this audio` and `Try again`.
  - [ ] Rewrite the file.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.analysis.AnalysisScreenTest'` passes and `./gradlew :app:compileDebugKotlin` succeeds. Commit: "Lay out the analysis screen as a stage beside one panel, stacked in portrait".
- **Depends on:** T0a, T0b, T0c.
- **Size:** M.

#### L1: Library

- **Goal:** make the Library the home screen:
  - a top bar with Quick chord, Songwriter and Settings;
  - one ＋ Add button with three choices;
  - a recording bar;
  - a two-column list in landscape;
  - the "Major and minor only" switch removed (it stays in Settings);
  - the swipe background clipped to the row's shape.
- **Context:**
  - Spec §4.2:
    - Top bar "Chordify", then Quick chord, Songwriter and Settings: an icon and a label in landscape; in portrait the icon only, with a content description.
    - The list: two columns in landscape, one in portrait. Rows as today: title, summary or progress or error; tap to open; swipe to delete (with the confirmation for the app's own files); cancel and retry.
    - The swipe background is clipped to the row's shape, so it no longer shows at the corners.
    - **＋ Add** (an extended floating button) opens three choices: Pick a song, Record, Import an analysis (.json).
    - While recording, a bar with a red dot, the timer and **Stop and analyse** replaces the Add button.
    - Messages show as today: a file that cannot be read, and the microphone refused with "Open settings".
    - The empty Library says what to do in one line.
  - Read:
    - `APP/ui/library/LibraryScreen.kt`: today's `LibraryScreen`, `LibraryContent`, `AnalysisRow`, `MICROPHONE_REFUSED`, the notification and microphone permission flow;
    - `APP/ui/library/LibraryViewModel.kt`: `majorMinorOnly`, `setMajorMinorOnly`, `options()`;
    - `APP/ui/Formatting.kt` (`summary`), `APP/music/Music.kt` (`formatTime`).
  - Icons from `material-icons-core`: Icons.Filled.Star for Quick chord, Icons.Filled.Edit for Songwriter, Icons.Filled.Settings for Settings. Use `ScreenTopBar("Chordify", onBack = null, actions = ...)` (C3) and `AdaptiveLayout` (C7) to pick the columns (`LazyVerticalGrid(GridCells.Fixed(if (landscape) 2 else 1))`).
  - `LibraryContent` is yours to reshape. Its new signature, for your test:
    ```kotlin
    @Composable fun LibraryContent(analyses: List<Analysis>, recordingSeconds: Double?, message: String?,
        onPick: () -> Unit, onRecord: () -> Unit, onImportAnalysis: () -> Unit, onDismissMessage: () -> Unit,
        onOpenAppSettings: (() -> Unit)?, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onDelete: (String) -> Unit,
        onOpen: (String) -> Unit, onQuickChord: () -> Unit, onSongwriter: () -> Unit, onSettings: () -> Unit)
    ```
  - `onRecord` starts recording when `recordingSeconds == null` (the Add menu's "Record") and stops it otherwise (the bar's "Stop and analyse"), as today.
  - The ViewModel keeps reading the setting for `options()`; remove `majorMinorOnly` and `setMajorMinorOnly` from it.
  - The swipe fix: give the `SwipeToDismissBox` background `Box` the row card's shape (`Modifier.clip(CardDefaults.shape)`), so the red shows only behind the card.
- **Files:** Modify `APP/ui/library/LibraryScreen.kt`, `APP/ui/library/LibraryViewModel.kt`. Create `JT/ui/library/LibraryScreenTest.kt`.
- **Contracts used:** C1 (`LibraryScreen`), C3, C6, C7, C9.
- **Steps:**
  - [ ] Write `LibraryScreenTest`. Build rows like `Analysis(id, "$id.mp3", "file:///$id.mp3", false, "sha", 0L, status = ..., modelFingerprint = "fp")`, with `com.arbaba.chordifymobile.data.Analysis` and `Status`.
    - Empty: the one-line hint is shown; clicking `add` lists `Pick a song`, `Record`, `Import an analysis (.json)`, which call `onPick`, `onRecord` and `onImportAnalysis`.
    - `recordingSeconds = 42.0`: `recordingBar` shows `Recording 0:42`; `Stop and analyse` calls `onRecord`; `add` does not exist.
    - A COMPLETED row's click calls `onOpen(id)`; a RUNNING row's `Cancel` and a FAILED row's `Try again` call back.
    - LANDSCAPE: clicking `Quick chord`, `Songwriter` and `Settings` (by text) calls their callbacks. PORTRAIT: the same by contentDescription.
    - LANDSCAPE with two rows: their tops are equal (two columns). PORTRAIT: the second is below the first.
    - No node has the text `Major and minor chords only (C instead of Cmaj7, G instead of G7)`.
    - `assertEveryControlNamed()`.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.library.LibraryScreenTest'` passes and `./gradlew :app:compileDebugKotlin` succeeds. Commit: "Make the Library home: top bar, one Add button, a recording bar and two columns sideways".
- **Depends on:** T0a, T0c.
- **Size:** M.

#### Q1: Quick chord

- **Goal:** give Quick chord a top bar with Back and two halves in landscape, stacked in portrait.
- **Context:**
  - Spec §4.3:
    - landscape: the record button (with the level meter while recording), "Or pick a short clip" and the errors on the left; the chord, its confidence and its shape on the right;
    - portrait: the same, stacked;
    - the instruction is one line: "Strum one chord for two or three seconds."
  - Read `APP/quick/QuickChordScreen.kt` (`QuickState`, `QuickResult`, the view model, `QuickChordScreen`, `QuickChordContent`).
  - Keep the view model and the permission flow unchanged. Replace `CardTitle` with `SectionTitle` (C3).
  - Layout: `ScreenTopBar("Quick chord", onBack)`, then `AdaptiveLayout { landscape -> TwoPanes(landscape, first = {…}, second = {…}) }`.
  - The result side keeps its polite live region; tags per C6. New content signature:
    ```kotlin
    @Composable fun QuickChordContent(state: QuickState, result: QuickResult?, error: String?, onRecord: () -> Unit,
        onPick: () -> Unit, onOpenAppSettings: (() -> Unit)? = null, onBack: () -> Unit = {})
    ```
- **Files:** Modify `APP/quick/QuickChordScreen.kt`. Create `JT/quick/QuickChordScreenTest.kt`.
- **Contracts used:** C1 (`QuickChordScreen`), C3, C6, C7, C9.
- **Steps:**
  - [ ] Write `QuickChordScreenTest`:
    - Idle: `Your chord will appear here.`; `Record a chord` calls `onRecord`; `Or pick a short clip` calls `onPick`; `back` calls `onBack`.
    - Recording(1.24, 0.1f): `Stop`, `1.2 s`, the `Input level` description, and `Or pick a short clip` disabled.
    - A result Am7 at 0.8712: `quickName` is `Am7`, with `Confidence 87%` and a guitar diagram description.
    - Working with an error and settings: `Listening…` disabled, the error shown, `Open the app's settings` calls back.
    - LANDSCAPE: the `recordChord` node's right is at or left of the `quickName` node's left (two halves). PORTRAIT: `quickName` is below `recordChord`.
    - `assertEveryControlNamed()` and `assertAnnounced()`.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.quick.QuickChordScreenTest'` passes. Commit: "Lay Quick chord out in two halves with a top bar".
- **Depends on:** T0a, T0c.
- **Size:** S.

#### S1: Songwriter

- **Goal:** give the Songwriter a top bar with Back and the key picker, two halves in landscape, and automatic suggestions instead of the Suggest button.
- **Context:**
  - Spec §4.4:
    - landscape: the progression (chips removed by a tap), the field to add chords and a one-line hint on the left; the key line and the suggestions on the right, a tap adding one;
    - the key picker ("Key: from the chords ▾") is in the top bar;
    - suggestions are asked for when the screen opens and after every change;
    - portrait: the same, stacked.
  - Read `APP/songwriter/SongwriterScreen.kt` (`KEYS`, `SUGGESTIONS`, `splitChords`, `keyLine`, the view model, the content).
  - Contract C8: `SongwriterContent` gains `onSuggest: () -> Unit` and runs `LaunchedEffect(chords, key) { onSuggest() }`; the view model's `update` and `setKey` stop calling `ask()`; `SongwriterScreen` passes `onSuggest = { model.ask() }`; remove the "Suggest the next chord" button.
  - New content signature:
    ```kotlin
    @Composable fun SongwriterContent(chords: List<String>, draft: String, key: String, answer: ProgressionResponse?,
        error: String?, onRemove: (Int) -> Unit, onDraft: (String) -> Unit, onAdd: () -> Unit, onKey: (String) -> Unit,
        onSuggest: () -> Unit, onPick: (String) -> Unit, onBack: () -> Unit = {})
    ```
  - Use `ScreenTopBar("Songwriter", onBack) { key chip with DropdownMenu over KEYS }`, `AdaptiveLayout` and `TwoPanes`, and `SectionTitle` instead of `CardTitle`.
- **Files:** Modify `APP/songwriter/SongwriterScreen.kt`. Create `JT/songwriter/SongwriterScreenTest.kt`.
- **Contracts used:** C1 (`SongwriterScreen`), C3, C6, C7, C8, C9.
- **Steps:**
  - [ ] Write `SongwriterScreenTest`. Hold `chords`/`draft`/`key` in `remember { mutableStateOf(...) }` inside `show { }` so callbacks change them.
    - On composition `onSuggest` is called once; removing a chip (contentDescription `Remove G`) calls it again; choosing a key from `key` (`Am`) calls it again.
    - Typing `F` in `draft` and clicking `Add` calls `onAdd`.
    - With an answer, `Assuming C major (87% sure)` and the reasons are shown, and `Add F` calls `onPick("F")`.
    - No node has the text `Suggest the next chord`.
    - LANDSCAPE: the suggestions' heading is to the right of the draft field. PORTRAIT: below it.
    - `assertEveryControlNamed()` and `assertAnnounced()`.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.songwriter.SongwriterScreenTest'` passes, and the existing `SongwriterTest` still passes. Commit: "Lay the Songwriter out in two halves and suggest without a button".
- **Depends on:** T0a, T0c.
- **Size:** S.

#### ST1: Settings and About

- **Goal:** Settings as a plain list with the new Screen setting; About as plain sections with Back.
- **Context:**
  - Spec §4.5: Settings is a plain list:
    - Major and minor chords only (a switch);
    - **Screen**: Landscape, Portrait, Follow the phone;
    - Storage (analyses and megabytes);
    - About ›.
  - About keeps everything it has (version, licence, source, third-party notices, dataset credits, models), as plain sections.
  - Read `APP/ui/settings/SettingsScreen.kt` (`SettingsViewModel`, `SettingsContent`, `AboutContent`, `NOTICES`, `DATASET_CREDITS`, `storageLine`) and `APP/data/Settings.kt` (C2: `screenOrientation`, `setScreenOrientation`, `ScreenOrientation.label`).
  - Add `val screen: StateFlow<ScreenOrientation>` (initial LANDSCAPE) and `fun setScreen(value)` to `SettingsViewModel`.
  - Use `ScreenTopBar("Settings", onBack)` and `ScreenTopBar("About", onBack)`, and `SectionTitle` instead of `CardTitle`. The Screen choice is three `selectable` rows (`Role.RadioButton`) with a `RadioButton(onClick = null)`, tags per C6.
  - New content signatures:
    ```kotlin
    @Composable fun SettingsContent(majorMinorOnly: Boolean, onMajorMinorOnly: (Boolean) -> Unit, screen: ScreenOrientation,
        onScreen: (ScreenOrientation) -> Unit, storage: String?, onAbout: () -> Unit, onBack: () -> Unit = {})
    @Composable fun AboutContent(version: String, models: Map<String, String>, onSource: () -> Unit, onBack: () -> Unit = {})
    ```
- **Files:** Modify `APP/ui/settings/SettingsScreen.kt`. Create `JT/ui/settings/SettingsScreenTest.kt`.
- **Contracts used:** C1 (`SettingsScreen`, `AboutScreen`), C2, C3, C6, C9.
- **Steps:**
  - [ ] Write `SettingsScreenTest`:
    - the switch row `majorMinorOnly` is off, and clicking it calls `onMajorMinorOnly(true)`;
    - `screen-LANDSCAPE` is selected; clicking `Portrait` calls `onScreen(PORTRAIT)`;
    - the storage text is shown; `about` calls `onAbout`; `back` calls `onBack`;
    - About shows `Version 2.0.0`, the GPL text (substring `GPL-3.0-or-later`), `Source code` (which calls `onSource`), every `NOTICES` name, `DATASET_CREDITS` and a model line; `back` calls `onBack`;
    - `assertEveryControlNamed()` for both.
  - [ ] Implement.
- **Done when:** `./gradlew :app:testDebugUnitTest --tests 'com.arbaba.chordifymobile.ui.settings.SettingsScreenTest'` passes. Commit: "Make Settings a plain list with the Screen setting, and About plain sections".
- **Depends on:** T0a, T0c.
- **Size:** S.

#### N1: Navigation and orientation

- **Goal:** remove the bottom bar, make the Library the start destination with the other screens one tap away, and apply the Screen setting to the activity's orientation.
- **Context:**
  - Spec §4.1:
    - no bottom bar;
    - the routes are `library` (start), `analysis/{id}`, `quick`, `write`, `settings`, `about`;
    - Back returns to the Library.
  - Spec §4.6: the setting (C2) is applied by the activity whenever it starts and whenever it changes: `requestedOrientation = setting.activityOrientation`. Rotating recreates the activity, and the analysis screen keeps its state (view model and saved state).
  - Read `APP/MainActivity.kt`: the `Tab` enum, `ChordifyNavigation`, `importShared`, `sharedFile`.
  - Changes in `MainActivity.kt`:
    - delete `Tab` and the `Scaffold` with the `NavigationBar`;
    - `NavHost(navigation, startDestination = "library")` with:
      - `library` → `LibraryScreen(onOpen = { navigate("analysis/$it") }, onQuickChord = { navigate("quick") }, onSongwriter = { navigate("write") }, onSettings = { navigate("settings") })`;
      - `analysis/{id}` → `AnalysisScreen(id, onBack = { navigation.popBackStack() })`;
      - `quick` → `QuickChordScreen(onBack = …)`; `write` → `SongwriterScreen(onBack = …)`;
      - `settings` → `SettingsScreen(onAbout = { navigate("about") }, onBack = …)`; `about` → `AboutScreen(onBack = …)`.
    - Give the `NavHost` `Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)`, since there is no Scaffold any more.
    - In `onCreate`, before `setContent`, launch in `lifecycleScope`: `repeatOnLifecycle(Lifecycle.State.CREATED) { container.settings.screenOrientation.collect { requestedOrientation = it.activityOrientation } }`, with `container = (application as ChordifyApp).container`.
    - Remove the now-unused imports.
  - `DT/MainActivityTest.kt` (yours): replace `theFourTabsOpenTheirScreens` with `theScreenSettingSetsTheOrientation`:
    - `runBlocking { container.settings.setScreenOrientation(PORTRAIT) }`, then launch with `ActivityScenario.launch(MainActivity::class.java)` (drop the `createAndroidComposeRule` rule, or keep it for the other test) and assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT` in `onActivity`;
    - then set LANDSCAPE and assert `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (poll up to 5 s);
    - restore LANDSCAPE at the end.
    - Keep `sharedAndOpenedAudioIsFound`. The navigation test is I1's.
- **Files:** Modify `APP/MainActivity.kt`, `DT/MainActivityTest.kt`.
- **Contracts used:** C1, C2.
- **Steps:**
  - [ ] Write the device test.
  - [ ] Implement.
  - [ ] Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`, then the device test below. Stop the emulator afterwards.
- **Done when:** `./gradlew :app:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.MainActivityTest` passes (2 tests). Commit: "Make the Library home without a bottom bar, and apply the Screen setting".
- **Depends on:** T0c.
- **Size:** S.

### Wave 2

#### I1: Integration, cleanup and verification

- **Goal:** remove the replaced composables, check the whole screen end to end with the real components, check navigation on a device, update the docs, and verify everything on the emulator and the phone.
- **Context:**
  - After wave 1, nothing should use `NowPlayingCard`, `NextChordCard`, `CardTitle` (`APP/ui/analysis/NowAndNext.kt`) or `Timeline` and its private helpers (`APP/ui/analysis/Timeline.kt`).
  - The spec to verify against is `docs/superpowers/specs/2026-11-21-mobile-simple-landscape-ui-design.md`; its §5 table lists every feature and where it goes.
  - The phone is a Samsung Galaxy A30s (Android 11), id `RZ8MB0JLD3A`, reached with `/c/Users/H/AppData/Local/Android/Sdk/platform-tools/adb.exe`. It may not be connected; if not, skip the phone steps and say so.
  - Never run device tests on the phone: `connectedDebugAndroidTest` uninstalls the app and wipes the user's library. Install with `./gradlew :app:installDebug`, which keeps the data.
- **Files:**
  - Delete: `APP/ui/analysis/NowAndNext.kt`, `APP/ui/analysis/Timeline.kt`.
  - Create: `JT/ui/analysis/AnalysisFlowTest.kt`, `JT/ui/AccessibilityTest.kt`.
  - Modify: `DT/MainActivityTest.kt` (add the navigation test), `Mobile/README.md`, `docs/superpowers/specs/2026-11-17-mobile-on-device-design.md` (§6.2), `docs/superpowers/specs/2026-11-21-mobile-simple-landscape-ui-design.md` (status line).
- **Contracts used:** all.
- **Steps:**
  - [ ] Run `grep -rn "NowPlayingCard\|NextChordCard\|CardTitle\|Timeline(" Mobile/app/src`. It must find nothing outside the two files; fix any leftover in place only if it is a one-line import. Otherwise report which task missed it. Then delete the two files and compile.
  - [ ] `AnalysisFlowTest` (Robolectric, real components, `AnalysisContent` with `Fixtures.loop`):
    - LANDSCAPE: `nowName` is `C`; `clock.seek(loop.chords[1].start + 0.01)` makes it `G`;
    - choose capo `fret 5  ★ 100% open chords` from `capo`: `nowName` becomes `G` at time 0, and `soundsAs` reads `Capo 5 · sounds as C`;
    - `more` → `Show Roman numerals` makes `nowName` `I`;
    - each tab shows its panel tag (`chordPanel`, `leadSheet`, `circle`, `statsPanel`, `songPanel`);
    - `audio = MISSING` shows `noAudio`;
    - swiping `stage` left moves to the next chord;
    - clicking `seekBar` at 75% moves `clock.time` to about 6.0;
    - PORTRAIT shows the same parts stacked: `panelArea` is below `stageArea`.
  - [ ] `AccessibilityTest` (Robolectric):
    - `assertEveryControlNamed()` on `LibraryContent`, `AnalysisContent` (LANDSCAPE and PORTRAIT), `QuickChordContent`, `SongwriterContent`, `SettingsContent` and `AboutContent`;
    - `assertAnnounced()` on `AnalysisContent` and `QuickChordContent` with a result.
  - [ ] In `DT/MainActivityTest.kt` add `theLibraryOpensTheOtherScreens`, using `createAndroidComposeRule<MainActivity>()`. The setting defaults to landscape, so the labels show as text:
    - `Add` exists;
    - click `Quick chord` → `Record a chord`, then `back` → `Add`;
    - `Songwriter` → `Add chords, e.g. F G`, then `back`;
    - `Settings` → `Screen`, then `about` → `Source code`, then `back` twice → `Add`.
  - [ ] Docs:
    - in `Mobile/README.md`'s app description, say the analysis screen is a play-along stage with one panel beside it, landscape first;
    - in the 2026-11-17 spec §6.2, add one line at its top: "M7 replaces this layout: see `2026-11-21-mobile-simple-landscape-ui-design.md`.";
    - set the M7 spec's status line to "built, 2026-11-<day>".
  - [ ] Run the full verification below.
  - [ ] If the phone is connected:
    - `./gradlew :app:installDebug`, then start the app (`adb shell am start -n com.arbaba.chordifymobile/.MainActivity`);
    - take screenshots (`adb exec-out screencap -p > <scratch>.png`) of the Library and an analysis in landscape;
    - switch Settings → Screen to Portrait, screenshot again, then restore Landscape;
    - look at each screenshot and report.
- **Done when:**
  ```bash
  node --experimental-strip-types tools/web_goldens.mjs --check      # from the repository root
  cd Mobile
  ./gradlew :engine:testDebugUnitTest :app:testDebugUnitTest          # all JVM tests, including the Robolectric ones
  ./gradlew :app:lintDebug                                            # no errors
  ./gradlew :app:api34DebugAndroidTest                                # all app device tests
  ./gradlew :app:assembleDebug :app:bundleRelease
  ```
  All pass, and the emulator is stopped afterwards. Commit: "Remove the replaced composables, and check the M7 screens end to end". The orchestrator then offers to merge `mobile-m7-simple-ui` into `main`.
- **Depends on:** all wave-1 tasks.
- **Size:** M.

## 5. Execution waves

```
Wave 0 (parallel):  T0a  T0b  T0c
                      \   |   /
                    merge into mobile-m7-simple-ui
                          |
Wave 1 (parallel):  A1 A2 A3 A4 A5 A6 A7  L1 Q1 S1 ST1  N1
   needs:           T0a+T0b for A1-A3; T0a+T0b+T0c for A4-A7;
                    T0a+T0c for L1, Q1, S1, ST1; T0c for N1
                          |
                    merge into mobile-m7-simple-ui
                          |
Wave 2:                  I1
```

- **Wave 0:** T0a, T0b and T0c touch disjoint files.
- **Wave 1:** twelve tasks, each owning its files. Shared files are written only in wave 0 (`Settings.kt`, `Common.kt`, `AnalysisText.kt`, `Charts.kt`, the stub files' contracts) or in wave 2 (`MainActivityTest.kt`'s navigation test, deletions, docs). N1 can start as soon as T0c is merged. If the machine cannot hold twelve Gradle daemons (about 1.5–2 GB each), run wave 1 in batches of 4–6, putting A7, A2 and L1 first.
- **Critical path:** T0b (or T0a, both M) → A7 (M; also A2, A4 and L1) → I1 (M, dominated by the emulator run of about 10 minutes). That is three steps of about 30–45 minutes each.
- **Merge order** within a wave does not matter (disjoint files). After merging wave 1, run `./gradlew :app:testDebugUnitTest` once on the base branch before starting I1.

## 6. Final integration and verification

Task **I1** above: it deletes `NowAndNext.kt` and `Timeline.kt`, adds the end-to-end Robolectric test of the assembled analysis screen, an app-wide accessibility test and a device navigation test, updates the docs, and runs the full suite. That suite is:
- the web golden check;
- the engine's and the app's JVM tests;
- lint;
- all app device tests on the emulator;
- the debug and release builds.

It also installs the app on the phone with its data kept and checks both orientations.

## 7. Spec coverage

Section numbers refer to `2026-11-21-mobile-simple-landscape-ui-design.md`.

| Spec requirement | Task(s) |
|---|---|
| §2 engine, data, playback, music logic unchanged | all (no task edits them) |
| §2.1 / §4.6 Screen setting, default Landscape, applied at start and on change | T0c (C2), ST1 (UI), N1 (applied) |
| §2.2 / §4.1 no bottom bar; Library home; routes; Back | N1, L1 (top bar), Q1/S1/ST1 (Back), I1 (device test) |
| §2.3 "Major and minor only" only in Settings | L1 (removed), ST1 (kept) |
| §2.4 / §4.4 automatic Songwriter suggestions, no Suggest button | S1 (C8) |
| §2.5 starred suggested capo | T0b (`capoMenuLabel`), A6 |
| §2 same colours, no cards in cards | A1–A6, A5, Q1, S1, ST1 (SectionTitle, no Card) |
| §3.1 landscape 60/40 stage+strip beside panel, screen not scrolling; portrait stacked | A7, I1 (end-to-end) |
| §3.2 top bar: Back, title, real key · tempo · meter, capo chip and menu, ⋮ menu | A6, T0b (`songFacts`, `capoLabel`) |
| §3.3 stage: name, theory line, capo line, "?" badge and text, diagram, no chord, live region, swipe | A1, T0b (`theoryLine`, `soundsAsLine`) |
| §3.4 strip: 72 dp, 70 dp/s, playhead at a third, graphics layer, section markers, name and numeral, colours, stripes, faint low confidence, ✦, beats, waveform, seek | A2 |
| §3.5 transport: play/pause, time, seek bar (overview, sections, outline, playhead, seek), speed chip | A3, A7 (`visibleSeconds`) |
| §3.6 panel tabs, chosen tab kept | A7 (`PanelTab`, `rememberSaveable`) |
| §3.6 Chord panel (Now, countdown text, doubt, What comes next, actual next, without history) | A4 |
| §3.6 Sheet, Circle, Stats panels | A5 |
| §3.6 Song panel (facts, capo texts, legend, models, shortcuts) | A4, T0b (`songDetails`) |
| §3.7 no-audio banner and mismatch | A6, T0b (`FILE_MISSING` text) |
| §3.8 status screens, keyboard, screen on, recomposition only on chord change | A7 (status screens, keys, screen on), A2/A3/A4 (time read in small scopes) |
| §4.2 Library: top bar, 2/1 columns, rows, swipe fix, ＋ Add, recording bar, messages, empty line | L1 |
| §4.3 Quick chord two halves, one-line instruction | Q1 |
| §4.4 Songwriter two halves, key in top bar | S1 |
| §4.5 Settings list (switch, Screen, Storage, About), About plain | ST1 |
| §4.6 rotation keeps the analysis screen's state | A7 (`rememberSaveable` tab; view model holds player and clock), N1 |
| §5 every feature placed | I1 (end-to-end and accessibility tests against the §5 table) |
| §6 tests in both orientations, accessibility, emulator and phone | every wave-1 task (own tests at both sizes), I1 |

## 8. Assumptions

1. **Robolectric for JVM Compose tests** (a new test-only dependency, `org.robolectric:robolectric:4.14.1`, run at SDK 34). This lets twelve parallel tasks test their UI without competing for one emulator. If T0a cannot make it work, wave-1 tests become device tests run one task at a time, which serialises verification.
2. **What "landscape" means:** a layout is "landscape" when its available width exceeds its height (`BoxWithConstraints`), not when the device's configuration says so. This makes tests deterministic and handles split screen.
3. **Notation item wording:** the ⋮ notation item reads "Show Roman numerals" or "Show letters", the spec's "Letters / Roman numerals (shows the current one)" made concrete.
4. **The "?" badge** toggles an inline line under the theory line, rather than opening a pop-up.
5. **The strip** drops the old timeline's bar numbers (the spec lists beats and downbeats, not numbers). The numeral is drawn only on blocks wider than 60 dp, the name on blocks wider than 34 dp.
6. **The seek bar's outline width** is the number of seconds that fit in the stage column's width at 70 dp per second.
7. **The Songwriter's asking** moves from the view model's `update`/`setKey` into a `LaunchedEffect(chords, key)` in the content (C8), so it is testable and also covers "when the screen opens".
8. **Library view model:** it stops exposing `majorMinorOnly` and `setMajorMinorOnly`, but still reads the setting for new analyses.
9. **"Follow the phone"** maps to `SCREEN_ORIENTATION_USER`, which honours the phone's rotation lock. Landscape and Portrait use the sensor variants, so either side works.
10. **Status screens:** the analysing, failed and cancelled screens stay as today, with no new top bar; system Back leaves them.
11. **No-chord stage:** the stage shows `N.C.` (what `chordName` returns) as the name and "No chord" as the theory line, with no diagram.
12. **Git workflow:** parallel agents use one worktree each and never push; the orchestrator merges each wave into `mobile-m7-simple-ui` and later asks before merging into `main`.
