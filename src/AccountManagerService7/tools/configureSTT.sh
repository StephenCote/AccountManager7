rm -rf env-stt
python3.10 -m venv env-stt
source ./env-stt/bin/activate
pip install --upgrade pip
pip install -r sttRequirements.txt
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
