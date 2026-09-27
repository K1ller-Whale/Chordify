"""Evaluate a serving-side acoustic model through the production decoder (plan 03 §7.2 gates).

Works for any chordify_core acoustic model: the training-free templates or an exported
ChordNet bundle. Each model extracts its *own* FeatureSpec, exactly as in serving.

    python -m chordify_ai.eval.evaluate_model --choco CHOCO --model templates --synthetic --songs 25
    python -m chordify_ai.eval.evaluate_model --choco CHOCO --model models/chordnet-chroma/2.0.0 \\
        --chroma FEATURES --split test        # real Billboard NNLS chroma (McGill archive or Kaggle)
"""
from __future__ import annotations

import argparse
import json

import numpy as np

from chordify_core import acoustic, decode, features, synth

from ..data import billboard, splits
from . import chords as chord_eval


def segments_for(model, raw: np.ndarray, spec: features.FeatureSpec, beats: list[float], duration: float,
                 output: acoustic.AcousticOutput | None = None, **overrides):
    """Decode as the server does (first pass, no key yet); ``overrides`` replace decoder settings."""
    output = output if output is not None else model.predict(raw)
    params = acoustic.decoder_kwargs(model) | overrides
    segments = decode.decode(output.chord, spec.frame_rate, beats=np.asarray(beats) if len(beats) > 1 else None,
                             change_prob=output.boundary, prior=getattr(model, "prior", None),
                             frame_offset=spec.offset, duration=duration, **params)
    return [(s.start, s.end, model.vocabulary.decode(s.index)) for s in segments]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--choco", required=True)
    parser.add_argument("--model", default="templates", help="'templates' or a ChordNet bundle directory")
    parser.add_argument("--split", default="validation", choices=["train", "validation", "test"])
    parser.add_argument("--split-file", default=str(splits.DEFAULT_SPLIT_FILE))
    parser.add_argument("--synthetic", action="store_true", help="render the annotations with the synthesiser")
    parser.add_argument("--chroma", "--kaggle", dest="chroma",
                        help="Billboard NNLS features (McGill archive or Kaggle mirror) instead of --synthetic")
    parser.add_argument("--songs", type=int, default=0)
    parser.add_argument("--max-seconds", type=float, default=90.0)
    parser.add_argument("--seed", type=int, default=2)
    parser.add_argument("--decoder", type=json.loads, default={}, help='override decoder settings, e.g. \'{"subdivide": 2}\'')
    args = parser.parse_args(argv)
    if not args.synthetic and not args.chroma:
        parser.error("choose --synthetic or --chroma")

    model = acoustic.load_acoustic_model(args.model)
    spec = model.feature_spec
    tracks = {t.track_id: t for t in billboard.load_choco_billboard(args.choco)}
    ids = [i for i in splits.read_split_file(args.split_file)[args.split] if i in tracks][:args.songs or None]
    rows = []
    for n, track_id in enumerate(ids):
        track = tracks[track_id]
        if args.synthetic:
            chords = [(s, min(e, args.max_seconds), lab) for s, e, lab in track.chords if s < args.max_seconds]
            audio, _ = synth.render_progression([(lab, e - s) for s, e, lab in chords], sr=spec.sample_rate,
                                                seed=args.seed + n, variety=True)
            raw = features.extract(spec, audio, spec.sample_rate)
            duration = len(audio) / spec.sample_rate
        else:
            if spec.kind != "nnls_bothchroma":
                parser.error("Billboard features are NNLS bothchroma; this model uses " + spec.kind)
            path = billboard.chroma_path(args.chroma, track_id)
            if not path.exists():
                continue
            _, raw = features.load_billboard_bothchroma(path)
            chords, duration = track.chords, track.duration
        beats = [b for b in track.beats if b < duration]
        rows.append(chord_eval.evaluate_track(chords, segments_for(model, raw, spec, beats, duration, **args.decoder)))
    scores = chord_eval.aggregate(rows)
    print(json.dumps({"model": model.id, "features": spec.kind, "split": args.split,
                      "source": "synthetic" if args.synthetic else "billboard-nnls", **scores}, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
