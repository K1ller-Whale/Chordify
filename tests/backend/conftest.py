import io
import time

import numpy as np
import pytest

pytest.importorskip("fastapi")
pytest.importorskip("librosa")

from fastapi.testclient import TestClient  # noqa: E402

from chordify_backend.app.config import Settings  # noqa: E402
from chordify_backend.app.main import create_app  # noqa: E402
from chordify_core import synth  # noqa: E402


def wav_bytes(chords: list[tuple[str, float]], sr: int = 44100, bpm: float | None = 120.0, seed: int = 0) -> bytes:
    """Rendered chords plus a click on every beat, as WAV bytes."""
    import soundfile as sf

    y, _ = synth.render_progression(chords, sr=sr, seed=seed)
    if bpm:
        n = int(0.02 * sr)
        tick = 0.3 * np.sin(2 * np.pi * 2000 * np.arange(n) / sr) * np.exp(-np.arange(n) / 200)
        for beat in np.arange(0, len(y) / sr, 60.0 / bpm):
            i = int(beat * sr)
            y[i:i + n] += tick[:len(y) - i]
    buffer = io.BytesIO()
    sf.write(buffer, y, sr, format="WAV")
    return buffer.getvalue()


@pytest.fixture(scope="session")
def client(tmp_path_factory):
    settings = Settings(data_dir=str(tmp_path_factory.mktemp("var")), warmup=False, max_upload_mb=5, chord_model="templates",
                        allowed_origins=["http://localhost:5173"])
    with TestClient(create_app(settings)) as test_client:
        yield test_client


def wait_for(client, analysis_id: str, timeout: float = 120.0) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = client.get(f"/api/v2/analyses/{analysis_id}").json()
        if status["status"] in ("completed", "failed", "cancelled"):
            return status
        time.sleep(0.1)
    raise TimeoutError(analysis_id)
