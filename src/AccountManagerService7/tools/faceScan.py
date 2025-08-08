import os
os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
import io
import base64
# import requests # No longer needed
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

# --- FairFace Globals (MODIFIED) ---
# TODO: Update this path to point to your local .pt model file!
FAIRFACE_WEIGHTS_PATH = "./face_models/res34_fair_align_multi_7_20190809.pt"
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

# The automatic download function has been removed.

def load_fairface_model(device="cpu"):
    global FAIRFACE_MODEL
    if not os.path.exists(FAIRFACE_WEIGHTS_PATH):
        raise FileNotFoundError(
            f"FairFace model not found at {FAIRFACE_WEIGHTS_PATH}. "
            "Please update the FAIRFACE_WEIGHTS_PATH variable in the script."
        )
    
    print(f"[FairFace] Loading local model from: {FAIRFACE_WEIGHTS_PATH}")
    FAIRFACE_MODEL = FairFaceClassifier()
    # map_location ensures it loads on CPU first before being moved
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
    EMOTION_MODEL.detect_emotions(np.zeros((100, 100, 3), dtype=np.uint8))
    print("[FER] Emotion model loaded. ✅")

def predict_emotion(image_rgb: np.ndarray):
    results = EMOTION_MODEL.detect_emotions(image_rgb)
    if not results:
        return None
    emotions = results[0]['emotions']
    return {emotion: float(score) for emotion, score in emotions.items()}

# -------------------
# DeepFace (Age, Gender) Functions
# -------------------
def warm_up_deepface():
    dummy_image = np.zeros((100, 100, 3), dtype=np.uint8)
    DeepFace.analyze(dummy_image, actions=['age', 'gender'], enforce_detection=False)
    print("[DeepFace] Age/Gender models loaded. ✅")

def analyze_age_gender(image_bgr: np.ndarray):
    results = DeepFace.analyze(image_bgr, actions=['age', 'gender'], enforce_detection=False)
    first_result = results[0] if isinstance(results, list) else results
    
    # Get the dictionary of gender scores from the results
    gender_data = first_result.get("gender", {})
    
    # Return a new dictionary with all values converted to standard Python types
    return {
        "age": int(first_result.get("age")),  # <-- Convert age to standard int
        "gender": first_result.get("dominant_gender"),
        # Rebuild the scores dictionary, converting each score to a standard float
        "gender_scores": {gender: float(score) for gender, score in gender_data.items()}
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
        ff_device = next(FAIRFACE_MODEL.parameters()).device
        race_scores = predict_fairface_race(image_np_rgb, device=ff_device)
        emotion_scores = predict_emotion(image_np_rgb)
        image_np_bgr = cv2.cvtColor(image_np_rgb, cv2.COLOR_RGB2BGR)
        age_gender_data = analyze_age_gender(image_np_bgr)

        # --- Combine results ---
        return {
            "age": age_gender_data.get("age"),
            "dominant_gender": age_gender_data.get("gender"),
            "dominant_emotion": max(emotion_scores, key=emotion_scores.get) if emotion_scores else "N/A",
            "dominant_race": max(race_scores, key=race_scores.get) if race_scores else "N/A",
            "emotion_scores": emotion_scores,
            "race_scores": race_scores,
            "gender_scores": age_gender_data.get("gender_scores"),
        }
        
    except Exception as e:
        return {"error": f"An error occurred during analysis: {str(e)}"}

@app.get("/")
def read_root():
    return {"status": "ok", "message": "Hybrid Facial Analysis API is running."}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8003)