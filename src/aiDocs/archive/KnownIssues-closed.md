> Archived 2026-10-25: closed `KnownIssues.md` entries that had **zero inbound citations** anywhere in the repo (code, tests, rules, docs, auto-memory) at the time of the move — KI-5, 6, 26, 41, 43a, 46, 50, 51, 52, 54, 55, 57, 58, 73, 75, 76 — moved verbatim under their original `## ` group headings. **Numbering is global with `../KnownIssues.md`; never reuse a number.** The live file keeps a one-line stub per moved id. Entries cited from source or docs stay live regardless of status.

# AccountManager7 — Known Issues: closed, archived entries

## Picture Book (2026-07-06, Stephen)

### KI-26. `MediaUtil`/`ArticleUtil` path-parsing regex was ASCII-only — 404'd for names like "François"/"Duña" — FIXED ✅ (2026-07-22, Stephen)
`MediaUtil.recPattern` (`AccountManagerService7/.../util/MediaUtil.java:62-64`), used by `writeBinaryContent`'s `request.getPathInfo()` parser to split a media URL into org path / model type / subPath+name, restricted every segment to `[\sA-Za-z0-9\.]`-style character classes — no accented Latin or other Unicode letters. Any group/file/character name containing a non-ASCII letter (Stephen's examples: "Duña", "François") failed `m.find()` entirely, logging `"Unexpected path construct"` and returning 404 for that image — indistinguishable from any other broken-media-URL symptom.

**Fixed:** added `\p{L}\p{N}` (Unicode letter/digit) alongside the existing ASCII classes in `recPattern`'s group 1 (org path) and group 3 (subPath+name) — group 2 (model type, e.g. `data.data`) intentionally left ASCII-only since type names are always plain identifiers. Purely additive (no character class removed), so no behavior change for existing ASCII paths.

**Same shape of bug exists in `ArticleUtil.articlePattern`** (`AccountManagerService7/.../util/ArticleUtil.java:60`, identical ASCII-only classes) — **not fixed here**, out of scope (Stephen scoped the report to the media/image path specifically); flagged for whoever picks up article-serving non-ASCII support next.

**Verified:** `TestMediaUtilStreaming#TestRecPatternMatchesNonAsciiNames` (`AccountManagerService7`) — reflects into the private `recPattern` field and asserts real matches for `/Development/data.data/Gallery/François.png` and `.../Duña.png` (plus an ASCII regression case). Confirmed the test fails against the pre-fix pattern (via `git stash`) and passes post-fix — a genuine regression guard, not just a happy-path check.


## Ux752 — Debug tooling (2026-06-24)

### KI-6. List view: favorites / system-library mode not resetting on reload (FIXED)
Once the favorites or system-library (admin) toggle was picked, the `systemList` flag persisted across list-view reloads (a prior change stopped the embedded list/pagination from resetting, which over-broadly stopped the main list from clearing the flag). `views/list.js` now tracks a `lastRouteKey` (type + objectId) and resets `systemList` only when the route actually changes for a non-embedded/non-picker list — so the toggle persists within a view (survives redraws, which call `initParams` via `onupdate`) but clears on genuine navigation/reload.

### KI-5. Browser-console object-view context accessor (ADDED)
`views/object.js` now exposes the live object-view context for console inspection:
`__am7page.objectContext()` → `{ type, objectId, isNew, tabIndex, entity, inst, pinst, foreignData, valuesState }`
(`window.__am7page` is set in `main.js`). The closure returns current values, so it reflects what the Ux is actually using — useful for spotting bad/missing fields and errors on the viewed object.

---


## Objects7 — Vault/crypto (2026-07-13, found while verifying KI-22's fix doesn't route through another buffer-everything path)

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

### KI-46. `am7model.newPrimitive('olio.sdConfig')` uses a non-existent model name — FIXED ✅ (2026-08-10)

Both call sites (`reimage.js`, `reimageApparel.js`) now use `olio.sd.config`. One line each. The
fallback only fires when `am7sd.fetchTemplate()` returns null (server unreachable), which is precisely
when it mattered: `newPrimitive` returned null and `prepareInstance(null, form)` then threw.

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


## Objects7 / ISO42001 — schema index collision (2026-09-07, found while verifying a Docker recreate)

### KI-73. Finished STORY books show "No text yet." on every page — `sceneNoteStore` discarded the LLM's `blurb` — **FIXED 2026-10-01** (reported by Stephen on `Ourselves.doc`)

**Symptom.** A PictureBook built from an extracted document rendered every page with
`<em>No text yet.</em>` in the PB2 reader (`pictureBook.js renderPb2ScenePage`, which shows
`poemStanza || blurb || summary`). Images, titles, settings and moods were all present; only the text was
missing.

**Cause.** The extract-scenes LLM reply carries each scene's prose as `blurb` and never emits `summary`
(`summary` only exists when a wizard edit supplies it). `PictureBookUtil.sceneNoteStore` built the
persisted `data.note` JSON with `blurb = summary-or-""`, so the LLM's text was overwritten with an empty
string at persistence. Everything downstream copied that nothing faithfully: `PbPipelineUtil.dualWriteScene`
PATCHed the empty blurb onto `olio.pb.scene`, `PbServiceFacade.bookPageView` handed `/pages` empty strings,
and the reader fell through to its placeholder. Measured on the Docker DB before the fix: **0 of 8617**
scene notes had a blurb. The gated render test in `pictureBook.spec.js` never caught it because its
hand-built scene supplies `summary`.

**Fix.** `sceneNoteStore` now prefers a meaningful `summary` (a wizard edit still wins) and otherwise keeps
the LLM's `blurb`, with `NarrativeUtil.isMeaningful` guarding both so a literal `"null"` placeholder cannot
leak into the page.

**Proof.** JUnit `TestExtractChunkLoop.TestSceneNoteStoreRetainsSourceChunkAndDropsSourceText` (LLM-blurb-only
and `summary:"null"` cases); Playwright `e2e/pictureBookSceneText.spec.js` — load-safe tier proves the
persisted notes carry the exact blurb, and the `PB_SD_TESTS=1` tier rendered a scene (LLM via LiteLLM + SD),
saw `/pages` return the blurb, and screenshotted the reader displaying it (`2 passed (1.8m)`,
`[e2e-llm] route=litellm server=http://litellm:4000`). Both run as `e2etest_shared`.

**Residual.** Books extracted before the fix (Ourselves included) still have empty blurbs in their scene
notes and `olio.pb.scene` rows; they need re-extraction or the per-scene regenerate-blurb path to pick up
text. No migration was written — the scene notes hold whatever the LLM said at the time and the original
`blurb` is gone from them.

### KI-75. `GET /rest/model/{type}/{oid}/full` 404s when a nested foreign model has more than 49 fields — PostgreSQL's 100-argument function limit — **FIXED 2026-10-07**
`ModelService.getFullModelByObjectId` calls `planMost(true)`. `StatementUtil.getInnerSelectTemplate` renders
each planned nested MODEL field as one `JSON_BUILD_OBJECT(name, col, name, col, …)`, two arguments per field,
so a nested model with more than 49 column-backed fields produces a call PostgreSQL rejects with
`cannot pass more than 100 arguments to a function`. `DBSearch` catches it and returns null, and the route
answers 404 — indistinguishable from "no such record" or a PBAC denial. `olio.sd.config` (87 materialized
fields) and `olio.llm.chatConfig` (77) are over the cap, so every parent that references them was
affected: `olio.pb.book` (`archive/IssueLog-2026-08-25.md` Issue 10), `olio.llm.chatRequest`, `olio.pb.run` (and transitively
`olio.pb.workflow`). Plain by-id GET and top-level `planMost(true)` on the wide model itself always worked.

**Fix (Stephen's direction: filter the plan, not the route).** `QueryPlan.planForFields`, MOST branch,
nested plans only: `QueryPlan.countMaterializedFields(schema, fields, parentModel)` applies the same
column filter StatementUtil uses, and when the count exceeds `QueryPlan.MAX_NESTED_PLAN_FIELDS` (49) the
nested plan is replaced by `RecordUtil.getCommonFields(model)` and an INFO line is logged:
`Reducing <parent> -> <model>.<field> to common fields: N nested fields exceed the limit of 49`.
Top-level plans are untouched. Consequence for consumers: a wide child inside a `/full` response carries
identity + common fields only; project a specific field explicitly (`request:["sdConfig.steps"]`) or fetch
the child by its own id. Nothing in the PB2 reader depended on a non-common field of `sdConfig`/`chatConfig`
through `/full`; `olio.world` (45) stays under the cap.

**Verified.** `TestQueryPlanNestedLimit` (Objects7; non-admin `queryPlanLimitUser`): plan shape test and a
live `planMost(true)` find of a `chatRequest` → `chatConfig` pair; 2/2, and the find test fails with the
exact `PSQLException` when the reduction is disabled. REST on the rebuilt Docker stack as the shared user
(2026-10-07): `/full` → 200 for `olio.pb.book`, `olio.llm.chatRequest`, `olio.pb.run`; server log shows the
`Reducing …` lines for `sdConfig`, `compositeSdConfig` and `chatConfig` and no 100-argument errors.
Documented in `.claude/rules/model-api.md` § "Nested plans are capped at 49 fields".

### KI-76. Same-named users in different organizations were served as whichever one Service7 cached first — `UserPrincipal` identity was name-only — **FIXED 2026-10-07** (found 2026-10-05)
`ServiceUtil.getPrincipalUser` memoizes resolved users in a static `principalCache` keyed by `UserPrincipal`.
`UserPrincipal.equals`/`hashCode` compared **`name` alone**, so `/Development/admin` and `/System/admin`
(or any two same-named users) were one cache key: after the first logged in, a login in the second org
authenticated correctly (`AM7LoginModule` logged the right org) but every REST call was then answered as the
first org's user — wrong `organizationPath`, wrong `id`, wrong home/library groups. It looked like "the record
does not exist in this org", and it is a cross-tenant identity defect, not a cache-freshness nuisance.

**Fix.** `UserPrincipal.equals`/`hashCode` are now `(organizationPath, name)` — `organizationPath` is what
every constructor site (`AM7LoginModule`, `AM7RequestWrapper`) populates; `id` is optional there so it is not
part of the identity. No cache-key change was needed in `ServiceUtil`.

**Verified.** JUnit `TestUserPrincipalIdentity` (Service7) 3/3. Live on the Docker stack 2026-10-07 as the
non-admin `e2etest_shared`, provisioned in both `/Development` (id 19) and `/Public` (id 24): three sequential
logins Development → Public → Development each returned their own `organizationPath` and `id` from
`GET /rest/principal/`, with no `/rest/cache/clearAll` between them. Before the fix the second and third
would both have reported the Development user.

