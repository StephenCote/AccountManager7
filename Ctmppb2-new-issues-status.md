New batch of 8 PB2/ChapBook issues addressed 2026-08-29. All compile; 478/478 Vitest green; vite build clean. Playwright E2E not run (Docker stack down — user must bring up to test).

**Issue 7**: `ChapBookUtil.createChapBookScene()` takes new 8th `chatConfig` param; calls `PictureBookUtil.callLlmForChapBook()` with stanza text to generate LLM SD prompts. Falls back to text excerpt when chatConfig null.

**Issue 13**: `PictureBookService.createFromScenes` now catches `RuntimeException` in addition to `PictureBookException` and returns `{"error":"...","cause":"..."}` JSON. Previously returned HTML 500 that JS could not parse. `sceneExtractor.js` reads `body.cause`. `pictureBook.js` catch block now opens a modal dialog (not toast).

**Issue 1**: `navigateUp()` in picker mode fetches parent group via `am7client.getFull` when `pg.container` is null; `navigatingUp` flag prevents re-entrancy. Picker dialog shows current `container.path`.

**Issue 3**: Poem row key changed to `objectId + '-' + (sel?'1':'0')` to force Mithril checkbox recreation on clear.

**Issue 4**: `prevRoute` tracking in `list.js`; when returning from `/new/` or `/pnew/` to `/list/`, calls `pagination.new()` to bust cache.

**Issue 8**: `ChapBookReview` render button now calls `openRenderConfigDialog()` instead of `renderChapBook()` directly; `renderRenderDialog()` added to view.

**Issue 9**: `pictureBook.js` now checks `page.context().roles.user`; shows yellow warning banner and disables Extract/Continue when missing.

**Issue 12**: Type picker popover now `position:fixed` using `getBoundingClientRect()` so it's not clipped by overflow:hidden ancestors. Breadcrumb type icon wired to `page.components.toggleTypePicker`.

**Key gotcha**: RuntimeException escaping a Jersey endpoint returns HTML not JSON — always catch both domain exception AND RuntimeException in Service7 endpoints.
