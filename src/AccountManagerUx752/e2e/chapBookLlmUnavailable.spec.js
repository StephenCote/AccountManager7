/**
 * ChapBook — LLM-unavailable signal E2E (client half of the backend llmUnavailable/llmDegraded fix).
 *
 * The Docker stack (am7test) cannot route to the LAN LLM at 192.168.1.42, so the backend's LLM
 * landscape-prompt STEP hard-fails inside the container. The deployed backend, for a freshly created
 * ChapBook scene (which carries a no-LLM fallback prompt), then renders on that STORED prompt and
 * returns the DEGRADED shape: { rendered:true, skipped:false, llmUnavailable:true, llmDegraded:true }.
 * (The task assumed the SKIP shape { rendered:false, skipped:true, llmUnavailable:true } — but the
 * deployed backend degrades instead of skipping because a fallback prompt exists. The skip-branch
 * client messaging is proven by chapBookRender.test.js SHAPE 2, since it is not naturally reachable
 * against this backend.)
 *
 * These tests prove the CLIENT now surfaces a DISTINCT, user-visible signal for that hard LLM/config
 * fault — clearly different from a benign "run Analyze" skip and from plain success:
 *   - per-scene (Review → Regenerate): a distinct WARNING toast ("Rendered using the stored prompt …")
 *   - bulk (Reader → Render):          a distinct ERROR summary toast ("… scene(s) affected by an
 *                                       unavailable LLM/chat config")
 *
 * Run against the Docker stack (never admin — shared test user only):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookLlmUnavailable.spec.js \
 *     --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');

// Exact wording the client raises for each distinct signal (kept in sync with chapBook.js constants).
const DEGRADED_MSG = 'Rendered using the stored prompt — the LLM prompt step was unavailable (no usable chat config or the LLM is unreachable).';
const BULK_ERROR_CLAUSE = 'scene(s) affected by an unavailable LLM/chat config';
// Benign wordings the distinct signals must NOT be confused with.
const BENIGN_REGEN_SKIP = 'Still no usable prompt — Analyze the poem or edit the stanza, then regenerate';
const BENIGN_REGEN_SUCCESS = 'Scene regenerated';

// A single-stanza poem → one page/scene, so each render is a single fast per-scene call.
const POEM = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.`;

async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

// Seed a poem (idempotent by name) and create a FRESH ChapBook from it; returns its bookObjectId.
async function createFreshBook(request, poemsGroupId, orgId, tag) {
    const poemName = 'cb-llmunavail-' + tag;
    const search = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.cb.poem',
            fields: [
                { name: 'name', comparator: 'equals', value: poemName },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
        }
    });
    const sBody = await search.json().catch(() => null);
    let poemOid = (sBody && sBody.results && sBody.results[0]) ? sBody.results[0].objectId : null;
    if (!poemOid) {
        const cResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.cb.poem', name: poemName, title: 'LLM-Unavailable Probe',
                author: 'Stephen W. Cote', groupId: poemsGroupId, text: POEM
            }
        });
        const created = await cResp.json().catch(() => null);
        poemOid = created && created.objectId;
    }
    expect(poemOid, 'failed to seed poem').toBeTruthy();

    const slug = 'cb-llmunavail-' + tag + '-' + Date.now().toString(36);
    const createResp = await request.post(CB_REST + '/create', {
        data: { slug, title: 'LLM-Unavailable ' + tag, poemObjectIds: [poemOid], maxLinesPerPage: 20 }
    });
    expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
    const created = await createResp.json();
    const bookOid = created && (created.bookObjectId || created.objectId);
    expect(bookOid, 'no bookObjectId in create response').toBeTruthy();
    return bookOid;
}

// Log in via REST + stub the WebSocket (Docker nginx strips the WS cookie) + install a toast-capturing
// MutationObserver BEFORE navigation so a 5s auto-dismissed toast can't be missed by a slow assert.
async function loginAsSharedUser(page) {
    await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {} close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;

        // Capture every toast body text as it appears. Toasts auto-dismiss (~5s), so scrape the DOM on
        // a short interval and record CUMULATIVELY. A setInterval poller (not a MutationObserver) is
        // used deliberately: it self-arms even if document.documentElement isn't ready at init time and
        // cannot be silently dropped, so a fast render's brief toast is never missed. Guard the array
        // and the timer so a re-run of this init script (fires on every navigation) never wipes history.
        window.__toasts = window.__toasts || [];
        if (!window.__toastPoll) {
            window.__toastPoll = setInterval(() => {
                document.querySelectorAll('.toast-text').forEach((el) => {
                    const t = (el.textContent || '').trim();
                    if (t && window.__toasts.indexOf(t) === -1) window.__toasts.push(t);
                });
            }, 150);
        }
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

const capturedToasts = (page) => page.evaluate(() => window.__toasts || []);

test.describe('ChapBook — LLM-unavailable client signal', () => {
    test.describe.configure({ timeout: 180000 });

    let orgId = null;
    let poemsGroupId = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLoginShared(request);
        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' + Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const poemsBody = await poemsDir.json();
        expect(poemsBody && poemsBody.id, 'could not ensure ~/Poems group').toBeTruthy();
        poemsGroupId = poemsBody.id;
        orgId = poemsBody.organizationId;
        await request.get(REST + '/logout');
    });

    // ── Layer-isolation gate: prove the DEPLOYED backend returns the new fields ──────────────────
    test('REST: per-scene generate returns llmUnavailable on the Docker backend (LLM unreachable)', async ({ request }) => {
        await restLoginShared(request);
        const bookOid = await createFreshBook(request, poemsGroupId, orgId, 'rest');
        const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed: ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        expect(Array.isArray(pages) && pages.length, 'book has no scenes').toBeTruthy();
        const sceneOid = pages[0].objectId;

        const gResp = await request.post(CB_REST + '/scene/' + sceneOid + '/generate', {
            data: { schema: 'olio.pictureBookRequest' }
        });
        expect(gResp.ok(), 'generate failed: ' + gResp.status()).toBe(true);
        const body = await gResp.json();
        console.log('[llmUnavail] REST generate response:', JSON.stringify(body));
        // The whole point of the fix: the field exists on the wire and is TRUE when the LLM step
        // could not run (here: LLM unreachable from Docker). A normal soft refusal would NOT set it.
        expect(body.llmUnavailable, 'backend did not report llmUnavailable — is the fix deployed?').toBe(true);
    });

    // ── Per-scene distinct signal: Review → Regenerate raises the DEGRADED warning toast ─────────
    test('UI per-scene: Review Regenerate raises the distinct degraded WARNING toast (not the benign skip/success)', async ({ page, request }) => {
        await restLoginShared(request);
        const bookOid = await createFreshBook(request, poemsGroupId, orgId, 'review');
        await request.get(REST + '/logout');

        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/review/' + oid; }, bookOid);

        // A fresh (un-prompted) scene card exposes the "Regenerate" affordance.
        const regenBtn = page.locator('button', { hasText: 'Regenerate' }).first();
        await expect(regenBtn, 'Regenerate button not visible on a fresh un-prompted scene').toBeVisible({ timeout: 30000 });
        await regenBtn.click();

        // The distinct degraded WARNING toast must appear — and NOT the benign skip / plain-success text.
        await expect.poll(async () => await capturedToasts(page), {
            message: 'degraded WARNING toast never appeared',
            timeout: 120000, intervals: [1000]
        }).toContain(DEGRADED_MSG);

        const toasts = await capturedToasts(page);
        console.log('[llmUnavail] per-scene toasts:', JSON.stringify(toasts));
        expect(toasts, 'must not show the benign "run Analyze" skip text').not.toContain(BENIGN_REGEN_SKIP);
        expect(toasts, 'must not show the plain-success text').not.toContain(BENIGN_REGEN_SUCCESS);

        fs.mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(OUT_DIR, 'llmUnavail-perscene-degraded-warning.png'), fullPage: true });
    });

    // ── Bulk distinct signal: Reader → Render raises the ERROR summary toast ─────────────────────
    test('UI bulk: Reader Render raises the distinct ERROR summary toast (affected by unavailable LLM/chat config)', async ({ page, request }) => {
        await restLoginShared(request);
        const bookOid = await createFreshBook(request, poemsGroupId, orgId, 'reader');
        // Confirm the reader will have at least one scene to render.
        const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        const pages = await pagesResp.json();
        expect(Array.isArray(pages) && pages.length, 'reader book has no scenes').toBeTruthy();
        await request.get(REST + '/logout');

        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/read/' + oid; }, bookOid);
        await expect(page.locator('text=/Page \\d+ of \\d+/').first(), 'reader did not load book pages')
            .toBeVisible({ timeout: 30000 });

        // Reader header Render (the only orange button) → the "Render Settings" dialog → its Render.
        const headerRender = page.locator('button.bg-orange-600').filter({ hasText: 'Render' }).first();
        await expect(headerRender, 'reader Render button not visible/enabled').toBeVisible({ timeout: 10000 });
        await headerRender.click();

        const renderDialog = page.locator('div.fixed.inset-0.z-50').filter({ hasText: 'Render Settings' });
        await expect(renderDialog, 'Render Settings dialog did not open').toBeVisible({ timeout: 10000 });
        await page.waitForTimeout(2000); // let the SD config load so a real sdConfig is sent
        const dialogRender = renderDialog.locator('button.bg-orange-600');
        await expect(dialogRender, 'dialog Render button not visible').toBeVisible({ timeout: 10000 });
        await dialogRender.click();

        // The bulk summary toast must carry the distinct LLM-unavailable clause; it must NOT be the
        // plain all-clear success wording.
        await expect.poll(async () => await capturedToasts(page), {
            message: 'bulk ERROR summary toast never appeared',
            timeout: 150000, intervals: [2000]
        }).toEqual(expect.arrayContaining([expect.stringContaining(BULK_ERROR_CLAUSE)]));

        const toasts = await capturedToasts(page);
        console.log('[llmUnavail] bulk toasts:', JSON.stringify(toasts));
        expect(
            toasts.some((t) => /^Render complete: \d+ scene\(s\) generated$/.test(t)),
            'must not show the plain all-clear success wording'
        ).toBe(false);

        fs.mkdirSync(OUT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(OUT_DIR, 'llmUnavail-bulk-error-summary.png'), fullPage: true });
    });
});
