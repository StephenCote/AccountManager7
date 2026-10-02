/**
 * PB2 book view after a wizard-path render — reproduces "images are generated for scenes, but the
 * book view reports there are no images".
 *
 * Follows exactly what the wizard's Step-2 Continue + Step-4 Generate do (workflows/pictureBook.js):
 *   POST /chapter {slug,title}                      -> olio.pb.book (+ its own world)
 *   POST /{work}/create-from-scenes {pb2BookObjectId} -> PB1 scene group linked to that book
 *   POST /scene/{sceneOid}/generate {chatConfig}    -> SD render (PictureBookUtil.generateSceneImage)
 * then reads what the two views read:
 *   GET /{pb2Book}/scenes   (editor: PB1 meta, imageObjectId)
 *   GET /{pb2Book}/pages    (book view /picture-book/v2/{id}: olio.pb.scene -> sceneNode -> composite)
 * and asserts the rendered scene is visible from BOTH.
 *
 * Two shapes: a standalone book, and a chapter book inside a series (the novel fan-out path,
 * fanOutChaptersExtract: POST /series, then POST /chapter {seriesObjectId, chapter, sourceRange}).
 *
 * Gated (PB_SD_TESTS=1) and serial: hits the LLM (.42 / LiteLLM) and SD (.39).
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test \
 *     e2e/pictureBookPagesAfterRender.spec.js --workers=1 --project=chromium
 *
 * Runs as e2etest_shared (ensureSharedTestUser) — never admin.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const RENDER_ENABLED = process.env.PB_SD_TESTS === '1';
const WORK_TEXT = 'A lone lighthouse keeper watches a storm roll across the northern sea at dawn.';

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

async function createWorkNote(request, tag) {
    const notesDir = await request.get(REST + '/path/make/auth.group/data/' +
        'B64-' + Buffer.from('~/Notes').toString('base64').replace(/=/g, '%3D'));
    const dir = await notesDir.json().catch(() => null);
    expect(dir && dir.id, 'could not resolve ~/Notes group').toBeTruthy();
    const noteResp = await request.post(REST + '/model', {
        data: {
            schema: 'data.note', groupId: dir.id, groupPath: dir.path, name: 'pb2-pages-work-' + tag,
            text: WORK_TEXT
        }
    });
    expect(noteResp.ok(), 'work note create failed: ' + noteResp.status()).toBe(true);
    const workOid = (await noteResp.json()).objectId;
    expect(workOid, 'no work objectId').toBeTruthy();
    return workOid;
}

/** A chapter book's sourceDataObjectId must be a data.data document (the manuscript), not a note. */
async function uploadWorkDocument(request, tag) {
    const dirResp = await request.get(REST + '/path/make/auth.group/data/' +
        'B64-' + Buffer.from('~/Manuscripts').toString('base64').replace(/=/g, '%3D'));
    const dir = await dirResp.json().catch(() => null);
    expect(dir && dir.id, 'could not resolve ~/Manuscripts group').toBeTruthy();
    const createResp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data', name: 'pb2-pages-work-' + tag + '.txt', groupId: dir.id, groupPath: dir.path,
            contentType: 'text/plain', dataBytesStore: Buffer.from(WORK_TEXT).toString('base64')
        }
    });
    expect(createResp.ok(), 'work document upload failed: ' + createResp.status()).toBe(true);
    const rec = await createResp.json();
    expect(rec && rec.objectId, 'work document create returned no objectId').toBeTruthy();
    return rec.objectId;
}

function sceneList() {
    return [{
        sceneIndex: 0,
        title: 'The Lighthouse at Dawn',
        summary: 'A lighthouse keeper watches a storm approach across the northern sea at dawn.',
        description: WORK_TEXT,
        characters: [{ name: 'The Keeper' }]
    }];
}

function twoSceneList() {
    return sceneList().concat([{
        sceneIndex: 1,
        title: 'The Lamp Room',
        summary: 'The keeper climbs to the lamp room as the first wave breaks against the rocks.',
        description: 'The keeper climbs the iron stair to the lamp room as the first wave breaks against the rocks below.',
        characters: [{ name: 'The Keeper' }]
    }]);
}

const SD_CONFIG_IDENTITY = ['id', 'objectId', 'urn', 'ownerId', 'groupId', 'organizationId', 'groupPath', 'organizationPath', 'narration'];

/** The wizard's common olio.sd.config: /olio/randomImageConfig with pinPictureBookDefaults applied. */
async function buildWizardSdConfig(request) {
    const resp = await request.get(REST + '/olio/randomImageConfig');
    expect(resp.ok(), 'GET /olio/randomImageConfig failed: ' + resp.status()).toBe(true);
    const entity = await resp.json();
    SD_CONFIG_IDENTITY.forEach(function (k) { delete entity[k]; });
    entity.schema = 'olio.sd.config';
    entity.compositeMode = 'flux2';
    entity.hires = false;
    entity.skipLandscape = true;
    entity.flux2IncludeLandscapeRef = false;
    entity.style = 'digitalArt';
    return entity;
}

async function generateLikeWizard(request, label, sceneOid, sdConfig, chatConfigName) {
    const genResp = await request.post(PB + '/scene/' + sceneOid + '/generate', {
        data: { schema: 'olio.pictureBookRequest', sdConfig, chatConfig: chatConfigName }, timeout: 900000
    });
    expect(genResp.ok(), label + ' generate failed: ' + genResp.status() + ' ' + await genResp.text()).toBe(true);
    const gen = await genResp.json();
    console.log('[pbPages] ' + label + ' generate ' + sceneOid + ' -> imageObjectId=' + gen.imageObjectId
        + ' graphWriteFailures=' + JSON.stringify(gen.graphWriteFailures || null));
    expect(gen.imageObjectId, label + ' generate produced no imageObjectId').toBeTruthy();
    return gen;
}

/**
 * Steps 2-5 shared by both shapes: create-from-scenes into the PB2 book, render the one scene the
 * way the wizard's Generate button does, then assert the editor (/scenes) AND the book view (/pages)
 * both see the image.
 */
async function persistRenderAndAssert(request, label, pb2BookOid, workOid, chatConfigName, tag) {
    const cfsResp = await request.post(PB + '/' + workOid + '/create-from-scenes', {
        data: {
            schema: 'olio.pictureBookRequest', sceneList: sceneList(), chatConfig: chatConfigName,
            genre: 'literary', bookName: 'PB2 Pages ' + tag, pb2BookObjectId: pb2BookOid
        },
        timeout: 600000
    });
    expect(cfsResp.ok(), label + ' create-from-scenes failed: ' + cfsResp.status() + ' ' + await cfsResp.text()).toBe(true);
    const meta = await cfsResp.json();
    console.log('[pbPages] ' + label + ' create-from-scenes meta=' + JSON.stringify(meta).slice(0, 600));
    expect(meta.pb2BookObjectId, label + ' create-from-scenes did not link the PB2 book').toBe(pb2BookOid);

    // Editor's scene list (PB1 meta) for the PB2 book objectId — what the wizard renders from.
    const scenesResp = await request.get(PB + '/' + pb2BookOid + '/scenes');
    expect(scenesResp.ok(), label + ' GET /scenes failed: ' + scenesResp.status()).toBe(true);
    const scenes = await scenesResp.json();
    expect(Array.isArray(scenes) && scenes.length === 1, label + ' expected one PB1 scene: ' + JSON.stringify(scenes)).toBe(true);
    const sceneOid = scenes[0].objectId;
    expect(sceneOid, label + ' scene has no objectId').toBeTruthy();

    // Render exactly as the wizard's Generate button does.
    const genResp = await request.post(PB + '/scene/' + sceneOid + '/generate', {
        data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName }, timeout: 600000
    });
    expect(genResp.ok(), label + ' generate failed: ' + genResp.status() + ' ' + await genResp.text()).toBe(true);
    const gen = await genResp.json();
    console.log('[pbPages] ' + label + ' generate -> imageObjectId=' + gen.imageObjectId
        + ' graphWriteFailures=' + JSON.stringify(gen.graphWriteFailures || null));
    expect(gen.imageObjectId, label + ' generate produced no imageObjectId').toBeTruthy();

    // Editor view: /scenes now carries the image.
    const scenes2 = await (await request.get(PB + '/' + pb2BookOid + '/scenes')).json();
    expect(scenes2[0].imageObjectId, label + ' editor /scenes lost the imageObjectId').toBe(gen.imageObjectId);

    // Book view: /pages must report the same scene as rendered.
    const pagesResp = await request.get(PB + '/' + pb2BookOid + '/pages');
    expect(pagesResp.ok(), label + ' GET /pages failed: ' + pagesResp.status() + ' ' + await pagesResp.text()).toBe(true);
    const pages = await pagesResp.json();
    console.log('[pbPages] ' + label + ' /pages after render: ' + JSON.stringify(pages).slice(0, 800));
    expect(pages.length, label + ' book view /pages has no scene rows although a scene was rendered').toBe(1);
    expect(pages[0].dataObjectId,
        label + ' book view /pages shows the scene as not rendered (no dataObjectId) although /scenes has imageObjectId='
        + gen.imageObjectId + ': ' + JSON.stringify(pages[0])).toBeTruthy();

    // The workflow the render was supposed to record into exists for this book.
    const wfResp = await request.get(PB + '/' + pb2BookOid + '/workflow');
    console.log('[pbPages] ' + label + ' /workflow status=' + wfResp.status());
    expect(wfResp.status(), label + ' book has no workflow after a render').toBe(200);
}

test.describe('PB2 book view sees wizard-rendered scene images (LLM+SD, gated)', () => {
    test.describe.configure({ timeout: 900000 });
    test.skip(!RENDER_ENABLED, 'PB_SD_TESTS!=1 — skips LLM/SD render');

    let chatConfigName = null;

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await apiLoginShared(request);
        chatConfigName = await ensureChatConfig(request);
        expect(chatConfigName, 'could not provision an LLM chatConfig').toBeTruthy();
    });

    test('standalone book: render via /scene/{id}/generate -> /pages reports the composite', async ({ request }) => {
        await apiLoginShared(request);
        const tag = Date.now().toString(36);

        // The wizard creates the PB2 book first (createChapBookRecord), slug = generateSlug(bookName).
        const slug = 'pb2-pages-' + tag;
        const chapterResp = await request.post(PB + '/chapter', {
            data: { slug, title: 'PB2 Pages ' + tag }, timeout: 120000
        });
        expect(chapterResp.ok(), 'POST /chapter failed: ' + chapterResp.status() + ' ' + await chapterResp.text()).toBe(true);
        const pb2BookOid = (await chapterResp.json()).bookObjectId;
        expect(pb2BookOid, 'no bookObjectId from /chapter').toBeTruthy();

        const workOid = await createWorkNote(request, tag);
        await persistRenderAndAssert(request, 'standalone', pb2BookOid, workOid, chatConfigName, tag);
    });

    test('series chapter book: render -> /pages reports the composite', async ({ request }) => {
        await apiLoginShared(request);
        const tag = Date.now().toString(36);
        const workOid = await uploadWorkDocument(request, tag);

        // fanOutChaptersExtract: one series, then chapter N inside it with a sourceRange over the work.
        const seriesSlug = 'pb2-pages-series-' + tag;
        const seriesResp = await request.post(PB + '/series', {
            data: { seriesSlug, title: 'PB2 Pages Series ' + tag }, timeout: 120000
        });
        expect(seriesResp.ok(), 'POST /series failed: ' + seriesResp.status() + ' ' + await seriesResp.text()).toBe(true);
        const series = await seriesResp.json();
        expect(series.seriesObjectId, 'createSeries returned no seriesObjectId').toBeTruthy();

        const chapterResp = await request.post(PB + '/chapter', {
            data: {
                slug: seriesSlug + '-ch1',
                title: 'Chapter One',
                seriesObjectId: series.seriesObjectId,
                chapter: 1,
                sourceDataObjectId: workOid,
                sourceRange: { startOffset: 0, endOffset: WORK_TEXT.length, title: 'Chapter One' }
            },
            timeout: 120000
        });
        expect(chapterResp.ok(), 'POST /chapter (series) failed: ' + chapterResp.status() + ' ' + await chapterResp.text()).toBe(true);
        const pb2BookOid = (await chapterResp.json()).bookObjectId;
        expect(pb2BookOid, 'no bookObjectId from series /chapter').toBeTruthy();
        console.log('[pbPages] chapter series=' + series.seriesObjectId + ' book=' + pb2BookOid);

        await persistRenderAndAssert(request, 'chapter', pb2BookOid, workOid, chatConfigName, tag);
    });

    // The wizard's "Generate All" (doGenerateAll/doGenerateOne): PUT /{book}/settings with the common
    // config, POST /{book}/prepare-images for every pending scene, then per scene POST
    // /scene/{id}/generate carrying sdConfig (compositeMode=flux2, skipLandscape=true) + chatConfig.
    // Two scenes sharing one character, then scene 0 rendered a SECOND time (supersedes + setSelected
    // on a two-revision chain). /pages must report both scenes with the latest composite.
    test('wizard Generate All shape: two scenes + a re-render -> /pages reports every composite', async ({ request }) => {
        test.setTimeout(1800000);
        await apiLoginShared(request);
        const tag = Date.now().toString(36);
        const slug = 'pb2-pages-ga-' + tag;
        const chapterResp = await request.post(PB + '/chapter', {
            data: { slug, title: 'PB2 Pages GA ' + tag }, timeout: 120000
        });
        expect(chapterResp.ok(), 'POST /chapter failed: ' + chapterResp.status() + ' ' + await chapterResp.text()).toBe(true);
        const pb2BookOid = (await chapterResp.json()).bookObjectId;
        expect(pb2BookOid, 'no bookObjectId from /chapter').toBeTruthy();
        const workOid = await createWorkNote(request, tag);

        const cfsResp = await request.post(PB + '/' + workOid + '/create-from-scenes', {
            data: {
                schema: 'olio.pictureBookRequest', sceneList: twoSceneList(), chatConfig: chatConfigName,
                genre: 'literary', bookName: 'PB2 Pages GA ' + tag, pb2BookObjectId: pb2BookOid
            },
            timeout: 600000
        });
        expect(cfsResp.ok(), 'create-from-scenes failed: ' + cfsResp.status() + ' ' + await cfsResp.text()).toBe(true);
        const meta = await cfsResp.json();
        console.log('[pbPages] GA create-from-scenes meta=' + JSON.stringify(meta).slice(0, 600));
        expect(meta.pb2BookObjectId).toBe(pb2BookOid);
        // A fresh wizard run holds the PB1 scene-group objectId as its bookObjectId (resume from a PB2
        // surface holds the pb.book objectId; both resolve server-side).
        const wizardBookId = meta.bookObjectId || pb2BookOid;

        const scenes = await (await request.get(PB + '/' + pb2BookOid + '/scenes')).json();
        expect(Array.isArray(scenes) && scenes.length === 2, 'expected two PB1 scenes: ' + JSON.stringify(scenes)).toBe(true);
        const sceneOids = scenes.map(function (s) { return s.objectId; });

        const sdConfig = await buildWizardSdConfig(request);
        const settingsResp = await request.put(PB + '/' + wizardBookId + '/settings', {
            data: { schema: 'olio.pictureBookRequest', sdConfig }, timeout: 120000
        });
        expect(settingsResp.ok(), 'PUT /settings failed: ' + settingsResp.status() + ' ' + await settingsResp.text()).toBe(true);

        const prepResp = await request.post(PB + '/' + wizardBookId + '/prepare-images', {
            data: { schema: 'olio.pictureBookRequest', sceneObjectIds: sceneOids, chatConfig: chatConfigName, sdConfig },
            timeout: 600000
        });
        expect(prepResp.ok(), 'POST /prepare-images failed: ' + prepResp.status() + ' ' + await prepResp.text()).toBe(true);
        console.log('[pbPages] GA prepare-images -> ' + (await prepResp.text()).slice(0, 300));

        const gen0 = await generateLikeWizard(request, 'GA scene0', sceneOids[0], sdConfig, chatConfigName);
        const gen1 = await generateLikeWizard(request, 'GA scene1', sceneOids[1], sdConfig, chatConfigName);
        const gen0b = await generateLikeWizard(request, 'GA scene0 re-render', sceneOids[0], sdConfig, chatConfigName);
        expect(gen0b.imageObjectId, 're-render returned the same image as the first render').not.toBe(gen0.imageObjectId);

        const scenes2 = await (await request.get(PB + '/' + pb2BookOid + '/scenes')).json();
        const byOid = {};
        scenes2.forEach(function (s) { byOid[s.objectId] = s; });
        expect(byOid[sceneOids[0]].imageObjectId, 'editor scene0 does not carry the re-rendered image').toBe(gen0b.imageObjectId);
        expect(byOid[sceneOids[1]].imageObjectId, 'editor scene1 lost its image').toBe(gen1.imageObjectId);

        const pagesResp = await request.get(PB + '/' + pb2BookOid + '/pages');
        expect(pagesResp.ok(), 'GET /pages failed: ' + pagesResp.status() + ' ' + await pagesResp.text()).toBe(true);
        const pages = await pagesResp.json();
        console.log('[pbPages] GA /pages: ' + JSON.stringify(pages.map(function (p) {
            return { sceneIndex: p.sceneIndex, title: p.title, dataObjectId: p.dataObjectId, imageName: p.imageName };
        })));
        expect(pages.length, 'book view /pages row count').toBe(2);
        const page0 = pages.find(function (p) { return p.sceneIndex === 0; });
        const page1 = pages.find(function (p) { return p.sceneIndex === 1; });
        expect(page0 && page0.dataObjectId, 'book view shows scene 0 as not rendered: ' + JSON.stringify(page0)).toBeTruthy();
        expect(page1 && page1.dataObjectId, 'book view shows scene 1 as not rendered: ' + JSON.stringify(page1)).toBeTruthy();
        expect(page0.dataObjectId, 'book view scene 0 is not the re-rendered composite').toBe(gen0b.imageObjectId);
        expect(page1.dataObjectId, 'book view scene 1 is not its composite').toBe(gen1.imageObjectId);
    });
});
