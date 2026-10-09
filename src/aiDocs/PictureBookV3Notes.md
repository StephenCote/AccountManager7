# PictureBook Image Generation
## Comprehensive Scene Composition & Rendering Architecture

**Date:** 2026-10-09  
**Status:** Proposed  
**Supersedes:** SequenceDev / Sequential Multi-Character & Location Pipeline  
**Related:** `PictureBookImageGenerationDesign.md`, `PictureBookSdConfigRefactor.md`, `PictureBookAsyncJobDesign.md`, `PictureBook2Plan.md`, `PictureBookWorkflowOverhaul.md`, `imageComposite.md`

---

# 1. Executive Summary

PictureBook currently treats image generation primarily as a process of combining character portraits and an optional landscape into a scene image.

That architecture has reached its practical limits.

The most visible limitation is the two-character reference constraint, but the deeper problem is that PictureBook has no first-class representation of **how a scene should be composed**. Character identity, location, camera, framing, placement, pose, expression, style and rendering strategy are scattered across scene fields, prompts and implementation-specific generation logic.

The proposed architecture changes the central abstraction from:

> **Scene → characters + optional landscape → generated image**

to:

> **Scene → Scene Composition → Render Plan → Rendering Strategy → Variants → QA → Approved Image**

The system should describe an arbitrary illustrated scene independently of the image-generation backend. The renderer then chooses the appropriate generation strategy for that composition.

This allows PictureBook to support:

- More than two characters
- Primary and secondary characters
- Crowds and background figures
- Explicit character placement
- Pose and expression
- Camera and framing
- Reusable locations
- Location variants
- Book-wide style locking
- Targeted regeneration
- Multiple image variants
- Visual quality verification
- Dependency-aware regeneration
- Multiple image-generation backends
- Sequential regional inpainting when required
- Future rendering techniques without changing the PictureBook data model

The existing SequenceDev proposal remains an important implementation component, particularly its location library, regional inpainting strategy and asynchronous generation. However, those mechanisms should be implementations of a larger rendering architecture rather than the architecture itself.

---

# 2. Current Problems

The current PictureBook implementation has several high-severity limitations.

The most significant are:

1. Scenes are limited to two active character references.
2. Character placement, focus, pose, expression and framing are not controllable.
3. Character portrait changes do not reliably propagate to existing scenes.
4. Locations are not reusable first-class entities.
5. Landscapes cannot currently be independently generated and approved.
6. Scene regeneration can unnecessarily regenerate unchanged assets.
7. Per-scene configuration can accidentally alter subsequent scenes.
8. Image style is not reliably locked across the book.
9. The prompt shown to the user is not necessarily the prompt used by the renderer.
10. Generation is synchronous rather than a persistent background job.
11. Only one generation attempt is surfaced to the user.
12. There is no targeted regeneration of individual scene components.
13. Generated images are not automatically checked for expected content.

These problems are documented in the current feature/gap analysis. In particular, G-1/G-2 identify the two-character and composition limitations, G-8–G-12 identify the landscape problems, G-15 identifies configuration leakage, G-17 identifies the lack of style locking, G-23 identifies prompt/render divergence, G-27 identifies synchronous generation, and G-36 identifies the lack of image-content verification.

---

# 3. Design Goals

## 3.1 Primary goals

### G1 — Separate scene intent from rendering implementation

The PictureBook model must describe **what the image should contain**, not how a particular image model happens to generate it.

### G2 — Support arbitrary scene complexity

The data model must not impose a two-character limit.

A scene may contain:

- zero characters
- one character
- two characters
- several named characters
- background extras
- crowds

### G3 — Preserve character identity

Character identity should remain consistent across scenes while allowing:

- outfit changes
- pose changes
- expression changes
- scene-specific placement
- portrait/version updates

### G4 — Make locations reusable

A location should be a persistent asset that can be reused across scenes and books where appropriate.

### G5 — Make style persistent and explicit

A book should have a coherent visual identity that can be locked and inherited by scenes.

### G6 — Make regeneration granular

The system should be capable of regenerating:

- an entire scene
- a character
- a background
- a face
- clothing
- a composition region

without unnecessarily regenerating everything else.

### G7 — Support multiple rendering strategies

No single image model or generation technique should define the PictureBook architecture.

### G8 — Make generation durable

Generation must survive navigation away from the browser and provide persistent progress, failure and cancellation state.

### G9 — Make failures visible

The user must know when content was:

- dropped
- substituted
- omitted
- generated without a reference
- rendered with fallback behavior
- rejected by visual QA

### G10 — Preserve an approved result

Once the user approves an image, subsequent regeneration should never silently replace that approved image.

---

# 4. Non-Goals

This redesign does not attempt to:

- build a general-purpose Photoshop replacement
- expose every ComfyUI node to PictureBook users
- require users to understand diffusion-model internals
- require users to manually construct masks for ordinary scenes
- make every character equally prominent
- guarantee perfect identity preservation from any particular model
- make SwarmUI or ComfyUI part of the PictureBook domain model

The system should expose meaningful illustration controls while keeping implementation complexity behind the rendering service.

---

# 5. Core Architectural Principle

## Scene intent is model-independent

PictureBook should not store its primary scene representation as a prompt.

Instead, the system should maintain structured scene intent.

Conceptually:

```text
Story
  ↓
Scene
  ↓
Scene Composition
  ↓
Render Plan
  ↓
Renderer
  ↓
Variants
  ↓
Visual QA
  ↓
Approved Image
```

The scene composition is the authoritative description.

The prompt is a generated artifact.

---

# 6. Core Domain Model

## 6.1 Book

The book owns:

- scenes
- characters
- locations
- book style
- generation defaults
- approved images
- render history

```text
Book
 ├── BookStyle
 ├── Characters[]
 ├── Locations[]
 ├── Scenes[]
 └── RenderHistory
```

---

# 7. Book Style

Book Style becomes a first-class entity.

```text
BookStyle
 ├── name
 ├── styleDescription
 ├── referenceImages[]
 ├── model
 ├── checkpoint
 ├── LoRAs[]
 ├── negativePrompt / exclusions
 ├── palette
 ├── defaultAspectRatio
 ├── defaultResolution
 ├── qualityPreset
 └── locked
```

A style can be:

- inherited by every scene
- overridden by a scene
- explicitly locked

The default behavior should be:

> **Book style is inherited unless a scene explicitly overrides it.**

A scene override must never mutate the book default.

This directly addresses the existing configuration leakage problem.

---

# 8. Character Model

Characters remain persistent entities, but their visual representation becomes versioned.

```text
Character
 ├── identity
 ├── description
 ├── appearance
 ├── defaultOutfit
 ├── portraits[]
 ├── activePortraitVersion
 └── version
```

A character portrait should be generated through the same rendering infrastructure used by PictureBook rather than through an unrelated character-reimage pipeline.

This avoids the current problem where character reimages can use a different default model and produce a visually inconsistent character.

---

# 9. Outfit Model

Outfits should become explicit assets rather than prompt fragments.

```text
Outfit
 ├── name
 ├── description
 ├── referenceImage
 ├── version
 └── active
```

A scene may select:

```text
Character: Alice
Outfit: Blue Dress
```

If no scene-specific outfit is selected:

```text
Character.defaultOutfit
```

is used.

The scene renderer should never construct an outfit from an empty or stale character copy.

---

# 10. Location Model

Locations become persistent first-class entities.

```text
Location
 ├── id
 ├── name
 ├── description
 ├── visualIdentity
 ├── prompt
 ├── referenceImages[]
 ├── approvedRenders[]
 ├── activeRender
 ├── styleVersion
 └── version
```

A location can be referenced by multiple scenes.

Example:

```text
Location: The Old Tavern

Used by:
  Scene 3
  Scene 7
  Scene 12
```

This addresses the current lack of location reuse.

---

# 11. Location Identity vs. Location Render

A critical distinction:

**Location identity is not the same thing as a single background image.**

The location defines the visual identity:

> Old stone tavern, dark oak beams, large fireplace, circular tables, stained windows.

A render is one realization:

```text
Old Tavern
 ├── Day exterior
 ├── Night exterior
 ├── Interior
 └── Interior / winter
```

This allows the system to maintain continuity without requiring every scene to reuse an identical bitmap.

---

# 12. Location Approval Workflow

A location should have an independent workflow:

```text
Generate Location
       ↓
Preview
       ↓
Edit Prompt
       ↓
Regenerate
       ↓
Approve
       ↓
Lock
```

Once approved, the location render becomes available to scene rendering.

A scene may still request a location variant, but the underlying location identity remains unchanged.

---

# 13. Scene Composition

This is the central addition.

Every PictureBook scene gets a `SceneComposition`.

Conceptually:

```text
SceneComposition
 ├── location
 ├── camera
 ├── style
 ├── actors[]
 ├── backgroundElements[]
 ├── action
 ├── mood
 ├── props[]
 ├── regions[]
 └── compositionVersion
```

The composition is the authoritative description of the desired image.

---

# 14. Camera

Camera controls should be structured.

```text
Camera
 ├── shotType
 ├── framing
 ├── viewpoint
 ├── focalActor
 ├── aspectRatio
 └── customDescription
```

Initial shot types:

- Close-up
- Medium shot
- Full-body
- Wide shot
- Establishing shot

Initial viewpoints:

- Eye level
- High angle
- Low angle
- Over-the-shoulder

The system should allow natural-language descriptions as a fallback.

Example:

> Wide establishing shot from slightly above, showing the entire tavern and all four characters.

---

# 15. Scene Actors

Every character participating in a scene becomes an actor entry.

```text
SceneActor
 ├── characterId
 ├── role
 ├── priority
 ├── outfitId
 ├── pose
 ├── expression
 ├── action
 ├── position
 ├── scale
 ├── facing
 ├── region
 └── referencePolicy
```

---

# 16. Actor Roles

The product should distinguish:

| Role | Description |
|---|---|
| Hero | Main focal character |
| Supporting | Important named character |
| Named Background | Named but less prominent |
| Extra | Generic person |
| Crowd | Unnamed environmental people |

The important point is that the system does **not** convert everyone after character #2 into an indistinguishable “extra.”

Two characters may be the current renderer's reference limit while still remaining equally important in the scene model.

---

# 17. Character Priority

Priority determines rendering strategy, not whether the character exists.

Example:

```text
Scene:
  Alice    → Hero
  Bob      → Supporting
  Carol    → Supporting
  David    → Named Background
  Crowd    → Extras
```

A renderer may initially reference Alice and Bob directly, then add Carol and David through regional generation.

The data model remains complete.

---

# 18. Character Placement

Scene actors should support normalized coordinates:

```text
x
y
width
height
depth
```

For example:

```text
Alice:
  x=0.25
  y=0.60
  scale=1.1

Bob:
  x=0.70
  y=0.62
  scale=0.9
```

The user does not necessarily need to edit numeric coordinates.

The UI can provide a simple visual composition board.

---

# 19. Composition Board

The user should be able to see:

```text
┌─────────────────────────────────┐
│                                 │
│       Alice                     │
│                                 │
│                       Bob       │
│                                 │
│            Child                │
│                                 │
└─────────────────────────────────┘
```

Controls should allow:

- drag
- resize
- reorder front/back
- designate focal character
- edit pose
- edit expression
- remove from scene
- add character

The initial automatic composition should come from the LLM.

Manual editing is for correction and refinement rather than mandatory setup.

---

# 20. Background Elements

Not everything in a scene should be represented as a character.

Support structured background elements:

```text
BackgroundElement
 ├── type
 ├── description
 ├── position
 ├── scale
 └── importance
```

Examples:

- table
- fireplace
- tree
- castle
- wagon
- animal
- crowd
- furniture

This makes the scene representation more expressive than character + landscape.

---

# 21. Render Plan

The renderer receives a model-independent `RenderPlan`.

Conceptually:

```text
RenderPlan
 ├── canvas
 ├── style
 ├── location
 ├── camera
 ├── actors
 ├── backgroundElements
 ├── references
 ├── masks
 ├── prompt
 ├── exclusions
 ├── model
 ├── strategy
 └── quality
```

The render plan is generated from the scene composition.

It is immutable for a particular render attempt.

---

# 22. Prompt Architecture

Prompts should no longer be the primary scene representation.

Instead:

```text
Scene Composition
       ↓
Prompt Compiler
       ↓
Renderer-specific prompt
```

Different renderers can have different prompt compilers.

For example:

```text
Flux2PromptCompiler
KontextPromptCompiler
SDXLPromptCompiler
RegionalInpaintPromptCompiler
```

This solves the current problem where the LLM-generated prompt may not actually be what FLUX.2 uses.

---

# 23. Rendering Strategy Abstraction

PictureBook should define a renderer-independent strategy interface.

Initial strategies:

```text
DirectReference
SequentialInpaint
RegionalInpaint
TextOnly
ClassicComposite
```

The selection should be automatic.

Example:

```text
0 characters
    → TextOnly

1–2 important characters
    → DirectReference

3–5 named characters
    → SequentialInpaint

Many named characters
    → RegionalInpaint

Crowd-heavy scene
    → RegionalInpaint + text extras
```

The exact thresholds should remain configurable.

---

# 24. Direct Reference Strategy

For simple scenes:

```text
Location + Character A + Character B
             ↓
          Renderer
```

This should remain the fastest and simplest path.

It should be preferred when the number of strong references is within the selected model's practical limits.

---

# 25. Sequential Inpainting Strategy

SequenceDev's proposed architecture becomes the primary implementation for scenes exceeding the direct-reference capacity.

Conceptually:

```text
Location
   ↓
Background Base
   ↓
Primary Character(s)
   ↓
Secondary Character Region
   ↓
Additional Regions
   ↓
Harmony / Cleanup
   ↓
Final Scene
```

This allows more characters without requiring the image model to condition on all character references simultaneously.

---

# 26. Regional Rendering

Every significant actor may receive a rendering region.

Example:

```text
Region 1 → Alice
Region 2 → Bob
Region 3 → Carol
Region 4 → David
```

The renderer can process these independently while preserving:

- location
- style
- camera
- existing actors
- composition

This also enables targeted regeneration.

---

# 27. Targeted Regeneration

The system should support:

```text
Regenerate Scene
Regenerate Background
Regenerate Alice
Regenerate Bob
Regenerate Faces
Regenerate Clothing
Regenerate Region
```

For example:

```text
Regenerate Alice
```

should preserve:

- approved location
- Bob
- camera
- composition
- style
- other approved regions

Only Alice's region should be regenerated where the renderer supports it.

---

# 28. Render Variants

Every generation request may produce multiple variants.

```text
Render Attempt
 ├── Variant A
 ├── Variant B
 ├── Variant C
 └── Variant D
```

The user chooses:

> Use this image

The selected variant becomes the scene's canonical render.

Unselected variants remain available as history until cleanup.

---

# 29. Render Versioning

Every generated image must retain the configuration that produced it.

Example:

```text
Scene 12 / Render 7

Style: BookStyle v3
Location: Tavern v2
Alice: Character v4
Alice Outfit: Dress v2
Bob: Character v2
Composition: v5
Renderer: SequentialInpaint
Model: FLUX.2
Quality: Final
Seed: ...
```

This provides reproducibility and debugging information.

---

# 30. Dependency Tracking

Assets should maintain dependency relationships.

Example:

```text
Character Alice v4
       ↓
Scene 2
Scene 7
Scene 12
```

If Alice changes:

```text
Alice v5
   ↓
Affected scenes become STALE
```

The system should not automatically destroy their existing approved renders.

Instead:

```text
3 scenes are affected.

[Review]
[Regenerate All]
```

---

# 31. Staleness States

Recommended states:

```text
CURRENT
STALE
GENERATING
FAILED
REVIEW
APPROVED
REJECTED
```

A stale image remains viewable.

This is important for book editing because changing one character should not temporarily make the book appear broken.

---

# 32. Approved Images

Approval must be explicit.

```text
Generated
   ↓
Review
   ↓
Approve
```

An approved image should remain stable even if:

- the character changes
- the style changes
- the location changes
- the model changes

Those changes make the render stale but do not overwrite it.

---

# 33. Style Consistency

Style should be applied through a centralized style definition.

A style may contain:

- positive style description
- exclusions
- reference images
- model
- checkpoint
- LoRAs
- palette
- rendering quality
- aspect ratio

Scene prompts should inherit these values.

This also fixes the current ChapBook inconsistency where book-level style and exclusions can be omitted from image generation.

---

# 34. Model Selection

Model selection should be resolved centrally.

```text
ModelResolver
 ├── requested model
 ├── available models
 ├── compatible task
 └── fallback
```

Before rendering, the system verifies:

- model exists
- model is installed
- model supports the requested operation
- required workflow is available

The user should see:

```text
Model:
FLUX.2 Klein 9B ✓ Available
```

rather than discovering the problem after a failed render.

---

# 35. Backend Abstraction

PictureBook must not depend directly on SwarmUI or ComfyUI concepts.

Recommended architecture:

```text
PictureBook
     ↓
ImageGenerationService
     ↓
RenderPlan
     ↓
RendererAdapter
     ↓
SwarmUIAdapter
     ↓
ComfyUI / SD / FLUX
```

SwarmUI/ComfyUI-specific details remain inside the adapter.

This allows future backends to be introduced without changing the PictureBook domain model.

---

# 36. SwarmUI / ComfyUI

The initial renderer may use SwarmUI and ComfyUI because the regional/sequential pipeline requires capabilities beyond simple text-to-image.

The adapter may support:

- session creation
- text-to-image
- image references
- masks
- regional inpainting
- workflow execution
- progress reporting
- cancellation
- output retrieval

However, none of those concepts should leak into the PictureBook scene model.

---

# 37. Asynchronous Generation

All substantial PictureBook generation should use the background job system.

Instead of:

```text
Browser
   ↓
Generate
   ↓
Wait
   ↓
Result
```

use:

```text
Browser
   ↓
Create Job
   ↓
Return Job ID
   ↓
Background Worker
   ↓
Render
   ↓
QA
   ↓
Persist Result
```

The user may leave the page and return later.

---

# 38. Job Model

A render job should contain:

```text
RenderJob
 ├── id
 ├── bookId
 ├── sceneId
 ├── renderPlanId
 ├── status
 ├── progress
 ├── currentStage
 ├── error
 ├── cancellationRequested
 └── timestamps
```

Stages might include:

```text
Preparing
Generating Location
Generating Background
Rendering Character 1
Rendering Character 2
Rendering Character 3
Harmonizing
QA
Saving
Complete
```

---

# 39. Cancellation

Cancellation should be propagated to the actual image server where possible.

If cancellation cannot interrupt an active model invocation, the job should at minimum:

- stop subsequent stages
- discard the result if appropriate
- mark the job cancelled
- prevent stale output from becoming canonical

---

# 40. Failure Handling

A failed component should not automatically fail the entire scene.

Examples:

### Location generation fails

Fall back to:

> Text-described location without a cached background.

### Secondary character fails

Keep the scene and mark the character:

> Character could not be rendered.

### QA fails

Return the image for review rather than silently accepting it.

The system should distinguish:

```text
Render failure
```

from:

```text
Render succeeded but content failed QA
```

---

# 41. Visual QA

Every completed render should optionally pass through content verification.

Initial checks:

- expected character count
- primary characters present
- approximate identity match
- expected location elements
- outfit consistency
- gross composition errors

Result:

```text
PASS
WARN
FAIL
```

Example:

> Warning: Bob may be missing from the generated scene.

The user can then inspect or regenerate.

QA should never silently delete a render.

---

# 42. User Interface

The PictureBook UI should evolve toward a scene-oriented workflow.

## Book

```text
Book
 ├── Style
 ├── Characters
 ├── Locations
 └── Scenes
```

## Scene

```text
Scene
 ├── Story
 ├── Composition
 ├── Assets
 ├── Render
 └── Review
```

---

# 43. Scene Composition UI

Recommended layout:

```text
┌──────────────────────────────────────────┐
│ Scene 7 — The Tavern                    │
├───────────────────────┬──────────────────┤
│                       │ Characters       │
│                       │                  │
│     Composition       │ Alice   Hero     │
│       Canvas          │ Bob     Support  │
│                       │ Carol   Support  │
│                       │ David   Extra    │
│                       │                  │
├───────────────────────┴──────────────────┤
│ Camera: Wide   Location: Tavern          │
│ Mood: Mysterious                          │
├──────────────────────────────────────────┤
│ [Generate] [Variants] [Regenerate]       │
└──────────────────────────────────────────┘
```

---

# 44. Automatic vs. Manual Composition

The LLM should generate an initial composition.

For example:

> Alice stands in the foreground holding the letter while Bob watches from beside the fireplace. Carol sits at the table behind them.

The system converts this into structured actor placement.

The user may then adjust it visually.

This preserves the convenience of AI generation without surrendering control.

---

# 45. Scene Review

The review screen should clearly show:

- current image
- selected variant
- expected characters
- location
- style
- generation status
- QA status
- stale warnings

Example:

```text
✓ Alice
✓ Bob
⚠ Carol — low confidence
✓ Tavern
✓ Style

[Approve]
[Regenerate Carol]
[Regenerate Entire Scene]
```

---

# 46. Transparency

The system should explicitly report substitutions and omissions.

Examples:

> 4 characters requested. 4 rendered.

or:

> 5 characters requested. 2 used as direct references; 3 rendered through regional generation.

or:

> Location image unavailable. Scene generated using textual location description.

This replaces the current silent dropping/substitution behavior.

---

# 47. Automatic Composition Strategy

The LLM extraction process should produce:

```text
Scene
 ├── location
 ├── action
 ├── mood
 ├── camera suggestion
 ├── actors
 │    ├── role
 │    ├── priority
 │    ├── pose
 │    └── expression
 └── background elements
```

The user can edit this before generation.

---

# 48. Prompt Generation

The LLM should not be asked to create one giant final prompt.

Instead it should populate structured fields.

For example:

```json
{
  "camera": {
    "shot": "wide",
    "viewpoint": "eye_level"
  },
  "actors": [
    {
      "character": "Alice",
      "role": "hero",
      "action": "holding a letter",
      "expression": "concerned"
    }
  ],
  "location": "old_tavern",
  "mood": "mysterious"
}
```

The renderer converts this to whatever prompt representation the selected model requires.

---

# 49. Cover and Book Art

Book-level art should use the same rendering architecture.

Supported assets should include:

- Cover
- Title page
- Chapter opener
- Series art

A cover may be:

1. Automatically generated from the story.
2. Based on a selected scene.
3. Manually composed.
4. Regenerated as a dedicated composition.

A scene image should never have to be repurposed simply because the book lacks a cover-generation system.

---

# 50. ChapBook

ChapBook should eventually share the same underlying rendering infrastructure.

ChapBook can use:

```text
BookStyle
Location
SceneComposition
RenderPlan
RenderJob
Variant
QA
```

while omitting characters unless character support is intentionally enabled.

This eliminates duplicated configuration behavior between PictureBook and ChapBook.

The current ChapBook-specific problems—especially book settings being in-memory, page overrides being overwritten, and single-page regeneration ignoring book settings—should be resolved through the same inheritance/versioning architecture rather than separate fixes.

---

# 51. Shared Rendering Infrastructure

Recommended shared service:

```text
IllustrationGenerationService
```

Consumers:

```text
PictureBook
ChapBook
Character Reimage
Cover Generator
Chapter Art
Series Art
```

All use:

```text
Style
Composition
RenderPlan
RenderJob
Variant
QA
```

---

# 52. Configuration Inheritance

Use explicit inheritance.

```text
Book Defaults
      ↓
Scene Defaults
      ↓
Scene Override
      ↓
Render Override
```

Never mutate the parent when applying a child override.

For example:

```text
Book Style = Watercolor

Scene 1 = inherited Watercolor
Scene 2 = Comic
Scene 3 = inherited Watercolor
```

Changing Scene 2 must never change Scene 3.

---

# 53. Resolution and Aspect Ratio

Resolution should be part of the render plan.

Examples:

```text
Portrait page
Landscape page
Square
Custom
```

The renderer must receive explicit width and height.

These settings must not depend on ignored or implicit values.

---

# 54. Quality Presets

Introduce:

### Draft

- lower resolution
- fewer steps
- one variant
- reduced QA

### Standard

- normal resolution
- normal generation
- one or two variants

### Final

- high resolution
- multiple variants
- full QA
- optional enhancement/upscaling

This gives users a meaningful cost/time trade-off.

---

# 55. Generation Lifecycle

The complete lifecycle becomes:

```text
Story
 ↓
Scene Extraction
 ↓
Scene Composition
 ↓
Asset Resolution
 ↓
Render Plan
 ↓
Strategy Selection
 ↓
Generation Job
 ↓
Variants
 ↓
Visual QA
 ↓
User Review
 ↓
Approved Image
```

---

# 56. Data Ownership

The following should be authoritative:

| Data | Owner |
|---|---|
| Character identity | Character |
| Outfit | Outfit |
| Location identity | Location |
| Book visual identity | BookStyle |
| Scene intent | Scene |
| Composition | SceneComposition |
| Renderer settings | RenderPlan |
| Generated image | Render |
| Approval | Render |
| QA | Render/QA |
| Generation state | RenderJob |

Avoid storing the same configuration in multiple unrelated places.

This directly addresses the current documented configuration drift between book metadata and `olio.pb.book.sdConfig`.

---

# 57. Versioning Strategy

Every mutable visual asset should have a version.

```text
Character v1
Character v2

Location v1
Location v2

Style v1
Style v2

Composition v1
Composition v2
```

A render references exact versions.

This provides reproducibility and makes stale detection deterministic.

---

# 58. Migration Strategy

Existing PictureBooks should migrate without requiring users to rebuild books.

Migration should create:

1. BookStyle from current book configuration.
2. Character versions from current character records.
3. Location entities from existing scene settings where possible.
4. SceneComposition from current scene metadata.
5. Render records for existing images.
6. Dependency relationships.

Existing approved images remain valid.

They become historical renders under the new system.

---

# 59. Backward Compatibility

Existing books should continue to render even when their data is incomplete.

Missing information should receive safe defaults.

For example:

```text
No camera
 → Medium/full scene default

No location entity
 → Text-only location

No composition
 → Automatic composition

No style asset
 → Current book image settings
```

Migration should be incremental rather than destructive.

---

# 60. Implementation Phases

## Phase 1 — State and Configuration Hygiene

Fix existing correctness issues first.

Tasks:

- unify SD configuration persistence
- fix landscape defaults
- fix ChapBook settings inheritance
- fix model resolution
- fix aspect ratio/size propagation
- fix prompt-template selection
- prevent null scene data
- separate displayed prompt from renderer prompt
- fix landscape failure behavior

Success criteria:

- Book defaults do not mutate from scene overrides.
- Page overrides survive book-level operations.
- Selected models are validated before generation.
- Explicit image dimensions reach the renderer.

---

# 61. Phase 2 — Asset and Version Model

Implement:

- BookStyle
- Character versions
- Outfit entities
- Location entities
- Render records
- dependency relationships
- stale state

Success criteria:

- Every generated image records the exact assets used.
- Character changes identify affected scenes.
- Location changes identify affected scenes.
- Existing approved renders remain intact.

---

# 62. Phase 3 — Location and Style Libraries

Implement:

- location generation
- preview
- prompt editing
- approval
- reuse
- variants
- style references
- style locking

Success criteria:

- Two scenes using one location can share a location identity.
- Approved location renders can be reused.
- Scene style overrides do not modify book style.

---

# 63. Phase 4 — Scene Composition

Implement:

- SceneComposition model
- actors
- roles
- priority
- pose
- expression
- placement
- camera
- background elements
- visual composition editor

Success criteria:

- A scene is no longer limited to two characters.
- The user can designate focal characters.
- The user can change relative placement.
- The user can control basic framing.

---

# 64. Phase 5 — Rendering Strategies

Implement:

1. DirectReference
2. SequentialInpaint
3. RegionalInpaint
4. TextOnly fallback

Use the existing SequenceDev regional approach as the first multi-character implementation.

Success criteria:

- A four-character scene renders all four characters.
- Character references remain identifiable.
- Background continuity is maintained.
- VRAM usage remains within expected limits.

---

# 65. Phase 6 — Asynchronous Rendering

Migrate all substantial generation to the background job system.

Implement:

- job creation
- progress
- cancellation
- retry
- failure recovery
- persistent state
- Generate All

Success criteria:

- Leaving the browser does not terminate generation.
- Returning to the book restores progress.
- Failed scenes can be retried independently.

---

# 66. Phase 7 — Variants and Targeted Regeneration

Implement:

- multiple variants
- variant selection
- character-only regeneration
- background-only regeneration
- region regeneration
- face regeneration where supported

Success criteria:

- Users can select between generated variants.
- Fixing one character does not require regenerating the entire scene.

---

# 67. Phase 8 — Visual QA

Implement automated checks for:

- expected characters
- character count
- location
- outfit
- gross composition failures

Success criteria:

- The system detects obvious missing characters.
- QA warnings are visible to the user.
- QA never silently replaces approved content.

---

# 68. Phase 9 — Cover and Book Art

Add:

- cover composition
- title page art
- chapter art
- series art

All use the same Style, Composition and RenderPlan infrastructure.

---

# 69. Testing Strategy

Testing should occur at four levels.

## Unit tests

Test:

- inheritance
- version resolution
- dependency calculation
- renderer strategy selection
- prompt compilation
- model resolution

## Integration tests

Test:

- location generation
- regional rendering
- async jobs
- cancellation
- image persistence
- QA

## Visual regression tests

Maintain a small canonical collection of:

- one-character scenes
- two-character scenes
- four-character scenes
- crowd scenes
- repeated locations
- different styles

## End-to-end tests

At minimum:

```text
Create book
 → extract scenes
 → create characters
 → approve location
 → compose scene
 → generate variants
 → select variant
 → approve
 → modify character
 → detect stale scene
 → regenerate
```

---

# 70. Initial Acceptance Scenarios

### Scenario A — Two characters

Alice and Bob appear in a tavern.

Expected:

- both identities preserved
- approved tavern reused
- composition respected
- style consistent

### Scenario B — Four characters

Alice, Bob, Carol and David appear.

Expected:

- all four represented
- no silent character dropping
- primary references selected automatically
- additional actors generated regionally

### Scenario C — Repeated location

Scenes 3, 7 and 12 occur in the same tavern.

Expected:

- all reference the same Location entity
- approved visual identity remains consistent

### Scenario D — Character change

Alice's hair changes.

Expected:

- Alice version increments
- affected scenes become stale
- existing approved renders remain
- user can regenerate affected scenes

### Scenario E — Background-only correction

The characters are correct but the tavern is wrong.

Expected:

- user can regenerate the location/background
- character regions remain unchanged

### Scenario F — Variant selection

Three scene variants are generated.

Expected:

- user can compare them
- one becomes canonical
- others remain historical

### Scenario G — Browser navigation

Generate All is running.

Expected:

- user can leave the page
- generation continues
- progress is retained
- user can return and inspect results

---

# 71. Observability

Every render should record:

- job ID
- scene ID
- render ID
- renderer strategy
- backend
- model
- model version where available
- seed
- resolution
- steps
- configuration version
- asset versions
- generation duration
- failure reason
- QA result

This will make image-generation issues diagnosable rather than dependent on reproducing a UI action.

---

# 72. Performance Considerations

The architecture should minimize unnecessary generation.

Prefer:

```text
Reuse approved location
Reuse approved character references
Reuse unchanged regions
Generate only changed regions
```

Avoid:

```text
Regenerate complete scene
Regenerate location
Regenerate all characters
```

unless explicitly requested.

Quality presets should allow users to balance generation time and quality.

---

# 73. VRAM Strategy

The renderer should be aware of reference complexity.

The PictureBook domain should not know exact VRAM requirements.

Instead:

```text
RenderPlan
   ↓
Renderer capability analysis
   ↓
Strategy selection
```

For example:

```text
4 strong character references
       ↓
Direct FLUX reference not appropriate
       ↓
Sequential strategy
```

This keeps hardware-specific limitations inside the rendering layer.

---

# 74. Renderer Capability Model

A renderer should expose capabilities such as:

```text
supportsMultipleReferences
maxRecommendedReferences
supportsMasks
supportsRegionalInpaint
supportsImageToImage
supportsStyleReferences
supportsCancellation
```

The strategy selector uses those capabilities.

This makes the system extensible to future models.

---

# 75. Security and Reliability

External image-generation systems should be treated as unreliable services.

The adapter must handle:

- timeout
- connection failure
- invalid model
- invalid workflow
- incomplete image
- server-side failure
- cancellation
- malformed output

No external renderer failure should corrupt the PictureBook state.

---

# 76. Recommended Backend Boundaries

```text
PictureBook Domain
       │
       │ RenderPlan
       ▼
IllustrationGenerationService
       │
       ├── StrategySelector
       │
       ├── PromptCompiler
       │
       ├── ModelResolver
       │
       └── RendererAdapter
                │
                ├── SwarmUI
                ├── ComfyUI
                └── Future backend
```

This is the key architectural boundary.

---

# 77. What Happens to SequenceDev?

SequenceDev should not be discarded.

Its strongest ideas become implementations within this architecture:

### Retain

- Location Library
- background-first generation
- sequential multi-character generation
- regional inpainting
- explicit model resolution
- asynchronous jobs

### Change

Instead of:

> PictureBook is a sequential multi-character pipeline.

Use:

> PictureBook is a scene-composition system with multiple rendering strategies, one of which is sequential regional inpainting.

This distinction is important for long-term maintainability.

---

# 78. Recommended Initial Rendering Decision Tree

```text
                 Scene
                   │
                   ▼
            How many actors?
                   │
          ┌────────┼────────┐
          │        │        │
          0       1–2       3+
          │        │        │
          ▼        ▼        ▼
       TextOnly  Direct   Can renderer
                 Reference handle directly?
                              │
                         ┌────┴────┐
                        Yes        No
                         │          │
                         ▼          ▼
                      Direct    Sequential /
                                Regional
                                  Inpaint
```

This should be an implementation decision, not something the user has to understand.

---

# 79. Product Philosophy

The system should follow three principles:

### 1. AI proposes

The LLM should automatically create:

- scene composition
- actor roles
- camera
- poses
- expressions
- location descriptions

### 2. User approves

The user can:

- adjust
- regenerate
- select variants
- approve

### 3. Renderer executes

The image-generation infrastructure determines the most appropriate technical method.

This keeps the user experience simple while allowing the backend to become increasingly sophisticated.

---

# 80. Final Architecture

The recommended final architecture is:

```text
                         BOOK
                          │
            ┌─────────────┼─────────────┐
            │             │             │
            ▼             ▼             ▼
        BOOK STYLE    CHARACTERS    LOCATIONS
            │             │             │
            └─────────────┼─────────────┘
                          │
                          ▼
                        SCENE
                          │
                          ▼
                  SCENE COMPOSITION
                          │
          ┌───────────────┼────────────────┐
          │               │                │
          ▼               ▼                ▼
       CAMERA           ACTORS          LOCATION
                          │
                    ┌─────┼─────┐
                    │     │     │
                  Hero  Support Extras
                          │
                          ▼
                     RENDER PLAN
                          │
                          ▼
                  STRATEGY SELECTOR
                          │
             ┌────────────┼────────────┐
             ▼            ▼            ▼
          Direct      Sequential    Regional
         Reference     Inpaint       Inpaint
             └────────────┼────────────┘
                          ▼
                       VARIANTS
                          │
                          ▼
                      VISUAL QA
                          │
                          ▼
                     USER REVIEW
                          │
                          ▼
                     APPROVED IMAGE
                          │
                          ▼
                       BOOK PAGE
```

---

# 81. Recommendation

**Adopt this architecture instead of implementing SequenceDev literally.**

SequenceDev correctly identifies several immediate engineering fixes and proposes a practical way to overcome the current two-reference limitation. However, the long-term PictureBook architecture should not be organized around that limitation.

The fundamental domain object should be the **Scene Composition**.

The renderer should be responsible for determining how that composition is realized.

That gives PictureBook a stable foundation for:

- arbitrary numbers of characters
- reusable locations
- consistent art direction
- camera and composition control
- regional generation
- targeted fixes
- variants
- asynchronous rendering
- visual QA
- future image models
- future rendering backends

Most importantly, it prevents the system from having to be redesigned again when the next image-generation model has different reference-image capabilities.

**The architectural rule should therefore be:**

> **Model the illustration the user wants, not the limitations of the image model currently available.**