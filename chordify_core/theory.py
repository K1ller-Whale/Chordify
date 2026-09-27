"""Deterministic music theory: keys, Roman numerals, harmonic function, scales, cadences.

It explains progressions and predictions in musicians' terms; it never predicts
anything itself (plan 03 §5.5).
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np

from . import vocab

MAJOR_SCALE = (0, 2, 4, 5, 7, 9, 11)
NATURAL_MINOR = (0, 2, 3, 5, 7, 8, 10)
# Minor keys also use the raised 7th (harmonic minor), which makes V and vii° diatonic.
MINOR_SCALE_EXTENDED = NATURAL_MINOR + (11,)

_DEGREE_NAMES = {
    "major": {0: "I", 1: "bII", 2: "II", 3: "bIII", 4: "III", 5: "IV", 6: "#IV", 7: "V", 8: "bVI", 9: "VI",
              10: "bVII", 11: "VII"},
    "minor": {0: "I", 1: "bII", 2: "II", 3: "III", 4: "#III", 5: "IV", 6: "#IV", 7: "V", 8: "VI", 9: "#VI",
              10: "VII", 11: "#VII"},
}
_FUNCTIONS = {
    "major": {0: "tonic", 4: "tonic", 9: "tonic", 2: "subdominant", 5: "subdominant", 7: "dominant",
              11: "dominant"},
    "minor": {0: "tonic", 3: "tonic", 8: "tonic", 2: "subdominant", 5: "subdominant", 7: "dominant",
              10: "dominant", 11: "dominant"},
}
_MODES = {
    "major": {0: "Ionian", 2: "Dorian", 4: "Phrygian", 5: "Lydian", 7: "Mixolydian", 9: "Aeolian", 11: "Locrian"},
    "minor": {0: "Aeolian", 2: "Locrian", 3: "Ionian", 5: "Dorian", 7: "Phrygian", 8: "Lydian", 10: "Mixolydian"},
}
# Keys conventionally written with flats.
_FLAT_MAJOR = {5, 10, 3, 8, 1}  # F Bb Eb Ab Db (F# major preferred over Gb)
_FLAT_MINOR = {2, 7, 0, 5, 10, 3}  # D G C F Bb Eb

# Krumhansl & Kessler (1982) key profiles.
_KK_MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_KK_MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


@dataclass(frozen=True)
class Key:
    tonic: int
    mode: str = "major"  # "major" | "minor"

    @property
    def flats(self) -> bool:
        return self.tonic in (_FLAT_MAJOR if self.mode == "major" else _FLAT_MINOR)

    @property
    def tonic_name(self) -> str:
        return vocab.pc_to_name(self.tonic, self.flats)

    @property
    def name(self) -> str:
        return f"{self.tonic_name} {self.mode}"

    @property
    def scale(self) -> tuple[int, ...]:
        return MAJOR_SCALE if self.mode == "major" else MINOR_SCALE_EXTENDED

    def contains(self, pitch_classes: Iterable[int]) -> bool:
        scale = {(self.tonic + d) % 12 for d in self.scale}
        return all(pc in scale for pc in pitch_classes)

    def spell(self, pc: int) -> str:
        return vocab.pc_to_name(pc, self.flats)

    def to_dict(self) -> dict:
        return {"tonic": self.tonic_name, "mode": self.mode}


def parse_key(text: str) -> Key:
    """'D', 'D:maj', 'D major', 'Bb:min', 'F#m', 'A minor' -> Key."""
    text = text.strip()
    match = re.fullmatch(r"([A-G][#b]*)\s*(?::?\s*(maj|major|min|minor|m))?", text, flags=re.IGNORECASE)
    if not match:
        raise ValueError(f"not a key: {text!r}")
    mode = (match.group(2) or "maj").lower()
    return Key(vocab.note_to_pc(match.group(1)), "minor" if mode in ("min", "minor", "m") else "major")


ALL_KEYS = tuple(Key(t, m) for m in ("major", "minor") for t in range(12))


def key_scores(chroma: np.ndarray) -> list[tuple[Key, float]]:
    """Correlate a 12-bin pitch-class distribution with Krumhansl–Kessler profiles."""
    chroma = np.asarray(chroma, dtype=np.float64)
    if chroma.ndim == 2:
        chroma = chroma.sum(axis=0)
    scores = []
    for key in ALL_KEYS:
        profile = np.roll(_KK_MAJOR if key.mode == "major" else _KK_MINOR, key.tonic)
        scores.append((key, float(np.corrcoef(chroma, profile)[0, 1]) if chroma.std() > 0 else 0.0))
    return sorted(scores, key=lambda kv: -kv[1])


def estimate_key_from_chords(chords: Sequence[tuple[str, float]]) -> tuple[Key, float]:
    """Key from ``[(label, duration), ...]``: duration-weighted chord tones correlated with
    key profiles, plus a bonus for keys whose tonic chord opens or closes the song.

    Returns (key, confidence in 0..1: the softmax margin over the 24 candidates).
    """
    histogram = np.zeros(12)
    real = [(vocab.parse(lab), dur) for lab, dur in chords if vocab.parse(lab).is_chord]
    if not real:
        return Key(0, "major"), 0.0
    for chord, duration in real:
        for pc in chord.pitch_classes:
            histogram[pc] += duration
        histogram[chord.root] += 0.5 * duration
    scored = []
    for key, score in key_scores(histogram):
        for chord, _ in (real[0], real[-1]):
            triad = vocab.triad(chord)
            if chord.root == key.tonic and triad == ("maj" if key.mode == "major" else "min"):
                score += 0.1
        scored.append((key, score))
    scored.sort(key=lambda kv: -kv[1])
    values = np.array([s for _, s in scored]) * 20.0
    probs = np.exp(values - values.max())
    probs /= probs.sum()
    return scored[0][0], float(probs[0])


# ---------------------------------------------------------------------------
# Chords in a key
# ---------------------------------------------------------------------------

def degree(label: str, key: Key) -> int | None:
    chord = vocab.parse(label)
    return None if chord.root is None else (chord.root - key.tonic) % 12


def roman(label: str, key: Key, with_quality: bool = False) -> str | None:
    """Roman numeral relative to the key: 'V', 'vi', 'bVII', 'vii°', 'V/V'.

    Minor-family chords are lower case; diminished get °, half-diminished ø, augmented +.
    """
    chord = vocab.parse(label)
    if chord.root is None:
        return None
    secondary = secondary_dominant(label, key)
    if secondary:
        return secondary
    offset = (chord.root - key.tonic) % 12
    numeral = _DEGREE_NAMES[key.mode][offset]
    kind = vocab.triad(chord)
    if kind in ("min", "dim"):
        numeral = re.sub(r"[IV]+", lambda m: m.group(0).lower(), numeral)
    if kind == "dim":
        numeral += "ø" if chord.seventh == "min" else "°"
    elif kind == "aug":
        numeral += "+"
    if with_quality:
        numeral += _quality_suffix(chord)
    return numeral


def _quality_suffix(chord: vocab.Chord) -> str:
    kind, seventh = vocab.triad(chord), chord.seventh
    if kind in ("sus2", "sus4"):
        return ("7" if seventh == "min" else "") + kind
    if seventh == "min" and kind not in ("dim",):
        return "7"
    if seventh == "maj":
        return "maj7"
    if seventh == "dim":
        return "7"
    return ""


def is_diatonic(label: str, key: Key) -> bool:
    chord = vocab.parse(label)
    return chord.is_chord and key.contains(chord.pitch_classes)


def secondary_dominant(label: str, key: Key) -> str | None:
    """'V/x' for a non-diatonic major or dominant-7th chord a fifth above a diatonic triad root."""
    chord = vocab.parse(label)
    if not chord.is_chord or is_diatonic(label, key) or vocab.triad(chord) != "maj" or chord.seventh == "maj":
        return None
    target = (chord.root - 7 - key.tonic) % 12
    if target == 0 or target not in key.scale:
        return None
    target_names = {"major": {2: "ii", 4: "iii", 5: "IV", 7: "V", 9: "vi"},
                    "minor": {3: "III", 5: "iv", 7: "V", 8: "VI", 10: "VII"}}[key.mode]
    return f"V/{target_names[target]}" if target in target_names else None


def function(label: str, key: Key) -> str | None:
    """'tonic' | 'subdominant' | 'dominant' | 'borrowed' (outside the key) | None for N."""
    chord = vocab.parse(label)
    if not chord.is_chord:
        return None
    if not is_diatonic(label, key):
        return "borrowed"
    return _FUNCTIONS[key.mode].get((chord.root - key.tonic) % 12, "borrowed")


def scale_hint(label: str, key: Key) -> str | None:
    """A scale to play over the chord: the key's mode on the chord root when diatonic."""
    chord = vocab.parse(label)
    if not chord.is_chord:
        return None
    root = key.spell(chord.root)
    offset = (chord.root - key.tonic) % 12
    if is_diatonic(label, key):
        if key.mode == "minor" and offset == 7 and vocab.triad(chord) == "maj":
            return f"{root} Phrygian dominant"
        if key.mode == "minor" and offset == 11:
            return f"{root} diminished"
        mode = _MODES[key.mode].get(offset)
        if mode:
            return f"{root} {mode}"
    kind, seventh = vocab.triad(chord), chord.seventh
    if kind == "maj":
        return f"{root} Mixolydian" if seventh == "min" else f"{root} Lydian"
    if kind == "min":
        return f"{root} Dorian"
    if kind in ("sus2", "sus4", "5"):
        return f"{root} Mixolydian"
    if kind == "dim":
        return f"{root} diminished"
    if kind == "aug":
        return f"{root} whole tone"
    return None


def borrowed_from(label: str, key: Key) -> str | None:
    """Parallel-mode source of a non-diatonic chord, e.g. bVII in D major -> 'D minor'."""
    if is_diatonic(label, key) or not vocab.parse(label).is_chord:
        return None
    parallel = Key(key.tonic, "minor" if key.mode == "major" else "major")
    return parallel.name if is_diatonic(label, parallel) else None


def describe(label: str, key: Key) -> dict:
    return {"roman": roman(label, key), "function": function(label, key), "scale_hint": scale_hint(label, key),
            "borrowed_from": borrowed_from(label, key), "secondary": secondary_dominant(label, key)}


# ---------------------------------------------------------------------------
# Progressions
# ---------------------------------------------------------------------------

def _base_numeral(numeral: str | None) -> str | None:
    return None if numeral is None else numeral.rstrip("°ø+")


_TRANSITIONS = {
    ("V", "I"): "authentic cadence V→I", ("V", "i"): "authentic cadence V→i",
    ("V", "vi"): "deceptive cadence V→vi", ("V", "VI"): "deceptive cadence V→VI",
    ("IV", "I"): "plagal motion IV→I", ("iv", "i"): "plagal motion iv→i", ("iv", "I"): "minor plagal iv→I",
    ("ii", "V"): "pre-dominant → dominant (ii→V)", ("IV", "V"): "subdominant → dominant",
    ("vi", "IV"): "vi→IV, the Axis loop", ("I", "vi"): "tonic → relative minor",
    ("I", "IV"): "tonic → subdominant", ("I", "V"): "tonic → dominant", ("V", "IV"): "rock “backdoor” V→IV",
    ("bVII", "I"): "backdoor resolution bVII→I", ("bVI", "bVII"): "bVI→bVII→I rise",
    ("vi", "ii"): "descending fifths vi→ii", ("iii", "vi"): "descending fifths iii→vi",
    ("IV", "vi"): "subdominant → relative minor", ("i", "VII"): "minor-key descent i→VII",
    ("VII", "VI"): "descending bass VII→VI", ("VI", "V"): "Andalusian step VI→V",
}


def transition_reason(prev_label: str, next_label: str, key: Key) -> str | None:
    """Short theory explanation of a chord change, or None if nothing notable."""
    a, b = _base_numeral(roman(prev_label, key)), _base_numeral(roman(next_label, key))
    if a is None or b is None or a == b:
        return None
    if b.startswith("V/"):
        return f"secondary dominant {b} leading to {b[2:]}"
    if a.startswith("V/"):
        return f"{a} resolves"
    if (a, b) in _TRANSITIONS:
        return _TRANSITIONS[(a, b)]
    borrowed = borrowed_from(next_label, key)
    if borrowed:
        return f"{b} borrowed from {borrowed}"
    fa, fb = function(prev_label, key), function(next_label, key)
    if fa and fb and fa != fb and "borrowed" not in (fa, fb):
        return f"{fa} → {fb}"
    return None


def cadence(prev_label: str, next_label: str, key: Key) -> str | None:
    a, b = _base_numeral(roman(prev_label, key)), _base_numeral(roman(next_label, key))
    if a is None or b is None:
        return None
    if a == "V" and b.upper() == "I":
        return "authentic"
    if a.upper() == "IV" and b.upper() == "I":
        return "plagal"
    if a == "V" and b.upper() == "VI":
        return "deceptive"
    if b == "V" and a != "V":
        return "half"
    return None


NAMED_PROGRESSIONS = {
    ("I", "V", "vi", "IV"): "I–V–vi–IV (the “Axis” progression)",
    ("I", "vi", "IV", "V"): "I–vi–IV–V (the ’50s progression)",
    ("vi", "IV", "I", "V"): "vi–IV–I–V (Axis, minor rotation)",
    ("I", "IV", "V", "IV"): "I–IV–V–IV (rock three-chord loop)",
    ("ii", "V", "I"): "ii–V–I",
    ("i", "VII", "VI", "V"): "i–VII–VI–V (Andalusian cadence)",
    ("I", "bVII", "IV", "I"): "I–bVII–IV–I (Mixolydian vamp)",
    ("I", "bVII", "IV"): "I–bVII–IV (Mixolydian vamp)",
    ("I", "IV", "V"): "I–IV–V (three-chord trick)",
}


def _rotations(pattern: tuple[str, ...]) -> list[tuple[str, ...]]:
    return [pattern[i:] + pattern[:i] for i in range(len(pattern))]


def _inside_loop(small: tuple[str, ...], loop: tuple[str, ...]) -> bool:
    """True if ``small`` is a contiguous piece of ``loop`` played cyclically."""
    doubled = loop + loop
    return len(small) <= len(loop) and any(doubled[i:i + len(small)] == small for i in range(len(loop)))


def find_patterns(numerals: Sequence[str], lengths: Sequence[int] = (4, 3), min_count: int = 3,
                  limit: int = 3) -> list[dict]:
    """Repeating loops in a chord-change sequence (Roman numerals), rotations merged.

    Returns ``[{"roman": [...], "name": str | None, "count": n}, ...]`` most frequent first.
    """
    base = [_base_numeral(n) for n in numerals if n]
    seq = [n for i, n in enumerate(base) if i == 0 or base[i - 1] != n]
    found: list[dict] = []
    covered: set[tuple[str, ...]] = set()
    for length in lengths:
        grams = Counter(tuple(seq[i:i + length]) for i in range(len(seq) - length + 1))
        classes: dict[tuple[str, ...], Counter] = {}
        for gram, count in grams.items():
            if len(set(gram)) < 2:
                continue
            canonical = min(_rotations(gram))
            classes.setdefault(canonical, Counter())[gram] = count
        for canonical, members in classes.items():
            rotation, count = members.most_common(1)[0]
            if count < min_count or any(_inside_loop(rotation, c) for c in covered):
                continue
            # Several rotations of a loop can have names (I–V–vi–IV and vi–IV–I–V); the table's
            # order decides which one is shown, so a loop is always presented the same way.
            rotations = set(_rotations(rotation))
            canonical_rotation = next((r for r in NAMED_PROGRESSIONS if r in rotations), None)
            name = NAMED_PROGRESSIONS[canonical_rotation] if canonical_rotation else None
            if canonical_rotation:
                rotation = canonical_rotation
            found.append({"roman": list(rotation), "name": name, "count": count})
            covered.add(canonical)
    found.sort(key=lambda p: (-p["count"], -len(p["roman"])))
    return found[:limit]
