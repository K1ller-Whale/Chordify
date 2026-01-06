# This is the main handler of the fast API

import sys
import os

sys.path.append(os.path.join(os.path.dirname(__file__), "..", ".."))

from fastapi import FastAPI, File, UploadFile, HTTPException
from contextlib import asynccontextmanager
from inference import recognizer
from schemas import ChordPrediction
from chordify_ai.utils import Utils
from config import *
import librosa
import io
from pydub import AudioSegment


@asynccontextmanager
async def lifespan(app: FastAPI):
    recognizer.load_resources()
    yield


app = FastAPI(title="Chord Recognition API", lifespan=lifespan)


@app.get("/")
def health_check():
    return {"status": "active", "model_loaded": recognizer.is_loaded}


@app.post("/predict", response_model=ChordPrediction)
async def predict_chord(file: UploadFile = File(...)):
    """
    Upload an audio file (wav, mp3). Returns the detected chord.
    """
    if not file.filename.lower().endswith((".wav", ".mp3", ".m4a")):
        raise HTTPException(status_code=400, detail="Unsupported file format.")

    audio_bytes = await file.read()
    audio = io.BytesIO(audio_bytes)

    if file.filename.lower().endswith(".m4a"):
        sound = AudioSegment.from_file(audio, format="m4a")
        wav_io = io.BytesIO()
        sound.export(wav_io, format="wav")
        wav_io.seek(0)  # Reset to beginning
        data, sr = librosa.load(wav_io, sr=SAMPLE_RATE, mono=True)
    else:
        data, sr = librosa.load(audio, sr=SAMPLE_RATE, mono=True)

    chroma = Utils.extract_mcgill_style_features(y=data, sr=sr)
    label, confidence = recognizer.predict(chroma)
    return {"chord": label, "confidence": round(confidence * 100, 4)}

    # 3. Preprocess
    # input_tensor = preprocessing.process_audio_bytes(audio_bytes)

    # if input_tensor is None:
    #     raise HTTPException(
    #         status_code=422,
    #         detail="Could not process audio. File might be silent or corrupted.",
    #     )

    # 4. Predict
    # try:
    #     label, confidence = recognizer.predict(input_tensor)
    #     return {"chord": label, "confidence": round(confidence, 4)}
    # except Exception as e:
    #     raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="localhost", port=8000, reload=True)
