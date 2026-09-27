import io

import numpy as np
import pytest

from chordify_core import audio, features, synth
from chordify_core.features import CQT_BOTHCHROMA, LOG_CQT, NNLS_BOTHCHROMA

pytest.importorskip("librosa")

C, E, G, A = 0, 4, 7, 9
nnls = pytest.mark.skipif(not features.nnls_available(), reason="nnls-chroma Vamp plugin not installed")


def sustained(chords, sr):
    """Chords whose notes barely decay, so a change is visible right up to its boundary."""
    return np.concatenate([synth.render_chord(label, seconds, sr, decay=0.1) for label, seconds in chords])


def top(vector, k=3):
    return set(np.argsort(vector)[::-1][:k].tolist())


def middle(frames):
    return frames[len(frames) // 2]


@nnls
@pytest.mark.parametrize("input_sr", [22050, 44100, 48000])
def test_nnls_frame_rate_is_billboards_whatever_the_input_rate(input_sr):
    """Golden test for finding S1: 100 frames must span 4.64 s, as in Billboard's bothchroma."""
    y, _ = synth.render_progression([("C:maj", 10.0)], sr=input_sr)
    frames = features.extract(NNLS_BOTHCHROMA, y, input_sr)
    assert frames.shape[1] == 24
    assert abs(frames.shape[0] - 10.0 * NNLS_BOTHCHROMA.frame_rate) <= 2
    assert 100 / NNLS_BOTHCHROMA.frame_rate == pytest.approx(4.644, abs=1e-3)


@nnls
def test_nnls_is_c_ordered_bass_then_treble():
    y, _ = synth.render_progression([("A:min", 6.0)], sr=44100)
    frame = middle(features.extract(NNLS_BOTHCHROMA, y, 44100))
    assert top(frame[12:]) == {A, 0, E}  # A C E
    assert np.argmax(frame[:12]) == A


def test_nnls_to_c_order_rolls_both_halves():
    a_based = np.zeros((1, 24), dtype=np.float32)
    a_based[0, 3] = 1.0  # bass bin 3 = C
    a_based[0, 12 + 0] = 1.0  # treble bin 0 = A
    rolled = features.nnls_to_c_order(a_based)
    assert rolled[0, C] == 1.0 and rolled[0, 12 + A] == 1.0


def test_billboard_csv_loader(tmp_path):
    row = ["/songs/x.wav", "0.0464"] + ["0"] * 24
    row[2 + 3] = "2.5"  # bass C
    row[2 + 12 + 7] = "1.0"  # treble E
    path = tmp_path / "bothchroma.csv"
    path.write_text(",".join(row) + "\n")
    times, frames = features.load_billboard_bothchroma(path)
    assert times.tolist() == [pytest.approx(0.0464 + NNLS_BOTHCHROMA.offset)]  # block start -> centre
    assert frames[0, C] == 2.5 and frames[0, 12 + E] == 1.0


@pytest.mark.parametrize("label, expected_treble, expected_bass", [
    ("C:maj", {C, E, G}, C),
    ("A:min", {A, C, E}, A),
    ("F:maj", {5, A, C}, 5),
    # G/B: the bass's harmonics (B, F#) leak into the treble, as they do with real
    # instruments; only NNLS's transcription step removes them, so check the bass only.
    ("G:maj/3", None, 11),
])
def test_cqt_bothchroma_finds_chord_tones(label, expected_treble, expected_bass):
    y, _ = synth.render_progression([(label, 4.0)], sr=22050)
    frames = features.extract(CQT_BOTHCHROMA, y, 22050)
    assert abs(frames.shape[0] - 4.0 * CQT_BOTHCHROMA.frame_rate) <= 2
    frame = middle(frames)
    if expected_treble is not None:
        assert top(frame[12:]) == expected_treble
    assert np.argmax(frame[:12]) == expected_bass


@pytest.mark.parametrize("cents", [-45, -30, -17, 17, 30, 45])
def test_cqt_bothchroma_survives_detuned_recordings(cents):
    y = synth.render_chord("A:min", 3.0, sr=22050, detune_cents=cents)
    frame = middle(features.extract(CQT_BOTHCHROMA, y, 22050))
    assert top(frame[12:]) == {A, C, E}
    assert np.argmax(frame[:12]) == A


def test_both_chroma_kinds_share_the_frame_rate():
    assert CQT_BOTHCHROMA.frame_rate == pytest.approx(NNLS_BOTHCHROMA.frame_rate)
    assert LOG_CQT.frame_rate == pytest.approx(NNLS_BOTHCHROMA.frame_rate)


def test_log_cqt_shape():
    y, _ = synth.render_progression([("C:maj", 3.0)], sr=22050)
    frames = features.extract(LOG_CQT, y, 22050)
    assert frames.shape[1] == 288 and frames.min() >= 0


def test_normalise_bothchroma():
    y, _ = synth.render_progression([("N", 2.0), ("C:maj", 2.0)], sr=22050)
    raw = features.extract(CQT_BOTHCHROMA, y, 22050)
    x = features.normalise_bothchroma(raw)
    assert x.shape == (raw.shape[0], 25)
    sounding = x[len(x) * 3 // 4]
    assert sounding[:12].max() == pytest.approx(1.0) and sounding[12:24].max() == pytest.approx(1.0)
    silent, loud = x[len(x) // 8, 24], sounding[24]
    assert silent < loud - 1.0  # energy channel separates silence from a chord
    assert features.normalise_bothchroma(raw * 37.0) == pytest.approx(x, abs=1e-4)  # gain invariant


def test_transposing_audio_matches_rolling_features():
    y_c, _ = synth.render_progression([("C:maj", 3.0)], sr=22050)
    y_d, _ = synth.render_progression([("D:maj", 3.0)], sr=22050)
    fc = middle(features.normalise_bothchroma(features.extract(CQT_BOTHCHROMA, y_c, 22050)))
    fd = middle(features.normalise_bothchroma(features.extract(CQT_BOTHCHROMA, y_d, 22050)))
    rolled = features.transpose_chroma(fc[None, :], 2)[0]
    assert top(rolled[12:24]) == top(fd[12:24]) == {2, 6, 9}
    assert rolled[24] == fc[24]  # energy channel untouched


def test_feature_spec_roundtrip():
    for spec in (NNLS_BOTHCHROMA, CQT_BOTHCHROMA, LOG_CQT):
        assert features.FeatureSpec.from_dict(spec.to_dict()) == spec


def test_models_trained_on_an_older_feature_revision_are_refused():
    legacy = {k: v for k, v in CQT_BOTHCHROMA.to_dict().items() if k != "revision"}  # pre-revision bundle.json
    spec = features.FeatureSpec.from_dict(legacy)
    assert spec.revision == 1
    with pytest.raises(ValueError, match="revision 1 was requested"):
        features.extract(spec, np.zeros(22050, dtype=np.float32), 22050)


def test_load_audio_resamples_wav_bytes():
    import soundfile as sf

    y, _ = synth.render_progression([("C:maj", 1.0)], sr=44100)
    buffer = io.BytesIO()
    sf.write(buffer, y, 44100, format="WAV")
    out = audio.load_audio(buffer.getvalue(), 22050, filename="clip.wav")
    assert abs(len(out) - 22050) <= 2 and out.dtype == np.float32


@nnls
def test_nnls_frame_times_match_the_audio():
    """Vamp stamps NNLS frames at block centres (frame 0 = 0.186 s); spec.frame_times must agree,
    or every chord boundary lands early (found via the backend's beat-level decoding)."""
    y = sustained([("C:maj", 4.0), ("F#:maj", 4.0)], 44100)
    frames = features.extract(NNLS_BOTHCHROMA, y, 44100)[:, 12:]
    change = np.argmax(frames[:, [6, 10, 1]].sum(1) > frames[:, [0, 4, 7]].sum(1))
    assert NNLS_BOTHCHROMA.frame_times(len(frames))[change] == pytest.approx(4.0, abs=0.2)
    assert NNLS_BOTHCHROMA.offset == pytest.approx(0.1858, abs=1e-4)


def test_cqt_frame_times_match_the_audio():
    y = sustained([("C:maj", 4.0), ("F#:maj", 4.0)], 22050)
    frames = features.extract(CQT_BOTHCHROMA, y, 22050)[:, 12:]
    change = np.argmax(frames[:, [6, 10, 1]].sum(1) > frames[:, [0, 4, 7]].sum(1))
    assert CQT_BOTHCHROMA.frame_times(len(frames))[change] == pytest.approx(4.0, abs=0.25)
