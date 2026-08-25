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
- `feedback-bytestore-access` -- Never read/write a byte_store field with raw .get()/.set() — use ByteModelUtil, since data may be compressed and/or encrypted
- `feedback-chapbook-authorize-scene-gotcha` -- authorizeSceneAccess queries data.note not olio.pb.scene — generateSceneImage cannot be used for ChapBook scenes
- `feedback-deflection-patterns` -- Stephen's repeated correction — stop shirking responsibility; \"pre-existing\" never discharges ownership of a test or bug I authored
- `feedback-likeInherits-noop` -- likeInherits in ModelSchema is metadata-only — no DDL or field-inheritance effect
- `feedback-llm-literal-null-strings` -- LLM-extracted JSON fields can contain the literal string \"null\"/\"n/a\"/\"unknown\" instead of being absent or blank — guard for that explicitly
- `feedback-memory-active-use` -- Memory system requires active search+write calls, not just relying on the SessionStart hook
- `feedback-nested-fk-cache-staleness` -- CacheDBSearch only invalidates a cached record by its own schema+identity — updating a nested foreign field elsewhere doesn't invalidate parents that embed it
- `feedback-no-irreversibility-ceremony` -- Don't build phased ceremony (pre-flight tests, write-but-don't-register steps) around schema decisions being irreversible — the test DB is a resettable container
- `feedback-no-rest-mocking` -- Never mock the REST/servlet layer (HttpServletRequest, ServletContext, UserPrincipal) to test business logic in-process — test through Objects7 directly or the real Ux/REST stack
- `feedback-objects7-skiptest` -- Objects7 POM skips tests by default; always pass -DskipTests=false or the run silently no-ops
- `feedback-own-it-no-defending` -- When something is wrong in code I changed, say so plainly and fix it — no self-defense, no lengthy justification, no attributing to agents
- `feedback-patch-no-cascade` -- AccessPoint.update()/PATCH only writes fields at the model level you called it on — it does not walk down and patch foreign/nested objects, with a few named exceptions
- `feedback-pb2-completion-overclaimed` -- Stephen rejected PB2/ChapBook 'complete/green' claims as overclaimed; treat those status memories as unverified until re-audited with real tests + visual proof
- `feedback-planmost-json-build-100args` -- planMost(true) recursive expansion hits PostgreSQL 100-arg JSON_BUILD_OBJECT limit on olio.pb.book — use targeted search with explicit request instead
- `feedback-referenced-field-patch-no-cascade` -- common.attributeList's \"attributes\" field (referenced-table storage) never persists via a parent-record copyRecord patch — must create/update the attribute record itself directly
- `feedback-scope-discipline` -- Don't drive-by-fix issues spotted outside the current task's scope — note them, don't touch them, unless asked
- `feedback-sd-config-consistency` -- Stephen has raised SD-config inconsistency across reimage/pictureBook workflows multiple times; treat as unresolved until values (not just slider markup) are verified consistent
- `feedback-search-existing-olio-utils-first` -- Before writing any custom record-persistence/patching logic in Olio, search for an existing utility that already does it — don't hand-roll
- `feedback-test-only-instrumentation` -- Debug/inspection hooks (e.g. emit-to-disk) belong in the test itself, never wired into production code
- `feedback-use-real-test-content` -- use the user's actual provided documents/characters for PictureBook (and similar) test content instead of inventing synthetic stand-ins
- `feedback-validate-dont-workaround-bad-queries` -- When a query/input is invalid (e.g. filters on a virtual/computed field), validate and reject with a clear error — don't build resolution logic to make it \"work\
- `feedback-visual-inspection-required` -- For generative image/content pipelines, a passing persistence/decode test is not proof the output is correct — actually look at the emitted output
- `playwright-docker-e2e-gotchas` -- WebSocket stub + dist freshness required for Playwright tests against the Docker stack

## project
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
- `project-pb2-chapbook-remediation-plan` -- Recorded evidence-based PB2+ChapBook remediation plan (src/aiDocs/PictureBook2ChapBookRemediationPlan.md) + verified blockers, corrections (M3 fixed), stack port 9443, real poem corpus
- `project-pb2-open-gaps` -- PB2 all phases done 2026-08-23 including Phase 1b; only Phase 3b (ComfyUI, optional) remains
- `project-pb2-remaining-work-status` -- B1/B2/B3/D3/M1/M3 implementation status from 2026-08-24 session — complete with architect-required fix
- `project-pb5-phase-status` -- Phase 5 workflow canvas complete — Test button, Stale recheck, DONE_UNVERIFIED color, 15 Playwright tests
- `project-pb6-phase-status` -- Phase 6 (Migration) status: PbMigrationUtil + TestPbMigration green
- `project-pb6b-phase-status` -- Phase 6b (Interactive Canvas Backend) complete: PbNodeExecutor + TestPbCanvas green
- `project-pb6c-phase-status` -- Phase 6c (SD config persistability) complete: S1-S6 all done, all tests green
- `project-picturebook-backend-redo` -- PictureBook feature backend persistence redo — charPerson/portrait/landscape not saved, reference images unused
- `project-service-testing-docker` -- Service7/Tomcat testing via Docker — database mapping, clean-env procedure, hot-deploy steps, Playwright command
- `testing-db-reset` -- Database reset rules: am7db and am7test resettable; am72db NEVER touched at all — no DDL, no migrations, no SQL
- `testing-olio-org-seed` -- Olio seed data loads per-organization and takes minutes on first use; reuse a single stable test org rather than random or multiple org names
- `tomcat-eclipse-redeploy` -- Tomcat runs inside Eclipse's managed server; frequent backend Java saves can hang it on redeploy
- `uat-pb2-issue1-no-universe-on-create` -- UAT Issue #1: Ux book creation wizard does not create PB2 olio.pb.book / universe / world; characters land in user home dir
- `uat-pb2-issue2-denoise-scale` -- UAT Issue #2: Denoise slider 0-100 in reimage vs 0-1 in SdConfigPanel — bespoke form never replaced
- `uat-pb2-issue3-no-model-defaults` -- UAT Issue #3: PB wizard new-book sdConfig starts from randomImageConfig not user saved defaults
- `uat-pb2-issues-status` -- PB2 UAT issues 1/2/3 fix status — all three implemented and compile-verified 2026-08-23

## reference
- `reference-sd-llm-hardware` -- SD at 192.168.1.39 (GTR9 Swarm :7801), LLM at 192.168.1.42 (Spark Ollama :11434) — not interchangeable; .42 crashes under sustained SD load
