/**
 * PictureBook wizard scene-count guidance, in the real browser against the live Docker stack.
 *
 * Why: the wizard used to send no `count`, so alchemy.doc (57K chars, 8 chunks) got the server's
 * default of 10 spread over the chunks and came back with 14 scenes. The wizard now measures the
 * source through the live detect-boundaries endpoint and pre-fills a guess, and the user can change
 * the count before extracting.
 *
 * Covered LIVE here: upload of the real alchemy.doc as the shared non-admin user, the Step 1 guess
 * computed from the server's real measurement of that document, editing it, and the Extract click
 * sending the edited count in the extract-scenes-only body. The extract request itself is aborted
 * after its body is captured, so NO LLM run is started: the server's handling of `count`
 * (PictureBookService → extractChunkedInternal → chunkSceneBudget) is unchanged and is not
 * exercised by this spec. Step 2's change-count-and-re-extract needs a finished extraction and is
 * covered by src/test/pictureBookSceneCount.test.js (Vitest, stubbed fetch), not here.
 *
 * The source is Stephen's own alchemy.doc, read from outside the repo (not committed); the spec
 * skips when it is absent. Override with PB_SCENECOUNT_DOC.
 *
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookSceneCount.spec.js \
 *       --workers=1 --retries=0 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath } from './helpers/api.js';
import fs from 'fs';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const ORG_PATH = '/Development';
const DOC_PATH = process.env.PB_SCENECOUNT_DOC || 'C:/temp/AccuRev/AccuRev/tales/Short Stories/alchemy.doc';
// Same constants as pictureBook.js: one scene per 2,500 chars, clamped 3..60.
const expectedGuess = (chars) => Math.min(60, Math.max(3, Math.round(chars / 2500)));

function b64(str) { return Buffer.from(str).toString('base64'); }

async function restLogin(request, creds) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: ORG_PATH, name: creds.testUserName,
            credential: b64(creds.testPassword), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

async function installWsStub(page) {
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
    });
}

test.describe('PictureBook wizard — scene count guidance (live)', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });
    let creds = null;
    let docObjectId = null;
    const docName = 'alchemy-scenecount-' + Date.now().toString(36) + '.doc';

    test.beforeAll(async ({ request }) => {
        test.skip(!fs.existsSync(DOC_PATH), 'source document not present: ' + DOC_PATH);
        creds = await ensureSharedTestUser(request);
        await restLogin(request, creds);
        const dir = await ensurePath(request, 'auth.group', 'data', '~/Manuscripts');
        expect(dir && dir.id, 'could not ensure ~/Manuscripts').toBeTruthy();
        const resp = await request.post(REST + '/model', {
            data: {
                schema: 'data.data', name: docName, groupId: dir.id, groupPath: dir.path,
                contentType: 'application/msword',
                dataBytesStore: fs.readFileSync(DOC_PATH).toString('base64')
            }
        });
        expect(resp.ok(), 'upload failed: ' + resp.status()).toBe(true);
        docObjectId = (await resp.json()).objectId;
        expect(docObjectId).toBeTruthy();
    });

    test.afterAll(async ({ request }) => {
        if (!creds || !docObjectId) return;
        await restLogin(request, creds);
        const resp = await request.delete(REST + '/model/data.data/' + docObjectId);
        console.log('[scene-count] cleanup: DELETE data.data ' + docObjectId + ' -> ' + resp.status());
    });

    test('Step 1 guesses from the real source size; an edited count is what Extract sends', async ({ page }) => {
        // Ground truth from the live server, independent of the UI. Logging in on page.request also
        // gives the SPA its session cookie.
        await restLogin(page.request, creds);
        const live = await page.request.get(REST + '/olio/picture-book/chapter/detect-boundaries?sourceDataObjectId=' + docObjectId);
        expect(live.ok(), 'detect-boundaries -> ' + live.status()).toBe(true);
        const liveRanges = await live.json();
        const chars = Math.max(...liveRanges.map(r => r.endOffset));
        const guess = expectedGuess(chars);
        console.log('[scene-count] detect-boundaries: ' + liveRanges.length + ' range(s), ' + chars
            + ' chars -> expected guess ' + guess);

        const pageErrors = [];
        page.on('pageerror', e => pageErrors.push(e.message));
        await installWsStub(page);
        await page.goto('/', { timeout: 45000 });
        await page.waitForFunction(() => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
            null, { timeout: 45000 });

        await page.goto('/#!/picture-book/' + docObjectId, { timeout: 45000 });
        await page.locator('button:has-text("Generate Picture Book")').click({ timeout: 30000 });
        const dialog = page.locator('[role="dialog"]').first();
        await expect(dialog).toContainText('Source: ' + docName, { timeout: 10000 });

        const input = dialog.locator('input[data-pb-scene-count]');
        await expect(input).toHaveValue(String(guess), { timeout: 30000 });
        await expect(dialog).toContainText('Suggested ' + guess + ' for ~');
        console.log('[scene-count] Step 1 hint: "' + (await dialog.locator('input[data-pb-scene-count] + div').textContent()).trim() + '"');

        // Edit, then use "Use N" to restore, then edit again — the value Extract sends must be the last edit.
        await input.fill('31');
        await expect(dialog.locator('[data-pb-scene-count-suggested]')).toHaveText('Use ' + guess);
        await dialog.locator('[data-pb-scene-count-suggested]').click();
        await expect(input).toHaveValue(String(guess));
        await input.fill('31');

        // Capture the real extract request body, then abort it so no LLM job starts.
        let captured = null;
        await page.route('**/extract-scenes-only**', async (route) => {
            captured = { url: route.request().url(), body: route.request().postDataJSON() };
            await route.abort();
        });
        await page.locator('button:has-text("Extract")').first().click();
        await expect.poll(() => captured, { timeout: 30000 }).not.toBeNull();
        console.log('[scene-count] extract request: ' + captured.url + ' body=' + JSON.stringify(captured.body));
        expect(captured.url).toContain('async=true');
        if (liveRanges.length >= 2) {
            // Chaptered: each chapter job carries its share, never the whole-book count.
            expect(captured.body.count).toBeGreaterThan(0);
            expect(captured.body.count).toBeLessThanOrEqual(31);
        } else {
            expect(captured.body.count).toBe(31);
        }
        expect(pageErrors, 'uncaught page errors: ' + pageErrors.join(' | ')).toEqual([]);
    });
});
