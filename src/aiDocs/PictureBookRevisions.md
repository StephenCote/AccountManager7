# Picture Book — Revision Plan

**Date:** 2026-03-29
**Status:** Design
**Scope:** Backend PictureBookService + frontend pictureBook.js/sceneExtractor.js

---

## 1. Prompt Template Pattern (Priority: HIGH)

### Problem
`PictureBookService.callLlm()` uses `PromptResourceUtil.getString()` which only loads from classpath resources (`olio/llm/prompts/{name}.json`). This bypasses the standard prompt template pattern used by ChatService, which allows user customization.

### Required Pattern (per ChatService)
1. **Resolve prompt template:** `ChatUtil.resolveConfig(user, MODEL_PROMPT_TEMPLATE, name, null)` — checks user's `~/Chat` group first, then system library (`Library/PromptTemplates`)
2. **System library bootstrap:** On first use, dynamically create the prompt template in the system library from the classpath resource if it doesn't exist
3. **Fallback:** Only use raw classpath resource if both user group and system library are empty

### Prompts Affected
| Prompt Name | Purpose |
|-------------|---------|
| `pictureBook.extract-scenes` | Scene extraction from story text |
| `pictureBook.extract-character` | Character detail extraction |
| `pictureBook.scene-blurb` | Scene blurb regeneration |
| `pictureBook.scene-image-prompt` | SD image prompt from scene data |
| `pictureBook.portrait-prompt` | Character portrait SD prompt |
| `pictureBook.landscape-prompt` | Scene landscape SD prompt |

### Implementation

**Step 1:** Create `olio/llm/templates/promptTemplate.pictureBook.{name}.json` files for each prompt (resource templates for library bootstrap).

**Step 2:** Add bootstrap method to `PictureBookService`:
```java
private void ensurePromptTemplates(BaseRecord user) {
    BaseRecord libDir = ChatLibraryUtil.getCreatePromptTemplateLibrary(user);
    for (String name : PROMPT_NAMES) {
        BaseRecord existing = ChatUtil.resolveConfig(user,
            OlioModelNames.MODEL_PROMPT_TEMPLATE, name, null);
        if (existing == null) {
            BaseRecord template = ChatUtil.loadPromptTemplateTemplate(name);
            if (template != null) {
                ChatLibraryUtil.createLibraryPromptTemplate(user, libDir, name, template);
            }
        }
    }
}
```

**Step 3:** Modify `callLlm()` to resolve templates via `ChatUtil.resolveConfig()`:
```java
private String callLlm(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
    // Try user-customizable template first
    BaseRecord promptTemplate = ChatUtil.resolveConfig(user,
        OlioModelNames.MODEL_PROMPT_TEMPLATE, promptName, null);

    String system, userTpl;
    if (promptTemplate != null) {
        system = promptTemplate.get("system");
        userTpl = promptTemplate.get("user");
    } else {
        // Fallback to classpath resource
        system = PromptResourceUtil.getString(promptName, "system");
        userTpl = PromptResourceUtil.getString(promptName, "user");
    }
    // ... rest of LLM call
}
```

**Step 4:** Call `ensurePromptTemplates()` once during the first `extract` call (lazy init with a static flag).

### Files Changed
- `PictureBookService.java` — `callLlm()`, new `ensurePromptTemplates()`
- New resource files: `olio/llm/templates/promptTemplate.pictureBook.*.json` (6 files)

---

## 2. `.pictureBookMeta` Save Bug (Priority: HIGH) — ROOT CAUSE FOUND

### Problem
`saveMeta()` stores the entire meta JSON in `data.data.description`, but `description` has `maxLength: 512`. The meta JSON with 3 scenes is ~1200 characters → **RecordValidator rejects it**.

### Server Log Evidence
```
ERROR RecordValidator - description value '{"workObjectId":"...","sceneCount":3,"scenes":[...]}' exceeds maximum length 512
ERROR RecordUtil - WriterException: Record failed validation in IO DATABASE
```

### Fix
Store the meta JSON in a field without a 512 char limit. Options:
1. **Use `dataBytesStore`** — store as byte blob (unlimited size)
2. **Use a `data.note` instead of `data.data`** — `data.note.text` has no maxLength
3. **Increase `description` maxLength** on `data.data` model (affects all data.data records)

**Recommended: Option 2** — change `saveMeta`/`loadMeta` to use a `data.note` named `.pictureBookMeta` instead of `data.data`. The `text` field on `data.note` is unlimited. This also eliminates the "Failed to add record" issue seen with test users (which was likely a secondary symptom of the validation failure).

### Files Changed
- `PictureBookService.java` — `saveMeta()` and `loadMeta()`: change `MODEL_DATA` to `MODEL_NOTE`, use `text` field instead of `description`

### Workaround (Currently Active)
Frontend `loadViewer()` has a fallback: when GET /scenes returns empty, it searches the `Scenes/` subdirectory for `data.note` records directly and parses their `text` JSON for scene data.

---

## 3. Frontend Scene Fallback Cleanup (Priority: MEDIUM)

### Current State
`loadViewer()` in `pictureBook.js` has a fallback that searches `Scenes/` subdirectory when GET /scenes returns empty. This works but:
- Doesn't read the scene order (meta has `index` field)
- Doesn't populate `workName` from the meta
- The `groupPath` field is virtual and may not be populated by `am7client.get()`

### Fix After Meta Bug Is Resolved
Once `.pictureBookMeta` saves correctly:
1. Remove the `Scenes/` directory fallback from `loadViewer()`
2. GET /scenes will return ordered scene data with descriptions
3. `loadPictureBook()` returns `[]` on non-200 (already fixed)

---

## 4. Blurb Persistence (Priority: MEDIUM)

### Problem
Blurb editing saves to the scene note's `text` JSON blob (under `blurb` key), but GET /scenes returns the `description` field from `.pictureBookMeta`, which is the original LLM `summary`. These don't stay in sync — blurb edits are lost on page reload.

### Fix
In `PictureBookService.listScenes()`, after reading meta scenes, merge the current `blurb` from each scene note's `text` JSON into the scene's `description` field before returning.

### Implementation
```java
// In listScenes(), after reading meta scenes:
for (Map<String, Object> scene : scenes) {
    String sceneOid = (String) scene.get("objectId");
    if (sceneOid != null) {
        BaseRecord sceneNote = findSceneNote(user, sceneOid);
        if (sceneNote != null) {
            String text = sceneNote.get("text");
            Map<String, Object> textData = parseJsonMap(text);
            String blurb = (String) textData.get("blurb");
            if (blurb != null && !blurb.isEmpty()) {
                scene.put("description", blurb);
            }
        }
    }
}
```

---

## 5. `am7client.currentOrganization` / `sCurrentOrganization` Mismatch (Priority: LOW)

### Problem
The router sets `am7client.currentOrganization = usr.organizationPath` after UI login, but `am7client.mediaDataPath()` reads the internal `sCurrentOrganization` variable. These are different — `sCurrentOrganization` is only set by `am7client.login()` (API login), not the UI form login.

### Impact
`mediaDataPath()` returns URLs with `undefined` for the org segment when records lack `organizationPath`. The `buildImageUrl()` in `sceneExtractor.js` uses `am7client.currentOrganization` directly (works), but `mediaDataPath` doesn't (broken for records without org path).

### Fix
In `am7client.js`, make `currentOrganization` a setter that also updates `sCurrentOrganization`:
```javascript
Object.defineProperty(am7client, 'currentOrganization', {
    get: function() { return sCurrentOrganization; },
    set: function(v) { sCurrentOrganization = v; }
});
```

---

## 6. Work Selector Scoping (Priority: LOW)

### Problem
The work selector in `pictureBook.js` searches `data.data` and `data.note` with no group filter — returns ALL records across ALL users. This works functionally (user only sees records they have permission to read) but is inefficient and shows test artifacts.

### Fix
Scope the search to the user's home directory:
```javascript
let homeDir = await am7client.find('auth.group', 'data', '~/');
if (homeDir && homeDir.id) {
    q.field('groupId', homeDir.id);
}
```

---

## 7. `olio.pictureBookRequest` Model Missing Fields (Priority: HIGH — FIXED)

### Problem
The `sdConfig` field was missing from `pictureBookRequestModel.json`. The `generateSceneImage` endpoint sends `sdConfig` as a nested object, but the deserializer fails with:
```
FieldException: newFieldInstance: Field sdConfig was not found on model olio.pictureBookRequest
```

### Fix (Applied)
Added `sdConfig` field to the model as an ephemeral `olio.sdConfig` model reference. **Requires backend rebuild.**

### LLM Timeout Note
The chatConfig used for testing has low timeouts. Complex LLM tasks (character extraction, blurb regeneration) may need more time. Consider adding a `timeout` field to `pictureBookRequest` or using higher defaults in the service.

---

## 8. Work Selector: Use Picker Control (Priority: HIGH)

### Problem
The work selector at `#!/picture-book` uses an unbound list (`am7client.search` with no group filter) to show documents. This returns ALL `data.data` and `data.note` records across ALL groups — inefficient, shows test artifacts, and doesn't follow the established UX pattern.

### Fix
Replace the custom list with the existing ObjectPicker component. The picker provides:
- Scoped navigation (user's home directory)
- Search/filter built in
- Consistent UX with the rest of the app
- Proper pagination

### Files Changed
- `features/pictureBook.js` — replace `loadWorks()` + `workSelectorView` with picker-based selection

---

## 9. Viewer Empty State: Add "Extract" Action (Priority: HIGH)

### Problem
When a document is selected that has no scenes extracted yet, the viewer shows "No picture book found for this work. Go back to select a document." — a dead end. There's no way to trigger extraction from the viewer.

### Fix
Replace the empty state message with an action panel that lets the user:
1. **Extract scenes** — call the Picture Book wizard dialog (already exists in `workflows/pictureBook.js`) or call the extract endpoint directly
2. Show chatConfig selector + scene count + genre hint (same as wizard Step 1)
3. After extraction completes, reload the viewer with the new scenes

The wizard dialog (`pictureBook` workflow) already has all the extraction UI. The simplest approach: add a "Generate Picture Book" button to the empty state that opens the wizard dialog for this work.

### Files Changed
- `features/pictureBook.js` — empty state in viewer, import and call `pictureBook` workflow

---

## 10. Character Portrait Prompt/Description Fails After Creation (Priority: HIGH)

### Problem
During full extraction, `createCharPerson` successfully creates the `olio.charPerson` record but then fails to set the portrait prompt and description:
```
AUDIT PERMIT system.user steve to ADD olio.charPerson Various Singles
WARN PictureBookService - Failed to set portrait prompt/description for Various Singles: null
```

### Root Cause
After `AccessPoint.create(user, charPerson)`, the code tries to set `narrative` and `description` fields on the returned record. The `null` in the error suggests `NarrativeUtil.buildPortraitPromptFromExtractedData()` is returning null, OR the `set()` call on the created record fails because the record returned by `create` is a partial record (identity fields only) and doesn't support setting fields.

### Investigation Needed
- Check if `AccessPoint.create` returns a full record or partial (identity-only)
- Check if `NarrativeUtil.buildPortraitPromptFromExtractedData` handles the LLM character data format correctly
- The character data comes from `parseLlmJsonObject(llmChar)` — the LLM response may not match the expected format
- After `create`, may need to re-fetch the full record before setting fields, then `update`

### Files
- `PictureBookService.java` — `createCharPerson()` lines 340-377

---

## 11. Image Generation Pipeline Failures (Priority: HIGH)

### Multiple issues in `generateSceneImage`:

#### 11a. CharPerson created with wrong groupPath
Characters are created in `~/Data/Characters/` but the charPerson record's `groupPath` resolves to the character NAME (e.g., `/Lonely Adult`) instead of the actual directory path. This causes `PolicyUtil.getResourcePolicy` to fail with "Group could not be found" when the image pipeline tries to read the charPerson.

**Root Cause:** `createCharPerson` uses `ParameterList(FIELD_PATH, charsGroup.get(FIELD_PATH))` and `ParameterList(FIELD_NAME, name)`. The factory resolves `groupPath` from the path parameter, but `groupPath` is virtual and might get set to the name portion. After `AccessPoint.create` returns a partial record, the re-fetch via `planMost(false)` might not populate `groupPath` correctly.

**Evidence:**
```
"groupPath" : "/Lonely Adult"   ← WRONG (should be /home/steve/Data/Characters or similar)
"groupId" : 15074               ← group ID doesn't match any existing group
```

#### 11b. No portrait prompt (narrative) for characters
After `createCharPerson`, the code tries to set `narrative` from `NarrativeUtil.buildPortraitPromptFromExtractedData`. This returns null because the LLM character extraction may not return fields in the expected format. Without a narrative, the portrait generation step has no prompt → empty portrait → broken composite.

**Evidence:**
```
WARN PictureBookService - No portrait prompt (narrative) for: Abandoned Single 1
```

#### 11c. Scene image pipeline crashes on null portrait
The 4-stage pipeline (portrait → landscape → stitch → kontext) doesn't gracefully handle missing portraits. If no charPerson portraits can be generated, the pipeline crashes instead of falling back to landscape-only generation.

### Fix Strategy
1. Fix `createCharPerson` groupPath by using the group's actual path, not relying on virtual field
2. If `NarrativeUtil.buildPortraitPromptFromExtractedData` returns null, generate a basic portrait prompt from the character name + scene description
3. If portrait generation fails, skip the portrait stages and go straight to landscape → scene image (skip the composite)
4. Add null checks throughout the 4-stage pipeline

### Files Changed
- `PictureBookService.java` — `createCharPerson()`, `generateSceneImage()` pipeline stages

---

## 12. List View Missing Icons — File Extension Treated as ContentType (Priority: MEDIUM)

### Problem
In the list view (e.g., when browsing chatConfig via picker), icons are missing. Objects with names ending in `.abc` (or any dot-separated suffix) have their name suffix incorrectly treated as a contentType, which causes the icon resolver to fail or show a wrong icon.

### Root Cause
The list view or icon resolver is parsing the object name's file extension as a MIME type hint, even for non-file objects like `olio.llm.chatConfig`. This is a recurring bug — it was fixed before but has regressed.

### Investigation Needed
- Check `objectViewDef.js` or `decorator.js` for name-based contentType inference
- Check the list view's icon/thumbnail resolver for `.` splitting on object names
- Fix: only infer contentType from name extension for `data.data` objects (actual files), not for other model types

### Files to Check
- `components/objectViewDef.js`
- `components/decorator.js` or `views/list.js`

---

## 12. Decouple Picture Book Identity from Source Document (Priority: HIGH)

### Problem
Picture book data (Scenes/, Characters/, .pictureBookMeta) is stored as subdirectories of the source document's group path. This means:
- Picture book name = source document name (no independent naming)
- Can't have multiple picture books from the same document
- All extractions overwrite each other
- No clean way to browse/manage/delete picture books independently

### Current Storage
```
~/Data/
  AIME.pdf                    ← source
  .pictureBookMeta            ← meta
  Scenes/                     ← shared across all extractions
  Characters/                 ← shared
```

### Proposed Storage
Each picture book gets its own named group under a `~/PictureBooks/` directory:
```
~/PictureBooks/
  AIME - Dark Noir/           ← user-named picture book
    .pictureBookMeta
    Scenes/
    Characters/
  AIME - Watercolor/           ← different version, same source
    .pictureBookMeta
    Scenes/
    Characters/
```

### Changes Needed
- **New field in wizard Step 1:** "Picture Book Name" (defaults to source doc name, editable)
- **Backend `extract`:** Create named group under `~/PictureBooks/`, store all data there
- **Backend `findWork`:** Still resolves the source document for text extraction
- **Meta:** Add `sourceObjectId` field pointing back to the source document
- **Work selector:** Browse `~/PictureBooks/` groups instead of source documents
- **Delete:** Delete the entire picture book group (clean, one operation)
- **Reset:** Only resets Scenes/Characters under the picture book group, not the source

### Files Changed
- `PictureBookService.java` — all endpoints: resolve paths relative to picture book group
- `features/pictureBook.js` — work selector browses ~/PictureBooks/
- `workflows/pictureBook.js` — wizard Step 1 adds name field

---

## 15. ChatConfig Options Not Saving (Priority: HIGH)

### Problem
Changes to chatConfig options (chatOptions sub-form) are not persisting on save. Same pattern as the charPerson voice field save issue — foreign model sub-form changes aren't being included in the patch/update.

### Root Cause (Likely)
The `chatOptions` field is a foreign model reference on `olio.llm.chatConfig`. When editing via the object page sub-form, the nested entity changes aren't tracked by `instance.changes` / `instance.patch()`. The save operation sends a patch without the `chatOptions` field, so the backend doesn't update it.

### Investigation Needed
- Check how `charPerson` voice save was fixed previously
- Check if `chatOptions` is in the patch when saving
- May need explicit `background: true` async sub-object save pattern (same as model ref forms from Phase 16)

### Files to Check
- `views/object.js` — save handler, sub-form save logic
- `core/formDef.js` — chatConfig / chatOptionsRef form definition

---

## 14. ChatConfig: Missing LLM Chat Option Presets (Priority: MEDIUM — FIXED)

### Problem
When viewing/editing a chatConfig object, the "Apply LLM Chat Option Presets" feature is missing. This was available in Ux7/Ux75 and allows quickly setting common configurations (temperature, top_p, etc.) from predefined presets.

### Investigation Needed
- Check how Ux7 implements chat option presets (`../AccountManagerUx7/client/`)
- Check if `chatOptions` field on `olio.llm.chatConfig` model has preset support
- May be a formDef command or a field renderer feature

### Files to Check
- `core/formDef.js` — chatConfig form definition
- Ux7 reference: `../AccountManagerUx7/client/chat/` or similar

---

## 13. List View: Missing Parent/Group Navigation for Dual-Hierarchy Objects (Priority: MEDIUM)

### Problem
`data.note` has both `groupId` (directory hierarchy) and `parentId` (parent-child hierarchy). The list view allows navigating DOWN into group children but:
- No way to navigate UP in the group hierarchy (no breadcrumb back to parent group)
- No parent-based navigation at all (can't navigate up/down via `parentId`)

### Expected Behavior
For objects with both `groupId` and `parentId`:
- Group breadcrumb trail with clickable segments to navigate up
- Parent navigation (click to view parent, list children by parentId)
- Toggle or indication of which hierarchy is being browsed

### Files to Check
- `views/list.js` — breadcrumb rendering, navigation handlers
- `components/pagination.js` — may need parent-aware pagination
- Check Ux7 reference for how dual-hierarchy navigation was handled

---

## 16. Chunked Scene Extraction with User Editing (Priority: HIGH — Design)

### Problem
Current extraction sends the entire story text (truncated at 8000 chars) to the LLM in one shot. This:
- Loses content beyond 8000 chars
- Gives the LLM no running context — scenes cluster at the start
- Produces generic blurbs and no usable diffusion prompts
- User has no control over scene selection before generation begins

### Proposed Flow

**Phase 1 — Chunked Summarization**
1. Split source text into chunks (e.g., 2000-char segments with overlap)
2. For each chunk, send to LLM with the **running scene list** as context:
   - System prompt: "You are extracting visual scenes from a narrative. Here are scenes found so far: {previousScenes}. Continue identifying new scenes or revise existing ones."
   - User prompt: "Extract scenes from this segment: {chunk}"
3. LLM returns: additions, revisions to existing scenes, or no changes
4. Accumulate into an in-memory scene list: `[{title, blurb, setting, action, mood, characters, diffusionPrompt}]`
5. After all chunks processed, the list covers the full narrative arc

**Phase 2 — User Editing**
1. Present the scene list in the wizard (new Step 2)
2. User can:
   - **Edit** title, blurb, diffusion prompt for any scene
   - **Remove** scenes they don't want
   - **Add** manual scenes
   - **Reorder** scenes via drag or up/down arrows
3. Each scene shows a preview of its diffusion prompt
4. "Regenerate prompt" button per scene — re-runs LLM to build a new diffusion prompt from the scene data

**Phase 3 — Book Generation**
1. User confirms the final scene list
2. Backend creates scene notes from the confirmed list (no LLM needed — data is ready)
3. Image generation uses the user-approved diffusion prompts (no separate prompt-building LLM step)
4. Characters are extracted from the confirmed scene list

### Data Structure (in-memory, saved to meta)
```json
{
  "sceneList": [
    {
      "index": 0,
      "title": "The Wilted Bouquet",
      "blurb": "Introverts slouch in romantic glow, clutching leathery petals...",
      "setting": "Dimly lit upscale bar, phone screens glowing on small tables",
      "action": "Singles sit alone with phones, tears melting through foundation",
      "mood": "Melancholic, somber, blue-tinted lighting",
      "characters": ["Introvert", "Service Staff"],
      "diffusionPrompt": "dimly lit upscale bar interior, single tables with phone screens glowing blue, lonely figure slouched over drink, tears on cheeks, heavy makeup, wilted flower bouquet on table, cinematic noir lighting, illustration style",
      "userEdited": false
    }
  ],
  "extractionComplete": true,
  "chunksProcessed": 4
}
```

### Changes Needed

**Backend:**
- New endpoint: `POST /{workObjectId}/extract-chunked` — accepts chunk index + previous scene list, returns updated scene list
- OR: do all chunking server-side in the existing `extract` endpoint (simpler)
- Store `sceneList` in the meta alongside the existing `scenes` array

**Frontend (wizard):**
- Step 1: Source + config (existing)
- **Step 2 (new): Scene list editor** — table/card view of extracted scenes with inline editing
  - Title, blurb, diffusion prompt all editable
  - Add/remove/reorder buttons
  - "Regenerate prompt" per scene
- Step 3: Characters (derived from confirmed scenes)
- Step 4: Image generation (uses confirmed diffusion prompts directly)
- Step 5: View/export

**Prompt templates needed:**
- `pictureBook.extract-chunk` — chunked extraction with running context
- `pictureBook.diffusion-prompt` — build SD prompt from scene data (already exists as `scene-image-prompt`)

### Benefits
- Full text coverage regardless of length
- Running context prevents scene clustering
- User controls exactly which scenes appear
- Diffusion prompts are visible and editable before generation
- No wasted LLM/SD calls on scenes the user doesn't want

---

## Execution Order

1. **Prompt template pattern** (#1) — standalone, no dependencies
2. **Meta save bug** (#2) — investigation, then fix
3. **Blurb persistence** (#4) — depends on meta working
4. **Frontend cleanup** (#3) — after meta fix
5. **currentOrganization** (#5) — standalone
6. **Work selector scoping** (#6) — standalone
