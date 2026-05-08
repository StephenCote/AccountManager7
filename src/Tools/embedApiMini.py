from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import uvicorn

import time

# Load models
model = SentenceTransformer("all-mpnet-base-v2")

# Initialize FastAPI app
app = FastAPI()

# Request models
class EmbeddingRequest(BaseModel):
    text: str
    class Config:
        anystr_strip=False

# Response models
class EmbeddingResponse(BaseModel):
    embedding: list[float]

@app.post("/generate_embedding", response_model=EmbeddingResponse)
def generate_embedding(request: EmbeddingRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    embedding = model.encode(request.text).tolist()
    return {"embedding": embedding}

@app.get("/heartbeat")
def heartbeat():
    return {"status": True, "time": int(time.time())}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8123)
