# Picture Book — Comprehensive Test Plan

**Date:** 2026-03-28
**Executor:** Claude Code (adjacent conversation)
**Test File:** `AccountManagerUx752/e2e/pictureBookLive.spec.js`
**Evidence Dir:** `AccountManagerUx752/e2e/screenshots/`

---

## *** READ THIS FIRST — NON-NEGOTIABLE ***

Every bug found during this testing was introduced by a previous Claude session that wrote the Picture Book feature, the sceneExtractor, image URL resolution, groupPath fix, mediaDataPath fix, work selector, wizard, export, and apparel reimage dialog. **Every bug is Claude's fault.**

**Rules:**
1. DO NOT make excuses about backend, permissions, or infrastructure
2. DO NOT skip, mock, stub, or bypass ANY test
3. DO NOT use admin or any existing user — create a fresh test user with `setupWorkflowTestData`
4. DO NOT claim a test passes without running it and saving evidence to disk
5. DO NOT ignore server logs (`C:\Users\swcot\Desktop\WEB-INF\logs\accountManagerService.log`) or browser console errors
6. Every server WARN/ERROR during the test window MUST be investigated
7. Every browser console error MUST be investigated
8. Every network 400/500 MUST be investigated
9. All services are live: backend (localhost:8443), Ollama (192.168.1.42:11434, model qwen3:8b), SD image server
10. Fix every bug you find. Do not move on until the fix is verified.
11. Save screenshots for EVERY UI test as proof: `e2e/screenshots/pb-{NN}-{name}.png`
12. After ALL tests, read and report server logs from the test window

---

## Test User

```javascript
// In beforeAll:
testInfo = await setupWorkflowTestData(request, { suffix: 'pb' + Date.now().toString(36) });
// Creates fresh user e2etest_pb{suffix} who owns their own data
// Login: { user: testInfo.testUserName, password: testInfo.testPassword }
```

## Test Data Setup (in beforeAll)

1. Login as test user via API (`apiLogin`)
2. Create `data.note` named `AIME-pb-{ts}` in `~/Data` with full AIME story text (read from `AccountManagerObjects7/media/AIME.pdf` or use the inline text)
3. Create `olio.llm.chatConfig` named `PB-cfg-{ts}` in `~/Chat` with: model=`qwen3:8b`, analyzeModel=`qwen3:8b`, serverUrl=`http://192.168.1.42:11434`, serviceType=`ollama` (lowercase), stream=false
4. Store `workObjectId` and `chatConfigName` for use by all tests
5. Logout via API

---

## A. Bug Fix Verification

These tests verify specific bugs that were introduced and supposedly fixed. If any fail, the fix is broken.

### A.1 groupPath default is absolute, not tilde
- Login as test user in browser
- `page.evaluate`: call `am7model.newPrimitive('data.data')` and read `.groupPath`
- **PASS:** groupPath starts with `/home/` (absolute), NOT `~/` or `/Data`
- **Screenshot:** `pb-A1-groupPath.png`

### A.2 mediaDataPath handles missing organizationPath
- `page.evaluate`: call `am7client.mediaDataPath({ name: 'test.png', groupPath: '/home/user/Data' }, false)`
- **PASS:** Returns a URL containing `/media/` and NOT containing `undefined`
- **Screenshot:** `pb-A2-mediaDataPath.png`

### A.3 Work selector uses search, not list
- Navigate to `#!/picture-book`
- Monitor network requests
- **PASS:** No request to `/rest/list/[object Object]/...`, no 400 errors
- **Screenshot:** `pb-A3-workSelector.png`

### A.4 No reimage button on data.data objects
- Navigate to view a `data.data` object (the AIME note)
- **PASS:** No "Reimage" button in the command bar
- **Screenshot:** `pb-A4-noReimage.png`

### A.5 Large file display (stream-stored data)
- Upload `AccountManagerObjects7/media/Anaconda.jpg` (5.6MB, stored as stream) via the UX or API
- Navigate to view the uploaded image object
- **PASS:** Thumbnail/preview renders without `undefined` in URL, no 404 on image request
- **Screenshot:** `pb-A5-largeFile.png`

---

## B. Backend Pipeline

### B.1 Extract Scenes Only
- POST to `/rest/olio/picture-book/{workObjectId}/extract-scenes-only`
- Body: `{ schema: 'olio.pictureBookRequest', count: 4, chatConfig: '{chatConfigName}' }`
- **PASS:** 200, array of scenes, length > 0, each has title
- **Timeout:** 300s
- **Screenshot:** `pb-B1-extractScenes.png`

### B.2 Full Extraction
- Reset first (DELETE `/rest/olio/picture-book/{workObjectId}/reset`)
- POST to `/rest/olio/picture-book/{workObjectId}/extract`
- Body: `{ schema: 'olio.pictureBookRequest', count: 3, genre: 'contemporary', chatConfig: '{chatConfigName}' }`
- **PASS:** 200, meta.sceneCount > 0, each scene has objectId + title + description (blurb), meta.scenes matches GET /scenes
- **Timeout:** 600s
- **Screenshot:** `pb-B2-fullExtract.png`

### B.3 GET /scenes
- GET `/rest/olio/picture-book/{workObjectId}/scenes`
- **PASS:** 200, array, each entry has objectId, title, description (non-empty)
- **Log:** Print each scene with title, description length, character count

### B.4 Generate Scene Image
- Pick first scene objectId from B.2
- POST to `/rest/olio/picture-book/scene/{sceneObjectId}/generate`
- Body: `{ schema: 'olio.pictureBookRequest', chatConfig: '{chatConfigName}' }`
- **PASS:** 200, response has imageObjectId string
- **Timeout:** 300s
- **Screenshot:** `pb-B4-imageGenerated.png`

### B.5 Verify Image Record
- Fetch the imageObjectId via `am7client.get('data.data', imageObjectId)`
- **PASS:** Record returned with groupPath (string) and name (string)
- Build URL via `buildImageUrl` or `mediaDataPath`
- **PASS:** URL does not contain `undefined`, `[object`, or `null`

---

## C. Work Selector

### C.1 Loads with heading
- Navigate to `#!/picture-book`
- **PASS:** `<h2>` with "Picture Book" text visible
- **Screenshot:** `pb-C1-selector.png`

### C.2 Lists test user's documents
- **PASS:** At least 1 item listed (the AIME note)
- **PASS:** Item shows name containing "AIME"

### C.3 Click navigates to viewer
- Click the AIME item
- **PASS:** URL changes to contain `/picture-book/` + objectId
- **Screenshot:** `pb-C3-navigated.png`

### C.4 No network errors on selector
- Monitor all requests during selector load
- **PASS:** No 400 or 500 responses, no `[object Object]` in URLs

---

## D. Viewer — Cover Page

### D.1 Cover renders with real data
- Navigate to `#!/picture-book/{workObjectId}` (with extracted data)
- Wait for loading to complete
- **PASS:** `<h1>` title visible, "Begin" button present, page dots present
- **PASS:** Dot count >= 2 (cover + at least 1 scene)
- **Screenshot:** `pb-D1-cover.png`

### D.2 Scene count text
- **PASS:** Text contains "{N} Scene" where N > 0

### D.3 Begin advances to page 1
- Click Begin button
- **PASS:** Scene title in `<h2>`, "Page 1" text, no "Begin" text
- **Screenshot:** `pb-D3-beginClicked.png`

---

## E. Viewer — Scene Pages

### E.1 Scene page structure
- On page 1:
- **PASS:** `<h2>` title non-empty
- **PASS:** Blurb text visible, NOT "No blurb yet"
- **PASS:** "Page 1 of N" text
- **Screenshot:** `pb-E1-scene1.png`

### E.2 Character badges
- Check for character badge elements on scene page
- **LOG:** How many badges, what names

### E.3 Image display (if B.4 generated one)
- **PASS:** `<img>` element with src containing `/media/`, NOT containing `undefined` or `[object`
- **PASS:** Image loads without 404

### E.4 Navigate ALL pages with screenshots
- From cover, ArrowRight through every page
- **PASS:** Each page has different title than previous
- **PASS:** Page number increments
- **Screenshot:** `pb-E4-page-{N}.png` for each

### E.5 Blurb content on every scene page
- **PASS:** Every scene has blurb text length > 0
- **LOG:** First 100 chars of each blurb

---

## F. Viewer — Keyboard Navigation

### F.1 ArrowRight / ArrowLeft
- Cover → ArrowRight → Page 1 → ArrowLeft → Cover
- **PASS:** Correct state transitions

### F.2 Home / End
- End → last page, Home → cover
- **PASS:** Correct pages displayed

### F.3 Page dot click
- Click 3rd dot (if exists)
- **PASS:** Page changes, clicked dot has `bg-blue-500` class

### F.4 Boundary — left on cover
- On cover, press ArrowLeft
- **PASS:** Still on cover, no error

### F.5 Boundary — right on last page
- On last page, press ArrowRight
- **PASS:** Still on last page, no error

---

## G. Viewer — Fullscreen

### G.1 Toggle on
- Click fullscreen icon
- **PASS:** `.fixed.inset-0` element appears
- **Screenshot:** `pb-G1-fullscreen.png`

### G.2 Escape exits
- Press Escape
- **PASS:** Fixed element removed

### G.3 Navigate in fullscreen
- Enter fullscreen, ArrowRight
- **PASS:** Page advances, still in fullscreen

---

## H. Viewer — Blurb Editing

### H.1 Edit button shows textarea
- Click pencil icon on blurb
- **PASS:** `<textarea>` appears with blurb text
- **Screenshot:** `pb-H1-editing.png`

### H.2 Cancel reverts
- Click Cancel
- **PASS:** Textarea gone, original text displayed

### H.3 Regenerate via AI
- Click "Regenerate via AI"
- **PASS:** No JS error, blurb text updates (or endpoint responds 200)
- **Timeout:** 60s

---

## I. Export

### I.1 Export enabled with data
- **PASS:** Download button not disabled

### I.2 Export triggers download
- Click export, wait for download event
- **PASS:** Download filename contains `picturebook.html`
- **Save to:** `e2e/screenshots/{name}-picturebook.html`

### I.3 HTML content valid
- Read saved HTML file
- **PASS:** Contains `<h1>`, `<h2>`, `</html>`
- **PASS:** File size > 500 bytes
- **PASS:** Contains scene title text

### I.4 Export disabled on empty viewer
- Navigate to nonexistent work
- **PASS:** Download button is disabled

---

## J. Negative Tests

### J.1 Nonexistent work
- Navigate to `#!/picture-book/nonexistent-id`
- **PASS:** Empty state message, no JS crash
- **Screenshot:** `pb-J1-notFound.png`

### J.2 Zero mediaUrl errors
- Full flow: selector → viewer → navigate → back
- Capture all console errors
- **PASS:** None contain "mediaUrl" or "is not a function"

### J.3 Zero [object Object] URLs
- Monitor network during full flow
- **PASS:** No request URL contains `[object` or `undefined`

### J.4 Zero 400/500 during normal flow
- **PASS:** No 4xx or 5xx responses during normal viewer operation

### J.5 Keyboard on empty viewer
- On empty viewer: ArrowRight, ArrowLeft, Home, End, Escape
- **PASS:** No JS errors

### J.6 Back from empty state
- Click back arrow on empty viewer
- **PASS:** Returns to selector

---

## K. Server Log Audit

After ALL tests:
1. Read last 200 lines of `C:\Users\swcot\Desktop\WEB-INF\logs\accountManagerService.log`
2. Filter for test user name
3. **REPORT:** All ERROR lines — each must be explained
4. **REPORT:** All DENY lines — each must be explained
5. **REPORT:** All "Chat config" lines — verify model != null, serverUrl != null
6. **VERIFY:** No `olio.pictureBookRequest was not found` errors
7. **VERIFY:** No `Field description was not found on model data.note` errors
8. Save filtered log to `e2e/screenshots/pb-server-log.txt`

---

## L. Apparel Reimage Dialog (separate from Picture Book but fixed in same session)

### L.1 Apparel reimage dialog shows all fields
- Navigate to an apparel object, click Reimage
- **PASS:** Dialog shows: Steps, Refiner Steps, CFG Scale, Denoising Strength, Model, Refiner Model, Style, Seed, HiRes checkbox
- **Screenshot:** `pb-L1-apparelReimage.png`

### L.2 Reimage NOT on data.data
- Navigate to a data.data object
- **PASS:** No Reimage command button visible

### L.3 Reimage IS on charPerson
- Navigate to a charPerson object
- **PASS:** Reimage command button visible

---

## Execution Order

1. Setup (beforeAll): create user, AIME note, chatConfig
2. Phase B: Backend pipeline (extraction + image gen) — creates the data everything else needs
3. Phase A: Bug fix verification
4. Phase C: Work selector
5. Phase D: Cover page
6. Phase E: Scene pages
7. Phase F: Navigation
8. Phase G: Fullscreen
9. Phase H: Blurb editing
10. Phase I: Export
11. Phase J: Negative tests
12. Phase K: Server log audit
13. Phase L: Apparel reimage (if apparel test data available)
