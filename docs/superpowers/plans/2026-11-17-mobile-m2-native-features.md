# Mobile M2: native features (NNLS Chroma, libsoxr, MediaCodec) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The engine decodes any audio file Android can play, resamples it to 44.1 kHz and computes NNLS bothchroma features in one streaming pass, and an x86_64 emulator shows the features match `chordify_core.features` on the server.

**Architecture:** A native library (`libchordify_engine.so`) compiles three unmodified upstream sources at the versions the Python reference runs: NNLS Chroma, the host side of the Vamp plugin SDK 2.6 that the Python `vamp` package bundles, and the libsoxr that the Python `soxr` package bundles. A small C++ host feeds NNLS Chroma exactly as `vamp.collect` does, and JNI exposes it and libsoxr to Kotlin. Kotlin decodes with `MediaExtractor`/`MediaCodec`, mixes to mono and streams chunks through the resampler into any number of sinks (NNLS now, the beat tracker in M3). Python writes the reference: the audio inputs and every stage's output, in a Docker container that builds NNLS Chroma as CI does.

**Tech Stack:** Android NDK 27.0.12077973, CMake 3.22.1, C++17, JNI; Vamp plugin SDK 2.6, NNLS Chroma (commit 4c5f214), libsoxr 0.1.3-14-ga66f3ee; `MediaExtractor`/`MediaCodec`; AndroidX Test 1.7 and Gradle Managed Devices (API 34, Google APIs, x86_64); Docker (`ubuntu:24.04`) for the Python reference; Kotlin 2.1.0 and AGP 8.8.0 as in M1.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md), milestone M2 (§9), pipeline steps 1–3 (§5), tests (§7).

**This plan is M2 of six.** M1 (`vocab`, `theory`, `lm`, `decode`) is merged. M3 (ONNX inference, beat tracking, the full `analysis`) gets its own plan when this one has landed.

## Global Constraints

- Everything from M1's plan still holds: module `:engine` in `Mobile/`, namespace `com.arbaba.chordifymobile.engine`, `compileSdk 35`, `minSdk 33`, JVM target 17, Python `ValueError` becomes `IllegalArgumentException`, golden files are generated and never edited by hand, commit messages are plain imperative sentences with the `Co-Authored-By` trailer.
- Gradle runs on JDK 17 or 21. On this machine use `export JAVA_HOME="/c/Program Files/Java/jdk-21"`: the default `java` is JDK 25, and Android Studio's bundled JDK is broken (its `lib/jvm.cfg` is missing).
- Native build: NDK `27.0.12077973`, CMake `3.22.1`, ABIs `arm64-v8a` and `x86_64`, `ANDROID_STL=c++_shared` (ONNX Runtime, in M3, also uses the shared STL).
- Third-party C and C++ code is unmodified upstream source, copied only by `tools/vendor_mobile_native.py`. Versions are pinned to what the Python reference runs: Python `vamp==1.1.0` (it bundles Vamp SDK 2.6), Python `soxr==1.1.0` (it bundles libsoxr 0.1.3-14-ga66f3ee), NNLS Chroma commit `4c5f214a75cb354f8d8c933e161377c5b4d83713`, and for the server's plugin build, vamp-plugin-sdk commit `44c2487763eb248a933e9eff9169cfadee375009`.
- Parity rules (spec §5 step 3): NNLS Chroma with block 16384, step 2048, output `bothchroma`, default parameters, wrapped in the SDK's `PluginInputDomainAdapter`; libsoxr at `SOXR_HQ`, float32, then the length fixed to `ceil(n × 44100 / rate)`, as `librosa.resample(fix=True)`.
- Tolerances (spec §7.1): decoded WAV samples identical; resampled samples within 1e-5; normalised features within 1e-4; MP3 and AAC timing within 30 ms of their WAV. Raw NNLS values are not bounded by 1, so for them the spec's 1e-4 is relative to the song's largest raw value: `1e-4 × max(1, max|raw|)`.
- Errors use the server's codes and limits: `UNSUPPORTED_MEDIA_TYPE`, `DECODE_FAILED`, `AUDIO_TOO_SHORT` (under 1 s), `AUDIO_TOO_LONG` (over 900 s); durations are measured at 44.1 kHz, as on the server.
- Licence: NNLS Chroma is GPL-2.0-or-later, libsoxr LGPL-2.1-or-later, the Vamp SDK MIT-style; the app is GPL-3.0-or-later. Add no dependency that is not GPL-3.0-compatible.

## Before you start

- **Disk space.** Docker Desktop keeps its disk image (`docker_data.vhdx`) on C:, and Gradle Managed Devices keep their emulator images under `%USERPROFILE%\.android`. On 2026-11-17, while this plan was written, C: had 0.3 GB free, and a Docker build failed when Docker's storage went read-only. Before Task 1, free at least 10 GB on C:, or move Docker's disk image to D: (Docker Desktop → Settings → Resources → Advanced → Disk image location). Before Task 3, give the emulator 8 GB: free space on C:, or point the Android user home at D: in the shell that runs Gradle (`export ANDROID_USER_HOME="D:/android-user"`).
- **Docker** must be running for Task 1 (`docker info` answers).
- **What was checked while writing this plan.** `tools/vendor_mobile_native.py` ran and vendored 188 files. The native code in Tasks 3 and 4 compiled for `x86_64` and `arm64-v8a` with NDK 27.0 and exports the eight JNI functions, and libsoxr found its SIMD path on both ABIs, as in python-soxr's x86-64 build. The generator's code without NNLS ran, and the updated golden-file test passes. Not run yet, because the disk was full: the Docker reference, the Gradle build and every device test.

## File structure

| Path | Responsibility |
|---|---|
| `tools/install_nnls_chroma.sh` | Builds NNLS Chroma for the server and CI; now pinned to fixed commits |
| `tools/mobile_goldens.Dockerfile` | The Python reference: chordify_core with NNLS Chroma, as CI sets it up |
| `tools/mobile_goldens.sh` | Builds that image and writes `features.json.gz` in it |
| `tools/vendor_mobile_native.py` | Copies the pinned third-party sources into the engine |
| `chordify_ai/export/mobile_goldens.py` | Gains the `features` golden file: audio inputs, decoded and resampled samples, raw and normalised features |
| `tests/ai/test_mobile_goldens.py` | Checks `features.json.gz` too, where NNLS Chroma is installed |
| `.github/workflows/ci.yml` | Pins `vamp` and `soxr`; adds the `android-device` job |
| `Mobile/engine/build.gradle`, `Mobile/gradle/libs.versions.toml` | Native build, shared test sources, instrumentation runner, managed emulator |
| `Mobile/engine/src/main/cpp/CMakeLists.txt` | Builds `libchordify_engine.so` |
| `.../cpp/nnls_host.{h,cpp}`, `nnls_factory.{h,cpp}` | The NNLS Chroma host |
| `.../cpp/nnls_jni.cpp`, `soxr_jni.cpp`, `jni_util.h` | JNI bridges |
| `.../cpp/third_party/` | Vendored Vamp SDK 2.6, libsoxr, NNLS Chroma, with their licences |
| `.../engine/NativeLibrary.kt` | Loads the native library once |
| `.../engine/Errors.kt` | `ErrorCode`, `EngineException`, `CancelledException` |
| `.../engine/audio/SampleSink.kt` | What receives the streamed audio |
| `.../engine/audio/Resampler.kt` | libsoxr with librosa's length rule |
| `.../engine/audio/AudioSource.kt` | A file or a content URI |
| `.../engine/audio/Pcm.kt` | Decoder PCM to mono float32; dropping encoder delay and padding |
| `.../engine/audio/AudioDecoder.kt` | `MediaExtractor` + `MediaCodec` |
| `.../engine/audio/AudioStream.kt` | `streamAudio`: decode, resample, limits, sinks |
| `.../engine/features/Features.kt` | `FeatureSpec`, `NNLS_BOTHCHROMA`, `nnlsToCOrder`, `normaliseBothchroma` |
| `.../engine/features/NnlsChroma.kt` | The NNLS Chroma sink |
| `.../engine/features/Extract.kt` | `extractNnlsBothchroma`: a file to raw features |
| `Mobile/engine/src/sharedTest/kotlin/.../GoldenJson.kt` | Golden-file helpers for the JVM and device tests (moved from `src/test`) |
| `Mobile/engine/src/androidTest/kotlin/.../` | `TestAudio.kt` and the device tests |
| `Mobile/engine/src/test/resources/goldens/audio/` | The audio inputs (WAV, MP3, M4A) |
| `Mobile/engine/src/test/resources/goldens/features.json.gz` | Generated |

`.../cpp` stands for `Mobile/engine/src/main/cpp`, and `.../engine` for `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine`; test paths follow the same pattern under `src/test`, `src/sharedTest` and `src/androidTest`.

All shell commands run from the repository root in Git Bash, with `JAVA_HOME` set as above, unless a step says otherwise.

---

### Task 1: The Python reference for audio, and the audio golden files

**Files:**
- Modify: `tools/install_nnls_chroma.sh`
- Modify: `.github/workflows/ci.yml` (job `python`)
- Create: `tools/mobile_goldens.Dockerfile`
- Create: `tools/mobile_goldens.sh`
- Modify: `chordify_ai/export/mobile_goldens.py`
- Modify: `tests/ai/test_mobile_goldens.py`
- Create (generated): `Mobile/engine/src/test/resources/goldens/audio/loop.wav`, `loop_padded.wav`, `loop_48k.wav`, `sevenths_stereo_22k.wav`, `loop.mp3`, `loop.m4a`
- Create (generated): `Mobile/engine/src/test/resources/goldens/features.json.gz`

**Interfaces:**
- Consumes: `chordify_core.audio.load_audio`, `to_mono`, `ffmpeg_binary`; `chordify_core.features.NNLS_BOTHCHROMA`, `NNLS_BLOCK`, `nnls_bothchroma`, `normalise_bothchroma`, `nnls_available`; `chordify_core.synth.render_progression`.
- Produces (Python, `chordify_ai.export.mobile_goldens`): `FILES` now includes `"features"`; `NEEDS_NNLS`, `GZIPPED: set[str]`; `golden_path(out, name) -> Path`; `read_golden(out, name) -> dict`; `AUDIO_DIR`; `envelope_offset_ms(reference, other, sr) -> float`; `render_audio()`; flag `--render-audio`.
- Produces (data, `features.json.gz`), read by Tasks 2–6:
  - `spec`: `kind`, `sample_rate`, `hop`, `n_bins`, `offset`, `revision`, `frame_rate`; `frame_times`: the first three frame times.
  - `inputs`: one object per WAV with `file`, `sample_rate`, `channels`, `frames` (at the file's own rate), `decoded` (`start`, `values`: mono samples at the file's rate), `samples` (length at 44.1 kHz), `resampled` (`start`, `values`; only when the file is not at 44.1 kHz), `raw` (C-ordered bothchroma, T × 24), `normalised` (T × 25).
  - `encoded`: `[{"file": "loop.mp3", "reference": "loop.wav"}, {"file": "loop.m4a", "reference": "loop.wav"}]`.
  - `logfreqspec`: `file` (`loop.wav`) and `values` (T × 256), the plugin's first stage.
  - `environment`: library versions, recorded and never compared.

- [ ] **Step 1: Check Docker and the disk**

Run:
```bash
docker info --format '{{.ServerVersion}}'
df -h /c /d
```
Expected: a version number, and at least 10 GB free on the drive that holds Docker's disk image (see "Before you start"). Stop and free space first if not.

- [ ] **Step 2: Pin the NNLS Chroma build**

In `tools/install_nnls_chroma.sh`, replace these two lines:
```bash
git clone -q --depth 1 https://github.com/vamp-plugins/vamp-plugin-sdk.git "$WORK/vamp-plugin-sdk"
git clone -q --depth 1 https://github.com/c4dm/nnls-chroma.git "$WORK/nnls-chroma"
```
with:
```bash
# Pinned, so the server, CI, the golden-file container (tools/mobile_goldens.Dockerfile) and the
# Android engine (Mobile/engine/src/main/cpp/third_party) all compute the same features.
VAMP_SDK_COMMIT=44c2487763eb248a933e9eff9169cfadee375009
NNLS_CHROMA_COMMIT=4c5f214a75cb354f8d8c933e161377c5b4d83713
fetch() {  # fetch <repository> <commit> <directory>
  git init -q "$3" && git -C "$3" fetch -q --depth 1 "$1" "$2" && git -C "$3" checkout -q FETCH_HEAD
}
fetch https://github.com/vamp-plugins/vamp-plugin-sdk.git "$VAMP_SDK_COMMIT" "$WORK/vamp-plugin-sdk"
fetch https://github.com/c4dm/nnls-chroma.git "$NNLS_CHROMA_COMMIT" "$WORK/nnls-chroma"
```
These are the two repositories' current heads, so the plugin the server and CI build does not change.

- [ ] **Step 3: Pin the Python host and resampler in CI**

In `.github/workflows/ci.yml`, job `python`, step `Install dependencies`, replace
```yaml
          pip install -r chordify_backend/requirements.txt pytest httpx mir_eval
```
with
```yaml
          pip install -r chordify_backend/requirements.txt pytest httpx mir_eval soxr==1.1.0
```
and in the step `Build the NNLS Chroma plugin, install FluidSynth and ffmpeg`, replace
```yaml
          pip install --no-build-isolation vamp
```
with
```yaml
          pip install --no-build-isolation vamp==1.1.0
```
The engine compiles the Vamp SDK and libsoxr that exactly these releases bundle.

- [ ] **Step 4: Create the reference container**

Create `tools/mobile_goldens.Dockerfile`:
```dockerfile
# The Python reference for the Android engine's audio golden files: chordify_core with the
# NNLS Chroma Vamp plugin, set up as the CI job `python` sets it up (.github/workflows/ci.yml).
# Built and run by tools/mobile_goldens.sh.
FROM ubuntu:24.04

RUN apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends \
        build-essential ca-certificates ffmpeg git libboost-dev libsndfile1 python3 python3-dev python3-venv \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /venv
ENV PATH=/venv/bin:$PATH VAMP_PATH=/opt/vamp

# soxr and vamp are pinned: the engine compiles the libsoxr and Vamp SDK sources these
# releases bundle (Mobile/engine/src/main/cpp/third_party/README.md).
RUN pip install -q --upgrade pip setuptools wheel numpy \
    && pip install -q scipy librosa soundfile soxr==1.1.0 pytest \
    && pip install -q --no-build-isolation vamp==1.1.0

COPY install_nnls_chroma.sh /tmp/install_nnls_chroma.sh
RUN sed -i 's/\r$//' /tmp/install_nnls_chroma.sh \
    && PREFIX=/opt/vamp bash /tmp/install_nnls_chroma.sh \
    && python -c "import vamp; assert 'nnls-chroma:nnls-chroma' in vamp.list_plugins()"
```
`ubuntu:24.04` has the same gcc (13) as CI's `ubuntu-latest`, which matters because NNLS Chroma is built with `-ffast-math`. The `sed` strips Windows line endings from the script when the checkout has them.

Create `tools/mobile_goldens.sh`:
```bash
#!/usr/bin/env bash
# Regenerate the Android engine's features.json.gz in a Linux container that runs NNLS Chroma
# exactly as CI does (tools/mobile_goldens.Dockerfile), then check every golden file. Needs Docker.
#
#   tools/mobile_goldens.sh                  # features.json.gz from the committed audio inputs
#   tools/mobile_goldens.sh --render-audio   # re-render the audio inputs too (rarely needed)
set -euo pipefail
cd "$(dirname "$0")/.."
docker build -q -t chordify-mobile-goldens -f tools/mobile_goldens.Dockerfile tools >/dev/null
repo="$(pwd)"
if [ -n "${MSYSTEM:-}" ]; then  # Git Bash on Windows: hand Docker a Windows path, unconverted
  repo="$(pwd -W)"
  export MSYS_NO_PATHCONV=1
fi
docker run --rm -v "$repo:/repo" -w /repo chordify-mobile-goldens sh -c \
  "python -m chordify_ai.export.mobile_goldens --only features $* && python -m pytest -q -p no:cacheprovider tests/ai/test_mobile_goldens.py"
```
Step 11 marks it executable in git.

- [ ] **Step 5: Update the generator's docstring and imports**

In `chordify_ai/export/mobile_goldens.py`, replace everything from the opening `"""` of the module docstring down to and including the line `from pathlib import Path` with:
```python
"""Golden files for the Android engine, the Kotlin port of chordify_core.

    python -m chordify_ai.export.mobile_goldens                 # every file
    python -m chordify_ai.export.mobile_goldens --only vocab    # one file
    tools/mobile_goldens.sh                                     # features.json.gz (needs Docker)

Each file holds inputs and what chordify_core returns for them. The engine's tests
(Mobile/engine/src/test and src/androidTest) replay the inputs through the Kotlin port and
compare, and tests/ai/test_mobile_goldens.py fails when chordify_core changes and the files
are stale. Inputs are deterministic and never come from numpy's random generators, whose
streams may change between numpy releases. The one exception, the rendered audio in
goldens/audio, is committed and only re-rendered on purpose (--render-audio).

features.json.gz needs the NNLS Chroma Vamp plugin, which tools/mobile_goldens.sh provides in
a Linux container. Where the plugin is not installed, that file is skipped.
"""
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path
```

- [ ] **Step 6: Add the `features` section and the new `main`**

In the same file, replace everything from the line
```python
FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden}
```
to the end of the file with:
```python
# ---------------------------------------------------------------------------
# features: decoding, resampling and NNLS Chroma (needs the Vamp plugin)
# ---------------------------------------------------------------------------

AUDIO_DIR = DEFAULT_OUT / "audio"
LOOP = [("C:maj", 2.0), ("G:maj", 2.0), ("A:min", 2.0), ("F:maj", 2.0)]
SEVENTHS = [("C:maj7", 2.0), ("A:min7", 2.0), ("D:min7", 2.0), ("G:7", 2.0)]
# 16-bit WAV inputs, which every decoder reads exactly. Each one exercises one more step:
# plain 44.1 kHz; digital silence at both ends; resampling down; stereo and resampling up.
WAV_INPUTS = ["loop.wav", "loop_padded.wav", "loop_48k.wav", "sevenths_stereo_22k.wav"]
# Lossy copies of loop.wav. Decoders differ, so only their timing is compared (spec §7.1).
ENCODED_INPUTS = {"loop.mp3": ["-codec:a", "libmp3lame", "-b:a", "128k"],
                  "loop.m4a": ["-codec:a", "aac", "-b:a", "128k"]}
SLICE_START_S = 1.0
DECODED_SLICE = 1024
RESAMPLED_SLICE = 2048


def envelope_offset_ms(reference: np.ndarray, other: np.ndarray, sr: int) -> float:
    """How late ``other`` is against ``reference``, in ms: the lag (within ±200 ms) that best
    lines up their 5 ms RMS envelopes. The engine's EncodedAudioDeviceTest computes the same."""
    hop = int(round(sr * 0.005))

    def envelope(y: np.ndarray) -> np.ndarray:
        n = len(y) // hop
        e = np.sqrt((y[:n * hop].astype(np.float64).reshape(n, hop) ** 2).mean(axis=1))
        return e - e.mean()

    a, b = envelope(reference), envelope(other)
    best, best_score = 0, -np.inf
    for lag in range(-40, 41):
        x, y = (a, b[lag:]) if lag >= 0 else (a[-lag:], b)
        m = min(len(x), len(y))
        score = float(np.dot(x[:m], y[:m]))
        if score > best_score:
            best, best_score = lag, score
    return best * hop / sr * 1000


def render_audio(audio_dir: Path = AUDIO_DIR) -> None:
    """Write the audio inputs. They are committed and only re-rendered on purpose
    (--render-audio): the synthesiser's noise comes from numpy's random generator."""
    import subprocess

    import soundfile as sf

    from chordify_core import audio, synth

    audio_dir.mkdir(parents=True, exist_ok=True)
    loop, _ = synth.render_progression(LOOP, sr=44100)
    sf.write(audio_dir / "loop.wav", loop, 44100, subtype="PCM_16")
    silence = np.zeros(int(1.5 * 44100), dtype=np.float32)
    sf.write(audio_dir / "loop_padded.wav", np.concatenate([silence, loop, silence]), 44100, subtype="PCM_16")
    loop48, _ = synth.render_progression(LOOP, sr=48000)
    sf.write(audio_dir / "loop_48k.wav", loop48, 48000, subtype="PCM_16")
    left, _ = synth.render_progression(SEVENTHS, sr=22050, seed=1)
    right, _ = synth.render_progression(SEVENTHS, sr=22050, seed=2)
    sf.write(audio_dir / "sevenths_stereo_22k.wav", np.stack([left, 0.6 * right], axis=1), 22050, subtype="PCM_16")
    for name, codec in ENCODED_INPUTS.items():
        subprocess.run([audio.ffmpeg_binary(), "-nostdin", "-loglevel", "error", "-y", "-i",
                        str(audio_dir / "loop.wav"), *codec, "-map_metadata", "-1", "-fflags", "+bitexact",
                        "-flags:a", "+bitexact", str(audio_dir / name)], check=True)


def _f32(values) -> list[float]:
    """float32 values as the shortest decimals that read back as the same float32."""
    return [float(f"{v:.9g}") for v in np.asarray(values, dtype=np.float32).ravel().tolist()]


def _f32_rows(matrix) -> list[list[float]]:
    return [_f32(row) for row in np.asarray(matrix, dtype=np.float32)]


def _environment() -> dict:
    """Versions that shape the features; recorded, not compared."""
    from importlib import metadata

    import soundfile as sf

    versions = {name: metadata.version(name) for name in ("numpy", "librosa", "soxr", "soundfile", "vamp")}
    return versions | {"libsndfile": sf.__libsndfile_version__}


def features_golden() -> dict:
    import soundfile as sf
    import vamp

    from chordify_core import audio, features

    if not features.nnls_available():
        raise SystemExit("features.json.gz needs the NNLS Chroma Vamp plugin: run tools/mobile_goldens.sh")
    spec = features.NNLS_BOTHCHROMA
    inputs = []
    for name in WAV_INPUTS:
        path = AUDIO_DIR / name
        native, sr = sf.read(str(path), dtype="float32", always_2d=False)
        mono = audio.to_mono(native)
        y44 = audio.load_audio(path.read_bytes(), spec.sample_rate, filename=name)  # the server's decode
        raw = features.nnls_bothchroma(y44, spec.sample_rate)
        start = int(SLICE_START_S * sr)
        row = {"file": name, "sample_rate": sr, "channels": 1 if native.ndim == 1 else native.shape[1],
               "frames": len(native), "decoded": {"start": start, "values": _f32(mono[start:start + DECODED_SLICE])},
               "samples": len(y44), "raw": _f32_rows(raw), "normalised": _f32_rows(features.normalise_bothchroma(raw))}
        if sr != spec.sample_rate:
            start = int(SLICE_START_S * spec.sample_rate)
            row["resampled"] = {"start": start, "values": _f32(y44[start:start + RESAMPLED_SLICE])}
        inputs.append(row)

    loop = audio.load_audio((AUDIO_DIR / "loop.wav").read_bytes(), spec.sample_rate, filename="loop.wav")
    for name in ENCODED_INPUTS:  # the encoded copies must line up with the WAV, as the device test assumes
        decoded = audio.load_audio((AUDIO_DIR / name).read_bytes(), spec.sample_rate, filename=name)
        offset = envelope_offset_ms(loop, decoded, spec.sample_rate)
        assert abs(offset) <= 5, f"Python decodes {name} {offset} ms off loop.wav"
    logfreqspec = vamp.collect(loop, spec.sample_rate, "nnls-chroma:nnls-chroma", output="logfreqspec",
                               block_size=features.NNLS_BLOCK, step_size=spec.hop)["matrix"][1]
    return {
        "environment": _environment(),
        "spec": spec.to_dict(),
        "frame_times": spec.frame_times(3).tolist(),
        "inputs": inputs,
        "encoded": [{"file": name, "reference": "loop.wav"} for name in ENCODED_INPUTS],
        "logfreqspec": {"file": "loop.wav", "values": _f32_rows(logfreqspec)},
    }


FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden,
         "features": features_golden}
NEEDS_NNLS = {"features"}  # skipped by default where the NNLS Chroma plugin is not installed
GZIPPED = {"features"}  # large, so stored compressed


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
                        help=f"re-render the committed audio inputs in {AUDIO_DIR} first")
    args = parser.parse_args(argv)
    if args.render_audio:
        render_audio()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    names = args.only
    if names is None:
        from chordify_core.features import nnls_available

        names = [n for n in FILES if n not in NEEDS_NNLS or nnls_available()]
        for skipped in FILES.keys() - set(names):
            print(f"{golden_path(out, skipped).name}: skipped, needs NNLS Chroma (tools/mobile_goldens.sh)")
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
`gzip.compress(..., mtime=0)` keeps the file identical between runs. The `.9g` format writes every float32 exactly: nine significant digits always read back as the same float32.

- [ ] **Step 7: Check the features file in the golden-file test**

Replace the whole of `tests/ai/test_mobile_goldens.py` with:
```python
"""The Android engine's golden files (Mobile/engine/src/test/resources/goldens) match what
chordify_core does now. When this fails, regenerate them and commit the result:

    python -m chordify_ai.export.mobile_goldens
    tools/mobile_goldens.sh        # features.json.gz, which needs the NNLS Chroma plugin
"""
import pytest

from chordify_ai.export import mobile_goldens
from chordify_core import features

# (rel, abs) per file. NNLS Chroma is built with -ffast-math, so two compilers or two numpy
# builds agree on its features only to about 1e-6; everything else is exact arithmetic.
TOLERANCE = {"features": (1e-4, 1e-5)}


def _same(fresh, committed, rel, abs_, path="$"):
    """Equal, except that floats may differ in their last bits between builds."""
    if isinstance(fresh, float) or isinstance(committed, float):
        assert fresh == pytest.approx(committed, rel=rel, abs=abs_), path
    elif isinstance(fresh, dict):
        assert isinstance(committed, dict) and fresh.keys() == committed.keys(), path
        for key in fresh:
            _same(fresh[key], committed[key], rel, abs_, f"{path}.{key}")
    elif isinstance(fresh, list):
        assert isinstance(committed, list) and len(fresh) == len(committed), path
        for i, (a, b) in enumerate(zip(fresh, committed)):
            _same(a, b, rel, abs_, f"{path}[{i}]")
    else:
        assert fresh == committed, path


@pytest.mark.parametrize("name", sorted(mobile_goldens.FILES))
def test_committed_goldens_match_chordify_core(tmp_path, name):
    if name in mobile_goldens.NEEDS_NNLS and not features.nnls_available():
        pytest.skip("needs the NNLS Chroma Vamp plugin (tools/install_nnls_chroma.sh)")
    mobile_goldens.main(["--out", str(tmp_path), "--only", name])
    fresh = mobile_goldens.read_golden(tmp_path, name)
    committed = mobile_goldens.read_golden(mobile_goldens.DEFAULT_OUT, name)
    fresh.pop("environment", None)  # library versions are recorded, not compared
    committed.pop("environment", None)
    _same(fresh, committed, *TOLERANCE.get(name, (1e-9, 1e-12)))
```

- [ ] **Step 8: Run the test here, without NNLS Chroma**

Run: `python -m pytest tests/ai/test_mobile_goldens.py -q`
Expected: `4 passed, 1 skipped` (the four M1 files pass; `features` is skipped because this machine has no Vamp plugin). Then run `python -m chordify_ai.export.mobile_goldens --out "$TEMP/goldens-check"` and expect the line `features.json.gz: skipped, needs NNLS Chroma (tools/mobile_goldens.sh)` before the four M1 files.

- [ ] **Step 9: Render the audio and write `features.json.gz` in the container**

Run: `bash tools/mobile_goldens.sh --render-audio`
Expected: the first run builds the image (several minutes: it compiles NNLS Chroma and the Python `vamp` host). It ends with a line `features.json.gz: … KB` (a few hundred KB) and then pytest's `5 passed`. The pytest run is in the container, so `features` is checked there against a fresh run of chordify_core with the plugin.

If `docker build` fails with `read-only file system`, Docker's disk image is out of space: see "Before you start".

- [ ] **Step 10: Check what was written**

Run:
```bash
ls -la Mobile/engine/src/test/resources/goldens/audio
python - <<'EOF'
from chordify_ai.export import mobile_goldens as g
data = g.read_golden(g.DEFAULT_OUT, "features")
for row in data["inputs"]:
    print(row["file"], row["sample_rate"], row["channels"], row["frames"], row["samples"], len(row["raw"]))
print(data["environment"])
EOF
```
Expected: six files (`loop.wav` about 690 KB, `loop_padded.wav` about 950 KB, `loop_48k.wav` about 750 KB, `sevenths_stereo_22k.wav` about 690 KB, `loop.mp3` and `loop.m4a` about 130 KB each) and these rows:
```
loop.wav 44100 1 352800 352800 173
loop_padded.wav 44100 1 485100 485100 237
loop_48k.wav 48000 1 384000 352800 173
sevenths_stereo_22k.wav 22050 2 176400 352800 173
```
(173 frames = ceil(352800 / 2048).) The `environment` line shows `soxr` and `vamp` at 1.1.0.

- [ ] **Step 11: Commit**

```bash
git add tools/install_nnls_chroma.sh tools/mobile_goldens.Dockerfile tools/mobile_goldens.sh .github/workflows/ci.yml \
  chordify_ai/export/mobile_goldens.py tests/ai/test_mobile_goldens.py Mobile/engine/src/test/resources/goldens
git update-index --chmod=+x tools/mobile_goldens.sh
git commit -m "Write audio golden files for the Android engine with a pinned NNLS Chroma reference" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 2: The feature spec, NNLS bin order and normalisation in Kotlin

**Files:**
- Move: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/GoldenJson.kt` → `Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/GoldenJson.kt`, then rewrite it
- Modify: `Mobile/engine/build.gradle`
- Create: `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/features/Features.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/features/FeaturesGoldenTest.kt`

**Interfaces:**
- Consumes: `features.json.gz` (Task 1): `spec`, `frame_times`, `inputs[].raw`, `inputs[].normalised`.
- Produces (package `com.arbaba.chordifymobile.engine.features`):
  - `data class FeatureSpec(val kind: String, val sampleRate: Int, val hop: Int, val nBins: Int, val offset: Double = 0.0, val revision: Int = 1)` with `frameRate: Double` and `frameTimes(nFrames: Int): DoubleArray`
  - `const val NNLS_ORDER_OFFSET = 9`, `const val NNLS_BLOCK = 16384`, `val NNLS_BOTHCHROMA: FeatureSpec`
  - `fun nnlsToCOrder(bothchroma: Array<FloatArray>): Array<FloatArray>`
  - `fun normaliseBothchroma(features: Array<FloatArray>, eps: Float = 1e-6f): Array<FloatArray>`
- Produces (package `com.arbaba.chordifymobile.engine`, source set `sharedTest`, used by JVM and device tests): everything M1's `GoldenJson.kt` had, plus `Goldens.loadGz(name: String): JsonObject`, `JsonElement.floats(): FloatArray`, `JsonElement.floatMatrix(): Array<FloatArray>`, `assertMatrixClose(message: String, expected: Array<FloatArray>, actual: Array<FloatArray>, absolute: Double)`, `assertFeaturesClose(message: String, expected: Array<FloatArray>, actual: Array<FloatArray>)`.

- [ ] **Step 1: Share the golden-file helpers between the JVM and device tests**

Run:
```bash
mkdir -p Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine
git mv Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/GoldenJson.kt \
  Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/GoldenJson.kt
```
In `Mobile/engine/build.gradle`, inside `android { ... }`, after the `compileOptions { ... }` block, add:
```groovy
    sourceSets {
        // Golden-file helpers serve both test kinds; the device tests read the same golden files.
        test {
            java.srcDirs += 'src/sharedTest/kotlin'
        }
    }
```

- [ ] **Step 2: Add the helpers the feature tests need**

Replace the whole of `Mobile/engine/src/sharedTest/kotlin/com/arbaba/chordifymobile/engine/GoldenJson.kt` with:
```kotlin
package com.arbaba.chordifymobile.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

// Reading the golden files written by chordify_ai/export/mobile_goldens.py. Shared by the JVM
// tests and the device tests; both find the files on the class path under /goldens.

object Goldens {
    fun load(name: String): JsonObject = parse(open("$name.json"))

    /** A gzip-compressed golden file, `<name>.json.gz`. */
    fun loadGz(name: String): JsonObject = parse(GZIPInputStream(open("$name.json.gz")))

    private fun open(file: String): InputStream =
        Goldens::class.java.getResourceAsStream("/goldens/$file")
            ?: error("goldens/$file is missing: run python -m chordify_ai.export.mobile_goldens (or tools/mobile_goldens.sh)")

    private fun parse(stream: InputStream): JsonObject =
        Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
}

fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }
fun JsonObject.has(name: String): Boolean = name in this
fun JsonObject.str(name: String): String? = getValue(name).stringOrNull()
fun JsonObject.int(name: String): Int? = getValue(name).let { if (it is JsonNull) null else it.jsonPrimitive.int }
fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean

fun JsonElement.stringOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
fun JsonElement.strings(): List<String?> = jsonArray.map { it.stringOrNull() }
fun JsonElement.ints(): List<Int?> = jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.int }
fun JsonElement.doubles(): DoubleArray = jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
fun JsonElement.matrix(): Array<DoubleArray> = jsonArray.map { it.doubles() }.toTypedArray()
fun JsonElement.floats(): FloatArray = jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
fun JsonElement.floatMatrix(): Array<FloatArray> = jsonArray.map { it.floats() }.toTypedArray()

/** Floats agree to a relative 1e-9: numpy and Kotlin add in different orders, so the last bits differ. */
fun assertClose(message: String, expected: Double, actual: Double, relative: Double = 1e-9) {
    assertEquals(message, expected, actual, relative * maxOf(1.0, abs(expected)))
}

/** Same shape, and every value within [absolute] of the expected one; a failure names the worst cell. */
fun assertMatrixClose(message: String, expected: Array<FloatArray>, actual: Array<FloatArray>, absolute: Double) {
    assertEquals("$message: frames", expected.size, actual.size)
    var worst = 0.0
    var where = "nowhere"
    for (t in expected.indices) {
        assertEquals("$message: values in frame $t", expected[t].size, actual[t].size)
        for (i in expected[t].indices) {
            val difference = abs(expected[t][i].toDouble() - actual[t][i])
            if (difference > worst) {
                worst = difference
                where = "frame $t, value $i: expected ${expected[t][i]}, got ${actual[t][i]}"
            }
        }
    }
    assertTrue("$message: largest difference $worst at $where (allowed $absolute)", worst <= absolute)
}

/** Raw features agree to 1e-4 of the song's largest raw value (spec §7.1). */
fun assertFeaturesClose(message: String, expected: Array<FloatArray>, actual: Array<FloatArray>) {
    val scale = maxOf(1.0, expected.maxOfOrNull { row -> row.maxOf { abs(it) }.toDouble() } ?: 0.0)
    assertMatrixClose(message, expected, actual, 1e-4 * scale)
}
```

- [ ] **Step 3: Check M1's tests still pass after the move**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, the 30 M1 tests passing (`PyTest` 5, `VocabGoldenTest` 7, `TheoryGoldenTest` 7, `LmGoldenTest` 5, `DecodeGoldenTest` 6).

- [ ] **Step 4: Write the failing test**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/features/FeaturesGoldenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturesGoldenTest {
    private val golden = Goldens.loadGz("features")

    @Test
    fun theNnlsSpecMatchesPython() {
        val spec = golden.obj("spec")
        assertEquals(spec.str("kind"), NNLS_BOTHCHROMA.kind)
        assertEquals(spec.int("sample_rate"), NNLS_BOTHCHROMA.sampleRate)
        assertEquals(spec.int("hop"), NNLS_BOTHCHROMA.hop)
        assertEquals(spec.int("n_bins"), NNLS_BOTHCHROMA.nBins)
        assertEquals(spec.int("revision"), NNLS_BOTHCHROMA.revision)
        assertEquals(spec.double("offset"), NNLS_BOTHCHROMA.offset, 0.0)
        assertEquals(spec.double("frame_rate"), NNLS_BOTHCHROMA.frameRate, 0.0)
        assertArrayEquals(golden.getValue("frame_times").doubles(), NNLS_BOTHCHROMA.frameTimes(3), 1e-12)
    }

    @Test
    fun nnlsBinsAreRolledToStartAtC() {
        // NNLS bin 0 is A, so C, the fourth bin, comes first in each half.
        val rolled = nnlsToCOrder(arrayOf(FloatArray(24) { it.toFloat() }))[0]
        val expected = (listOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1, 2) + listOf(15, 16, 17, 18, 19, 20, 21, 22, 23, 12, 13, 14))
            .map { it.toFloat() }.toFloatArray()
        assertArrayEquals(expected, rolled, 0f)
    }

    @Test
    fun normalisationMatchesPython() {
        val inputs = golden.objects("inputs")
        assertEquals(4, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val normalised = normaliseBothchroma(input.getValue("raw").floatMatrix())
            assertMatrixClose(name, input.getValue("normalised").floatMatrix(), normalised, 1e-4)
        }
    }

    @Test
    fun silenceAndNoFramesAreHandledAsInPython() {
        assertEquals(0, normaliseBothchroma(emptyArray()).size)
        // All-zero frames: every chroma value 0, and an energy spread of 0 is not divided by.
        for (row in normaliseBothchroma(Array(3) { FloatArray(24) })) assertArrayEquals(FloatArray(25), row, 0f)
    }
}
```

- [ ] **Step 5: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.features.FeaturesGoldenTest'`
Expected: compilation fails with `Unresolved reference 'NNLS_BOTHCHROMA'` (and `nnlsToCOrder`, `normaliseBothchroma`).

- [ ] **Step 6: Port the spec, the bin order and the normalisation**

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/features/Features.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import kotlin.math.ln
import kotlin.math.sqrt

// Port of chordify_core/features.py: the NNLS bothchroma feature spec, its bin order and its
// normalisation. Chroma features are stored with pitch class 0 = C; the NNLS plugin starts at A.

/** chordify_core.features.FeatureSpec: what a model's input frames are. */
data class FeatureSpec(
    val kind: String,
    val sampleRate: Int,
    val hop: Int,
    val nBins: Int,
    val offset: Double = 0.0, // time (s) at the centre of frame 0
    val revision: Int = 1, // bumped whenever the extractor's output changes
) {
    val frameRate: Double get() = sampleRate.toDouble() / hop

    /** Centre time of every frame: `offset + i / frameRate`. */
    fun frameTimes(nFrames: Int): DoubleArray = DoubleArray(nFrames) { offset + it / frameRate }
}

/** NNLS bin 0 is A (pitch class 9). */
const val NNLS_ORDER_OFFSET = 9
const val NNLS_BLOCK = 16384

/** Billboard's features: NNLS Chroma at 44.1 kHz, step 2048 (46.4 ms), block 16384. The Vamp host
 *  stamps each frame at the centre of its block, so frame 0 is at 0.186 s. */
val NNLS_BOTHCHROMA = FeatureSpec("nnls_bothchroma", 44100, 2048, 24, offset = NNLS_BLOCK / 2 / 44100.0)

/** Roll each 12-bin half of A-based NNLS output so that bin 0 = C (`numpy.roll` by 9). */
fun nnlsToCOrder(bothchroma: Array<FloatArray>): Array<FloatArray> = Array(bothchroma.size) { t ->
    val row = bothchroma[t]
    require(row.size == 24) { "frame $t has ${row.size} values, expected 24" }
    FloatArray(24) { i -> row[i / 12 * 12 + Math.floorMod(i % 12 - NNLS_ORDER_OFFSET, 12)] }
}

/** (T, 24) raw bothchroma -> (T, 25): each 12-bin half divided by its own max, plus a per-song
 *  z-scored log-energy channel, clipped to ±5, so silence (N) stays distinguishable. */
fun normaliseBothchroma(features: Array<FloatArray>, eps: Float = 1e-6f): Array<FloatArray> {
    val energy = DoubleArray(features.size) { t -> ln(eps.toDouble() + features[t].sumOf { it.toDouble() }) }
    val mean = energy.average()
    val std = sqrt(energy.sumOf { (it - mean) * (it - mean) } / energy.size)
    val spread = if (std > 1e-6) std else 1.0
    return Array(features.size) { t ->
        val row = features[t]
        val bassMax = maxOf((0 until 12).maxOf { row[it] }, eps)
        val trebleMax = maxOf((12 until 24).maxOf { row[it] }, eps)
        FloatArray(25) { i ->
            when {
                i < 12 -> row[i] / bassMax
                i < 24 -> row[i] / trebleMax
                else -> ((energy[t] - mean) / spread).coerceIn(-5.0, 5.0).toFloat()
            }
        }
    }
}
```
numpy computes the energy channel in float32 and this port computes it in double. The difference is about 1e-7, well inside the 1e-4 tolerance. The chroma halves divide in float32, as numpy does.

- [ ] **Step 7: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `FeaturesGoldenTest` 4 passed, and the M1 tests as in Step 3 (34 in all).

- [ ] **Step 8: Commit**

```bash
git add Mobile/engine
git commit -m "Port the NNLS feature spec, bin order and normalisation to Kotlin" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The native NNLS Chroma host, checked on the emulator

**Files:**
- Create: `tools/vendor_mobile_native.py`
- Create (vendored by that script): `Mobile/engine/src/main/cpp/third_party/` (`README.md`, `vamp-plugin-sdk/`, `libsoxr/`, `nnls-chroma/`)
- Create: `Mobile/engine/src/main/cpp/CMakeLists.txt`, `nnls_factory.h`, `nnls_factory.cpp`, `nnls_host.h`, `nnls_host.cpp`, `jni_util.h`, `nnls_jni.cpp`
- Modify: `Mobile/engine/build.gradle` (replace), `Mobile/gradle/libs.versions.toml`
- Create: `.../engine/NativeLibrary.kt`, `.../engine/audio/SampleSink.kt`, `.../engine/features/NnlsChroma.kt`
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/TestAudio.kt`, `.../androidTest/kotlin/com/arbaba/chordifymobile/engine/features/NnlsChromaDeviceTest.kt`
- Modify: `.github/workflows/ci.yml` (new job `android-device`)

**Interfaces:**
- Consumes: `NNLS_BOTHCHROMA`, `nnlsToCOrder` (Task 2); `Goldens`, `assertFeaturesClose`, `floatMatrix` (Task 2); `features.json.gz` and `goldens/audio/*.wav` (Task 1).
- Produces:
  - Native: `libchordify_engine.so`; class `chordify::NnlsHost(float sampleRate, const std::string &output)` with `write(const float *, size_t)` and `finish() -> std::vector<std::vector<float>>`.
  - `internal object NativeLibrary { fun load() }` (package `com.arbaba.chordifymobile.engine`).
  - `fun interface SampleSink { fun write(samples: FloatArray, count: Int) }` (package `...engine.audio`).
  - `class NnlsChroma(sampleRate: Int = 44100, output: String = "bothchroma") : SampleSink, AutoCloseable` with `finish(): Array<FloatArray>` (one row per frame, A-first bin order) (package `...engine.features`).
  - Device-test helpers, `object TestAudio` (package `com.arbaba.chordifymobile.engine`, `androidTest`): `golden(name): File`, `tempFile(name): File`, `class Wav(sampleRate, channels, mono)`, `readWav(file): Wav`, `writeWav(file, mono, sampleRate)`, `feed(sink, samples, chunk)`, `concat(parts: List<FloatArray>): FloatArray`.
  - Gradle task `:engine:api34DebugAndroidTest` (Gradle-managed emulator: Pixel 6, API 34, Google APIs, x86_64).

- [ ] **Step 1: Make room for the emulator**

Gradle creates the emulator on first use, in the Android user home: about 8 GB. Run `df -h /c /d`. If C: has less than 8 GB free, run every Gradle command of this and the later tasks in a shell where `export ANDROID_USER_HOME="D:/android-user"` is set.

- [ ] **Step 2: Configure the native build, device tests and the managed emulator**

In `Mobile/gradle/libs.versions.toml`, under `[versions]`, after `espressoCore = "3.7.0"`, add:
```toml
testRunner = "1.7.0"
```
and under `[libraries]`, after the `espresso-core` line, add:
```toml
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "testRunner" }
```

Replace the whole of `Mobile/engine/build.gradle` with:
```groovy
// The on-device analysis engine: a Kotlin port of chordify_core plus a native library
// (NNLS Chroma, libsoxr; src/main/cpp). No UI, no network.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace 'com.arbaba.chordifymobile.engine'
    compileSdk 35
    ndkVersion '27.0.12077973'

    defaultConfig {
        minSdk 33
        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
        ndk {
            abiFilters 'arm64-v8a', 'x86_64'
        }
        externalNativeBuild {
            cmake {
                arguments '-DANDROID_STL=c++_shared'
            }
        }
    }
    externalNativeBuild {
        cmake {
            path 'src/main/cpp/CMakeLists.txt'
            version '3.22.1'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    sourceSets {
        // Golden-file helpers serve both test kinds; the device tests read the same golden files.
        test {
            java.srcDirs += 'src/sharedTest/kotlin'
        }
        androidTest {
            java.srcDirs += 'src/sharedTest/kotlin'
            resources.srcDirs += 'src/test/resources'
        }
    }
    testOptions {
        managedDevices {
            localDevices {
                create('api34') {
                    device = 'Pixel 6'
                    apiLevel = 34
                    systemImageSource = 'google'
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation libs.kotlinx.serialization.json
    testImplementation libs.junit
    androidTestImplementation libs.junit
    androidTestImplementation libs.ext.junit
    androidTestImplementation libs.androidx.test.runner
}
```
`resources.srcDirs += 'src/test/resources'` packs the golden files into the test APK, where `Goldens` finds them on the class path as it does on the JVM. `systemImageSource = 'google'` is the image this machine already has (`system-images/android-34/google_apis/x86_64`). Gradle puts the native build in `Mobile/engine/.cxx`, which `Mobile/.gitignore` already ignores.

- [ ] **Step 3: Write the device-test helpers**

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/TestAudio.kt`:
```kotlin
package com.arbaba.chordifymobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.audio.SampleSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Audio for the device tests: the golden inputs, and WAV files written on the spot. */
object TestAudio {
    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    /** goldens/audio/<name> from the test APK, copied to a file that MediaExtractor can open. */
    fun golden(name: String): File {
        val file = File(cacheDir, "goldens/$name")
        if (!file.exists()) {
            file.parentFile!!.mkdirs()
            val stream = TestAudio::class.java.getResourceAsStream("/goldens/audio/$name")
                ?: error("goldens/audio/$name is missing: run tools/mobile_goldens.sh --render-audio")
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    /** An empty place in the cache for a test to write [name]. */
    fun tempFile(name: String): File = File(cacheDir, name).also { it.delete() }

    class Wav(val sampleRate: Int, val channels: Int, val mono: FloatArray)

    /** Reads a 16-bit PCM WAV and mixes it to mono as libsndfile and chordify_core.audio.to_mono
     *  do: every sample / 32768, then the mean of the channels in float32. */
    fun readWav(file: File): Wav {
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(text(bytes, 0) == "RIFF" && text(bytes, 8) == "WAVE") { "$file is not a WAV file" }
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var start = -1
        var size = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val length = data.getInt(at + 4)
            when (text(bytes, at)) {
                "fmt " -> {
                    channels = data.getShort(at + 10).toInt()
                    sampleRate = data.getInt(at + 12)
                    bits = data.getShort(at + 22).toInt()
                }
                "data" -> {
                    start = at + 8
                    size = length
                }
            }
            at += 8 + length + (length and 1)
        }
        require(bits == 16 && start >= 0) { "$file is not 16-bit PCM" }
        val mono = FloatArray(size / (2 * channels)) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += data.getShort(start + 2 * (frame * channels + channel)) / 32768f
            sum / channels
        }
        return Wav(sampleRate, channels, mono)
    }

    /** Writes [mono] (−1 to 1) as a 16-bit mono WAV. */
    fun writeWav(file: File, mono: FloatArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(44 + 2 * mono.size).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + 2 * mono.size).put("WAVE".toByteArray(Charsets.US_ASCII))
        data.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(sampleRate).putInt(2 * sampleRate).putShort(2.toShort()).putShort(16.toShort())
        data.put("data".toByteArray(Charsets.US_ASCII)).putInt(2 * mono.size)
        for (value in mono) data.putShort((value * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        file.writeBytes(data.array())
    }

    /** Hands [samples] to [sink] in chunks of at most [chunk] values. */
    fun feed(sink: SampleSink, samples: FloatArray, chunk: Int) {
        var at = 0
        while (at < samples.size) {
            val count = minOf(chunk, samples.size - at)
            sink.write(samples.copyOfRange(at, at + count), count)
            at += count
        }
    }

    fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun text(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
```

- [ ] **Step 4: Write the failing device test**

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/NnlsChromaDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertFeaturesClose
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NnlsChromaDeviceTest {
    private val golden = Goldens.loadGz("features")

    private fun nnls(samples: FloatArray, chunk: Int, output: String = "bothchroma"): Array<FloatArray> =
        NnlsChroma(output = output).use { nnls ->
            TestAudio.feed(nnls, samples, chunk)
            nnls.finish()
        }

    @Test
    fun bothchromaMatchesPythonForTheInputsAt44k() {
        val inputs = golden.objects("inputs").filter { it.int("sample_rate") == NNLS_BOTHCHROMA.sampleRate }
        assertEquals(2, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val frames = nnls(TestAudio.readWav(TestAudio.golden(name)).mono, chunk = 4096)
            assertFeaturesClose(name, input.getValue("raw").floatMatrix(), nnlsToCOrder(frames))
        }
    }

    // The host's part alone (framing, Hann window, FFT) before NNLS Chroma's own maths: when the
    // features differ, this tells the host's mistakes from the plugin build's rounding.
    @Test
    fun logFrequencySpectrumMatchesPython() {
        val expected = golden.obj("logfreqspec")
        val samples = TestAudio.readWav(TestAudio.golden(expected.str("file")!!)).mono
        assertFeaturesClose("logfreqspec", expected.getValue("values").floatMatrix(), nnls(samples, 4096, "logfreqspec"))
    }

    @Test
    fun chunkSizeDoesNotChangeTheFeatures() {
        val samples = TestAudio.readWav(TestAudio.golden("loop.wav")).mono
        val whole = nnls(samples, chunk = samples.size)
        for (chunk in listOf(1000, 2048, 16385)) {
            assertTrue("chunks of $chunk", whole.contentDeepEquals(nnls(samples, chunk)))
        }
    }

    @Test
    fun noAudioGivesNoFrames() {
        assertEquals(0, nnls(FloatArray(0), chunk = 1).size)
    }

    @Test
    fun anUnknownOutputIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { NnlsChroma(output = "chords") }
    }
}
```

- [ ] **Step 5: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: the build fails compiling the device tests, with `Unresolved reference 'SampleSink'` and `Unresolved reference 'NnlsChroma'`. (Gradle may first say it is setting up the managed device; that is expected.)

- [ ] **Step 6: Vendor the third-party sources**

Create `tools/vendor_mobile_native.py`:
```python
"""Copy the C and C++ sources the Android engine compiles into
Mobile/engine/src/main/cpp/third_party, at exactly the versions the Python reference runs:

  * Vamp plugin SDK 2.6, as bundled in the Python `vamp` 1.1.0 package: the host side
    (PluginInputDomainAdapter, its Hann window and built-in FFT) that turns audio into the
    spectra NNLS Chroma reads;
  * libsoxr 0.1.3-14-ga66f3ee, as bundled in the Python `soxr` 1.1.0 package (librosa's
    default resampler);
  * NNLS Chroma at the commit tools/install_nnls_chroma.sh builds.

    python tools/vendor_mobile_native.py

Downloads are checked against their SHA-256 (sdists) or commit hash (git). No file is
changed, except that chromamethods_nnls.cpp is written as chromamethods.cpp without
Chordino's chord dictionary, the only part that needs Boost.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEST = REPO / "Mobile" / "engine" / "src" / "main" / "cpp" / "third_party"

VAMP_SDIST = ("https://files.pythonhosted.org/packages/66/65/a54958777fa667ef881ceec18dd0b423b2ba2c77543060b21aff0e1dbcb6/"
              "vamp-1.1.0.tar.gz", "d40e0916e958e2806d37920f903c2518cc46959a4f21e020737b6a48f6e7e82d")
SOXR_SDIST = ("https://files.pythonhosted.org/packages/ed/11/27cebce4a108f77afea7c80545115536b45e3f11ebfb914f638fdd9ba847/"
              "soxr-1.1.0.tar.gz", "9f228ae21c78fa9359ca98d8a5e8e91f30639e438e574133dace62c5b5309e44")
NNLS_GIT = ("https://github.com/c4dm/nnls-chroma.git", "4c5f214a75cb354f8d8c933e161377c5b4d83713")

VAMP_SDK_DIRS = ["vamp", "vamp-sdk", "vamp-hostsdk", "src/vamp-sdk", "src/vamp-hostsdk"]
NNLS_FILES = ["COPYING", "README", "CITATION", "NNLSBase.cpp", "NNLSBase.h", "NNLSChroma.cpp", "NNLSChroma.h",
              "chromamethods.cpp", "chromamethods.h", "nnls.c", "nnls.h"]

README = """# Third-party sources

Copied by `tools/vendor_mobile_native.py`; do not edit. Each directory is unmodified upstream
source at the version the Python reference (`chordify_core.features`, run by the server and by
`chordify_ai.export.mobile_goldens`) uses, so the engine computes the same features.

| Directory | Source | Version | Licence |
|---|---|---|---|
| `vamp-plugin-sdk/` | Vamp plugin SDK, from the Python `vamp` 1.1.0 sdist (its `vamp-plugin-sdk/`) | 2.6 | MIT-style, in each file's header |
| `libsoxr/` | libsoxr, from the Python `soxr` 1.1.0 sdist (its `libsoxr/`) | 0.1.3-14-ga66f3ee | LGPL-2.1-or-later (`libsoxr/COPYING.LGPL`, `libsoxr/LICENCE`) |
| `nnls-chroma/` | https://github.com/c4dm/nnls-chroma | commit 4c5f214 | GPL-2.0-or-later (`nnls-chroma/COPYING`) |

`nnls-chroma/chromamethods_nnls.cpp` is generated: `chromamethods.cpp` without
`chordDictionary`, which only Chordino uses and which is the only code that needs Boost.
NNLS Chroma is GPL-2.0-or-later, so the app that bundles it is distributed under the GPL
(the app is GPL-3.0-or-later).
"""


def _download(url: str, sha256: str) -> bytes:
    with urllib.request.urlopen(url) as response:
        data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
        raise SystemExit(f"{url}: SHA-256 is {digest}, expected {sha256}")
    return data


def _unpack(data: bytes, into: Path) -> Path:
    """Extract an sdist and return its top directory."""
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        tar.extractall(into, filter="data")
    (top,) = [p for p in into.iterdir() if p.is_dir()]
    return top


def _without_chord_dictionary(source: str) -> str:
    """chromamethods.cpp lines 1-28, 34 and 36-207: everything up to dictionaryMatrix, minus Boost."""
    lines = source.split("\n")
    expected = {28: "#include <boost/tokenizer.hpp>", 33: "using namespace std;", 34: "using namespace boost;",
                206: "}", 208: "static"}
    for index, text in expected.items():
        if lines[index].rstrip() != text:
            raise SystemExit(f"chromamethods.cpp line {index + 1} is {lines[index]!r}, expected {text!r}: "
                             "the upstream file changed, so the cut points need checking")
    kept = lines[:28] + [lines[33]] + lines[35:207]
    note = ("// Generated by tools/vendor_mobile_native.py from chromamethods.cpp (lines 1-28, 34, 36-207):\n"
            "// the file without chordDictionary, which only Chordino uses and which needs Boost.\n")
    return note + "\n".join(kept) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dest", default=str(DEST))
    dest = Path(parser.parse_args(argv).dest)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)

        vamp = _unpack(_download(*VAMP_SDIST), work / "vamp")
        target = dest / "vamp-plugin-sdk"
        shutil.rmtree(target, ignore_errors=True)
        for sub in VAMP_SDK_DIRS:
            shutil.copytree(vamp / "vamp-plugin-sdk" / sub, target / sub)

        soxr = _unpack(_download(*SOXR_SDIST), work / "soxr")
        shutil.rmtree(dest / "libsoxr", ignore_errors=True)
        shutil.copytree(soxr / "libsoxr", dest / "libsoxr")

        url, commit = NNLS_GIT
        clone = work / "nnls-chroma"
        subprocess.run(["git", "-c", "core.autocrlf=false", "clone", "-q", url, str(clone)], check=True)
        subprocess.run(["git", "-C", str(clone), "-c", "core.autocrlf=false", "checkout", "-q", commit], check=True)
        target = dest / "nnls-chroma"
        shutil.rmtree(target, ignore_errors=True)
        target.mkdir(parents=True)
        for name in NNLS_FILES:
            shutil.copy2(clone / name, target / name)
        source = (clone / "chromamethods.cpp").read_text(encoding="utf-8")
        (target / "chromamethods_nnls.cpp").write_text(_without_chord_dictionary(source), encoding="utf-8",
                                                       newline="\n")

    (dest / "README.md").write_text(README, encoding="utf-8", newline="\n")
    print(f"vendored into {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```
Run: `python tools/vendor_mobile_native.py`
Expected: `vendored into ...third_party`, then `find Mobile/engine/src/main/cpp/third_party -type f | wc -l` prints `188`, and `grep -c boost Mobile/engine/src/main/cpp/third_party/nnls-chroma/chromamethods_nnls.cpp` prints `0`. libsoxr is vendored now and compiled from Task 4.

- [ ] **Step 7: Write the NNLS Chroma host and its JNI bridge**

Create `Mobile/engine/src/main/cpp/nnls_factory.h`:
```cpp
// Creates the NNLS Chroma plugin. Kept in its own file because plugin headers (vamp-sdk) and
// host headers (vamp-hostsdk) must not meet in one source file; the plugin is compiled into
// the host's namespace (_VAMP_PLUGIN_IN_HOST_NAMESPACE), so the host can wrap it directly.
#pragma once

namespace _VampHost {
namespace Vamp {
class Plugin;
}
}

namespace chordify {

_VampHost::Vamp::Plugin *createNnlsChroma(float sampleRate);

}
```

Create `Mobile/engine/src/main/cpp/nnls_factory.cpp`:
```cpp
// Compiled with the NNLS Chroma sources and _VAMP_PLUGIN_IN_HOST_NAMESPACE (see CMakeLists.txt).
#include "nnls_factory.h"

#include "NNLSChroma.h"

namespace chordify {

_VampHost::Vamp::Plugin *createNnlsChroma(float sampleRate) {
    return new NNLSChroma(sampleRate);
}

}
```

Create `Mobile/engine/src/main/cpp/nnls_host.h`:
```cpp
// Runs NNLS Chroma the way the Python `vamp` host (vamp 1.1.0, vamp.collect) runs it, so the
// features match chordify_core.features.nnls_bothchroma:
//
//   * one channel, step 2048, block 16384, default parameters, wrapped in the Vamp SDK 2.6
//     PluginInputDomainAdapter (Hann window, FFT shift, built-in FFT; timestamps shifted by
//     half a block);
//   * frame k holds samples [2048k, 2048k + 16384), zero-padded at the end, and there is one
//     frame for every k with 2048k < n (vamp/frames.py, frames_from_array);
//   * frame k is stamped RealTime::frame2RealTime(2048k, sampleRate).
//
// Samples arrive in chunks of any size; the features of one output come out of finish().
#pragma once

#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace _VampHost {
namespace Vamp {
class Plugin;
}
}

namespace chordify {

class NnlsHost {
public:
    static constexpr int kBlock = 16384;
    static constexpr int kStep = 2048;

    // `output` is a plugin output identifier: "bothchroma" for the features, or another one
    // ("logfreqspec") when checking the host against Python step by step.
    NnlsHost(float sampleRate, const std::string &output);
    ~NnlsHost();

    void write(const float *samples, std::size_t count);

    // Processes the zero-padded last frames and returns one row per frame. Call once.
    std::vector<std::vector<float>> finish();

private:
    void process(const float *frame);

    std::unique_ptr<_VampHost::Vamp::Plugin> m_plugin;
    float m_sampleRate;
    int m_output = -1;
    long m_frameStart = 0;  // sample index of the next frame's first sample
    std::vector<float> m_pending;  // samples from m_frameStart on (m_head marks the start)
    std::size_t m_head = 0;
    bool m_finished = false;
    std::vector<std::vector<float>> m_rows;
};

}
```

Create `Mobile/engine/src/main/cpp/nnls_host.cpp`:
```cpp
#include "nnls_host.h"

#include <vamp-hostsdk/PluginInputDomainAdapter.h>
#include <vamp-hostsdk/RealTime.h>

#include <algorithm>
#include <stdexcept>

#include "nnls_factory.h"

namespace chordify {

using _VampHost::Vamp::Plugin;
using _VampHost::Vamp::RealTime;
using _VampHost::Vamp::HostExt::PluginInputDomainAdapter;

// Appends the values of every feature of `output` to `rows`.
static void collect(const Plugin::FeatureSet &features, int output, std::vector<std::vector<float>> &rows) {
    auto found = features.find(output);
    if (found == features.end()) return;
    for (const Plugin::Feature &feature : found->second) rows.push_back(feature.values);
}

NnlsHost::NnlsHost(float sampleRate, const std::string &output)
    : m_plugin(new PluginInputDomainAdapter(createNnlsChroma(sampleRate))), m_sampleRate(sampleRate) {
    // vamp.load.load_and_configure, then vamp.process.process_with_initialised_plugin's reset().
    if (!m_plugin->initialise(1, kStep, kBlock)) {
        throw std::runtime_error("NNLS Chroma did not accept 1 channel, step 2048, block 16384");
    }
    m_plugin->reset();
    Plugin::OutputList outputs = m_plugin->getOutputDescriptors();
    for (std::size_t i = 0; i < outputs.size(); ++i) {
        if (outputs[i].identifier == output) m_output = int(i);
    }
    if (m_output < 0) throw std::invalid_argument("NNLS Chroma has no output named " + output);
}

NnlsHost::~NnlsHost() = default;

void NnlsHost::write(const float *samples, std::size_t count) {
    if (m_finished) throw std::logic_error("write() after finish()");
    m_pending.insert(m_pending.end(), samples, samples + count);
    while (m_pending.size() - m_head >= std::size_t(kBlock)) {
        process(m_pending.data() + m_head);
        m_head += kStep;
    }
    if (m_head > std::size_t(4 * kBlock)) {  // drop consumed samples now and then, not every frame
        m_pending.erase(m_pending.begin(), m_pending.begin() + long(m_head));
        m_head = 0;
    }
}

std::vector<std::vector<float>> NnlsHost::finish() {
    if (m_finished) throw std::logic_error("finish() called twice");
    m_finished = true;
    std::vector<float> frame(kBlock);
    while (m_head < m_pending.size()) {  // frames_from_array: while i < n, zero-padded
        std::size_t available = std::min(m_pending.size() - m_head, std::size_t(kBlock));
        std::fill(std::copy(m_pending.begin() + long(m_head), m_pending.begin() + long(m_head + available),
                            frame.begin()), frame.end(), 0.0f);
        process(frame.data());
        m_head += kStep;
    }
    collect(m_plugin->getRemainingFeatures(), m_output, m_rows);
    return std::move(m_rows);
}

void NnlsHost::process(const float *frame) {
    RealTime timestamp = RealTime::frame2RealTime(m_frameStart, (unsigned int)(m_sampleRate));
    collect(m_plugin->process(&frame, timestamp), m_output, m_rows);
    m_frameStart += kStep;
}

}
```
NNLS Chroma returns `bothchroma` from `getRemainingFeatures()` (it estimates tuning over the whole song first) and `logfreqspec` from `process()`, so `collect` reads both places, as `vamp.collect` does.

Create `Mobile/engine/src/main/cpp/jni_util.h`:
```cpp
// Small helpers shared by the JNI bridges.
#pragma once

#include <jni.h>

#include <vector>

namespace chordify {

inline void throwJava(JNIEnv *env, const char *className, const char *message) {
    jclass type = env->FindClass(className);
    if (type != nullptr) env->ThrowNew(type, message);
}

inline jfloatArray toJava(JNIEnv *env, const std::vector<float> &values) {
    jfloatArray array = env->NewFloatArray(jsize(values.size()));
    if (array != nullptr) env->SetFloatArrayRegion(array, 0, jsize(values.size()), values.data());
    return array;
}

}
```

Create `Mobile/engine/src/main/cpp/nnls_jni.cpp`:
```cpp
// JNI bridge for com.arbaba.chordifymobile.engine.features.NnlsNative.
#include <jni.h>

#include <exception>
#include <string>

#include "jni_util.h"
#include "nnls_host.h"

using chordify::NnlsHost;
using chordify::throwJava;
using chordify::toJava;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_create(JNIEnv *env, jclass, jfloat sampleRate,
                                                                 jstring output) {
    const char *chars = env->GetStringUTFChars(output, nullptr);
    std::string name(chars);
    env->ReleaseStringUTFChars(output, chars);
    try {
        return reinterpret_cast<jlong>(new NnlsHost(sampleRate, name));
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalArgumentException", error.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_write(JNIEnv *env, jclass, jlong handle,
                                                                jfloatArray samples, jint count) {
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    try {
        reinterpret_cast<NnlsHost *>(handle)->write(data, std::size_t(count));
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT jobjectArray JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_finish(JNIEnv *env, jclass, jlong handle) {
    std::vector<std::vector<float>> rows;
    try {
        rows = reinterpret_cast<NnlsHost *>(handle)->finish();
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(jsize(rows.size()), env->FindClass("[F"), nullptr);
    for (std::size_t i = 0; i < rows.size(); ++i) {
        jfloatArray row = toJava(env, rows[i]);
        env->SetObjectArrayElement(result, jsize(i), row);
        env->DeleteLocalRef(row);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_arbaba_chordifymobile_engine_features_NnlsNative_release(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<NnlsHost *>(handle);
}

}
```

Create `Mobile/engine/src/main/cpp/CMakeLists.txt`:
```cmake
# The engine's native library, libchordify_engine.so: NNLS Chroma run by a minimal Vamp host.
# The third-party code is unmodified upstream source at the versions the Python reference uses
# (third_party/README.md); flags follow how that reference is built.
cmake_minimum_required(VERSION 3.22.1)
project(chordify_engine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# No fused multiply-add anywhere: the Python reference runs on x86-64 without FMA, and arm64
# would otherwise contract a*b+c and round differently. -O3 even in debug builds, so the
# instrumented tests and the benchmark run the code that ships.
set(PARITY_FLAGS -O3 -ffp-contract=off)

# The host side of Vamp plugin SDK 2.6, as the Python vamp 1.1.0 package bundles it: the
# input-domain adapter (Hann window, built-in FFT) that turns audio into NNLS Chroma's input.
set(VAMP_SDK ${THIRD_PARTY}/vamp-plugin-sdk)
add_library(vamp_hostsdk STATIC
    ${VAMP_SDK}/src/vamp-hostsdk/PluginInputDomainAdapter.cpp
    ${VAMP_SDK}/src/vamp-hostsdk/PluginWrapper.cpp
    ${VAMP_SDK}/src/vamp-hostsdk/RealTime.cpp)
target_include_directories(vamp_hostsdk PUBLIC ${VAMP_SDK})
target_compile_definitions(vamp_hostsdk PRIVATE _USE_MATH_DEFINES)
target_compile_options(vamp_hostsdk PRIVATE ${PARITY_FLAGS} -w)

# NNLS Chroma, compiled into the host's namespace so the adapter wraps it directly, with the
# optimisation flags of its Makefile.linux (how tools/install_nnls_chroma.sh builds it).
set(NNLS ${THIRD_PARTY}/nnls-chroma)
add_library(nnls_chroma STATIC
    ${NNLS}/NNLSBase.cpp
    ${NNLS}/NNLSChroma.cpp
    ${NNLS}/chromamethods_nnls.cpp
    ${NNLS}/nnls.c
    nnls_factory.cpp)
target_include_directories(nnls_chroma PRIVATE ${NNLS} ${VAMP_SDK})
target_compile_definitions(nnls_chroma PRIVATE _VAMP_PLUGIN_IN_HOST_NAMESPACE)
target_compile_options(nnls_chroma PRIVATE ${PARITY_FLAGS} -ftree-vectorize -ffast-math -ffp-contract=off -w)

add_library(chordify_engine SHARED nnls_host.cpp nnls_jni.cpp)
target_compile_options(chordify_engine PRIVATE ${PARITY_FLAGS} -Wall)
target_link_libraries(chordify_engine PRIVATE nnls_chroma vamp_hostsdk log)
```
`-ffp-contract=off` comes after `-ffast-math` on purpose: `-ffast-math` would turn contraction back on.

- [ ] **Step 8: Write the Kotlin side**

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/NativeLibrary.kt`:
```kotlin
package com.arbaba.chordifymobile.engine

/** Loads libchordify_engine.so (src/main/cpp) once, before the first native call. */
internal object NativeLibrary {
    init {
        System.loadLibrary("chordify_engine")
    }

    fun load() = Unit
}
```

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/SampleSink.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

/** Receives mono audio chunk by chunk while a file is decoded. */
fun interface SampleSink {
    /** The first [count] values of [samples]. The array may be reused once the call returns. */
    fun write(samples: FloatArray, count: Int)
}
```

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/features/NnlsChroma.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import com.arbaba.chordifymobile.engine.NativeLibrary
import com.arbaba.chordifymobile.engine.audio.SampleSink

/** NNLS Chroma (Mauch & Dixon) as the server runs it: the Vamp plugin, driven by a native host
 *  that frames, windows and transforms the audio exactly as the Python `vamp` package does
 *  (src/main/cpp/nnls_host.h). Write mono audio at [sampleRate], then [finish] once.
 *  Not thread-safe; [close] frees the native memory. */
class NnlsChroma(
    sampleRate: Int = NNLS_BOTHCHROMA.sampleRate,
    output: String = "bothchroma",
) : SampleSink, AutoCloseable {
    private var handle = NnlsNative.create(sampleRate.toFloat(), output)
    private var finished = false

    override fun write(samples: FloatArray, count: Int) {
        check(handle != 0L && !finished) { "NnlsChroma is finished or closed" }
        require(count in 0..samples.size) { "count $count is outside 0..${samples.size}" }
        NnlsNative.write(handle, samples, count)
    }

    /** One row per frame, in the plugin's bin order: A first ([nnlsToCOrder] makes it C first). */
    fun finish(): Array<FloatArray> {
        check(handle != 0L && !finished) { "NnlsChroma is finished or closed" }
        finished = true
        return NnlsNative.finish(handle)
    }

    override fun close() {
        if (handle != 0L) {
            NnlsNative.release(handle)
            handle = 0L
        }
    }
}

/** src/main/cpp/nnls_jni.cpp. */
internal object NnlsNative {
    init {
        NativeLibrary.load()
    }

    @JvmStatic external fun create(sampleRate: Float, output: String): Long
    @JvmStatic external fun write(handle: Long, samples: FloatArray, count: Int)
    @JvmStatic external fun finish(handle: Long): Array<FloatArray>
    @JvmStatic external fun release(handle: Long)
}
```

- [ ] **Step 9: Run the device tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, with `NnlsChromaDeviceTest` 5 passed. The first run creates and boots the emulator, which takes a few minutes. The report is under `Mobile/engine/build/reports/androidTests/managedDevice/`.

If a feature test fails, its message names the worst frame and value:
- `logfreqspec` matches but `bothchroma` does not: the host is right and NNLS Chroma's own maths rounds differently under clang than under gcc with `-ffast-math`. Do not widen the tolerance or change the flags on your own: stop, report the largest difference, and ask, because the fix (looser tolerance, or building the server's plugin without `-ffast-math`) changes the reference.
- `logfreqspec` differs too: the host is wrong. Check, in this order, the frame count (`frames_from_array` makes `ceil(n / 2048)` frames), the zero padding of the last frames, and that the adapter is initialised with step 2048 and block 16384 before `reset()`.

If Gradle cannot start the managed emulator on Windows, start an API 34 x86_64 emulator from Android Studio's Device Manager instead and run `./gradlew :engine:connectedDebugAndroidTest`.

- [ ] **Step 10: Check the JVM tests still pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 34 tests passed.

- [ ] **Step 11: Run the device tests in CI**

In `.github/workflows/ci.yml`, add this job after the `android` job (same indentation as `python:`):
```yaml
  android-device:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    defaults:
      run:
        working-directory: Mobile
    steps:
      - uses: actions/checkout@v5
      - name: Let the emulator use KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - name: Engine device tests (x86_64 emulator)
        run: ./gradlew :engine:api34DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```
The runner has no GPU, hence `swiftshader_indirect`. The job downloads the API 34 Google APIs image on each run.

- [ ] **Step 12: Commit**

```bash
git add tools/vendor_mobile_native.py Mobile/gradle/libs.versions.toml Mobile/engine .github/workflows/ci.yml
git commit -m "Run NNLS Chroma natively in the engine, matching the Python Vamp host" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: Resampling with libsoxr, as librosa does it

**Files:**
- Create: `Mobile/engine/src/main/cpp/soxr_jni.cpp`
- Modify: `Mobile/engine/src/main/cpp/CMakeLists.txt` (replace)
- Create: `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/Resampler.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/audio/ResamplerTest.kt`
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/audio/ResamplerDeviceTest.kt`

**Interfaces:**
- Consumes: `NativeLibrary` (Task 3); `third_party/libsoxr` (vendored in Task 3); `TestAudio.readWav`, `golden`, `concat` (Task 3); `Goldens`, `assertMatrixClose`, `floats` (Task 2); `features.json.gz` `inputs[].samples`, `inputs[].resampled` (Task 1).
- Produces (package `com.arbaba.chordifymobile.engine.audio`):
  - `class Resampler(inRate: Int, outRate: Int) : AutoCloseable` with `process(input: FloatArray, count: Int = input.size): FloatArray` and `finish(): FloatArray`; after `finish`, exactly `ceil(n × outRate / inRate)` samples have come out. Equal rates pass samples through without native code.
  - `internal interface ResamplerCore : AutoCloseable { fun process(input: FloatArray, count: Int): FloatArray; fun flush(): FloatArray }` and the `internal` constructor `Resampler(inRate, outRate, core: ResamplerCore?)`, for tests.

- [ ] **Step 1: Write the failing JVM test of the length rule**

The length rule and the hold-back are plain Kotlin, so they are tested on the JVM with stand-in cores; libsoxr itself is tested on the emulator in Step 7.

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/audio/ResamplerTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {
    /** Stands in for libsoxr at twice the rate: every sample twice, then [tail] extra samples when flushed. */
    private class Doubler(private val tail: Int) : ResamplerCore {
        var closed = false
        override fun process(input: FloatArray, count: Int) = FloatArray(2 * count) { input[it / 2] }
        override fun flush() = FloatArray(tail) { -1f }
        override fun close() {
            closed = true
        }
    }

    /** Produces one sample per input sample, whatever the rates: too few for an upsampler. */
    private class Identity : ResamplerCore {
        override fun process(input: FloatArray, count: Int) = input.copyOf(count)
        override fun flush() = FloatArray(0)
        override fun close() = Unit
    }

    private fun run(resampler: Resampler, input: FloatArray, chunk: Int): FloatArray {
        val out = ArrayList<Float>()
        var at = 0
        while (at < input.size) {
            val count = minOf(chunk, input.size - at)
            out += resampler.process(input.copyOfRange(at, at + count)).toList()
            at += count
        }
        out += resampler.finish().toList()
        return out.toFloatArray()
    }

    @Test
    fun extraOutputIsCutToCeilOfTheRatio() {
        val out = run(Resampler(1, 2, Doubler(tail = 5)), FloatArray(101) { it.toFloat() }, chunk = 10)
        assertEquals(202, out.size) // ceil(101 × 2): the 5 flushed samples are dropped, as librosa's fix_length does
        assertArrayEquals(FloatArray(202) { (it / 2).toFloat() }, out, 0f)
    }

    @Test
    fun shortOutputIsZeroPadded() {
        val out = run(Resampler(2, 3, Identity()), FloatArray(10) { 1f }, chunk = 4)
        assertArrayEquals(FloatArray(15) { if (it < 10) 1f else 0f }, out, 0f) // ceil(10 × 1.5) = 15
    }

    @Test
    fun theNewestOutputIsHeldUntilFinish() {
        val resampler = Resampler(1, 2, Doubler(tail = 0))
        assertEquals(0, resampler.process(FloatArray(10)).size) // 20 samples, all inside the hold-back
        assertEquals(20, resampler.finish().size)
    }

    @Test
    fun chunkSizeDoesNotChangeTheOutput() {
        val input = FloatArray(1000) { (it % 17).toFloat() }
        val whole = run(Resampler(1, 2, Doubler(tail = 3)), input, chunk = input.size)
        for (chunk in listOf(1, 7, 300)) assertArrayEquals("chunks of $chunk", whole, run(Resampler(1, 2, Doubler(3)), input, chunk), 0f)
    }

    @Test
    fun theSameRatePassesThroughWithoutNativeCode() {
        val resampler = Resampler(44100, 44100)
        val input = floatArrayOf(0.5f, -0.25f, 0.125f)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), resampler.process(input, 2), 0f)
        assertEquals(0, resampler.finish().size)
    }

    @Test
    fun finishingTwiceIsRefused() {
        val resampler = Resampler(1, 2, Doubler(tail = 0))
        resampler.finish()
        assertThrows(IllegalStateException::class.java) { resampler.finish() }
        assertThrows(IllegalStateException::class.java) { resampler.process(FloatArray(1)) }
    }

    @Test
    fun closingClosesTheCore() {
        val core = Doubler(tail = 0)
        Resampler(1, 2, core).close()
        assertTrue(core.closed)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.audio.ResamplerTest'`
Expected: compilation fails with `Unresolved reference 'ResamplerCore'` and `Unresolved reference 'Resampler'`.

- [ ] **Step 3: Write the resampler**

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/Resampler.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import com.arbaba.chordifymobile.engine.NativeLibrary
import kotlin.math.ceil

/** Resamples a mono stream as `librosa.resample(y, orig_sr=inRate, target_sr=outRate)` does by
 *  default: libsoxr at high quality (`res_type="soxr_hq"`), then the result cut or zero-padded to
 *  `ceil(n × outRate / inRate)` samples (`fix=True`). Chunks may have any size. The newest
 *  output is held back until [finish], so the length can still be fixed. */
class Resampler internal constructor(
    val inRate: Int,
    val outRate: Int,
    private val core: ResamplerCore?,
) : AutoCloseable {
    constructor(inRate: Int, outRate: Int) : this(inRate, outRate, if (inRate == outRate) null else SoxrCore(inRate, outRate))

    private var inputs = 0L
    private var emitted = 0L
    private var held = FloatArray(0)
    private var finished = false

    /** Output for the first [count] values of [input]. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        check(!finished) { "process() after finish()" }
        inputs += count
        if (core == null) {
            emitted += count
            return input.copyOf(count)
        }
        val all = held + core.process(input, count)
        val release = maxOf(0, all.size - HOLDBACK)
        held = all.copyOfRange(release, all.size)
        emitted += release
        return all.copyOf(release)
    }

    /** The rest of the output. Afterwards exactly ceil(n × outRate / inRate) samples have come out. */
    fun finish(): FloatArray {
        check(!finished) { "finish() called twice" }
        finished = true
        if (core == null) return FloatArray(0)
        val total = ceil(inputs * (outRate.toDouble() / inRate)).toLong()
        check(total >= emitted) { "$emitted samples already out, more than the $total librosa keeps" }
        return (held + core.flush()).copyOf((total - emitted).toInt())
    }

    override fun close() {
        core?.close()
    }

    private companion object {
        // libsoxr never produces more than ceil(n × ratio) samples (checked against python-soxr
        // 1.1.0 for rates from 8 to 96 kHz), so holding back 64 is plenty.
        const val HOLDBACK = 64
    }
}

/** The resampling engine behind [Resampler]: libsoxr in the app, stand-ins in JVM tests. */
internal interface ResamplerCore : AutoCloseable {
    fun process(input: FloatArray, count: Int): FloatArray
    fun flush(): FloatArray
}

/** libsoxr through src/main/cpp/soxr_jni.cpp. */
private class SoxrCore(inRate: Int, outRate: Int) : ResamplerCore {
    private var handle = SoxrNative.create(inRate.toDouble(), outRate.toDouble())

    override fun process(input: FloatArray, count: Int): FloatArray = SoxrNative.process(handle, input, count)

    override fun flush(): FloatArray = SoxrNative.flush(handle)

    override fun close() {
        if (handle != 0L) {
            SoxrNative.release(handle)
            handle = 0L
        }
    }
}

internal object SoxrNative {
    init {
        NativeLibrary.load()
    }

    @JvmStatic external fun create(inRate: Double, outRate: Double): Long
    @JvmStatic external fun process(handle: Long, input: FloatArray, count: Int): FloatArray
    @JvmStatic external fun flush(handle: Long): FloatArray
    @JvmStatic external fun release(handle: Long)
}
```
`ceil(inputs * (outRate.toDouble() / inRate))` is librosa's `int(np.ceil(y.shape[-1] * ratio))` with `ratio = float(target_sr) / orig_sr`: the same double operations, so the same length.

- [ ] **Step 4: Run the JVM tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `ResamplerTest` 7 passed, 41 tests in all.

- [ ] **Step 5: Write the failing device test**

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/audio/ResamplerDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.features.NNLS_BOTHCHROMA
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResamplerDeviceTest {
    private val golden = Goldens.loadGz("features")

    private fun resample(samples: FloatArray, from: Int, chunk: Int): FloatArray =
        Resampler(from, NNLS_BOTHCHROMA.sampleRate).use { resampler ->
            val parts = ArrayList<FloatArray>()
            var at = 0
            while (at < samples.size) {
                val count = minOf(chunk, samples.size - at)
                parts += resampler.process(samples.copyOfRange(at, at + count))
                at += count
            }
            parts += resampler.finish()
            TestAudio.concat(parts)
        }

    @Test
    fun resamplingMatchesLibrosa() {
        val inputs = golden.objects("inputs").filter { it.has("resampled") }
        assertEquals(2, inputs.size) // 48 kHz down, 22.05 kHz up
        for (input in inputs) {
            val name = input.str("file")!!
            val wav = TestAudio.readWav(TestAudio.golden(name))
            val out = resample(wav.mono, wav.sampleRate, chunk = 4096)
            assertEquals(name, input.int("samples"), out.size)
            val slice = input.obj("resampled")
            val start = slice.int("start")!!
            val expected = slice.getValue("values").floats()
            assertMatrixClose(name, arrayOf(expected), arrayOf(out.copyOfRange(start, start + expected.size)), 1e-5)
        }
    }

    @Test
    fun chunkSizeDoesNotChangeTheOutput() {
        val wav = TestAudio.readWav(TestAudio.golden("loop_48k.wav"))
        val whole = resample(wav.mono, wav.sampleRate, chunk = wav.mono.size)
        for (chunk in listOf(1000, 7777)) {
            assertArrayEquals("chunks of $chunk", whole, resample(wav.mono, wav.sampleRate, chunk), 0f)
        }
    }

    @Test
    fun theAnalysisRatePassesThrough() {
        val samples = FloatArray(1000) { it / 1000f }
        assertArrayEquals(samples, resample(samples, NNLS_BOTHCHROMA.sampleRate, chunk = 300), 0f)
    }
}
```

- [ ] **Step 6: Build libsoxr and its JNI bridge**

Create `Mobile/engine/src/main/cpp/soxr_jni.cpp`:
```cpp
// JNI bridge for com.arbaba.chordifymobile.engine.audio.SoxrNative: libsoxr set up as
// python-soxr 1.1.0's soxr.resample sets it up for librosa.resample(res_type="soxr_hq"):
// float32 in and out, SOXR_HQ, no flags, default runtime settings.
#include <jni.h>

#include <vector>

#include "jni_util.h"
#include "soxr.h"

using chordify::throwJava;
using chordify::toJava;

namespace {

// Runs soxr_process until all `count` samples are consumed (or, with in == nullptr, until the
// flush yields nothing more) and returns everything it produced.
std::vector<float> run(JNIEnv *env, soxr_t soxr, const float *in, std::size_t count) {
    std::vector<float> out;
    float buffer[8192];
    std::size_t consumed = 0;
    for (;;) {
        std::size_t used = 0, made = 0;
        soxr_error_t error = soxr_process(soxr, in == nullptr ? nullptr : in + consumed, count - consumed,
                                          in == nullptr ? nullptr : &used, buffer, 8192, &made);
        if (error != nullptr) {
            throwJava(env, "java/lang/IllegalStateException", error);
            return {};
        }
        consumed += used;
        out.insert(out.end(), buffer, buffer + made);
        if (in == nullptr ? made == 0 : (consumed == count && made < 8192)) return out;
    }
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_create(JNIEnv *env, jclass, jdouble inRate,
                                                              jdouble outRate) {
    soxr_error_t error = nullptr;
    soxr_io_spec_t io = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
    soxr_quality_spec_t quality = soxr_quality_spec(SOXR_HQ, 0);
    soxr_t soxr = soxr_create(inRate, outRate, 1, &error, &io, &quality, nullptr);
    if (error != nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", error);
        return 0;
    }
    return reinterpret_cast<jlong>(soxr);
}

JNIEXPORT jfloatArray JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_process(JNIEnv *env, jclass, jlong handle,
                                                               jfloatArray input, jint count) {
    std::vector<float> in(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(input, 0, count, in.data());
    std::vector<float> out = run(env, reinterpret_cast<soxr_t>(handle), in.data(), in.size());
    return env->ExceptionCheck() ? nullptr : toJava(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_flush(JNIEnv *env, jclass, jlong handle) {
    std::vector<float> out = run(env, reinterpret_cast<soxr_t>(handle), nullptr, 0);
    return env->ExceptionCheck() ? nullptr : toJava(env, out);
}

JNIEXPORT void JNICALL
Java_com_arbaba_chordifymobile_engine_audio_SoxrNative_release(JNIEnv *, jclass, jlong handle) {
    soxr_delete(reinterpret_cast<soxr_t>(handle));
}

}
```
python-soxr passes its float32 mono input as one split channel (`SOXR_FLOAT32_S`); with one channel, split and interleaved are the same memory, so `SOXR_FLOAT32_I` gives the same samples. Feeding libsoxr in other chunk sizes does not change its output: python-soxr's own `ResampleStream`, chunk by chunk, gives the same samples as one call.

Replace the whole of `Mobile/engine/src/main/cpp/CMakeLists.txt` with:
```cmake
# The engine's native library, libchordify_engine.so: NNLS Chroma run by a minimal Vamp host,
# and libsoxr. The third-party code is unmodified upstream source at the versions the Python
# reference uses (third_party/README.md); flags follow how that reference is built.
cmake_minimum_required(VERSION 3.22.1)
project(chordify_engine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# No fused multiply-add anywhere: the Python reference runs on x86-64 without FMA, and arm64
# would otherwise contract a*b+c and round differently. -O3 even in debug builds, so the
# instrumented tests and the benchmark run the code that ships.
set(PARITY_FLAGS -O3 -ffp-contract=off)

# libsoxr, configured as python-soxr 1.1.0's CMakeLists.txt configures it.
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WITH_OPENMP OFF CACHE BOOL "" FORCE)
set(WITH_LSR_BINDINGS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/libsoxr ${CMAKE_CURRENT_BINARY_DIR}/libsoxr EXCLUDE_FROM_ALL)
target_compile_options(soxr PRIVATE ${PARITY_FLAGS} -w)

# The host side of Vamp plugin SDK 2.6, as the Python vamp 1.1.0 package bundles it: the
# input-domain adapter (Hann window, built-in FFT) that turns audio into NNLS Chroma's input.
set(VAMP_SDK ${THIRD_PARTY}/vamp-plugin-sdk)
add_library(vamp_hostsdk STATIC
    ${VAMP_SDK}/src/vamp-hostsdk/PluginInputDomainAdapter.cpp
    ${VAMP_SDK}/src/vamp-hostsdk/PluginWrapper.cpp
    ${VAMP_SDK}/src/vamp-hostsdk/RealTime.cpp)
target_include_directories(vamp_hostsdk PUBLIC ${VAMP_SDK})
target_compile_definitions(vamp_hostsdk PRIVATE _USE_MATH_DEFINES)
target_compile_options(vamp_hostsdk PRIVATE ${PARITY_FLAGS} -w)

# NNLS Chroma, compiled into the host's namespace so the adapter wraps it directly, with the
# optimisation flags of its Makefile.linux (how tools/install_nnls_chroma.sh builds it).
set(NNLS ${THIRD_PARTY}/nnls-chroma)
add_library(nnls_chroma STATIC
    ${NNLS}/NNLSBase.cpp
    ${NNLS}/NNLSChroma.cpp
    ${NNLS}/chromamethods_nnls.cpp
    ${NNLS}/nnls.c
    nnls_factory.cpp)
target_include_directories(nnls_chroma PRIVATE ${NNLS} ${VAMP_SDK})
target_compile_definitions(nnls_chroma PRIVATE _VAMP_PLUGIN_IN_HOST_NAMESPACE)
target_compile_options(nnls_chroma PRIVATE ${PARITY_FLAGS} -ftree-vectorize -ffast-math -ffp-contract=off -w)

add_library(chordify_engine SHARED nnls_host.cpp nnls_jni.cpp soxr_jni.cpp)
target_include_directories(chordify_engine PRIVATE ${THIRD_PARTY}/libsoxr/src)
target_compile_options(chordify_engine PRIVATE ${PARITY_FLAGS} -Wall)
target_link_libraries(chordify_engine PRIVATE nnls_chroma vamp_hostsdk soxr log)
```
libsoxr's CMake finds its SIMD engine on both ABIs (the configure log under `Mobile/engine/.cxx` says `Found SIMD32`), as python-soxr's x86-64 build does, so the HQ resampler runs the same code path.

- [ ] **Step 7: Run the device tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`; `NnlsChromaDeviceTest` 5 and `ResamplerDeviceTest` 3 passed. If `resamplingMatchesLibrosa` fails on the length, compare `ceil(n × ratio)` by hand for that file: 384000 samples at 48 kHz and 176400 at 22.05 kHz both give 352800.

- [ ] **Step 8: Commit**

```bash
git add Mobile/engine
git commit -m "Resample with libsoxr in the engine, as librosa does" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Streaming decode with MediaCodec, and features from a file

**Files:**
- Create: `.../engine/Errors.kt`, `.../engine/audio/AudioSource.kt`, `.../engine/audio/Pcm.kt`, `.../engine/audio/AudioDecoder.kt`, `.../engine/audio/AudioStream.kt`, `.../engine/features/Extract.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/audio/PcmTest.kt`
- Modify: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/TestAudio.kt` (add `envelopeOffsetMs`)
- Test: `.../androidTest/kotlin/com/arbaba/chordifymobile/engine/audio/AudioDecoderDeviceTest.kt`, `StreamAudioDeviceTest.kt`, `EncodedAudioDeviceTest.kt`
- Test: `.../androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeaturePipelineDeviceTest.kt`

**Interfaces:**
- Consumes: `Resampler` (Task 4); `SampleSink`, `NnlsChroma` (Task 3); `nnlsToCOrder`, `normaliseBothchroma` (Task 2); `pyRoundToInt` (M1, `engine.util`); `features.json.gz` (Task 1).
- Produces:
  - Package `com.arbaba.chordifymobile.engine`: `enum class ErrorCode { UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG }`, `class EngineException(val code: ErrorCode, message: String, cause: Throwable? = null) : Exception`, `class CancelledException : Exception`.
  - Package `...engine.audio`: `sealed interface AudioSource` with `FromFile(file: File)` and `FromUri(context: Context, uri: Uri)`; `const val ANALYSIS_SAMPLE_RATE = 44100`; `data class AudioLimits(val minSeconds: Double = 1.0, val maxSeconds: Double = 900.0)`; `data class StreamInfo(val sourceSampleRate: Int, val sourceChannels: Int, val sourceFrames: Long, val samples: Long)` with `durationSeconds`; `fun streamAudio(source: AudioSource, sinks: List<SampleSink>, limits: AudioLimits = AudioLimits(), isCancelled: () -> Boolean = { false }): StreamInfo`; internal `AudioDecoder`, `DecodedTrack`, `Pcm`, `EdgeTrimmer`.
  - Package `...engine.features`: `class NnlsFeatures(val info: StreamInfo, val raw: Array<FloatArray>)` and `fun extractNnlsBothchroma(source: AudioSource, limits: AudioLimits = AudioLimits(), isCancelled: () -> Boolean = { false }): NnlsFeatures` (raw is C-ordered). M3's analysis adds the beat tracker as a second sink of `streamAudio`.

- [ ] **Step 1: Write the failing JVM test of the PCM conversion**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/audio/PcmTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import android.media.AudioFormat
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    /** Little-endian integer samples, each [width] bytes. */
    private fun pcm(width: Int, vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * width).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) for (byte in 0 until width) buffer.put((value shr (8 * byte)).toByte())
        buffer.flip()
        return buffer
    }

    @Test
    fun sixteenBitIsScaledBy32768AndChannelsAreAveraged() {
        val mono = Pcm.toMono(pcm(2, 16384, -32768, 1, 2), AudioFormat.ENCODING_PCM_16BIT, channels = 2)
        assertArrayEquals(floatArrayOf((0.5f + -1f) / 2, (1 / 32768f + 2 / 32768f) / 2), mono, 0f)
    }

    @Test
    fun otherEncodingsAreScaledAsLibsndfileScalesThem() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 127 / 128f), Pcm.toMono(pcm(1, 0, 128, 255), AudioFormat.ENCODING_PCM_8BIT, 1), 0f)
        assertArrayEquals(floatArrayOf(-1f, 8388607 / 8388608f),
            Pcm.toMono(pcm(3, -8388608, 8388607), AudioFormat.ENCODING_PCM_24BIT_PACKED, 1), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0.5f), Pcm.toMono(pcm(4, Int.MIN_VALUE, 1 shl 30), AudioFormat.ENCODING_PCM_32BIT, 1), 0f)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-0.75f)
        floats.flip()
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), Pcm.toMono(floats, AudioFormat.ENCODING_PCM_FLOAT, 1), 0f)
    }

    @Test
    fun onlyTheBuffersWindowIsRead() {
        val buffer = pcm(2, 100, 16384, 300)
        buffer.position(2)
        buffer.limit(4)
        assertArrayEquals(floatArrayOf(0.5f), Pcm.toMono(buffer, AudioFormat.ENCODING_PCM_16BIT, 1), 0f)
    }

    @Test
    fun anUnreadableEncodingIsADecodeFailure() {
        val error = assertThrows(EngineException::class.java) { Pcm.toMono(pcm(2, 0), 999, 1) }
        assertEquals(ErrorCode.DECODE_FAILED, error.code)
    }

    @Test
    fun theTrimmerDropsTheEncoderDelayAndPadding() {
        val trimmer = EdgeTrimmer(delay = 3, padding = 2)
        val chunks = listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f, 5f), floatArrayOf(6f, 7f, 8f))
        assertEquals(listOf(4f, 5f, 6f), chunks.flatMap { trimmer.push(it).toList() }) // 1-3 delay, 7-8 padding
    }

    @Test
    fun aTrimmerWithNothingToDropPassesEverything() {
        val trimmer = EdgeTrimmer(delay = 0, padding = 0)
        assertArrayEquals(floatArrayOf(1f, 2f), trimmer.push(floatArrayOf(1f, 2f)), 0f)
    }
}
```
`AudioFormat`'s encodings are compile-time constants, so the JVM test needs no Android runtime.

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.audio.PcmTest'`
Expected: compilation fails with `Unresolved reference 'Pcm'`, `'EdgeTrimmer'`, `'EngineException'` and `'ErrorCode'`.

- [ ] **Step 3: Write the errors and the PCM conversion**

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/Errors.kt`:
```kotlin
package com.arbaba.chordifymobile.engine

/** The server's error codes (chordify_backend/app/audio_io.py), so the app can show the web app's
 *  message for each. M3 adds NO_MUSIC_DETECTED and MODEL_UNAVAILABLE. */
enum class ErrorCode { UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG }

/** An analysis that cannot go on, with the server's code and a message in the server's words. */
class EngineException(val code: ErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the caller's `isCancelled()` returned true. */
class CancelledException : Exception("cancelled")
```

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/Pcm.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import android.media.AudioFormat
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interleaved PCM from a decoder -> mono float32, as libsndfile and chordify_core.audio.to_mono
 *  make it: integers scaled by 1 / 2^(bits - 1) (8-bit samples are unsigned), then the mean of the
 *  channels in float32. */
internal object Pcm {
    fun toMono(pcm: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val data = pcm.slice().order(ByteOrder.LITTLE_ENDIAN)
        val width = bytesPerSample(encoding)
        return FloatArray(data.remaining() / (width * channels)) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += sample(data, (frame * channels + channel) * width, encoding)
            sum / channels
        }
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> throw EngineException(ErrorCode.DECODE_FAILED, "The decoder produced PCM encoding $encoding, which the engine cannot read.")
    }

    private fun sample(data: ByteBuffer, at: Int, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> data.getShort(at) / 32768f
        AudioFormat.ENCODING_PCM_FLOAT -> data.getFloat(at)
        AudioFormat.ENCODING_PCM_8BIT -> ((data.get(at).toInt() and 0xFF) - 128) / 128f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> ((data.get(at).toInt() and 0xFF) or
            ((data.get(at + 1).toInt() and 0xFF) shl 8) or (data.get(at + 2).toInt() shl 16)) / 8388608f
        else -> data.getInt(at) / 2147483648f
    }
}

/** Drops what an encoder added to a stream that arrives in chunks: the first [delay] frames and
 *  the last [padding] frames (MediaFormat.KEY_ENCODER_DELAY and KEY_ENCODER_PADDING). MediaCodec
 *  leaves this to the app; ffmpeg and libsndfile do it themselves. */
internal class EdgeTrimmer(private var delay: Int, private val padding: Int) {
    private var held = FloatArray(0)

    /** The part of [chunk] that is neither delay nor (possibly) padding; the newest [padding]
     *  frames wait for the next chunk, and whatever waits when the stream ends is the padding. */
    fun push(chunk: FloatArray): FloatArray {
        var samples = chunk
        if (delay > 0) {
            val skip = minOf(delay, samples.size)
            delay -= skip
            samples = samples.copyOfRange(skip, samples.size)
        }
        if (padding == 0) return samples
        val all = held + samples
        val release = maxOf(0, all.size - padding)
        held = all.copyOfRange(release, all.size)
        return all.copyOf(release)
    }
}
```

- [ ] **Step 4: Run the JVM tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `PcmTest` 6 passed, 47 tests in all.

- [ ] **Step 5: Write the failing device tests**

In `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/TestAudio.kt`, add these imports:
```kotlin
import com.arbaba.chordifymobile.engine.util.pyRoundToInt
import kotlin.math.sqrt
```
and add this function inside `object TestAudio`, after `concat`:
```kotlin
    /** chordify_ai.export.mobile_goldens.envelope_offset_ms: how late [other] is against
     *  [reference], in ms, from the lag (within ±200 ms) that best lines up their 5 ms RMS envelopes. */
    fun envelopeOffsetMs(reference: FloatArray, other: FloatArray, sampleRate: Int): Double {
        val hop = pyRoundToInt(sampleRate * 0.005)
        fun envelope(y: FloatArray): DoubleArray {
            val e = DoubleArray(y.size / hop) { i ->
                var sum = 0.0
                for (k in i * hop until (i + 1) * hop) sum += y[k].toDouble() * y[k]
                sqrt(sum / hop)
            }
            val mean = e.average()
            return DoubleArray(e.size) { e[it] - mean }
        }
        val a = envelope(reference)
        val b = envelope(other)
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -40..40) {
            val (x0, y0) = if (lag >= 0) 0 to lag else -lag to 0
            var score = 0.0
            for (i in 0 until minOf(a.size - x0, b.size - y0)) score += a[x0 + i] * b[y0 + i]
            if (score > bestScore) {
                best = lag
                bestScore = score
            }
        }
        return best * hop * 1000.0 / sampleRate
    }
```

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/audio/AudioDecoderDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDecoderDeviceTest {
    @Test
    fun wavFilesDecodeExactlyAsLibsndfileReadsThem() {
        val inputs = Goldens.loadGz("features").objects("inputs")
        assertEquals(4, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val parts = ArrayList<FloatArray>()
            var started = 0
            val track = AudioDecoder(AudioSource.FromFile(TestAudio.golden(name)))
                .decode(isCancelled = { false }, onStart = { started = it }, onSamples = { parts += it })
            assertEquals(name, input.int("sample_rate"), started)
            assertEquals(name, input.int("sample_rate"), track.sampleRate)
            assertEquals(name, input.int("channels"), track.channels)
            assertEquals(name, input.int("frames")!!.toLong(), track.frames)
            val mono = TestAudio.concat(parts)
            val slice = input.obj("decoded")
            val start = slice.int("start")!!
            val expected = slice.getValue("values").floats()
            assertArrayEquals(name, expected, mono.copyOfRange(start, start + expected.size), 0f)
        }
    }
}
```

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/audio/StreamAudioDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StreamAudioDeviceTest {
    private fun stream(
        file: File,
        limits: AudioLimits = AudioLimits(),
        sinks: List<SampleSink> = emptyList(),
        isCancelled: () -> Boolean = { false },
    ) = streamAudio(AudioSource.FromFile(file), sinks, limits, isCancelled)

    private fun assertFails(code: ErrorCode, block: () -> Unit) {
        val error = assertThrows(EngineException::class.java) { block() }
        assertEquals(error.message, code, error.code)
    }

    @Test
    fun reportsTheTrackAndItsLengthAt44k() {
        val input = Goldens.loadGz("features").objects("inputs").single { it.str("file") == "sevenths_stereo_22k.wav" }
        val info = stream(TestAudio.golden("sevenths_stereo_22k.wav"))
        assertEquals(22050, info.sourceSampleRate)
        assertEquals(2, info.sourceChannels)
        assertEquals(input.int("frames")!!.toLong(), info.sourceFrames)
        assertEquals(input.int("samples")!!.toLong(), info.samples)
    }

    @Test
    fun everySinkGetsEverySample() {
        val counts = LongArray(2)
        val info = stream(TestAudio.golden("loop_48k.wav"),
            sinks = listOf(SampleSink { _, count -> counts[0] += count }, SampleSink { _, count -> counts[1] += count }))
        assertEquals(info.samples, counts[0])
        assertEquals(info.samples, counts[1])
    }

    @Test
    fun aFileThatIsNotAudioIsUnsupported() {
        val file = TestAudio.tempFile("notes.wav").apply { writeText("These are not the samples you are looking for.") }
        assertFails(ErrorCode.UNSUPPORTED_MEDIA_TYPE) { stream(file) }
    }

    @Test
    fun audioShorterThanASecondIsRefused() {
        val file = TestAudio.tempFile("short.wav")
        TestAudio.writeWav(file, FloatArray(22050) { (0.25 * sin(2 * PI * 440 * it / 44100)).toFloat() }, 44100)
        assertFails(ErrorCode.AUDIO_TOO_SHORT) { stream(file) }
    }

    @Test
    fun audioLongerThanTheLimitIsRefused() {
        assertFails(ErrorCode.AUDIO_TOO_LONG) { stream(TestAudio.golden("loop.wav"), AudioLimits(maxSeconds = 2.0)) }
    }

    @Test
    fun cancellingStopsTheStream() {
        var checks = 0
        assertThrows(CancelledException::class.java) { stream(TestAudio.golden("loop.wav"), isCancelled = { ++checks > 3 }) }
    }
}
```

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/audio/EncodedAudioDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EncodedAudioDeviceTest {
    private fun decode(name: String): FloatArray {
        val parts = ArrayList<FloatArray>()
        streamAudio(AudioSource.FromFile(TestAudio.golden(name)), listOf(SampleSink { samples, count -> parts += samples.copyOf(count) }))
        return TestAudio.concat(parts)
    }

    // MP3 and AAC decoders treat encoder delay differently from ffmpeg and libsndfile, so lossy
    // files are compared by timing only: within 30 ms of the WAV they were encoded from (spec §7.1).
    @Test
    fun lossyCopiesLineUpWithTheirWav() {
        val encoded = Goldens.loadGz("features").objects("encoded")
        assertEquals(2, encoded.size)
        for (entry in encoded) {
            val name = entry.str("file")!!
            val reference = decode(entry.str("reference")!!)
            val decoded = decode(name)
            val offset = TestAudio.envelopeOffsetMs(reference, decoded, ANALYSIS_SAMPLE_RATE)
            assertTrue("$name is $offset ms off its WAV", abs(offset) <= 30.0)
            val longer = (decoded.size - reference.size) * 1000.0 / ANALYSIS_SAMPLE_RATE
            assertTrue("$name is $longer ms longer than its WAV", abs(longer) <= 100.0)
        }
    }
}
```

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeaturePipelineDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertFeaturesClose
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The whole of milestone M2: a file in, the server's features out (spec §9). */
@RunWith(AndroidJUnit4::class)
class FeaturePipelineDeviceTest {
    @Test
    fun theFeaturesOfEveryInputMatchPython() {
        val inputs = Goldens.loadGz("features").objects("inputs")
        assertEquals(4, inputs.size)
        for (input in inputs) {
            val name = input.str("file")!!
            val features = extractNnlsBothchroma(AudioSource.FromFile(TestAudio.golden(name)))
            assertEquals(name, input.int("samples")!!.toLong(), features.info.samples)
            assertFeaturesClose("$name raw", input.getValue("raw").floatMatrix(), features.raw)
            assertMatrixClose("$name normalised", input.getValue("normalised").floatMatrix(), normaliseBothchroma(features.raw), 1e-4)
        }
    }
}
```

- [ ] **Step 6: Run them to make sure they fail**

Run: `cd Mobile && ./gradlew :engine:api34DebugAndroidTest`
Expected: compiling the device tests fails with `Unresolved reference 'AudioDecoder'`, `'AudioSource'`, `'streamAudio'`, `'AudioLimits'`, `'ANALYSIS_SAMPLE_RATE'` and `'extractNnlsBothchroma'`.

- [ ] **Step 7: Write the source, the decoder and the stream**

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/AudioSource.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.File

/** A file to analyse: a path, or a content URI the user picked (read with their permission). */
sealed interface AudioSource {
    fun openIn(extractor: MediaExtractor)

    data class FromFile(val file: File) : AudioSource {
        override fun openIn(extractor: MediaExtractor) = extractor.setDataSource(file.absolutePath)
    }

    data class FromUri(val context: Context, val uri: Uri) : AudioSource {
        override fun openIn(extractor: MediaExtractor) = extractor.setDataSource(context, uri, null)
    }
}
```

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/AudioDecoder.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import java.io.IOException

/** The first audio track of a file: its own sample rate and channel count, and how many mono frames it gave. */
internal data class DecodedTrack(val sampleRate: Int, val channels: Int, val frames: Long)

/** Decodes the first audio track of a file with MediaExtractor and MediaCodec (mp3, m4a/aac, flac,
 *  ogg, opus, wav, webm), mixes it to mono and hands it on chunk by chunk at the track's own sample
 *  rate, as chordify_core.audio.load_audio does before it resamples. */
internal class AudioDecoder(private val source: AudioSource) {
    /** Calls [onStart] once with the sample rate, then [onSamples] with every chunk. */
    fun decode(isCancelled: () -> Boolean, onStart: (sampleRate: Int) -> Unit, onSamples: (FloatArray) -> Unit): DecodedTrack {
        val extractor = MediaExtractor()
        try {
            try {
                source.openIn(extractor)
            } catch (error: IOException) {
                throw unsupported("The file is not a supported audio format.", error)
            } catch (error: IllegalArgumentException) {
                throw unsupported("The file is not a supported audio format.", error)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw unsupported("The file has no audio track.", null)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = openCodec(format)
            try {
                return pump(extractor, codec, format, isCancelled, onStart, onSamples)
            } catch (error: IllegalStateException) { // MediaCodec.CodecException is one
                throw EngineException(ErrorCode.DECODE_FAILED, "Could not decode the file: ${error.message}", error)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun openCodec(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: IOException) {
            throw unsupported("Android has no decoder for $mime.", error)
        } catch (error: IllegalArgumentException) {
            throw unsupported("Android has no decoder for $mime.", error)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (error: RuntimeException) {
            codec.release()
            throw unsupported("Android cannot decode this $mime track.", error)
        }
        return codec
    }

    private fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        format: MediaFormat,
        isCancelled: () -> Boolean,
        onStart: (Int) -> Unit,
        onSamples: (FloatArray) -> Unit,
    ): DecodedTrack {
        val trimmer = EdgeTrimmer(format.intOr(MediaFormat.KEY_ENCODER_DELAY), format.intOr(MediaFormat.KEY_ENCODER_PADDING))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var sampleRate = 0
        var channels = 0
        var frames = 0L
        var idle = 0
        while (true) {
            if (isCancelled()) throw CancelledException()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) { // nothing yet, or a format change, which every buffer's own format covers
                if (inputDone && ++idle > MAX_IDLE) {
                    throw EngineException(ErrorCode.DECODE_FAILED, "The decoder stopped producing audio.")
                }
                continue
            }
            idle = 0
            val output = codec.getOutputFormat(index)
            val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (sampleRate == 0) {
                sampleRate = rate
                channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                onStart(sampleRate)
            } else if (rate != sampleRate) {
                throw EngineException(ErrorCode.DECODE_FAILED, "The sample rate changes in the middle of the file.")
            }
            val encoding = output.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val buffer = codec.getOutputBuffer(index)!!
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val mono = trimmer.push(Pcm.toMono(buffer, encoding, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)))
            codec.releaseOutputBuffer(index, false)
            if (mono.isNotEmpty()) {
                frames += mono.size
                onSamples(mono)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return DecodedTrack(sampleRate, channels, frames)
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int = 0) = if (containsKey(key)) getInteger(key) else default

    private fun unsupported(message: String, cause: Throwable?) = EngineException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, message, cause)

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 500 // 5 s without output after the end of the input
    }
}
```
A WAV goes through Android's raw decoder, which hands on the file's 16-bit samples unchanged, and `Pcm.toMono` divides them by 32768 as libsndfile does: that is why the WAV comparison in `AudioDecoderDeviceTest` is exact.

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/audio/AudioStream.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.audio

import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import java.util.Locale

/** Every analysis runs at 44.1 kHz, the server's decode_sample_rate. */
const val ANALYSIS_SAMPLE_RATE = 44100

/** Duration limits, as the server's min_duration_s and max_duration_s. */
data class AudioLimits(val minSeconds: Double = 1.0, val maxSeconds: Double = 900.0)

/** What [streamAudio] decoded: the track's own rate, channels and length, and its length at 44.1 kHz. */
data class StreamInfo(val sourceSampleRate: Int, val sourceChannels: Int, val sourceFrames: Long, val samples: Long) {
    val durationSeconds: Double get() = samples.toDouble() / ANALYSIS_SAMPLE_RATE
}

/** Decodes [source] once, as mono 44.1 kHz audio, and gives every chunk to every sink in turn:
 *  the streaming form of the server's decode() and check_duration() (chordify_backend/app/
 *  audio_io.py), which hold the whole song in memory. Errors carry the server's codes. */
fun streamAudio(
    source: AudioSource,
    sinks: List<SampleSink>,
    limits: AudioLimits = AudioLimits(),
    isCancelled: () -> Boolean = { false },
): StreamInfo {
    var resampler: Resampler? = null
    var samples = 0L
    fun emit(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        samples += chunk.size
        if (samples > limits.maxSeconds * ANALYSIS_SAMPLE_RATE) {
            throw EngineException(ErrorCode.AUDIO_TOO_LONG,
                String.format(Locale.ROOT, "The audio is longer than the %.0f min limit.", limits.maxSeconds / 60))
        }
        for (sink in sinks) sink.write(chunk, chunk.size)
    }
    try {
        val track = AudioDecoder(source).decode(
            isCancelled,
            onStart = { rate -> resampler = Resampler(rate, ANALYSIS_SAMPLE_RATE) },
            onSamples = { chunk -> emit(resampler!!.process(chunk)) },
        )
        emit(resampler!!.finish())
        if (samples == 0L) throw EngineException(ErrorCode.DECODE_FAILED, "The file contains no audio samples.")
        val seconds = samples.toDouble() / ANALYSIS_SAMPLE_RATE
        if (seconds < limits.minSeconds) {
            throw EngineException(ErrorCode.AUDIO_TOO_SHORT,
                String.format(Locale.ROOT, "The audio is %.2f s long; at least %.0f s is needed.", seconds, limits.minSeconds))
        }
        return StreamInfo(track.sampleRate, track.channels, track.frames, samples)
    } finally {
        resampler?.close()
    }
}
```
The decoder calls `onStart` for its first output buffer, even an empty end-of-stream one, so `resampler` is set whenever `decode` returns.

Create `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine/features/Extract.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.StreamInfo
import com.arbaba.chordifymobile.engine.audio.streamAudio

/** Raw NNLS bothchroma of a file (C-ordered, one row per frame) and what was decoded. */
class NnlsFeatures(val info: StreamInfo, val raw: Array<FloatArray>)

/** chordify_core.features.nnls_bothchroma on the server's decode of [source], in one streaming pass. */
fun extractNnlsBothchroma(
    source: AudioSource,
    limits: AudioLimits = AudioLimits(),
    isCancelled: () -> Boolean = { false },
): NnlsFeatures = NnlsChroma().use { nnls ->
    val info = streamAudio(source, listOf(nnls), limits, isCancelled)
    NnlsFeatures(info, nnlsToCOrder(nnls.finish()))
}
```
NNLS Chroma does its heavy work in `finish()`, after the stream; cancellation is checked while decoding, and `finish()` cannot be interrupted.

- [ ] **Step 8: Run all the tests to make sure they pass**

Run:
```bash
cd Mobile
./gradlew :engine:testDebugUnitTest
./gradlew :engine:api34DebugAndroidTest
```
Expected: JVM `BUILD SUCCESSFUL`, 47 tests. Device `BUILD SUCCESSFUL`: `NnlsChromaDeviceTest` 5, `ResamplerDeviceTest` 3, `AudioDecoderDeviceTest` 1, `StreamAudioDeviceTest` 6, `EncodedAudioDeviceTest` 1, `FeaturePipelineDeviceTest` 1 passed.

If `EncodedAudioDeviceTest` fails, its message gives the offset. A positive offset means the decoded copy starts late: the encoder delay was left in, because the track format had no `encoder-delay` key. A negative one means it starts early: the delay was trimmed twice, by the decoder and by `EdgeTrimmer`. Log `extractor.getTrackFormat(track)` in `AudioDecoder` to see which keys the platform reports, then stop and report: the fix depends on how this Android version behaves.

- [ ] **Step 9: Commit**

```bash
git add Mobile/engine
git commit -m "Decode any audio file with MediaCodec and stream it into the NNLS features" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: Benchmark, and how to work on the native engine

**Files:**
- Test: `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeatureBenchmarkDeviceTest.kt`
- Modify: `Mobile/engine/README.md` (replace)
- Modify: `docs/chord-progression-plan/08-implementation-status.md` (§3.6)

**Interfaces:**
- Consumes: `extractNnlsBothchroma`, `AudioSource.FromFile` (Task 5); `TestAudio.readWav`, `writeWav`, `golden`, `tempFile` (Task 3).
- Produces: a log line tagged `ChordifyBenchmark` with the time to decode a 240 s song and compute its features; the measured numbers in the status document.

- [ ] **Step 1: Write the benchmark**

The spec's risk table asks for NNLS Chroma's speed to be measured in M2: it dominates the server's analysis time (2–3 s per song), and the target is a 4-minute song analysed in at most 20 s on a mid-range phone.

Create `Mobile/engine/src/androidTest/kotlin/com/arbaba/chordifymobile/engine/features/FeatureBenchmarkDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.features

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.audio.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ceil

/** Times decoding and NNLS Chroma for a 4-minute song: loop.wav 30 times over. */
@RunWith(AndroidJUnit4::class)
class FeatureBenchmarkDeviceTest {
    @Test
    fun aFourMinuteSong() {
        val loop = TestAudio.readWav(TestAudio.golden("loop.wav")).mono
        val song = FloatArray(loop.size * 30) { loop[it % loop.size] }
        val file = TestAudio.tempFile("benchmark.wav").also { TestAudio.writeWav(it, song, 44100) }

        val started = SystemClock.elapsedRealtime()
        val features = extractNnlsBothchroma(AudioSource.FromFile(file))
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0

        Log.i("ChordifyBenchmark", String.format(Locale.ROOT, "decode + NNLS features of a %.0f s song: %.1f s on %s (%s)",
            song.size / 44100.0, seconds, Build.MODEL, Build.SUPPORTED_ABIS[0]))
        assertEquals(ceil(song.size / 2048.0).toInt(), features.raw.size)
    }
}
```

- [ ] **Step 2: Run it on the emulator and read the time**

Run:
```bash
cd Mobile
./gradlew :engine:api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arbaba.chordifymobile.engine.features.FeatureBenchmarkDeviceTest
grep -rh "ChordifyBenchmark" engine/build/outputs/androidTest-results/managedDevice
```
Expected: `BUILD SUCCESSFUL`, and a line like `decode + NNLS features of a 240 s song: <time> s on sdk_gphone64_x86_64 (x86_64)`. If `grep` finds nothing, the managed device's logcat file is somewhere else under `engine/build/outputs/androidTest-results/`: `grep -r` that folder instead.

- [ ] **Step 3: If an Android phone is at hand, run it there too**

Connect a phone with USB debugging on (arm64, Android 13 or later), then:
```bash
cd Mobile
./gradlew :engine:connectedDebugAndroidTest
adb logcat -d -s ChordifyBenchmark
```
Expected: every device test passes on arm64 too (`NnlsChromaDeviceTest` and `FeaturePipelineDeviceTest` are the ones that show whether arm64 rounds NNLS Chroma differently), and one benchmark line. Skip this step when no phone is available, and say so in the status document.

- [ ] **Step 4: Record the numbers**

In `docs/chord-progression-plan/08-implementation-status.md`, at the end of §3.6 (after the paragraph that starts "The phase 1 budget is p95 ≤ 20 s"), add:
```markdown
On the phone the Android engine computes the same NNLS features (`Mobile/engine`, milestone M2 of the
[mobile design](../superpowers/specs/2026-11-17-mobile-on-device-design.md)). Decoding and NNLS Chroma
alone, for a 240 s song, from `FeatureBenchmarkDeviceTest`:

| Device | ABI | Decode + NNLS features |
|---|---|---|
| Emulator (API 34, Google APIs) on <this computer's CPU> | x86_64 | <time from Step 2> s |
| <phone model, or "no phone measured yet"> | arm64-v8a | <time from Step 3> s |
```
Fill in the angle-bracketed cells with what Steps 2 and 3 printed (the CPU from Windows' System settings). This is the only number in the plan that cannot be known before running it.

- [ ] **Step 5: Document how to work on the native engine**

Replace the whole of `Mobile/engine/README.md` with:
````markdown
# Chordify engine

The Android app's on-device analysis engine: a Kotlin port of
[`chordify_core`](../../chordify_core) plus a small native library, with no UI and no network
access. Design:
[`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../../docs/superpowers/specs/2026-11-17-mobile-on-device-design.md).

Each package under `src/main/kotlin/com/arbaba/chordifymobile/engine/` ports the
`chordify_core` module of the same name, function by function, with names in camelCase.
`util/Py.kt` holds the Python and numpy behaviours the port relies on (rounding half to
even, `str.partition`, `numpy.searchsorted`, …). `audio/` decodes any file Android can play
with `MediaCodec` and streams it, mono at 44.1 kHz, into `features/`.

## Native code

`src/main/cpp/` builds `libchordify_engine.so` for arm64-v8a and x86_64 (NDK 27.0, CMake 3.22.1):

- `nnls_host.cpp` runs the NNLS Chroma Vamp plugin as the Python `vamp` package does
  (framing, Hann window, FFT, timestamps), so the features match the server's;
- `soxr_jni.cpp` exposes libsoxr, librosa's default resampler;
- `third_party/` holds the unmodified upstream sources, at the versions the Python reference
  uses. Change them only with `python tools/vendor_mobile_native.py`, together with the pins in
  `tools/install_nnls_chroma.sh`, `tools/mobile_goldens.Dockerfile` and `.github/workflows/ci.yml`.
  NNLS Chroma is GPL-2.0-or-later, which is why the app is GPL-3.0-or-later.

## Tests

```bash
cd Mobile
./gradlew :engine:testDebugUnitTest        # JVM: vocab, theory, lm, decode, feature maths
./gradlew :engine:api34DebugAndroidTest    # x86_64 emulator: native code, MediaCodec, features
```

Gradle 8.10 runs on JDK 17 or 21, not on newer JDKs. The device tests run on a
Gradle-managed emulator (Pixel 6, API 34, Google APIs, x86_64), which Gradle creates on first
use in the Android user home (`~/.android`, or `ANDROID_USER_HOME`); it needs about 8 GB. With
a phone or a running emulator, `./gradlew :engine:connectedDebugAndroidTest` runs the same tests
there. `FeatureBenchmarkDeviceTest` logs, under the tag `ChordifyBenchmark`, how long a 4-minute
song takes.

The tests replay golden files in `src/test/resources/goldens/`: inputs, and what
`chordify_core` returns for them. After changing `chordify_core`, regenerate the files from
the repository root and commit them with the change:

```bash
python -m chordify_ai.export.mobile_goldens   # vocab, theory, lm, decode
tools/mobile_goldens.sh                        # features.json.gz, in Docker, with NNLS Chroma
```

`goldens/audio/` holds the audio inputs, rendered once (`tools/mobile_goldens.sh --render-audio`)
and committed. `tests/ai/test_mobile_goldens.py` fails while the committed files are stale; it
checks `features.json.gz` where NNLS Chroma is installed, as in CI. Never edit the golden files
by hand: when a Kotlin test disagrees, the Kotlin port is what needs fixing.
````

- [ ] **Step 6: Run everything once more**

Run:
```bash
python -m pytest tests/ai/test_mobile_goldens.py -q
cd Mobile
./gradlew :engine:testDebugUnitTest
./gradlew :engine:api34DebugAndroidTest
./gradlew :app:assembleDebug
```
Expected: `4 passed, 1 skipped` (the features file is checked in the container and in CI, which have NNLS Chroma); JVM 47 tests; device 18 tests (`FeatureBenchmarkDeviceTest` included); and the old Java app still builds.

- [ ] **Step 7: Commit**

```bash
git add Mobile/engine docs/chord-progression-plan/08-implementation-status.md
git commit -m "Benchmark the engine's feature extraction and document the native build" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Done when

- `./gradlew :engine:testDebugUnitTest` (47 tests) and `./gradlew :engine:api34DebugAndroidTest` (18 tests) pass locally, and the CI jobs `android` and `android-device` pass.
- `pytest -q` passes in CI, including `tests/ai/test_mobile_goldens.py` with `features.json.gz` checked against CI's own NNLS Chroma build.
- `FeaturePipelineDeviceTest` passes: a WAV file goes in, and the raw and normalised features match the server's within spec §7.1's tolerances. That is milestone M2's "features match on the emulator".
- The benchmark's numbers are in `docs/chord-progression-plan/08-implementation-status.md`.
- The old `:app` module still builds unchanged.

Next: write the M3 plan (ONNX inference, the librosa beat tracker, the two-pass `analysis` and the `AnalysisResult` document), starting from the spec's §5 steps 4–6. M3 adds the beat tracker as a second `SampleSink` of `streamAudio` (and the waveform peaks for the app as a third), so the audio is still decoded once; its golden files add the 3/4 render and the GuitarSet excerpt of spec §7.1.
