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

## 2. `.pictureBookMeta` Save Bug (Priority: HIGH)

### Problem
`AccessPoint.create()` denies creation of `data.data` records named `.pictureBookMeta` in the user's home group via the internal factory path, even though the same user can create `data.data` via the REST model endpoint.

### Root Cause
Unknown — possibly a factory/parameter-list authorization path difference vs the REST model endpoint path. The `findWork()` fix (`planMost(true)`) populates `groupPath` correctly, but the `create` still fails with "Failed to add record."

### Investigation Needed
- Compare the `AccessPoint.create()` code path from `saveMeta()` vs `ModelService.create()`
- Check if `ParameterList`-based factory sets different ownership/authorization context
- Check if `.` prefix in name triggers any special handling

### Workaround (Currently Active)
Frontend `loadViewer()` has a fallback: when GET /scenes returns empty, it searches the `Scenes/` subdirectory for `data.note` records directly and parses their `text` JSON for scene data.

### Proper Fix
Once root cause is identified, fix `saveMeta()` so GET /scenes works without the fallback.

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

## Execution Order

1. **Prompt template pattern** (#1) — standalone, no dependencies
2. **Meta save bug** (#2) — investigation, then fix
3. **Blurb persistence** (#4) — depends on meta working
4. **Frontend cleanup** (#3) — after meta fix
5. **currentOrganization** (#5) — standalone
6. **Work selector scoping** (#6) — standalone
