"""Where ChordNet training examples come from.

- ``billboard_chroma``: Billboard's released NNLS bothchroma (Kaggle) + ChoCo labels. The
  real training source for ChordNet-Chroma v2; needs the Kaggle download.
- ``synthetic``: Billboard chord annotations rendered with ``chordify_core.synth`` (varied
  timbre) and passed through the *same* feature extractor used in serving. Exact labels,
  no licensing issues; used for pre-training, tests and pipeline smoke runs (plan 02 §7).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

from chordify_core import features, synth, vocab
from chordify_core.features import FeatureSpec

from ..data import billboard
from ..models.targets import frame_targets
from .dataset import TrackExample


@dataclass
class EvalTrack:
    """What validation needs besides the model input: reference labels and beats."""
    example: TrackExample
    reference: list[tuple[float, float, str]]
    beats: list[float]
    frame_rate: float
    frame_offset: float = 0.0


def _keys(track: billboard.BillboardTrack) -> list[tuple[float, int | None, str | None]]:
    return [(t, vocab.note_to_pc(tonic), None) for t, tonic in track.tonics]


def billboard_chroma(tracks: list[billboard.BillboardTrack], kaggle_root: str | Path,
                     vocabulary: vocab.Vocabulary) -> list[EvalTrack]:
    spec = features.NNLS_BOTHCHROMA
    out = []
    for track in tracks:
        path = billboard.kaggle_chroma_path(kaggle_root, track.track_id)
        if not path.exists():
            continue
        times, raw = features.load_billboard_bothchroma(path)
        if len(times) > 1 and abs(np.median(np.diff(times)) - 1 / spec.frame_rate) > 1e-3:
            raise ValueError(f"{path}: frame step {np.median(np.diff(times)):.4f} s is not NNLS_BOTHCHROMA's")
        offset = float(times[0]) if len(times) else spec.offset  # use the file's own timestamps
        x = features.normalise_bothchroma(raw)
        targets = frame_targets(len(x), spec.frame_rate, track.chords, vocabulary, _keys(track), frame_offset=offset)
        out.append(EvalTrack(TrackExample(track.track_id, x, targets, "bothchroma"), track.chords, track.beats,
                             spec.frame_rate, offset))
    return out


def synthetic(tracks: list[billboard.BillboardTrack], vocabulary: vocab.Vocabulary, spec: FeatureSpec,
              cache_dir: str | Path | None = None, seed: int = 0, max_seconds: float | None = None) -> list[EvalTrack]:
    """Render each track's chord annotation and extract ``spec`` features (cached as .npy)."""
    cache = Path(cache_dir) if cache_dir else None
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    out = []
    for i, track in enumerate(tracks):
        chords = [(s, e, lab) for s, e, lab in track.chords if max_seconds is None or s < max_seconds]
        if max_seconds is not None and chords:
            s, e, lab = chords[-1]
            chords[-1] = (s, min(e, max_seconds), lab)
        path = cache / f"{track.track_id}-{spec.kind}-{seed}.npy" if cache else None
        if path and path.exists():
            raw = np.load(path)
        else:
            timeline = [(lab, e - s) for s, e, lab in chords]
            audio, _ = synth.render_progression(timeline, sr=spec.sample_rate, seed=seed + i, variety=True)
            raw = features.extract(spec, audio, spec.sample_rate)
            if path:
                np.save(path, raw.astype(np.float32))
        x = features.normalise_bothchroma(raw) if spec.kind.endswith("bothchroma") else raw
        targets = frame_targets(len(x), spec.frame_rate, chords, vocabulary, _keys(track), frame_offset=spec.offset)
        beats = [b for b in track.beats if max_seconds is None or b < max_seconds]
        out.append(EvalTrack(TrackExample(track.track_id, x.astype(np.float32), targets,
                                          "bothchroma" if spec.kind.endswith("bothchroma") else "log_cqt"),
                             chords, beats, spec.frame_rate, spec.offset))
    return out
