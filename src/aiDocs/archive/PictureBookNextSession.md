# Picture Book — Next Session Prompt

**Date:** 2026-03-31
**Predecessor:** This session implemented #15 (chatOptions save), #3 (meta cleanup), #12 (decouple identity → ~/PictureBooks/), #16 (chunked extraction + scene editor), View Picture Book button fix, and createCharPerson narrative ClassCastException fix. 46/46 Playwright tests green. Vitest 258 pass.

---

## *** READ THESE FIRST ***

1. `aiDocs/PictureBookRevisions.md` — full revision plan with status of each item
2. `aiDocs/PictureBookDesign.md` — original design doc
3. `aiDocs/PictureBookTestPlan.md` — E2E test plan (phases A-L)
4. `AccountManagerUx752/e2e/pictureBookLive.spec.js` — existing 47-test Playwright suite
5. `AccountManagerUx752/src/features/pictureBook.js` — viewer + work selector
6. `AccountManagerUx752/src/workflows/pictureBook.js` — wizard dialog
7. `AccountManagerUx752/src/workflows/sceneExtractor.js` — API client layer
8. `AccountManagerService7/src/main/java/org/cote/rest/services/PictureBookService.java` — backend

---

## What Was Done (2026-03-31)

### #15 — ChatConfig Options Save
`olio.llm.chatOptions` has `ioConstraints: ["unknown"]` — embedded model. Sub-form save was trying to PATCH it independently (failed silently). Fixed `object.js` to detect embedded sub-models and merge changes into parent patch instead.

### #3 — Meta Save Cleanup
Removed `Scenes/` directory fallback from `loadViewer()` in `pictureBook.js`. Meta uses `data.note` with unlimited `text` field — GET /scenes confirmed working (B.3 test passes).

### #12 — Decouple Picture Book Identity
- Backend: `extract` creates `~/PictureBooks/{bookName}/` group, stores Scenes/Characters/meta there. Returns `bookObjectId`.
- `listScenes`, `reorderScenes`, `reset` now use `{bookObjectId}` path param (book group objectId).
- `reset` deletes entire book group in one operation.
- Frontend: route changed to `/picture-book/:bookObjectId`. Work selector browses `.pictureBookMeta` notes, uses `bookObjectId`. Wizard captures `bookObjectId` from extract response.
- Meta now has `sourceObjectId` (source doc) + `bookObjectId` (book group).

### #16 — Chunked Scene Extraction + Scene List Editor
- Backend: `POST /{workObjectId}/extract-chunked` — splits text into ~2000-char chunks with 200-char overlap, processes each with LLM using running scene context. Returns `{sceneList, extractionComplete, chunksProcessed}`.
- Prompt template: `pictureBook.extract-chunk.json` (additions/revisions/removals per chunk).
- Frontend: "Chunked Extract" button in wizard Step 1. Step 2 enhanced with add/remove/reorder/edit (title, blurb, diffusion prompt).

### View Picture Book Button
Disabled until `targets.every(s => s.imageObjectId)` — all images generated.

### createCharPerson Narrative Fix
`narrative` is `olio.narrative` model, not a string. Was doing `charPerson.set("narrative", stringValue)` → ClassCastException. Fixed to set `narrative.sdPrompt` and `narrative.physicalDescription`. Also fixed `generateSceneImage` to read `narrative.sdPrompt` instead of casting narrative to string. **Needs rebuild verification.**

---

## Pending: Verify After Rebuild

### createCharPerson Narrative Fix Verification
The ClassCastException fix (narrative → narrative.sdPrompt) was implemented but not yet tested against the live backend. After rebuild:
1. Run full Playwright suite
2. Check server logs for "Failed to set portrait prompt" warnings — should be gone
3. Check B.4 image generation — portrait stage should now work (characters get SD prompts)

---

## New Feature: Chunked Extract → Full Book Creation (#17)

### Problem
The "Chunked Extract" path discovers scenes across the full document and lets the user edit them (Step 2), but then stops — it doesn't create charPerson records, scene notes, or metadata. The user has to manually proceed through Steps 3-4 without the backend character pipeline.

Meanwhile, "Extract Everything" does all the backend work (characters + scenes + meta) but uses single-shot extraction (8000-char truncation, no user review).

### Goal
Combine the best of both: chunked extraction for scene discovery → user review/edit → then the same backend character creation + scene note + meta pipeline that "Extract Everything" uses, but fed by the **user-curated scene list**.

### Proposed Flow
1. **Step 1:** User picks source doc, selects "Chunked Extract"
2. **Step 2:** Chunked extraction runs, returns scene list. User edits scenes (add/remove/reorder/edit titles/blurbs/diffusion prompts). User confirms.
3. **Step 3 transition:** Frontend sends the confirmed scene list to a new backend endpoint that:
   - Creates `~/PictureBooks/{bookName}/` group (if not already created)
   - Creates scene `data.note` records from the confirmed scene list
   - Extracts unique characters from the confirmed scenes
   - Calls LLM to extract character details for each unique character
   - Creates `olio.charPerson` records with portrait prompts
   - Builds and saves `.pictureBookMeta`
   - Returns the meta with `bookObjectId`, scene objectIds, character objectIds
4. **Step 4:** Image generation using user-approved diffusion prompts and backend-created characters

### Backend Changes

**New endpoint:** `POST /{workObjectId}/create-from-scenes`
- Request body: `{ bookName, chatConfig, genre, sceneList: [...] }`
- The `sceneList` is the user-confirmed array from Step 2 (same structure as chunked extract output)
- Process:
  1. Create book group under `~/PictureBooks/{bookName}/`
  2. Create `Scenes/` and `Characters/` sub-groups
  3. For each scene in sceneList → create `data.note` with scene metadata
  4. Collect unique character names across all scenes
  5. For each unique character → LLM extract details → create `olio.charPerson` → set `narrative.sdPrompt`
  6. Build `.pictureBookMeta` with scene objectIds + character objectIds
  7. Return meta JSON (same format as `extract` response)

### Frontend Changes

**Wizard Step 2 → Step 3 transition:**
- When user clicks "Continue" from Step 2 after chunked extraction:
  - Call `createFromScenes(workObjectId, bookName, chatConfigName, genre, extractedScenes)`
  - This replaces the current flow where Step 3 just lists characters without creating them
  - Capture `bookObjectId` from response
  - Skip to Step 4 with all backend data created

**New API function in sceneExtractor.js:**
```javascript
async function createFromScenes(workObjectId, chatConfigName, genre, bookName, sceneList) {
    let body = { schema: 'olio.pictureBookRequest', sceneList };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (genre) body.genre = genre;
    if (bookName) body.bookName = bookName;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/create-from-scenes', { ... });
    return resp.json();
}
```

### Tests Needed
- Chunked extract → edit scenes → create-from-scenes → verify book group exists
- Verify charPerson records created with narrative.sdPrompt populated
- Verify scene notes created with correct metadata
- Verify .pictureBookMeta has scene and character objectIds
- Image generation works using chunked-extract-created characters

---

## Prompt Config/Template Pickers for Every LLM Call (#18)

### Problem
The wizard has a single "Chat Config" picker but no way to select **prompt configs or prompt templates** for any of the LLM calls. The backend `callLlm()` resolves prompts via `ChatUtil.resolveConfig()` (user's ~/Chat group → system library → classpath), but the frontend never sends a prompt template/config override — it always uses the default.

### LLM Prompts Used by PictureBook
| # | Prompt Name | Used By | Purpose |
|---|-------------|---------|---------|
| 1 | `pictureBook.extract-scenes` | `extract`, `extract-scenes-only` | Scene extraction from text |
| 2 | `pictureBook.extract-chunk` | `extract-chunked` | Chunked scene extraction |
| 3 | `pictureBook.extract-character` | `extract` | Character detail extraction |
| 4 | `pictureBook.scene-blurb` | `blurb` | Scene blurb regeneration |
| 5 | `pictureBook.landscape-prompt` | `generateSceneImage` | Landscape SD prompt |
| 6 | `pictureBook.scene-image-prompt` | (available) | Scene image SD prompt |

### UX Design
Add a "Prompt Config" section to wizard Step 1 with two modes:

**Mode A — "Use one for all"** (default):
- Single prompt template picker (using `ObjectPicker.openLibrary({ libraryType: 'promptTemplate' })`)
- Selected template name sent as `promptTemplate` in all API calls
- Backend applies it to every `callLlm()` invocation

**Mode B — "Select per prompt"**:
- Toggle/accordion expands to show one picker per prompt
- Each picker labeled with the prompt's purpose (e.g., "Scene Extraction", "Character Details", "Landscape Prompt")
- Each sends its own `promptTemplate` override in the request body
- Unset pickers use the default (classpath resource)

### State Variables (wizard)
```javascript
let promptMode = 'single';  // 'single' | 'per-prompt'
let promptTemplate = null;   // single mode — applies to all
let promptTemplates = {      // per-prompt mode
    extractScenes: null,
    extractChunk: null,
    extractCharacter: null,
    sceneBlurb: null,
    landscapePrompt: null
};
```

### Backend Changes
- `extract`, `extract-scenes-only`, `extract-chunked`: Parse `promptTemplate` from request body. Pass to `callLlm()` as an override name (resolve that name instead of the default).
- `generateSceneImage`: Parse `promptTemplate` for landscape prompt override.
- `blurb`: Parse `promptTemplate` for blurb prompt override.
- OR: Accept a `promptTemplates` map in the request body: `{ "extractScenes": "myTemplate", "landscapePrompt": "myOtherTemplate" }` — each key overrides one prompt.

### Frontend Changes
- `workflows/pictureBook.js` — Step 1 UI: prompt mode toggle + picker(s)
- `workflows/sceneExtractor.js` — All API functions accept optional `promptTemplate` or `promptTemplates` param, include in request body
- `features/pictureBook.js` — Blurb regenerate passes prompt template if set

### Files Changed
- `PictureBookService.java` — `callLlm()` accepts override name, all endpoints parse it
- `workflows/pictureBook.js` — Step 1 UI, state, pass to API calls
- `workflows/sceneExtractor.js` — API functions accept prompt overrides
- `features/pictureBook.js` — blurb regenerate

---

## Image Generation Error Handling + Accept/Reject Per Image (#20)

### Bug
When image generation fails (SD server error, association failure, timeout), the wizard silently marks the scene as "error" in `genProgress` but still allows advancing to Step 5. The user has no way to retry failed images or reject/regenerate individual images that came out wrong.

### Current Behavior
- `doGenerateAll()` iterates scenes, sets `genProgress[oid] = 'generating' | 'done' | 'error'`
- On error, the loop continues to the next scene
- Step 4 "View Picture Book" button checks `targets.every(s => s.imageObjectId)` — failed scenes block advancement (good), but there's no per-scene retry or feedback

### Required Behavior

**Error detection & retry:**
- When a scene fails, show a clear error state on that scene's card (red border, error message)
- Add a "Retry" button per failed scene — calls `doGenerateOne(scene)` again
- Failed scenes should NOT count as "done" — user stays on Step 4 until all are resolved
- Allow user to skip a failed scene (mark as "skipped" — advance without that image)

**Accept/reject per image:**
- After an image is generated successfully, show a thumbnail preview on the scene card
- Add "Accept" (check) and "Reject" (refresh) buttons per generated image
- "Reject" clears `imageObjectId`, resets `genProgress` to allow re-generation
- Before re-generating, user can edit the scene's diffusion prompt or tweak SD settings (steps, cfg, seed) for that specific scene
- "Accept" locks the image — scene card shows a green check, no more regeneration

**Per-scene prompt/settings override:**
- Each scene card in Step 4 should have an expandable section (like Step 2's diffusion prompt `<details>`)
- Shows the diffusion prompt (editable) and key SD settings (steps, cfg, seed) that override the global config for this scene only
- "Regenerate" uses the per-scene overrides if set, otherwise falls back to global SD config

### State Changes
```javascript
// Per-scene state
let sceneStatus = {};  // oid → 'pending' | 'generating' | 'done' | 'error' | 'accepted' | 'skipped'
let sceneErrors = {};  // oid → error message string
let sceneOverrides = {}; // oid → { promptOverride, steps, cfg, seed } or null
```

### UI Changes (Step 4 scene cards)
Each scene card shows:
1. Title + status badge (pending/generating spinner/done thumbnail/error red/accepted green)
2. If done: thumbnail preview + Accept/Reject buttons
3. If error: error message + Retry button
4. Expandable: diffusion prompt textarea + per-scene SD overrides (steps, cfg, seed)
5. Individual "Generate" button (calls `doGenerateOne` with overrides)

### "View Picture Book" button logic
- Enabled when: every scene is either `accepted` or `skipped`
- NOT enabled when any scene is `pending`, `generating`, `done` (unreviewed), or `error`

### Files Changed
- `workflows/pictureBook.js` — Step 4 rendering, state management, per-scene controls
- `workflows/sceneExtractor.js` — `generateSceneImage` already accepts `promptOverride` and `sdConfig` per call (no change needed)

---

## SD Config Panel — Missing Fields (#19)

### Problem
The wizard Step 4 SD config panel has: Steps, CFG, Seed, Style, Refiner Steps, HiRes. The state variables `sdModel` and `sdRefinerModel` exist in `pictureBook.js` and are passed in `buildSdConfig()`, but there is **no UI** for them. Missing fields:

- **Model selection** — dropdown of available SD models (use `am7model._sd.fetchModels()` like `reimageApparel.js` does)
- **Refiner model selection** — separate dropdown for the refiner model
- **Denoising strength** — slider/input for img2img denoising

### Reference Implementation
`reimageApparel.js` has the full SD config panel with all fields. Check how it:
1. Calls `am7model._sd.fetchModels()` to populate model dropdowns
2. Renders model/refinerModel as `<select>` with the fetched list
3. Includes denoising strength slider

### Frontend Changes
In `workflows/pictureBook.js` `renderSdConfig()`:
1. On Step 4 init (or lazy on first render), call `am7model._sd.fetchModels()` → populate `sdModelList`
2. Add `<select>` for Model (from `sdModelList`)
3. Add `<select>` for Refiner Model (from `sdModelList`)
4. Add `<input type="number">` for Denoising Strength (0.0–1.0, step 0.05, default 0.65)
5. Add `sdDenoisingStrength` state variable + include in `buildSdConfig()`

### Backend Changes
`generateSceneImage` in `PictureBookService.java` reads `steps`, `refinerSteps`, `cfg`, `hires`, `seed` from sdConfig body. It also needs to read and set on `sdConfigRec`:
- `model` — base model name
- `refinerModel` — refiner model name
- `denoisingStrength` — img2img denoising

Check the `olio.sd.config` model for exact field names.

### Files Changed
- `workflows/pictureBook.js` — `renderSdConfig()`, `buildSdConfig()`, new state vars
- `PictureBookService.java` — `generateSceneImage()` sdConfig parsing

---

## Character Details View/Edit in Wizard Step 3 (#22)

### Problem
The wizard Step 3 (Character Review) shows a flat list of character names with status badges (pending/creating/done/error) but NO way to see or edit character details. The LLM extracts appearance, gender, role, outfit, etc. — the user should be able to review and correct these before charPerson records are created.

### Required
Each character card in Step 3 should show:
- **Name** (editable — first/last)
- **Gender** (editable dropdown)
- **Appearance/physical description** (editable textarea)
- **Outfit/clothing** (editable textarea)
- **Role in story** (editable)
- **Portrait prompt** (editable — this becomes `narrative.sdPrompt`)
- **Remove** button (exclude character from creation)
- **Add** button (manually add a character)

### Flow
1. After Step 2 (scene editor), unique characters are collected from scenes
2. If auto-extract: LLM character detail extraction runs per character, results populate the edit fields
3. User reviews/edits each character's details
4. On "Continue to Images" → charPerson records are created with the user-approved data
5. Portrait prompts use the user-edited text, not raw LLM output

### Data Structure
The `characters` array should hold full detail objects, not just `{name, role}`:
```javascript
characters = [
    {
        name: "Lonely Introvert",
        firstName: "Lonely",
        lastName: "Introvert",
        gender: "FEMALE",
        appearance: "pale skin, dark circles under eyes, shoulder-length brown hair",
        outfit: "oversized sweater, rumpled jeans",
        role: "protagonist",
        portraitPrompt: "portrait of lonely introvert, female, pale skin...",
        status: 'pending'  // pending | extracting | done | error
    }
]
```

### Files Changed
- `workflows/pictureBook.js` — `renderStep3()` expanded with edit fields, `collectCharacters()` builds full detail objects, character extraction populates fields
- `PictureBookService.java` — `createCharPerson()` should accept user-provided portrait prompt instead of always generating one

---

## Bug: Scene Count Still Hardcoded to 3 in Frontend (#21a)

### Problem
Even though the scene count input was removed from the wizard UI and the backend cap was raised to 10, the frontend still sends `count: 3`:
- `sceneExtractor.js` line 17: `MAX_SCENES_DEFAULT = 3`
- `extractScenes()` and `fullExtract()` both send `count: count || MAX_SCENES_DEFAULT`
- `pictureBook.js` still has `sceneCount = MAX_SCENES_DEFAULT` and passes it to API calls

### Fix
- Change `MAX_SCENES_DEFAULT` in `sceneExtractor.js` to 10 (match backend)
- OR: stop sending `count` entirely — let the backend use its default
- Remove `sceneCount` state variable from `pictureBook.js` since there's no UI for it

---

## Unify Extract Buttons — Auto-Chunk on Long Text (#21)

### Problem
Step 1 has three extraction buttons: "Extract Scenes", "Chunked Extract" (primary/blue), and "Extract Everything". This is confusing — the user shouldn't have to decide the extraction strategy.

### Fix
Replace all three with a single **"Extract"** button. The backend (or frontend before calling) checks the text length:
- If text <= 8000 chars → single-shot extraction (`extract-scenes-only` or `extract`)
- If text > 8000 chars → automatic chunked extraction (`extract-chunked`)

The user never sees the difference. Both paths land on Step 2 (scene editor) for review.

### Implementation
**Option A — Frontend decides:**
- Frontend calls a lightweight endpoint (or checks the source document size) to determine text length
- Calls `extractScenes` or `extractChunked` accordingly
- Single `doExtract()` function replaces `doExtractScenesOnly`, `doExtractChunked`, `doFullExtract`

**Option B — Backend decides:**
- Single endpoint (e.g., modify `extract-scenes-only` or new `extract-smart`) that checks text length internally
- If text > threshold → chunks automatically
- Returns the same scene list format either way

Option B is cleaner — one API call, backend handles the strategy.

### Files Changed
- `PictureBookService.java` — merge `extract-scenes-only` and `extract-chunked` logic into one endpoint
- `workflows/pictureBook.js` — replace three buttons with one "Extract" button
- `workflows/sceneExtractor.js` — simplify to one extract function

---

## Viewer: Remove Blurb Edit, Add "Edit Book" Action (#23)

### Problem
The finished book viewer (features/pictureBook.js) has inline blurb edit buttons on each scene page. This is inconsistent — the viewer should be a read-only presentation. Editing belongs in the wizard.

### Fix
1. **Remove** the edit/regenerate blurb UI from the viewer scene pages (`features/pictureBook.js`)
2. **Add** an "Edit Book" button to the viewer header that reopens the wizard dialog for this book, pre-populated with existing scenes/characters — letting the user re-enter the full edit flow
3. The viewer stays clean: title, image, blurb text (read-only), character badges, navigation

### Files Changed
- `features/pictureBook.js` — remove `editingBlurb`, `blurbEditText`, `savingBlurb` state and all edit/regenerate UI from `renderScenePage()`. Add "Edit Book" button to `renderHeader()` that calls `pictureBookFromId(viewerBookId, viewerWorkName)`.

---

## Verify Seed Reuse for Character Consistency (#24)

### Problem
The wizard captures `seed` from the first generated image (`lastUsedSeed`) and passes it to subsequent `generateSceneImage` calls via `buildSdConfig()`. Need to verify:
1. The seed IS actually captured from the first image response (`result.seed`)
2. The seed IS passed in subsequent calls
3. The backend applies it to the `sdConfigRec`
4. The SD server uses it — characters should look consistent across scenes

### Current Code Path
- `doGenerateAll()` line ~189: `if (result.seed && lastUsedSeed < 0) lastUsedSeed = result.seed;`
- `buildSdConfig()`: `if (lastUsedSeed > 0) cfg.seed = lastUsedSeed;`
- Backend: `sdConfigRec.set("seed", seed);`

### Potential Issues
- The backend returns `"seed": -1` in test runs (see B.4 output) — if the SD server doesn't return the actual seed used, `lastUsedSeed` stays -1 and no seed reuse happens
- The SD server may need to be configured to return the seed in the response
- If seed = -1 means "random", the backend should capture the actual seed from the SD response and return it

### Investigation
1. Check what `sdu.createImage()` / `sdu.createSceneImage()` returns for the seed field
2. Check if `sdConfigRec.get("seed")` returns the actual seed after generation or still -1
3. If seed isn't being captured, fix the backend to extract it from the SD response
4. Verify in Playwright: after generating scene 1, check that scene 2+ requests include the same seed

### Files to Check
- `PictureBookService.java` — `generateSceneImage()` return value for seed
- `SDUtil.java` / `SWUtil.java` — how seed is returned from SD server
- `workflows/pictureBook.js` — `doGenerateAll()` seed capture logic

---

## Other Open Items (Lower Priority)

### List View Missing Icons (#12 from Revisions)
File extension on object names incorrectly treated as contentType. Affects chatConfig picker display. Check `objectViewDef.js` or `decorator.js` for name-based contentType inference — only infer for `data.data` objects.

### Work Selector Scoping (#6 from Revisions)
The `.pictureBookMeta` search has no group filter — returns all users' books. Should scope to user's home directory. Lower priority since permission filtering prevents cross-user access.

---

## Test Execution
```bash
# Run all picture book tests
npx playwright test e2e/pictureBookLive.spec.js --project=chromium --workers=1 --retries=0 --timeout=600000

# Run specific phase
npx playwright test e2e/pictureBookLive.spec.js --grep "B\. Backend" --project=chromium --workers=1
```

### Test Infrastructure Notes
- Use `setupWorkflowTestData` for fresh test user (NEVER admin)
- Extraction runs in `beforeAll` (takes 2-3 min with LLM)
- Use `--workers=1` — module-level state must persist across describes
- Backend: localhost:8443, Ollama: 192.168.1.42:11434 (model qwen3:8b), SD server up
- Use `steps:20, hires:false` for fast image generation
- Server logs at `C:\Users\swcot\Desktop\WEB-INF\logs\accountManagerService.log`
