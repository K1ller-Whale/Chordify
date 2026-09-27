# Chordify backend

FastAPI service for chord timelines, keys and next-chord predictions
(contract: [`docs/chord-progression-plan/05-api-and-communication.md`](../docs/chord-progression-plan/05-api-and-communication.md)).

## Run

From the repository root (the service imports `chordify_core` from there):

```bash
pip install -r chordify_backend/requirements.txt
uvicorn chordify_backend.app.main:app --reload --port 8000
# OpenAPI docs: http://localhost:8000/docs
```

`ffmpeg` must be on the PATH to decode m4a/aac and webm uploads (the web app records
WAV, so its recordings do not need it). For Billboard-compatible NNLS chroma features, which the shipped
ChordNet model needs, build the plugin and install its Python host:

```bash
bash tools/install_nnls_chroma.sh
pip install --upgrade setuptools wheel numpy
pip install --no-build-isolation vamp
python -c "from chordify_core.features import nnls_available; print(nnls_available())"
```

The build needs a C++ compiler and Boost headers: `apt-get install libboost-dev` on
Debian/Ubuntu; the Xcode command line tools and `brew install boost` on macOS. The last
line prints `True` when everything is in place.

Without it the service uses the training-free template model on the plugin-free CQT
chroma, and `GET /api/v2/models` shows which model is active.

## Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `CHORDIFY_ALLOWED_ORIGINS` | `["http://localhost:5173","http://127.0.0.1:5173"]` | CORS allow-list (JSON list) |
| `CHORDIFY_CHORD_MODEL` | `auto` | `auto`: the shipped `models/chordnet-chroma/2.0.0` where the NNLS plugin works, else the templates; or `templates`, or a bundle directory |
| `CHORDIFY_LM_MODEL` | `models/progression-ngram/1.0.0` | Progression model bundle |
| `CHORDIFY_MAX_UPLOAD_MB` / `CHORDIFY_MAX_DURATION_S` | `50` / `900` | Upload limits |
| `CHORDIFY_WORKERS` | `2` | Concurrent analyses |
| `CHORDIFY_DATA_DIR` | `chordify_backend/var` | Results and corrections |

## Endpoints

| | |
|---|---|
| `POST /api/v2/analyses` | Upload audio (multipart `file`, optional `options` JSON) → `202` + job, or `200` on a cache hit |
| `GET /api/v2/analyses/{id}` | Status (ETag / `304`) |
| `GET /api/v2/analyses/{id}/events` | Progress as Server-Sent Events (`snapshot`, `progress`, `partial`, `completed`/`failed`) |
| `GET /api/v2/analyses/{id}/result` | `AnalysisResult` JSON |
| `GET /api/v2/analyses/{id}/export?format=lab\|json` | Export |
| `POST /api/v2/analyses/{id}/corrections` | Store a user's chord fix |
| `POST /api/v2/progressions/next` | Next-chord suggestions for a typed progression |
| `GET /api/v2/models`, `/healthz`, `/readyz` | System |
| `POST /predict`, `/predict_time_stamps`, `/extract_full_chroma` | v1, deprecated, now served by the v2 pipeline |

## Layout

```
app/main.py            app factory, CORS, lifespan (loads models once, warms up)
app/config.py          settings from CHORDIFY_* environment variables
app/schemas.py         Pydantic models = the API contract
app/errors.py          problem+json errors with stable codes
app/audio_io.py        upload limits, format sniffing, decoding
app/jobs.py            in-process job runner: lifecycle, cache, progress events
app/pipeline/          beats, model registry, analysis stages
app/api/               routers: analyses, progressions, system, v1_legacy
```

Jobs run on a thread pool inside the API process. `JobManager` is the seam where a
Celery/Redis runner plugs in when analyses need separate worker machines (plan 04 §1).

Tests: `pytest tests/backend` from the repository root.
