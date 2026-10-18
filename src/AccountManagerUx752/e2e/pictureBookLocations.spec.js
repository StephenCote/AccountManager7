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
 *                        P3-5: POST /{book}/scene/{scene}/plan for a scene at the approved location carries
 *                        {locationId, revisionHash, renderArtifactId}; at an unrendered variant it is
 *                        LOCATION_TEXT_ONLY naming the variant; after the variant is approved its render rides.
 *   PB_LOC_DB_PROOF=1  — §7.2 exit criterion "deleting the world removes both": reads the stack DB
 *                        (docker exec am7-pg psql, READ ONLY) before and after DELETE /{book}/reset.
 *   P3-7 Ux (default) — the Locations tab in the browser (loginAsSharedUser): reached from the reader
 *                        header, create / variant / lock / unlock / delete through the cards, and the
 *                        ChapBook review page chip + picker pointing a scene (PUT /scene/{s}/location)
 *                        so "used by" on the card and the variant reflects it. The UI works on its own
 *                        "The Lighthouse" so the tavern the later tiers read is never touched.
 *   P3-7 Ux + SD       — PB_SD_TESTS=1: Render on the pinned tavern card is refused in place; "Unpin and
 *                        render" runs the job to a new artifact in the card's chain. No timing asserted.
 *
 * Run (Windows / Docker stack — 127.0.0.1, never localhost):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_LLM_TESTS=1 PB_SD_TESTS=1 PB_LOC_DB_PROOF=1 \
 *     npx playwright test e2e/pictureBookLocations.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig } from './helpers/api.js';
import { loginAsSharedUser } from './helpers/chapbook.js';
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
let locatedSceneObjectId;   // P3-5: a scene whose location is the approved tavern
let variantSceneObjectId;   // P3-5: a scene at the tavern's dusk/rain variant
let preApprovalVariantInputHash;  // P3-5: inputs.locationVariant before the variant has an approved render
let locatedPlanBefore;            // P3-5: the located scene's plan before the variant is approved

const P35_SETTING = 'Rain hammers the shutters while the fiddler tunes up';

// Mirror of PbRenderPlanUtil.joinSentences: a trailing stop or clause separator is kept as the join, else ". ".
function joinSentences(parts) {
    let s = '';
    for (const p of parts) {
        if (s.length) s += (/[.!?;,:]$/.test(s) ? ' ' : '. ');
        s += p;
    }
    return s;
}

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

// POST /{book}/scene/{scene}/plan (P2-4 inspector): the RenderPlan map + estimate + contentHash, nothing persisted.
async function planOf(sceneOid, label) {
    const r = await api.post(PB + '/' + bookObjectId + '/scene/' + sceneOid + '/plan', { data: {}, timeout: 120000 });
    const plan = await expectStatus(r, 200, 'POST /plan ' + label);
    console.log(TAG + ' plan ' + label + ': location=' + JSON.stringify(plan.location) + ' inputs=' + JSON.stringify(plan.inputs)
        + ' degradations=' + JSON.stringify(plan.degradations || []) + ' strategy=' + JSON.stringify(plan.strategy));
    expect(plan.location, 'plan carries a location member: ' + JSON.stringify(plan).slice(0, 400)).toBeTruthy();
    return plan;
}

function codesOf(plan) {
    return (plan.degradations || []).map(d => d.code);
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

    test('P3-5: the plan of a scene at the approved location carries the render; an unrendered variant is LOCATION_TEXT_ONLY', async () => {
        test.setTimeout(10 * 60 * 1000);
        // The scene FKs patch by numeric id; the location DTO carries objectIds only.
        const locRec = await expectStatus(await api.get(REST + '/model/olio.pb.location/' + tavern.objectId), 200, 'GET olio.pb.location by id');
        const varRec = await expectStatus(await api.get(REST + '/model/olio.pb.locationVariant/' + variantObjectId), 200, 'GET olio.pb.locationVariant by id');
        expect(locRec.id && varRec.id, 'numeric ids: ' + JSON.stringify([locRec, varRec]).slice(0, 300)).toBeTruthy();
        const locRef = { schema: 'olio.pb.location', id: locRec.id, objectId: tavern.objectId };
        const varRef = { schema: 'olio.pb.locationVariant', id: varRec.id, objectId: variantObjectId };
        const locDescription = (await getLocation(tavern.objectId)).description;

        const mk = async (idx, title, extra) => {
            const body = Object.assign({
                schema: 'olio.pb.scene', name: 'Scene ' + bookSlug + ' ' + idx, groupId: bookGroupId, groupPath: bookGroupPath,
                book: { schema: 'olio.pb.book', id: bookId, objectId: bookObjectId }, sceneIndex: idx, title, setting: P35_SETTING
            }, extra);
            const created = await expectStatus(await api.post(REST + '/model', { data: body }), 200, 'POST olio.pb.scene ' + idx);
            expect(created && created.objectId, 'scene ' + idx + ' create returned no objectId: ' + JSON.stringify(created)).toBeTruthy();
            return created.objectId;
        };
        locatedSceneObjectId = await mk(101, 'At the Anchor', { location: locRef });
        variantSceneObjectId = await mk(102, 'At the Anchor, dusk', { location: locRef, locationVariant: varRef });

        // Scene at the approved location: the plan carries the pinned render, names the location, no degradation.
        const plan = await planOf(locatedSceneObjectId, 'located');
        expect(plan.location.locationId, 'locationId is the location record').toBe(tavern.objectId);
        expect(plan.location.variantId == null, 'no variant named: ' + JSON.stringify(plan.location)).toBe(true);
        expect(plan.location.renderArtifactId, 'the approved render rides the plan').toBe(locationArtifactObjectId);
        expect(typeof plan.location.revisionHash === 'string' && plan.location.revisionHash.length > 0, 'revisionHash: ' + JSON.stringify(plan.location)).toBe(true);
        expect(plan.inputs && plan.inputs.location, 'inputs.location: ' + JSON.stringify(plan.inputs)).toBe(plan.location.revisionHash);
        expect(plan.inputs.locationVariant == null, 'no locationVariant input: ' + JSON.stringify(plan.inputs)).toBe(true);
        expect(plan.location.description, 'composed: location description, then the scene setting').toBe(joinSentences([locDescription, P35_SETTING]));
        expect(codesOf(plan), 'no LOCATION_TEXT_ONLY for an approved location').not.toContain('LOCATION_TEXT_ONLY');
        expect(plan.strategy && plan.strategy.name, 'strategy unchanged by the carried render').toBe('TEXT_ONLY');
        locatedPlanBefore = plan;

        // Scene at the variant (not rendered yet): the variant is the subject, so text-only naming the variant.
        const vplan = await planOf(variantSceneObjectId, 'variant-unrendered');
        expect(vplan.location.locationId).toBe(tavern.objectId);
        expect(vplan.location.variantId).toBe(variantObjectId);
        expect(vplan.location.renderArtifactId == null, 'the location render is no substitute for the variant: ' + JSON.stringify(vplan.location)).toBe(true);
        expect(vplan.location.revisionHash).toBe(plan.location.revisionHash);
        expect(vplan.inputs.location).toBe(plan.location.revisionHash);
        expect(typeof vplan.inputs.locationVariant === 'string' && vplan.inputs.locationVariant.length > 0, 'inputs.locationVariant: ' + JSON.stringify(vplan.inputs)).toBe(true);
        // Location description, then the variant's qualifiers in the fixed order (timeOfDay, weather), then the scene setting.
        expect(vplan.location.description, 'composed: location, dusk, rain, scene setting').toBe(joinSentences([locDescription, 'dusk', 'rain', P35_SETTING]));
        const vds = (vplan.degradations || []).filter(x => x.code === 'LOCATION_TEXT_ONLY');
        expect(vds.length, 'exactly one LOCATION_TEXT_ONLY: ' + JSON.stringify(vplan.degradations)).toBe(1);
        const d = vds[0];
        expect(d.stage).toBe('BASE');
        expect(d.actorId == null, 'not an actor degradation: ' + JSON.stringify(d)).toBe(true);
        expect(d.message).toBe('No approved render for variant The Drowned Anchor - dusk, rain; setting described in text');
        expect(vplan.contentHash, 'the two plans differ').not.toBe(plan.contentHash);
        // The compiled BASE prompt is built from the composed description: prompt shown = prompt sent.
        const base = vplan.compiled && vplan.compiled.BASE;
        expect(base && typeof base.prompt === 'string', 'compiled BASE stage: ' + JSON.stringify(Object.keys(vplan.compiled || {}))).toBe(true);
        expect(base.prompt, 'compiled prompt carries the location description').toContain(locDescription);
        expect(base.prompt, 'compiled prompt carries the variant qualifiers').toContain('dusk');
        expect(base.prompt, 'compiled prompt carries the scene setting').toContain(P35_SETTING);
        preApprovalVariantInputHash = vplan.inputs.locationVariant;
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

        // P3-5: now that the variant is approved, the same scene's plan carries the VARIANT's render and the
        // degradation is gone; the scene at the plain location still carries the location's own render.
        const vplan = await planOf(variantSceneObjectId, 'variant-approved');
        expect(vplan.location.locationId).toBe(tavern.objectId);
        expect(vplan.location.variantId).toBe(variantObjectId);
        expect(vplan.location.renderArtifactId, 'the variant render rides the plan').toBe(v.node.chain[0].objectId);
        expect(codesOf(vplan)).not.toContain('LOCATION_TEXT_ONLY');
        // Only the variant's own input moved (approvedRender is in the variant's watched set); the location's did not.
        expect(typeof vplan.inputs.locationVariant === 'string' && vplan.inputs.locationVariant.length > 0, 'inputs.locationVariant: ' + JSON.stringify(vplan.inputs)).toBe(true);
        expect(preApprovalVariantInputHash && locatedPlanBefore, 'pre-approval state captured by the P3-5 test').toBeTruthy();
        expect(vplan.inputs.locationVariant, 'the variant input hash moves with its approvedRender').not.toBe(preApprovalVariantInputHash);
        expect(vplan.inputs.location, 'the location input is untouched by the variant approval').toBe(locatedPlanBefore.location.revisionHash);
        expect(vplan.location.revisionHash).toBe(locatedPlanBefore.location.revisionHash);
        const locDescription = (await getLocation(tavern.objectId)).description;
        expect(vplan.location.description, 'the composed text does not change with the render').toBe(joinSentences([locDescription, 'dusk', 'rain', P35_SETTING]));
        const plan = await planOf(locatedSceneObjectId, 'located-after-variant');
        expect(plan.location.renderArtifactId).toBe(locationArtifactObjectId);
        expect(plan.location.variantId == null).toBe(true);
        expect(plan.location.revisionHash).toBe(locatedPlanBefore.location.revisionHash);
        expect(plan.inputs.location).toBe(locatedPlanBefore.inputs.location);
        expect(plan.inputs.locationVariant == null, 'still no locationVariant input: ' + JSON.stringify(plan.inputs)).toBe(true);
        expect(codesOf(plan)).not.toContain('LOCATION_TEXT_ONLY');
        expect(plan.location.description).toBe(locatedPlanBefore.location.description);
        expect(plan.contentHash, 'the located scene\'s plan is unchanged by the variant approval').toBe(locatedPlanBefore.contentHash);
    });
});

// ────────────────────────────── P3-7: the Locations tab in the browser ──────────────────────────────
const UI_SCENE_INDEX = 201;
const LIGHTHOUSE = { name: 'The Lighthouse', description: 'A white lighthouse on the headland, gulls wheeling', kind: 'exterior', aliases: ['the light', 'headland light'] };

async function openLibrary(page) {
    await page.goto('/#!/picture-book/' + bookObjectId + '/locations', { timeout: 30000 });
    await expect(page.getByTestId('pb-loc')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId('pb-loc-grid')).toBeVisible({ timeout: 30000 });
}

function cardOf(page, locationObjectId) {
    return page.locator('[data-testid="pb-loc-card"][data-location-id="' + locationObjectId + '"]');
}

function toast(page, text) {
    return page.locator('.toast-text', { hasText: text });
}

test.describe('location library Ux (default tier)', () => {
    let uiSceneObjectId;            // scene 201 — the CRUD tier proved the book scene-less, so the chip test seeds its own page
    let lighthouse;                 // { objectId, name } — created through the New-location dialog
    let lighthouseVariant;          // { objectId, name } — created through the Add-variant dialog

    test.beforeAll(async () => {
        const body = {
            schema: 'olio.pb.scene', name: 'Scene ' + bookSlug + ' ' + UI_SCENE_INDEX, groupId: bookGroupId, groupPath: bookGroupPath,
            book: { schema: 'olio.pb.book', id: bookId, objectId: bookObjectId }, sceneIndex: UI_SCENE_INDEX,
            title: 'The Keeper', setting: 'The lighthouse gallery at dusk, the lamp not yet lit'
        };
        const created = await expectStatus(await api.post(REST + '/model', { data: body }), 200, 'POST olio.pb.scene ' + UI_SCENE_INDEX);
        expect(created && created.objectId, 'scene ' + UI_SCENE_INDEX + ' create returned no objectId: ' + JSON.stringify(created)).toBeTruthy();
        uiSceneObjectId = created.objectId;
    });

    test('reached from the reader header; the grid lists what GET /locations returns; New creates a location', async ({ page }) => {
        test.setTimeout(5 * 60 * 1000);
        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book/v2/' + bookObjectId, { timeout: 30000 });
        const link = page.getByTestId('pb2-locations-link');
        await expect(link).toBeVisible({ timeout: 30000 });
        await link.click();
        await expect(page).toHaveURL(new RegExp('#!/picture-book/' + bookObjectId + '/locations$'));
        await expect(page.getByTestId('pb-loc')).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pb-loc-grid')).toBeVisible({ timeout: 30000 });

        // The grid is the API list, card for card.
        const before = (await listLocations()).locations;
        expect(before.map(l => l.objectId), 'the tavern from the CRUD tier is listed').toContain(tavern.objectId);
        await expect(page.getByTestId('pb-loc-card')).toHaveCount(before.length);
        for (const l of before) {
            await expect(cardOf(page, l.objectId)).toHaveAttribute('data-location-name', l.name);
            await expect(cardOf(page, l.objectId).getByTestId('pb-loc-description')).toHaveText(l.description || 'No description');
        }
        await expect(page.getByTestId('pb-loc-count')).toHaveText(before.length + ' location' + (before.length === 1 ? '' : 's'));

        await page.getByTestId('pb-loc-new').click();
        await expect(page.getByTestId('pb-loc-new-form')).toBeVisible();
        await page.getByTestId('pb-loc-new-name').fill(LIGHTHOUSE.name);
        await page.getByTestId('pb-loc-new-description').fill(LIGHTHOUSE.description);
        await page.getByTestId('pb-loc-new-kind').selectOption(LIGHTHOUSE.kind);
        await page.getByTestId('pb-loc-new-aliases').fill(LIGHTHOUSE.aliases.join(', '));
        await page.getByTestId('pb-loc-new-create').click();
        await expect(page.getByTestId('pb-loc-new-form')).toHaveCount(0, { timeout: 30000 });
        await expect(toast(page, 'Created ' + LIGHTHOUSE.name)).toBeVisible({ timeout: 15000 });

        const after = (await listLocations()).locations;
        const rec = after.find(l => l.name === LIGHTHOUSE.name);
        expect(rec, 'the UI-created location is in GET /locations: ' + JSON.stringify(after.map(l => l.name))).toBeTruthy();
        expect(after.length).toBe(before.length + 1);
        expect(String(rec.kind).toLowerCase()).toBe(LIGHTHOUSE.kind);
        expect(rec.description).toBe(LIGHTHOUSE.description);
        expect(rec.aliases).toEqual(LIGHTHOUSE.aliases);
        expect(rec.locked).toBe(false);
        expect(rec.usedByScenes || []).toEqual([]);
        lighthouse = { objectId: rec.objectId, name: rec.name };
        console.log(TAG + ' UI created ' + rec.name + ' ' + rec.objectId);

        const card = cardOf(page, lighthouse.objectId);
        await expect(card).toBeVisible({ timeout: 30000 });
        await expect(card).toHaveAttribute('data-location-name', LIGHTHOUSE.name);
        await expect(card).toHaveAttribute('data-locked', '0');
        await expect(card.getByTestId('pb-loc-kind')).toHaveText(LIGHTHOUSE.kind);
        await expect(card.getByTestId('pb-loc-description')).toHaveText(LIGHTHOUSE.description);
        await expect(card.getByTestId('pb-loc-alias')).toHaveText(LIGHTHOUSE.aliases);
        await expect(card.getByTestId('pb-loc-used-by')).toHaveText('Not used by any scene');
        await expect(card.getByTestId('pb-loc-not-rendered')).toHaveText('Not rendered yet');
        await expect(card.getByTestId('pb-loc-variant')).toHaveCount(0);
        await expect(page.getByTestId('pb-loc-card')).toHaveCount(after.length);
    });

    test('Add variant creates one; Lock disables edit / render / variant / delete and Unlock restores them', async ({ page }) => {
        test.setTimeout(5 * 60 * 1000);
        expect(lighthouse, 'created by the previous test').toBeTruthy();
        await loginAsSharedUser(page);
        await openLibrary(page);
        const card = cardOf(page, lighthouse.objectId);
        await expect(card).toBeVisible({ timeout: 30000 });

        await card.getByTestId('pb-loc-add-variant').click();
        await expect(page.getByTestId('pb-loc-variant-form')).toBeVisible();
        await page.getByTestId('pb-loc-variant-timeOfDay').fill('dusk');
        await page.getByTestId('pb-loc-variant-create').click();
        await expect(page.getByTestId('pb-loc-variant-form')).toHaveCount(0, { timeout: 30000 });
        const addedToast = toast(page, /^Added variant /);
        await expect(addedToast).toBeVisible({ timeout: 15000 });
        const addedText = await addedToast.textContent();

        const loc = await getLocation(lighthouse.objectId);
        expect(loc.variants.length, 'one variant: ' + JSON.stringify(loc.variants)).toBe(1);
        const v = loc.variants[0];
        expect(v.timeOfDay).toBe('dusk');
        expect(v.locationObjectId).toBe(lighthouse.objectId);
        expect(addedText, 'the toast names the server-derived variant').toBe('Added variant ' + v.name);
        lighthouseVariant = { objectId: v.objectId, name: v.name };
        console.log(TAG + ' UI created variant ' + v.name + ' ' + v.objectId);

        const row = card.locator('[data-testid="pb-loc-variant"][data-variant-id="' + v.objectId + '"]');
        await expect(row).toBeVisible({ timeout: 30000 });
        await expect(row.getByTestId('pb-loc-variant-name')).toHaveText(v.name);
        await expect(row.getByTestId('pb-loc-variant-used-by')).toHaveText('Not used by any scene');
        await expect(row.getByTestId('pb-loc-not-rendered')).toHaveText('Not rendered yet');
        await expect(row.getByTestId('pb-loc-variant-render')).toBeEnabled();

        // Lock: the card reflects the server echo, every write control on it is disabled, the API agrees.
        await card.getByTestId('pb-loc-lock').click();
        await expect(toast(page, new RegExp('^Locked ' + LIGHTHOUSE.name + '$'))).toBeVisible({ timeout: 15000 });
        await expect(card).toHaveAttribute('data-locked', '1', { timeout: 30000 });
        await expect(card.getByTestId('pb-loc-locked')).toHaveText('Locked');
        await expect(card.getByTestId('pb-loc-lock')).toHaveText(/Unlock/);
        for (const t of ['pb-loc-edit', 'pb-loc-render', 'pb-loc-add-variant', 'pb-loc-delete', 'pb-loc-merge']) {
            await expect(card.getByTestId(t), t + ' disabled while locked').toBeDisabled();
        }
        await expect(row.getByTestId('pb-loc-variant-render')).toBeDisabled();
        expect((await getLocation(lighthouse.objectId)).locked).toBe(true);

        await card.getByTestId('pb-loc-lock').click();
        await expect(toast(page, new RegExp('^Unlocked ' + LIGHTHOUSE.name + '$'))).toBeVisible({ timeout: 15000 });
        await expect(card).toHaveAttribute('data-locked', '0', { timeout: 30000 });
        await expect(card.getByTestId('pb-loc-locked')).toHaveCount(0);
        for (const t of ['pb-loc-edit', 'pb-loc-render', 'pb-loc-add-variant', 'pb-loc-delete']) {
            await expect(card.getByTestId(t), t + ' enabled after unlock').toBeEnabled();
        }
        await expect(row.getByTestId('pb-loc-variant-render')).toBeEnabled();
        expect((await getLocation(lighthouse.objectId)).locked).toBe(false);
    });

    test('ChapBook review: the page chip opens the picker; Apply points the scene at the variant and the library shows it as used; delete is refused; Clear un-points it', async ({ page }) => {
        test.setTimeout(8 * 60 * 1000);
        expect(lighthouse && lighthouseVariant && uiSceneObjectId, 'fixtures from the previous tests').toBeTruthy();
        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/review/' + bookObjectId, { timeout: 30000 });
        const chip = page.locator('[data-testid="cb-page-location"][data-scene-oid="' + uiSceneObjectId + '"]');
        await expect(chip).toBeVisible({ timeout: 30000 });
        await expect(chip).toContainText('unlocated');
        await expect(chip).toHaveAttribute('data-location-id', '');
        await expect(chip).toBeEnabled();

        await chip.click();
        await expect(page.getByTestId('cb-page-location-picker')).toBeVisible();
        const select = page.getByTestId('cb-page-location-select');
        await expect(select).toBeVisible({ timeout: 15000 });
        await expect(page.getByTestId('cb-page-location-empty')).toHaveCount(0);
        await expect(page.getByTestId('cb-page-location-error')).toHaveCount(0);
        await expect(page.getByTestId('cb-page-location-apply')).toBeDisabled();
        await expect(page.getByTestId('cb-page-location-clear')).toBeDisabled();
        await select.selectOption(lighthouse.objectId);
        const variantSelect = page.getByTestId('cb-page-location-variant');
        await expect(variantSelect).toBeVisible();
        await variantSelect.selectOption(lighthouseVariant.objectId);
        await expect(page.getByTestId('cb-page-location-apply')).toBeEnabled();
        await page.getByTestId('cb-page-location-apply').click();
        await expect(page.getByTestId('cb-page-location-picker')).toHaveCount(0);
        await expect(toast(page, 'Page located: ' + lighthouse.name + ' / ' + lighthouseVariant.name)).toBeVisible({ timeout: 15000 });
        await expect(chip).toHaveAttribute('data-location-id', lighthouse.objectId, { timeout: 30000 });
        await expect(chip).toHaveAttribute('data-variant-id', lighthouseVariant.objectId);
        await expect(chip).toContainText('located: ' + lighthouse.name + ' / ' + lighthouseVariant.name);

        // The API is the oracle for "used by": the location DTO names the scene (with its variant) and so does the variant.
        let loc = await getLocation(lighthouse.objectId);
        let used = (loc.usedByScenes || []).find(u => u.sceneObjectId === uiSceneObjectId);
        expect(used, 'usedByScenes: ' + JSON.stringify(loc.usedByScenes)).toBeTruthy();
        expect(used.sceneIndex).toBe(UI_SCENE_INDEX);
        expect(used.variantObjectId).toBe(lighthouseVariant.objectId);
        expect((loc.usedByScenes || []).length, 'only this scene points at the lighthouse').toBe(1);
        let vUsed = (loc.variants[0].usedByScenes || []).map(u => u.sceneObjectId);
        expect(vUsed, 'variant usedByScenes').toEqual([uiSceneObjectId]);

        await openLibrary(page);
        const card = cardOf(page, lighthouse.objectId);
        await expect(card).toBeVisible({ timeout: 30000 });
        await expect(card.getByTestId('pb-loc-used-by')).toHaveText('Used by scene ' + UI_SCENE_INDEX);
        const row = card.locator('[data-testid="pb-loc-variant"][data-variant-id="' + lighthouseVariant.objectId + '"]');
        await expect(row.getByTestId('pb-loc-variant-used-by')).toHaveText('Used by scene ' + UI_SCENE_INDEX);

        // A location a scene still points at cannot be deleted; the server's refusal is the toast, verbatim.
        await card.getByTestId('pb-loc-delete').click();
        await expect(page.locator('.am7-dialog-title', { hasText: 'Delete location' })).toBeVisible();
        await page.locator('.am7-dialog-btn-destructive').click();
        await expect(toast(page, /still the location of 1 scene/)).toBeVisible({ timeout: 15000 });
        await expect(card).toBeVisible();
        await getLocation(lighthouse.objectId);

        await page.goto('/#!/chap-book/review/' + bookObjectId, { timeout: 30000 });
        await expect(chip).toBeVisible({ timeout: 30000 });
        await expect(chip).toHaveAttribute('data-location-id', lighthouse.objectId);
        await expect(chip).toContainText('located: ' + lighthouse.name + ' / ' + lighthouseVariant.name);
        await chip.click();
        await expect(page.getByTestId('cb-page-location-picker')).toBeVisible();
        await expect(page.getByTestId('cb-page-location-select')).toHaveValue(lighthouse.objectId, { timeout: 15000 });
        await expect(page.getByTestId('cb-page-location-variant')).toHaveValue(lighthouseVariant.objectId);
        await expect(page.getByTestId('cb-page-location-clear')).toBeEnabled();
        await page.getByTestId('cb-page-location-clear').click();
        await expect(page.getByTestId('cb-page-location-picker')).toHaveCount(0);
        await expect(toast(page, 'Page location cleared')).toBeVisible({ timeout: 15000 });
        await expect(chip).toHaveAttribute('data-location-id', '', { timeout: 30000 });
        await expect(chip).toHaveAttribute('data-variant-id', '');
        await expect(chip).toContainText('unlocated');

        loc = await getLocation(lighthouse.objectId);
        expect((loc.usedByScenes || []).map(u => u.sceneObjectId), 'cleared: ' + JSON.stringify(loc.usedByScenes)).not.toContain(uiSceneObjectId);
        expect(loc.variants[0].usedByScenes || []).toEqual([]);

        await openLibrary(page);
        await expect(cardOf(page, lighthouse.objectId).getByTestId('pb-loc-used-by')).toHaveText('Not used by any scene');
        await expect(row.getByTestId('pb-loc-variant-used-by')).toHaveText('Not used by any scene');
    });

    test('Delete removes the location from the grid and from GET /locations', async ({ page }) => {
        test.setTimeout(5 * 60 * 1000);
        expect(lighthouse, 'created by the first test').toBeTruthy();
        await loginAsSharedUser(page);
        await openLibrary(page);
        const card = cardOf(page, lighthouse.objectId);
        await expect(card).toBeVisible({ timeout: 30000 });
        const countBefore = await page.getByTestId('pb-loc-card').count();

        await card.getByTestId('pb-loc-delete').click();
        await expect(page.locator('.am7-dialog-title', { hasText: 'Delete location' })).toBeVisible();
        await page.locator('.am7-dialog-btn-destructive').click();
        await expect(toast(page, 'Deleted ' + LIGHTHOUSE.name)).toBeVisible({ timeout: 30000 });
        await expect(card).toHaveCount(0, { timeout: 30000 });
        await expect(page.getByTestId('pb-loc-card')).toHaveCount(countBefore - 1);

        await expectStatus(await api.get(PB + '/' + bookObjectId + '/location/' + lighthouse.objectId), 404, 'deleted lighthouse is gone');
        const names = (await listLocations()).locations.map(l => l.name);
        expect(names).not.toContain(LIGHTHOUSE.name);
        expect(names, 'the tavern the later tiers read is untouched').toContain(tavern.name);
    });
});

test.describe('location library Ux: Render from the card (PB_SD_TESTS=1)', () => {
    test.skip(!SD_ENABLED, 'PB_SD_TESTS=1 not set — live SD render through the Locations tab skipped (a skip, not a pass)');

    test('Render on the pinned tavern is refused in place; Unpin and render runs the job to a new artifact in the chain', async ({ page }) => {
        test.setTimeout(35 * 60 * 1000);
        expect(tavern && tavern.objectId, 'tavern from the CRUD tier').toBeTruthy();
        const before = await getLocation(tavern.objectId);
        expect(before.node && before.node.pinned === true, 'the SD tier left the tavern pinned: ' + JSON.stringify(before.node)).toBe(true);
        expect(before.locked, 'the SD tier left the tavern unlocked').toBe(false);
        const beforeIds = (before.node.chain || []).map(a => a.objectId);

        await loginAsSharedUser(page);
        await openLibrary(page);
        const card = cardOf(page, tavern.objectId);
        await expect(card).toBeVisible({ timeout: 30000 });
        await expect(card.getByTestId('pb-loc-node-pinned').first()).toHaveText('pinned');
        for (const id of beforeIds) {
            await expect(card.locator('[data-testid="pb-loc-artifact"][data-artifact-id="' + id + '"]')).toBeVisible();
        }

        // The 409 is shown on the card with the server's own sentence, not as a run.
        await card.getByTestId('pb-loc-render').click();
        const refusal = card.getByTestId('pb-loc-pinned');
        await expect(refusal).toBeVisible({ timeout: 30000 });
        await expect(refusal).toContainText(/pinned/i);
        await expect(card.getByTestId('pb-loc-run')).toHaveCount(0);
        await expect(card.getByTestId('pb-loc-render')).toBeEnabled();

        const t0 = Date.now();
        await card.getByTestId('pb-loc-unpin').click();
        // The location's own run panel precedes the variants in the card.
        const run = card.getByTestId('pb-loc-run').first();
        await expect(run).toBeVisible({ timeout: 60000 });
        await expect(card.getByTestId('pb-loc-pinned')).toHaveCount(0);
        await expect(card.getByTestId('pb-loc-render')).toBeDisabled();
        await expect(card.getByTestId('pb-loc-render')).toHaveText(/Rendering/);
        expect(await run.getByTestId('pb-loc-run-stage').count(), 'planned stages listed').toBeGreaterThan(0);

        // Never a timing assertion: the ceiling exists so a dead job fails the test rather than hanging it.
        await expect(run).toHaveAttribute('data-run-terminal', '1', { timeout: 30 * 60 * 1000 });
        const summary = await run.getByTestId('pb-loc-run-counter').textContent();
        console.log(TAG + ' UI render of ' + tavern.name + ' terminal after ' + Math.round((Date.now() - t0) / 1000) + ' s: ' + summary);
        expect(summary).toMatch(/^Render complete/);

        const after = await getLocation(tavern.objectId);
        const fresh = (after.node.chain || []).map(a => a.objectId).filter(id => beforeIds.indexOf(id) < 0);
        expect(fresh.length, 'a new artifact in the chain: ' + JSON.stringify(after.node.chain)).toBeGreaterThan(0);
        expect(after.node.chain.find(a => a.objectId === fresh[0]).dataObjectId, 'the new artifact has bytes').toBeTruthy();
        await expect(card.locator('[data-testid="pb-loc-artifact"][data-artifact-id="' + fresh[0] + '"]')).toBeVisible({ timeout: 30000 });
        await expect(card.getByTestId('pb-loc-render')).toBeEnabled();
        await run.getByTestId('pb-loc-run-dismiss').click();
        await expect(card.getByTestId('pb-loc-run')).toHaveCount(0);
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
