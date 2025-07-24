# main.py
# Description: A FastAPI server for long-form text-to-speech using Coqui XTTS.
#
# This script sets up a web server with an endpoint that can:
# 1. Receive a JSON payload containing text and optional parameters.
# 2. The JSON can optionally include a voice sample as a base64 string.
# 3. Synthesize the text into audio chunks and stitch them together.
# 4. Encode the final MP3 audio into a base64 string.
# 5. Return a JSON response containing the base64 encoded audio.

import os
import torch
import nltk
import uvicorn
import tempfile
import base64
import io
from contextlib import asynccontextmanager
from pydub import AudioSegment
from typing import Optional

from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from TTS.api import TTS

# --- Pydantic Model for JSON Request Body ---
class SynthesisRequest(BaseModel):
    text: str
    speed: float = 1.0
    language: str = "en"
    voice_sample: Optional[str] = None

# --- GLOBAL VARIABLES & MODEL LOADING ---

# Global variable to hold the TTS model
tts_model = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Asynchronous context manager to handle application startup and shutdown.
    Loads the TTS model into memory.
    """
    global tts_model
    print("--- Server starting up ---")
    
    try:
        nltk.data.find('tokenizers/punkt')
    except nltk.downloader.DownloadError:
        print("Downloading NLTK 'punkt' model...")
        nltk.download('punkt')
        print("NLTK 'punkt' model downloaded.")

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Using device: {device}")

    model_name = "tts_models/multilingual/multi-dataset/xtts_v2"
    print(f"Loading TTS model: {model_name}...")
    tts_model = TTS(model_name).to(device)
    print("--- TTS model loaded successfully ---")
    
    yield
    
    print("--- Server shutting down ---")
    tts_model = None

app = FastAPI(lifespan=lifespan)

# --- API ENDPOINT DEFINITION ---

@app.post("/synthesize/")
async def synthesize_speech(request: SynthesisRequest):
    """
    Main endpoint to synthesize speech from text provided in a JSON body.
    """
    if not request.text.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Text content cannot be empty."
        )
    
    final_output_path = None
    try:
        # This temporary directory is for intermediate files (chunks, uploaded sample)
        # and will be cleaned up automatically.
        with tempfile.TemporaryDirectory() as temp_dir:
            speaker_wav_path = None
            if request.voice_sample:
                # If a base64 voice sample is provided, decode and save it
                try:
                    audio_bytes = base64.b64decode(request.voice_sample)
                    speaker_wav_path = os.path.join(temp_dir, "uploaded_voice.wav")
                    with open(speaker_wav_path, "wb") as f:
                        f.write(audio_bytes)
                    print(f"Using provided base64 voice sample.")
                except Exception:
                    raise HTTPException(status_code=400, detail="Invalid base64 string for voice sample.")
            else:
                # If no sample is provided, use a default voice file.
                default_voice_path = "female_speaker_0.mp3"
                if not os.path.exists(default_voice_path):
                     raise HTTPException(
                        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                        detail=f"Default voice file not found at '{default_voice_path}'. Please create it."
                    )
                speaker_wav_path = default_voice_path
                print(f"No voice sample provided. Using default voice: {default_voice_path}")

            # 1. Split text and synthesize chunks
            sentences = nltk.sent_tokenize(request.text)
            print(f"Text split into {len(sentences)} sentences.")
            chunk_files = []
            for i, sentence in enumerate(sentences):
                if not sentence.strip(): continue
                chunk_file = os.path.join(temp_dir, f"chunk_{i}.wav")
                print(f"Synthesizing chunk {i+1}/{len(sentences)}...")
                tts_model.tts_to_file(
                    text=sentence, speaker_wav=speaker_wav_path,
                    language=request.language, speed=request.speed, file_path=chunk_file
                )
                chunk_files.append(chunk_file)

            # 2. Stitch audio chunks
            print("Stitching audio chunks...")
            combined_audio = AudioSegment.empty()
            for chunk_file in chunk_files:
                combined_audio += AudioSegment.from_wav(chunk_file)
            
            # 3. Export to a persistent temporary file
            with tempfile.NamedTemporaryFile(delete=False, suffix=".mp3") as fp:
                final_output_path = fp.name
                print(f"Exporting final audio to temporary file: {final_output_path}")
                combined_audio.export(final_output_path, format="mp3", bitrate="320k")

        # 4. Read the final audio file's bytes
        print(f"Reading bytes from {final_output_path} for encoding.")
        with open(final_output_path, "rb") as f:
            audio_bytes = f.read()
        
        # 5. Encode the bytes to base64
        base64_encoded_audio = base64.b64encode(audio_bytes).decode('utf-8')
        
        # 6. Return the JSON response
        print("Returning JSON response with base64 audio.")
        return JSONResponse(content={"audio_base64": base64_encoded_audio})

    except Exception as e:
        print(f"An error occurred: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )
    finally:
        # Clean up the final output file
        if final_output_path and os.path.exists(final_output_path):
            print(f"Cleaning up temporary file: {final_output_path}")
            os.remove(final_output_path)

# --- ROOT ENDPOINT FOR TESTING ---
@app.get("/")
async def root():
    return {
        "message": "TTS Server is running.",
        "docs_url": "/docs",
        "synthesize_endpoint": "POST /synthesize/"
    }

# --- TO RUN THE SERVER ---
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)