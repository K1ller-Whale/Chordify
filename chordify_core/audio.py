"""Audio decoding and resampling shared by training and serving."""
from __future__ import annotations

import io
import os
import shutil
import subprocess

import numpy as np

# Formats libsndfile can decode directly from memory.
_SOUNDFILE_FORMATS = (".wav", ".flac", ".ogg", ".oga", ".aiff", ".aif", ".mp3")


class AudioDecodeError(ValueError):
    pass


def ffmpeg_binary() -> str | None:
    return os.environ.get("CHORDIFY_FFMPEG") or shutil.which("ffmpeg")


def _decode_ffmpeg(data: bytes, sr: int) -> np.ndarray:
    binary = ffmpeg_binary()
    if binary is None:
        raise AudioDecodeError("ffmpeg is required to decode this format but was not found")
    proc = subprocess.run(
        [binary, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", "pipe:0",
         "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "1", "-ar", str(sr), "pipe:1"],
        input=data, capture_output=True, timeout=120, check=False,
    )
    if proc.returncode != 0 or not proc.stdout:
        raise AudioDecodeError(proc.stderr.decode(errors="replace").strip() or "ffmpeg could not decode the file")
    return np.frombuffer(proc.stdout, dtype="<f4").astype(np.float32)


def resample(y: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    if sr_in == sr_out:
        return y.astype(np.float32, copy=False)
    import librosa

    return librosa.resample(y.astype(np.float32, copy=False), orig_sr=sr_in, target_sr=sr_out).astype(np.float32)


def to_mono(y: np.ndarray) -> np.ndarray:
    return y if y.ndim == 1 else y.mean(axis=1)


def load_audio(source: str | os.PathLike | bytes, sr: int, filename: str | None = None) -> np.ndarray:
    """Decode a file path or raw bytes to mono float32 at ``sr``.

    libsndfile handles wav/flac/ogg/mp3; everything else (m4a, webm, aac…) goes through ffmpeg.
    """
    name = (filename or (str(source) if not isinstance(source, bytes) else "")).lower()
    data = source if isinstance(source, bytes) else None
    if name.endswith(_SOUNDFILE_FORMATS) or not name:
        import soundfile as sf

        try:
            y, file_sr = sf.read(io.BytesIO(data) if data is not None else source, dtype="float32", always_2d=False)
            return resample(to_mono(y), file_sr, sr)
        except Exception as err:  # fall through to ffmpeg for mislabelled files
            if ffmpeg_binary() is None:
                raise AudioDecodeError(str(err)) from err
    if data is None:
        with open(source, "rb") as handle:
            data = handle.read()
    return _decode_ffmpeg(data, sr)
