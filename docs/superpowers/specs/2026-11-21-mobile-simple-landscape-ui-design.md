# A simpler, landscape-first Android app

Status: agreed design, 2026-11-21. Milestone M7 of the
[mobile app](2026-11-17-mobile-on-device-design.md).

## 1. Goal

The Android app ports the web app's screens, and the web's layout was made for a wide desktop
page where everything is visible at once. On a phone it becomes one long scroll in which every
part has the same weight: the analysis screen is four to five screens tall. M7 makes the app as
simple as it can be **without losing any feature**:

- The analysis screen becomes, first of all, a **play-along view**: the chord now, its guitar
  shape, and the chords coming up. Everything else is one tap away.
- The app is **landscape first**: on a sideways phone, the play-along view and one panel of
  details share the screen. Portrait shows the same parts, stacked.
- The **Library is home**. Quick chord, the Songwriter and Settings are one tap away from it.

What the user said, in order: they open a song mainly to **play along**; while playing, the
chord's guitar shape and the chords coming up must be visible without touching the phone; the
app should be mainly landscape, with portrait possible, showing more in landscape; Quick chord
and the Songwriter are used sometimes.

## 2. What stays and what changes

Unchanged: the engine, the library's data (Room rows, result files, imports, recordings), the
analysis jobs, playback and its clock, the music and capo logic (`music/`), export and import,
and every test of them.

Changed: the screens and how you move between them, and these five behaviours:

1. A new setting, **Screen**: Landscape (the default), Portrait, or Follow the phone.
2. The bottom navigation bar goes. The Library is home; its top bar opens Quick chord, the
   Songwriter and Settings.
3. The "Major and minor chords only" switch is no longer repeated in the Library; it stays in
   Settings as the default for new analyses.
4. The Songwriter suggests when it opens and on every change, so its "Suggest the next chord"
   button goes.
5. The suggested capo is starred in the capo menu, instead of a notice with "Use capo N".

The colours stay: the web's tokens, light and dark following the system, and the harmonic
function hues. Cards inside cards go; panels are plain sections.

## 3. The analysis screen

### 3.1 Layout

```
Landscape (wider than tall)                        Portrait
+--------------------------------------------+     +----------------+
| ‹ Cry   G major · 144 BPM · 4/4  Capo ▾  ⋮  |     | ‹ Cry   Capo ▾ ⋮|
+----------------------------+---------------+     +----------------+
|   Em ?          [shape]    | Chord Sheet … |     |  Em    [shape]  |
|   vi · Tonic               |               |     |  vi · Tonic     |
|                            |  (one panel)  |     +----------------+
| [Em][ C ][ D ✦][Bb][ G ]   |               |     | [Em][C][D]  strip|
|     ^ playhead             |               |     +----------------+
+----------------------------+---------------+     | Chord Sheet …   |
| ▶ 1:12 [seek bar: whole song in colours] 4:16 1×▾|  (panel, scrolls)|
+--------------------------------------------+     +----------------+
                                                   | ▶ [seek]   1×▾ |
                                                   +----------------+
```

- **Landscape** (width > height): top bar; then the stage with the strip below it (60% of the
  width) beside the panel (40%); then the transport. The screen itself does not scroll; the panel
  scrolls inside.
- **Portrait**: top bar, stage, strip, panel (taking the remaining height, scrolling inside),
  transport.

### 3.2 Top bar

Back, the title, and the song's real key · tempo · meter (with a capo, the header keeps the real
key, as today). Then:

- **Capo chip** ("Capo: none ▾", "Capo: fret 2 ▾"). It stays in sight because it changes the
  shapes. Its menu lists none and frets 1–9; the suggested fret (`suggestCapo`, when it is not 0)
  is marked "★ N% open chords".
- **⋮ menu**: Letters / Roman numerals (shows the current one), Export chords (.lab), Export
  analysis (.json), Pick the audio file.

### 3.3 Stage

- The chord's name, large: letters or numeral, with the capo's shapes.
- One quiet line under it: the numeral and function ("vi · Tonic"; in Roman mode, the letter
  name and function). With a capo, a second quiet line: "Capo 2 · sounds as F#m".
- A small **?** next to the name only when the model is less than half sure and has
  alternatives; tapping it shows "Unsure: Am or Em also possible".
- The guitar diagram, with its content description, counted from the capo.
- "No chord" shows the words and no diagram.
- The stage is a polite live region (spec §6.3). A swipe goes to the previous or next chord.

### 3.4 Strip (the timeline)

The detail track of today's timeline, compacted to fit beside the panel: 72 dp tall instead of
196 dp, with chord blocks at 70 dp per second under a fixed playhead a third of the way across,
moved by a graphics layer (spec §6.3). The section labels' own lane goes; sections become thin
markers. Each block shows the chord's name and, below it, its numeral (its letter name in Roman
mode). It keeps the timeline's marks:

- function colours, the active chord solid and the others tinted;
- stripes for chords outside the key, and a fainter block for low confidence;
- ✦ on surprising changes;
- beat and downbeat lines, section markers when there are sections;
- the waveform, faint behind the blocks.

The block at the playhead shrinks as it passes: that is the countdown at a glance (the countdown
text is in the Chord panel). Tap or drag to seek.

### 3.5 Transport

Play/pause; the time ("1:12 / 4:16"); the **seek bar**, which is today's overview strip (the
whole song in function colours, section markers, the playhead, a light outline of what the strip
shows; tap or drag to seek); and a speed chip ("1× ▾": 0.5×, 0.75×, 1×, 1.25×).

### 3.6 Panel

Five tabs; the chosen one is kept while the screen is open.

| Tab | Content |
|---|---|
| **Chord** | *Now*: the chord's function in the key ("Em: Tonic in G major"), the scale to play over it, the countdown ("3 beats until the change (1.4 s)"), and the doubt line when unsure. *What comes next*: the predictions with numeral, bar in function colour, percentage and reason ("Most likely · …" first), the chord that really came next ("✓ predicted #1" or "✦ not in the top 3"), and the prediction without the song's history. |
| **Sheet** | The lead sheet: four bars a row, the current bar lit and kept in view, a tap jumps to the bar; "No steady beat was found…" when there are no bars. |
| **Circle** | The circle of fifths, as today. |
| **Stats** | Time per chord (the top ten), repeating progressions, predictability. |
| **Song** | The key, tempo, meter, length and number of chords. The capo notice when a capo is on, and the suggestion's text. The colour legend (tonic, subdominant, dominant, outside the key, surprising). The models, and the keyboard shortcuts. |

### 3.7 No audio

When the file is missing (moved, deleted, or an imported analysis), a slim banner sits over the
top of the stage: "No audio for this song: the chords run silently." with **Pick the file**.
After a different file is picked, the banner says "This file is not the one that was analysed,
so chords may not line up." in the warning colour. The clock runs silently as today.

### 3.8 Kept as today

The analysing, failed and cancelled screens. The keyboard shortcuts (Space, ← →, R). The screen
stays on while playing. Compose state changes only when the chord changes (spec §6.3).

## 4. The rest of the app

### 4.1 Navigation

No bottom bar. The routes are: Library (start), analysis/{id}, Quick chord, Songwriter,
Settings, About. Back returns to the Library. The top bars of Quick chord, Songwriter, Settings
and About have a back arrow.

### 4.2 Library

- Top bar: "Chordify", then Quick chord, Songwriter and Settings (icon and label in landscape,
  icon with a content description in portrait).
- The list: two columns in landscape, one in portrait. Rows as today: title, summary or progress
  or error, tap to open, swipe to delete (with the confirmation for the app's own files), cancel
  and retry. The swipe background is clipped to the row's shape, so it no longer shows at the
  corners.
- **＋ Add** (an extended floating button) opens three choices: Pick a song, Record, Import an
  analysis (.json).
- While recording, a bar with a red dot, the timer and **Stop and analyse** replaces the Add
  button.
- Messages (a file that cannot be read, the microphone refused with "Open settings") show as
  today. The empty Library says what to do in one line.

### 4.3 Quick chord

Landscape: the record button (with the level meter while recording), "Or pick a short clip" and
the errors on the left; the chord, its confidence and its shape on the right. Portrait: the same,
stacked. The instruction is one line.

### 4.4 Songwriter

Landscape: the progression (chips, removed by a tap), the field to add chords and a one-line hint
on the left; the key line ("Assuming C major (87% sure)") and the suggestions on the right, a tap
adding one. The key picker ("Key: from the chords ▾") is in the top bar. Suggestions are asked
for when the screen opens and after every change. Portrait: the same, stacked.

### 4.5 Settings and About

Settings is a plain list:
- Major and minor chords only (switch);
- **Screen**: Landscape, Portrait, Follow the phone;
- Storage (analyses and megabytes);
- About ›.

About keeps everything it has (version, licence, source, third-party notices, dataset credits,
models), as plain sections.

### 4.6 Screen orientation

The setting is stored in DataStore and applied by the activity whenever it starts and whenever
the setting changes:

| Setting | `requestedOrientation` |
|---|---|
| Landscape (default) | `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (either side) |
| Portrait | `SCREEN_ORIENTATION_SENSOR_PORTRAIT` |
| Follow the phone | `SCREEN_ORIENTATION_USER` (honours the phone's rotation lock) |

Rotating recreates the activity; the analysis screen keeps its player, clock and chosen tab
(they live in the view model and in saved state).

## 5. Every feature, and where it goes

| Feature today | M7 |
|---|---|
| Title, key, BPM, meter | Analysis top bar |
| Number of chords | Song panel |
| Capo picker | Capo chip in the top bar |
| Capo suggestion, "Use capo N" | ★ on the suggested fret in the capo menu; its text in the Song panel |
| Capo notice ("Capo on fret N: …") | Song panel; "Capo N · sounds as X" under the chord |
| Letters / Roman toggle | ⋮ menu; R key |
| Export .lab / .json | ⋮ menu |
| File missing, pick the file, mismatch warning | No-audio banner; also ⋮ "Pick the audio file" |
| Chord name, numeral, function | Stage |
| Function in the key, scale hint | Chord panel, *Now* |
| Countdown and progress bar | The strip's shrinking block; the text in the Chord panel |
| "Unsure: … also possible" | "?" on the stage; the line in the Chord panel |
| Guitar diagram | Stage |
| Predictions, reasons, actual next, without history | Chord panel, *What comes next* |
| Timeline detail track | Strip |
| Timeline overview | Transport's seek bar |
| Timeline legend | Song panel |
| Lead sheet, circle of fifths, stats | Sheet, Circle and Stats panels |
| Play, time, speed | Transport |
| Models and shortcuts footnote | Song panel |
| Swipe, keyboard shortcuts, screen kept on, live region | Unchanged |
| Analysing, failed, cancelled screens | Unchanged |
| Library: pick, record, import | ＋ Add |
| Library: "Major and minor only" switch | Settings |
| Library rows: open, delete, cancel, retry, progress, errors | Unchanged |
| Quick chord: record, level, pick a clip, result, errors | Unchanged, in two halves |
| Songwriter: chips, add, key, suggestions, add a suggestion, errors | Unchanged, in two halves; key in the top bar; Suggest button replaced by automatic suggestions |
| Settings: storage; About: everything | Unchanged, as a plain list |
| Sharing or opening audio or a .json export with the app | Unchanged |

## 6. Testing

- The JVM tests of the logic stay as they are. New JVM tests cover the orientation setting's
  mapping.
- The Compose tests are rewritten for the new structure and run at a landscape size (the
  default) and a portrait size:
  - the analysis screen's arrangement in both orientations;
  - the stage, the strip seeking, the seek bar, the speed menu;
  - every panel's content;
  - the starred capo and the transposed shapes;
  - the ⋮ actions;
  - the no-audio and mismatch banners;
  - swipe and keyboard;
  - the Library's ＋ Add menu and recording bar;
  - Quick chord and Songwriter in both arrangements, and the Songwriter suggesting without a
    button;
  - Settings, including Screen.
- `MainActivityTest` checks the navigation (Library home, the three top-bar destinations, About
  from Settings, Back) and that the Screen setting sets the activity's orientation.
- The accessibility test covers every new screen: no unnamed control, the diagrams and charts
  described, the stage and the Quick chord result announced.
- Everything runs on the emulator (CI) and on the Galaxy A30s (Android 11).

## 7. Out of scope

New features; changes to the engine, the analysis or the results; a separate tablet layout (a
tablet uses the landscape layout); new colours or a new visual identity.
