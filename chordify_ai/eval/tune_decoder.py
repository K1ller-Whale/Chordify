"""Tune a bundle's decoder settings on the Billboard validation split.

The model runs once per song; every setting in a small grid then decodes those outputs
with the production decoder. The best majmin WCSR wins (segmentation breaks ties), and
``--write`` stores it in the bundle's ``decoder`` block, which the server reads. The test
split is never touched, so test scores stay honest.

    python -m chordify_ai.eval.tune_decoder --choco CHOCO --chroma FEATURES --model BUNDLE --write
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

from chordify_core import acoustic, features

from ..data import billboard, splits
from . import chords as chord_eval
from .evaluate_model import segments_for

GRID = {"alpha": [0.0, 0.3, 0.6], "self_prob": [0.6, 0.75, 0.85, 0.92],
        "boundary_weight": [0.0, 0.5, 1.0], "subdivide": [1, 2]}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--choco", required=True)
    parser.add_argument("--chroma", required=True, help="Billboard NNLS features (McGill archive or Kaggle mirror)")
    parser.add_argument("--model", required=True, help="ChordNet bundle directory, or 'templates' (report only)")
    parser.add_argument("--split-file", default=str(splits.DEFAULT_SPLIT_FILE))
    parser.add_argument("--songs", type=int, default=0)
    parser.add_argument("--write", action="store_true", help="store the best settings in bundle.json")
    args = parser.parse_args(argv)

    if args.model == "templates":
        if args.write:
            parser.error("--write needs a bundle; the template model's settings live in code")
        model = acoustic.TemplateChordModel(features.NNLS_BOTHCHROMA)  # the CSVs are NNLS, plugin or not
    else:
        model = acoustic.load_acoustic_model(args.model)
    spec = model.feature_spec
    if spec.kind != "nnls_bothchroma":
        parser.error("Billboard features are NNLS bothchroma; this model uses " + spec.kind)
    tracks = {t.track_id: t for t in billboard.load_choco_billboard(args.choco)}
    ids = [i for i in splits.read_split_file(args.split_file)["validation"] if i in tracks][:args.songs or None]
    songs = []
    for track_id in ids:
        path = billboard.chroma_path(args.chroma, track_id)
        if path.exists():
            _, raw = features.load_billboard_bothchroma(path)
            track = tracks[track_id]
            songs.append((track, raw, model.predict(raw), [b for b in track.beats if b < track.duration]))

    results = []
    for values in itertools.product(*GRID.values()):
        params = dict(zip(GRID, values))
        rows = [chord_eval.evaluate_track(track.chords, segments_for(model, raw, spec, beats, track.duration,
                                                                     output=output, **params))
                for track, raw, output, beats in songs]
        scores = chord_eval.aggregate(rows)
        results.append((scores["majmin"], scores["seg"], params))
    results.sort(key=lambda r: (r[0], r[1]), reverse=True)
    current = acoustic.decoder_kwargs(model)
    for majmin, seg, params in results[:5]:
        print(f"majmin {majmin:.4f}  seg {seg:.4f}  {params}")
    best_majmin, best_seg, best = results[0]
    print(json.dumps({"model": model.id, "tracks": len(songs), "current": current, "best": best,
                      "validation": {"majmin": round(best_majmin, 4), "seg": round(best_seg, 4)}}))
    if args.write:
        bundle_path = Path(args.model) / "bundle.json"
        bundle = json.loads(bundle_path.read_text())
        bundle["decoder"] = {**bundle.get("decoder", {}), **best, "tuned_on": "billboard validation split"}
        bundle_path.write_text(json.dumps(bundle, indent=1) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
