/**
 * PictureBook V3 Phase 3 — location library routes (P3-4), proven against the live stack as the shared
 * non-admin user. API-only (the Playwright `request` fixture); the Locations tab (P3-7) extends this file
 * with UI steps rather than inventing a second fixture.
 *
 * Routes under REST /olio/picture-book (PictureBookService, PbServiceFacade, PbLocationUtil):
 *   GET|POST /{book}/locations            GET|PATCH|DELETE /{book}/location/{id}
 *   POST /{book}/location/{id}/variants   POST /{book}/location/{id}/lock        POST /{book}/location/{id}/render
 *   POST /{book}/locations/propose        GET /{book}/locations/proposed?status=
 *   POST /{book}/locations/proposed/{pid}/accept|reject                          POST /{book}/locations/merge
 *
 * Tiers (a gated skip is a skip, never a pass):
 *   default            — CRUD, variants, lock/unlock 409s, merge, delete, propose on a scene-less book,
 *                        malformed bodies. No LLM, no SD.
 *   PB_LLM_TESTS=1     — scenes with settings → POST /locations/propose (live LLM through the shared user's
 *                        e2e-chapbook-llm config) → accept / reject; the §30 exit criterion "scenes 3/7/12
 *                        share one tavern".
 *   PB_SD_TESTS=1      — POST .../render → 202 job → /rest/job poll → PNG bytes from the MediaServlet →
 *                        approve pins approvedRender → lock refuses render/approve/reject → variant render.
 *   PB_LOC_DB_PROOF=1  — §7.2 exit criterion "deleting the world removes both": reads the stack DB
 *                        (docker exec am7-pg psql, READ ONLY) before and after DELETE /{book}/reset.
 *
 * Run (Windows / Docker stack — 127.0.0.1, never localhost):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_LLM_TESTS=1 PB_SD_TESTS=1 PB_LOC_DB_PROOF=1 \
 *     npx playwright test e2e/pictureBookLocations.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const DB_PROOF_ENABLED = process.env.PB_LOC_DB_PROOF === '1';
const TAG = '[pictureBookLocations]';

// Settings from TestPbLocationPassLive: three tavern scenes (3/7/12), two scriptorium, harbour, hill, one
// scene without a setting (skipped by the pass).
const SCENES = [
    [1, 'Dawn on the Quay', 'The harbour road at dawn, gulls over the fishing boats'],
    [2, 'The Copyist', 'A cramped scriptorium in the abbey, candles guttering'],
    [3, 'Arrival', 'The Gilded Tankard, a low-beamed tavern by the quay'],
    [4, 'After Midnight', 'The abbey scriptorium, the same candlelit desks after midnight'],
    [5, 'Interlude', null],
    [6, 'The Road Out', 'The windswept hill road above the town, dusk'],
    [7, 'The Taproom', 'Inside the Gilded Tankard: the taproom, ale-stained tables, the fire low'],
    [12, 'Festival Night', "Back at the Gilded Tankard, the tavern's taproom crowded for the festival"]
];

test.describe.configure({ mode: 'serial' });

let api;               // one logged-in APIRequestContext for the whole file (first session wins per context)
let bookObjectId;
let bookSlug;
let bookId;
let bookGroupId;
let bookGroupPath;
let worldObjectId;
let bookReset = false;

// Shared state across the serial tests
let tavern;            // { objectId, poiObjectId, name }
let variantObjectId;
let locationArtifactObjectId;
let locationDataObjectId;

async function errOf(resp) {
    const t = await resp.text();
    try {
        const j = JSON.parse(t);
        if (j && typeof j.message === 'string') return j.message;
        if (j && typeof j.error === 'string') return j.error;
        return t;
    } catch (e) {
        return t;
    }
}

async function expectStatus(resp, status, what) {
    const body = await resp.text();
    expect(resp.status(), what + ': expected ' + status + ' got ' + resp.status() + ' ' + body.slice(0, 400)).toBe(status);
    try { return JSON.parse(body); } catch (e) { return body; }
}

function errMsg(body) {
    if (body && typeof body === 'object') return String(body.message || body.error || JSON.stringify(body));
    return String(body);
}

async function getLocation(oid) {
    const r = await api.get(PB + '/' + bookObjectId + '/location/' + oid);
    return expectStatus(r, 200, 'GET /location/' + oid);
}

async function listLocations() {
    const r = await api.get(PB + '/' + bookObjectId + '/locations');
    return expectStatus(r, 200, 'GET /locations');
}

async function createLocation(body) {
    const r = await api.post(PB + '/' + bookObjectId + '/locations', { data: body });
    return expectStatus(r, 200, 'POST /locations ' + JSON.stringify(body));
}

async function setLock(oid, locked) {
    const r = await api.post(PB + '/' + bookObjectId + '/location/' + oid + '/lock', { data: { locked } });
    const b = await expectStatus(r, 200, 'POST /lock ' + locked);
    expect(b.locked, 'lock echo').toBe(locked);
    return b;
}

function psql(sql) {
    // READ ONLY against the Docker stack's own database (am7-pg / am72db / am7user) — never the local am72db.
    const out = execSync('docker exec am7-pg psql -U am7user -d am72db -At -F "|" -c "' + sql.replace(/"/g, '\\"') + '"',
        { encoding: 'utf8', env: Object.assign({}, process.env, { MSYS_NO_PATHCONV: '1' }) });
    return out.trim();
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

    bookSlug = 'p34loc' + Date.now().toString(36);
    const created = await api.post(PB + '/chapter', { data: { slug: bookSlug, title: 'P3-4 Locations ' + bookSlug }, timeout: 180000 });
    const cb = await expectStatus(created, 200, 'POST /chapter');
    bookObjectId = cb.bookObjectId;
    expect(bookObjectId, 'POST /chapter returned no bookObjectId: ' + JSON.stringify(cb)).toBeTruthy();

    // The book row (its numeric id and group) — scenes live in the book's own group (PbBookUtil.bookGroupPath).
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
test.describe('location library CRUD (default tier)', () => {

    test('a fresh book lists no locations and names its world', async () => {
        const list = await listLocations();
        expect(list.bookObjectId).toBe(bookObjectId);
        expect(list.worldObjectId, 'worldObjectId: ' + JSON.stringify(list)).toBeTruthy();
        worldObjectId = list.worldObjectId;
        // JSONUtil.exportObject is Include.NON_EMPTY: an empty list is absent on the wire, not [].
        expect((list.locations || []).length, 'fresh book has no locations: ' + JSON.stringify(list)).toBe(0);
    });

    test('POST /locations creates a location with a map-free POI in the book world', async () => {
        const loc = await createLocation({ name: 'The Drowned Anchor', description: 'A low tavern by the quay', kind: 'interior', aliases: ['the Anchor'] });
        expect(loc.objectId).toBeTruthy();
        expect(loc.name).toBe('The Drowned Anchor');
        expect(loc.description).toBe('A low tavern by the quay');
        expect(String(loc.kind).toLowerCase()).toBe('interior');
        expect(loc.aliases).toEqual(['the Anchor']);
        expect(loc.locked).toBe(false);
        expect(loc.poiObjectId, 'the §7.2 POI link: ' + JSON.stringify(loc)).toBeTruthy();
        expect(loc.worldObjectId).toBe(worldObjectId);
        expect(loc.approvedRenderObjectId == null, 'no approved render yet').toBe(true);
        expect(loc.node == null, 'no LOCATION node until the first render').toBe(true);
        expect(loc.variants || []).toEqual([]);
        tavern = { objectId: loc.objectId, poiObjectId: loc.poiObjectId, name: loc.name };

        // Creating the same name again resolves to the SAME record (getCreateLocation), no duplicate.
        const again = await createLocation({ name: 'the drowned anchor' });
        expect(again.objectId, 'same name resolves to the existing location').toBe(loc.objectId);
        // ...and so does one of its aliases.
        const byAlias = await createLocation({ name: 'The Anchor' });
        expect(byAlias.objectId, 'an alias resolves to the existing location').toBe(loc.objectId);
        expect((await listLocations()).locations.length).toBe(1);
    });

    test('POST /locations validation: name required, unknown kind, malformed body', async () => {
        let r = await api.post(PB + '/' + bookObjectId + '/locations', { data: { description: 'no name' } });
        expect(errMsg(await expectStatus(r, 400, 'no name'))).toBe('name is required');
        r = await api.post(PB + '/' + bookObjectId + '/locations', { data: { name: 'Nowhere', kind: 'underwater' } });
        expect(errMsg(await expectStatus(r, 400, 'bad kind'))).toMatch(/kind/i);
        r = await api.post(PB + '/' + bookObjectId + '/locations', { data: '[1,2]', headers: { 'Content-Type': 'application/json' } });
        expect(errMsg(await expectStatus(r, 400, 'malformed body'))).toBe('Malformed request body');
    });

    test('GET /location/{id} and PATCH of the editable fields; name is not editable', async () => {
        const got = await getLocation(tavern.objectId);
        expect(got.objectId).toBe(tavern.objectId);
        expect(got.poiObjectId).toBe(tavern.poiObjectId);

        let r = await api.patch(PB + '/' + bookObjectId + '/location/' + tavern.objectId,
            { data: { description: 'A low tavern by the quay, lamplit', kind: 'mixed', aliases: ['the Anchor', 'Anchor taproom'] } });
        const patched = await expectStatus(r, 200, 'PATCH editable');
        expect(patched.description).toBe('A low tavern by the quay, lamplit');
        expect(String(patched.kind).toLowerCase()).toBe('mixed');
        expect(patched.aliases).toEqual(['the Anchor', 'Anchor taproom']);

        r = await api.patch(PB + '/' + bookObjectId + '/location/' + tavern.objectId, { data: { name: 'Renamed' } });
        expect(errMsg(await expectStatus(r, 400, 'PATCH name'))).toMatch(/not editable/);
        r = await api.patch(PB + '/' + bookObjectId + '/location/' + tavern.objectId, { data: {} });
        expect(errMsg(await expectStatus(r, 400, 'PATCH empty'))).toBe('Nothing to change');
        r = await api.patch(PB + '/' + bookObjectId + '/location/' + tavern.objectId, { data: { aliases: 'not-a-list' } });
        expect(errMsg(await expectStatus(r, 400, 'PATCH aliases string'))).toMatch(/aliases must be a list/);

        r = await api.get(PB + '/' + bookObjectId + '/location/00000000-0000-0000-0000-000000000000');
        expect(errMsg(await expectStatus(r, 404, 'GET unknown'))).toMatch(/not found/i);
    });

    test('POST .../variants: default name from the deltas, duplicate is 409, no delta is 400', async () => {
        let r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/variants', { data: { timeOfDay: 'dusk', weather: 'rain' } });
        const v = await expectStatus(r, 200, 'POST variants');
        expect(v.objectId).toBeTruthy();
        expect(v.name).toBe('The Drowned Anchor - dusk, rain');
        expect(v.locationObjectId).toBe(tavern.objectId);
        expect(v.timeOfDay).toBe('dusk');
        expect(v.weather).toBe('rain');
        expect(v.node == null).toBe(true);
        variantObjectId = v.objectId;

        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/variants', { data: { timeOfDay: 'dusk', weather: 'rain' } });
        expect(errMsg(await expectStatus(r, 409, 'duplicate variant'))).toMatch(/already has a variant named/);
        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/variants', { data: {} });
        expect(errMsg(await expectStatus(r, 400, 'no delta'))).toMatch(/at least one of timeOfDay/);

        const got = await getLocation(tavern.objectId);
        expect(got.variants.length).toBe(1);
        expect(got.variants[0].objectId).toBe(variantObjectId);
    });

    test('lock refuses edit / variant / delete / merge with 409 and names the way out; unlock restores', async () => {
        await setLock(tavern.objectId, true);
        expect((await getLocation(tavern.objectId)).locked).toBe(true);

        let r = await api.patch(PB + '/' + bookObjectId + '/location/' + tavern.objectId, { data: { description: 'x' } });
        expect(errMsg(await expectStatus(r, 409, 'PATCH while locked'))).toBe('Location The Drowned Anchor is locked; unlock it to edit it');
        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/variants', { data: { season: 'winter' } });
        expect(errMsg(await expectStatus(r, 409, 'variant while locked'))).toBe('Location The Drowned Anchor is locked; unlock it to add a variant');
        r = await api.delete(PB + '/' + bookObjectId + '/location/' + tavern.objectId);
        expect(errMsg(await expectStatus(r, 409, 'delete while locked'))).toBe('Location The Drowned Anchor is locked; unlock it to delete it');

        // Locking again is idempotent and never refused by the lock itself.
        await setLock(tavern.objectId, true);
        // The body must be a JSON boolean.
        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/lock', { data: { locked: 'yes' } });
        expect(errMsg(await expectStatus(r, 400, 'lock non-boolean'))).toBe('locked must be a JSON boolean');

        await setLock(tavern.objectId, false);
        expect((await getLocation(tavern.objectId)).locked).toBe(false);
        r = await api.patch(PB + '/' + bookObjectId + '/location/' + tavern.objectId, { data: { description: 'A low tavern by the quay, lamplit' } });
        await expectStatus(r, 200, 'PATCH after unlock');
    });

    test('POST /locations/merge folds the duplicate into the survivor', async () => {
        const dup = await createLocation({ name: 'The Drowned Anchor Inn', aliases: ['Anchor Inn'] });
        expect(dup.objectId).not.toBe(tavern.objectId);
        expect((await listLocations()).locations.length).toBe(2);

        let r = await api.post(PB + '/' + bookObjectId + '/locations/merge', { data: { duplicateObjectId: dup.objectId, survivorObjectId: dup.objectId } });
        expect(errMsg(await expectStatus(r, 400, 'merge into itself'))).toBe('A location cannot be merged into itself');
        r = await api.post(PB + '/' + bookObjectId + '/locations/merge', { data: { duplicateObjectId: dup.objectId } });
        expect(errMsg(await expectStatus(r, 400, 'merge missing survivor'))).toBe('duplicateObjectId and survivorObjectId are required');

        r = await api.post(PB + '/' + bookObjectId + '/locations/merge', { data: { duplicateObjectId: dup.objectId, survivorObjectId: tavern.objectId } });
        const merged = await expectStatus(r, 200, 'merge');
        expect(merged.merged).toBe(true);
        expect(merged.survivor.objectId).toBe(tavern.objectId);
        const aliases = (merged.survivor.aliases || []).map(a => a.toLowerCase());
        expect(aliases, 'duplicate name folded into the survivor aliases: ' + JSON.stringify(aliases)).toContain('the drowned anchor inn');
        expect(aliases, 'duplicate aliases folded too').toContain('anchor inn');

        const list = await listLocations();
        expect(list.locations.length, 'duplicate gone from the list').toBe(1);
        expect(list.locations[0].objectId).toBe(tavern.objectId);
        r = await api.get(PB + '/' + bookObjectId + '/location/' + dup.objectId);
        await expectStatus(r, 404, 'merged duplicate is gone');
    });

    test('DELETE /location/{id} removes the location, its variants and its POI (audit steps)', async () => {
        const hill = await createLocation({ name: 'Windswept Hill', kind: 'exterior' });
        let r = await api.post(PB + '/' + bookObjectId + '/location/' + hill.objectId + '/variants', { data: { season: 'winter' } });
        await expectStatus(r, 200, 'hill variant');
        r = await api.delete(PB + '/' + bookObjectId + '/location/' + hill.objectId);
        const del = await expectStatus(r, 200, 'DELETE hill');
        expect(del.deleted, JSON.stringify(del)).toBe(true);
        expect(del.locationObjectId).toBe(hill.objectId);
        // DeleteResult.steps is a list of {step, model, count, ok, reason?} maps.
        const stepNames = (del.steps || []).map(s => s.step);
        expect(stepNames, 'delete audit: ' + JSON.stringify(del.steps)).toEqual(expect.arrayContaining(['variants', 'poi', 'location']));
        const poiStep = del.steps.find(s => s.step === 'poi');
        expect(poiStep.ok, 'poi step: ' + JSON.stringify(poiStep)).toBe(true);
        expect(poiStep.count, 'the map-free POI is removed with the location').toBe(1);
        r = await api.get(PB + '/' + bookObjectId + '/location/' + hill.objectId);
        await expectStatus(r, 404, 'deleted hill is gone');
        expect((await listLocations()).locations.length).toBe(1);
    });

    test('propose on a scene-less book is a no-op with a warning; proposed list is empty; unknown status is 400', async () => {
        let r = await api.get(PB + '/' + bookObjectId + '/locations/proposed');
        const empty = await expectStatus(r, 200, 'GET proposed');
        expect(empty.proposals || []).toEqual([]);
        r = await api.get(PB + '/' + bookObjectId + '/locations/proposed?status=bogus');
        // The body names the accepted values and never echoes the caller's text.
        expect(errMsg(await expectStatus(r, 400, 'bad status'))).toMatch(/^Unknown proposal status/);

        r = await api.post(PB + '/' + bookObjectId + '/locations/propose', { data: {} });
        const pass = await expectStatus(r, 200, 'propose (no scenes)');
        expect(pass.scenesConsidered).toBe(0);
        expect(pass.proposed).toBe(0);
        expect(pass.hardFailure).toBe(false);
        expect(pass.warnings, JSON.stringify(pass)).toContain('No scene of ' + bookSlug + ' has a setting to cluster');
        expect(pass.proposals || []).toEqual([]);
    });

    test('routes 404 for a book that does not exist', async () => {
        const r = await api.get(PB + '/00000000-0000-0000-0000-000000000000/locations');
        await expectStatus(r, 404, 'unknown book');
    });
});

// ────────────────────────────── LLM tier ──────────────────────────────
test.describe('location pass: propose / accept / reject (PB_LLM_TESTS=1)', () => {
    test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set — live LLM location pass skipped (a skip, not a pass)');

    let chatConfigName;
    let proposals;
    let tavernProposal;

    test('scenes with settings are created in the book group as the shared user', async () => {
        for (const [idx, title, setting] of SCENES) {
            const body = {
                schema: 'olio.pb.scene', name: 'Scene ' + bookSlug + ' ' + idx, groupId: bookGroupId, groupPath: bookGroupPath,
                book: { schema: 'olio.pb.book', id: bookId, objectId: bookObjectId }, sceneIndex: idx, title
            };
            if (setting) body.setting = setting;
            const r = await api.post(REST + '/model', { data: body });
            const created = await expectStatus(r, 200, 'POST olio.pb.scene ' + idx);
            expect(created && created.objectId, 'scene ' + idx + ' create returned no objectId: ' + JSON.stringify(created)).toBeTruthy();
        }
        // GET /{book}/scenes is the PB1 data.note meta list and returns [] for olio.pb.scene rows;
        // GET /{book}/pages is the olio.pb.scene page view (plain array of {objectId, sceneIndex, title, ...}).
        const r = await api.get(PB + '/' + bookObjectId + '/pages');
        const pages = await expectStatus(r, 200, 'GET /pages');
        expect(Array.isArray(pages), 'GET /pages: ' + JSON.stringify(pages).slice(0, 300)).toBe(true);
        expect(pages.length, 'GET /pages: ' + JSON.stringify(pages).slice(0, 300)).toBe(SCENES.length);
    });

    test('POST /locations/propose clusters the settings through the live LLM', async ({ request }) => {
        test.setTimeout(25 * 60 * 1000);
        chatConfigName = await ensureChatConfig(request, null);
        expect(chatConfigName, 'ensureChatConfig returned no config').toBeTruthy();

        const t0 = Date.now();
        const r = await api.post(PB + '/' + bookObjectId + '/locations/propose', { data: { chatConfigName }, timeout: 24 * 60 * 1000 });
        const pass = await expectStatus(r, 200, 'propose');
        console.log(TAG + ' propose (' + Math.round((Date.now() - t0) / 1000) + ' s): ' + JSON.stringify(pass).slice(0, 1500));
        expect(pass.hardFailure, 'hard failure: ' + JSON.stringify(pass.warnings)).toBe(false);
        expect(pass.scenesConsidered).toBe(7);
        expect(pass.scenesSkipped).toBe(1);
        expect(pass.chunksParsed).toBe(pass.chunks);
        expect(pass.proposed, 'at least tavern + scriptorium proposed').toBeGreaterThanOrEqual(2);
        proposals = pass.proposals;
        expect(Array.isArray(proposals) && proposals.length).toBeTruthy();

        const listed = await expectStatus(await api.get(PB + '/' + bookObjectId + '/locations/proposed?status=proposed'), 200, 'GET proposed');
        const listedProposals = listed.proposals || [];
        expect(listedProposals.length).toBe(proposals.length);
        for (const p of listedProposals) {
            expect(String(p.proposalStatus).toUpperCase()).toBe('PROPOSED');
            expect(Array.isArray(p.sceneObjectIds)).toBe(true);
        }
        // The tavern cluster: the proposal that carries scenes 3, 7 and 12 (the §30 exit criterion).
        const sceneIdx = new Map();
        const pages = await expectStatus(await api.get(PB + '/' + bookObjectId + '/pages'), 200, 'GET /pages');
        for (const s of (Array.isArray(pages) ? pages : [])) sceneIdx.set(s.objectId, s.sceneIndex);
        tavernProposal = listedProposals.find(p => {
            const idxs = (p.sceneObjectIds || []).map(o => sceneIdx.get(o));
            return idxs.includes(3) && idxs.includes(7) && idxs.includes(12);
        });
        expect(tavernProposal, 'one proposal must cluster scenes 3/7/12: ' + JSON.stringify(listedProposals.map(p => ({ n: p.proposedName, s: (p.sceneObjectIds || []).map(o => sceneIdx.get(o)) })))).toBeTruthy();
        console.log(TAG + ' tavern proposal: ' + JSON.stringify(tavernProposal));
    });

    test('accept re-points scenes 3/7/12 to one location; a second accept is 409; reject works once', async () => {
        test.setTimeout(5 * 60 * 1000);
        let r = await api.post(PB + '/' + bookObjectId + '/locations/proposed/' + tavernProposal.objectId + '/accept', { data: { overwrite: false } });
        const acc = await expectStatus(r, 200, 'accept tavern');
        console.log(TAG + ' accept: ' + JSON.stringify(acc).slice(0, 800));
        expect(acc.locationObjectId).toBeTruthy();
        expect(acc.scenesRepointed).toBe(tavernProposal.sceneObjectIds.length);
        expect(String(acc.proposal.proposalStatus).toUpperCase()).toBe('ACCEPTED');
        const loc = await getLocation(acc.locationObjectId);
        expect(loc.poiObjectId, 'accepted location has its POI').toBeTruthy();
        const idxs = (loc.usedByScenes || []).map(u => u.sceneIndex).sort((a, b) => a - b);
        expect(idxs, 'usedByScenes').toEqual(expect.arrayContaining([3, 7, 12]));
        // No workflow exists yet on this book, so accept does not materialize a LOCATION node.
        expect(acc.nodeObjectId == null, 'no node without a workflow: ' + JSON.stringify(acc)).toBe(true);

        r = await api.post(PB + '/' + bookObjectId + '/locations/proposed/' + tavernProposal.objectId + '/accept', { data: {} });
        expect(errMsg(await expectStatus(r, 409, 'accept twice'))).toMatch(/PROPOSED|already/i);

        const other = proposals.find(p => p.objectId !== tavernProposal.objectId);
        if (other) {
            r = await api.post(PB + '/' + bookObjectId + '/locations/proposed/' + other.objectId + '/reject', { data: { reason: 'e2e reject' } });
            const rej = await expectStatus(r, 200, 'reject');
            expect(String(rej.proposalStatus).toUpperCase()).toBe('REJECTED');
            r = await api.post(PB + '/' + bookObjectId + '/locations/proposed/' + other.objectId + '/reject', { data: {} });
            await expectStatus(r, 409, 'reject twice');
        }
        const rejected = await expectStatus(await api.get(PB + '/' + bookObjectId + '/locations/proposed?status=rejected'), 200, 'GET rejected');
        expect((rejected.proposals || []).length).toBe(other ? 1 : 0);

        // The accepted location cannot be deleted while scenes still point at it.
        r = await api.delete(PB + '/' + bookObjectId + '/location/' + acc.locationObjectId);
        expect(errMsg(await expectStatus(r, 409, 'delete in use'))).toMatch(/still the location of \d+ scene/);
    });
});

// ────────────────────────────── SD tier ──────────────────────────────
test.describe('LOCATION node render, approve pins, lock (PB_SD_TESTS=1)', () => {
    test.skip(!SD_ENABLED, 'PB_SD_TESTS=1 not set — live SD location render skipped (a skip, not a pass)');

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
        const out = path.join(OUT_DIR, fileName + (isPng ? '.png' : '.jpg'));
        fs.writeFileSync(out, buf);
        console.log(TAG + ' wrote ' + buf.length + ' bytes to ' + out + ' magic=' + buf.slice(0, 4).toString('hex') + ' ' + dataRec.contentType);
        return out;
    }

    test('POST .../render is a 202 job; the LOCATION node ends with a real image', async () => {
        test.setTimeout(30 * 60 * 1000);
        const t0 = Date.now();
        let r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/render', { data: {} });
        const sub = await expectStatus(r, 202, 'render submit');
        console.log(TAG + ' render submit: ' + JSON.stringify(sub).slice(0, 800));
        expect(sub.jobId).toBeTruthy();
        expect(sub.runObjectId).toBeTruthy();
        expect(sub.nodeObjectId, 'node materialized on first render').toBeTruthy();

        // A second submit while the first is in flight is refused (409) — the node is claimed.
        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/render', { data: {} });
        if (r.status() === 409) console.log(TAG + ' concurrent render refused: ' + await errOf(r));
        else console.log(TAG + ' concurrent render answered ' + r.status() + ' (first job already finished?) ' + (await r.text()).slice(0, 200));

        const job = await pollJob(sub.jobId, 'location', 25 * 60 * 1000);
        console.log(TAG + ' location render job terminal after ' + Math.round((Date.now() - t0) / 1000) + ' s: ' + JSON.stringify(job).slice(0, 1200));
        expect(job.status, 'job error: ' + (job.error || '')).toBe('completed');

        const loc = await getLocation(tavern.objectId);
        expect(loc.node, 'node DTO: ' + JSON.stringify(loc).slice(0, 600)).toBeTruthy();
        expect(loc.node.objectId).toBe(sub.nodeObjectId);
        // PbPipelineUtil.locationHandle: "location@" + shortRef(objectId)
        expect(loc.node.handle, 'location handle').toMatch(/^location@/);
        expect(String(loc.node.storedStatus).toUpperCase(), 'storedStatus ' + loc.node.lastError).toMatch(/READY|DONE/);
        expect(Array.isArray(loc.node.chain) && loc.node.chain.length, 'chain: ' + JSON.stringify(loc.node)).toBeTruthy();
        const art = loc.node.chain[0];
        expect(art.dataObjectId, 'artifact has bytes: ' + JSON.stringify(art)).toBeTruthy();
        locationArtifactObjectId = art.objectId;
        locationDataObjectId = art.dataObjectId;
        await fetchImage(locationDataObjectId, 'p34-location-render');
        // Nothing is approved automatically (autoApprove is off on a fresh book).
        expect(loc.approvedRenderObjectId == null, 'not auto-approved: ' + loc.approvedRenderObjectId).toBe(true);
    });

    test('approve pins approvedRender; lock refuses render/approve/reject; unlock + reject clears it', async () => {
        test.setTimeout(10 * 60 * 1000);
        let r = await api.put(PB + '/' + bookObjectId + '/artifact/' + locationArtifactObjectId + '/approve');
        const app = await expectStatus(r, 200, 'approve');
        expect(String(app.reviewStatus).toUpperCase()).toBe('APPROVED');
        let loc = await getLocation(tavern.objectId);
        expect(loc.approvedRenderObjectId, 'approvedRender pinned to the artifact').toBe(locationArtifactObjectId);
        expect(loc.node.pinned).toBe(true);

        await setLock(tavern.objectId, true);
        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/render', { data: {} });
        expect(errMsg(await expectStatus(r, 409, 'render while locked'))).toBe('Location The Drowned Anchor is locked; unlock it to render it');
        r = await api.put(PB + '/' + bookObjectId + '/artifact/' + locationArtifactObjectId + '/approve');
        expect(errMsg(await expectStatus(r, 409, 'approve while locked'))).toBe('Location The Drowned Anchor is locked; unlock it to approve a render of it');
        r = await api.put(PB + '/' + bookObjectId + '/artifact/' + locationArtifactObjectId + '/reject');
        expect(errMsg(await expectStatus(r, 409, 'reject while locked'))).toBe('Location The Drowned Anchor is locked; unlock it to reject its approved render');
        loc = await getLocation(tavern.objectId);
        expect(loc.approvedRenderObjectId, 'still pinned').toBe(locationArtifactObjectId);

        await setLock(tavern.objectId, false);
        r = await api.put(PB + '/' + bookObjectId + '/artifact/' + locationArtifactObjectId + '/reject');
        const rej = await expectStatus(r, 200, 'reject after unlock');
        expect(String(rej.reviewStatus).toUpperCase()).toBe('REJECTED');
        loc = await getLocation(tavern.objectId);
        expect(loc.approvedRenderObjectId == null, 'approvedRender cleared by the reject: ' + loc.approvedRenderObjectId).toBe(true);

        // Re-approve so the location leaves this file with an approved render (what P3-5 reads).
        r = await api.put(PB + '/' + bookObjectId + '/artifact/' + locationArtifactObjectId + '/approve');
        await expectStatus(r, 200, 're-approve');
        loc = await getLocation(tavern.objectId);
        expect(loc.approvedRenderObjectId).toBe(locationArtifactObjectId);
        // A pinned node refuses a new render until the pin is released.
        r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/render', { data: {} });
        expect(errMsg(await expectStatus(r, 409, 'render while pinned'))).toMatch(/pinned/i);
    });

    test('a variant renders through its own LOCATION node (?variant=)', async () => {
        test.setTimeout(30 * 60 * 1000);
        const t0 = Date.now();
        let r = await api.post(PB + '/' + bookObjectId + '/location/' + tavern.objectId + '/render?variant=' + variantObjectId, { data: {} });
        const sub = await expectStatus(r, 202, 'variant render submit');
        console.log(TAG + ' variant render submit: ' + JSON.stringify(sub).slice(0, 800));
        expect(sub.variantObjectId).toBe(variantObjectId);
        const job = await pollJob(sub.jobId, 'variant', 25 * 60 * 1000);
        console.log(TAG + ' variant render job terminal after ' + Math.round((Date.now() - t0) / 1000) + ' s: ' + JSON.stringify(job).slice(0, 1200));
        expect(job.status, 'job error: ' + (job.error || '')).toBe('completed');
        const loc = await getLocation(tavern.objectId);
        const v = loc.variants.find(x => x.objectId === variantObjectId);
        expect(v && v.node, 'variant node: ' + JSON.stringify(loc.variants)).toBeTruthy();
        // PbPipelineUtil.locationVariantHandle: "location_variant@" + shortRef(variantObjectId)
        expect(v.node.handle, 'variant handle').toMatch(/^location_variant@/);
        expect(v.node.chain.length).toBeGreaterThan(0);
        expect(v.node.chain[0].dataObjectId).toBeTruthy();
        await fetchImage(v.node.chain[0].dataObjectId, 'p34-location-variant-render');
        r = await api.put(PB + '/' + bookObjectId + '/artifact/' + v.node.chain[0].objectId + '/approve');
        await expectStatus(r, 200, 'approve variant');
        const after = await getLocation(tavern.objectId);
        expect(after.variants.find(x => x.objectId === variantObjectId).approvedRenderObjectId).toBe(v.node.chain[0].objectId);
        // The location's own pin is untouched by the variant's approval.
        expect(after.approvedRenderObjectId).toBe(locationArtifactObjectId);
    });
});

// ────────────────────────────── world deletion proof ──────────────────────────────
test.describe('deleting the book world removes the POI and its store (PB_LOC_DB_PROOF=1)', () => {
    test.skip(!DB_PROOF_ENABLED, 'PB_LOC_DB_PROOF=1 not set — stack-DB proof skipped (a skip, not a pass)');

    test('POI + store rows exist before DELETE /reset and are gone after it', async () => {
        test.setTimeout(5 * 60 * 1000);
        const loc = await getLocation(tavern.objectId);
        const poiRow = psql("select id, store, coalesce(location::text,'NULL'), east, north, groupid from a7_olio_pointofinterest_0_1 where objectid='" + loc.poiObjectId + "'");
        console.log(TAG + ' POI row before reset: ' + poiRow);
        expect(poiRow, 'POI row for ' + loc.poiObjectId).toBeTruthy();
        const [poiId, storeId, geo, east, north] = poiRow.split('|');
        expect(Number(storeId) > 0, 'POI has a store: ' + poiRow).toBe(true);
        expect(geo === 'NULL' || geo === '0', 'map-free POI has no geoLocation: ' + poiRow).toBe(true);
        expect(east).toBe('-1');
        expect(north).toBe('-1');
        expect(psql('select count(*) from a7_olio_store_0_1 where id=' + storeId)).toBe('1');
        expect(psql("select count(*) from a7_olio_pb_location_0_1 where objectid='" + tavern.objectId + "'")).toBe('1');
        expect(psql("select count(*) from a7_olio_world_0_1 where objectid='" + worldObjectId + "'")).toBe('1');

        await resetBook();

        expect(psql('select count(*) from a7_olio_pointofinterest_0_1 where id=' + poiId), 'POI row after reset').toBe('0');
        expect(psql('select count(*) from a7_olio_store_0_1 where id=' + storeId), 'store row after reset').toBe('0');
        expect(psql("select count(*) from a7_olio_pb_location_0_1 where objectid='" + tavern.objectId + "'"), 'location row after reset').toBe('0');
        expect(psql("select count(*) from a7_olio_pb_locationvariant_0_1 where objectid='" + variantObjectId + "'"), 'variant row after reset').toBe('0');
        expect(psql("select count(*) from a7_olio_world_0_1 where objectid='" + worldObjectId + "'"), 'world row after reset').toBe('0');
        const r = await api.get(PB + '/' + bookObjectId + '/locations');
        await expectStatus(r, 404, 'book gone');
    });
});
