"""Chord-timeline evaluation with mir_eval (plan 03 §7).

WCSR (weighted chord symbol recall) = fraction of reference time labelled correctly
at a comparison level; corpus scores are duration-weighted over tracks, as in MIREX.

    python -m chordify_ai.eval.chords REF_DIR EST_DIR     # directories of .lab files
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np

LEVELS = ("root", "majmin", "majmin_inv", "sevenths", "sevenths_inv", "mirex")
SEGMENTATION = ("overseg", "underseg", "seg")

Interval = tuple[float, float, str]


def _arrays(segments: Sequence[Interval]) -> tuple[np.ndarray, list[str]]:
    intervals = np.array([[s, e] for s, e, _ in segments], dtype=np.float64)
    return intervals, [label for _, _, label in segments]


def evaluate_track(reference: Sequence[Interval], estimate: Sequence[Interval]) -> dict[str, float]:
    """Scores for one track. Both inputs are [(start, end, Harte label), ...]."""
    import mir_eval

    ref_int, ref_lab = _arrays(reference)
    est_int, est_lab = _arrays(estimate)
    est_int, est_lab = mir_eval.util.adjust_intervals(est_int, est_lab, ref_int.min(), ref_int.max(),
                                                      mir_eval.chord.NO_CHORD, mir_eval.chord.NO_CHORD)
    scores = mir_eval.chord.evaluate(ref_int, ref_lab, est_int, est_lab)
    scores["duration"] = float(ref_int.max() - ref_int.min())
    return {k: float(v) for k, v in scores.items() if k in LEVELS + SEGMENTATION + ("duration",)}


def aggregate(per_track: Iterable[dict[str, float]]) -> dict[str, float]:
    """Duration-weighted means (WCSR), plus the number of tracks."""
    rows = list(per_track)
    weights = np.array([r["duration"] for r in rows])
    out = {"tracks": float(len(rows))}
    for key in LEVELS + SEGMENTATION:
        values = np.array([r[key] for r in rows])
        valid = ~np.isnan(values)
        out[key] = float(np.average(values[valid], weights=weights[valid])) if valid.any() else float("nan")
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


def format_table(scores: dict[str, float]) -> str:
    keys = [k for k in LEVELS + SEGMENTATION if k in scores]
    return "\n".join(f"{k:<14}{scores[k] * 100:6.2f}" for k in keys)


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
