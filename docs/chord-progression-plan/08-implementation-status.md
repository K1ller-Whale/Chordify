# 08 · Implementation status

What this PR builds from the plan, what was measured along the way, where the build
differs from the plan and why, and what is left. Everything below runs from a fresh
clone: see [§6](#6-running-it).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/app-desktop-dark.png">
  <img alt="The analysis screen of the rebuilt web app" src="assets/app-desktop-light.png">
</picture>

*The built analysis screen (not the mockup), on a real recording: GuitarSet take
`00_Rock1-90-C#_comp` (CC BY 4.0), analysed by the v2 API with NNLS chroma,
`chordnet-chroma@2.0.0` and the n-gram progression model. The chords C#–F#–C#–G#–F#–C#
come out right, spelled Db–Gb–Db–Ab–Gb–Db in the key the app found (Db major), and the
next chord is predicted correctly. The tempo is not: 123 BPM for a 90 BPM take, a known
weakness of the beat tracker ([§4](#4-where-the-build-differs-from-the-plan)).*

## 1. Status by phase

| Phase | Built in this PR | Still open |
|---|---|---|
| **0 · Foundations** | `chordify_core` package. One feature definition shared by training and serving: NNLS at 44.1 kHz/2048, plus a plugin-free CQT chroma. Golden tests. Billboard ingestion. Frozen artist-grouped splits. `mir_eval` harness. Backend hygiene (settings, CORS allow-list, problem+json, no files on disk). Configurable API URL in the web app. CI. | Chordino baseline and honest v1 numbers on the test split. The Chordify-Live recordings. |
| **1 · Full-song v2** | ChordNet in PyTorch (Conformer and BiGRU, every head), training, ONNX export with a parity check, model bundles. Beat-synchronous Viterbi decoder. API v2 with jobs, SSE, cache and v1 adapter. TypeScript web app with upload, progress, timeline and playback sync. **`chordnet-chroma@3.0.0`, which names 14 chord types and inversions, served by default** ([§3.4](#34-acoustic-model), [§3.5](#35-every-chord-type-chordnet-chroma300)). | Beat This! (librosa's tempo can be off, e.g. 123 BPM on a 90 BPM take). Celery, Postgres and object storage ([§4](#4-where-the-build-differs-from-the-plan)). |
| **2 · Progressions** | Theory engine: Roman numerals, functions, cadences, secondary dominants, borrowed chords, named loops, scale hints. n-gram + song-cache model: `/progressions/next`, predictions, surprise and predictability in every result, and a change matrix in the decoder. Web: Now/Next, circle of fifths, time per chord, repeating progressions, lead sheet, Roman/Letters, Songwriter. Corrections endpoint. | The ProgressionLM Transformer (Chordonomicon). Local keys and modulations. Practice mode. Corrections UI. |
| **3–5** | Nothing yet beyond what they reuse: vocabulary tiers up to 169 classes, the `log_cqt` feature spec, the AudioWorklet recorder. | All. |

## 2. Code map

```mermaid
flowchart LR
  AI["<b>chordify_ai</b> · offline<br/>Billboard ingestion, frozen splits<br/>ChordNet training, ONNX export<br/>n-gram training, evaluation"]
  CORE["<b>chordify_core</b> · shared<br/>vocab · features · acoustic<br/>decode · theory · lm"]
  MODELS[("<b>models/</b><br/>chord bundle<br/>n-gram bundle")]
  BE["<b>chordify_backend</b> · FastAPI<br/>analysis pipeline<br/>jobs, cache, SSE events"]
  W["<b>chordify-frontend</b><br/>React + TypeScript"]
  CORE -->|"imported by"| AI
  CORE -->|"imported by"| BE
  AI -->|"writes bundle.json<br/>+ weights"| MODELS
  MODELS -->|"loaded at startup"| BE
  BE <-->|"REST v2 · SSE"| W
```

| Where | What |
|---|---|
| [`chordify_core/`](../../chordify_core) | Runtime library: `vocab`, `audio`, `features`, `acoustic`, `decode`, `theory`, `lm`, `synth` (test renders) |
| [`chordify_ai/`](../../chordify_ai) | Billboard ingestion and splits, ChordNet training, ONNX export, n-gram training, evaluation |
| [`chordify_backend/`](../../chordify_backend) | API v2 + v1 adapter; [README](../../chordify_backend/README.md) lists endpoints and settings |
| [`chordify-frontend/`](../../chordify-frontend) | Web app; [README](../../chordify-frontend/README.md) lists screens and the code map |
| [`data/splits/billboard_v1.json`](../../data/splits/billboard_v1.json) | Frozen split (train 712 / validation 89 / test 89) |
| [`models/progression-ngram/1.0.0/`](../../models/progression-ngram/1.0.0) | The shipped progression model (bundle + 23 KB of counts) |
| [`tests/`](../../tests) | 1,684 tests: `core`, `ai`, `backend` |
| [`tools/install_nnls_chroma.sh`](../../tools/install_nnls_chroma.sh) | Builds the NNLS Chroma / Chordino Vamp plugins from source (≈15 s) |
| [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) | CI: Python tests with the plugin built, web lint + typecheck + build |

## 3. What was measured

### 3.1 v1's train/serve skew is real, and there was a second offset

- Running the NNLS plugin on 22.05 kHz audio, as v1's backend does, gives 93 ms frames. v1 was trained on 46 ms frames. `features.nnls_bothchroma` now resamples to 44.1 kHz first and refuses any other frame step.
- The Vamp host stamps each frame at the **centre of its 16,384-sample block**, so frame 0 is at 0.186 s, not 0. Without this offset every chord boundary came out 0.186 s early, and beat-synchronous pooling then moved many changes a whole beat early. `FeatureSpec.offset` now carries it through targets, the decoder and evaluation. The plugin-free CQT chroma is centred at 0.

### 3.2 Data

- **890 Billboard songs** come from ChoCo (JAMS chords) plus the original `salami_chords.txt` (metre, tonic, sections, bars). No Kaggle login is needed for annotations.
- **One annotation is corrupt:** ChoCo's 0974 ("Kokomo") has overlapping intervals. The loader validates every track and drops it, and the training scripts skip it.
- **Split:** artist-grouped (primary artist ∪ repeated title + artist), 429 groups, seed 20261005. Models record the split file's SHA-256 in their bundle.

### 3.3 Progression model: the gate ProgressionLM has to beat

Key-relative 4-gram with a song cache (`progression-ngram@1.0.0`), trained on 711 train songs:

| Frozen split | Top-1 | Top-3 | Corpus only (no song cache), top-1 / top-3 | First chord change of a song, top-1 / top-3 |
|---|---|---|---|---|
| Validation (8,656 transitions) | 82.0 % | 93.3 % | 41.8 / 70.8 % | 54.9 / 83.2 % |
| **Test (8,272 transitions)** | **81.1 %** | **93.2 %** | 46.1 / 75.7 % | 58.6 / 78.3 % |

The phase 2 gate for ProgressionLM (top-1 ≥ 82 %, top-3 ≥ 94 %) stays, now measured against these numbers on the frozen test split rather than the random split used in [02](02-datasets.md#25-progressions-are-predictable-and-even-more-so-within-a-song).

### 3.4 Acoustic model

The ChordNet stack is implemented and tested end to end:

- targets with transposition augmentation;
- a multi-task loss;
- EMA and cosine LR;
- early stopping on `mir_eval` WCSR;
- ONNX export whose output matches PyTorch to within 1e-3 at any length;
- windowed inference in serving.

**Real data.** `tools/get_billboard_data.sh` downloads the annotations and McGill's own feature archive (`billboard-2.0-chordino`, no login). Its CSVs stamp each frame at the *start* of its block. On every song the file has exactly our extractor's frames minus the final two, and template chords agree with the annotations best at +0.09–0.14 s rather than at 0. So CSV frame *i* is our frame *i*, centred 0.186 s after its stamp, and the loader returns centre times.

[GuitarSet](https://zenodo.org/records/3371780) (Xi et al., 2018, CC BY 4.0) adds real recordings: 180 accompaniment takes on acoustic guitar, recorded with a microphone, with lead-sheet chords. Unlike Billboard's precomputed features, `chordify_ai.eval.guitarset` runs the whole serving path, including our own feature extraction from the audio.

majmin WCSR on real music, scored as in MIREX: the time labelled right over the time each level can compare. Before 3.0.0 our harness weighted every level by each song's full length and counted a song with nothing comparable as 0, which read about 0.8 points lower on Billboard; every number below uses the corrected scoring. GuitarSet is scored on the takes of player 05 only, whom no model trained on (3.0.0 trained on players 00–03), against the chords as played and against the lead sheet:

| Model | Billboard validation (89 songs) | Billboard test (89) | GuitarSet test, as played (30 takes) | GuitarSet test, lead sheet |
|---|---|---|---|---|
| templates 0.2.0 (decoder tuned on validation) | 66.2 % | 71.9 % | 70.6 % | 54.6 % |
| `chordnet-chroma@2.0.0` (Billboard, major/minor only) | **81.8 %** | 81.3 % | 78.6 % | 67.9 % |
| **`chordnet-chroma@3.0.0`** (every chord type), **served by default** | 81.4 % | **82.3 %** | **80.9 %** | **72.6 %** |

Before its decoder was tuned, the template model scored 61.1 % and 68.2 % on Billboard validation and test (old weighting). With the plugin-free CQT chroma instead of NNLS it loses about 10 points on GuitarSet.

ChordNet-Chroma 2.0.0 is a 3.6 M-parameter Conformer trained on the 711 Billboard training songs, on a MacBook with an M5 Pro through the Apple GPU, at 42 s per epoch; early stopping ended the run at epoch 17 and kept epoch 11 ([`chordify_ai/TRAINING.md`](../../chordify_ai/TRAINING.md)). It only knows major and minor: G7 comes out as G. 3.0.0 is the same network trained for every chord type on four sources ([§3.5](#35-every-chord-type-chordnet-chroma300)). For each model the decoder was tuned on validation and the test split scored once. The server picks the shipped model by itself where the NNLS plugin works (`CHORDIFY_CHORD_MODEL=auto`) and falls back to the templates elsewhere.

GuitarSet test by style (majmin, chords as played, 6 takes per style):

| | singer-songwriter | rock | bossa nova | jazz | funk |
|---|---|---|---|---|---|
| templates 0.2.0 | 94.5 % | 87.1 % | 57.5 % | 38.0 % | 31.2 % |
| ChordNet 2.0.0 | 94.6 % | 91.6 % | **76.9 %** | 60.4 % | **46.1 %** |
| ChordNet 3.0.0 | **99.0 %** | **96.8 %** | 52.6 % | **76.2 %** | 43.2 % |

ChordNet gains the most where the templates struggled: quiet, jazzy comping. 3.0.0 adds 16 points on jazz, but loses 24 on bossa nova. Bossa nova is full of sixth chords, and C6 (C E G A) has exactly the notes of Am7. 3.0.0 often picks the m7, which `majmin` counts as the wrong root. The recordings go through our own NNLS extraction, not McGill's CSVs, so this also confirms that serving produces the features the models were trained on.

One caveat seen while checking the demo song. On a chord with no third (A–D–E–G, an A7sus4), the templates and ChordNet disagree about major versus minor. The audio can't settle that, and `majmin` scoring leaves such chords out.

**Synthetic renders** of the validation annotations (25 songs, first 90 s, each render with its own timbre and tuning within ±30 cents), measured with the 0.1.0 template settings:

| Model | Features | majmin | sevenths | seg |
|---|---|---|---|---|
| `chroma-templates@0.1.0` | NNLS | 98.2 % | 67.3 % | 0.90 |
| `chroma-templates@0.1.0` | CQT (revision 2) | 97.0 % | 66.1 % | 0.87 |
| ChordNet Conformer (3.6 M parameters), trained on 150 other synthetic songs, best of 10 epochs, served from ONNX | CQT (revision 2) | 99.3 % | 67.7 % | 0.93 |

Synthetic renders check that the pipeline works end to end: features, timing, training, ONNX export, windowed inference and the decoder. They are **not** a quality gate. Both models are near the ceiling on clean synthetic chords, and neither has heard a real band, so the real test is Billboard. Both miss sevenths, because the `majmin` vocabulary maps G:7 to G.

This benchmark found one real bug. The CQT chroma kept a bin a third of a semitone sharp, and it estimated tuning modulo 33 cents, so recordings tuned more than 17 cents flat were read a semitone low. The template score on these renders went from 59.1 % to 97.0 % once that was fixed. Feature specs now carry a `revision`, and a bundle trained on an older revision is refused instead of silently mis-served.

### 3.5 Every chord type: `chordnet-chroma@3.0.0`

3.0.0 names 14 chord types on each of the 12 roots, plus "no chord": 169 classes. The types are major, minor, dim, aug, 6, m6, 7, maj7, m7, dim7, half-diminished (m7b5), m(maj7), sus2 and sus4. Slash chords (C/E, G7/B) come from the bass head where it is at least 50 % sure of a chord tone other than the root. That threshold was tuned on validation, like the rest of the decoder. The app shows the full names and typed Roman numerals (V7, ii7, viiø7). A "Major and minor chords only" option on the upload page simplifies them instead (Cmaj7 → C, Bm7b5 → Bm).

**Why new data.** Billboard alone cannot teach this. In its training songs, major, minor, 7 and m7 fill 87 % of the time, and seven of the types together (dim, aug, m6, dim7, m7b5, m(maj7), sus2) about 1 %. 2.0.0 recognised none of the other types. 3.0.0 trains on four sources, each with a fixed share of the training crops, all through the same NNLS extractor the server uses:

| Source | Songs | Hours | Share of crops | What it adds | Licence |
|---|---|---|---|---|---|
| Billboard training split | 711 | 41.9 | 45 % | Real pop and rock | McGill features |
| Generated songs (`chordify_ai/data/render.py`) | 2,000 | 27.1 | 25 % | Every type in balanced amounts, 20 % inversions, varied voicings, bass lines, drums and melody | ours |
| [POP909](https://github.com/music-x-lab/POP909-Dataset) training split, rendered | 728 | 50.5 | 15 % | Real pop arrangements with rich chords | MIT |
| [GuitarSet](https://zenodo.org/records/3371780) players 00–03 | 120 takes | 1.0 | 15 % | Real acoustic guitar, labelled with the chords as played | CC BY 4.0 |

MIDI is rendered with FluidSynth and the FluidR3_GM soundfont (MIT), with random General MIDI instruments per song. AAM was left out: its labels are major/minor only. [`tools/get_extra_data.sh`](../../tools/get_extra_data.sh) downloads everything.

**Training.** The loss weights each chord type by 1/√(its share of the training crops), so rare types are learnt. That also makes the network over-predict them, and the balanced generated songs add to this. The decoder therefore divides the network's output by what it was trained to expect (training share × loss weight) and multiplies by Billboard's real type frequencies, a label-shift correction whose strength (`alpha`) is tuned on validation. The best epoch and the decoder settings both maximise the mean of `majmin` and `large`, where `large` is the share of time with exactly the right chord, type included. The reference run on the M5 Pro took 72 s per epoch at 4,000 crops. Early stopping ended it at epoch 28 and kept epoch 22. The rendered songs' features are cached after the first run.

**Results on the test splits** (every `mir_eval` level, corrected scoring):

| Test set | Model | root | thirds | triads | majmin | majmin_inv | sevenths | tetrads | mirex | large | seg |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Billboard (89 songs) | templates 0.2.0 | 73.4 | 69.4 | 65.0 | 71.9 | 69.8 | 58.4 | 52.1 | 70.6 | 55.8 | 75.2 |
| | 2.0.0 | 83.7 | 78.2 | 73.5 | 81.3 | 78.8 | 62.4 | 55.8 | 78.2 | 59.5 | **79.9** |
| | **3.0.0** | **84.4** | **80.2** | **75.1** | **82.3** | **80.3** | **71.0** | **63.6** | **81.8** | **67.9** | 79.4 |
| GuitarSet player 05, as played (30 takes) | 2.0.0 | 69.5 | 67.2 | 50.4 | 78.6 | 63.5 | 67.0 | 38.2 | 63.4 | 42.7 | 83.1 |
| | **3.0.0** | **74.4** | **72.3** | **55.2** | **80.9** | **66.2** | **85.9** | **52.3** | **86.5** | **71.3** | **86.8** |

3.0.0 beats 2.0.0 at every level on both test sets except Billboard segmentation (−0.5). Knowing the other chord types made plain major/minor slightly better, not worse. On GuitarSet scored against the lead sheet instead, which writes C where the guitarist played Cmaj7, 3.0.0's `sevenths` (48.0 %) is below 2.0.0's (61.3 %), because it reports the chords actually played. Its `majmin` is still higher (72.6 % vs 67.9 %).

**By chord type** (3.0.0, share of each type's time labelled exactly right; 2.0.0 got 0 % on every type but major and minor):

| Type | Billboard test: recall (share of time) | GuitarSet test, as played | Most often mistaken for |
|---|---|---|---|
| major | 80 % (53.4 %) | 92 % (37.6 %) | right type, wrong root; 7 |
| minor | 55 % (14.4 %) | 78 % (10.6 %) | m7 (21 % on Billboard) |
| 7 | 38 % (9.9 %) | 85 % (14.3 %) | major (44 % on Billboard) |
| m7 | 66 % (9.0 %) | 48 % (9.0 %) | minor on Billboard; 7 or major on GuitarSet |
| maj7 | 59 % (3.5 %) | 65 % (10.4 %) | major, 7 |
| sus4 | 27 % (2.7 %) | 0 % (1.2 %) | major, m7 |
| 6 | 12 % (0.7 %) | 18 % (9.1 %) | m7 (C6 has the notes of Am7), major |
| m7b5 | 28 % (0.1 %) | 90 % (3.8 %) | 7 |
| sus2, m6, dim, aug, dim7, m(maj7) | 0 % (together 0.7 %) | 0 % (together 4.0 %) | the nearest common type |
| no chord | 83 % (5.7 %) | — | major |

On Billboard validation, dim7 (53 %) and m6 (34 %) are found too, but there are only seconds of them in each test split.

**Against published results.** The closest published large-vocabulary system we found is ChordFormer (2025), a Conformer on CQT audio features. Trained and tested by 5-fold cross-validation on 1,217 songs (Isophonics, Billboard, MARL), it reports root 84.7, thirds 81.8, majmin 84.1, triads 77.6, sevenths 72.3, tetrads 65.3 and mirex 83.6 ([arXiv 2502.11840](https://arxiv.org/abs/2502.11840)). 3.0.0 is 0.3–2.5 points behind on every level. The songs, the features and the protocol all differ, so this is a rough yardstick, not a like-for-like comparison.

**Weak spots, in order of what they cost:**

1. **6 chords versus the relative m7.** C6 and Am7 are the same four notes; only the bass tells them apart, and 3.0.0 picks the m7 too often. This is what costs bossa nova 24 points of `majmin`. The bass head already predicts the lowest note. Using it to choose between chords with the same notes (6/m7, m6/m7b5, and the inversions of dim7 and aug) is the next decoder change.
2. **7 read as major on Billboard (44 %), minor as m7 (21 %).** Part of this is probably the annotations: a Billboard "7" can be written where the seventh is barely audible. On GuitarSet, whose labels follow what was played, 3.0.0 finds 85 % of the 7 chords.
3. **The rarest types on real audio** (sus2, m6, dim, aug, dim7, m(maj7)): the network learnt them from generated songs, but real recordings of them are scarce. More real data with these chords is the way up: jazz and bossa nova recordings with aligned chords, or progressions from ChoCo's jazz corpora rendered like the generated songs.

### 3.6 Latency

Full analysis of a 296 s synthetic song on a 4-vCPU container, warm, two runs each. Every stage is included: decode, beats, features, model, both decoder passes, key, theory, predictions.

| Chord model | Features | Time |
|---|---|---|
| templates | NNLS | 5.4–5.8 s |
| templates | CQT | 3.2–3.4 s |
| ChordNet Conformer, synthetic-trained (ONNX Runtime) | CQT | 3.6–4.1 s |
| `chordnet-chroma@2.0.0` (ONNX Runtime) | NNLS | 7.8 s; 6.1–6.2 s when re-measured next to 3.0.0 |
| **`chordnet-chroma@3.0.0`** (ONNX Runtime, 169 classes), served by default | NNLS | 6.1–6.6 s |

The phase 1 budget is p95 ≤ 20 s for a 4-minute song. Switching features from CQT to NNLS costs about 2 s. ChordNet with half-beat decoding units costs 1–3 s more than the templates. The 169-class decoder costs no measurable time over the 25-class one.

## 4. Where the build differs from the plan

| Plan | Built | Why | Revisit when |
|---|---|---|---|
| Celery + Redis workers, Postgres, object storage ([04 §1](04-software-architecture.md)) | `JobManager`: a thread pool inside the API process. Results and corrections are JSON files in `CHORDIFY_DATA_DIR`. The cache key is audio SHA-256 + model fingerprint + options. | One CPU VM handles the load (≈5 s per song), and it removes three services from setup. `JobManager` is the seam: `submit`, `get`, `result`, `events_after` and `cancel_or_delete` are what a Celery runner has to provide. | Analyses need more than one machine, or results must survive a disk loss |
| `models/registry.yaml` | One `bundle.json` per model (id, feature spec, vocabulary, file hashes, metrics, decoder parameters), selected by `CHORDIFY_CHORD_MODEL` / `CHORDIFY_LM_MODEL` | Same information without a second file to keep in sync | There are several deployments with different active models |
| DVC-tracked data, MLflow, Lightning | Frozen split JSON in git. ChoCo loaded directly with a local JSON cache. A plain PyTorch loop that writes `history.json`, `best.pt` and the bundle. | Nothing to host. The split and the n-gram bundle carry hashes. | More than one person trains models |
| Beat This! beat tracker | `librosa.beat.beat_track` (downbeat phase from chord-change alignment) | Already a dependency and fast. Beat This! adds a PyTorch model to serving. | Beat accuracy is measured on real audio |
| Key HMM with local keys | Global key from the decoded chords (duration-weighted Krumhansl–Kessler), then a second decoder pass with the key-relative LM | Enough for Roman numerals and predictions. ChordNet's key heads are trained, but serving does not read them yet. | Modulations matter in the UI (the key heads are ready) |
| ProgressionLM distilled into the decoder | The n-gram's key-relative change matrix, blended 50/50 with uniform | Same interface (`lm.change_matrix`), so the Transformer can drop in | ProgressionLM exists |
| TanStack Query + Zustand | Plain hooks. One `PlaybackClock` read through `useSyncExternalStore`, so only the components whose chord or bar changed re-render. | Two fewer dependencies for one page of server state | The app grows more pages with shared server state |

## 5. Next steps, in order

1. **Improve ChordNet.** 3.0.0 ships every chord type ([§3.5](#35-every-chord-type-chordnet-chroma300)). Next, in order of expected gain: choose between chords with the same notes (C6/Am7, Cm6/Am7b5) by the bass head; more real recordings with rare chords (ChoCo's jazz progressions rendered like the generated songs, aligned jazz and bossa nova recordings); then read the key heads for local keys. Every retrain follows [`chordify_ai/TRAINING.md`](../../chordify_ai/TRAINING.md) and must beat the shipped bundle on the Billboard and GuitarSet test splits.
2. **Baselines for the gate.** Chordino (`nnls-chroma:chordino`, built by `tools/install_nnls_chroma.sh`) takes audio, and Billboard ships only features. So on Billboard the like-for-like baseline is the template model through the same decoder (`evaluate_model --model templates --chroma …`, [§3.4](#34-acoustic-model)); Chordino proper runs on GuitarSet and the Chordify-Live audio from step 3.
3. **Chordify-Live.** Record and label the phone/guitar test set ([02 §5](02-datasets.md)): the only in-domain measure for the Quick chord screen.
4. **ProgressionLM.** Ingest Chordonomicon + ChoCo symbolic, train the Transformer, and gate it against [§3.3](#33-progression-model-the-gate-progressionlm-has-to-beat).
5. **Local keys and sections.**
6. Phase 3 onward as in [07](07-roadmap.md): audio-domain model v3, Android on v2, live mode.
7. **Remove v1.** Delete the v1 classifier files in `chordify_ai/` and the v1 endpoints once Android uses v2.

## 6. Running it

```bash
pip install -r chordify_backend/requirements.txt pytest httpx mir_eval torch onnx
bash tools/install_nnls_chroma.sh
pip install --upgrade setuptools wheel numpy
pip install --no-build-isolation vamp
pytest -q
```

`torch` and `onnx` are only needed for ChordNet training, export and their tests. The NNLS
plugin (build prerequisites in the [backend README](../../chordify_backend/README.md)) is
what the shipped model needs; without it the NNLS tests are skipped and the server uses
the templates. Then start the server and the web app in two terminals and open
http://localhost:5173:

```bash
uvicorn chordify_backend.app.main:app --port 8000
```

```bash
cd chordify-frontend
npm ci
npm run dev
```

Training and evaluation need the ChoCo checkout from the [README](README.md#reproducing-the-numbers-charts-and-mockup) (`--choco`).

| | |
|---|---|
| ![Songwriter screen](assets/app-songwriter.png) | ![Analysis screen on a phone](assets/app-mobile-dark.png) |
| Songwriter: type chords, get the next one with the reason | The analysis screen at 390 px |
