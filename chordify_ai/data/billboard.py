"""McGill Billboard ingestion.

Annotations come from the ChoCo mirror (890 JAMS files + the original
``salami_chords.txt`` files), which needs no Kaggle login:

    git clone --depth 1 --filter=blob:none --no-checkout https://github.com/smashub/choco.git
    git -C choco sparse-checkout set --no-cone 'partitions/billboard/choco/*' 'partitions/billboard/raw/original/*'
    git -C choco checkout HEAD

Features (NNLS ``bothchroma.csv``) come from the Kaggle mirror ``jacobvs/mcgill-billboard``
(``metadata/metadata/<id>/bothchroma.csv``) and are read with
``chordify_core.features.load_billboard_bothchroma``.
"""
from __future__ import annotations

import csv
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from chordify_core import vocab

_SECTION = re.compile(r"^([A-Z]'*|Z'*),\s*([a-z\- ]+),")
_REPEAT = re.compile(r"\|\s*x(\d+)")


@dataclass
class BillboardTrack:
    track_id: str  # Billboard id, e.g. "0681"
    title: str
    artist: str
    duration: float
    chords: list[tuple[float, float, str]]  # (start, end, Harte label), as annotated (not merged)
    tonics: list[tuple[float, str]] = field(default_factory=list)  # (time, tonic) changes
    metres: list[tuple[float, str]] = field(default_factory=list)
    sections: list[dict] = field(default_factory=list)  # start, end, letter, label
    bars: list[dict] = field(default_factory=list)  # start, end, chords, beats_per_bar

    def merged_chords(self) -> list[tuple[float, float, str]]:
        """Consecutive identical labels joined: one entry per chord change."""
        merged: list[list] = []
        for start, end, label in self.chords:
            if merged and merged[-1][2] == label and abs(merged[-1][1] - start) < 1e-3:
                merged[-1][1] = end
            else:
                merged.append([start, end, label])
        return [tuple(m) for m in merged]

    def tonic_at(self, time: float) -> str | None:
        current = self.tonics[0][1] if self.tonics else None
        for change_time, tonic in self.tonics:
            if change_time <= time + 1e-6:
                current = tonic
        return current

    @property
    def beats(self) -> list[float]:
        out = []
        for bar in self.bars:
            n = bar["beats_per_bar"]
            step = (bar["end"] - bar["start"]) / n
            out.extend(bar["start"] + i * step for i in range(n))
        return out

    @property
    def downbeats(self) -> list[float]:
        return [bar["start"] for bar in self.bars]

    def to_dict(self) -> dict:
        return {"track_id": self.track_id, "title": self.title, "artist": self.artist, "duration": self.duration,
                "chords": self.chords, "tonics": self.tonics, "metres": self.metres, "sections": self.sections,
                "bars": self.bars}

    @classmethod
    def from_dict(cls, data: dict) -> "BillboardTrack":
        return cls(data["track_id"], data["title"], data["artist"], data["duration"],
                   [tuple(c) for c in data["chords"]], [tuple(t) for t in data["tonics"]],
                   [tuple(m) for m in data["metres"]], data["sections"], data["bars"])


def beats_per_bar(metre: str | None) -> int:
    """4/4 -> 4, 3/4 -> 3, 12/8 -> 4 (dotted-quarter beats), 6/8 -> 2, 5/4 -> 5."""
    if not metre or "/" not in metre:
        return 4
    numerator, denominator = (int(x) for x in metre.split("/"))
    if denominator == 8 and numerator % 3 == 0 and numerator > 3:
        return numerator // 3
    return numerator


def parse_salami(text: str) -> dict:
    """Parse a Billboard ``salami_chords.txt``: header metadata, tonic/metre changes,
    sections and bars (with ``xN`` phrase repeats expanded)."""
    header, tonics, metres, timed = {}, [], [], []
    pending: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            name, _, value = line[1:].partition(":")
            name, value = name.strip(), value.strip()
            if name in ("tonic", "metre"):
                pending[name] = value
            if name not in header:
                header[name] = value
            continue
        time_text, _, content = line.partition("\t")
        try:
            time = float(time_text)
        except ValueError:
            continue
        if "tonic" in pending:
            tonics.append((time, pending.pop("tonic")))
        if "metre" in pending:
            metres.append((time, pending.pop("metre")))
        timed.append((time, content))

    sections, lines = [], []
    metre = header.get("metre")
    for index, (start, content) in enumerate(timed):
        for change_time, value in metres:
            if abs(change_time - start) < 1e-9:
                metre = value
        end = timed[index + 1][0] if index + 1 < len(timed) else start
        section = _SECTION.match(content)
        if section:
            sections.append({"letter": section.group(1), "label": section.group(2).strip(), "start": start})
        cells = [cell.split() for cell in re.findall(r"\|([^|]*)(?=\|)", content)]
        cells = [cell for cell in cells if cell]
        repeat = _REPEAT.search(content)
        if repeat:
            cells = cells * int(repeat.group(1))
        if cells and end > start:
            lines.append((start, end, cells, beats_per_bar(metre)))

    # A phrase followed by an un-barred fadeout would otherwise be stretched over the whole
    # fade; instead keep the song's typical bar length and repeat the phrase to fill the line.
    lengths = sorted(length for start, end, cells, _ in lines for length in [(end - start) / len(cells)] * len(cells))
    typical = lengths[len(lengths) // 2] if lengths else 0.0
    bars = []
    for start, end, cells, beats in lines:
        length = (end - start) / len(cells)
        n_bars = len(cells)
        if typical and length > 1.5 * typical:
            n_bars = max(len(cells), int(round((end - start) / typical)))
            length = (end - start) / n_bars
        for k in range(n_bars):
            bars.append({"start": round(start + k * length, 4), "end": round(start + (k + 1) * length, 4),
                         "chords": [token for token in cells[k % len(cells)] if token != "."],
                         "beats_per_bar": beats})
    last_end = bars[-1]["end"] if bars else (timed[-1][0] if timed else 0.0)
    for current, following in zip(sections, sections[1:] + [None]):
        current["end"] = following["start"] if following else last_end
    return {"header": header, "tonics": tonics, "metres": metres, "sections": sections, "bars": bars}


def check_intervals(chords: list[tuple[float, float, str]], tolerance: float = 0.05
                    ) -> tuple[list[tuple[float, float, str]], list[str]]:
    """Trim float-rounding overlaps; report real problems (reversed or out-of-order intervals)."""
    issues, clean = [], []
    for i, (start, end, label) in enumerate(chords):
        if end < start - 1e-6:
            issues.append(f"interval {i} ends before it starts ({start:.3f} > {end:.3f})")
        if clean and start < clean[-1][0] - 1e-6:
            issues.append(f"interval {i} starts before interval {i - 1}")
        if clean and clean[-1][1] > start:
            if clean[-1][1] - start > tolerance:
                issues.append(f"interval {i - 1} overlaps interval {i} by {clean[-1][1] - start:.3f} s")
            clean[-1] = (clean[-1][0], start, clean[-1][2])
        clean.append((start, end, label))
    return clean, issues


def load_choco_billboard(choco_root: str | os.PathLike, drop_invalid: bool = True) -> list[BillboardTrack]:
    """All Billboard annotations from a ChoCo checkout.

    Tracks whose chord intervals are corrupt (reversed or out of order; in the current ChoCo
    release only 0974, "Kokomo") are dropped with a warning unless ``drop_invalid`` is False.
    """
    import warnings

    root = Path(choco_root) / "partitions" / "billboard"
    tracks = []
    with open(root / "choco" / "meta.csv", newline="") as handle:
        for row in csv.DictReader(handle):
            jams_path = root / "choco" / "jams" / Path(row["jams_path"]).name
            salami_path = root / "raw" / "original" / row["billboard_id"] / "salami_chords.txt"
            with open(jams_path) as jf:
                jam = json.load(jf)
            chord_ann = next(a for a in jam["annotations"] if a["namespace"] == "chord")
            chords = []
            for obs in chord_ann["data"]:
                vocab.parse(obs["value"])  # validate every label up front
                chords.append((float(obs["time"]), float(obs["time"]) + float(obs["duration"]), obs["value"]))
            chords, issues = check_intervals(chords)
            if issues and drop_invalid:
                warnings.warn(f"Billboard {row['billboard_id']} skipped: {issues[0]} ({len(issues)} issues)")
                continue
            salami = parse_salami(salami_path.read_text(encoding="utf-8", errors="replace"))
            tracks.append(BillboardTrack(
                track_id=row["billboard_id"], title=row["track_title"].strip(),
                artist=row["track_performer"].strip(), duration=float(jam["file_metadata"]["duration"]),
                chords=chords, tonics=salami["tonics"], metres=salami["metres"],
                sections=salami["sections"], bars=salami["bars"]))
    tracks.sort(key=lambda t: t.track_id)
    return tracks


def save_tracks(tracks: list[BillboardTrack], path: str | os.PathLike) -> None:
    with open(path, "w") as handle:
        json.dump([t.to_dict() for t in tracks], handle)


def load_tracks(path: str | os.PathLike) -> list[BillboardTrack]:
    with open(path) as handle:
        return [BillboardTrack.from_dict(d) for d in json.load(handle)]


def kaggle_chroma_path(kaggle_root: str | os.PathLike, track_id: str) -> Path:
    return Path(kaggle_root) / "metadata" / "metadata" / track_id / "bothchroma.csv"
