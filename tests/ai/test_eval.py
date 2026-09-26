import pytest

from chordify_ai.eval import chords as ev

pytest.importorskip("mir_eval")

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
    table = dict(line.split() for line in capsys.readouterr().out.splitlines())
    assert table["majmin"] == "100.00" and table["seg"] == "100.00"


def test_overlapping_or_unsorted_intervals_are_sanitised():
    messy = [(1.0, 3.0000001, "C:maj"), (0.0, 1.0, "N"), (3.0, 9.0, "G:7")]
    assert ev.evaluate_track(REF, messy)["root"] > 0
