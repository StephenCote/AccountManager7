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
JOYCAPTION_MODEL_ID = "fancyfeast/llama-joycaption-alpha-two-hf-llava"  # Detailed uncensored captions
NSFW_MODEL_ID = "Falconsai/nsfw_image_detection"  # NSFW classifier
WD_MODEL_ID = "SmilingWolf/wd-vit-tagger-v3"  # WaifuDiffusion tagger for detailed tags
MODEL_CACHE_DIR = os.environ.get("MODEL_CACHE_DIR", "./model_cache")
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
TORCH_DTYPE = torch.float16 if DEVICE == "cuda" else torch.float32

# Global model and processor references
joycaption_model = None
joycaption_processor = None
nsfw_model = None
nsfw_processor = None
wd_model = None
wd_processor = None


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


class NsfwResult(BaseModel):
    """NSFW classification result."""
    is_nsfw: bool
    confidence: float
    label: str


class ImageResponse(BaseModel):
    """Response model for image tagging endpoint."""
    success: bool
    tags: list[TagResult]
    captions: Optional[list[str]] = None
    nsfw: Optional[NsfwResult] = None
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
    global joycaption_model, joycaption_processor, nsfw_processor, wd_model, wd_processor
    
    logger.info(f"Loading JoyCaption model {JOYCAPTION_MODEL_ID} on device: {DEVICE}")
    logger.info(f"CUDA available: {torch.cuda.is_available()}")
    
    if torch.cuda.is_available():
        logger.info(f"CUDA device: {torch.cuda.get_device_name(0)}")
        logger.info(f"CUDA version: {torch.version.cuda}")
    
    # Load JoyCaption (LLaVA-based model)
    from transformers import LlavaForConditionalGeneration
    
    joycaption_processor = AutoProcessor.from_pretrained(
        JOYCAPTION_MODEL_ID,
        cache_dir=MODEL_CACHE_DIR,
        trust_remote_code=True
    )
    
    joycaption_model = LlavaForConditionalGeneration.from_pretrained(
        JOYCAPTION_MODEL_ID,
        cache_dir=MODEL_CACHE_DIR,
        torch_dtype=TORCH_DTYPE,
        device_map="auto" if DEVICE == "cuda" else None,
        trust_remote_code=True
    )
    
    joycaption_model.eval()
    logger.info("JoyCaption model loaded successfully")
    
    # Load NSFW classifier
    logger.info(f"Loading NSFW model {NSFW_MODEL_ID}")
    from transformers import pipeline
    nsfw_processor = pipeline(
        "image-classification",
        model=NSFW_MODEL_ID,
        device=0 if DEVICE == "cuda" else -1,
        torch_dtype=TORCH_DTYPE
    )
    logger.info("NSFW model loaded successfully")
    
    # Load WaifuDiffusion tagger for detailed tags
    logger.info(f"Loading WD tagger {WD_MODEL_ID}")
    from transformers import AutoModelForImageClassification, AutoImageProcessor
    wd_processor = AutoImageProcessor.from_pretrained(
        WD_MODEL_ID,
        cache_dir=MODEL_CACHE_DIR
    )
    wd_model = AutoModelForImageClassification.from_pretrained(
        WD_MODEL_ID,
        cache_dir=MODEL_CACHE_DIR,
        torch_dtype=TORCH_DTYPE
    )
    if DEVICE == "cuda":
        wd_model = wd_model.to(DEVICE)
    wd_model.eval()
    logger.info("WD tagger loaded successfully")


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


def classify_nsfw(image: Image.Image) -> NsfwResult:
    """Classify image for NSFW content."""
    global nsfw_processor
    
    if nsfw_processor is None:
        raise RuntimeError("NSFW model not loaded")
    
    results = nsfw_processor(image)
    
    # Results are sorted by confidence, get top result
    top_result = results[0]
    label = top_result["label"].lower()
    confidence = top_result["score"]
    
    # Falconsai model returns "nsfw" or "normal"
    is_nsfw = label == "nsfw"
    
    return NsfwResult(
        is_nsfw=is_nsfw,
        confidence=confidence,
        label=top_result["label"]
    )


def get_wd_tags(image: Image.Image, threshold: float = 0.35) -> list[tuple[str, float]]:
    """Get detailed tags from WaifuDiffusion tagger."""
    global wd_model, wd_processor
    
    if wd_model is None or wd_processor is None:
        return []
    
    try:
        # Process image
        inputs = wd_processor(images=image, return_tensors="pt")
        inputs = {k: v.to(device=wd_model.device, dtype=wd_model.dtype) if v.is_floating_point() 
                  else v.to(device=wd_model.device) 
                  for k, v in inputs.items()}
        
        with torch.no_grad():
            outputs = wd_model(**inputs)
        
        # Get probabilities
        probs = torch.sigmoid(outputs.logits).cpu().numpy()[0]
        
        # Get tags above threshold
        tags_with_scores = []
        for idx, prob in enumerate(probs):
            if prob >= threshold:
                tag = wd_model.config.id2label[idx]
                # Clean up tag format (replace underscores, etc.)
                tag = tag.replace("_", " ")
                tags_with_scores.append((tag, float(prob)))
        
        # Sort by confidence
        tags_with_scores.sort(key=lambda x: x[1], reverse=True)
        
        return tags_with_scores
    except Exception as e:
        logger.warning(f"WD tagger error: {e}")
        return []


def generate_tags(image: Image.Image, max_tags: int = 20) -> tuple[list[str], list[str]]:
    """Generate tags for an image using JoyCaption."""
    global joycaption_model, joycaption_processor
    
    if joycaption_model is None or joycaption_processor is None:
        raise RuntimeError("Model not loaded")
    
    # JoyCaption prompts for different detail levels
    prompts = [
        "Write a short caption for this image.",
        "Write a detailed description of this image.",
    ]
    
    all_tags = []
    captions = []
    
    for prompt in prompts:
        # Format conversation for LLaVA
        conversation = [
            {
                "role": "user",
                "content": [
                    {"type": "image"},
                    {"type": "text", "text": prompt}
                ]
            }
        ]
        
        # Apply chat template
        text = joycaption_processor.apply_chat_template(
            conversation,
            add_generation_prompt=True
        )
        
        inputs = joycaption_processor(
            text=text,
            images=image,
            return_tensors="pt"
        )
        
        # Move inputs to the correct device
        inputs = {k: v.to(joycaption_model.device) if isinstance(v, torch.Tensor) else v 
                  for k, v in inputs.items()}
        
        with torch.no_grad():
            generated_ids = joycaption_model.generate(
                **inputs,
                max_new_tokens=512,
                do_sample=True,
                temperature=0.6,
                top_p=0.9,
            )
        
        # Decode only the new tokens
        generated_ids = generated_ids[:, inputs["input_ids"].shape[1]:]
        caption = joycaption_processor.batch_decode(
            generated_ids, 
            skip_special_tokens=True
        )[0].strip()
        
        if caption:
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
        status="healthy" if joycaption_model is not None else "model_not_loaded",
        model_loaded=joycaption_model is not None,
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
    if joycaption_model is None or joycaption_processor is None:
        raise HTTPException(
            status_code=503,
            detail="Model not loaded. Service is starting up."
        )
    
    try:
        # Decode the image
        image = decode_base64_image(request.image_base64)
        logger.info(f"Processing image: {image.size[0]}x{image.size[1]}")
        
        # Run NSFW classification
        nsfw_result = classify_nsfw(image)
        logger.info(f"NSFW: {nsfw_result.label} ({nsfw_result.confidence:.2%})")
        
        # Generate tags from JoyCaption
        tags, captions = generate_tags(image, request.max_tags)
        
        # Get detailed tags from WD tagger (includes NSFW tags)
        wd_tags = get_wd_tags(image, threshold=0.35)
        
        # Merge tags: JoyCaption tags first, then WD tags that aren't duplicates
        seen_tags = set(t.lower() for t in tags)
        for wd_tag, confidence in wd_tags:
            if wd_tag.lower() not in seen_tags:
                tags.append(wd_tag)
                seen_tags.add(wd_tag.lower())
        
        # Limit to max_tags
        tags = tags[:request.max_tags]
        
        # Build response
        tag_results = [
            TagResult(tag=tag, confidence=None)
            for tag in tags
        ]
        
        return ImageResponse(
            success=True,
            tags=tag_results,
            captions=captions if request.include_confidence else None,
            nsfw=nsfw_result,
            device_used=DEVICE,
            model_id=JOYCAPTION_MODEL_ID
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
    if joycaption_model is None or joycaption_processor is None:
        raise HTTPException(
            status_code=503,
            detail="Model not loaded. Service is starting up."
        )
    
    results = []
    for i, request in enumerate(requests):
        try:
            image = decode_base64_image(request.image_base64)
            nsfw_result = classify_nsfw(image)
            tags, captions = generate_tags(image, request.max_tags)
            
            # Get detailed tags from WD tagger
            wd_tags = get_wd_tags(image, threshold=0.35)
            
            # Merge tags
            seen_tags = set(t.lower() for t in tags)
            for wd_tag, confidence in wd_tags:
                if wd_tag.lower() not in seen_tags:
                    tags.append(wd_tag)
                    seen_tags.add(wd_tag.lower())
            
            tags = tags[:request.max_tags]
            
            tag_results = [
                TagResult(tag=tag, confidence=None)
                for tag in tags
            ]
            
            results.append(ImageResponse(
                success=True,
                tags=tag_results,
                captions=captions if request.include_confidence else None,
                nsfw=nsfw_result,
                device_used=DEVICE,
                model_id=JOYCAPTION_MODEL_ID
            ))
        except Exception as e:
            logger.error(f"Error processing image {i}: {str(e)}")
            results.append(ImageResponse(
                success=False,
                tags=[],
                captions=[f"Error: {str(e)}"],
                nsfw=None,
                device_used=DEVICE,
                model_id=JOYCAPTION_MODEL_ID
            ))
    
    return results


if __name__ == "__main__":
    import uvicorn
    
    # Get configuration from environment
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", 8000))
    
    logger.info(f"Starting server on {host}:{port}")
    logger.info(f"Model cache directory: {MODEL_CACHE_DIR}")
    
    uvicorn.run(
        app,
        host=host,
        port=port,
    )
