"""Acoustic models at serving time: frame features -> per-frame posteriors.

- ``OnnxChordModel`` runs an exported ChordNet bundle with ONNX Runtime (no PyTorch).
- ``TemplateChordModel`` is a training-free baseline (chroma template matching, in the
  spirit of Chordino) so the full pipeline works before a trained model ships.

Both expose the same interface, and both declare the ``FeatureSpec`` they consume;
the pipeline extracts features with exactly that spec.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from . import features, vocab
from .features import FeatureSpec


@dataclass
class AcousticOutput:
    chord: np.ndarray  # (T, V) probabilities
    boundary: np.ndarray | None = None  # (T,) P(change)
    key_tonic: np.ndarray | None = None  # (T, 13)
    key_mode: np.ndarray | None = None  # (T, 2)
    extra: dict = field(default_factory=dict)


def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    x = x - x.max(axis=axis, keepdims=True)
    e = np.exp(x)
    return e / e.sum(axis=axis, keepdims=True)


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-x))


def windowed(run: Callable[[np.ndarray], dict[str, np.ndarray]], x: np.ndarray, window: int = 512,
             margin: int = 64) -> dict[str, np.ndarray]:
    """Run a sequence model over a long input in overlapping windows, keeping each
    window's centre (``window - 2*margin`` frames), so every kept frame has context."""
    n = x.shape[0]
    if n <= window:
        return {k: v[0] for k, v in run(x[None].astype(np.float32)).items()}
    step = window - 2 * margin
    outputs: dict[str, np.ndarray] = {}
    start = 0
    while True:
        s = min(start, n - window)
        chunk = run(x[None, s:s + window].astype(np.float32))
        keep_from = 0 if s == 0 else margin
        keep_to = window if s + window >= n else window - margin
        for name, value in chunk.items():
            buffer = outputs.setdefault(name, np.zeros((n,) + value.shape[2:], dtype=np.float32))
            buffer[s + keep_from:s + keep_to] = value[0, keep_from:keep_to]
        if s + window >= n:
            break
        start = s + step
    return outputs


def prepare(spec: FeatureSpec, raw: np.ndarray) -> np.ndarray:
    """Model input from raw features: bothchroma kinds are normalised (+ energy channel)."""
    if spec.kind.endswith("bothchroma"):
        return features.normalise_bothchroma(raw)
    return raw.astype(np.float32)


class OnnxChordModel:
    """An exported ChordNet bundle: ``bundle.json`` + ``model.onnx``."""

    def __init__(self, bundle_dir: str | Path):
        import onnxruntime as ort

        self.path = Path(bundle_dir)
        self.bundle = json.loads((self.path / "bundle.json").read_text())
        model_file = self.path / self.bundle["files"]["model"]
        expected = self.bundle["files"].get("sha256")
        if expected and hashlib.sha256(model_file.read_bytes()).hexdigest() != expected:
            raise ValueError(f"checksum mismatch for {model_file}")
        self.id = self.bundle["id"]
        self.feature_spec = FeatureSpec.from_dict(self.bundle["features"])
        self.vocabulary = vocab.VOCABULARIES[self.bundle["vocabulary"]]
        self.decoder_params = self.bundle.get("decoder", {})
        self.prior = np.asarray(self.bundle["class_prior"]) if "class_prior" in self.bundle else None
        self.window = int(self.bundle.get("inference", {}).get("window", 512))
        self.margin = int(self.bundle.get("inference", {}).get("margin", 64))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 0
        self.session = ort.InferenceSession(str(model_file), options, providers=["CPUExecutionProvider"])
        self.output_names = [o.name for o in self.session.get_outputs()]

    def _run(self, x: np.ndarray) -> dict[str, np.ndarray]:
        values = self.session.run(self.output_names, {"features": x})
        return dict(zip(self.output_names, values))

    def predict(self, raw_features: np.ndarray) -> AcousticOutput:
        logits = windowed(self._run, prepare(self.feature_spec, raw_features), self.window, self.margin)
        return AcousticOutput(chord=softmax(logits["chord"]), boundary=sigmoid(logits["boundary"]),
                              key_tonic=softmax(logits["key_tonic"]), key_mode=softmax(logits["key_mode"]),
                              extra={"root": softmax(logits["root"]), "bass": softmax(logits["bass"])})


class TemplateChordModel:
    """Chroma template matching for major/minor triads + N (no training).

    Score per class = cosine(treble chroma, triad template) + a bass-root bonus;
    N wins on low-energy frames. Temperature-scaled into posteriors for the decoder.
    """

    id = "chroma-templates@0.1.0"
    vocabulary = vocab.MAJMIN
    decoder_params = {"alpha": 0.0, "self_prob": 0.8, "subdivide": 1, "min_units": 1}
    prior = None

    def __init__(self, feature_spec: FeatureSpec | None = None, temperature: float = 0.08, bass_weight: float = 0.25):
        self.feature_spec = feature_spec or features.default_chroma_spec()
        self.temperature = temperature
        self.bass_weight = bass_weight
        templates = np.zeros((24, 12))
        for index in range(24):
            chord = vocab.parse(self.vocabulary.decode(index))
            templates[index, list(chord.pitch_classes)] = 1.0
        self.templates = templates / np.linalg.norm(templates, axis=1, keepdims=True)
        self.roots = np.array([self.vocabulary.root_of(i) for i in range(24)])

    def predict(self, raw_features: np.ndarray) -> AcousticOutput:
        x = prepare(self.feature_spec, raw_features)
        bass, treble, energy = x[:, :12], x[:, 12:24], x[:, 24]
        unit = treble / np.maximum(np.linalg.norm(treble, axis=1, keepdims=True), 1e-6)
        scores = unit @ self.templates.T + self.bass_weight * bass[:, self.roots]
        no_chord = 0.75 + 0.25 * np.clip(-energy - 1.0, 0.0, 4.0)  # strong only when clearly quiet
        logits = np.concatenate([scores, no_chord[:, None]], axis=1) / self.temperature
        return AcousticOutput(chord=softmax(logits))


def load_acoustic_model(reference: str | Path | None) -> OnnxChordModel | TemplateChordModel:
    """``None`` or ``"templates"`` -> TemplateChordModel; a directory -> ONNX bundle."""
    if reference in (None, "", "templates"):
        return TemplateChordModel()
    return OnnxChordModel(reference)
