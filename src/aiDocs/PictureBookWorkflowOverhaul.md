# PictureBook Workflow — Overhaul / Correction

**Raised:** 2026-09-19 · **Status:** proposal, not started · **Trigger:** "the whole workflow part
seems broken … compose an overhaul to address"

> **Design of record remains `PictureBook2Plan.md`** (read Appendix D first). Build state is
> `PictureBook2ImplementationState.md`. This document does **not** supersede either. It records what
> is broken *now*, why, and the order in which to fix it — including the decision to retire
> PictureBook 1.
>
> Everything marked **MEASURED** was observed against the running `am72db` / `am7test-am7-1` stack on
> 2026-09-18/19. Everything marked **INFERRED** is reasoning from code that has not been run.
>
> **Ratified 2026-09-19 (Stephen):** Q6 — one Olio world per **series**, not per book (§6.4).
> Q8 — a chapter is **either** its own document **or** a range within one manuscript; both shapes
> must be supported (§6.5 N3). A novel-length manuscript will be designated for testing when
> implementation starts.

---

## 1. The short version

The workflow graph is not broken in the sense of "defective code". **It is switched off, and has
never run outside tests.** Phases 0–5 are all recorded DONE and verified in
`PictureBook2ImplementationState.md`, but `picturebook.v2=false` in the deployed `web.xml`, so
`PictureBookUtil.generateSceneImage` takes every v2 branch as a no-op and the canvas has nothing to
draw.

That is the whole user-visible symptom. The rest of this document is about the fact that a feature
which is complete, tested, and permanently disabled is not actually finished — and about the cost of
the dual PB1/PB2 code path that the flag exists to preserve.

---

## 2. What is actually broken

### 2.1 The flag is off in the deployed container — MEASURED

```
docker exec am7test-am7-1 grep -A2 picturebook.v2 .../WEB-INF/web.xml
  <param-name>picturebook.v2</param-name>
  <param-value>false</param-value>
```

`PbFeatureFlag.v2Enabled` defaults `false` and is set once at startup. With it off, the single
`isV2Enabled()` guard in `PictureBookUtil` (`:6982`) leaves `pbGraph` null, and the nine downstream
`pbGraph != null` blocks all skip. No workflow, no nodes, no bindings, no artifacts.

### 2.2 There is no graph for any real book — MEASURED

```
a7_olio_pb_workflow_0_1    2     both org 2, both ChapBook TEST books (cbtest145931, cbcancel145931)
a7_olio_pb_node_0_1       38     36 on workflow 1, 2 on workflow 2
a7_olio_pb_binding_0_1     0
a7_olio_pb_artifact_0_1    0
a7_olio_pb_book_0_1        5     incl. id 8 "Book bwo-3"
```

**Zero artifacts and zero bindings have ever been written to this database.** Nodes exist only for
two test books. `BWO 3` — a real book with 41 scenes and 41 rendered composites — has an
`olio.pb.book` row and a `pb2BookObjectId` on its meta, but no workflow at all.

Consequence for the user: opening the workflow canvas on `BWO 3` resolves the PB2 book via the
bridge, finds no workflow, and lands on
`"No PB2 workflow book found for this book. Generate some scenes to create one."` — which they
already did, 41 times.

### 2.3 Artifact/binding recording is therefore unexercised in production — INFERRED

`TestPbGraph` (15) and the level-1/level-2 live runs in the implementation state doc did exercise
recording, so the code is not untested. But nothing has written an artifact against this deployment,
and a path that has never run in its production configuration should be treated as unproven until it
has. **Turning the flag on is a change that needs verification, not a no-op.**

### 2.4 The dual code path is actively causing defects — MEASURED

This is the part that matters more than the flag.

`createFromScenes` exists as **two near-identical ~250-line overloads** — the 8-arg PB1 form
(`:6207`) and the 9-arg PB2 form taking `pb2BookObjectId` (`:6455`). On 2026-09-19 a duplicate-character
fix (`canonicalizeSceneCharacterNames`) was added to the PB1 overload only. **Every book created with
a world takes the PB2 overload**, so the fix was not reaching new books at all. It was found by
accident while investigating an unrelated report, not by a test.

`resolveSceneCharacter` carries the same split on the read side: it probes the legacy
`<book>/Characters` group first and only then the book world's `Population` group. For `BWO 3`,
group 765 (`Characters`) is **empty** and group 727 (`Population`) holds all four characters —
MEASURED. So every character resolution in every scene pays a guaranteed-miss query first. An
earlier incarnation of exactly this split produced the documented "0 portraits generated / refs=0"
failure.

---

## 3. "PB1" and "PB2" mean two different things, and conflating them is the trap

The names cover two independent axes, and the deployed system is currently split across them:

| Axis | What it is | Gated by | `BWO 3` state |
|---|---|---|---|
| **A. Book/world model** | `olio.pb.book`, one Olio world per book, `Population` group, per-book two-tier roles | whether `pb2BookObjectId` is passed to `createFromScenes` | **PB2** — world `bwo-3`, Population 727, meta carries `pb2BookObjectId` |
| **B. Workflow graph** | `olio.pb.workflow` / `node` / `binding` / `artifact`, provenance + dependent regeneration | `PbFeatureFlag` (`picturebook.v2`) | **PB1** — nothing recorded |

So "these are new pbs so should all be v2" is **already true for axis A** and **false for axis B**.
New books get the world, the Population group and the role pair; they do not get the graph.

Any statement of the form "is this book v1 or v2" is ambiguous until it says which axis. The overhaul
should collapse the two axes into one so the question stops being askable.

---

## 4. Should PictureBook 1 be retired? — Yes, as the *outcome*, not the first step

**Agreed on the destination.** The evidence for removing the dual path is concrete, not aesthetic:

- It has already shipped a real defect (§2.4), and the duplication is what hid it.
- The flag's stated purpose is to keep PB1 behaviour byte-identical as a non-regression gate
  (`TestPictureBookCustom#TestPictureBookCustomPipeline` runs with the flag off). A gate that
  guarantees the *disabled* path keeps working, while the *enabled* path never runs, is protecting
  the wrong side.
- Nine `pbGraph != null` blocks plus per-call `try/catch`-and-log are threaded through a 7,300-line
  method. Every one is a branch that production never takes.
- A feature permanently off is not a safety net; it is code that rots. Zero artifacts written in the
  lifetime of this database is what rot looks like before anyone notices.

**But not by deleting PB1 first.** Three things have to be true before the PB1 branches can go:

1. **The graph must actually work on a real book**, verified end to end, not just in `TestPbGraph`.
   Today it has never produced an artifact outside a test.
2. **The non-regression gate must be re-pointed.** Deleting PB1 deletes what
   `TestPictureBookCustomPipeline` asserts. The PB2 path needs equivalent coverage *before* the PB1
   path is removed, or the change is unguarded.
3. **Legacy PB1 books must be decided.** MEASURED: group 443 (`FullPipe 155430`) has a
   `.pictureBookMeta` with **no** `pb2BookObjectId` — a genuine PB1-only book, in this case a test
   artifact. Whether any book worth keeping is in that state is Stephen's call (§8 Q1).

Note the important distinction: **retiring PB1 means deleting the duplicate *write* path, not the
ability to open an existing PB1 book.** The read-side fallbacks (`<book>/Characters` lookup, meta
without `pb2BookObjectId`) are either kept as a narrow compatibility shim or retired by a one-time
migration — see Q1.

---

## 5. The overhaul

Five phases. Each has a gate; none of them is "it compiles".

### Phase W1 — Turn it on and find out what actually happens

Smallest possible step, and the one that converts inference into measurement.

1. Set `picturebook.v2=true` in `docker-compose*.yml` / the `web.xml` template (note
   `docker/entrypoint.sh` regenerates `web.xml` from the template on **every** boot — editing the
   deployed file is discarded; see `.claude/rules/architecture.md`).
2. Render **one** scene of a throwaway book.
3. Inspect what landed: workflow row, node rows, **binding rows, artifact rows**, and the
   `generatorRequest` / `configHash` / `inputHash` on each.

**Gate:** a single scene render produces a workflow with a complete node set *and* non-zero
bindings and artifacts, and the canvas draws it. Until artifacts exist in a real database, nothing
downstream in this plan is verifiable.

**Expect to find defects here.** A path whose production configuration has never executed is not
"verified" by tests that ran under a different one.

### Phase W2 — Make the graph the record, not a side effect

Today the graph is written defensively: every v2 call is wrapped so that "losing provenance must
never lose an image the GPU spent ten minutes producing" (`PbFeatureFlag` javadoc). That was the
right call while it was an optional add-on. Once the graph *is* the model, silent swallowing becomes
the thing that hides breakage — and §2.2 is what that looks like after a year.

- Keep failures non-fatal to the image, but make them **visible**: surface graph-write failures on
  the scene result and the book meta, the way `failedCharacters` / `unverifiedSceneCharacters`
  already are, rather than only in the log.
- Decide whether a node with no artifact is a legitimate state or a bug (the 38 orphan nodes in
  §2.2 suggest the current answer is "nobody knows").

**Gate:** a deliberately-failed artifact write shows up in the API response and the UI, not only in
`catalina.out`.

### Phase W3 — Collapse the two `createFromScenes` overloads

The PB2 overload becomes the only implementation; the 8-arg form becomes a thin delegation that
creates (or resolves) the book/world first. This is what stops §2.4 recurring.

- One body, one place to fix a bug.
- `resolveSceneCharacter` keeps the `<book>/Characters` probe **only** if Q1 says legacy books stay
  readable — and if so, it should try `Population` **first**, since that is where every book created
  in the last several months keeps its characters.

**Gate:** the existing character/extraction test set passes against the single path, and a test
asserts that both entry points produce the same character records for the same input.

### Phase W4 — Retire the flag and the PB1 branches

Only after W1–W3.

- Delete `PbFeatureFlag` and the `isV2Enabled()` guard; keep the nine `pbGraph != null` checks only
  where a null graph is a genuine runtime possibility (e.g. the `~/Chat` single-image fallback,
  which has no book).
- Re-point or replace `TestPictureBookCustomPipeline` so the non-regression gate covers the path
  that actually runs.
- Remove `picturebook.v2` from `web.xml`, the compose files, and the three `resource.properties`.

**Gate:** the Objects7 gate (153 tests per §2 of the implementation state doc) green with no flag in
the tree.

### Phase W5 — Make the canvas reachable and honest

- `pictureBookWorkflow` registers **no menu items** (`features.js:102-106`) — the route exists but
  nothing links to it. Add an entry point from the PictureBook wizard.
- Replace `"No PB2 workflow book found for this book. Generate some scenes to create one."` with
  something true: distinguish "this book predates the workflow graph" from "graph recording was off
  when these scenes were rendered" from "this book has genuinely never been rendered". The current
  message tells a user who rendered 41 scenes to go render some scenes.
- Offer a **backfill** for books in exactly `BWO 3`'s position: a book with images and no graph. A
  backfill cannot reconstruct real provenance (the configs and seeds are gone except what
  `data.data` attributes retained), so it must create `DONE_UNVERIFIED` nodes — the honest status the
  reuse path already uses — and never claim `DONE`.

---

## 6. Long-form works: a novel, a chapter at a time, with a shared cast

**Requirement (2026-09-19):** feed in one chapter at a time, extract its scenes and characters, and
keep building chapter by chapter — with extracted assets, characters above all, shared across the
whole work rather than re-extracted per chapter.

### 6.1 What already exists, and what is only *modelled*

The vocabulary is there. Almost none of it is implemented.

| Thing | State |
|---|---|
| `olio.pb.book.series` (FK), `olio.pb.book.chapter` (int), `olio.pb.book.sourceData` | fields exist |
| `olio.pb.series` — `name`, `description`, **`universe` typed `olio.world`**, `bookCount` | model exists |
| `olio.pb.castGroup` — `book` + `members` (list of `olio.charPerson`) | model exists |
| `POST /chapter` → `PbServiceFacade.createChapter` → `PbBookUtil.createBook` + `PbSharingUtil.copyToChapter` | implemented |

**MEASURED — the series/chapter concept is declared intent, not working code:**

```
a7_olio_pb_series_0_1      0 rows
a7_olio_pb_castgroup_0_1   0 rows
```

No code anywhere writes `series`, `castGroup`, or `chapter`. They appear once, in a projection field
list (`PbBookUtil.java:528-529`), and nowhere else. So `createChapter` today produces an **unlinked
sibling book** — its own world, groups and role pair, plus copies of whatever records you name — and
sets neither `series` nor `chapter`. Nothing afterwards can tell that two books are chapters of one
work.

### 6.2 Extraction is whole-work and non-additive — MEASURED

- `extract` / `extractScenesOnly` take a `workObjectId` and call `extractWorkText(user, work)` on the
  **entire** document, then auto-chunk (2,000-char chunks, 200 overlap) past
  `MAX_EXTRACTION_TEXT_CHARS = 8000`.
- `MAX_SCENES_DEFAULT = 10`, and `extract` clamps the requested count to it.
- `createFromScenes` **creates** a book's scene set. There is no entry point that appends scenes to an
  existing book, and none that takes a chapter, a range, or a second document.

So today a novel is either one 8,000-character-truncated book, or N unrelated books.

### 6.3 The central tension: copy versus shared cast

`PictureBook2Plan.md` §3.5 chose **copy** deliberately, and the reasoning is sound on its own terms:
apparel and wearables hang off a character, so a *shared instance* would make deleting chapter 1
destroy chapter 2's data. `PbSharingUtil.copyToChapter` clones each named record into the target
world and records the lineage as a `chapterSource` binding.

**That decision is what blocks this requirement.** Under copy:

- Chapter 2's "Darby" is a different `charPerson` with a different objectId, her own portrait, her own
  statistics and her own wardrobe. Nothing keeps them in step; editing her in chapter 3 leaves
  chapters 1–2 untouched.
- It multiplies the identity problem rather than containing it. The name-collision defect fixed on
  2026-09-18 involved **one** book with four characters. A ten-chapter novel under copy semantics has
  ten Darbys in ten Population groups, and every cross-chapter question ("is this the same person?")
  becomes a fuzzy match.
- Shared assets are the explicit ask, and copy is the opposite of sharing.

### 6.4 The series owns the world — RATIFIED 2026-09-19

Invert the ownership. Today it is one world per **book**. It is to be one world per **series**, with
each chapter a book that references it.

> **Decision (Stephen, 2026-09-19):** "One world per series seems to make the most sense." This
> supersedes `PictureBook2Plan.md` §3.5's copy-over-reference choice **for the cast**; see the note
> below for why that is a change of owner rather than a reversal of §3.5's reasoning.

The model already anticipates this: **`olio.pb.series.universe` is typed `olio.world`.** Nothing
writes it, but somebody meant it.

- Cast, apparel, colours, narratives and the `Population` group live in the **series** world, shared
  by reference. One Darby, one portrait, one wardrobe, for the whole novel.
- **§3.5's objection dissolves rather than being overruled.** The cast belongs to the series, not to
  chapter 1, so deleting a chapter deletes that chapter's scenes, prompts and images — never the
  cast. The copy-versus-reference argument was about *ownership*, and this changes the owner.
- `castGroup` becomes useful as intended: a named subset of the series cast appearing in a given
  chapter, which is also what a per-chapter `{knownCharacters}` roster should be built from.
- A single-book work is just a one-chapter series, so there is one code path, not two.

**Alternative B, not taken:** keep per-book worlds and add a cross-book character identity/alias
layer. It preserves §3.5 untouched, but it is strictly more machinery and it *keeps* the "which
Darby" problem, permanently, by design. Recorded only as the fallback if series-scoped worlds turn
out to break the PBAC role model (Q7) — which is the one thing that could still force it.

### 6.5 What has to be built

**N1 — make series and chapter real.** Create and populate `olio.pb.series`; set `series` and
`chapter` on every book (`createChapter` currently sets neither); a listing endpoint that returns a
series' chapters in order. Existing standalone books become one-chapter series with no data movement.

**N2 — series-scoped world.** `PbBookUtil.createBook` gains a series-aware path: the first chapter
creates the world under the series, later chapters adopt it. `resolveSceneCharacter`'s Population
lookup resolves the *series* world. Needs the role-model decision in Q7.

**N3 — additive, per-chapter ingest.** The substance of the request.

- An extraction entry that **appends** to an existing series rather than creating a fresh scene set.
  **Both input shapes are required** (Q8, answered 2026-09-19: "a chapter could be both"):
  - *its own `data.data`* — one uploaded document per chapter; `book.sourceData` points at it. This
    is closest to how `extract` already works, which takes a `workObjectId`.
  - *a range within one manuscript* — one `data.data` for the whole novel, each chapter an
    addressable span of it. `book.sourceData` is then the **same** document for every chapter.

  Two consequences follow directly, and both are easy to miss:

  1. **A chapter needs a source *descriptor*, not just a `sourceData` FK.** `olio.pb.book.sourceData`
     covers the document half only; the range half needs somewhere to live (offsets on
     `olio.pb.book`, or a small source-span model). Without it the two shapes cannot coexist on one
     model.
  2. **The extraction checkpoint key must include the range.** It is currently
     `extractTextHash(text)` + chunk size + overlap + total chunks. For a single manuscript split
     into chapters, *every chapter hashes the same document* — so chapters would share one
     checkpoint and a resume would cross-contaminate. This is no longer optional hardening; the
     "both" answer makes it a correctness requirement.
- `createFromScenes` becomes additive (or gains an additive sibling): new scenes take indexes after
  the current maximum, and characters resolve against the **series** `Population` first, creating only
  what is genuinely new.
- **Seed the `{knownCharacters}` roster from the series cast, not just the current run's scene list.**
  This is the single highest-value item for a novel. The roster (added 2026-09-19) exists precisely
  because the model loses track of established names; across chapters it starts empty every time, so
  chapter 3 will invent a second name for someone established in chapter 1 — the same failure that
  put Yolanda into two of Veronique's scenes, but now guaranteed at every chapter boundary.
- `canonicalizeSceneCharacterNames` likewise seeds from the series cast, so a new chapter's spellings
  fold onto the established ones.
- Revisit `MAX_SCENES_DEFAULT = 10` — per chapter or per work (Q9).

**N4 — reading and navigation.** Ordering across chapters is `(book.chapter, scene.sceneIndex)`. The
wizard and reader need a series view, and the workflow canvas must scope to **one chapter** — a
40-chapter novel at 10 scenes each is ~400 scenes and several thousand nodes, which no single canvas
will survive (Q9).

**Do not conflate chapters with chunks.** A *chapter* is semantic and user-addressable; a *chunk* is
the mechanical 2,000-character window `extractChunkedInternal` already slides over the text. A
chapter range is chunked internally exactly as a whole work is today — the chapter is the new unit of
*ingest*, not a replacement for chunking.

**Prior art for splitting a manuscript, and its limits — MEASURED.**
`VectorUtil.chunkByChapter(name, path, block, chunkSize)` already exists and already splits on
chapters. Its entire detection rule is `tmp.startsWith("Chapter ")` on a trimmed line. That will miss
`CHAPTER ONE`, `Chapter I`, `1.`, `PART TWO`, and any centred or styled heading — and it returns
*text chunks*, not offsets, so it cannot produce the addressable ranges the range-input shape needs.
Worth knowing it is there; not worth pretending it is reusable as-is. Who decides the boundaries is
Q11.

### 6.6 Sequencing against the rest of this document

- **N2 and W3 touch the same code** — the two `createFromScenes` overloads and
  `resolveSceneCharacter`'s group resolution. **Do W3 first.** Collapsing to one path before making it
  series-aware is far less work than doing both at once, and W3 exists precisely because duplicated
  paths drift apart silently.
- **N1 depends on W1.** Chapter lineage is already expressed as a `chapterSource` binding in the
  workflow graph, so it is only durable once graph recording is actually on.
- N3 is independently valuable and could ship before N2 if Q6 stalls — but without the series-scoped
  world it delivers per-chapter ingest with *copied* casts, which is half the request.

---

## 7. Things that will bite

- **`web.xml` is regenerated on every boot** from the template via `envsubst`. Editing the deployed
  copy looks like it works until the next restart.
- **Backfill cannot invent provenance.** `BWO 3`'s images carry an `s2i` attribute with the request
  that produced them (MEASURED — that is how the character mix-up in §2.4's sibling investigation
  was diagnosed), but not the node graph. `DONE_UNVERIFIED` exists precisely for this; use it.
- **The 38 orphan nodes** belong to org 2 test books. Decide whether the backfill/cleanup touches
  them or leaves them; do not let them silently become the canvas's first impression.
- **`am72db` must never be reset** (`.claude/rules/llm-conduct.md`). Any migration or backfill has to
  be idempotent and re-runnable, not "drop and rebuild".
- **Turning the flag on changes write volume per scene** — nodes, bindings and artifacts per
  portrait/landscape/reference/composite. Worth measuring once on a 41-scene book before assuming it
  is free.

---

## 8. Open questions for Stephen

- **Q1 — legacy PB1 books.** Group 443 is a PB1-only test book. Are there real PB1 books worth
  keeping readable? If no, W3 deletes the `<book>/Characters` fallback outright. If yes, is a
  one-time migration (create world + move characters) preferable to carrying the shim forever?
- **Q2 — flag default before removal.** Flip `picturebook.v2` to `true` as the shipped default at
  W1, or leave it opt-in until W4 deletes it? Flipping earlier gets real usage sooner; it also makes
  every render start writing graph rows before W2 has made failures visible.
- **Q3 — backfill scope.** Worth building for `BWO 3` specifically, or is it acceptable that books
  rendered before the flag flip simply have no graph and must be re-rendered to get one?
- **Q4 — orphan test graphs.** Delete the two org-2 test workflows and their 38 nodes, or leave them?
- **Q5 — does the canvas earn its place?** 889 lines of Ux with no menu entry, against a feature
  nobody has been able to use. Before W5 invests further: is the graph primarily a *provenance
  record* (queryable, mostly read via API and the node detail panel) or an *interactive editing
  surface*? The answer changes how much of the canvas is worth keeping.

- **Q6 — ownership inversion. ANSWERED 2026-09-19: one world per series.** Recorded in §6.4. The
  remaining work is to reconcile this with `PictureBook2Plan.md` §3.5 in that document, so the two
  do not contradict each other for the next reader.
- **Q7 — role model for a series.** Per-book two-tier roles today. Does a series get its own
  Writer/Admin pair, do chapters keep per-book roles with a series-level Reader, or does chapter
  membership simply follow series membership?
- **Q8 — what is a chapter, as input? ANSWERED 2026-09-19: both.** Its own document *and* a range
  within one manuscript. See §6.5 N3 for the two consequences (a source descriptor rather than a bare
  `sourceData` FK, and a range-aware checkpoint key).
- **Q9 — scale.** `MAX_SCENES_DEFAULT = 10`. Is that per chapter now? A 40-chapter novel at 10 each
  is ~400 scenes and several thousand graph nodes — which also decides whether the workflow canvas
  scopes per chapter (§6.5 N4) or needs replacing.
- **Q10 — existing works.** Do the standalone books already in the database become implicit
  one-chapter series (no data movement), or stay outside the series model entirely?
- **Q11 — who decides chapter boundaries in the single-manuscript case?** (Follow-on from Q8.)
  Manual designation in the Ux, auto-detection of chapter headings, or auto-detect with manual
  override? `VectorUtil.chunkByChapter`'s `startsWith("Chapter ")` is the only existing detector and
  is not sufficient (§6.5). Auto-detect-with-override is the obvious shape, but detection quality on
  a real manuscript is the deciding factor — which the designated test manuscript will settle.

**Test material.** Stephen will designate a novel-length manuscript when implementation starts. Use
it — not a synthetic stand-in — for N3/N4 verification: chapter-boundary detection, cross-chapter
cast sharing, and the scale question in Q9 are all properties of real manuscripts and cannot be
established against fabricated text.

---

## 9. Evidence appendix

Commands re-runnable against the live stack.

```bash
# The flag, as deployed
docker exec am7test-am7-1 sh -lc 'grep -A2 -i picturebook.v2 /opt/tomcat/webapps/*/WEB-INF/web.xml'

# Graph population
for t in a7_olio_pb_workflow_0_1 a7_olio_pb_node_0_1 a7_olio_pb_artifact_0_1 \
         a7_olio_pb_binding_0_1 a7_olio_pb_book_0_1; do
  printf '%-32s ' "$t"
  docker exec am7-pg psql -U am7user -d am72db -t -A -c "select count(*) from $t;"
done

# Which books have a PB2 book row / a pb2BookObjectId on their meta
docker exec am7-pg psql -U am7user -d am72db -c \
  "select n.groupid, (n.text::json->>'pb2BookObjectId') from a7_data_note_0_1 n
   where n.name='.pictureBookMeta';"

# BWO 3: characters live in the world Population group, not the PB1 Characters group
docker exec am7-pg psql -U am7user -d am72db -c \
  "select id,name,groupid from a7_olio_charperson_0_1 where groupid in (727,765);"
```

Key source locations:

| What | Where |
|---|---|
| The flag | `PbFeatureFlag.java` (`CONFIG_KEY = "picturebook.v2"`, default `false`) |
| The only `isV2Enabled()` guard in the pipeline | `PictureBookUtil.java:6982` |
| The nine dependent blocks | `PictureBookUtil.java`, `pbGraph != null` |
| Second guard | `PbPipelineUtil.java:184` |
| PB1 `createFromScenes` | `PictureBookUtil.java:6207` |
| PB2 `createFromScenes` | `PictureBookUtil.java:6455` |
| Legacy-first character lookup | `PictureBookUtil.resolveSceneCharacter` |
| REST (8 endpoints) | `PictureBookService.java` — `/pb2`, `/workflow`, `/workflow/node/{id}`, `/artifact/{id}`, `/stale`, `/node/{id}/regenerate`, `/node/{id}/pin`, `/members` |
| Ux client | `src/workflows/pictureBookWorkflow.js` (168 lines) |
| Ux canvas | `src/features/pictureBookWorkflow.js` (889 lines) |
| Feature registration (no menu item) | `src/features.js:102-106` |
