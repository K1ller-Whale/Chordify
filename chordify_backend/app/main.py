"""Chordify API.

    pip install -e ".[audio]" -r chordify_backend/requirements.txt
    uvicorn chordify_backend.app.main:app --reload --port 8000
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import errors
from .api import analyses, progressions, system, v1_legacy
from .config import Settings, get_settings
from .jobs import JobManager
from .pipeline.models import Models


def warm_up(models: Models) -> None:
    from chordify_core import synth

    from .pipeline import analysis

    y, _ = synth.render_progression([("C:maj", 2.0), ("G:maj", 2.0), ("A:min", 2.0), ("F:maj", 2.0)], sr=44100)
    analysis.analyse(y, 44100, models=models, analysis_id="warmup")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.settings = settings
        app.state.models = Models.load(settings)
        app.state.jobs = JobManager(settings, app.state.models)
        if settings.warmup:
            warm_up(app.state.models)
        logging.getLogger("chordify").info("models loaded: %s", app.state.models.ids)
        yield
        app.state.jobs.shutdown()

    app = FastAPI(title="Chordify API", version="2.0.0", lifespan=lifespan,
                  description="Chord timelines, keys and next-chord predictions. See docs/chord-progression-plan.")
    app.add_middleware(CORSMiddleware, allow_origins=settings.allowed_origins, allow_credentials=False,
                       allow_methods=["GET", "POST", "DELETE"], allow_headers=["*"],
                       expose_headers=["Location", "ETag", "Deprecation", "Sunset"])
    errors.install(app)
    for router in (system.router, analyses.router, progressions.router, v1_legacy.router):
        app.include_router(router)
    return app


app = create_app()
