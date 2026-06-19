# AccountManager7 — Enterprise Readiness Assessment

**Scope:** Objective review of the AM7 framework's readiness for enterprise customers, beyond the ISO 42001 feature work.
**Method:** Code/config review (not a runtime pen-test) of `AccountManagerObjects7`, `AccountManagerService7`, `AccountManagerUx752`, `setup/`, and `aiDocs/`. Confidence is flagged where findings are inferred from configuration rather than verified at runtime.
**Companion docs:** [`iso42001.md`](iso42001.md) (master plan), [`iso42001-design.md`](iso42001-design.md) (build design), [`iso42001-bias.md`](iso42001-bias.md) (bias module).
**Last revised:** 2026-06-19

---

## Top-line verdict

The framework has a **genuinely strong security/data core** (policy-based access control, field-level encryption, WebAuthn, a modern crypto stack) wrapped in a **prototype-grade delivery shell** (no CI/CD, manual install, no API docs, dev-only TLS posture). The hard part — the authz/crypto model — is done well. The gap is the *table-stakes operational and SSO surface* that every enterprise procurement checklist demands.

**Strategic point to confront first:** AM7 is positioned as an **ISO 42001 AI-governance product**. An enterprise security review evaluates the *platform's own* posture before trusting its compliance claims. The genuine gaps are operational and identity-related (SSO federation, CI/CD, GA dependency versions, API surface) — see below. The largest single unlock is **turnkey enterprise SSO**; the token/Bearer plumbing and splice points for it already exist (see gap #1), so this is completion work, not greenfield.

> **Correction (2026-06-19):** An earlier draft of this doc flagged `ssl.verification.disabled=true` and a hardcoded base64 token in `web.xml` as critical security blockers. **Both were mischaracterized.** They are intentional **development scaffolding**: the SSL flag is the standard toggle for talking to **dev-scoped self-signed certs**, and the token belonged to a **prototype task-delegation feature**. Neither reflects production posture. The legitimate enterprise concern that remains is narrower — *config hygiene*: a documented, enforced separation between dev and production configuration so these dev toggles can never ship in a release build. This is now tracked as a P2 hygiene item, not a P0 blocker.

---

## Strengths (real, and worth marketing)

| Area | Finding | Evidence |
|---|---|---|
| **Authorization model** | Participation/policy-based access control (PBAC) with a declarative policy engine, model- and field-level `access.roles`, token-substituted dynamic context — more sophisticated than typical RBAC | `AccountManagerObjects7/.../policy/PolicyUtil.java`, `.../client/AccessPoint.java`, `.../security/AuthorizationUtil.java` |
| **Encryption at rest** | Per-org vault, asymmetric org keypair + rotating symmetric field key, salted KDF | `.../security/VaultService.java` |
| **Passwordless auth** | Full WebAuthn/FIDO2 (counter anti-cloning, multi-authenticator) | `AccountManagerService7/.../WebAuthnService.java`, `auth.webauthnCredential` model |
| **Modern crypto libs** | JJWT 0.12.6, BouncyCastle 1.80, Log4j 2.24.3 (post-Log4Shell), Jackson 2.18.3 — all current | both `pom.xml` |
| **Data layer** | PostgreSQL 17 + pgvector (RAG-ready), tuned connection pool (max 150) | `AccountManagerService7/.../META-INF/context.xml` |
| **Test depth** | ~144 backend JUnit classes (Objects7 especially), 107 Playwright E2E specs, 19 Vitest suites | `*/src/test/`, `AccountManagerUx752/e2e/` |
| **Feature modularity** | The Ux752 feature manifest/profile system is the right primitive for product packaging (the ISO deployment leans on it — see `iso42001.md` §9) | `AccountManagerUx752/src/features.js` |

---

## Critical gaps — block enterprise deployment

These are the "fail at the door" items.

> Two items previously listed here — the `ssl.verification.disabled` flag and the `web.xml` token — were **dev scaffolding, not blockers** (see the correction note above). They were removed from this list and recharacterized as a P2 config-hygiene item.

1. **Incomplete SSO federation (foundation exists).** Auth today is password + WebAuthn + AM7-issued JWT. There is **no turnkey OAuth2/OIDC, SAML, or LDAP/AD federation** — but the plumbing to add it is present, so this is *completion*, not greenfield:
   - `LoginService` exposes `/login/jwt/authenticate` + `/login/jwt/validate`; `getAuthenticatedToken()` has an explicit `CredentialEnumType.TOKEN` branch that is currently a stub (`logger.error("Handle token")`) — the seam for third-party tokens.
   - `TokenFilter` already extracts `Bearer` tokens and resolves them to a user URN via `AM7SigningKeyLocator`. Splice points: (a) extend key resolution to trust an external IdP's JWKS / issuer, (b) add just-in-time provisioning where `getRecordByUrn` returns null.
   - Service is built around a pluggable `auth.provider` and credential-record API tokens.
   - **Gap to close:** OIDC authorization-code + client-credentials flow endpoints, external-issuer JWKS trust + token introspection, JIT user/role provisioning, then SAML and LDAP/AD as separate buyer-segment adapters. Still the largest sales unlock on this list.

2. **SCIM is substantially built — finish conformance + extensions.** *(Corrected: an earlier draft called this "read-only" — wrong.)* `ScimUserService`/`ScimGroupService` implement full GET/POST/PUT/PATCH/DELETE; `ScimService` implements `/ServiceProviderConfig`, `/ResourceTypes`, `/Schemas`, and `/Bulk`; supported by `ScimFilterParser`, `ScimPatchHandler`, `ScimSchemaGenerator`, `ScimErrorHandler`, and 5 JUnit test classes. `createUser` provisions through `AccessPoint` + person record and returns 201/Location. The remaining gap is **enterprise conformance & hardening**, not the write path — see the [SCIM completion plan](#scim-completion-plan) below. The detailed design already exists in [`../SCIM.md`](../SCIM.md).

3. **Pre-release framework versions.** Backend targets **Java 26** and **Jakarta EE 11.0.0-M2** (a *milestone* build), plus **Commons FileUpload 2.0.0-M2** (milestone). *(Confidence: high — read directly from pom.xml.)* → Enterprises standardize on LTS (Java 21) and GA dependencies; milestone builds fail dependency-risk review. Pin to GA, add a Maven enforcer + dependency-vulnerability scan (OWASP/Dependabot).

4. **No containerized app stack.** Install is a documented multi-step manual `mvn` + CLI + filesystem procedure; the ISO design doc's §10 single-container is *planned*, not built. → Deliver the **Docker Compose template** ([plan below](#container--docker-compose-template-plan)) so the product is `docker compose up`-deployable. **CI/CD is intentionally out of scope** — left to the consumer (their own pipeline, the container as-is, or a hosted offering).

---

## Important gaps — table stakes, not blockers

| Gap | Detail | Why enterprises care |
|---|---|---|
| **No OpenAPI/Swagger** | No API spec or generated docs across the Jersey services | Integration teams won't adopt an undocumented API; blocks gateways/SDKs |
| **No API versioning** | Endpoints unversioned (`/rest/`, `/scim/v2`) | No safe path for breaking changes; contractually risky |
| **No rate limiting / throttling** | No limiter or `X-RateLimit-*` headers found | DoS exposure; required by most security reviews |
| **No metrics/tracing/health** | Log4j2 + AuditUtil only; no Micrometer/Prometheus, no `/health` or `/ready`, no OpenTelemetry | Can't run under enterprise SRE/SLO monitoring; breaks k8s probes |
| **No DB migration tooling** | Custom `checkSchema` with a `dropColumns` flag; no Flyway/Liquibase | Auditable, reversible schema change is an audit requirement (`dropColumns` is dangerous) |
| **No documented backup/restore/DR** | Not found in docs | RPO/RTO questions appear in every enterprise contract |
| **CORS allow-list hardcoded** | localhost / `192.168.x.x` in web.xml | Must be config-driven per environment |
| **Operational docs absent** | Rich *design* docs in `aiDocs/`, but no admin/runbook/architecture/API guide | Enterprises need install, upgrade, scaling, incident runbooks |
| **No MFA layering / no HSM / no external secrets vault** | All keys software-side; WebAuthn is the only strong factor | Regulated buyers expect HSM/KMS or HashiCorp/AWS Secrets Manager options |

---

## Multi-tenancy & residency (verify before claiming)

The org-scoped vault and `organizationPath` in tokens suggest **per-organization isolation** exists — the right foundation for multi-tenancy. Tenant isolation guarantees (query scoping, noisy-neighbor, per-tenant key separation) were **not** verified end-to-end. Flag as a **must-prove** item: "can another tenant's data leak?" is a gating enterprise question. **Data residency** (region pinning) has no evidence either way and is likely net-new.

---

## How this folds into the ISO 42001 effort

Sequence platform-hardening alongside the ISO features rather than after:

- **Do first (unblocks everything):** pin to GA Java/Jakarta + dependency scanning, and establish a documented dev/prod config separation (so dev toggles like self-signed-cert SSL verification and prototype tokens cannot leak into release builds). These double as **ISO 42001 evidence** — Clause 7 (Support/resources), A.4 (resources), A.6.2.8 (event logs), and the Statement of Applicability.
- **Do with the ISO backend (design doc Phases 1–6):** add OpenAPI generation (Jersey + `swagger-jaxrs2`), API versioning, rate limiting, `/health` + Micrometer. The new `ISO42001Service` is a clean place to set the pattern, then backfill existing services.
- **Do with the container phase (Phase 8):** the Docker Compose template (see plan below), Flyway migrations, backup/restore runbook. The `init` service already needs to write the `compliance` feature profile — extend it to provision secrets and TLS too. (CI/CD is out of scope — consumer-provided.)
- **SSO/SAML/OIDC + SCIM provisioning:** the largest net-new chunks and highest-value sales unlocks. Independent of ISO 42001 but belong on the same roadmap; OIDC also replaces the hardcoded-token integration pattern.

---

## SCIM completion plan

SCIM 2.0 is mostly implemented (see gap #2). This plan covers the **delta to enterprise-certified provisioning** — the parts that determine whether Okta/Entra ID/Ping will actually integrate cleanly. It complements (does not replace) the design in [`../SCIM.md`](../SCIM.md); the unchecked boxes in that file's checklist are the line-item backlog.

**Stage 1 — Provisioning auth (ties to SSO federation, gap #1).** Most blocking item.
- Issue a dedicated, long-lived **service-account bearer token** for each org's IdP connector, with rotation, scoped to user/group management. The token plumbing exists (`TokenFilter` + credential-record tokens); add the issuance/rotation flow and an admin UI to mint connector tokens.
- `ServiceProviderConfig` advertises `oauthbearertoken` — align reality: support OAuth2 **client-credentials** as the connector auth so IdPs can fetch/refresh tokens, reusing the federation work in gap #1.
- Test: each IdP connector authenticates and is hard-scoped to its org (cross-org returns 404, per `TestScimOrganizationScoping`).

**Stage 2 — Deprovisioning semantics.** IdPs disable users via `PATCH active=false`, not `DELETE`.
- Confirm `PATCH /Users/{id}` with `active:false` maps to a **soft-disable** (`UserStatusEnumType`), reversible by `active:true`, and that `DELETE` is a separate hard-delete. This is the single most common SCIM integration failure.
- Test: disable → user denied login but record retained; re-enable restores; verify group membership behavior on disable.

**Stage 3 — Enterprise User extension.** `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`.
- Map `manager`, `department`, `employeeNumber`, `costCenter`, `organization`, `division` to AM7 person/attribute fields (or an attribute bag). `ResourceTypes` already references the extension URN — complete the adapter + schema mapping.
- Test: round-trip of all extension attributes in `ScimUserAdapter`.

**Stage 4 — Roles & Entitlements resources.** `ScimRoleAdapter` is a TODO in `SCIM.md`.
- Expose `/Roles` and `/Entitlements` (extension resources) mapped to `auth.role` / `auth.permission` participation, so IdPs can drive RBAC.
- Test: assign/revoke role via SCIM reflects in `AccessPoint` entitlements.

**Stage 5 — Protocol correctness & hardening.**
- Pagination: `startIndex` 1-based, correct `totalResults`/`itemsPerPage`/`Resources` envelope.
- `/Bulk`: verify `bulkId` cross-reference resolution and `failOnErrors`.
- Advertised capabilities must be true: `sort`, `etag`, `changePassword` — implement or set the flags to `false`.
- `/Me` self-service endpoint.
- Error format compliance (`application/scim+json`, RFC 7644 §3.12 error body) — covered by `TestScimErrorResponses`; confirm it passes.
- Audit: every provisioning op writes a `system.audit` record (doubles as ISO 42001 A.6.2.8 event-log evidence).

**Stage 6 — Conformance & IdP integration.**
- Run an RFC 7643/7644 conformance suite.
- Validate against **live Okta, Entra ID, and Ping** connectors (each has quirks: Entra's `active` handling, Okta's PATCH path syntax, Ping's group-sync).
- Write a per-IdP integration guide; set `ServiceProviderConfig.documentationUri` to it.

**Gate:** a real IdP completes create → update → group-assign → disable → re-enable → delete against a live AM7 org, with all ops audited and org-scoped, and the conformance suite green.

---

## Container & Docker Compose template plan

**Scope decision:** CI/CD is explicitly **out of scope** here. The deliverable is a self-contained, parameterized **Docker Compose template** that anyone can `up`. Consumers (students, customers, or a hosted offering) plug it into whatever CI/CD they already have, or run it as-is. This is the multi-service alternative to the single mega-container in [`iso42001-design.md`](iso42001-design.md) §10 (one Ubuntu image with supervisord) — Compose decomposes the same components into discrete services, which is easier to host, scale, and reason about.

### Service decomposition

| Service | Image / source | Role |
|---|---|---|
| `db` | `pgvector/pgvector:pg17` | PostgreSQL 17 + pgvector; named volume; healthcheck; tuned via existing [`setup/postgresql.conf`](../../setup/postgresql.conf) |
| `init` | `AccountManagerConsole7` jar (one-shot) | Runs once after `db` healthy: create/verify schema, bootstrap org + admin, **set the feature profile to `compliance`** (or chosen profile). Exits 0; gated by `depends_on: condition: service_completed_successfully` |
| `service` | Tomcat 11 + `AccountManagerService7.war` | REST/WebSocket API; mounts rendered `context.xml` + `/data` volume (keys, vault, certs); env-driven DB + secrets |
| `ux` | nginx + `AccountManagerUx752` build | Serves the Vite `dist/` built with the selected feature profile; reverse-proxies `/api` and `/wss` to `service`; TLS termination |
| `agent` *(optional)* | `AccountManagerAgent7` | Task/agent server, only if the deployment uses task delegation |

### Template generation (the "generate template" ask)

A single source of truth renders all deployment files, so there is no hand-edited drift:

```
deploy/
├── .env.template            # all knobs: image tags, DB creds, org name, admin pw,
│                            #   JWT signing key, TLS cert paths, FEATURE_PROFILE=compliance
├── docker-compose.yml       # references ${VARS} (Compose substitutes natively)
├── templates/
│   ├── context.xml.template     # Tomcat datasource + AM7 params → rendered to service config
│   └── nginx.conf.template      # /api, /wss proxy + TLS (adapt design-doc §10.3)
├── generate.sh              # renders templates + .env, generates secrets if absent
└── Makefile                 # `make generate` / `make up` / `make down` / `make logs`
```

- `generate.sh` (or `make generate`): reads/prompts config, **generates a JWT signing key** and either a self-signed cert or wires consumer-provided certs, renders `context.xml`/`nginx.conf` from templates, writes `.env`. This is the template generator.
- **Profile selection:** `FEATURE_PROFILE` (default `compliance`) drives both the Ux build arg (Vite prunes disabled-feature chunks — `iso42001.md` §9) and the `init` service's feature-config write to `/rest/config/features`. One variable produces an ISO-42001 appliance vs. a fuller install.
- **Secrets:** `.env` is git-ignored; `.env.template` is committed with placeholders. No secrets in the compose file or images. (Resolves the dev-config-hygiene P2 item — release artifacts carry no embedded secrets.)
- **TLS:** real verification on by default in the rendered configs; the dev self-signed path is opt-in via an env flag, never the default.

### Build vs. prebuilt

Support both: (a) a `docker-compose.build.yml` overlay with multi-stage Dockerfiles per service (Maven build for war/jar, Node build for Ux) for self-builders; (b) published image tags pinned in `.env` for consumers who just want to run. The single mega-container in design-doc §10 remains available for the "one image, web setup wizard" use case; Compose is the recommended path for hosting.

### Phases

| Phase | Scope | Gate |
|---|---|---|
| **C1** | `db` + `init` (schema bootstrap + admin + profile) | `docker compose up` reaches a healthy DB and `init` exits 0 with org/admin created |
| **C2** | `service` (Tomcat + war, rendered context.xml, `/data` volume) | API answers `/rest` over the network after `init` completes |
| **C3** | `ux` (nginx + profile-built dist, `/api`+`/wss` proxy, TLS) | Browser loads the app over HTTPS; login + a compliance route work |
| **C4** | `generate.sh` + `.env.template` + Makefile + secret/cert generation | Clean checkout → `make generate && make up` yields a working `compliance` deployment with no manual edits |
| **C5** | Build overlay + pinned prebuilt images + per-service healthchecks/restart policies | Both build and prebuilt paths come up; `docker compose ps` all healthy |

**Gate (overall):** on a fresh host with Docker, `make generate && make up` produces a running ISO-42001 appliance (compliance profile, TLS on, admin provisioned, unrelated features absent from the bundle) with zero source edits and no embedded secrets.

---

## Priority matrix

| Priority | Item | Effort | Enterprise impact |
|---|---|---|---|
| P0 | Pin to GA Java 21 + Jakarta EE GA; dependency scanning | Med | Blocks dependency-risk review |
| P0 | Docker Compose template (`make generate && make up`) | Med | Blocks "deployable product" (CI/CD out of scope — consumer-provided) |
| P1 | Complete OAuth2/OIDC federation (splice `TokenFilter`/`LoginService` TOKEN stub + JWKS trust + JIT provisioning) | Med–High | Largest procurement unlock; foundation exists |
| P1 | SCIM completion (conformance + deprovisioning + extensions; write path already built) | Low–Med | Identity-lifecycle requirement; mostly done |
| P1 | OpenAPI + API versioning + rate limiting | Med | Integration + security review |
| P1 | Metrics/tracing/health endpoints | Med | SRE/SLO operability |
| P2 | Dev/prod config separation (TLS verification + secrets externalized in release builds) | Low | Hygiene — dev scaffolding must not ship to prod |
| P2 | Flyway/Liquibase migrations | Low–Med | Audit/operability |
| P2 | Backup/restore/DR runbook + ops docs | Low–Med | Contractual (RPO/RTO) |
| P2 | SAML, LDAP/AD | Med–High | Specific buyer segments |
| P2 | External secrets vault / HSM/KMS option | Med | Regulated buyers |
| P3 | Verify multi-tenancy isolation; data residency | Med | Must-prove before claiming |

---

## Honesty notes

- This is a **code/config review**, not a runtime pen-test. Items marked *medium confidence* (TLS, multi-tenancy isolation) are inferred from configuration and require runtime verification before being stated as fact in customer-facing material.
- No framework code was changed in producing this assessment — it is analysis only.
