/**
 * PictureBook V3 Phase 4 — the compose job routes (P4-4), proven against the live stack as the shared
 * non-admin user. API-only (the Playwright `request` fixture). Its own book, never the locations spec's:
 * the compose job ends with the whole-book location pass, which would write proposals that spec reads.
 *
 * Routes under REST /olio/picture-book (PictureBookService, PbServiceFacade, PbComposeJobUtil, PbComposeUtil):
 *   POST /{book}/compose                 { quickStart?, force?, chatConfigName? }  202 {jobId} | 200 {submitted:false, reason}
 *   POST /{book}/scene/{scene}/compose   { force?, chatConfigName? }              200 composeResultDto (synchronous)
 *   POST /{book}/locations/propose       202 {jobId}  (the location pass alone; covered by pictureBookLocations.spec.js)
 *   GET  /rest/job/{jobId}, POST /rest/job/{jobId}/cancel
 *
 * Tiers (a gated skip is a skip, never a pass):
 *   default            — GET /rest/config/render-path says v3; compose on a scene-less book is a 200 no-op that
 *                        queues nothing; malformed body 400; unknown book 404; unknown scene 404; a create-from-scenes
 *                        import with no PictureBook bound answers composeJobSkipped. No LLM, no SD.
 *   PB_LLM_TESTS=1     — a create-from-scenes import bound to a /chapter book dual-writes its olio.pb.scene rows and
 *                        queues the compose job itself (composeJob in the import result; polled to completion);
 *                        two scenes with settings → POST /compose (202 job; live LLM through the shared user's
 *                        e2e-chapbook-llm config; an immediate second submit reuses the same job) → terminal
 *                        /rest/job result in story order → olio.pb.composition rows in the book group →
 *                        compose-on-open is idempotent → one scene re-composed synchronously with force.
 *   PB_SD_TESTS=1      — POST /compose {quickStart:true, force:true} → every composed scene gets one DRAFT
 *                        render; the bytes come back from the MediaServlet as a real image. No timing asserted.
 *
 * Run (Windows / Docker stack — 127.0.0.1, never localhost):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_LLM_TESTS=1 PB_SD_TESTS=1 \
 *     npx playwright test e2e/pictureBookCompose.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig, ensurePath } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const TAG = '[pictureBookCompose]';
const NIL = '00000000-0000-0000-0000-000000000000';

// Two scenes with a setting, an action and a mood but no cast: the LLM stages elements and (POPULATION or
// NONE tier) actors from the text alone, which is the common case for a freshly imported book.
const SCENES = [
    [1, 'Dawn on the Quay', 'The harbour road at dawn, gulls over the fishing boats',
        'A fishwife hauls a basket of herring up the slick stone steps while a boy coils rope', 'brisk'],
    [2, 'The Copyist', 'A cramped scriptorium in the abbey, candles guttering',
        'An old monk bends over a half-finished page, a quill in one hand and a knife in the other', 'hushed']
];

test.describe.configure({ mode: 'serial' });

let api;               // one logged-in APIRequestContext for the whole file
let bookObjectId;
let bookSlug;
let bookId;
let bookGroupId;
let bookGroupPath;
let bookReset = false;

let chatConfigName;
let sceneIds = [];     // [objectId] in story order, filled by the LLM tier
let composeResult;     // the terminal job result of the first compose job

async function expectStatus(resp, status, what) {
    const body = await resp.text();
    expect(resp.status(), what + ': expected ' + status + ' got ' + resp.status() + ' ' + body.slice(0, 400)).toBe(status);
    try { return JSON.parse(body); } catch (e) { return body; }
}

function errMsg(body) {
    if (body && typeof body === 'object') return String(body.message || body.error || JSON.stringify(body));
    return String(body);
}

async function pollJob(jobId, label, maxMs) {
    const t0 = Date.now();
    let last = '';
    for (;;) {
        const r = await api.get(REST + '/job/' + jobId);
        const j = await expectStatus(r, 200, 'GET /job/' + jobId);
        const line = j.status + ' phase=' + j.phase + ' ' + j.current + '/' + j.total + ' elapsed=' + j.elapsed + 's';
        if (line !== last) { console.log(TAG + ' ' + label + ' job ' + jobId + ': ' + line); last = line; }
        if (j.terminal) return j;
        expect(Date.now() - t0, label + ' job still running after ' + maxMs + ' ms').toBeLessThan(maxMs);
        await new Promise(res => setTimeout(res, 5000));
    }
}

async function listCompositions() {
    const r = await api.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.composition',
            fields: [
                { name: 'groupId', comparator: 'equals', value: bookGroupId },
                { name: 'organizationId', comparator: 'equals', value: 2 }
            ],
            request: ['id', 'objectId', 'name', 'source', 'purpose', 'revision', 'userEdited', 'settingText', 'action',
                'scene.objectId', 'scene.sceneIndex'],
            recordCount: 50, cache: false
        }
    });
    const res = await expectStatus(r, 200, 'olio.pb.composition search');
    const rows = (res && res.results) || [];
    console.log(TAG + ' compositions in group ' + bookGroupId + ': ' + rows.length + ' ' + JSON.stringify(rows.map(c => ({
        objectId: c.objectId, source: c.source, sceneIndex: c.scene && c.scene.sceneIndex, scene: c.scene && c.scene.objectId
    }))));
    return rows;
}

async function fetchImage(dataObjectId, fileName) {
    const dataResp = await api.get(REST + '/model/data.data/' + dataObjectId + '/full');
    const dataRec = await expectStatus(dataResp, 200, 'data.data ' + dataObjectId);
    expect(dataRec.groupPath && dataRec.name, 'data.data missing groupPath/name: ' + JSON.stringify(dataRec).slice(0, 300)).toBeTruthy();
    expect((dataRec.contentType || '').startsWith('image/'), 'contentType ' + dataRec.contentType).toBe(true);
    const mediaUrl = '/AccountManagerService7/media/Development/data.data' + encodeURI(dataRec.groupPath) + '/' + encodeURIComponent(dataRec.name);
    const img = await api.get(mediaUrl, { timeout: 60000 });
    expect(img.ok(), 'media fetch ' + img.status() + ' ' + mediaUrl).toBe(true);
    const buf = Buffer.from(await img.body());
    const isPng = buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47;
    const isJpeg = buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
    expect(isPng || isJpeg, 'bytes are neither PNG nor JPEG: ' + buf.slice(0, 4).toString('hex')).toBe(true);
    expect(buf.length, 'image is not a stub').toBeGreaterThan(1024);
    const out = path.join(OUT_DIR, fileName + (isPng ? '.png' : '.jpg'));
    fs.writeFileSync(out, buf);
    console.log(TAG + ' wrote ' + buf.length + ' bytes to ' + out + ' magic=' + buf.slice(0, 4).toString('hex') + ' ' + dataRec.contentType);
    return out;
}

async function resetBook() {
    if (bookReset || !bookObjectId) return;
    const r = await api.delete(PB + '/' + bookObjectId + '/reset', { timeout: 120000 });
    console.log(TAG + ' DELETE /' + bookObjectId + '/reset -> ' + r.status() + ' ' + (await r.text()).slice(0, 300));
    bookReset = true;
}

test.beforeAll(async ({ request }) => {
    test.setTimeout(5 * 60 * 1000);
    fs.mkdirSync(OUT_DIR, { recursive: true });
    const baseURL = test.info().project.use.baseURL;
    const shared = await ensureSharedTestUser(request);
    api = await pwRequest.newContext({ baseURL, ignoreHTTPSErrors: true });
    await api.get(REST + '/logout');
    const login = await api.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName,
            credential: Buffer.from(shared.testPassword).toString('base64'), type: 'hashed_password'
        }
    });
    expect(login.ok() || login.status() === 204, 'shared-user login failed: ' + login.status()).toBe(true);

    bookSlug = 'p44cmp' + Date.now().toString(36);
    const created = await api.post(PB + '/chapter', { data: { slug: bookSlug, title: 'P4-4 Compose ' + bookSlug }, timeout: 180000 });
    const cb = await expectStatus(created, 200, 'POST /chapter');
    bookObjectId = cb.bookObjectId;
    expect(bookObjectId, 'POST /chapter returned no bookObjectId: ' + JSON.stringify(cb)).toBeTruthy();

    const sr = await api.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.book',
            fields: [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            request: ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'world'], recordCount: 1, cache: false
        }
    });
    const sres = await expectStatus(sr, 200, 'olio.pb.book search');
    const book = sres && sres.results && sres.results[0];
    expect(book && book.id, 'created olio.pb.book not found by objectId: ' + JSON.stringify(sres).slice(0, 300)).toBeTruthy();
    bookId = book.id;
    bookGroupId = book.groupId;
    bookGroupPath = book.groupPath;
    console.log(TAG + ' book ' + bookSlug + ' objectId=' + bookObjectId + ' id=' + bookId + ' groupId=' + bookGroupId + ' groupPath=' + bookGroupPath);
});

test.afterAll(async () => {
    if (!api) return;
    try { await resetBook(); } finally { await api.dispose(); }
});

// ────────────────────────────── default tier: no LLM, no SD ──────────────────────────────
test.describe('compose routes (default tier)', () => {

    test('the stack is on the V3 render path for PictureBook', async () => {
        const r = await api.get(REST + '/config/render-path');
        const rp = await expectStatus(r, 200, 'GET /config/render-path');
        console.log(TAG + ' render-path: ' + JSON.stringify(rp));
        expect(String(rp.picturebook).toLowerCase(), 'the compose routes are V3-only; the test stack boots picturebook on v3').toBe('v3');
    });

    test('compose on a scene-less book is a 200 no-op: nothing is queued, the reason is named', async () => {
        const r = await api.post(PB + '/' + bookObjectId + '/compose', { data: {} });
        const out = await expectStatus(r, 200, 'compose (no scenes)');
        console.log(TAG + ' compose (no scenes): ' + JSON.stringify(out));
        expect(out.submitted, JSON.stringify(out)).toBe(false);
        expect(out.reused).toBe(false);
        expect(out.kind).toBe('pb.compose');
        expect(out.bookObjectId).toBe(bookObjectId);
        expect(out.sceneCount).toBe(0);
        expect(out.scenesToCompose).toBe(0);
        expect(out.scenesCurrent).toBe(0);
        expect(out.quickStart).toBe(false);
        expect(out.force).toBe(false);
        expect(out.reason).toBe('The book has no scenes');
        expect(out.jobId, 'no job was queued').toBeUndefined();
        // force changes nothing on a book with no scenes.
        const f = await expectStatus(await api.post(PB + '/' + bookObjectId + '/compose', { data: { force: true } }), 200, 'compose force (no scenes)');
        expect(f.submitted).toBe(false);
        expect(f.force).toBe(true);
        expect(f.reason).toBe('The book has no scenes');
    });

    test('malformed body is 400; unknown book is 404; unknown scene is 404', async () => {
        let r = await api.post(PB + '/' + bookObjectId + '/compose', { data: '[1,2]', headers: { 'Content-Type': 'application/json' } });
        expect(errMsg(await expectStatus(r, 400, 'compose malformed'))).toBe('Malformed request body');
        r = await api.post(PB + '/' + bookObjectId + '/scene/' + NIL + '/compose', { data: '"x"', headers: { 'Content-Type': 'application/json' } });
        expect(errMsg(await expectStatus(r, 400, 'scene compose malformed'))).toBe('Malformed request body');

        r = await api.post(PB + '/' + NIL + '/compose', { data: {} });
        expect(errMsg(await expectStatus(r, 404, 'compose unknown book'))).toMatch(/not found/i);
        r = await api.post(PB + '/' + NIL + '/scene/' + NIL + '/compose', { data: {} });
        expect(errMsg(await expectStatus(r, 404, 'scene compose unknown book'))).toMatch(/not found/i);

        r = await api.post(PB + '/' + bookObjectId + '/scene/' + NIL + '/compose', { data: {} });
        expect(errMsg(await expectStatus(r, 404, 'compose unknown scene'))).toBe('Scene ' + NIL + ' not found in this book');
    });

    test('a named chat config that does not exist is 404 before anything is queued', async () => {
        const r = await api.post(PB + '/' + bookObjectId + '/compose', { data: { chatConfigName: 'p44-no-such-config-' + bookSlug } });
        expect(errMsg(await expectStatus(r, 404, 'compose bad config'))).toMatch(/^Chat config not found/);
    });
});

// ────────────────────────────── create-from-scenes auto-submit (§9.1 "composed up front") ──────────────────────────────
// The wizard's import is the one place a V3 book gets its scenes, so the compose job is queued from there.
// Each test owns its book (POST /chapter) and its work note; the file's shared book above is never touched.
test.describe('create-from-scenes queues the compose job (default tier: skipped reason; PB_LLM_TESTS=1: job to terminal)', () => {

    // Blurb-only scenes, no characters: the import makes no LLM call of its own (the sceneText spec's load-safe shape).
    const IMPORT_SCENES = [
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

    async function createWorkNote(label) {
        const dir = await ensurePath(api, 'auth.group', 'data', '~/Notes');
        expect(dir && dir.id, '~/Notes group must resolve').toBeTruthy();
        const text = fs.readFileSync(path.resolve(SPEC_DIR, 'fixtures', 'bigWayOut.txt'), 'utf8');
        const r = await api.post(REST + '/model', {
            data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name: 'P4-4 ' + label + ' source ' + bookSlug, text }
        });
        const note = await expectStatus(r, 200, 'source note create');
        expect(note && note.objectId, 'source note objectId: ' + JSON.stringify(note)).toBeTruthy();
        return note.objectId;
    }

    async function listSceneRows(groupId) {
        const r = await api.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.scene',
                fields: [
                    { name: 'groupId', comparator: 'equals', value: groupId },
                    { name: 'organizationId', comparator: 'equals', value: 2 }
                ],
                request: ['id', 'objectId', 'name', 'sceneIndex', 'title', 'summary', 'setting', 'action', 'mood', 'blurb',
                    'description', 'imageObjectId', 'groupId'],
                recordCount: 50, cache: false
            }
        });
        const res = await expectStatus(r, 200, 'olio.pb.scene search');
        // The record serializer omits a zero sceneIndex on the wire (same convention as helpers/chapbook.js scenesByGroup).
        const rows = ((res && res.results) || []).map(s => ({ ...s, sceneIndex: s.sceneIndex || 0 }))
            .sort((a, b) => a.sceneIndex - b.sceneIndex);
        console.log(TAG + ' olio.pb.scene rows in group ' + groupId + ': ' + rows.length + ' '
            + JSON.stringify(rows.map(s => ({ objectId: s.objectId, sceneIndex: s.sceneIndex, title: s.title }))));
        return rows;
    }

    async function listCompositionsIn(groupId) {
        const r = await api.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.composition',
                fields: [
                    { name: 'groupId', comparator: 'equals', value: groupId },
                    { name: 'organizationId', comparator: 'equals', value: 2 }
                ],
                request: ['id', 'objectId', 'source', 'purpose', 'revision', 'userEdited', 'scene.objectId', 'scene.sceneIndex'],
                recordCount: 50, cache: false
            }
        });
        const res = await expectStatus(r, 200, 'olio.pb.composition search');
        return (res && res.results) || [];
    }

    async function readBookGroup(oid) {
        const sr = await api.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.book',
                fields: [{ name: 'objectId', comparator: 'equals', value: oid }],
                request: ['id', 'objectId', 'slug', 'groupId', 'groupPath'], recordCount: 1, cache: false
            }
        });
        const sres = await expectStatus(sr, 200, 'olio.pb.book search');
        const book = sres && sres.results && sres.results[0];
        expect(book && book.groupId, 'book ' + oid + ' not found: ' + JSON.stringify(sres).slice(0, 300)).toBeTruthy();
        return book;
    }

    test('an import with no PictureBook bound is a 200 that names why no compose job was queued', async () => {
        test.setTimeout(5 * 60 * 1000);
        const workObjectId = await createWorkNote('unbound');
        const r = await api.post(PB + '/' + workObjectId + '/create-from-scenes', {
            data: { schema: 'olio.pictureBookRequest', sceneList: IMPORT_SCENES, bookName: 'P4-4 Unbound Import ' + bookSlug },
            timeout: 240000
        });
        const out = await expectStatus(r, 200, 'create-from-scenes (no pb2BookObjectId)');
        console.log(TAG + ' create-from-scenes (unbound): ' + JSON.stringify(out).slice(0, 1200));
        expect(out.composeJobSkipped, JSON.stringify(out).slice(0, 400)).toBe('no PictureBook was bound to the import');
        expect(out.composeJob, 'nothing queued').toBeUndefined();
        expect(out.composeJobError).toBeUndefined();
        // The import's own result is returned unchanged around the compose fields.
        expect(out.bookObjectId, 'import bookObjectId').toBeTruthy();
        expect(Array.isArray(out.scenes) && out.scenes.length, 'persisted scenes: ' + JSON.stringify(out.scenes)).toBe(IMPORT_SCENES.length);
        expect(out.scenes.map(s => s.title)).toEqual(IMPORT_SCENES.map(s => s.title));
        for (const s of out.scenes) expect(s.objectId, 'scene note objectId: ' + JSON.stringify(s)).toBeTruthy();
    });

    test('an import bound to a V3 book writes its olio.pb.scene rows and queues the compose job to completion (PB_LLM_TESTS=1)', async ({ request }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set — live LLM compose job after import skipped (a skip, not a pass)');
        test.setTimeout(35 * 60 * 1000);
        const cfg = await ensureChatConfig(request, null);
        expect(cfg, 'ensureChatConfig returned no config').toBeTruthy();

        const slug = 'p44imp' + Date.now().toString(36);
        const created = await api.post(PB + '/chapter', { data: { slug, title: 'P4-4 Import Compose ' + slug }, timeout: 180000 });
        const cb = await expectStatus(created, 200, 'POST /chapter (import book)');
        const importBookOid = cb.bookObjectId;
        expect(importBookOid, 'POST /chapter returned no bookObjectId: ' + JSON.stringify(cb)).toBeTruthy();
        const book = await readBookGroup(importBookOid);
        console.log(TAG + ' import book ' + slug + ' objectId=' + importBookOid + ' groupId=' + book.groupId);
        expect((await listSceneRows(book.groupId)).length, 'a fresh book has no scene rows').toBe(0);

        try {
            const workObjectId = await createWorkNote('bound');
            const t0 = Date.now();
            const r = await api.post(PB + '/' + workObjectId + '/create-from-scenes', {
                data: {
                    schema: 'olio.pictureBookRequest', sceneList: IMPORT_SCENES, chatConfig: cfg,
                    bookName: 'P4-4 Import Compose ' + slug, pb2BookObjectId: importBookOid
                },
                timeout: 240000
            });
            const out = await expectStatus(r, 200, 'create-from-scenes (bound)');
            console.log(TAG + ' create-from-scenes (bound, ' + Math.round((Date.now() - t0) / 1000) + ' s): ' + JSON.stringify(out).slice(0, 1500));
            expect(out.composeJobError, 'compose job was not queued: ' + JSON.stringify(out.composeJobError)).toBeUndefined();
            expect(out.composeJobSkipped, 'compose job skipped: ' + out.composeJobSkipped).toBeUndefined();
            // pictureBookMeta.bookObjectId is the PB1 note-book the import always creates; the bound V3 book rides on pb2BookObjectId.
            expect(out.bookObjectId, 'import bookObjectId (PB1 note book)').toBeTruthy();
            expect(out.bookObjectId, 'the PB1 note book is not the V3 book').not.toBe(importBookOid);
            expect(out.pb2BookObjectId, 'meta pb2BookObjectId is the bound V3 book').toBe(importBookOid);
            expect(Array.isArray(out.scenes) && out.scenes.length, 'persisted scenes').toBe(IMPORT_SCENES.length);

            // The import-time dual write: one olio.pb.scene row per imported scene, in the book group, with the text.
            const rows = await listSceneRows(book.groupId);
            expect(rows.length, 'one olio.pb.scene row per imported scene').toBe(IMPORT_SCENES.length);
            for (let i = 0; i < IMPORT_SCENES.length; i++) {
                const row = rows[i];
                const src = IMPORT_SCENES[i];
                expect(row.sceneIndex, 'row ' + i + ' sceneIndex').toBe(i);
                expect(row.groupId).toBe(book.groupId);
                expect(row.title).toBe(src.title);
                expect(row.setting).toBe(src.setting);
                expect(row.action).toBe(src.action);
                expect(row.mood).toBe(src.mood);
                expect(row.blurb, 'blurb-only import: the blurb is the scene text').toBe(src.blurb);
                expect(row.description).toBe(src.blurb);
                expect(row.imageObjectId, 'no render yet').toBeFalsy();
            }

            // The compose job was queued from the import, over exactly those rows.
            const sub = out.composeJob;
            expect(sub, 'composeJob missing: ' + JSON.stringify(out).slice(0, 400)).toBeTruthy();
            expect(sub.submitted, JSON.stringify(sub)).toBe(true);
            expect(sub.kind).toBe('pb.compose');
            expect(sub.bookObjectId).toBe(importBookOid);
            expect(sub.sceneCount).toBe(IMPORT_SCENES.length);
            expect(sub.scenesToCompose).toBe(IMPORT_SCENES.length);
            expect(sub.scenesCurrent).toBe(0);
            expect(sub.quickStart).toBe(false);
            expect(sub.force).toBe(false);
            expect(sub.jobId, 'submit returned no jobId: ' + JSON.stringify(sub)).toBeTruthy();

            const job = await pollJob(sub.jobId, 'import compose', 30 * 60 * 1000);
            expect(job.status, 'import compose job ended ' + job.status + ': ' + (job.error || '') + ' ' + JSON.stringify(job).slice(0, 600)).toBe('completed');
            const res = job.result;
            console.log(TAG + ' import compose (' + Math.round((Date.now() - t0) / 1000) + ' s): ' + JSON.stringify(res).slice(0, 2500));
            expect(res.bookObjectId).toBe(importBookOid);
            expect(res.sceneCount).toBe(IMPORT_SCENES.length);
            expect(res.visited).toBe(IMPORT_SCENES.length);
            expect(res.cancelled).toBe(false);
            expect(res.hardFailures, 'hard failures: ' + JSON.stringify(res.scenes)).toBe(0);
            expect(res.composed + res.migrated, 'composed + migrated: ' + JSON.stringify(res)).toBe(IMPORT_SCENES.length);
            console.log(TAG + ' MEASURED import compose composed=' + res.composed + ' migrated=' + res.migrated + ' durationMs=' + res.durationMs);
            expect(res.scenes.map(s => s.sceneIndex), 'story order').toEqual(rows.map(s => s.sceneIndex));
            expect(res.scenes.map(s => s.sceneObjectId), 'the import-created rows, in order').toEqual(rows.map(s => s.objectId));
            expect(res.locationPassRan, 'the job ends with the whole-book location pass').toBe(true);

            const comps = await listCompositionsIn(book.groupId);
            expect(comps.length, 'one composition per imported scene: ' + JSON.stringify(comps)).toBe(IMPORT_SCENES.length);
            const byScene = new Map(comps.map(c => [c.scene && c.scene.objectId, c]));
            for (const s of res.scenes) {
                const c = byScene.get(s.sceneObjectId);
                expect(c, 'composition for scene ' + s.sceneIndex).toBeTruthy();
                expect(c.objectId).toBe(s.compositionObjectId);
                expect(c.revision).toBe(1);
                expect(c.userEdited).toBe(false);
            }

            // Compose-on-open after the import is a no-op (or re-queues only MIGRATED rows, which is cancelled here).
            const again = await api.post(PB + '/' + importBookOid + '/compose', { data: { chatConfigName: cfg } });
            if (res.migrated === 0) {
                const o = await expectStatus(again, 200, 'compose on the imported, composed book');
                expect(o.submitted).toBe(false);
                expect(o.reason).toBe('Every scene is composed');
            } else {
                const o = await expectStatus(again, 202, 'compose re-queues MIGRATED rows');
                expect(o.scenesToCompose).toBe(res.migrated);
                await expectStatus(await api.post(REST + '/job/' + o.jobId + '/cancel'), 200, 'cancel');
                await pollJob(o.jobId, 'import compose (cancelled)', 8 * 60 * 1000);
            }
        } finally {
            const d = await api.delete(PB + '/' + importBookOid + '/reset', { timeout: 120000 });
            console.log(TAG + ' DELETE /' + importBookOid + '/reset -> ' + d.status() + ' ' + (await d.text()).slice(0, 300));
        }
    });
});

// ────────────────────────────── LLM tier ──────────────────────────────
test.describe('compose job: story order, idempotent re-submit, compositions, per-scene re-compose (PB_LLM_TESTS=1)', () => {
    test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set — live LLM compose job skipped (a skip, not a pass)');

    test('scenes with settings are created in the book group as the shared user', async () => {
        for (const [idx, title, setting, action, mood] of SCENES) {
            const body = {
                schema: 'olio.pb.scene', name: 'Scene ' + bookSlug + ' ' + idx, groupId: bookGroupId, groupPath: bookGroupPath,
                book: { schema: 'olio.pb.book', id: bookId, objectId: bookObjectId }, sceneIndex: idx, title, setting, action, mood,
                description: title + ': ' + setting + '. ' + action + '.'
            };
            const r = await api.post(REST + '/model', { data: body });
            const created = await expectStatus(r, 200, 'POST olio.pb.scene ' + idx);
            expect(created && created.objectId, 'scene ' + idx + ' create returned no objectId: ' + JSON.stringify(created)).toBeTruthy();
            sceneIds.push(created.objectId);
        }
        const r = await api.get(PB + '/' + bookObjectId + '/pages');
        const pages = await expectStatus(r, 200, 'GET /pages');
        expect(Array.isArray(pages) && pages.length, 'GET /pages: ' + JSON.stringify(pages).slice(0, 300)).toBe(SCENES.length);
        expect((await listCompositions()).length, 'no composition before the job').toBe(0);
    });

    test('POST /compose is a 202 job; an immediate second submit reuses it; the result is in story order', async ({ request }) => {
        test.setTimeout(30 * 60 * 1000);
        chatConfigName = await ensureChatConfig(request, null);
        expect(chatConfigName, 'ensureChatConfig returned no config').toBeTruthy();

        const t0 = Date.now();
        let r = await api.post(PB + '/' + bookObjectId + '/compose', { data: { chatConfigName } });
        const sub = await expectStatus(r, 202, 'compose submit');
        console.log(TAG + ' compose submit: ' + JSON.stringify(sub));
        expect(sub.submitted, JSON.stringify(sub)).toBe(true);
        expect(sub.reused).toBe(false);
        expect(sub.kind).toBe('pb.compose');
        expect(sub.bookObjectId).toBe(bookObjectId);
        expect(sub.sceneCount).toBe(2);
        expect(sub.scenesToCompose).toBe(2);
        expect(sub.scenesCurrent).toBe(0);
        expect(sub.quickStart).toBe(false);
        expect(sub.force).toBe(false);
        expect(sub.jobId, 'submit returned no jobId: ' + JSON.stringify(sub)).toBeTruthy();
        expect(['queued', 'running']).toContain(String(sub.jobStatus));

        // The same principal submitting again while the job is in flight gets the same job back, not a second one.
        r = await api.post(PB + '/' + bookObjectId + '/compose', { data: { chatConfigName } });
        if (r.status() === 202) {
            const again = await expectStatus(r, 202, 'compose re-submit');
            expect(again.reused, 'second submit reuses the in-flight job: ' + JSON.stringify(again)).toBe(true);
            expect(again.jobId).toBe(sub.jobId);
        } else {
            // The first job already finished (a fast LLM): a no-op 200 or a fresh job are the only other answers.
            console.log(TAG + ' re-submit answered ' + r.status() + ' (first job already finished?) ' + (await r.text()).slice(0, 300));
        }

        // A synchronous per-scene compose is refused while the job holds the book.
        r = await api.post(PB + '/' + bookObjectId + '/scene/' + sceneIds[0] + '/compose', { data: { chatConfigName } });
        if (r.status() === 409) {
            expect(errMsg(await r.json())).toBe('A compose job is running for this book; wait for it to finish or cancel it first');
            console.log(TAG + ' per-scene compose refused while the job runs (409)');
        } else {
            console.log(TAG + ' per-scene compose during the job answered ' + r.status() + ' (job already finished?) ' + (await r.text()).slice(0, 300));
        }

        const job = await pollJob(sub.jobId, 'compose', 28 * 60 * 1000);
        expect(job.status, 'compose job ended ' + job.status + ': ' + (job.error || '') + ' ' + JSON.stringify(job).slice(0, 600)).toBe('completed');
        composeResult = job.result;
        expect(composeResult, 'completed job carries no result: ' + JSON.stringify(job).slice(0, 600)).toBeTruthy();
        console.log(TAG + ' compose (' + Math.round((Date.now() - t0) / 1000) + ' s): ' + JSON.stringify(composeResult).slice(0, 2500));

        const res = composeResult;
        expect(res.bookObjectId).toBe(bookObjectId);
        expect(res.slug).toBe(bookSlug);
        expect(res.quickStart).toBe(false);
        expect(res.force).toBe(false);
        expect(res.sceneCount).toBe(2);
        expect(res.visited).toBe(2);
        expect(res.cancelled).toBe(false);
        expect(res.interrupted).toBe(false);
        expect(res.hardFailures, 'hard failures: ' + JSON.stringify(res.scenes)).toBe(0);
        expect(res.current).toBe(0);
        expect(res.skippedUserEdited).toBe(0);
        expect(res.pagesSkipped).toBe(0);
        expect(res.quickStartRendered).toBe(0);
        // Every visited scene left with a composition: LLM-composed, or the MIGRATED fallback when the model's
        // reply could not be parsed twice. The split is measured and logged, never asserted.
        expect(res.composed + res.migrated, 'composed + migrated: ' + JSON.stringify(res)).toBe(2);
        console.log(TAG + ' MEASURED composed=' + res.composed + ' migrated=' + res.migrated + ' durationMs=' + res.durationMs);
        expect(res.lastSceneIndex).toBe(2);
        expect(res.locationPassRan, 'the job ends with the whole-book location pass').toBe(true);
        expect(res.locationPass, JSON.stringify(res.locationPass)).toBeTruthy();
        expect(res.locationPass.scenesConsidered).toBe(2);
        expect(res.locationPass.hardFailure, 'location pass: ' + JSON.stringify(res.locationPass.warnings)).toBe(false);

        expect(Array.isArray(res.scenes) && res.scenes.length).toBe(2);
        expect(res.scenes.map(s => s.sceneIndex), 'story order').toEqual([1, 2]);
        expect(res.scenes.map(s => s.sceneObjectId), 'the scenes created above, in order').toEqual(sceneIds);
        for (const s of res.scenes) {
            expect(['composed', 'migrated'], 'scene ' + s.sceneIndex + ': ' + JSON.stringify(s)).toContain(s.status);
            expect(s.compositionObjectId, 'scene ' + s.sceneIndex + ' has a composition: ' + JSON.stringify(s)).toBeTruthy();
            expect(['LLM', 'MIGRATED']).toContain(String(s.source).toUpperCase());
            expect(typeof s.elapsedMs).toBe('number');
        }
    });

    test('one olio.pb.composition per scene lives in the book group, revision 1, not user-edited', async () => {
        const rows = await listCompositions();
        expect(rows.length).toBe(2);
        const bySceneIndex = new Map(rows.map(c => [c.scene && c.scene.sceneIndex, c]));
        for (const s of composeResult.scenes) {
            const c = bySceneIndex.get(s.sceneIndex);
            expect(c, 'composition for scene ' + s.sceneIndex + ': ' + JSON.stringify(rows)).toBeTruthy();
            expect(c.objectId).toBe(s.compositionObjectId);
            expect(c.scene.objectId).toBe(s.sceneObjectId);
            expect(String(c.source).toUpperCase()).toBe(String(s.source).toUpperCase());
            expect(c.userEdited).toBe(false);
            expect(c.revision).toBe(1);
            expect(String(c.purpose).toUpperCase()).toBe('SCENE');
            expect(c.settingText, 'settingText carried from the scene: ' + JSON.stringify(c)).toBeTruthy();
        }
    });

    test('compose-on-open is idempotent: a composed book is a 200 no-op; a MIGRATED row is re-picked', async () => {
        test.setTimeout(10 * 60 * 1000);
        const r = await api.post(PB + '/' + bookObjectId + '/compose', { data: { chatConfigName } });
        if (composeResult.migrated === 0) {
            const out = await expectStatus(r, 200, 'compose on a composed book');
            console.log(TAG + ' compose on a composed book: ' + JSON.stringify(out));
            expect(out.submitted).toBe(false);
            expect(out.sceneCount).toBe(2);
            expect(out.scenesToCompose).toBe(0);
            expect(out.scenesCurrent).toBe(2);
            expect(out.reason).toBe('Every scene is composed');
        } else {
            // A MIGRATED fallback still counts as to-compose, so the open re-queues exactly those scenes.
            const out = await expectStatus(r, 202, 'compose on a partly MIGRATED book');
            console.log(TAG + ' compose re-queued the MIGRATED scenes: ' + JSON.stringify(out));
            expect(out.submitted).toBe(true);
            expect(out.scenesToCompose).toBe(composeResult.migrated);
            expect(out.scenesCurrent).toBe(composeResult.composed);
            const c = await expectStatus(await api.post(REST + '/job/' + out.jobId + '/cancel'), 200, 'cancel');
            console.log(TAG + ' cancel: ' + JSON.stringify(c));
            const job = await pollJob(out.jobId, 'compose (cancelled)', 8 * 60 * 1000);
            console.log(TAG + ' cancelled job ended ' + job.status + ' result=' + JSON.stringify(job.result).slice(0, 600));
        }
    });

    test('POST /scene/{scene}/compose with force re-composes one scene synchronously and bumps the revision', async () => {
        test.setTimeout(15 * 60 * 1000);
        const before = (await listCompositions()).find(c => c.scene && c.scene.objectId === sceneIds[0]);
        expect(before, 'scene 1 composition before the forced re-compose').toBeTruthy();
        const t0 = Date.now();
        const r = await api.post(PB + '/' + bookObjectId + '/scene/' + sceneIds[0] + '/compose', { data: { force: true, chatConfigName }, timeout: 14 * 60 * 1000 });
        const out = await expectStatus(r, 200, 'scene compose force');
        console.log(TAG + ' scene compose (' + Math.round((Date.now() - t0) / 1000) + ' s): ' + JSON.stringify(out).slice(0, 2000));
        expect(out.bookObjectId).toBe(bookObjectId);
        expect(out.sceneObjectId).toBe(sceneIds[0]);
        expect(out.sceneIndex).toBe(1);
        expect(out.cancelled).toBe(false);
        expect(out.skippedUserEdited).toBe(false);
        expect(out.hardFailure, 'hard failure: ' + out.failureReason).toBe(false);
        expect(out.written, JSON.stringify(out).slice(0, 400)).toBe(true);
        expect(out.compositionObjectId).toBeTruthy();
        expect(out.composition && out.composition.objectId).toBe(out.compositionObjectId);
        expect(['LLM', 'MIGRATED']).toContain(String(out.source).toUpperCase());
        console.log(TAG + ' MEASURED scene compose source=' + out.source + ' migratedFallback=' + out.migratedFallback
            + ' castTier=' + out.castTier + ' actors=' + (out.actors || []).length + ' elements=' + (out.elements || []).length);

        // Forced: the scene's composition row is kept (same objectId), its actor/element rows are rebuilt and the
        // revision is bumped - never a second composition for the scene.
        const rows = await listCompositions();
        expect(rows.length, 'still one composition per scene: ' + JSON.stringify(rows)).toBe(2);
        const mine = rows.filter(c => c.scene && c.scene.objectId === sceneIds[0]);
        expect(mine.length).toBe(1);
        expect(mine[0].objectId).toBe(before.objectId);
        expect(out.compositionObjectId).toBe(before.objectId);
        expect(mine[0].revision, 'revision bumped from ' + before.revision).toBeGreaterThan(before.revision);
        expect(mine[0].userEdited).toBe(false);
    });
});

// ────────────────────────────── SD tier ──────────────────────────────
test.describe('Quick start: a DRAFT render per composed scene (PB_SD_TESTS=1)', () => {
    test.skip(!SD_ENABLED, 'PB_SD_TESTS=1 not set — live SD Quick start skipped (a skip, not a pass)');
    test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set — Quick start needs the LLM tier\'s scenes (a skip, not a pass)');

    test('POST /compose {quickStart, force} renders every composed scene once; the bytes are a real image', async () => {
        test.setTimeout(60 * 60 * 1000);
        const t0 = Date.now();
        let r = await api.post(PB + '/' + bookObjectId + '/compose', { data: { quickStart: true, force: true, chatConfigName } });
        const sub = await expectStatus(r, 202, 'quick start submit');
        console.log(TAG + ' quick start submit: ' + JSON.stringify(sub));
        expect(sub.submitted).toBe(true);
        expect(sub.quickStart).toBe(true);
        expect(sub.force).toBe(true);
        expect(sub.scenesToCompose).toBe(2);

        const job = await pollJob(sub.jobId, 'quick start', 58 * 60 * 1000);
        expect(job.status, 'quick start job ended ' + job.status + ': ' + (job.error || '') + ' ' + JSON.stringify(job).slice(0, 600)).toBe('completed');
        const res = job.result;
        console.log(TAG + ' quick start (' + Math.round((Date.now() - t0) / 1000) + ' s): ' + JSON.stringify(res).slice(0, 3000));
        expect(res.quickStart).toBe(true);
        expect(res.visited).toBe(2);
        expect(res.cancelled).toBe(false);
        expect(res.hardFailures, JSON.stringify(res.scenes)).toBe(0);
        expect(res.composed + res.migrated).toBe(2);
        expect(res.quickStartFailed, 'draft render failures: ' + JSON.stringify(res.scenes.map(s => s.draftRenderError))).toBe(0);
        expect(res.quickStartRendered, 'one DRAFT per composed scene').toBe(res.composed + res.migrated);
        expect(res.locationPassRan).toBe(true);

        let n = 0;
        for (const s of res.scenes) {
            expect(s.draftRender, 'scene ' + s.sceneIndex + ' draftRender: ' + JSON.stringify(s)).toBeTruthy();
            expect(s.draftRender.artifactObjectId, 'scene ' + s.sceneIndex + ' artifact').toBeTruthy();
            expect(s.draftRender.nodeObjectId).toBeTruthy();
            expect(s.draftRender.runObjectId).toBeTruthy();
            expect(typeof s.draftRenderMs).toBe('number');
            console.log(TAG + ' scene ' + s.sceneIndex + ' draft: ' + JSON.stringify(s.draftRender) + ' in ' + s.draftRenderMs + ' ms');
            const art = await expectStatus(await api.get(PB + '/' + bookObjectId + '/artifact/' + s.draftRender.artifactObjectId), 200, 'GET artifact');
            expect(art.dataObjectId, 'artifact has bytes: ' + JSON.stringify(art)).toBeTruthy();
            expect(String(art.artifactType).toUpperCase()).toBe('IMAGE');
            await fetchImage(art.dataObjectId, 'p44-quickstart-scene' + s.sceneIndex);
            n++;
        }
        expect(n).toBe(2);
    });
});
