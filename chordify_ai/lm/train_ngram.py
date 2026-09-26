"""Train and evaluate the n-gram + song-cache progression model on Billboard.

    python -m chordify_ai.lm.train_ngram --choco path/to/choco [--out models/progression-ngram/1.0.0]

Trains on the frozen train split only, reports next-chord accuracy on the test split
(overall, corpus-only, and cold start = the first 8 changes of each song), and writes a
model bundle (model.json.gz + bundle.json with the metrics).
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from chordify_core import vocab
from chordify_core.lm import NgramProgressionModel, dedupe, token
from chordify_core.theory import Key

from ..data import billboard, splits

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "models" / "progression-ngram" / "1.0.0"
COLD_START = 8


def track_tokens(track: billboard.BillboardTrack) -> list[str]:
    """Key-relative chord-change tokens, using the annotated (local) tonic."""
    out = []
    for start, _, label in track.merged_chords():
        tonic = track.tonic_at(start)
        if tonic is None or not vocab.parse(label).is_chord:
            continue
        out.append(token(label, Key(vocab.note_to_pc(tonic), "major")))
    return dedupe(out)


def evaluate(model: NgramProgressionModel, sequences: list[list[str]]) -> dict[str, float]:
    hits = {"top1": 0, "top3": 0, "corpus_top1": 0, "corpus_top3": 0, "cold_top1": 0, "cold_top3": 0}
    total = cold = 0
    for seq in sequences:
        for t in range(1, len(seq)):
            history, truth = seq[:t], seq[t]
            mixed, _ = model.distribution(history)
            corpus = model.corpus_distribution(history)
            ranked = sorted(mixed, key=mixed.get, reverse=True)
            ranked_corpus = sorted(corpus, key=corpus.get, reverse=True)
            total += 1
            hits["top1"] += ranked[0] == truth
            hits["top3"] += truth in ranked[:3]
            hits["corpus_top1"] += ranked_corpus[0] == truth
            hits["corpus_top3"] += truth in ranked_corpus[:3]
            if t <= COLD_START:
                cold += 1
                hits["cold_top1"] += ranked[0] == truth
                hits["cold_top3"] += truth in ranked[:3]
    out = {k: v / (cold if k.startswith("cold") else total) for k, v in hits.items()}
    out.update({"transitions": total, "cold_transitions": cold})
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--choco", required=True)
    parser.add_argument("--split-file", default=str(splits.DEFAULT_SPLIT_FILE))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--order", type=int, default=4)
    args = parser.parse_args(argv)

    tracks = {t.track_id: t for t in billboard.load_choco_billboard(args.choco)}
    split = splits.read_split_file(args.split_file)
    train = [track_tokens(tracks[i]) for i in split["train"]]
    test = [track_tokens(tracks[i]) for i in split["test"]]
    model = NgramProgressionModel.fit(train, order=args.order)
    metrics = {name: evaluate(model, seqs) for name, seqs in (("validation", [track_tokens(tracks[i]) for i in split["validation"]]),
                                                               ("test", test))}

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model.save(out / "model.json.gz")
    split_hash = hashlib.sha256(Path(args.split_file).read_bytes()).hexdigest()
    bundle = {
        "id": f"progression-ngram@{out.name}", "role": "lm", "type": "ngram-cache",
        "files": {"model": "model.json.gz",
                  "sha256": hashlib.sha256((out / "model.json.gz").read_bytes()).hexdigest()},
        "tokens": "key-relative '<semitones above tonic>:<maj|min>'",
        "params": {"order": model.order, "min_count": model.min_count, "cache_weight": model.cache_weight,
                   "max_context": model.max_context},
        "training_data": {"dataset": "mcgill-billboard (ChoCo JAMS)", "split": Path(args.split_file).name,
                          "split_sha256": split_hash, "songs": len(train)},
        "metrics": metrics,
        "created": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    (out / "bundle.json").write_text(json.dumps(bundle, indent=1) + "\n")
    print(json.dumps(metrics, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
