/**
 * PictureBook P7-4 — opening a pre-V3 book: the "replaced by V3" notice's Import runs POST /migrate-v1
 * and opens the V3 reader on the imported book, with the result shown over it; the imported image shows.
 *
 * Why: under picturebook renderPath v3 a pure PB1 book (group → .pictureBookMeta → scene notes, no
 * olio.pb.book) is shown read-only on the PB1 viewer (P7-3). The only way forward for it is the
 * server's PbMigrationUtil.importV1Book, which creates the V3 book + scenes, carries the scene text,
 * the rendered scene image (the note's imageObjectId, read by bookPageView's fallback) and the
 * landscape (an unapproved artifact on the scene's LANDSCAPE node), and never modifies a PB1 record.
 *
 * Fixture: the exact on-disk PB1 shape, built over REST as the shared test user — book group,
 * Scenes group, one data.data PNG, one scene note whose JSON names that PNG as both imageObjectId
 * and landscapeObjectId, and the .pictureBookMeta note listing the scene. No olio.pb.book exists
 * for it until Import runs.
 *
 * Checks: notice → Import → POST /migrate-v1 {groupObjectId} 200 → hash /picture-book/v2/<new oid>
 * → result dialog "1 scene imported, 1 landscape imported" → Close → the reader page shows the PB1
 * image through /media (200) → the PB1 records are unchanged → the group now bridges (/pb2 200) so
 * re-opening the PB1 route redirects to the V3 reader → a second /migrate-v1 is the 409 slug
 * conflict (REST; the UI's handling of it is in src/test/pictureBookV3Import.test.js).
 *
 * Needs the Docker stack (PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443), renderPath v3. Skips under v1.
 * Never the admin user. Nothing here calls the LLM or the SD server.
 */
import fs from 'fs';
import path from 'path';
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, deleteObject, createObject, pb1Retired } from './helpers/api.js';
import { screenshot } from './helpers/auth.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';
const V1_SKIP = 'picturebook.renderPath=v1: the V3 import under test only exists under v3 (P7-4).';

let shared = null;
let v3 = true;

async function restLogin(ctx) {
    expect(shared, 'ensureSharedTestUser() must run before any login').toBeTruthy();
    const resp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
    const body = (await resp.text().catch(() => '')).trim();
    expect(body, 'login returned ' + resp.status() + ' but body was "' + body + '" (credential rejected)').not.toBe('false');
}

// WebSocket stub: Docker's nginx drops the session cookie on the WS upgrade, Tomcat closes the socket,
// and the app's reconnect path forces #!/sig. Installed before page.goto (troubleshooting.md).
function wsStubInit() {
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
}

async function loginAsSharedUser(page) {
    await restLogin(page.request);
    await page.addInitScript(wsStubInit);
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function gotoHash(page, hash) {
    await page.evaluate((h) => { window.location.hash = h; }, hash);
}

function currentHash(page) {
    return page.evaluate(() => window.location.hash);
}

// A data.note POSTed directly (createNote() would hand back any existing note of the same name).
async function postNote(request, dir, name, text) {
    const resp = await request.post(REST + '/model', {
        data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name, text }
    });
    expect(resp.ok(), 'POST data.note "' + name + '" failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const rec = await resp.json();
    expect(rec && rec.objectId, 'data.note "' + name + '" returned no objectId').toBeTruthy();
    return rec;
}

async function getNoteText(request, oid) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'data.note', request: ['id', 'objectId', 'name', 'text'], cache: false,
            fields: [{ name: 'objectId', comparator: 'EQUALS', value: oid }] }
    });
    expect(resp.ok(), 'note search failed: ' + resp.status()).toBe(true);
    const j = await resp.json();
    const rows = Array.isArray(j) ? j : (j && j.results) || [];
    return rows.length ? rows[0] : null;
}

async function resetBook(request, oid) {
    if (!oid) return;
    try {
        const r = await request.delete(PB_REST + '/' + oid + '/reset');
        console.log('[p74-cleanup] reset ' + oid + ' -> ' + r.status() + ' ' + (await r.text().catch(() => '')).slice(0, 160));
    } catch (e) { console.log('[p74-cleanup] reset ' + oid + ' threw ' + (e && e.message)); }
}

// ── Fixture: one pure PB1 book with a rendered scene image and a landscape ───────────────────────────
let tag = null;
let legacyName = null;
let legacyGroup = null;
let scenesGroup = null;
let imagesGroup = null;
let sceneImage = null;         // data.data PNG the PB1 scene note names as imageObjectId + landscapeObjectId
let sceneImageName = null;     // its name (the create response carries identity fields only)
let sceneNote = null;
let metaNote = null;
let sceneNoteText = null;
let metaNoteText = null;
let importedPb2Oid = null;     // the olio.pb.book Import creates — reset in afterAll
const SCENE_TITLE = 'The Keeper Rows Out';
const SCENE_BLURB = 'The keeper pushes the skiff off the shingle.';

test.describe('PictureBook V3 import of a pre-V3 book (P7-4)', () => {
    test.describe.configure({ timeout: 240000 });

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        v3 = await pb1Retired(request, { userName: shared.testUserName, password: shared.testPassword });
        if (!v3) return;
        await restLogin(request);

        tag = Date.now().toString(36);
        legacyName = 'e2e-p74-legacy-' + tag;
        legacyGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName);
        expect(legacyGroup && legacyGroup.objectId, 'legacy book group could not be ensured').toBeTruthy();
        scenesGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName + '/Scenes');
        expect(scenesGroup && scenesGroup.id, 'legacy Scenes group could not be ensured').toBeTruthy();
        imagesGroup = await ensurePath(request, 'auth.group', 'data', '~/Data/PictureBooks/' + legacyName + '/Images');
        expect(imagesGroup && imagesGroup.id, 'legacy Images group could not be ensured').toBeTruthy();

        // The "rendered" PB1 scene image: a real PNG so the reader's <img> can load it through /media.
        const imgPath = path.resolve('public', 'media', 'femaleSilhouette.png');
        expect(fs.existsSync(imgPath), 'fixture PNG missing: ' + imgPath).toBe(true);
        sceneImageName = 'scene-1-' + tag + '.png';
        sceneImage = await createObject(request, 'data.data', {
            groupId: imagesGroup.id, groupPath: imagesGroup.path,
            name: sceneImageName, contentType: 'image/png',
            dataBytesStore: fs.readFileSync(imgPath).toString('base64')
        });
        expect(sceneImage && sceneImage.objectId, 'fixture data.data could not be created').toBeTruthy();

        // The scene note, in the shape PictureBookUtil writes (pictureBookSceneModel.json keys): the
        // rendered image as imageObjectId, and the same PNG as landscapeObjectId so importLandscapes has
        // a landscape to carry.
        sceneNoteText = JSON.stringify({
            index: 0, title: SCENE_TITLE, blurb: SCENE_BLURB,
            setting: 'shingle beach', action: 'launching a skiff', mood: 'resolute', characters: [],
            imageObjectId: sceneImage.objectId, landscapeObjectId: sceneImage.objectId
        });
        sceneNote = await postNote(request, scenesGroup, 'Scene 1', sceneNoteText);
        // The meta note is written by buildMeta()'s toFullString(), so it carries the typed schema names;
        // importV1Book re-parses it as a typed olio.pictureBookMeta and a bare object parses as null (400).
        metaNoteText = JSON.stringify({
            schema: 'olio.pictureBookMeta',
            bookObjectId: legacyGroup.objectId, workName: legacyName, sceneCount: 1,
            scenes: [{
                schema: 'olio.pictureBookScene',
                objectId: sceneNote.objectId, index: 0, title: SCENE_TITLE, description: SCENE_BLURB,
                setting: 'shingle beach', action: 'launching a skiff', mood: 'resolute', characters: []
            }]
        });
        metaNote = await postNote(request, legacyGroup, '.pictureBookMeta', metaNoteText);

        // Preconditions: the PB1 scene list resolves and there is no V3 book for the group yet.
        const sc = await request.get(PB_REST + '/' + legacyGroup.objectId + '/scenes');
        expect(sc.status(), 'GET /{legacyGroup}/scenes should be 200').toBe(200);
        const scenes = await sc.json();
        expect(Array.isArray(scenes) && scenes.length === 1 && scenes[0].title === SCENE_TITLE,
            'legacy fixture did not read back as one scene: ' + JSON.stringify(scenes).slice(0, 300)).toBe(true);
        const noPb2 = await request.get(PB_REST + '/' + legacyGroup.objectId + '/pb2');
        expect(noPb2.status(), 'GET /{legacyGroup}/pb2 should be 404 before import').toBe(404);
        console.log('[p74] legacy group=' + legacyGroup.objectId + ' image=' + sceneImage.objectId + ' scene=' + sceneNote.objectId);
    });

    test.afterAll(async ({ request }) => {
        if (!v3) return;
        try {
            await restLogin(request);
            await resetBook(request, importedPb2Oid);
            for (const n of [metaNote, sceneNote]) {
                if (n && n.objectId) await deleteObject(request, 'data.note', n.objectId).catch(() => {});
            }
            if (sceneImage && sceneImage.objectId) await deleteObject(request, 'data.data', sceneImage.objectId).catch(() => {});
            for (const g of [imagesGroup, scenesGroup, legacyGroup]) {
                if (g && g.objectId) await deleteObject(request, 'auth.group', g.objectId).catch(() => {});
            }
            await apiLogout(request).catch(() => {});
        } catch (e) {
            console.log('[p74-cleanup] ' + (e && e.message));
        }
    });

    test('Import on the legacy notice migrates the book, opens the V3 reader with the result, and the image shows', async ({ page }) => {
        test.skip(!v3, V1_SKIP);
        await loginAsSharedUser(page);
        const consoleErrors = [];
        page.on('pageerror', (e) => consoleErrors.push(String(e && e.message)));

        await gotoHash(page, '!/picture-book/' + legacyGroup.objectId);
        const notice = page.locator('[data-pb1-legacy-notice]');
        await expect(notice).toBeVisible({ timeout: 30000 });
        await expect(notice).toContainText('replaced by V3');
        const importBtn = notice.locator('[data-pb1-import]');
        await expect(importBtn).toBeVisible();
        await expect(importBtn).toHaveText(/Import into V3/);
        await expect(importBtn).toBeEnabled();
        await screenshot(page, 'p74-legacy-notice-import');

        // Click → POST /migrate-v1 {groupObjectId} → 200 with the counts.
        const migrate = page.waitForResponse((r) => r.request().method() === 'POST' && /\/olio\/picture-book\/migrate-v1$/.test(r.url()), { timeout: 120000 });
        await importBtn.click();
        const migrateResp = await migrate;
        const sentBody = JSON.parse(migrateResp.request().postData() || '{}');
        expect(sentBody).toEqual({ groupObjectId: legacyGroup.objectId });
        const migrateText = await migrateResp.text();
        expect(migrateResp.status(), 'POST /migrate-v1 failed: ' + migrateResp.status() + ' ' + migrateText).toBe(200);
        const result = JSON.parse(migrateText);
        importedPb2Oid = result.bookObjectId;
        console.log('[p74] migrate-v1 -> ' + migrateText.slice(0, 600));
        expect(importedPb2Oid, 'migrate-v1 returned no bookObjectId').toBeTruthy();
        expect(result.scenesImported).toBe(1);
        expect(result.scenesFailed || 0).toBe(0);
        expect(result.landscapesImported).toBe(1);

        // The V3 reader on the imported book, with the result dialog on top.
        await expect.poll(() => currentHash(page), { timeout: 30000 }).toBe('#!/picture-book/v2/' + importedPb2Oid);
        const dialog = page.locator('.am7-dialog').filter({ has: page.locator('.am7-dialog-title', { hasText: 'Imported into V3' }) });
        await expect(dialog).toBeVisible({ timeout: 15000 });
        const resultBox = dialog.locator('[data-pb1-import-result]');
        await expect(resultBox).toContainText('1 scene imported, 1 landscape imported');
        await expect(resultBox).toContainText(result.slug);
        const warningCount = (result.warnings || []).length;
        console.log('[p74] warnings=' + warningCount + (warningCount ? ' ' + JSON.stringify(result.warnings).slice(0, 600) : ''));
        if (warningCount) {
            await expect(resultBox).toContainText(warningCount + ' warning');
            await expect(resultBox.locator('li')).toHaveCount(warningCount);
        } else {
            await expect(resultBox).not.toContainText('warning');
        }
        await screenshot(page, 'p74-import-result-dialog');
        await dialog.locator('[data-pb1-import-close]').click();
        await expect(dialog).toHaveCount(0);

        // Still in the reader (closing the dialog does not navigate); the book reads as 1 scene and the
        // PB1 image is the page image, served by /media.
        expect(await currentHash(page)).toBe('#!/picture-book/v2/' + importedPb2Oid);
        await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: 30000 });
        await expect(page.locator('[data-pb2-cover-count]')).toContainText('of 1 scene', { timeout: 30000 });
        await page.locator('button:has-text("Begin")').click();
        await expect(page.locator('h2', { hasText: SCENE_TITLE })).toBeVisible({ timeout: 15000 });
        const img = page.locator('[data-testid="pb2-scene-image"]');
        await expect(img).toBeVisible({ timeout: 15000 });
        // The cover already drew the same URL as its backdrop, so the page's <img> is served from the
        // browser's memory cache and no second network response exists to wait for. Prove the bytes
        // instead: the <img> decoded (naturalWidth), and the same /media URL answers 200 with the
        // page's session.
        const imgSrc = await img.getAttribute('src');
        console.log('[p74] page image src=' + imgSrc);
        expect(imgSrc, 'page image src').toContain('/media/');
        expect(imgSrc, 'page image src').toContain('/data.data');
        expect(imgSrc, 'page image src').toContain(sceneImageName);
        await expect.poll(() => img.evaluate((el) => el.naturalWidth), { timeout: 15000 }).toBeGreaterThan(0);
        const media = await page.request.get(await img.evaluate((el) => el.src));
        console.log('[p74] media ' + media.status() + ' ' + media.headers()['content-type']);
        expect(media.status(), 'the imported scene image did not load from /media').toBe(200);
        expect(String(media.headers()['content-type'] || ''), '/media content-type').toContain('image/');
        await expect(page.locator('text=' + SCENE_BLURB)).toBeVisible();
        await screenshot(page, 'p74-reader-imported-image');
        expect(consoleErrors, 'page errors during import').toEqual([]);
    });

    test('PB1 records are untouched; the group now bridges to the V3 book; a re-import is the 409 slug conflict', async ({ page, request }) => {
        test.skip(!v3, V1_SKIP);
        expect(importedPb2Oid, 'the import test must have run first').toBeTruthy();
        await restLogin(request);

        // 1. PB1 never modified: the scene note and the meta note read back byte-for-byte.
        const s = await getNoteText(request, sceneNote.objectId);
        expect(s && s.text, 'scene note vanished').toBeTruthy();
        expect(s.text).toBe(sceneNoteText);
        const mt = await getNoteText(request, metaNote.objectId);
        expect(mt && mt.text, 'meta note vanished').toBeTruthy();
        expect(mt.text).toBe(metaNoteText);

        // 2. The V3 book is in /books under the slug derived from the group name, and the PB1 group now
        //    bridges to it (/pb2 resolves by that same slug) ...
        const books = await (await request.get(PB_REST + '/books')).json();
        const book = books.find((b) => b.objectId === importedPb2Oid);
        expect(book, 'imported book is not in GET /books').toBeTruthy();
        expect(book.title).toBe(legacyName);
        const bridge = await request.get(PB_REST + '/' + legacyGroup.objectId + '/pb2');
        expect(bridge.status(), 'GET /{legacyGroup}/pb2 should be 200 after import').toBe(200);
        expect((await bridge.json()).pb2BookObjectId).toBe(importedPb2Oid);

        // ... so re-opening the PB1 route redirects to the V3 reader instead of showing the notice again.
        await loginAsSharedUser(page);
        await gotoHash(page, '!/picture-book/' + legacyGroup.objectId);
        await expect.poll(() => currentHash(page), { timeout: 30000 }).toBe('#!/picture-book/v2/' + importedPb2Oid);
        await expect(page.locator('[data-pb1-legacy-notice]')).toHaveCount(0);

        // 3. A second import of the same group is the server's 409 slug conflict (the UI relays this text
        //    as an error toast — src/test/pictureBookV3Import.test.js).
        const again = await request.post(PB_REST + '/migrate-v1', { data: { groupObjectId: legacyGroup.objectId } });
        const againText = await again.text();
        console.log('[p74] second migrate-v1 -> ' + again.status() + ' ' + againText.slice(0, 300));
        expect(again.status()).toBe(409);
        expect(JSON.parse(againText).error).toMatch(/already exists/);
    });
});
