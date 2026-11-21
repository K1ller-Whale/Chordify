# Mobile app with on-device chord analysis

Status: design approved in conversation, 2026-11-17. Not yet implemented.

## 1. Goal

Rebuild the Android app in `Mobile/` so that it has every feature of the web app
(`chordify-frontend/`) and runs the whole analysis on the phone. The app makes no network
requests: no backend, no internet permission.

Success means:

- An analysis on the phone produces the same `AnalysisResult` as the server's v2 pipeline on
  the same audio (same chords, Roman numerals, key and predictions; tolerances in §7.1).
- A 4-minute song is analysed in at most 20 s on a mid-range phone.
- Every web screen has a phone equivalent (§6).

## 2. Decisions

| Decision | Choice | Reason |
|---|---|---|
| Platforms | Android only | No iPhone app planned. The engine can be Kotlin. |
| UI stack | Kotlin + Jetpack Compose; the Java/XML app is replaced, not migrated | Every current screen calls v1 endpoints that do not exist on the device. The timeline, circle of fifths and chord diagrams are custom drawing, which Compose makes simple. |
| Engine approach | Port the Python pipeline to Kotlin; compile NNLS Chroma (C++) with the NDK; check the port against golden files written by Python | Chaquopy cannot run librosa (it needs numba) and adds over 100 MB. Kotlin is fast enough for the decoder and beat tracking, so C++ is kept to what is already C++. |
| Audio files | Keep a link to the user's file (persistable URI permission), not a copy | No extra storage. A moved or deleted file is picked again and checked by SHA-256, as on the web. Recordings made in the app are stored by the app. |
| Licence | The app is open source under GPL-3.0-or-later | NNLS Chroma is GPL-2.0-or-later. Bundling it in a distributed APK puts the app under the GPL. AndroidX and Media3 are Apache-2.0, which is compatible with GPLv3 but not GPLv2, so the app uses v3. |
| Analysis screen layout (portrait) | Player area pinned at the top, other panels in tabs below | Now playing, next chord, timeline and transport stay visible while playing along. |

## 3. Scope

In scope: the on-device engine, a local library of analyses, file import and recording,
background analysis, and these screens: Library (web "Analyse"), Analysis, Quick chord,
Songwriter, Settings, About.

Removed from the app: the chroma picture screen, typed time-slice analysis, the API URL
setting, Retrofit, the `INTERNET` permission and the cleartext network configuration.

Out of scope:

- iPhone.
- Live mode (chords while playing). The current model looks ahead within its 24 s window;
  live mode needs a streaming model (roadmap phase 4).
- A smaller (16-bit or 8-bit) model. It may follow later, only after it is re-scored with
  `chordify_ai.eval.evaluate_model` on Billboard and GuitarSet.
- Corrections, sync with the server, model downloads, local keys and sections (the server
  does not produce the last two either), background playback with lock-screen controls.
- Server changes. The web Quick chord screen still calls v1 `/predict`, so the v1 endpoints
  stay until that screen moves to v2.

## 4. Structure

`Mobile/` becomes a Gradle project with two modules. The package name
`com.arbaba.chordifymobile` stays, so the new app installs over the old one. `minSdk` is 30 (Android 11), lowered from the old app's 33 so the app runs on older phones.

### 4.1 `:engine` (Android library, no UI)

Kotlin packages, each mirroring the `chordify_core` or backend file it ports:

| Package | Ports | Does |
|---|---|---|
| `audio` | `chordify_core/audio.py` | Decode any file Android can play (`MediaExtractor` + `MediaCodec`), mix to mono, resample to 44.1 kHz, stream in chunks |
| `features` | `chordify_core/features.py` | JNI bridge to the NNLS host; A-to-C bin roll; frame times with the 0.186 s offset; `normalise_bothchroma` |
| `acoustic` | `chordify_core/acoustic.py` | Load `model.onnx` with ONNX Runtime (SHA-256 checked against `bundle.json`); windowed inference (512 frames, 64-frame margin); softmax/sigmoid |
| `beats` | `chordify_backend/app/pipeline/beats.py` and librosa | Onset strength and `beat_track` (tempo estimate + dynamic programming), regularity confidence, downbeat phase |
| `decode` | `chordify_core/decode.py` | Unit boundaries, pooling, prior scaling, Viterbi, segments, merging, inversions |
| `theory` | `chordify_core/theory.py` | Keys, Roman numerals, functions, scale hints, cadences, transition reasons, named loops |
| `lm` | `chordify_core/lm.py` | Load `model.json.gz`; n-gram + song cache; change matrix; `predict_next` |
| `vocab` | `chordify_core/vocab.py` | Harte parsing, vocabularies, simplification, display names, `parse_display` |
| `analysis` | `chordify_backend/app/pipeline/analysis.py` | The two-pass pipeline and the result document; `single_chord` |
| `model` | `chordify_backend/app/schemas.py` | `AnalysisResult` and its parts as data classes, serialised to the same JSON as the server |

`src/main/cpp/` holds the NNLS Chroma sources (with their `COPYING`), the Vamp plugin SDK
and libsoxr (LGPL-2.1-or-later), built with CMake for `arm64-v8a` and `x86_64`.

Public API:

- `analyse(source, options, onProgress, isCancelled): AnalysisResult`
- `nextChords(chords, key, k): ProgressionResponse` (Songwriter)
- `quickChord(pcm): Pair<label, confidence>` (Quick chord)

Model files are copied from the repository's `models/chordnet-chroma/3.0.0/` and
`models/progression-ngram/1.0.0/` into the engine's assets by a Gradle task, so git holds one
copy of each model. Decoder settings, class prior and feature spec are read from
`bundle.json`, as in Python, and a feature-spec revision the engine does not implement is
refused.

### 4.2 `:app`

Compose screens, Room library, Media3 playback, recording, import, WorkManager jobs.

### 4.3 Repository additions

- `chordify_ai/export/mobile_goldens.py` writes golden files into
  `Mobile/engine/src/test/resources/goldens/` (§7.1).
- A CI job that builds the app and runs the JVM and emulator tests.
- `Mobile/LICENSE` (GPL-3.0-or-later).

## 5. Engine pipeline

The audio is processed in one streaming pass: decoded chunks feed NNLS, the onset detector
and the waveform-peak counter together, and only their outputs are kept. A 15-minute song
as 44.1 kHz floats would take about 160 MB; its features take a few MB.

1. **Decode.** `MediaExtractor`/`MediaCodec` cover mp3, m4a/aac, flac, ogg, opus, wav and
   webm. Limits as on the server: at least 1 s, at most 15 min.
2. **Resample to 44.1 kHz** with libsoxr in its high-quality setting (librosa's default
   method), only when the source rate differs.
3. **NNLS features.** A minimal C++ Vamp host runs the plugin with Python's settings:
   block 16384, step 2048, output `bothchroma`, default parameters, wrapped in the Vamp SDK's
   `PluginInputDomainAdapter` (the same Hann window and FFT the Python `vamp` host uses).
   Then the bin roll, frame offset and normalisation as in `features.py`.
4. **Model.** ONNX Runtime CPU, 32-bit model, windowed as `acoustic.windowed`.
5. **Beats.** Port of librosa's onset strength and `beat_track` at 22.05 kHz, hop 512, as
   `track_beats` calls it. Golden files record the librosa version that produced them.
6. **Decode, key, theory, predictions.** As `analysis.analyse`: first decode, NO_MUSIC check,
   key from chords, second decode with the n-gram change matrix blended 50/50 with uniform,
   inversions from the bass head at the bundle's threshold, optional simplification to
   major/minor, merging, chord entries with theory, predictions with surprise and
   predictability, downbeats, bars, time share and patterns.

Progress stages are one list shared by the engine and the UI. This avoids the web app's
mismatch, where the backend sends `decode` and the UI expects `decode_chords`.

Cancellation is checked inside the streaming loop and between stages. Errors use the
server's codes: `UNSUPPORTED_MEDIA_TYPE` (no audio track Android can read), `DECODE_FAILED`
(the decoder fails or yields no samples), `AUDIO_TOO_SHORT`, `AUDIO_TOO_LONG`,
`NO_MUSIC_DETECTED`, `MODEL_UNAVAILABLE` (a model file fails its SHA-256 check).

## 6. App

### 6.1 Library and background analysis

Room table `analyses`: id, display name, file URI, SHA-256, duration, created time, status
(queued, running, completed, failed, cancelled), stage, progress, error code and message,
options, model fingerprint, capo.

Result JSON and waveform peaks are files in app storage: `files/results/<id>.json` and
`files/results/<id>.peaks`.

The same file (SHA-256), model fingerprint and options reuse the stored result. When an app
update ships a new model, old results stay readable and offer "Re-analyse with the new model".

Import: the system file picker (`ACTION_OPEN_DOCUMENT`, `audio/*`) and "Share to" / "Open
with" intents, keeping the persistable URI permission. When the file is gone, the analysis
still opens and asks for the file; a file with a different SHA-256 shows a warning.

Recording: `AudioRecord`, 44.1 kHz, 16-bit mono, `UNPROCESSED` source where supported, else
`VOICE_RECOGNITION`; saved as WAV in `files/recordings/`. Deleting the analysis deletes its
recording, after a confirmation.

Analyses run one at a time as WorkManager jobs in a foreground service with a progress
notification (`dataSync` type up to Android 14, `mediaProcessing` from Android 15).
Progress is written to Room and observed by the screens. Cancel stops the job. WorkManager
restarts a job killed with the process. The engine loads its models once per process, on
first use.

Export: `.lab` and `.json` through the share sheet or "Save to…" (`ACTION_CREATE_DOCUMENT`).

Settings: default notation (Letters/Roman), default "Major and minor only", clear library.
Notation and defaults live in DataStore.

### 6.2 Screens

M7 replaces this layout: see `2026-11-21-mobile-simple-landscape-ui-design.md`.

Bottom navigation: Library, Quick chord, Songwriter, Settings. The analysis screen opens from
the Library; About opens from Settings.

**Library**: "Pick a file", "Record" (with timer), "Major and minor only" switch, list of
analyses with progress or error, swipe to delete. "Import an analysis (.json)" adds an analysis
exported on another phone or by the web app, as does opening or sharing a `.json` export with the
app. It arrives without its audio: the analysis screen asks for the song, checked by the SHA-256
the export records.

**Analysis**, portrait:

- Header: title, key, BPM, number of chords, capo picker with the suggested capo,
  Letters/Roman toggle, export.
- Pinned player area: Now playing (name, Roman numeral, function, scale hint, countdown to
  the change, guitar diagram, alternatives when confidence < 0.5), What comes next (top
  predictions with reasons, the actual next chord, the prediction without the song's
  history), timeline (overview strip and detail view under a fixed playhead, with beats,
  downbeats, waveform, function colours, surprising changes), transport (play, time, speed).
- Tabs below: Lead sheet, Circle of fifths, Stats (time per chord, repeating progressions,
  predictability).

Landscape uses the web's two-column layout. States as on the web: analysing (stage and
progress), failed (message and code), file missing (pick it again; the timeline runs
silently until then).

Interactions: tap or drag the timeline to seek; swipe Now playing for the previous or next
chord; tap a lead-sheet bar to jump to it; with a hardware keyboard, Space, ←/→ and R as on
the web. The screen stays on while playing.

**Quick chord**: record two to three seconds with a level meter, or pick a short file; shows
the full display name (Am7, not A), confidence and guitar diagram.

**Songwriter**: progression chips (tap to remove), text field for chord names, key picker
with "Detect from the chords", predictions with Roman numeral, probability and reason; tap a
prediction to add it.

**About**: licence, link to the source, third-party notices (NNLS Chroma, Vamp SDK, libsoxr,
ONNX Runtime, AndroidX, Media3), dataset credits for the model (McGill Billboard, POP909,
GuitarSet).

### 6.3 Playback and drawing

Media3 ExoPlayer plays the file URI at 0.5×, 0.75×, 1× or 1.25× with pitch kept. A Kotlin
`PlaybackClock`, like `chordify-frontend/src/lib/clock.ts`, reads the player position every
frame (ExoPlayer's position already accounts for output latency). Compose state changes only
when the chord or bar index changes; the timeline is drawn once and moved with a graphics
layer translation.

`capo.ts` and the guitar shapes and colours from `music.ts` are ported to Kotlin in `:app`.
Light and dark themes use the web's colour tokens and follow the system setting.

Accessibility: chord diagrams have content descriptions; Now playing is a polite live region
(as the web's `aria-live`); text scales with the system font size.

## 7. Testing

### 7.1 Parity with Python

`mobile_goldens.py` produces, for each test input, the expected output of every stage: raw
and normalised features, model outputs, beats, first-pass segments, key, final
`AnalysisResult`. Inputs:

- Renders from `chordify_core.synth`, written as WAV: a C–G–Am–F loop, a seventh-chord loop,
  the loop with silence before and after, the loop at 48 kHz (resampling), a 3/4 song.
- A 30 s excerpt of one GuitarSet take (CC BY 4.0, credited in the test resources).

It also writes tables for the pure-Kotlin parts: every vocabulary label and common chord
name through `parse`, `display_name`, `parse_display` and `simplify`; `theory.describe` for
every large-vocabulary chord in all 24 keys; `predict_next` for a set of histories; `decode`
on fixed posteriors.

| Compared | Tolerance |
|---|---|
| `vocab`, `theory`, `lm`, chord labels, Roman numerals, key, predictions | identical |
| Features (raw and normalised) | max abs difference 1e-4 |
| Model outputs (logits) | max abs difference 1e-3 |
| Beats | within one onset frame (23 ms) |
| Chord boundaries | within one decoding unit |

Golden inputs are WAV, so decoding is exact. MP3 and AAC decoders may handle encoder delay
differently from libsndfile and ffmpeg; an extra test decodes an MP3 and an AAC version of a
render and allows a 30 ms timing offset.

### 7.2 Where tests run

- JVM unit tests (CI): `vocab`, `theory`, `lm`, `decode`, the `analysis` assembly from saved
  model outputs, capo and display logic.
- Emulator instrumented tests (CI, x86_64): NNLS host, resampler, `MediaCodec` decoding,
  ONNX inference, one end-to-end analysis of a bundled short WAV.
- Compose UI tests: screen states (analysing, failed, file missing), capo transposition,
  chord names.
- Manual benchmark on one real mid-range phone, recorded in
  `docs/chord-progression-plan/08-implementation-status.md`: target ≤ 20 s for a 4-minute song.

Accuracy needs no new scoring while the engine matches Python, because the shipped model's
published scores then carry over.

## 8. Errors shown to the user

| Situation | Behaviour |
|---|---|
| Engine error code | The web app's message for that code |
| File moved or deleted | Analysis opens; "pick the file again"; SHA-256 checked |
| Different file picked | Warning that chords may not line up |
| Microphone refused | Message with a link to the app's system settings |
| Storage full while recording | Recording stops; what was recorded is kept and can be analysed |
| Model file corrupt (SHA-256 mismatch) | Analysis fails with `MODEL_UNAVAILABLE`; reinstall advised |

## 9. Build order

| | Milestone | Done when |
|---|---|---|
| M1 | `mobile_goldens.py`; Kotlin `vocab`, `theory`, `lm`, `decode` | JVM tests match the golden files |
| M2 | NNLS host, libsoxr, streaming `MediaCodec` decode | Features match on the emulator |
| M3 | ONNX inference, beat tracking, `analysis` | The full `AnalysisResult` matches Python's |
| M4 | App shell: Compose, Room library, import, recording, WorkManager jobs; old Java app deleted | A song can be analysed and appears in the Library |
| M5 | Analysis screen, playback, capo, export | The analysis screen has every web feature |
| M6 | Quick chord, Songwriter, Settings, About, accessibility, device benchmark, licence files | Ready to release |

## 10. Risks

| Risk | Mitigation |
|---|---|
| The NNLS host differs from Python's (window, padding, number of frames at the end) | Built and checked first (M2) against golden features; the Python `vamp` host's source is the reference |
| librosa's beat tracker changes between versions | Port the version pinned when the golden files are made; record it in the files |
| Phones are slower than expected | NNLS dominates on the server (2–3 s per song); measure in M2. If too slow, profile the native build flags first |
| APK size (ONNX Runtime native libraries plus the 14.6 MB model) | Ship an Android App Bundle so each phone downloads one ABI |
| Process death during a long analysis | WorkManager restarts it; partial results are not kept |
