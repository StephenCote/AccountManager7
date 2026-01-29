import os
os.environ["CUDA_VISIBLE_DEVICES"] = "-1"  # Forces TensorFlow to use CPU

import io
import base64
import cv2
import numpy as np
from PIL import Image

# --- DeepFace handles everything ---
from deepface import DeepFace

# --- FastAPI Imports ---
from fastapi import FastAPI
from pydantic import BaseModel
import uvicorn

app = FastAPI(title="Facial Analysis API (DeepFace)")

class ImagePayload(BaseModel):
    image_data: str

def warm_up_deepface():
    """Initializes DeepFace for all analyses."""
    dummy_image = np.zeros((100, 100, 3), dtype=np.uint8)
    DeepFace.analyze(dummy_image, actions=['age', 'gender', 'race', 'emotion'], enforce_detection=False)
    print("[DeepFace] All models loaded. ✅")

@app.on_event("startup")
async def startup_event():
    print("API starting up... loading models.")
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
        image_np_bgr = cv2.cvtColor(image_np_rgb, cv2.COLOR_RGB2BGR)

        # --- 2. Run DeepFace for everything ---
        results = DeepFace.analyze(
            img_path=image_np_bgr,
            actions=['age', 'gender', 'race', 'emotion'],
            enforce_detection=True
        )
        
        first_face = results[0]
        
        # --- 3. Build response ---
        analysis_output = {
            "age": int(first_face.get("age")),
            "dominant_gender": first_face.get("dominant_gender"),
            "dominant_race": first_face.get("dominant_race"),
            "dominant_emotion": first_face.get("dominant_emotion"),
            "face_confidence": first_face.get('face_confidence'),
            "emotion_scores": {k: float(v) for k, v in first_face.get("emotion", {}).items()},
            "race_scores": {k: float(v) for k, v in first_face.get("race", {}).items()},
            "gender_scores": {k: float(v) for k, v in first_face.get("gender", {}).items()}
        }
        return analysis_output
        
    except Exception as e:
        return {"error": f"An error occurred during analysis: {str(e)}"}

@app.get("/")
def read_root():
    return {"status": "ok", "message": "Facial Analysis API is running."}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8003)