# AccountManagerUx75 — LLM Coding Assistant Rules

## Rule 0: READ UX7 BEFORE CODING

**EVERY UI feature, pattern, layout, toolbar, button, field rendering, or navigation behavior MUST be verified against the Ux7 reference implementation BEFORE writing any code.**

- Ux7 source: `../AccountManagerUx7/client/`
- Key reference files:
  - `view/list.js` — list view toolbar (`getActionButtonBar`, `getAdminButtons`, `getActionButtons`, `getOlioButtons`, `getFavoriteButtons`, `getPageToggleButtons`)
  - `view/object.js` — object view, `preparePicker` (picker path resolution via `am7view.path(type)`)
  - `components/dialog.js` — dialogs, `updateSessionName`, `newChatRequest` form
  - `components/formFieldRenderers.js` — how picker fields render (text input + context menu buttons)
  - `view.js` — `am7view.path()` returns `"~/" + model.group`

**DO NOT guess.** If you are uncertain about ANY pattern, read the Ux7 file first. If you catch yourself writing code without having read the reference, STOP and read it.

## Rule 1: Do EXACTLY What the User Asks

- If the user says "put X here", put X there. Do not put it somewhere else.
- If the user says "use control Y", use control Y. Do not substitute a different control.
- If the user says "add button Z in both modes", add it in BOTH modes. Not just one.
- Do not rearrange, "improve", or change things the user did not request.
- Do not embed icons inside text fields unless specifically asked to.
- Do not move elements between containers unless specifically asked to.

## Rule 2: Address ALL Points in User Messages

Before responding, re-read the user's message and check off every requirement mentioned. If the user lists 3 things, all 3 must be addressed. Do not skip any.

## Rule 3: Toolbar Button Patterns (from Ux7)

The list view toolbar follows this structure from Ux7's `getActionButtonBar`:

1. **Picker button** — picker mode only
2. **Admin/library buttons** (`getAdminButtons`) — BOTH modes. Shows `admin_panel_settings` for types with `am7model.system.library` entries
3. **Action buttons** (`getActionButtons`) — NORMAL mode only. Add, edit, delete, character wizard, etc.
4. **Olio buttons** (`getOlioButtons`) — BOTH modes. Globe button for olio navigation
5. **Favorite buttons** (`getFavoriteButtons`) — BOTH modes. Favorite navigation
6. **Option/page toggle buttons** — BOTH modes. Grid toggle, container toggle, info
7. **Search buttons** (`getGroupSearchButtons`) — BOTH modes. Filter/search for group types

## Rule 4: Picker Defaults

- **Default container**: Model default group path via `am7view.pathForType(type)` = `"~/" + model.group` expanded. Matches Ux7's `preparePicker` which uses `am7view.path(type)`.
- **Library dir**: Passed as `libraryContainerId` for the library navigation button, NOT as the starting container.
- **Favorites**: Always resolved and passed as `favoritesContainerId`.
- Picker fields in forms: text label + separate right-aligned icon-only buttons (search, clear). NOT embedded inside the text span.

## Rule 5: ALL Services Are LIVE — No Excuses

All services are running at localhost:8443 and are always available:

- **Backend**: AccountManagerService7 (REST API, WebSocket)
- **Database**: PostgreSQL
- **LLMs**: Chat, analysis, narration endpoints
- **SwarmUI / Stable Diffusion**: Image generation
- **All feature services**: OlioService, SchemaService, WebAuthnService, AccessRequestService, VectorService, PictureBookService

Never skip live testing. Never mark anything as "needs backend" or "needs server." Never assume a service is unavailable — it is available. Tests (Vitest unit tests, Playwright integration tests) can and should hit real endpoints.

## Rule 6: API Patterns

- `am7client.member()` sField = field name (e.g., `"apparel"`), NOT participantModel
- `groupId` = directory's numeric `.id`, NOT `.objectId` UUID
- Enums: sent lowercase, read UPPERCASE
- `page.member()` and `page.getTag()` do NOT exist in Ux75
- For tag memberships: sField must be null

## Rule 7: Don't Guess Config Names or API Endpoints

- List from library directories using `LLMConnector.getLibraryGroup(dirType)` + `page.listObjects()`
- Backend library dir types: `"chat"` (not chatConfig), `"prompt"` (not promptConfig), `"promptTemplate"` (same)
- Available chat configs: "Open Chat", "generalChat", "coding"
- Available templates: "coding" only
- Available prompt configs: "default" only

## Rule 8: Own Every Error and Warning

**You are responsible for every error and warning in the browser console and server logs.**

- After making changes, check the browser console and server logs for errors and warnings.
- Do not dismiss warnings as "not important" — investigate and resolve them.
- If your code produces a console error, that is YOUR bug. Fix it before moving on.
- If a server endpoint returns an error status, trace the cause. Do not hand-wave it away.
- 404s, null reference errors, failed fetches, unhandled promise rejections — all yours to fix.

## Rule 9: Test Every Change

**All new features and changes MUST be tested before declaring done.**

- Run the Vite build (`npx vite build`) to verify no compilation errors.
- Run Vitest (`npx vitest run`) for unit tests relevant to the change.
- For UI changes, verify in the browser against the live backend.
- For new features, write tests (Vitest for logic, Playwright for integration).
- You do NOT need to run full regression, but you MUST run tests covering your changes.

## Rule 10: Definition of "Done"

**You are NOT done until ALL of the following are true:**

1. The build passes with no errors.
2. All tests covering your changes pass.
3. All browser console errors and warnings from your changes are resolved.
4. All server log errors and warnings from your changes are resolved.
5. Every requirement from the user's message has been addressed (re-read it to verify).

Do not say "done" or "complete" or give a summary of changes until these conditions are met. If you cannot verify something (e.g., browser testing), say so explicitly rather than claiming completion.

## Rule 11: When You Get Corrected

1. Re-read the user's EXACT words
2. Read the Ux7 reference code for the feature in question
3. Make the change to match EXACTLY what was asked
4. Do not interpret, paraphrase, or "improve" the request
5. If corrected twice on the same thing, you have a pattern problem — fix the root cause, not just the symptom

## Rule 12: Track Issues, Don't Jump to Fix

When the user reports issues, bugs, or adds items to a list:

- **Record them in `aiDocs/OpenIssues.md`** — the persistent issue tracker for this project.
- **Do NOT immediately start fixing** unless the user explicitly says to fix it now.
- **Stay focused** on whatever task is currently in progress. Do not context-switch.
- If the user is dictating a list of issues, keep listening and recording until they're done.
- Only begin work on reported issues when the user says "fix these", "go ahead", or otherwise gives a clear instruction to start.

## Rule 13: Conversation Start/Continue Prompt Guide

When starting or continuing a conversation about Ux75 work, construct a detailed prompt that includes:

### For Starting a New Task
1. **What**: State the specific feature, bug, or change being requested
2. **Where**: List the exact files that will be modified or created
3. **Reference**: Identify which Ux7 files to read for patterns (per Rule 0)
4. **Context**: Note any related recent changes, known issues, or dependencies
5. **Acceptance criteria**: What does "done" look like? (UI behavior, tests, no errors)
6. **Previous attempts**: If this was tried before, what went wrong?

### For Continuing Work
1. **Status**: What was completed, what remains, what broke
2. **Open issues**: Specific errors, warnings, or user corrections not yet addressed
3. **Files modified**: List every file changed with line ranges
4. **Test status**: Which tests pass, which fail, which are missing
5. **User feedback**: Quote the user's exact words for any corrections — do not paraphrase
6. **Blockers**: What is preventing completion

### Example Start Prompt
```
Task: Fix chat session picker fields (issue #10)
Files: features/chat.js (pickerField function), components/picker.js (openLibrary), views/list.js (toolbar)
Ux7 reference: components/dialog.js lines 54-100 (updateSessionName), view/list.js lines 525-636 (getAdminButtons, getActionButtonBar), view/object.js lines 1304-1344 (preparePicker)
Context: Bug fix sprint. ObjectPicker was fixed in prior sprint (PickerView in layout, library paths). All backend services live.
Acceptance: Picker opens at model default group path (~/Chat). Toolbar shows admin_panel_settings + favorites in picker mode. Build passes, 197 Vitest pass, no console errors.
```

### Example Continue Prompt
```
Continuing: Chat picker fields (issue #10)
Done: pickerField renders with separate search/clear buttons, openLibrary resolves userContainerId
Remaining: Verify favorites button appears in picker toolbar, test session creation end-to-end
Open issues: User corrected 3x that search icon must NOT be inside text span. User corrected 2x about favorites in picker mode.
Files: chat.js:687-715 (pickerField), picker.js:155-204 (openLibrary), list.js:859-982 (toolbar)
Test status: 197 Vitest pass, build passes. Browser testing needed.
User feedback (exact): "THE PICKER LIST NEEDS FAVORITE AND SYSTEM BUTTON, AND GROUP SCOPE DEFAULTING TO MODEL DEFAULT GROUP PATH"
```
