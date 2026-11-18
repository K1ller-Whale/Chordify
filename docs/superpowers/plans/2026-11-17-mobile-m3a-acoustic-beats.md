# Mobile M3a: the chord model and beat tracking on the phone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The engine runs the shipped ChordNet model with ONNX Runtime and tracks beats as librosa does, and from one decoding pass it produces the features, beats and model outputs the server's `analyse()` starts from, each checked against Python.

**Architecture:** Beat tracking is a Kotlin port of librosa 1.0.1's `onset_strength` and `beat_track`. It is a second sink of M2's `streamAudio`, so the audio is still decoded once. The model runs through ONNX Runtime's Android library, with the server's windowing, from model files that Gradle copies out of the repository's `models/`. Python writes two new golden files (`beats.json.gz`, `acoustic.json.gz`), in the same Docker container as M2's features.

**Tech Stack:** Kotlin, ONNX Runtime Android 1.30.0 (MIT), librosa 1.0.1 and onnxruntime 1.30.0 as the Python reference, Docker; everything from M2.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md), milestone M3 (§9), pipeline steps 4–5 (§5), tests (§7.1).

**M3 is split in two.** This plan, M3a, builds the two remaining inputs of the analysis (model outputs and beats) and checks each against Python. M3b, planned when this lands, builds the two-pass `analysis` itself and the `AnalysisResult` document on top of `runFrontEnd` and `ChordModel.predict`. The split keeps each plan small enough to check, and M3b's golden file (a whole `AnalysisResult`) needs both halves working first.

## Global Constraints

- Everything from the M1 and M2 plans still holds: module `:engine`, namespace `com.arbaba.chordifymobile.engine`, `minSdk 33`, JVM 17, `export JAVA_HOME="/c/Program Files/Java/jdk-21"`, golden files generated and never edited, plain imperative commit messages with the `Co-Authored-By` trailer.
- On this machine run every Gradle command with `export ANDROID_USER_HOME="D:/android-user"` (the managed emulator lives there; C: is nearly full). Stop Docker Desktop after Task 1 (`docker desktop stop`): its disk image on C: grows while it runs.
- Reference versions are pinned in `tools/mobile_goldens.Dockerfile`: `librosa==1.0.1` and `onnxruntime==1.30.0` (plus M2's `soxr==1.1.0`, `vamp==1.1.0`). CI's `python` job pins `onnxruntime==1.30.0` only: librosa 1.0.1 needs Python 3.12 and that job runs 3.11, so it skips `beats` as it skips `features`. The engine depends on `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`, the same ONNX Runtime release.
- Beat tracking follows `chordify_backend.app.pipeline.beats.track_beats`: librosa resampling of the 44.1 kHz audio to 22.05 kHz, `onset_strength(hop_length=512)`, `beat_track(units="time", trim=False)`, then the server's tempo and regularity rounding.
- Tolerances (spec §7.1): model logits within 1e-3; beats within one onset frame (512 / 22050 s, 23 ms) and the same count; beat frames identical when computed from the same onset envelope.
- Models live once in git, in the repository's `models/`. The APK gets `chordnet-chroma/3.0.0` and `progression-ngram/1.0.0` (the latter for M3b), copied by Gradle at build time. A model that fails its SHA-256 check, or needs features other than `nnls_bothchroma` revision 1, fails with `MODEL_UNAVAILABLE`.

## What was checked while writing this plan

Every Kotlin file and test in this plan ran in a scratch copy of the repository, against golden files that the plan's own generator wrote with librosa 1.0.0 and onnxruntime 1.30.0:
- 60 JVM tests passed. The beat frames came out identical to librosa's for all six inputs, and the tempo bins exact, on the first run.
- 23 device tests passed on the managed emulator, including the model's logits (within 1e-3, for one window and across several) and the beats.

librosa 1.0.1, which this plan pins, differs from 1.0.0 only in rounding inside `fft_frequencies` and `power_to_db`, both of which the port follows as 1.0.1 writes them. All beat-tracking code is identical between the two releases.

For a 240 s song, four emulator runs of the benchmark gave 39.5–55.7 s for decoding, NNLS and beats, and 9.6–25.6 s for the model: the emulator's timings vary a lot from run to run. The emulator runs NNLS about ten times slower than the server, so whether this fits the 20 s budget waits for a phone (Task 6).

## File structure

| Path | Responsibility |
|---|---|
| `tools/mobile_goldens.Dockerfile`, `tools/mobile_goldens.sh`, `.github/workflows/ci.yml` | Pin librosa and onnxruntime; the container also writes `beats.json.gz` and `acoustic.json.gz` |
| `chordify_ai/export/mobile_goldens.py` | New audio inputs (a 4/4 and a 3/4 pulse); `beats` and `acoustic` golden files; `missing(name)` says why a file cannot be written here |
| `tests/ai/test_mobile_goldens.py` | Skips what this machine cannot reproduce |
| `.../engine/beats/Fft.kt` | Radix-2 FFT in double precision |
| `.../engine/beats/Onset.kt` | Hann window, Slaney mel filters, streaming `OnsetStrength` |
| `.../engine/beats/BeatTrack.kt` | Tempogram, tempo estimate, the dynamic-programming beat tracker |
| `.../engine/beats/BeatInfo.kt` | `BeatInfo` and `trackBeats`, the server's post-processing |
| `.../engine/beats/BeatTracker.kt` | The beat tracker as a sink of the 44.1 kHz stream |
| `.../engine/analysis/FrontEnd.kt` | One decoding pass into NNLS and the beat tracker |
| `.../engine/acoustic/Bundle.kt` | `bundle.json`, SHA-256 |
| `.../engine/acoustic/Windowed.kt` | The server's windowing, softmax, sigmoid |
| `.../engine/acoustic/ChordModel.kt` | ONNX Runtime session, `logits`, `predict` |
| `Mobile/engine/build.gradle`, `Mobile/gradle/libs.versions.toml` | ONNX Runtime; model files copied into the assets |
| `.../engine/Errors.kt` | Adds `MODEL_UNAVAILABLE` |
| tests under `src/test` and `src/androidTest` | One per unit, named below |
| `Mobile/engine/src/test/resources/goldens/audio/pulse.wav`, `waltz.wav`, `goldens/beats.json.gz`, `goldens/acoustic.json.gz` | Generated |

`.../engine` stands for `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine`. Shell commands run from the repository root in Git Bash unless a step says otherwise.

---

### Task 1: Golden files for beats and the chord model

**Files:**
- Modify: `tools/mobile_goldens.Dockerfile`, `tools/mobile_goldens.sh`, `.github/workflows/ci.yml`
- Modify: `chordify_ai/export/mobile_goldens.py`
- Modify: `tests/ai/test_mobile_goldens.py`
- Create (generated): `Mobile/engine/src/test/resources/goldens/audio/pulse.wav`, `waltz.wav`, `goldens/beats.json.gz`, `goldens/acoustic.json.gz`

**Interfaces:**
- Consumes: `chordify_backend.app.pipeline.beats.track_beats`; `chordify_core.acoustic.OnnxChordModel`, `windowed`, `prepare`, `softmax`, `sigmoid`; `features.json.gz` from M2 (the model is run on its raw `loop.wav` features, so `acoustic.json.gz` needs no NNLS Chroma).
- Produces (Python): `FILES` gains `beats` and `acoustic`; `PINNED`, `missing(name) -> str | None`, `_version(package)`; `render_audio()` now writes only missing inputs. `NEEDS_NNLS` is gone.
- Produces (`beats.json.gz`): `mel` (`shape`, `rows`, `cols`, `values`: the non-zero filter weights); `fft` (`frame`, `re`, `im`); `onset_case` (`y`: 2 s at 22.05 kHz, `onset`); `inputs[]` with `file`, `samples_22k`, `onset`, `tempogram` (344 doubles), `tempo`, `beat_frames`, `beats`, `bpm`, `confidence`, `usable`, for the four M2 WAVs plus `pulse.wav` and `waltz.wav`.
- Produces (`acoustic.json.gz`): `cases[]` (`case`, `tiles`, `frames`, `logits`: every head, `boundary` as a flat list), for `loop.wav`'s features once and four times over; `windowed[]` (`frames`, `out`); `softmax` (`logits`, `p`); `sigmoid` (`logits`, `p`).

- [ ] **Step 1: Pin librosa and ONNX Runtime**

In `tools/mobile_goldens.Dockerfile`, replace
```dockerfile
    && pip install -q scipy librosa soundfile soxr==1.1.0 pytest \
```
with
```dockerfile
    && pip install -q scipy librosa==1.0.1 soundfile soxr==1.1.0 onnxruntime==1.30.0 pytest \
```
In `.github/workflows/ci.yml`, job `python`, replace
```yaml
          pip install -r chordify_backend/requirements.txt pytest httpx mir_eval soxr==1.1.0
```
with
```yaml
          pip install -r chordify_backend/requirements.txt pytest httpx mir_eval soxr==1.1.0 onnxruntime==1.30.0
```
Do not pin librosa there: librosa 1.0.1 requires Python 3.12 and this job runs 3.11. The container (Ubuntu 24.04, Python 3.12) is where `beats.json.gz` is written and checked.
In `tools/mobile_goldens.sh`, replace `--only features $*` with `--only features --only beats --only acoustic $*`, and replace its first six lines with:
```bash
#!/usr/bin/env bash
# Regenerate the Android engine's features, beats and acoustic golden files in a Linux container
# with the pinned reference (tools/mobile_goldens.Dockerfile: NNLS Chroma, librosa, ONNX Runtime),
# then check every golden file. Needs Docker.
#
#   tools/mobile_goldens.sh                  # the three files, from the committed audio inputs
#   tools/mobile_goldens.sh --render-audio   # render any missing audio inputs first
```

- [ ] **Step 2: Add the pulsed inputs**

In `chordify_ai/export/mobile_goldens.py`, directly below the line `SEVENTHS = [("C:maj7", 2.0), ("A:min7", 2.0), ("D:min7", 2.0), ("G:7", 2.0)]`, add:
```python
# One chord attack per beat, so the beat tracker has a pulse to find: 4/4 at 120 bpm (16 s) and
# 3/4 at 150 bpm (9.6 s), with the chord changing on every bar.
PULSE = [(label, 0.5) for label in ("C:maj", "G:maj", "A:min", "F:maj") for _ in range(8)]
WALTZ = [(label, 0.4) for label in ("C:maj", "G:maj", "A:min", "F:maj") * 2 for _ in range(3)]
```
Replace the whole function `render_audio` (from `def render_audio(` down to the line before `def _f32(values)`) with:
```python
def render_audio(audio_dir: Path = AUDIO_DIR) -> None:
    """Write the audio inputs that are missing. They are committed and only rendered on purpose
    (--render-audio): the synthesiser's noise comes from numpy's random generator, so a render on
    another machine may differ, and committed inputs are never overwritten."""
    import subprocess

    import soundfile as sf

    from chordify_core import audio, synth

    audio_dir.mkdir(parents=True, exist_ok=True)

    def write(name: str, render) -> None:
        if not (audio_dir / name).exists():
            y, sr = render()
            sf.write(audio_dir / name, y, sr, subtype="PCM_16")

    def loop(sr: int) -> np.ndarray:
        return synth.render_progression(LOOP, sr=sr)[0]

    silence = np.zeros(int(1.5 * 44100), dtype=np.float32)
    write("loop.wav", lambda: (loop(44100), 44100))
    write("loop_padded.wav", lambda: (np.concatenate([silence, loop(44100), silence]), 44100))
    write("loop_48k.wav", lambda: (loop(48000), 48000))
    write("sevenths_stereo_22k.wav", lambda: (np.stack([synth.render_progression(SEVENTHS, sr=22050, seed=1)[0],
                                                        0.6 * synth.render_progression(SEVENTHS, sr=22050, seed=2)[0]],
                                                       axis=1), 22050))
    write("pulse.wav", lambda: (synth.render_progression(PULSE, sr=44100)[0], 44100))
    write("waltz.wav", lambda: (synth.render_progression(WALTZ, sr=44100)[0], 44100))
    for name, codec in ENCODED_INPUTS.items():
        if not (audio_dir / name).exists():
            subprocess.run([audio.ffmpeg_binary(), "-nostdin", "-loglevel", "error", "-y", "-i",
                            str(audio_dir / "loop.wav"), *codec, "-map_metadata", "-1", "-fflags", "+bitexact",
                            "-flags:a", "+bitexact", str(audio_dir / name)], check=True)
```
Rendering again never overwrites a committed input: the synthesiser's noise depends on numpy's random generator.

- [ ] **Step 3: Add the `beats` and `acoustic` sections**

Directly above the line `FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden,`, add:
```python
# ---------------------------------------------------------------------------
# beats: librosa's onset strength and dynamic-programming beat tracker
# ---------------------------------------------------------------------------

BEAT_INPUTS = WAV_INPUTS + ["pulse.wav", "waltz.wav"]
BEAT_SR = 22050  # chordify_backend.app.pipeline.beats.track_beats resamples to this, hop 512
BEAT_HOP = 512
ONSET_CASE_SECONDS = 2.0


def beats_golden() -> dict:
    import librosa

    from chordify_backend.app.pipeline.beats import track_beats
    from chordify_core import audio

    reason = missing("beats")
    if reason:
        raise SystemExit(f"beats.json.gz {reason}: run tools/mobile_goldens.sh")
    mel = librosa.filters.mel(sr=BEAT_SR, n_fft=2048, fmax=0.5 * BEAT_SR)  # onset_strength's filters
    rows, cols = np.nonzero(mel)
    rng = Lcg(5)
    frame = np.asarray([rng.random() * 2 - 1 for _ in range(2048)])
    spectrum = np.fft.rfft(frame)
    inputs = []
    for name in BEAT_INPUTS:
        y44 = audio.load_audio((AUDIO_DIR / name).read_bytes(), 44100, filename=name)
        y22 = librosa.resample(y44, orig_sr=44100, target_sr=BEAT_SR)
        onset = librosa.onset.onset_strength(y=y22, sr=BEAT_SR, hop_length=BEAT_HOP)
        tempo, frames = librosa.beat.beat_track(onset_envelope=onset, sr=BEAT_SR, hop_length=BEAT_HOP,
                                                units="frames", trim=False)
        tempogram = librosa.feature.tempogram(onset_envelope=onset, sr=BEAT_SR, hop_length=BEAT_HOP,
                                              win_length=344).mean(axis=1)
        info = track_beats(y44, 44100)
        inputs.append({"file": name, "samples_22k": len(y22), "onset": _f32(onset),
                       "tempogram": tempogram.tolist(), "tempo": float(np.atleast_1d(tempo)[0]),
                       "beat_frames": [int(f) for f in frames], "beats": info.beats.tolist(), "bpm": info.bpm,
                       "confidence": info.confidence, "usable": info.usable})
    pulse = audio.load_audio((AUDIO_DIR / "pulse.wav").read_bytes(), 44100, filename="pulse.wav")
    clip = librosa.resample(pulse, orig_sr=44100, target_sr=BEAT_SR)[:int(ONSET_CASE_SECONDS * BEAT_SR)]
    return {
        "environment": {name: _version(name) for name in ("numpy", "librosa", "soxr", "soundfile")},
        "mel": {"shape": list(mel.shape), "rows": rows.tolist(), "cols": cols.tolist(), "values": _f32(mel[rows, cols])},
        "fft": {"frame": frame.tolist(), "re": spectrum.real.tolist(), "im": spectrum.imag.tolist()},
        "onset_case": {"y": _f32(clip), "onset": _f32(librosa.onset.onset_strength(y=clip, sr=BEAT_SR, hop_length=BEAT_HOP))},
        "inputs": inputs,
    }


# ---------------------------------------------------------------------------
# acoustic: the shipped ChordNet with ONNX Runtime, on features.json.gz's raw features
# ---------------------------------------------------------------------------

ACOUSTIC_BUNDLE = REPO / "models" / "chordnet-chroma" / "3.0.0"
ACOUSTIC_TILES = 4  # loop.wav's 173 frames four times over: longer than one 512-frame window


def acoustic_golden() -> dict:
    from chordify_core import acoustic

    reason = missing("acoustic")
    if reason:
        raise SystemExit(f"acoustic.json.gz {reason}")
    model = acoustic.OnnxChordModel(ACOUSTIC_BUNDLE)
    loop = np.asarray(next(row["raw"] for row in read_golden(DEFAULT_OUT, "features")["inputs"]
                           if row["file"] == "loop.wav"), dtype=np.float32)
    cases = []
    for name, raw in (("loop.wav", loop), (f"loop.wav x{ACOUSTIC_TILES}", np.tile(loop, (ACOUSTIC_TILES, 1)))):
        logits = acoustic.windowed(model._run, acoustic.prepare(model.feature_spec, raw), model.window, model.margin)
        cases.append({"case": name, "tiles": 1 if name == "loop.wav" else ACOUSTIC_TILES, "frames": len(raw),
                      "logits": {head: _f32(v) if v.ndim == 1 else _f32_rows(v) for head, v in logits.items()}})
    first = cases[0]["logits"]
    softmax_in = np.asarray(first["chord"][:3], dtype=np.float32)
    sigmoid_in = np.asarray(first["boundary"][:3], dtype=np.float32)

    def fake(chunk: np.ndarray) -> dict:  # each window's output depends on where in the window a frame sits
        return {"a": chunk[:, :, :1] + np.arange(chunk.shape[1], dtype=np.float32)[None, :, None] / 1000}

    windows = [{"frames": n, "out": _f32(acoustic.windowed(fake, np.arange(n, dtype=np.float32)[:, None], 512, 64)["a"][:, 0])}
               for n in (300, 512, 1300)]
    return {"environment": {"numpy": _version("numpy"), "onnxruntime": _version("onnxruntime")},
            "bundle": model.id, "cases": cases, "windowed": windows,
            "softmax": {"logits": _f32_rows(softmax_in), "p": _f32_rows(acoustic.softmax(softmax_in))},
            "sigmoid": {"logits": _f32(sigmoid_in), "p": _f32(acoustic.sigmoid(sigmoid_in))}}
```

- [ ] **Step 4: Register them, and say why a file is skipped**

Replace everything from the line `FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden,` to the end of the file with:
```python
FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden,
         "features": features_golden, "beats": beats_golden, "acoustic": acoustic_golden}
# The reference versions of what shapes a file; elsewhere it is skipped by default (and by the test).
PINNED = {"beats": ("librosa", "1.0.1"), "acoustic": ("onnxruntime", "1.30.0")}
GZIPPED = {"features", "beats", "acoustic"}  # large, so stored compressed


def _version(package: str) -> str | None:
    from importlib import metadata

    try:
        return metadata.version(package)
    except metadata.PackageNotFoundError:
        return None


def missing(name: str) -> str | None:
    """Why this machine cannot write golden file ``name`` as the reference does, or None."""
    if name == "features":
        from chordify_core.features import nnls_available

        return None if nnls_available() else "needs NNLS Chroma"
    if name in PINNED:
        package, version = PINNED[name]
        return None if _version(package) == version else f"needs {package}=={version}"
    return None


def golden_path(out: Path, name: str) -> Path:
    return out / (f"{name}.json.gz" if name in GZIPPED else f"{name}.json")


def read_golden(out: Path, name: str) -> dict:
    data = golden_path(out, name).read_bytes()
    return json.loads(gzip.decompress(data) if name in GZIPPED else data)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--only", choices=sorted(FILES), action="append")
    parser.add_argument("--render-audio", action="store_true",
                        help=f"render the audio inputs missing from {AUDIO_DIR} first")
    args = parser.parse_args(argv)
    if args.render_audio:
        render_audio()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    names = args.only
    if names is None:
        names = [n for n in FILES if missing(n) is None]
        for skipped in FILES.keys() - set(names):
            print(f"{golden_path(out, skipped).name}: skipped, {missing(skipped)} (tools/mobile_goldens.sh)")
    for name in names:
        text = json.dumps(FILES[name](), ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n"
        path = golden_path(out, name)
        if name in GZIPPED:
            path.write_bytes(gzip.compress(text.encode("utf-8"), mtime=0))
        else:
            path.write_text(text, encoding="utf-8")
        print(f"{path.name}: {path.stat().st_size / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

Then bring the module docstring at the top of the file up to date. Replace
```
    python -m chordify_ai.export.mobile_goldens                 # every file
```
with
```
    python -m chordify_ai.export.mobile_goldens                 # every file this machine can write
```
and replace
```
    tools/mobile_goldens.sh                                     # features.json.gz (needs Docker)
```
with
```
    tools/mobile_goldens.sh                                     # features, beats, acoustic (needs Docker)
```
and replace
```
goldens/audio, is committed and only re-rendered on purpose (--render-audio).
```
with
```
goldens/audio, is committed: --render-audio writes the inputs that are missing, never the others.
```
and replace
```
features.json.gz needs the NNLS Chroma Vamp plugin, which tools/mobile_goldens.sh provides in
a Linux container. Where the plugin is not installed, that file is skipped.
```
with
```
features.json.gz needs the NNLS Chroma Vamp plugin, and beats.json.gz and acoustic.json.gz the
librosa and ONNX Runtime releases in PINNED. tools/mobile_goldens.sh provides all three in a Linux
container; elsewhere a file that cannot be reproduced is skipped, and missing(name) says why.
```

- [ ] **Step 5: Let the test skip what this machine cannot reproduce**

In `tests/ai/test_mobile_goldens.py`, replace the docstring line
```
    tools/mobile_goldens.sh        # features.json.gz, which needs the NNLS Chroma plugin
```
with
```
    tools/mobile_goldens.sh        # features, beats and acoustic, with NNLS Chroma and the pinned reference
```
delete the line `from chordify_core import features`, replace the `TOLERANCE` line with
```python
TOLERANCE = {"features": (1e-4, 1e-5), "beats": (1e-4, 1e-5), "acoustic": (1e-4, 1e-5)}
```
and replace
```python
    if name in mobile_goldens.NEEDS_NNLS and not features.nnls_available():
        pytest.skip("needs the NNLS Chroma Vamp plugin (tools/install_nnls_chroma.sh)")
```
with
```python
    reason = mobile_goldens.missing(name)
    if reason:
        pytest.skip(f"{name} {reason}, as tools/mobile_goldens.Dockerfile provides")
```

- [ ] **Step 6: Write the files in the container**

Check `df -h /c` shows at least 5 GB free (the image gains librosa's new pin and ONNX Runtime), and that Docker is running (start Docker Desktop if `docker info` fails). Then run `bash tools/mobile_goldens.sh --render-audio`.
Expected: the image rebuilds its Python layer. Then `features.json.gz`, `beats.json.gz` (about 320 KB) and `acoustic.json.gz` (about 1 MB) are written, and pytest says `7 passed`. Then run `docker desktop stop`.

- [ ] **Step 7: Check what was written, and the test here**

Run:
```bash
python - <<'EOF'
from chordify_ai.export import mobile_goldens as g
b = g.read_golden(g.DEFAULT_OUT, "beats")
for row in b["inputs"]:
    print(row["file"], len(row["onset"]), round(row["tempo"], 2), len(row["beat_frames"]), row["bpm"], row["confidence"], row["usable"])
print(b["environment"], g.read_golden(g.DEFAULT_OUT, "acoustic")["environment"])
EOF
python -m pytest tests/ai/test_mobile_goldens.py -q -p no:cacheprovider
```
Expected: librosa `1.0.1` and onnxruntime `1.30.0` in the environments, and six rows close to these (what librosa 1.0.0 gave while this plan was written):
```
loop.wav 345 117.45 17 117.5 0.978 True
loop_padded.wav 474 117.45 17 117.5 0.977 True
loop_48k.wav 345 136.0 19 136.0 0.937 True
sevenths_stereo_22k.wav 345 123.05 17 123.0 0.974 True
pulse.wav 690 117.45 33 117.5 0.972 True
waltz.wav 414 152.0 25 152.0 0.963 True
```
pytest here: `5 passed, 2 skipped`, the skips reading `beats needs librosa==1.0.1` and `features needs NNLS Chroma`. `acoustic` is checked here because this machine has onnxruntime 1.30.0 (`pip install onnxruntime==1.30.0` if it does not).

- [ ] **Step 8: Commit**

```bash
git add tools/mobile_goldens.Dockerfile tools/mobile_goldens.sh .github/workflows/ci.yml chordify_ai/export/mobile_goldens.py \
  tests/ai/test_mobile_goldens.py Mobile/engine/src/test/resources/goldens
git commit -m "Write golden files for beat tracking and the chord model" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Onset strength, as librosa computes it

**Files:**
- Create: `.../engine/beats/Fft.kt`, `.../engine/beats/Onset.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/beats/OnsetGoldenTest.kt`

**Interfaces:**
- Consumes: `SampleSink` (M2); `beats.json.gz` `mel`, `fft`, `onset_case` (Task 1).
- Produces (package `com.arbaba.chordifymobile.engine.beats`): `class Fft(size)` with `transform(re, im, inverse = false)` and `rfft(signal): Pair<DoubleArray, DoubleArray>`; `const val ONSET_SAMPLE_RATE = 22050`, `ONSET_HOP = 512`, `ONSET_N_FFT = 2048`, `ONSET_N_MELS = 128`; `fun hann(n): DoubleArray`; `fun hzToMel(hz)`, `fun melToHz(mel)`; `fun melFilters(sampleRate, nFft, nMels = 128): Array<FloatArray>`; `class OnsetStrength : SampleSink` with `finish(): FloatArray`; `fun onsetStrength(y: FloatArray): FloatArray`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/beats/OnsetGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.obj
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OnsetGoldenTest {
    private val golden = Goldens.loadGz("beats")

    @Test
    fun melFiltersMatchLibrosa() {
        val mel = golden.obj("mel")
        val filters = melFilters(ONSET_SAMPLE_RATE, ONSET_N_FFT)
        assertEquals(mel.getValue("shape").ints(), listOf(filters.size, filters[0].size))
        val rows = mel.getValue("rows").ints()
        val cols = mel.getValue("cols").ints()
        val values = mel.getValue("values").floats()
        val expected = Array(filters.size) { FloatArray(filters[0].size) }
        for (i in rows.indices) expected[rows[i]!!][cols[i]!!] = values[i]
        assertMatrixClose("mel filters", expected, filters, 1e-9)
    }

    @Test
    fun theFftMatchesNumpy() {
        val fft = golden.obj("fft")
        val (re, im) = Fft(ONSET_N_FFT).rfft(fft.getValue("frame").doubles())
        assertArrayEquals(fft.getValue("re").doubles(), re, 1e-9)
        assertArrayEquals(fft.getValue("im").doubles(), im, 1e-9)
    }

    @Test
    fun onsetStrengthMatchesLibrosa() {
        val case = golden.obj("onset_case")
        val onset = onsetStrength(case.getValue("y").floats())
        assertMatrixClose("onset strength", arrayOf(case.getValue("onset").floats()), arrayOf(onset), 1e-4)
    }

    @Test
    fun streamingTheOnsetInChunksChangesNothing() {
        val y = golden.obj("onset_case").getValue("y").floats()
        val whole = onsetStrength(y)
        for (chunk in listOf(1, 511, 4096)) {
            val onset = OnsetStrength()
            var at = 0
            while (at < y.size) {
                val count = minOf(chunk, y.size - at)
                onset.write(y.copyOfRange(at, at + count), count)
                at += count
            }
            assertTrue("chunks of $chunk", whole.contentEquals(onset.finish()))
        }
        assertTrue(abs(whole.sum()) > 0)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.beats.OnsetGoldenTest'`
Expected: compilation fails with `Unresolved reference 'melFilters'` (and `Fft`, `onsetStrength`, `OnsetStrength`).

- [ ] **Step 3: Write the FFT**

Create `.../engine/beats/Fft.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Radix-2 complex FFT in double precision, for a size that is a power of two. librosa's
 *  spectra come from scipy.fft in double precision too, so the results agree to rounding. */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size $size is not a power of two" }
    }

    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** In place: [re] and [im] become the transform (forward: e^-2πi kn/N; inverse also divides by N). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var length = 2
        while (length <= size) {
            val half = length / 2
            val stride = size / length
            for (start in 0 until size step length) {
                for (k in 0 until half) {
                    val wr = cosines[k * stride]
                    val wi = sign * sines[k * stride]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
            }
            length *= 2
        }
        if (inverse) {
            for (i in 0 until size) {
                re[i] /= size
                im[i] /= size
            }
        }
    }

    /** numpy.fft.rfft of a real signal of [size] values: bins 0..size/2, as (real parts, imaginary parts). */
    fun rfft(signal: DoubleArray): Pair<DoubleArray, DoubleArray> {
        require(signal.size == size) { "signal has ${signal.size} values, expected $size" }
        val re = signal.copyOf()
        val im = DoubleArray(size)
        transform(re, im)
        return re.copyOf(size / 2 + 1) to im.copyOf(size / 2 + 1)
    }
}
```

- [ ] **Step 4: Write the mel spectrogram and onset strength**

Create `.../engine/beats/Onset.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.audio.SampleSink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10

// Port of librosa 1.0.1's onset_strength(y, sr=22050, hop_length=512) as
// chordify_backend.app.pipeline.beats.track_beats calls it: a centred, zero-padded STFT
// (n_fft 2048, periodic Hann), a 128-band Slaney mel power spectrogram, power_to_db
// (ref 1, amin 1e-10, top_db 80 below the song's peak), then the mean positive difference
// between consecutive frames, shifted 3 frames late.

const val ONSET_SAMPLE_RATE = 22050
const val ONSET_HOP = 512
const val ONSET_N_FFT = 2048
const val ONSET_N_MELS = 128

/** scipy.signal.get_window("hann", n, fftbins=True): the periodic Hann window. */
fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

// The Slaney mel scale, with librosa's constants computed as librosa computes them.
private const val MEL_F_SP = 200.0 / 3
private const val MEL_MIN_LOG_HZ = 1000.0
private const val MEL_MIN_LOG_MEL = MEL_MIN_LOG_HZ / MEL_F_SP
private val MEL_LOGSTEP = ln(6.4) / 27.0

/** librosa.hz_to_mel with htk=False (Slaney: linear below 1 kHz, logarithmic above). */
fun hzToMel(hz: Double): Double =
    if (hz >= MEL_MIN_LOG_HZ) MEL_MIN_LOG_MEL + ln(hz / MEL_MIN_LOG_HZ) / MEL_LOGSTEP else hz / MEL_F_SP

/** librosa.mel_to_hz with htk=False. */
fun melToHz(mel: Double): Double =
    if (mel >= MEL_MIN_LOG_MEL) MEL_MIN_LOG_HZ * exp(MEL_LOGSTEP * (mel - MEL_MIN_LOG_MEL)) else MEL_F_SP * mel

/** librosa.filters.mel(sr, n_fft, n_mels) with fmin 0, fmax sr / 2 and Slaney normalisation,
 *  as float32: [nMels][1 + nFft / 2]. */
fun melFilters(sampleRate: Int, nFft: Int, nMels: Int = ONSET_N_MELS): Array<FloatArray> {
    val bins = 1 + nFft / 2
    val fftFreqs = DoubleArray(bins) { it * (sampleRate.toDouble() / nFft) }
    val minMel = hzToMel(0.0)
    val maxMel = hzToMel(sampleRate / 2.0)
    val step = (maxMel - minMel) / (nMels + 1) // numpy.linspace(minMel, maxMel, nMels + 2)
    val melF = DoubleArray(nMels + 2) { melToHz(if (it == nMels + 1) maxMel else it * step + minMel) }
    return Array(nMels) { i ->
        val enorm = 2.0 / (melF[i + 2] - melF[i])
        FloatArray(bins) { f ->
            val lower = -(melF[i] - fftFreqs[f]) / (melF[i + 1] - melF[i])
            val upper = (melF[i + 2] - fftFreqs[f]) / (melF[i + 2] - melF[i + 1])
            // numpy stores the weights as float32, then scales them by the float64 norm in place
            (maxOf(0.0, minOf(lower, upper)).toFloat().toDouble() * enorm).toFloat()
        }
    }
}

/** Onset strength of a mono 22.05 kHz stream, written chunk by chunk; [finish] returns the
 *  envelope, one value per 512-sample frame (1 + n / 512 of them). */
class OnsetStrength : SampleSink {
    private val fft = Fft(ONSET_N_FFT)
    private val window = hann(ONSET_N_FFT)
    private val filters = melFilters(ONSET_SAMPLE_RATE, ONSET_N_FFT)
    private val re = DoubleArray(ONSET_N_FFT)
    private val im = DoubleArray(ONSET_N_FFT)
    private val power = FloatArray(ONSET_N_FFT / 2 + 1)
    private val mel = ArrayList<FloatArray>() // mel power, one row per frame
    private var buffer = FloatArray(4 * ONSET_N_FFT)
    private var bufferStart = 0L // sample index of buffer[0]
    private var bufferEnd = 0L // samples received
    private var nextFrame = 0L

    override fun write(samples: FloatArray, count: Int) {
        ensureRoom(count)
        samples.copyInto(buffer, (bufferEnd - bufferStart).toInt(), 0, count)
        bufferEnd += count
        // frame t covers samples [512 t - 1024, 512 t + 1024): complete once 512 t + 1024 have arrived
        while (nextFrame * ONSET_HOP + ONSET_N_FFT / 2 <= bufferEnd) frame(nextFrame++)
    }

    fun finish(): FloatArray {
        val frames = 1 + bufferEnd / ONSET_HOP
        while (nextFrame < frames) frame(nextFrame++)
        return envelope(mel)
    }

    private fun ensureRoom(count: Int) {
        val keepFrom = maxOf(bufferStart, nextFrame * ONSET_HOP - ONSET_N_FFT / 2) // first sample still needed
        val kept = (bufferEnd - keepFrom).toInt()
        if (keepFrom > bufferStart) {
            buffer.copyInto(buffer, 0, (keepFrom - bufferStart).toInt(), (bufferEnd - bufferStart).toInt())
            bufferStart = keepFrom
        }
        if (kept + count > buffer.size) buffer = buffer.copyOf(maxOf(2 * buffer.size, kept + count))
    }

    private fun frame(t: Long) {
        val first = t * ONSET_HOP - ONSET_N_FFT / 2
        for (j in 0 until ONSET_N_FFT) {
            val index = first + j
            val sample = if (index in bufferStart until bufferEnd) buffer[(index - bufferStart).toInt()].toDouble() else 0.0
            re[j] = window[j] * sample
            im[j] = 0.0
        }
        fft.transform(re, im)
        for (f in power.indices) {
            // librosa keeps the STFT as complex64, so the magnitude is taken in float32
            val magnitude = hypot(re[f].toFloat().toDouble(), im[f].toFloat().toDouble()).toFloat()
            power[f] = magnitude * magnitude
        }
        mel += FloatArray(ONSET_N_MELS) { m ->
            val weights = filters[m]
            var sum = 0.0
            for (f in power.indices) if (weights[f] != 0f) sum += weights[f].toDouble() * power[f]
            sum.toFloat()
        }
    }

    private companion object {
        const val LAG = 1
        const val PAD = LAG + ONSET_N_FFT / (2 * ONSET_HOP) // 3: the lag plus the centring

        fun envelope(mel: List<FloatArray>): FloatArray {
            // power_to_db as librosa 1.0.1 computes it on a float32 array: log10 in place, then × 10
            val db = Array(mel.size) { t -> FloatArray(ONSET_N_MELS) { m -> log10(maxOf(1e-10f, mel[t][m]).toDouble()).toFloat() * 10f } }
            val peak = db.maxOfOrNull { row -> row.max() } ?: 0f
            for (row in db) for (m in row.indices) row[m] = maxOf(row[m], peak - 80f)
            return FloatArray(mel.size) { t ->
                if (t < PAD) 0f else {
                    var sum = 0.0
                    for (m in 0 until ONSET_N_MELS) sum += maxOf(0f, db[t - PAD + LAG][m] - db[t - PAD][m])
                    (sum / ONSET_N_MELS).toFloat()
                }
            }
        }
    }
}

/** The onset strength of a whole signal at 22.05 kHz. */
fun onsetStrength(y: FloatArray): FloatArray = OnsetStrength().also { it.write(y, y.size) }.finish()
```
The float32 and float64 steps follow librosa's: the STFT is computed in double precision and stored as complex64, so the magnitude is taken in float32. The mel weights are float32, scaled in place by a float64 norm. dB values are float32.

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `OnsetGoldenTest` 4 passed, 51 in all.

- [ ] **Step 6: Commit**

```bash
git add Mobile/engine
git commit -m "Port librosa's onset strength to the engine" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Tempo and the beat tracker

**Files:**
- Create: `.../engine/beats/BeatTrack.kt`, `.../engine/beats/BeatInfo.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/beats/BeatTrackGoldenTest.kt`

**Interfaces:**
- Consumes: `Fft`, `hann`, `ONSET_*` (Task 2); `pyRound`, `pyRoundToInt` (M1); `beats.json.gz` `inputs[]` (Task 1).
- Produces (package `...engine.beats`): `fun tempogramMean(onset: FloatArray): DoubleArray`; `fun estimateTempo(onset: FloatArray): Double`; `fun trackBeatFrames(onset: FloatArray, bpm: Double): IntArray`; `fun beatTrack(onset: FloatArray): Pair<Double, IntArray>`; `class BeatInfo(val beats: DoubleArray, val bpm: Double?, val confidence: Double)` with `usable`; `fun trackBeats(onset: FloatArray): BeatInfo`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/beats/BeatTrackGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.bool
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BeatTrackGoldenTest {
    private val golden = Goldens.loadGz("beats")

    @Test
    fun theTempogramAndTempoMatchLibrosa() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val onset = input.getValue("onset").floats()
            assertArrayEquals(name, input.getValue("tempogram").doubles(), tempogramMean(onset), 1e-9)
            assertEquals(name, input.double("tempo"), estimateTempo(onset), 0.0)
        }
    }

    @Test
    fun beatFramesMatchLibrosaExactly() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val frames = trackBeatFrames(input.getValue("onset").floats(), input.double("tempo"))
            assertEquals(name, input.getValue("beat_frames").ints(), frames.toList())
        }
    }

    @Test
    fun beatInfoMatchesTheServer() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val info = trackBeats(input.getValue("onset").floats())
            assertArrayEquals(name, input.getValue("beats").doubles(), info.beats, 1e-12)
            val bpm = input.getValue("bpm")
            if (bpm is JsonNull) assertNull(name, info.bpm) else assertEquals(name, bpm.jsonPrimitive.double, info.bpm!!, 0.0)
            assertEquals(name, input.double("confidence"), info.confidence, 0.0)
            assertEquals(name, input.bool("usable"), info.usable)
        }
    }

    @Test
    fun silenceHasNoBeats() {
        val info = trackBeats(FloatArray(500))
        assertEquals(0, info.beats.size)
        assertNull(info.bpm)
        assertEquals(0.0, info.confidence, 0.0)
        assertFalse(info.usable)
    }
}
```
The tracker is fed librosa's own onset envelope from the golden file, so its beat frames must be identical, not just close.

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.beats.BeatTrackGoldenTest'`
Expected: compilation fails with `Unresolved reference 'tempogramMean'` (and `estimateTempo`, `trackBeatFrames`, `trackBeats`).

- [ ] **Step 3: Write the tempo estimate and the tracker**

Create `.../engine/beats/BeatTrack.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.util.pyRoundToInt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log2
import kotlin.math.sqrt

// Port of librosa 1.0.1's beat_track(onset_envelope=..., sr=22050, hop_length=512, trim=False):
// tempo from the mean autocorrelation tempogram with a log-normal prior around 120 bpm, then
// Ellis's dynamic-programming beat tracker. The float32/float64 split follows librosa's numba
// kernels, so the beat frames come out identical for the same onset envelope.

private const val FRAME_RATE = ONSET_SAMPLE_RATE.toDouble() / ONSET_HOP
private const val TEMPOGRAM_WINDOW = 344 // librosa.time_to_frames(8.0, sr=22050, hop_length=512)
private const val START_BPM = 120.0
private const val MAX_TEMPO = 320.0
private const val TIGHTNESS = 100.0

/** librosa.feature.tempogram(onset_envelope, win_length=344).mean(axis=1): the autocorrelation
 *  of a Hann-windowed stretch of onsets around every frame, each normalised by its peak, averaged. */
fun tempogramMean(onset: FloatArray): DoubleArray {
    val n = onset.size
    val half = TEMPOGRAM_WINDOW / 2
    // np.pad(mode="linear_ramp", end_values=0): ramps from 0 up to the edge values, in float32
    val padded = FloatArray(n + TEMPOGRAM_WINDOW)
    val left = onset.firstOrNull() ?: 0f
    val right = onset.lastOrNull() ?: 0f
    for (k in 0 until half) {
        padded[k] = (k * (left.toDouble() / half)).toFloat()
        padded[n + half + k] = ((half - 1 - k) * (right.toDouble() / half)).toFloat()
    }
    onset.copyInto(padded, half)
    val window = hann(TEMPOGRAM_WINDOW)
    val fft = Fft(1024) // at least 2 × 344 - 1, so the circular autocorrelation is the linear one
    val re = DoubleArray(1024)
    val im = DoubleArray(1024)
    val sum = DoubleArray(TEMPOGRAM_WINDOW)
    for (t in 0 until n) {
        for (i in 0 until 1024) {
            re[i] = if (i < TEMPOGRAM_WINDOW) padded[t + i] * window[i] else 0.0
            im[i] = 0.0
        }
        fft.transform(re, im)
        for (i in 0 until 1024) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0.0
        }
        fft.transform(re, im, inverse = true)
        var peak = 0.0
        for (lag in 0 until TEMPOGRAM_WINDOW) peak = maxOf(peak, abs(re[lag]))
        if (peak < java.lang.Double.MIN_NORMAL) peak = 1.0 // util.normalize leaves an all-zero column as it is
        for (lag in 0 until TEMPOGRAM_WINDOW) sum[lag] += re[lag] / peak
    }
    return DoubleArray(TEMPOGRAM_WINDOW) { sum[it] / n }
}

/** librosa.feature.tempo(onset_envelope, sr=22050, hop_length=512): the tempogram bin that best
 *  balances periodicity against a log-normal prior around 120 bpm, below 320 bpm. */
fun estimateTempo(onset: FloatArray): Double {
    val tempogram = tempogramMean(onset)
    val bpms = DoubleArray(TEMPOGRAM_WINDOW) { if (it == 0) Double.POSITIVE_INFINITY else 60.0 * ONSET_SAMPLE_RATE / (ONSET_HOP * it.toDouble()) }
    val fastest = bpms.indexOfFirst { it < MAX_TEMPO }
    var best = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (i in bpms.indices) {
        val prior = if (i < fastest) Double.NEGATIVE_INFINITY else -0.5 * (log2(bpms[i]) - log2(START_BPM)).let { it * it }
        val score = ln1p(1e6 * tempogram[i]) + prior
        if (score > bestScore) {
            best = i
            bestScore = score
        }
    }
    return bpms[best]
}

/** The beat frames librosa.beat.beat_track(..., trim=False) finds at [bpm]. */
fun trackBeatFrames(onset: FloatArray, bpm: Double): IntArray {
    val n = onset.size
    val framesPerBeat = Math.rint(FRAME_RATE * 60.0 / bpm)

    // __normalize_onsets: divide by the standard deviation (ddof 1), in float32
    val mean = onset.sumOf { it.toDouble() } / n
    val norm = sqrt(onset.sumOf { (it - mean) * (it - mean) } / (n - 1)).toFloat()
    val normalised = FloatArray(n) { onset[it] / (norm + java.lang.Float.MIN_NORMAL) }

    // __beat_local_score: a Gaussian window of ±1 beat, accumulated in float32 as numba does
    val fpb = framesPerBeat.toInt()
    val kernel = DoubleArray(2 * fpb + 1) { k -> exp(-0.5 * ((k - framesPerBeat) * 32.0 / framesPerBeat).let { it * it }) }
    val size = kernel.size
    val local = FloatArray(n) { i ->
        var acc = 0f
        for (k in maxOf(0, i + size / 2 - n + 1) until minOf(i + size / 2, size)) {
            acc = (acc.toDouble() + kernel[k] * normalised[i + size / 2 - k]).toFloat()
        }
        acc
    }

    // __beat_track_dp, in float64
    val threshold = 0.01 * (local.maxOrNull() ?: 0f)
    val backlink = IntArray(n)
    val cumscore = DoubleArray(n)
    var firstBeat = true
    val logFpb = ln(framesPerBeat)
    val nearest = pyRoundToInt(framesPerBeat / 2)
    for (i in 0 until n) {
        var bestScore = Double.NEGATIVE_INFINITY
        var location = -1
        var loc = i - nearest
        val stop = (i - 2 * framesPerBeat - 1).toInt()
        while (loc > stop) {
            if (loc < 0) break
            val gap = ln((i - loc).toDouble()) - logFpb
            val score = cumscore[loc] - TIGHTNESS * gap * gap
            if (score > bestScore) {
                bestScore = score
                location = loc
            }
            loc--
        }
        cumscore[i] = if (location >= 0) local[i] + bestScore else local[i].toDouble()
        if (firstBeat && local[i] < threshold) {
            backlink[i] = -1
        } else {
            backlink[i] = location
            firstBeat = false
        }
    }

    // __last_beat: the last local maximum of the cumulative score above half their median
    val peaks = BooleanArray(n) { i ->
        when {
            n < 2 -> false
            i == 0 -> false
            i == n - 1 -> cumscore[i] > cumscore[i - 1]
            else -> cumscore[i] > cumscore[i - 1] && cumscore[i] >= cumscore[i + 1]
        }
    }
    val peakScores = cumscore.filterIndexed { i, _ -> peaks[i] }.sorted()
    var tail = n - 1
    if (peakScores.isNotEmpty()) {
        val middle = peakScores.size / 2
        val median = if (peakScores.size % 2 == 1) peakScores[middle] else (peakScores[middle - 1] + peakScores[middle]) / 2
        for (i in n - 1 downTo 0) {
            if (peaks[i] && cumscore[i] >= 0.5 * median) {
                tail = i
                break
            }
        }
    }

    // __dp_backtrack, then __trim_beats with trim=False: only frames with no local score at the
    // ends are dropped
    val beats = BooleanArray(n)
    var at = tail
    while (at >= 0) {
        beats[at] = true
        at = backlink[at]
    }
    var start = 0
    while (start < n && local[start] <= 0f) beats[start++] = false
    var end = n - 1
    while (end >= 0 && local[end] <= 0f) beats[end--] = false
    return beats.indices.filter { beats[it] }.toIntArray()
}

/** librosa.beat.beat_track(onset_envelope=onset, sr=22050, hop_length=512, trim=False):
 *  (tempo in bpm, beat frames); (0.0, no beats) for an envelope of zeros. */
fun beatTrack(onset: FloatArray): Pair<Double, IntArray> {
    if (onset.none { it != 0f }) return 0.0 to IntArray(0)
    val bpm = estimateTempo(onset)
    return bpm to trackBeatFrames(onset, bpm)
}
```
Two details decide whether the frames come out identical:
- librosa's `__beat_local_score` adds float64 products into a float32 array, rounding at every step, and the Kotlin `acc` does the same.
- numba's `round()` rounds halves to even, hence `pyRoundToInt`.

The tempogram uses a 1024-point FFT where scipy uses 720 points. Both are at least 2 × 344 − 1 long, so both give the linear autocorrelation.

Create `.../engine/beats/BeatInfo.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.util.pyRound
import kotlin.math.sqrt

/** chordify_backend.app.pipeline.beats.BeatInfo: beat times (s), tempo, and how regular the
 *  inter-beat intervals are (0..1). */
class BeatInfo(val beats: DoubleArray, val bpm: Double?, val confidence: Double) {
    /** Enough regular beats to decode chords on them. */
    val usable: Boolean get() = beats.size >= 8 && confidence >= 0.5
}

/** The rest of track_beats once the onset envelope is known: beats, rounded tempo, regularity. */
fun trackBeats(onset: FloatArray): BeatInfo {
    val (tempo, frames) = beatTrack(onset)
    val beats = DoubleArray(frames.size) { frames[it] * ONSET_HOP / ONSET_SAMPLE_RATE.toDouble() }
    if (beats.size < 4) return BeatInfo(beats, null, 0.0)
    val intervals = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
    val mean = intervals.average()
    val std = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
    val confidence = (1.0 - std / maxOf(mean, 1e-6)).coerceIn(0.0, 1.0)
    return BeatInfo(beats, pyRound(tempo, 1), pyRound(confidence, 3))
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `BeatTrackGoldenTest` 4 passed, 55 in all. If `beatFramesMatchLibrosaExactly` fails for one input, compare `framesPerBeat`, the normalised onsets and the first frames of `local` with librosa's `__beat_tracker` in a Python session. Do not loosen the test: the frames come from arithmetic that the port reproduces.

- [ ] **Step 5: Commit**

```bash
git add Mobile/engine
git commit -m "Port librosa's tempo estimate and dynamic-programming beat tracker" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Beats from the audio stream, and one pass for the whole front end

**Files:**
- Create: `.../engine/beats/BeatTracker.kt`, `.../engine/analysis/FrontEnd.kt`
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/beats/BeatTrackerDeviceTest.kt`
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/analysis/FrontEndDeviceTest.kt`

**Interfaces:**
- Consumes: `Resampler`, `SampleSink`, `streamAudio`, `ANALYSIS_SAMPLE_RATE`, `AudioSource`, `AudioLimits`, `StreamInfo`, `NnlsChroma`, `nnlsToCOrder` (M2); `OnsetStrength`, `trackBeats`, `BeatInfo` (Tasks 2–3).
- Produces: `class BeatTracker : SampleSink, AutoCloseable` with `finish(): Pair<FloatArray, BeatInfo>` (package `...engine.beats`); `class FrontEnd(val info: StreamInfo, val features: Array<FloatArray>, val onset: FloatArray, val beats: BeatInfo)` and `fun runFrontEnd(source: AudioSource, limits: AudioLimits = AudioLimits(), isCancelled: () -> Boolean = { false }): FrontEnd` (package `...engine.analysis`). M3b's analysis starts from `runFrontEnd`.

- [ ] **Step 1: Write the failing device tests**

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/beats/BeatTrackerDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.streamAudio
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BeatTrackerDeviceTest {
    // spec §7.1: beats within one onset frame (23 ms) of the server's
    @Test
    fun beatsMatchTheServer() {
        val inputs = Goldens.loadGz("beats").objects("inputs")
        assertEquals(6, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val (onset, info) = BeatTracker().use { tracker ->
                streamAudio(AudioSource.FromFile(TestAudio.golden(name)), listOf(tracker))
                tracker.finish()
            }
            assertMatrixClose("$name onset", arrayOf(input.getValue("onset").floats()), arrayOf(onset), 1e-3)
            val expected = input.getValue("beats").doubles()
            assertEquals("$name beats", expected.size, info.beats.size)
            val frame = ONSET_HOP / ONSET_SAMPLE_RATE.toDouble()
            for (i in expected.indices) assertTrue("$name beat $i", abs(expected[i] - info.beats[i]) <= frame + 1e-9)
            assertEquals("$name bpm", input.double("bpm"), info.bpm!!, 0.0)
        }
    }
}
```

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/analysis/FrontEndDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertFeaturesClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.beats.ONSET_HOP
import com.arbaba.chordifymobile.engine.beats.ONSET_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** One decoding pass gives the same features and beats as the two separately (spec §5). */
@RunWith(AndroidJUnit4::class)
class FrontEndDeviceTest {
    @Test
    fun oneDecodeGivesTheServersFeaturesAndBeats() {
        val features = Goldens.loadGz("features").objects("inputs")
        val beats = Goldens.loadGz("beats").objects("inputs")
        for (name in listOf("loop_48k.wav", "sevenths_stereo_22k.wav")) {
            val front = runFrontEnd(AudioSource.FromFile(TestAudio.golden(name)))
            assertFeaturesClose(name, features.single { it.str("file") == name }.getValue("raw").floatMatrix(), front.features)
            val expected = beats.single { it.str("file") == name }.getValue("beats").doubles()
            assertEquals(name, expected.size, front.beats.beats.size)
            for (i in expected.indices) {
                assertTrue("$name beat $i", abs(expected[i] - front.beats.beats[i]) <= ONSET_HOP / ONSET_SAMPLE_RATE.toDouble() + 1e-9)
            }
        }
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd Mobile && ./gradlew :engine:compileDebugAndroidTestKotlin`
Expected: compilation fails with `Unresolved reference 'BeatTracker'` and `Unresolved reference 'runFrontEnd'`.

- [ ] **Step 3: Write the sink and the front end**

Create `.../engine/beats/BeatTracker.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.audio.ANALYSIS_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.audio.Resampler
import com.arbaba.chordifymobile.engine.audio.SampleSink

/** Beat tracking as the server does it, as a sink of the 44.1 kHz stream: resampled to
 *  22.05 kHz with libsoxr (librosa.resample), onset strength, then [trackBeats] in [finish]. */
class BeatTracker : SampleSink, AutoCloseable {
    private val resampler = Resampler(ANALYSIS_SAMPLE_RATE, ONSET_SAMPLE_RATE)
    private val onset = OnsetStrength()

    override fun write(samples: FloatArray, count: Int) {
        val resampled = resampler.process(samples, count)
        onset.write(resampled, resampled.size)
    }

    /** The onset envelope and the beats. Call once, after the last [write]. */
    fun finish(): Pair<FloatArray, BeatInfo> {
        val rest = resampler.finish()
        onset.write(rest, rest.size)
        val envelope = onset.finish()
        return envelope to trackBeats(envelope)
    }

    override fun close() = resampler.close()
}
```

Create `.../engine/analysis/FrontEnd.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.StreamInfo
import com.arbaba.chordifymobile.engine.audio.streamAudio
import com.arbaba.chordifymobile.engine.beats.BeatInfo
import com.arbaba.chordifymobile.engine.beats.BeatTracker
import com.arbaba.chordifymobile.engine.features.NnlsChroma
import com.arbaba.chordifymobile.engine.features.nnlsToCOrder

/** Everything the analysis takes from the audio, from one decoding pass (spec §5): the stream's
 *  facts, raw NNLS bothchroma (C first, one row per frame), the onset envelope and the beats. */
class FrontEnd(val info: StreamInfo, val features: Array<FloatArray>, val onset: FloatArray, val beats: BeatInfo)

/** Decodes [source] once and feeds NNLS Chroma and the beat tracker together, as the server's
 *  analyse() computes features.extract and track_beats from the same samples. */
fun runFrontEnd(source: AudioSource, limits: AudioLimits = AudioLimits(), isCancelled: () -> Boolean = { false }): FrontEnd =
    NnlsChroma().use { nnls ->
        BeatTracker().use { tracker ->
            val info = streamAudio(source, listOf(nnls, tracker), limits, isCancelled)
            val (onset, beats) = tracker.finish()
            FrontEnd(info, nnlsToCOrder(nnls.finish()), onset, beats)
        }
    }
```

- [ ] **Step 4: Run the device tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 20 tests passed (M2's 18, `BeatTrackerDeviceTest` 1, `FrontEndDeviceTest` 1). The onset envelope is allowed 1e-3 of librosa's (libsoxr and float32 sums round a little differently on the device); the beats must still land within one frame, with the same count and tempo.

- [ ] **Step 5: Commit**

```bash
git add Mobile/engine
git commit -m "Track beats from the audio stream, and run NNLS and beats from one decode" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The chord model with ONNX Runtime

**Files:**
- Modify: `Mobile/gradle/libs.versions.toml`, `Mobile/engine/build.gradle`
- Modify: `.../engine/Errors.kt`
- Create: `.../engine/acoustic/Bundle.kt`, `.../engine/acoustic/Windowed.kt`, `.../engine/acoustic/ChordModel.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/acoustic/AcousticGoldenTest.kt`
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/acoustic/ChordModelDeviceTest.kt`

**Interfaces:**
- Consumes: `FeatureSpec`, `NNLS_BOTHCHROMA`, `normaliseBothchroma` (M2); `EngineException`, `ErrorCode` (M2); `acoustic.json.gz` (Task 1); `models/chordnet-chroma/3.0.0/` (repository).
- Produces (package `...engine.acoustic`): `class ChordBundle(id, modelFile, sha256, featureSpec, vocabulary, outputs, window, margin, decoder: Map<String, Double>, classPrior: DoubleArray?)` with `ChordBundle.parse(text)`; `fun sha256(bytes): String`; `fun windowed(n, window, margin, run): Map<String, Array<FloatArray>>`; `fun softmaxRows(logits)`, `fun sigmoid(logits)`; `class AcousticOutput(chord, boundary, keyTonic, keyMode, root, bass)`; `class ChordModel : AutoCloseable` with `ChordModel.fromAssets(context, directory = ChordModel.BUNDLE)`, `logits(input)`, `predict(raw): AcousticOutput`. `ErrorCode.MODEL_UNAVAILABLE`. Assets `models/chordnet-chroma/3.0.0/*` and `models/progression-ngram/1.0.0/*`.

- [ ] **Step 1: Write the failing JVM test**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/acoustic/AcousticGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.acoustic

import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.features.NNLS_BOTHCHROMA
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AcousticGoldenTest {
    private val golden = Goldens.loadGz("acoustic")

    private fun repoFile(path: String): File {
        val start = File("").absoluteFile // the working directory: Gradle runs unit tests in the module
        var dir: File? = start
        while (dir != null && !File(dir, path).exists()) dir = dir.parentFile
        return File(dir ?: error("$path not found above $start"), path)
    }

    private val bundleJson get() = repoFile("models/chordnet-chroma/3.0.0/bundle.json").readText()

    @Test
    fun windowingMatchesPython() {
        for (case in golden.objects("windowed")) {
            val n = case.int("frames")!!
            // the stand-in model of mobile_goldens.acoustic_golden: input + position in the window / 1000
            val out = windowed(n, 512, 64) { start, length ->
                mapOf("a" to Array(length) { floatArrayOf((start + it).toFloat() + it.toFloat() / 1000f) })
            }.getValue("a")
            assertArrayEquals("$n frames", case.getValue("out").floats(), FloatArray(n) { out[it][0] }, 0f)
        }
    }

    @Test
    fun softmaxAndSigmoidMatchNumpy() {
        val softmax = golden.obj("softmax")
        assertMatrixClose("softmax", softmax.getValue("p").floatMatrix(), softmaxRows(softmax.getValue("logits").floatMatrix()), 1e-6)
        val sigmoid = golden.obj("sigmoid")
        assertArrayEquals(sigmoid.getValue("p").floats(), sigmoid(sigmoid.getValue("logits").floats()), 1e-6f)
    }

    @Test
    fun theBundleIsReadAsPythonReadsIt() {
        val bundle = ChordBundle.parse(bundleJson)
        assertEquals("chordnet-chroma@3.0.0", bundle.id)
        assertEquals("model.onnx", bundle.modelFile)
        assertEquals(NNLS_BOTHCHROMA, bundle.featureSpec)
        assertEquals("large", bundle.vocabulary)
        assertEquals(listOf("chord", "root", "bass", "tones", "key_tonic", "key_mode", "boundary"), bundle.outputs)
        assertEquals(512, bundle.window)
        assertEquals(64, bundle.margin)
        assertEquals(0.3, bundle.decoder.getValue("alpha"), 0.0)
        assertEquals(0.5, bundle.decoder.getValue("inversion_threshold"), 0.0)
        assertEquals(169, bundle.classPrior!!.size)
        assertEquals(sha256(repoFile("models/chordnet-chroma/3.0.0/model.onnx").readBytes()), bundle.sha256)
    }

    @Test
    fun aModelForOtherFeaturesIsRefused() {
        val other = bundleJson.replace("\"revision\": 1", "\"revision\": 2")
        val error = assertThrows(EngineException::class.java) { ChordBundle.parse(other) }
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, error.code)
    }

    @Test
    fun sha256IsLowerCaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".toByteArray()))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.acoustic.AcousticGoldenTest'`
Expected: compilation fails with `Unresolved reference 'windowed'`, `'ChordBundle'`, `'sha256'` and `'MODEL_UNAVAILABLE'`.

- [ ] **Step 3: Add the error code, the bundle and the windowing**

In `.../engine/Errors.kt`, replace
```kotlin
/** The server's error codes (chordify_backend/app/audio_io.py), so the app can show the web app's
 *  message for each. M3 adds NO_MUSIC_DETECTED and MODEL_UNAVAILABLE. */
enum class ErrorCode { UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG }
```
with
```kotlin
/** The server's error codes (chordify_backend/app/audio_io.py), so the app can show the web app's
 *  message for each, plus MODEL_UNAVAILABLE for a model file that fails its SHA-256 check or needs
 *  features this engine does not compute. M3b adds NO_MUSIC_DETECTED. */
enum class ErrorCode { UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG, MODEL_UNAVAILABLE }
```

Create `.../engine/acoustic/Bundle.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.acoustic

import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.features.FeatureSpec
import com.arbaba.chordifymobile.engine.features.NNLS_BOTHCHROMA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** A model bundle's bundle.json (chordify_ai/export/bundle.py), as chordify_core.acoustic.OnnxChordModel reads it. */
class ChordBundle(
    val id: String,
    val modelFile: String,
    val sha256: String,
    val featureSpec: FeatureSpec,
    val vocabulary: String,
    val outputs: List<String>,
    val window: Int,
    val margin: Int,
    /** The numeric settings of the bundle's "decoder" block (alpha, self_prob, subdivide, ...). */
    val decoder: Map<String, Double>,
    val classPrior: DoubleArray?,
) {
    companion object {
        /** Reads bundle.json, refusing a model whose features this engine does not compute. */
        fun parse(text: String): ChordBundle {
            val json = Json.parseToJsonElement(text).jsonObject
            val features = json.getValue("features").jsonObject
            val spec = FeatureSpec(
                kind = features.text("kind"),
                sampleRate = features.getValue("sample_rate").jsonPrimitive.int,
                hop = features.getValue("hop").jsonPrimitive.int,
                nBins = features.getValue("n_bins").jsonPrimitive.int,
                offset = features["offset"]?.jsonPrimitive?.doubleOrNull ?: NNLS_BOTHCHROMA.offset,
                revision = features["revision"]?.jsonPrimitive?.int ?: 1,
            )
            if (spec != NNLS_BOTHCHROMA) {
                throw EngineException(ErrorCode.MODEL_UNAVAILABLE,
                    "The model needs ${spec.kind} features (revision ${spec.revision}); this engine computes " +
                        "${NNLS_BOTHCHROMA.kind} revision ${NNLS_BOTHCHROMA.revision}.")
            }
            val files = json.getValue("files").jsonObject
            val inference = json["inference"]?.jsonObject
            return ChordBundle(
                id = json.text("id"),
                modelFile = files.text("model"),
                sha256 = files.text("sha256"),
                featureSpec = spec,
                vocabulary = json.text("vocabulary"),
                outputs = json.getValue("outputs").jsonArray.map { it.jsonPrimitive.content },
                window = inference?.get("window")?.jsonPrimitive?.int ?: 512,
                margin = inference?.get("margin")?.jsonPrimitive?.int ?: 64,
                decoder = json["decoder"]?.jsonObject?.mapNotNull { (key, value) ->
                    value.jsonPrimitive.doubleOrNull?.let { key to it }
                }?.toMap().orEmpty(),
                classPrior = json["class_prior"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }?.toDoubleArray(),
            )
        }

        private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    }
}

/** Lower-case hex SHA-256, as hashlib.sha256(...).hexdigest(). */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
```

Create `.../engine/acoustic/Windowed.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.acoustic

import kotlin.math.exp

/** chordify_core.acoustic.windowed: runs a sequence model over [n] frames in overlapping windows
 *  of [window] frames, keeping each window's centre (all but [margin] frames at each inner edge),
 *  so every kept frame has context. [run] gets (first frame, frame count) and returns every
 *  output's rows for those frames. */
fun windowed(n: Int, window: Int, margin: Int, run: (start: Int, length: Int) -> Map<String, Array<FloatArray>>): Map<String, Array<FloatArray>> {
    if (n <= window) return run(0, n)
    val step = window - 2 * margin
    val outputs = LinkedHashMap<String, Array<FloatArray>>()
    var start = 0
    while (true) {
        val s = minOf(start, n - window)
        val chunk = run(s, window)
        val keepFrom = if (s == 0) 0 else margin
        val keepTo = if (s + window >= n) window else window - margin
        for ((name, rows) in chunk) {
            val buffer = outputs.getOrPut(name) { Array(n) { FloatArray(rows[0].size) } }
            for (i in keepFrom until keepTo) buffer[s + i] = rows[i]
        }
        if (s + window >= n) break
        start = s + step
    }
    return outputs
}

/** chordify_core.acoustic.softmax over each row, in float32 as numpy computes it. */
fun softmaxRows(logits: Array<FloatArray>): Array<FloatArray> = Array(logits.size) { t ->
    val row = logits[t]
    val max = row.max()
    val e = FloatArray(row.size) { exp((row[it] - max).toDouble()).toFloat() }
    val sum = e.sum()
    FloatArray(row.size) { e[it] / sum }
}

/** chordify_core.acoustic.sigmoid, in float32. */
fun sigmoid(logits: FloatArray): FloatArray = FloatArray(logits.size) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() }
```

- [ ] **Step 4: Run the JVM tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `AcousticGoldenTest` 5 passed, 60 in all.

- [ ] **Step 5: Add ONNX Runtime and the model files**

In `Mobile/gradle/libs.versions.toml`, under `[versions]` after `kotlinxSerialization = "1.7.3"`, add `onnxruntime = "1.30.0"`; under `[libraries]` after the `kotlinx-serialization-json` line, add:
```toml
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
```
In `Mobile/engine/build.gradle`, directly above `kotlin {`, add:
```groovy
// The models live once in git, in the repository's models/ folder; the engine's assets get a copy
// of the two it runs, made at build time.
def modelAssets = layout.buildDirectory.dir('generated/modelAssets').get().asFile
def copyModels = tasks.register('copyModels', Copy) {
    from(rootProject.file('../models')) {
        include 'chordnet-chroma/3.0.0/**', 'progression-ngram/1.0.0/**'
    }
    into new File(modelAssets, 'models')
}
android.sourceSets.main.assets.srcDir(modelAssets)
tasks.named('preBuild') { dependsOn copyModels }
```
and under `dependencies`, after `implementation libs.kotlinx.serialization.json`, add `implementation libs.onnxruntime.android`. ONNX Runtime is MIT-licensed; its AAR packs next to `libchordify_engine.so` without conflict (checked while writing this plan).

- [ ] **Step 6: Write the failing device test**

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/acoustic/ChordModelDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.acoustic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.features.normaliseBothchroma
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordModelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loopFeatures = Goldens.loadGz("features").objects("inputs")
        .single { it.str("file") == "loop.wav" }.getValue("raw").floatMatrix()

    private fun tiled(tiles: Int) = Array(loopFeatures.size * tiles) { loopFeatures[it % loopFeatures.size] }

    // spec §7.1: model outputs (logits) within 1e-3 of Python's, for one window and for several
    @Test
    fun logitsMatchPython() {
        ChordModel.fromAssets(context).use { model ->
            for (case in Goldens.loadGz("acoustic").objects("cases")) {
                val name = case.str("case")!!
                val logits = model.logits(normaliseBothchroma(tiled(case.int("tiles")!!)))
                val expected = case.obj("logits")
                for ((head, rows) in logits) {
                    val want = if (head == "boundary") expected.getValue(head).floats().map { floatArrayOf(it) }.toTypedArray()
                    else expected.getValue(head).floatMatrix()
                    assertMatrixClose("$name $head", want, rows, 1e-3)
                }
            }
        }
    }

    @Test
    fun predictGivesProbabilities() {
        ChordModel.fromAssets(context).use { model ->
            val output = model.predict(loopFeatures)
            assertEquals(loopFeatures.size, output.chord.size)
            assertEquals(169, output.chord[0].size)
            for (row in output.chord + output.keyTonic + output.keyMode + output.bass) assertEquals(1.0, row.sum().toDouble(), 1e-4)
            for (p in output.boundary) assertEquals(true, p in 0f..1f)
        }
    }

    @Test
    fun aDamagedModelIsRefused() {
        val bundle = ChordBundle.parse(context.assets.open("${ChordModel.BUNDLE}/bundle.json").use { it.readBytes().decodeToString() })
        val bytes = context.assets.open("${ChordModel.BUNDLE}/${bundle.modelFile}").use { it.readBytes() }
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        val error = assertThrows(EngineException::class.java) { ChordModel(bundle, bytes) }
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, error.code)
    }
}
```
Run `cd Mobile && ./gradlew :engine:compileDebugAndroidTestKotlin` and expect `Unresolved reference 'ChordModel'`.

- [ ] **Step 7: Write the model**

Create `.../engine/acoustic/ChordModel.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.acoustic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.features.normaliseBothchroma
import java.nio.FloatBuffer

/** chordify_core.acoustic.AcousticOutput: per-frame probabilities from every head of the model. */
class AcousticOutput(
    val chord: Array<FloatArray>,
    val boundary: FloatArray,
    val keyTonic: Array<FloatArray>,
    val keyMode: Array<FloatArray>,
    val root: Array<FloatArray>,
    val bass: Array<FloatArray>,
)

/** An exported ChordNet bundle run with ONNX Runtime on the CPU, as
 *  chordify_core.acoustic.OnnxChordModel runs it on the server. */
class ChordModel internal constructor(val bundle: ChordBundle, model: ByteArray) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        if (sha256(model) != bundle.sha256) {
            throw EngineException(ErrorCode.MODEL_UNAVAILABLE,
                "The chord model ${bundle.id} is damaged (its SHA-256 does not match bundle.json); reinstall the app.")
        }
        session = environment.createSession(model, OrtSession.SessionOptions())
    }

    /** Raw logits of every head for model input [input] (normalised features, one row per frame). */
    fun logits(input: Array<FloatArray>): Map<String, Array<FloatArray>> =
        windowed(input.size, bundle.window, bundle.margin) { start, length -> run(input, start, length) }

    /** OnnxChordModel.predict: raw NNLS features in, probabilities out. */
    fun predict(raw: Array<FloatArray>): AcousticOutput {
        val logits = logits(normaliseBothchroma(raw))
        return AcousticOutput(
            chord = softmaxRows(logits.getValue("chord")),
            boundary = sigmoid(FloatArray(raw.size) { logits.getValue("boundary")[it][0] }),
            keyTonic = softmaxRows(logits.getValue("key_tonic")),
            keyMode = softmaxRows(logits.getValue("key_mode")),
            root = softmaxRows(logits.getValue("root")),
            bass = softmaxRows(logits.getValue("bass")),
        )
    }

    private fun run(input: Array<FloatArray>, start: Int, length: Int): Map<String, Array<FloatArray>> {
        val width = input[0].size
        val flat = FloatArray(length * width)
        for (t in 0 until length) input[start + t].copyInto(flat, t * width)
        val shape = longArrayOf(1, length.toLong(), width.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), shape).use { tensor ->
            session.run(mapOf(INPUT to tensor)).use { result ->
                bundle.outputs.associateWith { name ->
                    val value = result.get(name).get() as OnnxTensor
                    val dims = value.info.shape
                    val size = if (dims.size == 3) dims[2].toInt() else 1 // boundary is (1, time)
                    val buffer = value.floatBuffer
                    Array(length) { t -> FloatArray(size) { buffer.get(t * size + it) } }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val INPUT = "features"
        const val BUNDLE = "models/chordnet-chroma/3.0.0"

        /** The bundle the app ships (copied from the repository's models/ into the engine's assets). */
        fun fromAssets(context: Context, directory: String = BUNDLE): ChordModel {
            val bundle = ChordBundle.parse(context.assets.open("$directory/bundle.json").use { it.readBytes().decodeToString() })
            val model = context.assets.open("$directory/${bundle.modelFile}").use { it.readBytes() }
            return ChordModel(bundle, model)
        }
    }
}
```

- [ ] **Step 8: Run the device tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 23 tests passed (`ChordModelDeviceTest` 3). The logits must be within 1e-3 of Python's: the same ONNX Runtime release, on a different CPU and operating system.

- [ ] **Step 9: Commit**

```bash
git add Mobile/gradle/libs.versions.toml Mobile/engine
git commit -m "Run the chord model with ONNX Runtime in the engine" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Benchmark the front end and the model, and document them

**Files:**
- Modify: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeatureBenchmarkDeviceTest.kt` (replace)
- Modify: `Mobile/engine/README.md`, `docs/chord-progression-plan/08-implementation-status.md`

**Interfaces:**
- Consumes: `runFrontEnd` (Task 4), `ChordModel` (Task 5), `TestAudio` (M2).
- Produces: a `ChordifyBenchmark` log line with the front end's and the model's times for a 240 s song.

- [ ] **Step 1: Time the whole front end and the model**

Replace the whole of `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeatureBenchmarkDeviceTest.kt` with:
```kotlin
package com.arbaba.chordifymobile.engine.features

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.runFrontEnd
import com.arbaba.chordifymobile.engine.audio.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ceil

/** Times the analysis front end for a 4-minute song (pulse.wav 15 times over): one decoding pass
 *  into NNLS Chroma and the beat tracker, then the chord model. */
@RunWith(AndroidJUnit4::class)
class FeatureBenchmarkDeviceTest {
    @Test
    fun aFourMinuteSong() {
        val pulse = TestAudio.readWav(TestAudio.golden("pulse.wav")).mono
        val song = FloatArray(pulse.size * 15) { pulse[it % pulse.size] }
        val file = TestAudio.tempFile("benchmark.wav").also { TestAudio.writeWav(it, song, 44100) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        var started = SystemClock.elapsedRealtime()
        val front = runFrontEnd(AudioSource.FromFile(file))
        val frontSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        started = SystemClock.elapsedRealtime()
        val output = ChordModel.fromAssets(context).use { it.predict(front.features) }
        val modelSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0

        Log.i("ChordifyBenchmark", String.format(Locale.ROOT,
            "%.0f s song: decode + NNLS + beats %.1f s, chord model %.1f s, on %s (%s)",
            song.size / 44100.0, frontSeconds, modelSeconds, Build.MODEL, Build.SUPPORTED_ABIS[0]))
        assertEquals(ceil(song.size / 2048.0).toInt(), front.features.size)
        assertEquals(front.features.size, output.chord.size)
        assertTrue(front.beats.usable)
    }
}
```
The song is now `pulse.wav` 15 times over, so the beat tracker has a steady pulse to follow.

- [ ] **Step 2: Run it**

Run:
```bash
cd Mobile
./gradlew :engine:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.engine.features.FeatureBenchmarkDeviceTest
grep -rh "ChordifyBenchmark" engine/build/outputs/androidTest-results/managedDevice
```
Expected: `BUILD SUCCESSFUL`, and a line like `240 s song: decode + NNLS + beats … s, chord model … s, on sdk_gphone64_x86_64 (x86_64)`. While this plan was written and run, four emulator runs gave 39.5–55.7 s and 9.6–25.6 s. If a phone is connected, also run `./gradlew :engine:connectedDebugAndroidTest` and `adb logcat -d -s ChordifyBenchmark` for its line.

- [ ] **Step 3: Record the numbers**

In `docs/chord-progression-plan/08-implementation-status.md`, §3.6, below M2's table and the paragraph after it (the one that ends `so it is to be profiled on a phone before being optimised.`), add:
```markdown

With beat tracking and the chord model (milestone M3a), for a 240 s song, from the same test:

| Device | ABI | Decode + NNLS + beats | Chord model |
|---|---|---|---|
| Emulator (API 34, Google APIs, 2 cores) on an Intel Core i7-9750H laptop | x86_64 | <Step 2's first number> s | <Step 2's second number> s |
| <phone model, or "No phone measured yet"> | arm64-v8a | | |
```
Fill the angle-bracketed cells from Step 2's output; with several runs, give the range and the number of runs, as M2's table does. Then add the line `The emulator's timings vary a lot between runs, and it runs NNLS about ten times slower than the server, so these numbers say little about a phone.` below the table.

- [ ] **Step 4: Document beats and the model in the README**

In `Mobile/engine/README.md`, at the end of the first section (after the paragraph that ends `streams it, mono at 44.1 kHz, into \`features/\`.`), add:
```markdown

`beats/` ports librosa's onset strength and beat tracker (librosa 1.0.1, as the server pins it),
and `acoustic/` runs the shipped ChordNet with ONNX Runtime. `analysis.runFrontEnd` decodes a file
once into both NNLS Chroma and the beat tracker. The model files are not in `src/`: Gradle copies
`chordnet-chroma/3.0.0` and `progression-ngram/1.0.0` from the repository's `models/` into the
assets at build time, so git holds one copy of each.
```
and in the `## Tests` section, replace the line
```
tools/mobile_goldens.sh                        # features.json.gz, in Docker, with NNLS Chroma
```
with
```
tools/mobile_goldens.sh                        # features, beats and acoustic, in Docker with the pinned reference
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
Expected: `5 passed, 2 skipped`; JVM 60 tests; device 23 tests; the old app still builds.

- [ ] **Step 6: Commit**

```bash
git add Mobile/engine docs/chord-progression-plan/08-implementation-status.md
git commit -m "Benchmark beat tracking and the chord model on the device" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Done when

- `./gradlew :engine:testDebugUnitTest` (60 tests) and `./gradlew :engine:api34DebugAndroidTest` (23 tests) pass locally, and the CI jobs `python`, `android` and `android-device` pass.
- The beat frames equal librosa's for the same onset envelope, and on the device the beats are within one onset frame of the server's for all six inputs.
- The model's logits are within 1e-3 of Python's, for one window and for several.
- `runFrontEnd` gives the server's features and beats from one decoding pass.
- The benchmark numbers are in `docs/chord-progression-plan/08-implementation-status.md`.

Next: write the M3b plan: the two-pass `analysis` (first decode, `NO_MUSIC_DETECTED`, key, second decode with the n-gram change matrix, inversions, merging, chord entries with theory and predictions, downbeats, bars, time share, patterns), the `AnalysisResult` document and its JSON, progress stages, and the public API (`analyse`, `nextChords`, `quickChord`). Its golden file is a whole `AnalysisResult` per input from `chordify_backend.app.pipeline.analysis.analyse`, which needs NNLS Chroma, so it is written in the container.
