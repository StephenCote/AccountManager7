/**
 * PictureBook V3 Phase 8 (Visual QA) — P8-5 Ux spec, live on the Docker stack as e2etest_shared.
 *
 * The server judges rendered variants (design §17): each verdict is a `qa` JSON artifact on the image's node
 * (QaReport JSON, keyed by imageArtifactObjectId), and PbServiceFacade rides the NEWEST verdict per image on its
 * summary as `qa` in GET /workflow/node/{n} and GET /review-queue (never on the `qa` / `renderPlan` summaries).
 * The Ux shows that verdict as advisory ✓ / ⚠ / ✗ / ? badges — a badge never changes selection or the review
 * actions — on the canvas tiles (+ compare captions + tooltips), in the plan inspector ("Visual QA" section,
 * newest attempt of each image role) and on the review rows (badge button → inline report).
 *
 * Fixture (no SD / LLM): a fresh ChapBook, one PORTRAIT node, TEXT artifacts in two attempts carrying the P6-1
 * fields, plus `qa` JSON artifacts written through the generic model route in exactly the persisted shape:
 *   attempt A (older): r1 v0 seed 100 GENERATED          ← q1 WARN (OUTFIT)
 *   attempt B (newest): r2 v0 seed 200 IN_REVIEW          ← q2 WARN then q4 FAIL (the newest wins)
 *                       r3 v1 seed 201 APPROVED/AUTO ●   ← q3 PASS (with a note)
 *                       r4 v2 seed 202 GENERATED          ← never judged
 *   + one renderPlan JSON artifact so "View plan" / "Plan" open the inspector.
 *
 * What this spec proves:
 *   1. [REST] /workflow/node and /review-queue carry `qa` on r1 / r2 / r3 only, r2's is the newest (FAIL), and the
 *      `qa` / `renderPlan` summaries carry none.
 *   2. [canvas] tile badges + data-qa-result in strip order, the FAIL tooltip lists the findings, the compare
 *      captions carry the verdicts, "View plan" shows the Visual QA section with attempt B's two reports.
 *   3. [review] row badges newest first, the FAIL row keeps Use / Approve enabled, the badge toggles the inline
 *      report with its checks, "Plan" shows the same inspector section.
 *   4. [SD] (PB_SD_TESTS=1) a real STANDARD render of a STORY scene through the reader's Plan & generate: the
 *      server's own verdict (whichever provider the stack selects — VLM on the default chat route, else the
 *      Swarm detector on .39, else UNAVAILABLE) is read back from the `qa` artifact and shown on the canvas tile
 *      and the review row. The verdict is the server's; nothing here asserts PASS or FAIL.
 *
 * Run:
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 [PB_SD_TESTS=1] \
 *     npx playwright test e2e/pictureBookQa.spec.js --workers=1 --project=chromium --retries=0
 *
 * ensureSharedTestUser only (never admin). Timings are logged, never asserted. Screenshots: test-results/p85-qa/.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { randomUUID } from 'crypto';
import { mkdirSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const CB_REST = REST + '/olio/chap-book';
const OUT_DIR = 'test-results/p85-qa';
const ROLE = 'portrait0';

const POEM_A = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.`;
const POEM_B = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green.`;

const SETTING = 'a narrow cobbled harbour street at dusk, lanterns being lit along the quay';
const ACTION = 'a fisherman coils rope beside an upturned boat while gulls settle on the wall';
const MOOD = 'quiet, end-of-day';

// A plan the shared inspector renders (the same minimal shape the Vitest fixtures use).
const PLAN = {
    planVersion: 'illustration/1', purpose: 'PORTRAIT', canvas: { width: 832, height: 1216 },
    preset: { preset: 'STANDARD', steps: 25, variants: 3, upscale: false, qa: 'CANONICAL' },
    strategy: { name: 'TEXT_ONLY', stages: ['BASE'], reason: 'p8-5 fixture' }, models: {}, compiled: { BASE: { prompt: 'p8-5 fixture prompt' } },
    degradations: [], estimate: { scenes: 1, stages: 1, variants: 3, steps: 25, measured: false, source: 'NONE' }, contentHash: 'p85',
    config: { model: 'x.safetensors', sampler: 'euler', scheduler: 'karras', cfg: 7, seed: -1, steps: 25, width: 832, height: 1216 }
};

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }

let shared = null;
let bookObjectId = null;
let nodeObjectId = null;
let nodeHandle = null;
let attemptA = null;
let attemptB = null;
const art = {};   // r1..r4 → image artifact objectId; q1..q4 → qa artifact objectId; plan → renderPlan objectId
const live = { book: null, scene: null, inventoryUnavailable: null, inventoryError: null };

function sharedCredential() {
    if (!shared) throw new Error('ensureSharedTestUser() must run first (beforeAll)');
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
}

async function login(request) {
    const resp = await request.post(REST + '/login', { data: sharedCredential() });
    expect(resp.ok() || resp.status() === 204, 'login as ' + shared.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

async function loginAsSharedUser(page) {
    await login(page.request);
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

async function findOrCreatePoem(request, orgId, poemsGroupId, name, title, text) {
    const found = await search(request, 'olio.cb.poem',
        [{ name: 'name', comparator: 'equals', value: name }, { name: 'organizationId', comparator: 'equals', value: orgId }], ['id', 'objectId']);
    if (found) return found.objectId;
    const resp = await request.post(REST + '/model', { data: { schema: 'olio.cb.poem', name, title, author: 'QA Spec', groupId: poemsGroupId, text } });
    const created = await resp.json().catch(() => null);
    return created && created.objectId;
}

async function nodeView(request, bookOid, nodeOid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/workflow/node/' + nodeOid);
    const text = await resp.text();
    expect(resp.ok(), 'GET /workflow/node/' + nodeOid + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function reviewQueue(request, bookOid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/review-queue?filter=all');
    const text = await resp.text();
    expect(resp.ok(), 'GET /review-queue: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function getArtifact(request, bookOid, oid) {
    const resp = await request.get(PB_ROOT + '/' + bookOid + '/artifact/' + oid);
    const text = await resp.text();
    expect(resp.ok(), 'GET /artifact/' + oid + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/**
 * Open the workflow canvas, select the node card and wait for its artifact strip. The click lands on the card's
 * header (status badge), never its centre: on a wide card the centre is the actions row, and a click there hits
 * "▶ Test" / 🔄 (a real execute / mark-stale POST) instead of selecting the node.
 */
async function openNodeStrip(page, bookOid, nodeOid) {
    await page.goto('/#!/picture-book/' + bookOid + '/workflow', { timeout: 30000 });
    const card = page.locator('[data-node-id="' + nodeOid + '"]');
    await expect(card, 'node card on the canvas').toBeVisible({ timeout: 60000 });
    await card.click({ position: { x: 14, y: 12 } });
    await expect(page.getByTestId('pbwf-art-history'), 'artifact strip loaded').toBeVisible({ timeout: 30000 });
}

function tile(page, oid) { return page.locator('[data-testid="pbwf-art-tile"][data-artifact-id="' + oid + '"]'); }
function reviewRow(page, nodeOid, oid) {
    return page.locator('[data-testid="pb2-review-node"][data-node-id="' + nodeOid + '"] [data-testid="pb2-review-artifact"][data-artifact-id="' + oid + '"]');
}

test.describe('PictureBook V3 P8-5 — visual-QA verdicts on the canvas, the inspector and the review page', () => {
    // Serial: the SD leg renders on .39 and judges on whichever provider answers; the budget is deliberately
    // generous (small devices) and the elapsed time is logged, never asserted.
    test.describe.configure({ mode: 'serial', timeout: 1800000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await login(request);
        mkdirSync(OUT_DIR, { recursive: true });

        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' + b64('~/Poems').replace(/=/g, '%3D'));
        const poemsDirBody = await poemsDir.json().catch(() => null);
        const poemsGroupId = poemsDirBody && poemsDirBody.id;
        const orgId = poemsDirBody && poemsDirBody.organizationId;
        expect(poemsGroupId && orgId, '~/Poems group: ' + JSON.stringify(poemsDirBody)).toBeTruthy();
        const pa = await findOrCreatePoem(request, orgId, poemsGroupId, 'qa-spec-poem-alpha', 'Falling Leaves (QA)', POEM_A);
        const pb = await findOrCreatePoem(request, orgId, poemsGroupId, 'qa-spec-poem-beta', 'Winter (QA)', POEM_B);
        expect(pa && pb, 'poems seeded').toBeTruthy();

        const ts = Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', { data: { slug: 'qa-spec-' + ts, title: 'P8-5 QA Spec ChapBook', poemObjectIds: [pa, pb], maxLinesPerPage: 20 } });
        expect(createResp.ok(), 'ChapBook create: HTTP ' + createResp.status()).toBe(true);
        bookObjectId = (await createResp.json()).objectId;
        expect(bookObjectId, 'ChapBook objectId').toBeTruthy();

        const wf = await (await request.get(PB_ROOT + '/' + bookObjectId + '/workflow')).json();
        const wfRec = await search(request, 'olio.pb.workflow', [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }], ['id', 'objectId', 'groupId', 'groupPath']);
        expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

        nodeHandle = 'p85-' + ts;
        const nodeResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.node', name: 'Node ' + nodeHandle, groupId: wfRec.groupId, groupPath: wfRec.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId }, handle: nodeHandle, nodeType: 'portrait', nodeStatus: 'done', ordinal: 930 }
        });
        expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
        const node = await nodeResp.json();
        nodeObjectId = node && node.objectId;
        expect(nodeObjectId && node.id, 'node identity').toBeTruthy();

        const seed = async (key, fields) => {
            const resp = await request.post(REST + '/model', {
                data: Object.assign({ schema: 'olio.pb.artifact', name: 'Artifact ' + nodeHandle + ' ' + key, groupId: wfRec.groupId, groupPath: wfRec.groupPath,
                    producedByNode: { schema: 'olio.pb.node', id: node.id, objectId: node.objectId }, role: ROLE, artifactType: 'text', artifactText: 'p8-5 ' + key,
                    byteLength: 1024, selected: false, reviewStatus: 'generated' }, fields)
            });
            expect(resp.ok(), 'create artifact ' + key + ': HTTP ' + resp.status()).toBe(true);
            const rec = await resp.json();
            expect(rec && rec.objectId, 'artifact ' + key + ' objectId').toBeTruthy();
            art[key] = rec.objectId;
        };
        attemptA = randomUUID();
        attemptB = randomUUID();
        await seed('r1', { revision: 1, attempt: attemptA, variantIndex: 0, seed: 100 });
        await seed('r2', { revision: 2, attempt: attemptB, variantIndex: 0, seed: 200, reviewStatus: 'in_review' });
        await seed('r3', { revision: 3, attempt: attemptB, variantIndex: 1, seed: 201, selected: true, reviewStatus: 'approved', approvedBy: 'AUTO' });
        await seed('r4', { revision: 4, attempt: attemptB, variantIndex: 2, seed: 202 });

        // qa reports in the persisted QaReport shape, one JSON artifact each, attempt + variantIndex mirroring the image.
        const report = (imageKey, result, checks, extra) => JSON.stringify(Object.assign({ result, provider: 'p85-fixture', model: 'p85-model', durationMs: 1234,
            imageArtifactObjectId: art[imageKey], checks }, extra || {}));
        await seed('q1', { role: 'qa', artifactType: 'json', revision: 1, attempt: attemptA, variantIndex: 0,
            artifactText: report('r1', 'WARN', [{ kind: 'OUTFIT', result: 'WARN', expected: 'red cloak', observed: 'brown coat', detail: 'colour off', actorId: 'a1' }]) });
        await seed('q2', { role: 'qa', artifactType: 'json', revision: 2, attempt: attemptB, variantIndex: 0,
            artifactText: report('r2', 'WARN', [{ kind: 'COMPOSITION', result: 'WARN', expected: 'two-shot', observed: 'close-up', detail: 'first look' }]) });
        await seed('q3', { role: 'qa', artifactType: 'json', revision: 3, attempt: attemptB, variantIndex: 1,
            artifactText: report('r3', 'PASS', [
                { kind: 'PERSON_COUNT', result: 'PASS', expected: '2', observed: '2', detail: 'two figures' },
                { kind: 'ACTOR_PRESENT', result: 'PASS', expected: 'Tobin', observed: 'present', detail: 'right', actorId: 'a2' }], { note: 're-judged' }) });
        await seed('q4', { role: 'qa', artifactType: 'json', revision: 4, attempt: attemptB, variantIndex: 0,
            artifactText: report('r2', 'FAIL', [
                { kind: 'PERSON_COUNT', result: 'FAIL', expected: '2', observed: '1', detail: 'one figure' },
                { kind: 'ACTOR_PRESENT', result: 'FAIL', expected: 'Tobin', observed: 'absent', detail: 'no second figure', actorId: 'a2' }]) });
        await seed('plan', { role: 'renderPlan', artifactType: 'json', revision: 1, selected: true, artifactText: JSON.stringify(PLAN) });

        console.log('[p85-seed] ' + JSON.stringify({ book: bookObjectId, node: nodeObjectId, handle: nodeHandle, attemptA, attemptB, art, SD_ENABLED }));
        await request.get(REST + '/logout');
    });

    test.afterAll(async () => {
        console.log('[p85-cleanup] left in place as the record of the run: ' + JSON.stringify({ book: bookObjectId, node: nodeObjectId, art, live }));
    });

    test('1. [REST] /workflow/node and /review-queue ride the newest verdict on each judged image only', async ({ request }) => {
        await login(request);
        const nv = await nodeView(request, bookObjectId, nodeObjectId);
        const chain = nv.artifacts && nv.artifacts[ROLE];
        expect(Array.isArray(chain) && chain.length, 'portrait0 chain: ' + JSON.stringify(nv.artifacts && Object.keys(nv.artifacts))).toBe(4);
        const byId = Object.fromEntries(chain.map(a => [a.objectId, a]));
        expect(byId[art.r1].qa && byId[art.r1].qa.result).toBe('WARN');
        expect(byId[art.r1].qa.checks.map(c => c.kind)).toEqual(['OUTFIT']);
        // r2 was judged twice: the newest report (q4, FAIL) is the one that rides.
        expect(byId[art.r2].qa && byId[art.r2].qa.result).toBe('FAIL');
        expect(byId[art.r2].qa.imageArtifactObjectId).toBe(art.r2);
        expect(byId[art.r2].qa.provider).toBe('p85-fixture');
        expect(byId[art.r2].qa.model).toBe('p85-model');
        expect(byId[art.r2].qa.durationMs).toBe(1234);
        expect(byId[art.r2].qa.checks.map(c => [c.kind, c.result, c.expected, c.observed, c.actorId || null]))
            .toEqual([['PERSON_COUNT', 'FAIL', '2', '1', null], ['ACTOR_PRESENT', 'FAIL', 'Tobin', 'absent', 'a2']]);
        expect(byId[art.r3].qa && byId[art.r3].qa.result).toBe('PASS');
        expect(byId[art.r3].qa.note).toBe('re-judged');
        expect(byId[art.r4].qa, 'r4 was never judged').toBeUndefined();
        for (const role of ['qa', 'renderPlan']) {
            const bookkeeping = nv.artifacts[role];
            expect(Array.isArray(bookkeeping) && bookkeeping.length, role + ' chain present').toBeGreaterThan(0);
            for (const a of bookkeeping) expect(a.qa, role + ' summary ' + a.objectId + ' never carries qa').toBeUndefined();
        }

        const rq = await reviewQueue(request, bookObjectId);
        const node = (rq.nodes || []).find(n => n.nodeObjectId === nodeObjectId);
        expect(node, 'fixture node in the queue: ' + JSON.stringify((rq.nodes || []).map(n => n.nodeObjectId))).toBeTruthy();
        const roles = new Set(node.artifacts.map(a => a.role));
        expect([...roles], 'the queue lists images only').toEqual([ROLE]);
        const rqById = Object.fromEntries(node.artifacts.map(a => [a.objectId, a]));
        expect(Object.keys(rqById).sort()).toEqual([art.r1, art.r2, art.r3, art.r4].sort());
        expect(rqById[art.r1].qa.result).toBe('WARN');
        expect(rqById[art.r2].qa.result).toBe('FAIL');
        expect(rqById[art.r2].qa.checks.length).toBe(2);
        expect(rqById[art.r3].qa.result).toBe('PASS');
        expect(rqById[art.r4].qa).toBeUndefined();
        expect(upper(rqById[art.r2].reviewStatus)).toBe('IN_REVIEW');
        console.log('[p85-1] node view + review queue verdicts: ' + JSON.stringify({ r1: byId[art.r1].qa.result, r2: byId[art.r2].qa.result, r3: byId[art.r3].qa.result, r4: byId[art.r4].qa }));
        await request.get(REST + '/logout');
    });

    test('2. [canvas] tile badges, the FAIL tooltip, compare captions and the inspector\'s Visual QA section', async ({ page }) => {
        await loginAsSharedUser(page);
        await openNodeStrip(page, bookObjectId, nodeObjectId);

        // The portrait0 strip (the qa / renderPlan roles have strips of their own): newest attempt first (B: v0, v1, v2), then A.
        const tiles = page.locator('[data-testid="pbwf-art-role"][data-role="' + ROLE + '"] [data-testid="pbwf-art-tile"]');
        await expect(tiles).toHaveCount(4);
        for (const role of ['qa', 'renderPlan']) {
            const bookkeeping = page.locator('[data-testid="pbwf-art-role"][data-role="' + role + '"] [data-testid="pbwf-art-tile"]');
            expect(await bookkeeping.count(), role + ' strip present').toBeGreaterThan(0);
            await expect(bookkeeping.locator('[data-testid="pbwf-art-qa-badge"]'), role + ' tiles never carry a badge').toHaveCount(0);
        }
        const ids = await tiles.evaluateAll(els => els.map(e => e.getAttribute('data-artifact-id')));
        expect(ids).toEqual([art.r2, art.r3, art.r4, art.r1]);
        const results = await tiles.evaluateAll(els => els.map(e => e.getAttribute('data-qa-result')));
        expect(results).toEqual(['FAIL', 'PASS', null, 'WARN']);
        const glyphs = await tiles.evaluateAll(els => els.map(e => { const b = e.querySelector('[data-testid="pbwf-art-qa-badge"]'); return b ? b.textContent : null; }));
        expect(glyphs).toEqual(['✗', '✓', null, '⚠']);
        await expect(tile(page, art.r2).getByTestId('pbwf-art-qa-badge')).toHaveAttribute('title', 'QA fail');
        await expect(tile(page, art.r3).getByTestId('pbwf-art-qa-badge')).toHaveAttribute('title', 'QA pass');
        await expect(tile(page, art.r1).getByTestId('pbwf-art-qa-badge')).toHaveAttribute('title', 'QA warn');
        await expect(tile(page, art.r4).getByTestId('pbwf-art-qa-badge')).toHaveCount(0);
        const failTitle = await tile(page, art.r2).getAttribute('title');
        expect(failTitle, 'FAIL tooltip').toContain('qa fail (p85-fixture)');
        expect(failTitle).toContain('PERSON_COUNT fail: expected 2, observed 1 — one figure');
        expect(failTitle).toContain('ACTOR_PRESENT a2 fail: expected Tobin, observed absent — no second figure');
        const passTitle = await tile(page, art.r3).getAttribute('title');
        expect(passTitle).toContain('qa pass (p85-fixture)');
        expect(passTitle, 'a PASS report with a note lists no findings').not.toContain('re-judged');
        expect(await tile(page, art.r4).getAttribute('title')).not.toContain('qa ');

        // Two-up compare carries the verdicts in its captions.
        await tile(page, art.r2).click();
        await tile(page, art.r3).click();
        const compare = page.getByTestId('pbwf-art-compare');
        await expect(compare).toBeVisible();
        const cq = compare.locator('[data-testid="pbwf-art-compare-qa"]');
        await expect(cq).toHaveCount(2);
        expect(await cq.evaluateAll(els => els.map(e => [e.getAttribute('data-qa-result'), e.textContent]))).toEqual([['FAIL', ' · qa fail'], ['PASS', ' · qa pass']]);
        await page.screenshot({ path: OUT_DIR + '/02-canvas-tiles.png', fullPage: true });

        // View plan → the inspector's Visual QA section: attempt B's judged images (r2, r3), never r1 (older attempt).
        const toggle = page.getByTestId('pbwf-node-plan-toggle');
        await expect(toggle).toHaveText('View plan');
        await toggle.click();
        await expect(toggle).toHaveText('Hide plan', { timeout: 30000 });
        const holder = page.getByTestId('pbwf-node-plan');
        await expect(holder.getByTestId('pb2-plan-inspector')).toBeVisible();
        await expect(page.getByTestId('pbwf-node-plan-error')).toHaveCount(0);
        const qaSection = holder.getByTestId('pb2-plan-qa');
        await expect(qaSection).toHaveAttribute('data-count', '2');
        const reports = qaSection.locator('[data-testid="pb2-plan-qa-report"]');
        expect(await reports.evaluateAll(els => els.map(e => [e.getAttribute('data-artifact-id'), e.getAttribute('data-result'), e.getAttribute('data-role')])))
            .toEqual([[art.r2, 'FAIL', ROLE], [art.r3, 'PASS', ROLE]]);
        const failReport = qaSection.locator('[data-testid="pb2-plan-qa-report"][data-artifact-id="' + art.r2 + '"]');
        await expect(failReport).toContainText('portrait0 v0');
        await expect(failReport).toContainText('✗ FAIL');
        await expect(failReport).toContainText('p85-fixture / p85-model');
        await expect(failReport).toContainText('1.2 s');
        await expect(failReport.locator('[data-testid="pb2-plan-qa-check"]')).toHaveCount(2);
        await expect(failReport.locator('[data-testid="pb2-plan-qa-check"][data-kind="ACTOR_PRESENT"]')).toContainText('expected Tobin → observed absent — no second figure');
        const passReport = qaSection.locator('[data-testid="pb2-plan-qa-report"][data-artifact-id="' + art.r3 + '"]');
        await expect(passReport).toContainText('re-judged');
        await page.screenshot({ path: OUT_DIR + '/02-canvas-inspector.png', fullPage: true });
    });

    test('3. [review] row badges newest first, advisory only, the badge toggles the inline report, Plan shows the section', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book/' + bookObjectId + '/review', { timeout: 30000 });
        const node = page.locator('[data-testid="pb2-review-node"][data-node-id="' + nodeObjectId + '"]');
        await expect(node).toBeVisible({ timeout: 60000 });
        const rows = node.locator('[data-testid="pb2-review-artifact"]');
        await expect(rows).toHaveCount(4);
        expect(await rows.evaluateAll(els => els.map(e => e.getAttribute('data-artifact-id')))).toEqual([art.r4, art.r3, art.r2, art.r1]);
        expect(await rows.evaluateAll(els => els.map(e => e.getAttribute('data-qa-result')))).toEqual([null, 'PASS', 'FAIL', 'WARN']);
        expect(await rows.evaluateAll(els => els.map(e => { const b = e.querySelector('[data-testid="pb2-review-qa"]'); return b ? b.textContent : null; })))
            .toEqual([null, '✓ PASS', '✗ FAIL', '⚠ WARN']);

        // Advisory: the FAIL row (IN_REVIEW, not selected) keeps Use and Approve enabled.
        const failRow = reviewRow(page, nodeObjectId, art.r2);
        await expect(failRow).toHaveAttribute('data-review-status', 'IN_REVIEW');
        await expect(failRow.getByTestId('pb2-review-use')).toBeEnabled();
        await expect(failRow.getByTestId('pb2-review-approve')).toBeEnabled();
        const badge = failRow.getByTestId('pb2-review-qa');
        await expect(badge).toHaveAttribute('data-open', '0');
        expect(await badge.getAttribute('title')).toContain('QA fail (p85-fixture)');
        expect(await badge.getAttribute('title')).toContain('PERSON_COUNT fail: expected 2, observed 1 — one figure');
        await expect(failRow.getByTestId('pb2-review-qa-report')).toHaveCount(0);
        await badge.click();
        await expect(badge).toHaveAttribute('data-open', '1');
        const report = failRow.getByTestId('pb2-review-qa-report');
        await expect(report).toBeVisible();
        await expect(report).toHaveAttribute('data-result', 'FAIL');
        await expect(report).toContainText('p85-fixture / p85-model · 1.2 s');
        const checks = report.locator('[data-testid="pb2-review-qa-check"]');
        await expect(checks).toHaveCount(2);
        expect(await checks.evaluateAll(els => els.map(e => [e.getAttribute('data-kind'), e.getAttribute('data-result')]))).toEqual([['PERSON_COUNT', 'FAIL'], ['ACTOR_PRESENT', 'FAIL']]);
        await expect(checks.nth(1)).toContainText('Tobin');
        // Only the clicked row opened; the selection is untouched.
        await expect(node.locator('[data-testid="pb2-review-qa-report"]')).toHaveCount(1);
        await expect(reviewRow(page, nodeObjectId, art.r3)).toHaveAttribute('data-selected', '1');
        await expect(failRow).toHaveAttribute('data-selected', '0');
        await page.screenshot({ path: OUT_DIR + '/03-review-report.png', fullPage: true });
        await badge.click();
        await expect(badge).toHaveAttribute('data-open', '0');
        await expect(failRow.getByTestId('pb2-review-qa-report')).toHaveCount(0);

        const planToggle = node.getByTestId('pb2-review-plan-toggle');
        await expect(planToggle).toHaveText('Plan');
        await planToggle.click();
        await expect(planToggle).toHaveText('Hide plan', { timeout: 30000 });
        const plan = node.getByTestId('pb2-review-plan');
        await expect(plan.getByTestId('pb2-plan-inspector')).toBeVisible();
        await expect(node.getByTestId('pb2-review-plan-error')).toHaveCount(0);
        await expect(plan.getByTestId('pb2-plan-qa')).toHaveAttribute('data-count', '2');
        expect(await plan.locator('[data-testid="pb2-plan-qa-report"]').evaluateAll(els => els.map(e => [e.getAttribute('data-artifact-id'), e.getAttribute('data-result')])))
            .toEqual([[art.r2, 'FAIL'], [art.r3, 'PASS']]);
        await page.screenshot({ path: OUT_DIR + '/03-review-plan.png', fullPage: true });
    });

    test('4. [SD] a real STANDARD render is judged by the stack\'s provider and the verdict shows on the canvas and the review row', async ({ page, request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD render');
        await login(request);
        const ts = Date.now().toString(36);
        const slug = 'p85-qa-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'P8-5 QA ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        const storyOid = chapter.bookObjectId || chapter.objectId;
        expect(storyOid, 'STORY book objectId').toBeTruthy();
        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: storyOid }], ['id', 'objectId', 'groupId', 'groupPath']);
        expect(book && book.id && book.groupId, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        const sceneResp = await request.post(REST + '/model', {
            data: { schema: 'olio.pb.scene', name: 'Scene ' + slug + ' 0', groupId: book.groupId, groupPath: book.groupPath,
                book: { schema: 'olio.pb.book', id: book.id, objectId: book.objectId }, sceneIndex: 0, title: 'Lanterns on the quay', setting: SETTING, action: ACTION, mood: MOOD }
        });
        const sceneText = await sceneResp.text();
        expect(sceneResp.ok(), 'create olio.pb.scene: HTTP ' + sceneResp.status() + ' ' + sceneText.slice(0, 300)).toBe(true);
        const scene = JSON.parse(sceneText);
        live.book = storyOid; live.scene = scene.objectId;
        // STANDARD = QA CANONICAL (variant 0 judged); the default DRAFT style would turn QA off.
        const styleResp = await request.put(PB_ROOT + '/' + storyOid + '/style', { data: { qualityPreset: 'STANDARD' } });
        const styleText = await styleResp.text();
        expect(styleResp.ok(), 'PUT /style: HTTP ' + styleResp.status() + ' ' + styleText.slice(0, 300)).toBe(true);
        const style = await (await request.get(PB_ROOT + '/' + storyOid + '/style')).json();
        expect(upper(style.effective && style.effective.qualityPreset), 'effective style: ' + JSON.stringify(style.effective)).toBe('STANDARD');
        const inv = await (await request.get(REST + '/olio/illustration/models?task=sceneDirect', { timeout: 300000 })).json();
        live.inventoryUnavailable = !!(inv.inventory && inv.inventory.error);
        live.inventoryError = inv.inventory ? inv.inventory.error : null;
        console.log('[p85-4-seed] ' + JSON.stringify({ book: storyOid, scene: scene.objectId, inventory: inv.inventory, resolution: inv.resolution }));
        await request.get(REST + '/logout');
        test.skip(live.inventoryUnavailable, 'the renderer inventory is unavailable (' + JSON.stringify(live.inventoryError) + ') — nothing can render');

        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book/v2/' + storyOid, { timeout: 30000 });
        await page.locator('button', { hasText: 'Begin' }).click();
        await expect(page.getByText(/^Page 1 of \d+$/).first()).toBeVisible({ timeout: 30000 });
        await page.getByTestId('pb2-plan-render').click();
        await expect(page.getByTestId('pb2-plan-dialog')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb2-plan-loading')).toHaveCount(0, { timeout: 900000 });
        await expect(page.getByTestId('pb2-plan-dialog-strategy').locator('xpath=following-sibling::span[1]')).toHaveText('STANDARD');
        const t0 = Date.now();
        await page.getByTestId('pb2-plan-generate').click();
        const toast = page.locator('.toast-text', { hasText: /^Scene rendered \(artifact [0-9a-f-]{36}\)$/ });
        await expect(toast).toBeVisible({ timeout: 1500000 });
        console.log('[p85-4] rendered + judged in ' + (Date.now() - t0) + ' ms: ' + (await toast.textContent()));
        await expect(page.getByTestId('pb2-plan-dialog')).toHaveCount(0);

        // The server's verdict, read back through the facade and the qa artifact itself.
        await login(request);
        const wf = await (await request.get(PB_ROOT + '/' + storyOid + '/workflow')).json();
        const planNode = (wf.nodes || []).find(n => upper(n.nodeType) === 'RENDER_PLAN' && n.scopeRef === scene.objectId);
        expect(planNode, 'RENDER_PLAN node for the scene: ' + JSON.stringify(wf.nodes)).toBeTruthy();
        const nv = await nodeView(request, storyOid, planNode.objectId);
        const roles = Object.keys(nv.artifacts || {});
        expect(roles, 'the node carries a qa chain: ' + JSON.stringify(roles)).toContain('qa');
        const qaChain = nv.artifacts.qa;
        const judged = [];
        for (const role of roles) {
            if (role === 'qa' || role === 'renderPlan') continue;
            for (const a of nv.artifacts[role]) if (a.qa) judged.push(Object.assign({ role }, a));
        }
        expect(judged.length, 'CANONICAL judges exactly one image (variant 0): ' + JSON.stringify(judged.map(j => [j.role, j.objectId, j.variantIndex]))).toBe(1);
        const img = judged[0];
        expect(img.variantIndex == null ? 0 : img.variantIndex).toBe(0);
        expect(img.qa.imageArtifactObjectId).toBe(img.objectId);
        const stored = JSON.parse((await getArtifact(request, storyOid, qaChain[qaChain.length - 1].objectId)).artifactText);
        expect(stored.imageArtifactObjectId).toBe(img.objectId);
        expect(stored.result).toBe(img.qa.result);
        const unjudged = nv.artifacts[img.role].filter(a => a.attempt === img.attempt && a.objectId !== img.objectId);
        for (const a of unjudged) expect(a.qa, 'variant ' + a.variantIndex + ' is not judged under CANONICAL').toBeUndefined();
        const flagged = img.qa.result === 'FAIL' || img.qa.result === 'WARN';
        console.log('[p85-4] verdict ' + img.qa.result + ' by ' + img.qa.provider + (img.qa.model ? '/' + img.qa.model : '') + ' in ' + img.qa.durationMs + ' ms'
            + (img.qa.note ? ' — ' + img.qa.note : '') + '; checks=' + JSON.stringify((img.qa.checks || []).map(c => [c.kind, c.result, c.expected, c.observed]))
            + '; image ' + img.role + ' ' + img.objectId + ' reviewStatus=' + img.reviewStatus + ' (flagged=' + flagged + '); variants in attempt=' + (unjudged.length + 1));
        await request.get(REST + '/logout');

        // Canvas: the judged tile carries the badge with the server's result; its siblings carry none.
        await openNodeStrip(page, storyOid, planNode.objectId);
        const judgedTile = tile(page, img.objectId);
        await expect(judgedTile).toHaveAttribute('data-qa-result', img.qa.result);
        await expect(judgedTile.getByTestId('pbwf-art-qa-badge')).toHaveAttribute('data-qa-result', img.qa.result);
        expect(await judgedTile.getAttribute('title')).toContain('qa ' + img.qa.result.toLowerCase() + ' (' + img.qa.provider + ')');
        for (const a of unjudged) await expect(tile(page, a.objectId).getByTestId('pbwf-art-qa-badge')).toHaveCount(0);
        await page.getByTestId('pbwf-node-plan-toggle').click();
        const section = page.getByTestId('pbwf-node-plan').getByTestId('pb2-plan-qa');
        await expect(section).toHaveAttribute('data-count', '1', { timeout: 30000 });
        await expect(section.locator('[data-testid="pb2-plan-qa-report"]')).toHaveAttribute('data-artifact-id', img.objectId);
        await expect(section.locator('[data-testid="pb2-plan-qa-report"]')).toHaveAttribute('data-result', img.qa.result);
        await page.screenshot({ path: OUT_DIR + '/04-live-canvas.png', fullPage: true });

        // Review page: the row carries the same badge; a FAIL / WARN image sits IN_REVIEW unless auto-approve moved it on.
        await page.goto('/#!/picture-book/' + storyOid + '/review', { timeout: 30000 });
        const row = reviewRow(page, planNode.objectId, img.objectId);
        await expect(row).toBeVisible({ timeout: 60000 });
        await expect(row).toHaveAttribute('data-qa-result', img.qa.result);
        await expect(row.getByTestId('pb2-review-qa')).toHaveText(new RegExp(' ' + img.qa.result + '$'));
        await expect(row).toHaveAttribute('data-review-status', upper(img.reviewStatus));
        await row.getByTestId('pb2-review-qa').click();
        const liveReport = row.getByTestId('pb2-review-qa-report');
        await expect(liveReport).toHaveAttribute('data-result', img.qa.result);
        await expect(liveReport.locator('[data-testid="pb2-review-qa-check"]')).toHaveCount((img.qa.checks || []).length);
        await page.screenshot({ path: OUT_DIR + '/04-live-review.png', fullPage: true });
    });
});
