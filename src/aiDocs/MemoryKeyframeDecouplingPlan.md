# Memory / Keyframe Decoupling Plan

**Status:** Draft — awaiting approval
**Date:** 2026-05-30
**Author context:** Memory creation is currently gated on `keyframeEvery`. The
keyframe pipeline was the original "memory" concept; it has since accreted a
parallel multi-aspect memory extraction path, but the trigger and lifecycle
remained shared. Per direction: leave keyframes alone as a legacy/disabled-by-default
feature, build memory as a fully independent system.

---

## 1. Current state — comprehensive review

### 1.1 Schema fields (chatConfigModel.json)

Memory + keyframe-related fields today:

| Field | Default | Description (today) | Actual behavior |
|-------|---------|---------------------|-----------------|
| `keyframeEvery` | 0 | "messages after previous keyframe before conducting analysis" | Triggers async keyframe LLM call + downstream memory pipeline |
| `lastKeyframeAt` | 0 | bookkeeping counter | Updated eagerly before async launch |
| `extractMemories` | (none) | "agent layer will automatically extract memories" | Enables memory creation IF keyframe also fires |
| `memoryExtractionEvery` | 0 | "**N keyframes** between automatic memory extraction from keyframe analysis text. 0=every keyframe" | Gates `persistKeyframeAsMemory` (OUTCOME-type) — NOT the multi-aspect path |
| `memoryExtractionPrompt` | (none) | "memory extraction prompt resource name" | Selects between V2 and multi-aspect parser |
| `memoryExtractionMaxPerSegment` | 1 | "max memories per segment" | Used by V2 path |
| `memoryExtractionTypes` | "FACT,RELATIONSHIP,DISCOVERY,DECISION,INSIGHT" | "allowed memory types" | Used by V2 path |
| `memoryBudget` | 0 | "Token budget for memory context injection" | Used by retrieval, not creation |
| `maxConversationMemories` | 100 | "max memories per conversation" | Hard cap on persisted memories |
| `keyframeSkipEchoThreshold` | 0.7 | "skip keyframe if segment is echo" | Used by Chat.checkKeyframeTrigger — applies to BOTH summary and multi-aspect |
| `memoryRecencyHalfLifeMinutes` | 30 | retrieval recency penalty | Used by Phase 2 retrieval scorer |
| `memoryMmrLambda` | 0.5 | retrieval MMR weight | Used by Phase 2 retrieval scorer |
| `memoryEssentialImportance` | 8 | recency-bypass threshold | Used by Phase 2 retrieval scorer |
| `memoryDedupSimilarity` | 0.8 | dedup threshold | Used by Phase 2 retrieval scorer |
| `memoryInjectionStyle` | "mcp" | "mcp" or "systemSection" | Used by Phase 4 formatter |
| `qualityEvaluatorEvery` | 0 | quality evaluator cadence (Phase 6) | Independent |
| `streamIdleTimeoutSeconds` | 30 | stream watchdog | Independent |

### 1.2 Code flow (today)

```
User sends message
  → Chat.chatInternal
  → ChatListener.oncomplete (or buffer-mode completion)
  → flushPendingKeyframe (if pendingKeyframeSnapshot was captured)
  → addKeyFrameAsync
      ├── chat(kfReq)                          ← LLM call #1 (keyframe analysis)
      ├── persistKeyframeAsMemory              ← creates OUTCOME memory
      │       └── (gated by memoryExtractionEvery: every Nth keyframe)
      └── extractMemoriesIfEnabled             ← UNCONDITIONALLY called on every keyframe
              └── extractMemoriesFromSegment
                      └── chat(extractReq)     ← LLM call #2 (multi-aspect or V2)
                              └── MemoryUtil.createMemory ×N (typed memories)
```

Triggering:
- `Chat.checkKeyframeTrigger` is called from `pruneCount` after every assistant turn.
- Fires when `msgSinceLastKeyframe >= keyframeEvery`.
- Captures `pendingKeyframeSnapshot` for deferred launch after main response completes.

### 1.3 Implementation pain points

1. **Naming vs behaviour:** `memoryExtractionEvery` is documented as "every N keyframes" but the natural reading and the form-field hint say "every N messages". Also it only affects the OUTCOME memory, not the multi-aspect path.
2. **Coupled gating:** `extractMemories=true && keyframeEvery=0` is force-rewritten to `keyframeEvery=5` in `configureChat` ([Chat.java:391-398](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L391-L398)). The user cannot have memory extraction without paying for keyframe analysis.
3. **Two LLM calls per cadence event:** every time the keyframe fires, BOTH the analysis call AND the extraction call run. There is no way to get only multi-aspect memories without the OUTCOME summary.
4. **Shared lock:** Phase 5.2's `tryAcquireAsyncLLMSlot("keyframe")` covers both summary and multi-aspect under one slot. Reasonable today; will need re-evaluation when they decouple.
5. **`memoryExtractionEvery`'s OUTCOME-skip logic counts existing OUTCOME memories** ([Chat.java:2752-2764](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L2752-L2764)) which is a DB query per keyframe — a hot path inside an async pipeline.
6. **`keyframeSkipEchoThreshold` is shared** — skipping a keyframe due to echo also skips memory extraction, which is the opposite of what's desirable (echo means the LLM is stuck — extracting memories from prior good content could help).
7. **`forceExtractMemories`** uses `keyFrameEvery` as the chunk size ([Chat.java:1368](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L1368)), with a code comment explicitly acknowledging the cross-purpose use.

### 1.4 Memory model (tool.memory)

The persisted memory record has these notable fields (independent of extraction trigger):
- `content`, `summary`
- `memoryType` (FACT, RELATIONSHIP, DECISION, INSIGHT, DISCOVERY, EMOTION, OUTCOME, NOTE, EVENT)
- `importance` (1-10)
- `sourceUri` (`am7://keyframe/<cfgObjId>` or `am7://memory/<cfgObjId>` depending on path)
- `conversationId` (chatConfig objectId)
- `person1Id`, `person2Id` (canonical pair, min/max for cross-character retrieval)
- `interactionModel`, `interactionId` (linked interaction, if any)
- `createdDate`, `modifiedDate`

The model is fine. The trigger and lifecycle are what need decoupling.

---

## 2. Target architecture

### 2.1 Two independent pipelines

**Keyframe pipeline (legacy, default off):**
- `keyframeEvery=0` by default — feature off unless user opts in.
- When enabled: every N messages, run keyframe analysis LLM call, persist as OUTCOME memory.
- That's it — no downstream memory extraction.
- Retains existing `lastKeyframeAt`, `keyframeSkipEchoThreshold`.

**Memory pipeline (new, independent):**
- `extractMemories=true` enables it.
- `memoryExtractionEvery` (renamed semantic: "N messages between extractions", default 5).
- New counter: `lastMemoryExtractionAt` (parallel to `lastKeyframeAt`).
- New trigger: `Chat.checkMemoryExtractionTrigger(req)` called from same place as `checkKeyframeTrigger`.
- When due: capture snapshot, launch async multi-aspect extraction on messages since `lastMemoryExtractionAt`.
- Persists FACT/RELATIONSHIP/DECISION/INSIGHT/DISCOVERY typed memories per the configured prompt (V2 or multi-aspect).
- Independent echo gate: new `memorySkipEchoThreshold` (defaults to same 0.7 but lets users tune separately).
- Independent unified-slot label: `"memory"` instead of `"keyframe"`.

### 2.2 Schema delta

**Default change:**
- `keyframeEvery`: stays default 0 (already correct).
- `memoryExtractionEvery`: change default from 0 to **5** with new description: "Messages between automatic memory extractions. 0 = disabled."

**Removals:**
- Remove the `configureChat` auto-upgrade (`extractMemories=true && keyframeEvery=0 → keyframeEvery=5`). Memory no longer needs keyframes.
- Remove the OUTCOME-count gating in `persistKeyframeAsMemory` (it conflated the two cadences).

**Additions:**
```json
{
  "name": "lastMemoryExtractionAt",
  "type": "int",
  "default": 0,
  "description": "Message count at which the last memory extraction ran. Bookkeeping for checkMemoryExtractionTrigger."
},
{
  "name": "memorySkipEchoThreshold",
  "type": "double",
  "default": 0.7,
  "minValue": 0.0,
  "maxValue": 1.0,
  "description": "Skip memory extraction (no LLM call) when the segment's pairwise echo Jaccard exceeds this. Independent of keyframeSkipEchoThreshold so memory and keyframe can tune separately."
}
```

**Description rewrites for clarity:**
- `extractMemories`: "When true, the memory pipeline runs independently on `memoryExtractionEvery` cadence. Does NOT require keyframes."
- `memoryExtractionEvery`: "Number of messages between automatic memory extractions. 0 = disabled. Independent of keyframeEvery."
- `keyframeEvery`: "Legacy keyframe summary pipeline. 0 = disabled (default). When enabled, runs an analysis LLM call every N messages and stores the result as an OUTCOME memory. The newer per-message memory extraction (extractMemories + memoryExtractionEvery) is the recommended path."

### 2.3 Code changes

**[Chat.java](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java):**

1. **Remove auto-upgrade** at lines 387-398. Don't override `keyframeEvery`.
2. **Add fields:**
   ```java
   private int memoryExtractionEvery = 0;
   private volatile List<OpenAIMessage> pendingMemorySnapshot = null;
   private volatile int pendingMemoryStartIdx = 0;
   private volatile boolean asyncMemoryInProgress = false;
   private static final ConcurrentHashMap<String, Long> activeMemoryExtractions = new ConcurrentHashMap<>();
   ```
3. **`configureChat`:** read `memoryExtractionEvery` from chatConfig (don't conflate with keyframeEvery).
4. **New `checkMemoryExtractionTrigger(req)`** modeled on `checkKeyframeTrigger`:
   - Reads `extractMemories`, `memoryExtractionEvery`, `lastMemoryExtractionAt`.
   - Computes msgsSinceLastExtraction.
   - If due: per-instance + per-config CAS acquire (parallel to keyframe locks).
   - Capture snapshot to `pendingMemorySnapshot` / `pendingMemoryStartIdx`.
   - Eagerly persist `lastMemoryExtractionAt = msgSize`.
5. **New `flushPendingMemory(req)`** modeled on `flushPendingKeyframe`:
   - Re-applies the unified async-LLM slot (label `"memory"`).
   - Re-applies pressure deferral.
   - Launches `extractMemoriesAsync(req, snapshot, startIdx)`.
6. **New `extractMemoriesAsync`** (renamed/refactored from `extractMemoriesIfEnabled`):
   - Same multi-aspect / V2 dispatch, but standalone.
   - Receives the segment range explicitly rather than inferring from keyframe context.
7. **`addKeyFrameAsync`**: remove the call to `extractMemoriesIfEnabled`. Keyframe pipeline ONLY persists the OUTCOME summary now.
8. **`persistKeyframeAsMemory`**: remove the `memoryExtractionEvery`-keyed skip logic (lines 2742-2764). Every keyframe (when enabled) produces an OUTCOME memory; no separate gate.
9. **Wire** `checkMemoryExtractionTrigger` and `flushPendingMemory` into the same call sites as the keyframe equivalents:
   - Trigger: at the end of `pruneCount` ([Chat.java:3064-3066](AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L3064-L3066)).
   - Flush: in `continueChat` (buffer mode, around line 572) and `ChatListener.oncomplete` (streaming, where `flushPendingKeyframe` is called).

**[chatConfigModel.json](AccountManagerObjects7/src/main/resources/models/olio/llm/chatConfigModel.json):**
- Add `lastMemoryExtractionAt`, `memorySkipEchoThreshold`.
- Update default + description of `memoryExtractionEvery`.
- Update description of `keyframeEvery` and `extractMemories`.

**[Ux752 modelDef.js](AccountManagerUx752/src/core/modelDef.js)** + **[formDef.js](AccountManagerUx752/src/core/formDef.js):**
- Mirror schema additions.
- Add form fields with hints.

**[Chat duel test (TestChatDuelLong)](AccountManagerObjects7/src/test/java/org/cote/accountmanager/objects/tests/TestChatDuelLong.java):**
- Add `-Dduel.memoryExtractionEvery=N` override.
- Default `keyframeEvery=0`, `memoryExtractionEvery=5` to exercise the new pipeline.

### 2.4 Migration

- Existing chatConfigs with `keyframeEvery>0` keep working — keyframe summaries still fire.
- Existing chatConfigs with `extractMemories=true && keyframeEvery=0` previously had `keyframeEvery` silently force-bumped to 5. Post-change, those configs will fire memory extraction on the NEW `memoryExtractionEvery` cadence (default 5 after the schema change). Equivalent behaviour, just no longer through the keyframe path. **No data migration needed.**
- Memory records persisted under either pipeline use the same `tool.memory` schema. Retrieval reads both transparently. `sourceUri` distinguishes their origin (`am7://keyframe/...` vs `am7://memory/...`).

---

## 3. Backend unit tests

All new pure-logic should be covered without DB / LLM:

**`TestMemoryExtractionTrigger`** (new, pure-Java):
- 12-15 tests for the standalone trigger decision:
  - `memoryExtractionEvery=0` → never triggers
  - `extractMemories=false` → never triggers
  - `msgSize - lastMemoryExtractionAt < every` → no trigger
  - `msgSize - lastMemoryExtractionAt == every` → trigger
  - `> every` → trigger
  - `assist=true` offset honored
  - Negative `lastMemoryExtractionAt` resets to 0
  - Stale `lastMemoryExtractionAt > msgSize` resets
  - Independence: `keyframeEvery=0` does not affect memory trigger
  - Independence: `keyframeEvery=5` does not affect memory cadence

Extract the trigger decision into a pure helper (à la `Chat.isUnderPressure`):
```java
public static boolean shouldExtractMemory(boolean enabled, int every, int msgSize, int lastAt, int introOverhead);
```

**Existing tests to update:**
- `TestEchoSuppression` — verify it still works (echo is independent of new pipeline).
- `TestMemoryRetrievalScorer` — unaffected, but rerun.
- `TestAsyncLLMSlotRegistry` — add a test exercising the new `"memory"` slot label alongside `"keyframe"`.

**Memory persistence path:**
- `TestMemoryUtilCreate` (new, requires DB seed via existing test harness):
  - createMemory roundtrips through DB with expected fields
  - canonPair (min/max) correctly assigned
  - Retrieval by pair returns memory regardless of insertion order
  - `maxConversationMemories` cap enforced

**Backend chat-flow integration (no LLM, mocked Ollama response):**
- `TestChatMemoryPipelineMocked` (new):
  - Mock the LLM to return canned multi-aspect JSON.
  - Drive `Chat` through 10 turns with `memoryExtractionEvery=3`.
  - Assert: 3 trigger events, 3 LLM calls, ~3 sets of typed memories persisted.
  - Assert: same run with `keyframeEvery=0` produces 0 OUTCOME memories.
  - Assert: `keyframeEvery=5, memoryExtractionEvery=0` produces only OUTCOME memories.
  - Assert: `keyframeEvery=5, memoryExtractionEvery=3` produces both, on independent cadences.

---

## 4. UX Playwright tests

**`memoryConfigForm.spec.js`** (new):
- Verify the new form fields render with correct labels/hints.
- `memoryExtractionEvery` slider/input accepts 0 and round-trips (using the existing zero-value fix).
- `memorySkipEchoThreshold` accepts 0.5, round-trips.
- `keyframeEvery=0` + `extractMemories=true` no longer triggers any UI warning that says "raising to 5".
- Save chatConfig with both pipelines off + `extractMemories=true` produces a valid save (no client-side overrides).

**`memoryViewer.spec.js`** (new — depends on existing memory list UI):
- Send a few chat messages.
- Wait for the async extraction.
- Refresh memory list panel.
- Verify the right kinds of memories appear:
  - `keyframeEvery=0, memoryExtractionEvery=3`: typed memories (FACT/RELATIONSHIP/etc.), no OUTCOME from keyframes.
  - `keyframeEvery=5, memoryExtractionEvery=0`: only OUTCOME, no typed memories.

**`chatConfigZeroValue.spec.js`** (existing — extend):
- Add cases for `memoryExtractionEvery=0` and `lastMemoryExtractionAt=0` round-trips.

---

## 5. Backend chat duel runnable as Playwright e2e

The existing `TestChatDuelLong` is a JUnit test that drives `Chat` instances directly. We can expose the same scenario as an end-to-end Playwright test that drives the actual Ux752 chat UI, so we cover the wire request, the listener events, and the rendering.

**`chatDuelE2E.spec.js`** (new):

Design:
1. **Setup (beforeAll):** create two character pairs via API. Create two chatConfigs (one per direction). Set defaults: `memoryExtractionEvery=5`, `keyframeEvery=0`, `extractMemories=true`, `qualityEvaluatorEvery=5`, `streamIdleTimeoutSeconds=30`. Use a low-cost model from the Ollama endpoint already configured.
2. **Per-turn loop:** Open chat A, send a message, wait for stream-complete event, parse the response. Open chat B (swapped characters), send the response as the next message, wait, parse, repeat for 20 turns per side.
3. **Per-turn assertions:**
   - `[QUAL]` log line is present on the page console (forwarded via listener).
   - Memory count grows over the duel (`/rest/llm/active` and `/rest/model/search` on `tool.memory`).
   - No `<think>` raw tags visible in rendered content (hidden by default via Phase 4).
   - `[ASYNC-SLOT]` blocks fire when expected; never two `"chat"` streams active at once.
4. **End-of-duel assertions:**
   - Final memory count > 0 per chatConfig.
   - Total LLM call count tracked (sum of streams + sync) within expected band.
   - Echo final value < 0.6 (with all phases active per Phase 4 results).
   - distinct final value > 0.4.
   - latencySlope ms/msgCount < 100 (essentially flat).

**Helpers:**
- Add `e2e/helpers/duel.js` with `runDuelTurn(page, configId, message)`, `pollMemoryCount(request, configId)`, `parseQualLine(consoleMsg)`.
- Subscribe to `page.on('console')` to collect `[QUAL]`, `[QUALITY]`, `[ASYNC-SLOT]`, `[DEFER]`, `[STREAM-IDLE]`, `[RETRIEVAL]` lines server-side via the `/rest/llm/active` endpoint OR via console forwarding from listener `onEvalProgress`.

**Why Playwright instead of just JUnit:**
- Catches UI regressions (form values not reflecting state, render bugs, listener event handling).
- Exercises the full wire stack including auth, session cookies, websocket events.
- Verifies the diagnostics surface (LLM debug panel) actually reflects what the backend logs.
- Tests `<think>` tag handling, `(Reminder` stripping, memory injection style end-to-end.

**Runtime:**
- ~3-5 minutes per duel pair against local Ollama with qwen3:8b.
- Tag as `@slow` so it doesn't run on every PR; nightly + manual.

### 5.1 Smaller "fast duel" variant

**`chatFastDuelE2E.spec.js`** (new):
- Same harness, 5 turns per side instead of 20.
- Single chatConfig (no character swap).
- Runtime <60s.
- Runs on every PR — smoke test that the duel pipeline doesn't crash.

---

## 6. Implementation order

| # | Task | Effort | Risk |
|---|------|--------|------|
| 1 | Schema additions + descriptions + defaults | 1h | Low |
| 2 | Ux752 modelDef + formDef mirror | 30m | Low |
| 3 | `Chat.shouldExtractMemory` pure helper + 12-15 unit tests | 2h | Low |
| 4 | New trigger / flush / async methods in Chat.java | 4h | Med (touches hot path; needs careful lock + slot review) |
| 5 | Remove auto-upgrade in `configureChat` | 30m | Low |
| 6 | Remove OUTCOME-count gating in `persistKeyframeAsMemory` | 30m | Low |
| 7 | Update `TestChatDuelLong` to use new overrides + run baseline | 1h | Low |
| 8 | `TestChatMemoryPipelineMocked` (3-4 scenarios) | 3h | Med |
| 9 | `memoryConfigForm.spec.js` | 1.5h | Low |
| 10 | `memoryViewer.spec.js` | 2h | Med (depends on what memory UI exists) |
| 11 | `e2e/helpers/duel.js` + `chatFastDuelE2E.spec.js` (5-turn) | 3h | Med |
| 12 | `chatDuelE2E.spec.js` (full 20-turn × 2 sides) | 2h | Low (helper reuse) |
| 13 | Update [ConversationQualityBaseline.md](aiDocs/ConversationQualityBaseline.md) with new behavior + re-baseline | 1h | Low |

**Total: ~21 hours.**

Suggested sequence: 1→2→3→4→5→6→7→8 (backend complete + tested) → 9→10 (UI mirror complete) → 11→12 (e2e validation) → 13 (docs).

---

## 7. Risks & open questions

1. **Performance:** adding a second independent trigger means another DB write per Nth message (`lastMemoryExtractionAt` update). With default `memoryExtractionEvery=5`, that's one extra update every 5 turns per chat. Cheap; should not be a concern.
2. **Concurrent flush:** keyframe flush + memory flush could race. Mitigation: both go through the Phase 5.2 unified async-LLM slot, so they serialize. Re-verify with the duel.
3. **Echo gating:** Splitting `keyframeSkipEchoThreshold` into a separate `memorySkipEchoThreshold` is the natural step but doubles config surface. **Open question:** keep them separate (more flexible) or share one threshold (simpler)? Recommended: separate, since the use cases differ (keyframe summary of echo is bad; memory extraction of pre-echo content may still be valuable).
4. **Backward compat:** any existing chatConfig with `extractMemories=true && keyframeEvery=0` was previously force-bumped to keyframeEvery=5. After the change, those configs will fire memory extraction (good) but NOT keyframe summaries (different from before). Most users probably want this; flag in release notes.
5. **`memoryExtractionEvery` semantic change:** changing the description from "every N keyframes" to "every N messages" is a breaking semantic change for users who set this expecting the old meaning. With default 0 (current) vs default 5 (proposed), most existing chatConfigs are unaffected, but those that explicitly set it will need re-tuning. Flag in release notes.

---

## 8. Definition of done

- [ ] Schema fields shipped with new defaults + descriptions
- [ ] Ux752 modelDef + formDef mirror with form fields visible in chatConfig editor
- [ ] `Chat.shouldExtractMemory` + 12-15 unit tests passing
- [ ] `TestChatMemoryPipelineMocked` 3-4 scenarios passing
- [ ] Existing `TestChatDuelLong` updated; baseline re-run; results captured in Baseline doc
- [ ] `memoryConfigForm.spec.js` (form field round-trip) passing
- [ ] `memoryViewer.spec.js` (typed vs OUTCOME memory creation by config) passing
- [ ] `chatFastDuelE2E.spec.js` (5-turn smoke) passing — wired into CI
- [ ] `chatDuelE2E.spec.js` (20-turn × 2) passing — manual / nightly tag
- [ ] No regressions in the 191 existing unit tests
- [ ] No regressions in the 3-test chatConfigZeroValue spec
- [ ] Release note draft covering the backward-compatibility points in §7
