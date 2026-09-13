# Async background jobs for PictureBook / ChapBook

Status: **implemented and verified live** (2026-09-13). Covers the shared job layer, extraction
checkpoint/resume, and the ChapBook bulk operations.

---

## The defect this removes

`POST /rest/olio/picture-book/{workObjectId}/extract-scenes-only` was **one synchronous request**.
`PictureBookUtil.extractChunkedInternal` chunks the source at 2000 chars (overlap 200) and, as
written, produced no output at all until the last chunk — nothing in the method or the loop wrote to
the database, so 100% of the extracted scenes existed only in the HTTP response body.

Measured against the live `am7test` Docker stack:

| | |
|---|---|
| run started | `16:28:26` |
| nginx returned `504` | `16:43:26` — **exactly 900s**, the then-current `proxy_read_timeout` |
| server logged `Chunk 11/17 processed` | `16:47:10` — four minutes after the client gave up |
| server still calling the LLM | `16:52:24` |
| real duration | 17 chunks × ~80–110s ≈ **27 minutes** |

The 504 is nginx's **own synthesized response**: it hit its read timeout, answered the client itself
and closed the connection, while Tomcat carried on working. Nothing threw, so nothing was logged —
the "inexplicable error with no server-side trace" was the proxy. All ~27 minutes of LLM work was
discarded, and there was nothing to resume from.

This also explains **KI-49** (`Content-Length header of network response exceeds response Body`),
open since 2026-08-09 and never reproduced: same endpoint, same "end of extraction" timing, and a
proxy abort mid-response produces exactly that browser-visible shape. KI-49's surviving candidate was
"an exception thrown after the headers were committed" — right about the shape, wrong about the
agent, which is why a month of looking for a stack trace found nothing. See KnownIssues KI-49 for the
full closure, including what was *not* reproduced.

### Two independent failure modes, two independent fixes

They are easy to conflate and neither fix covers the other:

| Failure | Fix |
|---|---|
| **The caller's connection dies** (proxy timeout, reload, navigate-away) while the server keeps working | The job layer: the result is retained in memory for a TTL and collected by polling |
| **The server process dies** (container restart, redeploy, crash) mid-run | Incremental persistence: partial scenes are checkpointed to the DB and the next run resumes |

---

## The shared job layer

Three new classes in `org.cote.accountmanager.thread` (Objects7):

- **`AsyncJob`** — identity, owner, live progress, terminal status, retained result. Progress is the
  existing `SummarizeProgress` rather than new fields: it already carries
  phase/current/total/elapsed/cancelled and is already what the chunk loop updates.
- **`AsyncJobWork`** — the unit of work; returns its finished payload as a JSON string, so the
  registry needs no knowledge of what any feature produces.
- **`AsyncJobRegistry`** — principal-scoped registry plus a bounded executor.

`JobService` (Service7) is the transport: `GET /rest/job/{jobId}`,
`POST /rest/job/{jobId}/cancel`, `GET /rest/job`. One controller for both features, because a jobId
is globally unique and ownership-scoped.

### Design points that are load-bearing, not preferences

**Ownership keying.** The composite key is `<principal objectId> + ' ' + <jobId>`, inherited
deliberately from `PictureBookCancelRegistry` — read that class's javadoc before changing anything
here, because it documents a real security defect (an earlier version keyed on a client-supplied id
with the principal discarded, letting any authenticated user cancel anyone's extraction). A poll or
cancel by a non-owner simply misses the map, so **an unknown jobId and another user's jobId are
indistinguishable** and these endpoints cannot be used to probe what others are running.

**A server-generated jobId, not the work/book id.** The old registry keyed on the client-supplied
work/book objectId, so `extract-scenes-only` and `prepare-images` shared one flat key space and a
user running two phases at once silently clobbered one token with the other.

**`MAX_CONCURRENT_JOBS = 2`.** Matches `ChatUtil.DEFAULT_SUMMARY_WORKERS` and its stated reason
("Ollama processes requests sequentially on a single GPU, so more than 2 concurrent calls causes
queue-induced timeouts"); the Spark is known to fall over under sustained load. Making this work
async makes it trivial to launch several 27-minute jobs, so the cap is a correctness constraint.
Jobs beyond it sit visibly `QUEUED`.

**Not the ForkJoin common pool.** These tasks block for tens of minutes on outbound HTTP and would
starve `ChatUtil.mapSummarize`, keyframe and memory work; the common pool also offers no way to bound
them.

**The pool is lazily recreatable.** It was originally `static final`, which made `shutdown()`
permanent for the classloader's life: after one `IOSystem.close()` every later `submit` threw
`RejectedExecutionException`. That breaks a Tomcat context redeploy in the same JVM, not just tests.
`IOSystem.close()` calls `AsyncJobRegistry.shutdown()`.

**Result retention is the point.** `COMPLETED_TTL_MS` is 30 minutes, with `MAX_RETAINED_JOBS = 200`
and a lazy sweep. Without retention, async merely moves *where* the work is lost.

**Polling is the source of truth; WebSocket push is an accelerator only.** WS does work for a real
browser in Docker (verified: `WebSocketService - Opened socket` / `Register user by principal`), but
`chirpUser` **silently drops** when no socket is live, `urnToSession` holds one session per URN (a
second tab evicts the first), and every Playwright spec stubs `window.WebSocket`. A result that is
only pushed is a result that can be lost.

### Starting a job

`async=true` is a **query parameter, not a body field**, on three routes:

```
POST /rest/olio/picture-book/{workObjectId}/extract-scenes-only?async=true[&fresh=true]
POST /rest/olio/chap-book/create?async=true
POST /rest/olio/chap-book/render/{bookObjectId}?async=true
```

Each returns `202 {"jobId":"...","status":"..."}`.

A query param because `RecordFactory.getSchema` reads the **persisted** `ModelSchema` from
`a7_system_modelschema_0_1` first and only falls back to the resource when the DB has no row — so
adding `async` to `olio.pictureBookRequest` would have had **no runtime effect** on an
already-provisioned deployment. A query param needs no schema and is trivially exercisable with
curl. (See `.claude/rules/objects7-reference.md`.)

Opt-in rather than auto-switching on text length: `extract-scenes-only` *already* returns two shapes
depending on whether the text chunked (a bare array vs `{sceneList, ...}`), which the client
special-cases, and making the shape depend on a second hidden condition is how that became confusing
in the first place. **The synchronous path is unchanged**, so existing callers and specs keep working.

---

## Extraction checkpoint + resume

A chunked extraction writes its accumulated scenes to a scratch `data.note` named
`.pbExtractProgress.<workObjectId>` in the **source document's own group** — mirroring the existing
`.pictureBookMeta` convention (`PictureBookUtil.saveMeta`), for the same reason: `data.note.text` has
no length limit. The checkpoint is keyed to the document because at extraction time **no book exists
yet** (the book is created later, by `create-from-scenes`).

Written every `EXTRACT_CHECKPOINT_EVERY = 2` chunks and always on the final chunk.

### Writes go through `AccessPoint`, not `io.Queue`

The original plan called for `Queue.queueUpdate` + `Queue.processQueue(user)`. **That would have been
a defect.** `io.Queue` holds ONE process-global `static Map`, and `processQueue(user)` flushes
*everything* anyone has queued as `user` — so with two concurrent jobs (plus the Olio
action/simulation subsystem, its only other caller) job A's flush would write job B's records under
A's principal. That is the `architecture.md` "per-org config must never be written to process-global
state" hazard in a different costume. The checkpoint is written with `AccessPoint.update`/`create`
exactly as `saveMeta` already does: PBAC-safe, not hand-rolled, no shared state. One write per
checkpoint is negligible against an 80–110s LLM call.

### The validity guard

A checkpoint is only resumable against **byte-identical source text chunked identically**. It stores
`textHash` (`CryptoUtil.getDigestAsString`), `chunkSize`, `overlap` and `totalChunks`, and
`loadExtractCheckpoint` returns null — meaning "start over" — on any mismatch, on a
`chunksProcessed` outside `1..totalChunks`, or on unparseable JSON. Chunk index *n* only denotes a
passage relative to a specific text; resuming across a changed document would splice scenes from one
document into a run over another.

### `sourceText` is stripped and rehydrated

Each scene carries a transient ~2000-char `sourceText` (the passage it came from), needed later by
`createFromScenes` for its per-character reduce. Persisting it would grow the checkpoint by the size
of the whole document as scenes accumulate, so the checkpoint stores the integer `sourceChunk`
instead and rehydrates `sourceText` from the identical chunk list on resume. `sourceChunk` is
stripped again in `createSceneNote`, alongside `sourceText`.

### Lifecycle — the part that is easy to get wrong

The checkpoint's fate is decided **inside `extractChunkedInternal`**, where "did this run reach the
end of the document" is actually known:

| Outcome | Checkpoint |
|---|---|
| loop reached the end | **deleted** |
| cancelled | **kept** — re-drive continues |
| thread interrupted (shutdown/redeploy) | **kept** — re-drive continues |
| LLM unreachable (circuit breaker) | **kept** — re-drive continues |

Per-chunk *parse* failures do **not** block the delete: they are already surfaced to the client in
`failedExtractions`, the run genuinely reached the end, and `chunksProcessed` has advanced past them,
so keeping the checkpoint would leave a record nothing could ever consume or clear.

> **This was a real defect, introduced and then caught by the restart test.** The first version put
> the decision in the callers, where a clean finish, a cancel and an interrupt are
> indistinguishable — all three just return a scene list. On `docker restart`, `shutdownNow()`
> interrupted the worker mid-LLM-call, the remaining chunks "failed" instantly against an unreachable
> server, the loop sailed to the end, and the run **deleted its own checkpoint** and reported
> `COMPLETED elapsed=61s` after 2 of 5 chunks — destroying exactly the work the checkpoint exists to
> protect. Two guards fix it: a `Thread.currentThread().isInterrupted()` check at the top of the
> chunk loop, and a circuit breaker that stops after **2 consecutive empty LLM responses** (which
> does not depend on the interrupt flag surviving whatever caught `InterruptedException` down in the
> HTTP stack).

`?fresh=true` discards a checkpoint on demand — the escape hatch for a user who cancelled a run
because its output was wrong and wants a clean one rather than a continuation.

---

## ChapBook

`createChapBook` and `renderChapBookSummary` gained optional `SummarizeProgress` overloads
(progress + cooperative cancellation), and `ChapBookService` gained `?async=true` on `/create` and
`/render/{bookObjectId}`. ChapBook previously had **no** cancel or progress wiring at all.

Cancelling is safe here for the same reason a 504 was survivable: **each scene is persisted as it is
created** (`ChapBookUtil` ~`:784`, `:817`), so stopping early leaves a real book with fewer scenes.
Progress counts stanza chunks, whose total is computed by a pre-pass that loads each poem once —
that also removed a duplicate `loadPoem` per poem. The bulk render counts scenes **attempted**, not
rendered, because counting only rendered ones made the bar stall on a book with skipped scenes and
never reach its total, which reads as a hung job.

Two smaller corrections in the same area:

- The "BLANK book" 500 guard now requires `!stopped`. A run cancelled before its first scene
  legitimately has zero scenes, and reporting that as the "residual artifacts collided on the unique
  (name, groupId, organizationId) index" failure would misdescribe the user's own cancel.
- `createChapBook` reports a `preparing book world` phase **before** `PbBookUtil.createBook`, because
  on the first chapbook in an organization that call alone runs for minutes while Olio seed data
  loads, and the scene total is not knowable until after it.

### `PbRunStatusEnumType.CANCELLED` — added, but RESERVED AND UNWRITTEN

`CANCELLED` now exists and `closeRun(SceneGraph, PbRunStatusEnumType, String)` can record it — but
**nothing sets it yet**, and the enum's javadoc says so explicitly. Do not read its presence as
evidence that a cancelled run is recorded anywhere.

The original omission was argued as "runs are synchronous and there is no cancel endpoint, so a
value nothing can set would be a false affordance". Half that premise is now gone — the bulk
operations are cancellable — but the other half still holds for the run graph specifically: the only
`closeRun` call sites are in `PictureBookUtil`'s single-scene image generation
(`PictureBookUtil.java:5618`, `:5621`), and the ChapBook bulk paths never create an `olio.pb.run`
row at all. So no cancellable loop currently owns a run to stamp.

Wiring it means calling `closeRun(graph, PbRunStatusEnumType.CANCELLED, ...)` from a cancellable
path that owns a run. That is deliberately left undone rather than faked.

The value is validated against the Java `baseClass` declared in `runModel.json`, **not** against a
list in the JSON — so adding it to the enum is sufficient and needs no model-schema change (which on
a provisioned deployment would have had no runtime effect anyway).

`PbPipelineUtil` still swallows graph-write failures deliberately: the graph row is bookkeeping
alongside the authoritative PB1 records, and losing a status stamp must not fail an operation whose
real output already persisted. The consequence is that **a run row can be left `RUNNING` if the write
fails, so it is not a reliable liveness signal** — `GET /rest/job` is.

---

## Client (Ux752)

`workflows/sceneExtractor.js` owns the shared job client, used by both features:
`startExtractScenes`, `pollJob`, `cancelJob`, `listJobs`, `getJob`, `scenesFromResult`.

**`pollJob` is bounded by a deadline, not a poll count.** The summarize poller it is modelled on
(`chat/ContextPanel.js startSummarizePoller`) caps at a fixed number of ticks, which is fine for a
short summarize and meaningless here — 27 minutes at 3s is 540 polls, so a count cap either aborts
healthy runs or means nothing.

**Aborting the poll does not cancel the job**, deliberately. `signal` stops *watching*; `cancelJob`
stops *working*. Navigating away should leave a run going (its result stays collectable); an explicit
Cancel should not. A deadline overrun likewise never cancels — giving up on watching is not a reason
to destroy the work.

**401/403 raises a distinct `JobAuthError` and calls `am7client.forceLogin()`.** These workflow files
talk to the server with a bare `fetch(..., {credentials:'include'})` rather than through `am7client`,
so a session expiring during a 27-minute run previously surfaced as an opaque status code with no
re-login.

`workflows/pictureBook.js`:
- `doExtract()` starts a job and polls it; renders real `current/total` with a progress bar.
- **A Cancel during extraction now exists.** Previously the wizard's only Cancel was
  `Dialog.close()`, which abandoned the fetch with the server still working and left `extracting`
  stuck true — the wizard could not be used again without a reload. There are now two distinct
  actions: *Stop extraction* (cancels the job) and *Close* (stops watching, run continues).
- `reattachExtractJob()` on open finds a running `pb.extractScenes` job for this document via
  `GET /rest/job` and reattaches, instead of starting a second run against the same source.
- Partial results are labelled: a cancelled or early-stopped extraction shows an "incomplete" banner
  with a *Start over* (`fresh=true`) action, and `failedExtractions` is surfaced as its own banner.
  Both were previously invisible — a cancelled run's partial list looked identical to a finished one,
  and the service dropped `failedExtractions` before it ever reached the client.

**The 90s `bgActivity` expiry is fixed from the poller.** `LLMConnector.setBgActivity` arms a 90s
self-clearing timer and **restarts it on every call**, so refreshing it each poll tick keeps it alive
exactly as long as the job. No change to `LLMConnector` was needed. Previously a chunk taking
80–110s outlived the timer and the indicator died mid-run on a healthy extraction.

`features/chapBook.js` gets the same treatment for creation (`startCreateChapBook`,
`onCreateChapBookProgress`, `cancelCreateChapBook`, `reattachCreateChapBook`) **and renders
`bgActivity` for the first time** — it never did, so every progress chirp the server already sent for
ChapBook operations was dropped on the floor. Reattach keys on the slug, which also avoids the
same-slug collision that produces the "BLANK book" failure.

> The bulk **render** client function (`renderChapBook`) is defined but never called: the client
> already renders scene-by-scene via `renderChapBookScenes`, which has real per-scene progress. The
> server-side `?async=true` support exists for API and script callers; no client poller was added for
> it, on purpose.

---

## Verification

**Unit — Objects7 (JUnit, live `am7db`).** `-DskipTests=false` is mandatory or the run executes
nothing and still prints `BUILD SUCCESS`.

```
cd src
mvn -o -pl AccountManagerObjects7 -Dtest="TestExtractCheckpoint,TestAsyncJobRegistry,TestLlmJsonSalvage,TestPictureBookSceneAuthz,TestPictureBookSourceTextField" -DskipTests=false test
```

- `TestExtractCheckpoint` (11) — round-trip and resume index; rejection on changed text, changed
  chunking and out-of-range counts; `sourceText` stripped / `sourceChunk` retained; overwrite in
  place; clear on completion; per-document isolation; `failedExtractions` survival; and **titles
  round-tripping byte-exact** (including quotes, em dashes and non-ASCII) because `revisions` and
  `removals` are matched **by title** — a resumed run's correctness depends on that entirely.
- `TestAsyncJobRegistry` (10) — result retained after completion; ownership (non-owner poll/cancel
  indistinguishable from a bad id); TTL; lazy pool recreation after shutdown.

**Unit — Ux752 (Vitest).** `npx vitest run src/test/asyncJobPoller.test.js` (21) — `async=true` and
`fresh=true` on the wire, 202 required, terminal detection, progress on every tick, deadline
bounding, no-cancel-on-timeout, no-cancel-on-abort, `JobAuthError` + `forceLogin`, and
`scenesFromResult` over all three response shapes.

**E2E — `e2e/pictureBookAsyncJob.spec.js`, gated.** The repeatable in-repo version of the
integration checks below. Skipped by default (5 skipped); run it with:

```
PB_ASYNC_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443   npx playwright test e2e/pictureBookAsyncJob.spec.js --workers=1 --project=chromium
```

**5 passed (4.7m)** — A1 async start returns 202 promptly and the run completes 5/5 with
`extractionComplete: true`; A2 an unknown/non-owned jobId is 404 and `cancelled:false`; **A3 the
result is collected from a SEPARATE request context after the starting context was destroyed** (the
reported defect, reproduced deliberately and shown fixed), with all scene titles distinct; A4 a
mid-run cancel yields `cancelled` with partial scenes retained and `extractionComplete: false`; A5
`GET /rest/job` lists the caller's own jobs for reattach, without results.

Two things this spec had to get right that are easy to miss:
- **`test.setTimeout(20 min)` per test.** Playwright's default 60s cap killed the first run mid-chunk
  ("Test timeout of 60000ms exceeded") while the server was healthily on chunk 3/5. The poller's own
  budget is irrelevant once Playwright has aborted the test.
- **`ensureChatConfig` returns the config NAME as a string, not a record.** Reading `.name` off it
  gave `undefined`, the body omitted `chatConfig`, the server fell back to `generalChat` (which this
  user is DENIED), every LLM call returned nothing, and the extraction "completed" with zero scenes
  while the weaker original assertions still passed. The spec now fails loudly on a missing config
  and asserts that a healthy run actually produced scenes.

**FULL PIPELINE — document to a real image.** Everything above verifies the *extraction slice*.
This is the whole feature, run 2026-09-13 against the live stack with the real LLM and real SD:

| Step | Result |
|---|---|
| upload `AIME.pdf` as `data.data` | bytes persisted |
| `extract-scenes-only?async=true&fresh=true` | 5 chunks, **4 scenes**, `extractionComplete: true`, 68s |
| `create-from-scenes` | book `8a7870d3…`, **2 scene notes, 4 charPerson records** |
| `POST /scene/{id}/generate` (SwarmUI 192.168.1.39) | `imageObjectId` returned, **346s** |
| the stored artifact | **real PNG, 1,395,098 bytes**, `contentType=image/png` |
| the UI's own media URL (`/media/{org}/data.data{groupPath}/{name}`) | **HTTP 200**, same 1,395,098 bytes |
| the completed extraction's checkpoint | cleared |

The image was also **looked at**, not just decoded: it renders two women on phones in a
glitter-dusted, decayed ballroom with trophies, pink bows, dead roses and bugs on the walls —
matching the extracted scene "The AI And Me Singles Event: A Glittering Trap" and the source text.
A passing magic-byte check is not evidence the picture is right.

> Two traps in verifying the image, both of which cost a retry. The media route is **path-based and
> outside `/rest`** (`/media/{dotOrgPath}/data.data{groupPath}/{name}`) — there is no
> `/media/data.data/{objectId}`. And `am7client.getDotPath` only strips the leading slash, it does
> **not** lowercase: the org segment is `Development`, not `development`. Getting it wrong yields a
> 500 whose only clue is `PathUtil - Invalid search for auth.group ... org 0 from 'home'`.

**Still not covered: the browser UI.** Every test here drives HTTP. No test opens the wizard,
clicks Extract, and watches the progress bar, Cancel, reattach or the partial banners behave —
those are covered only by `pictureBookExtractState.test.js` against a stubbed fetch.

**Integration — live LLM, real AIME.pdf, Docker stack.** All of the following were observed, not
inferred:

| | |
|---|---|
| `POST ?async=true` | **202** + jobId, returns immediately |
| result collected from a **completely separate session** after the original POST connection was gone | 10 scenes single-shot / 4 chunked — **the original defect, fixed** |
| chunked branch on AIME.pdf | `chunked=true`, 5 chunks, progress 0→1→2→4→5 |
| mid-run cancel | status `cancelled`, **5 partial scenes retained**, `extractionComplete=false` |
| non-owner poll/cancel | **404** / `cancelled=false`; `GET /job` lists own jobs for reattach |
| checkpoint written mid-run | `chunksProcessed=2`, `sourceText` absent from the note |
| `docker restart` mid-run | interrupt detected, checkpoint **kept**, survived the restart |
| re-drive | `Resuming chunked extraction ... at chunk 3/5 with 4 scene(s) already extracted` |
| resumed run | completed, **11 distinct scene titles — no duplicates, none dropped** |
| completed run | checkpoint **deleted**; cancelled run keeps it; `fresh=true` discards it |
| ChapBook `?async=true` | 202 + jobId; book collected from a separate session; `total=36` scenes, progress advancing |
| ChapBook mid-run cancel | `cancelled` at 2/36; book kept, and **2 scenes persisted** (confirmed in `a7_olio_pb_scene_0_1`) |
| ChapBook phase before the scene total is knowable | `preparing book world` |
| **WordPerfect WP6+ misnamed `.DOC`** (`5NIGHTS.DOC`, declared `application/msword`) | ingested **10,861 chars** through `POST /chap-book/poems` — Defect 3 end-to-end |
| WordPerfect WP5.x (`RAINBOW.DOC`) | extracted 420 chars, 100% printable — not garbage |

> **A test-query trap worth repeating, because it cost time here.** The cancel check first reported
> "0 persisted scenes" for a book that demonstrably had 2. The query was
> `olio.pb.scene` filtered on `groupPath LIKE '%slug%'` with **no `organizationId`** — and a list
> query over a `data.directory`-derived type without an explicit numeric `organizationId` is denied
> by PBAC, which surfaces as an empty result set rather than an error. Measured side by side
> afterwards: the old shape returns 0 rows, the corrected one (`name LIKE 'Scene <slug>%'` plus
> `organizationId` as a **number**) returns both scenes. It read exactly like a product defect.
> See `.claude/rules/model-api.md`.

**Not verified live:**
- The truncation-repair salvage path — unit-tested only; no truncation occurred in these runs.
- `PbRunStatusEnumType.CANCELLED` being written to a run row. This is **unreachable, not merely
  unverified** — see the section above. An earlier draft of this document described it as
  "not verified live", which implied it was wired. It is not.
- The bulk ChapBook `?async=true` render route end to end. The client never calls the bulk render
  (it renders scene by scene), so this was exercised only far enough to confirm the 202 contract.

**A defect this work introduced and then caught, recorded because the shape recurs.** The job
reported `status: completed` with `extractionComplete: true` after extracting **zero** scenes,
because the chat config could not be resolved (`AUDIT DENY … generalChat`), every LLM call returned
nothing, and the circuit breaker stopped the run at chunk 2/5. The breaker behaved correctly; the
*reporting* lied, because "complete" was being derived from the cancel flag — which cannot express
an interrupt or an unreachable model server. `ScenesOnlyResult.complete` now carries the chunk
loop's own answer. The general rule: **a long operation has more than two outcomes, and any boolean
that collapses them will eventually claim success for a run that did not finish.**

### Redeploying to the Docker stack without an image rebuild

```
cd src
mvn -o -q -pl AccountManagerObjects7 install -DskipTests
mvn -o -q -pl AccountManagerService7 package -DskipTests
docker cp AccountManagerObjects7/target/AccountManagerObjects7-7.0.0-SNAPSHOT.jar \
  am7test-am7-1:/opt/tomcat/webapps/AccountManagerService7/WEB-INF/lib/
docker cp AccountManagerService7/target/classes/org/cote/rest/services/<X>.class \
  am7test-am7-1:/opt/tomcat/webapps/AccountManagerService7/WEB-INF/classes/org/cote/rest/services/
docker restart am7test-am7-1
```

Use the **main** jar, not `-tests.jar` (`ls -t` picks the tests jar). `supervisorctl` is unusable in
this image — use `docker restart`. UI only:
`cd AccountManagerUx752 && npx vite build && docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/`.

The application log is **`docker logs`**, not `/opt/tomcat/logs/*.log` — log4j2 writes to stdout in
this image, so `catalina.<date>.log` contains none of the application's own lines. Reading the wrong
source once made a passing resume test look like a failure.

---

## Known limits (deliberate)

- **No checkpoint for the single-shot (non-chunked) path.** It is one LLM call, so there is nothing
  partial to save; a lost connection there is covered by job result retention alone.
- **The interrupt-time checkpoint save is best-effort and can lose its race with IO teardown.**
  Observed on `docker restart`: the process died before the write landed. Mitigated rather than
  solved — `EXTRACT_CHECKPOINT_EVERY` is **1**, so the periodic write is the durable mechanism and
  the early-exit saves are an optimisation. Worst case the run re-does the chunk that was in
  flight, which produced nothing anyway.
- **`PbRunStatusEnumType.CANCELLED` is reserved and unwritten** — see above. Wiring it needs a
  cancellable path that owns an `olio.pb.run`; `generateSceneImage`, the only `closeRun` caller,
  takes no cancel token. Left undone rather than faked.
- **The ChapBook bulk `?async=true` render is verified only to its 202 contract.** The client
  renders scene-by-scene and never calls the bulk route, so the async wrapper exists for API and
  script callers.
- **No test drives the browser UI.** The wizard's progress bar, Cancel, reattach and partial
  banners are covered by unit tests against a stubbed fetch, and by nothing else.

## Closed since the first draft

Each of these was a real gap, found either by the independent review or by the tests themselves:

| Gap | Resolution |
|---|---|
| `extractionComplete` derived from the cancel flag, so interrupt and circuit-breaker stops reported success | `reachedEnd` carried out of the loop on `ScenesOnlyResult.complete`; also removed the determination from the transport layer |
| An interrupt during the **final** chunk still cleared the checkpoint | post-loop interrupt re-check before the clear |
| `chunksProcessed` advanced past chunks that produced **nothing**, so a resume skipped that passage for good | it now advances only where a chunk's result was merged; the failure paths `continue` past it |
| A checkpoint with `chunksProcessed == 0` was written, unresumable, and never cleaned up | `saveExtractCheckpoint` skips it |
| Checkpoint notes survived forever if the source document was deleted | `clearExtractCheckpoint` falls back to `deleteOrphanedExtractCheckpoints`, an org-scoped exact-name sweep |
| `GET /rest/job` had no paging | `startRecord` / `recordCount`, defaulting to "everything" so existing callers are unaffected |
| `reattachCreateChapBook` was exported but never called | wired into `openCreateDialog`, **and** re-checked in `doCreateChapBook` against the slug as typed |
| A cancelled ChapBook create fired both a warn and a success toast | one correct toast |
| `reattachExtractJob`'s guard was evaluated before its first `await`, allowing two pollers | claim flag set synchronously |
| `LLMConnector.setBgActivity`'s 90s expiry killed the indicator mid-run for any held operation | holding the lock now selects a 30-minute backstop instead of the 90s net |
| The chunk loop's branches had no unit test at all | `PictureBookUtil.ChunkLlm` seam + `TestExtractChunkLoop` (10 tests) |
| A test re-implemented the revision merge instead of calling it | `mergeChunkResult` extracted; 7 tests, mutation-verified |
| The client poller's server contract was asserted only against hand-written fixtures | explicit field-shape assertion in the gated e2e, where both sides actually meet |
