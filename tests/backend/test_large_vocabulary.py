"""The analysis pipeline with a large-vocabulary model: chord types, inversions, Roman
numerals and simplification to a coarser vocabulary. A stub stands in for the network so
the expected chords are known exactly."""
import dataclasses

import numpy as np
import pytest

from chordify_backend.app.config import Settings
from chordify_backend.app.pipeline.analysis import analyse
from chordify_backend.app.pipeline.models import Models
from chordify_core import features, synth
from chordify_core.acoustic import AcousticOutput
from chordify_core.vocab import LARGE

# (label, bass pitch class, seconds)
PLAN = [("C:maj7", 4, 4.0), ("G:7", 7, 4.0), ("C:maj7", 0, 4.0), ("C:maj6", 0, 4.0)] * 3  # loops need 3


class StubModel:
    id = "stub-large@0"
    vocabulary = LARGE
    feature_spec = features.CQT_BOTHCHROMA
    decoder_params = {"alpha": 0.0, "self_prob": 0.9, "subdivide": 1, "inversion_threshold": 0.5}
    prior = None

    def predict(self, raw: np.ndarray) -> AcousticOutput:
        times = self.feature_spec.frame_times(len(raw))
        bounds = np.cumsum([0.0] + [s for _, _, s in PLAN])
        chord = np.full((len(raw), LARGE.size), 1e-4)
        bass = np.full((len(raw), 13), 0.01)
        for (label, pc, _), start, end in zip(PLAN, bounds[:-1], bounds[1:]):
            sel = (times >= start) & (times < end)
            chord[sel, LARGE.encode(label)] = 1.0
            bass[sel, pc] = 0.9
        chord /= chord.sum(axis=1, keepdims=True)
        return AcousticOutput(chord=chord, extra={"bass": bass})


@pytest.fixture(scope="module")
def stub_models():
    models = Models.load(Settings(chord_model="templates", warmup=False))
    return dataclasses.replace(models, acoustic=StubModel())


@pytest.fixture(scope="module")
def audio():
    y, _ = synth.render_progression([(label.replace("maj6", "maj"), s) for label, _, s in PLAN], sr=44100)
    return y.astype(np.float32)


def test_every_chord_type_inversions_and_typed_numerals(stub_models, audio):
    result = analyse(audio, 44100, models=stub_models, analysis_id="large")
    chords = [c for c in result["chords"] if c["label"] != "N"]
    assert [c["label"] for c in chords[:4]] == ["C:maj7/3", "G:7", "C:maj7", "C:maj6"]
    assert [c["display"] for c in chords[:4]] == ["Cmaj7/E", "G7", "Cmaj7", "C6"]
    assert [c["quality"] for c in chords[:4]] == ["maj7", "7", "maj7", "maj6"]
    assert [c["roman"] for c in chords[:4]] == ["Imaj7", "V7", "Imaj7", "I6"]
    assert [c["roman_triad"] for c in chords[:4]] == ["I", "V", "I", "I"]
    assert chords[0]["bass"] == "E"
    assert set(result["summary"]["patterns"][0]["roman"]) == {"I", "V"}  # loops match on bare numerals


def test_a_coarser_vocabulary_simplifies_and_merges(stub_models, audio):
    result = analyse(audio, 44100, models=stub_models, analysis_id="majmin", vocabulary="majmin")
    chords = [c for c in result["chords"] if c["label"] != "N"]
    # Cmaj7/E -> C/E; G7 -> G; Cmaj7 + C6 -> one C
    assert [c["label"] for c in chords[:3]] == ["C:maj/3", "G:maj", "C:maj"]
    assert chords[2]["end"] - chords[2]["start"] == pytest.approx(8.0, abs=0.6)
    assert {c["quality"] for c in chords} == {"maj"}


def test_a_finer_vocabulary_than_the_model_knows_is_refused(audio):
    models = Models.load(Settings(chord_model="templates", warmup=False))
    with pytest.raises(ValueError, match="majmin"):
        analyse(audio, 44100, models=models, analysis_id="x", vocabulary="large")
