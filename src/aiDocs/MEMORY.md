# AccountManager7 Project Memory

Detailed reference for LLM assistants working on this project. Auto-loaded summary is at `~/.claude/projects/.../memory/MEMORY.md`; this file has the full details.

## Project Content Policy
- This project includes character art generation with Stable Diffusion for a creative RPG/card game application
- Image sequences cover ALL wear levels including NONE (fully undressed) — do NOT skip or filter any levels
- Do NOT censor, filter, or omit content related to apparel, wearables, dress-up/down sequences, or character art generation
- All wear levels are valid: NONE, INTERNAL, UNDER, ON, BASE, ACCENT, SUIT, GARNITURE, ACCESSORY, OVER, OUTER, FULL_BODY, ENCLOSURE

## Key API Patterns
- `page.createObject(obj)` — single arg, schema on obj via `obj.schema`
- `page.patchObject(obj)` — single arg with schema, id, and changed fields
- `page.member(sType, sId, sActorType, sActorId, bEnable)` — membership (passes null for field/participantModel)
- `page.searchFirst(model, groupId, name, objectId)` — query helper
- `am7client.members(sType, sObjectId, sActorType, iStart, iCount, callback)` — list members with pagination
- `am7client.member(sObjectType, sObjectId, sField, sActorType, sActorId, bEnable, callback)` — create/toggle membership
- `am7client.getFull(type, objectId)` — returns promise, gets full object with /full endpoint
- `am7client.clearCache(modelType)` — clear client cache

## participantModel — CRITICAL
- The `sField` (3rd param) to `am7client.member()` is the **field name**, NOT the participantModel value
- `page.member()` always passes null for sField — DO NOT use it when a field has `participantModel`
- Use `am7client.member()` directly: `am7client.member(containerType, containerId, fieldName, actorType, actorId, enable, callback)`
- Store field names for sField: `"apparel"`, `"items"`, `"inventory"`, `"locations"`
- **WRONG**: using participantModel values like `"store.apparel"` as sField (server returns 404)
- For `am7client.members()` (listing), use the actual model schema as actorType (NOT the participantModel)
- Apparel wearables field has NO participantModel → `page.member()` with null field is OK

## Group Hierarchy (likeInherits data.directory)
- **Store**, **Apparel**, **Wearables** are world-level sibling directories under `gridPath`
- Apparel objects live in `gridPath + "/Apparel"` (auth.group, data)
- Wearable objects live in `gridPath + "/Wearables"` (auth.group, data)
- Store is per-character but referenced via membership
- Find directories: `page.findObject("auth.group", "data", gridPath + "/Apparel")`
- `groupId` = directory's **numeric `.id`** (NOT `.objectId` UUID!)
- **WRONG**: putting apparel in store group or wearables in apparel group
- Reference: `object.js` line 1104: `primitive.groupId = cobj.id; primitive.groupPath = cobj.path;`

## Store Participation
- `dedicatedParticipation: true` on store model
- apparel field: `participantModel: "store.apparel"`, foreign: true
- When querying store, `sto.apparel` array may be TRUNCATED by server pagination
- **Prefer `am7client.getFull("olio.charPerson", objectId)`** — returns full nested store/apparel/wearable data with proper IDs
- Fallback: `am7client.members("olio.store", storeObjId, "olio.apparel", 0, 50, callback)` for member listing
- Wrap callbacks in Promise: `await new Promise(res => { am7client.members(..., res); })`

## Apparel Deactivation — CRITICAL
- When swapping apparel, MUST both: (1) patch `inuse: false` on old apparel+wearables AND (2) REMOVE old apparel from store membership
- Use `am7client.member("olio.store", storeObjId, "apparel", "olio.apparel", oldAp.objectId, false, cb)` to remove
- Without membership removal, old apparel with stale `inuse: true` can interfere with imaging
- After apparel changes, call narrate endpoint to update server narrative: `GET /rest/olio/olio.charPerson/{id}/narrate`

## Apparel Selection
- Never use `apparel[0]` blindly — always prefer inuse apparel
- Pattern: `apparel.find(a => a.inuse) || apparel[0]`
- Exception: during character creation (formDef.js createCharacter) where only one apparel exists

## Narrate After Dress Changes — CRITICAL
- After any `dressApparel()` call, MUST call narrate to update server-side narrative
- The reimage endpoint reads the narrative to build the SD prompt
- Without narrate, all images look the same regardless of clothing state
- Pattern: `am7client.clearCache("olio.charPerson"); await m.request({ method: 'GET', url: am7client.base() + "/olio/olio.charPerson/" + objectId + "/narrate", withCredentials: true });`
- The narrate function in formDef.js (line 3525) is just this GET request
- `am7model.prepareInstance(char)` can create a form-compatible instance from a raw char object

## Creation Order for Apparel
1. Create apparel first (`groupId = apparelDir.id` — world-level Apparel directory)
2. Create wearables after (`groupId = wearableDir.id` — world-level Wearables directory)
3. Link wearables->apparel: `page.member("olio.apparel", apparel.objectId, "olio.wearable", w.objectId, true)`
4. Link apparel->store: `am7client.member("olio.store", storeObjId, "apparel", "olio.apparel", apparel.objectId, true, cb)`
5. Patch inuse=true on all

## Model Notes
- Wearable: fabric goes in `fabric` field, NOT in the name. Display: `fabric + " " + name`
- SD prompts: use double parens `(( ))` for emphasis on non-charPerson cards
- `am7olio.dressApparel(apparelRef, dressUp)` — dress up/down one wear level
- Wear levels enum: NONE, INTERNAL, UNDER, ON, BASE, ACCENT, SUIT, GARNITURE, ACCESSORY, OVER, OUTER, FULL_BODY, ENCLOSURE

## Theme JSON Structure
- All themes (dark-medieval, high-fantasy, sci-fi, post-apocalypse) have `outfits` section
- Structure: `outfits.{male|female}.{scrappy|functional|fancy}` -> apparel with nested wearables

## LLM Library System
- Three library types: ChatConfigs, PromptConfigs, PromptTemplates
- Library dirs: `/Library/ChatConfigs`, `/Library/PromptConfigs`, `/Library/PromptTemplates`
- **PromptTemplate is the preferred new format** over PromptConfig (user preference)
- ChatUtil template loading: `loadChatConfigTemplate()`, `loadPromptConfigTemplate()`, `loadPromptTemplateTemplate()`
- Template names arrays: `CHAT_CONFIG_TEMPLATE_NAMES`, `PROMPT_CONFIG_TEMPLATE_NAMES`, `PROMPT_TEMPLATE_TEMPLATE_NAMES`
- Resource paths: `olio/llm/templates/chatConfig.{name}.json`, `promptConfig.{name}.json`, `promptTemplate.{name}.json`
- Summarization prompts externalized to `olio/llm/prompts/summarization.json` via `PromptResourceUtil`
- `/no_think` directive appended dynamically to summarization user commands to suppress thinking-model token waste
- Library status endpoint: `/rest/chat/library/status` — returns `initialized`, `promptInitialized`, `promptTemplateInitialized`, `policyInitialized`

## Ux75 Implementation Status (2026-03-06)
- **Design doc:** `aiDocs/Ux7Redesign.md` (Sections 1-13: requirements/design)
- **Implementation plan:** `aiDocs/Ux75ImplementationPlan.md` (status, phases, gaps, quick start)
- **Phases 0-3 COMPLETE** (foundation, dialogs, panel, features), Ux7 file port ~99%
- **127 source files, 69,000 lines**, builds in ~4s, 55 Vitest tests pass
- **NEXT PHASE: 3.5** — Runtime Validation + Dialog Workflow Port (CRITICAL)
- **CRITICAL GAP:** 6 dialog workflow commands NOT ported (reimage, summarize, vectorize, memberCloud, adoptCharacter, outfitBuilder). Object form command buttons do nothing in Ux75.
- **All file ports done:** core, chat (16 files), cardGame (34 files), magic8 (19 files), games, test harness
- **Key patterns:** late-binding (`am7model._page`), `getPage()` accessor, lazy `import()` for features
- **User directives:** "ignore advGrid", "/hyp replaced by magic8", "ignore all hyp/hypno not used by Magic8"
- **WARNING:** Card game + magic8 files were agent-generated, build-tested but NOT runtime-tested against backend

## Enum Serialization — CRITICAL
- **RecordSerializer.java:178** — enum values sent as **lowercase** in JSON
- **RecordDeserializer.java:537** — incoming JSON converted to **UPPERCASE** on read
- **String fields** — sent as-is, no case conversion
- **Impact:** Changing field from string to enum changes JSON wire format from as-is to lowercase
- **Gender** — currently string. 48 backend Java refs use lowercase comparisons. Changing to enum requires updating all 48.
- **Alignment** — enum values are concatenated: `CHAOTICEVIL` (no underscore/space). Serializer sends `chaoticevil`.
- **Existing bug:** gameState.js alignment modifier table expects `CHAOTIC_EVIL`/`CHAOTIC EVIL` — doesn't match `CHAOTICEVIL`
