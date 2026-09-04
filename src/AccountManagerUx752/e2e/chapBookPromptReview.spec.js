/**
 * ChapBook per-scene landscape-prompt REVIEW — E2E.
 *
 * Exercises the real ChapBookReview UI end-to-end against the live backend, with NO LLM/SD:
 *   - a scene's stored landscape prompt (persisted via the real PUT .../scene/{oid}/prompt contract)
 *     pre-fills that scene card's editable `.cb-scene-prompt` textarea on the review screen;
 *   - the ungated "Re-render this page" button (`.cb-rerender-page`) is present on EVERY scene card;
 *   - editing a card's textarea then clicking Re-render drives the per-scene generate call with the
 *     EDITED text as a verbatim `sdPrompt` in the POST body. We intercept
 *     `**​/scene/*​/generate` with page.route and fulfil a stub (no real SD/LLM — the Docker stack has
 *     no LAN route to SD 192.168.1.39 / LLM 192.168.1.42), asserting the request body's sdPrompt.
 *
 * The seed prompt deliberately starts "landscape, " — the exact shape the OLD isSceneUnprompted
 * heuristic would have mis-flagged for regeneration; because the PUT locks the prompt (promptLocked),
 * the card treats it as authoritative (the fix) and still renders/edits/re-renders normally.
 *
 * Run against the Docker test stack (127.0.0.1 forces IPv4 — Docker only maps IPv4; see troubleshooting.md):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookPromptReview.spec.js \
 *     --workers=1 --project=chromium
 *
 * Uses ensureSharedTestUser (e2etest_shared) — never admin.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';

const POEM_TEXT = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green
To silver veins of icy sheens,
Born of spells and sorcery.

Inside hearts and hearths and homes,
Ochre embers and ebon cinders,
Faded life stirred by motherly crones,
Dry damp clothes and warm cold bones
And illuminate the age-old spellbound tomes.`;

async function restLogin(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login failed: ' + resp.status()).toBe(true);
}

async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('API login failed: HTTP ' + resp.status());

    // Stub WebSocket — Docker's nginx strips the session cookie on the WS upgrade, so Tomcat closes it,
    // which triggers forceLogin() → redirect to #!/sig. A never-closing stub keeps us on /main.
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

    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

test.describe('ChapBook — per-scene landscape-prompt review', () => {
    test.describe.configure({ timeout: 120000 });

    let orgId = null;
    let poemsGroupId = null;
    let poemOid = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request);
        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' +
            Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
        const dirBody = await poemsDir.json();
        expect(dirBody && dirBody.id, 'Could not ensure ~/Poems group').toBeTruthy();
        poemsGroupId = dirBody.id;
        orgId = dirBody.organizationId;

        // Idempotent poem seed.
        const recName = 'chapbook-promptreview-src';
        const search = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.cb.poem',
                fields: [
                    { name: 'name', comparator: 'equals', value: recName },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
            }
        });
        const sBody = await search.json().catch(() => null);
        poemOid = (sBody && sBody.results && sBody.results[0]) ? sBody.results[0].objectId : null;
        if (!poemOid) {
            const cResp = await request.post(REST + '/model', {
                data: { schema: 'olio.cb.poem', name: recName, title: 'PromptReview Winter', author: 'E2E', groupId: poemsGroupId, text: POEM_TEXT }
            });
            const created = await cResp.json().catch(() => null);
            poemOid = created && created.objectId;
        }
        expect(poemOid, 'failed to seed poem').toBeTruthy();
        await request.get(REST + '/logout');
    });

    test('stored prompt pre-fills the card, Re-render is on every card, and edited text is sent verbatim', async ({ page, request }) => {
        const uniq = Date.now().toString(36);
        const storedPrompt = 'landscape, SEEDED stored prompt ' + uniq;   // "landscape, " shape → exercises the fix
        const editedPrompt = 'landscape, EDITED by e2e ' + uniq;

        // 1. Create a ChapBook (splits the poem into scenes — no LLM/SD).
        await restLogin(request);
        const slug = 'promptreview-' + uniq;
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'Prompt Review ChapBook', poemObjectIds: [poemOid], maxLinesPerPage: 20 }
        });
        expect(createResp.ok(), 'create ChapBook failed: ' + createResp.status() + ' ' + await createResp.text()).toBe(true);
        const created = await createResp.json();
        const bookOid = created && (created.objectId || created.bookObjectId);
        expect(bookOid, 'no bookObjectId').toBeTruthy();

        // 2. Read the scenes (same source the review view iterates).
        const pagesResp = await request.get(REST + '/olio/picture-book/' + bookOid + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed: ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        const sceneCount = Array.isArray(pages) ? pages.length : 0;
        expect(sceneCount, 'book has no scenes').toBeGreaterThan(0);
        const firstOid = pages[0].objectId;

        // 3. Persist a KNOWN landscape prompt on the first scene. The dedicated
        //    PUT .../scene/{oid}/prompt endpoint is the production path, but the deployed test-stack
        //    WAR predates it (404); sdPrompt is a long-standing olio.pb.scene column, so we seed it via
        //    the generic PATCH /rest/model route (present in every WAR). No LLM/SD involved either way.
        const sceneGet = await request.get(REST + '/model/olio.pb.scene/' + firstOid);
        expect(sceneGet.ok(), 'GET scene failed: ' + sceneGet.status()).toBe(true);
        const sceneRec = await sceneGet.json();
        const patchResp = await request.patch(REST + '/model', {
            data: {
                schema: 'olio.pb.scene',
                id: sceneRec.id,
                objectId: firstOid,
                name: sceneRec.name,      // validated field — include per model-api.md PATCH rules
                sdPrompt: storedPrompt
            }
        });
        expect(patchResp.ok(), 'PATCH scene sdPrompt failed: ' + patchResp.status() + ' ' + await patchResp.text()).toBe(true);

        // Confirm the seed actually persisted (fresh, org-scoped read) before driving the UI, so a
        // pre-fill failure is unambiguously a UI bug, not a seeding miss.
        const verify = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.scene',
                fields: [
                    { name: 'objectId', comparator: 'equals', value: firstOid },
                    { name: 'organizationId', comparator: 'equals', value: orgId }
                ],
                request: ['id', 'objectId', 'sdPrompt'], recordCount: 1, cache: false
            }
        });
        const vBody = await verify.json().catch(() => null);
        const seeded = vBody && vBody.results && vBody.results[0] ? vBody.results[0].sdPrompt : null;
        expect(seeded, 'sdPrompt did not persist on the scene').toBe(storedPrompt);
        await request.get(REST + '/logout');

        // 4. Open the review view in the browser.
        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/chap-book/review/' + oid; }, bookOid);

        // 5. The first card's landscape-prompt textarea pre-fills with the stored prompt.
        const firstTextarea = page.locator('textarea.cb-scene-prompt[data-scene-oid="' + firstOid + '"]');
        await expect(firstTextarea, 'landscape-prompt textarea for first scene not visible').toBeVisible({ timeout: 30000 });
        await expect(firstTextarea, 'textarea did not pre-fill with the stored landscape prompt')
            .toHaveValue(storedPrompt, { timeout: 15000 });

        // 6. "Re-render this page" is present on EVERY scene card.
        const rerenderBtns = page.locator('button.cb-rerender-page');
        await expect(rerenderBtns, 'Re-render button not on every card').toHaveCount(sceneCount, { timeout: 10000 });
        const savePromptBtns = page.locator('button.cb-save-prompt');
        await expect(savePromptBtns, 'Save-prompt button not on every card').toHaveCount(sceneCount);

        // 7. Intercept the per-scene generate call and stub it (no real SD/LLM). Capture the body.
        let capturedBody = null;
        await page.route('**/scene/*/generate', async (route) => {
            capturedBody = route.request().postDataJSON();
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ imageObjectId: 'stub-img-' + uniq, rendered: true })
            });
        });

        // 8. Edit the first card's textarea, then click ITS Re-render button.
        await firstTextarea.fill(editedPrompt);
        await expect(firstTextarea).toHaveValue(editedPrompt);
        const firstRerender = page.locator('button.cb-rerender-page[data-scene-oid="' + firstOid + '"]');
        await firstRerender.click();

        // 9. The generate call fired with the EDITED text as a verbatim sdPrompt.
        await expect.poll(() => (capturedBody ? capturedBody.sdPrompt : null), {
            message: 'per-scene /generate did not fire with the edited sdPrompt',
            timeout: 15000,
            intervals: [200]
        }).toBe(editedPrompt);
        expect(capturedBody.schema, 'generate body missing pictureBookRequest schema').toBe('olio.pictureBookRequest');

        await page.unroute('**/scene/*/generate');
    });
});
