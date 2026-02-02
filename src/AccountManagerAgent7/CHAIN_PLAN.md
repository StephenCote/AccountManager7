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
| `vectorReferenceUri` | string | null | For RAG_QUERY steps: MCP URI of scoped source (am7://) |

### 1.4 Extend `tool.plan` Model

**Modify:** `AccountManagerObjects7/src/main/resources/models/tool/planModel.json`

Add fields:

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `maxSteps` | int | 20 | Max steps including dynamic insertions |
| `totalExecutedSteps` | int | 0 | Running count for loop prevention |
| `chainContextJson` | string | null | Serialized accumulated context (MCP context block format) |
| `chainMode` | boolean | false | Use ChainExecutor vs legacy PlanExecutor |
| `streamSessionId` | string | null | WebSocket session for progress streaming |
| `mcpSessionId` | string | null | MCP session ID when chain is triggered via MCP tool call |

> **MCP note:** `chainContextJson` should be serialized using `McpContextBuilder` format (`<mcp:context>` blocks) rather than ad-hoc JSON, so that accumulated context is directly compatible with MCP resource reads and LLM context injection (see MCP.md Part 6).

### 1.5 New Model: `tool.chainEvent`

**New file:** `AccountManagerObjects7/src/main/resources/models/tool/chainEventModel.json`

Non-persistent (`ioConstraints: ["unknown"]`) model for WebSocket event serialization:

Fields: `eventType`, `planName`, `stepNumber`, `totalSteps`, `stepType`, `stepStatus`, `stepSummary`, `toolName`, `outputPreview`, `errorMessage`, `timestamp`, `mcpContextUri`

> **MCP note:** Include `mcpContextUri` so chain events can reference MCP resources. When chain execution is triggered via the MCP `am7_chain_execute` tool, events should also be serializable as MCP notifications for `resources/subscribe` listeners (see MCP.md Part 8).

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
- Look up prompt/chat config by name from step fields (resolve via `Am7Uri` if URIs provided)
- Compose message from step inputs + accumulated chainContext using `McpContextBuilder` for structured context injection (replaces raw string concatenation — see MCP.md Part 6)
- Create `Chat` instance, call `continueChat()`
- Extract assistant response; filter MCP context blocks from response via `McpContextParser` if present
- Store in step output and chainContext
- After completion, call `handleDynamicStepInsertion()` for routing

**`executeRAGStep(step)`** - Vector search:
- Resolve `ragQuery` with `{{varName}}` substitution
- If step has `vectorReferenceUri`, parse via `Am7Uri` to resolve scope (see MCP.md Part 13)
- Call `VectorUtil.find()` with step's ragLimit; results now include `vectorReferenceObjectId` for MCP URI construction
- Format chunks as MCP context blocks via `McpContextBuilder.addResource()` instead of raw citation text
- Store MCP-formatted results in step output and chainContext (key from step output name)
- Attach `vectorReferenceUri` to each result for downstream MCP resource resolution

**`executePolicyGateStep(plan, step, steps, index)`** - Policy validation:
- Build `policy.fact` with `factData` = previous step's output
- Evaluate via `PolicyEvaluator` (which may internally use `ChatOperation` for LLM validation)
- If PERMIT: mark completed, continue
- If DENY: mark as GATED, optionally halt chain

> **SCIM note:** When chains operate on identity resources (users, groups, roles), policy gates can enforce SCIM-compatible authorization. The chain's contextUser `organizationId` scoping applies identically to SCIM's org-bounded access model (see SCIM.md — all queries are scoped to the authenticated user's organization via `AccessPoint`). Policy gates that evaluate identity operations should respect the same org boundaries enforced by the SCIM layer.

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
- New method: `createChainPlanFromMcp(McpToolCallRequest)` - creates chain plan from MCP tool call arguments, resolving `am7://` URIs for chatConfig, promptConfig, and data references (see MCP.md Part 13)

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

> **MCP note:** The `chainContextPrompt.txt` template should format accumulated context using `<mcp:context>` block syntax rather than ad-hoc delimiters. This ensures LLMs receiving chain context see the same structured format used throughout the MCP integration (see MCP.md Part 2). RAG results within the context should use `<mcp:context type="resource" uri="am7://vector/...">` blocks.

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

### 3.4 MCP Tool Registration for Chain Execution

**Modify:** `AccountManagerService7/src/main/java/org/cote/rest/services/mcp/McpToolService.java`

Register chain execution as an MCP tool so external MCP clients (Claude Desktop, etc.) can trigger chains:

```json
{
  "name": "am7_chain_execute",
  "description": "Execute a dynamic LLM call chain with tool, RAG, LLM, and policy gate steps",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": { "type": "string", "description": "The chain query/objective" },
      "chatConfigUri": { "type": "string", "description": "am7:// URI of chat config" },
      "promptConfigUri": { "type": "string", "description": "am7:// URI of prompt config" },
      "maxSteps": { "type": "integer", "default": 20 },
      "dataReferences": {
        "type": "array",
        "items": { "type": "string" },
        "description": "am7:// URIs of data sources for RAG steps"
      }
    },
    "required": ["query", "chatConfigUri"]
  }
}
```

Implementation in `McpToolService.callTool()`:
- Parse `am7://` URIs via `Am7Uri` to resolve chatConfig, promptConfig, and data references
- Create chain plan via `AgentToolManager.createChainPlanFromMcp()`
- Execute chain via `ChainExecutor.executeChain()`
- Return MCP-formatted tool result with chain output and step summaries
- If MCP subscription is active, emit chain events as MCP `resource/updated` notifications

### 3.5 Chain Result as MCP Resource

Chain execution results should be readable as MCP resources after completion:

```
POST /mcp/v1/resources/read
{ "uri": "am7://default/tool.plan/{planObjectId}" }
```

This allows MCP clients to retrieve chain history, step details, and accumulated context after execution.

### 3.6 SCIM Service Pattern Consistency

**Note:** The chain REST endpoints (`/chat/chain`, `/chat/chain/status/{planId}`) should follow the same patterns established by the SCIM service layer (see SCIM.md):

- **Organization scoping**: Chain operations are scoped to the authenticated user's organization via `AccessPoint`, identical to SCIM's org-boundary enforcement. The `TokenFilter` resolves the bearer token to a `contextUser` whose `organizationId` is applied to all chain queries.
- **Error response format**: Chain error responses should follow SCIM-style structured error bodies (`status`, `detail`) for consistency across the REST API surface.
- **Authorization**: Chain execution goes through the standard `AccessPoint` PBAC layer, same as SCIM CRUD operations. The contextUser must have sufficient permissions to execute tools, query vectors, and access referenced data.
- **SCIM identity in chains**: When chain TOOL steps operate on identity resources (create user, modify group membership), they should use the same adapter layer as SCIM (`ScimUserAdapter`, `ScimGroupAdapter`) to ensure consistent field mapping and validation.

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
- RAG step results rendered using `McpContextFilter` to properly display citations and suppress ephemeral context blocks
- LLM step responses filtered via `McpContextFilter` to handle any `<mcp:context>` blocks in assistant output (reasoning traces, inline resources)

### 4.3b MCP Context Integration

**Modify:** `AccountManagerUx7/client/view/chat.js`

Replace ad-hoc chain result filtering with the MCP context parser/filter (see MCP.md Part 5):

```javascript
// In chain result rendering
import { McpContextFilter } from './mcp/index.js';

const chainContextFilter = new McpContextFilter({
    showEphemeral: false,      // Hide RAG citations, reminders
    showReasoning: false,      // Hide reasoning traces
    renderResources: true      // Render media resources inline
});

// When chain step output contains MCP context blocks
function renderStepOutput(stepOutput) {
    const { content, contexts } = chainContextFilter.filter(stepOutput);
    return {
        displayContent: content,
        citationCount: contexts.citations.length,
        hasReasoning: contexts.reasoning.length > 0
    };
}
```

This replaces any chain-specific `pruneOut`/`pruneTag` calls with the unified MCP filtering pipeline.

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

#### Group L: MCP Context Format Integration

**`testRAGStepProducesMcpContextBlocks`**
- Execute a chain with a RAG_QUERY step
- Assert step output contains `<mcp:context type="resource"` blocks, not `--- BEGIN CITATIONS ---` format
- Assert each result within the MCP context block has a `uri` field matching `am7://` format
- Assert `score`, `chunk`, and `sourceType` metadata are present in results

**`testLLMStepReceivesMcpFormattedContext`**
- Build a 2-step plan: RAG_QUERY -> LLM
- Execute, inspect the message sent to the LLM in step 2
- Assert the LLM input contains `<mcp:context>` blocks from step 1 output
- Assert no legacy `--- CITATION ---` markers are present

**`testChainContextJsonIsMcpFormat`**
- Execute a multi-step chain
- Read `plan.chainContextJson` after completion
- Assert it parses as valid MCP context block content
- Assert each step output is wrapped in a separate `<mcp:context>` block with appropriate URI

**`testChainEventIncludesMcpContextUri`**
- Wire mock event listener
- Execute a chain
- Assert `mcpContextUri` field is populated on chain events with `am7://` URI referencing the plan

**`testMcpContextBuilderRoundTrip`**
- Build chain context using `McpContextBuilder`
- Parse it back using `McpContextParser`
- Assert all context blocks survive the round-trip with data intact

---

#### Group M: SCIM and MCP Integration

**`testChainWithScimUserCreation`**
- Build a plan with a TOOL step that creates a user via SCIM adapter
- Execute, assert user was created in the contextUser's organization
- Assert the created user is visible via both SCIM `GET /Users/{id}` and AM7 `AccessPoint`
- Assert person record was created in `/Persons` group per AM7 convention

**`testChainWithScimGroupMembership`**
- Build a plan:
  - Step 1 (TOOL): create a group via SCIM adapter
  - Step 2 (TOOL): add contextUser to the group via SCIM PATCH
- Execute, assert group membership is established
- Assert membership is visible in SCIM `GET /Groups/{id}` response's `members[]`

**`testChainOrgScopingEnforced`**
- Create two users in different organizations
- Execute a chain as user A that attempts to reference a resource in user B's org via `am7://` URI
- Assert the chain step fails with a 404-equivalent error (not 403), consistent with SCIM cross-org behavior

**`testChainResultReadableAsMcpResource`**
- Execute a chain to completion
- Read the completed plan via `McpResourceService.readResource()` using `am7://default/tool.plan/{objectId}`
- Assert the MCP resource response contains plan fields: `executed`, `steps`, `output`, `totalExecutedSteps`

**`testMcpToolCallTriggersChain`**
- Build an `am7_chain_execute` tool call request with query and chatConfigUri
- Execute via `McpToolService.callTool()`
- Assert response status is 200
- Assert result contains chain output and step summaries
- Assert `plan.executed == true`

**`testMcpChainStatusTool`**
- Trigger a chain via MCP tool
- Call `am7_chain_status` with the plan URI
- Assert response contains current step number, total steps, and execution status

**`testUriBasedRagScopeInChain`**
- Build a plan with RAG_QUERY step that has `vectorReferenceUri` set to `am7://default/data.data/{objectId}`
- Execute, assert the vector search was scoped to the referenced document
- Assert results include `vectorReferenceObjectId` matching the scoped document

---

### 5.3 Updated Test File Summary

| File | Action | Purpose |
|------|--------|---------|
| `TestChainExecutor.java` | Create | All chain-specific tests (Groups A-M above) |
| `TestAgent.java` | Keep unchanged | Existing tests must still pass (backward compat) |
| `McpToolServiceTest.java` | Extend | Add `am7_chain_execute`, `am7_chain_status`, `am7_chain_cancel` tool tests |
| `McpContextBuilderTest.java` | Extend | Add chain-specific context serialization tests |

---

## Phase 6: SCIM and MCP Integration

This phase addresses cross-cutting concerns from the SCIM (see `SCIM.md`) and MCP (see `MCP.md`) implementations that affect the chain executor architecture.

### 6.1 MCP Context Format Adoption

**Dependency:** MCP.md Part 2 (MCP-Compliant Syntax), Part 6 (Context Injection Refactor)

The chain executor must adopt MCP context block format as its native context representation. This affects three areas:

**A. Chain Context Serialization**

Replace raw JSON `chainContextJson` with MCP context blocks:

```java
// Old: ad-hoc JSON
plan.set("chainContextJson", JSONUtil.objectToJson(chainContext));

// New: MCP context block format
McpContextBuilder ctxBuilder = new McpContextBuilder();
for (Map.Entry<String, Object> entry : chainContext.entrySet()) {
    ctxBuilder.addResource(
        "am7://chain/" + plan.get("objectId") + "/context/" + entry.getKey(),
        "urn:am7:chain:step-output",
        Map.of("value", entry.getValue()),
        true  // ephemeral
    );
}
plan.set("chainContextJson", ctxBuilder.build());
```

**B. RAG Step Output**

RAG_QUERY steps produce output in MCP resource format:

```xml
<mcp:context type="resource" uri="am7://vector/citations/{stepId}">
{
  "schema": "urn:am7:vector:search-result",
  "query": "resolved rag query",
  "results": [
    {
      "uri": "am7://default/data.data/doc-123",
      "content": "chunk text...",
      "score": 0.89,
      "chunk": 3,
      "sourceType": "data.data"
    }
  ]
}
</mcp:context>
```

This replaces the raw citation text format and aligns with `McpCitationResolver` output (MCP.md Part 14).

**C. LLM Step Context Injection**

LLM steps use `McpContextBuilder` to compose their input from accumulated context, ensuring the LLM receives structured `<mcp:context>` blocks rather than concatenated strings.

### 6.2 URI-Based Object References

**Dependency:** MCP.md Part 13 (Object Reference Refactoring)

Chain steps that reference AM7 objects should use `am7://` URIs instead of internal database IDs:

| Chain Component | Current | MCP Refactored |
|----------------|---------|----------------|
| Step tool name resolution | Method name string | `am7://` URI to tool resource (optional) |
| RAG scope reference | Internal long ID | `am7://org/type/objectId` via `vectorReferenceUri` |
| LLM config references | Name-based lookup | `am7://` URI via `Am7Uri.parse()` |
| Policy reference | Name string | Name string (unchanged — policies are resolved by name within org) |
| Step output references | `{{varName}}` substitution | `{{varName}}` substitution (unchanged — internal to chain context) |
| Chain result in chat history | Raw assistant message | Message with MCP context blocks |
| Character references in chat | Raw BaseRecord | URI refs via `Am7Uri.toUri()` |

The `Am7Uri` utility (MCP.md Part 9) handles URI construction and parsing. Key methods used by the chain:
- `Am7Uri.toUri(BaseRecord)` — construct URI from any record with an `objectId`
- `Am7Uri.parse(String)` — parse URI to extract org, type, and objectId
- `Am7Uri.builder()` — construct URIs programmatically

### 6.3 RAG Pipeline Integration

**Dependency:** MCP.md Part 14 (RAG Pipeline Refactoring)

The chain executor's `executeRAGStep()` depends on the RAG pipeline, which is being refactored for MCP compatibility. Key integration points:

| RAG Component | Chain Impact |
|---------------|-------------|
| `VectorUtil.findByEmbedding()` now returns `vectorReferenceObjectId` | RAG step results can construct MCP URIs without a second DB lookup |
| `McpCitationResolver` replaces `ChatUtil.getDataCitations()` | RAG steps should use `McpCitationResolver.resolve()` for citation building |
| `DocumentUtil.getStringContent()` returns `ContentExtractionResult` | Chain TOOL steps that extract content get structured metadata |
| Chunking methods (`chunkBySentence`, `chunkByWord`, `chunkByChapter`) are now public | Chain TOOL steps can invoke chunking directly for multi-step vectorization flows |
| `McpContextBuilder` replaces `"--- BEGIN CITATIONS ---"` format | RAG step output is natively MCP-compatible |

**Implementation sequence:** RAG pipeline refactoring (MCP.md Phase 5b/5c) must complete before chain RAG steps can produce MCP-formatted output. During the transition, `executeRAGStep()` should support both legacy citation format and MCP context blocks, controlled by the plan's `chainMode` flag.

### 6.4 SCIM Identity Operations in Chains

**Dependency:** SCIM.md (full document)

Chain TOOL steps may operate on identity resources (users, groups, roles, permissions). When they do, the chain should leverage SCIM adapter classes for consistent mapping:

**A. Identity Tool Steps**

New tool methods that chains can invoke for identity operations:

| Tool Method | Maps To | SCIM Adapter |
|-------------|---------|--------------|
| `createUser(name, attributes)` | `POST /scim/v2/Users` | `ScimUserAdapter.fromScim()` |
| `updateUser(objectId, attributes)` | `PATCH /scim/v2/Users/{id}` | `ScimPatchHandler.applyPatch()` |
| `createGroup(name, members)` | `POST /scim/v2/Groups` | `ScimGroupAdapter.fromScim()` |
| `addGroupMember(groupId, userId)` | `PATCH /scim/v2/Groups/{id}` | `ScimPatchHandler` add operation |
| `findUsers(filter)` | `GET /scim/v2/Users?filter=...` | `ScimFilterParser.parse()` |

These tool methods use the SCIM adapter layer internally, ensuring that:
1. Field mapping is consistent (e.g., `userName` ↔ `name`, `active` ↔ `status`)
2. Organization scoping is enforced (all operations bounded to contextUser's org)
3. Person records are created/updated alongside user records per AM7 convention
4. Validation rules match SCIM compliance (required fields, uniqueness constraints)

**B. Organization Boundary Enforcement**

Chain execution inherits the contextUser's organization scope. This is critical when chains perform identity operations:

- A chain cannot create users in a different organization than the contextUser's
- `ScimFilterParser` queries generated by chain steps are automatically scoped to the contextUser's `organizationId` via `AccessPoint`
- Cross-organization references in `am7://` URIs will fail with 404, not 403 (consistent with SCIM behavior — see SCIM.md Organization Scoping)

**C. SCIM Bulk Operations in Chains**

For chains that need to create or modify multiple identity resources, a single TOOL step can invoke SCIM bulk operations:

```java
// Chain TOOL step: bulk identity provisioning
executeTool("scimBulk", Map.of(
    "operations", List.of(
        Map.of("method", "POST", "path", "/Users", "data", user1Json),
        Map.of("method", "POST", "path", "/Users", "data", user2Json),
        Map.of("method", "POST", "path", "/Groups", "data", groupJson, "bulkId", "grp1"),
        Map.of("method", "PATCH", "path", "/Groups/bulkId:grp1", "data", addMembersJson)
    ),
    "failOnErrors", 1
));
```

### 6.5 MCP Tool Exposure of Chain Capabilities

**Dependency:** MCP.md Part 5 (Tool Mapping)

Register chain-related MCP tools alongside existing AM7 tools:

```json
[
  {
    "name": "am7_chain_execute",
    "description": "Execute a dynamic LLM call chain",
    "inputSchema": { "...": "see Phase 3.4" }
  },
  {
    "name": "am7_chain_status",
    "description": "Get the current status of a running or completed chain",
    "inputSchema": {
      "type": "object",
      "properties": {
        "planUri": { "type": "string", "description": "am7:// URI of the chain plan" }
      },
      "required": ["planUri"]
    }
  },
  {
    "name": "am7_chain_cancel",
    "description": "Cancel a running chain execution",
    "inputSchema": {
      "type": "object",
      "properties": {
        "planUri": { "type": "string" }
      },
      "required": ["planUri"]
    }
  }
]
```

### 6.6 Chain Events as MCP Notifications

When a chain is triggered via MCP (`am7_chain_execute`), progress events should be emittable as MCP resource-change notifications:

```java
// In ChainEventHandler — dual-mode emission
public void onChainEvent(BaseRecord user, BaseRecord chainEvent) {
    // WebSocket chirp (existing)
    String eventJson = JSONUtil.exportObject(chainEvent);
    WebSocketService.chirpUser(user, new String[] {
        "chainEvent", chainEvent.get("eventType"), eventJson
    });

    // MCP notification (new — for MCP subscription listeners)
    if (mcpSubscriptionActive) {
        String planUri = "am7://default/tool.plan/" + chainEvent.get("planName");
        McpNotification notification = McpNotification.resourceUpdated(planUri);
        mcpEventSink.send(notification);
    }
}
```

This enables MCP clients to subscribe to chain progress via `resources/subscribe` on the plan URI.

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
12. **MCP context format (Group L):** Chain context serialization uses `<mcp:context>` blocks; RAG step output is MCP-compliant; LLM steps receive MCP-formatted context injection
13. **MCP tool execution:** `am7_chain_execute` MCP tool triggers chain, returns MCP-formatted result; `am7_chain_status` returns plan state; chain plan readable as MCP resource
14. **MCP event notifications:** Chain events emitted as MCP `resource/updated` notifications when triggered via MCP; MCP subscription on plan URI receives progress events
15. **URI references:** Chain context uses `am7://` URIs for object references; RAG results include `vectorReferenceObjectId` for URI construction; `Am7Uri` round-trip from BaseRecord and back
16. **SCIM adapter integration:** Identity TOOL steps use SCIM adapters for consistent field mapping; organization scoping enforced for identity operations; SCIM bulk operations work within chain steps
17. **Cross-module consistency:** Chain error responses follow SCIM-style format; chain authorization uses same `AccessPoint` PBAC as SCIM; MCP context filtering in client uses same `McpContextFilter` for chain output and regular chat

---

## Key Files Summary

| File | Action | Module |
|------|--------|--------|
| `type/StepTypeEnumType.java` | Create | Objects7 |
| `type/StepStatusEnumType.java` | Create | Objects7 |
| `models/tool/planStepModel.json` | Extend (+ `vectorReferenceUri`) | Objects7 |
| `models/tool/planModel.json` | Extend (+ `mcpSessionId`) | Objects7 |
| `models/tool/chainEventModel.json` | Create (+ `mcpContextUri`) | Objects7 |
| `agent/IChainEventListener.java` | Create | Agent7 |
| `agent/ChainExecutor.java` | Create (uses `McpContextBuilder`, `Am7Uri`, `McpCitationResolver`) | Agent7 |
| `agent/AgentToolManager.java` | Modify (+ `createChainPlanFromMcp()`) | Agent7 |
| `resources/chainRoutePrompt.txt` | Create | Agent7 |
| `resources/chainContextPrompt.txt` | Create (MCP context block format) | Agent7 |
| `resources/planPrompt.txt` | Extend | Agent7 |
| `sockets/ChainEventHandler.java` | Create (dual-mode: WebSocket chirp + MCP notification) | Service7 |
| `sockets/WebSocketService.java` | Modify (add chain routing) | Service7 |
| `rest/services/ChatService.java` | Modify (add chain endpoints) | Service7 |
| `rest/services/mcp/McpToolService.java` | Modify (register `am7_chain_execute`, `am7_chain_status`, `am7_chain_cancel`) | Service7 |
| `rest/services/mcp/McpResourceService.java` | Extend (chain plan readable as MCP resource) | Service7 |
| `client/pageClient.js` | Modify (add chainEvent routing) | Ux7 |
| `client/view/chat.js` | Modify (chain stream + progress view + `McpContextFilter` integration) | Ux7 |
| `tests/TestChainExecutor.java` | Create (Groups A-M) | Agent7 |
| `tests/TestAgent.java` | Keep unchanged | Agent7 |

### Dependency on SCIM and MCP Files

The chain implementation depends on these files from the SCIM and MCP plans:

| Dependency File | Chain Usage | Source Plan |
|----------------|-------------|-------------|
| `mcp/adapter/Am7Uri.java` | URI construction/parsing for object references | MCP.md Part 9 |
| `mcp/context/McpContextBuilder.java` | Chain context serialization, RAG output formatting | MCP.md Part 6 |
| `mcp/context/McpContextParser.java` | Parsing LLM responses containing MCP blocks | MCP.md Part 6 |
| `mcp/context/McpContextFilter.java` | Client-side chain output rendering | MCP.md Part 5 |
| `mcp/McpCitationResolver.java` | RAG step citation building (replaces `ChatUtil.getDataCitations()`) | MCP.md Part 14 |
| `scim/adapter/ScimUserAdapter.java` | Identity TOOL steps for user create/update | SCIM.md Phase 2 |
| `scim/adapter/ScimGroupAdapter.java` | Identity TOOL steps for group operations | SCIM.md Phase 2 |
| `scim/filter/ScimFilterParser.java` | Identity TOOL steps for user/group search | SCIM.md Phase 3 |
| `scim/patch/ScimPatchHandler.java` | Identity TOOL steps for PATCH operations | SCIM.md Phase 4 |
| `VectorUtil` (refactored) | RAG steps depend on `vectorReferenceObjectId` field | MCP.md Part 14 |
| `client/mcp/index.js` | Client-side MCP parsing/filtering module | MCP.md Part 5 |
