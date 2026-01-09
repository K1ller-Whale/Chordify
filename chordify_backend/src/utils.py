from http.client import HTTPException
import io
import librosa
from pydub import AudioSegment
from config import *

class Utils: 
    async def proccess_audio(file):
        filename = file.filename.lower()
        if not filename.endswith((".wav", ".mp3", ".m4a", ".webm")):
            raise HTTPException(
                status_code=400,
                detail="Unsupported file format. Please upload wav, mp3, m4a, or webm.",
            )

        # 2. Read the file bytes into memory
        try:
            audio_bytes = await file.read()
            print(f"Audio bytes length: {len(audio_bytes)}")
            audio_buffer = io.BytesIO(audio_bytes)
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"Failed to read file: {str(e)}")
        print("done")
        # 3. Convert and Load Audio Data
        try:
            if filename.endswith((".m4a", ".webm")):
                # pydub is required for m4a and webm (Requires FFmpeg installed on the system)
                format_type = "m4a" if filename.endswith(".m4a") else "webm"
                try:
                    audio = AudioSegment.from_file(audio_buffer, format=format_type)
                except FileNotFoundError:
                    raise HTTPException(
                        status_code=500,
                        detail="FFmpeg not found on server. Cannot process .m4a or .webm files.",
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
                print("loading with librosa")
        
        except Exception as e:
            print(f"Audio decoding failed: {str(e)}")
            raise HTTPException(status_code=500, detail=f"Audio decoding failed: {str(e)}")
        return data, sr