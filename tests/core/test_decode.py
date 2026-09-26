import numpy as np
import pytest

from chordify_core import decode
from chordify_core.vocab import MAJMIN

FPS = 44100 / 2048


def noisy_posteriors(sequence, seconds_each, noise=0.35, flicker=0.15, seed=0):
    """Frame posteriors that mostly favour the true chord but flicker to random others."""
    rng = np.random.default_rng(seed)
    rows = []
    for label in sequence:
        n = int(round(seconds_each * FPS))
        truth = MAJMIN.encode(label)
        for _ in range(n):
            p = rng.random(MAJMIN.size) * noise
            winner = rng.integers(MAJMIN.size) if rng.random() < flicker else truth
            p[winner] += 1.0
            rows.append(p / p.sum())
    return np.asarray(rows)


def labels(segments):
    return [MAJMIN.decode(s.index) for s in segments]


def test_frame_level_decoding_removes_flicker():
    seq = ["C:maj", "G:maj", "A:min", "F:maj"]
    post = noisy_posteriors(seq, 2.0)
    raw_changes = np.count_nonzero(np.diff(post.argmax(axis=1)))
    segments = decode.decode(post, FPS)
    assert labels(segments) == seq
    assert raw_changes > 20  # the argmax flickers a lot
    for seg, expected_start in zip(segments, [0, 2, 4, 6]):
        assert seg.start == pytest.approx(expected_start, abs=0.15)


def test_beat_synchronous_boundaries_land_on_beats():
    seq = ["C:maj", "G:maj", "A:min", "F:maj", "C:maj"]
    post = noisy_posteriors(seq, 2.0)  # 120 BPM: 4 beats per chord
    beats = np.arange(0, 10.0, 0.5)
    segments = decode.decode(post, FPS, beats=beats)
    assert labels(segments) == seq
    for seg in segments[1:]:
        assert np.min(np.abs(beats - seg.start)) < 1e-9  # exactly on a beat
    assert segments[-1].end == pytest.approx(len(post) / FPS)


def test_segments_report_confidence_and_alternatives():
    post = noisy_posteriors(["A:min"], 3.0, noise=0.02, flicker=0.0)
    (seg,) = decode.decode(post, FPS)
    assert 0.5 < seg.confidence <= 1.0
    assert len(seg.alternatives) == 3
    assert all(idx != seg.index and p < seg.confidence for idx, p in seg.alternatives)


def test_change_matrix_resolves_ambiguous_evidence():
    # Second chord is a coin-flip between E:min and G:maj; the transition prior prefers G after C.
    c, g, e = MAJMIN.encode("C:maj"), MAJMIN.encode("G:maj"), MAJMIN.encode("E:min")
    n = int(2 * FPS)
    post = np.full((2 * n, MAJMIN.size), 0.01)
    post[:n, c] = 1.0
    post[n:, g] = 0.5
    post[n:, e] = 0.5
    post /= post.sum(axis=1, keepdims=True)
    matrix = decode.uniform_change_matrix(MAJMIN.size)
    matrix[c, g] *= 5.0
    matrix /= matrix.sum(axis=1, keepdims=True)
    assert labels(decode.decode(post, FPS, change_matrix=matrix)) == ["C:maj", "G:maj"]
    matrix[c, g] /= 25.0
    matrix /= matrix.sum(axis=1, keepdims=True)
    assert labels(decode.decode(post, FPS, change_matrix=matrix)) == ["C:maj", "E:min"]


def test_prior_scaling_stops_the_common_class_from_winning():
    c, cm = MAJMIN.encode("C:maj"), MAJMIN.encode("C:min")
    post = np.full((50, MAJMIN.size), 0.01)
    post[:, c], post[:, cm] = 0.45, 0.40
    post /= post.sum(axis=1, keepdims=True)
    prior = np.full(MAJMIN.size, 0.01)
    prior[c], prior[cm] = 0.5, 0.05
    prior /= prior.sum()
    assert labels(decode.decode(post, FPS)) == ["C:maj"]
    assert labels(decode.decode(post, FPS, prior=prior, alpha=0.5)) == ["C:min"]


def test_boundary_head_allows_short_chords():
    # A one-beat chord that the default self-transition swallows.
    beats = np.arange(0, 4.0, 0.5)
    post = noisy_posteriors(["C:maj"], 4.0, noise=0.05, flicker=0.0, seed=1)
    d = MAJMIN.encode("D:min")
    lo, hi = int(2.0 * FPS), int(2.5 * FPS)
    post[lo:hi] = 0.01
    post[lo:hi, d] = 0.8
    post[lo:hi] /= post[lo:hi].sum(axis=1, keepdims=True)
    change = np.full(len(post), 0.01)
    change[[lo, hi]] = 0.99
    assert labels(decode.decode(post, FPS, beats=beats)) == ["C:maj"]
    with_head = decode.decode(post, FPS, beats=beats, change_prob=change)
    assert labels(with_head) == ["C:maj", "D:min", "C:maj"]
    assert with_head[1].start == 2.0 and with_head[1].end == 2.5


def test_combine_stay():
    assert decode.combine_stay(0.75, 0.5, 0.0) == pytest.approx(0.75)
    assert decode.combine_stay(0.75, 0.99, 1.0) == pytest.approx(0.01)
    assert decode.combine_stay(0.75, 0.99, 0.5) < 0.2 < 0.9 < decode.combine_stay(0.75, 0.01, 0.5)


def test_merge_short_absorbs_fragments():
    beats = np.arange(0, 6.0, 0.5)
    post = noisy_posteriors(["G:maj", "E:min"], 3.0, flicker=0.0)
    lo = 22  # frames 22-26 have their centres in the half-beat unit [1.0 s, 1.25 s)
    post[lo:lo + 5] = 0.0
    post[lo:lo + 5, MAJMIN.encode("B:min")] = 1.0  # half-beat blip
    segments = decode.decode(post, FPS, beats=beats, subdivide=2, self_prob=0.3, min_units=2)
    assert labels(segments) == ["G:maj", "E:min"]


def test_unit_boundaries_cover_the_whole_song():
    b = decode.unit_boundaries(10.0, np.array([0.7, 1.2, 1.7, 9.2]), FPS)
    assert b[0] == 0.0 and b[-1] == 10.0 and np.all(np.diff(b) > 0)
    b2 = decode.unit_boundaries(2.0, np.array([0.5, 1.0, 1.5]), FPS, subdivide=2)
    assert b2.tolist() == [0.0, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0]
