rm -rf face-tts
python3.10 -m venv env-face
source ./env-face/bin/activate
pip install --upgrade pip
#pip install fastapi[standard] deepface numpy pillow opencv-python face_recognition pydantic tf-keras
pip install fastapi uvicorn pillow numpy opencv-python-headless torch torchvision insightface fer requests
