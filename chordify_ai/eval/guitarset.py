"""Score a serving-side acoustic model on GuitarSet: real guitar recordings, real audio.

Unlike the Billboard evaluation (McGill's precomputed NNLS features), this runs the model's
own feature extraction on the recordings, exactly as the server does, then the production
decoder with GuitarSet's annotated beats. References are the lead-sheet ("instructed")
chords of the accompaniment takes, or with ``--labels performed`` the chords actually
played (transcribed from the hexaphonic pickup: 7ths, 6ths, sus, m7b5 …).
GuitarSet: Xi et al., ISMIR 2018, CC BY 4.0, https://zenodo.org/records/3371780
(annotation.zip + audio_mono-mic.zip).

    python -m chordify_ai.eval.guitarset --root GUITARSET --model templates
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from chordify_core import acoustic, audio, features

from ..data.guitarset import load_annotation, read_split, style_of  # noqa: F401 (re-exported)
from . import chords as chord_eval
from .evaluate_model import segments_for

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", required=True, help="folder with annotation/ and audio/ (audio_mono-mic)")
    parser.add_argument("--model", default="templates", help="'templates' or a ChordNet bundle directory")
    parser.add_argument("--mode", default="comp", choices=["comp", "solo"])
    parser.add_argument("--labels", default="instructed", choices=["instructed", "performed"])
    parser.add_argument("--split", default="all", choices=["all", "train", "validation", "test"],
                        help="players from data/splits/guitarset_v1.json (default: all six)")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--decoder", type=json.loads, default={}, help='override decoder settings, e.g. \'{"subdivide": 2}\'')
    args = parser.parse_args(argv)

    model = acoustic.load_acoustic_model(args.model)
    spec = model.feature_spec
    root = Path(args.root)
    players = None if args.split == "all" else set(read_split()[args.split])
    jams = [p for p in sorted((root / "annotation").glob(f"*_{args.mode}.jams"))
            if players is None or p.stem[:2] in players][:args.limit or None]
    rows, by_style = [], defaultdict(list)
    for path in jams:
        wav = root / "audio" / f"{path.stem}_mic.wav"
        if not wav.exists():
            continue
        ref = load_annotation(path, args.labels)
        sr = spec.sample_rate
        y = audio.load_audio(wav, sr)
        raw = features.extract(spec, y, sr)
        duration = len(y) / sr
        beats = [b for b in ref["beats"] if b < duration]
        row = chord_eval.evaluate_track(ref["chords"], segments_for(model, raw, spec, beats, duration, **args.decoder))
        rows.append(row)
        by_style[style_of(path.stem)].append(row)
    report = {"model": model.id, "features": spec.kind, "dataset": f"guitarset-{args.mode}-mic",
              "split": args.split, "labels": args.labels,
              **chord_eval.aggregate(rows),
              "by_style": {style: round(chord_eval.aggregate(r)["majmin"], 4) for style, r in sorted(by_style.items())}}
    print(json.dumps(report, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
