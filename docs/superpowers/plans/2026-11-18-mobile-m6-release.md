# Mobile M6: ready to release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The three remaining tabs work: Quick chord, Songwriter, and Settings with About. Every screen passes an accessibility check. The app carries its licence and third-party notices, and builds as an Android App Bundle for release.

**Architecture:**
- `quick/` turns a short recording or a picked clip into one chord, with the engine's `quickChord`. It reads it with the engine's `streamAudio`.
- `songwriter/` asks the engine's `nextChords`, as the web's Songwriter asks `POST /api/v2/progressions/next`.
- Both reach the engine through `EngineHolder.concurrent()`, so a running analysis does not hold them up:
  - ONNX Runtime sessions are safe to run from several threads;
  - the chord model allocates per call;
  - the progression model is read-only.
- `ui/settings/` holds Settings and About.
- `Mobile/LICENSE` is the GPL-3.0 text. `Mobile/THIRD_PARTY_NOTICES.md` lists what the app bundles; About shows the same list.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), DataStore, the engine (`quickChord`, `nextChords`, `streamAudio`, `displayName`), Android App Bundle.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md):
- §6.2: Quick chord, Songwriter, About;
- §6.3: accessibility;
- §7.2: the manual benchmark;
- §9: M6, "ready to release";
- §10: the App Bundle.

The web pages `pages/QuickChord.tsx` and `pages/Songwriter.tsx` are the reference.

## Global Constraints

- Everything from the earlier plans still holds.
- **Quick chord** shows the full display name (Am7, not A), as the spec asks. The web's v1 call shows only major and minor. It also shows the confidence in percent and the guitar diagram. A recording stops by itself after 3 s. A picked clip must be between 0.5 s and 30 s.
- **Songwriter** starts with C, G and Am, as the web does. It asks for 5 suggestions, and the key list is the web's `KEYS`, with "Detect from the chords" first. Errors show the engine's message, which is the server's.
- The app is GPL-3.0-or-later, because it bundles NNLS Chroma (GPL-2.0-or-later). About credits the datasets the model was trained on: McGill Billboard, POP909 and GuitarSet.
- The release build stays unminified: ONNX Runtime's JNI classes would need keep rules that no test covers yet. The App Bundle splits the native libraries by ABI, so each phone downloads only its own.

## Tasks

### Task 1: The engine beside a running analysis
- `EngineHolder.concurrent()` loads the engine under its own lock and returns it. `withEngine` still serialises analyses.

### Task 2: Quick chord
- `Recorder.record` gains `onLevel`, the RMS of each tenth of a second.
- `.../app/quick/QuickChord.kt`:
  - `decodeClip(source)`, with the 0.5 s and 30 s limits;
  - `chordShape(label)`;
  - `quickChordName(label)`, the engine's `displayName`, or "No chord".
- `QuickChordViewModel` and `QuickChordScreen`:
  - a Record button, with Stop and Listening…;
  - a level meter;
  - "Or pick a short clip";
  - the result card (name, confidence, diagram) as a polite live region;
  - errors, including the microphone refused, as in the Library.
- Tests:
  - `QuickChordTest` (JVM);
  - `QuickChordDeviceTest`: the first two seconds of `loop.wav` are C, with the confidence of the engine's own quick-chord check;
  - `QuickChordScreenTest`: the states.

### Task 3: Songwriter
- `.../app/songwriter/Songwriter.kt`:
  - `splitChords(draft)` splits on spaces, commas and bars, as the web does;
  - `keyLine(response)`: "Assuming C major (87% sure)" or "In G major".
- `SongwriterViewModel` and `SongwriterScreen`:
  - the chips, which remove a chord when tapped;
  - the text field with Add;
  - the key picker;
  - "Suggest the next chord";
  - the predictions with numeral, bar, percentage and reason; tapping one adds it.
- Tests:
  - `SongwriterTest` (JVM);
  - `SongwriterDeviceTest`: the engine through the holder suggests chords for C G Am, with the key estimated;
  - `SongwriterScreenTest`.

### Task 4: Settings and About
- `SettingsScreen`:
  - "Major and minor chords only", the Library's option, from `Settings`;
  - the space the results, recordings and copies use;
  - About.
- `AboutScreen`:
  - the version;
  - the licence, and the source (it opens the browser);
  - the third-party notices;
  - the dataset credits;
  - the models.
- Tests: `SettingsScreenTest`.

### Task 5: Licence files and the release build
- Add `Mobile/LICENSE`, the GPL-3.0 text, and `Mobile/THIRD_PARTY_NOTICES.md`.
- `:app:bundleRelease` builds an App Bundle. The README says how to build and install it.

### Task 6: Accessibility
- `AccessibilityTest` runs every screen's stateless content and checks:
  - every clickable node has a text or a content description;
  - chord diagrams and charts have descriptions;
  - Now playing and the Quick chord result are live regions.
- Problems it finds are fixed.

### Task 7: The benchmark and the status page
- `docs/chord-progression-plan/08-implementation-status.md` records the app as built, M4–M6.
- The real-phone row is still open: it needs a phone over USB. The step to measure it: run `FeatureBenchmarkDeviceTest` with a phone attached.

### Task 8: Run everything
- The engine's and the app's JVM and device tests, `web_goldens.mjs --check`, `:app:assembleDebug` and `:app:bundleRelease`.
