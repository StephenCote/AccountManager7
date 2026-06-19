# ISO 42001 — Reusable Build-Phase Prompt Template

Use this to start any phase from [`iso42001-implementation-plan.md`](iso42001-implementation-plan.md). Copy the template, fill the `<<...>>` placeholders from that phase's row + detail, and paste it as the build session's opening prompt. After the session, update the plan's **Status** and **Progress log**.

The **Standing rules** block is fixed — never weaken it. Only the `<<...>>` sections change per phase.

---

## Template

> **Task: ISO 42001 — <<PHASE NUMBER + NAME>>.**
>
> Implement `<<one-line goal>>` in AccountManager7. Authoritative design: `aiDocs/ISO42001/iso42001-design.md`. **Read these sections before writing code:** `<<§ refs, e.g. §2, §6.3, §7>>`. Also read `aiDocs/ISO42001/iso42001.md` (requirements) and the relevant module `CLAUDE.md`. Do not invent specifications — use the referenced definitions; if a referenced spec is incomplete, stop and say so before coding.
>
> **Scope (this phase only — do not pull work forward):**
> 1. `<<scope item>>`
> 2. `<<scope item>>`
> 3. `<<scope item>>`
>
> **Standing rules (non-negotiable — integrated system testing, not mocks):**
> - All services are live. **Do not mock or skip tests.** A "test" exercises real CRUD/flows through `AccessPoint` / the live service layer against the real DB. Reuse the Objects7 `BaseTest` pattern (`AccountManagerObjects7/src/test/java/org/cote/accountmanager/objects/tests/BaseTest.java`).
> - **Test organization: `/ISO42001`** (`OrganizationEnumType.DEVELOPMENT`); create it if not initialized (`getTestOrganization()` pattern).
> - **Use the org Admin user ONLY to create test users and assign roles** (`getCreateUser(name, orgContext)` creates via `orgContext.getAdminUser()`). Create the role users this phase needs, e.g. `<<isoTester (iso42001testers), isoReader (iso42001readers), ...>>`.
> - **NEVER run a test assertion as the Admin user.** Every operation under test runs as a non-admin role user.
> - **Always include a negative RBAC assertion** where access control applies (an unauthorized role user is denied).
> - **DB reset policy:** the test `resource.properties` may set `test.db.reset=true` to rebuild the test schema (one-shot; BaseTest auto-clears). That is the ONLY reset path. **NEVER** drop/recreate the docker postgres container; **NEVER** directly `TRUNCATE`/`DROP`/`DELETE` tables, schema, or data. Keep committed default `test.db.reset=false`.
> - Frontend phases: tests use `ensureSharedTestUser()` (Ux752 `e2e/helpers/api.js`), never admin.
>
> **Tests to write:** `<<test class name(s) + what each asserts; include the negative RBAC case>>`
>
> **Definition of done:**
> - `<<build/compile command>>` succeeds.
> - `<<test command, e.g. mvn -pl iso42001 test -Dtest=TestX>>` shows BUILD SUCCESS with the above passing, all as non-admin users against `/ISO42001`.
> - Report exactly what passed and what (if anything) could not be tested — honestly. No "tested"/"working" claim without a green run shown.
>
> Follow `CLAUDE.md` and the per-module `CLAUDE.md`. Flag any decision where you substituted judgment for the spec (⚠ MY JUDGMENT) and ask before proceeding on it. When done, update `aiDocs/ISO42001/iso42001-implementation-plan.md` (Status + Progress log row).

---

## Filled example — Phase 1 (Foundation)

> **Task: ISO 42001 — Phase 1 Foundation (module, models, RBAC, integrated test harness).**
>
> Implement the foundation of the ISO 42001 subsystem in AccountManager7. Authoritative design: `aiDocs/ISO42001/iso42001-design.md`. Read **§2 (data models), §6.2 (Maven config), §6.3 (model registration), §7 (unit-test strategy), §9A.1 (RBAC roles)** before writing code. Also read `aiDocs/ISO42001/iso42001.md`. Do not invent model fields — use the §2 definitions; if any are incomplete, stop and say so first.
>
> **Scope (this phase only):**
> 1. Create the adjacent `iso42001` Maven module per §6.2, depending on `AccountManagerObjects7` (and its test-jar so the test harness is reusable).
> 2. Add the 7 model JSON schemas under `models/iso42001/` per §2: `iso42001.testConfig`, `iso42001.testRun`, `iso42001.testResult`, `iso42001.report`, `iso42001.reportSection`, `iso42001.certification`, `iso42001.certRequest` — with `access.roles` blocks.
> 3. Register the `iso42001` namespace to load at runtime (follow §6.3 / `OlioModelNames.use()` pattern).
> 4. Define ISO roles: `iso42001testers`, `iso42001reporters`, `iso42001certifiers`, `iso42001readers`, `iso42001administrators` (§9A.1).
>
> **Standing rules:** *(as above — live services, `/ISO42001` org, admin only creates users, never test as admin, reset via property only, never drop container/truncate.)*
>
> **Tests to write:** `TestISO42001Models` — for each of the 7 models: create→read→patch→list→delete via `AccessPoint` as an authorized role user (e.g. `isoTester`/`isoAdmin`), plus a negative RBAC assertion (`isoReader` create is denied). Admin used only to create `isoTester`, `isoReader`, `isoAdmin` with their roles.
>
> **Definition of done:** `mvn -pl iso42001 compile` and `mvn -pl iso42001 test` both BUILD SUCCESS with `TestISO42001Models` passing, all as non-admin users against `/ISO42001`. Report what passed/failed honestly. Then update the implementation plan.
