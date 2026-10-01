/**
 * PictureBook scene TEXT survives from the wizard's extracted scene to the finished book's reader.
 *
 * THE DEFECT THIS PROVES FIXED (reported 2026-10-01 on Ourselves.doc): a finished STORY book showed
 * "No text yet." on every page. The LLM's extract-scenes reply carries the scene text as `blurb`;
 * `PictureBookUtil.sceneNoteStore` overwrote it with `summary`-or-"" when persisting the scene note,
 * so every scene note in the Docker DB (8617 of them) had no blurb at all, the PB2 dual-write copied
 * that nothing onto `olio.pb.scene`, and `/pages` handed the reader empty strings. The gated render
 * test in pictureBook.spec.js never saw it because its hand-built scene supplies `summary`.
 *
 * The sceneList here is the WIZARD'S shape — `blurb`, no `summary` — exactly what the extractor emits.
 *
 * Run against the Docker UAT stack:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookSceneText.spec.js --workers=1 --project=chromium
 *
 * Two tiers:
 *  - DEFAULT (load-safe, no LLM/SD): /chapter -> create-from-scenes with blurb-only scenes -> the
 *    persisted scene notes carry the blurb. No characters are supplied, so no LLM call is made.
 *  - GATED (PB_SD_TESTS=1, --workers=1): render one scene (LLM prompt + SD) so the olio.pb.scene row
 *    exists, then /pages carries the blurb and the in-browser PB2 reader displays it.
 *
 * NEVER uses the admin user for assertions — ensureSharedTestUser()/ensureChatConfig() provision;
 * every assertion runs as e2etest_shared.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig, ensurePath } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');

// Verbatim excerpt of Stephen's "The Big Way Out" — real document content, not synthetic filler.
const BWO_TEXT = fs.readFileSync(path.resolve(SPEC_DIR, 'fixtures', 'bigWayOut.txt'), 'utf8');

// Scenes in the wizard's extracted shape: `blurb` carries the text, there is NO `summary`.
// The blurbs are sentences lifted verbatim from the source so the reader assertion is exact.
const SCENES = [
    {
        title: 'Get Out',
        blurb: 'Following those two words, Darby expected her dad to fly into blistering criticisms. He didn\'t.',
        setting: 'A foyer with belongings piled on the hardwood floor, the front door open to cool, foggy morning air',
        action: 'Darby\'s dad points at the pile and then at the door; she shoulders a handbag and takes a suitcase handle',
        mood: 'quiet, wounded',
        characters: []
    },
    {
        title: 'The Nexon',
        blurb: 'Darby pulled the suitcase to her little white Nexon. The hatchback opened when she approached, and she fed the suitcase to the loading arm which drew it into the cargo area.',
        setting: 'A driveway in morning fog beside a small white hatchback',
        action: 'The loading arm packs and secures the suitcases as Darby and her dad cross paths in silence',
        mood: 'resigned',
        characters: []
    }
];

async function apiLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user API login failed: ' + resp.status()).toBeTruthy();
}

/** Log in as the shared test user (page.request shares the browser cookie jar), then boot the SPA. */
async function loginAsSharedUser(page) {
    await apiLoginShared(page.request);
    // Stub WebSocket — Docker's nginx strips cookies on the WS upgrade so Tomcat closes the
    // connection, which triggers forceLogin() and redirects to #!/sig.
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

const RUN = Date.now().toString(36);
const RENDER_ENABLED = process.env.PB_SD_TESTS === '1';
let chatConfigName = null;
let pb2BookObjectId = null;
let metaScenes = [];

test.describe.serial('PictureBook — scene text persists from extraction to reader', () => {
    test.describe.configure({ timeout: 300000 });

    test.beforeAll(async ({ request }) => {
        fs.mkdirSync(OUT_DIR, { recursive: true });
        await ensureSharedTestUser(request);
        chatConfigName = await ensureChatConfig(request, null);
        expect(typeof chatConfigName, 'ensureChatConfig must yield a config name').toBe('string');
        await apiLoginShared(request);

        const dir = await ensurePath(request, 'auth.group', 'data', '~/Notes');
        expect(dir && dir.id, '~/Notes group must resolve').toBeTruthy();
        const noteResp = await request.post(REST + '/model', {
            data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name: 'BWO scene-text source ' + RUN, text: BWO_TEXT }
        });
        expect(noteResp.ok(), 'source note create failed: ' + noteResp.status()).toBe(true);
        const workObjectId = (await noteResp.json()).objectId;
        expect(workObjectId, 'source note objectId').toBeTruthy();

        const chapterResp = await request.post(PB + '/chapter', {
            data: { slug: 'bwo-scene-text-' + RUN, title: 'BWO Scene Text ' + RUN }, timeout: 120000
        });
        expect(chapterResp.ok(), 'POST /chapter failed: ' + chapterResp.status() + ' ' + await chapterResp.text()).toBe(true);
        const chapter = await chapterResp.json();
        pb2BookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(pb2BookObjectId, 'PB2 book objectId').toBeTruthy();

        const cfsResp = await request.post(PB + '/' + workObjectId + '/create-from-scenes', {
            data: {
                schema: 'olio.pictureBookRequest',
                sceneList: SCENES,
                chatConfig: chatConfigName,
                bookName: 'BWO Scene Text ' + RUN,
                pb2BookObjectId
            },
            timeout: 240000
        });
        expect(cfsResp.ok(), 'create-from-scenes failed: ' + cfsResp.status() + ' ' + await cfsResp.text()).toBe(true);
        const meta = await cfsResp.json();
        metaScenes = meta.scenes || [];
        console.log('[sceneText] book=' + pb2BookObjectId + ' persisted scenes=' + metaScenes.length);
    });

    test('persisted scene notes keep the LLM blurb when no summary was supplied', async ({ request }) => {
        await apiLoginShared(request);
        expect(metaScenes.length, 'create-from-scenes must persist every supplied scene').toBe(SCENES.length);

        for (let i = 0; i < SCENES.length; i++) {
            const sceneOid = metaScenes[i].objectId;
            expect(sceneOid, 'scene ' + i + ' has no objectId: ' + JSON.stringify(metaScenes[i])).toBeTruthy();
            const noteResp = await request.get(REST + '/model/data.note/' + sceneOid + '/full');
            expect(noteResp.ok(), 'scene note fetch failed: ' + noteResp.status()).toBe(true);
            const note = await noteResp.json();
            const stored = JSON.parse(note.text || '{}');
            console.log('[sceneText] note ' + i + ' keys=' + Object.keys(stored).join(',') + ' blurb=' + JSON.stringify(stored.blurb));
            expect(stored.blurb,
                'scene ' + i + ' blurb was lost on persistence (sceneNoteStore clobbered it): ' + note.text)
                .toBe(SCENES[i].blurb);
        }
    });

    // GATED (PB_SD_TESTS=1, --workers=1): render one scene so the olio.pb.scene row exists, then the
    // reader must show the text. Lives in the same serial group so a retry in a fresh worker re-runs
    // beforeAll and never sees a null book.
    test('/pages carries the blurb and the PB2 reader displays it (LLM+SD, gated)', async ({ page, request }) => {
        test.skip(!RENDER_ENABLED, 'PB_SD_TESTS!=1 — skips LLM/SD render');
        test.setTimeout(900000);
        await apiLoginShared(request);
        expect(pb2BookObjectId, 'beforeAll must have created the book').toBeTruthy();
        expect(metaScenes.length, 'beforeAll must have persisted scenes').toBeGreaterThan(0);

        const genResp = await request.post(PB + '/scene/' + metaScenes[0].objectId + '/generate', {
            data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName, sdConfig: { steps: 20, hires: false } },
            timeout: 600000
        });
        expect(genResp.ok(), 'scene generate failed: ' + genResp.status() + ' ' + await genResp.text()).toBe(true);
        const gen = await genResp.json();
        expect(gen && gen.imageObjectId, 'generateSceneImage produced no image: ' + JSON.stringify(gen)).toBeTruthy();

        const pagesResp = await request.get(PB + '/' + pb2BookObjectId + '/pages');
        expect(pagesResp.ok(), 'pages fetch failed: ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        console.log('[sceneText] pages=' + JSON.stringify(pages.map(p => ({ i: p.sceneIndex, title: p.title, blurb: p.blurb }))));
        const rendered = pages.find(p => p.title === SCENES[0].title);
        expect(rendered, 'no /pages entry for the rendered scene').toBeTruthy();
        expect(rendered.blurb, '/pages blurb is empty — the dual-write copied nothing').toBe(SCENES[0].blurb);

        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/picture-book/v2/' + oid; }, pb2BookObjectId);
        const begin = page.locator('button:has-text("Begin")');
        await expect(begin, 'reader cover must offer Begin once a page exists').toBeVisible({ timeout: 30000 });
        await begin.click();

        const pageText = page.locator('p.italic');
        await expect(pageText.first()).toBeVisible({ timeout: 15000 });
        const bodyText = await page.evaluate(() => document.body.innerText || '');
        const shot = path.join(OUT_DIR, 'pb2-reader-scene-text.png');
        await page.screenshot({ path: shot, fullPage: true });
        console.log('[sceneText] reader screenshot: ' + shot);
        expect(bodyText.includes('No text yet.'), 'reader still shows "No text yet."').toBe(false);
        expect(bodyText, 'reader does not display the scene blurb').toContain(SCENES[0].blurb);
    });
});
