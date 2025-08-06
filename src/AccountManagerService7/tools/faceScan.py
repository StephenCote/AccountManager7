# --- ADD THESE TWO LINES AT THE VERY TOP ---
import os
os.environ['CUDA_VISIBLE_DEVICES'] = '-1'

# --- The rest of your script follows ---
import cv2
import face_recognition
import numpy as np
import base64
import io
import uvicorn
from PIL import Image
from fastapi import FastAPI
from pydantic import BaseModel
from deepface import DeepFace

# --- (The rest of your code remains the same) ---
# --- Define the application and the request model ---

app = FastAPI(
    title="Facial Analysis API",
    description="An API that uses DeepFace and face_recognition to analyze facial attributes from an image."
)

class ImagePayload(BaseModel):
    image_data: str 

# --- 1. REVISED: Startup event with a more robust pre-loader ---
def clean_numpy(data):
    if isinstance(data, dict):
        return {k: clean_numpy(v) for k, v in data.items()}
    elif isinstance(data, list):
        return [clean_numpy(v) for v in data]
    elif isinstance(data, (np.integer, np.floating)):
        return data.item()
    return data
    
@app.on_event("startup")
async def load_all_models():
    """
    This function runs once when the application starts.
    It pre-loads all the necessary models into memory by simulating
    a real analysis call.
    """
    print("🚀 Server is starting up, pre-loading all models...")
    
    # Create a dummy image to trigger the model loading
    dummy_image = np.zeros((100, 100, 3), dtype=np.uint8)
    
    # Use a try...except block because DeepFace will raise an error
    # when it can't find a face in the blank image. This is expected.
    # The goal is not to get a result, but to force the models to load.
    try:
        DeepFace.analyze(
            dummy_image,
            actions=['age', 'gender', 'emotion', 'race'],
        )
    except ValueError as e:
        # This error is expected on a blank image, we can safely ignore it.
        print(f"   -> Ignoring expected error on dummy image: {e}")

    # Also warm up the face_recognition library
    face_recognition.face_locations(dummy_image)
    
    print("✅ All models have been pre-loaded and are ready.")


# --- (Utility functions and API endpoints remain the same) ---

def base64_to_cv2_image(base64_string: str):
    if "," in base64_string:
        header, encoded_data = base64_string.split(",", 1)
    else:
        encoded_data = base64_string
    image_bytes = base64.b64decode(encoded_data)
    pil_image = Image.open(io.BytesIO(image_bytes))
    return cv2.cvtColor(np.array(pil_image), cv2.COLOR_BGR2RGB)

@app.post("/analyze/")
async def analyze_face(payload: ImagePayload):
    frame = base64_to_cv2_image(payload.image_data)
    rgb_frame = frame[:, :, ::-1]  # Convert BGR to RGB
    face_locations = face_recognition.face_locations(rgb_frame)
    results = []

    if not face_locations:
        return {"message": "No faces found in the image.", "results": []}

    for face_location in face_locations:
        top, right, bottom, left = face_location
        face_image = frame[top:bottom, left:right]

        try:
            analysis = DeepFace.analyze(
                face_image,
                actions=["age", "gender", "emotion", "race"],
                enforce_detection=False
            )

            # Handle if it's a list or a single result
            if isinstance(analysis, list) and len(analysis) > 0:
                face_data = analysis[0]
            elif isinstance(analysis, dict):
                face_data = analysis
            else:
                face_data = {}

            result = {
                "face_location": {
                    "top": int(top),
                    "right": int(right),
                    "bottom": int(bottom),
                    "left": int(left)
                },
                "age": int(face_data.get("age", -1)),
                "dominant_gender": face_data.get("dominant_gender"),
                "gender_scores": clean_numpy(face_data.get("gender", {})),
                "dominant_emotion": face_data.get("dominant_emotion"),
                "emotion_scores": clean_numpy(face_data.get("emotion", {})),
                "dominant_race": face_data.get("dominant_race"),
                "race_scores": clean_numpy(face_data.get("race", {})),
                "face_confidence": clean_numpy(face_data.get("face_confidence", 0.0)),
                "region": clean_numpy(face_data.get("region", {}))
            }

            results.append(result)

        except Exception as e:
            print(f"DeepFace analysis error: {e}")
            results.append({
                "face_location": {
                    "top": int(top),
                    "right": int(right),
                    "bottom": int(bottom),
                    "left": int(left)
                },
                "error": str(e)
            })

    return {
        "message": f"Successfully analyzed {len(results)} face(s).",
        "results": results
    }

@app.get("/")
def read_root():
    return {"status": "ok", "message": "Facial Analysis API is running."}


if __name__ == "__main__":
    # This block allows you to run the app directly with: python main.py
	uvicorn.run(app, host="0.0.0.0", port=8003)