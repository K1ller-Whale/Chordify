"""From frame posteriors to a chord timeline (plan 03 §3).

1. Pool frame log-posteriors per beat (chords change on beats).
2. Divide by class priors (hybrid NN/HMM scaling) so frequent classes are not favoured.
3. Viterbi with a per-step probability of *staying* (from the boundary head or a prior)
   and a chord-to-chord matrix for *changes* (from the progression model).
4. Group into segments with confidence and alternatives, merge fragments.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

_EPS = 1e-9


@dataclass
class Segment:
    start: float
    end: float
    index: int  # vocabulary class
    confidence: float  # mean frame posterior of the chosen class
    alternatives: list[tuple[int, float]] = field(default_factory=list)

    @property
    def duration(self) -> float:
        return self.end - self.start


def unit_boundaries(duration: float, beats: np.ndarray | None, frame_rate: float,
                    subdivide: int = 1) -> np.ndarray:
    """Boundaries of decoding units: beat intervals (optionally subdivided), or frames.

    Includes the span before the first beat and after the last one.
    """
    if beats is None or len(beats) < 2:
        n = int(np.ceil(duration * frame_rate))
        return np.arange(n + 1, dtype=np.float64) / frame_rate
    beats = np.asarray(beats, dtype=np.float64)
    beats = beats[(beats > 0) & (beats < duration)]
    points = [0.0]
    for a, b in zip(beats[:-1], beats[1:]):
        points.extend(a + (b - a) * np.arange(subdivide) / subdivide)
    points.append(beats[-1])
    if duration - beats[-1] > 1e-3:
        points.append(duration)
    points = np.unique(np.round(points, 6))
    if points[0] > 0:
        points = np.concatenate([[0.0], points])
    return points


def pool(log_probs: np.ndarray, frame_rate: float, boundaries: np.ndarray) -> tuple[np.ndarray, list[slice]]:
    """Mean log-probability per unit. Units that contain no frame centre borrow the nearest frame."""
    n_frames = log_probs.shape[0]
    centres = (np.arange(n_frames) + 0.5) / frame_rate
    starts = np.searchsorted(centres, boundaries[:-1], side="left")
    ends = np.searchsorted(centres, boundaries[1:], side="left")
    pooled, spans = [], []
    for s, e in zip(starts, ends):
        if e <= s:
            s = min(s, n_frames - 1)
            e = s + 1
        spans.append(slice(int(s), int(e)))
        pooled.append(log_probs[s:e].mean(axis=0))
    return np.asarray(pooled), spans


def combine_stay(self_prob: float, p_change: float, weight: float) -> float:
    """Log-linear pooling of the prior stay probability and the boundary head:
    stay ∝ self_prob^(1-w) · (1-p)^w, change ∝ (1-self_prob)^(1-w) · p^w."""
    stay = self_prob ** (1 - weight) * (1 - p_change) ** weight
    change = (1 - self_prob) ** (1 - weight) * p_change ** weight
    return stay / (stay + change)


def uniform_change_matrix(n_classes: int) -> np.ndarray:
    m = np.full((n_classes, n_classes), 1.0 / (n_classes - 1))
    np.fill_diagonal(m, 0.0)
    return m


def viterbi(log_emissions: np.ndarray, stay: np.ndarray, change_matrix: np.ndarray,
            log_initial: np.ndarray | None = None) -> np.ndarray:
    """Best state path.

    ``stay[t]`` is P(no change entering step t); a change goes i -> j with
    probability ``(1 - stay[t]) * change_matrix[i, j]`` (rows sum to 1, zero diagonal).
    """
    n_steps, n_states = log_emissions.shape
    log_change = np.log(change_matrix + _EPS)
    np.fill_diagonal(log_change, -np.inf)
    score = log_emissions[0] + (log_initial if log_initial is not None else -np.log(n_states))
    back = np.zeros((n_steps, n_states), dtype=np.int32)
    for t in range(1, n_steps):
        s = np.clip(stay[t], 1e-6, 1 - 1e-6)
        stay_score = score + np.log(s)
        change_scores = score[:, None] + np.log(1 - s) + log_change  # [from, to]
        best_from = np.argmax(change_scores, axis=0)
        best_change = change_scores[best_from, np.arange(n_states)]
        use_stay = stay_score >= best_change
        back[t] = np.where(use_stay, np.arange(n_states), best_from)
        score = np.where(use_stay, stay_score, best_change) + log_emissions[t]
    path = np.empty(n_steps, dtype=np.int32)
    path[-1] = int(np.argmax(score))
    for t in range(n_steps - 1, 0, -1):
        path[t - 1] = back[t, path[t]]
    return path


def decode(posteriors: np.ndarray, frame_rate: float, *, beats: np.ndarray | None = None,
           change_prob: np.ndarray | None = None, prior: np.ndarray | None = None, alpha: float = 0.5,
           self_prob: float | None = None, change_matrix: np.ndarray | None = None,
           subdivide: int = 1, min_units: int = 1, n_alternatives: int = 3,
           boundary_weight: float = 0.5) -> list[Segment]:
    """Decode (T, V) frame posteriors into chord segments.

    ``change_prob`` (T,) is the boundary head's P(chord change at frame t). When it is
    given, each unit's stay probability pools ``1 - max(change_prob near its start)``
    with ``self_prob`` (see ``combine_stay``). ``change_matrix`` (V, V) is the transition
    prior for changes (e.g. from the progression model); uniform when omitted.
    """
    posteriors = np.asarray(posteriors, dtype=np.float64)
    n_frames, n_classes = posteriors.shape
    duration = n_frames / frame_rate
    log_probs = np.log(posteriors + _EPS)
    boundaries = unit_boundaries(duration, beats, frame_rate, subdivide)
    pooled, spans = pool(log_probs, frame_rate, boundaries)
    if prior is not None:
        pooled = pooled - alpha * np.log(np.asarray(prior) + _EPS)
    units_are_frames = beats is None or len(beats) < 2
    if self_prob is None:
        self_prob = 0.97 if units_are_frames else 0.75
    stay = np.full(len(spans), self_prob)
    if change_prob is not None:
        change_prob = np.clip(np.asarray(change_prob, dtype=np.float64), 1e-4, 1 - 1e-4)
        for u, span in enumerate(spans):
            lo, hi = max(0, span.start - 1), min(n_frames, span.start + 2)
            stay[u] = combine_stay(self_prob, float(change_prob[lo:hi].max()), boundary_weight)
    matrix = change_matrix if change_matrix is not None else uniform_change_matrix(n_classes)
    path = viterbi(pooled, stay, matrix)
    segments = _to_segments(path, boundaries, spans, posteriors, n_alternatives)
    return merge_short(segments, boundaries, min_units, posteriors, frame_rate, n_alternatives)


def _segment_stats(posteriors: np.ndarray, frames: slice, index: int, n_alternatives: int):
    mean = posteriors[frames].mean(axis=0)
    order = [int(i) for i in np.argsort(mean)[::-1] if i != index][:n_alternatives]
    return float(mean[index]), [(i, float(mean[i])) for i in order]


def _to_segments(path, boundaries, spans, posteriors, n_alternatives) -> list[Segment]:
    segments = []
    start = 0
    for u in range(1, len(path) + 1):
        if u == len(path) or path[u] != path[start]:
            frames = slice(spans[start].start, spans[u - 1].stop)
            conf, alts = _segment_stats(posteriors, frames, int(path[start]), n_alternatives)
            segments.append(Segment(float(boundaries[start]), float(boundaries[u]), int(path[start]), conf, alts))
            start = u
    return segments


def merge_short(segments: list[Segment], boundaries: np.ndarray, min_units: int, posteriors: np.ndarray,
                frame_rate: float, n_alternatives: int = 3) -> list[Segment]:
    """Absorb segments shorter than ``min_units`` units into the neighbour whose class
    has more posterior mass over the fragment."""
    if min_units <= 1 or len(segments) < 2:
        return segments

    def n_units(seg):
        return int(np.searchsorted(boundaries, seg.end - 1e-6) - np.searchsorted(boundaries, seg.start - 1e-6))

    def frames(start, end):
        return slice(int(round(start * frame_rate)), max(int(round(start * frame_rate)) + 1, int(round(end * frame_rate))))

    changed = True
    while changed and len(segments) > 1:
        changed = False
        for i, seg in enumerate(segments):
            if n_units(seg) >= min_units:
                continue
            span = frames(seg.start, seg.end)
            candidates = [j for j in (i - 1, i + 1) if 0 <= j < len(segments)]
            j = max(candidates, key=lambda k: posteriors[span, segments[k].index].mean())
            keep = segments[j]
            start, end = min(keep.start, seg.start), max(keep.end, seg.end)
            conf, alts = _segment_stats(posteriors, frames(start, end), keep.index, n_alternatives)
            segments[j] = Segment(start, end, keep.index, conf, alts)
            del segments[i]
            segments = _coalesce(segments, posteriors, frames, n_alternatives)
            changed = True
            break
    return segments


def _coalesce(segments: list[Segment], posteriors: np.ndarray, frames, n_alternatives: int) -> list[Segment]:
    """Join neighbours that ended up with the same chord after a merge."""
    out: list[Segment] = []
    for seg in segments:
        if out and out[-1].index == seg.index:
            start = out[-1].start
            conf, alts = _segment_stats(posteriors, frames(start, seg.end), seg.index, n_alternatives)
            out[-1] = Segment(start, seg.end, seg.index, conf, alts)
        else:
            out.append(seg)
    return out
