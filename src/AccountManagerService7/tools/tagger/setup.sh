#!/usr/bin/env bash
# Setup script for Image Tagger API on DGX Spark (CUDA 12.x / cu130)
# NOTE: Run this script with bash explicitly: bash setup.sh

set -e

echo "=========================================="
echo "Image Tagger API Setup for DGX Spark"
echo "=========================================="

# Check CUDA availability
echo ""
echo "Checking CUDA installation..."
if command -v nvidia-smi > /dev/null 2>&1; then
    nvidia-smi
else
    echo "WARNING: nvidia-smi not found. Ensure CUDA drivers are installed."
fi

# Create virtual environment
echo ""
echo "Creating Python virtual environment..."
python3 -m venv venv
. venv/bin/activate

# Upgrade pip
echo ""
echo "Upgrading pip..."
./venv/bin/python3 -m pip install --upgrade pip wheel setuptools

# Install dependencies that torch/torchvision need (may be missing on aarch64)
echo ""
echo "Installing base dependencies..."
./venv/bin/python3 -m pip install networkx six typing-extensions sympy filelock jinja2 fsspec

# Install PyTorch with CUDA 13.0 support
echo ""
echo "Installing PyTorch with CUDA 13.0 support..."
./venv/bin/python3 -m pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu130

# Install other dependencies
echo ""
echo "Installing other dependencies..."
./venv/bin/python3 -m pip install -r requirements.txt

# Verify installation
echo ""
echo "Verifying installation..."
./venv/bin/python3 -c "
import torch
print(f'PyTorch version: {torch.__version__}')
print(f'CUDA available: {torch.cuda.is_available()}')
if torch.cuda.is_available():
    print(f'CUDA version: {torch.version.cuda}')
    print(f'GPU: {torch.cuda.get_device_name(0)}')
    print(f'GPU Memory: {torch.cuda.get_device_properties(0).total_memory / 1e9:.1f} GB')
"

# Create model cache directory
echo ""
echo "Creating model cache directory..."
mkdir -p ./model_cache

# Download model (optional - will also download on first run)
echo ""
read -p "Download model now? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Downloading Florence-2-base model..."
    ./venv/bin/python3 -c "
from transformers import AutoProcessor, AutoModelForCausalLM
print('Downloading processor...')
processor = AutoProcessor.from_pretrained('microsoft/Florence-2-base', cache_dir='./model_cache', trust_remote_code=True)
print('Downloading model...')
model = AutoModelForCausalLM.from_pretrained('microsoft/Florence-2-base', cache_dir='./model_cache', trust_remote_code=True)
print('Model downloaded successfully!')
"
fi

echo ""
echo "=========================================="
echo "Setup complete!"
echo ""
echo "To start the server:"
echo "  . venv/bin/activate"
echo "  python image_tagger_api.py"
echo ""
echo "Or with uvicorn:"
echo "  . venv/bin/activate"
echo "  uvicorn image_tagger_api:app --host 0.0.0.0 --port 8000"
echo ""
echo "API documentation will be available at:"
echo "  http://localhost:8000/docs"
echo "=========================================="
