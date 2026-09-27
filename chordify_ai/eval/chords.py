"""Chord-timeline evaluation with mir_eval (plan 03 §7).

WCSR (weighted chord symbol recall) = fraction of reference time labelled correctly
at a comparison level; corpus scores are duration-weighted over tracks, as in MIREX.
Besides mir_eval's levels, ``large`` is the exact-match rate on the 170-class large
vocabulary (root and chord type, bass ignored) and ``by_quality`` breaks it down per
reference chord type, with what each type was mistaken for.

    python -m chordify_ai.eval.chords REF_DIR EST_DIR     # directories of .lab files
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from collections import Counter, defaultdict
from typing import Iterable, Sequence

import numpy as np

from chordify_core import vocab

LEVELS = ("root", "thirds", "triads", "majmin", "majmin_inv", "sevenths", "sevenths_inv",
          "tetrads", "tetrads_inv", "mirex")
SEGMENTATION = ("overseg", "underseg", "seg")

Interval = tuple[float, float, str]


def _arrays(segments: Sequence[Interval]) -> tuple[np.ndarray, list[str]]:
    """Sorted, non-overlapping, non-empty intervals (mir_eval rejects overlaps)."""
    rows = []
    for start, end, label in sorted(segments, key=lambda s: s[0]):
        if rows and rows[-1][1] > start:
            rows[-1][1] = start
        if end > start:
            rows.append([start, end, label])
    rows = [r for r in rows if r[1] > r[0]]
    return np.array([[s, e] for s, e, _ in rows], dtype=np.float64), [label for _, _, label in rows]


def evaluate_track(reference: Sequence[Interval], estimate: Sequence[Interval]) -> dict[str, float]:
    """Scores for one track. Both inputs are [(start, end, Harte label), ...]."""
    import mir_eval

    ref_int, ref_lab = _arrays(reference)
    lo, hi = float(ref_int.min()), float(ref_int.max())
    # Clip to the reference span first: mir_eval would trim a segment that starts where the
    # reference ends to zero length and then reject it.
    clipped = [(max(s, lo), min(e, hi), label) for s, e, label in estimate] or [(lo, hi, "N")]
    est_int, est_lab = _arrays(clipped)
    if not est_lab:
        est_int, est_lab = np.array([[lo, hi]]), ["N"]
    est_int, est_lab = mir_eval.util.adjust_intervals(est_int, est_lab, lo, hi,
                                                      mir_eval.chord.NO_CHORD, mir_eval.chord.NO_CHORD)
    scores = mir_eval.chord.evaluate(ref_int, ref_lab, est_int, est_lab)
    scores["duration"] = float(ref_int.max() - ref_int.min())
    out = {k: float(v) for k, v in scores.items() if k in LEVELS + SEGMENTATION + ("duration",)}
    out["qualities"] = quality_breakdown(ref_int, ref_lab, est_int, est_lab)
    return out


def _large_class(label: str, cache: dict[str, int | None]) -> int | None:
    if label not in cache:
        try:
            cache[label] = vocab.LARGE.encode(label)
        except ValueError:
            cache[label] = None
    return cache[label]


def _quality_name(index: int) -> str:
    return "N" if index == vocab.LARGE.no_chord else vocab.LARGE.quality_of(index)


def quality_breakdown(ref_int: np.ndarray, ref_lab: list[str], est_int: np.ndarray,
                      est_lab: list[str]) -> dict[str, dict[str, float]]:
    """Seconds of each reference chord type (large vocabulary; X skipped) and how they were
    labelled: {"maj7": {"maj7": 12.3, "maj": 4.1, ...}}. Exact class match, bass ignored.

    Works on the pieces between consecutive reference and estimate boundaries, over the
    reference span; where the estimate has a gap, its nearest chord counts."""
    cache: dict[str, int | None] = {}
    names: dict[int, str] = {}

    def quality(index: int) -> str:
        if index not in names:
            names[index] = _quality_name(index)
        return names[index]

    edges = np.unique(np.concatenate([ref_int.ravel(), est_int.ravel()]))
    edges = edges[(edges >= ref_int[0, 0]) & (edges <= ref_int[-1, 1])]
    mids, lengths = (edges[:-1] + edges[1:]) / 2, np.diff(edges)
    ref_idx = np.searchsorted(ref_int[:, 0], mids, side="right") - 1
    est_idx = np.clip(np.searchsorted(est_int[:, 0], mids, side="right") - 1, 0, len(est_lab) - 1)
    table: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    for i, j, t, length in zip(ref_idx, est_idx, mids, lengths):
        if i < 0 or t >= ref_int[i, 1]:
            continue  # a gap in the reference
        r = _large_class(ref_lab[i], cache)
        if r is None:
            continue
        e = _large_class(est_lab[j], cache)
        q = quality(r)
        verdict = q if e == r else ("X" if e is None else ("wrong root" if quality(e) == q else quality(e)))
        table[q][verdict] += float(length)
    return {q: dict(v) for q, v in table.items()}


def aggregate(per_track: Iterable[dict]) -> dict:
    """Duration-weighted means (WCSR), the number of tracks, the large-vocabulary exact-match
    rate and its per-chord-type breakdown (recall, share of the reference time, confusions)."""
    rows = list(per_track)
    weights = np.array([r["duration"] for r in rows])
    out: dict = {"tracks": float(len(rows))}
    for key in LEVELS + SEGMENTATION:
        values = np.array([r[key] for r in rows])
        valid = ~np.isnan(values)
        out[key] = float(np.average(values[valid], weights=weights[valid])) if valid.any() else float("nan")
    totals: dict[str, Counter] = defaultdict(Counter)
    for row in rows:
        for q, verdicts in row.get("qualities", {}).items():
            totals[q].update(verdicts)
    seconds = {q: sum(v.values()) for q, v in totals.items()}
    grand = sum(seconds.values())
    if grand:
        out["large"] = sum(v[q] for q, v in totals.items()) / grand
        out["by_quality"] = {
            q: {"recall": round(totals[q][q] / seconds[q], 4), "share": round(seconds[q] / grand, 4),
                "mistaken_for": {k: round(s / seconds[q], 3) for k, s in totals[q].most_common(4) if k != q}}
            for q in sorted(seconds, key=seconds.get, reverse=True)}
    return out


def read_lab(path: str | Path) -> list[Interval]:
    out = []
    for line in Path(path).read_text().splitlines():
        parts = line.split()
        if len(parts) >= 3:
            out.append((float(parts[0]), float(parts[1]), parts[2]))
    return out


def write_lab(segments: Sequence[Interval], path: str | Path) -> None:
    Path(path).write_text("".join(f"{s:.6f}\t{e:.6f}\t{label}\n" for s, e, label in segments))


def format_table(scores: dict) -> str:
    keys = [k for k in LEVELS + ("large",) + SEGMENTATION if k in scores]
    lines = [f"{k:<14}{scores[k] * 100:6.2f}" for k in keys]
    for q, row in scores.get("by_quality", {}).items():
        lines.append(f"  {q:<12}{row['recall'] * 100:6.1f}  ({row['share'] * 100:.1f} % of the time)")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("reference_dir")
    parser.add_argument("estimate_dir")
    args = parser.parse_args(argv)
    rows = []
    for ref_path in sorted(Path(args.reference_dir).glob("*.lab")):
        est_path = Path(args.estimate_dir) / ref_path.name
        if not est_path.exists():
            print(f"missing estimate for {ref_path.name}", file=sys.stderr)
            continue
        rows.append(evaluate_track(read_lab(ref_path), read_lab(est_path)))
    print(format_table(aggregate(rows)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
