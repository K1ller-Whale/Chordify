"""Export a trained ChordNet to an immutable model bundle (plan 03 §8).

    models/chordnet-<kind>/<version>/
        model.onnx     dynamic time axis, input "features" (1, T, F)
        bundle.json    feature spec, vocabulary, decoder params, class prior, metrics, sha256
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import torch

from chordify_core.features import FeatureSpec

from ..models.chordnet import ChordNet

OUTPUTS = ("chord", "root", "bass", "tones", "key_tonic", "key_mode", "boundary")


class _Wrapper(torch.nn.Module):
    def __init__(self, model: ChordNet):
        super().__init__()
        self.model = model

    def forward(self, features):
        out = self.model(features)
        return tuple(out[name] for name in OUTPUTS)


def export_onnx(model: ChordNet, path: str | Path, n_features: int, opset: int = 17) -> None:
    model = model.eval()
    example = torch.randn(1, 300, n_features)
    torch.onnx.export(_Wrapper(model), (example,), str(path), input_names=["features"], output_names=list(OUTPUTS),
                      dynamic_axes={"features": {1: "time"}, **{n: {1: "time"} for n in OUTPUTS}},
                      opset_version=opset, dynamo=False)


def check_parity(model: ChordNet, onnx_path: str | Path, n_features: int, frames: int = 700) -> float:
    """Max absolute difference between PyTorch and ONNX Runtime chord logits."""
    import onnxruntime as ort

    x = np.random.default_rng(0).normal(size=(1, frames, n_features)).astype(np.float32)
    with torch.no_grad():
        expected = model.eval()(torch.from_numpy(x))["chord"].numpy()
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    got = session.run(["chord"], {"features": x})[0]
    return float(np.abs(expected - got).max())


def write_bundle(model: ChordNet, out_dir: str | Path, bundle_id: str, feature_spec: FeatureSpec, vocabulary: str,
                 class_prior: np.ndarray, decoder: dict, metrics: dict, training_data: dict,
                 window: int = 512, margin: int = 64) -> Path:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    onnx_path = out / "model.onnx"
    export_onnx(model, onnx_path, model.config.n_features)
    parity = check_parity(model, onnx_path, model.config.n_features)
    if parity > 1e-3:
        raise RuntimeError(f"ONNX export differs from PyTorch by {parity}")
    bundle = {
        "id": bundle_id, "role": "chord", "type": "chordnet",
        "files": {"model": "model.onnx", "sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest()},
        "features": feature_spec.to_dict(), "normalisation": "bothchroma-v1" if "bothchroma" in feature_spec.kind else None,
        "vocabulary": vocabulary, "outputs": list(OUTPUTS), "model": model.config.to_dict(),
        "inference": {"window": window, "margin": margin}, "decoder": decoder,
        "class_prior": [round(float(p), 6) for p in class_prior], "metrics": metrics,
        "training_data": training_data, "onnx_parity_max_abs": parity,
        "created": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    (out / "bundle.json").write_text(json.dumps(bundle, indent=1) + "\n")
    return out
