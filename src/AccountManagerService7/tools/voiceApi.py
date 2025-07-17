# main_unified.py
# Description: A unified FastAPI server for long-form text-to-speech (TTS)
# that supports both Piper and Coqui XTTS models.
#
# This script sets up a web server with a single endpoint that can:
# 1. Receive a JSON payload with text and synthesis parameters.
# 2. Intelligently determine whether to use Piper or XTTS based on the request.
# 3. For Piper: Use a pre-defined speaker model.
# 4. For XTTS: Use a default voice or a user-provided voice sample (via base64).
# 5. Synthesize text into audio chunks, stitch them together, and encode to MP3.
# 6. Return a JSON response with the base64 encoded audio.

import os
import torch
import nltk
import uvicorn
import tempfile
import base64
import io
import json
import inspect
import wave
import uuid  # Import the uuid module for generating random names
from contextlib import asynccontextmanager
from pydub import AudioSegment
from typing import Dict, Set, Optional, Literal

from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

# Piper specific imports
from piper.voice import PiperVoice
from piper.config import PiperConfig
import onnxruntime as ort

# XTTS specific imports
from TTS.api import TTS

# --- Configuration ---
PIPER_MODELS_DIR = "./piper_models"
XTTS_DEFAULT_VOICE = "female_speaker_0.mp3"  # Default voice for XTTS
# Ensure ffmpeg is installed for pydub to work correctly.
# On Debian/Ubuntu: sudo apt-get install ffmpeg
# On macOS (with Homebrew): brew install ffmpeg

# --- Pydantic Model for a Unified JSON Request ---
class SynthesisRequest(BaseModel):
    text: str
    engine: Literal['piper', 'xtts'] = Field(..., description="The TTS engine to use: 'piper' or 'xtts'.")
    # Piper-specific arguments
    speaker: Optional[str] = Field(None, description="The Piper speaker model name (e.g., 'en_US-lessac-medium'). Required if engine is 'piper'.")
    # XTTS-specific arguments
    voice_sample: Optional[str] = Field(None, description="Base64 encoded WAV audio for voice cloning in XTTS.")
    language: str = Field("en", description="Language for XTTS synthesis.")
    speed: float = Field(1.0, description="Playback speed for the synthesized audio.")

# --- Global Variables & Model Caching ---
piper_model_cache: Dict[str, PiperVoice] = {}
xtts_model = None
PIPER_CONFIG_ARGS: Set[str] = set()

# --- Application Lifespan (Startup & Shutdown) ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Handles application startup and shutdown events.
    - Loads all necessary AI models into memory.
    - Sets up necessary directories and downloads NLTK data.
    """
    print("--- Server starting up ---")

    # --- NLTK Setup ---
    try:
        nltk.data.find('tokenizers/punkt')
    except nltk.downloader.DownloadError:
        print("Downloading NLTK 'punkt' model...")
        nltk.download('punkt')
        print("NLTK 'punkt' model downloaded.")

    # --- Piper Setup ---
    global PIPER_CONFIG_ARGS
    sig = inspect.signature(PiperConfig)
    PIPER_CONFIG_ARGS = set(sig.parameters.keys())
    os.makedirs(PIPER_MODELS_DIR, exist_ok=True)
    print(f"Piper models directory: {os.path.abspath(PIPER_MODELS_DIR)}")
    print(f"Found valid PiperConfig arguments: {PIPER_CONFIG_ARGS}")

    # --- XTTS Setup ---
    global xtts_model
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Using device: {device}")
    model_name = "tts_models/multilingual/multi-dataset/xtts_v2"
    print(f"Loading XTTS model: {model_name}...")
    xtts_model = TTS(model_name).to(device)
    print("--- XTTS model loaded successfully ---")

    yield

    print("--- Server shutting down ---")
    piper_model_cache.clear()
    xtts_model = None

# --- FastAPI App Initialization ---
app = FastAPI(lifespan=lifespan)

# --- Piper Synthesis Logic ---
def synthesize_with_piper(request: SynthesisRequest) -> bytes:
    """
    Handles the synthesis process for the Piper TTS engine.
    """
    if not request.speaker:
        raise ValueError("A 'speaker_model' must be provided for the Piper engine.")

    voice_model = piper_model_cache.get(request.speaker)
    if not voice_model:
        onnx_path = os.path.join(PIPER_MODELS_DIR, f"{request.speaker}.onnx")
        config_path = os.path.join(PIPER_MODELS_DIR, f"{request.speaker}.onnx.json")

        if not (os.path.exists(onnx_path) and os.path.exists(config_path)):
            raise FileNotFoundError(
                f"Local model for '{request.speaker}' not found. "
                f"Expected: {onnx_path} and {config_path}."
            )

        print(f"Loading Piper model '{request.speaker}' from disk.")
        with open(config_path, "r", encoding="utf-8") as f:
            config_json = json.load(f)

        def find_valid_args(data: Dict, valid_args: Set[str]) -> Dict:
            found = {k: v for k, v in data.items() if k in valid_args}
            for k, v in data.items():
                if k in valid_args:
                    found[k] = v
                elif k == 'voice' and 'espeak_voice' in valid_args:
                    found['espeak_voice'] = v
                elif isinstance(v, dict):
                   found.update(find_valid_args(v, valid_args))
            return found

        filtered_config = find_valid_args(config_json, PIPER_CONFIG_ARGS)
        if 'phoneme_type' not in filtered_config:
            filtered_config['phoneme_type'] = 'espeak'
        filtered_config["length_scale"] = request.speed

        config = PiperConfig(**filtered_config)
        providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
        session = ort.InferenceSession(onnx_path, providers=providers)
        voice_model = PiperVoice(session=session, config=config)
        piper_model_cache[request.speaker] = voice_model

    sentences = nltk.sent_tokenize(request.text)
    audio_segments = []
    print(f"Synthesizing {len(sentences)} sentences with Piper...")
    for sentence in sentences:
        if not sentence.strip(): continue
        with io.BytesIO() as wav_io:
            with wave.open(wav_io, "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(voice_model.config.sample_rate)
                voice_model.synthesize_wav(sentence, wf)
            wav_io.seek(0)
            audio_segments.append(AudioSegment.from_wav(wav_io))

    combined_audio = sum(audio_segments, AudioSegment.empty())
    with io.BytesIO() as buffer:
        combined_audio.export(buffer, format="mp3", bitrate="320k")
        return buffer.getvalue()

# --- XTTS Synthesis Logic ---
def synthesize_with_xtts(request: SynthesisRequest) -> bytes:
    """
    Handles the synthesis process for the Coqui XTTS engine.
    """
    with tempfile.TemporaryDirectory() as temp_dir:
        speaker_wav_path = None
        if request.voice_sample:
            try:
                audio_bytes = base64.b64decode(request.voice_sample)
                # --- MODIFICATION START ---
                # Generate a unique filename to prevent race conditions
                unique_filename = f"{uuid.uuid4()}.wav"
                speaker_wav_path = os.path.join(temp_dir, unique_filename)
                # --- MODIFICATION END ---
                with open(speaker_wav_path, "wb") as f:
                    f.write(audio_bytes)
                print(f"Using provided base64 voice sample for XTTS, saved to {unique_filename}.")
            except Exception:
                raise ValueError("Invalid base64 string for 'voice_sample'.")
        else:
            if not os.path.exists(XTTS_DEFAULT_VOICE):
                raise FileNotFoundError(f"Default XTTS voice not found at '{XTTS_DEFAULT_VOICE}'.")
            speaker_wav_path = XTTS_DEFAULT_VOICE
            print(f"Using default XTTS voice: {XTTS_DEFAULT_VOICE}")

        sentences = nltk.sent_tokenize(request.text)
        audio_segments = []
        print(f"Synthesizing {len(sentences)} sentences with XTTS...")

        for i, sentence in enumerate(sentences):
            if not sentence.strip(): continue
            chunk_file = os.path.join(temp_dir, f"chunk_{i}.wav")
            xtts_model.tts_to_file(
                text=sentence,
                speaker_wav=speaker_wav_path,
                language=request.language,
                speed=request.speed,
                file_path=chunk_file
            )
            audio_segments.append(AudioSegment.from_wav(chunk_file))

        combined_audio = sum(audio_segments, AudioSegment.empty())
        with io.BytesIO() as buffer:
            combined_audio.export(buffer, format="mp3", bitrate="320k")
            return buffer.getvalue()

# --- Unified API Endpoint ---
@app.post("/synthesize/")
async def synthesize_speech(request: SynthesisRequest):
    """
    Main endpoint to synthesize speech. It routes the request to the
    appropriate TTS engine (Piper or XTTS) based on the 'engine' parameter.
    """
    if not request.text.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Text content cannot be empty."
        )

    try:
        print(f"Received request for TTS engine: '{request.engine}'")
        if request.engine == 'piper':
            audio_bytes = await run_in_threadpool(synthesize_with_piper, request)
        elif request.engine == 'xtts':
            audio_bytes = await run_in_threadpool(synthesize_with_xtts, request)
        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Invalid engine '{request.engine}'. Must be 'piper' or 'xtts'."
            )

        base64_audio = base64.b64encode(audio_bytes).decode('utf-8')
        print("Synthesis successful. Returning base64 encoded audio.")
        return JSONResponse(content={"audio_base64": base64_audio})

    except (FileNotFoundError, ValueError) as e:
        print(f"Client Error: {e}")
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        print(f"An internal error occurred: {e}")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

# --- Root Endpoint for Health Check ---
@app.get("/")
async def root():
    return {
        "message": "Unified TTS Server (Piper & XTTS) is running.",
        "docs_url": "/docs",
        "synthesize_endpoint": {
            "path": "/synthesize/",
            "method": "POST",
            "body": SynthesisRequest.model_json_schema()
        }
    }

# --- Main execution ---
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)