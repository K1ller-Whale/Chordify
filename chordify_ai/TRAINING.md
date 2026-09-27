# Training ChordNet on your own machine

This trains the chord-recognition model on the McGill Billboard dataset (711 songs), tunes
its decoder on the validation songs, scores it once on the held-out test songs, and ships
it to the app. It works on a Mac with Apple silicon (the GPU is used through PyTorch's
`mps` backend), on Linux, or on any machine with an NVIDIA GPU.

The whole workflow was rehearsed on Linux (CPU). The Apple GPU path could not be tested
there; if it fails, step 4 shows the one-flag fallback to the CPU.

## 1. One-time setup (about 10 minutes)

You need `git` and Python 3.11 or 3.12 (on a Mac: `brew install python@3.11`). Use
`python3.11` by name: on a Mac, plain `python3` is often Xcode's Python 3.9, which can
train but cannot run the server. After `source .venv/bin/activate`, `python --version`
should print 3.11 or 3.12. The `git checkout` line is only needed until PR #1 is merged
into `main`.

```bash
git clone https://github.com/K1ller-Whale/Chordify.git
cd Chordify
git checkout claude/ecstatic-galileo-cfgdu9
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r chordify_backend/requirements.txt torch onnx mir_eval
```

## 2. Download the data (about 1 minute, 1 GB on disk)

```bash
bash tools/get_billboard_data.sh
```

It fetches the chord annotations (ChoCo) and McGill's audio features into `data/raw/`,
which git ignores, and ends with `890 songs with features (expected 890)`.

## 3. Check that everything works (2–3 minutes)

```bash
python -m chordify_ai.train.train_chordnet --source billboard \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --songs 40 --val-songs 8 --epochs 1 --items 64 --out runs/smoke
```

The first line should end with `on mps` on a Mac (`on cuda` with an NVIDIA GPU, `on cpu`
otherwise). The score after this tiny run is meaningless; it only proves the setup.

## 4. Full training

```bash
mkdir -p runs
PYTORCH_ENABLE_MPS_FALLBACK=1 caffeinate -i \
python -m chordify_ai.train.train_chordnet --source billboard \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --out runs/chordnet-chroma-2.0.0 \
    --export models/chordnet-chroma/2.0.0 --bundle-id chordnet-chroma@2.0.0 \
    2>&1 | tee -i runs/train.log
```

- `caffeinate -i` keeps a Mac awake until training ends (leave it plugged in). On Linux, drop it.
- `PYTORCH_ENABLE_MPS_FALLBACK=1` runs on the CPU any operation Apple's GPU backend lacks.
- If it stops with an `mps` error anyway, add `--device cpu` and start again.

Each epoch prints one line, for example:

```
epoch   4  chord loss 1.497  boundary 0.772  val majmin 75.6  seg 71.0  (406.2 s)
```

`val majmin` is the share of the validation songs' time labelled with the right chord.
The template model scores 66.4 % on the same songs.

Reference run (`chordnet-chroma@2.0.0`, MacBook with an M5 Pro, Apple GPU): 42 s per
epoch, 68.4 % after epoch 1, best 79.5 % at epoch 11, early stop at epoch 17, about
12 minutes in all. After step 5: 81.4 % on validation; step 6: 80.5 % on test. The same
run on 3 cloud CPU cores took about 7 minutes per epoch.

Training runs up to 30 epochs and stops early once 6 epochs pass without improvement.
Multiply the seconds of your first epoch by about 20 for a rough total. Pressing Ctrl-C
once stops training and still exports the best epoch so far: wait for the
`bundle written to models/chordnet-chroma/2.0.0` line.

When it finishes you have `models/chordnet-chroma/2.0.0/` with `bundle.json` and
`model.onnx` (about 15 MB).

## 5. Tune the decoder on the validation songs (about 5 minutes)

```bash
python -m chordify_ai.eval.tune_decoder \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --model models/chordnet-chroma/2.0.0 --write
```

This picks how eagerly chords may change, using the validation songs only, and saves the
choice into `bundle.json`.

## 6. Score it on the test songs (once)

```bash
python -m chordify_ai.eval.evaluate_model \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --model models/chordnet-chroma/2.0.0 --split test
```

Compare `majmin` with the template model on the same 89 test songs: **0.714**. Only ship
the new model if it is higher. Do not re-tune after looking at the test score; that would
make the number meaningless.

## 7. Ship it

```bash
git add models/chordnet-chroma/2.0.0
git commit -m "Add ChordNet-Chroma 2.0.0 trained on Billboard"
git push
```

The server picks the bundle up automatically (`CHORDIFY_CHORD_MODEL=auto`) wherever the
NNLS Chroma plugin is installed (`tools/install_nnls_chroma.sh`, Linux and macOS); anywhere
else it keeps using the template model. Then ask Claude to check it on real guitar recordings
(`chordify_ai.eval.guitarset`) and update the docs and the PR.

# Every chord type: the large-vocabulary model (3.0.0)

Steps 1–7 above train `chordnet-chroma@2.0.0`, which only knows major, minor and "no
chord": a G7 comes out as G. This part trains the model behind every chord type the app
can show. It has 14 types on each of the 12 roots, plus "no chord", 169 classes in all:

| Type | Example | Type | Example |
|---|---|---|---|
| major, minor | C, Cm | 7, maj7, m7 | C7, Cmaj7, Cm7 |
| diminished, augmented | Cdim, Caug | dim7, half-diminished | Cdim7, Cm7b5 |
| 6, m6 | C6, Cm6 | minor-major 7 | Cm(maj7) |
| sus2, sus4 | Csus2, Csus4 | inversions | C/E, G7/B (from the bass head) |

Billboard alone cannot teach this. In its 711 training songs, diminished sevenths, minor-major
sevenths and augmented chords together make up less than half a percent of the time, and
2.0.0 gets 0 % of every type except major and minor. The training mixes four sources, each
with its own share of the training crops:

| Source | What it adds | Share | Licence |
|---|---|---|---|
| Billboard (711 songs, 42 h) | Real pop and rock recordings | 45 % | features from McGill |
| Generated songs (2,000, about 27 h) | Every chord type in balanced amounts, inversions, voicings, bass, drums and melody, rendered with a General MIDI soundfont | 25 % | made by `chordify_ai/data/render.py` |
| POP909 (728 songs, about 50 h) | Real pop arrangements with rich chords, rendered the same way | 15 % | MIT |
| GuitarSet (120 takes, 1 h) | Real guitar recordings with jazz, bossa nova and funk chords, labelled with what was actually played | 15 % | CC BY 4.0 |

The loss gives rare types more weight, so the network learns them. The decoder then
corrects for how rare each type is in real music (Billboard's frequencies). That way the
model knows an A7 when it hears one, without calling a plain A an A7 just in case.

## 8. Setup for this part (about 10 minutes)

In the `Chordify` folder from step 1, with the virtual environment active:

```bash
git fetch origin
git checkout claude/large-vocabulary-chords
brew install fluid-synth
pip install torch onnx mir_eval pretty_midi
bash tools/get_extra_data.sh
```

The `pip install` line is harmless if some of them are there already. It matters if you made a
new virtual environment to run the app (say, to move to Python 3.11), because that one has no
PyTorch yet.

On Linux, use `sudo apt-get install fluidsynth` instead of `brew install fluid-synth`. The
script downloads GuitarSet (about 700 MB), POP909 (about 150 MB) and the FluidR3_GM
soundfont (150 MB) into `data/raw/`. It ends with:

```
==> Done: 180 GuitarSet accompaniment takes (expected 180), 909 POP909 songs (expected 909)
```

## 9. Check that everything works (about 5 minutes)

```bash
python -m chordify_ai.train.train_chordnet \
    --source billboard,guitarset,pop909,generated --vocabulary large \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --guitarset data/raw/guitarset --pop909 data/raw/pop909 \
    --songs 40 --val-songs 8 --pop909-songs 10 --generated 20 \
    --epochs 1 --items 64 --out runs/smoke-large
```

It should print one line per source (`billboard: 40 songs`, `guitarset: 120 songs`,
`pop909: 10 songs`, `generated: 20 songs`), then `on mps` and one epoch line. The scores
mean nothing yet.

## 10. Full training (about 1.5 hours on a MacBook Pro)

```bash
PYTORCH_ENABLE_MPS_FALLBACK=1 caffeinate -i \
python -m chordify_ai.train.train_chordnet \
    --source billboard,guitarset,pop909,generated --vocabulary large \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --guitarset data/raw/guitarset --pop909 data/raw/pop909 \
    --items 4000 --epochs 40 \
    --out runs/chordnet-large-3.0.0 \
    --export models/chordnet-chroma/3.0.0 --bundle-id chordnet-chroma@3.0.0 \
    2>&1 | tee -i runs/train-large.log
```

The first run renders the POP909 and generated songs to audio and extracts their features,
using every CPU core but one. On a laptop that takes roughly 15–30 minutes, and
`data/cache/` keeps the results, so later runs start training right away. Then each epoch
prints a line like this one, from a short 4-epoch rehearsal on 3 CPU cores (your epochs will
be much faster and, after more of them, score higher):

```
epoch   4  chord loss 3.153  boundary 0.722  val majmin 69.0  large 44.7  seg 62.9  (419.1 s)
```

- `val majmin` is scored on Billboard's and GuitarSet's validation songs together, so it is
  lower than the 79.5 % of step 4, which used Billboard's alone. Compare 3.0.0 with 2.0.0 in
  steps 12 and 13 instead.
- `large` is the share of time with exactly the right chord, type included.
- The best epoch is the one with the best mean of the two. Training stops early after 6
  epochs without improvement, and Ctrl-C still exports the best epoch so far.

A bigger network may help now that there is 4 times more data. Once the run above works,
you can try `--d-model 256 --layers 6` with a different `--out`, `--export` and
`--bundle-id`, and keep whichever scores better in step 11.

## 11. Tune the decoder (10–15 minutes)

```bash
python -m chordify_ai.eval.tune_decoder \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --model models/chordnet-chroma/3.0.0 --write
```

For this model it maximises the mean of `majmin` and `large` on Billboard's validation
songs. It also picks the bass-head confidence at which a slash chord (C/E) is shown.

## 12. Score it on the test songs (once)

```bash
python -m chordify_ai.eval.evaluate_model \
    --choco data/raw/choco --chroma data/raw/billboard-features \
    --model models/chordnet-chroma/3.0.0 --split test
python -m chordify_ai.eval.guitarset --root data/raw/guitarset \
    --model models/chordnet-chroma/3.0.0 --split test --labels performed
```

The first command scores the 89 Billboard test songs, the second the 30 GuitarSet takes of
player 05, whom training never heard. Each prints the scores from `root` to `tetrads_inv`,
then one line per chord type with its recall and what it is mistaken for most. Here is where
2.0.0 stands:

| Test set | majmin | sevenths | tetrads | large | types other than maj/min recognised |
|---|---|---|---|---|---|
| Billboard test, 2.0.0 | 80.5 % | 61.6 % | 55.8 % | 59.5 % | none |
| GuitarSet player 05 (performed), 2.0.0 | 69.6 % | 47.3 % | 38.2 % | 42.7 % | none |

Ship 3.0.0 if `majmin` on Billboard test stays within about a point of 80.5 % while
`sevenths`, `tetrads` and `large` go up. That trade is the point of this model. As in step
6, do not re-tune after looking at test scores.

## 13. Ship it

```bash
git add models/chordnet-chroma/3.0.0
git commit -m "Add ChordNet-Chroma 3.0.0 with every chord type"
git push origin claude/large-vocabulary-chords
```

Then tell Claude it is pushed. Claude checks the numbers, makes 3.0.0 the default model,
updates the docs and gets CI green. Anyone who prefers plain chords can still pick "Major and
minor" in the app. It uses the same model and simplifies its chords.
