"""GuitarSet: real acoustic-guitar recordings with chord, beat and key annotations.

Xi et al., ISMIR 2018, CC BY 4.0, https://zenodo.org/records/3371780 (annotation.zip +
audio_mono-mic.zip, fetched by tools/get_extra_data.sh). Chordify uses the 180
accompaniment ("comp") takes: six players, five styles, and chords that include 7ths,
6ths, sus and m7b5. Each take has two chord annotations: the lead sheet the player was
given ("instructed") and the chords actually played, transcribed from the hexaphonic
pickup ("performed"). Training uses the performed chords.

The split is by player, so no guitarist is in two splits: data/splits/guitarset_v1.json.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from chordify_core import vocab

SPLIT_FILE = Path(__file__).resolve().parents[2] / "data" / "splits" / "guitarset_v1.json"
STYLES = {"BN": "bossa nova", "Funk": "funk", "Jazz": "jazz", "Rock": "rock", "SS": "singer-songwriter"}


@dataclass
class GuitarSetTake:
    take_id: str  # "00_BN1-129-Eb_comp"
    player: str  # "00"
    style: str
    chords: list[tuple[float, float, str]]  # performed
    lead_sheet: list[tuple[float, float, str]]  # instructed
    beats: list[float]
    duration: float
    audio: Path
    key: tuple[int | None, str | None] = (None, None)  # (tonic pitch class, "major" | "minor")
    tonics: list[tuple[float, str]] = field(default_factory=list)


def load_annotation(path: str | Path, labels: str = "instructed") -> dict:
    """Chords (lead-sheet "instructed" or transcribed "performed"), beat times, key and
    duration from a GuitarSet JAMS file."""
    jam = json.loads(Path(path).read_text())
    chord_annotations = [a for a in jam["annotations"] if a["namespace"] == "chord"]
    transcribed = [a for a in chord_annotations
                   if "transcription" in a.get("annotation_metadata", {}).get("data_source", "").lower()]
    if labels == "performed":
        chosen = transcribed[0] if transcribed else chord_annotations[-1]
    else:
        chosen = next((a for a in chord_annotations if a not in transcribed), chord_annotations[0])
    beats = next((a for a in jam["annotations"] if a["namespace"] == "beat_position"), {"data": []})
    key = next((a for a in jam["annotations"] if a["namespace"] == "key_mode"), {"data": []})
    key_value = key["data"][0]["value"] if key["data"] else None
    chords = [(d["time"], d["time"] + d["duration"], d["value"]) for d in chosen["data"]]
    return {"chords": chords, "beats": sorted(d["time"] for d in beats["data"]),
            "duration": float(jam["file_metadata"]["duration"]), "key": key_value}


def style_of(take_id: str) -> str:
    code = take_id.split("_")[1]  # "00_BN1-129-Eb_comp" -> "BN1-129-Eb"
    return next((name for prefix, name in STYLES.items() if code.startswith(prefix)), "other")


def load_guitarset(root: str | Path, mode: str = "comp") -> list[GuitarSetTake]:
    """Every take with both annotations and its microphone recording, sorted by id."""
    root = Path(root)
    takes = []
    for path in sorted((root / "annotation").glob(f"*_{mode}.jams")):
        audio = root / "audio" / f"{path.stem}_mic.wav"
        if not audio.exists():
            continue
        performed, instructed = load_annotation(path, "performed"), load_annotation(path, "instructed")
        tonic_name, _, mode_name = (performed["key"] or "").partition(":")
        tonic = vocab.note_to_pc(tonic_name) if tonic_name else None
        takes.append(GuitarSetTake(path.stem, path.stem[:2], style_of(path.stem), performed["chords"],
                                   instructed["chords"], performed["beats"], performed["duration"], audio,
                                   (tonic, mode_name or None), [(0.0, tonic_name)] if tonic_name else []))
    return takes


def read_split(path: str | Path = SPLIT_FILE) -> dict[str, list[str]]:
    """Players per split: {"train": ["00", ...], "validation": [...], "test": [...]}."""
    data = json.loads(Path(path).read_text())
    return {k: data[k] for k in ("train", "validation", "test")}


def takes_in(takes: list[GuitarSetTake], split: str, path: str | Path = SPLIT_FILE) -> list[GuitarSetTake]:
    players = set(read_split(path)[split])
    return [t for t in takes if t.player in players]
