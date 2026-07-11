# AccountManager7 — Known Issues / Backlog

Running list of known issues and out-of-scope refactors surfaced during development. Add entries with date + context. This is a tracker, not a commitment — scheduled work moves into the relevant phase/plan.

---

## Ux752 — Cross-view consistency (2026-07-09, Stephen)

### KI-13. Many Ux752 views are inconsistent — comprehensive refactor needed — OPEN
Stephen's assessment: a significant number of views across Ux752 have drifted inconsistent with each
other — patterns, conventions, and behaviors that should be uniform across the object/list/form views
have diverged view-by-view (see the KI-2/KI-3/KI-7/KI-9 series above for concrete examples of this class
of drift already found and partly fixed piecemeal — picker/list/pagination/toggle state handling each
had their own divergent bug). This entry is the umbrella: the piecemeal fixes are treating symptoms:
**a comprehensive cross-view audit + refactor is needed**, not filed as specific bugs yet.
**Fix direction:** before refactoring, run a discovery pass across `src/views/*.js` and
`src/components/*.js` to catalogue where the same concern (query/pagination state, picker/container
resolution, cache invalidation, list-vs-icon-view toggling, embedded/picker-mode gating, etc.) is
implemented differently in different views, then converge on one shared implementation per concern
(likely consolidating into `views/list.js`/`components/pagination.js`/`components/picker.js` helpers that
every view calls, rather than each view re-implementing its own variant). Scope and sequencing TBD —
this is a backlog placeholder, not a plan.

### KI-16. Range-slider handling is inconsistent (≥5 divergent implementations) and can show durably-wrong values on `olio.charPerson` statistics/personality — OPEN (2026-07-10, Stephen)

Instance of the KI-13 class, investigated in depth per Stephen's request. Two sub-findings — a
composition/consistency bug (client, confirmed by code) and an empirically-verified but distinct
data-correctness bug hit while reproducing (server-side, flagged for backend-specialist) — plus a
full catalogue of the divergent slider implementations across the codebase.

**Reproduction method:** live REST calls (`ensureSharedTestUser()`, org `/Development`) against
Tomcat `:8443`, plus a disposable Playwright script against a live `npx vite dev` (`:8899`) driving
`https://localhost:8443/AccountManagerService7/rest`, viewing `olio.charPerson`
`7d7b730a-f9c3-4c73-9bd1-dd0c11416e90` ("Chananya Slate Hausauer"). The exact "physicalStrength /
physicalEndurance blank on first paint, fixed by refresh" instance Stephen saw was **not** reproduced
verbatim on this record (those two fields painted correctly on first open in every run) — consistent
with a timing/cache-dependent race that isn't 100% reproducible on demand, not a refutation of the
report. What *was* reproduced, 3× independently, is the mechanism below and a same-class symptom on
two sibling sliders of the identical form.

**Finding A — client, confirmed by code + live capture: the nested-model sub-form loader in
`views/object.js` always re-fetches data the page already has, through a cache that is durable
across reloads and blind to field projection.**
- `views/object.js:171-178` (`loadEntity`) loads the whole `olio.charPerson` via
  `am7client.getFull()`, whose doc comment says it exists specifically "to load all nested foreign
  models (personality, statistics, etc.)" — confirmed live: `GET .../olio.charPerson/{id}/full`
  returns a fully-populated nested `statistics` block (real `physicalStrength`, `agility`, etc.)
  before any tab is even clicked.
- Despite that, `modelForm()`'s `form.model && form.property` branch (`views/object.js:1072-1147`,
  used by every `model:true` sub-form — `statisticsRef`, `personalityRef`, `profile`, …) *always*
  fires a second, independent `am7client.search()` for the same sub-object the first time its tab is
  activated (`views/object.js:1100-1119`), regardless of whether `mo` already holds complete data.
  Live capture confirms this second call actually fires (`POST /rest/model/search type=olio.statistics
  request=[physicalStrength,...,health,...]`) every time the Statistics tab opens.
- That second call's result is merged straight into `existing.entity[k]` (`views/object.js:1110-1114`)
  field-by-field, bypassing the `inst.api`/decorator setter path entirely and relying solely on
  Mithril's implicit `m.request` autoredraw — there is no explicit `m.redraw()`, no loading indicator,
  and no distinction shown between "not yet arrived" and "arrived, unchanged."
- That second call is also subject to `am7client`'s query cache, which (a) defaults every query to
  `cache: true` (`core/am7client.js:309`), (b) is backed by a **`localStorage`-persisted, 5-minute-TTL
  cache** (`core/cacheDb.js:11`, `:85-94` — "Persistent, TTL-aware cache backed by localStorage" — it
  survives a full page reload, contradicting the assumption that hitting refresh reliably clears
  staleness), and (c) computes its cache key from `type, order, limit, sortField, startRecord,
  recordCount, userId,` and the filter `fields` — but **never from `request`** (`core/am7client.js`
  `q.key()`, lines 353-367). Two queries against the identical type + filter + sort/paging but with
  *different* requested-field projections (e.g., a narrow list/preview query for `olio.statistics`
  somewhere else in the app vs. this full-field "fix-up" fetch) collide on the same cache key and can
  silently serve each other's differently-shaped payload for up to 5 minutes, spanning multiple page
  loads. This is exactly the shape of bug that would look like "shows a stale/wrong value, sometimes
  fixed by a refresh, sometimes not" — the fix depends on TTL/eviction timing, not a deterministic
  redraw.
- Scope: every `model:true` sub-form in the app is affected (statistics, personality, profile, and
  any future ones), not just charPerson statistics.

**Finding B — server, empirically confirmed live, distinct bug hit while reproducing Finding A (flag
to backend-specialist, not a Ux752 fix):** on the sampled `olio.statistics` record, the fields
`health` (regular `int`, `default: -1`) and `save` (`virtual`, `ComputeProvider`/`PERC20`,
`modelDef.js` ~line 7147) come back **entirely absent** from every query style tried — the default
list-query projection, an explicit `request` array naming them outright, and `/full` — verified 3×
independently including with `cache:false`. Sibling virtual/computed fields on the same record
(`athleticism`, `maximumHealth`, both `ComputeProvider`/`AVG`) *do* compute and serialize correctly in
the same responses, so this isn't "virtual fields never populate" — it's specific to `health`/`save`.
Because the client decorator's fallback (`core/model.js` `rangeDecorator`/`numberDecorator`
`decorateOut`, lines ~598-643: `if (v == undefined) v = (typeof f.default == "number") ? f.default :
0;`) silently substitutes the compiled-in schema default whenever a field is `undefined`, the
`health` slider permanently renders `-1` (a value that looks plausible — it's inside the field's
declared range) with **no UI cue that this is a placeholder, not persisted data** — indistinguishable
from a real, deliberately-set minimum. This can't be fixed by any client-side refresh; the field
simply never arrives. Backend fix direction (not implemented here, out of Ux752 scope): check
`ComputeProvider`'s `PERC20` path for `save`, and whether `health` is actually persisted for
generated characters vs. always `null` (see `objects7-reference.md` computed-field/`ComputeEnumType`
docs). Ux752-side compounding factor worth fixing regardless: `rangeDecorator`/`numberDecorator`
give the "never arrived" and "genuinely equals the default" cases the identical rendering — there is
no way for a user (or QA) to tell them apart from the slider alone.

**Finding C — catalogue of divergent range/slider implementations found across the codebase (the
"spinner vs. label" part of the report), at least five distinct patterns, not two:**
1. `components/formFieldRenderers.js` `renderers.range` (lines 201-273) — the generic, schema-driven
   renderer used by every `formDef.js` field with `format:"range"` (charPerson `statistics`,
   `personality`, LLM `chatConfig` fields like `maxTokens`/`temperature`, etc., via
   `views/object.js`). Renders a slider **and** a companion numeric spinner input side by side; no
   visible text label (`sr-only`); min/max/step derived from the field's schema
   (`minValue`/`maxValue`/`type`); wired through `inst.api`/the decorator pipeline
   (`rangeDecorator`/`numberDecorator` in `core/model.js`); updates live via `oninput`.
2. Bespoke, schema-blind range inputs duplicated near-identically across the image-generation
   workflow panels — `workflows/reimage.js:294-367`, `workflows/reimageApparel.js:44-58`,
   `workflows/pictureBook.js:755-793`, `components/SdConfigPanel.js:95-108` (a fourth near-copy of
   the same widget, only in `SdConfigPanel.js` is it actually deduplicated into one `rangeInput()`
   helper reused by its own callers). Renders a **single** `<input type=range>` with **no** spinner;
   the current value is baked directly into the sibling `<label>` text (e.g.
   `'CFG: ' + cinst.api.cfg()` at `reimage.js:362`, `'Denoising: ' + config.denoisingStrength` at
   `SdConfigPanel.js:170`). Min/max/step are hardcoded per call site, not schema-derived; three of the
   four variants (`reimage.js`, `reimageApparel.js`, `pictureBook.js`) don't even share code with each
   other, let alone with `SdConfigPanel.js` or `formFieldRenderers.range`. Updates live via `oninput`.
3. `components/pdfViewer.js:78-88` — zoom-percentage slider, label rendered **after** the input
   (rather than before, as in pattern 2) and — a distinct behavioral divergence, not just markup —
   updates only on `onchange` (release), not `oninput` (drag), unlike every other slider catalogued
   here.
4. `cardGame/designer/designerCanvas.js:734`, `cardGame/designer/exportDialog.js:168` — one-off
   inline range inputs local to the card-game designer, styled/labeled differently again.
5. `magic8/components/SessionConfigEditor.js` — its own internal `_rangeField(label, section, field,
   min, max, step, fmt, isFloat)` helper (line ~767) reused consistently *within* that one component,
   plus two more inline one-offs at lines 388 and 429 that don't even use its own helper — so Magic8
   alone contains two more distinct patterns. (Magic8/cardGame are separate mini-apps that don't use
   the `am7model`/`formDef` system at all, so they're logged here for completeness per the task's
   "catalogue every implementation" ask, but are lower priority than 1-3 for convergence since they
   don't share the model/decorator infrastructure to converge onto.)

**Fix direction (backlog, not a plan):**
- Finding A: either stop the redundant per-tab-activation fetch when the parent's `getFull()` already
  populated the sub-object (check `am7model.hasIdentity(mo)` *and* whether `mo` already has non-identity
  fields set before firing `am7client.search()`), or at minimum bypass the cache for it (`q.cache(false)`)
  and add `request` (or its join) into `am7client.js` `q.key()` so differently-projected queries against
  the same row can never collide.
- Finding B: hand off to backend-specialist per `troubleshooting.md`'s routing gate (already applied
  here — raw API reproducibly wrong for `health`/`save`, independent of the Ux752 client).
- Finding C: converge on `formFieldRenderers.range` (pattern 1) as the one canonical implementation —
  it's the only one that's schema-driven and decorator-integrated — and port `reimage.js` /
  `reimageApparel.js` / `pictureBook.js` / `SdConfigPanel.js`'s bespoke sliders onto it (they're all
  editing plain config objects, not `am7model` instances, so this likely needs `formFieldRenderers`
  (or a small extraction of just its slider markup) to accept a plain `{value, onInput}` contract
  rather than only an `inst`-backed `rendererCtx`). Leave `pdfViewer.js`'s `onchange`-vs-`oninput`
  choice as a deliberate exception if drag-time re-render is genuinely expensive there — otherwise
  converge it too. Magic8/cardGame: lower priority, address only if/when those subsystems get folded
  into the shared component set.

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

## Service7 — Credential Service (2026-07-09, discovered during PageIndex REST verification)

### KI-14. `CredentialService.newPrimaryCredential` hardcodes the password to the literal "password" — OPEN
`POST /rest/credential/{type}/{objectId}` (`CredentialService.java:82`) never reads the password actually
sent in the request body. `authReq.get(FieldNames.FIELD_CREDENTIAL)` is parsed but discarded — the
parameter list passed to the credential factory is hardcoded: `plist.parameter("password", "password")`.
Every credential created or replaced through this endpoint silently becomes the literal string
`"password"`, regardless of what the caller requested. Discovered while live-verifying the new
`PageIndexService` REST surface: created two test users with a distinct password via this endpoint, both
got `true` (success) responses, but login with that password failed — only `"password"` worked.
**Impact:** any admin/API-created `system.user` credential (test fixtures, provisioning scripts, an admin
resetting a user's password) ends up with a trivial, identical, predictable password no matter what was
specified. Security-relevant; not fixed as part of the PageIndex work (out of scope, deserves a deliberate
fix + test, not a drive-by change). **Fix direction:** replace the hardcoded `"password"` literal with the
actual submitted credential, e.g. `plist.parameter("password", new String((byte[])authReq.get(FieldNames.FIELD_CREDENTIAL)))`
(mirroring how `LoginService`/`getAuthenticatedToken` decode the same field), then add a JUnit/REST test
that creates a credential with a specific password and asserts login succeeds with THAT password and fails
with `"password"` (to catch a regression back to the hardcoded literal).

### KI-15. Same method, `cred.set(FieldNames.FIELD_PRIMARY_KEY, false)` sets a field that doesn't exist on `auth.credential` — OPEN
`CredentialService.java:108` (the "replace active credential" branch, hit when a credential already exists
and the caller is a model administrator) calls `cred.set(FieldNames.FIELD_PRIMARY_KEY, false)`.
`FieldNames.FIELD_PRIMARY_KEY` = `"primaryKey"`, but `auth.credential`'s actual field
(`credentialModel.json`) is named `"primary"` — there is no `FIELD_PRIMARY` constant. Setting a field name
absent from the schema drives `BaseRecord.checkField` → `RecordFactory.newFieldInstance` →
`ErrorUtil.printStackTrace()`, i.e. a full stack trace logged at ERROR on every credential replace, though
the call doesn't hard-fail (best-effort fallback). Observed live in the Tomcat log during the same
PageIndex REST verification session (2026-07-09) — surfaced together with KI-14 because they're the same
method and the same incidental discovery, not because they're the same bug. **Fix direction:** add
`FieldNames.FIELD_PRIMARY = "primary"` and use it at `CredentialService.java:108` (or use the raw string
`"primary"` if a new constant is out of scope), then verify no `ErrorUtil.printStackTrace()` fires on a
credential-replace call.

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

---

## Ux752 / Service7 — Feature requests

### KI-17. Gallery/Group export to ZIP — FEATURE REQUEST (2026-07-10, Stephen)

From any gallery view, add an **Export** button that: (a) walks the group's contents, (b) builds a ZIP
containing each item's extracted data (images/documents/audio/video/text as actual files; for any object
that **isn't** a content-bearing model like `data.data`, include its **full JSON** — `toFullString()` —
instead of trying to extract file content), (c) persists the ZIP (as a `data.stream`-backed object given
likely size, mirroring how large uploads already work), and (d) once an export exists for a gallery, shows
a download link in the gallery view instead of (or alongside) the Export button.

**Grounding research (file:line, so a future implementer isn't starting from zero):**

- **Object-walk model (mirror the logic, not the output):** `AccountManagerConsole7/.../console/actions/ExportAction.java` — `exportGroup()` (lines 65-81) walks a `data.group`'s children by type (`FIELD_GROUP_ID` query, line 74), recursing into child groups when `--recurse` is set; `exportObject()` (lines 83-110) re-fetches each record fully (`planMost(true)`, line 85) then either extracts `data.data` bytes (via `StreamSegmentUtil.streamToEnd()` if stream-backed, else `ByteModelUtil.getValue()`, lines 96-99) or falls back to `toFullString()` JSON (lines 107-109) for anything else — **this fallback is exactly the behavior Stephen asked for**, already precedented here. **What it does NOT do:** build an archive — it writes loose files to a mirrored directory tree on local disk (`FileUtil.emitFile`, no `java.util.zip` usage at all). Reuse the walk/extract logic, not the "dump to disk" output.
- **Large-binary → stream-object pattern (the "likely a stream reference given size" part):** `AccountManagerObjects7/.../util/StreamUtil.java`, `streamToData()` (lines 450-546) — reads in 16KB chunks into memory up to `STREAM_CUTOFF` (default 1MB, lines 73-74); under the cutoff, bytes go straight into `data.data.dataBytesStore`; over it, it flushes into a `data.stream` record built from `data.stream_segment` chunks (lines 489-496) and links it via `data.data.stream` (the `data.streamExt` model, one field: `stream` → `data.stream`). The upload-side servlet exercising this end to end is `AccountManagerService7/.../servlets/MediaFormServlet.java` (`doPost`, lines 78-144) → `StreamUtil.streamToData(...)` (line 131). **A generated export ZIP should be persisted the same way** — build the archive, then hand its bytes to the same size-cutoff logic (either call `streamToData`-equivalent logic directly, or route through the same servlet-adjacent path) rather than inventing a second storage convention.
- **No multi-entry ZIP writer exists server-side today:** `AccountManagerObjects7/.../util/ZipUtil.java` (57 lines, read in full) is **gzip-only** (`GZIPOutputStream`/`GZIPInputStream`) — no `ZipOutputStream`/`ZipEntry` anywhere in Objects7/Service7/Console7. This is genuinely new code: a small `ZipArchiveUtil`-style helper wrapping `java.util.zip.ZipOutputStream` with one entry per exported item (named files for `data.data` extractions, `<name>.json` for the JSON-fallback case) is needed. (There IS a working multi-entry ZIP example in the codebase, just client-side/JS: `AccountManagerUx752/src/cardGame/designer/exportPipeline.js` uses `window.JSZip` — `zip.file(...)` per asset (line 213), `zip.generateAsync({type:"blob", compression:"DEFLATE"})` (lines 239-243) — useful as a *reference for the archive-building shape*, but building the ZIP server-side is almost certainly right here since gallery items already live server-side as `data.stream`/blob content, and a large gallery (video/audio) could be too big to responsibly re-fetch client-side just to re-upload as one blob.)
- **Serving the finished ZIP back for download — a directly-reusable precedent exists** (the research pass that fed this entry missed it — found on a follow-up check): `AccountManagerService7/.../rest/services/ISO42001Service.java` (~lines 218-249) has exactly this shape already: a report model holds a foreign FK to a `data.data` export artifact (`report.get("exportedPdf")`); the endpoint resolves the FK, does `IOSystem...getReader().populate(data, new String[]{FieldNames.FIELD_BYTE_STORE})`, and returns `Response.entity(bytes).type(...).header("Content-Disposition", "inline; filename=\"...\"")`; if the FK is null it 404s with **"Report has no exported PDF; POST /report/{id}/export first"** — the exact "generate then link" flow this feature needs. **Mirror this pattern**: add an `exportedArchive`-style foreign field (on `auth.group`, or a small sibling model if per-group metadata doesn't belong directly on the group record), a `POST .../export` endpoint that runs the walk+zip+persist, and a `GET .../export` endpoint with the same resolve→populate→stream-or-byte-response→`Content-Disposition: attachment` shape (use `attachment`, not `inline`, so the browser downloads rather than tries to render a ZIP). For a stream-backed (large) export, serve via the existing `StreamService`/`StreamSegmentUtil` segment-read path instead of a single `byte[]` response.
- **Ux752 insertion point:** `AccountManagerUx752/src/views/list.js` — gallery/grid rendering is the `gridMode > 0` branch of `getListViewInner()` (~line 876 on). Toolbar buttons are assembled in `getActionButtonBar()` (lines 852-863) from several `get*Buttons()` functions; `getActionButtons(type)` (lines 775-796) is the natural home for a new Export button (same `pagination.button(...)` push pattern used there for existing type-conditional buttons), or `getPageToggleButtons()` (lines 829-837) if it's meant to sit with the other view-mode toggles. The download-link-once-it-exists state needs a small client query against the new `exportedArchive` field/endpoint on group load (no existing "does an export exist" check to reuse — new client code).

**Scope note:** this is a genuinely new feature (new model field, two new REST endpoints, new server-side ZIP-writing utility, new Ux button + state) — not a small patch. Filed here per the tracker's own header ("a tracker, not a commitment") for scoping/sequencing later, not committed to a timeline.

**Status: backend implemented + JUnit-verified (2026-07-10); Ux752 + live REST verification not started.**
- `AccountManagerObjects7/.../util/ZipUtil.java` — extended (not a new class, per Stephen) with
  `createArchive(Map<String,byte[]>)` / `newOrderedEntries()` (`java.util.zip.ZipOutputStream`/`ZipEntry`);
  4 pure unit tests (`TestZipUtil`, no DB) verify round-trip, insertion order, empty map, null-content entries.
- New model `data.groupExport` (`sourceGroup` FK → `auth.group`, `archive` FK → `data.data`, `itemCount`,
  `generatedDate`; one per source group/org, rebuild replaces in place) + `ActionEnumType.EXPORT` (hand-added
  per the `VECTORIZE`/`PAGE_INDEX` precedent, no XSD to regenerate from).
- New `AccountManagerObjects7/.../util/GroupExportUtil.java` — walks a group's children of a given type,
  extracts bytes for content-bearing records (`hasField(dataBytesStore) && hasField(contentType)`, stream-backed
  via `StreamSegmentUtil` or inline via `ByteModelUtil`, mirroring `ExportAction`) or falls back to
  `toFullString()` JSON named `<name>.json` for anything else (the fallback Stephen asked for), builds the
  ZIP via `ZipUtil.createArchive`, and persists it via `StreamUtil.streamToData` (same size-cutoff logic as
  any upload) into a fixed `~/Exports` directory (mirrors `ChatUtil`'s `~/Notes/Summaries` convention, so
  exports don't reappear inside the gallery they're exporting). Rebuild = delete prior container + archive,
  then build fresh (mirrors `PageIndexUtil.createPageIndex`'s "replaces an existing index" semantics).
  **Real bug caught by the JUnit tests, not by inspection:** `StreamUtil.streamToData`'s `groupPath` parameter
  (not just `groupId`) feeds the factory's `ParameterList` — passing `null` there (since the group was already
  resolved by id) left the new `data.data` record's `name` field unset and failed schema validation
  (`\S` pattern) on insert. Fixed by passing the resolved export directory's actual path string.
  **Also caught by JUnit:** `assertEquals(a.get(FIELD), b.get(FIELD))` with two unqualified generic
  `<T> T get(String)` calls as both arguments resolves, in this codebase's JUnit4, to the deprecated
  `assertEquals(Object[], Object[])` overload instead of `assertEquals(Object,Object)` — a real
  `ClassCastException` at runtime, not merely a style nit. Extract to typed local variables first, don't
  inline two ambiguous generic calls into one `assertEquals`.
- `AccessPoint.exportGroup` (canUpdate-gated — derived artifact, mirrors `pageIndex()`/`vectorize()`) and
  `AccessPoint.findGroupExport` (canRead-gated) added, both live-verified via 4 new JUnit tests
  (`TestGroupExport`: extraction + JSON-fallback + rebuild-replaces-prior-archive + cross-user PBAC gate) —
  runs in the default suite, no LLM/embedding involved so no env-flag gate needed, all passing against the
  live DB.
- New `AccountManagerService7/.../rest/services/GroupExportService.java` (`@Path("/groupExport")`) — `POST
  /groupExport/{type}/{groupObjectId}` (build/rebuild), `GET .../{type}/{groupObjectId}` (status/metadata,
  404 with "POST .../export first" if none built yet — mirrors `ISO42001Service`'s PDF pattern), `GET
  .../{type}/{groupObjectId}/download` (byte-store-or-stream-reconstructed response with
  `Content-Disposition: attachment`). Compiles clean against the installed Objects7 jar; **not yet
  live-verified over HTTP** — Tomcat was stopped for this work session; verify via curl (mirroring how
  Tier 1 of the PageIndex REST surface was verified) once it's back up.
- **Not started:** the Ux752 button/download-link UI (the insertion points below are still accurate/unchanged).
