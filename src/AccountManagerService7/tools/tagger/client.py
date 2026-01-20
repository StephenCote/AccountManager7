#!/usr/bin/env python3
"""
Simple client for the Image Tagger API

Usage:
    python client.py /path/to/image.jpg
    python client.py /path/to/image.png --url http://localhost:8000
    python client.py /path/to/image.jpg --max-tags 10
    python client.py /path/to/image.jpg --resize 1024
"""

import argparse
import base64
import io
import json
import sys
from pathlib import Path

import requests

# Optional: PIL for resizing
try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False


def resize_image(image_path: Path, max_size: int) -> bytes:
    """Resize image if larger than max_size, return as bytes."""
    with Image.open(image_path) as img:
        # Convert to RGB if necessary (handles PNG with alpha, etc.)
        if img.mode not in ('RGB', 'L'):
            img = img.convert('RGB')
        
        # Check if resize needed
        width, height = img.size
        if max(width, height) > max_size:
            # Calculate new size maintaining aspect ratio
            if width > height:
                new_width = max_size
                new_height = int(height * (max_size / width))
            else:
                new_height = max_size
                new_width = int(width * (max_size / height))
            
            print(f"Resizing: {width}x{height} -> {new_width}x{new_height}")
            img = img.resize((new_width, new_height), Image.LANCZOS)
        else:
            print(f"Image size: {width}x{height} (no resize needed)")
        
        # Save to bytes
        buffer = io.BytesIO()
        img.save(buffer, format='JPEG', quality=90)
        return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description="Tag an image using the Image Tagger API")
    parser.add_argument("image", help="Path to the image file")
    parser.add_argument("--url", default="http://192.168.1.42:8000", help="API base URL (default: http://localhost:8000)")
    parser.add_argument("--max-tags", type=int, default=20, help="Maximum number of tags (default: 20)")
    parser.add_argument("--resize", type=int, default=1024, help="Max dimension to resize to (default: 1024, 0 to disable)")
    parser.add_argument("--json", action="store_true", help="Output results as JSON")
    args = parser.parse_args()

    # Check if file exists
    image_path = Path(args.image)
    if not image_path.exists():
        print(f"Error: File not found: {args.image}")
        sys.exit(1)

    # Read and optionally resize image
    print(f"Reading image: {image_path}")
    
    if args.resize > 0 and HAS_PIL:
        image_bytes = resize_image(image_path, args.resize)
        image_base64 = base64.b64encode(image_bytes).decode("utf-8")
    elif args.resize > 0 and not HAS_PIL:
        print("Warning: PIL not installed, skipping resize. Install with: pip install Pillow")
        with open(image_path, "rb") as f:
            image_base64 = base64.b64encode(f.read()).decode("utf-8")
    else:
        with open(image_path, "rb") as f:
            image_base64 = base64.b64encode(f.read()).decode("utf-8")

    # Send request
    print(f"Sending to API at {args.url}...")
    try:
        response = requests.post(
            f"{args.url}/tag",
            json={
                "image_base64": image_base64,
                "max_tags": args.max_tags,
                "include_confidence": True
            },
            timeout=60
        )
        response.raise_for_status()
    except requests.exceptions.ConnectionError:
        print(f"Error: Could not connect to API at {args.url}")
        sys.exit(1)
    except requests.exceptions.HTTPError as e:
        print(f"Error: API returned {e.response.status_code}: {e.response.text}")
        sys.exit(1)

    # Parse and display results
    result = response.json()
    
    # JSON output mode
    if args.json:
        print(json.dumps(result, indent=2))
        return
    
    print("\n" + "=" * 50)
    print("RESULTS")
    print("=" * 50)
    print(f"Success: {result['success']}")
    print(f"Model: {result['model_id']}")
    print(f"Device: {result['device_used']}")
    print(f"\nTags ({len(result['tags'])}):")
    
    for i, tag_info in enumerate(result['tags'], 1):
        print(f"  {i:2}. {tag_info['tag']}")

    if result.get('captions'):
        print(f"\nCaptions:")
        for caption in result['captions']:
            print(f"  • {caption}")


if __name__ == "__main__":
    main()
