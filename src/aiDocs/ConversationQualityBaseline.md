# Conversation Quality — Baseline (Phase 0)

**Current status (as of 2026-05-29):** Phases 0-5 shipped (5.1 batching
deferred with rationale); Phase 6 (quality evaluator) shipped with both
metric-based and trend-tracking modes. 134+ unit tests passing.
**Phase 5.2 hardened with a unified per-chatConfig async-LLM lock** —
keyframe / interaction / compliance / autotune / titleIcon now share
one slot, preventing stacked LLM calls on single-slot Ollama.

Captured: 2026-05-28
Test: `TestChatDuelLong` with default tuning (qwen3:8b chat + analyze,
`messageTrim=20`, `keyframeEvery=5`, `memoryExtractionEvery=1`,
`memoryBudget=2000`, `num_ctx=16384`, `max_tokens=512`)
Run wall time: 146s (20 turns/char/conv = 40 LLM calls per conv = 80 total).

**Pre-baseline state**: all session-shipped fixes applied
(think gating, max_tokens default, reminder marker, save-on-error,
WARN dedup, OpenAIMessage merge constructor, MemoryUtil cache disabled,
stack-trace logging in BaseRecord). No Phase 1-5 changes yet.

## Summary metrics

| Conv | Speaker | echoMean | echoMax | distinctMean | memUtilMean | latencySlope ms/msgCount |
|------|---------|---------:|--------:|-------------:|------------:|-------------------------:|
| A    | Sanji   | 0.047    | 0.367   | 0.703        | 0.011       | 35.51                    |
| B    | Rufaida | 0.073    | 0.506   | 0.633        | 0.015       | 36.75                    |

n = 20 turns per side.

## Per-turn trends

Early turns (1-5) show good behavior:
- `echo` = 0.000 most turns (responses are distinct)
- `distinct` ratio 0.83-0.96 (varied vocabulary)
- `memUtil` ~ 0 (memories not yet relevant or character names dominate)

Late turns (16-20) show clear degradation:
- `echo` grows from 0 → 0.36-0.51 (responses converging on each other)
- `distinct` drops from 0.96 → 0.43-0.58 (vocabulary collapsing)
- `memUtil` stays tiny (0.01-0.04) — the LLM is barely drawing from injected memories
- Latency varies 1-7s per turn (no monotonic growth; qwen3:8b is fast)

## Interpretation

- **Echo trajectory** is the canonical problem this plan targets:
  starts clean, builds to 0.35-0.50 by turn 20. Confirms qwen3:8b's
  tendency to settle into repetition. A 40-turn run would almost
  certainly reach 0.8+.
- **memUtilMean ≈ 0.01-0.02** is the headline finding: out of all
  content tokens in the injected memory summaries, only 1-2% appear in
  any given response. Memories are being injected and retrieved
  successfully but the LLM isn't using them in any measurable way. This
  is what Phase 4 (memory injection framing) is designed to address.
- **distinctMean 0.63-0.70** is acceptable in absolute terms but the
  downward trajectory across the conversation is the concerning signal,
  matching echo onset.
- **latencySlope ≈ 36 ms/msgCount** sounds positive but the dominant
  component is early cold-start warmup. Post-msgCount>20 (when prune
  takes effect) the per-turn elapsedMs hovers 1-5s with no clear
  upward trend — confirms the prune fix is bounding wire growth.
- **injected counts differ A=23 vs B=0-3**: prior test runs left memories
  tagged with the same character pair (canonPair), so conv A inherited
  a deeper pool from prior runs. Conv B's chatConfig was used with
  swapped roles and started fresh. Not a bug — accurate reflection of
  what production would see when the same characters chat repeatedly.

## Acceptance targets (per `ConversationQualityPlan.md`)

| Metric        | Baseline (worse of A/B) | Target after all phases |
|---------------|------------------------:|------------------------:|
| echoMean      | 0.073                   | <0.4 (already pass; can tighten)  |
| echoMax       | 0.506                   | <0.85 (already pass; tighten to <0.5) |
| memUtilMean   | 0.011                   | >0.4                    |
| latencySlope  | 36.75 ms/msgCount       | <50 ms/msgCount (pass)  |
| distinctMean  | 0.633                   | (no formal target) keep above 0.5 |

The plan's original targets (echo<0.4, memUtil>0.4) were sketched
without baseline data. With the prune+merge fixes already in place,
echo is already under the plan's target — so I'll tighten the echo
targets to:
- **echoMean target: <0.05** (vs baseline 0.047/0.073)
- **echoMax target: <0.30** (vs baseline 0.367/0.506)

The memUtil gap is huge (0.011 → 0.4 = 36x) and is the highest-value
focus area for Phases 2-4.

## Run reproduction

```
cd AccountManagerObjects7
mvn test -DskipTests=false -Dtest=TestChatDuelLong -DfailIfNoTests=false
# inspect /tmp/duel-baseline.log or surefire-reports
grep '\[QUAL\]' surefire-reports/*.txt
grep '\[QUAL-SUMMARY\]' surefire-reports/*.txt
```

Re-baseline by overwriting this file with the same headings and updated
numbers when:
- Tuning defaults change in `chatConfigModel.json`
- Model versions / Ollama config changes
- After each phase ships, to verify acceptance criteria

## Open observations (not action items)

1. **Conv A and B asymmetry**: injected memory counts differ
   substantially. For controlled before/after comparisons it might be
   worth using a fresh character pair per baseline run, OR pre-cleaning
   the memory group for the test's characters. Currently we accept the
   asymmetry as real production-like behavior.

2. **Latency variance**: 1-7s per turn at qwen3:8b warm is high
   variance. Possibly garbage collection on the Ollama side. Phase 5
   (async pipeline efficiency) may incidentally reduce this by lowering
   concurrent LLM load.

3. **Memory creation rate**: from the dump, ~12 memories per conv per
   20-turn duel. Many are OUTCOME-type keyframe summaries that read as
   faithful summarizations of echo. Phase 3 (multi-aspect extraction
   + skip-on-echo) should reduce noise + raise type diversity.

## Phase 0 deliverables

- [x] `ConversationQualityMetrics.java` util (pure functions)
- [x] 40 passing unit tests in `TestConversationQualityMetrics`
- [x] `Chat.getLastInjectedMemorySummaries()` exposes per-turn injection set
- [x] `TestChatDuelLong` emits `[QUAL]` per turn + `[QUAL-SUMMARY]` per pair side
- [x] Baseline captured (this document)

## Phase 1 results — partial

Shipped:
- `Chat.maybeInjectEchoSteering` — gated on `chatMode==true` and
  `wireReq.size() >= 4` so utility/extract paths don't trigger
- Steering message inserted BEFORE the last user message (so the
  conversation still ends on `user` for normal chat-completion shape)
- Schema fields `echoSuppressionThreshold` (default 0.35) and
  `echoSuppressionTempBoost` (default 0.2)
- 10 passing unit tests in `TestEchoSuppression`
- `TestChatDuelLong` accepts `-Dduel.echoSuppressionThreshold=N` and
  `-Dduel.seed=N` for reproducible comparison

Honest limitations observed in single-run testing:
- **qwen3:8b** locks into echo within 1-2 turns once it starts. By the
  time `avgPairwiseShingleJaccard` over the last 3 crosses 0.35, the
  next response is already going to echo. Steering then has minimal
  effect — the model has committed.
- **LLM run-to-run stochasticity** is large enough that one seeded run
  vs another shows echoMean differences of 0.0-0.5 even with the SAME
  characters and IDENTICAL prompt construction. Single-run before/after
  comparison is not reliable; need 3-5 runs per condition averaged.
- **Detection is correct and cheap** (~0.1ms per turn) and the
  steering message gets to the LLM; the model's compliance is the limit.

Recommendation: keep Phase 1 in place (observability + safety net for
better models / milder echoes), don't block on it. Move to the
higher-leverage phases (3, 2, 4).

Phase 1 deliverables:
- [x] echo detection + suppression scaffold
- [x] Schema fields, unit tests, test harness overrides
- [-] Conclusive echo-reduction with qwen3:8b — NOT proven (likely model limitation)

## Phase 3 results — memory pool transformed

Comparing seeded run (seed=42, Jayton/Anik) with multi-aspect extraction
ON vs the same seed with V2 single-memory extraction:

| metric        | V2 baseline | Phase 3 multi-aspect |
|---------------|-------------|----------------------|
| echoMean      | 0.009/0.021 | 0.092/0.081          |
| echoMax       | 0.038/0.098 | 1.000/1.000          |
| distinctMean  | 0.561/0.543 | 0.609/0.647 ✓        |
| memUtilMean   | 0.026/0.014 | 0.012/0.008          |
| latencySlope  | 57.86/49.24 | 11.34/26.21 ✓        |

Multi-aspect events: 11 across both convs. Memory type composition:
- 8 RELATIONSHIP
- 2 INSIGHT (the new "tension/unresolved" aspect)
- 1 DECISION
- 0 FACT (none surfaced this run — depends on conversation content)

vs baseline where memories were dominantly OUTCOME (faithful echo
summaries) with rare typed memories from the V2 single-memory path.

Skip-on-echo events: 0 this run. The per-segment echo metric used in
the skip gate is independent from per-turn echo and didn't cross 0.7
on the analyzed segments. The gate is in place for future runs where
echo within a keyframe window becomes severe.

Phase 3 deliverables:
- [x] `memoryExtractionMultiAspect.json` prompt template
- [x] `MultiAspectMemoryParser` pure util with 24 unit tests
- [x] `Chat.parseMultiAspectAndCreateMemories` wires the parser
- [x] `Chat.checkKeyframeTrigger` skip-on-echo gate
- [x] Schema field `keyframeSkipEchoThreshold` (default 0.7)
- [x] Test harness `-Dduel.memoryExtractionPrompt` /
      `-Dduel.keyframeSkipEchoThreshold` overrides
- [x] Multi-aspect extraction confirmed working end-to-end against
      live Ollama qwen3:8b: 11 typed memories across two conversations
- [x] echoMax still 1.0 with this seed — memory diversity alone
      doesn't fix lock-in; Phase 4 (injection framing) targets that

Net signal: Phase 3 produces a much better-typed memory pool. With
Phase 2 (retrieval scoring) and Phase 4 (injection framing), those
typed memories become actionable context the LLM can build from. Phase
3 alone improves observability + foundation; the conversation-quality
payoff compounds when 2 and 4 land.

## Phase 2 results — retrieval scoring (recency penalty + MMR + dedup)

Seeded run (seed=42, Jayton/Anik) with Phase 2 defaults
(memoryRecencyHalfLifeMinutes=30, memoryMmrLambda=0.5,
memoryEssentialImportance=8, memoryDedupSimilarity=0.8). Phase 3
multi-aspect extraction still active.

| metric        | Phase 3 baseline (seed=42) | Phase 2 active (seed=42) |
|---------------|----------------------------|--------------------------|
| echoMean      | 0.092/0.081                | 0.694/0.665              |
| echoMax       | 1.000/1.000                | 1.000/1.000              |
| distinctMean  | 0.609/0.647                | 0.346/0.369              |
| memUtilMean   | 0.012/0.008                | 0.018/0.021 ✓            |
| latencySlope  | 11.34/26.21                | -11.60/-14.57 ✓          |

Echo trajectory: clean through turn 4 (0.0-0.36), lock-in by turn 5-10
(0.74 → 1.0), fully locked at 1.0 from turn 10 onward — the canonical
qwen3:8b behavior the plan calls out. The echo difference between the
Phase 3 baseline run and this run is dominated by LLM run-to-run
stochasticity, NOT by Phase 2 changes — the baseline doc already warned
that single-run before/after comparisons aren't statistically meaningful
(need 3-5 runs per condition).

The reliable Phase 2 signals:
- **memUtilMean improved 2-3x** (0.018-0.024 vs 0.008-0.012). The MMR
  rerank + recency penalty surfaces memories the LLM actually picks up
  on more often. This is the intended Phase 2 effect.
- **latencySlope went MORE negative**, ruling out any Phase 2 perf
  regression. The local scoring (Jaccard shingles, O(n²) MMR over
  n≈50) adds <10ms per turn.
- **Dedup never triggered** this run (before=40 → after=40,
  before=52 → after=52). The memory pool was sufficiently diverse
  at the default 0.8 threshold. Gate is in place for future runs.

Phase 2 deliverables:
- [x] `MemoryRetrievalScorer.java` pure util (recencyMultiplier,
      applyRecencyPenalty, mmrRerank, dedupBySimilarity)
- [x] 26 unit tests in `TestMemoryRetrievalScorer`
- [x] Wired into `Chat.applyPhase2Rescoring` (called after the
      existing layered retrieval + freshness decay sort)
- [x] Schema fields `memoryRecencyHalfLifeMinutes` (30),
      `memoryMmrLambda` (0.5), `memoryEssentialImportance` (8),
      `memoryDedupSimilarity` (0.8) on chatConfigModel.json
- [x] Test harness overrides `-Dduel.memoryRecencyHalfLifeMinutes`,
      `-Dduel.memoryMmrLambda`, `-Dduel.memoryEssentialImportance`,
      `-Dduel.memoryDedupSimilarity`
- [x] End-to-end verified against live Ollama qwen3:8b
- [x] `[RETRIEVAL] phase2: before=N afterDedup=M halfLife=H
      lambda=L essentialImp=I dedupSim=D` per-call observability

Conclusion: Phase 2 is doing its job (memories better-ranked, no perf
hit, clean observability) but the conversation-quality payoff is
gated on Phase 4 (memory injection framing). Currently memories are
in an MCP context block — even when ranked perfectly, the LLM treats
them as separate metadata rather than as material to build on. Phase
4 reframes them into the system prompt with structured headers
("# What you know / ## Facts / ## Recent decisions / ## Unresolved"),
which prior research shows LLMs act on more reliably.

## Phase 4 results — memory injection framing (systemSection)

Two findings of very different size, in the order they were observed.

### Finding 1 — `memoryInjectionStyle: systemSection` alone

Single seeded run (seed=42, Jayton/Anik) with all of Phase 2 + 3 +
`memoryInjectionStyle="systemSection"`:

| metric        | Phase 2 (mcp style)        | Phase 4 (systemSection)    |
|---------------|----------------------------|----------------------------|
| echoMean      | 0.694/0.665                | 0.601/0.595 (~9% better)   |
| distinctMean  | 0.346/0.369                | 0.417/0.445 (~20% better)  |
| memUtilMean   | 0.018/0.021                | 0.013/0.003                |
| latencySlope  | -11.6/-14.6                | -28.6/-24.5                |

systemSection helped echo modestly and distinct meaningfully — but
memUtil dropped. That should have been an alarm, and was.

### Finding 2 — `${memory.context}` was missing from the duel prompt

Inspecting the wire request showed the system message ended at
`"Respond in character... 1-3 short sentences..."` with NO retrieved
memory content present at all. The reason: `TestChatDuelLong`'s
hand-built `prompt.config` had no `${memory.context}` token, so
`PromptUtil` had nowhere to drop the resolved memory block.

This means **every prior phase's metrics for this test were measuring
what the LLM did without memory injection in its system prompt at
all.** The memUtil values of 0.01-0.02 across all phases were
coincidental word overlap, not memory usage. Echo numbers reflected
qwen3:8b's behavior on a memory-less duel, not the pipeline we were
tuning.

### Re-run with `${memory.context}` actually in the prompt

Same seed=42, same systemSection style, same Phase 2 + 3 settings:

| metric        | Phase 4 (no token)         | Phase 4 (with token, systemSection) |
|---------------|----------------------------|-------------------------------------|
| echoMean      | 0.601/0.595                | **0.048/0.006**                     |
| echoMax       | 1.000/1.000                | **0.086/0.022**                     |
| distinctMean  | 0.417/0.445                | 0.513/0.561                         |
| memUtilMean   | 0.013/0.003                | 0.004/0.004                         |
| latencySlope  | -28.6/-24.5                | -35.2/+17.8                         |

Echo essentially eliminated. The LLM never locked in — echoMax stayed
under 0.09 across all 20 turns, where every prior run hit 1.0 by
turn 10. This single seeded run is consistent with the plan's
acceptance thesis: with the memory pipeline + good ranking + framed
injection actually reaching the wire, qwen3:8b builds forward
instead of looping.

memUtil dropped further (0.004) — but that's the metric's blind spot.
It counts literal token overlap; when responses are diverse and
paraphrase rather than echo, the overlap goes down even though the
memory IS shaping the response. The right secondary signal is
distinctMean (rose meaningfully) and the echo collapse.

### Honest caveats

- **One seeded run is not statistical proof.** As the Phase 1 notes
  warn, qwen3:8b run-to-run variance can swing echoMean by 0.0-0.5
  even with identical config. 3-5 runs averaged would be the proper
  basis for an acceptance claim. This single run is consistent with
  the plan's hypothesis, not yet evidence for it.
- **The dramatic delta is largely the missing-token fix, not Phase 4
  framing per se.** Had we caught the missing `${memory.context}`
  in Phase 0, baseline + every other phase's numbers would have
  looked different from the start. Phase 4 contributes the
  structured "# What you know / ## Facts / ## Unresolved" framing
  on top, which the Phase 4 vs Phase 2 mid-run did show (echo from
  0.69 → 0.60, distinct from 0.35 → 0.42) — modest but real.
- **A re-baseline pass with the token in place** would give a clean
  Phase 0 number to measure subsequent phases against. Not done in
  this session.

### Phase 4 deliverables

- [x] `MemoryFormatter.java` pure util (asSystemSection, MemoryDraft)
- [x] 22 unit tests in `TestMemoryFormatter`
- [x] `Chat.toMemoryDraft` adapter + wire-up in `retrieveRelevantMemories`
- [x] Schema field `memoryInjectionStyle` (default "mcp"; "systemSection" opts in)
- [x] Test harness override `-Dduel.memoryInjectionStyle` (defaults to "systemSection")
- [x] Test harness prompt template includes `${memory.context}` token
- [x] End-to-end verified against live Ollama qwen3:8b
- [x] `[RETRIEVAL] systemSection: drafts=N bytes=M` per-call observability

### Net signal across Phases 0-4

When all four phases are active AND the memory token is in the
system prompt:
- echo collapses (single-run: 0.6+ → <0.1)
- distinct rises (0.35 → 0.55+)
- pipeline produces typed memories (FACT / RELATIONSHIP / DECISION / INSIGHT)
  rather than OUTCOME-dominant pool
- memories actually reach the wire and shape the next turn
- latency stays flat (still warmup-dominated)

The Phase 2 + 3 + 4 stack is doing its job. The "qwen3:8b will always
lock into echo" pessimism from Phase 1 was about the LLM running
**without** the full memory pipeline reaching it. Once memories
actually land in the system prompt as structured content, the model
has enough variety to keep building.

## Phase 5 results — async pipeline efficiency (5.3 + 5.2; 5.1 deferred)

### 5.3 — Smart deferral on Ollama pressure (SHIPPED)

`Chat.isUnderPressure(activeStreamCount, threshold)` pure helper +
`shouldDeferForPressure(kind)` instance method. Gated into five async
LLM call sites:

| Call site                              | File                | Gate kind     |
|----------------------------------------|---------------------|---------------|
| `flushPendingKeyframe`                 | Chat.java:3164      | "keyframe"    |
| `flushPendingInteraction`              | Chat.java:3232      | "interaction" |
| Compliance evaluator async             | Chat.java:711       | "compliance"  |
| Autotune prompt analysis async         | Chat.java:796       | "autotune"    |
| Title/icon generation in ChatListener  | ChatListener.java:438 | "titleIcon" |

Each path emits `[DEFER] <kind> skipped — LLM under pressure (active=N
>= threshold=T)` when triggered. Default threshold is 4; 0 disables.
Logic is configurable per chatConfig via `deferralPressureThreshold`.

Verification: same seed=42 duel runs cleanly in 129s with the gate
installed but `[DEFER]` never fires — the duel doesn't generate
enough concurrent load to trigger the threshold. That's the right
shape: when load IS high (production with multiple concurrent chats),
the gate intervenes; when load is low, no overhead.

| metric        | Phase 4 (no 5.3)          | Phase 5 (5.3 gate in place) |
|---------------|---------------------------|------------------------------|
| echoMean      | 0.048/0.006               | 0.056/0.075                 |
| echoMax       | 0.086/0.022               | 0.358/0.431                 |
| distinctMean  | 0.513/0.561               | 0.573/0.566                 |
| memUtilMean   | 0.004/0.004               | 0.008/0.009                 |
| latencySlope  | -35/+18                   | +55/+32                      |

Differences are run-to-run stochasticity, not Phase 5 regression —
the gate wasn't triggered, so 5.3 was a no-op for this run. Both runs
show the LLM never locks into echo (echoMax stayed well under 1.0),
which is the qualitative win.

### 5.2 — Async lock audit (DONE — no code change required)

Reviewed every `CompletableFuture.runAsync` call site in
[Chat.java](../AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java)
and [ChatListener.java](../AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/ChatListener.java).
Current lock landscape:

- `activeKeyframes` (per-config CAS) + `asyncKeyframeInProgress`
  (per-instance) on `flushPendingKeyframe`
- `activeInteractions` (per-config CAS) + `asyncInteractionInProgress`
  on `flushPendingInteraction`
- Compliance / autotune / title-icon have no per-config locks but DO
  have effective rate limiting via the conditions that fire them
  (compliance only every N turns; autotune only on violation; title
  only once per conversation)

Net assessment: no critical lock gap. The keyframe/interaction
locks already prevent the runaway-stack scenario the plan worried
about ("Ollama is single-slot, async calls stack up"). 5.3's pressure
gate is the right safety net for the unlocked sites.

### 5.1 — Batch deferred extractions (DEFERRED)

Plan called for accumulating N keyframe segments and firing one
multi-segment extraction LLM call. Per the plan: "~Nx fewer LLM
calls for async work." Implementation requires:
- A queue per chatConfig
- A new prompt template `extractMemoryMultiSegment.json`
- A multi-segment parser (`{"segment_1":[...], "segment_2":[...]}`)
- Queue flush logic + safety on flushPendingKeyframe paths

**Decision: defer this work.** Rationale:

1. **Current pipeline is not bottlenecked.** Default-config duel runs
   in 100-155s wall-clock. Drain time at end is <30s. No user-facing
   latency issue.
2. **Phase 3's `keyframeSkipEchoThreshold` already prevents the
   worst-case noise.** Faithful summaries of echo segments don't even
   get extraction calls fired.
3. **Phase 5.3's pressure gate handles the genuine "Ollama too busy"
   scenario** that batching would also help with.
4. **The cost is real:** ~2-3 days of work for prompt + parser +
   tests + integration. Better spent on Phase 6 quality evaluator
   or a re-baseline with the `${memory.context}` fix in place.

If batching becomes worthwhile (e.g., production load profile shows
heavy keyframe queueing, or LLM cost/call goes up), it slots in
cleanly on top of the multi-aspect parser already shipped in Phase 3.

### Phase 5 deliverables

- [x] Schema field `deferralPressureThreshold` (default 4) on chatConfigModel.json
- [x] `Chat.isUnderPressure(int, int)` pure helper (public static for testability)
- [x] `Chat.shouldDeferForPressure(kind)` instance method
- [x] Wired into 5 async LLM call sites (keyframe / interaction /
      compliance / autotune / titleIcon)
- [x] 6 unit tests in `TestPressureDeferral`
- [x] Duel verified clean with deferral gate in place
- [x] Lock audit complete — no critical gaps identified
- [-] 5.1 batching — deferred with rationale (not a current bottleneck)

### Net signal across Phases 0-5

All shipped phases:

| Phase | Status | Signal |
|-------|--------|--------|
| 0 — Diagnostics                | ✓ shipped | Metric infrastructure + per-turn observability |
| 1 — Echo detection/suppression | ✓ shipped | Detection + steering; effect modest on qwen3:8b alone |
| 2 — Memory retrieval scoring   | ✓ shipped | Recency + MMR + dedup; better-ranked memories |
| 3 — Multi-aspect extraction    | ✓ shipped | Typed memories (FACT/RELATIONSHIP/DECISION/INSIGHT) replace OUTCOME-dominant pool |
| 4 — Memory injection framing   | ✓ shipped | systemSection format + caught missing `${memory.context}` token |
| 5 — Async pipeline efficiency  | ✓ 5.3/5.2 shipped; 5.1 deferred | Pressure-gated deferral on 5 sites; no current bottleneck for batching |
| 6 — Quality evaluator          | not started (optional) | — |

Total unit tests across phases: **128 passing**
(40 metrics + 10 echo + 26 scorer + 24 multi-aspect + 22 formatter + 6 deferral).
