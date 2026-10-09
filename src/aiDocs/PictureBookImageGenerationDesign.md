# PictureBook / ChapBook Image Generation — Feature Design & Gap Analysis

**Date:** 2026-10-09
**Status:** current-state description + gap analysis (open; no work started)
**Scope:** the images PictureBook and ChapBook produce, and what a user can and cannot control about
them: characters, settings/landscapes, style, model choice, prompts, review and regeneration.

> **How this was produced.** A read-through of the current code, summarized at the feature level.
> Nothing was run, so "works" below means "the code is written to do this", not "verified". Code-level
> findings are kept in Appendix A for when implementation starts.

Related: `PictureBookSdConfigRefactor.md` (one book style + per-scene overrides),
`PictureBookAsyncJobDesign.md`, `PictureBook2Plan.md` / `PictureBookWorkflowOverhaul.md` (workflow
graph), `imageComposite.md` (original composite approach), `KnownIssues.md` KI-59 / KI-66.

---

## 1. What the feature does today

### 1.1 PictureBook

The user uploads a story. The system pulls scenes and characters out of it, and then illustrates the
scenes one at a time.

1. **Extract** — an LLM splits the story into scenes. Each scene has a title, blurb, setting, action,
   mood and the characters present.
2. **Create characters** — each character becomes a full Olio character. Gender, race, physical
   description and outfit come from the story text, and the gaps are filled with generated values.
   No images are made at this point.
3. **Manage characters** — the user can edit characters, regenerate a character's portrait, and tag
   outfits to particular scenes.
4. **Generate images** — for each scene:
   - **Portraits:** each character in the scene gets a portrait, generated the first time the
     character appears and reused after that. Portraits keep a character looking the same from scene
     to scene.
   - **Setting / landscape (optional):** a background image of the scene's setting.
   - **Scene image:** the portraits (and the landscape, if one was made) are combined into one
     illustration of the characters acting in the setting.
5. **Review** — the user accepts, rejects, skips or retries each scene. Optionally they edit the scene
   prompt or the scene's settings, then regenerate.
6. **Read** — a book-format viewer.

**Ways of combining a scene.** The default is FLUX.2, which gives the model the portraits (and,
optionally, the landscape) as reference images. Two older modes still exist: **Kontext** (one
stitched reference strip) and **classic** (the portraits pasted onto the landscape, then repainted).

### 1.2 ChapBook

The user picks poems. Each stanza becomes a page with one illustration of the scenery: no characters
and no combining step. The LLM writes a landscape prompt for each stanza, carrying continuity from the
poem's theme and the stanzas before it. Per page, the user can edit or lock the prompt, regenerate the
prompt, re-render the image, merge pages and delete pages.

### 1.3 What the user can control

| Control | PictureBook | ChapBook |
|---|---|---|
| Image style (photograph, anime, comic, art, …) for the whole book | ✔ | ✔ (but see G-25) |
| Per-scene setting overrides | ✔ (but see G-21) | ✔ (but see G-24) |
| Per-character style | ✔ | n/a |
| Checkpoint / refiner / LoRAs | ✔ shared SD panel | ✔ shared SD panel |
| Composite engine (FLUX.2 / Kontext / classic) | pinned to FLUX.2 by the wizard | n/a |
| Include the landscape | "Skip landscape" checkbox (on by default) | n/a, landscape only |
| Edit the prompt | per-scene prompt override | per-page prompt, lockable |
| Regenerate one image | per scene | per page |
| Regenerate a portrait | via character reimage | n/a |
| Choose the LLM used for prompts and extraction | chat config picker | chat config picker |

---

## 2. Feature gap analysis

Severity: **H** = visibly missing or wrong for the user; **M** = limits quality or control;
**L** = polish.

### 2.1 Characters in scenes

| ID | Sev | Gap |
|---|---|---|
| G-1 | H | **Only two characters per scene.** Any scene with three or more people loses everyone after the second. The user isn't told, and can't choose who gets left out. There are no group scenes or crowds, and no background characters. |
| G-2 | H | **No control over the shot.** The user can't say who is in front or who is the focus, and can't set pose, expression, framing (close-up, wide), placement or relative scale. Every composite is "person on the left, person on the right". |
| G-3 | H | **Portraits don't keep up with character edits.** Once a character's portrait exists it is reused for the whole book. Changing appearance, race or default outfit in Manage Characters doesn't update it. There is no "regenerate all portraits" for a book. |
| G-4 | M | **Regenerating a portrait goes through a different pipeline** (character reimage, with a different default model). The new portrait can look different in style from the rest of the book. |
| G-5 | M | **Outfit changes per scene are fragile.** They are only supported for newer books, and the outfit described in the prompt can be wrong, including a possible "no clothes" description. |
| G-6 | M | **A scene image can show the wrong people.** If a portrait fails, a different character's portrait is used as the reference, while the text still describes the original two. |
| G-7 | L | **Characters with an unclear gender are drawn as women.** |

### 2.2 Settings / landscapes

| ID | Sev | Gap |
|---|---|---|
| G-8 | H | **Landscapes effectively don't work.** They are off by default in the UI. With the default composite engine, even unchecking "Skip landscape" doesn't produce one unless a hidden setting is also changed. So no book currently renders with a setting image, and the setting reaches the image only as text. |
| G-9 | H | **No setting workflow.** When landscapes are on, the user can't generate, preview, approve or regenerate a setting by itself, and can't edit the landscape prompt. The landscape is redrawn every time its scene is regenerated. |
| G-10 | H | **Locations aren't reused.** Two scenes in the same tavern get two unrelated backgrounds. There is no location library and no "this scene happens in the same place as scene 3". |
| G-11 | M | **A failed landscape fails the whole scene.** It should fall back to text-only. |
| G-12 | M | **Nothing checks that the setting actually shows up in the final image.** |

### 2.3 Book-level art

| ID | Sev | Gap |
|---|---|---|
| G-13 | H | **No cover image.** The viewer has a cover page, but nothing illustrates it. |
| G-14 | M | **No title page, chapter openers or series art.** Series and chapters exist as features, but have no images of their own. |

### 2.4 Style & consistency

| ID | Sev | Gap |
|---|---|---|
| G-15 | H | **A per-scene override can change the whole book.** Overriding a setting on one scene also changes the defaults for the scenes generated after it. |
| G-16 | M | **Non-photo styles are fighting the system.** Built-in "ultra realistic / photograph" wording, and a landscape exclusion list containing "cartoon, anime, illustration", pull comic, anime and art styles toward photorealism. |
| G-17 | M | **No style lock or reference.** The user can't pin a "look" — for example a reference image, a fixed seed, or one approved scene used as the style anchor — to keep the illustrations consistent across the book. |
| G-18 | L | **Image size and aspect ratio are mostly ignored.** Portraits and ChapBook pages always come out square, whatever is configured. |

### 2.5 Model selection

| ID | Sev | Gap |
|---|---|---|
| G-19 | M | **Different parts of the product use different default models.** Character reimage, PictureBook and ChapBook each default to a different checkpoint, so the same character looks different depending on where it was drawn. |
| G-20 | M | **The model choice isn't validated.** Picking a checkpoint that isn't installed on the image server only fails at render time. Nothing shows which models are actually available for which job (portrait vs. combined scene). |
| G-21 | M | **The composite engine isn't a user choice.** The wizard pins FLUX.2, and its own model and settings aren't exposed. Kontext, if chosen, runs with the wrong settings. |
| G-22 | L | **No quality presets.** There is no "fast draft" vs. "final" mode, although FLUX.2 render cost scales with step count and the number of reference images. |

### 2.6 Prompting

| ID | Sev | Gap |
|---|---|---|
| G-23 | M | **The AI-written scene prompt isn't used by the default engine.** The LLM writes a scene prompt, but FLUX.2 builds its own from the scene fields. The user is editing or reviewing a prompt that doesn't drive the image, except when they override it outright. |
| G-24 | M | **A prompt template choice can apply to the wrong step.** Picking a custom landscape-prompt template may also replace the scene-prompt template. |
| G-25 | M | **ChapBook images ignore the book style and the default quality exclusions** (no style suffix, no negative prompt). |
| G-26 | L | **Bad scene data can leak into prompts.** Missing or placeholder scene data (literal "null") can reach the prompt or break prompt generation. |

### 2.7 Generation workflow & review

| ID | Sev | Gap |
|---|---|---|
| G-27 | H | **Generation isn't a background job.** "Generate All" runs scene by scene inside the browser session. Leaving the page stops it, and Cancel doesn't stop the image currently rendering on the server. Extraction and ChapBook already support background jobs. |
| G-28 | M | **One image per attempt.** There are no variants to choose between. The workflow graph can store several versions per scene, but there is no UI to pick one. |
| G-29 | M | **No targeted fixes.** The user can't redo only the faces, only one character, or only the background. The only option is to regenerate the whole scene. |
| G-30 | M | **The workflow canvas "Regenerate" only marks a node out of date.** It doesn't regenerate anything. |
| G-31 | L | **No visibility into dropped or substituted content.** The user isn't told when characters are dropped (G-1), a landscape is skipped (G-8), or the prompt shown isn't the prompt used (G-23). |

### 2.8 ChapBook-specific

| ID | Sev | Gap |
|---|---|---|
| G-32 | H | **Book-level image settings aren't saved.** On top of that, the render dialog overwrites each page's own overrides, and single-page Regenerate ignores the book settings entirely. "Book defaults + per-page override" doesn't hold. |
| G-33 | M | **Bulk render isn't wired into the UI.** It exists as a background job, but the UI renders page by page. |
| G-34 | L | **Merging pages doesn't regenerate the merged prompt,** although it is meant to. |
| G-35 | L | **Product question: should ChapBook support characters at all?** A narrator or speaker, for example. Today it is landscapes only. |

### 2.9 Quality assurance

| ID | Sev | Gap |
|---|---|---|
| G-36 | H | **Nothing verifies image content.** No test or check confirms that a scene image contains the right characters, their outfits, or the setting. Existing tests only confirm that an image was produced. |

---

## 3. Decisions needed (Stephen)

1. **Group scenes (G-1, G-2):** how many characters per scene should be supported, and how? More
   reference images, building the scene up one character at a time, or picking the "main" two and
   letting the rest appear as unreferenced background people?
2. **Landscapes (G-8–G-11):** on or off by default? And should settings become first-class reusable
   **locations** (generated once, approved, shared across scenes) rather than a per-scene step?
3. **Cover and book art (G-13, G-14):** is a cover generated automatically from the story, or chosen
   from scene images?
4. **One model policy (G-19–G-21):** a single default checkpoint for all character art, and whether
   the composite engine and its model should be user-selectable.
5. **Background generation (G-27):** should PictureBook generation move to the same job system as
   extraction and ChapBook?
6. **ChapBook characters (G-35):** in scope or not?

## 4. Suggested order

1. **Correctness the user already hits:** G-15, G-32, G-25, G-8 (make the landscape toggle actually
   work).
2. **Settings as a real feature:** G-9–G-12 (prompt edit, preview/approve, reuse, non-fatal failure).
3. **Characters:** G-3/G-4 (portrait refresh in the book's own pipeline), then the G-1/G-2 design.
4. **Book art and workflow:** G-13, G-27, G-28.
5. **Consistency and polish:** G-16, G-17, G-19–G-22, G-31.

---

## Appendix A — Implementation notes (for later)

Code-level causes behind the gaps above, kept short. Paths are under
`AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/`. Items marked ✅ were re-read
against the source; the rest are from reading the code.

- **G-1/G-6:** the two-character cap is at `picturebook/PictureBookUtil.java:9149, 9291, 10096`. The
  prompt and the portraits pick their two characters by different rules.
- **G-8 ✅:** `PictureBookUtil.landscapeEnabled` (:3522) → `sd/SceneCompositeUtil.includesLandscapeReference`
  (:126). `flux2IncludeLandscapeRef` has no schema default and comes out `false`, so
  `flux2Defaults.json`'s `true` is never reached (documented at `SceneCompositeUtil:103-121`).
  The UI also pins `skipLandscape=true` (`AccountManagerUx752/src/workflows/pictureBook.js:240-248`).
  KI-59's "candidate to check first" is superseded by this.
- **G-9/G-11:** the landscape image is re-rendered every run (`landscapeObjectId` is written but never
  read back), and a failure throws a 500 (`PictureBookUtil:9573-9579`).
- **G-15 ✅:** `applyOverrides(common, sdConfigOverride)` runs at `:9042`, then
  `persistBookSdConfig(common)` at `:9072`. The book config is also stored in two places (meta vs.
  `olio.pb.book.sdConfig`), and they drift.
- **G-18 ✅:** `sd/SDUtil.createImage` (:1090) sets no width or height, and ignores its own `hires` and
  `seed` arguments.
- **G-19:** reimage and chat use `sd.model`. PictureBook v1 uses the request/book/random config. v2
  and ChapBook end up on the schema default `OfficialStableDiffusion/sd_xl_base_1.0`, because
  `createImage` never calls `resolveModel`. FLUX.2 uses `flux2Klein_9b`.
- **G-21:** the composite mode is decided twice (inline in `PictureBookUtil:9637` and in
  `SceneCompositeUtil`). Kontext uses SDXL steps/cfg and an SDXL model fallback; the `kontext*` fields
  are never read.
- **G-23:** FLUX.2 and Kontext compose their prompts in `sd/swarm/SWUtil.java` (:268-410, :454-494).
  The LLM `scene-image-prompt` output only reaches the image server in classic mode.
- **G-24 ✅ (mechanism):** `PictureBookUtil.callLlm(..., overrideName)` (:4181) swaps the template
  name for whatever call it is handed.
- **G-25/G-32 ✅:** `picturebook/ChapBookUtil.java:1868-1876` sends the prompt as-is, applies the
  client config over the page override, and passes `hires=false`, which is ignored. The UI's book
  panel is in memory only, and single-page Regenerate sends `{}`.
- **G-5:** `describeOutfit` runs on an unpopulated store after `selectSceneApparel` changed a
  different copy, so it can fall through to `NarrativeUtil:345-347` ("naked/nude…"). Needs a live
  repro.
- **G-26:** scene setting/action/mood aren't passed through `NarrativeUtil.isMeaningful`, and a null
  setting can throw a NullPointerException (`PictureBookUtil:3629`).
- **G-27:** `/olio/picture-book/scene/{id}/generate` is synchronous with no job and no server cancel.
- **G-30:** `PbServiceFacade.requestRegenerate` only marks nodes STALE. The workflow node runner
  (`picturebook/PbNodeExecutor.java`) also diverges from the main pipeline: different profile load,
  portrait not linked, and no SD-server fallback in `testNode`.
- **Other:** every SD write path sets `FIELD_BYTE_STORE` directly instead of going through
  `ByteModelUtil` (`SDUtil` ~781–2205, `OlioService:690`). Graph runs are left RUNNING when a scene
  throws.
- **Doc drift:** `PictureBookWorkflowOverhaul.md` ("not started", but W3/W4 are done),
  `AccountManagerUx752/aiDocs/ChapBookReviewRedesign.md` ("nothing implemented", but mostly done),
  `PictureBookUxAndOlioModelPlan.md` (A2.2/A2.3, B and C not done), and two field descriptions in
  `configModel.json` (`flux2IncludeLandscapeRef`, `negativePrompt`) that describe behaviour the code
  doesn't have.
