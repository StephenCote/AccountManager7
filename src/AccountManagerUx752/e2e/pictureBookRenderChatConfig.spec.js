/**
 * PictureBook V3 — I-2 (Implementation §6.C 226 a, §24): the reader's Chat Config control rides on every render
 * it submits, and the run records which config it was opened with.
 *
 * Before I-2 the reader's Render / Render all / Plan & generate sent no body, so a render run never named a
 * chat config and the visual-QA hook fell back to the user's default route. Now the three render routes take
 * an optional JSON body `{ chatConfigName }`, resolved AS THE CALLER after the book and scene gates (unknown =
 * 404 "Chat config not found: <name>", absent / blank = no config on the run), written to `run.chatConfig` and
 * answered on the 202 body, on GET /{book}/run/{id}, and in the reader's run banner chip.
 *
 * This is the live counterpart of src/test/pictureBookRenderChatConfig.test.js (the mocked-fetch unit proof)
 * and TestPictureBookRenderChatConfigRoutes (the Service7/H2 gate-order proof, which cannot render). It is the
 * only place the run is seen to CARRY the config end to end: browser pick → wire body → 202 → run DTO → chip.
 *
 * Shape (serial, one book, the shared test user as owner, ensureIso42001TestUser as the stranger — never admin):
 *   beforeAll  resolveChatRoute() (prints `[e2e-llm] route=…`), ensureChatConfig(000-i2-render-llm on that tier),
 *              POST /chapter (a scene-less STORY book) + two olio.pb.scene rows (setting/action/mood prose).
 *   1. REST    an unknown name is 404 "Chat config not found: <name>" on all three routes, before any workflow
 *              or node is materialized — GET /workflow is 404 before and still 404 after.
 *   2. REST    the stranger, naming the owner's real config, gets 404 "Book not found" on all three; nothing made.
 *   3. browser pick 000-i2-render-llm in the reader header → Render page 1 → the POST body is exactly
 *              { chatConfigName }, Content-Type application/json, 202 `chatConfig {objectId, name}`; the run banner
 *              chip names it; GET /run/{id} carries the same ref; the run reaches a terminal state (COMPLETED).
 *   4. REST    Render all with NO body (the pre-226a wire contract) opens a run with no `chatConfig`; the job
 *              is polled to terminal so the next spec never finds a node in flight.
 *
 * Timings are logged as observations, never asserted; ceilings exist only so a dead watcher fails instead of
 * hanging. The two renders go to the SD server the stack is configured for (Swarm .39); the config's connection
 * is whatever `resolveChatRoute()` picked. Fixture rows are left in place in the resettable stack DB.
 *
 * Run (Docker stack at :9443, dist current via `npx vite build` + `docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/`):
 *   PB_LLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookRenderChatConfig.spec.js --workers=1 --project=chromium --retries=0
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensureIso42001TestUser, resolveChatRoute, ensureChatConfig } from './helpers/api.js';
import { REST, PB_REST, restLoginShared, loginAsSharedUser } from './helpers/chapbook.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TAG = '[i2-render-cfg]';
const OUT_DIR = path.resolve(__dirname, '../test-results/i2-render-chat-config');
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const CONFIG_NAME = '000-i2-render-llm';     // '000-' sorts onto the ObjectPicker's first page
const CONN_NAME = '000-i2-render-conn';
const MISSING_CONFIG = 'i2-no-such-config-' + Date.now().toString(36);
const SETTING = 'a narrow cobbled lane between leaning timber houses, lanterns just lit, rain shining on the stones';
const ACTION = 'a courier in a long coat pauses under an archway to read a letter';
const MOOD = 'hushed, expectant';

// Ceilings only (a dead watcher must fail, not hang).
const T = { ui: 30000, render: 1500000, renderAll: 3600000 };

const stamp = Date.now().toString(36);
const fx = { shared: null, stranger: null, route: null, chatConfig: null, book: null, scenes: [], run1: null, run2: null };
const consoleErrors = [];

function b64(str) { return Buffer.from(str).toString('base64'); }
function PB() { return PB_REST + '/' + fx.book.objectId; }
async function jsonOf(resp) { const t = await resp.text(); try { return JSON.parse(t); } catch (e) { return { _raw: t.slice(0, 600) }; } }
function errorText(body) { return (body && (body.message || body.error)) || (body && body._raw) || JSON.stringify(body); }
async function shot(page, name) {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    const file = path.join(OUT_DIR, name + '.png');
    await page.screenshot({ path: file, fullPage: true });
    return file;
}
async function pbGet(ctx, suffix) {
    const r = await ctx.get(PB() + suffix, { timeout: 300000 });
    return { status: r.status(), body: await jsonOf(r) };
}
async function pbPost(ctx, suffix, body) {
    const opts = { timeout: 900000 };
    if (body !== undefined) opts.data = body;
    const r = await ctx.post(PB() + suffix, opts);
    return { status: r.status(), body: await jsonOf(r) };
}
async function search(ctx, type, fields, requestFields) {
    const resp = await ctx.post(REST + '/model/search', { data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false } });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}
async function seedScene(ctx, bookRef, index, extra) {
    const resp = await ctx.post(REST + '/model', {
        data: Object.assign({ schema: 'olio.pb.scene', name: 'Scene i2 ' + stamp + ' ' + index, groupId: fx.book.groupId, groupPath: fx.book.groupPath, book: bookRef, sceneIndex: index }, extra)
    });
    const text = await resp.text();
    expect(resp.ok(), 'create olio.pb.scene ' + index + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    const scene = JSON.parse(text);
    expect(scene && scene.id && scene.objectId, 'scene identity').toBeTruthy();
    return { id: scene.id, objectId: scene.objectId, index };
}
function credentialFor(who) {
    return { schema: 'auth.credential', organizationPath: '/Development', name: who.testUserName, credential: b64(who.testPassword), type: 'hashed_password' };
}
/** Poll GET /rest/job/{jobId} until terminal; bounded by the ceiling only. */
async function waitForJob(ctx, jobId, ceilingMs) {
    const started = Date.now();
    let last = null;
    while (Date.now() - started < ceilingMs) {
        last = await jsonOf(await ctx.get(REST + '/job/' + jobId));
        if (last && last.terminal) return last;
        await new Promise(r => setTimeout(r, 5000));
    }
    throw new Error('job ' + jobId + ' not terminal after ' + Math.round(ceilingMs / 1000) + ' s: ' + JSON.stringify(last).slice(0, 300));
}
/** Pick a chat config through the real ObjectPicker (pattern: pictureBookNewBookRender.spec.js). */
async function pickFromOverlay(page, configName) {
    const overlay = page.locator('.am7-picker-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });
    await expect(overlay.locator('h3')).toHaveText('Select Chat Config');
    const row = overlay.locator('tr.tabular-row', { hasText: configName });
    await expect(row, 'picker lists ~/Chat/' + configName).toBeVisible({ timeout: 20000 });
    if (!/tabular-row-active/.test((await row.getAttribute('class')) || '')) await row.click();
    await overlay.locator('button:has(span:text-is("check"))').first().click();
    await expect(overlay).toHaveCount(0, { timeout: 10000 });
}
async function openReader(page, bookOid) {
    const target = '/#!/picture-book/v2/' + bookOid;
    if (!page.url().endsWith(target)) await page.goto(target, { timeout: T.ui });
    await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: T.ui });
    await expect(page.locator('button', { hasText: 'Begin' }).or(page.getByText(/^Page \d+ of \d+$/).first())).toBeVisible({ timeout: T.ui });
}
// Land on page 1: Begin from the cover, otherwise chevron_left back (pattern: pictureBookNewBookRender.spec.js).
async function openFirstPage(page) {
    const page1 = page.getByText(/^Page 1 of \d+$/);
    const begin = page.locator('button', { hasText: 'Begin' });
    const prev = page.locator('button:has(span:text-is("chevron_left"))').first();
    for (let i = 0; i < 16 && !(await page1.count()); i++) {
        await expect(page.getByText('Loading scenes...')).toHaveCount(0, { timeout: 15000 }).catch(() => {});
        if (await begin.count()) await begin.first().click({ timeout: 5000 }).catch(() => {});
        else await prev.click({ timeout: 5000 }).catch(() => {});
        await page.waitForTimeout(400);
    }
    await expect(page1.first(), '"Page 1 of N" shown').toBeVisible({ timeout: T.ui });
}

test.describe('PictureBook V3 I-2 — the reader\'s chat config rides on render and is recorded on the run', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });
    test.use({ viewport: { width: 1600, height: 900 }, actionTimeout: T.ui });

    test.beforeAll(async ({ request }) => {
        if (!LLM_ENABLED) return;
        test.setTimeout(10 * 60 * 1000);
        fs.mkdirSync(OUT_DIR, { recursive: true });
        fx.shared = await ensureSharedTestUser(request);
        expect(fx.shared && fx.shared.testUserName, 'shared test user').toBeTruthy();
        fx.stranger = await ensureIso42001TestUser(request);
        expect(fx.stranger.testUserName !== fx.shared.testUserName, 'the stranger is a different principal').toBe(true);
        fx.route = resolveChatRoute();   // prints the authoritative `[e2e-llm] route=…` line itself
        fx.chatConfig = await ensureChatConfig(request, null, { configName: CONFIG_NAME, connectionName: CONN_NAME, requestTimeout: 900 });
        expect(fx.chatConfig, 'chat config ' + CONFIG_NAME + ' provisioned on the resolved LLM tier').toBe(CONFIG_NAME);
        await restLoginShared(request);

        // A fresh STORY book with no workflow and no scenes (POST /chapter), then two scenes with prose.
        const slug = 'i2-cfg-' + stamp;
        const created = await request.post(PB_REST + '/chapter', { data: { slug, title: 'I-2 render chat config ' + stamp }, timeout: 180000 });
        const cb = await jsonOf(created);
        expect(created.status(), 'POST /chapter: ' + JSON.stringify(cb).slice(0, 300)).toBe(200);
        const bookOid = cb.bookObjectId || cb.objectId;
        expect(bookOid, 'seeded book objectId').toBeTruthy();
        const row = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookOid }], ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId']);
        expect(row && row.id && row.groupId && row.groupPath, 'book row: ' + JSON.stringify(row)).toBeTruthy();
        fx.book = { id: row.id, objectId: row.objectId, name: row.name, slug, groupId: row.groupId, groupPath: row.groupPath };
        const bookRef = { schema: 'olio.pb.book', id: row.id, objectId: row.objectId };
        fx.scenes.push(await seedScene(request, bookRef, 0, { title: 'The lane at lamplight', setting: SETTING, action: ACTION, mood: MOOD }));
        fx.scenes.push(await seedScene(request, bookRef, 1, { title: 'The letter', setting: 'the same lane, closer, the archway framing the courier', action: 'the courier folds the letter and looks up', mood: 'resolved' }));
        const wf = await pbGet(request, '/workflow');
        expect(wf.status, 'a fresh /chapter book has no workflow yet').toBe(404);
        console.log(TAG + ' seeded book ' + bookOid + ' slug=' + slug + ' scenes=' + fx.scenes.map(s => s.objectId).join(',') + ' route=' + JSON.stringify(fx.route));
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        console.log(TAG + ' left in place: ' + JSON.stringify({ book: fx.book, scenes: fx.scenes, chatConfig: fx.chatConfig, run1: fx.run1, run2: fx.run2 }));
        console.log(TAG + ' browser console errors (observation): ' + consoleErrors.length + (consoleErrors.length ? '\n  ' + consoleErrors.slice(0, 40).join('\n  ') : ''));
    });

    test('1. REST: an unknown chatConfigName is 404 "Chat config not found: <name>" on all three routes and materializes nothing', async ({ request }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 required: provisions a chat config on the resolved LLM tier and renders on the SD server');
        await restLoginShared(request);
        const expected = 'Chat config not found: ' + MISSING_CONFIG;
        const scene = fx.scenes[0].objectId;
        const probes = [
            ['scene render', await pbPost(request, '/scene/' + scene + '/render?force=true', { chatConfigName: MISSING_CONFIG })],
            ['render-all', await pbPost(request, '/render-all', { chatConfigName: MISSING_CONFIG })],
            ['render-plan', await pbPost(request, '/scene/' + scene + '/render-plan', { chatConfigName: MISSING_CONFIG })],
            ['render-plan (padded name is trimmed)', await pbPost(request, '/scene/' + scene + '/render-plan', { chatConfigName: '  ' + MISSING_CONFIG + ' ' })]
        ];
        for (const [label, r] of probes) {
            console.log(TAG + ' unknown config → ' + label + ': HTTP ' + r.status + ' ' + JSON.stringify(r.body).slice(0, 200));
            expect(r.status, label + ' with an unknown config: ' + JSON.stringify(r.body).slice(0, 300)).toBe(404);
            expect(errorText(r.body), label + ' names the missing config').toBe(expected);
        }
        // Resolved after the book/scene gates and BEFORE ensureWorkflow: nothing was created.
        const wf = await pbGet(request, '/workflow');
        expect(wf.status, 'GET /workflow is still 404 after the four refused probes: ' + JSON.stringify(wf.body).slice(0, 200)).toBe(404);
        await request.get(REST + '/logout');
    });

    test('2. REST: a stranger naming the owner\'s real config gets 404 "Book not found" on all three routes; nothing is created', async ({ request, playwright }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 required');
        const sctx = await playwright.request.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const login = await sctx.post(REST + '/login', { data: credentialFor(fx.stranger) });
            expect(login.ok() || login.status() === 204, 'login as ' + fx.stranger.testUserName + ': HTTP ' + login.status()).toBe(true);
            const scene = fx.scenes[0].objectId;
            const probes = [
                ['scene render', await pbPost(sctx, '/scene/' + scene + '/render?force=true', { chatConfigName: CONFIG_NAME })],
                ['render-all', await pbPost(sctx, '/render-all', { chatConfigName: CONFIG_NAME })],
                ['render-plan', await pbPost(sctx, '/scene/' + scene + '/render-plan', { chatConfigName: CONFIG_NAME })]
            ];
            for (const [label, r] of probes) {
                console.log(TAG + ' stranger → ' + label + ': HTTP ' + r.status + ' ' + JSON.stringify(r.body).slice(0, 200));
                expect(r.status, 'stranger ' + label + ': ' + JSON.stringify(r.body).slice(0, 300)).toBe(404);
                expect(errorText(r.body), 'absent and denied collapse to the same 404 (the config is never consulted)').toBe('Book not found');
            }
            await sctx.get(REST + '/logout');
        } finally {
            await sctx.dispose();
        }
        await restLoginShared(request);
        const wf = await pbGet(request, '/workflow');
        expect(wf.status, 'owner: GET /workflow is still 404 after the stranger\'s probes').toBe(404);
        await request.get(REST + '/logout');
    });

    test('3. reader: the picked Chat Config is the render body, the 202 ref, the run chip and GET /run/{id}', async ({ page, request }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 required');
        test.setTimeout(T.render + 10 * 60 * 1000);
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text().slice(0, 300)); });
        page.on('pageerror', err => consoleErrors.push('pageerror: ' + String(err && err.message).slice(0, 300)));
        await loginAsSharedUser(page);
        await restLoginShared(request);

        await openReader(page, fx.book.objectId);
        const control = page.getByTestId('pb2-chat-config');
        await expect(control, 'the reader header shows the Chat Config control').toBeVisible({ timeout: T.ui });
        await expect(control).not.toContainText('Resolving default', { timeout: T.ui });
        const labelBefore = ((await control.textContent()) || '').replace(/search$/, '').trim();
        await shot(page, '03a-reader-before-pick');
        await control.click();
        await pickFromOverlay(page, CONFIG_NAME);
        await expect(control, 'the control shows the picked config').toContainText(CONFIG_NAME);
        await shot(page, '03b-reader-picked');

        await openFirstPage(page);
        const renderBtn = page.getByTestId('pb2-scene-render');
        await expect(renderBtn).toBeVisible({ timeout: T.ui });
        const renderResp = page.waitForResponse(r => r.request().method() === 'POST' && /\/scene\/[^/]+\/render(\?|$)/.test(r.url()), { timeout: 120000 });
        const t0 = Date.now();
        await renderBtn.click();
        const rr = await renderResp;
        const req = rr.request();
        const body = await jsonOf(rr);
        console.log(TAG + ' POST ' + req.url().replace(/^https?:\/\/[^/]+/, '') + ' body=' + JSON.stringify(req.postData()) + ' → HTTP ' + rr.status() + ' ' + JSON.stringify(body).slice(0, 400));
        // THE wire contract under test: exactly { chatConfigName }, as JSON.
        expect(req.url(), 'the reader\'s Render posts ?force=true').toMatch(/\/scene\/[^/]+\/render\?force=true$/);
        expect(req.postDataJSON(), 'request body is exactly { chatConfigName }').toEqual({ chatConfigName: CONFIG_NAME });
        expect(String((await req.allHeaders())['content-type'] || ''), 'Content-Type is application/json').toMatch(/^application\/json/);
        expect(rr.status(), 'POST /scene/{id}/render with the picked config: ' + JSON.stringify(body).slice(0, 300)).toBe(202);
        expect(body.chatConfig && body.chatConfig.name, '202 body names the config the run was opened with').toBe(CONFIG_NAME);
        expect(body.chatConfig.objectId, '202 body carries the config objectId').toBeTruthy();
        expect(body.runObjectId && body.jobId, '202 body carries runObjectId + jobId').toBeTruthy();
        fx.run1 = { runObjectId: body.runObjectId, jobId: body.jobId, chatConfig: body.chatConfig };

        // The run banner chip, as the user sees it.
        const chip = page.getByTestId('pb2-run-chat-config');
        await expect(chip, 'the run banner names the config').toBeVisible({ timeout: 60000 });
        await expect(chip).toHaveText(CONFIG_NAME);
        await shot(page, '03c-reader-run-chip');

        // GET /run/{id}: the persisted run carries the same ref.
        const run = await pbGet(request, '/run/' + body.runObjectId);
        expect(run.status, 'GET /run/{id}: ' + JSON.stringify(run.body).slice(0, 300)).toBe(200);
        expect(run.body.chatConfig, 'run DTO chatConfig {objectId, name} equals the 202 ref').toEqual({ objectId: body.chatConfig.objectId, name: CONFIG_NAME });
        // And it is the owner's real record: ~/Chat/<name> as olio.llm.chatConfig.
        const cfgRow = await search(request, 'olio.llm.chatConfig', [{ name: 'objectId', comparator: 'equals', value: body.chatConfig.objectId }], ['id', 'objectId', 'name']);
        expect(cfgRow && cfgRow.name, 'the ref points at the real olio.llm.chatConfig row').toBe(CONFIG_NAME);

        // Let the render finish (ceiling only); the outcome is logged, a FAILED run is a real red.
        const progress = page.getByTestId('pb2-run-progress');
        await expect(progress).toBeVisible({ timeout: 60000 });
        await expect(progress).toHaveAttribute('data-run-terminal', '1', { timeout: T.render });
        const renderMs = Date.now() - t0;
        const counter = ((await page.getByTestId('pb2-run-counter').textContent().catch(() => '')) || '').trim();
        await shot(page, '03d-reader-run-terminal');
        const runAfter = await pbGet(request, '/run/' + body.runObjectId);
        console.log(TAG + ' run ' + body.runObjectId + ' terminal in ' + Math.round(renderMs / 1000) + ' s (observation) counter="' + counter + '" status=' + runAfter.body.runStatus + ' executed=' + runAfter.body.executedNodeCount + ' failed=' + runAfter.body.failedNodeCount + ' error=' + JSON.stringify(runAfter.body.error) + ' chatConfig=' + JSON.stringify(runAfter.body.chatConfig));
        expect(runAfter.body.chatConfig, 'the ref survives the run closing').toEqual({ objectId: body.chatConfig.objectId, name: CONFIG_NAME });
        expect(String(runAfter.body.runStatus).toUpperCase(), 'the run closed COMPLETED: ' + JSON.stringify(runAfter.body).slice(0, 400)).toBe('COMPLETED');
        expect(runAfter.body.failedNodeCount, 'no node failed').toBe(0);
        await page.getByTestId('pb2-run-dismiss').click().catch(() => {});
        console.log(TAG + ' control label before pick: "' + labelBefore + '"');
        await request.get(REST + '/logout');
    });

    test('4. REST: Render all with no body (the pre-226a wire contract) opens a run that names no chatConfig', async ({ request }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 required');
        test.setTimeout(T.renderAll + 10 * 60 * 1000);
        await restLoginShared(request);
        const t0 = Date.now();
        const ra = await pbPost(request, '/render-all');
        console.log(TAG + ' POST /render-all (no body) → HTTP ' + ra.status + ' ' + JSON.stringify(ra.body).slice(0, 400));
        expect(ra.status, 'POST /render-all with no body: ' + JSON.stringify(ra.body).slice(0, 300)).toBe(202);
        expect(ra.body.chatConfig, 'no body ⇒ the run names no chatConfig').toBeUndefined();
        expect(ra.body.sceneCount, 'Render all plans both scenes').toBe(fx.scenes.length);
        expect(ra.body.reusedNodeCount, 'scene 0 (rendered in test 3) is reused').toBeGreaterThanOrEqual(1);
        fx.run2 = { runObjectId: ra.body.runObjectId, jobId: ra.body.jobId };
        const run = await pbGet(request, '/run/' + ra.body.runObjectId);
        expect(run.status, 'GET /run/{id}').toBe(200);
        expect(run.body.chatConfig, 'run DTO carries no chatConfig').toBeUndefined();
        const job = await waitForJob(request, ra.body.jobId, T.renderAll);
        const runAfter = await pbGet(request, '/run/' + ra.body.runObjectId);
        console.log(TAG + ' render-all run ' + ra.body.runObjectId + ' terminal in ' + Math.round((Date.now() - t0) / 1000) + ' s (observation) job=' + JSON.stringify({ status: job.status, current: job.current, total: job.total }) + ' status=' + runAfter.body.runStatus + ' executed=' + runAfter.body.executedNodeCount + ' failed=' + runAfter.body.failedNodeCount + ' error=' + JSON.stringify(runAfter.body.error));
        expect(runAfter.body.chatConfig, 'still no chatConfig after the run closed').toBeUndefined();
        expect(String(runAfter.body.runStatus).toUpperCase(), 'the run closed COMPLETED: ' + JSON.stringify(runAfter.body).slice(0, 400)).toBe('COMPLETED');
        expect(runAfter.body.failedNodeCount, 'no node failed').toBe(0);
        // Test 3's run is untouched by test 4's.
        const run1 = await pbGet(request, '/run/' + fx.run1.runObjectId);
        expect(run1.body.chatConfig, 'the first run still names its config').toEqual(fx.run1.chatConfig);
        await request.get(REST + '/logout');
    });
});
