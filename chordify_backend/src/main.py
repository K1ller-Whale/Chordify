import sys
import os
import io
import librosa
from pydub import AudioSegment
from fastapi import FastAPI, File, UploadFile, HTTPException, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import List
import json
# Local Imports
sys.path.append(os.path.join(os.path.dirname(__file__), "..", ".."))
from inference import recognizer
from schemas import ChordPrediction, TimeStamp
from chordify_ai.utils import Utils
from config import *  # Ensure this is defined in your config.py
from utils import Utils as LocalUtils

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Handles startup and shutdown events.
    Loads the AI model into memory once when the server starts.
    """
    recognizer.load_resources()
    yield


app = FastAPI(title="Chord Recognition API", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Allow all origins for mobile app access
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
def health_check():
    return {"status": "active", "model_loaded": recognizer.is_loaded}

@app.post("/extract_full_chroma")
async def extract_full_chroma(file: UploadFile = File(...)):
    """
    Upload an audio file (wav, mp3, m4a). Returns the full chroma features.
    Processes file entirely in memory.
    """
    print("Received file:", file.filename)
    data, sr = await LocalUtils.proccess_audio(file)
    # 4. Feature Extraction
    try:
        # We pass the raw samples (data) and sample rate (sr) to your McGill extractor
        chroma = Utils.extract_mcgill_style_features(y=data, sr=sr, slice=False)
        # now = datetime.now()
        # formatted = now.strftime("%Y-%m-%d%H:%M:%S.%f")
        file_name = f"{datetime.now(timezone.utc).timestamp()}.png"
        print(file_name)
        
        # Ensure CHROMA_DIR is absolute and exists
        chroma_dir_abs = os.path.join(BASE_DIR, CHROMA_DIR) if not os.path.isabs(CHROMA_DIR) else CHROMA_DIR
        os.makedirs(chroma_dir_abs, exist_ok=True)
        
        file_path = os.path.join(chroma_dir_abs, file_name)
        print(f"Full file path: {file_path}")
        Utils.save_chroma_plot(chroma.T, path=chroma_dir_abs, filename=file_name, sr=sr, hop_length=2048)
        
        # Verify file was created
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Chroma plot file was not created at {file_path}")
            
    except Exception as e:
        print(f"Feature extraction failed: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(
            status_code=500, detail=f"Feature extraction failed: {str(e)}"
        )

    # 5. Return the file
    try:
        print(f"Returning chroma file: {file_path}")
        return FileResponse(
            file_path,
            media_type="image/png",
            filename="chroma_visualization.png"
        )
    except Exception as e:
        print(f"File response failed: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"File response failed: {str(e)}")



@app.post("/predict", response_model=ChordPrediction)
async def predict_chord(file: UploadFile = File(...)):
    """
    Upload an audio file (wav, mp3, m4a). Returns the detected chord.
    Processes file entirely in memory.
    """
    print("Received file:", file.filename)
    data, sr = await LocalUtils.proccess_audio(file)
    # 1. Validate File Extension
    # 4. Feature Extraction
    try:
        # We pass the raw samples (data) and sample rate (sr) to your McGill extractor
        chroma = Utils.extract_mcgill_style_features(y=data, sr=sr)
    except Exception as e:
        print(f"Feature extraction failed: {str(e)}")
        raise HTTPException(
            status_code=500, detail=f"Feature extraction failed: {str(e)}"
        )

    # 5. Model Inference
    try:
        label, confidence = recognizer.predict(chroma)
        return {"chord": label, "confidence": round(float(confidence) * 100, 4)}
    except Exception as e:
        print(f"Prediction failed: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Prediction failed: {str(e)}")




@app.post("/predict_time_stamps")
async def predict_time_stamps(file: UploadFile = File(...), timestamps: str = Form(...)):
    """
    Upload an audio file and a list of time stamps. Returns predictions for each time stamp.
    Skeleton endpoint - functionality to be implemented.
    """
    # Parse timestamps
    try:
        timestamps_list = json.loads(timestamps)
        # Validate as list of TimeStamp
        validated_timestamps = [TimeStamp(**ts) for ts in timestamps_list]
        data, sr = await LocalUtils.proccess_audio(file)
        print(validated_timestamps[0])
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid timestamps format: {str(e)}")
    # Helper: parse simple time formats like "2s" or numeric strings
    def _parse_time(t: str) -> float:
        if isinstance(t, (int, float)):
            return float(t)
        s = str(t).strip()
        if s.endswith("s"):
            s = s[:-1]
        return float(s)

    results = []
    try:
        total_samples = len(data)
        for ts in validated_timestamps:
            start_sec = _parse_time(ts.start)
            end_sec = _parse_time(ts.end)
            if end_sec <= start_sec:
                raise ValueError(f"end must be greater than start for timestamp {ts}")

            start_idx = max(0, int(start_sec * sr))
            end_idx = min(total_samples, int(end_sec * sr))

            if start_idx >= end_idx:
                raise ValueError(f"Timestamp slice empty for {ts}")

            segment = data[start_idx:end_idx]

            # Extract chroma for the segment using the shared Utils
            chroma = Utils.extract_mcgill_style_features(y=segment, sr=sr, slice=False)

            # Predict chord for this segment
            label, confidence = recognizer.predict_long_audio(chroma)

            results.append({
                "start": ts.start,
                "end": ts.end,
                "chord": label,
                "confidence": round(float(confidence) * 100, 4),
            })

        return {"segments": results}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed processing timestamps: {str(e)}")





if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
