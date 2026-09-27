"""v1 endpoints kept for the current web and Android clients (plan 05 §8).

They now run on the v2 pipeline (shared feature extraction and the active chord
model) instead of the v1 Keras model, which fixes the train/serve skew from the audit.
Responses keep the v1 shapes and carry Deprecation/Sunset headers.
"""
from __future__ import annotations

import io
import json

import numpy as np
from fastapi import APIRouter, File, Form, Request, Response, UploadFile
from fastapi.responses import JSONResponse

from chordify_core import features

from ..audio_io import decode, read_upload
from ..errors import ApiError
from ..pipeline.analysis import single_chord
from ..schemas import LegacyChordPrediction, LegacyTimeStamp

router = APIRouter(tags=["v1 (deprecated)"])
DEPRECATION = {"Deprecation": "true", "Sunset": "Wed, 30 Jun 2027 00:00:00 GMT",
               "Link": '</api/v2/analyses>; rel="successor-version"'}


async def _load(request: Request, file: UploadFile) -> np.ndarray:
    settings = request.app.state.settings
    return decode(await read_upload(file, settings.max_upload_bytes), settings.decode_sample_rate)


def _seconds(value: str | float) -> float:
    text = str(value).strip()
    return float(text[:-1] if text.endswith("s") else text)


@router.post("/predict", response_model=LegacyChordPrediction)
async def predict(request: Request, file: UploadFile = File(...)):
    """One chord for a short clip (v1 shape: confidence in percent)."""
    y = await _load(request, file)
    label, confidence = single_chord(request.app.state.models, y, request.app.state.settings.decode_sample_rate)
    return JSONResponse({"chord": label, "confidence": round(confidence * 100, 4)}, headers=DEPRECATION)


@router.post("/predict_time_stamps")
async def predict_time_stamps(request: Request, file: UploadFile = File(...), timestamps: str = Form(...)):
    """One chord per user-supplied slice (v1 shape)."""
    try:
        slices = [LegacyTimeStamp(**item) for item in json.loads(timestamps)]
        spans = [(_seconds(s.start), _seconds(s.end)) for s in slices]
    except (ValueError, TypeError) as err:
        raise ApiError(400, "INVALID_REQUEST", f"Invalid timestamps format: {err}") from err
    y = await _load(request, file)
    sr = request.app.state.settings.decode_sample_rate
    segments = []
    for item, (start, end) in zip(slices, spans):
        if end <= start:
            raise ApiError(400, "INVALID_REQUEST", f"end must be greater than start for {item.model_dump()}")
        piece = y[max(0, int(start * sr)):min(len(y), int(end * sr))]
        if piece.size < sr // 4:
            raise ApiError(400, "INVALID_REQUEST", f"slice {item.start}-{item.end} is outside the audio or too short")
        label, confidence = single_chord(request.app.state.models, piece, sr)
        segments.append({"start": item.start, "end": item.end, "chord": label, "confidence": round(confidence * 100, 4)})
    return JSONResponse({"segments": segments}, headers=DEPRECATION)


@router.post("/extract_full_chroma")
async def extract_full_chroma(request: Request, file: UploadFile = File(...)):
    """Chromagram PNG, rendered in memory (v1 wrote a file per request and never deleted it)."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    y = await _load(request, file)
    spec = request.app.state.models.acoustic.feature_spec
    raw = features.extract(spec, y, request.app.state.settings.decode_sample_rate)
    treble = features.normalise_bothchroma(raw)[:, 12:24].T if spec.kind.endswith("bothchroma") else raw.T
    fig, ax = plt.subplots(figsize=(14, 5), dpi=100)
    ax.imshow(treble, aspect="auto", origin="lower", cmap="magma", interpolation="nearest",
              extent=(0, raw.shape[0] / spec.frame_rate, -0.5, treble.shape[0] - 0.5))
    if treble.shape[0] == 12:
        ax.set_yticks(range(12), ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"])
    ax.set_xlabel("Time (s)")
    ax.set_title("Chromagram")
    fig.tight_layout()
    buffer = io.BytesIO()
    fig.savefig(buffer, format="png")
    plt.close(fig)
    return Response(buffer.getvalue(), media_type="image/png",
                    headers={**DEPRECATION, "Content-Disposition": 'inline; filename="chroma_visualization.png"'})
