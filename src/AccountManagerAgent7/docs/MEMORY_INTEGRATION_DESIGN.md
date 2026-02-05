# Refactor Design: Automatic Memory Integration in Conversations

## Overview

This design describes how to automatically weave relevant memories into chat conversations, enabling characters to "remember" past interactions, discoveries, and insights.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Chat.continueChat()                        │
├─────────────────────────────────────────────────────────────────┤
│  1. checkAction(req, message)                                   │
│  2. ┌─────────────────────────────────────────────────────────┐ │
│     │  NEW: MemoryInjector.injectRelevantMemories()           │ │
│     │       - Search memories by semantic similarity          │ │
│     │       - Filter by character, recency, importance        │ │
│     │       - Format as context and inject into system prompt │ │
│     └─────────────────────────────────────────────────────────┘ │
│  3. newMessage(req, message)                                    │
│  4. chat(req) → LLM inference                                   │
│  5. handleResponse(req, lastRep)                                │
│  6. ┌─────────────────────────────────────────────────────────┐ │
│     │  NEW: MemoryExtractor.extractAndStore()                 │ │
│     │       - Analyze conversation for memorable content      │ │
│     │       - Create memory records with vectors              │ │
│     └─────────────────────────────────────────────────────────┘ │
│  7. saveSession(req)                                            │
└─────────────────────────────────────────────────────────────────┘
```

## Key Components

### 1. MemoryInjector (new class in Agent7)

**File:** `AccountManagerAgent7/src/main/java/org/cote/accountmanager/agent/MemoryInjector.java`

```java
public class MemoryInjector {

    /**
     * Injects relevant memories into the chat request before LLM inference.
     * Called at the start of continueChat() after checkAction().
     */
    public static void injectRelevantMemories(
            BaseRecord user,
            BaseRecord chatConfig,
            OpenAIRequest req,
            String currentMessage) {

        if (!isMemoryEnabled(chatConfig)) return;

        // 1. Build search context from current message + recent conversation
        String searchContext = buildSearchContext(req, currentMessage);

        // 2. Search memories semantically
        List<BaseRecord> memories = searchMemories(user, chatConfig, searchContext);

        // 3. Filter and rank by relevance
        memories = filterAndRank(memories, chatConfig);

        // 4. Format as context block
        String memoryContext = formatMemoryContext(memories);

        // 5. Inject into system message or as separate context message
        injectIntoRequest(req, memoryContext, chatConfig);
    }

    private static List<BaseRecord> searchMemories(
            BaseRecord user, BaseRecord chatConfig, String context) {

        // Get character-specific memories
        BaseRecord sysChar = chatConfig.get("systemCharacter");
        BaseRecord usrChar = chatConfig.get("userCharacter");

        List<BaseRecord> results = new ArrayList<>();

        // Semantic search with VectorUtil
        results.addAll(MemoryUtil.searchMemories(user, context, 10, 0.7));

        // Also get recent conversation memories between these characters
        // (by sourceUri or character tags)

        return results;
    }

    private static List<BaseRecord> filterAndRank(
            List<BaseRecord> memories, BaseRecord chatConfig) {

        int maxMemories = chatConfig.get("maxMemoryContext", 5);
        int maxTokens = chatConfig.get("maxMemoryTokens", 500);

        return memories.stream()
            // Sort by: importance * recency * semantic_score
            .sorted(Comparator.comparingDouble(m ->
                -calculateRelevanceScore(m)))
            // Limit count
            .limit(maxMemories)
            // Limit total tokens
            .collect(tokenLimitedCollector(maxTokens));
    }

    private static void injectIntoRequest(
            OpenAIRequest req, String memoryContext, BaseRecord chatConfig) {

        if (memoryContext.isEmpty()) return;

        String injectionMode = chatConfig.get("memoryInjectionMode", "system_append");

        switch (injectionMode) {
            case "system_append":
                // Append to existing system message
                appendToSystemMessage(req, memoryContext);
                break;
            case "context_message":
                // Add as separate "context" role message (before user)
                insertContextMessage(req, memoryContext);
                break;
            case "user_prefix":
                // Prefix the user message with context
                prefixUserMessage(req, memoryContext);
                break;
        }
    }
}
```

### 2. MemoryExtractor (new class in Agent7)

**File:** `AccountManagerAgent7/src/main/java/org/cote/accountmanager/agent/MemoryExtractor.java`

```java
public class MemoryExtractor {

    /**
     * Extracts memorable content from completed conversation turns.
     * Called after handleResponse() in continueChat().
     */
    public static void extractAndStore(
            BaseRecord user,
            BaseRecord chatConfig,
            OpenAIRequest req,
            OpenAIResponse resp) {

        if (!isExtractionEnabled(chatConfig)) return;

        String extractionMode = chatConfig.get("memoryExtractionMode", "periodic");

        switch (extractionMode) {
            case "every_turn":
                extractFromLastTurn(user, chatConfig, req, resp);
                break;
            case "periodic":
                // Every N turns
                if (shouldExtractNow(req, chatConfig)) {
                    extractFromRecentTurns(user, chatConfig, req);
                }
                break;
            case "on_save":
                // Handled in saveSession(), not here
                break;
            case "llm_triggered":
                // LLM explicitly creates memories via tool call
                break;
        }
    }

    private static void extractFromLastTurn(
            BaseRecord user, BaseRecord chatConfig,
            OpenAIRequest req, OpenAIResponse resp) {

        // Get the assistant's last response
        BaseRecord msg = resp.get("message");
        String content = msg.get("content");

        // Quick heuristic check: is this worth remembering?
        if (!isMemoryWorthy(content)) return;

        // Create memory with auto-classification
        MemoryTypeEnumType type = classifyMemoryType(content);
        int importance = estimateImportance(content, chatConfig);

        String conversationId = getConversationId(chatConfig, req);
        String sourceUri = buildSourceUri(chatConfig);

        MemoryUtil.createMemory(
            user, content,
            summarize(content),  // Quick summary
            type, importance,
            sourceUri, conversationId
        );
    }

    private static boolean isMemoryWorthy(String content) {
        // Heuristics:
        // - Contains factual statements
        // - Mentions decisions or outcomes
        // - Describes character actions or behaviors
        // - Contains discoveries or insights
        // - Emotional significance

        // Could also use a fast classifier or keyword matching
        return content.length() > 50 &&
               !content.matches("(?i).*(hello|hi|bye|thanks).*");
    }

    private static MemoryTypeEnumType classifyMemoryType(String content) {
        // Rule-based or ML classification
        if (content.contains("learned") || content.contains("discovered")) {
            return MemoryTypeEnumType.DISCOVERY;
        }
        if (content.contains("decided") || content.contains("chose")) {
            return MemoryTypeEnumType.DECISION;
        }
        if (content.contains("failed") || content.contains("mistake")) {
            return MemoryTypeEnumType.ERROR_LESSON;
        }
        // Default
        return MemoryTypeEnumType.NOTE;
    }
}
```

### 3. ChatConfig Model Extensions

**File:** `AccountManagerObjects7/src/main/resources/models/olio/llm/chatConfigModel.json`

Add new fields:

```json
{
    "name": "useMemory",
    "type": "boolean",
    "default": false,
    "description": "Enable automatic memory injection and extraction"
},
{
    "name": "memoryInjectionMode",
    "type": "string",
    "maxLength": 32,
    "default": "system_append",
    "description": "How to inject memories: system_append, context_message, user_prefix"
},
{
    "name": "memoryExtractionMode",
    "type": "string",
    "maxLength": 32,
    "default": "periodic",
    "description": "When to extract memories: every_turn, periodic, on_save, llm_triggered"
},
{
    "name": "maxMemoryContext",
    "type": "int",
    "default": 5,
    "description": "Maximum number of memories to inject per turn"
},
{
    "name": "maxMemoryTokens",
    "type": "int",
    "default": 500,
    "description": "Maximum tokens for memory context"
},
{
    "name": "memoryExtractionInterval",
    "type": "int",
    "default": 5,
    "description": "Extract memories every N turns (for periodic mode)"
},
{
    "name": "memorySearchThreshold",
    "type": "double",
    "default": 0.7,
    "description": "Minimum semantic similarity for memory retrieval"
}
```

### 4. Integration Points in Chat.java

**File:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java`

Modify `continueChat()`:

```java
public void continueChat(OpenAIRequest req, String message) {
    if (deferRemote) {
        checkRemote(req, message, true);
        return;
    }

    OpenAIResponse lastRep = null;
    LineAction act = checkAction(req, message);
    if (act == LineAction.BREAK || act == LineAction.CONTINUE || act == LineAction.SAVE_AND_CONTINUE) {
        // ... existing handling
        return;
    }

    // NEW: Inject relevant memories before the user message
    if (chatConfig != null && chatConfig.get("useMemory", false)) {
        MemoryInjector.injectRelevantMemories(user, chatConfig, req, message);
    }

    if (message != null && message.length() > 0) {
        newMessage(req, message);
    }

    boolean stream = req.get("stream");

    if (!stream) {
        lastRep = chat(req);
        if (lastRep != null) {
            handleResponse(req, lastRep, false);

            // NEW: Extract memories from the response
            if (chatConfig != null && chatConfig.get("useMemory", false)) {
                MemoryExtractor.extractAndStore(user, chatConfig, req, lastRep);
            }
        } else {
            logger.warn("Last rep is null");
        }
        saveSession(req);
    } else {
        // Streaming path - extraction happens in oncomplete listener
        logger.info("Defer to async processing");
        chat(req);
    }
}
```

### 5. Memory Context Formatting

The injected memory context should be formatted clearly for the LLM:

```
<memories>
You have the following relevant memories from past interactions:

1. [DISCOVERY, importance=8, 2 days ago]
   Summary: Learned that the northern gate is unguarded at dawn
   Context: During reconnaissance mission with Kira

2. [DECISION, importance=7, 1 week ago]
   Summary: Agreed to help the merchant guild in exchange for supplies
   Context: Negotiation at the town square

3. [BEHAVIOR, importance=6, 3 days ago]
   Summary: Marcus tends to lie when cornered
   Context: Interrogation after the ambush
</memories>
```

### 6. Prompt Config Extensions

Add memory-related prompt templates to `promptConfigModel.json`:

```json
{
    "name": "memoryContextPrefix",
    "type": "string",
    "default": "You have the following relevant memories from past interactions:"
},
{
    "name": "memoryContextSuffix",
    "type": "string",
    "default": "Use these memories to inform your responses, but don't explicitly reference them unless directly relevant."
},
{
    "name": "memoryFormat",
    "type": "string",
    "default": "[{{memoryType}}, importance={{importance}}, {{age}}]\nSummary: {{summary}}\nContext: {{sourceContext}}"
}
```

## Memory Lifecycle

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   CREATE    │    │   RETRIEVE  │    │   PRUNE     │
├─────────────┤    ├─────────────┤    ├─────────────┤
│ On extract  │    │ On inject   │    │ Scheduled   │
│ from chat   │───▶│ before LLM  │───▶│ or manual   │
│             │    │ inference   │    │ cleanup     │
└─────────────┘    └─────────────┘    └─────────────┘
      │                  │                  │
      ▼                  ▼                  ▼
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ VectorUtil  │    │ VectorUtil  │    │ Consolidate │
│ .create()   │    │ .find()     │    │ & decay     │
└─────────────┘    └─────────────┘    └─────────────┘
```

## Configuration Example

```java
// Enable memory-aware chat
BaseRecord cfg = ChatUtil.getCreateChatConfig(user, "Memory Chat");
cfg.set("useMemory", true);
cfg.set("memoryInjectionMode", "system_append");
cfg.set("memoryExtractionMode", "periodic");
cfg.set("memoryExtractionInterval", 3);  // Every 3 turns
cfg.set("maxMemoryContext", 5);
cfg.set("memorySearchThreshold", 0.7);
```

## Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `MemoryInjector.java` | Create | Memory injection logic |
| `MemoryExtractor.java` | Create | Memory extraction logic |
| `chatConfigModel.json` | Modify | Add memory config fields |
| `Chat.java` | Modify | Integration hooks |
| `promptConfigModel.json` | Modify | Add memory format templates |
| `TestMemoryIntegration.java` | Create | Integration tests |

## Existing Infrastructure

The following components are already implemented and ready to use:

### MemoryUtil.java (Agent7)
- `createMemory()` - Creates memory records with vector embeddings
- `searchMemories()` - Semantic search via VectorUtil
- `searchMemoriesByTag()` - Tag-based retrieval
- `getConversationMemories()` - Get memories by conversation ID
- `formatMemoriesAsContext()` - MCP context block formatting
- `extractMemoriesFromResponse()` - Parse LLM extraction responses

### Models (Objects7)
- `tool.memory` - Memory record with content, summary, type, importance, sourceUri, conversationId
- `tool.vectorMemory` - Vector store for memory chunks
- `tool.vectorMemoryList` - Factory for creating memory vectors
- `MemoryTypeEnumType` - UNKNOWN, DISCOVERY, BEHAVIOR, OUTCOME, NOTE, INSIGHT, DECISION, ERROR_LESSON

### AgentToolManager (Agent7)
- `searchMemories` tool - Already registered for chain/plan usage

## Implementation Priority

1. **Phase 1: ChatConfig fields** - Add useMemory, injection/extraction mode fields
2. **Phase 2: MemoryInjector** - Implement injection before LLM calls
3. **Phase 3: MemoryExtractor** - Implement extraction after responses
4. **Phase 4: Chat.java hooks** - Wire up the integration points
5. **Phase 5: Tests** - TestMemoryIntegration with end-to-end verification

This design keeps memory operations modular and configurable, allowing fine-tuned control over when and how memories flow into conversations.
