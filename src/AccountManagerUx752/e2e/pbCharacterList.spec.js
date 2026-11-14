/**
 * PB2 character-list regression proof, on the V3 import path.
 *
 * Original bug: after the (now deleted) all-in-one /extract completed, the "Manage Characters" list came
 * back empty because CharacterUtil.randomPerson spun forever on an empty basis-world name pool and the
 * extract never returned. The property this pins is unchanged - a real LLM extraction followed by the
 * import must leave GET /{bookObjectId}/characters NON-EMPTY - but the pipeline it drives is the one the
 * wizard (sceneExtractor.js) actually uses since I-1 / KI-89 (b), 2026-11-13:
 *
 *   POST /chapter                              -> the olio.pb.book every import is bound to
 *   POST /{work}/extract-scenes-only?async=true -> 202 {jobId}, polled on GET /rest/job/{jobId} (live LLM)
 *   POST /{work}/create-from-scenes             -> { sceneList, chatConfig, bookName, pb2BookObjectId }
 *   GET  /{book}/characters                     -> must be non-empty
 *
 * Request-API only (no page.evaluate / no /src/ dev-server imports), so it runs against the Docker prod
 * build on :9443. Shared non-admin user (ensureSharedTestUser) and a chatConfig on whichever LLM tier
 * resolveChatRoute() finds (it prints "[e2e-llm] route=... "); never a hard-coded server.
 *
 * Gated: PB_LLM_TESTS=1 (a skip is a skip, never a pass). Run single-threaded:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_LLM_TESTS=1 \
 *     npx playwright test e2e/pbCharacterList.spec.js --workers=1 --project=chromium --retries=0
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, ensureChatConfig, createNote } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const JOB = REST + '/job';
const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const TAG = '[pbCharacterList]';

const AIME_TEXT = `AIME

Valentines Day singles events were blissfully epicaricacious. Schadenfreudian. Delightfully delectable. Lonely adults trodding out a lifetime's worth of emotional baggage casually dressed to such fantasy as trophies enshrined behind the thin plate glass of a secondhand curio; Of course one or more cats sprayed the back, and occasionally a few exotic bugs crept from the darkened hollows of the rear right leg.

The AI And Me singles event promised to be unlike any other; perhaps had they simply spoke plain, for the price of a geriatric rock star's off-Broadway casino theatre ticket, one could spend a Valentines Day evening taking the very same date they'd been on every day for as long as they could remember; This special evening your phone and you will immerse in a peaceful atmosphere and draw reverie from the curated bar.

Introverts slouching in their date's romantic glow formed a wilted bouquet, long ago plucked buds desperately clutching leathery petals against stems thickened with unnatural fertilizers. An eery somber echo rang for every clink against rectangular glass. And, as if scripted to specific times or events, sometimes in a rippling sequence, lips momentarily touched glass and a tear gleamed silver as it melted through heavy foundation.

With Rejects popping off in a slow though consistent rolling thunder, Break-Ups were streaks and bolts and chains of lightning that when striking close set the spine to shiver. An unsettling crash and crunch, a sudden hush making audible room for the imminent gasp of a dejected soliloquy. Quick-change service staff carry the remains upon a silver platter hoisted betwixt outstretched hands, pallbearers leading a somber procession towards the always available cry room.

Outside, the rain began to fall, light and misty, fog churning like a smoldering fire through the streets. Bathed in the bright neon lights advertising the very explicit fantasies so secretly craved, the walk across the slick street through choking fog appeared programmatic, hypnotic, and the way the doors whisper open and greet with a pleasant warm puff of air is resplendent, only to be greeted by a solemn faced caretaker who prepares an arrangement of new vessels into which you must pour your soul.`;

async function expectStatus(resp, status, what) {
    const body = await resp.text();
    expect(resp.status(), what + ': expected ' + status + ' got ' + resp.status() + ' ' + body.slice(0, 400)).toBe(status);
    try { return JSON.parse(body); } catch (e) { return body; }
}

// Deadline-bounded poll of the async job, as sceneExtractor.pollJob does. The deadline is a budget for
// a small device, never a pass/fail timing threshold.
async function pollJob(api, jobId, label, maxMs) {
    const t0 = Date.now();
    let last = '';
    for (;;) {
        const r = await api.get(JOB + '/' + jobId, { timeout: 60000 });
        const j = await expectStatus(r, 200, 'GET /job/' + jobId);
        const line = j.status + ' phase=' + j.phase + ' ' + j.current + '/' + j.total + ' elapsed=' + j.elapsed + 's';
        if (line !== last) { console.log(TAG + ' ' + label + ' job ' + jobId + ': ' + line); last = line; }
        if (j.terminal) return j;
        expect(Date.now() - t0, label + ' job still running after ' + maxMs + ' ms').toBeLessThan(maxMs);
        await new Promise(res => setTimeout(res, 5000));
    }
}

function scenesFromResult(result) {
    if (!result) return [];
    if (Array.isArray(result)) return result;
    if (Array.isArray(result.sceneList)) return result.sceneList;
    return [];
}

test('V3 import (extract-scenes-only + create-from-scenes) yields a non-empty character list (PB_LLM_TESTS=1)', async ({ request }) => {
    test.skip(!LLM_ENABLED, 'PB_LLM_TESTS=1 not set - live LLM extraction + import skipped (a skip, not a pass)');
    test.setTimeout(30 * 60 * 1000); // LLM extraction + character creation can take many minutes (a budget, not a threshold)

    const baseURL = test.info().project.use.baseURL;
    const shared = await ensureSharedTestUser(request);
    const chatConfigName = await ensureChatConfig(request, null);
    expect(chatConfigName, 'chatConfig provisioned via resolveChatRoute()').toBeTruthy();

    const api = await pwRequest.newContext({ baseURL, ignoreHTTPSErrors: true });
    try {
        await api.get(REST + '/logout');
        const login = await api.post(REST + '/login', {
            data: {
                schema: 'auth.credential', organizationPath: '/Development', name: shared.testUserName,
                credential: Buffer.from(shared.testPassword).toString('base64'), type: 'hashed_password'
            }
        });
        expect(login.ok() || login.status() === 204, 'shared-user login failed: ' + login.status()).toBe(true);
        console.log(TAG + ' user=' + shared.testUserName + ' chatConfig=' + chatConfigName);

        const ts = Date.now().toString(36);
        const note = await createNote(api, '~/Notes', 'AIME-chars-' + ts, AIME_TEXT);
        expect(note && note.objectId, 'AIME note created').toBeTruthy();
        const workObjectId = note.objectId;
        console.log(TAG + ' AIME note: ' + workObjectId);

        // The olio.pb.book every import is bound to (I-1 / KI-89 b).
        const slug = 'pbchars' + ts;
        const bookName = 'AIME Chars Book ' + ts;
        const cb = await expectStatus(await api.post(PB + '/chapter', { data: { slug, title: bookName }, timeout: 180000 }), 200, 'POST /chapter');
        const bookObjectId = cb.bookObjectId;
        expect(bookObjectId, 'POST /chapter returned no bookObjectId: ' + JSON.stringify(cb)).toBeTruthy();
        console.log(TAG + ' book=' + bookObjectId + ' slug=' + slug);

        // Live LLM scene extraction, exactly as the wizard runs it (async job).
        const started = await expectStatus(await api.post(PB + '/' + workObjectId + '/extract-scenes-only?async=true', {
            data: { schema: 'olio.pictureBookRequest', chatConfig: chatConfigName, count: 3, genre: 'contemporary' },
            timeout: 60000
        }), 202, 'extract-scenes-only (async)');
        expect(started.jobId, 'extraction jobId').toBeTruthy();
        const extractJob = await pollJob(api, started.jobId, 'extract', 20 * 60 * 1000);
        expect(extractJob.status, 'extraction job status: ' + JSON.stringify(extractJob).slice(0, 400)).not.toBe('failed');
        const scenes = scenesFromResult(extractJob.result);
        scenes.forEach((s, i) => console.log(TAG + '   scene ' + i + ': "' + (s.title || '') + '" characters=' + JSON.stringify((s.characters || []).map(c => (c && c.name) || c))));
        expect(scenes.length, 'extraction produced scenes').toBeGreaterThan(0);

        // The import - bound to the book - is the step that creates the charPersons (this is the path that
        // used to hang in the character loop).
        const cfs = await api.post(PB + '/' + workObjectId + '/create-from-scenes', {
            data: { schema: 'olio.pictureBookRequest', sceneList: scenes, chatConfig: chatConfigName, bookName, pb2BookObjectId: bookObjectId },
            timeout: 20 * 60 * 1000
        });
        const meta = await expectStatus(cfs, 200, 'create-from-scenes (did not hang)');
        console.log(TAG + ' import: bookObjectId(group)=' + meta.bookObjectId + ' pb2BookObjectId=' + meta.pb2BookObjectId
            + ' scenes=' + ((meta.scenes || []).length) + ' failedCharacters=' + JSON.stringify(meta.failedCharacters || []));
        // meta.pb2BookObjectId is the bound olio.pb.book; meta.bookObjectId stays the book GROUP's objectId
        // (~/Data/PictureBooks/<slug>, used by delete/reset) - PictureBookUtil.createFromScenes.
        expect(meta.pb2BookObjectId, 'the import result names the bound PB2 book').toBe(bookObjectId);
        expect(meta.bookObjectId, 'the import result carries the book group objectId').toBeTruthy();
        expect(meta.bookObjectId, 'bookObjectId (group) and pb2BookObjectId (book) are distinct records').not.toBe(bookObjectId);
        expect(Array.isArray(meta.scenes) && meta.scenes.length, 'persisted scenes').toBeGreaterThan(0);

        // THE ORIGINAL BUG: the character list must be non-empty.
        const characters = await expectStatus(await api.get(PB + '/' + bookObjectId + '/characters'), 200, 'GET /characters');
        console.log(TAG + ' character count: ' + (Array.isArray(characters) ? characters.length : 'N/A'));
        if (Array.isArray(characters)) {
            characters.forEach((c, i) => console.log(TAG + '   ' + i + ': "' + (c.name || '(unnamed)') + '" gender=' + c.gender + ' portrait=' + c.hasPortrait));
        }
        expect(Array.isArray(characters), 'characters is an array').toBe(true);
        expect(characters.length, 'character list is NON-EMPTY (the original bug)').toBeGreaterThan(0);
        for (const c of characters) {
            expect(c.objectId, 'each character has an objectId').toBeTruthy();
            expect((c.name || '').trim().length, 'each character has a non-empty name').toBeGreaterThan(0);
        }
        console.log(TAG + ' VERIFIED: V3 import completed and the character list has ' + characters.length + ' entries');
    } finally {
        await api.dispose();
    }
});
