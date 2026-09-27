import numpy as np
import pytest

from chordify_ai.eval import chords as ev

mir_eval = pytest.importorskip("mir_eval")

REF = [(0.0, 1.0, "N"), (1.0, 3.0, "C:maj"), (3.0, 5.0, "G:7"), (5.0, 7.0, "A:min7"), (7.0, 9.0, "F:maj/3")]


def test_perfect_estimate_scores_one():
    scores = ev.evaluate_track(REF, REF)
    for level in ev.LEVELS + ev.SEGMENTATION:
        assert scores[level] == pytest.approx(1.0)
    assert scores["duration"] == 9.0


def test_majmin_ignores_sevenths_but_sevenths_does_not():
    est = [(0.0, 1.0, "N"), (1.0, 3.0, "C:maj"), (3.0, 5.0, "G:maj"), (5.0, 7.0, "A:min"), (7.0, 9.0, "F:maj")]
    scores = ev.evaluate_track(REF, est)
    assert scores["majmin"] == pytest.approx(1.0)
    assert scores["sevenths"] < 1.0
    assert scores["majmin_inv"] < 1.0  # F/A vs F


def test_shifted_boundary_costs_exactly_the_shifted_time():
    est = [(0.0, 1.0, "N"), (1.0, 3.5, "C:maj"), (3.5, 5.0, "G:7"), (5.0, 7.0, "A:min7"), (7.0, 9.0, "F:maj/3")]
    scores = ev.evaluate_track(REF, est)
    assert scores["mirex"] == pytest.approx(1 - 0.5 / 9.0)
    assert scores["seg"] < 1.0


def test_estimate_is_padded_to_reference_span():
    est = [(1.0, 5.0, "C:maj")]
    scores = ev.evaluate_track(REF, est)
    assert 0 <= scores["root"] < 1


def test_each_level_is_weighted_by_the_time_it_can_compare():
    # track 2: 5 s of sus4 (majmin cannot compare it) and 5 s of G read as Am
    rows = [ev.evaluate_track([(0.0, 10.0, "C:maj")], [(0.0, 10.0, "C:maj")]),
            ev.evaluate_track([(0.0, 5.0, "C:sus4"), (5.0, 10.0, "G:maj")], [(0.0, 10.0, "A:min")]),
            ev.evaluate_track([(0.0, 10.0, "X")], [(0.0, 10.0, "C:maj")])]
    assert rows[1]["majmin"] == 0.0 and rows[1]["comparable"]["majmin"] == pytest.approx(5.0)
    assert np.isnan(rows[2]["majmin"])  # nothing to compare: left out, not scored 0
    agg = ev.aggregate(rows)
    assert agg["majmin"] == pytest.approx(10 / 15)  # correct seconds / comparable seconds
    assert agg["seg"] == pytest.approx(np.mean([r["seg"] for r in rows]))  # segmentation: by duration
    expected = mir_eval.chord.evaluate(np.array([[0.0, 5.0], [5.0, 10.0]]), ["C:sus4", "G:maj"],
                                       np.array([[0.0, 10.0]]), ["A:min"])
    for key in ev.LEVELS + ev.SEGMENTATION:  # per track, the same numbers as mir_eval's own evaluate
        assert rows[1][key] == pytest.approx(expected[key])


def test_aggregate_is_duration_weighted():
    rows = [{"duration": 10.0, **{k: 1.0 for k in ev.LEVELS + ev.SEGMENTATION}},
            {"duration": 30.0, **{k: 0.0 for k in ev.LEVELS + ev.SEGMENTATION}}]
    agg = ev.aggregate(rows)
    assert agg["majmin"] == pytest.approx(0.25) and agg["tracks"] == 2


def test_lab_roundtrip_and_cli(tmp_path, capsys):
    (tmp_path / "ref").mkdir()
    (tmp_path / "est").mkdir()
    ev.write_lab(REF, tmp_path / "ref" / "a.lab")
    ev.write_lab(REF, tmp_path / "est" / "a.lab")
    assert ev.read_lab(tmp_path / "ref" / "a.lab") == REF
    assert ev.main([str(tmp_path / "ref"), str(tmp_path / "est")]) == 0
    lines = capsys.readouterr().out.splitlines()
    table = dict(line.split() for line in lines if not line.startswith(" "))
    assert table["majmin"] == "100.00" and table["seg"] == "100.00" and table["large"] == "100.00"
    assert any(line.split()[:2] == ["7", "100.0"] for line in lines if line.startswith(" "))


def test_overlapping_or_unsorted_intervals_are_sanitised():
    messy = [(1.0, 3.0000001, "C:maj"), (0.0, 1.0, "N"), (3.0, 9.0, "G:7")]
    assert ev.evaluate_track(REF, messy)["root"] > 0


def test_estimate_running_past_the_reference_is_clipped():
    est = REF[:-1] + [(7.0, 9.0, "F:maj/3"), (9.0, 12.0, "N")]  # starts exactly where REF ends
    assert ev.evaluate_track(REF, est)["mirex"] == pytest.approx(1.0)
    assert ev.evaluate_track(REF, [(20.0, 30.0, "C:maj")])["majmin"] == pytest.approx(1 / 9)  # only N at 0-1 s


def test_breakdown_by_chord_type_counts_exact_matches_and_confusions():
    ref = [(0.0, 2.0, "C:maj7"), (2.0, 4.0, "G:7"), (4.0, 6.0, "A:min"), (6.0, 8.0, "B:hdim7")]
    est = [(0.0, 2.0, "C:maj"), (2.0, 4.0, "G:7"), (4.0, 6.0, "A:min7"), (6.0, 8.0, "C:hdim7")]
    scores = ev.aggregate([ev.evaluate_track(ref, est)])
    by = scores["by_quality"]
    assert by["7"]["recall"] == pytest.approx(1.0)
    assert by["maj7"]["recall"] == 0 and by["maj7"]["mistaken_for"] == {"maj": 1.0}
    assert by["min"]["mistaken_for"] == {"min7": 1.0}
    assert by["hdim7"]["mistaken_for"] == {"wrong root": 1.0}
    assert by["maj7"]["share"] == pytest.approx(0.25, abs=0.01)
    assert scores["large"] == pytest.approx(0.25, abs=0.01)
    assert scores["tetrads"] < scores["majmin"]
