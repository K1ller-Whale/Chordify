"""The analysis pipeline (plan 04 §3): audio -> AnalysisResult document.

Stages report progress through ``progress(stage, fraction, partial)`` so the API can
stream them. Every piece of maths lives in chordify_core; this module orchestrates.
"""
from __future__ import annotations

import math
from collections import Counter
from typing import Callable

import numpy as np

from chordify_core import decode, features, lm, theory, vocab
from chordify_core.acoustic import decoder_kwargs
from chordify_core.theory import Key

from .beats import BeatInfo, downbeat_phase, track_beats
from .models import Models

ProgressFn = Callable[[str, float, dict | None], None]
LM_PRIOR_WEIGHT = 0.5


class NoMusicDetected(Exception):
    pass


class Cancelled(Exception):
    pass


def _check(should_cancel: Callable[[], bool]) -> None:
    if should_cancel():
        raise Cancelled()


def decode_chords(models: Models, posteriors: np.ndarray, boundary: np.ndarray | None, spec: features.FeatureSpec,
                  duration: float, beats: BeatInfo, key: Key | None = None, min_units: int = 1) -> list[decode.Segment]:
    """Beat-synchronous decoding; with ``key`` the progression model supplies the change prior."""
    params = decoder_kwargs(models.acoustic)
    matrix = None
    if key is not None:
        size = models.acoustic.vocabulary.size
        uniform = decode.uniform_change_matrix(size)
        matrix = (1 - LM_PRIOR_WEIGHT) * uniform + LM_PRIOR_WEIGHT * models.lm.change_matrix(models.acoustic.vocabulary, key)
    return decode.decode(posteriors, spec.frame_rate, beats=beats.beats if beats.usable else None, change_prob=boundary,
                         prior=models.acoustic.prior, change_matrix=matrix, min_units=min_units,
                         frame_offset=spec.offset, duration=duration, **params)


def _spell(label: str, key: Key) -> str:
    return vocab.transpose(label, 0, flats=key.flats) if vocab.parse(label).is_chord else label


def _bars(downbeats: np.ndarray, end: float, segments: list[dict]) -> list[dict]:
    bounds = list(downbeats) + [end]
    if bounds[0] > 0.3:  # pickup before the first downbeat
        bounds.insert(0, 0.0)
    bars = []
    for start, stop in zip(bounds[:-1], bounds[1:]):
        if stop - start < 1e-3:
            continue
        names = []
        for seg in segments:
            overlap = min(stop, seg["end"]) - max(start, seg["start"])
            if overlap >= 0.25 * (stop - start) and (not names or names[-1] != seg["display"]):
                names.append(seg["display"])
        bars.append({"start": round(float(start), 3), "end": round(float(stop), 3), "chords": names})
    return bars


def analyse(y: np.ndarray, sr: int, *, models: Models, analysis_id: str, vocabulary: str = "majmin",
            predictions: bool = True, min_segment_beats: int = 1, filename: str | None = None,
            sha256: str | None = None, no_music_threshold: float = 0.9,
            progress: ProgressFn = lambda *_: None, should_cancel: Callable[[], bool] = lambda: False) -> dict:
    duration = len(y) / sr
    acoustic = models.acoustic
    if vocabulary != acoustic.vocabulary.name:
        raise ValueError(f"the active chord model supports '{acoustic.vocabulary.name}', not '{vocabulary}'")

    progress("beats", 0.1, None)
    beats = track_beats(y, sr)
    tempo = {"bpm": beats.bpm, "meter": "4/4", "confidence": beats.confidence}
    progress("beats", 0.2, {"kind": "beats", "tempo": tempo, "beats": [round(b, 3) for b in beats.beats.tolist()]})
    _check(should_cancel)

    spec = acoustic.feature_spec
    raw = features.extract(spec, y, sr)
    progress("features", 0.45, None)
    _check(should_cancel)

    output = acoustic.predict(raw)
    progress("acoustic", 0.6, None)
    _check(should_cancel)

    first = decode_chords(models, output.chord, output.boundary, spec, duration, beats)
    labels = [(acoustic.vocabulary.decode(s.index), s.duration) for s in first]
    no_chord = sum(d for label, d in labels if label == vocab.NO_CHORD)
    if duration > 0 and no_chord / duration > no_music_threshold:
        raise NoMusicDetected(f"{no_chord / duration:.0%} of the audio has no recognisable chord.")
    key, key_confidence = theory.estimate_key_from_chords(labels)
    progress("key", 0.7, None)

    segments = decode_chords(models, output.chord, output.boundary, spec, duration, beats, key=key,
                             min_units=min_segment_beats)
    progress("decode", 0.8, None)
    _check(should_cancel)

    beat_times = beats.beats
    chords: list[dict] = []
    for i, seg in enumerate(segments):
        label = _spell(acoustic.vocabulary.decode(seg.index), key)
        chord = vocab.parse(label)
        entry = {"index": i, "start": round(seg.start, 3), "end": round(seg.end, 3), "label": label,
                 "display": vocab.display_name(label, flats=key.flats), "confidence": round(seg.confidence, 4),
                 "alternatives": [{"label": _spell(acoustic.vocabulary.decode(j), key),
                                   "display": vocab.display_name(_spell(acoustic.vocabulary.decode(j), key), key.flats),
                                   "p": round(p, 4)} for j, p in seg.alternatives]}
        if len(beat_times):
            entry["beats"] = int(((beat_times >= seg.start - 1e-3) & (beat_times < seg.end - 1e-3)).sum())
        if chord.is_chord:
            entry.update({"root": key.spell(chord.root), "quality": acoustic.vocabulary.quality_of(seg.index),
                          "bass": key.spell(chord.bass), **{k: v for k, v in theory.describe(label, key).items()
                                                            if k in ("roman", "function", "scale_hint")}})
        chords.append(entry)

    real = [c for c in chords if vocab.parse(c["label"]).is_chord]
    if predictions and real:
        tokens = [lm.token(c["label"], key) for c in real]
        observed = []
        for j, c in enumerate(real):
            history = [r["label"] for r in real[:j + 1]]
            beats_so_far = [float(r.get("beats") or 0) for r in real[:j + 1]] if len(beat_times) else None
            preds, prior = lm.predict_next(models.lm, history, key, k=3, history_beats=beats_so_far)
            c["next"] = [p.to_dict() for p in preds]
            c["theory_only"] = prior
            if j > 0 and tokens[j] != tokens[j - 1]:
                dist, _ = models.lm.distribution(tokens[:j])
                p = max(dist.get(tokens[j], 0.0), 1e-6)
                c["surprise"] = round(-math.log2(p), 3)
                observed.append(p)
        predictability = round(float(np.mean(observed)), 4) if observed else None
    else:
        predictability = None
    progress("analyse", 0.95, None)

    downbeats = np.asarray([])
    if len(beat_times):
        phase = downbeat_phase(beat_times, [c["start"] for c in chords[1:]])
        downbeats = beat_times[phase::4]

    share = Counter()
    for c in real:
        share[c["display"]] += c["end"] - c["start"]
    total = sum(share.values()) or 1.0
    romans = [c.get("roman") for c in real]
    return {
        "schema_version": 1,
        "analysis_id": analysis_id,
        "status": "completed",
        "source": {"filename": filename, "duration": round(duration, 3), "sha256": sha256},
        "models": models.ids,
        "tempo": tempo,
        "key": {"global": {"tonic": key.tonic_name, "mode": key.mode, "confidence": round(key_confidence, 4)},
                "segments": [{"tonic": key.tonic_name, "mode": key.mode, "confidence": round(key_confidence, 4),
                              "start": 0.0, "end": round(duration, 3)}]},
        "beats": [round(b, 3) for b in beat_times.tolist()],
        "downbeats": [round(b, 3) for b in downbeats.tolist()],
        "bars": _bars(downbeats, duration, chords) if len(downbeats) else [],
        "sections": [],
        "chords": chords,
        "summary": {"unique_chords": len(share),
                    "time_share": [{"display": d, "seconds": round(s, 3), "share": round(s / total, 4)}
                                   for d, s in share.most_common()],
                    "patterns": theory.find_patterns([r for r in romans if r]),
                    "predictability": predictability},
    }


def single_chord(models: Models, y: np.ndarray, sr: int) -> tuple[str, float]:
    """v1 '/predict' semantics on the new pipeline: one label for a short clip.

    Frame posteriors are averaged with weights 1 - P(N); the best chord class wins.
    """
    acoustic = models.acoustic
    output = acoustic.predict(features.extract(acoustic.feature_spec, y, sr))
    posteriors = output.chord
    n = acoustic.vocabulary.no_chord
    weights = 1.0 - posteriors[:, n]
    if weights.sum() < 1e-3:
        return vocab.NO_CHORD, float(posteriors[:, n].mean())
    chord_mass = (posteriors * weights[:, None]).sum(axis=0) / weights.sum()
    chord_mass[n] = 0.0
    best = int(np.argmax(chord_mass))
    return acoustic.vocabulary.decode(best), float(chord_mass[best] / chord_mass.sum())
