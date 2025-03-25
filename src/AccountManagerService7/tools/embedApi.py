from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from keybert import KeyBERT
from transformers import pipeline
from nltk.tokenize import word_tokenize
from sklearn.feature_extraction.text import TfidfVectorizer
import uvicorn
import nltk
import spacy
import time

# Download necessary NLTK data
nltk.download('punkt')

# Load models
model = SentenceTransformer("all-mpnet-base-v2")
keyword_extractor = KeyBERT(model)
summarizer = pipeline("summarization", model="facebook/bart-large-cnn")
sentiment_analyzer = pipeline("sentiment-analysis", model="cardiffnlp/twitter-roberta-base-sentiment-latest")
nlp = spacy.load("en_core_web_sm")

# Initialize FastAPI app
app = FastAPI()

# Request models
class EmbeddingRequest(BaseModel):
    text: str
    class Config:
        anystr_strip=False

class KeywordRequest(BaseModel):
    text: str
    top_n: int = 5
    class Config:
        anystr_strip=False

class SummaryRequest(BaseModel):
    text: str
    max_length: int = 130
    min_length: int = 30
    class Config:
        anystr_strip=False

class SentimentRequest(BaseModel):
    text: str
    class Config:
        anystr_strip=False

class NERRequest(BaseModel):
    text: str
    class Config:
        anystr_strip=False

class TokenizationRequest(BaseModel):
    text: str
    class Config:
        anystr_strip=False

class TopicModelingRequest(BaseModel):
    text: list[str]
    num_topics: int = 5
    class Config:
        anystr_strip=False

class GenerateTagsRequest(BaseModel):
    text: str
    num_keywords: int = 5
    num_topics: int = 5
    class Config:
        anystr_strip=False

# Response models
class EmbeddingResponse(BaseModel):
    embedding: list[float]

class KeywordResponse(BaseModel):
    keywords: list[str]

class SummaryResponse(BaseModel):
    summary: str

class SentimentResponse(BaseModel):
    sentiment: str
    confidence: float

class NERResponse(BaseModel):
    entities: list[dict]

class TokenizationResponse(BaseModel):
    tokens: list[str]

class TopicModelingResponse(BaseModel):
    topics: list[str]

class GenerateTagsResponse(BaseModel):
    tags: list[str]

@app.post("/generate_embedding", response_model=EmbeddingResponse)
def generate_embedding(request: EmbeddingRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    embedding = model.encode(request.text).tolist()
    return {"embedding": embedding}

@app.post("/extract_keywords", response_model=KeywordResponse)
def extract_keywords(request: KeywordRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    keywords = keyword_extractor.extract_keywords(request.text, keyphrase_ngram_range=(1, 2), top_n=request.top_n)
    return {"keywords": [kw[0] for kw in keywords]}

@app.post("/generate_summary", response_model=SummaryResponse)
def generate_summary(request: SummaryRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    summary = summarizer(request.text, max_length=request.max_length, min_length=request.min_length, do_sample=False)
    return {"summary": summary[0]["summary_text"]}

@app.post("/analyze_sentiment", response_model=SentimentResponse)
def analyze_sentiment(request: SentimentRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    result = sentiment_analyzer(request.text)[0]
    return {"sentiment": result["label"], "confidence": result["score"]}

@app.post("/named_entity_recognition", response_model=NERResponse)
def named_entity_recognition(request: NERRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    doc = nlp(request.text)
    entities = [{"text": ent.text, "label": ent.label_} for ent in doc.ents]
    return {"entities": entities}

@app.post("/tokenize_text", response_model=TokenizationResponse)
def tokenize_text(request: TokenizationRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")
    
    tokens = word_tokenize(request.text)
    return {"tokens": tokens}

@app.post("/topic_modeling", response_model=TopicModelingResponse)
def topic_modeling(request: TopicModelingRequest):
    if not request.text:
        raise HTTPException(status_code=400, detail="Input text list cannot be empty.")
    
    vectorizer = TfidfVectorizer(stop_words="english")
    X = vectorizer.fit_transform(request.text)
    terms = vectorizer.get_feature_names_out()
    top_terms = [terms[i] for i in X.sum(axis=0).argsort()[0, -request.num_topics:].tolist()]
    
    return {"topics": top_terms}

@app.post("/generate_tags")
def generate_tags(request: GenerateTagsRequest):
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Input text cannot be empty.")

    # Extract keywords using KeyBERT
    keywords = keyword_extractor.extract_keywords(
        request.text, keyphrase_ngram_range=(1, 2), top_n=request.num_keywords
    )
    keyword_list = [kw[0] for kw in keywords]  # Extract keyword text

    # Perform TF-IDF vectorization
    vectorizer = TfidfVectorizer(stop_words="english")
    X = vectorizer.fit_transform([request.text])

    # Get feature names as a normal list
    terms = vectorizer.get_feature_names_out().tolist()  

    # Convert NumPy array to a normal list to avoid unhashable type error
    top_term_indices = X.sum(axis=0).A1.argsort()[-request.num_topics:].tolist()
    top_terms = [terms[i] for i in top_term_indices]  # Extract top terms

    # Ensure everything is a string and remove duplicates
    tags = list(set(keyword_list + top_terms))  # Combine and deduplicate

    return {"tags": tags}

@app.get("/heartbeat")
def heartbeat():
    return {"status": True, "time": int(time.time())}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8123)
