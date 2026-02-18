# Chat Refactor Phase 2 — Image Drop, Agent7 Bridge, Memory Sharing

## Context

The chat system has matured through prior refactoring phases (template composition, keyframe persistence, MCP context, policy evaluation, auto-tuning). This new phase adds three capabilities:

1. **Image drag/drop** — Users can drop images into chat, which get uploaded, auto-tagged, and embedded as MCP context so the LLM "sees" a description even before vision model integration.
2. **Agent7 bridge** — Connect Agent7's ChainExecutor into the chat pipeline so tagged ("Agent Aware") objects can be searched/summarized/described by agentic chains, with results returned as MCP context. Also refactor the summarize service to use map-reduce for large documents.
3. **Memory sharing** — Users can browse and share memories across conversations; the system character automatically recalls relevant memories before each response.

---

## Phase 1: Image Drag/Drop in Chat

### 1.1 Client — chat.js

**File:** `AccountManagerUx7/client/view/chat.js`

**State variables** added near existing `showTagSelector`/`selectedImageTags`:
```js
let dragActive = false;
let uploadingImage = false;
```

**Drag/drop handlers** on the outer container div in `getChatBottomMenuView()`:
- `ondragover` / `ondragenter` → prevent default, set `dragActive = true`
- `ondragleave` → `dragActive = false`
- `ondrop` → `dragActive = false; handleImageDrop(e);`
- Visual: purple ring (`ring-2 ring-purple-500`) when `dragActive` is true
- Upload spinner: `hourglass_top` icon shown in feature tools when `uploadingImage`

**`readFileAsBase64(file)`** — Promise wrapper around `FileReader.readAsDataURL()`, strips data URI prefix.

**`handleImageDrop(e)`** — full flow:
1. Validate: must have active chat instance, file must be `image/*`
2. Read file as base64 via `readFileAsBase64()`
3. Upload to `~/Gallery/Uploads` using `page.makePath()` + `page.createObject()` with `am7model.newPrimitive("data.data")` (matches `handleAudioSave()` pattern)
4. Call `GET /rest/tag/{objectId}` via `m.request()` to get tags + captions from `TagService`
5. Build token `${image.OBJECTID.tag1,tag2,...}` and insert into chat input field
6. Toast success/failure; graceful degradation if tagging service unavailable

**Reuses:** `handleAudioSave()` (line 1446), `insertImageToken()` (line 364), `am7imageTokens` system, `ChatTokenRenderer.processImageTokens()`

### 1.2 Server — Image MCP Context Injection

**File:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java`

**`buildImageMcpContext(BaseRecord user, String message)`** — scans message for `${image.OBJECTID.TAGS}` tokens using regex:
1. Load `data.data` record by objectId via AccessPoint (PBAC-enforced)
2. Get image bytes via `ByteModelUtil.getValue()` or thumbnail
3. Call `ImageTagUtil.tagImageBase64()` for tags and captions
4. Build MCP context via `McpContextBuilder.addResource()` with schema `urn:am7:media:image-description` containing: objectId, tags, first caption (longer description), full tag list
5. Return built context string

**Call site:** In `continueChat()`, after receiving user message, before LLM call — append MCP context so LLM sees image description adjacent to token.

**Fallback:** If `ImageTagUtil` unavailable or tagging fails, log warning and continue without MCP context. Image token still renders visually via client-side `ChatTokenRenderer`.

**Key dependencies:**
- `ImageTagUtil` (Objects7/tools/)
- `McpContextBuilder.addResource()` (Objects7/mcp/)
- `ByteModelUtil.getValue()` (Objects7/io/)

### 1.3 Tests

**Java unit test:** `AccountManagerObjects7/src/test/java/.../tests/TestImageMcpContext.java`
- Extends `BaseTest`
- Uses `media/sunflower.jpg` from Objects7/media as test image
- Tests: MCP context contains `<mcp:context`, `urn:am7:media:image-description`, caption text
- Tests: graceful fallback for non-existent objectId

**JS UX test:** `imageDrop` category in `llmTestSuite.js`
- Tests: `readFileAsBase64()` with synthetic blob
- Tests: token insertion, drag event handlers, upload flow

---

## Phase 2a: Agent7 Integration into Service7

### 2a.1 Maven Dependency

**File:** `AccountManagerService7/pom.xml`

Add `AccountManagerAgent7` as dependency after existing `AccountManagerObjects7` dependency.

### 2a.2 REST Endpoint — Wire ChainExecutor

**File:** `AccountManagerService7/src/main/java/org/cote/rest/services/ChatService.java`

Replace placeholder `/chain` endpoint (line 525-543):
1. Parse `planQuery` from request JSON
2. Instantiate `AgentToolManager` with `AM7AgentTool` for the user
3. If request has pre-built plan, import it; otherwise `AgentToolManager.createChainPlan(planQuery)`
4. Create `ChainExecutor`, execute plan
5. Convert result to MCP context via `McpContextBuilder`, return

### 2a.3 WebSocket — Wire ChainExecutor

**File:** `AccountManagerService7/src/main/java/org/cote/sockets/WebSocketService.java`

In `handleChainRequest()`:
1. `CompletableFuture.runAsync()` to avoid blocking WebSocket thread
2. Create `AgentToolManager` + `ChainExecutor` for user
3. Set up `IChainEventListener` chirping progress events via `chirpUser()`
4. Generate plan, execute chain
5. Chirp final result as MCP context on completion

### 2a.4 Agentic Chat Bridge

**File:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java`

**`detectAndRouteAgentic(BaseRecord user, BaseRecord chatConfig, String message)`**:
1. Check chatConfig for agentic flag or `Agent Aware` tag presence
2. Search for objects tagged "Agent Aware" authorized for user (via `DocumentUtil.getCreateTag()` + AccessPoint query)
3. Build ChainExecutor plan using existing tools (vectorize/summarize, searchMemories, image descriptions)
4. Execute chain, collect results as MCP context blocks
5. Return MCP context string appended before main LLM call

**Flow:** ChatService/WebSocket → `Chat.continueChat()` → `detectAndRouteAgentic()` → ChainExecutor → MCP results → appended to message → main LLM call

### 2a.5 Tests

**`AccountManagerAgent7/src/test/.../TestAgentChatBridge.java`**
- Extends BaseTest, uses `TestAgent.getChatConfig()` pattern
- Creates test objects tagged "Agent Aware"
- Executes agentic bridge, asserts MCP context returned

**`AccountManagerAgent7/src/test/.../TestChainService.java`**
- Full chain flow: planQuery → plan generation → execution → MCP result
- Uses existing `TestChainExecutor` patterns

---

## Phase 2b: Summarize Refactor (Map-Reduce)

### 2b.1 Refactor composeSummary()

**File:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/ChatUtil.java`

Replace sequential `composeSummary()` (line 825-952) with hierarchical map-reduce:

**New implementation — same method signature for backward compatibility:**

1. **`loadChunks(user, ref)`** — extracted from existing lines 831-847; loads vector store chunks for the reference record, returns `List<String>` of content strings

2. **Map phase — `mapSummarize(user, chatConfig, promptConfig, chunks, remote, batchSize)`**:
   - Split chunks into batches of 5
   - Each batch: parallel `CompletableFuture.supplyAsync()` calls
   - Each parallel task creates its own `Chat(user, chatConfig, promptConfig)` instance (Chat is not thread-safe)
   - Each task calls `summarizeChunk(content, chat, remote)` (extracted from existing lines 876-913)
   - Collect results with 120s timeout per chunk

3. **Reduce phase — `reduceSummaries(user, chatConfig, promptConfig, summaries, remote, batchSize)`**:
   - While `summaries.size() > 1`: batch summaries, merge each batch via LLM call
   - Each reduce creates own Chat instance
   - Guard: `MAX_REDUCE_DEPTH = 5` prevents infinite recursion
   - Each reduce pass: "Create a summary from the following summaries using 1000 words or less"

4. Return final summary list (same format as before)

**Thread safety:** Each parallel task creates its own `Chat` instance. `IOSystem` and DB connections use pooling and are thread-safe.

**`createSummary()` (line 724) and `VectorService`** continue to work unchanged.

### 2b.2 Content Type Handling

- Verify `VectorUtil.createVectorStore()` extracts text from PDFs and DOCX before chunking
- `chunkByChapter()` strategy for fiction; `chunkByWord()` for general text
- Test with Objects7/media files: The Verse.docx (large fiction), CardFox.pdf/txt

### 2b.3 Tests

**`AccountManagerObjects7/src/test/.../tests/TestMapReduceSummary.java`**
- Extends `BaseTest`, uses `OlioTestUtil.getChatConfig()` for LLM config
- Tests: CardFox.txt (known text), CardFox.pdf (PDF extraction), The Verse.docx (large document with multiple reduce passes)
- Asserts: non-null summary, reasonable length (< 2000 words), contains key themes
- Edge cases: single chunk, empty content, very small content

---

## Phase 3: Memory Enhancement

### 3.1 Memory Browser Panel — Client

**File:** `AccountManagerUx7/client/components/chat/MemoryPanel.js`

**State variables:**
```js
let viewMode = "pair";    // "pair" | "character" | "search"
let characterMemories = [];
let characterLoading = false;
```

**Mode selector** in panel header:
- "Pair" — current character pair memories (existing behavior)
- "Character" — all memories for system character across conversations
- "Search" — cross-conversation discovery via search

**`loadForCharacter(personObjectId)`** — calls `GET /rest/memory/person/{objectId}/50`

**`memoryListView()`** updated to switch data source based on `viewMode`

**"Share" button** on memory items in character/search modes:
- `shareMemoryToCurrentPair(mem)` calls `POST /rest/memory/create` with:
  - `content`, `summary`, `memoryType`, `importance` from source memory
  - `person1ObjectId`, `person2ObjectId` from current chatConfig characters
  - `conversationId` from current chatConfig
- Refreshes pair view after sharing; toast success/failure

### 3.2 System Auto-Recall Enhancement

**File:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java`

Existing auto-recall (`retrieveRelevantMemories()`) has Layer 1 (pair) and Layer 2 (cross-pair). Add **Layer 3: Semantic relevance**:

1. Get last user message from current request
2. Call `MemoryUtil.searchMemories(user, lastUserMessage, 5, 0.6)` — vector similarity
3. Deduplicate against Layer 1/2 results
4. Add matches to scored list with freshness-decayed scores
5. Budget: 10% stolen from Layer 2

**File:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/util/MemoryUtil.java`

**`searchMemoriesByPersonAndQuery(user, queryText, personId, limit, threshold)`**:
- Combines person-filtered query with vector similarity
- Returns memories where personId matches AND similarity > threshold

### 3.3 Tests

**`AccountManagerObjects7/src/test/.../tests/TestMemorySharing.java`**
- Two character pairs: (Alice, Bob) and (Charlie, Bob)
- Creates memories per pair via `MemoryUtil.createMemory()`
- Tests: cross-pair retrieval via `searchMemoriesByPerson()` for Bob
- Tests: sharing (Alice,Bob) memory to (Charlie,Bob) pair
- Tests: auto-recall returns memories from both pairs
- Tests: semantic search returns relevant match

**`AccountManagerAgent7/src/test/.../TestMemoryAutoRecall.java`**
- Full auto-recall flow: create Chat, inject memories, `refreshSystemPrompt()`
- Assert: memory MCP context in system prompt
- Uses `McpTestUtil` patterns

**JS UX test:** `memoryBrowser` category in `llmTestSuite.js`
- Mode toggle, character loading, sharing flow, share button visibility

---

## Files Modified

| File | Phase | Changes |
|------|-------|---------|
| `AccountManagerUx7/client/view/chat.js` | 1 | Drag/drop handlers, handleImageDrop(), readFileAsBase64(), upload indicator |
| `AccountManagerObjects7/.../olio/llm/Chat.java` | 1, 2a, 3 | buildImageMcpContext(), detectAndRouteAgentic(), Layer 3 in retrieveRelevantMemories() |
| `AccountManagerObjects7/.../olio/llm/ChatUtil.java` | 2b | Refactor composeSummary() to map-reduce: loadChunks(), mapSummarize(), reduceSummaries() |
| `AccountManagerObjects7/.../util/MemoryUtil.java` | 3 | searchMemoriesByPersonAndQuery() |
| `AccountManagerService7/pom.xml` | 2a | Add Agent7 dependency |
| `AccountManagerService7/.../rest/services/ChatService.java` | 2a | Wire ChainExecutor into /chain endpoint |
| `AccountManagerService7/.../sockets/WebSocketService.java` | 2a | Wire ChainExecutor into handleChainRequest() |
| `AccountManagerUx7/client/components/chat/MemoryPanel.js` | 3 | View mode toggle, character memory loading, share button |
| `AccountManagerUx7/client/test/llm/llmTestSuite.js` | 1, 3 | imageDrop and memoryBrowser test categories |

## New Files

| File | Phase | Purpose |
|------|-------|---------|
| `AccountManagerObjects7/src/test/.../tests/TestImageMcpContext.java` | 1 | Image upload + MCP context unit tests |
| `AccountManagerObjects7/src/test/.../tests/TestMapReduceSummary.java` | 2b | Map-reduce summarization tests |
| `AccountManagerObjects7/src/test/.../tests/TestMemorySharing.java` | 3 | Cross-conversation memory sharing tests |
| `AccountManagerAgent7/src/test/.../TestAgentChatBridge.java` | 2a | Agentic bridge integration tests |
| `AccountManagerAgent7/src/test/.../TestMemoryAutoRecall.java` | 3 | Auto-recall flow test |

## Key Existing Code Reused

| Utility | Location | Used For |
|---------|----------|----------|
| `ImageTagUtil.tagImageBase64()` | Objects7/tools/ImageTagUtil.java | Phase 1 — tags/captions |
| `McpContextBuilder.addResource()` | Objects7/mcp/McpContextBuilder.java | All phases — MCP blocks |
| `MemoryUtil.formatMemoriesAsContext()` | Objects7/util/MemoryUtil.java | Phase 3 — memories as MCP |
| `MemoryUtil.createMemory()` | Objects7/util/MemoryUtil.java | Phase 3 — persist shared memories |
| `MemoryUtil.searchMemories()` | Objects7/util/MemoryUtil.java | Phase 3 — vector search |
| `handleAudioSave()` pattern | Ux7/client/view/chat.js | Phase 1 — file upload pattern |
| `am7imageTokens.parse/resolve` | Ux7 global | Phase 1 — token system |
| `OlioTestUtil.getChatConfig()` | Objects7/test/OlioTestUtil.java | Tests — LLM config |
| `McpTestUtil` | Objects7/test/McpTestUtil.java | Tests — MCP assertions |
| `TestAgent.getChatConfig()` | Agent7/test/TestAgent.java | Tests — Agent config |
| `ChainExecutor` | Agent7/agent/ChainExecutor.java | Phase 2a — execution |
| `AgentToolManager` | Agent7/agent/AgentToolManager.java | Phase 2a — tool discovery |
| `DocumentUtil.getCreateTag()` | Objects7/util/DocumentUtil.java | Phase 2a — tagging |

## Verification

### Unit Tests
```bash
# Phase 1
cd AccountManagerObjects7 && mvn test -Dtest=TestImageMcpContext

# Phase 2a
cd AccountManagerAgent7 && mvn test -Dtest=TestAgentChatBridge,TestChainService

# Phase 2b
cd AccountManagerObjects7 && mvn test -Dtest=TestMapReduceSummary

# Phase 3
cd AccountManagerObjects7 && mvn test -Dtest=TestMemorySharing
cd AccountManagerAgent7 && mvn test -Dtest=TestMemoryAutoRecall
```

### UX Tests
Browser test harness → LLM suite → categories: `imageDrop`, `memoryBrowser`

### Manual E2E
1. **Image drop:** Drop JPG into chat → token inserted → send → LLM references image content
2. **Agent bridge:** Enable agentic config, tag objects "Agent Aware" → query → chain chirps → MCP results
3. **Summarize:** Large document → parallel chunks → merged summary
4. **Memory share:** Character mode in memory panel → see cross-conversation memories → Share → LLM references in next response
