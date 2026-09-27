"""Analysis jobs (plan 04 §3.2).

``JobManager`` runs analyses on a thread pool inside the API process. It implements the
job lifecycle, the content-hash cache and the progress-event log that the SSE endpoint
streams. A Celery/Redis runner can replace it behind the same methods when analyses
need their own worker machines; the API layer does not change.
"""
from __future__ import annotations

import hashlib
import json
import threading
import uuid
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .audio_io import check_duration, decode
from .config import Settings
from .errors import ApiError
from .pipeline import analysis
from .pipeline.models import Models

TERMINAL = {"completed", "failed", "cancelled"}


@dataclass
class Job:
    id: str
    cache_key: str
    sha256: str
    filename: str | None
    options: dict
    status: str = "queued"
    stage: str | None = None
    progress: float = 0.0
    error: dict | None = None
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    events: list[dict] = field(default_factory=list)
    cancel_requested: bool = False

    def snapshot(self) -> dict:
        return {"status": self.status, "stage": self.stage, "progress": round(self.progress, 3), "error": self.error}


class JobManager:
    def __init__(self, settings: Settings, models: Models):
        self.settings = settings
        self.models = models
        self.jobs: OrderedDict[str, Job] = OrderedDict()
        self.by_cache_key: dict[str, str] = {}
        self.lock = threading.Lock()
        self.executor = ThreadPoolExecutor(max_workers=settings.workers, thread_name_prefix="analysis")
        self.results_dir = Path(settings.data_dir) / "results"
        self.results_dir.mkdir(parents=True, exist_ok=True)

    # -- submission ----------------------------------------------------------------
    def cache_key(self, sha256: str, options: dict) -> str:
        return f"{sha256}:{self.models.fingerprint}:{json.dumps(options, sort_keys=True)}"

    def submit(self, data: bytes, filename: str | None, options: dict) -> tuple[Job, bool]:
        """Returns (job, cache_hit). A hit returns the existing job for identical input."""
        sha256 = hashlib.sha256(data).hexdigest()
        key = self.cache_key(sha256, options)
        with self.lock:
            existing = self.jobs.get(self.by_cache_key.get(key, ""))
            if existing and existing.status not in ("failed", "cancelled"):
                return existing, True
            job = Job(id=f"an_{uuid.uuid4().hex[:20]}", cache_key=key, sha256=sha256, filename=filename, options=options)
            self.jobs[job.id] = job
            self.by_cache_key[key] = job.id
            self._evict()
        self._emit(job, "progress", {"stage": "queued", "progress": 0.0})
        self.executor.submit(self._run, job, data)
        return job, False

    def _evict(self) -> None:
        while len(self.jobs) > self.settings.job_retention:
            old_id, old = next(iter(self.jobs.items()))
            if old.status not in TERMINAL:
                break
            self.jobs.pop(old_id)
            self.by_cache_key.pop(old.cache_key, None)

    # -- execution -----------------------------------------------------------------
    def _emit(self, job: Job, event: str, data: dict) -> None:
        with self.lock:
            job.events.append({"id": len(job.events) + 1, "event": event, "data": data})

    def _progress(self, job: Job, stage: str, fraction: float, partial: dict | None) -> None:
        job.stage, job.progress = stage, fraction
        self._emit(job, "progress", {"stage": stage, "progress": round(fraction, 3)})
        if partial:
            self._emit(job, "partial", partial)

    def _finish(self, job: Job, status: str, data: dict, error: dict | None = None) -> None:
        """Status change and terminal event in one critical section, so readers holding the
        lock never see a terminal status without its event (or the reverse)."""
        with self.lock:
            job.status, job.error = status, error
            if status == "completed":
                job.stage, job.progress = "done", 1.0
            job.events.append({"id": len(job.events) + 1, "event": status, "data": data})

    def _fail(self, job: Job, code: str, message: str) -> None:
        error = {"code": code, "message": message}
        self._finish(job, "failed", error, error)

    def _run(self, job: Job, data: bytes) -> None:
        if job.cancel_requested:
            return
        job.status = "running"
        try:
            self._progress(job, "decode", 0.02, None)
            y = decode(data, self.settings.decode_sample_rate)
            check_duration(y, self.settings.decode_sample_rate, self.settings.min_duration_s, self.settings.max_duration_s)
            result = analysis.analyse(
                y, self.settings.decode_sample_rate, models=self.models, analysis_id=job.id, filename=job.filename,
                sha256=job.sha256, no_music_threshold=self.settings.no_music_threshold,
                progress=lambda s, f, p: self._progress(job, s, f, p),
                should_cancel=lambda: job.cancel_requested, **job.options)
            (self.results_dir / f"{job.id}.json").write_text(json.dumps(result, separators=(",", ":")))
            self._finish(job, "completed", {"result_url": f"/api/v2/analyses/{job.id}/result"})
        except analysis.Cancelled:
            self._finish(job, "cancelled", {})
        except ApiError as err:
            self._fail(job, err.code, err.detail)
        except analysis.NoMusicDetected as err:
            self._fail(job, "NO_MUSIC_DETECTED", str(err))
        except Exception as err:  # noqa: BLE001 - reported to the client, logged by the server
            import logging

            logging.getLogger("chordify.jobs").exception("analysis %s failed", job.id)
            self._fail(job, "INTERNAL", f"{type(err).__name__}: {err}")

    # -- queries -------------------------------------------------------------------
    def get(self, job_id: str) -> Job:
        job = self.jobs.get(job_id)
        if job is None:
            raise ApiError(404, "NOT_FOUND", f"No analysis with id {job_id}.")
        return job

    def result(self, job_id: str) -> dict:
        job = self.get(job_id)
        if job.status != "completed":
            raise ApiError(409, "NOT_READY", f"Analysis {job_id} is {job.status}.")
        return json.loads((self.results_dir / f"{job_id}.json").read_text())

    def events_after(self, job_id: str, last_id: int) -> tuple[list[dict], str]:
        """New events and the job status, read together."""
        job = self.get(job_id)
        with self.lock:
            return [e for e in job.events if e["id"] > last_id], job.status

    def cancel_or_delete(self, job_id: str) -> None:
        job = self.get(job_id)
        job.cancel_requested = True
        if job.status == "queued":
            self._finish(job, "cancelled", {})
        with self.lock:
            if job.status in TERMINAL:
                self.jobs.pop(job_id, None)
                self.by_cache_key.pop(job.cache_key, None)
        (self.results_dir / f"{job_id}.json").unlink(missing_ok=True)

    def list(self) -> list[Job]:
        return list(reversed(self.jobs.values()))

    def shutdown(self) -> None:
        self.executor.shutdown(wait=False, cancel_futures=True)
