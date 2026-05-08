rm -rf env-tts
python3.10 -m venv env-tts
source ./env-tts/bin/activate
pip install --upgrade pip
pip install -r ttsRequirements.txt
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
