# stt_api.py
# Description: A FastAPI server for speech-to-text (STT) 
# that supports the Whisper model.

import os
import torch
import uvicorn
import tempfile
import base64
import io
from contextlib import asynccontextmanager
from pydub import AudioSegment
from typing import Dict, Optional

from fastapi import FastAPI, HTTPException, status, WebSocket
from fastapi.responses import JSONResponse
from starlette.websockets import WebSocketDisconnect
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

# Whisper specific imports
import whisper
from whisper.model import Whisper

# --- Pydantic Models for API Requests ---
class STTRequest(BaseModel):
    audio_sample: str = Field(..., description="Base64 encoded audio stream (e.g., WAV, MP3).")
    model_name: Optional[str] = Field("base", description="The whisper model size to use (e.g., 'tiny', 'base', 'small', 'medium', 'large').")

# --- Global Variables & Model Caching ---
stt_model_cache: Dict[str, Whisper] = {}

# --- Application Lifespan (Startup & Shutdown) ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Handles application startup and shutdown events.
    - Loads all necessary AI models into memory.
    """
    print("--- Server starting up ---")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Using device: {device}")

    # --- Whisper STT Setup ---
    global stt_model_cache
    default_stt_model = "base"
    print(f"Loading default Whisper STT model: '{default_stt_model}'...")
    stt_model_cache[default_stt_model] = whisper.load_model(default_stt_model)
    print("--- Whisper STT model loaded successfully ---")

    yield

    print("--- Server shutting down ---")
    stt_model_cache.clear()

# --- FastAPI App Initialization ---
app = FastAPI(lifespan=lifespan)

def transcribe_audio_buffer(audio_buffer: io.BytesIO, stt_model: Whisper) -> str:
    """
    Handles speech-to-text from an in-memory audio buffer using a Whisper model.
    The buffer should contain WAV-formatted data.
    """
    audio_buffer.seek(0)
    audio_segment = AudioSegment.from_file(audio_buffer)

    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_audio_file:
        temp_audio_path = temp_audio_file.name
    
    try:
        audio_segment.export(temp_audio_path, format="wav")
        result = stt_model.transcribe(temp_audio_path, fp16=torch.cuda.is_available())
        transcribed_text = result.get('text', '')
    finally:
        if os.path.exists(temp_audio_path):
            os.remove(temp_audio_path)

    return transcribed_text

@app.websocket("/ws/transcribe")
async def websocket_transcribe(websocket: WebSocket):
    """
    Handles live audio streaming by accumulating chunks into a valid audio stream,
    transcribing the full stream for context, and sending only the new text back.
    """
    await websocket.accept()
    print("WebSocket client connected. Ready for stateful transcription.")

    model_key = "base"
    stt_model = stt_model_cache.get(model_key)
    if not stt_model:
        stt_model = whisper.load_model(model_key)
        stt_model_cache[model_key] = stt_model

    session_audio_buffer = io.BytesIO()
    last_sent_transcript = ""

    try:
        while True:
            data = await websocket.receive_bytes()
            
            session_audio_buffer.write(data)
            
            transcription_buffer = io.BytesIO(session_audio_buffer.getvalue())
            
            if transcription_buffer.getbuffer().nbytes == 0:
                continue

            full_transcript = await run_in_threadpool(
                transcribe_audio_buffer, transcription_buffer, stt_model
            )
            
            new_text = ""
            if full_transcript and full_transcript.strip():
                if len(full_transcript) > len(last_sent_transcript):
                    new_text = full_transcript[len(last_sent_transcript):].strip()
                
            if new_text:
                await websocket.send_json({"text": new_text})
                last_sent_transcript = full_transcript

    except WebSocketDisconnect:
        print("Client disconnected gracefully.")
    except Exception as e:
        print(f"Error in WebSocket session: {e}")
    finally:
        session_audio_buffer.close()
        print("WebSocket session finished and buffer closed.")
        
# --- Whisper Transcription Logic ---
def transcribe_with_whisper(request: STTRequest) -> str:
    """
    Handles the speech-to-text process using a Whisper model.
    """
    global stt_model_cache
    model_key = request.model_name
    
    stt_model = stt_model_cache.get(model_key)
    if not stt_model:
        print(f"Whisper model '{model_key}' not in cache. Loading now...")
        try:
            stt_model = whisper.load_model(model_key)
            stt_model_cache[model_key] = stt_model
            print(f"--- Whisper STT model '{model_key}' loaded successfully ---")
        except Exception as e:
            raise RuntimeError(f"Failed to load Whisper model '{model_key}'. It may be an invalid model size. Error: {e}")

    try:
        audio_bytes = base64.b64decode(request.audio_sample)
    except Exception:
        raise ValueError("Invalid base64 string for 'audio_sample'.")

    try:
        audio_segment = AudioSegment.from_file(io.BytesIO(audio_bytes))
    except Exception as e:
        raise ValueError(f"Could not read audio data. Ensure it's a valid format (e.g., MP3, WAV, M4A). Error: {e}")

    temp_audio_path = None
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_audio_file:
        temp_audio_path = temp_audio_file.name
    
    try:
        audio_segment.export(temp_audio_path, format="wav")
        
        print(f"Transcribing standardized WAV file with Whisper model '{model_key}'...")
        result = stt_model.transcribe(temp_audio_path, fp16=torch.cuda.is_available())
        transcribed_text = result['text']
    finally:
        if temp_audio_path and os.path.exists(temp_audio_path):
            os.remove(temp_audio_path)

    print("Transcription successful.")
    return transcribed_text

@app.post("/speech-to-text/")
async def speech_to_text(request: STTRequest):
    """
    Endpoint to perform speech-to-text using Whisper.
    Accepts a base64 encoded audio stream and returns the transcribed text.
    """
    if not request.audio_sample.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The 'audio_base64' field cannot be empty."
        )
    
    try:
        text_result = await run_in_threadpool(transcribe_with_whisper, request)
        return JSONResponse(content={"text": text_result})
    except (ValueError, RuntimeError) as e:
        print(f"Client or Runtime Error: {e}")
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        print(f"An internal error occurred during STT: {e}")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal error occurred during transcription.")

# --- Root Endpoint for Health Check ---
@app.get("/")
async def root():
    return {
        "message": "STT Server is running.",
        "docs_url": "/docs",
        "speech_to_text_endpoint": {
            "path": "/speech-to-text/",
            "method": "POST",
            "body": STTRequest.model_json_schema()
        },
        "websocket_transcribe_endpoint": {
            "path": "/ws/transcribe",
            "method": "WebSocket"
        }
    }

# --- Main execution ---
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8002)