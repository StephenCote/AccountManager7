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

## 11. List View Missing Icons — File Extension Treated as ContentType (Priority: MEDIUM)

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

## Execution Order

1. **Prompt template pattern** (#1) — standalone, no dependencies
2. **Meta save bug** (#2) — investigation, then fix
3. **Blurb persistence** (#4) — depends on meta working
4. **Frontend cleanup** (#3) — after meta fix
5. **currentOrganization** (#5) — standalone
6. **Work selector scoping** (#6) — standalone
