# PictureBook imaging: use one common `olio.sd.config` (+ per-scene overrides), like CardGame

**Status:** implemented 2026-08-03 — see KnownIssues **KI-38** (FIXED). Outstanding items (fake-pass
test fix, catatone real-content regression, wizard E2E) tracked in **KI-39** and the "Implementation
status" section at the end of this doc. Design + rationale below.

## Context

The PictureBook image pipeline was built with a **parallel, custom style system** instead of the
canonical `olio.sd.config` styling the rest of the app uses. Concretely, the picturebook code:

- defines its own `ALLOWED_STYLES` (a 12-word superset including the non-canonical `illustration`/`custom`),
- carries a single-word `SceneGenerationParams.style` and injects style via `SWUtil.styleClause(...)`
  ("High quality photograph" / "Anime art style" …),
- builds a throwaway `olio.sd.config` per call that sets only the bare `style` word and **never
  populates the per-style detail fields** (`photographer`, `stillCamera`, `fashionMagazine`, …), so
  `SDUtil.getSDConfigPrompt()` can't be used and was bypassed everywhere,
- adds a separate book-level `compositionContext` art-direction anchor.

Result (observed live): portraits go through the canonical config path and get a rich style
("Fashion photography for V Magazine in 1970s … by Paul Strand"), while the scene/landscape get my
`styleClause` ("High quality photograph") — **two style systems in one composite**, plus style
drift (photograph↔anime) and no consistent composition. Images look wrong.

**Goal:** the caller (custom test **or** the Ux) supplies **one common `olio.sd.config`** (optionally a
second *alternate* config for the composite/Kontext step), with **per-scene overrides as needed** —
exactly the CardGame pattern. Style + composition then derive from that one config, consistently,
across portraits, landscape, and scene.

### Decisions (confirmed with user)
- **Drop `illustration`/`custom`** from the picturebook. Use only canonical `olio.sd.config` styles
  (art, digitalArt, photograph, anime, movie, comic, portrait, fashion, vintage, selfie). Book default
  = a canonical illustrative style (`art`).
- **Composition anchor:** keep the `compositionContext` prompt-anchor *mechanism* but **default it to
  blank** (no auto-seeded hardcoded art-direction line). Real book-wide consistency comes from the
  config's Composition/Setting/Action fields (+ optional shared reference image), CardGame-style.

## Target architecture (mirror CardGame)

CardGame's canonical pattern (to copy):
- Base config and per-item overrides are the **same model** `olio.sd.config` — no separate overrides
  model; the override view is just `forms.sdConfigOverrides`.
- `_default` = the common config; per-item entries are **deltas** (`getCardTypeDelta`,
  `cardGame/services/artPipeline.js:297-311`).
- Merge chain per image (`artPipeline.js:507-566`): start from a **random template**
  (`am7sd.buildEntity()` → REST `/olio/randomImageConfig` → `SDUtil.randomSDConfig()`), then
  `am7sd.applyOverrides(entity,_default)` → `applyOverrides(entity,delta)` → `fillStyleDefaults(entity)`.
- Style text is produced by the config (`SDUtil.getSDConfigPrompt`), never a hand-rolled clause.
- Composition consistency = config fields `bodyStyle`("Composition") / `imageSetting`("Setting") /
  `imageAction`("Action") + a shared reference image injected into every image.

PictureBook adopts the same: one common `olio.sd.config` stored on the book, optional per-scene delta,
optional alternate composite config; the backend merges (common → scene delta → fillStyleDefaults) and
uses `getSDConfigPrompt` as the single style seam.

## Changes by layer

### 1. Backend — Objects7 (the core fix; do first)
Files: `olio/sd/SDUtil.java`, `olio/sd/swarm/SWUtil.java`, `olio/picturebook/PictureBookUtil.java`.

- **Add merge helpers to `SDUtil`** mirroring the Ux `am7sd` primitives:
  - `applyOverrides(BaseRecord base, BaseRecord override)` — copy only non-null overridable fields
    (the allowlist in `components/sdConfig.js:240-262`: model/refiner/cfg/steps/hires/width/height/
    samplers/schedulers/style/denoisingStrength/bodyStyle/imageSetting/imageAction/description + the
    per-style detail fields).
  - `fillStyleDefaults(BaseRecord cfg)` — fill *missing* per-style detail fields for `cfg.style` from
    the existing pools; reuse the logic already in `randomSDConfig()`/`randomSDConfigValue`
    (`SDUtil.java:639-705`).
- **`PictureBookUtil.generateSceneImage`** (`:2826-3273`):
  - Resolve the common config: use the request's `sdConfig` if present, else the book's stored
    `.pictureBookMeta.sdConfig` (`getBookSdConfig`, `:390-397`); if still empty, `randomSDConfig()`.
    Run `fillStyleDefaults` to guarantee detail fields, then `applyOverrides(common, perSceneDelta)`.
  - Replace every `appendStyleClauseOnce(...)` / `styleClause` usage with the config style suffix
    `SDUtil.getSDConfigPrompt(mergedConfig)` on the scene + landscape prompts (the same seam
    `NarrativeUtil.getSDPrompt` uses at `NarrativeUtil.java:954`).
  - **Portraits (Stage 1, `:2963-3141`)** must carry the same style: build the portrait prompt through
    the config-style path (append `getSDConfigPrompt`) instead of `createImage` with a raw
    `description` that bypasses it (`SDUtil.createImage:760-764`).
  - **Composite/Kontext step:** use the optional *alternate* config if provided, else the common config
    rendered FLUX-friendly. Kontext needs SDXL `(...)` weighting stripped — reuse
    `SWUtil.stripSDXLWeighting` on the `getSDConfigPrompt` output.
  - Delete `SceneGenerationParams.style` (`:201`); the SD fields now come from the `olio.sd.config`
    record(s), not flattened params. Keep only non-SD knobs (chatConfig, promptOverride,
    promptTemplateOverride, isBook, useKontext).
- **`resolveScenePrompt` / `resolveLandscapePrompt`** (`:620-682`, `:832-888`): swap the `styleClause`
  suffix for `getSDConfigPrompt(config)`; keep the `prependContextOnce(loadCompositionContext(...))`
  seam (now blank-by-default).
- **Delete** `ALLOWED_STYLES` (`:136-137`) and `appendStyleClauseOnce` (`:569-575`). Leave
  `SWUtil.styleClause` in place only if a non-picturebook caller still needs it (see Risks).
- **`compositionContext`:** stop auto-seeding the hardcoded art-direction line in `createFromScenes`
  (`:2789-2804`); default the field to `""`. Keep `loadCompositionContext`/`prependContextOnce` so it
  can be set explicitly later. (Leave `pictureBook.art-direction.json` as an optional template, not
  auto-applied.)

### 2. Models — Objects7 resources
Files under `src/main/resources/models/olio/`.
- `pictureBookRequestModel.json`: already nests `sdConfig` → `olio.sd.config` (ephemeral). Add optional
  `compositeSdConfig` → `olio.sd.config` (the alternate) and optional `sdConfigOverride` →
  `olio.sd.config` (per-scene delta). Drop reliance on a top-level style string.
- `pictureBookMetaModel.json`: already has `sdConfig` + `compositionContext`. Add a `compositeSdConfig`
  slot; keep `compositionContext` (blank default).
- No new/edited `style.limit` needed — we simply stop using `illustration`/`custom`.

### 3. Service7 REST — `PictureBookService.java`
- `POST /scene/{id}/generate` (`:362-408`) and `POST /{bookObjectId}/prepare-images` (`:420-463`):
  parse the nested `sdConfig` as a full `olio.sd.config` record (common), plus optional
  `compositeSdConfig` and `sdConfigOverride`; **remove the `style` string** plumbing. Pass the
  record(s) to `PictureBookUtil` (no flattened SD scalars).
- Add `PUT /{bookObjectId}/settings` to store the book's common (+ composite) config once via
  `persistBookSdConfig` — so the test/Ux "set one config" and generation reads it back. (GET already
  exists at `:597-605`.) This is transport only; merge logic stays in Objects7.

### 4. Ux752 — `workflows/pictureBook.js`, `workflows/sceneExtractor.js`, `components/SdConfigPanel.js`
- Replace the bespoke plain `defaultSdConfig()` object (`pictureBook.js:93-115`) + `DEFAULT_SD_CONFIG`
  (`sceneExtractor.js:9-15`) with a real `olio.sd.config` instance built the CardGame/reimage way:
  `am7sd.buildEntity()` (random template) → `am7model.prepareInstance(entity, forms.sdConfig)`
  (`reimage.js:121-125`).
- Render per-scene overrides as `olio.sd.config` deltas via `forms.sdConfigOverrides` and the
  `getCardTypeDelta` + `applyOverrides` + `fillStyleDefaults` merge chain
  (`components/sdConfig.js:165-262`, `artPipeline.js:297-311`), replacing the 4-field `sceneOverrides`.
- Remove the picturebook-only `STYLE_OPTIONS`/`illustration` (`SdConfigPanel.js:13-15`); use the
  canonical style list from `forms.sdConfig`. Optional second "composite" config tab → `compositeSdConfig`.
- POST the common config to `PUT /settings` and send only the per-scene delta on `generate` /
  `prepare-images` (backend merges).

### 5. Custom test — `TestPictureBookCustom.java` (+ prompt-inspection test)
- Build **one** common `olio.sd.config` via `SDUtil.randomSDConfig()` (or a fixed canonical style with
  `fillStyleDefaults`), store it on the book via the settings endpoint/`persistBookSdConfig`; optionally
  a composite alternate; optionally a per-scene override. Delete `buildSdConfigTemplate`'s custom
  `SceneGenerationParams` style fields.

## Key reuse (don't reinvent)
- Random style generator: `SDUtil.randomSDConfig()` / `randomSDConfigValue` (`SDUtil.java:639-705`);
  Ux `am7sd.buildEntity()` → REST `/olio/randomImageConfig`.
- Style→text: `SDUtil.getSDConfigPrompt` (`SDUtil.java:581-632`) — the single seam.
- Ux merge primitives: `am7sd.applyOverrides` / `fillStyleDefaults` / `STYLE_FIELDS`
  (`components/sdConfig.js:165-262`, `:178-234`, `:22-34`); delta diff `getCardTypeDelta`
  (`artPipeline.js:297-311`); tabbed UI `cardGame/ui/deckView.js:260-317`.
- Composition/consistency: config fields `bodyStyle`/`imageSetting`/`imageAction` (`forms.sdConfig`
  `formDef.js:837-940`, `forms.sdConfigOverrides` `:1190-1319`); shared reference-image injection
  pattern `artPipeline.js:526-557`.
- FLUX weighting strip: `SWUtil.stripSDXLWeighting`.

## Risks / cross-cutting
- **`SWUtil.newKontextSceneTxt2Img` + `styleClause` are shared with `ChatService.generateScene`.** Make
  the picturebook change **additive** — a config-aware Kontext path (uses `getSDConfigPrompt` stripped
  for FLUX) that does not alter Chat's existing byte-for-byte behavior; leave `styleClause` for Chat.
- **`getSDConfigPrompt` emits SDXL `(...)`-weighted tags** meant for SDXL; fine for portraits/landscape/
  classic scene (all SDXL), but must be stripped for the Kontext composite (FLUX).
- **Editing an Objects7 model/resource means rebuild+reinstall the Objects7 jar and redeploy Service7**
  for the Ux/REST to see it (per module CLAUDE.md).

## Verification (prompts first, images last — no wasted compute)
1. **Prompt-text unit test (no SD, no LLM):** assemble the portrait + landscape + scene prompts for a
   book whose common config has a known canonical style (e.g. `art`), and assert: (a) all three contain
   the **same** `getSDConfigPrompt(config)` style substring, (b) **no** `styleClause` text anywhere,
   (c) a per-scene override changes only that scene's prompt, (d) composition fields
   (`imageSetting`/`bodyStyle`) appear consistently. This is the check that would have caught the
   original bug for free.
2. `mvn -o -pl AccountManagerObjects7 test-compile` + the prompt-inspection test green.
3. Service7: rebuild/redeploy; a REST smoke test that `PUT /settings` then `generate` uses the stored
   config (inspect returned prompt/seed, not the image).
4. **Only then** one live scene generation (Swarm up) — visually inspect the exported PNGs
   (`./export/`) to confirm portraits + landscape + scene share one coherent style. Single generation,
   not a sweep.
5. Ux: `npx vite build` + `npx vitest run`; a Playwright check that the picturebook SD panel renders a
   real `olio.sd.config` (canonical styles, no `illustration`) and per-scene override tabs.

## Suggested sequencing
Backend + models + prompt-inspection test (1,2,5-test) → REST (3) → Ux (4) → one live image (verify 4).
Each stage is independently verifiable via prompt text before any image is generated.

---

## Implementation status — 2026-08-03

**All code layers implemented.** See KnownIssues.md **KI-38** (FIXED) for the full change list.

Done + genuinely verified (SD outcomes checked, not just JUnit exit codes):
- **Backend** — `SDUtil.applyOverrides`/`fillStyleDefaults` (+ `randomSDConfig` refactor); `SWUtil`
  additive `useConfigStyle` Kontext overload; `PictureBookUtil` single `getSDConfigPrompt` seam across
  portraits/landscape/scene/Kontext, common-config resolve+merge, `ALLOWED_STYLES`/`styleClause`/
  `SceneGenerationParams.style` removed, `compositionContext` blank-default. Compiles; Service7 compiles.
- **Models** — `compositeSdConfig`/`sdConfigOverride` added; `compositionContext` optional/blank.
- **Service7** — generate/prepare-images take `olio.sd.config` records; new `PUT /{bookObjectId}/settings`.
- **Ux752** — real `olio.sd.config` + per-scene `sdConfigOverride` deltas (CardGame pattern); dropped
  `illustration`/`DEFAULT_SD_CONFIG`/bespoke object; default style `digitalArt`. `vite build` + `vitest` 337.
- **Tests** — seam test `TestSdConfigStyleSeamAndOverrideMerge` PASS; picturebook test files migrated to
  the config API; `test-compile` green.
- **Cross-area SD regression (KI-38 verify #4, extended per Stephen "regress all sdconfig areas"):**
  `TestSDStyles` randomSDConfig all-10-styles + `getSDConfigPrompt` all-11-styles PASS; `TestKontext`
  7 prompt-level tests PASS (legacy path byte-identical → ChatService unaffected); **`TestSD` live** —
  real population portrait + 6 apparel mannequins with the installed model PASS; **one live picturebook
  scene** — real image, single config style suffix `((Street art …))`, no `styleClause`.
- **Docker/stack** — `am7:latest` builds; full stack deploys on `:8443` (my pgvector on `:15433`, DB
  setup via `/AccountManagerService7/rest/setup/`, Vite dev `:8899`).

Outstanding / not done:
- **KI-39** — fix the fake-passing live test(s): `TestKontext#testKontextSceneWithOlioCharacters`
  silently returns green when SD refuses the uninstalled default model (bare `randomSDConfig()` with no
  `model`). Must set `test.swarm.model` and `fail()` loudly instead of silent `return`. **Live FLUX-Kontext
  generation is therefore NOT yet verified.**
- **Full catatone first-two-scenes real-content regression (Stephen):** run the picturebook backend
  pipeline on catatone.docx's first two scenes — composition = a dilapidated rental with Jideon
  (middle-aged Spanish man) and Duña (his drug-withdrawing teen daughter); fresh book (bump iter) to
  exercise the new seam, export + visually inspect the 2 scene composites.
- **Frontend E2E** — picturebook wizard Playwright specs (`pictureBookWizardUx`, `sdConfigFlow`) not run;
  `rangeSliderConverge` showed a failure in the **reimage** (unchanged) denoising-scale path (0–100 vs
  test's 0–1), likely pre-existing — not confirmed vs baseline.
- **Tidy-ups** — remove `illustration`/`custom` from backend `configModel.json` `style.limit`; drop the
  Ux agent's leftover `git stash@{0}`; reconsider the uninstalled `sdXL_v10VAEFix.safetensors` schema
  default model.
