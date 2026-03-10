# Backend Implementation Plan for Ux75 Frontend

**Created:** 2026-03-08 | **Status:** Planning

## Summary

The Ux75 frontend has Phases 0-8 complete (client-only). Remaining phases (3.5c, 4, 6-9, 10) require backend endpoints. This plan identifies what exists, what needs modification, and what must be created.

---

## 1. What Already Exists (No Backend Work Needed)

All core CRUD, path, auth, olio, vector, chat, game, voice, face, word, media, WebSocket, and schema READ endpoints exist. These cover:

- **Phase 3.5c** (Workflow Runtime Validation) — all endpoints exist: reimage, narrate, summarize, vectorize, adopt, game interact, chat
- **Phase 10** (Game Feature Validation) — all game endpoints exist: interact, claim/release, state, move, resolve, save/load, outfit, chat

**Action:** These phases only need runtime testing against the running backend. No backend code changes required.

---

## 2. New Backend Work Required

### Priority 1: Compliance Data Aggregation — SEPARATED

**MOVED TO:** `aiDocs/ISO42001Plan.md` — all ISO 42001 compliance work (Ux + backend) is tracked in its own plan per user direction. ComplianceService.java endpoints, bias pattern management, and related backend work are defined there.

---

### Priority 2: WebAuthn Service (Unblocks Phase 8) — COMPLETE

**Effort:** High (3-5 days) | **Risk:** Medium | **Status:** COMPLETE (2026-03-09)

New model + service for passwordless authentication:

**New model:** `auth.webauthnCredential`
- Fields: `credentialId` (string), `publicKey` (blob), `publicKeyPem` (string), `counter` (long), `rpId` (string), `origin` (string), `algorithm` (int), `transports` (list), `lastUsed` (timestamp)
- Inherits: `common.nameId`, `common.directory`

**New enum value:** Add `WEBAUTHN` to `CredentialEnumType.java`

**New Maven dependency:** `webauthn4j-core` in `AccountManagerService7/pom.xml`

**New `WebAuthnService.java` endpoints:**

| Endpoint | Purpose |
|----------|---------|
| `GET /rest/credential/webauthn/register` | Return PublicKeyCredentialCreationOptions |
| `POST /rest/credential/webauthn/register` | Validate attestation, store credential |
| `GET /rest/credential/webauthn/auth?user={path}` | Return PublicKeyCredentialRequestOptions |
| `POST /rest/credential/webauthn/auth` | Validate assertion, issue JWT |
| `GET /rest/credential/webauthn/credentials` | List user's registered credentials |
| `DELETE /rest/credential/webauthn/{credentialId}` | Remove a credential |

**Integration:** Challenge stored in HttpSession. Auth flow terminates by calling existing `TokenService.createJWTToken()`.

---

### Priority 3: Access Request Workflow (Unblocks Phase 9) — COMPLETE

**Effort:** Medium-High (3-5 days) | **Risk:** Medium | **Status:** COMPLETE (2026-03-10)

`AccessRequestService.java` with 5 REST endpoints at `/rest/access/`. Policy-based async approval via `PatternEnumType.APPROVAL` dispatching to 4 operations (`AccessApprovalOperation`, `DelegateApprovalOperation`, `LookupOwnerOperation`, `LookupApproverOperation`). `PolicyEvaluator` supports `PENDING` propagation → `PENDING_OPERATION` response. Auto-provisioning on APPROVE via `MemberUtil.member()`. Spool-based notifications (`SpoolBucketEnumType.APPROVAL`). WebSocket notification stubs added. Integration test `TestApprovalFlow` covers full policy evaluation cycle.

| Endpoint | Purpose |
|----------|---------|
| `GET /rest/access/requests` | List requests (my requests / pending approval / all) |
| `POST /rest/access/requests` | Submit new access request |
| `PATCH /rest/access/requests/{id}` | Approve/deny/update request |
| `GET /rest/access/requestable?type=role` | List requestable resources |
| `POST /rest/access/requests/{id}/notify` | Send notification/reminder |

---

### Priority 4: Schema Write Endpoints (Enhances Phase 6/7)

**Effort:** Medium-High (2-4 days) | **Risk:** HIGH (touches core schema system)

Current `SchemaService.java` is read-only (4 GET endpoints). Phase 6/7 work fine for browsing. Write endpoints enable user-defined models/fields.

| Endpoint | Purpose |
|----------|---------|
| `PUT /rest/schema/{type}` | Update model (add user-defined fields) |
| `POST /rest/schema` | Create user-defined model type |
| `DELETE /rest/schema/{type}` | Delete non-system model |
| `DELETE /rest/schema/{type}/field/{fieldName}` | Remove non-system field |

**Backend work:** Add `isSystem` tracking, implement runtime schema modification in `RecordFactory`/`SchemaUtil`, persist user-defined models to database.

**Deferred** — read-only schema browser works fine. Write capabilities are not blocking any frontend work.

---

### Priority 5: Feature Configuration (Optional Enhancement)

**Effort:** Low (1 day) | **Risk:** Low

Optional server-side feature enablement per organization. Currently handled client-side with build profiles.

| Endpoint | Purpose |
|----------|---------|
| `GET /rest/config/features` | Return enabled features for current org |
| `PUT /rest/config/features` | Update enabled features (admin only) |

**Can be deferred indefinitely** — client-side build profiles work.

---

## 3. Implementation Order

| Order | Item | Effort | Unblocks | Notes |
|-------|------|--------|----------|-------|
| 1 | Runtime validation (Phase 3.5c + 10) | Test only | Phases 3.5c, 10 | No backend changes — just test |
| 2 | Compliance endpoints | 2-3d | ISO 42001 | **SEPARATED** — see `aiDocs/ISO42001Plan.md` |
| 3 | WebAuthn service | 3-5d | Phase 8 | **COMPLETE** — `WebAuthnService.java`, `auth.webauthnCredential` model, `WEBAUTHN` enum, `webauthn4j-core` dep |
| 4 | Access Request workflow | 3-5d | Phase 9 | **COMPLETE** — `AccessRequestService.java`, 4 policy operations, `PolicyEvaluator` PENDING support, auto-provisioning, WebSocket stubs |
| 5 | Schema write endpoints | 2-4d | Phase 6/7 write | High risk, low urgency |
| 6 | Feature configuration | 1d | None | Optional |

---

## 4. Key Backend Files

| File | Role |
|------|------|
| `AccountManagerService7/src/main/java/org/cote/rest/services/` | All REST service classes |
| `AccountManagerService7/src/main/webapp/WEB-INF/web.xml` | Servlet mappings, CORS, security |
| `AccountManagerObjects7/src/main/java/org/cote/accountmanager/schema/type/` | Enum types |
| `AccountManagerObjects7/src/main/resources/models/` | JSON model definitions |
| `AccountManagerService7/pom.xml` | Maven dependencies |

## 5. Cross-Cutting Concerns

- **CORS:** Already configured for `http://localhost:8899` (Vite dev server)
- **Auth:** Use `@RolesAllowed` on all new endpoints (except WebAuthn auth which is pre-auth)
- **Serialization:** Follow existing pattern: `JSONUtil.exportObject()` for responses, `RecordDeserializerConfig.getFilteredModule()` for request bodies
- **Testing:** Each new service needs unit tests in corresponding test directory
