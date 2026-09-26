"""/api/v2/analyses: upload, status, progress (SSE), result, export, corrections (plan 05 §2-§5)."""
from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

from fastapi import APIRouter, File, Form, Header, Request, Response, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse, StreamingResponse
from pydantic import ValidationError

from ..audio_io import read_upload, sniff_format
from ..errors import ApiError
from ..jobs import TERMINAL, Job, JobManager
from ..schemas import AnalysisOptions, AnalysisResult, AnalysisStatus, Correction

router = APIRouter(prefix="/api/v2/analyses", tags=["analyses"])
HEARTBEAT_S = 15.0


def _manager(request: Request) -> JobManager:
    return request.app.state.jobs


def _status(job: Job) -> AnalysisStatus:
    base = f"/api/v2/analyses/{job.id}"
    return AnalysisStatus(id=job.id, status=job.status, stage=job.stage, progress=round(job.progress, 3),
                          filename=job.filename, created_at=job.created_at, error=job.error,
                          links={"self": base, "events": f"{base}/events", "result": f"{base}/result"})


@router.post("", response_model=AnalysisStatus, status_code=202,
             responses={200: {"model": AnalysisStatus, "description": "Cache hit: this audio was already analysed"}})
async def create_analysis(request: Request, file: UploadFile = File(...), options: str | None = Form(None)):
    """Upload audio (multipart ``file`` + optional ``options`` JSON) and start an analysis."""
    manager = _manager(request)
    try:
        parsed = AnalysisOptions.model_validate_json(options) if options else AnalysisOptions()
    except ValidationError as err:
        raise ApiError(422, "INVALID_REQUEST", f"options: {err.errors()[0]['msg']}") from err
    vocabulary = manager.models.acoustic.vocabulary.name
    if parsed.vocabulary != vocabulary:
        raise ApiError(422, "OPTION_UNAVAILABLE", f"The active chord model supports the '{vocabulary}' vocabulary.")
    data = await read_upload(file, manager.settings.max_upload_bytes)
    if sniff_format(data[:16]) is None:
        raise ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "The file is not a supported audio format "
                                                      "(wav, flac, ogg, mp3, m4a/aac, webm, aiff).")
    job, cached = manager.submit(data, file.filename, parsed.model_dump())
    status = _status(job)
    return JSONResponse(status.model_dump(), status_code=200 if cached else 202,
                        headers={"Location": status.links.self})


@router.get("", response_model=list[AnalysisStatus])
def list_analyses(request: Request):
    return [_status(job) for job in _manager(request).list()]


@router.get("/{analysis_id}", response_model=AnalysisStatus)
def get_analysis(analysis_id: str, request: Request, if_none_match: str | None = Header(None)):
    status = _status(_manager(request).get(analysis_id))
    etag = f'"{status.status}-{status.stage}-{status.progress}"'
    if if_none_match == etag:
        return Response(status_code=304, headers={"ETag": etag})
    return JSONResponse(status.model_dump(), headers={"ETag": etag})


@router.get("/{analysis_id}/result", response_model=AnalysisResult)
def get_result(analysis_id: str, request: Request):
    manager = _manager(request)
    result = manager.result(analysis_id)
    etag = f'"{manager.get(analysis_id).cache_key.split(":")[0][:16]}-{manager.models.fingerprint}"'
    return JSONResponse(result, headers={"ETag": etag, "Cache-Control": "private, max-age=31536000, immutable"})


@router.get("/{analysis_id}/events")
async def stream_events(analysis_id: str, request: Request, last_event_id: str | None = Header(None)):
    """Server-Sent Events: ``snapshot`` first, then ``progress``/``partial`` and a final
    ``completed``/``failed``/``cancelled``. Resumable with ``Last-Event-ID``."""
    manager = _manager(request)
    job = manager.get(analysis_id)
    start = int(last_event_id) if last_event_id and last_event_id.isdigit() else 0

    def fmt(event: str, data: dict, event_id: int | None = None) -> str:
        head = f"id: {event_id}\n" if event_id is not None else ""
        return f"{head}event: {event}\ndata: {json.dumps(data)}\n\n"

    async def generator():
        last = start
        yield fmt("snapshot", job.snapshot(), len(job.events))
        heartbeat = time.monotonic()
        while True:
            if await request.is_disconnected():
                return
            events, status = manager.events_after(analysis_id, last)
            for event in events:
                last = event["id"]
                yield fmt(event["event"], event["data"], event["id"])
                if event["event"] in TERMINAL:
                    return
            if status in TERMINAL and not events:
                return  # the terminal event was sent before a reconnect
            if time.monotonic() - heartbeat > HEARTBEAT_S:
                heartbeat = time.monotonic()
                yield ": keep-alive\n\n"
            await asyncio.sleep(0.2)

    return StreamingResponse(generator(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.get("/{analysis_id}/export")
def export(analysis_id: str, request: Request, format: str = "lab"):
    result = _manager(request).result(analysis_id)
    if format == "lab":
        body = "".join(f"{c['start']:.3f}\t{c['end']:.3f}\t{c['label']}\n" for c in result["chords"])
        return PlainTextResponse(body, headers={"Content-Disposition": f'attachment; filename="{analysis_id}.lab"'})
    if format == "json":
        return JSONResponse(result, headers={"Content-Disposition": f'attachment; filename="{analysis_id}.json"'})
    raise ApiError(422, "OPTION_UNAVAILABLE", f"Export format '{format}' is not available (use lab or json).")


@router.delete("/{analysis_id}", status_code=204)
def delete_analysis(analysis_id: str, request: Request):
    _manager(request).cancel_or_delete(analysis_id)
    return Response(status_code=204)


@router.post("/{analysis_id}/corrections", status_code=201)
def add_correction(analysis_id: str, correction: Correction, request: Request):
    """Store a user's chord fix. Used for training only with ``consent_for_training``."""
    manager = _manager(request)
    result = manager.result(analysis_id)
    if correction.chord_index >= len(result["chords"]):
        raise ApiError(422, "INVALID_REQUEST", "chord_index is out of range.")
    from chordify_core import vocab

    try:
        new_label = vocab.parse_display(correction.new_label)
    except ValueError as err:
        raise ApiError(422, "INVALID_REQUEST", str(err)) from err
    record = {"analysis_id": analysis_id, "sha256": result["source"]["sha256"], "chord_index": correction.chord_index,
              "old_label": result["chords"][correction.chord_index]["label"], "new_label": new_label,
              "start": correction.start, "end": correction.end, "consent_for_training": correction.consent_for_training,
              "models": result["models"], "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    path = Path(manager.settings.data_dir) / "corrections.jsonl"
    with path.open("a") as handle:
        handle.write(json.dumps(record) + "\n")
    return {"stored": True, "new_label": new_label}
