"""Tiny additive synthesiser that renders chord labels to audio with exact timings.

Used by tests and demos, and the seed of the synthetic training-data generator
(plan 02 §7): every render comes with perfect labels.
"""
from __future__ import annotations

import numpy as np

from . import vocab


def _note(freq: float, n: int, sr: int, harmonics: int = 5, decay: float = 1.6) -> np.ndarray:
    t = np.arange(n) / sr
    tone = sum((0.6 ** h) * np.sin(2 * np.pi * freq * (h + 1) * t) for h in range(harmonics))
    attack = np.minimum(1.0, t / 0.01)
    release = np.minimum(1.0, (n / sr - t) / 0.03).clip(0, 1)
    return (tone * attack * release * np.exp(-t * decay)).astype(np.float32)


def render_chord(label: str, duration: float, sr: int = 22050, octave: int = 4, bass_octave: int = 2,
                 harmonics: int = 5, decay: float = 1.6, spread: bool = False, detune_cents: float = 0.0,
                 rng: np.random.Generator | None = None) -> np.ndarray:
    """Chord tones around ``octave`` plus the bass note in ``bass_octave``; silence for N.

    ``spread`` voices each tone in a random octave (4 or 5) like an open guitar/piano voicing.
    """
    n = int(round(duration * sr))
    chord = vocab.parse(label)
    if not chord.is_chord:
        return np.zeros(n, dtype=np.float32)
    out = np.zeros(n, dtype=np.float32)
    detune = 2 ** (detune_cents / 1200)
    for pc in sorted(chord.pitch_classes):
        o = octave + (int(rng.integers(0, 2)) if spread and rng is not None else 0)
        midi = 12 * (o + 1) + pc
        out += _note(440.0 * detune * 2 ** ((midi - 69) / 12), n, sr, harmonics=harmonics, decay=decay)
    bass_midi = 12 * (bass_octave + 1) + chord.bass
    out += 1.2 * _note(440.0 * detune * 2 ** ((bass_midi - 69) / 12), n, sr, harmonics=3, decay=1.0)
    return out


def render_progression(chords: list[tuple[str, float]], sr: int = 22050, seed: int | None = 0,
                       noise_db: float = -45.0, variety: bool = False) -> tuple[np.ndarray, list[tuple[float, float, str]]]:
    """Render ``[(label, seconds), ...]``. Returns (audio, [(start, end, label), ...]).

    With ``variety`` each render gets its own timbre (harmonics, decay, register, voicing
    spread, tuning within ±30 cents) so synthetic training data is not one instrument.
    """
    rng = np.random.default_rng(seed)
    timbre = {}
    if variety:
        timbre = {"harmonics": int(rng.integers(2, 8)), "decay": float(rng.uniform(0.3, 3.0)),
                  "octave": int(rng.integers(3, 5)), "bass_octave": int(rng.integers(1, 3)),
                  "spread": bool(rng.integers(0, 2)), "detune_cents": float(rng.uniform(-30, 30)), "rng": rng}
    parts, intervals, t = [], [], 0.0
    for label, seconds in chords:
        parts.append(render_chord(label, seconds, sr, **timbre))
        intervals.append((t, t + seconds, label))
        t += seconds
    audio = np.concatenate(parts) if parts else np.zeros(0, dtype=np.float32)
    peak = np.abs(audio).max()
    if peak > 0:
        audio = 0.5 * audio / peak
    if seed is not None and noise_db is not None:
        audio = audio + (10 ** (noise_db / 20)) * rng.standard_normal(len(audio)).astype(np.float32)
    return audio.astype(np.float32), intervals
