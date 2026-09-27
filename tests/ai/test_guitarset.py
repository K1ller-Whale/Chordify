import json

import pytest
import soundfile as sf

from chordify_ai.eval import guitarset
from chordify_core import synth

pytest.importorskip("mir_eval")

CHORDS = [(0.0, 2.0, "C:maj"), (2.0, 4.0, "G:maj"), (4.0, 6.0, "A:min"), (6.0, 8.0, "F:maj")]


def make_guitarset(root, name="00_Rock1-120-C_comp"):
    (root / "annotation").mkdir(parents=True)
    (root / "audio").mkdir()
    y, _ = synth.render_progression([(lab, e - s) for s, e, lab in CHORDS], sr=44100)
    sf.write(root / "audio" / f"{name}_mic.wav", y, 44100)
    chord = lambda data: {"namespace": "chord", "data": [
        {"time": s, "duration": e - s, "value": lab, "confidence": 1.0} for s, e, lab in data]}
    jam = {"file_metadata": {"title": name, "duration": len(y) / 44100},
           "annotations": [{"namespace": "beat_position",
                            "data": [{"time": 0.5 * i, "duration": 0.0, "value": {}} for i in range(16)]},
                           chord(CHORDS),
                           {**chord([(s, e, lab.replace(":maj", ":maj7")) for s, e, lab in CHORDS]),
                            "annotation_metadata": {"data_source": "Semi-automatic chord transcription"}}]}
    (root / "annotation" / f"{name}.jams").write_text(json.dumps(jam))
    return root


def test_lead_sheet_chords_are_the_reference(tmp_path):
    root = make_guitarset(tmp_path)
    ref = guitarset.load_annotation(root / "annotation" / "00_Rock1-120-C_comp.jams")
    assert [lab for _, _, lab in ref["chords"]] == ["C:maj", "G:maj", "A:min", "F:maj"]
    assert ref["beats"][:3] == [0.0, 0.5, 1.0]
    performed = guitarset.load_annotation(root / "annotation" / "00_Rock1-120-C_comp.jams", "performed")
    assert [lab for _, _, lab in performed["chords"]] == ["C:maj7", "G:maj7", "A:min", "F:maj7"]
    assert guitarset.style_of("00_Rock1-120-C_comp") == "rock"
    assert guitarset.style_of("03_BN2-131-B_comp") == "bossa nova"


def test_templates_score_the_recording_through_the_server_path(tmp_path, capsys):
    root = make_guitarset(tmp_path)
    assert guitarset.main(["--root", str(root), "--model", "templates"]) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["tracks"] == 1 and report["dataset"] == "guitarset-comp-mic"
    assert report["majmin"] > 0.8
    assert set(report["by_style"]) == {"rock"}


def test_split_by_player_keeps_every_guitarist_in_one_split(tmp_path):
    from chordify_ai.data import guitarset as gs_data

    split = gs_data.read_split()
    players = [p for part in ("train", "validation", "test") for p in split[part]]
    assert sorted(players) == ["00", "01", "02", "03", "04", "05"] and len(set(players)) == 6
    for take_id in ("00_Rock1-120-C_comp", "04_Jazz2-110-Bb_comp", "05_SS1-68-E_comp"):
        make_guitarset(tmp_path / take_id[:2], take_id)
    takes = [t for p in ("00", "04", "05") for t in gs_data.load_guitarset(tmp_path / p)]
    assert [t.player for t in gs_data.takes_in(takes, "train")] == ["00"]
    assert [t.style for t in gs_data.takes_in(takes, "validation")] == ["jazz"]
    assert gs_data.takes_in(takes, "test")[0].chords[0][2] == "C:maj7"  # performed labels
