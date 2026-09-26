"""Feature extraction with one definition for training and serving.

Every model bundle names the ``FeatureSpec`` it was trained on, and serving calls
``extract(spec, y, sr)`` with that same spec. That makes the v1 bug (training on
44.1 kHz / 2048-hop NNLS frames, serving 22.05 kHz audio through the same plugin,
so each frame lasted 93 ms instead of 46 ms) impossible to repeat.

Chroma features are stored with pitch class 0 = C. The NNLS plugin and the
Billboard ``bothchroma.csv`` start at A, so they are rolled on load.
"""
from __future__ import annotations

import os
from dataclasses import asdict, dataclass

import numpy as np

from .audio import resample

NNLS_ORDER_OFFSET = 9  # NNLS bin 0 is A (pitch class 9)


NNLS_BLOCK = 16384


@dataclass(frozen=True)
class FeatureSpec:
    kind: str  # "nnls_bothchroma" | "cqt_bothchroma" | "log_cqt"
    sample_rate: int
    hop: int
    n_bins: int
    offset: float = 0.0  # time (s) at the centre of frame 0

    @property
    def frame_rate(self) -> float:
        return self.sample_rate / self.hop

    def frame_times(self, n_frames: int) -> np.ndarray:
        """Centre time of every frame: ``offset + i / frame_rate``."""
        return self.offset + np.arange(n_frames, dtype=np.float64) / self.frame_rate

    def to_dict(self) -> dict:
        return asdict(self) | {"frame_rate": self.frame_rate}

    @classmethod
    def from_dict(cls, data: dict) -> "FeatureSpec":
        default = SPECS[data["kind"]].offset if data["kind"] in SPECS else 0.0
        return cls(data["kind"], int(data["sample_rate"]), int(data["hop"]), int(data["n_bins"]),
                   float(data.get("offset", default)))


# Billboard's released features: NNLS Chroma plugin at 44.1 kHz, step 2048 (46.4 ms), block 16384.
# The Vamp host stamps each frame at the centre of its 16384-sample block, so frame 0 is at 0.186 s.
NNLS_BOTHCHROMA = FeatureSpec("nnls_bothchroma", 44100, 2048, 24, offset=NNLS_BLOCK / 2 / 44100)
# Same frame rate and layout without the Vamp plugin (librosa CQT folded into bass/treble chroma).
# librosa centres frame i at i * hop.
CQT_BOTHCHROMA = FeatureSpec("cqt_bothchroma", 22050, 1024, 24)
# v3 input: 3 bins per semitone, C1 - 6 st to C8 + 6 st, same 21.53 fps frame rate.
LOG_CQT = FeatureSpec("log_cqt", 22050, 1024, 288)
SPECS = {s.kind: s for s in (NNLS_BOTHCHROMA, CQT_BOTHCHROMA, LOG_CQT)}


# ---------------------------------------------------------------------------
# NNLS chroma (Vamp plugin)
# ---------------------------------------------------------------------------

def nnls_available() -> bool:
    try:
        import vamp
    except ImportError:
        return False
    return "nnls-chroma:nnls-chroma" in vamp.list_plugins()


def nnls_to_c_order(bothchroma: np.ndarray) -> np.ndarray:
    """Roll each 12-bin half of A-based NNLS output so that bin 0 = C."""
    bothchroma = np.asarray(bothchroma, dtype=np.float32)
    bass, treble = bothchroma[:, :12], bothchroma[:, 12:24]
    return np.concatenate([np.roll(bass, NNLS_ORDER_OFFSET, axis=1),
                           np.roll(treble, NNLS_ORDER_OFFSET, axis=1)], axis=1)


def nnls_bothchroma(y: np.ndarray, sr: int) -> np.ndarray:
    """[bass(12), treble(12)] NNLS chroma, C-ordered, at exactly 44.1 kHz / 2048."""
    import vamp

    y44 = resample(y, sr, NNLS_BOTHCHROMA.sample_rate)
    result = vamp.collect(y44, NNLS_BOTHCHROMA.sample_rate, "nnls-chroma:nnls-chroma", output="bothchroma",
                          block_size=NNLS_BLOCK, step_size=NNLS_BOTHCHROMA.hop)
    step, matrix = result["matrix"]
    if abs(float(step) - 1 / NNLS_BOTHCHROMA.frame_rate) > 1e-4:
        raise RuntimeError(f"unexpected NNLS frame step {float(step)} s")
    return nnls_to_c_order(matrix)


def load_billboard_bothchroma(path: str | os.PathLike) -> tuple[np.ndarray, np.ndarray]:
    """Read a Billboard ``bothchroma.csv`` (filename, time, 12 bass, 12 treble; A-based).

    Returns ``(times, features)`` with C-ordered features.
    """
    import csv

    times, rows = [], []
    with open(path, newline="") as handle:
        for row in csv.reader(handle):
            if len(row) < 26:
                continue
            times.append(float(row[1]))
            rows.append([float(v) for v in row[2:26]])
    return np.asarray(times, dtype=np.float64), nnls_to_c_order(np.asarray(rows, dtype=np.float32))


# ---------------------------------------------------------------------------
# CQT-based features (no plugin needed)
# ---------------------------------------------------------------------------

_CQT_FMIN_C1 = 32.703195662574764


def _fold_to_chroma(magnitude: np.ndarray, first_pc: int, weights: np.ndarray) -> np.ndarray:
    """Sum weighted semitone bins into 12 pitch classes. ``magnitude`` is (semitones, T)."""
    weighted = magnitude * weights[:, None]
    chroma = np.zeros((12, magnitude.shape[1]), dtype=np.float32)
    for i in range(magnitude.shape[0]):
        chroma[(first_pc + i) % 12] += weighted[i]
    return chroma


def cqt_bothchroma(y: np.ndarray, sr: int) -> np.ndarray:
    """Bass + treble chroma from a semitone CQT at 22.05 kHz / 1024 (21.53 fps).

    The bass window emphasises C1-B3 and the treble window C3-B7, mirroring the
    two NNLS outputs so both feature kinds share one model layout.
    """
    import librosa

    spec = CQT_BOTHCHROMA
    y22 = resample(y, sr, spec.sample_rate)
    # 3 bins per semitone, tuned to the recording; keeping each semitone's centre bin
    # avoids the heavy leakage of a 12-bin-per-octave CQT's wide filters.
    tuning = librosa.estimate_tuning(y=y22, sr=spec.sample_rate, bins_per_octave=36) if len(y22) > 4096 else 0.0
    cqt = np.abs(librosa.cqt(y22, sr=spec.sample_rate, hop_length=spec.hop, fmin=_CQT_FMIN_C1,
                             n_bins=252, bins_per_octave=36, tuning=tuning)).astype(np.float32)
    cqt = cqt.reshape(84, 3, -1)[:, 1, :]
    semitone = np.arange(84)
    bass_weights = np.clip(1.0 - np.abs(semitone - 18) / 18.0, 0.0, 1.0)  # peaks at F#2, fades by C4
    treble_weights = np.clip((semitone - 24) / 12.0, 0.0, 1.0) * np.clip((84 - semitone) / 12.0, 0.0, 1.0)
    bass = _fold_to_chroma(cqt, 0, bass_weights)
    treble = _fold_to_chroma(cqt, 0, treble_weights)
    return np.concatenate([bass, treble], axis=0).T


def log_cqt(y: np.ndarray, sr: int) -> np.ndarray:
    """log1p(100·|CQT|), 3 bins/semitone with a ±6-semitone margin for transposition by cropping."""
    import librosa

    spec = LOG_CQT
    y22 = resample(y, sr, spec.sample_rate)
    fmin = _CQT_FMIN_C1 * 2 ** (-6 / 12)
    cqt = np.abs(librosa.cqt(y22, sr=spec.sample_rate, hop_length=spec.hop, fmin=fmin,
                             n_bins=spec.n_bins, bins_per_octave=36))
    return np.log1p(100.0 * cqt).T.astype(np.float32)


# ---------------------------------------------------------------------------
# Dispatch, normalisation, transposition
# ---------------------------------------------------------------------------

def extract(spec: FeatureSpec, y: np.ndarray, sr: int) -> np.ndarray:
    """Raw (T, n_bins) features for ``spec``."""
    if spec.kind == "nnls_bothchroma":
        return nnls_bothchroma(y, sr)
    if spec.kind == "cqt_bothchroma":
        return cqt_bothchroma(y, sr)
    if spec.kind == "log_cqt":
        return log_cqt(y, sr)
    raise ValueError(f"unknown feature kind {spec.kind!r}")


def default_chroma_spec() -> FeatureSpec:
    """NNLS when the plugin is installed, otherwise the plugin-free CQT chroma."""
    return NNLS_BOTHCHROMA if nnls_available() else CQT_BOTHCHROMA


def normalise_bothchroma(features: np.ndarray, eps: float = 1e-6) -> np.ndarray:
    """(T, 24) raw bothchroma -> (T, 25): each 12-bin half divided by its own max, plus a
    per-song z-scored log-energy channel so silence (N) stays distinguishable.

    Identical for Billboard CSVs, NNLS and CQT chroma, and for training and serving.
    """
    features = np.asarray(features, dtype=np.float32)
    bass, treble = features[:, :12], features[:, 12:24]
    energy = np.log(eps + bass.sum(axis=1) + treble.sum(axis=1))
    std = energy.std()
    energy = (energy - energy.mean()) / (std if std > 1e-6 else 1.0)
    bass = bass / np.maximum(bass.max(axis=1, keepdims=True), eps)
    treble = treble / np.maximum(treble.max(axis=1, keepdims=True), eps)
    return np.concatenate([bass, treble, np.clip(energy, -5, 5)[:, None]], axis=1).astype(np.float32)


def transpose_chroma(features: np.ndarray, semitones: int) -> np.ndarray:
    """Transpose C-ordered chroma features: roll every 12-bin group, leave extra channels."""
    out = np.array(features, dtype=np.float32, copy=True)
    for start in range(0, (out.shape[1] // 12) * 12, 12):
        out[:, start:start + 12] = np.roll(features[:, start:start + 12], semitones, axis=1)
    return out


def frame_times(n_frames: int, spec: FeatureSpec) -> np.ndarray:
    return spec.frame_times(n_frames)
