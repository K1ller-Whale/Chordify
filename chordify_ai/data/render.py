"""Songs rendered to audio with FluidSynth, to cover chord types real datasets barely contain.

Billboard's 42 training hours hold about 30 seconds of dim7 and a minute of m(maj7); no model
learns a chord type from that. Two kinds of rendered songs fill the gap:

- ``generate_song``: new songs whose chord types are deliberately balanced over the 14-type
  large vocabulary (``QUALITY_SHARE``), over common root movements, with inversions, one or
  two accompaniment instruments in varied patterns, a bass line, drums and a melody.
- ``arrange_pop909``: POP909's human-made MIDI arrangements, re-orchestrated with random
  General MIDI instruments, plus a bass line and drums that follow its chord and beat labels.

Audio comes from the FluidSynth command-line synthesiser with a General MIDI soundfont
(FluidR3_GM, MIT licence; ``tools/get_extra_data.sh`` fetches both). Every song is
reproducible from its index and seed, and ``GENERATOR_VERSION`` goes into feature caches.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from chordify_core import vocab

GENERATOR_VERSION = 1

# Target share of generated chord time per chord type (the rest is N).
QUALITY_SHARE = {"maj": 0.13, "min": 0.11, "7": 0.09, "min7": 0.08, "maj7": 0.08, "maj6": 0.05, "min6": 0.05,
                 "sus4": 0.05, "sus2": 0.05, "dim": 0.05, "aug": 0.05, "dim7": 0.06, "hdim7": 0.07, "minmaj7": 0.05}
NO_CHORD_SHARE = 0.03
INVERSION_SHARE = 0.2
# Root movement between consecutive chords, in semitones (fourths/fifths and steps dominate).
ROOT_STEPS = {5: 0.25, 7: 0.15, 2: 0.12, 10: 0.12, 9: 0.08, 3: 0.08, 8: 0.06, 4: 0.05, 1: 0.04, 11: 0.03, 6: 0.02}
CHORD_BEATS = {1: 0.1, 2: 0.35, 4: 0.45, 8: 0.1}

# General MIDI programs (0-based).
HARMONY_PROGRAMS = (0, 1, 2, 4, 5, 6, 16, 17, 18, 19, 24, 25, 26, 27, 29, 46, 48, 49, 50, 52, 61, 88, 89, 90, 91, 94)
BASS_PROGRAMS = (32, 33, 34, 35, 36, 38, 39, 43)
MELODY_PROGRAMS = (40, 56, 57, 64, 65, 66, 71, 72, 73, 80, 81)
POP909_PIANO_PROGRAMS = (0, 1, 2, 4, 5, 16, 24, 25, 26, 46, 48)
POP909_BRIDGE_PROGRAMS = (0, 24, 48, 49, 52, 61, 89)
HARMONY_PATTERNS = ("block_beats", "block_bar", "arpeggio", "strum", "stabs")
BASS_PATTERNS = ("whole", "beats", "root_fifth", "eighths")
KICK, SNARE, HAT = 36, 38, 42


@dataclass
class Song:
    song_id: str
    midi: "pretty_midi.PrettyMIDI"  # noqa: F821
    chords: list[tuple[float, float, str]]
    beats: list[float]
    duration: float
    keys: list[tuple[float, str]] = field(default_factory=list)


# ---------------------------------------------------------------------------
# FluidSynth
# ---------------------------------------------------------------------------

SOUNDFONT_CANDIDATES = (
    "/usr/share/sounds/sf2/FluidR3_GM.sf2",
    "/usr/share/soundfonts/FluidR3_GM.sf2",
    str(Path(__file__).resolve().parents[2] / "data" / "raw" / "soundfonts" / "FluidR3_GM.sf2"),
)


def fluidsynth_binary() -> str | None:
    return os.environ.get("CHORDIFY_FLUIDSYNTH") or shutil.which("fluidsynth")


def find_soundfont(path: str | os.PathLike | None = None) -> Path | None:
    for candidate in (path, os.environ.get("CHORDIFY_SOUNDFONT"), *SOUNDFONT_CANDIDATES):
        if candidate and Path(candidate).is_file():
            return Path(candidate)
    return None


def render(song: Song, soundfont: str | os.PathLike, sr: int = 44100, gain: float = 0.5) -> np.ndarray:
    """Mono float32 audio of ``song``, cut to its labelled length and peak-normalised."""
    import soundfile as sf

    binary = fluidsynth_binary()
    if binary is None:
        raise RuntimeError("fluidsynth not found (Linux: apt-get install fluidsynth; macOS: brew install fluid-synth)")
    with tempfile.TemporaryDirectory() as tmp:
        midi, wav = Path(tmp) / "song.mid", Path(tmp) / "song.wav"
        song.midi.write(str(midi))
        subprocess.run([binary, "-ni", "-q", "-g", str(gain), "-r", str(sr), "-F", str(wav), str(soundfont), str(midi)],
                       check=True, capture_output=True, timeout=600)
        y, file_sr = sf.read(wav, dtype="float32", always_2d=True)
    y = y.mean(axis=1)
    if file_sr != sr:
        from chordify_core.audio import resample

        y = resample(y, file_sr, sr)
    n = int(round(song.duration * sr))
    y = np.pad(y, (0, max(0, n - len(y))))[:n]
    peak = float(np.abs(y).max())
    return (y / peak * 0.9).astype(np.float32) if peak > 0 else y


# ---------------------------------------------------------------------------
# Voicings and patterns
# ---------------------------------------------------------------------------

def _intervals(chord: vocab.Chord) -> list[int]:
    return sorted({vocab.degree_to_semitones(d) % 12 for d in chord.degrees} | {0})


def voicing(label: str, rng: np.random.Generator, spread: bool = False, own_bass: bool = False) -> list[int]:
    """MIDI notes for the accompaniment: every chord tone (a perfect fifth may be left out of
    a four-note chord, as players do), a random inversion of the upper structure, around C4.
    With ``own_bass`` (no bass instrument) the labelled bass note is added below, so the audio's
    lowest note is the one the label names."""
    chord = vocab.parse(label)
    intervals = _intervals(chord)
    if len(intervals) >= 4 and 7 in intervals and rng.random() < 0.3:
        intervals.remove(7)
    notes = [48 + chord.root + i for i in intervals]
    k = int(rng.integers(len(notes)))
    notes = sorted(notes[k:] + [n + 12 for n in notes[:k]])
    if spread and len(notes) >= 3:
        notes[1] += 12
        notes.sort()
    while notes[0] < 50:
        notes = [n + 12 for n in notes]
    while notes[0] > 62:
        notes = [n - 12 for n in notes]
    if own_bass:
        low = notes[0] - 1
        while low % 12 != chord.bass:
            low -= 1
        notes.insert(0, low)
    return notes


def bass_note(label: str) -> int:
    return 33 + (vocab.parse(label).bass - 9) % 12  # A1 .. G#2


def _note(pm_instrument, pitch: int, start: float, end: float, velocity: int, rng: np.random.Generator) -> None:
    import pretty_midi

    jitter = float(rng.normal(0, 0.006))
    start = max(0.0, start + jitter)
    if end - start > 0.02:
        pm_instrument.notes.append(pretty_midi.Note(int(np.clip(velocity, 20, 127)), int(pitch), start, end))


def _harmony(inst, label: str, t0: float, t1: float, beat: float, pattern: str, rng: np.random.Generator,
             spread: bool, own_bass: bool = False) -> None:
    notes = voicing(label, rng, spread, own_bass)
    base = int(rng.integers(55, 95))
    if pattern == "block_bar":
        for n in notes:
            _note(inst, n, t0, t1, base, rng)
        return
    if pattern == "arpeggio":
        step, t, i = beat / 2, t0, 0
        cycle = notes + notes[-2:0:-1] if len(notes) > 2 else notes
        while t < t1 - 1e-3:
            _note(inst, cycle[i % len(cycle)], t, min(t + step * 1.5, t1), base + int(rng.integers(-8, 8)), rng)
            t, i = t + step, i + 1
        return
    if pattern == "stabs":
        t = t0 + beat / 2
        while t < t1 - 1e-3:
            for n in notes:
                _note(inst, n, t, min(t + beat * 0.35, t1), base, rng)
            t += beat
        return
    strum = pattern == "strum"
    t = t0
    while t < t1 - 1e-3:
        for k, n in enumerate(notes):
            offset = k * float(rng.uniform(0.008, 0.02)) if strum else 0.0
            _note(inst, n, t + offset, min(t + beat * 0.95, t1), base + int(rng.integers(-6, 6)), rng)
        t += beat


def _bass(inst, label: str, t0: float, t1: float, beat: float, pattern: str, rng: np.random.Generator) -> None:
    low = bass_note(label)
    chord = vocab.parse(label)
    fifth = low + 7 if 7 in _intervals(chord) and chord.bass == chord.root else low
    velocity = int(rng.integers(70, 105))
    if pattern == "whole":
        _note(inst, low, t0, t1, velocity, rng)
        return
    step = beat / 2 if pattern == "eighths" else beat
    t, i = t0, 0
    while t < t1 - 1e-3:
        pitch = fifth if pattern == "root_fifth" and i % 2 else low
        _note(inst, pitch, t, min(t + step * 0.9, t1), velocity, rng)
        t, i = t + step, i + 1


def _drums(pm, beats: list[float], meter: int, end: float, rng: np.random.Generator) -> None:
    import pretty_midi

    kit = pretty_midi.Instrument(program=0, is_drum=True, name="drums")
    beat = float(np.median(np.diff(beats))) if len(beats) > 1 else 0.5
    for i, t in enumerate(beats):
        if t >= end:
            break
        position = i % meter
        if position == 0 or (meter == 4 and position == 2):
            _note(kit, KICK, t, t + 0.1, int(rng.integers(90, 120)), rng)
        if (meter == 4 and position in (1, 3)) or (meter == 3 and position == 2):
            _note(kit, SNARE, t, t + 0.1, int(rng.integers(80, 110)), rng)
        for half in (0.0, beat / 2):
            _note(kit, HAT, t + half, t + half + 0.05, int(rng.integers(45, 80)), rng)
    pm.instruments.append(kit)


def _melody(inst, chords: list[tuple[float, float, str]], beat: float, rng: np.random.Generator) -> None:
    for t0, t1, label in chords:
        chord = vocab.parse(label)
        if not chord.is_chord:
            continue
        tones = [72 + (chord.root + i) % 12 for i in _intervals(chord)]
        t = t0
        while t < t1 - 1e-3:
            length = beat * float(rng.choice([0.5, 1.0, 2.0], p=[0.4, 0.45, 0.15]))
            if rng.random() > 0.2:
                pitch = int(rng.choice(tones))
                if rng.random() < 0.25:  # passing / neighbour note
                    pitch += int(rng.choice([-2, -1, 1, 2]))
                _note(inst, pitch, t, min(t + length, t1), int(rng.integers(60, 100)), rng)
            t += length


# ---------------------------------------------------------------------------
# Songs
# ---------------------------------------------------------------------------

def _choice(rng: np.random.Generator, table: dict):
    keys = list(table)
    p = np.array([table[k] for k in keys], dtype=float)
    return keys[int(rng.choice(len(keys), p=p / p.sum()))]


def generate_chords(rng: np.random.Generator, beat: float, meter: int, seconds: float) -> list[tuple[float, float, str]]:
    root = int(rng.integers(12))
    chords, t = [], 0.0
    while t < seconds:
        beats = _choice(rng, {3: 0.4, 6: 0.4, 1: 0.1, 12: 0.1}) if meter == 3 else _choice(rng, CHORD_BEATS)
        if rng.random() < NO_CHORD_SHARE:
            label = vocab.NO_CHORD
        else:
            label = f"{vocab.SHARP_NAMES[root]}:{_choice(rng, QUALITY_SHARE)}"
            if rng.random() < INVERSION_SHARE:
                degrees = sorted(vocab.parse(label).degrees - {"1"})
                label += "/" + degrees[int(rng.integers(len(degrees)))]
        chords.append((t, t + beats * beat, label))
        t += beats * beat
        root = (root + _choice(rng, ROOT_STEPS)) % 12
    return chords


def generate_song(index: int, seed: int = 0) -> Song:
    """A new song with balanced chord types; deterministic in (index, seed)."""
    import pretty_midi

    rng = np.random.default_rng([seed, index, GENERATOR_VERSION])
    tempo = float(rng.uniform(70, 150))
    beat = 60.0 / tempo
    meter = 3 if rng.random() < 0.15 else 4
    chords = generate_chords(rng, beat, meter, float(rng.uniform(35, 60)))
    duration = chords[-1][1]
    beats = list(np.arange(0.0, duration, beat))
    pm = pretty_midi.PrettyMIDI(initial_tempo=tempo)
    has_bass = bool(rng.random() < 0.85)
    layers = 2 if rng.random() < 0.35 else 1
    for layer in range(layers):
        program = int(rng.choice(HARMONY_PROGRAMS))
        pattern = "block_bar" if layer == 1 else str(rng.choice(HARMONY_PATTERNS))
        spread = bool(rng.random() < 0.4)
        inst = pretty_midi.Instrument(program=program, name=f"harmony{layer}")
        for t0, t1, label in chords:
            if label != vocab.NO_CHORD:
                _harmony(inst, label, t0, t1, beat, pattern, rng, spread, own_bass=not has_bass and layer == 0)
        pm.instruments.append(inst)
    if has_bass:
        bass = pretty_midi.Instrument(program=int(rng.choice(BASS_PROGRAMS)), name="bass")
        pattern = str(rng.choice(BASS_PATTERNS))
        for t0, t1, label in chords:
            if label != vocab.NO_CHORD:
                _bass(bass, label, t0, t1, beat, pattern, rng)
        pm.instruments.append(bass)
    if rng.random() < 0.4:
        melody = pretty_midi.Instrument(program=int(rng.choice(MELODY_PROGRAMS)), name="melody")
        _melody(melody, chords, beat, rng)
        pm.instruments.append(melody)
    if rng.random() < 0.6:
        _drums(pm, beats, meter, duration, rng)
    return Song(f"gen{GENERATOR_VERSION}-{seed}-{index:05d}", pm, chords, [float(b) for b in beats], duration)


def arrange_pop909(song, seed: int = 0) -> Song:
    """A POP909 song (``chordify_ai.data.pop909.Pop909Song``) re-orchestrated: random GM
    instruments for its tracks, and sometimes a bass line and drums from its labels."""
    import pretty_midi

    rng = np.random.default_rng([seed, int(song.song_id), GENERATOR_VERSION])
    pm = pretty_midi.PrettyMIDI(str(song.midi))
    for inst in pm.instruments:
        name = inst.name.upper()
        table = MELODY_PROGRAMS if "MELODY" in name else POP909_BRIDGE_PROGRAMS if "BRIDGE" in name else POP909_PIANO_PROGRAMS
        inst.program = int(rng.choice(table))
        scale = float(rng.uniform(0.7, 1.1))
        for note in inst.notes:
            note.velocity = int(np.clip(note.velocity * scale, 20, 127))
    beat = float(np.median(np.diff(song.beats))) if len(song.beats) > 1 else 0.5
    if rng.random() < 0.6:
        bass = pretty_midi.Instrument(program=int(rng.choice(BASS_PROGRAMS)), name="bass")
        pattern = str(rng.choice(BASS_PATTERNS))
        for t0, t1, label in song.chords:
            if vocab.parse(label).is_chord:
                _bass(bass, label, t0, t1, beat, pattern, rng)
        pm.instruments.append(bass)
    if rng.random() < 0.5 and song.beats:
        _drums(pm, song.beats, 4, song.duration, rng)
    return Song(f"pop909-{song.song_id}-{seed}", pm, list(song.chords), list(song.beats), song.duration,
                list(song.keys))
