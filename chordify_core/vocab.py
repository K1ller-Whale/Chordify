"""Chord labels: Harte parsing, pitch-class maths and vocabulary tiers.

Everything here works on intervals and pitch classes, never on label strings,
so enharmonic spellings (Bb vs A#, Cb vs B, E# vs F) can never split a class.

Harte syntax (Harte et al., ISMIR 2005): ``root[:shorthand][(degrees)][/bass]``
e.g. ``A:min7``, ``C:maj/3``, ``G:sus4(b7,9)``, ``D:(1,b3,5)``, ``N`` (no chord).
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Iterable

NO_CHORD = "N"
UNKNOWN = "X"

SHARP_NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
FLAT_NAMES = ("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
_NATURALS = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}

# Scale degree -> semitones above the root (compound degrees keep their octave).
_DEGREE_SEMITONES = {1: 0, 2: 2, 3: 4, 4: 5, 5: 7, 6: 9, 7: 11, 8: 12, 9: 14, 10: 16, 11: 17, 12: 19, 13: 21}

# Harte shorthands as degree lists.
SHORTHANDS: dict[str, tuple[str, ...]] = {
    "maj": ("1", "3", "5"),
    "min": ("1", "b3", "5"),
    "dim": ("1", "b3", "b5"),
    "aug": ("1", "3", "#5"),
    "maj7": ("1", "3", "5", "7"),
    "min7": ("1", "b3", "5", "b7"),
    "7": ("1", "3", "5", "b7"),
    "dim7": ("1", "b3", "b5", "bb7"),
    "hdim7": ("1", "b3", "b5", "b7"),
    "minmaj7": ("1", "b3", "5", "7"),
    "maj6": ("1", "3", "5", "6"),
    "min6": ("1", "b3", "5", "6"),
    "9": ("1", "3", "5", "b7", "9"),
    "maj9": ("1", "3", "5", "7", "9"),
    "min9": ("1", "b3", "5", "b7", "9"),
    "11": ("1", "3", "5", "b7", "9", "11"),
    "maj11": ("1", "3", "5", "7", "9", "11"),
    "min11": ("1", "b3", "5", "b7", "9", "11"),
    "13": ("1", "3", "5", "b7", "9", "11", "13"),
    "maj13": ("1", "3", "5", "7", "9", "11", "13"),
    "min13": ("1", "b3", "5", "b7", "9", "11", "13"),
    "sus2": ("1", "2", "5"),
    "sus4": ("1", "4", "5"),
    "5": ("1", "5"),
    "1": ("1",),
}


def note_to_pc(name: str) -> int:
    """'C' -> 0, 'Bb' -> 10, 'E#' -> 5, 'Cbb' -> 10."""
    if not name or name[0] not in _NATURALS:
        raise ValueError(f"not a note name: {name!r}")
    pc = _NATURALS[name[0]]
    for accidental in name[1:]:
        if accidental == "#":
            pc += 1
        elif accidental == "b":
            pc -= 1
        else:
            raise ValueError(f"not a note name: {name!r}")
    return pc % 12


def pc_to_name(pc: int, flats: bool = False) -> str:
    return (FLAT_NAMES if flats else SHARP_NAMES)[pc % 12]


def degree_to_semitones(degree: str) -> int:
    """'b3' -> 3, '#11' -> 18, 'bb7' -> 9."""
    stripped = degree.lstrip("b#")
    modifier = degree[: len(degree) - len(stripped)]
    try:
        base = _DEGREE_SEMITONES[int(stripped)]
    except (ValueError, KeyError):
        raise ValueError(f"not a scale degree: {degree!r}") from None
    return base + modifier.count("#") - modifier.count("b")


@dataclass(frozen=True)
class Chord:
    """A parsed chord. ``root`` is None for N and X."""

    label: str
    root: int | None
    degrees: frozenset[str]  # Harte degree strings actually present, e.g. {"1", "b3", "5", "b7"}
    bass: int | None  # bass pitch class (absolute); equals root for root-position chords

    @property
    def is_chord(self) -> bool:
        return self.root is not None

    @property
    def pitch_classes(self) -> frozenset[int]:
        """Sounding pitch classes; a slash bass is a chord tone too (Harte semantics)."""
        if self.root is None:
            return frozenset()
        tones = {(self.root + degree_to_semitones(d)) % 12 for d in self.degrees}
        return frozenset(tones | {self.bass})

    @property
    def third(self) -> str | None:
        """'maj', 'min' or None (sus/power chords)."""
        if "3" in self.degrees:
            return "maj"
        if "b3" in self.degrees:
            return "min"
        return None

    @property
    def fifth(self) -> str | None:
        """'perfect', 'dim', 'aug' or None."""
        for degree, kind in (("5", "perfect"), ("b5", "dim"), ("#5", "aug")):
            if degree in self.degrees:
                return kind
        return None

    @property
    def seventh(self) -> str | None:
        """'maj' (7), 'min' (b7), 'dim' (bb7) or None."""
        for degree, kind in (("7", "maj"), ("b7", "min"), ("bb7", "dim")):
            if degree in self.degrees:
                return kind
        return None

    @property
    def has_sixth(self) -> bool:
        return "6" in self.degrees and self.seventh is None


@lru_cache(maxsize=4096)
def parse(label: str) -> Chord:
    """Parse a Harte label. Raises ValueError for malformed labels."""
    label = label.strip()
    if label in (NO_CHORD, UNKNOWN):
        return Chord(label, None, frozenset(), None)
    body, _, bass_part = label.partition("/")
    root_part, sep, quality_part = body.partition(":")
    root = note_to_pc(root_part)
    if not sep:
        quality_part = "maj"
    shorthand, paren, extra = quality_part.partition("(")
    if paren and not extra.endswith(")"):
        raise ValueError(f"unbalanced parentheses in {label!r}")
    if shorthand and shorthand not in SHORTHANDS:
        raise ValueError(f"unknown shorthand {shorthand!r} in {label!r}")
    if not shorthand and not paren:
        raise ValueError(f"missing quality in {label!r}")
    degrees = set(SHORTHANDS[shorthand]) if shorthand else set()
    for item in filter(None, (x.strip() for x in extra[:-1].split(","))) if paren else ():
        if item.startswith("*"):
            degrees.discard(item[1:])
        else:
            degree_to_semitones(item)  # validate
            degrees.add(item)
    if not shorthand:
        degrees.add("1")
    bass = root
    if bass_part:
        bass = (root + degree_to_semitones(bass_part)) % 12
    return Chord(label, root, frozenset(degrees), bass)


def triad(chord: Chord) -> str | None:
    """Triad quality: maj, min, dim, aug, sus2, sus4, 5 (power), 1 (single note) or None."""
    if not chord.is_chord:
        return None
    third, fifth = chord.third, chord.fifth
    if third == "maj":
        return "aug" if fifth == "aug" else "maj"
    if third == "min":
        return "dim" if fifth == "dim" else "min"
    if "4" in chord.degrees and fifth == "perfect":
        return "sus4"
    if "2" in chord.degrees and fifth == "perfect":
        return "sus2"
    if fifth == "perfect":
        return "5"
    return "1"


# ---------------------------------------------------------------------------
# Vocabulary tiers
# ---------------------------------------------------------------------------

LARGE_QUALITIES = ("maj", "min", "dim", "aug", "maj6", "min6", "7", "maj7", "min7",
                   "dim7", "hdim7", "minmaj7", "sus2", "sus4")


def _reduce_majmin(chord: Chord) -> str | None:
    kind = triad(chord)
    return kind if kind in ("maj", "min") else None


def _reduce_sevenths(chord: Chord) -> str | None:
    kind, seventh = triad(chord), chord.seventh
    if kind == "maj":
        return {None: "maj", "min": "7", "maj": "maj7"}.get(seventh)
    if kind == "min":
        return {None: "min", "min": "min7"}.get(seventh)
    return None


def _reduce_large(chord: Chord) -> str | None:
    kind, seventh = triad(chord), chord.seventh
    table = {
        ("maj", None): "maj6" if chord.has_sixth else "maj",
        ("min", None): "min6" if chord.has_sixth else "min",
        ("maj", "min"): "7", ("maj", "maj"): "maj7",
        ("min", "min"): "min7", ("min", "maj"): "minmaj7",
        ("dim", None): "dim", ("dim", "dim"): "dim7", ("dim", "min"): "hdim7",
        ("aug", None): "aug", ("aug", "min"): "aug", ("aug", "maj"): "aug",
        ("sus2", None): "sus2", ("sus4", None): "sus4",
        ("sus2", "min"): "sus2", ("sus4", "min"): "sus4",
    }
    return table.get((kind, seventh))


@dataclass(frozen=True)
class Vocabulary:
    """A fixed list of chord classes. Class ``i < 12*Q`` is ``root = i % 12``,
    ``quality = qualities[i // 12]``; ``N`` is the last class."""

    name: str
    qualities: tuple[str, ...]

    @property
    def size(self) -> int:
        return 12 * len(self.qualities) + 1

    @property
    def no_chord(self) -> int:
        return self.size - 1

    @property
    def labels(self) -> tuple[str, ...]:
        return tuple(f"{SHARP_NAMES[i % 12]}:{self.qualities[i // 12]}"
                     for i in range(12 * len(self.qualities))) + (NO_CHORD,)

    def reduce_quality(self, chord: Chord) -> str | None:
        reducer = {"majmin": _reduce_majmin, "sevenths": _reduce_sevenths, "large": _reduce_large}[self.name]
        return reducer(chord)

    def encode(self, label: str) -> int | None:
        """Class index, or None when the chord is outside the vocabulary (X)."""
        chord = parse(label)
        if chord.label == NO_CHORD:
            return self.no_chord
        if chord.root is None:
            return None
        quality = self.reduce_quality(chord)
        if quality is None:
            return None
        return self.qualities.index(quality) * 12 + chord.root

    def decode(self, index: int) -> str:
        return self.labels[index]

    def transpose_index(self, index: int, semitones: int) -> int:
        if index == self.no_chord:
            return index
        return (index // 12) * 12 + (index % 12 + semitones) % 12

    def root_of(self, index: int) -> int | None:
        return None if index == self.no_chord else index % 12

    def quality_of(self, index: int) -> str | None:
        return None if index == self.no_chord else self.qualities[index // 12]


MAJMIN = Vocabulary("majmin", ("maj", "min"))
SEVENTHS = Vocabulary("sevenths", ("maj", "min", "7", "maj7", "min7"))
LARGE = Vocabulary("large", LARGE_QUALITIES)
VOCABULARIES = {v.name: v for v in (MAJMIN, SEVENTHS, LARGE)}


def reduce(label: str, vocabulary: Vocabulary = MAJMIN) -> str:
    """Map any Harte label onto a vocabulary tier, returning 'X' when it has no class."""
    index = vocabulary.encode(label)
    return UNKNOWN if index is None else vocabulary.decode(index)


def _root_text(label: str) -> str:
    end = 1
    while end < len(label) and label[end] in "#b":
        end += 1
    return label[:end]


def transpose(label: str, semitones: int, flats: bool = False) -> str:
    """Transpose a Harte label, keeping its quality, degrees and bass interval."""
    chord = parse(label)
    if not chord.is_chord:
        return chord.label
    return pc_to_name(chord.root + semitones, flats) + label.strip()[len(_root_text(label.strip())):]


def chord_tones(label: str) -> list[int]:
    """12-dim binary vector of the chord's pitch classes (absolute)."""
    tones = [0] * 12
    for pc in parse(label).pitch_classes:
        tones[pc] = 1
    return tones


# ---------------------------------------------------------------------------
# Display
# ---------------------------------------------------------------------------

_DISPLAY_SUFFIX = {
    "maj": "", "min": "m", "dim": "dim", "aug": "aug", "maj7": "maj7", "min7": "m7", "7": "7",
    "dim7": "dim7", "hdim7": "m7b5", "minmaj7": "m(maj7)", "maj6": "6", "min6": "m6", "9": "9",
    "maj9": "maj9", "min9": "m9", "11": "11", "maj11": "maj11", "min11": "m11", "13": "13",
    "maj13": "maj13", "min13": "m13", "sus2": "sus2", "sus4": "sus4", "5": "5", "1": "(1)",
}
_SPECIAL_DISPLAY = {
    "maj(9)": "add9", "min(9)": "m(add9)", "sus4(b7)": "7sus4", "sus4(b7,9)": "9sus4",
    "sus2(b7)": "7sus2", "7(#9)": "7#9", "7(b9)": "7b9", "7(#11)": "7#11", "7(b13)": "7b13",
    "maj6(9)": "6/9", "5(b7)": "7(no3)", "aug(b7)": "aug7",
}


def display_name(label: str, flats: bool | None = None) -> str:
    """Lead-sheet style name: 'A:sus4(b7)' -> 'A7sus4', 'C:maj/3' -> 'C/E', 'N' -> 'N.C.'."""
    chord = parse(label)
    if chord.label == NO_CHORD:
        return "N.C."
    if chord.root is None:
        return "?"
    body, _, bass_part = label.strip().partition("/")
    root_text, sep, quality = body.partition(":")
    if flats is None:
        flats = "b" in root_text[1:]
    quality = quality if sep else "maj"
    if quality in _SPECIAL_DISPLAY:
        suffix = _SPECIAL_DISPLAY[quality]
    else:
        shorthand, paren, extra = quality.partition("(")
        suffix = _DISPLAY_SUFFIX.get(shorthand, shorthand)
        if paren:
            suffix += f"({extra[:-1]})"
    text = root_text + suffix
    if bass_part and chord.bass != chord.root:
        text += "/" + pc_to_name(chord.bass, flats)
    return text


def unique_in_order(labels: Iterable[str]) -> list[str]:
    seen: dict[str, None] = {}
    for label in labels:
        seen.setdefault(label, None)
    return list(seen)
