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

`beats/` ports librosa's onset strength and beat tracker (librosa 1.0.1, as the server pins it),
and `acoustic/` runs the shipped ChordNet with ONNX Runtime. `analysis.runFrontEnd` decodes a file
once into both NNLS Chroma and the beat tracker. The model files are not in `src/`: Gradle copies
`chordnet-chroma/3.0.0` and `progression-ngram/1.0.0` from the repository's `models/` into the
assets at build time, so git holds one copy of each.

`ChordifyEngine` is the API the app calls: `analyse` returns the whole analysis document
(`model.AnalysisResult`, the JSON the server's analysis job writes), `nextChords` the
Songwriter's suggestions, and `quickChord` the one chord in a short clip. `analysis/` ports the
server's pipeline after the audio stages. `analysis.analyseMeasured` runs it from recorded beats
and model output, which is how the JVM tests check it against the server's own numbers.

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
tools/mobile_goldens.sh                        # features, beats, acoustic and analysis, in Docker with the pinned reference
```

`goldens/audio/` holds the audio inputs, rendered once (`tools/mobile_goldens.sh --render-audio`)
and committed. `tests/ai/test_mobile_goldens.py` fails while the committed files are stale; it
checks `features.json.gz` where NNLS Chroma is installed, as in CI. Never edit the golden files
by hand: when a Kotlin test disagrees, the Kotlin port is what needs fixing.
