# Picture Book — Post-Implementation Issue List

**Date:** 2026-03-31
**Context:** After implementing #17, #18, #19, #20, #21, #21a, #22, #23, #24 — awaiting backend rebuild + live testing.

---

## Open Issues

### Issue #25 — `extractChunked` export unused but still exported
`sceneExtractor.js` still exports `extractChunked` (and the backend endpoint still exists), but the frontend no longer calls it — `doExtract()` uses `extractScenes()` which auto-chunks server-side. The export and endpoint are dead code.
**Files:** `sceneExtractor.js`, `PictureBookService.java`

### Issue #26 — `doFullExtract()` still exists, unreachable
`pictureBook.js` still has `doFullExtract()` but nothing calls it — the three-button UI was replaced with `doExtract()`. Dead code.
**Files:** `pictureBook.js`

### Issue #27 — `extractChunked` import in pictureBook.js removed but function in sceneExtractor.js remains
The wizard no longer imports `extractChunked`, but the function is still exported from `sceneExtractor.js`. Consistent with #25.
**Files:** `sceneExtractor.js`

### Issue #28 — Viewer blurb save was sending empty body
The removed `saveBlurb()` in `features/pictureBook.js` was sending `{ schema: 'olio.pictureBookRequest' }` (no blurb text) to the blurb endpoint — it was never actually saving the user's edit. The blurb endpoint regenerates via LLM, it doesn't accept user text. This was a pre-existing bug, now moot since blurb edit was removed.
**Status:** Moot (edit UI removed in #23)

### Issue #29 — `createFromScenes` character data passthrough untested
The `create-from-scenes` endpoint accepts a `characters` array with user-edited details (appearance, outfit, portraitPrompt). The LLM merge logic (don't overwrite user edits) needs live verification. If the user fills in `portraitPrompt` in Step 3, that should flow to `narrative.sdPrompt` on the charPerson — this path hasn't been exercised.
**Files:** `PictureBookService.java` (createFromScenes), `pictureBook.js` (Step 3 → createFromScenes)

### Issue #30 — `ObjectPicker.openLibrary({ libraryType: 'promptTemplate' })` untested
The prompt template pickers in Step 1 call `ObjectPicker.openLibrary` with `libraryType: 'promptTemplate'`. Need to verify this library type is supported by the ObjectPicker component and that prompt template records exist in the system library.
**Files:** `pictureBook.js` (renderStep1)

### Issue #31 — SD model list fetch may fail silently
`loadSdModels()` calls `am7model._sd.fetchModels()` which may not exist or may fail if the SD server is down. Currently fails silently with empty list. Should show a warning if models can't be loaded.
**Files:** `pictureBook.js` (loadSdModels)

### Issue #32 — Backend `denoisingStrength` field name on `olio.sd.config`
The code does `sdConfigRec.set("denoisingStrength", denoisingStrength)` — need to verify the exact field name on the `olio.sd.config` model. If the field is named differently (e.g., `denoising`), the set will fail silently.
**Files:** `PictureBookService.java` (generateSceneImage)

### Issue #33 — Backend `model`/`refinerModel` field names on `olio.sd.config`
Same concern as #32 — `sdConfigRec.set("model", sdModelName)` and `sdConfigRec.set("refinerModel", sdRefinerModelName)` assume these field names exist on the model. Verify against schema.
**Files:** `PictureBookService.java` (generateSceneImage)

### Issue #34 — `AttributeUtil.getAttributeValue` signature verification
`extractSeedFromImage()` calls `AttributeUtil.getAttributeValue(image, "seed", -1)`. Need to verify this method signature exists and that the image record has attributes populated (attributes may require explicit request in query).
**Files:** `PictureBookService.java` (extractSeedFromImage)

### Issue #35 — Per-scene overrides prompt override textarea spans 3 columns
The "Prompt Override" textarea in Step 4 scene overrides is in a 3-column grid but should span all 3 columns for usability.
**Files:** `pictureBook.js` (renderStep4, scene overrides details)

### Issue #36 — Thumbnail resolution race on fast redraw
`doGenerateOne()` resolves thumbnails via `resolveImageUrl().then()` which triggers `m.redraw()`. If multiple scenes finish quickly, the async thumbnail resolution could cause excessive redraws. Not a bug, but worth watching under load.
**Files:** `pictureBook.js` (doGenerateOne)

### Issue #37 — `sceneExtractor.js` still exports `extractChunked` in export block
The `extractChunked` function is still in the export list. Should be kept for backward compat (tests reference it) or removed if dead.
**Files:** `sceneExtractor.js`

### Issue #39 — Prompt templates not loaded into system library ✅ FIXED
PictureBook prompt templates existed on classpath (`olio/llm/prompts/pictureBook.*.json`) but were not registered for library bootstrap. `ChatUtil.PROMPT_TEMPLATE_TEMPLATE_NAMES` didn't include them, and no `promptTemplate.pictureBook.*.json` files existed in the templates directory.
**Fix:** Created 6 library-format template files in `olio/llm/templates/` and added names to `PROMPT_TEMPLATE_TEMPLATE_NAMES`. After rebuild, call `POST /rest/chat/library/prompt/init` to bootstrap them into the system library.
**Files:** `ChatUtil.java`, `olio/llm/templates/promptTemplate.pictureBook.*.json` (6 new files)

### Issue #39b — Prompt template picker opens at user dir (empty), not library dir ✅ FIXED
`ObjectPicker.openLibrary()` was defaulting `startId` to `userContId || dir.objectId` — the user's `~/Chat` dir (which has 0 templates) instead of the shared library. Changed to always start at the library dir for `openLibrary()` calls.
**Fix:** `picker.js` line 215: `let startId = dir.objectId` (was `userContId || dir.objectId`)
**Files:** `picker.js`

### Issue #39c — List control missing column headers for prompt template/config types ✅ FIXED
`modelHeaderMap` in `decorator.js` had no entries for `olio.llm.promptTemplate` or `olio.llm.promptConfig`, causing fallback to `defaultHeaderMap` which showed groupPath as a column.
**Fix:** Added both types to `modelHeaderMap` with `[_rowNum, _icon, name, description, _favorite]`.
**Files:** `decorator.js`

### Issue #38 — Deleted picture books still appear in work selector list
After deleting a picture book via the viewer's delete button, the book still shows in the `/picture-book` work selector list. The `reset` endpoint deletes the book group + meta, but `loadExistingBooks()` searches for `.pictureBookMeta` notes — the search results may be cached, or the delete isn't fully cascading the meta note. Possible causes:
1. `loadExistingBooks()` doesn't re-query after navigating back from deleted book
2. The `.pictureBookMeta` note survives the delete (reset deletes group but meta query finds orphaned records)
3. Client-side `existingBooks` array isn't cleared on re-entry
**Files:** `features/pictureBook.js` (loadExistingBooks, workSelectorView), `PictureBookService.java` (reset)

### Issue #40 — Group navigation broken in list view
Cannot navigate up to parent group from the list view. Needs investigation — may be related to breadcrumb `objectId` resolution or `navigateByParent` logic.
**Files:** `list.js` (breadcrumb click handlers, `openPath`)

### Issue #41 — Prompt template sections appear empty in form view
When opening a prompt template in the object form, the `sections` list shows items (system, user) but expanding a section shows no prompt content. Error: `Invalid type model.js:62` when clicking expand — `tableListEditor.js:156 openEditor` fails because the section model type isn't recognized.
**Files:** `tableListEditor.js`, `model.js`, `object.js`

### Issue #42 — PictureBookService uses hand-rolled JSON maps instead of models
All internal data structures (`Map<String, Object>`, `LinkedHashMap`) should be replaced with proper AM7 model definitions. Current code hand-rolls meta, scene data, and generate results as raw JSON maps instead of using `RecordFactory.newInstance()`. Design doc specifies model-based approach.
**Files:** `PictureBookService.java` — needs models for: pictureBookMeta, sceneData, generateResult, createFromScenesResponse

### Issue #43 — `page.systemLibrary()` was missing from Ux752 ✅ FIXED
Ux7 had `systemLibrary()` on the page object; Ux752 did not. Caused `TypeError: systemLibrary is not a function` when clicking system library button.
**Fix:** Added `systemLibrary()` to `pageClient.js`, exported on `page` object.

### Issue #44 — ChatService `/library/init` used ObjectMapper instead of AM7 pattern ✅ FIXED
Used Jackson `ObjectMapper`/`JsonNode` for parsing instead of `JSONUtil.importObject` with `LooseRecord`.
**Fix:** Replaced with `JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule())`.

---

## Verification Checklist (after rebuild)

- [ ] Run full Playwright suite — all 46+ tests pass
- [ ] Check server logs for "Failed to set portrait prompt" — should be gone (narrative fix)
- [ ] Verify single "Extract" button works for short text (< 8000 chars)
- [ ] Verify single "Extract" button auto-chunks for long text (> 8000 chars)
- [ ] Step 3 character cards render with editable fields
- [ ] `create-from-scenes` endpoint creates book group, scenes, characters
- [ ] Step 4 accept/reject per image works
- [ ] Step 4 retry on error works
- [ ] Step 4 skip works, "View Picture Book" enabled after all accepted/skipped
- [ ] SD model dropdowns populate from server
- [ ] Denoising strength passes through to backend
- [ ] Viewer "Edit Book" button opens wizard
- [ ] Viewer has no blurb edit UI
- [ ] Prompt template picker opens (if library type is supported)
- [ ] Seed from first image is captured and reused in subsequent calls
