"""Frozen, artist-grouped dataset splits (plan 02 §5.3).

Songs by the same (primary) artist, and repeat annotations of the same song, always
land in the same split, so no test song shares a performer with training data.
Split files are committed and never regenerated; changing the method means a new
file name.
"""
from __future__ import annotations

import json
import random
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

_SPLIT_ARTIST = re.compile(r"\s*(?:,|&|\band\b|\bfeaturing\b|\bfeat\.?|\bwith\b|\bvs\.?|/)\s*")


def normalise(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    text = re.sub(r"^the\s+", "", text.strip())
    return re.sub(r"[^a-z0-9 ]", "", text).strip()


def primary_artist(artist: str) -> str:
    return normalise(_SPLIT_ARTIST.split(artist.strip(), maxsplit=1)[0])


def group_items(items: list[dict]) -> dict[str, list[str]]:
    """Union of 'same primary artist' and 'same normalised title + artist' groups.

    ``items``: ``[{"id", "title", "artist"}, ...]`` -> ``{group_key: [ids]}``.
    """
    parent: dict[str, str] = {}

    def find(x: str) -> str:
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        parent[find(a)] = find(b)

    for item in items:
        node = f"id:{item['id']}"
        union(node, f"artist:{primary_artist(item['artist'])}")
        union(node, f"song:{normalise(item['title'])}|{normalise(item['artist'])}")
    groups: dict[str, list[str]] = defaultdict(list)
    for item in items:
        groups[find(f"id:{item['id']}")].append(item["id"])
    return {min(ids): sorted(ids) for ids in groups.values()}


def make_splits(items: list[dict], fractions=(0.8, 0.1, 0.1), seed: int = 20261005,
                names=("train", "validation", "test")) -> dict[str, list[str]]:
    """Assign whole groups to splits so each split approaches its fraction of items."""
    groups = list(group_items(items).values())
    rng = random.Random(seed)
    rng.shuffle(groups)
    groups.sort(key=len, reverse=True)  # place big groups first; ties keep the shuffled order
    total = sum(len(g) for g in groups)
    targets = [f * total for f in fractions]
    splits: dict[str, list[str]] = {name: [] for name in names}
    for group in groups:
        # the split furthest below its target (relative) takes the group
        deficits = [(len(splits[n]) + len(group)) / max(t, 1e-9) for n, t in zip(names, targets)]
        splits[names[deficits.index(min(deficits))]].extend(group)
    return {name: sorted(ids) for name, ids in splits.items()}


def write_split_file(path: str | Path, dataset: str, splits: dict[str, list[str]], method: str, seed: int) -> None:
    payload = {"dataset": dataset, "method": method, "seed": seed,
               "counts": {k: len(v) for k, v in splits.items()}, **splits}
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(payload, indent=1) + "\n")


def read_split_file(path: str | Path) -> dict[str, list[str]]:
    data = json.loads(Path(path).read_text())
    return {k: data[k] for k in ("train", "validation", "test")}


DEFAULT_SPLIT_FILE = Path(__file__).resolve().parents[2] / "data" / "splits" / "billboard_v1.json"
