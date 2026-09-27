"""Train ChordNet and optionally export a model bundle.

Real training (ChordNet-Chroma v2, needs the Billboard NNLS features: McGill's
billboard-2.0-chordino archive, extracted, or the Kaggle mirror):
    python -m chordify_ai.train.train_chordnet --source billboard --choco CHOCO --chroma FEATURES \\
        --out runs/chordnet-chroma --export models/chordnet-chroma/2.0.0

Synthetic pre-training / smoke run (no downloads beyond ChoCo):
    python -m chordify_ai.train.train_chordnet --source synthetic --choco CHOCO --songs 120 --val-songs 20 \\
        --features cqt_bothchroma --epochs 6 --out runs/chordnet-synth
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import json
from pathlib import Path

from chordify_core import features

from ..data import billboard, splits
from ..export.bundle import write_bundle
from ..models.chordnet import ChordNetConfig
from . import sources
from .trainer import TrainConfig, load_checkpoint, train


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", choices=["billboard", "synthetic"], required=True)
    parser.add_argument("--choco", required=True)
    parser.add_argument("--chroma", "--kaggle", dest="chroma",
                        help="Billboard NNLS features: the extracted McGill billboard-2.0-chordino archive "
                             "or the Kaggle mcgill-billboard download (billboard source)")
    parser.add_argument("--split-file", default=str(splits.DEFAULT_SPLIT_FILE))
    parser.add_argument("--features", default="cqt_bothchroma", choices=sorted(features.SPECS),
                        help="synthetic source only; billboard always uses nnls_bothchroma")
    parser.add_argument("--songs", type=int, default=0, help="limit training songs (0 = all)")
    parser.add_argument("--val-songs", type=int, default=0)
    parser.add_argument("--max-seconds", type=float, default=None, help="truncate synthetic songs")
    parser.add_argument("--vocabulary", default="majmin")
    parser.add_argument("--encoder", default="conformer", choices=["conformer", "bigru"])
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--d-model", type=int, default=192)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--items", type=int, default=2000, help="random crops per epoch")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--threads", type=int, default=0)
    parser.add_argument("--device", default="auto",
                        help="auto (CUDA, then Apple's mps, then cpu), cuda, mps or cpu")
    parser.add_argument("--cache", default="data/cache/synthetic")
    parser.add_argument("--out", required=True)
    parser.add_argument("--export", help="write an ONNX model bundle to this directory")
    parser.add_argument("--bundle-id", default=None)
    args = parser.parse_args(argv)

    tracks = {t.track_id: t for t in billboard.load_choco_billboard(args.choco)}
    split = splits.read_split_file(args.split_file)
    train_ids = [i for i in split["train"] if i in tracks][:args.songs or None]  # corrupt tracks were dropped
    val_ids = [i for i in split["validation"] if i in tracks][:args.val_songs or None]
    from chordify_core import vocab as vocab_module
    vocabulary = vocab_module.VOCABULARIES[args.vocabulary]
    if args.source == "billboard":
        if not args.chroma:
            parser.error("--chroma is required for --source billboard")
        spec = features.NNLS_BOTHCHROMA
        train_tracks = sources.billboard_chroma([tracks[i] for i in train_ids], args.chroma, vocabulary)
        val_tracks = sources.billboard_chroma([tracks[i] for i in val_ids], args.chroma, vocabulary)
    else:
        spec = features.SPECS[args.features]
        train_tracks = sources.synthetic([tracks[i] for i in train_ids], vocabulary, spec, args.cache, seed=1,
                                         max_seconds=args.max_seconds)
        val_tracks = sources.synthetic([tracks[i] for i in val_ids], vocabulary, spec, args.cache, seed=2,
                                       max_seconds=args.max_seconds)
    input_kind = "bothchroma" if spec.kind.endswith("bothchroma") else "log_cqt"
    model_config = ChordNetConfig(input=input_kind, n_features=25 if input_kind == "bothchroma" else spec.n_bins,
                                  d_model=args.d_model, encoder=args.encoder, n_layers=args.layers)
    config = TrainConfig(vocabulary=args.vocabulary, model=model_config, epochs=args.epochs, items_per_epoch=args.items,
                         batch_size=args.batch_size, lr=args.lr, threads=args.threads, device=args.device)
    result = train(config, train_tracks, val_tracks, args.out, log=functools.partial(print, flush=True))
    print(json.dumps({"best_majmin": result["best_score"], "epochs": result["epochs"]}, indent=1))
    if not Path(result["checkpoint"]).exists():
        print("no checkpoint yet (stopped before the first epoch finished); nothing to export")
        return 1

    if args.export:
        model, checkpoint = load_checkpoint(result["checkpoint"])
        bundle_id = args.bundle_id or f"chordnet-{args.source}@{Path(args.export).name}"
        write_bundle(model, args.export, bundle_id, spec, args.vocabulary, result["prior"],
                     decoder={"alpha": 0.3, "subdivide": 1, "min_units": 1},
                     metrics={"validation": checkpoint["validation"], "epoch": checkpoint["epoch"]},
                     training_data={"source": args.source, "split": Path(args.split_file).name,
                                    "split_sha256": hashlib.sha256(Path(args.split_file).read_bytes()).hexdigest(),
                                    "train_songs": len(train_tracks), "validation_songs": len(val_tracks)})
        print(f"bundle written to {args.export}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
