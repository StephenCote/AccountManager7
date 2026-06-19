# ISO 42001 — Implementation Plan & Progress Tracker

**Purpose:** The living tracker for building the ISO 42001 subsystem + the enterprise-readiness work. Update the **Status** and **Progress log** as phases complete. Authoritative design is [`iso42001-design.md`](iso42001-design.md); requirements in [`iso42001.md`](iso42001.md); enterprise context in [`enterpriseReadiness.md`](enterpriseReadiness.md). Start each phase from [`iso42001-prompt-template.md`](iso42001-prompt-template.md).

**Last updated:** 2026-06-19

Status legend: ⬜ Not started · 🟡 In progress · ✅ Done · ⏸ Blocked

---

## Standing constraints (apply to EVERY phase)

These are non-negotiable and are baked into the prompt template. A phase is not "done" if any are violated.

1. **Integrated system tests, not mocks.** All services are live. "Tested" = real CRUD/flows exercised through `AccessPoint` / live service layer against the real DB. No `typeof`/`.parse()`/skipped tests. The Objects7 `BaseTest` pattern already provides this — reuse it.
2. **Test organization: `/ISO42001`.** All backend tests run against this org (`OrganizationEnumType.DEVELOPMENT`), created if absent.
3. **Admin creates users only.** Use the org Admin user ONLY to create test users and assign roles. **Never run a test assertion as Admin.** Every operation under test runs as a non-admin role user.
4. **DB reset policy.** The test `resource.properties` may set `test.db.reset=true` to rebuild the test schema (one-shot, BaseTest auto-clears it). That is the ONLY sanctioned reset path. **Never** drop/recreate the docker postgres container; **never** directly `TRUNCATE`/`DROP`/`DELETE` tables, schema, or data. Committed default: `test.db.reset=false`.
5. **Frontend tests** use `ensureSharedTestUser()` (Ux752 `e2e/helpers/api.js`) — never admin.
6. **Honesty.** Report exactly what passed and what could not be tested. No "tested"/"working" claims without a green run shown. Flag judgment calls (⚠) and ask before deviating from the spec.

---

## Phase tracker

| # | Phase | Status | Gate (definition of done) | Design ref |
|---|---|---|---|---|
| 1 | Foundation — module, 7 models, RBAC roles, `/ISO42001` test harness | ⬜ | `mvn -pl iso42001 test` green: `TestISO42001Models` CRUD + RBAC, all as non-admin users | §2, §6.2, §6.3, §7, §9A.1 |
| 2 | Statistical engine & scoring | ⬜ | Tests vs hand-checked fixtures: Mann-Whitney U, Chi-square, Fisher, Kruskal-Wallis, Cohen's d, odds ratio, Cramér's V, verdict logic | §4 (`iso42001.md`), §12.2 |
| 3 | Execution engine (Tier-1/Tier-2, seeded interleaving, verbatim logging) | ⬜ | A real run vs a live endpoint produces reproducible, logged, persisted results | §11 Phase 3 |
| 4 | Bias module (start: ATTR/HIRE/REF critical set) | ⬜ | A run scores + verdicts a protected-class comparison end-to-end | [`iso42001-bias.md`](iso42001-bias.md) |
| 5 | Reporting & signed PDF export | ⬜ | Result set → report JSON → signed PDF; signature block valid | §4, §12.3 |
| 6 | Certification (request/approve/sign/verify/revoke + message thread) | ⬜ | Full lifecycle; signature verifies; revoke invalidates | §3, §2.7–2.8 |
| 7 | REST & MCP (`ISO42001Service`, fold in `ComplianceService`) | ⬜ | JUnit per endpoint via live service layer + MCP tool round-trip | §6.4, §1.3–1.4, [`../ISO42001Plan.md`](../ISO42001Plan.md) |
| 8 | Ux752 feature views + `compliance` profile | ⬜ | Vitest + Playwright (`ensureSharedTestUser()`): suite renders, RBAC gating, profile carve-out | [`iso42001.md`](iso42001.md) §9, design §9A |
| 9 | Docker Compose template (`deploy/` + `generate.sh`) | ⬜ | Clean host → `make generate && make up` → working compliance appliance | [`enterpriseReadiness.md`](enterpriseReadiness.md) |
| 10 | Integration & validation | ⬜ | Full run → report → certify → export vs live endpoints; audit-artifact review | §11 Phase 9 |

### Parallel enterprise tracks (do not block the ISO core)

| Track | Status | Gate | Ref |
|---|---|---|---|
| E1 | SSO federation completion (splice `TokenFilter`/`LoginService` TOKEN stub + JWKS trust + JIT provisioning) | ⬜ | OIDC login against a live IdP issues an AM7 session; JIT-provisioned user | `enterpriseReadiness.md` gap #1 |
| E2 | SCIM completion (deprovisioning semantics, enterprise ext, Roles/Entitlements, conformance) | ⬜ | Live IdP create→update→group→disable→re-enable→delete, all org-scoped + audited | `enterpriseReadiness.md` SCIM plan |
| E3 | GA version pinning + dependency scanning | ⬜ | Build on GA Java/Jakarta; scan in place | `enterpriseReadiness.md` P0 |
| E4 | OpenAPI + API versioning + rate limiting; metrics/health | ⬜ | Spec generated; `/health` live; limiter enforced | `enterpriseReadiness.md` P1 |

---

## Per-phase detail

### Phase 1 — Foundation
**Architecture (locked):** All ISO 42001 code + models live in their **own** Java/Maven project under packages `org.cote.accountmanager.iso42001.*`. Service7 adds it as a **dependency**; Objects7 is NOT modified. Models register at runtime via `ISO42001ModelNames extends ModelNames` + `use()` (the `OlioModelNames` pattern — design §6.3, §12.1).

**Pinned Maven coordinates (locked 2026-06-19):**

| Field | Value |
|---|---|
| `groupId` | `org.cote.accountmanager` |
| `artifactId` | `AccountManagerISO42001` |
| `version` | `7.0.0-SNAPSHOT` |
| `packaging` | `jar` |
| Project dir | `AccountManagerISO42001/` (sibling of `AccountManagerObjects7`) |

Service7 `<dependency>` uses the same `groupId`/`artifactId`/`version`. (`-pl` short name in build commands is normalized by a project script.)

**Scope:** new project (depends on Objects7 + its `test-jar`); 7 models per design §2 under `src/main/resources/models/iso42001/`; `ISO42001ModelNames` registration class; call `ISO42001ModelNames.use()` in test setup; 6 ISO roles (CamelCase): `ISO42001Testers`, `ISO42001Reporters`, `ISO42001Certifiers`, `ISO42001Readers`, `ISO42001Auditors`, `ISO42001Administrators`.

**Model naming & shape (reconciled 2026-06-19):**
- First-class (group-backed, own `access.roles`): `testConfig`, `testRun`, `report`, `certification`, `certificationRequest` (note: full name `iso42001.certificationRequest`, file `certificationRequestModel.json`).
- Embedded list-element models (inherit `common.nameId` only; no group/constraints/access.roles — gated via their parent): `testResult` (inside `testRun.results`), `reportSection` (inside `report.sections`).
- §2 already carries `access.roles` on report/certification/certificationRequest; **add `access.roles` to testConfig + testRun** (Testers create/update; Readers/Auditors read; Administrators delete/admin).

**Tests:** `TestISO42001Models` — for each of the 5 first-class models: create→read→patch→list→delete via `AccessPoint` as an authorized non-admin role user, plus a negative RBAC assertion (unauthorized user denied). For the 2 embedded models: validate via their parent (a `testRun` round-trips its `results`; a `report` round-trips its `sections`). Admin used only to create the role users.
**DoD:** after `mvn -f AccountManagerObjects7/pom.xml install`, both `cd AccountManagerISO42001 && mvn compile` and `mvn test` show BUILD SUCCESS with the above passing.
**Test harness:** confirmed — Objects7 already publishes a `test-jar` (maven-jar-plugin 3.4.1); Agent7 consumes it. The ISO project's `test-jar` dependency reuses `BaseTest`/`OlioTestUtil` directly; no Objects7 change needed.

*(Add per-phase detail blocks for 2–10 as each is started, copying the gate from the tracker and expanding scope/tests/DoD from the prompt template.)*

---

## Progress log

| Date | Phase | Change | Result |
|---|---|---|---|
| 2026-06-19 | — | Plan + prompt template captured; tracker created | n/a |
| 2026-06-19 | 1 | Pinned Maven coords (`org.cote.accountmanager:AccountManagerISO42001:7.0.0-SNAPSHOT`); confirmed Objects7 test-jar available | Ready |
| 2026-06-19 | 1 | Spot-checked design §2: all 7 model specs complete; base `inherits` all exist. Reconciled 4 inconsistencies — role names → CamelCase 6-role set (incl. Auditors), cert model → `iso42001.certificationRequest`, added `access.roles` to testConfig/testRun, documented embedded vs first-class model test approach | §2 build-ready |

> Append one row per working session: what was attempted, the test command run, and the actual outcome (BUILD SUCCESS/failure, counts). Keep it honest — failures stay logged, not erased.
