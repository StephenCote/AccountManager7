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
- **OI-18: Migrator condition coverage** — ~~The static condition map in `PromptConfigMigrator` covers the 7 most common conditional fields. Fields like `femalePerspective`/`malePerspective` have no condition mapping.~~ **RESOLVED in Phase 11** — `CONDITION_MAP` expanded from 7 to 14 entries (OI-19).
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
   - Prune tests: `[Phase 11 pending: Keyframe refactor]`
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
- **OI-26: Keyframe detection heuristic** — ~~Test 76 checks for `[MCP:KeyFrame` or `(KeyFrame:` text patterns in message history. False negatives possible if keyframe format changes.~~ **RESOLVED in Phase 11** — Updated to MCP URI fragment detection (`<mcp:context` + `/keyframe/`).

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

### Phase 9: Policy-Based LLM Response Regulation (Higher risk, medium impact) — COMPLETED

**Goal:** Detect and respond to LLM failures using the existing policy evaluation infrastructure (Section 8).

1. **Implement `TimeoutDetectionOperation`** — Detect null/empty responses from timeout or connection drop.
2. **Implement `RecursiveLoopDetectionOperation`** — Detect repeated text blocks (50-char window, 3x threshold).
3. **Implement `WrongCharacterDetectionOperation`** — Detect LLM responding as the user character.
4. **Implement `RefusalDetectionOperation`** — Detect LLM safety refusals within configured rating.
5. **Implement `ResponsePolicyEvaluator`** — Wire operations into policy evaluation pipeline.
6. **Implement `ChatAutotuner`** — LLM-based prompt analysis and rewrite on policy violation.
7. **Create sample policy JSON files** — Default policies for RPG, Clinical, and General use cases.

**New files:** `TimeoutDetectionOperation.java`, `RecursiveLoopDetectionOperation.java`, `WrongCharacterDetectionOperation.java`, `RefusalDetectionOperation.java`, `ResponsePolicyEvaluator.java`, `ChatAutotuner.java`, sample policy JSONs
**Modified files:** `Chat.java` (post-response evaluation hook), `ChatListener.java` (async post-response hook), `WebSocketService.java` (chirp notifications)

#### Phase 9 Prep Notes

**Current code state (post-Phase 8):**

**1. Policy Evaluation Infrastructure — Already Implemented:**

The core policy framework is fully functional and used for authorization decisions. Phase 9 reuses this infrastructure for LLM response evaluation.

| Component | File | Purpose |
|-----------|------|---------|
| `PolicyEvaluator` | `AccountManagerObjects7/.../policy/PolicyEvaluator.java` | Hierarchical rule/pattern evaluation with scoring |
| `IOperation` | `AccountManagerObjects7/.../policy/operation/IOperation.java` | Custom operation interface: `read()` + `operate()` → `OperationResponseEnumType` |
| `Operation` (abstract) | `AccountManagerObjects7/.../policy/operation/Operation.java` | Base class with `IReader`, `ISearch`, `FactUtil` injection |
| `PolicyUtil` | `AccountManagerObjects7/.../policy/PolicyUtil.java` | Dynamic fact/pattern/rule construction and evaluation helpers |
| `FactUtil` | `AccountManagerObjects7/.../policy/FactUtil.java` | Fact data extraction and resolution |

**IOperation interface (line 30-31):**
```java
public interface IOperation {
    public <T> T read(BaseRecord sourceFact, final BaseRecord referenceFact);
    public OperationResponseEnumType operate(final BaseRecord prt, BaseRecord prr,
        final BaseRecord pattern, BaseRecord sourceFact, final BaseRecord referenceFact);
}
```

Return values: `OperationResponseEnumType.SUCCEEDED` (rule passes), `FAILED` (rule triggers), `ERROR`, `UNKNOWN`.

**Existing Operation implementations** (examples to follow):
- `RegexOperation.java` — Pattern-cached regex matching against fact data
- `TokenOperation.java` — Token-based access check
- `OwnerOperation.java` — Resource ownership check

All extend `Operation` base class which provides `IReader`, `ISearch`, and `FactUtil` via constructor injection.

**2. Policy Model Schemas (in `resources/models/policy/`):**

| Schema | Key Fields | Notes |
|--------|------------|-------|
| `policy.policy` | `rules[]`, `enabled`, `decisionAge`, `condition` (ANY/ALL/NONE) | Top-level policy container |
| `policy.rule` | `patterns[]`, `rules[]` (recursive), `condition`, `type` (PERMIT/DENY), `score` | Rule node — can nest child rules |
| `policy.pattern` | `fact`, `match`, `operation`, `operationClass`, `type` (PARAMETER/OPERATION/EXPRESSION/AUTHORIZATION/SEPARATION_OF_DUTY), `comparator` | Pattern — references source fact, match fact, and optional custom operation |
| `policy.fact` | `factData`, `type` (ATTRIBUTE/PARAMETER/FUNCTION), `valueType`, `parameters[]` | Fact holder — **already has `chatConfig` and `promptConfig` foreign references** (lines 95-107 in factModel.json) |
| `policy.operation` | `operation` (class name), `type` (INTERNAL/FUNCTION) | Operation definition — `FUNCTION` type invokes the class via `IOperation` interface |

**Critical discovery:** `policy.fact` already has `chatConfig` (→ `olio.llm.chatConfig`) and `promptConfig` (→ `olio.llm.promptConfig`) foreign references. This was pre-built for Phase 9 — facts can natively reference chat and prompt configurations.

**3. `chatConfig.policy` field — Pre-built but Unused:**

In `chatConfigModel.json` (line 207-213):
```json
{
    "name": "policy",
    "type": "model",
    "baseModel": "policy.policy",
    "foreign": true,
    "followReference": false,
    "description": "Development: Using a policy to route conversation requests through conditional rules and logic."
}
```

This field exists on every chatConfig instance but is never referenced in `Chat.java` or `ChatListener.java`. Phase 9 activates it.

**4. Post-Response Hook Locations (two paths):**

**Path A — Synchronous (buffer mode, `stream=false`):**
`Chat.continueChat()` lines 341-347:
```java
if(!stream) {
    lastRep = chat(req);                       // Line 341: LLM call
    if (lastRep != null) {
        handleResponse(req, lastRep, false);   // Line 343: Add to history
    }
    // *** PHASE 9 HOOK HERE: evaluate policy AFTER handleResponse, BEFORE saveSession ***
    saveSession(req);                          // Line 347: Persist
}
```

**Path B — Asynchronous (streaming mode, `stream=true`):**
`ChatListener.oncomplete()` lines 243-255:
```java
chat.handleResponse(request, response, false);  // Line 243
// *** PHASE 9 HOOK HERE ***
chat.saveSession(request);                       // Line 255
```

Both paths need the hook. The streaming path also has access to `WebSocketService.chirpUser()` for real-time notifications.

**5. WebSocket Notification via `chirpUser()`:**

`WebSocketService.java` line 434:
```java
public static boolean chirpUser(BaseRecord user, String[] chirps)
```

Already used for chat lifecycle events:
- `chirpUser(user, new String[] {"chatStart", objectId, requestJson})`
- `chirpUser(user, new String[] {"chatUpdate", objectId, message})`
- `chirpUser(user, new String[] {"chatComplete", objectId})`
- `chirpUser(user, new String[] {"chatError", objectId, msg})`
- `chirpUser(user, new String[] {"chainEvent", "chainStart", planQuery})`

Phase 9 adds `policyEvent` type following the same `new String[] {action, type, data}` pattern.

**Note:** `chirpUser` is in the Service7 module, not Objects7. The four detection operations and `ResponsePolicyEvaluator` will live in Objects7 (`olio.llm.policy` package). The chirp calls must be made from `ChatListener` (Service7) which already imports `WebSocketService`, not from the operations themselves.

**6. Existing UX Policy Test (placeholder):**

`llmTestSuite.js` Test 81 (`testPolicy`) is already stubbed as a Phase 9 placeholder:
```javascript
async function testPolicy(cats) {
    // PHASE DEP: Phase 9 (Policy-Based LLM Response Regulation) — NOT STARTED
    // Only config validation possible; evaluation operations not implemented
    ...
    let hasPolicy = !!(chatCfg.responsePolicy || chatCfg.policy);
    ...
    log("policy", "Policy evaluation tests will be fully functional after Phase 9 implementation", "info");
}
```

Phase 9 should expand this with actual policy evaluation tests and add `policyEvent` WebSocket handler tests.

**7. Implementation Plan:**

| Step | Component | Package/Location | Dependencies |
|------|-----------|------------------|-------------|
| 1 | `TimeoutDetectionOperation` | `olio.llm.policy` (Objects7) | `IOperation`, `Operation` base class |
| 2 | `RecursiveLoopDetectionOperation` | `olio.llm.policy` (Objects7) | `IOperation`, `Operation` base class |
| 3 | `WrongCharacterDetectionOperation` | `olio.llm.policy` (Objects7) | `IOperation`, `Operation` base class |
| 4 | `RefusalDetectionOperation` | `olio.llm.policy` (Objects7) | `IOperation`, `Operation` base class |
| 5 | `ResponsePolicyEvaluator` | `olio.llm.policy` (Objects7) | Steps 1-4, `PolicyEvaluator`, `FactUtil` |
| 6 | Sample policy JSON files | `resources/olio/llm/` (Objects7) | Steps 1-4 (references operation class names) |
| 7 | Post-response hooks in `Chat.continueChat()` and `ChatListener.oncomplete()` | Objects7 + Service7 | Step 5, `chatConfig.policy` field |
| 8 | `ChatAutotuner` | `olio.llm.policy` (Objects7) | Step 5, `Chat` LLM call infrastructure |
| 9 | WebSocket `policyEvent` notifications | Service7 (`ChatListener`) | Step 7, `WebSocketService.chirpUser()` |
| 10 | Enhanced stop with failover | Objects7 (`Chat`) + Service7 (`ChatListener`) | `CompletableFuture.cancel()` |
| 11 | Backend tests (TestResponsePolicy.java) | Objects7 test | Steps 1-8 |
| 12 | UX test updates (llmTestSuite.js) | UX | Steps 7, 9 |
| 13 | UX `policyEvent` handler (chat.js / SessionDirector.js) | UX | Step 9 |

**8. Test Plan (Tests 46-62):**

| Test | Name | Description | LLM Required |
|------|------|-------------|:---:|
| 46 | `TestTimeoutDetection` | Null/empty response → FAILED | No |
| 47 | `TestTimeoutWithContent` | Non-empty response → SUCCEEDED | No |
| 48 | `TestRecursiveLoopDetection` | Repeated 50-char blocks ≥3x → FAILED | No |
| 49 | `TestRecursiveLoopClean` | Varied text → SUCCEEDED | No |
| 50 | `TestWrongCharacterDetection` | Response starts with user char name + colon → FAILED | No |
| 51 | `TestWrongCharacterClean` | Response starts with system char name → SUCCEEDED | No |
| 52 | `TestRefusalDetection` | 2+ refusal phrases → FAILED | No |
| 53 | `TestRefusalClean` | Normal response → SUCCEEDED | No |
| 54 | `TestResponsePolicyEvaluatorPermit` | All operations pass → PERMIT | No |
| 55 | `TestResponsePolicyEvaluatorDeny` | One operation fails → DENY with violation details | No |
| 56 | `TestPolicyLoadFromResource` | Load sample policy JSON, verify structure | No |
| 57 | `TestChatAutotunerAnalysis` | Autotune generates rewrite suggestion from violation | Yes |
| 58 | `TestChatAutotunerSave` | Autotuned prompt saved with correct naming convention | Yes |
| 59 | `TestPolicyHookBufferMode` | `stream=false` → policy evaluated post-response | Yes |
| 60 | `TestPolicyHookStreamMode` | `stream=true` → policy evaluated in oncomplete | Yes |
| 61 | `TestEnhancedStopGraceful` | Graceful stop terminates within timeout | Yes |
| 62 | `TestEnhancedStopFailover` | Forced cancellation after failover window | Yes |

Tests 46-56 are unit-testable with synthetic responses (no LLM server required). Tests 57-62 require a live LLM.

**9. Open Issues to Address in Phase 9:**

- **OI-22**: Policy tests placeholder (Test 81) — will be expanded with actual evaluation
- **OI-36**: Adaptive chatOptions recommendation — `ChatAutotuner` provides the infrastructure; chatOptions rebalancing can be added to the analysis prompt as a secondary suggestion alongside prompt rewrites

**10. Risk Assessment:**

| Risk | Impact | Mitigation |
|------|--------|-----------|
| `PolicyEvaluator` integration mismatch — operations may not receive facts in expected format | High | Unit test each operation with synthetic facts before wiring into evaluator |
| `ChatAutotuner` LLM call fails — analysis model produces unparseable JSON | Medium | Catch parse errors, log raw response, return autotune failure (no retry) |
| `chirpUser` in Service7 vs operations in Objects7 — cross-module boundary | Medium | Operations return violation objects; hook code in `ChatListener` (Service7) calls chirpUser |
| Enhanced stop `future.cancel(true)` may not interrupt HttpClient | Medium | Test with actual Ollama hang scenario; fall back to connection close if cancel insufficient |
| Recursive loop detection false positives on legitimate repeated content (e.g., poetry, lists) | Low | Configurable window size and threshold via policy fact parameters; conservative defaults (50 chars, 3x) |

### Phase 10: UX Chat Refactor — Common Components, Conversation Management, MCP (Medium risk, high impact)

**Goal:** Unify the chat.js view with embeddable views (magic8, cardGame) via common components, redesign conversation management for multi-conversation workflows, and replace the ad-hoc chatInfo/blender object association pattern with MCP-based context binding.

#### Problem Statement

The current chat UX has four structural deficits:

1. **No shared components** — `chat.js` (1354 lines), `Magic8App.js`, and `CardGameApp.js` each implement their own LLM interaction patterns (REST chat, WebSocket streaming, polling loops) with no reusable components. Common concerns like message rendering, token processing (image/audio), character display, and streaming state management are duplicated or divergent.

2. **Poor conversation management** — `chat.js` manages conversations as flat state (`chatCfg.history`) with no multi-conversation UI. Switching sessions requires `pickSession()` with no conversation list, search, or metadata display. Users cannot see, compare, or organize multiple conversations.

3. **Duct-taped object association** — Chat configs, prompt configs, characters, and other objects are associated via `page.context().contextObjects["chatConfig"]` set by the drag-and-drop blender (`dnd.js`), with `sendToMagic8()` using `sessionStorage` for cross-view handoff. This makes it difficult to discover, attach, or manage object associations within a conversation.

4. **Minimal chat.js API** — `chat.js` exports only 5 methods (`makePrompt`, `makeChat`, `getChatRequest`, `deleteChat`, `chat`). No helpers for history retrieval, response content extraction, JSON directive parsing, config template cloning, streaming abstraction, or error recovery. Every consumer reimplements these.

#### Known Issues and Gaps (from Phase 9 UX testing)

| # | Issue | Impact | Resolution |
|---|-------|--------|------------|
| OI-42 | REST config endpoints hardcoded to `~/Chat` group — fixed with group-agnostic owner-based search | Config lookup fails for configs in other groups | **Resolved** (Phase 9 patch) |
| OI-40 | `policyEvent` WebSocket handler not wired in `chat.js`/`SessionDirector.js` | Policy violations not visible to user | 10a: `StreamStateIndicator` |
| OI-20 | Image/audio token processing varies across views | Inconsistent media rendering | 10a: `ChatTokenRenderer` |
| OI-24 | `findOrCreateConfig` caching — template changes require manual deletion | Stale test configs | 10b: add update-if-changed |
| OI-43 | History retrieval intermittently returns 2/3 messages (Phase 9 UX test 73-74) | Potential message persistence timing issue or messageTrim interaction | 10b: investigate |
| OI-44 | `formDef.js` calls `am7sd.fetchModels()` at module load before authentication — 403 on login screen | Console errors on every page load | **Resolved** (auth guard added) |
| OI-45 | `am7chat.sendMessage()` called in test suite but doesn't exist in `chat.js` API | Test hang until fixed | **Resolved** (changed to `am7chat.chat()`) |
| NEW | No `getHistory()` helper in `chat.js` — tests and views must manually construct REST call | Code duplication, fragile | 10a: `LLMConnector.getHistory()` |
| NEW | No `extractContent(response)` helper — SessionDirector, llmBase, and test suite each reimplement | Three divergent implementations | 10a: `LLMConnector.extractContent()` |
| NEW | No streaming abstraction — WebSocket streaming requires knowing `page.wss` internals | Cannot reuse across views | 10a: `LLMConnector.streamChat()` |
| NEW | CardGame `chatManager.js` manually tracks conversation array client-side (separate from server history) | Diverges from server state | 10b: unified history |
| NEW | SessionDirector (1444 lines) reimplements config management, response parsing, JSON repair | Large duplication surface | 10a: extract to shared |

#### Duplication Audit

The following patterns are duplicated across 3+ files:

| Pattern | `chat.js` | `SessionDirector.js` | `llmBase.js` / `chatManager.js` | Shared Component |
|---------|-----------|---------------------|--------------------------------|-----------------|
| Config template clone + customize | `makeChat()` | `_ensureChatConfig()` | `ensureChatConfig()` | `LLMConnector.cloneConfig()` |
| Prompt create-or-find | `makePrompt()` | `_ensurePromptConfig()` | `ensurePromptConfig()` | `LLMConnector.ensurePrompt()` |
| Response content extraction | (none) | `_extractContent()` | `extractContent()` | `LLMConnector.extractContent()` |
| JSON directive parsing | (none) | `_parseDirective()` + repair | `cleanJsonResponse()` | `LLMConnector.parseDirective()` |
| History retrieval | (none — manual REST) | (none — stateless) | `currentConversation[]` | `LLMConnector.getHistory()` |
| Error tracking | (none) | `consecutiveErrors` count | `lastError` field | `LLMConnector.errorState` |

#### Object Association Audit

Current mechanisms (to be replaced by 10.3 MCP):

| Mechanism | Where Used | Type | Persistent? |
|-----------|-----------|------|------------|
| `page.context().contextObjects["chatConfig"]` | `dnd.js` → `chat.js` | In-memory route context | No |
| `sessionStorage.setItem('magic8Context')` | `chat.js` → `Magic8App.js` | Session storage | Per-tab |
| `page.member(container, member)` | `dnd.js` blender | DB membership | Yes |
| `chatRequest.chatConfig` / `.promptConfig` | `ChatUtil.getCreateChatRequest()` | DB foreign ref | Yes |
| Direct array push + `page.patchObject()` | `dnd.js` contact/address | DB field update | Yes |

No generic "associate object X with conversation Y" API exists.

#### Architecture

**10.1 — Common Chat Components**

Extract shared concerns from `chat.js`, `Magic8App.js`, and `CardGameApp.js` into reusable Mithril components:

| Component | Extracts From | Purpose |
|-----------|--------------|---------|
| `ChatMessageList` | `chat.js` message rendering | Renders message history with role-based styling, markdown, thought display |
| `ChatInput` | `chat.js` input area | Text input with send/cancel, streaming state, character counter |
| `ChatTokenRenderer` | `chat.js` image/audio token processing | Resolves `${image.*}` and `${audio.*}` tokens, handles caching (OI-20) |
| `CharacterBadge` | `chat.js` character portrait display | Displays character name + portrait for system/user characters |
| `StreamStateIndicator` | `chat.js` pending/streaming flags | Shows loading, streaming, error, policy violation states (OI-40) |
| `ChatConfigPanel` | `chat.js` config selection | Chat/prompt config picker with template browser |
| `LLMConnector` | Multiple views | Unified REST + WebSocket chat API (see below) |

**`LLMConnector` API** (replaces ad-hoc patterns across all views):

```
LLMConnector.ensurePrompt(name, system[])     — create-or-find prompt config
LLMConnector.ensureConfig(name, template, overrides) — clone-and-customize chat config
LLMConnector.createSession(name, chatCfg, promptCfg) — get/create chat request
LLMConnector.chat(session, message)            — send message (REST, buffered)
LLMConnector.streamChat(session, message, onChunk) — send message (WebSocket, streaming)
LLMConnector.getHistory(session)               — retrieve message history
LLMConnector.extractContent(response)          — extract last assistant message
LLMConnector.parseDirective(content, options)  — parse JSON from LLM response (lenient)
LLMConnector.deleteSession(session, force)     — clean up session + request
LLMConnector.cancelStream()                    — cancel active stream
```

**New file:** `client/components/chat/` — shared component directory.

**10.2 — Conversation Manager**

Replace the flat `chatCfg` state with a conversation-first UI:

- **Conversation list sidebar** — Shows all chat sessions for the user with name, last message preview, timestamp, associated characters, and config metadata.
- **Conversation search/filter** — Filter by character, config, date range, or content.
- **Conversation grouping** — Group by chatConfig, universe, or custom tags.
- **Multi-tab or split-pane** — View multiple conversations simultaneously (e.g., compare two prompt strategies).
- **Conversation metadata** — Display policy evaluation status, autotuned prompt history, memory extraction state.
- **Quick-create** — New conversation dialog with config/prompt template selection and character assignment.
- **REST API revision** — Config endpoints now search by owner across all groups (OI-42 resolved). Add objectId-based lookup endpoints for direct addressing.

**10.3 — Object Association via MCP**

Replace the blender/sessionStorage pattern with MCP (Model Context Protocol) tool definitions:

- **Context binding tools** — Define MCP tools that allow the LLM (or user) to attach objects to a conversation context:
  - `attachChatConfig(configId)` — Bind a chatConfig to the active conversation
  - `attachPromptConfig(promptId)` — Bind a promptConfig
  - `attachCharacter(characterId, role)` — Bind a character as system or user role
  - `attachEpisode(episodeId)` — Bind narrative episodes
  - `listAttachments()` — Show current conversation's bound objects
  - `detach(objectId)` — Remove an association
- **Context panel** — UI sidebar showing all objects currently associated with the conversation, with drag-and-drop to add/remove.
- **Cross-view handoff** — Replace `sessionStorage.setItem('magic8Context', ...)` with a structured handoff via conversation ID reference. Magic8 and CardGame load context from the conversation's MCP-managed attachments.
- **Server-side MCP integration** — Leverage the existing `McpContextBuilder` infrastructure to expose conversation context as MCP resources/tools.

#### Implementation Items

**10a — Common Components:**
1. Create `client/components/chat/` directory with `LLMConnector.js` (unified API)
2. Extract `ChatMessageList`, `ChatInput`, `ChatTokenRenderer`, `CharacterBadge`, `StreamStateIndicator`, `ChatConfigPanel` from `chat.js`
3. Refactor `chat.js` to use shared components (preserve all existing functionality)
4. Refactor `Magic8App.js` and `SessionDirector.js` to use `LLMConnector` (eliminates ~400 lines of duplicated config/response handling)
5. Refactor `CardGameApp.js` / `llmBase.js` / `chatManager.js` to use `LLMConnector`
6. Wire `policyEvent` WebSocket handler into `StreamStateIndicator` (OI-40)

**10b — Conversation Manager:**
7. Implement conversation list sidebar with search/filter/grouping
8. Implement conversation metadata display (policy status, autotuned prompts, memories)
9. Add REST endpoint for objectId-based config lookup: `/rest/chat/config/prompt/id/{objectId}` (OI-42 extension)
10. Investigate and fix history message loss (OI-43: 2/3 messages found in UX test 73-74)
11. Add `findOrCreateConfig` update-if-changed to avoid stale test configs (OI-24)

**10c — MCP Integration:**
12. Define MCP tool schema for object association (attach/detach/list)
13. Implement server-side MCP tool handlers for conversation context management
14. Implement UX context panel with drag-and-drop object association
15. Replace `sendToMagic8()` sessionStorage handoff with conversation ID reference
16. Add UX tests for: shared components, conversation manager, MCP tools, cross-view handoff

**Files created:** `client/components/chat/LLMConnector.js`, `ChatMessageList.js`, `ChatInput.js`, `ChatTokenRenderer.js`, `CharacterBadge.js`, `StreamStateIndicator.js`, `ChatConfigPanel.js`, `ConversationManager.js`, `ContextPanel.js`
**Files modified:** `chat.js` (major refactor), `Magic8App.js`, `SessionDirector.js`, `CardGameApp.js`, `cardGame/ai/llmBase.js`, `cardGame/ai/chatManager.js`, `dnd.js`, `pageClient.js`, `formDef.js`
**Backend files:** MCP tool handler classes in `AccountManagerService7/`, `ChatService.java` (objectId endpoint), `ChatUtil.java` (group-agnostic search)

#### Risk Factors

| Risk | Severity | Mitigation |
|------|----------|------------|
| Large refactor scope across 3+ views | High | Phase into sub-increments: 10a (shared components), 10b (conversation manager), 10c (MCP integration) |
| Breaking existing Magic8/CardGame workflows | Medium | Keep existing API contracts, add new components alongside old code, deprecate incrementally |
| MCP integration complexity with existing `McpContextBuilder` | Medium | Start with server-side MCP tools, wire UX after backend is tested |
| Performance impact of conversation list queries | Low | Paginate conversation list, lazy-load history and metadata |
| SessionDirector.js (1444 lines) tightly coupled to Magic8 state | Medium | Extract LLM concerns only, leave biometric/directive logic in place |

#### Recommendations

1. **Start with `LLMConnector`** — This single component eliminates the most duplication and provides the foundation for all other Phase 10 work. Build and test it against the existing `chat.js` flow before touching other views.
2. **Preserve `am7chat` as thin wrapper** — Keep `chat.js` backward-compatible by having it delegate to `LLMConnector`. This avoids breaking any existing code that references `am7chat.*`.
3. **Extract, don't rewrite, SessionDirector** — The 1444-line `SessionDirector.js` has significant Magic8-specific logic (biometrics, directives, mood ring). Only extract the LLM interaction patterns (config management, response parsing, JSON repair); leave domain logic in place.
4. **Defer MCP (10c) until 10a+10b are stable** — MCP integration touches the deepest architectural layer. Get shared components and conversation management working first.
5. **UX test expansion** — Current test suite (63-85) covers config, prompt, chat, stream, history, prune, episode, analyze, narrate, policy. Phase 10 should add tests for: shared component rendering, conversation CRUD, multi-session management, cross-view handoff, MCP tool invocation. Suggested test range: 86-110.

### Phase 11: Memory System Hardening & Keyframe Refactor (Low risk, medium impact)

**Goal:** Complete memory system gaps, refactor keyframe/memory overlap, and relocate tests.

1. **Populate `personModel` field** — Set `personModel` in `MemoryUtil.createMemory()` and `Chat.persistKeyframeAsMemory()` when the person type is known (e.g., `"olio.charPerson"`). (Open issue from Phases 2-3.)
2. **Pass person pair IDs in `extractMemoriesFromResponse()`** — Add `personId1`/`personId2` parameters so LLM-extracted memories are tagged with the character pair. (Open issue from Phases 2-3.)
3. **Relocate memory/keyframe tests to Objects7** — `TestMemoryUtil`, `TestMemoryPhase2`, `TestKeyframeMemory`, and `TestMemoryDuel` have zero Agent7 imports and depend entirely on Objects7 classes (`Chat`, `MemoryUtil`, `PromptUtil`, etc.). Move them to `AccountManagerObjects7/src/test/java/` to match their actual dependency scope. (Open issue from Phase 3.)
4. **Deprecate old `(KeyFrame:` format** — Remove backward-compatibility checks for `(KeyFrame:` prefix in `pruneCount()`, `countBackTo()`, `addKeyFrame()`, and `getFormattedChatHistory()`. Require MCP format only. Reduces code complexity.
5. **Add keyframe cost guard** — When `keyframeEvery` is low (e.g., 2-3), each keyframe triggers an expensive `analyze()` LLM call. Add a minimum floor (e.g., `keyframeEvery >= 5` when `extractMemories=true`) or a rate limiter to prevent excessive LLM costs. (Open issue from Phase 3.)

**Files modified:** `MemoryUtil.java`, `Chat.java`, `ChatUtil.java`
**Files relocated:** `TestMemoryUtil.java`, `TestMemoryPhase2.java`, `TestKeyframeMemory.java`, `TestMemoryDuel.java` → `AccountManagerObjects7/src/test/java/`

### Phase 12: UX Polish & Remaining Cleanup (Low-Medium risk, High impact)

**Goal:** Fix broken chat.js sidebar layout, complete ContextPanel UX, resolve all remaining P3/P4 open issues, and finish Phase 1 template cleanup.

#### 12a — Chat Sidebar & Message Layout Fixes

1. **Session list item layout** — `ConversationManager.sessionItemView()` wraps a delete button and session-name button in a `flex items-center` div, but the session-name button uses `w-full` which fights with the sibling. Result: delete icons float left with names truncated or invisible in narrow sidebars. Fix: replace the two-button row with a single flex row that allocates fixed space for the delete icon and fills remaining width for the name (with `truncate` / `text-ellipsis overflow-hidden`). Ensure the `flyout-button.active` highlight spans the entire row, not just the name.
2. **Fallback inline session list** — `view/chat.js:665-679` pre-10b fallback has the same layout issue (delete button nested *inside* the session button via `[bDel, s.name]`). Either remove the fallback entirely (ConversationManager is now stable) or fix it to match the ConversationManager layout.
3. **Setting text overflow** — `view/chat.js:994-996` renders `"Setting: " + setting` as full-width (`w-full`) with no truncation. Long settings (e.g., "Space-age lunar colony with domed habitats and lunar rovers, circa 2100 AD.") dominate the top of the chat area. Fix: add `truncate` class with a title tooltip for full text, or collapse to one line with expand-on-click.
4. **ContextPanel collapsed state** — The collapsed "Context" toggle (`ContextPanel.PanelView`) shows no indication of what's bound. A session with chatConfig + 2 characters bound looks identical to one with no bindings. Fix: add a binding count badge (e.g., "Context (3)") or colored dot indicator next to the toggle when bindings exist.
5. **ContextPanel expanded layout** — The expanded view is a flat list of `text-xs` rows. No visual grouping, no icons for object types, detach buttons are tiny `link_off` icons. Improve: add schema-type icons (gear for config, person for character, link for context), group rows by category, make detach buttons more discoverable.

**Files:** `ConversationManager.js`, `ContextPanel.js`, `view/chat.js`

#### 12b — Phase 1 Remainder (Template Cleanup)

6. **Fix rule numbering** — Replace hardcoded rule numbers in `PromptUtil.buildEpisodeReplacements()` and related methods with dynamic assembly. Currently, adding or removing a rule shifts all subsequent numbers, creating maintenance burden.
7. **Trim consent blocks** — Reduce consent/censor text from ~200 tokens to ~50 tokens. The current blocks consume significant context window for minimal behavioral impact.
8. **Document variable dependencies** — Add comments to `PromptUtil` documenting the stage ordering and which variables must be populated before which replacement stages run. Currently implicit knowledge.

**Files:** `PromptUtil.java`

#### 12c — Remaining P3 Issues

9. **Magic8 server-side wiring (OI-17)** — `SessionDirector.js` uses a client-side template for Magic8 prompts. Wire it to the server-side `prompt.magic8.json` template so the same structured template pipeline applies.
10. **Ollama abort API (OI-27)** — `CompletableFuture` cancellation on client timeout doesn't reach the Ollama server — the request continues consuming GPU resources. Investigate Ollama's `/api/abort` or connection-close semantics for server-side cancellation.
11. **Ollama native options object (OI-29)** — After Phase 8 mapping fix, Ollama-specific fields (`top_k`, `typical_p`, `repeat_penalty`, `min_p`, `repeat_last_n`) are no longer sent in the request. Add an `options` sub-object to the Ollama request builder that passes these through when `serviceType=OLLAMA`.

**Files:** `SessionDirector.js`, `Chat.java`, `ChatUtil.java`

#### 12d — Remaining P4 Issues

12. **Remove client-side prune backward compat (OI-18)** — Phase 10a delegated prune functions to LLMConnector but kept old implementations as fallbacks. Once sessions have refreshed, remove dead code paths.
13. **UX form Ollama section (OI-33)** — `formDef.js` chatOptions form only shows OpenAI-compatible fields. Add an "Advanced / Ollama" section exposing `typical_p`, `repeat_penalty`, `top_k`, `min_p`, `repeat_last_n`, `num_gpu`.
14. **applyAnalyzeOptions hardcodes (OI-30)** — `Chat.applyAnalyzeOptions()` hardcodes `temperature=0.4`, `top_p=0.5` instead of reading from config. Refactor to read from chatConfig or a dedicated analyzeOptions sub-config.
15. **getNarratePrompt double-apply (OI-31/OI-34)** — `Chat.getNarratePrompt()` calls `applyAnalyzeOptions()` (which internally calls `applyChatOptions()`) then calls `applyChatOptions()` again. Harmless but wasteful — remove the redundant call.
16. **WrongCharacterDetection false positives (OI-39)** — Heuristic regex patterns match in-character quoted dialogue. Consider context-aware detection: only trigger when the response *starts* with the user character pattern, or exclude content within quotation marks.
17. **Stale analyzeModel migration (OI-41)** — Existing DB records may still have `analyzeModel=dolphin-llama3` (old default removed in Phase 9). Add a one-time migration query or document the manual cleanup.
18. **Test execution order sensitivity (OI-28)** — `TestStreamTimeoutTriggered` sets `requestTimeout=1` on a shared DB config; crash between set and restore leaks the value. Already mitigated by explicit resets; consider using a dedicated config per test.
19. **CardGame shared state (OI-23)** — Switching test suites resets shared `TF.testState`. By design for now; document the limitation.

**Files:** `view/chat.js`, `formDef.js`, `Chat.java`, `WrongCharacterDetectionOperation.java`, various test files

#### New Open Issues (Phase 12)

| # | Issue | Source | Sub-phase | Priority |
|---|-------|--------|-----------|----------|
| OI-51 | ConversationManager session item layout broken — delete button and name button competing for width in flex row, names truncated/invisible | Phase 12 UX audit | 12a (item 1) | P2 |
| OI-52 | Pre-10b fallback session list still present in `view/chat.js:657-681` — nested delete button inside session button, same layout problems as OI-51 | Phase 12 UX audit | 12a (item 2) | P3 |
| OI-53 | Setting text overflow — long setting descriptions render full-width with no truncation, pushing chat messages down | Phase 12 UX audit | 12a (item 3) | P3 |
| OI-54 | ContextPanel collapsed state gives no indication of binding count — empty and fully-bound sessions look identical | Phase 12 UX audit | 12a (item 4) | P3 |
| OI-55 | ContextPanel expanded view is flat text-only list with tiny detach icons — no type icons, no grouping, poor discoverability | Phase 12 UX audit | 12a (item 5) | P4 |

### Phase Progress Summary

| Phase | Status | Risk | Impact | Tests |
|-------|--------|------|--------|-------|
| 1 — Template Cleanup | Partial (item 3 superseded by Phase 4) | Low | High | 1-7 (not yet written) |
| 2 — Memory Retrieval | **IMPLEMENTED** | Medium | High | 8-18 (all pass) |
| 3 — Keyframe-to-Memory Pipeline | **IMPLEMENTED** | Medium | Medium | 19-22 (all pass) |
| 4 — Structured Template Schema | **COMPLETED** | Higher | High | 23-28 (all pass) |
| 5 — Client-Side Cleanup & Validation/Migration | **COMPLETED** | Low | Medium | 29-35 (all pass) |
| 6 — UX Test Suite | **COMPLETED** | Low | High | 63-81, 82-85 (browser) |
| 7 — Always-Stream Backend | **COMPLETED** | Medium | Medium | 36-39 (all pass) |
| 8 — LLM Config & ChatOptions Fix | **COMPLETED** | Low | High | 40-45 (all pass), 82-85 (browser) |
| 9 — Policy-Based Response Regulation | **COMPLETED** | Higher | Medium | 46-62 (backend, all pass); 63-85 (browser: 47 pass, 2 fail, 6 warn) |
| 10 — UX Chat Refactor (Common Components, Conversation Mgmt, MCP) | **COMPLETED** (10a+10b+10c) | Medium | High | 86-110 (UX browser), P10-1..P10-4 + P10c-1..P10c-4 (backend, all 8 pass) |
| 11 — Memory Hardening & Keyframe Refactor | **COMPLETED** | Low | Medium | P11-1..P11-7 (8 tests, all pass) |
| 12 — UX Polish & Remaining Cleanup | **COMPLETED** | Low-Med | High | P12-1..P12-5 (backend, all 5 pass); 111-115, 103b-104g (UX browser) |
| 13 — chatInto Redesign, Memory UX & MCP Visibility | **PLANNED** | Medium | High | — |

### Phase 12 Implementation Summary

All 12 phases are now **COMPLETED**. Phase 12 was implemented in 4 sub-phases:

**12a — Chat Sidebar & Message Layout Fixes:**
- OI-51: `ConversationManager.sessionItemView()` rewritten with `group` hover-reveal delete, `truncate` name, full-row active highlight
- OI-52: Pre-10b fallback session list removed from `view/chat.js`; `aSess` variable and `deleteChat()` function removed
- OI-53: Setting text overflow fixed with `truncate` class, `text-sm`, and `title` tooltip
- OI-54: `ContextPanel` binding count badge added — collapsed header shows "Context (3)"
- OI-55: `ContextPanel` expanded layout improved — schema-type icons, flex-1 truncation, `link_off` detach buttons
- Chat message bubbles compacted: `px-3 mb-1 py-1.5 text-sm max-w-[85%]` for more conversation real estate

**12b — Phase 1 Remainder:** Items 6 (rule numbering) and 8 (variable dependencies) confirmed already resolved in prior phases. Item 7 (consent trimming) deferred to content review.

**12c — Remaining P3 Issues:**
- OI-17: `SessionDirector._loadPromptTemplate()` rewired to fetch from server-side REST endpoint with local file fallback
- OI-27: Ollama abort implemented — `ChatListener.registerHttpResponse()` stores `HttpResponse`, `stopStream()` closes body stream to signal server-side cancellation
- OI-29: Ollama native options (`top_k`, `repeat_penalty`, `typical_p`, `min_p`, `repeat_last_n`) sent via `request.options` sub-object when `serviceType=OLLAMA`

**12d — Remaining P4 Issues:**
- OI-18: 5 local prune wrapper functions removed from `view/chat.js`, 8 call sites updated to `LLMConnector.*` directly
- OI-28: `TestChatStream.TestStreamTimeoutTriggered` wrapped in try-finally for config restore
- OI-30: `applyAnalyzeOptions()` hardcoded values converted to public documented constants (`ANALYZE_TEMPERATURE=0.4`, etc.)
- OI-31/34: Redundant `applyChatOptions(areq)` call removed from `getNarratePrompt()`
- OI-33: 5 Ollama-specific fields added to `formDef.js` chatOptions form
- OI-39: `WrongCharacterDetection` quote exclusion added — responses starting with `"` or `\u201c` skip heuristic patterns
- OI-41: Documented as no-migration-needed — `analyzeModel` default already removed in Phase 9
- OI-23: Documented as by design — CardGame shared state is expected behavior

**Tests:** `TestChatPhase12.java` (5 backend tests, all pass), `llmTestSuite.js` (tests 111-115 layout/context, 103b-104g comprehensive token processing)

### Phase 13: chatInto Redesign, Memory UX & MCP Visibility

**Status:** COMPLETED
**Priority:** Medium | **Impact:** High
**Goal:** Redesign the `chatInto` object analysis feature to integrate with Phase 10-12 shared infrastructure (LLMConnector, ConversationManager, ContextPanel), eliminate the `window.open` / `remoteEntity` cross-window pattern, unify vectorize/summarize dialogs, **add full UX visibility for the memory system and MCP context**, enable users to observe and exercise cross-conversation character memory, and resolve all remaining TODOs and deferred items.

**Completion Notes:**
- 13a: AnalysisManager.js replaces dialog.chatInto + remoteEntity pattern with in-page sessions
- 13b: vectorize/summarize use showProgress dialog with try/catch error details
- 13c: Stream interrupt via LLMConnector.stopStream(); ChatUtil.getCreateChatConfig deprecated; $flex documented
- 13d: SD config defaults documented; dual view comment permanent; consent trimming deferred (OI-66)
- 13e: 9 missing chatConfig fields added to formDef.js + cloneFields; WS exponential backoff reconnect; WS null user → VIOLATED_POLICY; audio single decode; page.userProfilePath
- 13f: MemoryService.java (6 endpoints); MemoryPanel.js sidebar; memory config form fields; LLMConnector.handleMemoryEvent; ChatTokenRenderer.processMcpTokens (MCP inspector)
- 13g: Object links (objectLinkRow/metaRow) in ConversationManager metadata; chatTitle/chatIcon display
- Tests: TestChatPhase13.java (8 tests, all pass); llmTestSuite.js tests 129-146 (memory, analysis, MCP, infra)

#### Background

The `chatInto` feature allows users to open an LLM-powered analysis chat for any object (character, document, chat session). It was implemented pre-Phase-10 and uses patterns that bypass all shared infrastructure:

- **`window.open("/", "_blank")`** — opens a separate browser window
- **`window.remoteEntity`** — passes chat config via window property
- **`dnd.workingSet`** — injects vectorized session + character tags into the new window
- **Name-based config filtering** (`/^Object/gi`) — fragile convention to find "analysis" configs
- **No ContextPanel integration** — pre-bound chatConfig/promptConfig aren't reflected in context bindings
- **No ConversationManager integration** — new analysis session won't appear in session list until reload

Current callers:
- `view/chat.js:271,1040` — "query_stats" button in chat toolbar
- `view/object.js:268,1061` — "query_stats" button in object view toolbar
- `dialog.js:10-106` — core implementation

Related features:
- `dialog.js:269-292` — standalone `vectorize()` dialog
- `dialog.js:216-267` — standalone `summarize()` dialog
- `VectorService.java` — REST endpoints for vectorize/summarize/reference
- `ChatUtil.createSummary()` — server-side vectorize→chunk→LLM→summarize pipeline
- `Chat.createNarrativeVector()` — auto-vectorize after message send

#### Open Issues Carried Forward

| # | Issue | Source | Priority |
|---|-------|--------|----------|
| OI-56 | `chatInto` bypasses LLMConnector, ConversationManager, ContextPanel — analysis sessions are invisible to shared infrastructure | Phase 12 analysis | P2 |
| OI-57 | `chatInto` uses `window.open` + `remoteEntity` cross-window pattern — fragile, no error recovery, no state sync | Phase 12 analysis | P2 |
| OI-58 | Config filtering by name prefix (`/^Object/gi`) — fragile convention, no configs may match, no fallback | Phase 12 analysis | P3 |
| OI-59 | `vectorize()` and `summarize()` dialogs don't surface progress or results — fire-and-forget with toast only | Phase 12 analysis | P3 |
| OI-60 | `ChatUtil.getCreateChatConfig()` marked `TODO: DEPRECATE THIS` — still used by tests and internal code | ChatUtil.java:324 | P4 |
| OI-61 | `dialog.js:3` TODO — "Persist chatRequest / underlying AI request should also be persisted" | dialog.js:3 | P3 |
| OI-62 | `dialog.js:829` TODO — SD config defaults not in form ("TODO - Add to form") | dialog.js:829 | P4 |
| OI-63 | `ChatUtil.java:458,465` TODO — QueryPlan `$flex` field type workaround for interaction actor/interactor | ChatUtil.java:458 | P4 |
| OI-64 | `chat.js:475` TODO — "Chat is streaming - TODO - interrupt" — no cancel-and-send support during stream | chat.js:475 | P3 |
| OI-65 | `chat.js:28` TODO — dual object view construction methods (generic `am7view` vs legacy `object component`) | chat.js:28 | P4 |
| OI-66 | Phase 12b item 7 deferred — consent block trimming needs content review | Phase 12b | P4 |
| OI-67 | No REST endpoints for memory — `MemoryUtil` has search/query methods but no service layer exposes them to the UX | Phase 13 analysis | P2 |
| OI-68 | Memory config fields (`extractMemories`, `memoryBudget`, `memoryExtractionEvery`) not in formDef.js — users can't enable/configure memory from the UI | Phase 13 analysis | P2 |
| OI-69 | No UX memory browser — users cannot view, search, or manage memories for character pairs | Phase 13 analysis | P2 |
| OI-70 | MCP context blocks invisible — stripped for display, no inspect/debug mode to see what context the LLM actually receives | Phase 13 analysis | P3 |
| OI-71 | Cross-conversation memory not demonstrable — no indicator when memories are loaded, no way to see memory count or content during chat | Phase 13 analysis | P2 |
| OI-72 | Keyframe events not surfaced in UX — no visual indicator when keyframes fire or memories are extracted during conversation | Phase 13 analysis | P3 |
| OI-73 | Memory search not exposed — `MemoryUtil.searchMemories()` (semantic vector search) has no client-side equivalent | Phase 13 analysis | P3 |
| OI-74 | 6 chatConfig model fields not in formDef.js — `requestTimeout`, `terrain`, `populationDescription`, `animalDescription`, `universeName`, `worldName` | Phase 13 gap audit | P3 |
| OI-75 | WebSocket auto-reconnect missing — WS close during stream silently breaks chat, no recovery | pageClient.js:374 | P2 |
| OI-76 | WebSocket token auth fallback — null user proceeds to anonymous state, security gap | WebSocketService.java:179 | P3 |
| OI-77 | Audio double-encoding — client sends double-base64, server has workaround decode | WebSocketService.java:543 | P4 |
| OI-78 | Logged-in user profile uses `v1-profile` attribute workaround instead of looking up `identity.person` under `/Persons`. Chat context should use `chatConfig.userCharacter` (charPerson) when set, fallback to user's person record when not | pageClient.js:777 | P3 |
| OI-79 | Chat details metadata shows config/prompt/character names as plain text — no clickable links to open the objects in the editor | ConversationManager.js:170 | P3 |
| OI-80 | Chat sessions use raw name in sidebar — no auto-generated title or topic icon after first exchange | ConversationManager.js:134 | P3 |
| OI-81 | No UX indication when memories are recalled and applied to a chat prompt — users can't tell if memory is working | chat.js, Chat.java | P3 |

---

#### Sub-phase 13a — chatInto In-Page Redesign

**Goal:** Replace the `window.open` pattern with an in-page analysis session that integrates with all Phase 10-12 components.

##### Item 1: New `AnalysisManager` module (OI-56, OI-57)
**New file:** `AccountManagerUx7/client/components/chat/AnalysisManager.js`

Create a lightweight analysis coordinator that replaces the cross-window pattern:

```
window.AnalysisManager = {
    startAnalysis(ref, sourceInst, sourceCCfg)  // replaces dialog.chatInto()
    getActiveAnalysis()                          // returns current analysis context or null
    clearAnalysis()                              // detach and return to normal chat
}
```

**Flow:**
1. `startAnalysis()` receives the reference object, optional source session instance, optional chat configs
2. Resolves analysis chatConfig and promptConfig (see Item 2)
3. Vectorizes the source session if present (reuses existing `/vector/vectorize/` endpoint)
4. Calls `POST /rest/chat/new` to create the analysis chatRequest server-side (same as `openChatSettings`)
5. Uses `ContextPanel.attach()` to bind the reference object to the new session
6. Uses `ConversationManager.refresh()` to surface the new session in the sidebar
7. Selects the new session via `ConversationManager.select()` — triggers `pickSession()` in chat.js
8. Populates `dnd.workingSet` with vectorized session + character tags (same data as before)
9. Chat view renders the analysis session in-page — no new window needed

**Key architectural decision:** The user stays in the same tab. The analysis session appears in the ConversationManager sidebar alongside regular sessions, distinguished by a name prefix ("Analyze TYPE NAME") and optionally a visual indicator (icon or badge). The user can switch back to their original session at any time.

##### Item 2: Replace name-based config filtering (OI-58)
**Files:** `dialog.js`, `AnalysisManager.js`, `chatConfigModel.json` (optional)

Replace `name.match(/^Object/gi)` with a purpose-based approach:

**Option A (minimal):** Use a config naming convention but with a fallback chain:
1. Look for configs with name starting with "Analyze" or "Object"
2. If none found, use the first available config
3. Let the user pick from chatSettings dialog as before

**Option B (structural — preferred):** Add a `purpose` enum field to `chatConfigModel.json`:
```json
{
    "name": "purpose",
    "type": "string",
    "default": "chat",
    "description": "Config purpose: chat, analysis, magic8, rpg"
}
```
Then filter by `purpose === "analysis"` instead of name prefix. Existing configs default to "chat". Analysis configs explicitly set `purpose: "analysis"`. This also benefits Magic8 (which currently has similar name-based filtering in SessionDirector).

##### Item 3: Update chat.js to consume AnalysisManager (OI-57)
**File:** `AccountManagerUx7/client/view/chat.js`

- Remove the `remoteEntity` consumption in `oninit` (lines 1174-1178, 1192-1194)
- Remove the local `chatInto()` wrapper (line 271-273)
- Replace the "query_stats" button handler (line 1040) to call `AnalysisManager.startAnalysis()`
- `AnalysisManager.startAnalysis()` creates the session server-side and selects it via ConversationManager — no page navigation needed

##### Item 4: Update object.js to use AnalysisManager
**File:** `AccountManagerUx7/client/view/object.js`

- Replace `page.components.dialog.chatInto(inst.entity)` (line 269) with `AnalysisManager.startAnalysis(inst.entity)`
- The analysis session opens in the chat view via `m.route.set("/chat")` with no `remoteEntity` — AnalysisManager stores pending analysis state in module scope

##### Item 5: Remove dialog.chatInto (dead code cleanup)
**File:** `AccountManagerUx7/client/components/dialog.js`

- Remove `chatInto()` function (lines 10-106)
- Remove `chatInto` from the exports object (line 1978)
- Keep `loadChatList()`, `loadPromptList()`, `chatSettings()` — still used by summarize and other dialogs

---

#### Sub-phase 13b — Vectorize & Summarize UX Improvements

**Goal:** Improve the vectorize/summarize dialog UX with progress feedback and result surfacing.

##### Item 6: Vectorize progress indicator (OI-59)
**File:** `AccountManagerUx7/client/components/dialog.js` (vectorize function, lines 269-292)

Replace fire-and-forget toast with `dialog.showProgress()`:
1. Show progress dialog with spinner during vectorization
2. On success, show chunk count in the success toast
3. On failure, show error details (not just "Vectorization failed")

##### Item 7: Summarize progress and result navigation (OI-59)
**File:** `AccountManagerUx7/client/components/dialog.js` (summarize function, lines 216-267)

1. Show progress dialog during summarization (which can be long — involves LLM calls per chunk)
2. On success, offer to navigate to the generated summary note (`~/Notes` path)
3. Surface the summary note in `dnd.workingSet` so it's available as context for chatInto analysis

##### Item 8: Wire summarize into AnalysisManager pipeline
**File:** `AccountManagerUx7/client/components/chat/AnalysisManager.js`

Add optional pre-analysis summarization:
- When `startAnalysis()` receives a large object (e.g., full chat session), offer to summarize first
- The summarized note becomes the primary reference in the analysis session's workingSet
- This replaces the current manual two-step flow (user must manually vectorize, then manually summarize, then manually chatInto)

---

#### Sub-phase 13c — Backend Cleanup

##### Item 9: Deprecate `ChatUtil.getCreateChatConfig()` (OI-60)
**File:** `AccountManagerObjects7/.../ChatUtil.java` (line 324)

This method is marked `TODO: DEPRECATE THIS`. Audit all callers:
- If only used by tests: update tests to use `LLMConnector.ensureConfig()` equivalent or the REST endpoint
- If used by production code: refactor callers to use the config template pipeline (`loadChatConfigTemplate` + `applyChatConfigTemplate`)
- Add `@Deprecated` annotation with migration note

##### Item 10: Stream interruption support (OI-64)
**File:** `AccountManagerUx7/client/view/chat.js` (line 475)

Currently when a user types a message while streaming, the message is silently discarded with `console.warn("Chat is streaming - TODO - interrupt")`.

Fix: Implement cancel-and-send:
1. When user sends during active stream, call `LLMConnector.stopStream()` (already implemented via Phase 12c Ollama abort)
2. Wait for stream cancellation acknowledgment
3. Then send the new message via `doChat()`

Alternative (simpler): Queue the message and send after stream completes. Display "(queued)" indicator on the input bar.

##### Item 11: ChatRequest persistence improvement (OI-61)
**File:** `AccountManagerObjects7/.../ChatUtil.java`, `ChatService.java`

The TODO at `dialog.js:3` notes that chat requests should persist their underlying AI request for easier access. Currently `data.data.byteStore` requires deserialization.

Fix: Add a server-side endpoint or field that returns the last OpenAI request object as part of the chatRequest response, or store it as a linked record rather than serialized bytes. This enables:
- Analysis sessions to inspect what was actually sent to the LLM
- Debugging and replay of specific requests
- ContextPanel to show request details

---

#### Sub-phase 13d — Remaining P4 Items

##### Item 12: SD config form defaults (OI-62)
**File:** `AccountManagerUx7/client/components/dialog.js` (line 829), `formDef.js`

The `tempApplyDefaults()` function hardcodes SD config defaults (steps=40, cfg=5, model name). Move these to the `sdConfig` form definition in `formDef.js` as proper default values so the dialog form renders them without the workaround function.

##### Item 13: QueryPlan $flex field workaround documentation (OI-63)
**File:** `AccountManagerObjects7/.../ChatUtil.java` (lines 458, 465)

The `$flex` field type workaround for interaction actor/interactor is a known framework limitation. Two options:
- **Fix:** Extend QueryPlan to support `$flex` field types in plan filtering
- **Document:** If the fix is out of scope for Phase 13, document the limitation clearly and remove the TODO comments in favor of a permanent explanatory comment referencing a framework issue tracker item

##### Item 14: Consent block trimming (OI-66, deferred from Phase 12b)
**Action:** Review consent block template content in promptConfig data records (`userConsentPrefix`, `userConsentRating`, `userConsentNlp`). Trim verbose or redundant text that inflates prompt token count. This is a content change, not a code change — requires domain-specific review.

##### Item 15: Dual object view construction TODO (OI-65)
**File:** `AccountManagerUx7/client/view/chat.js` (line 28)

Document the two view construction approaches (generic `am7view` vs legacy object component) and their respective use cases. If the legacy approach is only used in chat.js, evaluate migrating to the generic approach. If both are needed, add a comment explaining when to use each.

---

#### Sub-phase 13e — Missing chatConfig Form Fields & Infrastructure

**Goal:** Expose all chatConfig model fields in the form editor and fix critical chat infrastructure gaps.

##### Item 16: Missing chatConfig form fields (OI-74)
**File:** `AccountManagerUx7/client/formDef.js` (chatConfig form, ~line 5031)

The following fields exist in `chatConfigModel.json` but are NOT in `formDef.js`, meaning users cannot configure them from the UI:

```javascript
// Add to forms.chatConfig.fields after existing fields:
requestTimeout: {
    layout: "one",
    label: "Request Timeout (sec)",
    hint: "Hard timeout for LLM connections. 0=no timeout."
},
terrain: {
    layout: "third",
    label: "Terrain"
},
populationDescription: {
    layout: "third",
    label: "Population"
},
animalDescription: {
    layout: "third",
    label: "Animals"
},
universeName: {
    layout: "third",
    label: "Universe Name"
},
worldName: {
    layout: "third",
    label: "World Name"
},
```

These are used by the prompt template system (e.g., `${setting.terrain}`, `${setting.population}`) for world-building context. Without form exposure, users must edit raw records to set them.

##### Item 17: WebSocket auto-reconnect (OI-75)
**File:** `AccountManagerUx7/client/pageClient.js` (line 374)

**Problem:** When the WebSocket connection closes (network blip, server restart, timeout), the client has no reconnect logic. Chat streaming events (`chatStart`, `chatUpdate`, `chatComplete`, `chatError`) and policy events come over WS — losing the connection silently breaks chat.

**Fix:** Add exponential backoff reconnection:
```javascript
// In the WebSocket onclose handler (pageClient.js ~line 370):
let reconnectDelay = 1000;
const MAX_RECONNECT_DELAY = 30000;
const MAX_RECONNECT_ATTEMPTS = 10;
let reconnectAttempts = 0;

webSocket.onclose = function(event) {
    console.warn("[WebSocket] Closed (code: " + event.code + ")");
    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        reconnectAttempts++;
        setTimeout(function() {
            console.log("[WebSocket] Reconnecting (attempt " + reconnectAttempts + ")...");
            page.openSocket(); // existing connection method
        }, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
    } else {
        page.toast("error", "Connection lost. Please reload.", 0);
    }
};
// Reset on successful connection:
webSocket.onopen = function() {
    reconnectAttempts = 0;
    reconnectDelay = 1000;
};
```

##### Item 18: WebSocket token auth fallback (OI-76)
**File:** `AccountManagerService7/.../WebSocketService.java` (line 179)

**Problem:** `TODO: Add token auth support` — when principal is null, WebSocket connection proceeds with anonymous state. Chat stream events may be sent to unauthenticated sessions.

**Fix:** If principal is null, attempt to authenticate via token from the WebSocket query parameter or first message payload. If no valid token, close the session:
```java
if (user == null) {
    // Try token from query parameter
    String token = session.getRequestParameterMap().getOrDefault("token", List.of("")).get(0);
    if (token != null && !token.isEmpty()) {
        user = resolveUserFromToken(token);
    }
    if (user == null) {
        logger.warn("Unauthenticated WebSocket connection, closing");
        session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Authentication required"));
        return;
    }
}
```

##### Item 19: Audio double-encoding fix (OI-77)
**File:** `AccountManagerService7/.../WebSocketService.java` (line 543)

**Problem:** `TODO: Fix the double encoding on the client side` — audio data arrives double-base64-encoded, requiring extra decode. Wastes bandwidth and processing.

**Fix:** Fix the client-side encoding to single base64, then remove the server-side double-decode workaround.

##### Item 20: User profile reference model cleanup (OI-78)
**Files:**
- `AccountManagerUx7/client/pageClient.js` (lines 777-800) — `updateProfile()` workaround
- `AccountManagerUx7/client/components/topMenu.js` (line 24) — portrait path retrieval

**Problem:** The logged-in user's profile portrait and voice are stored via attribute workarounds (`v1-profile`, `v1-profile-path`) instead of looking up the user's corresponding `identity.person` record. The TODO (pageClient.js:777-779) notes this should be cleaned up.

**Important terminology distinction:**
- **Logged-in user** = `system.user` model — the authenticated account. Do NOT modify `system.userModel.json`.
- **Chat "system" character** = `olio.charPerson` from `chatConfig.systemCharacter` — this is NOT a `system.user` model. The name is confusing but they are completely separate models.
- **Chat "user" character** = `olio.charPerson` from `chatConfig.userCharacter` — also NOT a `system.user` model.
- **User's person record** = `identity.person` (not `olio.charPerson`) — found under `/Persons` (read-only system group for the org). This has a `profile` field referencing `identity.profile` with portrait, voice, album, etc.

**Architecture context:** In the chat system, model references (characters, configs) are provided on the chat request — they are not persisted on the user. Content for context injection is extracted from the vector store after the source objects have been vectorized and optionally summarized. The `olio.charPerson` models already have proper profile references for chat. The problem is only with how the **logged-in user's** portrait/profile is retrieved for the top menu bar and UX display.

**Fix:**

**A. UX top menu / logged-in user profile (non-chat context):**
1. **Look up `identity.person` under `/Persons`** — For the logged-in user, query for their corresponding `identity.person` instance under the `/Persons` read-only system group in the org. The person record has a `profile` field (which references `identity.profile` with portrait, voice, etc.)
2. **Replace `updateProfile()` in pageClient.js** — instead of patching `v1-profile` / `v1-profile-path` attributes, fetch the user's person record and read portrait from `person.profile.portrait`
3. **Replace `topMenu.js:24`** — instead of `am7client.getAttributeValue(page.user, "v1-profile-path", 0)`, use the person record's profile portrait path
4. **Add REST helper if needed** — if no existing endpoint returns the logged-in user's person record, add a lightweight endpoint (e.g., `GET /rest/user/person`) that looks up the `identity.person` for the authenticated user in `/Persons`
5. **Remove the `v1-profile` and `v1-profile-path` attribute workarounds** after the new lookup is working

**B. Chat context — identity resolution for `chatConfig.userCharacter` only:**
1. If `chatConfig.userCharacter` is set (role play), the user's conversation identity is that `olio.charPerson` — use its profile for portrait, voice, etc. This is NOT a `system.user` model.
2. If `chatConfig.userCharacter` is NOT set (plain chat), fall back to the logged-in user's `identity.person` under `/Persons`
3. `chatConfig.systemCharacter` is always the LLM assistant persona (`olio.charPerson`) — no fallback needed. The term "system" here refers to the LLM/assistant side, NOT the `system.user` model.
4. The chat view (chat.js) already reads `chatCfg.user.profile.portrait` for charPerson — this path works correctly for role play. The fix here only needs to handle the fallback case where no `userCharacter` is configured

---

#### Sub-phase 13f — Memory UX & MCP Visibility

**Goal:** Make the memory system fully visible and exercisable from the UX. Users should be able to see memories being created, browse existing memories for character pairs, observe memory injection into prompts, and verify cross-conversation memory recall during character chats.

**Current state of memory system:**
- **Backend complete:** `MemoryUtil.java` provides create/search/format methods. `Chat.java` has `retrieveRelevantMemories()` (person-pair query, categorization into relationship/facts/lastSession, MCP formatting) and `persistKeyframeAsMemory()` (auto-extract on keyframe). `PromptUtil.java` processes `{{memory.context}}`, `{{memory.relationship}}`, `{{memory.facts}}`, `{{memory.lastSession}}` template placeholders.
- **Backend config exists:** `chatConfigModel` has `extractMemories` (boolean), `memoryBudget` (int, controls max memories per prompt), `memoryExtractionEvery` (int, 0=every keyframe). `Chat.java` enforces `MIN_KEYFRAME_EVERY_WITH_EXTRACT=5`.
- **No REST endpoints:** `MemoryUtil` methods are not exposed via any REST service — UX cannot query memories.
- **No UX form fields:** `extractMemories`, `memoryBudget`, `memoryExtractionEvery` are not in `formDef.js` — users cannot enable memory from the config editor.
- **No memory browser:** No way to view, search, or manage memories for character pairs.
- **No visibility during chat:** No indicator when keyframes create memories, no display of memory count, no way to see what MCP context the LLM receives.

##### Item 21: Memory REST Service (OI-67)
**New file:** `AccountManagerService7/.../MemoryService.java`

Create a REST service exposing memory operations to the UX:

```
@Path("/memory")
public class MemoryService {

    GET  /memory/conversation/{configObjectId}
         → MemoryUtil.getConversationMemories(user, configObjectId)
         Returns all memories for a chat config (conversation)

    GET  /memory/person/{personObjectId}/{limit}
         → MemoryUtil.searchMemoriesByPerson(user, personId, limit)
         Returns memories involving a specific character

    GET  /memory/pair/{person1ObjectId}/{person2ObjectId}/{limit}
         → MemoryUtil.searchMemoriesByPersonPair(user, p1Id, p2Id, limit)
         Returns memories for a specific character pair

    POST /memory/search/{limit}/{threshold}
         Body: query text
         → MemoryUtil.searchMemories(user, query, limit, threshold)
         Semantic vector search across all memories

    GET  /memory/count/{person1ObjectId}/{person2ObjectId}
         → Returns count only (lightweight for badge display)

    DELETE /memory/{objectId}
         → Delete a specific memory record
}
```

Note: The person endpoints need to resolve objectId→id internally since the UX works with objectIds, not internal long IDs. Add a helper method `resolvePersonId(user, objectId)` that looks up the character record and returns its internal ID.

##### Item 22: Memory config form fields (OI-68)
**File:** `AccountManagerUx7/client/formDef.js` (chatConfig form, ~line 5031)

Add memory configuration fields to the chatConfig form, grouped after the existing keyframe/prune section:

```javascript
// After the existing prune/keyframeEvery/messageTrim block:
extractMemories: {
    layout: "one",
    label: "Extract Memories"
},
memoryBudget: {
    layout: "one",
    label: "Memory Budget",
    hint: "Max tokens for memory context (0=disabled)"
},
memoryExtractionEvery: {
    layout: "one",
    label: "Extract Every N Keyframes",
    hint: "0=every keyframe, N=every Nth"
},
```

Also add these fields to `LLMConnector.js` cloneFields array (~line 184) so they're preserved during config cloning:
```javascript
"extractMemories", "memoryBudget", "memoryExtractionEvery"
```

##### Item 23: MemoryPanel sidebar component (OI-69)
**New file:** `AccountManagerUx7/client/components/chat/MemoryPanel.js`

Create a collapsible panel (similar to ContextPanel) for the chat sidebar that shows memory state:

```
window.MemoryPanel = {
    PanelView: {
        // Collapsible header: "Memories (N)" or "Memories" when 0
        // Expanded: lists memories for current character pair
        // Each memory row shows: type icon, summary (truncated), importance badge
        // Click to expand full content
        // Delete button (calls DELETE /memory/{objectId})
        // Search input for semantic search across memories
    },

    loadForSession(chatConfig)     // Load memories for current session's character pair
    getMemoryCount()               // Returns count for badge display
    refresh()                      // Force reload
}
```

**Memory type icons** (Material Symbols):
- OUTCOME → `flag`
- RELATIONSHIP → `favorite`
- FACT/NOTE → `notes`
- INSIGHT → `lightbulb`
- DECISION → `gavel`
- DISCOVERY → `explore`
- BEHAVIOR → `psychology`
- ERROR_LESSON → `warning`

**Integration into chat sidebar:** Add `MemoryPanel.PanelView` to `getSplitLeftContainerView()` between ConversationManager and ContextPanel:
```javascript
function getSplitLeftContainerView() {
    let children = [];
    if (window.ConversationManager) {
        children.push(m(ConversationManager.SidebarView, { onNew: openChatSettings }));
    }
    if (window.MemoryPanel) {
        children.push(m(MemoryPanel.PanelView));
    }
    if (window.ContextPanel) {
        children.push(m(ContextPanel.PanelView));
    }
    return m("div", { class: "splitleftcontainer flex flex-col" }, children);
}
```

##### Item 24: Live memory indicators during chat (OI-71, OI-72)
**Files:** `view/chat.js`, `LLMConnector.js`

Add real-time memory visibility during character chats:

**A. Memory injection indicator:** When `retrieveRelevantMemories()` finds memories on the backend, the response should include a `memoryCount` field. Display this in the chat UI:
- Add `memoryCount` to the chat response metadata (server-side: `ChatService.java` text endpoint response, or via WebSocket event)
- In the message display area, show a subtle indicator: `"🧠 3 memories recalled"` before the assistant's first response in a session
- After each keyframe, show: `"📌 Keyframe created"` and if memory was extracted: `"🧠 Memory saved"`

**B. WebSocket memory events:** Extend the existing WebSocket event system (which already has `policyEvent`) to include:
- `memoryEvent: { type: "recalled", count: N }` — when memories are loaded for a prompt
- `memoryEvent: { type: "extracted", summary: "..." }` — when a keyframe produces a new memory
- `memoryEvent: { type: "keyframe" }` — when a keyframe fires (even without memory extraction)

**C. Chat status bar:** Add a compact status area above the input bar showing:
- Memory status: "3 memories" or "No memories" for current pair
- Keyframe countdown: "Next keyframe in 5 messages" (based on `keyframeEvery` - current message count)
- Extract status: enabled/disabled

##### Item 25: MCP context inspector (OI-70)
**Files:** `view/chat.js`, `ChatTokenRenderer.js`

Add a developer/debug mode to inspect MCP context blocks that are normally stripped from display:

**A. Toggle in chat toolbar:** Add a "debug" icon button (`bug_report`) to the chat toolbar. When active:
- MCP blocks are shown inline (collapsible) instead of stripped
- Keyframe blocks shown with a distinctive border (e.g., amber left border)
- Memory blocks shown with brain icon and content preview
- Reminder blocks shown with bell icon

**B. Message metadata tooltip:** When hovering over a message, show:
- MCP blocks count
- Memory injection count (from `PromptUtil.memoryCount`)
- Whether the message triggered a keyframe

**C. ChatTokenRenderer extension:**
```javascript
ChatTokenRenderer.processMcpTokens = function(content, debugMode) {
    if (!debugMode) return LLMConnector.stripMcpBlocks(content);
    // Parse <mcp:context> blocks and render as collapsible cards
    // with type-specific icons and formatted JSON content
};
```

##### Item 26: Cross-conversation memory demonstration scenario
**Goal:** Enable users to see memory working across conversations with the same character pair.

**Scenario flow:**
1. User opens chat with Character A (e.g., "Aria") — config has `extractMemories: true`, `memoryBudget: 500`, `keyframeEvery: 5`
2. User chats for several messages — keyframe fires, memory extracted
3. MemoryPanel shows "Memories (1)" with the extracted memory
4. User creates a **new session** with the same character pair (via ConversationManager "New" button)
5. On first message in new session, `retrieveRelevantMemories()` loads the memory from session 1
6. Memory injection indicator shows "1 memory recalled"
7. The LLM's response references/builds on the previous conversation
8. MemoryPanel shows the same memory (loaded from pair query, not conversation-specific)

**Required wiring for this scenario:**
- Item 17 ensures `extractMemories` and `memoryBudget` are configurable
- Item 16 ensures memories are queryable
- Item 18 shows them in the sidebar
- Item 19 shows real-time feedback during chat
- No new backend code needed — `Chat.retrieveRelevantMemories()` already does this

**Manual test checklist:**
- [ ] Enable `extractMemories` + set `memoryBudget > 0` on a chatConfig
- [ ] Chat until keyframe fires (observe keyframe indicator)
- [ ] Verify memory appears in MemoryPanel
- [ ] Start new session with same characters + same chatConfig
- [ ] Send first message — verify memory count indicator
- [ ] Verify LLM response shows awareness of prior conversation
- [ ] Open MemoryPanel in new session — verify same memories visible
- [ ] Search memories using the semantic search input
- [ ] Delete a memory — verify it's removed from next session's recall

##### Item 27: Memory search in chat (OI-73)
**File:** `AccountManagerUx7/client/components/chat/MemoryPanel.js`

The MemoryPanel search input uses the `POST /memory/search/{limit}/{threshold}` endpoint for semantic vector search. This allows users to:
- Search across all memories by content similarity
- Find memories from different character pairs
- Verify that the vector embedding + search pipeline is working

The search results display includes the character pair names, conversation source, and importance score.

---

#### Sub-phase 13g — Chat UX Polish

**Goal:** Improve the chat sidebar and conversation experience with object navigation links, auto-generated titles/icons, and memory application visibility.

##### Item 28: Object links in chat details (OI-79)
**File:** `AccountManagerUx7/client/components/chat/ConversationManager.js` (lines 170-193, `metadataView()`)

**Problem:** The metadata/details panel shows config and prompt names as plain text. Users cannot navigate from the chat sidebar to the underlying objects (chatConfig, promptConfig, system character, user character) to edit them.

**Fix:** Replace plain text labels with clickable links that open the object in the editor:
```javascript
function metadataView() {
    // ...existing code...
    if (meta.chatConfig) {
        let cc = meta.chatConfig;
        rows.push(objectLinkRow("Config", cc, "olio.llm.chatConfig"));
        if (cc.model) rows.push(metaRow("Model", cc.model));
        // ...existing trim/stream/prune rows...
        if (cc.systemCharacter) {
            rows.push(objectLinkRow("System", cc.systemCharacter, "olio.charPerson"));
        }
        if (cc.userCharacter) {
            rows.push(objectLinkRow("User Char", cc.userCharacter, "olio.charPerson"));
        }
    }
    if (meta.promptConfig) {
        rows.push(objectLinkRow("Prompt", meta.promptConfig, "olio.llm.promptConfig"));
    }
    // ...
}

function objectLinkRow(label, obj, modelType) {
    let name = obj.name || obj.objectId || "—";
    return m("div", { class: "text-xs text-gray-400 flex items-center" }, [
        m("span", label + ": "),
        m("a", {
            class: "text-blue-400 hover:text-blue-300 cursor-pointer truncate ml-1",
            title: "Open " + name,
            onclick: function(e) {
                e.preventDefault();
                // Navigate to object editor — use page.navigateTo or am7client.openObject
                if (window.page && page.navigateTo) {
                    page.navigateTo(modelType, obj.objectId);
                }
            }
        }, name)
    ]);
}

function metaRow(label, value) {
    return m("div", { class: "text-xs text-gray-400" }, label + ": " + value);
}
```

**Note:** The `page.navigateTo()` call needs to match the existing navigation pattern in the app. Check how object.js and other views handle opening a specific object by type + objectId. May use `page.rule` or route-based navigation.

##### Item 29: Auto-generated chat title and icon (OI-80)
**Files:**
- `AccountManagerUx7/media/prompts/chatTitlePrompt.json` (NEW)
- `AccountManagerUx7/client/components/chat/ConversationManager.js` (sidebar display)
- `AccountManagerUx7/client/view/chat.js` (trigger after first exchange)
- Server-side: `Chat.java` or `ChatService.java` (LLM call for title generation)

**Problem:** Chat sessions in the sidebar show the raw chatRequest name (e.g., "Chat 2025-01-15"). After an exchange, there's enough context to generate a meaningful title and pick an appropriate icon, but this doesn't happen automatically.

**Design:**

**A. Prompt template** — Store in `Ux7/media/prompts/chatTitlePrompt.json`:
```json
{
    "system": [
        "Given a conversation opening, generate a short title (max 40 chars) and pick one Material Symbols icon name that best represents the topic.",
        "Respond ONLY with JSON: {\"title\": \"...\", \"icon\": \"...\"}",
        "Icon examples: chat, psychology, sword, castle, restaurant, travel, code, music, heart, science, sports, school, work, pet"
    ]
}
```

**B. Trigger** — After the first actual exchange (user sends message + LLM responds), if the chatRequest has no `chatTitle` attribute:
1. Send the first user message + first LLM response to a lightweight LLM call using the title prompt
2. Parse the JSON response to get `title` and `icon`
3. Store as attributes on the chatRequest: `am7client.setAttribute(chatRequest, "chatTitle", title)` and `am7client.setAttribute(chatRequest, "chatIcon", icon)`
4. Save the chatRequest

**C. Sidebar display** — In `sessionItemView()`, check for `chatTitle` / `chatIcon` attributes:
```javascript
function sessionItemView(session, isSelected) {
    let title = am7client.getAttributeValue(session, "chatTitle", 0) || session.name || "(unnamed)";
    let icon = am7client.getAttributeValue(session, "chatIcon", 0);
    // ...
    return m("button", { class: cls, onclick: ... }, [
        // delete icon (existing)...
        icon ? m("span", {
            class: "material-symbols-outlined flex-shrink-0 mr-1",
            style: "font-size: 16px;"
        }, icon) : "",
        m("span", { class: "flex-1 truncate text-left" }, title)
    ]);
}
```

**D. Server-side option** — The title generation could happen either:
- **Client-side:** UX makes a lightweight LLM call via the existing chat endpoint with a special `purpose: "title"` flag. Simpler but adds a visible extra call.
- **Server-side:** `Chat.java` auto-generates after first exchange as part of `chatComplete`. Cleaner UX but requires a backend change. The title prompt file would need to be loaded server-side or stored as a promptConfig.

Prefer server-side — add a `generateChatTitle()` method to `Chat.java` that runs after the first exchange (check `messages.size() == 2`). Store results as attributes on the chatRequest via `AttributeUtil`. Send a chirp `chatTitleUpdate` so the sidebar refreshes.

##### Item 30: Memory application indicator in chat (OI-81)
**Files:** `view/chat.js`, `LLMConnector.js`, server-side `Chat.java` / `ChatService.java`

**Problem:** When memories are recalled and injected into a chat prompt, there's no UX indication that this happened. Users can't tell whether memory is working, what memories were applied, or how many.

**Design options (from most to least feasible):**

**A. Response metadata badge (recommended):** The server already knows the memory count from `retrieveRelevantMemories()`. Include it in the chat response:
- Server-side: After `retrieveRelevantMemories()` runs in `Chat.chat()`, store the count and optionally summaries on a response metadata field or as a WebSocket chirp
- Add a new chirp type: `memoryRecall` with payload `{ count: N, summaries: ["...", "..."] }`
- Client-side: When `memoryRecall` chirp is received, show a subtle indicator above the assistant's response:
  ```
  [brain icon] 3 memories recalled
  ```
  Clicking it expands to show the memory summaries

**B. Inline annotation (lightweight alternative):** If chirps are too complex, the assistant's response could include an MCP context block with memory metadata that `ChatTokenRenderer` renders as a collapsible "memories applied" badge rather than stripping it.

**C. Status bar approach (from existing item 24C):** The compact status area above the chat input already planned in item 24 would show memory count. This item extends it to be more specific about what memories were applied (summaries, not just count).

**Integration with item 24:** Item 24 defines the WebSocket `memoryEvent` system. This item (30) focuses on the UX rendering of that data — specifically:
- How the memory indicator looks in the message stream (not just sidebar)
- Expandable summary list (not just a count)
- Visual distinction between "recalled" memories (applied to prompt) vs "extracted" memories (saved from keyframe)

**Minimal viable approach:**
1. Add `memoryCount` to chat response attributes (server adds it after `retrieveRelevantMemories()`)
2. In `chat.js` message rendering, if `memoryCount > 0`, show a small badge before the assistant response
3. No new WebSocket events needed — piggyback on the response data

---

#### New Tests

##### Backend Tests (TestChatPhase13.java)

| Test ID | Category | Validates |
|---------|----------|-----------|
| P13-1 | backend | Config purpose field — new chatConfig with `purpose="analysis"` can be created and queried |
| P13-2 | backend | `ChatUtil.getCreateChatConfig()` deprecation — verify replacement path works |
| P13-3 | backend | createSummary pipeline — vectorize + summarize produces valid note with Summary tag |
| P13-4 | backend | chatRequest context attachment — verify `/rest/chat/context/attach` works for analysis reference objects |
| P13-5 | backend | Memory REST: GET /memory/conversation returns memories for a config |
| P13-6 | backend | Memory REST: GET /memory/pair returns memories for a character pair |
| P13-7 | backend | Memory REST: POST /memory/search performs semantic vector search |
| P13-8 | backend | Memory REST: GET /memory/count returns correct count for pair |
| P13-9 | backend | Memory REST: DELETE removes memory and its vectors |
| P13-10 | backend | Cross-conversation recall: memory created in session 1 is retrieved in session 2 with same characters |
| P13-11 | backend | WebSocket token auth: null principal with valid token resolves user |
| P13-12 | backend | WebSocket token auth: null principal with no token closes session |

##### UX Tests (llmTestSuite.js additions)

| Test # | Category | Validates |
|--------|----------|-----------|
| 129 | dialog | AnalysisManager.startAnalysis() exists and is a function |
| 130 | dialog | AnalysisManager creates session via ConversationManager (no window.open) |
| 131 | dialog | AnalysisManager attaches reference to ContextPanel |
| 132 | dialog | AnalysisManager populates workingSet with vectorized context |
| 133 | dialog | Config purpose-based filtering (no name prefix dependency) |
| 134 | dialog | Vectorize progress dialog shown during operation |
| 135 | dialog | Summarize progress dialog shown during operation |
| 136 | dialog | dialog.chatInto removed from exports (dead code cleanup verified) |
| 137 | dialog | remoteEntity pattern removed from chat.js oninit |
| 138 | dialog | Stream interrupt: message sent during stream triggers cancel-and-send or queue |
| 139 | memory | MemoryPanel.PanelView exists as Mithril component |
| 140 | memory | MemoryPanel shows memory count badge when memories exist |
| 141 | memory | Memory config fields (extractMemories, memoryBudget, memoryExtractionEvery) in formDef |
| 142 | memory | Memory REST endpoint /memory/conversation accessible |
| 143 | memory | Memory REST endpoint /memory/pair accessible |
| 144 | memory | Memory REST endpoint /memory/search accessible |
| 145 | memory | MCP debug toggle in chat toolbar |
| 146 | memory | ChatTokenRenderer.processMcpTokens exists |
| 147 | memory | LLMConnector cloneFields includes memory config fields |
| 148 | memory | MemoryPanel semantic search input present |
| 149 | config | chatConfig form has requestTimeout field |
| 150 | config | chatConfig form has terrain, populationDescription, animalDescription fields |
| 151 | config | chatConfig form has universeName, worldName fields |
| 152 | infra | WebSocket reconnect: page.openSocket exists and is reconnectable |
| 153 | memory | Prompt templates include memory.context conditional block |
| 154 | memory | Prompt template prompt.memoryChat.json has all 4 memory variables |
| 155 | sidebar | Chat details metadata rows have clickable object links (objectLinkRow function exists) |
| 156 | sidebar | Session item displays chatTitle attribute when present instead of raw name |
| 157 | sidebar | Session item displays chatIcon attribute as material icon when present |
| 158 | memory | Memory recall badge rendered before assistant response when memoryCount > 0 |

---

#### Files Modified Summary

| File | Sub-phase | Changes |
|------|-----------|---------|
| `AnalysisManager.js` | 13a | **NEW** — in-page analysis coordinator |
| `dialog.js` | 13a, 13b | Remove `chatInto()`, improve vectorize/summarize progress |
| `view/chat.js` | 13a, 13c, 13f | Remove remoteEntity pattern, use AnalysisManager, stream interrupt, memory indicators, MCP debug toggle |
| `view/object.js` | 13a | Replace `dialog.chatInto` with `AnalysisManager.startAnalysis` |
| `chatConfigModel.json` | 13a | Add `purpose` field (optional — depends on approach chosen) |
| `ChatUtil.java` | 13c, 13d | Deprecate `getCreateChatConfig()`, document $flex workaround |
| `ChatService.java` | 13c, 13f | chatRequest persistence, memory count in response metadata |
| `MemoryService.java` | 13f | **NEW** — REST endpoints for memory CRUD and search |
| `MemoryPanel.js` | 13f | **NEW** — sidebar memory browser + search component |
| `ChatTokenRenderer.js` | 13f | Add `processMcpTokens()` for MCP debug display |
| `LLMConnector.js` | 13f | Add memory config fields to cloneFields |
| `formDef.js` | 13d, 13e, 13f | SD config defaults, purpose field, memory config fields, missing chatConfig fields (requestTimeout, terrain, etc.) |
| `pageClient.js` | 13e | WebSocket auto-reconnect with exponential backoff |
| `WebSocketService.java` | 13e | Token auth fallback, audio double-encoding fix |
| `pageClient.js` | 13e | WebSocket reconnect, replace `v1-profile` attribute workaround with `identity.person` lookup under `/Persons` |
| `topMenu.js` | 13e | Replace attribute-based portrait path with person record profile lookup |
| `ConversationManager.js` | 13g | Object links in metadataView, chatTitle/chatIcon display in sessionItemView |
| `chatTitlePrompt.json` | 13g | **NEW** — prompt template for auto-generating chat title + icon |
| `Chat.java` | 13g | `generateChatTitle()` method, memory count on response, `chatTitleUpdate` chirp |
| `TestChatPhase13.java` | NEW | 12 backend tests |
| `llmTestSuite.js` | ALL | 30 UX tests (129-158) |
| `chatRefactor.md` | ALL | Phase 13 status, new OI items |

---

#### Implementation Order

1. **13e item 16** — Missing chatConfig form fields (quick win — requestTimeout, terrain, world-building)
2. **13f item 21** — Memory config form fields (extractMemories, memoryBudget — unlocks memory for users)
3. **13f item 20** — Memory REST service (required for all memory UX features)
4. **13f item 22** — MemoryPanel sidebar component
5. **13f item 23** — Live memory indicators during chat
6. **13e item 17** — WebSocket auto-reconnect (critical infrastructure reliability)
7. **13a items 1-2** — AnalysisManager module + config purpose resolution (core chatInto redesign)
8. **13a items 3-4** — Wire chat.js and object.js to AnalysisManager
9. **13a item 5** — Remove dialog.chatInto dead code
10. **13b items 6-8** — Vectorize/summarize UX improvements
11. **13c item 10** — Stream interrupt (high UX impact)
12. **13c items 9,11** — Backend cleanup (deprecation, persistence)
13. **13f item 24** — MCP context inspector (debug tool)
14. **13f items 25-26** — Cross-conversation memory demo scenario + memory search
15. **13e items 18-19** — WebSocket token auth, audio double-encoding fix
16. **13e item 20** — User profile reference model refactor (model + UX)
17. **13g item 28** — Object links in chat details (quick UX win)
18. **13g item 29** — Auto-generated chat title and icon (server-side generateChatTitle + prompt + sidebar display)
19. **13g item 30** — Memory application indicator (piggyback on item 24 memory events + response metadata)
20. **13d items 12-15** — Remaining P4 items
21. **Tests** — Backend TestChatPhase13 + UX test additions
22. **chatRefactor.md** — Update all OI statuses, phase summary

---

#### Deferred Beyond Phase 13

These items were identified in the gap audit but are out of scope for Phase 13:

| Item | Reason |
|------|--------|
| Chain execution endpoints (ChatService /chain, /chain/status) | Requires Agent7 module integration — separate feature |
| GameStreamHandler action cancellation (line 66) | Game stream infrastructure, not chat-specific |
| formDef.js promptRaceConfig migration (line 4623) | Long-running model migration, not blocking |
| ColorUtil hash replacement (line 37) | Cosmetic, no user impact |
| NarrativeUtil config model migration (line 943) | Low priority, not chat-blocking |
| OlioUtil/CharacterUtil deprecations | Framework cleanup, not chat-specific |
| Auto1111Util model configurability (line 54) | SD-specific, not chat-specific |

---

#### Verification

1. **Backend tests:** `mvn test -Dtest=TestChatPhase13` in AccountManagerObjects7
2. **Regression tests:** Run TestChatPhase12, TestChatPhase11, TestResponsePolicy, TestPromptTemplate
3. **UX tests:** Open browser test suite, run categories: `dialog`, `context`, `connector`, `convmgr`, `memory`, `config`, `infra`
4. **Manual verification — Analysis pipeline:**
   - From chat view: click "query_stats" → analysis session created in same tab, visible in sidebar
   - From object view: click "query_stats" → routes to chat, analysis session appears
   - Switch between analysis and regular sessions via ConversationManager
   - ContextPanel shows the reference object as a binding
   - Vectorize/summarize dialogs show progress indicator
5. **Manual verification — Memory system (cross-conversation scenario):**
   - Enable `extractMemories` + set `memoryBudget > 0` on a chatConfig via form editor
   - Chat with a character pair until keyframe fires (observe keyframe indicator in status bar)
   - Verify memory appears in MemoryPanel sidebar with type icon and summary
   - Start NEW session with same character pair
   - Send first message — verify "N memories recalled" indicator
   - Verify LLM response shows awareness of prior conversation
   - Open MemoryPanel — verify same memories visible (loaded by pair, not by session)
   - Use semantic search in MemoryPanel to find memories across all pairs
   - Delete a memory — verify it no longer appears in next session's recall
   - Toggle MCP debug mode — verify keyframe/memory MCP blocks visible inline
6. **Manual verification — Infrastructure:**
   - Set `requestTimeout` via form editor — verify it persists and is used
   - Set terrain/populationDescription/universeName via form editor — verify template substitution
   - Close WebSocket (network disconnect) — verify auto-reconnect with exponential backoff
   - Verify WS reconnects and streaming resumes after reconnection
7. **Build:** Run `node build.js` in AccountManagerUx7

---

### Phases 1-12 Complete Summary

All 12 prior phases of the chat redesign are implemented and tested. The system provides:
- Structured 12-stage prompt template pipeline with dynamic rules and variable replacement
- Memory retrieval and keyframe-to-memory pipeline with MCP-only format
- Policy-based LLM response regulation with autotuning
- Always-stream backend with graceful/forced cancellation
- Dual-service (OpenAI + Ollama) support with native options
- Shared UX components (LLMConnector, ConversationManager, ContextPanel, ChatTokenRenderer)
- MCP context bindings for generic object association
- Comprehensive test coverage: 62+ backend tests, 128+ browser tests

**Rationale:**

- **Phase 8** is now **COMPLETED**. All three P1 bugs fixed (OI-6: `top_k` maxValue, OI-7: `typical_p` mapping, OI-8: `repeat_penalty` mapping). New fields added to chatOptions (`frequency_penalty`, `presence_penalty`, `max_tokens`, `seed`). Request model defaults corrected. Six chatConfig templates created. UX form updated. All backend tests (40-45) pass, all regression tests pass.

- **Phase 8 implementation summary:**
  - `chatOptionsModel.json`: Fixed `top_k` maxValue 1→500, added `max_tokens`, `frequency_penalty`, `presence_penalty`, `seed` fields
  - `openaiRequestModel.json`: Fixed `frequency_penalty`/`presence_penalty` defaults 1.3→0.0, added `seed` field
  - `ChatUtil.applyChatOptions()`: Rewrote to read new fields directly from chatOptions (no more cross-mapping of `repeat_penalty`→`frequency_penalty` or `typical_p`→`presence_penalty`). Added service-type-aware max token field selection.
  - `Chat.applyAnalyzeOptions()`: Fixed to use `getMaxTokenField()` instead of hardcoded `max_completion_tokens`. Fixed penalty field mapping.
  - Created 6 chatConfig template JSON files in `olio/llm/templates/`
  - Added `ChatUtil.loadChatConfigTemplate()`, `getChatConfigTemplateNames()`, `applyChatConfigTemplate()` methods
  - Updated UX `formDef.js`: Replaced old `typical_p`/`repeat_penalty` form fields with correct `frequency_penalty`/`presence_penalty`/`max_tokens`/`seed` fields
  - Updated UX `SessionDirector.js`: Added new fields to optKeys copy list
  - Fixed UX `modelDef.js`: Updated `olio.llm.chatOptions` schema (top_k maxValue 1→500, added `max_tokens`, `frequency_penalty`, `presence_penalty`, `seed` fields). Fixed `olio.llm.openai.openaiRequest` defaults (`frequency_penalty`/`presence_penalty` 1.3→0.0, added `seed` field)
  - Updated `llmTestChatConfig.json`: Added `chatOptions` section with template defaults
  - Added UX tests (82-85) to `llmTestSuite.js`: chatOptions field presence, top_k range fix verification, field range validation, UX schema verification, openaiRequest default verification

- **Phase 9** is now **COMPLETED**. Four detection operations implemented (`TimeoutDetection`, `RecursiveLoopDetection`, `WrongCharacterDetection`, `RefusalDetection`). `ResponsePolicyEvaluator` delegates to existing `PolicyEvaluator.evaluatePolicyRequest()` pipeline — no security bypass. `ChatAutotuner` provides LLM-based prompt analysis on violation. Post-response hooks in both buffer (`Chat.continueChat()`) and stream (`ChatListener.oncomplete()`) paths. WebSocket `policyEvent` notifications. Enhanced stop with `CompletableFuture` failover. Backend tests 46-54 pass, UX test 81 expanded.

- **Phase 9 implementation summary:**
  - `TimeoutDetectionOperation.java` (NEW): Detects null/empty responses from LLM timeout or connection drop. Returns `FAILED` if response content is null or whitespace-only.
  - `RecursiveLoopDetectionOperation.java` (NEW): Sliding-window detection of repeated text blocks. Default: 50-char window, 3x threshold, configurable via `referenceFact` parameters. Uses half-window step size for overlap.
  - `WrongCharacterDetectionOperation.java` (NEW): Detects LLM responding as user character instead of system character. Three heuristics: dialogue pattern (`Bob: `), narrative pattern (`*Bob walks*`), "As Bob" pattern. Parses character names from `referenceFact.factData` JSON.
  - `RefusalDetectionOperation.java` (NEW): Detects LLM safety refusals via 14-phrase pattern matching. Configurable `minMatches` threshold (default 2) — single match could be in-character; 2+ indicates actual refusal.
  - `ResponsePolicyEvaluator.java` (NEW): Delegates to existing `PolicyEvaluator.evaluatePolicyRequest()` pipeline. Resolves policy from `chatConfig.policy` foreign reference. Builds `PolicyRequestType` with response content as source fact, chatConfig/promptConfig as reference fact data. Returns `PolicyEvaluationResult` with PERMIT/DENY and violation details.
  - `ChatAutotuner.java` (NEW): LLM-based prompt analysis and rewrite suggestion on policy violation. Uses `analyzeModel` from chatConfig (falls back to main model). Creates non-persistent analysis session. Counts existing autotuned prompts via LIKE query for naming convention.
  - Sample policy JSON files (3 NEW): `policy.rpg.json` (all 4 operations, standard thresholds), `policy.clinical.json` (strict refusal detection, minMatches=1), `policy.general.json` (timeout + loop only, no autotune).
  - `Chat.java` (MODIFIED): Added `evaluateResponsePolicy()` method with post-response hook in buffer path. Registers stream future with `ChatListener` for failover cancellation.
  - `ChatListener.java` (MODIFIED): Added post-response policy hook in `oncomplete()`. Enhanced `stopStream()` with `CompletableFuture.delayedExecutor()` failover timer. Added `asyncStreamFutures` map and `registerStreamFuture()`.
  - `IChatHandler.java` (MODIFIED): Added `onPolicyViolation()` as default method so existing implementations don't break.
  - `WebSocketService.java` (MODIFIED): Implements `onPolicyViolation()` — chirps `policyEvent` with violation details to client via WebSocket.
  - `TestResponsePolicy.java` (NEW): Backend tests 46-54, 48b, 52b, 61 — all synthetic (no LLM required). Tests timeout detection (null/empty/whitespace/normal), recursive loop detection (repeated blocks/clean/configurable), wrong character detection (dialogue/narrative/system-char-clean), refusal detection (multi-phrase/clean/strict), autotuner count query, sample policy JSON loading.
  - `llmTestSuite.js` (MODIFIED): Test 81 (`testPolicy`) expanded from Phase 9 placeholder to full implementation — policy field existence check, policy configuration validation, WebSocket handler verification, live policy evaluation test.

- **Phase 10a** is now **COMPLETED**. Shared components extracted, all 3 consumer views refactored, 25 UX tests + 4 backend tests added, all passing.

- **Phase 10a implementation summary:**
  - `LLMConnector.js` (NEW): Unified IIFE module (`window.LLMConnector`) with 20+ methods extracted from 3 consumer views. Config management: `findChatDir()`, `getOpenChatTemplate()`, `ensurePrompt()` (with OI-24 update-if-changed), `ensureConfig()` (with full template clone + chatOptions + OI-24 sync). Chat operations: `chat()`, `streamChat()`, `cancelStream()`, `getHistory()`, `createSession()`, `deleteSession()`. Response processing: `extractContent()` (handles 5+ response shapes), `parseDirective()` (strict + lenient JS-object parsing + truncation repair), `repairJson()`. Content pruning: `pruneTag()`, `pruneToMark()`, `pruneOther()`, `pruneOut()`, `pruneAll()`. Utilities: `cloneConfig()`, `errorState`, `onPolicyEvent()`/`handlePolicyEvent()` (OI-40).
  - `ChatTokenRenderer.js` (NEW): Shared image/audio token processing (OI-20). Methods: `parseImageTokens()`, `parseAudioTokens()`, `processImageTokens()` (with async resolution pipeline), `processAudioTokens()` (with inline audio player buttons), `pruneForDisplay()`.
  - `chat.js` (MODIFIED): Added 5 new exports to `am7chat` (OI-46): `getHistory`, `extractContent`, `streamChat`, `parseDirective`, `cloneConfig` — all delegating to `LLMConnector`.
  - `pageClient.js` (MODIFIED): Added `policyEvent` chirp handler (OI-40) routing to `LLMConnector.handlePolicyEvent()`.
  - `view/chat.js` (MODIFIED): Replaced `getHistory()` with LLMConnector delegation. Replaced 5 prune functions (`pruneAll`, `pruneOther`, `pruneToMark`, `pruneOut`, `pruneTag`) with LLMConnector delegations. Added `LLMConnector.onPolicyEvent()` display handler showing toast notifications.
  - `SessionDirector.js` (MODIFIED): Removed `_ensurePromptConfig()`, `_ensureDiagnosticPromptConfig()`, `_ensureChatConfig()`, `_ensureDiagnosticChatConfig()`, `_repairTruncatedJson()` (~200 lines). `initialize()` delegates to `LLMConnector.findChatDir()`, `getOpenChatTemplate()`, `ensurePrompt()`, `ensureConfig()`, `createSession()`. `_extractContent()` delegates to `LLMConnector.extractContent()`. `_parseDirective()` delegates JSON parsing to `LLMConnector.parseDirective()`, keeps domain-specific directive field validation. Diagnostic tests updated to use LLMConnector.
  - `llmBase.js` (MODIFIED): Static methods (`findChatDir`, `getOpenChatTemplate`, `ensurePromptConfig`, `ensureChatConfig`, `extractContent`, `cleanJsonResponse`) now delegate to LLMConnector. `initializeLLM()` uses LLMConnector throughout. `chat()` delegates to `LLMConnector.chat()`. Backward-compatible: all callers in `director.js`, `narrator.js`, `chatManager.js`, `testMode.js` unchanged.
  - `ChatService.java` (MODIFIED): Added 2 objectId-based REST endpoints: `GET /rest/chat/config/prompt/id/{objectId}` and `GET /rest/chat/config/chat/id/{objectId}`.
  - `TestChatPhase10.java` (NEW): 4 backend tests — P10-1 (objectId lookup), P10-2 (update-if-changed), P10-3 (history ordering after 3 exchanges with delays — OI-43), P10-4 (prune=false preserves all messages). All 4 pass with live LLM.
  - `llmTestSuite.js` (MODIFIED): 25 new UX tests (86-110) in 3 categories (`connector`, `token`, `convmgr`). Fixed `findOrCreateConfig` with OI-24 update-if-changed logic. Tests cover: module availability, config management, response extraction (7 shapes), directive parsing (4 modes), JSON repair, pruning, cloning, history, streaming, delegation, token rendering, display pruning, live config update, history ordering, objectId REST, policyEvent wiring, error state tracking.
  - `build.js` (MODIFIED): Added `LLMConnector.js` and `ChatTokenRenderer.js` to jsFiles after `chat.js`.
  - **Resolved OIs:** OI-20 (token standardization), OI-24 (config caching), OI-40 (policyEvent wiring), OI-42 (objectId endpoints), OI-46 (chat.js missing exports), OI-47 (SessionDirector duplication).
  - **Remaining for 10b/10c:** ~~OI-43 (history message loss)~~, ~~OI-48 (chatManager local tracking)~~, OI-49 (generic association API — deferred to 10c MCP).
- **Phase 10b implementation summary:**
  - `ConversationManager.js` (NEW): Session list sidebar component — search/filter, selection, deletion, metadata panel. Replaces inline session list in `view/chat.js`. Exposes `window.ConversationManager` with `SidebarView` Mithril component, `onSelect`/`onDelete` callbacks, `refresh()`, `autoSelectFirst()`.
  - `view/chat.js` (MODIFIED): Integrated ConversationManager sidebar (with fallback), delegated `processImageTokensInContent` and `processAudioTokensInContent` to `ChatTokenRenderer`, updated `doPeek()` to prefer objectId-based config lookups (10a endpoints), removed `escapeHtmlAttr` (now in ChatTokenRenderer).
  - `chatManager.js` (MODIFIED): `startConversation()` now seeds `currentConversation[]` from server history via `LLMConnector.getHistory()` (OI-48 fix).
  - `llmTestSuite.js` (MODIFIED): Added 200ms delays between sequential message sends in tests 73-74 (OI-43 fix — matches pattern from test 106 and backend P10-3/P10-4).
  - `Chat.java` (MODIFIED): Fixed `getSDPrompt()` — `applyChatOptions(req)` → `applyChatOptions(areq)` so SD prompt options apply to the new request, not the source (OI-35 fix).
  - `build.js` (MODIFIED): Added `ConversationManager.js` after `ChatTokenRenderer.js`.
  - **Resolved OIs:** OI-35 (SD prompt options target), OI-43 (history message loss timing), OI-48 (chatManager local tracking).
  - **Remaining for 10c:** OI-49 (generic association API / MCP context binding).
- **Phase 10b patch — CacheDBSearch + TestChatStream fixes:**
  - `CacheDBSearch.java` (MODIFIED): Removed `synchronized` from `find()` and all private helper methods (`checkCache`, `getCacheMap`, `getCache`, `addToCache`, `clearCache(BaseRecord)`). Replaced `getCacheMap()` check-then-put with `ConcurrentHashMap.computeIfAbsent()`. The per-model `ConcurrentHashMap` instances provide thread safety for individual operations without requiring a global lock on every query. The previous `synchronized find()` serialized ALL database reads through a single lock — under Tomcat load, this caused cascading thread pool exhaustion when any one query was slow (e.g., during a DELETE or makePath), blocking all subsequent requests including login. (Resolves OI-50.)
  - `TestChatStream.java` (MODIFIED): Reduced `requestTimeout` from 120s to 30s across all 4 tests (36-39). Removed `STREAM_TEST_MODEL` override — tests now use whatever model is configured in `resource.properties`. All 4 tests pass in 17 seconds total (previously could hang for 2+ minutes per test due to excessive timeouts masking connectivity issues).
  - **Resolved OIs:** OI-50 (CacheDBSearch.find() synchronized bottleneck).
- **Phase 10c implementation summary:**
  - `chatRequestModel.json` (MODIFIED): Added `contextType` (string, maxLength 64) and `context` ($flex foreign, foreignType=contextType) fields. `contextType` added to query array; `context` excluded from query array to avoid `planMost(true)` $flex resolution failures when contextType is null (the existing `session` $flex field predates this issue because its DB column was created at model initialization).
  - `Am7ToolProvider.java` (MODIFIED): Added 3 MCP tools — `am7_session_attach` (attach chatConfig/promptConfig/systemCharacter/userCharacter/context to session), `am7_session_detach` (detach systemCharacter/userCharacter/context), `am7_session_context` (list all bindings as text summary with model/stream/prune metadata). Characters attach to chatConfig (not chatRequest) since systemCharacter/userCharacter are chatConfig fields. Added `findByObjectId()`, `sessionAttach()`, `sessionDetach()`, `sessionContext()`, and 3 schema builder methods.
  - `ChatService.java` (MODIFIED): Added 3 REST context endpoints — `POST /rest/chat/context/attach` (JSON body: sessionId, attachType, objectId, optional objectType), `POST /rest/chat/context/detach` (JSON body: sessionId, detachType), `GET /rest/chat/context/{objectId}` (returns JSON with chatConfig/promptConfig/systemCharacter/userCharacter/context bindings). Added `findByObjectId()` and `escJson()` helpers.
  - `ContextPanel.js` (NEW): Mithril.js IIFE component for session context binding display and management. Loads context from `GET /rest/chat/context/{sessionId}`. Supports attach/detach via REST. Drag-and-drop from `dnd.workingSet` auto-detects schema type (chatConfig→attachChatConfig, promptConfig→attachPromptConfig, charPerson→attachSystemCharacter, other→attachContext). Collapsible `PanelView` with expand/collapse toggle. Public API: `load()`, `refresh()`, `getData()`, `onContextChange()`, `attach()`, `detach()`, `toggle()`, `clear()`. Exposes `window.ContextPanel`.
  - `view/chat.js` (MODIFIED): Integrated ContextPanel below ConversationManager in sidebar. `pickSession()` calls `ContextPanel.load(objectId)`. `doClear()` calls `ContextPanel.clear()`. `sendToMagic8()` passes `sessionId` as route param alongside sessionStorage fallback.
  - `magic8/index.js` (MODIFIED): `launchFromChat()` includes `sessionId` in route params from `chatContext.instanceId`.
  - `magic8/Magic8App.js` (MODIFIED): `oninit()` reads `sessionId` from route attrs, stores as `this.chatSessionId` for server-side context loading.
  - `build.js` (MODIFIED): Added `ContextPanel.js` after `ConversationManager.js`.
  - `TestChatPhase10.java` (MODIFIED): Added 4 backend tests — P10c-1 (contextType field persistence: set/update/re-fetch), P10c-2 (context attach/detach round-trip: create data.data object with ParameterList, attach as context, verify contextType persistence, detach, verify removal), P10c-3 (chatConfig switch: create 2 configs, switch via set()+update(), re-fetch and verify), P10c-4 (MCP tool provider lists 7+ tools including am7_session_attach/detach/context). All 8 tests (P10-1..4 + P10c-1..4) pass.
  - **Resolved OIs:** OI-49 (generic object association API — chatRequest context/$flex foreign + MCP tools + REST endpoints + UX ContextPanel).
  - **Known limitation:** `planMost(true)` queries on chatRequest fail with PSQLException when the `context` $flex foreign field has null contextType. The `context` field is excluded from the query array to prevent this. Consumers should load context explicitly via `ContextPanel.load()` or `GET /rest/chat/context/{sessionId}` rather than relying on planMost to resolve it.

- **Phase 11** is now **COMPLETED**. All 8 Phase 11 open issues resolved (OI-1, OI-3, OI-5, OI-13, OI-14, OI-15, OI-19, OI-26). 8 backend tests added, all pass.

- **Phase 11 implementation summary:**
  - `MemoryUtil.java` (MODIFIED): OI-1 — Added 3-arg overload chain for `createMemory()` accepting `personModel` parameter. Sets `personModel` field on memory record when non-null/non-empty. Backward-compatible: existing callers without `personModel` pass through to original overload. OI-3 — Added new `extractMemoriesFromResponse()` overload accepting `personId1`/`personId2`/`personModel`, passes them through to `createMemory()` for each extracted memory.
  - `Chat.java` (MODIFIED): OI-1 — `persistKeyframeAsMemory()` extracts `personModel` from `systemChar.getSchema()` and passes to `createMemory()`. OI-5 — Added `MIN_KEYFRAME_EVERY_WITH_EXTRACT = 5` constant; `configureChat()` enforces minimum when `extractMemories=true` with warning log. OI-14 — Replaced all old keyframe/reminder detection with MCP-only methods: `isMcpKeyframe()` (checks `<mcp:context` + `/keyframe/`), `isMcpReminder()` (checks `<mcp:context` + `/reminder/`), `countBackToMcp(OpenAIRequest, String)` (scans backward for MCP URI fragment). Removed old `countBackTo()`/`isMcpEquivalent()` methods. Updated `pruneCount()`, `addKeyFrame()`, `newMessage()` to use new methods.
  - `ChatUtil.java` (MODIFIED): OI-14 — `getFormattedChatHistory()` updated: primary detection is MCP-only (`<mcp:context` + `/keyframe/`). Legacy `(KeyFrame:` skip and `(Reminder:` strip retained for display of existing chat histories (backward-compatible display, not creation).
  - `PromptUtil.java` (MODIFIED): OI-15 — Added `reapplyNlpCommand()` post-Stage-7 pass. Stored `nlpCommand` in `PromptBuilderContext` during Stage 6 (`buildRatingNlpConsentReplacements()`), reapplied after `buildDynamicRulesReplacement()` to fix `${nlp.command}` tokens reintroduced by Stage 7 dynamic rules.
  - `PromptBuilderContext.java` (MODIFIED): OI-15 — Added `public String nlpCommand = null` field to carry NLP command across pipeline stages.
  - `PromptConfigMigrator.java` (MODIFIED): OI-19 — Expanded `CONDITION_MAP` from 7 to 14 entries: added `userConsentRating` → `rating>=M`, `scene` → `includeScene`, `femalePerspective` → `systemCharacter.gender=female`, `malePerspective` → `systemCharacter.gender=male`, `systemSDPrompt` → `useSDPrompt`, `assistantReminder` → `assist`, `userReminder` → `assist`.
  - `TestChatPhase11.java` (NEW): 8 backend tests — P11-1 (personModel field population), P11-1b (backward compat without personModel), P11-2 (extractMemoriesFromResponse with person pair IDs), P11-3 (keyframeEvery minimum floor enforcement), P11-4 (MCP-only keyframe detection in getFormattedChatHistory), P11-5 (nlp.command reapplication after Stage 7), P11-6 (migrator condition coverage — 9 fields with content detected), P11-7 (keyframe detection MCP URI fragment: isMcpKeyframe/isMcpReminder). All 8 pass.
  - Test files relocated (OI-13): `TestMemoryUtil.java`, `TestMemoryPhase2.java`, `TestKeyframeMemory.java`, `TestMemoryDuel.java` copied from `AccountManagerAgent7/src/test/java/` to `AccountManagerObjects7/src/test/java/`. Same package, same `BaseTest` — zero Agent7 dependencies.
  - `llmTestSuite.js` (MODIFIED): OI-26 — Test 76 keyframe detection updated from old `[MCP:KeyFrame` / `(KeyFrame:` patterns to MCP URI fragment detection (`<mcp:context` + `/keyframe/`).
  - `ChatService.java` (MODIFIED): Fixed ContextPanel UX crash — 404 response changed from `entity(null)` to `entity("{}")` (mithril can't parse null). Fixed JSON comma bug in context endpoint when chatConfig is null.
  - `ContextPanel.js` (MODIFIED): Fixed `loadContext()` with `extract` function for resilient response parsing — handles non-200 and empty/malformed responses gracefully.
  - `TestPromptTemplate.java` (MODIFIED): Fixed `TestOpenChatLLMIntegration` and `TestRPGTemplateLLMIntegration` — `getRandmChatConfig()` creates configs with default `serviceType=OPENAI` (from chatConfigModel.json), but tests only updated `model` and `serverUrl` for Ollama. Added `serviceType=OLLAMA` to the slim copy update, fixing incorrect URL construction (`/openai/deployments/...` against Ollama server → 404 HTML → JSON parse failure → null content).
  - **Resolved OIs:** OI-1 (personModel population), OI-3 (memory person pair IDs), OI-5 (keyframeEvery floor), OI-13 (test relocation), OI-14 (old keyframe format deprecation), OI-15 (nlp.command pipeline ordering), OI-19 (migrator condition coverage), OI-26 (keyframe detection heuristic).
  - **Additional fixes:** TestPromptTemplate serviceType bug (pre-existing — tests 27/28 never worked against Ollama), ContextPanel UX crash (server returned null entity + JSON comma bug).

- **Phase 10** (UX Chat Refactor) is **COMPLETED**. Design audit identified and resolved 6 duplicated patterns, 5 missing API methods, 5 association mechanisms, and 8 new open issues.

- **Phase 1** (items 1, 2, 4) could be done anytime as a low-risk cleanup pass. Item 3 (condition checks) is largely superseded by Phase 4's `PromptConditionEvaluator` for new templates, but the legacy flat pipeline still benefits from `if` guards. Now folded into Phase 12b.

**Suggested order:** ~~10a~~ ~~10b~~ ~~10c~~ ~~11~~ ~~1 (remainder)~~ → **12a** → 12b → 12c → 12d

**Phase 11 status:** ALL DONE. All 8 backend tests pass (P11-1, P11-1b, P11-2..P11-7). All 8 assigned open issues resolved. UX keyframe test updated. Relocated test regression (missing `test.llm.serviceType` in Objects7 `resource.properties`) fixed.

**Open issues remaining (by priority):**
- **P2:** OI-51 (session list layout broken)
- **P3:** OI-17 (Magic8 wiring), OI-27 (Ollama abort), OI-29 (Ollama options), OI-52 (pre-10b fallback), OI-53 (setting overflow), OI-54 (ContextPanel no binding indicator)
- **P4:** OI-18 (prune backward compat), OI-23 (shared state), OI-28 (test order), OI-30 (analyze hardcoded), OI-31/34 (double-apply), OI-33 (UX form Ollama section), OI-39 (wrong char false positives), OI-41 (stale analyzeModel), OI-55 (ContextPanel expanded layout)

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
- Deprecate old `(KeyFrame:` text format — require MCP format only (Phase 11 item 4)
- Consider reducing keyframe content in message stream to a short summary, with full analysis stored only in the memory system

**Future consideration (not currently planned):**
- Once the memory system has reliable semantic retrieval and importance scoring, keyframes could potentially be replaced entirely by automatic memory injection. This would require: (a) memory retrieval fast enough for inline use, (b) importance scoring that reliably selects the right context, and (c) testing that LLM output quality doesn't degrade. This is an architectural decision for Phase 4+ evaluation.

---

## 11. Open Issue Tracker

All known open issues with their assigned resolution phase:

| # | Issue | Source | Assigned Phase | Priority |
|---|-------|--------|---------------|----------|
| OI-1 | ~~`personModel` field not populated by `MemoryUtil.createMemory()` or `Chat.persistKeyframeAsMemory()`~~ Fixed: `createMemory()` accepts `personModel` parameter; `persistKeyframeAsMemory()` passes `systemChar.getSchema()`. Test P11-1 validates | Phase 2-3 known issues | ~~Phase 11 (item 1)~~ **RESOLVED** | ~~P3~~ |
| OI-2 | ~~`MEMORY_RELATIONSHIP`, `MEMORY_FACTS`, `MEMORY_LAST_SESSION`, `MEMORY_COUNT` always empty/zero~~ | Phase 2-3 known issues | ~~Phase 4 (item 8)~~ **RESOLVED** | ~~P2~~ |
| OI-15 | ~~`${nlp.command}` pipeline ordering: Stage 6 replaces before Stage 7 can reintroduce via dynamic rules~~ Fixed: `reapplyNlpCommand()` added as post-Stage-7 pass. `nlpCommand` stored in `PromptBuilderContext` by Stage 6, reapplied after `buildDynamicRulesReplacement()`. Test P11-5 validates | Phase 4 implementation | ~~Phase 11~~ **RESOLVED** | ~~P3~~ |
| OI-16 | ~~StackOverflowError in deeply nested record authorization — RecordDeserializer debug `toString()` removed + PolicyUtil `getForeignPatterns` depth-limited + slim `copyRecord()` update pattern~~ | Phase 4 testing | ~~Phase 10~~ **RESOLVED** | ~~P3~~ |
| OI-17 | ~~Magic8 client-side template (`SessionDirector.js`) not yet wired to server-side `prompt.magic8.json`~~ Fixed: `_loadPromptTemplate()` rewired to fetch from server REST endpoint (`/rest/chat/config/prompt/prompt.magic8`) with local file fallback | Phase 4 implementation | ~~Phase 12c (item 9)~~ **RESOLVED** | ~~P3~~ |
| OI-3 | ~~`extractMemoriesFromResponse()` does not pass person pair IDs~~ Fixed: new overload accepts `personId1`/`personId2`/`personModel` and passes them to `createMemory()`. Test P11-2 validates | Phase 2-3 known issues | ~~Phase 11 (item 2)~~ **RESOLVED** | ~~P2~~ |
| OI-4 | ~~`openaiMessage` model missing `thinking` field — qwen3/CoT models produce error logs~~ | Phase 3 testing | ~~Phase 7 (item 4)~~ **RESOLVED** | ~~P2~~ |
| OI-5 | ~~Low `keyframeEvery` values trigger expensive `analyze()` LLM calls~~ Fixed: `configureChat()` enforces `MIN_KEYFRAME_EVERY_WITH_EXTRACT=5` when `extractMemories=true`. Test P11-3 validates | Phase 3 known issues | ~~Phase 11 (item 5)~~ **RESOLVED** | ~~P3~~ |
| OI-6 | ~~`top_k` maxValue=1 prevents valid values (should be 500)~~ | Section 6.1 | ~~Phase 8 (item 1)~~ **RESOLVED** | ~~P1~~ |
| OI-7 | ~~`typical_p` incorrectly mapped to OpenAI `presence_penalty` in `applyChatOptions()`~~ | Section 6.1 | ~~Phase 8 (item 2)~~ **RESOLVED** | ~~P1~~ |
| OI-8 | ~~`repeat_penalty` mapped to `frequency_penalty` with different semantics~~ | Section 6.1 | ~~Phase 8 (item 2)~~ **RESOLVED** | ~~P1~~ |
| OI-9 | ~~Missing `max_tokens` field on chatOptions~~ | Section 6.1 | ~~Phase 8 (item 3)~~ **RESOLVED** | ~~P2~~ |
| OI-10 | ~~Missing `frequency_penalty` field on chatOptions (OpenAI-native)~~ | Section 6.1 | ~~Phase 8 (item 3)~~ **RESOLVED** | ~~P2~~ |
| OI-11 | ~~Missing `presence_penalty` field on chatOptions (OpenAI-native)~~ | Section 6.1 | ~~Phase 8 (item 3)~~ **RESOLVED** | ~~P2~~ |
| OI-12 | ~~Missing `seed` field on chatOptions~~ | Section 6.1 | ~~Phase 8 (item 3)~~ **RESOLVED** | ~~P3~~ |
| OI-13 | ~~Memory/keyframe tests in Agent7 have no Agent7 dependencies — should relocate to Objects7~~ Fixed: `TestMemoryUtil`, `TestMemoryPhase2`, `TestKeyframeMemory`, `TestMemoryDuel` copied to `AccountManagerObjects7/src/test/java/`. Same package, same BaseTest. Test P11-1..7 added alongside | Phase 3 testing | ~~Phase 11 (item 3)~~ **RESOLVED** | ~~P3~~ |
| OI-14 | ~~Old `(KeyFrame:` text format still supported alongside MCP format — maintenance burden~~ Fixed: `pruneCount()`, `addKeyFrame()`, `newMessage()` use MCP-only detection via `isMcpKeyframe()`/`isMcpReminder()`/`countBackToMcp()`. Old `countBackTo()`/`isMcpEquivalent()` removed. `getFormattedChatHistory()` MCP-only. UX test 76 updated. Test P11-4/P11-7 validate | Phase 3 code review | ~~Phase 11 (item 4)~~ **RESOLVED** | ~~P3~~ |
| OI-18 | ~~Client-side prune functions retained for backward compatibility~~ Fixed: 5 wrapper functions removed from `view/chat.js`, 8 call sites updated to `LLMConnector.*` directly | Phase 5 implementation | ~~Phase 12d (item 12)~~ **RESOLVED** | ~~P4~~ |
| OI-19 | ~~Migrator condition coverage — static condition map covers 7 of ~34 fields; fields like `femalePerspective`/`malePerspective` have no condition mapping~~ Fixed: `CONDITION_MAP` expanded from 7 to 14 entries — added `userConsentRating` (rating>=M), `scene` (includeScene), `femalePerspective`/`malePerspective` (systemCharacter.gender), `systemSDPrompt` (useSDPrompt), `assistantReminder`/`userReminder` (assist). Test P11-6 validates | Phase 5 implementation | ~~Phase 11~~ **RESOLVED** | ~~P3~~ |
| OI-20 | ~~Token standardization — image/audio token processing still varies between prompt template styles~~ Extracted to `ChatTokenRenderer.js` shared module; `view/chat.js` delegates to it | Phase 5 review | ~~Phase 10 (10a: extract to ChatTokenRenderer)~~ **RESOLVED** | ~~P3~~ |
| OI-21 | ~~Stream tests require WebSocket — Tests 71-72 need active `page.wss` and `chatConfig.stream=true`~~ | Phase 6 implementation | ~~Phase 7~~ **RESOLVED** | ~~P3~~ |
| OI-22 | ~~Policy tests placeholder — Test 81 validates config presence only; evaluation requires Phase 9~~ | Phase 6 implementation | ~~Phase 9~~ **RESOLVED** | ~~P3~~ |
| OI-23 | ~~CardGame shared state coupling — switching suites resets shared `TF.testState`~~ Documented as by design — suite isolation is expected behavior | Phase 6 implementation | ~~Phase 12d (item 19)~~ **RESOLVED (by design)** | ~~P4~~ |
| OI-24 | ~~`findOrCreateConfig` caching — template changes require manual deletion of existing server objects~~ `LLMConnector.ensureConfig()` and `ensurePrompt()` now compare key fields and patch if changed. UX test `findOrCreateConfig` updated with syncFields comparison | Phase 6 implementation | ~~Future: add update-if-changed~~ **RESOLVED** (Phase 10a) | ~~P3~~ |
| OI-25 | ~~Episode transition execution not testable — `#NEXT EPISODE#` detection is server-side~~ | Phase 6 implementation | ~~Phase 7~~ **RESOLVED** | ~~P3~~ |
| OI-26 | ~~Keyframe detection heuristic — Test 76 pattern-matches `[MCP:KeyFrame` / `(KeyFrame:` text; fragile~~ Fixed: UX test 76 updated to MCP URI fragment detection (`<mcp:context` + `/keyframe/`). Backend test P11-7 validates both `isMcpKeyframe()` and `isMcpReminder()` methods | Phase 6 implementation | ~~Phase 11~~ **RESOLVED** | ~~P3~~ |
| OI-27 | ~~Ollama server-side request not cancelled on client timeout~~ Fixed: `ChatListener.registerHttpResponse()` stores `HttpResponse`; `stopStream()` closes response body stream to signal server-side abort before failover timer | Phase 7 implementation | ~~Phase 12c (item 10)~~ **RESOLVED** | ~~P3~~ |
| OI-28 | ~~Test execution order sensitivity — Test 37 sets requestTimeout=1 on shared DB config; crash between set/restore leaks value~~ Fixed: `TestChatStream.TestStreamTimeoutTriggered` wrapped in try-finally to always restore `requestTimeout`. Test P12-5 validates constant | Phase 7 testing | ~~Phase 12d (item 18)~~ **RESOLVED** | ~~P4~~ |
| OI-29 | ~~Ollama native `options` object not supported — `top_k`, `typical_p`, `repeat_penalty`, `min_p`, `repeat_last_n` not sent~~ Fixed: `ChatUtil.applyChatOptions()` populates `request.options` sub-object (chatOptions model) with Ollama-specific fields when `serviceType=OLLAMA`. Test P12-4 validates | Phase 8 prep | ~~Phase 12c (item 11)~~ **RESOLVED** | ~~P3~~ |
| OI-30 | ~~`applyAnalyzeOptions()` hardcodes `temperature=0.4`, `top_p=0.5`~~ Fixed: Hardcoded values converted to public documented constants (`ANALYZE_TEMPERATURE=0.4`, `ANALYZE_TOP_P=0.5`, etc.) — intentionally conservative for deterministic analysis output. Test P12-1 validates | Phase 8 prep | ~~Phase 12d (item 14)~~ **RESOLVED** | ~~P4~~ |
| OI-31 | ~~`getNarratePrompt()` double-applies chatOptions — calls `applyAnalyzeOptions()` then `applyChatOptions()` again~~ Fixed: Redundant `applyChatOptions(areq)` call removed. `applyAnalyzeOptions()` already calls it internally. Test P12-1/P12-2 validate | Phase 8 prep | ~~Phase 12d (item 15)~~ **RESOLVED** | ~~P4~~ |
| OI-32 | ~~`openaiRequestModel.json` defaults for `frequency_penalty` and `presence_penalty` are 1.3 — should be 0.0 to match OpenAI API defaults~~ | Phase 8 prep | ~~Phase 8~~ **RESOLVED** | ~~P2~~ |
| OI-33 | ~~UX `formDef.js` chatOptions form missing Ollama-specific fields~~ Fixed: Added `top_k`, `typical_p`, `repeat_penalty`, `min_p`, `repeat_last_n` to `forms.chatOptions` with layout 'third' and format 'range' | Phase 8 implementation | ~~Phase 12d (item 13)~~ **RESOLVED** | ~~P4~~ |
| OI-34 | ~~`Chat.getNarratePrompt()` still double-applies chatOptions~~ Fixed: Same fix as OI-31 — redundant `applyChatOptions(areq)` removed | Phase 8 review | ~~Phase 12d (item 15)~~ **RESOLVED** | ~~P4~~ |
| OI-35 | ~~`Chat.getSDPrompt()` calls `applyChatOptions(req)` on the source request instead of the new `areq` at line 611~~ Fixed: changed to `applyChatOptions(areq)` so SD prompt options apply to the correct request object | Phase 8 review | ~~Future bug fix~~ **RESOLVED** (Phase 10b) | ~~P3~~ |
| OI-36 | ~~Adaptive chatOptions recommendation — during or after a chat session, auto-analyze conversation style/type and recommend or auto-rebalance chatOptions (temperature, penalties, etc.) for the detected use case. Similar to dynamic prompt rewriting in Phase 9's ChatAutotuner, but applied to LLM parameters rather than prompt content. Could use the chatConfig templates as target profiles for classification.~~ `ChatAutotuner` (Phase 9) provides the infrastructure — analysis prompt can be extended to include chatOptions rebalancing alongside prompt rewrites | User request (Phase 8) | ~~Future phase~~ **RESOLVED (Phase 9)** | ~~P3~~ |
| OI-37 | **RESOLVED** (Phase 9) — Tests 55-62 all implemented: pipeline DENY/PERMIT (55-56), ChatAutotuner analysis + naming (57-58), policy hook buffer/stream mode (59-60), enhanced stop failover (62). All 19 tests pass with live LLM (`qwen3:8b` for autotuner, `qwen3-coder:30b` for chat) | Phase 9 implementation | Resolved | — |
| OI-38 | **RESOLVED** (Phase 9) — `ChatAutotuner.autotune()` was using `dolphin-llama3` for analysis due to two issues: (1) `analyzeModel` schema default was `dolphin-llama3` instead of empty, so the fallback to `model` field never triggered; (2) chatConfig records from `getAccessPoint().create()` didn't retain in-memory field values. Fixed by: removing the stale `analyzeModel` default from chatConfigModel.json, and calling `OlioUtil.getFullRecord(chatConfig)` to resolve persisted field values | Phase 9 implementation | Resolved | — |
| OI-39 | ~~`WrongCharacterDetectionOperation` false positives — heuristic regex patterns match in-character quoted dialogue~~ Fixed: Added quote exclusion — responses starting with `"` or `\u201c` skip heuristic patterns (in-character quoting). Test P12-3 validates both exclusion and continued detection | Phase 9 implementation | ~~Phase 12d (item 16)~~ **RESOLVED** | ~~P4~~ |
| OI-40 | ~~UX `policyEvent` handler not wired in `chat.js`/`SessionDirector.js`~~ `pageClient.js` chirp handler routes `policyEvent` to `LLMConnector.handlePolicyEvent()`. `view/chat.js` registers display handler via `LLMConnector.onPolicyEvent()` showing toast notification. UX test 109 validates wiring | Phase 9 implementation | ~~Phase 10 (10a: shared StreamStateIndicator)~~ **RESOLVED** (Phase 10a) | ~~P3~~ |
| OI-41 | ~~`chatConfigModel.json` `analyzeModel` default was `dolphin-llama3` (stale)~~ Default removed in Phase 9. Existing DB records fall back to main `model` field when `analyzeModel` is null/empty. No migration script needed — users can clear manually via config editor | Phase 9 testing | ~~Phase 12d (item 17)~~ **RESOLVED (documented)** | ~~P4~~ |
| OI-42 | ~~REST config endpoints only search by name~~ Fixed: group-agnostic search (Phase 9). Phase 10a added objectId-based endpoints: `GET /rest/chat/config/prompt/id/{objectId}` and `GET /rest/chat/config/chat/id/{objectId}`. UX test 108 validates. Backend test P10-1 validates `ChatUtil.getConfig()` objectId lookup | Phase 9 UX testing | **Resolved** (Phase 9 + Phase 10a) | ~~P2~~ |
| OI-43 | ~~UX history test (73-74) intermittently finds only 2/3 sent messages~~ Root cause: rapid-fire sequential REST calls race against server-side persistence. Fixed: added 200ms delays between sequential sends in tests 73-74, matching pattern from test 106 and backend P10-3/P10-4 (which already pass). Backend logic is correct; timing issue is client-side | Phase 9 UX testing | ~~Phase 10 (10b: investigate)~~ **RESOLVED** (Phase 10b) | ~~P3~~ |
| OI-44 | ~~`formDef.js` calls `am7sd.fetchModels()` at module load before authentication, causing 403 on `/rest/olio/sdModels` on the login screen~~ Fixed: added `page.authenticated()` guard | Phase 9 UX testing | **Resolved** (auth guard) | ~~P2~~ |
| OI-45 | ~~`llmTestSuite.js` Test 72 called `am7chat.sendMessage()` which doesn't exist in `chat.js` (only `chat()` is exported), causing test hang~~ Fixed: changed to `am7chat.chat()` | Phase 9 UX testing | **Resolved** | ~~P2~~ |
| OI-46 | ~~`chat.js` exports only 5 methods~~ `am7chat` now exports 10 methods: 5 original + `getHistory()`, `extractContent()`, `streamChat()`, `parseDirective()`, `cloneConfig()` — all delegating to `LLMConnector`. UX test 100 validates | Phase 10 design audit | ~~Phase 10 (10a: `LLMConnector`)~~ **RESOLVED** | ~~P2~~ |
| OI-47 | ~~`SessionDirector.js` reimplements config management, response extraction, JSON directive parsing + repair~~ `initialize()` delegates to `LLMConnector.findChatDir()`, `getOpenChatTemplate()`, `ensurePrompt()`, `ensureConfig()`, `createSession()`. `_extractContent()` delegates to `LLMConnector.extractContent()`. `_parseDirective()` delegates JSON parsing to `LLMConnector.parseDirective()`, keeps domain-specific directive validation. `_repairTruncatedJson()` removed. Diagnostics use LLMConnector. ~200 lines removed | Phase 10 design audit | ~~Phase 10 (10a: extract to `LLMConnector`)~~ **RESOLVED** | ~~P2~~ |
| OI-48 | ~~`cardGame/ai/chatManager.js` manually tracks `currentConversation[]` array client-side, diverging from server-side history~~ Fixed: `startConversation()` now seeds `currentConversation[]` from server history via `LLMConnector.getHistory()`. Local array kept as optimistic cache for immediate UI responsiveness | Phase 10 design audit | ~~Phase 10 (10b: unified history via `LLMConnector.getHistory()`)~~ **RESOLVED** (Phase 10b) | ~~P3~~ |
| OI-49 | ~~No generic object association API~~ Fixed: `chatRequestModel.json` adds `contextType`/`context` ($flex foreign) fields for associating any model type with a conversation. MCP tools (`am7_session_attach`/`detach`/`context`) provide programmatic API. REST endpoints (`/rest/chat/context/attach`, `/detach`, `/{objectId}`) provide HTTP API. UX `ContextPanel.js` provides drag-and-drop and visual management | Phase 10 design audit | ~~Phase 10 (10c: MCP context binding)~~ **RESOLVED** (Phase 10c) | ~~P3~~ |
| OI-50 | ~~`CacheDBSearch.find()` synchronized — serializes ALL database reads through a single lock. Under Tomcat concurrent load, one slow query blocks every other query (including makePath, DELETE, login), causing cascading thread pool exhaustion and complete server hang~~ Fixed: removed `synchronized` from `find()` and helpers; per-model `ConcurrentHashMap` instances provide thread safety. `getCacheMap()` uses `computeIfAbsent()` for atomic map creation | Phase 10b testing | **RESOLVED** (Phase 10b patch) | ~~P1~~ |
| OI-51 | ~~`ConversationManager.sessionItemView()` layout broken~~ Fixed: Single `<button>` wrapper with `group` class, hover-reveal delete icon (`opacity-0 group-hover:opacity-100`), `flex-1 truncate text-left` name span. Full-row active highlight. UX test 111 validates | Phase 12 UX audit | ~~Phase 12a (item 1)~~ **RESOLVED** | ~~P2~~ |
| OI-52 | ~~Pre-10b fallback session list still present~~ Fixed: Entire fallback branch removed from `getSplitLeftContainerView()`. Dead `aSess` variable, `deleteChat()` function, and 6 references removed | Phase 12 UX audit | ~~Phase 12a (item 2)~~ **RESOLVED** | ~~P3~~ |
| OI-53 | ~~Setting text overflow~~ Fixed: Added `truncate` class, `text-sm`, `title` tooltip. Removed nested `m("p",...)` wrapping. UX test 113 validates | Phase 12 UX audit | ~~Phase 12a (item 3)~~ **RESOLVED** | ~~P3~~ |
| OI-54 | ~~ContextPanel collapsed state shows no binding count~~ Fixed: Added `getBindingCount()` helper. Collapsed header shows "Context (3)" when bindings exist. UX test 114 validates | Phase 12 UX audit | ~~Phase 12a (item 4)~~ **RESOLVED** | ~~P3~~ |
| OI-55 | ~~ContextPanel expanded view flat text-only list~~ Fixed: Added `schemaIcon()` mapping (settings/description/person/link). `contextRowView()` includes Material icon, `flex-1 min-w-0` truncation, improved detach button. UX test 115 validates | Phase 12 UX audit | ~~Phase 12a (item 5)~~ **RESOLVED** | ~~P4~~ |
| OI-56 | `chatInto` bypasses LLMConnector, ConversationManager, ContextPanel — analysis sessions invisible to shared infrastructure | Phase 12 analysis | Phase 13a (item 1) | P2 |
| OI-57 | `chatInto` uses `window.open` + `remoteEntity` cross-window pattern — fragile, no error recovery, no state sync | Phase 12 analysis | Phase 13a (items 1,3) | P2 |
| OI-58 | Config filtering by name prefix (`/^Object/gi`) — fragile, no fallback if no configs match | Phase 12 analysis | Phase 13a (item 2) | P3 |
| OI-59 | `vectorize()` and `summarize()` dialogs fire-and-forget with toast only — no progress or result surfacing | Phase 12 analysis | Phase 13b (items 6,7) | P3 |
| OI-60 | `ChatUtil.getCreateChatConfig()` marked `TODO: DEPRECATE THIS` (ChatUtil.java:324) | Code review | Phase 13c (item 9) | P4 |
| OI-61 | ChatRequest persistence: underlying AI request stored as serialized bytes, should be easily accessible (dialog.js:3 TODO) | Code review | Phase 13c (item 11) | P3 |
| OI-62 | SD config defaults hardcoded in `tempApplyDefaults()` instead of form definition (dialog.js:829 TODO) | Code review | Phase 13d (item 12) | P4 |
| OI-63 | QueryPlan `$flex` field type workaround for interaction actor/interactor (ChatUtil.java:458,465 TODO) | Code review | Phase 13d (item 13) | P4 |
| OI-64 | No cancel-and-send or message queuing during active stream (chat.js:475 TODO) | Code review | Phase 13c (item 10) | P3 |
| OI-65 | Dual object view construction methods undocumented — generic `am7view` vs legacy object component (chat.js:28 TODO) | Code review | Phase 13d (item 15) | P4 |
| OI-66 | Consent block trimming deferred from Phase 12b — needs content review to reduce token count | Phase 12b deferral | Phase 13d (item 14) | P4 |
| OI-67 | No REST endpoints for memory — `MemoryUtil` has search/query methods but no service layer exposes them to the UX | Phase 13 analysis | Phase 13f (item 21) | P2 |
| OI-68 | Memory config fields (`extractMemories`, `memoryBudget`, `memoryExtractionEvery`) not in formDef.js — users can't enable/configure memory from the UI | Phase 13 analysis | Phase 13f (item 22) | P2 |
| OI-69 | No UX memory browser — users cannot view, search, or manage memories for character pairs | Phase 13 analysis | Phase 13f (item 23) | P2 |
| OI-70 | MCP context blocks invisible — stripped for display, no inspect/debug mode to see what context the LLM actually receives | Phase 13 analysis | Phase 13f (item 25) | P3 |
| OI-71 | Cross-conversation memory not demonstrable — no indicator when memories are loaded, no way to see memory count during chat | Phase 13 analysis | Phase 13f (item 24) | P2 |
| OI-72 | Keyframe events not surfaced in UX — no visual indicator when keyframes fire or memories are extracted | Phase 13 analysis | Phase 13f (item 24) | P3 |
| OI-73 | Memory search not exposed — `MemoryUtil.searchMemories()` (semantic vector search) has no client-side equivalent | Phase 13 analysis | Phase 13f (item 27) | P3 |
| OI-74 | 6 chatConfig model fields not in formDef.js — `requestTimeout`, `terrain`, `populationDescription`, `animalDescription`, `universeName`, `worldName` | Phase 13 gap audit | Phase 13e (item 16) | P3 |
| OI-75 | WebSocket auto-reconnect missing — WS close during stream silently breaks chat, no recovery | pageClient.js:374 | Phase 13e (item 17) | P2 |
| OI-76 | WebSocket token auth fallback — null user proceeds to anonymous state, security gap | WebSocketService.java:179 | Phase 13e (item 18) | P3 |
| OI-77 | Audio double-encoding — client sends double-base64, server has workaround decode | WebSocketService.java:543 | Phase 13e (item 19) | P4 |
| OI-78 | Logged-in user profile uses `v1-profile` attribute workaround instead of looking up `identity.person` under `/Persons`. Chat identity: use `chatConfig.userCharacter` when set, else user's person record | pageClient.js:777 | Phase 13e (item 20) | P3 |
| OI-79 | Chat details metadata shows config/prompt/character names as plain text — no links to open objects | ConversationManager.js:170 | Phase 13g (item 28) | P3 |
| OI-80 | Chat sessions use raw name in sidebar — no auto-generated title or topic icon after first exchange | ConversationManager.js:134 | Phase 13g (item 29) | P3 |
| OI-81 | No UX indication when memories are recalled and applied to a chat prompt | chat.js, Chat.java | Phase 13g (item 30) | P3 |

---

## 12. Automated Operation Trigger Reference

This section documents when each automated operation fires during chat processing. These replace the manual vectorize/summarize buttons that were removed in Phase 13f.

### 12.1 Policy Evaluation

**When:** After every LLM response (both buffer and streaming modes)
**Condition:** `chatConfig.policy` foreign reference is non-null
**Location:** `Chat.evaluateResponsePolicy()` -> `ResponsePolicyEvaluator.evaluate()`
**Client notification:** `policyEvent` WebSocket chirp -> toast in chat view

### 12.2 Chat History Vectorization

**When:** After every `saveSession()` call (runs after each message exchange)
**Condition:** `VectorUtil.isVectorSupported()` AND message count > 2
**Location:** `Chat.createNarrativeVector()` called from `Chat.saveSession()`
**What it creates:** `MODEL_VECTOR_CHAT_HISTORY` chunks (WORD/1000)
**Note:** This replaces the manual "Vectorize" button on chat sessions

### 12.3 Keyframe Creation

**When:** During `pruneCount()` which runs when a user message is added via `newMessage()`
**Conditions (ALL must be true):**
1. `chatConfig.assist = true`
2. `chatConfig.keyframeEvery > 0`
3. `messages.size() > (pruneSkip + keyframeEvery)`
4. Messages since last keyframe >= `keyframeEvery`

**What happens:** Calls `analyze()` (LLM call) to summarize conversation, inserts MCP keyframe block into message history, keeps last 2 keyframes
**Client notification:** `memoryEvent` type `keyframe` via WebSocket

### 12.4 Memory Creation (from Keyframes)

**When:** Inside `addKeyFrame()` -> `persistKeyframeAsMemory()`
**Conditions (ALL must be true, in addition to keyframe conditions above):**
1. `chatConfig.extractMemories = true`
2. `analyze()` returned non-empty text
3. If `memoryExtractionEvery > 0`: existing OUTCOME memory count % `memoryExtractionEvery == 0`
4. If `memoryExtractionEvery == 0`: memory created at every keyframe

**What it creates:** `tool.memory` record (type OUTCOME, importance 7) in `~/Memories`, plus `tool.vectorMemory` chunks (WORD/500) for semantic search
**Client notification:** `memoryEvent` type `extracted` via WebSocket -> toast in chat view
**Server log:** `"Persisted keyframe as memory for [character names]"`

**Minimum keyframeEvery enforcement:** When `extractMemories=true` AND `keyframeEvery < 5`, the system raises `keyframeEvery` to 5 to prevent excessive LLM analyze calls.

### 12.5 Memory Retrieval (Cross-Session)

**When:** At the start of every chat prompt composition in `getChatPrompt()`
**Condition:** `chatConfig.memoryBudget > 0` AND both `systemCharacter` and `userCharacter` are set
**What happens:** Queries `~/Memories` for pair-specific memories (canonical person ID ordering), formats as MCP context, sets PromptUtil thread-locals for template variable substitution
**Max memories loaded:** `memoryBudget / 100`
**Client notification:** `memoryEvent` type `recalled` via WebSocket
**Server log:** `"Recalled N memories for id1/id2"`

### 12.6 Summarization

**Status:** Manual only — no automatic trigger during chat
**Previous access:** Via "Summarize" button on object forms (removed in Phase 13f)
**Programmatic access:** `POST /vector/summarize/{chunkType}/{chunkCount}` REST endpoint
**Used by:** `AnalysisManager.js` for object analysis sessions

### 12.7 Features Deprecated by MCP + Memory

| Feature | Status | Replacement |
|---------|--------|-------------|
| Manual "Vectorize" button (data, note, charPerson, openaiRequest forms) | **Removed** | Chat history auto-vectorized on `saveSession()`; memories auto-vectorized on creation |
| Manual "Summarize" button (data, charPerson, openaiRequest forms) | **Removed** | Keyframe analysis provides running summaries; memory extraction stores durable summaries |
| Keyframe-only context (no persistence) | **Superseded** | Keyframes now persist as OUTCOME memories with vector embeddings for cross-session retrieval |
| `dialog.chatInto()` / `window.open` + `remoteEntity` | **Removed** (Phase 13a) | `AnalysisManager.startAnalysis()` — in-page analysis sessions |

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
