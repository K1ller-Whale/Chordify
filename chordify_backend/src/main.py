import sys
import os
import io
import librosa
from pydub import AudioSegment
from fastapi import FastAPI, File, UploadFile, HTTPException
from contextlib import asynccontextmanager

# Local Imports
sys.path.append(os.path.join(os.path.dirname(__file__), "..", ".."))
from inference import recognizer
from schemas import ChordPrediction
from chordify_ai.utils import Utils
from config import SAMPLE_RATE  # Ensure this is defined in your config.py


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Handles startup and shutdown events.
    Loads the AI model into memory once when the server starts.
    """
    recognizer.load_resources()
    yield


app = FastAPI(title="Chord Recognition API", lifespan=lifespan)


@app.get("/")
def health_check():
    return {"status": "active", "model_loaded": recognizer.is_loaded}


@app.post("/predict", response_model=ChordPrediction)
async def predict_chord(file: UploadFile = File(...)):
    """
    Upload an audio file (wav, mp3, m4a). Returns the detected chord.
    Processes file entirely in memory.
    """

    # 1. Validate File Extension
    filename = file.filename.lower()
    if not filename.endswith((".wav", ".mp3", ".m4a")):
        raise HTTPException(
            status_code=400,
            detail="Unsupported file format. Please upload wav, mp3, or m4a.",
        )

    # 2. Read the file bytes into memory
    try:
        audio_bytes = await file.read()
        audio_buffer = io.BytesIO(audio_bytes)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Failed to read file: {str(e)}")

    # 3. Convert and Load Audio Data
    try:
        if filename.endswith(".m4a"):
            # pydub is required for m4a (Requires FFmpeg installed on the system)
            try:
                audio = AudioSegment.from_file(audio_buffer, format="m4a")
            except FileNotFoundError:
                raise HTTPException(
                    status_code=500,
                    detail="FFmpeg not found on server. Cannot process .m4a files.",
                )

            # Standardize audio: Mono and matching your Model's Sample Rate
            audio = audio.set_channels(1).set_frame_rate(SAMPLE_RATE)

            # Export to a temporary WAV buffer for librosa to read
            wav_io = io.BytesIO()
            audio.export(wav_io, format="wav")
            wav_io.seek(0)

            data, sr = librosa.load(wav_io, sr=SAMPLE_RATE)

        else:
            # librosa handles wav and mp3 natively via io.BytesIO
            audio_buffer.seek(0)
            data, sr = librosa.load(audio_buffer, sr=SAMPLE_RATE, mono=True)

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Audio decoding failed: {str(e)}")

    # 4. Feature Extraction
    try:
        # We pass the raw samples (data) and sample rate (sr) to your McGill extractor
        chroma = Utils.extract_mcgill_style_features(y=data, sr=sr)
    except Exception as e:
        raise HTTPException(
            status_code=500, detail=f"Feature extraction failed: {str(e)}"
        )

    # 5. Model Inference
    try:
        label, confidence = recognizer.predict(chroma)
        return {"chord": label, "confidence": round(float(confidence) * 100, 4)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Prediction failed: {str(e)}")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="localhost", port=8000, reload=True)
