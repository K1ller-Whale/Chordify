"""Golden files for the Android engine, the Kotlin port of chordify_core.

    python -m chordify_ai.export.mobile_goldens                 # every file this machine can write
    python -m chordify_ai.export.mobile_goldens --only vocab    # one file
    tools/mobile_goldens.sh                                     # features, beats, acoustic (needs Docker)

Each file holds inputs and what chordify_core returns for them. The engine's tests
(Mobile/engine/src/test and src/androidTest) replay the inputs through the Kotlin port and
compare, and tests/ai/test_mobile_goldens.py fails when chordify_core changes and the files
are stale. Inputs are deterministic and never come from numpy's random generators, whose
streams may change between numpy releases. The one exception, the rendered audio in
goldens/audio, is committed: --render-audio writes the inputs that are missing, never the others.

features.json.gz needs the NNLS Chroma Vamp plugin, and beats.json.gz and acoustic.json.gz the
librosa and ONNX Runtime releases in PINNED. tools/mobile_goldens.sh provides all three in a Linux
container; elsewhere a file that cannot be reproduced is skipped, and missing(name) says why.
"""
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path

import numpy as np

from chordify_core import decode, lm, theory, vocab
from chordify_core.theory import Key

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "Mobile" / "engine" / "src" / "test" / "resources" / "goldens"
LM_MODEL = REPO / "models" / "progression-ngram" / "1.0.0" / "model.json.gz"


class Lcg:
    """64-bit linear congruential generator: the same numbers on every platform."""

    def __init__(self, seed: int):
        self.state = seed

    def random(self) -> float:
        self.state = (6364136223846793005 * self.state + 1442695040888963407) % 2**64
        return (self.state >> 11) / 2**53


def _attempt(fn, *args) -> dict:
    """{"value": fn(*args)}, or {"error": True} when it raises ValueError."""
    try:
        return {"value": fn(*args)}
    except ValueError:
        return {"error": True}


# ---------------------------------------------------------------------------
# vocab
# ---------------------------------------------------------------------------

EXTRA_LABELS = [
    "N", "X", "C", "C:maj/3", "C:maj/5", "G:7/b7", "G:7/3", "A:sus4(b7)", "A:sus4(b7,9)", "D:(1,b3,5)", "C:min(9)",
    "C:maj(9)", "C:maj(*5)", "C:7(#9)", "C:7(b9)", "C:7(#11)", "C:7(b13)", "C:maj6(9)", "C:5(b7)", "C:aug(b7)",
    "A:9", "A:maj9", "A:min9", "G:11", "G:min11", "G:13", "G:maj13", "C:5", "C:1", "Bb:min7", "E#:maj", "Cb:maj",
    "Fb:min", "B#:7", "Cbb:maj", "F#:hdim7/b3", "Eb:dim7/bb7", "Ab:minmaj7/5", "D:sus2/5", "E:maj6/6", " C:min ",
    "C:maj/b7", "C:min/2", "Gb:maj7/7",
]
INVALID_LABELS = ["H:maj", "C:foo", "C:maj(3", "C:", "", "c:maj", "C:maj/x", "C:(1,13x)", "Cx:maj"]
DISPLAY_NAMES = [
    "Em", "Bm7", "C/E", "A7sus4", "N.C.", "nc", "N", "Cmaj7", "CM7", "CΔ", "F#m7b5", "Bbø", "Bb°7", "Bbo", "G+",
    "Gaug7", "C6/9", "Dsus", "Dsus2", "Am(maj7)", "AmMaj7", "Em/G", "D/F#", "G7/F", "Fadd9", "Fmadd9", "Fm(add9)",
    "C-7", "C-", "Cdom7", "E9sus4", "Ab7#9", "Ab7b9", "Db6", "Dbm6", "C5", "C13", "Cm11", "C:min7", "Bb:maj/3",
    "Hm", "Cx7", "", "C/H", "C:foo", "Cmaj7#11",
]
WITH_BASS_LABELS = ["C:maj", "C:maj7", "G:7", "A:min", "B:hdim7", "D:sus4", "N", "X"]


def _inversions() -> list[str]:
    """Every chord type of the large vocabulary on D, over each of its chord tones."""
    return [f"D:{quality}/{degree}" for quality in vocab.LARGE_QUALITIES
            for degree in sorted(vocab.parse(f"D:{quality}").degrees - {"1"})]


def _chord_row(label: str) -> dict:
    chord = vocab.parse(label)
    row = {
        "label": label, "parsed": chord.label, "root": chord.root, "bass": chord.bass,
        "degrees": sorted(chord.degrees), "pitch_classes": sorted(chord.pitch_classes),
        "third": chord.third, "fifth": chord.fifth, "seventh": chord.seventh, "has_sixth": chord.has_sixth,
        "triad": vocab.triad(chord), "chord_tones": vocab.chord_tones(label),
        "display": {"auto": vocab.display_name(label), "flats": vocab.display_name(label, True),
                    "sharps": vocab.display_name(label, False)},
        "transpose": {"-3": vocab.transpose(label, -3), "-3b": vocab.transpose(label, -3, True),
                      "5": vocab.transpose(label, 5), "5b": vocab.transpose(label, 5, True)},
    }
    for name, vocabulary in vocab.VOCABULARIES.items():
        row[name] = {"encode": vocabulary.encode(label), "reduce": vocab.reduce(label, vocabulary),
                     "reduce_keep_bass": vocab.reduce_keep_bass(label, vocabulary),
                     "simplify": vocab.simplify(label, vocabulary)}
    return row


def vocab_golden() -> dict:
    for label in INVALID_LABELS:
        assert "error" in _attempt(vocab.parse, label), f"{label!r} parses; it does not belong in INVALID_LABELS"
    labels = list(dict.fromkeys(list(vocab.LARGE.labels) + _inversions() + EXTRA_LABELS))
    rows = [_chord_row(label) for label in labels]
    names = list(dict.fromkeys(DISPLAY_NAMES + [r["display"]["auto"] for r in rows]
                               + [r["display"]["flats"] for r in rows]))
    return {
        "chords": rows,
        "invalid": INVALID_LABELS,
        "vocabularies": [{"name": v.name, "qualities": list(v.qualities), "size": v.size, "no_chord": v.no_chord,
                          "labels": list(v.labels),
                          "transpose_index": {str(k): [v.transpose_index(i, k) for i in range(v.size)] for k in (-5, 7)},
                          "root_of": [v.root_of(i) for i in range(v.size)],
                          "quality_of": [v.quality_of(i) for i in range(v.size)]}
                         for v in vocab.VOCABULARIES.values()],
        "with_bass": [{"label": label, "bass": pc, "result": vocab.with_bass(label, pc)}
                      for label in WITH_BASS_LABELS for pc in range(12)],
        "parse_display": [{"name": name, **_attempt(vocab.parse_display, name)} for name in names],
    }


# ---------------------------------------------------------------------------
# theory
# ---------------------------------------------------------------------------

PARSE_KEY_TEXTS = ["D", "D:maj", "D major", "Bb:min", "F#m", "A minor", "C:minor", "Eb maj", "G#:m", "FM",
                   "Db  major", "Gbb:min", " E ", "C#:major", "a minor", "H", "", "C mixolydian"]
DESCRIBE_KEYS = [Key(0, "major"), Key(3, "major"), Key(6, "major"), Key(9, "minor"), Key(0, "minor"), Key(8, "minor")]
DESCRIBE_EXTRA = ["X", "C:maj/3", "G:7/b7", "A:sus4(b7)", "C:maj(9)", "F#:hdim7/b3", "C:5"]
PAIR_KEYS = [Key(0, "major"), Key(9, "minor"), Key(3, "major"), Key(6, "minor")]
PAIR_LABELS = (list(vocab.MAJMIN.labels[:-1]) + [f"{n}:7" for n in vocab.SHARP_NAMES]
               + [f"{n}:dim" for n in vocab.SHARP_NAMES] + ["N"])
ESTIMATE_CASES = [
    [("C:maj", 2.0), ("G:maj", 2.0), ("A:min", 2.0), ("F:maj", 2.0)] * 2,
    [("A:min", 2.0), ("G:maj", 2.0), ("F:maj", 2.0), ("E:maj", 2.0)] * 2,
    [("D:maj", 4.0), ("A:7", 4.0), ("B:min", 4.0), ("G:maj", 4.0)],
    [("C:min7", 4.0), ("F:7", 4.0), ("Bb:maj7", 8.0)],
    [("E:maj", 1.0)],
    [("N", 3.0), ("C:maj", 1.0), ("N", 2.0)],
    [("N", 3.0)],
    [],
]
PATTERN_CASES = [
    {"numerals": ["I", "V", "vi", "IV"] * 4},
    {"numerals": ["vi", "IV", "I", "V"] * 3 + ["vi"]},
    {"numerals": ["I", "I", "V", "V", "vi", "IV"] * 3},
    {"numerals": ["i", "VII", "VI", "V"] * 3},
    {"numerals": ["ii", "V", "I", "ii", "V", "I", "ii", "V", "I", "vii°", "I"]},
    {"numerals": ["I", None, "V", "", "vi°", "IV", "I", "V", "vi", "IV", "I", "V", "vi", "IV"]},
    {"numerals": ["I", "bVII", "IV", "I"] * 3, "lengths": [4, 3, 2], "min_count": 2, "limit": 5},
    {"numerals": ["I", "IV"] * 5, "lengths": [2], "min_count": 2},
    {"numerals": []},
]


def _key_score_inputs() -> list[list[float]]:
    rng = Lcg(12)
    major = [1.0 if pc in (0, 2, 4, 5, 7, 9, 11) else 0.0 for pc in range(12)]
    minor = [1.0 if pc in (9, 11, 0, 2, 4, 5, 8) else 0.0 for pc in range(12)]
    noisy = [[round(rng.random() * 3, 6) for _ in range(12)] for _ in range(3)]
    return [major, minor, [0.0] * 12, *noisy]


def _parse_key(text: str) -> dict:
    key = theory.parse_key(text)
    return {"tonic": key.tonic, "mode": key.mode}


def theory_golden() -> dict:
    describe_labels = list(vocab.LARGE.labels) + DESCRIBE_EXTRA
    estimates = []
    for chords in ESTIMATE_CASES:
        key, confidence = theory.estimate_key_from_chords(chords)
        estimates.append({"chords": [[label, duration] for label, duration in chords],
                          "tonic": key.tonic, "mode": key.mode, "confidence": confidence})
    patterns = []
    for case in PATTERN_CASES:
        options = {k: v for k, v in case.items() if k != "numerals"}
        patterns.append({**case, "result": theory.find_patterns(case["numerals"], **options)})
    return {
        "keys": [{"tonic": k.tonic, "mode": k.mode, "flats": k.flats, "tonic_name": k.tonic_name, "name": k.name,
                  "scale": list(k.scale)} for k in theory.ALL_KEYS],
        "parse_key": [{"text": text, **_attempt(_parse_key, text)} for text in PARSE_KEY_TEXTS],
        "describe": [{"tonic": k.tonic, "mode": k.mode, "label": label, "diatonic": theory.is_diatonic(label, k),
                      **theory.describe(label, k)} for k in DESCRIBE_KEYS for label in describe_labels],
        "pairs": [{"tonic": k.tonic, "mode": k.mode, "labels": PAIR_LABELS,
                   "reason": [[theory.transition_reason(a, b, k) for b in PAIR_LABELS] for a in PAIR_LABELS],
                   "cadence": [[theory.cadence(a, b, k) for b in PAIR_LABELS] for a in PAIR_LABELS]}
                  for k in PAIR_KEYS],
        "key_scores": [{"chroma": chroma,
                        "scores": [[k.tonic, k.mode, score] for k, score in theory.key_scores(np.asarray(chroma))]}
                       for chroma in _key_score_inputs()],
        "estimate_key": estimates,
        "patterns": patterns,
    }


# ---------------------------------------------------------------------------
# lm
# ---------------------------------------------------------------------------

LM_CASES = [
    {"history": ["C:maj", "G:maj", "A:min", "F:maj", "C:maj", "G:maj", "A:min"], "key": [0, "major"],
     "beats": [4.0] * 7, "k": 3},
    {"history": ["D:maj", "A:7"], "key": [2, "major"], "beats": None, "k": 5},
    {"history": ["A:min", "G:maj", "F:maj", "E:maj"] * 3, "key": [9, "minor"], "beats": [2.0] * 12, "k": 3},
    {"history": ["E:maj"], "key": [4, "major"], "beats": None, "k": 3},
    {"history": ["Bb:maj7", "G:min7", "C:min7", "F:7"] * 2, "key": [10, "major"], "beats": [4.0, 2.0, 2.0, 4.0] * 2,
     "k": 4},
    {"history": ["C:maj", "N", "G:maj", "G:maj", "D:min"], "key": [0, "major"], "beats": None, "k": 3},
    {"history": ["F#:min", "D:maj", "A:maj", "E:maj"] * 2 + ["F#:min"], "key": [6, "minor"],
     "beats": [4.0, 0.0, 4.0, 2.0] * 2 + [4.0], "k": 3},
    {"history": [], "key": [0, "major"], "beats": None, "k": 3},
]
TOKEN_LABELS = ["C:maj", "A:min", "G:7", "B:dim", "F:sus4", "E:aug", "D:min7", "N", "X", "Bb:maj/3"]
TOKEN_KEYS = [Key(0, "major"), Key(9, "minor"), Key(3, "major"), Key(6, "minor")]


def lm_golden() -> dict:
    model = lm.NgramProgressionModel.load(LM_MODEL)
    cases = []
    for case in LM_CASES:
        key = Key(*case["key"])
        tokens = [lm.token(h, key) for h in case["history"] if vocab.parse(h).is_chord]
        cache, context = model.cache_counts(tokens)
        mixed, _ = model.distribution(tokens)
        predictions, prior = lm.predict_next(model, case["history"], key, k=case["k"], history_beats=case["beats"])
        cases.append({**case, "tokens": tokens, "corpus": model.corpus_distribution(tokens), "cache": dict(cache),
                      "cache_context": context, "mixed": mixed,
                      "top": [[c.token, c.p, c.cache_count] for c in model.top(tokens, case["k"])],
                      "predictions": [p.to_dict() for p in predictions], "prior": prior})
    return {
        "params": {"order": model.order, "min_count": model.min_count, "cache_weight": model.cache_weight,
                   "max_context": model.max_context},
        "token_list": list(lm.TOKENS),
        "tokens": [{"label": label, "tonic": k.tonic, "mode": k.mode, "token": lm.token(label, k)}
                   for label in TOKEN_LABELS for k in TOKEN_KEYS],
        "token_labels": [{"token": t, "tonic": k.tonic, "mode": k.mode, "label": lm.token_label(t, k)}
                         for t in lm.TOKENS for k in TOKEN_KEYS],
        "cases": cases,
        "change_matrix": [
            {"vocabulary": "majmin", "tonic": 3, "mode": "major",
             "matrix": model.change_matrix(vocab.MAJMIN, Key(3, "major")).tolist()},
            {"vocabulary": "sevenths", "tonic": 9, "mode": "minor",
             "matrix": model.change_matrix(vocab.SEVENTHS, Key(9, "minor")).tolist()},
        ],
    }


# ---------------------------------------------------------------------------
# decode
# ---------------------------------------------------------------------------

FPS = 44100 / 2048
NNLS_OFFSET = 16384 / 2 / 44100


def _truth(blocks: list[tuple[int, int]]) -> list[int]:
    return [cls for cls, frames in blocks for _ in range(frames)]


def _posteriors(truth: list[int], n_classes: int, rng: Lcg, flicker: float = 0.1) -> list[list[float]]:
    """Frame posteriors that favour the true class, with random flicker to another class."""
    rows = []
    for cls in truth:
        row = [0.02 + 0.3 * rng.random() for _ in range(n_classes)]
        row[cls] += 1.0
        if rng.random() < flicker:
            row[int(rng.random() * n_classes)] += 1.2
        total = sum(row)
        rows.append([round(v / total, 6) for v in row])
    return rows


def _change_prob(truth: list[int], rng: Lcg) -> list[float]:
    return [round(0.05 + 0.1 * rng.random() + (0.8 if t > 0 and truth[t] != truth[t - 1] else 0.0), 6)
            for t in range(len(truth))]


def _distribution(n: int, rng: Lcg) -> list[float]:
    raw = [0.1 + rng.random() for _ in range(n)]
    return [round(v / sum(raw), 6) for v in raw]


def _change_matrix(n: int, rng: Lcg) -> list[list[float]]:
    rows = []
    for i in range(n):
        raw = [0.0 if j == i else 0.05 + rng.random() for j in range(n)]
        rows.append([round(v / sum(raw), 6) for v in raw])
    return rows


UNIT_CASES = [
    (3.0, None, 10.0, 1),
    (2.05, [], 10.0, 1),
    (2.0, [1.0], 10.0, 1),
    (10.0, [1.0, 1.5, 2.0, 2.5, 3.0], 10.0, 1),
    (10.0, [0.0, 2.0, 2.5, 3.0, 5.5, 6.0, 6.5], FPS, 2),
    (8.0, [0.8, 1.3, 1.8, 2.3, 9.0], 10.0, 3),
    (7.3, [2.0, 2.5, 3.0, 3.5, 4.0, 4.0005], FPS, 1),
]


def decode_golden() -> dict:
    rng = Lcg(20261117)
    cases = []

    truth = _truth([(0, 30), (7, 25), (21, 40), (5, 30), (24, 15), (0, 60)])
    cases.append({"name": "frame units, default settings", "posteriors": _posteriors(truth, 25, rng),
                  "frame_rate": 10.0, "options": {}})

    truth = _truth([(0, 43), (7, 43), (21, 22), (17, 21), (5, 64), (24, 21), (0, 86)])
    cases.append({"name": "beats, boundary head, prior (server-like settings)",
                  "posteriors": _posteriors(truth, 25, rng), "frame_rate": FPS,
                  "options": {"beats": [round(0.3 + 0.5 * i, 6) for i in range(28)],
                              "change_prob": _change_prob(truth, rng), "prior": _distribution(25, rng),
                              "alpha": 0.3, "self_prob": 0.6, "boundary_weight": 1.0, "subdivide": 2,
                              "frame_offset": NNLS_OFFSET, "duration": 14.2}})

    truth = _truth([(24, 50), (2, 40), (9, 6), (2, 30), (14, 50), (19, 44)])
    beats = [2.0 + 0.5 * i for i in range(8)] + [7.5 + 0.5 * i for i in range(8)]
    cases.append({"name": "change matrix, intro, gap and fade-out without beats, short fragments merged",
                  "posteriors": _posteriors(truth, 25, rng, flicker=0.25), "frame_rate": FPS,
                  "options": {"beats": beats, "change_matrix": _change_matrix(25, rng), "min_units": 2,
                              "n_alternatives": 2, "frame_offset": NNLS_OFFSET}})

    truth = _truth([(0, 25), (36, 20), (51, 30), (19, 25), (60, 20), (40, 30)])
    cases.append({"name": "sevenths vocabulary, frame units, three-frame minimum",
                  "posteriors": _posteriors(truth, 61, rng, flicker=0.3), "frame_rate": 10.0,
                  "options": {"min_units": 3}})

    truth = _truth([(3, 40), (10, 40)])
    cases.append({"name": "one beat only falls back to frame units",
                  "posteriors": _posteriors(truth, 25, rng), "frame_rate": 10.0,
                  "options": {"beats": [2.0], "self_prob": 0.9, "alpha": 0.0}})

    for case in cases:
        options = {k: np.asarray(v) if isinstance(v, list) else v for k, v in case["options"].items()}
        segments = decode.decode(np.asarray(case["posteriors"]), case["frame_rate"], **options)
        case["segments"] = [[s.start, s.end, s.index, s.confidence, [[i, p] for i, p in s.alternatives]]
                            for s in segments]

    viterbi = []
    for n_steps, n_states in ((20, 5), (12, 3)):
        log_emissions = [[round(np.log(0.05 + rng.random()), 6) for _ in range(n_states)] for _ in range(n_steps)]
        stay = [round(0.3 + 0.6 * rng.random(), 6) for _ in range(n_steps)]
        matrix = _change_matrix(n_states, rng)
        path = decode.viterbi(np.asarray(log_emissions), np.asarray(stay), np.asarray(matrix))
        viterbi.append({"log_emissions": log_emissions, "stay": stay, "change_matrix": matrix, "path": path.tolist()})

    inversions = []
    for label in ("C:maj", "C:maj7", "G:7", "A:min", "N"):
        for boosted in (0, 4, 10):
            probs = [0.01 + 0.05 * rng.random() for _ in range(13)]
            probs[boosted] += 0.6
            probs = [round(p, 6) for p in probs]
            for threshold in (0.3, 0.5, 0.95):
                inversions.append({"label": label, "bass_probs": probs, "threshold": threshold,
                                   "result": decode.inversion(label, np.asarray(probs), threshold)})

    posteriors = [[round(rng.random(), 6) for _ in range(4)] for _ in range(10)]
    times = [round(0.05 + 0.1 * i, 6) for i in range(10)]
    windows = [(0.0, 0.3), (0.32, 0.34), (0.95, 2.0), (0.5, 0.5)]

    return {
        "decode": cases,
        "unit_boundaries": [{"duration": d, "beats": b, "frame_rate": fr, "subdivide": s,
                             "result": decode.unit_boundaries(d, None if b is None else np.asarray(b), fr, s).tolist()}
                            for d, b, fr, s in UNIT_CASES],
        "viterbi": viterbi,
        "combine_stay": [{"self_prob": sp, "p_change": p, "weight": w, "result": decode.combine_stay(sp, p, w)}
                         for sp in (0.6, 0.75, 0.97) for p in (1e-4, 0.3, 0.9999) for w in (0.0, 0.5, 1.0)],
        "inversion": inversions,
        "mean_over": {"posteriors": posteriors, "frame_times": times,
                      "windows": [{"start": a, "end": b,
                                   "result": decode.mean_over(np.asarray(posteriors), np.asarray(times), a, b).tolist()}
                                  for a, b in windows]},
    }


# ---------------------------------------------------------------------------
# features: decoding, resampling and NNLS Chroma (needs the Vamp plugin)
# ---------------------------------------------------------------------------

AUDIO_DIR = DEFAULT_OUT / "audio"
LOOP = [("C:maj", 2.0), ("G:maj", 2.0), ("A:min", 2.0), ("F:maj", 2.0)]
SEVENTHS = [("C:maj7", 2.0), ("A:min7", 2.0), ("D:min7", 2.0), ("G:7", 2.0)]
# One chord attack per beat, so the beat tracker has a pulse to find: 4/4 at 120 bpm (16 s) and
# 3/4 at 150 bpm (9.6 s), with the chord changing on every bar.
PULSE = [(label, 0.5) for label in ("C:maj", "G:maj", "A:min", "F:maj") for _ in range(8)]
WALTZ = [(label, 0.4) for label in ("C:maj", "G:maj", "A:min", "F:maj") * 2 for _ in range(3)]
# 16-bit WAV inputs, which every decoder reads exactly. Each one exercises one more step:
# plain 44.1 kHz; digital silence at both ends; resampling down; stereo and resampling up.
WAV_INPUTS = ["loop.wav", "loop_padded.wav", "loop_48k.wav", "sevenths_stereo_22k.wav"]
# Lossy copies of loop.wav. Decoders differ, so only their timing is compared (spec §7.1).
ENCODED_INPUTS = {"loop.mp3": ["-codec:a", "libmp3lame", "-b:a", "128k"],
                  "loop.m4a": ["-codec:a", "aac", "-b:a", "128k"]}
SLICE_START_S = 1.0
DECODED_SLICE = 1024
RESAMPLED_SLICE = 2048


def envelope_offset_ms(reference: np.ndarray, other: np.ndarray, sr: int) -> float:
    """How late ``other`` is against ``reference``, in ms: the lag (within ±200 ms) that best
    lines up their 5 ms RMS envelopes. The engine's EncodedAudioDeviceTest computes the same."""
    hop = int(round(sr * 0.005))

    def envelope(y: np.ndarray) -> np.ndarray:
        n = len(y) // hop
        e = np.sqrt((y[:n * hop].astype(np.float64).reshape(n, hop) ** 2).mean(axis=1))
        return e - e.mean()

    a, b = envelope(reference), envelope(other)
    best, best_score = 0, -np.inf
    for lag in range(-40, 41):
        x, y = (a, b[lag:]) if lag >= 0 else (a[-lag:], b)
        m = min(len(x), len(y))
        score = float(np.dot(x[:m], y[:m]))
        if score > best_score:
            best, best_score = lag, score
    return best * hop / sr * 1000


def render_audio(audio_dir: Path = AUDIO_DIR) -> None:
    """Write the audio inputs that are missing. They are committed and only rendered on purpose
    (--render-audio): the synthesiser's noise comes from numpy's random generator, so a render on
    another machine may differ, and committed inputs are never overwritten."""
    import subprocess

    import soundfile as sf

    from chordify_core import audio, synth

    audio_dir.mkdir(parents=True, exist_ok=True)

    def write(name: str, render) -> None:
        if not (audio_dir / name).exists():
            y, sr = render()
            sf.write(audio_dir / name, y, sr, subtype="PCM_16")

    def loop(sr: int) -> np.ndarray:
        return synth.render_progression(LOOP, sr=sr)[0]

    silence = np.zeros(int(1.5 * 44100), dtype=np.float32)
    write("loop.wav", lambda: (loop(44100), 44100))
    write("loop_padded.wav", lambda: (np.concatenate([silence, loop(44100), silence]), 44100))
    write("loop_48k.wav", lambda: (loop(48000), 48000))
    write("sevenths_stereo_22k.wav", lambda: (np.stack([synth.render_progression(SEVENTHS, sr=22050, seed=1)[0],
                                                        0.6 * synth.render_progression(SEVENTHS, sr=22050, seed=2)[0]],
                                                       axis=1), 22050))
    write("pulse.wav", lambda: (synth.render_progression(PULSE, sr=44100)[0], 44100))
    write("waltz.wav", lambda: (synth.render_progression(WALTZ, sr=44100)[0], 44100))
    for name, codec in ENCODED_INPUTS.items():
        if not (audio_dir / name).exists():
            subprocess.run([audio.ffmpeg_binary(), "-nostdin", "-loglevel", "error", "-y", "-i",
                            str(audio_dir / "loop.wav"), *codec, "-map_metadata", "-1", "-fflags", "+bitexact",
                            "-flags:a", "+bitexact", str(audio_dir / name)], check=True)


def _f32(values) -> list[float]:
    """float32 values as the shortest decimals that read back as the same float32."""
    return [float(f"{v:.9g}") for v in np.asarray(values, dtype=np.float32).ravel().tolist()]


def _f32_rows(matrix) -> list[list[float]]:
    return [_f32(row) for row in np.asarray(matrix, dtype=np.float32)]


def _environment() -> dict:
    """Versions that shape the features; recorded, not compared."""
    from importlib import metadata

    import soundfile as sf

    versions = {name: metadata.version(name) for name in ("numpy", "librosa", "soxr", "soundfile", "vamp")}
    return versions | {"libsndfile": sf.__libsndfile_version__}


def features_golden() -> dict:
    import soundfile as sf
    import vamp

    from chordify_core import audio, features

    if not features.nnls_available():
        raise SystemExit("features.json.gz needs the NNLS Chroma Vamp plugin: run tools/mobile_goldens.sh")
    spec = features.NNLS_BOTHCHROMA
    inputs = []
    for name in WAV_INPUTS:
        path = AUDIO_DIR / name
        native, sr = sf.read(str(path), dtype="float32", always_2d=False)
        mono = audio.to_mono(native)
        y44 = audio.load_audio(path.read_bytes(), spec.sample_rate, filename=name)  # the server's decode
        raw = features.nnls_bothchroma(y44, spec.sample_rate)
        start = int(SLICE_START_S * sr)
        row = {"file": name, "sample_rate": sr, "channels": 1 if native.ndim == 1 else native.shape[1],
               "frames": len(native), "decoded": {"start": start, "values": _f32(mono[start:start + DECODED_SLICE])},
               "samples": len(y44), "raw": _f32_rows(raw), "normalised": _f32_rows(features.normalise_bothchroma(raw))}
        if sr != spec.sample_rate:
            start = int(SLICE_START_S * spec.sample_rate)
            row["resampled"] = {"start": start, "values": _f32(y44[start:start + RESAMPLED_SLICE])}
        inputs.append(row)

    loop = audio.load_audio((AUDIO_DIR / "loop.wav").read_bytes(), spec.sample_rate, filename="loop.wav")
    for name in ENCODED_INPUTS:  # the encoded copies must line up with the WAV, as the device test assumes
        decoded = audio.load_audio((AUDIO_DIR / name).read_bytes(), spec.sample_rate, filename=name)
        offset = envelope_offset_ms(loop, decoded, spec.sample_rate)
        assert abs(offset) <= 5, f"Python decodes {name} {offset} ms off loop.wav"
    logfreqspec = vamp.collect(loop, spec.sample_rate, "nnls-chroma:nnls-chroma", output="logfreqspec",
                               block_size=features.NNLS_BLOCK, step_size=spec.hop)["matrix"][1]
    return {
        "environment": _environment(),
        "spec": spec.to_dict(),
        "frame_times": spec.frame_times(3).tolist(),
        "inputs": inputs,
        "encoded": [{"file": name, "reference": "loop.wav"} for name in ENCODED_INPUTS],
        "logfreqspec": {"file": "loop.wav", "values": _f32_rows(logfreqspec)},
    }


# ---------------------------------------------------------------------------
# beats: librosa's onset strength and dynamic-programming beat tracker
# ---------------------------------------------------------------------------

BEAT_INPUTS = WAV_INPUTS + ["pulse.wav", "waltz.wav"]
BEAT_SR = 22050  # chordify_backend.app.pipeline.beats.track_beats resamples to this, hop 512
BEAT_HOP = 512
ONSET_CASE_SECONDS = 2.0


def beats_golden() -> dict:
    import librosa

    from chordify_backend.app.pipeline.beats import track_beats
    from chordify_core import audio

    reason = missing("beats")
    if reason:
        raise SystemExit(f"beats.json.gz {reason}: run tools/mobile_goldens.sh")
    mel = librosa.filters.mel(sr=BEAT_SR, n_fft=2048, fmax=0.5 * BEAT_SR)  # onset_strength's filters
    rows, cols = np.nonzero(mel)
    rng = Lcg(5)
    frame = np.asarray([rng.random() * 2 - 1 for _ in range(2048)])
    spectrum = np.fft.rfft(frame)
    inputs = []
    for name in BEAT_INPUTS:
        y44 = audio.load_audio((AUDIO_DIR / name).read_bytes(), 44100, filename=name)
        y22 = librosa.resample(y44, orig_sr=44100, target_sr=BEAT_SR)
        onset = librosa.onset.onset_strength(y=y22, sr=BEAT_SR, hop_length=BEAT_HOP)
        tempo, frames = librosa.beat.beat_track(onset_envelope=onset, sr=BEAT_SR, hop_length=BEAT_HOP,
                                                units="frames", trim=False)
        tempogram = librosa.feature.tempogram(onset_envelope=onset, sr=BEAT_SR, hop_length=BEAT_HOP,
                                              win_length=344).mean(axis=1)
        info = track_beats(y44, 44100)
        inputs.append({"file": name, "samples_22k": len(y22), "onset": _f32(onset),
                       "tempogram": tempogram.tolist(), "tempo": float(np.atleast_1d(tempo)[0]),
                       "beat_frames": [int(f) for f in frames], "beats": info.beats.tolist(), "bpm": info.bpm,
                       "confidence": info.confidence, "usable": info.usable})
    pulse = audio.load_audio((AUDIO_DIR / "pulse.wav").read_bytes(), 44100, filename="pulse.wav")
    clip = librosa.resample(pulse, orig_sr=44100, target_sr=BEAT_SR)[:int(ONSET_CASE_SECONDS * BEAT_SR)]
    return {
        "environment": {name: _version(name) for name in ("numpy", "librosa", "soxr", "soundfile")},
        "mel": {"shape": list(mel.shape), "rows": rows.tolist(), "cols": cols.tolist(), "values": _f32(mel[rows, cols])},
        "fft": {"frame": frame.tolist(), "re": spectrum.real.tolist(), "im": spectrum.imag.tolist()},
        "onset_case": {"y": _f32(clip), "onset": _f32(librosa.onset.onset_strength(y=clip, sr=BEAT_SR, hop_length=BEAT_HOP))},
        "inputs": inputs,
    }


# ---------------------------------------------------------------------------
# acoustic: the shipped ChordNet with ONNX Runtime, on features.json.gz's raw features
# ---------------------------------------------------------------------------

ACOUSTIC_BUNDLE = REPO / "models" / "chordnet-chroma" / "3.0.0"
ACOUSTIC_TILES = 4  # loop.wav's 173 frames four times over: longer than one 512-frame window


def acoustic_golden() -> dict:
    from chordify_core import acoustic

    reason = missing("acoustic")
    if reason:
        raise SystemExit(f"acoustic.json.gz {reason}")
    model = acoustic.OnnxChordModel(ACOUSTIC_BUNDLE)
    loop = np.asarray(next(row["raw"] for row in read_golden(DEFAULT_OUT, "features")["inputs"]
                           if row["file"] == "loop.wav"), dtype=np.float32)
    cases = []
    for name, raw in (("loop.wav", loop), (f"loop.wav x{ACOUSTIC_TILES}", np.tile(loop, (ACOUSTIC_TILES, 1)))):
        logits = acoustic.windowed(model._run, acoustic.prepare(model.feature_spec, raw), model.window, model.margin)
        cases.append({"case": name, "tiles": 1 if name == "loop.wav" else ACOUSTIC_TILES, "frames": len(raw),
                      "logits": {head: _f32(v) if v.ndim == 1 else _f32_rows(v) for head, v in logits.items()}})
    first = cases[0]["logits"]
    softmax_in = np.asarray(first["chord"][:3], dtype=np.float32)
    sigmoid_in = np.asarray(first["boundary"][:3], dtype=np.float32)

    def fake(chunk: np.ndarray) -> dict:  # each window's output depends on where in the window a frame sits
        return {"a": chunk[:, :, :1] + np.arange(chunk.shape[1], dtype=np.float32)[None, :, None] / 1000}

    windows = [{"frames": n, "out": _f32(acoustic.windowed(fake, np.arange(n, dtype=np.float32)[:, None], 512, 64)["a"][:, 0])}
               for n in (300, 512, 1300)]
    return {"environment": {"numpy": _version("numpy"), "onnxruntime": _version("onnxruntime")},
            "bundle": model.id, "cases": cases, "windowed": windows,
            "softmax": {"logits": _f32_rows(softmax_in), "p": _f32_rows(acoustic.softmax(softmax_in))},
            "sigmoid": {"logits": _f32(sigmoid_in), "p": _f32(acoustic.sigmoid(sigmoid_in))}}


FILES = {"vocab": vocab_golden, "theory": theory_golden, "lm": lm_golden, "decode": decode_golden,
         "features": features_golden, "beats": beats_golden, "acoustic": acoustic_golden}
# The reference versions of what shapes a file; elsewhere it is skipped by default (and by the test).
PINNED = {"beats": ("librosa", "1.0.1"), "acoustic": ("onnxruntime", "1.30.0")}
GZIPPED = {"features", "beats", "acoustic"}  # large, so stored compressed


def _version(package: str) -> str | None:
    from importlib import metadata

    try:
        return metadata.version(package)
    except metadata.PackageNotFoundError:
        return None


def missing(name: str) -> str | None:
    """Why this machine cannot write golden file ``name`` as the reference does, or None."""
    if name == "features":
        from chordify_core.features import nnls_available

        return None if nnls_available() else "needs NNLS Chroma"
    if name in PINNED:
        package, version = PINNED[name]
        return None if _version(package) == version else f"needs {package}=={version}"
    return None


def golden_path(out: Path, name: str) -> Path:
    return out / (f"{name}.json.gz" if name in GZIPPED else f"{name}.json")


def read_golden(out: Path, name: str) -> dict:
    data = golden_path(out, name).read_bytes()
    return json.loads(gzip.decompress(data) if name in GZIPPED else data)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--only", choices=sorted(FILES), action="append")
    parser.add_argument("--render-audio", action="store_true",
                        help=f"render the audio inputs missing from {AUDIO_DIR} first")
    args = parser.parse_args(argv)
    if args.render_audio:
        render_audio()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    names = args.only
    if names is None:
        names = [n for n in FILES if missing(n) is None]
        for skipped in FILES.keys() - set(names):
            print(f"{golden_path(out, skipped).name}: skipped, {missing(skipped)} (tools/mobile_goldens.sh)")
    for name in names:
        text = json.dumps(FILES[name](), ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n"
        path = golden_path(out, name)
        if name in GZIPPED:
            path.write_bytes(gzip.compress(text.encode("utf-8"), mtime=0))
        else:
            path.write_text(text, encoding="utf-8")
        print(f"{path.name}: {path.stat().st_size / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
