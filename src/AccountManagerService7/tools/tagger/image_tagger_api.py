#!/usr/bin/env python3
"""
Image Keyword/Tag Identification REST API

A FastAPI-based REST service that accepts base64-encoded images and returns
identified keywords/tags using a locally-running vision-language model.

Designed for NVIDIA DGX Spark with CUDA 12.x (cu130 compatible)

Usage:
    pip install -r requirements.txt
    python image_tagger_api.py

    # Or with uvicorn directly:
    uvicorn image_tagger_api:app --host 0.0.0.0 --port 8000
"""

import base64
import io
import logging
import os
from contextlib import asynccontextmanager
from typing import Optional

import torch
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image
from pydantic import BaseModel, Field
from transformers import AutoProcessor, AutoModelForCausalLM

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)

# Configuration
MODEL_ID = "microsoft/Florence-2-base"  # Excellent for image tagging, runs well on GPU
MODEL_CACHE_DIR = os.environ.get("MODEL_CACHE_DIR", "./model_cache")
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
TORCH_DTYPE = torch.float16 if DEVICE == "cuda" else torch.float32

# Global model and processor references
model = None
processor = None


class ImageRequest(BaseModel):
    """Request model for image tagging endpoint."""
    image_base64: str = Field(
        ...,
        description="Base64-encoded image data (with or without data URI prefix)"
    )
    max_tags: Optional[int] = Field(
        default=20,
        ge=1,
        le=100,
        description="Maximum number of tags to return"
    )
    include_confidence: Optional[bool] = Field(
        default=True,
        description="Whether to include confidence scores (when available)"
    )


class TagResult(BaseModel):
    """Individual tag result."""
    tag: str
    confidence: Optional[float] = None


class ImageResponse(BaseModel):
    """Response model for image tagging endpoint."""
    success: bool
    tags: list[TagResult]
    captions: Optional[list[str]] = None
    device_used: str
    model_id: str


class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    model_loaded: bool
    device: str
    cuda_available: bool
    cuda_device_name: Optional[str] = None


def load_model():
    """Load the vision-language model and processor."""
    global model, processor
    
    logger.info(f"Loading model {MODEL_ID} on device: {DEVICE}")
    logger.info(f"CUDA available: {torch.cuda.is_available()}")
    
    if torch.cuda.is_available():
        logger.info(f"CUDA device: {torch.cuda.get_device_name(0)}")
        logger.info(f"CUDA version: {torch.version.cuda}")
    
    # Load processor
    processor = AutoProcessor.from_pretrained(
        MODEL_ID,
        cache_dir=MODEL_CACHE_DIR,
        trust_remote_code=True
    )
    
    # Load config first and set attention implementation
    from transformers import AutoConfig
    config = AutoConfig.from_pretrained(
        MODEL_ID,
        cache_dir=MODEL_CACHE_DIR,
        trust_remote_code=True
    )
    config._attn_implementation = "eager"
    
    # Load model with the modified config
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_ID,
        config=config,
        cache_dir=MODEL_CACHE_DIR,
        torch_dtype=TORCH_DTYPE,
        trust_remote_code=True,
        device_map="auto" if DEVICE == "cuda" else None
    )
    
    if DEVICE == "cuda" and model.device.type != "cuda":
        model = model.to(DEVICE)
    
    model.eval()
    logger.info("Model loaded successfully")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifespan - load model on startup."""
    load_model()
    yield
    # Cleanup on shutdown
    logger.info("Shutting down, cleaning up resources...")


# Create FastAPI app
app = FastAPI(
    title="Image Keyword Tagger API",
    description="REST API for identifying keywords/tags in images using a local vision-language model",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def decode_base64_image(base64_string: str) -> Image.Image:
    """Decode a base64 string to a PIL Image."""
    # Handle data URI format (e.g., "data:image/png;base64,...")
    if "," in base64_string and base64_string.startswith("data:"):
        base64_string = base64_string.split(",", 1)[1]
    
    # Remove any whitespace
    base64_string = base64_string.strip()
    
    try:
        image_data = base64.b64decode(base64_string)
        image = Image.open(io.BytesIO(image_data))
        # Convert to RGB if necessary (handles PNG with alpha, etc.)
        if image.mode != "RGB":
            image = image.convert("RGB")
        return image
    except Exception as e:
        raise ValueError(f"Failed to decode image: {str(e)}")


def extract_tags_from_caption(caption: str, max_tags: int = 20) -> list[str]:
    """Extract individual tags from a caption or description."""
    import re
    
    # Common stop words to filter out
    stop_words = {
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "must", "shall", "can", "need", "dare",
        "ought", "used", "to", "of", "in", "for", "on", "with", "at", "by",
        "from", "as", "into", "through", "during", "before", "after", "above",
        "below", "between", "under", "again", "further", "then", "once", "here",
        "there", "when", "where", "why", "how", "all", "each", "few", "more",
        "most", "other", "some", "such", "no", "nor", "not", "only", "own",
        "same", "so", "than", "too", "very", "just", "and", "but", "if", "or",
        "because", "until", "while", "this", "that", "these", "those", "it",
        "its", "image", "picture", "photo", "photograph", "showing", "shows",
        "appears", "seems", "looks", "looking", "seen", "see", "visible"
    }
    
    # Clean and tokenize
    caption_lower = caption.lower()
    # Remove punctuation but keep spaces
    caption_clean = re.sub(r'[^\w\s]', ' ', caption_lower)
    words = caption_clean.split()
    
    # Filter and deduplicate while preserving order
    seen = set()
    tags = []
    for word in words:
        word = word.strip()
        if (word and 
            word not in stop_words and 
            word not in seen and 
            len(word) > 1 and
            not word.isdigit()):
            seen.add(word)
            tags.append(word)
    
    return tags[:max_tags]


def generate_tags(image: Image.Image, max_tags: int = 20) -> tuple[list[str], str]:
    """Generate tags for an image using the vision-language model."""
    global model, processor
    
    if model is None or processor is None:
        raise RuntimeError("Model not loaded")
    
    # Florence-2 task prompts for different types of analysis
    # Using multiple prompts for comprehensive tagging
    tasks = [
        "<CAPTION>",           # Basic caption
        "<DETAILED_CAPTION>",  # Detailed description
        "<MORE_DETAILED_CAPTION>",  # Even more detailed
    ]
    
    all_tags = []
    captions = []
    
    for task in tasks:
        inputs = processor(
            text=task,
            images=image,
            return_tensors="pt"
        )
        
        # Move inputs to the correct device and dtype
        inputs = {k: v.to(device=model.device, dtype=model.dtype) if isinstance(v, torch.Tensor) and v.is_floating_point() 
                  else v.to(device=model.device) if isinstance(v, torch.Tensor)
                  else v 
                  for k, v in inputs.items()}
        
        with torch.no_grad():
            generated_ids = model.generate(
                input_ids=inputs["input_ids"],
                pixel_values=inputs["pixel_values"],
                max_new_tokens=256,
                num_beams=3,
                do_sample=False,
                use_cache=False  # Fix for compatibility with newer transformers
            )
        
        generated_text = processor.batch_decode(
            generated_ids, 
            skip_special_tokens=True
        )[0]
        
        # Post-process the generated text
        result = processor.post_process_generation(
            generated_text,
            task=task,
            image_size=(image.width, image.height)
        )
        
        caption = result.get(task, generated_text)
        if isinstance(caption, str):
            captions.append(caption)
            tags = extract_tags_from_caption(caption, max_tags * 2)
            all_tags.extend(tags)
    
    # Deduplicate tags while preserving order (most common first)
    from collections import Counter
    tag_counts = Counter(all_tags)
    unique_tags = [tag for tag, _ in tag_counts.most_common(max_tags)]
    
    return unique_tags, captions


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Check the health status of the service."""
    cuda_device_name = None
    if torch.cuda.is_available():
        cuda_device_name = torch.cuda.get_device_name(0)
    
    return HealthResponse(
        status="healthy" if model is not None else "model_not_loaded",
        model_loaded=model is not None,
        device=DEVICE,
        cuda_available=torch.cuda.is_available(),
        cuda_device_name=cuda_device_name
    )


@app.post("/tag", response_model=ImageResponse)
async def tag_image(request: ImageRequest):
    """
    Analyze an image and return identified keywords/tags.
    
    Accepts a base64-encoded image and returns a list of relevant tags
    that describe the content of the image.
    """
    if model is None or processor is None:
        raise HTTPException(
            status_code=503,
            detail="Model not loaded. Service is starting up."
        )
    
    try:
        # Decode the image
        image = decode_base64_image(request.image_base64)
        logger.info(f"Processing image: {image.size[0]}x{image.size[1]}")
        
        # Generate tags
        tags, captions = generate_tags(image, request.max_tags)
        
        # Build response
        tag_results = [
            TagResult(tag=tag, confidence=None)
            for tag in tags
        ]
        
        return ImageResponse(
            success=True,
            tags=tag_results,
            captions=captions if request.include_confidence else None,
            device_used=DEVICE,
            model_id=MODEL_ID
        )
        
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.exception("Error processing image")
        raise HTTPException(status_code=500, detail=f"Processing error: {str(e)}")


@app.post("/tag/batch", response_model=list[ImageResponse])
async def tag_images_batch(requests: list[ImageRequest]):
    """
    Process multiple images in a batch.
    
    More efficient than calling /tag multiple times for bulk processing.
    """
    if model is None or processor is None:
        raise HTTPException(
            status_code=503,
            detail="Model not loaded. Service is starting up."
        )
    
    results = []
    for i, request in enumerate(requests):
        try:
            image = decode_base64_image(request.image_base64)
            tags, captions = generate_tags(image, request.max_tags)
            
            tag_results = [
                TagResult(tag=tag, confidence=None)
                for tag in tags
            ]
            
            results.append(ImageResponse(
                success=True,
                tags=tag_results,
                captions=captions if request.include_confidence else None,
                device_used=DEVICE,
                model_id=MODEL_ID
            ))
        except Exception as e:
            logger.error(f"Error processing image {i}: {str(e)}")
            results.append(ImageResponse(
                success=False,
                tags=[],
                captions=[f"Error: {str(e)}"],
                device_used=DEVICE,
                model_id=MODEL_ID
            ))
    
    return results


if __name__ == "__main__":
    import uvicorn
    
    # Get configuration from environment
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", 8000))
    workers = int(os.environ.get("WORKERS", 1))  # Keep at 1 for GPU memory management
    
    logger.info(f"Starting server on {host}:{port}")
    logger.info(f"Model cache directory: {MODEL_CACHE_DIR}")
    
    uvicorn.run(
        "image_tagger_api:app",
        host=host,
        port=port,
        workers=workers,
        reload=False  # Disable reload in production
    )
