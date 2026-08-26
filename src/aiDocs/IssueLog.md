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

## Issue 6 — ChapBook creation: binary text, blank book, no LLM/SD requests (INVESTIGATED)

**Confirmed by UAT testing with nakendang.doc (2026-08-25). The entire end-to-end pipeline is broken.**

### 6A — Binary/garbled text for .doc/.docx/.rtf imports

**Symptom:** Poem text is garbled binary characters — unreadable.
**Root cause:** `ByteModelUtil.getValueString()` does a raw `new String(bytes, UTF_8)`. For `.doc`/`.docx`/`.rtf` binary formats, this produces garbage. `sanitizeText()` strips nulls and C0 control chars but cannot recover text from binary content — most binary bytes decode to valid, non-control UTF-8 and survive the filter.
**Files:**
- `src/AccountManagerService7/src/main/java/org/cote/rest/services/ChapBookService.java` lines ~392, ~493 — both paths call `sanitizeText(ByteModelUtil.getValueString(data))` with no content-type check
- `src/AccountManagerObjects7/src/main/java/org/cote/accountmanager/util/ByteModelUtil.java` line ~188 — `getValueString` is raw bytes → UTF-8, no format-aware extraction
**Fix needed:** Check `data.contentType` before calling `getValueString`. For binary formats (`application/msword`, `application/vnd.openxmlformats…`, `application/rtf`), either use a format-aware extractor (Apache Tika is already on the classpath) or reject with a 400 explaining only text/plain is supported.
**Note:** `data.note` path is clean — `note.get("text")` returns a proper String. Only `data.data` imports are affected.

### 6B — No LLM request made (Analyze step never fires)

**Symptom:** After creation, no LLM theme/analyze request is made.
**Root cause:** The ChapBook creation flow does not automatically trigger the Analyze step. `doCreateChapBook()` fires `POST /chapbook/create`, then navigates to the PB2 viewer — it never calls `POST /chapbook/analyze/{objectId}`. The analyze call only fires if the user explicitly presses an "Analyze" button, which doesn't exist or isn't visible in the current post-create view.
**Files:** `src/AccountManagerUx752/src/features/chapBook.js` line ~429 (post-create nav, no analyze call); post-create view has no Analyze trigger visible.

### 6C — No SD request made, no images generated

**Symptom:** No image is generated for any scene.
**Root cause:** Same as 6B — the Render step is never triggered automatically. And the navigation after creation goes to `pictureBook.js` PB2 viewer (`/picture-book/v2/{id}`), which:
  1. Only renders images when `scene.dataObjectId` is non-null (populated only after a separate render POST)
  2. Never calls the ChapBook-specific render endpoint
**Files:** `src/AccountManagerUx752/src/features/chapBook.js` line ~429; `src/AccountManagerUx752/src/views/pictureBook.js` line ~786

### 6D — Post-creation view is blank

**Symptom:** After creation, shows a blank book with no text and no images.
**Root cause:** `doCreateChapBook()` navigates to `/picture-book/v2/{id}` — the PB2 workflow viewer. That viewer:
  1. Fetches scenes via the PB2 `/pages` endpoint — which does not return `poemStanza`
  2. Falls back to `scene.blurb || scene.summary`, both null on a fresh ChapBook
  3. Shows `dataObjectId`-based images only — null on a fresh ChapBook
  The dedicated `renderChapBookPage()` function in `chapBook.js` (line ~103) is never called — there is no route that uses it.
**Files:** `src/AccountManagerUx752/src/features/chapBook.js` line ~429; `src/AccountManagerUx752/src/views/pictureBook.js` lines ~765, ~786

**Status:** INVESTIGATED — all four sub-issues confirmed. Awaiting fix authorization.

---

## Issue 7 — PictureBook: untested; assume same category of failure (OPEN)

**Symptom:** User has lost confidence that PictureBook was tested at all, given ChapBook's total failure.
**Root cause:** Unknown — not yet investigated.
**Status:** OPEN — user has not attempted UAT testing; flagged as presumed broken until proven otherwise.

<!-- New issues below this line -->
