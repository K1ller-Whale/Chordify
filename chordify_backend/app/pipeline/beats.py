"""Beat tracking. librosa's dynamic-programming tracker is the dependency-free default;
Beat This! (plan 03 §4) can replace it behind the same function."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

TRACKER_ID = "librosa-beat-track"


@dataclass
class BeatInfo:
    beats: np.ndarray  # seconds
    bpm: float | None
    confidence: float  # 0..1, regularity of inter-beat intervals

    @property
    def usable(self) -> bool:
        return len(self.beats) >= 8 and self.confidence >= 0.5


def track_beats(y: np.ndarray, sr: int) -> BeatInfo:
    import librosa

    y22 = librosa.resample(y, orig_sr=sr, target_sr=22050) if sr != 22050 else y
    onset = librosa.onset.onset_strength(y=y22, sr=22050, hop_length=512)
    tempo, beats = librosa.beat.beat_track(onset_envelope=onset, sr=22050, hop_length=512, units="time", trim=False)
    beats = np.asarray(beats, dtype=np.float64)
    if len(beats) < 4:
        return BeatInfo(beats, None, 0.0)
    intervals = np.diff(beats)
    confidence = float(np.clip(1.0 - intervals.std() / max(intervals.mean(), 1e-6), 0.0, 1.0))
    bpm = float(np.atleast_1d(tempo)[0]) if np.size(tempo) else 60.0 / float(np.median(intervals))
    return BeatInfo(beats, round(bpm, 1), round(confidence, 3))


def downbeat_phase(beats: np.ndarray, change_times: list[float], beats_per_bar: int = 4, tolerance: float = 0.08) -> int:
    """Pick the bar phase whose downbeats coincide with the most chord changes."""
    if len(beats) < beats_per_bar or not change_times:
        return 0
    changes = np.asarray(change_times)
    scores = []
    for phase in range(beats_per_bar):
        downbeats = beats[phase::beats_per_bar]
        distance = np.abs(changes[:, None] - downbeats[None, :]).min(axis=1)
        scores.append(int((distance < tolerance).sum()))
    return int(np.argmax(scores))
