# Issue #14: Memory Construction / Ollama Infinite Loops — Investigation Plan

## Problem Statement
Memory extraction during chat conversations causes infinite loops in Ollama. The root cause is likely an inappropriate model selection for memory extraction, combined with architectural issues that allow runaway LLM calls.

## Environment
- **Test Host**: DGX Spark
- **Constraint**: Single worker only — do NOT spawn multiple threads
- **Backend**: All services live at localhost:8443

---

## Phase 1: Reproduce & Diagnose (Unit Tests)

### 1a. Run Existing Memory Tests
```bash
# From AccountManagerObjects7/
mvn test -pl AccountManagerObjects7 -Dtest=TestMemExtract
mvn test -pl AccountManagerObjects7 -Dtest=TestMemoryPhase2
mvn test -pl AccountManagerObjects7 -Dtest=TestKeyframeMemory
mvn test -pl AccountManagerObjects7 -Dtest=TestMemoryDuel
```
**Goal**: Establish baseline — which tests pass, which hang, which fail.

### 1b. Add Timeout to Memory Extraction LLM Call
- **File**: `MemoryUtil.java` (extractMemoriesFromResponse)
- Add a hard timeout (e.g., 60s) around the LLM call for extraction
- If timeout is hit, log the request payload for analysis
- This prevents infinite hangs during testing

### 1c. Log LLM Request/Response for Extraction
- **File**: `Chat.java` (forceExtractMemories)
- Log the full prompt sent to the LLM for memory extraction
- Log the raw response text before JSON parsing
- Check if the response is valid JSON or if the model is producing malformed output that triggers retry loops

---

## Phase 2: Worker Blocking Verification

### 2a. Verify Single-Worker Semaphore Behavior
- **Files**: `ChatListener.java`, `ChatUtil.java` (both import `Semaphore`)
- **Test**: Start one long-running LLM call (e.g., a chat message)
- **Then**: Attempt a second LLM call (e.g., memory extraction)
- **Expected**: Second call should block until first completes
- **Verify**: No deadlock occurs when both calls target the same Ollama instance with 1 worker

### 2b. Check LLMConnectionManager Concurrency
- **File**: `LLMConnectionManager.java`
- Verify the `ConcurrentHashMap` for streaming futures doesn't leak entries
- Check if `CompletableFuture.runAsync()` calls have proper timeout (leak detection exists but verify threshold)

### 2c. Verify Ollama Thread Safety
- With `numGpu=1` and a single context, concurrent requests to Ollama will serialize
- Test: Send two simultaneous requests via REST — verify they queue, not crash
- Monitor Ollama logs for OOM or context exhaustion errors

---

## Phase 3: Root Cause Analysis

### 3a. Model Appropriateness
- **Current**: Check which model is configured for memory extraction in the chatConfig
- **Issue**: Smaller/quantized models may not reliably produce valid JSON for memory extraction
- **Test**: Try extraction with known-good model (e.g., llama3.1:8b-instruct) vs current model
- Compare JSON output quality

### 3b. Extraction Prompt Issues
- **V1 prompt**: `olio/llm/prompts/memoryExtraction.json` — asks for ALL 10 types at once
- **V2 prompt**: `olio/llm/prompts/memoryExtractionV2.json` — more focused
- **Test**: Run extraction with V2 prompt only, check if infinite loop still occurs
- Consider limiting to 3-5 memory types per extraction call

### 3c. JSON Parsing Fallback Cascade
- **File**: `MemoryUtil.java` lines 454-471
- The extractor handles 3+ output formats (JSON, text-based lists, markdown-fenced JSON)
- 6+ regex patterns for fallback parsing
- **Risk**: If parsing fails silently, the system may retry extraction indefinitely
- **Fix**: Add extraction attempt counter, hard-cap at 3 retries per keyframe

### 3d. No Total Memory Throttle
- **Finding**: No cap on total memory count per conversation
- If the model keeps producing memories, extraction continues without limit
- **Fix**: Add `maxMemoriesPerConversation` config field, default 50

---

## Phase 4: Deduplication & Noise Reduction

### 4a. Cosine Similarity Threshold
- Current: 0.92 (very tight — near-duplicates slip through)
- **Test**: Lower to 0.85, measure dedup hit rate
- **File**: `MemoryUtil.java` lines 563-607

### 4b. Jaccard Text Similarity
- Current: 0.85
- Works as fallback when vector similarity unavailable
- Consider raising to 0.80 for more aggressive dedup

### 4c. Extraction Rate Limiting
- `keyframeEvery=20`: A 50-message conversation triggers 2-3 extractions
- `memoryExtractionMaxPerSegment=1`: Limits to 1 memory per segment
- **Verify** these limits are actually respected in the code path

---

## Phase 5: Specific Tests to Write

### Unit Test: Extraction Timeout
```java
@Test
public void testExtractionDoesNotHang() {
    // Configure chatConfig with extractMemories=true, keyframeEvery=5
    // Send 10 messages to trigger extraction
    // Assert extraction completes within 120 seconds
    // Assert no infinite loop (check LLM call count <= 5)
}
```

### Unit Test: Worker Blocking
```java
@Test
public void testSingleWorkerBlocking() {
    // Set worker count to 1
    // Start async LLM call A
    // Start async LLM call B
    // Verify B waits for A (no concurrent execution)
    // Verify both complete without deadlock
}
```

### Unit Test: Malformed JSON Recovery
```java
@Test
public void testMalformedExtractionResponse() {
    // Mock LLM to return invalid JSON for extraction
    // Verify extraction fails gracefully (no retry loop)
    // Verify error is logged with request payload
}
```

### Unit Test: Memory Count Cap
```java
@Test
public void testMemoryCountCap() {
    // Set maxMemoriesPerConversation=10
    // Run extraction that would produce 20+ memories
    // Verify only 10 are persisted
}
```

---

## Phase 6: Fixes (After Diagnosis)

Based on investigation findings, likely fixes include:

1. **Hard timeout** on all memory extraction LLM calls (60s)
2. **Retry cap** on extraction attempts per keyframe (max 3)
3. **Total memory cap** per conversation (configurable, default 50)
4. **Model validation** — ensure configured model can produce valid JSON
5. **Semaphore audit** — verify single-worker mode truly blocks concurrent calls
6. **V2 prompt only** — deprecate V1 prompt that requests all 10 types
7. **Extraction attempt logging** — log every extraction request/response for debugging

---

## Files to Examine

| File | Purpose |
|------|---------|
| `MemoryUtil.java` | Core extraction, dedup, vector embedding |
| `Chat.java` | Chat flow, keyframe triggers, extraction calls |
| `ChatUtil.java` | Session management, semaphore |
| `ChatListener.java` | WebSocket chat handler, semaphore |
| `LLMConnectionManager.java` | HTTP client registry, streaming futures |
| `OllamaOptions.java` | Ollama-specific config (numGpu, numCtx) |
| `MemoryService.java` | REST endpoints for memory operations |
| `memoryExtractionV2.json` | V2 extraction prompt template |
| `TestMemExtract.java` | Existing extraction unit tests |
| `TestKeyframeMemory.java` | Keyframe + memory lifecycle tests |

## Execution Order
1. Run existing tests to establish baseline (Phase 1a)
2. Add logging/timeouts (Phase 1b-1c)
3. Test single-worker blocking (Phase 2)
4. Identify root cause from logs (Phase 3)
5. Write targeted tests (Phase 5)
6. Apply fixes (Phase 6)
