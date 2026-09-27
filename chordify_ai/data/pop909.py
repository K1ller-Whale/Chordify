"""POP909: 909 Chinese-pop songs as piano arrangements (MIDI) with chord, beat and key labels.

Wang et al., ISMIR 2020, MIT licence, https://github.com/music-x-lab/POP909-Dataset (cloned
by tools/get_extra_data.sh). Each song has MELODY, BRIDGE and PIANO tracks aligned to the
original recording, ``chord_midi.txt`` (chords extracted from the MIDI, Harte syntax, with
inversions such as C:maj/3), ``beat_midi.txt`` and ``key_audio.txt``. Chordify renders the
MIDI through FluidSynth with varied instruments (``chordify_ai.data.render``), so its role is
to add inversions, sus chords and piano-led pop arrangements to the training data.

Split by song number (frozen in data/splits/pop909_v1.json): numbers ending in 0 are test,
ending in 1 validation, the rest train.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

SPLIT_FILE = Path(__file__).resolve().parents[2] / "data" / "splits" / "pop909_v1.json"


@dataclass
class Pop909Song:
    song_id: str  # "001"
    midi: Path
    chords: list[tuple[float, float, str]]
    beats: list[float]
    keys: list[tuple[float, str]]  # (start time, "Gb:maj")

    @property
    def duration(self) -> float:
        return self.chords[-1][1] if self.chords else 0.0


def _rows(path: Path) -> list[list[str]]:
    return [line.split() for line in path.read_text().splitlines() if line.strip()]


def load_song(folder: str | Path) -> Pop909Song:
    folder = Path(folder)
    song_id = folder.name
    chords = [(float(r[0]), float(r[1]), r[2]) for r in _rows(folder / "chord_midi.txt")]
    beats = [float(r[0]) for r in _rows(folder / "beat_midi.txt")]
    key_file = folder / "key_audio.txt"
    keys = [(float(r[0]), r[2]) for r in _rows(key_file)] if key_file.exists() else []
    return Pop909Song(song_id, folder / f"{song_id}.mid", chords, beats, keys)


def load_pop909(root: str | Path) -> list[Pop909Song]:
    """Every song folder under ``root`` (the cloned repository or its POP909/ folder)."""
    root = Path(root)
    base = root / "POP909" if (root / "POP909").is_dir() else root
    return [load_song(folder) for folder in sorted(base.iterdir())
            if folder.is_dir() and folder.name.isdigit() and (folder / "chord_midi.txt").exists()]


def read_split(path: str | Path = SPLIT_FILE) -> dict[str, list[str]]:
    data = json.loads(Path(path).read_text())
    return {k: data[k] for k in ("train", "validation", "test")}


def make_split(song_ids: list[str]) -> dict[str, list[str]]:
    test = [s for s in song_ids if int(s) % 10 == 0]
    validation = [s for s in song_ids if int(s) % 10 == 1]
    train = [s for s in song_ids if int(s) % 10 not in (0, 1)]
    return {"train": train, "validation": validation, "test": test}
