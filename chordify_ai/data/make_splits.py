"""Create the frozen Billboard split file (run once; the output is committed).

    python -m chordify_ai.data.make_splits --choco path/to/choco [--out data/splits/billboard_v1.json]
"""
from __future__ import annotations

import argparse

from . import billboard, splits

SEED = 20261005
METHOD = "artist-grouped (primary artist ∪ repeated title+artist), 80/10/10, greedy by group size"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--choco", required=True)
    parser.add_argument("--out", default=str(splits.DEFAULT_SPLIT_FILE))
    args = parser.parse_args(argv)
    tracks = billboard.load_choco_billboard(args.choco)
    items = [{"id": t.track_id, "title": t.title, "artist": t.artist} for t in tracks]
    result = splits.make_splits(items, seed=SEED)
    splits.write_split_file(args.out, "mcgill-billboard", result, METHOD, SEED)
    print({k: len(v) for k, v in result.items()}, "->", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
