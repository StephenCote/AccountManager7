/**
 * PictureBook V3 style presets + no-style warnings (2026-11-05) on the Docker stack.
 *
 * Why: the V3 plan path takes a book's whole look from the book style's description / photoreal / exclusions;
 * a fresh book has none, so every image picked its own style while the visible SD "Style" dropdowns did nothing.
 * What this proves on the LIVE stack (UI as e2etest_shared, REST only to seed and verify; no LLM; no render is
 * ever started - the render dialog is opened and cancelled):
 *   1. a fresh book's render dialog warns "no style description" and links to the Book Style page;
 *   2. the Book Style page warns too, and the "Anime" preset fills Description / Photoreal / Exclusions as unsaved
 *      edits (warning gone, dirty marks), Save sends them, and GET /{book}/style carries exactly what the editor showed;
 *   3. the render dialog no longer warns once the style has a description.
 *
 * Run:
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3StylePreset.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const ART_EXCLUSIONS = 'photograph, photorealistic, photo, realistic skin texture';

function b64(str) { return Buffer.from(str).toString('base64'); }

let shared = null;
let bookObjectId = null;

function sharedCredential() {
    return { schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' };
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

async function getStyle(request, oid) {
    const resp = await request.get(PB_ROOT + '/' + oid + '/style');
    const text = await resp.text();
    expect(resp.ok(), 'GET /style: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

async function openRenderDialog(page) {
    await page.goto('/#!/picture-book/v2/' + bookObjectId, { timeout: 30000 });
    await expect(page.getByTestId('pb2-render-all')).toBeVisible({ timeout: 60000 });
    await page.getByTestId('pb2-render-all').click();
    await expect(page.getByTestId('pb2-render-dialog')).toBeVisible({ timeout: 15000 });
}

test.describe('PictureBook V3 style presets', () => {
    test.describe.configure({ mode: 'serial', timeout: 300000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        const lr = await request.post(REST + '/login', { data: sharedCredential() });
        expect(lr.ok() || lr.status() === 204, 'login: HTTP ' + lr.status()).toBe(true);
        const slug = 'pbpreset-' + Date.now().toString(36);
        const resp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'Preset ' + slug }, timeout: 120000 });
        const text = await resp.text();
        expect(resp.ok(), 'POST /chapter: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(text);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded book').toBeTruthy();
        const fresh = await getStyle(request, bookObjectId);
        expect(fresh.own, 'a fresh book has no style of its own').toBeUndefined();
        console.log('[pb-preset-seed] ' + JSON.stringify({ book: bookObjectId, slug }));
        await request.get(REST + '/logout');
    });

    test('1. a fresh book: the render dialog warns there is no style description', async ({ page }) => {
        await loginAsSharedUser(page);
        await openRenderDialog(page);
        await expect(page.getByTestId('pb2-render-no-style')).toBeVisible({ timeout: 15000 });
        await page.getByTestId('pb2-render-no-style-link').click();
        await expect(page.getByTestId('pb2-style')).toBeVisible({ timeout: 30000 });
        expect(page.url()).toContain('/picture-book/' + bookObjectId + '/style');
    });

    test('2. the Anime preset fills description / photoreal / exclusions, and Save persists exactly that', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book/' + bookObjectId + '/style', { timeout: 30000 });
        await expect(page.getByTestId('pb2-style-chain')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb2-style-no-description')).toBeVisible();

        await page.getByTestId('pb2-style-photoreal').check();
        await page.getByTestId('pb2-style-preset').selectOption('anime');
        const desc = await page.getByTestId('pb2-style-description').inputValue();
        console.log('[pb-preset] description="' + desc + '"');
        expect(desc.startsWith('Anime illustration')).toBe(true);
        expect(desc).not.toMatch(/null|undefined|\(/);
        await expect(page.getByTestId('pb2-style-photoreal')).not.toBeChecked();
        await expect(page.getByTestId('pb2-style-exclusions')).toHaveValue(ART_EXCLUSIONS);
        await expect(page.getByTestId('pb2-style-no-description')).toHaveCount(0);
        await expect(page.getByTestId('pb2-style-dirty-description')).toBeVisible();

        /// Re-roll redraws asynchronously; the value is read back only after Save has settled, so the comparison is
        /// against what the editor actually shows (an immediate read races the redraw - measured 2026-11-05).
        await page.getByTestId('pb2-style-preset-reroll').click();
        await expect(page.getByTestId('pb2-style-description')).toHaveValue(/^Anime illustration/);

        await page.getByTestId('pb2-style-save').click();
        await expect(page.getByTestId('pb2-style-dirty-description')).toHaveCount(0, { timeout: 15000 });
        const rerolled = await page.getByTestId('pb2-style-description').inputValue();
        console.log('[pb-preset] shown after save="' + rerolled + '"');
        expect(rerolled.startsWith('Anime illustration')).toBe(true);
        const st = await getStyle(page.request, bookObjectId);
        console.log('[pb-preset] GET /style own.fields=' + JSON.stringify(st.own && st.own.fields));
        expect(st.own && st.own.fields.description).toBe(rerolled);
        expect(st.own.fields.exclusions).toBe(ART_EXCLUSIONS);
        /// photoreal is a BOOLEAN with DDL default false, so "false" may be reported as absent (PbStyleResolver).
        expect(!!st.own.fields.photoreal).toBe(false);
        expect(!!(st.effective && st.effective.fields && st.effective.fields.photoreal)).toBe(false);
        await page.reload();
        await expect(page.getByTestId('pb2-style-description')).toHaveValue(rerolled, { timeout: 30000 });
        await expect(page.getByTestId('pb2-style-no-description')).toHaveCount(0);
    });

    test('3. once the style has a description the render dialog no longer warns', async ({ page }) => {
        await loginAsSharedUser(page);
        await openRenderDialog(page);
        await page.waitForTimeout(1500);
        await expect(page.getByTestId('pb2-render-no-style')).toHaveCount(0);
        await page.getByTestId('pb2-render-dialog-cancel').click();
    });
});
