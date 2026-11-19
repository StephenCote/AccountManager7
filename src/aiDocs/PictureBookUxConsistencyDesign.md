# PictureBook & ChapBook — Ux consistency correction (design)

Drafted 2026-11-19 on Stephen's request: *"Render puts a dynamically sized list at the top of the book;
characters is a pop-in (I like the current character popup); style, locations, composition board, review
queue, etc are wholly separate views … no obvious reasoning or consistency to the Ux layout … no clear/logical
separation between viewing the book and editing it. I want it clear whether the book is in view or edit mode,
and when in edit mode have a consistent unified experience. PB2 somewhat had this, setup in a wizard fashion …
This should only need front end changes."*

Status: **design only. Nothing here is built.** Stephen ruled on D1–D8 on 2026-11-19, and the rulings are
recorded in §10 and folded into the sections below. Every claim about current behaviour below cites the code that it was read from on
2026-11-19. Paths are relative to `src/AccountManagerUx752/src/` unless stated otherwise.

---

## 1. What is wrong today (inventory)

### 1.1 Where each book surface lives

| Surface | How it's reached | Container | Header | Back goes to |
|---|---|---|---|---|
| PB book list | menu → `/picture-book` | page, `max-w-3xl` | hand-rolled h2 (`features/pictureBook.js:742`) | — |
| PB reader | list row → `/picture-book/v2/:id` | page, `max-w-3xl` body | hand-rolled 5-group toolbar (`pictureBook.js:2526-2645`) | list |
| Style | reader **Style** button → `/picture-book/:id/style` | separate page, `max-w-3xl` | `pageHeader`, with Save in the header | reader |
| Characters | reader **Characters** button / list row icon | **Dialog** `xl`, master/detail (`workflows/pictureBookCharacters.js:1224`) | Dialog title | (close) |
| Locations | reader **Tools ▾** menu → `/locations` | separate page, `max-w-6xl`, card grid | `pageHeader` | reader |
| Composition board | Tools ▾, or the per-page **Compose** button → `/compose[/:scene]` | separate page, full width, 2+1 grid | `pageHeader` + scene prev/select/next + **Covers** link | reader |
| Cover art | Tools ▾ → `/cover[/:purpose]` | the compose page in cover mode | `pageHeader` + purpose tabs + **Scenes** link | reader |
| Review queue | Tools ▾ → `/review` | separate page, `max-w-3xl` | `pageHeader` + filter pills (hand-rolled classes, `pictureBookReview.js:329`) | reader |
| Workflow canvas | Tools ▾, list row icon → `/workflow` | separate page, full bleed | `pageHeader({bar:true})` + 9 actions | reader |
| Render | reader **Render** → Dialog (`pictureBook.js:1769`) | Dialog `sm` | — | — |
| Plan & generate | per-page button → Dialog (`pictureBook.js:1888`) | Dialog `md` | — | — |
| Chat config, Auto-approve | Tools ▾ menu items (`pictureBook.js:2490-2497`) | menu item / picker | — | — |
| Create wizard | list → Browse Notes / Documents | Dialog `xl`, 4 steps (`workflows/pictureBook.js:2192`) | local stepper (`:1938`) | — |
| ChapBook library | menu → `/chap-book` | page, `max-w-5xl`, not centred | hand-rolled h2 (`features/chapBook.js:2392`) | — |
| ChapBook reader | library → `/chap-book/read/:id` | `ChapBookConfig` panel **above** a `ReaderShell` | ReaderShell header | library |
| ChapBook "Review" (its editor) | library / reader → `/chap-book/review/:id` | page, `max-w-6xl`, master/detail pages | `pageHeader` + config panel + sticky style bar | library |

### 1.2 The concrete inconsistencies

1. **There is no mode.** The PB reader mixes reading with editing. Every scene page carries Compose, Render
   or Re-render, Regenerate background, and Plan & generate (`pictureBook.js:2418-2447`), and the toolbar
   carries Style, Characters, Render, Tools and Delete. The ChapBook reader goes further: it mounts the whole
   `ChapBookConfig` panel above the book (`chapBook.js:2874-2880`), which brings the poem queue, Create,
   default settings and Render with it, and it adds Analyze and a chat-config picker to the shell's toolbar
   (`chapBook.js:2770-2799`).
2. **Render progress pushes the book down.** `renderPb2RunProgress` (`pictureBook.js:1922-1942`) inserts a
   `jobRunPanel` between the toolbar and the page. It has one row per planned stage, from
   `deriveStageList(pb2Run.planned, job)`. Its height grows with the run, it stays on screen until it is
   dismissed, and it is deliberately kept in fullscreen (comment at `:2806`). ChapBook has the opposite
   problem: a bulk render shows nothing except the button label "Rendering N/M…". The per-scene
   `renderProgress` map is written but never rendered (`chapBook.js:689`, `:1470`).
3. **Edit surfaces use four unrelated containers:** a page route (Style, Locations, Compose, Cover, Review,
   Canvas), a Dialog (Characters, Render, Plan), a context-menu item (Chat config, Auto-approve), and an
   inline panel (ChapBook config). Which container a surface got depends on when it was built, not on what
   it is.
4. **Navigation between edit surfaces is ad hoc.** Every sub-page's only way out is "back to the reader".
   Compose and Cover link to each other in their header (`pictureBookCompose.js:1248-1250`), and nothing else
   links sideways. Locations, Composition, Cover, Review and Canvas are hidden behind a "Tools" overflow
   labelled "optional" (`pictureBook.js:2472-2478`). Style and Characters are on the toolbar.
5. **The wizard can't be re-entered.** The 4-step create wizard (Source › Scenes › Characters › Style) is
   the one place the book's setup reads as an ordered, skippable sequence. An existing book never re-enters
   it (`workflows/pictureBook.js:2134-2147`). The list row's Edit pencil only navigates to the reader, and its
   comment ("lands on Step 4/5", `pictureBook.js:169-171`) is stale.
6. **Every surface lays out differently.** Widths are 3xl, 5xl, 6xl, or none. The layouts are a card grid
   (Locations), a single column (Review, Style), a 2+1 grid with the scene selector in the header (Compose),
   or master/detail (Characters, ChapBook pages).
7. **There are five save models:**
   - Style: one header Save, with a "•" dirty mark on each changed field.
   - Compose: a Save per section and per row, and no dirty marks except the amber ring on a moved canvas box.
   - Locations: inline Save/Cancel on each card.
   - ChapBook pages: a Save on each card plus Save all, with an "unsaved" badge on the list row.
   - Autosave in two places: the character stat sliders save on input, and the ChapBook book-style bar sends
     N PATCHes when you change a value.

   No surface guards against navigating away with unsaved edits. Compose silently drops its drafts when the
   scene changes (`pictureBookCompose.js:1655`).
8. **The headers are inconsistent.** The PB reader and both libraries hand-roll their headers. The sub-pages
   use `pageHeader`, and the ChapBook reader uses ReaderShell. The tooltips differ too: "Back to the book",
   "Back to book", "Back to Poem Library".
9. **The terminology collides.** In ChapBook, "Review" means *edit the book* (`/chap-book/review`). In
   PictureBook, "Review queue" means *approve or reject rendered artifacts*. Also, ChapBook's create lands
   on its reader (`chapBook.js:1704`), while PB's create lands on its reader after Style.
10. **Small things are duplicated:**
    - `INPUT` is defined three times, and `CHIP` and `field()` twice each
      (`pictureBookCompose.js:1163-1168`, `pictureBookLocations.js:579-582`, `pictureBookStyle.js:563`).
    - Review hand-writes its button classes instead of using `BTN` (`pictureBookReview.js:232`, `:329`).
    - Dialog `actions` are an array in Compose but `{view}` in Locations.
    - The loading text and its placement differ on every page.

### 1.3 The design of record already asked for this

`PictureBookV3Design.md` §26 specified a single **Book workspace**:
`Book ▸ Style | Characters | Locations | Scenes | Cover & Art | Review queue`. Scene detail was to be
`Story | Composition | Render | Review`, and the Workflow canvas was to stay as the power-user view.

The as-built notes in §26 record that each piece was instead shipped as its own sub-route reached from the
reader (P3-7, P4-9). This correction finishes §26. It does not replace it.

---

## 2. Principles

1. **A book is always in exactly one of two modes, Read or Edit, and the mode is always visible.** The mode
   is part of the URL, appears in the header as a segmented `Read | Edit` toggle, and Edit mode has its own
   visual treatment (§4.1).
2. **Read mode has no editing controls.** It shows the book, page navigation, export, fullscreen, and one way
   into Edit mode. It may *show* status ("3 pages need rendering"), but it does not act on it.
3. **Edit mode is one workspace with one navigation model.** It has a section rail on the left, in the
   wizard's order, with a status mark on each section; the active section in the centre; and Back/Next in
   the footer. It works the same way for PictureBook and ChapBook. You can jump to any section at any time,
   and you can come back to it at any time.
4. **Collections use master/detail.** The layout to copy is the Characters manager that Stephen likes:
   a `w-56` list with status badges on the left, and the selected item's detail on the right with its own
   scroll. Locations, Scenes, Cover art, the Review queue and ChapBook pages all follow it.
5. **Long-running work never moves the content.** Job progress lives in a fixed-height **activity dock** in
   Edit mode, and as a one-line **activity pill** in the header in both modes. It is never inserted in the
   page flow above the book.
6. **Dialogs are for one bounded task with a commit or a cancel.** Examples: create, add, merge, confirm,
   pick, render settings, plan & generate. A dialog is never used to *manage a whole section*. The Characters
   pop-in is retired (D2): Characters becomes an Edit section, and it is reachable from wherever a character
   appears (§4.7).
9. **In Edit mode, the book is still a book.** You can flip through pages inside Edit (the Scenes / Pages
   section's **Page** tab, §5.6), and everything on the page links to the place that edits it:
   characters, location, scene settings, composition, render (D3).
7. **One save model** (§6.2): explicit Save at item level, dirty marks on fields and list rows, a guard
   whenever dirty state would be lost, and immediate commands stay immediate.
8. **Frontend only.** Every action below calls an endpoint the client already calls today
   (`workflows/pictureBookWorkflow.js`, `features/chapBook.js`). §9 lists the places where that has to be
   confirmed rather than assumed.

---

## 3. Routes

The mode lives in the URL, so a link, a refresh or a back-button press lands in the right place.

| Route | Mode / section | Replaces |
|---|---|---|
| `/picture-book` | PB library | (same) |
| `/picture-book/v2/:id` | **PB Read** | (same route, kept: it's in tests, bookmarks and `readerRoute()`) |
| `/picture-book/:id/edit` | PB Edit → first section needing attention, else **Book** | — |
| `/picture-book/:id/edit/:section` | PB Edit → section | `/style`, `/locations`, `/review`, `/compose`, `/cover`, `/workflow` |
| `/picture-book/:id/edit/:section/:item` | PB Edit → section + selected item | `/compose/:sceneObjectId`, `/cover/:purpose` |
| `/chap-book` | CB library | (same) |
| `/chap-book/read/:id` | **CB Read** | (same) |
| `/chap-book/edit/:id[/:section[/:item]]` | CB Edit | `/chap-book/review/:id` |

Mithril has no optional parameters (as the P4-9 notes say), so each arity is its own route entry.

**Old routes stay as redirects**, using `m.route.set(new, null, {replace:true})` with the same params, so
existing links, tests and the `pbReader.js` e2e helper keep working while specs migrate:

| Old route | Redirects to |
|---|---|
| `/style` | `edit/style` |
| `/locations` | `edit/locations` |
| `/review` | `edit/review` |
| `/compose` | `edit/scenes` |
| `/compose/:s` | `edit/scenes/:s` (Composition tab) |
| `/cover` | `edit/cover` |
| `/cover/:p` | `edit/cover/:p` |
| `/workflow` | `edit/canvas` |
| `/chap-book/review/:id` | `/chap-book/edit/:id/pages` |

A redirect is a pure mapping, so it is tested in Vitest.

The legacy PB1 viewer route `/picture-book/:bookObjectId` (ReaderShell, read-only, with an "Import to V3"
banner) is out of scope. It already redirects to v2 whenever a V3 record resolves
(`pictureBook.js:829-841`).

---

## 4. Shared chrome (both products, both modes)

### 4.1 `bookHeader` — new `components/bookHeader.js`, built on `pageHeader`

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ←  Series · Book name  [Chapter ▾]      [ Read | Edit ]      ● Rendering 4/12 ▸     ⋯ mode actions │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **← (back)** always goes to the product's library. The tooltip says "Back to Picture Books" or "Back to
  ChapBooks". This is the only back arrow anywhere in the book.
- **Title block:** the series name if there is one, the book name, and the chapter `<select>` (the existing
  `renderPb2ChapterSelect`; it switches chapter and keeps the mode and section).
- **Mode toggle:** a segmented `Read | Edit` control (`data-testid="book-mode"`, `data-mode="read|edit"`).
  Switching keeps your place: Read page N ⇄ Edit › Scenes › scene N (§4.3).
- **Activity pill** (`data-testid="book-activity"`): appears only while a job is running, or after one
  finishes and before it is dismissed. It is one line: icon, label, counter. Clicking it opens Edit › Render
  with the dock expanded. It replaces the in-flow `renderPb2RunProgress` panel, and it gives ChapBook a
  progress indicator for the first time.
- **Mode actions** (right side):
  - Read: Export, Fullscreen.
  - Edit: none at book level. Section actions belong to the section toolbar (§4.2), and Delete moves to
    Edit › Book.
- **Edit-mode treatment:** the header gets a 3 px accent bottom border and an "Editing" chip beside the
  title, and the page background shifts one step (`bg-gray-50 dark:bg-gray-900`). These three cues make it
  unambiguous which mode you're in. Read mode keeps today's plain look.

The PB2 reader keeps its own header today because it has "workflow controls the shell has no slots for"
(`pictureBook.js:1301-1307`). Once those controls leave Read mode, the PB reader and the ChapBook reader can
share the same header, and they also share the `ReaderShell` body (D5, §5.1).

### 4.2 `bookWorkspace` — the Edit-mode shell, new `components/bookWorkspace.js`

```
┌ bookHeader (Edit) ───────────────────────────────────────────────────────────────────────────┐
├──────────────┬───────────────────────────────────────────────────────────────────────────────┤
│ SECTIONS     │ ┌ section toolbar: Title · meta chips ……………… section actions · ⟳ ┐              │
│ 1 Book     ✓ │ └──────────────────────────────────────────────────────────────┘              │
│ 2 Style    ⚠ │                                                                               │
│ 3 Characters │   section body — master/detail for collections (§4.4),                        │
│ 4 Locations  │   a form for settings sections                                                │
│ 5 Scenes  3● │                                                                               │
│ 6 Cover & Art│                                                                               │
│ 7 Review   5 │                                                                               │
│ 8 Render     │                                                                               │
│ ───────────  │                                                                               │
│ Canvas (adv.)│                                                                               │
├──────────────┴───────────────────────────────────────────────────────────────────────────────┤
│ ◂ Back: Style                       [activity dock, collapsible]             Next: Locations ▸ │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Section rail** (`w-48`, `data-testid="ws-rail"`): sections are numbered in the wizard's order, so the
  rail reads like the PB2 stepper. Each row carries one status mark from a shared vocabulary:
  - ✓ done
  - ⚠ needs attention (for example, Style has no description; the existing `pb2StyleMark`)
  - a count (for example, 3 scenes stale or need rendering, 5 items waiting in review)
  - ● running (a job owned by that section)
  - nothing (optional, or not yet known)

  Every row is always clickable; nothing is locked behind an earlier step. Status comes only from data the
  client already loads (§9).
- **Section toolbar:** `pageHeader({bar:true})` inside the pane. It holds the section title, its meta (counts,
  chips) and its actions (New location, Propose from scenes, Compose book…, the filter, refresh). Today's
  per-page headers move here **unchanged apart from losing their back arrow and their cross-links**: the
  Compose page's "Covers" and the Cover page's "Scenes" buttons go, because the rail replaces them.
- **Footer:** Back / Next go to the neighbouring sections and show their names. On the last section, Next
  becomes "Done — Read the book" and switches to Read mode. This is the wizard's back/forth/skip, available
  at any time.
- **Activity dock** (§4.5) sits in the footer row, between Back and Next.
- **Scrolling:** the rail and the section body each scroll on their own. The header and footer don't scroll.
- **Narrow screens** (below `lg`): the rail collapses into a `<select>` at the top of the pane. The footer
  stays.

### 4.3 Keeping your place across modes

The workspace keeps one `focus` object per book — `{section, itemId}` for Edit, and `{page}` for Read —
in module state, keyed by book objectId.

- **Entering Edit with no explicit target** (the Edit toggle, the library pencil, a bare `/edit`) opens the
  **next incomplete section** (D6). This is the first section in rail order whose status is ⚠ or has an
  outstanding count. If none is incomplete, it opens the most logical working section:
  - a book with no style description opens **Style**;
  - otherwise **Scenes** (ChapBook: **Pages**), at the page Read last showed.
  
  The resolver is one pure function, `nextSection(statuses, lastPage)`, tested in Vitest. While the statuses
  are still loading, it falls back to Scenes / Pages instead of waiting.
- **"Edit this page" from Read** (an icon-only affordance at the top-right of the image) is an explicit
  target. It opens `edit/scenes/:sceneObjectId?tab=page`. It replaces today's four per-page buttons (D3).
- **Guided mode after a step** (`?guided=1`, §7): Next goes to the next *incomplete* section after the
  current one, not merely the adjacent one. The footer names where it goes ("Next: Style").
- **Edit › Scenes › scene N → Read:** opens page N. Any other section returns to the page Read last showed.

### 4.7 Cross-links: everything on a page links to what edits it

There is one helper, `editLink(book, target)`, with `target` ∈ `{character, location, scene(tab), cover,
review}`. It returns a route under `/edit`, goes through the dirty guard (§6.2), and is used everywhere an
entity is named in Edit mode:

| Where the entity appears | Links to |
|---|---|
| Page tab (§5.6): character names / portraits on the page | `edit/characters/:charObjectId` |
| Page tab: the location chip | `edit/locations/:locationObjectId` |
| Page tab: the scene settings / composition / render buttons | the same scene's Story / Composition / Render tab |
| Composition tab: an actor row | `edit/characters/:charObjectId` (and the existing outfit picker stays in place) |
| Characters detail: "Affected scenes" (today it links to `/review`, `pictureBookCharacters.js:327-369`) | each scene number → `edit/scenes/:s?tab=page`; Review → `edit/review` |
| Locations detail: "used by scenes 3, 7, 12" | each number → `edit/scenes/:s?tab=page` |
| Review detail: a node's scene / character / location | the owning section |
| Library row: the Characters icon (today it opens the pop-in) | `/picture-book/:id/edit/characters` |

Characters therefore needs `:item` = the character's objectId, which the manager's selection takes as its
initial value.

### 4.4 `masterDetail` — new `components/masterDetail.js`

This extracts the Characters manager's skeleton (`pictureBookCharacters.js:1176-1190`) and the ChapBook
page list (`chapBook.js:4322-4343`) into one component:

```
masterDetail({
  testid, items, selectedId, onSelect,
  rowView(item) → {title, subtitle?, badges:[{text,tone}], dirty?},
  toolbar?: [vnode]          // above the list: "Merge duplicates", filters, "+ New"
  multiSelect?: {...}        // the Characters merge mode, generalized
  detailView(item) → vnode,  // the right pane; owns its own scroll
  emptyText
})
```

- The list is `w-56`, with its own scroll and a selected state that matches today's blue. The badges use the
  `pageHeader` `chip` tones. A row with a dirty item shows an "unsaved" chip, as ChapBook does today
  (`chapBook.js:4297-4320`).
- **Keyboard:** ↑/↓ moves the selection when the list has focus.
- `ensureSelection(items, selectedId)` is a pure re-seat rule, generalizing `ensureReviewSelection`:
  - keep the selection if it still exists;
  - else select the item at the same index;
  - else select the first item.

  It is tested in Vitest.

### 4.5 Activity: one model, two renderings

There is one `bookActivity` store per book, `{label, counter, terminal, failed, stages, error, cancel, dismiss}`.
It is fed by:

- PB: `pb2WatchJob` / `pb2ReattachRun`
- Locations renders
- Compose and compose-book jobs
- ChapBook create, `renderChapBookScenes` and per-page re-render
- extraction, when it is running in the wizard

It renders in two ways:

- **Pill** (`bookHeader`, both modes): one line. Clicking it opens Edit › Render.
- **Dock** (`bookWorkspace` footer): collapsed it is the same one line; expanded it is `max-h-64`, scrolls
  internally, and holds the existing `jobRunPanel` stage list. Expanding it overlays the section body from
  the bottom rather than resizing it.

Rules:

- No surface renders `jobRunPanel` into the page flow any more. Today it does so at `pictureBook.js:1929`,
  `pictureBookCompose.js:1260` and `:1323`, and `pictureBookLocations.js:669`.
- The *create* dialogs (wizard extraction, ChapBook create) keep their in-dialog progress, because the
  dialog *is* the task.
- Leaving the page still only stops *watching* the job; the server job continues and re-attaches on return
  (as it does today, `pictureBook.js:2787-2788`).
- ChapBook's render becomes visible: the dock lists one row per page with skipped / pinned / failed / done
  states, fed from the `renderProgress` map that is already maintained but never rendered.

### 4.6 Small shared primitives — new or consolidated in `components/`

- `stateBlock({loading, error, empty, loadingText, emptyText, testids})` provides one look for
  loading / error / empty states: centred, `py-12`, the same type scale. It replaces the per-page variants
  listed in §1.2 (10), and keeps each page's existing testids.
- `formKit.js` holds `INPUT`, `CHIP`, `WARN_CHIP`, `field(label, control, {dirty, inherited})` and `dirtyMark`.
  The three copies of `INPUT` and the two copies each of `CHIP` and `field()` are deleted.
- Buttons come only from `components/buttons.js`. Review's hand-written classes become `BTN` / `BTN_PRIMARY`,
  and its filter pills become a shared `segmented({options, value, onchange})`. The same control drives the
  mode toggle, Canvas's Chapter/Series toggle, and Review's All/Auto-approved filter.
- Dialog `actions` always use the array form. The `{view}` form survives only where the labels must react
  ("Creating…"). For that case the helper `reactiveActions(fn)` keeps the shape uniform.

---

## 5. PictureBook — section by section

### 5.1 Read mode — `/picture-book/v2/:id`

| Kept | Removed from the reader | Goes to |
|---|---|---|
| ← library, ‹ page ›, title/series, chapter select, page dots, keyboard paging | Style button | Edit › Style |
| Export, Fullscreen | Characters button | Edit › Characters (the pop-in is retired, D2) |
| Stale / rejected badge **on the image**, since that is status about the page itself | Render (primary), "needs style" link | Edit › Render |
| Location chip, as a label only, not a picker | Tools ▾: Locations, Composition, Cover, Review, Canvas, Chat config, Auto-approve | their Edit sections; Chat config and Auto-approve go to Edit › Book |
| Health banner, **reduced to one line**: "This book has N problems — Fix in Edit" | Repair button | Edit › Book |
| Empty state ("no scenes rendered yet") with an **"Open in Edit"** button | "Open Workflow Canvas" button | Edit › Render (or Canvas when the health check says the graph is stale) |
| | Per-page Compose / Render / Regenerate background / Plan & generate | Edit › Scenes › scene, Render tab (§5.6) |
| | Delete | Edit › Book › Danger zone |
| | Run progress panel | activity pill (§4.5) |
| | Resume banner | the activity pill shows "Paused — Resume", which opens Edit › Render |

**The reader moves onto `ReaderShell`** (D5), the same component the ChapBook reader and the PB1 viewer use,
so both products read with one look and feel. The custom header existed because the reader carried
"workflow controls the shell has no slots for" (`pictureBook.js:1301-1307`). With those gone, the PB-specific
parts fit the shell's existing parameters:

- the scene page renderer → `renderPage`;
- the front pages (cover, series art) → `frontPages` / `renderFrontPage`, as ChapBook already passes them;
- the export → `exportBook`, which the reader already reuses.

The shell's own header is replaced by `bookHeader` in Read mode, for both products. Two things are owed
before the switch:

1. The shell's fullscreen overlay is z-50 and skips the layout wrapper, while the PB reader's is z-30 and
   keeps the wrapper so that the run it is watching survives the toggle (`:1303-1306`). Watching now belongs
   to the `bookActivity` store (§4.5), not to the mounted reader, so the shell's contract is correct for both.
   That has to be proven by a test: start a run, toggle fullscreen, and confirm the pill's counter keeps
   advancing.
2. The status badges on the image (stale / rejected) and the location label need a slot: a new optional
   `pageOverlay(page)` parameter on the shell. That is the only shell API change.

### 5.2 Edit › Book (new section, PictureBook and ChapBook)

This section collects the book-level settings that are currently scattered across a menu, banners and
icon buttons:

- **Identity:** name, slug (read-only), series and chapter position, scene count, status.
- **Defaults:** the Chat config control (`renderChatConfigControl`, stacked), the Auto-approve toggle
  (`pb2ToggleAutoApprove`). ChapBook adds Fixed page height (`setBookFixPageHeight`) and Max lines per page.
- **Health:** the full findings list and Repair (`renderPb2HealthBanner` / `repairPb2Book`, moved
  unchanged).
- **Chapters:** "New chapter…" (the existing Canvas dialog, `pictureBookWorkflow.js:1376`) and Share
  (`:1216`).
- **Retention:** the existing dialog (`:1563`).
- **Danger zone:** Delete book. Delete series appears when the book is the first chapter.

Its rail status is ⚠ when health has errors, and ✓ otherwise.

### 5.3 Edit › Style

- The section body is `renderStyleBody()` unchanged. The wizard already embeds it this way
  (`renderStyleHeader({embedded:true})`, `workflows/pictureBook.js:1916-1928`).
- The section toolbar holds Lock/Unlock and Save, as the Style header does today.
- The dirty "•" marks and "Inherited from X" tags stay. They are the reference for §6.2.
- Rail status is `pb2StyleMark`.

### 5.4 Edit › Characters

- The body is `renderCharacterManagerContent()`, the master/detail layout the pop-in has today, rendered inline
  as wizard step 3 already does (`pictureBookCharacters.js:1199-1215`).
- "Open Full Editor →" leaves the workspace through the guard (§6.2).
- Rail status shows a count of characters with a warning badge (no portrait, no apparel, failed).
- `:item` = the character's objectId, so §4.7's links land on the right character.
- **The pop-in is retired** (D2). `openCharacterManager(…, {standalone:true})` and its `Dialog.open`
  (`pictureBookCharacters.js:1224-1235`) are removed once the library row, the reader and the wizard no
  longer call it. The library row's Characters icon routes to `edit/characters`.
- **Stat sliders become drafts** (D4). Moving a slider marks the field dirty, and the character's pane Save
  commits it, the same as every other field.

### 5.5 Edit › Locations — becomes master/detail

- **List:** name, a kind chip, a lock badge, and "used by N scenes". The toolbar holds
  `+ New location`, `Propose from scenes`, and a **Proposals** pseudo-row pinned to the top of the list.
  Selecting that row puts the proposals panel and its status filter in the detail pane.
- **Detail:** today's card content (`pictureBookLocations.js:735-798`) laid out as a page: description and
  aliases (inline edit, explicit Save), variants with their render chains, Render, Lock, Merge, Delete. The
  location's render job goes to the activity dock instead of an inline panel.
- `:item` = the location's objectId.

### 5.6 Edit › Scenes — the scene list plus the composition board

This is V3 §26's "Scene detail: Story | Composition | Render | Review", finally built:

- **List:** the scene number and title, a thumbnail, and the status chips that are on the reader today
  (stale / needs render / rejected / approved), plus the degradation and location chips. It replaces the
  composition board's header prev / select / next (`pictureBookCompose.js:1240-1245`).
- **Detail tabs** (using `components/tab.js`):
  - **Page** (the default tab): the page exactly as Read draws it, using the same `renderPage` (D3). It has
    ‹ › to flip to the previous or next scene, which moves the list selection and goes through the guard.
    Everything on it is a §4.7 link: character names, the location chip, and buttons for Story / Composition /
    Render. It shows the stale / rejected badges, and the render gate's reason when rendering is blocked.
  - **Story:** editable (D7). The fields are `title` and `blurb` (the page text), `summary`, and `setting` /
    `action` / `mood`, all existing `olio.pb.scene` fields
    (`AccountManagerObjects7/src/main/resources/models/olio/pb/sceneModel.json`).
    - Save sends one `PATCH /rest/model` with `schema`, the identity fields, the changed keys and
      `userEdited:true`. This is the mechanism ChapBook already uses for its page title / stanza
      (`saveSceneRecord`, `chapBook.js:3553`).
    - `name` is never sent unless it changed (the `common.name` validation trap, `.claude/rules/model-api.md`).
    - The PATCH result is checked; a false result keeps the draft and toasts an error.
    - If Composition currently shows setting / action / mood in its "Camera & scene" block, those fields
      move here, and Composition keeps the camera.
    - **Stale is the server's call.** After a Save the scene is re-read with `cache:false`, and its chip
      shows whatever the server's stale check reports.
    - **Owed before this tab ships (§9-2):** a live test that edits `blurb` and records whether the scene
      then reads stale. If it does not, the tab says "Text saved — the image was rendered from the earlier
      text" until the next render. It must not claim a staleness the server does not report.
  - **Composition:** today's board body, unchanged: placement canvas, actors, elements, prompt override,
    image config override, with the compiled plan aside.
  - **Render:** the current image and its variant strip; Render / Re-render; Regenerate background (only
    when there's an image, as today); and Plan & generate (the existing dialog). The `pb2RenderGate` reason is
    shown inline.
  - **Review:** this scene's slice of the review queue, using the same artifact rows as §5.8.
- The section toolbar holds the Chat config control and "Compose book…", both moved from the board header.
- `:item` = `sceneObjectId`. The tab is a query param (`?tab=composition`), so `/compose/:s` redirects to
  `edit/scenes/:s?tab=composition`.

### 5.7 Edit › Cover & Art

- **List:** Cover, Title page, Chapter opener, Series art — today's purpose tabs. Each row has a green ● when
  that purpose has a composition, as it does today.
- **Detail:** the cover-mode board and its cover strip, with "Create cover…" (the existing dialog,
  `pictureBookCompose.js:782`).
- `:item` = the purpose word (`coverPathWord`).

### 5.8 Edit › Review

- **List:** the nodes in the queue, each with an unreviewed / QA-warning / stale-approved badge. The
  toolbar holds the All / Auto-approved `segmented` filter and an Auto-approve status chip that links to
  Edit › Book.
- **Detail:** that node's artifact rows (Use this / Approve / Reject) and its `PbPlanInspector`, shown
  expanded in the pane instead of a `max-h-96` toggle.
- Rail status shows the queue count.

### 5.9 Edit › Render

This is the book-level run, which today is split between a dialog, a banner and an in-flow panel:

- The render settings that the Render dialog holds today (`pictureBook.js:1729-1755`): page count, the
  auto-approve-for-this-run checkbox, and the "needs style" gate with a link to Edit › Style.
- **Render all**, plus **Resume** when a paused run exists.
- The **activity dock pinned open** above the footer, so the stage list lives *here* in a fixed region.
- A per-scene summary table (scene, state, last result) built from the same `deriveStageList` rows.
- Rail status: ● while a run is going, or the count of scenes needing a render.

### 5.10 Canvas (advanced) — `edit/canvas`

- The Workflow canvas becomes the last rail item, under a divider labelled "Advanced".
- Its toolbar is kept, but without its back arrow, and its "Pages" button becomes the mode toggle.
- The section body is full-bleed, so the canvas keeps its pan/zoom area, and the node detail panel stays as
  its right overlay.
- It is the only section that hides the footer's Back/Next, because the canvas needs the height.

---

## 6. Cross-cutting rules

### 6.1 Container rule: which surface gets which container

| It is… | Container |
|---|---|
| Reading the book | Read mode |
| A book-level settings area (Book, Style) | an Edit section with a form body |
| A collection of things in the book (characters, locations, scenes, covers, review items, pages) | an Edit section with `masterDetail` |
| One bounded task with commit/cancel (new, add, merge, pick, confirm, create cover, render settings, plan & generate, outfit) | a Dialog |
| A long-running job | the activity pill + dock (and the create dialog while creating) |
| Creating a book | the create wizard (§7) |

### 6.2 Save model (one rule, every section)

1. **Edits are drafts until saved.** The detail pane's toolbar has **Save** and **Revert**, both disabled
   unless the item is dirty. Settings sections (Book, Style) have one Save for the section, as Style does today.
2. **Show what is dirty:**
   - a "•" `dirtyMark` beside each changed field (Style's existing pattern);
   - an "unsaved" chip on the item's list row (ChapBook's existing pattern);
   - a "•" on the rail section that holds any unsaved item.
3. **Guard every path that would lose drafts:**
   - selecting another item;
   - switching section, tab, or mode;
   - changing chapter, going back to the library, or following Open Full Editor;
   - a route change, via `onbeforeremove` on the workspace plus a `beforeunload` listener while dirty.

   The guard is `Dialog.confirm` with three outcomes: **Save**, **Discard**, **Stay**. It is one helper,
   `guardDirty(next)`, unit-tested on its decision table.
4. **Drafts survive switching** within the workspace when the user picks Stay or Save. Compose's
   `resetState`-on-scene-change (`pictureBookCompose.js:1655`, `:1699`) is replaced by drafts keyed per item,
   so they are no longer dropped. This generalizes ChapBook's `_dirty` set (`chapBook.js:3531`) and the
   `chapBookReviewMasterDetail` test's "dirty flag survives switching pages".
5. **Writes stay sparse.** Every Save sends only the changed keys, through the existing delta helpers:
   `touch()`/draft for Style, `saveEdit` for Locations, Compose's delta PATCH, `saveSceneRecord`, and
   `computeBookSdDelta`. Nothing about what goes over the wire changes. Per `.claude/rules/model-api.md`, the
   update result is never discarded: a failed Save keeps the draft and toasts the error.
6. **Commands are immediate and never drafts:** Approve, Reject, Use this, Lock, Pin/Unpin, Render, Delete,
   Merge, Split, Propose, and Repair. Destructive commands use `Dialog.confirm({destructive:true})`.
7. **Both existing autosaves become explicit** (D4):
   - **Character stat sliders.** Today they save on input (`pictureBookCharacters.js`). They become drafts
     that are committed with the character's Save.
   - **ChapBook book-style bar.** Today `applyBookStyle` (`chapBook.js:3835`) sends N per-page PATCHes on
     every change. It becomes a section draft with a preview applied on screen. Save sends the same N PATCHes
     once, then reports the outcome, including partial failures, as `promptWriteOutcome` already does for
     prompts.

### 6.3 Layout widths

| Region | Width |
|---|---|
| Read body | `max-w-3xl`, centred (as today) |
| Edit form sections | content `max-w-4xl` inside the pane |
| `masterDetail` detail panes | full pane width |
| Canvas | full bleed |
| Libraries (both products) | `max-w-5xl mx-auto` |

### 6.4 Terminology

| Concept | PictureBook | ChapBook | Notes |
|---|---|---|---|
| Landing page | **Picture Books** | **ChapBooks** | the h2 "ChapBook — Poem Library" becomes "ChapBooks"; the poem queue is a section of that page called "Poems" |
| Modes | **Read** / **Edit** | **Read** / **Edit** | ChapBook "Review" (as edit) is retired; **"Review" means only the approval queue** |
| Unit | Scene (list) / Page (reader) | Page | as today |
| Image settings | "Image settings" (book, in Style) / "Image overrides" (per scene or page) | same | replaces "Default image (SD) settings" / "Book image settings" / "Render Settings" / "Image config overrides" |
| Back | "Back to Picture Books" | "Back to ChapBooks" | only the `bookHeader` back arrow |
| Render | "Render" (book), "Render page" / "Re-render page" (one) | same | — |

---

## 7. Create flows

### 7.1 PictureBook

The wizard dialog keeps the two steps that happen **before the book exists**:

1. **Source:** name, auto/manual, chat config, scene count, genre, prompt templates, extract with progress.
2. **Scenes:** the editable scene cards, re-extract, add/remove/reorder.

Step 2's **Continue** already creates the book and its characters (`workflows/pictureBook.js:2018-2111`).
Instead of then showing steps 3–4 in the dialog, it closes the dialog and routes to
**`/picture-book/:id/edit/characters?guided=1`**.

- Guided mode is the same workspace. The only difference is that the footer's Next is the primary button,
  and the rail shows "New book setup" above the sections. Characters → Style → … → Render → Done is the old
  wizard path, and it now also exists after creation.
- The stepper labels (`renderProgressBar`, `:1938`) become "Source › Scenes", followed by "then the book
  workspace".
- The Characters and Style steps no longer exist twice, once in the dialog and once in the workspace.
- The wizard's finish gate (`styleEditorState()` feeding "Continue to Book") becomes the Style section's ⚠
  rail mark plus the `pb2RenderGate` on Edit › Render. These are the same check, in the place where the user
  acts on it.
- The library row's **Edit pencil** routes to `/picture-book/:id/edit`, which opens the next incomplete
  section (§4.3). This fixes the stale "lands on Step 4/5" behaviour (§1.2-5).
- Guided mode starts at Characters, because extraction just created them. From there, Next follows
  `nextSection` (Style, which is required before rendering, comes before anything optional).

### 7.2 ChapBook

- The poem queue and Create live **only** on the library page. They come out of the reader and the editor,
  because `ChapBookConfig` stops being mounted there.
- The design settings in the config panel (filter, max lines, default chat config, default image settings)
  become fields of the Create dialog, because they are inputs to *this* create. For an existing book they
  live in Edit › Book and Edit › Style.
- The Create dialog stays a single async form with in-dialog progress, Stop, and reattach. On success it
  routes to **`/chap-book/edit/:id?guided=1`** instead of the reader. That resolves through `nextSection` (D6),
  which in practice is Pages, because new pages have prompts and no images yet. The post-create "ChapBook
  created" strip on the library becomes unnecessary.

---

## 8. ChapBook — Read and Edit

### 8.1 Read mode — `/chap-book/read/:id`

- **Kept:** `bookHeader` (Read), the ReaderShell body (front pages, cover, series art, page flip, export,
  fullscreen).
- **Removed from the reader:**
  - the `ChapBookConfig` panel, which moves to the library and to Edit › Book / Style;
  - the Review button, replaced by the mode toggle;
  - the chat-config picker and Analyze, which move to Edit › Pages › page (Analyze) and Edit › Book (chat
    config).
- The fullscreen route check that PB1 has (`pictureBook.js:2878`) is added to the ChapBook routes, so the
  shell's overlay covers the chrome consistently.

### 8.2 Edit sections — `/chap-book/edit/:id/:section`

| # | Section | Content (moved from) |
|---|---|---|
| 1 | Book | name, Fixed page height, Max lines, default chat config (session-only, see §9), Delete (from the library row and the config panel) |
| 2 | Style | the book-style bar (Font, Text colour, Bg colour, Bg opacity, Align; `chapBook.js:4748-4867`) + Image settings (`saveBookSdConfig`); one section Save (D4) |
| 3 | Locations | **the same PB Locations section** — the page location picker's empty state ("Add them in the Locations library") becomes a link here |
| 4 | Pages | today's master/detail (`renderReviewBody`), moved into `masterDetail`; the per-card Save becomes the pane Save; "Save all" stays in the section toolbar; Split/Merge/Remove stay; the per-page style overrides stay in the detail; a **Page** tab draws the page as Read does, with ‹ › flipping and a location link (§4.7), as for PB scenes |
| 5 | Cover & Art | the PB Cover section, if it works for `CHAPBOOK` books (§9) |
| 6 | Review | the PB Review section, if it works for `CHAPBOOK` books (§9) |
| 7 | Render | the Render Settings dialog's content inline (chat config, image settings, Render), with the dock showing per-page progress |

ChapBook has no Characters or Scenes sections. Today the PB reader hides Characters for a ChapBook
(`pictureBook.js:2589`); here those sections simply aren't in the rail. (As built, Canvas IS offered — §12.3b.) ChapBook's section list is a
configuration of the same `bookWorkspace`, not a second implementation.

**A ChapBook opened at a PB route:** a CHAPBOOK-typed book that reaches `/picture-book/v2/:id` or
`/picture-book/:id/edit/*` redirects to the matching `/chap-book/...` route once `pb2IsChapBook` resolves.
Today it silently gets a PB reader with the style gate and Characters suppressed
(`pictureBook.js:2143`, `e2e/chapBook.spec.js:683-688`). One book type now has one Ux.

---

## 9. Front-end-only boundary: what must be confirmed, not assumed

Every action above calls an existing client function. These points are **unverified** and must be checked
against the live stack before the dependent slice is built:

1. **Cover & Art and Review for ChapBook books.** The PB `/cover` and `/review` code paths have not been run
   against a `CHAPBOOK` book. `chapBook.js` reads covers (`getCovers`, `coverImageRefFor`), which suggests
   covers exist, but that has not been run either. If either path fails, that section is omitted from
   ChapBook's rail. No backend work is implied.
2. **Story tab editing (D7: editable).** This uses the generic `PATCH /rest/model` on `olio.pb.scene`, the
   same route ChapBook uses for its pages, so it is still front-end only. Two things are unverified:
   - whether a PB user who can read the book is also entitled to PATCH its scene rows. The test runs as
     `ensureSharedTestUser()` on a book that user owns.
   - whether changing `blurb` / `setting` / `action` / `mood` makes the scene read stale.

   Both are measured in U3's Playwright test before the tab ships. If PATCH is denied, the tab stays
   read-only and the denial is reported to Stephen, since that would be a PBAC finding, not a Ux one.
3. **Rail status counts.** Each count needs one existing read per section, made on entering the workspace:
   - Style: the style read (`pb2StyleDescribed`)
   - Characters: the manager's own load
   - Scenes: `bookPages` / `getStale`
   - Review: `getReviewQueue`
   - Book: `bookHealth`
   - Render: `getRun`

   That is up to six GETs per book open. They are cached per book and refreshed after writes in the owning
   section. If the cost is a problem, counts load lazily, showing "nothing" (rule §4.2) until known. A count
   is never guessed.
4. **ChapBook chat config is session state only.** `olio.pb.book` has no `chatConfig` field
   (`workflows/pictureBookWorkflow.js:2460-2466`). Edit › Book shows it with the note "for this session".
   Persisting it would be a backend change and is out of scope.

---

## 10. Rulings (Stephen, 2026-11-19)

| # | Question | Ruling | Where it lands |
|---|---|---|---|
| D1 | Edit mode as a workspace route, or as a full-height dialog? | **Route.** | §3, §4.2 |
| D2 | Keep the Characters pop-in as a shortcut? | **It doesn't need to be kept, as long as Characters is conveniently reached elsewhere.** The pop-in is retired; Characters is a rail section, deep-linkable by character, and linked from every place a character appears. | §4.7, §5.4 |
| D3 | Drop the per-page actions from the reader? | **Yes, in Read mode. In Edit mode it should still be possible to flip through pages and link to characters, scene settings, etc.** | §5.1, §5.6 Page tab, §4.7 |
| D4 | Convert both autosaves to explicit Save? | **Yes, both.** | §5.4, §6.2-7 |
| D5 | Move the PB reader onto `ReaderShell`? | **Yes, if it gives both products a consistent look and feel.** It does: one shell, one `bookHeader`. | §5.1 |
| D6 | Where does Edit open? | **The next incomplete section, or failing that the next most logical one (Style or Scenes).** | §4.3 `nextSection`, §7 |
| D7 | Make Story editable via the generic PATCH? | **Yes.** | §5.6 Story, §9-2 |
| D8 | Redirect old routes permanently? | **Yes, or deprecate / retire them once they are no longer used.** | §3, U6 |

**D8 in practice:** the old routes redirect from U1 onward. At U6, each old route is checked with a grep
across `src/` and `e2e/` for its route string. A route with no remaining caller is removed, and its redirect
entry goes with it. A route that is still referenced (for example, `/picture-book/v2/:id` from
`readerRoute()` and the e2e helpers) stays, and the U6 hand-back names the referencing line. The pop-in
entry point `openCharacterManager({standalone:true})` is retired by the same check.

---

## 11. Implementation slices (each ends green; nothing is DONE until Stephen says so)

| Slice | Content | Proof |
|---|---|---|
| U0 | `bookHeader`, `bookWorkspace`, `masterDetail`, `segmented`, `stateBlock`, `formKit`, `guardDirty`, `bookActivity` store, `editLink`, `nextSection` — components only, no surface uses them yet | Vitest: `ensureSelection`, the `guardDirty` decision table, `nextSection` (each incomplete-section case, plus the Style → Scenes fallback), section-status derivation from fixture data, the route redirect map |
| U1 | PB Edit workspace hosting today's bodies *as they are* (Style, Characters, Locations, Compose, Cover, Review, Canvas, plus a new Book section); old routes redirect; Compose and Cover lose their cross-links; jobs go to the dock | `vite build`, `vitest run`; Playwright as `ensureSharedTestUser()`: every rail section opens, the redirects land, the Back/Next walk |
| U2 | PB Read mode stripped (§5.1); activity pill; "Edit this page"; Edit › Render | Playwright: no edit control is in the reader DOM; starting a render from Edit shows the pill in Read and the page does not move (the bounding box of `pb2-scene-image` is unchanged before and after the run starts) — LLM/SD-touching, so `--workers=1` |
| U3 | Master/detail conversions: Locations, Scenes (list + Page / Story / Composition / Render / Review tabs), Cover, Review; drafts keyed per item; guard; §4.7 cross-links; Characters pop-in retired and stat sliders made drafts (D2, D4) | Playwright: flip pages in the Page tab; a character link lands on that character, a location link on that location; edit a scene, switch scene → guard, Stay keeps the draft, Save persists it (re-read with `cache:false`); **Story PATCH as the shared user, with whether the scene then reads stale recorded (§9-2)**; a slider change is not persisted until Save |
| U4 | ChapBook: Read stripped, Edit workspace (§8.2), `/chap-book/review` redirect, create → guided Edit, Locations shared, render progress in the dock, CHAPBOOK-at-PB-route redirect, book-style bar made an explicit Save (D4), Page tab | migrate the 15 `chapBook*.spec.js` that reference `cb-config-panel` / `/chap-book/review`; `chapBookReviewMasterDetail` behaviour preserved; style bar: a change sends nothing until Save, then N PATCHes, with partial failure reported; §9-1 measured for Cover / Review on a CHAPBOOK book |
| U4b | PB reader onto `ReaderShell` with `bookHeader` + `pageOverlay` (D5) | Playwright: PB and ChapBook Read headers have the same structure (same groups and testids); a run that keeps advancing across a fullscreen toggle; export unchanged (the existing UAT-2 export assertions) |
| U5 | PB create wizard reduced to Source › Scenes → guided workspace; list pencil → `/edit` | Playwright: create from a note as the shared user, land in Edit › Characters guided, Next through to Done → Read |
| U6 | Consistency sweep: widths, `stateBlock` everywhere, `formKit`, Review buttons, the Dialog actions shape, terminology (§6.4); D8 route / entry-point retirement (§10) | `vite build`, `vitest run`, the whole §16 usability gate (`PictureBookV3Implementation.md` §16) re-run, with the walk updated to the new routes; a grep showing each retired route has no caller |

**Test migration.** E2E files that reference the toolbar, the Tools menu or the ChapBook config panel today:

- 6 PictureBook specs (`pictureBookLive`, `pictureBookLocations`, `pictureBookV3PlanInspector`,
  `pictureBookV3StyleEditor`, `pictureBookV3Usability`, `pictureBookV3Ux`) plus the `e2e/helpers/pbReader.js`
  helper;
- 15 ChapBook specs.

That count comes from a grep for the reader and Tools testids and the `/chap-book/review` route string. It is
a floor: specs that navigate by other route strings (`/compose`, `/workflow`) are not counted yet.

Moving the shared navigation into `pbReader.js` (`openEdit(section)`, `openRead()`) first keeps the
per-spec diffs small. Existing `data-testid`s are kept on every element that survives
(`pb2-style-link` → the rail's Style row keeps that testid, and so on), so assertions about content need no
change, only the navigation steps that reach them.

**Not changed by this correction:** any REST call, request body, or server behaviour; the render, compose,
retention and review semantics; the wizard's extraction step; the PB1 legacy viewer.

---

## 12. As built (2026-11-19) — what was implemented, what deviates, what was measured

Nothing below is DONE until Stephen says so. Evidence logs are under `scratch/pb-ux/` (gitignored).

### 12.1 Built

| Slice | As built |
|---|---|
| U0 | `workflows/bookWorkspaceNav.js` (routes, `nextSection` / `nextAfter` / `adjacent`, `legacyRedirect`, `chapBookRouteFor`, `editLink`, `guardDecision`, `ensureSelection`), `components/dirtyGuard.js`, `workflows/bookActivity.js`, `components/bookUi.js` (`segmented`, `stateBlock`, `masterDetail`, `field`, `dirtyMark`, `INPUT` / `CHIP` / `WARN_CHIP`), `components/bookHeader.js`, `components/bookWorkspace.js` |
| U1 | `features/pictureBookEdit.js` (`pbWorkspace`; Book, Scenes, Render sections), `features/pictureBookWorkspace.js` (literal route table + legacy redirects); section adapters `styleSection`, `charactersSection`, `locationsSection`, `coverSection` + `composeApi`, `reviewSection`, `canvasSection` in their own modules; `features.js` `pictureBook` + `pictureBookWorkflow` load the workspace table |
| U2 | Read mode (`renderPb2Header` = Read `bookHeader`; `renderPb2ScenePage(scene, {mode})`; `pb2PublishActivity`; one-line health notice; "Edit this page"); Read ⇄ Edit keeps the place and keeps watching the run |
| U3 | Master/detail Locations, Scenes (Page / Story / Composition / Render / Review tabs), Cover, Review; per-item drafts + guard; §4.7 links where the data carries the id; Characters pop-in entry points routed to Edit; stat sliders are drafts |
| U4 | ChapBook Read (`bookHeader` through the new ReaderShell `renderHeader` slot, no config panel), `features/chapBookWorkspace.js` (book, style, locations, pages, cover, review, render), `/chap-book/review` redirect, create → guided Edit, style bar is an explicit Save, render progress in the dock |
| U5 | Wizard is Source › Scenes; Step 2 Continue → `edit/characters?guided=1`; the library pencil → `/edit` |

### 12.2 Deviations from the sections above (and why)

1. **Story tab edits title / page text (`blurb`) / summary only** (§5.6 said setting / action / mood too). Those three are edited on the composition board, which stores them on `olio.pb.composition` (`settingText`, `action`, `mood`) — the record the compiled plan reads. A second editor over the same-named `olio.pb.scene` fields would edit data the renderer does not use. The Story tab links to the Composition tab for them.
2. **New chapter / Share / Retention stay on the Canvas toolbar**; Edit › Book links there (§5.2 said "moved"). Their dialogs depend on the canvas module's loaded graph state; opening them from Book without it would be fragile.
3. **Rail testids are `ws-rail-<section>`** (§11 said the rail keeps `pb2-style-link` etc.). Specs migrate to the new ids.
4. **Review's rail count is items waiting for a decision (`pendingCount`)**, not every queue node — otherwise a fully approved book would read "incomplete" forever and capture D6's landing.
5. **The PictureBook reader is not yet on `ReaderShell` (U4b, D5).** Both readers share `bookHeader` (same header look and controls); ReaderShell gained the `renderHeader` slot this needs. The PB reader body (cover, series art, dots) is still its own code.
6. **ChapBook judgment calls (ux agent, ⚠):** Filter poems stays beside the poem queue it filters; Max lines is read-only in Edit › Book (changing it re-paginates nothing); book-level "Analyze poems" is on the Pages toolbar; the role-warning banner shows in Edit, not Read; Edit › Render runs the dirty guard before rendering.
7. **Compose drafts:** a per-section Save still re-reads the board and drops the other sections' drafts (pre-existing, pinned by a test); the guard covers the scene on screen — drafts stashed on other scenes are dropped without a prompt on a book change.
8. **Not done:** the D8 retirement sweep (U6) — the old routes all redirect, `openCharacterManager` and wizard steps 3 / 4 are kept and marked `@deprecated`; the full U6 consistency sweep (e.g. Canvas's Chapter / Series toggle is not yet `segmented`).

### 12.3 Measured live (Docker stack `:9443`, shared test user, `e2e/bookModes.spec.js`)

- Read has none of the old edit controls; "Edit this page" lands on `edit/scenes/:scene?tab=page`; every rail section opens; Back / Next walk; Edit → Read keeps the page; the bare `/edit` opened **Style** on the fixture book while it had no style description, and **Scenes** once it had one (D6 behaving as designed); every legacy sub-route redirects; the library's Edit pencil and Characters icon open Edit (no dialog); the Story tab's draft / guard (Stay, Save, Discard) / PATCH body (`userEdited:true`, no `name`) — 9 passed.
- `[SD]` a render started in Edit › Scenes › Render shows in the Read header pill, no run panel is in the Read page flow, and the page image does not move while the run progresses; the render finished without failure — 1 passed (42 s, SwarmUI `.39`).
- **§9-1:** Cover & Art and Review load on a CHAPBOOK book ("N4 Seed ChapBook") with no error state and no 5xx — so both sections are offered on ChapBook.
- **§9-2 (D7 stale) — inconclusive.** The fixture page read STALE both before and after a page-text edit (even right after a fresh render), so this run cannot say whether the edit itself marks the image stale. The Story tab therefore claims nothing: it shows whatever the server reports after the save.
- **Found live and fixed:** a ChapBook already loaded by the reader and then opened on a PictureBook Edit route was not redirected (the check ran only on a full load).

### 12.3a Independent verifier round (2026-11-19) — CHANGES-NEEDED, all fixed

The verifier found that the unit suite never mounted a workspace and let it redraw, and with that:

1. **Request loop (critical)** — the PictureBook workspace header called `ensureBook` on every redraw; a refresh ends in a
   redraw, so `/pages` + `/health` + `/covers` were re-read ~44×/s for as long as Edit was open. Fixed: no load from the
   header; `pb2RefreshPages` is single-flight. Pinned by `bookWorkspaceLifecycle.test.js` (mounts each section, asserts the
   request counts stop growing; mutation-checked: re-adding the call fails 4 tests).
2. **Empty / failed book reloaded on every call** — killed the run being watched on a new STORY book. Fixed: "already
   open" keys on the book whose load finished (`pb2LoadedBookId`), not on `pb2Pages.length`.
3. **ChapBook: Read during an Edit render** wiped the run's progress and re-enabled Render (parallel second render).
   Fixed: `renderRunningFor`; progress is only reset when idle; re-entry keeps Render disabled.
4. **Route changes that bypass `ctx.go` skipped the guard** (browser Back / Forward, plain links, the app menu).
   Fixed: `dirtyGuard` leave guard (popstate revert + an `m.route.set` wrapper installed at module load), scoped to the
   open workspace. Live: `e2e/bookModes.spec.js` "browser Back out of Edit with an unsaved draft asks first".
5. **Section `remove()` never ran on leaving; `init` / `onEnter` never re-ran on return** — fixed (exit detected after the
   route settles, so a remount between route arities inside the workspace is not an exit).
6. **The bare `/edit` entry could pull the user back** after they navigated away — fixed (cancelled on remove, and it
   checks the route is still the entry route).
7. Leaving to the library now stops watching and clears the olio book context (`pb2LeaveBook`).
8. Nits fixed: Story dirty mark on a bare `edit/scenes`; Edit → Read moves the reader page only after the guard;
   `legacyRedirect` keeps the query string; selecting another item **inside a section no longer asks** (drafts are kept
   per item and survive the switch — the row "unsaved" mark shows them); leaving the section or the workspace asks.

Also measured while fixing 4: every Playwright `page.goto` of a hash route adds two history entries in this app (the
library's too) — not caused by this change; specs that test Back enter Edit in-app.

### 12.3b E2E migration round (2026-11-19)

44 existing spec files were migrated to the new UI (helper `e2e/helpers/pbReader.js` keeps its old function names and
navigates through Edit). Every ungated migrated spec was run live as the shared user; SD / LLM-gated legs were migrated
but mostly **not run** (list in the hand-back). Two product defects it surfaced, both fixed and re-run:

1. **Series art on a standalone book** (`edit/cover/series-art`) showed "Select an item." — the list deliberately omits
   the purpose there. `masterDetail` gained `detailFallback`; the board's own "not a chapter of a series" now shows
   without listing the purpose (the e2e pins that a standalone never offers it).
2. **The canvas's node detail panel covered the Canvas toolbar** inside the workspace (it was viewport-fixed at
   top:80px — the pre-existing §6.C 212 n geometry, unavoidable once the canvas sits under the book header). Embedded,
   it is anchored to the canvas area, and sits below the dialog stack (z-index 20, under dialogCore's z-40+) — at z-200 it
   had covered the Retention dialog's own buttons. `pictureBookVariants` 3 / 3 passed after both.

**Reversed (⚠ my judgment): §8.2 "ChapBook has no Canvas".** ChapBooks have a workflow graph (variants, retention, QA
live on it) and were reachable on the canvas before; removing it lost capability, and several specs drive the canvas on
ChapBook fixtures. Canvas is now the "Advanced" section for both products (`CB_SECTIONS`); a ChapBook on a PictureBook
canvas route goes to `/chap-book/edit/:id/canvas`. ChapBook still has no Characters or Scenes section.

Assertions the migration inverted because the design reverses them (each needs Stephen's OK): the ChapBook config
panel is absent from Read / Review; create lands in Edit, not the reader; the library pencil opens Edit; the Read
header has no Characters; Open Full Editor navigates in place; the Read chat-config button counts became the Edit ›
Book control; the render dialog's settings are inline; the Style link became `ws-rail-style` ✓.

### 12.4 Open issues for Stephen

1. **A new STORY book's Edit › Scenes is empty until its first render** — `olio.pb.scene` rows are created by the render path (`PbPipelineUtil.getCreateSceneRow`), so there is nothing to list, compose or edit before then (the reader has the same gap today). The section says so and points at Render. Closing it needs either scene rows at create time (backend) or a Scenes list fed by the extracted scene notes (`/scenes`).
2. **Fixture side effects of the live runs:** the `[SD]` test rendered a new variant on the shared user's "Book pb2-pages-muvp86wc" and, through `ensureBookStyleDescription`, may have given that book a style description; the Story test restores the page text it edits.
3. **Review / Cover / Characters links that the data cannot support:** the review-queue node DTO carries no scene / character / location objectId, so Review's detail has no §4.7 links (a backend DTO addition would enable them).
