from pydantic import BaseModel

class ChordPrediction(BaseModel):
    chord: str
    confidence: float

class ErrorResponse(BaseModel):
    detail: str