# Mobile M5a: the analysis screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a song in the Library opens its analysis. The header shows the key, tempo, number of chords and a capo picker with the suggested capo. Now playing and What comes next follow the music while Media3 plays the file, in letters or Roman numerals, with the web app's guitar shapes, capo logic and wording.

**Architecture:** `music/` ports the web app's `music.ts` and `capo.ts` to Kotlin. A golden file, computed by Node from those very files, checks the port. `playback/` has one `PlaybackClock`, a port of `clock.ts`. It follows a Media3 `ExoPlayer`, or runs on its own while the file is missing. `ui/analysis/` holds the screen: an `AnalysisViewModel` over the library's row and result, and composables for the header, the notices, Now playing, What comes next, a Canvas guitar diagram and the transport. The library can now watch one analysis, store its capo, and take its file again after a SHA-256 check.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2025.05.00, Material 3), Media3 ExoPlayer 1.5.1, Room 2.7, Navigation Compose 2.8; Node 22 for the web golden file; the engine (`:engine`) and the app shell from M1–M4.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md):
- §6.2, the analysis screen;
- §6.3, playback and drawing;
- §7.2, Compose UI tests of the screen states, the capo transposition and the chord names;
- §8, a moved, deleted or different file;
- §9, M5.

**M5 is split in two.** M5a, this plan, builds the screen itself:
- the header, the capo and the Letters/Roman toggle;
- Now playing and What comes next;
- playback;
- the analysing, failed and file-missing states.

M5b adds:
- the timeline with the waveform peaks;
- the Lead sheet, Circle of fifths and Stats tabs;
- export (`.lab` and `.json`);
- the landscape layout;
- hardware-keyboard shortcuts, and swiping Now playing to the previous or next chord.

## Global Constraints

- Everything from the earlier milestones' plans still holds: `minSdk 33`, JVM 17, package `com.arbaba.chordifymobile`, `export JAVA_HOME="/c/Program Files/Java/jdk-21"` and `export ANDROID_USER_HOME="D:/android-user"` before every Gradle command, golden files generated and never edited, plain imperative commit messages with the `Co-Authored-By` trailer.
- Everything runs on the phone: the app requests no network permission.
- The web app is the reference for what the screen shows and says. `chordify-frontend/src/lib/music.ts` and `capo.ts` are ported to Kotlin. The wording comes from `components/NowAndNext.tsx` and `pages/AnalysisPage.tsx`.
- `Mobile/app/src/test/resources/goldens/web.json` is written by `tools/web_goldens.mjs` from the web's own code and never edited. CI fails when it is stale.
- With a capo, the header keeps the song's real key; everything below it shows the shapes to play, as on the web.
- The capo goes from fret 0 to fret 9 (`MAX_CAPO`, as on the web). A capo is suggested only if it gives at least 10 percentage points more open chords than no capo.
- Playback speeds are 0.5×, 0.75×, 1× and 1.25×, with the pitch kept (spec §6.3).
- Compose state changes only when the chord changes. Only the countdown, the progress bar and the time text read the clock every frame (spec §6.3).
- Chord diagrams have content descriptions, and Now playing is a polite live region (spec §6.3).
- The screen stays on while playing (spec §6.2).
- A missing file (spec §8): the analysis still opens and its chords run silently. "Pick the file" attaches it again after a SHA-256 check. A different file is kept, with a warning.

## What was checked while writing this plan

Every file and test in this plan ran in a scratch copy of the repository:
- The app has 21 JVM tests and 26 device tests on the managed emulator, all passing. `web_goldens.mjs --check` passes, also on a checkout whose `web.json` has Windows line endings.
- The plan, applied task by task to a fresh copy of `main`, gives the same files as the scratch copy. After every task, the app's JVM tests and device tests compiled and the JVM tests passed, in the numbers each task gives.
- The Kotlin port gives the web's results for:
  - every chord type of the large vocabulary on every root;
  - the shape key of every key at every capo;
  - 320 transposed names and labels;
  - the three analysed golden songs: capo suggestion, open-chord share at every capo, names in both notations, and the whole result transposed for capo 1 and capo 3 (numbers to 1e-12).
- On the emulator, ExoPlayer plays a WAV, seeks, plays at half speed and reports the end.
- Every "Run it to make sure it fails" step was run, and fails with the errors it gives.

Applied to the fresh copy, the first generator failed: `Mobile/app/src/test/resources/goldens/` does not exist on `main` yet. The generator in this plan creates it.

Three test bugs turned up while checking, and the tests in this plan are the corrected ones:
- In a fake player, a `speed` property clashed on the JVM with the interface's `setSpeed`.
- An expected countdown counted a beat that had already passed.
- Some device-test methods returned a value, which JUnit refuses; `runBlocking<Unit>` fixes them.

**Choices this plan makes where the spec leaves room:**
- The header, the notices and the two cards scroll together, and the transport stays at the bottom. The spec's pinned player area, above the tabs, comes in M5b with the tabs.
- The Letters/Roman choice lasts while the screen is open. The capo is stored with the analysis, in the row's `capo` column from M4.
- Without its file, the clock runs on its own, as the web's clock does without audio.
- A different file picked again replaces the old one and plays, with the warning. The analysis is not run again.
- Each open analysis screen has one ExoPlayer, released when the screen goes. Playing in the background with a media notification is not part of M5.
- `music.formatTime`, the web's version, which shows negative times as 0:00, replaces M4's `ui.formatTime`.

## File structure

`.../app/` stands for `Mobile/app/src/main/kotlin/com/arbaba/chordifymobile/`.

| Path | Responsibility |
|---|---|
| `tools/web_goldens.mjs`, `Mobile/app/src/test/resources/goldens/web.json` | What the web's `music.ts` and `capo.ts` return, as a golden file |
| `.../app/music/Music.kt` | Note names, chord names in both notations, function hues, guitar shapes, `formatTime`, chord lookup by time, the circle of fifths |
| `.../app/music/Capo.kt` | `shapeKey`, transposition, `withCapo`, `openShare`, `suggestCapo` |
| `.../app/playback/PlaybackClock.kt` | `AudioPlayer`, `PlaybackClock`, `ExoAudioPlayer` |
| `.../app/data/Analysis.kt`, `.../app/data/Library.kt` | Watch one analysis; store its capo; attach its file again |
| `.../app/ui/Theme.kt` | The web's function colours, muted text and grid colour |
| `.../app/ui/analysis/AnalysisText.kt` | What the screen says: countdown, function, doubt, actual next, capo notices |
| `.../app/ui/analysis/GuitarDiagram.kt` | A chord box drawn on a Canvas |
| `.../app/ui/analysis/NowAndNext.kt` | The Now playing and What comes next cards |
| `.../app/ui/analysis/AnalysisViewModel.kt`, `.../app/ui/analysis/AnalysisScreen.kt` | The screen and its states |
| `.../app/MainActivity.kt`, `.../app/ui/library/LibraryScreen.kt` | A Library row opens its analysis |
| `.github/workflows/ci.yml`, `Mobile/README.md` | CI checks `web.json`; how to regenerate it |

---

### Task 1: The web app's music and capo logic as a golden file

**Files:**
- Create: `tools/web_goldens.mjs`
- Create (generated): `Mobile/app/src/test/resources/goldens/web.json`
- Modify: `.github/workflows/ci.yml`, `Mobile/README.md`

**Interfaces:**
- Consumes: `chordify-frontend/src/lib/music.ts` and `capo.ts`; the engine's golden file `Mobile/engine/src/test/resources/goldens/analysis.json.gz` (the server's results for `loop.wav`, `sevenths_stereo_22k.wav` and `waltz.wav`).
- Produces: `web.json`, with the keys `sources`, `max_capo`, `notes`, `shapes`, `keys`, `transposed`, `circle`, `times`, `chord_at` and `results`. Each entry of `results` has `file`, `result` (the server's result), `suggestion`, `open_share` (capo 0 to 7), `names` (`letters` and `roman` for each chord) and `with_capo` (the result transposed for capo 1 and 3).

- [ ] **Step 1: Write the generator**

Create `tools/web_goldens.mjs`:
```javascript
// Golden values for the Android app's port of the web app's music and capo logic
// (chordify-frontend/src/lib/music.ts and capo.ts), computed by those very files, so the app
// shows the same guitar shapes, capo suggestions and transposed names as the web app.
//
//   node --experimental-strip-types tools/web_goldens.mjs           # writes the golden file
//   node --experimental-strip-types tools/web_goldens.mjs --check   # fails if it is stale
//
// The analysis results it transposes come from the engine's golden file (analysis.json.gz).
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { gunzipSync } from 'node:zlib'

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..')
const LIB = join(REPO, 'chordify-frontend', 'src', 'lib')
const ANALYSIS = join(REPO, 'Mobile', 'engine', 'src', 'test', 'resources', 'goldens', 'analysis.json.gz')
const OUT = join(REPO, 'Mobile', 'app', 'src', 'test', 'resources', 'goldens', 'web.json')

// Node runs .ts files with their types stripped, but only with explicit .ts import paths.
const work = mkdtempSync(join(tmpdir(), 'chordify-web-'))
for (const name of ['music.ts', 'capo.ts']) {
  const text = readFileSync(join(LIB, name), 'utf8').replace(/from '\.\/music'/g, "from './music.ts'")
  writeFileSync(join(work, name), text)
}
const music = await import(pathToFileURL(join(work, 'music.ts')).href)
const capo = await import(pathToFileURL(join(work, 'capo.ts')).href)
rmSync(work, { recursive: true, force: true })

const SHARPS = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
const FLATS = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']
// the large vocabulary's chord types (Mobile/engine/.../vocab/Vocabulary.kt LARGE_QUALITIES)
const QUALITIES = ['maj', 'min', 'dim', 'aug', 'maj6', 'min6', '7', 'maj7', 'min7', 'dim7', 'hdim7', 'minmaj7', 'sus2', 'sus4']
const RESULTS = ['loop.wav', 'sevenths_stereo_22k.wav', 'waltz.wav']

const analysis = JSON.parse(gunzipSync(readFileSync(ANALYSIS)).toString('utf8'))
const results = RESULTS.map((file) => analysis.inputs.find((input) => input.file === file).result)

const shapes = []
for (let pc = 0; pc < 12; pc++) {
  for (const quality of QUALITIES) {
    const chord = { label: `${SHARPS[pc]}:${quality}`, root: SHARPS[pc], quality }
    shapes.push({ root: SHARPS[pc], quality, shape: music.guitarShape(chord), open: music.hasOpenShape(pc, quality),
                  minor: music.isMinorQuality(quality) })
  }
}

const keys = []
for (const mode of ['major', 'minor']) {
  for (let pc = 0; pc < 12; pc++) {
    for (const tonic of new Set([SHARPS[pc], FLATS[pc]])) {
      for (let fret = 0; fret <= capo.MAX_CAPO; fret++) keys.push({ tonic, mode, capo: fret, shapes: capo.shapeKey({ tonic, mode }, fret) })
    }
  }
}

const DISPLAYS = ['C', 'Cmaj7/E', 'F#m', 'Bbm7b5', 'Ab7', 'G/B', 'Ebmaj7', 'C#dim7', 'N.C.', 'Dsus4', 'E+', 'Abm6']
const LABELS = ['C:maj7/3', 'F#:min', 'Bb:hdim7', 'N', 'X', 'Ab:7', 'E:aug', 'Db:sus2(b7)']
const transposed = []
for (const shift of [-9, -5, -3, -2, -1, 0, 1, 7]) {
  for (const flats of [false, true]) {
    for (const display of DISPLAYS) transposed.push({ kind: 'display', value: display, shift, flats, out: capo.transposeDisplay(display, shift, flats) })
    for (const label of LABELS) transposed.push({ kind: 'label', value: label, shift, flats, out: capo.transposeLabel(label, shift, flats) })
  }
}

const starts = [0, 1.5, 3, 4.25, 8]
const golden = {
  sources: ['chordify-frontend/src/lib/music.ts', 'chordify-frontend/src/lib/capo.ts'],
  max_capo: capo.MAX_CAPO,
  notes: ['C', 'C#', 'Db', 'B#', 'Cb', 'Fb', 'E#', 'Bbb', 'F##', 'G', 'Ab', 'A#'].map((name) => ({ name, pc: music.noteToPc(name) })),
  shapes,
  keys,
  transposed,
  circle: [false, true].flatMap((minor) => Array.from({ length: 12 }, (_, pc) => ({ pc, minor, index: music.circleIndex(pc, minor) }))),
  times: [0, 7.9, 59.99, 60, 61.5, 899.4, -3].map((seconds) => ({ seconds, text: music.formatTime(seconds) })),
  chord_at: { starts, cases: [-1, 0, 0.5, 1.5, 2.99, 3, 4.3, 8, 100].map((t) => ({ t, index: music.chordIndexAt(starts, t) })) },
  results: results.map((result) => ({
    file: result.source.filename,
    result,
    suggestion: capo.suggestCapo(result.chords),
    open_share: Array.from({ length: 8 }, (_, fret) => capo.openShare(result.chords, fret)),
    names: result.chords.map((chord) => ({ letters: music.chordName(chord, 'letters'), roman: music.chordName(chord, 'roman') })),
    with_capo: [1, 3].map((fret) => ({ capo: fret, result: capo.withCapo(result, fret) })),
  })),
}

const text = JSON.stringify(golden) + '\n'
if (process.argv.includes('--check')) {
  // a Windows checkout with core.autocrlf may have turned the final newline into CRLF
  if (readFileSync(OUT, 'utf8').replaceAll('\r\n', '\n') !== text) {
    console.error(`${OUT} is stale: run node --experimental-strip-types tools/web_goldens.mjs and commit it`)
    process.exit(1)
  }
  console.log('web.json is up to date')
} else {
  mkdirSync(dirname(OUT), { recursive: true })
  writeFileSync(OUT, text)
  console.log(`web.json: ${Math.round(text.length / 1024)} KB`)
}
```

- [ ] **Step 2: Check that a missing golden file fails**

Run, from the repository root: `node --experimental-strip-types tools/web_goldens.mjs --check`
Expected: exit code 1 with `Error: ENOENT: no such file or directory, open '...web.json'`. Node also prints an `ExperimentalWarning` about type stripping, here and in every later run; it is harmless.

- [ ] **Step 3: Generate it**

Run: `node --experimental-strip-types tools/web_goldens.mjs`
Expected: `web.json: 122 KB`.

Run: `node --experimental-strip-types tools/web_goldens.mjs --check`
Expected: `web.json is up to date`.

Run: `node -e "console.log(JSON.stringify(require('./Mobile/app/src/test/resources/goldens/web.json').results.map(r => [r.file, r.suggestion])))"`
Expected: `[["loop.wav",{"capo":5,"open":1}],["sevenths_stereo_22k.wav",{"capo":0,"open":1}],["waltz.wav",{"capo":5,"open":1}]]`.

- [ ] **Step 4: Check it in CI**

In `.github/workflows/ci.yml`, in the `web` job, replace
```yaml
      - run: npm run lint
      - run: npm run build
```
with
```yaml
      - run: npm run lint
      - run: npm run build
      - name: The Android app's golden file of this code is up to date
        run: node --experimental-strip-types ../tools/web_goldens.mjs --check
```
The `web` job runs in `chordify-frontend`, so the script is `../tools/web_goldens.mjs`.

- [ ] **Step 5: Say how to regenerate it**

In `Mobile/README.md`, replace
```markdown
The engine's own tests are in [`engine/README.md`](engine/README.md).
```
with
````markdown
`app/src/test/resources/goldens/web.json` holds what the web app's own `music.ts` and `capo.ts`
return for a set of inputs, so the app's port can be checked against them. After changing
either file, regenerate it from the repository root (Node 22) and commit it:

```bash
node --experimental-strip-types tools/web_goldens.mjs
```

The engine's own tests are in [`engine/README.md`](engine/README.md).
````

- [ ] **Step 6: Commit**

```bash
git add tools/web_goldens.mjs Mobile/app/src/test/resources/goldens/web.json .github/workflows/ci.yml Mobile/README.md
git commit -m "Record what the web app's music and capo code returns, for the Android port" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Chord names, guitar shapes and the capo in Kotlin

**Files:**
- Modify: `Mobile/app/build.gradle`
- Create: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music/WebGoldens.kt`
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music/MusicGoldenTest.kt`
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music/CapoGoldenTest.kt`
- Create: `.../app/music/Music.kt`, `.../app/music/Capo.kt`

**Interfaces:**
- Consumes: `web.json` (Task 1); the engine's `AnalysisResult`, `ChordSegment`, `ServerJson` (`com.arbaba.chordifymobile.engine.model`).
- Produces, in `com.arbaba.chordifymobile.music`:
  - `fun noteToPc(name: String): Int`, `fun isNoChord(label: String): Boolean`;
  - `enum class Notation { LETTERS, ROMAN }`, `fun chordName(chord: ChordSegment, notation: Notation): String`;
  - `enum class FunctionHue { TONIC, SUBDOMINANT, DOMINANT, BORROWED, NONE }`, `fun functionHue(function: String?): FunctionHue`, `val FUNCTION_NAMES: Map<String, String>`, `fun isMinorQuality(quality: String?): Boolean`;
  - `typealias Shape = List<Int>` (six strings, low E first, -1 muted), `fun hasOpenShape(pc: Int, quality: String): Boolean`, `fun guitarShape(root: String?, quality: String?): Shape?`, `fun guitarShape(chord: ChordSegment): Shape?`;
  - `fun formatTime(seconds: Double): String`, `fun chordIndexAt(starts: List<Double>, t: Double): Int`;
  - `val MAJOR_CIRCLE`, `val MINOR_CIRCLE`, `fun circleIndex(pc: Int, minor: Boolean): Int`;
  - `const val MAX_CAPO = 9`, `data class ShapeKey(tonic, mode, flats)`, `fun shapeKey(tonic: String, mode: String, capo: Int): ShapeKey`;
  - `fun transposeNote`, `fun transposeDisplay`, `fun transposeLabel` (`(String, semitones: Int, flats: Boolean) -> String`);
  - `fun withCapo(result: AnalysisResult, capo: Int): AnalysisResult`;
  - `data class CapoSuggestion(val capo: Int, val open: Double)`, `fun openShare(chords: List<ChordSegment>, capo: Int): Double`, `fun suggestCapo(chords: List<ChordSegment>): CapoSuggestion`;
  - in the JVM tests: `object WebGoldens { val json: JsonObject }` and `fun assertSameJson(message, expected, actual)`.

- [ ] **Step 1: Let the JVM tests read JSON**

In `Mobile/app/build.gradle`, replace
```groovy
    testImplementation libs.junit
```
with
```groovy
    testImplementation libs.junit
    testImplementation libs.kotlinx.serialization.json
```

- [ ] **Step 2: Write the failing tests**

Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music/WebGoldens.kt`:
```kotlin
package com.arbaba.chordifymobile.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import kotlin.math.abs

/** web.json, written by tools/web_goldens.mjs from the web app's music.ts and capo.ts. */
object WebGoldens {
    val json: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("goldens/web.json") ?: error("goldens/web.json is missing")
        Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }
}

/** The same JSON, numbers compared as numbers (JavaScript writes 2 where Kotlin writes 2.0). */
fun assertSameJson(message: String, expected: JsonElement, actual: JsonElement) {
    fun walk(path: String, e: JsonElement, a: JsonElement) {
        when (e) {
            is JsonObject -> {
                if (a !is JsonObject) fail("$message at $path: expected an object, got $a")
                a as JsonObject
                assertEquals("$message at $path: keys", e.keys, a.keys)
                for (name in e.keys) walk("$path.$name", e.getValue(name), a.getValue(name))
            }
            is JsonArray -> {
                if (a !is JsonArray) fail("$message at $path: expected a list, got $a")
                a as JsonArray
                assertEquals("$message at $path: length", e.size, a.size)
                for (i in e.indices) walk("$path[$i]", e[i], a[i])
            }
            is JsonNull -> assertEquals("$message at $path", JsonNull, a)
            is JsonPrimitive -> {
                if (a !is JsonPrimitive || a is JsonNull) fail("$message at $path: expected $e, got $a")
                a as JsonPrimitive
                val x = e.doubleOrNull
                val y = a.doubleOrNull
                if (e.isString || x == null || y == null) assertEquals("$message at $path", e.content, a.content)
                else if (abs(x - y) > 1e-12) fail("$message at $path: expected $x, got $y")
            }
        }
    }
    walk("$", expected, actual)
}
```
Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music/MusicGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.music

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicGoldenTest {
    private val golden = WebGoldens.json

    private fun JsonObject.list(name: String) = getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun noteNamesAreReadAsTheWebReadsThem() {
        for (note in golden.list("notes")) {
            assertEquals(note.text("name"), note.getValue("pc").jsonPrimitive.int, noteToPc(note.text("name")))
        }
    }

    @Test
    fun everyChordTypeInEveryKeyHasTheWebsGuitarShape() {
        val shapes = golden.list("shapes")
        assertEquals(12 * 14, shapes.size)
        for (row in shapes) {
            val name = "${row.text("root")}:${row.text("quality")}"
            val shape = row.getValue("shape")
            val expected = if (shape is JsonNull) null else shape.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(name, expected, guitarShape(row.text("root"), row.text("quality")))
            assertEquals(name, row.getValue("open").jsonPrimitive.boolean, hasOpenShape(noteToPc(row.text("root")), row.text("quality")))
            assertEquals(name, row.getValue("minor").jsonPrimitive.boolean, isMinorQuality(row.text("quality")))
        }
    }

    @Test
    fun theCapoMovesTheKeyAsOnTheWeb() {
        for (row in golden.list("keys")) {
            val shapes = row.getValue("shapes").jsonObject
            assertEquals("$row", ShapeKey(shapes.text("tonic"), shapes.text("mode"), shapes.getValue("flats").jsonPrimitive.boolean),
                         shapeKey(row.text("tonic"), row.text("mode"), row.getValue("capo").jsonPrimitive.int))
        }
    }

    @Test
    fun namesAndLabelsAreTransposedAsOnTheWeb() {
        for (row in golden.list("transposed")) {
            val shift = row.getValue("shift").jsonPrimitive.int
            val flats = row.getValue("flats").jsonPrimitive.boolean
            val value = row.text("value")
            val actual = if (row.text("kind") == "display") transposeDisplay(value, shift, flats) else transposeLabel(value, shift, flats)
            assertEquals("$row", row.text("out"), actual)
        }
    }

    @Test
    fun theCircleTimesAndChordLookupMatchTheWeb() {
        for (row in golden.list("circle")) {
            assertEquals("$row", row.getValue("index").jsonPrimitive.int,
                         circleIndex(row.getValue("pc").jsonPrimitive.int, row.getValue("minor").jsonPrimitive.boolean))
        }
        for (row in golden.list("times")) assertEquals("$row", row.text("text"), formatTime(row.getValue("seconds").jsonPrimitive.double))
        val lookup = golden.getValue("chord_at").jsonObject
        val starts = lookup.getValue("starts").jsonArray.map { it.jsonPrimitive.double }
        for (row in lookup.list("cases")) {
            assertEquals("$row", row.getValue("index").jsonPrimitive.int, chordIndexAt(starts, row.getValue("t").jsonPrimitive.double))
        }
        assertEquals(golden.getValue("max_capo").jsonPrimitive.int, MAX_CAPO)
    }
}
```
Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music/CapoGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.music

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CapoGoldenTest {
    private val cases = WebGoldens.json.getValue("results").jsonArray.map { it.jsonObject }

    private fun result(case: JsonObject): AnalysisResult =
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), case.getValue("result"))

    @Test
    fun theCapoSuggestionIsTheWebs() {
        assertEquals(3, cases.size)
        for (case in cases) {
            val chords = result(case).chords
            val name = case.getValue("file").jsonPrimitive.content
            val suggestion = case.getValue("suggestion").jsonObject
            assertEquals(name, CapoSuggestion(suggestion.getValue("capo").jsonPrimitive.int, suggestion.getValue("open").jsonPrimitive.double),
                         suggestCapo(chords))
            val shares = case.getValue("open_share").jsonArray.map { it.jsonPrimitive.double }
            for ((capo, share) in shares.withIndex()) assertEquals("$name capo $capo", share, openShare(chords, capo), 1e-12)
        }
    }

    @Test
    fun chordsAreNamedAsOnTheWebInBothNotations() {
        for (case in cases) {
            val names = case.getValue("names").jsonArray.map { it.jsonObject }
            for ((chord, name) in result(case).chords.zip(names)) {
                assertEquals(name.getValue("letters").jsonPrimitive.content, chordName(chord, Notation.LETTERS))
                assertEquals(name.getValue("roman").jsonPrimitive.content, chordName(chord, Notation.ROMAN))
            }
        }
    }

    @Test
    fun withACapoTheResultShowsTheShapesAsOnTheWeb() {
        for (case in cases) {
            for (row in case.getValue("with_capo").jsonArray.map { it.jsonObject }) {
                val capo = row.getValue("capo").jsonPrimitive.int
                val actual = ServerJson.encodeToJsonElement(AnalysisResult.serializer(), withCapo(result(case), capo))
                assertSameJson("${case.getValue("file")} capo $capo", row.getValue("result"), actual)
            }
        }
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest`
Expected: `:app:compileDebugUnitTestKotlin FAILED`, starting with `Unresolved reference 'CapoSuggestion'.`, `Unresolved reference 'suggestCapo'.`, `Unresolved reference 'openShare'.` and `Unresolved reference 'chordName'.`; the other names above follow.

- [ ] **Step 4: Port music.ts**

Create `.../app/music/Music.kt`:
```kotlin
package com.arbaba.chordifymobile.music

import com.arbaba.chordifymobile.engine.model.ChordSegment
import kotlin.math.floor

// Port of chordify-frontend/src/lib/music.ts: chord names in both notations, harmonic
// functions, guitar shapes and the time helpers of the analysis screen. web.json, written by
// tools/web_goldens.mjs from the web app's own code, checks the port.

private val NATURALS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

/** The web's noteToPc: lenient, as names here come from the engine. */
fun noteToPc(name: String): Int {
    var pc = NATURALS[name.firstOrNull()] ?: 0
    for (accidental in name.drop(1)) pc += when (accidental) { '#' -> 1; 'b' -> -1; else -> 0 }
    return Math.floorMod(pc, 12)
}

fun isNoChord(label: String): Boolean = label == "N" || label == "X"

enum class Notation { LETTERS, ROMAN }

/** What a chord is called on screen: its name, or its Roman numeral. */
fun chordName(chord: ChordSegment, notation: Notation): String = when {
    isNoChord(chord.label) -> "N.C."
    notation == Notation.ROMAN -> chord.roman ?: chord.display
    else -> chord.display
}

/** A chord's harmonic function, for its colour (the web's --tonic, --subdominant, ... tokens). */
enum class FunctionHue { TONIC, SUBDOMINANT, DOMINANT, BORROWED, NONE }

fun functionHue(function: String?): FunctionHue = when (function) {
    "tonic" -> FunctionHue.TONIC
    "subdominant" -> FunctionHue.SUBDOMINANT
    "dominant" -> FunctionHue.DOMINANT
    "borrowed" -> FunctionHue.BORROWED
    else -> FunctionHue.NONE
}

val FUNCTION_NAMES = mapOf("tonic" to "Tonic", "subdominant" to "Subdominant", "dominant" to "Dominant",
                           "borrowed" to "Outside the key")

fun isMinorQuality(quality: String?): Boolean =
    quality != null && (quality.startsWith("min") || quality == "dim" || quality == "hdim7")

/** Guitar fingering: frets per string E A D G B e; -1 muted, 0 open. */
typealias Shape = List<Int>

private val OPEN_SHAPES: Map<String, Shape> = linkedMapOf(
    "C:maj" to listOf(-1, 3, 2, 0, 1, 0), "D:maj" to listOf(-1, -1, 0, 2, 3, 2), "E:maj" to listOf(0, 2, 2, 1, 0, 0),
    "G:maj" to listOf(3, 2, 0, 0, 0, 3), "A:maj" to listOf(-1, 0, 2, 2, 2, 0), "A:min" to listOf(-1, 0, 2, 2, 1, 0),
    "D:min" to listOf(-1, -1, 0, 2, 3, 1), "E:min" to listOf(0, 2, 2, 0, 0, 0), "C:7" to listOf(-1, 3, 2, 3, 1, 0),
    "D:7" to listOf(-1, -1, 0, 2, 1, 2), "E:7" to listOf(0, 2, 0, 1, 0, 0), "G:7" to listOf(3, 2, 0, 0, 0, 1),
    "A:7" to listOf(-1, 0, 2, 0, 2, 0), "B:7" to listOf(-1, 2, 1, 2, 0, 2), "A:min7" to listOf(-1, 0, 2, 0, 1, 0),
    "E:min7" to listOf(0, 2, 0, 0, 0, 0), "D:min7" to listOf(-1, -1, 0, 2, 1, 1), "C:maj7" to listOf(-1, 3, 2, 0, 0, 0),
    "F:maj7" to listOf(-1, -1, 3, 2, 1, 0), "E:maj7" to listOf(0, 2, 1, 1, 0, 0), "D:maj7" to listOf(-1, -1, 0, 2, 2, 2),
    "G:maj7" to listOf(3, 2, 0, 0, 0, 2), "A:maj7" to listOf(-1, 0, 2, 1, 2, 0), "A:sus4" to listOf(-1, 0, 2, 2, 3, 0),
    "D:sus4" to listOf(-1, -1, 0, 2, 3, 3), "E:sus4" to listOf(0, 2, 2, 2, 0, 0), "A:sus2" to listOf(-1, 0, 2, 2, 0, 0),
    "D:sus2" to listOf(-1, -1, 0, 2, 3, 0),
)

// Movable shapes relative to the root fret (null = muted); E-shapes put the root on string 6,
// A-shapes on string 5. Every chord type of the large vocabulary has at least one.
private val X: Int? = null
private val E_SHAPES: Map<String, List<Int?>> = mapOf(
    "maj" to listOf(0, 2, 2, 1, 0, 0), "min" to listOf(0, 2, 2, 0, 0, 0), "7" to listOf(0, 2, 0, 1, 0, 0),
    "min7" to listOf(0, 2, 0, 0, 0, 0), "maj7" to listOf(0, X, 1, 1, 0, X), "sus4" to listOf(0, 2, 2, 2, 0, 0),
    "maj6" to listOf(0, X, -1, 1, 0, X), "min6" to listOf(0, X, -1, 0, 0, X), "dim" to listOf(0, 1, 2, 0, X, X),
    "aug" to listOf(0, X, 2, 1, 1, 0), "dim7" to listOf(0, X, -1, 0, -1, X), "hdim7" to listOf(0, X, 0, 0, -1, X),
    "minmaj7" to listOf(0, X, 1, 0, 0, X),
)
private val A_SHAPES: Map<String, List<Int?>> = mapOf(
    "maj" to listOf(X, 0, 2, 2, 2, 0), "min" to listOf(X, 0, 2, 2, 1, 0), "7" to listOf(X, 0, 2, 0, 2, 0),
    "min7" to listOf(X, 0, 2, 0, 1, 0), "maj7" to listOf(X, 0, 2, 1, 2, 0), "sus4" to listOf(X, 0, 2, 2, 3, 0),
    "sus2" to listOf(X, 0, 2, 2, 0, 0), "maj6" to listOf(X, 0, 2, 2, 2, 2), "min6" to listOf(X, 0, 2, 2, 1, 2),
    "dim" to listOf(X, 0, 1, 2, 1, X), "aug" to listOf(X, 0, 3, 2, 2, 1), "dim7" to listOf(X, 0, 1, 2, 1, 2),
    "hdim7" to listOf(X, 0, 1, 0, 1, X), "minmaj7" to listOf(X, 0, 2, 1, 1, 0),
)

private fun openShape(pc: Int, quality: String): Shape? =
    OPEN_SHAPES.entries.firstOrNull { (label, _) ->
        val (root, q) = label.split(":")
        noteToPc(root) == pc && q == quality
    }?.value

/** Whether a chord (root pitch class and quality) has an open-position shape. */
fun hasOpenShape(pc: Int, quality: String): Boolean = openShape(pc, quality) != null

/** A playable guitar shape for a chord, or null when there is none for its quality. */
fun guitarShape(root: String?, quality: String?): Shape? {
    if (root.isNullOrEmpty() || quality.isNullOrEmpty()) return null
    val pc = noteToPc(root)
    openShape(pc, quality)?.let { return it }
    val eFret = Math.floorMod(pc - 4, 12).let { if (it == 0) 12 else it }
    val aFret = Math.floorMod(pc - 9, 12).let { if (it == 0) 12 else it }
    val (fret, shape) = listOf(eFret to E_SHAPES[quality], aFret to A_SHAPES[quality])
        .filter { it.second != null }.minByOrNull { it.first } ?: return null
    return shape!!.map { if (it == null) -1 else it + fret }
}

fun guitarShape(chord: ChordSegment): Shape? = guitarShape(chord.root, chord.quality)

/** m:ss, as the web shows times. */
fun formatTime(seconds: Double): String {
    val s = maxOf(0.0, seconds)
    return "${floor(s / 60).toInt()}:${floor(s % 60).toInt().toString().padStart(2, '0')}"
}

/** Index of the chord sounding at time [t] (chords sorted by start). */
fun chordIndexAt(starts: List<Double>, t: Double): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) shr 1
        if (starts[mid] <= t) lo = mid else hi = mid - 1
    }
    return maxOf(0, lo)
}

val MAJOR_CIRCLE = listOf("C", "G", "D", "A", "E", "B", "F#", "Db", "Ab", "Eb", "Bb", "F")
val MINOR_CIRCLE = listOf("Am", "Em", "Bm", "F#m", "C#m", "G#m", "D#m", "Bbm", "Fm", "Cm", "Gm", "Dm")

/** Position on the circle of fifths (0 = C / Am) of a pitch class. */
fun circleIndex(pc: Int, minor: Boolean): Int = ((if (minor) (pc + 3) % 12 else pc) * 7) % 12
```

- [ ] **Step 5: Port capo.ts**

Create `.../app/music/Capo.kt`:
```kotlin
package com.arbaba.chordifymobile.music

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment

// Port of chordify-frontend/src/lib/capo.ts. With a capo on fret N, every chord is played with
// the shape of the chord N semitones lower: a song in Ab with the capo on 1 is played with G
// shapes. The app shows those shapes; Roman numerals and functions are relative to the key, so
// they do not change.

const val MAX_CAPO = 9

private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

// The same key signatures as the server (chordify_core/theory.py): F# major, not Gb
private val FLAT_MAJOR = setOf(5, 10, 3, 8, 1)
private val FLAT_MINOR = setOf(2, 7, 0, 5, 10, 3)

private val NOTE = Regex("^([A-G][#b]*)")
private val DISPLAY = Regex("^([A-G][#b]*)(.*?)(?:/([A-G][#b]*))?$")

private fun pcName(pc: Int, flats: Boolean) = (if (flats) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(pc, 12)]

/** The key the shapes are in: the song's key moved down by the capo, spelled as usual. */
data class ShapeKey(val tonic: String, val mode: String, val flats: Boolean)

fun shapeKey(tonic: String, mode: String, capo: Int): ShapeKey {
    val pc = Math.floorMod(noteToPc(tonic) - capo, 12)
    val flats = pc in (if (mode == "minor") FLAT_MINOR else FLAT_MAJOR)
    return ShapeKey(pcName(pc, flats), mode, flats)
}

fun transposeNote(name: String, semitones: Int, flats: Boolean): String = pcName(noteToPc(name) + semitones, flats)

/** "Cmaj7/E" -> "Bmaj7/D#" for -1; the suffix is kept, "N.C." is left alone. */
fun transposeDisplay(display: String, semitones: Int, flats: Boolean): String {
    val match = DISPLAY.find(display) ?: return display
    val root = match.groupValues[1]
    val suffix = match.groupValues[2]
    val bass = match.groups[3]?.value
    return transposeNote(root, semitones, flats) + suffix + (if (bass.isNullOrEmpty()) "" else "/" + transposeNote(bass, semitones, flats))
}

/** Harte labels: "C:maj7/3" -> "B:maj7/3" (the bass is an interval, so it stays). */
fun transposeLabel(label: String, semitones: Int, flats: Boolean): String {
    val match = NOTE.find(label) ?: return label
    val root = match.groupValues[1]
    return transposeNote(root, semitones, flats) + label.substring(root.length)
}

private fun transposeChord(chord: ChordSegment, shift: Int, flats: Boolean): ChordSegment = chord.copy(
    label = transposeLabel(chord.label, shift, flats),
    display = transposeDisplay(chord.display, shift, flats),
    root = chord.root?.takeIf { it.isNotEmpty() }?.let { transposeNote(it, shift, flats) } ?: chord.root,
    bass = chord.bass?.takeIf { it.isNotEmpty() }?.let { transposeNote(it, shift, flats) } ?: chord.bass,
    // "Ab Mixolydian" -> "G Mixolydian": the leading note moves like a label's root
    scaleHint = chord.scaleHint?.takeIf { it.isNotEmpty() }?.let { transposeLabel(it, shift, flats) } ?: chord.scaleHint,
    alternatives = chord.alternatives.map {
        it.copy(label = transposeLabel(it.label, shift, flats), display = transposeDisplay(it.display, shift, flats))
    },
    next = chord.next.map {
        it.copy(label = transposeLabel(it.label, shift, flats), display = transposeDisplay(it.display, shift, flats))
    },
)

/** The analysis as it looks with the capo on fret [capo]: every chord name is the shape to play. */
fun withCapo(result: AnalysisResult, capo: Int): AnalysisResult {
    if (capo == 0) return result
    val global = result.key.global
    val key = shapeKey(global.tonic, global.mode, capo)
    val shift = -capo
    fun display(name: String) = transposeDisplay(name, shift, key.flats)
    return result.copy(
        key = result.key.copy(
            global = global.copy(tonic = key.tonic),
            segments = result.key.segments.map { it.copy(tonic = transposeNote(it.tonic, shift, key.flats)) },
        ),
        chords = result.chords.map { transposeChord(it, shift, key.flats) },
        bars = result.bars.map { bar -> bar.copy(chords = bar.chords.map(::display)) },
        summary = result.summary.copy(timeShare = result.summary.timeShare.map { it.copy(display = display(it.display)) }),
    )
}

data class CapoSuggestion(val capo: Int, val open: Double)

/** Share of the chord time (N.C. excluded) that has an open-position shape with the capo on [capo]. */
fun openShare(chords: List<ChordSegment>, capo: Int): Double {
    var open = 0.0
    var total = 0.0
    for (chord in chords) {
        val root = chord.root
        val quality = chord.quality
        if (root.isNullOrEmpty() || quality.isNullOrEmpty()) continue
        val length = chord.end - chord.start
        total += length
        if (hasOpenShape(Math.floorMod(noteToPc(root) - capo, 12), quality)) open += length
    }
    return if (total != 0.0) open / total else 0.0
}

/** The capo position (0-7) whose shapes are most often open chords, the lowest on a tie. No
 *  capo unless one gives at least 10% more of the song in open chords. */
fun suggestCapo(chords: List<ChordSegment>): CapoSuggestion {
    val none = CapoSuggestion(0, openShare(chords, 0))
    var best = none
    for (capo in 1..7) {
        val open = openShare(chords, capo)
        if (open > best.open + 1e-9) best = CapoSuggestion(capo, open)
    }
    return if (best.open >= none.open + 0.1) best else none
}
```

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 14 tests: `MusicGoldenTest` 5, `CapoGoldenTest` 3 and M4's 6.

- [ ] **Step 7: Commit**

```bash
git add Mobile/app/build.gradle Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/music Mobile/app/src/main/kotlin/com/arbaba/chordifymobile/music
git commit -m "Port the web app's chord names, guitar shapes and capo to Kotlin" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: One playback clock over Media3

**Files:**
- Modify: `Mobile/gradle/libs.versions.toml`, `Mobile/app/build.gradle`
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/playback/PlaybackClockTest.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/playback/ExoAudioPlayerDeviceTest.kt`
- Create: `.../app/playback/PlaybackClock.kt`

**Interfaces:**
- Consumes: Media3 ExoPlayer.
- Produces, in `com.arbaba.chordifymobile.playback`:
  - `interface AudioPlayer { val positionSeconds: Double; var onEnded: (() -> Unit)?; fun play(); fun pause(); fun seekTo(seconds: Double); fun setSpeed(speed: Double); fun release() }`;
  - `class PlaybackClock(val duration: Double, nanoTime: () -> Long = System::nanoTime)` with `time`, `playing` and `rate` (`StateFlow`s), `player`, `attach(player: AudioPlayer?)`, `tick()`, `play()`, `pause()`, `toggle()`, `seek(seconds: Double)`, `setRate(rate: Double)`, and `PlaybackClock.SPEEDS = listOf(0.5, 0.75, 1.0, 1.25)`;
  - `class ExoAudioPlayer(context: Context, uri: Uri) : AudioPlayer`, made, used and released on the main thread.

- [ ] **Step 1: Add Media3**

In `Mobile/gradle/libs.versions.toml`, replace
```toml
datastore = "1.1.4"
```
with
```toml
datastore = "1.1.4"
media3 = "1.5.1"
```
then replace
```toml
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```
with
```toml
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
```
In `Mobile/app/build.gradle`, replace
```groovy
    implementation libs.datastore.preferences
```
with
```groovy
    implementation libs.datastore.preferences
    implementation libs.media3.exoplayer
```

- [ ] **Step 2: Write the failing tests**

Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/playback/PlaybackClockTest.kt`:
```kotlin
package com.arbaba.chordifymobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {
    private var now = 0L

    private class FakePlayer : AudioPlayer {
        var position = 0.0
        var currentSpeed = 1.0
        var playing = false
        override val positionSeconds get() = position
        override var onEnded: (() -> Unit)? = null
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(seconds: Double) { position = seconds }
        override fun setSpeed(speed: Double) { currentSpeed = speed }
        override fun release() {}
    }

    private fun clock(duration: Double) = PlaybackClock(duration) { now }

    private fun advance(seconds: Double) { now += (seconds * 1e9).toLong() }

    @Test
    fun withoutAudioTheClockRunsAtItsRateAndStopsAtTheEnd() {
        val clock = clock(10.0)
        clock.play()
        advance(2.0)
        clock.tick()
        assertEquals(2.0, clock.time.value, 1e-9)
        clock.setRate(0.5)
        advance(2.0)
        clock.tick()
        assertEquals(3.0, clock.time.value, 1e-9)
        advance(100.0)
        clock.tick()
        assertEquals(10.0, clock.time.value, 1e-9)
        assertFalse(clock.playing.value)
    }

    @Test
    fun playingAtTheEndStartsAgain() {
        val clock = clock(10.0)
        clock.seek(9.99)
        clock.play()
        assertEquals(0.0, clock.time.value, 0.0)
    }

    @Test
    fun aPausedClockDoesNotMove() {
        val clock = clock(10.0)
        clock.play()
        clock.pause()
        advance(3.0)
        clock.tick()
        assertEquals(0.0, clock.time.value, 0.0)
    }

    @Test
    fun withAPlayerTheClockFollowsItsPosition() {
        val clock = clock(10.0)
        clock.setRate(0.75)
        clock.seek(4.0)
        val player = FakePlayer()
        clock.attach(player)
        assertEquals(4.0, player.position, 0.0)
        assertEquals(0.75, player.currentSpeed, 0.0)
        clock.play()
        assertTrue(player.playing)
        player.position = 4.6
        clock.tick()
        assertEquals(4.6, clock.time.value, 0.0)
        clock.seek(20.0)
        assertEquals(10.0, player.position, 0.0)
        player.onEnded!!()
        assertFalse(clock.playing.value)
        assertFalse(player.playing)
    }
}
```
Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/playback/ExoAudioPlayerDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.playback

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Media3 plays an analysed file, seeks, changes speed and reports its end. */
@RunWith(AndroidJUnit4::class)
class ExoAudioPlayerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test
    fun itPlaysSeeksAndEnds() {
        val file = File(instrumentation.targetContext.cacheDir, "player-loop.wav")
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/loop.wav")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        var ended = false
        val player = onMain { ExoAudioPlayer(instrumentation.targetContext, Uri.fromFile(file)).also { it.onEnded = { ended = true } } }
        try {
            onMain { player.play() }
            SystemClock.sleep(1500)
            val playedFor = onMain { player.positionSeconds }
            assertTrue("played to $playedFor s after 1.5 s", playedFor in 0.5..2.0)

            onMain { player.seekTo(6.0) }
            SystemClock.sleep(300)
            val afterSeek = onMain { player.positionSeconds }
            assertTrue("at $afterSeek s after seeking to 6", afterSeek in 5.9..6.6)

            onMain { player.setSpeed(0.5) }
            val start = onMain { player.positionSeconds }
            SystemClock.sleep(1000)
            val halfSpeed = onMain { player.positionSeconds } - start
            assertTrue("moved $halfSpeed s in 1 s at half speed", halfSpeed in 0.25..0.8)

            onMain { player.setSpeed(1.0); player.seekTo(7.5) }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!ended && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("the end of the file was reported", ended)
        } finally {
            onMain { player.release() }
            file.delete()
        }
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest`
Expected: `:app:compileDebugUnitTestKotlin FAILED`, starting with `Unresolved reference 'AudioPlayer'.` and `'positionSeconds' overrides nothing.`

- [ ] **Step 4: Write the clock and the player**

Create `.../app/playback/PlaybackClock.kt`:
```kotlin
package com.arbaba.chordifymobile.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

/** What plays the audio: Media3 in the app, a fake in the JVM tests. */
interface AudioPlayer {
    /** Where playback is, in seconds. ExoPlayer's position already allows for the output latency. */
    val positionSeconds: Double
    var onEnded: (() -> Unit)?
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun setSpeed(speed: Double)
    fun release()
}

/** One playback clock for the analysis screen (chordify-frontend/src/lib/clock.ts, spec §6.3).
 *  With a player attached it follows the player's position; without one it runs a simulated
 *  clock, so a result can still be explored while its audio file is missing. The screen calls
 *  [tick] once a frame while [playing]; state changes only when the time does. */
class PlaybackClock(val duration: Double, private val nanoTime: () -> Long = System::nanoTime) {
    private val _time = MutableStateFlow(0.0)
    private val _playing = MutableStateFlow(false)
    private val _rate = MutableStateFlow(1.0)
    val time: StateFlow<Double> = _time.asStateFlow()
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    val rate: StateFlow<Double> = _rate.asStateFlow()

    var player: AudioPlayer? = null
        private set
    private var lastTick = 0L

    fun attach(player: AudioPlayer?) {
        this.player?.onEnded = null
        this.player = player
        if (player != null) {
            player.setSpeed(_rate.value)
            player.seekTo(_time.value)
            player.onEnded = { pause() }
            if (_playing.value) player.play()
        }
    }

    fun tick() {
        if (!_playing.value) return
        val now = nanoTime()
        val current = player
        if (current != null) {
            _time.value = maxOf(0.0, current.positionSeconds)
        } else {
            _time.value = min(duration, _time.value + (now - lastTick) / 1e9 * _rate.value)
            if (_time.value >= duration) _playing.value = false
        }
        lastTick = now
    }

    fun play() {
        if (_playing.value) return
        if (_time.value >= duration - 0.05) seek(0.0)
        _playing.value = true
        lastTick = nanoTime()
        player?.play()
    }

    fun pause() {
        _playing.value = false
        player?.pause()
    }

    fun toggle() = if (_playing.value) pause() else play()

    fun seek(seconds: Double) {
        _time.value = seconds.coerceIn(0.0, if (duration > 0) duration else seconds)
        player?.seekTo(_time.value)
    }

    fun setRate(rate: Double) {
        _rate.value = rate
        player?.setSpeed(rate)
    }

    companion object {
        /** The speeds the transport offers (spec §6.3), pitch kept. */
        val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25)
    }
}

/** Media3 ExoPlayer on a file URI. Create, use and release it on the main thread. */
class ExoAudioPlayer(context: Context, uri: Uri) : AudioPlayer {
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    override var onEnded: (() -> Unit)? = null

    init {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
        player.prepare()
    }

    override val positionSeconds: Double get() = player.currentPosition / 1000.0

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(seconds: Double) = player.seekTo((seconds * 1000).toLong())

    /** ExoPlayer keeps the pitch when only the speed changes. */
    override fun setSpeed(speed: Double) = player.setPlaybackSpeed(speed.toFloat())

    override fun release() = player.release()
}
```

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 18 tests.

Run: `cd Mobile && ./gradlew :app:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.playback.ExoAudioPlayerDeviceTest`
Expected: `BUILD SUCCESSFUL`, 1 test. Afterwards, stop any emulator process that is still running (`qemu-system-x86_64-headless`).

- [ ] **Step 6: Commit**

```bash
git add Mobile/gradle/libs.versions.toml Mobile/app/build.gradle Mobile/app/src/main/kotlin/com/arbaba/chordifymobile/playback Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/playback Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/playback
git commit -m "Play the analysed file with Media3 under one playback clock" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The library for the analysis screen

**Files:**
- Modify: `.../app/data/Analysis.kt`, `.../app/data/Library.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/AttachFileDeviceTest.kt`

**Interfaces:**
- Consumes: M4's `AnalysisDao`, `Library` (with its private `open`, `sha256`, `keep` and `displayNameOf`), `Status.FINISHED`.
- Produces:
  - `AnalysisDao.observe(id): Flow<Analysis?>`, `AnalysisDao.setCapo(id, capo)`, `AnalysisDao.setFile(id, fileUri, ownsFile)`;
  - `Library.observe(id): Flow<Analysis?>`, `suspend fun Library.setCapo(id: String, capo: Int)`;
  - `suspend fun Library.isReadable(analysis: Analysis): Boolean`;
  - `suspend fun Library.attach(id: String, uri: Uri): Boolean`, which returns whether the file is the one that was analysed.

- [ ] **Step 1: Write the failing test**

Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/AttachFileDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Spec §8: a file that moved can be picked again; a different file plays with a warning. */
@RunWith(AndroidJUnit4::class)
class AttachFileDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = (context.applicationContext as ChordifyApp).container.library
    private var id: String? = null

    private fun golden(name: String, saveAs: String = name): File {
        val file = File(context.cacheDir, saveAs)
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @After
    fun remove() = runBlocking<Unit> { id?.let { library.delete(it) } }

    @Test
    fun aMovedFileIsMissingUntilPickedAgain() = runBlocking<Unit> {
        val original = golden("loop.wav", "attach-loop.wav")
        val analysis = library.import(Uri.fromFile(original), AnalysisOptions()).also { id = it }
        withTimeout(180_000) { library.observe(analysis).first { it?.status in Status.FINISHED } }
        assertTrue(library.isReadable(library.get(analysis)!!))

        original.delete()
        assertFalse(library.isReadable(library.get(analysis)!!))

        val moved = golden("loop.wav", "attach-loop-moved.wav")
        assertTrue("the same audio is the analysed file", library.attach(analysis, Uri.fromFile(moved)))
        val row = library.get(analysis)!!
        assertEquals(Uri.fromFile(moved).toString(), row.fileUri)
        assertTrue(library.isReadable(row))

        assertFalse("other audio is not", library.attach(analysis, Uri.fromFile(golden("loop_48k.wav", "attach-other.wav"))))
        moved.delete()
    }

    @Test
    fun theCapoIsKeptWithTheAnalysis() = runBlocking<Unit> {
        val analysis = library.import(Uri.fromFile(golden("loop.wav", "capo-loop.wav")), AnalysisOptions()).also { id = it }
        library.setCapo(analysis, 3)
        assertEquals(3, library.observe(analysis).first()!!.capo)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :app:compileDebugAndroidTestKotlin`
Expected: `FAILED`, with `Unresolved reference 'observe'.` (and `Cannot infer type` errors on the same line), then `Unresolved reference` errors for `isReadable`, `attach` and `setCapo`.

- [ ] **Step 3: Add the queries**

In `.../app/data/Analysis.kt`, replace
```kotlin
    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun get(id: String): Analysis?
```
with
```kotlin
    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun get(id: String): Analysis?

    @Query("SELECT * FROM analyses WHERE id = :id")
    fun observe(id: String): Flow<Analysis?>
```
then replace
```kotlin
    @Query("DELETE FROM analyses WHERE id = :id")
```
with
```kotlin
    @Query("UPDATE analyses SET capo = :capo WHERE id = :id")
    suspend fun setCapo(id: String, capo: Int)

    @Query("UPDATE analyses SET fileUri = :fileUri, ownsFile = :ownsFile WHERE id = :id")
    suspend fun setFile(id: String, fileUri: String, ownsFile: Boolean)

    @Query("DELETE FROM analyses WHERE id = :id")
```

- [ ] **Step 4: Watch, store the capo, attach the file again**

In `.../app/data/Library.kt`, replace
```kotlin
    suspend fun get(id: String): Analysis? = dao.get(id)
```
with
```kotlin
    suspend fun get(id: String): Analysis? = dao.get(id)

    fun observe(id: String): Flow<Analysis?> = dao.observe(id)

    /** The capo the analysis screen shows it with (spec §6.1). */
    suspend fun setCapo(id: String, capo: Int) = dao.setCapo(id, capo)

    /** Whether the analysed file can still be read where it was. */
    suspend fun isReadable(analysis: Analysis): Boolean = withContext(Dispatchers.IO) {
        try {
            open(Uri.parse(analysis.fileUri)).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Points the analysis at [uri] after its file went missing (spec §8). Returns whether it is
     *  the file that was analysed (the same SHA-256); a different file is kept too, with a warning. */
    suspend fun attach(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val row = dao.get(id) ?: return@withContext false
        val same = sha256(uri) == row.sha256
        val (fileUri, owns) = keep(uri, id, displayNameOf(uri))
        if (row.ownsFile && row.fileUri != fileUri.toString()) Uri.parse(row.fileUri).path?.let { File(it).delete() }
        dao.setFile(id, fileUri.toString(), owns)
        same
    }
```
`attach` reuses the library's own `keep`, so a file it cannot keep a permission for is copied into `files/imports/`, as at import. A copy the library made of the old file is deleted.

- [ ] **Step 5: Run the test to make sure it passes**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest :app:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.data.AttachFileDeviceTest`
Expected: `BUILD SUCCESSFUL`; 18 JVM tests and 2 device tests. Room's schema is unchanged: the queries use M4's columns.

- [ ] **Step 6: Commit**

```bash
git add Mobile/app/src/main/kotlin/com/arbaba/chordifymobile/data Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/AttachFileDeviceTest.kt
git commit -m "Watch one analysis, keep its capo, and take its file again" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: What the analysis screen says

**Files:**
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/analysis/AnalysisTextTest.kt`
- Create: `.../app/ui/analysis/AnalysisText.kt`

**Interfaces:**
- Consumes: `FUNCTION_NAMES`, `Notation`, `chordName`, `isNoChord`, `shapeKey`, `Shape`, `CapoSuggestion` (Task 2); `WebGoldens` (Task 2, in the tests).
- Produces, in `com.arbaba.chordifymobile.ui.analysis`:
  - `fun countdown(chord: ChordSegment, beats: List<Double>, time: Double): String`, `fun elapsed(chord, time): Float`;
  - `fun functionLine(chord, result): String?`, `fun unsure(chord): String?`;
  - `fun actualNext(chords, chord): ChordSegment?`, `fun actualNextLine(chord, next, notation): String?`, `fun theoryOnlyLine(chord): String?`, `fun percent(p: Double): Long`;
  - `fun capoNotice(analysed: AnalysisResult, capo: Int): String?`, `fun suggestionNotice(suggestion: CapoSuggestion, capo: Int): String?`;
  - `fun diagramDescription(shape: Shape?, capo: Int): String`;
  - `const val FILE_MISSING`, `const val FILE_MISMATCH`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/analysis/AnalysisTextTest.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.Alternative
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.lm.PriorEntry
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.WebGoldens
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisTextTest {
    /** loop.wav's analysis: C, G, Am, F, two seconds each, in C major. */
    private val loop: AnalysisResult = ServerJson.decodeFromJsonElement(
        AnalysisResult.serializer(), WebGoldens.json.getValue("results").jsonArray[0].jsonObject.getValue("result"))
    private val c = loop.chords[0]
    private val g = loop.chords[1]

    @Test
    fun theCountdownCountsTheBeatsLeftInTheChord() {
        val beats = listOf(0.0, 0.5, 1.0, 1.5, 2.0)
        val chord = c.copy(start = 0.0, end = 2.0)
        assertEquals("2 beats until the change (1.5 s)", countdown(chord, beats, 0.5))
        assertEquals("1 beat until the change (0.6 s)", countdown(chord, beats, 1.4))
        assertEquals("0 beats until the change (0.0 s)", countdown(chord, beats, 2.5))
        assertEquals("", countdown(chord.copy(label = "N"), beats, 0.5))
        assertEquals(0.25f, elapsed(chord, 0.5), 1e-6f)
    }

    @Test
    fun theFunctionAndTheDoubtAreSpelledOut() {
        assertEquals("Tonic in C major", functionLine(c, loop))
        assertNull(functionLine(c.copy(function = null), loop))
        val doubtful = c.copy(confidence = 0.4, alternatives = listOf(Alternative("A:min", "Am", 0.3), Alternative("E:min", "Em", 0.2),
                                                                       Alternative("F:maj", "F", 0.1)))
        assertEquals("Unsure: Am or Em also possible", unsure(doubtful))
        assertNull(unsure(c.copy(confidence = 0.9)))
    }

    @Test
    fun theActualNextChordIsComparedWithThePredictions() {
        assertEquals(g, actualNext(loop.chords, c))
        assertEquals(c, actualNext(loop.chords, c.copy(label = "N", index = 99))) // as on the web: the first chord
        assertNull(actualNext(loop.chords, loop.chords.last()))
        val ranked = c.next.indexOfFirst { it.roman == (g.romanTriad ?: g.roman) }
        val expected = if (ranked >= 0) "Actual next: G ✓ predicted #${ranked + 1}" else "Actual next: G ✦ not in the top 3"
        assertEquals(expected, actualNextLine(c, g, Notation.LETTERS))
        assertNull(actualNextLine(c.copy(next = emptyList()), g, Notation.LETTERS))
        assertEquals("Without this song’s history: I 40% · V 31%",
                     theoryOnlyLine(c.copy(theoryOnly = listOf(PriorEntry("I", 0.4), PriorEntry("V", 0.305)))))
        assertEquals(31, percent(0.305))
        assertEquals(100, percent(0.9999))
    }

    @Test
    fun theCapoNoticesReadAsOnTheWeb() {
        assertEquals("Capo on fret 5: chords are shown as the shapes you play, in G major. The song sounds in C major.", capoNotice(loop, 5))
        assertNull(capoNotice(loop, 0))
        assertEquals("With the capo on fret 5, 100% of the song is open chords.", suggestionNotice(CapoSuggestion(5, 1.0), 0))
        assertNull(suggestionNotice(CapoSuggestion(5, 1.0), 5))
        assertNull(suggestionNotice(CapoSuggestion(0, 0.75), 0))
        assertEquals("Guitar chord diagram, frets x 3 2 0 1 0, counted from a capo on fret 2", diagramDescription(listOf(-1, 3, 2, 0, 1, 0), 2))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest`
Expected: `:app:compileDebugUnitTestKotlin FAILED`, with `Unresolved reference` errors for `countdown`, `functionLine`, `actualNext` and the other functions above.

- [ ] **Step 3: Write it**

Create `.../app/ui/analysis/AnalysisText.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.FUNCTION_NAMES
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.Shape
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.music.shapeKey
import java.util.Locale
import kotlin.math.roundToLong

// What the analysis screen says, worded as the web app words it (NowAndNext.tsx and
// AnalysisPage.tsx), apart from its Android layout.

/** "3 beats until the change (1.4 s)", or nothing over "no chord". */
fun countdown(chord: ChordSegment, beats: List<Double>, time: Double): String {
    if (isNoChord(chord.label)) return ""
    val left = maxOf(0.0, chord.end - time)
    val beatsLeft = beats.count { it > time && it < chord.end - 0.05 }
    return "$beatsLeft beat${if (beatsLeft == 1) "" else "s"} until the change (${String.format(Locale.ROOT, "%.1f", left)} s)"
}

/** How far into the chord the playhead is, 0..1. */
fun elapsed(chord: ChordSegment, time: Double): Float =
    (1 - maxOf(0.0, chord.end - time) / maxOf(1e-6, chord.end - chord.start)).toFloat().coerceIn(0f, 1f)

/** "Tonic in C major", or null when the chord has no function. */
fun functionLine(chord: ChordSegment, result: AnalysisResult): String? {
    val name = FUNCTION_NAMES[chord.function] ?: return null
    val key = result.key.global
    return "$name in ${key.tonic} ${key.mode}"
}

/** "Unsure: Am or Em also possible", for a chord the model is less than half sure of. */
fun unsure(chord: ChordSegment): String? =
    if (chord.confidence < 0.5 && !isNoChord(chord.label) && chord.alternatives.isNotEmpty()) {
        "Unsure: ${chord.alternatives.take(2).joinToString(" or ") { it.display }} also possible"
    } else null

/** The chord after [chord] that was really played (the first chord when [chord] is "no chord"). */
fun actualNext(chords: List<ChordSegment>, chord: ChordSegment): ChordSegment? {
    val real = chords.filter { !isNoChord(it.label) }
    return real.getOrNull(real.indexOfFirst { it.index == chord.index } + 1)
}

/** "Actual next: G ✓ predicted #1" or "... ✦ not in the top 3"; predictions are bare numerals,
 *  so they are matched against the chord's bare numeral (V, not V7). */
fun actualNextLine(chord: ChordSegment, next: ChordSegment?, notation: Notation): String? {
    if (next == null || chord.next.isEmpty()) return null
    val rank = chord.next.indexOfFirst { it.roman == (next.romanTriad ?: next.roman) }
    return "Actual next: ${chordName(next, notation)} " + if (rank >= 0) "✓ predicted #${rank + 1}" else "✦ not in the top 3"
}

/** "Without this song’s history: I 40% · V 30%" */
fun theoryOnlyLine(chord: ChordSegment): String? =
    if (chord.theoryOnly.isEmpty()) null
    else "Without this song’s history: " + chord.theoryOnly.joinToString(" · ") { "${it.roman} ${percent(it.p)}%" }

/** JavaScript's Math.round(p * 100). */
fun percent(p: Double): Long = Math.floor(p * 100 + 0.5).roundToLong()

/** The capo notices above Now playing (AnalysisPage.tsx). */
fun capoNotice(analysed: AnalysisResult, capo: Int): String? {
    if (capo == 0) return null
    val key = analysed.key.global
    val mode = if (key.mode == "unknown") "" else " ${key.mode}"
    val shapes = shapeKey(key.tonic, key.mode, capo)
    return "Capo on fret $capo: chords are shown as the shapes you play, in ${shapes.tonic}$mode. The song sounds in ${key.tonic}$mode."
}

fun suggestionNotice(suggestion: CapoSuggestion, capo: Int): String? =
    if (suggestion.capo > 0 && suggestion.capo != capo) {
        "With the capo on fret ${suggestion.capo}, ${percent(suggestion.open)}% of the song is open chords."
    } else null

/** What a screen reader says for a chord box, as the web's aria-label. */
fun diagramDescription(shape: Shape?, capo: Int): String =
    if (shape == null) "No guitar diagram"
    else "Guitar chord diagram, frets ${shape.joinToString(" ") { if (it < 0) "x" else "$it" }}" +
        if (capo > 0) ", counted from a capo on fret $capo" else ""

const val FILE_MISSING ="The audio file is not where it was. Pick it again to play along; until then the chords run silently."
const val FILE_MISMATCH = "This file is not the one that was analysed, so chords may not line up."
```
As on the web, the countdown counts the beats still to come before the change, leaving out a beat less than 50 ms before it. The predictions are bare numerals, so the chord that came next is compared by its bare numeral (`V`, not `V7`).

- [ ] **Step 4: Run the test to make sure it passes**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 22 tests.

- [ ] **Step 5: Commit**

```bash
git add Mobile/app/src/main/kotlin/com/arbaba/chordifymobile/ui/analysis/AnalysisText.kt Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/analysis
git commit -m "Word the analysis screen as the web app does" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The analysis screen

**Files:**
- Modify: `.../app/ui/Theme.kt`, `Mobile/app/build.gradle`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/AnalysisScreenTest.kt`
- Create: `.../app/ui/analysis/GuitarDiagram.kt`, `.../app/ui/analysis/NowAndNext.kt`, `.../app/ui/analysis/AnalysisViewModel.kt`, `.../app/ui/analysis/AnalysisScreen.kt`

**Interfaces:**
- Consumes: Tasks 2–5; M4's `ChordifyApp`, `stageLabel`, `Analysis`, `Status`, and `Library.result(id)`, `Library.retry(id)`.
- Produces:
  - in `...ui`: `data class ChordifyColors(...)` with `fun of(hue: FunctionHue): Color`, and `val LocalChordifyColors`;
  - in `...ui.analysis`:
    - `@Composable fun GuitarDiagram(shape: Shape?, capo: Int, width: Dp = 120.dp)`;
    - `@Composable fun NowPlayingCard(chord, result, time: () -> Double, notation, capo, sounding: String?)`;
    - `@Composable fun NextChordCard(chord, actualNext, chordsByDisplay, notation)`;
    - `enum class AudioFile { CHECKING, PLAYABLE, MISSING }`;
    - `class AnalysisViewModel(application, id)`;
    - `@Composable fun AnalysisScreen(id: String)`;
    - `@Composable fun AnalysisStatus(analysis, onRetry)`;
    - the stateless `@Composable fun AnalysisContent(title, analysed, clock, notation, capo, audio, mismatch, onNotation, onCapo, onPickFile)`.

- [ ] **Step 1: Let the device tests read JSON**

In `Mobile/app/build.gradle`, replace
```groovy
    androidTestImplementation libs.compose.ui.test.junit4
```
with
```groovy
    androidTestImplementation libs.compose.ui.test.junit4
    androidTestImplementation libs.kotlinx.serialization.json
```

- [ ] **Step 2: Write the failing test**

Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/AnalysisScreenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.analysis.AnalysisContent
import com.arbaba.chordifymobile.ui.analysis.AnalysisStatus
import com.arbaba.chordifymobile.ui.analysis.AudioFile
import com.arbaba.chordifymobile.ui.analysis.FILE_MISMATCH
import com.arbaba.chordifymobile.ui.analysis.FILE_MISSING
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class AnalysisScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** The server's analysis of loop.wav (C, G, Am, F in C major), from the engine's golden file. */
    private val loop: AnalysisResult by lazy {
        val stream = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("goldens/analysis.json.gz"))
        val golden = ServerJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val input = golden.getValue("inputs").jsonArray.map { it.jsonObject }.single { it.getValue("file").jsonPrimitive.content == "loop.wav" }
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), input.getValue("result"))
    }
    private val events = ArrayList<String>()

    private fun show(clock: PlaybackClock = PlaybackClock(loop.source.duration), audio: AudioFile = AudioFile.PLAYABLE,
                     mismatch: Boolean = false): PlaybackClock {
        compose.setContent {
            var notation by mutableStateOf(Notation.LETTERS)
            var capo by mutableIntStateOf(0)
            ChordifyTheme {
                AnalysisContent("loop.wav", loop, clock, notation, capo, audio, mismatch,
                                onNotation = { notation = it }, onCapo = { capo = it; events += "capo $it" },
                                onPickFile = { events += "pick" })
            }
        }
        return clock
    }

    @Test
    fun theHeaderAndNowPlayingShowTheResult() {
        show()
        compose.onNodeWithText("Key C major").assertIsDisplayed()
        compose.onNodeWithText("118 BPM").assertIsDisplayed()
        compose.onNodeWithText("4 chords").assertIsDisplayed()
        compose.onNodeWithTag("nowName").assertTextEquals("C")
        compose.onNodeWithText("· Tonic in C major").assertIsDisplayed()
        compose.onNodeWithText(loop.chords[0].next[0].reason, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("time").assertTextEquals("0:00 / 0:08")
    }

    @Test
    fun romanNumeralsReplaceTheNames() {
        show()
        compose.onNodeWithText("Roman").performClick()
        compose.onNodeWithTag("nowName").assertTextEquals("I")
    }

    @Test
    fun theChordFollowsTheClock() {
        val clock = show()
        compose.runOnIdle { clock.seek(loop.chords[1].start + 0.01) }
        compose.onNodeWithTag("nowName").assertTextEquals("G")
    }

    @Test
    fun theSuggestedCapoShowsTheShapesToPlay() {
        show()
        compose.onNodeWithText("With the capo on fret 5, 100% of the song is open chords.").assertIsDisplayed()
        compose.onNodeWithText("Use capo 5").performClick()
        compose.onNodeWithTag("nowName").assertTextEquals("G")
        compose.onNodeWithText("Capo 5 · sounds as C").assertIsDisplayed()
        compose.onNodeWithText("Capo on fret 5: chords are shown as the shapes you play, in G major. The song sounds in C major.")
            .assertIsDisplayed()
        compose.onNodeWithText("Key C major").assertIsDisplayed() // the header keeps the real key
        assertEquals(listOf("capo 5"), events)
    }

    @Test
    fun aMissingFileCanBePickedAgain() {
        show(audio = AudioFile.MISSING, mismatch = true)
        compose.onNodeWithText(FILE_MISSING).assertIsDisplayed()
        compose.onNodeWithText(FILE_MISMATCH).assertIsDisplayed()
        compose.onNodeWithText("Pick the file").performClick()
        assertEquals(listOf("pick"), events)
    }

    @Test
    fun anAnalysisWithoutAResultShowsItsState() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "sha", 0L, modelFingerprint = "fp")
        var shown by mutableStateOf(row.copy(status = Status.RUNNING, stage = "acoustic", progress = 0.6))
        compose.setContent { ChordifyTheme { AnalysisStatus(shown, onRetry = { events += "retry" }) } }
        compose.onNodeWithText("Recognising chords…").assertIsDisplayed()
        shown = row.copy(status = Status.FAILED, errorCode = "DECODE_FAILED", errorMessage = "The file contains no audio samples.")
        compose.onNodeWithText("Could not analyse this audio").assertIsDisplayed()
        compose.onNodeWithText("The file contains no audio samples.").assertIsDisplayed()
        compose.onNodeWithText("Error code: DECODE_FAILED").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), events)
    }
}
```
It reads `loop.wav`'s result from the engine's golden file, which the device tests already have as a resource (M4).

- [ ] **Step 3: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :app:compileDebugAndroidTestKotlin`
Expected: `FAILED`, with `Unresolved reference` errors for `AnalysisContent`, `AnalysisStatus` and `AudioFile`. (`FILE_MISSING` and `FILE_MISMATCH` exist since Task 5.)

- [ ] **Step 4: The web's function colours**

In `.../app/ui/Theme.kt`, replace
```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
```
with
```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.arbaba.chordifymobile.music.FunctionHue
```
then replace
```kotlin
// system setting. The harmonic-function hues come back with the analysis screen (M5).
```
with
```kotlin
// system setting.
```
then replace
```kotlin
@Composable
fun ChordifyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
```
with
```kotlin
/** The web's tokens Material 3 has no slot for: harmonic functions, muted text, the grid. */
data class ChordifyColors(val tonic: Color, val subdominant: Color, val dominant: Color, val borrowed: Color,
                          val muted: Color, val grid: Color) {
    fun of(hue: FunctionHue): Color = when (hue) {
        FunctionHue.TONIC -> tonic
        FunctionHue.SUBDOMINANT -> subdominant
        FunctionHue.DOMINANT -> dominant
        FunctionHue.BORROWED -> borrowed
        FunctionHue.NONE -> grid
    }
}

private val LightTokens = ChordifyColors(Color(0xFF2A78D6), Color(0xFF1BAF7A), Color(0xFFEB6834), Color(0xFF898781),
                                         Color(0xFF898781), Color(0xFFE1E0D9))
private val DarkTokens = ChordifyColors(Color(0xFF3987E5), Color(0xFF199E70), Color(0xFFD95926), Color(0xFF898781),
                                        Color(0xFF898781), Color(0xFF2C2C2A))

val LocalChordifyColors = staticCompositionLocalOf { LightTokens }

@Composable
fun ChordifyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChordifyColors provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
```
The colours are `--tonic`, `--subdominant`, `--dominant`, `--borrowed`, `--muted` and `--grid` from `chordify-frontend/src/index.css`, light and dark.

- [ ] **Step 5: The guitar diagram**

Create `.../app/ui/analysis/GuitarDiagram.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.music.Shape
import com.arbaba.chordifymobile.ui.LocalChordifyColors

/** A chord box (GuitarDiagram.tsx): drawn on the web's 120 × 150 grid. With a capo, frets are
 *  counted from the capo, as on a capo chart. */
@Composable
fun GuitarDiagram(shape: Shape?, capo: Int, width: Dp = 120.dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outline
    val muted = LocalChordifyColors.current.muted
    val text = rememberTextMeasurer()
    Canvas(Modifier.size(width, width * 1.25f).semantics { contentDescription = diagramDescription(shape, capo) }) {
        val unit = size.width / 120f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun label(value: String, x: Float, y: Float, size: Int) {
            val layout = text.measure(value, TextStyle(color = muted, fontSize = (size * unit / density).sp))
            drawText(layout, topLeft = Offset(x * unit - layout.size.width / 2f, y * unit - layout.size.height * 0.8f))
        }
        if (shape == null) {
            label("no diagram", 60f, 80f, 12)
            return@Canvas
        }
        val x0 = 20f
        val y0 = 28f
        val dx = 16f
        val dy = 24f
        val fretted = shape.filter { it > 0 }
        val lowest = fretted.minOrNull() ?: 1
        val base = if ((shape.maxOrNull() ?: 0) > 4) lowest else 1
        if (capo > 0 && base == 1) {
            drawRoundRect(ink2, p(x0 - 7, y0 - 7), Size((dx * 5 + 14) * unit, 7 * unit), CornerRadius(3.5f * unit))
        } else {
            drawRect(ink, p(x0, y0 - 3), Size(dx * 5 * unit, (if (base == 1) 4f else 1f) * unit))
        }
        for (s in 0..5) drawLine(axis, p(x0 + s * dx, y0), p(x0 + s * dx, y0 + dy * 4), unit)
        for (f in 0..4) drawLine(axis, p(x0, y0 + f * dy), p(x0 + dx * 5, y0 + f * dy), unit)
        for ((s, fret) in shape.withIndex()) {
            val x = x0 + s * dx
            val above = if (capo > 0 && base == 1) y0 - 14 else y0 - 10
            when {
                fret < 0 -> label("×", x, above, 11)
                fret == 0 -> drawCircle(ink2, 4 * unit, p(x, above - 4), style = Stroke(1.5f * unit))
                else -> drawCircle(ink, 6 * unit, p(x, y0 + (fret - base + 0.5f) * dy))
            }
        }
        if (base > 1) label("${base}fr", x0 + dx * 5 + 14, y0 + dy * 0.5f + 4, 11)
        label(if (capo > 0) "guitar · capo $capo" else "guitar", 60f, 146f, 12)
    }
}
```
It draws `GuitarDiagram.tsx`'s chord box on the same 120 × 150 grid, scaled to `width`.

- [ ] **Step 6: Now playing and What comes next**

Create `.../app/ui/analysis/NowAndNext.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.guitarShape
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.ui.LocalChordifyColors

@Composable
private fun CardTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalChordifyColors.current.muted)
}

/** Now playing (NowAndNext.tsx): the chord, its numeral and function, what to play over it, the
 *  time to the change and its guitar shape. A polite live region, as the web's aria-live. */
@Composable
fun NowPlayingCard(chord: ChordSegment, result: AnalysisResult, time: () -> Double, notation: Notation, capo: Int, sounding: String?) {
    val colors = LocalChordifyColors.current
    Card(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle("Now playing")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chordName(chord, notation), fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("nowName"))
                    if (capo > 0 && !isNoChord(chord.label) && sounding != null) {
                        Text("Capo $capo · sounds as $sounding", style = MaterialTheme.typography.bodySmall)
                    }
                    if (isNoChord(chord.label)) {
                        Text("No chord", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.of(functionHue(chord.function))))
                            Text((if (notation == Notation.ROMAN) chord.display else chord.roman).orEmpty(), fontWeight = FontWeight.Bold)
                            functionLine(chord, result)?.let { Text("· $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    chord.scaleHint?.let { Text("Scale to play over it: $it", style = MaterialTheme.typography.bodySmall) }
                    ChordProgress(chord, time)
                    ChangeCountdown(chord, result.beats, time)
                    unsure(chord)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!isNoChord(chord.label)) GuitarDiagram(guitarShape(chord), capo, width = 96.dp)
            }
        }
    }
}

/** The progress bar and the countdown read the clock in their own scopes, so the rest of Now
 *  playing recomposes only when the chord changes (spec §6.3). */
@Composable
private fun ChordProgress(chord: ChordSegment, time: () -> Double) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(elapsed(chord, time())).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

@Composable
private fun ChangeCountdown(chord: ChordSegment, beats: List<Double>, time: () -> Double) {
    Text(countdown(chord, beats, time()), style = MaterialTheme.typography.bodySmall)
}

/** What comes next (NowAndNext.tsx): the predictions with their reasons, the chord that really
 *  came next, and the prediction without the song's own history. */
@Composable
fun NextChordCard(chord: ChordSegment, actualNext: ChordSegment?, chordsByDisplay: Map<String, ChordSegment>, notation: Notation) {
    val colors = LocalChordifyColors.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("What comes next")
            if (chord.next.isEmpty()) Text("Predictions appear once a chord is playing.", style = MaterialTheme.typography.bodySmall)
            for ((k, prediction) in chord.next.withIndex()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text((if (notation == Notation.ROMAN) prediction.roman else prediction.display).orEmpty(), fontSize = 22.sp,
                             fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                        Text((if (notation == Notation.ROMAN) prediction.display else prediction.roman).orEmpty(),
                             color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(prediction.p.toFloat().coerceIn(0f, 1f)).height(8.dp)
                                    .background(colors.of(functionHue(chordsByDisplay[prediction.display]?.function))))
                        }
                        Text("${percent(prediction.p)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text((if (k == 0) "Most likely · " else "") + prediction.reason, style = MaterialTheme.typography.bodySmall,
                         color = colors.muted, modifier = Modifier.padding(start = 76.dp))
                }
            }
            val actual = actualNextLine(chord, actualNext, notation)
            val theory = theoryOnlyLine(chord)
            if (actual != null || theory != null) {
                HorizontalDivider()
                actual?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                theory?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
```

- [ ] **Step 7: The view model and the screen**

Create `.../app/ui/analysis/AnalysisViewModel.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.ExoAudioPlayer
import com.arbaba.chordifymobile.playback.PlaybackClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the analysed audio can be played: still being checked, playable, or gone. */
enum class AudioFile { CHECKING, PLAYABLE, MISSING }

class AnalysisViewModel(application: Application, private val id: String) : AndroidViewModel(application) {
    private val library = (application as ChordifyApp).container.library

    val analysis: StateFlow<Analysis?> = library.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val result = MutableStateFlow<AnalysisResult?>(null)
    val notation = MutableStateFlow(Notation.LETTERS)
    val audio = MutableStateFlow(AudioFile.CHECKING)
    val mismatch = MutableStateFlow(false)

    /** The clock of the completed result, made once it is loaded. */
    var clock: PlaybackClock? = null
        private set
    private var player: ExoAudioPlayer? = null

    init {
        viewModelScope.launch {
            analysis.collect { row ->
                if (row?.status == Status.COMPLETED && result.value == null) {
                    val loaded = library.result(id) ?: return@collect
                    clock = PlaybackClock(loaded.source.duration)
                    result.value = loaded
                    connect(row)
                }
            }
        }
    }

    fun setCapo(capo: Int) = viewModelScope.launch { library.setCapo(id, capo) }

    fun retry() = viewModelScope.launch { library.retry(id) }

    /** "Pick the file again" when it went missing: plays it, warning when it is another file. */
    fun attach(uri: Uri) = viewModelScope.launch {
        mismatch.value = !library.attach(id, uri)
        library.get(id)?.let { connect(it) }
    }

    private suspend fun connect(row: Analysis) {
        val playable = library.isReadable(row)
        audio.value = if (playable) AudioFile.PLAYABLE else AudioFile.MISSING
        player?.release()
        player = if (playable) ExoAudioPlayer(getApplication(), Uri.parse(row.fileUri)) else null
        clock?.attach(player)
    }

    override fun onCleared() {
        clock?.pause()
        player?.release()
    }
}
```
Create `.../app/ui/analysis/AnalysisScreen.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.MAX_CAPO
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordIndexAt
import com.arbaba.chordifymobile.music.formatTime
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.music.withCapo
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.stageLabel
import java.util.Locale
import kotlin.math.roundToInt

/** The analysis screen (spec §6.2), opened from the Library. M5b adds the timeline, the tabs and
 *  export. */
@Composable
fun AnalysisScreen(id: String) {
    val application = LocalContext.current.applicationContext as Application
    val model: AnalysisViewModel = viewModel(key = id) { AnalysisViewModel(application, id) }
    val row by model.analysis.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val notation by model.notation.collectAsStateWithLifecycle()
    val audio by model.audio.collectAsStateWithLifecycle()
    val mismatch by model.mismatch.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.attach(uri) }
    val analysis = row
    val analysed = result
    val clock = model.clock
    if (analysis == null) return
    if (analysis.status != Status.COMPLETED || analysed == null || clock == null) {
        AnalysisStatus(analysis, onRetry = { model.retry() })
        return
    }
    AnalysisContent(
        title = analysis.displayName,
        analysed = analysed,
        clock = clock,
        notation = notation,
        capo = analysis.capo,
        audio = audio,
        mismatch = mismatch,
        onNotation = { model.notation.value = it },
        onCapo = { model.setCapo(it) },
        onPickFile = { pick.launch(arrayOf("audio/*")) },
    )
}

/** The screen of an analysis that has no result to show yet, or none at all. */
@Composable
fun AnalysisStatus(analysis: Analysis, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(analysis.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        when (analysis.status) {
            Status.FAILED -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not analyse this audio", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(analysis.errorMessage.orEmpty())
                    Text("Error code: ${analysis.errorCode}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
            Status.CANCELLED -> {
                Text("This analysis was cancelled.")
                TextButton(onClick = onRetry) { Text("Analyse") }
            }
            else -> {
                Text(stageLabel(analysis.stage))
                LinearProgressIndicator(progress = { analysis.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A completed analysis, without its view model, so tests can show it in any state. The header
 *  keeps the song's real key; everything below shows the shapes to play with the capo on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisContent(
    title: String,
    analysed: AnalysisResult,
    clock: PlaybackClock,
    notation: Notation,
    capo: Int,
    audio: AudioFile,
    mismatch: Boolean,
    onNotation: (Notation) -> Unit,
    onCapo: (Int) -> Unit,
    onPickFile: () -> Unit,
) {
    val result = remember(analysed, capo) { withCapo(analysed, capo) }
    val suggestion = remember(analysed) { suggestCapo(analysed.chords) }
    // The time is read by small scopes only (the countdown, the progress bar, the time text), so
    // the rest of the screen recomposes when the chord changes, not every frame (spec §6.3).
    val time: State<Double> = clock.time.collectAsState()
    val playing by clock.playing.collectAsState()
    val starts = remember(result) { result.chords.map { it.start } }
    val index by remember(starts) { derivedStateOf { chordIndexAt(starts, time.value) } }
    val chord = result.chords[index]
    val chordsByDisplay = remember(result) { result.chords.filter { !isNoChord(it.label) }.associateBy { it.display } }
    val view = LocalView.current

    LaunchedEffect(clock, playing) {
        while (playing) withFrameNanos { clock.tick() }
    }
    DisposableEffect(playing) { // the screen stays on while playing (spec §6.2)
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Header(analysed, result, notation, capo, onNotation, onCapo)
            Notices(analysed, capo, suggestion.let { suggestionNotice(it, capo) }, suggestion.capo, audio, mismatch, onCapo, onPickFile)
            NowPlayingCard(chord, result, { time.value }, notation, capo, analysed.chords.getOrNull(index)?.display)
            NextChordCard(chord, actualNext(result.chords, chord), chordsByDisplay, notation)
        }
        Transport({ time.value }, result.source.duration, playing, clock)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(analysed: AnalysisResult, result: AnalysisResult, notation: Notation, capo: Int,
                   onNotation: (Notation) -> Unit, onCapo: (Int) -> Unit) {
    val key = analysed.key.global
    var choosing by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AssistChip(onClick = {}, label = { Text("Key ${key.tonic} ${key.mode}") })
        result.tempo.bpm?.let { AssistChip(onClick = {}, label = { Text("${it.roundToInt()} BPM") }) }
        AssistChip(onClick = {}, label = { Text(result.tempo.meter) })
        AssistChip(onClick = {}, label = { Text("${result.summary.uniqueChords} chords") })
        Box {
            AssistChip(onClick = { choosing = true }, label = { Text(if (capo == 0) "Capo: none" else "Capo: fret $capo") },
                       modifier = Modifier.testTag("capo"))
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                for (fret in 0..MAX_CAPO) {
                    DropdownMenuItem(text = { Text(if (fret == 0) "none" else "fret $fret") },
                                     onClick = { onCapo(fret); choosing = false })
                }
            }
        }
    }
    SingleChoiceSegmentedButtonRow {
        for ((i, choice) in Notation.entries.withIndex()) {
            SegmentedButton(selected = notation == choice, onClick = { onNotation(choice) },
                            shape = SegmentedButtonDefaults.itemShape(i, Notation.entries.size)) {
                Text(if (choice == Notation.LETTERS) "Letters" else "Roman")
            }
        }
    }
}

@Composable
private fun Notices(analysed: AnalysisResult, capo: Int, suggestion: String?, suggestedCapo: Int, audio: AudioFile,
                    mismatch: Boolean, onCapo: (Int) -> Unit, onPickFile: () -> Unit) {
    val capoNotice = capoNotice(analysed, capo)
    if (capoNotice != null || suggestion != null) {
        Notice {
            capoNotice?.let { Text(it) }
            if (suggestion != null) {
                Text(suggestion)
                TextButton(onClick = { onCapo(suggestedCapo) }) { Text("Use capo $suggestedCapo") }
            }
        }
    }
    if (audio == AudioFile.MISSING) {
        Notice {
            Text(FILE_MISSING)
            Button(onClick = onPickFile) { Text("Pick the file") }
        }
    }
    if (mismatch) Notice { Text(FILE_MISMATCH, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Notice(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Transport(time: () -> Double, duration: Double, playing: Boolean, clock: PlaybackClock) {
    val rate by clock.rate.collectAsState()
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clock.toggle() }, modifier = Modifier.testTag("play")) { Text(if (playing) "Pause" else "Play") }
            TimeText(time, duration, Modifier.weight(1f))
            for (speed in PlaybackClock.SPEEDS) {
                FilterChip(selected = rate == speed, onClick = { clock.setRate(speed) },
                           label = { Text(String.format(Locale.ROOT, "%s×", speed.toString().removeSuffix(".0"))) })
            }
        }
    }
}

/** Its own scope, so that only this text follows the clock every frame. */
@Composable
private fun TimeText(time: () -> Double, duration: Double, modifier: Modifier) {
    Text("${formatTime(time())} / ${formatTime(duration)}", modifier = modifier.testTag("time"))
}
```
`AnalysisContent` collects the clock's time as a `State` and passes it on as `{ time.value }`. Only `ChordProgress`, `ChangeCountdown` and `TimeText` call it, so they recompose every frame while playing; the chord index is a `derivedStateOf` the rest of the screen reads.

- [ ] **Step 8: Run the test to make sure it passes**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest :app:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.ui.AnalysisScreenTest`
Expected: `BUILD SUCCESSFUL`; 22 JVM tests and 6 device tests.

- [ ] **Step 9: Commit**

```bash
git add Mobile/app/build.gradle Mobile/app/src/main/kotlin/com/arbaba/chordifymobile/ui Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/AnalysisScreenTest.kt
git commit -m "Show an analysis: header, capo, Now playing, What comes next and playback" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Open an analysis from the Library

**Files:**
- Modify: `.../app/MainActivity.kt`, `.../app/ui/library/LibraryScreen.kt`, `.../app/ui/Formatting.kt`, `Mobile/README.md`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/LibraryScreenTest.kt`, `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/FormattingTest.kt`

**Interfaces:**
- Consumes: `AnalysisScreen` (Task 6), `music.formatTime` (Task 2).
- Produces: `LibraryScreen(onOpen: (String) -> Unit, model)`, `LibraryContent(..., onOpen: (String) -> Unit)`, and the navigation route `analysis/{id}`.

- [ ] **Step 1: Write the failing test**

In `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/LibraryScreenTest.kt`, replace
```kotlin
                               onRetry = { events += "retry $it" }, onDelete = { events += "delete $it" })
```
with
```kotlin
                               onRetry = { events += "retry $it" }, onDelete = { events += "delete $it" },
                               onOpen = { events += "open $it" })
```
then replace
```kotlin
        compose.onNodeWithText("Analyse").performClick()
        assertEquals(listOf("cancel running", "retry failed", "retry cancelled"), events)
```
with
```kotlin
        compose.onNodeWithText("Analyse").performClick()
        compose.onNodeWithText("done.mp3").performClick()
        assertEquals(listOf("cancel running", "retry failed", "retry cancelled", "open done"), events)
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :app:compileDebugAndroidTestKotlin`
Expected: `FAILED`, with `No parameter with name 'onOpen' found.` and `Unresolved reference 'it'.`

- [ ] **Step 3: A tap on a row opens it**

In `.../app/ui/library/LibraryScreen.kt`, replace
```kotlin
fun LibraryScreen(model: LibraryViewModel = viewModel()) {
```
with
```kotlin
fun LibraryScreen(onOpen: (String) -> Unit, model: LibraryViewModel = viewModel()) {
```
then replace
```kotlin
        onDelete = { model.delete(it) },
    )
}
```
with
```kotlin
        onDelete = { model.delete(it) },
        onOpen = onOpen,
    )
}
```
then replace
```kotlin
    onDelete: (String) -> Unit,
) {
    var confirmDelete
```
with
```kotlin
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var confirmDelete
```
then replace
```kotlin
            AnalysisRow(analysis, onCancel, onRetry, onDelete = { if (analysis.ownsFile) confirmDelete = analysis else onDelete(analysis.id) })
```
with
```kotlin
            AnalysisRow(analysis, onCancel, onRetry, onDelete = { if (analysis.ownsFile) confirmDelete = analysis else onDelete(analysis.id) },
                        onOpen = { onOpen(analysis.id) })
```
then replace
```kotlin
private fun AnalysisRow(analysis: Analysis, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit) {
```
with
```kotlin
private fun AnalysisRow(analysis: Analysis, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit,
                        onOpen: () -> Unit) {
```
then replace
```kotlin
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(analysis.displayName, fontWeight = FontWeight.SemiBold)
```
with
```kotlin
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(analysis.displayName, fontWeight = FontWeight.SemiBold)
```
then replace
```kotlin
import com.arbaba.chordifymobile.ui.formatTime
```
with
```kotlin
import com.arbaba.chordifymobile.music.formatTime
```
The last replacement switches the recording timer to the web's `formatTime`, which Step 5 makes the only one.

- [ ] **Step 4: The route**

In `.../app/MainActivity.kt`, replace
```kotlin
import com.arbaba.chordifymobile.ui.ChordifyTheme
```
with
```kotlin
import com.arbaba.chordifymobile.ui.ChordifyTheme
import com.arbaba.chordifymobile.ui.analysis.AnalysisScreen
```
then replace
```kotlin
            composable(Tab.LIBRARY.route) { LibraryScreen() }
```
with
```kotlin
            composable(Tab.LIBRARY.route) { LibraryScreen(onOpen = { navigation.navigate("analysis/$it") }) }
            composable("analysis/{id}") { entry -> AnalysisScreen(entry.arguments!!.getString("id")!!) }
```
The analysis screen is a route of the same navigation host, so Back returns to the Library.

- [ ] **Step 5: One formatTime**

In `.../app/ui/Formatting.kt`, replace
```kotlin
import com.arbaba.chordifymobile.data.Analysis
```
with
```kotlin
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.music.formatTime
```
then replace
```kotlin
).joinToString(" · ")

/** m:ss */
fun formatTime(seconds: Double): String {
    val whole = seconds.toInt()
    return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60)
}
```
with
```kotlin
).joinToString(" · ")
```
In `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/FormattingTest.kt`, replace
```kotlin
    }

    @Test
    fun timesAreMinutesAndSeconds() {
        assertEquals("0:00", formatTime(0.0))
        assertEquals("0:07", formatTime(7.9))
        assertEquals("15:00", formatTime(900.0))
    }
}
```
with
```kotlin
    }
}
```
`MusicGoldenTest` checks `formatTime` against the web's.

- [ ] **Step 6: Describe the new packages**

In `Mobile/README.md`, replace
```markdown
  foreground service with a progress notification. `record/` records from the microphone, and
  `ui/` holds the screens.
```
with
```markdown
  foreground service with a progress notification. `record/` records from the microphone.
  `music/` ports the web app's `music.ts` and `capo.ts` (chord names, guitar shapes, capo),
  `playback/` plays the analysed file with Media3 under one playback clock, and `ui/` holds the
  screens.
```

- [ ] **Step 7: Run everything**

Run:
```bash
node --experimental-strip-types tools/web_goldens.mjs --check
cd Mobile
./gradlew :engine:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:api34DebugAndroidTest
./gradlew :app:assembleDebug
```
Expected:
- `web.json is up to date`;
- 74 engine and 21 app JVM tests;
- 26 app device tests: M4's 17, `ExoAudioPlayerDeviceTest` 1, `AttachFileDeviceTest` 2 and `AnalysisScreenTest` 6;
- the app builds.

Afterwards, stop any emulator process that is still running.

- [ ] **Step 8: Commit**

```bash
git add Mobile/app/src Mobile/README.md
git commit -m "Open an analysis from the Library" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Build and test

```bash
node --experimental-strip-types tools/web_goldens.mjs --check   # from the repository root
cd Mobile
./gradlew :app:testDebugUnitTest                                 # 21 JVM tests
./gradlew :app:api34DebugAndroidTest                             # 26 device tests on the managed emulator
```

To try it on the emulator or a phone: install with `./gradlew :app:installDebug`, then pick a song in the Library. When its analysis completes, tap it. Play it, pick a capo, switch to Roman numerals, and watch Now playing and What comes next follow the music.

## Done when

- `web_goldens.mjs --check` passes, and so do the 21 app JVM tests, the 74 engine JVM tests and the 26 app device tests. CI's `web`, `android` and `android-device` jobs pass.
- A completed analysis opens from the Library. Its header shows the key, tempo, number of chords and the capo, with the suggested capo offered.
- Now playing follows the playback, with the chord's name, numeral, function, scale, countdown and guitar shape. What comes next gives the predictions with their reasons, the chord that really came next, and the prediction without the song's history.
- Letters and Roman numerals switch, and the capo transposes everything below the header, as on the web.
- An analysis that is still running, has failed or has lost its file shows that state, and a lost file can be picked again.

Next: the M5b plan. It covers the timeline with the waveform peaks, the Lead sheet, Circle of fifths and Stats tabs, export, the landscape layout, keyboard shortcuts and swiping Now playing.
