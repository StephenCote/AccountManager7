/**
 * PictureBook Read / Edit navigation helpers for e2e specs.
 *
 * Since 2026-11-19 (aiDocs/PictureBookUxConsistencyDesign.md §3–§7) the PictureBook is split into two modes:
 *   Read  /picture-book/v2/:id                      — bookHeader (book-header[data-mode=read]), no edit controls
 *   Edit  /picture-book/:id/edit/:section[/:item]   — bookWorkspace: rail ws-rail-<section>, pane ws-pane[data-section]
 *
 * The old reader "Tools" overflow (pb2-tools-menu) and every editing control it held moved into Edit sections. The
 * helpers below keep their old names so the specs that used them still read the same; each one now goes to the Edit
 * section that owns the old control:
 *
 *   old reader-tool testid       → Edit section   (what clickReaderTool does after arriving)
 *   pb2-locations-link           → locations      (nothing more — the section IS the old page)
 *   pb2-compose-link             → scenes         (nothing more; the Composition tab is on each scene)
 *   pb2-cover-link               → cover
 *   pb2-review-link              → review
 *   pb2-workflow-link            → canvas
 *   pb2-style-link               → style
 *   pb2-characters-link          → characters
 *   pb2-render-all               → render
 *   pb2-chat-config              → book           (then clicks the Book section's pb2-chat-config control → picker)
 *   pb2-auto-approve-badge       → book           (then clicks the Book section's pb2-auto-approve-badge checkbox)
 *
 * Navigation is through the UI: from Read it clicks the header's Edit toggle (book-mode-edit), then the rail row.
 * From an Edit route it clicks the rail row directly.
 */
import { expect } from '@playwright/test';

/** The old reader-tool testid → the Edit section that now owns it. */
export const TOOL_SECTION = {
    'pb2-locations-link': 'locations',
    'pb2-compose-link': 'scenes',
    'pb2-cover-link': 'cover',
    'pb2-review-link': 'review',
    'pb2-workflow-link': 'canvas',
    'pb2-style-link': 'style',
    'pb2-characters-link': 'characters',
    'pb2-render-all': 'render',
    'pb2-chat-config': 'book',
    'pb2-auto-approve-badge': 'book'
};

/** Old tool testids that are a control inside their section (clicked after arriving), not just a link to it. */
const TOOL_IS_CONTROL = { 'pb2-chat-config': true, 'pb2-auto-approve-badge': true };

/** The book objectId of the current PB route (Read /picture-book/v2/:id or Edit /picture-book/:id/edit/…). */
export function currentBookId(page) {
    const h = decodeURIComponent(new URL(page.url()).hash.replace(/^#!/, ''));
    let mm = h.match(/^\/picture-book\/v2\/([^/?]+)/);
    if (mm) return mm[1];
    mm = h.match(/^\/picture-book\/([^/?]+)\/edit/);
    return mm ? mm[1] : null;
}

/**
 * Open Edit › <section> of the current book through the UI: Read → book-mode-edit → ws-rail-<section>.
 * Resolves once ws-pane carries data-section=<section>.
 */
export async function openEditSection(page, section, opts) {
    const timeout = (opts && opts.timeout) || 30000;
    const ws = page.getByTestId('book-workspace');
    if (!(await ws.isVisible().catch(() => false))) {
        const edit = page.getByTestId('book-mode-edit');
        await expect(edit, 'the Read header offers the Edit toggle').toBeVisible({ timeout });
        await edit.click();
        await expect(ws).toBeVisible({ timeout });
        // the bare Edit entry first resolves a section (D6), then replaces the route
        await expect(page.getByTestId('ws-pane')).toBeVisible({ timeout });
    }
    const pane = page.getByTestId('ws-pane');
    if ((await pane.getAttribute('data-section')) !== section) {
        await page.getByTestId('ws-rail-' + section).click();
    }
    await expect(pane).toHaveAttribute('data-section', section, { timeout });
}

/** Go straight to an Edit route by URL (for specs whose subject is the section, not the way there). */
export async function gotoEditSection(page, bookOid, section, item, query, opts) {
    const timeout = (opts && opts.timeout) || 30000;
    let route = '/picture-book/' + bookOid + '/edit/' + section + (item ? '/' + item : '');
    if (query) route += '?' + new URLSearchParams(query).toString();
    await page.goto('/#!' + route, { timeout });
    await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', section, { timeout });
}

/**
 * Edit › Review is master/detail (design §5.8): the list rows are pb2-review-row[data-item-id=<nodeObjectId>] and only
 * the SELECTED node's detail (pb2-review-node[data-node-id], its artifact rows, its plan shown expanded) is rendered.
 * Open the section with that node selected (the :item of the route) and return the detail locator.
 */
export async function openReviewNode(page, bookOid, nodeOid, opts) {
    const timeout = (opts && opts.timeout) || 60000;
    await page.goto('/#!/picture-book/' + bookOid + '/edit/review/' + nodeOid, { timeout: 30000 });
    await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'review', { timeout });
    const node = page.locator('[data-testid="pb2-review-node"][data-node-id="' + nodeOid + '"]');
    await expect(node).toBeVisible({ timeout });
    return node;
}

/** Select a node in an already-open Edit › Review by clicking its list row; returns the detail locator. */
export async function selectReviewNode(page, nodeOid, opts) {
    const timeout = (opts && opts.timeout) || 60000;
    const row = page.locator('[data-testid="pb2-review-row"][data-item-id="' + nodeOid + '"]');
    await expect(row).toBeVisible({ timeout });
    if ((await row.getAttribute('data-selected')) !== '1') await row.click();
    const node = page.locator('[data-testid="pb2-review-node"][data-node-id="' + nodeOid + '"]');
    await expect(node).toBeVisible({ timeout });
    return node;
}

/**
 * From a Read scene page: its "Edit this page" pencil (pb2-edit-page) → Edit › Scenes › that scene, then the given
 * tab (default 'render', where pb2-scene-render / pb2-scene-regen-background / pb2-plan-render now live, §5.6).
 * Returns the scene objectId the reader page carried.
 */
export async function openPageInEdit(page, tab, opts) {
    const timeout = (opts && opts.timeout) || 30000;
    const t = tab || 'render';
    const sceneId = await page.getByTestId('pb2-scene-page').getAttribute('data-scene-id');
    await page.getByTestId('pb2-edit-page').click();
    await expect(page.getByTestId('pb-scene-tabs')).toBeVisible({ timeout });
    if (t !== 'page') {
        await page.getByTestId('pb-scene-tab-' + t).click();
        await expect(page.getByTestId('pb-scene-tab-' + t)).toHaveAttribute('aria-selected', 'true', { timeout });
    }
    return sceneId;
}

/**
 * The run panel (pb2-run-progress / pb2-run-stage / pb2-run-counter / -cancel / -dismiss) renders in the Edit workspace's
 * activity dock (design §4.5) — pinned open on Edit › Render, collapsed elsewhere. Expand it if collapsed.
 */
export async function openActivityDock(page, opts) {
    const timeout = (opts && opts.timeout) || 60000;
    const dock = page.getByTestId('ws-dock');
    await expect(dock, 'the activity dock appears once a job is published').toBeVisible({ timeout });
    if ((await dock.getAttribute('data-open')) !== '1') await page.getByTestId('ws-dock-toggle').click();
    await expect(page.getByTestId('ws-dock-panel')).toBeVisible({ timeout });
}

/** Switch the current book to Read (header toggle) and wait for the reader. */
export async function switchToRead(page, opts) {
    const timeout = (opts && opts.timeout) || 30000;
    await page.getByTestId('book-mode-read').click();
    await expect(page.getByTestId('pb2-reader')).toBeVisible({ timeout });
}

/**
 * Former "open the Tools overflow". There is no overflow any more; this opens Edit (the section the D6 resolver
 * picks) so a following clickReaderTool / locator finds the workspace. Kept for old call sites.
 */
export async function openReaderTools(page, opts) {
    const timeout = (opts && opts.timeout) || 30000;
    const ws = page.getByTestId('book-workspace');
    if (await ws.isVisible().catch(() => false)) return;
    await page.getByTestId('book-mode-edit').click();
    await expect(ws).toBeVisible({ timeout });
    await expect(page.getByTestId('ws-pane')).toBeVisible({ timeout });
}

/** Former "close the Tools overflow": a no-op now (there is no menu to close). */
export async function closeReaderTools(page) { /* no overflow menu in the Read | Edit UI */ }

/**
 * Former "click a Tools item": go to the Edit section that owns the old item (TOOL_SECTION) and, for the two
 * controls that live inside Edit › Book (pb2-chat-config, pb2-auto-approve-badge), click that control there.
 */
export async function clickReaderTool(page, testid, opts) {
    const section = TOOL_SECTION[testid];
    if (!section) throw new Error('clickReaderTool: no Edit section is mapped for the old reader tool "' + testid + '"');
    await openEditSection(page, section, opts);
    if (TOOL_IS_CONTROL[testid]) {
        const ctl = page.getByTestId('ws-pane').getByTestId(testid);
        await expect(ctl).toBeVisible({ timeout: (opts && opts.timeout) || 30000 });
        await ctl.click();
    }
}

/** The description e2e fixtures give a book whose test is not about style (the render gate needs one). */
export const E2E_STYLE_DESCRIPTION = 'soft watercolor storybook illustration, warm muted palette';

/**
 * Since 2026-11-16 scene render / render-plan / regenerate-background / render-all refuse (409, and the
 * UI disables Render) until the book's EFFECTIVE style has a description. A fixture book that is
 * not testing style gets one here: GET /{book}/style, and PUT {description} only when the effective
 * description is blank, so a book that inherits one from its series is left alone.
 * `pbRoot` is the …/rest/olio/picture-book root.
 */
export async function ensureBookStyleDescription(request, pbRoot, bookOid, description) {
    const url = pbRoot + '/' + bookOid + '/style';
    const get = await request.get(url);
    const getText = await get.text();
    expect(get.ok(), 'GET /style: HTTP ' + get.status() + ' ' + getText.slice(0, 300)).toBe(true);
    const st = JSON.parse(getText);
    const cur = st && st.effective && st.effective.fields ? st.effective.fields.description : null;
    if (cur != null && String(cur).trim() !== '') return st;
    const put = await request.put(url, { data: { description: description || E2E_STYLE_DESCRIPTION } });
    const putText = await put.text();
    expect(put.ok(), 'PUT /style {description}: HTTP ' + put.status() + ' ' + putText.slice(0, 300)).toBe(true);
    return JSON.parse(putText);
}

/**
 * After the create wizard's Step 2 Continue (which now closes the dialog and routes to
 * /picture-book/:id/edit/characters?guided=1, design §7.1): wait for the workspace, open Edit › Style, fill
 * pb2-style-description when it is blank and Save, then switch to Read so callers that expected the reader still
 * land there. Returns once the reader is mounted.
 */
export async function completeWizardStyleStep(page, description, opts) {
    const timeout = (opts && opts.timeout) || 60000;
    await expect(page.getByTestId('book-workspace'), 'Step 2 Continue hands off to the Edit workspace').toBeVisible({ timeout });
    await expect(page.getByTestId('ws-pane')).toBeVisible({ timeout });
    await page.getByTestId('ws-rail-style').click();
    await expect(page.getByTestId('ws-pane')).toHaveAttribute('data-section', 'style', { timeout });
    const desc = page.getByTestId('pb2-style-description');
    await expect(desc).toBeVisible({ timeout });
    if (((await desc.inputValue()) || '').trim() === '') {
        await desc.fill(description || E2E_STYLE_DESCRIPTION);
        const save = page.getByTestId('pb2-style-save');
        await expect(save).toBeEnabled({ timeout });
        await save.click();
        // the section's dirty mark clears once the PUT /style landed
        await expect(page.locator('[data-testid="ws-rail-style"] [data-rail-dirty]')).toHaveCount(0, { timeout });
    }
    // the rail's Style mark is ✓ only once the SAVED effective style has a description (the render gate's check)
    await expect(page.getByTestId('ws-rail-style'), 'Style is saved with a description').toHaveAttribute('data-status', 'ok', { timeout });
    await page.getByTestId('book-mode-read').click();
    await expect(page.getByTestId('pb2-reader')).toBeVisible({ timeout });
}
