All PB2 phases done as of 2026-08-23: Phase 0-4 (Objects7 + REST), Phase 5a+5b (Ux752 Playwright 13/13), Phase 6 (migration), Phase 6b (interactive canvas backend), Phase 6c S1-S6 (SD config persistability), **Phase 1b** (universe/world IDs threaded through Service7+Ux, Playwright gate 2/2 passed + 4 skipped for Docker, exit 0, 2026-08-22).

**Why:** Phase 1b added `getBookContextByIds` to `PbOlioContextUtil`, `resolveOlioContext` helper in `GameService`, and `am7olio.setCurrentBook / currentWorldObjectId / withBookContext` in `olio.js`; `pictureBook.js` calls `setCurrentBook` when a book opens/closes; `adoptCharacter.js` uses `withBookContext`.

Gate: Playwright `pictureBookWorldSwitch.spec.js` 2/2 REST tests passed (4 browser tests skip without Docker stack — require `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`).

**Only remaining phase:**
- Phase 3b: ComfyUI backend (optional). Prereq: capture a working graph from the live ComfyUI UI (Save/Export API format or network payload) before writing any Java. ComfyUI is SwarmUI-bundled at localhost:7821 (localhost-only) / through Swarm at `localhost:7801/ComfyBackendDirect/...` (works off-box). The plan's Q12 question: one-node-one-call first, Swarm stays default; multi-node batch is the payoff but only after the one-call path works.

**How to apply:** Phase 3b cannot start until someone captures the live graph JSON from the ComfyUI UI. No code until that artifact exists.
