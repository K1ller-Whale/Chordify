# chordify_ai

Training and research code. Shared runtime code (features, vocabularies, decoding,
theory) lives in [`chordify_core`](../chordify_core) and is imported from there, so
training and serving cannot drift apart.

| Path | What |
|---|---|
| `data/billboard.py` | McGill Billboard ingestion (ChoCo JAMS + `salami_chords.txt`; Kaggle `bothchroma.csv`) |
| `data/splits.py`, `data/make_splits.py` | Frozen artist-grouped splits → [`data/splits/billboard_v1.json`](../data/splits/billboard_v1.json) |
| `eval/chords.py` | mir_eval WCSR + segmentation, duration-weighted corpus scores, `.lab` CLI |
| `lm/` | Progression model training/evaluation (n-gram + song cache baseline) |
| `models/`, `train/`, `export/` | ChordNet (PyTorch), training loop, ONNX export + model bundles |

The files at the top level of this folder (`config.py`, `crnn_model.py`,
`dataset_logic.py`, `train_model.py`, `utils.py`) are the **v1** single-chord
classifier, kept unchanged for reference until the v1 endpoints are retired.
See `docs/chord-progression-plan/01-current-state.md` for why v1 is being replaced.
