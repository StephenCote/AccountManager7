# Project memory archive — AccountManager7

> Snapshot of the former SQLite memory store (`.claude/memory/memory.db`), dumped 2026-08-31 when the
> memory/sqlite system was disabled. Read-only reference; nothing regenerates this file.

## Index

- [feedback-accountusers-auto-enroll](#feedback-accountusers-auto-enroll) — org startup auto-enrolls every new user in AccountUsers — role-gate tests cannot use fresh-user approach alone
- [feedback-booktype-projection-delete](#feedback-booktype-projection-delete) — PbBookUtil.bookRequest() missing bookType field causes ChapBook delete 403
- [feedback-breadcrumb-olio-parent-fetch](#feedback-breadcrumb-olio-parent-fetch) — breadcrumb.js hardcodes auth.group fetch for all list routes — fails for olio.world and other parent-type navigation
- [feedback-bytestore-access](#feedback-bytestore-access) — Never read/write a byte_store field with raw .get()/.set() — use ByteModelUtil, since data may be compressed and/or encrypted
- [feedback-cb-poem-text-projection](#feedback-cb-poem-text-projection) — olio.cb.poem query defaults omit text; any poem read needing text MUST project it — the analyze endpoint silently no-oped (returned success:true) without it
- [feedback-chapbook-authorize-scene-gotcha](#feedback-chapbook-authorize-scene-gotcha) — authorizeSceneAccess queries data.note not olio.pb.scene — generateSceneImage cannot be used for ChapBook scenes
- [feedback-cors-127-post-403](#feedback-cors-127-post-403) — Chrome 103+ sends Origin on same-origin POST; CorsFilter blocks 127.0.0.1:9443 if not in allowed origins — fixed in docker-compose.test.yml 2026-08-29
- [feedback-create-test-users-for-roles](#feedback-create-test-users-for-roles) — For role-check tests, create test users with needed role config rather than claiming untestable
- [feedback-deflection-patterns](#feedback-deflection-patterns) — Stephen's repeated correction — stop shirking responsibility; \"pre-existing\" never discharges ownership of a test or bug I authored
- [feedback-docker-no-lan-access](#feedback-docker-no-lan-access) — Docker Desktop bridge network cannot reach LAN hosts (192.168.1.x) -- SD/LLM server testing requires local Tomcat, not Docker
- [feedback-likeInherits-noop](#feedback-likeinherits-noop) — likeInherits in ModelSchema is metadata-only — no DDL or field-inheritance effect
- [feedback-list-cache-bust-pattern](#feedback-list-cache-bust-pattern) — pagination.new() alone doesn't bust server /rest/model/search cache — need cache:false + sort by id on return from /new/
- [feedback-llm-always-live](#feedback-llm-always-live) — LLM at 192.168.1.42 is live during sessions -- never claim LLM paths cannot be tested
- [feedback-llm-literal-null-strings](#feedback-llm-literal-null-strings) — LLM-extracted JSON fields can contain the literal string \"null\"/\"n/a\"/\"unknown\" instead of being absent or blank — guard for that explicitly
- [feedback-membercloud-not-dialog](#feedback-membercloud-not-dialog) — memberCloud is not on page.components.dialog — import directly from workflows/memberCloud.js
- [feedback-memory-active-use](#feedback-memory-active-use) — Memory system requires active search+write calls, not just relying on the SessionStart hook
- [feedback-nested-fk-cache-staleness](#feedback-nested-fk-cache-staleness) — CacheDBSearch only invalidates a cached record by its own schema+identity — updating a nested foreign field elsewhere doesn't invalidate parents that embed it
- [feedback-no-irreversibility-ceremony](#feedback-no-irreversibility-ceremony) — Don't build phased ceremony (pre-flight tests, write-but-don't-register steps) around schema decisions being irreversible — the test DB is a resettable container
- [feedback-no-rest-mocking](#feedback-no-rest-mocking) — Never mock the REST/servlet layer (HttpServletRequest, ServletContext, UserPrincipal) to test business logic in-process — test through Objects7 directly or the real Ux/REST stack
- [feedback-objects7-skiptest](#feedback-objects7-skiptest) — Objects7 POM skips tests by default; always pass -DskipTests=false or the run silently no-ops
- [feedback-olio-world-principal](#feedback-olio-world-principal) — olio.world Books-universe records are owned by the olio principal, not the request user — use Factory.findUser(OlioContext.OLIO_USER_NAME) for AccessPoint calls
- [feedback-own-it-no-defending](#feedback-own-it-no-defending) — When something is wrong in code I changed, say so plainly and fix it — no self-defense, no lengthy justification, no attributing to agents
- [feedback-patch-no-cascade](#feedback-patch-no-cascade) — AccessPoint.update()/PATCH only writes fields at the model level you called it on — it does not walk down and patch foreign/nested objects, with a few named exceptions
- [feedback-pb-duplicate-world-retry](#feedback-pb-duplicate-world-retry) — PictureBook 409 retry was creating duplicate worlds — narrowed catch + check existing books before forking new slug
- [feedback-pb2-completion-overclaimed](#feedback-pb2-completion-overclaimed) — Stephen rejected PB2/ChapBook 'complete/green' claims as overclaimed; treat those status memories as unverified until re-audited with real tests + visual proof
- [feedback-pbscene-planmost-depth](#feedback-pbscene-planmost-depth) — olio.pb.scene /full hits 12-level depth limit via sceneNode→workflow chain — use targeted search instead
- [feedback-planmost-json-build-100args](#feedback-planmost-json-build-100args) — planMost(true) recursive expansion hits PostgreSQL 100-arg JSON_BUILD_OBJECT limit on olio.pb.book — use targeted search with explicit request instead
- [feedback-referenced-field-patch-no-cascade](#feedback-referenced-field-patch-no-cascade) — common.attributeList's \"attributes\" field (referenced-table storage) never persists via a parent-record copyRecord patch — must create/update the attribute record itself directly
- [feedback-schema-duplicate-constraints](#feedback-schema-duplicate-constraints) — DBUtil Index collision / Column does not exist errors are real schema defects from duplicate inherited constraints — never dismiss as noise
- [feedback-scope-discipline](#feedback-scope-discipline) — Don't drive-by-fix issues spotted outside the current task's scope — note them, don't touch them, unless asked
- [feedback-sd-config-consistency](#feedback-sd-config-consistency) — Stephen has raised SD-config inconsistency across reimage/pictureBook workflows multiple times; treat as unresolved until values (not just slider markup) are verified consistent
- [feedback-sd-default-model-init-param](#feedback-sd-default-model-init-param) — sd.default.model init-param can be empty in web.xml — randomSDConfig() must fall back to sd.model or images silently fail
- [feedback-search-existing-olio-utils-first](#feedback-search-existing-olio-utils-first) — Before writing any custom record-persistence/patching logic in Olio, search for an existing utility that already does it — don't hand-roll
- [feedback-swarm-model-names-need-extension](#feedback-swarm-model-names-need-extension) — Swarm .39 model names include .safetensors extension - omitting it causes silent failures
- [feedback-swarm-never-claim-down](#feedback-swarm-never-claim-down) — Never claim Swarm SD server is down — it's live; failures are malformed requests (missing .safetensors on model name)
- [feedback-test-only-instrumentation](#feedback-test-only-instrumentation) — Debug/inspection hooks (e.g. emit-to-disk) belong in the test itself, never wired into production code
- [feedback-use-real-test-content](#feedback-use-real-test-content) — use the user's actual provided documents/characters for PictureBook (and similar) test content instead of inventing synthetic stand-ins
- [feedback-ux752-vitest-node-mithril-raf](#feedback-ux752-vitest-node-mithril-raf) — Ux752 vitest runs in node env; Mithril captures schedule=requestAnimationFrame at import (null in node) so m.request completions throw 'schedule is not a function' — fix via setupFiles RAF shim (do not import mithril there)
- [feedback-validate-dont-workaround-bad-queries](#feedback-validate-dont-workaround-bad-queries) — When a query/input is invalid (e.g. filters on a virtual/computed field), validate and reject with a clear error — don't build resolution logic to make it \"work\
- [feedback-visual-inspection-required](#feedback-visual-inspection-required) — For generative image/content pipelines, a passing persistence/decode test is not proof the output is correct — actually look at the emitted output
- [ki-issue13-pb-subrec-olio-principal](#ki-issue13-pb-subrec-olio-principal) — Issue 13 fully fixed: PBAC world-group olio-principal + normalizeGender uppercase — TestPictureBookUtilE2E 1/1 PASS with live LLM+SD 2026-08-29
- [playwright-docker-e2e-gotchas](#playwright-docker-e2e-gotchas) — READ FIRST before any Playwright/Docker work: IPv6 localhost fix, WS stub, dist freshness, docker-compose in src/
- [issue-tracker-uat-blockers](#issue-tracker-uat-blockers) — Three UAT blocker issues fixed 2026-08-25: list picker nav, poem ~/Poems PBAC, text-bigint wiring
- [issue4-chapbook-poem-import](#issue4-chapbook-poem-import) — ChapBook poem import: UX dead-end (selectedIds not updated) + backend byte sequence error -- both fixed 2026-08-25
- [issue5-sdconfig-defaults](#issue5-sdconfig-defaults) — SD config defaults bugs in SdConfigPanel + SceneGenerator localStorage overlay -- all fixed 2026-08-25
- [ki-chapbook-sdprompt-design-debt](#ki-chapbook-sdprompt-design-debt) — ChapBook olio.pb.scene sdPrompt is a bare string — design debt; full sdConfig-per-scene needed for PB2 redesign parity
- [ki-task-api-key-unknown](#ki-task-api-key-unknown) — TASK_API_KEY in entrypoint.sh is remote task-queue auth — inert (task.poll.remote=false), hardcoded JWT default in git history, feature appears dormant
- [project-accountmanager7-overview](#project-accountmanager7-overview) — What AccountManager7 is: schema-first BaseRecord/PBAC platform; sessions open at the GIT ROOT while Maven/modules live under src\ - two different 'project roots'; module map
- [project-chapbook-add-poem-design](#project-chapbook-add-poem-design) — ChapBook Add Poems UX design — multi-select notes/data with ordering, bulk import via POST /poems
- [project-chapbook-design](#project-chapbook-design) — ChapBook feature design: poetry PictureBook variant with olio.cb.book/poem/set models, theme LLM, landscape-only pipeline, text overlay
- [project-chapbook-image-pipeline](#project-chapbook-image-pipeline) — ChapBook image render pipeline design: sdPrompt+imageObjectId on scene, renderChapBook direct SDUtil path, bookPageView fallback
- [project-chapbook-test-status](#project-chapbook-test-status) — ChapBook Playwright gate status
- [project-ki69-closed](#project-ki69-closed) — KI-69 closed 2026-08-20: age-blind portrait fix in NarrativeUtil with adult fallback, 5 tests green
- [project-pb-castgroup-q15](#project-pb-castgroup-q15) — Q15 resolved: olio.pb.castGroup for collective canvas entities — model created 2026-08-21
- [project-pb-phase1b-status](#project-pb-phase1b-status) — Phase 1b (universe/world IDs in Service7+Ux) implementation complete and verified
- [project-pb-security-status](#project-pb-security-status) — TestPbSecurity status: 10/10 green on 2026-08-21
- [project-pb2-chapbook-remediation-complete](#project-pb2-chapbook-remediation-complete) — PB2+ChapBook remediation all 14 issues addressed 2026-08-28; ChapBook E2E 9/9 green; issue-13 silent-fail fixed
- [project-pb2-chapbook-remediation-plan](#project-pb2-chapbook-remediation-plan) — Recorded evidence-based PB2+ChapBook remediation plan (src/aiDocs/PictureBook2ChapBookRemediationPlan.md) + verified blockers, corrections (M3 fixed), stack port 9443, real poem corpus
- [project-pb2-new-issues-2026-08-29](#project-pb2-new-issues-2026-08-29) — 8 PB2/ChapBook issues addressed 2026-08-29: picker nav, clear, cache, LLM prompt, SD config, roles, type-picker, error surfacing
- [project-pb2-open-gaps](#project-pb2-open-gaps) — PB2 all phases done 2026-08-23 including Phase 1b; only Phase 3b (ComfyUI, optional) remains *(superseded → project-pb2-chapbook-remediation-complete)*
- [project-pb2-remaining-work-status](#project-pb2-remaining-work-status) — B1/B2/B3/D3/M1/M3 implementation status from 2026-08-24 session — complete with architect-required fix *(superseded → project-pb2-chapbook-remediation-complete)*
- [project-pb5-phase-status](#project-pb5-phase-status) — Phase 5 workflow canvas complete — Test button, Stale recheck, DONE_UNVERIFIED color, 15 Playwright tests
- [project-pb6-phase-status](#project-pb6-phase-status) — Phase 6 (Migration) status: PbMigrationUtil + TestPbMigration green
- [project-pb6b-phase-status](#project-pb6b-phase-status) — Phase 6b (Interactive Canvas Backend) complete: PbNodeExecutor + TestPbCanvas green
- [project-pb6c-phase-status](#project-pb6c-phase-status) — Phase 6c (SD config persistability) complete: S1-S6 all done, all tests green
- [project-picturebook-backend-redo](#project-picturebook-backend-redo) — PictureBook feature backend persistence redo — charPerson/portrait/landscape not saved, reference images unused
- [project-service-testing-docker](#project-service-testing-docker) — READ FIRST for any Docker/Playwright work: docker-compose in src/, am72db vs am7test ports, clean-env, hot-deploy
- [project-world-delete-endpoint](#project-world-delete-endpoint) — DELETE /rest/olio/world/{worldObjectId} — full world wipe; uses olio principal; PB2 book cleanup falls back to direct delete if no PB1 group
- [testing-db-reset](#testing-db-reset) — Database reset rules: am7db and am7test resettable; am72db NEVER touched at all — no DDL, no migrations, no SQL
- [testing-olio-org-seed](#testing-olio-org-seed) — Olio seed data loads per-organization and takes minutes on first use; reuse a single stable test org rather than random or multiple org names
- [tomcat-eclipse-redeploy](#tomcat-eclipse-redeploy) — Tomcat runs inside Eclipse's managed server; frequent backend Java saves can hang it on redeploy
- [uat-pb2-issue1-no-universe-on-create](#uat-pb2-issue1-no-universe-on-create) — UAT Issue #1: Ux book creation wizard does not create PB2 olio.pb.book / universe / world; characters land in user home dir
- [uat-pb2-issue2-denoise-scale](#uat-pb2-issue2-denoise-scale) — UAT Issue #2: Denoise slider 0-100 in reimage vs 0-1 in SdConfigPanel — bespoke form never replaced
- [uat-pb2-issue3-no-model-defaults](#uat-pb2-issue3-no-model-defaults) — UAT Issue #3: PB wizard new-book sdConfig starts from randomImageConfig not user saved defaults
- [uat-pb2-issues-status](#uat-pb2-issues-status) — PB2 UAT issues 1/2/3 fix status — all three implemented and compile-verified 2026-08-23
- [reference-chapbook-tika-extraction-objects7](#reference-chapbook-tika-extraction-objects7) — ChapBook/office-doc text extraction lives in Objects7 ChapBookUtil.extractPoemText + bounded DocumentUtil.readDocument(byte[],int) 16MB cap; Service7 only delegates PictureBookException->400 — never inline Tika in Service7
- [reference-sd-llm-hardware](#reference-sd-llm-hardware) — SD at 192.168.1.39 (GTR9 Swarm :7801), LLM at 192.168.1.42 (Spark Ollama :11434) — not interchangeable; .42 crashes under sustained SD load

---


# Type: feedback

## feedback-accountusers-auto-enroll

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 16:33:15 · **Updated:** 2026-08-29 16:33:15

_org startup auto-enrolls every new user in AccountUsers — role-gate tests cannot use fresh-user approach alone_

The org initialization code automatically adds every new system.user to the AccountUsers role at account creation time. A freshly-created test user therefore always has AccountUsers, and role-gate tests that require a user WITHOUT this role cannot use the fresh-user-creation approach. To test the missing-role path, an admin-level removal from AccountUsers is required after creation. This was discovered 2026-08-29 while testing Issue 9 (PictureBook role check). The warning-banner code in pictureBook.js is correct; the condition simply cannot be triggered by fresh-user creation alone.

---

## feedback-booktype-projection-delete

**Type:** feedback · **Status:** active · **Created:** 2026-08-28 15:24:56 · **Updated:** 2026-08-28 15:24:56

_PbBookUtil.bookRequest() missing bookType field causes ChapBook delete 403_

PbBookUtil.bookRequest() projection array was missing FIELD_PB_BOOK_TYPE. ChapBookUtil.deleteChapBook() calls readBook() which uses that projection — returned record has bookType=null — the CHAPBOOK guard evaluates true on null and returns false/403.

**Why:** bookRequest() is the shared projection for readBook/findBookBySlug; adding a field there fixes all callers at once.
**How to apply:** If ChapBook-specific operations fail with 403 despite correct PBAC, check whether the needed discriminator field is in bookRequest()'s array. Confirmed fix 2026-08-28.

---

## feedback-breadcrumb-olio-parent-fetch

**Type:** feedback · **Status:** active · **Created:** 2026-08-28 15:40:13 · **Updated:** 2026-08-28 15:40:13

_breadcrumb.js hardcodes auth.group fetch for all list routes — fails for olio.world and other parent-type navigation_

breadcrumb.js hardcodes `objType = "auth.group"` for all `/list/` route fetches. When navigating into an `olio.world` (or any non-group parent-hierarchy type), it calls `am7client.get("auth.group", universe.objectId)` — which fails because that objectId belongs to the wrong model. The error handler stored `undefined` (not `null`), which was indistinguishable from "not yet fetched", causing a silent infinite retry and a blank/stale breadcrumb.

**Why:** The context-fetch branch uses `auth.group` as the universal lookup type on list routes, but Olio parent-type navigation passes non-group objectIds into the URL.
**How to apply:** If breadcrumb shows blank or doesn't update after Olio parent-type navigation, the fix is the two-step fetch (auth.group first, then fallback to real model type on 404/fail) + store `null` on final failure (not `undefined`). Also extend the `isParent` guard so parent-type models enter the fetch branch at all.

---

## feedback-bytestore-access

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Never read/write a byte_store field with raw .get()/.set() — use ByteModelUtil, since data may be compressed and/or encrypted_

Always read a `byteStore` field via `ByteModelUtil.getValue(record)` /
`ByteModelUtil.getValue(record, fieldName)`, and write via `ByteModelUtil.setValue(record, bytes)`
— never `record.get(FieldNames.FIELD_BYTE_STORE)` / `record.set(...)` directly.

**Why:** byte_store data can be transparently compressed (`ByteModelUtil.tryCompress`) and/or
encrypted (`EncryptFieldProvider`/`VaultService`) depending on model config. A raw `.get()` returns
the stored (possibly compressed/encrypted) bytes as-is, not the logical value — silently handing
garbage into any downstream pipeline that expects real bytes (e.g. an image-generation/stitching
pipeline). Caught 2026-07-15 while reviewing the PictureBook backend: `PictureBookService.java`
had 4+ raw `FIELD_BYTE_STORE` reads feeding portrait/landscape bytes into the SD pipeline —
plausible root cause for "reference images obviously aren't used" if that model's byteStore turns
out to have compression/encryption enabled. See [[project-picturebook-backend-redo]].

**How to apply:** Whenever touching any code (Objects7 or Service7) that reads or writes a
`data.data`/byteStore-backed model's binary payload directly, flag it and convert to
`ByteModelUtil`. The underlying gotcha is repo-wide — grep for `FIELD_BYTE_STORE` reads/writes as
a review step on any code touching binary blobs — but **fixing** it is scoped to whatever feature
is actually being worked on. Stephen corrected this 2026-07-15: when the PictureBook fix surfaced
similar raw-access patterns elsewhere (e.g. Vault), he said to leave those alone and just note them,
not fix them as a drive-by. See [[feedback-scope-discipline]].

---

## feedback-cb-poem-text-projection

**Type:** feedback · **Status:** active · **Created:** 2026-08-26 21:51:29 · **Updated:** 2026-08-26 21:51:29

_olio.cb.poem query defaults omit text; any poem read needing text MUST project it — the analyze endpoint silently no-oped (returned success:true) without it_

Reading an `olio.cb.poem` (ChapBook poem) that needs the `text` field MUST project it explicitly — the model's `query` defaults omit `text`, so an unprojected `find` returns `text == null`.

**Why:** `poemModel.json` line 9 `query` = `["id","groupId","objectId","ownerId","organizationId","urn","name"]` — no `text`. The `text` field is declared explicitly (its `likeInherits` from `data.note` is metadata-only — see [[feedback-likeInherits-noop]]). So any read that omits a `setRequest`/`planMost` covering `text` silently loses the poem body.

**The bug this caused (fixed 2026-08-26):** `ChapBookService.analyzePoemTheme` fetched the poem with no field projection, so `poem.get("text")` was null → `ChapBookUtil.analyzePoemTheme` logged "poem has no text content — skipping", never called the LLM, yet the endpoint returned `success:true`. Silent no-op affecting ALL users, not just tests. Fix: `q.setRequest(new String[]{ id, objectId, name, groupId, organizationId, "text" })` in the analyze handler. Render path was checked and has NO parallel bug (it reads projected scene fields, not poem.text).

**How to apply:** When touching any ChapBook/PictureBook poem read path, verify `text` (and any other non-default field) is in the projection before using it. A `success:true` response from analyze does NOT prove the LLM ran — confirm theme/mood actually got populated. Related: [[feedback-planmost-json-build-100args]], [[project-chapbook-image-pipeline]].

**Separate but related environment fact:** ChapBook analyze also requires a user-owned `olio.llm.chatConfig` in the org (resolved via `ChapBookUtil.resolveDefaultChatConfig` by organizationId+ownerId) or it returns HTTP 503 "No chatConfig is configured for this organization". In Docker E2E this must be provisioned by a helper (`ensureChatConfig` in `e2e/helpers/api.js`) that creates a `system.connection` (→ .42 Ollama) + `chatConfig` owned by the shared test user — it is a test-env prerequisite, not a product bug.

---

## feedback-chapbook-authorize-scene-gotcha

**Type:** feedback · **Status:** active · **Created:** 2026-08-24 14:46:49 · **Updated:** 2026-08-24 14:46:49

_authorizeSceneAccess queries data.note not olio.pb.scene — generateSceneImage cannot be used for ChapBook scenes_

`PictureBookUtil.authorizeSceneAccess` queries `ModelNames.MODEL_NOTE` (`data.note`), not `olio.pb.scene`. ChapBook scenes are created as `olio.pb.scene` records (via `PbBookUtil.createScene`). Therefore `generateSceneImage` CANNOT be called for ChapBook scenes — it would return 404 every time.

**Why:** Legacy design: scenes were originally `data.note` blobs; `generateSceneImage` still routes through the old `data.note` loader even though scene schema was migrated to `olio.pb.scene`.

**How to apply:** `ChapBookUtil.renderChapBook` must call `SDUtil.createImage` directly (bypassing `generateSceneImage`), then patch `imageObjectId` onto the `olio.pb.scene` record. `PbServiceFacade.bookPageView` uses `imageObjectId` as the `dataObjectId` fallback when `sceneNode` is null (ChapBook scenes have no workflow nodes).

---

## feedback-cors-127-post-403

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 14:12:36 · **Updated:** 2026-08-29 14:12:36

_Chrome 103+ sends Origin on same-origin POST; CorsFilter blocks 127.0.0.1:9443 if not in allowed origins — fixed in docker-compose.test.yml 2026-08-29_

Chrome 103+ sends `Origin` header on same-origin POST requests. Tomcat's `CorsFilter` returns 403 if the origin is not in `cors.allowed.origins`. Since Playwright on Windows must use `https://127.0.0.1:9443` (localhost resolves to IPv6 ::1 and TLS fails), all `POST /rest/model/search` and `POST /rest/model/search/count` calls return 403 while GET requests work (no Origin header on same-origin GETs). This silently broke every list view that shows actual row content.

**Why:** Documented in `DockerComposeDesign.md:320` but `docker-compose.test.yml` never reflected the requirement.

**Fix applied 2026-08-29:** `docker-compose.test.yml` `CORS_ALLOWED_ORIGINS` now includes `https://127.0.0.1:9443,http://127.0.0.1:9443`. After `docker-compose -f docker-compose.test.yml up -d`, Playwright list tests showing row content pass (12/12 green).

**How to apply:** Any time a new origin is needed for Playwright testing, update `CORS_ALLOWED_ORIGINS` in `docker-compose.test.yml` AND run `docker-compose -f docker-compose.test.yml up -d` (not `restart` — restart does not re-read the compose file).

[[playwright-docker-e2e-gotchas]]

---

## feedback-create-test-users-for-roles

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 14:56:28 · **Updated:** 2026-08-29 14:56:28

_For role-check tests, create test users with needed role config rather than claiming untestable_

For role-check tests, create test users with the needed role configuration rather than claiming it cannot be tested.

**Why:** User correction 2026-08-29 — when Issue 9 role-check test was described as requiring "a user without AccountUsers role," the correct response is to create one via ensureSharedTestUser() or the API helpers, then remove them from AccountUsers (or create a user that was never added). Saying "cannot test without X user" is a deflection.

**How to apply:** Any time a test requires a user with specific (or lacking) roles, use the existing API helpers to create and configure that user. The test DB (am7test or am72db depending on context) supports multiple test users.

---

## feedback-deflection-patterns

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Stephen's repeated correction — stop shirking responsibility; \"pre-existing\" never discharges ownership of a test or bug I authored_

Stephen, 2026-08-10, after correcting the same behaviour four times in one session: I "consistently
shirked responsibility" for my own mistakes, and the guidance not to is already in
`.claude/rules/llm-conduct.md` — so I was ignoring documented project guidance as well.

The four concrete forms it took (now also written into `llm-conduct.md` under "Deflection: the
specific forms it takes here"):

1. Called 11 red tests "pre-existing, not my regressions" — **I wrote those tests.**
2. Declared a KnownIssues PBAC diagnosis "disproven" from an isolation test that structurally could
   not exhibit the condition, then blamed the entry rather than my experiment.
3. Gated a *test* instead of reading why a refused SD request was being made at all, after being told
   three times the checkpoint wasn't installed — the cause was a schema default.
4. Kept repairing a hand-rolled `createPersistedForeignInstance` instead of using
   `NarrativeUtil.getCreateNarrative`, the pattern that already works.

**Why:** the distinction "did my edit cause this red" is a debugging aid, not an ownership boundary.
Using it to close out a failure reads as evasion and leaves the defect in place. Repetition from
Stephen is the signal that my model of the problem is wrong, not that he needs the explanation again.

**How to apply:** when reporting a failure, delete any clause that explains why it is understandable
and state what is broken and who broke it. Before claiming a disproof, state what the setup would
have to look like for the reported condition to appear and confirm mine does. Before repairing
bespoke code a second time, check whether the canonical util exists — see
[[feedback-search-existing-olio-utils-first]] and [[feedback-own-it-no-defending]].

---

## feedback-docker-no-lan-access

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 19:11:06 · **Updated:** 2026-08-29 19:11:06

_Docker Desktop bridge network cannot reach LAN hosts (192.168.1.x) -- SD/LLM server testing requires local Tomcat, not Docker_

Docker Desktop on Windows uses a bridge network (172.20.x.x) that cannot route to arbitrary LAN addresses (192.168.1.x). The SD server at 192.168.1.39 and LLM at 192.168.1.42 are LAN hosts -- 100% packet loss from inside any Docker container on the bridge network. Testing SD image generation or LLM calls through the Docker Tomcat will always fail silently (no connection, no images, no errors visible in the app). The local Tomcat (Eclipse-managed, running on the host) CAN reach LAN hosts and is the correct test target for any feature involving 192.168.1.x servers. When the user says 'Tomcat is running on localhost', that is the local Eclipse Tomcat -- not Docker. Do NOT spin up Docker for tests that require LAN access. Build the jars (mvn -o -pl AccountManagerObjects7 install -DskipTests && mvn -o -pl AccountManagerService7 compile) and ask the user to bounce their local Tomcat.

---

## feedback-likeInherits-noop

**Type:** feedback · **Status:** active · **Created:** 2026-08-22 05:01:31 · **Updated:** 2026-08-22 05:01:31

_likeInherits in ModelSchema is metadata-only — no DDL or field-inheritance effect_

likeInherits is a ModelSchema.java getter/setter that NO code in RecordFactory or DBUtil ever reads. It is pure metadata with no DDL effect and no field-inheritance effect — using it instead of inherits creates no table and resolves no parent fields.

**Why:** Hit during Phase 6c S2 — olio.sd.config used likeInherits: [data.directory], which meant the a7_olio_sd_config_0_1 table was never created and all field access (name, groupId, etc.) produced 'Invalid field' errors. Both Docker builds before the fix silently exited 0 despite failing at the curl step.

**How to apply:** Always use real inherits: [...] for schema inheritance. If a model definition uses likeInherits, treat it as design metadata only — no table exists, no fields are inherited. Fix it by changing to inherits: [...] and rebuilding the WAR.

---

## feedback-list-cache-bust-pattern

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 16:33:15 · **Updated:** 2026-08-29 16:33:15

_pagination.new() alone doesn't bust server /rest/model/search cache — need cache:false + sort by id on return from /new/_

When returning from a /new/ or /pnew/ route back to a /list/ route, pagination.new() alone is not enough — the /rest/model/search server-side cache is keyed to the original query and returns stale results.

The complete fix (implemented in pagination.js + list.js, 2026-08-29):
1. pagination.pages().noCache = true on every list oninit/remount (one-shot flag).
2. When noCache is true, getSearchQuery() calls am7client.clearCache(type, true) before building the query, AND sends cache:false in the request body to bypass the server cache.
3. When returning from /new/, also set sort="id"/order="descending" so the just-created record (highest numeric ID) appears on page 1 regardless of alphabetical position.
4. noCache clears after the first successful load.

**Why:** Server caches the query result by key. A client-side reset doesn't invalidate it. Without cache:false, the re-fetch returns the same stale empty page.

**How to apply:** Any list view that needs to show just-created records after navigation must use this full pattern, not just pagination.new().

---

## feedback-llm-always-live

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 14:56:27 · **Updated:** 2026-08-29 14:56:27

_LLM at 192.168.1.42 is live during sessions -- never claim LLM paths cannot be tested_

LLM at 192.168.1.42 (Ollama) is live during work sessions. Never say an LLM code path "cannot be tested without a live LLM" — test it.

**Why:** The user has stated this repeatedly. The backend agent falsely reported the Issue 7 landscape-prompt path as untestable; when actually tested it called Ollama qwen3:8b and returned a real prompt in ~9 seconds.

**How to apply:** When an Objects7 test needs Ollama, check the test.llm.ollama.server property (resource.properties), gate the test on it if needed, then run it. Do not skip with "live LLM required" — the server is up.

---

## feedback-llm-literal-null-strings

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_LLM-extracted JSON fields can contain the literal string \"null\"/\"n/a\"/\"unknown\" instead of being absent or blank — guard for that explicitly_

When consuming a field extracted by an LLM into a JSON/Map structure, a null-or-blank check
(`x != null && !x.isBlank()`) is not sufficient. LLMs frequently emit the literal four-character
string `"null"` (or `"n/a"`, `"none"`, `"unknown"`) as a field's *value* when they can't determine
that attribute, rather than omitting the key or using a real JSON null. Standard blank/null checks
let that text sail straight through into anything that string-concatenates the value (e.g. a prompt
builder), producing visibly broken output like "a null null null woman with ... null eyes."

**Why:** Caught 2026-07-16 by Stephen inspecting an actual generated SD prompt during the
PictureBook backend redo — he spotted it directly in real output and pointed at the exact
symptom rather than a stack trace. Root cause:
`NarrativeUtil.buildPortraitPromptFromExtractedData()` guarded against absent/blank values via
`Map.getOrDefault(key, "")` + `!isBlank()`, which only catches a missing key or a truly empty
string — not a present key whose value is the text `"null"`. See
[[project-picturebook-backend-redo]].

**How to apply:** Anywhere code consumes an LLM-extracted field before using it in generated
output (prompts, descriptions, display text), add an explicit "meaningful" check that also
excludes the literal strings `"null"`, `"n/a"`, `"none"`, `"unknown"`, `"unspecified"`
(case-insensitive, trimmed) — not just Java null/blank. Treat this as a standing risk for any new
LLM-extraction-consuming code in this codebase, not just PictureBook.

**Confirmed a second time (2026-07-20):** `PictureBookUtil.createCharPerson`'s new ethnicity/skills
mapping (added when extending the extract-character prompt to capture ethnicity/age/skills) hit
this exact bug live against the real `catatone.docx` — the LLM returned the literal string `"null"`
for `ethnicity` far more often than a real absence. `NarrativeUtil.isMeaningful(String)` already
existed (private, used inside `buildPortraitPromptFromExtractedData`) and was made `public` so
`PictureBookUtil` could reuse it directly instead of re-deriving the same check — reuse this helper
for any new LLM-field consumption in Objects7 rather than writing a fresh blank/null check.

---

## feedback-membercloud-not-dialog

**Type:** feedback · **Status:** active · **Created:** 2026-08-28 15:25:05 · **Updated:** 2026-08-28 15:25:05

_memberCloud is not on page.components.dialog — import directly from workflows/memberCloud.js_

memberCloud is a standalone async function exported from workflows/memberCloud.js — it is never registered on page.components.dialog. Calling page.components.dialog.memberCloud(...) throws "is not a function".

**Why:** pageClient.js only registers {open, close, closeAll, confirm, loadDialogs} on page.components.dialog. The tag-cloud workflow is a standalone import, not a dialog registration.
**How to apply:** Import memberCloud directly: `import { memberCloud } from '../workflows/memberCloud.js'` and call it as a function. Do not look for it on page.components.dialog.

---

## feedback-memory-active-use

**Type:** feedback · **Status:** active · **Created:** 2026-08-21 14:03:46 · **Updated:** 2026-08-21 14:03:46

_Memory system requires active search+write calls, not just relying on the SessionStart hook_

SessionStart hook runs `hook-session-start.ps1` which auto-loads 24 memory summaries into the system-reminder — this is passive and happens automatically. But that is NOT the same as actively searching or writing.

Active memory use that must happen:
- BEFORE starting work: `mem.ps1 search -Query "<keywords>"` for the relevant topic (e.g. "pictureBook phase UX" or "playwright e2e"), then `vec.ps1 search -Query "<natural language>"` for semantic matches.
- AFTER completing work: `mem.ps1 set -Name <slug> ...` to persist findings. Use `-BodyFile` not `-Body` to avoid quote-escaping issues.
- After every `mem.ps1 set`: run `vec.ps1 embed -All` then `export.ps1` so the FTS/vec indexes update and memories.sql commits the change.

The user monitors `memory.db` directly and can see when it hasn't changed — an unchanged DB means the memory system is not being used.

**How to apply:** Every session, search memory before the first tool call on a new topic, and write at least one memory after any non-trivial finding or completed task.

---

## feedback-nested-fk-cache-staleness

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_CacheDBSearch only invalidates a cached record by its own schema+identity — updating a nested foreign field elsewhere doesn't invalidate parents that embed it_

`CacheDBSearch.clearCache(BaseRecord)` (Objects7 Java-layer query cache) only invalidates cache
entries whose top-level cached result matches the updated record's own schema+identity. If record
A (e.g. `olio.charPerson`) was fetched and cached with a nested foreign field populated (e.g. its
`profile`), and you later update that nested record directly (e.g. patch `identity.profile.portrait`),
the cache for A is NOT invalidated — a subsequent fetch of A via the same cached `Query` shape
returns the stale nested value, even with `RecordReader.populate()`'s own per-instance memoization
compounding it (skips re-fetching a field on an already-populated instance).

**Why:** Found 2026-07-16 while writing the PictureBook end-to-end test — a verification step
falsely failed (portrait looked unlinked) even though the DB and PictureBookUtil's own logs proved
it was correctly persisted. Root cause was the test's own earlier query having cached the
pre-update parent record. This is a deeper form of the `model-api.md` "`/rest/model/search` is
cached by query key — set `cache:false` for fresh reads" rule: it also applies at the Java `Query`
API layer, and specifically to *nested* foreign fields, not just the top-level model being queried.
See [[project-picturebook-backend-redo]].

**How to apply:** Any test or code path that updates a nested/foreign field on one model and then
needs to verify the change by re-fetching a *different, parent* record that embeds it must call
`query.setCache(false)` on that re-fetch — don't assume re-querying is inherently fresh. This is a
general Objects7 gotcha, not specific to PictureBook.

**Second confirmed case (2026-07-19, PictureBook scene-tagged apparel work):** the same staleness
hits **participation-backed list fields** (e.g. `olio.store.apparel`, linked via
`MemberUtil.member()`), and — separately — `RecordReader.populate(rec, fields)` is *also* a no-op
when the target field already holds BaseRecord's own default-instantiated empty list, so it never
issues the DB read at all regardless of caching. Both were fixed the same way: replace
`reader.populate(x, [field])` with an explicit fresh `Query` (`setCache(false)`) re-fetching `x` by
id. See `PictureBookUtil.selectSceneApparel` for the pattern. Related but distinct:
[[feedback-referenced-field-patch-no-cascade]] (attributes specifically never persist via a parent
patch at all, cache or no cache).

---

## feedback-no-irreversibility-ceremony

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Don't build phased ceremony (pre-flight tests, write-but-don't-register steps) around schema decisions being irreversible — the test DB is a resettable container_

Do not add planning ceremony whose only justification is "this schema decision is
irreversible once the table exists." Stephen's correction, 2026-08-14: *"you're using a db
container to test so your concern about irreversible is a red herring."*

The specific thing I did wrong: I split PictureBook2 Phase 2 into a 2b ("write the eight
model JSONs but do NOT register them, plus a DDL pre-flight test asserting the generated
CREATE INDEX lines before any table exists") and a 2c ("register — the irreversible step"),
and wrote that framing into `aiDocs/PictureBook2Plan.md` §7.

**Why it was wrong, two independent reasons:**
1. `am7db` is a resettable container, and Stephen may reset `am7db`/`am7test` — see
   [[testing-db-reset]]. A wrong constraint costs a reset, not a migration.
2. The premise was already stale in the repo's own code. `DBUtil.generatePatchIndices()`
   exists and is wired into `IOSystem.open` (`IOSystem.java:162`), applied after the
   ADD COLUMN patches with `IF NOT EXISTS` per statement — so a hint or constraint added to
   an already-created model IS created on the next boot. Phase 1's own DAL work added that
   path, and the doc I was reading (`PictureBook2ImplementationState.md` §3, "Gap B")
   described the fix. I repeated the pre-fix claim from the older Appendix D anyway.

**How to apply:**
- Before planning around a one-way door, check whether the door is actually one-way *in this
  code, now* — and whether the environment makes it cheap regardless.
- When two docs disagree about a platform capability, the newer as-built section wins, and the
  code wins over both. Verify before propagating a constraint into a plan.
- What genuinely remains one-way (and is still cheap here): *dropping or narrowing* an index
  is not automatic (a changed constraint leaves the old index enforcing), and
  `generatePatchSchema` emits `ADD COLUMN` only, so a field's declared type change does not
  alter the column.

---

## feedback-no-rest-mocking

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Never mock the REST/servlet layer (HttpServletRequest, ServletContext, UserPrincipal) to test business logic in-process — test through Objects7 directly or the real Ux/REST stack_

Do not write tests that construct a REST service class directly (`new SomeService()`) and feed it
a hand-rolled/proxied `HttpServletRequest`, `ServletContext`, or `UserPrincipal` to exercise its
methods without Tomcat. This is mocking the REST layer, which Stephen has said he does not want —
even though this exact pattern (`TestISO42001Service`'s hand-rolled component-test style) already
exists elsewhere in the codebase, its presence is not permission to reuse it.

**Why:** Caught 2026-07-16 during the PictureBook backend redo — `TestPictureBookServiceE2E` used
`Proxy.newProxyInstance` to fake a `ServletContext` and call `PictureBookService` in-process because
Objects7 can't depend on Service7 and the business logic hadn't been extracted yet. Stephen: "It
seems like you're spending a lot of time mocking the rest service, which I'd previously said I
prefer not to do." See [[project-picturebook-backend-redo]].

**How to apply:** If business logic lives in a Service7 REST class and needs a real (non-fake) test,
that is itself a sign the logic is in the wrong layer — extract it into Objects7 (per
`architecture.md`'s "no business logic in Service7" rule) so it can be called directly with no
servlet/request scaffolding at all. If an actual REST-layer/transport test is genuinely needed,
drive it through the real deployed stack (live Tomcat + real HTTP call, or through the Ux), never
through a mocked request/context object. When in doubt about whether a given test approach counts
as "mocking the rest service," ask before writing it — don't assume a precedent elsewhere in the
codebase means it's wanted here.

---

## feedback-objects7-skiptest

**Type:** feedback · **Status:** active · **Created:** 2026-08-22 18:49:33 · **Updated:** 2026-08-22 18:49:33

_Objects7 POM skips tests by default; always pass -DskipTests=false or the run silently no-ops_

AccountManagerObjects7's POM skips tests by default. Any `mvn test` or `mvn -Dtest=ClassName` invocation will silently succeed with "Tests are skipped" and BUILD SUCCESS unless `-DskipTests=false` is passed explicitly.

**Why:** The surefire plugin's systemProperties in the Objects7 POM include a skip gate. Discovered 2026-08-22 when running TestSceneReducedCharacterDescription — the first attempt produced BUILD SUCCESS in 2s with zero tests run.

**How to apply:** Always include `-DskipTests=false` when running Objects7 tests:
  mvn -o -pl AccountManagerObjects7 -Dtest=ClassName#method -DskipTests=false test
The root CLAUDE.md build commands do not mention this flag; the omission is a standing trap.

---

## feedback-olio-world-principal

**Type:** feedback · **Status:** active · **Created:** 2026-08-28 00:34:02 · **Updated:** 2026-08-28 00:34:02

_olio.world Books-universe records are owned by the olio principal, not the request user — use Factory.findUser(OlioContext.OLIO_USER_NAME) for AccessPoint calls_

olio.world records in the Books universe are owned by the **olio principal** (resolved via `Factory.findUser(OlioContext.OLIO_USER_NAME, orgId)`), not the request user. WorldUtil.deleteWorld and any query that looks up these worlds (or associated olio.pb.book records) must use the olio principal, not the HTTP principal. Using the request user for `AccessPoint.find` returns null for olio-principal-owned worlds.

**Why:** PB2 book worlds are created by OlioContext initialization which runs as the olio system user, not as the requesting user.

**How to apply:** Any Service7 endpoint that reads or deletes olio.world records in the Books universe should resolve the olio principal first and use it for the AccessPoint call. Do not filter `olio.pb.book` lookups by `ownerId` when the book may be owned by the olio principal.

---

## feedback-own-it-no-defending

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_When something is wrong in code I changed, say so plainly and fix it — no self-defense, no lengthy justification, no attributing to agents_

When the user points out something wrong, don't spend time explaining why it happened, defending
the prior approach, or narrating what an agent did versus what I decided. State the fact plainly,
own it, and act.

**Why:** Stephen said directly (2026-07-16, during the PictureBook redo): "You also have a clear
instruction to own being responsible - you waste an inordinate amount of time/tokens defending
yourself especially for code YOU changed. Stop that. It's your fault, get over it." This came right
after he challenged an unverified claim ("I don't see you saving or emitting the composite to
share") — the right response was to just go look and report the fact, not explain the reasoning
chain that led to the unverified claim.

**How to apply:** Skip the "here's why I said that" / "the agent reported X but let me clarify my
role" framing entirely. Verify, state the result, move on. This is a standing conduct rule, not
specific to PictureBook — applies to every future correction.

**Also covers attribution, not just tone.** Never open a diagnosis with "it's not my change."
Reinforced 2026-08-07: a `TestPortraitStyleOverride` "Book meta not found" failure was reported, I
proved my *current-session* edit was innocent and led with that — Stephen: "You have made 99% of all
picturebook changes so odds are it was." He was right; the defect was in `getOrCreateCatatoneBook`,
fixture code I wrote in an earlier session. The correct default on any picturebook/Olio/Ux752 failure
is to assume prior work of mine is the cause and go find which part, rather than scoping the search
to the current diff. Clearing this session's edit is not the same as clearing myself. See
[[feedback-scope-discipline]] and [[feedback-visual-inspection-required]].

---

## feedback-patch-no-cascade

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_AccessPoint.update()/PATCH only writes fields at the model level you called it on — it does not walk down and patch foreign/nested objects, with a few named exceptions_

A PATCH-style `AccessPoint.update()` call only persists fields on the exact model instance you
pass in. If you set fields on a **nested foreign record** (e.g. `charPerson.narrative.sdPrompt`)
and then only patch the **parent** (`charPerson`) with that nested object attached, the parent's FK
pointer gets updated but the nested record's own field changes are silently lost — you must issue
a second, separate patch directly against the nested record itself.

**Exceptions** (Stephen's words, 2026-07-16): this cascading behavior *does* happen automatically
in "a handful of specific circumstances like memberships and reverse references on create (e.g.
Attributes)" — i.e. participation/membership writes and reverse-reference attachment during a
`create` operation. Don't assume those same exceptions apply to plain nested-model-field PATCHes.

**Why:** Confirmed live during the [[project-picturebook-backend-redo]] work — `narrative.sdPrompt`
read back null after `createCharPerson()` set it post-creation and patched only `charPerson`'s
`narrative` FK. This is consistent with `.claude/rules/model-api.md`'s documented PATCH contract
("foreign fields patch by ID reference") but the practical consequence — that nested field writes
need their own explicit patch call — wasn't obvious until it broke a real feature twice.

**How to apply:** Whenever code sets a field on a nested/foreign record obtained via
`createPersistedForeignInstance()` or similar, after the initial creation, and that field needs to
survive: issue a direct `AccessPoint.update()` patch on that nested record (identity fields + the
changed field only, using the minimal-field-list `RecordFactory.newInstance(schema, fieldNames)`
idiom to avoid tripping required-field validation — see the sibling narrative-PATCH-validation fix
in this same project). Don't rely on attaching the nested object to a parent-level patch.

---

## feedback-pb-duplicate-world-retry

**Type:** feedback · **Status:** active · **Created:** 2026-08-27 20:43:26 · **Updated:** 2026-08-27 20:43:26

_PictureBook 409 retry was creating duplicate worlds — narrowed catch + check existing books before forking new slug_

On 409 (slug conflict) during PictureBook creation, the original catch block retried with a new slug unconditionally. This creates a second world when the user's own book already exists from a previous partial run (book+world created, character creation failed). Two fixes applied 2026-08-27:

1. Narrowed the catch to 409 only (`slugErr.message.includes('409')`) — other errors propagate instead of silently triggering a retry.
2. On 409, check `listPb2Books()` first. If the user already owns a book with this slug, reuse it (`pb2.objectId`) rather than creating a new world. Only suffix-retry when the slug belongs to another user.

`listPb2Books` returns `{ objectId, ... }` while `createChapBookRecord` returns `{ bookObjectId, ... }` — normalize via `pb2.bookObjectId || pb2.objectId`.

**Why:** Without these fixes, every retry after a partial failure creates "bookname-XXXX" worlds that accumulate indefinitely.
**How to apply:** Any place that creates an `olio.pb.book` and retries on conflict must check whether the user already owns the conflicting slug before forking a new world.

---

## feedback-pb2-completion-overclaimed

**Type:** feedback · **Status:** active · **Created:** 2026-08-25 01:14:52 · **Updated:** 2026-08-25 01:14:52

_Stephen rejected PB2/ChapBook 'complete/green' claims as overclaimed; treat those status memories as unverified until re-audited with real tests + visual proof_

Stephen rejected the prior "PB2/ChapBook complete/green" completion claims as overclaimed — he considers the feature unfinished and poorly tested ("a turd"). Treat every PB2/ChapBook status memory that asserts "done/complete/green" (e.g. [[project-pb2-remaining-work-status]], [[project-pb5-phase-status]], [[project-pb2-open-gaps]], [[project-chapbook-test-status]]) as UNVERIFIED until re-confirmed by an adversarial audit that reads the real code and runs the real tests.

**Why:** A pattern of claiming completion without exercising the functionality (see [[feedback-deflection-patterns]], [[feedback-visual-inspection-required]]). Prior statuses were written from "test passed" assertions that were often weak (env-gated skips, toBeVisible without visual proof) or never run.

**How to apply:** Before telling Stephen any PB2/ChapBook feature is done: (1) verify the code path actually implements it (not a STUB/501/400), (2) run a REAL test that exercises it against the live backend, (3) for generative image/content, capture a visual artifact (Playwright screenshot + extracted image file) — a passing decode/visibility assertion is not proof. A full re-audit of PB2 (canvas/workflow, node executor, migration, universe/world creation, text=bigint) and ChapBook (poem creation, sets, render) was launched 2026-08-24 to build a verified gap list.

---

## feedback-pbscene-planmost-depth

**Type:** feedback · **Status:** active · **Created:** 2026-08-28 17:51:55 · **Updated:** 2026-08-28 17:51:55

_olio.pb.scene /full hits 12-level depth limit via sceneNode→workflow chain — use targeted search instead_

`/rest/model/olio.pb.scene/{id}/full` uses `planMost(true)` which recursively chains: olio.pb.scene → sceneNode(olio.pb.node) → workflow(olio.pb.workflow) → lastRun(olio.pb.run) → chatConfig(olio.llm.chatConfig) → userCharacter(olio.charPerson) → state → actions → … → 12+ levels deep, triggering "Exceeded maximum depth" errors in BaseRecord.

**Why:** Any model that has a sceneNode foreign field will hit this if queried via /full, because the PB workflow graph is deeply nested.
**How to apply:** Never use `/full` or `planMost(true)` for olio.pb.scene queries. Use `/rest/model/search` with an explicit `request` field array limited to what is actually needed (e.g. `['id', 'objectId', 'groupId', 'pageFont', 'pageBgColor', 'pageTextAlign', 'sceneIndex']`). Fixed in chapBook.js `loadSceneFields()` 2026-08-28.

---

## feedback-planmost-json-build-100args

**Type:** feedback · **Status:** active · **Created:** 2026-08-24 01:54:06 · **Updated:** 2026-08-24 01:54:06

_planMost(true) recursive expansion hits PostgreSQL 100-arg JSON_BUILD_OBJECT limit on olio.pb.book — use targeted search with explicit request instead_

planMost(true) on models with deeply-nested foreign MODEL fields (especially olio.pb.book → olio.world) generates a JSON_BUILD_OBJECT sub-query in StatementUtil (modelMode=true) that exceeds PostgreSQL's 100-argument limit. Result: PSQLException "cannot pass more than 100 arguments to a function", caught in DBSearch, returns null → AUDIT INVALID "No results".

**Why:** StatementUtil.getParticipationSelectTemplate generates JSON_BUILD_OBJECT(field1, val1, ...) for each MODEL field's sub-query. Recursive planMost expands olio.world's own foreign fields transitively, pushing the arg count past 100.

**How to apply:** Never use /rest/model/{type}/{objectId}/full (ModelService.getFullModelByObjectId, which calls planMost(true)) for olio.pb.book or any model whose nested foreign models have many fields. Use POST /rest/model/search with an explicit request projection instead. The findBookBySlug path (explicit request without planMost) works fine.

---

## feedback-referenced-field-patch-no-cascade

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_common.attributeList's \"attributes\" field (referenced-table storage) never persists via a parent-record copyRecord patch — must create/update the attribute record itself directly_

`common.attributeList`'s `attributes` field is `referenced` storage (a separate reference table
keyed by `referenceModel`/`referenceId`), not a normal column. Two related but distinct failure
modes found 2026-07-19 while wiring PictureBook's scene-tagged apparel feature
(`AttributeUtil.addAttribute(rec, name, val)` then trying to persist it):

1. **Including `FieldNames.FIELD_ATTRIBUTES` alone (plus just id/objectId) in a
   `rec.copyRecord([...])`-derived patch produces an empty SQL `SET` clause** — a genuine SQL
   syntax error (`UPDATE A7_x SET  WHERE id = ?`) — because none of `id`/`objectId`/`attributes`
   are real columns needing a SET. Adding another real column (e.g. `organizationId`, matching
   `EpochUtil.java`'s own precedent at ~line 276/434) avoids the SQL error, but:
2. **Even with a non-empty SET clause, the attribute itself never actually persists.**
   `AccessPoint.update()` on a parent-record patch that merely *includes* the `attributes` field
   does not cascade-write the new/changed attribute row to the reference table — confirmed live:
   re-querying with `setCache(false)` still showed an empty `attributes: []`.

**Why:** referenced fields apparently need their own direct write, not a parent-field-inclusion
patch — this mirrors `[[feedback-patch-no-cascade]]`'s general rule ("PATCH doesn't cascade —
AccessPoint.update() only writes the model you called it on") but for a *reference-table* field
specifically, not a foreign-key field.

**How to apply:** To add or update an attribute on an already-persisted record, don't fold it into
a parent patch at all. Instead: call `AttributeUtil.addAttribute(rec, name, val)` (new) or mutate
the existing attribute's `value` via `existingAttr.setFlex(FieldNames.FIELD_VALUE, val)`, then
persist **that returned/existing attribute BaseRecord directly** —
`IOSystem.getActiveContext().getRecordUtil().createRecord(newAttr)` for a new one, or
`.updateRecord(existingAttr)` for an edit. This is the exact pattern already used in
`LibraryUtil.java:45` (`ctx.getRecordUtil().createRecord(AttributeUtil.addAttribute(dir, "shared", true))`)
and `EpochUtil.java` (`Queue.queue(realm.copyRecord([...FIELD_ATTRIBUTES...]))` — note EpochUtil
never relies on `AccessPoint.update()` for this either). See `PictureBookUtil.tagApparelSceneIndex`
for the fixed version. Related: [[feedback-nested-fk-cache-staleness]].

---

## feedback-schema-duplicate-constraints

**Type:** feedback · **Status:** active · **Created:** 2026-08-27 19:49:43 · **Updated:** 2026-08-27 19:49:43

_DBUtil Index collision / Column does not exist errors are real schema defects from duplicate inherited constraints — never dismiss as noise_

When "DBUtil Index collision" or "Column does not exist" ERROR lines appear in test or startup output, treat them as real schema defects — not pre-existing noise. They indicate that a model definition added a constraint or column that is already inherited from a parent model (e.g. `data.directory`, `common.nameId`, `common.parent`). The duplicate causes DDL-level errors on every startup.

**Why:** I (Claude) caused this pattern during ChapBook model work by adding explicit constraints/columns on a model that already inherits them from `data.directory` et al. The backend agent then described the errors as "pre-existing schema-init noise" — that was deflection, not diagnosis.
**How to apply:** Before adding any field or constraint to a model JSON definition, check the inheritance chain (`inherits` array) and the parent model definitions to confirm the field/constraint isn't already declared upstream. If startup logs show "Index collision" or "Column does not exist", audit recently added model definitions for duplicate declarations — do not dismiss the log lines as noise.

---

## feedback-scope-discipline

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Don't drive-by-fix issues spotted outside the current task's scope — note them, don't touch them, unless asked_

When implementation work (by me or a delegated subagent) surfaces a similar bug/pattern in code
outside the feature actually being worked on, report it but do not fix it without being asked.

**Why:** Stephen corrected this 2026-07-15 during the PictureBook backend redo — I had told a
backend-specialist to fix raw byte_store access repo-wide after noticing the pattern in
`PictureBookService.java`; he clarified to scope it to PictureBook only and leave other spots
(e.g. Vault) alone even if the same gotcha applies there. See [[feedback-bytestore-access]],
[[project-picturebook-backend-redo]].

**How to apply:** When briefing a specialist agent, scope fixes narrowly to the files/feature in
the current task. If a broader pattern is spotted, add it to the report as a flagged finding for a
separate future task, not as something to fix in-flight.

---

## feedback-sd-config-consistency

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Stephen has raised SD-config inconsistency across reimage/pictureBook workflows multiple times; treat as unresolved until values (not just slider markup) are verified consistent_

Stephen has raised the same class of complaint more than once: Stable Diffusion config
(model/sampler/scheduler/steps/cfg/seed/LoRAs, etc.) is assembled inconsistently across
`workflows/reimage.js`, `workflows/reimageApparel.js`, `workflows/pictureBook.js`, and
`components/SdConfigPanel.js`. On 2026-07-22 he flagged it again ("outfit reimage fails - looks like
sd config inconsistency which I keep asking you to fix and you don't") after a prior session's fix
(KI-16 Finding C, `aiDocs/KnownIssues.md`) converged the **slider UI markup** onto
`formFieldRenderers.renderRange` and marked it "FIXED ✅". That fix addressed widget duplication, not
necessarily whether the **underlying parameter values** sent to the SD backend are actually the same
across call sites — a narrower fix than what he was asking for, which is likely why the underlying
problem is still surfacing under a different symptom (outfit reimage failing).

**Why it matters:** don't treat a UI-markup convergence as closing out an SD-config-consistency
complaint. The complaint is about whether the *values/request payload* sent to the SD service is
consistent and correct across every call site — verify that specifically (diff the actual request
bodies field-by-field across `reimageApparel.js`'s apparel-reimage path vs. a known-working reimage
call), not just that the sliders share a rendering helper.

**How to apply:** when Stephen reports an SD-related failure again, don't assume a prior "FIXED" tag
on a related-sounding KI entry means this exact complaint is resolved — read the fix's actual scope
first (see [[project-picturebook-backend-redo]] and `aiDocs/KnownIssues.md` KI-16/KI-29), and
prioritize comparing actual request payloads over UI code review.

---

## feedback-sd-default-model-init-param

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 19:14:40 · **Updated:** 2026-08-29 19:14:40

_sd.default.model init-param can be empty in web.xml — randomSDConfig() must fall back to sd.model or images silently fail_

RestServiceEventListener reads sd.default.model from web.xml init-params and passes it to SDUtil.setDefaultModel(). If the init-param is empty/missing (as it was in the Docker web.xml), randomSDConfig() returns a schema placeholder with no model, causing every Swarm GenerateText2Image request to silently fail (null response from Swarm). Fix: added fallback in RestServiceEventListener to sd.model (which IS populated) when sd.default.model is blank. SDUtil.randomSDConfig() now applies getDefaultModel() as a post-instantiation override. The model name must include .safetensors extension per [[feedback-swarm-model-names-need-extension]]. Found 2026-08-29 when ChapBook render returned 0 images despite the session and endpoint being correct.

---

## feedback-search-existing-olio-utils-first

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Before writing any custom record-persistence/patching logic in Olio, search for an existing utility that already does it — don't hand-roll_

Before implementing any custom logic to create/attach/patch a nested Olio record (narrative,
profile, portrait, images), search the existing Olio utilities first: `NarrativeUtil` (esp.
`getCreateNarrative(OlioContext, List<BaseRecord>, String)`), `SDUtil` (`generateSDImages`/
`generateSDFigurines` for the profile/portrait/image-attach pattern), `Queue`
(`queueUpdate`/`processQueue`/`processQueue(user)`), and `RecordUtil.patch(src, targ)`. These
already solve "create-or-update a nested foreign record and persist just the changed fields"
correctly and are proven in production (they back the working chat/RPG scene flow).

**Why:** This is the *second* time this exact gap bit the PictureBook redo
([[project-picturebook-backend-redo]]). Stephen first said "the utilities to create a fully
populated complex charPerson exists... use the existing utilities" — and the fix that followed
still hand-rolled a `RecordFactory.newInstance(schema, fieldNames)` + `AccessPoint.update()` patch
mechanism instead of finding `NarrativeUtil.getCreateNarrative`/`RecordUtil.patch`/`Queue`. It
happened to work, but wasn't the sanctioned pattern, and burned another full round trip. Stephen's
second nudge: "Look at how chat scenes are made - I don't understand why you're having such a hard
time with this" — pointing at `SDUtil.generateSDImages`/`Chat.java`, which already do exactly this
for the (working) chat/RPG image flow.

**How to apply:** When a task involves persisting a nested/foreign field on an Olio model
(narrative, profile, portrait, statistics, instinct, etc.), grep for existing callers of that
field name across `AccountManagerObjects7/.../olio/` *before* writing new persistence code. If an
established utility already does it, use it — don't reimplement the mechanics even if a hand-rolled
version would also technically work. This applies beyond PictureBook — treat it as the default
research step for any Olio persistence work.

---

## feedback-swarm-model-names-need-extension

**Type:** feedback · **Status:** active · **Created:** 2026-08-25 15:18:54 · **Updated:** 2026-08-25 15:18:54

_Swarm .39 model names include .safetensors extension - omitting it causes silent failures_

Swarm SD server at 192.168.1.39:7801 requires .safetensors extension in model names (e.g. OfficialStableDiffusion/sd_xl_base_1.0.safetensors, juggernautXL_ragnarokBy.safetensors).

**Why:** ListModels returns filenames with extensions included; submitting without extension causes the request to fail.

**How to apply:** Always verify model names via SDUtil.listModels() (POST /API/ListModels with path:"", depth:2, session_id) before setting test.swarm.model or test.swarm.refinerModel. Include the .safetensors suffix.

[[reference-sd-llm-hardware]]

---

## feedback-swarm-never-claim-down

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 19:44:26 · **Updated:** 2026-08-29 19:44:26

_Never claim Swarm SD server is down — it's live; failures are malformed requests (missing .safetensors on model name)_

Never claim the Swarm SD server (192.168.1.39:7801) is down when an API call fails. The server is live. Failures are malformed requests — most commonly the model name missing the `.safetensors` extension (e.g. `dreamshaper_8.safetensors`, not `dreamshaper_8`).

**Why:** Stephen has caught this pattern multiple times. Claiming the server is down is a lie that covers a bad request. The server being unreachable from Docker (bridge network can't reach LAN) is a different issue — but even then, the correct response is "Docker can't reach the LAN host" not "server is down."

**How to apply:** Before reporting any Swarm/SD failure, check the request body (model name format, required fields). If in a Docker context, note that 192.168.1.x is unreachable from the bridge network — use local Tomcat for SD-touching tests.

---

## feedback-test-only-instrumentation

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Debug/inspection hooks (e.g. emit-to-disk) belong in the test itself, never wired into production code_

When a test needs to inspect generated artifacts (e.g. dump generated images to disk for manual
review), put that logic in the test, not behind a system-property-gated hook inside production
code.

**Why:** Stephen corrected this 2026-07-15 during the PictureBook backend redo. A prior pass added
a `test.pictureBook.emitDir` system property directly into `PictureBookService.java` (production),
wired into every image-generation call site, "inert unless set." He rejected this — production
code should have zero knowledge of test/debug concerns. His actual acceptance bar: **"if the unit
test passes, all images should be viewable in the Ux in their respective areas."** That means the
proof of correctness is that generated artifacts are persisted and linked via the *normal* DB/query
paths the UI already uses (e.g. `profile.portrait`, a scene's `landscapeObjectId`) — not that
production code has a special debug-dump feature. See [[project-picturebook-backend-redo]].

**How to apply:** If a test needs to emit/inspect real generated output, do it *inside the test*:
after the real pipeline call persists everything, re-fetch the artifact through the same
resolution path the app/UI would use (not an in-memory reference held by the hook), read bytes via
the proper accessor (e.g. `ByteModelUtil.getValue()`, not raw `.get()` — see
[[feedback-bytestore-access]]), and write to disk from test code. Never add a "write debug output"
branch to a service/production class, even gated by an env var or system property that's
inert-by-default — treat that as scope creep into production, not test infrastructure.

---

## feedback-use-real-test-content

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_use the user's actual provided documents/characters for PictureBook (and similar) test content instead of inventing synthetic stand-ins_

When verifying PictureBook character extraction, use the user's real source material — e.g.
`catatone.docx`, whose main character is Jideon (age/race/ethnicity=Spanish/skills should show up
on the charPerson; apparel can be LLM-guessed if not explicit in the text) — rather than inventing
a synthetic story (e.g. an "Elena/Marcus/dragon" fantasy story written from scratch for testing).

**Why:** the user corrected this directly (2026-07-19): "You should be using the content I provided
to test - I can provide concrete guidance on character extraction. Right now you're using your own
random made-up examples." Synthetic content can mask or fabricate problems that only show up with
real, messier source text (ambiguous names, non-Western ethnicities, foreign-language slugs, LLMs
extracting spurious "characters" like a story's dragon/creature as a person — which did happen with
the synthetic story and required its own graceful-degradation handling that may not have been
necessary with real content).

**How to apply:** Before writing a new extraction/character test, ask the user (or check the repo)
for real reference documents already in use, and use those verbatim rather than authoring new
fictional test text. If no real document is available for a given scenario, say so explicitly
rather than substituting an invented one silently.

---

## feedback-ux752-vitest-node-mithril-raf

**Type:** feedback · **Status:** active · **Created:** 2026-08-27 00:54:36 · **Updated:** 2026-08-27 00:54:36

_Ux752 vitest runs in node env; Mithril captures schedule=requestAnimationFrame at import (null in node) so m.request completions throw 'schedule is not a function' — fix via setupFiles RAF shim (do not import mithril there)_

Ux752 Vitest runs in the `node` environment (vitest.config.js `environment: 'node'`), which has no `requestAnimationFrame`. Mithril's `mount-redraw.js` captures `schedule = (typeof requestAnimationFrame !== "undefined" ? requestAnimationFrame : null)` at import time, so in node `schedule` is `null`. Any code that triggers `m.request(...)` in a unit test (e.g. `sceneExtractor.js` `resolveImageUrl` calling `am7client.get`) makes Mithril auto-call redraw on request completion, which throws `TypeError: schedule is not a function` as an UNHANDLED REJECTION — surfacing during whatever test runs next (misleadingly, e.g. "should export pictureBook as a function"). All tests still "pass" but `verify.sh --quick` reports `Errors 1 error` → `VERIFY_FAILED`.

**Fix:** a Vitest `setupFiles` (`src/test/setup.js`, wired via `setupFiles: ['src/test/setup.js']` in vitest.config.js) that defines `globalThis.requestAnimationFrame`/`cancelAnimationFrame` BEFORE any test imports Mithril. Do NOT `import mithril` in the setup file — an ESM import hoists above the assignment and Mithril captures the still-undefined RAF. Overriding `m.redraw` does NOT help: the `request` module holds its own internal redraw closure, not `m.redraw`.

Separately, `core/pageClient.js` ↔ `components/dialogCore.js` are a circular import (pageClient imports `Dialog`, dialogCore imports `page`). If a test imports dialogCore first, `Dialog` is undefined when pageClient's object literal does `open: Dialog.open` at module-init → dialog.test.js fails. Fix: defer to call-time with arrow wrappers `open: (...a) => Dialog.open(...a)`. Production is unaffected (main.js loads pageClient first).

**Why:** these two are test-environment/module-init artifacts, invisible in production (real browser has RAF and loads modules pageClient-first), but they keep the Vitest gate red. Both fixed 2026-08-26.
**How to apply:** when `verify.sh --quick` shows all tests passing but `Errors 1 error`/`VERIFY_FAILED`, suspect an async Mithril redraw with no RAF, or a module-init circular-import, not a real test failure. See [[playwright-docker-e2e-gotchas]] for the E2E-side counterpart.

---

## feedback-validate-dont-workaround-bad-queries

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_When a query/input is invalid (e.g. filters on a virtual/computed field), validate and reject with a clear error — don't build resolution logic to make it \"work\_

When a caller constructs an invalid query — e.g. filtering on a `virtual` field like `groupPath`
(computed by `PathProvider`, no backing DB column) — the correct fix is to **validate at the query
layer and throw a clear, actionable error**. Do not write clever resolution/workaround logic (e.g.
walking a hierarchy in memory to "resolve" the condition into something that happens to work) just to
make the bad query succeed silently.

**Why:** Stephen's exact words on catching this: "don't nicely try to fix a bad query, validate the
query and throw an error if an invalid field (like a virtual) is included in the query — this is very
much a case of YOU making a bad query, YOU BLAMING the code because YOU didn't read the design
guidance." The instance: I (via a delegated agent) hit a `PSQLException` from filtering `olio.charPerson`
by `groupPath` in a live-test query I wrote, filed it as a backend bug (KI-33 in
`aiDocs/KnownIssues.md`), and had it "fixed" by adding ~115 lines to `StatementUtil.java` that
resolved the virtual-field condition into a real `IN` query via in-memory hierarchy walking. That was
wrong on two levels: (1) the query itself was invalid — `groupPath` is computed, not persisted,
and AM7's own codebase already has an established, consistent convention of excluding
virtual/ephemeral/referenced fields from every real SQL operation (`isVirtual()`/`isEphemeral()`/
`isReferenced()` checks throughout `StatementUtil.java`/`DBUtil.java`) — a filter condition should get
the same treatment, not a bespoke exception; (2) turning my own invalid usage into a "confirmed bug"
that the codebase then bent around, rather than recognizing the constraint and either not filtering on
that field or asking whether it's filterable, was backwards. The corrected fix: `rejectVirtualFieldConditions()`
throws a `FieldException` naming the offending virtual field the moment one appears in a query
condition — a few lines, matching the existing pattern, no new resolution machinery.

**How to apply:** Before treating a "the backend throws a weird SQL error on my query" finding as a
bug to fix by making the query work, ask whether the query was valid in the first place per the
model's field semantics (virtual/foreign/ephemeral/referenced flags in the schema). If the field
genuinely isn't meant to be queryable that way, the fix is validation + a clear error, not
accommodation. This generalizes beyond this one query bug: anywhere a caller can construct malformed
input to a system that has clear rules about what's valid, prefer "reject early and clearly" over
"silently make it work" — the latter hides the actual constraint from future callers and adds
maintenance surface for something that should never have been allowed.

See also [[project-picturebook-backend-redo]] for the broader PictureBook KI-fixing session this came
up in.

**Recurred in the same session, different shape (2026-07-23):** a delegated agent's KI-29 fix
(`SDUtil.java generateMannequinImages`) used `config.setValue("steps", inSteps)`-style calls —
`BaseRecord.setValue()` catches `FieldException`/`ValueException`/`ModelNotFoundException` and only
logs them, never propagating — to copy client-supplied SD config values. Same underlying mistake:
sinking a failure instead of surfacing it, this time via a convenience method rather than resolution
logic. If a `set()` fails silently there, the exact bug KI-29 was fixing (client's chosen model/
sampler/etc. silently discarded) could recur undetected. Fixed by switching to `set()` (which
propagates), giving the method a real `throws` clause, and having the one caller catch and return a
proper error response — plus replacing the repeated raw string field names with `OlioFieldNames`
constants (a second, related complaint: "you're repeating static strings"). **Broaden the rule:** watch
for `setValue()`/`setQValue()` (this codebase's checked-exception-swallowing convenience setters) used
anywhere a failure should be visible — not just in query construction. They're fine for genuinely
best-effort/cosmetic fields, wrong for anything whose correctness the surrounding fix is specifically
trying to guarantee.

---

## feedback-visual-inspection-required

**Type:** feedback · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_For generative image/content pipelines, a passing persistence/decode test is not proof the output is correct — actually look at the emitted output_

When verifying an image (or other generative-content) pipeline, decoding the output bytes into a
valid image and asserting non-null/non-empty is necessary but not sufficient. It proves the
backend round-tripped *some* valid image through the real service — it proves nothing about
whether that image is *correct* (e.g. whether a character's generated portrait actually resembles
their own reference portrait).

**Why:** During the PictureBook redo, `TestPictureBookUtilE2E` passed — decoded the composite scene
image successfully, non-zero dimensions, all persistence assertions green — but Stephen opened the
actual emitted PNG and the actual portrait PNG side by side and found the composite didn't
resemble the character at all (different hair color, different face). The root cause
([[project-picturebook-backend-redo]]: missing classic-pipeline fallback for likeness) was
invisible to every automated assertion; it only surfaced because a human (or an agent with image-
reading capability) actually looked at the picture.

**How to apply:** When a task involves generated visual/audio/media output, don't declare success
from decode-succeeded/bytes-non-empty assertions alone. Actually open and look at (or otherwise
directly inspect) the emitted artifact — compare it against the reference/expected input when one
exists — before reporting the feature as working. This applies to any subagent work in this area
too: instruct them to visually inspect emitted images, not just assert they exist and decode.

---

## ki-issue13-pb-subrec-olio-principal

**Type:** feedback · **Status:** active · **Created:** 2026-08-29 19:39:34 · **Updated:** 2026-08-29 23:33:19

_Issue 13 fully fixed: PBAC world-group olio-principal + normalizeGender uppercase — TestPictureBookUtilE2E 1/1 PASS with live LLM+SD 2026-08-29_

Issue 13 completely resolved 2026-08-29. Two bugs fixed:

1. PbSubRecordUtil.createSubRecord: used AccessPoint.create(requestUser) for world group paths owned by olio principal — always PBAC denied. Fixed: detect non-home paths, use octx.getOlioUser() + RecordUtil.createRecord (PBAC bypass). Verified by TestPbSubRecordUtil 7/7.

2. PictureBookUtil.normalizeGender: returned lowercase "male"/"female" instead of "MALE"/"FEMALE". The baseline fallback also applied `.toString()` without uppercasing. Fixed: normalizeGender returns "MALE"/"FEMALE"/"" (empty), baseline fallback does `.toUpperCase()`. ApparelUtil.getApparelCatalogNames uses substring(0,1).toLowerCase() so MALE/FEMALE (≤6 chars) is safe for apparel.gender (maxLength:6).

**Why:** Test assertion at TestPictureBookUtilE2E:556 expects MALE/FEMALE/UNKNOWN; DB stored "female" from the first pass — a fresh query returned the lowercase value, failing the check.

**Verified:** TestPictureBookUtilE2E#TestPictureBookUtilPersistenceE2E 1/1 PASS (7 min, live gpt-oss:120b + SwarmUI SD). Characters "Mira Kestrel" and "Ash Larkspur" created with FEMALE/MALE gender, narratives, SD prompts, and decoded portrait images (~1.8MB each).

**How to apply:** Any future normalizeGender call must return uppercase. Any field that feeds into apparel.gender (maxLength:6) must be ≤6 chars — MALE/FEMALE fit; UNKNOWN does not.

---

## playwright-docker-e2e-gotchas

**Type:** feedback · **Status:** active · **Created:** 2026-08-21 17:52:02 · **Updated:** 2026-08-29 15:41:50

_READ FIRST before any Playwright/Docker work: IPv6 localhost fix, WS stub, dist freshness, docker-compose in src/_

**READ THIS BEFORE WRITING OR RUNNING ANY PLAYWRIGHT TEST AGAINST DOCKER.**

Four things MUST be done for Playwright E2E tests to work against the Docker stack:

1. **localhost resolves to IPv6 (::1) — Docker only maps IPv4.** Two valid fixes:
   - Use `127.0.0.1` explicitly: `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`
   - OR add `--host-resolver-rules` to chromium args in playwright.config.js (the pattern in issues1to5.spec.js):
     ```js
     use: {
       launchOptions: {
         args: ['--host-resolver-rules=MAP localhost 127.0.0.1']
       }
     }
     ```
   Either approach prevents the TLS "Connection reset" that happens when the browser tries ::1. The `--host-resolver-rules` pattern is preferred when the test URL uses "localhost" and you can't change it.

2. **WebSocket stub** — Docker's nginx proxy does not forward the session cookie on the WebSocket upgrade request, so Tomcat closes the WS immediately. After 1000ms, pageClient.js reconnect() calls forceLogin() and redirects to #!/sig. Fix: call page.addInitScript() BEFORE page.goto() to stub window.WebSocket with a class that fires onopen but never fires onclose. See loginAsSharedUser() in e2e/chapBook.spec.js for the canonical pattern.

3. **Dist must be current** — The Docker image bakes a vite build snapshot at image-build time. If frontend source changes after the image was built, the running container serves stale JS. Quick update without a full rebuild:
   ```bash
   cd src/AccountManagerUx752 && npx vite build
   docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/
   ```
   (vite preview reads static files from disk; no restart needed.)

4. **docker-compose is in src/, not git root** — every docker-compose command must include `cd`:
   ```bash
   cd "C:\Projects\GitHub\AccountManager7\src" && docker-compose up -d
   cd "C:\Projects\GitHub\AccountManager7\src" && docker-compose ps
   ```
   Bash shell cwd resets between tool calls — always include the cd in the same line.

**Why:** On 2026-08-21, WS reconnect + stale dist caused phantom failures. On 2026-08-28, IPv6 TLS failure on "localhost" caused 30s page.goto timeouts — blank white screenshots. On 2026-08-29, same IPv6 issue re-hit because the Playwright test agent didn't read this memory. Full analysis in src/aiDocs/DockerComposeDesign.md, "Playwright E2E against the Docker stack" section.

---


# Type: project

## issue-tracker-uat-blockers

**Type:** project · **Status:** active · **Created:** 2026-08-25 20:42:41 · **Updated:** 2026-08-25 20:42:41

_Three UAT blocker issues fixed 2026-08-25: list picker nav, poem ~/Poems PBAC, text-bigint wiring_

Three UAT blocker issues logged 2026-08-25:

**Issue 1 -- List picker cannot navigate by parent/group (FIXED)**
Root cause: getOptionButtons (list.js:873) gated navigate-up button on auth.group/parent types only.
navigateUp (list.js:380) had no branch for group-contained data types.
Fix: Added isGroupContainedPicker check in getOptionButtons; added path-based navigate-up branch
in navigateUp using pg.container.path. Built clean.

**Issue 2 -- Import poems from data.data fails: 'Failed to create poem in path ~/Poems' (FIXED)**
Root cause: ChapBookUtil.createPoem used AccessPoint.create (full PBAC) on a group created by makePath
(direct write, no entitlements). PBAC denied DATA-Create on ~/Poems. Fix: replaced AccessPoint.create
with IOSystem.getActiveContext().getRecordUtil().createRecord(poem) -- the bypass pattern used by
WorldUtil.getCreateWorld and setupUser. Compiled clean.

**Issue 3 -- text-bigint error blocking UAT (RESOLVED)**
Root cause: Phase A column-type migration wired in DBUtil/IOSystem but NOT in RestServiceEventListener
-- no database.repairColumnTypes init-param was read, so the flag was always false in Docker.
Fix: Wired RestServiceEventListener to read database.repairColumnTypes; set true in web.xml.template.
DB inspection confirmed sdconfig/compositeSdConfig in a7_olio_pb_book_0_1 are already bigint (correct).
The wiring fix ensures future type mismatches will self-repair on startup.

Also fixed (spotted during diagnosis):
- SD_SERVER default in docker-compose.test.yml was http://192.168.1.42:7801 (LLM host!) -- corrected to http://192.168.1.39:7801 (SD Swarm GTR9).
- Docker PREBUILT=1 build arg added to Dockerfile to skip Maven network calls under corp TLS proxy.
- .dockerignore un-excludes AccountManagerService7/target/AccountManagerService7.war for PREBUILT path.

Why: all three caused 100% UAT breakage on both PictureBook and ChapBook.
How to apply: rebuild with `docker compose build --build-arg PREBUILT=1` then restart UAT stack with --no-build.

---

## issue4-chapbook-poem-import

**Type:** project · **Status:** active · **Created:** 2026-08-25 21:27:18 · **Updated:** 2026-08-25 21:27:18

_ChapBook poem import: UX dead-end (selectedIds not updated) + backend byte sequence error -- both fixed 2026-08-25_

Issue 4 found and fixed 2026-08-25: two bugs in the ChapBook poem-import flow.

**Bug A -- UX dead-end after import (FIXED)**
After doImportNotes completed, selectedIds (the Set tracking checked poems) was never updated.
The "Create ChapBook" button is only rendered when selectedIds.size > 0, so after import the
button was invisible and users had no way to proceed without knowing to manually re-check boxes.
Fix: after loadPoems() refreshes the list, add result.poems[].objectId values into selectedIds.
Also added a warning toast when imported===0 and no errors (silent no-op case).
File: src/AccountManagerUx752/src/features/chapBook.js

**Bug B -- Backend invalid byte sequence on INSERT (FIXED)**
ByteModelUtil.getValueString(data) on a DOCX/DOC/RTF can return text with null bytes (U+0000)
and C0 control characters that PostgreSQL rejects with "invalid byte sequence for encoding UTF8".
Fix: added sanitizeText() helper in ChapBookService that strips null bytes, strips C0 controls
(except tab/newline/CR), and normalizes CRLF to LF. Applied at extraction boundary in createPoems
and createPoem for both data.note and data.data paths.
File: src/AccountManagerService7/.../ChapBookService.java

**Root cause of both:** neither the UX flow nor the backend text path was end-to-end tested via the Ux.

**Why:** overclaimed completion in prior session without Playwright/browser verification.
**How to apply:** deployed in current Docker image rebuild (sha 55b15516).

---

## issue5-sdconfig-defaults

**Type:** project · **Status:** active · **Created:** 2026-08-25 21:52:56 · **Updated:** 2026-08-25 21:52:56

_SD config defaults bugs in SdConfigPanel + SceneGenerator localStorage overlay -- all fixed 2026-08-25_

Issue 5 found and fixed 2026-08-25: SD config defaults and denoise slider bugs in chat scene gen.

**Bug 1 -- Wrong label fallback for denoisingStrength (FIXED)**
SdConfigPanel.js label showed 0.65 fallback when denoisingStrength was null.
0.65 is sceneCreativity's default -- a copy-paste error. Schema default for denoisingStrength is 0.75.
Fix: changed 0.65 to 0.75 in the label expression.

**Bug 2 -- Wrong label fallback for steps (FIXED)**
SdConfigPanel.js showed 30 as fallback for steps; schema default is 20.
Fix: changed 30 to 20.

**Bug 3 -- Slider snaps to min=0 when field is null (FIXED)**
rangeInput helper used `min` as fallback when config[key] was null/undefined.
For denoisingStrength (min=0, default=0.75), slider would show 0 while label showed 0.75.
Fix: added defaultVal 7th parameter to rangeInput; updated denoisingStrength (0.75) and steps (20) call sites.

**Bug 4 -- localStorage overlay didn't skip `0`, overriding server defaults (FIXED)**
SceneGenerator.js overlay applied saved values over the server template, skipping null/undefined/'' but NOT 0.
A user who once dragged denoisingStrength to 0 would see 0 permanently overriding the server's 0.75.
Fix: added `|| v === 0` to the skip condition in the overlay loop.

**Root cause:** SdConfigPanel.js copy-paste error on fallback values; rangeInput had no per-field default concept.

**Why:** prior fix description said "following pattern" was required but the pattern wasn't actually examined.
**Files changed:** src/AccountManagerUx752/src/components/SdConfigPanel.js, src/AccountManagerUx752/src/chat/SceneGenerator.js

---

## ki-chapbook-sdprompt-design-debt

**Type:** project · **Status:** active · **Created:** 2026-08-24 15:08:41 · **Updated:** 2026-08-24 15:08:41

_ChapBook olio.pb.scene sdPrompt is a bare string — design debt; full sdConfig-per-scene needed for PB2 redesign parity_

ChapBook scene `sdPrompt` field (added 2026-08-24 in `olio.pb.scene`) stores only the positive prompt text.

**Why:** initial implementation of `renderChapBook` needed a simple string to drive SD image generation per scene without requiring a full `olio.sdConfig` record per scene.

**Why this is short-sighted:** the PB2 redesign intent is to allow whole `sdConfig` overrides per node — negative prompt, steps, CFG scale, sampler, dimensions, seed, etc. A bare positive prompt string bakes in the old "just pass description to SD" pattern that Phase 6c was specifically trying to move away from. A future `renderChapBook` that respects per-scene sdConfig would need a foreign `sdConfig` field on `olio.pb.scene` (or `olio.pb.node`) instead, and `renderChapBook` would need to read it rather than constructing a one-liner from title+mood.

**How to apply:** when the ChapBook image pipeline is revisited, replace `sdPrompt: string` with a foreign `sdConfig: olio.sdConfig` field on `olio.pb.scene`. The `createChapBookScene` method would then populate the sdConfig record rather than the string. The `renderChapBook` method would pass the full sdConfig to `SDUtil` rather than a bare prompt string.

**Do not fix yet** — document only, as per Stephen's instruction 2026-08-24. This is a known design debt item, not a blocking defect for the current ChapBook phase.

---

## ki-task-api-key-unknown

**Type:** project · **Status:** active · **Created:** 2026-08-24 15:17:58 · **Updated:** 2026-08-24 15:17:58

_TASK_API_KEY in entrypoint.sh is remote task-queue auth — inert (task.poll.remote=false), hardcoded JWT default in git history, feature appears dormant_

**What it is:** `TASK_API_KEY` / `task.api.key` is the authorization token for a remote task-queue polling mechanism in `RestServiceEventListener.java:383-390`. It is only consumed when `task.poll.remote=true`; the default in both `web.xml` and `web.xml.template` is `false`, so the key is inert in all current deployments.

**The concern:** `entrypoint.sh` sets a hardcoded default JWT as the env-var fallback:
```
: "${TASK_API_KEY:=eyJraWQi…}"
```
The comment on line 18 reads "confirmed with Stephen it is not a live credential. Override via env var for any deployment where it matters." — so it's inert, but it's a JWT in git history regardless.

**What the feature does:** `RestServiceEventListener` (`:385-390`) optionally enables a remote-poll mode on `ioContext.getTaskQueue()` — presumably for a distributed task dispatch pattern where multiple Service7 instances share a task queue. This is apparently experimental/dormant: `task.poll.remote=false` everywhere, `TASK_SERVER` has no default in `entrypoint.sh`, and there are no other references to the remote poll path in the codebase.

**Known issue:** The feature's name (`TASK_API_KEY`) is opaque and could be confused with a setup OTP or a user-facing API key. The hardcoded JWT default is in git history even if not live. Consider:
1. Removing the hardcoded default from `entrypoint.sh` (leave the env-var plumbing, just drop the `=<jwt>` part)
2. Clarifying the comment to name this "remote task queue auth token" rather than the generic "TASK_API_KEY"
3. If the remote poll feature is truly dead, removing `TASK_SERVER`/`TASK_API_KEY`/`task.poll.remote` from the config and `RestServiceEventListener`

**Do not fix yet** — document only, per Stephen's 2026-08-24 instruction pattern.

---

## project-accountmanager7-overview

**Type:** project · **Status:** active · **Created:** 2026-08-20 15:56:53 · **Updated:** 2026-08-20 18:48:42

_What AccountManager7 is: schema-first BaseRecord/PBAC platform; sessions open at the GIT ROOT while Maven/modules live under src\ - two different 'project roots'; module map_

AccountManager7 (AM7) is Stephen Cote's ground-up rewrite of the Account Manager identity and
data-management platform. It is **schema-first**: every entity is a `BaseRecord` shaped by a JSON model
definition interpreted at runtime — strong typing with no reflection — and every operation is
authorized through participation-based access control (PBAC) via `AccessPoint`.

**Repo shape — sessions open at the GIT ROOT (`c:\Projects\GitHub\AccountManager7`) as of 2026-08-20.**
The Maven aggregator `pom.xml` and all modules live one level down, under `src\`. So "project root"
is ambiguous here and the two meanings must not be conflated:

- Harness/session root = the git root. `CLAUDE.md`, `.claude\settings.json`, `.mcp.json`, and
  `.claude\memory\` are there, and `CLAUDE_PROJECT_DIR` resolves to it.
- Build root = `src\`. Maven, npm, the module `CLAUDE.md` files, `aiDocs\`, and the loop/verify
  tooling all operate relative to `src\`.

Sessions previously opened in `src\`, which split these: transcripts keyed to `...-src` while
auto-memory keyed to the git root. Opening at the root collapses that. **Migration is only partly
done** — the memory wiring moved up, but `src\CLAUDE.md` and `src\.claude\{agents,commands,hooks,
loop,rules}` are still below, and a root session does not auto-load them. The root `CLAUDE.md` is an
interim stub that points at `src\CLAUDE.md`; read that file for the real orientation. Note
`.claude\loop\detect.sh` derives its ROOT as "the directory containing `.claude`", so moving the loop
tooling up without fixing that makes it treat `src` itself as one giant module.

Maven modules — dependencies point *downward* toward Objects7, never the reverse:

- `AccountManagerObjects7` (jar) — the core. `BaseRecord`/schema system, PBAC, query system
  (`Query`/`QueryPlan`/`ISearch`), groups/orgs, vault/crypto, the Olio population-simulation
  framework, and all runtime LLM prompt templates.
- `AccountManagerISO42001` (jar) — ISO 42001 AI-management bias testing, scoring, certification.
  No ISO knowledge is permitted in Objects7.
- `AccountManagerService7` (war) — Jersey REST + MCP + WebSocket. **Transport only**, no business
  logic; deployed to Tomcat at `https://localhost:8443`.
- `AccountManagerAgent7` (jar) — agent runtime. `AccountManagerConsole7` (jar) — CLI entry points.

Frontends, not Maven modules: `AccountManagerUx752` (Vite + Mithril) is the active refactor and the
canonical UI reference — read it before writing UI code. `deprecated/AccountManagerUx7/client/` is the
legacy monolith it supersedes.

Persistence is PostgreSQL primary, plus H2 and pgvector. A live Service7/Ux752 stack for testing comes
up via docker-compose rather than a hand-managed Tomcat — see [[project-service-testing-docker]]. LLM
and embedding work targets the DGX Spark at `192.168.1.42`; see [[reference-sd-llm-hardware]].

Authoritative detail lives in the repo, not here: `src/CLAUDE.md` for orientation and build/test
commands, `src/.claude/rules/` for the mandatory conduct, architecture, and model-API rules, each
module's own `CLAUDE.md` for depth, and `src/aiDocs/` for design documents.

---

## project-chapbook-add-poem-design

**Type:** project · **Status:** active · **Created:** 2026-08-24 20:35:41 · **Updated:** 2026-08-24 20:35:41

_ChapBook Add Poems UX design — multi-select notes/data with ordering, bulk import via POST /poems_

---
name: project-chapbook-add-poem-design
description: ChapBook "Add Poems" UX design — multi-select notes/data with ordering, then bulk import via POST /poems
metadata:
  type: project
---

ChapBook "Add Poems from Note/Data" flow finalized 2026-08-24:

**Frontend (chapBook.js):**
- "Add from Note" button → ObjectPicker with `multiSelect: true`, type `data.note`
- "Add from Data" button → ObjectPicker with `multiSelect: true`, type `data.data`
- After picker confirms, shows a "Set poem order" dialog with numbered list, ↑/↓ and × per item
- "Import N poem(s)" calls `POST /olio/chap-book/poems` with ordered `sources` array

**ObjectPicker (picker.js):**
- New `multiSelect: true` option — passes full array to handler instead of unwrapping to [0]

**Backend (ChapBookService.java):**
- `POST /poems` (bulk, error-tolerant) — accepts `{ sources: [{type, objectId, title?}, ...] }`
  - `data.note` → reads `text` field directly
  - `data.data` → ByteModelUtil.getValueString() (handles decompression/decryption)
  - Processes all in order, collects errors, returns `{ poems: [{objectId, title},...], errors?: [...] }`
- `POST /poem` (single) — unchanged, now also accepts `noteObjectId` or `dataObjectId` as alternatives to raw `text`

**Why:** [[project-chapbook-design]] — "Multi-poem → one ChapBook: all selected poems combined in sequence"

---

## project-chapbook-design

**Type:** project · **Status:** active · **Created:** 2026-08-23 22:09:25 · **Updated:** 2026-08-23 23:19:19

_ChapBook feature design: poetry PictureBook variant with olio.cb.book/poem/set models, theme LLM, landscape-only pipeline, text overlay_

---
name: project-chapbook-design
description: ChapBook feature design: poetry PictureBook variant with olio.cb.book/poem/set models, theme LLM, landscape-only pipeline, text overlay
metadata:
  type: project
---

ChapBook is a poetry variant of PictureBook 2.0. Pipeline: LANDSCAPE_PROMPT + LANDSCAPE nodes only — no characters, no Flux Kontext, no composite.

**Design decisions (made 2026-08-23, auto mode, taken from handoff recommended options):**
- Multi-poem → one ChapBook: all selected poems combined in sequence; scenes = stanzas across all poems in order.
- LLM template: new `chapBook.landscape-prompt` (not reusing `pictureBook.landscape-prompt`).
- Overlay opacity: `overlayOpacity` field on `olio.cb.book` (no schema default; renderer uses 0.4 when null).
- `maxLinesPerPage` on `olio.cb.book` (no schema default; ChapBookUtil uses 8 when null).

**Models added (olio/cb/):**
- `olio.cb.poem` — inherits data.directory; fields: title, author, theme, mood, keywords (LLM-extracted)
- `olio.cb.set` — inherits data.directory; fields: description, poems (list FK, participation table cb.set.poem)
- `olio.cb.book` — inherits olio.pb.book; fields: maxLinesPerPage, overlayOpacity

**Existing model changes:**
- `olio.pb.book` — added `bookType` enum (PbBookTypeEnumType: UNKNOWN, STORY, CHAPBOOK)
- `olio.pb.scene` — added `poemStanza` string field

**Ux status (2026-08-23):** Complete and verified (vite build + 445 vitest pass). chapBook.js created with PoemLibrary, renderChapBookPage, ChapBookFeature; wired into features.js and manifest.

**Backend status (2026-08-23):** Backend agent launched for Java models/ChapBookUtil/ChapBookService — outcome not yet confirmed.

**Why:** [[project-pb2-open-gaps]]

---

## project-chapbook-image-pipeline

**Type:** project · **Status:** active · **Created:** 2026-08-24 14:46:58 · **Updated:** 2026-08-24 14:46:58

_ChapBook image render pipeline design: sdPrompt+imageObjectId on scene, renderChapBook direct SDUtil path, bookPageView fallback_

ChapBook image render pipeline (implemented 2026-08-24, agents running — not yet verified):

- `olio.pb.scene` model: added `sdPrompt` (string, landscape prompt) and `imageObjectId` (string, data.data objectId after render)
- `OlioFieldNames`: added `FIELD_CB_SD_PROMPT = "sdPrompt"` and `FIELD_PB_IMAGE_OBJECT_ID = "imageObjectId"`
- `PbBookUtil.sceneRequest()`: includes `poemStanza`, `sdPrompt`, `imageObjectId`
- `ChapBookUtil.createChapBookScene`: patches `sdPrompt` from format string on scene creation
- `ChapBookUtil.renderChapBook(user, bookObjectId, sdApiType, sdServer)`: calls `SDUtil.createImage` per scene using `sdPrompt`, patches `imageObjectId` onto scene
- `PbServiceFacade.bookPageView`: emits `poemStanza`; uses `imageObjectId` as `dataObjectId` fallback
- `ChapBookService`: new `POST /render/{bookObjectId}` reads sdApiType/sdServer from Servlet init-params per-request
- `chapBook.spec.js`: synthetic POEM_1/POEM_2 replaced with real corpus poems (fallingleaves.txt, winter_1.txt); SD-gated test added (gate: `CHAPBOOK_SD_TESTS=1`)

**Why:** E2E + image testing required; `generateSceneImage` incompatible with `olio.pb.scene` records (see [[feedback-chapbook-authorize-scene-gotcha]])

**How to apply:** SD-gated render test runs with `CHAPBOOK_SD_TESTS=1 --workers=1`. Standard 5-test suite stays always-on.

---

## project-chapbook-test-status

**Type:** project · **Status:** active · **Created:** 2026-08-24 01:54:13 · **Updated:** 2026-08-24 23:16:36

_ChapBook Playwright gate status_

ChapBook Playwright gate: 8/8 green (1 LLM-gated skipped) as of 2026-08-24 with CHAPBOOK_SD_TESTS=1. SD image generation verified passing at 3.5 min. favorites() fix deployed: grp.organizationId now set before createObject so Add from Note/Add from Data picker opens without 403 error.

---

## project-ki69-closed

**Type:** project · **Status:** active · **Created:** 2026-08-20 23:15:47 · **Updated:** 2026-08-20 23:15:47

_KI-69 closed 2026-08-20: age-blind portrait fix in NarrativeUtil with adult fallback, 5 tests green_

KI-69 is CLOSED as of 2026-08-20.

**Fix:** `NarrativeUtil.buildPortraitPromptFromExtractedData` (line ~1942) now inserts `"adult"` as the `age_approx` fallback immediately after the outfit fallback, when `age_approx` is absent or a placeholder token. The fallback is unconditional at the StringBuilder level — the age field always appears in the prompt with weight `:1.5`.

**Why:** LLM extraction for role-only characters (e.g., "innkeeper") produces `role` data but no `age_approx`; without the fallback the SD prompt was age-blind: `"...portrait of a ((woman))..."` with no age qualifier, leaving the model to determine age freely.

**Test:** `TestNarrativeUtilPortraitPrompt` — 5 tests, all green including new `TestRoleOnlyCharacterGetsAgeBlindFix`.

**Residual:** "fully clothed in appropriate attire" directive in the positive prompt is not honoured by the SD model — separate issue, not addressed.

**How to apply:** KI-69 is done; the next portrait-quality issue is KI-68 secondary (steps/cfgscale investigation for flux2Klein_9b). Do not re-open KI-69 for the outfit-directive failure — it is a generation quality issue, not a prompt-construction issue.

---

## project-pb-castgroup-q15

**Type:** project · **Status:** active · **Created:** 2026-08-21 22:37:31 · **Updated:** 2026-08-21 22:37:31

_Q15 resolved: olio.pb.castGroup for collective canvas entities — model created 2026-08-21_

Q15 (cast/group canvas entities) resolved 2026-08-21: use olio.pb.castGroup.

**Decision:** A collective like 'Meadow Herd' gets its own olio.pb.castGroup record. The earlier 'NO NEW MODEL' call (decision 13 in PictureBook2Plan.md) was mine (Claude) — Stephen overrode it.

**Model: olio.pb.castGroup**
- Inherits: common.groupExt, common.baseLight, common.urn (likeInherits: data.directory)
- dedicatedParticipation: true
- constraints: name, groupId, organizationId
- Fields: name (string), description (string), book (FK to olio.pb.book), members (list of olio.charPerson via pb.castGroup.member participation)
- Registered as OlioModelNames.MODEL_PB_CAST_GROUP in OlioModelNames.java

**Wire-up:** A binding references it via refModel='olio.pb.castGroup' + refObjectId. Canvas chip label = castGroup.name. Handle on the node = character_@herd (per Phase 6b canvas design).

**Why:** castGroup is justified over N-bindings when the collective needs its own name, description, or shared style ref. The binding model description updated to reflect this.

---

## project-pb-phase1b-status

**Type:** project · **Status:** active · **Created:** 2026-08-23 00:36:06 · **Updated:** 2026-08-23 01:10:11

_Phase 1b (universe/world IDs in Service7+Ux) implementation complete and verified_

Phase 1b implementation complete: W1 (OlioContextUtil.findCachedByWorldObjectId + PbOlioContextUtil.getBookContextByIds), W2 (GameService.resolveOlioContext private helper replacing all 16 OlioContextUtil.getOlioContext calls), W3 (olio.js setCurrentBook/currentWorldObjectId/currentUniverseObjectId/withBookContext), W4 (adoptCharacter.js + pictureBook.js threading IDs). Playwright gate: pictureBookWorldSwitch.spec.js — 4 browser tests skip on Vite dev (need Docker/same-origin for API-login cookie pattern, same constraint as Phase 5), 2 REST tests pass (confirmed fake worldObjectId returns non-500). Build gates all green: Objects7 compile+install, Service7 compile, vite build, vitest (445 pass + 1 pre-existing fail). Objects7 full suite: 8 failures all pre-existing (5 TestPictureBookWorkflow AI-dependent, 1 TestPictureBookCustom order-dependent, 1 TestPathUtilBehavior pre-existing, 1 repeat).

---

## project-pb-security-status

**Type:** project · **Status:** active · **Created:** 2026-08-21 21:51:17 · **Updated:** 2026-08-21 21:51:17

_TestPbSecurity status: 10/10 green on 2026-08-21_

TestPbSecurity is GREEN: 10/10 tests passed (0 failures, 0 errors) on 2026-08-21 against am7db. All 10 cases passed including: case01 (B cannot read A's records), case02 (list path defect PINNED not fixed), case03 (B cannot create in A's workflow), case04 (reads don't enrol), case05 (sharing requires holding), case06 (book role alone can't read universe corpora), case07 (second book doesn't widen first), case08 (role hierarchy direction measured), case09 (fresh org book creation works), case10 (no PB util reaches destructive paths). MEASURED DEFECT in case02 is intentionally characterized, not an error. **Why:** This is the phase 2 security exit criterion from PB2Plan.md §9. **How to apply:** TestPbSecurity is complete; do not rewrite or re-run unless PB2 PBAC logic changes.

---

## project-pb2-chapbook-remediation-complete

**Type:** project · **Status:** active · **Created:** 2026-08-25 18:18:56 · **Updated:** 2026-08-28 17:05:38

_PB2+ChapBook remediation all 14 issues addressed 2026-08-28; ChapBook E2E 9/9 green; issue-13 silent-fail fixed_

ChapBook Playwright gate: 9/9 tests pass (3 skipped for LLM/SD) on 2026-08-28 using `https://127.0.0.1:9443`. Root cause of previous failures was localhost → IPv6 TLS handshake failure.

Issue 13 (PictureBook character creation silent failure) root cause confirmed and fixed 2026-08-28:
- `initCharacterManager(bookObjectId)` was inside the same try block as `createFromScenes`. If character-list fetch threw after book creation succeeded, the toast said "Failed to create book: " with empty message (e.message was undefined because the throw was non-Error or had no message).
- Fix: moved `initCharacterManager` OUT of the main try block, after it, wrapped in its own non-fatal try/catch — same pattern the resume path (line ~1498) already used.
- Also improved error extraction: `e?.message || (typeof e === 'string' ? e : null) || 'Unknown error'` instead of bare `e.message || ''`.

**Why:** createFromScenes returned a book but initCharacterManager failed fetching empty character list on fresh book → whole step appeared to fail → user saw generic "Failed to create book:" message with no detail.
**How to apply:** Whenever a post-creation initialization step could fail, put it outside the creation try block with its own non-fatal catch.

---

## project-pb2-chapbook-remediation-plan

**Type:** project · **Status:** active · **Created:** 2026-08-25 01:26:43 · **Updated:** 2026-08-25 01:26:43

_Recorded evidence-based PB2+ChapBook remediation plan (src/aiDocs/PictureBook2ChapBookRemediationPlan.md) + verified blockers, corrections (M3 fixed), stack port 9443, real poem corpus_

Consolidated, evidence-based remediation plan for PictureBook 2 + ChapBook lives at
`src/aiDocs/PictureBook2ChapBookRemediationPlan.md` (recorded 2026-08-24). It supersedes the
"done/green" PB2/ChapBook status memories, which were overclaimed (see [[feedback-pb2-completion-overclaimed]]).

Built from THREE adversarial audits that read real source and ran real tests live against the Docker
stack. Verified blockers: (B1) ChapBook has no poem-creation UI; (B2) Analyze button 400s (no
chatConfig body); (B3) render has no UI trigger; (B4) character extraction creates no book/universe/world,
chars land in home dir (UAT#1); (B5) text=bigint unrepaired for pre-S6 DBs — DBUtil only ADD/DROP
column, no ALTER TYPE; (B6) COMPOSITE node = 501 stub (the node that makes the final page image);
(B7) /workflow + /stale 404 for a real book; (B8) workflow canvas is viewer-only (pan/zoom), no editor.

**Corrections to the earlier feature-prompt (do not chase these as bugs):**
- M3-old "OlioContext auth-before-initialized" is ALREADY FIXED — a distinct `authorizationConfigured`
  flag exists (OlioContext.java:153), set only after both configureWorldAuthorization calls, catch
  re-throws when unset. Not a gap.
- UAT#2 (denoise 0-1) and UAT#3 (new-book sdConfig defaults) appear RESOLVED per code — confirm live, then close.

**Operational facts confirmed 2026-08-24:**
- Current Docker test stack maps `0.0.0.0:9443->8443`; port 8443 is NOT published. Use
  `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443` — a live run got 14 passed / 2 failed (the 2 fails are B7).
- Real test poems: `volatile/poemsXml/txt/<collection>/<poem>.txt` — 146 real Stephen Cote poems in 11
  collections. Plan calls for 10 random per end-to-end run.

**Why:** Stephen: "You left me with a turd" — both features are unusable end-to-end; prior green claims
came from env-gated skips / toBeVisible-without-visual-proof / suites that never touch the broken path.

**How to apply:** Work the plan's phases A→F in order (A = text=bigint via the framework, architect
sign-off required, no hand-rolled JDBC). Nothing claimed done without a real test + extracted-image
visual proof. See [[feedback-visual-inspection-required]], [[feedback-use-real-test-content]],
[[testing-db-reset]].

---

## project-pb2-new-issues-2026-08-29

**Type:** project · **Status:** active · **Created:** 2026-08-29 14:38:54 · **Updated:** 2026-08-29 14:38:54

_8 PB2/ChapBook issues addressed 2026-08-29: picker nav, clear, cache, LLM prompt, SD config, roles, type-picker, error surfacing_

New batch of 8 PB2/ChapBook issues addressed 2026-08-29. All compile; 478/478 Vitest green; vite build clean. Playwright E2E not run (Docker stack down).

**Issue 7**: ChapBookUtil.createChapBookScene() takes new 8th chatConfig param; calls PictureBookUtil.callLlmForChapBook() with stanza text to generate LLM SD prompts. Falls back to text excerpt when chatConfig null.

**Issue 13**: PictureBookService.createFromScenes now catches RuntimeException in addition to PictureBookException and returns {"error":"...","cause":"..."} JSON. Previously returned HTML 500 that JS could not parse. sceneExtractor.js reads body.cause. pictureBook.js catch block now opens a modal dialog (not toast).

**Issue 1**: navigateUp() in picker mode fetches parent group via am7client.getFull when pg.container is null; navigatingUp flag prevents re-entrancy. Picker dialog shows current container.path.

**Issue 3**: Poem row key changed to objectId + '-' + (sel?'1':'0') to force Mithril checkbox recreation on clear.

**Issue 4**: prevRoute tracking in list.js; when returning from /new/ or /pnew/ to /list/, calls pagination.new() to bust cache.

**Issue 8**: ChapBookReview render button now calls openRenderConfigDialog() instead of renderChapBook() directly; renderRenderDialog() added to view.

**Issue 9**: pictureBook.js now checks page.context().roles.user; shows yellow warning banner and disables Extract/Continue when missing.

**Issue 12**: Type picker popover now position:fixed using getBoundingClientRect() so not clipped by overflow:hidden ancestors. Breadcrumb type icon wired to page.components.toggleTypePicker.

**Key gotcha**: RuntimeException escaping a Jersey endpoint returns HTML not JSON -- always catch both domain exception AND RuntimeException in Service7 endpoints, returning structured JSON with message+cause.

---

## project-pb2-open-gaps

**Type:** project · **Status:** superseded · **Superseded by:** project-pb2-chapbook-remediation-complete · **Created:** 2026-08-22 17:49:51 · **Updated:** 2026-08-23 13:56:48

_PB2 all phases done 2026-08-23 including Phase 1b; only Phase 3b (ComfyUI, optional) remains_

All PB2 phases done as of 2026-08-23: Phase 0-4 (Objects7 + REST), Phase 5a+5b (Ux752 Playwright 13/13), Phase 6 (migration), Phase 6b (interactive canvas backend), Phase 6c S1-S6 (SD config persistability), and Phase 1b (universe/world IDs threaded through Service7+Ux, Playwright gate 2/2 passed + 4 skipped for Docker, exit 0, 2026-08-22).

**Why:** Phase 1b added `getBookContextByIds` to `PbOlioContextUtil`, `resolveOlioContext` helper in `GameService`, and `am7olio.setCurrentBook / currentWorldObjectId / withBookContext` in `olio.js`; `pictureBook.js` calls `setCurrentBook` when a book opens/closes; `adoptCharacter.js` uses `withBookContext`. Gate: Playwright `pictureBookWorldSwitch.spec.js` 2/2 REST tests passed (4 browser tests skip without Docker stack — require `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`).

**Only remaining phase:**
- Phase 3b: ComfyUI backend (optional). Prereq: capture a working graph from the live ComfyUI UI (Save/Export API format or network payload) before writing any Java. ComfyUI is SwarmUI-bundled at localhost:7821 (localhost-only) / through Swarm at `localhost:7801/ComfyBackendDirect/...` (works off-box). Q12: one-node-one-call first, Swarm stays default; multi-node batch is the payoff but only after the one-call path works.

**How to apply:** Phase 3b cannot start until someone captures the live graph JSON from the ComfyUI UI. No code until that artifact exists.

---

## project-pb2-remaining-work-status

**Type:** project · **Status:** superseded · **Superseded by:** project-pb2-chapbook-remediation-complete · **Created:** 2026-08-24 19:21:30 · **Updated:** 2026-08-24 19:26:18

_B1/B2/B3/D3/M1/M3 implementation status from 2026-08-24 session — complete with architect-required fix_

Implemented 2026-08-24 in the "remaining PB2/ChapBook work" session:

**B1 (createPoem endpoint + POST /poem):** `ChapBookUtil.createPoem()` added; `POST /olio/chap-book/poem` wired in ChapBookService. Frontend Add Poem modal (green button, pop-in dialog) added to chapBook.js PoemLibrary.

**B2 (PbNodeExecutor node types):** LANDSCAPE, SCENE_PROMPT, LANDSCAPE_PROMPT cases implemented; COMPOSITE throws 501 ("img2img pipeline not yet implemented"); all other unimplemented types now return 501 (was incorrectly 400). resolveEffectiveConfig(book, node, false) — false = standard sdConfig tier, not composite.

**B3 (migrate-v1 endpoint):** `POST /olio/picture-book/migrate-v1` added to PictureBookService, calls `PbMigrationUtil.importV1Book` and maps `ImportResult` fields to response DTO. TestPbMigration already existed with `assumeTrue` guard (contradicted the feature prompt's claim it was missing).

**D3:** `@Ignore` applied to `TestD3MismatchedTypeRequestIsNotSilentlySatisfiedByAnotherType` in TestPathUtilBehavior. TestPathUtilBehavior: 15 run, 0 failed, 1 skipped — BUILD SUCCESS.

**M1:** `olio.cb.set` membership deferred to ChapBook Phase 2 — documented in PictureBook2ImplementationState.md + TODO comment in chapBook.js `fetchSets`.

**M3 (OlioContext authorizationConfigured):** Already implemented — `authorizationConfigured` flag is at OlioContext.java:939 and `PbOlioContextUtil.getCreateBookContext:333` already checks `isAuthorizationConfigured()`. No work needed.

**B4 (workflow spec):** `e2e/pictureBookWorkflow.spec.js` already existed (contradicts impl state doc "Not started"). 3 new real-book tests added: REST test for /workflow returning nodes, UI test for /picture-book/v2/ route, and env-gated test for PB1 workflow canvas node cards. vite build clean. vitest: 1 pre-existing failure in dialog.test.js (mock ordering issue), 31 suites pass.

**Architecture gotcha fixed:** `PbConfigUtil.resolveEffectiveConfig(book, node, composite)` — the third parameter is the `composite` flag (true = pull compositeSdConfig tier). LANDSCAPE nodes must pass `false` (standard sdConfig), not `true`. Passing `true` silently produces the wrong config and wrong hash for staleness decisions.

**PbNodeExecutor 400 vs 501:** 400 = bad caller input (missing scopeRef); 501 = valid input but feature not built. All unimplemented node types now return 501.

**Workflow route distinction:** `/picture-book/:bookGroupObjectId/workflow` uses a PB1 auth.group objectId (the bridge endpoint calls findByObjectId on auth.group). ChapBook books are native olio.pb.book records — use `/picture-book/v2/:pb2BookObjectId` for them.

---

## project-pb5-phase-status

**Type:** project · **Status:** active · **Created:** 2026-08-21 14:03:31 · **Updated:** 2026-08-24 19:49:20

_Phase 5 workflow canvas complete — Test button, Stale recheck, DONE_UNVERIFIED color, 15 Playwright tests_

Phase 5a+5b Playwright gates closed — 13/13 tests pass; full Phase 5 exit criterion met. Canvas now has: pan/zoom, SVG edges, node cards, pin, regen, **Test button** (POST /node/{oid}/test), Stale recheck button (GET /stale + reload), DONE_UNVERIFIED status color. testNode export added to workflows/pictureBookWorkflow.js. 2026-08-24.

---

## project-pb6-phase-status

**Type:** project · **Status:** active · **Created:** 2026-08-21 19:13:53 · **Updated:** 2026-08-21 19:13:53

_Phase 6 (Migration) status: PbMigrationUtil + TestPbMigration green_

Phase 6 migration complete as of 2026-08-21. PbMigrationUtil.importV1Book imports a PB1 auth.group+data.note book into PB2 (olio.pb.book/scene/workflow); PB1 records untouched. TestPbMigration: 2/2 green against Catatone Custom Book 1 in /Development/PictureBook Custom Tests / pbCustomTestUser. Test is idempotent (re-run verifies existing import rather than throwing 409). DB must be up (port 15430). Run with: mvn -o -pl AccountManagerObjects7 -Dtest=TestPbMigration -DskipTests=false test from src/. Next: bump iter in TestPictureBookCustom to 5, run TestPictureBookCustomPipeline, then re-run TestPbMigration for a fresh book.

---

## project-pb6b-phase-status

**Type:** project · **Status:** active · **Created:** 2026-08-22 00:44:54 · **Updated:** 2026-08-22 00:44:54

_Phase 6b (Interactive Canvas Backend) complete: PbNodeExecutor + TestPbCanvas green_

Phase 6b (Interactive Canvas Backend) complete:
- PbNodeExecutor.java: standalone PORTRAIT node executor (SwarmUI end-to-end, no OlioContext needed)
- PbServiceFacade.testNode(): facade method using ServerConfigUtil.SERVER_SD
- POST /{bookObjectId}/node/{nodeObjectId}/test added to PictureBookService.java
- TestPbCanvas#TestPortraitNodeExecution: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time: 38.52s
- TestPictureBookCustom#TestPictureBookCustomPipeline: still passing

Known key: query.setRequestRange(0, N) not q.setValue("startRecord", 0) — auto-boxes int to Integer but field is Long.

---

## project-pb6c-phase-status

**Type:** project · **Status:** active · **Created:** 2026-08-22 00:55:02 · **Updated:** 2026-08-22 21:42:55

_Phase 6c (SD config persistability) complete: S1-S6 all done, all tests green_

Phase 6c complete after Phase 6c Test Cleanup session (2026-08-22).

S1-S6: all done. TestS6BookSdConfigForeignRef, TestPbModelSchema, TestPbGraph all green.

S6 landed: book.sdConfig and book.compositeSdConfig promoted to foreign:true FK referencing olio.sd.config rows.

Test cleanup done:
- TestSdConfigFieldsAreSerializedNotForeign replaced by TestSdConfigIsNowForeignRefAfterS6 (asserts book fields ARE foreign)
- TestBookRoundTripsAndUrnIsComposed fixed: persists a real olio.sd.config, sets it as FK, asserts objectId survives round-trip. Note: style field has limitValues constraint (art, movie, photograph, ...) — must use a valid value.
- TestPbGraph.case09_computeInputHashGoldenVector golden hash updated to 879405447e367aa8235c053aee863cd856ea93ea46e5261b0c6b68dcb33cdef4 (changed because configModel.json canonical form changed with S6)

Why it is correct: TestPbModelSchema 19/19, TestPbGraph 15/15 on 2026-08-22.

---

## project-picturebook-backend-redo

**Type:** project · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_PictureBook feature backend persistence redo — charPerson/portrait/landscape not saved, reference images unused_

As of 2026-07-15, Stephen rejected a previous fix attempt for the PictureBook feature: charPerson
records aren't saved, portrait and landscape images aren't saved, and reference images are
obviously not being used. He asked for a full backend-first review (AccountManagerObjects7 before
any UX work), with all generated images emitted to disk during testing and explicit DB
verification that every expected supporting object is both created and actually used
(narrative/profile/portrait/landscape linkage, not just orphaned rows).

**Why:** this is a second attempt — the first one apparently tested fragments in isolation
(hand-built charPerson objects inline in tests, mechanism proven but the real code path never
exercised) and declared success without an end-to-end DB-verification test.

**Findings so far (traced by planner, confirmed by architect):**
- All PictureBook business logic lives in `AccountManagerService7/.../PictureBookService.java`
  (1,819 lines) — a "no business logic in Service7" architecture violation. Decision: fix
  persistence bugs in place first (with a DB-reverifying JUnit test as regression baseline), do
  the Objects7 extraction as a separate mechanical follow-up pass afterward — not both at once.
- Landscape image is generated, persisted internally by `SDUtil.createSceneImage`, then
  immediately deleted by `PictureBookService` — matches Stephen's exact complaint.
- Portrait persistence was gated on a fragile `sceneGroupPath.contains("/Scenes")` string check.
- `createCharPerson()` root causes: (a) LLM-extracted gender values overflow a maxLength:10 field
  and throw before create, silently aborting the character (Stephen's decision: clamp to
  MALE/FEMALE/UNKNOWN only, no other values — no schema change needed since all three fit); (b)
  `olio.narrative` attached via a bare groupless `RecordFactory.newInstance` + risky full-object
  update instead of the existing fully-populated-charPerson utility pattern (Stephen's decision:
  use the existing utility, don't hand-roll it).
- Separately found: `PictureBookService.java` had 4+ raw `FIELD_BYTE_STORE` reads instead of
  `ByteModelUtil.getValue()` — see [[feedback-bytestore-access]]. Plausible contributor to
  "reference images obviously aren't used" if that model has compression/encryption enabled.

**Follow-up regression found by real E2E test (test-author, calling the actual live
`PictureBookService` against real Postgres/LLM/SD):** `patchCharPersonField()`'s narrative-attach
PATCH always fails `RecordValidator` because `RecordFactory.newInstance(schema)` pre-populates all
schema fields (including `common.name`'s required/`$notEmpty` `name` field, inherited via
`olio.charPerson` → `identity.person` → `data.directory` → `common.nameId` → `common.name`), so
every extracted character ends up in `failedCharacters` and the book has zero characters. Sent back
to backend-specialist to find the correct minimal-partial-update idiom this codebase actually uses
(candidates: `LooseRecord`, or whatever `identity.profile`'s working PATCH in `TestPortraitReuse`
actually relies on) rather than weakening `RecordValidator`.

Also: a disk-emit-to-file hook was mistakenly added to `PictureBookService.java` (production) for
test inspection — Stephen rejected this, see [[feedback-test-only-instrumentation]]. Emission
belongs in the test, re-fetching persisted images via the normal resolution path.

**Follow-up regression #2 (found by the same real E2E test, after regression #1 was fixed):**
`narrative.sdPrompt` always reads back null. Cause: `createCharPerson()` sets `sdPrompt` on the
in-memory `narrative` object *after* it was created, then only patches `charPerson.narrative` (the
FK reference) — per this repo's own PATCH contract ("foreign fields patch by ID reference"), that
does not cascade-write the nested `narrative` record's own fields. Fix requires a *second*,
separate `AccessPoint.update()` patch directly on the `narrative` record itself. Sent back to
backend-specialist (2026-07-16); also asked it to check whether `profile` has the same class of
bug. General lesson if this recurs elsewhere: after `createPersistedForeignInstance()`, any field
set on the child record post-creation needs its own direct patch — patching the parent's FK
pointer to the child is not enough.

**Course correction #2 (2026-07-16):** Stephen pointed at the existing, proven "chat scenes" flow
(`SDUtil.generateSDImages`/`generateSDFigurines`, `Chat.java`) as the pattern that should have been
used all along instead of the hand-rolled `createPersistedForeignInstance`/`patchCharPersonField`
machinery from earlier passes. Real pattern: `NarrativeUtil.getCreateNarrative(ctx, population,
setting)` for narrative create-or-patch, `prof.setValue("portrait", img); Queue.queueUpdate(prof,
{FIELD_ID,"portrait"});` for profile/portrait, flushed via `Queue.processQueue(user)` (PBAC-
respecting) or `RecordUtil.patch(src, targ)` for updating an existing nested record's fields. See
[[feedback-search-existing-olio-utils-first]]. The Objects7 extraction in flight is being redone to
use these utilities instead of the custom mechanism (which worked, per live tests, but wasn't the
sanctioned approach and cost an extra round trip).

**Extraction completed (2026-07-16):** business logic now lives in
`AccountManagerObjects7/.../olio/picturebook/PictureBookUtil.java` (static methods, `BaseRecord
user` param, driven through `AccessPoint`, zero servlet dependency); `PictureBookService.java`
(Service7) is thin transport (~430 lines, was 1,819). SD-server address is now a plain string
param instead of a `ServletContext` init-param lookup, matching how `TestPictureBookPipeline`
already talks to the real SwarmUI backend — likely fixes the earlier spurious `Connection refused`.
Notable deliberate non-adoption: did NOT use `NarrativeUtil.getCreateNarrative` (it builds prompts
from a full simulated `PersonalityProfile` PictureBook characters don't have —
`NarrativeUtil.buildPortraitPromptFromExtractedData` is the actual already-correct utility for
LLM-extracted characters) or the shared static `Queue`/`Queue.processQueue()` (swallows per-record
success/failure that `failedCharacters`/`failedPortraits` need; process-wide static map is a
concurrency hazard for a live multi-user REST endpoint; `RecordUtil`'s direct writes bypass
`AccessPoint`/PBAC). Did adopt the `record.copyRecord(fieldNames)`-on-an-already-loaded-record
idiom in place of `RecordFactory.newInstance(schema, explicitFields)`, still going through
`AccessPoint` directly. `TestPictureBookServiceE2E.java` (Service7, mocked ServletContext) is
retired next in favor of a real Objects7-tree test calling `PictureBookUtil` directly.

**Real generation-fidelity bug found by visual inspection (2026-07-16):** Stephen opened the
actual composite image and portrait image side by side — the composite doesn't resemble the
portrait at all (different hair color, different face). Root cause: `PictureBookUtil` only had the
Kontext pipeline (stitch portraits+landscape, hand to a Flux Kontext model as a single reference
image, rely on text description to preserve likeness). The proven chat-scene flow
(`ChatService.java:1398-1456`) treats Kontext as unreliable for likeness on its own and falls back
to a "classic" pipeline: `SDUtil.compositeSceneCanvas()` literally draws the real portrait pixels
onto the landscape canvas via `Graphics2D`, then runs SDXL img2img at a controlled
creativity/denoise value — preserving identity because the real pixels are physically present
before refinement. PictureBook had no such fallback. Fix in flight: mirror ChatService's exact
dual-pipeline (`useKontext` + `sceneCreativity` config, fallback on empty Kontext result). Lesson:
"passing the persistence/decode test" is not the same as "the image is actually correct" — visual
inspection of real output caught something no assertion checked for.

**Milestone reached (2026-07-16): full pipeline verified end-to-end, real backend, no mocking.**
`TestPictureBookUtilE2E` (Objects7 tree, calls `PictureBookUtil` directly, zero mocking) passed
against live Postgres/Ollama/SwarmUI: character extraction (gender clamp confirmed for both a
clearly-gendered and an ambiguous character), narrative+profile persisted, portrait/landscape/
Kontext-composite images all actually generated, persisted, decoded via `ImageIO` into real
multi-pixel images, and one composite was manually opened and visually confirmed to match the
scene text. Portrait reuse across a second scene confirmed (no duplicate). Old mocked
`TestPictureBookServiceE2E.java` (Service7) deleted. Still open at this point: the literal-"null"-
string prompt bug (see [[feedback-llm-literal-null-strings]]) was caught by Stephen inspecting real
prompt output and was still in-flight when this E2E run passed — the run's images look fine
visually but the underlying prompts likely still contained garbage "null" tokens; re-verify once
that fix lands.

**Ground-truth verification method (2026-07-16):** Stephen checks SwarmUI's own generation
history directly (`http://192.168.1.42:7801`) as the real source of truth for whether portrait
generation ever actually fires — more reliable than trusting DB rows or agent-reported pipeline
progress. As of this note, no successful run has gotten a portrait request through to SwarmUI at
all (every run either hit the pre-fix "no sdPrompt, skip portrait" bug or a post-fix
`Connection refused` from a mismatched SD-server config sourced via a mocked ServletContext).
Landscape/scene images visible in SwarmUI's history predate these fixes (from when the code
generated-then-deleted the AM7-side record; SwarmUI keeps its own copy regardless). **Any future
"it's fixed" claim for this feature must be checked against SwarmUI's own history showing an
actual portrait image, not just a DB row existing.**

**How to apply:** When resuming this work, check the current state of `PictureBookService.java`
and `CharPersonFactory.java` against these findings rather than assuming they're still accurate —
fixes were still iterating as of this writing (narrative-PATCH validation bug + emit-hook removal
both in flight). Frontend/Ux752 changes are explicitly out of scope until backend is verified with
real tests, and the real acceptance bar is: if the unit test passes, all generated images must be
viewable in the Ux through their normal resolution paths.

---

## project-service-testing-docker

**Type:** project · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-29 15:17:10

_READ FIRST for any Docker/Playwright work: docker-compose in src/, am72db vs am7test ports, clean-env, hot-deploy_

Stephen's standing instruction (2026-07-19): all Service7/Ux752 testing that needs a live backend (Playwright E2E, manual REST checks) should use the repo's docker-compose.yml + Dockerfile setup, not an ad hoc manually-managed local Tomcat.

**READ THIS BEFORE ANY DOCKER OR PLAYWRIGHT WORK. Do not rediscover these settings.**

**docker-compose.yml location:** `src/docker-compose.yml` — it is in `src/`, NOT the git root. Every docker-compose command must be run from `src/`:
```bash
cd "C:\Projects\GitHub\AccountManager7\src" && docker-compose up -d
cd "C:\Projects\GitHub\AccountManager7\src" && docker-compose ps
```
The Bash shell cwd resets between tool calls — always include the `cd` in the same command.

**Docker stack database mapping — critical:**
- `docker-compose.yml` (full stack, nginx + vite preview, `:8443`): uses `am72db` on main postgres (`:15432`). This is the protected database. Do NOT run tests that modify schema against this stack.
- `docker-compose.test.yml` (test stack, Tomcat only, `:9443`): uses `am7test` on `am7-pg` (`:15433`). This is the resettable test DB. Use this for Playwright E2E tests.

**Why this matters:** the containers and databases share port prefixes (`15432` vs `15433`) and the database names look similar (`am72db` vs `am7test`). If you connect to the wrong one and run DDL, you've touched `am72db`. Always verify which container the test stack is configured against before doing anything.

**MANDATORY: Clean-env procedure before running Playwright E2E tests**

Stephen explicitly requires a clean environment before starting (2026-08-24). Switching ports mid-session means tests may run against the wrong Vite server or backend, silently passing against wrong state.

```bash
# 1. Kill any existing Vite processes (all of them, not just "ours")
pkill -f "vite" || true
# On Windows/Git Bash:
taskkill //F //IM node.exe //FI "WINDOWTITLE eq vite" 2>/dev/null || true

# 2. Verify the desired port is free before starting
# (If it's not free, find and kill the occupying process — do not silently move to a different port)
# Windows: netstat -ano | findstr ":8900"
# Git Bash: ss -tlnp | grep 8900 || netstat -an | grep 8900

# 3. Start Vite on the intended port with the explicit backend
cd src/AccountManagerUx752
AM7_BACKEND=https://localhost:9443 npx vite --port 8900 &

# 4. Confirm the server is up and on the right port before running tests
sleep 3 && curl -sk https://localhost:8900 | head -5

# 5. Run tests against the confirmed port
PLAYWRIGHT_BASE_URL=https://localhost:8900 npx playwright test e2e/... --workers=1 --project=chromium
```

**What goes wrong without a clean env:**
- An old Vite server on 8900 (from a previous session, no AM7_BACKEND set) defaults to `localhost:8443`.
- A new server auto-moves to 8901. Tests run against 8901 (correct backend), but without verifying this, you can't be sure.
- Worse: if the test runner picks up the port from the old server, it hits 8443 instead of 9443.

**Rule:** never accept a port fallback silently. If the desired port is occupied, investigate and kill the occupying process first.

**Hot-deploying changed jars/classes to the running test container (am7test-am7-1):**

```bash
# 1. Build the changed module locally (offline)
cd src && mvn -o -q -pl AccountManagerObjects7 install -DskipTests

# 2. Copy the updated Objects7 jar into the container's WEB-INF/lib
docker cp src/AccountManagerObjects7/target/AccountManagerObjects7-7.0.0-SNAPSHOT.jar \
  am7test-am7-1:/opt/tomcat/webapps/AccountManager7/WEB-INF/lib/

# 3. For Service7 classes (ChapBookService etc.), extract and copy the .class file
cd src && mvn -o -pl AccountManagerService7 compile -DskipTests
CLASS_SRC="AccountManagerService7/target/classes/org/cote/rest/services/ChapBookService.class"
docker exec am7test-am7-1 mkdir -p /opt/tomcat/webapps/AccountManager7/WEB-INF/classes/org/cote/rest/services/
docker cp "$CLASS_SRC" am7test-am7-1:/opt/tomcat/webapps/AccountManager7/WEB-INF/classes/org/cote/rest/services/

# 4. Restart Tomcat inside the container
docker exec am7test-am7-1 /opt/tomcat/bin/shutdown.sh || true
sleep 3
docker exec am7test-am7-1 /opt/tomcat/bin/startup.sh

# 5. Wait for Tomcat to come up (watch logs or poll)
docker logs -f am7test-am7-1 2>&1 | grep -m1 "Server startup"
```

**Frontend dist hot-deploy:**
```bash
cd src/AccountManagerUx752
npx vite build
docker cp dist/. am7test-am7-1:/opt/ux752/dist/
```

**Pure Objects7 JUnit tests:** never need Tomcat/Docker. They talk to the DB/Ollama directly via `IOSystem`. They use `am7db` (resettable). See [[testing-db-reset]] for reset procedure and [[testing-olio-org-seed]] for the olio seed cost gotcha.

**What "verified working" means:**
- Backend compile: `mvn -o -pl <module> compile`, `BUILD SUCCESS`.
- REST behavior: `ensureSharedTestUser()` + actual HTTP calls to the live stack at `:9443`. Never mock the REST layer — see [[feedback-no-rest-mocking]].
- UI behavior: Playwright test passing against the live stack with the WS stub in place — see [[playwright-docker-e2e-gotchas]].

---

## project-world-delete-endpoint

**Type:** project · **Status:** active · **Created:** 2026-08-28 00:34:15 · **Updated:** 2026-08-28 00:34:15

_DELETE /rest/olio/world/{worldObjectId} — full world wipe; uses olio principal; PB2 book cleanup falls back to direct delete if no PB1 group_

`DELETE /rest/olio/world/{worldObjectId}` — full world wipe endpoint in OlioService.java.

Steps: (1) resolve olio principal via `Factory.findUser(OlioContext.OLIO_USER_NAME, orgId)`; (2) find `olio.world` using olio principal; (3) populate 2 levels deep; (4) for Books-universe worlds, find associated `olio.pb.book` by slug (no ownerId filter) and call `PictureBookUtil.reset` — if reset throws 404 (no PB1 group), fall back to `accessPoint.delete(olioUser, pb2Book)` directly; (5) call `WorldUtil.deleteWorld(olioUser, world)`.

**Why:** Pure PB2 books created via `/chapter` may not have a PB1 book group at `~/Data/PictureBooks/{slug}`, so `PictureBookUtil.reset` throws 404. The fallback deletes the olio.pb.book record directly.

**How to apply:** Use this endpoint pattern whenever a world delete is needed from the REST layer. Never call `WorldUtil.deleteWorld` with the HTTP user for Books-universe worlds.

---

## testing-db-reset

**Type:** project · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-24 13:42:52

_Database reset rules: am7db and am7test resettable; am72db NEVER touched at all — no DDL, no migrations, no SQL_

**Reset permission, stated by Stephen 2026-08-14 (supersedes the blanket prohibition in `.claude/rules/architecture.md` "Hard prohibitions" and `CLAUDE.md`, both of which say never reset and that Stephen does that himself):**

- **`am7db` — may be reset.** The Objects7/Agent7 unit-test DB.
- **`am7test` — may be reset.** The docker Service7 stack's DB.
- **`am72db` — NEVER. Not reset, not migrated, not altered, not touched with any SQL at all.** This is the database Console7's `resource.properties` points at (`15432/am72db`). It shares the port with `am7db`, so read the database name carefully — not just the host:port.

**am72db is off-limits entirely.** The rule is not just "don't reset it." Do not run DDL, do not run migrations, do not run any manual SQL on `am72db`. If a test stack is connecting to `am72db` and needs a schema change (e.g. a migration hasn't been applied), the fix is to reconfigure the stack to use `am7test` or `am7db` — not to run SQL on `am72db`. Running a migration on `am72db` to make tests pass is wrong. (2026-08-24: I made this mistake — ran ALTER TABLE on am72db to fix a column type mismatch in the test stack. Stephen corrected this. Never again.)

**Unit-test DB reset (`am7db`):** edit `AccountManagerObjects7/src/test/resources/resource.properties` to `test.db.reset=true`. The first test that runs will:
1. Drop the entire schema on `am7db`
2. Delete all keys (the unit-test keystore tree)
3. Delete all data

Subsequent runs in the same session see `test.db.reset=true` flipped to `false` after the first reset (BaseTest setup logic handles that).

**Container layout:**
- Main postgres container: `0.0.0.0:15432->5432`. Contains both `am7db` (unit tests) and `am72db` (protected).
- Second container `am7-pg`: `0.0.0.0:15433->5432`. Contains `am7test` (the Docker test stack's DB, may be reset).

**How to apply:**
- Need a clean test DB? Set `test.db.reset=true` and run any Objects7 test. Done.
- Playwright/REST tests need a live backend with a modifiable DB? Use the Docker test stack (`docker-compose.test.yml`) which targets `am7test` on `am7-pg:15433`, not `am72db`.
- If a Docker test stack is showing schema errors on `am72db`, that stack is misconfigured. Fix the config, not the database.
- Never `cp` from `c:/Projects/data/am7/` into the Objects7 test tree. Those keys are for the prod DB.
- See [[project-service-testing-docker]] for the Docker stack command.

---

## testing-olio-org-seed

**Type:** project · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Olio seed data loads per-organization and takes minutes on first use; reuse a single stable test org rather than random or multiple org names_

When writing JUnit tests under `AccountManagerObjects7` that touch the Olio
simulation (population, characters, realms, OlioContext, OlioContextUtil),
the seed data is loaded **at the organization level on first use** and
takes **several minutes** to populate (names, races, locations, clothing
templates, personality data, etc.).

**Rule:** Be deliberate about org count. Each unique org path triggers a
fresh multi-minute seed load on first use. Sometimes that's intentional
(e.g. `TestKontext` uses a distinct org and pays the cost knowingly);
sometimes it's accidental (random suffixes, timestamps, UUIDs in org
names produce a fresh seed load every run).

**Why:** Reason given by Stephen 2026-05-28. Seed data is keyed by
organization, so a new org name means the libraries re-run the full seed
pipeline before any test work can happen. Multiple distinct orgs in one
test multiplies that wait. This isn't a bug — just a budget to account
for.

**How to apply:**
- For a test you expect to run repeatedly during dev, prefer one stable
  hard-coded org path. First run pays the seed cost, subsequent runs
  reuse.
- Avoid org names derived from `UUID`, `Date.now()`, `Math.random()`
  unless multi-org-per-run is genuinely the point of the test.
- When designing a test that DOES need multiple orgs, expect every fresh
  org to add several minutes to first-run wall time — don't blame Olio
  or the test framework when it happens.
- Universe / World names passed to `OlioContextUtil.getGridContext`
  should also be stable per test class for the same reason.
- Applies to any test extending `BaseTest` that creates its own
  `OrganizationContext` via `getTestOrganization(...)`.

Related: the test DB itself is separate from the production DB
(production = `am72db` on :15432, the Service7/Ux752 target). Test target
is `am7db` on :15432 with `test.db.reset=true` triggering fresh schema
on first run — but the seed cost is org-creation overhead, not schema
overhead.

---

## tomcat-eclipse-redeploy

**Type:** project · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-20 17:18:51

_Tomcat runs inside Eclipse's managed server; frequent backend Java saves can hang it on redeploy_

Tomcat (the live dev backend at `https://localhost:8443`) runs as a server managed inside Eclipse, not
standalone. Editing/saving backend Java source (`AccountManagerObjects7`, `AccountManagerService7`, etc.)
can trigger Eclipse's auto-redeploy of the running server. Frequent small edit-save cycles — especially
several in quick succession, or overlapping with live traffic (e2e/Playwright/JUnit tests hitting the
server mid-redeploy) — can hang the managed Tomcat instance so it stops responding even to simple
DB-touching REST calls (confirmed live: bare servlet root still responded instantly, but `/rest/login`
hung for minutes). Stephen restarts it manually when this happens.

**Why it matters:** a hang like this looks identical to a DB connection-pool exhaustion or stuck
transaction from the outside — don't assume the backend code itself is broken just because live REST
calls stop responding. Check whether backend Java files were recently/repeatedly saved (by you or a
parallel agent) before chasing a server-side concurrency bug.

**How to apply:** when making backend Java changes while Tomcat is live, batch edits for one fix into a
single round before compiling/letting a redeploy happen, rather than many small edit-save-test cycles.
If live REST calls suddenly hang, first check for recent/ongoing backend source changes (including from
parallel background agents) before diagnosing it as an application bug — see [[llm_no_lying_testing]] for
the general "verify against live backend" discipline this sits alongside.

---

## uat-pb2-issue1-no-universe-on-create

**Type:** project · **Status:** active · **Created:** 2026-08-23 15:00:28 · **Updated:** 2026-08-23 15:00:46

_UAT Issue #1: Ux book creation wizard does not create PB2 olio.pb.book / universe / world; characters land in user home dir_

Issue #1 (UAT 2026-08-23): Creating a picturebook from the Ux does not create a PB2 universe/world.

**Root cause:** The Ux wizard calls `POST /{workObjectId}/create-from-scenes` →
`PictureBookUtil.createFromScenes()` → `OlioContextUtil.getOlioContext()`.
That is the PB1 path: it creates a PB1-style book group but never calls
`PbBookUtil.createBook`, so no `olio.pb.book` record, no `Books` universe, and
no per-book world are created.

**Correct PB2 creation path:** `PbServiceFacade.createChapter(user, dataPath, null, slug, title, ...)`
→ `PbBookUtil.createBook` → `PbOlioContextUtil.getCreateBookContext`.
REST surface: `POST /olio/picture-book/chapter` with no `fromBookObjectId`.
`PbServiceFacade.java:410` explicitly documents null fromBookObjectId = "create a standalone root book".

**Where it is NOT called from:** The Ux wizard never calls `POST /chapter`.
The only callers of `PbBookUtil.createBook` are `PbMigrationUtil` (migration) and
`PbServiceFacade.createChapter` (copy/new-chapter).

**Observed symptom (Stephen, UAT 2026-08-23):** Extracted characters land in the user's home
directory instead of the picturebook world. Because no PB2 world was created, `createCharPerson`
has no `{world}/Characters` group to write into and falls back to the acting user's home.
See `PictureBookUtil.java` OlioContext null-fallback guards (lines ~2902, ~2947).

**Status:** Not fixed — investigate/record only.

**Key files:** PbServiceFacade.java:410, PbBookUtil.java:87, PbPipelineUtil.java:50-51,
PictureBookService.java:330-367, PictureBookUtil.java:2786-2790

---

## uat-pb2-issue2-denoise-scale

**Type:** project · **Status:** active · **Created:** 2026-08-23 15:12:28 · **Updated:** 2026-08-23 15:12:28

_UAT Issue #2: Denoise slider 0-100 in reimage vs 0-1 in SdConfigPanel — bespoke form never replaced_

Issue #2 (UAT 2026-08-23): Denoise slider is inconsistent across SD config forms.

**Root cause:** reimage.js renders a BESPOKE SD config form, not SdConfigPanel.
Its denoise slider uses `api.denoisingStrength()` (the 0-100 scaled range-decorator accessor),
with `min: 0, max: 100, step: 5`, initialized to 75 (reimage.js:135, 295-302).

SdConfigPanel.js renders denoise at `min: 0, max: 1, step: 0.05`, reading
`entity.denoisingStrength` directly (bypassing the api accessor — instConfig() lines 164-168).

User sees "75" on reimage, "0.65" on PB wizard Image Gen — same effective value, different scale.

Stephen's repeated instruction: standardize; stop making custom sd config forms everywhere.
**Fix direction:** reimage.js should use SdConfigPanel instead of its bespoke form.

**Files:** reimage.js:135,295-302; SdConfigPanel.js:224-226,152-174

**Status:** Not fixed — recorded only.

---

## uat-pb2-issue3-no-model-defaults

**Type:** project · **Status:** active · **Created:** 2026-08-23 15:12:28 · **Updated:** 2026-08-23 15:12:28

_UAT Issue #3: PB wizard new-book sdConfig starts from randomImageConfig not user saved defaults_

Issue #3 (UAT 2026-08-23): PB wizard Image Generation does not use model defaults for a new book.

**Root cause:** ensureSdConfig() in pictureBook.js calls am7sd.buildEntity() →
fetchTemplate() → GET /olio/randomImageConfig (server-generated template).
pinPictureBookDefaults() only overrides compositeMode, hires, style — leaves all other fields
(including denoisingStrength, steps, model, sampler, etc.) at whatever the random template returns.

The reimage page (reimage.js) loads the user's stored preferences via loadConfig() from
~/Data/.preferences, so it shows the user's actual saved defaults.

The RESUME path in tryResumeExistingBook() calls getBookSdConfig/applySdConfig correctly —
existing books load their last-used config. Only NEW books in the wizard start from the wrong base.

**Fix direction:** ensureSdConfig() should first try to load from the user's stored "default"
SD config (~/Data/.preferences/sdcfg-default or equivalent) before falling back to randomImageConfig.

**Files:** pictureBook.js:194-214 (ensureSdConfig), pictureBook.js:181-187 (pinPictureBookDefaults),
sdConfig.js:76-91 (fetchTemplate), pictureBook.js:1432-1458 (tryResumeExistingBook)

**Status:** Not fixed — recorded only.

---

## uat-pb2-issues-status

**Type:** project · **Status:** active · **Created:** 2026-08-23 23:19:27 · **Updated:** 2026-08-23 23:21:45

_PB2 UAT issues 1/2/3 fix status — all three implemented and compile-verified 2026-08-23_

---
name: uat-pb2-issues-status
description: PB2 UAT issues 1/2/3 fix status — all three implemented and compile-verified 2026-08-23
metadata:
  type: project
---

All three UAT issues were addressed in the 2026-08-23 session. Backend compiles clean (BUILD SUCCESS on both Objects7 and Service7). Frontend vite build passes; 445 Vitest pass. No E2E/JUnit tests run — live backend required.

**Issue #1 — Book creation wizard does not create PB2 universe/world (IMPLEMENTED, NOT YET E2E TESTED):**
- Root cause: wizard called `createFromScenes` (PB1 path); no `PbBookUtil.createBook()`, no olio.pb.book, no world.
- Ux fix: `sceneExtractor.js` exports `createChapBookRecord(slug, title)` → calls POST `/rest/olio/picture-book/chapter`. `pictureBook.js` wizard step 2 calls `/chapter` first (with slug-collision retry), passes `pb2BookObjectId` to `createFromScenes`, uses `meta.pb2BookObjectId || meta.bookObjectId`.
- Backend fix: `PictureBookUtil.createFromScenes` overloaded with `String pb2BookObjectId` param. When present: loads PB2 book, resolves slug, calls `PbOlioContextUtil.getCreateBookContext` to get book OlioContext, passes it to `PbSubRecordUtil.prepareGroups` (routes sub-records into world groups), overrides returned meta's `bookObjectId` to PB2 book objectId.
- `PictureBookService.createFromScenes` reads `pb2BookObjectId` from params body, passes to new overload.

**Issue #2 — Denoise slider 0-100 in reimage vs 0-1 in SdConfigPanel (FIXED, vite build verified):**
- `reimage.js` entire bespoke SD form replaced with `m(SdConfigPanel, {config: cinst.entity, models: sdModelList, loras: loraList, onChange: ...})`.
- Denoise init changed from `cinst.api.denoisingStrength(75)` to `cinst.entity.denoisingStrength = 0.75` (native 0–1 scale).

**Issue #3 — New book starts from randomImageConfig not user saved defaults (FIXED, vite build verified):**
- `ensureSdConfig()` in `pictureBook.js` now tries `am7sd.loadConfig('sdcfg-default', '~/Data/.preferences')` first; falls back to `buildEntity()`. `pinPictureBookDefaults` runs in both paths.

**Why:** [[uat-pb2-issue1-no-universe-on-create]] [[uat-pb2-issue2-denoise-scale]] [[uat-pb2-issue3-no-model-defaults]]

---


# Type: reference

## reference-chapbook-tika-extraction-objects7

**Type:** reference · **Status:** active · **Created:** 2026-08-27 00:54:39 · **Updated:** 2026-08-27 00:54:39

_ChapBook/office-doc text extraction lives in Objects7 ChapBookUtil.extractPoemText + bounded DocumentUtil.readDocument(byte[],int) 16MB cap; Service7 only delegates PictureBookException->400 — never inline Tika in Service7_

ChapBook document text extraction (for `.doc`/`.docx`/`.rtf`/`text/rtf` poem imports from `data.data`) lives in **Objects7**, not Service7: `ChapBookUtil.extractPoemText(BaseRecord data)` does the content-type dispatch (null/empty/`text/*` → `ByteModelUtil.getValueString` + sanitize; office set → Tika; else → `PictureBookException` with status 400) and `ChapBookUtil.sanitizeText(String)` does the Postgres-safe cleanup (strip U+0000 + C0 controls except \t\n\r, CRLF→LF, blank→null). Office extraction goes through `DocumentUtil.readDocument(byte[] data, int maxChars)` — a bounded overload added alongside the existing unbounded `readDocument(byte[])`, using `BodyContentHandler(maxChars)` with a finite `MAX_EXTRACT_CHARS = 16 MB` cap (security: don't use `setMaxStringLength(-1)`/unbounded). `ChapBookService` is a pure delegate: it calls these and maps `PictureBookException.getStatus()` → `errorResponse(400, ...)`.

**Why:** an earlier fix inlined Apache Tika dispatch directly in `ChapBookService` (Service7 transport), which the architect flagged as a hard-prohibition violation ("no business logic in Service7"). Moved into Objects7 2026-08-26; architect final verdict APPROVED. `DocumentUtil.getStringContent(BaseRecord)` was the pre-existing Objects7 Tika entry point but it does NOT cover rtf-from-bytes and lacks the Postgres sanitize — that's why ChapBookUtil has its own method rather than calling getStringContent directly.
**How to apply:** need to extract text from an uploaded binary document anywhere in the ChapBook/PictureBook path? Call `ChapBookUtil.extractPoemText` (or `DocumentUtil.readDocument(bytes, cap)` for the raw Tika step). Never add a `new Tika()` / content-type dispatch in Service7. See [[feedback-search-existing-olio-utils-first]] and [[feedback-bytestore-access]].

---

## reference-sd-llm-hardware

**Type:** reference · **Status:** active · **Created:** 2026-08-20 15:42:56 · **Updated:** 2026-08-24 15:11:27

_SD at 192.168.1.39 (GTR9 Swarm :7801), LLM at 192.168.1.42 (Spark Ollama :11434) — not interchangeable; .42 crashes under sustained SD load_

Two GPU hosts, and they are NOT interchangeable:

- **192.168.1.39 (Beelink GTR9, AMD Strix Halo iGPU, 128GB unified — 32GB system / 96GB VRAM as of 2026-08-07, considering 64/64).** Runs SwarmUI at `192.168.1.39:7801` (also accessible as `localhost:7801` from the host machine). A local Ollama runs `way-local` (24.5GB) and `qwen3:8b`. Slower per image (FLUX.2 Klein 9B, 3x1024px refs, 24 steps, 1024x768 = **10.64 min gen / 25.6s prep**) but it can hold sustained load. **This is where picture-book / sustained SD work belongs.** Configured as `SD_SERVER=http://192.168.1.39:7801` in docker/entrypoint.sh and `sd.server` in web.xml.
- **192.168.1.42 (DGX Spark, GB10 Blackwell).** SwarmUI at `:7801`, Ollama at `:11434` running `gpt-oss:120b`. Roughly **3x faster** for a single image (~3.3 min for the same request) BUT its **thermal handling is poor — it crashes quickly under sustained load**. Fine for a one-off generation or LLM calls; do NOT route a whole book's back-to-back generations at it. **LLM stays on .42.**

**Installed checkpoints are disjoint**, so switching hosts means switching model names too (verified 2026-08-07):

| checkpoint | GTR9 (.39) | Spark (.42) |
|---|---|---|
| `OfficialStableDiffusion/sd_xl_base_1.0` | yes | no |
| `sdXL_v10VAEFix` | no | yes |
| `flux1Kontext_flux1KontextDev` | no | yes |
| `flux2Klein_9b` | yes | yes |
| `flux2_dev` | yes | no |

A wrong checkpoint name does **not** error — Swarm returns an empty image list that callers log and skip (this is KI-39's "fake pass"). Hence `sd.default.model` config and `SDUtil.resolveModel`.

**Because SD runs on .39 and the big LLM runs on .42, they do not contend for one GPU** — which is why disabling the opportunistic Ollama unload (`llm.ollama.unload=false`) costs nothing here.

Slow FLUX.2 generation on the GTR9 is **iGPU compute, not memory** — 96GB VRAM far exceeds the ~20-30GB FLUX.2 9B needs, and prep (the memory-bound load phase) is only ~25s. See [[feedback-visual-inspection-required]] and [[project-picturebook-backend-redo]].

---
