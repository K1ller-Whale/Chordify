"""Training examples, random crops and augmentation for ChordNet (plan 02 §5.4)."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import torch
from torch.utils.data import Dataset

from chordify_core import features, vocab

from ..models.targets import IGNORE, FrameTargets, transpose_targets


@dataclass
class TrackExample:
    track_id: str
    features: np.ndarray  # (T, F) model input (already normalised)
    targets: FrameTargets
    input_kind: str  # "bothchroma" | "log_cqt"


def transpose_input(x: np.ndarray, semitones: int, input_kind: str) -> np.ndarray:
    if input_kind == "bothchroma":
        return features.transpose_chroma(x, semitones)
    shift = 3 * semitones  # log-CQT has 3 bins per semitone; the ±6 st margin absorbs the shift
    out = np.zeros_like(x)
    n = x.shape[1]
    if shift >= 0:
        out[:, shift:] = x[:, :n - shift]
    else:
        out[:, :n + shift] = x[:, -shift:]
    return out


class CropDataset(Dataset):
    """Songs sampled uniformly; each item is a random crop with augmentation.

    Augmentation (training only): random transposition (roots balanced by construction,
    replacing v1's undersampling), time-stretch by frame resampling, Gaussian noise.
    """

    def __init__(self, examples: list[TrackExample], vocabulary: vocab.Vocabulary, crop: int = 512,
                 items_per_epoch: int = 2000, transpose: bool = True, stretch: tuple[float, float] = (0.85, 1.15),
                 noise: float = 0.02, seed: int = 0):
        self.examples = examples
        self.vocabulary = vocabulary
        self.crop = crop
        self.items_per_epoch = items_per_epoch
        self.transpose = transpose
        self.stretch = stretch
        self.noise = noise
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return self.items_per_epoch

    def __getitem__(self, _: int) -> dict[str, torch.Tensor]:
        ex = self.examples[self.rng.integers(len(self.examples))]
        x, targets = ex.features, ex.targets
        if self.stretch:
            rate = self.rng.uniform(*self.stretch)
            index = np.floor(np.arange(int(len(x) / rate)) * rate).astype(np.int64)
            index = index[index < len(x)]
            x, targets = x[index], targets.take(index)
        if len(x) > self.crop:
            start = int(self.rng.integers(0, len(x) - self.crop + 1))
            x, targets = x[start:start + self.crop], targets.take(np.arange(start, start + self.crop))
        if self.transpose:
            k = int(self.rng.integers(-5, 7))
            if k:
                x = transpose_input(x, k, ex.input_kind)
                targets = transpose_targets(targets, k, self.vocabulary)
        if self.noise:
            x = x + self.rng.normal(0, self.noise, size=x.shape).astype(np.float32)
        return to_tensors(x, targets)


def to_tensors(x: np.ndarray, t: FrameTargets) -> dict[str, torch.Tensor]:
    return {"features": torch.from_numpy(np.ascontiguousarray(x, dtype=np.float32)),
            "chord": torch.from_numpy(t.chord), "root": torch.from_numpy(t.root), "bass": torch.from_numpy(t.bass),
            "tones": torch.from_numpy(t.tones), "tones_mask": torch.from_numpy(t.tones_mask),
            "key_tonic": torch.from_numpy(t.key_tonic), "key_mode": torch.from_numpy(t.key_mode),
            "boundary": torch.from_numpy(t.boundary), "frame_mask": torch.from_numpy(t.frame_mask)}


def collate(items: list[dict[str, torch.Tensor]]) -> dict[str, torch.Tensor]:
    """Pad to the longest item: ignore-index for labels, False for masks, zeros elsewhere."""
    length = max(item["features"].shape[0] for item in items)
    out: dict[str, torch.Tensor] = {}
    for name in items[0]:
        padded = []
        for item in items:
            value = item[name]
            pad = length - value.shape[0]
            if pad:
                fill = IGNORE if value.dtype == torch.int64 else 0
                value = torch.cat([value, torch.full((pad,) + value.shape[1:], fill, dtype=value.dtype)])
            padded.append(value)
        out[name] = torch.stack(padded)
    return out
