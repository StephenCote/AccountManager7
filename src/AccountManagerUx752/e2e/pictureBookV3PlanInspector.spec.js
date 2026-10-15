/**
 * PictureBook V3 P2-9 — "Plan & generate" dialog, estimate display and the plan inspector (design §26),
 * live on the Docker stack as e2etest_shared.
 *
 * The reader's "Plan & generate" button POSTs /{book}/scene/{s}/plan and shows the plan AS RECEIVED:
 * strategy, estimate (steps + the estimate source verbatim when unmeasured), degradations (code: message),
 * canvas, and — behind "View plan" — the shared PbPlanInspector (models by task, compiled prompts,
 * degradations, config). "Generate" POSTs /render-plan with no client-side deadline and shows the server's
 * status + message verbatim. The same inspector is reachable from the workflow canvas node detail
 * ("View plan" on a node that has a renderPlan artifact) and from the review page ("Plan").
 *
 * What this spec proves:
 *   1. [no SD needed] open the dialog on scene 1 → the strategy / estimate / degradations / canvas shown
 *      equal the JSON a direct POST /plan returns; "View plan" shows one model row per plan.models key
 *      ('unresolved' when the plan carries no model), the BASE stage with the compiled prompt, and one
 *      degradation <li data-code> per plan.degradations entry; the preview preset select re-plans with
 *      {preset:'DRAFT'} and shows the preview-only note; Close.
 *   2. [only while the renderer inventory is unavailable] Generate → the 503 text verbatim in the dialog
 *      (dialog stays open, no toast), the plan artifact id in that message resolves through
 *      GET /{book}/artifact/{oid} to a renderPlan artifact whose artifactText is the plan, and the
 *      workflow now has one RENDER_PLAN node scoped to the scene. Skipped when the inventory answers.
 *   3. [needs 2] the canvas: select that node → "View plan" → the inspector with `rev 1 · <oid>`.
 *   4. [SD] (PB_SD_TESTS=1 and the inventory available) Generate succeeds: dialog closes, toast
 *      'Scene rendered (artifact …)', the page image appears; the review page lists the node and its
 *      "Plan" control shows the inspector.
 *
 * Run:
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3PlanInspector.spec.js --workers=1 --project=chromium --retries=0
 *
 * ensureSharedTestUser only (never admin). Timings are logged, never asserted. Screenshots: test-results/p29-plan/.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { mkdirSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/p29-plan';
const SETTING = 'a narrow cobbled harbour street at dusk, lanterns being lit along the quay';
const ACTION = 'a fisherman coils rope beside an upturned boat while gulls settle on the wall';
const MOOD = 'quiet, end-of-day';

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }

let shared = null;

function sharedCredential() {
    if (!shared) throw new Error('login before ensureSharedTestUser() — the beforeAll must set the shared user first');
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
}

async function login(request) {
    const resp = await request.post(REST + '/login', { data: sharedCredential() });
    expect(resp.ok() || resp.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', { data: sharedCredential() });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login as ' + shared.testUserName + ' failed: HTTP ' + resp.status());
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1; window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(() => window.location.hash.includes('/main') && document.querySelector('[role="main"]'), { timeout: 30000 });
}

async function search(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', { data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false } });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}

async function planScene(request, bookOid, sceneOid, body) {
    const resp = await request.post(PB_ROOT + '/' + bookOid + '/scene/' + sceneOid + '/plan', { data: body || {}, timeout: 900000 });
    const text = await resp.text();
    expect(resp.ok(), 'POST /plan: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function modelsForTask(request, task) {
    const resp = await request.get(REST + '/olio/illustration/models?task=' + encodeURIComponent(task), { timeout: 300000 });
    const text = await resp.text();
    expect(resp.ok(), 'GET /illustration/models?task=' + task + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function openReader(page, bookOid) {
    await page.goto('/#!/picture-book/v2/' + bookOid, { timeout: 30000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible({ timeout: 30000 });
}

async function openFirstPage(page) {
    await page.locator('button', { hasText: 'Begin' }).click();
    // The reader prints the counter twice (header span + page footer); strict mode needs .first().
    await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible({ timeout: 30000 });
}

async function openPlanDialog(page) {
    const btn = page.getByTestId('pb2-plan-render');
    await expect(btn).toBeVisible({ timeout: 30000 });
    await expect(btn).toHaveAttribute('title', 'Preview the illustration plan and estimate, then generate');
    const t0 = Date.now();
    await btn.click();
    await expect(page.getByTestId('pb2-plan-dialog')).toBeVisible({ timeout: 30000 });
    // Planning resolves models against the SD inventory; while that server is unreachable each read blocks for
    // its connect timeout (~42 s measured, 212 s per plan). Generous wait; elapsed time logged, never asserted.
    await expect(page.getByTestId('pb2-plan-loading')).toHaveCount(0, { timeout: 900000 });
    console.log('[p29-plan] dialog planned in ' + (Date.now() - t0) + ' ms');
}

let bookObjectId = null;
let slug = null;
const fx = { book: null, scene: null, inventoryUnavailable: null, inventoryError: null, planArtifactOid: null, nodeOid: null };

test.describe('PictureBook V3 P2-9 — Plan & generate dialog, estimate, plan inspector', () => {
    // Budgets are generous on purpose: while the SD server is unreachable the server's inventory read blocks
    // ~42 s per attempt and POST /plan re-reads it several times (212 s measured, C:/tmp/p29-plan-time.log);
    // test 1 plans three times (direct, dialog, DRAFT preview). Timings are logged, never asserted.
    test.describe.configure({ mode: 'serial', timeout: 2400000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await login(request);
        const ts = Date.now().toString(36);
        slug = 'p29-plan-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'P2-9 Plan ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();

        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId', 'bookType']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        fx.book = { id: book.id, objectId: book.objectId, name: book.name, slug: book.slug, groupId: book.groupId, groupPath: book.groupPath };

        const sceneResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.scene', name: 'Scene ' + slug + ' 0', groupId: book.groupId, groupPath: book.groupPath,
                book: { schema: 'olio.pb.book', id: book.id, objectId: book.objectId }, sceneIndex: 0,
                title: 'Lanterns on the quay', setting: SETTING, action: ACTION, mood: MOOD }
        });
        const sceneText = await sceneResp.text();
        expect(sceneResp.ok(), 'create olio.pb.scene: HTTP ' + sceneResp.status() + ' ' + sceneText.slice(0, 300)).toBe(true);
        const scene = JSON.parse(sceneText);
        expect(scene && scene.objectId, 'scene identity').toBeTruthy();
        fx.scene = { id: scene.id, objectId: scene.objectId, sceneIndex: 0 };

        const wf = await request.get(PB_ROOT + '/' + bookObjectId + '/workflow');
        expect(wf.status(), 'a fresh book has no workflow yet').toBe(404);
        const inv = await modelsForTask(request, 'sceneDirect');
        fx.inventoryUnavailable = !!(inv.inventory && inv.inventory.error);
        fx.inventoryError = inv.inventory ? inv.inventory.error : null;
        console.log('[p29-plan-seed] ' + JSON.stringify({ book: bookObjectId, slug, scene: fx.scene, inventory: inv.inventory, deploymentDefault: inv.deploymentDefault, SD_ENABLED }));
        mkdirSync(OUT_DIR, { recursive: true });
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        if (!bookObjectId) return;
        console.log('[p29-plan-cleanup] left in place as the record of the run: ' + JSON.stringify({ book: bookObjectId, slug, ...fx }));
    });

    test('1. the dialog shows the plan as POST /plan returns it; View plan opens the inspector; the preview preset re-plans', async ({ page, request }) => {
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        await login(request);
        const t0 = Date.now();
        const plan = await planScene(request, bookObjectId, fx.scene.objectId, {});
        console.log('[p29-plan-1] direct POST /plan in ' + (Date.now() - t0) + ' ms: strategy=' + JSON.stringify(plan.strategy) + ' estimate=' + JSON.stringify(plan.estimate)
            + ' canvas=' + JSON.stringify(plan.canvas) + ' models=' + JSON.stringify(Object.keys(plan.models || {})) + ' degradations=' + JSON.stringify(plan.degradations));
        expect(plan.strategy && plan.strategy.name, 'the plan carries a strategy').toBeTruthy();
        expect(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt, 'the plan carries a compiled BASE prompt').toBeTruthy();
        await request.get(REST + '/logout');

        await loginAsSharedUser(page);
        const planPosts = [];
        page.on('request', req => { if (req.method() === 'POST' && /\/scene\/[^/]+\/plan$/.test(req.url())) planPosts.push(JSON.parse(req.postData() || 'null')); });
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await expect(page.getByTestId('pb2-style-link')).toBeVisible();
        await openPlanDialog(page);
        expect(planPosts, 'the first plan is requested with the style default preset').toEqual([{}]);

        const dialog = page.getByTestId('pb2-plan-dialog');
        await expect(dialog).toContainText('Lanterns on the quay');
        await expect(page.getByTestId('pb2-plan-dialog-strategy')).toHaveText(plan.strategy.name);
        const est = page.getByTestId('pb2-plan-dialog-estimate');
        await expect(est).toContainText('Estimate: ');
        const estimate = plan.estimate || {};
        if (estimate.steps != null) await expect(est).toContainText(estimate.steps + ' steps');
        if (estimate.measured) await expect(est).toContainText('~');
        else if (estimate.source) await expect(est).toContainText(String(estimate.source));
        if (plan.canvas && plan.canvas.width != null) await expect(dialog).toContainText(plan.canvas.width + ' × ' + plan.canvas.height);
        const degradations = Array.isArray(plan.degradations) ? plan.degradations : [];
        if (degradations.length) {
            const dg = page.getByTestId('pb2-plan-dialog-degradations');
            await expect(dg).toBeVisible();
            for (const d of degradations) await expect(dg).toContainText((d.code ? d.code + ': ' : '') + (d.message || ''));
        } else {
            await expect(page.getByTestId('pb2-plan-dialog-degradations')).toHaveCount(0);
        }
        await expect(page.getByTestId('pb2-plan-error')).toHaveCount(0);
        await expect(page.getByTestId('pb2-plan-generate')).toBeEnabled();
        await expect(page.getByTestId('pb2-plan-dialog-close')).toBeEnabled();

        const toggle = page.getByTestId('pb2-plan-inspect-toggle');
        await expect(toggle).toHaveText('View plan');
        await toggle.click();
        await expect(toggle).toHaveText('Hide plan');
        const inspector = page.getByTestId('pb2-plan-inspector');
        await expect(inspector).toBeVisible();
        await expect(page.getByTestId('pb2-plan-strategy')).toHaveText(plan.strategy.name);
        const dialogEstimate = ((await est.textContent()) || '').replace(/^Estimate: /, '');
        expect(dialogEstimate.length, 'dialog estimate text').toBeGreaterThan(0);
        await expect(page.getByTestId('pb2-plan-estimate')).toHaveText(dialogEstimate);
        const tasks = Object.keys(plan.models || {});
        await expect(inspector.locator('tr[data-task]')).toHaveCount(tasks.length);
        for (const t of tasks) {
            const row = inspector.locator('tr[data-task="' + t + '"]');
            const mr = plan.models[t] || {};
            await expect(row).toHaveAttribute('data-degraded', mr.degraded ? '1' : '0');
            if (mr.model) await expect(row).toContainText(mr.model);
            else await expect(row).toContainText('unresolved');
            if (mr.reason) await expect(row).toContainText(String(mr.reason));
        }
        const stage = inspector.locator('[data-testid="pb2-plan-stage"][data-stage="BASE"]');
        await expect(stage).toHaveCount(1);
        await expect(stage).toContainText(plan.compiled.BASE.prompt);
        if (plan.compiled.BASE.estimatedTokens != null) await expect(stage).toContainText('~' + plan.compiled.BASE.estimatedTokens + ' tokens');
        if (degradations.length) {
            await expect(inspector.locator('[data-testid="pb2-plan-degradations"] li[data-code]')).toHaveCount(degradations.length);
            for (const d of degradations) await expect(inspector.locator('li[data-code="' + (d.code || '') + '"]')).toContainText((d.stage ? '[' + d.stage + '] ' : '') + (d.message || ''));
        } else {
            await expect(inspector).toContainText('None.');
        }
        if (plan.config && plan.config.model) await expect(inspector).toContainText(plan.config.model);
        await page.screenshot({ path: OUT_DIR + '/01-dialog-inspector.png', fullPage: true });

        // Preview preset: re-plans with {preset:'DRAFT'}; the note says Generate ignores it.
        await page.getByTestId('pb2-plan-preset').selectOption('DRAFT');
        await expect(page.getByTestId('pb2-plan-loading')).toHaveCount(0, { timeout: 900000 });
        await expect.poll(() => planPosts.length, { timeout: 30000 }).toBe(2);
        expect(planPosts[1]).toEqual({ preset: 'DRAFT' });
        await expect(dialog).toContainText('The preset above only changes this preview; Generate uses the book style\'s quality preset.');
        await expect(page.getByTestId('pb2-plan-dialog-strategy').locator('xpath=following-sibling::span[1]')).toHaveText('DRAFT');
        await expect(page.getByTestId('pb2-plan-error')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/01-dialog-draft.png', fullPage: true });

        await page.getByTestId('pb2-plan-dialog-close').click();
        await expect(page.getByTestId('pb2-plan-dialog')).toHaveCount(0);
        await expect(page.getByTestId('pb2-plan-render')).toBeEnabled();
    });

    test('2. [inventory unavailable] Generate shows the 503 verbatim, keeps the dialog open, and the plan artifact + RENDER_PLAN node it names exist', async ({ page, request }) => {
        test.skip(!fx.inventoryUnavailable, 'the renderer inventory is available (' + JSON.stringify(fx.inventoryError) + ') — the pre-render 503 cannot occur; test 4 covers the render');
        await loginAsSharedUser(page);
        const renderPosts = [];
        page.on('request', req => { if (req.method() === 'POST' && /\/render-plan$/.test(req.url())) renderPosts.push({ body: req.postData() }); });
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await openPlanDialog(page);
        await expect(page.getByTestId('pb2-plan-generate')).toBeEnabled();
        const t0 = Date.now();
        await page.getByTestId('pb2-plan-generate').click();
        const err = page.getByTestId('pb2-plan-error');
        await expect(err).toBeVisible({ timeout: 900000 });
        console.log('[p29-plan-2] render-plan answered in ' + (Date.now() - t0) + ' ms');
        const text = (await err.textContent()) || '';
        console.log('[p29-plan-2] dialog error: ' + text);
        expect(renderPosts.length, 'one POST /render-plan').toBe(1);
        expect(renderPosts[0].body == null || renderPosts[0].body === '', 'render-plan is sent without a body').toBe(true);
        // The server names the scene by its sceneIndex (the fixture's is 0), not by its 1-based page number.
        expect(text.startsWith('(503) Renderer inventory unavailable; cannot verify a model for scene ' + fx.scene.sceneIndex + ' before rendering - '), text).toBe(true);
        expect(text).toContain('INVENTORY_UNAVAILABLE');
        const m = text.match(/\(plan artifact ([0-9a-f-]{36})\)$/);
        expect(m, 'the 503 names the persisted plan artifact: ' + text).toBeTruthy();
        fx.planArtifactOid = m[1];
        await expect(page.getByTestId('pb2-plan-dialog')).toBeVisible();
        await expect(page.getByTestId('pb2-plan-rendering')).toHaveCount(0);
        await expect(page.getByTestId('pb2-plan-generate')).toBeEnabled();
        await expect(page.locator('.toast-text', { hasText: 'Renderer inventory unavailable' })).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/02-generate-503.png', fullPage: true });

        await login(request);
        const artResp = await request.get(PB_ROOT + '/' + bookObjectId + '/artifact/' + fx.planArtifactOid);
        const artText = await artResp.text();
        expect(artResp.ok(), 'GET /artifact/' + fx.planArtifactOid + ': HTTP ' + artResp.status() + ' ' + artText.slice(0, 300)).toBe(true);
        const art = JSON.parse(artText);
        expect(art.role).toBe('renderPlan');
        expect(art.revision).toBe(1);
        const stored = JSON.parse(art.artifactText);
        expect(stored.strategy && stored.strategy.name).toBeTruthy();
        expect(stored.compiled && stored.compiled.BASE && stored.compiled.BASE.prompt).toBeTruthy();
        const wfResp = await request.get(PB_ROOT + '/' + bookObjectId + '/workflow');
        expect(wfResp.ok(), 'GET /workflow: HTTP ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        const planNodes = (wf.nodes || []).filter(n => upper(n.nodeType) === 'RENDER_PLAN');
        expect(planNodes.length, 'one RENDER_PLAN node: ' + JSON.stringify(wf.nodes)).toBe(1);
        expect(planNodes[0].scopeRef).toBe(fx.scene.objectId);
        fx.nodeOid = planNodes[0].objectId;
        console.log('[p29-plan-2] plan artifact ' + fx.planArtifactOid + ' (' + art.byteLength + ' bytes) on node ' + fx.nodeOid);
        await request.get(REST + '/logout');
    });

    test('3. the canvas node detail: "View plan" on the RENDER_PLAN node shows the same inspector with its revision', async ({ page }) => {
        test.skip(!fx.nodeOid, 'needs the RENDER_PLAN node from test 2');
        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book/' + bookObjectId + '/workflow', { timeout: 30000 });
        const node = page.locator('[data-node-id="' + fx.nodeOid + '"]');
        await expect(node).toBeVisible({ timeout: 60000 });
        await node.click();
        const toggle = page.getByTestId('pbwf-node-plan-toggle');
        await expect(toggle).toBeVisible({ timeout: 30000 });
        await expect(toggle).toHaveText('View plan');
        await toggle.click();
        await expect(toggle).toHaveText('Hide plan', { timeout: 30000 });
        const holder = page.getByTestId('pbwf-node-plan');
        await expect(holder).toContainText('rev 1 · ' + fx.planArtifactOid);
        await expect(holder.getByTestId('pb2-plan-inspector')).toBeVisible();
        await expect(holder.locator('[data-testid="pb2-plan-stage"][data-stage="BASE"]')).toHaveCount(1);
        await expect(page.getByTestId('pbwf-node-plan-error')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/03-canvas-plan.png', fullPage: true });
        await toggle.click();
        await expect(toggle).toHaveText('View plan');
        await expect(holder.getByTestId('pb2-plan-inspector')).toHaveCount(0);
    });

    test('4. [SD] Generate renders the scene; the review page lists the node and "Plan" shows its inspector', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render');
        test.skip(fx.inventoryUnavailable, 'the renderer inventory is unavailable (' + JSON.stringify(fx.inventoryError) + ') — nothing can render');
        await loginAsSharedUser(page);
        await openReader(page, bookObjectId);
        await openFirstPage(page);
        await openPlanDialog(page);
        const t0 = Date.now();
        await page.getByTestId('pb2-plan-generate').click();
        await expect(page.getByTestId('pb2-plan-rendering')).toBeVisible();
        const toast = page.locator('.toast-text', { hasText: /^Scene rendered \(artifact [0-9a-f-]{36}\)$/ });
        await expect(toast).toBeVisible({ timeout: 900000 });
        console.log('[p29-plan-4] rendered in ' + (Date.now() - t0) + ' ms: ' + (await toast.textContent()));
        await expect(page.getByTestId('pb2-plan-dialog')).toHaveCount(0);
        await expect(page.getByTestId('pb2-scene-image')).toBeVisible({ timeout: 60000 });
        await page.screenshot({ path: OUT_DIR + '/04-rendered.png', fullPage: true });

        await login(request);
        const wf = await (await request.get(PB_ROOT + '/' + bookObjectId + '/workflow')).json();
        const planNode = (wf.nodes || []).find(n => upper(n.nodeType) === 'RENDER_PLAN' && n.scopeRef === fx.scene.objectId);
        expect(planNode, 'RENDER_PLAN node for the scene').toBeTruthy();
        await request.get(REST + '/logout');

        await page.goto('/#!/picture-book/' + bookObjectId + '/review', { timeout: 30000 });
        const row = page.locator('[data-testid="pb2-review-node"][data-node-id="' + planNode.objectId + '"]');
        await expect(row).toBeVisible({ timeout: 60000 });
        const planToggle = row.getByTestId('pb2-review-plan-toggle');
        await expect(planToggle).toHaveText('Plan');
        await planToggle.click();
        await expect(planToggle).toHaveText('Hide plan', { timeout: 30000 });
        await expect(row.getByTestId('pb2-review-plan').getByTestId('pb2-plan-inspector')).toBeVisible();
        await expect(row.getByTestId('pb2-review-plan-error')).toHaveCount(0);
        await page.screenshot({ path: OUT_DIR + '/04-review-plan.png', fullPage: true });
    });
});
