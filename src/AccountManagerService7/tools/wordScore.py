from fastapi import FastAPI
from pydantic import BaseModel
from transformers import GPT2LMHeadModel, GPT2TokenizerFast
import uvicorn
import torch

# ----------------------------
# Load model once at startup
# ----------------------------
model_id = "distilgpt2"
tokenizer = GPT2TokenizerFast.from_pretrained(model_id)
model = GPT2LMHeadModel.from_pretrained(model_id)
model.eval()

# ----------------------------
# FastAPI setup
# ----------------------------
app = FastAPI(title="Phrase Scoring API")

class PhraseRequest(BaseModel):
    phrases: list[str]

class PhraseScore(BaseModel):
    phrase: str
    score: float

class PhraseResponse(BaseModel):
    scores: list[PhraseScore]

def score_phrase(phrase: str) -> float:
    encodings = tokenizer(phrase, return_tensors="pt")
    with torch.no_grad():
        outputs = model(**encodings, labels=encodings["input_ids"])
        return outputs.loss.item()  # average cross-entropy per token

@app.post("/score", response_model=PhraseResponse)
async def score_phrases(request: PhraseRequest):
    results = [{"phrase": p, "score": score_phrase(p)} for p in request.phrases]
    return {"scores": results}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8004)