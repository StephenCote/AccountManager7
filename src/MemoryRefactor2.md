# Memory System Refactor v2 — Design Document

**Date:** 2026-02-21
**Status:** DRAFT — Awaiting Review
**Scope:** Agent7, Objects7, Service7, Ux7

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Problems & Root Causes](#2-problems--root-causes)
3. [Refactor Goals](#3-refactor-goals)
4. [Phase 1: Code Cleanup & Dead Path Removal](#phase-1-code-cleanup--dead-path-removal)
5. [Phase 1b: Agent7 / Objects7 Memory Consolidation](#phase-1b-agent7--objects7-memory-consolidation)
6. [Phase 2: Memory Extraction — Focused & Quiet](#phase-2-memory-extraction--focused--quiet)
6. [Phase 3: Interaction Events — Complete Implementation](#phase-3-interaction-events--complete-implementation)
7. [Phase 4: Chat Events — olio.event Per Conversation](#phase-4-chat-events--olioevent-per-conversation)
8. [Phase 5: Cross-Character Memory Recall & Gossip](#phase-5-cross-character-memory-recall--gossip)
9. [Phase 6: Vector Memory Cleanup](#phase-6-vector-memory-cleanup)
10. [Phase 7: Auto-Title & Auto-Icon Fix](#phase-7-auto-title--auto-icon-fix)
11. [Phase 8: Chat UI Refactor](#phase-8-chat-ui-refactor)
12. [Integration Tests](#integration-tests)
13. [File Impact Matrix](#file-impact-matrix)
14. [Risk & Dependencies](#risk--dependencies)

---

## 1. Current State Analysis

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                           Ux7 (Mithril.js)                       │
│  chat.js ─ ConversationManager ─ MemoryPanel ─ ContextPanel      │
│  LLMConnector ─ pageClient (WebSocket)                           │
├──────────────────────────────────────────────────────────────────┤
│                         Service7 (REST)                           │
│  MemoryService ─ ChatService ─ VectorService ─ GameService       │
├──────────────────────────────────────────────────────────────────┤
│                        Objects7 (Core)                           │
│  Chat.java ─ ChatUtil.java ─ MemoryUtil.java ─ VectorUtil.java   │
│  InteractionUtil ─ InteractionAction ─ EventUtil                 │
│  Models: tool.memory, tool.vectorMemory, olio.interaction,       │
│          olio.event, olio.llm.chatConfig, olio.llm.chatRequest   │
├──────────────────────────────────────────────────────────────────┤
│                        Agent7 (LLM Chains)                       │
│  AM7AgentTool ─ memoryExtractionChain.json ─ ChainExecutor       │
│  memoryExtractionPrompt.txt                                      │
└──────────────────────────────────────────────────────────────────┘
```

### Memory Lifecycle (Current)

1. **Keyframe trigger**: Every N messages (`keyframeEvery`), Chat.java creates a keyframe
2. **Keyframe analysis**: Async LLM call summarizes the conversation segment
3. **OUTCOME memory**: Keyframe summary persisted as `MemoryTypeEnumType.OUTCOME` memory
4. **Discrete extraction**: Optionally, every Mth keyframe, a second LLM call extracts typed memories (FACT, RELATIONSHIP, EMOTION, DECISION, DISCOVERY, BEHAVIOR, etc.)
5. **Vector embedding**: Each memory is automatically vectorized for semantic search
6. **Memory injection**: Before each LLM call, a 3-layer budget-allocated retrieval injects memories into the system prompt

### What Works

- Canonical person pair ordering (lower ID first) — role-agnostic retrieval
- Three-layer memory retrieval (pair 50%, character 30%, semantic 20%)
- Type-prioritized budget allocation (RELATIONSHIP 40%, FACT 25%, etc.)
- Freshness decay for stale memory deprioritization
- Semantic deduplication (0.92 cosine, 0.85 Jaccard fallback)
- Memory merge on duplicate (higher importance, combined sourceUris)
- MCP context block format for memory injection
- MemoryPanel UI with pair/character view modes, manual creation, injection

### What's Broken or Incomplete

| Issue | Status | Impact |
|-------|--------|--------|
| Memory extraction too noisy/verbose | Broken | 50-msg chat produces ~50 memories |
| Multiple output format fallbacks cause noise | Design flaw | Inconsistent extraction quality |
| Interaction extraction partially implemented | Incomplete | InteractionAction exists but never called from chat |
| No olio.event per conversation | Missing | No event timeline for chats |
| No cross-character memory recall (gossip) | Missing | Characters can't share memories |
| Auto-title and auto-icon | Never worked | Multiple cascading failure points |
| No vector cleanup on memory delete | Missing | Orphaned vectors accumulate |
| Chat UI sidebar too prominent | UX issue | Conversation space cramped |
| Info and Details tabs separate | UX issue | Related information split |

---

## 2. Problems & Root Causes

### 2.1 Noisy Memory Extraction

**Root cause:** The extraction prompt (`memoryExtractionPrompt.txt`) asks the LLM to extract ALL memory types (FACT, RELATIONSHIP, EMOTION, DECISION, DISCOVERY, BEHAVIOR, OUTCOME, NOTE, INSIGHT, ERROR_LESSON) from a conversation segment in a single call. The prompt says "1-5 memories" but the LLM over-extracts because:

1. The prompt lists 10 memory types with examples, encouraging the LLM to find one of each
2. Keyframe interval (`keyframeEvery=20`) means extraction runs on 20-message chunks, which contain many extractable items
3. The extraction chain's step 1 fetches up to 50 existing memories for dedup, but the dedup threshold (0.92 cosine) is too tight — near-duplicates still slip through
4. No throttle on total memory count per conversation

**Evidence:** A 50-message conversation with `keyframeEvery=20` triggers ~2-3 extractions, each producing 5-10 memories, plus keyframe OUTCOME memories = 15-30+ memories per conversation.

### 2.2 Multiple Output Format Handling

**Root cause:** `MemoryUtil.extractMemoriesFromResponse()` handles THREE output formats:
1. JSON array (preferred)
2. Text-based numbered/bulleted lists with metadata patterns
3. Markdown code-fenced JSON

The text fallback parser uses 6+ regex patterns and continuation logic that produces verbose logging (5-10x the actual memory count in log output). This fallback exists because the LLM sometimes ignores the "JSON only" directive.

### 2.3 Partial Interaction Implementation

**Root cause:** The interaction system was designed in layers that were never fully connected:
- `olio.interaction` model: Complete — 41 interaction types with actor/interactor perspectives
- `InteractionEnumType`: Complete — positive/neutral/negative classification
- `InteractionAction`: Complete — maps interactions to component actions with stat weights
- `InteractionUtil`: Partially commented out — `guessReason()` / `guessReasonXXX()` never finished
- **Missing link:** Chat.java calls `InteractionEvaluator.evaluate()` from the keyframe pipeline, but the evaluator's connection to persisted olio.interaction records is incomplete. Chat conversations don't create interaction records.

### 2.4 Auto-Title / Auto-Icon Failure Chain

**Root causes (cascading):**

1. **chatRequestObjectId not set**: Buffer mode depends on `this.chatRequestObjectId` which may be null if Chat wasn't initialized via the full ChatService path
2. **Async race condition**: Title generation runs in `CompletableFuture.runAsync()` but the cache (`asyncChatRequestRecords`) gets cleared in `clearCache(oid)` immediately after `oncomplete`, potentially before the async title job reads from it
3. **No retry**: If LLM call times out or returns unparseable output, the title is silently dropped — never retried
4. **Default value confusion**: `chatConfigModel.json` shows `autoTitle` default as `true` but tests show the runtime default is actually `false`
5. **No fallback**: If generation fails, there's no fallback title derivation (e.g., from the first user message)

---

## 3. Refactor Goals

| # | Goal | Metric |
|---|------|--------|
| G1 | Reduce memory noise | < 5 memories per 50-message conversation |
| G2 | Separate memory extraction from interaction extraction | 2 focused LLM calls instead of 1 overloaded call |
| G3 | Complete interaction persistence | Every chat produces a persisted olio.interaction |
| G4 | Create event timeline for chats | Every chat has an olio.event with member interactions |
| G5 | Enable cross-character gossip | Characters recall memories 2 degrees of separation away |
| G6 | Clean up orphaned vectors | Deleting a memory cascades to its vectorMemory records |
| G7 | Fix auto-title and auto-icon | Titles/icons appear reliably after first exchange |
| G8 | Streamline chat UI | Maximize conversation space, collapsible sidebar |
| G9 | Remove dead code | No deprecated methods, no commented-out blocks |
| G10 | Configurable extraction | Clear, separate control over what gets extracted |

---

## Implementation Constraints

### Follow Established Design Patterns

Before writing any code, **study the existing codebase patterns and follow them exactly.** This project has established conventions for:

- **Record creation and persistence** — two-phase creation (create base, then update with foreign refs) using `RecordUtil` to bypass policy when needed. Do not invent alternative persistence paths.
- **Foreign reference resolution** — use `OlioUtil.getFullRecord()`, not direct field access on stubs. Do not create shortcut methods that skip resolution.
- **Query construction** — use the existing `QueryUtil` / `BaseRecord` query patterns. Do not write raw SQL or bypass the query layer.
- **Security and authorization** — the PBAC (Policy-Based Access Control) layer enforces field-level and record-level security. **Do not circumvent it.** If you get an authorization or field error, you almost certainly did not follow the established pattern. Stop, read the existing code that does the same operation successfully, and match it.
- **Model field access** — use `record.get("fieldName")` and `record.set("fieldName", value)` with the correct types. Do not guess field names or types.
- **Chat configuration** — read values with defaults, e.g., `chatConfig.get("fieldName", defaultValue)`. Follow how existing fields like `keyframeEvery`, `memoryBudget`, `extractMemories` are read in `Chat.configureChat()`.
- **Vector operations** — use `VectorUtil` and `VectorMemoryListFactory` as established. Do not create parallel vector storage paths.
- **Prompt loading** — use `ResourceUtil.getResource()` for prompt templates. Follow the pattern in `Chat.java` for loading and injecting prompt content.
- **Async operations** — follow the `CompletableFuture.runAsync()` pattern with proper lock management (`ConcurrentHashMap`, volatile flags, timeout-based leak detection) as established in the keyframe system.

### Do Not Fight the Framework

**When you encounter an authorization error, field validation error, or policy violation during implementation or testing:**

1. **Assume you are wrong.** 99% of the time, the error means you are not following the established pattern.
2. **Read existing working code** that performs the same type of operation (e.g., if creating a memory fails, read how `MemoryUtil.createMemory()` does its two-phase persist).
3. **Match the pattern exactly** — same method calls, same order, same field names, same bypass mechanisms where they are already used.
4. **Do NOT:**
   - Disable security checks to "make it work"
   - Add `@PermitAll` or skip authorization on endpoints
   - Bypass policy evaluation with custom workarounds
   - Create alternate persistence paths that avoid validation
   - Suppress or catch-and-ignore authorization exceptions
   - Add fields to models without understanding the schema constraints
   - Modify core framework classes (RecordUtil, PolicyUtil, AccessPoint, etc.) to accommodate your code
5. **If truly stuck**, document the exact error, the code you wrote, and the existing pattern you tried to follow. Ask for guidance rather than inventing around the problem.

### Code Review Checklist

Before submitting any phase, verify:

- [ ] New code follows the same patterns as adjacent existing code
- [ ] No new persistence paths — uses existing `RecordUtil`, `AccessPoint`, or `IOSystem` methods
- [ ] No security bypasses introduced
- [ ] No framework modifications — only application-layer changes
- [ ] Field names and types match model JSON definitions exactly
- [ ] Foreign references use proper resolution (not raw ID access)
- [ ] Async code follows the established lock/guard patterns
- [ ] Existing tests still pass without modification (unless the test itself is being refactored)
- [ ] New tests follow the patterns in existing test classes (e.g., `TestMemoryUtil`, `TestMemoryPhase2`, `TestKeyframeMemory`)

---

## Testing Policy

**Every phase MUST include both backend integration tests AND Ux tests for all changes and new features.** No phase is considered complete until all tests pass.

### Backend Tests (Objects7 / Agent7 / Service7)

All backend "unit tests" are effectively integration tests with full service access: LLM endpoints, embedding API, database (PostgreSQL + pgvector), and prepopulated characters/world data. Each phase lists its required backend tests in its `Tests` subsection.

**Requirements:**
- Every new class, method, or code path must have a corresponding test
- Every bug fix must have a regression test proving the fix
- Every removed code path must have a test verifying the removal doesn't break callers
- Tests must assert specific outcomes, not just "no exceptions thrown"
- New tests MUST follow the patterns established in existing test classes — study `TestMemoryUtil`, `TestMemoryPhase2`, `TestKeyframeMemory`, and `TestMemoryDuel` for setup, assertion style, and infrastructure usage
- If a test fails due to authorization or field errors, the test (or the code under test) is not following established patterns — fix the pattern adherence, do not weaken the security model

### Ux Tests (Ux7)

All UI changes and new features require Ux tests. These are functional verification tests exercised through the running application.

**Requirements:**
- Every new UI component, button, panel, or visual state must have a Ux test
- Every modified UI behavior (layout changes, styling changes, interaction changes) must have a Ux test
- Every new API integration (REST calls, WebSocket events) from the UI must have a Ux test verifying the round-trip
- Ux tests must verify both the happy path and error/empty states
- REST calls must use the established `LLMConnector` and service patterns — do not create ad-hoc fetch calls that bypass the existing API layer

### Test Naming Convention

- Backend: `Test{Feature}_{Scenario}` — e.g., `TestGossip_CrossCharacterRecall`
- Ux: `Ux_{Component}_{Scenario}` — e.g., `Ux_GossipButton_GlowOnMatch`

---

## Phase 1: Code Cleanup & Dead Path Removal

### Scope

Remove deprecated code, unused fields, dead conditions, and commented-out blocks across all modules.

### 1.1 Objects7 Cleanup

#### InteractionUtil.java
- **DELETE**: Commented-out `guessReason()` and `guessReasonXXX()` methods
- These were Phase 1 exploration code that was never activated

#### MemoryUtil.java
- **DELETE**: Text-based fallback parser in `extractMemoriesFromResponse()`
  - Remove Format 2 (numbered/bulleted text parsing) and Format 3 (markdown code fence stripping)
  - Keep ONLY JSON array parsing
  - If the LLM doesn't return valid JSON, log a warning and return empty list — do not attempt heroic parsing
- **DELETE**: Backward-compatibility overloads that accept `long personId1, long personId2`
  - All callers should use `BaseRecord` person references
  - Remove `stubPersonRecord()` helper
- **CONSOLIDATE**: Merge `searchMemoriesByPerson()` and `searchMemoriesByPersonPair()` parameter validation into a shared private method

#### Chat.java
- **DELETE**: `formatOutput` field (boolean, never referenced)
- **DELETE**: `forceJailbreak` field (boolean, unused)
- **REVIEW**: `chatConsole()` interactive CLI method — keep if used for testing, mark `@Deprecated` if not
- **DELETE**: Legacy text-format remind/keyframe handling if fully replaced by MCP blocks

#### VectorProvider.java
- **REVIEW**: `describe()` method's model-specific branches — remove any that reference deprecated model types

### 1.2 Agent7 Cleanup

#### memoryExtractionPrompt.txt
- **REPLACE**: Will be replaced entirely in Phase 2 (new focused prompt)

#### memoryExtractionChain.json
- **REPLACE**: Will be replaced entirely in Phase 2 (simplified chain)

#### AM7AgentTool.java
- **REVIEW**: `extractMemories` tool — may need signature changes for Phase 2
- **KEEP**: `searchMemories`, `searchMemoriesByPair`, `searchMemoriesByPerson` tools (still needed)

### 1.3 Service7 Cleanup

#### MemoryService.java
- **REVIEW**: `resetLastKeyframeAt()` side effect on memory delete — move to Objects7 if it belongs there
- No dead endpoints identified

### 1.4 Ux7 Cleanup

#### chat.js
- **REVIEW**: Any commented-out UI code or unused state variables
- **DEFER**: Major UI changes to Phase 8

### 1.5 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestCleanup_TextParserRemoval` | Verify that JSON-only extraction still works after text parser removal |
| `TestCleanup_NonJsonReturnsEmpty` | Verify non-JSON LLM response returns empty list, not crash |
| `TestCleanup_OverloadRemoval` | Verify all callers use BaseRecord overloads |
| `TestCleanup_DeadFieldRemoval` | Verify no runtime references to removed fields |
| `TestCleanup_ExistingTestsStillPass` | Run existing TestMemoryUtil, TestMemoryPhase2, TestKeyframeMemory — all must pass |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_MemoryPanel_StillLoads` | MemoryPanel loads and displays memories after cleanup changes |
| `Ux_MemoryPanel_ExtractStillWorks` | Extract Memories button still triggers extraction and refreshes list |
| `Ux_Chat_ExistingFlowUnbroken` | Full chat send/receive cycle works after cleanup |

---

## Phase 1b: Agent7 / Objects7 Memory Consolidation

### Problem

Memory-related code is split between Agent7 and Objects7 without a clear boundary:

**Objects7 (MemoryUtil.java):**
- Memory CRUD (create, search, delete)
- Memory extraction from LLM response text (`extractMemoriesFromResponse()`)
- Deduplication logic (`findSemanticDuplicate()`, `mergeMemory()`)
- Vector embedding creation (`createMemoryVectors()`)
- Memory formatting for prompt injection (`formatMemoriesAsContext()`)
- Canonical person pair ordering

**Agent7 (AM7AgentTool.java + chain/prompt resources):**
- `searchMemories` tool — thin wrapper around `MemoryUtil.searchMemories()`
- `searchMemoriesByPair` tool — thin wrapper around `MemoryUtil.searchMemoriesByPersonPair()`
- `searchMemoriesByPerson` tool — thin wrapper around `MemoryUtil.searchMemoriesByPerson()`
- `extractMemories` tool — thin wrapper that calls `MemoryUtil.extractMemoriesFromResponse()`
- `memoryExtractionChain.json` — 3-step chain definition (tool → LLM → tool)
- `memoryExtractionPrompt.txt` — LLM prompt template

**The problem:** The actual memory logic lives in Objects7's `MemoryUtil`, while Agent7 provides thin tool wrappers and prompt/chain resources. This split means:
1. The chain definition and prompt live in Agent7 but the extraction logic lives in Objects7
2. Agent7 tool methods are 5-10 line wrappers with no real logic
3. `Chat.java` (Objects7) directly calls `MemoryUtil` for extraction — it doesn't use the Agent7 chain at all for keyframe-triggered extraction
4. The Agent7 chain is only used when the agent/planner explicitly invokes memory extraction as a tool

### Analysis: What Should Stay Where

| Component | Current Module | Recommended Module | Rationale |
|-----------|---------------|-------------------|-----------|
| `MemoryUtil.java` (all methods) | Objects7 | **Objects7** (keep) | Core data layer, belongs with models |
| `extractMemoriesFromResponse()` | Objects7 | **Objects7** (keep) | JSON parsing of LLM output, used by both Chat.java and Agent tools |
| `formatMemoriesAsContext()` | Objects7 | **Objects7** (keep) | MCP formatting, used by Chat.java prompt injection |
| Agent tool wrappers | Agent7 | **Agent7** (keep) | Must remain in Agent7 — these are tool registrations for the agent/planner system |
| `memoryExtractionPrompt.txt` | Agent7 | **Objects7** (move) | Used by `Chat.java` for keyframe extraction; should be co-located with the code that uses it |
| `memoryExtractionChain.json` | Agent7 | **Review/Simplify** | Chain is only used by agent tool path; with V2 simplification (Phase 2), may reduce to a single tool call |
| `InteractionExtractor` (new, Phase 3) | — | **Objects7** | Called from `Chat.java` keyframe pipeline |
| `interactionExtractionPrompt.txt` (new, Phase 3) | — | **Objects7** resources | Co-locate with `InteractionExtractor` |

### 1b.1 Actions

1. **Move prompt resources to Objects7:** Move `memoryExtractionPrompt.txt` (and its V2 replacement from Phase 2) to `Objects7/src/main/resources/olio/llm/prompts/`. This co-locates prompts with `Chat.java` which is their primary consumer.

2. **Keep Agent7 tool wrappers:** The `AM7AgentTool` search/extract methods must stay in Agent7 — they are agent tool registrations. But simplify them:
   - Remove the `extractMemories` tool's redundant parameter handling
   - Have it delegate directly to `MemoryUtil` with minimal wrapping

3. **Simplify or remove the extraction chain:** With Phase 2 reducing extraction to a single focused LLM call:
   - The 3-step chain (`searchByPair → LLM → extractTool`) is overkill
   - Step 1 (pre-fetch existing memories for dedup) is unnecessary because dedup happens at persist time in `MemoryUtil.createMemory()`
   - **Decision:** Replace the chain with a direct LLM call in `Chat.java` (which is already what keyframe extraction does). Keep the chain only if the agent/planner needs standalone memory extraction as a tool action.

4. **Consolidate prompt loading:** `Chat.java` currently loads prompts via `ResourceUtil.getResource()` from Agent7's classpath. After moving prompts to Objects7, update the resource path references.

5. **Document the boundary:** After consolidation, the split should be:
   - **Objects7:** All memory logic, models, prompts, utilities, extraction, search, formatting
   - **Agent7:** Tool registrations that expose Objects7 memory methods to the agent/planner system

### 1b.2 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestConsolidation_PromptLoadFromObjects7` | Prompt resources load correctly from Objects7 classpath |
| `TestConsolidation_AgentToolsStillWork` | Agent tool wrappers still delegate correctly to MemoryUtil |
| `TestConsolidation_KeyframeExtractionPath` | Chat.java keyframe extraction works with prompts in Objects7 |
| `TestConsolidation_AgentChainPath` | Agent chain (if retained) still executes correctly |
| `TestConsolidation_NoAgent7MemoryLogic` | Agent7 contains no memory business logic — only tool wrappers |

---

## Phase 2: Memory Extraction — Focused & Quiet

### Problem

The current single-prompt extraction tries to do too much:
- Extract ALL 10 memory types at once
- Identify interactions
- Produce structured JSON
- Deduplicate against existing memories

This results in verbose, inconsistent output. A 50-message conversation easily produces 50 memories.

### Solution: Two-Pass Focused Extraction

Split memory extraction into two separate, focused LLM calls:

**Pass 1: Memory Extraction** (focused, quiet)
- Extract only the **single most significant memory** from a conversation segment
- Constrained to ONE memory per segment
- Types limited to: FACT, RELATIONSHIP, DISCOVERY, DECISION, INSIGHT
- Remove BEHAVIOR, EMOTION, NOTE, ERROR_LESSON, OUTCOME from extraction targets (OUTCOME stays as keyframe-only type)

**Pass 2: Interaction Evaluation** (separate call, Phase 3)
- Handled entirely in Phase 3 as a distinct operation

### 2.1 New Memory Extraction Prompt

**File:** `memoryExtractionPromptV2.txt`

```
You are a memory analyst. Extract the SINGLE most important memory from this conversation segment.

Characters: {{systemCharName}} and {{userCharName}}
Setting: {{setting}}

Conversation:
{{conversationSegment}}

Rules:
- Extract exactly ONE memory — the single most significant fact, relationship change, discovery, decision, or insight
- If nothing is worth remembering, return an empty array: []
- Do NOT extract trivial greetings, small talk, or obvious context

Return a JSON array with 0 or 1 elements:
[{"content": "...", "summary": "...", "memoryType": "FACT|RELATIONSHIP|DISCOVERY|DECISION|INSIGHT", "importance": 1-10}]

Return ONLY the JSON array. No other text.
```

### 2.2 Revised Extraction Chain

**File:** `memoryExtractionChainV2.json`

Two-step chain (down from three):

| Step | Type | Action |
|------|------|--------|
| 1 | LLM | Extract single memory using focused prompt |
| 2 | Tool | Persist via `extractMemories` tool (with dedup) |

Remove step 1 (searchMemoriesByPair for 50 existing memories) from the chain. Dedup is handled at persist time by `MemoryUtil.createMemory()` which already calls `findSemanticDuplicate()`.

### 2.3 Extraction Frequency Tuning

| Parameter | Current | New | Rationale |
|-----------|---------|-----|-----------|
| `keyframeEvery` | 20 | 30 | Larger windows = fewer extractions |
| `memoryExtractionEvery` | 0 (every keyframe) | 1 (every keyframe) | Keep 1:1 but with larger windows |
| Max memories per extraction | 5 (prompt says 1-5) | 1 (hard limit) | One memory per segment |

**Expected outcome:** A 50-message conversation triggers ~1-2 keyframes, each producing 1 memory = 1-2 memories (down from 15-30+).

### 2.4 MemoryUtil.extractMemoriesFromResponse() Simplification

```java
public static List<BaseRecord> extractMemoriesFromResponse(
        BaseRecord user, String llmResponse, String sourceUri,
        String conversationId, BaseRecord person1, BaseRecord person2) {

    // 1. Strip markdown code fences if present (keep this one convenience)
    String cleaned = stripCodeFences(llmResponse);

    // 2. Parse as JSON array — this is the ONLY supported format
    JSONArray arr = parseJsonArray(cleaned);
    if (arr == null) {
        logger.warn("Memory extraction returned non-JSON: " +
            cleaned.substring(0, Math.min(100, cleaned.length())));
        return Collections.emptyList();
    }

    // 3. Limit to 1 memory max (enforce prompt contract)
    int limit = Math.min(arr.length(), 1);

    // 4. Create memories with dedup
    List<BaseRecord> memories = new ArrayList<>();
    for (int i = 0; i < limit; i++) {
        // ... existing JSON→memory creation logic
        // ... existing dedup via findSemanticDuplicate()
    }
    return memories;
}
```

### 2.5 Configurable Extraction

Add new fields to `chatConfigModel.json`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `memoryExtractionMaxPerSegment` | int | 1 | Max memories per extraction (1-5) |
| `memoryExtractionTypes` | string | "FACT,RELATIONSHIP,DISCOVERY,DECISION,INSIGHT" | Comma-separated types to extract |
| `memoryExtractionPrompt` | string | "memoryExtractionV2" | Resource name for extraction prompt |

These fields allow per-chat-config tuning without code changes. Power users can increase `memoryExtractionMaxPerSegment` for detailed worlds; default is 1 for quiet operation.

### 2.6 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestMemExtract_SingleMemory` | 50-message conversation produces <= 2 memories |
| `TestMemExtract_EmptyArray` | Trivial conversation produces 0 memories |
| `TestMemExtract_JsonOnly` | Non-JSON LLM response returns empty list (no text fallback) |
| `TestMemExtract_DedupStillWorks` | Duplicate content is caught and merged |
| `TestMemExtract_ConfigurableTypes` | Custom type list filters extraction |
| `TestMemExtract_ConfigurableMax` | Max per segment is respected |
| `TestMemExtract_NewPromptV2` | memoryExtractionPromptV2.txt produces valid JSON output |
| `TestMemExtract_ChainV2` | memoryExtractionChainV2.json 2-step chain executes correctly |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_MemoryPanel_ReducedCount` | After a conversation, memory count badge shows <= 2 (not 10+) |
| `Ux_MemoryPanel_TypeIcons` | Extracted memories display correct type icons (FACT, RELATIONSHIP, etc.) |
| `Ux_Chat_KeyframeIndicator` | Background activity indicator shows during keyframe/extraction |
| `Ux_MemoryPanel_ForceExtract` | Manual "Extract Memories" button works with new V2 prompt |

---

## Phase 3: Interaction Events — Complete Implementation

### Problem

The `olio.interaction` model and `InteractionEnumType` (41 types) exist, `InteractionAction` maps them to component actions, but chat conversations never create persisted interaction records. The connection between conversations and interactions was never completed.

### Solution

Create a dedicated interaction extraction pass that runs after memory extraction in the keyframe pipeline. This is the second of two focused LLM calls (Phase 2 handles memory, Phase 3 handles interaction).

### 3.1 Interaction Extraction Prompt

**File:** `interactionExtractionPrompt.txt`

```
You are analyzing a conversation between two characters to identify the primary interaction.

Characters: {{systemCharName}} and {{userCharName}}
Setting: {{setting}}

Conversation:
{{conversationSegment}}

Classify the PRIMARY interaction using EXACTLY ONE of these types:
ACCOMMODATE, ALLY, BEFRIEND, COMMERCE, COMPETE, CONFLICT, COOPERATE,
CORRESPOND, COERCE, COMBAT, CRITICIZE, DATE, DEBATE, DEFEND, ENTERTAIN,
EXCHANGE, EXPRESS_GRATITUDE, EXPRESS_INDIFFERENCE, HELP, INTIMATE,
INVESTIGATE, MENTOR, NEGOTIATE, OPPOSE, PEER_PRESSURE, RECREATE,
ROMANCE, SHUN, SOCIALIZE, THREATEN, BREAK_UP, NONE

For each character, assess the outcome: FAVORABLE, UNFAVORABLE, NEUTRAL, MIXED

Return a JSON object:
{"type": "SOCIALIZE", "description": "Brief description of what happened",
 "actorOutcome": "FAVORABLE", "interactorOutcome": "NEUTRAL"}

If the conversation is trivial small talk with no meaningful interaction, return:
{"type": "NONE"}

Return ONLY the JSON object. No other text.
```

### 3.2 InteractionExtractor (New Class — Objects7)

**File:** `InteractionExtractor.java`

```java
public class InteractionExtractor {

    /**
     * Extract and persist an interaction from a conversation segment.
     * Called from the keyframe pipeline AFTER memory extraction.
     *
     * @return persisted olio.interaction or null if type=NONE
     */
    public static BaseRecord extractInteraction(
            BaseRecord user,
            BaseRecord chatConfig,
            BaseRecord systemChar,
            BaseRecord userChar,
            String conversationSegment,
            String conversationId) {

        // 1. Load interaction extraction prompt
        // 2. Build LLM request (temperature 0.3, max_tokens 200)
        // 3. Call LLM
        // 4. Parse JSON response
        // 5. If type == "NONE", return null
        // 6. Create olio.interaction record:
        //    - actor = systemChar
        //    - interactor = userChar
        //    - type = parsed InteractionEnumType
        //    - description = parsed description
        //    - actorOutcome = parsed OutcomeEnumType
        //    - interactorOutcome = parsed OutcomeEnumType
        //    - state = COMPLETE
        //    - interactionStart/End = conversation timestamps
        // 7. Persist and return
    }
}
```

### 3.3 Keyframe Pipeline Integration

Modify `Chat.addKeyFrameAsync()` to add interaction extraction after memory extraction:

```
Current pipeline:
  1. Build keyframe request
  2. LLM analysis call (keyframe summary)
  3. Persist as OUTCOME memory
  4. Extract discrete memories (Phase 2 — now 1 memory max)

New pipeline:
  1. Build keyframe request
  2. LLM analysis call (keyframe summary)
  3. Persist as OUTCOME memory
  4. Extract single focused memory (Phase 2)
  5. NEW: Extract interaction (Phase 3)
  6. NEW: Associate interaction with chat event (Phase 4)
```

### 3.4 Temporal Pinning

Each memory created in step 4 gets a temporal pin to the interaction from step 5:

- Add field `interactionId` (string, max 128) to `tool.memory` model
- When interaction is extracted, set `memory.interactionId = interaction.objectId`
- This links memories to specific interactions at specific points in the conversation timeline

### 3.5 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestInteraction_ExtractFromChat` | Conversation produces a persisted olio.interaction |
| `TestInteraction_TypeClassification` | Argument → CONFLICT, help request → HELP, etc. |
| `TestInteraction_OutcomeAssessment` | Actor/interactor outcomes are set correctly |
| `TestInteraction_NoneForSmallTalk` | Trivial chat returns type=NONE, no record created |
| `TestInteraction_TemporalPin` | Memory's interactionId links to the extracted interaction |
| `TestInteraction_CanonicalPairing` | Interaction actor/interactor matches canonical person order |
| `TestInteraction_PromptOutput` | interactionExtractionPrompt.txt produces valid JSON with type and outcomes |
| `TestInteraction_PersistenceRoundtrip` | Created interaction can be queried back by actor/interactor |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_Chat_InteractionExtracted` | After conversation with keyframe, interaction is visible via REST query |
| `Ux_MemoryPanel_InteractionIdVisible` | Memory detail view shows linked interaction when interactionId is set |

---

## Phase 4: Chat Events — olio.event Per Conversation

### Problem

There is no event record for chat conversations. The `olio.event` model exists with full participant/interaction/timeline support but is only used for game world events, not for conversations.

### Solution

Create an `olio.event` for each chat session (chatRequest), and make interaction records members of that event.

### 4.1 Event Creation

**When:** First message of a new chat session (in `Chat.continueChat()` or `ChatService.newSession()`)

**Event structure:**
```json
{
  "schema": "olio.event",
  "name": "Conversation: {chatTitle or chatRequest.name}",
  "type": "INTERACT",
  "state": "IN_PROGRESS",
  "actors": [systemCharacter],
  "participants": [userCharacter],
  "eventStart": "{first message timestamp}",
  "interactions": []
}
```

**Lifecycle:**
1. **Created** when chat session starts → state = `IN_PROGRESS`
2. **Updated** when interactions are extracted (Phase 3) → `interactions[]` appended
3. **Closed** when chat ends or is archived → state = `COMPLETE`, `eventEnd` set

### 4.2 ChatConfig Event Binding

The `chatConfig` model already has an `event` field (model: olio.event). Use this to bind the conversation event:

```java
// On first message:
BaseRecord event = EventUtil.getOrCreateChatEvent(user, chatConfig, chatRequest);
chatConfig.set("event", event);
// persist
```

### 4.3 Interaction → Event Association

When `InteractionExtractor.extractInteraction()` (Phase 3) creates an interaction:

```java
BaseRecord event = chatConfig.get("event");
if (event != null) {
    List<BaseRecord> interactions = event.get("interactions");
    interactions.add(interaction);
    // persist event
}
```

### 4.4 ChatEventUtil (New Utility — Objects7)

**File:** `ChatEventUtil.java`

```java
public class ChatEventUtil {

    /** Get or create the event for a chat conversation */
    public static BaseRecord getOrCreateChatEvent(
            BaseRecord user, BaseRecord chatConfig, BaseRecord chatRequest) {
        // Check if event already exists on chatConfig
        // If not, create new INTERACT event with participants
        // Bind to chatConfig.event
        // Return event
    }

    /** Close the event when conversation ends */
    public static void closeChatEvent(BaseRecord user, BaseRecord chatConfig) {
        // Set state = COMPLETE
        // Set eventEnd = now
        // Persist
    }

    /** Add an interaction to the chat event */
    public static void addInteractionToEvent(
            BaseRecord user, BaseRecord chatConfig, BaseRecord interaction) {
        // Append to event.interactions[]
        // Persist
    }
}
```

### 4.5 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestChatEvent_CreatedOnFirstMessage` | New chat creates an olio.event |
| `TestChatEvent_InteractionMembership` | Extracted interactions are members of the event |
| `TestChatEvent_EventTimeline` | eventStart/eventEnd bracket the conversation |
| `TestChatEvent_EventQuery` | Events can be queried by participant (character) |
| `TestChatEvent_ChatConfigBinding` | chatConfig.event references the event |
| `TestChatEvent_IdempotentCreate` | Second message doesn't create a duplicate event |
| `TestChatEvent_CloseOnEnd` | Closing a chat sets event state to COMPLETE and eventEnd |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_SessionInfo_EventLink` | Session Info section shows associated event after first message |
| `Ux_Chat_EventCreatedOnSend` | Sending first message creates event (verify via REST) |

---

## Phase 5: Cross-Character Memory Recall & Gossip

### Problem

Currently, memory recall is limited to the direct character pair in the conversation. There is no mechanism for:
- A character to recall memories from conversations with OTHER characters
- A user to see memories that are contextually related across character boundaries
- "Gossip" — dynamically surfacing cross-character memories during conversation

### 5.1 Two-Degree Memory Recall (System Side)

**Concept:** If Don (system) is talking to Rex (user), Don should be able to recall memories from Don's conversation with Mike (memory #1), IF the recalled memory is semantically relevant to the current conversation.

**Implementation — New retrieval layer in `retrieveRelevantMemories()`:**

```
Current 3-layer retrieval:
  Layer 1 (50%): Pair-specific (Don+Rex)
  Layer 2 (30%): Character-specific (Don with anyone)
  Layer 3 (20%): Semantic (vector similarity to current message)

New 4-layer retrieval:
  Layer 1 (40%): Pair-specific (Don+Rex)
  Layer 2 (25%): Character-specific (Don with anyone)
  Layer 3 (15%): Semantic (vector similarity to current message, Don-only)
  Layer 4 (20%): NEW — Cross-character (Don's memories with others, filtered by relevance)
```

**Layer 4 logic:**
1. Get all persons Don has memories with: `MemoryUtil.getMemoryPartners(user, donId)`
2. For each partner, search memories by semantic similarity to current conversation
3. Filter by minimum relevance threshold (0.7)
4. This is the "gossip pool" — memories Don could reference from other conversations

**Gating:** Layer 4 only activates when `gossipEnabled = true` on chatConfig (new field).

### 5.2 Gossip Feature (User Side — Ux7)

**Concept:** As the user types a response, the UI searches for semantically related memories from THEIR character's other conversations. If a good match is found, a "gossip" button glows, allowing the user to inject that memory into the chat.

#### 5.2.1 Dynamic Memory Suggestions

**Trigger:** On each keystroke (debounced 500ms) in the chat input AND on each system response:

```javascript
// In chat.js input handler
let debounceTimer = null;
function onInputChange(text) {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
        searchGossipMemories(text);
    }, 500);
}

// Also triggered when system response arrives
function onSystemResponse(response) {
    searchGossipMemories(response.content);
}
```

**Search:** POST to `/rest/memory/gossip` endpoint (new):
```json
{
  "personId": "userCharacter.objectId",
  "excludePairPersonId": "systemCharacter.objectId",
  "query": "current input text + last system response",
  "limit": 5,
  "threshold": 0.65
}
```

**Response:** Array of memories from other conversations, sorted by relevance.

#### 5.2.2 Gossip Button

**Location:** Next to the chat input, as an icon button

**States:**
- **Dim:** No relevant gossip memories found
- **Glowing (pulse animation):** One or more relevant memories found
- **Badge count:** Number of available gossip memories

**Click behavior:** Opens a flyout showing the top gossip memories. User clicks one to inject it as `${memory.*objectId*}` token in the chat input. The token gets resolved to an MCP context block before sending.

#### 5.2.3 Involuntary Gossip (D20 Mechanic)

**Concept:** If the user's character has low stats, they may involuntarily share a gossip memory. This models a character who "can't keep a secret."

**Trigger conditions (ALL must be true):**
- `gossipEnabled = true` on chatConfig
- Gossip memories are available (from the search above)
- Character meets ANY of:
  - `intelligence < 8`
  - `willpower < 8` (maps to `mentalStrength` in the stat model)
  - `age < 13`

**Mechanic:**
```javascript
function checkInvoluntaryGossip(character, gossipMemories) {
    if (gossipMemories.length === 0) return null;

    let int = character.statistics.intelligence || 10;
    let wil = character.statistics.mentalStrength || 10;
    let age = character.age || 20;

    if (int >= 8 && wil >= 8 && age >= 13) return null;

    // D20 roll — lower stats = higher chance
    // DC = average of (int, wil) capped at 15
    let dc = Math.min(Math.floor((int + wil) / 2), 15);
    if (age < 13) dc = Math.max(dc - 3, 2); // Kids have lower DC

    let roll = Math.floor(Math.random() * 20) + 1; // 1-20

    if (roll < dc) {
        // Failed save — character blabs
        // Pick the most relevant gossip memory
        let blabMemory = gossipMemories[0];
        return blabMemory;
    }
    return null;
}
```

**UX when involuntary gossip triggers:**
1. Brief animation on the gossip button (shake + glow)
2. Toast message: "*{characterName} couldn't help but mention...*"
3. Memory token is automatically appended to the user's next message
4. User can manually remove it before sending (not forced)

**Reference implementation:** Use the existing `ActionUtil` or `StatisticsUtil` stat resolution patterns. The D20 mechanic follows the same pattern as the card game system where random outcomes are gated by character stats.

### 5.3 New REST Endpoint

**File:** `MemoryService.java`

```java
@POST
@Path("/gossip")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public Response searchGossipMemories(String body) {
    // 1. Parse personId, excludePairPersonId, query, limit, threshold
    // 2. Get all memory partners for personId
    // 3. Exclude the current pair partner
    // 4. Semantic search across remaining partner memories
    // 5. Return sorted by relevance
}
```

### 5.4 New MemoryUtil Methods

```java
/** Get all person IDs that share memories with the given person */
public static List<Long> getMemoryPartners(BaseRecord user, long personId)

/** Search memories across all partners except the excluded one */
public static List<BaseRecord> searchCrossCharacterMemories(
    BaseRecord user, long personId, long excludePartnerId,
    String query, int limit, double threshold)
```

### 5.5 ChatConfig New Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `gossipEnabled` | boolean | false | Enable cross-character memory recall and gossip |
| `gossipThreshold` | double | 0.65 | Minimum relevance for gossip suggestions |
| `gossipMaxSuggestions` | int | 5 | Max gossip memories to suggest |

### 5.6 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestGossip_CrossCharacterRecall` | Don recalls memory from Don+Mike conversation while talking to Rex |
| `TestGossip_ExcludesCurrentPair` | Gossip search excludes memories from the active pair |
| `TestGossip_SemanticRelevance` | Only semantically relevant cross-character memories are returned |
| `TestGossip_GatingByConfig` | gossipEnabled=false returns no cross-character memories |
| `TestGossip_InvoluntaryD20` | Low-stat character triggers involuntary gossip at expected rate (~100 rolls, verify distribution) |
| `TestGossip_HighStatNoBlabs` | High-stat character (int>=8, wil>=8, age>=13) never involuntarily blabs |
| `TestGossip_LayerFourInjection` | System prompt includes Layer 4 gossip memories when enabled |
| `TestGossip_MemoryPartners` | getMemoryPartners returns correct partner list |
| `TestGossip_RestEndpoint` | POST /rest/memory/gossip returns correct results with exclusion |
| `TestGossip_EmptyWhenNoPartners` | Gossip search returns empty when character has no other memory partners |
| `TestGossip_ThresholdFiltering` | Results below gossipThreshold are excluded |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_GossipButton_DimWhenNoMatches` | Gossip button is dim/inactive when no cross-character memories match |
| `Ux_GossipButton_GlowOnMatch` | Gossip button glows with pulse animation when relevant memories found |
| `Ux_GossipButton_BadgeCount` | Badge shows correct count of available gossip memories |
| `Ux_GossipButton_FlyoutList` | Clicking glowing gossip button opens flyout with memory list |
| `Ux_GossipButton_InjectToken` | Clicking a gossip memory inserts ${memory.*objectId*} token into input |
| `Ux_GossipButton_TokenResolved` | Sending a message with memory token resolves to MCP context block |
| `Ux_Gossip_DebounceSearch` | Typing triggers gossip search only after 500ms pause |
| `Ux_Gossip_SearchOnResponse` | Gossip search triggers automatically when system response arrives |
| `Ux_Gossip_InvoluntaryToast` | Toast message appears when low-stat character involuntarily blabs |
| `Ux_Gossip_InvoluntaryRemovable` | Involuntarily added memory token can be removed before sending |
| `Ux_Gossip_DisabledWhenOff` | Gossip button hidden when gossipEnabled=false on chatConfig |

---

## Phase 6: Vector Memory Cleanup

### Problem

When a `tool.memory` record is deleted, its associated `tool.vectorMemory` records are NOT automatically deleted. This leaves orphaned vector embeddings consuming storage and polluting semantic search results.

### 6.1 Cascade Delete on Memory Removal

**Modify:** `MemoryService.deleteMemory()` (Service7) or add a delete hook in Objects7

```java
// Before deleting the memory record:
VectorUtil vectorUtil = new VectorUtil(...);
int deleted = vectorUtil.deleteVectorStore(memoryRecord,
    OlioModelNames.MODEL_VECTOR_MEMORY);
logger.info("Deleted " + deleted + " vector records for memory " + objectId);

// Then delete the memory record itself
```

### 6.2 Orphan Cleanup Enhancement

**Modify:** `StatementUtil.getDeleteOrphanTemplate()` — already handles `MODEL_VECTOR_EXT` orphans generically. Verify that `tool.vectorMemory` records referencing deleted `tool.memory` records are caught by the existing orphan SQL:

```sql
DELETE FROM tool_vectormemory
WHERE vectorReferenceType = 'tool.memory'
AND vectorReference > 0
AND vectorReference NOT IN (SELECT id FROM tool_memory)
```

If this is already covered by the generic vector orphan logic (lines 202-226 in StatementUtil), then no additional SQL is needed — just verify with a test.

### 6.3 Batch Cleanup Command

Add a dedicated memory cleanup command to `AdminAction`:

```java
case "--cleanup-memories":
    // 1. Delete orphaned vectorMemory records
    // 2. Delete memories with no person references (broken records)
    // 3. Report counts
    break;
```

Also expose via REST: `DELETE /rest/memory/cleanup`

### 6.4 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestVectorCleanup_CascadeOnDelete` | Deleting memory deletes its vectorMemory records |
| `TestVectorCleanup_OrphanDetection` | Orphan cleanup finds vectorMemory with missing parent |
| `TestVectorCleanup_BatchCleanup` | Batch cleanup removes all orphaned vectors |
| `TestVectorCleanup_NoFalsePositives` | Valid vectors are not deleted |
| `TestVectorCleanup_RestEndpoint` | DELETE /rest/memory/cleanup returns correct count |
| `TestVectorCleanup_SearchAfterDelete` | Semantic search no longer returns deleted memory's content |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_MemoryPanel_DeleteCascades` | Deleting a memory via UI removes it from list AND from semantic search results |
| `Ux_MemoryPanel_CountUpdatesOnDelete` | Memory count badge decrements after deletion |

---

## Phase 7: Auto-Title & Auto-Icon Fix

### Problem

Auto-title and auto-icon have never worked. The implementation exists but has multiple cascading failure points.

### Root Causes & Fixes

### 7.1 Fix: chatRequestObjectId Availability

**Problem:** In buffer mode, `this.chatRequestObjectId` may be null.

**Fix:** Pass the chatRequest objectId through the Chat constructor or via a setter called by ChatService before `continueChat()`. Also accept it as a parameter on `continueChat()` itself:

```java
public void continueChat(OpenAIRequest req, String message, String chatRequestObjectId) {
    this.chatRequestObjectId = chatRequestObjectId;
    // ... rest of method
}
```

### 7.2 Fix: Async Race Condition

**Problem:** Cache cleared before async title job reads from it.

**Fix:** Don't use the cache for title generation. Pass the chatRequest record directly to the async job:

```java
// In continueChat(), buffer mode:
if (autoTitle && userMsgCount >= 1) {
    // Query chatRequest NOW, synchronously, before launching async
    BaseRecord chatReq = queryChatRequest(chatRequestObjectId);
    if (chatReq != null && chatReq.get("chatTitle") == null) {
        CompletableFuture.runAsync(() -> {
            String[] result = generateChatTitleAndIcon(req);
            if (result[0] != null) {
                setChatTitle(chatReq, result[0]);
                if (listener != null) listener.onChatTitle(user, req, chatReq.get("objectId"), result[0]);
            }
            if (result[1] != null) {
                setChatIcon(chatReq, result[1]);
                if (listener != null) listener.onChatIcon(user, req, chatReq.get("objectId"), result[1]);
            }
        });
    }
}
```

### 7.3 Fix: Retry on Failure

**Problem:** No retry if LLM call fails or times out.

**Fix:** Add a simple retry with backoff:

```java
public String[] generateChatTitleAndIcon(OpenAIRequest req) {
    for (int attempt = 0; attempt < 2; attempt++) {
        try {
            // ... existing generation logic
            String[] result = parseResponse(response);
            if (result[0] != null) return result;
        } catch (Exception e) {
            logger.warn("Title generation attempt " + (attempt + 1) + " failed: " + e.getMessage());
            if (attempt == 0) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
    }
    // Fallback: derive title from first user message
    return new String[] { deriveFallbackTitle(req), "chat" };
}

private String deriveFallbackTitle(OpenAIRequest req) {
    // Extract first user message, truncate to 40 chars at word boundary
    // e.g., "Tell me about the northern gate" → "Tell me about the northern gate"
    // e.g., "I want to discuss our plans for the upcoming siege..." → "Plans for the upcoming siege"
}
```

### 7.4 Fix: Default Value Alignment

**Problem:** Model says `autoTitle` defaults to `true` but runtime may default to `false`.

**Fix:** Ensure `chatConfigModel.json` has `"default": true` for `autoTitle` AND that `Chat.configureChat()` reads it with `chatConfig.get("autoTitle", true)` (defaulting to true if not set).

### 7.5 Fix: Streaming Mode Cache Timing

**Problem:** `clearCache(oid)` runs in `ChatListener.oncomplete()` before the async title job can read from cache.

**Fix:** Remove cache dependency entirely (see 7.2). The title job receives the chatRequest record directly, not from cache.

### 7.6 UI Display

Already implemented correctly in ConversationManager.js:
- WebSocket handlers for `chatTitle` and `chatIcon` events exist
- Display uses `session.chatTitle || session.name || "(unnamed)"`
- Icon renders as Material Symbols when present

The UI side needs no changes — only the backend generation needs fixing.

### 7.7 Tests

#### Backend Tests

| Test | Description |
|------|-------------|
| `TestAutoTitle_BufferMode` | Title generated after first exchange in buffer mode |
| `TestAutoTitle_StreamMode` | Title generated after first exchange in streaming mode |
| `TestAutoTitle_Retry` | Title generated on second attempt after first failure |
| `TestAutoTitle_Fallback` | Fallback title from first user message when LLM fails twice |
| `TestAutoTitle_NoOverwrite` | Existing title is not overwritten on subsequent messages |
| `TestAutoTitle_IconGenerated` | Material Symbols icon name is generated alongside title |
| `TestAutoTitle_Persistence` | Title and icon persisted to chatRequest record |
| `TestAutoTitle_WebSocketEvent` | WebSocket chirp sent with correct chatRequestId |
| `TestAutoTitle_DefaultTrue` | New chatConfig has autoTitle=true by default |
| `TestAutoTitle_ChatRequestIdPassed` | chatRequestObjectId is available in both buffer and stream paths |

#### Ux Tests

| Test | Description |
|------|-------------|
| `Ux_AutoTitle_AppearsAfterFirstExchange` | Title appears in session list after sending first message and receiving response |
| `Ux_AutoTitle_IconDisplayed` | Material Symbols icon renders next to title in session list |
| `Ux_AutoTitle_FallbackDisplayed` | If LLM fails, fallback title (from first message) still appears |
| `Ux_AutoTitle_GeneratingIndicator` | "Generating..." placeholder shown during title generation |
| `Ux_AutoTitle_NameDimmed` | Original chatRequest.name is shown dimmed below auto-title |
| `Ux_AutoTitle_WebSocketUpdate` | Title updates in real-time via WebSocket without page refresh |

---

## Phase 8: Chat UI Refactor

### 8.1 Collapsible Sidebar

**Current:** Left sidebar (280px fixed) shows ConversationManager, MemoryPanel, and ContextPanel stacked vertically. Always visible when not in fullMode.

**New:** Sidebar becomes a collapsible drawer that defaults to collapsed, showing only icon buttons. Clicking expands to full panel.

```
┌─────────────────────────────────────────────────────────┐
│ [≡] [≡ collapsed sidebar]  │  Conversation Area         │
│ [📋]                        │                            │
│ [🧠]                        │  ┌──────────────────────┐  │
│ [🔗]                        │  │ Message 1            │  │
│                             │  │ Message 2            │  │
│                             │  │ ...                  │  │
│                             │  └──────────────────────┘  │
│                             │                            │
│                             │  [input] [gossip] [send]   │
└─────────────────────────────────────────────────────────┘

Collapsed: 48px wide (icon buttons only)
Expanded: 300px wide (current panels)
```

**Icon buttons (collapsed state):**
- `list` — Conversations (ConversationManager)
- `psychology` — Memories (MemoryPanel)
- `link` — Context (ContextPanel)

**Expand behavior:** Click any icon to expand the sidebar showing that panel. Click again or click the collapse arrow to close.

### 8.2 Combine Info & Details Tabs

**Current:** ConversationManager has a "Details" section at the bottom showing: name, config (model, trim, stream, prune, autotune), promptConfig, systemCharacter, userCharacter, context.

**New:** Merge this into a single "Session Info" section within ConversationManager:

```
Session Info (collapsible)
├── Title: {chatTitle} (editable)
├── Icon: {chatIcon} (editable)
├── Model: gpt-4
├── Characters: {sysChar.name} ↔ {usrChar.name}
├── Config: {chatConfig.name}
├── Prompt: {promptConfig.name}
└── Context: {contextType} - {context.name}
```

Remove the separate ContextPanel component. Its attach/detach functionality moves into the Session Info section as action buttons.

### 8.3 Conversation List Density

**Current:** Each session item uses `flyout-button` styling with `px-2 py-1.5` padding and default text size.

**New:** Compact list items:

```css
.session-item {
    padding: 4px 8px;       /* was 6px 12px */
    font-size: 0.8125rem;   /* 13px, was 0.875rem / 14px */
    line-height: 1.25rem;   /* 20px */
    gap: 4px;               /* was 8px */
}

.session-item .material-symbols-outlined {
    font-size: 18px;        /* was 24px */
}
```

**Result:** ~30% more sessions visible without scrolling.

### 8.4 Message Bubble Adjustments

**Current:** `px-3 py-1.5 text-sm font-light max-w-[85%] border rounded-md`

Keep current styling — it's already reasonable. Only change:
- Remove `font-light` (300 weight is too thin for readability) → use default `font-normal` (400)
- Increase `max-w-[85%]` to `max-w-[90%]` for wider messages

### 8.5 Gossip Button Integration

Add the gossip button to the chat toolbar (between Feature Tools group and Input Area):

```
Toolbar Layout (revised)
├── Left Group (view controls)
├── Divider
├── Feature Tools Group
│   ├── ... existing tools ...
│   └── Gossip Button (NEW — psychology icon with pulse animation)
├── Divider
├── Input Area
├── Divider
└── Send Group
```

### 8.6 Auto-Title/Icon Display

**Current:** ConversationManager shows `session.chatTitle || session.name` with optional icon.

**New:** Show auto-generated title prominently with icon:

```
Session List Item:
┌─────────────────────────────────────┐
│ [🎭 icon] Auto Title Here          │
│           chatRequest.name (dimmed) │
└─────────────────────────────────────┘
```

- Auto-generated title is primary (larger, bold)
- Original chatRequest.name is secondary (smaller, dimmed, below title)
- If no auto-title yet, show chatRequest.name as primary with a subtle "generating..." indicator during the first exchange

### 8.7 Tests

#### Ux Tests — Sidebar

| Test | Description |
|------|-------------|
| `Ux_Sidebar_CollapsedDefault` | Sidebar defaults to collapsed state (48px icon strip) |
| `Ux_Sidebar_ExpandOnClick` | Clicking icon expands sidebar to 300px showing corresponding panel |
| `Ux_Sidebar_CollapseOnToggle` | Clicking expanded panel's icon or collapse arrow closes sidebar |
| `Ux_Sidebar_PanelSwitch` | Clicking a different icon switches to that panel without collapsing first |
| `Ux_Sidebar_ConversationSpace` | Conversation area takes full width minus 48px when sidebar collapsed |
| `Ux_Sidebar_IconTooltips` | Collapsed icon buttons show tooltips (Conversations, Memories, Context) |

#### Ux Tests — Session Info (Combined Info/Details)

| Test | Description |
|------|-------------|
| `Ux_SessionInfo_AllFieldsVisible` | Title, icon, model, characters, config, prompt, context all shown |
| `Ux_SessionInfo_TitleEditable` | Chat title field is editable inline |
| `Ux_SessionInfo_IconEditable` | Chat icon field is editable inline |
| `Ux_SessionInfo_AttachDetach` | Attach/detach character and context actions work from Session Info |
| `Ux_SessionInfo_Collapsible` | Session Info section collapses and expands |
| `Ux_SessionInfo_ContextPanelRemoved` | Separate ContextPanel component no longer rendered |

#### Ux Tests — Conversation List Density

| Test | Description |
|------|-------------|
| `Ux_ConvList_CompactPadding` | Session items use reduced padding (4px 8px) |
| `Ux_ConvList_SmallerFont` | Session item text renders at 13px |
| `Ux_ConvList_SmallerIcons` | Session item icons render at 18px |
| `Ux_ConvList_MoreVisible` | ~30% more sessions visible in same viewport height |
| `Ux_ConvList_TruncatesLongTitles` | Long titles truncate with ellipsis |

#### Ux Tests — Auto-Title Display

| Test | Description |
|------|-------------|
| `Ux_ConvList_AutoTitlePrimary` | Auto-generated title is displayed as primary (larger, bold) |
| `Ux_ConvList_NameSecondary` | chatRequest.name is displayed below title (smaller, dimmed) |
| `Ux_ConvList_IconRendered` | Material Symbols icon renders to the left of the title |
| `Ux_ConvList_GeneratingState` | "Generating..." indicator shown during first exchange before title arrives |
| `Ux_ConvList_FallbackToName` | If no auto-title, chatRequest.name is shown as primary |

#### Ux Tests — Message Bubbles

| Test | Description |
|------|-------------|
| `Ux_Messages_FontWeight` | Message text renders at font-weight 400 (not 300) |
| `Ux_Messages_MaxWidth` | Message bubbles use max-w-[90%] |
| `Ux_Messages_ReadableContrast` | Both user and assistant message text is legible in dark and light mode |

#### Ux Tests — Gossip Button

| Test | Description |
|------|-------------|
| `Ux_GossipBtn_ToolbarPosition` | Gossip button is positioned in the Feature Tools group |
| `Ux_GossipBtn_HiddenWhenDisabled` | Button not shown when gossipEnabled=false |
| `Ux_GossipBtn_DimNoMatches` | Button is dim/inactive when no gossip memories match |
| `Ux_GossipBtn_PulseOnMatch` | Button pulses/glows when gossip memories are available |
| `Ux_GossipBtn_FlyoutOpens` | Clicking glowing button opens flyout with memory list |
| `Ux_GossipBtn_InjectOnSelect` | Selecting a memory from flyout inserts token into chat input |

---

## Integration Tests

**All changes require tests. No exceptions.** All backend tests are integration tests with full service access (LLM, embedding API, database, prepopulated characters). All Ux changes require functional Ux tests.

### Test Infrastructure Requirements

- Running Ollama server with `valkyriesys/eudaimonia-dryad3-vision` model (smallest available; override in Objects7 `resource.properties`)
- PostgreSQL with pgvector extension
- Prepopulated test characters (at least 3: e.g., Mike, Don, Rex)
- Prepopulated world/setting for character context
- Running Service7 instance for Ux tests

### Test Counts by Phase

| Phase | Backend Tests | Ux Tests | Total |
|-------|---------------|----------|-------|
| Phase 1: Cleanup | 5 | 3 | 8 |
| Phase 1b: Agent7/Objects7 Consolidation | 5 | 0 | 5 |
| Phase 2: Extraction | 8 | 4 | 12 |
| Phase 3: Interactions | 8 | 2 | 10 |
| Phase 4: Events | 7 | 2 | 9 |
| Phase 5: Gossip | 11 | 11 | 22 |
| Phase 6: Vector Cleanup | 6 | 2 | 8 |
| Phase 7: Auto-Title | 10 | 6 | 16 |
| Phase 8: Chat UI | 0 | 28 | 28 |
| E2E | 1 | 1 | 2 |
| **Total** | **61** | **59** | **120** |

### Test Execution Order

Tests should be run in phase order since later phases depend on earlier ones:

```
Phase 1 Tests (cleanup validation)
  └── Phase 2 Tests (focused extraction)
      └── Phase 3 Tests (interaction extraction)
          └── Phase 4 Tests (chat events)
              └── Phase 5 Tests (gossip/cross-character)
                  └── Phase 6 Tests (vector cleanup)
                      └── Phase 7 Tests (auto-title/icon)
                          └── Phase 8 Tests (UI)
                              └── E2E Tests
```

### End-to-End Integration Test

**File:** `TestMemoryRefactorE2E.java`

```
Scenario: Full conversation lifecycle with all refactored features

Setup:
  - 3 characters: Mike, Don, Rex
  - gossipEnabled = true
  - autoTitle = true
  - extractMemories = true
  - keyframeEvery = 10

Step 1: Mike (system) + Don (user) conversation — 12 messages
  → Verify: olio.event created for conversation
  → Verify: 1 keyframe triggered at message 10
  → Verify: <= 1 memory extracted
  → Verify: 1 interaction extracted (e.g., DEBATE)
  → Verify: interaction is member of conversation event
  → Verify: memory has interactionId pointing to interaction
  → Verify: auto-title and auto-icon generated after first exchange

Step 2: Rex (system) + Mike (user) conversation — 12 messages
  → Verify: separate olio.event created
  → Verify: <= 1 memory extracted
  → Verify: 1 interaction extracted

Step 3: Don (system) + Rex (user) conversation — 6 messages
  → Verify: Don's system prompt includes Layer 4 gossip memories
  → Verify: Don may recall memory from Don+Mike argument (Step 1)
  → Verify: /rest/memory/gossip returns Rex's memories from Rex+Mike (Step 2)
  → Verify: gossip search excludes Don+Rex pair memories

Step 4: Delete a memory from Step 1
  → Verify: associated vectorMemory records are deleted
  → Verify: orphan cleanup finds no remaining orphans

Step 5: Verify total memory count
  → Verify: <= 4 total memories across all 3 conversations (was 30-50+ before)
```

### End-to-End Ux Test

**Test:** `Ux_E2E_FullConversationLifecycle`

```
Scenario: Verify all UI features work together through a complete conversation lifecycle

Setup:
  - 3 characters available in the UI (Mike, Don, Rex)
  - gossipEnabled = true, autoTitle = true on chatConfig

Step 1: Create new chat session (Mike system, Don user)
  → Verify: Session appears in conversation list
  → Verify: Sidebar collapsed by default, expandable

Step 2: Send first message and receive response
  → Verify: Auto-title and icon appear in session list (or "Generating..." then title)
  → Verify: chatRequest.name is dimmed below auto-title
  → Verify: Background activity indicator appears during keyframe/extraction

Step 3: Continue conversation to 12 messages
  → Verify: Memory count badge updates in MemoryPanel
  → Verify: <= 2 memories shown in pair view
  → Verify: Memory type icons are correct

Step 4: Open Session Info
  → Verify: All fields present (title, icon, model, characters, config, prompt)
  → Verify: Event is linked

Step 5: Start new chat (Don system, Rex user) and send messages
  → Verify: Gossip button appears (gossipEnabled=true)
  → Verify: Gossip button glows if Don has relevant memories from Step 1
  → Verify: Clicking gossip button shows flyout with memories from Don+Mike
  → Verify: Selecting a memory injects token into input

Step 6: Delete a memory from MemoryPanel
  → Verify: Memory disappears from list
  → Verify: Count badge decrements
  → Verify: Semantic search no longer returns it

Step 7: Verify conversation list density
  → Verify: Multiple sessions visible with compact styling
  → Verify: Titles truncate with ellipsis
```

---

## File Impact Matrix

### New Files

| File | Module | Description |
|------|--------|-------------|
| `InteractionExtractor.java` | Objects7 | Extract interactions from conversation segments |
| `ChatEventUtil.java` | Objects7 | Manage olio.event records for conversations |
| `interactionExtractionPrompt.txt` | Agent7 | Prompt for interaction classification |
| `memoryExtractionPromptV2.txt` | Agent7 | Focused single-memory extraction prompt |
| `memoryExtractionChainV2.json` | Agent7 | Simplified 2-step extraction chain |
| `TestMemoryRefactorE2E.java` | Objects7/test | End-to-end integration test |
| `TestInteractionExtractor.java` | Objects7/test | Interaction extraction tests |
| `TestChatEvent.java` | Objects7/test | Chat event lifecycle tests |
| `TestGossipMemory.java` | Objects7/test | Cross-character gossip tests |
| `TestAutoTitleFix.java` | Objects7/test | Auto-title/icon fix verification |
| `TestVectorCleanup.java` | Objects7/test | Vector cleanup cascade tests |

### Modified Files

| File | Module | Changes |
|------|--------|---------|
| `MemoryUtil.java` | Objects7 | Remove text parser fallback, add gossip methods, add interactionId field handling |
| `Chat.java` | Objects7 | Fix auto-title, add interaction extraction to keyframe pipeline, add event creation |
| `ChatUtil.java` | Objects7 | Add helper methods for events and interaction persistence |
| `InteractionUtil.java` | Objects7 | Remove commented-out guessReason methods |
| `memoryModel.json` | Objects7 | Add `interactionId` field |
| `chatConfigModel.json` | Objects7 | Add gossip fields, extraction config fields |
| `ChatListener.java` | Objects7 | Fix auto-title cache race condition |
| `MemoryService.java` | Service7 | Add gossip endpoint, add vector cascade delete |
| `AM7AgentTool.java` | Agent7 | Update extractMemories tool for new prompt |
| `chat.js` | Ux7 | Collapsible sidebar, gossip button, density changes |
| `ConversationManager.js` | Ux7 | Combined info/details, compact list, auto-title display |
| `MemoryPanel.js` | Ux7 | Adjust for sidebar collapse |
| `ContextPanel.js` | Ux7 | Merge into ConversationManager or remove |
| `LLMConnector.js` | Ux7 | Add gossip memory search API calls |
| `pageClient.js` | Ux7 | Handle gossip-related WebSocket events |
| `basiTail.css` | Ux7 | Collapsible sidebar styles, compact list styles, gossip button animation |

### Deleted Files

| File | Module | Reason |
|------|--------|--------|
| `memoryExtractionPrompt.txt` | Agent7 | Replaced by V2 |
| `memoryExtractionChain.json` | Agent7 | Replaced by V2 |
| `ContextPanel.js` | Ux7 | Merged into ConversationManager (if fully merged) |

---

## Risk & Dependencies

### Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Reduced memory count loses important information | Medium | Configurable `memoryExtractionMaxPerSegment` lets users increase if needed |
| Interaction extraction LLM call adds latency to keyframe pipeline | Low | Runs async, doesn't block chat response |
| Gossip feature adds API calls on every keystroke | Medium | Debounce (500ms), cache results, limit to 5 results |
| D20 involuntary gossip may frustrate users | Low | Only triggers for low-stat characters; user can remove token before sending |
| Auto-title retry adds latency | Low | Retry only once with 1s backoff; fallback is instant |

### Dependencies

| Dependency | Required For | Status |
|------------|-------------|--------|
| PostgreSQL + pgvector | All vector operations | Already deployed |
| LLM endpoint (OpenAI/Ollama) | Memory extraction, interaction extraction, auto-title | Already deployed |
| WebSocket service | Real-time gossip, auto-title events | Already deployed |
| olio.interaction model | Phase 3 | Already exists |
| olio.event model | Phase 4 | Already exists |
| Character statistics model | Phase 5 D20 mechanic | Already exists |

### Implementation Order Constraints

```
Phase 1 (cleanup) ──→ Phase 1b (consolidation) ──→ Phase 2 (extraction) ──→ Phase 3 (interactions)
                                                                                    │
                                                                                    ├──→ Phase 4 (events)
                                                                                    │
Phase 6 (vector cleanup) ←── can run in parallel ─────────────────────────────────→ Phase 5 (gossip)
                                                                                    │
Phase 7 (auto-title) ←── independent ─────────────────────────────────────────────→ Phase 8 (UI)
```

- Phases 1→1b→2→3→4 are sequential (each builds on the previous)
- Phase 1b must complete before Phase 2 since prompts are being relocated
- Phase 5 requires Phase 3 (interactions exist to associate with gossip)
- Phase 6 is independent and can run in parallel with Phases 3-5
- Phase 7 is independent and can run in parallel with Phases 3-6
- Phase 8 depends on Phase 5 (gossip UI) and Phase 7 (auto-title display) but can start CSS/layout work in parallel
