"""POST /api/v2/progressions/next: next-chord suggestions for a typed progression (plan 05 §6)."""
from __future__ import annotations

from fastapi import APIRouter, Request

from chordify_core import lm, theory, vocab

from ..errors import ApiError
from ..schemas import ProgressionRequest, ProgressionResponse

router = APIRouter(prefix="/api/v2/progressions", tags=["progressions"])


@router.post("/next", response_model=ProgressionResponse)
def next_chords(body: ProgressionRequest, request: Request):
    models = request.app.state.models
    try:
        labels = [vocab.parse_display(name) for name in body.chords]
    except ValueError as err:
        raise ApiError(422, "INVALID_REQUEST", str(err)) from err
    if body.durations_beats is not None and len(body.durations_beats) != len(labels):
        raise ApiError(422, "INVALID_REQUEST", "durations_beats must have one entry per chord.")
    beats = body.durations_beats or [4.0] * len(labels)
    if body.key:
        try:
            key, confidence, estimated = theory.parse_key(body.key), 1.0, False
        except ValueError as err:
            raise ApiError(422, "INVALID_REQUEST", str(err)) from err
    else:
        key, confidence = theory.estimate_key_from_chords(list(zip(labels, beats)))
        estimated = True
    real = [(label, b) for label, b in zip(labels, beats) if vocab.parse(label).is_chord]
    if not real:
        raise ApiError(422, "INVALID_REQUEST", "At least one chord (not N.C.) is needed.")
    predictions, prior = lm.predict_next(models.lm, [label for label, _ in real], key, k=body.k,
                                         history_beats=[b for _, b in real])
    return {"key_used": {**key.to_dict(), "confidence": round(confidence, 4), "estimated": estimated},
            "predictions": [p.to_dict() for p in predictions], "theory_only": prior, "model": models.lm_id}
