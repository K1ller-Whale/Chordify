"""Health, readiness and model information."""
from __future__ import annotations

from fastapi import APIRouter, Request

from chordify_core import features

router = APIRouter(tags=["system"])


@router.get("/healthz")
def healthz():
    return {"status": "ok"}


@router.get("/readyz")
def readyz(request: Request):
    return {"status": "ready", "models": request.app.state.models.ids}


@router.get("/api/v2/models")
def models_info(request: Request):
    models, settings = request.app.state.models, request.app.state.settings
    spec = models.acoustic.feature_spec
    return {
        "models": models.ids,
        "vocabularies": [models.acoustic.vocabulary.name],
        "features": spec.to_dict(),
        "nnls_plugin_available": features.nnls_available(),
        "limits": {"max_upload_mb": settings.max_upload_mb, "max_duration_s": settings.max_duration_s,
                   "min_duration_s": settings.min_duration_s},
    }
