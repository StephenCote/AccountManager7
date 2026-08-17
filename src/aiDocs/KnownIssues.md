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

**Findings A and C: FIXED ✅ (2026-07-13).** Finding B remains a backend item (unaddressed here, per its
own routing).
- **Finding A:** `core/model.js` — added `am7model.hasPopulatedData(entity)` (true when at least one
  non-identity/virtual/ephemeral field carries a non-default value). `views/object.js` `modelForm()`
  now skips the redundant `am7client.search()` when the parent's own load already populated the
  sub-object; when it does still need to fire, it now calls `q.cache(false)`. `core/am7client.js`
  `q.key()` now includes the `request` field-projection string (it was computed as `r` but never joined
  into the returned key) so differently-projected queries against the same row can no longer collide.
  Verified via `src/test/subFormFetch.test.js` (Vitest, 8 tests): reverted to pre-fix `am7client.js`/
  `model.js` and confirmed 7/8 failed, then confirmed all pass post-fix.
- **Finding C:** `components/formFieldRenderers.js` — extracted the canonical slider+spinner markup into
  `renderRangeSliderSpinner`, exposed as `formFieldRenderers.renderRange({value,onInput,min,max,step,
  label,disabled,fieldClass,name})` for plain-config callers; `renderers.range` (the existing
  `inst`-backed usage) now delegates to it with no behavior change. Ported onto it: `reimage.js` (5
  sliders), `reimageApparel.js` (4), `pictureBook.js` (4), and `SdConfigPanel.js`'s own `rangeInput()`
  helper (1 definition, 5 call sites). `pdfViewer.js`'s `onchange`-vs-`oninput` left as the deliberate
  exception per the fix direction above (not further investigated); `magic8`/`cardGame` left alone.
  Verified via `src/test/rangeSliderConverge.test.js` (Vitest, 6 tests, real vnode/handler inspection)
  and `e2e/rangeSliderConverge.spec.js` (2 live Playwright tests — charPerson and apparel Reimage
  dialogs — dragging updates value+spinner live, min/max enforced by the real range input).

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

### KI-7. Tabular sort — embedded/picker FIXED ✅; system-role slice FIXED ✅ (2026-07-13)
Two distinct causes:
- **Embedded/popup picker lists (FIXED):** the list's `update()` early-returned in embedded/picker mode (`views/list.js`), so a sort click never re-queried. Added `pagination.sortPending()` and let `update()` proceed when a sort change is pending → the popup picker now re-sorts.
- **System roles (FIXED):** `pagination.js`'s `updatePage()` `pages.listSystem` branch served `page.application["system"+fType].slice(start, start+count)` without ever applying `pages.sort`/`pages.order`. Added `sortClientList()` helper (`pagination.js:47-69,232`); the branch now sorts before slicing. Verified via `src/test/systemRoleSort.test.js` (Vitest): reverted to pre-fix code and confirmed 3/3 tests failed, then confirmed all pass post-fix. Separately discovered (not fixed, backend, out of scope here): `ApplicationUtil.getApplicationProfile` (Objects7) doesn't reflect a just-added `RoleReaders` membership in `userRoles`, blocking a non-admin e2e path to the admin-panel toggle for this list.

### KI-8. List overflow/scroll not showing when the browser console is open — INVESTIGATED, ALREADY FIXED (2026-07-13)
Reproduction attempted at viewport heights 150–400px in both table and grid modes — `.list-results > div.flex-1` (`views/list.js:1004`) already scrolls correctly (verified via `scrollHeight>clientHeight`, programmatic `scrollTop`, and a real mouse-wheel event). `git log -S` shows commit `bd5806af` ("Patch", 2026-06-26, the day after this was filed) already added the missing `overflow-auto`; the production Tailwind build also contains `.min-h-0`/`.overflow-auto`. No code change made — nothing left to fix. Added `e2e/listOverflowScroll.spec.js` as a regression guard.

### KI-9. List view empty after switching back from group/parent navigation — FIXED ✅ (2026-07-13)
Root cause: `pagination.js`'s `stopPaginating()` (called from `views/list.js`'s `onremove`, which fires because toggling container mode off does a real route navigation/unmount) reset `pages` but never reset the `requesting` flag. An in-flight container-lookup fetch resolving after the reset hit its stale-discard branch without clearing `requesting`, permanently stranding it `true` — every subsequent `pagination.update()` no-oped forever at its `if (requesting) return;` guard. The icon-view toggle path never hit this because it re-queries synchronously without an intervening route change/unmount. Fixed: `stopPaginating()` now also resets `requesting = false` (`pagination.js:284-296`). Verified via `e2e/listContainerToggle.spec.js` (live Playwright, `ensureSharedTestUser()`): fails pre-fix (0 rows), passes post-fix (5 rows, no manual refresh).

## Picture Book (2026-07-06, Stephen)

### PictureBook fix plan — priority order (2026-07-22)

Backend (Tomcat/Postgres/Swarm/LLM) is live and was actually exercised against, not just read — see
each entry below for what's code-confirmed vs. live-confirmed vs. corrected after testing. Recommended
implementation order — awaiting Stephen's go-signal before any of this is implemented:

1. **KI-32b** (PathProvider log spam) — one-line severity downgrade. **Live-confirmed 2026-07-22**:
   the exact cascade (ids 1094-1164) fired on a plain live query during this session, unprompted.
2. **KI-32a** (`PictureBookUtil.reset()` non-recursive delete) — new recursive-delete helper, PBAC-safe.
   Root cause and no-cascade-in-`PathProvider` finding both code-confirmed; the log cascade above
   corroborates the orphan is real and current in the live DB.
3. **KI-31** (error/empty LLM content reaching the SD prompt) — one shared guard in
   `resolveScenePrompt`/`resolveLandscapePrompt`/`callLlmInternal`. Code-confirmed, not yet live-tested.
4. **KI-33** (new, found live) — `groupPath`-filtered searches throw `PSQLException: column
   oocn1.grouppath does not exist`. **FIXED ✅ (2026-07-23)** — the "self-join" hypothesis was wrong;
   `oocn1` is the primary table's own alias. Root cause: `groupPath` is a `virtual` field (computed by
   `PathProvider`, no backing DB column), and `StatementUtil` built a WHERE fragment against it like any
   other column. Fixed by resolving such conditions in memory before SQL generation.
5. **KI-29** (apparel reimage SD-config discarded) — parse a real config in `OlioService.reimageApparel`
   instead of passing `null`; reconcile the denoisingStrength scale mismatch. Code-confirmed, not yet
   live-tested (needs a same-owner apparel reimage call).
6. **KI-28** (outfit generation) — **live-tested 2026-07-22, the ownerId-NPE hypothesis did NOT
   reproduce** (200 OK against a fully-populated character). Downgraded from "diagnosed" back to
   "needs a same-owner sparse character to test the real KI-30-adjacent theory" — see the KI-28 entry
   for what was actually tried and why it was inconclusive rather than negative.
7. **KI-10** (cancellation) — port the existing `SummarizeProgress`/`summarizingRefs` pattern from
   `ChatUtil`/`ChatService` onto PictureBook's extraction/image loops.
8. **KI-30** (random-baseline-then-override character creation) — use
   `OlioContextUtil.getOlioContext(user, dataPath)` (confirmed direction, no open design question) +
   `CharacterUtil.randomPerson(ctx, lastName)`, then apply LLM overrides on top.

**KI-27** stays deferred (re-checked, nothing new). Note for whoever implements: `/model/search`
request bodies need `"schema":"io.query"`, not the bare `"query"` some doc examples showed (now fixed
in `model-api.md`/`service7-reference.md`) — cost real time mid-session before the actual app log
(now at `C:\projects\logs\accountManagerService.log`/`-error.log`) revealed the real cause. See
`troubleshooting.md`'s new "Backend PBAC/policy tracing" section for `PolicyUtil.setTrace(true/false)`
when authorization reasoning itself needs inspecting.

### KI-10. Cancel does not actually stop scene extraction or image generation — FIXED ✅ (2026-07-23)
Hitting cancel during scene extraction or image generation does not abort the in-flight backend operation — it keeps running to completion. The picture-book generate/extract endpoints in `AccountManagerService7/.../PictureBookService.java` run synchronously with no cancellation/interrupt token, so a client "cancel" only affects the UI, not the server work (mirrors the ISO 42001 run model, which is also synchronous with no cancel endpoint). Fix direction: introduce a cancellation signal the long-running loops (chunked extraction, per-scene image pipeline) check between stages — e.g. a per-request/per-book cancel flag set via a small endpoint or WebSocket chirp, polled at chunk/stage boundaries — and have the UI cancel button hit it. Client side, ensure the cancel also stops awaiting/rendering the in-flight request. Related: KI (portrait persistence/reuse) work in the same service.

**A ready-made template already exists in the codebase — reuse it, don't invent (2026-07-22):**
`ChatUtil`'s map-reduce summarization already solved exactly this problem via
`olio/llm/SummarizeProgress.java` — a small mutable class with `volatile boolean cancelled` +
`isCancelled()`/`cancel()`, checked at every phase/chunk boundary inside `ChatUtil.java` (lines ~847,
1058, 1076, 1168, 1196, 1236, 1298, 1319), tracked in `ChatService.java:79` via
`Map<String, Map<String, SummarizeProgress>> summarizingRefs` (session → objectId → progress,
`ConcurrentHashMap`), with a real `POST /summarize/cancel` endpoint (`ChatService.java:778-818`) and
cleanup in a `finally` block (`ChatService.java:1164-1170`). `WebSocketService` is wired to PictureBook
**one-way only** today (server→client progress toasts via `PictureBookProgressNotifier`,
`PictureBookService.java:83-91`) — there's no inbound cancel-message handling for PictureBook, and the
only precedent for one (`GameStreamHandler.java:81-85`) is an unimplemented `// TODO` stub, not worth
building on.

**Recommended minimal design:** skip WebSocket; copy the `SummarizeProgress`/`summarizingRefs` pattern
directly. Add a `ConcurrentHashMap<String, AtomicBoolean>` (or a small registry class) in
`PictureBookService` keyed by `workObjectId`/`bookObjectId`, a `POST /{workObjectId}/cancel` endpoint
that sets the flag, and thread it as a parameter into `PictureBookUtil.extractChunkedInternal`
(checkpoint at the chunk loop, `PictureBookUtil.java:940`) and `prepareSceneImagePrompts` (checkpoint
at its loop, `PictureBookUtil.java:2324`) — mirroring how `SummarizeProgress` is threaded through
`ChatUtil`'s map/reduce calls, with the same `finally`-block cleanup on completion. `generateSceneImage`
(`PictureBookUtil.java:1845-2270+`) is a single-scene, single-HTTP-call pipeline, not itself a
multi-scene loop — the "generate all" looping happens client-side, one REST call per scene — so it's a
lower-value checkpoint target than the two loops above.

**Fixed (2026-07-23):** implemented exactly the recommended design above — reused `SummarizeProgress`
itself rather than a near-duplicate flag class. `PictureBookUtil.extractChunkedInternal` and
`prepareSceneImagePrompts` each gained a `SummarizeProgress cancelToken` parameter (nullable,
backward-compatible overloads kept for existing callers), checked once per iteration at the top of
their loops (`extractChunkedInternal`'s chunk loop, `prepareSceneImagePrompts`'s per-scene loop) —
`break` on `cancelToken.isCancelled()`, returning whatever was already extracted/resolved rather than
running to completion. `extractChunkedInternal` also now calls `cancelToken.setTotal(chunks.size())`
and `incrementCurrent()` after each chunk's LLM call (mirroring `ChatUtil.mapSummarize`'s own
progress-tracking use of the same class), so a caller can observe how many chunks actually ran, not
just whether cancellation was requested. `PictureBookService` added a
`private static final Map<String, SummarizeProgress> cancelRegistry` (flat `ConcurrentHashMap`, no
session-scoping needed since each key is the workObjectId/bookObjectId the client already holds),
registers/removes an entry around each of `extractScenesOnly`, `extractChunked`, and
`prepareSceneImagePrompts`'s calls into `PictureBookUtil` (register before, `finally`-remove after —
same lifecycle as `ChatService.summarizingRefs`), and added a new
`POST /{key:[0-9A-Za-z\-]+}/cancel` endpoint that looks up the registry and calls `.cancel()`,
returning `{"cancelled":true|false}` (false, not an error, when nothing is in-flight for that key).
`generateSceneImage`/`regenerateBlurb` were left untouched per the design note above (single-call
pipelines, not multi-item loops).

**Verified:** `TestExtractScenesOnlyCancellationStopsChunkedExtractionEarly`
(`AccountManagerObjects7`, `TestPictureBookFull.java`) — builds a >10,000-char story (forces the
real auto-chunk path, 6 chunks), starts `PictureBookUtil.extractScenesOnly(...)` against the live
Ollama server with a `SummarizeProgress` token, and a background thread that calls `.cancel()` the
moment `cancelToken.getCurrent() >= 1` (i.e., only after a real chunk-extraction LLM call has
actually completed — proves the loop genuinely ran, not a same-request no-op). Asserts
`processedChunks >= 1` (loop ran) and `processedChunks < totalChunks` (loop stopped early). Live run:
processed 2/6 chunks before cancellation took effect, returning the 2 scenes already extracted.
**Confirmed as a genuine regression test, not just a happy-path check:** temporarily disabled just the
loop's `isCancelled()` check (`if (false && cancelToken != null && ...)`, production code only, test
file untouched) and reran — failed exactly as predicted (`processed=6 total=6`, ran to completion
despite the background thread having called `.cancel()`), then restored the check and reran — passes.
Ran via
`mvn -o -pl AccountManagerObjects7 -DskipTests=false -Dtest=TestPictureBookFull#TestExtractScenesOnlyCancellationStopsChunkedExtractionEarly test`
— `Tests run: 1, Failures: 0, Errors: 0` / `BUILD SUCCESS` (both before-fix failure and after-fix
pass observed directly, not assumed).

### KI-30. PictureBook character creation should run the general random-character generator first, then override with LLM-extracted fields — FIXED ✅ (2026-07-23)
Stephen's direction: "Use random character generator for every person first, then override the
settings." Confirmed current behavior doesn't do this: `PictureBookUtil.createCharPerson`
(`AccountManagerObjects7/.../olio/picturebook/PictureBookUtil.java:1195+`) builds the `olio.charPerson`
directly via `Factory.newInstance(MODEL_CHAR_PERSON, ...)` and sets only the specific fields the LLM
extracted (name/firstName/lastName/gender/age/ethnicity/skills, and whatever the async
apparel/statistics enrichment fills in afterward) — it never calls the general population generator,
`CharacterUtil.randomPerson(OlioContext ctx, String preferredLastName)`
(`AccountManagerObjects7/.../olio/CharacterUtil.java:63`), which is what produces a fully-populated
baseline character (statistics, personality/instinct, physical build, etc.) for ordinary Olio
population generation. Building from an empty/mostly-empty record instead of a randomized baseline is
the likely reason characters were observed with sparse data (this session's own live run showed every
generated character marked "no portrait" / "no apparel" / "apparel failed" in the Manage Characters
list) beyond whatever the LLM explicitly extracted.

**Fix direction:** call `CharacterUtil.randomPerson(ctx, ...)` first to get a complete, sensibly-random
baseline person, then apply the LLM-extracted overrides (name, gender, appearance hints, role-derived
detail) on top of that baseline instead of building from scratch. **Open integration question, not
yet resolved:** `randomPerson` takes an `OlioContext`, and it's not yet established whether
`PictureBookUtil`'s character-creation call site has (or should acquire) a full `OlioContext` for this
purpose, or whether a lighter-weight path into the same randomization logic is needed. Whoever picks
this up should check how other non-full-simulation callers of `CharacterUtil`/`OlioContext` handle
this (if any exist) before deciding.

**Integration question resolved (2026-07-22):** `randomPerson` (`CharacterUtil.java:63-159`) only
touches `ctx.getOlioUser()` and `ctx.getWorld()` — it never reads realms/locations/events/population,
just the world's basis-name libraries and its `population/statistics/instincts/behaviors/
personalities/states/stores/profiles` sub-group paths. But there is **no lightweight way to build just
that**: `OlioContext.initialize()` (`OlioContext.java:279-378`) is all-or-nothing — it always loads
full world data and requires at least one `IOlioContextRule.generate()` to return a real
`rootEvent`/region or it throws `OlioException("Failed to find or create a new region")`; no no-op
context rule exists. Every `new OlioContext(cfg)` call in the repo (main and test) does full
`initialize()`. `randomPerson` has exactly one other caller in the whole codebase
(`EvolutionUtil.java:124`, inside live population evolution against an already-initialized realm) —
no precedent for a cheap single-character context.

**Direction confirmed (Stephen, 2026-07-22): use `OlioContextUtil.getOlioContext(user, dataPath)`
directly — it's the existing, already-used API for exactly this, not a workaround.** Same pattern
`GameService`/`OlioService`/`GameStreamHandler` already call; it memoizes per-user in a static map so
`initialize()` is paid once per JVM process/user, not per call. `PictureBookUtil.createCharPerson`
calls this once, gets back the cached `ctx`, calls `randomPerson(ctx, lastName)` for the baseline, then
re-targets/copies the generated `statistics`/`instinct`/`personality`/`state`/`store`/`profile`/`race`/
`alignment` fields onto the book's own `charsGroup`-scoped `charPerson` record before applying the
LLM-extracted overrides on top. No further design question — implement against this API as-is.

**Fixed (2026-07-23):** implemented against the confirmed API exactly as directed above.
`PictureBookUtil.createCharPerson` gained a `dataPath` parameter (the `datagen.path` init-param
value — threaded down from `PictureBookService.extract`/`createFromScenes`, which now read
`context.getInitParameter("datagen.path")` the same way `GameService` already does, and threaded as
a new trailing parameter through `PictureBookUtil.extract`/`createFromScenes` down to
`createCharPerson`). A new `buildRandomBaseline(user, dataPath, lastName, age)` helper calls
`OlioContextUtil.getOlioContext(user, dataPath)` then `CharacterUtil.randomPerson(ctx, lastName)` —
and, since `randomPerson` itself does not roll statistics/personality (confirmed by reading its only
two real call sites, `CharacterUtil.java`'s own population loop and `EvolutionUtil.java`'s birth
path), also calls `StatisticsUtil.rollStatistics`/`rollHeight` and `ProfileUtil.rollPersonality` on
the baseline's sub-records, matching that exact population-generation recipe. `createCharPerson` then:
(1) applies baseline `race`/`alignment` directly onto the in-memory charPerson before `create()`
(plain fields, no separate persist step needed); (2) seeds the existing persisted-instance calls for
`profile`/`statistics`/`store` with the baseline's corresponding sub-record via a new
`copyBaselineFieldValues(source, target)` helper (field-by-field `source.get(n) -> target.set(n, v)`,
excluding identity/path/groupId/name/foreign fields — deliberately NOT `BaseRecord.copyIntoRecord`,
which replaces the target's entire field list rather than merging into it); (3) adds three new
best-effort (not hard-required, unlike profile/statistics/store) persisted-instance blocks for
`instinct`/`personality`/`state`, previously never created at all. LLM-extracted overrides
(name/firstName/lastName/gender/age/ethnicity/trades, and the physical-estimate step's
height/strength/etc.) are applied exactly where they already were, after the baseline seeding —
"baseline first, then override" as directed, not a redesign of the override logic itself.

**Verified:** `TestCreateFromScenesSeedsRandomBaselineOnCharacter` (`AccountManagerObjects7`,
`TestPictureBookFull.java`) — runs `PictureBookUtil.createFromScenes` with a minimal `{name, gender,
role}` client stub (same shape as the pre-existing `TestCreateFromScenesWithClientCharacterStubsCreatesRealCharacters`)
against the live Ollama server and `test.datagen.path`'s real `OlioContext`, then asserts on the
resulting `olio.charPerson`: `race`/`alignment` are populated (never set at all before this fix) and
`instinct`/`personality`/`state` are real persisted records with `id>0` (never created at all before
this fix) — the genuine regression signals for this change. Also asserts `statistics.physicalStrength`/
`agility` are real rolled values (>0, per the fix spec's own suggested check), though note: this
specific assertion turns out to already have passed before this fix too, since
`StatisticsUtil.estimateFromExtractedPhysical` (called later in `createCharPerson`, unrelated to and
unchanged by this fix) unconditionally calls `rollStatistics` regardless of any baseline — recorded
here for honesty rather than overclaiming that one signal as proof of this specific change.
**Confirmed as a genuine regression test for the race/alignment/instinct/personality/state signals** via
the swap-test pattern: temporarily forced `buildRandomBaseline`'s result to `null` (production code
only, test file untouched) and reran — failed exactly as predicted
(`AssertionError: race must be populated from the random baseline (KI-30) ... was never set before
this fix`), then restored the real call and reran — passes. Ran via
`mvn -o -pl AccountManagerObjects7 -DskipTests=false -Dtest=TestPictureBookFull#TestCreateFromScenesSeedsRandomBaselineOnCharacter test`
— `Tests run: 1, Failures: 0, Errors: 0` / `BUILD SUCCESS`. Also reran the three closest pre-existing
PictureBook tests (`TestIsErrorOrEmptyPayloadDetectsErrorShapedAndEmptyLlmContent`,
`TestResetRecursivelyDeletesNestedSceneAndCharacter`,
`TestCreateFromScenesWithClientCharacterStubsCreatesRealCharacters`) together to confirm no regression
from the new `dataPath`/baseline plumbing — `Tests run: 3, Failures: 0, Errors: 0` / `BUILD SUCCESS`.

### KI-31. Scene image generation sends a failed upstream call's error JSON as the SD prompt text — FIXED ✅ (2026-07-23)
Live log evidence:
```
SDUtil - txt2img request: ... prompt=[{"error":"Missing story text and count parameters"}]
SDUtil - txt2img: Swarm returned error: ComfyUI execution error: could not convert string to float: '"Missing story text and count parameters"}'
SDUtil - txt2img error (...) — retrying once with a fresh session
```
Somewhere upstream of the `txt2img` call, whatever step builds the scene/landscape prompt received
(or itself produced) an error response body — `{"error":"Missing story text and count parameters"}`
— and that literal JSON text ended up as the **prompt string** sent to the SD/Swarm backend instead
of being caught and surfaced as a failure. Swarm/ComfyUI then fails trying to parse part of that
string as a number (`could not convert string to float`), and `SDUtil` retries once with a fresh
session (per the WARN log) — masking the real error behind a generic retry rather than surfacing
"prompt generation failed" to the caller.

**Not yet isolated:** the exact string `"Missing story text and count parameters"` does not appear
anywhere in this repo's Java or JS source (grepped both) — it's either a dynamically-assembled
message, or the response body of a call to an endpoint whose validation message wasn't found by a
literal-string search. **Next step for whoever picks this up:** trace which call in the
scene-image/landscape-prompt pipeline (`PictureBookUtil`'s landscape-prompt generation,
`prepareSceneImagePrompts`, or wherever `SDUtil.txt2img`'s `prompt` parameter is assembled) can
receive an error-shaped JSON object as input, and why that isn't caught before being forwarded as
prompt text — likely a missing failure check on an internal call whose response is treated as
"always success" and passed straight through.

**Additional live log evidence (2026-07-22, Stephen) — the bad-prompt shape is not a single fixed
string, it's a whole class of pass-through failures:**
```
Prompt: {"error":"No story text provided"}
Prompt: []
Prompt: {"error":"Please provide the story text and the desired number of scenes to identify."}
```
At least **three distinct error-message wordings** have now been observed landing in the SD prompt
field, not just the one originally logged — meaning whatever validation is upstream produces its
message dynamically (varying by which parameter(s) are missing / which code path validates), and
**every** variant reaches the prompt unchecked, not just one specific phrasing. This rules out
"search for this one literal string" as a viable fix and confirms the bug is structural: **something
in the pipeline never checks for an `error` key (or a non-string/array response) before treating the
whole response body as prompt text.** The bare `[]` case is a second, related failure shape — an
empty **array** (not an error object) also reaches the prompt, consistent with a scene/landscape list
that came back empty being stringified and used as-is instead of being treated as "nothing to
generate, skip or fail" — likely the same missing-guard class as KI-25's `count`-defaults-to-`0` bug
(a call that returns `[]` cleanly with HTTP 200, not an exception, so nothing downstream that only
checks for exceptions/non-200 status would ever catch it). **Refined next step:** don't grep for a
literal message string — instead find every call in the scene/landscape-prompt assembly path whose
result is used as (or concatenated into) prompt text, and add one shared guard there that rejects any
result that is empty, an array, or contains an `error` field, before it ever reaches `SDUtil.txt2img`.

**Root cause confirmed by code read (2026-07-22):** `PictureBookUtil.callLlmInternal`
(`PictureBookUtil.java:829-914`) returns the LLM's raw message content verbatim (line 908:
`return stripThink(resp.getMessage().getContent());`) with **no shape/error validation**. Its two
prompt-building callers only guard against `null`/blank: `resolveScenePrompt`
(`PictureBookUtil.java:503-527`, check at line 515) and `resolveLandscapePrompt`
(`PictureBookUtil.java:677-694`, check at line 688). This is not a missed HTTP-status check —
`Chat.chatInternal` (`Chat.java:3869-4143`) already correctly detects real HTTP-level failures and
returns `null` (`Chat.java:4003-4014`) — the gap is that a **200-OK response whose message content
happens to be error-shaped JSON or an empty array** (a misbehaving/hallucinating model, or a gateway
that echoes JSON errors with status 200) passes the null/blank guard, gets cached via
`updateSceneTextField(...)`, and is later forwarded unmodified into `SWUtil.newSceneTxt2Img(...)`
(`PictureBookUtil.java:2165`, `:2249`) → `SDUtil.txt2img`.

**Fix:** add one shared "looks like an error/empty payload" check — reject content that, after
trimming, starts with `{`/`[` and either parses as JSON containing an `error` key or parses as an
empty array — in `resolveScenePrompt`/`resolveLandscapePrompt` (or centralize it in
`callLlmInternal` itself, right after line 908) so such content is treated the same as `null`/blank:
falling through to the existing fallback path instead of being cached and forwarded to `txt2img`.

**Fixed (2026-07-23):** added `PictureBookUtil.isErrorOrEmptyPayload(String)` — a small, directly
unit-testable static helper (kept LOCAL to the two prompt-resolvers, not centralized in
`callLlmInternal`, since that method has other callers — extract-chunk/extract-scenes/
extract-character/scene-blurb — whose responses are legitimately JSON objects/arrays already
parsed defensively by `parseLlmJsonArray`/`parseLlmJsonObject`; rejecting any `{`/`[`-shaped
content at that shared choke point would risk misclassifying a valid extraction result). Detects
null/blank, an empty JSON array (`[]`), or a JSON object containing an `error` key; `content.trim()`
is checked for a leading `{`/`[` before attempting to parse, so ordinary prose prompt text is never
touched. `resolveScenePrompt` (`PictureBookUtil.java` ~515) and `resolveLandscapePrompt` (~688) now
call it in place of the old `== null || isBlank()` check, so error-shaped/empty content falls
through to the existing fallback (raw concatenation / setting text) instead of being cached via
`updateSceneTextField(...)` and forwarded to `SWUtil.newSceneTxt2Img(...)` → `SDUtil.txt2img`.

**Verified:** `TestIsErrorOrEmptyPayloadDetectsErrorShapedAndEmptyLlmContent`
(`AccountManagerObjects7`, `TestPictureBookFull.java`) — a direct unit test (no live LLM call
needed; this validates a parsing/guard function against crafted input strings, not the LLM
round-trip itself) asserting all three of KI-31's actual observed log-evidence error strings plus a
bare `[]` are detected, that the pre-existing null/blank behavior is preserved, and that real prose
prompt text and legitimately-shaped JSON (no `error` key, non-empty array) are NOT flagged. Ran via
`mvn -o -pl AccountManagerObjects7 -DskipTests=false -Dtest=TestPictureBookFull#TestIsErrorOrEmptyPayloadDetectsErrorShapedAndEmptyLlmContent test`
— `Tests run: 1, Failures: 0, Errors: 0` / `BUILD SUCCESS`.

**Follow-up — root cause of "prompts are still completely broken" found and fixed (2026-07-23,
Stephen reported the fix above wasn't actually working):** live evidence in the running Tomcat
instance's own log (`C:\projects\logs\accountManagerService.log`) showed the SD prompt was, for
different scenes: `"A detailed environment"`, `"Natural lighting consistent with the background.
High quality photograph."` (both `SWUtil.styleClause()`'s bare fallback tail — meaning setting/
action/mood/charNarrations were ALSO empty), and — the actual smoking gun —
`"I'm happy to help identify the most visually compelling scenes, but I need the actual story text
and the number of scenes you'd like selected. Could you please provide those details?"`. That
sentence is prose, not JSON/empty-shaped, so KI-31's `isErrorOrEmptyPayload` guard above (which only
inspects `{`/`[`-leading content) never caught it — it was reaching `SDUtil.txt2img` verbatim, and
because `resolveScenePrompt`'s cache check (`getSceneTextField(scene, "scenePrompt")`) never
validated cached content quality, once poisoned a scene served the identical garbage on every
subsequent regenerate attempt — exactly matching "regenerating doesn't help."

**Actual root cause (confirmed by tracing the prompt CONSTRUCTION, not guessing from the response):**
`SceneGenerationParams.promptTemplateOverride` (`PictureBookUtil.java:146`) is a **single field
applied to three semantically different LLM operations** — `pictureBook.scene-image-prompt`
(`resolveScenePrompt`), `pictureBook.landscape-prompt` (`resolveLandscapePrompt`), and
`pictureBook.extract-scenes` (`extractScenesOnly`) all read the *same* `promptTemplateOverride`
value (`PictureBookService.java` reads it from the client's `"promptTemplate"` JSON field on
`/extract-scenes-only`, `/prepare-scene-image-prompts`, and `/generate-image` alike). Client-side,
`AccountManagerUx752/src/workflows/pictureBook.js`'s **"single" prompt-template mode** (line ~44,
"applies to all (object ref)") sends one user-selected template as the override for every one of
those calls. `pictureBook.extract-scenes`'s real template (`promptTemplate.pictureBook.extract-
scenes.json`) needs `{text}`/`{count}` vars; `resolveScenePrompt`/`resolveLandscapePrompt` only ever
supply `setting`/`action`/`mood`/`charNarrations` — so if a user (or a prior test/experiment) has a
custom template selected in "single" mode, applying the extraction template to an image-prompt call
leaves `{text}`/`{count}` **literally unsubstituted** in the outgoing message. The LLM, sensibly,
responds by asking for the missing story text and scene count — the exact live-observed sentence.

**Fixed (2026-07-23) — three changes, all in `PictureBookUtil.java`:**
1. **Construction-time guard (the real fix):** `callLlmInternal` now checks the fully-substituted
   `userTpl` for any leftover `\{[a-zA-Z][a-zA-Z0-9_]*\}`-shaped token (`UNSUBSTITUTED_PLACEHOLDER`,
   a new class-level `Pattern`) immediately after applying `vars`, and refuses to call the LLM at all
   — logging the exact mismatched promptName and the vars that were supplied — returning `null`
   (triggering each caller's existing fallback) instead of sending a malformed prompt over the
   network. This is a general defense: it catches ANY future template/vars mismatch for this call
   path, not just the specific extract-scenes-vs-image-prompt collision found here.
2. **Response-shape defense in depth:** `isErrorOrEmptyPayload` extended with a `CONVERSATIONAL_
   REFUSAL` pattern (phrases like "I'm happy to help", "I need the actual", "could you please
   provide") and a same-shaped unsubstituted-placeholder check on the LLM's raw response text, so an
   already-malformed response that somehow reaches this guard (e.g. a differently-phrased refusal)
   is still caught before caching.
3. **Cache self-heal:** `resolveScenePrompt`/`resolveLandscapePrompt`'s cache-read (`getSceneTextField`)
   now also runs the cached value through `isErrorOrEmptyPayload` before trusting it — a scene
   poisoned by this bug *before* the fix landed regenerates the next time it's touched instead of
   serving the same broken text forever.

**Client-side fix (2026-07-23, Stephen requested it too):** `AccountManagerUx752/src/workflows/
pictureBook.js`'s `getPromptTemplate(key)` — the single chokepoint deciding which override (if any)
is sent for a given prompt "slot" — previously returned the user's one "single mode" template for
*every* slot unconditionally. It now checks a new `IMAGE_PROMPT_SLOTS = ['landscapePrompt',
'sceneImagePrompt']` list first: for those slots, "single" mode returns `null` (server default)
instead of the extraction-shaped template, since that template's vars were never validated against
what an image-prompt call actually needs — applying it can only leave placeholders unfilled, never
do anything useful. Per-prompt mode is untouched (it already scoped correctly per slot). Added a
short UI caption under the "single" mode picker (`pictureBook.js`'s Step 1 prompt-template config)
explaining the scope, so the new no-op isn't silent to the user — "Applies to scene/chunk/character
extraction and blurb prompts only — landscape and scene-image prompts need different template vars
and always use their own defaults; switch to 'Select per prompt' to customize those." Exported
`getPromptTemplate` + a new `__setPromptStateForTest` seam (test-only) since the wizard's mode/
template state is module-private, mutated only via UI interaction otherwise.

**Second root cause found and fixed (2026-07-23, Stephen tested live on his real `/Public`-org
catatone book after the fixes above and found it still broken):** the first fix closed the
"cross-purpose template" failure mode, but a second, structurally different failure mode produced
the same "generic, story-disconnected prompt" symptom — Stephen's exact report: `"masterpiece, best
quality, expansive alpine meadow dotted with wildflowers and a crystal-clear river winding through
rugged, snow-capped mountains in the distance, ..."` for a scene that should have been a rain-soaked
dystopian city street. Traced via the live server's own request/response log (`C:\projects\logs\
accountManagerService.log`), not guesswork: the actual wire request sent to the LLM read literally
`"Create a Stable Diffusion landscape/environment prompt for this scene:\nSETTING: \nMOOD: \nTIME:
\nSTYLE: photograph\n\n..."` — **`setting` and `mood` were both blank.** `resolveLandscapePrompt`/
`resolveScenePrompt` called the LLM anyway; given nothing real to describe, the model didn't
error or refuse — it fabricated a plausible, well-formed, but entirely unrelated landscape. That
response is coherent prose, not JSON-shaped, not a conversational refusal, not an unsubstituted
placeholder, so neither guard from the first fix ever catches it — it got cached and served forever.
**Proof this was a stale cache, not a fresh hallucination each time:** the exact same alpine-meadow
text, byte-for-byte, appeared again in the log at 19:43:08 — *after* the `NEG_PROMPT` fix had already
landed and was visibly active (the negative-prompt text in that later log line is the new
`NarrativeUtil.getDefaultNegativePrompt()` text, not the old one) — proving the landscape prompt
itself wasn't being regenerated at all, just re-served from cache.

**Fixed:** `resolveLandscapePrompt` and `resolveScenePrompt` now check whether there is any real
input at all (`setting`/`mood` for landscape; `setting`/`action`/`mood`/character narrations for
scene-image) *before* calling the LLM. If everything is blank, the LLM is never called — the method
goes straight to the same deterministic fallback an LLM failure would already produce (`"A detailed
environment"` for landscape; `SWUtil.styleClause(style)` for scene-image). Garbage/absent input can
no longer become a confident-looking wrong answer. **Existing poisoned caches self-heal too** (not
just future ones): the cache-read check now also verifies that if `setting`/`mood` are *still* blank
right now, the cached value must be exactly the one thing this code could ever legitimately produce
for blank input (`"A detailed environment"`, or `styleClause(style)`'s output) — anything else is
conclusively a pre-fix hallucination (not a fuzzy content-similarity guess: with the guard in place,
blank input can never again produce anything else), and gets discarded and regenerated. Stephen's
`/Public` book's already-poisoned scenes will self-heal automatically the next time `prepareSceneImagePrompts`
runs for them — no manual DB cleanup needed.

**Verified, live:** two new tests in `TestPictureBookFull.java`. `TestBlankSettingSkipsLlmCallEntirely`
— a fully-blank scene resolves in 111ms (vs. this session's real LLM calls, which took 3-90+ seconds)
and returns exactly the deterministic fallback for both prompts. `TestPoisonedHallucinatedLandscapePromptSelfHeals`
— pre-seeds a scene with the exact live-observed hallucinated text as its cached `landscapePrompt`,
confirms it's discarded and replaced with `"A detailed environment"`. Confirmed as genuine regression
protection: temporarily disabled the blank-input guard, reran — `TestBlankSettingSkipsLlmCallEntirely`
failed exactly as predicted (7889ms elapsed, i.e. a real LLM round-trip happened), restored, reran —
both pass, `BUILD SUCCESS`. Full KI-31 test set (5 tests together) — `Tests run: 5, Failures: 0,
Errors: 0` / `BUILD SUCCESS`. `mvn -o -pl AccountManagerObjects7 install -DskipTests` + `mvn -o -pl
AccountManagerService7 clean compile` — both `BUILD SUCCESS`.

**Not fixed here, flagged for whoever picks it up (a smaller, pre-existing, separate issue — not
what was reported):** `sceneImagePrompt` has no template slot of its own even in "per-prompt" mode
today — the client only ever sends one override (via the `landscapePrompt` slot) into
`prepareSceneImagePrompts`, and `PictureBookUtil.java` applies that same value to *both*
`resolveLandscapePrompt` and `resolveScenePrompt` server-side. A per-prompt-mode user who
deliberately customizes `landscapePrompt` with a template that doesn't reference `{action}`/
`{charNarrations}` (since it was written for landscape purposes) won't crash — the construction-time
guard only fires on *literal* leftover placeholders, and a template that never mentions those vars
has none to leave unfilled — but the resulting scene-image prompt silently loses character/action
detail it would otherwise have had. Giving `sceneImagePrompt` its own client slot and a matching
second server-side override param on `prepareSceneImagePrompts`/`generateSceneImage` would close
this properly; out of scope for the reported "prompts completely broken" bug, which is fully fixed.

**Verified, live, against the real Ollama LLM (no mocking) — three tests in `TestPictureBookFull.java`:**
- `TestIsErrorOrEmptyPayloadDetectsErrorShapedAndEmptyLlmContent` (extended) — direct unit test of
  the new placeholder/conversational-refusal detection against the exact live-observed sentence
  (both the long and short variants actually logged) plus a raw `{text}`-unsubstituted string;
  confirms real prose mentioning "story"/"scene" isn't false-positived.
- `TestMismatchedPromptTemplateOverrideDoesNotPoisonScenePrompt` (new, live) — reproduces the exact
  bug: calls `prepareSceneImagePrompts` with `promptTemplateOverride="pictureBook.extract-scenes"`
  on a normal scene. Live log confirms the guard fired for BOTH the landscape and scene-image calls
  (`"Refusing to call LLM for prompt 'pictureBook.extract-scenes' — template has unsubstituted
  placeholder(s) (first: '{count}')..."`) with zero network calls made, and both cached values are
  the real, sane fallback text built from the scene's actual setting/action/mood — not garbage.
- `TestPoisonedCachedScenePromptSelfHeals` (new, live) — pre-seeds a scene's cached `scenePrompt`
  with the literal historical poisoned text, calls `prepareSceneImagePrompts` normally (no override),
  and confirms the LLM is actually re-invoked (real Ollama round-trip logged) and the poisoned text
  is replaced with a real generated SD prompt, not served again.
- All three confirmed as genuine regression guards, not happy-path checks: temporarily disabled all
  three guard sites simultaneously (`if (false && ...)`), reran — the deterministic unit test failed
  exactly as predicted (`AssertionError: A conversational request for missing story text/scene count
  must be detected`); the live end-to-end test happened to pass on that run because the model's
  stochastic output that particular time was short/blank (caught by the pre-existing, pre-KI-31
  null/blank check instead) — noted honestly rather than treated as proof, since LLM output isn't
  deterministic run-to-run; the unit test result is the reliable regression signal. Restored all
  three guards, reran everything together — `Tests run: 3, Failures: 0, Errors: 0` / `BUILD SUCCESS`.
  Full `mvn -o -pl AccountManagerObjects7 install -DskipTests` + `mvn -o -pl AccountManagerService7
  clean compile` — both `BUILD SUCCESS`.
- **Client-side**: new `AccountManagerUx752/src/test/pictureBookPromptTemplateScope.test.js` (4
  tests) drives the real, exported `getPromptTemplate` function directly — confirms "single" mode
  still applies to extraction-shaped slots, confirms it returns `null` (not the mismatched template)
  for `landscapePrompt`/`sceneImagePrompt`, confirms "per-prompt" mode is unaffected, and confirms no
  crash when no template is selected yet. Confirmed as a genuine regression guard: temporarily
  disabled the `IMAGE_PROMPT_SLOTS` check, reran — failed exactly as predicted (`expected
  'pictureBook.extract-scenes' to be null`, i.e. reproducing the live bug verbatim), restored,
  reran — 4/4 pass. Full suite: `npx vitest run` — 327/327 individual tests pass (same pre-existing
  `pictureBook.test.js`/mithril unhandled-rejection flake noted elsewhere in this doc, confirmed
  unrelated). `npx vite build` — clean.

### KI-32. `PictureBookUtil.reset()`'s non-recursive delete leaves orphaned/dangling group hierarchy, surfacing as cascades of `PathProvider` "Parent auth.group index not found" errors — FIXED ✅ (2026-07-23)
Stephen flagged this log burst as "likely related to upper ascii path" (grouping it with KI-26's
François/Duña `MediaUtil` fix, since it appeared while reading/modifying a note involving the
character "Duña"). Read the actual code path (`PathProvider.java:103-127`) and it looks structurally
different from KI-26: this is `PathProvider` walking a record's `parentId` chain upward to compute its
virtual `path` field, and at some level the read (`IOSystem...getReader().read(base, parentId)`)
returns `null` — the parent `auth.group` row genuinely doesn't exist — throwing `ReaderException:
Parent auth.group index not found for id {id}`. This isn't obviously an ASCII/encoding issue; it's
consistent with **orphaned group hierarchy**, and this exact mechanism was already identified earlier
in this project's history (documented in this session's own working notes, not yet promoted to a
`KnownIssues.md` entry until now): `PictureBookUtil.reset()` only deletes 3 top-level rows (the
book's Scenes group, Characters group, and `.pictureBookMeta` note) **non-recursively** — every
group/note/record nested underneath is orphaned, not deleted. A subsequent read of any surviving
record whose `parentId` chain climbs through one of those orphaned/deleted intermediate groups hits
exactly this error.

**Evidence for the "wide range of ids" pattern:** the same ~24 group ids (813, 827, 841 ... 1164)
fail identically across two separate operations moments apart on the same note — consistent with a
long, now-broken parent chain being re-walked (and re-failing at the same missing links) on every
path computation for anything still rooted under it, not a one-off.

**Fix direction:** make `PictureBookUtil.reset()` actually recursive (walk and delete all
groups/notes/charPerson records nested under the book's Scenes/Characters groups, not just the 3
top-level rows), or — if a full recursive delete is out of scope for a quick fix — at minimum make
`PathProvider`'s parent-chain walk tolerant of a missing intermediate group (treat it as "path ends
here" rather than throwing) so a pre-existing orphan doesn't turn every subsequent read of a
surviving record into a wall of `ReaderException` log spam. **Next step:** confirm via direct query
whether `auth.group` ids 813-1164 for organizationId=3 are genuinely absent from the DB (not just
slow/uncached), then trace which one(s) are the actual missing links closest to the surviving note's
`parentId`, to confirm the orphaned-reset theory before implementing either fix.

**Confirmed by code read (2026-07-22):** `PictureBookUtil.reset()` (`PictureBookUtil.java:2635-2677`)
deletes exactly 4 rows — Scenes group, Characters group, `.pictureBookMeta` note, book group itself —
each via a single-record `AccessPoint.delete(user, grp)`. The method's own comment already admits it:
"Explicit child deletion — AccessPoint.delete on a group does NOT cascade." `AccessPoint.delete`
(`client/AccessPoint.java:320-350`) is single-row only; its `delete(BaseRecord, Query)` overload
(352-382) — the natural home for a bulk/query-based delete — is an **unimplemented stub** ("Not
implemented", always returns `false`). No generic recursive/cascade-delete utility exists anywhere in
Objects7 to reuse; the closest analog, `WorldUtil.cleanupWorld`/`cleanupLocation`
(`olio/WorldUtil.java:227-298`), bypasses `AccessPoint`/PBAC entirely (raw `writer.delete(query)`) and
is hand-enumerated per Olio-world model type — not reusable as-is for a user-invoked action that
should still respect PBAC.

**`PathProvider` re-read — the "cascade" is pure log noise, not a second bug:** the throw at
`PathProvider.java:126` is caught in the *same method* at lines 144-146
(`catch (ReaderException e) { logger.error(e); }`); the enclosing `provide(...)` doesn't even declare
`ReaderException`. So a missing parent already can't propagate — the walk keeps whatever partial path
it built and returns a truncated `path`. The only real problem is `logger.error(e)` dumping a full
stack trace per affected record per read, which is what produces the "same ~24 ids fail repeatedly"
pattern. **No structural PathProvider change needed.**

**Recommended fix (both parts, no live DB access needed to implement, only to verify):**
1. Add a small recursive delete helper used by `reset()`: for the Scenes/Characters groups, query
   direct children by `groupId` for each relevant model in that subtree (`data.note` scenes,
   `olio.charPerson` + its `store`/`profile`/apparel foreign rows, generated `data.data` images, and
   any nested `auth.group` subgroups — recurse into those first), deleting bottom-up, still through
   `AccessPoint.delete(user, rec)` per record (not the PBAC-bypassing `writer.delete` pattern) since
   this is a user-invoked action. Since every PictureBook record lives under the acting user's own
   `~/Data/PictureBooks/...` path with no cross-book sharing found, PBAC/ownership shouldn't surprise
   here — but spot-check live once DB access is available.
2. `PathProvider.java:145` — downgrade `logger.error(e)` (full stack trace) to a single-line
   `logger.warn(e.getMessage())` (or dedupe per missing id) purely to kill the log-spam symptom; this
   is independent of fix 1 and safe on its own since behavior (truncated path, no failure) is
   unchanged.

**Live-confirmed (2026-07-22), incidentally, while live-testing KI-28/29 against the real Tomcat
instance:** a plain `olio.charPerson` list query (no relation to PictureBook at all) for
`organizationId=2` threw the exact predicted cascade —
```
PathProvider - org.cote.accountmanager.exceptions.ReaderException: Parent auth.group index not found for id 1164
PathProvider - org.cote.accountmanager.exceptions.ReaderException: Parent auth.group index not found for id 1151
PathProvider - org.cote.accountmanager.exceptions.ReaderException: Parent auth.group index not found for id 1138
PathProvider - org.cote.accountmanager.exceptions.ReaderException: Parent auth.group index not found for id 1116
PathProvider - org.cote.accountmanager.exceptions.ReaderException: Parent auth.group index not found for id 1094
```
— the same ~13-id spacing pattern as the original report, on the live `/Development` org's real data,
right now. This isn't a historical/one-off artifact; the orphaned hierarchy is present today and firing
on ordinary reads. Confirms the fix is worth landing, not just theoretical.

**Fixed (2026-07-23), part 1 (the recursive delete):** added `PictureBookUtil.deleteGroupRecursive
(BaseRecord user, BaseRecord group)` (`PictureBookUtil.java` ~2742), called by `reset()`
(~2704) for the Scenes/Characters sub-groups before the existing `.pictureBookMeta`-note and
book-group deletes. Walks bottom-up, entirely through `AccessPoint.delete(user, rec)` (never the
PBAC-bypassing `writer.delete` pattern, per the recommended fix above): (1) recurses into nested
`auth.group` subgroups first (deepest-first), (2) deletes `data.note` children (scene notes), (3)
deletes `olio.charPerson` children, (4) deletes `data.data` children (generated images grouped
directly under the group), (5) deletes the group itself. **Scope note, not addressed by this fix:**
a charPerson's own foreign single-model fields (`profile`/`narrative`/`statistics`/`store` — see
`createPersistedForeignInstance`) are persisted under the acting user's own shared `~/Profiles`/
`~/Narratives`/etc. buckets, not grouped under the book's Characters subtree, so they are NOT
reachable by this group-subtree walk and are NOT deleted by it. That's a separate, smaller storage
leak (no dangling `auth.group` parent chain, so no `PathProvider` symptom) and was left out of
scope — flagged here for whoever picks it up next.

**Part 2 (the `PathProvider` log-spam downgrade) was already present in the working tree when this
fix landed** — `PathProvider.java:145` now reads `logger.warn(e.getMessage())` instead of
`logger.error(e)` (confirmed via `git diff` while working this issue). Not implemented by this fix;
noting it here only so the "Recommended fix (both parts)" note above isn't misread as still open.

**Verified:** `TestResetRecursivelyDeletesNestedSceneAndCharacter` (`AccountManagerObjects7`,
`TestPictureBookFull.java`) — builds a minimal book with one `data.note` scene and one bare
`olio.charPerson` (no LLM call — direct `Factory.newInstance` + `AccessPoint.create`, exercising the
delete path only) nested under Scenes/Characters, confirms all four records resolve before
`reset()`, calls `reset()`, then confirms via direct by-objectId/by-path queries that the scene
note, the character, AND both the Scenes and Characters groups themselves are gone — not just the
top-level book group. **Confirmed as a genuine regression test, not just a happy-path check:**
temporarily reverted only the production fix (`git stash` on `PictureBookUtil.java` alone, test file
untouched) and reran the same test — it failed exactly as predicted (`AssertionError: Scene note
must be deleted, not just orphaned, after reset() ... was:<{...groupId:1237...}>`, i.e. the scene
note genuinely survived pre-fix), then restored the fix and reran — passes. Ran via
`mvn -o -pl AccountManagerObjects7 -DskipTests=false -Dtest=TestPictureBookFull#TestResetRecursivelyDeletesNestedSceneAndCharacter test`
— `Tests run: 1, Failures: 0, Errors: 0` / `BUILD SUCCESS`.

**Follow-up gap closed (2026-07-23, Stephen requested):** the fix above left a documented gap — a
character's own foreign single-model sub-records (`profile`/`narrative`/`statistics`/`store`/
`instinct`/`personality`/`state`, created by `createPersistedForeignInstance`) live in the acting
user's shared `~/Profiles`/`~/Narratives`/etc. buckets, not nested under the book's Characters
subtree, so `deleteGroupRecursive`'s group-subtree walk never reached them. `deleteGroupRecursive`
now projects those seven fields on each `olio.charPerson` it finds and deletes each persisted FK
(via `AccessPoint.delete`, same PBAC-respecting pattern as everything else in this method) before
deleting the character itself. Safe because each is created fresh, once, per character —
`createPersistedForeignInstance` is never called with a shared/reused instance, so no other
character's data can be orphaned by this. **Verified:** new
`TestResetDeletesCharacterForeignSubRecords` (`TestPictureBookFull.java`) — builds a real character
via `createFromScenes` (so all seven sub-records are genuinely persisted, not a bare fixture),
captures each one's objectId, calls `reset()`, asserts every one of them is gone afterward — not just
the character. Confirmed as a genuine regression test: temporarily disabled just the new deletion
loop (`for (String foreignField : (false ? charForeignFields : new String[0]))`), reran — failed
exactly as predicted (`AssertionError: profile must be deleted, not just orphaned, after reset() ...
was:<{...groupId:1090...}>`), restored, reran — passes, alongside the original
`TestResetRecursivelyDeletesNestedSceneAndCharacter` test (both together: `Tests run: 2, Failures: 0,
Errors: 0` / `BUILD SUCCESS`). Live audit log confirms real `DELETE` calls for all seven models
(`identity.profile`, `olio.narrative`, `olio.statistics`, `olio.store`, `olio.instinct`,
`identity.personality`, `olio.state`) plus the character itself.

### KI-28. Outfit generation wizard for `olio.charPerson` fails with a server error — NOT REPRODUCED, needs exact repro steps (2026-07-22, Stephen; re-tested 2026-07-23; re-checked 2026-08-10)

**2026-08-10 note, not a reproduction:** two server-side defects fixed this pass sit directly on the
outfit/apparel call graph and could each present as "fails with a server error" —
`SDUtil.createImage`'s `Integer`→`Double` `ClassCastException` (KI-51, which aborted the whole call
before any image was requested) and `PolicyUtil`'s NPE-in-the-diagnostic on urn-less models such as
`olio.wearable` (KI-50). Neither was confirmed to be THIS bug; both are named so the next attempt
knows what has already been removed from underneath it. Still needs Stephen's exact repro steps.
Reported by Stephen; not yet reproduced or diagnosed in this session — no error text or repro steps captured yet. Filed as a placeholder so it isn't lost: the outfit-builder flow (`workflows/outfitBuilder.js`, invoked from PictureBook's `pictureBookCharacters.js` "Generate New Outfit" button and presumably from the charPerson editor directly) fails server-side. **Next step for whoever picks this up:** reproduce via `ensureSharedTestUser()` (never admin), capture the actual REST response/status and Tomcat log entry for the failing call, then route per `troubleshooting.md`'s gate (raw API call first, to confirm client vs. backend before assuming either).

**Call chain traced end to end (2026-07-22), strong candidate root cause found:**
`pictureBookCharacters.js` `doOutfitBuilder()` → `workflows/outfitBuilder.js` (opens dialog) →
`components/olio.js:297-333` `generateOutfit()` → `POST /game/outfit/generate` →
`GameService.java:782-820` → `GameUtil.findCharacter(characterId)` (`GameUtil.java:46-51`, loads via
`OlioUtil.planMost(q)`) → `GameUtil.generateOutfit()` (`GameUtil.java:912-934`) →
`ApparelUtil.contextApparel()` (`ApparelUtil.java:669-702`).

**Likely NPE at `ApparelUtil.java:694`:** `long ownerId = person.get(FieldNames.FIELD_OWNER_ID);` —
`person` was loaded through `OlioUtil.planMost` → `OlioUtil.FULL_PLAN_FILTER`
(`OlioUtil.java:645`), which **explicitly excludes `ownerId` from the query projection**. `ownerId` is
schema'd as primitive `"type": "long"`, so the excluded field's `get()` returns boxed `null`, and the
implicit unboxing into `long ownerId` throws immediately — a bare 500 with no message, matching
"server error, no error text captured." This should reproduce for **any** charPerson through this
endpoint, not only PictureBook-created ones, so it isn't necessarily entangled with KI-30's
sparse-character problem — **live-repro step:** confirm the Tomcat stack trace names
`ApparelUtil.contextApparel` at this line for any character before assuming it's PictureBook-specific.
**Fix:** stop excluding `ownerId` from the query plan `GameUtil.findCharacter` uses, or (cleaner, less
blast radius) refactor `contextApparel`/`constructApparel` so `ownerId` is read lazily only inside the
`ctx == null` branch that actually needs it — it's dead on this call path where `ctx != null` always.
**Secondary, non-crashing:** `GameService.java:812` reads `climate` from the request but never
forwards `params.get("style")` into `GameUtil.generateOutfit(...)` — the outfit-builder's style choice
is silently dropped; cosmetic, fix alongside the NPE if convenient.

**Live-tested (2026-07-22) — the ownerId-NPE hypothesis did NOT reproduce; correcting the record
rather than leaving a stale claim:** `POST /game/outfit/generate` against a real, fully-populated
`olio.charPerson` (`7d7b730a-f9c3-4c73-9bd1-dd0c11416e90`, from the live `/Development` Olio world)
returned a genuine **200** with a complete `olio.apparel` + wearables payload — no NPE. Likely
explanation: `ownerId` is one of `common.base`'s default **query** fields (always included in the
baseline projection per `model-api.md`), so excluding it from `OlioUtil.FULL_PLAN_FILTER`'s *custom*
filter list may not actually remove it from what's returned — the original static-analysis hypothesis
was probably wrong on this point, not just unconfirmed. A second attempt against a genuinely **sparse**
character (`f350fe5a-...`, statistics almost entirely unpopulated) hit a **403 Forbidden** before
reaching any apparel logic — that character is owned by a different test fixture user
(`e2etest_pbuxmrwc7xit_char`, ownerId 170), not `e2etest_shared`, so PBAC correctly denied it; this
was a test-setup mistake, not evidence either way.

**Re-tested 2026-07-23 with the actual missing control — a sparse character genuinely owned by the
calling user (`e2etest_shared_ki16char`, objectId `352d3c7c-7475-446e-99a3-42a1f650c2ed`, `ownerId 18`,
statistics almost entirely unpopulated — only `height`/`weight`/`unit` present): `POST
/game/outfit/generate` returned a clean **200** with a full `olio.apparel` + 5 wearables payload. This
was the specific test the KI-30-sparseness theory needed and it also came back clean. Between this and
the fully-populated-character test above, **two independent live attempts covering both the "any
character" and "sparse, same-owner" hypotheses both succeeded** — the endpoint is not reproducibly
broken via a direct REST call with plausible parameters.

**Status change: downgrading from "diagnosed, ready to fix" to "cannot reproduce as understood."**
Per this project's honesty rules, no fix is being written for a bug that won't trigger — writing one
now would be guessing, not fixing. Remaining possibilities, in rough likelihood order: (a) the bug was
already fixed by an intervening patch since Stephen's report (recent "Patch"-titled commits exist) and
this KI is stale; (b) the real client (`outfitBuilder.js`/`olio.js generateOutfit()`) sends a
materially different request than what was tried here (different `techTier`/`climate`/`style` values,
or a `characterId` in a transient state — e.g. mid-async-enrichment right after PictureBook character
creation, before statistics/apparel finish populating — that these two tests didn't happen to hit); (c)
it's environment-specific (a different SD/Swarm backend state, e.g. a missing model, that only
manifests downstream of `generateOutfit` in a way these tests' successful apparel *object* creation
wouldn't surface, since apparel generation itself doesn't call SD). **Next step, and this one actually
needs Stephen:** the exact character (ideally its objectId, or "the one I just created via the wizard")
and exact click sequence at the time of the original failure, since two honest reproduction attempts
using the best available substitutes did not find it. The ownerId/`FULL_PLAN_FILTER` observation from
the static read remains true as a general code-cleanliness note (dead-code-on-this-path read of an
excluded field) but is **not confirmed to be a bug** — not worth fixing blind.

### KI-29. Outfit reimage fails — suspected SD-config inconsistency, same class of bug as KI-16 Finding C — FIXED ✅ (2026-07-23)
Reported by Stephen, with explicit frustration that this class of bug was raised before and not fixed: reimaging an apparel/outfit item fails, and the symptom looks like an SD-config inconsistency. KI-16's Finding C already catalogued **≥5 divergent SD/range-slider implementations** across `workflows/reimage.js`, `workflows/reimageApparel.js`, `workflows/pictureBook.js`, and `components/SdConfigPanel.js` — schema-blind, hardcoded min/max/step, no shared source of truth for SD parameters — and marked it "FIXED ✅ (2026-07-13)" by converging the **slider markup** onto `formFieldRenderers.renderRange`. That fix addressed the UI widget duplication; it did **not** necessarily guarantee the underlying **SD parameter values** (model/sampler/scheduler/steps/cfg/seed/LoRAs, etc.) are assembled consistently across `reimageApparel.js` vs. `pictureBook.js`'s own SD config panel vs. whatever the outfit-reimage call path actually sends to the SD backend — that's a different, deeper claim the markup convergence doesn't prove. Not yet reproduced or diagnosed this session. **Next step:** reproduce via `ensureSharedTestUser()`, capture the actual failing request payload sent to the SD service and compare field-by-field against a working reimage call (e.g. the charPerson portrait reimage path) to find exactly which parameter(s) differ or are missing; check whether `reimageApparel.js` and `pictureBook.js`'s apparel-reimage path independently assemble the SD request object rather than sharing one code path. This is very likely a case for KI-13's broader cross-view-consistency umbrella, not a one-line fix.

**Confirmed by code read (2026-07-22) — this is exactly Finding-C-class, and the divergence is server-side, not just UI:**
`reimageApparel.js` → `OlioService.reimageApparel` reads **only `hires` and `seed`** off the client
JSON (`OlioService.java:265-266`) — `sdConfig` is hardcoded `null` into
`SDUtil.generateMannequinImages(...)` (`OlioService.java:270`). By contrast `reimage.js` →
`OlioService.reimageWithConfig` forwards the **entire** parsed record (steps/cfg/sampler/scheduler/
model/refinerModel/loras/denoisingStrength/style) into `sdu.generateSDImages(...)`
(`OlioService.java:214`). With `sdConfig=null`, `generateMannequinImages`
(`SDUtil.java:1438-1462`) falls back to `randomSDConfig()` (`SDUtil.java:639-705`), which never sets
model/sampler/scheduler/cfg — those come from **hardcoded literals**:
`model="sdXL_v10VAEFix.safetensors"`, `scheduler="normal"`, `sampler="dpmpp_2m"`, `cfg=7`, `steps=20`
(`SDUtil.java:1458-1462`), regardless of what the client's `fetchModels()`-validated selection sent.
LoRAs can never apply either — `appendLoras(prompt, sdConfig)` is called with `sdConfig=null`
(`SDUtil.java:1470`), a no-op (`SDUtil.java:285`); `reimageApparel.js` doesn't even offer LoRA UI.
`denoisingStrength` is also scaled inconsistently between the two workflows (0-1/step 0.05 in
`reimageApparel.js` vs. 0-100/step 5 in `reimage.js` for the same underlying field) — moot for apparel
today since the server discards it anyway, but will bite once the fix below wires it through.

**Fix:** `OlioService.reimageApparel` needs to parse the request body into a real `olio.sdConfig`-like
record the same way `reimageWithConfig` does (defaulting model/refinerModel from init params when
absent), and pass that through to `generateMannequinImages` instead of `null`; `generateMannequinImages`
itself needs to copy steps/cfg/sampler/scheduler/model/refinerModel/loras from the passed config (today
it only copies `style`/`hires`, `SDUtil.java:1439-1446`). Reconcile the denoisingStrength scale
(0-1 vs 0-100) between `reimageApparel.js` and `reimage.js` while touching this. **Live-repro check
first:** capture the `"Generating mannequin image: ... model=..."` log line (`SDUtil.java:1499`) when
reproducing — if the hardcoded fallback model (`sdXL_v10VAEFix.safetensors`) isn't actually installed
on the configured SD backend, that alone (not a code exception) is the direct cause of "outfit reimage
fails," and would already resolve once real config values flow through.

**Fixed:**
- `AccountManagerService7/src/main/java/org/cote/rest/services/OlioService.java` — `reimageApparel`
  (~line 233) now parses the request body via `JSONUtil.importObject(json, LooseRecord.class,
  RecordDeserializerConfig.getFilteredModule())` — the same pattern `reimageWithConfig` already used —
  instead of a plain `Map<String,Object>` that only read `hires`/`seed`. Defaults `model`/`refinerModel`
  from the `sd.model`/`sd.refinerModel` init params when the client didn't send them (mirroring
  `reimageWithConfig` lines 150-155), and passes the real parsed record into
  `sdu.generateMannequinImages(...)` instead of `null`.
- `AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/sd/SDUtil.java` —
  `generateMannequinImages` (~line 1406) now copies `steps`/`cfg`/`sampler`/`scheduler`/`model`/
  `refinerModel`/`denoisingStrength`/`loras` from the passed `sdConfig` onto the working `config`
  object (in addition to the pre-existing `style`/`hires` copy), only where the client actually set a
  value — `randomSDConfig()` defaults and the hardcoded literal fallbacks (`sdXL_v10VAEFix.safetensors`/
  `normal`/`dpmpp_2m`/`7`/`20`) are unchanged and still apply for any field the client left unset. Also
  switched the `appendLoras(prompt, sdConfig)` call to use the merged `config` object instead of the
  raw `sdConfig` param, so LoRAs actually apply now that they're merged in (previously a no-op since
  the caller passed `null`).
- `AccountManagerUx752/src/core/formDef.js` — added a `denoisingStrength: { format: 'range' }` field
  entry to `forms.sdMannequinConfig` (it had none at all). Root cause of the scale "mismatch": the
  `rangeDecorator` that converts the UI's 0-100 slider to the schema's 0.0-1.0 wire value
  (`AccountManagerUx752/src/core/model.js`'s `rangeDecorator`, gated on the field's form entry having
  `format: 'range'`) was only ever wired up for `forms.sdConfig` (used by `reimage.js`) — `reimageApparel.js`
  used `forms.sdMannequinConfig`, which had no `denoisingStrength` entry, so its 0-1/step-0.05 slider
  was actually already wire-correct (undecorated raw value), just presented on a different UI scale.
  Widening the slider without this form-def addition would have broken the value (would have sent 75
  instead of 0.75); with it, both forms now decorate identically.
- `AccountManagerUx752/src/workflows/reimageApparel.js` — converged the denoising slider's rendering
  (min/max/step and `onInput` handler) onto `reimage.js`'s exact 0-100/step-5 pattern.

**Flagged, NOT fixed (out of scope):** `reimageApparel.js:24` and `reimage.js:123` both call
`am7model.newPrimitive('olio.sdConfig')` as their fallback when `am7sd.fetchTemplate(true)` returns
nothing — but the registered model name is `olio.sd.config` (`AccountManagerUx752/src/core/modelDef.js:9918`;
used correctly everywhere else in the codebase, e.g. `formDef.js:6114`,
`cardGame/services/artPipeline.js:257`). That typo'd fallback returns `null` from `newPrimitive`
(logs `"No model for: olio.sdConfig"`) and then throws in `am7model.prepareInstance(null, form)`. It's
invisible in practice only because a saved template config normally already exists server-side by the
time a user opens either dialog. Discovered while writing this fix's Vitest coverage (had to mock
`fetchTemplate` to avoid tripping it). Whoever picks this up next: fix both call sites to
`'olio.sd.config'`.

**Verified:**
- Backend (live, against the real SD/Swarm backend): new JUnit test
  `TestSD#TestGenerateMannequinImagesForwardsClientConfig` (`AccountManagerObjects7`) builds a real,
  fully-specified `olio.sd.config` record with `model`/`sampler`/`scheduler`/`cfg`/`steps` deliberately
  different from `generateMannequinImages`' hardcoded fallback literals
  (`juggernautXL_ragnarokBy.safetensors`/`euler_ancestral`/`karras`/`11`/`33` vs. the fallback's
  `sdXL_v10VAEFix.safetensors`/`dpmpp_2m`/`normal`/`7`/`20`), calls `generateMannequinImages` directly
  against the live SwarmUI backend at `test.swarm.server` (`http://localhost:7801`, confirmed reachable
  and confirmed the test model is actually installed there before writing the test), then reads back
  the **actual `SWTxt2Img` request object that was sent**, recorded verbatim by production code itself
  as the `"s2i"` attribute on each resulting `data.data` record (`SDUtil.java` ~1538,
  `AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i))` — not test instrumentation),
  and asserts the client's values (not the fallback literals) reached the real request. Ran via
  `mvn -o -pl AccountManagerObjects7 -DskipTests=false -Dtest=TestSD#TestGenerateMannequinImagesForwardsClientConfig test`
  — `Tests run: 1, Failures: 0, Errors: 0` / `BUILD SUCCESS`; test log shows the live request actually
  sent: `model=juggernautXL_ragnarokBy.safetensors steps=33 cfgScale=11 sampler=euler_ancestral
  scheduler=karras`, and 6 real images (one per wear level: BASE/ACCENT/SUIT/GARNITURE/OVER/OUTER) came
  back from the live server and were written to disk for inspection. Full `TestSD` suite (3 tests,
  including the pre-existing `TestCreatePersonImage` which also hits the live SD backend) re-ran clean
  after rebuilding the Objects7 jar and recompiling Service7 — `Tests run: 3, Failures: 0, Errors: 0` /
  `BUILD SUCCESS`. `mvn -o -pl AccountManagerObjects7 install -DskipTests` then
  `mvn -o -pl AccountManagerService7 clean compile` both `BUILD SUCCESS` (full recompile of
  `OlioService.java` confirmed, not a stale no-op).
- Frontend: new Vitest file `AccountManagerUx752/src/test/reimageApparelConfig.test.js` (4 tests, all
  passing) exercises the real `reimageApparel()` workflow function end-to-end — real Dialog config
  captured, real rendered Mithril vnode tree walked to find the actual `<input type="range">` for
  denoising among the 4 sliders it renders, a real "drag" fired via the real `oninput` handler, and the
  real "Generate" action's `onclick` invoked to inspect the actual POST body (only `m.request` is
  intercepted, at the network boundary) — confirms `forms.sdMannequinConfig` now declares
  `denoisingStrength: {format:'range'}`, confirms the same 80 → 0.8 decoration now happens identically
  on both `sdMannequinConfig` and `sdConfig`, confirms the rendered slider bounds are 0/100/5 (matching
  `reimage.js`), and confirms the POST body carries the full config (`model`/`sampler`/`scheduler`/
  `cfg`/`steps`/`hires`/`seed`) plus the correctly-scaled `denoisingStrength: 0.8`. Ran via
  `npx vitest run src/test/reimageApparelConfig.test.js` — 4/4 passed; full suite
  (`npx vitest run`) — 320/321 tests passed, the one pre-existing failure (`dialog.test.js`, a
  `mithril`/`Dialog` wiring issue) confirmed unrelated and pre-existing via `git stash` (fails
  identically on the pre-fix tree). `npx vite build` — clean.

**Correction (2026-07-23, Stephen):** the first pass's new copy-block in `SDUtil.java` used
`config.setValue("steps", inSteps)`-style calls throughout — `setValue()` catches and merely logs
`FieldException`/`ValueException`/`ModelNotFoundException` rather than propagating them, so a bad
value on any of these fields (the exact class of "client override silently lost" bug this fix exists
to close) would fail silently again; it also repeated raw string field-name literals instead of named
constants. Fixed: added `OlioFieldNames.FIELD_STYLE`/`FIELD_HIRES`/`FIELD_SD_STEPS`/`FIELD_SD_CFG`/
`FIELD_SD_SAMPLER`/`FIELD_SD_SCHEDULER`/`FIELD_SD_MODEL`/`FIELD_SD_REFINER_MODEL`/
`FIELD_SD_DENOISING_STRENGTH`/`FIELD_SD_LORAS` constants; switched the copy-block to `config.set(...)`
and gave `generateMannequinImages` a `throws FieldException, ValueException, ModelNotFoundException`
signature so a bad value is a real, visible failure; `OlioService.reimageApparel` now catches those and
returns a `500` with the actual error message instead of the call silently succeeding with wrong
values. Re-verified: `TestSD#TestGenerateMannequinImagesForwardsClientConfig` re-run live against the
real SwarmUI backend post-change — same client values (`model=juggernautXL_ragnarokBy.safetensors
steps=33 cfgScale=11 sampler=euler_ancestral scheduler=karras`) reached the actual request,
`Tests run: 1, Failures: 0` / `BUILD SUCCESS`; both modules recompile clean
(`mvn -o -pl AccountManagerObjects7 install -DskipTests` then `mvn -o -pl AccountManagerService7 clean
compile`, both `BUILD SUCCESS`).

### KI-33. Filtering a search by `groupPath` throws `PSQLException: column oocn1.grouppath does not exist` — FIXED ✅ (2026-07-23)
Found live (2026-07-22) while trying to locate a PictureBook-created `olio.charPerson` to test KI-28/29
against — not PictureBook-specific itself, affects any `data.directory`-derived model. A `POST
/model/search` on `olio.charPerson` (also reproduced on `data.data`) with a `groupPath LIKE "%..."`
field condition threw server-side:
```
DBSearch - org.postgresql.util.PSQLException: ERROR: column oocn1.grouppath does not exist
  Position: 85 (live) / 2173 (JUnit repro)
  at org.cote.accountmanager.io.db.DBSearch.find(DBSearch.java:108)
  at org.cote.accountmanager.io.db.cache.CacheDBSearch.find(CacheDBSearch.java:135)
```
The identical query without the `groupPath` field condition (same type/org, just no filter on that
field) succeeded normally.

**Root cause found — corrects the original "self-join" hypothesis, which was wrong.** `StatementUtil`'s
`getAlias(query, model)` is called once per `Query` object and cached on it (`query.set(FIELD_ALIAS,
...)`) — for this simple query (no `joins` records added) it is the **primary table's own FROM alias**,
not a self-join copy. `oocn1` = `olio.charPerson`'s own alias (first+last char of each dot-segment,
`StatementUtil.java` `getAlias()`), confirmed by checking `query.get(FieldNames.FIELD_JOINS)` is empty
and no join clause is emitted for this query at all — there is no self-join anywhere in this path.

The real cause: `groupPath` (declared in `common.groupExtModel.json`, inherited via `data.directory`) is
a **`virtual`** field — `PathProvider` computes it at READ time by following the record's own `groupId`
FK to `auth.group` and copying that group's own (also virtual, parent-chain-derived) `path`. It has **no
backing database column on any table** — confirmed empty in Postgres (`a7_olio_charperson_0_1`,
`a7_auth_group_0_1` both lack any `path`/`grouppath` column). `StatementUtil.getQueryClause()`
(`StatementUtil.java` ~line 954, pre-fix) built the WHERE fragment for **any** field name blindly as
`alias + "." + columnName(fieldName)` with no check for `isVirtual()`/`isEphemeral()`/`isReferenced()` —
so a `groupPath` condition produced `oocn1.grouppath`, a column that has never existed and never could,
regardless of self-joins.

**Fix corrected 2026-07-23 (Stephen):** the first pass at this fix (above) rewrote the invalid
condition in place into a resolved `IN` query — silently making a bad query "work" by inventing
hierarchy-walking resolution logic. That was the wrong instinct: **the original query was invalid**
(filtering on a computed, non-persisted field), and the codebase already has an established,
consistent convention for this — `StatementUtil`/`DBUtil` exclude virtual/ephemeral/referenced fields
from every real SQL operation everywhere else (select projections, insert/update column lists — see
the `isVirtual()`/`isEphemeral()`/`isReferenced()` checks throughout both classes). A filter condition
on a virtual field is the same case and should get the same treatment: **reject it up front with a
clear error**, not accommodate it. The hierarchy-walking resolution code (`resolveVirtualPathConditions`
+ helpers, ~115 lines, plus now-unused `PathProvider`/`QueryResult`/`HashSet`/`Pattern` imports) was
removed entirely.

**Fixed (corrected):** `StatementUtil.java` — `rejectVirtualFieldConditions()` (same 3 call sites:
top of `getSelectTemplate`/`getCountTemplate`/`getDeleteTemplate`, right after the model schema is
resolved) walks the query's field-condition tree (recursing into `GROUP_AND`/`GROUP_OR`) and throws a
`FieldException` — `"Field '<name>' on model '<model>' is virtual (computed, no backing column) and
cannot be used in a query condition"` — the instant it finds a leaf condition on any field where
`FieldSchema.isVirtual()` is true. `DBSearch.find` already wraps `FieldException` into `ReaderException`
(same as every other query-construction failure), so callers get a clear, actionable error instead of
either a cryptic `PSQLException` or a query that was silently changed underneath them.

**Verified:** `TestQuery#TestQueryByGroupPathLikeIsRejected` (`AccountManagerObjects7`) — filters
`data.data` by `groupPath LIKE "%anything%"` and asserts a `ReaderException` is thrown whose message
names the `groupPath` field, rather than asserting the query succeeds. Actually run: `mvn -o -pl
AccountManagerObjects7 -Dtest=TestQuery#TestQueryByGroupPathLikeIsRejected test` →
`FieldException: Field 'groupPath' on model 'data.data' is virtual (computed, no backing column) and
cannot be used in a query condition`, `Tests run: 1, Failures: 0`, `BUILD SUCCESS`. Full `TestQuery`
class re-run: 10/11 pass, the one failure (`TestAuthorizedQuery`, `NoSuchMethodError:
sun.misc.Unsafe.ensureClassInitialized`) is the same pre-existing, unrelated JVM/scripting-engine flake
already flagged by the prior pass — not caused by this change.

### KI-24. `characters` field on `olio.pictureBookRequest` pointed at the wrong `baseModel`, silently dropping every character sent by the client — FIXED ✅ (2026-07-18)
The wizard's character-creation call (`PictureBookUtil.createFromScenes`) received a `characters` array from the client, but `pictureBookRequestModel.json`'s `characters` field declared `"baseModel": "olio.pictureBookScene"` — the *scene* shape, not a character shape. `RecordDeserializer` deserializes each array entry against the declared `baseModel`'s field set; since a character object (`name`/`gender`/`role`) shares no fields with `olio.pictureBookScene`, every field was silently dropped (logged only as a debug-level "Invalid field" per entry). The per-character loop in `createFromScenes` then saw `name == null` for every entry and `continue`d before `createCharPerson` was ever called — so `failedCharacters` (the intended "some characters failed" signal) never populated either, since that only fires when `createCharPerson` itself returns null. Net effect: the wizard reported success with a valid `bookObjectId`, zero real `olio.charPerson` records existed, and the "Manage Characters" screen rendered permanently blank with no error anywhere.

**Fixed:** added `olio.pictureBookCharacterStub` (`name`/`gender`/`role` only — deliberately minimal; `createCharPerson` never reads client-sent `firstName`/`lastName`/`outfit`/`portraitPrompt`, and omitting `appearance` is what makes the real per-character LLM enrichment call fire and build detail from the source text instead of a client guess) and corrected `pictureBookRequestModel.json`'s `characters.baseModel` to point at it. Registered in `OlioModelNames.java`.

Also reordered the wizard (`AccountManagerUx752/src/workflows/pictureBook.js`) to match "extracted characters → manage characters → generate images": character creation (`createFromScenes`) now runs at the Step 2→3 transition (guarded so Back-then-Continue doesn't re-run it once `bookObjectId` is set), and Step 3 renders `pictureBookCharacters.js`'s real list/statistics/apparel/portrait panel inline instead of a disconnected pre-creation stub editor. `pictureBookCharacters.js` split into `initCharacterManager`/`renderCharacterManagerContent`/`openCharacterManager` so the same UI serves both Step 3 (inline) and the Step 4/5 popup; its "Open Full Editor →" link switched from `Dialog.close()` to `Dialog.closeAll()` so it closes the right thing in both contexts.

**Verified:** `TestPictureBookRequestCharactersFieldRoundTrips` (fast, no-LLM deserialization regression test) + `TestCreateFromScenesWithClientCharacterStubsCreatesRealCharacters` (live, real LLM enrichment from minimal stubs) in `TestPictureBookFull.java`, and — after KI-25 below was found and fixed — a real Playwright click-through (`e2e/pictureBookWizardUx.spec.js`, real login/navigation/clicks against the live Tomcat + Vite stack, `ensureSharedTestUser`-style test user, never admin): extract → Continue creates real characters → Step 3 shows them (not blank) → Continue to Images → Step 4 renders. Passes in ~1.6 minutes.

### KI-25. `count` param on scene-extraction endpoints silently became `0` (not the intended default of 10) whenever the client omitted it — FIXED ✅ (2026-07-22)
Found while live-verifying KI-24's fix through the actual UX (per working discipline: JUnit tests alone aren't sufficient proof). `PictureBookService.extractScenesOnly()` and `.extract()` both did:
```java
Object countObj = params.get("count");
if (countObj instanceof Number) count = ((Number) countObj).intValue();
```
`params` is a `LooseRecord` deserialized from the request JSON. When the client never includes `"count"` in the body — which the real wizard never does (`pictureBook.js`'s `doExtract()` → `sceneExtractor.js`'s `extractScenes(workObjectId, chatConfigName(), null, ...)` only sets `body.count` `if (count != null && count > 0)`, and the UI never collects a count from the user at all) — `params.get("count")` returns `0` (the unset int field's primitive default), **not** `null`. `0 instanceof Number` is true, so `count` was silently overwritten from the intended `PictureBookUtil.MAX_SCENES_DEFAULT` (10) down to `0` on every real extraction. The LLM was then asked for "the 0 most visually notable scenes," which it dutifully and quickly complies with — a valid `HTTP 200` with an empty array, indistinguishable from a broken pipeline (no error, no exception, just "no scenes" every time). This is why the bug looked identical across every model/user/session/connection-config variation tried while debugging live: none of those were ever the actual cause.

**Fixed:** both endpoints now check `params.hasField("count")` before reading the value, distinguishing "field genuinely absent" from "field present with value 0."

**Verified live:** direct REST repro (curl, real non-admin test user, real Ollama server) confirmed the exact failure (fast `[]` response, ~2-7s, well under real generation time) before the fix and a real ~30-45s completion with 5 fully-formed scenes after, through the actual redeployed Tomcat. Also covered end-to-end by the same `e2e/pictureBookWizardUx.spec.js` Playwright test cited in KI-24 (its own scene-extraction call goes through this exact code path with no explicit `count`).

**Gotcha hit repeatedly while diagnosing this (worth remembering for next time):** `olio.llm.chatConfig` no longer stores `serverUrl`/`apiKey`/`requestTimeout` directly — those moved to a separate `system.connection` record referenced via chatConfig's `connection` FK (`chatConfigModel.json`, `followReference: false`). A chatConfig created with a flat `serverUrl` field (the old, pre-migration shape) silently has no server to call — `Chat.configureChat()` logs a warning and leaves `serverUrl` unset, which looks identical to this KI-25 bug (fast, empty, no error) unless you check for it specifically. See `TestConnection.java` for the correct create-connection-then-link-it pattern; `AccountManagerObjects7/src/test/java/.../TestPictureBookFull.java`'s `getOrCreatePbChatConfig` is the canonical example for PictureBook specifically.

### KI-26. `MediaUtil`/`ArticleUtil` path-parsing regex was ASCII-only — 404'd for names like "François"/"Duña" — FIXED ✅ (2026-07-22, Stephen)
`MediaUtil.recPattern` (`AccountManagerService7/.../util/MediaUtil.java:62-64`), used by `writeBinaryContent`'s `request.getPathInfo()` parser to split a media URL into org path / model type / subPath+name, restricted every segment to `[\sA-Za-z0-9\.]`-style character classes — no accented Latin or other Unicode letters. Any group/file/character name containing a non-ASCII letter (Stephen's examples: "Duña", "François") failed `m.find()` entirely, logging `"Unexpected path construct"` and returning 404 for that image — indistinguishable from any other broken-media-URL symptom.

**Fixed:** added `\p{L}\p{N}` (Unicode letter/digit) alongside the existing ASCII classes in `recPattern`'s group 1 (org path) and group 3 (subPath+name) — group 2 (model type, e.g. `data.data`) intentionally left ASCII-only since type names are always plain identifiers. Purely additive (no character class removed), so no behavior change for existing ASCII paths.

**Same shape of bug exists in `ArticleUtil.articlePattern`** (`AccountManagerService7/.../util/ArticleUtil.java:60`, identical ASCII-only classes) — **not fixed here**, out of scope (Stephen scoped the report to the media/image path specifically); flagged for whoever picks up article-serving non-ASCII support next.

**Verified:** `TestMediaUtilStreaming#TestRecPatternMatchesNonAsciiNames` (`AccountManagerService7`) — reflects into the private `recPattern` field and asserts real matches for `/Development/data.data/Gallery/François.png` and `.../Duña.png` (plus an ASCII regression case). Confirmed the test fails against the pre-fix pattern (via `git stash`) and passes post-fix — a genuine regression guard, not just a happy-path check.

### KI-27. PictureBook wizard's own chatConfig auto-resolve intermittently 404s deep in a full Playwright run despite an identical create-then-resolve sequence succeeding via direct REST — STILL OPEN (2026-08-10, not investigated)

**2026-08-10: not investigated this pass.** It reproduces only "deep in a full Playwright run", which
needs the Vite dev server on :8899 proxying to a live stack plus the full e2e suite; the stack came up
on :9443 (the test-compose port) and no Playwright run was performed. Nothing here was ruled in or out.
Found while live-verifying the "Open Full Editor"/apparel new-tab links (below) via `e2e/pictureBookWizardUx.spec.js`. The wizard's Step 1 calls `LLMConnector.resolveConfig('contentAnalysis')` → `GET /rest/chat/library/chat/contentAnalysis`, which 404s (`{"error":"not found"}`) even though the test's own `beforeAll` had just created that exact `system.connection` + linked `olio.llm.chatConfig` moments earlier via REST, with explicit `response.ok()` checks added specifically to rule out a silent create failure (they never fired — the creates genuinely returned 200).

**Ruled out (confirmed NOT the cause):**
- Stale Tomcat bytecode — reproduces immediately after a fresh rebuild+restart.
- The create silently failing — hardened `beforeAll` checks (`connResp.ok()`/`cfgResp.ok()`, throw with response body otherwise) never fired.
- Session/account mismatch — direct curl reproduction of the identical create-then-resolve sequence (same session, and separately a **second, brand-new session for the same account**) resolved correctly every time, immediately after create.
- Server-side caching in the resolve path — `ChatUtil.getConfig` queries `AccessPoint.find()` directly (no cache layer); `LLMConnector.resolveConfig` on the client does a raw uncached `m.request` GET.

**Not yet isolated:** the failure is specific to running through the full test flow (`setupWorkflowTestData()` → note/connection/chatConfig creation in `beforeAll` → real browser login → wizard navigation) — a minimal curl-only repro of just the connection+chatConfig create/resolve steps could not reproduce it. Something about the fuller sequence (`setupWorkflowTestData`'s own additional creates — a `~/Characters` charPerson, a `~/Data` data.data, a `~/Notes` note — or the real-browser-login step specifically, as opposed to an `apiLogin`-only session) is implicated but not pinned down. Given the sheer volume of Tomcat redeploys/test runs against the shared live dev DB during this same session, cumulative server-side state (leaked threads, connection pool pressure — cf. the concurrent `MediaUtil` log spam observed around the same time) is also plausible but unconfirmed.

**Status:** deferred per Stephen's call — the actual features under test (character-creation reorder [KI-24], count-param fix [KI-25]) were already thoroughly proven via multiple full clean runs earlier in the same session; the new-tab links themselves are simple/low-risk and accepted as build-verified (clean `vite build`, straightforward `window.open` + URL construction, code-reviewed) without a final clean live run. Whoever revisits this: `e2e/pictureBookWizardUx.spec.js`'s `beforeAll` already has the hardened error-checking in place, so a future flaky run will at least fail loudly with a response body if the create step is ever the actual cause.

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

**Status: DONE — backend + both Ux752 gallery surfaces (`views/list.js` toolbar and
`page.imageGallery()` pop-in) implemented and live-verified end to end (2026-07-10/11).**
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
  `Content-Disposition: attachment`).
- **Ux752:** `src/workflows/groupExport.js` (new — `exportGroup`/`buildGroupExport`/`checkGroupExport`/
  `downloadGroupExport`, mirroring `pageIndex.js`'s Dialog→REST→toast shape), wired into
  `src/views/list.js`'s toolbar (`getExportButtons`, added to `getActionButtonBar`) — an Export icon
  button (confirm dialog → `POST /groupExport/{type}/{groupObjectId}`, toasts item count) shown whenever
  a group's contents are being viewed, plus a Download icon button that appears once
  `GET /groupExport/{type}/{groupObjectId}` confirms an export already exists (checked lazily, cached per
  `type+groupObjectId` key so it doesn't refire every redraw); clicking Download opens
  `.../download` in a new tab (`Content-Disposition: attachment` — no client-side blob assembly needed,
  unlike the `exportPipeline.js`/JSZip precedent, since the server already builds and serves the archive).
- **Live-verified**: `npx vite build` clean; `npx vitest run` — 299 passed (the 1 failing suite,
  `dialog.test.js`, is pre-existing on unmodified `main`, confirmed via `git stash`, unrelated to this
  work); new `e2e/groupExport.spec.js` (`ensureSharedTestUser`, never admin) passes live against the real
  Tomcat + Vite stack — navigates into a group's list view, clicks Export, confirms the dialog, waits for
  the real "Export complete" toast (a genuine `POST` round-trip through `AccessPoint.exportGroup` →
  `GroupExportUtil` → persisted ZIP), then clicks Download and asserts the REST response is a real `200`
  with a `zip` content-type and `attachment` disposition.
- Two real, non-obvious things found and fixed while writing the e2e test (see KI-18 for the first —
  logged separately since it's a general Ux752 dialog issue, not specific to this feature):
  a Playwright `context.waitForEvent('response', filter)` armed *after* `Promise.all([waitForEvent('popup'), click()])`
  resolves can miss a fast response entirely — arm every listener before the triggering action, in the
  same `Promise.all`; and Chromium hands an `attachment`-disposition response straight to its download
  manager, after which `Network.getResponseBody` (and Playwright's `response.body()`) legitimately 404s —
  that's a browser mechanic, not a bug, and re-verifying the downloaded bytes isn't worth fighting it here
  since `TestGroupExport` (Objects7 JUnit) already verifies real ZIP entries/content server-side; the e2e
  test's job is proving the UI wiring (button → dialog → REST → toast → download button → REST), which the
  response status/headers check already does.
- **Second gallery surface covered (2026-07-11):** Stephen flagged that `views/list.js`'s toolbar isn't
  the only gallery entry point — `page.imageGallery()` (`src/core/pageClient.js`, opened via the
  `photo_library` button on `olio.charPerson` views, `src/views/object.js:1208`) is a separate pop-in
  dialog (character portrait gallery) that doesn't go through `list.js` at all. Added three raw REST
  helpers directly in `pageClient.js` (`checkGroupExportStatus`/`buildGroupExportRaw`/
  `downloadGroupExportRaw` — NOT imported from `workflows/groupExport.js`, since workflows import
  `page`/`am7client` from `pageClient.js` and importing back would cycle), resolved the gallery's
  backing group's `objectId` (the REST contract is objectId-keyed; `imageGallery` otherwise only ever
  had the numeric `id`), and replaced the dialog's static `actions` array with a reactive
  `actions: { view: buildDialogActions }` (confirmed via `dialogCore.js` lines 116-123 that `actions`
  supports the same `{view: fn}` component form as `content`) so the Download button appears
  immediately after Export completes, without closing/reopening the dialog.
  Live-verified: new `e2e/charGalleryExport.spec.js` (`ensureSharedTestUser`, never admin) — creates a
  minimal `olio.charPerson` (no portrait, so `imageGallery` falls back to `~/Gallery`) plus one real
  image in `~/Gallery`, opens the character view, clicks `photo_library`, confirms the Export button
  inside the gallery dialog, clicks it, waits for the real "Export complete" toast (genuine `POST
  /groupExport/data.data/{groupObjectId}` round-trip), confirms the Download button then appears, clicks
  it, and asserts the REST response is a real `200` with `zip` content-type and `attachment` disposition
  — passes twice in a row against the live Tomcat + Vite stack.
  Locator gotcha found here, not present in the `list.js` version: the dialog renders as a sibling of
  `<main>`, not nested inside it (confirmed via Playwright's accessibility snapshot) — scoping locators
  to `page.getByRole('main')` (the pattern `groupExport.spec.js` used to dodge a duplicate-DOM quirk)
  finds nothing here. Scope to `page.getByRole('dialog')` instead for anything inside the pop-in; the
  "Export complete" toast itself isn't inside the dialog either, so it's asserted unscoped.
  KI-18's backdrop-intercepts-clicks issue did not reproduce for this dialog's own Export/Download
  buttons (plain, unforced clicks worked) — consistent with KI-18 being specifically about a
  `Dialog.confirm` sub-dialog stacked on top of another already-open dialog, not a single dialog's own
  action-bar buttons.

### KI-18. `am7-dialog` primary-button clicks can be intercepted by the dialog's own backdrop — FIXED ✅ (2026-07-13)
Found writing `e2e/groupExport.spec.js`: clicking a dialog's primary button (`.am7-dialog-btn-primary`)
via Playwright fails actionability with *"button ... from `<div class="am7-dialog-backdrop am7-animate-in">`
subtree intercepts pointer events"* — not a transient animation-timing flake, it persists for a full 60s
retry window regardless of viewport/scroll state. The dialog renders visually correct (confirmed via
screenshot — title, message, Cancel/Export buttons all in the expected place with no visible overlap), so
this is specifically a **pointer-events/z-index/stacking-context mismatch between the backdrop and its own
button**, not a layout bug — something in the backdrop's subtree (possibly a click-outside-to-close
overlay layer, or the `am7-animate-in` transform/transition class leaving a stale hit-test region) sits
above the button in the browser's actual hit-testing order even though the button paints on top. Worked
around in the e2e test with `.click({ force: true })`; a real user might or might not hit this depending on
exact cursor position within the button's bounds — unverified whether this affects real usage or is a
Playwright-only artifact (e.g. of headless rendering not running CSS transitions), so treat as **investigate
first, don't assume force-click is required for humans too**. **Fix direction:** inspect `dialogCore.js`'s
backdrop/button DOM structure and CSS (`am7-dialog-backdrop`, `am7-animate-in`, `am7-dialog-btn-primary`)
for a `pointer-events` or `z-index` rule that only takes effect post-animation-class-application; reproduce
with a real browser + devtools element-picker at the button's coordinates to see what's actually on top.
Possibly related to the broader KI-13 cross-view consistency umbrella (dialog usage may have drifted
inconsistent across call sites the same way sliders did in KI-16) — or a standalone dialogCore.js bug.

**Investigated per "don't assume force-click is required for humans too," and it turned out to be a real,
non-CSS bug — confirmed, not assumed:** `views/list.js`'s `renderContent()` (used by every `/list/...`
route) redundantly rendered its own `page.components.dialog.loadDialogs()` + `page.loadToast()`, in
addition to `router.js`'s global `OverlayGuard`, which already renders both for every route. Result: on
list routes specifically, **two live, pixel-identical `.am7-dialog-backdrop` copies** existed
simultaneously whenever a dialog was open — one nested inside `<main>` (from `renderContent()`), one a
sibling of it (from `OverlayGuard`). Confirmed via `document.elementFromPoint()` at the real button
coordinates: `backdropCount:2`, and the hit-test resolved to the *other* (outside-`<main>`) copy's
button, not the one a `page.getByRole('main')`-scoped locator finds — a structural duplicate-render bug,
not a CSS-animation-timing artifact.

**Fixed:** `views/list.js` `renderContent()` no longer double-mounts the dialog/toast overlay; the
embedded/picker gating check was also fixed to read the internal `pickerMode`/`embeddedMode` state (not
only `vnode.attrs`), since `components/picker.js`'s dedicated list instance calls `renderContent()`
directly and bypasses `vnode.attrs` entirely. Removed the `{force:true}` workaround from
`e2e/groupExport.spec.js` (scoped its locators to `page.getByRole('dialog')`, matching KI-19's
established pattern) — the plain, unforced click now passes. Regression-checked `list.spec.js`/
`listNavigation.spec.js`/`breadcrumb*.spec.js` against unmodified `main` to confirm two pre-existing,
unrelated flakes (a `.../Favorites` console-error flake and a carousel/pagination bug) reproduce
identically there and aren't caused by this fix.

### KI-19. `uri`/object-link field: broken URI + wrong tab placement — FIXED ✅ (2026-07-11; follow-up closed 2026-07-23, Stephen)
Stephen flagged, while checking KI-17's `data.groupExport` records: the generic `uri` field (added to
`system.primaryKey` — every model inherits it — rendered via `formFieldRenderers.js`'s `object-link`
format: a link + a `file_json` print icon) showed up **directly on the object's main/default tab**
instead of tucked into the "Info" sub-tab like every other model, and the link itself was broken two
ways: (a) the built URI duplicated `/rest` (`client.base()` already returns `.../rest` — see
`am7client.js`'s `sBase = applicationPath + "/rest"` — so appending another `/rest/model/...` produced
`.../rest/rest/model/...`), and (b) it pointed at the default minimal-field stub instead of `/full`
(the fully-populated record — see `model-api.md`'s field-projection rules).

**Root cause of the tab-placement bug:** `data.groupExport` (added for KI-17) had **zero** entries in
`AccountManagerUx752/src/core/modelDef.js` or `formDef.js` — every other model in the schema maps its
`uri`/`urn`/`objectId`/date fields to a named Info sub-form (`groupinfo`/`groupdateinfo`/`dateinfo`,
looked up via `am7model.forms[type.split('.').pop()]` in `model.js:734`); with no `forms.groupExport`
entry at all, every field — including `uri` — fell onto a single unstructured tab.

**Fixed:**
- `AccountManagerUx752/src/components/formFieldRenderers.js` (`renderers["object-link"]`) — corrected the
  URI to `client.base() + "/model/" + modelKey + "/" + objectId + "/full"` (no more double `/rest`, now
  requests the full record). This is the shared renderer for every model's `uri` field, not just
  `data.groupExport`'s — the fix applies everywhere the link is used.
- `AccountManagerUx752/src/core/modelDef.js` — added the missing `data.groupExport` schema entry
  (mirrors the real model at `AccountManagerObjects7/.../models/data/groupExportModel.json`: inherits
  `data.directory` + `common.dateTime`; fields `sourceGroup`/`archive`/`itemCount`/`generatedDate`).
- `AccountManagerUx752/src/core/formDef.js` — added `forms.groupExport` (label "Export") with
  `sourceGroup`/`archive`/`itemCount`/`generatedDate` on the main tab and `forms: ["groupdateinfo"]` for
  the Info sub-tab (same pattern `forms.data` already uses), so `uri`/`urn`/`objectId`/`organizationPath`/
  the three dates/`groupPath` land in Info like everywhere else.
- **Real bug caught while fixing this, not by inspection:** originally gave `sourceGroup`/`archive` their
  own `format: "object-link"` too (they're foreign single-model fields, seemed like a natural fit for a
  "here's a link to the referenced record" affordance) — live-tested and found the renderer resolves
  `ctx.useEntity || ctx.entity` to the **containing** record for these fields, not the field's own foreign
  value, so both fields rendered a link/label pointing back at the groupExport container itself (visible
  proof: both showed the same href and the same label, the container's own name). **`object-link` is only
  correct for a field bound to the record's own identity (like the top-level `uri` field), not for
  arbitrary nested foreign-model fields** — reverted `sourceGroup`/`archive` to the default foreign-field
  renderer (no custom format).
- **Known follow-up, not fixed here (logged, not silently dropped):** the default renderer for a foreign
  single-`model` field with no custom format just stringifies the object, so `sourceGroup`/`archive` on
  `data.groupExport`'s main tab currently display literally `[object Object]` in a disabled textbox —
  functionally harmless (the fields are read-only anyway) but not informative. A real fix needs either a
  small dedicated renderer (name + link, without the `object-link` bug above) or reuse of whatever
  read-only "foreign record summary" pattern (if any) exists elsewhere in Ux752 — not investigated further
  here since it wasn't the reported issue.
- **Live-verified:** new `e2e/objectLinkFix.spec.js` (`ensureSharedTestUser`, never admin) — builds a real
  export via `POST /groupExport/data.data/{groupObjectId}`, navigates to the resulting
  `data.groupExport` record's view page, confirms the main/"Export" tab does **not** carry the `uri` link,
  clicks the "Info" tab, confirms the link is there, and asserts its `href` has no `/rest/rest` and ends in
  `/full`. Locators had to be scoped with the `:visible` pseudo-class, not just `page.getByRole('main')` —
  this app mounts a second, offscreen/hidden copy of the DOM (the same pre-existing quirk noted in
  `e2e/groupExport.spec.js`), and the hidden copy matched the plain locator too, producing a false failure
  before the `:visible` scoping was added.
- `npx vite build` clean; `npx vitest run` — 299 passed (same 1 pre-existing unrelated `dialog.test.js`
  failure as KI-17, confirmed unrelated via `git stash` there).

**Follow-up fixed (2026-07-23, Stephen requested):** the known-follow-up `[object Object]` display
noted above is resolved. `components/formFieldRenderers.js` gained `renderers["foreign-summary"]` —
reads the field's own value via `ctx.defVal` (the same accessor every other field renderer uses),
not `ctx.entity`/`ctx.useEntity` (the containing record — `object-link`'s bug), and renders a
name+link (falling back to `objectId` as the label when the foreign record has no `name`, and a
`(none)` placeholder when the field is empty) instead of stringifying the object. `core/formDef.js`'s
`forms.groupExport` now sets `format: "foreign-summary"` on both `sourceGroup` and `archive`.
**Verified:** new `src/test/foreignSummaryRenderer.test.js` (3 tests, real Mithril vnode inspection,
not source-text greps) — asserts the link/label are built from the field's own foreign value, not the
container's; the objectId-fallback label; and the empty-value placeholder. Confirmed as a genuine
regression guard for the exact KI-19 bug class: temporarily swapped the renderer's `ctx.defVal` back
to `ctx.useEntity || ctx.entity`, reran — 2/3 failed exactly as predicted (link pointed at the
container's `objectId`/model instead of the field's own), restored, reran — 3/3 pass. Full suite:
`npx vitest run` — 323/323 individual tests pass (only the same pre-existing, unrelated
`dialog.test.js` cross-file unhandled-rejection flake noted above, confirmed via prior `git stash`
checks elsewhere in this doc). `npx vite build` — clean.

### KI-20. Deleting a stream-backed `data.data` never deletes the underlying file(s) — FEATURE REQUEST (2026-07-11, Stephen)

Add a gated system property (`resource.properties` for test/console, `web.xml` context-param for the
backend — mirroring the existing `test.db.reset`/`database.dropColumns` opt-in pattern) that, when
enabled, deletes the physical on-disk file(s) backing a `data.data`'s stream when that record is
deleted — including any currently-"unboxed" (decrypted) copy, not just the encrypted `.box` file.

**Grounding research (file:line):**

- **Where the bytes actually live:** despite the model name, there is no `data.stream_segment` DB
  table — `data.streamSegment` is declared `"ephemeral": true`
  (`AccountManagerObjects7/.../models/data/streamSegmentModel.json:3`) with custom
  `StreamSegmentReader`/`Writer`/`Search` IO classes, and `SchemaUtil.java:206` skips ephemeral models
  when generating DB tables. The real bytes are one file per stream, written by
  `StreamSegmentWriter.writeFileSegment` (`.../io/stream/StreamSegmentWriter.java:126-161`) to a path
  from `StreamSegmentUtil.getFileStreamPath(stream)` (`.../io/stream/StreamSegmentUtil.java:133-201`,
  pattern `IOFactory.DEFAULT_FILE_BASE/.streams/<org>/<groupId>/<objectId><ext>`), cached onto the
  stream's (encrypted) `streamSource` field.
- **What "unboxed" means (not `VaultService.unvaultField` — a distinct, stream-file-specific concept):**
  `StreamUtil.boxStream`/`unboxStream` (`.../util/StreamUtil.java:201-270`, `rebox` at 272-326) encrypt/
  decrypt the **entire physical stream file in place**: `boxStream` deletes the plaintext and leaves only
  `<path>.box`; `unboxStream` writes the plaintext back out to `<path>` (no `.box`) alongside it.
  `isStreamUnboxed`/`unboxedMap` (lines 126-147) track which streams currently have a live plaintext
  copy on disk. Cleanup already exists but is **manual/opt-in only**: `clearUnboxedStream` (162-197,
  single stream) and `clearAllUnboxedStreams` (162-180, sweeps the whole `.streams/` tree via
  `DirectoryUtil` deleting any non-`.box` file) — normal upload (`streamToData`, lines 450-546) calls
  `boxStream` + `clearUnboxedStream` right after writing (530-533), so the plaintext copy is
  conventionally short-lived, but **nothing forces that**, and **nothing ties either the `.box` file or
  a lingering unboxed copy to record deletion**.
- **What delete does today — confirmed it touches neither the `data.stream` row nor any file:**
  `AccessPoint.delete(...)` (`AccountManagerObjects7/.../client/AccessPoint.java:320-396`) →
  `RecordUtil.deleteRecord` (`.../util/RecordUtil.java:744-759`) → `DBWriter.delete`
  (`.../io/db/DBWriter.java:101-144`) deletes only the `data.data` row itself; the foreign-key cascade
  path (`StatementUtil.getForeignDeleteTemplate`, gated by `deleteForeignReferences`, default `false`,
  never set `true` anywhere in the codebase) only nulls/cascades **other** tables' FKs pointing *at* the
  deleted row — it does not follow the deleted row's *own* FK (`data.data.stream` → `data.stream`), a
  gap `StatementUtil.java:104`'s own comment acknowledges ("there's the possibility that orphans would
  be left"). Even if the `data.stream` row itself were deleted, `StreamSegmentWriter.delete(...)`
  (`.../io/stream/StreamSegmentWriter.java:79-82`) is a hard-coded no-op (`// TODO`) — there is currently
  **no code path anywhere that deletes the on-disk file**, boxed, unboxed, or otherwise.

  **Design note (Stephen, 2026-07-11): this non-cascading behavior is intentional, not a gap in
  general.** Deleting a container (a group, or any object with children/contained objects) has always
  been by design a delete of *only that object* — it does not recursively delete the group's contents.
  A separate **orphan cleanup process** is responsible for finding and deleting objects that have been
  abandoned by their parent/container and reaping them independently, on its own schedule, rather than
  as a synchronous side effect of the parent's delete. KI-20's ask is narrower than "make delete
  cascade": it's specifically about the *physical file* backing a stream having no cleanup path at all
  today, not the DB-row orphan case, which the existing orphan-cleanup process already covers by design.
  Any implementation of KI-20 should plug into that same orphan-cleanup rhythm (or at minimum not
  contradict it) rather than introducing ad hoc synchronous cascade-on-delete behavior — see KI-21 for
  the bigger architectural question this raises for an enterprise deployment.
- **Precedent for the gated property (mirror this exactly):** test/console side —
  `test.db.reset`/`db.schema.dropColumns` in
  `AccountManagerObjects7/src/test/resources/resource.properties:5,7`, read via
  `Boolean.parseBoolean(testProperties.getProperty(...))` in
  `AccountManagerObjects7/.../tests/BaseTest.java:88,135`. Backend side — `database.dropColumns`
  (`AccountManagerService7/src/main/webapp/WEB-INF/web.xml:47-49`) and `vector.enabled`
  (lines 163-165) both read via `context.getInitParameter(...)` in
  `RestServiceEventListener.java:214,240` (the latter using a `parseBoolean(str, default)` helper that
  supports a non-false default, worth reusing if this property should default to a specific value); the
  existing `stream.cutoff` param (web.xml:57-59, read at `RestServiceEventListener.java:198-201` via
  `StreamUtil.setStreamCutoff`) is the closest existing precedent for a *stream-specific* config knob
  and the natural place to add the new one alongside it.

**Scope note:** genuinely new behavior (a delete-time hook wiring `data.data` deletion to
`StreamUtil`'s existing box/unbox-aware file paths, plus the new gated property on both the test/console
and backend config surfaces) — not a small patch, and the flag should very likely default to **off**
given `deleteForeignReferences`'s existing default and the orphan-file risk of getting this wrong
(deleting a file a *different* still-live record's stream also points at, if streams are ever shared —
not confirmed either way here, worth checking before implementing). Filed here per the tracker's own
header for scoping/sequencing later, not committed to a timeline.

### KI-21. Soft delete — DESIGN NOTE / open architectural question (2026-07-11, Stephen)

Raised while writing KI-20: today's delete model — a container's delete only removes that one record,
with a separate orphan-cleanup process reaping abandoned children independently — is a deliberate,
long-standing design choice, not an oversight (see the design note inside KI-20). It works well for the
"reap what's abandoned, eventually" model. But **an enterprise deployment may need an actual soft-delete
concept** (mark-as-deleted with a retention/undo window, audit trail of who deleted what and when,
exclusion from normal queries without physical removal, eventual hard-delete or archive after a
retention period) — and Stephen was explicit that this would be **an entirely new concept**, not an
extension of the existing hard-delete-plus-orphan-cleanup model.

**Not researched or scoped here** — no grounding file:line citations yet, deliberately, since this is
architecture-level and needs a real design pass, not a patch. Things a future design pass should
resolve before any implementation:
- Where the "deleted" flag/timestamp would live — a field on `common.base`/`system.primaryKey` (every
  model), or an opt-in mixin model (`common.softDeleteExt` or similar) the way `data.journalExt`/
  `crypto.vaultExt` are opt-in today?
- How PBAC/`AccessPoint` and the query layer (`Query`/`QueryPlan`/`ISearch`) treat soft-deleted records
  by default — excluded from every query unless explicitly asked for (an `includeDeleted` query flag),
  mirroring how `data.directory`-derived queries already require an explicit `organizationId` condition?
- Interaction with the existing orphan-cleanup process (KI-20's design note) — does orphan cleanup
  soft-delete first and hard-delete later, or are they two independent mechanisms?
- Interaction with KI-20's stream-file cleanup — a soft-deleted `data.data` almost certainly should
  *not* have its stream file deleted yet (defeats "undo"), so KI-20's file-cleanup hook would need to
  fire at hard-delete/retention-expiry time, not at soft-delete time, if both land.
- Whether this is global (every model) or scoped to specific high-value domains first (e.g. `data.*`,
  `identity.*`) given "entirely new concept" implies real schema/migration cost across the whole model
  set.

**Scope note:** explicitly filed as a design question, not a committed feature or even a scoped feature
request yet — per the tracker's own header, this is here so the question doesn't get lost, not because
an implementation approach has been chosen.

## Service7 — Media serving (2026-07-13, found while investigating the group-export OOM fix)

### KI-22. `MediaUtil.writeBinaryData` buffers the entire file in memory before writing the response — OPEN

Stephen asked, while an OOM-on-large-group-export fix was in progress (KI-17 follow-up,
`GroupExportUtil`/`ZipUtil`), whether the same "buffer everything, then write" shape exists in
`MediaUtil` — it does, in the generic media/download-serving path used by images, thumbnails, and any
`data.data` content served back over HTTP.

**Where:** `AccountManagerService7/src/main/java/org/cote/accountmanager/util/MediaUtil.java`,
`writeBinaryData(...)`, lines 332-397. For a stream-backed record it calls
`StreamSegmentUtil.streamToEnd(streamId, 0, 0)` (line 342) — passing `len=0` tells the underlying
`readFileSegment` to read to end-of-file into one `ByteArrayOutputStream`/`byte[]` in a single read
(`StreamSegmentUtil.java:50-80`) — and for an inline record, `ByteModelUtil.getValue(data)` (line 345).
Either way the full content lands in one `byte[] value`, optionally gets base64-re-encoded whole
(`BinaryUtil.toBase64(value)`, line 352, if `options.isEncodeData()`), then is written in one shot:
`response.setContentLength(value.length); response.getOutputStream().write(value);` (lines 395-396).
For a large stream-backed file (video, big document, big export download) this holds the entire file
in memory at once — the same OOM shape as the group-export bug, just in the generic media-serving path
rather than the export-building path.

**A streaming primitive already exists to fix this**, added as part of the KI-17 OOM follow-up:
`StreamSegmentUtil.streamToOutput(String streamId, OutputStream out, int chunkSize)` — pages through
the file `chunkSize` bytes at a time, writing each chunk straight to a caller-supplied `OutputStream`,
so at most one chunk is resident in memory regardless of total stream size (see its doc comment in
`StreamSegmentUtil.java` for the exact rationale, same file as `streamToEnd`).

**Fix direction (not implemented here — judged a significant-enough change to log rather than
drive-by fix per Stephen's instruction):**
- Swap `streamToEnd(...)` for `streamToOutput(streamId, response.getOutputStream(), chunkSize)` on the
  stream-backed branch, so bytes flow straight to the servlet response instead of through an
  intermediate full-size `byte[]`.
- `response.setContentLength(value.length)` currently relies on already having read the whole array to
  know its size. True streaming needs either (a) a cheap way to get the stream's byte size up front
  without reading it (check whether `data.stream` or the backing file exposes a size/length without a
  full read — e.g. `Files.size()` on the path from `StreamSegmentUtil.getFileStreamPath`), or (b) skip
  `setContentLength` entirely and let the servlet container fall back to chunked transfer encoding —
  the simpler option, but confirm no caller depends on `Content-Length` being present (e.g. a client
  progress bar, or range requests — check whether this path or a sibling one supports HTTP range/partial
  content before assuming it's safe to drop).
- `options.isEncodeData()` (base64) and `options.isUseTemplate()` (HTML-template wrapping, the DWAC
  path) both currently assume a full in-memory buffer — a real streaming base64 encoder exists in the
  JDK (`Base64.getEncoder().wrap(OutputStream)`) so that branch is fixable the same way, but the
  template-wrapping branch string-replaces content into a text template and is presumably only ever
  used for small text content, not large binary media — worth confirming that assumption (or gating
  streaming mode off when a template is requested) rather than assuming it needs the same treatment.
- The inline (`ByteModelUtil.getValue`) branch is lower priority: inline storage only happens for
  content under `StreamUtil`'s size cutoff (default 1MB per KI-17's grounding notes), so it's already
  bounded and not the source of the >1GB-scale OOM risk — the stream-backed branch is what matters.
- Needs a real live test (not just code inspection) proving a large stream-backed download completes
  without OOM and with byte-for-byte correct content (checksum against the source) — mirroring the kind
  of test the KI-17 OOM follow-up used for `GroupExportUtil`/`ZipUtil`.

**Scope note:** touches a generic, widely-used serving path (images/thumbnails/any download) with
several option-interaction edge cases (`isEncodeData`, `isUseTemplate`, thumbnail vs. full content,
possible range-request support elsewhere) and needs a genuine large-payload test to verify — filed here
per the tracker's own header for scoping/sequencing later, not committed to a timeline in this pass.

**Status:** FIXED ✅ (2026-07-13) — see below.

`writeBinaryData`'s stream-backed branch now calls `StreamSegmentUtil.streamToOutput(streamId,
response.getOutputStream(), MEDIA_STREAM_CHUNK_SIZE)` directly instead of `streamToEnd(...)`, so at
most one 1MB chunk is resident at a time. Content-Length is set up front via
`StreamSegmentUtil.getFileStreamSize(stream)` (a cheap `FileChannel.size()` call, no read of the file
content) when it returns a positive size; otherwise the container falls back to chunked transfer
encoding — no HTTP range/partial-content support existed on this path before or after this change, so
nothing regressed there. Per the fix-direction notes above, `isEncodeData()` (base64) and
`isUseTemplate()` requests still fall through to the original buffered `streamToEnd(...)` +
`byte[]` path unchanged — both are bounded/small-content use cases, not the >1GB-scale risk this
issue was about, and streaming-safe base64/template rewrites are deferred rather than drive-by'd here.
Real end-to-end JUnit coverage (`AccountManagerService7`, `TestMediaUtilStreaming`) exercises
`MediaUtil.writeBinaryData` directly against a live-DB stream-backed fixture spanning multiple chunks
and asserts byte-for-byte content via checksum, plus a regression case proving the small/inline and
`isEncodeData`/`isUseTemplate` branches are unaffected.

## Objects7 — Vault/crypto (2026-07-13, found while verifying KI-22's fix doesn't route through another buffer-everything path)

### KI-23. `StreamUtil.unboxStream`/`boxStream` decrypt or encrypt an entire boxed stream file into one `byte[]` in memory — OPEN

While confirming that KI-22's fix (`StreamSegmentUtil.streamToOutput`) doesn't itself hit a hidden
buffer-everything path, traced what `streamToOutput` calls before paging chunks:
`StreamUtil.unboxStream(stream, false)` (`StreamSegmentUtil.java:95`, and identically at line 55 in the
pre-existing `streamToEnd`). `unboxStream` is a cheap no-op in the common case — it returns immediately
if the stream isn't `StreamEnumType.FILE`, or if `isStreamUnboxed(stream)` says the plaintext file
already exists on disk (`StreamUtil.java:126-147`, backed by an in-memory `unboxedMap` cache after the
first check). But when a stream's plaintext file does NOT yet exist and only the encrypted `.box` file
does — i.e. the very first read after the file was boxed (encrypted at rest) — it falls into
`rebox(stream, f2, path, false)` (`StreamUtil.java:269`), which does:

```java
// StreamUtil.java:307-313
byte[] eval = new byte[0];
if(enc) {
    eval = CryptoUtil.encipher(key, fileHandleToBytes(f1));
} else {
    eval = CryptoUtil.decipher(key, fileHandleToBytes(f1));
}
```

`fileHandleToBytes(File)` (`StreamUtil.java:96-112`) reads the entire file into one
`ByteArrayOutputStream`/`byte[]` via `copyStream(FileInputStream, baos)`, `CryptoUtil.decipher`/
`encipher` (`CryptoUtil.java:206-234`) then run byte[]-in/byte[]-out over the whole thing, and the
result is written back out whole. Same OOM shape as KI-17/KI-22, one layer deeper: a large boxed stream
file being unboxed for the first time holds two full copies of the file in memory at once (ciphertext +
plaintext `byte[]`), on top of whatever `CryptoUtil` allocates internally for the cipher operation.

**This is pre-existing and NOT a regression from the KI-22 fix** — both the old `streamToEnd` and the
new `streamToOutput` call the identical `unboxStream(stream, false)` line before reading, so exposure is
unchanged either way.

**Why not drive-by fixed here:** `CryptoUtil` has no streaming cipher primitives at all (`encipher`/
`decipher` are exclusively `byte[] -> byte[]`, confirmed by grep — no `CipherInputStream`/
`CipherOutputStream` usage anywhere in the class). A correct fix means adding genuine streaming
cipher support (`CipherOutputStream`/`CipherInputStream` chained to `FileInputStream`/`FileOutputStream`)
to security-sensitive vault/crypto code — IV handling, auth-tag placement for authenticated modes, and
chunk-boundary correctness all need care and dedicated crypto-focused tests, not a quick swap. Matches
the same judgment call already made for KI-22 (log significant, security/crypto-adjacent changes rather
than drive-by fixing them).

**Correction — this is NOT theoretical, it fires on every real upload.** An initial grep for
`StreamUtil.boxStream(...)` (dotted call form) found only test callers and this doc originally
(wrongly) called it a test-only exposure. Re-checked by actually running the KI-22 regression test
against a live H2-backed instance: creating a stream-backed file via `StreamUtil.streamToData(...)` —
the same entry point real uploads use — logs `Boxing ./am7/.streams/.../<id>.a.box ...` and deletes the
plaintext file every time. The call site is `StreamUtil.java:531`, inside `streamToData` itself:
`boxStream(streamRec, false); clearUnboxedStream(streamRec);` — an undotted same-class call the first
grep missed. So **every** stream-backed upload is boxed (encrypted at rest) immediately, and the
*first* read after upload always takes the `rebox()`/`fileHandleToBytes` full-buffer path described
above before any chunked reading can begin — confirmed directly: the KI-22 regression test below hit a
`FileNotFoundException` on the plaintext path until `unboxStream`'s rebox ran.

**Consequence for the KI-22 fix above:** `MediaUtil`'s new `streamToOutput` call only pages the file in
chunks once the plaintext copy already exists on disk. For a file's first read after upload,
`unboxStream` (called at the top of both `streamToEnd` and `streamToOutput`, unchanged either way) still
buffers the whole file once during unboxing before any chunking helps. The KI-22 fix is real and
correct for what it targets — the read-time serving path — but does NOT close the full OOM risk for a
stream-backed file's first read; that gap is this issue, one layer down.

**Scope note:** filed for scoping/sequencing later, not committed to a timeline in this pass. Given this
fires on every upload rather than an edge case, this should be prioritized above where it might otherwise
sit on the backlog.

**Status:** FIXED ✅ (2026-07-13) — see below.

`StreamUtil.rebox()` (`StreamUtil.java`, the shared method behind both `boxStream` and `unboxStream`) no
longer calls `fileHandleToBytes(f1)` to pull the whole source file into one `byte[]` before handing it to
`CryptoUtil.encipher`/`decipher`. It now opens a `FileInputStream`/`FileOutputStream` pair on the source
and destination paths and drives them through two new methods on `CryptoUtil`:
`decipherStream(CryptoBean, InputStream, OutputStream)` and `encipherStream(CryptoBean, InputStream,
OutputStream)`. Both wrap the exact same already-initialized `Cipher` that `CryptoFactory.getDecrypt/
EncryptCipherKey()` already produces for the byte[] `decipher()`/`encipher()` methods — via
`CipherInputStream`/`CipherOutputStream`, the JDK's own streaming primitives for a `Cipher` — so this is
not a new crypto implementation, just driving the existing cipher a chunk at a time instead of calling
`doFinal()` on a whole-file buffer. Confirmed via `CryptoFactory.getCipherKey()` that the stream/file
cipher path always uses a symmetric secret key + `IvParameterSpec` (not the asymmetric EC/IES branch,
which is for small field-level values elsewhere), so this is an ordinary block-cipher stream, well within
what `CipherInputStream`/`CipherOutputStream` are designed for.

Real test coverage: `TestStreamEncryption#TestStreamEncryptionLargeRoundTrip`
(`AccountManagerObjects7`) uploads a ~3MB stream-backed fixture spanning multiple 1MB
`StreamSegmentUtil` chunks (confirming it gets boxed immediately per the finding above), force-unboxes
it through the new `CipherInputStream` path, and asserts byte-for-byte + SHA-256 checksum equality
against the original content — then re-boxes (streaming encipher) and re-unboxes (streaming decipher)
a second time to prove both directions of the new streaming path, not just one. `TestMediaUtilStreaming`
(`AccountManagerService7`, from the KI-22 fix) independently exercises the same code path end-to-end
through `MediaUtil.writeBinaryData`'s first read after upload. Pre-existing `TestGroupExport` and
`TestStreamEncryption` (original methods) suites re-ran clean against the change (7/7 passing) —
confirming the KI-17 export path and existing box/unbox behavior are unaffected.

### KI-62. Opening the picture gallery for a character with NO pictures lands on `~/Data` — OPEN (2026-08-10, Stephen)

Open the picture gallery for a charPerson that has no images yet and the browser defaults to `~/Data`
instead of the character's own gallery location (or an empty view of it). The user then has to
navigate to where the images would go, and there is nothing telling them where that is.

**Not diagnosed — pointers only, no cause claimed.** The inline gallery renderer
(`formFieldRenderers.js:567`, `renderers.gallery`) simply returns `"No images"` when the list is
empty, so it is not the source of a path default; the `~/Data` fallback is coming from whatever opens
the gallery BROWSER. The only literal `~/Data` defaults found in `Ux752/src` are
`sdConfig.js:96` and `:121` (`groupPath || "~/Data/.preferences"`), which are for SD preference
storage and look unrelated — mentioned so the next person does not re-find them and assume.

Related: KI-63 and KI-34/KI-61 — where a character's images *should* live is exactly the unresolved
question those entries are about, so fixing the empty-gallery default probably wants to wait on the
storage-location decision rather than hardcode another path.

### KI-63. PictureBook character images are created under Olio and are not compartmentalized to the book — OPEN (2026-08-10, Stephen)

Character images generated for a picture book land in the **Olio world's** storage
(`{world.gallery.path}/Characters/{name}` — see `SDUtil.resolveCharacterImagePath`), not under the
book. They should be compartmentalized to the picture book that produced them.

This is the same knot as KI-34 (two same-named characters share one world-wide group) and KI-61 (my
attempt to scope the path to the character's own group broke reimage, because the writer is the Olio
user and the character's group is in the acting user's home). Those two entries state the constraint
that any fix must satisfy: a collision-free location **and** one the writing principal is authorized
to create in.

**Stephen's proposed direction (2026-08-10) — a world per picture book:**

> *"I'm almost wondering if it's easier to make a new 'world' for the picture book and then use that
> reference as it will allow everything to be in contextually appropriate locations without having to
> custom handle it, and thereby make a world specific to a picturebook."*

Worth taking seriously, because it dissolves the KI-61 dilemma rather than working around it. A
book-scoped world would give:
- **contextual location for free** — characters, apparel, wearables, qualities, narratives, gallery
  all land in that world's own directories, which is what the Olio machinery already does; no
  PictureBook-specific path handling to maintain;
- **collision-free by construction** — two books are two worlds, so two "Jideon"s never share storage,
  which is KI-34 without a special case;
- **a principal that matches the tree** — the Olio user owns the world it creates in, so the
  create-authorization failure in KI-61 does not arise;
- **deletion that actually cleans up** — dropping a book becomes dropping its world, which is relevant
  to KI-32 (`reset()`'s non-recursive delete) and to KI-60's delete-then-recreate repro.

Open questions for whoever picks this up (not answered here): what the per-book world costs to create
(the universe seed is the expensive part, and a world sharing an existing universe should be cheap —
verify, do not assume); whether the shared colour/word libraries stay on the universe so
`ApparelUtil`'s complementary-colour lookup keeps working (KI-35's note says that sharing is the
reason apparel is Olio-owned in the first place); and what happens to books that already exist.

Related: KI-34, KI-61, KI-32, KI-60, KI-62.

### KI-61. I broke character reimage by "fixing" KI-34 — REVERTED ✅ (2026-08-10, Stephen)

```
PathUtil - Not authorized to create auth.group of type (DATA) node François Touvier
  with parent #3535 in path /home/steve/Data/PictureBooks/catatone 3/Characters/François Touvier/Gallery
```

**Character reimage was working. I changed it for a backlog item nobody had reported, and broke it.**

`SDUtil.generateSDImages`/`generateSDFigurines` call
`createPersonImage(octx.getOlioUser(), per, path, …)` — the principal is the **Olio user**. My KI-34
change moved `path` from `{world.gallery.path}/Characters/{name}` (inside the Olio world, where the
Olio user has create rights) to the character's own `groupPath + "/" + name + "/Gallery"`, which for a
PictureBook character sits inside the **acting user's home**. I changed the storage location without
changing the principal that writes there, so `makePath`'s create-authorization check refused it and
reimage stopped producing images.

**Reverted** to the original world-gallery path. `resolveCharacterImagePath` remains as a single seam
with the constraint documented at the top, so the next attempt does not rediscover it the hard way.

**What any real KI-34 fix must satisfy — BOTH, not either:**
1. a collision-free location for two charPersons that share a name, and
2. a location the **calling principal** (the Olio user, for these two callers) is authorized to create
   in — or a change of principal to the character's owner.

`TestKi34PortraitPathIsResolvableByTheOlioUserThatWritesIt` now asserts (2) directly by running the
real `makePath` as the Olio user, so this specific break cannot be reintroduced silently.
`TestKi34SameNamedCharactersStillCollide_KnownOpen` pins that (1) is still broken, so the collision is
not forgotten.

**Process failure, recorded:** KI-34 was a theoretical entry in the backlog, not a reported fault. I
modified working production code to address it, shipped the regression, and then framed the breakage
as a constraint discovery instead of leading with "I broke a working feature." Backlog items that
require touching working code need a live exercise of that feature before and after — see
`.claude/rules/llm-conduct.md`, "Deflection: the specific forms it takes here".

### KI-34. Standalone character-portrait image generation keys storage off `{world.gallery.path}/Characters/{name}` — collides whenever two charPerson records share a name — REOPENED ⚠ (2026-08-10)

> **The 2026-08-10 "fix" below was REVERTED — it broke character reimage. See KI-61.** The collision
> described in this entry is still present and unfixed. Any new attempt must also satisfy the
> create-authorization constraint KI-61 documents.

New `SDUtil.resolveCharacterImagePath(octx, per)` is the single seam, used by both
`generateSDFigurines` and `generateSDImages`. It derives storage from the character's OWN group —
`{groupPath}/{name}/Gallery` — instead of the world-wide `{world.gallery.path}/Characters/{name}`.
The name stays as the leaf so the layout is still browsable and two characters in the same group don't
share a folder; it is the ROOT that changes from world-wide to character-scoped.

The audit this entry asked for (callers that pass a bare `pop` without a reliably-populated
`groupPath`) is handled rather than assumed: `groupPath` is virtual (computed by `PathProvider`), so
when a partially-planned record arrives without it the helper resolves it from `groupId` before
falling back. The world-gallery scheme remains only for a character with no resolvable group of its
own, and logs a warning when it fires. Apparel/mannequin storage is untouched, per this entry's own
scope note.

**Verified:** `TestKi34SameNamedCharactersInDifferentBooksGetDistinctImagePaths` — two charPersons both
named "Jideon de Rosa" in different book groups now resolve to
`…/KI34 Book A/Characters/Jideon de Rosa/Gallery` vs `…/KI34 Book B/…`.
`TestKi34GroupPathIsResolvedFromGroupIdWhenNotProjected` re-reads a character with a groupId-only
projection and confirms it still lands in its own book group, not the world gallery.

<details><summary>Original entry</summary>


Found while wiring up PictureBook apparel generation. `SDUtil.generateSDImages` and
`SDUtil.generateSDFigurines` (`AccountManagerObjects7/.../olio/sd/SDUtil.java`, ~lines 314-321 and
360-365) both derive the image storage path as:
```java
String basePath = octx.getWorld().get("gallery.path");
String path = basePath + "/Characters/" + per.get(FieldNames.FIELD_NAME);
```
This is the **world's own gallery path** (shared across every book/context/run in the organization),
keyed purely by the character's display **name** — not the charPerson's own `objectId` or `groupPath`.
Two different `charPerson` records with the same name (a recurring name across separate PictureBook
runs, e.g. re-running the same source story, or just two different stories that both have a "Jideon")
resolve to the identical storage group and will overwrite/share each other's portrait images. This is
the path used by `OlioService.reimageWithConfig` (standalone `POST /olio/{type}/{objectId}/reimage`,
the Ux's "Regenerate Portrait" button) and any other caller of `generateSDImages`/`generateSDFigurines`
for general Olio population image generation — **not** PictureBook's own scene-driven pipeline, which
already avoids this: `PictureBookUtil.generateSceneImage`'s Stage 1
(`PictureBookUtil.java` ~line 2602, `portraitGroupPath = isBook ? sceneGroupPath.replace("/Scenes",
"/Characters") : sceneGroupPath`) is book-scoped via the scene's own group path, not name+world.

**Explicitly out of scope for this entry (confirmed with Stephen 2026-07-24):** apparel/mannequin
image storage (`SDUtil.generateMannequinImages`, `OlioService.reimageApparel`'s `"~/Gallery/Apparel/"
+ apparelName` groupPath) is a separate concern and should not be touched as part of this fix.

**Fix direction (not yet implemented — deliberately spun off as its own item rather than bundled into
the apparel work that surfaced it):** derive the portrait storage path from the character's own
already-unique location instead of `{world.gallery.path}/Characters/{name}` — e.g.
`per.get(FieldNames.FIELD_GROUP_PATH) + "/Gallery"` when the character has one (every charPerson
already lives somewhere specific — a book's own `.../Characters/{Name}/` when created via PictureBook,
or wherever else it was placed otherwise), falling back to the current `{world.gallery.path}/Characters/
{name}` scheme only for characters with no meaningful group scope of their own. No schema change
needed — `groupPath` is already a populated field on every `data.directory`-derived model (see
`model-api.md`'s default query-field notes). This is a bigger refactor than a one-line fix: both
`generateSDFigurines` and `generateSDImages` need the change, and callers that pass a bare `pop` list
without a reliably-populated `groupPath` (some general population-generation paths may only plan
common fields) need auditing first so the fallback path is actually exercised correctly rather than
silently landing back on the collision-prone default.
</details>

### KI-35. Olio-owned PictureBook apparel/wearables/qualities aren't writable by the acting user, so dress-up/down can't toggle `inuse` — FIXED ✅ (2026-08-10)

**The original diagnosis was right: it is the ownership.** I first claimed otherwise and was wrong —
see "How I got this wrong" below, because the mistake is instructive.

**Root cause.** `OlioContext.configureEnvironment` enrols the acting user in `~/Roles/Olio User` —
the role that carries the world-group grants — with
`ioContext.getMemberUtil().member(olioUser, userRole, config.getUser(), null, true)`. That call sat
**below the early return** taken whenever the Olio Admin role already exists:

```java
adminRole = findPath(olioUser, MODEL_ROLE, "~/Roles/Olio Admin", ...);
if(adminRole != null) {
    return;                                   // <-- everything below is skipped
}
initConfig = true;
...
member(olioUser, userRole, config.getUser(), null, true);   // acting-user enrolment
```

So the enrolment ran **once per organization, ever** — for whoever first initialised Olio there.
Every other acting user held no membership in the role and therefore none of the world-group grants.
`ApparelUtil.constructApparel`/`randomApparel` deliberately create apparel/wearables/qualities as
`ctx.getOlioUser()` (so colours resolve from the shared colour library needed for complementary-colour
computation), while the character and store belong to the acting user — so for any user who did not
initialise the world, dress-up/down was a write to another owner's record with no grant behind it.
PBAC refused it, `inuse` stayed permanently true ("always worn"), and `getWearing`/`describeOutfit`
kept reporting the full outfit after dressing down. That matches the reported observation exactly,
including that a user-owned wearable in the same outfit *did* respond.

**Fix.** Acting-user enrolment is not one-time provisioning, so it no longer lives behind the
one-time branch: `configureEnvironment` now resolves `userRole` and enrols `config.getUser()` on
**every** configure, before the early return. `enrole()` checks `isMember` first, so it is idempotent.
This also fixes a second consequence of the early return — `userRole` used to be left **null** on
every subsequent init, and only recovered later by chance in `configureWorldAuthorization`.

**Verified:** `TestKi35SecondActingUserCanToggleInuseOnOlioOwnedWearable` — a brand-new SECOND acting
user against an already-initialised world (the reporter's condition). It asserts the user is enrolled,
that the wearable really is owned by the Olio user, and that the patch flips `inuse` in the database.
**Proven to be a real regression test:** disabling just the enrolment line (production code only)
makes it fail on the membership assertion; restoring it makes it pass.

**Second, independent defect on the same path, also fixed (Ux):** the patch bodies omitted `name`.
`olio.wearable` inherits `olio.item` → `common.name` (`required` / `allowNull:false` / `$notEmpty`),
and the writer validates the **patch record itself**, not the merged result — so
`{schema, id, inuse}` is rejected with `Failed to modify record` even for a fully authorized user.
`olio.apparel` inherits `common.name` too, so `setApparelDescription`'s `{id, description}` patch was
failing the same way. `olio.js` now sends `{schema, id, objectId, name, inuse}` via a `wearablePatch`
helper, and neither result is swallowed — a rejected patch toasts an error and returns false.
Covered by `src/test/dressApparelPatch.test.js` (3 tests) and, backend-side, by
`TestKi35ActingUserCanToggleInuseOnOlioOwnedWearable`, which pins that the same patch minus `name` is
refused.

**How I got this wrong, recorded so the next reader doesn't repeat it.** I ran an "isolation" test —
fresh org, proposed grant disabled — saw `AUDIT PERMIT`, and concluded authorization was not involved.
The experiment was structurally incapable of showing the bug: in a fresh org the test user IS the
world's initialiser, which is precisely the one user the early return does enrol. A negative result
from a setup that cannot exhibit the condition is not evidence, and I reported it as if it were.

<details><summary>Original entry</summary>


PictureBook character apparel is built via `ApparelUtil.constructApparel(ctx, …)`/`randomApparel(ctx, …)`,
which (correctly, so colors resolve from the shared color library needed for complementary-color
computation) creates the `olio.apparel`/`olio.wearable`/`olio.quality` records owned by
`ctx.getOlioUser()` in the world groups (`/Olio/Universes/…/Worlds/…/{Apparel,Wearables,Qualities}`).
But the character/store are owned by the acting real user. So when the acting user runs dress-up/down
(`reimage.js` `dressApparel` → `page.patchObject` on `olio.wearable.inuse`), PBAC won't let them patch
another owner's wearable — the patch silently fails, `inuse` stays `true` permanently ("always worn"),
and `getWearing(person)`/`describeOutfit(person)` still reports the full outfit even after dressing down
(observed live: two same-level wearables, the picturebook one owned by the Olio user never responds to
dress-up/down, the user-owned one does).

**Fix direction (Stephen's chosen approach — "give the OlioUsers role read/write access to the group
they're in", scoped to apparel/wearables/qualities):** grant the **Olio User** role RW on those three
world groups. The machinery already exists in `OlioContext`: the role is `~/Roles/Olio User` (`userRole`,
`OlioContext.java:255`), the acting user is already a member of it
(`member(olioUser, userRole, config.getUser(), …)`, `OlioContext.java:258`), and grants use
`getAuthorizationUtil().setEntitlement(grantor, role, groups[], crudperms, [DATA, GROUP])` (same call at
`OlioContext.java:200-201/217/275`). Currently `userRole` only gets **Read** on the top-level
root/universe/world dirs (`:275`); it never gets **write** on the apparel/wearables/qualities groups.
Recommended: in `OlioContext` world-config, pre-create those three world groups (via their world
`FIELD_APPAREL_PATH`/`FIELD_WEARABLES_PATH`/`FIELD_QUALITIES_PATH`) and `setEntitlement(userRole/adminRole,
[those 3], crudperms, …)` once per world (nuance: the groups are created lazily on first apparel/wearable/
quality, so pre-create them at config time or entitle at creation in `ApparelUtil.constructApparel`).
Alternative considered and rejected: making the picturebook apparel user-owned/local — that pulls colors
local too and loses the shared-library complementary-color lookup, which Stephen wants kept. **No
`OlioUsers` literal role exists — it means `~/Roles/Olio User`.** Existing already-broken data will be
cleaned up by Stephen; the fix only needs to correct new-character creation.
</details>

### KI-36. `AuthorizationService.enableMember`/`AccessPoint.findByObjectId` throws a raw `NullPointerException` (500) on an unknown/undefined participant type instead of a clean 400 — FIXED ✅ (2026-08-10)

Two layers, as the entry suggested:
- **Objects7**: new `AccessPoint.isResolvableModel(String)`; `findByObjectId`/`findById`/`findByUrn`
  now treat an unresolvable model name exactly like a null one — INVALID audit + null return —
  instead of letting it reach `RecordUtil.getCommonFields`, whose `ms.getQuery()` dereferences the
  null `ModelSchema`.
- **Service7 (transport-level input validation, not business logic)**: `AuthorizationService`
  `badModelType(...)` returns **400 "Unknown model type '<x>'"**. Applied to all four affected
  endpoints, not just the reported one — `enableMember`, `countMembers`, `members`, and
  `membershipStats` (whose documented `any` wildcard for `participantType` is exempt). The path-param
  regex `[\.A-Za-z]+` happily admits any word, including the literal "undefined" the client sent.

**Verified:** `TestKi36UnresolvableModelTypeReturnsNullInsteadOfNpe` — "undefined", "null" and
"not.a.model" each return null (with an `AUDIT INVALID`) from all three by-identity lookups rather
than throwing, and a real model still resolves.

<details><summary>Original entry</summary>


When the member (participation) endpoint is called with a participant model type that doesn't resolve to
a schema (e.g. the literal string `"undefined"`), `AccessPoint.findByObjectId → AccessPoint.find →
RecordUtil.getCommonFields → RecordFactory.getSchema("undefined")` fails
(`ModelNotFoundException: Model undefined was not found` → `Failed to load models/undefinedModel.json`)
and the request surfaces as an uncaught `java.lang.NullPointerException` mapped to a generic 500, with a
long stack trace (`AuthorizationService.enableMember:67`). Found 2026-07-25 while a Ux client bug sent an
undefined participant type on wearable removal (the client bug — list-serialization schema loss — is
fixed separately in `views/object.js`; see the `am7model.updateListModel`/field-`baseModel` fallback).
**Fix direction (defensive, backend):** validate the participant/container type in
`AuthorizationService.enableMember` (and/or `AccessPoint.findByObjectId`) — if the model can't be resolved
(`RecordFactory.getSchema` would fail), return a clear **400 Bad Request** ("unknown model type '<x>'")
rather than letting the NPE propagate to the default exception mapper as a 500. Turns future "bad type"
client bugs into an actionable error instead of a stack trace.
</details>

### KI-37. `PictureBookUtil.callLlmInternal` hand-rolls prompt-template section composition instead of using the canonical `PromptTemplateComposer` — FIXED ✅ (2026-08-10)

The inline `sections` loop is gone; `callLlmInternal` now calls
`PromptTemplateComposer.composeSystem/composeUser`, the same path `Chat`/`ChatUtil`/
`InteractionExtractor` use. Role-less sections are no longer dropped, and `extends` inheritance,
per-section `condition`s, `sectionOrder`/`priority` and `${…}` token replacement all apply.

The classpath fallback was **narrowed** at the same time: it now fires only when NO template record
resolved at all. Backfilling one half from the classpath when a DB template supplied the other is
exactly the Frankenstein prompt this entry describes; if a resolved template composes to nothing for a
role, that is the template's own content and must not be patched from elsewhere.

The duplication this entry warns about is real and now in effect: a role-less section belongs to
**every** role, so its text appears in both messages. PictureBook DB templates should set section roles
explicitly where that isn't wanted. All seven shipped `promptTemplate.pictureBook.*` library templates
already use explicit `system`/`user` sections, so none are affected.

**Verified:** `TestKi37RoleLessTemplateSectionReachesBothHalves` — a DB template with an explicit
system section, an explicit user section, and a **role-less** section carrying an unfillable
`{ki37RoleLessMarker}` placeholder. Observed via `callLlmInternal`'s own pre-network
unsubstituted-placeholder guard, so no LLM call is needed: post-fix it logs
`Refusing to call LLM … (first: '{ki37RoleLessMarker}')`, proving the role-less section reached the
composed USER half. **Confirmed to be a real regression test:** temporarily reverting
`PromptTemplateComposer`'s role-less inclusion to the old drop-it behaviour (production code only,
one line) makes it fail exactly as predicted; restoring it makes it pass.
`TestKi37ClasspathFallbackStillResolvesRealPictureBookPrompts` guards the remaining legitimate
fallback for all six shipped prompts. `TestPromptTemplate` (15) and `TestPromptLibrary` (14) pass.

<details><summary>Original entry</summary>


`PictureBookUtil.callLlmInternal` (`AccountManagerObjects7/.../olio/picturebook/PictureBookUtil.java`
~line 1155) builds the system + user halves of every PictureBook LLM call. When a DB-stored
`olio.llm.promptTemplate` record resolves for the prompt name (i.e. a `promptTemplateOverride` /
user-customized template), it composes it with a small hand-rolled loop (~lines 1166-1180):
```java
for (BaseRecord sec : sections) {
    String role = sec.get("role");
    List<String> lines = sec.get("lines");
    if (lines == null) continue;
    String text = String.join("\n", lines);
    if ("system".equals(role)) sysBuf.append(text).append("\n");
    else if ("user".equals(role)) usrBuf.append(text).append("\n");
}
```
falling back to the flat classpath resource (`PromptResourceUtil.getString(name, "system"/"user")`,
~lines 1187-1188) for whichever half is still null.

**This is a divergent partial re-implementation of the canonical `PromptTemplateComposer`**
(`AccountManagerObjects7/.../olio/llm/PromptTemplateComposer.java`), which every *other* caller uses —
`Chat.java` (1209/1214/2600/4918), `ChatUtil` (958-959), `InteractionExtractor` (100) — via
`composeSystem()`/`composeUser()`. The composer resolves `extends` inheritance, evaluates per-section
`condition`s, orders by `sectionOrder`/`priority`, joins lines, and runs `${…}` token replacement
through `PromptUtil`. The inline loop does **none** of that.

**Actual defects (note: the reported "duplicate the content" symptom is, on inspection, the opposite —
this loop *drops* content):**
- Each section lands in exactly one buffer (`sysBuf` **xor** `usrBuf`), so a template with both a
  system and a user section produces two distinct messages — no literal duplication in this loop.
- Per `promptSectionModel.json`, a section's `role` is **optional**: "If empty, inherits from parent
  template" (whose `role` defaults to `"system"` — `promptTemplateModel.json`). The loop only matches
  the literal strings `"system"`/`"user"`, so any **role-less section is silently dropped** — its
  content never reaches the LLM.
- The classpath fallback only fires when a half is `null`, so a DB template that supplies *only* system
  sections (or only user) gets the **other half silently backfilled from the classpath resource** — a
  mismatched, incoherent prompt.
- Ignores `extends`, `condition`, `sectionOrder`, `priority`, and `${…}` tokens entirely.

**Where the duplication concern is genuinely real:** the *correct* composer treats a role-less section
as belonging to **every** role — `compose(role)` includes a section when its role is empty OR equals
the target (`PromptTemplateComposer.java:70-74`). So once this call site is (correctly) switched to
call both `composeSystem` and `composeUser`, a shared/role-less section's text will appear in **both**
the system and user messages. That is the duplication to watch, and a reason to make section roles
explicit in any PictureBook DB templates.

**Fix direction (not implemented):** replace the inline `sections` loop in `callLlmInternal` with the
canonical composer, e.g.
```java
BaseRecord pt = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_PROMPT_TEMPLATE, promptName, null);
if (pt != null) {
    system  = PromptTemplateComposer.composeSystem(pt, null, chatConfig);
    userTpl = PromptTemplateComposer.composeUser(pt, null, chatConfig);
}
```
This fixes the dropped-role-less-sections bug, respects inheritance/conditions/ordering/token
replacement, and makes PictureBook use the same tested path as the rest of the chat pipeline. Cover
with a test alongside the existing `TestPromptTemplate` (which already exercises
`composeSystem`/`composeUser`), and verify a real PictureBook prompt (e.g. `pictureBook.landscape-prompt`)
still resolves correctly against the live backend before closing. Core Objects7 LLM path — route through
backend-specialist per `troubleshooting.md`.
</details>

### KI-38. PictureBook image generation forked a custom style system instead of the canonical `olio.sd.config` — conflicting/garbage prompts — FIXED ✅ (2026-08-03)

PictureBook had built a **parallel** image-style system: `PictureBookUtil.ALLOWED_STYLES` (a 12-word
superset incl. the non-canonical `illustration`), a single-word `SceneGenerationParams.style`, style
injected via `SWUtil.styleClause(...)` ("High quality photograph"), a book-level `compositionContext`
art-direction anchor, and a throwaway `olio.sd.config` built per call that set only the bare `style`
word and **never populated the per-style detail fields** (photographer/stillCamera/fashionMagazine/…),
so `SDUtil.getSDConfigPrompt` couldn't be used and was bypassed. Result (observed live): portraits went
through the canonical config path and got a rich style ("Fashion photography for V Magazine … by Paul
Strand") while the scene/landscape got the `styleClause` short phrase — **two style systems in one
composite**, style drift (photograph↔anime), and no consistent composition. Images looked wrong.

**Fix (Stephen's direction: "use a common sd config with per-scene overrides, like CardGame"):** one
common `olio.sd.config` (style **+ detail fields** via the random-style generator) is now the SINGLE
style seam — `SDUtil.getSDConfigPrompt` — across portraits, landscape, classic scene, and Kontext
composite, with per-scene overrides merged on top (CardGame pattern). Specifics:
- `SDUtil` — added `applyOverrides(base, sparseOverride)` (overlays only fields present on a delta) and
  `fillStyleDefaults(cfg)` (completes missing per-style detail fields from the sdConfigData pools);
  `randomSDConfig()` refactored to reuse `fillStyleDefaults` (behavior preserved — see KI-39 test note).
- `SWUtil.newKontextSceneTxt2Img` — added an **additive** trailing `boolean useConfigStyle` overload
  (config style via `getSDConfigPrompt`, SDXL-weighting-stripped for FLUX); the legacy 10-arg overload
  delegates with `false`, so `ChatService.generateScene` stays byte-identical.
- `PictureBookUtil` — deleted `ALLOWED_STYLES`/`appendStyleClauseOnce`/`SceneGenerationParams.style`;
  `SceneGenerationParams` now carries `BaseRecord sdConfig`/`compositeSdConfig`/`sdConfigOverride`;
  `generateSceneImage` resolves common (request→book meta→`randomSDConfig`)+`fillStyleDefaults`+optional
  override; portraits/landscape/scene/Kontext all derive style from that one config; `compositionContext`
  now defaults blank (kept as an optional prompt anchor, no longer auto-seeded).
- Models — `pictureBookRequest`/`pictureBookMeta` gained `compositeSdConfig` (+ `sdConfigOverride` on the
  request); `compositionContext` documented as optional/blank-default.
- Service7 — `/scene/{id}/generate` and `/{bookObjectId}/prepare-images` now parse full `olio.sd.config`
  records (no flattened `style` string); new `PUT /{bookObjectId}/settings` stores the book's common
  (+composite) config once.
- Ux752 — `workflows/pictureBook.js`/`sceneExtractor.js`/`components/SdConfigPanel.js` now use a real
  `olio.sd.config` (`am7sd.buildEntity`/`prepareInstance`) + per-scene `olio.sd.config` override deltas
  via `forms.sdConfigOverrides`; dropped the bespoke `defaultSdConfig()`/`DEFAULT_SD_CONFIG` object and
  the picture-book-only `illustration` style word; canonical default style `digitalArt`.

**Verified:** seam unit test `TestPictureBookFull#TestSdConfigStyleSeamAndOverrideMerge` (one config →
rich detail-driven style, sparse-override isolation, no `styleClause`); `TestSDStyles` randomSDConfig
all-10-styles + `getSDConfigPrompt` all-11-styles; `TestKontext` 7 prompt-level tests (legacy path
byte-identical); `TestSD` live (real population portrait + 6 apparel mannequins, installed model);
one live picturebook scene image (real, single config-driven style suffix `((Street art …))`, no
`styleClause`); Ux `vite build` + `vitest` (337); Docker image builds and the full stack deploys on
`:8443`. Design/implementation record: `aiDocs/PictureBookSdConfigRefactor.md`.

**Follow-ups (non-blocking):** backend `configModel.json` still lists `illustration`/`custom` in
`style.limit` though `getSDConfigPrompt` has no `illustration` branch (client dropped it — harmless
dead entry, remove for tidiness); the picture-book default style `digitalArt` was a judgment call
(easily changed); live FLUX-Kontext scene generation for PictureBook not yet visually verified end to
end; the full catatone first-two-scenes real-content regression is still pending.

### KI-39 + KI-48. Live SD tests that cannot reach their backend must skip VISIBLY, never pass — FIXED ✅ (2026-08-10)

New shared helper `AccountManagerObjects7/src/test/.../SdTestGate.java`:
- `requireSwarmConfigured(server)` — visible `Assume` skip when no server is configured;
- `requireModelInstalled(sdu, server, model, what)` — **gates UP FRONT, before any generation**;
- `emptyResultIsSkipOrFailure(...)` — an empty result is classified, never ignored: absent checkpoint ⇒
  visible Skip, present checkpoint ⇒ hard `fail(...)`;
- `insufficientPreparation(what, got, needed)` — staging that fell short skips visibly;
- `stampInstalledModel(cfg, props)` — stamps `test.swarm.model`/`refinerModel` onto a
  `randomSDConfig()` used for a live call, so it isn't sent with the per-deployment schema default.

Applied to `TestKontext` (the two remaining `logger.warn(); return;` bail-outs at the portrait and
landscape stages, the unconfigured-server bail-out, and `ensurePortrait`'s bare `randomSDConfig()`)
and to `TestLandscape`'s three unconfigured-server returns.

**Up-front gating matters, and Stephen caught it mid-session**: classifying only *after* the fact still
fires a request Swarm refuses —
`[Warning] Refused to generate image for local: Invalid model value for param Model -
'flux1Kontext_flux1KontextDev'` — and pays for the portraits and landscape first.
`testKontextSceneWithOlioCharacters` now resolves the Kontext checkpoint from a new
`test.swarm.kontextModel` property (falling back to the schema default) and skips before generating
anything.

**Verified:** `mvn -o -pl AccountManagerObjects7 -Dtest=TestKontext#testKontextSceneWithOlioCharacters
test` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1` in 3.1s, logging
`Checkpoint 'flux1Kontext_flux1KontextDev' installed on http://localhost:7801: false`. No request
reaches Swarm's generate API, and the result is reported as **Skipped**, not Passed.

**Remaining sweep, classified not fixed:** `TestPictureBookCustom`'s `exportImage`/`generatePortrait`
`warn+return`s are in export helpers of a deliberately manual, visual-inspection harness — they do not
turn a generation assertion green. Left as-is on purpose rather than converted.

<details><summary>Original KI-39 entry</summary>


Any live SD test that builds its config with `SDUtil.randomSDConfig()` **without overriding `model`**
sends the schema-default `sdXL_v10VAEFix.safetensors`, which is **not installed** on Stephen's SwarmUI
(`[Warning] Refused to generate image … Invalid model value for param Model 'sdXL_v10VAEFix.safetensors'`).
The generation returns empty, and such tests then **silently skip and report success** rather than
failing. Confirmed in `TestKontext#testKontextSceneWithOlioCharacters` (`TestKontext.java:322-325` uses
`randomSDConfig()` for the portrait step; `:395-398` `if (ready.size() < 2) { logger.warn(...); return; }`
returns green without generating). So its JUnit "pass" verifies **nothing** about live FLUX-Kontext —
a fake pass. (Contrast `TestSD` / the migrated picturebook tests, which DO set `model` from
`test.swarm.model` and genuinely generate.) This bit during the KI-38 regression: a JUnit-green result
was reported as a live pass when the SD call had actually been refused.

**Fix direction:** (1) in the affected live tests, build the SD config with `model`/`refinerModel` from
`test.swarm.*` (as `TestSD`/`TestPictureBookFull` already do) so the call actually runs; (2) replace the
silent `return` on insufficient prep / empty SD result with a hard `fail(...)` (or `assumeTrue`-style
explicit skip that is visibly reported), so a refused/empty generation can never masquerade as a pass —
per `.claude/rules/llm-conduct.md` "no fake tests". Consider a shared test helper that stamps the
installed model onto any `randomSDConfig()` used for a live call. Also see KI-38's note re: removing the
uninstalled `sdXL_v10VAEFix.safetensors` default (or making the schema default a model that ships).
</details>

### KI-40. Chat scene generation should adopt the newer PictureBook approach — turn a chat into its own dynamically-growing picturebook — OPEN (2026-08-04, Stephen)

The picture-book pipeline (`PictureBookUtil`) is now the mature, config-driven approach for scene
illustration: one common `olio.sd.config` drives portraits + landscape + composite; the art style is
applied exactly once via `SDUtil.getSDConfigPrompt` and stays **globally consistent** (with a
per-character local override for a single character's portrait — e.g. one character rendered as a
cartoon while everything else is a photograph); and scene-image prompts are assembled from
**style- and setting-free** per-character narrations (appearance + outfit only) plus the scene's own
setting/action/mood. Chat scene generation still runs on the older/divergent image path and does not
share this machinery.

**Goal:** update chat scene generation to use the newer/updated PictureBook approach — effectively
**turn a chat into its own dynamically-growing picturebook**. As the conversation advances, scenes are
extracted/created and illustrated incrementally into a per-chat book (append new scenes over time
rather than regenerating a single one-off image), reusing the picture-book scene/character/portrait/
landscape/composite pipeline and the book's common `olio.sd.config` for global style consistency.

**Fix direction:** locate the current chat image-generation path, converge it onto `PictureBookUtil`'s
scene/portrait/landscape/composite stages, and model the chat as a book whose `scenes` list grows as
the chat progresses. Reuse the common-config style seam and the per-character override mechanism rather
than forking a parallel image path (the same anti-pattern KI-38 fixed). Scope/sequencing TBD — backlog
placeholder, not a plan.

### KI-41. Create-or-get library utils produce admin-privileged side effects on read paths — RULE ADDED ✅ (2026-08-10)

`.claude/rules/architecture.md` gained a "Read paths must not create, and never as the org admin"
section: the shape of the trap, the rule ("split create-or-get utils into a find-only read path and an
authorized write path"), and both adjacent findings (permissions configured only on the create branch;
the grant-before-create ordering bug in `ChatLibraryUtil`). **`LibraryUtil` itself is deliberately
untouched** — every existing caller passes `enableCRU=true`, and this entry says to raise that with
Stephen first.

<details><summary>Original entry</summary>

Surfaced during the Ux feature-flag work (`aiDocs/UxFeatureFlagDesign.md` D1) and **deferred
deliberately, not dropped** — the local case is handled there, but the general trap is uncovered by
any rules file, and this is the **second** time it has been hit.

**The shape.** `LibraryUtil.getCreateSharedLibrary` is create-or-get, and its create branch runs as the
**org admin**: `makePath(octx.getAdminUser(), ...)` (`LibraryUtil.java:43`), then a raw
`ctx.getRecordUtil().createRecord(...)` PBAC bypass (`:45`), then role permission grants
(`:49` → `:100-108`). Calling it from a read path therefore means **any non-authorized caller can
trigger admin-privileged group creation and permission grants as a side effect of a read.** The
feature-config design avoided it by splitting read (`findPath` only, absent ⇒ defaults) from write
(`getCreateSharedLibrary`), but nothing prevents the next caller from repeating the mistake.

**Two adjacent findings from the same review, recorded so they aren't re-derived:**
- Permissions are configured **only on the create path** — `getCreateSharedGroup` returns early when the
  group already exists (`LibraryUtil.java:40-42`), *before* `configureLibraryPermissions` (`:49`). So an
  `enableCRU=false` call is an assumption about history, not an enforced invariant, and nothing here can
  repair or downgrade an already over-granted group.
- **Ordering bug, latent in existing callers:** `configureLibraryRootPermissions` bails at
  `LibraryUtil.java:90-94` when `/Library` does not exist yet. Callers that invoke
  `configureLibraryRootReader` *before* `getCreateSharedLibrary` (e.g. `ChatLibraryUtil.java:47-48`)
  silently no-op the root grant on the first write in a fresh org. Grant calls must follow group
  creation; they are idempotent via `MemberUtil.member(..., true)`, so ordering them after is safe.

**Fix direction:** propose a one-line `architecture.md` bullet — "read paths must not create, and never
as the org admin; split create-or-get utils into a find-only read path and an authorized write path" —
and consider making the `LibraryUtil` grant behaviour honest (either configure permissions on the
get branch too, or rename the method so its create-only ACL semantics are visible at the call site).
Not scheduled; raise with Stephen before touching `LibraryUtil`, since every existing caller passes
`enableCRU=true` and would be affected.
</details>

## PictureBook / SD — carried forward from the 2026-08-07..09 SD session

> **2026-08-10 pass.** Fixed and verified below: KI-34, KI-35, KI-36, KI-37, KI-39+KI-48, KI-42,
> KI-43, KI-43a, KI-46, and KI-50–KI-58 (defects found during the pass, several reported live by
> Stephen mid-session).
>
> **Still OPEN:** KI-27, **KI-34 (reopened — the fix was reverted, see KI-61)**, KI-40, KI-44,
> KI-45, KI-47, KI-49, **KI-59**, KI-62, **KI-63 (storage location — Stephen's per-book-world idea
> would dissolve KI-34/KI-61/KI-32 together; read it before attempting any of them individually)**,
> and **KI-60 (HIGH — narrative
> creation uses a hand-rolled path instead of `NarrativeUtil.getCreateNarrative`; the KI-42 recovery
> adopts the WRONG group. Stephen is investigating this one — do not start a competing fix.)** (no exported image shows the
> landscape integrated into a composite — recorded, not investigated). Each entry states exactly what
> was and was not done.
>
> Two corrections worth reading before trusting an older entry: **KI-35's PBAC diagnosis was right and
> my first "disproof" of it was wrong** (the isolation test could not exhibit the condition), and
> **KI-42's stated cause was wrong** (the repeated get-or-create is not sufficient on its own).

> **The items below were originally all OPEN and belong to PictureBook.** They were diagnosed (some only partially)
> during a long SD/FLUX.2 session and deliberately NOT fixed, either because the fix was unproven or
> because the session ran out of room to verify it. Fix these in a fresh conversation. Everything
> stated as "measured" was measured; everything stated as a hypothesis is unproven — do not promote a
> hypothesis to a cause without evidence, which is how time was lost in the original session.

### KI-42. `Narratives` group duplicate-key aborts character extraction — PARTIALLY FIXED ⚠ (2026-08-10)

> **See KI-60 first.** The character is no longer lost, but the duplicate-key INSERT still fires on
> every run (the lookup still misses a row that exists — cause still unknown), and the recovery path
> added here was observed adopting the WRONG group (#151 `Apparel` instead of #1049 `Narratives`).
> Do not treat this as closed.

**The mechanism, reproduced.** The KnownIssues entry's own decisive question ("does the run duplicate
only `Narratives`, or every foreign-model group?") turned out to have a third answer: **neither — the
repeated get-or-create is not sufficient on its own.**
`TestPictureBookKnownIssues#TestKi42ForeignSubModelGroupsResolveOncePerRequest` drives
`createPersistedForeignInstance`'s exact two lines for all seven sub-models, three passes, as a user
whose home contains none of those groups — and it passes clean. Sequential get-or-create is fine, so
the batching/visibility hypothesis in the original entry is **wrong**.

What actually happens needs the lookup to MISS a row that exists. The unique constraint is
`(name, parentId, organizationId)` and does **not** include `type`, while `PathUtil.makePath`'s lookup
filters **by type** — so a `Narratives` row of any other type is invisible to a `DATA` lookup and the
insert then collides with it. Reproduced deterministically in
`TestKi42MakePathNeverReturnsAGroupThatIsNotInTheDatabase`, producing the reported log verbatim:
`BatchUpdateException: Batch entry 0 INSERT INTO A7_auth_group_0_1 (… 'Narratives' …) was aborted:
ERROR: duplicate key value violates unique constraint "a7_auth_group_0_1_ne_pd_od_1_idx"`.

**The defect that turns a lost race into lost data** is what `makePath` does next (this is about the
`Narratives` group insert in KI-42 only — it is unrelated to KI-35's wearable ownership):
it ignores `writer.write(node)`'s return value. `DBWriter` catches the `SQLException`, logs it and
returns 0 — but it has **already** stamped a sequence-allocated id onto the in-memory record. `makePath`
reads that id back and returns an ordinary-looking `auth.group` whose id matches **no row**. Everything
persisted against it then fails PBAC with `Group could not be found: <id>`, which is why the reported
id differed every run (3470, then 3490) while the collision key did not: each run burns a fresh
sequence value on an insert that never lands. Confirmed live — the test observed `makePath` returning
phantom group id 364.

**Fix, two parts.**
1. `PathUtil.makePath` now checks the write result. On a lost write it re-reads on the constraint's own
   key (name + parent + org, **no type filter**) and adopts the existing row — a get-or-create must be
   robust to its create losing — and returns **null** if nothing is there, so a caller sees a real
   failure instead of a phantom. New `PathUtil.findExistingNode` helper. Not a PBAC/PolicyUtil change,
   per Stephen's instruction.
2. `PictureBookUtil.prepareForeignSubModelGroups(user)` resolves each of the seven sub-model groups
   **once per request**, before the character loop, instead of 13 × N times — KI-42's own stated fix
   direction, which now collapses the window the race runs in rather than being the whole fix.

**Verified:** `TestKi42MakePathNeverReturnsAGroupThatIsNotInTheDatabase` fails pre-fix (phantom id 364,
no such row) and passes post-fix, logging `Write of auth.group node Narratives in parent #365 lost to
an existing record (#366); adopting it`. End-to-end,
`TestKi42CreateFromScenesCreatesEveryCharacterForANewUser` runs a real 3-character `createFromScenes`
against live Ollama + Postgres for a brand-new user: 3/3 characters created, `failedCharacters` empty,
zero duplicate-key log lines, exactly one group per sub-model.

**Also fixed, separately (the latent defect this entry flagged):** `PolicyUtil.getResourcePolicy`'s
"Group could not be found" branch called `resource.copyRecord(new String[]{… FIELD_URN …})
.toFullString()`. `copyRecord` returns **null** when the model lacks a requested field, so for anything
inheriting `common.baseLight` (no `urn`) — `olio.narrative`, `olio.wearable` — the DIAGNOSTIC ITSELF
threw an NPE out of `AuthorizationUtil.canUpdate`, masking the real condition. It hit during KI-35
testing, so it is no longer merely latent. Replaced with `describeUnresolvedResource(...)`, which
requests only fields the model actually has and never lets logging throw. Authorization behaviour is
unchanged; the denial is now reported as a clean `AUDIT DENY`.

<details><summary>Original entry (diagnosis superseded above)</summary>


`/create-from-scenes` fails to create characters. Reproduced twice on org 3 (user `steve`), at 16:03
and 18:46 on 2026-08-09:

```
DBWriter - java.sql.BatchUpdateException: Batch entry 0
  INSERT INTO A7_auth_group_0_1 (... name='Narratives', parentId=31, organizationId=3 ...)
  was aborted: ERROR: duplicate key value violates unique constraint "a7_auth_group_0_1_ne_pd_od_1_idx"
  Detail: Key (name, parentid, organizationid)=(Narratives, 31, 3) already exists.
PolicyUtil - Group could not be found / Resolve resource by groupId: 3490
BaseRecord - copyRecord: newInstance: Field urn was not found on model olio.narrative
PictureBookUtil - createCharPerson failed for 'Jideon de Rosa' — character will be absent from the book
```

**Facts established.** The new group id differs per run (3470, then 3490) but the collision key
`(Narratives, 31, 3)` is identical — so it reproduces every run and the pre-existing row is stable.
This is NOT leftover state from one bad run. `Factory.java:80` already uses `PathUtil.makePath`
(get-or-create), and `makePath` is `synchronized`, so an in-JVM race is ruled out. The failure is a
**batched** write (`BatchUpdateException: Batch entry 0`). `PictureBookUtil.createPersistedForeignInstance`
has **13 call sites**, each independently resolving `"~/" + schema.getGroup()`, so a single request
get-or-creates the same group path many times across characters.

**Hypothesis, UNPROVEN:** a `makePath` insert sits in an unflushed batch, so the next `makePath`'s
lookup cannot see it, decides to create, and both inserts land in the same batch. `synchronized` does
not help, because the problem is write visibility rather than concurrency.

**The one datum that settles it:** does the same run also duplicate the OTHER foreign-model groups
(Profiles, Statistics, Instincts), or only `Narratives`? All of them ⇒ the repeated get-or-create
pattern above. Only `Narratives` ⇒ the hypothesis is wrong and something is specific to that model.
Get this fact before writing any code.

**Fix direction (in PictureBookUtil, NOT in PBAC):** resolve each foreign-model group once per request,
before the character loop, instead of 13 × N times.

**Do NOT "fix" this in `PolicyUtil`.** That was attempted in the original session and reverted at
Stephen's instruction. The `copyRecord`/`urn` NPE in `PolicyUtil.getResourcePolicy` — its error branch
requests `FIELD_URN`, and `olio.narrative` inherits `common.baseLight` which has none, so `copyRecord`
returns null and the logger itself NPEs — is a real latent core defect that merely *masks* this one. It
became reachable only because `createPersistedForeignInstance` now persists these nested models through
`AccessPoint` at all; previously they were in-memory placeholders that were never persisted. Raise it
separately with Stephen rather than bundling it into this fix.
</details>

### KI-43. Mannequin denoise slider in `reimageApparel.js` is disconnected — FIXED ✅ (2026-08-10)

Implemented exactly the four steps this entry specified, all in the Ux:
1. `mannequinCreativity` added to `core/modelDef.js` (double, 0.0–1.0, **no declared default**);
2. added to `forms.sdMannequinConfig` in `core/formDef.js` with `format: 'range'`, so the instance gets
   the 0-100 UI ↔ 0-1 wire decorator;
3. the slider in `reimageApparel.js` now writes through `cinst.api.mannequinCreativity(...)` (writing
   `e.target.value` onto `cinst.entity` bypasses the decorator) and is relabelled "Mannequin
   Creativity" — the dialog is mannequin-only, and `denoisingStrength` is dead there: `SDUtil`'s only
   reader of it is `applyImg2Img`, which requires a `referenceImageId` this dialog never sets;
4. `src/test/reimageApparelConfig.test.js` updated — the contract moved with the code.

One detail the entry didn't anticipate: `am7model.newPrimitive` materializes an undeclared double as
**0**, not undefined, so an untouched slider sends `0`. That is already the server's "unset" sentinel
(`SDUtil.generateMannequinImages`: `cfgDenoise != null && cfgDenoise > 0`), so 0 on the wire correctly
means MANNEQUIN_INIT_IMAGE_CREATIVITY (0.85). The slider therefore **displays 85** while unset rather
than a misleading 0. Accepted consequence: an explicit 0 is indistinguishable from unset, and 0
creativity means "don't clothe the mannequin at all".

**Verified:** `npx vitest run src/test/reimageApparelConfig.test.js` — 8/8. Covers the decorator
conversion (60 → 0.6 on the entity, 60 back out), that a real user drag on the rendered slider reaches
the POST body as `mannequinCreativity: 0.8`, that `mannequinCreativity` has no declared default while
`denoisingStrength` still carries its never-null 0.75, and that an untouched slider shows 85 and sends
the 0 sentinel. (Found and fixed while writing it: `fetchTemplate` was `mockResolvedValue`, handing
every test the same entity, so one test's drag leaked into the next.)

### KI-43a. Mannequin prompt carried no gender — FIXED ✅ (2026-08-10, Stephen, live)

Reported mid-session from a live generation: `8k … of a ((full body retail mannequin)) displaying:
(((Medium Spring Bud Bengaline silk t-shirt, Sinopia Gauze underwear)))…` — no gender anywhere.
`SDUtil.generateMannequinImages` already reads `apparel.gender` to pick the male/female base asset, but
`NarrativeUtil.getMannequinPrompt` never put it in the text, so the prompt and the init image disagreed
about the body being rendered. `getMannequinPrompt` now resolves gender the same way
`getMannequinBaseImage` does ("male" → male, everything else → female, so the two cannot diverge) and
injects it into both the opening clause and the "one single … mannequin" clause.

`SDUtil.generateMannequinImages` reads `mannequinCreativity`; `reimageApparel.js`'s Denoising slider
writes `denoisingStrength`. Nothing sets `mannequinCreativity`, so the slider looks live, does nothing,
and every mannequin renders at the hardcoded `MANNEQUIN_INIT_IMAGE_CREATIVITY` (0.85).

The dedicated field exists for a real reason: `denoisingStrength` carries a `0.75` schema default that
is never null, so the intended value could never apply — confirmed live, a generation logged
"Init Image Creativity: 0.75" when 0.6 was intended.

**The fix is entirely in the Ux. Do NOT add a server-side fallback to `denoisingStrength`** — that was
attempted and reverted. Stephen: *"it shouldn't need a server fix! It's in the Ux - the slider either
uses the instance ability to adapt or it doesn't"*. Required:

1. add `mannequinCreativity` to `Ux752/src/core/modelDef.js` (double, 0.0–1.0, **no default**);
2. add it to `forms.sdMannequinConfig` in `core/formDef.js` with `format: 'range'`, so `inst.api.…`
   performs the 0-100 ↔ 0-1 conversion. Per `SdConfigPanel.js:149-155`, the `range` decorator does that
   scaling; reading `e.target.value` straight onto `cinst.entity` bypasses it;
3. bind the slider through `cinst.api.mannequinCreativity(...)`;
4. **update `src/test/reimageApparelConfig.test.js`** — it currently asserts the `denoisingStrength`
   0-100 → 0-1 conversion (KI-29 lineage). Move the contract and its test together; a failure there
   means the assertion needs updating, not that the change is wrong. Attempting step 3 without step 4
   is what stalled this in the original session.

### KI-44. Chat scene generation convergence — Stage A done, B and C outstanding — STILL OPEN (re-confirmed 2026-08-10, deliberately not attempted)

**2026-08-10: not done, and deliberately so.** Stages B and C are the same work as KI-45 — extracting
the landscape stage and the ~190-line portrait stage out of a 534-line method — and this entry states
the check is "a live picture-book run", which this session could not complete end to end (no live
picture-book run was performed; see KI-47). Shipping a ~350-line restructuring of the scene pipeline
with no live verification is exactly how the 8 documented Ux75 regressions happened. The bug fixes in
this pass were kept separate from it on purpose. Stage A remains built-and-unexercised as described
below.

Continuation of KI-40. Stage A is complete and builds (`vite build` + 432 vitest passing) but has
**never been exercised against a live chat scene**. `Ux752/src/chat/SceneGenerator.js` now builds a real
`olio.sd.config` via `am7sd.buildEntity()` — the same helper `pictureBook.js` uses — instead of a
hand-rolled flat object, overlays saved tweaks on top of it while skipping blanks, and never persists or
restores `model`/`refinerModel`. That hand-rolled object was the cause of `Invalid model value for param
Model - 'OfficialStableDiffusion/sd_xl_base_1.0'` on a node lacking that checkpoint: `model: ""` was
sent verbatim and the server fell through to a necessarily node-specific schema default.

Still outstanding, and these are the actual convergence:

- **Stage B:** extract the landscape stage — currently duplicated between `ChatService.generateScene`
  (inline) and `PictureBookUtil.generateSceneImage` Stage 2.
- **Stage C:** extract Stage 1 portraits (~190 lines) behind a result holder.
- Verify Stage A live — needs Tomcat or the Docker stack plus a chat session with two characters.

Already shared: the composite request (`SceneCompositeUtil.buildSceneRequest`) and mode resolution.
Deliberately NOT shared: portrait acquisition and prompt composition — a chat and a book legitimately
differ in where characters and setting come from, and forcing those together is how
`generateSceneImage` reached 534 lines.

### KI-45. `PictureBookUtil` cyclomatic complexity — STILL OPEN (re-confirmed 2026-08-10, deliberately not attempted)

**2026-08-10: not done** — same reasoning as KI-44, with which it shares the work. This entry says it
itself: "Pure restructuring, so existing unit tests prove little — the real check is a live
picture-book run." No live picture-book run happened this session, so the restructuring has no way to
be verified and was not started. Note the line counts have moved slightly with this pass's fixes
(`generateSceneImage` is unchanged in structure; `createCharPerson` gained the KI-42
group-pre-resolution call in `createFromScenes`, not in `createCharPerson` itself).

Measured line counts: `generateSceneImage` **534**, `createCharPerson` **463**, `createFromScenes` 206,
`callLlmInternal` 165, `resolveSceneCharacter` 149.

`generateSceneImage` already has stage seams marked in comments, so the extraction is mechanical:
Stage 1 portraits (~190 lines) → `generateScenePortraits`, Stage 3/4 composite (~158) →
`renderSceneComposite`, Stage 2 landscape (~27) → `generateSceneLandscape`. That leaves roughly 120
lines of orchestration, and it is the SAME work as KI-44's Stages B and C — do them together.
`createCharPerson` needs its own read; its seams were never examined.

Pure restructuring, so existing unit tests prove little — the real check is a live picture-book run.

### KI-46. `am7model.newPrimitive('olio.sdConfig')` uses a non-existent model name — FIXED ✅ (2026-08-10)

Both call sites (`reimage.js`, `reimageApparel.js`) now use `olio.sd.config`. One line each. The
fallback only fires when `am7sd.fetchTemplate()` returns null (server unreachable), which is precisely
when it mattered: `newPrimitive` returned null and `prepareInstance(null, form)` then threw.

### KI-47. Unverified changes from the SD session — need a live look before being trusted — STILL OPEN (2026-08-10)

**2026-08-10: not closed.** The Docker stack was brought up and REST-verified this session (which
required fixing KI-52 first), but no live image was generated through the real UI or pipeline, so none
of the four items below has had the visual check they need. What DID change underneath them, and must
be re-checked together with them:
- `SDUtil.createImage` no longer dies with a `ClassCastException` before requesting an image (KI-51) —
  the portrait path in the third bullet below could not have been visually verified before this;
- the mannequin prompt now carries gender (KI-43a), which changes the first bullet's output;
- `TestKontext` now skips visibly instead of masking a refused checkpoint (KI-39/KI-48), so the second
  bullet's "TestSD was never run" gap is now honestly reported rather than hidden.

Original list, all still needing a live look:

- **Mannequin output moved 512×768 → 1024×1024**, with `mannequinUseBaseImage` defaulting ON and
  `mannequinSteps` 60. Measured good in a standalone harness (`TestMannequinBaseLive`, images
  inspected) — never run through `reimageApparel`. The 512 size was the cause of flat catalog-grid
  output: SDXL degrades badly below its native 1024.
- **`SWTxt2Img` refiner fields are now nullable + `@JsonInclude(NON_NULL)`** so the refiner block is
  omitted unless configured. This touches EVERY SD path, not just FLUX.2. `TestSD`'s KI-29 test (which
  asserts refiner values reach the live request) plus `TestKontext` are the right coverage; `TestSD`
  was never run.
- **`PictureBookUtil`'s flux2 branch now routes through `SceneCompositeUtil`.** No test covers
  `PictureBookUtil` actually calling the shared builder.
- **The FLUX.2 composite prompt was restructured** — the config style clause moved to the position the
  guidance doc specifies (before the identity demands), to address a cartoonish composite while the
  portraits and landscape were photorealistic. Prompt-shape tests pass; the visual result was never
  checked.

### KI-48. `TestKontext` fake-pass fixed; audit the rest of the suite for the same pattern — FIXED ✅ (2026-08-10, folded into KI-39 above)

<details><summary>Original entry</summary>

`TestKontext.testKontextSceneWithOlioCharacters` reported green while Swarm refused every request
(`Invalid model value for param Model - 'flux1Kontext_flux1KontextDev'` — that checkpoint is not
installed on the local node). It now queries the Swarm model list and reports **Skipped** with the
reason, or **fails** when the checkpoint IS installed and generation still returned nothing.

Two other `logger.warn(...); return;` bail-outs remain in that class (portrait failure, landscape
failure), and the same KI-39 pattern very likely exists elsewhere in the SD/LLM tests. Worth a sweep:
a live test that cannot reach its backend must skip visibly, never pass.
</details>

### KI-49. `Content-Length header of network response exceeds response Body` at the end of scene extraction — OPEN (2026-08-09, Stephen)

Reported from the Ux on conclusion of scene extraction (the picture-book Step 2 /extract-scenes-only
path). This is a **browser-side** network error: the client received fewer body bytes than the
`Content-Length` header promised, so the response is rejected after the headers were already sent.

**Not yet diagnosed — the notes below are candidate causes, not findings.** Do not treat any of them as
the answer without evidence.

1. **Truncated response.** An exception thrown *after* the response headers/length were committed
   leaves the body short. Scene extraction is long-running and chunked
   (`extractChunkedInternal`), and a failure late in the loop is exactly this shape. Check the server
   log for a stack trace at the same timestamp as the client error — this is the first thing to look at,
   and the cheapest.
2. **Byte-vs-character length mismatch.** If any layer sets `Content-Length` from a Java `String`
   length while the body is written as UTF-8, multibyte characters break the count. Scene payloads
   reliably carry non-ASCII from the LLM — `Duña`, U+2011 non-breaking hyphens, em dashes (see the
   SD-typography work in this same session). NOTE the direction of the reported error though: header
   **exceeds** body means the promised length is LARGER than what arrived, whereas a char-count/UTF-8
   mix-up would normally make it SMALLER. So this does not fit cleanly and may be a red herring.
3. **A filter or the container re-encoding the body after the length was set.** `TokenFilter`,
   `CorsFilter` and `ExpiresFilter` all wrap the response chain (see `web.xml`), as does gzip if
   enabled anywhere. Any of them altering the body after `setContentLength` produces this exactly.
4. **Timeout/abort mid-transfer.** Worth ruling out given `http.read.timeout` work elsewhere in this
   session, though that governs the server's OUTBOUND calls, not the response to the browser.

**How to narrow it quickly.** Capture the failing response in browser DevTools (Network → the
`extract-scenes-only` request) and compare the reported `Content-Length` against the actual received
size, then look for a server-side exception at the same timestamp. If the body is valid JSON that simply
stops early, it is (1). If the byte delta equals the number of non-ASCII characters in the payload, it
is (2). If the body is complete but the header is wrong, it is (3).

---

**2026-08-10 — STILL OPEN. Not reproduced. Candidates (2) and (3) measured and ruled out; (1) is now
the leading explanation, but that is inference, not evidence.**

Measured against the live Docker stack (nginx → Tomcat, through `TokenFilter` + `CorsFilter` +
`ExpiresFilter`), fetching a large JSON body from the same filter chain (`GET /rest/schema`,
271,956 bytes, 28 non-ASCII characters):

| | measured |
|---|---|
| `Content-Length` | **absent** — the response is `Transfer-Encoding: chunked` |
| `Content-Encoding` | absent (no gzip anywhere in the chain) |
| body | 271,956 bytes, decodes as UTF-8 cleanly, parses as valid JSON |

Two conclusions follow. Candidate **(2), byte-vs-character length mismatch, is ruled out** for this
chain — nothing is computing a length from a Java `String` length, because nothing is computing a
length at all. Candidate **(3), a filter re-encoding after the length was set, is ruled out** for a
large body — the framing is chunked and intact end to end. That also matches the entry's own note that
the reported direction (header LARGER than body) never fitted a UTF-8 mix-up.

If the browser saw a `Content-Length` at all, it was set on a *smaller* response (Tomcat buffers and
sets a length only when the whole body fits its output buffer, otherwise it switches to chunked), which
points squarely at **(1): an exception thrown after the headers were committed**, leaving the body
short. That is also consistent with two real aborts fixed in this same pass, both of which occur mid-
response on this exact endpoint's call graph: the KI-42 character abort and the
`SDUtil.createImage` `Integer`→`Double` `ClassCastException` (KI-51).

**What I did NOT do:** I did not reproduce the error itself. Reproducing it needs a real
`extract-scenes-only` run from the browser against a chat config wired to a reachable LLM, and the
browser-side `Content-Length`/received-bytes pair from DevTools. **Next step is unchanged and is the
cheapest one:** run one extraction from the Ux, and at the same timestamp check
`accountManagerService.log` for a stack trace. If KI-42/KI-51 were the aborts, this may already be
gone — but do not close it on that assumption.

Related: KI-44 (chat/picture-book convergence) and the scene-extraction cache in
`TestPictureBookCustom.getOrCreateCatatoneScenes`, which is the fastest way to reproduce an extraction
without paying for the LLM twice.

### KI-53. Composite prompt carried each character's OWN baked-in art style — three styles in one image — FIXED ✅ (2026-08-10, Stephen, live)

Reported from a live FLUX composite while the book and both portraits were configured as
`photograph`:

```
The first person is 8k ... circa 2500 AD.
  Comic book panel in Archie Comics style from the manga-influenced Western 2000s ...
The second person is 8k ... Dystopian North American city.
  Fashion photography for CR Fashion Book in 1950s New Look elegance style by Cindy Sherman.
... Photograph taken with a Polaroid SX-70,Nikon F camera ...        <- the book's ACTUAL style
```

A character's narrative bakes in a style clause at CREATION time from a **random** config
(`NarrativeUtil.getSDPrompt` → `getSDConfigPrompt(randomSDConfig())`). The portrait path already
strips that before applying the book style (`buildPortraitPrompt` →
`appendConfigStyleOnce(stripTrailingConfigStyle(...))`), but `resolveSceneCharacter`'s scene-narration
path never did — its comment asserted `physicalDescription`/`outfitDescription` were
"style/setting-free", which was simply not true of stored data. So every character dragged its own
style into the composite, on top of the book's.

**Fix:** `resolveSceneCharacter` now applies `stripTrailingConfigStyle(sceneNarration)` before
returning. Order matters and is documented at the call site: it must run **before** the FLUX/Kontext
SDXL-weighting strip, while `getSDConfigPrompt`'s balanced parentheses are still present — that is the
only thing distinguishing a style clause from ordinary description, and it is exactly why the clauses
appear unparenthesised in the report.

**Verified:** `TestKi53PerCharacterStyleIsStrippedFromSceneNarration` builds a character description
plus a real generated clause for **all ten** styles (comic and fashion among them — the two from the
report) and asserts the clause is gone while the description survives.
`TestKi53StripIsNoOpOnStyleFreeNarration` pins that plain prose is not truncated.

### KI-54. `TestPictureBookFull` had 11 red tests, all of them mine — FIXED ✅ (2026-08-10)

I initially reported these as "pre-existing, not my regressions". That framing was a deflection:
I wrote these tests, and a red test I authored is my defect whether or not this session's changes
caused it. Root causes, all in the tests except where noted:

| Test | Cause |
|---|---|
| `TestThinkFalseOnChatOptions` | Asserted `think` defaults to **true**; `chatOptionsModel.json` says **false**. The test was wrong about the schema. Now asserts false and exercises both directions. |
| `TestBlankSettingSkipsLlmCallEntirely`, `TestPoisonedHallucinatedLandscapePromptSelfHeals`, `TestMismatchedPromptTemplateOverrideDoesNotPoisonScenePrompt` | Asserted exact equality against the **pre-KI-38** prompt, before the config style suffix became part of every persisted prompt. **These also exposed a real production bug** — see below. |
| `TestLiveSwarmMinimalDiagnosticProbe`, `TestLiveSwarmCompositeImg2ImgDiagnosticProbe`, `TestGenerateSceneImageCompletesWithHiresDisabled` | **Hardcoded `http://192.168.1.42:7801`**, ignoring `test.swarm.server`, and set `style: "illustration"` — a style word KI-38 removed, so `getSDConfigPrompt` had no branch for it. Now use the configured server via `SdTestGate` and the canonical `digitalArt`. All three pass live against localhost:7801 (1 image each, 15s/80s/172s). |
| `TestCharPersonCreation` | Genuinely fixed by the KI-35 enrolment fix — the acting user could not read the Olio-owned charPerson, so the re-fetch returned null ("Full person"). |
| `TestLlmSceneExtraction` | Hand-rolled its **own copy** of fence-stripping/array-slicing/JSON-binding instead of calling `PictureBookUtil.parseLlmJsonArray`. The copy had drifted (no `stripThink`) and failed on responses production parses fine. `parseLlmJsonArray` is now public and the test uses it — testing a copy tests the copy. |
| `TestExtractFromRealCatatoneDocumentCapturesJideon`, `TestCatatoneOpeningScenesRealPromptsAndImages` | Assert EXTRACTION QUALITY calibrated on `qwen3-vl:8b-instruct` (Jideon reads male; the passage yields ≥2 scenes). Under `qwen3:8b` they assert the model's competence, not the pipeline's. Weakening them would delete their point, so they now **skip visibly** via `requireCalibratedLlm(...)`, naming both models — the same rule as the SD checkpoint gate. |

**Real production bug found underneath the three prompt tests:** the self-healing cache guards in
`resolveLandscapePrompt`/`resolveScenePrompt` compared the cached value against the **bare** fallback
("A detailed environment" / the bare style clause), but KI-38 made the style suffix (and optional
composition-context prefix) part of what is persisted. So the guard could never recognise its own
legitimate output: it condemned it as a "pre-fix hallucinated result" and re-generated **on every
single call**, logging a false warning each time. Both guards now reconstruct the deterministic value
the same way the write path builds it. `TestPoisonedHallucinatedLandscapePromptSelfHeals` gained a
second resolve asserting the heal STICKS, which is the part that was broken.

### KI-55. Composite defaulted to FLUX Kontext with an uninstalled checkpoint — every default composite was refused — FIXED ✅ (2026-08-10, Stephen)

Stephen reported this three times while I kept fixing the *test* instead of the *default*:

```
[Warning] Refused to generate image for local: Invalid value for parameter Model:
  Invalid model value for param Model - 'flux1Kontext_flux1KontextDev' - are you sure that model name is correct?
```

Three schema defaults in `configModel.json` combined into a path nothing could execute:
`useKontext` was `"default": true`, `compositeMode` had no default (so it fell back to that boolean),
and `kontextModel` defaulted to `flux1Kontext_flux1KontextDev`. A schema default is never null, so
**every** config that did not explicitly say otherwise ran Kontext and sent a checkpoint the local
Swarm does not carry. Swarm refused it, and the composite produced nothing.

**Fix — the defaults now describe what actually ships:**

| field | was | now |
|---|---|---|
| `compositeMode` | *(unset → fell back to useKontext)* | **`"flux2"`** |
| `useKontext` | `true` | `false` |
| `kontextModel` | `flux1Kontext_flux1KontextDev` | *(no default — explicit opt-in)* |

`flux2Model` already defaulted to `flux2Klein_9b`, which is installed, so the default composite now
runs FLUX.2 Klein as Stephen specified.

**`TestKontext.testKontextSceneWithOlioCharacters` is now `@Ignore`d, not skipped.** Kontext is a
superseded path — the picture book routes composites through `SceneCompositeUtil`'s FLUX.2 builder —
and a permanently-skipped live test that fires refused requests at Swarm is pure noise and GPU time.
The prompt-shape tests in that class still cover the shared builder and stay enabled.

**Verified:** `TestFlux2Composite` 17/17 — `bareConfigResolvesToFlux2ByDefault` (replacing
`bareConfigResolvesToKontextBecauseOfTheSchemaDefault`) pins all three corrected defaults, and
`legacyBooleanStillHonoredWhenModeAbsent` still proves a saved pre-`compositeMode` config keeps
working once the mode is cleared. `TestKontext` 8 run / 1 ignored, `TestSDStyles` 4/4,
`TestPictureBookKnownIssues` 13/13.

**My failure here, recorded:** Stephen told me the checkpoint was not installed and to use
flux2Klein, and I responded by gating the test rather than reading why the request was being made at
all. The refusals kept appearing because the *default* was still Kontext. Hours of compute went into a
path nothing uses.

### KI-56. Changing the SD style from the Ux produced a clause containing the literal text "null" — FIXED ✅ (2026-08-10, Stephen)

"The composite image style gets perverted somewhere from the Ux." Two halves, both fixed:

**Server.** Every branch of `SDUtil.getSDConfigPrompt` concatenates its per-style detail fields
straight into the clause, with no null guard. A config whose style was CHANGED carries the previous
style's details and none for the new one, so it rendered as
`(Comic book panel) in (null) style from the (null) with (null).` — literal "null" text sent to the
image model. `getSDConfigPrompt` now calls `fillStyleDefaults(cfg)` before composing; it only fills
MISSING fields, so an explicitly configured detail is never overwritten.

**Ux.** `SdConfigPanel`'s style picker set `config.style` and fired `onChange` without repopulating
the new style's detail fields — the thing that created the incomplete config in the first place. The
picker now calls `am7sd.fillStyleDefaults(config)` on change.

**Verified:** `TestKi56StyleClauseNeverContainsLiteralNull` walks all ten styles with a bare
style-only config and asserts no `(null)` survives; `TestKi56StyleChangeProducesTheNewStyleClause`
switches photograph → comic on a record still carrying the photograph details and asserts the clause
describes the NEW style and contains no null.

### KI-57. Changing style mid-run kept regenerating with the OLD style (cached prompts) — FIXED ✅ (2026-08-10, Stephen)

Reported alongside KI-56: "if picture gen is started for style #1, then stopped, the style changed and
restarted, it seemed like it started producing strange/incomplete results for regenerating corrected
images that had used style #1."

A resolved scene/landscape prompt is PERSISTED into the scene note **with the style clause of the
config that produced it**, and the cache-hit path in `resolveScenePrompt`/`resolveLandscapePrompt`
returned it verbatim. Changing the book's style therefore never invalidated it: every regenerated
image re-sent style #1's clause while the rest of the run used style #2, and the two mixed.

**Fix:** new `restyleCached(cached, sdConfig)` — strip whatever trailing style clause the cached value
carries and append the current one, then persist if it changed. The LLM-authored description and any
prepended composition context are untouched; only the code-owned style suffix moves. No cache to clear
by hand.

### KI-58. ImageIO shutdown hook REGISTERED providers under a "deregistering" log line — FIXED ✅ (2026-08-10, Stephen)

Reported error:
```
NoClassDefFoundError: ... Illegal access: this web application instance has been stopped already.
  Could not load [java.nio.channels.Channels]
  com.twelvemonkeys.imageio.stream.BufferedInputStreamImageInputStreamSpi.createInputStreamInstance
  javax.imageio.ImageIO.read -> GraphicsUtil.createThumbnail -> ThumbnailUtil -> MediaUtil -> ThumbnailServlet
```

`AccountManagerContextListener.contextDestroyed` logged *"Deregistering ImageIO service providers to
prevent ClassLoader leaks"* and then called **`ImageIO.scanForPlugins()`** — which REGISTERS
providers. `IIORegistry.getDefaultInstance()` is per-ThreadGroup and long outlives a webapp, so
scanning on the way out pinned this webapp's classloader and the TwelveMonkeys SPI classes into a
registry that survives undeploy.

**Fix, two parts:**
- `contextDestroyed` now actually deregisters — it walks `IIORegistry`'s categories and removes only
  providers whose implementation class was loaded by THIS webapp's classloader, leaving the JDK's own
  codecs and anything container-supplied alone. Never throws; cleanup cannot block undeploy.
- `contextInitialized` sets `ImageIO.setUseCache(false)` and scans once, up front. The disk cache is
  what lazily drags in the `FileCacheImageInputStream`/`Channels` machinery *during a request* — the
  precise class load that gets refused once the instance is stopped. Thumbnails do not need it.

### KI-59. No exported test image shows the landscape actually integrated into the composite — OPEN (2026-08-10, Stephen)

**Stephen's observation, not mine:** none of the images the tests exported show a composite that
successfully integrated the landscape. He also noted the caveat himself — *"those are also old
tests"* — so the artifacts may be evidence about superseded code paths rather than about the current
pipeline. **Deliberately NOT fixed this pass; recorded for a fresh look.**

**What I can state factually (code-level, verified):**

- **No test anywhere asserts landscape integration.** Every composite/landscape assertion in
  `TestPictureBookFull` is existence-only — `assertFalse(landImages.isEmpty())`,
  `assertNotNull(landscapeBytes)`, `assertFalse(compImages.isEmpty())`,
  `assertNotNull("Should produce a final composite image", imageObjectId)`. The single
  content-sensitive one (`:1233`) asserts two composites *differ*, not that either contains the
  setting. So a composite that silently dropped the landscape would pass every test we have. This is
  a genuine coverage hole regardless of what the images show.
- **The exported artifacts came from paths that are no longer the default.**
  `TestLiveSwarmCompositeImg2ImgDiagnosticProbe` deliberately exercises the **classic** pipeline
  (landscape as an img2img *init image* at `initImageCreativity=0.85`), and `kontext-*.png` in
  `AccountManagerObjects7/kontext-test-output/` is dated 2026-08-09 and comes from the **Kontext**
  path, now `@Ignore`d as superseded. KI-55 changed the default composite to **flux2**, where the
  landscape is a *reference image*, not an init image — a structurally different mechanism. Whether
  current flux2 composites integrate the landscape is **unknown and untested**.
- **A concrete candidate to check first:** `flux2IncludeLandscapeRef` has no schema default
  (deliberately), so `SceneCompositeUtil` falls back to `Flux2Defaults.includeLandscapeRef()`, which
  reads `olio/sd/flux2Defaults.json` → currently `true`. If that resource is ever edited to `false`
  (it is documented as the primary speed lever, ~40s per reference), the landscape reference is
  suppressed and the setting reaches the model **as prompt text only** — which would look exactly
  like "the landscape was not integrated". `SceneCompositeUtil` logs
  `"landscape reference SUPPRESSED by flux2IncludeLandscapeRef=false"` when that happens, so the log
  settles it in one line.

**What I did NOT do:** I did not visually inspect the exported images, and I did not generate a
current flux2 composite to compare. Per the project's own standard, decode-succeeded assertions are
not proof for an image pipeline — someone has to look.

**Next steps, in order:**
1. Generate ONE flux2 composite with the current defaults and look at it, checking the log for the
   `landscape reference SUPPRESSED` line first (free, and it may end the investigation).
2. If the landscape genuinely isn't landing, compare `buildFlux2References`' reference ordering and
   `flux2ReferenceSize` letterboxing against the FLUX.2 guidance — the setting reference is the third
   one, and reference order matters to edit models.
3. Add the missing assertion in whatever form is honest for an image pipeline (a human-inspected
   fixture comparison, or at minimum asserting the request actually carried three references when
   `flux2IncludeLandscapeRef` is on) — the existence-only checks above cannot catch this class of bug.

Related: KI-47 (the four SD-session changes still needing a live look, one of which is
"`PictureBookUtil`'s flux2 branch now routes through `SceneCompositeUtil`" — same pipeline, also
unverified) and KI-54 (the stale-test cleanup that explains why these exports are old).

### KI-60. Narrative creation is wrong — duplicate-key INSERT on re-create, and my recovery adopts the WRONG GROUP — OPEN, HIGH — **Stephen investigating** (2026-08-10)

> **CHARACTERIZATION RESULTS — 2026-08-14.** A dedicated suite,
> `AccountManagerObjects7/src/test/java/org/cote/accountmanager/objects/tests/TestPathUtilBehavior.java`
> (15 cases, live `am7db`, non-admin users, uuid-scoped scratch trees), was written to reproduce this
> entry's theories. Its path oracle is `materializedPath()` — it rebuilds the path by walking `parentId`
> out of the DB with `setCache(false)`, so it does not trust the virtual `path` field computed by the
> machinery under test. **Two of this entry's standing theories are now eliminated, and three separate
> defects were proven.** Read this before spending more time on the entry below.
>
> **ELIMINATED — the `CacheDBSearch` query-key theory.** Driving the exact lookup `makePath` uses
> (`findByNameInParent(auth.group, parent, <name>, "DATA", org)`) across the full KI-60 sibling set
> (`Apparel, Wearables, Qualities, Narratives, Profiles, Statistics, Instincts, Personalities, States`)
> in the reported order returned the correctly-named row every time; the identical sequence with
> `setCache(false)` returned **the same ids**. Two code facts agree: `QueryUtil.fieldKey()` appends the
> field **value** (`QueryUtil.java:137-157`), so the key distinguishes names; and
> `CacheDBSearch.addToCache` only caches when `count > 0`, so **a miss is never cached and the cache
> cannot manufacture a false miss.** Do not add cache workarounds.
>
> **NOT REPRODUCED — the wrong-*name* adoption.** With the sibling set present and `Narratives` arranged
> so the DATA lookup misses it, the recovery fired and `findExistingNode` adopted the **correct**
> `Narratives` (name, parent and org all matching). *Honest caveat:* that repro forces the miss with a
> **mismatched-type** row, whereas the live case is a `Narratives` row already `DATA` missed by a `DATA`
> lookup — a condition that could not be produced by any means tried. So this is "not reproduced under a
> different pre-state", **not** "disproven". A setup capable of showing it would need the live org-3 data
> (ids 151 vs 1049, ~900 apart — consistent with `Narratives` recreated long after `Apparel`), a
> long-lived JVM where a cache entry predates the delete by more than `CacheDBSearch.checkCache()`'s 360s
> age flush, and `PictureBookUtil.reset()`'s actual delete rather than a single `deleteRecord`.
>
> **PROVEN — three real defects, all now fixed or being fixed:**
> 1. **`~` expansion re-emits the whole path beneath itself.** `makePath` performs **no normalization**:
>    `~` is textually replaced by the home path (`PathUtil:72-88`), split on `/`, and each segment
>    resolved/created under the previous — with no check that the remainder already begins with the home
>    path. `~/home/<user>/X` resolves to `/home/<user>/home/<user>/X`, returning an ordinary non-null
>    group with **no signal to the caller**. This is Stephen's separately-reported "a whole path emitted
>    under a sub path". It is a **live input shape**: `PathProvider.provide:53-60` writes the *expanded
>    absolute* path back onto the record and `RecordUtil.resolveUserPath:839-848` expands the same way,
>    while ~15 sites build paths as `"~/" + value` (`CharPersonFactory:35,44-50`,
>    `PictureBookUtil:2290,2311`, `VaultService:338,388,453,570`, `RecordUtil:139`). *Which* production
>    caller performs the round-trip was **not** identified — every model-declared `group` hint is a bare
>    single segment, so those concatenation sites are safe today.
> 2. **The type-mismatch recovery returns the wrong type as if it satisfied the request.** The unique
>    constraint is `(name, parentId, organizationId)` — **type is not in it** — while the lookup
>    (`PathUtil:130`) **is** type-filtered. So a `DATA` request whose sibling row is `BUCKET` can never
>    find it, can never insert it, and the recovery (`:172-200`, `findExistingNode:236-251`) adopts on the
>    constraint key alone and hands back a `BUCKET`. *(Stephen: "it matched on the name but the sub type
>    wasn't specified.")*
> 3. **The recovery is not idempotent** — the direct consequence of (2), and **this is exactly this
>    entry's "the duplicate-key INSERT is still ATTEMPTED on every run"**. The second `makePath` for an
>    already-resolved path re-attempts the insert and collides again, because nothing ever repairs the
>    condition that made the lookup miss.
>
> **NEW DEFECT, not previously catalogued — the `utype` override.** `PathUtil:113-120` overrides the
> **lookup** type to `DATA` for any segment named `home` or named after the owner, but `:151-153`
> **creates** with the *original* requested type. A non-DATA request whose path contains such a segment
> therefore writes a node its own lookup can never match — a **deterministic instance of this entry's
> otherwise-unexplained "a lookup that fails to see a row which is present", with no hand-placed row and
> no cache involved.** It reaches only non-DATA group requests (`BUCKET` — custom collections such as
> Favorites; `USER`/`ACCOUNT` — mostly IAM/PBAC), which are comparatively rare, so it is **unlikely to be
> the live KI-60 cause** and is being fixed on its own merits.
>
> **STILL UNEXPLAINED:** the live miss itself — a `DATA` lookup failing to see a `DATA` row. None of the
> above accounts for it.
>
> **Also found, not changed:** `findPath` (`:55-57`) is **not synchronized** — it calls the private
> overload directly, bypassing the monitor `makePath` holds at `:62`, so `synchronized` does not
> serialize reads against in-flight creates. Untested (needs a concurrency harness); flagged because
> `PathUtil.makePath` is a JVM-global monitor and changing the locking has broad consequences.

Reported live from the running service:

```
DBWriter - java.sql.BatchUpdateException: Batch entry 0
  INSERT INTO A7_auth_group_0_1 (... 'Narratives' ... organizationId 3 ... parentId 31 ...
   urn 'am6:auth.group.data:public:home.steve.narratives')
  was aborted: ERROR: duplicate key value violates unique constraint "a7_auth_group_0_1_ne_pd_od_1_idx"
  Detail: Key (name, parentid, organizationid)=(Narratives, 31, 3) already exists.
PathUtil - Write of auth.group node Narratives in parent #31 lost to an existing record (#151);
  adopting it rather than returning an unpersisted node
```

**Two separate problems, and the second one is mine and is worse than the bug it replaced.**

**1. The KI-42 fix works as designed, but only as a safety net.** The `adopting it` line is the new
`PathUtil.makePath` recovery firing: no phantom group id is returned any more, so the character is not
lost. But the duplicate-key INSERT is still ATTEMPTED on every run — the lookup still misses a row that
exists. KI-42 fixed the *consequence*, not the *cause*. The cause remains unknown: a
`findByNameInParent(auth.group, 31, "Narratives", "DATA", 3)` that fails to see a row which is present
and is typed `DATA`.

**2. THE RECOVERY ADOPTS THE WRONG GROUP.** Verified directly against the live database:

```
 id   |    name    | parentid | organizationid | type
------+------------+----------+----------------+------
  151 | Apparel    |       31 |              3 | DATA
 1049 | Narratives |       31 |              3 | DATA
```

The Narratives group under (31, 3) is **#1049**. `findExistingNode` returned **#151, which is
`Apparel`** — a different group entirely. So instead of a phantom id, narratives are now filed into
the Apparel group. That is silent data misplacement, and it is a regression introduced by my KI-42
recovery path, not a pre-existing condition. **This should be fixed before the KI-42 change is relied
on.**

**REPRODUCTION STEPS (Stephen, 2026-08-10):**

1. Create a picture book.
2. Extract scenes and characters.
3. **Delete the picture book.**
4. Try to add it again — *the name does not matter, changing it makes no difference.*

The delete-then-recreate cycle is the trigger. Note that step 4 failing regardless of the book NAME
rules out anything scoped to the book group: the group that collides (`Narratives` under the USER's
home, parent 31) is user-scoped, not book-scoped, so a fresh book name reuses the same target.

**STEPHEN'S DIAGNOSIS — treat this as the primary lead, ahead of anything below:** *"however you are
trying to add the narrative is wrong."* The narrative is being created by
`PictureBookUtil.createPersistedForeignInstance` — a hand-rolled
`Factory.newInstance(path) + AccessPoint.create` pair written for PictureBook — when the codebase
already has the pattern that works: **`NarrativeUtil.getCreateNarrative(OlioContext, List<BaseRecord>,
String setting)`** (`NarrativeUtil.java:1063`), used by `SDUtil.generateSDImages`/`generateSDFigurines`
and the rest of Olio. The fix direction is to use the existing pattern rather than keep repairing the
bespoke one.

This was already recorded as standing guidance before this session ("search existing Olio utils first
— `NarrativeUtil.getCreateNarrative`/`RecordUtil.patch`/`Queue` already solve nested-record
persistence; don't hand-roll") and I did not follow it: the 2026-08-10 pass added
`prepareForeignSubModelGroups` to pre-resolve groups for the hand-rolled path instead of replacing
that path with the working one. Both KI-42's residual INSERT and the wrong-group adoption live in that
bespoke code.

**Stephen is investigating this one himself.** Do not start a competing fix.

**Secondary observation — UNPROVEN, do not promote it without evidence** (this session already lost time to
exactly that mistake): the search cache. `CacheDBSearch` caches `QueryResult`s by query hash, and a
picture-book run resolves a whole series of sibling groups through the same shape of call —
`findByNameInParent(auth.group, 31, <name>, …)` for Apparel, Wearables, Qualities, Narratives,
Profiles… If the cache key does not distinguish the `name` VALUE, every later lookup returns the first
cached sibling. That would explain both symptoms at once: the type-filtered lookup missing Narratives,
and the type-less re-read returning Apparel. `CacheDBSearch` already carries a
`MISMATCHED CACHED RESULT TYPE` guard for a related transposition problem, which is suggestive but not
proof. **Check the query-key construction in `Query.key()`/`CacheDBSearch` before writing anything.**

**Minimum safe change, whatever the root cause turns out to be:** `PathUtil.findExistingNode` must
VERIFY the record it re-read before adopting it — confirm `name`, `parentId` and `organizationId`
match what was requested, and return null (a clean failure) rather than adopt a mismatch. A
get-or-create recovery that can hand back a different object is worse than one that fails loudly. A
targeted repro should also re-read with `setCache(false)` to confirm or eliminate the cache in one
step.

Also relevant to the delete-then-recreate trigger: `PictureBookUtil.reset()` (KI-32) deletes the book
group, and the search cache is not obviously invalidated for the sibling-group lookups that follow.

Related: KI-42 (the fix this exposes), and the `Query`/cache notes in `.claude/rules/model-api.md`
("`/rest/model/search` is cached by query key — set `cache:false` for views that must see
just-created/edited/deleted records").

### KI-50. `PolicyUtil`'s "Group could not be found" diagnostic NPEs on any model without a `urn` — FIXED ✅ (2026-08-10)

Split out of KI-42, where it was recorded as latent. It is not latent: it fired during KI-35 testing,
turning an ordinary authorization outcome on `olio.wearable` into an uncaught
`NullPointerException` out of `AuthorizationUtil.canUpdate`. `getResourcePolicy`'s error branch called
`resource.copyRecord(new String[]{… FIELD_URN …}).toFullString()`, and `copyRecord` returns **null**
when the model lacks a requested field — which is every model inheriting `common.baseLight`
(`olio.narrative`, `olio.wearable`). The diagnostic destroyed the diagnosis. Replaced with
`describeUnresolvedResource(...)`: requests only fields the model actually has, and never lets logging
throw. Authorization behaviour unchanged — the denial now surfaces as a clean `AUDIT DENY`, observed
live.

### KI-51. `SDUtil.createImage` threw `ClassCastException: Integer cannot be cast to Double` on every portrait — FIXED ✅ (2026-08-10)

Found in `C:\projects\logsccountManagerService-error.log` while investigating KI-49, from a real
run on 2026-08-08:

```
PictureBookUtil - Portrait generation error for Catatonic Figure:
  class java.lang.Integer cannot be cast to class java.lang.Double
    at SDUtil.createImage(SDUtil.java:860)
    at PictureBookUtil.generateSceneImage(PictureBookUtil.java:3361)
    at PictureBookService.generateSceneImage(PictureBookService.java:398)
```

`BaseRecord.get` is generic, so `s2i.setCfgScale(sdConfig.get("cfg"))` compiled to a cast to `Double`
while `olio.sd.config` declares `cfg` as an **int** — the field-type trap in `objects7-reference.md`.
Every picture-book portrait reaching this path died *before any image was requested*. Fixed with a
`numberValue(rec, field, default)` helper reading through `Number`, applied to the `cfg` read and to
every `refinerControlPercentage` read in `SDUtil`/`SWUtil` (clients send these too — `reimageApparel`'s
CFG slider even steps by 0.5).

**Verified:** `TestCreateImageDoesNotClassCastOnIntegerCfg` asserts `cfg` really is an `Integer` on a
schema-built config (or the regression means nothing), then drives `createImage` against a deliberately
dead address — reaching the transport proves the request was built, since the cast happened before any
socket was opened.

### KI-52. Docker image could not start from a Windows checkout: CRLF entrypoint — FIXED ✅ (2026-08-10)

`docker compose -p am7test -f docker-compose.test.yml up` looped with
`exec /usr/local/bin/entrypoint.sh: no such file or directory` — the kernel reporting that the
interpreter `/bin/bash
` does not exist, not that the script is missing. The repo had no
`.gitattributes`, so a Windows checkout produced CRLF shell scripts. Fixed at both ends: a new
`.gitattributes` pins `*.sh`, `docker/**`, `*.conf` and `Dockerfile` to `eol=lf`, and the Dockerfile
now `sed -i 's/
$//'`s the copied scripts and configs so the image builds correctly from a checkout
with any `core.autocrlf` setting. **Verified:** stack rebuilt and came up on `:9443`, first-run setup
completed, REST calls served.

### KI-64. Chat scene generator's SD form mishandles sliders — defaults don't display correctly, and generation then fails with an imaging error — OPEN (2026-08-12, Stephen)

Stephen's report: on the **chat** scene generator's SD config form, the sliders are not being used
correctly — default values don't show correctly — **and the consequence is an imaging error**, i.e.
this is not cosmetic; the wrong/absent value reaches the image request.

**Not diagnosed — pointers only, nothing verified below.** Chat renders the shared panel
(`chat/SceneGenerator.js:259` → `components/SdConfigPanel.js`), the same component the picture book
uses (`workflows/pictureBook.js:950`), so a chat-only symptom suggests the difference is in what chat
*feeds* the panel, not the panel's markup. Two things a next pass should check before assuming a
renderer bug:

- `SdConfigPanel.rangeInput` (`components/SdConfigPanel.js:109-121`) displays `min` whenever
  `config[key] == null`, and only writes `config[key]` on `onInput`. So an unset field renders at its
  minimum while the entity still holds `null`/absent — the displayed value and the value actually sent
  are different, with no UI cue distinguishing them. That is the same "default substituted silently,
  looks plausible, indistinguishable from real data" shape as KI-16 Finding B, and it is a plausible
  route from "default doesn't show correctly" to a rejected image request.
- `ensureSdConfig` (`chat/SceneGenerator.js:61-101`) overlays saved tweaks onto a server template but
  **skips any stored value that is `undefined`/`null`/`''`** (`:84`) and skips `model`/`refinerModel`
  (`:54`, `:81`) — deliberate, per the HISTORY comment at `:40-54`. Combined with
  `pinChatSceneDefaults` forcing `compositeMode="flux2"` and `hires=false` (`:30-38`), the FLUX.2
  fields (`flux2Cfg`, `flux2Steps`, `flux2ReferenceSize`, …) are exactly the ones most likely to be
  absent from the template/overlay and therefore rendered-at-min-but-sent-as-null.

Related: KI-16 Finding C (the slider-implementation convergence, which `SdConfigPanel`'s
`rangeInput` was folded into), KI-43 (a disconnected denoise slider in `reimageApparel.js` — same
class), KI-51 (an int/double type trap on `cfg` that killed image requests before any socket opened),
KI-55 (a default that pointed at an uninstalled checkpoint, so every default composite was refused).
KI-65 below is the other half of Stephen's report.

### KI-65. Chat's SD config does not persist — it should, the same way reimage/reimageApparel do — OPEN (2026-08-12, Stephen)

Stephen's report: the SD config doesn't persist for chat, and it should.

**Pointers, not a diagnosis.** Chat is the only SD surface that persists to **`localStorage`**
(`chat/SceneGenerator.js:18` `SD_CONFIG_KEY = "am7.sdConfig"`, written by `saveConfig` at `:103-117`,
read back by `ensureSdConfig` at `:74-88`). Every other SD surface persists **server-side** as a named
`olio.sd.config` under `~/Data/.preferences` via `am7sd.saveConfig`/`loadConfig`
(`components/sdConfig.js:94-163`) — `workflows/reimage.js:144,238,495-497,829-831` (per-character plus a
shared `sharedSD.json`) and `workflows/reimageApparel.js:35,168,216` (`sharedApparelSD.json`).

So chat's config is per-browser, lost on a storage clear or a different machine/profile, and invisible
to the server. Note also that `ensureSdConfig` drops stored `null`/`''` values and never restores
`model`/`refinerModel` (`:54`, `:81`, `:84`) — intentional for the reasons in the `:40-54` HISTORY
comment, but it means "I changed a field and it came back" is expected behaviour for *some* fields even
before this issue, which will confuse the repro if not accounted for.

**Fix direction (not implemented):** move chat onto `am7sd.saveConfig`/`loadConfig` with its own named
config (e.g. `sharedChatSD.json`, mirroring the existing shared configs) so it uses the same
persistence the rest of the SD surfaces already do, keeping the deliberate model/refinerModel exclusion
intact. Overlapping with KI-64 — a persisted config that round-trips through the model is also the path
that would stop unset slider fields from being invented at render time.

### KI-66. Include/exclude landscape in composite creation must be a config option in the Ux — FEATURE REQUEST (2026-08-12, Stephen)

Stephen: the include/exclude-landscape choice (boolean/checkbox) for composite creation needs to be a
**config option exposed from the Ux**, not something only settable in code/config files.

**The backend already supports it; only the Ux exposure is missing.** Verified by reading the code:
- `olio.sd.config` declares the field — `flux2IncludeLandscapeRef`,
  `AccountManagerObjects7/src/main/resources/models/olio/sd/configModel.json:359`. Its description
  states the intent (setting fidelity vs. GPU cost — it's the primary speed lever for picture-book
  runs) and states that it deliberately carries **no schema default**, because a default is never null
  and would make the `flux2Defaults.json` fallback dead.
- The composite path honours it — `SceneCompositeUtil.java:111-131` reads
  `sdConfig.get("flux2IncludeLandscapeRef")` and falls back to `Flux2Defaults.includeLandscapeRef()`
  (`Flux2Defaults.java:120`, backed by `olio/sd/flux2Defaults.json:36`, currently `true`) only when the
  config value is null; `false` drops the landscape reference and logs that the setting is then carried
  by prompt text only.
- Coverage exists — `TestFlux2Composite.java:373-415` asserts both directions.

**What's missing (checked, not assumed):** there is no control for it anywhere in `AccountManagerUx752/src`.
`grep flux2` over `src/` returns only `compositeMode = 'flux2'` assignments plus a comment
(`chat/SceneGenerator.js:35,45`, `workflows/pictureBook.js:182,1420`). The shared panel
`components/SdConfigPanel.js` renders exactly one checkbox, `hires` (`:320`) — see `checkboxInput` at
`:123`. And the client's copy of the schema is stale on this point: the `olio.sd.config` block in
`core/modelDef.js:9933-10224` contains **zero** `flux2*` fields, and `forms.sdConfig`
(`core/formDef.js:769`) declares none either. That last part matters for implementation, because
`SdConfigPanel`'s `instConfig` proxy only defines properties for `inst.fields`
(`components/SdConfigPanel.js:156-174`) — a field absent from the form/model def is not reachable
through the `inst` path even when the server template carries a value for it. This is the hand-curated
`modelDef.js` divergence already recorded in `AccountManagerUx752/CLAUDE.md`.

**Fix direction (not implemented):** add `flux2IncludeLandscapeRef` to the client model/form defs and a
checkbox next to `hires` in `SdConfigPanel`, leaving the value **null/unset by default** so the
`flux2Defaults.json` fallback keeps working (setting a client-side default would reintroduce exactly the
dead-fallback problem the schema comment warns about). Whatever lands must also actually persist —
which for chat means KI-65 first, or the toggle resets per browser.

Related: KI-64/KI-65 (the other two items from this report), KI-59 (no exported test image yet shows the
landscape genuinely integrated into a composite — this toggle is the switch that test would need to
flip), KI-55 (a composite default that silently refused every generation).

---

### KI-67. `AccessPoint.list` performs NO per-record authorization — an org-wide list returns another user's group-scoped records — OPEN, PLATFORM (2026-08-16, found running the phase-2c PictureBook 2 tests; disposed for PB2 on 2026-08-17)

**Measured on `am7db` with two users in one organization.** A by-objectId read of user A's
`olio.pb.node` as user B is correctly `AUDIT DENY`. An org-wide list of the same model with an explicit
numeric `organizationId` condition returns it, `AUDIT PERMIT`.

**Mechanism, read out of the code rather than inferred.** `AccessPoint.find` (`:480-523`) authorizes the
query shape **and then** runs `AuthorizationUtil.canRead` on the result before returning it
(`:511-517`). `AccessPoint.list` (`:623-636`) authorizes the shape via `authorizeQuery` and returns
whatever `search` returned, **unfiltered**. `count` (`:607-622`) is the same shape.

**This is pre-existing, general to every group-scoped model, and partly deliberate.** The design note at
`AccessPoint.java:600-605` records the history: AM5 restricted lists to the parent or group, AM6 used an
elaborate dynamic SQL evaluation, and AM7 settled on assembling a policy from the query "so that at least
one policy check becomes required to perform the action." So `list` authorizes a **query shape** by
design. What is defective is the consequence: an explicit `organizationId` condition satisfies PBAC's
query requirement while being neither a tenancy nor a compartment filter, so any authenticated user in an
organization can enumerate group-scoped records they cannot read by identity.

**Pinned, not left red:** `TestPbSecurity#case02_theListPathIsNotASecurityBoundary_MEASURED_DEFECT`
asserts the current (wrong) behaviour as a labelled characterization, the same way
`TestPbModelSchema#TestSdConfigFieldsAreSerializedNotForeign` pins a known-wrong shape. It will fail the
day this is fixed, which is the point.

**Disposition for PictureBook 2 (2026-08-17) — constrain at the utility layer so phase 3/4 are unblocked;
`AccessPoint.list` not changed in that phase.** Every PB2 list is reached from an authorized
`AccessPoint.find` of the book (§5.6b's root-reference principle: authorize the root by identity, then
list inside its compartment), and phase 4's endpoints take a **book objectId**, `find` it, and delegate to
the Objects7 utility. Phase 4 must not expose the generic `/rest/model/search` over `olio.pb.*` and must
not list on a caller-supplied `groupId`/`organizationId`. Full reasoning in `PictureBook2Plan.md`
Appendix D.

**CORRECTION 2026-08-17 (Stephen) — the cost argument against fixing `list` was WRONG, and it was the
load-bearing one.** The disposition originally claimed that per-record filtering means an
`AuthorizationUtil.canRead` policy evaluation **per row**. It does not: for a **parent- or
directory-scoped** record the check resolves **at that level first**. `PolicyUtil.java:761-789` rewrites
the policy's resource to the **group's urn** for anything inheriting `data.directory`
(`policyBase = g.replaceAll(grp.get(FIELD_URN))`), and `:795-804` does the same through `parentId` for
anything inheriting `common.parent`. N records in one group therefore share **one** policy key — one
evaluation, then decision-cache hits; the per-row cost is a cache lookup plus a `conditionalPopulate` of
identity fields (`AuthorizationUtil.java:158`). ⇒ **Fixing `list` is materially cheaper than claimed, and
whether to fix it is reopened.** The only genuine remaining consideration is that rows would stop
appearing in existing lists — which is the defect, not a regression, but is still a product-wide change
deserving its own baseline rather than riding in on a PictureBook phase. **Recommendation is now to fix
`list`**, leaning on that group-level resolution.

**Residual risk (open):** an Objects7 caller that fabricates a workflow/node record from an id without an
authorized `find` can still list another user's rows. Any endpoint anywhere that lists group-scoped
records must constrain by `groupId` or filter per record itself — relying on `list` to do it is wrong
today.

Related: `.claude/rules/model-api.md` ("`AccessPoint.list` is NOT a per-record authorization boundary",
added in phase 2c), KI-63 (PictureBook images not compartmentalized).

---

### KI-68. The FLUX.2 scene prompt asserts and then NEGATES its own medium — `"Photograph taken with a …"` followed by `"no photograph"` in the same positive prompt — OPEN (2026-08-17, Stephen)

**Read out of a persisted `generatorRequest`, not inferred.** A picture-book scene rendered with
`style=photograph` produces this positive prompt (abridged, real):

```
Combine the exact person and face from the first reference image with ... Place both people together in
the environment shown in the third reference image (...). The first person is 8k highly detailed highest
quality ultra realistic full body of Jideon de Rosa ... The mood is ...
Photograph taken with a Kodak Brownie box camera and Canon FD 50mm f/1.8 lens using Lomography 100 film
processed with Kodacolor by James Van Der Zee.
Preserve facial identity, hair, and clothing precisely. Matching lighting, scale, and perspective across
the whole image. No extra people.
Do not draw the reference images themselves - no photograph, poster, screen, mirror, billboard, framed
picture or character sheet anywhere in the scene.
A single continuous scene, no panels, no split screen, no collage.
```

FLUX.2 is an instruction-following edit model and this is the **positive** prompt — there is no negative
weighting to separate the two — so the medium is asserted by the style clause and cancelled four
sentences later. Observed effect: a book configured as `photograph` renders as glossy digital art.

**Location:** `SWUtil.java:315-316` (`newFlux2SceneTxt2Img`'s coherence block).

**An earlier fix for the same bug class missed this occurrence.** The comment at `SWUtil.java:317-319`
records that `"photographic"` was removed from this exact clause because *"the medium is now stated once,
up front, by the config style … Saying it twice in different words is how a comic-styled book ended up
being told 'photographic' mid-prompt."* `"no photograph"` was left in the same sentence, so the word
survived in its **negated** form — which is worse than the duplication that was fixed, because it now
contradicts the configured style rather than merely restating it.

**Fix direction (one word, not applied):** drop `photograph` from the forbidden-objects list at `:315`.
The remaining items — `poster, screen, mirror, billboard, framed picture, character sheet` — already
express "do not render the reference as a depicted object in the scene", and `photograph` is the only one
that collides with the medium vocabulary. Consider the same audit for any other style word that could
appear in that list (`painting`, `drawing`, `illustration` would each break a differently-styled book).

**Why it was not applied on discovery:** `newFlux2SceneTxt2Img` is the **shared** builder — the chat scene
generator uses it too — so the change alters generation output for chat as well as for both PictureBook
paths, and the phase-3 non-regression gate was mid-run against the current string. It needs its own
before/after visual comparison rather than a drive-by edit.

**Secondary, same prompt, lower confidence:** `8k highly detailed highest quality ultra realistic` appears
**twice** (once per character) and pulls toward hyperreal/CGI against a film-stock directive; and
`steps=4` at `cfgscale=2.0` on the distilled `flux2Klein_9b` contributes its own smoothed look. Both are
worth testing after the contradiction is removed, not before — the contradiction is the dominant term.

Related: KI-59 (honest verification of an image pipeline), KI-66 (include/exclude landscape as a Ux
option), `PictureBook2Plan.md` §2.4 (the deliberate `steps:4` vs `FALLBACK_STEPS=24` split).
