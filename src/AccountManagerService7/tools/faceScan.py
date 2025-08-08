import os
import io
import base64
import requests
import cv2
import numpy as np
from PIL import Image

# --- PyTorch & FairFace Imports ---
import torch
import torch.nn as nn
from torchvision import transforms
from torchvision.models import resnet34

# --- FER (Emotion) Import ---
from fer import FER

# --- DeepFace (Age, Gender) Import ---
from deepface import DeepFace

# --- FastAPI Imports ---
from fastapi import FastAPI
from pydantic import BaseModel
import uvicorn

# -------------------
# Config and Globals
# -------------------
app = FastAPI(title="Hybrid Facial Analysis API")

# --- FairFace Globals ---
CACHE_DIR = os.path.expanduser("./face_models")
FAIRFACE_WEIGHTS_URL = "https://github.com/dchen236/FairFace/releases/download/v1.0/fairface_res34_state_dict.pkl"
FAIRFACE_WEIGHTS_PATH = os.path.join(CACHE_DIR, "fairface_res34_state_dict.pkl") # Use original name
FAIRFACE_MODEL = None
FAIRFACE_LABELS = ["White", "Black", "Latino_Hispanic", "East Asian", "Southeast Asian", "Indian", "Middle Eastern"]

# --- FER (Emotion) Global ---
EMOTION_MODEL = None

class ImagePayload(BaseModel):
    image_data: str

# -------------------
# FairFace Model Definition & Functions
# -------------------
class FairFaceClassifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.model = resnet34(weights=None)
        num_ftrs = self.model.fc.in_features
        self.model.fc = nn.Linear(num_ftrs, 7)

    def forward(self, x):
        return self.model(x)

def download_fairface_weights():
    os.makedirs(CACHE_DIR, exist_ok=True)
    if not os.path.exists(FAIRFACE_WEIGHTS_PATH):
        print("[FairFace] Downloading weights...")
        resp = requests.get(FAIRFACE_WEIGHTS_URL, stream=True)
        resp.raise_for_status()
        with open(FAIRFACE_WEIGHTS_PATH, 'wb') as f:
            for chunk in resp.iter_content(chunk_size=8192):
                f.write(chunk)
        print("[FairFace] Download complete.")

def load_fairface_model(device="cpu"):
    global FAIRFACE_MODEL
    download_fairface_weights()
    FAIRFACE_MODEL = FairFaceClassifier()
    state_dict = torch.load(FAIRFACE_WEIGHTS_PATH, map_location=torch.device('cpu'))
    FAIRFACE_MODEL.load_state_dict(state_dict, strict=False)
    FAIRFACE_MODEL.eval()
    FAIRFACE_MODEL.to(device)
    print(f"[FairFace] Model loaded onto {device}. ✅")

def predict_fairface_race(image_rgb: np.ndarray, device="cpu"):
    transform = transforms.Compose([
        transforms.ToPILImage(),
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])
    tensor = transform(image_rgb).unsqueeze(0).to(device)
    with torch.no_grad():
        outputs = FAIRFACE_MODEL(tensor)
        probs = torch.softmax(outputs, dim=1).cpu().numpy()[0]
    return {label: float(p) for label, p in zip(FAIRFACE_LABELS, probs)}

# -------------------
# FER (Emotion) Functions
# -------------------
def load_emotion_model():
    global EMOTION_MODEL
    EMOTION_MODEL = FER(mtcnn=True)
    # The model warms up by detecting a dummy face
    EMOTION_MODEL.detect_emotions(np.zeros((100, 100, 3), dtype=np.uint8))
    print("[FER] Emotion model loaded. ✅")

def predict_emotion(image_rgb: np.ndarray):
    results = EMOTION_MODEL.detect_emotions(image_rgb)
    if not results:
        return None
    # Return emotion scores for the first face found
    return results[0]['emotions']

# -------------------
# DeepFace (Age, Gender) Functions
# -------------------
def warm_up_deepface():
    dummy_image = np.zeros((100, 100, 3), dtype=np.uint8)
    DeepFace.analyze(dummy_image, actions=['age', 'gender'], enforce_detection=False)
    print("[DeepFace] Age/Gender models loaded. ✅")

def analyze_age_gender(image_bgr: np.ndarray):
    results = DeepFace.analyze(image_bgr, actions=['age', 'gender'], enforce_detection=False)
    # If multiple faces, results is a list. We'll take the first.
    first_result = results[0] if isinstance(results, list) else results
    return {
        "age": first_result.get("age"),
        "gender": first_result.get("dominant_gender"),
        "gender_scores": first_result.get("gender")
    }

# -------------------
# API Endpoints
# -------------------
@app.on_event("startup")
async def startup_event():
    print("API starting up... loading all models.")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    load_fairface_model(device=device)
    load_emotion_model()
    warm_up_deepface()
    print("--- All models are ready. ---")

@app.post("/analyze/")
async def analyze(payload: ImagePayload):
    try:
        if "," in payload.image_data:
            _, b64data = payload.image_data.split(",", 1)
        else:
            b64data = payload.image_data
            
        image_bytes = base64.b64decode(b64data)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        image_np_rgb = np.array(image)

        # --- Run all specialized analyses ---
        # 1. Race Analysis (FairFace)
        ff_device = next(FAIRFACE_MODEL.parameters()).device
        race_scores = predict_fairface_race(image_np_rgb, device=ff_device)
        
        # 2. Emotion Analysis (FER)
        emotion_scores = predict_emotion(image_np_rgb)
        
        # 3. Age & Gender Analysis (DeepFace)
        image_np_bgr = cv2.cvtColor(image_np_rgb, cv2.COLOR_RGB2BGR)
        age_gender_data = analyze_age_gender(image_np_bgr)

        # --- Combine results into a single response ---
        return {
            "age": age_gender_data.get("age"),
            "gender": age_gender_data.get("gender"),
            "dominant_emotion": max(emotion_scores, key=emotion_scores.get) if emotion_scores else "N/A",
            "dominant_race": max(race_scores, key=race_scores.get) if race_scores else "N/A",
            "emotion_scores": emotion_scores,