# Chordify Mobile

The Android app: every feature of the web app, with the analysis running on the phone and no
network access. Design:
[`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../docs/superpowers/specs/2026-11-17-mobile-on-device-design.md).

- `engine/` is the analysis engine, a Kotlin port of `chordify_core` plus a native library
  ([`engine/README.md`](engine/README.md)).
- `app/` is the Compose app over it. `data/` keeps the library: one Room row per analysis in
  `library.db`, the result documents in `files/results/`, the app's recordings in
  `files/recordings/`, and copies of shared files it cannot keep a permission for in
  `files/imports/`. An analysis exported as `.json`, on another phone or by the web app, can be
  imported: it arrives without its audio, which the analysis screen asks for. `work/` runs each
  analysis as a WorkManager job, one at a time, as a foreground service with a progress
  notification. `record/` records from the microphone.
  `music/` ports the web app's `music.ts` and `capo.ts` (chord names, guitar shapes, capo).
  `playback/` plays the analysed file with Media3 under one playback clock and makes its
  waveform. `quick/` is Quick chord, `songwriter/` the Songwriter, and `ui/` holds the other
  screens: the Library, the analysis screen with its timeline and tabs, Settings and About.

Chordify Mobile is free software under the GPL, version 3 or later ([`LICENSE`](LICENSE)),
because it bundles NNLS Chroma (GPL-2.0-or-later). What else it bundles, and under which
licences, is in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Build and test

```bash
cd Mobile
./gradlew :app:assembleDebug               # the app, for arm64-v8a and x86_64
./gradlew :app:testDebugUnitTest            # JVM tests of the app
./gradlew :app:api34DebugAndroidTest        # device tests of the app, on the managed emulator
./gradlew :app:bundleRelease                # the release App Bundle, app/build/outputs/bundle/release/
```

The App Bundle holds the native libraries for `arm64-v8a` and `x86_64`, the two ABIs the
engine is built for, and the store gives each phone only its own: about 77 MB installed on an
arm64 phone, before compression. The bundle is unsigned; sign it with your upload key before
uploading it. The release build is not minified yet. ONNX Runtime and the engine's JNI classes
would need keep rules that no test covers.

`app/src/test/resources/goldens/web.json` holds what the web app's own `music.ts` and `capo.ts`
return for a set of inputs, so the app's port can be checked against them. After changing
either file, regenerate it from the repository root (Node 22) and commit it:

```bash
node --experimental-strip-types tools/web_goldens.mjs
```

The engine's own tests are in [`engine/README.md`](engine/README.md). Gradle 8.10 runs on JDK
17 or 21. The device tests use the Gradle-managed emulator (Pixel 6, API 34, Google APIs,
x86_64). They analyse the engine's golden audio inputs through the app's own library, database
and WorkManager, so a song is analysed end to end exactly as when the app picks it.
