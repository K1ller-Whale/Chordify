# Chordify Mobile

The Android app: every feature of the web app, with the analysis running on the phone and no
network access. Design:
[`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../docs/superpowers/specs/2026-11-17-mobile-on-device-design.md).

- `engine/` is the analysis engine, a Kotlin port of `chordify_core` plus a native library
  ([`engine/README.md`](engine/README.md)).
- `app/` is the Compose app over it. `data/` keeps the library: one Room row per analysis in
  `library.db`, the result documents in `files/results/`, the app's recordings in
  `files/recordings/`, and copies of shared files it cannot keep a permission for in
  `files/imports/`. `work/` runs each analysis as a WorkManager job, one at a time, as a
  foreground service with a progress notification. `record/` records from the microphone, and
  `ui/` holds the screens.

## Build and test

```bash
cd Mobile
./gradlew :app:assembleDebug               # the app, for arm64-v8a and x86_64
./gradlew :app:testDebugUnitTest            # JVM tests of the app
./gradlew :app:api34DebugAndroidTest        # device tests of the app, on the managed emulator
```

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
