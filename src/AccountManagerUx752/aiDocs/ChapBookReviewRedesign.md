# ChapBook Review UI Redesign — Design & Analysis

Status: **DRAFT — design/analysis only, nothing implemented.** Author: orchestrated investigation
(review-UI map, SD-config divergence, poem/scene data model, reader parity), 2026-09-06.

This document turns the eight-point change request into a grounded design. Every current-state claim
below is backed by a file:line reference found by reading the actual code, not assumed.

---

## 1. Goals (from the request)

- **T. Many poems at once, with common controls.** The review screen should edit a whole book of
  poems with a shared control bar, not repeat every control per card.
- **#1/#4. SD-config parity + correct defaults.** The review section's SD form must be the same
  shared component PictureBook/charPerson use, and its default values (scheduler especially) must be
  correct — not cached-wrong.
- **#2. One textarea + one save model.** One larger text area per poem; a single, predictable save
  model instead of the current three (auto-save + a prompt Save button + an SD-config Save button).
- **#3. Font-color selection.** Let the user choose the overlay text color (and actually apply it).
- **#6. List-level delete/merge.** Merge folds the next poem's text up into the current one with a
  `\n` separator and deletes the merged-away one; delete removes a poem outright; the list reindexes.
- **#8. Reader parity.** The ChapBook Read view should behave like PictureBook's — left/right page
  navigation and whole-book export.
- **#7. Real verification.** Every change is exercised by a genuine Playwright E2E against the live
  stack and live LLM/SD — no mocks, no skipped "untestable" paths, visual inspection of generated
  output. (This is a standing gate, folded into §9.)

---

## 2. Current state (evidence)

All frontend paths are under `src/AccountManagerUx752/`. Backend under `src/AccountManagerObjects7/`
and `src/AccountManagerService7/`.

### 2.1 Review/edit screen
- `ChapBookReview` component, `features/chapBook.js:2628-2711`; route `/chap-book/review/:bookObjectId`.
- It **already stacks many poems** — a vertical `space-y-4` of `renderSceneCard(scene, idx)`
  (`:2702-2706`, card body `:2393-2626`). What's missing is a **shared/common control bar**: the only
  book-level control is a header `Render` button (`:2679`). Every editing control is duplicated per card.
- The review edits **scenes** (`olio.pb.scene`), not library poems. Each card binds `scene.poemStanza`,
  `scene.sdPrompt`, `scene.pageFont`, `scene.pageBgColor`, `scene.pageTextAlign`, plus per-scene
  image-config overrides.

### 2.2 Textareas (two per card)
- Stanza text — `:2441-2447`, `rows:6`, `value: scene.poemStanza`.
- Landscape prompt — `:2543-2551`, `rows:3`, `value: scene.sdPrompt`.

### 2.3 Save model — three coexisting mechanisms (the core pain, confirmed)
1. **Auto-save on blur/change** → `doPatchSceneField` (`:2060`) → `PATCH /rest/model`
   `{schema:'olio.pb.scene', objectId, [field]:value}`: title (`:2435`), stanza (`:2446`), font
   (`:2457`), bg-color (`:2473`), align (`:2491`).
2. **"Save prompt" button** (`:2559`) → `PUT /rest/olio/chap-book/scene/{oid}/prompt` `{sdPrompt}`.
3. **Separate SD-config "Save" button** (`:2597`) → `PUT /rest/olio/picture-book/scene/{oid}/config-override`
   `{configOverride:<sparse JSON>}` (+ a "Clear" button).

### 2.4 Font / overlay
- The reader's page renderer `renderChapBookPage` (`:236-260`) **hardcodes** `font-family: Georgia,
  serif` and `color: white` (`:255`) and **ignores** the persisted `pageFont`/`pageBgColor`/
  `pageTextAlign` fields entirely (grep: those fields are read only inside review-edit code).
- The scene model has `pageBgColor` (panel background) but **no text-color field**
  (`models/olio/pb/sceneModel.json:105-107`). Font color is not selectable anywhere.

### 2.5 SD config forms — two of them, failing differently
- **Pre-render dialog** (`renderRenderDialog`, `:699-770`) already mounts the **shared** `SdConfigPanel`
  (`components/SdConfigPanel.js`) at `:744-750`, wired via `am7model.prepareInstance(entity,
  am7model.forms.sdConfig)` (`ensureRenderSdConfig`, `:615-645`) — same wiring PictureBook uses. **This
  one has converged in source.** If bespoke markup appears here, `dist` is stale.
- **Per-scene "Image config overrides"** (review card `:2581-2624`) is the **bespoke** one — it renders
  the generic object-view form `am7model.forms.sdConfigOverrides` via `page.views.object()`
  (`getSceneOverrideInst`, `:2190`), **not** `SdConfigPanel`. This is the "different/custom" SD form in
  the review section.
- **Scheduler default bug (root-caused):** the schema default is `"Karras"` (capital K)
  (`models/olio/sd/configModel.json:77-79`; client mirror `core/modelDef.js:10021`). Every option list
  is lowercase `'karras'` (`SdConfigPanel.js:33-36`; `core/formDef.js:1160,1176,1270,1286`). The select
  helper `selectInput` (`SdConfigPanel.js:77-86`) has **no placeholder and no fallback**, so a value
  that doesn't case-match any option silently renders the **first** option (`'normal'`). Only
  charPerson's reimage repairs it, forcing `scheduler='karras'` (`workflows/reimage.js:134`);
  PictureBook and ChapBook inherit the bug. The **Model** field has the same class of bug
  (`sdXL_v10VAEFix.safetensors` default vs `sdXL_v10VAEFix` option, `formDef.js:1079`).
- **"Cached but wrong" — literally true.** Wrong values persist in (a) a session `_templateCache`
  (`components/sdConfig.js:15`) and (b) a saved `olio.sd.config` record `sdcfg-default` under
  `~/Data/.preferences` (`sdConfig.js:95-143`). Once saved with `"Karras"`/a missing scheduler it
  sticks across reloads.

### 2.6 Poem/scene data model (for delete/merge)
- Library poem `olio.cb.poem` (`models/olio/cb/poemModel.json`) inherits `data.directory`; fields
  `text/title/author/theme/mood/keywords` + a `book` FK. **No order field.** `listPoems` sorts by name
  ASC (`ChapBookUtil.java:918`). Poems are **user-owned** (`createPoem`, `:482-495`).
- The review list is **scenes** (`olio.pb.scene`), which **do** have `sceneIndex` order and carry
  `poemStanza/sdPrompt/imageObjectId/pageFont/pageBgColor/pageTextAlign`
  (`sceneModel.json:80-107`). Scenes belong to the book, live in the book's world/group, and are
  **olio-principal-owned**.
- Split-on-blank-lines lives in `ChapBookUtil.chunkPoem` (`:253-308`,
  `poemText.split("(?m)(^\\s*$\\n?)+")`). Scenes are created **only at book-creation time**
  (`createChapBook`→`createChapBookScene`, `:618-760`) and never re-chunk when text later changes.
- Existing ordering util to model on: `PbBookUtil.reorderScenes` (`:348-385`) — N `sceneIndex` patches,
  each carrying `name`.
- **No scene merge, no scene-list delete/merge endpoint exists.** Poem delete today is the generic
  `DELETE /rest/model/olio.cb.poem/{id}` (works because poems are user-owned). `ChapBookService`
  endpoints: analyze, create, render, scene generate, scene prompt, poems get/post, books, book delete,
  sets — none for scene delete/merge/reorder.

### 2.7 Readers
- PictureBook `pictureBookView` (`features/pictureBook.js:675`) is **one-page-at-a-time**: `currentPage`
  state, `goToPage`, keyboard `onKeyDown` (Arrow/Home/End/Esc, `:316-328`), on-screen chevrons
  (`:584-602`), page dots (`:654-671`), cover, fullscreen. **Whole-book export** exists
  (`exportPictureBook`, `:398-453`) — entirely client-side: fetch each image → base64 → inline into one
  self-contained HTML string with print CSS → download as a Blob. No backend endpoint.
- ChapBook `ChapBookReader` (`chapBook.js:1672-1791`) is a **plain vertical scroll** of all pages
  (`:1777`). No pagination, no keyboard nav, no chevrons/dots, no cover, no fullscreen, **no export**.
- The nav shell and the export engine are generic; the only real coupling is that both readers use
  **module-global state** instead of props, and the per-page renderer differs (portrait+blurb vs
  landscape+stanza-overlay) and image-URL resolution differs.

---

## 3. Proposed design

### 3.1 Common control bar (T) — book-level toolbar over per-scene cards
Add a sticky **book-level toolbar** to `ChapBookReview` holding the controls that are naturally shared
across the whole book, with fan-out across `reviewScenes`:
- **Shared style:** font family, **font color** (see §3.4), background color, text alignment. Changing a
  shared value applies to **all** scenes (book default) and is persisted per scene.
- **Shared SD config:** one `SdConfigPanel` (see §3.3) that sets the book-level image config; "apply to
  all scenes" and per-scene override remain possible.
- **Book actions:** Save all, Render all, Export, Read.

Per-card retains only genuinely per-scene content: the **one** stanza textarea (§3.2), the landscape
prompt, the scene image + regenerate, the per-scene **override** toggle, and the **delete/merge**
controls (§3.5).

Fan-out mechanics: iterate a **PATCH per scene** using the field-name `newInstance`/`copyRecord` idiom
that includes identity **+ the validated `name` field** + changed style field (per `model-api.md`:
scene patches must carry `name` or validation rejects them — `reorderScenes` already does this). A
bulk "apply style to all" is N patches, or a new bulk endpoint (§3.6) if N is large. Do **not** use
batch `update` for heterogeneous dirty sets (batch requires identical field sets across records).

### 3.2 One textarea + one save model (#2)
- Collapse to **one larger stanza textarea** per card (`rows:12`, `resize-y`). The landscape prompt
  stays a distinct field but obeys the same save model.
- **Unify to a single explicit "Save" per card** (recommended — see decision D1). The card tracks a
  dirty set across *all* its fields (stanza, prompt, font, color, align, SD override). One "Save"
  button commits them in one action; the button is disabled when clean and shows one success/failure.
  Remove the on-blur auto-save and the two separate Save buttons.
- Implementation is a **client-side consolidation** — no new endpoints required for the save model
  itself: on Save the card issues the minimal set of existing calls (`PATCH /rest/model` for scene
  fields incl. `name`; `PUT .../prompt` if the prompt changed; `PUT .../config-override` if the
  override changed) and reports a single outcome. A book-level **"Save all"** in the toolbar runs the
  same per-card commit across dirty cards.

### 3.3 SD-config parity + correct defaults (#1/#4)
- **Replace** the per-scene bespoke `forms.sdConfigOverrides` object-view form with the shared
  `SdConfigPanel` (compact mode), so the review section uses the exact component PictureBook/charPerson
  use. Keep the per-scene "override vs inherit book default" semantics.
- **Fix the default at the source** (this is a shared backend fix, not ChapBook-local):
  - `models/olio/sd/configModel.json` — change the `scheduler`/`refinerScheduler` default `"Karras"` →
    `'karras'` to match the option lists; reconcile the `model` default's `.safetensors` extension with
    the option list. Rebuild+install Objects7, redeploy Service7 WAR. Mirror the change in
    `core/modelDef.js`.
  - **Harden `SdConfigPanel.selectInput`** to be case-insensitive and/or prepend a placeholder, so a
    legacy `"Karras"` still highlights (defense-in-depth for records already persisted).
  - After the source is correct, **delete the reimage per-call override** (`reimage.js:134-135`) so
    there is one behavior everywhere.
- **Repair the poisoned cache:** normalize the persisted `sdcfg-default` record and clear/normalize the
  session `_templateCache` on load (read-repair: if a loaded config's scheduler doesn't case-match an
  option, coerce to lowercase before display and re-save). This directly resolves the "cached but wrong"
  symptom.

### 3.4 Font-color selection (#3) — and wire up the ignored style fields
- Add `pageTextColor` (string/hex) to `sceneModel.json`.
- Add a **color picker** to the common toolbar (book default) + optional per-card override, saved via
  the unified save model.
- **Fix the real bug:** make `renderChapBookPage` (`:236-260`) actually **read**
  `pageTextColor`/`pageFont`/`pageTextAlign`/`pageBgColor` instead of hardcoding `color: white` and
  `Georgia, serif`. Today the reader ignores everything the review persists — so this requirement is
  "add color" **plus** "connect the existing font/align/bg fields to the renderer." Fall back to the
  current hardcoded values when a field is unset.

### 3.5 List-level delete/merge (#6) — operates on scenes
Semantics (on the review scene list):
- **Delete(scene)**: delete the scene, then reindex `sceneIndex` on following scenes.
- **Merge(sceneN, sceneN+1)**: `sceneN.poemStanza = sceneN.poemStanza + "\n" + sceneN+1.poemStanza`;
  delete `sceneN+1`; reindex `sceneIndex`. Because `sceneN`'s stanza changed, its **already-generated
  image is now stale** — mark it stale/needs-render rather than silently keeping a mismatched image
  (reuse the existing scene status/stale mechanism if one exists — see open item O1 — otherwise add a
  minimal `imageStale` flag).
- Optionally also merge/clear `sceneN+1`'s `sdPrompt` (drop it; the merged stanza needs a fresh prompt).

Backend (new surface — `@RolesAllowed` + unit tests required):
- New endpoints on `ChapBookService` (transport only): `DELETE .../chap-book/scene/{oid}` and
  `POST .../chap-book/scene/{oid}/merge-up` (merges the next scene into this one).
- Business logic in **Objects7** (`ChapBookUtil`/`PbBookUtil`), not Service7. Because scenes are
  **olio-principal-owned**, the util must resolve the olio principal
  (`Factory.findUser(OlioContext.OLIO_USER_NAME, orgId)`) for the delete/patch, exactly as
  `deleteChapBook`/scene writes already do. Reindex reuses/extends `PbBookUtil.reorderScenes`.

### 3.6 Optional bulk-apply endpoint
If "apply style to all scenes" over large books is too many round-trips, add
`POST .../chap-book/{bookOid}/apply-style` (Objects7 util iterates scene patches server-side, olio
principal, single response). Otherwise iterate PATCH client-side. Recommend starting client-side and
adding the endpoint only if measured latency warrants it (avoid speculative surface).

### 3.7 Reader parity (#8) — shared paginated reader shell
Extract a **shared reader shell** from PictureBook's `pictureBookView`:
- Generic nav core (`currentPage`/`goToPage`/`onKeyDown`/chevrons/dots/fullscreen) and the client-side
  **export engine** become a component parameterized on attrs (not module globals):
  `pages`, `title`, `renderPage(page, i)`, `imageUrlFor(page)`, `renderCover()`, header action slots.
- **PictureBook** uses it with its portrait+blurb page renderer; **ChapBook** uses it with the existing
  landscape+stanza-overlay renderer (`renderChapBookPage`, now style-aware per §3.4). ChapBook gains
  left/right + keyboard nav, dots, cover, fullscreen, and whole-book HTML export for free.
- **Regression risk:** this refactors a *working* PictureBook reader. The verification plan (§9) must
  test **both** readers for parity, not just ChapBook.

---

## 4. Backend change summary

| Change | Module | Notes |
|---|---|---|
| `scheduler`/`refinerScheduler` default `"Karras"`→`'karras'`; reconcile `model` extension | Objects7 `configModel.json` | **Shared** — affects all SD consumers; rebuild+install+redeploy |
| `pageTextColor` field | Objects7 `sceneModel.json` | new field; additive |
| (maybe) `imageStale` flag | Objects7 `sceneModel.json` | only if no existing stale/status field (O1) |
| `DELETE scene`, `POST scene/merge-up` | Service7 `ChapBookService` (transport) + Objects7 `ChapBookUtil`/`PbBookUtil` (logic) | `@RolesAllowed` + unit tests; olio-principal writes; reindex via `reorderScenes` |
| (optional) `POST book/apply-style` | Service7 + Objects7 | only if client-side fan-out is too slow |

Layering: business logic stays in Objects7; Service7 is transport; no ISO involvement; PBAC respected
(scene writes via olio principal, never bypassing `AccessPoint`).

## 5. Frontend change summary
- `chapBook.js`: common toolbar; one stanza textarea; unified per-card + book-level Save; replace
  per-scene override form with `SdConfigPanel`; font-color control; delete/merge controls; reader uses
  shared shell.
- `SdConfigPanel.js`: case-insensitive/placeholder `selectInput`.
- `sdConfig.js`: read-repair of `sdcfg-default`/`_templateCache` scheduler case.
- `reimage.js`: remove the now-redundant scheduler override (after source fix).
- `pictureBook.js`: extract shared reader shell; keep PictureBook behavior identical.
- `renderChapBookPage`: read the persisted style fields.
- `modelDef.js`: mirror the scheduler/model default normalization.

## 6. Data-shape / migration concerns
- **Poisoned `sdcfg-default`:** existing saved records hold `"Karras"`/blank scheduler. Read-repair on
  load (coerce case, re-save) plus the case-insensitive select prevents a hard migration.
- **`pageTextColor` additive:** unset → renderer falls back to white; no migration needed.
- **Scenes are a create-time snapshot** decoupled from library poems (only `scene.title == poem.title`
  links them). Merge/delete operate on scenes; the library poem is untouched. Merging invalidates the
  scene's image — handle via stale flag, don't leave a silent mismatch.
- **No persisted poem order** — irrelevant to the redesign because we operate on `sceneIndex`, which
  *is* ordered.

## 7. Decision points (need sign-off)
- **D1 — Save model:** *Recommend* a single explicit **Save button** per card (+ book-level "Save all")
  — most predictable, directly kills the three-way confusion. Alternative: uniform **debounced
  auto-save** on every field. Tradeoff: auto-save is frictionless but makes heavy SD/prompt edits fire
  implicitly; an explicit button makes deliberate changes deliberate. (You offered either — this picks
  one.)
- **D2 — Common-control scope:** *Recommend* font/color/align/SD as **book-level defaults with
  per-scene override**. Alternative: purely per-scene (no shared defaults).
- **D3 — Merge stale image:** *Recommend* flag the merged scene **stale and require an explicit
  re-render** (avoids surprise SD cost/time). Alternative: auto-regenerate on merge.
- **D4 — SD default fix location:** *Recommend* fix at the **schema source** (`configModel.json`) +
  harden the select. Alternative: client-only override (leaves the schema wrong for other consumers).
- **D5 — Reader:** *Recommend* a **shared reader shell** (true parity, one code path). Alternative:
  copy PictureBook's nav/export into ChapBook (less refactor risk, some duplication).

## 8. Risks / regression surface
- Changing `configModel.json` touches **every** SD consumer (PictureBook, charPerson/reimage, ChapBook)
  — must regression-test all three, and confirm removing the reimage override doesn't reintroduce the
  bug there.
- Extracting the shared reader refactors a working PictureBook reader — parity test both.
- New scene merge/delete endpoints touch olio-principal-owned records — get PBAC right or writes silently
  no-op (return-value must be checked, per `model-api.md`).
- Stale `dist` masks the already-converged render-dialog form — rebuild/redeploy is a precondition, and
  a test must assert the served form is the shared panel.

## 9. Verification plan (#7 — the hard gate)
Every change gets a real Playwright E2E; `ensureSharedTestUser()` (never admin); real poem corpus
(the user's actual documents, not synthetic); visual inspection of generated output.

**Where each spec runs (honest routing — this is how "all services live, no mocks" is satisfied):**
- **Non-generative specs** (form-is-shared, scheduler/model defaults correct + persist, one-textarea +
  single-save persistence, delete/merge persistence + `sceneIndex` reindex + stale flag, reader nav +
  export, font-color applied) run on the **Docker stack** at `https://127.0.0.1:9443` (127.0.0.1 not
  localhost; WS stub via `page.addInitScript` before `goto`; `--workers=1`).
- **Generative specs** (actually render a merged/edited scene image via SD `192.168.1.39`; LLM
  re-analyze via `192.168.1.42`) run against the **host Eclipse Tomcat**, because the Docker bridge
  cannot reach the LAN (per `troubleshooting.md`). These are single-worker and their output is
  **visually inspected** (screenshot the rendered page / generated image).

**Spec list (each asserts real behavior, no `if (present)` hollow guards):**
1. `chapBookSdConfig.spec.js` — open review; assert the per-scene SD form is `SdConfigPanel`; assert
   **scheduler defaults to `karras`** (not empty/`normal`) and Model is populated; change + Save +
   reload → persists; verify a previously-poisoned `sdcfg-default` read-repairs.
2. `chapBookSave.spec.js` — assert exactly **one** save affordance per card; edit stanza/prompt/font/
   color/align, Save once, reload → all persisted; "Save all" commits multiple dirty cards.
3. `chapBookFontColor.spec.js` — pick a text color; open the reader; assert the overlay's **computed
   `color`** equals the chosen value (proves the renderer now reads the field); screenshot.
4. `chapBookMerge.spec.js` — build a book with ≥3 stanzas from a real poem; merge #1+#2 → assert
   `#1.poemStanza` == old#1 + `"\n"` + old#2, #2 gone, `sceneIndex` reindexed, #1 image flagged stale;
   delete a scene → gone + reindex.
5. `chapBookReader.spec.js` — left/right arrow + on-screen chevron + keyboard nav through pages;
   fullscreen; **export** downloads the self-contained HTML; screenshot each page.
6. `pictureBookReaderParity.spec.js` — regression: PictureBook reader still navigates + exports after
   the shared-shell extraction.
7. **(host Tomcat)** `chapBookRenderLive.spec.js` — render a merged scene end-to-end through SD;
   re-analyze a poem through the LLM; visually inspect the produced image/prompt.

Backend: new endpoints covered by JUnit against the live DB (Objects7 `-DskipTests=false`), asserting
merge/delete text + reindex + olio-principal authorization.

## 10. Open items
- **O1 — existing stale/status field?** Confirm whether `olio.pb.scene` already has a status/stale
  field (PB5 workflow work referenced scene statuses) before adding `imageStale`. Reuse if present.
- **O2 — "poem" vs "scene" wording in UI.** The review list is scenes; confirm the user wants
  delete/merge at the **scene** granularity (what the review shows) — this matches the described
  behavior ("poem #1 is just the title") since each card is one stanza/scene.
- **O3 — export format.** PictureBook exports self-contained HTML (print-friendly). Confirm that's the
  desired ChapBook export too, or whether a PDF is wanted (would add a client PDF lib or a backend
  render path — larger scope).
