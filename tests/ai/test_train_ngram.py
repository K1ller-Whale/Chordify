from chordify_ai.data.billboard import BillboardTrack
from chordify_ai.lm.train_ngram import evaluate, track_tokens
from chordify_core.lm import NgramProgressionModel


def test_track_tokens_follow_local_tonic_and_skip_no_chord():
    track = BillboardTrack("0001", "t", "a", 20.0, chords=[
        (0.0, 1.0, "N"), (1.0, 3.0, "C:maj"), (3.0, 5.0, "C:maj"), (5.0, 7.0, "G:7"),
        (7.0, 9.0, "D:maj"), (9.0, 11.0, "A:maj")], tonics=[(0.0, "C"), (6.9, "D")])
    # C (I in C), G7 (V in C), then the key changes: D (I in D), A (V in D)
    assert track_tokens(track) == ["0:maj", "7:maj", "0:maj", "7:maj"]


def test_evaluate_reports_overall_corpus_and_cold_start():
    seqs = [["0:maj", "5:maj", "7:maj"] * 4]
    model = NgramProgressionModel.fit(seqs, order=3, min_count=1)
    metrics = evaluate(model, seqs)
    assert metrics["top1"] == 1.0 and metrics["transitions"] == 11
    assert metrics["cold_transitions"] == 8
