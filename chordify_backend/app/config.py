"""Settings from environment variables (prefix CHORDIFY_), e.g.

    CHORDIFY_ALLOWED_ORIGINS='["http://localhost:5173"]'
    CHORDIFY_CHORD_MODEL=templates    # default "auto": the shipped ChordNet bundle when NNLS works here
    CHORDIFY_MAX_UPLOAD_MB=50
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

REPO_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CHORDIFY_", env_file=".env", extra="ignore")

    allowed_origins: list[str] = ["http://localhost:5173", "http://127.0.0.1:5173"]
    max_upload_mb: float = 50.0
    max_duration_s: float = 900.0
    min_duration_s: float = 1.0
    decode_sample_rate: int = 44100
    chord_model: str = "auto"  # "auto", "templates" or a ChordNet bundle directory
    default_chord_bundle: str = str(REPO_ROOT / "models" / "chordnet-chroma" / "2.0.0")
    lm_model: str = str(REPO_ROOT / "models" / "progression-ngram" / "1.0.0")
    workers: int = 2
    data_dir: str = str(REPO_ROOT / "chordify_backend" / "var")
    job_retention: int = 200  # analyses kept in memory; results are also written to data_dir
    no_music_threshold: float = 0.9  # fraction of N above which an analysis fails
    warmup: bool = True  # run a tiny analysis at start-up so numba/ONNX compilation is not paid by a user

    @property
    def max_upload_bytes(self) -> int:
        return int(self.max_upload_mb * 1024 * 1024)


@lru_cache
def get_settings() -> Settings:
    return Settings()
