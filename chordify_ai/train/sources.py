"""Where ChordNet training examples come from.

- ``billboard_chroma``: Billboard's released NNLS bothchroma (Kaggle) + ChoCo labels. The
  real training source for ChordNet-Chroma v2; needs the Kaggle download.
- ``synthetic``: Billboard chord annotations rendered with ``chordify_core.synth`` (varied
  timbre) and passed through the *same* feature extractor used in serving. Exact labels,
  no licensing issues; used for pre-training, tests and pipeline smoke runs (plan 02 §7).
- ``guitarset_chroma``: GuitarSet's microphone recordings through our NNLS extractor, with
  the performed chords (real audio, rich in 7ths, 6ths, sus and m7b5).
- ``rendered_chroma``: songs rendered with FluidSynth (``chordify_ai.data.render``): generated
  songs with balanced chord types, and re-orchestrated POP909 arrangements.

Features from audio are cached as .npy files keyed by the feature kind and revision (and
the generator version for rendered songs), so a second run skips the extraction.
"""
from __future__ import annotations

import json
import os
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from chordify_core import audio, features, synth, vocab
from chordify_core.features import FeatureSpec

from ..data import billboard, guitarset, pop909, render
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


def billboard_chroma(tracks: list[billboard.BillboardTrack], features_root: str | Path,
                     vocabulary: vocab.Vocabulary) -> list[EvalTrack]:
    spec = features.NNLS_BOTHCHROMA
    out = []
    for track in tracks:
        path = billboard.chroma_path(features_root, track.track_id)
        if not path.exists():
            continue
        times, raw = features.load_billboard_bothchroma(path)
        if len(times) > 1 and abs(np.median(np.diff(times)) - 1 / spec.frame_rate) > 1e-3:
            raise ValueError(f"{path}: frame step {np.median(np.diff(times)):.4f} s is not NNLS_BOTHCHROMA's")
        offset = float(times[0]) if len(times) else spec.offset  # frame-centre time of the first frame
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
        path = cache / f"{track.track_id}-{spec.kind}-r{spec.revision}-{seed}.npy" if cache else None
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


def _track(track_id: str, raw: np.ndarray, chords: list[tuple[float, float, str]], beats: list[float],
           keys: list[tuple[float, int | None, str | None]], vocabulary: vocab.Vocabulary) -> EvalTrack:
    spec = features.NNLS_BOTHCHROMA
    x = features.normalise_bothchroma(raw).astype(np.float32)
    targets = frame_targets(len(x), spec.frame_rate, chords, vocabulary, keys, frame_offset=spec.offset)
    return EvalTrack(TrackExample(track_id, x, targets, "bothchroma"), chords, beats, spec.frame_rate, spec.offset)


def guitarset_chroma(takes: list[guitarset.GuitarSetTake], vocabulary: vocab.Vocabulary,
                     cache_dir: str | Path | None = None, labels: str = "performed") -> list[EvalTrack]:
    """GuitarSet takes through the serving NNLS extractor, labelled with the performed chords."""
    spec = features.NNLS_BOTHCHROMA
    cache = Path(cache_dir) if cache_dir else None
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    out = []
    for take in takes:
        path = cache / f"guitarset-{take.take_id}-{spec.kind}-r{spec.revision}.npy" if cache else None
        if path and path.exists():
            raw = np.load(path)
        else:
            raw = features.extract(spec, audio.load_audio(take.audio, spec.sample_rate), spec.sample_rate)
            if path:
                np.save(path, raw.astype(np.float32))
        chords = take.chords if labels == "performed" else take.lead_sheet
        tonic, mode = take.key
        out.append(_track(take.take_id, raw, chords, take.beats, [(0.0, tonic, mode)], vocabulary))
    return out


def _mode(name: str) -> str | None:
    return {"maj": "major", "major": "major", "min": "minor", "minor": "minor"}.get(name)


def _render_one(recipe: tuple, soundfont: str, cache_dir: str | None) -> tuple[str, dict]:
    """Render one song and extract NNLS features (a process-pool worker). ``recipe`` is
    ("generated", index, seed) or ("pop909", song_folder, seed)."""
    spec = features.NNLS_BOTHCHROMA
    kind, what, seed = recipe
    song_id = (f"gen{render.GENERATOR_VERSION}-{seed}-{int(what):05d}" if kind == "generated"
               else f"pop909-{Path(what).name}-{seed}-g{render.GENERATOR_VERSION}")
    stem = Path(cache_dir) / f"{song_id}-{spec.kind}-r{spec.revision}" if cache_dir else None
    if stem and stem.with_suffix(".json").exists() and stem.with_suffix(".npy").exists():
        return song_id, {"meta": json.loads(stem.with_suffix(".json").read_text()), "npy": str(stem.with_suffix(".npy"))}
    song = render.generate_song(int(what), seed) if kind == "generated" \
        else render.arrange_pop909(pop909.load_song(what), seed)
    raw = features.extract(spec, render.render(song, soundfont, spec.sample_rate), spec.sample_rate).astype(np.float32)
    meta = {"chords": song.chords, "beats": song.beats, "keys": song.keys, "duration": song.duration}
    if stem:
        np.save(stem.with_suffix(".npy"), raw)
        stem.with_suffix(".json").write_text(json.dumps(meta))
        return song_id, {"meta": meta, "npy": str(stem.with_suffix(".npy"))}
    return song_id, {"meta": meta, "raw": raw}


def rendered_chroma(recipes: list[tuple], vocabulary: vocab.Vocabulary, soundfont: str | os.PathLike,
                    cache_dir: str | Path | None = None, workers: int = 0, log=print) -> list[EvalTrack]:
    """Songs rendered with FluidSynth and passed through the serving NNLS extractor, in
    parallel (``workers`` processes; 0 = all cores but one)."""
    cache = str(cache_dir) if cache_dir else None
    if cache:
        Path(cache).mkdir(parents=True, exist_ok=True)
    workers = workers or max(1, (os.cpu_count() or 2) - 1)
    results = []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(_render_one, r, str(soundfont), cache) for r in recipes]
        for n, future in enumerate(futures, 1):
            results.append(future.result())
            if n % 200 == 0 or n == len(futures):
                log(f"rendered {n}/{len(futures)} songs")
    out = []
    for song_id, item in results:
        meta = item["meta"]
        raw = item["raw"] if "raw" in item else np.load(item["npy"])
        chords = [tuple(c) for c in meta["chords"]]
        keys = []
        for start, name in meta.get("keys", []):
            tonic, _, mode = name.partition(":")
            keys.append((float(start), vocab.note_to_pc(tonic), _mode(mode)))
        out.append(_track(song_id, raw, chords, meta["beats"], keys, vocabulary))
    return out
