# AccountManager7 — Known Issues / Backlog

Running list of known issues and out-of-scope refactors surfaced during development. Add entries with date + context. This is a tracker, not a commitment — scheduled work moves into the relevant phase/plan.

---

## Ux752 — Role & Group membership

### KI-1. `auth.group` needs a member picker + list for PERSON / ACCOUNT / USER (2026-06-24, Stephen)
The group object view does not provide a member picker + list for its participant types (`identity.person`, `identity.account`, `system.user`). `auth.role` has a single `members` field driven by the role's `type` enum (`foreignType: "type"`, resolved `USER`→`system.user`, etc.); `auth.group` has no equivalent single discriminator — a group can hold members of all three types simultaneously — so the same single-field pattern does not map cleanly.

**Broader refactor needed (OUT OF SCOPE for the current ISO work):** the `auth.group` "type" handling needs to be reworked so the UI can present per-type member lists/pickers (e.g. three lists, or a type-selector that drives the picker/participant model). This touches the `$flex` + `foreignType` resolution in `membership.js`/`tableListEditor.js`, the group form definition in `formDef.js`, and likely the participation/type model for groups. Until that refactor lands, group membership management from the object view is not available.

**Interim:** group membership can still be managed via the REST `authorization` member endpoint
(`GET /rest/authorization/auth.group/{groupObjectId}/member/null/{actorType}/{actorId}/{enable}`) and the DnD/list flows where present.

---

## Ux752 — Role membership bugs (ISO UAT, 2026-06-24)

Reported by Stephen during ISO 42001 UAT; these regressed (role membership "used to work"). Status as of 2026-06-24:

### KI-2. Add-member picker for role/group: crash + invalid-type + container 404 (FIXED ✅, e2e verified)
Adding a member to a role drove three failures (root: the participant **type** — the role/group `type` enum `USER`/`ACCOUNT`/`PERSON` — was reaching the picker/list/server as a raw enum or `$flex` instead of a model name `system.user`/`identity.account`/`identity.person`):
1. `TypeError: ...reading 'inherits'` (`am7model.inherits(null)`) — null model in the list toolbar/pagination.
2. `list: missing model for type user` — the lowercased enum reached `initParams`.
3. `GET /rest/model/system.user/{id}` **404** — the picker tried to load a *container* for `system.user` (org-scoped, has no group container), and the embedded list leaked the host route's objectId as its container.

**Fixes (all client-side, using model functions — no hardcoded type strings):**
- `core/model.js`: `am7model.inherits` + `isGroup` guard a null/unknown model (`if (!o) return false;`).
- `components/picker.js` `open()`: **single chokepoint** — normalize `opts.type` via `am7view.typeToModel(...)` (USER→system.user, ACCOUNT→identity.account, PERSON→identity.person), and only resolve/require a **container** when the model is a directory/parent (`hasField('groupId') || isGroup() || isParent()`); org-scoped types (system.user) open without a container.
- `views/object.js` `doFieldPicker`: map the resolved participant enum → model before `preparePicker`.
- `components/tableListEditor.js` `resolveTableType`: resolve `$flex` via `typeToModel(entity[foreignType])` (was only handling `$self`).
- `components/membership.js` `addMember`: convert the `$flex`/`foreignType` enum → model; bail (warn) rather than open a picker with a non-model type.
- `components/pagination.js` `getSearchQuery`: skip the query (return empty) if `getModel(resultType)` is null — never send an unknown type to the server (prevents the `ModelSchema.getFields()` NPE).
- `views/list.js` `initParams`: in picker/embedded mode the list container comes only from explicit attrs/nav — never the underlying route's objectId.

**Verified:** `e2e/roleMembers.spec.js` (chromium) passes with **zero console errors** — role view → Members tab → add → participant picker opens with no crash/invalid-type/404.

### KI-3. Role/group members LIST not populating (FIXED ✅, e2e verified)
**Root cause:** the members field is virtual/ephemeral (`foreign` is undefined). Both renderers — `object.js renderMemberListField` and `tableListEditor getValues` — gated the function-driven load (`objectMembers`) on `field.foreign`, so for the members field the load **never fired**: no `am7client.members` call, no error, empty list (and the participation add error KI-3a below compounded it). Also the `.member` add/remove sent `field:"members"` (a virtual field with no server schema) → `Null field schema for auth.role.members`, so adds didn't persist.
**Fixes (client):**
- `object.js renderMemberListField` + `tableListEditor.getValues`: fire the `field.function` load whenever a function is defined (not only for `foreign`).
- `object.js` member-list add/delete, and `membership.js` (`pickMember`/`deleteMember`/`pickEntity`/`deleteEntity`): resolve the participation `field` arg via the **model** field, passing the field name **only** when it defines `participantModel` (else `null` → default participation). Virtual fields like `auth.role.members` → `null`.
**Verified:** `e2e/roleMembersLoad.spec.js` — role view → Members tab → `objectMembers` fires `GET /authorization/auth.role/{id}/system.user/0/100` → member displays (count 1). Add/remove persist (member shows after refresh). Temporary debug logging (`am7client.member`/`members`/`countMembers`, `membership.objectMembers`, `object.modelField`) was added during diagnosis and has been **removed**.

#### (historical) KI-3 original notes — members added OK but didn't display
Observed during UAT after KI-2: a member can be **added** via the picker, and existing members are **filtered out of the picker** (so the membership data is known somewhere), but the members **table** shows "0 items" for any role. Stephen's hypothesis: a **Ux cache issue** — membership is cached and a stale (empty) result is served.
- `am7client.listMembers` caches by key (`LIST-<type>-<id>-<actorType>-<start>-<count>`); if an empty result was cached (e.g. members loaded before the type resolved, or before a member was added) it is reused and the add never invalidates it.
- The members table (`tableListEditor.resolveTableValue`) reads `foreignData[name]` first; `objectMembers` populates it via `am7client.members(...)`. If that returns the (stale-empty) cached list, the table is empty even though the membership exists.
**Diagnose with the new accessor (KI-5):** in the role view, run `__am7page.objectContext()` and inspect `.foreignData` / `.entity.members` — if it holds the members, it's a render/binding bug; if empty, it's the load/cache path. Likely fix: clear the members cache on add/remove (`membership.pickMember`/`deleteMember` → `am7client.clearCache(...)` for the role) and/or bypass cache for the members load.

### KI-4. "View system roles" gating — verify RoleReader (likely already correct)
Reported as gated on Admin; should be `RoleReaders`. `views/list.js` `getAdminButtons` (added 2026-05-11) already allows the system-list toggle for `rs.roleReader` on `auth.role` (and `permissionReader` on `auth.permission`). The ISO context roles were also added to `setContextRoles`. If viewing is still blocked for a RoleReader-only user, the gate is elsewhere (navigation/menu) — needs a RoleReader-only user in the harness to confirm.

---

## Ux752 — List view bugs (ISO UAT, 2026-06-25)

### KI-7. Tabular sort — embedded/picker FIXED ✅; system-role slice still OPEN
Two distinct causes:
- **Embedded/popup picker lists (FIXED):** the list's `update()` early-returned in embedded/picker mode (`views/list.js`), so a sort click never re-queried. Added `pagination.sortPending()` and let `update()` proceed when a sort change is pending → the popup picker now re-sorts.
- **System roles (OPEN):** when `pages.listSystem` + `auth.role`/`permission` (`pagination.js:195`), the list is served from a pre-loaded client array (`page.application.systemRoles.slice(...)`) and is **not** re-sorted by the query — the sort icon has no effect. Fix direction: client-side sort the sliced array by `pages.sort`/`order` in that branch. (Parent-navigated, non-system role lists go through the search API and carry `q.sort`/`order`.)

### KI-8. List overflow/scroll not showing when the browser console is open — OPEN
With the dev console open (reduced viewport height), the list body doesn't show its overflow/scroll — content is clipped without a scrollbar. Likely a layout/overflow class issue (a flex child needs `min-h-0` / `overflow-auto`, or a Tailwind class was dropped/needs a rebuild). Needs visual inspection at a constrained height; check the list body container's overflow + the flex chain in `views/list.js renderContent` / `pageLayout`.

### KI-9. List view empty after switching back from group/parent navigation — OPEN (2026-07-06, Stephen)
Switching the list toolbar toggle from "navigate by group/parent" back to plain **list view** shows an initially **empty** list; a manual refresh populates it. Switching to **icon view** works correctly, so the data/query is fine — it looks like the list-view path misses a pagination reset (start/count or query key) following the group/parent-navigation change. Fix direction: when toggling back to list view after a group/parent nav change, reset list pagination and re-run the query (compare the icon-view toggle path, which resets correctly, against the list-view toggle path). Likely in `views/list.js` toggle handling / `pagination.js` state carried over from the parent-nav mode.

## Picture Book (2026-07-06, Stephen)

### KI-10. Cancel does not actually stop scene extraction or image generation — OPEN (2026-07-06, Stephen)
Hitting cancel during scene extraction or image generation does not abort the in-flight backend operation — it keeps running to completion. The picture-book generate/extract endpoints in `AccountManagerService7/.../PictureBookService.java` run synchronously with no cancellation/interrupt token, so a client "cancel" only affects the UI, not the server work (mirrors the ISO 42001 run model, which is also synchronous with no cancel endpoint). Fix direction: introduce a cancellation signal the long-running loops (chunked extraction, per-scene image pipeline) check between stages — e.g. a per-request/per-book cancel flag set via a small endpoint or WebSocket chirp, polled at chunk/stage boundaries — and have the UI cancel button hit it. Client side, ensure the cancel also stops awaiting/rendering the in-flight request. Related: KI (portrait persistence/reuse) work in the same service.

## Ux752 — Debug tooling (2026-06-24)

### KI-6. List view: favorites / system-library mode not resetting on reload (FIXED)
Once the favorites or system-library (admin) toggle was picked, the `systemList` flag persisted across list-view reloads (a prior change stopped the embedded list/pagination from resetting, which over-broadly stopped the main list from clearing the flag). `views/list.js` now tracks a `lastRouteKey` (type + objectId) and resets `systemList` only when the route actually changes for a non-embedded/non-picker list — so the toggle persists within a view (survives redraws, which call `initParams` via `onupdate`) but clears on genuine navigation/reload.

### KI-5. Browser-console object-view context accessor (ADDED)
`views/object.js` now exposes the live object-view context for console inspection:
`__am7page.objectContext()` → `{ type, objectId, isNew, tabIndex, entity, inst, pinst, foreignData, valuesState }`
(`window.__am7page` is set in `main.js`). The closure returns current values, so it reflects what the Ux is actually using — useful for spotting bad/missing fields and errors on the viewed object.

---

## Backend / test infrastructure — secrets

### KI-11. Test LLM credentials sit in `resource.properties` as plaintext — move to an encrypted DB connection via a setup utility — OPEN (2026-07-08, Stephen)
Surfaced during PageIndex work: a hosted (Azure OpenAI) chat connection was needed, so the api key was put in `AccountManagerObjects7/src/test/resources/resource.properties` (`test.llm.openai.authorizationToken=…`) and the Agent7 copy. These files are git-**tracked** (not gitignored; HEAD keeps tokens blank), so a blanket `git add -A` would write live keys into history. Both the verifier and security-reviewer flagged this as blocking-for-commit. (Confirmed: keys are only in the uncommitted working tree, not in committed history.)

**Desired end state:** keep the key out of properties entirely by storing it **encrypted in the test DB**, reusing the existing `system.connection.apiKey` encrypted field (`EncryptFieldProvider` + org vault — already the intended home for credentials). Tests resolve the connection / chat config by name (e.g. the `contentAnalysis` chat config → its connection); the vault decrypts at use time; nothing sensitive on disk.

**Proposed approach (Stephen):** a command-line utility that creates a test chat config + connection with the encrypted key, runnable against the Objects7 unit-test DB — e.g. a small tool under `AccountManagerObjects7/src/test`.

**Design notes / thoughts:**
- **Form:** a test-scoped setup utility that **reuses existing connection/chat-config creation code** (the `ChatLibraryUtil` "Connections" library / `LLMConnectionManager` / the Console7 connection patterns) rather than new infra. Cleanest to run inside the existing test harness so it inherits the bootstrapped `IOSystem` + org + vault (`BaseTest`): either a **gated JUnit setup method** (env-gated / `@Disabled`-by-default, e.g. `SetupTestConnections#createOpenAiConnection`) or a `main` under `src/test`. Run once per environment.
- **Idempotent, env-driven (make it dumb — no state to track).** The utility reconciles the connection from env vars on **every** run: if the key env var is **set**, add/overwrite the connection (encrypt the key into `apiKey`); if it's **not set**, clear/delete the connection (or blank the key). No one-time handoff — run it as part of test setup or standalone, and it converges to whatever the environment says. The key thus only ever lives in the environment + encrypted in the DB, never in a tracked file.
- **Security model (what this does and doesn't protect).** The DB `apiKey` is enciphered with the org's **vaulted (asymmetric) key**, so it's protected at rest — safe from repo/disk/plaintext-`resource.properties` leakage, which is the actual goal here. Caveat (accepted): code running in the **same environment** with the vault context could still decrypt/extract it — the vault has to decrypt for legitimate use, so this defends against leakage, not against local code execution. Acceptable for a test-credential.
- **Vault prerequisite:** `system.connection.apiKey` uses `EncryptFieldProvider`, which needs the org to have an initialized vault (`crypto.vaultExt`). Setup must ensure the test org vault is initialized.
- **Consumption:** unit tests resolve the connection/chat config by URN/name from the DB — no `test.llm.*.authorizationToken` in properties.
- **Scope:** chat/LLM credentials. Embeddings currently use the **LOCAL** service (no key), so no embedding key is at risk today; if a hosted embedding is adopted later, route its credential through a connection the same way (embeddings currently read type/server/token from properties, not a connection — small refactor).
- **Belt-and-suspenders:** add a pre-commit / CI guard rejecting a non-empty `test.*.authorizationToken` in any tracked `resource.properties`, so the plaintext path can't regress.
- **Interim (today):** keys live only in the uncommitted working tree; never `git add -A`; commit `resource.properties` only with blank tokens.
