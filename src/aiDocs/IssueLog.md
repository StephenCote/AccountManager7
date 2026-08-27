# UAT Issue Log — 2026-08-25

Status key: OPEN | INVESTIGATED | FIXED

---

## Issue 1 — Picker cannot navigate up to parent/group (FIXED)

**Symptom:** Embedded list picker shows no "up" navigation; impossible to pick records in a different path.
**Root cause:** `getOptionButtons` gated navigate-up button on `auth.group`/parent types only. `navigateUp` had no branch for group-contained data types.
**Files:** `src/AccountManagerUx752/src/views/list.js` lines 873, 380
**Fix:** `isGroupContainedPicker` condition in `getOptionButtons`; path-based navigate-up branch in `navigateUp`.
**Status:** FIXED (deployed 2026-08-25)

---

## Issue 2 — Import poems from data.data fails: "Failed to create poem in path ~/Poems" (FIXED)

**Symptom:** Adding a poem from a data.data source fails with the above error.
**Root cause:** `ChapBookUtil.createPoem` used `AccessPoint.create` (full PBAC) on a group created by `makePath` (direct write, no entitlements). PBAC denied DATA-Create on `~/Poems`.
**Files:** `src/AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/picturebook/ChapBookUtil.java` line ~223
**Fix:** Replaced `AccessPoint.create` with `recordUtil.createRecord` (bypass pattern, same as `WorldUtil.getCreateWorld`).
**Status:** FIXED (deployed 2026-08-25)

---

## Issue 3 — text→bigint error blocking UAT (FIXED)

**Symptom:** DB write failures on PictureBook/ChapBook; UAT 100% broken.
**Root cause:** Phase A column-type migration code existed in `DBUtil`/`IOSystem` but `RestServiceEventListener` never read the `database.repairColumnTypes` init-param — flag was always false in Docker.
**Files:** `src/AccountManagerService7/src/main/java/org/cote/rest/listeners/RestServiceEventListener.java`; `src/docker/web.xml.template`
**Fix:** Wired init-param; set `true` in template. DB inspection confirmed columns already correct (prior fix).
**Status:** FIXED (deployed 2026-08-25)

---

## Issue 4 — After adding poems, Ux shows nothing / no way to continue (FIXED)

**Symptom A (UX):** After successful poem import, "Create ChapBook" button stays hidden.
**Root cause:** `selectedIds` never updated after import; button only renders when `selectedIds.size > 0`.
**Files:** `src/AccountManagerUx752/src/features/chapBook.js` line ~297
**Fix:** Auto-add returned poem objectIds to `selectedIds` after `loadPoems()`. Added warn toast for zero-import case.

**Symptom B (Backend):** INSERT fails with invalid byte sequence.
**Root cause:** `ByteModelUtil.getValueString(data)` on DOCX/DOC/RTF returns text with null bytes and C0 control characters that PostgreSQL rejects.
**Files:** `src/AccountManagerService7/src/main/java/org/cote/rest/services/ChapBookService.java`
**Fix:** `sanitizeText()` helper strips null bytes, C0 controls, normalizes CRLF. Applied at extraction boundary for both `data.note` and `data.data` paths.
**Status:** FIXED (deployed 2026-08-25)

---

## Issue 5 — Chat SD config: wrong denoise defaults, slider snaps to 0 (FIXED)

**Symptom:** Denoise label shows wrong fallback (0.65); slider snaps to 0 when field is null; steps fallback wrong (30 vs 20); previously saved 0 permanently overrides server defaults.
**Root cause:** Copy-paste error in `SdConfigPanel.js` label fallbacks; `rangeInput` had no per-field default; localStorage overlay didn't skip `0`.
**Files:** `src/AccountManagerUx752/src/components/SdConfigPanel.js` lines 224, 111, 279; `src/AccountManagerUx752/src/chat/SceneGenerator.js` line ~84
**Fix:** Corrected fallbacks (0.75, 20); added `defaultVal` param to `rangeInput`; overlay now skips `0`.
**Status:** FIXED (deployed 2026-08-25)

---

---

## Issue 6 — ChapBook creation: binary text, blank book, no LLM/SD requests (FIXED)

**Confirmed by UAT testing with nakendang.doc (2026-08-25). The entire end-to-end pipeline is broken.**

### 6A — Binary/garbled text for .doc/.docx/.rtf imports (FIXED — Playwright-verified 2026-08-26)

**Symptom:** Poem text is garbled binary characters — unreadable.
**Root cause:** `ByteModelUtil.getValueString()` does a raw `new String(bytes, UTF_8)`. For `.doc`/`.docx`/`.rtf` binary formats, this produces garbage. `sanitizeText()` strips nulls and C0 control chars but cannot recover text from binary content — most binary bytes decode to valid, non-control UTF-8 and survive the filter.
**Fix:** Content-type-aware extraction — for binary office formats (`application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/rtf`, `text/rtf`) route through Apache Tika (already on the classpath); non-text/* types otherwise rejected with 400. **Extraction lives in Objects7, not Service7** (architect layering finding, resolved 2026-08-26): `ChapBookUtil.extractPoemText(BaseRecord)` + `ChapBookUtil.sanitizeText(String)` own the content-type dispatch, Tika extraction (via new bounded `DocumentUtil.readDocument(byte[], int)` with a finite 16 MB char cap per security review), and Postgres-safe sanitize; `ChapBookService` is now a pure delegate that maps the domain `PictureBookException` → HTTP 400. Architect final verdict: APPROVED.
**Files:** `src/AccountManagerObjects7/.../olio/picturebook/ChapBookUtil.java` (extraction + sanitize); `src/AccountManagerObjects7/.../util/DocumentUtil.java` (bounded readDocument overload); `src/AccountManagerService7/src/main/java/org/cote/rest/services/ChapBookService.java` (delegate only)
**Playwright proof:** `e2e/chapBook.spec.js:734` "6A: binary .docx import extracts readable prose (Tika), not zip garbage" — GREEN 2026-08-26. Uploads real `winter_1.docx` (asserts raw bytes ARE a PK/zip container with no readable prose), imports it, and confirms the persisted poem text = readable English (`"…Outside, all is pristine, From cobalt skies of charcoal…"`), contains "pristine"/"sorcery", no leading "PK", no null byte, >95% printable. Screenshot `test-results/6A-docx-readable.png`.
**Test-correctness fixes made this session (not product changes):** the test now (a) uses a unique per-run poem title — `olio.cb.poem` has a unique constraint on `(name, groupId, organizationId)` so a fixed title made re-runs fail with a duplicate-key INSERT abort; and (b) reads the poem back via targeted `POST /model/search` projecting `text` instead of `GET …/{id}/full`, which 404s on `olio.cb.poem` (same 100-arg limit as Issue 10). Note the duplicate-name import returned an objectId despite the aborted INSERT — a latent silent-write-failure in the import path, logged as a follow-up in Issue 11.
**Note:** `data.note` path is clean — `note.get("text")` returns a proper String. Only `data.data` imports were affected.

### 6B — No LLM request made (Analyze step never fires) (FIXED — Playwright-verified 2026-08-26)

**Symptom:** After creation, no LLM theme/analyze request is made.
**Root cause:** The ChapBook creation flow does not automatically trigger the Analyze step, and the post-create view (PB2 `/v2` viewer) exposed no visible "Analyze" button. Compounded by Issue 8 (the analyze endpoint silently no-oped because poem `text` wasn't projected).
**Fix:** Dedicated ChapBook reader route `#!/chap-book/read/{bookObjectId}` now renders a prominent **Analyze** control (alongside Render). The analyze endpoint itself was fixed under Issue 8 (project `text`).
**Files:** `src/AccountManagerUx752/src/features/chapBook.js` (reader route + Analyze button); `src/AccountManagerService7/src/main/java/org/cote/rest/services/ChapBookService.java` (Issue 8 projection).
**Playwright proof:** `e2e/chapBook.spec.js:854` "6D: reader shows poem stanzas immediately, no Render required" asserts the **Analyze** button is visible (line 924) — GREEN 2026-08-26 (`2 passed`, 10.8s). The analyze LLM path itself is proven by `e2e/chapBook.spec.js:465` (Issue 8, LLM-gated).
**Status:** FIXED — Analyze control present and analyze endpoint functional.

### 6C — No SD request made, no images generated (FIXED — Playwright-verified 2026-08-26)

**Symptom:** No image is generated for any scene.
**Root cause:** Two distinct defects. (1) Render was never triggered — the ChapBook reader route now exposes an explicit Render action calling `POST /olio/chap-book/render/{bookObjectId}`. (2) Even after render produced a `data.data` image, the UI built its `<img src>` as `/rest/resource/data.data/{id}` — a Jersey route that DOES NOT EXIST (live HTTP 404). Image bytes for `data.data` are served ONLY by `MediaServlet` (`/media/{orgDotPath}/data.data{groupPath}/{name}`), path-based on org dotPath + the data record's `groupPath` + name.
**Fix:**
- Backend: `PbServiceFacade.bookPageView` now enriches each page with `imageGroupPath`, `imageName`, `imageContentType` (resolved by loading the `data.data` record through `AccessPoint.find`, projecting `[id,objectId,name,groupId,groupPath,contentType,organizationId]` — `groupPath` is a virtual PathProvider field so `groupId` MUST also be projected).
- UI: `pb2ImageUrl(page)` in `pictureBook.js` and `renderChapBookPage` in `chapBook.js` now build a `MediaServlet` URL (`applicationPath + '/media/' + dotPath(org) + '/data.data' + page.imageGroupPath + '/' + page.imageName`).
**Files:** `src/AccountManagerObjects7/…/picturebook/PbServiceFacade.java` (bookPageView ~612-666); `src/AccountManagerUx752/src/features/pictureBook.js` (pb2ImageUrl ~720); `src/AccountManagerUx752/src/features/chapBook.js` (~104)
**Playwright proof:** `e2e/chapBook.spec.js:506` "POST /olio/chap-book/render generates scene images" — GREEN 2026-08-26 (3.9m, SD-gated). Asserts the rendered `<img>` `naturalWidth>0` (real browser decode) and byte-fetches the exact app-produced `src`, writing a valid **2048×2048 PNG, 6.58 MB** to `test-results/6C-chapbook-render-bytes.bin` (PNG magic confirmed) plus screenshot `test-results/6C-chapbook-render.png`.
**Status:** FIXED — Playwright-verified with on-disk image byte proof.

### 6D — Post-creation view is blank (FIXED — Playwright-verified 2026-08-26)

**Symptom:** After creation, shows a blank book with no text and no images.
**Root cause:** `doCreateChapBook()` navigated to `/picture-book/v2/{id}` — the PB2 workflow viewer — which fetches scenes via `/pages` (no `poemStanza`), falls back to `scene.blurb || scene.summary` (both null on a fresh ChapBook), and shows only `dataObjectId`-based images (null before render). The dedicated ChapBook page-render function had no route.
**Fix:** Added a dedicated ChapBook reader route `#!/chap-book/read/{bookObjectId}` that reads `poemStanza` directly from scenes, paginates (`Page N of M`), and carries Analyze + Render controls — so the book is readable immediately with no render required.
**Files:** `src/AccountManagerUx752/src/features/chapBook.js` (reader route + `renderChapBookPage`).
**Playwright proof:** `e2e/chapBook.spec.js:854` "6D: reader shows poem stanzas immediately, no Render required" — GREEN 2026-08-26 (`2 passed`, 10.8s). Seeds 10 real winter poems, creates the ChapBook, opens `#!/chap-book/read/{oid}`, and asserts (a) a `<p>` matching real seeded poem words is visible with NO render click, (b) a "Page N of M" footer, (c) Analyze + Render buttons present. Screenshot `test-results/6D-reader-stanzas.png`.
**Status:** FIXED — reader shows stanzas immediately with working controls.

**Status (Issue 6 overall):** FIXED — all four sub-issues (6A/6B/6C/6D) Playwright-verified 2026-08-26.

---

## Issue 7 — PictureBook: full E2E audit (VERIFIED)

**Audit 2026-08-26 via `e2e/pictureBook.spec.js` — load-safe suite GREEN `4 passed` + gated render journey GREEN `1 passed`, run as `e2etest_shared` (never admin).**

- **Step 1 — Create PB2 book with universe/world — WORKS.** `POST /rest/olio/picture-book/chapter` created a book whose re-read `olio.pb.book.world` FK is fully populated (`world.groupPath=/Olio/Universes/Books/Worlds`), proving both the "Books" universe and the book's world exist. **Refutes UAT Issue #1 for the `/chapter` creation path.** (`pictureBook.spec.js:123`)
- **Step 3 — Workflow graph — WORKS (lazy).** A fresh book has NO workflow; `GET /{book}/workflow` returns 404 "generate a scene first". `PbBookUtil.createBook` provisions nodes lazily on first scene generation. Test asserts this actual contract. (`pictureBook.spec.js:161`)
- **Step 4 — `/pages` DTO — WORKS.** Well-formed empty array for a scene-less book; DTO carries `sceneIndex/dataObjectId/imageGroupPath/imageName`. (`pictureBook.spec.js:176`)
- **Step 5 — PB2 reader loads in-browser — WORKS.** `#!/picture-book/v2/{oid}` loads without forceLogin bounce, renders honest "No scenes in this book yet." empty state. Screenshot `test-results/pb2-reader-loaded.png` (valid PNG 1280×720). (`pictureBook.spec.js:197`)
- **Step 2-5 — STORY create-from-scenes → SD render → in-browser image byte-proof — WORKS (VERIFIED 2026-08-26).** `e2e/pictureBook.spec.js:246` "full render journey (LLM+SD, gated)" — GREEN `1 passed` (2.6m, serial, `PB_SD_TESTS=1`). Flow: `POST /olio/picture-book/create-from-scenes` (real scene list) → `POST /olio/picture-book/scene/{sceneOid}/generate` fires the 4-stage SD pipeline (LLM .42 for the prompt, SD .39 for the image) → returns a real `imageObjectId`. Byte-proof: `GET /rest/model/data.data/{imageObjectId}/full` resolves `groupPath`+`name`+`contentType=image/png`; the raw bytes fetched from the exact MediaServlet URL a browser uses (`/media/Development/data.data{groupPath}/{name}`) are a valid **PNG 1024×768, 1.28 MB** written to `test-results/pb2-render-bytes.bin` (magic `89504e47` confirmed on disk via `file`). In-browser: the meta viewer route `#!/picture-book/{bookGroupObjectId}` → `pictureBookView` renders the scene `<img src=/media/…>` with `naturalWidth=1024` (real decode); screenshot `test-results/pb2-render.png` (382 KB).
  - **KEY FINDING — PB2 has two structurally distinct "book" concepts.** `create-from-scenes` builds a **group+meta book**: a book GROUP under `~/Data/PictureBooks/{name}` plus a `.pictureBookMeta` file whose `bookObjectId` is that **GROUP's objectId**, NOT an `olio.pb.book` record — and NO Olio world. It is viewed at `#!/picture-book/{bookGroupObjectId}` (`pictureBookView`), and its scene images are served by MediaServlet resolved from each scene's `imageObjectId`. The `/chapter` path (`PbBookUtil.createBook`) instead builds a real `olio.pb.book` record WITH a world/grants. `/pages`, `/books`, `/workflow`, and `#!/picture-book/v2/{oid}` operate **only** on `olio.pb.book` records — so a create-from-scenes book returns `{"error":"Book not found"}` from `/pages` and is absent from `/books`. This is the substance of UAT Issue #1 (below).

**Status:** VERIFIED — create/universe/world/pages/reader confirmed working; STORY create-from-scenes render byte-proof GREEN with on-disk PNG.

---

## Issue 11 — create-from-scenes books are invisible to `/pages` and `/books` (OPEN — UAT Issue #1)

**Symptom:** A book made via `POST /olio/picture-book/create-from-scenes` never appears in `GET /olio/picture-book/books` and `GET /olio/picture-book/{bookObjectId}/pages` returns `{"error":"Book not found"}`, even though its scenes render real images.
**Root cause:** `create-from-scenes` (`PictureBookUtil.createFromScenes`) produces a group+meta book (a directory group under `~/Data/PictureBooks/{name}` + a `.pictureBookMeta` file whose `bookObjectId` is the GROUP objectId), and does **not** create an `olio.pb.book` record or an Olio world. `/pages` → `PbServiceFacade.bookPageView` → `requireBook` → `PbBookUtil.readBook` queries `olio.pb.book` by objectId and 404s; `/books` lists `olio.pb.book` rows only. The scene/render pipeline works entirely off the meta + scene notes + `imageObjectId`, so images are fine; only the workflow-book DTOs are blind to these books.
**Impact:** create-from-scenes books cannot use the `/v2` workflow reader, `/pages`, `/workflow`, or the book list. They are viewable only via the meta viewer `#!/picture-book/{bookGroupObjectId}`.
**Fix direction (not yet done):** either (a) have `create-from-scenes` also provision an `olio.pb.book` + world (unifying on the workflow-book model), or (b) teach `/books`/`/pages` to enumerate/resolve group+meta books. Decision needed on which book model is canonical.
**Status:** OPEN — confirmed by the Issue 7 gated render audit; the render pipeline itself is proven working, this is a DTO/visibility gap.

---

## Issue 10 — `GET /rest/model/olio.pb.book/{oid}/full` returns HTTP 404 (OPEN)

**Symptom:** The by-id `/full` endpoint 404s (Tomcat HTML error page) **specifically for `olio.pb.book`**, while plain by-id GET returns 200 and `olio.world/{oid}/full` returns 200.
**Root cause:** `/full` uses `planMost(true)`, whose recursive field expansion on `olio.pb.book` exceeds PostgreSQL's 100-argument `JSON_BUILD_OBJECT` limit (see memory `feedback-planmost-json-build-100args`).
**Impact (PB2 reader `pictureBook.js loadPb2Pages`):** `am7client.getFull('olio.pb.book', oid)` returns null → (a) reader header stuck on "Loading..." instead of the book title; (b) `am7olio.setCurrentBook(null)`, so downstream game/render REST calls that thread `world.objectId`/`world.basis.objectId` (Phase-1b) get null. Scene display itself is unaffected (uses the separate `bookPages()` endpoint). Minor: created book `name` is `"Book <slug>"`, not the passed `title`.
**Fix direction (not yet done):** reader should use a targeted projection (explicit `request` fields incl. `world.objectId`, `world.basis.objectId`) instead of `getFull` on `olio.pb.book`; do not `planMost(true)` this model.
**Status:** OPEN — reported by the Issue 7 audit; noted, not fixed (out of the immediate image-render scope). Fix requires confirming whether the STORY render path depends on `currentBook` being non-null.

<!-- New issues below this line -->

## Issue 8 — Analyze endpoint silently no-ops: poem `text` not projected (FIXED)

**Symptom:** `POST /olio/chap-book/analyze/{poemObjectId}` returned `success:true` but scene theme/mood were never populated — the LLM was never called.
**Root cause:** `ChapBookService.analyzePoemTheme` fetched the poem with no field projection. `olio.cb.poem`'s model `query` defaults omit `text`, so `poem.get("text")` was `null` → `ChapBookUtil.analyzePoemTheme` logged "poem has no text content — skipping" and returned without calling the LLM, yet the endpoint still reported success. Silent no-op affecting ALL users, not just tests.
**Files:** `src/AccountManagerService7/src/main/java/org/cote/rest/services/ChapBookService.java` (analyze handler)
**Fix:** `q.setRequest(new String[]{ id, objectId, name, groupId, organizationId, "text" })` before the find. Render path checked — no parallel bug (it reads projected scene fields, not `poem.text`).
**Playwright proof:** `e2e/chapBook.spec.js:465` "POST /olio/chap-book/analyze/{poemObjectId} enriches poem metadata" (LLM-gated, requires a user-owned `olio.llm.chatConfig` provisioned by `ensureChatConfig`).
**Status:** FIXED. (Recorded as durable memory `feedback-cb-poem-text-projection`.)

---

## Issue 9 — Broken image URL: `/rest/resource/data.data/{id}` route does not exist (FIXED)

**Symptom:** ChapBook AND PictureBook scene images never displayed even after a successful render; byte-fetch of the image URL returned HTTP 404.
**Root cause:** The UI built image URLs as `/rest/resource/data.data/{objectId}` — there is no Jersey `@Path` "resource" route (confirmed 404 on the live stack). `data.data` image bytes are served exclusively by `MediaServlet` (`/media/*`) / `ThumbnailServlet` (`/thumbnail/*`), which are path-based on the org dotPath + the data record's `groupPath` + name (see `web.xml`; reference pattern `src/components/decorator.js:59-70`, `src/chat/imageTokens.js:78`). A broken `<img>` still has a layout box, so the prior `toBeVisible` assertion passed and masked the bug.
**Fix:** Same as Issue 6C — backend `bookPageView` DTO now carries `imageGroupPath`/`imageName`/`imageContentType`; UI builds a MediaServlet URL via `pb2ImageUrl(page)` (`pictureBook.js`) and in `chapBook.js`.
**Files:** `src/AccountManagerUx752/src/features/pictureBook.js` (~720); `src/AccountManagerUx752/src/features/chapBook.js` (~104); `src/AccountManagerObjects7/…/picturebook/PbServiceFacade.java` (bookPageView).
**Playwright proof:** `e2e/chapBook.spec.js:506` — asserts `naturalWidth>0` and byte-fetches the exact app-produced MediaServlet `src` to a valid on-disk PNG (see Issue 6C).
**Status:** FIXED — Playwright-verified.
