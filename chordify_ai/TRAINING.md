# Training ChordNet on your own machine

This trains the chord-recognition model on the McGill Billboard dataset (711 songs), tunes
its decoder on the validation songs, scores it once on the held-out test songs, and ships
it to the app. It works on a Mac with Apple silicon (the GPU is used through PyTorch's
`mps` backend), on Linux, or on any machine with an NVIDIA GPU.

The whole workflow was rehearsed on Linux (CPU). The Apple GPU path could not be tested
there; if it fails, step 4 shows the one-flag fallback to the CPU.

## 1. One-time setup (about 10 minutes)

You need `git` and Python 3.11 or 3.12 (on a Mac: `brew install python@3.11`).

```bash
git clone https://github.com/K1ller-Whale/Chordify.git
cd Chordify
git checkout claude/ecstatic-galileo-cfgdu9      # until PR #1 is merged into main
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
For reference, a cloud run on 3 CPU cores (about 7 minutes per epoch) reached
67.4, 73.9, 74.6 and 75.6 % in epochs 1–4 before it was stopped. The template model the
app uses today scores 66.4 % on the same songs.

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
NNLS Chroma plugin is installed (`tools/install_nnls_chroma.sh`, Linux); anywhere else it
keeps using the template model. Then ask Claude to check it on real guitar recordings
(`chordify_ai.eval.guitarset`) and update the docs and the PR.
