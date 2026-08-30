# Memory index

Generated from `memory.db` by `mem.ps1 index` -- do not edit by hand.
Store of record: `C:\Projects\GitHub\AccountManager7\.claude\memory\memory.db` (lives with the project, so it can be shared).
To read or write memories, and for setup notes see `SETUP.md` alongside the DB:

```
powershell -NoProfile -File "C:\Projects\GitHub\AccountManager7\.claude\memory\mem.ps1" get    -Name <slug>
powershell -NoProfile -File "C:\Projects\GitHub\AccountManager7\.claude\memory\mem.ps1" search -Query "<fts query>"
powershell -NoProfile -File "C:\Projects\GitHub\AccountManager7\.claude\memory\mem.ps1" set    -Name <slug> -Description "..." -Type <t> -Body "..."
```


## feedback
- `feedback-accountusers-auto-enroll` -- org startup auto-enrolls every new user in AccountUsers — role-gate tests cannot use fresh-user approach alone
- `feedback-booktype-projection-delete` -- PbBookUtil.bookRequest() missing bookType field causes ChapBook delete 403
- `feedback-breadcrumb-olio-parent-fetch` -- breadcrumb.js hardcodes auth.group fetch for all list routes — fails for olio.world and other parent-type navigation
- `feedback-bytestore-access` -- Never read/write a byte_store field with raw .get()/.set() — use ByteModelUtil, since data may be compressed and/or encrypted
- `feedback-cb-poem-text-projection` -- olio.cb.poem query defaults omit text; any poem read needing text MUST project it — the analyze endpoint silently no-oped (returned success:true) without it
- `feedback-chapbook-authorize-scene-gotcha` -- authorizeSceneAccess queries data.note not olio.pb.scene — generateSceneImage cannot be used for ChapBook scenes
- `feedback-cors-127-post-403` -- Chrome 103+ sends Origin on same-origin POST; CorsFilter blocks 127.0.0.1:9443 if not in allowed origins — fixed in docker-compose.test.yml 2026-08-29
- `feedback-create-test-users-for-roles` -- For role-check tests, create test users with needed role config rather than claiming untestable
- `feedback-deflection-patterns` -- Stephen's repeated correction — stop shirking responsibility; \"pre-existing\" never discharges ownership of a test or bug I authored
- `feedback-docker-no-lan-access` -- Docker Desktop bridge network cannot reach LAN hosts (192.168.1.x) -- SD/LLM server testing requires local Tomcat, not Docker
- `feedback-likeInherits-noop` -- likeInherits in ModelSchema is metadata-only — no DDL or field-inheritance effect
- `feedback-list-cache-bust-pattern` -- pagination.new() alone doesn't bust server /rest/model/search cache — need cache:false + sort by id on return from /new/
- `feedback-llm-always-live` -- LLM at 192.168.1.42 is live during sessions -- never claim LLM paths cannot be tested
- `feedback-llm-literal-null-strings` -- LLM-extracted JSON fields can contain the literal string \"null\"/\"n/a\"/\"unknown\" instead of being absent or blank — guard for that explicitly
- `feedback-membercloud-not-dialog` -- memberCloud is not on page.components.dialog — import directly from workflows/memberCloud.js
- `feedback-memory-active-use` -- Memory system requires active search+write calls, not just relying on the SessionStart hook
- `feedback-nested-fk-cache-staleness` -- CacheDBSearch only invalidates a cached record by its own schema+identity — updating a nested foreign field elsewhere doesn't invalidate parents that embed it
- `feedback-no-irreversibility-ceremony` -- Don't build phased ceremony (pre-flight tests, write-but-don't-register steps) around schema decisions being irreversible — the test DB is a resettable container
- `feedback-no-rest-mocking` -- Never mock the REST/servlet layer (HttpServletRequest, ServletContext, UserPrincipal) to test business logic in-process — test through Objects7 directly or the real Ux/REST stack
- `feedback-objects7-skiptest` -- Objects7 POM skips tests by default; always pass -DskipTests=false or the run silently no-ops
- `feedback-olio-world-principal` -- olio.world Books-universe records are owned by the olio principal, not the request user — use Factory.findUser(OlioContext.OLIO_USER_NAME) for AccessPoint calls
- `feedback-own-it-no-defending` -- When something is wrong in code I changed, say so plainly and fix it — no self-defense, no lengthy justification, no attributing to agents
- `feedback-patch-no-cascade` -- AccessPoint.update()/PATCH only writes fields at the model level you called it on — it does not walk down and patch foreign/nested objects, with a few named exceptions
- `feedback-pb-duplicate-world-retry` -- PictureBook 409 retry was creating duplicate worlds — narrowed catch + check existing books before forking new slug
- `feedback-pb2-completion-overclaimed` -- Stephen rejected PB2/ChapBook 'complete/green' claims as overclaimed; treat those status memories as unverified until re-audited with real tests + visual proof
- `feedback-pbscene-planmost-depth` -- olio.pb.scene /full hits 12-level depth limit via sceneNode→workflow chain — use targeted search instead
- `feedback-planmost-json-build-100args` -- planMost(true) recursive expansion hits PostgreSQL 100-arg JSON_BUILD_OBJECT limit on olio.pb.book — use targeted search with explicit request instead
- `feedback-referenced-field-patch-no-cascade` -- common.attributeList's \"attributes\" field (referenced-table storage) never persists via a parent-record copyRecord patch — must create/update the attribute record itself directly
- `feedback-schema-duplicate-constraints` -- DBUtil Index collision / Column does not exist errors are real schema defects from duplicate inherited constraints — never dismiss as noise
- `feedback-scope-discipline` -- Don't drive-by-fix issues spotted outside the current task's scope — note them, don't touch them, unless asked
- `feedback-sd-config-consistency` -- Stephen has raised SD-config inconsistency across reimage/pictureBook workflows multiple times; treat as unresolved until values (not just slider markup) are verified consistent
- `feedback-sd-default-model-init-param` -- sd.default.model init-param can be empty in web.xml — randomSDConfig() must fall back to sd.model or images silently fail
- `feedback-search-existing-olio-utils-first` -- Before writing any custom record-persistence/patching logic in Olio, search for an existing utility that already does it — don't hand-roll
- `feedback-swarm-model-names-need-extension` -- Swarm .39 model names include .safetensors extension - omitting it causes silent failures
- `feedback-swarm-never-claim-down` -- Never claim Swarm SD server is down — it's live; failures are malformed requests (missing .safetensors on model name)
- `feedback-test-only-instrumentation` -- Debug/inspection hooks (e.g. emit-to-disk) belong in the test itself, never wired into production code
- `feedback-use-real-test-content` -- use the user's actual provided documents/characters for PictureBook (and similar) test content instead of inventing synthetic stand-ins
- `feedback-ux752-vitest-node-mithril-raf` -- Ux752 vitest runs in node env; Mithril captures schedule=requestAnimationFrame at import (null in node) so m.request completions throw 'schedule is not a function' — fix via setupFiles RAF shim (do not import mithril there)
- `feedback-validate-dont-workaround-bad-queries` -- When a query/input is invalid (e.g. filters on a virtual/computed field), validate and reject with a clear error — don't build resolution logic to make it \"work\
- `feedback-visual-inspection-required` -- For generative image/content pipelines, a passing persistence/decode test is not proof the output is correct — actually look at the emitted output
- `ki-issue13-pb-subrec-olio-principal` -- Issue 13 fully fixed: PBAC world-group olio-principal + normalizeGender uppercase — TestPictureBookUtilE2E 1/1 PASS with live LLM+SD 2026-08-29
- `playwright-docker-e2e-gotchas` -- READ FIRST before any Playwright/Docker work: IPv6 localhost fix, WS stub, dist freshness, docker-compose in src/

## project
- `issue-tracker-uat-blockers` -- Three UAT blocker issues fixed 2026-08-25: list picker nav, poem ~/Poems PBAC, text-bigint wiring
- `issue4-chapbook-poem-import` -- ChapBook poem import: UX dead-end (selectedIds not updated) + backend byte sequence error -- both fixed 2026-08-25
- `issue5-sdconfig-defaults` -- SD config defaults bugs in SdConfigPanel + SceneGenerator localStorage overlay -- all fixed 2026-08-25
- `ki-chapbook-sdprompt-design-debt` -- ChapBook olio.pb.scene sdPrompt is a bare string — design debt; full sdConfig-per-scene needed for PB2 redesign parity
- `ki-task-api-key-unknown` -- TASK_API_KEY in entrypoint.sh is remote task-queue auth — inert (task.poll.remote=false), hardcoded JWT default in git history, feature appears dormant
- `project-accountmanager7-overview` -- What AccountManager7 is: schema-first BaseRecord/PBAC platform; sessions open at the GIT ROOT while Maven/modules live under src\ - two different 'project roots'; module map
- `project-chapbook-add-poem-design` -- ChapBook Add Poems UX design — multi-select notes/data with ordering, bulk import via POST /poems
- `project-chapbook-design` -- ChapBook feature design: poetry PictureBook variant with olio.cb.book/poem/set models, theme LLM, landscape-only pipeline, text overlay
- `project-chapbook-image-pipeline` -- ChapBook image render pipeline design: sdPrompt+imageObjectId on scene, renderChapBook direct SDUtil path, bookPageView fallback
- `project-chapbook-test-status` -- ChapBook Playwright gate status
- `project-ki69-closed` -- KI-69 closed 2026-08-20: age-blind portrait fix in NarrativeUtil with adult fallback, 5 tests green
- `project-pb-castgroup-q15` -- Q15 resolved: olio.pb.castGroup for collective canvas entities — model created 2026-08-21
- `project-pb-phase1b-status` -- Phase 1b (universe/world IDs in Service7+Ux) implementation complete and verified
- `project-pb-security-status` -- TestPbSecurity status: 10/10 green on 2026-08-21
- `project-pb2-chapbook-remediation-complete` -- PB2+ChapBook remediation all 14 issues addressed 2026-08-28; ChapBook E2E 9/9 green; issue-13 silent-fail fixed
- `project-pb2-chapbook-remediation-plan` -- Recorded evidence-based PB2+ChapBook remediation plan (src/aiDocs/PictureBook2ChapBookRemediationPlan.md) + verified blockers, corrections (M3 fixed), stack port 9443, real poem corpus
- `project-pb2-new-issues-2026-08-29` -- 8 PB2/ChapBook issues addressed 2026-08-29: picker nav, clear, cache, LLM prompt, SD config, roles, type-picker, error surfacing
- `project-pb5-phase-status` -- Phase 5 workflow canvas complete — Test button, Stale recheck, DONE_UNVERIFIED color, 15 Playwright tests
- `project-pb6-phase-status` -- Phase 6 (Migration) status: PbMigrationUtil + TestPbMigration green
- `project-pb6b-phase-status` -- Phase 6b (Interactive Canvas Backend) complete: PbNodeExecutor + TestPbCanvas green
- `project-pb6c-phase-status` -- Phase 6c (SD config persistability) complete: S1-S6 all done, all tests green
- `project-picturebook-backend-redo` -- PictureBook feature backend persistence redo — charPerson/portrait/landscape not saved, reference images unused
- `project-service-testing-docker` -- READ FIRST for any Docker/Playwright work: docker-compose in src/, am72db vs am7test ports, clean-env, hot-deploy
- `project-world-delete-endpoint` -- DELETE /rest/olio/world/{worldObjectId} — full world wipe; uses olio principal; PB2 book cleanup falls back to direct delete if no PB1 group
- `testing-db-reset` -- Database reset rules: am7db and am7test resettable; am72db NEVER touched at all — no DDL, no migrations, no SQL
- `testing-olio-org-seed` -- Olio seed data loads per-organization and takes minutes on first use; reuse a single stable test org rather than random or multiple org names
- `tomcat-eclipse-redeploy` -- Tomcat runs inside Eclipse's managed server; frequent backend Java saves can hang it on redeploy
- `uat-pb2-issue1-no-universe-on-create` -- UAT Issue #1: Ux book creation wizard does not create PB2 olio.pb.book / universe / world; characters land in user home dir
- `uat-pb2-issue2-denoise-scale` -- UAT Issue #2: Denoise slider 0-100 in reimage vs 0-1 in SdConfigPanel — bespoke form never replaced
- `uat-pb2-issue3-no-model-defaults` -- UAT Issue #3: PB wizard new-book sdConfig starts from randomImageConfig not user saved defaults
- `uat-pb2-issues-status` -- PB2 UAT issues 1/2/3 fix status — all three implemented and compile-verified 2026-08-23

## reference
- `reference-chapbook-tika-extraction-objects7` -- ChapBook/office-doc text extraction lives in Objects7 ChapBookUtil.extractPoemText + bounded DocumentUtil.readDocument(byte[],int) 16MB cap; Service7 only delegates PictureBookException->400 — never inline Tika in Service7
- `reference-sd-llm-hardware` -- SD at 192.168.1.39 (GTR9 Swarm :7801), LLM at 192.168.1.42 (Spark Ollama :11434) — not interchangeable; .42 crashes under sustained SD load
