# PictureBook Series / Chapters (N-series) — Implementation Plan

**Status:** PLAN ONLY — not implemented. Authored 2026-09-20.
**Scope:** The "N-series" of `PictureBookWorkflowOverhaul.md` §6 (N1–N4): make series/chapters
real, share one Olio world per series, additive per-chapter ingest, series reader + chapter-scoped
canvas. The "W-series" (W1–W5, graph recording) is already **done and verified** — this plan does
not touch it except to correct stale references to it.

**Test manuscript:** `src/AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx` — the first 3–4
chapters. A single `.docx` → this is deliberately the **range-within-one-manuscript** input shape
(Q8/Q11), not a set of separate files.

> **How to read this doc.** Every factual claim is cited to current code as `file:line`. Where the
> older design doc (`PictureBookWorkflowOverhaul.md`) and the code disagree, **the code wins** and the
> doc is called stale. This plan was re-derived against the actual post-W-series tree, per Stephen's
> instruction to re-review the design vis-à-vis what is implemented.

---

## 0. Ratified decisions (from Stephen) folded into this plan

| # | Decision (all RATIFIED by Stephen 2026-09-20) | Effect on plan |
|---|---|---|
| Q6 | **One Olio world per _series_, holding a canonical BASELINE cast plus per-chapter SHADOW copies.** The baseline is one shared `charPerson` per character (identity + default look). Each chapter that a character appears in gets a **shadow copy** — a per-chapter instance, tagged by chapter, holding that chapter's render state (apparel/pose/scene). Shadows are **overwritable**: re-rendering a chapter regenerates only its own shadows, so earlier chapters stay re-renderable. **Not** in-place shared mutation (that would clobber prior chapters — the reason Stephen reversed course), and **not** a per-chapter world (all records live in the one series world). | N1/N2: the series owns the world via `olio.pb.series.universe`; every chapter's book points its `world` at that same world. `copyToChapter`/`copyBaselineFieldValues` are **redirected** (not removed): they copy baseline→shadow *within the shared world, tagged by chapter*, instead of copying into a fresh per-chapter world. copy / recopy / merge operate baseline→shadow (Q6-sync, §3 N1). Shadow creation is **lazy** — only for characters present in the chapter. Chapter-delete drops that chapter's shadows + scene/graph records; baseline + other chapters untouched. |
| Q7 | **Chapters use the series Writer/Admin roles DIRECTLY** — no per-chapter role pair. | N1: create one Writer/Admin pair at the series; chapters authorize against it directly. |
| Q8 | A chapter is BOTH (a) its own uploaded document AND (b) a **range within one shared manuscript**, stored as a **dedicated `sourceRange` sub-record** (not bare int columns). | N2: chapter carries a `sourceRange` descriptor; the extraction checkpoint key must be range-aware (today it collides — §3 N2). |
| Q9 | `MAX_SCENES` is per-chapter for now (no UX surface yet; if surfaced later, per-book/extension). Canvas shows **whole series AND can zoom to a single chapter** — two distinct view states. | N4: no new scene-cap field today; canvas gets two view states. |
| Q11 | Chapter boundaries via **auto-detect headings + manual override** in the Ux. | N3: a real boundary→offset detector + an editable Ux review step. |
| Age | **NO age floor in production code.** Stephen explicitly declined it — the age-handling code stays exactly as-is (`if (age > 0) charPerson.set("age", age)`). The toddler render was a **test-content** matter, not a code bug to patch. | N4: **remove** the proposed floor. This is only a **test-design caution** (§4): when choosing/exercising test characters, verify none render as children — do not manufacture a moral problem and imply Stephen requested it. |

---

## 1. Current reality vs. the design doc (what changed under W-series)

`PictureBookWorkflowOverhaul.md`'s "key locations" table (`:466-481`) and several §2/§6 line refs
are now **stale**. Verified corrections (strike these when the overhaul doc is next edited):

| Doc claim (STALE) | Verified current fact (`file:line`) |
|---|---|
| Flag is `PbFeatureFlag.java` (`CONFIG_KEY="picturebook.v2"`), guard at `PictureBookUtil.java:6982` | **DELETED.** No `PbFeatureFlag.java`; `grep PbFeatureFlag\|picturebook.v2` hits **only `.md` docs**, zero Java. Graph recording is now unconditional (W4 done). |
| Two `createFromScenes` overloads at `:6207` and `:6455` | **Collapsed.** One 9-arg impl at `PictureBookUtil.java:6294`; thin 8-arg delegate at `:6278-6283` (passes `pb2BookObjectId=null`); `extract()` calls the 8-arg at `:6261` (W3 done). |
| `resolveSceneCharacter` also probes legacy `<book>/Characters` | **Population-only.** 2-arg at `:2154` → 4-arg at `:2163`; Population lookup `:2184-2199` via `findBookPopulationGroup(...)` `:2189`; legacy probe removed (W3 done). |
| `olio.pictureBookResult` has no graph-failure surface | **Added.** `graphWriteFailures` list<string> at `pictureBookResultModel.json:33-38` (W2 done). |

---

## 2. Exists / modelled-only / missing (the N-series surface)

| Surface | State | Evidence (`file:line`) |
|---|---|---|
| `olio.pb.series` model (`name`, `description`, `bookCount`, `universe`→`olio.world`) | **Modelled-only** | `seriesModel.json:1-38`; registered `OlioModelNames.java:84,124`; schema-tested `TestPbModelSchema.java:57`; **never instantiated** — no `createSeries`/`newInstance(...series)` anywhere. |
| `olio.pb.book.series` FK, `.chapter` int, `.sourceData` FK | **Modelled, never written** | `bookModel.json:40-55`. `createBook` (`PbBookUtil.java:88-179`) and `createChapter` (`PbServiceFacade.java:409-474`) set **none** of them (`grep set("series"\|set("chapter"\|set("sourceData")` → zero hits). |
| One world per **book** | **Exists — REPLACED for chapters by Q6** | `createBook` always mints a new world via `PbOlioContextUtil.getCreateBookContext(...)` (`PbBookUtil.java:133`), then patches `book.world` (`:156-168`). Q6 keeps this only for standalone (no-series) books; every chapter points `book.world` at the one shared `series.universe`. |
| Per-**series** role pair (Q7) | **Missing** | No series role path constants. Q7: build one Writer/Admin pair at the series; chapters authorize against it **directly** (no per-chapter pair). |
| `castGroup` for baseline vs. shadow (Q6) | **Exists — needs both `series` and chapter scoping** | `castGroupModel.json:27-40` (`book` FK, `members` via `pb.castGroup.member`). Q6 needs two membership scopes in the one world: a **baseline** cast keyed to `series` (nullable `series` FK, added) and **per-chapter shadow** casts keyed to `book`/`chapter` (existing `book` FK). Chapter runs resolve against the baseline, then copy→shadow — see N1/N2. |
| `createChapter` REST + Ux | **Exists but incomplete** | `POST /olio/picture-book/chapter` (`PictureBookService.java:1174-1197`) takes only `{fromBookObjectId, slug, title?, copyRecordModel?, copyRecordObjectIds?}`. Ux `createChapter()` (`workflows/pictureBookWorkflow.js:99-110`), `doCreateChapter()` + button (`features/pictureBookWorkflow.js:345,675`) pass only slug+title. Lineage is only a `chapterSource` binding (`PbSharingUtil.java:48`, `PbServiceFacade.java:465`), **not** the `series` FK. |
| Chapter as a **range** in one manuscript (Q8) | **Missing** | No offset/range descriptor; `sourceData` is a bare FK. Q8: add a dedicated `sourceRange` sub-record (new model), not bare int columns. |
| Range-aware extraction checkpoint (Q8) | **Missing / actively broken for chapters** | See §3 N2. |
| Heading→offset detector (Q11) | **Insufficient** | `VectorUtil.chunkByChapter` (`:408-446`) exists but wrong shape — see §3 N3. |
| Age floor to 18 | **NOT to be built** | Stephen declined it. The age code stays as-is; the toddler render is a **test-content** matter. Test-design caution only — see §3 N4 and §4. |

---

## 3. Ordered implementation (N1 → N4)

All backend logic stays in **Objects7** (business logic must not live in Service7 — architecture
rule). Service7 changes are transport only and keep `@RolesAllowed`.

### N1 — Series as a real object + ONE shared world (baseline + per-chapter shadow copies) (Q6, Q7)

**Goal:** an `olio.pb.series` row owning exactly one `olio.world` (`series.universe`,
`seriesModel.json:26-32`). That single world holds a **canonical baseline cast** (one `charPerson`
per character) **and** the **per-chapter shadow copies** derived from it. All chapters share the one
world; no per-chapter world is minted.

1. **New `PbSeriesUtil.getCreateSeries(user, dataPath, seriesSlug, title)`** mirroring
   `PbBookUtil.createBook` (`:88-179`) but creating the world **once** and storing it on
   `series.universe`. Reuse the unique-index serialize discipline (series constraint
   `"name, groupId, organizationId"`, `seriesModel.json:13`). `olio.world.basis` still points at the
   Books universe (unchanged); `series.universe` is the shared *world*, not the universe template.
2. **`PbBookUtil.createBook` grows a series-aware overload:** when a `series` is supplied, do **not**
   call `getCreateBookContext` (no new world) — set `book.world = series.universe`, `book.series` FK,
   and `book.chapter` ordinal. Keep the no-series path unchanged so standalone books still mint their
   own world (no regression).
3. **Q7 roles — chapters use the series roles DIRECTLY.** Add series role path constants + creation
   (mirror `PbOlioContextUtil.java:122,127,213-229`) for `~/Roles/Olio/Series/{slug}/Writer` and
   `.../Admin`. There is **no per-chapter Writer/Admin pair**; every chapter book authorizes against
   the single series pair. (This simplifies `PbOlioContextUtil` for the series path — it no longer
   mints per-book roles when a series is supplied.)
4. **`series.bookCount`** maintained on chapter add (plain int column).

> **Baseline + shadow copies — the cast model (Stephen, 2026-09-20).** The re-render requirement
> settles the earlier flip-flop: a *purely* shared, in-place-mutated cast would let chapter 2 clobber
> chapter 1's apparel/state, making chapter 1 un-re-renderable. So chapters keep **per-chapter copies**
> — but as **shadows of one shared baseline in the single series world**, not a full per-chapter world.
> - **Baseline cast:** one canonical `charPerson` per character, in the series world's Population,
>   grouped by a series-scoped `castGroup`. Shared identity + default appearance. The pull source.
> - **Shadow copy:** for each chapter a character appears in, a per-chapter instance derived from the
>   baseline, tagged by chapter (`book`-scoped `castGroup`), carrying that chapter's render state.
>   Shadows are **overwritable** — re-rendering a chapter regenerates only its own shadows.
> - **Sync ops (Q6, canonical-pull):** **copy** = seed a chapter shadow from baseline on first
>   appearance; **recopy** = overwrite an existing shadow from baseline (discard chapter edits);
>   **merge** = pull baseline updates into a shadow while keeping chapter-specific overrides
>   (apparel/state/pose). Earlier chapters never change unless explicitly recopied/merged.
> - **Reuses existing machinery (do NOT delete it):** `PbSharingUtil.copyToChapter` /
>   `copyBaselineFieldValues` already copy baseline field values — **redirect** them to write shadow
>   instances *within* `series.universe`, tagged by chapter, instead of into a fresh per-chapter world.
> - **Lazy:** a shadow materializes only for characters actually present in the chapter (natural
>   "optional" — you don't pay for absent characters). A global on/off toggle is a later
>   per-extension option, **not** built now.
> - **Delete (§5):** chapter-delete drops that chapter's shadows (chapter-tagged) + its scene/graph
>   records; the baseline and every other chapter's shadows are untouched.

**Model/schema impact:** `series`, `chapter`, `sourceData` are all **nullable, column-backed** and
already declared in `bookModel.json:40-55` (foreign = ID column; `chapter` = int). Per the Path-1
rule (`objects7-reference.md`), a nullable column-backed field needs only a **WAR restart** — no
migration, no `addFieldToSchema`. Verify the columns exist (`\d a7_olio_pb_book_*`); if any is
missing it is an additive nullable `ALTER … ADD COLUMN` on restart. **Never** reset the schema.

### N2 — Chapter linkage + range descriptor (Q8) — the correctness core

**Goal:** a chapter is BOTH its own `data.data` upload AND a range within one shared manuscript;
fix the checkpoint collision.

1. **New `olio.pb.sourceRange` sub-record model (Q8 — dedicated sub-record, not int columns).**
   Fields: `startOffset` (int), `endOffset` (int), `title` (string, the detected/edited heading),
   `sourceData` (`data.data` FK — the manuscript the range is cut from). `olio.pb.book` gets a
   nullable `sourceRange` foreign field pointing at it. This is a proper sub-record so a chapter's
   provenance (which manuscript, which span, what heading) travels as one object and the boundary
   detector (N3) can emit `List<sourceRange>` directly. It also feeds the range-aware checkpoint key
   (item 3) cleanly. **Schema note:** a foreign sub-record FK is a nullable column-backed field on
   `book` → Path-1 restart-only, no migration; the new `olio.pb.sourceRange` model is a **new table**
   created on startup (register in `OlioModelNames`).
2. **Rewrite `PbServiceFacade.createChapter` (`:409-474`)** to accept and persist `seriesObjectId`,
   `chapter` ordinal, `sourceData` FK, and an optional `sourceRange` sub-record. Actually set
   `series`/`chapter`/`sourceData`/`sourceRange` on the new book (today all unset). Route world
   creation through N1 (share `series.universe`; no per-chapter world). `copyRecordModel`/
   `copyRecordObjectIds` are **redirected** from "copy into a fresh chapter world" to "seed this
   chapter's shadow instances from the series baseline, tagged by chapter" (N1 shadow model). Then extend
   **`POST /chapter` (`PictureBookService.java:1174-1197`)** body to carry those fields (transport
   only, keep `@RolesAllowed`):
   `{seriesObjectId?, fromBookObjectId?, slug, title?, chapter?, sourceDataObjectId?,
   sourceRange?:{startOffset,endOffset,title}, copyRecordModel?, copyRecordObjectIds?}`.
3. **Fix the checkpoint-key collision (the Q8 bug).** Cited current state:
   - Checkpoint note **name** = `EXTRACT_PROGRESS_NOTE + "." + workObjectId` (`PictureBookUtil.java:4219`),
     living in the work's group (`findWorkGroupPath`, `:4195-4207`), "deliberately keyed to the source
     document rather than to a book" (`:4195-4197`).
   - Validity guard = `textHash + chunkSize + overlap + totalChunks` only (`ExtractCheckpoint`
     `:4176-4185`; guard `:4315`; `extractTextHash` = digest of the passed text `:4188-4190`). **No
     range component.**
   - **Consequence:** every chapter of one manuscript shares one `workObjectId`, so the note name
     **collides** — chapter 2's `saveExtractCheckpoint` overwrites chapter 1's. Whole-document text →
     identical `textHash` too → resume splices one chapter's scenes into another. Range-only text →
     hash-mismatch discards, but the checkpoint is still clobbered, so resume protection is lost.
     **Either way broken.**
   - **Fix:** make the note **name** and the guard **range-aware** — append
     `startOffset+"-"+endOffset` (or a digest of the range's text) to the note name, and add
     `startOffset`/`endOffset` to `ExtractCheckpoint` + the `:4315` guard.
4. **Seed the cross-chapter character roster.** `knownCharacterNames(scenes)` (`:3989-3994`) is built
   only from the current run's `scenes` — empty every chapter, so "Darby" in ch.1 is re-invented as
   "the girl" in ch.2 (the drift its own javadoc `:3975-3981` describes). Seed the roster passed to
   the extraction prompt (`vars.put("knownCharacters", …)`, `:4626`) from the **series' existing
   Population/cast names**, not just the empty in-run list. Guard names with
   `NarrativeUtil.isMeaningful(String)`.

### N3 — Chapter-heading → offset detection with manual override (Q11)

**Goal:** auto-detect boundaries **as offset ranges**, surfaced editable in the Ux.

Current detector `VectorUtil.chunkByChapter` (`:408-446`) is **insufficient**:
- **Too narrow:** `tmp.startsWith("Chapter ")` only (`:424`, case-sensitive exact prefix). Misses
  `CHAPTER ONE` (all-caps), `PART TWO`, bare roman numerals / `I.` / `ONE`, centred/styled headings.
- **Wrong output shape:** returns re-chunked **vector text chunks** (`List<String>` of serialized
  `MODEL_VECTOR_CHUNK`, sentence-rechunked `:448-462`), not the **character offsets** N2 needs.
- **Coupled to embedding:** exists to feed the vector store — cannot be reused without side effects.

Plan: add a **new** `PbChapterBoundaryUtil.detectBoundaries(String text) → List<{startOffset,
endOffset, title}>` in Objects7 with a broadened, case-insensitive heading regex
(`^\s*(chapter|part)\b`, roman-numeral and word-number forms, tolerant of leading whitespace/centred
headings). Expose via a read-only REST endpoint (transport only, `@RolesAllowed`) returning the
ranges; the Ux presents them as **editable rows (manual override)** before `createChapter` is called
with the chosen offsets. **Leave `VectorUtil.chunkByChapter` untouched** (it serves embeddings).

### N4 — Canvas view states + per-chapter scene cap

1. **Age handling — NO production change. Test-design caution only (Stephen, 2026-09-20).** Stephen
   explicitly declined an age floor: *"not at all — it's just something to factor into your tests so
   you don't create a moral conundrum for yourself and then try to pawn it off on me like I was doing
   something intentionally nefarious."* So the age code stays **exactly as-is** — do **not** add a
   floor, do **not** change the `if (age > 0) charPerson.set("age", age)` guard (`:5577`), do **not**
   touch `parseAgeApprox`.
   - What this means for **testing** (not for code): the BWO character rendered as a toddler because
     `parseAgeApprox` returned 0 (missing/unparseable/literal-"null" age, `PictureBookUtil.java:5140-5150`),
     the `if (age > 0)` guard then left persisted `charPerson.age` at 0, and `getGenderLabel(gender, 0)`
     → "child" (`NarrativeUtil.java:856-864`). That is understood and **is the expected behavior of the
     current code** for a character whose age the manuscript never states.
   - **The test obligation is on the test author, not the product:** when selecting/exercising test
     characters from `HarlotsEight_Vol1_SM.docx`, pick characters whose age is clearly adult in the
     text (or assert on adult characters), and do **not** stage a child-render and then report it as
     though Stephen requested something improper. If a chosen character has no stated age and would
     render as a child, that is a **test-content selection** problem to avoid, not a bug to patch.
   - KI-69 context is retained only as background: it floored the raw-string portrait path
     (`NarrativeUtil.java:2037-2038`), not the persisted int — which is *why* the current code renders
     age-0 as a child. This is documented, not to be "fixed" here.
2. **Per-chapter scene cap (Q9).** `MAX_SCENES_DEFAULT = 10` (`:126`) is applied per extraction call
   (`:222,387`, clamp near `:6250`) — already effectively per-chapter once each chapter is its own
   extraction. **No new field today.** If surfaced later, per-book/extension, not global.
3. **Canvas view states (Q9).** `features/pictureBookWorkflow.js` must support a whole-series view
   AND a zoom-to-single-chapter view as distinct states. Ux752 work: read the existing canvas render
   first (per the "read the reference UI first" rule), `npx vite build` + `npx vitest run`, Playwright
   for behavior.

---

## 4. Verification plan (real tests, live backend — no fakes)

- **Objects7 (JUnit, am7db, `-DskipTests=false` MANDATORY or the run silently no-ops):**
  - Extend `TestExtractCheckpoint` with a **two-ranges-same-document** case proving two independent
    checkpoints (N2/Q8). `mvn -o -pl AccountManagerObjects7 -Dtest=TestExtractCheckpoint -DskipTests=false test`.
  - New `PbSeriesUtil` **one-shared-world** test: create a series, add 2 chapters, assert both books'
    `world` FK is the *same* `series.universe` id (not two different worlds), and each has
    `series`/`chapter`/`sourceData`/`sourceRange` set (N1/N2). Live LLM at `192.168.1.42`
    (`test.llm.ollama.server` in `resource.properties`).
  - **Baseline+shadow cast test (the core Q6 proof):** extract chapter 1, then chapter 2 of the same
    manuscript. Assert (a) a character appearing in both maps to **one baseline `charPerson`** in the
    series world's Population (not two baselines); (b) each chapter has its **own shadow instance** of
    that character (chapter-tagged `castGroup`), so there are exactly two shadows for the two chapters;
    (c) a chapter-2 apparel change writes to **ch.2's shadow only** — re-reading ch.1's shadow still
    shows ch.1's apparel (the re-render guarantee); (d) **recopy** overwrites a shadow from baseline and
    **merge** pulls a baseline change while keeping the chapter override; (e) chapter-delete on ch.1
    drops **ch.1's shadows + scene/graph** only — the baseline and ch.2's shadows remain intact.
    Guard the nested-FK cache-staleness and referenced-field-patch rules (`model-api.md`) when asserting
    apparel on shadows (re-query with `setCache(false)`).
  - Boundary-detector unit test (N3) over the real `.docx` text; assert it emits `sourceRange`
    offset ranges (with titles), not vector text chunks.
- **Real corpus:** first 3–4 chapters of `HarlotsEight_Vol1_SM.docx` — a single `.docx` (range-input
  shape). Use `ensureSharedTestUser()`/non-admin; the admin password ('password') is **only** for
  creating test users. **Never reset the schema** (am7db reset is Stephen's; **am72db must never be
  touched at all**).
- **Test-content caution (age — NOT a code test).** There is no age-floor feature to test. Instead,
  when picking characters to exercise, choose ones whose age is clearly adult in the manuscript so
  nothing renders as a child. Do **not** stage a child-render from an ageless character and then
  present it as a defect Stephen asked for. This is a test-author responsibility, per §3 N4.
- **Service7:** after any model/facade change, rebuild+redeploy the WAR
  (`mvn -o -pl AccountManagerObjects7 install -DskipTests && mvn -o -pl AccountManagerService7 compile`,
  then hot-redeploy: `docker cp` jar/classes into the container + `docker restart`; see
  `PictureBookAsyncJobDesign.md`). New/changed endpoints keep `@RolesAllowed` and need unit coverage.
- **Ux752 (canvas view states, chapter form):** `npx vite build` + `npx vitest run`, then Playwright
  `--workers=1` for LLM/SD paths against the Docker stack; stub the WebSocket and use
  `https://127.0.0.1:9443` (localhost/IPv6 does not resolve to Docker) per `troubleshooting.md`.

---

## 5. Risks & gotchas

- **Redirecting the copy path + chapter-scoped delete is the biggest change (HIGH).** `createChapter`'s
  javadoc ratified COPY-not-reference and per-chapter worlds (`PbServiceFacade.java:406-407`) so that
  deleting chapter 1 couldn't destroy chapter 2's data. Q6 keeps per-chapter copies but as **shadows
  inside the one series world** (N1), so two things must hold together: (a) `copyToChapter`/
  `copyBaselineFieldValues` are **redirected** to write shadow instances tagged by chapter within
  `series.universe` (not into a fresh world) — verify they don't accidentally still create/point at a
  new world; and (b) chapter-delete must remove only that chapter's **shadow** cast/apparel + its own
  scene/graph records (matched by the chapter tag), and must **never** touch the baseline or another
  chapter's shadows. Audit every delete path touching a chapter book (`PbServiceFacade`/`PbBookUtil`
  delete, world-delete `DELETE /rest/olio/world/...`) to confirm it scopes by chapter tag and never
  wipes the shared world. This is the load-bearing correctness item of N1/N2.
- **Shadow overwrite/merge must not clobber other chapters or corrupt the baseline (HIGH).** A returning
  character gets a **per-chapter shadow** derived from the baseline; recopy overwrites that shadow,
  merge pulls baseline updates while keeping chapter overrides. `resolveSceneCharacter` (Population-only
  after W3, `:2154-2199`) must (a) find the **baseline** `charPerson` by name (via the cross-chapter
  roster seed, N2 item 4) and reuse it — never mint a second baseline; and (b) write chapter render
  state (apparel via `MemberUtil`/participation, pose, scene) to **that chapter's shadow only**, so a
  chapter-2 change never mutates chapter-1's shadow or the baseline. Guard the nested-FK cache-staleness
  and referenced-field-patch rules (`model-api.md`) — updating apparel on a shadow must not be verified
  against a stale parent cache.
- **PBAC / groupless reads (MED).** Series/world records are olio-principal-owned — a shared-world
  lookup as the request user returns **null** (looks like "not found", not "denied"). Resolve the
  olio principal first: `Factory.findUser(OlioContext.OLIO_USER_NAME, orgId)` (`troubleshooting.md`).
  List queries over `data.directory`-derived book rows need an explicit `organizationId` (and ideally
  `groupId`) condition or PBAC denies.
- **Checkpoint fix must not orphan notes (MED).** Changing the note-name scheme means legacy
  checkpoints under the old name won't be found (acceptable → re-extraction), but check
  `deleteOrphanedExtractCheckpoints` (`:4379`) so old-format notes don't survive forever.
- **`likeInherits` no-op (LOW, informational).** `series`/`book`/`castGroup` all carry
  `likeInherits:["data.directory"]` which does **nothing** (`feedback-likeInherits-noop`); their real
  parents are `inherits:[common.groupExt, common.baseLight, common.urn]`. Books already create fine,
  so this is a note, not a blocker. The new `olio.pb.sourceRange` model should use real `inherits`.
- **`chapterSource` binding vs. `series` FK (LOW).** Both exist after N2 — retire the binding or keep
  it as provenance → §6.3.

---

## 6. Decisions — all resolved by Stephen (2026-09-20)

Every design question that blocked this plan is now answered. Recorded here so the implementation
conversation does not re-litigate them:

1. **Q6 lifecycle / delete semantics — RESOLVED: one shared world, canonical baseline + per-chapter
   shadow copies.** All assets live in **one** series world (no per-chapter world). Within it: a
   canonical **baseline** cast (one `charPerson` per character, the shared identity/default look) plus
   per-chapter **shadow** copies derived from the baseline and tagged by chapter, holding that chapter's
   render state (apparel/pose/scene). Shadows are **overwritable**, which is what makes re-render work.
   Sync ops (canonical-pull): **copy** = seed a chapter's shadow from baseline; **recopy** = overwrite a
   shadow from baseline; **merge** = pull baseline updates while keeping chapter overrides. Shadow
   creation is **lazy** (only characters present in the chapter). Chapter-delete removes only that
   chapter's shadows + its own scene/graph records; the baseline and other chapters are untouched. The
   existing `copyToChapter`/`copyBaselineFieldValues` machinery is **redirected** (write shadows inside
   the shared world) — **not removed**. This reconciles two of Stephen's constraints: *"I want all the
   assets in the same world — that's the point… no need to keep copying and maintaining n copies"* AND
   *"if characters get overwritten each chapter that means you can't go back to re-render, so I guess it
   does have to be per chapter… create a shadow copy per chapter that can be overwritten."* One world,
   one baseline, cheap overwritable per-chapter shadows.
2. **Age — RESOLVED: no production change.** No floor. Test-design caution only (§3 N4, §4).
3. **`chapterSource` fate — implementer's call (LOW).** Prefer retiring it in favour of the `series`/
   `chapter` FKs; keep it only if a distinct provenance/lineage use survives. Not blocking.
4. **Range descriptor storage — RESOLVED: dedicated `sourceRange` sub-record** (new `olio.pb.sourceRange`
   model), not bare int columns (§3 N2 item 1).
5. **Q7 role mechanics — RESOLVED: chapters use the series Writer/Admin roles DIRECTLY.** No
   per-chapter role pair (§3 N1 item 3).

---

## 7. Key file map (all under `src/`)

- `AccountManagerObjects7/.../olio/picturebook/PictureBookUtil.java` — age handling (NOT a floor —
  leave as-is): age local `:5491`, `if (age>0)` guard `:5577`, `parseAgeApprox` `:5140-5150`;
  checkpoint `:4176-4331` (make range-aware); roster `:3989`/`:4626` (seed cross-chapter);
  `createFromScenes` `:6278`/`:6294`; `resolveSceneCharacter` `:2154-2199` (baseline+shadow);
  constants `:126`.
- `AccountManagerObjects7/.../olio/picturebook/PbBookUtil.java` — `createBook` `:88-179`.
- `AccountManagerObjects7/.../olio/picturebook/PbServiceFacade.java` — `createChapter` `:409-474`.
- `AccountManagerObjects7/.../olio/picturebook/PbOlioContextUtil.java` — role paths `:122,127`;
  creation `:213-229`.
- `AccountManagerObjects7/.../olio/picturebook/PbSharingUtil.java` — `copyToChapter`;
  `ROLE_CHAPTER_SOURCE` `:48`.
- `AccountManagerObjects7/.../olio/NarrativeUtil.java` — `getGenderLabel` `:856-864`; KI-69 `:2037-2038`.
- `AccountManagerObjects7/.../olio/StatisticsUtil.java` — age branches `:98,141`.
- `AccountManagerObjects7/.../util/VectorUtil.java` — `chunkByChapter` `:408-446`.
- `AccountManagerObjects7/src/main/resources/models/olio/pb/{book,series,castGroup}Model.json`;
  `.../olio/pictureBookResultModel.json`.
- `AccountManagerService7/.../rest/services/PictureBookService.java` — `/chapter` `:1174-1197`.
- `AccountManagerUx752/src/workflows/pictureBookWorkflow.js` `:99-110`;
  `AccountManagerUx752/src/features/pictureBookWorkflow.js` `:345,675`.
- Test corpus: `AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx`.
