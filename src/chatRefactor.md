# Chat & Prompt Template System - Refactor Design

## Table of Contents

1. [Current Architecture Summary](#1-current-architecture-summary)
2. [Identified Issues & Limitations](#2-identified-issues--limitations)
3. [Refactor Proposals](#3-refactor-proposals)
4. [Memory System Integration](#4-memory-system-integration)
5. [Implementation Phases](#5-implementation-phases)

---

## 1. Current Architecture Summary

### 1.1 Prompt Template System

**Two distinct template schemas exist:**

| Schema | Location | Structure | Purpose |
|--------|----------|-----------|---------|
| `olio.llm.promptConfig` | `AccountManagerObjects7/.../olio/llm/prompt.config.json` | Named string-array fields (`system[]`, `user[]`, `assistant[]`, etc.) | Server-side default RP prompt with ~30 template variables |
| Ad-hoc JSON | `AccountManagerUx7/media/prompts/chatPrompt.json` | Same `olio.llm.promptConfig` schema but SMS-style | Client-authored prompt variant stored in user's `~/Chat` directory |
| Ad-hoc JSON | `AccountManagerUx7/media/prompts/magic8DirectorPrompt.json` | `{name, lines[], imageTagsAppendix[]}` | Completely different schema; not a promptConfig at all |

**Template Variable Pipeline (`PromptUtil.java`):**

The `getChatPromptTemplate()` method runs 10 sequential replacement stages through `PromptBuilderContext`:

```
buildRaceReplacements         -> ${system.race}, ${user.race}
buildSceneReplacements         -> ${scene}, ${scene.auto}, ${interaction.description}
buildSettingReplacements       -> ${setting}, ${location.terrains}
buildEpisodeReplacements       -> ${episode}, ${episodeRule}, ${episodeReminder}
buildRatingNlpConsentReplacements -> ${rating}, ${ratingMpa}, ${censorWarn}, ${nlp}
buildPronounAgeTradeReplacements  -> ${system.firstName}, pronouns, ${perspective}
buildRatingReplacements        -> ESRB-specific expansions
buildCharacterDescriptionReplacements -> Full character desc blocks
buildProfileReplacements       -> Compatibility assessments
buildInteractionReplacements   -> Interaction context
```

All patterns are defined in `TemplatePatternEnumType` (~75 regex patterns compiled once).

**Three distinct prompt composition flows:**

1. **System prompt** (`getSystemChatPromptTemplate`): Joins `system[]` array, applies all replacements
2. **User prompt** (`getUserChatPromptTemplate`): Joins `user[]` + consent blocks, applies replacements
3. **Assistant prompt** (`getAssistChatPromptTemplate`): Joins `assistant[]` + optional NLP/outfit, applies replacements

### 1.2 Chat Session Lifecycle

```
Client (chat.js)                    Server (Chat.java)
─────────────────                   ──────────────────
loadConfigList()
  ├── list promptConfigs
  ├── list chatConfigs
  └── list chatRequests (sessions)

doPeek()
  ├── GET /rest/chat/config/prompt/{name} -> full promptConfig
  ├── GET /rest/chat/config/chat/{name}   -> full chatConfig (with characters)
  └── POST /rest/chat/history             -> message history

doChat()
  ├── pushHistory() (local)
  ├── POST /rest/chat/text          -> Chat.continueChat()
  │   OR                            │   ├── getChatPrompt() -> PromptUtil pipeline
  ├── WS "chat" (streaming)         │   ├── newMessage() -> add user msg + reminder
  │                                 │   ├── pruneCount() -> trim + keyframe
  │                                 │   ├── chat() -> LLM API call
  │                                 │   └── saveSession() -> persist + vectorize
  └── display response
```

### 1.3 Context Window Management

| Mechanism | Trigger | Effect |
|-----------|---------|--------|
| **Message Trimming** | `messageTrim` (default 20) | Marks old messages as `pruned=true`; keeps last N |
| **Keyframing** | `keyframeEvery` (default 20) | Calls `analyze()` to summarize history, inserts MCP context block |
| **Reminders** | `remindEvery` (default 6) | Injects MCP reminder block with episode/NLP guidance |
| **Pruning** | `prune` flag | Enables/disables trimming entirely |

**Keyframe format (MCP block):**
```xml
<mcp:context type="resource" uri="am7://keyframe/{configId}" ephemeral="true">
{"schema":"urn:am7:narrative:keyframe","data":{"state":{
  "summary":"...", "analysis":"...", "rating":"M", "characters":"..."}}}
</mcp:context>
```

### 1.4 Memory System (Current State)

**Storage models exist but integration is minimal:**

- `tool.memory` - Discrete engrams: `{content, summary, memoryType, importance, sourceUri, conversationId}`
- `tool.vectorMemory` - Embedded chunks with `memoryType` and `conversationId` denormalized
- `VectorMemoryListFactory` - Creates vector containers for memory entries
- `VectorChatHistoryListFactory` - Converts chat history to vector chunks (1000-word chunks)

**What exists:**
- `createNarrativeVector()` in `Chat.java` vectorizes chat history on session save
- `extractMemories` flag on `chatConfig` (extraction mechanism exists in agent layer)
- `MemoryReader.java` / `MemoryWriter.java` for persistence

**What is missing:**
- No memory retrieval during prompt composition
- No memory injection into templates
- No cross-session memory continuity
- No semantic search query during `getChatPrompt()`
- No `${memory.*}` template variables

### 1.5 Client-Side Processing (chat.js)

**Post-processing pipeline for display:**
```
raw message content
  -> pruneTag("think")          // Strip <think>...</think>
  -> pruneTag("thought")        // Strip <thought>...</thought>
  -> pruneToMark("(Metrics")    // Strip face metrics
  -> pruneToMark("(Reminder")   // Strip reminder blocks
  -> pruneToMark("(KeyFrame")   // Strip keyframes
  -> processImageTokensInContent()  // ${image.TAG} -> <img> or placeholder
  -> processAudioTokensInContent()  // ${audio.TEXT} -> inline audio player
  -> marked.parse()             // Markdown rendering
```

---

## 2. Identified Issues & Limitations

### 2.1 Template Architecture Issues

**P1 - No conditional logic in templates**

Templates are flat string arrays with no way to conditionally include/exclude sections. Every promptConfig must include all possible fields even when unused. The NLP, episode, jailbreak, and censor sections are always present in the template even when their features are disabled - they just resolve to empty strings, leaving orphan numbering (rule "2", "4", "5" but no "1" or "3").

```json
// prompt.config.json system[] includes:
"${episode}",        // Empty string if no episodes
"${episodeRule}",    // Empty string if no episodes
"${nlp}",            // Empty string if useNLP=false
"${censorWarn}"      // Always present but content varies
```

**P1 - Implicit ordering dependencies**

The 10-stage replacement pipeline has undocumented ordering constraints. For example, `${scene.auto}` in the system template is built during `buildSceneReplacements()` which must run before character descriptions are resolved, but after episodes. Breaking this order silently produces corrupt prompts.

**P2 - No template inheritance or composition**

`chatPrompt.json` (SMS style) and `prompt.config.json` (RP style) share zero structure. Creating a new prompt style requires duplicating the entire schema and re-implementing all sections. There's no way to say "use the base system rules but override the perspective style."

**P2 - Inconsistent template schemas**

`magic8DirectorPrompt.json` uses `{name, lines[], imageTagsAppendix[]}` - a completely different format from `olio.llm.promptConfig`. This means the template engine can't process it, and it has its own ad-hoc token replacement (`${command}`, `${imageTags}`).

**P3 - Hardcoded role assumptions**

Templates assume exactly two characters (system + user) in a dyadic conversation. There's no support for multi-character scenes, narrator roles, or group conversations without creating entirely separate template types.

### 2.2 Memory System Gaps

**P1 - One-way memory flow**

Memories are extracted FROM chat and vectorized, but never loaded back INTO subsequent prompts. The `createNarrativeVector()` call in `saveSession()` creates embeddings that are never queried during `getChatPrompt()`. This means every new session starts with zero accumulated knowledge about the characters' relationship history.

**P1 - No memory retrieval in prompt pipeline**

`PromptUtil.java` has no concept of memories. There are no `${memory.*}` template variables, no semantic search call, and no way for a template to request "load the 5 most important memories about these two characters."

**P2 - Keyframes are discarded, not accumulated**

When a keyframe is created, the previous keyframe is deleted. This means the running summary only captures the most recent window. Long conversations lose all early context beyond what fits in the single keyframe summary. Keyframes should feed into the memory system as durable summaries.

**P2 - No cross-conversation memory**

Memories are scoped to `conversationId`. When a user starts a new chat session with the same two characters, none of their previous interaction history is available. The `importance` field (1-10) exists on memories but is never used for retrieval prioritization.

**P3 - No memory categorization in prompts**

The `memoryType` enum (`NOTE`, `FACT`, `EVENT`, `RELATIONSHIP`, `PERSONALITY`, `PREFERENCE`, `RULE`, `OUTCOME`) exists but is never used to structure how memories appear in prompts. A prompt should be able to request only `RELATIONSHIP` memories for building rapport context.

### 2.3 Context Window Inefficiency

**P1 - Naive message trimming**

`pruneCount()` simply marks the oldest messages as pruned beyond `messageTrim`. It doesn't consider message importance, role distribution, or information density. A critical plot point from message #3 is pruned identically to a "lol" from message #4.

**P2 - Redundant context in every message**

The full system prompt, user introduction, and assistant introduction are sent with every request. For streaming sessions with many turns, this wastes significant context window space with static content that never changes.

**P2 - Client-side display pruning duplicates server logic**

`chat.js` re-implements pruning logic (`pruneTag`, `pruneToMark`, `pruneOther`) that the server already handles via `getFormattedChatHistory()`. The client strips `<think>` tags, metrics, reminders, and keyframes - all of which could be filtered server-side before the history response.

**P3 - No token counting**

Neither client nor server tracks actual token usage. `messageTrim=20` is a message count, not a token budget. A 20-message conversation with short texts uses far less context than 20 messages with long narrative paragraphs, but both are trimmed identically.

### 2.4 Prompt Quality Issues

**P1 - Conflicting rule numbering**

The default `prompt.config.json` system prompt has rules numbered 1-6, but rules 1 and 3 are generated by `${episodeRule}` and `${censorWarn}` respectively. When episodes are disabled, the numbering jumps from nothing to "3." to "5." - confusing the LLM about rule structure.

**P2 - Perspective text is overly prescriptive**

The male/female perspective blocks contain 11 sub-items each with rigid behavioral directives ("Use phrases like 'logic dictates'", "Incorporate aggressive terms like 'crush' and 'dominate'"). These override character personality rather than complementing it. A shy male character shouldn't be forced into "confident, ambitious, competitive" language.

**P2 - Consent and rating blocks consume excessive tokens**

The consent prefix, rating consent, NLP consent, and censor warning blocks together can consume 200+ tokens of the system prompt on setup text that the LLM doesn't need for response quality.

**P3 - SMS prompt doesn't use available template variables**

`chatPrompt.json` only uses a small subset of available variables (`${system.firstName}`, `${perspective}`, `${censorWarn}`, `${rating}`, `${ratingMpa}`). It doesn't use scene, setting, episode, interaction, or character description variables, meaning those features silently do nothing when the SMS prompt is selected.

---

## 3. Refactor Proposals

### 3.1 Structured Prompt Template Schema

Replace flat string arrays with a structured, composable template format.

**New `olio.llm.promptTemplate` schema:**

```json
{
  "schema": "olio.llm.promptTemplate",
  "name": "Base Roleplay",
  "version": 2,
  "extends": null,
  "style": "narrative",

  "sections": {
    "identity": {
      "order": 10,
      "required": true,
      "content": [
        "You control ${system.fullName}. ${system.characterDesc}"
      ]
    },
    "rules": {
      "order": 20,
      "required": true,
      "content": [
        "Respond ONLY for ${system.firstName} from FIRST PERSON perspective.",
        "Use <think>tags</think> for internal monologue.",
        "Limit to 1-4 sentences. Wait for user response."
      ]
    },
    "episode": {
      "order": 30,
      "condition": "hasEpisodes",
      "content": ["${episode}", "${episodeRule}"]
    },
    "scene": {
      "order": 40,
      "condition": "includeScene",
      "content": ["${scene.auto}"]
    },
    "rating": {
      "order": 50,
      "required": true,
      "content": ["Content rated ${rating}/${ratingMpa}."]
    },
    "perspective": {
      "order": 60,
      "condition": "always",
      "content": ["${perspective}"]
    },
    "memory": {
      "order": 70,
      "condition": "hasMemories",
      "content": ["${memory.context}"]
    },
    "media": {
      "order": 80,
      "condition": "supportsMedia",
      "content": [
        "Send pics: ${image.TAG} or ${image.TAG,TAG}",
        "Send voice: ${audio.TEXT}"
      ]
    }
  },

  "roles": {
    "system": {
      "sections": ["identity", "rules", "episode", "scene", "rating", "perspective", "memory", "media"]
    },
    "user": {
      "content": ["${user.firstName} (${user.asg}). ${user.characterDescPublic}"],
      "consent": true
    },
    "assistant": {
      "content": ["${system.firstName} (${system.asg}). ${system.race}"]
    }
  }
}
```

**Key improvements:**
- **Conditional sections** via `condition` field - sections only included when their feature is active
- **Ordered composition** via `order` field - explicit, documented ordering replaces implicit pipeline dependencies
- **Inheritance** via `extends` - SMS prompt extends base and overrides `rules` and `perspective` sections
- **Role-specific section selection** - each role declares which sections it uses

**SMS override example:**

```json
{
  "schema": "olio.llm.promptTemplate",
  "name": "SMS Chat",
  "extends": "Base Roleplay",
  "style": "sms",

  "sections": {
    "identity": {
      "override": true,
      "content": ["YOU are ${system.firstName} texting ${user.firstName}."]
    },
    "rules": {
      "override": true,
      "content": [
        "FORMAT: Short SMS/DM messages. 1-3 sentences MAX.",
        "Use text slang, abbreviations, emojis, lowercase.",
        "Stay in character. Match personality and speech patterns."
      ]
    },
    "perspective": {
      "override": true,
      "content": ["${perspective.casual}"]
    },
    "episode": { "exclude": true },
    "scene": { "exclude": true }
  }
}
```

### 3.2 Template Condition System

Add a condition evaluator to `PromptUtil` that checks `chatConfig` state:

| Condition | Evaluates to true when |
|-----------|----------------------|
| `hasEpisodes` | `chatConfig.episodes` is non-empty |
| `includeScene` | `chatConfig.includeScene == true` |
| `hasMemories` | Memory retrieval returned results |
| `supportsMedia` | `chatConfig.chatOptions.imageTokens` or `audioTokens` enabled |
| `useNLP` | `chatConfig.useNLP == true` |
| `always` | Always included |

This eliminates the orphan numbering problem - when episodes are disabled, the episode section is omitted entirely rather than resolving to empty strings.

### 3.3 Perspective Refactor

Replace the rigid 11-point male/female perspective blocks with character-driven style hints:

**Current problem:**
```json
"malePerspective": [
  "Use action-oriented, direct, assertive language.",
  "Prioritize independence, self-reliance, and problem-solving...",
  "Show confidence, ambition, and competitiveness.",
  "Use phrases like 'logic dictates'..."
]
```

**Proposed replacement - derive from character personality:**

Add a new template variable `${perspective.dynamic}` that generates style guidance from the character's actual personality traits, instincts, and statistics rather than gender stereotypes:

```
Writing style for ${system.firstName}:
- Personality: ${system.personality.summary}
- Speech pattern: ${system.speechPattern}
- Emotional tendency: ${system.emotionalTendency}
```

Keep `${perspective}` as a fallback for backward compatibility, but add `${perspective.casual}` for SMS-style prompts that generates shorter, tone-appropriate guidance:

```
TEXT STYLE: ${system.personality.casual}
```

### 3.4 Rule Numbering Fix

Replace the current fragmented numbering with dynamic rule assembly:

```java
// New approach in PromptUtil
List<String> rules = new ArrayList<>();
if (hasEpisodes) {
    rules.add("Follow EPISODE GUIDANCE for plot points.");
    rules.add("After completing last stage, respond with: #NEXT EPISODE#");
}
rules.add("Content rated " + rating + ". " + censorText);
rules.add("Respond ONLY for " + systemName + " from FIRST PERSON perspective.");
// ... remaining rules

// Number dynamically
for (int i = 0; i < rules.size(); i++) {
    numbered.add((i + 1) + ". " + rules.get(i));
}
ctx.replace(TemplatePatternEnumType.RULES, String.join("\n", numbered));
```

### 3.5 Unified Prompt Schema for Magic8

Bring `magic8DirectorPrompt.json` into the `olio.llm.promptTemplate` schema:

```json
{
  "schema": "olio.llm.promptTemplate",
  "name": "Magic8 Director",
  "style": "directive",

  "sections": {
    "identity": {
      "order": 10,
      "content": ["You are the Session Director for Magic8, an immersive audiovisual experience."]
    },
    "intent": {
      "order": 20,
      "content": ["SESSION INTENT: ${command}"]
    },
    "directives": {
      "order": 30,
      "content": ["...directive field documentation..."]
    },
    "imageTags": {
      "order": 90,
      "condition": "hasImageTags",
      "content": ["Available image tags: ${imageTags}"]
    }
  }
}
```

This unifies all prompt types under one schema, making them manageable through the same UI and API.

---

## 4. Memory System Integration

### 4.1 Memory Retrieval Pipeline

Add a memory retrieval phase to `Chat.getChatPrompt()` that runs BEFORE template variable replacement:

```
getChatPrompt()
  ├── 1. Load chatConfig + promptConfig (existing)
  ├── 2. NEW: retrieveRelevantMemories()
  │       ├── Query vector store for character-pair memories
  │       ├── Filter by memoryType relevance
  │       ├── Rank by importance + recency
  │       └── Build memory context block
  ├── 3. Template replacement pipeline (existing, extended)
  │       └── NEW: buildMemoryReplacements() stage
  └── 4. Compose final prompt (existing)
```

**New method in Chat.java:**

```java
private MemoryContext retrieveRelevantMemories(BaseRecord chatConfig) {
    BaseRecord systemChar = chatConfig.get("systemCharacter");
    BaseRecord userChar = chatConfig.get("userCharacter");

    // 1. Character-pair memories (cross-conversation)
    List<BaseRecord> pairMemories = vectorUtil.searchByCharacterPair(
        systemChar.get("objectId"),
        userChar.get("objectId"),
        memoryBudget  // token budget for memory section
    );

    // 2. Character-specific memories (personality, preferences)
    List<BaseRecord> charMemories = vectorUtil.searchByCharacter(
        systemChar.get("objectId"),
        EnumSet.of(MemoryType.PERSONALITY, MemoryType.PREFERENCE)
    );

    // 3. Recent session summary (if continuing)
    BaseRecord lastKeyframe = findLastKeyframe(chatConfig);

    return new MemoryContext(pairMemories, charMemories, lastKeyframe);
}
```

### 4.2 New Template Variables for Memory

Add these variables to `TemplatePatternEnumType`:

| Variable | Source | Content |
|----------|--------|---------|
| `${memory.context}` | All retrieved memories formatted as a block | "Previous interactions: ..." |
| `${memory.relationship}` | RELATIONSHIP-type memories only | "They have been friends for..." |
| `${memory.facts}` | FACT-type memories only | "User prefers..." |
| `${memory.lastSession}` | Most recent keyframe summary | "Last time they talked about..." |
| `${memory.count}` | Number of memories retrieved | "12" (for diagnostics) |

**Memory context block format (injected via MCP):**

```xml
<mcp:context type="resource" uri="am7://memory/{systemCharId}/{userCharId}" ephemeral="true">
{"schema":"urn:am7:narrative:memory","data":{
  "relationship": [
    {"summary": "They met at a coffee shop", "importance": 8, "session": "2025-01-15"},
    {"summary": "Had an argument about politics", "importance": 6, "session": "2025-01-20"}
  ],
  "facts": [
    {"summary": "User's favorite color is blue", "importance": 3},
    {"summary": "System character is allergic to cats", "importance": 4}
  ],
  "lastSession": "They reconciled after the argument and planned to meet again."
}}
</mcp:context>
```

### 4.3 Keyframe-to-Memory Pipeline

When a keyframe is created, also persist its summary as a durable memory:

```java
private void addKeyFrame(OpenAIRequest req) {
    // ... existing keyframe logic ...
    String analysisText = analyze(req, null, false, false, false);

    // NEW: Persist keyframe as a durable memory
    if (chatConfig.get("extractMemories")) {
        BaseRecord memory = RecordFactory.newInstance("tool.memory");
        memory.set("content", analysisText);
        memory.set("summary", truncateToSentence(analysisText, 200));
        memory.set("memoryType", "EVENT");
        memory.set("importance", calculateImportance(req));
        memory.set("sourceUri", "am7://keyframe/" + cfgObjId);
        memory.set("conversationId", req.get("objectId"));

        // Also tag with character pair for cross-conversation retrieval
        memory.set("annotations", JSON.of(Map.of(
            "systemCharacterId", chatConfig.get("systemCharacter.objectId"),
            "userCharacterId", chatConfig.get("userCharacter.objectId")
        )));

        IOSystem.getActiveContext().getWriter().write(memory);
        vectorizeMemory(memory);
    }
}
```

### 4.4 In-Conversation Memory Extraction

Instead of only extracting memories post-conversation, extract notable events in real-time after each LLM response:

```java
// In continueChat(), after receiving LLM response:
if (chatConfig.get("extractMemories") && shouldExtractMemory(response)) {
    // Use a lightweight model to identify memorable content
    String extractionPrompt = "Extract any new facts, relationship changes, or notable events from this exchange. Return JSON array of memories or empty array.";
    List<BaseRecord> newMemories = extractMemoriesFromExchange(
        lastUserMessage, response, extractionPrompt
    );
    for (BaseRecord mem : newMemories) {
        persistAndVectorize(mem);
    }
}
```

**Extraction frequency control:** Add `memoryExtractionEvery` to chatConfig (default: 5) to avoid over-extraction. Only run extraction every N exchanges.

### 4.5 Memory Budget Management

Add a token budget system for memory injection:

```java
public class MemoryBudgetManager {
    private int totalBudget;      // Total tokens available for memory section
    private int relationshipPct;  // % of budget for relationship memories (default 40%)
    private int factsPct;         // % of budget for facts (default 30%)
    private int sessionPct;       // % of budget for last session summary (default 30%)

    public MemoryContext selectMemories(List<BaseRecord> allMemories) {
        // Sort by importance * recency_weight
        // Fill each category up to its token budget
        // Highest importance memories always included
        // Recent memories preferred over old ones at same importance
    }
}
```

Add `memoryBudget` field to `chatConfig` (default: 500 tokens).

### 4.6 Cross-Conversation Memory Scoping

Replace the current `conversationId`-only scoping with a hierarchical scope:

```
Character Pair Scope (systemChar + userChar)
  └── Conversation Scope (specific session)
       └── Exchange Scope (specific message pair)
```

**Query hierarchy:**
1. **New conversation** with known character pair: Load character-pair-scoped memories
2. **Continuing conversation**: Load pair memories + conversation-specific memories
3. **New character pairing**: Load only character-specific personality/preference memories

Add to `VectorUtil`:

```java
public List<BaseRecord> searchMemories(
    String systemCharId,
    String userCharId,
    Set<MemoryType> types,
    int maxResults,
    float minSimilarity
) {
    // Vector similarity search with metadata filters
    // Filters: characterPair AND memoryType IN types
    // Ordered by: importance DESC, createdDate DESC
    // Limited to: maxResults with minSimilarity threshold
}
```

---

## 5. Implementation Phases

### Phase 1: Template Cleanup (Low risk, high impact)

**Goal:** Fix prompt quality without changing architecture.

1. **Fix rule numbering** - Replace hardcoded numbers with dynamic assembly in `PromptUtil.buildEpisodeReplacements()` and related methods
2. **Trim consent blocks** - Reduce consent/censor text to essential minimum (~50 tokens instead of ~200)
3. **Add condition checks** - Wrap existing replacement stages in `if` guards so disabled features produce no output (no orphan text)
4. **Document variable dependencies** - Add comments to `PromptUtil` documenting which stages must precede others

**Files modified:** `PromptUtil.java`, `prompt.config.json`

### Phase 2: Memory Retrieval (Medium risk, high impact)

**Goal:** Make memories available during prompt composition.

1. **Add `retrieveRelevantMemories()`** to `Chat.java`
2. **Add memory template variables** to `TemplatePatternEnumType` (`${memory.context}`, `${memory.relationship}`, etc.)
3. **Add `buildMemoryReplacements()`** stage to `PromptUtil` pipeline (after episode, before character descriptions)
4. **Implement character-pair memory query** in `VectorUtil`
5. **Add `memoryBudget` field** to `chatConfigModel.json`

**Files modified:** `Chat.java`, `PromptUtil.java`, `TemplatePatternEnumType.java`, `VectorUtil.java`, `chatConfigModel.json`

### Phase 3: Keyframe-to-Memory Pipeline (Medium risk, medium impact)

**Goal:** Make keyframes durable and accumulative.

1. **Persist keyframe summaries as memories** in `addKeyFrame()`
2. **Tag memories with character pair IDs** for cross-conversation retrieval
3. **Add `memoryExtractionEvery` config** to control extraction frequency
4. **Keep last 2 keyframes** instead of 1 for better continuity during active conversations

**Files modified:** `Chat.java`, `chatConfigModel.json`, `memoryModel.json` (add annotations field)

### Phase 4: Structured Template Schema (Higher risk, high long-term impact)

**Goal:** Replace flat string arrays with composable, conditional sections.

1. **Define `promptTemplateModel.json`** with sections, conditions, ordering, inheritance
2. **Create `PromptTemplateComposer.java`** - new class that processes the structured schema
3. **Add condition evaluator** that checks chatConfig state
4. **Implement template inheritance** (`extends` field resolution)
5. **Migrate existing prompts** to new schema (keep old schema working via adapter)
6. **Unify Magic8 prompt** under the new schema
7. **Update chat.js** if any client-side template handling changes

**New files:** `promptTemplateModel.json`, `PromptTemplateComposer.java`, `PromptConditionEvaluator.java`
**Modified files:** `PromptUtil.java` (add adapter), prompt JSON files, `chat.js`

### Phase 5: Client-Side Cleanup (Low risk, medium impact)

**Goal:** Remove duplicated logic and improve display pipeline.

1. **Move display pruning to server** - Have `/rest/chat/history` return pre-pruned messages with a `displayContent` field alongside raw `content`
2. **Remove client-side `pruneTag`/`pruneToMark`** functions from chat.js
3. **Add message metadata** - Server returns `{content, displayContent, hasThoughts, hasMetrics, hasKeyframe}` so client can toggle visibility without re-parsing
4. **Standardize token processing** - Image and audio token processing should work identically regardless of prompt template style

**Files modified:** `ChatUtil.java` (server-side formatting), `chat.js` (simplify rendering)

---

## Appendix A: Current Template Variable Reference

| Variable | Stage | Source |
|----------|-------|--------|
| `${system.firstName}` | pronounAge | `chatConfig.systemCharacter.firstName` |
| `${system.fullName}` | charDesc | `chatConfig.systemCharacter.firstName + lastName` |
| `${system.asg}` | pronounAge | Age/sex/gender string |
| `${system.race}` | race | Race description from `promptConfig.races[]` |
| `${system.characterDesc}` | charDesc | Full narrative description via `NarrativeUtil.describe()` |
| `${system.characterDescLight}` | charDesc | Abbreviated description |
| `${system.characterDescPublic}` | charDesc | Public-facing description |
| `${system.ppro}` | pronounAge | Possessive pronoun (his/her) |
| `${system.cpro}` | pronounAge | Capital pronoun (He/She) |
| `${system.gender}` | pronounAge | Gender string |
| `${user.firstName}` | pronounAge | `chatConfig.userCharacter.firstName` |
| `${user.fullName}` | charDesc | Full name |
| `${user.asg}` | pronounAge | Age/sex/gender |
| `${user.race}` | race | Race description |
| `${user.characterDescPublic}` | charDesc | Public description |
| `${user.question}` | (runtime) | User's current question (citations mode) |
| `${user.citations}` | (runtime) | Citation text block |
| `${episode}` | episode | Episode guidance block |
| `${episodeRule}` | episode | Episode rules |
| `${episodeReminder}` | episode | Episode stage reminder |
| `${episodic}` | episode | "episodic" or "" |
| `${episodeAssist}` | episode | Episode assistant intro |
| `${scene}` | scene | Scene description |
| `${scene.auto}` | scene | Auto-generated scene block |
| `${setting}` | setting | Setting text |
| `${location.terrains}` | setting | Terrain description |
| `${population.people}` | setting | Population description |
| `${population.animals}` | setting | Animal description |
| `${event.alignment}` | setting | Event moral alignment |
| `${rating}` | rating | ESRB rating code |
| `${ratingMpa}` | rating | MPA equivalent |
| `${censorWarn}` | ratingNlp | Censor warning block |
| `${assistCensorWarn}` | ratingNlp | Assistant censor text |
| `${nlp}` | ratingNlp | NLP instruction block |
| `${nlp.command}` | ratingNlp | NLP command string |
| `${nlpWarn}` | ratingNlp | NLP assistant warning |
| `${nlpReminder}` | ratingNlp | NLP reminder text |
| `${perspective}` | pronounAge | Male/female perspective block |
| `${profile.ageCompat}` | profile | Age compatibility assessment |
| `${profile.romanceCompat}` | profile | Romance compatibility |
| `${profile.raceCompat}` | profile | Race compatibility |
| `${profile.leader}` | profile | Leadership dynamic |
| `${interaction.description}` | interaction | Current interaction context |
| `${firstSecondToBe}` | pronounAge | "I am" or "You are" |
| `${firstSecondWho}` | pronounAge | "I am" or "You are" (identity) |

## Appendix B: Chat Configuration Fields Reference

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `rating` | ESRBEnum | - | Content rating (E/T/M/AO/RC) |
| `serviceType` | LLMServiceEnum | - | LLM provider (OPENAI/OLLAMA) |
| `model` | String | "dolphin-llama3" | LLM model name |
| `analyzeModel` | String | - | Model for analysis/keyframing |
| `stream` | boolean | false | Enable WebSocket streaming |
| `assist` | boolean | false | Include assistant intro message |
| `prune` | boolean | false | Enable message trimming |
| `messageTrim` | int | 20 | Message window size |
| `keyframeEvery` | int | 20 | Messages between keyframes |
| `remindEvery` | int | 6 | Messages between reminders |
| `extractMemories` | boolean | false | Enable memory extraction |
| `useNLP` | boolean | false | Enable NLP commands |
| `nlpCommand` | String | - | NLP goal text |
| `useJailBreak` | boolean | false | Enable jailbreak prompt |
| `includeScene` | boolean | false | Include scene description |
| `setting` | String | - | Setting text ("random" for auto) |
| `startMode` | String | "user" | Who starts (user/system/none) |
| `episodes` | List | [] | Episode guidance objects |
| `systemCharacter` | FK | - | NPC character reference |
| `userCharacter` | FK | - | Player character reference |
| `serverUrl` | String | - | LLM API endpoint |
| `apiKey` | String | - | Encrypted API key |
