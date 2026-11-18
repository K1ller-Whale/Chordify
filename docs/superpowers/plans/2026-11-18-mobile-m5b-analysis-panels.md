# Mobile M5b: the rest of the analysis screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The analysis screen gets every remaining web feature:
- the timeline, with the waveform;
- the Lead sheet, Circle of fifths and Stats tabs;
- export as `.lab` or `.json`;
- the landscape layout;
- keyboard shortcuts, and a swipe on Now playing to the previous or next chord.

With it, M5's "the analysis screen has every web feature" is met.

**Architecture:**
- `playback/Peaks.kt` computes the web's waveform peaks from one decoding pass through the engine's `streamAudio`. The library keeps them as `files/results/<id>.peaks` and makes them again for a file picked anew.
- `ui/analysis/Charts.kt` holds the pure logic of the panels: surprising changes, the bar index, a step to the previous or next chord, the circle's nodes and transitions, and seek positions. JVM tests cover it.
- `ui/analysis/Timeline.kt` draws `Timeline.tsx`. The overview is redrawn every frame. The detail track is drawn once per chord, and a graphics layer moves it under a fixed playhead (spec §6.3).
- `ui/analysis/Panels.kt` draws `Panels.tsx`: the lead sheet, the circle of fifths, and the time per chord with the repeating progressions.
- `data/Export.kt` writes the server's `.lab` and `.json` exports and shares them through a `FileProvider`.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2025.05.00, Material 3, Canvas, `graphicsLayer`), Media3, AndroidX Core `FileProvider`; the engine's `streamAudio`.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md):
- §6.2: the timeline, the tabs, export, landscape, and the interactions;
- §6.3: drawing, and accessibility;
- §9: M5's "done when".

The web code is the reference: `components/Timeline.tsx`, `components/Panels.tsx`, `lib/audio.ts` (`waveformPeaks`) and `pages/AnalysisPage.tsx` (keyboard, export, layout); the server's export is `chordify_backend/app/api/analyses.py` (`export`).

## Global Constraints

- Everything from the earlier plans still holds (M1–M5a), including the environment variables before Gradle, golden files never edited, and plain imperative commits with the `Co-Authored-By` trailer.
- The timeline uses the web's sizes: 70 dp per second, a 196 dp detail track, and the playhead a third of the way across. The waveform step is `max(0.05, duration / 3000)` seconds, normalised to the loudest peak.
- A change is surprising when its `surprise` is above `log2(1 / 0.15)`, as on the web.
- The `.lab` lines are the server's `f"{start:.3f}\t{end:.3f}\t{label}\n"`. Numbers are rounded as Python rounds them: half-even on the exact binary value, which `String.format` does not do. The `.json` export is the stored result, without the capo.
- The keys are the web's: Space plays or pauses, ← and → jump between chords, and R toggles Roman numerals.
- A screen at least 600 dp wide and wider than tall puts Now playing and What comes next side by side, as the web's two-column layout does.

## Tasks

### Task 1: Waveform peaks
- Create `.../app/playback/Peaks.kt`:
  - `class PeakSink(hopSamples)`, a `SampleSink` keeping the peak of each hop;
  - `peakStep(duration)` and `reducePeaks(peaks, factor)`, which takes the maximum of each group and normalises;
  - `decodePeaks(source, duration, isCancelled)`.
- In `Library`:
  - `suspend fun peaks(analysis, duration): FloatArray?`, kept in `files/results/<id>.peaks`;
  - `attach` and `delete` remove the kept file.
- Tests:
  - `PeaksTest` (JVM);
  - `PeaksDeviceTest`: the peaks of `loop.wav` (8 s) are 160 values with a maximum of 1, and the second call reads the kept file.

### Task 2: The panels' logic
- Create `.../app/ui/analysis/Charts.kt`:
  - `surprisingChords`, `barIndexAt`, `chordStep`;
  - `nodeKey`, `circleNodes`, `circleTransitions`;
  - `detailSeek`, `overviewSeek`.
- Create `.../app/data/Export.kt`: `labText(result)`, `jsonText(result)`, `exportName(displayName, format)`, `exportFile(context, ...)` and `shareIntent(context, file, format)`. Declare a `FileProvider` for `cache/exports/` in the manifest.
- Tests:
  - `ChartsTest` and `ExportTest` (JVM). `ExportTest` checks the rounding with values on which Java and Python disagree: 1.2345, 0.1245, 4.0625 and 1.0005.
  - `ExportDeviceTest`: the file is written and the `FileProvider` gives it a content URI.

### Task 3: The timeline
- Create `.../app/ui/analysis/Timeline.kt`. It draws:
  - the overview, with the chords by function, the sections, the viewport and the playhead; tap or drag it to seek;
  - the detail track:
    - sections;
    - beats, and downbeats with a bar number every four bars;
    - chord blocks: the function colour (the active chord solid, the others tinted), stripes for chords outside the key, low confidence faded, the name and numeral, and ✦ on surprising changes;
    - the waveform.
  - Tap the detail track or drag it to seek.
  - The legend.
- Content description: "Timeline of <n> chords".

### Task 4: The panels and the tabs
- Create `.../app/ui/analysis/Panels.kt`:
  - `LeadSheet`: four bars a row in its own scrolling grid. The current bar is marked and scrolled into view, and tapping a bar seeks to it. A song with no bar grid says so, as on the web.
  - `CircleOfFifths`: the key's wedge, the two rings, the transitions weighted by count, the dashed line to the predicted chord, the labels the song does not use, and nodes sized by time share. Its content description is the web's.
  - `ChordShare`: the ten chords with the most time, the repeating progressions and the predictability.

### Task 5: The screen with everything
- In `AnalysisScreen.kt`:
  - the Export chip, with `.lab` and `.json`;
  - the timeline;
  - the tabs Lead sheet, Circle of fifths and Stats;
  - the footnote with the models and the shortcuts;
  - the landscape layout;
  - the keyboard shortcuts;
  - the swipe on Now playing.

  `NowPlayingCard` takes a `modifier`. `AnalysisViewModel` loads the peaks and makes the export intent.
- Extend `AnalysisScreenTest`:
  - the tabs and their content;
  - tapping a bar seeks;
  - Space, → and R;
  - swiping Now playing;
  - tapping the overview seeks;
  - Export calls back with the format;
  - the wide layout puts the two cards side by side.

### Task 6: Run everything
- `web_goldens.mjs --check`; the engine's and the app's JVM tests; the app's device tests; `:app:assembleDebug`.

## Choices this plan makes where the spec leaves room

- The peaks are made when the screen first opens with a playable file, not by the analysis job. Results that were reused and files picked again get a waveform too. A file picked again gets the waveform of the file that plays.
- Only the transport is pinned in portrait. Now playing, What comes next and the timeline together are taller than a phone screen, so they scroll with the tabs below them.
- The share sheet carries the export, so it can go to Files, mail or another app. The `.json` file is the stored result document.
