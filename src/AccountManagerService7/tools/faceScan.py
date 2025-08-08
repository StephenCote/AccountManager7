import os
os.environ["CUDA_VISIBLE_DEVICES"] = "-1" # Forces TensorFlow to use CPU

import io
import base64
import cv2
import numpy as np
from PIL import Image

# --- Specialist Model Imports ---
from fer import FER
from deepface import DeepFace

# --- FastAPI Imports ---
from fastapi import FastAPI
from pydantic import BaseModel
import uvicorn

# -------------------
# Config and Globals
# -------------------
app = FastAPI(title="Hybrid Facial Analysis API (FER + DeepFace)")

# --- FER (Emotion) Global ---
EMOTION_MODEL = None

class ImagePayload(BaseModel):
    image_data: str

# -------------------
# Model Loading & Prediction Functions
# -------------------
def load_emotion_model():
    """Initializes the FER model for emotion detection."""
    global EMOTION_MODEL
    EMOTION_MODEL = FER(mtcnn=True)
    # Warm up the model by detecting a dummy face
    EMOTION_MODEL.detect_emotions(np.zeros((100, 100, 3), dtype=np.uint8))
    print("[FER] Emotion model loaded. ✅")

def predict_emotion(image_rgb: np.ndarray):
    """Analyzes a cropped face image for emotions using FER."""
    if not EMOTION_MODEL:
        return None
    results = EMOTION_MODEL.detect_emotions(image_rgb)
    if not results:
        return None
    # Convert scores to standard Python floats
    emotions = results[0]['emotions']
    return {emotion: float(score) for emotion, score in emotions.items()}

def warm_up_deepface():
    """Initializes DeepFace for age, gender, and race analysis."""
    dummy_image = np.zeros((100, 100, 3), dtype=np.uint8)
    DeepFace.analyze(dummy_image, actions=['age', 'gender', 'race'], enforce_detection=False)
    print("[DeepFace] Age, Gender, and Race models loaded. ✅")


# -------------------
# API Endpoints
# -------------------
@app.on_event("startup")
async def startup_event():
    print("API starting up... loading models.")
    load_emotion_model()
    warm_up_deepface()
    print("--- All models are ready. ---")

@app.post("/analyze/")
async def analyze(payload: ImagePayload):
    try:
        # --- 1. Decode Image ---
        if "," in payload.image_data:
            _, b64data = payload.image_data.split(",", 1)
        else:
            b64data = payload.image_data
            
        image_bytes = base64.b64decode(b64data)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        image_np_rgb = np.array(image)

        # --- 2. Run DeepFace for Race, Age, Gender, and Face Location ---
        image_np_bgr = cv2.cvtColor(image_np_rgb, cv2.COLOR_RGB2BGR)
        deepface_results = DeepFace.analyze(
            img_path=image_np_bgr,
            actions=['race', 'age', 'gender'],
            enforce_detection=True
        )
        
        # The result is a list; take the first detected face
        first_face = deepface_results[0]
        region = first_face['region'] # Get face coordinates

        # --- 3. Crop the face from the original image ---
        x, y, w, h = region['x'], region['y'], region['w'], region['h']
        cropped_face_rgb = image_np_rgb[y:y+h, x:x+w]

        # --- 4. Run FER on the CROPPED face for emotion analysis ---
        emotion_scores = predict_emotion(cropped_face_rgb)
        
        # --- 5. Combine all results and ensure JSON compatibility ---
        analysis_output = {
            "age": int(first_face.get("age")),
            "dominant_gender": first_face.get("dominant_gender"),
            "dominant_race": first_face.get("dominant_race"),
            "dominant_emotion": max(emotion_scores, key=emotion_scores.get) if emotion_scores else "N/A",
            "face_confidence": first_face.get('face_confidence'),
            "emotion_scores": emotion_scores,
            "race_scores": {k: float(v) for k, v in first_face.get("race", {}).items()},
            "gender_scores": {k: float(v) for k, v in first_face.get("gender", {}).items()}
        }
        return analysis_output
        
    except Exception as e:
        return {"error": f"An error occurred during analysis: {str(e)}"}

@app.get("/")
def read_root():
    return {"status": "ok", "message": "Hybrid Facial Analysis API is running."}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8003)