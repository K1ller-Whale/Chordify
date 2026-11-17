# Chordify engine

The Android app's on-device analysis engine: a Kotlin port of
[`chordify_core`](../../chordify_core), with no UI and no network access. Design:
[`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../../docs/superpowers/specs/2026-11-17-mobile-on-device-design.md).

Each package under `src/main/kotlin/com/arbaba/chordifymobile/engine/` ports the
`chordify_core` module of the same name, function by function, with names in camelCase.
`util/Py.kt` holds the Python and numpy behaviours the port relies on (rounding half to
even, `str.partition`, `numpy.searchsorted`, …).

## Tests

```bash
cd Mobile
./gradlew :engine:testDebugUnitTest
```

Gradle 8.10 runs on JDK 17 or 21 (Android Studio's bundled JDK works), not on newer JDKs.

The tests replay golden files in `src/test/resources/goldens/`: inputs, and what
`chordify_core` returns for them. After changing `chordify_core`, regenerate the files
from the repository root and commit them with the change:

```bash
python -m chordify_ai.export.mobile_goldens
```

`tests/ai/test_mobile_goldens.py` fails while the committed files are stale. Never edit
them by hand: when a Kotlin test disagrees, the Kotlin port is what needs fixing.
