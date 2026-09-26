import json

import pytest

from chordify_ai.eval import evaluate_model

from test_billboard import make_choco  # same directory (pytest prepend import mode)

pytest.importorskip("mir_eval")


def test_templates_score_a_synthetic_render_through_the_production_decoder(tmp_path, capsys):
    chords = [(0.0, 1.0, "N")] + [(1.0 + 2 * i, 3.0 + 2 * i, lab)
                                  for i, lab in enumerate(["C:maj", "G:maj", "A:min", "F:maj"] * 3)]
    root = make_choco(tmp_path, [("0001", "Song A", "Band", chords), ("0002", "Song B", "Other", chords)])
    split_file = tmp_path / "split.json"
    split_file.write_text(json.dumps({"train": ["0002"], "validation": ["0001"], "test": []}))

    assert evaluate_model.main(["--choco", str(root), "--split-file", str(split_file), "--synthetic"]) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["model"] == "chroma-templates@0.1.0" and report["source"] == "synthetic"
    assert report["tracks"] == 1
    assert report["majmin"] > 0.7
