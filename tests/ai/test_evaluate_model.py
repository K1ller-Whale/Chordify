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
    assert report["model"] == "chroma-templates@0.2.0" and report["source"] == "synthetic"
    assert report["tracks"] == 1
    assert report["majmin"] > 0.7


def write_bothchroma_csv(path, chords, frames):
    """A Billboard-style bothchroma.csv (block-start stamps, A-based bins) with ideal triads."""
    from chordify_core import features, vocab

    step = 1 / features.NNLS_BOTHCHROMA.frame_rate
    rows = []
    for i in range(frames):
        centre = i * step + features.NNLS_BOTHCHROMA.offset
        label = next((lab for s, e, lab in chords if s <= centre < e), "N")
        chord = vocab.parse(label)
        bass, treble = [0.0] * 12, [0.0] * 12
        if chord.is_chord:
            for pc in chord.pitch_classes:
                treble[(pc + 3) % 12] = 1.0  # C-ordered pitch class -> A-based bin
            bass[(chord.root + 3) % 12] = 1.0
        rows.append(",".join(["x" if i == 0 else "", f"{i * step:.9f}"] + [str(v) for v in bass + treble]))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(rows) + "\n")


def test_decoder_sweep_reports_settings_on_the_validation_split(tmp_path, capsys):
    from chordify_ai.eval import tune_decoder

    chords = [(0.0, 1.0, "N")] + [(1.0 + 2 * i, 3.0 + 2 * i, lab)
                                  for i, lab in enumerate(["C:maj", "G:maj", "A:min", "F:maj"] * 3)]
    root = make_choco(tmp_path, [("0001", "Song A", "Band", chords)])
    write_bothchroma_csv(tmp_path / "features" / "McGill-Billboard" / "0001" / "bothchroma.csv", chords, 560)
    split_file = tmp_path / "split.json"
    split_file.write_text(json.dumps({"train": [], "validation": ["0001"], "test": []}))

    assert tune_decoder.main(["--choco", str(root), "--chroma", str(tmp_path / "features"),
                              "--split-file", str(split_file), "--model", "templates"]) == 0
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert report["tracks"] == 1
    assert set(report["best"]) == set(tune_decoder.GRID)
    assert report["validation"]["majmin"] > 0.9
