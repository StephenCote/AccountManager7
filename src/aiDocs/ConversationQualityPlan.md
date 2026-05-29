# Conversation Quality Backend Plan

**Goal:** efficient, lengthy, performant LLM conversation support. The LLM
should reasonably keep building on a conversation rather than stall out or
degenerate into an echo loop.

**Scope:** server-side only (Java in `AccountManagerObjects7` and
`AccountManagerService7`). No UI changes. No new external dependencies.

**Date drafted:** 2026-05-28
**Author context:** drafted after `TestChatDuelLong` ([Objects7/src/test/.../TestChatDuelLong.java](../AccountManagerObjects7/src/test/java/org/cote/accountmanager/objects/tests/TestChatDuelLong.java)) exposed:
- Pruning bug (every-other index marked) — FIXED via `OpenAIMessage(rec)` merge constructor
- Memory cache returning stale counts — FIXED via `setCache(false)` in `MemoryUtil.getConversationMemories`
- Echo loop: by turn 11-20, qwen3:8b produced identical 73-char responses repeatedly even with memory injection working

Those fixes are correctness; this plan addresses *content quality* and
*retrieval scoring*.

---

## Problem framing

In a long conversation three forces interact:

1. **Recent context** dominates next-turn computation — the last N messages on the wire
2. **Injected memories** backfill what pruning removed — currently 1-2 per turn after retrieval scoring
3. **Model behavior** drifts to path of least resistance (echo) when recent context is itself repetitive

Healthy: pruning trims noise, memories surface meaningful beats, LLM
builds forward.

Unhealthy: recent context is echo → keyframe summaries describe echo →
memory injection reinforces echo → LLM continues echo. Self-reinforcing.

The chat fixes shipped this session bound the wire payload (proven: wire
peaked at ~3.9K and decreased to 3.6K while msgCount grew from 30 to 39).
But the echo dynamic is upstream of pruning — it's about what content the
LLM sees and how it's framed.

## Goals (priority order)

1. **No stall**: turn latency stays roughly constant past 50+ exchanges (today: wire bounded; need to verify with phase 0)
2. **No echo**: assistant responses don't repeat verbatim or near-verbatim
3. **Forward momentum**: conversation builds on prior beats; new facts/decisions get woven in
4. **Async pipeline keeps up**: memory extraction doesn't fall multiple turns behind chat

## Constraints / non-goals

- Don't add more LLM calls per turn. Reduce where possible.
- Don't change the prompt template engine. Reuse existing tokens / sections.
- Don't break backward compatibility for existing chat sessions / configs.
- Don't add external dependencies; pure-Java implementations of similarity, MMR, etc.
- Don't switch models as a workaround. Pipeline should be sound on qwen3:8b.

## Files referenced in this plan

Server-side (`AccountManagerObjects7/src/main/java/org/cote/accountmanager/`):
- `olio/llm/Chat.java` — main chat orchestration, keyframes, memory extraction trigger
- `olio/llm/ChatUtil.java` — shared chat helpers, options apply, request shaping
- `olio/llm/ChatListener.java` — websocket / stream listener
- `olio/llm/OpenAIMessage.java` — message wrapper (recently fixed: merge constructor preserves ephemerals)
- `olio/llm/OpenAIRequest.java` — request wrapper
- `olio/llm/policy/ResponseComplianceEvaluator.java` — existing pattern for evaluators
- `util/MemoryUtil.java` — memory CRUD + retrieval entry points
- `util/LLMConnectionManager.java` — global stream tracking
- `mcp/McpContextBuilder.java` — MCP block formatting

Test (`AccountManagerObjects7/src/test/java/org/cote/accountmanager/objects/tests/`):
- `TestChatDuelLong.java` — long-duel diagnostic test (10-13 phases will add metric assertions here)

---

## Execution order

Recommended order — each phase's metrics inform the next:

| # | Phase | Effort | Risk | Direct goal |
|---|-------|--------|------|-------------|
| 0 | Diagnostics — measurement first | 1 day | Low | Baseline numbers |
| 1 | Echo detection + suppression | 2 days | Low | Anti-echo |
| 3 | Keyframe extraction overhaul | 3 days | Med | Memory pool quality |
| 2 | Retrieval scoring (MMR + recency) | 2-3 days | Low | Surface variety |
| 4 | Memory injection framing | 1-2 days | Med | LLM acts on memories |
| 5 | Async pipeline efficiency | 2-3 days | Med | Performance |
| 6 | Quality evaluator | 3-4 days | Low | Observability + auto-tune |

Phases 1-5: ~10-13 days. Phase 6: optional, ~4 days.

Phase 3 deliberately comes before Phase 2 — better memory content first,
then better retrieval of that content.

---

## Phase 0 — Diagnostics first

**Why first**: every subsequent change needs measurable acceptance
criteria. Today the test reports msgCount, wireBytes, elapsedMs,
respChars per turn — nothing about echo, memory utility, or topic
diversity.

### 0.1 Add `ConversationQualityMetrics` util

**New file**: `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/ConversationQualityMetrics.java`

Pure-Java, no LLM calls. Methods:

```java
public class ConversationQualityMetrics {
    /// Jaccard similarity over token shingles (k=3 by default).
    public static double shingleJaccard(String a, String b, int k);

    /// Average pairwise Jaccard of strings in a sliding window.
    /// Used to detect echo over the last N assistant responses.
    public static double avgPairwiseShingleJaccard(List<String> recent, int k);

    /// Levenshtein distance normalized to [0,1] by max length.
    public static double normalizedLevenshtein(String a, String b);

    /// Distinct-token ratio over a rolling window of recent messages.
    /// 1.0 = every token unique; <0.5 = heavy repetition.
    public static double distinctTokenRatio(List<String> recent);

    /// % of injected memory content tokens (named entities + nouns over 4 chars)
    /// that appear in the subsequent assistant response.
    public static double memoryUtilization(List<String> memorySummaries, String response);
}
```

Token shingles preferred over Levenshtein for echo detection: cheap and
catches paraphrased echoes too.

### 0.2 Wire metrics into `TestChatDuelLong`

Modify `runOneTurn` to call the metrics and emit a `[QUAL]` line per
turn:

```
[QUAL] turn=12 conv=A echo=0.42 distinctRatio=0.71 memUtil=0.15 elapsedMs=1247
```

Where:
- `echo`: avgPairwiseShingleJaccard over last 3 assistant responses for this conv
- `distinctRatio`: distinctTokenRatio over last 10 messages (both roles)
- `memUtil`: memoryUtilization for THIS turn's response vs the memories that were injected on THIS turn

Track which memories were injected by intercepting `Chat.retrieveRelevantMemories` returns — easiest path: add `getLastInjectedMemoryIds()` on `Chat`, populated each turn.

### 0.3 End-of-pair summary

After the duel completes, log a rollup:

```
[QUAL-SUMMARY] echoMean=0.31 echoMax=0.78 distinctMean=0.68 memUtilMean=0.22
[QUAL-SUMMARY] latencySlope=12.3 ms/turn (over msgCount 1..39)
```

Latency slope: ordinary least squares of `elapsedMs` against `msgCount`.
Near-zero slope = bounded performance. Positive slope = degradation as
conversation grows.

### Acceptance

- 20-turn duel emits per-turn `[QUAL]` and end `[QUAL-SUMMARY]` lines
- Baseline numbers captured into `aiDocs/ConversationQualityBaseline.md` (created in this phase)
- Test still passes; no behavior change

### Risk
None — read-only instrumentation.

### Dependencies
None.

---

## Phase 1 — Echo detection + suppression

**Highest direct leverage on the observed problem.** No new LLM calls;
pure local string math + one transient system message when triggered.

### 1.1 Track recent assistant responses on `Chat`

**File**: `Chat.java`

Add per-instance state:

```java
/// Rolling window of the last N assistant response contents, most recent first.
private final LinkedList<String> recentAssistantContent = new LinkedList<>();
private static final int RECENT_ASSISTANT_WINDOW = 3;

private void recordAssistantResponse(String content) {
    if (content == null || content.isEmpty()) return;
    recentAssistantContent.addFirst(content);
    while (recentAssistantContent.size() > RECENT_ASSISTANT_WINDOW) {
        recentAssistantContent.removeLast();
    }
}
```

Populate in `handleResponse` after `req.addMessage(...)`. Reset on
session-clear / new chat.

### 1.2 Detect echo before next chat request

**File**: `Chat.java`, inside `newMessage(...)` or just before the LLM
call in `continueChat`.

```java
private boolean isEchoing() {
    if (recentAssistantContent.size() < 2) return false;
    double avgSim = ConversationQualityMetrics.avgPairwiseShingleJaccard(
            recentAssistantContent, 3);
    return avgSim > echoThreshold;  // default 0.6
}
```

`echoThreshold` configurable via `chatConfig.echoSuppressionThreshold`
(new field, default 0.6, range 0-1, 0 = disabled).

### 1.3 Inject one-shot steering when echoing

When `isEchoing()` returns true, prepend a TRANSIENT system message to
the wire request for this turn only. Do NOT add it to
`req.getMessages()` (would persist into session).

Implementation: in `chatInternal` where `wireReq = ChatUtil.getPrunedRequest(req, ignoreFields)`, add the steering message to wireReq.messages at position 0 (or right after the existing system prompt) BEFORE serialization:

```java
if (isEchoing()) {
    OpenAIMessage steer = new OpenAIMessage();
    steer.setRole(systemRole);
    steer.setContent(
        "Recent responses have been very similar. Vary your direction: "
      + "introduce a new observation, ask an unanswered question, change "
      + "the scene, or build on something said earlier. Do not repeat "
      + "your previous responses."
    );
    List<OpenAIMessage> msgs = wireReq.getMessages();
    // insert after system prompt at position 1
    msgs.add(Math.min(1, msgs.size()), steer);
    wireReq.setMessages(msgs);
    logger.info("[ECHO] steering injected (avgSim=" + avgSim + ")");
}
```

Optionally bump `temperature` for this wire request only (don't mutate
the chatConfig). +0.2 default.

### 1.4 Reset behavior

If `isEchoing()` is false on a given turn, the steering message is just
not added. No persistent state to "reset" — recentAssistantContent
keeps rolling.

### 1.5 Schema field

**File**: `AccountManagerObjects7/src/main/resources/models/olio/llm/chatConfigModel.json`

Add:

```json
{
    "name": "echoSuppressionThreshold",
    "type": "double",
    "default": 0.6,
    "minValue": 0.0,
    "maxValue": 1.0,
    "description": "Jaccard similarity over last 3 assistant responses above which echo-steering injects a system message for the next turn. 0 = disabled."
},
{
    "name": "echoSuppressionTempBoost",
    "type": "double",
    "default": 0.2,
    "minValue": 0.0,
    "maxValue": 1.0,
    "description": "Amount to add to temperature for one turn when echo-steering triggers."
}
```

### 1.6 Test acceptance

Modify `TestChatDuelLong`:
- Assert: in a 20-turn duel, `[ECHO] steering injected` log fires at least once when the conversation hits qwen3:8b's typical echo onset (~turn 8-10).
- Assert: after a steering-injected turn, the next turn's echo metric drops by at least 0.1.
- Assert: end-of-run `[QUAL-SUMMARY] echoMax` < 0.85 (previously hit ~1.0 with identical responses).

### Risks

- **False positive**: legitimate back-and-forth that uses similar phrasing. Mitigation: tuned threshold (0.6 is moderate), and steering message is mild ("vary direction") not blocking.
- **Steering message disrupts character voice**: the LLM might break character to "vary direction". Mitigation: phrase as flavor-neutral creative-writing advice.

### Dependencies

Phase 0 (uses the metrics).

---

## Phase 3 — Keyframe extraction overhaul

(Out of numerical order — placed here because better content in the memory pool benefits all subsequent retrieval work.)

Current behavior ([Chat.java:2597-2678 `persistKeyframeAsMemory`](../AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L2597) and [Chat.java:2449-2593 `extractMemoriesFromText`](../AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L2449)):

- Each keyframe creates ONE OUTCOME memory (faithful summary, hand-written prompt)
- Plus spawns ONE extraction LLM call asking for "THE SINGLE MOST IMPORTANT memory"
- When conversation is echoing, summary IS the echo and extraction often returns `[]`

### 3.1 Multi-aspect extraction in one LLM call

**New prompt resource**: `AccountManagerObjects7/src/main/resources/olio/llm/prompts/extractMemoryMultiAspect.json`

```json
{
    "schema": "olio.llm.promptTemplate",
    "name": "extractMemoryMultiAspect",
    "system": [
        "You analyze conversation segments and extract structured memory entries.",
        "Each call returns up to 4 typed extractions OR null where not applicable.",
        "Be conservative — return null rather than fabricate. Empty segments produce all nulls.",
        "Respond with ONLY a JSON object. No markdown, no code fences."
    ],
    "user": [
        "Conversation segment between ${system.firstName} and ${user.firstName}:",
        "${segment}",
        "",
        "Extract:",
        "- new_fact: a concrete new fact revealed (name, place, ability, preference, etc.) OR null",
        "- decision: a choice made or commitment given OR null",
        "- tension: an unresolved question, disagreement, or unspoken thing OR null",
        "- relationship_change: how the dynamic between them shifted OR null",
        "",
        "For each non-null field provide: content (1-2 sentence summary), importance (1-10).",
        "Return JSON: {\"new_fact\":{\"content\":\"...\",\"importance\":N}|null, \"decision\":..., \"tension\":..., \"relationship_change\":...}"
    ]
}
```

### 3.2 Wire the new extraction path

**File**: `Chat.java`, `extractMemoriesFromText` method.

Add branching based on `chatConfig.memoryExtractionPrompt`:
- `"memoryExtractionV2"` (current) → existing behavior
- `"extractMemoryMultiAspect"` (new) → multi-aspect, parses JSON object with 4 typed keys

Map to memory types:
- `new_fact` → `MemoryTypeEnumType.FACT`
- `decision` → `MemoryTypeEnumType.DECISION`
- `tension` → `MemoryTypeEnumType.INSIGHT` (current types: FACT, RELATIONSHIP, DISCOVERY, DECISION, INSIGHT — INSIGHT is closest fit for unresolved tension)
- `relationship_change` → `MemoryTypeEnumType.RELATIONSHIP`

Each non-null extraction → one `MemoryUtil.createMemory` call with:
- content, summary (truncated), type as above
- importance from LLM (clamped to 1-10)
- sourceUri = `am7://keyframe-multi/<cfgObjId>`
- conversationId = cfgObjId
- person1=systemChar, person2=userChar

### 3.3 Skip keyframe when no progress detected

**File**: `Chat.java`, `checkKeyframeTrigger`.

Before deferring a keyframe, compute echo metric on the segment that
would be analyzed (messages since last keyframe). If high echo:

```java
List<OpenAIMessage> segment = req.getMessages().subList(lastKfAt, msgs.size());
List<String> assistantContent = segment.stream()
    .filter(m -> assistantRole.equals(m.getRole()))
    .map(OpenAIMessage::getContent)
    .filter(s -> s != null)
    .collect(Collectors.toList());

if (assistantContent.size() >= 2) {
    double segEcho = ConversationQualityMetrics.avgPairwiseShingleJaccard(assistantContent, 3);
    if (segEcho > keyframeSkipEchoThreshold) {  // default 0.7
        logger.info("Skipping keyframe — segment echo=" + segEcho + " (no new content)");
        return;
    }
}
```

This avoids burning an LLM call to extract memories from a degenerate
segment. The pool stays clean.

### 3.4 Schema field

**File**: `chatConfigModel.json`

```json
{
    "name": "keyframeSkipEchoThreshold",
    "type": "double",
    "default": 0.7,
    "minValue": 0.0,
    "maxValue": 1.0,
    "description": "Skip keyframe/memory-extraction if the segment's avg pairwise echo exceeds this. 1.0 = never skip."
}
```

### 3.5 Test acceptance

Modify `TestChatDuelLong`:
- Run with `memoryExtractionPrompt = "extractMemoryMultiAspect"` set on chatConfig
- Assert: memory pool composition shifts — DUMP shows FACT/DECISION/RELATIONSHIP/INSIGHT each > 0 (previously most were OUTCOME)
- Assert: at least one `Skipping keyframe — segment echo=...` log line appears in a 20-turn duel that visibly echoes (qwen3:8b echoing is reliable)
- Assert: total LLM call count per duel goes DOWN compared to baseline (Phase 0 captured this number)
- Assert: end-of-duel memory count is still ≥ existing baseline minus skipped-keyframe count

### Risks

- **JSON parse failures on small models**: qwen3:8b can produce malformed JSON. Mitigation: retry once on parse failure (existing pattern), strict validation, log+skip on second failure.
- **Skipping keyframes loses some content**: by design — we WANT to skip echo. If a small variation IS load-bearing and gets dropped, recency penalty in Phase 2 lets that variation through. Net: better signal-to-noise.

### Dependencies

Phase 0 (uses the metrics for skip detection).

---

## Phase 2 — Memory retrieval: diversity + recency penalty

Current `Chat.retrieveRelevantMemories` ([Chat.java:3533-3540](../AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L3533)) allocates 40/25/20/15 across RELATIONSHIP/FACT/DECISION+DISCOVERY/EMOTION and runs three layers (pair / character / semantic). Good type-coverage thinking. Within each layer it scores purely by vector similarity to the current turn's query.

### 2.1 Recency penalty

**New file**: `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/MemoryRetrievalScorer.java`

Pure-Java util:

```java
public class MemoryRetrievalScorer {
    /// Exponential decay: penalize memories created within the last
    /// `recencyHalfLifeMinutes` minutes (where they're still likely
    /// on the wire and don't need re-injection).
    public static double recencyMultiplier(long memoryCreatedMs, long nowMs, double halfLifeMinutes) {
        double ageMinutes = (nowMs - memoryCreatedMs) / 60000.0;
        if (ageMinutes <= 0) return 0.0;  // future / clock skew = penalize hard
        // soft penalty for very recent, full weight after ~3 half-lives
        return 1.0 - Math.exp(-ageMinutes / halfLifeMinutes);
    }

    /// Apply recency multiplier to a list of scored memories.
    /// IMPORTANT: HIGH-IMPORTANCE memories (importance >= essentialImportance)
    /// are EXEMPT — they bypass recency penalty entirely so load-bearing
    /// facts aren't accidentally suppressed.
    public static List<ScoredMemory> applyRecencyPenalty(
            List<ScoredMemory> in,
            long nowMs,
            double halfLifeMinutes,
            int essentialImportance);
}
```

### 2.2 MMR (Maximal Marginal Relevance)

Same file, within-type-bucket re-ranker:

```java
/// After top-K by similarity, re-rank for diversity.
/// MMR: score = λ * similarity(mem, query) - (1-λ) * max_similarity(mem, picked)
/// λ=0.5 balances relevance and diversity. λ=1.0 = pure similarity (current behavior).
public static List<ScoredMemory> mmrRerank(
        List<ScoredMemory> candidates,
        String queryText,
        int finalCount,
        double lambda,
        BiFunction<String, String, Double> textSimilarity);
```

For `textSimilarity` use the shingle Jaccard from Phase 0 (cheap, no
vector ops needed since we already have the candidates).

### 2.3 Dedup before injection

Before returning the final memory list:

```java
public static List<ScoredMemory> dedupBySimilarity(
        List<ScoredMemory> in,
        double similarityThreshold);  // default 0.8
```

Removes near-duplicates that would just be repetition in the injected
context.

### 2.4 Wire into `Chat.retrieveRelevantMemories`

Modify the method to apply, in order:
1. Existing layered retrieval (pair / character / semantic) gets top-K candidates
2. `applyRecencyPenalty(candidates, now, halfLifeMinutes=30, essentialImportance=8)`
3. `mmrRerank(candidates, currentTurnText, finalCount=memoryBudget/avgMemSize, lambda=0.5, shingleJaccard)`
4. `dedupBySimilarity(final, 0.8)`

### 2.5 Schema fields

**File**: `chatConfigModel.json`

```json
{
    "name": "memoryRecencyHalfLifeMinutes",
    "type": "double",
    "default": 30.0,
    "minValue": 0.0,
    "description": "Memories created within this window are down-weighted in retrieval (still on wire). 0 = disabled."
},
{
    "name": "memoryMmrLambda",
    "type": "double",
    "default": 0.5,
    "minValue": 0.0,
    "maxValue": 1.0,
    "description": "MMR weight between relevance (1.0) and diversity (0.0)."
},
{
    "name": "memoryEssentialImportance",
    "type": "int",
    "default": 8,
    "minValue": 1,
    "maxValue": 10,
    "description": "Memories with importance >= this bypass recency penalty (always eligible)."
}
```

### 2.6 Test acceptance

- Phase 0 metric `memUtil` improves: more injected memories are referenced in subsequent responses
- Echo metric improves alongside (LLM sees varied material)
- Memory injection count per turn unchanged (we're re-ranking, not increasing)
- Per-turn retrieval latency unchanged (<10ms — pure local math)
- No regression in existing memory tests

### Risks

- **Down-weighting a recent load-bearing fact**: mitigated by importance exemption (bumping importance to ≥8 in extraction prompts for FACT-type when they're concrete).
- **MMR over-diversifies in topic-focused conversations**: λ=0.5 is balanced; can tune per chatConfig.

### Dependencies

Phase 3 (better memory pool quality first; otherwise we're diversifying low-quality memories).

---

## Phase 4 — Memory injection framing

Currently memories are injected as a separate MCP context block.
The LLM has to figure out what to do with them.

### 4.1 New memory injection style: system-section

**File**: `Chat.java` (retrieve / format pipeline) and `mcp/McpContextBuilder.java`.

Add a new formatting path that injects memories AS PART OF the system
prompt at a marked section, rather than as a separate MCP block:

```
[existing system prompt content]

# What you know
- Earlier today, Elaahi mentioned her sister's wedding next month
- You agreed to bring the photos
- There's unresolved tension about whose family will attend

# How to respond
Continue the conversation. Reference what you know when relevant.
Do not repeat your previous responses.
```

### 4.2 Group memories by type with headers

Within the "# What you know" section, group:

```
# What you know

## Facts
- Elaahi's sister is getting married next month
- The venue is the old stone church

## Recent decisions
- Elaahi agreed to bring photos

## Unresolved
- Whose family will attend remains unsettled
```

LLMs treat structured headers as semantic separators — much easier to
act on than a flat list.

### 4.3 Backward compatibility

**Schema field** on `chatConfigModel.json`:

```json
{
    "name": "memoryInjectionStyle",
    "type": "string",
    "default": "mcp",
    "description": "How to inject retrieved memories: 'mcp' (current; separate MCP context block) or 'systemSection' (appended to system prompt with structured headers)."
}
```

Existing sessions keep `"mcp"` default. New chat templates can opt into
`"systemSection"`.

### 4.4 Memory formatter util

**New file**: `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/MemoryFormatter.java`

```java
public class MemoryFormatter {
    public static String asMcpBlock(List<BaseRecord> memories, String conversationId);

    /// Groups by memoryType, formats as markdown sections.
    public static String asSystemSection(List<BaseRecord> memories);
}
```

### 4.5 Wire into `Chat.refreshSystemPrompt` or equivalent

Detect `chatConfig.memoryInjectionStyle == "systemSection"`. If so:
- Retrieve memories (existing path)
- Format via `MemoryFormatter.asSystemSection`
- Append to system prompt content INSTEAD of building MCP block

### 4.6 Test acceptance

- New chatConfig with `memoryInjectionStyle = "systemSection"` runs end-to-end
- Phase 0 `memUtil` improves further (LLM more likely to use system-prompt content than MCP context block)
- No regression on `"mcp"` style sessions

### Risks

- **System prompt bloat**: memories pushed into system prompt make it larger per turn. Mitigation: this is no more bytes than the MCP block was; just relocated.
- **Prompt template engine doesn't have a memory section token yet**: may need to add `${memories.systemSection}` token to the engine or just append after the engine processes the rest of the template.

### Dependencies

Phase 2 (good quality memories to inject). Optionally Phase 3.

---

## Phase 5 — Async pipeline efficiency

Each main-chat turn potentially spawns:
- 1 keyframe LLM call (async, deferred until after main response)
- 1 extraction LLM call (per keyframe)

With Ollama single-slot, these queue serially. By turn 20 of a fast
duel, keyframes from turn 18 may still be processing.

### 5.1 Batch deferred extractions

**File**: `Chat.java`, `flushPendingKeyframe`.

Today: one extraction per keyframe (one LLM call each).

Change: accumulate pending segments on a per-chatConfig queue. Fire ONE
extraction LLM call per N keyframes (default 3) with a "summarize these
N segments" prompt that returns N independent memory sets.

Multi-segment prompt:

```
Segment 1: [segment text]
Segment 2: [segment text]
Segment 3: [segment text]

For each numbered segment, extract memories per the multi-aspect format.
Return JSON: {"segment_1":[...], "segment_2":[...], "segment_3":[...]}
```

Cuts LLM calls by ~Nx for async work. Latency per extraction is roughly
linear in input length, so batching has slight overhead but huge net
savings.

### 5.2 Single LLM-call lock per chatConfig (verify and extend)

**File**: `Chat.java`. Already has `activeKeyframes` map ([Chat.java:2796-2814](../AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java#L2796)) for keyframes specifically.

Audit all async LLM call sites:
- `flushPendingKeyframe` (covered)
- `flushPendingInteraction` (verify)
- `extractMemoriesFromSegment`/`Range` (verify, likely covered transitively)
- `ChatAutotuner.tune` (verify)
- `ResponseComplianceEvaluator.evaluate` (verify)
- title/icon auto-generation in `ChatListener.oncomplete` (verify)

Each should share the SAME per-chatConfig lock so they don't stack up
when Ollama is single-slot. Easy regression if not.

### 5.3 Smart deferral on Ollama pressure

**File**: `Chat.java`, `flushPendingKeyframe` (and similar entry
points).

```java
int activeStreams = LLMConnectionManager.getActiveStreamCount();
if (activeStreams >= deferralPressureThreshold) {
    logger.info("Deferring keyframe — LLM under pressure (active=" + activeStreams + ")");
    return;
}
```

Schema field `deferralPressureThreshold` on chatConfig, default 4.

Better to skip a keyframe than make the user wait for their chat
response.

### 5.4 Test acceptance

- A 40-turn duel produces roughly N/3 the keyframe LLM calls compared to today (batched into fewer multi-segment calls)
- Drain time at end of test drops from ~20s to <10s
- No regression in memory count or quality (verify against Phase 3 metrics)
- Latency-slope from Phase 0 stays near zero across 40 turns

### Risks

- **Batching delays memory availability**: small batch size (3) caps the delay. Recency penalty (Phase 2) makes this less impactful.
- **Smart deferral causes memory gaps if user runs many fast chats**: by design — chat responsiveness wins over memory coverage. Memory pipeline catches up when load eases.

### Dependencies

Phase 3 (the multi-aspect extraction prompt is what we're batching).

---

## Phase 6 — Quality evaluator (optional)

Today's `ResponseComplianceEvaluator` and policy machinery focus on
safety/ESRB compliance. There's no automatic detection of quality
issues like echo or stalled exposition.

This phase is optional and lower priority — the metrics from Phase 0
plus visible echo-steering events from Phase 1 give us most of the
observability we need.

### 6.1 New `ConversationQualityEvaluator`

**New file**: `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/policy/ConversationQualityEvaluator.java`

Modeled on `ResponseComplianceEvaluator`. Runs every Nth turn (not
every turn). Outputs structured score:

- `echoScore` (0-1, from Phase 0 metric — no LLM call needed)
- `memoryUtilizationScore` (0-1, from Phase 0)
- `narrativeMomentumScore` (LLM judge call): "Read this conversation. Is it progressing toward something, or stuck repeating the same beat? Score 1-10."
- `characterVoiceConsistencyScore` (LLM judge): "Is character X behaving consistently with their established personality? Score 1-10."

### 6.2 Auto-tune feedback

When scores trend down, feed into `Chat.handleAutotuning` to adjust:
- Increase temperature
- Lower `keyframeEvery` (extract more often)
- Raise `memoryBudget` (inject more memories)
- Switch `memoryInjectionStyle` to `systemSection`

### 6.3 Schema field

```json
{
    "name": "qualityEvaluatorEvery",
    "type": "int",
    "default": 0,
    "description": "Run conversation quality evaluator every Nth turn. 0 = disabled."
}
```

### 6.4 Test acceptance

Subjective. After a 40-turn duel, the quality eval scores correlate with
what a human reading the duel would call "good vs degenerate".

### Risks

- **LLM-as-judge is noisy**: small models give inconsistent scores. Mitigation: use as a signal trend (3-turn rolling avg), not a single-turn gate.
- **Adds LLM cost**: 1 call per N turns. With N=5 and qwen3:8b that's ~5s every 5 turns. Optional and opt-in via schema field default 0.

### Dependencies

Phase 0, 1, 2, 3, 4 (uses metrics and infrastructure from all of them).

---

## What NOT to do

- **Don't add more memory types.** Current 5 (FACT/RELATIONSHIP/DECISION/DISCOVERY/INSIGHT) are sufficient. The issue is content quality and retrieval scoring, not category gaps.
- **Don't switch models as a workaround.** Pipeline should work with qwen3:8b. way-local being slow is a model-size choice, not a pipeline bug.
- **Don't add more LLM calls per turn.** Goal is to reduce, not grow. Echo detection, recency penalty, MMR, dedup — all pure local computation.
- **Don't break existing chat sessions.** Every new behavior gated on a chatConfig flag with backwards-compatible defaults.
- **Don't bake "way-local-specific" or "qwen3:8b-specific" logic** into core paths. Use chatConfig fields so behaviors can be tuned per session/model.

## Verification at each phase

Each phase ships with metric assertions in `TestChatDuelLong` (built in
Phase 0). The duel is reproducible enough across runs that
run-to-run comparisons are meaningful. Capture a baseline before starting
any phase (write to
`aiDocs/ConversationQualityBaseline.md`) and update after each phase.

## Open questions to resolve during implementation

1. **Where does the prompt template engine substitute tokens?** Phase 4 needs to inject memories into the system prompt section. Locate `PromptUtil.getSystemPrompt` (or equivalent) to confirm a clean injection point exists or needs to be added.
2. **How is `MemoryUtil.searchMemories` (vector path) implemented?** Phase 2 MMR needs access to the candidate scoring; if `searchMemories` already returns scored results, great; if not, we need to surface the scores.
3. **Are there async write-locks on memory creation that batching would conflict with?** Phase 5 batching pushes more memories per LLM call; verify no race conditions in `MemoryUtil.createMemory`.
4. **Does `extractMemories=true` apply globally or per-extraction-call?** Phase 5 batching needs to handle the case where some queued segments come from sessions with `extractMemories=false`.

## Rollout

Each phase is independently shippable. Recommended cadence:

- **Phase 0** ships immediately (no risk, just instrumentation)
- **Phase 1** ships next, evaluated against Phase 0 baseline
- **Phase 3** before **Phase 2** (better memory pool, then better retrieval of it)
- **Phase 4** after **Phase 2** (good memories surfaced, then framed well)
- **Phase 5** any time after **Phase 3** (depends on multi-aspect extraction)
- **Phase 6** last and optional

Estimated total: 10-13 days for Phases 0-5. Phase 6: +3-4 days.

Each phase commits to a feature branch and gets one focused PR. No
mega-branch.

## Acceptance for the whole plan

A 40-turn duel on qwen3:8b with all phases shipped:

- `echoMean` < 0.4 (currently can hit ~1.0 in degenerate runs)
- `echoMax` < 0.85
- `memUtilMean` > 0.4 (vs current ~0.15-0.25)
- `latencySlope` < 50 ms/turn (essentially flat past msgCount > messageTrim)
- Total LLM calls per duel reduced by ~30% vs current (batching savings minus echo-suppression overhead)
- Memory pool composition: at least one memory of each type (FACT/RELATIONSHIP/DECISION/INSIGHT) per 5-turn window
- Conversation reads (human inspection) as building forward, not stuck

If we hit those numbers, the system supports efficient, lengthy,
performant conversations — the original goal.
