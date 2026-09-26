"""Per-frame training targets built on the fly from chord intervals (plan 02 §5.1).

Labels are never stored per frame, so a new vocabulary tier needs no re-extraction.
"""
from __future__ import annotations

from dataclasses import dataclass, fields

import numpy as np

from chordify_core import vocab

IGNORE = -100


@dataclass
class FrameTargets:
    chord: np.ndarray  # (T,) class index or IGNORE (X / outside vocabulary / padding)
    root: np.ndarray  # (T,) 0-11, 12 = N, IGNORE
    bass: np.ndarray  # (T,) 0-11, 12 = N, IGNORE
    tones: np.ndarray  # (T, 12) float 0/1
    tones_mask: np.ndarray  # (T,) bool
    key_tonic: np.ndarray  # (T,) 0-11, 12 = none, IGNORE when unknown
    key_mode: np.ndarray  # (T,) 0 major, 1 minor, IGNORE when unknown (e.g. Billboard)
    segment: np.ndarray  # (T,) id of the chord segment (for boundaries)
    frame_mask: np.ndarray  # (T,) bool, False for padding

    @property
    def boundary(self) -> np.ndarray:
        """1.0 within ±1 frame of a change between segments."""
        change = np.zeros(len(self.segment), dtype=np.float32)
        idx = np.flatnonzero(np.diff(self.segment) != 0) + 1
        for offset in (-1, 0, 1):
            j = np.clip(idx + offset, 0, len(change) - 1)
            change[j] = 1.0
        return change

    def take(self, index: np.ndarray) -> "FrameTargets":
        return FrameTargets(**{f.name: getattr(self, f.name)[index] for f in fields(self)})

    def __len__(self) -> int:
        return len(self.chord)


def frame_targets(n_frames: int, frame_rate: float, chords: list[tuple[float, float, str]],
                  vocabulary: vocab.Vocabulary, keys: list[tuple[float, int | None, str | None]] | None = None,
                  frame_offset: float | None = None) -> FrameTargets:
    """Targets for frames whose centres fall inside ``chords`` intervals (else N).

    Frame i is centred at ``frame_offset + i / frame_rate`` (``FeatureSpec.offset``; default
    the middle of the frame). ``keys``: [(start_time, tonic_pc or None, mode or None), ...].
    """
    offset = 0.5 / frame_rate if frame_offset is None else frame_offset
    centres = offset + np.arange(n_frames) / frame_rate
    merged: list[list] = []
    for start, end, label in chords:
        if merged and merged[-1][2] == label and abs(merged[-1][1] - start) < 1e-3:
            merged[-1][1] = end
        else:
            merged.append([start, end, label])
    starts = np.array([m[0] for m in merged]) if merged else np.zeros(0)
    ends = np.array([m[1] for m in merged]) if merged else np.zeros(0)
    idx = np.searchsorted(starts, centres, side="right") - 1
    inside = (idx >= 0) & (idx < len(merged)) & (centres < ends[np.clip(idx, 0, max(len(merged) - 1, 0))]) \
        if merged else np.zeros(n_frames, dtype=bool)

    per_segment = []
    for _, _, label in merged:
        chord = vocab.parse(label)
        index = vocabulary.encode(label)
        if chord.label == vocab.NO_CHORD:
            per_segment.append((vocabulary.no_chord, 12, 12, np.zeros(12), True))
        elif chord.root is None:  # X
            per_segment.append((IGNORE, IGNORE, IGNORE, np.zeros(12), False))
        else:
            tones = np.zeros(12)
            tones[list(chord.pitch_classes)] = 1.0
            per_segment.append((IGNORE if index is None else index, chord.root, chord.bass, tones, True))
    no_chord = (vocabulary.no_chord, 12, 12, np.zeros(12), True)

    chord_t = np.empty(n_frames, dtype=np.int64)
    root_t = np.empty(n_frames, dtype=np.int64)
    bass_t = np.empty(n_frames, dtype=np.int64)
    tones_t = np.zeros((n_frames, 12), dtype=np.float32)
    tones_mask = np.zeros(n_frames, dtype=bool)
    segment = np.where(inside, idx, -1).astype(np.int64)
    for t in range(n_frames):
        c, r, b, tones, ok = per_segment[idx[t]] if inside[t] else no_chord
        chord_t[t], root_t[t], bass_t[t], tones_mask[t] = c, r, b, ok
        tones_t[t] = tones

    key_tonic = np.full(n_frames, IGNORE, dtype=np.int64)
    key_mode = np.full(n_frames, IGNORE, dtype=np.int64)
    for start, tonic, mode in keys or []:
        sel = centres >= start
        key_tonic[sel] = IGNORE if tonic is None else tonic
        key_mode[sel] = IGNORE if mode is None else (0 if mode == "major" else 1)
    return FrameTargets(chord_t, root_t, bass_t, tones_t, tones_mask, key_tonic, key_mode, segment,
                        np.ones(n_frames, dtype=bool))


def transpose_targets(targets: FrameTargets, semitones: int, vocabulary: vocab.Vocabulary) -> FrameTargets:
    def shift_pc(a: np.ndarray) -> np.ndarray:
        out = a.copy()
        sel = (a >= 0) & (a < 12)
        out[sel] = (a[sel] + semitones) % 12
        return out

    chord = targets.chord.copy()
    sel = chord >= 0
    chord[sel] = [vocabulary.transpose_index(int(c), semitones) for c in chord[sel]]
    return FrameTargets(chord, shift_pc(targets.root), shift_pc(targets.bass),
                        np.roll(targets.tones, semitones, axis=1), targets.tones_mask.copy(),
                        shift_pc(targets.key_tonic), targets.key_mode.copy(), targets.segment.copy(),
                        targets.frame_mask.copy())
