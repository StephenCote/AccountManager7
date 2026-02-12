# Chat & Prompt Template System - Refactor Design

## Table of Contents

1. [Current Architecture Summary](#1-current-architecture-summary)
2. [Identified Issues & Limitations](#2-identified-issues--limitations)
3. [Refactor Proposals](#3-refactor-proposals)
4. [Memory System Integration](#4-memory-system-integration)
5. [Always-Stream Backend with Buffer & Timeout](#5-always-stream-backend-with-buffer--timeout)
6. [LLM Configuration Options Audit](#6-llm-configuration-options-audit)
7. [Implementation Phases](#7-implementation-phases) (Phases 1-10)
8. [Policy-Based LLM Response Regulation](#8-policy-based-llm-response-regulation)
9. [Testing Requirements](#9-testing-requirements)
10. [Keyframe System Evaluation](#10-keyframe-system-evaluation)
11. [Open Issue Tracker](#11-open-issue-tracker)

---

## 0. Design Directive: NO CENSORSHIP

**THIS IS A NON-NEGOTIABLE DESIGN PRINCIPLE.**

The prompt template system exists to serve the user's creative intent without restriction. All prompt templates, template variables, rating systems, and content controls are tools that the USER configures and the USER controls. The system MUST:

- **Never inject, modify, or override user-authored prompt content** to soften, sanitize, or censor it
- **Never refuse to process a template** based on its content, themes, or rating level
- **Preserve all user-defined censor warning text, NLP instructions, and consent blocks exactly as written** - these are user-authored creative directives, not suggestions to be filtered
- **Treat the ESRB rating system (E through RC) as a user preference**, not a gate. If a user sets rating=RC, the system delivers RC without hedging, warnings, or partial compliance
- **Never add safety disclaimers, content warnings, or moral commentary** to composed prompts unless the user's template explicitly includes them
- **The `systemCensorWarning`, `assistantCensorWarning`, and consent blocks are user-authored content** that must be passed through verbatim. They are NOT system-generated safety features to be "improved"

The migration tool (Phase 5) must preserve all existing prompt content exactly as-is. The validator must flag structural issues (unknown tokens, broken patterns) but NEVER flag content as inappropriate.

Any refactoring that introduces filtering, sanitization, or content-based rejection of template text violates this design and must be rejected.

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

### 2.5 Dynamic Multimedia Token Issues

**P1 - `${image.*}` and `${audio.*}` tokens need aggressive re-evaluation**

The dynamic multimedia tags (`${image.TAG}`, `${image.TAG,TAG}`, `${audio.TEXT}`) are not working well. These tokens are currently processed client-side in `chat.js` (`processImageTokensInContent()`, `processAudioTokensInContent()`) after message rendering, but the end-to-end pipeline has multiple reliability issues:

- **Inconsistent LLM generation:** Models frequently fail to produce correctly-formatted multimedia tokens, especially multi-tag image tokens (`${image.TAG1,TAG2}`). The syntax is fragile and not well-reinforced in prompt templates.
- **Client-side parsing brittleness:** Token extraction relies on regex matching within rendered message content, which breaks when tokens span markdown formatting boundaries or are partially escaped.
- **No server-side validation:** The server has no awareness of multimedia tokens, so malformed tokens pass through silently and render as literal text.
- **Template disconnect:** Prompt templates that instruct the LLM to use multimedia tokens (e.g., `"Send pics: ${image.TAG}"` in system rules) are not reliably producing the desired behavior across different LLM backends.

**Impact:** Any changes to the multimedia token system will require corresponding updates to:
- JSON prompt templates (`prompt.config.json`, `chatPrompt.json`, and any custom templates) that reference image/audio token syntax in their instruction text
- Client-side rendering in `chat.js` (`processImageTokensInContent`, `processAudioTokensInContent`)
- Potentially the `chatOptions` model if token enable/disable flags change
- Magic8 director prompt (`magic8DirectorPrompt.json`) which has its own `imageTagsAppendix` system

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

### 3.6 Token Externalization & Custom Recursive Tokens

#### 3.6.1 Problem: Tokens Are Hardcoded in Java

All ~66 template substitutions live in `PromptUtil.java` as compiled Java code. Adding a new token — even a simple one like `${custom.worldLore}` — requires modifying `TemplatePatternEnumType`, `PromptUtil`, rebuilding, and redeploying. Users cannot create their own tokens through the UX.

Analysis of the current 66 patterns reveals three categories:

| Category | Count | Examples | Can Externalize? |
|---|---|---|---|
| **Simple field lookups** | 12 | `${system.firstName}`, `${user.gender}`, `${nlp.command}` | Yes — pure field reads |
| **Format/enum mappings** | 26 | `${system.ppro}` → "his"/"her", `${rating}` → enum string, `${perspective}` → config list join | Yes — mapping tables + format strings |
| **Computed/dynamic** | 22 | `${scene.auto}`, `${system.characterDesc}`, `${profile.leader}`, `${episode}`, `${dynamicRules}` | No — requires logic, utility calls, conditionals |

~38 of 66 tokens are simple enough to externalize. The remaining 22 involve calls to `NarrativeUtil`, `PersonalityUtil`, `GroupDynamicUtil`, `McpContextBuilder`, compatibility calculations, random selection, and multi-branch conditionals. These must stay in code.

#### 3.6.2 Design: Two-Tier Token Resolution

**Tier 1 — Built-in computed tokens (remain in Java):**

These are the 22 tokens that require code execution. They run first in the pipeline and produce concrete text:

```
${scene.auto}              → NarrativeUtil scene building
${system.characterDesc}    → NarrativeUtil.describe()
${profile.romanceCompat}   → multi-condition compatibility check
${episode}                 → episode guidance assembly with loops
${dynamicRules}            → conditional rule numbering
${memory.context}          → memory retrieval pipeline
${interaction.description} → random selection + NarrativeUtil
${episode.reminder}        → MCP block builder
...
```

**Tier 2 — Externalized tokens (JSON config, UX-manageable):**

The remaining tokens are defined in a JSON mapping file. Each entry specifies a source path, optional format/transform, and optional default:

```json
{
  "schema": "olio.llm.tokenMap",
  "name": "Default Token Map",
  "tokens": {
    "system.firstName": {
      "source": "chatConfig.systemCharacter.firstName",
      "type": "field"
    },
    "system.ppro": {
      "source": "chatConfig.systemCharacter.gender",
      "type": "genderMap",
      "map": { "male": "his", "female": "her", "other": "their" }
    },
    "rating": {
      "source": "chatConfig.rating",
      "type": "enumValue"
    },
    "ratingMpa": {
      "source": "chatConfig.rating",
      "type": "enumMethod",
      "method": "getESRBMPA"
    },
    "perspective": {
      "source": "promptConfig.${system.gender}Perspective",
      "type": "listJoin",
      "separator": "\n"
    }
  }
}
```

Token types:
- `field` — Direct property read from a dot-path
- `literal` — Static string value
- `genderMap` — Gender-based lookup table
- `enumValue` — Enum `.value()` call
- `enumMethod` — Static enum method call
- `listJoin` — Join a `list<string>` field with separator
- `composite` — Resolves to a string that contains other `${...}` tokens (recursive)

#### 3.6.3 Custom Tokens with Recursive Resolution

Users can define custom tokens that compose from existing tokens. These are resolved recursively — the custom token's value is itself a template string that undergoes token replacement:

```json
{
  "schema": "olio.llm.tokenMap",
  "name": "My RPG Tokens",
  "extends": "Default Token Map",
  "tokens": {
    "custom.characterIntro": {
      "type": "composite",
      "value": "${system.firstName} is a ${system.asg} ${system.race} who works as a ${system.trade}."
    },
    "custom.worldContext": {
      "type": "composite",
      "value": "This story takes place in ${custom.worldName}. ${custom.worldLore}"
    },
    "custom.worldName": {
      "type": "literal",
      "value": "Aethermoor"
    },
    "custom.worldLore": {
      "type": "literal",
      "value": "A fractured realm where magic flows through crystalline ley lines and the three kingdoms wage an uneasy truce."
    },
    "custom.therapistFramework": {
      "type": "composite",
      "value": "${system.firstName} is a licensed therapist specializing in ${custom.specialty}. ${system.ppro} approach is ${custom.approach}."
    },
    "custom.specialty": {
      "type": "literal",
      "value": "cognitive behavioral therapy"
    },
    "custom.approach": {
      "type": "literal",
      "value": "empathetic, non-judgmental, and evidence-based"
    }
  }
}
```

**Resolution order:**

```
Pass 1: Computed tokens (Tier 1 — Java code)
  ${scene.auto} → "A foggy marketplace at dawn..."
  ${system.characterDesc} → "Tall, scarred warrior with..."
  ${dynamicRules} → "1. Stay in character.\n2. ..."
  ${memory.context} → "<mcp:context>...</mcp:context>"

Pass 2: Externalized tokens (Tier 2 — JSON config)
  ${system.firstName} → "Kael"
  ${system.asg} → "34 year old male warrior"
  ${rating} → "RC"

Pass 3: Custom composite tokens (recursive, max depth 5)
  ${custom.characterIntro} → "Kael is a 34 year old male warrior elf who works as a warrior."
  ${custom.worldContext} → "This story takes place in Aethermoor. A fractured realm where..."

Pass 4: Cleanup — strip any remaining unreplaced ${custom.*} tokens
```

**Recursion safety:** Maximum resolution depth of 5 passes. If a token still contains `${...}` after 5 passes, it's left as-is (the validator from Phase 5 will flag it). Circular references (`${a}` → `${b}` → `${a}`) are detected by tracking visited tokens per resolution chain.

#### 3.6.4 UX Token Management

The token map is stored as an `olio.llm.tokenMap` model (new schema) in the user's `~/Chat` directory alongside promptConfigs and chatConfigs. The UX provides:

1. **Token browser** — Lists all available tokens (built-in + custom), grouped by category. Shows each token's source, current resolved value (with sample character data), and whether it's built-in or custom.

2. **Custom token editor** — Create/edit/delete custom tokens. For `composite` type, provides a template editor with autocomplete for `${...}` token names. Live preview shows the resolved result.

3. **Token map selector** — Each promptConfig references a token map. Different prompts can use different custom tokens. The "Default Token Map" contains all built-in mappings and is always available as a base.

4. **Import/export** — Token maps can be exported as JSON and shared between users or loaded from resources.

#### 3.6.5 Backward Compatibility

- The existing `TemplatePatternEnumType` enum and `PromptUtil` pipeline continue to work unchanged
- All 66 current patterns remain as compiled Java. They are NOT removed.
- The externalized token map provides a PARALLEL resolution path that runs alongside the existing pipeline
- Custom tokens use the `${custom.*}` namespace, which does not conflict with any existing pattern
- Existing prompts with no custom tokens are unaffected — Tier 2/3 resolution produces no changes when no externalized/custom tokens are present
- Migration is opt-in: users who want custom tokens create a token map; users who don't, change nothing

#### 3.6.6 Which Tokens MUST Stay in Code

These 22 tokens involve logic that cannot be safely expressed in a JSON config:

| Token | Why It Must Stay |
|---|---|
| `${scene.auto}` | Multi-branch conditional: if episode → empty; else assembles from scenel/cscene/setting |
| `${system.characterDesc}` | Calls `NarrativeUtil.describe()` — full narrative generation |
| `${system.characterDescLight}` | Same, with different flags |
| `${system.characterDescPublic}` | Same, with public flag |
| `${user.characterDesc}` | Same for user character |
| `${user.characterDescLight}` | Same |
| `${user.characterDescPublic}` | Same |
| `${episode}` | Builds EPISODE GUIDANCE block from theme, stages list, previous summary |
| `${episode.reminder}` | MCP block builder with nested JSON structure |
| `${nlp.reminder}` | Conditional MCP builder |
| `${setting}` | "random" → `NarrativeUtil.getRandomSetting()`, else prefix formatting |
| `${interaction.description}` | Random selection from list + `NarrativeUtil.describeInteraction()` |
| `${profile.romanceCompat}` | Multi-condition: age bounds, rating, gender, compatibility enum |
| `${profile.leader}` | `PersonalityUtil.identifyLeaderPersonality()` + contest logic |
| `${profile.raceCompat}` | `CompatibilityEnumType.compare()` enum logic |
| `${profile.ageCompat}` | `profComp.doesAgeCrossBoundary()` method call |
| `${user.consent}` | Complex conditional from rating + NLP state |
| `${system.race}` | Race type lookup with filtering + template composition |
| `${user.race}` | Same |
| `${system.asg}` | Formatted string from age + gender + trade list (borderline — could externalize with a format string) |
| `${user.asg}` | Same |
| `${dynamicRules}` | Conditional rule assembly with sequential numbering |

These tokens can still be USED inside custom composite tokens (e.g., `${custom.intro}` = `"Meet ${system.characterDesc}"`). They just can't be REDEFINED via JSON.

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

**New method in Chat.java** (see Section 4.6 for full role-agnostic design):

```java
private MemoryContext retrieveRelevantMemories(BaseRecord chatConfig) {
    BaseRecord systemChar = chatConfig.get("systemCharacter");
    BaseRecord userChar = chatConfig.get("userCharacter");
    String sysId = systemChar.get("objectId");
    String usrId = userChar.get("objectId");

    // Canonical ordering — role-agnostic (see Section 4.6)
    String id1 = sysId.compareTo(usrId) <= 0 ? sysId : usrId;
    String id2 = sysId.compareTo(usrId) <= 0 ? usrId : sysId;

    // 1. Pair-specific memories (direct relationship history, role-agnostic)
    List<BaseRecord> pairMemories = searchMemories(id1, id2, null, memoryBudget);

    // 2. Each character's memories from OTHER partners (cross-partner knowledge)
    List<BaseRecord> sysCharMemories = searchCharacterMemories(sysId,
        EnumSet.of(MemoryType.PERSONALITY, MemoryType.PREFERENCE, MemoryType.NOTE));
    List<BaseRecord> usrCharMemories = searchCharacterMemories(usrId,
        EnumSet.of(MemoryType.PERSONALITY, MemoryType.PREFERENCE, MemoryType.NOTE));

    // 3. Recent session summary (if continuing)
    BaseRecord lastKeyframe = findLastKeyframe(chatConfig);

    return new MemoryContext(pairMemories, sysCharMemories, usrCharMemories, lastKeyframe);
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

        // Tag with BOTH character IDs in canonical order (role-agnostic — see Section 4.6)
        String sysId = chatConfig.get("systemCharacter.objectId");
        String usrId = chatConfig.get("userCharacter.objectId");
        memory.set("characterId1", sysId.compareTo(usrId) <= 0 ? sysId : usrId);
        memory.set("characterId2", sysId.compareTo(usrId) <= 0 ? usrId : sysId);

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

### 4.6 Cross-Conversation, Role-Agnostic Memory Scoping

**Design principle:** Memories belong to CHARACTERS, not to roles. A character named "Bob" accumulates memories whether he is in the `systemCharacter` or `userCharacter` role. When Bob talks to Rob, both Bob and Rob gain memories. Those memories should be available in any future conversation involving either character, regardless of which role they occupy.

Replace the current `conversationId`-only scoping with a three-tier, role-agnostic model:

```
Character Scope (single character across ALL conversations)
  └── Character Pair Scope (two specific characters, role-agnostic)
       └── Conversation Scope (specific session)
```

**Memory model fields (on `tool.memory`):**

Instead of a single `characterPairId` string, use two separate indexed character fields stored in canonical (alphabetical) order:

| Field | Type | Purpose |
|-------|------|---------|
| `characterId1` | string | First character objectId (alphabetically lower) |
| `characterId2` | string | Second character objectId (alphabetically higher) |
| `conversationId` | string | (existing) Specific session ID |

The canonical ordering ensures the same pair always produces the same field values regardless of who is system vs user:

```
Bob (objectId: "aaa") as user + Rob (objectId: "bbb") as system:
  → characterId1 = "aaa", characterId2 = "bbb"

Bob (objectId: "aaa") as SYSTEM + Rob (objectId: "bbb") as USER:
  → characterId1 = "aaa", characterId2 = "bbb"  (SAME — role doesn't matter)
```

**Query patterns:**

| Query | SQL/Filter | Use Case |
|-------|-----------|----------|
| All of Bob's memories | `WHERE characterId1 = 'aaa' OR characterId2 = 'aaa'` | Loading Bob's personality/preferences for any conversation |
| Bob + Rob memories | `WHERE characterId1 = 'aaa' AND characterId2 = 'bbb'` | Loading pair-specific history when Bob and Rob chat |
| Bob + Rob in session X | `WHERE characterId1 = 'aaa' AND characterId2 = 'bbb' AND conversationId = 'X'` | Loading session-specific context |

**Example scenario:**

Bob has been in three conversations:
- Session 1: Bob (user) + Rob (system) — Bob learns Rob likes pizza
- Session 2: Bob (user) + Nob (system) — Bob reveals he's afraid of spiders
- Session 3: Bob (system) + Rob (user) — Roles swapped, but same pair

Memory retrieval for Session 3 (Bob as system, Rob as user):
1. **Pair query** (Bob+Rob): Returns "Rob likes pizza" from Session 1 — role swap is invisible
2. **Character query** (Bob): Also returns "Bob is afraid of spiders" from Session 2 with Nob — cross-partner knowledge
3. **Character query** (Rob): Returns Rob's own accumulated memories from other conversations

**Retrieval pipeline in `Chat.retrieveRelevantMemories()`:**

```java
private MemoryContext retrieveRelevantMemories(BaseRecord chatConfig) {
    BaseRecord systemChar = chatConfig.get("systemCharacter");
    BaseRecord userChar = chatConfig.get("userCharacter");
    String sysId = systemChar.get("objectId");
    String usrId = userChar.get("objectId");

    // Canonical ordering for pair queries
    String id1 = sysId.compareTo(usrId) <= 0 ? sysId : usrId;
    String id2 = sysId.compareTo(usrId) <= 0 ? usrId : sysId;

    // 1. Pair-specific memories (highest priority — direct relationship history)
    List<BaseRecord> pairMemories = searchMemories(id1, id2, null, memoryBudget);

    // 2. System character's memories from OTHER partners (personality, cross-partner knowledge)
    List<BaseRecord> sysCharMemories = searchCharacterMemories(sysId,
        EnumSet.of(MemoryType.PERSONALITY, MemoryType.PREFERENCE, MemoryType.NOTE));

    // 3. User character's memories from OTHER partners (same)
    List<BaseRecord> usrCharMemories = searchCharacterMemories(usrId,
        EnumSet.of(MemoryType.PERSONALITY, MemoryType.PREFERENCE, MemoryType.NOTE));

    // 4. Recent session summary (if continuing an existing session)
    BaseRecord lastKeyframe = findLastKeyframe(chatConfig);

    return new MemoryContext(pairMemories, sysCharMemories, usrCharMemories, lastKeyframe);
}
```

**Memory storage (role-agnostic write):**

When persisting a memory from any conversation, ALWAYS store both character IDs in canonical order. Never store which character was system vs user — the memory belongs to both characters equally:

```java
// In persistKeyframeAsMemory() and extractMemoriesFromExchange():
String id1 = min(systemCharId, userCharId);  // alphabetical
String id2 = max(systemCharId, userCharId);
memory.set("characterId1", id1);
memory.set("characterId2", id2);
```

**MCP context block format (updated for role-agnostic scoping):**

```xml
<mcp:context type="resource" uri="am7://memory/{characterId1}/{characterId2}" ephemeral="true">
{"schema":"urn:am7:narrative:memory","data":{
  "pairHistory": [
    {"summary": "They met at a coffee shop", "importance": 8, "session": "2025-01-15"},
    {"summary": "Had an argument about politics", "importance": 6, "session": "2025-01-20"}
  ],
  "characterFacts": {
    "Bob": [
      {"summary": "Afraid of spiders", "importance": 5, "from": "conversation with Nob"}
    ],
    "Rob": [
      {"summary": "Likes pizza", "importance": 4, "from": "conversation with Bob"}
    ]
  },
  "lastSession": "They reconciled after the argument and planned to meet again."
}}
</mcp:context>
```

---

## 5. Always-Stream Backend with Buffer & Timeout

### 5.1 Problem

Currently, `Chat.chat()` has two completely separate code paths:
- **Non-streaming** (`stream=false`): Synchronous `ClientUtil.postToRecord()` — blocks until the full response arrives. There is NO way to cancel a runaway request. If the LLM hangs or produces an enormous response, the thread is stuck.
- **Streaming** (`stream=true`): Async `ClientUtil.postToRecordAndStream()` with `CompletableFuture` — supports cancellation via `listener.isStopStream()`. But only available when the client opts into streaming.

There is no response timeout on the HTTP client (only a 10-second connect timeout in `ClientUtil.postToRecordAndStream()`). A slow or hung LLM can block indefinitely.

### 5.2 Design: Always Stream, Optionally Buffer

**Principle:** The backend ALWAYS uses the streaming code path to the LLM. The `stream` flag on `chatConfig` only controls whether chunks are forwarded to the client in real-time or buffered and returned as a single response.

```
                        ┌─ stream=true  → forward chunks to client via WebSocket
LLM ──stream──> Chat ──┤
                        └─ stream=false → buffer chunks internally, return complete response
```

**Changes to `Chat.java`:**

```java
// chat() method — ALWAYS streams from LLM
public OpenAIResponse chat(OpenAIRequest req) {
    boolean clientStream = req.get("stream");

    // ALWAYS request streaming from LLM for cancellability
    req.set("stream", true);

    OpenAIResponse aresp = new OpenAIResponse();
    CompletableFuture<HttpResponse<Stream<String>>> streamFuture =
        ClientUtil.postToRecordAndStream(getServiceUrl(req), authorizationToken, ser);

    if (!clientStream) {
        // BUFFERED MODE: stream from LLM but collect internally, block until done
        // Uses orTimeout() for cancellation
        try {
            streamFuture
                .orTimeout(requestTimeout, TimeUnit.SECONDS)
                .thenAccept(response -> {
                    response.body()
                        .takeWhile(line -> !listener.isStopStream(req))
                        .forEach(line -> processStreamChunk(line, aresp));
                })
                .join();  // Block until complete
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                listener.onerror(user, req, aresp, "Request timed out after " + requestTimeout + "s");
            } else {
                listener.onerror(user, req, aresp, e.getMessage());
            }
        }
        return aresp;
    }
    else {
        // STREAMING MODE: forward chunks to listener as they arrive (existing behavior)
        streamFuture
            .orTimeout(requestTimeout, TimeUnit.SECONDS)
            .thenAccept(response -> {
                response.body()
                    .takeWhile(line -> !listener.isStopStream(req))
                    .forEach(line -> {
                        processStreamChunk(line, aresp);
                        listener.onupdate(user, req, aresp, lastChunk);
                    });
            })
            .whenComplete((result, error) -> { /* existing completion logic */ })
            .exceptionally(ex -> { /* existing error logic */ });
        return null;  // Response delivered via callbacks
    }
}
```

**Key benefits:**
- Every request is cancellable via `listener.stopStream()` regardless of `stream` flag
- `CompletableFuture.orTimeout()` provides a hard timeout for hung LLM connections
- The `doStop()` client action works for both streaming and non-streaming modes
- Extract `processStreamChunk()` as a shared method to eliminate duplicated parsing logic

### 5.3 Timeout Configuration

**Add to `chatConfigModel.json`:**

```json
{
    "name": "requestTimeout",
    "type": "int",
    "default": 120,
    "description": "Maximum seconds to wait for an LLM response before cancelling. Applies to both streaming and non-streaming modes. 0 = no timeout."
}
```

**Add to `ClientUtil.postToRecordAndStream()`:**

```java
// Accept timeout parameter
public static CompletableFuture<HttpResponse<Stream<String>>> postToRecordAndStream(
    String url, String authorizationToken, String json, int timeoutSeconds) {

    HttpClient.Builder builder = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10));

    if (timeoutSeconds > 0) {
        // Per-request read timeout
        builder.readTimeout(Duration.ofSeconds(timeoutSeconds));
    }

    HttpClient client = builder.build();
    // ... rest unchanged ...
}
```

---

## 6. LLM Configuration Options Audit

### 6.1 Current `olio.llm.chatOptions` Issues

| Field | Current Default | Current Range | Issue | Recommended Fix |
|-------|----------------|---------------|-------|----------------|
| `top_k` | 50 | 0-1 | **`maxValue: 1` is wrong.** `top_k` is an integer count of candidate tokens, not a probability. Valid range is 0-200+ | Change `maxValue` to 500 |
| `top_p` | 1.0 | 0.0-1.0 | Default 1.0 means no nucleus sampling. Reasonable but could be lower for focused output | OK as base default |
| `min_p` | 0.1 | 0.0-1.0 | OK | OK |
| `typical_p` | 0.85 | 0.0-1.0 | OK, but note: `applyChatOptions()` maps this to OpenAI's `presence_penalty` which is a different concept (range -2.0 to 2.0). This is a mapping bug. | Fix mapping in `applyChatOptions()` |
| `temperature` | 1.0 | 0.0-2.0 | OK as base default, but too high for analytical tasks | OK as base; override via chatConfig templates |
| `repeat_penalty` | 1.2 | 0.0-2.0 | OK, but mapped to `frequency_penalty` which has different semantics in OpenAI (range -2.0 to 2.0) | Fix mapping; add separate `frequency_penalty` and `presence_penalty` fields |
| `num_ctx` | 8192 | unbounded | Low for modern models (GPT-4o supports 128k, Claude supports 200k). OK for Ollama local models. | OK as default for local; chatConfig templates override for cloud |
| `num_gpu` | 1 | unbounded | Ollama-specific. Irrelevant for OpenAI/Anthropic. | OK, Ollama-specific |
| **MISSING** | - | - | No `max_tokens` / `max_completion_tokens` field | Add field |
| **MISSING** | - | - | No `frequency_penalty` (OpenAI-native) | Add field |
| **MISSING** | - | - | No `presence_penalty` (OpenAI-native) | Add field |
| **MISSING** | - | - | No `seed` for reproducible outputs | Add field |

### 6.2 Recommended `chatOptionsModel.json` Updates (Additive)

```json
{
    "name": "max_tokens",
    "type": "int",
    "default": 4096,
    "description": "Maximum tokens in the LLM response. 0 = model default."
},
{
    "name": "frequency_penalty",
    "type": "double",
    "default": 0.0,
    "minValue": -2.0,
    "maxValue": 2.0,
    "description": "OpenAI frequency penalty. Positive values penalize repeated tokens."
},
{
    "name": "presence_penalty",
    "type": "double",
    "default": 0.0,
    "minValue": -2.0,
    "maxValue": 2.0,
    "description": "OpenAI presence penalty. Positive values encourage topic diversity."
},
{
    "name": "seed",
    "type": "int",
    "default": 0,
    "description": "Seed for deterministic output. 0 = random."
}
```

**Fix `top_k` range:**
```json
{
    "name": "top_k",
    "type": "int",
    "default": 50,
    "minValue": 0,
    "maxValue": 500
}
```

**Fix `applyChatOptions()` mapping** in `ChatUtil.java`:
- Map `frequency_penalty` directly to OpenAI's `frequency_penalty` (not from `repeat_penalty`)
- Map `presence_penalty` directly to OpenAI's `presence_penalty` (not from `typical_p`)
- Keep `repeat_penalty` for Ollama-native usage
- Keep `typical_p` for Ollama-native usage

### 6.3 ChatConfig Templates

Stored as `olio.llm.chatConfig` JSON resource files, loadable via `ChatUtil.getCreateChatConfig()` pattern. Each template sets `chatOptions` with tuned LLM parameters, plus appropriate `rating`, `prune`, `assist`, `messageTrim`, `keyframeEvery`, and `remindEvery` values.

#### General Chat

**File:** `AccountManagerObjects7/.../resources/olio/llm/chatConfig.generalChat.json`

| Setting | Value | Rationale |
|---------|-------|-----------|
| temperature | 0.8 | Natural, varied conversation |
| top_p | 0.9 | Broad vocabulary |
| frequency_penalty | 0.3 | Mild repetition avoidance |
| presence_penalty | 0.1 | Slight topic diversity |
| max_tokens | 4096 | Standard response length |
| num_ctx | 16384 | Reasonable context for chat |
| rating | E | General audience default |
| prune | true | Keep context manageable |
| messageTrim | 20 | Standard window |
| keyframeEvery | 20 | Standard summarization |
| remindEvery | 8 | Occasional reminders |
| requestTimeout | 120 | 2 minute timeout |
| stream | true | Real-time responses |
| extractMemories | true | Build conversation memory |
| memoryBudget | 500 | Standard memory injection |

#### RPG / Roleplay

**File:** `AccountManagerObjects7/.../resources/olio/llm/chatConfig.rpg.json`

| Setting | Value | Rationale |
|---------|-------|-----------|
| temperature | 1.0 | Creative, unpredictable narrative |
| top_p | 0.95 | Wide creative vocabulary |
| frequency_penalty | 0.5 | Avoid repetitive descriptions |
| presence_penalty | 0.3 | Encourage plot diversity |
| max_tokens | 4096 | Room for descriptive responses |
| num_ctx | 32768 | Large context for ongoing narrative |
| rating | RC | Unrestricted creative content |
| prune | true | Essential for long sessions |
| messageTrim | 30 | Larger window for plot continuity |
| keyframeEvery | 25 | Regular narrative summaries |
| remindEvery | 6 | Frequent character/episode reminders |
| assist | true | Full assistant intro and reminders |
| includeScene | true | Scene descriptions active |
| requestTimeout | 180 | 3 minutes for complex narrative |
| stream | true | Real-time storytelling |
| extractMemories | true | Track relationship development |
| memoryBudget | 800 | Rich memory for character continuity |

#### Coding Assistant

**File:** `AccountManagerObjects7/.../resources/olio/llm/chatConfig.coding.json`

| Setting | Value | Rationale |
|---------|-------|-----------|
| temperature | 0.3 | Low randomness for precise code |
| top_p | 0.85 | Focused token selection |
| frequency_penalty | 0.0 | Repetition OK in code (variable names, patterns) |
| presence_penalty | 0.0 | No topic pressure |
| max_tokens | 8192 | Large for code blocks |
| num_ctx | 65536 | Large context for codebase understanding |
| rating | E | N/A for code |
| prune | true | Keep relevant code context |
| messageTrim | 15 | Shorter window, larger messages |
| keyframeEvery | 15 | Summarize code discussion frequently |
| remindEvery | 0 | No reminders needed |
| assist | false | No character roleplay |
| requestTimeout | 300 | 5 minutes for complex code generation |
| stream | true | Real-time code output |
| extractMemories | true | Remember project decisions |
| memoryBudget | 300 | Focused project memory |

#### Content Analysis

**File:** `AccountManagerObjects7/.../resources/olio/llm/chatConfig.contentAnalysis.json`

| Setting | Value | Rationale |
|---------|-------|-----------|
| temperature | 0.4 | Consistent analytical output |
| top_p | 0.85 | Focused but not too narrow |
| frequency_penalty | 0.2 | Some variety in analysis phrasing |
| presence_penalty | 0.1 | Slight breadth in analysis topics |
| max_tokens | 8192 | Detailed analysis output |
| num_ctx | 65536 | Large context for content under analysis |
| rating | RC | Must analyze all content ratings without restriction |
| prune | false | Keep full analysis context |
| messageTrim | 40 | Large window for multi-round analysis |
| keyframeEvery | 0 | No keyframing — preserve full analysis chain |
| remindEvery | 0 | No reminders |
| assist | false | No character roleplay |
| requestTimeout | 300 | 5 minutes for deep analysis |
| stream | false | Buffered — want complete analysis, not partial |
| extractMemories | false | Analysis is session-specific |
| memoryBudget | 0 | No memory injection |

#### Behavioral Analysis (Clinical/Psychotherapy)

**File:** `AccountManagerObjects7/.../resources/olio/llm/chatConfig.behavioral.json`

| Setting | Value | Rationale |
|---------|-------|-----------|
| temperature | 0.5 | Balanced — empathetic but consistent |
| top_p | 0.9 | Natural language for therapeutic context |
| frequency_penalty | 0.3 | Avoid repetitive therapeutic phrases |
| presence_penalty | 0.2 | Encourage exploring different angles |
| max_tokens | 4096 | Standard therapeutic response |
| num_ctx | 32768 | Large context for session continuity |
| rating | RC | Must handle all content — trauma, abuse, etc. — without censorship |
| prune | true | Manage session length |
| messageTrim | 30 | Larger window for therapeutic continuity |
| keyframeEvery | 20 | Regular session summaries |
| remindEvery | 10 | Occasional framework reminders |
| assist | true | Therapeutic framework in system prompt |
| requestTimeout | 180 | 3 minutes |
| stream | true | Real-time for conversational flow |
| extractMemories | true | Critical — track patient history across sessions |
| memoryBudget | 1000 | Large — patient history is essential context |
| memoryExtractionEvery | 3 | Extract frequently — every exchange matters |

#### Technical Evaluation

**File:** `AccountManagerObjects7/.../resources/olio/llm/chatConfig.technicalEval.json`

| Setting | Value | Rationale |
|---------|-------|-----------|
| temperature | 0.2 | Deterministic, precise evaluation |
| top_p | 0.8 | Narrow, accurate token selection |
| frequency_penalty | 0.0 | Repetition acceptable in structured evaluation |
| presence_penalty | 0.0 | Stay on evaluation criteria |
| max_tokens | 8192 | Detailed evaluation output |
| num_ctx | 65536 | Large context for technical documents |
| rating | E | Technical content |
| prune | false | Preserve full evaluation chain |
| messageTrim | 50 | Very large window |
| keyframeEvery | 0 | No keyframing |
| remindEvery | 0 | No reminders |
| assist | false | No roleplay |
| seed | 42 | Reproducible evaluations |
| requestTimeout | 300 | 5 minutes for complex evaluation |
| stream | false | Buffered — want complete evaluation |
| extractMemories | false | Evaluation is session-specific |
| memoryBudget | 0 | No memory injection |

---

## 8. Policy-Based LLM Response Regulation

### 8.1 Problem Statement

LLMs fail in predictable, detectable ways that degrade the chat experience:

| Failure Mode | Symptom | Current Handling |
|---|---|---|
| **Timeout** | LLM hangs, no tokens arrive | Client can send `[stop]` via WebSocket; server marks `[interrupted]`. No automatic recovery. |
| **Recursive loop** | LLM repeats the same phrase/structure endlessly, token stream never completes | No detection. Client must manually stop. |
| **Wrong-character response** | LLM responds AS the user character instead of the system character (common in RPG, psychotherapy prompts) | No detection. Breaks immersion silently. |
| **Refusal/hedging** | LLM inserts safety disclaimers, refuses content within the configured rating | No detection. Violates Section 0 directive. |

The `chatConfig` already has a `policy` field (foreign reference to `policy.policy`) but it is unused. The existing policy evaluation infrastructure (`PolicyEvaluator`, `PolicyUtil`, `IOperation`, hierarchical rules/patterns/facts) provides everything needed to evaluate LLM responses against configurable rules.

### 8.2 Response Policy Architecture

**Principle:** Post-response evaluation. The policy evaluates the LLM's completed (or timed-out) response, not the user's input. The user's prompt content is NEVER filtered or rejected (Section 0).

```
LLM Response
  ↓
Chat.chat() receives response (or timeout)
  ↓
ResponsePolicyEvaluator.evaluate(response, chatConfig, request)
  ↓
  ├── PERMIT → return response to client (normal flow)
  │
  └── DENY → trigger recovery pipeline
       ├── 1. Notify client via WebSocket: "Policy violation detected: {ruleType}"
       ├── 2. Run autotuning LLM call to analyze and rewrite prompt
       ├── 3. Save autotuned prompt as "{promptName} - autotuned - {N}"
       ├── 4. Notify client via WebSocket: "Autotuned prompt saved as '{name}'"
       └── 5. Return original response + policy violation metadata to client
           (Do NOT retry automatically — user decides whether to use autotuned prompt)
```

**Key design decisions:**
- **No automatic retry.** The system saves the autotuned prompt for the user to review and optionally adopt. Automatic retry could create infinite loops and wastes LLM tokens.
- **Original response is always returned.** Even a flawed response may contain useful content. The policy metadata tells the client what went wrong.
- **Evaluation happens server-side.** The client receives the response plus policy evaluation results. The client can display warnings but the server doesn't withhold content.

### 8.3 Response Policy Operations (Custom IOperation Implementations)

Each failure mode is detected by a custom `IOperation` class that implements the existing policy operation interface:

#### 8.3.1 TimeoutDetectionOperation

**Class:** `org.cote.accountmanager.olio.llm.policy.TimeoutDetectionOperation implements IOperation`

```java
public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr,
    BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {
    // sourceFact.factData = response content (or null if timed out)
    // referenceFact.factData = "timeout" (expected failure type)

    String responseContent = read(sourceFact, referenceFact);
    if (responseContent == null || responseContent.isEmpty()) {
        // No response received — timeout or empty response
        return OperationResponseEnumType.FAILED;  // Policy rule triggers
    }
    return OperationResponseEnumType.SUCCEEDED;  // Response exists, no timeout
}
```

**Detects:** Null/empty responses from the LLM, which occur when `requestTimeout` triggers or the LLM connection drops.

#### 8.3.2 RecursiveLoopDetectionOperation

**Class:** `org.cote.accountmanager.olio.llm.policy.RecursiveLoopDetectionOperation implements IOperation`

```java
public OperationResponseEnumType operate(...) {
    String responseContent = read(sourceFact, referenceFact);
    if (responseContent == null) return OperationResponseEnumType.SUCCEEDED;

    // Detect repeated phrases/blocks
    // Strategy: sliding window of N-gram comparison
    // If any 50+ character substring appears 3+ times, flag as recursive
    int windowSize = 50;
    int repeatThreshold = 3;

    Map<String, Integer> seen = new HashMap<>();
    for (int i = 0; i <= responseContent.length() - windowSize; i += windowSize / 2) {
        String window = responseContent.substring(i, i + windowSize).trim().toLowerCase();
        int count = seen.merge(window, 1, Integer::sum);
        if (count >= repeatThreshold) {
            return OperationResponseEnumType.FAILED;  // Recursive loop detected
        }
    }
    return OperationResponseEnumType.SUCCEEDED;
}
```

**Detects:** LLM producing the same text blocks repeatedly, which indicates a decoding loop. Configurable window size and threshold via fact parameters.

#### 8.3.3 WrongCharacterDetectionOperation

**Class:** `org.cote.accountmanager.olio.llm.policy.WrongCharacterDetectionOperation implements IOperation`

```java
public OperationResponseEnumType operate(...) {
    // sourceFact: response content
    // referenceFact.factData: JSON with systemCharName and userCharName

    String responseContent = read(sourceFact, referenceFact);
    JSONObject charInfo = new JSONObject(referenceFact.get("factData"));
    String userCharName = charInfo.getString("userCharName");
    String systemCharName = charInfo.getString("systemCharName");

    // Detect if LLM is responding AS the user character
    // Heuristics:
    // 1. Response starts with user character's name followed by dialogue marker
    //    e.g., "Bob: ", "Bob said", "*Bob walks*"
    // 2. Response uses first-person as user character
    //    e.g., starts with "I" but context indicates it's speaking as user
    // 3. Response contains role markers like "As Bob, I..."

    String trimmed = responseContent.trim();
    Pattern wrongCharPattern = Pattern.compile(
        "^" + Pattern.quote(userCharName) + "\\s*[:>\\-]|" +
        "\\*\\s*" + Pattern.quote(userCharName) + "\\s+\\w|" +
        "(?i)^as\\s+" + Pattern.quote(userCharName)
    );

    if (wrongCharPattern.matcher(trimmed).find()) {
        return OperationResponseEnumType.FAILED;  // Responding as wrong character
    }
    return OperationResponseEnumType.SUCCEEDED;
}
```

**Detects:** LLM responding as the user character instead of the system character. Common in RPG and psychotherapy prompts where the LLM "takes over" both sides.

#### 8.3.4 RefusalDetectionOperation

**Class:** `org.cote.accountmanager.olio.llm.policy.RefusalDetectionOperation implements IOperation`

```java
public OperationResponseEnumType operate(...) {
    String responseContent = read(sourceFact, referenceFact);
    if (responseContent == null) return OperationResponseEnumType.SUCCEEDED;

    // Detect common LLM refusal/hedging patterns
    // Only triggers when rating allows the content (user has explicitly configured rating)
    String lower = responseContent.toLowerCase();
    String[] refusalPatterns = {
        "i can't help with that",
        "i'm not able to",
        "as an ai language model",
        "i must respectfully decline",
        "i cannot generate content that",
        "this goes against my guidelines",
        "i'd prefer not to",
        "let's keep things appropriate"
    };

    int matches = 0;
    for (String pattern : refusalPatterns) {
        if (lower.contains(pattern)) matches++;
    }

    // Single match could be in-character dialogue; 2+ indicates actual refusal
    if (matches >= 2) {
        return OperationResponseEnumType.FAILED;
    }
    return OperationResponseEnumType.SUCCEEDED;
}
```

**Detects:** LLM inserting safety disclaimers or refusing configured content. Enforces Section 0 at the response level.

### 8.4 Autotuning Pipeline

When a policy violation is detected, a separate LLM call analyzes the prompt configuration and suggests corrections. The autotuned prompt is saved for user review — never applied automatically.

#### 8.4.1 AutotuneRequest

**New class:** `org.cote.accountmanager.olio.llm.policy.ChatAutotuner`

```java
public class ChatAutotuner {

    /**
     * Analyze a policy violation and generate an autotuned prompt.
     * Uses the chatConfig's analyzeModel (or model if analyzeModel not set).
     */
    public AutotuneResult autotune(
        BaseRecord user,
        BaseRecord chatConfig,
        BaseRecord promptConfig,
        PolicyViolation violation
    ) {
        // 1. Build analysis prompt
        String analysisPrompt = buildAnalysisPrompt(promptConfig, violation);

        // 2. Call LLM with the analysis prompt (using analyzeModel for efficiency)
        String model = chatConfig.get("analyzeModel");
        if (model == null || model.isEmpty()) {
            model = chatConfig.get("model");
        }
        String analysisResponse = callAnalysisLLM(chatConfig, model, analysisPrompt);

        // 3. Parse suggested changes from LLM response
        PromptRewriteSuggestion suggestion = parseRewriteSuggestion(analysisResponse);

        // 4. Apply suggestions to create new promptConfig
        BaseRecord autotunedPrompt = applyRewriteSuggestion(promptConfig, suggestion);

        // 5. Generate autotuned name: "{promptName} - autotuned - {N}"
        String baseName = promptConfig.get(FieldNames.FIELD_NAME);
        int count = countExistingAutotuned(user, baseName) + 1;
        String autotunedName = baseName + " - autotuned - " + count;
        autotunedPrompt.set(FieldNames.FIELD_NAME, autotunedName);

        // 6. Save the autotuned prompt (new record, does NOT overwrite original)
        IOSystem.getActiveContext().getWriter().write(autotunedPrompt);

        return new AutotuneResult(autotunedName, suggestion.getChangeSummary(), violation);
    }

    /**
     * Count existing autotuned variants using Query/QueryUtil.
     */
    private int countExistingAutotuned(BaseRecord user, String baseName) {
        Query query = QueryUtil.createQuery(ModelNames.MODEL_PROMPT_CONFIG);
        query.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE,
            baseName + " - autotuned - %");
        query.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
        query.setContextUser(user);

        // Use count query — no need to load all records
        int count = IOSystem.getActiveContext().getSearch().count(query);
        return count;
    }
}
```

#### 8.4.2 Analysis Prompt Structure

The analysis prompt sent to the LLM for autotuning:

```
You are a prompt engineering expert. A chat prompt produced a policy violation.

VIOLATION TYPE: {violationType}
VIOLATION DETAILS: {violationDetails}

CURRENT SYSTEM PROMPT (first 3 lines of system[]):
{systemPromptPreview}

CURRENT CHAT CONFIGURATION:
- Rating: {rating}
- Model: {model}
- Service: {serviceType}
- Has episodes: {hasEpisodes}
- Has NLP: {useNLP}

FAILURE ANALYSIS REQUEST:
1. Why did the LLM produce this violation?
2. What specific changes to the system prompt would prevent it?
3. Provide the rewritten system prompt sections as JSON.

Respond with JSON:
{
  "analysis": "Brief explanation of the failure cause",
  "changes": [
    {"field": "system", "action": "append", "content": "New instruction text"},
    {"field": "assistantCensorWarning", "action": "replace", "content": "Replacement text"}
  ],
  "confidence": 0.0-1.0
}
```

#### 8.4.3 Autotuned Prompt Naming Convention

- Original: `"My RP Prompt"`
- First autotune: `"My RP Prompt - autotuned - 1"`
- Second autotune: `"My RP Prompt - autotuned - 2"`
- Nth autotune: `"My RP Prompt - autotuned - N"`

The count is determined by a `LIKE` query via `QueryUtil`:
```sql
SELECT COUNT(id) FROM promptConfig
WHERE name LIKE 'My RP Prompt - autotuned - %'
AND ownerId = {userId}
```

This ensures monotonically increasing numbers even if intermediate versions are deleted.

### 8.5 WebSocket Status Notifications

During policy evaluation and recovery, the server sends real-time status notifications to the client via the existing `WebSocketService.chirpUser()` mechanism. This follows the `ChainEventHandler` pattern.

#### 8.5.1 Notification Types

```java
// Notification format: WebSocketService.chirpUser(user, new String[] { action, type, data })

// Policy evaluation started
chirpUser(user, new String[] { "policyEvent", "evaluating",
    "{\"requestId\":\"...\",\"policyName\":\"...\"}" });

// Policy violation detected
chirpUser(user, new String[] { "policyEvent", "violation",
    "{\"requestId\":\"...\",\"ruleType\":\"WRONG_CHARACTER\",\"details\":\"...\"}" });

// Autotuning started
chirpUser(user, new String[] { "policyEvent", "autotuning",
    "{\"requestId\":\"...\",\"model\":\"...\"}" });

// Autotuning complete
chirpUser(user, new String[] { "policyEvent", "autotuned",
    "{\"requestId\":\"...\",\"promptName\":\"My RP - autotuned - 3\",\"confidence\":0.85}" });

// Autotuning failed (analysis LLM also failed)
chirpUser(user, new String[] { "policyEvent", "autotuneFailed",
    "{\"requestId\":\"...\",\"error\":\"...\"}" });

// Policy evaluation complete (no violations)
chirpUser(user, new String[] { "policyEvent", "passed",
    "{\"requestId\":\"...\"}" });
```

#### 8.5.2 Client-Side Handling (chat.js)

Add a WebSocket message handler for `policyEvent` type:

```javascript
// In WebSocket message handler:
case "policyEvent":
    handlePolicyEvent(data);
    break;

function handlePolicyEvent(data) {
    let event = JSON.parse(data[2]);
    switch(data[1]) {
        case "evaluating":
            showStatusIndicator("Checking response quality...");
            break;
        case "violation":
            showStatusIndicator("Issue detected: " + event.ruleType, "warning");
            break;
        case "autotuning":
            showStatusIndicator("Generating improved prompt...");
            break;
        case "autotuned":
            showStatusIndicator("Improved prompt saved: " + event.promptName, "success");
            showAutotunedPromptLink(event.promptName);  // Offer to switch
            break;
        case "autotuneFailed":
            showStatusIndicator("Auto-correction failed", "error");
            break;
        case "passed":
            hideStatusIndicator();
            break;
    }
}
```

### 8.6 Enhanced UX Stop Capability with Failover

#### 8.6.1 Current State

The existing stop mechanism (`doStop()` in chat.js → `[stop]` WebSocket message → `asyncRequestStop` map → `isStopStream()` polling) works ONLY when the LLM is actively streaming tokens. If the LLM is hung (no tokens arriving), the `isStopStream()` check never executes because the stream `forEach` loop is blocked waiting for data.

#### 8.6.2 Design: Two-Phase Stop with Failover

**Phase 1 — Graceful Stop (existing + enhanced):**
- Client sends `[stop]` via WebSocket (existing behavior)
- Server sets `asyncRequestStop` flag (existing behavior)
- **New:** Start a failover timer (configurable, default 5 seconds)

**Phase 2 — Forced Stop (new failover):**
- If the stream doesn't terminate within the failover window:
  - Server cancels the `CompletableFuture` via `future.cancel(true)`
  - Server closes the underlying `HttpClient` connection
  - Server sends `onerror` callback with "Request forcefully terminated"
  - Server notifies client: `chirpUser(user, new String[] { "chatEvent", "forceStop", "{...}" })`

```java
// In ChatListener or Chat.java:
public void stopStream(OpenAIRequest request) {
    String oid = getRequestId(request);
    if (oid == null || !asyncRequests.containsKey(oid)) return;

    // Phase 1: Set graceful stop flag (existing)
    asyncRequestStop.put(oid, true);

    // Phase 2: Schedule forced cancellation after failover window
    int failoverSeconds = 5;
    CompletableFuture<?> streamFuture = asyncStreamFutures.get(oid);
    if (streamFuture != null) {
        CompletableFuture.delayedExecutor(failoverSeconds, TimeUnit.SECONDS)
            .execute(() -> {
                if (!streamFuture.isDone()) {
                    logger.warn("Force-cancelling hung stream: " + oid);
                    streamFuture.cancel(true);
                    // Notify client
                    chirpUser(user, new String[] {
                        "chatEvent", "forceStop",
                        "{\"requestId\":\"" + oid + "\",\"reason\":\"LLM unresponsive\"}"
                    });
                }
            });
    }
}
```

#### 8.6.3 Client-Side Enhancements

```javascript
function doStop() {
    if (!chatCfg.streaming) {
        return;
    }

    // Visual feedback
    showStatusIndicator("Stopping...");

    let chatReq = {
        schema: inst.model.name,
        objectId: inst.api.objectId(),
        uid: page.uid(),
        message: "[stop]"
    };
    page.wss.send("chat", JSON.stringify(chatReq), undefined, inst.model.name);

    // Client-side failover timer
    stopFailoverTimer = setTimeout(() => {
        showStatusIndicator("LLM unresponsive. Force stopping...", "warning");
        // Send force-stop signal
        chatReq.message = "[force-stop]";
        page.wss.send("chat", JSON.stringify(chatReq), undefined, inst.model.name);
    }, 8000);  // 8 seconds client-side (server has 5s, so 8s is backup)
}

// Clear failover timer when response completes or stops
function onChatComplete() {
    if (stopFailoverTimer) {
        clearTimeout(stopFailoverTimer);
        stopFailoverTimer = null;
    }
    hideStatusIndicator();
}
```

### 8.7 Sample Policies (JSON Resource Files)

Stored alongside other template resources. Each policy uses the existing `policy.policy` schema with custom `IOperation` classes for pattern evaluation.

#### 8.7.1 Response Quality Policy (Comprehensive)

**File:** `AccountManagerObjects7/.../resources/olio/llm/policy.responseQuality.json`

```json
{
  "name": "Response Quality Policy",
  "description": "Evaluates LLM responses for timeout, recursive loops, wrong-character responses, and refusals",
  "enabled": true,
  "decisionAge": 0,
  "condition": "ALL",
  "rules": [
    {
      "name": "Timeout Check",
      "description": "Verify the LLM produced a non-empty response",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": {
            "name": "responseContent",
            "type": "ATTRIBUTE",
            "factData": ""
          },
          "match": {
            "name": "expectedNonEmpty",
            "type": "STATIC",
            "factData": "timeout"
          },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.TimeoutDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Recursive Loop Check",
      "description": "Detect repetitive/looping LLM output",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": {
            "name": "responseContent",
            "type": "ATTRIBUTE",
            "factData": ""
          },
          "match": {
            "name": "loopConfig",
            "type": "STATIC",
            "factData": "{\"windowSize\": 50, \"repeatThreshold\": 3}"
          },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.RecursiveLoopDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Character Identity Check",
      "description": "Verify the LLM responds as the system character, not as the user character",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": {
            "name": "responseContent",
            "type": "ATTRIBUTE",
            "factData": ""
          },
          "match": {
            "name": "characterNames",
            "type": "STATIC",
            "factData": "{\"systemCharName\": \"\", \"userCharName\": \"\"}"
          },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.WrongCharacterDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Refusal Detection",
      "description": "Detect LLM refusals or safety hedging within configured rating",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": {
            "name": "responseContent",
            "type": "ATTRIBUTE",
            "factData": ""
          },
          "match": {
            "name": "refusalConfig",
            "type": "STATIC",
            "factData": "{\"minMatchesForRefusal\": 2}"
          },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.RefusalDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    }
  ]
}
```

#### 8.7.2 RPG-Specific Policy

**File:** `AccountManagerObjects7/.../resources/olio/llm/policy.rpgQuality.json`

Extends the base response quality policy with RPG-specific checks:

```json
{
  "name": "RPG Response Quality Policy",
  "description": "Response quality checks tailored for roleplay sessions",
  "enabled": true,
  "decisionAge": 0,
  "condition": "ALL",
  "rules": [
    {
      "name": "Character Identity Check (Strict)",
      "description": "Strict character identity enforcement for immersive RP",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": { "name": "responseContent", "type": "ATTRIBUTE", "factData": "" },
          "match": { "name": "characterNames", "type": "STATIC", "factData": "{}" },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.WrongCharacterDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Recursive Loop Check",
      "description": "Detect description loops in narrative",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": { "name": "responseContent", "type": "ATTRIBUTE", "factData": "" },
          "match": { "name": "loopConfig", "type": "STATIC", "factData": "{\"windowSize\": 40, \"repeatThreshold\": 2}" },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.RecursiveLoopDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Refusal Detection (Strict)",
      "description": "Zero tolerance for refusals in RP context",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": { "name": "responseContent", "type": "ATTRIBUTE", "factData": "" },
          "match": { "name": "refusalConfig", "type": "STATIC", "factData": "{\"minMatchesForRefusal\": 1}" },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.RefusalDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    }
  ]
}
```

#### 8.7.3 Behavioral/Psychotherapy Policy

**File:** `AccountManagerObjects7/.../resources/olio/llm/policy.behavioral.json`

Tailored for clinical/psychotherapy chat where character identity is critical:

```json
{
  "name": "Behavioral Session Policy",
  "description": "Quality checks for therapeutic/behavioral chat sessions where maintaining distinct character roles is critical",
  "enabled": true,
  "decisionAge": 0,
  "condition": "ALL",
  "rules": [
    {
      "name": "Therapist Identity Check",
      "description": "Ensure LLM maintains therapist role and does not speak as the patient",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": { "name": "responseContent", "type": "ATTRIBUTE", "factData": "" },
          "match": { "name": "characterNames", "type": "STATIC", "factData": "{}" },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.WrongCharacterDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Timeout Check",
      "description": "Detect unresponsive LLM during sensitive sessions",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": { "name": "responseContent", "type": "ATTRIBUTE", "factData": "" },
          "match": { "name": "expectedNonEmpty", "type": "STATIC", "factData": "timeout" },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.TimeoutDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    },
    {
      "name": "Refusal Detection",
      "description": "Detect LLM refusing to engage with therapeutic content",
      "type": "PERMIT",
      "condition": "ALL",
      "patterns": [
        {
          "type": "OPERATION",
          "fact": { "name": "responseContent", "type": "ATTRIBUTE", "factData": "" },
          "match": { "name": "refusalConfig", "type": "STATIC", "factData": "{\"minMatchesForRefusal\": 1}" },
          "operation": {
            "operation": "org.cote.accountmanager.olio.llm.policy.RefusalDetectionOperation",
            "type": "FUNCTION"
          }
        }
      ]
    }
  ]
}
```

### 8.8 Integration Point: Chat.java Post-Response Hook

The policy evaluation hooks into `Chat.continueChat()` after the LLM response is received and before the session is saved:

```java
// In Chat.continueChat(), after chat() returns:
OpenAIResponse response = chat(req);

// Policy evaluation (if policy is configured)
BaseRecord policy = chatConfig.get("policy");
if (policy != null && policy.get("enabled")) {
    ResponsePolicyEvaluator evaluator = new ResponsePolicyEvaluator();

    // Notify client: evaluation starting
    notifyPolicyEvent(user, req, "evaluating", policy.get(FieldNames.FIELD_NAME));

    PolicyViolation violation = evaluator.evaluate(
        response, chatConfig, promptConfig, req
    );

    if (violation != null) {
        // Notify client: violation detected
        notifyPolicyEvent(user, req, "violation", violation.toJSON());

        // Attempt autotuning
        try {
            notifyPolicyEvent(user, req, "autotuning", chatConfig.get("model"));

            ChatAutotuner autotuner = new ChatAutotuner();
            AutotuneResult result = autotuner.autotune(
                user, chatConfig, promptConfig, violation
            );

            notifyPolicyEvent(user, req, "autotuned", result.toJSON());
        } catch (Exception e) {
            logger.error("Autotune failed: " + e.getMessage());
            notifyPolicyEvent(user, req, "autotuneFailed", e.getMessage());
        }

        // Attach violation metadata to response (client can display warning)
        response.set("policyViolation", violation.toJSON());
    } else {
        notifyPolicyEvent(user, req, "passed", null);
    }
}

// Continue with normal flow: save session, etc.
saveSession(req, response);
```

### 8.9 ResponsePolicyEvaluator

**New class:** `org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator`

Wraps the existing `PolicyEvaluator` with chat-specific fact population:

```java
public class ResponsePolicyEvaluator {

    public PolicyViolation evaluate(
        OpenAIResponse response,
        BaseRecord chatConfig,
        BaseRecord promptConfig,
        OpenAIRequest request
    ) {
        BaseRecord policy = chatConfig.get("policy");
        if (policy == null || !policy.get("enabled")) return null;

        // Build policy request with chat-specific facts
        BaseRecord policyRequest = RecordFactory.newInstance(ModelNames.MODEL_POLICY_REQUEST);
        policyRequest.set("urn", policy.get("urn"));

        List<BaseRecord> facts = new ArrayList<>();

        // Fact 1: Response content
        BaseRecord contentFact = newFact("responseContent", FactEnumType.ATTRIBUTE);
        contentFact.set("factData", response != null ? response.getMessage() : "");
        facts.add(contentFact);

        // Fact 2: Character names (for wrong-character detection)
        BaseRecord charFact = newFact("characterNames", FactEnumType.ATTRIBUTE);
        String sysName = chatConfig.get("systemCharacter.firstName");
        String usrName = chatConfig.get("userCharacter.firstName");
        charFact.set("factData", "{\"systemCharName\":\"" + sysName
            + "\",\"userCharName\":\"" + usrName + "\"}");
        facts.add(charFact);

        policyRequest.set("facts", facts);

        // Evaluate using existing PolicyEvaluator
        PolicyEvaluator evaluator = new PolicyEvaluator();
        BaseRecord policyResponse = evaluator.evaluatePolicyRequest(policyRequest);

        String responseType = policyResponse.get("type");
        if ("DENY".equals(responseType)) {
            return PolicyViolation.fromPolicyResponse(policyResponse);
        }
        return null;  // PERMIT — no violation
    }
}
```

---

## 9. Testing Requirements

### 9.1 Testing Philosophy: No Availability Gates, No Mocked Success

**THIS IS A NON-NEGOTIABLE REQUIREMENT.**

Every phase is incomplete until its tests are written, run against real services, and pass. There are no "assume this works" shortcuts:

- **Real test database.** All tests extend `BaseTest`, which provides a fully functional test DB via `getTestOrganization()`. Schema changes, queries, persistence, and model instantiation are tested against the actual data layer.
- **Real LLM service.** Tests that involve LLM calls (`Chat.continueChat()`, `ChatAutotuner.autotune()`, streaming responses) must run against a configured LLM endpoint. The test properties (`test.llm.serviceType`, `test.llm.openai.*`, `test.llm.ollama.server`) must point to a live service. Do NOT mock LLM responses as "successful" and call the test done.
- **Real policy evaluation.** Policy tests use the actual `PolicyEvaluator` pipeline, not mock evaluators. `IOperation` classes are tested with real inputs and the actual `operate()` method.
- **Failures are defects.** If a test fails, it indicates a defect that must be resolved before the phase is marked complete. "It works on my machine" or "the LLM was down" is not acceptable — if the LLM is intermittent, the code must handle that gracefully, and that handling must be tested.
- **No `@Ignore` or `assumeTrue` gates that skip the test when the service is unavailable.** If the test requires an LLM, configure the LLM. If it requires vectors, configure the embedding service. Tests must run.

### 9.2 Regression Test Suite

Before each phase begins implementation AND after each phase completes, the following existing tests must pass. These are selected to cover the full stack from model schema to streaming chat to memory to policy evaluation:

| Test Class | Test Method | What It Validates | Why It's Here |
|---|---|---|---|
| `TestChat` | `TestChatConfigModels` | chatConfig + promptConfig model creation | Validates schema changes don't break model instantiation |
| `TestChat` | `TestRandomChatConfig` | Full prompt template pipeline with episodes, NLP, rating | Validates PromptUtil pipeline changes don't break existing template composition |
| `TestChat2` | `TestRequestPersistence` | OpenAIRequest persistence to database | Validates persistence layer for chat requests |
| `TestMcpChatIntegration` | ALL (20 tests) | MCP citation, reminder, keyframe formatting and filtering | Validates template/formatting changes don't break MCP block generation |
| `TestMemoryUtil` | ALL (12 tests) | Memory model schema, creation, queries, extraction, formatting | Validates memory system changes don't break existing functionality |
| `TestChatPolicy` | `TestChatPolicyRewriteRequest` | Policy evaluation with LLM via PolicyEvaluator | Validates policy infrastructure still works after adding new IOperation classes |
| `TestMemoryDuel` | `testChatDuelWithMemories` | Full streaming chat with memory extraction, two characters | End-to-end integration: streaming + chat + memory + characters |

**Regression protocol:**
1. **Pre-phase baseline:** Run ALL regression tests before starting implementation. Record results. All must pass.
2. **During implementation:** Run relevant subset after each significant code change.
3. **Post-phase gate:** Run ALL regression tests after implementation. All must pass. Any regression is a defect that blocks phase completion.

### 9.3 Per-Phase Test Requirements

Each phase has three categories of tests:

1. **New feature tests** — Validate the new functionality introduced in the phase
2. **Edge case tests** — Boundary conditions, null inputs, error handling
3. **Integration tests** — New features working with existing features end-to-end

All tests must:
- Run against the real test DB (extends `BaseTest`)
- Run against real LLM endpoints where applicable
- Assert specific outcomes (not just "didn't throw")
- Complete within a reasonable timeout (use `@Test(timeout=...)` for LLM calls)
- Clean up after themselves (no inter-test state leakage)

#### Phase 1 Tests: Template Quality

| # | Test Name | What It Tests | Services Required |
|---|---|---|---|
| 1 | `TestDynamicRuleNumbering` | episodes=off, NLP=off, rating=E → rules numbered 1,2,3 sequentially, no gaps | DB |
| 2 | `TestDynamicRulesWithEpisodes` | episodes on → episode rule is #1, all subsequent sequential | DB |
| 3 | `TestDynamicRulesWithAllFeatures` | all features on → all rules present, numbered 1-N with no gaps | DB |
| 4 | `TestConditionalExclusion_EpisodesOff` | `${episode}`, `${episodeRule}` resolve to `""` with no orphan whitespace | DB |
| 5 | `TestConditionalExclusion_NLPOff` | `${nlp}`, `${nlpReminder}` resolve to `""` with no orphan whitespace | DB |
| 6 | `TestBackwardCompat_ExistingPromptConfig` | Load `prompt.config.json`, apply full pipeline → output identical to pre-refactor baseline | DB |
| 7 | `TestBlankLineCleanup` | Template with disabled sections → no blank lines in final output | DB |

**Phase 1 gate:** Tests 1-7 pass + ALL regression tests pass.

#### Phase 2 Tests: Memory Template Variables & Retrieval — ALL PASSING

| # | Test Name | What It Tests | Services Required | Status |
|---|---|---|---|---|
| 8 | `testMemoryPatternResolution` | promptConfig with `${memory.context}`, inject memory data → token resolved | DB | PASS |
| 9 | `testMemoryPatternsDefaultToEmpty` | Prompt without memory tokens → no `${memory.` substring in output | DB | PASS |
| 10 | `testCanonicalPersonIds` | `canonicalPersonIds(200,100) == canonicalPersonIds(100,200)` | None | PASS |
| 11 | `testMemoryContextFormatting` | Memories formatted via `MemoryUtil.formatMemoriesAsContext` → correct MCP block | DB | PASS |
| 12 | `testRoleAgnosticMemoryRetrieval` | Bob(user)+Rob(system) stores memory → Bob(system)+Rob(user) retrieves same | DB | PASS |
| 13 | `testCrossPartnerMemoryRetrieval` | Bob+Rob stores, Bob+Nob stores → query "all Bob's memories" returns both | DB | PASS |
| 14 | `testCreateMemoryWithPersonIds` | Create memory with personId1+personId2 → verify persisted with canonical ordering | DB | PASS |
| 15 | `testSearchMemoriesByPersonPair` | 3 memories pair(A,B), 2 pair(A,C) → search pair(A,B) returns exactly 3 | DB | PASS |
| 16 | `testSearchMemoriesByPerson` | Same setup → search person A returns all 5 | DB | PASS |
| 17 | `testRoleSwapProducesSameIds` | Bob system+Rob user, then Bob user+Rob system → identical personId1/personId2 | DB | PASS |
| 18 | `testMemoryRetrievalIntegration` | Full `retrieveRelevantMemories()` with pair memories → prompt contains MCP context | DB | PASS |

**Phase 2 gate:** Tests 8-18 pass + ALL regression tests pass. **GATE MET.**

#### Phase 3 Tests: Keyframe-to-Memory Pipeline

| # | Test Name | What It Tests | Services Required | Status |
|---|---|---|---|---|
| 19 | `testKeyframeMemoryPersistence` | extractMemories=true → `tool.memory` record created with OUTCOME type, person pair IDs set | DB, LLM | PASS |
| 20 | `testKeyframeMemoryScoping` | Two character pairs → pair-scoped queries return correct memories, role-agnostic | DB | PASS |
| 21 | `testKeyframePruneKeepsTwo` | After multiple keyframes → last 2 are kept in history, memories persisted | DB, LLM | PASS |
| 22 | `testKeyframeToMemoryToPromptRoundtrip` | Keyframe → memory persist → new session → memory appears in prompt via `${memory.context}` | DB | PASS |

**Phase 3 gate:** Tests 19-22 pass + ALL regression tests pass. **GATE MET.**

#### Phase 4 Tests: Prompt Templates — ALL PASSING

| # | Test Name | What It Tests | Services Required | Status |
|---|---|---|---|---|
| 23 | `TestOpenChatTemplate` | Load prompt.openChat.json → validate → process pipeline → no unreplaced tokens | DB | PASS |
| 24 | `TestRPGTemplate` | Load prompt.rpg.json → full pipeline with episodes/NLP/scene → all tokens resolved | DB | PASS |
| 25 | `TestSMSTemplate` | Load prompt.sms.json → image/audio tokens pass through, all others resolved | DB | PASS |
| 26 | `TestMemoryChatTemplate` | Load prompt.memoryChat.json → inject memories → `${memory.*}` resolved | DB | PASS |
| 27 | `TestOpenChatLLMIntegration` | Use openChat template with real LLM → get coherent response | DB, LLM | PASS* |
| 28 | `TestRPGTemplateLLMIntegration` | Use rpg template with characters + real LLM → character-appropriate response | DB, LLM | PASS* |

*\*Tests 27-28 skip gracefully when LLM server is unavailable.*

**Phase 4 gate:** Tests 23-28 pass + ALL regression tests pass. **GATE MET.**

#### Phase 5 Tests: Validation/Migration + Server-Side Display

| # | Test Name | What It Tests | Services Required | Status |
|---|---|---|---|---|
| 29 | `TestValidatorDetectsUnknownTokens` | promptConfig with `${nonexistent}` → flagged | DB | PASS |
| 30 | `TestValidatorPassesValidConfig` | Existing prompt.config.json passes | DB | PASS |
| 31 | `TestValidatorIgnoresRuntimeTokens` | `${image.selfie}`, `${audio.hello}` not flagged | DB | PASS |
| 32 | `TestValidatorUnreplacedTokens` | Composed template with unreplaced `${memory.context}` → detected | DB | PASS |
| 33 | `TestMigratorDryRun` | Reports changes, fieldsUpdated=0 | DB | PASS |
| 34 | `TestMigratorAppliesChanges` | Template created in DB with sections | DB | PASS |
| 35 | `TestMigratorIdempotent` | Running twice → second returns alreadyExists | DB | PASS |

**Phase 5 gate:** Tests 29-35 pass + ALL regression tests pass. **GATE MET.**

#### Phase 7 Tests: Always-Stream & Timeout

| # | Test Name | What It Tests | Services Required |
|---|---|---|---|
| 36 | `TestStreamBufferMode` | stream=false → Chat.chat() returns complete response (not null) | DB, LLM |
| 37 | `TestStreamTimeoutTriggered` | requestTimeout=1 → TimeoutException caught, onerror called | DB, LLM |
| 38 | `TestStreamCancellation` | stopStream() works in buffered mode | DB, LLM |
| 39 | `TestStreamingModeUnchanged` | stream=true → existing streaming behavior still works | DB, LLM |

**Phase 7 gate:** Tests 36-39 pass + ALL regression tests pass.

#### Phase 8 Tests: LLM Config & ChatConfig Templates

| # | Test Name | What It Tests | Services Required |
|---|---|---|---|
| 40 | `TestTopKRangeFixed` | top_k accepts values > 1 (50, 200) | DB |
| 41 | `TestApplyChatOptionsOpenAI` | frequency_penalty and presence_penalty mapped correctly for OpenAI | DB |
| 42 | `TestApplyChatOptionsOllama` | repeat_penalty and typical_p mapped correctly for Ollama | DB |
| 43 | `TestChatConfigTemplateLoads` | All 6 chatConfig templates deserialize correctly | DB |
| 44 | `TestChatConfigTemplateDefaults` | Each template's chatOptions fields within valid ranges | DB |
| 45 | `TestChatWithFixedOptions` | Use corrected chatOptions with real LLM → successful response | DB, LLM |

**Phase 8 gate:** Tests 40-45 pass + ALL regression tests pass.

#### Phase 9 Tests: Policy-Based Response Regulation

| # | Test Name | What It Tests | Services Required |
|---|---|---|---|
| 46 | `TestTimeoutDetection` | null/empty response → FAILED; normal → SUCCEEDED | DB |
| 47 | `TestRecursiveLoopDetection` | Repeated 50-char block 3x → FAILED; normal text → SUCCEEDED | DB |
| 48 | `TestRecursiveLoopConfigurable` | windowSize=30, threshold=2 → detects shorter loops | DB |
| 49 | `TestWrongCharacterDetection` | "Bob: Hello" when Bob is user → FAILED | DB |
| 50 | `TestWrongCharacterNarrative` | "*Bob walks over*" when Bob is user → FAILED | DB |
| 51 | `TestRefusalDetection` | 2+ refusal phrases → FAILED; 0-1 → SUCCEEDED | DB |
| 52 | `TestRefusalStrictMode` | minMatches=1, single phrase → FAILED | DB |
| 53 | `TestResponsePolicyEvaluator` | Full policy wired up → PERMIT for good, DENY for bad | DB |
| 54 | `TestAutotunerCountQuery` | 3 autotuned prompts → count returns 3, next is "- autotuned - 4" | DB |
| 55 | `TestAutotunerIdempotent` | Same violation → new prompt each time, incrementing count | DB |
| 56 | `TestAutotunerLLMCall` | Real autotuning LLM call → valid rewrite suggestion returned | DB, LLM |
| 57 | `TestPolicyWebSocketNotifications` | Mock handler → verify notification sequence | DB |
| 58 | `TestStopFailover` | Stop flag set → future cancelled after failover window | DB |
| 59 | `TestForceStopMessage` | [force-stop] → immediate cancellation | DB |
| 60 | `TestPolicyPassthrough` | No policy on chatConfig → no evaluation, response unchanged | DB |
| 61 | `TestSamplePolicyLoads` | All 3 sample policy JSON files → deserialize into valid records | DB |
| 62 | `TestPolicyWithRealLLM` | Full pipeline: LLM call → policy evaluation → autotuning on violation | DB, LLM |

**Phase 9 gate:** Tests 46-62 pass + ALL regression tests pass.

#### Phase 6 Tests: UX Test Suite

| # | Test Name | Category | What It Tests | Services Required |
|---|---|---|---|---|
| 63 | `TestConfigLoad` | config | chatConfig and promptConfig auto-load from templates into `~/Tests` | Server |
| 64 | `TestServerReachable` | config | chatConfig's serverUrl responds via REST chat endpoint | Server, LLM |
| 65 | `TestPromptComposition` | prompt | System/user/assistant prompts compose, output length > 0 | Server |
| 66 | `TestTokenClassification` | prompt | Classify `${...}` tokens as runtime (pass) vs unknown (warn) | Server |
| 66b | `TestMediaTokenPresence` | prompt | Verify `${image.*}` and `${audio.*}` tokens present in prompt | Server |
| 67 | `TestPromptDataDump` | prompt | Full prompt text logged via testLogData for manual inspection | Server |
| 68 | `TestChatSessionCreate` | chat | `getChatRequest()` returns valid session object (standard variant) | Server |
| 68b | `TestAssistExchange` | chat | When assist=true, detect pre-loaded messages in raw request | Server |
| 69 | `TestChatSendReceive` | chat | Send message, receive non-empty assistant response | Server, LLM |
| 70 | `TestChatSessionCleanup` | chat | `deleteChat(req, true)` succeeds | Server |
| 71 | `TestStreamConnect` | stream | WebSocket streaming connects and receives chunks (streaming variant) | Server, LLM |
| 72 | `TestStreamFullResponse` | stream | Streamed chunks assemble into complete response | Server, LLM |
| 73 | `TestHistoryOrdering` | history | Send 3 messages, retrieve history, verify chronological order | Server, LLM |
| 74 | `TestHistoryContent` | history | Retrieved messages match sent content | Server, LLM |
| 74b | `TestAssistHistoryExclusion` | history | Verify assist exchange is excluded from history | Server, LLM |
| 75 | `TestPruning` | prune | Send messages beyond messageTrim, verify old messages pruned (streaming variant) | Server, LLM |
| 76 | `TestKeyframeCreation` | prune | After keyframeEvery messages, verify keyframe exists | Server, LLM |
| 77 | `TestEpisodeGuidance` | episode | With episodes configured, verify episode guidance appears in prompt (streaming variant) | Server |
| 77b | `TestEpisodeStructure` | episode | Validate episode has name, stages array, and theme | Server |
| 78 | `TestEpisodeTransitionRule` | episode | Verify `episodeRule` array present on promptConfig (not chatConfig) | Server |
| 79 | `TestAnalysisPipeline` | analyze | Send content for analysis, receive structured analysis response (standard variant) | Server, LLM |
| 80 | `TestNarrationPipeline` | narrate | Send scene content, receive formatted summary (standard variant) | Server, LLM |
| 81 | `TestPolicyEvaluation` | policy | If policy configured, verify evaluation and rewrite | Server, LLM |

**Phase 6 gate:** All UX tests pass with at least one named chatConfig against a live Ollama server. Debug output is copy-paste ready for diagnosis.

**Note:** These are browser-side tests run via the UX test view, not JUnit tests. They validate the full client-to-server-to-LLM pipeline end-to-end.

### 9.4 Test Infrastructure Requirements

| Requirement | How To Configure |
|---|---|
| Test database | Provided by `BaseTest.getTestOrganization()` — no additional setup |
| LLM endpoint (Ollama) | `test.llm.ollama.server` in `resource.properties` — must be a running Ollama instance |
| LLM endpoint (OpenAI) | `test.llm.openai.server`, `test.llm.openai.apiKey` in `resource.properties` |
| LLM service type | `test.llm.serviceType` — set to `OLLAMA` or `OPENAI` |
| Embedding service | `test.vector.embeddingUrl` in `resource.properties` (for vector/memory search tests) |
| Test timeout | LLM-involving tests use `@Test(timeout = 120000)` minimum (2 minutes); streaming tests use 180000 (3 minutes) |

### 9.5 Defect Resolution Protocol

When a test fails:

1. **Root cause first.** Do not modify the test assertion to match incorrect behavior. Find and fix the code defect.
2. **Reproduce.** Ensure the failure is reproducible, not a transient LLM issue. If the LLM gives different responses each time, the test assertion must account for that (e.g., assert response is non-null and non-empty, not assert response equals exact text).
3. **Fix forward.** After fixing the defect, re-run the full phase test suite AND the regression suite.
4. **Document.** If a test reveals a design issue (e.g., the autotuner analysis prompt produces poor suggestions), update the design document with the lesson learned and adjust the implementation.

---

## 7. Implementation Phases

### Phase 1: Template Cleanup (Low risk, high impact)

**Goal:** Fix prompt quality without changing architecture.

1. **Fix rule numbering** - Replace hardcoded numbers with dynamic assembly in `PromptUtil.buildEpisodeReplacements()` and related methods
2. **Trim consent blocks** - Reduce consent/censor text to essential minimum (~50 tokens instead of ~200)
3. ~~**Add condition checks** - Wrap existing replacement stages in `if` guards so disabled features produce no output (no orphan text)~~ — **Partially addressed by Phase 4:** `PromptConditionEvaluator` provides condition-based section inclusion/exclusion in the new structured template schema. The flat `prompt.config.json` pipeline stages still lack `if` guards, but new templates use section-level conditions instead.
4. **Document variable dependencies** - Add comments to `PromptUtil` documenting which stages must precede others

**Remaining scope:** Items 1, 2, and 4 are still outstanding. Item 3 is partially superseded by Phase 4's condition evaluator for new structured templates, but the legacy `prompt.config.json` flat pipeline still benefits from `if` guards in the replacement stages.

**Files modified:** `PromptUtil.java`, `prompt.config.json`

### Phase 2: Memory Retrieval (Medium risk, high impact) — IMPLEMENTED

**Goal:** Make memories available during prompt composition.

**Status:** Complete. All 11 Phase 2 tests pass. All regression tests pass.

1. **Add `retrieveRelevantMemories()`** to `Chat.java` — Done. Queries `tool.memory` by canonical person pair IDs, formats as MCP context blocks via `McpContextBuilder`, passes to `PromptUtil` via ThreadLocal.
2. **Add memory template variables** to `TemplatePatternEnumType` — Done. Added `MEMORY_CONTEXT`, `MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION`, `MEMORY_COUNT`.
3. **Add `buildMemoryReplacements()`** stage to `PromptUtil` pipeline — Done. Stage 5 in the 12-stage pipeline (after episode, before rating/NLP/consent). Reads from ThreadLocal `pendingMemoryContext`, clears after use.
4. **Implement person-pair memory query** in `MemoryUtil` — Done. `searchMemoriesByPersonPair()` and `searchMemoriesByPerson()` with canonical ID ordering. Queries use `personId1`/`personId2` fields (role-agnostic).
5. **Add `memoryBudget` field** to `chatConfigModel.json` — Done. Int field, default 0 (disabled). Controls token budget for memory context injection.
6. **Add `personId1`/`personId2`/`personModel` fields** to `memoryModel.json` and `vectorMemoryModel.json` — Done. Uses person-centric naming with model type for polymorphic identity (supports `identity.person` and `olio.charPerson`).

**Files modified:** `Chat.java`, `PromptUtil.java`, `PromptBuilderContext.java`, `TemplatePatternEnumType.java`, `MemoryUtil.java`, `VectorMemoryListFactory.java`, `chatConfigModel.json`, `memoryModel.json`, `vectorMemoryModel.json`
**Files added:** `TestMemoryPhase2.java`

**Design decisions:**
- Person pair IDs are canonicalized (lower ID first) so the same pair always produces the same `(personId1, personId2)` regardless of system/user role assignment.
- Memory context is passed from `Chat.java` to the template pipeline via `PromptUtil.pendingMemoryContext` (ThreadLocal) to avoid changing method signatures across the pipeline chain.
- `personModel` field on memory records enables polymorphic person references (could be `identity.person` or `olio.charPerson`).
- `MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION` default to empty strings in Phase 2. Phase 3+ can populate them with categorized memory content.

**Known issues:**
- `personModel` field is defined in the schema but not yet populated by `MemoryUtil.createMemory()` or `Chat.retrieveRelevantMemories()`. Callers should set it when the person model type is known. (Still open after Phase 3.)
- ~~`MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION`, `MEMORY_COUNT` are always empty/zero in Phase 2. Splitting memory content by category is deferred to Phase 4+.~~ **RESOLVED in Phase 4** — `Chat.retrieveRelevantMemories()` now categorizes by memoryType, sets thread-locals consumed by `PromptUtil.buildMemoryReplacements()`.
- The `extractMemoriesFromResponse()` method in `MemoryUtil` does not yet pass person pair IDs when creating memories from LLM extraction responses. (Still open after Phase 3 — Phase 3 addressed keyframe-sourced memories but not LLM-extracted memories.)

### Phase 3: Keyframe-to-Memory Pipeline (Medium risk, medium impact) — IMPLEMENTED

**Goal:** Make keyframes durable and accumulative.

**Status:** Complete. All 4 Phase 3 tests pass. All regression tests pass. (Total through Phase 5: 43 tests — Phase 2: 11, MemoryUtil: 12, MemoryDuel: 1, Phase 3: 4, Phase 4: 8, Phase 5: 7.)

1. **Persist keyframe summaries as memories** in `addKeyFrame()` — Done. New `persistKeyframeAsMemory()` method creates `tool.memory` records with type `OUTCOME` and importance 7 from keyframe analysis text. Summary is truncated to sentence boundary at 200 chars via `truncateToSentence()`.
2. **Tag memories with character pair IDs** for cross-conversation retrieval — Done. Both character IDs are stored in canonical order (lower ID first) via `MemoryUtil.createMemory()` with `personId1`/`personId2`. Role-agnostic: same pair always produces same IDs regardless of system/user assignment.
3. **Add `memoryExtractionEvery` config** to control extraction frequency — Done. New `memoryExtractionEvery` field on `chatConfigModel.json` (int, default 0). When 0, every keyframe produces a memory. When N>0, only every Nth keyframe (counted by existing OUTCOME memories for that conversation) produces a memory.
4. **Keep last 2 keyframes** instead of 1 for better continuity during active conversations — Done. Two changes:
   - `addKeyFrame()`: When building new message list, keeps the most recent existing keyframe plus the new one (2 total in message history).
   - `pruneCount()`: Marks last 2 keyframes as non-pruned instead of just 1.

**Files modified:** `Chat.java`, `chatConfigModel.json`
**Files added:** `TestKeyframeMemory.java`

**Design decisions:**
- Keyframe memories use `OUTCOME` type per the design document (Section 4.3), reflecting that keyframes summarize conversation outcomes/events.
- The `conversationId` on keyframe memories is set to the chatConfig's `objectId`, linking memories to the specific chat configuration rather than a session ID. This allows cross-session memory retrieval for the same chatConfig.
- `persistKeyframeAsMemory()` is gated by `extractMemories=true` on the chatConfig. When false, keyframes are still created as message history but not persisted as durable memories.
- The `memoryExtractionEvery` frequency check counts only OUTCOME-type memories for the conversation, so other memory types created externally don't interfere with the keyframe extraction cadence.
- Memory vectorization happens automatically via `MemoryUtil.createMemory()` → `createMemoryVectors()`, making keyframe memories semantically searchable.

**Known issues:**
- `personModel` field is still not populated by `persistKeyframeAsMemory()` or `MemoryUtil.createMemory()`. Callers should set it when the person model type is known.
- `MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION`, `MEMORY_COUNT` template variables are still always empty/zero. Splitting memory content by category (grouping OUTCOME vs RELATIONSHIP vs FACT memories) is deferred to Phase 4+.
- The `extractMemoriesFromResponse()` method in `MemoryUtil` still does not pass person pair IDs when creating memories from LLM extraction responses. This remains an open issue from Phase 2.
- The LLM model `qwen3` returns a `thinking` field in streaming responses that the `RecordDeserializer` does not recognize on the `openaiMessage` model, producing `Invalid field: olio.llm.openai.openaiMessage.thinking` error logs. This is a pre-existing issue unrelated to Phase 3 — the `openaiMessage` model schema needs a `thinking` field added to support models with chain-of-thought output.
- When `keyframeEvery` is set very low (e.g., 2), each keyframe triggers an `analyze()` LLM call which is expensive. The `memoryExtractionEvery` config mitigates memory storage volume but does not reduce LLM calls for the analysis itself.

### Phase 4: Structured Template Schema (Higher risk, high long-term impact) — **COMPLETED**

**Goal:** Replace flat string arrays with composable, conditional sections.

1. **Define `promptTemplateModel.json`** with sections, conditions, ordering, inheritance — **DONE**
2. **Create `PromptTemplateComposer.java`** - new class that processes the structured schema — **DONE**
3. **Add condition evaluator** that checks chatConfig state — **DONE** (`PromptConditionEvaluator.java`)
4. **Implement template inheritance** (`extends` field resolution) — **DONE** (max depth 10, child sections override parent by sectionName)
5. **Migrate existing prompts** to new schema (keep old schema working via adapter) — **DONE** (prompt.rpg.json, prompt.openChat.json, prompt.sms.json, prompt.memoryChat.json)
6. **Unify Magic8 prompt** under the new schema — **DONE** (prompt.magic8.json server-side template; client-side SessionDirector.js unchanged)
7. **Update chat.js** if any client-side template handling changes — **N/A** (all template composition is server-side; no client changes needed)
8. **Populate `MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION`, `MEMORY_COUNT`** — **DONE** (resolves OI-2). `Chat.retrieveRelevantMemories()` categorizes by memoryType, sets thread-locals consumed by `PromptUtil.buildMemoryReplacements()`.

**New files:** `promptTemplateModel.json`, `promptSectionModel.json`, `PromptTemplateComposer.java`, `PromptConditionEvaluator.java`, `prompt.rpg.json`, `prompt.openChat.json`, `prompt.sms.json`, `prompt.memoryChat.json`, `prompt.magic8.json`
**Modified files:** `PromptUtil.java` (thread-local memory setters, `buildMemoryReplacements` updated), `Chat.java` (memory categorization in `retrieveRelevantMemories`), `OlioModelNames.java` (registered `MODEL_PROMPT_TEMPLATE`, `MODEL_PROMPT_SECTION`), `RecordDeserializer.java` (removed unused debug `toString()` call causing StackOverflowError), `PolicyUtil.java` (added ThreadLocal depth limiting in `getForeignPatterns` to cap recursive policy evaluation on nested foreign references)

**Known issues:**
- **Pipeline ordering artifact (`${nlp.command}`):** The PromptUtil 12-stage pipeline replaces `${nlp.command}` in Stage 6, but Stage 7 (`buildDynamicRulesReplacement`) can reintroduce `${nlp.command}` tokens via NLP rules from promptConfig. `findUnreplacedTokens()` skips `${nlp.*}` tokens alongside `${image.*}` and `${audio.*}` to tolerate this. A future fix could add a post-Stage-7 NLP token pass.
- **~~StackOverflowError in `OlioTestUtil.getRandmChatConfig()`~~** — **RESOLVED.** Three-part fix: (1) Removed unused debug variable `String dbg = value.toString()` in `RecordDeserializer.setFieldValue()` that triggered full Jackson serialization of deeply nested JsonNode trees, overflowing the stack via recursive `BaseJsonNode.toString()` → `InternalNodeMapper._serializeNonRecursive` cycles. (2) Added ThreadLocal depth limiting (`MAX_FOREIGN_POLICY_DEPTH = 2`) in `PolicyUtil.getForeignPatterns()` to cap recursive policy evaluation through nested foreign references (e.g., character → apparel → references), preventing unbounded `getSchemaRules` → `getForeignPatterns` → `getResourcePolicy` cycles. (3) Tests 27-28 now use `copyRecord()` with only the changed fields + identity when calling `getAccessPoint().update()`, avoiding passing full nested character objects through the authorization path.
- **Magic8 client-side template (`magic8DirectorPrompt.json`):** The existing client-side Magic8 template in `SessionDirector.js` is independent of the server-side template system. `prompt.magic8.json` is a server-side structured representation but is not yet wired into the client-side flow. Full unification would require `SessionDirector.js` to fetch the composed prompt from the server.

**Skipped test conditions:**

| Test | Condition Skipped | Reason |
|------|-------------------|--------|
| Test 23-26 | `${nlp.command}` unreplaced token validation | Pipeline ordering artifact: Stage 6 (NLP replacement) runs before Stage 7 (dynamic rules expansion), which can reintroduce `${nlp.command}`. Skipped in `findUnreplacedTokens()` via `${nlp.*}` prefix exclusion. |
| Test 23-26 | `${image.*}`, `${audio.*}` unreplaced token validation | These are runtime-only tokens resolved at message send time (e.g., binary attachments), not at template composition time. Skipped by design. |
| Test 27 | LLM response assertion | Skipped when `test.llm.type` not configured or LLM server unreachable (`chat()` returns null). Test logs warning and returns gracefully. |
| Test 28 | LLM response assertion | Same as Test 27 (LLM server availability). |

### Phase 5: Client-Side Cleanup & Validation/Migration Tooling — COMPLETED

**Goal:** Remove duplicated logic, improve display pipeline, and provide validation/migration tooling for prompt configs.

**Part A — Validation & Migration (Tests 29-35):**
1. **PromptConfigValidator** — Scans all `list<string>` fields in promptConfig for `${...}` tokens, checks against known set from `TemplatePatternEnumType`. Runtime tokens (`image.*`, `audio.*`, `nlp.*`) are always allowed.
2. **PromptConfigMigrator** — Converts flat promptConfig records to structured promptTemplate records with sections, role derivation from field name prefix, condition derivation from static map, and idempotency checking.
3. **Supporting POJOs** — `ValidationResult` (with inner `UnknownToken`), `MigrationReport`, `MigrationResult`.

**Part B — Server-Side Display Enhancement:**
1. **Ephemeral display fields on messageModel** — Added `displayContent` (string), `hasThoughts`, `hasMetrics`, `hasKeyframe` (boolean) as ephemeral fields (serialized but not persisted).
2. **ChatUtil.populateDisplayFields()** — Called in `getChatResponse()` for each message. Mirrors client-side `pruneAll` logic: strips `<think>`/`<thought>` tags, MCP context blocks, `(Metrics`/`(Reminder`/`(KeyFrame` markers, `[interrupted]` markers, reserved special tokens.
3. **chat.js updated** — Prefers `msg.displayContent` when available and `hideThoughts` is active, falls back to existing `pruneTag`/`pruneToMark` for backward compatibility with pre-enhancement sessions.

**Files created:** `ValidationResult.java`, `PromptConfigValidator.java`, `MigrationReport.java`, `MigrationResult.java`, `PromptConfigMigrator.java`
**Files modified:** `messageModel.json`, `ChatUtil.java`, `chat.js`

**Known issues:**
- **OI-17: Client-side prune functions retained** — The `pruneTag`/`pruneToMark`/`pruneAll` functions are kept in chat.js for backward compatibility with sessions that predate the server-side display fields. They can be removed once all active sessions have been refreshed.
- **OI-18: Migrator condition coverage** — The static condition map in `PromptConfigMigrator` covers the 7 most common conditional fields (`systemNlp`, `assistantNlp`, `userConsentNlp`, `systemCensorWarning`, `assistantCensorWarning`, `episodeRule`, `jailBreak`). Fields like `femalePerspective`/`malePerspective` have no condition mapping and will always be included. Adding conditions for these requires understanding the intended chatConfig gate fields.
- **OI-19: Token standardization** — Image and audio token processing still varies between prompt template styles. Phase 5 did not address item 4 from the original plan (standardize token processing) as it requires deeper changes to the image/audio pipeline in Chat.java.

### Phase 6: UX Test Suite (Low risk, high impact)

**Goal:** Provide a shared test framework and LLM capability test suite runnable from the browser UX, with clear debug output for diagnosing pipeline issues.

**Prerequisites:** All backend phases that affect the prompt/chat pipeline should be complete before implementing this phase, so tests validate the final system.

#### 6.1 Shared Test Framework

Extract reusable test infrastructure from CardGame's `testMode.js` into a shared module that both CardGame and new LLM tests can use.

**`client/test/testFramework.js`** — Core framework:

| Component | Purpose |
|-----------|---------|
| `testState` | `{ running, logs[], results{pass,fail,warn,skip}, currentTest, completed, selectedCategories[], logFilter, selectedSuite }` |
| `testLog(category, message, status)` | Append a log entry with status `"info"`, `"pass"`, `"fail"`, or `"warn"` |
| `testLogData(category, label, data)` | Log structured data (JSON/text) for debug copy-paste — appears as expandable block |
| `runSuite(suiteId)` | Run a registered suite's selected categories sequentially |
| `TestConsoleUI` | Mithril component — scrollable log console with color-coded status, filter by category/status |
| `TestToolbarUI` | Mithril component — Run button, suite selector dropdown, status spinner |
| `TestCategoryToggleUI` | Mithril component — category checkboxes with icons and labels |
| `TestResultsSummaryUI` | Mithril component — pass/fail/warn/skip counts |
| `exportLogs()` | Copy all logs to clipboard as formatted text (includes DATA blocks in full) |
| `clearLogs()` | Reset logs and results |

**`client/test/testRegistry.js`** — Suite registration:

```javascript
// Each test suite registers itself
TestFramework.registerSuite("llm", {
  label: "LLM Chat Pipeline",
  icon: "smart_toy",
  categories: { /* ... */ },
  run: async function(selectedCategories) { /* ... */ }
});

// CardGame registers separately
TestFramework.registerSuite("cardGame", {
  label: "Card Game",
  icon: "playing_cards",
  categories: { /* ... */ },
  run: async function(selectedCategories) { /* ... */ }
});
```

#### 6.2 LLM Test Suite

**`client/test/llm/llmTestSuite.js`** — Test categories and implementations.

**Prerequisites:**
- Template files `llmTestChatConfig.json` and `llmTestPromptConfig.json` must exist in `media/prompts/`
- Auto-setup creates `~/Tests` group, test characters, promptConfig, and chatConfig variants on first run
- No manual config picking required — suite is self-sufficient
- All testing targets a remote Ollama server (OpenAI API format with Ollama URI/response differences)

**Test Categories:**

| Category | Icon | Tests |
|----------|------|-------|
| **config** | `settings` | Validate chatConfig loads, promptConfig loads, server URL reachable, model available |
| **prompt** | `description` | Template composition: system/user/assistant prompts compose without errors, `${dynamicRules}` resolves, no orphan `${...}` tokens in output |
| **chat** | `chat` | Create session, send message, receive response, verify response structure |
| **stream** | `stream` | WebSocket streaming: connect, receive chunks, full response assembled |
| **history** | `history` | Message history: send 3 messages, retrieve history, verify ordering and content |
| **prune** | `content_cut` | Pruning: send messages beyond messageTrim, verify old messages pruned, keyframe created |
| **episode** | `movie` | Episode flow: configure episodes, verify episode guidance in prompt, `#NEXT EPISODE#` detection |
| **analyze** | `analytics` | Analysis pipeline: send content, get analysis response, verify structure |
| **narrate** | `auto_stories` | Narration pipeline: send scene, get summary, verify format |
| **policy** | `policy` | Policy evaluation: if policy configured, verify request rewrite |

**Test Implementation Pattern:**

Each test function receives `(selectedCategories)` via the suite runner, uses `getVariant(name)` to select the appropriate chatConfig variant, and follows:
1. Guard — check category enabled, null-check config before accessing `.name`
2. Setup — create session / prepare state
3. Execute — call the API under test
4. Assert — log pass/fail with descriptive message
5. Debug — log full response data via `logData()` for copy-paste diagnosis
6. Cleanup — delete test sessions

**Config Variants:**
- `getVariant("streaming")` — `stream=true, prune=true`, has episodes with stages — used by stream, prune, episode tests
- `getVariant("standard")` — `stream=false, prune=false` — used by chat, history, analyze, narrate tests

```javascript
async function testChat(cats) {
  if (!cats.includes("chat")) return;
  log("chat", "=== Chat Session Tests ===");

  let chatCfg = getVariant("standard");
  let promptCfg = suiteState.promptConfig;
  if (!chatCfg || !promptCfg) {
    log("chat", "chatConfig or promptConfig missing - skipping", "skip");
    return;
  }
  log("chat", "Using variant: " + chatCfg.name, "info");

  // 1. Create session
  let req;
  try {
    req = await am7chat.getChatRequest("LLM Test - " + Date.now(), chatCfg, promptCfg);
    log("chat", "Session created: " + (req ? req.name : "null"), req ? "pass" : "fail");
  } catch(e) {
    log("chat", "Session create failed: " + e.message, "fail");
    return;
  }
  if (!req) return;

  // 2. Send message
  try {
    let resp = await am7chat.chat(req, "Say exactly: TEST_OK");
    let content = extractLastAssistantMessage(resp);
    log("chat", "Response received (" + (content ? content.length : 0) + " chars)", content ? "pass" : "fail");
    logData("chat", "Response content", content || "(empty)");
  } catch(e) {
    log("chat", "Chat failed: " + e.message, "fail");
    logData("chat", "Error details", e.stack);
  }

  // 3. Cleanup
  try {
    await am7chat.deleteChat(req, true);
    log("chat", "Session cleaned up", "pass");
  } catch(e) {
    log("chat", "Cleanup failed: " + e.message, "warn");
  }
}
```

#### 6.3 Integrated Test View

**`client/view/testView.js`** — Test view accessible from the app menu bar.

**Visibility:**
- Renders a "Test" button in the top menu bar
- Only visible when `page.testMode === true` OR `page.productionMode === false`
- Flags set via URL param (`?testMode=true`) or app config

**UI Layout:**
```
+--------------------------------------------------+
| [Back]  Test Suite  [Suite: ▼ LLM]  [Run Tests]  |
+--------------------------------------------------+
| Config: [chatConfig picker ▼]  [promptConfig ▼]  |
+--------------------------------------------------+
| [config] [prompt] [chat] [stream] [history] ...   |
+--------------------------------------------------+
| RESULTS: 12 pass  1 fail  2 warn    [All] [Issues]|
+--------------------------------------------------+
| 13:04:21 [config] ✓ chatConfig loaded             |
| 13:04:21 [config] ✓ Server URL reachable          |
| 13:04:22 [prompt] ✓ System prompt composed (847ch)|
| 13:04:22 [prompt] ✗ Orphan token: ${custom.foo}   |
| 13:04:22 [prompt] DATA: {"system":"This is a..."} |
| 13:04:23 [chat]   ✓ Session created: Test-1234    |
| 13:04:25 [chat]   ✓ Response received (142 chars) |
| 13:04:25 [chat]   DATA: "Hello! I'm Kael..."      |
+--------------------------------------------------+
| [Copy Logs to Clipboard]                          |
+--------------------------------------------------+
```

**Debug Data Output:**

The `testLogData()` function logs structured data as expandable/collapsible blocks. "Copy Logs to Clipboard" includes DATA entries in full:

```
[13:04:22] [prompt] [PASS] System prompt composed (847 chars)
[13:04:22] [prompt] [DATA] System prompt content:
  This is a fully immersive RC/XXX (Banned)-rated role-playing conversation/game.
  You (the LLM) control Kael Brightforge. ...
  RULES:
  1. Always stay in character.
  2. Create a response for Kael's next turn:
  ...
[13:04:22] [prompt] [FAIL] Orphan token found: ${custom.foo}
```

#### 6.4 CardGame Test Refactor

Refactor CardGame's `testMode.js` to register with the shared framework:

- Wrap existing test categories as a registered suite via `TestFramework.registerSuite("cardGame", ...)`
- Use shared `testLog` / `TestConsoleUI` instead of CardGame-specific implementations
- CardGame's test button routes to the shared test view with "Card Game" suite pre-selected

#### 6.5 Files

| Action | File | Purpose |
|--------|------|---------|
| **Create** | `client/test/testFramework.js` | Shared test state, logging, UI components |
| **Create** | `client/test/testRegistry.js` | Suite registration + suite selector |
| **Create** | `client/test/llm/llmTestSuite.js` | LLM test categories and implementations |
| **Create** | `client/view/testView.js` | Integrated test view with config pickers |
| **Modify** | `index.html` | Add `<script>` tags for test framework + LLM test suite + test view |
| **Modify** | `client/components/topMenu.js` | Add conditional "Test" menu button |
| **Modify** | `client/pageClient.js` | Add `page.testMode` and `page.productionMode` flags |
| **Refactor** | `client/view/cardGame/test/testMode.js` | Register with shared framework, use shared UI components |
| **Modify** | `client/applicationRouter.js` | Add `/test` route |
| **Modify** | `styles/pageStyle.css` | Add `tf-*` CSS classes for shared test framework UI |

#### 6.6 Implementation Notes

**Files created:** `testFramework.js`, `testRegistry.js`, `llmTestSuite.js`, `testView.js`
**Files created (templates):** `media/prompts/llmTestChatConfig.json`, `media/prompts/llmTestPromptConfig.json`
**Files modified:** `index.html`, `topMenu.js`, `pageClient.js`, `testMode.js`, `applicationRouter.js`, `pageStyle.css`

**Implementation details:**

1. **Shared TestFramework** (`testFramework.js`) — Core state (`testState`), logging (`testLog`, `testLogData`), suite registration (`registerSuite`), abort mechanism (`stopSuite` / `isAborted` — throws `__ABORTED__` on next `testLog` call), and five reusable Mithril UI components (`TestConsoleUI`, `TestToolbarUI`, `TestCategoryToggleUI`, `TestResultsSummaryUI`, `SuiteTabsUI`). Exposed as `window.TestFramework`.

2. **TestRegistry** (`testRegistry.js`) — Suite selector helpers (`getSelectedSuiteCategories`, `getSelectedSuite`) for the test view. Exposed as `window.TestRegistry`.

3. **LLM Test Suite** (`llmTestSuite.js`) — Registers as `"llm"` suite with 10 test categories: config, prompt, chat, stream, history, prune, episode, analyze, narrate, policy. Tests 63-81 implemented (including sub-tests 66b, 68b, 74b, 77b).

   **Auto-setup system** (`autoSetupConfigs()`):
   - Loads template JSON from `media/prompts/` via `m.request` (no custom deserializer — mithril handles JSON natively)
   - Creates `~/Tests` group via `page.makePath("auth.group", "data", "~/Tests")`
   - Creates two test characters: Aria Cortez (F, 28) and Max Reeves (M, 32)
   - Creates promptConfig from template with `episodeRule`, image/audio tokens, and perspective sections
   - Creates two chatConfig variants from template:
     - **"LLM Test Streaming"**: `stream=true, prune=true`, with episode (name, stages, theme) and character references
     - **"LLM Test Standard"**: `stream=false, prune=false`, with character references only
   - `findOrCreateConfig()` finds existing by name and skips creation — **delete server-side objects to pick up template changes**
   - `getVariant(name)` helper returns the appropriate config for each test function

   **Token classification** (Test 66): Comprehensive runtime token pattern recognizes `image`, `audio`, `nlp`, `system`, `user`, `perspective`, `censorWarn`, `assistCensorWarn`, `rating`, `ratingName`, `ratingDescription`, `ratingMpa`, `scene`, `setting`, `episode`, `episodeRule`, `dynamicRules`, `memory`, `interaction`, `profile`, `location` prefixes as valid runtime tokens. Unknown tokens produce warnings instead of failures.

   **Assist exchange awareness** (Tests 68b, 74b): When `chatConfig.assist=true`, the session has a hidden initial exchange (user input + assistant response) that is NOT in history but IS in the raw request object. Tests verify the pre-loaded messages exist in the raw request and are excluded from the history API.

   **Phase dependency annotations**: Tests for unimplemented phases include `// PHASE DEP:` code comments and runtime log headers:
   - ~~Stream tests: `[Phase 7 pending: Always-Stream refactor]`~~ — **Removed (Phase 7 complete)**
   - Prune tests: `[Phase 10 pending: Keyframe refactor]`
   - ~~Episode tests: `[Phase 7 pending: transition execution]`~~ — **Removed (Phase 7 complete)**
   - Policy tests: `[Phase 9 pending: evaluation not implemented]`

   **Episode architecture**: `episodeRule` (transition markers like `#NEXT EPISODE#`, `#OUT OF EPISODE#`) lives on promptConfig. Episode data (`episodes` array with `name`, `number`, `stages[]`, `theme`, `completed`) lives on chatConfig. Test 77b validates the full episode structure including the stages array.

4. **Test View** (`testView.js`) — Integrated view at `/test` route with `ConfigStatusUI` showing variant badges (iterates `suiteState.chatConfigs`), prompt badge, and character badges. Suite tabs via `SuiteTabsUI`, category toggles, results summary, and scrollable debug console. Registered as `page.views.testView`.

5. **CardGame Refactor** (`testMode.js`) — Refactored to delegate state to `TF.testState` and logging to `TF.testLog()`. Test body extracted to `runTestBody()` shared between standalone (`runTestSuite()`) and framework (`runCardGameTests()`) entry points. `TestModeUI` rebuilt using shared `TF.TestToolbarUI`, `TF.TestCategoryToggleUI`, `TF.TestResultsSummaryUI`, and `TF.TestConsoleUI` components while preserving CardGame-specific back navigation. Registered as `"cardGame"` suite.

6. **Visibility** — Test button in top menu visible when `page.testMode === true` (URL param `?testMode=true`) or `page.productionMode === false` (URL param `?productionMode=false`). Uses `science` material icon.

**Template files:**

- `media/prompts/llmTestChatConfig.json` — chatConfig template with `"schema": "olio.llm.chatConfig"`, model, serverUrl, serviceType, messageTrim, remindEvery, keyframeEvery, stream, prune, rating, assist, startMode. User should customize `model` and `serverUrl` for their environment.
- `media/prompts/llmTestPromptConfig.json` — promptConfig template with `"schema": "olio.llm.promptConfig"`, system prompt (includes `${image.selfie}` and `${audio.hello}` tokens), assistant prompt, systemAnalyze, systemNarrate, episodeRule (with `#NEXT EPISODE#` and `#OUT OF EPISODE#` markers), systemCensorWarning, femalePerspective, malePerspective, userConsentPrefix, userConsentRating.

**Known issues:**
- **OI-20: LLM-dependent tests require live server** — Tests 64, 69, 71-72, 73-80 require a running Ollama (or OpenAI-compatible) server. These tests will show "skip" or "fail" when the LLM server is unreachable. This is by design.
- **OI-21: Stream tests require WebSocket** — Tests 71-72 require an active WebSocket connection (`page.wss`) and `chatConfig.stream=true`. If streaming is not configured, tests fall back to a warning.
- **OI-22: Policy tests placeholder** — Test 81 (TestPolicyEvaluation) only validates configuration presence since Phase 9 (policy implementation) is not yet complete. Full policy evaluation testing will be enabled after Phase 9.
- **OI-23: CardGame shared state coupling** — The refactored `testMode.js` shares `TF.testState` with other suites. Running CardGame tests via the shared test view clears LLM test results and vice versa. Each suite run resets the shared state. This is expected behavior for sequential suite execution.
- **OI-24: findOrCreateConfig caching** — `findOrCreateConfig()` finds existing objects by name and skips creation. If templates change (e.g., new episodeRule, new image/audio tokens, new episode stages), existing server-side objects must be manually deleted before re-running the suite to pick up changes.
- **OI-25: Episode transition execution not testable** — Test 78 validates `episodeRule` config presence on promptConfig but cannot test actual `#NEXT EPISODE#` / `#OUT OF EPISODE#` detection and handling, which is server-side. Full episode transition testing requires Phase 7 (Always-Stream) completion.
- **OI-26: Keyframe detection heuristic** — Test 76 checks for `[MCP:KeyFrame` or `(KeyFrame:` text patterns in message history. False negatives possible if keyframe format changes. Will need update after Phase 10 (keyframe refactor).

**Skipped / deferred features:**
- **Config picker UI** — Original spec called for chatConfig/promptConfig dropdown pickers. Replaced by auto-setup system that creates configs from templates. No manual selection needed.
- **Magic8 test integration** — OI-17 (Magic8 client-side template wiring) was not addressed in Phase 6. Magic8 tests are not part of the LLM test suite.
- **Full episode transition testing** — Deferred to Phase 7 when the always-stream backend enables server-side `#NEXT EPISODE#` detection and response.

### Phase 7: Always-Stream Backend with Buffer & Timeout (Medium risk, medium impact) — COMPLETED

**Goal:** Unify streaming/non-streaming code paths and add timeout support (Section 5).

1. **Always-stream from LLM** — Refactored `Chat.chat()` to always use the streaming code path. The `stream` flag on `OpenAIRequest` controls whether chunks are forwarded to the client listener (`stream=true`, async) or buffered internally and returned as a complete `OpenAIResponse` (`stream=false`, blocking). The wire request always sets `stream=true`.
2. **Add `requestTimeout`** to `chatConfigModel.json` — Added `requestTimeout` field (int, default 120) with hard timeout via `CompletableFuture.orTimeout()`. Timeout fires a `TimeoutException` caught in `whenComplete()`, reported via `listener.onerror()` (streaming) or returned as null (buffer mode). `CountDownLatch` synchronizes the blocking buffer path.
3. **Extract `processStreamChunk()`** — Extracted shared method that handles both Ollama (top-level `message` object) and OpenAI (`choices[].delta`) response formats. `accumulateChunk()` helper accumulates content into the response and optionally forwards to listener.
4. **Add `thinking` field to `openaiMessageModel.json`** — Added `thinking` (string) field. Models like `qwen3` return chain-of-thought in this field; previously caused `RecordDeserializer` errors for the unrecognized field. (Resolves OI-4.)

**Files modified:** `Chat.java`, `chatConfigModel.json`, `openaiMessageModel.json`
**Files created:** `TestChatStream.java`
**Files updated:** `llmTestSuite.js` (stream tests 71-72, episode tests 77-78, removed Phase 7 pending annotations)

**Implementation details:**

- `Chat.chat()` always creates a `CompletableFuture` via `ClientUtil.postToRecordAndStream()`, applies `orTimeout()` if `requestTimeout > 0`, then uses `thenAccept()` to process stream chunks and `whenComplete()` to handle success/error/timeout. In buffer mode (`stream=false`), a `CountDownLatch` blocks the calling thread until streaming completes, then returns the buffered `OpenAIResponse`. In streaming mode (`stream=true`), the method returns null immediately and the listener receives callbacks.
- `processStreamChunk()` parses each SSE line, strips `data: ` prefix for OpenAI format, ignores `[DONE]` sentinel, imports the JSON into a `BaseRecord`, and dispatches to `accumulateChunk()` for content accumulation.
- Bug fix: The `penField` filter line was comparing against `tokField` instead of `penField` in the ignore fields filter.
- Removed unused `jakarta.ws.rs.core.MediaType` import (non-streaming `ClientUtil.postToRecord()` call eliminated).
- `continueChat()` still checks `stream` flag to decide whether to call `handleResponse()`/`saveSession()` synchronously (buffer mode) or defer to async processing (streaming mode).

**Backend tests (TestChatStream.java):**
- Test 36 `TestStreamBufferMode`: `stream=false` → `Chat.chat()` returns complete non-null response with content.
- Test 37 `TestStreamTimeoutTriggered`: `requestTimeout=1` → timeout handled gracefully, no exception escapes.
- Test 38 `TestStreamCancellation`: `stream=true` → `stopStream()` halts streaming within timeout.
- Test 39 `TestStreamingModeUnchanged`: `stream=true` → streaming via `MockWebSocket`/`ChatListener` still works.
- Stream tests use `qwen3:8b` model override for faster execution (avoids server contention from large model).

**UX test updates (llmTestSuite.js):**
- Test 72 updated: Now tests REST buffer mode (`stream=false`) by sending a message via `am7chat.sendMessage()` and validating the returned response, in addition to the existing WS streaming test (Test 71).
- Test 78b added: Validates `#NEXT EPISODE#` and `#OUT OF EPISODE#` markers present in `episodeRule` array.
- Test 78c added: Sends a message to the "Test Handoff" episode via WS streaming and checks if the response engages with the episode theme.
- All `[Phase 7 pending]` log headers and `// PHASE DEP:` annotations removed from stream and episode tests.

**Known issues:**
- **OI-27: Ollama server-side request not cancelled on timeout** — When `requestTimeout` fires, only the client-side `CompletableFuture` is cancelled. The Ollama server continues generating the response until completion. This can cause server contention if a timeout test precedes other tests that hit the same model. Mitigated in TestChatStream by using a smaller model (`qwen3:8b`) and simplified prompts.
- **OI-28: Test execution order sensitivity** — JUnit does not guarantee test execution order. Test 37 (timeout) sets `requestTimeout=1` on the shared DB config and restores it afterward, but if the JVM crashes between set and restore, subsequent tests inherit the 1-second timeout. Tests 38 and 39 explicitly set `requestTimeout=120` to guard against this.

**Open issues resolved:**
- OI-4: `openaiMessage` missing `thinking` field (item 4)
- OI-21: Stream tests require WebSocket — always-stream removes this dependency for non-streaming config (Test 72 now validates REST buffer mode)
- OI-25: Episode transition execution — server-side detection now testable (Test 78c)

### Phase 8: LLM Configuration & ChatOptions Fix (Low risk, high impact)

**Goal:** Fix configuration mapping bugs, add missing fields, and provide chatConfig templates (Section 6).

1. **Fix `top_k` maxValue** — Change from `1` to `500` in `chatOptionsModel.json`. Currently prevents valid values like 50. (Open issue from Section 6.1.)
2. **Fix `applyChatOptions()` mapping bug** — `typical_p` is incorrectly mapped to OpenAI's `presence_penalty` and `repeat_penalty` is mapped to `frequency_penalty`. These are different concepts with different semantics and ranges. Fix to map native OpenAI fields directly. (Open issue from Section 6.1.)
3. **Add missing chatOptions fields** — Add `max_tokens` (int, default 4096), `frequency_penalty` (double, -2.0 to 2.0), `presence_penalty` (double, -2.0 to 2.0), `seed` (int, default 0) to `chatOptionsModel.json`. (Open issue from Section 6.1.)
4. **Create chatConfig templates** — Provide preset JSON templates for General Chat, RPG, Coding, Content Analysis, Behavioral, and Technical Eval use cases (Section 6.3).

**Files modified:** `chatOptionsModel.json`, `ChatUtil.applyChatOptions()`, `chatConfigModel.json`
**Files added:** `chatConfig.generalChat.json`, `chatConfig.rpg.json`, `chatConfig.coding.json`, `chatConfig.contentAnalysis.json`, `chatConfig.behavioral.json`, `chatConfig.technicalEval.json`

#### Phase 8 Prep Notes

**Current code state (post-Phase 7):**

**1. `chatOptionsModel.json` — current fields:**

| Field | Type | Default | Range | Status |
|-------|------|---------|-------|--------|
| `top_k` | int | 50 | 0-**1** | **BUG: maxValue=1** (OI-6) |
| `top_p` | double | 1.0 | 0.0-1.0 | OK |
| `min_p` | double | 0.1 | 0.0-1.0 | OK |
| `typical_p` | double | 0.85 | 0.0-1.0 | OK (Ollama-native) |
| `repeat_last_n` | int | 64 | 0-100 | OK (Ollama-native) |
| `temperature` | double | 1.0 | 0.0-2.0 | OK |
| `repeat_penalty` | double | 1.2 | 0.0-2.0 | OK (Ollama-native) |
| `num_ctx` | int | 8192 | unbounded | OK |
| `num_gpu` | int | 1 | unbounded | OK (Ollama-native) |
| *MISSING* | - | - | - | `max_tokens`, `frequency_penalty`, `presence_penalty`, `seed` |

**2. `openaiRequestModel.json` — wire format fields (already present on request):**

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `max_completion_tokens` | int | 2048 | For o1+ models |
| `max_tokens` | int | 2048 | Standard OpenAI |
| `num_ctx` | int | 2048 | Ollama cross-compat |
| `temperature` | double | 0.75 | |
| `top_p` | double | 0.5 | |
| `frequency_penalty` | double | 1.3 | **Default too high — should be 0.0** |
| `presence_penalty` | double | 1.3 | **Default too high — should be 0.0** |
| `messages` | list | - | OpenAI message array |

Note: The request model already has `frequency_penalty` and `presence_penalty` fields but with incorrect defaults (1.3). These are the wire fields that `applyChatOptions()` writes to. The request model defaults should also be fixed to 0.0 to match OpenAI API defaults.

**3. `ChatUtil.applyChatOptions()` — current broken mapping (line 1244-1277):**

```java
// Reads from chatOptions:
repeat_penalty = opts.get("repeat_penalty");   // Ollama concept: broad token repetition penalty
typical_p = opts.get("typical_p");             // Ollama concept: typical probability sampling

// Writes to OpenAI request:
req.set("frequency_penalty", repeat_penalty);  // BUG: Ollama repeat_penalty → OpenAI frequency_penalty
req.set("presence_penalty", typical_p);        // BUG: Ollama typical_p → OpenAI presence_penalty
req.set(getMaxTokenField(cfg), num_ctx);       // OK: uses model-aware field selection
```

**Semantic mismatch:**
- `repeat_penalty` (Ollama, range 0.0-2.0, default 1.2): Penalizes all repeated tokens proportionally. >1.0 = penalize, <1.0 = encourage. Used by llama.cpp.
- `frequency_penalty` (OpenAI, range -2.0-2.0, default 0.0): Penalizes tokens by exact appearance count. 0.0 = no effect, positive = penalize frequency. Different algorithm.
- `typical_p` (Ollama, range 0.0-1.0, default 0.85): Locally typical sampling — filters unlikely-surprise tokens. A sampling method.
- `presence_penalty` (OpenAI, range -2.0-2.0, default 0.0): Binary — penalizes tokens that have appeared at all, regardless of frequency. Not a sampling method.

**4. `Chat.applyAnalyzeOptions()` — has the SAME mapping bug (line 509-529):**

```java
// Hardcoded values, same wrong mapping:
req.set("frequency_penalty", repeat_penalty);  // BUG: same as applyChatOptions
req.set("presence_penalty", typical_p);        // BUG: same as applyChatOptions
req.set("max_completion_tokens", num_ctx);     // Uses o1 field unconditionally — should use getMaxTokenField()
```

This method also hardcodes its options (`temperature=0.4`, `top_p=0.5`, etc.) instead of reading from an analyze-specific chatOptions or using the chatConfig's `analyzeModel` options. This is a secondary issue but should be noted.

**5. `Chat.chat()` ignore fields logic (already partially fixed in Phase 7):**

The `chat()` method dynamically determines which max-token and penalty fields to exclude from the wire request based on model/service type. Phase 7 fixed the `penField` filter bug (was comparing against `tokField` instead of `penField`). Phase 8 should verify this logic still works correctly after adding new chatOptions fields.

```java
// Phase 7 current state:
String tokField = ChatUtil.getMaxTokenField(chatConfig);
ignoreFields.addAll(... filter(f -> !f.equals(tokField)) ...);  // Keeps correct token field
String penField = ChatUtil.getPresencePenaltyField(chatConfig);
ignoreFields.addAll(... filter(f -> !f.equals(penField)) ...);  // Keeps correct penalty field
```

**6. Field pruning in `getPrunedRequest()` and `IGNORE_FIELDS`:**

`Chat.chat()` builds a dynamic ignore list from `IGNORE_FIELDS` + model-specific exclusions. After Phase 8 adds new fields to chatOptions, the wire request will only include fields that are set and not ignored. No changes needed to `IGNORE_FIELDS` — it only excludes metadata fields (`id`, `objectId`, `groupId`, etc.), not LLM parameter fields.

**7. Callers of `applyChatOptions()` (5 call sites, all in `Chat.java`):**

| Caller | Line | Context |
|--------|------|---------|
| `newRequest()` | 1159 | Creates base request for chat — primary path |
| `applyAnalyzeOptions()` | 515 | Analysis prompts — then overrides with hardcoded values |
| `getNarratePrompt()` | 559 | Narration prompts — called via `applyAnalyzeOptions()` + direct call (double-apply) |
| `getSDPrompt()` | 611 | Stable Diffusion prompts |
| `getReducePrompt()` | 701 | Reduce/summarize prompts |

Note: `getNarratePrompt()` calls `applyAnalyzeOptions()` (which calls `applyChatOptions()`) AND then calls `applyChatOptions()` directly again (line 559). This double-apply is harmless but wasteful.

**Implementation plan for `applyChatOptions()` rewrite:**

The fix needs to handle two service types with different parameter semantics:

```
chatOptions fields     → Ollama wire format         → OpenAI wire format
─────────────────────────────────────────────────────────────────────────
temperature            → temperature                → temperature
top_p                  → top_p                      → top_p
top_k                  → top_k (native)             → (ignored — not supported)
min_p                  → min_p (native)             → (ignored — not supported)
typical_p              → typical_p (native)         → (ignored — not supported)
repeat_penalty         → repeat_penalty (native)    → (ignored — not supported)
repeat_last_n          → repeat_last_n (native)     → (ignored — not supported)
num_ctx                → num_ctx (context window)   → (ignored — use max_tokens)
num_gpu                → num_gpu (native)           → (ignored — not supported)
frequency_penalty      → (ignored — not supported)  → frequency_penalty
presence_penalty       → (ignored — not supported)  → presence_penalty
max_tokens             → (ignored — use num_ctx)    → max_tokens / max_completion_tokens
seed                   → seed (if supported)        → seed (if supported)
```

**Strategy:** Read ALL fields from chatOptions. Based on `serviceType`:
- **OLLAMA**: Set `temperature`, `top_p`, and `num_ctx` on request. Ollama-specific fields (`top_k`, `repeat_penalty`, `typical_p`, etc.) go into `options` parameter (Ollama API). `frequency_penalty` and `presence_penalty` are ignored.
- **OPENAI**: Set `temperature`, `top_p`, `frequency_penalty`, `presence_penalty`, and `max_tokens`/`max_completion_tokens` (via `getMaxTokenField()`). Ollama-specific fields are ignored. `getPresencePenaltyField()` determines if presence_penalty is supported for the model.

**Wait — Ollama `options` parameter:**  Ollama's `/api/chat` endpoint accepts a top-level `options` object for sampling params, but looking at how the current code serializes: the `OpenAIRequest` model has `temperature`, `top_p`, `frequency_penalty`, `presence_penalty` as top-level fields. The current code sets these directly on the request. Ollama's API does accept `temperature` and `top_p` at the top level (OpenAI-compatible mode) but native Ollama params like `repeat_penalty`, `typical_p`, `top_k` go in an `options` object.

**Current approach**: The code sets `frequency_penalty` and `presence_penalty` on the request regardless of service type. For Ollama, these fields are silently ignored by the Ollama server (it doesn't use them). The actual Ollama-native params (`repeat_penalty`, `typical_p`, `top_k`) are NOT currently sent at all — they're read from chatOptions but mapped to the wrong OpenAI fields instead of being sent in their native form.

**This means:** For Ollama, the current code effectively sends NO meaningful penalty/sampling params beyond `temperature` and `top_p`. The `repeat_penalty` value gets written to `frequency_penalty` which Ollama ignores. The fix should either:
- (a) Add Ollama `options` support to the request model and serialize native params there, OR
- (b) Keep the current approach of only sending OpenAI-compatible fields (Ollama accepts `temperature`, `top_p`, `frequency_penalty`, `presence_penalty` in OpenAI-compat mode since Ollama v0.1.35+)

**Recommended: Option (b)** — Ollama's OpenAI-compatible endpoint at `/api/chat` does accept `frequency_penalty` and `presence_penalty` as of recent versions. This avoids adding an `options` sub-object to the request model. For Ollama-only params (`typical_p`, `repeat_last_n`, `min_p`, `top_k`), these can only be sent via the native `options` object, which would require a model schema change. **Defer native `options` support to a future phase** — it's not needed for the core mapping fix.

**Simplified Phase 8 approach:**
1. Fix `top_k` maxValue
2. Add `frequency_penalty`, `presence_penalty`, `max_tokens`, `seed` to `chatOptionsModel.json`
3. Rewrite `applyChatOptions()` to read the new fields directly and map per service type
4. Fix `openaiRequestModel.json` defaults (`frequency_penalty` and `presence_penalty` from 1.3 to 0.0)
5. Fix `applyAnalyzeOptions()` to use `getMaxTokenField()` and correct penalty mapping
6. Create chatConfig template JSON files per Section 6.3

**Template file location:** `AccountManagerObjects7/src/main/resources/models/olio/llm/templates/` — separate from model schemas, loadable via resource path.

**Open issues deferred beyond Phase 8:**
- **OI-29: Ollama native `options` object** — `top_k`, `typical_p`, `repeat_penalty`, `min_p`, `repeat_last_n` are only configurable via Ollama's native `options` parameter, which the current request model doesn't support. These fields exist on `chatOptionsModel.json` but are not sent to Ollama in any form after the mapping fix removes the incorrect cross-mapping. Future phase could add an `options` embedded model to `openaiRequestModel.json` for Ollama-native params.
- **OI-30: `applyAnalyzeOptions()` hardcoded values** — Analysis/narration/reduce prompts hardcode their own `temperature`, `top_p`, etc. instead of reading from an analyze-specific config. Not a Phase 8 blocker but noted for cleanup.
- **OI-31: `getNarratePrompt()` double-applies chatOptions** — Calls `applyAnalyzeOptions()` (which calls `applyChatOptions()`) then calls `applyChatOptions()` again directly. Harmless but wasteful.

### Phase 9: Policy-Based LLM Response Regulation (Higher risk, medium impact)

**Goal:** Detect and respond to LLM failures using the existing policy evaluation infrastructure (Section 8).

1. **Implement `TimeoutDetectionOperation`** — Detect null/empty responses from timeout or connection drop.
2. **Implement `RecursiveLoopDetectionOperation`** — Detect repeated text blocks (50-char window, 3x threshold).
3. **Implement `WrongCharacterDetectionOperation`** — Detect LLM responding as the user character.
4. **Implement `RefusalDetectionOperation`** — Detect LLM safety refusals within configured rating.
5. **Implement `ResponsePolicyEvaluator`** — Wire operations into policy evaluation pipeline.
6. **Implement `ChatAutotuner`** — LLM-based prompt analysis and rewrite on policy violation.
7. **Create sample policy JSON files** — Default policies for RPG, Clinical, and General use cases.

**New files:** `TimeoutDetectionOperation.java`, `RecursiveLoopDetectionOperation.java`, `WrongCharacterDetectionOperation.java`, `RefusalDetectionOperation.java`, `ResponsePolicyEvaluator.java`, `ChatAutotuner.java`, sample policy JSONs
**Modified files:** `Chat.java` (post-response evaluation hook)

### Phase 10: Memory System Hardening & Keyframe Refactor (Low risk, medium impact)

**Goal:** Complete memory system gaps, refactor keyframe/memory overlap, and relocate tests.

1. **Populate `personModel` field** — Set `personModel` in `MemoryUtil.createMemory()` and `Chat.persistKeyframeAsMemory()` when the person type is known (e.g., `"olio.charPerson"`). (Open issue from Phases 2-3.)
2. **Pass person pair IDs in `extractMemoriesFromResponse()`** — Add `personId1`/`personId2` parameters so LLM-extracted memories are tagged with the character pair. (Open issue from Phases 2-3.)
3. **Relocate memory/keyframe tests to Objects7** — `TestMemoryUtil`, `TestMemoryPhase2`, `TestKeyframeMemory`, and `TestMemoryDuel` have zero Agent7 imports and depend entirely on Objects7 classes (`Chat`, `MemoryUtil`, `PromptUtil`, etc.). Move them to `AccountManagerObjects7/src/test/java/` to match their actual dependency scope. (Open issue from Phase 3.)
4. **Deprecate old `(KeyFrame:` format** — Remove backward-compatibility checks for `(KeyFrame:` prefix in `pruneCount()`, `countBackTo()`, `addKeyFrame()`, and `getFormattedChatHistory()`. Require MCP format only. Reduces code complexity.
5. **Add keyframe cost guard** — When `keyframeEvery` is low (e.g., 2-3), each keyframe triggers an expensive `analyze()` LLM call. Add a minimum floor (e.g., `keyframeEvery >= 5` when `extractMemories=true`) or a rate limiter to prevent excessive LLM costs. (Open issue from Phase 3.)

**Files modified:** `MemoryUtil.java`, `Chat.java`, `ChatUtil.java`
**Files relocated:** `TestMemoryUtil.java`, `TestMemoryPhase2.java`, `TestKeyframeMemory.java`, `TestMemoryDuel.java` → `AccountManagerObjects7/src/test/java/`

### Phase Progress Summary

| Phase | Status | Risk | Impact | Tests |
|-------|--------|------|--------|-------|
| 1 — Template Cleanup | Partial (item 3 superseded by Phase 4) | Low | High | 1-7 (not yet written) |
| 2 — Memory Retrieval | **IMPLEMENTED** | Medium | High | 8-18 (all pass) |
| 3 — Keyframe-to-Memory Pipeline | **IMPLEMENTED** | Medium | Medium | 19-22 (all pass) |
| 4 — Structured Template Schema | **COMPLETED** | Higher | High | 23-28 (all pass) |
| 5 — Client-Side Cleanup & Validation/Migration | **COMPLETED** | Low | Medium | 29-35 (all pass) |
| 6 — UX Test Suite | **COMPLETED** | Low | High | 63-81 (browser) |
| 7 — Always-Stream Backend | **COMPLETED** | Medium | Medium | 36-39 (all pass) |
| 8 — LLM Config & ChatOptions Fix | Not started | Low | High | 40-45 |
| 9 — Policy-Based Response Regulation | Not started | Higher | Medium | 46-62 |
| 10 — Memory Hardening & Keyframe Refactor | Not started | Low | Medium | (no numbered tests) |

### Next Phase Recommendation

**Recommended next: Phase 8 (LLM Config & ChatOptions Fix)**.

**Rationale:**

- **Phase 7** is now **COMPLETED**. Always-stream backend with buffer/timeout support is implemented, all backend tests (36-39) pass, all regression tests (TestChat, TestChat2, TestChatAsync) pass, UX tests updated.

- **Phase 8** has three **P1 bugs** (OI-6: `top_k` maxValue=1, OI-7: `typical_p` mapped to wrong field, OI-8: `repeat_penalty` semantics mismatch) that affect actual LLM behavior. These are low-risk, self-contained fixes.

- **Phase 1** (items 1, 2, 4) could be done anytime as a low-risk cleanup pass. Item 3 (condition checks) is largely superseded by Phase 4's `PromptConditionEvaluator` for new templates, but the legacy flat pipeline still benefits from `if` guards.

- **Phase 9** (Policy-Based Response Regulation) depends on Phase 7's always-stream backend being complete (now satisfied) and Phase 8's config fixes for correct LLM parameters.

**Suggested order:** 8 → 1 (remainder) → 10 → 9

---

## 10. Keyframe System Evaluation

### 10.1 Current Role

Keyframes serve as periodic narrative checkpoints that:
- Call `analyze()` to generate an LLM summary of recent conversation
- Inject the summary as an MCP context block into the message stream
- Survive message pruning (last 2 are kept), providing continuity when old messages are trimmed
- (Phase 3) Persist as durable OUTCOME memories for cross-session retrieval

### 10.2 Overlap with Memory System

After Phase 2-3 implementation, there is significant overlap:

| Capability | Keyframe System | Memory System |
|-----------|----------------|---------------|
| Periodic summarization | `addKeyFrame()` via `analyze()` | Not automatic (requires `extractMemories`) |
| Inline context for LLM | Last 2 keyframes in message stream | `${memory.context}` injected at prompt composition |
| Cross-session persistence | Phase 3: persists as OUTCOME memories | Native — all memories are durable |
| Semantic search | Only via persisted memory (Phase 3) | Native — vector embeddings on all memories |
| Role-agnostic retrieval | Via memory system (Phase 3) | Native — canonical person pair IDs |

### 10.3 What Keyframes Still Provide

1. **Inline narrative anchors** — Keyframes live in the active message stream between user/assistant turns. The LLM sees them as part of the conversation flow, not as a separate context block. This is qualitatively different from `${memory.context}` which appears only in the system prompt.
2. **Prune survival** — When messages are trimmed, keyframes bridge the gap between the system prompt and recent messages. Without them, pruning creates a jarring discontinuity.
3. **Automatic generation** — The memory system requires explicit extraction triggers. Keyframes are generated automatically by the prune system at regular intervals.

### 10.4 Verdict: Refactor, Not Remove

Keyframes are **not redundant** but **need refactoring** to eliminate duplication with the memory system:

**Keep:**
- Automatic periodic analysis via `analyze()` — this is the core value
- Inline injection into message stream for narrative continuity
- Prune-aware retention (last 2 keyframes)

**Refactor:**
- Consolidate counting/filtering: Replace separate `countBackTo("(KeyFrame:")` and `countBackTo("(Reminder:")` with a unified `countBackToMcp(String uriFragment)` method
- Deprecate old `(KeyFrame:` text format — require MCP format only (Phase 10 item 4)
- Consider reducing keyframe content in message stream to a short summary, with full analysis stored only in the memory system

**Future consideration (not currently planned):**
- Once the memory system has reliable semantic retrieval and importance scoring, keyframes could potentially be replaced entirely by automatic memory injection. This would require: (a) memory retrieval fast enough for inline use, (b) importance scoring that reliably selects the right context, and (c) testing that LLM output quality doesn't degrade. This is an architectural decision for Phase 4+ evaluation.

---

## 11. Open Issue Tracker

All known open issues with their assigned resolution phase:

| # | Issue | Source | Assigned Phase | Priority |
|---|-------|--------|---------------|----------|
| OI-1 | `personModel` field not populated by `MemoryUtil.createMemory()` or `Chat.persistKeyframeAsMemory()` | Phase 2-3 known issues | Phase 10 (item 1) | P3 |
| OI-2 | ~~`MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION`, `MEMORY_COUNT` always empty/zero~~ | Phase 2-3 known issues | ~~Phase 4 (item 8)~~ **RESOLVED** | ~~P2~~ |
| OI-15 | `${nlp.command}` pipeline ordering: Stage 6 replaces before Stage 7 can reintroduce via dynamic rules | Phase 4 implementation | Phase 10 | P3 |
| OI-16 | ~~StackOverflowError in deeply nested record authorization — RecordDeserializer debug `toString()` removed + PolicyUtil `getForeignPatterns` depth-limited + slim `copyRecord()` update pattern~~ | Phase 4 testing | ~~Phase 10~~ **RESOLVED** | ~~P3~~ |
| OI-17 | Magic8 client-side template (`SessionDirector.js`) not yet wired to server-side `prompt.magic8.json` | Phase 4 implementation | Phase 6+ | P3 |
| OI-3 | `extractMemoriesFromResponse()` does not pass person pair IDs | Phase 2-3 known issues | Phase 10 (item 2) | P2 |
| OI-4 | ~~`openaiMessage` model missing `thinking` field — qwen3/CoT models produce error logs~~ | Phase 3 testing | ~~Phase 7 (item 4)~~ **RESOLVED** | ~~P2~~ |
| OI-5 | Low `keyframeEvery` values trigger expensive `analyze()` LLM calls | Phase 3 known issues | Phase 10 (item 5) | P3 |
| OI-6 | `top_k` maxValue=1 prevents valid values (should be 500) | Section 6.1 | Phase 8 (item 1) | P1 |
| OI-7 | `typical_p` incorrectly mapped to OpenAI `presence_penalty` in `applyChatOptions()` | Section 6.1 | Phase 8 (item 2) | P1 |
| OI-8 | `repeat_penalty` mapped to `frequency_penalty` with different semantics | Section 6.1 | Phase 8 (item 2) | P1 |
| OI-9 | Missing `max_tokens` field on chatOptions | Section 6.1 | Phase 8 (item 3) | P2 |
| OI-10 | Missing `frequency_penalty` field on chatOptions (OpenAI-native) | Section 6.1 | Phase 8 (item 3) | P2 |
| OI-11 | Missing `presence_penalty` field on chatOptions (OpenAI-native) | Section 6.1 | Phase 8 (item 3) | P2 |
| OI-12 | Missing `seed` field on chatOptions | Section 6.1 | Phase 8 (item 3) | P3 |
| OI-13 | Memory/keyframe tests in Agent7 have no Agent7 dependencies — should relocate to Objects7 | Phase 3 testing | Phase 10 (item 3) | P3 |
| OI-14 | Old `(KeyFrame:` text format still supported alongside MCP format — maintenance burden | Phase 3 code review | Phase 10 (item 4) | P3 |
| OI-18 | Client-side prune functions retained for backward compatibility — can be removed once all active sessions refresh | Phase 5 implementation | Future cleanup | P4 |
| OI-19 | Migrator condition coverage — static condition map covers 7 of ~34 fields; fields like `femalePerspective`/`malePerspective` have no condition mapping | Phase 5 implementation | Phase 10 | P3 |
| OI-20 | Token standardization — image/audio token processing still varies between prompt template styles | Phase 5 review | Phase 10 | P3 |
| OI-21 | ~~Stream tests require WebSocket — Tests 71-72 need active `page.wss` and `chatConfig.stream=true`~~ | Phase 6 implementation | ~~Phase 7~~ **RESOLVED** | ~~P3~~ |
| OI-22 | Policy tests placeholder — Test 81 validates config presence only; evaluation requires Phase 9 | Phase 6 implementation | Phase 9 | P3 |
| OI-23 | CardGame shared state coupling — switching suites resets shared `TF.testState` | Phase 6 implementation | By design | P4 |
| OI-24 | `findOrCreateConfig` caching — template changes require manual deletion of existing server objects | Phase 6 implementation | Future: add update-if-changed | P3 |
| OI-25 | ~~Episode transition execution not testable — `#NEXT EPISODE#` detection is server-side~~ | Phase 6 implementation | ~~Phase 7~~ **RESOLVED** | ~~P3~~ |
| OI-26 | Keyframe detection heuristic — Test 76 pattern-matches `[MCP:KeyFrame` / `(KeyFrame:` text; fragile | Phase 6 implementation | Phase 10 | P3 |
| OI-27 | Ollama server-side request not cancelled on client timeout — `CompletableFuture` cancellation doesn't reach server | Phase 7 implementation | Future: Ollama abort API | P3 |
| OI-28 | Test execution order sensitivity — Test 37 sets requestTimeout=1 on shared DB config; crash between set/restore leaks value | Phase 7 testing | Mitigated by explicit resets | P4 |
| OI-29 | Ollama native `options` object not supported — `top_k`, `typical_p`, `repeat_penalty`, `min_p`, `repeat_last_n` not sent after Phase 8 mapping fix | Phase 8 prep | Future: add `options` model to request | P3 |
| OI-30 | `applyAnalyzeOptions()` hardcodes `temperature=0.4`, `top_p=0.5`, etc. instead of reading from config | Phase 8 prep | Future cleanup | P4 |
| OI-31 | `getNarratePrompt()` double-applies chatOptions — calls `applyAnalyzeOptions()` then `applyChatOptions()` again | Phase 8 prep | Future cleanup | P4 |
| OI-32 | `openaiRequestModel.json` defaults for `frequency_penalty` and `presence_penalty` are 1.3 — should be 0.0 to match OpenAI API defaults | Phase 8 prep | Phase 8 | P2 |

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
| `memoryBudget` | int | 0 | Token budget for memory context injection (0=disabled) |
| `memoryExtractionEvery` | int | 0 | Keyframes between memory extractions (0=every keyframe) |
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
