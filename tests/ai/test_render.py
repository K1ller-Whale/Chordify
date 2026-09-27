from collections import Counter

import numpy as np
import pytest

pretty_midi = pytest.importorskip("pretty_midi")

from chordify_ai.data import pop909, render  # noqa: E402
from chordify_ai.train.train_chordnet import example_weights, parse_mix  # noqa: E402
from chordify_core import vocab  # noqa: E402


def test_generated_songs_are_reproducible_and_contiguous():
    a, b = render.generate_song(7), render.generate_song(7)
    assert a.chords == b.chords and a.song_id == b.song_id
    assert render.generate_song(8).chords != a.chords
    assert all(abs(e0 - s1) < 1e-9 for (_, e0, _), (s1, _, _) in zip(a.chords, a.chords[1:]))
    assert a.duration == pytest.approx(a.chords[-1][1]) and 30 < a.duration < 75
    assert all(vocab.LARGE.encode(label) is not None for _, _, label in a.chords)


def test_generated_chord_types_are_balanced():
    seconds = Counter()
    for i in range(150):
        for t0, t1, label in render.generate_song(i).chords:
            index = vocab.LARGE.encode(label)
            seconds["N" if index == vocab.LARGE.no_chord else vocab.LARGE.quality_of(index)] += t1 - t0
    total = sum(seconds.values())
    assert set(vocab.LARGE_QUALITIES) <= set(seconds)
    for quality in vocab.LARGE_QUALITIES:  # every type gets several % of the time (dim7 is 0.02 % of Billboard)
        assert seconds[quality] / total > 0.03, quality


@pytest.mark.parametrize("label", ["C:maj", "A:min7/b7", "G:7/5", "B:hdim7", "F#:dim7", "D:minmaj7", "E:aug/3"])
def test_voicings_play_exactly_the_chord_tones(label):
    chord = vocab.parse(label)
    rng = np.random.default_rng(1)
    for _ in range(20):
        notes = render.voicing(label, rng, spread=bool(rng.integers(2)), own_bass=True)
        assert {n % 12 for n in notes} <= chord.pitch_classes
        assert chord.pitch_classes - {n % 12 for n in notes} <= {(chord.root + 7) % 12}  # only a fifth may go
        assert notes[0] % 12 == chord.bass  # without a bass instrument the labelled bass is lowest
        assert render.bass_note(label) % 12 == chord.bass


def test_generated_midi_matches_the_labels():
    song = render.generate_song(3)
    for inst in song.midi.instruments:
        if inst.is_drum or inst.name == "melody":
            continue
        for note in inst.notes:
            mid = note.start + 0.03
            label = next(lab for t0, t1, lab in song.chords if t0 - 0.05 <= mid < t1)
            assert note.pitch % 12 in vocab.parse(label).pitch_classes, (inst.name, label, note.pitch)


def _fake_pop909(tmp_path):
    folder = tmp_path / "POP909" / "012"
    folder.mkdir(parents=True)
    pm = pretty_midi.PrettyMIDI(initial_tempo=120)
    for name in ("MELODY", "BRIDGE", "PIANO"):
        inst = pretty_midi.Instrument(program=0, name=name)
        inst.notes.append(pretty_midi.Note(80, 60, 0.0, 4.0))
        pm.instruments.append(inst)
    pm.write(str(folder / "012.mid"))
    (folder / "chord_midi.txt").write_text("0.0\t2.0\tC:maj\n2.0\t4.0\tA:min/b3\n")
    (folder / "beat_midi.txt").write_text("".join(f"{0.5 * i}\t1.0\t0.0\n" for i in range(8)))
    (folder / "key_audio.txt").write_text("0.0\t4.0\tC:maj\n")
    return tmp_path


def test_pop909_loading_split_and_arrangement(tmp_path):
    root = _fake_pop909(tmp_path)
    song = pop909.load_pop909(root)[0]
    assert song.song_id == "012" and song.chords[1] == (2.0, 4.0, "A:min/b3") and song.keys == [(0.0, "C:maj")]
    assert len(song.beats) == 8 and song.duration == 4.0
    arranged = render.arrange_pop909(song)
    assert arranged.chords == song.chords and arranged.keys == song.keys
    tracks = {inst.name: inst for inst in arranged.midi.instruments}
    assert tracks["MELODY"].program in render.MELODY_PROGRAMS and tracks["PIANO"].program in render.POP909_PIANO_PROGRAMS
    if "bass" in tracks:
        assert {n.pitch % 12 for n in tracks["bass"].notes if n.start >= 2.0} <= {0}  # A:min/b3 -> C in the bass
    split = pop909.make_split([f"{i:03d}" for i in range(1, 21)])
    assert split["test"] == ["010", "020"] and split["validation"] == ["001", "011"] and len(split["train"]) == 16
    assert set(pop909.read_split()["test"]) == {f"{i:03d}" for i in range(10, 910, 10)}


def test_mix_and_sampling_weights():
    mix = parse_mix("billboard=2,generated=1", ["billboard", "generated"])
    assert mix == pytest.approx({"billboard": 2 / 3, "generated": 1 / 3})
    with pytest.raises(ValueError):
        parse_mix("pop909=1", ["billboard"])

    class Track:
        def __init__(self, n):
            self.example = type("Example", (), {"features": np.zeros((n, 25))})()

    weights = example_weights({"billboard": [Track(100), Track(300)], "generated": [Track(50)]}, mix)
    assert weights == pytest.approx([2 / 3 * 0.25, 2 / 3 * 0.75, 1 / 3])


@pytest.mark.skipif(render.fluidsynth_binary() is None or render.find_soundfont() is None,
                    reason="needs fluidsynth and a General MIDI soundfont")
def test_render_produces_audio_of_the_labelled_length():
    song = render.generate_song(0)
    y = render.render(song, render.find_soundfont(), sr=22050)
    assert len(y) == round(song.duration * 22050) and 0.5 < np.abs(y).max() <= 0.9 + 1e-6
