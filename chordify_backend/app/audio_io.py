"""Upload intake: size limit, format sniffing by magic bytes (not file extension), decoding."""
from __future__ import annotations

import numpy as np
from fastapi import UploadFile

from chordify_core.audio import AudioDecodeError, load_audio

from .errors import ApiError

_CHUNK = 1 << 20


def sniff_format(head: bytes) -> str | None:
    """Container from the first bytes, or None if this does not look like audio."""
    if head[:4] == b"RIFF" and head[8:12] == b"WAVE":
        return "wav"
    if head[:4] == b"fLaC":
        return "flac"
    if head[:4] == b"OggS":
        return "ogg"
    if head[:3] == b"ID3" or (len(head) > 1 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0):
        return "mp3"
    if head[4:8] == b"ftyp":
        return "m4a"
    if head[:4] == b"\x1a\x45\xdf\xa3":
        return "webm"
    if head[:4] in (b"FORM",) and head[8:12] in (b"AIFF", b"AIFC"):
        return "aiff"
    return None


async def read_upload(file: UploadFile, max_bytes: int) -> bytes:
    chunks, size = [], 0
    while chunk := await file.read(_CHUNK):
        size += len(chunk)
        if size > max_bytes:
            raise ApiError(413, "PAYLOAD_TOO_LARGE", f"The upload exceeds {max_bytes // (1024 * 1024)} MB.")
        chunks.append(chunk)
    return b"".join(chunks)


def decode(data: bytes, sample_rate: int) -> np.ndarray:
    kind = sniff_format(data[:16])
    if kind is None:
        raise ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "The file is not a supported audio format "
                            "(wav, flac, ogg, mp3, m4a/aac, webm, aiff).")
    try:
        y = load_audio(data, sample_rate, filename=f"upload.{kind}")
    except AudioDecodeError as err:
        raise ApiError(422, "DECODE_FAILED", f"Could not decode the {kind} file: {err}") from err
    if y.size == 0:
        raise ApiError(422, "DECODE_FAILED", "The file contains no audio samples.")
    return y


def check_duration(y: np.ndarray, sample_rate: int, min_s: float, max_s: float) -> float:
    duration = len(y) / sample_rate
    if duration > max_s:
        raise ApiError(422, "AUDIO_TOO_LONG", f"The audio is {duration / 60:.1f} min long; the limit is "
                                              f"{max_s / 60:.0f} min.")
    if duration < min_s:
        raise ApiError(422, "AUDIO_TOO_SHORT", f"The audio is {duration:.2f} s long; at least {min_s:.0f} s is needed.")
    return duration
