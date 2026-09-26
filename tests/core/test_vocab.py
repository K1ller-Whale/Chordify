import pytest

from chordify_core import vocab
from chordify_core.vocab import LARGE, MAJMIN, SEVENTHS

# Every chord quality that occurs in the McGill Billboard annotations, plus edge cases.
BILLBOARD_QUALITIES = [
    "maj", "min", "7", "min7", "maj7", "5", "1", "maj(9)", "maj6", "sus4", "min9", "7(#9)",
    "sus4(b7,9)", "sus4(b7)", "maj9", "11", "9", "13", "min11", "5(b7)", "maj6(9)", "min6",
    "sus2", "dim", "hdim7", "min(9)", "7(b9)", "sus4(9)", "aug(b7)", "maj13", "min7(11)",
    "sus4(b7,9,13)", "min(b13)", "7(#11)", "maj(11)", "min(11)", "maj7(#11)", "aug", "min13",
    "(11)", "dim7", "minmaj7", "sus2(b7)", "(b3,b7)", "7(b13)", "(b7)", "maj(#9)",
    "7(b9,b13)", "(b3,b7,11,9)", "9(13,#11)", "(b5,b7,3)", "5(b13)", "maj9(13,#11)",
    "maj6(b7)", "min7(b13)", "(3)", "maj(*3)", "min7(*5)",
]


@pytest.mark.parametrize("quality", BILLBOARD_QUALITIES)
@pytest.mark.parametrize("root", ["C", "F#", "Bb", "Cb", "E#"])
@pytest.mark.parametrize("bass", ["", "/3", "/b7", "/5"])
def test_pitch_classes_match_mir_eval(root, quality, bass):
    mir_eval_chord = pytest.importorskip("mir_eval.chord")
    label = f"{root}:{quality}{bass}"
    ref_root, ref_bitmap, ref_bass = mir_eval_chord.encode(label, reduce_extended_chords=True)
    chord = vocab.parse(label)
    assert chord.root == ref_root
    expected = {(ref_root + i) % 12 for i, on in enumerate(ref_bitmap) if on}
    assert chord.pitch_classes == expected, label
    assert chord.bass == (ref_root + ref_bass) % 12


def test_no_chord_and_unknown():
    assert vocab.parse("N").root is None
    assert vocab.parse("X").root is None
    assert MAJMIN.encode("N") == MAJMIN.no_chord == 24
    assert MAJMIN.encode("X") is None


def test_root_without_quality_is_major():
    assert vocab.parse("A").pitch_classes == vocab.parse("A:maj").pitch_classes
    assert vocab.parse("A/3").bass == vocab.note_to_pc("C#")


@pytest.mark.parametrize("bad", ["H:maj", "C:foo", "C:(1,3", "C:maj/12", ":min", ""])
def test_malformed_labels_raise(bad):
    with pytest.raises(ValueError):
        vocab.parse(bad)


def test_enharmonics_share_a_class():
    # The v1 pipeline used a hand-written string map for these.
    for a, b in [("Bb:maj", "A#:maj"), ("Cb:min", "B:min"), ("E#:maj", "F:maj"), ("Fb:maj", "E:maj"),
                 ("Gb:min", "F#:min"), ("Db:maj7", "C#:maj7")]:
        for v in (MAJMIN, SEVENTHS, LARGE):
            assert v.encode(a) == v.encode(b) is not None


@pytest.mark.parametrize("label, majmin, sevenths, large", [
    ("C:maj", "C:maj", "C:maj", "C:maj"),
    ("A:min7", "A:min", "A:min7", "A:min7"),
    ("G:7", "G:maj", "G:7", "G:7"),
    ("G:9", "G:maj", "G:7", "G:7"),
    ("E:7(#9)", "E:maj", "E:7", "E:7"),  # #9 is an extension, not a minor third
    ("C:maj(9)", "C:maj", "C:maj", "C:maj"),
    ("F:maj6", "F:maj", "F:maj", "F:maj6"),
    ("D:min9", "D:min", "D:min7", "D:min7"),
    ("B:hdim7", "X", "X", "B:hdim7"),
    ("B:dim7", "X", "X", "B:dim7"),
    ("A:sus4(b7)", "X", "X", "A:sus4"),
    ("D:5", "X", "X", "X"),
    ("C:aug", "X", "X", "C:aug"),
    ("C:minmaj7", "C:min", "X", "C:minmaj7"),
    ("C:maj/3", "C:maj", "C:maj", "C:maj"),
    ("N", "N", "N", "N"),
])
def test_tier_reductions(label, majmin, sevenths, large):
    assert vocab.reduce(label, MAJMIN) == majmin
    assert vocab.reduce(label, SEVENTHS) == sevenths
    assert vocab.reduce(label, LARGE) == large


def test_vocabulary_sizes_match_the_plan():
    assert (MAJMIN.size, SEVENTHS.size, LARGE.size) == (25, 61, 169)
    assert len(set(LARGE.labels)) == LARGE.size


@pytest.mark.parametrize("v", [MAJMIN, SEVENTHS, LARGE])
def test_transpose_index_matches_transposed_label(v):
    for index in range(v.size):
        label = v.decode(index)
        for k in range(12):
            assert v.transpose_index(index, k) == v.encode(vocab.transpose(label, k))


@pytest.mark.parametrize("label, k, expected", [
    ("C:maj", 2, "D:maj"),
    ("A:min7/b3", 3, "C:min7/b3"),
    ("Bb:sus4(b7)", 1, "B:sus4(b7)"),
    ("G/3", 5, "C/3"),
    ("N", 4, "N"),
    ("E", -4, "C"),
])
def test_transpose_label(label, k, expected):
    assert vocab.transpose(label, k) == expected


@pytest.mark.parametrize("label, expected", [
    ("A:sus4(b7)", "A7sus4"), ("B:min7", "Bm7"), ("G:maj9", "Gmaj9"), ("D:maj", "D"),
    ("C:maj/3", "C/E"), ("A:min/b3", "Am/C"), ("Bb:7", "Bb7"), ("Eb:maj/5", "Eb/Bb"),
    ("F#:hdim7", "F#m7b5"), ("C:maj(9)", "Cadd9"), ("E:7(#9)", "E7#9"), ("N", "N.C."),
    ("C:min(b13)", "Cm(b13)"),
])
def test_display_name(label, expected):
    assert vocab.display_name(label) == expected


def test_chord_tones_and_triads():
    assert vocab.chord_tones("C:maj7") == [1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1]
    assert vocab.triad(vocab.parse("C:sus4(b7)")) == "sus4"
    assert vocab.triad(vocab.parse("C:5")) == "5"
    assert vocab.triad(vocab.parse("C:1")) == "1"
    assert vocab.triad(vocab.parse("N")) is None
