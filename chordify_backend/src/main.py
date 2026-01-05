#This is the main handler of the fast API

from fastapi import FastAPI, File, UploadFile, HTTPException
from contextlib import asynccontextmanager
from . import preprocessing
from .inference import recognizer
from .schemas import ChordPrediction

# --- LIFESPAN MANAGER ---
# This ensures the model loads BEFORE the app starts accepting requests
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup logic
    recognizer.load_resources()
    yield
    # Shutdown logic (if needed, e.g., closing DB connections)
    pass

app = FastAPI(title="Chord Recognition API", lifespan=lifespan)

@app.get("/")
def health_check():
    """Simple check to see if server is online."""
    return {"status": "active", "model_loaded": recognizer.is_loaded}

@app.post("/predict", response_model=ChordPrediction)
async def predict_chord(file: UploadFile = File(...)):
    """
    Upload an audio file (wav, mp3, m4a). Returns the detected chord.
    """
    # 1. Validate File Type
    if not file.filename.lower().endswith(('.wav', '.mp3')):
        raise HTTPException(status_code=400, detail="Unsupported file format.")
    
    # 2. Read Bytes
    audio_bytes = await file.read()
    
    # 3. Preprocess
    input_tensor = preprocessing.process_audio_bytes(audio_bytes)
    
    if input_tensor is None:
        raise HTTPException(status_code=422, detail="Could not process audio. File might be silent or corrupted.")

    # 4. Predict
    try:
        label, confidence = recognizer.predict(input_tensor)
        return {"chord": label, "confidence": round(confidence, 4)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("src.main:app", host="0.0.0.0", port=8000, reload=True)