# Mobile M4: the app shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kotlin and Compose app replaces the old Java app. A song picked, shared, opened or recorded is analysed on the phone by a background job, and appears in the Library with its progress, its error or its result.

**Architecture:** One activity with Compose, four tabs (Library, Quick chord, Songwriter, Settings), and an `AppContainer` made once per process. `data/` holds the library: a Room table `analyses`, result documents as JSON files, and a `Library` that imports files, reuses a stored result for the same file, models and options, and deletes. `work/` runs each analysis as a WorkManager job. Jobs wait for one engine, loaded once per process. The running job is a foreground service, and its progress goes to Room, which the screens observe. `record/` records 44.1 kHz WAV from the microphone.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2025.05.00, Material 3), Room 2.7 with KSP, WorkManager 2.10, DataStore 1.1, Navigation Compose 2.8; the engine (`:engine`) from M1–M3.

**Spec:** [`docs/superpowers/specs/2026-11-17-mobile-on-device-design.md`](../specs/2026-11-17-mobile-on-device-design.md), §4.2 and §6.1 (library and background analysis), §6.2 (Library and the bottom navigation), §8 (errors), §9 (M4: "a song can be analysed and appears in the Library").

## Global Constraints

- Everything from the earlier milestones' plans still holds: `minSdk 33`, JVM 17, package `com.arbaba.chordifymobile` (so the new app installs over the old one), `export JAVA_HOME="/c/Program Files/Java/jdk-21"` and `export ANDROID_USER_HOME="D:/android-user"` before every Gradle command, golden files generated and never edited, plain imperative commit messages with the `Co-Authored-By` trailer.
- Everything runs on the phone: the app requests no network permission.
- An analysis's status values are the server's: `queued`, `running`, `completed`, `failed`, `cancelled`.
- Progress stages are the engine's `Stage` ids.
- A failure shows as the web app shows it: "Could not analyse this audio", the engine's message, then "Error code: <code>".
- The same file (SHA-256), model fingerprint and options reuse a completed result (spec §6.1). The fingerprint is the server's `Models.fingerprint`.
- Analyses run one at a time. The running job is a foreground service of type `dataSync` on Android 13 and 14 and `mediaProcessing` from Android 15. Cancelling a job stops the engine. WorkManager runs a job killed with the process again.
- Recording: `AudioRecord` at 44.1 kHz, 16-bit mono, from the `UNPROCESSED` source where the phone supports it, else `VOICE_RECOGNITION`, saved as WAV in `files/recordings/`. Deleting an analysis deletes its recording, after a confirmation.

## What was checked while writing this plan

Every file and test in this plan ran in a scratch copy of the repository:
- The engine has 74 JVM tests; the app has 6 JVM tests and 17 device tests on the managed emulator, all passing.
- The app as Task 2 leaves it (four empty tabs) built and ran its JVM tests on its own.
- The new dependencies resolved and built on the first try.
- In the device tests, a WAV from the engine's golden inputs went through the app's own library, database, WorkManager and engine to a completed row, with the server's key, tempo and chords. A second import of the same file reused the first result. Silence failed with the server's `NO_MUSIC_DETECTED` message. Cancel, retry and delete worked, and so did recording from the emulator's microphone.

The first device run had one failure, a test bug: "Delete" names both the swipe background and the dialog button. The dialog's button has its own test tag now.

**Choices this plan makes where the spec leaves room:**
- Quick chord, Songwriter and Settings are tabs whose screens say "… is not built yet." until M6.
- Tapping a Library row does nothing until M5 adds the analysis screen.
- The `analyses` table also holds the result's duration, key and tempo, so the list need not read every result file.
- A file shared or opened from another app usually comes with a read grant that ends with the activity, so the library copies it into `files/imports/` and deletes the copy with the analysis.
- The waveform peaks (`files/results/<id>.peaks`) come with M5, whose analysis screen draws them.

## File structure

| Path | Responsibility |
|---|---|
| `chordify_ai/export/mobile_goldens.py`, `Mobile/engine/src/test/resources/goldens/analysis.json.gz` | The golden file records the server's model fingerprint |
| `Mobile/engine/.../engine/analysis/Models.kt` | `modelIds`, `modelFingerprint`, `Models.fingerprint` |
| `Mobile/gradle/libs.versions.toml`, `Mobile/build.gradle`, `Mobile/app/build.gradle` | Compose, Room/KSP, WorkManager, DataStore, Navigation; the app's managed emulator |
| `Mobile/app/src/main/java/`, `res/layout`, … | The old Java app, deleted |
| `.../app/ChordifyApp.kt` | `ChordifyApp`, `AppContainer` |
| `.../app/MainActivity.kt` | The activity, the four tabs, "Share to" and "Open with" |
| `.../app/data/Analysis.kt` | `Status`, the `Analysis` row, `AnalysisDao`, `LibraryDatabase` |
| `.../app/data/Library.kt` | Import, reuse, retry, cancel, delete; the job's reports; result files |
| `.../app/data/Settings.kt` | "Major and minor chords only", in DataStore |
| `.../app/work/AnalysisWorker.kt` | `EngineHolder`, `AnalysisWorker`, `WorkJobScheduler`, `AnalysisNotification` |
| `.../app/record/Recorder.kt` | `WavWriter`, `Recorder` |
| `.../app/ui/Theme.kt`, `Labels.kt`, `Formatting.kt` | The web's colour tokens; stage names; summaries |
| `.../app/ui/library/LibraryViewModel.kt`, `LibraryScreen.kt` | The Library tab |
| `Mobile/app/schemas/` | Room's exported schema, version 1 |
| `.github/workflows/ci.yml`, `Mobile/README.md` | The app's tests in CI; how to build and test |

`.../app` stands for `Mobile/app/src/main/kotlin/com/arbaba/chordifymobile`, and `.../engine` for `Mobile/engine/src/main/kotlin/com/arbaba/chordifymobile/engine`. Shell commands run from the repository root in Git Bash unless a step says otherwise.

---

### Task 1: The server's model fingerprint in the engine

**Files:**
- Modify: `chordify_ai/export/mobile_goldens.py`
- Modify (generated): `Mobile/engine/src/test/resources/goldens/analysis.json.gz`
- Modify: `.../engine/analysis/Models.kt`
- Test: `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/FingerprintTest.kt`

**Interfaces:**
- Consumes: `chordify_backend.app.pipeline.models.Models.fingerprint`; `ChordBundle`, `sha256`, `Models` (M3); `JVM_MODELS` (M3b).
- Produces: `analysis.json.gz` gains `fingerprint`. In package `...engine.analysis`: `fun modelIds(bundle: ChordBundle, lmId: String): Map<String, String>`, `fun modelFingerprint(bundle: ChordBundle, lmId: String): String`, `Models.fingerprint`. The app computes the fingerprint from the two `bundle.json` files, without loading either model.

- [ ] **Step 1: Record the fingerprint in the golden file**

In `chordify_ai/export/mobile_goldens.py`, in `analysis_golden`, replace
```python
    return {"environment": _environment() | {"onnxruntime": _version("onnxruntime")}, "models": models.ids,
```
with
```python
    return {"environment": _environment() | {"onnxruntime": _version("onnxruntime")}, "models": models.ids,
            "fingerprint": models.fingerprint,
```
Start Docker Desktop if `docker info` fails, then run `bash tools/mobile_goldens.sh`.
Expected:
- `8 passed`;
- `git status` lists only `mobile_goldens.py` and `analysis.json.gz`;
- `python -c "from chordify_ai.export import mobile_goldens as g; print(g.read_golden(g.DEFAULT_OUT, 'analysis')['fingerprint'])"` prints `517393fe4cb3120c`.

Then quit Docker Desktop: `docker desktop stop`, or from its tray icon if that times out (it did, twice, while this plan was run).

- [ ] **Step 2: Write the failing test**

Create `Mobile/engine/src/test/kotlin/com/arbaba/chordifymobile/engine/analysis/FingerprintTest.kt`:
```kotlin
package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintTest {
    // The app reuses a stored result only for the same file, options and fingerprint, as the
    // server's cache key does (chordify_backend/app/jobs.py).
    @Test
    fun theFingerprintIsTheServers() {
        val expected = Goldens.loadGz("analysis").str("fingerprint")
        assertEquals(expected, JVM_MODELS.fingerprint)
        assertEquals(expected, modelFingerprint(JVM_MODELS.bundle, JVM_MODELS.lmId))
    }
}
```
Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest --tests 'com.arbaba.chordifymobile.engine.analysis.FingerprintTest'`
Expected: compilation fails with `Unresolved reference 'fingerprint'` and `Unresolved reference 'modelFingerprint'`.

- [ ] **Step 3: Compute it**

In `.../engine/analysis/Models.kt`:
- after `import com.arbaba.chordifymobile.engine.acoustic.ChordBundle` add `import com.arbaba.chordifymobile.engine.acoustic.sha256`;
- after `import kotlinx.serialization.json.Json` add `import kotlinx.serialization.json.JsonPrimitive`;
- replace
```kotlin
    /** The result's "models" block, in the server's order. */
    val ids: Map<String, String> = linkedMapOf(
        "chord" to bundle.id, "lm" to lmId, "beats" to BEATS_ID, "features" to bundle.featureSpec.kind,
        "vocabulary" to vocabulary.name,
    )
```
with
```kotlin
    /** The result's "models" block, in the server's order. */
    val ids: Map<String, String> = modelIds(bundle, lmId)

    /** Models.fingerprint: part of an analysis's cache key, so that a new model or feature
     *  revision means a new analysis, never a stale result. */
    val fingerprint: String get() = modelFingerprint(bundle, lmId)
```
- append to the end of the file:
```kotlin
/** The "models" block of a result made with [bundle] and the progression model [lmId]. */
fun modelIds(bundle: ChordBundle, lmId: String): Map<String, String> = linkedMapOf(
    "chord" to bundle.id, "lm" to lmId, "beats" to Models.BEATS_ID, "features" to bundle.featureSpec.kind,
    "vocabulary" to VOCABULARIES.getValue(bundle.vocabulary).name,
)

/** The server's Models.fingerprint without loading either model: the first 16 hex digits of the
 *  SHA-256 of json.dumps(ids | {"feature_revision": revision}, sort_keys=True). */
fun modelFingerprint(bundle: ChordBundle, lmId: String): String {
    val fields = modelIds(bundle, lmId).mapValues { JsonPrimitive(it.value).toString() } +
        ("feature_revision" to bundle.featureSpec.revision.toString())
    val text = fields.toSortedMap().entries.joinToString(", ", "{", "}") { (name, value) -> "${JsonPrimitive(name)}: $value" }
    return sha256(text.toByteArray(Charsets.UTF_8)).take(16)
}
```
Python's `json.dumps(..., sort_keys=True)` writes `{"a": 1, "b": 2}`, with a space after every separator; the text has to match that byte for byte.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :engine:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 74 tests.

- [ ] **Step 5: Commit**

```bash
git add chordify_ai/export/mobile_goldens.py Mobile/engine
git commit -m "Compute the server's model fingerprint in the engine" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: A Kotlin and Compose app in place of the Java app

**Files:**
- Modify: `Mobile/gradle/libs.versions.toml` (replace), `Mobile/build.gradle`, `Mobile/app/build.gradle` (replace)
- Delete: `Mobile/app/src/main/java/`, `Mobile/app/src/test/java/`, `Mobile/app/src/androidTest/java/`, `res/color/`, `res/layout/`, `res/raw/`, `res/wave_anim.json`, `res/drawable/bg_gradient.xml`, `res/drawable/duration_bg.xml`, `res/xml/network_security_config.xml`
- Modify: `Mobile/app/src/main/AndroidManifest.xml` (replace), `res/values/themes.xml`, `res/values-night/themes.xml`, `res/values/colors.xml`, `res/values/strings.xml`
- Create: `res/values-night/colors.xml`, `.../app/MainActivity.kt`, `.../app/ui/Theme.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/MainActivityTest.kt`

(`res/` is `Mobile/app/src/main/res/`.)

**Interfaces:**
- Consumes: nothing from the old app.
- Produces: `MainActivity`; `@Composable fun ChordifyNavigation()` (the four tabs); `@Composable fun ChordifyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`; the version catalog entries the later tasks use (`room.*`, `work.runtime.ktx`, `datastore.preferences`, `compose.*`, `lifecycle.*`, `navigation.compose`, plugins `kotlin.compose` and `ksp`); the managed device `api34` for `:app`.

- [ ] **Step 1: Delete the old app**

```bash
cd Mobile/app/src
git rm -r -q main/java test/java androidTest/java main/res/color main/res/layout main/res/raw main/res/wave_anim.json \
  main/res/drawable/bg_gradient.xml main/res/drawable/duration_bg.xml main/res/xml/network_security_config.xml
cd -
```
The launcher icons (`mipmap-*`, `drawable/ic_launcher_*`) and the backup rules (`xml/backup_rules.xml`, `xml/data_extraction_rules.xml`) stay.

- [ ] **Step 2: Write the failing test**

Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/MainActivityTest.kt`:
```kotlin
package com.arbaba.chordifymobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theFourTabsOpenTheirScreens() {
        compose.onNodeWithText("Library is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Quick chord").performClick()
        compose.onNodeWithText("Quick chord is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Songwriter").performClick()
        compose.onNodeWithText("Songwriter is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Settings is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Library is not built yet.").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Set up the build**

Replace the whole of `Mobile/gradle/libs.versions.toml` with:
```toml
[versions]
agp = "8.8.0"
kotlin = "2.1.0"
kotlinxSerialization = "1.7.3"
onnxruntime = "1.30.0"
ksp = "2.1.0-1.0.29"
coreKtx = "1.15.0"
activityCompose = "1.10.1"
composeBom = "2025.05.00"
lifecycle = "2.8.7"
navigationCompose = "2.8.9"
room = "2.7.1"
work = "2.10.1"
datastore = "1.1.4"
junit = "4.13.2"
junitVersion = "1.3.0"
testRunner = "1.7.0"

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "testRunner" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```
It drops the old app's `appcompat`, `material`, `activity`, `constraintlayout` and `espresso` entries.

In `Mobile/build.gradle`, after `    alias(libs.plugins.kotlin.serialization) apply false`, add:
```groovy
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
```
Replace the whole of `Mobile/app/build.gradle` with:
```groovy
// The Chordify app: Compose screens over the on-device engine (:engine). Everything runs on the
// phone; the app has no network access.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace 'com.arbaba.chordifymobile'
    compileSdk 35

    defaultConfig {
        applicationId 'com.arbaba.chordifymobile'
        minSdk 33
        targetSdk 35
        versionCode 2
        versionName '2.0.0-dev'
        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    buildFeatures {
        compose true
    }
    sourceSets {
        // The device tests analyse the engine's golden audio inputs.
        androidTest {
            resources.srcDirs += '../engine/src/test/resources'
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

ksp {
    arg('room.schemaLocation', "$projectDir/schemas")
}

dependencies {
    implementation project(':engine')
    implementation libs.androidx.core.ktx
    implementation libs.androidx.activity.compose
    implementation platform(libs.compose.bom)
    implementation libs.compose.ui
    implementation libs.compose.ui.tooling.preview
    implementation libs.compose.material3
    implementation libs.compose.material.icons.core
    implementation libs.lifecycle.viewmodel.compose
    implementation libs.lifecycle.runtime.compose
    implementation libs.navigation.compose
    implementation libs.room.runtime
    implementation libs.room.ktx
    ksp libs.room.compiler
    implementation libs.work.runtime.ktx
    implementation libs.datastore.preferences
    debugImplementation libs.compose.ui.tooling
    debugImplementation libs.compose.ui.test.manifest

    testImplementation libs.junit
    androidTestImplementation libs.junit
    androidTestImplementation libs.ext.junit
    androidTestImplementation libs.androidx.test.runner
    androidTestImplementation platform(libs.compose.bom)
    androidTestImplementation libs.compose.ui.test.junit4
}
```

- [ ] **Step 4: Run it to make sure it fails**

Run: `cd Mobile && ./gradlew :app:compileDebugAndroidTestKotlin`
Expected: the build resolves the new dependencies, then fails in `:app:processDebugResources` with `resource style/Theme.Material3.DayNight.NoActionBar ... not found`. The old themes still name the Material Components theme, whose library the new build no longer includes, and Step 5 replaces them.

- [ ] **Step 5: Write the theme, resources and activity**

Replace the whole of `res/values/themes.xml` with:
```xml
<resources>
    <!-- The window behind Compose, in the web app's page colour, until the first frame draws. -->
    <style name="Theme.Chordify" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/page</item>
    </style>
</resources>
```
and of `res/values-night/themes.xml` with:
```xml
<resources>
    <style name="Theme.Chordify" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/page</item>
    </style>
</resources>
```
Replace `res/values/colors.xml` with:
```xml
<resources>
    <color name="page">#F9F9F7</color>
</resources>
```
and create `res/values-night/colors.xml`:
```xml
<resources>
    <color name="page">#0D0D0D</color>
</resources>
```
Replace `res/values/strings.xml` with:
```xml
<resources>
    <string name="app_name">Chordify Mobile</string>
</resources>
```
Replace the whole of `Mobile/app/src/main/AndroidManifest.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Everything runs on the phone: no network permission. -->

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Chordify">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
Create `.../app/ui/Theme.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web app's colour tokens (chordify-frontend/src/index.css), light and dark, following the
// system setting. The harmonic-function hues come back with the analysis screen (M5).

private val Light = lightColorScheme(
    primary = Color(0xFF2A78D6), onPrimary = Color.White, // --tonic
    background = Color(0xFFF9F9F7), onBackground = Color(0xFF0B0B0B), // --page, --ink
    surface = Color(0xFFFCFCFB), onSurface = Color(0xFF0B0B0B), // --surface
    surfaceVariant = Color(0xFFF0EFEC), onSurfaceVariant = Color(0xFF52514E), // --surface-2, --ink-2
    surfaceContainer = Color(0xFFF0EFEC),
    outline = Color(0xFFC3C2B7), outlineVariant = Color(0xFFE1E0D9), // --axis, --grid
    error = Color(0xFFB3261E), onError = Color.White, // --warn
)

private val Dark = darkColorScheme(
    primary = Color(0xFF3987E5), onPrimary = Color.White,
    background = Color(0xFF0D0D0D), onBackground = Color.White,
    surface = Color(0xFF1A1A19), onSurface = Color.White,
    surfaceVariant = Color(0xFF242423), onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainer = Color(0xFF242423),
    outline = Color(0xFF383835), outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFF2B8B5), onError = Color(0xFF0B0B0B),
)

@Composable
fun ChordifyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
```
Create `.../app/MainActivity.kt`:
```kotlin
package com.arbaba.chordifymobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arbaba.chordifymobile.ui.ChordifyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChordifyTheme { ChordifyNavigation() } }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY("library", "Library", Icons.AutoMirrored.Filled.List),
    QUICK_CHORD("quick", "Quick chord", Icons.Filled.Star),
    SONGWRITER("write", "Songwriter", Icons.Filled.Edit),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** The four tabs of spec §6.2. The Library comes with the library screen; Quick chord, Songwriter
 *  and Settings arrive in milestone M6. */
@Composable
fun ChordifyNavigation() {
    val navigation = rememberNavController()
    val current by navigation.currentBackStackEntryAsState()
    Scaffold(bottomBar = {
        NavigationBar {
            for (tab in Tab.entries) {
                NavigationBarItem(
                    selected = current?.destination?.route == tab.route,
                    onClick = {
                        navigation.navigate(tab.route) {
                            popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        }
    }) { padding ->
        NavHost(navigation, startDestination = Tab.LIBRARY.route, modifier = Modifier.padding(padding)) {
            composable(Tab.LIBRARY.route) { NotBuiltYet("Library") }
            composable(Tab.QUICK_CHORD.route) { NotBuiltYet("Quick chord") }
            composable(Tab.SONGWRITER.route) { NotBuiltYet("Songwriter") }
            composable(Tab.SETTINGS.route) { NotBuiltYet("Settings") }
        }
    }
}

@Composable
private fun NotBuiltYet(name: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("$name is not built yet.", style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:assembleDebug :app:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 1 device test passed. KSP finds no Room entities yet, so it writes no schema.

- [ ] **Step 7: Commit**

```bash
git add -A Mobile/gradle/libs.versions.toml Mobile/build.gradle Mobile/app
git commit -m "Replace the Java app with an empty Kotlin and Compose app" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The library's data

**Files:**
- Create: `.../app/data/Analysis.kt`, `.../app/data/Library.kt`, `.../app/data/Settings.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/AnalysisDaoTest.kt`
- Create (generated): `Mobile/app/schemas/com.arbaba.chordifymobile.data.LibraryDatabase/1.json`

**Interfaces:**
- Consumes: `AnalysisOptions`, `AnalysisResult`, `parseAnalysisResult`, `toJson` (M3b).
- Produces (package `com.arbaba.chordifymobile.data`):
  - `object Status` with `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` and `FINISHED`;
  - `@Entity data class Analysis(id, displayName, fileUri, ownsFile, sha256, createdAt, status, stage, progress, errorCode, errorMessage, vocabulary, predictions, minSegmentBeats, modelFingerprint, capo, duration, keyName, bpm)`;
  - `interface AnalysisDao` with `observeAll`, `get`, `findCompleted`, `upsert`, `setProgress`, `setFailed`, `setCompleted`, `setCancelled`, `delete`, and `abstract class LibraryDatabase`;
  - `interface JobScheduler { fun start(id: String); fun cancel(id: String) }`;
  - `class Library(context, dao, jobs: JobScheduler, fingerprint: String, clock = System::currentTimeMillis)` with:
    - `analyses: Flow<List<Analysis>>`, `get(id)`;
    - `import(uri, options, displayName = null): String`, `addRecording(file, displayName, options): String`;
    - `retry(id)`, `cancel(id)`, `delete(id)`, `result(id): AnalysisResult?`, `resultFile(id): File`;
    - the job's reports: `reportProgress(id, stage, progress)`, `reportFailed(id, code, message)`, `reportCancelled(id)`, `reportCompleted(id, result)`;
  - `class Settings(context)` with `majorMinorOnly: Flow<Boolean>`, `setMajorMinorOnly(value)`, `Settings.options(majorMinorOnly): AnalysisOptions`.

- [ ] **Step 1: Write the failing test**

Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/AnalysisDaoTest.kt`:
```kotlin
package com.arbaba.chordifymobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisDaoTest {
    private val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
                                                        LibraryDatabase::class.java).build()
    private val dao = database.analyses()

    private fun row(id: String, createdAt: Long, vocabulary: String? = null, status: String = Status.QUEUED) =
        Analysis(id, "$id.wav", "file:///$id.wav", false, "sha-$id", createdAt, status = status,
                 vocabulary = vocabulary, modelFingerprint = "fp")

    @After
    fun close() = database.close()

    @Test
    fun theNewestAnalysisComesFirst() = runBlocking {
        dao.upsert(row("a", 1))
        dao.upsert(row("b", 3))
        dao.upsert(row("c", 2))
        assertEquals(listOf("b", "c", "a"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun aCompletedAnalysisIsReusedOnlyForTheSameFileModelsAndOptions() = runBlocking {
        dao.upsert(row("plain", 1, status = Status.COMPLETED))
        dao.upsert(row("simple", 2, vocabulary = "majmin", status = Status.COMPLETED).copy(sha256 = "sha-plain"))
        dao.upsert(row("failed", 3, status = Status.FAILED).copy(sha256 = "sha-plain"))
        assertEquals("plain", dao.findCompleted("sha-plain", "fp", null, true, 1)?.id)
        assertEquals("simple", dao.findCompleted("sha-plain", "fp", "majmin", true, 1)?.id)
        assertNull(dao.findCompleted("sha-plain", "other model", null, true, 1))
        assertNull(dao.findCompleted("sha-plain", "fp", null, false, 1))
        assertNull(dao.findCompleted("sha-plain", "fp", null, true, 2))
        assertNull(dao.findCompleted("sha-other", "fp", null, true, 1))
    }

    @Test
    fun aCancelledAnalysisStaysCancelledWhateverTheJobReportsLate() = runBlocking {
        dao.upsert(row("a", 1))
        dao.setProgress("a", Status.RUNNING, "decode", 0.3)
        assertEquals(Status.RUNNING, dao.get("a")!!.status)
        dao.setCancelled("a")
        dao.setProgress("a", Status.RUNNING, "acoustic", 0.6)
        dao.setFailed("a", "INTERNAL", "late")
        dao.setCompleted("a", 10.0, "C major", 120.0)
        val row = dao.get("a")!!
        assertEquals(Status.CANCELLED, row.status)
        assertEquals("decode", row.stage)
        assertNull(row.errorCode)
    }

    @Test
    fun completingAndFailingRecordWhatTheListShows() = runBlocking {
        dao.upsert(row("ok", 1))
        dao.setProgress("ok", Status.RUNNING, "analyse", 0.9)
        dao.setCompleted("ok", 240.0, "G major", 98.5)
        with(dao.get("ok")!!) {
            assertEquals(Status.COMPLETED, status)
            assertEquals("done", stage)
            assertEquals(1.0, progress, 0.0)
            assertEquals("G major", keyName)
            assertEquals(98.5, bpm!!, 0.0)
        }
        dao.upsert(row("bad", 2))
        dao.setProgress("bad", Status.RUNNING, "decode", 0.1)
        dao.setFailed("bad", "DECODE_FAILED", "The file contains no audio samples.")
        with(dao.get("bad")!!) {
            assertEquals(Status.FAILED, status)
            assertEquals("DECODE_FAILED", errorCode)
            assertEquals("The file contains no audio samples.", errorMessage)
        }
        dao.delete("bad")
        assertNull(dao.get("bad"))
    }
}
```
Run `cd Mobile && ./gradlew :app:compileDebugAndroidTestKotlin` and expect `Unresolved reference 'LibraryDatabase'` (and `Analysis`, `Status`).

- [ ] **Step 2: Write the table**

Create `.../app/data/Analysis.kt`:
```kotlin
package com.arbaba.chordifymobile.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** An analysis's lifecycle, as the server names it (chordify_backend/app/schemas.py). */
object Status {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val FINISHED = setOf(COMPLETED, FAILED, CANCELLED)
}

/** One row of the library (spec §6.1). The result itself is a file (ResultStore); the row holds
 *  what the list shows and what the job needs. */
@Entity(tableName = "analyses")
data class Analysis(
    @PrimaryKey val id: String,
    val displayName: String,
    /** content:// for a picked file, file:// for a recording or a copy the app keeps. */
    val fileUri: String,
    /** The file is the app's own (a recording, or a shared file it copied), deleted with the row. */
    val ownsFile: Boolean,
    val sha256: String,
    val createdAt: Long,
    val status: String = Status.QUEUED,
    val stage: String? = null,
    val progress: Double = 0.0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** The options it was asked with: AnalysisOptions.vocabulary, predictions, minSegmentBeats. */
    val vocabulary: String? = null,
    val predictions: Boolean = true,
    val minSegmentBeats: Int = 1,
    val modelFingerprint: String,
    val capo: Int = 0,
    /** From the result, for the list: seconds, "C major", beats per minute. */
    val duration: Double? = null,
    val keyName: String? = null,
    val bpm: Double? = null,
)

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analyses ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Analysis>>

    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun get(id: String): Analysis?

    /** A finished analysis of the same file with the same models and options, whose result can be reused. */
    @Query("""SELECT * FROM analyses WHERE sha256 = :sha256 AND modelFingerprint = :fingerprint
              AND vocabulary IS :vocabulary AND predictions = :predictions AND minSegmentBeats = :minSegmentBeats
              AND status = 'completed' ORDER BY createdAt DESC LIMIT 1""")
    suspend fun findCompleted(sha256: String, fingerprint: String, vocabulary: String?, predictions: Boolean,
                              minSegmentBeats: Int): Analysis?

    @Upsert
    suspend fun upsert(analysis: Analysis)

    // A cancelled row stays cancelled: the job may report once more before it notices.
    @Query("""UPDATE analyses SET status = :status, stage = :stage, progress = :progress
              WHERE id = :id AND status IN ('queued', 'running')""")
    suspend fun setProgress(id: String, status: String, stage: String?, progress: Double)

    @Query("""UPDATE analyses SET status = 'failed', errorCode = :code, errorMessage = :message
              WHERE id = :id AND status = 'running'""")
    suspend fun setFailed(id: String, code: String, message: String)

    @Query("""UPDATE analyses SET status = 'completed', stage = 'done', progress = 1.0, duration = :duration,
              keyName = :keyName, bpm = :bpm WHERE id = :id AND status = 'running'""")
    suspend fun setCompleted(id: String, duration: Double, keyName: String, bpm: Double?)

    @Query("UPDATE analyses SET status = 'cancelled' WHERE id = :id AND status IN ('queued', 'running')")
    suspend fun setCancelled(id: String)

    @Query("DELETE FROM analyses WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Analysis::class], version = 1)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun analyses(): AnalysisDao
}
```
The progress, failed and completed updates only touch a row that is still queued or running. A job that reports once more after its row was cancelled therefore leaves it cancelled.

- [ ] **Step 3: Write the library and the settings**

Create `.../app/data/Library.kt`:
```kotlin
package com.arbaba.chordifymobile.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Starts and stops analysis jobs; WorkManager in the app, something simpler in tests. */
interface JobScheduler {
    fun start(id: String)
    fun cancel(id: String)
}

/** The library of analyses (spec §6.1): rows in Room, result documents as files in app storage,
 *  and the jobs that make them. Picked files are read where they are, through a persisted URI
 *  permission; a shared file the app cannot keep a permission for is copied in. */
class Library(
    private val context: Context,
    private val dao: AnalysisDao,
    private val jobs: JobScheduler,
    private val fingerprint: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val resultsDir = File(context.filesDir, "results")
    private val importsDir = File(context.filesDir, "imports")

    val analyses: Flow<List<Analysis>> = dao.observeAll()

    suspend fun get(id: String): Analysis? = dao.get(id)

    /** Adds the audio at [uri] and starts its analysis; returns the analysis id. An earlier,
     *  completed analysis of the same file with the same models and options is reused. */
    suspend fun import(uri: Uri, options: AnalysisOptions, displayName: String? = null): String = withContext(Dispatchers.IO) {
        val name = displayName ?: displayNameOf(uri)
        val sha256 = sha256(uri)
        reuse(sha256, options)?.let { return@withContext it }
        val id = newId()
        val (fileUri, owns) = keep(uri, id, name)
        add(Analysis(id, name, fileUri.toString(), owns, sha256, clock(), modelFingerprint = fingerprint,
                     vocabulary = options.vocabulary, predictions = options.predictions,
                     minSegmentBeats = options.minSegmentBeats))
        id
    }

    /** Adds one of the app's own recordings, which is deleted with its analysis. */
    suspend fun addRecording(file: File, displayName: String, options: AnalysisOptions): String = withContext(Dispatchers.IO) {
        val id = newId()
        add(Analysis(id, displayName, Uri.fromFile(file).toString(), true, sha256(Uri.fromFile(file)), clock(),
                     modelFingerprint = fingerprint, vocabulary = options.vocabulary, predictions = options.predictions,
                     minSegmentBeats = options.minSegmentBeats))
        id
    }

    /** Analyses a failed or cancelled row again, with the same file and options. */
    suspend fun retry(id: String) {
        val row = dao.get(id) ?: return
        if (row.status !in setOf(Status.FAILED, Status.CANCELLED)) return
        add(row.copy(status = Status.QUEUED, stage = null, progress = 0.0, errorCode = null, errorMessage = null))
    }

    suspend fun cancel(id: String) {
        dao.setCancelled(id)
        jobs.cancel(id)
    }

    /** Removes the row, its result and, if the app owns it, the audio file. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val row = dao.get(id) ?: return@withContext
        jobs.cancel(id)
        dao.delete(id)
        resultFile(id).delete()
        if (row.ownsFile) Uri.parse(row.fileUri).path?.let { File(it).delete() }
    }

    suspend fun result(id: String): AnalysisResult? = withContext(Dispatchers.IO) {
        resultFile(id).takeIf { it.exists() }?.let { parseAnalysisResult(it.readText()) }
    }

    // -- what the analysis job reports ---------------------------------------------------------

    suspend fun reportProgress(id: String, stage: String, progress: Double) = dao.setProgress(id, Status.RUNNING, stage, progress)

    suspend fun reportFailed(id: String, code: String, message: String) = dao.setFailed(id, code, message)

    suspend fun reportCancelled(id: String) = dao.setCancelled(id)

    suspend fun reportCompleted(id: String, result: AnalysisResult) = withContext(Dispatchers.IO) {
        resultsDir.mkdirs()
        val file = resultFile(id)
        val partial = File(resultsDir, "$id.json.part")
        partial.writeText(result.toJson())
        partial.renameTo(file)
        val key = result.key.global
        dao.setCompleted(id, result.source.duration, "${key.tonic} ${key.mode}", result.tempo.bpm)
    }

    fun resultFile(id: String): File = File(resultsDir, "$id.json")

    // -- files ----------------------------------------------------------------------------------

    private suspend fun add(row: Analysis) {
        dao.upsert(row)
        jobs.start(row.id)
    }

    private suspend fun reuse(sha256: String, options: AnalysisOptions): String? =
        dao.findCompleted(sha256, fingerprint, options.vocabulary, options.predictions, options.minSegmentBeats)
            ?.takeIf { resultFile(it.id).exists() }?.id

    /** The URI the job will read, and whether the app owns the file it points to. */
    private fun keep(uri: Uri, id: String, name: String): Pair<Uri, Boolean> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri to false
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri to false
        } catch (_: SecurityException) {
            // Shared or opened from another app: the read grant ends with the activity, so keep a copy.
        }
        importsDir.mkdirs()
        val copy = File(importsDir, "$id.${name.substringAfterLast('.', "audio")}")
        open(uri).use { input -> copy.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(copy) to true
    }

    private fun displayNameOf(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
    }

    private fun sha256(uri: Uri): String = open(uri).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Cannot read $uri")

    private fun newId() = "an_${UUID.randomUUID().toString().replace("-", "").take(20)}"
}
```
Create `.../app/data/Settings.kt`:
```kotlin
package com.arbaba.chordifymobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** The user's defaults, in DataStore (spec §6.1). M6's Settings screen adds the notation. */
class Settings(private val context: Context) {
    /** "Major and minor chords only": new analyses simplify to C and G instead of Cmaj7 and G7. */
    val majorMinorOnly: Flow<Boolean> = context.settingsStore.data.map { it[MAJOR_MINOR_ONLY] ?: false }

    suspend fun setMajorMinorOnly(value: Boolean) {
        context.settingsStore.edit { it[MAJOR_MINOR_ONLY] = value }
    }

    companion object {
        private val MAJOR_MINOR_ONLY = booleanPreferencesKey("major_minor_only")

        /** The options a new analysis is asked with, as the web app sends them. */
        fun options(majorMinorOnly: Boolean): AnalysisOptions =
            if (majorMinorOnly) AnalysisOptions(vocabulary = "majmin") else AnalysisOptions()
    }
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 5 device tests passed (`AnalysisDaoTest` 4). KSP writes `Mobile/app/schemas/com.arbaba.chordifymobile.data.LibraryDatabase/1.json`.

- [ ] **Step 5: Commit**

```bash
git add Mobile/app
git commit -m "Keep the library of analyses in Room and app storage" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Analyses as background jobs

**Files:**
- Create: `.../app/work/AnalysisWorker.kt`, `.../app/ui/Labels.kt`, `.../app/ChordifyApp.kt`
- Modify: `Mobile/app/src/main/AndroidManifest.xml`
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/LabelsTest.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/LibraryDeviceTest.kt`

**Interfaces:**
- Consumes: `Library`, `JobScheduler`, `Status`, `LibraryDatabase`, `Settings` (Task 3); `modelFingerprint`, `Models.readLmBundle`, `Models.LM_BUNDLE` (Task 1, M3b); `ChordifyEngine`, `ChordBundle`, `ChordModel.BUNDLE`, `AudioSource`, `SourceInfo`, `AnalysisOptions`, `Stage`, `CancelledException`, `EngineException` (M3).
- Produces:
  - in `...work`: `class EngineHolder(context)` with `suspend fun <T> withEngine(block: suspend (ChordifyEngine) -> T): T`, `class AnalysisWorker` (input `AnalysisWorker.KEY_ID`), `class WorkJobScheduler(context) : JobScheduler`, `class AnalysisNotification(context, id, title)` with `info(stage, fraction)` and `AnalysisNotification.createChannel(context)`;
  - in `...ui`: `fun stageLabel(stage: String?): String`;
  - in `com.arbaba.chordifymobile`: `class ChordifyApp : Application` with `container: AppContainer`, and `class AppContainer(context)` with `database`, `engine`, `settings`, `fingerprint`, `library`.

- [ ] **Step 1: Write the failing tests**

Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/LabelsTest.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

import com.arbaba.chordifymobile.engine.analysis.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LabelsTest {
    @Test
    fun everyEngineStageHasItsOwnLabel() {
        val labels = Stage.entries.map { stageLabel(it.id) }
        for ((stage, label) in Stage.entries.zip(labels)) assertNotEquals("${stage.id} has no label", "Analysing…", label)
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("Waiting to start…", stageLabel(null))
    }
}
```
Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/data/LibraryDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** M4's "done when" (spec §9): a song is analysed by a background job and appears in the library.
 *  Runs the app's own library, database, WorkManager and engine on the engine's golden audio. */
@RunWith(AndroidJUnit4::class)
class LibraryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = (context.applicationContext as ChordifyApp).container.library
    private val added = ArrayList<String>()

    private fun golden(name: String): Uri {
        val file = File(context.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/$name")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private suspend fun import(name: String, options: AnalysisOptions = AnalysisOptions()) =
        library.import(golden(name), options).also { added += it }

    private suspend fun finished(id: String): Analysis = withTimeout(180_000) {
        library.analyses.first { rows -> rows.single { it.id == id }.status in Status.FINISHED }.single { it.id == id }
    }

    @After
    fun removeWhatTheTestAdded() = runBlocking { for (id in added) library.delete(id) }

    @Test
    fun aSongIsAnalysedAndAppearsInTheLibrary() = runBlocking {
        val id = import("loop.wav")
        assertEquals("loop.wav", library.get(id)!!.displayName)
        val row = finished(id)
        assertEquals(Status.COMPLETED, row.status)
        assertEquals("C major", row.keyName)
        assertEquals(117.5, row.bpm!!, 0.0)
        assertEquals(8.0, row.duration!!, 0.0)
        val result = library.result(id)!!
        assertEquals(id, result.analysisId)
        assertEquals(listOf("C", "G", "Am", "F"), result.chords.map { it.display })
        assertEquals(row.sha256, result.source.sha256)
    }

    @Test
    fun theSameFileWithTheSameOptionsReusesTheResult() = runBlocking {
        val first = import("loop.wav")
        finished(first)
        assertEquals(first, import("loop.wav"))
        val simple = import("loop.wav", AnalysisOptions(vocabulary = "majmin"))
        assertTrue(simple != first)
        assertEquals("majmin", finished(simple).vocabulary)
        assertEquals(1, library.analyses.first().count { it.id == first })
    }

    @Test
    fun silenceFailsWithTheServersMessage() = runBlocking {
        val row = finished(import("silence.wav"))
        assertEquals(Status.FAILED, row.status)
        assertEquals("NO_MUSIC_DETECTED", row.errorCode)
        assertEquals("101% of the audio has no recognisable chord.", row.errorMessage)
        assertNull(library.result(row.id))
    }

    @Test
    fun aCancelledAnalysisStopsAndCanBeStartedAgain() = runBlocking {
        val id = import("pulse.wav")
        library.cancel(id)
        assertEquals(Status.CANCELLED, finished(id).status)
        library.retry(id)
        assertEquals(Status.COMPLETED, finished(id).status)
    }

    @Test
    fun deletingRemovesTheRowAndTheResultButNotAFileTheAppDoesNotOwn() = runBlocking {
        val uri = golden("loop_48k.wav")
        val id = library.import(uri, AnalysisOptions())
        finished(id)
        assertTrue(library.resultFile(id).exists())
        library.delete(id)
        assertNull(library.get(id))
        assertFalse(library.resultFile(id).exists())
        assertTrue(File(uri.path!!).exists())
    }

    @Test
    fun aRecordingIsDeletedWithItsAnalysis() = runBlocking {
        val recording = File(context.filesDir, "recordings/test-recording.wav").apply { parentFile!!.mkdirs() }
        File(golden("loop.wav").path!!).copyTo(recording, overwrite = true)
        val id = library.addRecording(recording, "Recording", AnalysisOptions()).also { added += it }
        assertTrue(library.get(id)!!.ownsFile)
        library.delete(id)
        assertFalse(recording.exists())
    }
}
```
Run `cd Mobile && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` and expect `Unresolved reference 'stageLabel'` and `Unresolved reference 'ChordifyApp'`.

- [ ] **Step 2: Write the stage names**

Create `.../app/ui/Labels.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

/** What the app shows for each progress stage (the web app's words, chordify-frontend Panels.tsx). */
fun stageLabel(stage: String?): String = when (stage) {
    null, "queued" -> "Waiting to start…"
    "decode" -> "Reading the audio…"
    "acoustic" -> "Recognising chords…"
    "key" -> "Working out the key…"
    "decode_chords" -> "Lining chords up with the beat…"
    "analyse" -> "Explaining the progression…"
    "done" -> "Done"
    else -> "Analysing…"
}
```

- [ ] **Step 3: Write the job**

Create `.../app/work/AnalysisWorker.kt`:
```kotlin
package com.arbaba.chordifymobile.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.JobScheduler
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.analysis.Stage
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.ui.stageLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The engine, loaded once per process on first use, and the lock that runs one analysis at a time. */
class EngineHolder(private val context: Context) {
    private val lock = Mutex()
    private var engine: ChordifyEngine? = null

    suspend fun <T> withEngine(block: suspend (ChordifyEngine) -> T): T = lock.withLock {
        val loaded = engine ?: withContext(Dispatchers.Default) { ChordifyEngine.fromAssets(context) }.also { engine = it }
        block(loaded)
    }
}

/** One analysis as a WorkManager job (spec §6.1): queued jobs wait for the engine, the running one
 *  is a foreground service with a progress notification, and progress goes to the library's row.
 *  WorkManager runs a job killed with the process again; cancelling it stops the engine. */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val container = (applicationContext as ChordifyApp).container
        val library = container.library
        return container.engine.withEngine { engine ->
            val row = library.get(id)
            if (row == null || row.status in Status.FINISHED) return@withEngine Result.success()
            val notification = AnalysisNotification(applicationContext, id, row.displayName)
            foreground(notification.info(null, 0.0))
            val progress = MutableStateFlow(Stage.DECODE to 0.0)
            try {
                val result = coroutineScope {
                    val reporter = launch {
                        var shown = -1
                        progress.collect { (stage, fraction) ->
                            library.reportProgress(id, stage.id, fraction)
                            val percent = (fraction * 100).roundToInt()
                            if (percent >= shown + 5 || stage == Stage.DONE) {
                                shown = percent
                                foreground(notification.info(stage, fraction))
                            }
                        }
                    }
                    withContext(Dispatchers.Default) {
                        engine.analyse(
                            AudioSource.FromUri(applicationContext, Uri.parse(row.fileUri)),
                            SourceInfo(id, row.displayName, row.sha256),
                            AnalysisOptions(row.vocabulary, row.predictions, row.minSegmentBeats),
                            onProgress = { stage, fraction -> progress.value = stage to fraction },
                            isCancelled = { isStopped },
                        )
                    }.also { reporter.cancel() }
                }
                library.reportCompleted(id, result)
                Result.success()
            } catch (_: CancelledException) {
                stopped(id)
                Result.failure()
            } catch (error: CancellationException) {
                stopped(id)
                throw error
            } catch (error: EngineException) {
                library.reportFailed(id, error.code.name, error.message ?: error.code.name)
                Result.failure()
            } catch (error: Exception) { // reported as the server reports an unexpected failure
                library.reportFailed(id, "INTERNAL", "${error.javaClass.simpleName}: ${error.message}")
                Result.failure()
            }
        }
    }

    /** Cancelled by the user: the row says so. Stopped by the system: WorkManager runs it again. */
    private suspend fun stopped(id: String) = withContext(NonCancellable) {
        if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
            (applicationContext as ChordifyApp).container.library.reportCancelled(id)
        }
    }

    /** A job started while the app is in the background may not become a foreground service; it
     *  then runs as an ordinary job. */
    private suspend fun foreground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        const val KEY_ID = "analysis_id"
    }
}

/** Starts and cancels AnalysisWorker jobs, one unique job per analysis. */
class WorkJobScheduler(context: Context) : JobScheduler {
    private val work = WorkManager.getInstance(context)

    override fun start(id: String) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(workDataOf(AnalysisWorker.KEY_ID to id)).build()
        work.enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(id: String) {
        work.cancelUniqueWork(name(id))
    }

    private fun name(id: String) = "analysis-$id"
}

/** The progress notification of the running analysis. */
class AnalysisNotification(private val context: Context, id: String, private val title: String) {
    private val notificationId = id.hashCode()

    fun info(stage: Stage?, fraction: Double): ForegroundInfo {
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(stageLabel(stage?.id))
            .setProgress(100, (fraction * 100).roundToInt(), stage == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(notificationId, notification, type)
    }

    companion object {
        const val CHANNEL = "analyses"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Analyses", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Progress of the song being analysed"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
```
Things the code relies on:
- The engine is blocking code. It runs on `Dispatchers.Default`, and `isCancelled = { isStopped }` lets a cancelled job stop it between stages and inside the decoding loop.
- `stopReason` tells a user's cancel (the row says "cancelled") from the system stopping the job (WorkManager runs it again, and the row stays running).
- `setForeground` can be refused to a job that starts while the app is in the background (Android 12 and later). The job then runs without the notification.

- [ ] **Step 4: Write the application and its container**

Create `.../app/ChordifyApp.kt`:
```kotlin
package com.arbaba.chordifymobile

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.arbaba.chordifymobile.data.Library
import com.arbaba.chordifymobile.data.LibraryDatabase
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.Models
import com.arbaba.chordifymobile.engine.analysis.modelFingerprint
import com.arbaba.chordifymobile.work.AnalysisNotification
import com.arbaba.chordifymobile.work.EngineHolder
import com.arbaba.chordifymobile.work.WorkJobScheduler

class ChordifyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AnalysisNotification.createChannel(this)
    }
}

/** The app's long-lived parts, made once per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val database: LibraryDatabase = Room.databaseBuilder(app, LibraryDatabase::class.java, "library.db").build()
    val engine = EngineHolder(app)
    val settings = Settings(app)

    /** The shipped models' fingerprint, read from their bundle.json files without loading them. */
    val fingerprint: String by lazy {
        fun asset(path: String) = app.assets.open(path).use { it.readBytes().decodeToString() }
        modelFingerprint(ChordBundle.parse(asset("${ChordModel.BUNDLE}/bundle.json")),
                         Models.readLmBundle(asset("${Models.LM_BUNDLE}/bundle.json")).first)
    }

    val library: Library by lazy { Library(app, database.analyses(), WorkJobScheduler(app), fingerprint) }
}
```
In `Mobile/app/src/main/AndroidManifest.xml`:
- replace `    <!-- Everything runs on the phone: no network permission. -->` with
```xml
    <!-- Everything runs on the phone: no network permission. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
```
- replace `    <application` with
```xml
    <application
        android:name=".ChordifyApp"
```
- directly above `    </application>` add:
```xml

        <!-- The analysis job's foreground service: dataSync on Android 13 and 14, mediaProcessing from 15. -->
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync|mediaProcessing"
            tools:node="merge" />
```

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest :app:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`; JVM `LabelsTest` 1 passed; 11 device tests passed (`LibraryDeviceTest` 6). `aSongIsAnalysedAndAppearsInTheLibrary` is M4's "done when".

- [ ] **Step 6: Commit**

```bash
git add Mobile/app
git commit -m "Analyse songs in background jobs, one at a time" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Recording

**Files:**
- Create: `.../app/record/Recorder.kt`
- Modify: `Mobile/gradle/libs.versions.toml`, `Mobile/app/build.gradle`, `Mobile/app/src/main/AndroidManifest.xml`
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/record/WavWriterTest.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/record/RecorderDeviceTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (package `...record`): `class WavWriter(file: File, sampleRate: Int) : Closeable` with `write(pcm: ShortArray, count: Int)`, `samples`, and `WavWriter.header(samples, sampleRate): ByteArray`; `object Recorder` with `SAMPLE_RATE`, `source(context): Int`, and `record(context, file, isStopped, onSeconds): Long` (requires `RECORD_AUDIO`).

- [ ] **Step 1: Write the failing tests**

Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/record/WavWriterTest.kt`:
```kotlin
package com.arbaba.chordifymobile.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    @Test
    fun theHeaderDescribesSixteenBitMono() {
        val header = ByteBuffer.wrap(WavWriter.header(1000, 44100)).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int) = String(header.array(), at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", text(0))
        assertEquals(36 + 2000, header.getInt(4))
        assertEquals("WAVE", text(8))
        assertEquals("fmt ", text(12))
        assertEquals(16, header.getInt(16))
        assertEquals(1.toShort(), header.getShort(20)) // PCM
        assertEquals(1.toShort(), header.getShort(22)) // mono
        assertEquals(44100, header.getInt(24))
        assertEquals(88200, header.getInt(28)) // bytes per second
        assertEquals(2.toShort(), header.getShort(32))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals("data", text(36))
        assertEquals(2000, header.getInt(40))
    }

    @Test
    fun samplesAreLittleEndianAndTheSizesAreFilledInOnClose() {
        val file = File.createTempFile("wav", ".wav").apply { deleteOnExit() }
        WavWriter(file, 22050).use { wav ->
            wav.write(shortArrayOf(1, -2, 0x1234), 3)
            wav.write(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 99), 2) // only the first two
            assertEquals(5L, wav.samples)
        }
        val bytes = file.readBytes()
        assertEquals(44 + 10, bytes.size)
        assertArrayEquals(WavWriter.header(5, 22050), bytes.copyOfRange(0, 44))
        val data = ByteBuffer.wrap(bytes, 44, 10).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(1, -2, 0x1234, Short.MAX_VALUE, Short.MIN_VALUE), List(5) { data.getShort() })
    }

    @Test
    fun anEmptyRecordingIsStillAValidFile() {
        val file = File.createTempFile("wav", ".wav").apply { deleteOnExit() }
        WavWriter(file, 44100).close()
        assertArrayEquals(WavWriter.header(0, 44100), file.readBytes())
    }
}
```
Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/record/RecorderDeviceTest.kt`:
```kotlin
package com.arbaba.chordifymobile.record

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecorderDeviceTest {
    @get:Rule
    val microphone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRecordingIsAWavFileOfWhatWasRecorded() {
        val file = File(context.cacheDir, "test-recording.wav")
        val started = SystemClock.elapsedRealtime()
        var seconds = 0.0
        val samples = Recorder.record(context, file, isStopped = { SystemClock.elapsedRealtime() - started > 1500 }) { seconds = it }
        assertTrue("$samples samples for 1.5 s", samples in Recorder.SAMPLE_RATE..3L * Recorder.SAMPLE_RATE)
        assertEquals(44 + 2 * samples, file.length())
        assertArrayEquals(WavWriter.header(samples, Recorder.SAMPLE_RATE), file.readBytes().copyOf(44))
        assertEquals(samples.toDouble() / Recorder.SAMPLE_RATE, seconds, 1e-9)
        file.delete()
    }
}
```
In `Mobile/gradle/libs.versions.toml`, after the `androidx-test-runner` line, add:
```toml
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "testRunner" }
```
In `Mobile/app/build.gradle`, after `    androidTestImplementation libs.androidx.test.runner`, add `    androidTestImplementation libs.androidx.test.rules`.

Run `cd Mobile && ./gradlew :app:testDebugUnitTest` and expect `Unresolved reference 'WavWriter'`.

- [ ] **Step 2: Write the recorder**

Create `.../app/record/Recorder.kt`:
```kotlin
package com.arbaba.chordifymobile.record

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A 16-bit PCM mono WAV file written as samples arrive; the sizes in its header are filled in
 *  when it is closed, so whatever was written before a failure is still a valid file. */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(0, sampleRate)) }

    var samples = 0L
        private set

    fun write(pcm: ShortArray, count: Int) {
        val bytes = ByteArray(2 * count)
        for (i in 0 until count) {
            bytes[2 * i] = pcm[i].toInt().toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        out.write(bytes)
        samples += count
    }

    override fun close() {
        try {
            out.seek(0)
            out.write(header(samples, sampleRate))
        } finally {
            out.close()
        }
    }

    companion object {
        /** The 44-byte RIFF header of a 16-bit mono file with [samples] samples. */
        fun header(samples: Long, sampleRate: Int): ByteArray {
            val dataBytes = 2 * samples
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
                .putInt(2 * sampleRate).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(dataBytes.toInt())
            return header.array()
        }
    }
}

/** Recording from the microphone as the spec asks (§6.1): 44.1 kHz, 16-bit mono, from the
 *  UNPROCESSED source where the phone has one, else VOICE_RECOGNITION. */
object Recorder {
    const val SAMPLE_RATE = 44100

    fun source(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        return if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    /** Records into [file] until [isStopped] returns true, reporting the seconds recorded. Stops
     *  early, keeping what it has, when the file cannot grow (storage full). Returns the samples
     *  written. Blocks: call it off the main thread. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun record(context: Context, file: File, isStopped: () -> Boolean, onSeconds: (Double) -> Unit): Long {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = maxOf(minimum, SAMPLE_RATE / 10) // a tenth of a second
        val record = AudioRecord(source(context), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT, 2 * chunk)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened." }
        val buffer = ShortArray(chunk)
        try {
            WavWriter(file, SAMPLE_RATE).use { wav ->
                record.startRecording()
                while (!isStopped()) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    try {
                        wav.write(buffer, n)
                    } catch (_: IOException) {
                        break // storage full: keep what was recorded
                    }
                    onSeconds(wav.samples.toDouble() / SAMPLE_RATE)
                }
                return wav.samples
            }
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }
}
```
In `Mobile/app/src/main/AndroidManifest.xml`, directly below `    <!-- Everything runs on the phone: no network permission. -->`, add:
```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 3: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest :app:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`; JVM 4 tests (`WavWriterTest` 3); 12 device tests (`RecorderDeviceTest` 1). The emulator's microphone gives silence or the host's sound: either is a valid recording.

- [ ] **Step 4: Commit**

```bash
git add Mobile/gradle/libs.versions.toml Mobile/app
git commit -m "Record from the microphone into a WAV file" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The Library tab, and files shared with the app

**Files:**
- Create: `.../app/ui/Formatting.kt`, `.../app/ui/library/LibraryViewModel.kt`, `.../app/ui/library/LibraryScreen.kt`
- Modify: `.../app/MainActivity.kt` (replace), `Mobile/app/src/main/AndroidManifest.xml`
- Test: `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/FormattingTest.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/LibraryScreenTest.kt`
- Test: `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/MainActivityTest.kt` (replace)

**Interfaces:**
- Consumes: `Library`, `Analysis`, `Status`, `Settings` (Task 3); `ChordifyApp`, `stageLabel` (Task 4); `Recorder` (Task 5); `ChordifyTheme` (Task 2).
- Produces:
  - in `...ui`: `fun summary(analysis: Analysis): String`, `fun formatTime(seconds: Double): String`;
  - in `...ui.library`: `class LibraryViewModel(application)`, `@Composable fun LibraryScreen(model)`, the stateless `@Composable fun LibraryContent(...)`, and `MICROPHONE_REFUSED`;
  - `MainActivity.sharedAudio(intent): Uri?`.

- [ ] **Step 1: Write the failing tests**

Create `Mobile/app/src/test/kotlin/com/arbaba/chordifymobile/ui/FormattingTest.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

import com.arbaba.chordifymobile.data.Analysis
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun aCompletedAnalysisIsSummarisedByKeyTempoAndLength() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "00", 0L, modelFingerprint = "f",
                           duration = 240.4, keyName = "C major", bpm = 117.5)
        assertEquals("C major · 117.5 BPM · 4:00", summary(row))
        assertEquals("A minor · 120 BPM · 0:59", summary(row.copy(keyName = "A minor", bpm = 120.0, duration = 59.99)))
        assertEquals("E major · 1:05", summary(row.copy(keyName = "E major", bpm = null, duration = 65.0)))
    }

    @Test
    fun timesAreMinutesAndSeconds() {
        assertEquals("0:00", formatTime(0.0))
        assertEquals("0:07", formatTime(7.9))
        assertEquals("15:00", formatTime(900.0))
    }
}
```
Create `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/ui/LibraryScreenTest.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.ui.library.LibraryContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = ArrayList<String>()

    private fun row(id: String, status: String, ownsFile: Boolean = false) =
        Analysis(id, "$id.mp3", "file:///$id.mp3", ownsFile, "sha", 0L, status = status, modelFingerprint = "fp")

    private fun show(analyses: List<Analysis>, recordingSeconds: Double? = null, message: String? = null) {
        compose.setContent {
            ChordifyTheme {
                LibraryContent(analyses, majorMinorOnly = false, recordingSeconds = recordingSeconds, message = message,
                               onPick = { events += "pick" }, onRecord = { events += "record" },
                               onMajorMinorOnly = { events += "majorMinorOnly=$it" }, onDismissMessage = { events += "dismiss" },
                               onOpenAppSettings = null, onCancel = { events += "cancel $it" },
                               onRetry = { events += "retry $it" }, onDelete = { events += "delete $it" })
            }
        }
    }

    @Test
    fun anEmptyLibrarySaysWhatToDo() {
        show(emptyList())
        compose.onNodeWithText("Pick a file").performClick()
        compose.onNodeWithTag("record").performClick()
        compose.onNodeWithTag("majorMinorOnly").performClick()
        compose.onNodeWithText("Pick a song or record your instrument", substring = true).assertIsDisplayed()
        assertEquals(listOf("pick", "record", "majorMinorOnly=true"), events)
    }

    @Test
    fun eachStateShowsWhatTheWebAppShows() {
        show(listOf(
            row("running", Status.RUNNING).copy(stage = "decode_chords", progress = 0.8),
            row("done", Status.COMPLETED).copy(keyName = "C major", bpm = 117.5, duration = 8.0),
            row("failed", Status.FAILED).copy(errorCode = "NO_MUSIC_DETECTED", errorMessage = "101% of the audio has no recognisable chord."),
            row("cancelled", Status.CANCELLED),
        ))
        compose.onNodeWithText("Lining chords up with the beat…").assertIsDisplayed()
        compose.onNodeWithText("C major · 117.5 BPM · 0:08").assertIsDisplayed()
        compose.onNodeWithText("Could not analyse this audio").assertIsDisplayed()
        compose.onNodeWithText("101% of the audio has no recognisable chord.").assertIsDisplayed()
        compose.onNodeWithText("Error code: NO_MUSIC_DETECTED").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Analyse").performClick()
        assertEquals(listOf("cancel running", "retry failed", "retry cancelled"), events)
    }

    @Test
    fun recordingShowsItsTime() {
        show(emptyList(), recordingSeconds = 72.4)
        compose.onNodeWithText("Stop and analyse (1:12)").assertIsDisplayed()
    }

    @Test
    fun swipingDeletesAnImportAtOnceAndARecordingAfterAsking() {
        show(listOf(row("song", Status.COMPLETED), row("take", Status.COMPLETED, ownsFile = true)))
        compose.onNodeWithTag("analysis-song").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("delete song"), events)
        compose.onNodeWithTag("analysis-take").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete this analysis?").assertIsDisplayed()
        compose.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf("delete song", "delete take"), events)
    }
}
```
Replace the whole of `Mobile/app/src/androidTest/kotlin/com/arbaba/chordifymobile/MainActivityTest.kt` with:
```kotlin
package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theFourTabsOpenTheirScreens() {
        compose.onNodeWithText("Pick a file").assertIsDisplayed()
        compose.onNodeWithText("Quick chord").performClick()
        compose.onNodeWithText("Quick chord is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Songwriter").performClick()
        compose.onNodeWithText("Songwriter is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Settings is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Pick a file").assertIsDisplayed()
    }

    @Test
    fun sharedAndOpenedAudioIsFound() {
        val song = Uri.parse("content://media/external/audio/media/42")
        assertEquals(song, MainActivity.sharedAudio(Intent(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, song)))
        assertEquals(song, MainActivity.sharedAudio(Intent(Intent.ACTION_VIEW).setDataAndType(song, "audio/mpeg")))
        assertNull(MainActivity.sharedAudio(Intent(Intent.ACTION_MAIN)))
    }
}
```
Run `cd Mobile && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` and expect `Unresolved reference 'summary'`, `'LibraryContent'` and `'sharedAudio'`.

- [ ] **Step 2: Write the summaries**

Create `.../app/ui/Formatting.kt`:
```kotlin
package com.arbaba.chordifymobile.ui

import com.arbaba.chordifymobile.data.Analysis
import java.util.Locale

/** "C major · 117.5 BPM · 4:00" */
fun summary(analysis: Analysis): String = listOfNotNull(
    analysis.keyName,
    analysis.bpm?.let { String.format(Locale.ROOT, "%.1f BPM", it).replace(".0 BPM", " BPM") },
    analysis.duration?.let { formatTime(it) },
).joinToString(" · ")

/** m:ss */
fun formatTime(seconds: Double): String {
    val whole = seconds.toInt()
    return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60)
}
```

- [ ] **Step 3: Write the Library tab**

Create `.../app/ui/library/LibraryViewModel.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.library

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.record.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ChordifyApp).container
    private val library = container.library

    val analyses: StateFlow<List<Analysis>> =
        library.analyses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val majorMinorOnly: StateFlow<Boolean> =
        container.settings.majorMinorOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Seconds recorded so far, or null when not recording. */
    val recordingSeconds = MutableStateFlow<Double?>(null)

    /** A problem to show above the list (a file that could not be read), until dismissed. */
    val message = MutableStateFlow<String?>(null)

    private var stopRecording: AtomicBoolean? = null

    fun setMajorMinorOnly(value: Boolean) {
        viewModelScope.launch { container.settings.setMajorMinorOnly(value) }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            try {
                library.import(uri, options())
            } catch (error: Exception) {
                message.value = "Could not read this file: ${error.message}"
            }
        }
    }

    /** Starts recording; call only with the microphone permission granted. */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (stopRecording != null) return
        val stop = AtomicBoolean(false).also { stopRecording = it }
        val started = Date()
        val file = File(File(getApplication<Application>().filesDir, "recordings").apply { mkdirs() },
                        "recording-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(started)}.wav")
        recordingSeconds.value = 0.0
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Recorder.record(getApplication(), file, isStopped = stop::get) { recordingSeconds.value = it }
                }
                val name = "Recording ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(started)}"
                library.addRecording(file, name, options())
            } catch (error: Exception) {
                file.delete()
                message.value = "Could not record: ${error.message}"
            } finally {
                recordingSeconds.value = null
                stopRecording = null
            }
        }
    }

    fun stopRecording() {
        stopRecording?.set(true)
    }

    fun cancel(id: String) = viewModelScope.launch { library.cancel(id) }

    fun retry(id: String) = viewModelScope.launch { library.retry(id) }

    fun delete(id: String) = viewModelScope.launch { library.delete(id) }

    private suspend fun options() = Settings.options(container.settings.majorMinorOnly.first())
}
```
Create `.../app/ui/library/LibraryScreen.kt`:
```kotlin
package com.arbaba.chordifymobile.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.ui.formatTime
import com.arbaba.chordifymobile.ui.stageLabel
import com.arbaba.chordifymobile.ui.summary

/** The Library tab (spec §6.2): pick a file or record, the "Major and minor only" default, and
 *  every analysis with its progress or error. */
@Composable
fun LibraryScreen(model: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val analyses by model.analyses.collectAsStateWithLifecycle()
    val majorMinorOnly by model.majorMinorOnly.collectAsStateWithLifecycle()
    val recording by model.recordingSeconds.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var microphoneRefused by remember { mutableStateOf(false) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            model.import(uri)
            askForNotifications()
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneRefused = !granted
        if (granted) model.startRecording()
    }

    LibraryContent(
        analyses = analyses,
        majorMinorOnly = majorMinorOnly,
        recordingSeconds = recording,
        message = message ?: if (microphoneRefused) MICROPHONE_REFUSED else null,
        onPick = { pick.launch(arrayOf("audio/*")) },
        onRecord = {
            if (recording != null) {
                model.stopRecording()
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                model.startRecording()
                askForNotifications()
            } else {
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onMajorMinorOnly = model::setMajorMinorOnly,
        onDismissMessage = {
            model.message.value = null
            microphoneRefused = false
        },
        onOpenAppSettings = if (microphoneRefused) {
            {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                             Uri.fromParts("package", context.packageName, null)))
            }
        } else null,
        onCancel = { model.cancel(it) },
        onRetry = { model.retry(it) },
        onDelete = { model.delete(it) },
    )
}

const val MICROPHONE_REFUSED = "Chordify needs the microphone to record. You can allow it in the app's settings."

/** The Library without its view model, so tests can show it in any state. */
@Composable
fun LibraryContent(
    analyses: List<Analysis>,
    majorMinorOnly: Boolean,
    recordingSeconds: Double?,
    message: String?,
    onPick: () -> Unit,
    onRecord: () -> Unit,
    onMajorMinorOnly: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Analysis?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPick, enabled = recordingSeconds == null) { Text("Pick a file") }
                OutlinedButton(onClick = onRecord, modifier = Modifier.testTag("record")) {
                    Text(if (recordingSeconds == null) "Record" else "Stop and analyse (${formatTime(recordingSeconds)})")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Major and minor chords only (C instead of Cmaj7, G instead of G7)", Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = majorMinorOnly, onCheckedChange = onMajorMinorOnly, modifier = Modifier.testTag("majorMinorOnly"))
            }
        }
        if (message != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Row {
                            if (onOpenAppSettings != null) TextButton(onClick = onOpenAppSettings) { Text("Open settings") }
                            TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
        if (analyses.isEmpty()) {
            item {
                Text("Pick a song or record your instrument. Chordify finds the chords, the beat and the key, on your phone.",
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(analyses, key = { it.id }) { analysis ->
            AnalysisRow(analysis, onCancel, onRetry, onDelete = { if (analysis.ownsFile) confirmDelete = analysis else onDelete(analysis.id) })
        }
    }
    confirmDelete?.let { analysis ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this analysis?") },
            text = { Text("The recording of \"${analysis.displayName}\" is deleted with it.") },
            confirmButton = {
                TextButton(onClick = { onDelete(analysis.id); confirmDelete = null }, Modifier.testTag("confirmDelete")) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisRow(analysis: Analysis, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false // the row goes away when the analysis does
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        },
        modifier = Modifier.testTag("analysis-${analysis.id}"),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(analysis.displayName, fontWeight = FontWeight.SemiBold)
                when (analysis.status) {
                    Status.QUEUED, Status.RUNNING -> {
                        Text(stageLabel(analysis.stage), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { analysis.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { onCancel(analysis.id) }) { Text("Cancel") }
                    }
                    Status.COMPLETED -> Text(summary(analysis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Status.FAILED -> {
                        Text("Could not analyse this audio", color = MaterialTheme.colorScheme.error)
                        Text(analysis.errorMessage.orEmpty())
                        Text("Error code: ${analysis.errorCode}", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRetry(analysis.id) }) { Text("Try again") }
                    }
                    Status.CANCELLED -> {
                        Text("Cancelled", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRetry(analysis.id) }) { Text("Analyse") }
                    }
                }
            }
        }
    }
}
```
`LibraryContent` takes plain values and callbacks, so `LibraryScreenTest` can show every state without a database. `LibraryScreen` asks for the microphone before recording. It asks for notifications after the first import, since the job's progress notification needs them on Android 13 and later.

- [ ] **Step 4: Put the Library in its tab, and take shared files**

Replace the whole of `.../app/MainActivity.kt` with:
```kotlin
package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.ui.ChordifyTheme
import com.arbaba.chordifymobile.ui.library.LibraryScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChordifyTheme { ChordifyNavigation() } }
        if (savedInstanceState == null) importShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /** "Share to" (ACTION_SEND) and "Open with" (ACTION_VIEW) an audio file: add it to the library. */
    private fun importShared(intent: Intent) {
        val uri = sharedAudio(intent) ?: return
        val container = (application as ChordifyApp).container
        lifecycleScope.launch {
            try {
                container.library.import(uri, Settings.options(container.settings.majorMinorOnly.first()))
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Could not read this file: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** The audio file an intent hands the app, if it is a share or an "open with". */
        fun sharedAudio(intent: Intent): Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY("library", "Library", Icons.AutoMirrored.Filled.List),
    QUICK_CHORD("quick", "Quick chord", Icons.Filled.Star),
    SONGWRITER("write", "Songwriter", Icons.Filled.Edit),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** The four tabs of spec §6.2. Quick chord, Songwriter and Settings arrive in milestone M6. */
@Composable
fun ChordifyNavigation() {
    val navigation = rememberNavController()
    val current by navigation.currentBackStackEntryAsState()
    Scaffold(bottomBar = {
        NavigationBar {
            for (tab in Tab.entries) {
                NavigationBarItem(
                    selected = current?.destination?.route == tab.route,
                    onClick = {
                        navigation.navigate(tab.route) {
                            popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        }
    }) { padding ->
        NavHost(navigation, startDestination = Tab.LIBRARY.route, modifier = Modifier.padding(padding)) {
            composable(Tab.LIBRARY.route) { LibraryScreen() }
            composable(Tab.QUICK_CHORD.route) { NotBuiltYet("Quick chord") }
            composable(Tab.SONGWRITER.route) { NotBuiltYet("Songwriter") }
            composable(Tab.SETTINGS.route) { NotBuiltYet("Settings") }
        }
    }
}

@Composable
private fun NotBuiltYet(name: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("$name is not built yet.", style = MaterialTheme.typography.bodyLarge)
    }
}
```
In `Mobile/app/src/main/AndroidManifest.xml`, directly below the launcher's `</intent-filter>` line, add:
```xml
            <!-- "Share to" and "Open with" an audio file (spec §6.1) -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="audio/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" android:mimeType="audio/*" />
            </intent-filter>
```

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `cd Mobile && ./gradlew :app:testDebugUnitTest :app:api34DebugAndroidTest`
Expected: `BUILD SUCCESSFUL`; JVM 6 tests; 17 device tests (`LibraryScreenTest` 4, `MainActivityTest` 2).

- [ ] **Step 6: Commit**

```bash
git add Mobile/app
git commit -m "Show the library, pick or record a song, and take shared audio" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The app's tests in CI, and how to build it

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `Mobile/README.md`

**Interfaces:**
- Consumes: the app's tests (Tasks 2–6).
- Produces: CI runs `:app:testDebugUnitTest` in the `android` job and `:app:api34DebugAndroidTest` in the `android-device` job.

- [ ] **Step 1: Run the app's tests in CI**

In `.github/workflows/ci.yml`, in the `android` job, after the step
```yaml
      - name: Engine tests
        run: ./gradlew :engine:testDebugUnitTest
```
add
```yaml
      - name: App tests
        run: ./gradlew :app:testDebugUnitTest
```
In the `android-device` job:
- replace its `    timeout-minutes: 45` with `    timeout-minutes: 60`;
- after the step whose name is `Engine device tests (x86_64 emulator)`, add:
```yaml
      - name: App device tests (x86_64 emulator)
        run: ./gradlew :app:api34DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

- [ ] **Step 2: Say how to build and test**

Create `Mobile/README.md`:
````markdown
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

The engine's own tests are in [`engine/README.md`](engine/README.md). Gradle 8.10 runs on JDK
17 or 21. The device tests use the Gradle-managed emulator (Pixel 6, API 34, Google APIs,
x86_64). They analyse the engine's golden audio inputs through the app's own library, database
and WorkManager, so a song is analysed end to end exactly as when the app picks it.
````

- [ ] **Step 3: Run everything once more**

Run:
```bash
python -m pytest tests/ai/test_mobile_goldens.py -q -p no:cacheprovider
cd Mobile
./gradlew :engine:testDebugUnitTest :app:testDebugUnitTest
./gradlew :engine:api34DebugAndroidTest :app:api34DebugAndroidTest
./gradlew :app:assembleDebug
```
Expected: `5 passed, 3 skipped`; engine 74 and app 6 JVM tests; 28 engine and 17 app device tests; the app builds.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml Mobile/README.md
git commit -m "Run the app's tests in CI and document how to build it" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Done when

- `./gradlew :engine:testDebugUnitTest :app:testDebugUnitTest` (74 and 6 tests) and `./gradlew :engine:api34DebugAndroidTest :app:api34DebugAndroidTest` (28 and 17 tests) pass locally, and CI's `android` and `android-device` jobs pass.
- The old Java app is gone, and the new app installs over it with the same package name.
- `LibraryDeviceTest.aSongIsAnalysedAndAppearsInTheLibrary` passes: a song is analysed by a background job and appears in the Library with the server's key, tempo and chords.
- The same file with the same options reuses its result, a failure shows the server's code and message, and cancel, retry, delete and recording work.

Next: the M5 plan, the analysis screen. It covers the header, Now playing, What comes next, the timeline with the waveform peaks, the transport with Media3, the lead sheet, the circle of fifths, stats, capo and export. Tapping a Library row opens it.
