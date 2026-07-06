# Picture Book — Implementation Plan

**Date:** 2026-03-27
**Design Doc:** `aiDocs/PictureBookDesign.md`
**Original Spec:** `aiDocs/Ux75ImplementationPlan.md` Phase 16 (lines 780-979)

---

## Phase 1: Fix Image URL Resolution (sceneExtractor.js)

The `am7client.mediaUrl()` function does not exist. All image display is broken.

**File:** `AccountManagerUx752/src/workflows/sceneExtractor.js`

Add:
```javascript
const imageRecordCache = {};

async function resolveImageUrl(objectId) {
    if (!objectId) return null;
    if (imageRecordCache[objectId]) return am7client.mediaDataPath(imageRecordCache[objectId]);
    // Fetch image record to get groupPath + name
    let rec = await am7client.getObject('data.data', objectId);
    if (rec) {
        imageRecordCache[objectId] = rec;
        return am7client.mediaDataPath(rec);
    }
    return null;
}

function clearImageCache() { Object.keys(imageRecordCache).forEach(k => delete imageRecordCache[k]); }
```

Export `resolveImageUrl` and `clearImageCache`.

---

## Phase 2: Rewrite Picture Book Viewer (features/pictureBook.js)

**Keep unchanged:** `workSelectorView`, route registration structure, feature manifest.

**Rewrite: `pictureBookView`**

### State
```
currentPage = 0          // 0 = cover, 1..N = scene pages
viewerScenes = []         // ordered scene array from meta
imageUrls = {}            // objectId → resolved media URL
loading / error
editingBlurb / blurbEditText / savingBlurb
fullscreen = false
```

### Load Flow
1. `loadPictureBook(workObjectId)` → scene array
2. For each scene with `imageObjectId`: `resolveImageUrl()` in parallel
3. Store resolved URLs in `imageUrls` map
4. Set `currentPage = 0` (cover)

### Render
- `currentPage === 0` → `renderCover()`
  - Uses first scene image (if available) as background
  - Title overlay, scene count, "Begin" button
- `currentPage > 0` → `renderScenePage(scene)`
  - Hero image (resolved URL), title, blurb, character badges
  - Page number

### Navigation
- Header: `[←] Title — Page X of Y [→] [Export] [⛶]`
- Bottom: page dots (clickable)
- Keyboard: `←` `→` `Home` `End` via `oncreate`/`onremove` listeners

### Edit Mode
- Pencil icon on blurb → inline edit (existing pattern preserved)
- Regenerate blurb button

### Fullscreen
- Toggle sets class on container, hides app chrome
- ESC exits

---

## Phase 3: Fix Wizard Step 5 (workflows/pictureBook.js)

**File:** `AccountManagerUx752/src/workflows/pictureBook.js`

- Replace `am7client.mediaUrl()` calls with `resolveImageUrl()` (async)
- Step 5 "View Picture Book" action: navigate to `/picture-book/:workObjectId` route
- Cover created last: after all images generated in Step 4, the meta `coverImageObjectId` is set to first scene's image

---

## Phase 4: Export as HTML

**File:** `AccountManagerUx752/src/features/pictureBook.js` (add export function)

### `exportPictureBook(workName, scenes, imageUrls)`
1. Fetch all scene image bytes as base64 (via canvas or fetch+blob)
2. Build self-contained HTML:
   - Inline CSS with book layout
   - Cover section (title + first image)
   - Scene sections (image + title + blurb)
   - Print-friendly styles
3. Create Blob → `URL.createObjectURL()` → trigger `<a download>` click
4. Filename: `{workName}-picturebook.html`

### HTML Template Structure
```html
<!DOCTYPE html>
<html><head><style>/* book styles */</style></head>
<body>
  <div class="cover">
    <img src="data:image/png;base64,..." />
    <h1>AIME</h1>
    <p>6 Scenes</p>
  </div>
  <div class="scene">
    <img src="data:image/png;base64,..." />
    <h2>Scene Title</h2>
    <p>Blurb text...</p>
    <div class="characters">Character badges</div>
  </div>
  ...
</body></html>
```

---

## Phase 5: Tests

### 5a. Vitest (src/test/pictureBook.test.js)
- `resolveImageUrl`: returns null for null, returns URL for valid objectId (mock fetch)
- `clearImageCache`: clears cache
- Page navigation bounds: cover at 0, scenes 1-N, can't go below 0 or above N
- Export HTML builder: produces valid HTML with embedded images

### 5b. Playwright (e2e/pictureBook.spec.js)
- Navigate to `/picture-book` → work selector renders
- Select a work → viewer loads
- Cover page displays title
- Navigate forward/back with arrows
- Keyboard ← → navigation
- Page dots reflect current page
- Blurb edit: click edit → type → save
- Export button triggers download
- Back button returns to selector

---

## File Change Register

| File | Action | Scope |
|------|--------|-------|
| `src/workflows/sceneExtractor.js` | Edit | Add `resolveImageUrl`, `clearImageCache`, export them |
| `src/features/pictureBook.js` | Rewrite viewer | Book-format viewer + export function. Keep work selector. |
| `src/workflows/pictureBook.js` | Edit | Fix mediaUrl → resolveImageUrl in Step 5 + renderStep5. Wire "View" to route. |
| `src/test/pictureBook.test.js` | Edit | Add tests for new functions |
| `e2e/pictureBook.spec.js` | Edit | Add viewer navigation + export tests |

## Execution Order
1. Phase 1 (image URL fix) — unblocks all image display
2. Phase 2 (viewer rewrite) — the main deliverable
3. Phase 3 (wizard Step 5 fix) — connects wizard to viewer
4. Phase 4 (export) — adds HTML export
5. Phase 5 (tests) — validates end-to-end
