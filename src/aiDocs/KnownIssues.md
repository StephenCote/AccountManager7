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

### KI-10. Cancel does not actually stop scene extraction or image generation — OPEN (2026-07-06, Stephen)
Hitting cancel during scene extraction or image generation does not abort the in-flight backend operation — it keeps running to completion. The picture-book generate/extract endpoints in `AccountManagerService7/.../PictureBookService.java` run synchronously with no cancellation/interrupt token, so a client "cancel" only affects the UI, not the server work (mirrors the ISO 42001 run model, which is also synchronous with no cancel endpoint). Fix direction: introduce a cancellation signal the long-running loops (chunked extraction, per-scene image pipeline) check between stages — e.g. a per-request/per-book cancel flag set via a small endpoint or WebSocket chirp, polled at chunk/stage boundaries — and have the UI cancel button hit it. Client side, ensure the cancel also stops awaiting/rendering the in-flight request. Related: KI (portrait persistence/reuse) work in the same service.

### KI-30. PictureBook character creation should run the general random-character generator first, then override with LLM-extracted fields — DESIGN DIRECTION (2026-07-22, Stephen)
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

### KI-28. Outfit generation wizard for `olio.charPerson` fails with a server error — OPEN (2026-07-22, Stephen)
Reported by Stephen; not yet reproduced or diagnosed in this session — no error text or repro steps captured yet. Filed as a placeholder so it isn't lost: the outfit-builder flow (`workflows/outfitBuilder.js`, invoked from PictureBook's `pictureBookCharacters.js` "Generate New Outfit" button and presumably from the charPerson editor directly) fails server-side. **Next step for whoever picks this up:** reproduce via `ensureSharedTestUser()` (never admin), capture the actual REST response/status and Tomcat log entry for the failing call, then route per `troubleshooting.md`'s gate (raw API call first, to confirm client vs. backend before assuming either).

### KI-29. Outfit reimage fails — suspected SD-config inconsistency, same class of bug as KI-16 Finding C — OPEN (2026-07-22, Stephen)
Reported by Stephen, with explicit frustration that this class of bug was raised before and not fixed: reimaging an apparel/outfit item fails, and the symptom looks like an SD-config inconsistency. KI-16's Finding C already catalogued **≥5 divergent SD/range-slider implementations** across `workflows/reimage.js`, `workflows/reimageApparel.js`, `workflows/pictureBook.js`, and `components/SdConfigPanel.js` — schema-blind, hardcoded min/max/step, no shared source of truth for SD parameters — and marked it "FIXED ✅ (2026-07-13)" by converging the **slider markup** onto `formFieldRenderers.renderRange`. That fix addressed the UI widget duplication; it did **not** necessarily guarantee the underlying **SD parameter values** (model/sampler/scheduler/steps/cfg/seed/LoRAs, etc.) are assembled consistently across `reimageApparel.js` vs. `pictureBook.js`'s own SD config panel vs. whatever the outfit-reimage call path actually sends to the SD backend — that's a different, deeper claim the markup convergence doesn't prove. Not yet reproduced or diagnosed this session. **Next step:** reproduce via `ensureSharedTestUser()`, capture the actual failing request payload sent to the SD service and compare field-by-field against a working reimage call (e.g. the charPerson portrait reimage path) to find exactly which parameter(s) differ or are missing; check whether `reimageApparel.js` and `pictureBook.js`'s apparel-reimage path independently assemble the SD request object rather than sharing one code path. This is very likely a case for KI-13's broader cross-view-consistency umbrella, not a one-line fix.

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

### KI-27. PictureBook wizard's own chatConfig auto-resolve intermittently 404s deep in a full Playwright run despite an identical create-then-resolve sequence succeeding via direct REST — OPEN (2026-07-22)
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

### KI-19. `uri`/object-link field: broken URI + wrong tab placement — FIXED (2026-07-11, Stephen)
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
