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
> - **Architecture (locked, §6.3/§12.1):** all ISO 42001 code + models live in the standalone `AccountManagerISO42001` project (`org.cote.accountmanager.iso42001.*`); Service7 depends on it; **do NOT add ISO source/models to Objects7**. Models register at runtime via `ISO42001ModelNames.use()` — call it in test setup.
> - All services are live. **Do not mock or skip tests.** A "test" exercises real CRUD/flows through `AccessPoint` / the live service layer against the real DB. Reuse the Objects7 `BaseTest` pattern (`AccountManagerObjects7/src/test/java/org/cote/accountmanager/objects/tests/BaseTest.java`).
> - **Test organization: `/ISO42001`** (`OrganizationEnumType.DEVELOPMENT`); create it if not initialized (`getTestOrganization()` pattern).
> - **Use the org Admin user ONLY to create test users and assign roles** (`getCreateUser(name, orgContext)` creates via `orgContext.getAdminUser()`). Create the role users this phase needs from the 6 CamelCase ISO roles, e.g. `<<isoTester (ISO42001Testers), isoReader (ISO42001Readers), ...>>`.
> - **NEVER run a test assertion as the Admin user.** Every operation under test runs as a non-admin role user.
> - **Always include a negative RBAC assertion** where access control applies (an unauthorized role user is denied).
> - **DB reset policy:** the test `resource.properties` may set `test.db.reset=true` to rebuild the test schema (one-shot; BaseTest auto-clears). That is the ONLY reset path. **NEVER** drop/recreate the docker postgres container; **NEVER** directly `TRUNCATE`/`DROP`/`DELETE` tables, schema, or data. Keep committed default `test.db.reset=false`.
> - Frontend phases: tests use `ensureSharedTestUser()` (Ux752 `e2e/helpers/api.js`), never admin.
>
> **Tests to write:** `<<test class name(s) + what each asserts; include the negative RBAC case>>`
>
> **Definition of done:**
> - `<<build/compile command>>` succeeds.
> - `<<test command, e.g. cd AccountManagerISO42001 && mvn test -Dtest=TestX>>` shows BUILD SUCCESS with the above passing, all as non-admin users against `/ISO42001`.
> - Report exactly what passed and what (if anything) could not be tested — honestly. No "tested"/"working" claim without a green run shown.
>
> Follow `CLAUDE.md` and the per-module `CLAUDE.md`. Flag any decision where you substituted judgment for the spec (⚠ MY JUDGMENT) and ask before proceeding on it. When done, update `aiDocs/ISO42001/iso42001-implementation-plan.md` (Status + Progress log row).

---

## Filled example — Phase 1 (Foundation)

> **Task: ISO 42001 — Phase 1 Foundation (module, models, RBAC, integrated test harness).**
>
> Implement the foundation of the ISO 42001 subsystem in AccountManager7. Authoritative design: `aiDocs/ISO42001/iso42001-design.md`. Read **§2 (data models), §6.2 (Maven config), §6.3 (model registration), §7 (unit-test strategy), §9A.1 (RBAC roles)** before writing code. Also read `aiDocs/ISO42001/iso42001.md`. Do not invent model fields — use the §2 definitions; if any are incomplete, stop and say so first.
>
> **Architecture (locked — design §6.3, §12.1):** ALL ISO 42001 code + models live in their OWN Java/Maven project (`AccountManagerISO42001`, packages `org.cote.accountmanager.iso42001.*`). Service7 will add it as a dependency. **Do NOT add ISO source or models to Objects7.** Model names register at runtime via `ISO42001ModelNames extends ModelNames` + `use()` (the `OlioModelNames` pattern). Pinned coordinates: `groupId=org.cote.accountmanager`, `artifactId=AccountManagerISO42001`, `version=7.0.0-SNAPSHOT`, `packaging=jar`, dir `AccountManagerISO42001/` (sibling of Objects7).
>
> **Scope (this phase only):**
> 1. Create the new standalone `AccountManagerISO42001` Maven module per §6.2, depending on `AccountManagerObjects7` and its `test-jar` (`<type>test-jar</type><scope>test</scope>`) to reuse `BaseTest` — Objects7 already publishes this test-jar (Agent7 consumes it), so no Objects7 change is needed.
> 2. Add the 7 model JSON schemas under the project's `src/main/resources/models/iso42001/` per §2: `iso42001.testConfig`, `iso42001.testRun`, `iso42001.testResult`, `iso42001.report`, `iso42001.reportSection`, `iso42001.certification`, `iso42001.certificationRequest`. First-class models (testConfig, testRun, report, certification, certificationRequest) carry `access.roles`; testResult + reportSection are embedded list elements (inherit `common.nameId` only, gated via parent). Add `access.roles` to testConfig + testRun (§2 only has them on report/certification/certificationRequest).
> 3. Create `org.cote.accountmanager.iso42001.schema.ISO42001ModelNames` (extends `ModelNames`, declares `MODEL_*` constants + `MODELS` list + idempotent `use()`); call `ISO42001ModelNames.use()` in test setup (as `BaseTest` calls `OlioModelNames.use()`).
> 4. Define the 6 CamelCase ISO roles: `ISO42001Testers`, `ISO42001Reporters`, `ISO42001Certifiers`, `ISO42001Readers`, `ISO42001Auditors`, `ISO42001Administrators` (§9A.1).
>
> **Standing rules:** *(as above — live services, `/ISO42001` org, admin only creates users, never test as admin, reset via property only, never drop container/truncate.)*
>
> **Tests to write:** `TestISO42001Models` — for each of the 7 models: create→read→patch→list→delete via `AccessPoint` as an authorized role user (e.g. `isoTester`/`isoAdmin`), plus a negative RBAC assertion (`isoReader` create is denied). Admin used only to create `isoTester`, `isoReader`, `isoAdmin` with their roles.
>
> **Definition of done:** after `mvn -f AccountManagerObjects7/pom.xml install`, both `cd AccountManagerISO42001 && mvn compile` and `mvn test` show BUILD SUCCESS with `TestISO42001Models` passing, all as non-admin users against `/ISO42001`. Report what passed/failed honestly. Then update the implementation plan.

---

## Filled example — Phase 7 (REST & MCP thin shim) — start of Track B

> Track B note: this is the first phase that needs the appserver. Per the tracker's execution grouping, Tomcat is started once at the top of Track B and Phase 8 (Ux) runs in the same session. The shim adds **no** business logic — it only marshals transport onto the Track-A-tested methods.

> **Task: ISO 42001 — Phase 7: REST & MCP thin shim (`ISO42001Service` + MCP tools/resources).**
>
> Expose the already-built and Track-A-tested ISO engine/service methods over HTTP (REST) and MCP in AccountManager7 — **transport marshaling only, no business logic**. Authoritative design: `aiDocs/ISO42001/iso42001-design.md`. **Read before writing code:** §6.4 (REST `ISO42001Service` endpoint list), §1.3 (MCP resources/tools), §1.4 (agent tools), §6.3 (runtime model registration + where Service7 calls `use()` at startup), §7.2 (test categories — `RestIntegrationTest` = running Service7); plus `aiDocs/ISO42001/iso42001.md` §6.4, the tracker's **Phase 7 row** and the **"Backend-complete-before-shim (locked, 2026-06-23)"** note, and the `AccountManagerService7` + `AccountManagerObjects7` module `CLAUDE.md`. Do not invent endpoints/specs — use the §6.4/§1.3 definitions; if a spec is incomplete, stop and say so before coding.
>
> **Context — this is a THIN SHIM (locked).** All substantive logic already lives in Track A and is unit-tested against the live backend: `engine.TestRunner`/`BiasTestExecutor` (Phase 3), `reporting.ReportGenerator`/`PdfExporter` (Phase 5), `certification.ISO42001CertificationFactory` (create/verify/revoke) + `ISO42001CertificationRequestFactory` (create/append/approve/deny) (Phase 6). Phase 7 only marshals HTTP/JSON-RPC ↔ those methods, applies auth, and serializes. **No new business logic in the shim** — if you find yourself adding domain logic, it belongs in the ISO module with its own Track-A test, not here. Note the method-signature reality from Phase 6: the certification methods take the acting context user as the first argument (`createCertification(user, report, certifier)`, `verifyCertification(user, certification)`, `revokeCertification(user, certification, reason)`, and the request-factory methods take the acting user) — the shim resolves the REST/MCP principal to that AM7 context user and passes it through; the methods already enforce RBAC.
>
> **Scope (this phase only — do not pull Phase 8/Ux forward):**
> 1. `ISO42001Service.java` in `AccountManagerService7/src/main/java/org/cote/rest/services/` (`@Path("/iso42001")`, auto-discovered by `RestServiceConfig`) marshaling the §6.4 endpoints onto the tested methods: test config/run (`TestRunner`), report generate/export/pdf download (`ReportGenerator`/`PdfExporter`, reuse the existing stream/media servlet for the PDF bytes), and certification `request`/`approve`/`deny`/`verify`/`get` (the two Phase-6 factories). Resolve the REST principal to the AM7 context user and pass it through. Fold the existing `ComplianceService` in per the tracker.
> 2. **Service7 startup wiring (the production seam for what Track A did in test setup):** call `ISO42001ModelNames.use()` once at startup (context listener / `RestServiceConfig` init, before requests) so the namespace + schema register, and call `ISO42001Provisioning.ensureRoles(adminUser, orgId)` per initialized org so the 6 ISO roles + the `ISO42001Certifiers`/`Administrators` → system `Approvers`/`RequestUpdaters` entitlement exist in production exactly as in test. Add the `AccountManagerISO42001` dependency to `AccountManagerService7/pom.xml` (§6.2).
> 3. MCP tools + resources (§1.3) on the existing `Am7ToolProvider`/`Am7ResourceProvider` (`IToolProvider`/`IResourceProvider`): tools `iso42001_run_test`, `iso42001_test_status`, `iso42001_report_summary`, `iso42001_certify`; resources `am7://{org}/iso42001/report/{id}`, `…/testrun/{id}`, `…/certification/{id}`. Marshal onto the same tested methods.
>
> **Standing rules (non-negotiable — integrated system testing, not mocks; Track B variant):**
> - **Architecture (locked, §6.3/§12.1):** all ISO logic/models stay in `AccountManagerISO42001` (`org.cote.accountmanager.iso42001.*`); Service7 only *depends on* it and adds the thin REST/MCP layer. **Do NOT add ISO business logic or models to Objects7 or Service7** — the service class is pure transport. **Do NOT modify the PBAC/role engine** (provision via the existing `ISO42001Provisioning` utility only).
> - **Live Tomcat + live DB.** Tests are real HTTP/JSON-RPC round-trips against a **running Service7** (`@Category(RestIntegrationTest.class)`, §7.2) on `am7isotestdb` — no mocks, no skips. Stephen starts Tomcat (Service7 webapp) at the top of this Track-B session; Phase 8 (Ux) follows in the same session to bounce Tomcat minimally.
> - **Test organization `/ISO42001`; admin only creates users + assigns roles** (reuse `ISO42001Provisioning` for roles). **Never assert as admin**; every call under test authenticates as a non-admin ISO role user (REST login → session/token).
> - **Negative RBAC at the boundary (required):** an unauthorized role user (or anonymous) is rejected at the endpoint (HTTP 401/403), and the boundary does not leak past the method's own RBAC.
> - **DB reset policy:** committed `test.db.reset=false`; never drop the container or `TRUNCATE`/`DROP`/`DELETE`.
>
> **Tests to write (`@Category(RestIntegrationTest.class)`, all non-admin against a running Service7):** `TestISO42001RestService` — per §6.4 endpoint group: reachable + correct status/serialization for config/run, report generate→export→pdf download (`%PDF` bytes, `application/pdf`), and the certification flow request→approve→verify→get; assert the boundary enforces RBAC (a non-Certifier approve, and an anonymous call, are rejected) — re-testing only wiring/auth/serialization, not the logic Track A already covers. Plus an MCP round-trip test (`iso42001_certify` / a `…/certification/{id}` resource fetch) exercising the JSON-RPC marshaling. Confirm Service7 startup registered the models + provisioned the roles.
>
> **Definition of done:**
> - `mvn -f AccountManagerObjects7/pom.xml install`, `mvn -f AccountManagerISO42001/pom.xml install`, then Service7 builds and deploys to Tomcat with `ISO42001Service` + the MCP tools/resources discovered.
> - The `RestIntegrationTest` suite shows BUILD SUCCESS against the running Service7, all as non-admin users against `/ISO42001`, with the boundary RBAC + MCP round-trip cases passing.
> - Report exactly what passed and what (if anything) could not be tested — honestly; no "working" claim without a green run shown. Flag any judgment call (⚠). When done, update `aiDocs/ISO42001/iso42001-implementation-plan.md` (Status 7 → ✅ + Progress-log row).
