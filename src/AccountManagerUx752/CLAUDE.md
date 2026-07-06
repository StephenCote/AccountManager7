# AccountManagerUx752 — Focused Refactor

## MANDATORY rules — read first

Working-discipline rules (NO LYING, honesty, testing, About The Codebase Author) live in the
canonical file **`../.claude/rules/llm-conduct.md`**. Architecture/layering rules live in
**`../.claude/rules/architecture.md`**. Read them before coding. Highlights that bite here:
- "Tested" = a Playwright/Vitest test actually run against the live backend. No fake tests.
- NEVER use the admin user — use `ensureSharedTestUser()` from `e2e/helpers/api.js`.
- **DECISION AUDIT:** flag every non-trivial decision ✓ FOLLOWING INSTRUCTIONS or ⚠ MY JUDGMENT; if ⚠, ask first.

## Design Documents
- `../aiDocs/Ux752Plan.md` — standing orders, evidence analyst mode, guiding principles, prompt template
- `../aiDocs/Ux752ImplementationPlan.md` — 5 phases, filled-in prompt templates, file-level change register

## What This Project Is
New project forked from Ux75. Surgical refactor of `views/list.js` and `components/decorator.js`, fixing what's broken while preserving everything that works. ~95% of the codebase is untouched.
**This module (Ux752) is now the primary/canonical UI reference for the project.** Ux7 is deprecated legacy.

## Critical Config
- All backend services LIVE at localhost:8443
- Test user: `ensureSharedTestUser()` from `e2e/helpers/api.js` — NEVER use admin
- Legacy reference (deprecated): `../deprecated/AccountManagerUx7/client/` — consult when you need to understand a pattern's origin
- Build: Vite 6, `npx vite build`
- Unit tests: Vitest, `npx vitest run`
- E2E tests: Playwright, `npx playwright test`

## Content Policy
- ALL wear levels valid including NONE — do NOT skip/filter/censor apparel or character art content

## API Gotchas
- `am7client.member()` sField param = **field name** (e.g. `"apparel"`), NOT participantModel value
- `groupId` = directory's **numeric `.id`**, NOT `.objectId` UUID
- Enums sent as **lowercase** in JSON, read as **UPPERCASE**
