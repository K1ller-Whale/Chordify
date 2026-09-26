from pathlib import Path

import numpy as np
import pytest

from chordify_core import lm
from chordify_core.lm import NgramProgressionModel, predict_next, token, token_label
from chordify_core.theory import Key
from chordify_core.vocab import MAJMIN

D = Key(2, "major")
C = Key(0, "major")
BUNDLE = Path(__file__).resolve().parents[2] / "models" / "progression-ngram" / "1.0.0" / "model.json.gz"

I, IV, V, vi, ii = "0:maj", "5:maj", "7:maj", "9:min", "2:min"


def test_tokens_are_key_relative():
    assert token("A:sus4(b7)", D) == V
    assert token("B:min7", D) == vi
    assert token("E:7", Key(4, "minor")) == I
    assert token("N", D) is None
    assert token_label(vi, D) == "B:min"
    assert token_label("10:maj", D) == "C:maj"
    assert token_label("3:maj", Key(5, "major")) == "Ab:maj"  # flat key spelling


@pytest.fixture
def toy_model():
    corpus = [[I, IV, V, I]] * 5 + [[ii, V, I]] * 4 + [[I, V, I, IV]] * 5 + [[I, V, vi, IV]] * 2
    return NgramProgressionModel.fit(corpus, order=3, min_count=2)


def test_corpus_distribution_backs_off_and_never_repeats(toy_model):
    dist = toy_model.corpus_distribution([IV, V])
    assert max(dist, key=dist.get) == I
    assert dist[V] == 0.0
    assert sum(dist.values()) == pytest.approx(1.0)
    unseen = toy_model.corpus_distribution(["3:min", "8:maj"])  # backs off to unigram
    assert max(unseen, key=unseen.get) in (I, V)


def test_song_cache_overrides_the_corpus(toy_model):
    corpus_guess = toy_model.corpus_distribution([IV, I, V])
    assert max(corpus_guess, key=corpus_guess.get) == I
    history = [I, V, vi, IV] * 3 + [I, V]
    top = toy_model.top(history, 1)[0]
    # the longest context seen before (6 chords) occurred twice, both followed by vi
    assert top.token == vi and top.cache_count == 2 and top.p > 0.75


def test_predict_next_uses_song_labels_reasons_and_lengths(toy_model):
    history = ["D:maj", "A:sus4(b7)", "B:min7", "G:maj9"] * 3 + ["D:maj", "A:sus4(b7)"]
    beats = [4.0] * len(history)
    predictions, prior = predict_next(toy_model, history, D, k=3, history_beats=beats)
    best = predictions[0]
    assert (best.label, best.display, best.roman) == ("B:min7", "Bm7", "vi")
    assert "earlier in the song" in best.reason and "deceptive cadence" in best.reason
    assert best.expected_beats == 4.0
    assert [p["roman"] for p in prior][0] == "I"  # the corpus alone expects V→I
    assert best.to_dict()["p"] == best.p


def test_change_matrix_for_decoder(toy_model):
    m = toy_model.change_matrix(MAJMIN, C)
    assert m.shape == (MAJMIN.size, MAJMIN.size)
    assert np.allclose(m.sum(axis=1), 1.0) and np.allclose(np.diag(m), 0.0)
    g, c, db = MAJMIN.encode("G:maj"), MAJMIN.encode("C:maj"), MAJMIN.encode("C#:maj")
    assert m[g, c] > 10 * m[g, db]


def test_save_load_roundtrip(tmp_path, toy_model):
    toy_model.save(tmp_path / "m.json.gz")
    loaded = NgramProgressionModel.load(tmp_path / "m.json.gz")
    assert loaded.corpus_distribution([IV, V]) == toy_model.corpus_distribution([IV, V])


@pytest.mark.skipif(not BUNDLE.exists(), reason="trained bundle not present")
def test_committed_billboard_model_predicts_common_moves():
    model = NgramProgressionModel.load(BUNDLE)
    after_v = model.corpus_distribution([I, IV, V])
    assert max(after_v, key=after_v.get) == I
    predictions, _ = predict_next(model, ["C:maj", "G:maj", "A:min", "F:maj"] * 2 + ["C:maj", "G:maj"], C)
    assert predictions[0].label == "A:min"
    assert lm.dedupe([1, 1, 2, 2, 1]) == [1, 2, 1]
