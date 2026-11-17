"""Golden files for the Android engine, the Kotlin port of chordify_core.

    python -m chordify_ai.export.mobile_goldens                 # every file
    python -m chordify_ai.export.mobile_goldens --only vocab    # one file

Each file holds inputs and what chordify_core returns for them. The engine's JVM tests
(Mobile/engine/src/test) replay the inputs through the Kotlin port and compare, and
tests/ai/test_mobile_goldens.py fails when chordify_core changes and the files are stale.
Inputs are deterministic and never come from numpy's random generators, whose streams may
change between numpy releases.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from chordify_core import theory, vocab
from chordify_core.theory import Key

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "Mobile" / "engine" / "src" / "test" / "resources" / "goldens"


class Lcg:
    """64-bit linear congruential generator: the same numbers on every platform."""

    def __init__(self, seed: int):
        self.state = seed

    def random(self) -> float:
        self.state = (6364136223846793005 * self.state + 1442695040888963407) % 2**64
        return (self.state >> 11) / 2**53


def _attempt(fn, *args) -> dict:
    """{"value": fn(*args)}, or {"error": True} when it raises ValueError."""
    try:
        return {"value": fn(*args)}
    except ValueError:
        return {"error": True}


# ---------------------------------------------------------------------------
# vocab
# ---------------------------------------------------------------------------

EXTRA_LABELS = [
    "N", "X", "C", "C:maj/3", "C:maj/5", "G:7/b7", "G:7/3", "A:sus4(b7)", "A:sus4(b7,9)", "D:(1,b3,5)", "C:min(9)",
    "C:maj(9)", "C:maj(*5)", "C:7(#9)", "C:7(b9)", "C:7(#11)", "C:7(b13)", "C:maj6(9)", "C:5(b7)", "C:aug(b7)",
    "A:9", "A:maj9", "A:min9", "G:11", "G:min11", "G:13", "G:maj13", "C:5", "C:1", "Bb:min7", "E#:maj", "Cb:maj",
    "Fb:min", "B#:7", "Cbb:maj", "F#:hdim7/b3", "Eb:dim7/bb7", "Ab:minmaj7/5", "D:sus2/5", "E:maj6/6", " C:min ",
    "C:maj/b7", "C:min/2", "Gb:maj7/7",
]
INVALID_LABELS = ["H:maj", "C:foo", "C:maj(3", "C:", "", "c:maj", "C:maj/x", "C:(1,13x)", "Cx:maj"]
DISPLAY_NAMES = [
    "Em", "Bm7", "C/E", "A7sus4", "N.C.", "nc", "N", "Cmaj7", "CM7", "CΔ", "F#m7b5", "Bbø", "Bb°7", "Bbo", "G+",
    "Gaug7", "C6/9", "Dsus", "Dsus2", "Am(maj7)", "AmMaj7", "Em/G", "D/F#", "G7/F", "Fadd9", "Fmadd9", "Fm(add9)",
    "C-7", "C-", "Cdom7", "E9sus4", "Ab7#9", "Ab7b9", "Db6", "Dbm6", "C5", "C13", "Cm11", "C:min7", "Bb:maj/3",
    "Hm", "Cx7", "", "C/H", "C:foo", "Cmaj7#11",
]
WITH_BASS_LABELS = ["C:maj", "C:maj7", "G:7", "A:min", "B:hdim7", "D:sus4", "N", "X"]


def _inversions() -> list[str]:
    """Every chord type of the large vocabulary on D, over each of its chord tones."""
    return [f"D:{quality}/{degree}" for quality in vocab.LARGE_QUALITIES
            for degree in sorted(vocab.parse(f"D:{quality}").degrees - {"1"})]


def _chord_row(label: str) -> dict:
    chord = vocab.parse(label)
    row = {
        "label": label, "parsed": chord.label, "root": chord.root, "bass": chord.bass,
        "degrees": sorted(chord.degrees), "pitch_classes": sorted(chord.pitch_classes),
        "third": chord.third, "fifth": chord.fifth, "seventh": chord.seventh, "has_sixth": chord.has_sixth,
        "triad": vocab.triad(chord), "chord_tones": vocab.chord_tones(label),
        "display": {"auto": vocab.display_name(label), "flats": vocab.display_name(label, True),
                    "sharps": vocab.display_name(label, False)},
        "transpose": {"-3": vocab.transpose(label, -3), "-3b": vocab.transpose(label, -3, True),
                      "5": vocab.transpose(label, 5), "5b": vocab.transpose(label, 5, True)},
    }
    for name, vocabulary in vocab.VOCABULARIES.items():
        row[name] = {"encode": vocabulary.encode(label), "reduce": vocab.reduce(label, vocabulary),
                     "reduce_keep_bass": vocab.reduce_keep_bass(label, vocabulary),
                     "simplify": vocab.simplify(label, vocabulary)}
    return row


def vocab_golden() -> dict:
    for label in INVALID_LABELS:
        assert "error" in _attempt(vocab.parse, label), f"{label!r} parses; it does not belong in INVALID_LABELS"
    labels = list(dict.fromkeys(list(vocab.LARGE.labels) + _inversions() + EXTRA_LABELS))
    rows = [_chord_row(label) for label in labels]
    names = list(dict.fromkeys(DISPLAY_NAMES + [r["display"]["auto"] for r in rows]
                               + [r["display"]["flats"] for r in rows]))
    return {
        "chords": rows,
        "invalid": INVALID_LABELS,
        "vocabularies": [{"name": v.name, "qualities": list(v.qualities), "size": v.size, "no_chord": v.no_chord,
                          "labels": list(v.labels),
                          "transpose_index": {str(k): [v.transpose_index(i, k) for i in range(v.size)] for k in (-5, 7)},
                          "root_of": [v.root_of(i) for i in range(v.size)],
                          "quality_of": [v.quality_of(i) for i in range(v.size)]}
                         for v in vocab.VOCABULARIES.values()],
        "with_bass": [{"label": label, "bass": pc, "result": vocab.with_bass(label, pc)}
                      for label in WITH_BASS_LABELS for pc in range(12)],
        "parse_display": [{"name": name, **_attempt(vocab.parse_display, name)} for name in names],
    }


# ---------------------------------------------------------------------------
# theory
# ---------------------------------------------------------------------------

PARSE_KEY_TEXTS = ["D", "D:maj", "D major", "Bb:min", "F#m", "A minor", "C:minor", "Eb maj", "G#:m", "FM",
                   "Db  major", "Gbb:min", " E ", "C#:major", "a minor", "H", "", "C mixolydian"]
DESCRIBE_KEYS = [Key(0, "major"), Key(3, "major"), Key(6, "major"), Key(9, "minor"), Key(0, "minor"), Key(8, "minor")]
DESCRIBE_EXTRA = ["X", "C:maj/3", "G:7/b7", "A:sus4(b7)", "C:maj(9)", "F#:hdim7/b3", "C:5"]
PAIR_KEYS = [Key(0, "major"), Key(9, "minor"), Key(3, "major"), Key(6, "minor")]
PAIR_LABELS = (list(vocab.MAJMIN.labels[:-1]) + [f"{n}:7" for n in vocab.SHARP_NAMES]
               + [f"{n}:dim" for n in vocab.SHARP_NAMES] + ["N"])
ESTIMATE_CASES = [
    [("C:maj", 2.0), ("G:maj", 2.0), ("A:min", 2.0), ("F:maj", 2.0)] * 2,
    [("A:min", 2.0), ("G:maj", 2.0), ("F:maj", 2.0), ("E:maj", 2.0)] * 2,
    [("D:maj", 4.0), ("A:7", 4.0), ("B:min", 4.0), ("G:maj", 4.0)],
    [("C:min7", 4.0), ("F:7", 4.0), ("Bb:maj7", 8.0)],
    [("E:maj", 1.0)],
    [("N", 3.0), ("C:maj", 1.0), ("N", 2.0)],
    [("N", 3.0)],
    [],
]
PATTERN_CASES = [
    {"numerals": ["I", "V", "vi", "IV"] * 4},
    {"numerals": ["vi", "IV", "I", "V"] * 3 + ["vi"]},
    {"numerals": ["I", "I", "V", "V", "vi", "IV"] * 3},
    {"numerals": ["i", "VII", "VI", "V"] * 3},
    {"numerals": ["ii", "V", "I", "ii", "V", "I", "ii", "V", "I", "vii°", "I"]},
    {"numerals": ["I", None, "V", "", "vi°", "IV", "I", "V", "vi", "IV", "I", "V", "vi", "IV"]},
    {"numerals": ["I", "bVII", "IV", "I"] * 3, "lengths": [4, 3, 2], "min_count": 2, "limit": 5},
    {"numerals": ["I", "IV"] * 5, "lengths": [2], "min_count": 2},
    {"numerals": []},
]


def _key_score_inputs() -> list[list[float]]:
    rng = Lcg(12)
    major = [1.0 if pc in (0, 2, 4, 5, 7, 9, 11) else 0.0 for pc in range(12)]
    minor = [1.0 if pc in (9, 11, 0, 2, 4, 5, 8) else 0.0 for pc in range(12)]
    noisy = [[round(rng.random() * 3, 6) for _ in range(12)] for _ in range(3)]
    return [major, minor, [0.0] * 12, *noisy]


def _parse_key(text: str) -> dict:
    key = theory.parse_key(text)
    return {"tonic": key.tonic, "mode": key.mode}


def theory_golden() -> dict:
    describe_labels = list(vocab.LARGE.labels) + DESCRIBE_EXTRA
    estimates = []
    for chords in ESTIMATE_CASES:
        key, confidence = theory.estimate_key_from_chords(chords)
        estimates.append({"chords": [[label, duration] for label, duration in chords],
                          "tonic": key.tonic, "mode": key.mode, "confidence": confidence})
    patterns = []
    for case in PATTERN_CASES:
        options = {k: v for k, v in case.items() if k != "numerals"}
        patterns.append({**case, "result": theory.find_patterns(case["numerals"], **options)})
    return {
        "keys": [{"tonic": k.tonic, "mode": k.mode, "flats": k.flats, "tonic_name": k.tonic_name, "name": k.name,
                  "scale": list(k.scale)} for k in theory.ALL_KEYS],
        "parse_key": [{"text": text, **_attempt(_parse_key, text)} for text in PARSE_KEY_TEXTS],
        "describe": [{"tonic": k.tonic, "mode": k.mode, "label": label, "diatonic": theory.is_diatonic(label, k),
                      **theory.describe(label, k)} for k in DESCRIBE_KEYS for label in describe_labels],
        "pairs": [{"tonic": k.tonic, "mode": k.mode, "labels": PAIR_LABELS,
                   "reason": [[theory.transition_reason(a, b, k) for b in PAIR_LABELS] for a in PAIR_LABELS],
                   "cadence": [[theory.cadence(a, b, k) for b in PAIR_LABELS] for a in PAIR_LABELS]}
                  for k in PAIR_KEYS],
        "key_scores": [{"chroma": chroma,
                        "scores": [[k.tonic, k.mode, score] for k, score in theory.key_scores(np.asarray(chroma))]}
                       for chroma in _key_score_inputs()],
        "estimate_key": estimates,
        "patterns": patterns,
    }


FILES = {"vocab": vocab_golden, "theory": theory_golden}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--only", choices=sorted(FILES), action="append")
    args = parser.parse_args(argv)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for name in args.only or FILES:
        text = json.dumps(FILES[name](), ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        (out / f"{name}.json").write_text(text + "\n", encoding="utf-8")
        print(f"{name}.json: {len(text) / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
