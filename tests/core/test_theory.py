import numpy as np
import pytest

from chordify_core import theory
from chordify_core.theory import Key, parse_key

D_MAJOR = Key(2, "major")
C_MAJOR = Key(0, "major")
A_MINOR = Key(9, "minor")


@pytest.mark.parametrize("text, expected", [
    ("D", Key(2, "major")), ("D:maj", Key(2, "major")), ("Bb:min", Key(10, "minor")),
    ("F#m", Key(6, "minor")), ("A minor", Key(9, "minor")), ("Eb major", Key(3, "major")),
])
def test_parse_key(text, expected):
    assert parse_key(text) == expected


def test_key_spelling():
    assert Key(10, "major").name == "Bb major"
    assert Key(6, "major").name == "F# major"
    assert Key(3, "minor").name == "Eb minor"
    assert Key(2, "minor").spell(10) == "Bb"


@pytest.mark.parametrize("label, key, numeral", [
    ("D:maj", D_MAJOR, "I"), ("A:sus4(b7)", D_MAJOR, "V"), ("B:min7", D_MAJOR, "vi"),
    ("G:maj9", D_MAJOR, "IV"), ("E:min", D_MAJOR, "ii"), ("C#:dim", D_MAJOR, "vii°"),
    ("C#:hdim7", D_MAJOR, "viiø"), ("C:maj", D_MAJOR, "bVII"), ("E:7", D_MAJOR, "V/V"),
    ("B:7", C_MAJOR, "V/iii"), ("C:7", C_MAJOR, "V/IV"), ("A:min", A_MINOR, "i"),
    ("G:maj", A_MINOR, "VII"), ("E:7", A_MINOR, "V"), ("F:maj", A_MINOR, "VI"), ("C:aug", C_MAJOR, "I+"),
    ("N", C_MAJOR, None),
])
def test_roman(label, key, numeral):
    assert theory.roman(label, key) == numeral


def test_roman_with_quality():
    assert theory.roman("A:sus4(b7)", D_MAJOR, with_quality=True) == "V7sus4"
    assert theory.roman("B:min7", D_MAJOR, with_quality=True) == "vi7"
    assert theory.roman("G:maj7", D_MAJOR, with_quality=True) == "IVmaj7"


@pytest.mark.parametrize("label, key, fn", [
    ("D:maj", D_MAJOR, "tonic"), ("B:min7", D_MAJOR, "tonic"), ("G:maj9", D_MAJOR, "subdominant"),
    ("E:min", D_MAJOR, "subdominant"), ("A:sus4(b7)", D_MAJOR, "dominant"), ("C:maj", D_MAJOR, "borrowed"),
    ("E:7", A_MINOR, "dominant"), ("D:min", A_MINOR, "subdominant"), ("N", D_MAJOR, None),
])
def test_function(label, key, fn):
    assert theory.function(label, key) == fn


@pytest.mark.parametrize("label, key, hint", [
    ("A:sus4(b7)", D_MAJOR, "A Mixolydian"), ("B:min7", D_MAJOR, "B Aeolian"), ("G:maj9", D_MAJOR, "G Lydian"),
    ("E:min", D_MAJOR, "E Dorian"), ("E:7", A_MINOR, "E Phrygian dominant"), ("C:maj", D_MAJOR, "C Lydian"),
    ("Bb:maj", Key(5, "major"), "Bb Lydian"),
])
def test_scale_hint(label, key, hint):
    assert theory.scale_hint(label, key) == hint


def test_borrowed_from_parallel_mode():
    assert theory.borrowed_from("C:maj", D_MAJOR) == "D minor"
    assert theory.borrowed_from("Bb:maj", D_MAJOR) == "D minor"
    assert theory.borrowed_from("A:maj", D_MAJOR) is None


@pytest.mark.parametrize("prev, nxt, expected", [
    ("A:7", "D:maj", "authentic"), ("G:maj", "D:maj", "plagal"), ("A:7", "B:min", "deceptive"),
    ("E:min", "A:maj", "half"), ("D:maj", "G:maj", None),
])
def test_cadence(prev, nxt, expected):
    assert theory.cadence(prev, nxt, D_MAJOR) == expected


def test_transition_reason():
    assert theory.transition_reason("A:sus4(b7)", "B:min7", D_MAJOR) == "deceptive cadence V→vi"
    assert theory.transition_reason("B:min", "G:maj", D_MAJOR) == "vi→IV, the Axis loop"
    assert theory.transition_reason("D:maj", "E:7", D_MAJOR) == "secondary dominant V/V leading to V"
    assert theory.transition_reason("D:maj", "Bb:maj", D_MAJOR) == "bVI borrowed from D minor"
    assert theory.transition_reason("D:maj", "D:maj", D_MAJOR) is None


@pytest.mark.parametrize("progression, expected", [
    ([("D:maj", 2), ("A:maj", 2), ("B:min", 2), ("G:maj", 2)] * 4, Key(2, "major")),
    ([("A:min", 2), ("F:maj", 2), ("C:maj", 2), ("G:maj", 2)] * 4 + [("A:min", 4)], Key(9, "minor")),
    ([("E:maj", 4), ("A:maj", 2), ("B:7", 2)] * 3 + [("E:maj", 4)], Key(4, "major")),
    ([("Eb:maj", 2), ("Ab:maj", 2), ("Bb:7", 2), ("Eb:maj", 2)], Key(3, "major")),
])
def test_estimate_key_from_chords(progression, expected):
    key, confidence = theory.estimate_key_from_chords(progression)
    assert key == expected
    assert 0.0 < confidence <= 1.0


def test_key_scores_from_chroma():
    chroma = np.zeros(12)
    for pc in (7, 11, 2, 0, 4, 9, 6):  # G major scale, tonic triad weighted
        chroma[pc] = 1.0
    chroma[[7, 11, 2]] += 1.0
    assert theory.key_scores(chroma)[0][0] == Key(7, "major")


def test_find_patterns_merges_rotations_and_names_them():
    loop = ["I", "V", "vi", "IV"]
    numerals = ["I"] * 2 + loop * 6 + ["IV", "I"] * 4 + loop * 2
    patterns = theory.find_patterns(numerals)
    assert patterns[0]["roman"] == loop
    assert patterns[0]["name"].startswith("I–V–vi–IV")
    assert patterns[0]["count"] >= 7
    assert any(p["roman"][:2] in (["IV", "I"], ["I", "IV"]) for p in patterns[1:])


def test_find_patterns_ignores_rare_loops():
    assert theory.find_patterns(["I", "IV", "V", "vi"]) == []


def test_named_loop_is_shown_in_its_canonical_rotation():
    numerals = ["vi", "IV", "I", "V"] * 5  # starts on vi, still the Axis loop
    top = theory.find_patterns(numerals)[0]
    assert top["roman"] == ["I", "V", "vi", "IV"] and top["name"].startswith("I–V–vi–IV")
