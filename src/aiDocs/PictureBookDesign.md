# Picture Book — Completion Design Document

**Date:** 2026-03-27
**Status:** In Progress
**Test Content:** AIME.pdf (satirical short story, ~2500 words, 3 pages)
**Original Spec:** `aiDocs/Ux75ImplementationPlan.md` Phase 16 (lines 780-979)

---

## 1. What Exists vs. What Was Specified

### Backend (COMPLETE — matches spec, no changes needed)
- `PictureBookService.java` — all 7 REST endpoints implemented per spec (16.3)
- 9 LLM prompt templates (spec called for 4; additional landscape/portrait/list prompts added)
- Scene extraction, character creation, outfit + narrate pipeline, SD image generation — all working
- `.pictureBookMeta` data model matches spec (16.1)
- 9 backend tests (6 unit + 3 live LLM/SD integration)

### Frontend — What's Done
- `sceneExtractor.js` — API client layer with 8 exported functions (spec 16.4) — **DONE**
- `workflows/pictureBook.js` — 5-step wizard (spec 16.5) — **DONE structurally**, but:
  - Step 5 is a basic grid, not the "full-screen gallery" described in spec
  - Uses nonexistent `am7client.mediaUrl()` — **images cannot display**
- `features/pictureBook.js` — Work selector + viewer route (spec 16.4, 16.6) — **PARTIAL**
  - Work selector: working
  - Viewer: basic thumbnail strip + detail pane scaffold
  - Same `mediaUrl` bug — images don't render

### Frontend — What Was Specified But Not Finished (from spec 16.5, 16.6)

**Step 5 (spec 16.5):**
- [ ] Drag-to-reorder scenes (calls `/scenes/order`)
- [ ] "Insert into Document" — copy Markdown reference to clipboard
- [ ] "Export Picture Book" — download as HTML or ZIP
- [ ] Full-screen gallery with proper image display

**Viewer Route (spec 16.6):**
- [ ] "Responsive masonry or flex-row gallery" — only a basic scaffold exists
- [ ] Scene selection shows full-size image + blurb + character list
- [ ] "Edit" button opens wizard at Step 4 to add/regenerate
- [ ] Dark/light mode aware (partially — uses Tailwind dark: classes)

**Critical Bug:**
- `am7client.mediaUrl()` does not exist. Correct function: `am7client.mediaDataPath(imageRecord, isThumb, size)` — requires the full image record (with `groupPath`, `name`, `organizationPath`), not just an objectId.

---

## 2. User's Vision: Book-Format Viewer

The user wants to go beyond the original "masonry gallery" spec and present Picture Book output as a **sequential book** — comic, picture book, or pop-up book style:

- **Sequential pages** — one scene per page, navigated in order
- **Short blurbs or excerpts** from the story text on each page
- **Cover page created LAST** — after all scenes and images are generated, the cover is composed from the finished elements (uses first scene image or a dedicated cover generation)
- **Book-quality typography** — not a data grid
- **Export** — download as self-contained HTML (included in this pass)

### Compartmentalization

All elements for a book are scoped to the work's group path. Backend already creates:
```
{workGroupPath}/
  .pictureBookMeta     ← meta JSON (scene order, image refs, cover ref)
  Scenes/              ← scene data.note records + scene images
  Characters/          ← olio.charPerson records with portraits
```

The frontend must treat each book as a self-contained unit. The viewer, export, and wizard all operate within this scope. No cross-book leakage of state, images, or blurbs.

The `.pictureBookMeta` JSON gains a `coverImageObjectId` field (set after all scene images are done) and an `exportedAt` timestamp.

This is an evolution of the original spec, not a contradiction. The original viewer was deliberately left as a scaffold.

---

## 3. Story Analysis: AIME

AIME is a ~2500-word satirical short story about an "AI And Me" Valentine's Day singles event. People bring their AI phone assistants as dates to an upscale bar. Dark humor, literary prose, contemporary noir setting.

### Key Visual Scenes (6 candidates for LLM extraction)

| # | Scene | Setting | Mood |
|---|-------|---------|------|
| 1 | **The Event** | Upscale bar/lounge, dim lighting, tables for one, phone screens glowing | Melancholic, ironic |
| 2 | **The Wilted Bouquet** | Same venue; faces lit by phones, tears through heavy makeup | Somber, voyeuristic |
| 3 | **The Break-Up Storm** | Crashed phones, gasps, silver platter pallbearers to the cry room | Chaotic, theatrical |
| 4 | **The Abandoned** | Screen goes plaid then dark — "Ciao." Single person, empty table | Devastated, still |
| 5 | **Eyes Meet** | Two tables, vacuum between; two strangers locked in eye contact | Electric, cinematic |
| 6 | **Into The Rain** | Neon-lit street, rain, fog; phone store across the street, "new vessels" | Noir, bittersweet |

### Cover Concept
- Title: "AIME" (large, stylized)
- Image: Neon-lit bar through rain-streaked glass, single table, phone glow
- Style: Contemporary noir illustration

---

## 4. Image URL Resolution (the `mediaUrl` bug)

### Problem
The meta stores `imageObjectId` (a UUID). `am7client.mediaDataPath()` needs a record with `groupPath`, `name`, `organizationPath`. There's no direct objectId → URL endpoint.

### Solution
Add a helper to `sceneExtractor.js`:

```javascript
// Cache: objectId → { groupPath, name, contentType }
const imageRecordCache = {};

async function resolveImageUrl(objectId) {
    if (!objectId) return null;
    if (imageRecordCache[objectId]) {
        return am7client.mediaDataPath(imageRecordCache[objectId]);
    }
    // Fetch minimal record from model service
    let rec = await am7client.get('data.data', objectId);
    if (rec) {
        imageRecordCache[objectId] = rec;
        return am7client.mediaDataPath(rec);
    }
    return null;
}
```

This fetches once per image, caches for the session. Called during viewer load for all scenes in parallel.

---

## 5. Design: Book-Format Viewer

### Layout
```
┌──────────────────────────────────────────────────┐
│  [←]    AIME  —  Page 3 of 6           [→]  [⛶] │  ← Compact header
├──────────────────────────────────────────────────┤
│                                                  │
│    ┌──────────────────────────────────────┐      │
│    │                                      │      │
│    │         Scene Image (hero)           │      │
│    │         max-height: 55vh             │      │
│    │         object-contain, centered     │      │
│    │                                      │      │
│    └──────────────────────────────────────┘      │
│                                                  │
│    The Break-Up Storm                            │  ← Scene title
│    ───────────────────                           │
│    "An unsettling crash and crunch, a sudden     │  ← Blurb excerpt
│     hush making audible room for the imminent    │
│     gasp of a dejected soliloquy..."             │
│                                                  │
│    ┌─────┐ ┌───────────┐                        │  ← Character badges
│    │Staff│ │Patron     │                        │
│    └─────┘ └───────────┘                        │
│                                                  │
├──────────────────────────────────────────────────┤
│        ○  ○  ●  ○  ○  ○                         │  ← Page indicators
└──────────────────────────────────────────────────┘
```

### Cover Page (page index 0)
- Created LAST — after all scene images are generated
- Uses the first scene image as background (or a dedicated cover generation in future)
- Full-height hero with dark gradient overlay at bottom
- Title: large, centered, text-shadow for readability over image
- Scene count subtitle: "6 Scenes"
- If no cover image exists yet: dark gradient background with title only
- "Begin" arrow or tap to advance to page 1

### Scene Pages (pages 1–N)
- Hero image: `object-contain` (preserve aspect), centered, max ~55vh
- Placeholder if image not yet generated (gray box with "image" icon)
- Title: larger font, `font-serif` class for book feel
- Blurb: `leading-relaxed`, `text-base`, slightly indented for readability
- Character badges: small rounded pills below blurb
- Page number at bottom: "Page X of Y"

### Navigation
- **Arrows**: Left/right buttons on header bar (visible, not hovering ghost arrows)
- **Keyboard**: `←` prev, `→` next, `Home` first, `End` last
- **Page dots**: Clickable circles at bottom, filled for current page
- Event listeners attached in `oncreate`, removed in `onremove`

### Edit Mode
- Pencil icon on blurb text → inline textarea + Save/Cancel/Regenerate buttons
- Same pattern as existing `renderSceneDetail()` edit flow, preserved
- Only available in non-fullscreen mode

### Fullscreen
- Toggle button in header (expand icon)
- Sets a CSS class on the viewer container that hides app navigation
- Dark background, content centered with max-width
- ESC exits fullscreen

---

## 6. File Changes

| File | Scope |
|------|-------|
| `features/pictureBook.js` | **Rewrite viewer** — replace scaffold with book-format viewer. Keep work selector unchanged. |
| `workflows/pictureBook.js` | **Fix `mediaUrl` bug** in Step 5 render. Option: Step 5 "View" button navigates to `/picture-book/:id` route instead of inline grid. |
| `workflows/sceneExtractor.js` | **Add `resolveImageUrl()` helper** with cache. Export it. |
| `src/test/pictureBook.test.js` | **Add tests** for resolveImageUrl, page navigation logic |
| `e2e/pictureBook.spec.js` | **Add E2E tests** for viewer navigation, cover page, keyboard nav |

### Unchanged
- Backend — no changes
- Feature registration (`features.js`) — no changes
- Router — no changes
- Wizard Steps 1-4 — no changes (only Step 5 touched)

---

## 7. Export Picture Book

**Included in this pass.** The viewer header includes an "Export" button that generates a self-contained HTML file and triggers download.

### Export Format: Single HTML File
- Inline CSS (book layout styles)
- Scene images embedded as base64 `<img>` tags (fetched from media URLs at export time)
- Cover page as first section
- Each scene: image + title + blurb
- No external dependencies — the HTML file works standalone in any browser
- Filename: `{workName}-picturebook.html`

### Export Flow
1. User clicks Export button in viewer header
2. Frontend fetches all scene image bytes (via media URLs already resolved)
3. Builds HTML string with embedded images and styled layout
4. Creates Blob, triggers `<a download>` click
5. Updates meta `exportedAt` timestamp

### Deferred (not in this pass)
- Drag-to-reorder in viewer (endpoint exists, UI doesn't)
- "Insert into Document" clipboard copy
- "Edit" button in viewer that opens wizard at Step 4
- Dedicated cover image generation (separate SD prompt for cover art)

---

## 8. Test Plan

### Vitest
- `resolveImageUrl()`: returns null for null, fetches and caches, returns cached on repeat
- Page navigation: next/prev boundaries, cover page at index 0
- Cover vs scene page rendering branch

### Playwright (against live backend at localhost:8443)
- Navigate to `/picture-book` → work selector renders
- Select a work → viewer loads
- Cover page displays title
- Arrow navigation advances pages
- Keyboard ← → navigation
- Page dots update with current page
- Blurb edit flow (click edit → type → save)
- Back button returns to work selector
