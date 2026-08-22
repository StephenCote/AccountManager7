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
- `project-accountmanager7-overview` -- What AccountManager7 is: schema-first BaseRecord/PBAC platform; sessions open at the GIT ROOT while Maven/modules live under src\ - two different 'project roots'; module map
- `project-ki69-closed` -- KI-69 closed 2026-08-20: age-blind portrait fix in NarrativeUtil with adult fallback, 5 tests green
- `project-pb-castgroup-q15` -- Q15 resolved: olio.pb.castGroup for collective canvas entities — model created 2026-08-21
- `project-pb-security-status` -- TestPbSecurity status: 10/10 green on 2026-08-21
- `project-pb2-open-gaps` -- PB2 all phases done 2026-08-22; remaining: Phase 1b (universe/world IDs in Service7+Ux) and Phase 3b (ComfyUI, optional)
- `project-pb5-phase-status` -- Phase 5a+5b Playwright gates closed — 13/13 tests pass; full Phase 5 exit criterion met
- `project-pb6-phase-status` -- Phase 6 (Migration) status: PbMigrationUtil + TestPbMigration green
- `project-pb6b-phase-status` -- Phase 6b (Interactive Canvas Backend) complete: PbNodeExecutor + TestPbCanvas green
- `project-pb6c-phase-status` -- Phase 6c (SD config persistability) complete: S1-S6 all done, all tests green
- `project-picturebook-backend-redo` -- PictureBook feature backend persistence redo — charPerson/portrait/landscape not saved, reference images unused
- `project-service-testing-docker` -- Service7/Tomcat testing should use the verified docker-compose setup, not a manually-run local Tomcat
- `testing-db-reset` -- Which AM7 databases may be reset: am7db and am7test yes, am72db NEVER; test.db.reset=true drops schema/keys/data on the unit-test DB
- `testing-olio-org-seed` -- Olio seed data loads per-organization and takes minutes on first use; reuse a single stable test org rather than random or multiple org names
- `tomcat-eclipse-redeploy` -- Tomcat runs inside Eclipse's managed server; frequent backend Java saves can hang it on redeploy

## reference
- `reference-sd-llm-hardware` -- The two GPU boxes: local Beelink GTR9 (Strix Halo) for sustained SD work, DGX Spark at 192.168.1.42 for LLM — Spark crashes under sustained load
