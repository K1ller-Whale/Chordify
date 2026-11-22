# Correcting the chords on the phone

Status: built, 2026-11-22. Milestone M8 of the
[mobile app](2026-11-17-mobile-on-device-design.md), building on M7's
[landscape-first UI](2026-11-21-mobile-simple-landscape-ui-design.md).

## 1. Goal

The model is often right about a chord in one place and wrong about the same chord when the
progression comes back. M8 lets the player correct the analysis by hand:

- change **which chord** plays at a given time;
- change **where it starts and ends**;
- change **the guitar shape** the app shows for a chord.

Corrections travel with the analysis. The `.json` export carries them, and importing that file on
another phone loads the corrected chords and shapes.

Every correction is made by hand. The app does not apply a fix anywhere on its own.

## 2. What the player sees

### 2.1 Editing mode

- **Entering.** A **✎** button in the analysis top bar, next to the capo chip, starts **Editing**.
- **Top bar while editing.** It shows "✎ Editing", **Undo**, **Redo**, **Cancel** and **Done**.
  - **Done** saves the session's changes.
  - **Cancel** discards them, after asking if there are any.
- **Leaving.** Back and the system Back gesture behave as Cancel. A rotation keeps the session; it
  does not end it.
- **Playback.** Playback works as usual while editing. A **Loop chord** button in the transport
  replays the selected chord until it is pressed again.
- **Restore.** The ⋮ menu gains **Restore the model's chords**. It is shown only when the analysis
  has edits. After a confirmation ("Your chords and shapes for this song will be replaced by the
  model's. This cannot be undone."), it removes every edit and custom shape.

### 2.2 Selecting a chord

- **The strip.** In Editing the strip grows from 72 dp to about 110 dp. Tapping a block selects it:
  it gets an outline and two drag handles, one on each edge, in the text colour (dark in the light
  theme, white in the dark theme).
- **The side panel.** It takes the place of the five tabs while editing, in both orientations, and
  shows:
  - the chord's name, with its numeral and function;
  - **Starts** and **Ends** as exact times (m:ss.cc), each with **−beat** and **+beat**;
  - **Set start to here** and **Set end to here**, which use the playhead;
  - **Split here**, which splits the selected chord at the playhead;
  - **Merge with next**;
  - **Change chord…** and **Edit shape…**.
- **The stage.** It keeps showing the selected chord's name, numeral and shape, and follows
  playback when nothing is selected.

### 2.3 Timing rules

- **Chords always touch.** The song is covered from 0 to its end by chords with no gaps and no
  overlaps. Silence is a "No chord" (N.C.) chord like any other. Moving one chord's end moves the
  next chord's start with it.
- **Snapping.** While dragging, an edge snaps to the nearest beat when one is within 6 dp; otherwise
  it moves freely. The panel's times and ±beat buttons give exact control.
- **Removing.** If an edge reaches the neighbouring edge, the chord in between is removed. So is a
  chord shorter than 50 ms.
- **Merging.** Two neighbouring chords with the same label become one after a rename, or when
  removing a chord leaves them touching. A split's two halves stay separate until one is renamed
  or **Merge with next** joins them, so the new edge can be dragged into place first.
- **Song bounds.** Times are clamped to [0, song length].

### 2.4 Change chord…

A picker in the style of M7's screens:

- **Suggested.** Up to six chords:
  - the model's own alternatives for this spot (`ChordSegment.alternatives`);
  - the chords that usually follow the previous chord, from the progression model, as the
    Songwriter asks for them;
  - no duplicates, and not the current chord.
- **Root.** C, C♯, D, E♭, E, F, F♯, G, A♭, A, B♭, B.
- **Type.** major, minor, 7, maj7, m7, 6, m6, sus2, sus4, dim, aug, 9, add9.
- **Bass.** "none" or any of the twelve notes, for a slash chord.
- **No chord.** Sets N.C.
- **Preview.** A panel shows the chosen chord's name, numeral and function in the song's key, and
  its shape.
- **Confirming.** **Use ‹chord›** applies the choice; **Cancel** closes the picker.

The picker shows names as the player sees them: with a capo on, they are the capo shapes (§2.6).
Names come only from the picker. Typing a name is out of scope (§6).

### 2.5 Edit shape…

- **The fretboard.** Six strings (E A D G B e) by four frets.
  - Tapping a fret on a string puts a finger there. Tapping that finger again removes it.
  - Tapping above the nut cycles that string between **open ○** and **muted ×**.
  - **▲ higher** and **▼ lower** move the four-fret window up and down the neck. The starting fret
    is shown, from 1 to 12.
- **Scope.** The shape applies to **every chord with that name in this song**. Other songs keep
  the app's default.
- **Default shape.** Returns that chord name to the app's shape.
- **Strum it.** Plays the shape's notes from low to high as a short synthesized plucked string:
  Karplus–Strong through `AudioTrack`, about 1.5 s, and independent of the song's playback.
- **A non-blocking check.** If the shape lacks a note of the chord, for example "This shape has no
  B, the 3rd of G", a line in the muted colour says so. Saving is never blocked.
- **Chords without a default shape.** They show an empty fretboard to fill in.

### 2.6 Capo

- **What is stored.** Chords are stored as they sound, without the capo.
- **What the editor shows.** It shows them as the stage does: the capo shapes. A chord picked with
  a capo on is converted back to its sounding chord, spelled in the song's key as the model spells
  it (C♯m in A major, even when the capo shapes are in A♭).
- **Shapes.** Custom shapes are fingerings, so they are keyed by the **shape** chord: what the
  player sees under the capo, as a Harte label such as `G:maj`. A custom G shape is used wherever
  the stage shows a G, whatever the capo.

### 2.7 After a change

After every edit, the following are recalculated from the edited chords:

- numerals, functions and scale hints;
- "What comes next" and the "✦ surprising" marks;
- the lead sheet's bars;
- the circle of fifths;
- the stats.

The song's **key stays as analysed**.

An edited chord counts as certain: it has no "?" doubt badge and no "also possible" line, and it
carries a small "edited" mark in the strip and on the lead sheet.

## 3. Data

### 3.1 On the phone

- **The model's result.** `filesDir/results/<id>.json` is never modified by editing.
- **The edits file.** Edits live beside it, in `filesDir/results/<id>.edits.json`:

  ```json
  {
    "version": 1,
    "chords": [ { "start": 0.0, "end": 2.31, "label": "C:maj", "edited": false }, ... ],
    "shapes": { "G:maj": [3, -1, 0, 0, 3, 3] }
  }
  ```

  - `chords` covers the whole song, under the rules of §2.3, and holds every chord, including the
    unedited ones. A chord is `edited` when the player changed its name or either of its edges.
  - `shapes` maps a shape label (§2.6) to six frets, E to e, where −1 is muted and 0 is open. These
    are the app's `Shape` values.
- **When it is written.** **Done** writes the file atomically (`.part` then rename). **Restore**
  deletes it.
- **Loading.** When an analysis opens, `AnalysisViewModel` loads the model's result and then, if
  the edits file exists, the rebuilt result (§3.2). The screen shows the rebuilt one.
- **Library rows.** They show the key and tempo of the result. Both stay as analysed (§2.7), so
  rows do not change.
- **Adding the same song again.** Adding a file whose completed analysis already exists (same
  SHA-256, models and options) does not make a copy: it opens that analysis, with its edits.

### 3.2 Rebuilding from chords

The engine gains one public, pure function:

```kotlin
fun rebuild(result: AnalysisResult, chords: List<ChordSpan>, models: Models): AnalysisResult
data class ChordSpan(val start: Double, val end: Double, val label: String, val edited: Boolean)
```

It uses the parts of `analyseMeasured` that do not need audio, extracted from it so that both share
one implementation:

- **The chord entries.** Display name, root, bass, quality, numerals, function and scale hint come
  from `describe` in the result's global key.
- **Predictions.** It recomputes `next`, `theoryOnly` and `surprise` with the progression model, as
  today, but only if the result had predictions; an analysis made without them stays without.
- **Other fields.** It also recomputes `bars[].chords` (from the existing downbeats) and
  `summary.uniqueChords`, `timeShare`, `patterns` and `predictability`.
- **Unchanged fields.** It keeps `key`, `tempo`, `beats`, `downbeats`, `sections`, `source`,
  `models` and `schemaVersion`.
- **Confidence.** An unedited span equal to a model segment keeps that segment's `confidence` and
  `alternatives`. An edited span gets `confidence = 1.0` and no alternatives.
- **Chords outside the vocabulary.** A label outside the analysis's vocabulary tier is described
  from its own root and quality instead of the tier's encoding. So an add9 in a "major/minor" song
  still gets its name, numeral and shape.

`ChordSegment` gains `edited: Boolean = false` (JSON `edited`).

### 3.3 Export and import

- **Export chords (.lab).** It writes the shown chords, so the edits.
- **Export analysis (.json).** It writes the **rebuilt** result, so any reader sees the corrected
  chords, bars and stats, plus a top-level `edits` object:

  ```json
  "edits": {
    "version": 1,
    "original_chords": [ { ...the model's ChordSegment, as stored... }, ... ],
    "shapes": { "G:maj": [3, -1, 0, 0, 3, 3] }
  }
  ```

  An unedited analysis exports exactly as today, with no `edits`.
- **Import.** `parseExport` accepts `edits`. When it is present:
  - the model's result is `rebuild(file, original_chords)`, stored as `<id>.json`;
  - the edits file is built from the file's chords, with their `edited` flags, and its shapes.

  The imported song then opens corrected, and **Restore the model's chords** works there too.
- **Compatibility.**
  - An `edits` object that is not an object, or whose chords do not cover the song, is refused with
    "This export's edits are damaged."
  - Older versions of the app cannot read `edits`, so both phones need M8.
  - The web app and the server ignore unknown fields. Nothing is sent to the server, and its
    `/corrections` endpoint stays unused.

## 4. Errors

- **Damaged edits file.** A file that cannot be read opens the analysis with the model's chords and
  a banner: "Your edits for this song could not be loaded." The file is left in place. If the player
  later saves or clears the song's edits, it is first moved aside to `<id>.edits.damaged.json`, so
  nothing is destroyed; deleting the analysis deletes that file too.
- **Edits that cannot be rebuilt.** A file that reads fine but whose chords cannot be rebuilt right
  now (for example, when a model fails to load) is not damage. The model's chords show with a short
  message, "Could not update the chords: …", **Restore** stays available, and **✎** starts editing
  from the saved edits, rebuilding them again.
- **Other messages.** A failed **Restore** ("Could not restore the model's chords: …") and an editing
  session that cannot start because its first rebuild fails are told in a short message at the
  bottom of the screen.
- **Spans out of range.** Spans beyond the song's length, for example from an import, are clipped.
  A song whose spans would leave a gap is filled with N.C.
- **A failed save.** Writing the edits file is atomic. If it fails, Editing stays open with "Could
  not save your changes: …" and nothing is lost.

## 5. Testing

- **Engine (JVM).**
  - `rebuild` with the model's own chords returns the original result for every golden.
  - Renaming one chord changes its numeral, function, the bars, the stats and its neighbours'
    predictions, as the model would.
  - An out-of-vocabulary label is described.
  - Confidence and alternatives behave as §3.2 says.
- **Edit operations (JVM, pure).** Moving an edge, snapping, splitting, merging, removal by
  collapse, clamping, merging same-name neighbours, and undo/redo.
- **App UI (JVM, Robolectric).**
  - Entering Editing.
  - Selecting a chord and the side panel's values.
  - ±beat and "set to here".
  - Dragging an edge.
  - The picker: suggestions first, and a root/type/bass choice producing the right label.
  - The shape editor: fingers, open/muted, the window, the missing-note line and the default.
  - Undo/Redo, Cancel, Done and Restore.
  - Both orientations, the capo case, and the accessibility helpers.
- **Export and import (JVM).**
  - An edited analysis is exported, imported as a new song, and gives the same chords, shapes and
    edited marks.
  - Restore on the imported song gives the model's chords.
  - The .lab output holds the edits.
  - An unedited export is byte-for-byte as before.
- **Device (emulator).** Edit, Done, leave, reopen, then export, import and open.
- **Phone.** A hand check on the Galaxy A30s.

## 6. Out of scope

- Typing chord names, and free-text names.
- Applying one fix to repeats automatically.
- Editing the key, tempo, beats or sections.
- A shape library shared across songs.
- Sending corrections to the server, and syncing between phones other than by export/import.
- Using corrections to train the model.
