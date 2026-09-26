import json

import numpy as np
import pytest

torch = pytest.importorskip("torch")

from chordify_ai.data.billboard import BillboardTrack  # noqa: E402
from chordify_ai.models.chordnet import ChordNet, ChordNetConfig, count_parameters  # noqa: E402
from chordify_ai.models.losses import chordnet_loss  # noqa: E402
from chordify_ai.models.targets import IGNORE, frame_targets, transpose_targets  # noqa: E402
from chordify_ai.train import sources  # noqa: E402
from chordify_ai.train.dataset import CropDataset, collate, to_tensors, transpose_input  # noqa: E402
from chordify_ai.train.trainer import TrainConfig, evaluate, train  # noqa: E402
from chordify_core import acoustic, features  # noqa: E402
from chordify_core.vocab import MAJMIN, SEVENTHS  # noqa: E402

FPS = 44100 / 2048


@pytest.mark.parametrize("config, n_features", [
    (ChordNetConfig(), 25),
    (ChordNetConfig.small(), 25),
    (ChordNetConfig(input="log_cqt", n_features=288, n_layers=1), 288),
])
def test_forward_shapes(config, n_features):
    model = ChordNet(config).eval()
    out = model(torch.randn(2, 100, n_features))
    assert out["chord"].shape == (2, 100, 25)
    assert out["tones"].shape == (2, 100, 12) and out["key_tonic"].shape == (2, 100, 13)
    assert out["boundary"].shape == (2, 100)


def test_base_config_size_matches_the_plan():
    params = count_parameters(ChordNet(ChordNetConfig()))
    assert 2.5e6 < params < 5e6  # plan 03 §2.1: ~3.7 M


def test_frame_targets():
    chords = [(0.0, 1.0, "N"), (1.0, 2.0, "C:maj"), (2.0, 3.0, "C:maj"), (3.0, 4.0, "G:sus4"), (4.0, 5.0, "A:min7/b3")]
    t = frame_targets(int(5 * FPS), FPS, chords, MAJMIN, keys=[(0.0, 0, None), (3.5, 7, "major")])
    at = lambda sec: int(sec * FPS)  # noqa: E731
    assert t.chord[at(0.5)] == MAJMIN.no_chord and t.root[at(0.5)] == 12
    assert t.chord[at(1.9)] == t.chord[at(2.1)] == MAJMIN.encode("C:maj")  # repeated labels merged
    assert t.chord[at(3.5)] == IGNORE and t.root[at(3.5)] == 7  # sus4 has no majmin class but a root
    assert t.bass[at(4.5)] == 0 and t.chord[at(4.5)] == MAJMIN.encode("A:min")
    assert t.tones[at(4.5)].tolist() == [1, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0]  # A C E G
    assert t.key_tonic[at(1.0)] == 0 and t.key_tonic[at(4.0)] == 7
    assert t.key_mode[at(1.0)] == IGNORE and t.key_mode[at(4.0)] == 0
    changes = np.flatnonzero(t.boundary)
    assert any(abs(c - 1.0 * FPS) <= 1.5 for c in changes) and not any(abs(c - 2.0 * FPS) <= 1.5 for c in changes)


def test_transposition_keeps_features_and_targets_aligned():
    y_chords = [(0.0, 2.0, "C:maj"), (2.0, 4.0, "A:min")]
    raw = features.extract(features.CQT_BOTHCHROMA, *_render(y_chords))
    x = features.normalise_bothchroma(raw)
    t = frame_targets(len(x), features.CQT_BOTHCHROMA.frame_rate, y_chords, SEVENTHS)
    for k in (-5, 3):
        xk, tk = transpose_input(x, k, "bothchroma"), transpose_targets(t, k, SEVENTHS)
        frame = int(1.0 * features.CQT_BOTHCHROMA.frame_rate)
        assert np.argmax(xk[frame, :12]) == tk.bass[frame] == (0 + k) % 12
        assert SEVENTHS.decode(int(tk.chord[frame])) == SEVENTHS.decode(SEVENTHS.transpose_index(int(t.chord[frame]), k))


def _render(chords):
    from chordify_core import synth

    audio, _ = synth.render_progression([(lab, e - s) for s, e, lab in chords], sr=22050)
    return audio, 22050


def test_log_cqt_transposition_shifts_three_bins_per_semitone():
    x = np.zeros((1, 288), dtype=np.float32)
    x[0, 100] = 1.0
    assert np.argmax(transpose_input(x, 2, "log_cqt")) == 106
    assert np.argmax(transpose_input(x, -1, "log_cqt")) == 97


def test_loss_is_finite_and_differentiable():
    chords = [(0.0, 3.0, "C:maj"), (3.0, 6.0, "X"), (6.0, 9.0, "N")]
    t = frame_targets(int(9 * FPS), FPS, chords, MAJMIN)
    batch = collate([to_tensors(np.random.rand(len(t), 25).astype(np.float32), t),
                     to_tensors(np.random.rand(50, 25).astype(np.float32), t.take(np.arange(50)))])
    model = ChordNet(ChordNetConfig.small(n_layers=1))
    loss, parts = chordnet_loss(model(batch["features"]), batch)
    loss.backward()
    assert np.isfinite(loss.item()) and set(parts) >= {"chord", "boundary", "tones"}
    assert batch["chord"][1, -1] == IGNORE and not batch["frame_mask"][1, -1]


def test_windowed_inference_stitches_exactly():
    x = np.random.default_rng(0).normal(size=(1300, 25)).astype(np.float32)
    identity = lambda chunk: {"chord": chunk}  # noqa: E731
    np.testing.assert_array_equal(acoustic.windowed(identity, x, window=512, margin=64)["chord"], x)
    np.testing.assert_array_equal(acoustic.windowed(identity, x[:300], window=512)["chord"], x[:300])


def _toy_tracks(n=4, seconds=24.0):
    rng = np.random.default_rng(0)
    pool = ["C:maj", "G:maj", "A:min", "F:maj", "D:min", "E:min"]
    out = []
    for i in range(n):
        chords, t = [], 0.0
        while t < seconds:
            chords.append((t, t + 2.0, pool[rng.integers(len(pool))]))
            t += 2.0
        out.append(BillboardTrack(f"{i:04d}", "t", "a", seconds, chords, tonics=[(0.0, "C")], metres=[(0.0, "4/4")],
                                  bars=[{"start": s, "end": s + 2.0, "chords": [], "beats_per_bar": 4}
                                        for s in np.arange(0, seconds, 2.0)]))
    return out


def test_tiny_training_run_learns_and_exports(tmp_path):
    tracks = sources.synthetic(_toy_tracks(), MAJMIN, features.CQT_BOTHCHROMA, cache_dir=tmp_path / "cache")
    config = TrainConfig(model=ChordNetConfig.small(d_model=64, n_layers=1), epochs=4, items_per_epoch=64,
                         batch_size=8, crop=256, warmup_steps=5, lr=3e-3, patience=10)
    result = train(config, tracks[:3], tracks[3:], tmp_path / "run", log=lambda *_: None)
    losses = [row["loss"]["chord"] for row in result["history"]]
    assert losses[-1] < losses[0]
    assert (tmp_path / "run" / "best.pt").exists()

    from chordify_ai.export.bundle import write_bundle
    from chordify_ai.train.trainer import load_checkpoint

    model, checkpoint = load_checkpoint(result["checkpoint"])
    bundle_dir = write_bundle(model, tmp_path / "bundle", "chordnet-test@0.0.1", features.CQT_BOTHCHROMA, "majmin",
                              result["prior"], {"alpha": 0.3}, {"validation": checkpoint["validation"]}, {"source": "toy"})
    bundle = json.loads((bundle_dir / "bundle.json").read_text())
    assert bundle["onnx_parity_max_abs"] < 1e-3 and bundle["features"]["kind"] == "cqt_bothchroma"

    onnx_model = acoustic.load_acoustic_model(bundle_dir)
    raw = features.extract(features.CQT_BOTHCHROMA, *_render(tracks[0].reference))
    output = onnx_model.predict(raw)
    assert output.chord.shape == (len(raw), MAJMIN.size)
    np.testing.assert_allclose(output.chord.sum(axis=1), 1.0, rtol=1e-5)
    assert onnx_model.feature_spec == features.CQT_BOTHCHROMA

    (bundle_dir / "model.onnx").write_bytes(b"tampered")
    with pytest.raises(ValueError, match="checksum"):
        acoustic.load_acoustic_model(bundle_dir)
    assert set(evaluate(model, tracks[3:], MAJMIN, None)) >= {"majmin", "seg"}


def test_crop_dataset_items_have_consistent_lengths():
    tracks = sources.synthetic(_toy_tracks(2, 12.0), MAJMIN, features.CQT_BOTHCHROMA)
    ds = CropDataset([t.example for t in tracks], MAJMIN, crop=128, items_per_epoch=5)
    item = ds[0]
    assert item["features"].shape[0] == item["chord"].shape[0] == item["boundary"].shape[0] <= 128


@pytest.mark.parametrize("config", [ChordNetConfig(d_model=64, n_layers=2, n_heads=4),
                                    ChordNetConfig(input="log_cqt", n_features=288, d_model=64, n_layers=1)])
def test_conformer_onnx_export_matches_pytorch_for_any_length(tmp_path, config):
    pytest.importorskip("onnxruntime")
    from chordify_ai.export.bundle import check_parity, export_onnx

    model = ChordNet(config).eval()
    export_onnx(model, tmp_path / "m.onnx", config.n_features)
    for frames in (37, 512, 900):
        assert check_parity(model, tmp_path / "m.onnx", config.n_features, frames=frames) < 1e-3
