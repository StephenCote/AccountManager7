# Ux Feature Flags — Design Review

Scope: **AccountManagerUx752 only.** Service-side notes are recorded in §6 as follow-up, not as
part of this design's implementation scope.

Status of the premise: the task was framed as "not started." It is in fact **largely built** — a
13-feature manifest, lazy route loading, menu gating, profiles, a REST config endpoint, an admin
toggle UI, and five Vitest suites all exist. What follows is a review of that implementation and
the changes needed to reach the stated goal ("start out neat/simple, easily tailored").

---

## 1. What exists today

| Piece | Location | State |
|---|---|---|
| Feature manifest (id, label, deps, `required`, lazy `routes`, `menuItems`) | `AccountManagerUx752/src/features.js:4-128` | 13 features |
| Profiles (`minimal`/`standard`/`full`/`gaming`/`enterprise`/`compliance`) | `features.js:130-139` | client-only |
| Enable/disable + dep resolution | `features.js:146-186` | works |
| Lazy route merge | `features.js:188-203`, `router.js:278-285` | works |
| Menu gating | `topMenu.js:76-77`, `asideMenu.js:85-89` | partial (§3.4) |
| Profile resolution at login | `router.js:221-239` | server-first |
| Server config endpoint | `AccountManagerService7/.../FeatureConfigService.java` | per-**user** (§3.1) |
| Admin toggle UI | `src/features/featureConfig.js` | needs reload to apply |
| Unit tests | `src/test/{features,featureConfig,iso42001,accessRequests,webauthn}.test.js` | manifest-level only |

Lazy loading genuinely works — disabled features never fetch their chunk (`CardGameApp` 473 KB,
`pdf` 437 KB, `Magic8App` 167 KB, `llmTestSuite` 116 KB are all separate).

---

## 2. Verdict

The **manifest shape is right** and should be kept. Every real problem is in *ownership* — who
holds the truth, at what scope, and when it is applied. Five of the nine findings below make the
feature flag inert or wrong in normal operation, not merely awkward.

---

## 3. Findings

### 3.1 The server config is per-user, not per-org — the toggle is inert (blocking)

`FeatureConfigService.findConfigRecord()` (`:220-235`) resolves `.featureConfig` from
`user.get("homeDirectory.path")` — the **calling user's own home directory**. `PUT /rest/config/features`
is `@RolesAllowed({"admin"})` (`:111`).

Consequence: an admin's save writes a record into the *admin's* home dir. Every other user's `GET`
finds no record and falls back to `DEFAULT_FEATURES` (`:93`, `:102`) — i.e. everything. Non-admins can
never get a record written for them at all, because they cannot call `PUT`. The admin UI's own copy
says "Enable or disable features for this organization" (`featureConfig.js:139`), which is not what
the code does.

**Nothing an admin toggles affects any other user.** This is the single change that has to land
first; the rest is polish on top of a mechanism that currently does nothing.

**Fix:** resolve and store the enabled set at organization scope (org root/`/System` group record, or
an organization attribute), keyed on `organizationId`, not on the caller's home path. `GET` resolves
the org record for any authenticated user; `PUT` stays admin-only.

### 3.2 Two manifests, already drifted (blocking)

`features.js` defines 13 features. `FeatureConfigService.AVAILABLE_FEATURES` (`:52-65`) defines 12 —
**`media` is missing server-side.** Three concrete consequences:

- `DEFAULT_FEATURES` (`:48-50`) omits `media`, so every user on the default path silently loses the
  media feature even though the client's `full` profile includes it.
- `media` is absent from `KNOWN_FEATURE_IDS`, so a `PUT` containing it returns 400 (`:143-149`).
- The admin UI renders cards from `getAvailableFeatures()` (`featureConfig.js:31`), so `media`
  cannot be enabled from the UI at all.

Profiles are duplicated too, and have *also* drifted: `featureConfig.js:229-234` hardcodes a second
set of profile lists that disagree with `features.js:130-139` (its "Standard" is `["core","chat"]`;
the manifest's is `["core","media","chat"]`).

**Fix (ideal):** one JSON manifest as the single source — Objects7 classpath resource read by the
service, and imported by the Ux at build time. The client keeps only what cannot be data: the
`routes: () => import(...)` factories and `menuItems`, keyed by id.
**Fix (minimum, cheap, real):** keep both but add a contract test that fetches
`/rest/config/features/available` against the live backend and asserts id set, `deps`, and `required`
match `features.js` exactly. Import `profiles` from `features.js` into `featureConfig.js` and delete
the hardcoded copies.

### 3.3 Three runtime copies of the enabled set; changes need a page reload

`features.js` module state, the server record, and `featureConfig.js`'s own `enabledSet` are three
separate copies. On save, `featureConfig.js:56` calls `initFeatures(result.features)` — which mutates
the live set (so menus change) but never re-runs `loadFeatureRoutes()` or re-mounts the router. So
routes do not change, which is why the UI has to tell the user to reload (`:54`). Between save and
reload the app is in a mixed state: menu says disabled, route still resolves.

**Fix:** one `applyFeatures(list)` in `features.js` that resets the set, `await`s
`loadFeatureRoutes()`, and re-mounts via `page.router.refresh()`. Drop the reload message.

### 3.4 `devOnly` is broken in one menu and ineffective in the other

- `asideMenu.js:87` tests `page.devMode` — **never defined anywhere**. `pageClient.js:804` defines
  only `productionMode`. So `mi.devOnly && !undefined` is always true and *every* `devOnly` aside item
  is permanently hidden.
- `topMenu.js:77` tests `!(mi.devOnly && page.productionMode)`, and `productionMode` is only true when
  `?productionMode=true` is in the URL — so the `testHarness` button ships visible in normal builds.

Two menus, two predicates, opposite failure modes.

Also: `asideMenu.js:86` honours `adminOnly`; `topMenu.js:76-77` does not. No top item uses it today,
so this is latent rather than live.

**Fix:** one `isMenuItemVisible(mi)` exported from `features.js`, used by both menus, covering
`adminOnly`, `devOnly` (against a single defined flag — `import.meta.env.DEV` is the natural one), and
a new `roles: [...]` predicate. The router already computes `ctxRoles.iso42001Any` (`router.js:326-329`)
and nothing consumes it for menu gating; the five ISO aside items should use it.

### 3.5 "Core" is not feature-free — the main obstacle to "neat/simple"

`modelDef.js:8-146` hardcodes an **`olio` category** (`olio.charPerson`, `identity.voice`,
`olio.apparel`, `olio.wearable`, `olio.item`) and an **`ai` category** (`tool.memory`,
`system.connection`, `olio.llm.chatConfig`, `promptConfig`, `promptTemplate`, `openaiRequest`).
`asideMenu.js:84` renders `am7model.categories` **unconditionally**.

So on the `minimal` profile the sidebar still offers Olio and AI browsing, and those list views work.
`formDef.js` carries 47 olio references, and `main.js` eagerly imports `olio.js` (658 lines) and
`gameStream.js` into the core bundle. Turning off games and chat today does not produce a neat
install — it produces the same install with three buttons missing.

**Fix:** tag categories with an owning feature (`{"name":"olio", "feature":"cardGame"}`,
`{"name":"ai", "feature":"chat"}`) and filter `cats` by `isEnabled(cat.feature)` in `asideMenu.js`
(untagged ⇒ always shown). Apply the same filter to the navigator/explorer type pickers. The eager
`olio.js` import exists because `formDef.js` command buttons need `am7model._olio` synchronously
(`main.js:24`) — either make that lazy or accept it and document it as core weight.

### 3.6 Disabled deep links fail silently

Disabled routes are never registered, so `#!/cardGame` falls through to Mithril's default `/main`
with no explanation. Correct outcome, confusing presentation.

**Fix:** a catch-all that recognises a path belonging to a known-but-disabled feature and renders
"This feature is not enabled."

### 3.7 URL/build overrides are dead exactly where they'd be useful

`router.js:222-238` tries the server first for authenticated users, and the server default is
"everything." The `?features=` URL param and `__FEATURE_PROFILE__` are consulted only when the
server returned nothing — i.e. essentially never once logged in. Demo and test overrides therefore
don't work for logged-in users.

**Fix:** precedence `?features=` → server org config → `__FEATURE_PROFILE__` → `'standard'`. Gate the
URL override behind dev mode if that is a concern; it is not a security control either way (§5).

### 3.8 Build always emits every chunk

`__FEATURE_PROFILE__` is only a runtime default; it never gates `import()`. `vite build` therefore
always emits every feature chunk. Fine for lazy loading, but a "compliance appliance" artifact still
ships the card game on disk.

**Fix (only if a slim artifact is actually required):** a small Vite plugin that stubs the `import()`
factories for ids excluded by `VITE_FEATURE_PROFILE`. Low priority.

### 3.9 Architecture: the manifest is business logic in Service7

`architecture.md` — "No business logic in Service7. It is transport." `FeatureConfigService` holds the
feature catalogue as a static Java list (`:46-76`). Resolution/storage of the enabled set belongs in
Objects7 (a util + model/resource); the service should marshal only. Folding this in while doing §3.1
and §3.2 costs little and avoids a second pass.

---

## 4. Recommended order of work

Design detail for items 1–5 (the blocking gaps) is in **§4a** as D1–D5.

1. **§3.1** org-scoped config — without it nothing else has an effect.
2. **§3.2** de-duplicate the manifest (+ contract test) and **§3.9** move resolution into Objects7.
3. **§3.3** single runtime source + apply-without-reload.
4. **§3.4** unified `isMenuItemVisible`, incl. role gating.
5. **§3.5** feature-tagged categories — this is what actually delivers "neat/simple."
6. **§3.6** disabled-route feedback, **§3.7** override precedence.
7. **§3.8** build-time exclusion, only if a slim artifact is needed.

Verification per `architecture.md`: `npx vite build` + `npx vitest run` for each step; Playwright for
menu/route behaviour; `ensureSharedTestUser()`, never admin. Note that §3.1 needs a **second**
non-admin test user to prove an admin's toggle is visible to someone else — the current suites only
assert manifest arithmetic, never end-to-end propagation.

---

## 4a. Design detail for the five blocking gaps

The `Fix:` lines in §3 state intent. This section is the design. Each item gives the mechanism, the
data shape, the files touched, the edge cases that will bite, and the test that proves it.

---

### D1 — Org-scoped feature config (addresses §3.1)

**Mechanism.** Model it on `ServerConfigUtil` (`AccountManagerObjects7/.../util/ServerConfigUtil.java`),
which is the established DB-backed-config pattern in this codebase — with two deliberate departures,
because feature config is *per-organization* where server config is *deployment-global*.

**Storage.** A single `data.data` record named `.featureConfig`, `contentType: application/json`, in
each organization's `/Library/Configuration` group. `/Library` is already a per-org path
(`LibraryUtil.basePath`, resolved against `octx.getOrganizationId()` at `LibraryUtil.java:64-71`), so
this reuses an existing convention rather than inventing a location. Create the group with
`PathUtil.makePath` on first write.

Do **not** use the caller's `homeDirectory` (today's bug) and do **not** use `/System` (that would make
the setting deployment-global, which is a different product decision — see "Open question" below).

**Resolver.** New `FeatureConfigUtil` in Objects7 (`org.cote.accountmanager.util`):

```java
public static List<String> getEnabledFeatures(BaseRecord user)   // read path, any authenticated user
public static boolean setEnabledFeatures(BaseRecord user, List<String> ids)  // write path, admin
public static List<Map<String,Object>> getManifest()             // see D2
public static void invalidate(long organizationId)
```

Two departures from `ServerConfigUtil`, both mandatory:

1. **Go through `AccessPoint`, not a raw `IOSystem` search.** `ServerConfigUtil.load()` documents an
   *intentional* authorization bypass, justified because the value is deployment config with "no
   per-user or per-org answer to give," it is on a hot path, and many callers have no principal user.
   None of those hold here: the value *is* per-org, the read happens once per login, and the caller
   always has a principal user. So `architecture.md`'s "never bypass PBAC" applies with no exemption.
2. **Cache keyed by `organizationId`, never bound to a singleton.** `architecture.md` §"Per-org config
   must never be written to process-global state" prohibits resolving per-org and pushing into a
   process-wide field. A `ConcurrentHashMap<Long, Entry>` is fine — it is a keyed cache, not shared
   mutable state. There is no `applyToBoundUtils` analogue here and there must not be one.

Keep `ServerConfigUtil`'s `Entry`-with-`present` shape so "no record" (⇒ use `DEFAULT_FEATURES`) is
distinguishable from "record with an empty list" (⇒ core only). Keep the "never throws" contract:
resolution failure degrades to the default profile, it does not fail the login.

**Cache invalidation is in-process only.** A second JVM (Console7) writing the record cannot invalidate
the WAR's cache; the TTL is the only bound. State that in the javadoc rather than implying live
propagation — same honesty standard `ServerConfigUtil` holds itself to.

**Service7 changes.** `FeatureConfigService` becomes pure transport: `GET` → `getEnabledFeatures(user)`,
`PUT` → validate against the manifest then `setEnabledFeatures(user, ids)`, `GET /available` →
`getManifest()`. Role annotations stay as they are (`user` read, `admin` write). This also discharges
§3.9.

**Ux changes.** None required — [features.js](../AccountManagerUx752/src/features.js) and
`router.js:222-238` already consume the endpoint. Fix the admin UI copy at `featureConfig.js:139`,
which currently claims org scope the code does not implement; after D1 it becomes true.

**Edge cases.** Org with no record ⇒ `DEFAULT_FEATURES`. First-run/setup, before `/Library` exists ⇒
`present=false`, default profile, no error. Org deleted mid-session ⇒ cache entry expires harmlessly.
`core` is force-included on write (already done at `FeatureConfigService.java:152-154`) and must also
be force-included on *read*, so a hand-edited record cannot produce a routeless app.

**Test (this is the one the current suites cannot express).** Playwright/JUnit against the live
backend with **two** users in one org: an admin and a non-admin from `ensureSharedTestUser()`. Admin
`PUT`s a reduced set; the non-admin's `GET` must return that set. Today it returns
`DEFAULT_FEATURES` — so this test fails before the change and passes after, which is the point. Every
existing suite only asserts manifest arithmetic inside one process and would pass unchanged either way.

---

### D2 — One manifest (addresses §3.2)

**Split by nature, not by convenience.** The manifest has two kinds of field:

| Kind | Fields | Must live where |
|---|---|---|
| Data | `id`, `label`, `description`, `required`, `deps` | one place, server-authoritative |
| Wiring | `routes: () => import(...)`, `menuItems` | client only — cannot be serialized |

**Recommended.** A single JSON resource in Objects7 — `resources/features/manifest.json` — read by
`FeatureConfigUtil.getManifest()` and served verbatim by `GET /rest/config/features/available`. This
deletes the static Java list at `FeatureConfigService.java:46-76`. `features.js` keeps only the wiring,
keyed by id, and merges the server's data over it at `initFeatures()` time. This is the same
generated-from-server shape `modelDef.js` already uses ("Generated by AccountManagerService7/rest/schema").

**Minimum acceptable** if the JSON move is deferred: keep both lists but make drift impossible to ship
— a live contract test asserting the id set, `deps`, and `required` from
`GET /rest/config/features/available` match `Object.keys(features)` exactly. This would have caught the
missing `media` on the day it appeared.

**Either way, three deletions.**
- Add `media` server-side (or remove it client-side — but it has a real route module, so add it).
- `featureConfig.js:229-234`: delete the six hardcoded profile lists; `import { profiles } from '../features.js'`
  and render `Object.entries(profiles)`. The drift in "Standard" disappears by construction.
- `DEFAULT_FEATURES` should be *derived* — `profiles.full` equivalent — not a third hand-maintained list.

**Precedence for `deps`.** Server data wins for `deps`/`required`; a client-only id (present in
`features.js`, absent server-side) is a **hard error surfaced in the UI**, not a silent skip. Silent
skipping is what let `media` rot unnoticed.

---

### D3 — One runtime source of truth, applied without reload (addresses §3.3)

**Add to `features.js`:**

```javascript
async function applyFeatures(list) {   // list = server-authoritative id array
    initFeatures(list);                // reset enabledFeatures + clear loadedRoutes
    await loadFeatureRoutes();         // fetch chunks for the new set
    if (page.router) page.router.refresh();   // re-mount m.route with core + feature routes
    m.redraw();
}
```

`loadedRoutes` must be cleared by `initFeatures` (it already is, `features.js:144`) or a re-enable
after a disable would merge stale route objects.

**Re-mounting is an established path, not a new risk.** `page.router.refresh()` is `refreshApplication`,
and it is already called on login (`views/sig.js:62`) and from `pageClient.js:299`. It re-runs
`m.route(document.body, ...)` with the freshly merged route table (`router.js:278-285`). D3 reuses that
exercised path rather than inventing a second mount mechanism.

**`featureConfig.js` changes.** Keep `enabledSet` as *pending editor state* only — that is legitimate,
it is the unsaved form value. On successful save, replace `initFeatures(result.features)` (`:56`) with
`await applyFeatures(result.features)` and delete the "Reload the page to apply changes" message
(`:54`). The mixed state described in §3.3 disappears.

**Route-table caveat to handle.** If the user is *currently on* a route belonging to a
just-disabled feature, re-mounting leaves them on a path with no handler. `applyFeatures` must check
the current route against the new table and redirect to `/main` before re-mounting.

**Test.** Vitest for `applyFeatures` (set contents, `loadedRoutes` reset, redirect decision).
Playwright for the real behaviour: as admin, disable a feature, assert the menu item disappears **and**
navigating to its route no longer renders it — without a page reload.

---

### D4 — One visibility predicate (addresses §3.4)

**Define the dev flag once.** Add to `pageClient.js` beside `testMode`/`productionMode` (`:803-804`):

```javascript
devMode: (typeof import.meta !== 'undefined' && import.meta.env ? !!import.meta.env.DEV : false)
```

`import.meta.env.DEV` is the build-time truth under both Vite and Vitest, so it cannot silently
default to the wrong value the way the currently-undefined `page.devMode` does. Keep
`productionMode` — it is a separate, URL-driven override with other callers — but stop using it as a
proxy for "not dev."

**Export one predicate from `features.js`** and use it in both menus:

```javascript
function isMenuItemVisible(mi, ctx) {
    if (mi.adminOnly && !(ctx.roles && ctx.roles.admin)) return false;
    if (mi.devOnly && !page.devMode) return false;
    if (mi.roles && !mi.roles.some(r => ctx.roles && ctx.roles[r])) return false;
    return true;
}
```

Replace `topMenu.js:76-77` and `asideMenu.js:85-89` with calls to it. This fixes both bugs at once:
`devOnly` aside items become visible in dev instead of permanently hidden, `testHarness` stops
shipping visible in production builds, and `adminOnly` gains top-menu coverage.

**New `roles` field.** The router already computes `ctxRoles.iso42001Any` (`router.js:326-329`) and
nothing consumes it for menus. Tag the five ISO aside items (`features.js:66-72`) with
`roles: ['iso42001Any']`, so a user in an ISO-enabled org without any ISO role stops seeing five
dead-end menu entries. Untagged items behave exactly as today.

**Test.** Vitest table over the matrix (`adminOnly`/`devOnly`/`roles` × admin/non-admin/dev/prod) with
`page.devMode` and `page.context().roles` stubbed. Assert specifically that `testHarness` is hidden
when `devMode` is false — the current regression.

---

### D5 — Make core actually feature-free (addresses §3.5)

This is the item that delivers the stated goal, and it reaches **further than §3.5 said**: categories
are consumed in *three* places, not one.

| Consumer | Line | What it renders |
|---|---|---|
| `components/panel.js` | `:89`, `:286` | **the `/main` dashboard cards** — the landing page |
| `components/asideMenu.js` | `:84` | sidebar category list |
| `core/model.js` | `:29` | model→category reverse lookup |

Filtering only `asideMenu` would leave Olio and AI cards on the front page, which is the first thing
anyone sees. All three need the same filter.

**Data change.** Tag the feature-owned categories in `modelDef.js:8-146`:

```json
{ "name": "olio",   "label": "Olio", "feature": "cardGame", ... }
{ "name": "ai",     "label": "AI",   "feature": "chat",     ... }
```

Untagged categories (`identity`, `asset`, `process`, `policy`) are core and always shown — so the
change is additive and cannot regress the core surface. `modelDef.js` is generated from
`/rest/schema`, so `feature` should be added to the `system.modelCategory` model in Objects7 rather
than hand-patched into the generated file, or the next regeneration drops it.

**Code change.** One shared helper, used by all three consumers:

```javascript
function visibleCategories() {
    return (am7model.categories || []).filter(c => !c.feature || isEnabled(c.feature));
}
```

**Known test breakage — expected, must be updated, not deleted.** `src/test/panel.test.js:87` asserts
`cards.length === am7model.categories.length`. That becomes wrong by design. Rewrite it to assert
against `visibleCategories().length` and add cases proving the `minimal` profile drops Olio and AI
while retaining the four core categories. `src/test/model.test.js:25-26` only asserts non-emptiness
and is unaffected.

**Eager core weight — decide, don't drift.** `main.js:24` eagerly imports `olio.js` (658 lines)
because `formDef.js` command buttons need `am7model._olio` **synchronously**; `gameStream.js` is
likewise eager. Two honest options: (a) make the `formDef` command buttons resolve `_olio` lazily and
move both behind their features, or (b) accept them as core weight and say so in
`AccountManagerUx752/CLAUDE.md`. Option (a) is correct but touches `formDef.js` (47 olio references)
and should be its own change, gated on its own tests — not smuggled into D5. Option (b) is the
honest interim. Either way the sidebar and dashboard are clean after D5; only bundle bytes are at stake.

**Test.** Vitest: `visibleCategories()` under `minimal` returns the four core categories and excludes
`olio`/`ai`; under `full` returns all six. Playwright: on a `minimal`-profile org, assert the `/main`
dashboard renders no Olio or AI card and the sidebar shows no Olio or AI entry.

---

### Decision: scope is per-organization

**Settled by Stephen, 2026-08-07: per-org, for now.** D1 above is the design of record — store in each
org's `/Library/Configuration`, cache keyed by `organizationId`, `GET` readable by any authenticated
user in the org, `PUT` gated on org admin. This also makes the admin UI's existing copy
(`featureConfig.js:139`, "for this organization") true rather than aspirational.

Two consequences to hold onto, so a later change doesn't quietly break them:

- **`/System` is not the fallback.** An org with no `.featureConfig` record resolves to
  `DEFAULT_FEATURES` (D2: derived from `profiles.full`), *not* to some deployment-wide record. There is
  deliberately no two-level lookup — one scope, one answer, no precedence rules to get wrong.
- **"For now" means the per-org cache key is load-bearing.** If a deployment-wide setting is ever added
  on top, it must be a second explicitly-scoped resolver, not a repurposing of this one. Collapsing the
  keyed cache into a process-global field is the exact cross-tenant defect `architecture.md`
  §"Per-org config must never be written to process-global state" prohibits — org A's login would
  mutate what org B reads.

The "compliance appliance" profile (`features.js:136-138`) is unaffected: it remains a *client build
default* (`VITE_FEATURE_PROFILE` / `__FEATURE_PROFILE__`) for a single-tenant artifact, which is a
packaging concern, not the per-org runtime setting. Note §3.7 — that override is currently dead for
authenticated users and needs the precedence fix before an appliance build behaves as intended.

---

## 5. Feature flags are not an authorization boundary

Everything above is packaging and UX. The security boundary remains PBAC plus `@RolesAllowed`, which
is already in place on every service listed in §6. A flag must never be presented, or relied on, as
an access control — a user with a token can call any endpoint their roles permit regardless of what
the Ux has hidden.

---

## 6. Services that would need work to *block* disabled features (out of scope; note only)

Mapping verified by grepping the Ux feature modules for the REST paths they call, against
`@Path` on each service.

| Feature | Services it drives |
|---|---|
| `chat` | `/rest/chat`, `/rest/memory`, `/rest/vector`, `/rest/voice`, `/rest/mcp`, `/rest/stream` |
| `cardGame` | `/rest/game`, `/rest/olio`, `/rest/chat`, `/rest/voice` |
| `games` | `/rest/word` |
| `iso42001` | `/rest/iso42001`, `/rest/compliance` |
| `biometrics` | `/rest/face`, plus `/rest/chat`, `/rest/olio`, `/rest/voice` (magic8) |
| `schema` | `/rest/schema` |
| `webauthn` | `/rest/credential/webauthn` |
| `accessRequests` | `/rest/access` |
| `featureConfig` | `/rest/config` |
| `pictureBook` | `/rest/olio/picture-book` |
| `media` | `/rest/stream`, `/rest/pageIndex` |
| `testHarness` | `/rest/script` |

Three caveats that make naive service-level gating insufficient:

- **Shared services.** `/rest/olio`, `/rest/chat`, `/rest/voice`, `/rest/vector`, `/rest/memory` each
  serve several features. They cannot be gated on a single flag; gating must be per-endpoint or
  per-feature-union.
- **The generic model route bypasses all of it.** Even with every feature service blocked,
  `olio.charPerson`, `olio.llm.chatConfig`, and `iso42001.testConfig` stay reachable through
  `/rest/model/{type}`. Real blocking needs a model-type deny list derived from the enabled set.
- **Where the gate goes.** A single Jersey `ContainerRequestFilter` that 404s paths owned by a
  disabled feature is the right shape (transport-layer policy, consistent with Service7's role), but
  it must read the enabled set from an Objects7 resolver — see §3.9.
