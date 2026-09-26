# 08 · Implementation status

What this PR builds from the plan, what was measured along the way, where the build
differs from the plan and why, and what is left. Everything below runs from a fresh
clone: see [§6](#6-running-it).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/app-desktop-dark.png">
  <img alt="The analysis screen of the rebuilt web app" src="assets/app-desktop-light.png">
</picture>

*The built analysis screen (not the mockup). It shows a 4 min 56 s synthetic render
of a D–A–Bm–G song, analysed by the v2 API with NNLS chroma, the template chord model
and the n-gram progression model. Key D major, 108 BPM, the loop found as
"I–V–vi–IV (Axis) ×17", Bm predicted next at 76 %.*

## 1. Status by phase

| Phase | Built in this PR | Still open |
|---|---|---|
| **0 · Foundations** | `chordify_core` package. One feature definition shared by training and serving: NNLS at 44.1 kHz/2048, plus a plugin-free CQT chroma. Golden tests. Billboard ingestion. Frozen artist-grouped splits. `mir_eval` harness. Backend hygiene (settings, CORS allow-list, problem+json, no files on disk). Configurable API URL in the web app. CI. | Chordino baseline and honest v1 numbers on the test split. The Chordify-Live recordings. |
| **1 · Full-song v2** | ChordNet in PyTorch (Conformer and BiGRU, every head), training, ONNX export with a parity check, model bundles. Beat-synchronous Viterbi decoder. API v2 with jobs, SSE, cache and v1 adapter. TypeScript web app with upload, progress, timeline and playback sync. | Training ChordNet on real Billboard features and passing the gates ([§3.4](#34-acoustic-model)). Beat This!. Celery, Postgres and object storage ([§4](#4-where-the-build-differs-from-the-plan)). |
| **2 · Progressions** | Theory engine: Roman numerals, functions, cadences, secondary dominants, borrowed chords, named loops, scale hints. n-gram + song-cache model: `/progressions/next`, predictions, surprise and predictability in every result, and a change matrix in the decoder. Web: Now/Next, circle of fifths, time per chord, repeating progressions, lead sheet, Roman/Letters, Songwriter. Corrections endpoint. | The ProgressionLM Transformer (Chordonomicon). Local keys and modulations. `sevenths` in serving. Practice mode. Corrections UI. |
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

It has **not** been trained on real audio features yet. The Billboard NNLS features are on Kaggle (login required) and the McGill site, and the build environment could reach neither. Serving therefore defaults to the training-free chroma templates.

What could be measured, on synthetic renders of the validation annotations (25 songs, first 90 s, each render with its own timbre and tuning within ±30 cents):

| Model | Features | majmin | sevenths | seg |
|---|---|---|---|---|
| `chroma-templates@0.1.0` | NNLS | 98.2 % | 67.3 % | 0.90 |
| `chroma-templates@0.1.0` | CQT (revision 2) | 97.0 % | 66.1 % | 0.87 |

Synthetic renders check that the pipeline works end to end (features, timing, decoder, export). They are **not** a quality gate: templates are near the ceiling on clean synthetic chords, and the real test is Billboard.

This benchmark found one real bug. The CQT chroma kept a bin a third of a semitone sharp, and it estimated tuning modulo 33 cents, so recordings tuned more than 17 cents flat were read a semitone low. The template score on these renders went from 59.1 % to 97.0 % once that was fixed. Feature specs now carry a `revision`, and a bundle trained on an older revision is refused instead of silently mis-served.

### 3.5 Latency

Full analysis of a 296 s song on a 4-vCPU container, warm (every stage: decode, beats, features, model, both decoder passes, key, theory, predictions):

| Chord model | Features | Time |
|---|---|---|
| templates | NNLS | 6.2 s |
| templates | CQT | 4.5 s |

The phase 1 budget is p95 ≤ 20 s for a 4-minute song.

## 4. Where the build differs from the plan

| Plan | Built | Why | Revisit when |
|---|---|---|---|
| Celery + Redis workers, Postgres, object storage ([04 §1](04-software-architecture.md)) | `JobManager`: a thread pool inside the API process. Results and corrections are JSON files in `CHORDIFY_DATA_DIR`. The cache key is audio SHA-256 + model fingerprint + options. | One CPU VM handles the load (≈5 s per song), and it removes three services from setup. `JobManager` is the seam: `submit`, `get`, `result`, `events_after` and `cancel_or_delete` are what a Celery runner has to provide. | Analyses need more than one machine, or results must survive a disk loss |
| `models/registry.yaml` | One `bundle.json` per model (id, feature spec, vocabulary, file hashes, metrics, decoder parameters), selected by `CHORDIFY_CHORD_MODEL` / `CHORDIFY_LM_MODEL` | Same information without a second file to keep in sync | There are several deployments with different active models |
| DVC-tracked data, MLflow, Lightning | Frozen split JSON in git. ChoCo loaded directly with a local JSON cache. A plain PyTorch loop that writes `history.json`, `best.pt` and the bundle. | Nothing to host. The split and the n-gram bundle carry hashes. | More than one person trains models |
| Beat This! beat tracker | `librosa.beat.beat_track` (downbeat phase from chord-change alignment) | Already a dependency and fast. Beat This! adds a PyTorch model to serving. | Beat accuracy is measured on real audio |
| Key HMM with local keys | Global key from the decoded chords (duration-weighted Krumhansl–Kessler), then a second decoder pass with the key-relative LM | Enough for Roman numerals and predictions. ChordNet's key heads are trained but not used by the templates. | ChordNet serves (use its key heads), or modulations matter in the UI |
| ProgressionLM distilled into the decoder | The n-gram's key-relative change matrix, blended 50/50 with uniform | Same interface (`lm.change_matrix`), so the Transformer can drop in | ProgressionLM exists |
| TanStack Query + Zustand | Plain hooks. One `PlaybackClock` read through `useSyncExternalStore`, so only the components whose chord or bar changed re-render. | Two fewer dependencies for one page of server state | The app grows more pages with shared server state |

## 5. Next steps, in order

1. **Real features.** Download the Billboard NNLS features (Kaggle `jacobvs/mcgill-billboard`, or McGill's `billboard-2.0-chordino` archive), then:
   ```bash
   python -m chordify_ai.train.train_chordnet --source billboard --choco CHOCO --kaggle KAGGLE \
       --features nnls_bothchroma --encoder bigru --out runs/small --export models/chordnet-chroma/2.0.0
   python -m chordify_ai.eval.evaluate_model --choco CHOCO --kaggle KAGGLE --split test \
       --model models/chordnet-chroma/2.0.0
   ```
   Start with the BiGRU (`small`) as the sanity check, then the Conformer (`base`, the default encoder). When a bundle beats the templates on the test split, point `CHORDIFY_CHORD_MODEL` at it.
2. **Baselines for the gate.** Chordino (`nnls-chroma:chordino`, built by `tools/install_nnls_chroma.sh`) takes audio, and Billboard ships only features. So on Billboard the like-for-like baseline is the template model through the same decoder (`evaluate_model --model templates --kaggle …`); Chordino proper runs on the Chordify-Live audio from step 3.
3. **Chordify-Live.** Record and label the phone/guitar test set ([02 §5](02-datasets.md)): the only in-domain measure for the Quick chord screen.
4. **ProgressionLM.** Ingest Chordonomicon + ChoCo symbolic, train the Transformer, and gate it against [§3.3](#33-progression-model-the-gate-progressionlm-has-to-beat).
5. **Sevenths and inversions in serving** (vocabulary and bass head already exist), local keys, sections.
6. Phase 3 onward as in [07](07-roadmap.md): audio-domain model v3, Android on v2, live mode.
7. **Remove v1.** Delete the v1 classifier files in `chordify_ai/` and the v1 endpoints once Android uses v2.

## 6. Running it

```bash
pip install -r chordify_backend/requirements.txt pytest httpx mir_eval
pip install torch onnx                     # only for ChordNet training/export and its tests
bash tools/install_nnls_chroma.sh          # optional NNLS features: needs libboost-dev and
                                           # pip install --no-build-isolation vamp
pytest -q                                  # 1,684 tests; NNLS tests skip without the plugin

uvicorn chordify_backend.app.main:app --port 8000
cd chordify-frontend && npm ci && npm run dev   # http://localhost:5173
```

Training and evaluation need the ChoCo checkout from the [README](README.md#reproducing-the-numbers-charts-and-mockup) (`--choco`).

| | |
|---|---|
| ![Songwriter screen](assets/app-songwriter.png) | ![Analysis screen on a phone](assets/app-mobile-dark.png) |
| Songwriter: type chords, get the next one with the reason | The analysis screen at 390 px |
