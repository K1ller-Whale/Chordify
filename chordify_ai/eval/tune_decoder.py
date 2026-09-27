"""Tune a bundle's decoder settings on the Billboard validation split.

The model runs once per song; every setting in a small grid then decodes those outputs
with the production decoder. The best score wins (segmentation breaks ties): majmin WCSR for
major/minor models, the mean of majmin and large for larger vocabularies, so rich chord
types are not bought with worse triads (``--select`` overrides). Then the bass-head
confidence for slash chords is chosen on majmin_inv (tetrads_inv for larger vocabularies).
``--write`` stores both in the bundle's ``decoder`` block, which the server reads. The test
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

GRID = {"alpha": [0.0, 0.3, 0.6, 1.0], "self_prob": [0.6, 0.75, 0.85, 0.92],
        "boundary_weight": [0.0, 0.5, 1.0], "subdivide": [1, 2]}
INVERSION_THRESHOLDS = (None, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--choco", required=True)
    parser.add_argument("--chroma", required=True, help="Billboard NNLS features (McGill archive or Kaggle mirror)")
    parser.add_argument("--model", required=True, help="ChordNet bundle directory, or 'templates' (report only)")
    parser.add_argument("--split-file", default=str(splits.DEFAULT_SPLIT_FILE))
    parser.add_argument("--songs", type=int, default=0)
    parser.add_argument("--select", default=None,
                        help="score to maximise: a level or 'a+b' (their mean); default majmin, or "
                             "majmin+large for larger vocabularies")
    parser.add_argument("--write", action="store_true", help="store the best settings in bundle.json")
    args = parser.parse_args(argv)

    if args.model == "templates":
        if args.write:
            parser.error("--write needs a bundle; the template model's settings live in code")
        model = acoustic.TemplateChordModel(features.NNLS_BOTHCHROMA)  # the CSVs are NNLS, plugin or not
    else:
        model = acoustic.load_acoustic_model(args.model)
    spec = model.feature_spec
    select = args.select or ("majmin" if model.vocabulary.name == "majmin" else "majmin+large")
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
        results.append((sum(scores[level] for level in select.split("+")) / len(select.split("+")),
                        scores["seg"], params, scores))
    results.sort(key=lambda r: (r[0], r[1]), reverse=True)
    current = acoustic.decoder_kwargs(model)
    for score, seg, params, scores in results[:5]:
        extra = "".join(f"  {level} {scores[level]:.4f}" for level in ("majmin", "large") if level in scores)
        print(f"{select} {score:.4f}{extra}  seg {seg:.4f}  {params}")
    best_score, best_seg, best, best_scores = results[0]

    # Slash basses from the bass head: pick the confidence threshold on the inversion-aware level.
    level = "majmin_inv" if model.vocabulary.name == "majmin" else "tetrads_inv"
    inversions = {}
    if any("bass" in output.extra for _, _, output, _ in songs):
        for threshold in INVERSION_THRESHOLDS:
            rows = [chord_eval.evaluate_track(track.chords, segments_for(model, raw, spec, beats, track.duration,
                                                                         output=output, inversion_threshold=threshold,
                                                                         **best))
                    for track, raw, output, beats in songs]
            inversions[threshold] = chord_eval.aggregate(rows)[level]
            print(f"inversions {'off' if threshold is None else threshold}: {level} {inversions[threshold]:.4f}")
    best_threshold = max(inversions, key=lambda t: (inversions[t], t is None)) if inversions else None
    print(json.dumps({"model": model.id, "tracks": len(songs), "current": current, "best": best,
                      "inversion_threshold": best_threshold,
                      "validation": {select: round(best_score, 4), "majmin": round(best_scores["majmin"], 4),
                                     **({"large": round(best_scores["large"], 4)} if "large" in best_scores else {}),
                                     "seg": round(best_seg, 4),
                                     **({level: round(inversions[best_threshold], 4)} if inversions else {})}}))
    if args.write:
        bundle_path = Path(args.model) / "bundle.json"
        bundle = json.loads(bundle_path.read_text())
        decoder = {**bundle.get("decoder", {}), **best, "tuned_on": "billboard validation split"}
        decoder.pop("inversion_threshold", None)
        if best_threshold is not None:
            decoder["inversion_threshold"] = best_threshold
        bundle["decoder"] = decoder
        bundle_path.write_text(json.dumps(bundle, indent=1) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
