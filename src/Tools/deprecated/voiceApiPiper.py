# main.py
# Description: A FastAPI server for long-form text-to-speech using Piper.
#
# This script sets up a web server with an endpoint that can:
# 1. Receive a JSON payload containing text and a specified speaker model.
# 2. Check for local models first, then download if not present.
# 3. Synthesize the text into audio chunks and stitch them together.
# 4. Encode the final MP3 audio into a base64 string.
# 5. Return a JSON response containing the base64 encoded audio.

import os
import nltk
import uvicorn
import base64
import io
import json
import inspect # Import the inspect module
import onnxruntime as ort
import wave
from contextlib import asynccontextmanager
from pydub import AudioSegment
from typing import Dict, Set, Optional

from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool
from asyncio import CancelledError

# Piper TTS specific imports
from piper.voice import PiperVoice
from piper.config import PiperConfig

# --- Configuration ---
# Define a local directory to store Piper models. This helps avoid download issues.
PIPER_MODELS_DIR = "./piper_models"
# NOTE: pydub requires an external audio processing library like ffmpeg or libav.
# Ensure ffmpeg is installed and accessible in your system's PATH.
# On Debian/Ubuntu: sudo apt-get install ffmpeg
# On macOS (with Homebrew): brew install ffmpeg


# --- Pydantic Model for JSON Request Body ---
class SynthesisRequest(BaseModel):
    text: str
    speaker: str  # e.g., "en_US-lessac-medium"
    speed: Optional[float] = None  # Optional user-defined speed
# --- GLOBAL VARIABLES & MODEL LOADING ---

# Cache for loaded Piper voice models to avoid reloading from disk
model_cache: Dict[str, PiperVoice] = {}
# Set of valid arguments for the PiperConfig class, to be populated at startup.
PIPER_CONFIG_ARGS: Set[str] = set()

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Asynchronous context manager to handle application startup and shutdown.
    """
    print("--- Server starting up ---")
    
    # At startup, inspect the PiperConfig class to get a list
    # of all its valid constructor arguments. This creates an "allowlist".
    global PIPER_CONFIG_ARGS
    sig = inspect.signature(PiperConfig)
    PIPER_CONFIG_ARGS = set(sig.parameters.keys())
    print(f"Found valid PiperConfig arguments: {PIPER_CONFIG_ARGS}")

    # Ensure the local models directory exists
    os.makedirs(PIPER_MODELS_DIR, exist_ok=True)
    print(f"Piper models will be stored in: {os.path.abspath(PIPER_MODELS_DIR)}")
    
    # Download the sentence tokenizer model from NLTK if not present
    try:
        nltk.data.find('tokenizers/punkt')
    except nltk.downloader.DownloadError:
        print("Downloading NLTK 'punkt' model...")
        nltk.download('punkt')
        print("NLTK 'punkt' model downloaded.")
    
    yield
    
    print("--- Server shutting down ---")
    model_cache.clear()

app = FastAPI(lifespan=lifespan)

# --- Synchronous Helper Function for TTS Processing ---
def process_synthesis_in_background(request: SynthesisRequest) -> str:
    """
    This function contains all the blocking, CPU-intensive code.
    It's designed to be run in a separate thread to avoid blocking the main event loop.
    """
    global model_cache
    
    # --- Model Loading ---
    
    voice_model = model_cache.get(request.speaker)
    if not voice_model:
        onnx_path = os.path.join(PIPER_MODELS_DIR, f"{request.speaker}.onnx")
        config_path = os.path.join(PIPER_MODELS_DIR, f"{request.speaker}.onnx.json")


        if os.path.exists(onnx_path) and os.path.exists(config_path):
            print(f"Found local model for '{request.speaker}'. Loading from disk.")
            with open(config_path, "r", encoding="utf-8") as f:
                config_json = json.load(f)
            
            # This logic intelligently and recursively searches the entire JSON config
            # for valid PiperConfig arguments.
            def find_valid_args(data: Dict, valid_args: Set[str]) -> Dict:
                found_args = {}
                if not isinstance(data, dict):
                    return found_args

                for key, value in data.items():
                    # If the key is a valid argument, add it and its value.
                    # Do NOT recurse into it, as the config expects the whole object.
                    if key in valid_args:
                        found_args[key] = value
                    # Handle the special 'voice' -> 'espeak_voice' case
                    elif key == 'voice' and 'espeak_voice' in valid_args:
                        found_args['espeak_voice'] = value
                    # If the key is NOT a valid argument, but its value is a dictionary,
                    # then it might contain valid arguments, so we recurse.
                    elif isinstance(value, dict):
                        found_args.update(find_valid_args(value, valid_args))
                return found_args

            filtered_config = find_valid_args(config_json, PIPER_CONFIG_ARGS)

            # **FINAL FIX:** Some model configs don't explicitly state all
            # required parameters. We'll add a default for 'phoneme_type' if it's
            # not found after parsing the JSON, as this is a common omission.
            if 'phoneme_type' not in filtered_config:
                print("WARNING: 'phoneme_type' not found in config. Adding default value: 'espeak'")
                filtered_config['phoneme_type'] = 'espeak'
            
            filtered_config["length_scale"] = request.speed if request.speed is not None else 1.0
            # Create the config object from the carefully constructed dictionary
            config = PiperConfig(**filtered_config)
            providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
            session = ort.InferenceSession(onnx_path, providers=providers)
            voice_model = PiperVoice(session=session, config=config)
            #voice_model = PiperVoice(onnx_path, config=config)
            model_cache[request.speaker] = voice_model
            #print(f"Voice model created: {voice_model} (type: {type(voice_model)})")
            #print(f"Does it have .session? {hasattr(voice_model, 'session')}")
            #print(f"Session type (if any): {type(getattr(voice_model, 'session', None))}")
        else:
            raise ValueError(
                f"Local model for '{request.speaker}' not found. "
                f"Expected files: {onnx_path}, {config_path}. "
                "Automatic download disabled. Please provide the model manually."
            )
    if not isinstance(voice_model, PiperVoice):
        raise TypeError(f"Expected PiperVoice instance, got {type(voice_model)} instead.")
		
    # --- Synthesis ---
    try:
        # 1. Split text into sentences
        sentences = nltk.sent_tokenize(request.text)
        print(f"Text split into {len(sentences)} sentences.")
        
        # 2. Synthesize each sentence and collect the audio segments
        audio_segments = []
        for i, sentence in enumerate(sentences):
            if not sentence.strip(): continue
            print(f"Synthesizing chunk {i+1}/{len(sentences)} with '{request.speaker}'...")
            wav_bytes = io.BytesIO()
            with wave.open(wav_bytes, "wb") as wave_writer:
                wave_writer.setnchannels(1)
                wave_writer.setsampwidth(2)
                wave_writer.setframerate(voice_model.config.sample_rate)
                voice_model.synthesize_wav(sentence, wave_writer)
            #voice_model.synthesize_wav(sentence, wav_bytes)
            wav_bytes.seek(0)
            
            audio_segment = AudioSegment.from_wav(wav_bytes)
            audio_segments.append(audio_segment)

        # 3. Stitch audio chunks together
        print("Stitching audio chunks...")
        combined_audio = sum(audio_segments, AudioSegment.empty())
        
        # 4. Export to an in-memory buffer as an MP3
        print("Exporting audio to in-memory buffer...")
        buffer = io.BytesIO()
        combined_audio.export(buffer, format="mp3", bitrate="320k")
        
        # 5. Get bytes from buffer, encode to base64, and return
        buffer.seek(0)
        audio_bytes = buffer.read()
        return base64.b64encode(audio_bytes).decode('utf-8')

    except Exception:
        print("An exception occurred during the synthesis process.")
        raise

# --- API ENDPOINT DEFINITION ---
@app.post("/synthesize/")
async def synthesize_speech(request: SynthesisRequest):
    """
    Main endpoint to synthesize speech using Piper. It runs the blocking TTS code
    in a separate thread to keep the server responsive.
    """
    if not request.text.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Text content cannot be empty."
        )
    
    try:
        #base64_audio = await run_in_threadpool(process_synthesis_in_background, request)
        base64_audio = process_synthesis_in_background(request)
        print("Returning JSON response with base64 audio.")
        return JSONResponse(content={"audio_base64": base64_audio})

    except CancelledError:
        print("\n--- Synthesis request cancelled by user ---")
        raise
    except Exception as e:
        print(f"An error occurred during synthesis: {e}")
        if "Could not load speaker model" in str(e):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

# --- ROOT ENDPOINT FOR TESTING ---
@app.get("/")
async def root():
    return {
        "message": "Piper TTS Server is running.",
        "docs_url": "/docs",
        "synthesize_endpoint": "POST /synthesize/"
    }

# --- TO RUN THE SERVER ---
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)
