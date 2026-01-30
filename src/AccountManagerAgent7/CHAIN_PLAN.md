# Dynamic LLM Call Chaining - Implementation Plan

## Overview

Extend the existing `tool.plan`/`tool.planStep` model with new step types (LLM, RAG_QUERY, POLICY_GATE) alongside the existing TOOL type. A new `ChainExecutor` handles all four step types, supports dynamic step insertion (LLM proposes next steps, policies gate transitions), and streams intermediate progress to the client via WebSocket chirps.

---

## Phase 1: Foundation Models (AccountManagerObjects7)

### 1.1 New Enum: `StepTypeEnumType`

**New file:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/schema/type/StepTypeEnumType.java`

```java
public enum StepTypeEnumType {
    UNKNOWN,
    TOOL,           // Existing: Java method call via reflection
    LLM,            // LLM call with prompt config
    RAG_QUERY,      // Vector search returning context chunks
    POLICY_GATE     // Policy evaluation that gates progression
}
```

### 1.2 New Enum: `StepStatusEnumType`

**New file:** `AccountManagerObjects7/src/main/java/org/cote/accountmanager/schema/type/StepStatusEnumType.java`

```java
public enum StepStatusEnumType {
    UNKNOWN, PENDING, EXECUTING, COMPLETED, FAILED, GATED, SKIPPED
}
```

### 1.3 Extend `tool.planStep` Model

**Modify:** `AccountManagerObjects7/src/main/resources/models/tool/planStepModel.json`

Add fields (existing fields unchanged for backward compatibility):

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `stepType` | enum (StepTypeEnumType) | `TOOL` | Determines execution path |
| `stepStatus` | enum (StepStatusEnumType) | `PENDING` | Per-step progress tracking |
| `promptConfigName` | string | null | For LLM steps: which prompt config |
| `chatConfigName` | string | null | For LLM steps: which chat config |
| `policyName` | string | null | For POLICY_GATE steps |
| `ragQuery` | string | null | For RAG_QUERY steps: search text |
| `ragLimit` | int | 10 | For RAG_QUERY steps: max results |
| `dynamic` | boolean | false | Whether inserted at runtime |
| `parentStep` | int | -1 | Which step spawned this dynamic step |
| `summaryText` | string | null | Human-readable result summary for client |

### 1.4 Extend `tool.plan` Model

**Modify:** `AccountManagerObjects7/src/main/resources/models/tool/planModel.json`

Add fields:

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `maxSteps` | int | 20 | Max steps including dynamic insertions |
| `totalExecutedSteps` | int | 0 | Running count for loop prevention |
| `chainContextJson` | string | null | Serialized accumulated context |
| `chainMode` | boolean | false | Use ChainExecutor vs legacy PlanExecutor |
| `streamSessionId` | string | null | WebSocket session for progress streaming |

### 1.5 New Model: `tool.chainEvent`

**New file:** `AccountManagerObjects7/src/main/resources/models/tool/chainEventModel.json`

Non-persistent (`ioConstraints: ["unknown"]`) model for WebSocket event serialization:

Fields: `eventType`, `planName`, `stepNumber`, `totalSteps`, `stepType`, `stepStatus`, `stepSummary`, `toolName`, `outputPreview`, `errorMessage`, `timestamp`

---

## Phase 2: Chain Executor (AccountManagerAgent7)

### 2.1 New Interface: `IChainEventListener`

**New file:** `AccountManagerAgent7/src/main/java/org/cote/accountmanager/agent/IChainEventListener.java`

```java
public interface IChainEventListener {
    void onChainEvent(BaseRecord user, BaseRecord chainEvent);
}
```

### 2.2 New Class: `ChainExecutor`

**New file:** `AccountManagerAgent7/src/main/java/org/cote/accountmanager/agent/ChainExecutor.java`

Core execution loop with these responsibilities:

**Constructor:** Takes `AgentToolManager` + `BaseRecord user`. Creates internal `Map<String, Object> chainContext`.

**`executeChain(BaseRecord plan)`** - Main loop:
- Sort steps by step number
- Iterate: for each step, dispatch by `stepType`
- After each step: emit progress event, check for dynamic insertion
- Guard: hard ceiling `ABSOLUTE_MAX_STEPS = 50`, plan-level `maxSteps`
- Stall detection: identical LLM output twice in a row terminates chain
- On completion: `plan.executed = true`, emit `chainComplete`

**`executeToolStep(step)`** - Reuses PlanExecutor's reflection pattern:
- Resolve method via `toolManager.getToolMethod(toolName)`
- Prepare arguments with `{{varName}}` substitution from `chainContext`
- Invoke via reflection, store result in step output and chainContext

**`executeLLMStep(plan, step)`** - New LLM call execution:
- Look up prompt/chat config by name from step fields
- Compose message from step inputs + accumulated chainContext
- Create `Chat` instance, call `continueChat()`
- Extract assistant response, store in step output and chainContext
- After completion, call `handleDynamicStepInsertion()` for routing

**`executeRAGStep(step)`** - Vector search:
- Resolve `ragQuery` with `{{varName}}` substitution
- Call `VectorUtil.find()` with step's ragLimit
- Format chunks as citation context text
- Store in step output and chainContext (key from step output name)

**`executePolicyGateStep(plan, step, steps, index)`** - Policy validation:
- Build `policy.fact` with `factData` = previous step's output
- Evaluate via `PolicyEvaluator` (which may internally use `ChatOperation` for LLM validation)
- If PERMIT: mark completed, continue
- If DENY: mark as GATED, optionally halt chain

**`handleDynamicStepInsertion(plan, step, steps, index, maxSteps)`** - Routing:
- Only for LLM steps
- Build routing prompt from `chainRoutePrompt.txt` template
- Call routing LLM with accumulated context + step output
- Parse JSON response: `{complete: bool, nextSteps: [...]}`
- If not complete: create new `tool.planStep` records with `dynamic=true`
- Insert after current index, renumber, emit `stepsInserted` event
- Limit: at most 3 new steps per routing decision

### 2.3 Modify `AgentToolManager`

**Modify:** `AccountManagerAgent7/src/main/java/org/cote/accountmanager/agent/AgentToolManager.java`

Minimal additions:
- New field: `private ChainExecutor chainExec`
- Initialize in constructor: `chainExec = new ChainExecutor(this, user)`
- New accessor: `getChainExecutor()`
- New method: `createChainPlan(query)` - calls existing `createPlan()`, sets `chainMode=true`

### 2.4 New Prompt Templates

**New file:** `AccountManagerAgent7/src/main/resources/chainRoutePrompt.txt`

The "what should I do next?" prompt. Given the plan query, current step output, accumulated context, and available step types, asks LLM to respond with structured JSON:
```json
{"complete": true/false, "reason": "...", "nextSteps": [{stepType, toolName?, promptConfigName?, ragQuery?, policyName?, description, inputs}]}
```
Limits nextSteps to 3 max. Only returns JSON.

**New file:** `AccountManagerAgent7/src/main/resources/chainContextPrompt.txt`

Formats accumulated context for LLM consumption within chain LLM steps. Includes plan query, accumulated context from prior steps, optional RAG citations.

**Modify:** `AccountManagerAgent7/src/main/resources/planPrompt.txt`

Append paragraph describing the new step types (LLM, RAG_QUERY, POLICY_GATE) with required fields for each. Default stepType remains TOOL for backward compat.

---

## Phase 3: Service Layer (AccountManagerService7)

### 3.1 New Class: `ChainEventHandler`

**New file:** `AccountManagerService7/src/main/java/org/cote/sockets/ChainEventHandler.java`

Implements `IChainEventListener`. Bridges chain events to WebSocket:

```java
public void onChainEvent(BaseRecord user, BaseRecord chainEvent) {
    String eventJson = JSONUtil.exportObject(chainEvent);
    WebSocketService.chirpUser(user, new String[] {
        "chainEvent", chainEvent.get("eventType"), eventJson
    });
}
```

### 3.2 Modify WebSocketService

**Modify:** `AccountManagerService7/src/main/java/org/cote/sockets/WebSocketService.java`

Add `"chain"` to the message routing in `onMessage()`:
- Parse chain request (planQuery, chatConfigId, etc.)
- Create `AgentToolManager` with user's chat config and `AM7AgentTool`
- Create `ChainExecutor`, wire `ChainEventHandler` as listener
- Execute in a `CompletableFuture.runAsync()` (mirrors existing chat async pattern)
- Progress streams back via chirps; chain runs in background thread

### 3.3 New REST Endpoints in ChatService

**Modify:** `AccountManagerService7/src/main/java/org/cote/rest/services/ChatService.java`

- `POST /chat/chain` - Synchronous chain execution (fallback for non-WebSocket clients). Returns final result.
- `GET /chat/chain/status/{planId}` - Query chain execution status (for polling clients).

---

## Phase 4: Client (AccountManagerUx7)

### 4.1 WebSocket Event Routing

**Modify:** `AccountManagerUx7/client/pageClient.js`

In the chirps routing handler (where `chatStart`/`chatUpdate`/`chatComplete`/`chatError` are dispatched), add:

```javascript
if (chirps[0] === "chainEvent") {
    let eventType = chirps[1];
    let eventData = JSON.parse(chirps[2]);
    if (page.chainStream) {
        page.chainStream["on" + eventType](eventData);
    }
}
```

Add `page.chainStream = undefined` to page state.

### 4.2 Chain Stream Handler

**Modify:** `AccountManagerUx7/client/view/chat.js`

New function `newChainStream()` (pattern follows existing `newChatStream()` at line ~256):

- Tracks: `steps[]`, `currentStep`, `totalSteps`, `isRunning`
- Callbacks: `onstepStart`, `onstepComplete`, `onstepError`, `onchainComplete`, `onstepsInserted`, `onchainMaxSteps`
- On `chainComplete`: injects final result into `chatCfg.history.messages` as assistant message
- Calls `m.redraw()` on each event for reactive UI updates

### 4.3 Progressive Chain Display

**Modify:** `AccountManagerUx7/client/view/chat.js`

New function `getChainProgressView()` rendered inline in the chat view when `page.chainStream.isRunning`:

- Progress bar showing `currentStep / totalSteps`
- Spinning icon during active step
- Step history list with status icons (check/error), type labels (Tool/LLM/RAG/Gate), and summary text
- Disappears when chain completes (result becomes a normal assistant message)

### 4.4 Chain Trigger

**Modify:** `AccountManagerUx7/client/view/chat.js`

Add option in the chat input area to send as a chain request instead of a simple chat message. This could be a toggle or button next to the send button that routes through `page.wss.send("chain", ...)` instead of `page.wss.send("chat", ...)`.

---

## Phase 5: Testing

All tests extend the existing `BaseTest` class in Agent7, which handles IOSystem initialization, database connection, organization context, user creation, and teardown. Tests use the `resource.properties` config for LLM service and embedding credentials.

### 5.1 New Test Class: `TestChainExecutor`

**New file:** `AccountManagerAgent7/src/test/java/org/cote/accountmanager/objects/tests/TestChainExecutor.java`

Extends `BaseTest`. Shared setup creates `testUser1`, `chatConfig`, `AM7AgentTool`, `AgentToolManager`, and `ChainExecutor` for reuse across tests.

---

#### Group A: Model & Schema Validation

**`testPlanStepModelNewFields`**
- Create a `tool.planStep` via `RecordFactory.newInstance`
- Assert `stepType` defaults to `TOOL` (StepTypeEnumType.TOOL)
- Assert `stepStatus` defaults to `PENDING`
- Assert `dynamic` defaults to `false`
- Assert `parentStep` defaults to `-1`
- Assert `ragLimit` defaults to `10`
- Set each new field (`stepType=LLM`, `promptConfigName`, `chatConfigName`, `policyName`, `ragQuery`, `summaryText`) and verify round-trip via `get()`
- Serialize to JSON via `toFullString()`, deserialize back, assert all fields preserved

**`testPlanModelNewFields`**
- Create a `tool.plan` via `RecordFactory.newInstance`
- Assert `maxSteps` defaults to `20`
- Assert `totalExecutedSteps` defaults to `0`
- Assert `chainMode` defaults to `false`
- Set `chainMode=true`, `maxSteps=5`, `streamSessionId="test-session"`
- Serialize/deserialize round-trip, assert preservation
- Verify existing fields (`planQuery`, `executed`, `steps`, `output`) still work

**`testChainEventModel`**
- Create a `tool.chainEvent` via `RecordFactory.newInstance`
- Populate all fields: `eventType="stepComplete"`, `planName`, `stepNumber=3`, `totalSteps=7`, `stepType="LLM"`, `stepStatus="COMPLETED"`, `stepSummary="Analyzed data"`, `toolName`, `outputPreview="first 100 chars..."`, `timestamp=System.currentTimeMillis()`
- Serialize to JSON, assert valid JSON output
- Assert `ioConstraints` prevents persistence (should not be writable to DB)

**`testStepTypeEnumValues`**
- Assert all enum values exist: UNKNOWN, TOOL, LLM, RAG_QUERY, POLICY_GATE
- Create planStep, set `stepType` to each value, assert `getEnum("stepType")` returns correctly
- Verify `UNKNOWN` is handled gracefully by ChainExecutor (throws PlanExecutionError)

**`testStepStatusEnumValues`**
- Assert all enum values: UNKNOWN, PENDING, EXECUTING, COMPLETED, FAILED, GATED, SKIPPED
- Verify status transitions: PENDING -> EXECUTING -> COMPLETED is valid
- Verify FAILED and GATED statuses are set correctly by executor on error/gate

**`testBackwardCompatibility`**
- Create a plan with steps that have NO `stepType` field set (simulating old data)
- Execute via `ChainExecutor.executeChain()`
- Assert steps default to TOOL type and execute identically to legacy `PlanExecutor`
- Compare output with same plan run through legacy `PlanExecutor.executePlan()` -- results must match

---

#### Group B: TOOL Step Execution

**`testToolStepBasic`**
- Build a plan with a single TOOL step: `describeAllModels`
- Set `stepType=TOOL`, `toolName="describeAllModels"`
- Execute via `ChainExecutor.executeChain()`
- Assert `step.stepStatus == COMPLETED`
- Assert `step.output` is non-null with `valueType=STRING`
- Assert output value contains known model names (e.g., "olio.charPerson")

**`testToolStepWithInputs`**
- Build a plan with a TOOL step: `newQueryField("hairColor", EQUALS, "red")`
- Provide inputs as `dev.parameter` list
- Execute, assert output is a `QueryField` model with correct field/value/comparator

**`testToolStepContextPassing`**
- Build a 2-step plan:
  - Step 1 (TOOL): `newQueryField("age", GREATER_THAN, "21")` -> output named `queryField`
  - Step 2 (TOOL): `findPersons({{queryField}})` -> input references step 1 output
- Execute, assert step 2 receives the QueryField from step 1 via context variable substitution
- Assert step 2 output contains a list (may be empty depending on test data, but no exceptions)

**`testToolStepReflectionError`**
- Build a plan with `toolName="nonExistentTool"`
- Execute via ChainExecutor
- Assert `PlanExecutionError` is thrown
- Assert step status is FAILED

**`testToolStepArgumentMismatch`**
- Build a plan step for `findPersons` but provide wrong parameter types/names
- Execute, assert `PlanExecutionError` with descriptive message
- Assert step status is FAILED

---

#### Group C: LLM Step Execution

**`testLLMStepBasic`**
- Create a prompt config with system prompt: "You are a helpful assistant. Answer briefly."
- Build a plan with a single LLM step
- Set `stepType=LLM`, `promptConfigName` pointing to the created config, `chatConfigName` pointing to test chat config
- Provide input: `factData = "What is 2+2?"`
- Execute via ChainExecutor
- Assert step output is non-null, non-empty string
- Assert `stepStatus == COMPLETED`

**`testLLMStepContextInjection`**
- Build a 2-step plan:
  - Step 1 (TOOL): `describeAllModels` -> output named `modelList`
  - Step 2 (LLM): prompt says "Summarize the following model list: {{modelList}}"
- Execute, assert step 2 receives the model list in its input via context substitution
- Assert step 2 output contains a non-empty response that references models

**`testLLMStepAccumulatedContext`**
- Build a 3-step plan, all LLM:
  - Step 1: "List 3 colors" -> output named `colors`
  - Step 2: "Pick one color from {{colors}} and explain why" -> output named `reasoning`
  - Step 3: "Summarize: colors={{colors}}, reasoning={{reasoning}}"
- Execute all 3, assert each step has access to all prior context
- Assert final output references content from both prior steps

**`testLLMStepEmptyResponse`**
- Create a prompt config designed to potentially return empty (extremely restrictive)
- Execute, assert step status is FAILED
- Assert error is logged and `summaryText` contains error description

**`testLLMStepWithDifferentConfigs`**
- Create two different prompt configs (different system prompts, e.g., one formal, one casual)
- Build a 2-step LLM plan where each step uses a different promptConfigName
- Execute, assert each step used its own config (responses should differ in tone)
- Assert both steps completed successfully

---

#### Group D: RAG Step Execution

**`testRAGStepBasic`**
- Prerequisite: seed vector store with test documents via `VectorUtil`
- Build a plan with a single RAG_QUERY step
- Set `ragQuery="test search term"`, `ragLimit=5`
- Execute, assert output contains formatted citation chunks
- Assert result count <= ragLimit

**`testRAGStepContextSubstitution`**
- Build a 2-step plan:
  - Step 1 (LLM): "Generate a search query about population demographics" -> output `searchQuery`
  - Step 2 (RAG_QUERY): `ragQuery="{{searchQuery}}"`, `ragLimit=10`
- Execute, assert RAG step resolves the query from context
- Assert step 2 output contains vector search results (or empty list if no matches -- no exceptions)

**`testRAGStepFeedsLLM`**
- Build a 3-step plan:
  - Step 1 (RAG_QUERY): search for known content -> output `citations`
  - Step 2 (LLM): "Using these citations: {{citations}}, answer: ..."
  - Step 3 (LLM): "Synthesize final answer from {{step2output}}"
- Execute all 3, assert the LLM steps incorporate RAG context
- This tests the RAG -> LLM pipeline end-to-end

**`testRAGStepNoResults`**
- Build a RAG step with a query that will return zero results (e.g., UUID gibberish)
- Execute, assert step completes (not fails) with empty result set
- Assert `stepStatus == COMPLETED` (empty results are valid, not errors)

**`testRAGStepLimitEnforced`**
- Set `ragLimit=2`, execute against a store with many documents
- Assert result count is exactly <= 2

---

#### Group E: POLICY_GATE Step Execution

**`testPolicyGatePermit`**
- Create a simple policy with a rule that always permits (e.g., fact type PARAMETER, condition ALL with a pattern that always matches)
- Build a 2-step plan:
  - Step 1 (LLM): generates some text -> output `llmResult`
  - Step 2 (POLICY_GATE): `policyName` = the permitting policy
- Execute, assert step 2 status is COMPLETED
- Assert chain continues past the gate

**`testPolicyGateDeny`**
- Create a policy that always denies
- Build a 2-step plan:
  - Step 1 (LLM): generates text
  - Step 2 (POLICY_GATE): `policyName` = the denying policy
- Execute, assert step 2 status is GATED
- Assert chain halts at the gate (step 2 does not proceed)
- Assert `PlanExecutionError` or controlled termination

**`testPolicyGateWithChatOperation`**
- Create a policy with a `ChatOperation` pattern (mirrors `TestChatPolicy` setup):
  - Fact with `factData` = previous step output
  - Match fact with `chatConfig` + `promptConfig` (e.g., "rewrite to be more formal")
  - Operation = `ChatOperation`
- Build a 3-step plan:
  - Step 1 (LLM): "Write a casual greeting"
  - Step 2 (POLICY_GATE): policy with ChatOperation that rewrites the greeting
  - Step 3 (LLM): "Use this greeting: {{gateOutput}}"
- Execute, assert the ChatOperation ran inside the policy gate
- Assert the fact was modified by ChatOperation (output differs from step 1)
- Assert step 3 used the modified output

**`testPolicyGateWithComplexRules`**
- Create a policy with multiple rules (ANY condition):
  - Rule 1: expression pattern checking output length > 10
  - Rule 2: ChatOperation pattern for LLM-based validation
- Build plan with gate step, execute
- Assert policy evaluation traverses both rules
- Assert final gate result reflects the ANY condition correctly

**`testPolicyGateNonExistentPolicy`**
- Reference a `policyName` that doesn't exist in the DB
- Execute, assert `PlanExecutionError` is thrown with descriptive message
- Assert step status is FAILED

---

#### Group F: Dynamic Step Insertion

**`testDynamicInsertionBasic`**
- Build a plan with a single LLM step where the routing prompt returns `{complete: false, nextSteps: [{stepType: "TOOL", toolName: "describeAllModels"}]}`
- Mock or configure the routing LLM to return this response
- Execute, assert a new step was inserted after the LLM step
- Assert new step has `dynamic=true`, `parentStep` = original step number
- Assert the dynamically inserted step was executed

**`testDynamicInsertionMultipleSteps`**
- Configure routing to propose 3 new steps
- Execute, assert all 3 are inserted and executed in order
- Assert step numbering is sequential after insertion

**`testDynamicInsertionRespectMaxSteps`**
- Set `plan.maxSteps=3`
- Start with 2 steps, routing proposes 5 more
- Execute, assert only enough steps are inserted to reach maxSteps
- Assert chain terminates at limit with `chainMaxSteps` event

**`testDynamicInsertionChaining`**
- Routing from step 1 proposes an LLM step
- That LLM step's routing proposes another TOOL step
- Assert multi-level dynamic insertion works (chain grows dynamically across levels)
- Assert all dynamically inserted steps track their `parentStep` correctly

**`testDynamicInsertionComplete`**
- Configure routing to return `{complete: true, nextSteps: []}`
- Execute, assert no new steps are inserted
- Assert chain proceeds to completion normally

**`testDynamicInsertionInvalidJSON`**
- Configure routing LLM to return malformed JSON
- Execute, assert chain handles gracefully (logs error, does NOT insert steps, continues)
- Assert step status remains COMPLETED (routing failure is non-fatal)

---

#### Group G: Guard Rails & Safety

**`testMaxStepsHardCeiling`**
- Set `plan.maxSteps=100` (exceeds ABSOLUTE_MAX_STEPS=50)
- Create a chain that would run 60 steps via dynamic insertion
- Assert chain stops at 50 (hard ceiling)
- Assert `chainMaxSteps` event was emitted

**`testMaxStepsPlanLevel`**
- Set `plan.maxSteps=3`
- Create a plan with 5 static steps
- Execute, assert only 3 steps execute
- Assert remaining steps are not marked COMPLETED

**`testStallDetection`**
- Create 2 consecutive LLM steps with identical prompt configs and inputs
- Configure so they produce identical output (same prompt, deterministic temperature=0)
- Assert chain detects the stall and terminates
- Assert appropriate error/event is emitted

**`testInfiniteLoopPrevention`**
- Create a routing prompt that always proposes 1 new LLM step (would loop forever)
- Set `plan.maxSteps=10`
- Execute, assert chain terminates at step 10
- Assert `totalExecutedSteps` reflects the count accurately

**`testConcurrentChainExecution`**
- Create two separate plans for two different users
- Execute both chains concurrently (separate threads)
- Assert no cross-contamination of chainContext between executions
- Assert both complete independently with correct results

---

#### Group H: Context Management

**`testContextAccumulationAcrossStepTypes`**
- Build a 4-step plan mixing all types:
  - Step 1 (TOOL): `describeAllModels` -> `modelList`
  - Step 2 (RAG_QUERY): search for content -> `citations`
  - Step 3 (LLM): "Given models={{modelList}} and citations={{citations}}, analyze" -> `analysis`
  - Step 4 (LLM): "Summarize: {{analysis}}"
- Execute, assert each step can reference all prior outputs by name
- Assert final output incorporates content from all 3 prior steps

**`testContextSaveRestore`**
- Build and partially execute a chain (execute 2 of 4 steps)
- Call `saveContext()`, inspect `plan.chainContextJson` -- assert valid JSON
- Create a new ChainExecutor, call `restoreContext()` from the saved plan
- Assert the restored context contains outputs from the 2 executed steps

**`testContextVariableNotFound`**
- Build a step with input referencing `{{nonExistentVar}}`
- Execute, assert the variable is NOT silently replaced with null
- Assert `PlanExecutionError` or logged warning with the missing variable name

**`testContextOverwrite`**
- Two steps produce output with the same name (e.g., both named `result`)
- Assert the second value overwrites the first in chainContext
- Assert subsequent steps see the latest value

---

#### Group I: Event Listener

**`testEventListenerReceivesAllEvents`**
- Create a mock `IChainEventListener` that records all events
- Wire it to the ChainExecutor
- Execute a 3-step plan
- Assert listener received: `stepStart(1)`, `stepComplete(1)`, `stepStart(2)`, `stepComplete(2)`, `stepStart(3)`, `stepComplete(3)`, `chainComplete`
- Assert event timestamps are monotonically increasing

**`testEventListenerStepFailure`**
- Wire mock listener, execute a plan with a step that will fail
- Assert listener received `stepStart` followed by `stepError`
- Assert `errorMessage` field is populated in the error event

**`testEventListenerDynamicInsertion`**
- Wire mock listener, execute a plan where routing inserts steps
- Assert listener received `stepsInserted` event
- Assert `totalSteps` in subsequent events reflects the new count

**`testNoListenerDoesNotFail`**
- Execute chain with NO event listener wired (null listener)
- Assert chain executes normally without NullPointerException

**`testChainEventSerialization`**
- Create a `tool.chainEvent`, populate all fields
- Serialize via `JSONUtil.exportObject()`, then deserialize back
- Assert round-trip preserves all values
- This validates the event will survive WebSocket transmission

---

#### Group J: End-to-End Integration

**`testFullChainQueryToAnswer`**
- Submit a realistic query: "Find all people over 30 and describe them"
- Let `createChainPlan()` generate the plan via LLM
- Execute the full chain including any dynamic steps
- Assert final `plan.output` contains a coherent answer
- Assert `plan.executed == true`
- Assert `plan.totalExecutedSteps > 0`

**`testFullChainWithRAGAndPolicy`**
- Seed vector store with test character descriptions
- Create a validation policy
- Submit query that requires: RAG retrieval -> LLM synthesis -> policy validation
- Assert all step types execute in sequence
- Assert policy gate permits (or gates) appropriately
- Assert final output incorporates RAG context

**`testFullChainPersistenceRoundTrip`**
- Execute a chain, serialize the completed plan to JSON
- Write to DB via `IOSystem.getActiveContext().getRecordUtil().createRecord()`
- Read back from DB, deserialize
- Assert all fields preserved: steps with statuses, outputs, dynamic flags, context
- Assert `plan.executed == true` survives persistence

**`testLegacyPlanStillWorks`**
- Run the existing `TestAgent` test flow (createPlan -> refineStep -> executePlan -> evaluateResult)
- Assert it produces the same results as before the model changes
- This is the critical backward-compatibility gate

---

#### Group K: Magic8 Integration & Video Recording

These tests verify that chain execution results correctly flow into Magic8 for immersive rendering and video capture. The server-side tests (Java) validate data structure and context packaging. The client-side tests (manual/browser) validate recording behavior.

**Server-Side (TestChainExecutor.java):**

**`testChainResultFormatsForMagic8Context`**
- Execute a multi-step chain (TOOL -> LLM -> LLM) that produces a final assistant message
- Serialize the chain result into the format expected by `sendToMagic8()`: `{chatHistory: messages[], systemCharacter, userCharacter, chatConfigId}`
- Assert `chatHistory.messages` contains the full chain output as properly formed `{role: "assistant", content: "..."}` entries
- Assert `systemCharacter` and `userCharacter` references are populated from the chain's chatConfig
- Assert the serialized context is valid JSON and parseable

**`testChainStepSummariesInMagic8History`**
- Execute a chain with 4+ steps of mixed types
- Build the chat history that would be sent to Magic8
- Assert intermediate step summaries (from `summaryText` fields) are included as system or assistant messages so the SessionDirector LLM has full chain context
- Assert step type labels (Tool/LLM/RAG/Gate) appear in the history for director awareness

**`testChainOutputWithCharacterContext`**
- Create a chain that queries character data (e.g., `findPersons`)
- Execute the chain with `systemCharacter` and `userCharacter` set on the chatConfig
- Assert the plan output includes character-relevant data
- Assert character records are fully hydrated (not just IDs) in the context that would be passed to Magic8
- This ensures the SessionDirector has enough character detail to drive voice, visuals, and text

**`testChainRAGCitationsAvailableForDirector`**
- Execute a chain that includes a RAG_QUERY step
- Build Magic8 context from the chain result
- Assert RAG citations are present in the chat history or as a separate context block
- The SessionDirector can use these citations to drive text sequences and visual themes

**Client-Side (Manual Browser Tests):**

**`[Manual] testMagic8ReceivesChainContext`**
- Execute a chain via the chat UI
- Click "Send to Magic8" after chain completes
- Verify Magic8App receives the chain context via sessionStorage
- Verify `this.chatContext.chatHistory` contains all chain messages
- Verify SessionDirector LLM can process the chain output

**`[Manual] testMagic8RecordingCapturesChainSession`**
- Enable video recording in Magic8 session config (`recording.enabled = true`)
- Send chain execution results to Magic8 via `sendToMagic8()`
- Start recording (auto or manual toggle)
- Let SessionDirector process chain context and drive audio/visual changes
- Stop recording after director completes at least one directive cycle
- Assert WebM file is saved to `~/Magic8/Recordings/`
- Assert video contains both canvas visuals and audio tracks
- Assert video duration > 0 and file size > 0

**`[Manual] testMagic8RecordingWithProgressiveChain`**
- Start Magic8 with recording enabled
- Trigger a chain execution via WebSocket while Magic8 is active
- As chain events stream in (`chainEvent` chirps), verify they could drive real-time SessionDirector updates
- Verify recording captures the full progressive session including any mid-chain visual/audio changes

**`[Manual] testMagic8ChainToRecordingRoundTrip`**
- Full pipeline: Chat query -> Chain execution -> Chain completes -> Send to Magic8 -> Director processes -> Recording captures -> Save to server
- Verify the saved recording can be retrieved from `~/Magic8/Recordings/`
- Verify session config JSON is saved alongside the recording

---

### 5.2 Test File Summary

| File | Action | Purpose |
|------|--------|--------|
| `TestChainExecutor.java` | Create | All chain-specific tests (Groups A-K above) |
| `TestAgent.java` | Keep unchanged | Existing tests must still pass (backward compat) |

Both test classes extend `BaseTest` from Agent7, which initializes the database, IOSystem, organization context, and test users. Chat and prompt configs are created in `@Before` setup using the patterns from `TestChatPolicy` (`ChatUtil.getCreateChatConfig`, `OlioTestUtil.getObjectPromptConfig`). Policy structures for gate tests are created using the `getCreatePolicy`/`getCreateRule`/`getCreatePattern`/`getCreateFact` helpers from `TestChatPolicy`.

---

## Verification

1. **Backward compat:** Run existing `TestAgent` suite -- all tests pass unchanged
2. **Model validation (Group A):** New fields default correctly, serialize/deserialize without loss
3. **Step type execution (Groups B-E):** Each step type executes correctly in isolation with DB-backed configs
4. **Dynamic insertion (Group F):** LLM routing proposes steps, executor inserts and executes them, respects limits
5. **Safety (Group G):** Max step limits enforced at both plan and hard-ceiling levels, stall detection works, concurrent execution is safe
6. **Context (Group H):** Variables accumulate across step types, save/restore works, missing variables handled
7. **Events (Group I):** Listener receives complete event stream, events serialize correctly for WebSocket
8. **End-to-end (Group J):** Full query->answer flow, RAG+policy integration, DB persistence round-trip
9. **Magic8 integration (Group K):** Chain results format correctly for Magic8 context, character data hydrated, RAG citations available for SessionDirector
10. **Manual Magic8 recording:** Chain -> Magic8 -> SessionDirector -> Recording pipeline produces valid WebM with audio+video
11. **Manual WebSocket test:** Send chain request from client, verify progressive events in chat UI

---

## Key Files Summary

| File | Action | Module |
|------|--------|--------|
| `type/StepTypeEnumType.java` | Create | Objects7 |
| `type/StepStatusEnumType.java` | Create | Objects7 |
| `models/tool/planStepModel.json` | Extend | Objects7 |
| `models/tool/planModel.json` | Extend | Objects7 |
| `models/tool/chainEventModel.json` | Create | Objects7 |
| `agent/IChainEventListener.java` | Create | Agent7 |
| `agent/ChainExecutor.java` | Create | Agent7 |
| `agent/AgentToolManager.java` | Modify (minimal) | Agent7 |
| `resources/chainRoutePrompt.txt` | Create | Agent7 |
| `resources/chainContextPrompt.txt` | Create | Agent7 |
| `resources/planPrompt.txt` | Extend | Agent7 |
| `sockets/ChainEventHandler.java` | Create | Service7 |
| `sockets/WebSocketService.java` | Modify (add chain routing) | Service7 |
| `rest/services/ChatService.java` | Modify (add chain endpoints) | Service7 |
| `client/pageClient.js` | Modify (add chainEvent routing) | Ux7 |
| `client/view/chat.js` | Modify (chain stream + progress view) | Ux7 |
| `tests/TestChainExecutor.java` | Create (Groups A-K) | Agent7 |
| `tests/TestAgent.java` | Keep unchanged | Agent7 |
