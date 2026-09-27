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
                           chord([(s, e, lab.replace(":maj", ":maj7")) for s, e, lab in CHORDS])]}
    (root / "annotation" / f"{name}.jams").write_text(json.dumps(jam))
    return root


def test_lead_sheet_chords_are_the_reference(tmp_path):
    root = make_guitarset(tmp_path)
    ref = guitarset.load_annotation(root / "annotation" / "00_Rock1-120-C_comp.jams")
    assert [lab for _, _, lab in ref["chords"]] == ["C:maj", "G:maj", "A:min", "F:maj"]
    assert ref["beats"][:3] == [0.0, 0.5, 1.0]
    assert guitarset.style_of("00_Rock1-120-C_comp") == "rock"
    assert guitarset.style_of("03_BN2-131-B_comp") == "bossa nova"


def test_templates_score_the_recording_through_the_server_path(tmp_path, capsys):
    root = make_guitarset(tmp_path)
    assert guitarset.main(["--root", str(root), "--model", "templates"]) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["tracks"] == 1 and report["dataset"] == "guitarset-comp-mic"
    assert report["majmin"] > 0.8
    assert set(report["by_style"]) == {"rock"}
