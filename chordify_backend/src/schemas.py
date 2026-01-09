from pydantic import BaseModel
import numpy as np
from typing import List

class ChordPrediction(BaseModel):
    chord: str
    confidence: float


class ErrorResponse(BaseModel):
    detail: str


class TimeStamp(BaseModel):
    start: str
    end: str
