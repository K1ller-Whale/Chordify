# Mobile M3b: the whole analysis on the phone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ChordifyEngine.analyse` turns an audio file into the same `AnalysisResult` document the server's analysis job writes, and the engine also answers the Songwriter's next-chord request and Quick chord, each checked against Python.

**Architecture:** M3a's single decoding pass (`runFrontEnd`) and `ChordModel.predict` give the beats and the model's output. `analysis.analyseMeasured` ports the rest of the server's `analyse()`: two decodings, the key, chord names, theory, predictions, downbeats, bars and the summary. It returns `model.AnalysisResult`, data classes that serialise to the server's JSON. Because `analyseMeasured` starts from recorded measurements, JVM tests replay the server's own beats and model output and must reproduce its result to 1e-9. A device test then runs the whole thing from the audio file.

**Tech Stack:** Kotlin, kotlinx.serialization (compiler plugin and JSON), ONNX Runtime Android 1.30.0, Docker; everything from M1–M3a.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md), §4.1 (packages `analysis` and `model`, public API), §5 (step 6, progress stages, cancellation, errors), §7 (tests), §9 (M3: "the full `AnalysisResult` matches Python's").

**Builds on** M3a ([`2026-11-17-mobile-m3a-acoustic-beats.md`](2026-11-17-mobile-m3a-acoustic-beats.md)): `runFrontEnd`, `FrontEnd`, `ChordModel`, `ChordBundle`, `AcousticOutput`, `BeatInfo`, and the golden files it writes.

## Global Constraints

- Everything from the M1, M2 and M3a plans still holds: module `:engine`, namespace `com.arbaba.chordifymobile.engine`, `minSdk 33`, JVM 17, `export JAVA_HOME="/c/Program Files/Java/jdk-21"` and `export ANDROID_USER_HOME="D:/android-user"` before every Gradle command, golden files generated and never edited, plain imperative commit messages with the `Co-Authored-By` trailer. Stop Docker Desktop after Task 1 (`docker desktop stop`).
- The analysis follows `chordify_backend.app.pipeline.analysis.analyse` and `single_chord`, and `chordify_backend.app.api.progressions.next_chords`. The result is `AnalysisResult.model_validate(result).model_dump(mode="json", by_alias=True)`, as `chordify_backend.app.jobs` stores it: every field present, defaults and nulls included.
- Errors use the server's codes. Analysis failures throw `EngineException` with `ErrorCode.NO_MUSIC_DETECTED` and the server's message. Where the server answers `INVALID_REQUEST`, `nextChords` throws `IllegalArgumentException` with the server's message.
- Progress stages are one list for the engine and the app (spec §5), named as the web app names them: `decode`, `acoustic`, `key`, `decode_chords`, `analyse`, `done`. `decode` covers the single pass that decodes the file, computes NNLS Chroma and tracks the beats.
- Tolerances (spec §7.1):
  - Replayed from the server's beats and model output, the result matches the server's to 1e-9: every label, key, Roman numeral, prediction and count identical, every number equal after the server's rounding.
  - From the audio file on a device, strings, lists and counts are identical, and numbers agree to 2e-3.
- The models live once in git, in the repository's `models/`. The Android build un-gzips `*.gz` assets and drops the suffix, so the APK holds `progression-ngram/1.0.0/model.json` where `bundle.json` names `model.json.gz`.

## What was checked while writing this plan

Every Kotlin file and test in this plan ran in a scratch copy of the repository, against an `analysis.json.gz` that the plan's own generator wrote in the Docker container:
- 73 JVM tests passed. On the first run, the six results and the four option replays matched the server's to 1e-9, along with the silent input's `NO_MUSIC_DETECTED` message, the next-chord responses and errors, and Quick chord.
- 28 device tests passed on the managed emulator. From each WAV file, the whole `AnalysisResult` matched the server's within 2e-3.
- On a 240 s song, one emulator run of the whole analysis took 20.1 s: 14.9 s decoding, NNLS and beats, 4.0 s the chord model, 1.2 s the rest. The emulator's timings vary a lot from run to run, so the budget still waits for a phone.

The first device run failed: the Android build had stored `model.json.gz` as `model.json`. `ChordifyEngine.fromAssets` now opens that name (Task 5).

**Not in this plan:**
- The GuitarSet excerpt of spec §7.1 needs the GuitarSet audio, which is not on this machine (a download of several hundred MB from Zenodo, CC BY 4.0). It is offered separately.
- The waveform peaks of spec §5 move to M5, whose analysis screen draws them.

## File structure

| Path | Responsibility |
|---|---|
| `tools/mobile_goldens.Dockerfile`, `tools/mobile_goldens.sh` | The container gets the backend's API packages and also writes `analysis.json.gz` |
| `chordify_ai/export/mobile_goldens.py` | A silent input; the `analysis` golden file; `NEEDS` says what each file needs |
| `tests/ai/test_mobile_goldens.py` | Tolerance for the new file |
| `Mobile/gradle/libs.versions.toml`, `Mobile/build.gradle`, `Mobile/engine/build.gradle` | The kotlinx.serialization compiler plugin |
| `.../engine/model/AnalysisResult.kt` | `AnalysisResult`, `ProgressionResponse` and their parts, serialised as the server's JSON |
| `.../engine/lm/Predict.kt` | `Prediction` and `PriorEntry` become serialisable |
| `.../engine/Errors.kt` | Adds `NO_MUSIC_DETECTED` |
| `.../engine/beats/BeatInfo.kt` | `downbeatPhase` |
| `.../engine/analysis/Models.kt` | The chord bundle and progression model an analysis decodes with |
| `.../engine/analysis/Analysis.kt` | Options, stages, `decodeChords`, `firstPass`, `analyseMeasured` |
| `.../engine/analysis/Suggest.kt` | `nextChords`, `singleChord` |
| `.../engine/audio/AudioDecoder.kt`, `AudioStream.kt`, `.../engine/analysis/FrontEnd.kt` | Decoding progress |
| `.../engine/ChordifyEngine.kt` | The public API: `analyse`, `nextChords`, `quickChord` |
| tests under `src/test`, `src/sharedTest`, `src/androidTest` | Named in each task |
| `Mobile/engine/src/test/resources/goldens/analysis.json.gz`, `goldens/audio/silence.wav` | Generated |

`.../engine` stands for `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine`. Shell commands run from the repository root in Git Bash unless a step says otherwise.

---

### Task 1: The analysis golden file

**Files:**
- Modify: `tools/mobile_goldens.Dockerfile`, `tools/mobile_goldens.sh`
- Modify: `chordify_ai/export/mobile_goldens.py`
- Modify: `tests/ai/test_mobile_goldens.py`
- Create (generated): `Mobile/engine/src/test/resources/goldens/analysis.json.gz`, `Mobile/engine/src/test/resources/goldens/audio/silence.wav`

**Interfaces:**
- Consumes: `chordify_backend.app.pipeline.analysis` (`analyse`, `decode_chords`, `single_chord`, `NoMusicDetected`), `chordify_backend.app.pipeline.beats.track_beats`, `chordify_backend.app.pipeline.models.Models`, `chordify_backend.app.api.progressions.next_chords`, `chordify_backend.app.schemas` (`AnalysisResult`, `ProgressionRequest`, `ProgressionResponse`), `chordify_backend.app.errors.ApiError`; M3a's `BEAT_INPUTS`, `ACOUSTIC_BUNDLE`, `LM_MODEL`, `missing`, `_version`, `_f32`, `_f32_rows`, `_environment`.
- Produces (Python): `FILES["analysis"]`; `PINNED` now maps a package to its version; `NEEDS` maps each file to what it needs (`"nnls"`, `"librosa"`, `"onnxruntime"`); `missing(name)` keeps its messages.
- Produces (`analysis.json.gz`):
  - `models` (the server's `models` block), `sample_rate`, `analysis_id` (`"an_golden"`);
  - `inputs[]`, one per input (the six beat inputs plus `silence.wav`): `file`, `samples`, `filename`, `sha256`, `beats` (`beats`, `bpm`, `confidence`), `output` (`chord`, `boundary`, `bass`, float32), `first_pass[]` (`start`, `end`, `label`, `confidence`), `key` (`tonic`, `mode`, `confidence`), then `result` or `error` (`code`, `message`);
  - `replays[]`: `loop.wav`'s measurements with each of four option sets, `options` and `result`;
  - `quick_chord[]`: `file`, `start`, `end`, `chord` (float32), `label`, `confidence`;
  - `next_chords[]`: `request`, then `response` or `error`.

- [ ] **Step 1: Give the container the backend's API packages**

In `tools/mobile_goldens.Dockerfile`, replace
```dockerfile
onnxruntime==1.30.0 pytest \
```
with
```dockerfile
onnxruntime==1.30.0 fastapi pydantic pydantic-settings pytest \
```
The analysis golden calls the server's own pipeline, schemas and progression endpoint, which import FastAPI and pydantic. None of these packages changes a number in the file, so none is pinned.

In `tools/mobile_goldens.sh`:
- replace `# Regenerate the Android engine's features, beats and acoustic golden files in a Linux container` with `# Regenerate the Android engine's features, beats, acoustic and analysis golden files in a Linux container`;
- replace `#   tools/mobile_goldens.sh                  # the three files, from the committed audio inputs` with `#   tools/mobile_goldens.sh                  # the four files, from the committed audio inputs`;
- replace `--only features --only beats --only acoustic $*` with `--only features --only beats --only acoustic --only analysis $*`.

- [ ] **Step 2: Add a silent input**

In `chordify_ai/export/mobile_goldens.py`, in `render_audio`, directly below the line
```python
    write("waltz.wav", lambda: (synth.render_progression(WALTZ, sr=44100)[0], 44100))
```
add
```python
    write("silence.wav", lambda: (np.zeros(3 * 44100, dtype=np.float32), 44100))
```
Three seconds of digital silence: the server fails it with `NO_MUSIC_DETECTED`.

- [ ] **Step 3: Add the `analysis` section**

Directly above the line `FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden,`, add:
```python
# ---------------------------------------------------------------------------
# analysis: chordify_backend's analyse() end to end, next-chord suggestions and single_chord
# ---------------------------------------------------------------------------

ANALYSIS_INPUTS = BEAT_INPUTS + ["silence.wav"]
ANALYSIS_ID = "an_golden"
ANALYSIS_SR = 44100  # the server's decode_sample_rate
REPLAYS = [  # loop.wav's recorded outputs again, with each analysis option
    {"vocabulary": "majmin"}, {"vocabulary": "sevenths"}, {"predictions": False}, {"min_segment_beats": 2}]
QUICK_CLIPS = [("loop.wav", 0.0, 2.0), ("loop.wav", 2.0, 4.0), ("loop.wav", 4.0, 6.0), ("loop.wav", 6.0, 8.0),
               ("pulse.wav", 0.0, 2.0), ("silence.wav", 0.0, 1.5)]
PROGRESSION_REQUESTS = [
    {"chords": ["C", "G", "Am", "F"]},
    {"chords": ["G", "Em", "C", "D7"], "k": 5},
    {"chords": ["Am", "F", "C", "G"], "key": "C"},
    {"chords": ["D", "A/C#", "Bm", "G"], "durations_beats": [4, 2, 2, 8]},
    {"chords": ["Cmaj7", "Am7", "Dm7", "G7"], "key": "C major"},
    {"chords": ["N.C.", "E", "B", "C#m", "A"]},
    {"chords": ["Bb", "F/A", "Gm7", "Eb"], "k": 1},
    {"chords": ["Hm", "C"]},
    {"chords": ["N.C."]},
    {"chords": ["C", "G"], "durations_beats": [4]},
    {"chords": ["C", "F"], "key": "H major"},
]


def _analysis_models():
    from chordify_backend.app.pipeline.models import Models
    from chordify_core.acoustic import OnnxChordModel
    from chordify_core.lm import NgramProgressionModel

    bundle = json.loads((LM_MODEL.parent / "bundle.json").read_text())
    return Models(acoustic=OnnxChordModel(ACOUSTIC_BUNDLE), lm=NgramProgressionModel.load(LM_MODEL), lm_id=bundle["id"])


def _run_analyse(models, y: np.ndarray, **kwargs) -> dict:
    """analyse() as the server's job runs it: the document as stored, or the error it fails with."""
    from chordify_backend.app.pipeline import analysis
    from chordify_backend.app.schemas import AnalysisResult

    try:
        result = analysis.analyse(y, ANALYSIS_SR, models=models, analysis_id=ANALYSIS_ID, **kwargs)
    except analysis.NoMusicDetected as err:
        return {"error": {"code": "NO_MUSIC_DETECTED", "message": str(err)}}
    return {"result": AnalysisResult.model_validate(result).model_dump(mode="json", by_alias=True)}


def _replay(models, samples: int, beats, output, **kwargs) -> dict:
    """analyse() with its audio stages (beats, features, model) replaced by recorded results:
    what the engine's JVM tests replay."""
    from unittest import mock

    from chordify_backend.app.pipeline import analysis

    with mock.patch.object(analysis, "track_beats", lambda y, sr: beats), \
            mock.patch.object(analysis.features, "extract", lambda spec, y, sr: None), \
            mock.patch.object(models.acoustic, "predict", lambda raw: output):
        return _run_analyse(models, np.zeros(samples, dtype=np.float32), **kwargs)


def analysis_golden() -> dict:
    import hashlib
    from types import SimpleNamespace

    from chordify_backend.app.api import progressions
    from chordify_backend.app.errors import ApiError
    from chordify_backend.app.pipeline.analysis import decode_chords, single_chord
    from chordify_backend.app.pipeline.beats import track_beats
    from chordify_backend.app.schemas import ProgressionRequest, ProgressionResponse
    from chordify_core import audio, features, theory

    reason = missing("analysis")
    if reason:
        raise SystemExit(f"analysis.json.gz {reason}: run tools/mobile_goldens.sh")
    models = _analysis_models()
    spec = models.acoustic.feature_spec
    inputs, recorded = [], {}
    for name in ANALYSIS_INPUTS:
        data = (AUDIO_DIR / name).read_bytes()
        source = {"filename": name, "sha256": hashlib.sha256(data).hexdigest()}
        y = audio.load_audio(data, ANALYSIS_SR, filename=name)  # the server's decode
        beats = track_beats(y, ANALYSIS_SR)
        output = models.acoustic.predict(features.extract(spec, y, ANALYSIS_SR))
        outcome = _replay(models, len(y), beats, output, **source)
        assert outcome == _run_analyse(models, y, **source), f"replaying {name} differs from analysing it"
        first = decode_chords(models, output.chord, output.boundary, spec, len(y) / ANALYSIS_SR, beats)
        labels = [(models.acoustic.vocabulary.decode(s.index), s.duration) for s in first]
        key, confidence = theory.estimate_key_from_chords(labels)
        recorded[name] = (len(y), beats, output, source)
        inputs.append({"file": name, "samples": len(y), **source,
                       "beats": {"beats": beats.beats.tolist(), "bpm": beats.bpm, "confidence": beats.confidence},
                       "output": {"chord": _f32_rows(output.chord), "boundary": _f32(output.boundary),
                                  "bass": _f32_rows(output.extra["bass"])},
                       "first_pass": [{"start": s.start, "end": s.end, "label": label, "confidence": s.confidence}
                                      for s, (label, _) in zip(first, labels)],
                       "key": {**key.to_dict(), "confidence": confidence}, **outcome})
    samples, beats, output, source = recorded["loop.wav"]
    replays = [{"file": "loop.wav", "options": options, **_replay(models, samples, beats, output, **source, **options)}
               for options in REPLAYS]

    quick = []
    for name, start, end in QUICK_CLIPS:
        y = audio.load_audio((AUDIO_DIR / name).read_bytes(), ANALYSIS_SR, filename=name)
        clip = y[int(start * ANALYSIS_SR):int(end * ANALYSIS_SR)]
        label, confidence = single_chord(models, clip, ANALYSIS_SR)
        chord = models.acoustic.predict(features.extract(spec, clip, ANALYSIS_SR)).chord
        quick.append({"file": name, "start": start, "end": end, "chord": _f32_rows(chord), "label": label,
                      "confidence": confidence})

    request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(models=models)))
    suggestions = []
    for body in PROGRESSION_REQUESTS:
        try:
            response = progressions.next_chords(ProgressionRequest(**body), request)
            suggestions.append({"request": body, "response": ProgressionResponse.model_validate(response)
                               .model_dump(mode="json")})
        except ApiError as err:
            suggestions.append({"request": body, "error": {"code": err.code, "message": err.detail}})
    return {"environment": _environment() | {"onnxruntime": _version("onnxruntime")}, "models": models.ids,
            "sample_rate": ANALYSIS_SR, "analysis_id": ANALYSIS_ID, "inputs": inputs, "replays": replays,
            "quick_chord": quick, "next_chords": suggestions}
```
`_replay` is the heart of the file. It runs the server's `analyse()` with `track_beats`, `features.extract` and the model's `predict` replaced by what they returned for the real audio. The engine's JVM tests replay those same measurements. `analysis_golden` checks that replaying gives exactly what analysing the audio gives.

- [ ] **Step 4: Register the file and say what it needs**

Replace
```python
         "features": features_golden, "beats": beats_golden, "acoustic": acoustic_golden}
# The reference versions of what shapes a file; elsewhere it is skipped by default (and by the test).
PINNED = {"beats": ("librosa", "1.0.1"), "acoustic": ("onnxruntime", "1.30.0")}
GZIPPED = {"features", "beats", "acoustic"}  # large, so stored compressed
```
with
```python
         "features": features_golden, "beats": beats_golden, "acoustic": acoustic_golden,
         "analysis": analysis_golden}
# The reference versions of what shapes the files, and what each file needs; elsewhere a file is
# skipped by default (and by the test).
PINNED = {"librosa": "1.0.1", "onnxruntime": "1.30.0"}
NEEDS = {"features": ["nnls"], "beats": ["librosa"], "acoustic": ["onnxruntime"],
         "analysis": ["nnls", "librosa", "onnxruntime"]}
GZIPPED = {"features", "beats", "acoustic", "analysis"}  # large, so stored compressed
```
and, in `missing`, replace
```python
    if name == "features":
        from chordify_core.features import nnls_available

        return None if nnls_available() else "needs NNLS Chroma"
    if name in PINNED:
        package, version = PINNED[name]
        return None if _version(package) == version else f"needs {package}=={version}"
    return None
```
with
```python
    for need in NEEDS.get(name, []):
        if need == "nnls":
            from chordify_core.features import nnls_available

            if not nnls_available():
                return "needs NNLS Chroma"
        elif _version(need) != PINNED[need]:
            return f"needs {need}=={PINNED[need]}"
    return None
```
Then bring the module docstring up to date. Replace
```
    tools/mobile_goldens.sh                                     # features, beats, acoustic (needs Docker)
```
with
```
    tools/mobile_goldens.sh                                     # features, beats, acoustic, analysis (needs Docker)
```
and replace
```
features.json.gz needs the NNLS Chroma Vamp plugin, and beats.json.gz and acoustic.json.gz the
librosa and ONNX Runtime releases in PINNED. tools/mobile_goldens.sh provides all three in a Linux
container; elsewhere a file that cannot be reproduced is skipped, and missing(name) says why.
```
with
```
features.json.gz needs the NNLS Chroma Vamp plugin, beats.json.gz and acoustic.json.gz the librosa
and ONNX Runtime releases in PINNED, and analysis.json.gz all three (NEEDS). tools/mobile_goldens.sh
provides them in a Linux container; elsewhere a file that cannot be reproduced is skipped, and
missing(name) says why.
```

- [ ] **Step 5: Give the test the new file's tolerance**

In `tests/ai/test_mobile_goldens.py`, replace the docstring line
```
    tools/mobile_goldens.sh        # features, beats and acoustic, with NNLS Chroma and the pinned reference
```
with
```
    tools/mobile_goldens.sh        # features, beats, acoustic and analysis, with NNLS Chroma and the pinned reference
```
and replace the `TOLERANCE` line with
```python
TOLERANCE = {"features": (1e-4, 1e-5), "beats": (1e-4, 1e-5), "acoustic": (1e-4, 1e-5), "analysis": (1e-4, 1e-5)}
```
Run `python -m pytest tests/ai/test_mobile_goldens.py -q -p no:cacheprovider -rs`.
Expected: `5 passed, 3 skipped`, with `analysis needs NNLS Chroma` among the skips.

- [ ] **Step 6: Write the files in the container**

Check that Docker is running (start Docker Desktop if `docker info` fails) and that `df -h /c` shows at least 5 GB free. Then run `bash tools/mobile_goldens.sh --render-audio`.
Expected:
- the image rebuilds its Python layer;
- the four files are written, `analysis.json.gz` at about 1.5 MB;
- `features.json.gz`, `beats.json.gz` and `acoustic.json.gz` come out byte-identical, so `git status` does not list them;
- pytest says `8 passed`.

Then run `docker desktop stop`.

- [ ] **Step 7: Check what was written**

Run:
```bash
python - <<'EOF'
from chordify_ai.export import mobile_goldens as g
a = g.read_golden(g.DEFAULT_OUT, "analysis")
for row in a["inputs"]:
    r = row.get("result")
    print(row["file"], r and ("%(tonic)s %(mode)s" % r["key"]["global"], r["tempo"]["bpm"], [c["display"] for c in r["chords"]]) or row["error"])
print([q["label"] for q in a["quick_chord"]], [s.get("error", {}).get("code") for s in a["next_chords"]].count("INVALID_REQUEST"))
EOF
```
Expected (what the container gave while this plan was written):
```
loop.wav ('C major', 117.5, ['C', 'G', 'Am', 'F'])
loop_padded.wav ('C major', 117.5, ['N.C.', 'C', 'G', 'Am', 'F', 'N.C.'])
loop_48k.wav ('C major', 136.0, ['C', 'G', 'Am', 'F'])
sevenths_stereo_22k.wav ('C major', 123.0, ['Cmaj7', 'Am7', 'Dm7', 'G7', 'N.C.'])
pulse.wav ('C major', 117.5, ['C', 'G', 'Am', 'F'])
waltz.wav ('C major', 152.0, ['C', 'G', 'Am', 'F', 'C', 'G', 'Am', 'F'])
silence.wav {'code': 'NO_MUSIC_DETECTED', 'message': '101% of the audio has no recognisable chord.'}
['C:maj', 'G:maj', 'A:min', 'F:maj', 'C:maj', 'A:dim'] 4
```
"101%" is the server's own arithmetic: its last decoding unit runs past the end of the audio. The engine must reproduce it, not fix it.

- [ ] **Step 8: Commit**

```bash
git add tools/mobile_goldens.Dockerfile tools/mobile_goldens.sh chordify_ai/export/mobile_goldens.py \
  tests/ai/test_mobile_goldens.py Mobile/engine/src/test/resources/goldens
git commit -m "Write the golden file for the whole analysis" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The result document

**Files:**
- Modify: `Mobile/gradle/libs.versions.toml`, `Mobile/build.gradle`, `Mobile/engine/build.gradle`
- Modify: `.../engine/lm/Predict.kt`
- Create: `.../engine/model/AnalysisResult.kt`
- Create: `Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/JsonAssert.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/model/AnalysisResultJsonTest.kt`

**Interfaces:**
- Consumes: `analysis.json.gz` `inputs[].result`, `replays[].result`, `next_chords[].response` (Task 1); `lm.Prediction`, `lm.PriorEntry` (M1).
- Produces (package `...engine.model`): `@Serializable` data classes `KeyInfo`, `KeySegment`, `KeySummary(global, segments)`, `Alternative`, `ChordSegment`, `Bar`, `Section`, `TimeShare`, `Pattern`, `Summary`, `Tempo`, `Source`, `AnalysisResult`, `ProgressionKey`, `ProgressionResponse`, with the server's field names and defaults. Also `val ServerJson: Json`, `fun AnalysisResult.toJson(): String`, `fun parseAnalysisResult(text: String): AnalysisResult`. `lm.Prediction` and `lm.PriorEntry` become `@Serializable`.
- Produces (shared test code): `fun assertJsonClose(message: String, expected: JsonElement, actual: JsonElement, absolute: Double = 1e-9)`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/JsonAssert.kt`:
```kotlin
package com.arbaba.chordifymobile.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import kotlin.math.abs

/** Two JSON documents agree: the same keys (in any order), lists, strings, booleans and nulls,
 *  and every number within [absolute]. */
fun assertJsonClose(message: String, expected: JsonElement, actual: JsonElement, absolute: Double = 1e-9) {
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
                if (e.isString || x == null || y == null) {
                    assertEquals("$message at $path", e.content, a.content)
                } else if (abs(x - y) > absolute) {
                    fail("$message at $path: expected $x, got $y (allowed $absolute)")
                }
            }
        }
    }
    walk("$", expected, actual)
}
```
Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/model/AnalysisResultJsonTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.model

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisResultJsonTest {
    private val golden = Goldens.loadGz("analysis")

    private val results: List<Pair<String, JsonObject>> =
        golden.objects("inputs").filter { it.has("result") }.map { it.str("file")!! to it.getValue("result").jsonObject } +
            golden.objects("replays").map { "replay ${it.getValue("options")}" to it.getValue("result").jsonObject }

    @Test
    fun theServersResultsReadBackAsTheSameJson() {
        assertEquals(10, results.size)
        for ((name, json) in results) {
            val result = parseAnalysisResult(json.toString())
            assertJsonClose(name, json, Json.parseToJsonElement(result.toJson()), absolute = 0.0)
        }
    }

    @Test
    fun theServersSuggestionsReadBackAsTheSameJson() {
        for (case in golden.objects("next_chords").filter { it.has("response") }) {
            val json = case.getValue("response")
            val response = ServerJson.decodeFromJsonElement(ProgressionResponse.serializer(), json)
            assertJsonClose("${case.getValue("request")}", json,
                            ServerJson.encodeToJsonElement(ProgressionResponse.serializer(), response), absolute = 0.0)
        }
    }

    @Test
    fun defaultsAndNullsAreWritten() {
        val chord = ChordSegment(index = 0, start = 0.0, end = 1.0, label = "N", display = "N.C.", confidence = 0.9)
        val json = Json.parseToJsonElement(ServerJson.encodeToString(ChordSegment.serializer(), chord)).jsonObject
        assertEquals(listOf("index", "start", "end", "beats", "label", "display", "root", "quality", "bass", "roman",
                            "roman_triad", "function", "scale_hint", "confidence", "alternatives", "next", "theory_only",
                            "surprise"), json.keys.toList())
        assertEquals("null", json.getValue("surprise").toString())
        assertEquals("[]", json.getValue("next").toString())
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.model.AnalysisResultJsonTest'`
Expected: compilation fails with `Unresolved reference 'parseAnalysisResult'` (and `toJson`, `ServerJson`, `ProgressionResponse`, `ChordSegment`).

- [ ] **Step 3: Add the serialization compiler plugin**

In `Mobile/gradle/libs.versions.toml`, under `[plugins]` after the `kotlin-android` line, add:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```
In `Mobile/build.gradle`, after `    alias(libs.plugins.kotlin.android) apply false`, add `    alias(libs.plugins.kotlin.serialization) apply false`. In `Mobile/engine/build.gradle`, in `plugins`, after `    alias(libs.plugins.kotlin.android)`, add `    alias(libs.plugins.kotlin.serialization)`.

- [ ] **Step 4: Make predictions serialisable**

In `.../engine/lm/Predict.kt`:
- after `import com.arbaba.chordifymobile.engine.vocab.parse` add `import kotlinx.serialization.SerialName` and `import kotlinx.serialization.Serializable`;
- replace the comment line `// High-level prediction with explanations, from chordify_core/lm.py.` with
```kotlin
// High-level prediction with explanations, from chordify_core/lm.py. Both classes serialise as the
// server's Prediction and PriorEntry (chordify_backend/app/schemas.py).
```
- put `@Serializable` on the line above `data class Prediction(` and above `data class PriorEntry(`;
- replace `    val cacheCount: Int = 0,` with `    @SerialName("cache_count") val cacheCount: Int = 0,` and `    val expectedBeats: Double? = null,` with `    @SerialName("expected_beats") val expectedBeats: Double? = null,`.

- [ ] **Step 5: Write the document**

Create `.../engine/model/AnalysisResult.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.model

import com.arbaba.chordifymobile.engine.lm.Prediction
import com.arbaba.chordifymobile.engine.lm.PriorEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The analysis document and the next-chord response as the server stores and serves them
// (chordify_backend/app/schemas.py): the same fields, names and defaults, so either side reads
// what the other writes. Lists the server leaves out of a chord (a "no chord" has no
// predictions) default to empty, as pydantic fills them.

@Serializable
data class KeyInfo(val tonic: String, val mode: String, val confidence: Double = 0.0)

@Serializable
data class KeySegment(val tonic: String, val mode: String, val confidence: Double = 0.0, val start: Double, val end: Double)

@Serializable
data class KeySummary(val global: KeyInfo, val segments: List<KeySegment>)

@Serializable
data class Alternative(val label: String, val display: String, val p: Double)

@Serializable
data class ChordSegment(
    val index: Int,
    val start: Double,
    val end: Double,
    val beats: Int? = null,
    val label: String,
    val display: String,
    val root: String? = null,
    val quality: String? = null,
    val bass: String? = null,
    val roman: String? = null,
    @SerialName("roman_triad") val romanTriad: String? = null,
    val function: String? = null,
    @SerialName("scale_hint") val scaleHint: String? = null,
    val confidence: Double,
    val alternatives: List<Alternative> = emptyList(),
    val next: List<Prediction> = emptyList(),
    @SerialName("theory_only") val theoryOnly: List<PriorEntry> = emptyList(),
    val surprise: Double? = null,
)

@Serializable
data class Bar(val start: Double, val end: Double, val chords: List<String>)

@Serializable
data class Section(val letter: String, val label: String, val start: Double, val end: Double)

@Serializable
data class TimeShare(val display: String, val seconds: Double, val share: Double)

@Serializable
data class Pattern(val roman: List<String>, val name: String? = null, val count: Int)

@Serializable
data class Summary(
    @SerialName("unique_chords") val uniqueChords: Int,
    @SerialName("time_share") val timeShare: List<TimeShare>,
    val patterns: List<Pattern>,
    val predictability: Double? = null,
)

@Serializable
data class Tempo(val bpm: Double?, val meter: String, val confidence: Double = 0.0)

@Serializable
data class Source(
    val filename: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Double,
    val sha256: String? = null,
)

@Serializable
data class AnalysisResult(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("analysis_id") val analysisId: String,
    val status: String = "completed",
    val source: Source,
    val models: Map<String, String>,
    val tempo: Tempo,
    val key: KeySummary,
    val beats: List<Double>,
    val downbeats: List<Double>,
    val bars: List<Bar>,
    val sections: List<Section> = emptyList(),
    val chords: List<ChordSegment>,
    val summary: Summary,
)

@Serializable
data class ProgressionKey(val tonic: String, val mode: String, val confidence: Double = 0.0, val estimated: Boolean)

@Serializable
data class ProgressionResponse(
    @SerialName("key_used") val keyUsed: ProgressionKey,
    val predictions: List<Prediction>,
    @SerialName("theory_only") val theoryOnly: List<PriorEntry>,
    val model: String,
)

/** The server's JSON: every field written, defaults and nulls included, as pydantic's model_dump. */
val ServerJson: Json = Json { encodeDefaults = true }

fun AnalysisResult.toJson(): String = ServerJson.encodeToString(AnalysisResult.serializer(), this)

fun parseAnalysisResult(text: String): AnalysisResult = ServerJson.decodeFromString(AnalysisResult.serializer(), text)
```
`ServerJson` writes defaults. That is what makes a "no chord" entry carry `"next": []` and `"surprise": null`, as pydantic writes them.

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `AnalysisResultJsonTest` 3 passed, 63 in all.

- [ ] **Step 7: Commit**

```bash
git add Mobile/gradle/libs.versions.toml Mobile/build.gradle Mobile/engine
git commit -m "Add the analysis document and its JSON to the engine" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The analysis from beats and the model's output

**Files:**
- Modify: `.../engine/Errors.kt`, `.../engine/beats/BeatInfo.kt`
- Create: `.../engine/analysis/Models.kt`, `.../engine/analysis/Analysis.kt`
- Create: `Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/AnalysisGoldens.kt`
- Create: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/JvmModels.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/AnalysisGoldenTest.kt`

**Interfaces:**
- Consumes:
  - from M1: `decode`, `uniformChangeMatrix`, `inversion`, `meanOver`, `Segment`, `estimateKeyFromChords`, `describe`, `findPatterns`, `Key`, `predictNext`, `token`, `NgramProgressionModel.changeMatrix` and `distribution`, `VOCABULARIES`, `TIERS`, `Vocabulary`, `parse`, `displayName`, `simplify`, `transpose`, `pyRound`;
  - from M3a: `ChordBundle`, `AcousticOutput`, `BeatInfo`;
  - from Task 2: `model.*`, `assertJsonClose`; and `analysis.json.gz` from Task 1.
- Produces:
  - `ErrorCode.NO_MUSIC_DETECTED`;
  - in `...engine.beats`: `fun downbeatPhase(beats: DoubleArray, changeTimes: List<Double>, beatsPerBar: Int = 4, tolerance: Double = 0.08): Int`;
  - in `...engine.analysis`:
    - `class Models(bundle: ChordBundle, lm: NgramProgressionModel, lmId: String)`, with `vocabulary`, `ids`, `Models.BEATS_ID`, `Models.LM_BUNDLE`, `Models.readLmBundle(text): Pair<String, String>`;
    - `const val LM_PRIOR_WEIGHT`, `const val NO_MUSIC_THRESHOLD`;
    - `data class AnalysisOptions(vocabulary: String? = null, predictions: Boolean = true, minSegmentBeats: Int = 1)`;
    - `data class SourceInfo(analysisId: String, filename: String? = null, sha256: String? = null)`;
    - `enum class Stage(val id: String)`: `DECODE`, `ACOUSTIC`, `KEY`, `DECODE_CHORDS`, `ANALYSE`, `DONE`;
    - `class Measurements(samples: Long, beats: BeatInfo, output: AcousticOutput)`, with `duration`;
    - `fun targetVocabulary(models, options): Vocabulary`;
    - `fun decodeChords(models, posteriors, boundary, duration, beats, key = null, minUnits = 1): List<Segment>`;
    - `fun toDouble(rows: Array<FloatArray>): Array<DoubleArray>`;
    - `fun firstPass(models, measured): Pair<List<Segment>, Pair<Key, Double>>`;
    - `fun analyseMeasured(models, measured, options, source, onProgress = { _, _ -> }, isCancelled = { false }): AnalysisResult`;
  - shared test code: `JsonObject.measurements(): Measurements`, `JsonObject.sourceInfo(analysisId): SourceInfo`;
  - JVM test code: `fun repoFile(path: String): File`, `val JVM_MODELS: Models`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/AnalysisGoldens.kt`:
```kotlin
package com.arbaba.chordifymobile.engine

import com.arbaba.chordifymobile.engine.acoustic.AcousticOutput
import com.arbaba.chordifymobile.engine.analysis.Measurements
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.beats.BeatInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The analysis golden file (analysis.json.gz): what the server measured for each input, and
// what its analyse() returned.

/** The beats and chord model output recorded for one input, as the engine's analysis takes them. */
fun JsonObject.measurements(): Measurements {
    val beats = obj("beats")
    val output = obj("output")
    val bpm = beats.getValue("bpm").jsonPrimitive.doubleOrNull
    return Measurements(
        samples = getValue("samples").jsonPrimitive.long,
        beats = BeatInfo(beats.getValue("beats").doubles(), bpm, beats.double("confidence")),
        output = AcousticOutput(chord = output.getValue("chord").floatMatrix(), boundary = output.getValue("boundary").floats(),
                                keyTonic = emptyArray(), keyMode = emptyArray(), root = emptyArray(),
                                bass = output.getValue("bass").floatMatrix()),
    )
}

/** The analysis_id, file name and SHA-256 the server's result was made with. */
fun JsonObject.sourceInfo(analysisId: String): SourceInfo = SourceInfo(analysisId, str("filename"), str("sha256"))
```
Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/JvmModels.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import java.io.File

/** A file of the repository, found from the module directory Gradle runs unit tests in. */
fun repoFile(path: String): File {
    val start = File("").absoluteFile
    return generateSequence(start) { it.parentFile }.map { File(it, path) }.firstOrNull { it.exists() }
        ?: error("$path not found above $start")
}

/** The shipped models, read from the repository's models/ (no ONNX session: the JVM tests start
 *  from the chord model's recorded output). */
val JVM_MODELS: Models by lazy {
    val bundle = ChordBundle.parse(repoFile("models/chordnet-chroma/3.0.0/bundle.json").readText())
    val (lmId, lmFile) = Models.readLmBundle(repoFile("${Models.LM_BUNDLE}/bundle.json").readText())
    Models(bundle, repoFile("${Models.LM_BUNDLE}/$lmFile").inputStream().use { NgramProgressionModel.load(it) }, lmId)
}
```
Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/AnalysisGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.measurements
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.sourceInfo
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisGoldenTest {
    private val golden = Goldens.loadGz("analysis")
    private val analysisId = golden.str("analysis_id")!!
    private val inputs = golden.objects("inputs")
    private val analysed = inputs.filter { it.has("result") }

    private fun analyse(input: JsonObject, options: AnalysisOptions = AnalysisOptions()) =
        analyseMeasured(JVM_MODELS, input.measurements(), options, input.sourceInfo(analysisId))

    @Test
    fun theModelsBlockIsTheServers() {
        assertEquals(golden.obj("models").mapValues { it.value.jsonPrimitive.content }, JVM_MODELS.ids)
    }

    @Test
    fun theFirstDecodingAndTheKeyMatchPython() {
        assertEquals(6, analysed.size)
        for (input in analysed) {
            val name = input.str("file")!!
            val (segments, estimate) = firstPass(JVM_MODELS, input.measurements())
            val expected = input.objects("first_pass")
            assertEquals("$name segments", expected.size, segments.size)
            for ((want, got) in expected.zip(segments)) {
                assertEquals("$name start", want.double("start"), got.start, 1e-9)
                assertEquals("$name end", want.double("end"), got.end, 1e-9)
                assertEquals("$name label", want.str("label"), JVM_MODELS.vocabulary.decode(got.index))
                assertEquals("$name confidence", want.double("confidence"), got.confidence, 1e-9)
            }
            val (key, confidence) = estimate
            val wantKey = input.obj("key")
            assertEquals("$name key", "${wantKey.str("tonic")} ${wantKey.str("mode")}", key.name)
            assertEquals("$name key confidence", wantKey.double("confidence"), confidence, 1e-9)
        }
    }

    @Test
    fun resultsMatchPython() {
        for (input in analysed) {
            assertJsonClose(input.str("file")!!, input.getValue("result"), Json.parseToJsonElement(analyse(input).toJson()))
        }
    }

    @Test
    fun optionsChangeTheResultAsOnTheServer() {
        val loop = inputs.single { it.str("file") == "loop.wav" }
        for (replay in golden.objects("replays")) {
            val given = replay.obj("options")
            val options = AnalysisOptions(
                vocabulary = given["vocabulary"]?.jsonPrimitive?.content,
                predictions = given["predictions"]?.jsonPrimitive?.boolean ?: true,
                minSegmentBeats = given["min_segment_beats"]?.jsonPrimitive?.int ?: 1,
            )
            assertJsonClose("$given", replay.getValue("result"), Json.parseToJsonElement(analyse(loop, options).toJson()))
        }
    }

    @Test
    fun silenceFailsWithNoMusicDetected() {
        val silence = inputs.single { it.str("file") == "silence.wav" }
        val error = assertThrows(EngineException::class.java) { analyse(silence) }
        assertEquals(ErrorCode.valueOf(silence.obj("error").str("code")!!), error.code)
        assertEquals(silence.obj("error").str("message"), error.message)
    }

    @Test
    fun aFinerVocabularyThanTheModelKnowsIsRefused() {
        val text = repoFile("models/chordnet-chroma/3.0.0/bundle.json").readText()
        val majmin = Models(ChordBundle.parse(text.replace("\"vocabulary\": \"large\"", "\"vocabulary\": \"majmin\"")),
                            JVM_MODELS.lm, JVM_MODELS.lmId)
        val error = assertThrows(IllegalArgumentException::class.java) { targetVocabulary(majmin, AnalysisOptions("sevenths")) }
        assertEquals("the active chord model knows the 'majmin' chord types, not 'sevenths'", error.message)
    }

    @Test
    fun stagesAreReportedInOrder() {
        val stages = ArrayList<Pair<Stage, Double>>()
        val loop = analysed.first()
        analyseMeasured(JVM_MODELS, loop.measurements(), AnalysisOptions(), loop.sourceInfo(analysisId),
                        onProgress = { stage, fraction -> stages += stage to fraction })
        assertEquals(listOf(Stage.KEY to 0.7, Stage.DECODE_CHORDS to 0.8, Stage.ANALYSE to 0.9), stages)
    }

    @Test
    fun cancellingStopsTheAnalysis() {
        val loop = analysed.first()
        assertThrows(CancelledException::class.java) {
            analyseMeasured(JVM_MODELS, loop.measurements(), AnalysisOptions(), loop.sourceInfo(analysisId),
                            isCancelled = { true })
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.analysis.AnalysisGoldenTest'`
Expected: compilation fails with `Unresolved reference 'Measurements'` (and `Models`, `analyseMeasured`, `firstPass`, `NO_MUSIC_DETECTED`, …).

- [ ] **Step 3: Add the error code and the downbeat phase**

In `.../engine/Errors.kt`, replace
```kotlin
 *  message for each, plus MODEL_UNAVAILABLE for a model file that fails its SHA-256 check or needs
 *  features this engine does not compute. M3b adds NO_MUSIC_DETECTED. */
enum class ErrorCode { UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG, MODEL_UNAVAILABLE }
```
with
```kotlin
 *  message for each, plus MODEL_UNAVAILABLE for a model file that fails its SHA-256 check or needs
 *  features this engine does not compute. */
enum class ErrorCode {
    UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG, NO_MUSIC_DETECTED, MODEL_UNAVAILABLE,
}
```
In `.../engine/beats/BeatInfo.kt`, add `import kotlin.math.abs` above `import kotlin.math.sqrt`, and append to the end of the file:
```kotlin
/** downbeat_phase (chordify_backend/app/pipeline/beats.py): the bar phase whose downbeats
 *  coincide with the most chord changes, the first such phase on a tie. */
fun downbeatPhase(beats: DoubleArray, changeTimes: List<Double>, beatsPerBar: Int = 4, tolerance: Double = 0.08): Int {
    if (beats.size < beatsPerBar || changeTimes.isEmpty()) return 0
    var best = 0
    var bestScore = -1
    for (phase in 0 until beatsPerBar) {
        val downbeats = beats.filterIndexed { i, _ -> i >= phase && (i - phase) % beatsPerBar == 0 }
        val score = changeTimes.count { change -> downbeats.minOf { abs(change - it) } < tolerance }
        if (score > bestScore) {
            best = phase
            bestScore = score
        }
    }
    return best
}
```

- [ ] **Step 4: Write the models and the analysis**

Create `.../engine/analysis/Models.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import com.arbaba.chordifymobile.engine.vocab.VOCABULARIES
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What an analysis decodes with, as chordify_backend/app/pipeline/models.py's Models: the chord
 *  model's bundle (its vocabulary, decoder settings and class prior) and the progression model.
 *  The ONNX session itself lives in ChordModel, so everything here also runs on the JVM. */
class Models(val bundle: ChordBundle, val lm: NgramProgressionModel, val lmId: String) {
    val vocabulary: Vocabulary = VOCABULARIES.getValue(bundle.vocabulary)

    /** The result's "models" block, in the server's order. */
    val ids: Map<String, String> = linkedMapOf(
        "chord" to bundle.id, "lm" to lmId, "beats" to BEATS_ID, "features" to bundle.featureSpec.kind,
        "vocabulary" to vocabulary.name,
    )

    companion object {
        /** chordify_backend.app.pipeline.beats.TRACKER_ID */
        const val BEATS_ID = "librosa-beat-track"
        const val LM_BUNDLE = "models/progression-ngram/1.0.0"

        /** The id and model file name of a progression model's bundle.json. */
        fun readLmBundle(text: String): Pair<String, String> {
            val json = Json.parseToJsonElement(text).jsonObject
            return json.getValue("id").jsonPrimitive.content to
                json.getValue("files").jsonObject.getValue("model").jsonPrimitive.content
        }
    }
}
```
Create `.../engine/analysis/Analysis.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.acoustic.AcousticOutput
import com.arbaba.chordifymobile.engine.audio.ANALYSIS_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.beats.BeatInfo
import com.arbaba.chordifymobile.engine.beats.downbeatPhase
import com.arbaba.chordifymobile.engine.decode.Segment
import com.arbaba.chordifymobile.engine.decode.decode
import com.arbaba.chordifymobile.engine.decode.inversion
import com.arbaba.chordifymobile.engine.decode.meanOver
import com.arbaba.chordifymobile.engine.decode.uniformChangeMatrix
import com.arbaba.chordifymobile.engine.lm.predictNext
import com.arbaba.chordifymobile.engine.lm.token
import com.arbaba.chordifymobile.engine.model.Alternative
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.Bar
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.engine.model.KeyInfo
import com.arbaba.chordifymobile.engine.model.KeySegment
import com.arbaba.chordifymobile.engine.model.KeySummary
import com.arbaba.chordifymobile.engine.model.Pattern
import com.arbaba.chordifymobile.engine.model.Source
import com.arbaba.chordifymobile.engine.model.Summary
import com.arbaba.chordifymobile.engine.model.Tempo
import com.arbaba.chordifymobile.engine.model.TimeShare
import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.theory.describe
import com.arbaba.chordifymobile.engine.theory.estimateKeyFromChords
import com.arbaba.chordifymobile.engine.theory.findPatterns
import com.arbaba.chordifymobile.engine.util.pyRound
import com.arbaba.chordifymobile.engine.vocab.NO_CHORD
import com.arbaba.chordifymobile.engine.vocab.TIERS
import com.arbaba.chordifymobile.engine.vocab.VOCABULARIES
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import com.arbaba.chordifymobile.engine.vocab.displayName
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.simplify
import com.arbaba.chordifymobile.engine.vocab.transpose
import kotlin.math.log2

// Port of chordify_backend/app/pipeline/analysis.py from the point where beats and the chord
// model's output are known: two decodings, the key, chord names, theory, predictions, downbeats,
// bars and the summary. The audio stages before it are ChordifyEngine.analyse's.

/** The progression model's share of the second decoding's change prior (LM_PRIOR_WEIGHT). */
const val LM_PRIOR_WEIGHT = 0.5

/** The share of no-chord time above which an analysis fails (the server's no_music_threshold). */
const val NO_MUSIC_THRESHOLD = 0.9

/** The server's AnalysisOptions (chordify_backend/app/schemas.py). */
data class AnalysisOptions(val vocabulary: String? = null, val predictions: Boolean = true, val minSegmentBeats: Int = 1) {
    init {
        require(minSegmentBeats in 1..8) { "min_segment_beats must be between 1 and 8, not $minSegmentBeats" }
    }
}

/** What the result says about its audio: the server's analysis_id, and the file's name and SHA-256. */
data class SourceInfo(val analysisId: String, val filename: String? = null, val sha256: String? = null)

/** Progress stages, one list for the engine and the app (spec §5). [id] is the web app's name. */
enum class Stage(val id: String) {
    DECODE("decode"), ACOUSTIC("acoustic"), KEY("key"), DECODE_CHORDS("decode_chords"), ANALYSE("analyse"), DONE("done"),
}

/** What the audio stages measured: the length at 44.1 kHz, the beats and the chord model's output. */
class Measurements(val samples: Long, val beats: BeatInfo, val output: AcousticOutput) {
    val duration: Double get() = samples.toDouble() / ANALYSIS_SAMPLE_RATE
}

/** The chord types a result reports: the model's own, or the coarser tier [AnalysisOptions.vocabulary]
 *  asks for. A finer tier than the model knows is refused, as the server refuses it. */
fun targetVocabulary(models: Models, options: AnalysisOptions): Vocabulary {
    val name = options.vocabulary ?: return models.vocabulary
    val target = VOCABULARIES[name] ?: throw IllegalArgumentException("unknown vocabulary '$name'")
    require(TIERS.indexOf(target.name) <= TIERS.indexOf(models.vocabulary.name)) {
        "the active chord model knows the '${models.vocabulary.name}' chord types, not '${target.name}'"
    }
    return target
}

/** decode_chords: beat-synchronous decoding with the bundle's settings; with [key], the
 *  progression model supplies the change prior, blended with uniform. */
fun decodeChords(
    models: Models,
    posteriors: Array<DoubleArray>,
    boundary: DoubleArray,
    duration: Double,
    beats: BeatInfo,
    key: Key? = null,
    minUnits: Int = 1,
): List<Segment> {
    val spec = models.bundle.featureSpec
    val settings = models.bundle.decoder
    val matrix = key?.let {
        val uniform = uniformChangeMatrix(models.vocabulary.size)
        val progression = models.lm.changeMatrix(models.vocabulary, it)
        Array(uniform.size) { i ->
            DoubleArray(uniform.size) { j -> (1 - LM_PRIOR_WEIGHT) * uniform[i][j] + LM_PRIOR_WEIGHT * progression[i][j] }
        }
    }
    return decode(
        posteriors, spec.frameRate, beats = if (beats.usable) beats.beats else null, changeProb = boundary,
        prior = models.bundle.classPrior, alpha = settings["alpha"] ?: 0.5, selfProb = settings["self_prob"],
        changeMatrix = matrix, subdivide = settings["subdivide"]?.toInt() ?: 1, minUnits = minUnits,
        boundaryWeight = settings["boundary_weight"] ?: 0.5, frameOffset = spec.offset, duration = duration,
    )
}

/** The chord model's probabilities in float64, as decode() converts them. */
fun toDouble(rows: Array<FloatArray>): Array<DoubleArray> =
    Array(rows.size) { t -> DoubleArray(rows[t].size) { rows[t][it].toDouble() } }

/** The first decoding and the key estimated from it; fails with NO_MUSIC_DETECTED when almost
 *  all of the audio decodes as "no chord". */
fun firstPass(models: Models, measured: Measurements): Pair<List<Segment>, Pair<Key, Double>> {
    val duration = measured.duration
    val first = decodeChords(models, toDouble(measured.output.chord), measured.output.boundary.toDouble(), duration, measured.beats)
    val labels = first.map { models.vocabulary.decode(it.index) to it.duration }
    val noChord = labels.filter { it.first == NO_CHORD }.sumOf { it.second }
    if (duration > 0 && noChord / duration > NO_MUSIC_THRESHOLD) {
        throw EngineException(ErrorCode.NO_MUSIC_DETECTED, "${percent(noChord / duration)} of the audio has no recognisable chord.")
    }
    return first to estimateKeyFromChords(labels)
}

/** The server's analyse() once [measured] is known: the whole analysis document. */
fun analyseMeasured(
    models: Models,
    measured: Measurements,
    options: AnalysisOptions,
    source: SourceInfo,
    onProgress: (Stage, Double) -> Unit = { _, _ -> },
    isCancelled: () -> Boolean = { false },
): AnalysisResult {
    val target = targetVocabulary(models, options)
    val vocabulary = models.vocabulary
    val duration = measured.duration
    val beats = measured.beats
    val output = measured.output
    val posteriors = toDouble(output.chord)

    onProgress(Stage.KEY, 0.7)
    val (_, estimate) = firstPass(models, measured)
    val (key, keyConfidence) = estimate
    checkCancelled(isCancelled)
    onProgress(Stage.DECODE_CHORDS, 0.8)
    val segments = decodeChords(models, posteriors, output.boundary.toDouble(), duration, beats, key, options.minSegmentBeats)
    checkCancelled(isCancelled)
    onProgress(Stage.ANALYSE, 0.9)

    val beatTimes = beats.beats
    val threshold = models.bundle.decoder["inversion_threshold"]
    val bass = if (threshold != null) toDouble(output.bass) else null
    val frameTimes = models.bundle.featureSpec.frameTimes(output.chord.size)

    fun name(index: Int, segment: Segment? = null): String {
        var label = vocabulary.decode(index)
        if (bass != null && segment != null) {
            label = inversion(label, meanOver(bass, frameTimes, segment.start, segment.end), threshold!!)
        }
        if (target !== vocabulary) label = simplify(label, target)
        return if (parse(label).isChord) transpose(label, 0, flats = key.flats) else label
    }

    val merged = ArrayList<Merged>() // a coarser vocabulary can make neighbours equal (Cmaj7, C6 -> C, C)
    for (segment in segments) {
        val label = name(segment.index, segment)
        val last = merged.lastOrNull()
        if (last != null && last.label == label) {
            val total = (last.end - last.start) + segment.duration
            last.confidence = ((last.end - last.start) * last.confidence + segment.duration * segment.confidence) / total
            last.end = segment.end
        } else {
            merged += Merged(segment.start, segment.end, label, segment.confidence,
                             segment.alternatives.map { (j, p) -> name(j) to p })
        }
    }

    val chords = merged.mapIndexedTo(ArrayList()) { i, segment -> chordEntry(i, segment, key, target, beatTimes) }
    val real = chords.indices.filter { parse(chords[it].label).isChord }
    var predictability: Double? = null
    if (options.predictions && real.isNotEmpty()) {
        val tokens = real.map { token(chords[it].label, key)!! }
        val observed = ArrayList<Double>()
        for ((j, at) in real.withIndex()) {
            val history = real.subList(0, j + 1).map { chords[it].label }
            val beatsSoFar = if (beatTimes.isNotEmpty()) real.subList(0, j + 1).map { (chords[it].beats ?: 0).toDouble() } else null
            val (predictions, prior) = predictNext(models.lm, history, key, k = 3, historyBeats = beatsSoFar)
            var surprise: Double? = null
            if (j > 0 && tokens[j] != tokens[j - 1]) {
                val (distribution, _) = models.lm.distribution(tokens.subList(0, j))
                val p = maxOf(distribution[tokens[j]] ?: 0.0, 1e-6)
                surprise = pyRound(-log2(p), 3)
                observed += p
            }
            chords[at] = chords[at].copy(next = predictions, theoryOnly = prior, surprise = surprise)
        }
        if (observed.isNotEmpty()) predictability = pyRound(observed.average(), 4)
    }

    var downbeats = DoubleArray(0)
    if (beatTimes.isNotEmpty()) {
        val phase = downbeatPhase(beatTimes, chords.drop(1).map { it.start })
        downbeats = beatTimes.filterIndexed { i, _ -> i >= phase && (i - phase) % 4 == 0 }.toDoubleArray()
    }

    val share = LinkedHashMap<String, Double>()
    for (i in real) share[chords[i].display] = (share[chords[i].display] ?: 0.0) + (chords[i].end - chords[i].start)
    val total = share.values.sum().takeIf { it != 0.0 } ?: 1.0
    val romans = real.mapNotNull { chords[it].romanTriad }.filter { it.isNotEmpty() } // loops match bare numerals: V7 counts as V
    val keyInfo = KeyInfo(key.tonicName, key.mode, pyRound(keyConfidence, 4))
    return AnalysisResult(
        analysisId = source.analysisId,
        source = Source(filename = source.filename, duration = pyRound(duration, 3), sha256 = source.sha256),
        models = models.ids,
        tempo = Tempo(beats.bpm, "4/4", beats.confidence),
        key = KeySummary(keyInfo, listOf(KeySegment(keyInfo.tonic, keyInfo.mode, keyInfo.confidence, 0.0, pyRound(duration, 3)))),
        beats = beatTimes.map { pyRound(it, 3) },
        downbeats = downbeats.map { pyRound(it, 3) },
        bars = if (downbeats.isNotEmpty()) bars(downbeats, duration, chords) else emptyList(),
        chords = chords,
        summary = Summary(
            uniqueChords = share.size,
            timeShare = share.entries.sortedByDescending { it.value }
                .map { (display, seconds) -> TimeShare(display, pyRound(seconds, 3), pyRound(seconds / total, 4)) },
            patterns = findPatterns(romans).map { Pattern(it.roman, it.name, it.count) },
            predictability = predictability,
        ),
    )
}

private class Merged(var start: Double, var end: Double, val label: String, var confidence: Double,
                     val alternatives: List<Pair<String, Double>>)

private fun chordEntry(index: Int, segment: Merged, key: Key, target: Vocabulary, beatTimes: DoubleArray): ChordSegment {
    val label = segment.label
    val chord = parse(label)
    val described = if (chord.isChord) describe(label, key) else null
    return ChordSegment(
        index = index,
        start = pyRound(segment.start, 3),
        end = pyRound(segment.end, 3),
        beats = if (beatTimes.isEmpty()) null else beatTimes.count { it >= segment.start - 1e-3 && it < segment.end - 1e-3 },
        label = label,
        display = displayName(label, flats = key.flats),
        root = chord.root?.let { key.spell(it) },
        quality = if (chord.isChord) target.qualityOf(target.encode(label)!!) else null,
        bass = if (chord.isChord) key.spell(chord.bass!!) else null,
        roman = described?.roman,
        romanTriad = described?.romanTriad,
        function = described?.function,
        scaleHint = described?.scaleHint,
        confidence = pyRound(segment.confidence, 4),
        alternatives = segment.alternatives.filter { it.first != label }
            .map { (alt, p) -> Alternative(alt, displayName(alt, key.flats), pyRound(p, 4)) },
    )
}

/** _bars: the chords sounding in each bar, a pickup bar first when the first downbeat is late. */
internal fun bars(downbeats: DoubleArray, end: Double, chords: List<ChordSegment>): List<Bar> {
    val bounds = ArrayList(downbeats.toList()).apply { add(end) }
    if (bounds[0] > 0.3) bounds.add(0, 0.0) // pickup before the first downbeat
    val out = ArrayList<Bar>()
    for (i in 0 until bounds.size - 1) {
        val start = bounds[i]
        val stop = bounds[i + 1]
        if (stop - start < 1e-3) continue
        val names = ArrayList<String>()
        for (chord in chords) {
            val overlap = minOf(stop, chord.end) - maxOf(start, chord.start)
            if (overlap >= 0.25 * (stop - start) && (names.isEmpty() || names.last() != chord.display)) names += chord.display
        }
        out += Bar(pyRound(start, 3), pyRound(stop, 3), names)
    }
    return out
}

/** Python's f"{x:.0%}". */
internal fun percent(x: Double): String = "${pyRound(x * 100, 0).toLong()}%"

private fun FloatArray.toDouble(): DoubleArray = DoubleArray(size) { this[it].toDouble() }

private fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancelledException()
}
```
Line for line, this follows `analyse()` after `progress("acoustic", 0.6, None)`. Where the result could change:
- `decode()` turns the model's float32 probabilities into float64 first; `toDouble` does the same.
- Each chord's `beats` counts beats against the merged segment's unrounded bounds, while the downbeat phase and the bars use the rounded `start` and `end`.
- Python's `Counter.most_common()` keeps first-seen order among equal times, and so does a stable `sortedByDescending` over a `LinkedHashMap`.

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `AnalysisGoldenTest` 8 passed, 71 in all.

If `resultsMatchPython` fails, the message names the first JSON path that differs. Check `theFirstDecodingAndTheKeyMatchPython` first, since every later step depends on it. Do not loosen the tolerance: the replay starts from the server's own numbers.

- [ ] **Step 6: Commit**

```bash
git add Mobile/engine
git commit -m "Port the server's analysis after the audio stages to the engine" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Next-chord suggestions and Quick chord

**Files:**
- Create: `.../engine/analysis/Suggest.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/SuggestGoldenTest.kt`

**Interfaces:**
- Consumes: `Models`, `JVM_MODELS` (Task 3); `parseDisplay`, `parseKey`, `estimateKeyFromChords`, `predictNext`, `argmax`, `pyRound` (M1); `ProgressionKey`, `ProgressionResponse`, `ServerJson` (Task 2); `analysis.json.gz` `next_chords`, `quick_chord` (Task 1).
- Produces (package `...engine.analysis`): `fun nextChords(models: Models, chords: List<String>, key: String? = null, k: Int = 3, durationsBeats: List<Double>? = null): ProgressionResponse`; `fun singleChord(chord: Array<FloatArray>, vocabulary: Vocabulary): Pair<String, Double>`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/SuggestGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import com.arbaba.chordifymobile.engine.strings
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuggestGoldenTest {
    private val golden = Goldens.loadGz("analysis")

    @Test
    fun nextChordsAnswersAsTheServer() {
        val cases = golden.objects("next_chords")
        assertEquals(11, cases.size)
        for (case in cases) {
            val request = case.obj("request")
            val call = {
                nextChords(JVM_MODELS, request.getValue("chords").strings().map { it!! },
                           key = request["key"]?.jsonPrimitive?.content, k = request["k"]?.jsonPrimitive?.int ?: 3,
                           durationsBeats = request["durations_beats"]?.doubles()?.toList())
            }
            if (case.has("error")) {
                val error = assertThrows(IllegalArgumentException::class.java) { call() }
                assertEquals("$request", case.obj("error").str("message"), error.message)
            } else {
                assertJsonClose("$request", case.getValue("response"),
                                ServerJson.encodeToJsonElement(ProgressionResponse.serializer(), call()))
            }
        }
    }

    @Test
    fun singleChordNamesEachClipAsTheServer() {
        for (clip in golden.objects("quick_chord")) {
            val (label, confidence) = singleChord(clip.getValue("chord").floatMatrix(), JVM_MODELS.vocabulary)
            val name = "${clip.str("file")} at ${clip.double("start")} s"
            assertEquals(name, clip.str("label"), label)
            assertEquals(name, clip.double("confidence"), confidence, 1e-5)
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.analysis.SuggestGoldenTest'`
Expected: compilation fails with `Unresolved reference 'nextChords'` and `Unresolved reference 'singleChord'`.

- [ ] **Step 3: Write them**

Create `.../engine/analysis/Suggest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.lm.predictNext
import com.arbaba.chordifymobile.engine.model.ProgressionKey
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.engine.theory.estimateKeyFromChords
import com.arbaba.chordifymobile.engine.theory.parseKey
import com.arbaba.chordifymobile.engine.util.argmax
import com.arbaba.chordifymobile.engine.util.pyRound
import com.arbaba.chordifymobile.engine.vocab.NO_CHORD
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.parseDisplay

/** POST /api/v2/progressions/next (chordify_backend/app/api/progressions.py): next-chord
 *  suggestions for typed chord names, in [key] or the key the chords suggest. Throws
 *  IllegalArgumentException with the server's message where the server answers INVALID_REQUEST. */
fun nextChords(models: Models, chords: List<String>, key: String? = null, k: Int = 3,
               durationsBeats: List<Double>? = null): ProgressionResponse {
    require(chords.size in 1..512) { "Between 1 and 512 chords are needed." }
    require(k in 1..10) { "k must be between 1 and 10." }
    val labels = chords.map { parseDisplay(it) }
    require(durationsBeats == null || durationsBeats.size == labels.size) { "durations_beats must have one entry per chord." }
    val beats = durationsBeats ?: List(labels.size) { 4.0 }
    val (useKey, confidence, estimated) = if (!key.isNullOrEmpty()) {
        Triple(parseKey(key), 1.0, false)
    } else {
        estimateKeyFromChords(labels.zip(beats)).let { (estimate, score) -> Triple(estimate, score, true) }
    }
    val real = labels.zip(beats).filter { parse(it.first).isChord }
    require(real.isNotEmpty()) { "At least one chord (not N.C.) is needed." }
    val (predictions, prior) = predictNext(models.lm, real.map { it.first }, useKey, k = k, historyBeats = real.map { it.second })
    return ProgressionResponse(ProgressionKey(useKey.tonicName, useKey.mode, pyRound(confidence, 4), estimated),
                               predictions, prior, models.lmId)
}

/** single_chord (chordify_backend/app/pipeline/analysis.py): one label for a short clip, from the
 *  chord model's per-frame probabilities. Frames count with weight 1 - P(N); the best chord
 *  class wins, with its share of the chord mass as the confidence. The server computes this in
 *  float32 and the engine in float64, so confidences agree to about 1e-6. */
fun singleChord(chord: Array<FloatArray>, vocabulary: Vocabulary): Pair<String, Double> {
    val n = vocabulary.noChord
    val weights = DoubleArray(chord.size) { 1.0 - chord[it][n] }
    val total = weights.sum()
    if (total < 1e-3) return NO_CHORD to chord.sumOf { it[n].toDouble() } / chord.size
    val mass = DoubleArray(vocabulary.size) { c ->
        var sum = 0.0
        for (t in chord.indices) sum += chord[t][c] * weights[t]
        sum / total
    }
    mass[n] = 0.0
    val best = argmax(mass)
    return vocabulary.decode(best) to mass[best] / mass.sum()
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `SuggestGoldenTest` 2 passed, 73 in all.

- [ ] **Step 5: Commit**

```bash
git add Mobile/engine
git commit -m "Suggest next chords and name a single chord in the engine" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The engine's public API

**Files:**
- Modify: `.../engine/audio/AudioDecoder.kt`, `.../engine/audio/AudioStream.kt`, `.../engine/analysis/FrontEnd.kt`
- Create: `.../engine/ChordifyEngine.kt`
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/analysis/ChordifyEngineDeviceTest.kt`

**Interfaces:**
- Consumes: `runFrontEnd`, `ChordModel`, `NnlsChroma`, `nnlsToCOrder`, `AudioSource`, `AudioLimits` (M2, M3a); `analyseMeasured`, `targetVocabulary`, `Measurements`, `SourceInfo`, `Stage`, `AnalysisOptions`, `Models` (Task 3); `nextChords`, `singleChord` (Task 4); `NgramProgressionModel.fromJson` (M1); `assertJsonClose`, `measurements`, `sourceInfo` (Tasks 2–3).
- Produces:
  - `streamAudio(..., onProgress: (Double) -> Unit = {})` and `runFrontEnd(..., onProgress: (Double) -> Unit = {})`: the share of the file read so far, in steps of about 1%;
  - `class ChordifyEngine(chordModel: ChordModel, models: Models) : AutoCloseable` (package `...engine`), with:
    - `analyse(source: AudioSource, info: SourceInfo, options: AnalysisOptions = AnalysisOptions(), onProgress: (Stage, Double) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }, limits: AudioLimits = AudioLimits()): AnalysisResult`;
    - `nextChords(chords, key = null, k = 3, durationsBeats = null): ProgressionResponse`;
    - `quickChord(pcm: FloatArray): Pair<String, Double>`;
    - `ChordifyEngine.fromAssets(context)` and `ChordifyEngine.DECODE_SHARE`.

  M4's background job calls `analyse`, and M6's Quick chord and Songwriter screens call the other two.

- [ ] **Step 1: Write the failing device test**

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/analysis/ChordifyEngineDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.sourceInfo
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordifyEngineDeviceTest {
    private val golden = Goldens.loadGz("analysis")
    private val analysisId = golden.str("analysis_id")!!

    // M3's "done when" (spec §9): the whole AnalysisResult, from the audio file, matches Python's.
    // Labels, keys and structure are identical; numbers agree to DEVICE_TOLERANCE, the room the
    // model's logits have (1e-3) once they are probabilities rounded to 4 places.
    @Test
    fun analysesMatchTheServer() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val source = AudioSource.FromFile(TestAudio.golden(name))
            if (input.has("error")) {
                val error = assertThrows(EngineException::class.java) { engine.analyse(source, input.sourceInfo(analysisId)) }
                assertEquals(name, ErrorCode.NO_MUSIC_DETECTED, error.code)
                continue
            }
            val result = engine.analyse(source, input.sourceInfo(analysisId))
            assertJsonClose(name, input.getValue("result"), Json.parseToJsonElement(result.toJson()), DEVICE_TOLERANCE)
        }
    }

    @Test
    fun progressGoesThroughTheStagesInOrder() {
        val seen = ArrayList<Pair<Stage, Double>>()
        engine.analyse(AudioSource.FromFile(TestAudio.golden("pulse.wav")), SourceInfo("an_progress"),
                       onProgress = { stage, fraction -> seen += stage to fraction })
        assertEquals(Stage.entries.toList(), seen.map { it.first }.distinct())
        assertTrue("fractions only grow: $seen", seen.zipWithNext().all { (a, b) -> b.second >= a.second })
        assertTrue("decoding reports its progress: $seen", seen.count { it.first == Stage.DECODE } > 10)
        assertEquals(Stage.DONE to 1.0, seen.last())
    }

    @Test
    fun cancellingStopsTheDecoding() {
        var calls = 0
        assertThrows(CancelledException::class.java) {
            engine.analyse(AudioSource.FromFile(TestAudio.golden("pulse.wav")), SourceInfo("an_cancel"),
                           isCancelled = { ++calls > 20 })
        }
    }

    @Test
    fun quickChordNamesEachClipAsTheServer() {
        for (clip in golden.objects("quick_chord")) {
            val wav = TestAudio.readWav(TestAudio.golden(clip.str("file")!!))
            assertEquals(44100, wav.sampleRate)
            val pcm = wav.mono.copyOfRange((clip.double("start") * 44100).toInt(), (clip.double("end") * 44100).toInt())
            val (label, confidence) = engine.quickChord(pcm)
            val name = "${clip.str("file")} at ${clip.double("start")} s"
            assertEquals(name, clip.str("label"), label)
            assertEquals(name, clip.double("confidence"), confidence, DEVICE_TOLERANCE)
        }
    }

    @Test
    fun nextChordsUsesTheShippedProgressionModel() {
        val response = engine.nextChords(listOf("C", "G", "Am", "F"))
        assertEquals("progression-ngram@1.0.0", response.model)
        assertEquals(listOf("G", "C", "Dm"), response.predictions.map { it.display })
    }

    companion object {
        const val DEVICE_TOLERANCE = 2e-3

        private lateinit var engine: ChordifyEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = ChordifyEngine.fromAssets(InstrumentationRegistry.getInstrumentation().targetContext)
        }

        @AfterClass
        @JvmStatic
        fun close() = engine.close()
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:compileDebugAndroidTestKotlin`
Expected: compilation fails with `Unresolved reference 'ChordifyEngine'`.

- [ ] **Step 3: Report decoding progress**

In `.../engine/audio/AudioDecoder.kt`:
- replace
```kotlin
    /** Calls [onStart] once with the sample rate, then [onSamples] with every chunk. */
    fun decode(isCancelled: () -> Boolean, onStart: (sampleRate: Int) -> Unit, onSamples: (FloatArray) -> Unit): DecodedTrack {
```
with
```kotlin
    /** Calls [onStart] once with the sample rate, then [onSamples] with every chunk, and [onProgress]
     *  with the share of the track read so far, when the container gives its duration. */
    fun decode(
        isCancelled: () -> Boolean,
        onStart: (sampleRate: Int) -> Unit,
        onSamples: (FloatArray) -> Unit,
        onProgress: (Double) -> Unit = {},
    ): DecodedTrack {
```
- replace `                return pump(extractor, codec, format, isCancelled, onStart, onSamples)` with `                return pump(extractor, codec, format, isCancelled, onStart, onSamples, onProgress)`;
- in `pump`, replace
```kotlin
        onSamples: (FloatArray) -> Unit,
    ): DecodedTrack {
        val trimmer
```
with
```kotlin
        onSamples: (FloatArray) -> Unit,
        onProgress: (Double) -> Unit,
    ): DecodedTrack {
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        var reported = 0.0
        val trimmer
```
- replace
```kotlin
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
```
with
```kotlin
                        val time = extractor.sampleTime
                        codec.queueInputBuffer(index, 0, size, time, 0)
                        extractor.advance()
                        if (durationUs > 0) {
                            val fraction = (time.toDouble() / durationUs).coerceIn(0.0, 1.0)
                            if (fraction >= reported + PROGRESS_STEP) {
                                reported = fraction
                                onProgress(fraction)
                            }
                        }
```
- in the `companion object`, after `        const val MAX_IDLE = 500 // 5 s without output after the end of the input`, add `        const val PROGRESS_STEP = 0.01 // report progress in steps of 1%`.

In `.../engine/audio/AudioStream.kt`:
- at the end of `streamAudio`'s comment, replace ` *  audio_io.py), which hold the whole song in memory. Errors carry the server's codes. */` with
```kotlin
 *  audio_io.py), which hold the whole song in memory. Errors carry the server's codes.
 *  [onProgress] gets the share of the file read so far (0..1), in steps of about 1%, when the
 *  container states its duration. */
```
- after the parameter `    isCancelled: () -> Boolean = { false },` add `    onProgress: (Double) -> Unit = {},`;
- after `            onSamples = { chunk -> emit(resampler!!.process(chunk)) },` add `            onProgress = onProgress,`.

In `.../engine/analysis/FrontEnd.kt`, replace
```kotlin
/** Decodes [source] once and feeds NNLS Chroma and the beat tracker together, as the server's
 *  analyse() computes features.extract and track_beats from the same samples. */
fun runFrontEnd(source: AudioSource, limits: AudioLimits = AudioLimits(), isCancelled: () -> Boolean = { false }): FrontEnd =
```
with
```kotlin
/** Decodes [source] once and feeds NNLS Chroma and the beat tracker together, as the server's
 *  analyse() computes features.extract and track_beats from the same samples. [onProgress] gets
 *  the share of the file read so far. */
fun runFrontEnd(
    source: AudioSource,
    limits: AudioLimits = AudioLimits(),
    isCancelled: () -> Boolean = { false },
    onProgress: (Double) -> Unit = {},
): FrontEnd =
```
and `            val info = streamAudio(source, listOf(nnls, tracker), limits, isCancelled)` with `            val info = streamAudio(source, listOf(nnls, tracker), limits, isCancelled, onProgress)`.

- [ ] **Step 4: Write the engine**

Create `.../engine/ChordifyEngine.kt`:
```kotlin
package com.arbaba.chordifymobile.engine

import android.content.Context
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.analysis.Measurements
import com.arbaba.chordifymobile.engine.analysis.Models
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.analysis.Stage
import com.arbaba.chordifymobile.engine.analysis.analyseMeasured
import com.arbaba.chordifymobile.engine.analysis.nextChords
import com.arbaba.chordifymobile.engine.analysis.runFrontEnd
import com.arbaba.chordifymobile.engine.analysis.singleChord
import com.arbaba.chordifymobile.engine.analysis.targetVocabulary
import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.features.NnlsChroma
import com.arbaba.chordifymobile.engine.features.nnlsToCOrder
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The engine's public API (spec §4.1), with the chord and progression models loaded once.
 *  Not thread-safe: run one call at a time, off the main thread. */
class ChordifyEngine(private val chordModel: ChordModel, val models: Models) : AutoCloseable {

    /** The whole analysis of an audio file, as the server's analysis job produces it. [onProgress]
     *  gets each stage as it starts, with the share of the work done (0..1); [isCancelled] is
     *  checked while decoding and between stages, and throws CancelledException. */
    fun analyse(
        source: AudioSource,
        info: SourceInfo,
        options: AnalysisOptions = AnalysisOptions(),
        onProgress: (Stage, Double) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        limits: AudioLimits = AudioLimits(),
    ): AnalysisResult {
        targetVocabulary(models, options) // a finer vocabulary is refused before any decoding, as on the server
        onProgress(Stage.DECODE, 0.0)
        val front = runFrontEnd(source, limits, isCancelled) { onProgress(Stage.DECODE, DECODE_SHARE * it) }
        if (isCancelled()) throw CancelledException()
        onProgress(Stage.ACOUSTIC, DECODE_SHARE)
        val output = chordModel.predict(front.features)
        if (isCancelled()) throw CancelledException()
        val result = analyseMeasured(models, Measurements(front.info.samples, front.beats, output), options, info,
                                     onProgress, isCancelled)
        onProgress(Stage.DONE, 1.0)
        return result
    }

    /** Next-chord suggestions for the Songwriter (POST /api/v2/progressions/next). */
    fun nextChords(chords: List<String>, key: String? = null, k: Int = 3, durationsBeats: List<Double>? = null): ProgressionResponse =
        nextChords(models, chords, key, k, durationsBeats)

    /** Quick chord: the one chord in a short mono 44.1 kHz clip, with its confidence (the server's single_chord). */
    fun quickChord(pcm: FloatArray): Pair<String, Double> {
        require(pcm.isNotEmpty()) { "The clip has no samples." }
        val raw = NnlsChroma().use { nnls ->
            nnls.write(pcm, pcm.size)
            nnlsToCOrder(nnls.finish())
        }
        return singleChord(chordModel.predict(raw).chord, models.vocabulary)
    }

    override fun close() = chordModel.close()

    companion object {
        /** The share of [analyse]'s progress taken by the decoding pass (decode, NNLS and beats). */
        const val DECODE_SHARE = 0.6

        /** The engine with the models the app ships (the engine's assets). */
        fun fromAssets(context: Context): ChordifyEngine {
            val chordModel = ChordModel.fromAssets(context)
            try {
                val (lmId, lmFile) = Models.readLmBundle(
                    context.assets.open("${Models.LM_BUNDLE}/bundle.json").use { it.readBytes().decodeToString() })
                // The Android build un-gzips *.gz assets and drops the suffix (the APK compresses them
                // anyway), so the APK holds model.json where bundle.json names model.json.gz.
                val lm = context.assets.open("${Models.LM_BUNDLE}/${lmFile.removeSuffix(".gz")}").use { stream ->
                    NgramProgressionModel.fromJson(Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject)
                }
                return ChordifyEngine(chordModel, Models(chordModel.bundle, lm, lmId))
            } catch (error: Exception) {
                chordModel.close()
                throw error
            }
        }
    }
}
```

- [ ] **Step 5: Run the device tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 28 tests passed (M3a's 23, `ChordifyEngineDeviceTest` 5).

If `analysesMatchTheServer` fails on a number just over 2e-3, look at where it is. Chord confidences come from the model's probabilities, whose logits are allowed 1e-3. A time that is off means the beats moved, and M3a's `BeatTrackerDeviceTest` should fail too. Do not widen the tolerance before finding out which.

- [ ] **Step 6: Commit**

```bash
git add Mobile/engine
git commit -m "Analyse a file end to end with the engine's public API" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Benchmark the whole analysis, and document it

**Files:**
- Modify: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeatureBenchmarkDeviceTest.kt` (replace)
- Modify: `Mobile/engine/README.md`, `docs/chord-progression-plan/08-implementation-status.md`

**Interfaces:**
- Consumes: `ChordifyEngine`, `Stage`, `SourceInfo` (Tasks 3, 5), `TestAudio` (M2).
- Produces: a `ChordifyBenchmark` log line with each stage's time for a 240 s song.

- [ ] **Step 1: Time the whole analysis**

Replace the whole of `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeatureBenchmarkDeviceTest.kt` with:
```kotlin
package com.arbaba.chordifymobile.engine.features

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.analysis.Stage
import com.arbaba.chordifymobile.engine.audio.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Times a whole analysis of a 4-minute song (pulse.wav 15 times over), stage by stage: one
 *  decoding pass into NNLS Chroma and the beat tracker, the chord model, then the rest (two
 *  decodings, key, theory and predictions). The spec's budget is 20 s on a mid-range phone. */
@RunWith(AndroidJUnit4::class)
class FeatureBenchmarkDeviceTest {
    @Test
    fun aFourMinuteSong() {
        val pulse = TestAudio.readWav(TestAudio.golden("pulse.wav")).mono
        val song = FloatArray(pulse.size * 15) { pulse[it % pulse.size] }
        val file = TestAudio.tempFile("benchmark.wav").also { TestAudio.writeWav(it, song, 44100) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        ChordifyEngine.fromAssets(context).use { engine ->
            val started = HashMap<Stage, Long>()
            val result = engine.analyse(AudioSource.FromFile(file), SourceInfo("an_benchmark"),
                                        onProgress = { stage, _ -> started.putIfAbsent(stage, SystemClock.elapsedRealtime()) })
            fun seconds(from: Stage, to: Stage) = (started.getValue(to) - started.getValue(from)) / 1000.0
            Log.i("ChordifyBenchmark", String.format(Locale.ROOT,
                "%.0f s song: decode + NNLS + beats %.1f s, chord model %.1f s, the rest %.1f s, in all %.1f s, on %s (%s)",
                song.size / 44100.0, seconds(Stage.DECODE, Stage.ACOUSTIC), seconds(Stage.ACOUSTIC, Stage.KEY),
                seconds(Stage.KEY, Stage.DONE), seconds(Stage.DECODE, Stage.DONE), Build.MODEL, Build.SUPPORTED_ABIS[0]))
            assertEquals(240.0, result.source.duration, 0.01)
            assertEquals(listOf("C", "G", "Am", "F"), result.chords.take(4).map { it.display })
            assertTrue(result.tempo.confidence >= 0.5)
        }
    }
}
```

- [ ] **Step 2: Run it**

Run:
```bash
cd Mobile
./gradlew :engine:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.engine.features.FeatureBenchmarkDeviceTest
grep -rh "ChordifyBenchmark" engine/build/outputs/androidTest-results/managedDevice
```
Expected: `BUILD SUCCESSFUL`, and a line like `240 s song: decode + NNLS + beats … s, chord model … s, the rest … s, in all … s, on sdk_gphone64_x86_64 (x86_64)`. While this plan was written, the emulator gave 14.9 s, 4.0 s, 1.2 s and 20.1 s. If a phone is connected, also run `./gradlew :engine:connectedDebugAndroidTest` and `adb logcat -d -s ChordifyBenchmark` for its line.

- [ ] **Step 3: Record the numbers**

In `docs/chord-progression-plan/08-implementation-status.md`, §3.6, directly below the line `The emulator's timings vary a lot between runs, and it runs NNLS about ten times slower than the server, so these numbers say little about a phone.`, add:
```markdown

The whole analysis (milestone M3b), for a 240 s song: `ChordifyEngine.analyse`, timed stage by stage by the same test:

| Device | ABI | Decode + NNLS + beats | Chord model | The rest | In all |
|---|---|---|---|---|---|
| Emulator (API 34, Google APIs, 2 cores) on an Intel Core i7-9750H laptop | x86_64 | <Step 2's first number> s | <second> s | <third> s | <fourth> s |
| <phone model, or "No phone measured yet"> | arm64-v8a | | | | |

"The rest" covers both decodings, the key, theory and predictions: everything the server's `analyse()` does after the chord model.
```
Fill the angle-bracketed cells from Step 2's output. If there were several runs, give the range and the number of runs, as the tables above do.

- [ ] **Step 4: Document the API**

In `Mobile/engine/README.md`, at the end of the first section (after the paragraph that ends `assets at build time, so git holds one copy of each.`), add:
```markdown

`ChordifyEngine` is the API the app calls: `analyse` returns the whole analysis document
(`model.AnalysisResult`, the JSON the server's analysis job writes), `nextChords` the
Songwriter's suggestions, and `quickChord` the one chord in a short clip. `analysis/` ports the
server's pipeline after the audio stages. `analysis.analyseMeasured` runs it from recorded beats
and model output, which is how the JVM tests check it against the server's own numbers.
```
In its `## Tests` section, replace
```
tools/mobile_goldens.sh                        # features, beats and acoustic, in Docker with the pinned reference
```
with
```
tools/mobile_goldens.sh                        # features, beats, acoustic and analysis, in Docker with the pinned reference
```

- [ ] **Step 5: Run everything once more**

Run:
```bash
python -m pytest tests/ai/test_mobile_goldens.py -q -p no:cacheprovider
cd Mobile
./gradlew :engine:testDebugUnitTest
./gradlew :engine:api34DebugAndroidTest
./gradlew :app:assembleDebug
```
Expected: `5 passed, 3 skipped`; 73 JVM tests; 28 device tests; the old app still builds.

- [ ] **Step 6: Commit**

```bash
git add Mobile/engine docs/chord-progression-plan/08-implementation-status.md
git commit -m "Benchmark the whole analysis on the device" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Done when

- `./gradlew :engine:testDebugUnitTest` (73 tests) and `./gradlew :engine:api34DebugAndroidTest` (28 tests) pass locally, and the CI jobs `python`, `android` and `android-device` pass.
- Replayed from the server's beats and model output, the analysis of every input and option set matches the server's result to 1e-9, and silence fails with the server's `NO_MUSIC_DETECTED` message.
- From the audio file on the emulator, `ChordifyEngine.analyse` gives the server's `AnalysisResult` within 2e-3, with identical labels, key, numerals and predictions.
- `nextChords` and `quickChord` answer as the server's progression endpoint and `single_chord` do.
- The benchmark numbers are in `docs/chord-progression-plan/08-implementation-status.md`.

That completes M3 (spec §9). Next: the M4 plan, the app shell. It covers Compose, the Room library, import, recording and WorkManager jobs that call `ChordifyEngine.analyse`, and it deletes the old Java app.
