# Picture Book — Next Session Prompt

**Date:** 2026-03-30
**Predecessor:** This session completed the Picture Book test plan (46 Playwright tests all green), fixed ~15 bugs, and designed remaining features. This prompt picks up where it left off.

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

## Open Issues to Fix

### Issue #3: Frontend Fallback Cleanup (MEDIUM)
The viewer has a fallback that searches `Scenes/` subdirectory when GET /scenes returns empty. Now that the meta saves as `data.note` (text field, no 512 limit), verify the meta actually saves and GET /scenes returns scene data. If it works, remove the fallback code from `loadViewer()` in `pictureBook.js`. If not, debug why.

**Test:** After extraction, verify GET /scenes returns scenes with blurbs and imageObjectIds. The test `B.3 GET /scenes returns valid data` covers this.

### Issue #12: Decouple Picture Book Identity from Source Document (HIGH)
Currently, picture book data (Scenes/, Characters/, .pictureBookMeta) lives as subdirectories of the source document's group. This means:
- Can't have multiple picture books from the same source
- No independent naming
- Messy deletion

**Fix:** Create a `~/PictureBooks/` directory. Each book gets its own named group:
```
~/PictureBooks/
  AIME - Dark Noir/
    .pictureBookMeta
    Scenes/
    Characters/
```

This touches every endpoint in PictureBookService:
- `extract`: Create book group under `~/PictureBooks/{bookName}/`, store Scenes/Characters there
- `findWork`: Still resolves source document for text extraction
- `saveMeta`/`loadMeta`: Meta lives in the book group, not the source group
- `listScenes`: Reads meta from book group
- `generateSceneImage`: Scene notes are in book's Scenes/ group
- `reset`: Deletes the entire book group
- `reorderScenes`: Reads/writes meta in book group
- `regenerateBlurb`: Scene note lookup scoped to book group

Frontend changes:
- Work selector shows existing books from `~/PictureBooks/`
- Viewer route changes to use book group objectId instead of source document objectId
- Wizard creates the book group in Step 1

Add `sourceObjectId` to meta so the viewer knows which document the book came from.

### Issue #15: ChatConfig Options Not Saving (HIGH)
Changes to chatConfig sub-form (chatOptions) don't persist. Same pattern as charPerson voice field. The `chatOptions` is a foreign model reference — sub-form changes aren't tracked by `instance.patch()`.

**Investigation:**
- Check `views/object.js` save handler for how foreign model fields are saved
- Check if `chatOptions` needs the `background: true` async sub-object save pattern
- Look at how charPerson voice was fixed (memory file `project_picker_fix.md` may have context)

### Issue #13: List View Parent/Group Navigation (MEDIUM)
`data.note` has both `groupId` and `parentId`. The nav-up for group-based models was partially fixed (added else-if in `navigateUp`). Verify it works. Also check:
- Breadcrumb rendering shows clickable path segments for group navigation
- Parent-based up/down navigation works for models with `parentId`
- Toggling between group and parent navigation modes

---

## New Feature: Chunked Scene Extraction (#16)

### Overview
Replace the single-shot LLM extraction with a chunked approach that covers the full text and lets users edit scenes before generation.

### Phase 1 — Chunked Summarization (Backend)
1. Split source text into ~2000-char chunks with ~200-char overlap
2. For each chunk, call LLM with running scene list as context
3. LLM returns new scenes, revisions to existing scenes, or no changes
4. Accumulate into a `sceneList` array
5. Each scene has: `title, blurb, setting, action, mood, characters, diffusionPrompt`

**New endpoint:** `POST /{workObjectId}/extract-chunked`
- Request body: `{ chatConfig, count, genre, bookName }`
- Does all chunking server-side
- Returns the complete `sceneList` array

**New prompt template:** `pictureBook.extract-chunk`
- System: "You are extracting visual scenes. Here are scenes found so far: {previousScenes}. Continue from this text segment."
- User: "{chunk}"
- Response format: JSON with `additions`, `revisions`, `removals`

### Phase 2 — Scene List Editor (Frontend Wizard Step 2)
After extraction, show a card/table view of all scenes:
- Inline editing for title, blurb, diffusion prompt
- Remove button per scene
- Add manual scene button
- Reorder via up/down buttons
- "Regenerate prompt" button per scene (calls LLM to rebuild diffusion prompt)
- Preview of what will be generated

### Phase 3 — Modified Book Generation
1. User confirms scene list → becomes the authoritative data
2. Backend creates scene notes from confirmed list (no LLM needed)
3. Image generation uses user-approved diffusion prompts directly
4. Characters extracted from confirmed scene list
5. `sceneList` saved to meta for persistence

### Data Structure
```json
{
  "sceneList": [
    {
      "index": 0,
      "title": "The Wilted Bouquet",
      "blurb": "Introverts slouch in romantic glow...",
      "setting": "Dimly lit upscale bar, phone screens glowing",
      "action": "Singles sit alone with phones, tears through foundation",
      "mood": "Melancholic, somber, blue-tinted lighting",
      "characters": ["Introvert", "Service Staff"],
      "diffusionPrompt": "dimly lit upscale bar interior, single tables with phone screens glowing blue, lonely figure slouched, cinematic noir lighting, illustration",
      "userEdited": false
    }
  ]
}
```

---

## Playwright Testing Requirements

### Existing Tests (47)
The file `e2e/pictureBookLive.spec.js` has 47 tests covering phases A-L. All passed on the last run (46 pass + 1 skip). These tests must continue to pass.

### New Tests Needed

**For #12 (Decouple Identity):**
- Create a picture book with a custom name, verify it appears in `~/PictureBooks/`
- Create two books from the same source, verify they're independent
- Delete one book, verify the other is unaffected
- Verify source document is not modified by book operations

**For #15 (ChatConfig Save):**
- Open chatConfig, change an option (e.g., temperature), save, reload, verify value persisted

**For #16 (Chunked Extraction):**
- Upload a long document (>8000 chars), run chunked extraction
- Verify scene count covers content from beginning AND end of document
- Edit a scene title in the scene list editor, verify it persists
- Remove a scene, verify it's gone from the list
- Add a manual scene, verify it appears
- Reorder scenes, verify new order
- Generate images using the confirmed scene list
- Verify diffusion prompts are visible and editable

### Test Execution
```bash
# Run all picture book tests
npx playwright test e2e/pictureBookLive.spec.js --project=chromium --workers=1 --retries=0 --timeout=600000

# Run specific phase
npx playwright test e2e/pictureBookLive.spec.js --grep "D\. Viewer" --project=chromium --workers=1
```

### Test Infrastructure Notes
- Use `setupWorkflowTestData` for fresh test user (NEVER admin)
- Extraction runs in `beforeAll` (takes 2-3 min with LLM)
- Use `--workers=1` — module-level state (`extractedScenes`) must persist across describes
- Screenshots saved to `e2e/screenshots/pb-*.png`
- Server logs at `C:\Users\swcot\Desktop\WEB-INF\logs\accountManagerService.log`
- Backend: localhost:8443, Ollama: 192.168.1.42:11434 (model qwen3:8b), SD server up
- Use `steps:20, hires:false` for fast image generation

---

## Key Technical Notes

### Virtual Fields
`groupPath` on `data.note` and `data.data` is VIRTUAL (computed by PathProvider). It's NOT returned by `am7client.get()` or default queries. You MUST explicitly request it:
```javascript
let q = am7client.newQuery('data.note');
q.field('objectId', id);
if (q.entity.request.indexOf('groupPath') < 0) q.entity.request.push('groupPath');
```

### am7client.get() Returns Callbacks, Not Always Promises
`am7client.get(type, objectId)` uses `m.request` internally (returns Promise), but may return cached values synchronously. Use search queries for reliable async behavior when you need virtual fields.

### Meta Storage
`.pictureBookMeta` is stored as a `data.note` with the meta JSON in the `text` field. `loadMeta` uses `planMost(true)` to populate virtual fields. `saveMeta` creates/updates the note.

### Dialog API
Use `Dialog.open({ title, size, closable, content: {view: fn}, actions: [...] })` and `Dialog.close()` from `components/dialogCore.js`. The old `setDialog`/`endDialog` API does NOT exist in Ux752.

### Picker API
- `ObjectPicker.open({ type, title, onSelect })` — browse by model type
- `ObjectPicker.openLibrary({ libraryType: 'chatConfig', title, onSelect })` — browse chatConfig/promptTemplate/promptConfig

### currentOrganization
Fixed with getter/setter in am7client.js. `am7client.currentOrganization` now syncs with internal `sCurrentOrganization`. Both `mediaDataPath()` and `buildImageUrl()` work after UI login.

### SD Config — Missing Refiner Params and Model Selection
The wizard Step 4 SD config panel currently has: steps, CFG, seed, style, refiner steps, HiRes. It's MISSING:
- **Model selection** — dropdown of available SD models (use `am7model._sd.fetchModels()` like `reimageApparel.js` does)
- **Refiner model selection** — separate dropdown for the refiner model
- **Denoising strength** — slider/input for img2img denoising
- **CFG scale for refiner** — may differ from base CFG

The backend `generateSceneImage` endpoint reads `steps`, `refinerSteps`, `cfg`, `hires`, `seed` from the `sdConfig` body. It needs to also read and pass: `model`, `refinerModel`, `denoisingStrength`. Check `reimageApparel.js` for the full field list — it has all of them.

The `olio.sd.config` model has all these fields. The frontend `buildSdConfig()` in `workflows/pictureBook.js` needs to include them. The backend needs to set them on the `sdConfigRec` before passing to `SDUtil`.

### Image URL Resolution
`resolveImageUrl` in `sceneExtractor.js` uses a search query (not `am7client.get`) to ensure `groupPath` is populated. The URL format: `/AccountManagerService7/media/{org}/data.data{groupPath}/{name}`

---

### Wizard Step 4 — "View Picture Book" Button Premature
The "View Picture Book" action in Step 4 is enabled/primary (blue) even before all images are generated. It should only become active (blue/primary) once ALL scenes have `imageObjectId` set (i.e., all image generation is complete). Until then, it should be disabled or secondary styling.

Check `buildActions()` in `workflows/pictureBook.js` — the Step 4 action for "View Picture Book" needs a condition:
```javascript
let allGenerated = targets.every(s => s.imageObjectId);
// Only make primary if all images are done
{ label: 'View Picture Book', icon: 'auto_stories', primary: allGenerated, disabled: !allGenerated, ... }
```

---

## Execution Order

1. **#15 ChatConfig options save** — unblocks testing chatConfig changes
2. **#3 Verify meta saves, clean up fallback** — unblocks reliable GET /scenes
3. **#12 Decouple identity** — major refactor, do this before #16
4. **#16 Chunked extraction + scene editor** — the big feature
5. **#13 List nav verification** — polish
6. **Run full Playwright suite** — all existing + new tests green
