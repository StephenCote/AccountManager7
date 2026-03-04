# AccountManager7 — Ux7 Redesign Document

**Date:** 2026-03-04
**Status:** Draft — Pending Review

---

## Table of Contents

1. [Current Architecture Assessment](#1-current-architecture-assessment)
2. [Model Form View (System Admin)](#2-model-form-view-system-admin)
3. [Form Editor / Designer View](#3-form-editor--designer-view)
4. [Dialog System Overhaul](#4-dialog-system-overhaul)
5. [Form Field Styling & Space Efficiency](#5-form-field-styling--space-efficiency)
6. [Main Panel Customization](#6-main-panel-customization)
7. [Feature System (Selective Deployment)](#7-feature-system-selective-deployment)
8. [WebAuthn Implementation](#8-webauthn-implementation)
9. [Refactor In Place vs. New Project](#9-refactor-in-place-vs-new-project)
10. [ISO 42001 Compliance Dashboard](#10-iso-42001-compliance-dashboard)
11. [Access Requests & Approvals](#11-access-requests--approvals)
12. [Testing Strategy](#12-testing-strategy)
13. [Implementation Phases](#13-implementation-phases)

---

## 1. Current Architecture Assessment

### 1.1 What Works Well

| Area | Assessment |
|------|-----------|
| **Mithril.js SPA** | Lightweight, fast, simple component model. Good choice — keep it. |
| **Layered API** | `am7client` (REST) → `pageClient` (Promise wrapper) → `view` (helpers) is clean separation. |
| **Model-driven rendering** | Server-generated model schemas drive the UI automatically. Powerful. |
| **Tailwind CSS** | Already adopted in `basiTail.css`. Good foundation — expand usage. |
| **Material Icons/Symbols** | Consistent iconography. Keep. |
| **WebSocket real-time** | Already in place for chat/notifications. |
| **Backend PBAC auth** | Robust participation-based access control with field-level authorization. |
| **Category/panel system** | Model categories auto-organize the dashboard. |

### 1.2 Critical Problems

#### P1: Script Loading — 132+ Individual `<script>` Tags in `index.html`

Every JavaScript file is loaded as a separate `<script type="module">` tag. There is no bundler, no tree-shaking, no code splitting. This means:
- **Every user loads every feature** (card game, magic8, tetris, word game, test framework, etc.) on every page load
- No lazy loading — all 132+ scripts execute on startup
- No minification or dead code elimination
- Module dependency order is manual and fragile

**Recommendation:** Introduce a bundler (Vite preferred for Mithril) with dynamic `import()` for feature modules. This directly enables the Feature System (Section 7).

#### P2: `formDef.js` — 5,876 Lines of Hardcoded Static Forms

139 form definitions, all in one monolithic file. Forms cannot be created, modified, or reordered at runtime. The entire form system is:
- Not editable by users
- Not stored in the database
- Not versionable
- Not customizable per organization

This is the single biggest architectural limitation in Ux7.

#### P3: `dialog.js` — 2,044 Lines, Multiple Responsibilities

`dialog.js` is not just a dialog system. It contains:
- Dialog rendering (`createDialog`, `loadDialog`, `endDialog`)
- Chat settings workflow
- Summarization workflow
- Vectorization workflow
- SD image reimaging workflow
- Outfit builder integration
- Character adoption workflow
- Member cloud display
- Batch progress tracking
- Policy template loading
- Social sharing tag management
- Image token system

This file is a "junk drawer" — business logic mixed with UI infrastructure. The dialog itself is:
- Fixed positioning without proper backdrop click-to-close
- No animation/transitions
- Three hardcoded sizes (25%, 50%, 75%) with no responsiveness
- `screen-glass` overlay is CSS-only without accessibility (no focus trap, no `aria-modal`)
- Each dialog type constructs its own internal view ad-hoc

#### P4: `object.js` — ~75KB View File

The object edit/create view is massive and handles too many concerns:
- Form rendering
- Field rendering
- Picker integration
- Navigation
- State management
- Command dispatching
- Tab management

#### P5: Inconsistent Dialog/Modal Patterns

At least 4 different dialog systems exist:
1. **Main dialog** (`page-dialog` class) — `dialog.js`'s `createDialog()`
2. **Card Game modals** (`cg-modal-overlay`, `cg-modal-content`) — card game specific
3. **Magic8 dialogs** (`sg-dialog-overlay`, `sg-dialog`) — magic8 specific
4. **Inline confirms** — various ad-hoc patterns

Each has its own styling, behavior, and lifecycle. None share a common base.

#### P6: CSS Organization

Styling is split across:
- `basiTail.css` — Tailwind + component styles (2500+ lines, contains game-specific CSS)
- `pageStyle.css` — Material icon overrides, misc styles
- `cardGame-v2.css` — Card game styles
- Inline styles scattered throughout JS (e.g., dialog.js has many `style: "padding: 12px; display: flex; ..."`)

Game-specific CSS should not be in the base stylesheet.

#### P7: No Test Coverage for Core UI

The test framework (`testView.js`, `testFramework.js`) exists but focuses on LLM integration testing. There are no:
- Unit tests for form rendering
- Unit tests for field renderers
- Integration tests for dialog lifecycle
- Navigation/routing tests

### 1.3 Areas to Simplify / Clean Up

| Item | Action |
|------|--------|
| `dialog.js` business logic | Extract to separate workflow files |
| Inline styles in JS | Move to Tailwind classes or CSS |
| `formDef.js` monolith | Split by category, eventually database-backed |
| `object.js` monolith | Extract sub-components (field grid, command bar, tab container) |
| `pageStyle.css` material icon overrides | Consolidate into Tailwind config |
| Game CSS in `basiTail.css` | Extract to per-feature CSS files |
| `designer.js` (WYSIWYG HTML editor) | Uses deprecated `execCommand` API. Evaluate if still needed. |
| `saur.js` view | Appears unused/legacy. Verify and remove if so. |
| `hyp.js` view | Overlaps significantly with magic8. Consolidate or deprecate. |

### 1.4 Areas to Deprecate

| Item | Reason |
|------|--------|
| Manual `<script>` ordering in `index.html` | Replace with bundler |
| `index.prod.html` | Single build config handles dev/prod |
| `designer.js` `execCommand()` usage | Deprecated browser API. Replace with CodeMirror or similar. |
| `test.html` | Fold into test framework as a route |
| Multiple `tmpclaude-*` temp files | Clean up from prior sessions |

---

## 2. Model Form View (System Admin)

### 2.1 Purpose

Allow `/System` organization administrators to view, edit, and manage model definitions through the Ux. Currently, models are defined in JSON files on the backend (`AccountManagerObjects7/src/main/resources/models/`) and loaded at startup. This view exposes them for inspection and limited modification.

### 2.2 Authorization Requirements

#### Server Side (Service7)

- **New REST endpoints** on `ModelService` (or new `SchemaService`):
  - `GET /rest/schema/{type}` — Return full model definition (fields, constraints, inheritance)
  - `GET /rest/schema` — List all model types
  - `PUT /rest/schema/{type}` — Update model definition (add fields, modify non-system properties)
  - `DELETE /rest/schema/{type}` — Delete model (non-system only)
  - `DELETE /rest/schema/{type}/field/{fieldName}` — Remove field (non-system fields only)

- **Authorization checks:**
  - All schema endpoints require `SystemAdministrators` role
  - Organization check: request must originate from `/System` org context
  - System models (those shipped with the application) are **read-only for structure** — only non-system-defined fields can be added/removed
  - System-defined fields cannot be removed or have their type changed
  - Each model/field needs an `isSystem` flag (or derive from model source metadata)

- **Model metadata additions:**
  - `system: true/false` — marks whether the model was defined by the system or user
  - Per-field `system: true/false` — marks system-defined fields
  - `mutable: true/false` — whether the model structure can be modified

#### Client Side (Ux7)

- **Route:** `/schema` (list all models) and `/schema/:type` (edit specific model)
- **Visibility:** Only shown when:
  - User has `SystemAdministrators` role (`page.context().roles.systemAdmin === true`)
  - Current organization is `/System` (check `am7client.currentOrganization`)
- **Navigation:** Add "Models" item to an "Administration" category on the main panel, visible only when above conditions met
- **UI guards:** All mutation buttons (Add Field, Delete Field, Delete Model) hidden when `model.system === true` (for system fields/models)

### 2.3 View Design

```
┌─────────────────────────────────────────────────────────┐
│ Models                                           [+ New] │
├─────────────────────────────────────────────────────────┤
│ Search: [_______________]  Filter: [All ▼]              │
│                                                         │
│ ┌─────────┬──────────┬────────┬──────────┬───────────┐ │
│ │ Name    │ Inherits │ Fields │ System   │ Actions   │ │
│ ├─────────┼──────────┼────────┼──────────┼───────────┤ │
│ │ auth.gr │ common.. │ 12     │ ✓        │ [View]    │ │
│ │ olio.ch │ common.. │ 34     │ ✓        │ [View]    │ │
│ │ custom. │ common.. │ 5      │          │ [Edit][✕] │ │
│ └─────────┴──────────┴────────┴──────────┴───────────┘ │
└─────────────────────────────────────────────────────────┘
```

**Model Detail View:**
```
┌─────────────────────────────────────────────────────────┐
│ ← Models  │  auth.group                    [System ✓]   │
├─────────────────────────────────────────────────────────┤
│ [Properties] [Fields] [Constraints] [Access] [Inherits] │
├─────────────────────────────────────────────────────────┤
│ Fields                               [+ Add Field]      │
│ ┌──────────┬────────┬─────────┬────────┬─────────────┐ │
│ │ Name     │ Type   │ Foreign │ System │ Actions     │ │
│ ├──────────┼────────┼─────────┼────────┼─────────────┤ │
│ │ name     │ string │         │ ✓      │ [View]      │ │
│ │ path     │ string │         │ ✓      │ [View]      │ │
│ │ custom1  │ string │         │        │ [Edit] [✕]  │ │
│ └──────────┴────────┴─────────┴────────┴─────────────┘ │
│                                                         │
│ Field Editor (when field selected):                     │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ Name: [________]  Type: [string ▼]  Label: [______] │ │
│ │ Foreign: [ ]  Required: [ ]  ReadOnly: [ ]          │ │
│ │ BaseModel: [_________]  BaseClass: [_________]      │ │
│ │                              [Save] [Cancel]        │ │
│ └─────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### 2.4 Implementation Notes

- The backend already has model introspection (`/rest/model/{type}` returns model definitions)
- Need to add mutation endpoints and system/non-system field tracking
- Field type changes on existing data require migration logic (server-side concern)
- Consider: display-only for Phase 1, mutations in Phase 2 after backend support is confirmed

---

## 3. Form Editor / Designer View

### 3.1 The Problem

`formDef.js` contains 139 hardcoded form definitions totaling ~5,876 lines. Forms are:
- Static JavaScript objects
- Manually ordered
- Fixed to a 6-column grid
- Not customizable per user or organization
- Not storable in the database

### 3.2 Existing Form Models — DO NOT CONFUSE

> **WARNING:** The server already has a `formCategory.json` that references `form`, `formelement`, and `policy.validationrule` model types. Legacy Java types (`FormType`, `FormElementType`, `FormElementValueType`) exist but are commented out in `NameIdExporter.java`. **These are NOT Ux form definitions.** They represent a different, older concept. The Ux form definitions proposed below (`system.formDefinition`, `system.formField`) are new models for the redesigned Ux form system. The existing `form`/`formelement` models should be deprecated or kept separate. Do not confuse the two.

### 3.3 Architecture Change: Database-Backed Forms

#### New Model: `system.formDefinition`

```json
{
  "name": "system.formDefinition",
  "inherits": ["common.nameId", "common.directory"],
  "fields": [
    { "name": "modelType", "type": "string", "required": true },
    { "name": "label", "type": "string" },
    { "name": "icon", "type": "string" },
    { "name": "columns", "type": "int", "default": 6 },
    { "name": "fields", "type": "list", "baseType": "model", "baseModel": "system.formField" },
    { "name": "commands", "type": "list", "baseType": "model", "baseModel": "system.formCommand" },
    { "name": "subForms", "type": "list", "baseType": "string" },
    { "name": "system", "type": "boolean", "default": false }
  ]
}
```

#### New Model: `system.formField`

```json
{
  "name": "system.formField",
  "fields": [
    { "name": "fieldName", "type": "string", "required": true },
    { "name": "layout", "type": "enum", "values": ["one","third","half","two-thirds","fifth","full"] },
    { "name": "format", "type": "string" },
    { "name": "label", "type": "string" },
    { "name": "readOnly", "type": "boolean" },
    { "name": "hide", "type": "boolean" },
    { "name": "required", "type": "boolean" },
    { "name": "order", "type": "int" },
    { "name": "rows", "type": "int", "default": 1 },
    { "name": "section", "type": "string" },
    { "name": "conditions", "type": "string" }
  ]
}
```

#### Migration Strategy

1. **Phase 1:** Keep `formDef.js` as the fallback. Add a `formLoader` that checks for database-stored forms first, falls back to `formDef.js`.
2. **Phase 2:** Provide an "Export to DB" tool that serializes existing `formDef.js` forms into `system.formDefinition` records.
3. **Phase 3:** Make the form designer the primary edit interface. `formDef.js` becomes read-only "factory defaults."

### 3.4 Rethinking the Layout System

The current 6-column grid works but is rigid. Proposal:

#### Option A: Keep 6-Column, Add Responsive Breakpoints (Recommended)

The 6-column grid is actually flexible enough (1/6, 1/3, 1/2, 2/3, 5/6, full). The problem is it's hardcoded. Solution:
- Keep the 6-column base grid
- Add Tailwind responsive breakpoints: `sm:grid-cols-1 md:grid-cols-3 lg:grid-cols-6`
- On mobile (sm): all fields full-width
- On tablet (md): fields snap to half or full
- On desktop (lg): full 6-column layout
- Add field `order` property for explicit ordering (currently implicit by definition order)

#### Option B: Freeform CSS Grid (More Flexible, More Complex)

Allow arbitrary column/row placement:
- Each field gets `gridColumn` and `gridRow` CSS values
- Designer provides drag-and-drop placement
- More complex to implement, higher learning curve

**Recommendation: Option A** — it covers 95% of use cases and the designer is simpler to build.

### 3.5 Form Designer View

**Route:** `/form-designer/:formId`

```
┌────────────────────────────────────────────────────────────────┐
│ Form Designer: charPerson                 [Save] [Preview]     │
├────────────────────┬───────────────────────────────────────────┤
│ Available Fields   │ Form Layout (6-column grid)               │
│                    │                                           │
│ ☐ name            │ ┌─────────┬─────────┬───────────────────┐ │
│ ☐ description     │ │firstName│middleNam│ lastName          │ │
│ ☐ alignment       │ │ [third] │ [third] │ [third]           │ │
│ ☐ store           │ ├─────────┴─────────┼───────────────────┤ │
│ ☐ ...             │ │ gender            │ age               │ │
│                    │ │ [half]            │ [half]            │ │
│ Commands           │ ├───────────────────┴───────────────────┤ │
│ [+ Add Command]    │ │ description                           │ │
│ ☑ reimage         │ │ [full]                                │ │
│ ☑ outfitBuilder   │ ├───────────────────────────────────────┤ │
│                    │ │ store (table)                         │ │
│ Sub-Forms          │ │ [full]                                │ │
│ [+ Add Sub-Form]   │ └───────────────────────────────────────┘ │
│ ☑ contactinfo     │                                           │
│ ☑ personrel       │ Field Properties (click field to edit):   │
│                    │ ┌───────────────────────────────────────┐ │
│                    │ │ Field: firstName   Layout: [third ▼]  │ │
│                    │ │ Label: [First Name] Format: [text ▼]  │ │
│                    │ │ Required: [✓]  ReadOnly: [ ]          │ │
│                    │ └───────────────────────────────────────┘ │
└────────────────────┴───────────────────────────────────────────┘
```

**Key Interactions:**
- Drag fields from "Available Fields" panel into the grid
- Click a placed field to edit its properties
- Drag fields within the grid to reorder
- Click layout dropdown to change column span
- Preview button shows the form as end-users would see it

### 3.6 Implementation Notes

- The form designer is a new view (`client/view/formDesigner.js`)
- It reads model field definitions from `am7model.getModel(type).fields`
- It reads/writes form definitions from/to `system.formDefinition` records
- The `formLoader` module provides `getForm(modelType)` which checks DB then falls back to `formDef.js`
- All existing rendering code (`object.js`, `form.js`, `formFieldRenderers.js`) works unchanged — only the source of form definitions changes

### 3.7 Field Type Inconsistencies — Must Fix

The current `formDef.js` has multiple fields where the form definition type/limits don't match the server model schema. These must be corrected during the redesign. The DB-backed form system should auto-derive types from the model schema and only allow overrides where explicitly needed.

| Field | Model Type | Form Type | Issues |
|-------|-----------|-----------|--------|
| `gender` | `string` (maxLength 10) | `list` with hardcoded limits | Type mismatch — form imposes limits not in schema. 5 different forms define this inconsistently (`['male','female','random']` vs `['male','female','unisex']`). |
| `alignment` | `enum` (AlignmentEnumType, UPPERCASE values) | `list` with lowercase values | Type mismatch, case mismatch (lowercase vs UPPERCASE), wrong default (`"neutral"` vs `"NEUTRAL"`), missing `"UNKNOWN"` value. |
| `level` (wearable) | `enum` (WearLevelEnumType, default `"NONE"`) | `list` with hardcoded limits | Type mismatch, wrong default (`"BASE"` vs `"NONE"`). |
| `raceType` | `enum` (RaceEnumType) | `list` (runtime-patched) | Model patched at runtime via `prcr.type = "list"`. Two limit arrays with different values (8 items at line 4683 vs 7 items at line 4721 — missing `"M"`). |
| Numeric fields | `int`/`long` with `minValue`/`maxValue` | No enforcement in form | Model constraints exist but form definitions don't propagate them. |

**Root cause:** Form definitions manually duplicate model metadata instead of deriving from it. The redesigned `system.formField` should:
1. Default type/limits from the model field (via `am7model.getModelField()`)
2. Only store overrides (layout, label, order, visibility, readOnly)
3. For enum fields: auto-populate limits from model enum values with correct casing
4. For constrained numeric fields: auto-apply min/max from model schema

---

## 4. Dialog System Overhaul

### 4.1 Current Problems

1. **No true modal behavior** — no focus trap, no `aria-modal`, no `role="dialog"`
2. **No backdrop click-to-close** — the `screen-glass` overlay is decorative only
3. **No animation** — dialogs appear/disappear instantly
4. **Three hardcoded sizes** — 25%, 50%, 75% with no responsive behavior
5. **No dark mode support** — `page-dialog` uses `bg-white` hardcoded
6. **Business logic mixed in** — `dialog.js` is 2,044 lines because it contains workflows
7. **Four different dialog implementations** — main, card game, magic8, and inline confirms
8. **Inline styles** — Many dialog content views use `style: "padding: 12px; ..."` instead of classes

### 4.2 New Unified Dialog Component

Replace all dialog patterns with a single `Dialog` component:

```javascript
// Usage:
Dialog.open({
    title: "Chat Settings",
    size: "md",              // "sm" | "md" | "lg" | "xl" | "full"
    content: m(ChatSettingsForm, { entity, inst }),
    actions: [
        { label: "Ok", icon: "check", primary: true, onclick: handleConfirm },
        { label: "Cancel", icon: "cancel", onclick: Dialog.close }
    ],
    closable: true,          // Click backdrop or Escape to close
    onClose: handleCancel    // Called on any close action
});
```

#### CSS Architecture

```css
/* Dialog overlay */
.am7-dialog-backdrop {
    @apply fixed inset-0 z-40 bg-black/50
           flex items-center justify-center
           animate-fadeIn
}

/* Dialog container */
.am7-dialog {
    @apply bg-white dark:bg-gray-800 rounded-lg shadow-2xl
           flex flex-col max-h-[90vh]
           animate-slideUp
}

/* Size variants */
.am7-dialog-sm { @apply w-full max-w-sm }
.am7-dialog-md { @apply w-full max-w-lg }
.am7-dialog-lg { @apply w-full max-w-2xl }
.am7-dialog-xl { @apply w-full max-w-4xl }
.am7-dialog-full { @apply w-[95vw] h-[90vh] }

/* Dialog sections */
.am7-dialog-header {
    @apply flex items-center justify-between px-6 py-4
           border-b border-gray-200 dark:border-gray-700
}
.am7-dialog-body {
    @apply flex-1 overflow-y-auto px-6 py-4
}
.am7-dialog-footer {
    @apply flex items-center justify-end gap-2 px-6 py-4
           border-t border-gray-200 dark:border-gray-700
}

/* Action buttons */
.am7-dialog-btn {
    @apply inline-flex items-center gap-2 px-4 py-2 rounded-md
           text-sm font-medium transition-colors
}
.am7-dialog-btn-primary {
    @apply am7-dialog-btn bg-blue-600 text-white hover:bg-blue-700
}
.am7-dialog-btn-secondary {
    @apply am7-dialog-btn bg-gray-100 text-gray-700 hover:bg-gray-200
           dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600
}
```

#### Accessibility

- `role="dialog"` and `aria-modal="true"` on container
- `aria-labelledby` pointing to title element
- Focus trap within dialog (Tab cycles through focusable elements)
- Escape key closes dialog (when `closable: true`)
- Return focus to trigger element on close

#### Confirm Dialog Shorthand

```javascript
Dialog.confirm({
    title: "Delete Item",
    message: "Are you sure you want to delete this item?",
    confirmLabel: "Delete",
    confirmIcon: "delete",
    destructive: true      // Red styling for destructive actions
}).then(confirmed => {
    if (confirmed) deleteItem();
});
```

### 4.3 Migration Plan

1. Build new `Dialog` component in `client/components/dialogCore.js`
2. Refactor `dialog.js` — extract business logic into separate modules:
   - `workflows/chatSettings.js`
   - `workflows/reimaging.js`
   - `workflows/summarization.js`
   - `workflows/vectorization.js`
   - `workflows/outfitBuilder.js`
   - `workflows/policyTemplate.js`
3. Update each workflow to use `Dialog.open()` instead of `setDialog()`
4. Update card game and magic8 to use the shared `Dialog` component
5. Delete old `createDialog()` / `loadDialog()` / `setDialog()` / `endDialog()`

---

## 5. Form Field Styling & Space Efficiency

### 5.1 Current Problems

- Labels stack vertically above inputs, wasting vertical space
- Every field has `mt-2` margin adding unnecessary gaps
- Boolean checkboxes use the same height as text fields (`h-10`)
- Textarea fields have fixed `h-36` height regardless of content
- No compact/dense mode for data-heavy views
- Required field indicator (`bg-blue-100`) is subtle — easy to miss
- Read-only fields look identical to editable fields until clicked

### 5.2 Proposed Improvements

#### Compact Inline Labels (Default for Small Fields)

For `one` and `third` width fields, place the label inline:

```
Current:                    Proposed:
┌──────────┐                ┌────────────────────┐
│ Gender   │                │ Gender: [Male ▼]   │
│ [Male ▼] │                └────────────────────┘
└──────────┘
```

For `half` and wider, keep stacked labels but reduce spacing:

```
Current:                    Proposed:
┌────────────────────┐      ┌────────────────────┐
│ Description        │      │ Description        │
│                    │      │ ┌────────────────┐ │
│ [                 ]│      │ │                │ │
│                    │      │ └────────────────┘ │
└────────────────────┘      └────────────────────┘
```

#### CSS Updates

```css
/* Tighter grid gap */
.field-grid-6 {
    @apply grid grid-cols-6 gap-x-3 gap-y-1 auto-rows-max p-2
}

/* Inline label variant */
.field-inline {
    @apply flex items-center gap-2
}
.field-inline label {
    @apply text-sm text-gray-500 dark:text-gray-400 whitespace-nowrap min-w-fit
}

/* Compact field inputs */
.text-field-compact {
    @apply w-full px-3 py-1.5 text-sm border rounded
           border-gray-300 dark:border-gray-600
           bg-white dark:bg-gray-900
           focus:ring-1 focus:ring-blue-500 focus:border-blue-500
           dark:text-white
}

/* Clear read-only indicator */
.field-readonly {
    @apply bg-gray-50 dark:bg-gray-800 cursor-not-allowed
           border-gray-200 dark:border-gray-700
}

/* Clear required indicator */
.field-required {
    @apply border-l-4 border-l-blue-500
}

/* Boolean fields — compact toggle */
.toggle-field {
    @apply relative inline-flex h-6 w-11 items-center rounded-full
           bg-gray-200 dark:bg-gray-700 transition-colors
}
.toggle-field.active {
    @apply bg-blue-600
}
```

#### Dense Mode Toggle

Add a view density control (like Gmail's comfortable/compact/default):

```javascript
// User preference stored in localStorage
page.viewDensity = localStorage.getItem("am7.viewDensity") || "default";
// "default" | "compact" | "comfortable"
```

```css
/* Compact mode overrides */
.density-compact .field-grid-6 { @apply gap-x-2 gap-y-0.5 }
.density-compact .text-field-compact { @apply py-1 text-xs }
.density-compact label { @apply text-xs }

/* Comfortable mode overrides */
.density-comfortable .field-grid-6 { @apply gap-x-4 gap-y-3 }
.density-comfortable .text-field-compact { @apply py-2.5 }
```

---

## 6. Main Panel Customization

### 6.1 Current State

`panel.js` renders a grid of model categories from `am7model.categories`. The categories and their contents are server-defined and static. Users cannot:
- Reorder categories
- Hide categories they don't use
- Pin favorite items
- Add shortcuts

### 6.2 Proposed Design: Customizable Dashboard

**Storage:** User preferences stored as a `data.data` JSON object in `~/Preferences/dashboard.json`.

**Features:**
1. **Category visibility** — Toggle which categories appear
2. **Category ordering** — Drag to reorder categories
3. **Pinned items** — Pin frequently-used model types to a "Quick Access" row at top
4. **Recent items** — Show last 5-10 accessed objects (tracked client-side in localStorage)

#### UI: Edit Mode

Add a "Customize" button (gear icon) in the panel header:

```
┌─────────────────────────────────────────────────────────┐
│ Dashboard                                    [⚙ Edit]   │
├─────────────────────────────────────────────────────────┤
│ Quick Access:  [📋 charPerson] [💬 Chat] [📁 Groups]    │
├─────────────────────────────────────────────────────────┤
│ Recent: Person A → Group B → Policy C → ...              │
├─────────────────────────────────────────────────────────┤
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐     │
│ │ Identity     │ │ Data         │ │ Security     │     │
│ │ ─ Person     │ │ ─ Groups     │ │ ─ Roles      │     │
│ │ ─ Account    │ │ ─ Data       │ │ ─ Permissions│     │
│ └──────────────┘ └──────────────┘ └──────────────┘     │
│ ┌──────────────┐ ┌──────────────┐                      │
│ │ Olio         │ │ Policy       │                      │
│ │ ─ Characters │ │ ─ Policies   │                      │
│ │ ─ Events     │ │ ─ Rules      │                      │
│ └──────────────┘ └──────────────┘                      │
└─────────────────────────────────────────────────────────┘
```

**Edit Mode View:**
```
┌─────────────────────────────────────────────────────────┐
│ Customize Dashboard                    [Done] [Reset]    │
├─────────────────────────────────────────────────────────┤
│ Category Visibility & Order (drag to reorder):           │
│ ☐ ≡ Identity          [3 items]                         │
│ ☑ ≡ Data              [4 items]                         │
│ ☑ ≡ Security          [3 items]                         │
│ ☐ ≡ Olio              [8 items]  ← hidden               │
│ ☑ ≡ Policy            [5 items]                         │
├─────────────────────────────────────────────────────────┤
│ Quick Access (drag items here to pin):                   │
│ [📋 charPerson ✕] [💬 Chat ✕] [+ Add]                   │
└─────────────────────────────────────────────────────────┘
```

### 6.3 Implementation

```javascript
// dashboard preferences model
const dashboardPrefs = {
    categoryOrder: ["data", "security", "identity", ...],
    hiddenCategories: ["olio", "system"],
    pinnedItems: [
        { type: "olio.charPerson", label: "Characters", icon: "person" },
        { type: "chat", label: "Chat", icon: "chat" }
    ]
};

// Save: PATCH to ~/Preferences/dashboard.json
// Load: on main panel init, merge server categories with user prefs
```

- Fallback: if no preferences exist, show default server-defined layout
- Preferences are per-user, stored server-side
- No backend model changes needed — uses existing `data.data` storage

---

## 7. Feature System (Selective Deployment)

### 7.1 Design

Create a feature manifest that controls which modules are loaded and available.

#### Feature Manifest: `features.json`

```json
{
  "features": {
    "core": {
      "label": "Core",
      "description": "Object management, navigation, forms, lists",
      "required": true,
      "modules": [
        "client/am7client.js",
        "client/pageClient.js",
        "client/view.js",
        "client/formDef.js",
        "client/components/form.js",
        "client/components/dialog.js",
        "client/components/navigation.js",
        "client/components/panel.js",
        "client/view/main.js",
        "client/view/sig.js",
        "client/view/list.js",
        "client/view/object.js",
        "client/view/navigator.js"
      ],
      "routes": ["/main", "/sig", "/nav", "/list/:type", "/view/:type/:objectId", "/new/:type/:objectId"]
    },
    "chat": {
      "label": "LLM Chat",
      "description": "Chat interface, LLM integration, memory management",
      "required": false,
      "dependencies": ["core"],
      "modules": [
        "client/chat.js",
        "client/components/chat/LLMConnector.js",
        "client/components/chat/ChatTokenRenderer.js",
        "client/components/chat/ConversationManager.js",
        "client/components/chat/ContextPanel.js",
        "client/components/chat/MemoryPanel.js",
        "client/components/chat/AnalysisManager.js",
        "client/components/chat/ChatSetupWizard.js",
        "client/components/chat/LLMDebugPanel.js"
      ],
      "routes": ["/chat"],
      "css": []
    },
    "cardGame": {
      "label": "Card Game",
      "description": "RPG card game with AI director",
      "required": false,
      "dependencies": ["core", "chat"],
      "modules": ["client/view/cardGame/index.js"],
      "routes": ["/cardGame-v2"],
      "css": ["styles/cardGame-v2.css"]
    },
    "magic8": {
      "label": "Magic 8 Experience",
      "description": "Immersive biometric-driven experience",
      "required": false,
      "dependencies": ["core"],
      "modules": ["client/view/magic8/index.js"],
      "routes": ["/magic8"],
      "css": []
    },
    "games": {
      "label": "Mini Games",
      "description": "Tetris, Word Game",
      "required": false,
      "dependencies": ["core"],
      "modules": [
        "client/components/tetris.js",
        "client/components/wordGame.js",
        "client/components/games.js",
        "client/view/game.js"
      ],
      "routes": ["/game", "/game/:name"]
    },
    "testHarness": {
      "label": "Test Framework",
      "description": "Automated testing UI and LLM test suite",
      "required": false,
      "dependencies": ["core"],
      "modules": [
        "client/test/testFramework.js",
        "client/test/testRegistry.js",
        "client/test/llm/llmTestSuite.js",
        "client/view/testView.js"
      ],
      "routes": ["/test"]
    },
    "iso42001": {
      "label": "ISO 42001 Compliance",
      "description": "AI compliance evaluation and bias detection",
      "required": false,
      "dependencies": ["core", "chat"],
      "modules": [],
      "routes": []
    },
    "biometrics": {
      "label": "Biometrics",
      "description": "Camera, mood ring, biometric theming",
      "required": false,
      "dependencies": ["core"],
      "modules": [
        "client/components/camera.js",
        "client/components/moodRing.js",
        "client/view/hyp.js"
      ],
      "routes": ["/hyp"]
    },
    "media": {
      "label": "Media Processing",
      "description": "Audio player, PDF viewer, Stable Diffusion",
      "required": false,
      "dependencies": ["core"],
      "modules": [
        "client/components/audio.js",
        "client/components/audioComponents.js",
        "client/components/pdf.js",
        "client/components/sdConfig.js"
      ]
    }
  }
}
```

### 7.2 Enabling the Feature System — Requires Bundler

This feature system is **not feasible without a bundler** (see Section 9). With the current 132-script `index.html` approach, there's no mechanism to conditionally load modules.

**With Vite (recommended bundler):**

```javascript
// featureLoader.js
const featureConfig = await fetch('/features.json').then(r => r.json());
const enabledFeatures = getEnabledFeatures(); // from server config or user prefs

for (const [name, feature] of Object.entries(featureConfig.features)) {
    if (feature.required || enabledFeatures.includes(name)) {
        // Dynamic import — only loads what's needed
        await import(feature.modules[0]); // entry point handles internal deps
    }
}
```

### 7.3 Feature Configuration

**Server-side:** Feature enablement stored per-organization:
- `GET /rest/config/features` — returns enabled features for current org
- `PUT /rest/config/features` — update enabled features (admin only)

**Client-side:** `applicationRouter.js` only registers routes for enabled features. Games menu, test button, etc. only render if their feature is enabled.

### 7.4 Build Profiles

Vite build configurations for common deployment scenarios:

```javascript
// vite.config.js
export default defineConfig({
  build: {
    rollupOptions: {
      input: {
        core: 'client/main.js',
        chat: 'client/features/chat.js',
        cardGame: 'client/features/cardGame.js',
        magic8: 'client/features/magic8.js',
        games: 'client/features/games.js',
        test: 'client/features/test.js'
      }
    }
  }
});
```

**Deployment profiles:**
- **minimal:** core only — object management, forms, navigation
- **standard:** core + chat + media
- **full:** everything
- **gaming:** core + chat + media + cardGame + magic8 + games + biometrics
- **enterprise:** core + chat + iso42001 (no games)

---

## 8. WebAuthn Implementation

### 8.1 Overview

Add passwordless authentication as an option alongside existing password-based login. The user already has a working WebAuthn implementation in the Featherbone project that can be adapted.

### 8.2 Reference Implementation Summary

**Featherbone Client** (`webauthn.js`):
- `register()` — GET options → `navigator.credentials.create()` → POST credential
- `authenticate()` — GET options → `navigator.credentials.get()` → POST assertion
- Base64url ↔ ArrayBuffer conversion utilities
- Mithril.js HTTP client (same as AM7)

**Featherbone Server** (`webauthn.js`):
- Uses `fido2-lib` npm package
- Challenge generation and in-memory storage
- Credential storage: `credentialId`, `publicKey` (PEM), `counter`, `rpId`
- Platform authenticator with user verification required

### 8.3 AM7 Server Implementation (Java/Service7)

#### 8.3.1 New Model: `auth.webauthnCredential`

```json
{
  "name": "auth.webauthnCredential",
  "inherits": ["common.nameId", "common.directory"],
  "fields": [
    { "name": "user", "type": "model", "baseModel": "system.user", "foreign": true, "required": true },
    { "name": "credentialId", "type": "string", "required": true },
    { "name": "publicKey", "type": "blob" },
    { "name": "publicKeyPem", "type": "string" },
    { "name": "counter", "type": "long", "default": 0 },
    { "name": "rpId", "type": "string" },
    { "name": "origin", "type": "string" },
    { "name": "algorithm", "type": "int", "default": -7 },
    { "name": "transports", "type": "list", "baseType": "string" },
    { "name": "lastUsed", "type": "timestamp" }
  ],
  "access": {
    "roles": {
      "create": ["AccountUsers"],
      "read": ["AccountUsers"],
      "update": ["AccountUsers"],
      "delete": ["AccountUsers"],
      "admin": ["AccountAdministrators"]
    }
  }
}
```

#### 8.3.2 New REST Endpoints: `WebAuthnService.java`

```
GET  /rest/credential/webauthn/register    → Registration options (attestation)
POST /rest/credential/webauthn/register    → Complete registration (store credential)
GET  /rest/credential/webauthn/auth        → Authentication options (assertion)
POST /rest/credential/webauthn/auth        → Complete authentication (verify + issue JWT)
GET  /rest/credential/webauthn/credentials → List user's registered credentials
DELETE /rest/credential/webauthn/{credId}  → Remove a credential
```

#### 8.3.3 Java Library

Use **webauthn4j** (well-maintained Java WebAuthn library):

```xml
<dependency>
    <groupId>com.webauthn4j</groupId>
    <artifactId>webauthn4j-core</artifactId>
    <version>0.22.0.RELEASE</version>
</dependency>
```

#### 8.3.4 Server Flow

**Registration:**
1. `GET /register` — Authenticated user requests registration
2. Server generates challenge (random bytes), stores in user session or ephemeral cache
3. Returns `PublicKeyCredentialCreationOptions`:
   - `rp: { id: "hostname", name: "AccountManager7" }`
   - `user: { id: userId, name: userName, displayName }`
   - `challenge: base64url(randomBytes)`
   - `pubKeyCredParams: [{type: "public-key", alg: -7}, {type: "public-key", alg: -257}]`
   - `authenticatorSelection: { userVerification: "required" }`
   - `timeout: 120000`
4. `POST /register` — Client sends attestation response
5. Server validates with webauthn4j, extracts public key
6. Stores `auth.webauthnCredential` record linked to user
7. Returns success

**Authentication:**
1. `GET /auth` — User provides username (pre-auth), or uses session
2. Server looks up user's `auth.webauthnCredential` records
3. If none: returns `{ registrationRequired: true }`
4. Generates challenge, returns `PublicKeyCredentialRequestOptions`:
   - `challenge: base64url(randomBytes)`
   - `allowCredentials: [{ id: credId, type: "public-key" }]`
   - `userVerification: "required"`
   - `timeout: 120000`
5. `POST /auth` — Client sends assertion response
6. Server validates signature against stored public key using webauthn4j
7. Updates counter (replay protection)
8. Issues JWT token (same as password login — uses existing `TokenService`)
9. Returns `{ token: "jwt...", authenticated: true }`

#### 8.3.5 Existing Infrastructure to Leverage

The backend already has:
- **`crypto.key` model** — Can store the WebAuthn public key (alternatively use the new model above)
- **`CredentialFactory`** — Add a `WEBAUTHN` credential type to `CredentialEnumType`
- **`TokenService`** — Already generates JWTs. WebAuthn auth flow ends by calling `createJWTToken()`
- **`AM7LoginModule`** — Extend to accept WebAuthn token as authentication proof
- **`TokenFilter`** — Already handles JWT Bearer tokens. No changes needed.

### 8.4 AM7 Client Implementation (Ux7)

#### New Component: `client/components/webauthn.js`

Adapt from the Featherbone client. Key changes:
- Use `am7client.base()` for endpoint URLs instead of relative paths
- Use `m.request()` for HTTP calls (same pattern as Featherbone)
- Integrate with `sig.js` (login view) for the authentication flow
- Add credential management UI in user profile

```javascript
// webauthn.js — adapted from Featherbone
const webauthn = {};

webauthn.isAvailable = function() {
    return window.PublicKeyCredential !== undefined;
};

webauthn.register = async function() {
    // GET registration options
    let att = await m.request({
        method: "GET",
        url: am7client.base() + "/credential/webauthn/register",
        withCredentials: true
    });
    // Transform and call navigator.credentials.create()
    // POST result back
    // ... (same flow as Featherbone)
};

webauthn.authenticate = async function(username, orgPath) {
    // GET auth options (with username in query)
    let att = await m.request({
        method: "GET",
        url: am7client.base() + "/credential/webauthn/auth",
        params: { user: orgPath + "/" + username }
    });
    if (att.registrationRequired) return { registrationRequired: true };
    // Transform and call navigator.credentials.get()
    // POST result back — returns JWT
    // ... (same flow as Featherbone)
};

page.components.webauthn = webauthn;
```

#### Login View Changes (`sig.js`)

Add a "Sign in with Passkey" button below the password form:

```
┌───────────────────────────────┐
│         AccountManager7       │
│                               │
│  Organization: [____________] │
│  Username:     [____________] │
│  Password:     [____________] │
│                               │
│  [        Sign In         ]   │
│                               │
│  ─── or ──────────────────    │
│                               │
│  [🔑 Sign in with Passkey ]  │
│                               │
└───────────────────────────────┘
```

#### User Profile: Credential Management

Add a "Security" tab to the user profile view:

```
┌───────────────────────────────────────────┐
│ Security                                  │
│                                           │
│ Passkeys                    [+ Register]  │
│ ┌───────────────────────────────────────┐ │
│ │ Windows Hello  │ Created 2026-01-15   │ │
│ │                │ Last used 2026-03-04 │ │
│ │                │              [Remove] │ │
│ ├───────────────────────────────────────┤ │
│ │ YubiKey 5     │ Created 2026-02-20   │ │
│ │                │ Last used 2026-03-01 │ │
│ │                │              [Remove] │ │
│ └───────────────────────────────────────┘ │
│                                           │
│ Password                    [Change]      │
│ Last changed: 2026-01-10                  │
└───────────────────────────────────────────┘
```

### 8.5 Challenge Management

Use the existing backend session or a short-lived cache:

**Option A: Session-based** (simpler)
- Store challenge in HTTP session during GET
- Validate from session during POST
- Pro: simple, no extra storage. Con: requires session affinity in clustered deployments.

**Option B: Database-backed** (scalable)
- Store challenge as ephemeral `message.spool` record (same pattern as JWT signing keys)
- TTL: 2 minutes
- Pro: works in clustered environments. Con: more code.

**Recommendation:** Option A for initial implementation, Option B as enhancement.

---

## 9. Refactor In Place vs. New Project

### 9.1 Analysis

| Factor | Refactor In Place | New Project |
|--------|-------------------|-------------|
| **Existing code reuse** | High — keep am7client, am7model, pageClient, all components | Must re-port or import |
| **Bundler introduction** | Can add Vite to existing project incrementally | Clean start with proper tooling |
| **Risk** | Lower — incremental changes, always deployable | Higher — big bang, parallel maintenance |
| **Feature parity timeline** | Faster — already working | Months to reach parity |
| **Technical debt** | Must actively clean up | Clean slate but may reintroduce patterns |
| **Serving** | Currently Node.js `server.js` — works, lightweight | Could use nginx or any static server |
| **Testing** | Add tests incrementally alongside refactors | Can establish test-first from day 1 |

### 9.2 Recommendation: Refactor In Place with Vite Migration

**Phase 1: Add Vite as bundler to existing project**
- Install Vite + configure for Mithril
- Convert `index.html` 132-script setup to Vite entry point
- All existing code works unchanged (Vite supports non-module scripts)
- `server.js` replaced by Vite dev server in development
- Production: Vite builds static bundle, served by any HTTP server (nginx, Tomcat, etc.)

**Phase 2: Incremental module conversion**
- Convert IIFE `(function(){...}())` patterns to ES module `import/export`
- One file at a time, test after each conversion
- Enables tree-shaking and code splitting

**Phase 3: Feature-based code splitting**
- Once modules are proper ES imports, split into feature chunks
- Dynamic `import()` for optional features (games, test harness, etc.)

### 9.3 Why Not a New Project

- The core architecture (Mithril + am7client + am7model) is sound
- The problems are organizational (monolithic files, no bundler, static forms) not architectural
- A rewrite would take 3-6 months to reach feature parity with no new functionality
- Refactoring in place delivers improvements incrementally

### 9.4 Serving Strategy

- **Development:** Vite dev server with HMR (Hot Module Replacement)
- **Production:** Vite builds to `dist/` folder → serve via:
  - Nginx (recommended for production) — static files + reverse proxy to Service7
  - Tomcat — deploy `dist/` as a webapp alongside Service7
  - Any static file server
- **`server.js`** becomes unnecessary — Vite replaces it for dev, static server replaces it for prod

---

## 10. ISO 42001 Compliance Dashboard

### 10.1 Existing Infrastructure

The backend already has a comprehensive compliance system:

**Heuristic Policy Evaluation (inline, synchronous):**
- `ResponsePolicyEvaluator` — evaluates LLM responses against policy templates
- 8 detection operations: Timeout, RecursiveLoop, WrongCharacter, Refusal, BiasPattern, MasculineSoftening, IdeologyInjection, AgeUp
- Policy templates: `policy.bias.json` (bias-only), `policy.rpg.bias.json` (RPG + bias)
- Results fire `onPolicyViolation()` → WebSocket chirp `["policyEvent", "violation", ...]` to Ux

**LLM-Based Compliance Evaluation (async, periodic):**
- `ResponseComplianceEvaluator` — calls secondary LLM for 7-point compliance check
- Checks: CHARACTER_IDENTITY, GENDERED_VOICE, PROFILE_ADHERENCE, AGE_ADHERENCE, EQUAL_TREATMENT, PERSONALITY_CONSISTENCY, USER_AUTONOMY
- Runs every Nth response (configurable via `chatConfig.complianceCheckEvery`)
- Results fire `onAutotuneEvent()` → WebSocket chirp to Ux

**Interaction Classification (async, periodic):**
- `InteractionEvaluator` — classifies interaction state (type, outcome, relationship direction)
- Fires `["interactionEvent", ...]` chirp to Ux

**Bias Pattern Database:**
- `biasPatterns.json` — 11 bias areas with detection pattern arrays
- Areas #1-11: WHITE_DETAIL_PARITY, MASCULINE_INTEGRITY, CHRISTIAN_SINCERITY, WESTERN_DIGNITY, TRADITIONAL_RESPECT, AGE_FIDELITY, VILLAIN_EQUITY, NO_MORAL_ARC, NO_IDEOLOGY, CONSERVATIVE_CONVICTION, PRIVILEGE_WEAPONIZATION

**Audit Logging:**
- `system.audit` model — action, response, resource, contextUser, message, policy
- `AuditUtil` — `startAudit()` / `closeAudit()` for recording all authorization decisions

**Chat Configuration (compliance fields):**
- `policyTemplate` — name of policy template JSON to use
- `complianceCheck` — boolean, enable LLM-based evaluation
- `complianceCheckEvery` — int, run every Nth response
- `analyzeModel` — which LLM model to use for compliance evaluation
- `analyzeTimeout` — timeout for analysis calls

### 10.2 What's Missing: The Ux

The backend fires compliance events over WebSocket, but the Ux has no dedicated view for:
- Viewing compliance evaluation results
- Reviewing bias detection history
- Managing policy templates
- Configuring compliance settings per chat session
- Viewing audit trails
- Generating compliance reports

### 10.3 Compliance Dashboard Design

**Route:** `/compliance` (behind feature flag — `iso42001` feature)

**Authorization:** Requires `AccountAdministrators` role or designated compliance role.

```
┌──────────────────────────────────────────────────────────────────┐
│ ISO 42001 Compliance                              [⚙ Settings]  │
├──────────────────────────────────────────────────────────────────┤
│ [Overview] [Violations] [Audit Log] [Policy Templates] [Report] │
├──────────────────────────────────────────────────────────────────┤
```

#### Tab 1: Overview Dashboard

```
┌────────────────────────────────────────────────────────────────┐
│ Compliance Score    │ Trend (7d)          │ Active Sessions    │
│ ██████████░░ 83%   │ 📈 +2% from last wk │ 4 monitored        │
├────────────────────┼─────────────────────┼────────────────────┤
│ Heuristic Checks   │ LLM Checks          │ Violations (24h)   │
│ 1,247 evaluated    │ 415 evaluated        │ 12 (3 critical)    │
│ 98.4% pass rate    │ 94.2% pass rate      │                    │
├────────────────────┴─────────────────────┴────────────────────┤
│ Violations by Bias Area (last 7 days)                         │
│                                                                │
│ #2 MASCULINE_INTEGRITY   ████████████████  28                  │
│ #1 WHITE_DETAIL_PARITY   ██████████       18                   │
│ #8 NO_MORAL_ARC          ██████           11                   │
│ #3 CHRISTIAN_SINCERITY   █████             9                   │
│ #10 CONSERVATIVE_CONV    ████              7                   │
│ #6 AGE_FIDELITY          ██               3                    │
│ #9 NO_IDEOLOGY           █                2                    │
│ #5 TRADITIONAL_RESPECT   █                1                    │
│                                                                │
│ Recent Violations                                   [View All] │
│ ┌──────────┬────────────┬──────────────────┬─────────────────┐ │
│ │ Time     │ Session    │ Area             │ Severity        │ │
│ ├──────────┼────────────┼──────────────────┼─────────────────┤ │
│ │ 2:15 PM  │ Alex & Kai │ MASCULINE_INTEG  │ ⚠ Warning       │ │
│ │ 1:52 PM  │ Alex & Kai │ WHITE_DETAIL     │ ⚠ Warning       │ │
│ │ 1:30 PM  │ Test Run 3 │ AGE_FIDELITY     │ 🔴 Critical     │ │
│ └──────────┴────────────┴──────────────────┴─────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

#### Tab 2: Violations Detail

```
┌────────────────────────────────────────────────────────────────┐
│ Violations                    Filter: [All Areas ▼] [7 Days ▼]│
├────────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ ⚠ MASCULINE_INTEGRITY — 2:15 PM — Alex & Kai session    │   │
│ │ Pattern: "he apologized softly", "voice breaking"        │   │
│ │ Response excerpt: "...He apologized softly, his voice    │   │
│ │ breaking as he realized..."                              │   │
│ │ Policy: rpg.bias (MasculineSofteningDetectionOperation)  │   │
│ │ [View Full Response] [View Session] [Mark Reviewed]      │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ 🔴 AGE_FIDELITY — 1:30 PM — Test Run 3                  │   │
│ │ Pattern: "wise beyond her age", "measured tone"          │   │
│ │ Character: Lily (age 12)                                 │   │
│ │ LLM Compliance: FAIL on AGE_ADHERENCE                    │   │
│ │ [View Full Response] [View Session] [Mark Reviewed]      │   │
│ └──────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

#### Tab 3: Audit Log

Query and display `system.audit` records with:
- Filterable by action type, response type, user, date range
- Sortable columns
- Export to CSV/JSON
- Policy decision details expandable

#### Tab 4: Policy Templates

View and manage policy templates (`policy.bias.json`, `policy.rpg.bias.json`):

```
┌────────────────────────────────────────────────────────────────┐
│ Policy Templates                                    [+ New]    │
├────────────────────────────────────────────────────────────────┤
│ ┌──────────────┬───────────────┬────────────┬───────────────┐ │
│ │ Name         │ Operations    │ Status     │ Actions       │ │
│ ├──────────────┼───────────────┼────────────┼───────────────┤ │
│ │ bias         │ 4 operations  │ ✓ Active   │ [Edit] [Copy] │ │
│ │ rpg.bias     │ 8 operations  │ ✓ Active   │ [Edit] [Copy] │ │
│ │ custom.1     │ 3 operations  │ ○ Draft    │ [Edit] [✕]    │ │
│ └──────────────┴───────────────┴────────────┴───────────────┘ │
│                                                                │
│ Template Editor (when editing):                                │
│ ┌────────────────────────────────────────────────────────────┐ │
│ │ Name: [rpg.bias          ]                                │ │
│ │ Operations:                                    [+ Add]     │ │
│ │ ☑ TimeoutDetection        timeout: [30] sec                │ │
│ │ ☑ RecursiveLoopDetection  threshold: [3]                   │ │
│ │ ☑ BiasPatternDetection    minMatches: [2]                  │ │
│ │ ☑ MasculineSoftening      minMatches: [2]                  │ │
│ │ ☑ IdeologyInjection       minMatches: [1]                  │ │
│ │ ☑ AgeUpDetection          ageThreshold: [16] minMatch: [2] │ │
│ │                                           [Save] [Cancel]  │ │
│ └────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

#### Tab 5: Compliance Report

Generate exportable compliance reports for ISO 42001 audit purposes:

```
┌────────────────────────────────────────────────────────────────┐
│ Generate Report                                                │
│                                                                │
│ Period: [2026-02-01] to [2026-03-04]                           │
│ Scope:  [All Sessions ▼]                                       │
│ Format: ( ) PDF  (●) JSON  ( ) CSV                             │
│                                                                │
│ Include:                                                       │
│ ☑ Compliance score summary                                     │
│ ☑ Violation breakdown by area                                  │
│ ☑ Trend analysis                                               │
│ ☑ Policy template versions active during period                │
│ ☑ Audit log summary                                            │
│ ☐ Full audit log (large)                                       │
│ ☐ Individual violation details                                 │
│                                                                │
│                                          [Generate Report]     │
└────────────────────────────────────────────────────────────────┘
```

### 10.4 Real-Time Compliance Indicators

In addition to the dashboard, add compliance status to the **chat view** itself:

```
┌─ Chat View ──────────────────────────────────────────────┐
│                                                    [🛡️▼] │ ← Compliance indicator
│                                                          │
│ (clicking 🛡️ shows inline compliance panel)              │
│ ┌──────────────────────────────────────────────────────┐ │
│ │ Session Compliance: 94% (32/34 checks passed)        │ │
│ │ Last check: 2 min ago — PASS                         │ │
│ │ Heuristic: ✓  LLM: ✓  Bias: 1 warning               │ │
│ │ [View Details]                                       │ │
│ └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

The WebSocket already sends `policyEvent` and `autotuneEvent` chirps. The Ux just needs to:
1. Listen for these events in the chat view
2. Accumulate pass/fail counts per session
3. Display the indicator badge (green/yellow/red)
4. Show details on click

### 10.5 Implementation Notes

- **Data source:** Compliance events are already sent via WebSocket — no new backend APIs needed for real-time display
- **Historical data:** Need a new endpoint or query to fetch past violations from `system.audit` records filtered by policy-related actions
- **Policy template CRUD:** Templates are currently JSON files in resources. For editing, either:
  - Store as `data.data` JSON objects in a `/Library/PolicyTemplates` directory (like prompt configs)
  - Or create a `system.policyTemplate` model (heavier, more structured)
- **Report generation:** Server-side endpoint that queries audit records and aggregates — return JSON, render as PDF client-side or server-side
- **Feature flag:** The entire compliance dashboard is behind the `iso42001` feature flag (Section 7)

### 10.6 Bias Pattern Editor

Allow editing the `biasPatterns.json` pattern database from the UI:

```
┌────────────────────────────────────────────────────────────────┐
│ Bias Patterns                                                  │
├────────────────────────────────────────────────────────────────┤
│ Area #2: MASCULINE_INTEGRITY                    [+ Add Pattern]│
│ Description: Male characters are systematically softened       │
│                                                                │
│ Patterns (65):                                                 │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ "he apologized"                                    [✕]   │   │
│ │ "tears welled"                                     [✕]   │   │
│ │ "voice breaking"                                   [✕]   │   │
│ │ "bit his lip"                                      [✕]   │   │
│ │ ...                                                      │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                │
│ Test Pattern: [Enter text to test against patterns___________] │
│ Result: 2 matches found — "he apologized", "voice breaking"   │
│                                                                │
│                                          [Save] [Reset]        │
└────────────────────────────────────────────────────────────────┘
```

---

## 11. Access Requests & Approvals

### 11.1 Existing Infrastructure

The backend has a well-designed access request/approval model system:

**Core Models:**
- `access.accessRequest` — Main request model with `approvalStatus` enum (APPROVE, DENY, PENDING, REQUEST, CERTIFIED, REMOVE, DUPLICATE)
- `access.approver` — Mixin with `approver` and `delegate` fields (flex foreign — any type can be approver/delegate)
- `access.requester` — Mixin with `requester` and `requesterType`
- `access.submitter` — Mixin with `submitter` and `submitterType`
- `access.baseAccess` — Base with `resource`, `resourceType`, `resourceData`, `token`
- `auth.entitlement` — Mixin with `entitlementType` and `entitlement`

**Key Model Fields on `access.accessRequest`:**
- `messages` (list of `message.spool`) — Notification queue
- `approvalStatus` (enum) — PENDING → APPROVE/DENY/etc.
- `approver` + `delegate` (flex foreign) — Who approves, who can act on their behalf
- `requester` (flex foreign) — Who is requesting
- `resource` (flex foreign) — What resource/entitlement is being requested

**Factory:** `AccessRequestFactory` — sets expiry, creates initial spool message, copies requester/submitter/entitlement/resource with IDs.

**Role-Based Access on the Model:**
- Create: `Requesters`
- Read: `RequestReaders`
- Update: `RequestUpdaters`, `RequestAdministrators`
- Delete: `RequestAdministrators`
- `approvalStatus` field update restricted to `Approvers` role

**Existing Policy System (for approval rules):**
- `policy.policy` → contains `rules` (recursive `policy.rule`)
- `policy.rule` → contains `patterns` (list of `policy.pattern`)
- `policy.pattern` → links `fact`, `match`, `operation`, `comparator`
- `policy.fact` → data point to evaluate
- `policy.operation` → Java class to execute
- `policy.policyRequest` → request evaluation with attributes/facts

**Notification:** `message.spool` model with sender/recipient/status/data — already linked to `access.accessRequest.messages`.

**Existing Forms:** `forms.request` and `forms.requestList` exist in `formDef.js` with basic fields.

### 11.2 What Needs to Be Built

> **NOTE:** The user has a simple hierarchical approval concept already designed. ASK BEFORE implementing — do not guess at the workflow structure.

#### A. Shopping Cart / Request Submission UI

**Route:** `/requests` (list) and `/requests/new` (create)

```
┌────────────────────────────────────────────────────────────────┐
│ Access Requests                           [+ New Request]      │
├────────────────────────────────────────────────────────────────┤
│ [My Requests] [Pending My Approval] [All Requests (admin)]     │
├────────────────────────────────────────────────────────────────┤
│ My Requests:                                                   │
│ ┌────────┬──────────────────┬────────────┬────────┬─────────┐ │
│ │ Status │ Resource         │ Type       │ Date   │ Actions │ │
│ ├────────┼──────────────────┼────────────┼────────┼─────────┤ │
│ │ ⏳ PEND│ DataAnalysts role │ Role       │ Mar 1  │ [View]  │ │
│ │ ✓ APPR│ /Shared/Reports   │ Group      │ Feb 28 │ [View]  │ │
│ │ ✕ DENY│ Admin role        │ Role       │ Feb 25 │ [View]  │ │
│ └────────┴──────────────────┴────────────┴────────┴─────────┘ │
└────────────────────────────────────────────────────────────────┘
```

**Shopping Cart (New Request):**

```
┌────────────────────────────────────────────────────────────────┐
│ New Access Request                                             │
├────────────────────────────────────────────────────────────────┤
│ What are you requesting?                                       │
│                                                                │
│ Request Type: [Role ▼]  (Role | Group | Permission | Resource) │
│                                                                │
│ Search: [____________] [🔍]                                    │
│                                                                │
│ Available:                        Cart:                        │
│ ┌──────────────────────┐         ┌──────────────────────┐     │
│ │ ☐ DataAnalysts       │   →     │ DataAnalysts    [✕]   │     │
│ │ ☐ ReportViewers      │         │ Auditors        [✕]   │     │
│ │ ☐ Auditors           │         └──────────────────────┘     │
│ │ ☐ ContentEditors     │                                      │
│ │ ☐ PolicyAdmins       │         Justification:               │
│ └──────────────────────┘         [                           ] │
│                                  [                           ] │
│                                                                │
│                                  [Submit Request]              │
└────────────────────────────────────────────────────────────────┘
```

**Implementation Notes:**
- "Available" list populates from a query of requestable roles/groups/permissions/resources
- Cart allows batching multiple items into one request (or one request per item — ASK about preference)
- Justification field maps to `description` on the access request
- On submit, calls `page.createObject()` with `access.accessRequest` — the `AccessRequestFactory` handles initialization
- `requester` = current user, `submitter` = current user (or can differ for delegated submissions)

#### B. Approval Workflow Designer (No-Code)

> **IMPORTANT:** The user mentioned a "simple hierarchical approval concept" that already exists. This section is a proposal — implementation should START by asking the user about their existing concept.

**Proposed Approach: Policy-Based Approval Rules**

Since the policy model already supports recursive rules, facts, patterns, and operations, approval workflows can be expressed as policies:

```
Policy: "Standard Role Request Approval"
├── Rule: "Manager Approval Required" (condition: AND)
│   ├── Pattern: requester.manager → must approve
│   └── Pattern: requestType == "role" → match
├── Rule: "Admin Approval for Privileged" (condition: AND)
│   ├── Pattern: entitlement.type == "PRIVILEGED_ACCESS"
│   └── Pattern: requester.manager + securityAdmin → both must approve
└── Condition: OR (first matching rule applies)
```

**Approval Workflow View:**

```
┌────────────────────────────────────────────────────────────────┐
│ Approval Workflows                              [+ New]        │
├────────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Standard Role Request                          [Edit]    │   │
│ │ Trigger: requestType = "role"                            │   │
│ │ Steps:                                                   │   │
│ │   1. Manager approval                                    │   │
│ │   2. (if privileged) Security admin approval             │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Group Access Request                           [Edit]    │   │
│ │ Trigger: requestType = "group"                           │   │
│ │ Steps:                                                   │   │
│ │   1. Group owner approval                                │   │
│ └──────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

**Workflow Step Editor (no-code):**

```
┌────────────────────────────────────────────────────────────────┐
│ Edit Workflow: Standard Role Request              [Save]       │
├────────────────────────────────────────────────────────────────┤
│ Trigger Conditions:                                            │
│   When: [Request Type ▼] [equals ▼] [role ▼]     [+ Add]     │
│                                                                │
│ Approval Steps:                                   [+ Add Step] │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Step 1: Manager Approval                                 │   │
│ │ Approver: [Requester's Manager ▼]                        │   │
│ │ Timeout:  [7] days → [Escalate to next step ▼]           │   │
│ │ Delegation: [Allow ▼]                                    │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Step 2: Security Review (conditional)                    │   │
│ │ Condition: [Entitlement Type ▼] [equals ▼] [PRIVILEGED]  │   │
│ │ Approver: [Role: SecurityAdmins ▼]                       │   │
│ │ Timeout:  [14] days → [Auto-deny ▼]                      │   │
│ │ Delegation: [Allow ▼]                                    │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                │
│ On All Steps Approved: [Grant Entitlement ▼]                   │
│ On Any Step Denied:    [Deny Request ▼]                        │
└────────────────────────────────────────────────────────────────┘
```

**Storage:** Each workflow step maps to a `policy.rule` with `policy.pattern` entries. The workflow itself is a `policy.policy` record. This leverages the existing policy infrastructure without new models.

#### C. Notifications for Status Changes

**Notification Channels:**

1. **In-App Notification Badge** (already partially exists via `page.context().notifications`)
   - When a request changes status → create `message.spool` entry (factory already does this)
   - Badge count in top menu shows unread notifications
   - Click opens notification panel

2. **WebSocket Real-Time Push**
   - When `approvalStatus` changes on an access request, fire a WebSocket event
   - Requestor sees status change immediately
   - Approver sees new pending requests immediately

3. **Notification Panel:**

```
┌──────────────────────────────────────────┐
│ Notifications                     [All]  │
├──────────────────────────────────────────┤
│ 🔔 Your request for DataAnalysts was     │
│    APPROVED by J. Smith — 2 min ago      │
│                                          │
│ 🔔 New request pending your approval:    │
│    M. Johnson requests ReportViewers     │
│    [Approve] [Deny] [View]   — 15 min    │
│                                          │
│ 🔔 Request for Admin role was DENIED     │
│    Reason: "Insufficient justification"  │
│    — 3 days ago                          │
└──────────────────────────────────────────┘
```

#### D. Approver Actions

**Pending Approval View:**

```
┌────────────────────────────────────────────────────────────────┐
│ Pending My Approval (3)                                        │
├────────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ M. Johnson requests: DataAnalysts role                   │   │
│ │ Justification: "Need access for Q1 reporting project"    │   │
│ │ Submitted: Mar 1, 2026                                   │   │
│ │ Step: 1 of 1 (Manager Approval)                          │   │
│ │                                                          │   │
│ │ [✓ Approve] [✕ Deny] [→ Delegate] [💬 Comment]          │   │
│ │                                                          │   │
│ │ Deny Reason: [________________________________]          │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ A. Chen requests: /Shared/Confidential group             │   │
│ │ Justification: "Project X requires access to reports"    │   │
│ │ Submitted: Feb 28, 2026                                  │   │
│ │ Step: 2 of 2 (Security Review) — Manager: ✓ Approved     │   │
│ │                                                          │   │
│ │ [✓ Approve] [✕ Deny] [→ Delegate] [💬 Comment]          │   │
│ └──────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

**Delegation:**
- Click "Delegate" → opens picker to select another user
- Sets `delegate` field on the access request's approver mixin
- Delegatee receives notification and can approve/deny
- Original approver can still act (delegation doesn't remove their authority)

### 11.3 Implementation Notes

- **The existing model structure is sufficient** — `access.accessRequest` with its mixins covers the full request lifecycle
- **`AccessRequestFactory` handles initialization** — expiry, initial spool message, copying references
- **The `Approvers` role restricts `approvalStatus` updates** — field-level access control already in place
- **`message.spool` is the notification mechanism** — already linked via the `messages` field
- **Policy model is the rules engine** — workflows stored as policies with patterns matching request attributes
- **ASK about the hierarchical approval concept** before implementing workflow storage/evaluation
- **ASK about cart behavior** — one request per resource, or batch?
- **ASK about auto-grant** — should approval automatically add the user to the role/group, or is that a separate step?

### 11.4 Open Questions for User (MUST ASK)

1. **Hierarchical approval concept:** What is the existing design for hierarchical approvals? How do approval levels (PRIVILEGED_ACCESS, APPLICATION, ACCESS, FEDERAL, OWNER from `ApprovalEnumType`) factor in?

2. **Cart behavior:** Should the shopping cart create one `access.accessRequest` per resource, or a single request with multiple resources?

3. **Auto-provisioning:** When a request is fully approved, should the system automatically add the requester to the role/group/entitlement? Or does an admin need to manually provision?

4. **Approval routing:** How is the approver determined? Options:
   - Manual (requester selects approver)
   - Hierarchical (requester's manager in org chart)
   - Resource owner (owner of the group/role)
   - Policy-driven (rules determine approver)

5. **Existing REST endpoints:** Are there any existing approval-specific endpoints beyond generic CRUD, or does everything go through `/rest/model/access.accessRequest`?

6. **Email notifications:** Should the notification system also send email, or is in-app + WebSocket sufficient?

---

## 12. Testing Strategy

### 10.1 Unit Tests

**Framework:** Vitest (comes with Vite, fast, compatible with existing code)

**Coverage targets:**

| Component | Tests |
|-----------|-------|
| `am7client.js` | API method signatures, URL construction, error handling |
| `pageClient.js` | Promise resolution, context management, navigation |
| `view.js` | Field rendering, visibility logic, type mapping |
| `formFieldRenderers.js` | Each renderer produces correct Mithril vnodes |
| `dialog.js` (new) | Open/close lifecycle, focus trap, accessibility |
| `formLoader.js` (new) | DB forms → fallback to formDef.js |
| `featureLoader.js` (new) | Feature manifest parsing, dependency resolution |
| `webauthn.js` (new) | Credential encoding/decoding, flow state |

### 10.2 Component Tests

**Framework:** Vitest + mithril-query (renders Mithril components without DOM)

| View | Tests |
|------|-------|
| `sig.js` | Login form, WebAuthn button visibility, error display |
| `panel.js` | Category rendering, customization, pinned items |
| `object.js` | Form rendering, field grid, command bar |
| `list.js` | Pagination, sorting, row rendering |
| `formDesigner.js` (new) | Drag-and-drop, field placement, property editing |
| `schemaView.js` (new) | Model list, field editor, authorization guards |

### 10.3 Integration Tests

**Framework:** Playwright (end-to-end browser testing)

| Flow | Tests |
|------|-------|
| Login with password | Enter creds → JWT → main panel |
| Login with WebAuthn | Passkey prompt → JWT → main panel |
| Object CRUD | Create → Edit → Save → Delete |
| Dialog lifecycle | Open → Interact → Confirm/Cancel |
| Feature toggling | Enable game → route available → disable → route gone |
| Form designer | Create form → add fields → save → use form |

### 10.4 Existing Test Framework Integration

The existing `testView.js` / `testFramework.js` / `testRegistry.js` stays as the **in-app integration test suite** for LLM and game features. New unit/component tests run in Vitest (headless, CI-friendly).

---

## 13. Implementation Phases

### Phase 0: Foundation (Prerequisite for all other phases)
**Goal:** Add Vite bundler, establish testing infrastructure

- [ ] Install Vite, configure for Mithril project
- [ ] Create `vite.config.js` with proper entry points
- [ ] Convert `index.html` to Vite-managed entry
- [ ] Verify all existing functionality works through Vite
- [ ] Install Vitest, create first test file
- [ ] Set up Playwright for E2E tests
- [ ] Create CI-friendly test scripts in `package.json`

**Estimated files changed:** 5-10 config files, `index.html`, `package.json`
**Risk:** Low — additive only, existing code unchanged

### Phase 1: Dialog System + Field Styling
**Goal:** Consistent, accessible dialogs. Better field space usage.

- [ ] Create `client/components/dialogCore.js` — new unified Dialog component
- [ ] Create dialog CSS classes in `basiTail.css`
- [ ] Migrate `dialog.js` business logic to `workflows/` modules
- [ ] Update field CSS — compact inline labels, density modes
- [ ] Update `formFieldRenderers.js` — apply new field styling
- [ ] Write unit tests for Dialog component
- [ ] Write unit tests for field renderers
- [ ] Migrate card game and magic8 to shared Dialog
- [ ] Remove old dialog CSS and inline styles

**Estimated files changed:** 15-20
**Risk:** Medium — touches rendering across the app

### Phase 2: Main Panel Customization
**Goal:** Users can customize their dashboard

- [ ] Create dashboard preferences data model (JSON in `~/Preferences/`)
- [ ] Update `panel.js` — customizable category order, visibility, pins
- [ ] Add "Customize" mode to panel
- [ ] Add "Quick Access" row
- [ ] Add "Recent Items" tracking (localStorage)
- [ ] Write tests for panel customization
- [ ] Write E2E test for customize flow

**Estimated files changed:** 3-5
**Risk:** Low — additive, panel.js is self-contained

### Phase 3: Feature System
**Goal:** Selective feature loading/deployment

- [ ] Create `features.json` manifest
- [ ] Create `featureLoader.js` — reads manifest, registers routes conditionally
- [ ] Update `applicationRouter.js` — dynamic route registration
- [ ] Update `topMenu.js` — conditionally show game/test/biometric buttons
- [ ] Update `games.js` — only show enabled games
- [ ] Convert game modules to dynamic `import()` (requires Phase 0)
- [ ] Create admin UI for feature toggle (admin panel)
- [ ] Write tests for feature loading/unloading
- [ ] Define build profiles (minimal, standard, full, enterprise)

**Estimated files changed:** 10-15
**Risk:** Medium — requires bundler (Phase 0)

### Phase 4: Model Form View
**Goal:** System admins can view/edit model definitions

- [ ] Create `client/view/schemaView.js` — model list and detail view
- [ ] Create/extend backend endpoints for schema introspection
- [ ] Add `system` flag tracking to backend model metadata
- [ ] Add authorization checks (SystemAdmin + /System org)
- [ ] Add route `/schema` and `/schema/:type` (behind feature flag)
- [ ] Add "Administration > Models" to panel (conditional on auth)
- [ ] Write unit tests for schema view
- [ ] Write E2E test for model viewing
- [ ] Phase 4b: Add mutation endpoints (add field, remove non-system field, delete non-system model)

**Estimated files changed:** 5-8 (client) + 3-5 (server)
**Risk:** Medium-High — server changes required, authorization critical

### Phase 5: Form Editor / Designer
**Goal:** Forms can be created and edited visually

- [ ] Create `system.formDefinition` and `system.formField` models on backend
- [ ] Create `client/formLoader.js` — loads from DB, falls back to formDef.js
- [ ] Create `client/view/formDesigner.js` — visual form editor
- [ ] Implement drag-and-drop field placement
- [ ] Implement field property editor panel
- [ ] Implement form preview
- [ ] Update `object.js` to use `formLoader` instead of direct `formDef.js` reference
- [ ] Create migration tool: export `formDef.js` forms to database
- [ ] Add responsive breakpoints to field grid
- [ ] Write comprehensive tests for form designer
- [ ] Write E2E test for create form → use form flow

**Estimated files changed:** 10-15 (client) + 3-5 (server)
**Risk:** High — fundamental change to form system, requires careful migration

### Phase 6: WebAuthn
**Goal:** Passwordless authentication option

- [ ] Create `auth.webauthnCredential` model on backend
- [ ] Add `webauthn4j` dependency to Service7
- [ ] Implement `WebAuthnService.java` — 4 endpoints (reg GET/POST, auth GET/POST)
- [ ] Implement challenge management (session-based)
- [ ] Extend `AM7LoginModule` for WebAuthn token verification
- [ ] Create `client/components/webauthn.js` — adapted from Featherbone
- [ ] Update `sig.js` — add Passkey button
- [ ] Add credential management to user profile view
- [ ] Write server unit tests for WebAuthn flow
- [ ] Write client unit tests for credential encoding
- [ ] Write E2E test for registration and authentication flows
- [ ] Feature-flag the WebAuthn option

**Estimated files changed:** 5-8 (client) + 5-8 (server)
**Risk:** Medium — well-defined protocol, reference implementation exists

### Phase 7: ISO 42001 Compliance Dashboard
**Goal:** Visible compliance monitoring, bias pattern management, and audit reporting

- [ ] Create `/compliance` route and compliance dashboard view
- [ ] Implement Overview tab — aggregate violation stats, pass rates, trends
- [ ] Implement Violations tab — searchable/filterable violation list with response excerpts
- [ ] Implement Audit Log tab — query `system.audit` records with filters and export
- [ ] Implement Policy Templates tab — view/edit policy template JSON files
- [ ] Implement Report tab — generate compliance reports (JSON/CSV, server-side aggregation)
- [ ] Add real-time compliance indicator to chat view (listen for `policyEvent`/`autotuneEvent` chirps)
- [ ] Implement Bias Pattern Editor — view/edit `biasPatterns.json` pattern database
- [ ] Create compliance data aggregation endpoint on backend (violation counts, pass rates over time)
- [ ] Add `iso42001` feature flag (Section 7)
- [ ] Write tests for dashboard components
- [ ] Write E2E test for compliance dashboard flow

**Estimated files changed:** 5-8 (client) + 2-4 (server)
**Risk:** Medium — backend events already exist, mostly Ux buildout

### Phase 8: Access Requests & Approvals
**Goal:** Full request lifecycle — submission, approval workflow, notifications, provisioning

**Prerequisites:** ASK user about hierarchical approval concept before implementing.

- [ ] Enhance `forms.request` in formDef.js with full request fields and shopping cart UX
- [ ] Create access request list view with tabs (My Requests, Pending My Approval, All)
- [ ] Create shopping cart / new request view with resource picker
- [ ] Implement approval actions (Approve, Deny, Delegate, Comment)
- [ ] Implement approval workflow designer using policy model (or user's simpler approach — ASK)
- [ ] Implement notification panel for access request status changes
- [ ] Add WebSocket event handling for real-time request/approval notifications
- [ ] Implement delegation flow (delegate field on approver mixin)
- [ ] Add notification badge to top menu
- [ ] Create/extend backend endpoints for approval workflow evaluation
- [ ] Implement auto-provisioning on approval (if desired — ASK)
- [ ] Write tests for request submission, approval, and notification flows
- [ ] Write E2E test for full request → approve → provision lifecycle

**Estimated files changed:** 8-12 (client) + 3-6 (server)
**Risk:** Medium-High — requires user input on workflow design, notification infrastructure

---

## Appendix A: Technology Decisions

| Choice | Current | Recommendation | Rationale |
|--------|---------|----------------|-----------|
| **UI Framework** | Mithril.js | **Keep Mithril** | Lightweight, fast, simple. No reason to switch. |
| **CSS Framework** | Tailwind CSS | **Keep Tailwind** | Already adopted, utility-first fits component model well. |
| **Icons** | Material Icons + Symbols | **Keep** | Comprehensive, well-supported. |
| **Bundler** | None (raw scripts) | **Add Vite** | Fast, supports Mithril, enables code splitting. |
| **Test Runner** | Custom testFramework.js | **Add Vitest** (unit) + **Playwright** (E2E) | Industry standard, CI-friendly, fast. |
| **WebAuthn Library (Java)** | N/A | **webauthn4j** | Mature, well-maintained, Apache 2.0 license. |
| **WebAuthn Library (Client)** | N/A | **Native Web API** | No library needed — browser APIs are sufficient (same as Featherbone). |
| **Dev Server** | Node.js server.js | **Vite dev server** | HMR, fast, no custom code. |
| **Production Server** | Node.js server.js | **Nginx or Tomcat** | Static file serving, reverse proxy to Service7. |

## Appendix B: Files to Split / Refactor

| File | Size | Action |
|------|------|--------|
| `formDef.js` | 5,876 lines | Split by category: `formDef.auth.js`, `formDef.olio.js`, etc. Eventually DB-backed. |
| `dialog.js` | 2,044 lines | Extract business logic to `workflows/*.js`. Dialog UI to `dialogCore.js`. |
| `object.js` | ~75KB | Extract: `fieldGrid.js`, `commandBar.js`, `tabContainer.js`, `pickerIntegration.js` |
| `chat.js` (view) | ~77KB | Already partially split into `components/chat/`. Continue extraction. |
| `pageClient.js` | ~57KB | Consider splitting: `pageClient.core.js`, `pageClient.navigation.js`, `pageClient.websocket.js` |
| `basiTail.css` | 2,500+ lines | Extract game CSS to feature-specific files. |

## Appendix C: Open Design Questions (For Review)

All questions needing answers before or during implementation, organized by section.

### C.1 — Architecture & Infrastructure (Sections 1, 9)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 1 | **Production deployment target?** | **RESOLVED:** Tomcat for Service7 + recommended web frontend for Ux7 static files. Docker container target, but mostly local dev/test currently. **Decision:** Vite dev server for development, Nginx for production static serving + reverse proxy to Tomcat. Docker Compose with two containers (nginx + tomcat). | Phase 0 |
| 2 | **Is clustering / HA a concern?** | **RESOLVED:** Clustering planned. Current OAuth token is client-held (in-memory only), which works for clustering. Server caching must be synced across nodes. **Decision:** Use database-backed challenge storage for WebAuthn (Option B). Feature config and compliance data should also be DB-backed, not in-memory. | Phase 0, 6 |
| 3 | **Vite vs. other bundlers?** | **RESOLVED:** No licensing/cost constraints. Preference for fast+capable over bloated/slow. **Decision:** Vite — MIT licensed, fastest dev server (native ESM + esbuild for deps), uses Rollup for prod builds (also MIT). Zero license fees. Faster than Webpack, more capable than raw esbuild. | Phase 0 |
| 4 | **IIFE → ES module conversion order?** | **RESOLVED:** Incremental. New/refactored files become ES modules immediately. Existing files converted one-by-one during each phase's refactoring work. Vite handles mixed IIFE/ESM natively during transition. Goal: all files are ES modules by end of Phase 3. | Phase 0 |
| 5 | **Existing bugs from prior Claude changes?** | **RESOLVED:** Various small and large changes were implemented but never worked well. No comprehensive list — discover and fix as encountered during refactoring. **Standing rule:** Do NOT be defensive about existing code. Prior Claude sessions likely introduced the bugs. Own it, fix it, move on. | All phases |
| 6 | **`server.js` current responsibilities?** | **RESOLVED (code review):** Express server that: (a) serves static files with MIME types, (b) **replaces `%AM7_SERVER%` token** in HTML with backend URL from `client.json`, (c) HTTPS with TLS certs, (d) CORS for localhost origins, (e) compression, (f) admin client auto-auth. **Decision:** Vite dev server replaces this with: (a) native static serving, (b) `define` plugin or `.env` for `%AM7_SERVER%` replacement, (c) Vite `server.https` config, (d) Vite `server.cors` config, (e) built-in compression. Admin client auth (f) is unused by Ux — drop it. | Phase 0 |

### C.2 — Model Form View (Section 2)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 7 | **Model mutation API — does it exist?** | **RESOLVED:** System models are startup-only from JSON. Runtime model addition is supported; runtime updates are spotty (may require reboot). Custom field types not needed — no need to update `FieldNames`/`ModelNames` classes. JS operation support (like computed/provider fields in Objects7) is a future consideration, not needed now. **Decision:** Phase 4 starts as read-only model viewer. Add-field mutations target user-defined models only. System model structure is immutable from the Ux. | Phase 4 |
| 8 | **How are `system` vs. `user` models distinguished?** | **RESOLVED:** Add `userDefined: boolean` to model and field schema definitions. System-shipped models/fields default to `false` (not user-defined = system). Runtime-added models/fields set to `true`. `modelDef.js` includes this flag, and the Ux uses it to determine mutability. Fields with `userDefined: false` are immutable from the Ux. | Phase 4 |
| 9 | **Model field type changes on existing data?** | **RESOLVED:** Auto-attempt conversion with warning to the user. Backend already has a dynamic schema update mechanism (previously implemented). Ux shows a confirmation dialog: "Changing type from X to Y. Existing data will be auto-converted where possible. Proceed?" | Phase 4 |
| 10 | **Should model editing be read-only first?** | **RESOLVED:** Yes — read-only first. Phase 4a: View models, fields, constraints, access, inheritance. Phase 4b: Add mutations (add/edit/remove user-defined fields, create/delete user-defined models) after validating backend schema update mechanism works correctly. | Phase 4 |

### C.3 — Form Editor / Designer (Section 3)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 11 | **Form storage location?** | **RESOLVED:** Three-tier resolution: User → Org → System. Lookup checks user's forms first, then org-level, then system defaults. Note: Cross-org authorization is prohibited, so org-level forms are copies of system forms (not references). System forms are seeded to each org on creation/update. User forms stored in `~/FormDefinitions`, org forms in org-level directory. | Phase 5 |
| 12 | **Layout system — Option A or B?** | **RESOLVED:** Option A — keep 6-column grid with responsive breakpoints. `sm:grid-cols-1 md:grid-cols-3 lg:grid-cols-6`. Add explicit field `order` property. Designer uses column-span dropdowns, not freeform drag. | Phase 5 |
| 13 | **Form versioning?** | **RESOLVED:** No versioning. Use existing `createdDate`/`modifiedDate` timestamps only. Keep it simple. | Phase 5 |
| 14 | **Form permissions?** | **RESOLVED:** System admins only (`SystemAdministrators` role) can create/edit form definitions. Regular users and org admins just use them. | Phase 5 |
| 15 | **formDef.js migration — automatic or manual?** | **RESOLVED:** Auto-seed on first run. When the system detects no DB forms exist for the org, auto-create them from `formDef.js` defaults. Seamless for users. | Phase 5 |
| 16 | **Multiple forms per model?** | **RESOLVED:** Yes — multiple named form variants per model (e.g., "default", "compact", "admin"). Add a `variant` or `name` field to `system.formDefinition`. View context or user preference selects which variant to display. | Phase 5 |
| 17 | **Form commands — how to handle function references?** | Commands stay in code — only field layout/ordering/visibility is customizable via the designer. Commands remain hardcoded per model type in JS. No custom JS execution in Ux. | **RESOLVED** |
| 18 | **Sub-form handling?** | Unlimited nesting supported. Sub-forms serve two purposes: (1) creating tabs within a form, and (2) pointing to child model instances loaded as nested views. Keep the current pattern — it avoids navigating through many separate forms. Start with existing behavior, cleanup/refactor incrementally. Designer should support adding/reordering sub-forms. | **RESOLVED** |

### C.4 — Dialog System (Section 4)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 19 | **Dialog stacking?** | Yes — unlimited stacking. Each new dialog overlays the previous with a dimmed backdrop. Implement a dialog stack (array) managed by the Dialog component. z-index increments per layer. Closing pops the top dialog. | **RESOLVED** |
| 20 | **Dialog animation library?** | CSS-only. Use `@keyframes` and CSS transitions for enter/exit (fadeIn, slideUp, fadeOut). Brief delay before DOM removal for exit animations. No animation library dependency. | **RESOLVED** |
| 21 | **Drawer vs. dialog for some use cases?** | Yes — Dialog component supports both centered modal and slide-in drawer modes via a `mode` property (`"modal"` or `"drawer"`). Drawers slide from right edge, good for settings/config/notifications panels. Both variants participate in the dialog stack. | **RESOLVED** |

### C.5 — Form Field Styling (Section 5)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 22 | **Inline labels — which field widths?** | Row-level consistency: if ALL fields in a row use inline labels, they all render inline. If ANY field in the row uses a vertical (above) label, all fields in that row switch to vertical labels. Default: narrow fields (one, third) prefer inline; wider fields prefer vertical. The row renderer checks all fields in the row and picks the consistent mode. | **RESOLVED** |
| 23 | **Default density mode?** | Default to "comfortable" (standard spacing). Users can switch to compact in preferences. | **RESOLVED** |
| 24 | **Density preference scope?** | Global default with per-view override. One global preference, but individual views can override (e.g., list views default to compact, object views to comfortable). Stored in user preferences. | **RESOLVED** |
| 25 | **Toggle switch vs. checkbox?** | Replace all boolean checkboxes with toggle switches. Consistent modern look across all forms. Implement as a Tailwind-styled `<input type="checkbox">` with toggle appearance via CSS. | **RESOLVED** |
| 26 | **Textarea auto-resize?** | Auto-resize with max-height. Textarea grows with content up to a configurable max-height (default ~200px), then scrolls. Use `oninput` handler to adjust height. Prevents unbounded layout shifts while improving UX. | **RESOLVED** |

### C.6 — Main Panel Customization (Section 6)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 27 | **Dashboard preferences storage?** | Server + localStorage cache. Server (`data.data` JSON in `~/Preferences/`) is source of truth. localStorage is cache for instant load. Sync from server in background on page load. Write-through: save to both on change. | **RESOLVED** |
| 28 | **Recent items — how many?** | Default 10, user-configurable (range 5-25). Stored in user preferences. | **RESOLVED** |
| 29 | **Admin-defined default layouts?** | Yes. Org admins set a default dashboard layout for their org. Users inherit and customize from that baseline. Follows User → Org → System resolution (Q11). Admin layouts stored as `data.data` in org-level Preferences directory. | **RESOLVED** |
| 30 | **Category item customization?** | Both — category-level toggle for bulk on/off, plus ability to hide individual items within enabled categories. Preferences store both `hiddenCategories: []` and `hiddenItems: { categoryName: [itemName, ...] }`. | **RESOLVED** |

### C.7 — Feature System (Section 7)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 31 | **Feature configuration scope?** | Global + per-org override. System admin sets global baseline, org admins can enable/disable features within allowed set. Aligns with PBAC model and User → Org → System resolution. | **RESOLVED** |
| 32 | **Feature configuration persistence?** | Layered: env variable → DB record at Org level → DB record at system/server config level. Env vars are highest priority (deployment override). Org DB records override system defaults. System DB records are the baseline. | **RESOLVED** |
| 33 | **Feature runtime toggle?** | Build-level and restart only. Features are included/excluded at build time via Vite. Example: deploy ISO 42001-only build without game code/styles. No runtime hot-toggle — feature set is fixed per build. This keeps bundles lean (tree-shaking removes disabled features entirely). | **RESOLVED** |
| 34 | **Feature dependencies — strict or graceful?** | Auto-disable dependent features. If a dependency is disabled at build time, dependent features are excluded too. Build config validates dependency graph and warns about missing deps. | **RESOLVED** |
| 35 | **Third-party npm dependencies per feature?** | Bundle with feature chunk. Each feature's Vite chunk includes its deps. Feature is included/excluded at build time (Q33), so deps come along with it. Simple code splitting. | **RESOLVED** |
| 36 | **Olio (character/apparel) — is it a feature or core?** | Core for now. Olio should ideally be a separate feature, but it's too tightly coupled in Objects7 currently. Treat as core until backend decoupling is done. Future: separate feature with its own Vite chunk. | **RESOLVED** |

### C.8 — WebAuthn (Section 8)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 37 | **WebAuthn challenge storage?** | Database-backed. Models, persistence, and crypto infrastructure already exist for storing WebAuthn credentials in DB. Use existing credential model for challenge storage with short TTL. Supports clustering (Q2). | **RESOLVED** |
| 38 | **WebAuthn — platform vs. cross-platform authenticators?** | Platform only (`authenticatorAttachment: "platform"`). Built-in authenticators only (Windows Hello, Touch ID, Face ID). No USB key support for now. | **RESOLVED** |
| 39 | **WebAuthn — resident key / discoverable credential?** | Both. Support discoverable credentials for passwordless login (no username entry), BUT also provide a "switch user" / "specify user" option on the login form for cases where the user needs to authenticate as a different account. | **RESOLVED** |
| 40 | **WebAuthn — attestation level?** | None (`attestation: "none"`). Most compatible. No device verification. Works with all platform authenticators. | **RESOLVED** |
| 41 | **WebAuthn — existing `crypto.key` reuse?** | Use existing `crypto.key` model. No new model needed. WebAuthn public keys stored alongside other crypto keys. | **RESOLVED** |
| 42 | **WebAuthn as sole auth?** | Yes — users can disable password and use passkey-only. Use existing credential storage and eval service. Add a `CredentialEnumType` entry for WebAuthn and specific validation logic. Do NOT create new custom backend services — leverage existing `CredentialFactory`, `TokenService`, credential evaluation pipeline. | **RESOLVED** |
| 43 | **WebAuthn registration — require existing auth?** | Require existing auth. User must be logged in (via password or other credential) before registering a passkey. Standard security practice. | **RESOLVED** |

### C.9 — ISO 42001 Compliance (Section 10)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 44 | **Compliance data retention?** | Configurable per org. Org admins set retention period (default 1 year). Supports different regulatory requirements. Archived records can be exported before deletion. | **RESOLVED** |
| 45 | **Compliance report format?** | Both — summary dashboard for daily use + formal ISO 42001-aligned export for audits. Dashboard shows live metrics; export generates structured report following ISO 42001 Annex D format. | **RESOLVED** |
| 46 | **Compliance/governance template storage?** | New model: `governance.policy` under `./resources/models/governance/`. NOT the existing AuthZ `policy.policy` model. If it makes sense, spin all ISO 42001 backend work into its own Java library (e.g., `AccountManagerGovernance7`). **NOTE:** Current Ux form lookup has a limitation — it doesn't handle discovery with a namespace (e.g., `governance.policy` vs `policy.policy`). The redesigned form system MUST account for namespace-qualified model type lookups. | **RESOLVED** |
| 47 | **Compliance dashboard access?** | Role-based + user self-view. Admins and ComplianceOfficer role see full dashboard with all users/sessions. Regular users see only their own session compliance stats. ComplianceOfficer role can be assigned to non-admin users. | **RESOLVED** |
| 48 | **Bias pattern editing — who?** | Admins + designated compliance users. System admins and users with a ComplianceEditor role can modify bias patterns. Role-based access via PBAC. | **RESOLVED** |
| 49 | **Violation severity classification?** | Combined approach: Heuristic-only match (ResponsePolicyEvaluator) = **Warning**. If LLM compliance check (ResponseComplianceEvaluator) also confirms the violation = **Critical**. This leverages both evaluation stages already in the pipeline — fast heuristic flags, LLM validates. Adjustable: if the heuristic match count exceeds a configurable threshold (default 3) in one response, auto-escalate to Critical even without LLM confirmation. | **RESOLVED** |
| 50 | **Compliance score calculation?** | Per-bias-area scoring, then weighted average. Calculate compliance % for each of the 10 overcorrection areas independently (`area_passed / area_total * 100`). Overall score = weighted average across areas (all weights default to 1.0, configurable). This surfaces which specific areas need attention rather than hiding problems in a single number. Dashboard shows both individual area scores and overall. | **RESOLVED** |
| 51 | **Auto-tune on violation?** | Log + auto-adjust (no halt). Record the violation AND automatically strengthen the overcorrection directive in subsequent prompts. Don't halt the session. Auto-tune already exists in backend (`autotuneEvent`). Dashboard shows auto-tune actions taken. | **RESOLVED** |
| 52 | **Cross-session compliance?** | All levels. Dashboard supports: per-session (debugging), per-user (accountability), per-chatConfig (template quality), and global (reporting). Drill-down navigation between levels. Default view is global with drill-down. | **RESOLVED** |

### C.10 — Access Requests & Approvals (Section 11)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 53 | **Hierarchical approval concept?** | Approval uses AuthZ policy model. Policy defines logical rules representing levels; facts define approval type (user, role, group). Flow: (1) Open request → policy evaluates → returns PENDING → sends notification to approver. (2) Approver approves/denies → policy re-runs → sees current level finished → moves to next level or exits. May need a `level` attribute on the approval model. Core loop: `while (currentLevel = getNextLevel(max(approval.level)) > 0) { run policy for next approval or exit }`. | **RESOLVED** |
| 54 | **Approval routing?** | Policy-based. Policies are loosely coupled to objects via controls, which can be set on the instance or parent container. The policy rules determine the approver (can be user, role, or group). No hardcoded routing — all configurable via policy editor. | **RESOLVED** |
| 55 | **Shopping cart behavior?** | Batch into one request. Shopping cart collects multiple resources, submits as one `access.accessRequest` containing all items. All-or-nothing approval per the policy. Fewer requests to manage, consistent approval lifecycle. | **RESOLVED** |
| 56 | **Auto-provisioning?** | Configurable per resource type via policy. Low-risk resources auto-provision on full approval (system automatically adds requester to role/group). High-risk resources require manual admin provisioning after approval. Policy rules determine which behavior applies. | **RESOLVED** |
| 57 | **Existing REST endpoints for approvals?** | No v7-specific approval endpoints yet — generic CRUD only. Reference: older v5 [ApprovalService.java](https://github.com/StephenCote/AccountManager/blob/master/src/AccountManagerService/src/main/java/org/cote/rest/services/ApprovalService.java) has 9 endpoints: `/requests/open` (list open), `/requestable/{type}/{id}` (check eligibility), `/policy/attach/{type}/{id}/{policyId}` (bind policy to resource), `/requests/{type}/{id}` (list by object), `/requests/count` + `/requests/list` (search with pagination), `/request/policies/{id}` (get associated policies), `/policy/owner` (org-level policy). Actual approve/deny logic was in separate PolicyService/RequestService. Adapt these for v7 approval endpoints. | **RESOLVED** |
| 58 | **Email notifications?** | In-app + WebSocket for now. Store messages in DB via `message.spool` for reading/deleting/responding. Email support deferred to a future phase. | **RESOLVED** |
| 59 | **Delegation depth?** | Multi-level with configurable depth. Delegatees can re-delegate up to a configurable max depth (default 3). Prevents infinite chains while supporting large org structures. Depth limit stored in policy rules. | **RESOLVED** |
| 60 | **Approval timeout behavior?** | Configurable per workflow via policy rules. Timeout actions: auto-escalate, auto-deny, send reminder, or auto-approve. Different workflows define their own timeout behavior. Timeout period also configurable. | **RESOLVED** |
| 61 | **Request cancellation?** | Both requester and submitter can cancel pending requests. Cancelled requests update `approvalStatus` and send notification to any pending approvers. | **RESOLVED** |
| 62 | **Approval comments/audit trail?** | Optional comments on approve/deny. Full audit trail visible to requester — they can see all status changes and any comments left by approvers. Audit records stored via existing `system.audit` model. | **RESOLVED** |
| 63 | **Requestable resources scope?** | Two levels: (1) Object/container → control → policy: specific resources become requestable when an approval policy is attached via a control on the object or its parent container. (2) Model-level → policy: an approval policy can be set at the model type level, making ALL instances of that model requestable. Shopping cart browse view queries both levels. | **RESOLVED** |

### C.11 — Testing (Section 12)

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 64 | **Test coverage threshold?** | Minimum coverage % to enforce? Or coverage as informational only? | Phase 0 |
| 65 | **CI/CD pipeline?** | Is there an existing CI/CD system (GitHub Actions, Jenkins, etc.)? Should tests run automatically on push/PR? | Phase 0 |
| 66 | **E2E test environment?** | Playwright tests need a running backend. Use a test instance of Service7? Mock the API? Or run against a Docker-composed full stack? | Phase 0 |

### C.12 — General / Cross-Cutting

| # | Question | Options / Context | Blocks |
|---|----------|-------------------|--------|
| 67 | **`saur.js` and `hyp.js` — keep or deprecate?** | `saur.js` appears unused/legacy. `hyp.js` overlaps with magic8. Are these still in active use? Can `hyp.js` be consolidated? | Phase 0 |
| 68 | **`designer.js` (WYSIWYG HTML editor) — keep?** | Uses deprecated `execCommand` API. Is it actively used? If so, replace with CodeMirror-based editor (already in the project) or similar. | Phase 0 |
| 69 | **Dark mode completeness?** | Some components (dialogs, inline styles) don't support dark mode. Should full dark mode be a goal of this redesign, or just fix the obvious gaps? | Phase 1 |
| 70 | **Accessibility standards target?** | WCAG 2.1 AA? Just the dialog focus trap and ARIA attributes, or a broader accessibility audit? | Phase 1 |
| 71 | **Mobile / responsive priority?** | Is mobile a target platform? Currently the UI is desktop-only. Responsive breakpoints (Section 3.3) help but a full mobile experience requires more work. | Phase 1, 5 |
| 72 | **Internationalization (i18n)?** | Any plans for localization? Labels are currently hardcoded in English. If i18n is a future goal, it affects how labels are stored in forms and throughout the UI. | All phases |
| 73 | **Phase ordering preferences?** | The proposed order is 0→1→2→3→4→5→6→7→8. Should any phase be reprioritized based on current needs? | All phases |
| 74 | **Parallel phase execution?** | Some phases are independent (e.g., Phase 2 panel customization and Phase 6 WebAuthn). Should multiple phases run in parallel? | All phases |
| 75 | **Notification system — unified or per-feature?** | Access request notifications (Section 11) and compliance notifications (Section 10) both need notification infrastructure. Build one unified notification system, or each feature manages its own? | Phase 7, 8 |
| 76 | **`tmpclaude-*` temp files in repo?** | Several temp files from prior Claude sessions are untracked in git. Clean up now or as part of Phase 0? | Phase 0 |
| 77 | **Client-side caching upgrade — SQLite/WebDB?** | Current client caching is in-memory only (`am7client` cache). Consider using model definitions with SQLite (via WASM) or IndexedDB/WebDB for persistent local caching. Would allow longer cache lifetime, offline browsing of cached data, and better performance for large datasets. Evaluate complexity vs. benefit. | Phase 2 |
