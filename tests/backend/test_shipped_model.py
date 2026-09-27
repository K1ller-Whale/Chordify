"""The shipped ChordNet bundle, as the server loads it with CHORDIFY_CHORD_MODEL=auto."""
from pathlib import Path

import numpy as np
import pytest

from chordify_backend.app.config import Settings
from chordify_backend.app.pipeline.analysis import analyse
from chordify_backend.app.pipeline.models import Models
from chordify_core import features, synth

BUNDLE = Path(Settings().default_chord_bundle)
pytestmark = [
    pytest.mark.skipif(not (BUNDLE / "bundle.json").exists(), reason="no shipped ChordNet bundle"),
    pytest.mark.skipif(not features.nnls_available(), reason="the bundle needs the NNLS Chroma Vamp plugin"),
]


def test_auto_serves_the_shipped_chordnet_and_finds_the_loop():
    models = Models.load(Settings(chord_model="auto", warmup=False))
    assert models.acoustic.id.startswith("chordnet-chroma@")
    loop = ["C:maj", "G:maj", "A:min", "F:maj"] * 4
    y, _ = synth.render_progression([(label, 2.0) for label in loop], sr=44100, seed=3)
    result = analyse(y.astype(np.float32), 44100, models=models, analysis_id="shipped")
    chords = [c for c in result["chords"] if c["label"] != "N"]
    assert [c["display"] for c in chords] == ["C", "G", "Am", "F"] * 4
    for chord, expected_start in zip(chords, range(0, 32, 2)):
        assert chord["start"] == pytest.approx(expected_start, abs=0.26)  # half-beat decoding units
    assert result["key"]["global"]["tonic"] == "C" and result["key"]["global"]["mode"] == "major"
    assert result["models"]["chord"] == models.acoustic.id


def test_the_shipped_model_names_seventh_chords():
    models = Models.load(Settings(chord_model="auto", warmup=False))
    if models.acoustic.vocabulary.name == "majmin":
        pytest.skip("the shipped bundle only knows major and minor")
    loop = ["C:maj7", "A:min7", "D:min7", "G:7"] * 4
    y, _ = synth.render_progression([(label, 2.0) for label in loop], sr=44100, seed=3)
    result = analyse(y.astype(np.float32), 44100, models=models, analysis_id="shipped-sevenths")
    chords = [c for c in result["chords"] if c["label"] != "N"]
    assert [c["display"] for c in chords] == ["Cmaj7", "Am7", "Dm7", "G7"] * 4
    assert [c["roman"] for c in chords[:4]] == ["Imaj7", "vi7", "ii7", "V7"]
    assert result["key"]["global"]["tonic"] == "C" and result["key"]["global"]["mode"] == "major"
