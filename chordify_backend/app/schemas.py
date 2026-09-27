"""API contract (plan 05). FastAPI generates the OpenAPI document from these models."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class AnalysisOptions(BaseModel):
    vocabulary: Literal["majmin", "sevenths", "large"] | None = Field(
        None, description="Chord types to report. Default: every type the active model knows. A coarser "
                          "tier simplifies its chords (Cmaj7 -> C, Bm7b5 -> Bm); a finer one is refused.")
    predictions: bool = True
    min_segment_beats: int = Field(1, ge=1, le=8)


class Links(BaseModel):
    self: str
    events: str
    result: str


class AnalysisError(BaseModel):
    code: str
    message: str


class AnalysisStatus(BaseModel):
    id: str
    status: Literal["queued", "running", "completed", "failed", "cancelled"]
    stage: str | None = None
    progress: float = 0.0
    filename: str | None = None
    created_at: str
    error: AnalysisError | None = None
    links: Links


class KeyInfo(BaseModel):
    tonic: str
    mode: Literal["major", "minor", "unknown"]
    confidence: float = 0.0


class KeySegment(KeyInfo):
    start: float
    end: float


class KeySummary(BaseModel):
    model_config = {"populate_by_name": True}
    global_: KeyInfo = Field(alias="global")
    segments: list[KeySegment]


class Alternative(BaseModel):
    label: str
    display: str
    p: float


class Prediction(BaseModel):
    label: str
    display: str
    roman: str | None = None
    p: float
    reason: str
    cache_count: int = 0
    expected_beats: float | None = None


class PriorEntry(BaseModel):
    roman: str | None
    p: float


class ChordSegment(BaseModel):
    index: int
    start: float
    end: float
    beats: int | None = None
    label: str
    display: str
    root: str | None = None
    quality: str | None = None
    bass: str | None = None
    roman: str | None = Field(None, description="Roman numeral with the chord type: V7, IVmaj7, viiø7")
    roman_triad: str | None = Field(None, description="Bare numeral (V for V7), as predictions and patterns use")
    function: str | None = None
    scale_hint: str | None = None
    confidence: float
    alternatives: list[Alternative] = []
    next: list[Prediction] = []
    theory_only: list[PriorEntry] = []
    surprise: float | None = None


class Bar(BaseModel):
    start: float
    end: float
    chords: list[str]


class Section(BaseModel):
    letter: str
    label: str
    start: float
    end: float


class TimeShare(BaseModel):
    display: str
    seconds: float
    share: float


class Pattern(BaseModel):
    roman: list[str]
    name: str | None = None
    count: int


class Summary(BaseModel):
    unique_chords: int
    time_share: list[TimeShare]
    patterns: list[Pattern]
    predictability: float | None = None


class Tempo(BaseModel):
    bpm: float | None
    meter: str
    confidence: float = 0.0


class Source(BaseModel):
    filename: str | None = None
    title: str | None = None
    artist: str | None = None
    duration: float
    sha256: str | None = None


class AnalysisResult(BaseModel):
    model_config = {"populate_by_name": True}
    schema_version: int = 1
    analysis_id: str
    status: Literal["completed"] = "completed"
    source: Source
    models: dict[str, str]
    tempo: Tempo
    key: KeySummary
    beats: list[float]
    downbeats: list[float]
    bars: list[Bar]
    sections: list[Section] = []
    chords: list[ChordSegment]
    summary: Summary


class ProgressionRequest(BaseModel):
    chords: list[str] = Field(..., min_length=1, max_length=512,
                              description="Chord names ('G', 'Em', 'D7', 'C/E') or Harte labels ('G:maj')")
    durations_beats: list[float] | None = None
    key: str | None = Field(None, description="e.g. 'G', 'G major', 'Em'; estimated from the chords when omitted")
    k: int = Field(3, ge=1, le=10)


class ProgressionKey(KeyInfo):
    estimated: bool


class ProgressionResponse(BaseModel):
    key_used: ProgressionKey
    predictions: list[Prediction]
    theory_only: list[PriorEntry]
    model: str


class Correction(BaseModel):
    chord_index: int = Field(..., ge=0)
    new_label: str
    start: float | None = None
    end: float | None = None
    consent_for_training: bool = False


class LegacyChordPrediction(BaseModel):
    chord: str
    confidence: float


class LegacyTimeStamp(BaseModel):
    start: str
    end: str
