# Chat review (2026-11-17) and wardrobe-token design

Status: **proposal**. Nothing in §3 is built yet. §1 records what was measured on the running
`am7test` stack against `am72db`, using read-only queries. §2 lists the Ux fixes, one of which (mobile height) has landed.

---

## 1. Review of the live chat "Rebecca and Steve"

- Chat: `olio.llm.chatRequest` 67, which uses `chatConfig` 26.
- Connection: `system.connection` 20 "LiteLLM", `OPENAI_COMPAT` / upstream `OLLAMA`.
- Model alias: `qwen3:8b-jos`, which resolves to `ollama_chat/goekdenizguelmez/JOSIEFIED-Qwen3:8b` (`src/litellm/config.yaml:236-248`).
- Session: 6 messages (1 system, 2 user, 3 assistant).

### 1.1 Blocking: embeddings fail, so no memory or history vector can be stored

| What | Value |
|---|---|
| Boot config (`web.xml` via env) | `embedding.type=openai_compat`, `embedding.server=http://ollama:11434/v1/embeddings`, `embedding.model=nomic-embed-text`, `dimensions=768` |
| DB override: `/System` `system.connection` **`embedding` (id 6)** | `http://192.168.1.42:8123`, the custom Python service, which answers `POST /generate_embedding {"text":…}` |
| Effect | `EmbeddingUtil.getEmbedding` posts the OpenAI-shaped body to the bare `:8123` URL, gets **404**, and logs `Response was null`. That happened 52 times on 2026-11-17, and every `vectorchathistory` chunk failed with `Embedding for chunk 1 is null or empty` |

The protocol is fixed at boot and only the URL can be changed at runtime. Neither the record nor the Ux shows the type, so you can save a URL that the running protocol cannot talk to, and nothing checks it.

Both services return 768-dimension vectors. All four vector tables in `am72db` are **empty** today, so either fix is free right now:

- **A.** Repoint record 6 at `http://ollama:11434/v1/embeddings`, or blank it. This applies at runtime.
- **B.** Set `EMBEDDING_TYPE=local` and `EMBEDDING_SERVER=http://192.168.1.42:8123`, then recreate the container.

The choice is Stephen's. Once vectors exist, switching providers mixes models in one fixed-width column.

**Product fix (proposed):** when a URL is saved to the `embedding` connection, run one test embedding through the live `EmbeddingUtil` and reject the save if it returns null or the wrong width. Also show the boot-pinned `embedding.type` read-only next to the URL field.

### 1.2 Memory extraction: why there are no memories, and what makes extraction unreliable

There are zero rows in `a7_tool_memory_0_1` across the whole database. For this chat that is **expected so far**:

- The trigger is `msgSize − effectiveStart ≥ memoryExtractionEvery` (`Chat.java:5611-5620`).
- The first effective start is 2 for this config, so the first extraction needs at least 7 messages of history (`Chat.java:3673`).
- The log agrees: `Memory check: … msgSize=6 lastMemoryExtractionAt=0 introOverhead=2`.

Even after the threshold is reached, the following can drop extraction silently. They are ordered by likelihood for an 8B model:

1. **The JSON parse is fragile.** `MultiAspectMemoryParser` (`:53-83`) strips one leading code fence and nothing else. A `<think>` block or any prose before the JSON produces an empty list, logged only as INFO "0 drafts". `ConversationQualityPlan.md:412` promises a retry; none is implemented. The user's `systemAnalyze` directive is also prepended to the JSON-only prompt (`Chat.java:3048-3053`), which makes prose output more likely.
2. **The echo gate.** If assistant messages in the segment echo each other above `memorySkipEchoThreshold` (0.7), extraction is skipped (`Chat.java:3987-4012`). qwen3:8b echoes, so extraction stops exactly when the chat starts looping.
3. **Embeddings (§1.1).** Retrieval and storage of vector memory need them.
4. **MultiAspect ignores settings.** It returns early (`Chat.java:3139-3143`), so `memoryExtractionMaxPerSegment` and `memoryExtractionTypes` have no effect, and dedup does not run (dedup exists only on the V2 path, `MemoryUtil.java:439-455`). DISCOVERY is never produced (`MultiAspectMemoryParser.java:26-31`), and INSIGHT has no retrieval budget share (`Chat.java:5447-5451`).
5. **New memories are down-weighted.** The 30-minute recency half-life (`Chat.java:5729`) suppresses memories created in the current session.
6. **Async slot contention.** Extraction shares the per-chat async slot (`Chat.java:3929`) and the global ≥4-active-calls gate (`:3906`).

**Fixes (proposed, Objects7):**

- Strip `<think>…</think>` and any leading prose up to the first `[` or `{` before `readTree`.
- Retry once with "Return ONLY the JSON array" when the result is 0 drafts.
- Don't prepend `systemAnalyze` to JSON-output prompts.
- Log 0-draft results at WARN and include the first 200 characters of the response.
- Either honour `maxPerSegment`, `types` and dedup on the MultiAspect path, or hide those fields in the Ux when MultiAspect is selected.
- Map a DISCOVERY aspect, and give INSIGHT a budget share.

### 1.3 chatOptions for this model

The persisted values are the fixed **"RPG / Creative" preset** (`formDef.js:5165-5172`). All six presets are keyed by task, and nothing reads the model's real context window.

| Field | Now | Recommend | Reason |
|---|---|---|---|
| `num_ctx` | 131072 | **32768** (max 40960) | The real window is 40960 (`config.yaml:133`). The request-body value reaches Ollama and wins (`:107-120`). Memory extraction inherits it (`Chat.java:3093`). An oversized KV cache costs VRAM, and switching between 131072 and the analyze calls' 8192 forces model reloads |
| `max_tokens` | 16384 | **1536** | An output cap. It bounds runaway or looping replies |
| `frequency_penalty` / `presence_penalty` | 0.2 / 0.3 | n/a | Pruned on the OPENAI_COMPAT+OLLAMA route (`Chat.java:4432-4434`), so they are dead settings |
| `repeat_penalty` / `repeat_last_n` | 1.1 / 100 | 1.12 / 128 | The only anti-repetition knobs that reach Ollama |
| `think` | false | false | Correct. Sent explicitly |
| `temperature` / `top_p` / `top_k` / `min_p` | 0.8 / 0.95 / 60 / 0.05 | keep | |
| `memoryExtractionEvery` | 5 | **4** | Messages arrive in pairs. An even number fires on a consistent side of the exchange |
| `memorySkipEchoThreshold` | 0.7 | **0.85** | See §1.2 item 2 |
| `memoryRecencyHalfLifeMinutes` | 30 | **10** | See §1.2 item 5 |
| `analyzeTimeout` | 300 | **200** | LiteLLM times out at 240 s (`config.yaml:241`). A client timeout above that is never reached |

Related defects:

- `LLMConnector` clone (`:302-315`) drops `think`, `analyzeModel` and every `memoryExtraction*` field.
- The form shows `typical_p`, which is always pruned (`Chat.java:4500`).
- Stale docs: `chatRefactor.md:2368` still describes `memoryExtractionEvery` as counting keyframes; the `formDef.js:5569` hint says blank means V2, but the schema default is MultiAspect.

---

## 2. Chat Ux: fixes and simplification

### 2.1 Mobile height (landed 2026-11-17)

**Cause:** every route shell (`router.js` `pageLayout`, and the `chat`, `games`, `cardGame`, `biometrics` and `testHarness` routes) used inline `height:100vh`. In mobile browsers `100vh` is the *large* viewport, which includes the area behind the URL bar. The column was therefore taller than the visible screen, so the page scrolled the nav bar away to reveal the input.

**Fix:**

- Added a shared `.app-shell` class in `main.css` (`height:100vh; height:100dvh`).
- Added `dvh` to `content-outer` and to the aside `max-height`.
- Added `interactive-widget=resizes-content` to the viewport meta, so Android Chrome shrinks the layout when the soft keyboard opens and the input stays visible.

**Not verifiable in Playwright:** emulated Chromium has no collapsing URL bar, so `100vh` equals `100dvh` there. This needs a check on a real phone.

### 2.2 Proposed simplifications

1. **Sidebar on narrow screens.** The chat sidebar is a fixed `w-64` plus its icon strip, which leaves about 100 px for messages on a phone. Below `md`, start it collapsed and open it as an overlay drawer.
2. **Portrait header** collapses to an avatar row below `md`.
3. **Settings driven by model capabilities.** Query LiteLLM `/model/info` (or Ollama `/api/show`) for `context_length` when a model is picked. Default `num_ctx` to `min(32768, context_length)` and clamp the field to it.
4. **Hide fields per route.** Hide dialect-dead fields, such as the penalties and `typical_p` on OPENAI_COMPAT+OLLAMA, instead of letting users tune no-ops.
5. **One memory control.** Collapse the 15 memory fields into one selector, *Off / Light / Standard / Rich*, with an "Advanced" disclosure. Hide fields that the chosen extraction prompt ignores.
6. **Show memory health on `MemoryPanel`:** last extraction, drafts produced, and the last skip reason (echo, slot, parse). The skip reasons already exist as log lines; return them on the chat response instead. Today the only way to learn why memories are missing is to grep Tomcat.
7. **Fix `LLMConnector` clone** so it copies every chatConfig field (§1.3).

---

## 3. Wardrobe tokens

### 3.1 Grammar

`me` and `you` are relative to the **speaker** of the message:

- In an assistant reply, `me` is `systemCharacter`.
- In a user message, `me` is `userCharacter`.
- `me` may be omitted.

| Unresolved (what the LLM or user writes) | Resolved (what is stored) | Effect |
|---|---|---|
| `${dress.me.SUIT}` or `${dress.you.BASE}` | `${dress.<apparelOid>.me.SUIT}` | Every wearable of the active apparel at or below the level is `inuse`; everything above is not (`Dress`/`Undress` semantics) |
| `${dress.me.up}` | `${dress.<apparelOid>.me.ACCENT}` | Next populated level up |
| `${dress.me.down}` | `${dress.<apparelOid>.me.BASE}` | Turn off the current top level |
| `${outfit:boxer shorts,tuxedo pants,tuxedo shirt,tuxedo jacket,shoes}` | `${outfit.<apparelOid>.me:boxer shorts,…}` | New apparel built from the items, added to the speaker's store, set active, all items worn |
| `${outfit.you:corsage}` | `${outfit.<wearableOid>.you:corsage}` | Add the item(s) to the *other* character's **active** apparel, worn |
| `${give.you:silver ring}` | `${give.<wearableOid>.you:silver ring}` | Move the wearable from the speaker's apparel to the other's active apparel, worn |

**Key decision: relative operations are rewritten to absolute state.** `up` and `down` resolve to the level they produced. The resolved form is therefore idempotent. Re-applying it gives the same wardrobe, which is what makes re-evaluation (§3.4) safe. The objectId in the resolved form means "already exists, do not create".

### 3.2 Where tokens are processed: server side, on receipt

> Stephen's original ask was the `${image}` pattern: a client pass after completion (`chat.js:206-256`),
> where the presence of an objectId means "done". That pattern already prevents repeat work, so it
> would also work here. Agreed 2026-11-17: **try server-side first.** If it fights us, fall back to the
> client pattern; the token grammar is the same either way.

Why the server is tried first. These are small advantages, not blockers for the client pattern:

- **Ordering.** The wardrobe write lands before `saveSession`, so the next turn's prompt is guaranteed to see the new outfit. With a client pass, the user's next message can race the patch.
- **One writer.** Two open tabs can't both resolve the same unresolved token before the first patch lands.
- **No round-trip.** The stored history holds the resolved token from the start; there is no re-query, patch and reload.

**Hook points:**

- **Assistant replies:** `Chat.handleResponse`. It runs on the non-stream path at `Chat.java:1046` and the stream path at `ChatListener.java:338`, before `saveSession`, so the stored history holds the resolved token from the start, with no client patch round-trip.
- **User messages:** `Chat.newMessage`, before the message is appended.

**Code location:** business logic goes in a new **Objects7** `olio/llm/ChatWardrobeTokenUtil` (parse, resolve, apply). This respects the layering rule: Service7 stays transport-only. All writes are made **as the chat owner through `AccessPoint`** and never through `RecordUtil` or as admin.

**Registration:** add `dress.`, `outfit.` and `give.` to `PromptConfigValidator.RUNTIME_PREFIXES` (`:30`) and `PromptTemplateComposer` (`:150-160`).

**Client side:** `ChatTokenRenderer` renders resolved tokens as small chips (for example "👔 Bob put on: tuxedo…") instead of hiding them, so the reader can see what changed.

### 3.3 Reuse map

| Need | Existing code |
|---|---|
| Item names to catalog entries (fuzzy: "tuxedo shirt" falls back to "shirt") | `PbWardrobeUtil.catalogItems` (`:1438-1486`). Package-private, so promote it to `ApparelUtil` |
| Build apparel and wearables (level, gender, location, fabric, colour) | `ApparelUtil.constructApparel` (`:466`), `applyGuessedColors` (`:481`), `applyFabric` (`:667`) |
| Add to store and make active | `AccessPoint.member(user, store, "apparel", …)` (`PbWardrobeUtil:539`), `PbWardrobeUtil.setDefault` / `flipInUse` (`:561-656`) |
| Set wear level | `actions/Dress.java:93-110` / `Undress.java:89-93` semantics. Each wearable is patched with `id + objectId + name + inuse`; `name` is required (KI-35) |
| Add one wearable to an apparel | `AccessPoint.member(user, apparel, "wearables", wearable, null, true)`. Nothing in the codebase does this yet |
| Description the next prompt sees | `${system.characterDesc}` reads live `inuse` wearables (`NarrativeUtil.describe`). **`characterDescLight` omits apparel** (`prompt.sms.json:21`), so a template using it never sees wardrobe changes |
| Portrait | `POST /olio/{type}/{oid}/reimage` plus `reimage.js:124-380` (per-character `sdcfg-<oid>`) |

**Gaps that need new code:**

1. **Items the catalog doesn't know.** `corsage` matches nothing. Create a free-form wearable with that name, at a level from a small keyword table (jewelry, corsage, pin → ACCESSORY 8; else 6), with gender `u`. Log it, and never drop it silently.
2. **Wearable transfer** between characters. `Transfer` and `ItemUtil` work on inventory entries, not apparel participations. The transfer is: remove from the source apparel's `wearables`, add to the target's.
3. **A non-book variant of the apparel builder.** `createAlternate` is gated on a book. Chat needs the same steps keyed only on the character.
4. **Cache.** After a write, the next turn's `ChatUtil.getChat → getFullRecord` must see it. Clear `olio.charPerson`, `olio.apparel` and `olio.wearable` caches for those ids (see the nested-cache trap in `model-api.md`).

### 3.4 Base-layer rule, re-evaluation, starting wardrobe, portrait

**Base-layer rule:**

- **Prompt (primary).** The token instruction says: *every `${outfit:…}` that dresses a character must include the base (underwear) layer, plus whatever else the scene calls for.*
- **Server (backstop).** If a new outfit has no item at level ≤ BASE (4), add the gender default from the catalog: `boxer shorts` for m; `bra` + `panties` for f; `undershirt` for u. Record the added item in the resolved token, so the transcript shows the real outfit.

**Re-evaluation:** a "Re-apply wardrobe" action (chat menu), backed by a facade call in Objects7. It walks the session's messages in order and applies every resolved token, all of which are idempotent. It resolves any still-unresolved token and patches it in place, using the same mechanism as `patchChatImageToken`. It is also the repair path after a failed write.

**Starting wardrobe:**

- New chat-setup step "Choose outfit" for the **user** character. It reuses the PictureBook wardrobe panel's list, add and set-default flow (`pictureBookCharacters.js:405-501`), un-gated from the book.
- The choice is emitted into the user's intro message as `${outfit.<apparelOid>.me}`.
- The system character's starting outfit is whatever is active, or whatever the LLM's first `${outfit:…}` sets.
- Re-evaluation therefore starts from a known baseline.

**Portrait:** an optional `chatConfig.autoPortraitOnWardrobe` field (default off). When a resolved token changed a character, the chat response returns `wardrobeChanged:[charOid…]`. The client runs the existing `reimage()` for each one, debounced to one per character per turn, and refreshes the portrait header.

### 3.4a Quick-open wardrobe: fix or customize in place

Stephen's requirement: a way to open the new wardrobe quickly, to fix issues or customize it.

**Entry points**

- **Token chip.** Click a rendered `${outfit…}`, `${dress…}` or `${give…}` chip to open that apparel, using the objectId in the resolved token.
- **Portrait header.** A "Wardrobe" button on each character opens their *active* apparel.

**Dialog.** A pop-in `Dialog.open`, following the outfit dialog at `features/pictureBookCompose.js:1078-1112`. It is not a new route or tab.

| Section | Contents | Backing |
|---|---|---|
| Items | One row per wearable: name, level, worn toggle, remove | Wearable patch `id + objectId + name + inuse` (KI-35); `member(…, "wearables", …, false)` to remove |
| Wear level | A slider over the levels present ("dress to level") | Same semantics as `${dress.X.LEVEL}` |
| Add item | Text box with catalog autocomplete (`ApparelUtil.getApparelCatalogNames`). Unknown names become free-form items | The same resolver the tokens use, so the dialog and tokens never disagree |
| Switch outfit | The character's other apparel, with "make active" | `PbWardrobeUtil.setDefault` semantics, un-gated from book |
| Actions | **Regenerate portrait** (`reimage()`), **Open full editor** (`#!/view/olio.apparel/<oid>`, as `pictureBookCharacters.js:866` does), Close | Existing |

**Rule.** The dialog edits **current state**. It does not rewrite the tokens in the transcript. "Re-apply wardrobe" (§3.4) replays the transcript tokens, so it can undo manual edits. The re-apply action warns about this first, and it never runs automatically.

**Playwright addition (§3.6):**

1. Click Bob's corsage chip; the dialog opens on Bob's active apparel.
2. Toggle the jacket off, then reload. The jacket stays off and the transcript token is unchanged.

### 3.4b Action notes: tell the history what the token *did*

Stephen, 2026-11-17: a bare `${dress.me.down}` in history could be read as a negative comment, and nothing in the history says what action it implied. An image can be described by its pose.

**Rule.** When a token is resolved, the resolver appends a short system-written note **immediately after** the token:

```
${dress.<oid>.me.BASE}<private>[wardrobe] Rebecca took off her mini dress and tank top; she is now in her bra and panties.</private>
${outfit.<oid>.you:jewelry:corsage:ACCESSORY:u:chest}<private>[wardrobe] Sally pinned a corsage on Bob.</private>
${outfit.<oid>.me:…}<private>[wardrobe] Bob got dressed: boxer shorts, undershirt, tuxedo pants, tuxedo shirt, tuxedo jacket, shoes.</private>
${give.<oid>.you:jacket}<private>[wardrobe] Rebecca handed her jacket to Stephen; he is now wearing it.</private>
${image.<oid>.selfie,casual}<private>[image] Rebecca sent a casual selfie: <pose / caption>.</private>
```

**Why append and not wrap.** The Ux strips `<private>`, `<think>` and `<thought>` *before* it resolves tokens (`ChatTokenRenderer.processContent`: `pruneForDisplay` runs first; `THOUGHT_TAGS`, `:26`). A wrapped token would disappear from display: no image, no chip. An appended note is hidden by the same pass while the token still renders.

**Why it reaches the model.** The server strips `<private>` only from the ephemeral `displayContent` (`ChatUtil.populateDisplayFields`, `:1741`). The stored `content`, which is what goes back to the LLM, keeps it. So the note is in the history on every later turn and invisible in the chat.

**Why `<private>` and not `<think>`.**

- `<private>` is the character-notes tag both prompt generations already use for "status and notes".
- `<think>` is a model-special tag for qwen3. I have **not verified** whether the qwen3 chat template drops `<think>` blocks from earlier assistant turns. If it does, a `<think>` note would never reach the model. This needs a check (one request, then read the Langfuse trace) before relying on any tag other than `<private>`.

**Content rules**

- **Written from state, not by an LLM.** The text is built from the before/after `inuse` diff (for `dress`/`give`) or the item list (for `outfit`). It is free, instant and accurate. It is third person and past tense, names the actor and the target, and stays short (one sentence, or about 30 tokens).
- **Marked.** The `[wardrobe]` / `[image]` marker tells the resolver its own notes apart from the model's `<private>` notes. Re-evaluation (§3.4) regenerates a marked note and never touches unmarked ones.
- **Idempotent.** Resolving or re-evaluating twice never stacks two notes.
- **User tokens too.** A note is the only way the model learns that *Sally* pinned the corsage, rather than reading an ambiguous token in the user turn.
- **Prompt line.** Add to the token instruction (all templates): "Notes like `<private>[wardrobe] …</private>` after a token are written by the system and describe what really happened. Never write them yourself."

**Images**

- `${image}` is resolved by the client (`chat.js` `resolveChatImages` → `patchChatImageToken`). The note is appended in the same patch, so this part does not wait for §3.2.
- **Note content: use the best description available.**
  1. The tagger caption from `ImageTagUtil` (what `buildImageMcpContext`, `Chat.java:1721-1790`, already fetches for user messages).
  2. Otherwise the tags, as in "a casual selfie".
- **Pose (open question).** Optionally, teach the model to put the pose in the note itself, e.g. `${image.selfie,casual}<private>[image] pose: leaning on the porch rail, laughing</private>`. The resolver would then (a) feed that pose into the SD prompt for the generated image and (b) keep the note as the history description. That turns the note from a record into an input.
- **Assistant images.** Today they get no description at all. The MCP enrichment runs only on incoming user messages (`Chat.java:1028-1033`), so these notes are the first description assistant images will get.

**Cost.** About 20–35 tokens per resolved token. With pruning on (§1.3), that is a rounding error.

### 3.5 Prompt instruction (landed 2026-11-17)

The instruction is in three templates:

- `AccountManagerUx752/public/media/prompts/chatPrompt.json` (rule 7)
- `AccountManagerObjects7/.../olio/llm/alg.prompt.json` (bullets under rule 3)
- `prompt.sms.json` (media section)

**Live chats keep their copy.** Live chats use a `promptConfig` stored in the DB, such as `alg2.prompt` (id 1, imported, no source file in the repo), so they do not see the instruction until it is re-imported or pasted in.

**Items use the existing embedded wearable format**, `category:name:level:gender:location`. This is the format `ChatUtil.getAutoOutfit` already uses, and `ApparelUtil.applyAutfit` and `applyEmbeddedWearable` already parse it:

- Each item carries its own level and location, so items the catalog doesn't know (for example `jewelry:corsage:ACCESSORY:u:chest`) need no lookup.
- Locations are joined with `+` only. A `,` inside a location would split the token.
- Locations must be in the `olio.wearable.location` limit list. The instruction lists a subset.
- Bare names (`${outfit:boxers,tuxedo pants}`) remain a server-side fallback through the catalog matcher (§3.3), for when an 8B model drops the format.

**The instruction teaches:**

- the base-layer rule ("ALWAYS include the BASE layer (underwear)");
- the `WearLevelEnumType` names as LEVEL (BASE, ACCENT, SUIT, GARNITURE, ACCESSORY, OVER, OUTER, FULL_BODY), each described from the enum's own comments. `WearLevelEnumType.fromToken` parses a name or a number, so `applyEmbeddedWearable` accepts both (the catalogs keep numbers). An unrecognized level now skips that item with an ERROR log, instead of throwing `NumberFormatException`. `ApparelUtil.constructApparelFromEmbedded` is the public in-memory builder for `${outfit:…}`. Tests: `TestWearLevelEmbed`;
- `${outfit.you:…}`, `${dress.me|you.up|down|LEVEL}` and `${give.you:…}`;
- the resolved `#` form;
- "react to the user's wardrobe tokens".

**Validator.** `PromptConfigValidator.RUNTIME_PREFIXES` now includes `dress.`, `outfit.`, `outfit:`, `give.` and `give:`. `PromptTemplateComposer.findUnreplacedTokens` delegates to `PromptConfigValidator.isRuntimeToken` (one list, not two). `TestPromptTemplate#TestWardrobeTokensAreRuntime` and `TestSMSTemplate` cover this.

**Until §3.2 is built, these tokens are not processed.** A model following the instruction emits them, and they render as raw text.

### 3.6 Tests: Bob (system) and Sally (user), "Wedding Day"

All tests run as `ensureSharedTestUser()` or a dedicated test user, never admin.

**Objects7 JUnit, `TestChatWardrobeTokens`, against the live DB:**

1. **Parse:** every row of §3.1, unresolved and resolved forms, plus malformed input such as `${outfit:}` and unknown levels.
2. **Outfit:** Bob's reply `${outfit:boxers,tuxedo pants,tuxedo jacket,tuxedo shirt,shoes}`. Check that:
   - the apparel exists in Bob's store and is active;
   - all five wearables are `inuse`;
   - "boxers" maps fuzzily to `boxer shorts`;
   - the token is rewritten with the apparel objectId.
3. **Base-layer backstop:** `${outfit:tuxedo pants,shoes}` gets `boxer shorts` added and recorded in the token.
4. **Other character:** Sally's message `${outfit.you:corsage}`. The free-form corsage is added to **Bob's** active apparel, worn, at ACCESSORY level, and Sally's wardrobe is unchanged.
5. **Up/down:** `${dress.me.down}` then `${dress.me.up}` restore the original state. The resolved tokens carry absolute levels.
6. **Idempotence and re-evaluation:** applying resolved tokens twice, and running re-evaluation, gives an identical `inuse` set and creates no duplicate records.
7. **Prompt:** the next turn's `${system.characterDesc}` mentions the tuxedo and the corsage. This catches the cache trap.
8. **Live LLM (gated, `[LLM-GATE]` line quoted):** with the instruction in place, Bob's model reply to "It's our wedding day, get dressed" contains an `${outfit:…}` token that includes a base-layer item.

**Playwright `chatWardrobe.spec.js`, `--workers=1`:**

1. Choose Sally's outfit in the setup step.
2. Send a message containing `${outfit.you:corsage}`.
3. Check that the chip renders, reloading does not create a second corsage, and the "Re-apply wardrobe" action leaves the count unchanged.
4. With `autoPortraitOnWardrobe` on, check that Bob's portrait objectId changes.

### 3.7 Open questions for Stephen

1. ~~Server-side or client-side processing?~~ Decided 2026-11-17: try server-side; the client `${image}` pattern is the fallback.
2. Should `${outfit:…}` *replace* the active apparel (new apparel set active, as proposed) or *merge* the items into it?
3. For `${outfit.you:…}` from the user: should the target always be the other character, or should the user be able to name the character?
4. Should portrait regeneration be automatic per turn (cost: one SD job per change) or a one-click "update portrait" chip?
5. Should `${give…}` cover only worn wearables, or also `store.items` (the inventory entries `Transfer` already handles)?
