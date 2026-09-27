"""Train ChordNet and optionally export a model bundle.

Large-vocabulary model (every chord type; see chordify_ai/TRAINING.md): Billboard's NNLS
features plus GuitarSet recordings, rendered POP909 arrangements and generated songs with
balanced chord types, all through the same NNLS extractor the server uses:
    python -m chordify_ai.train.train_chordnet --source billboard,guitarset,pop909,generated \\
        --vocabulary large --choco CHOCO --chroma FEATURES --guitarset GUITARSET --pop909 POP909 \\
        --out runs/chordnet-large --export models/chordnet-chroma/3.0.0

Billboard only, major/minor (how chordnet-chroma@2.0.0 was trained):
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

import numpy as np

from chordify_core import features
from chordify_core import vocab as vocab_module

from ..data import billboard, guitarset, pop909, render, splits
from ..export.bundle import write_bundle
from ..models.chordnet import ChordNetConfig
from . import sources
from .trainer import TrainConfig, class_frequencies, load_checkpoint, train

SOURCES = ("billboard", "guitarset", "pop909", "generated", "synthetic")
DEFAULT_MIX = {"billboard": 0.45, "generated": 0.25, "pop909": 0.15, "guitarset": 0.15, "synthetic": 1.0}


def parse_mix(text: str | None, names: list[str]) -> dict[str, float]:
    """'billboard=0.5,generated=0.3' -> shares for the chosen sources, summing to 1."""
    mix = {name: DEFAULT_MIX[name] for name in names}
    for item in filter(None, (text or "").split(",")):
        name, _, value = item.partition("=")
        if name not in mix:
            raise ValueError(f"--mix names {name!r}, which is not in --source")
        mix[name] = float(value)
    total = sum(mix.values())
    return {name: share / total for name, share in mix.items()}


def example_weights(groups: dict[str, list[sources.EvalTrack]], mix: dict[str, float]) -> list[float]:
    """Per-song sampling weights: each source gets its share of the crops, and within a
    source longer songs are drawn proportionally more often."""
    weights = []
    for name, tracks in groups.items():
        frames = np.array([len(t.example.features) for t in tracks], dtype=float)
        weights += list(mix[name] * frames / frames.sum())
    return weights


def _sha(path: str | Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", required=True,
                        help="one or more of billboard, guitarset, pop909, generated (comma-separated), or synthetic")
    parser.add_argument("--choco", required=True)
    parser.add_argument("--chroma", "--kaggle", dest="chroma",
                        help="Billboard NNLS features: the extracted McGill billboard-2.0-chordino archive "
                             "or the Kaggle mcgill-billboard download (billboard source)")
    parser.add_argument("--guitarset", help="GuitarSet folder with annotation/ and audio/ (guitarset source)")
    parser.add_argument("--pop909", help="the cloned POP909-Dataset repository (pop909 source)")
    parser.add_argument("--generated", type=int, default=2000, help="number of generated songs (generated source)")
    parser.add_argument("--pop909-songs", type=int, default=0, help="limit POP909 training songs (0 = all)")
    parser.add_argument("--mix", help="share of training crops per source, e.g. billboard=0.45,generated=0.25 "
                                      f"(defaults: {DEFAULT_MIX})")
    parser.add_argument("--soundfont", help="General MIDI soundfont for rendering (default: FluidR3_GM if found)")
    parser.add_argument("--workers", type=int, default=0, help="processes for rendering (0 = all cores but one)")
    parser.add_argument("--split-file", default=str(splits.DEFAULT_SPLIT_FILE))
    parser.add_argument("--features", default="cqt_bothchroma", choices=sorted(features.SPECS),
                        help="synthetic source only; every other source uses nnls_bothchroma")
    parser.add_argument("--songs", type=int, default=0, help="limit Billboard/synthetic training songs (0 = all)")
    parser.add_argument("--val-songs", type=int, default=0)
    parser.add_argument("--max-seconds", type=float, default=None, help="truncate synthetic songs")
    parser.add_argument("--vocabulary", default="majmin", choices=sorted(vocab_module.VOCABULARIES))
    parser.add_argument("--select", default=None,
                        help="validation score that picks the best epoch, e.g. majmin or majmin+large "
                             "(default: majmin for the majmin vocabulary, majmin+large otherwise)")
    parser.add_argument("--class-weight-power", type=float, default=0.5,
                        help="chord-type loss weight = 1 / training frequency ** power (0 turns it off)")
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
    parser.add_argument("--cache", default="data/cache")
    parser.add_argument("--out", required=True)
    parser.add_argument("--export", help="write an ONNX model bundle to this directory")
    parser.add_argument("--bundle-id", default=None)
    args = parser.parse_args(argv)

    names = [n.strip() for n in args.source.split(",") if n.strip()]
    unknown = [n for n in names if n not in SOURCES]
    if unknown or not names:
        parser.error(f"unknown --source {unknown or args.source}; choose from {', '.join(SOURCES)}")
    if "synthetic" in names and len(names) > 1:
        parser.error("synthetic is a smoke-test source and cannot be mixed with the others")
    for name, flag in (("billboard", args.chroma), ("guitarset", args.guitarset), ("pop909", args.pop909)):
        if name in names and not flag:
            parser.error(f"--{'chroma' if name == 'billboard' else name} is required for the {name} source")
    vocabulary = vocab_module.VOCABULARIES[args.vocabulary]
    log = functools.partial(print, flush=True)
    cache = Path(args.cache)

    tracks = {t.track_id: t for t in billboard.load_choco_billboard(args.choco)}
    split = splits.read_split_file(args.split_file)
    train_ids = [i for i in split["train"] if i in tracks][:args.songs or None]  # corrupt tracks were dropped
    val_ids = [i for i in split["validation"] if i in tracks][:args.val_songs or None]
    groups: dict[str, list[sources.EvalTrack]] = {}
    val_tracks: list[sources.EvalTrack] = []
    data_info: dict = {}
    if names == ["synthetic"]:
        spec = features.SPECS[args.features]
        groups["synthetic"] = sources.synthetic([tracks[i] for i in train_ids], vocabulary, spec,
                                                cache / "synthetic", seed=1, max_seconds=args.max_seconds)
        val_tracks = sources.synthetic([tracks[i] for i in val_ids], vocabulary, spec, cache / "synthetic",
                                       seed=2, max_seconds=args.max_seconds)
    else:
        spec = features.NNLS_BOTHCHROMA
        soundfont = None
        if {"pop909", "generated"} & set(names):
            soundfont = render.find_soundfont(args.soundfont)
            if soundfont is None or render.fluidsynth_binary() is None:
                parser.error("rendering needs fluidsynth and a General MIDI soundfont: run tools/get_extra_data.sh")
        if "billboard" in names:
            groups["billboard"] = sources.billboard_chroma([tracks[i] for i in train_ids], args.chroma, vocabulary)
            val_tracks += sources.billboard_chroma([tracks[i] for i in val_ids], args.chroma, vocabulary)
            data_info["billboard"] = {"split": Path(args.split_file).name, "split_sha256": _sha(args.split_file)}
        if "guitarset" in names:
            takes = guitarset.load_guitarset(args.guitarset)
            log("extracting GuitarSet features")
            groups["guitarset"] = sources.guitarset_chroma(guitarset.takes_in(takes, "train"), vocabulary,
                                                           cache / "guitarset")
            val_tracks += sources.guitarset_chroma(guitarset.takes_in(takes, "validation"), vocabulary,
                                                   cache / "guitarset")
            data_info["guitarset"] = {"split": guitarset.SPLIT_FILE.name, "split_sha256": _sha(guitarset.SPLIT_FILE),
                                      "labels": "performed"}
        if "pop909" in names:
            by_id = {s.song_id: s for s in pop909.load_pop909(args.pop909)}
            ids = [i for i in pop909.read_split()["train"] if i in by_id][:args.pop909_songs or None]
            log(f"rendering {len(ids)} POP909 songs")
            groups["pop909"] = sources.rendered_chroma([("pop909", str(by_id[i].midi.parent), 0) for i in ids],
                                                       vocabulary, soundfont, cache / "rendered", args.workers, log)
            data_info["pop909"] = {"split": pop909.SPLIT_FILE.name, "split_sha256": _sha(pop909.SPLIT_FILE)}
        if "generated" in names:
            log(f"rendering {args.generated} generated songs")
            groups["generated"] = sources.rendered_chroma([("generated", i, 0) for i in range(args.generated)],
                                                          vocabulary, soundfont, cache / "rendered", args.workers, log)
            data_info["generated"] = {"generator_version": render.GENERATOR_VERSION,
                                      "quality_share": render.QUALITY_SHARE}
    mix = parse_mix(args.mix, list(groups))
    for name, group in groups.items():
        hours = sum(len(t.example.features) for t in group) / spec.frame_rate / 3600
        data_info.setdefault(name, {}).update({"songs": len(group), "hours": round(hours, 2), "share": round(mix[name], 3)})
        log(f"{name}: {len(group)} songs, {hours:.1f} h, {mix[name]:.0%} of training crops")
    train_tracks = [t for group in groups.values() for t in group]
    weights = example_weights(groups, mix) if len(groups) > 1 else None
    # The decoder prior is corrected towards real music's chord-type frequencies (Billboard's), so
    # the balanced generated songs and the loss weights teach the network rare types without
    # making it guess them where a plain triad is far more likely.
    reference = (class_frequencies([t.example for t in groups["billboard"]], vocabulary)
                 if "billboard" in groups and len(groups) > 1 else None)

    input_kind = "bothchroma" if spec.kind.endswith("bothchroma") else "log_cqt"
    model_config = ChordNetConfig(input=input_kind, n_features=25 if input_kind == "bothchroma" else spec.n_bins,
                                  d_model=args.d_model, encoder=args.encoder, n_layers=args.layers)
    select = args.select or ("majmin" if args.vocabulary == "majmin" else "majmin+large")
    config = TrainConfig(vocabulary=args.vocabulary, model=model_config, epochs=args.epochs, items_per_epoch=args.items,
                         batch_size=args.batch_size, lr=args.lr, threads=args.threads, device=args.device,
                         select=select, class_weight_power=args.class_weight_power)
    result = train(config, train_tracks, val_tracks, args.out, log=log, sample_weights=weights,
                   reference_prior=reference)
    print(json.dumps({f"best_{select}": result["best_score"], "epochs": result["epochs"]}, indent=1))
    if not Path(result["checkpoint"]).exists():
        print("no checkpoint yet (stopped before the first epoch finished); nothing to export")
        return 1

    if args.export:
        model, checkpoint = load_checkpoint(result["checkpoint"])
        bundle_id = args.bundle_id or f"chordnet-{'-'.join(names)}@{Path(args.export).name}"
        write_bundle(model, args.export, bundle_id, spec, args.vocabulary, result["prior"],
                     decoder={"alpha": 0.3, "subdivide": 1, "min_units": 1},
                     metrics={"validation": checkpoint["validation"], "epoch": checkpoint["epoch"], "select": select},
                     training_data={"source": ",".join(names), "sources": data_info,
                                    "split": Path(args.split_file).name, "split_sha256": _sha(args.split_file),
                                    "train_songs": len(train_tracks), "validation_songs": len(val_tracks),
                                    "class_weight_power": args.class_weight_power,
                                    "prior_reference": "billboard" if reference is not None else None})
        print(f"bundle written to {args.export}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
