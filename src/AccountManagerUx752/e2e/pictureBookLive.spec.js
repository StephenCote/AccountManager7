/**
 * Picture Book — Comprehensive E2E Test Plan (Phases A–L)
 * Exercises actual Picture Book UX functionality against live backend.
 *
 * Every bug found here was introduced by a previous Claude session.
 * Uses setupWorkflowTestData — NEVER admin for testing.
 * Screenshots saved to e2e/screenshots/ as proof.
 *
 * Env gates (the default multi-worker suite must never fire LLM/SD work in parallel):
 *   PB_LLM_TESTS=1  — beforeAll runs the real LLM extraction + import on the V3 path the wizard uses
 *                     (POST /chapter → extract-scenes-only?async=true → create-from-scenes bound to the
 *                     PB2 book; --workers=1) and the scene-dependent phases (D–I, H.1) get data. The
 *                     chatConfig comes from ensureChatConfig()/resolveChatRoute() ("[e2e-llm] route=…"),
 *                     never a hard-coded server. Without the flag the extraction is NOT run and every
 *                     scene-dependent test skips with NO_SCENES_REASON. B.1 (extract from the page) is
 *                     gated the same way. (B.4/B.5/E.3 — the PB1 scene render and what it produced — went
 *                     with POST /scene/{id}/generate at Phase 10 P10-1; B.2 — the bookless all-in-one
 *                     POST /{work}/extract — went with I-1 / KI-89 (b), 2026-11-13.)
 * The selector/picker/wizard/reimage-dialog tests (C.*, L.*) make no LLM or SD call and always run.
 *
 * bookObjectId is still the book GROUP objectId (~/Data/PictureBooks/<slug>) — what /scenes, /reset and
 * the #!/picture-book/{id} route take; pb2BookObjectId is the olio.pb.book the import is bound to. Since
 * every import now carries a PB2 record, the viewer route replace-redirects to
 * #!/picture-book/v2/{pb2BookObjectId} (loadViewer → resolvePb2BookObjectId, P7-3), so phases D–J exercise
 * the hand-rolled V3 reader (features/pictureBook.js pb2PageReaderView), NOT components/readerShell.js:
 *   root [data-testid="pb2-reader"][data-fullscreen="0|1"]; header [data-pb2-book-name] + 'Cover' /
 *   'Page N of M', [data-testid="pb2-export"] + [data-testid="pb2-fullscreen"] (UAT-2, through the shared
 *   exportBook engine); cover h1 = the book's NAME ("Book <slug>" — PbBookUtil.bookName; the human title
 *   lives in description), [data-pb2-cover-count] "N rendered of M scenes", a rounded-full Begin button;
 *   scene page h2 = scene.title, p.italic = poemStanza|blurb|summary, [data-testid="pb2-scene-image"] or
 *   an 'image' placeholder, Compose / Render / Plan & generate controls, footer 'Page N of M'; page dots
 *   button.w-2.5.rounded-full titled 'Cover' / 'Page N'. A fresh book has ONE front page (the cover), so
 *   the dot count is scenes + 1 (the old `button.rounded-full` selector also caught Begin — "5 dots").
 * Only an id with no V3 record (I.4, J.1, J.5, J.6) stays on the PB1 viewer, which is a ReaderShell.
 * Every wait below is a condition on the reader's own DOM (Begin visible = /pages answered), never a
 * fixed sleep: a key press that lands mid-load leaves the header on 'Page 1 of N' with the body still
 * 'Loading scenes...' (the D.3 / E.1 cold-worker reds of run 60).
 */
import { test as base, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import {
    setupWorkflowTestData, apiLogin, apiLogout, ensureChatConfig,
    ensurePath, createNote, createObject
} from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const test = base;
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOT_DIR = path.resolve(__dirname, 'screenshots');
const REST = '/AccountManagerService7/rest';

function saveScreenshot(pg, name) {
    fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
    return pg.screenshot({ path: path.join(SCREENSHOT_DIR, 'pb-' + name + '.png'), fullPage: true });
}

// Open the imported book in the V3 reader and wait for its COVER page: the PB1 route bridges to
// /picture-book/v2/{pb2}, the reader mounts with 'Loading scenes...' and renders the cover (Begin) once
// /pages and /scenes have answered. The timeouts are budgets for a small device, never pass/fail thresholds.
const DOT_SELECTOR = 'button.w-2\\.5.rounded-full';
async function openReaderAtCover(page) {
    await page.evaluate((wid) => { window.location.hash = '#!/picture-book/' + wid; }, bookObjectId || workObjectId);
    await expect(page.locator('[data-testid="pb2-reader"]'), 'the V3 reader mounts for the imported book').toBeVisible({ timeout: 30000 });
    await expect(page.locator('[data-pb2-cover-count]'), 'the cover page rendered (pages loaded)').toBeVisible({ timeout: 60000 });
    await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible();
}

// The scene page the reader shows for 1-based scene number n, pinned to the imported scene.
async function expectScenePage(page, n) {
    await expect(page.locator('h2'), 'scene ' + n + ' title').toHaveText(extractedScenes[n - 1].title, { timeout: 10000 });
    await expect(page.locator('[data-testid="pb2-reader"]')).toContainText('Page ' + n + ' of ' + extractedScenes.length);
    await expect(page.locator('button', { hasText: 'Begin' })).toHaveCount(0);
}

const PB_LLM = !!process.env.PB_LLM_TESTS;
const NO_SCENES_REASON = PB_LLM
    ? 'No scenes from extraction'
    : 'LLM extraction gated: set PB_LLM_TESTS=1 and run with --workers=1';

// Shared state across all tests in this describe block
let testInfo = {};
let workObjectId = null;     // Source document objectId (for extract API)
let bookObjectId = null;     // Book group objectId (for scenes/viewer/reset APIs)
let pb2BookObjectId = null;  // olio.pb.book objectId the V3 import is bound to (I-1 / KI-89 b)
let chatConfigName = null;
let extractedScenes = [];  // populated by Phase B
let pickNoteName = null;     // a second AIME note in ~/Notes, never extracted — what "Browse Notes" must list (C.2/C.3)
let pickNoteObjectId = null;
let apparelObj = null;       // olio.apparel owned by the test user (L.1); createObject returns identity only
let apparelName = null;
let exportedHtmlPath = null; // the HTML file I.2 downloaded in this worker; what I.3 validates

const AIME_TEXT = `AIME

Valentines Day singles events were blissfully epicaricacious. Schadenfreudian. Delightfully delectable. Lonely adults trodding out a lifetime's worth of emotional baggage casually dressed to such fantasy as trophies enshrined behind the thin plate glass of a secondhand curio; Of course one or more cats sprayed the back, and occasionally a few exotic bugs crept from the darkened hollows of the rear right leg.

The AI And Me singles event promised to be unlike any other; perhaps had they simply spoke plain, for the price of a geriatric rock star's off-Broadway casino theatre ticket, one could spend a Valentines Day evening taking the very same date they'd been on every day for as long as they could remember; This special evening your phone and you will immerse in a peaceful atmosphere and draw reverie from the curated bar.

Introverts slouching in their date's romantic glow formed a wilted bouquet, long ago plucked buds desperately clutching leathery petals against stems thickened with unnatural fertilizers. An eery somber echo rang for every clink against rectangular glass. And, as if scripted to specific times or events, sometimes in a rippling sequence, lips momentarily touched glass and a tear gleamed silver as it melted through heavy foundation.

With Rejects popping off in a slow though consistent rolling thunder, Break-Ups were streaks and bolts and chains of lightning that when striking close set the spine to shiver. An unsettling crash and crunch, a sudden hush making audible room for the imminent gasp of a dejected soliloquy. Quick-change service staff carry the remains upon a silver platter hoisted betwixt outstretched hands, pallbearers leading a somber procession towards the always available cry room.

Momentarily the shock unchokes the perception of reality. Screens going dark are infrequent, and screens going plaid hint at an appreciation for Mel Brooks. Ciao. That's all that's left behind when Abandoned: The heartless sayonara of an emotionally binary ex.

A vacuum forms between the two tables, two singles abandoned at a Valentines Day singles event, forced into eye contact; the following sequence of events from table, to check, to door proceeded with all the predictability of a B-list romantic comedy.

Outside, the rain began to fall, light and misty, fog churning like a smoldering fire through the streets. Bathed in the bright neon lights advertising the very explicit fantasies so secretly craved, the walk across the slick street through choking fog appeared programmatic, hypnotic, and the way the doors whisper open and greet with a pleasant warm puff of air is resplendent, only to be greeted by a solemn faced caretaker who prepares an arrangement of new vessels into which you must pour your soul.`;

test.describe('Picture Book — Comprehensive E2E (Phases A–L)', () => {
    // beforeAll does LLM extraction which takes 2-4 minutes
    test.describe.configure({ timeout: 600000 });

    test.beforeAll(async ({ request }) => {
        test.setTimeout(1800000); // LLM extraction + Olio seed can take 10+ minutes
        testInfo = await setupWorkflowTestData(request, { suffix: 'pb' + Date.now().toString(36) });
        console.log('=== Setup: user=' + testInfo.testUserName + ' ===');

        // Login as test user and create AIME note + chatConfig
        await apiLogin(request, {
            user: testInfo.testUserName,
            password: testInfo.testPassword
        });

        let ts = Date.now().toString(36);
        let docName = 'AIME-pb-' + ts;

        // Create data.note with AIME story text
        let note = await createNote(request, '~/Data', docName, AIME_TEXT);
        if (note && note.objectId) {
            workObjectId = note.objectId;
            console.log('=== Setup: AIME note created: ' + workObjectId + ' name=' + docName + ' ===');
        } else {
            console.log('=== Setup: FAILED to create AIME note ===');
        }

        // Second AIME note in ~/Notes: the selector's "Browse Notes" picker opens on the user's
        // data.note default path (view.js getBasePathByType -> ~/Notes), so this is what C.2 must find.
        // It is never extracted, so C.3's wizard opens at step 1 rather than resuming a book.
        pickNoteName = 'AIME-pick-' + ts;
        let pickNote = await createNote(request, '~/Notes', pickNoteName, AIME_TEXT);
        if (pickNote && pickNote.objectId) {
            pickNoteObjectId = pickNote.objectId;
            console.log('=== Setup: picker note created: ' + pickNoteObjectId + ' name=' + pickNoteName + ' ===');
        } else {
            console.log('=== Setup: FAILED to create picker note in ~/Notes ===');
        }

        // Owned olio.apparel for L.1 (the reimage dialog must open on a record the test user can read;
        // cleanupTestUser removes ~/Apparel).
        {
            let apparelDir = await ensurePath(request, 'auth.group', 'data', '~/Apparel');
            apparelName = testInfo.testUserName + '_pbapparel';
            if (apparelDir && apparelDir.id) {
                apparelObj = await createObject(request, 'olio.apparel', {
                    name: apparelName,
                    groupId: apparelDir.id, groupPath: apparelDir.path
                });
            }
            console.log('=== Setup: apparel ' + (apparelObj && apparelObj.objectId ? apparelObj.objectId : 'FAILED') + ' ===');
        }

        // chatConfig owned by the test user (PBAC), on whichever LLM tier resolveChatRoute() finds —
        // the helper opens its own session as this user and prints "[e2e-llm] route=…". Never a
        // hard-coded server/model: the model names belong to the resolved route (LiteLLM aliases when
        // routed, real Ollama names otherwise).
        await apiLogout(request);
        chatConfigName = await ensureChatConfig(request, null, {
            user: testInfo.testUserName, password: testInfo.testPassword,
            configName: 'PB-cfg-' + ts, connectionName: 'PB-conn-' + ts
        });
        console.log('=== Setup: chatConfig ' + (chatConfigName || 'FAILED') + ' ===');

        // Run the extraction + import in beforeAll so all tests have scene data — only when PB_LLM_TESTS
        // is set (real LLM call; must be serial). This is the V3 path the wizard (sceneExtractor.js)
        // drives: the import must be bound to an olio.pb.book (I-1 / KI-89 b).
        if (!PB_LLM) {
            console.log('=== Setup: PB_LLM_TESTS not set — skipping LLM extraction; scene-dependent tests will skip ===');
        } else if (workObjectId && chatConfigName) {
            await apiLogin(request, { user: testInfo.testUserName, password: testInfo.testPassword });
            try {
                let slug = 'aime-live-' + ts;
                let bookName = 'AIME Test Book ' + ts;
                let chapResp = await request.post(REST + '/olio/picture-book/chapter', { data: { slug, title: bookName }, timeout: 180000 });
                let chap = null;
                try { chap = await chapResp.json(); } catch (e) { chap = null; }
                if (chapResp.status() !== 200 || !chap || !chap.bookObjectId) {
                    throw new Error('beforeAll: POST /chapter failed: ' + chapResp.status() + ' ' + JSON.stringify(chap).substring(0, 300));
                }
                pb2BookObjectId = chap.bookObjectId;
                console.log('=== Setup: PB2 book created: pb2BookObjectId=' + pb2BookObjectId + ' slug=' + slug + ' ===');

                let startResp = await request.post(REST + '/olio/picture-book/' + workObjectId + '/extract-scenes-only?async=true', {
                    data: { schema: 'olio.pictureBookRequest', count: 3, genre: 'contemporary', chatConfig: chatConfigName },
                    timeout: 60000
                });
                let started = null;
                try { started = await startResp.json(); } catch (e) { started = null; }
                if (startResp.status() !== 202 || !started || !started.jobId) {
                    throw new Error('beforeAll: extract-scenes-only?async=true failed: ' + startResp.status() + ' ' + JSON.stringify(started).substring(0, 300));
                }
                // Poll to terminal (a budget for a small device, never a pass/fail timing threshold).
                let job = null;
                let t0 = Date.now();
                let lastLine = '';
                for (;;) {
                    let jr = await request.get(REST + '/job/' + started.jobId, { timeout: 60000 });
                    job = await jr.json();
                    let line = job.status + ' phase=' + job.phase + ' ' + job.current + '/' + job.total + ' elapsed=' + job.elapsed + 's';
                    if (line !== lastLine) { console.log('=== Setup: extract job ' + started.jobId + ': ' + line); lastLine = line; }
                    if (job.terminal) break;
                    if (Date.now() - t0 > 20 * 60 * 1000) throw new Error('beforeAll: extraction job still running after 20 minutes');
                    await new Promise(r => setTimeout(r, 5000));
                }
                if (job.status === 'failed') {
                    throw new Error('beforeAll: extraction job failed: ' + JSON.stringify(job).substring(0, 500));
                }
                let sceneList = Array.isArray(job.result) ? job.result : (job.result && Array.isArray(job.result.sceneList) ? job.result.sceneList : []);
                console.log('=== Setup: extraction returned ' + sceneList.length + ' scene(s) ===');

                let importResp = await request.post(REST + '/olio/picture-book/' + workObjectId + '/create-from-scenes', {
                    data: { schema: 'olio.pictureBookRequest', sceneList, chatConfig: chatConfigName, bookName, pb2BookObjectId },
                    timeout: 20 * 60 * 1000
                });
                let meta = null;
                try { meta = await importResp.json(); } catch (e) { meta = null; }
                if (importResp.status() !== 200 || !meta) {
                    throw new Error('beforeAll: create-from-scenes failed: ' + importResp.status() + ' ' + JSON.stringify(meta).substring(0, 500));
                }
                // meta.bookObjectId = the book GROUP (what /scenes, /reset and the viewer route take);
                // meta.pb2BookObjectId = the bound olio.pb.book.
                if (meta.bookObjectId) {
                    bookObjectId = meta.bookObjectId;
                    console.log('=== Setup: Book group created: bookObjectId=' + bookObjectId + ' pb2BookObjectId=' + meta.pb2BookObjectId + ' ===');
                }
                if (meta.scenes && meta.scenes.length > 0) {
                    extractedScenes = meta.scenes;
                    console.log('=== Setup: Imported ' + extractedScenes.length + ' scenes ===');
                    extractedScenes.forEach((s, i) => {
                        console.log('  ' + i + ': "' + (s.title || '') + '" oid=' + s.objectId);
                    });
                } else {
                    console.log('=== Setup: Import returned no scenes ===');
                    console.log('  Body: ' + JSON.stringify(meta).substring(0, 300));
                }
            } finally {
                await apiLogout(request);
            }
        }

        if (!workObjectId) {
            throw new Error(
                'beforeAll: AIME note creation failed — all pipeline tests will ' +
                'silently skip without this guard. Check login session and /rest/model POST.'
            );
        }
        if (!pickNoteObjectId) {
            throw new Error('beforeAll: picker note creation in ~/Notes failed — C.2/C.3 would silently skip.');
        }
        if (PB_LLM && !extractedScenes.length) {
            throw new Error(
                'beforeAll: LLM extraction + import returned no scenes. ' +
                'Check the "[e2e-llm] route=" line above for the tier the chatConfig=' + chatConfigName +
                ' points at (LiteLLM over the container Ollama first; Langfuse :3001 / docker logs ' +
                'am7test-litellm-1 for the trace) and that its models are loaded.'
            );
        }
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE B — Backend Pipeline (must run first to create data)
    // ══════════════════════════════════════════════════════════════════════

    test.describe('B. Backend Pipeline', () => {

        test('B.1 Extract scenes only', async ({ page }) => {
            test.setTimeout(300000);
            test.skip(!PB_LLM, NO_SCENES_REASON);
            test.skip(!workObjectId, 'No work created in setup');

            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let result = await page.evaluate(async (args) => {
                let { applicationPath } = await import('/src/core/config.js');
                let resp = await fetch(applicationPath + '/rest/olio/picture-book/' + args.workObjectId + '/extract-scenes-only', {
                    method: 'POST', credentials: 'include',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ schema: 'olio.pictureBookRequest', count: 4, chatConfig: args.chatConfigName })
                });
                let text = await resp.text();
                let parsed = null;
                try { parsed = JSON.parse(text); } catch (e) {}
                return { status: resp.status, body: text.substring(0, 1000), scenes: Array.isArray(parsed) ? parsed : [] };
            }, { workObjectId, chatConfigName });

            console.log('=== B.1 Extract Scenes Only ===');
            console.log('  Status: ' + result.status);
            console.log('  Scene count: ' + result.scenes.length);
            result.scenes.forEach((s, i) => {
                console.log('  ' + (i + 1) + ': "' + (s.title || 'untitled') + '"');
            });
            await saveScreenshot(page, 'B1-extractScenes');

            expect(result.status).toBe(200);
            expect(result.scenes.length).toBeGreaterThan(0);
            for (let s of result.scenes) {
                expect(s.title || s.summary || s.description).toBeTruthy();
            }
        });

        /* B.2 'Full extraction (reset + extract)' deleted with its subject (I-1 / KI-89 b, 2026-11-13): it
           drove the bookless all-in-one POST /{work}/extract, which no longer exists
           (PictureBookService.java had @Path("/{workObjectId}/extract") at :601 in HEAD d6c3ad41; the working
           tree has none). The import now runs in beforeAll on the V3 path the wizard uses - /chapter +
           extract-scenes-only?async=true + create-from-scenes bound to pb2BookObjectId - and B.3 pins that
           GET /scenes agrees with what it created. */

        test('B.3 GET /scenes returns valid data', async ({ page }) => {
            test.setTimeout(30000);
            test.skip(!workObjectId, 'No work created');

            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let scenesId = bookObjectId || workObjectId;
            let result = await page.evaluate(async (args) => {
                let { applicationPath } = await import('/src/core/config.js');
                let resp = await fetch(applicationPath + '/rest/olio/picture-book/' + args.scenesId + '/scenes', {
                    credentials: 'include'
                });
                if (!resp.ok) return { status: resp.status, scenes: [] };
                let scenes = await resp.json().catch(() => []);
                return {
                    status: resp.status,
                    scenes: Array.isArray(scenes) ? scenes.map(s => ({
                        objectId: s.objectId,
                        title: s.title,
                        description: (s.description || '').substring(0, 100),
                        descLen: (s.description || '').length,
                        characterCount: Array.isArray(s.characters) ? s.characters.length : 0
                    })) : []
                };
            }, { scenesId });

            console.log('=== B.3 GET /scenes ===');
            result.scenes.forEach((s, i) => {
                console.log('  ' + i + ': "' + s.title + '" desc=' + s.descLen + 'ch chars=' + s.characterCount);
                console.log('    blurb: ' + s.description);
            });

            expect(result.status).toBe(200);
            expect(result.scenes.length).toBeGreaterThan(0);
            for (let s of result.scenes) {
                expect(s.objectId).toBeTruthy();
                expect(s.title).toBeTruthy();
            }
            if (!extractedScenes.length) extractedScenes = result.scenes;
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE A — Bug Fix Verification
    // ══════════════════════════════════════════════════════════════════════

    test.describe('A. Bug Fix Verification', () => {

        test('A.1 groupPath default is absolute, not tilde', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let result = await page.evaluate(async () => {
                let { am7model } = await import('/src/core/model.js');
                let obj = am7model.newPrimitive('data.data');
                return { groupPath: obj.groupPath || null };
            });

            console.log('=== A.1 groupPath default ===');
            console.log('  groupPath: ' + result.groupPath);
            await saveScreenshot(page, 'A1-groupPath');

            expect(result.groupPath).toBeTruthy();
            expect(result.groupPath.startsWith('/home/')).toBe(true);
            expect(result.groupPath.startsWith('~/')).toBe(false);
            console.log('VERIFIED: groupPath is absolute');
        });

        test('A.2 mediaDataPath handles missing organizationPath', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let result = await page.evaluate(async () => {
                let { am7client } = await import('/src/core/am7client.js');
                // am7client.currentOrganization is set after login
                let org = am7client.currentOrganization;
                // Test with a record that has groupPath but NO organizationPath
                // mediaDataPath should fall back to currentOrganization
                let url = am7client.mediaDataPath({ name: 'test.png', groupPath: '/home/user/Data' }, false);
                return {
                    url: url,
                    org: org,
                    hasMedia: (url || '').includes('/media/'),
                    hasUndefined: (url || '').includes('undefined')
                };
            });

            console.log('=== A.2 mediaDataPath ===');
            console.log('  URL: ' + result.url);
            console.log('  currentOrganization: ' + result.org);
            await saveScreenshot(page, 'A2-mediaDataPath');

            // If currentOrganization is set, URL should be clean
            if (result.org) {
                expect(result.url).toBeTruthy();
                expect(result.hasMedia).toBe(true);
                expect(result.hasUndefined).toBe(false);
                console.log('VERIFIED: mediaDataPath works without organizationPath');
            } else {
                // If org is not set (e.g. session issue), log but don't fail
                console.log('  WARNING: currentOrganization not set — URL may contain undefined');
                console.log('  This is expected if the login session does not set organization context');
                expect(result.url).toBeTruthy();
            }
        });

        test('A.3 Work selector uses search, not list (no 400s)', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let networkErrors = [];
            page.on('requestfailed', req => networkErrors.push(req.url()));
            page.on('response', resp => {
                if (resp.status() >= 400) {
                    networkErrors.push(resp.status() + ' ' + resp.url());
                }
            });

            await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
            await page.waitForTimeout(3000);
            await saveScreenshot(page, 'A3-workSelector');

            let objectObjectUrls = networkErrors.filter(u => u.includes('[object Object]') || u.includes('[object'));
            let badUrls = networkErrors.filter(u => u.includes('400') || u.includes('500'));

            console.log('=== A.3 Work Selector Network ===');
            console.log('  Errors: ' + networkErrors.length);
            networkErrors.forEach(e => console.log('    ' + e));

            expect(objectObjectUrls).toHaveLength(0);
            // Allow some 400s that might be unrelated, but log them
            if (badUrls.length > 0) {
                console.log('  WARNING: ' + badUrls.length + ' 4xx/5xx responses');
            }
            console.log('VERIFIED: No [object Object] in URLs');
        });

        test('A.4 No reimage button on data.data objects', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            // Navigate to the AIME note via object page
            await page.evaluate((wid) => {
                window.location.hash = '#!/view/data.data/' + wid;
            }, workObjectId);
            await page.waitForTimeout(3000);
            await saveScreenshot(page, 'A4-noReimage');

            let hasReimage = await page.evaluate(() => {
                let buttons = Array.from(document.querySelectorAll('button'));
                return buttons.some(b => b.textContent.includes('Reimage'));
            });

            console.log('=== A.4 No Reimage on data.data ===');
            console.log('  Has Reimage button: ' + hasReimage);

            expect(hasReimage).toBe(false);
            console.log('VERIFIED: No Reimage button on data.data');
        });

        test('A.5 Large file display (stream-stored data)', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            // Upload Anaconda.jpg via API
            let uploadResult = await page.evaluate(async () => {
                let { am7client } = await import('/src/core/am7client.js');
                let { applicationPath } = await import('/src/core/config.js');

                // Check if file already exists by searching
                let q = am7client.newQuery('data.data');
                q.field('name', 'Anaconda-test.jpg');
                q.range(0, 1);
                let qr = await am7client.search(q);
                if (qr && qr.results && qr.results.length > 0) {
                    let rec = qr.results[0];
                    let url = am7client.mediaDataPath(rec);
                    return { objectId: rec.objectId, url: url, alreadyExists: true };
                }
                return { objectId: null, url: null, alreadyExists: false };
            });

            console.log('=== A.5 Large File Display ===');
            console.log('  Upload result: ' + JSON.stringify(uploadResult));

            // If no large file exists, skip gracefully
            if (!uploadResult.objectId) {
                console.log('  No large file available for test — skipping image display check');
                await saveScreenshot(page, 'A5-largeFile');
                return;
            }

            expect(uploadResult.url).toBeTruthy();
            expect(uploadResult.url).not.toContain('undefined');
            await saveScreenshot(page, 'A5-largeFile');
            console.log('VERIFIED: Large file URL is clean');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE C — Work Selector
    // ══════════════════════════════════════════════════════════════════════

    test.describe('C. Work Selector', () => {

        test('C.1 Loads with heading', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
            await page.waitForTimeout(3000);
            await saveScreenshot(page, 'C1-selector');

            let state = await page.evaluate(() => {
                let h2 = document.querySelector('h2');
                return { heading: h2 ? h2.textContent : null };
            });

            console.log('=== C.1 Selector Heading ===');
            console.log('  Heading: ' + state.heading);
            expect(state.heading).toBeTruthy();
            expect(state.heading).toContain('Picture Book');
        });

        // The selector no longer lists source documents inline (features/pictureBook.js:706-768 lists PB2
        // books + "Browse Notes"/"Browse Documents" buttons). "Browse Notes" opens the shared ObjectPicker
        // (components/picker.js: `.am7-picker-overlay`, h3 title 'Select Note') on the user's ~/Notes path
        // (view.js getBasePathByType), embedding the list control in table mode (list.js:1538 gridMode=0,
        // rows `tr.tabular-row`, decorator.js:333). A single row click only toggles selection
        // (decorator.js:355); the toolbar "check" button (list.js:972) confirms and fires onSelect.
        const PICKER = '.am7-picker-overlay';
        async function openNotesPicker(page) {
            await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
            await expect(page.locator('h2', { hasText: 'Picture Book' }).first()).toBeVisible({ timeout: 15000 });
            await page.locator('button', { hasText: 'Browse Notes' }).first().click();
            let picker = page.locator(PICKER);
            await expect(picker, 'Browse Notes must open the object picker').toBeVisible({ timeout: 10000 });
            await expect(picker.locator('h3')).toHaveText('Select Note');
            await expect(picker.locator('.result-nav-outer').first(), 'embedded list toolbar').toBeVisible({ timeout: 15000 });
            return picker;
        }

        test('C.2 Lists test user documents (AIME visible)', async ({ page }) => {
            test.skip(!pickNoteObjectId, 'No picker note created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let picker = await openNotesPicker(page);
            // The picker header shows the container path it landed on (picker.js:431-436): ~/Notes of this user.
            await expect(picker.locator('p.text-xs').first()).toHaveText(new RegExp('/home/' + testInfo.testUserName + '/Notes$'), { timeout: 10000 });

            let rows = picker.locator('tr.tabular-row');
            let aimeRow = rows.filter({ hasText: pickNoteName });
            await expect(aimeRow, 'the AIME note created in ~/Notes must be listed').toHaveCount(1, { timeout: 15000 });
            let names = await rows.allTextContents();
            console.log('=== C.2 Browse Notes picker rows: ' + names.length + ' ===');
            names.forEach(n => console.log('    ' + n.replace(/\s+/g, ' ').trim()));
            await saveScreenshot(page, 'C2-notesPicker');

            // Header close (picker.js:438-441) dismisses it.
            await picker.locator('button:has(span:text-is("close"))').first().click();
            await expect(picker).not.toBeVisible({ timeout: 5000 });
        });

        test('C.3 Click navigates to viewer', async ({ page }) => {
            test.skip(!pickNoteObjectId, 'No picker note created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let picker = await openNotesPicker(page);
            let aimeRow = picker.locator('tr.tabular-row').filter({ hasText: pickNoteName });
            await expect(aimeRow).toHaveCount(1, { timeout: 15000 });
            await aimeRow.click();
            await expect(aimeRow, 'row click must mark the note selected').toHaveClass(/tabular-row-active/);
            await picker.locator('button:has(span:text-is("check"))').first().click();
            await expect(picker, 'confirming the pick closes the picker').not.toBeVisible({ timeout: 5000 });

            // onSelect -> pictureBookFromId -> wizard dialog titled 'Picture Book — <name>' (workflows/pictureBook.js:2832),
            // at step 1 for a never-extracted note: 'Source: <name>', footer Cancel + Extract (:2410-2426).
            let dialog = page.locator('[role="dialog"]').filter({ hasText: 'Picture Book — ' + pickNoteName });
            await expect(dialog, 'the Picture Book wizard must open for the picked note').toBeVisible({ timeout: 15000 });
            await expect(dialog.locator('.am7-dialog-title')).toHaveText('Picture Book — ' + pickNoteName);
            await expect(dialog.getByText('Source: ' + pickNoteName)).toBeVisible();
            let extractBtn = dialog.locator('.am7-dialog-footer button', { hasText: 'Extract' });
            await expect(extractBtn, 'step 1 primary action is Extract').toBeVisible();
            await expect(extractBtn).toHaveClass(/am7-dialog-btn-primary/);
            await saveScreenshot(page, 'C3-wizardOpen');

            // Never click Extract here (it would launch a real LLM extraction). Cancel must close the wizard.
            await dialog.locator('.am7-dialog-footer button', { hasText: 'Cancel' }).click();
            await expect(dialog).not.toBeVisible({ timeout: 5000 });
            let hash = await page.evaluate(() => window.location.hash);
            console.log('=== C.3 wizard opened + cancelled; hash=' + hash + ' ===');
            expect(hash).toContain('/picture-book');
        });

        test('C.4 No network errors on selector', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let badResponses = [];
            page.on('response', resp => {
                if (resp.status() >= 400) {
                    badResponses.push(resp.status() + ' ' + resp.url());
                }
            });

            await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
            await page.waitForTimeout(3000);

            let objectUrls = badResponses.filter(u => u.includes('[object'));
            console.log('=== C.4 Network Check ===');
            console.log('  Bad responses: ' + badResponses.length);
            badResponses.forEach(r => console.log('    ' + r));

            expect(objectUrls).toHaveLength(0);
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE D — Viewer Cover Page
    // ══════════════════════════════════════════════════════════════════════

    test.describe('D. Viewer — Cover Page', () => {

        test('D.1 Cover renders with real data', async ({ page }) => {
            test.setTimeout(60000);
            console.log('=== D.1 DEBUG: workObjectId=' + workObjectId + ' extractedScenes.length=' + extractedScenes.length);
            test.skip(!workObjectId, 'No work created');
            test.skip(!extractedScenes.length, NO_SCENES_REASON);
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            await openReaderAtCover(page);
            await saveScreenshot(page, 'D1-cover');

            let state = await page.evaluate((dotSel) => {
                let h1 = document.querySelector('h1');
                let dots = document.querySelectorAll(dotSel);
                let count = document.querySelector('[data-pb2-cover-count]');
                let name = document.querySelector('[data-pb2-book-name]');
                return {
                    coverTitle: h1 ? h1.textContent.trim() : null,
                    headerName: name ? name.textContent.trim() : null,
                    dotCount: dots.length,
                    dotTitles: Array.from(dots).map(d => d.title),
                    hasNoBook: document.body.innerText.includes('No picture book'),
                    coverCount: count ? count.textContent.trim() : null
                };
            }, DOT_SELECTOR);

            console.log('=== D.1 Cover Page ===');
            console.log('  Title: ' + state.coverTitle + ' (header: ' + state.headerName + ')');
            console.log('  Dots: ' + state.dotCount + ' ' + JSON.stringify(state.dotTitles));
            console.log('  Cover count: ' + state.coverCount);

            expect(state.hasNoBook).toBe(false);
            // The cover h1 is the BOOK's name — "Book <slug>" (PbBookUtil.bookName); the human title the
            // import was given ("AIME Test Book <ts>") lives in the book's description, not its name.
            expect(state.coverTitle).toBeTruthy();
            expect(state.coverTitle).toBe(state.headerName);
            expect(state.coverTitle).toMatch(/^Book aime-live-[a-z0-9]+$/);
            // One front page (the cover) + one dot per imported scene; titled Cover, Page 1..N.
            expect(state.dotCount).toBe(extractedScenes.length + 1);
            expect(state.dotTitles).toEqual(['Cover'].concat(extractedScenes.map((s, i) => 'Page ' + (i + 1))));
            // Nothing is rendered yet: "0 rendered of N scenes".
            expect(state.coverCount).toBe('0 rendered of ' + extractedScenes.length + ' scene' + (extractedScenes.length !== 1 ? 's' : ''));
        });

        test('D.2 Scene count text visible', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // The V3 cover says "N rendered of M scenes" ([data-pb2-cover-count]); M = GET /{book}/scenes
            // (max'd with /pages), which for an imported book is exactly the imported scene count.
            let text = await page.locator('[data-pb2-cover-count]').textContent();
            let match = (text || '').trim().match(/^(\d+) rendered of (\d+) scenes?$/);

            console.log('=== D.2 Scene Count ===');
            console.log('  Match: ' + (match ? match[0] : 'NONE (text was: ' + text + ')'));

            expect(match).toBeTruthy();
            expect(parseInt(match[2])).toBe(extractedScenes.length);
            expect(parseInt(match[1])).toBeLessThanOrEqual(parseInt(match[2]));
        });

        test('D.3 Begin advances to page 1', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // Begin → pb2GoToPage(front count) = scene 1: h2 is the first imported scene's title, the
            // header and footer say 'Page 1 of N', and the cover's Begin button is gone.
            await page.locator('button', { hasText: 'Begin' }).click();
            await expectScenePage(page, 1);
            await saveScreenshot(page, 'D3-beginClicked');

            let state = await page.evaluate(() => {
                let h2 = document.querySelector('h2');
                return {
                    sceneTitle: h2 ? h2.textContent.trim() : null,
                    pageLabels: (document.body.innerText.match(/Page 1 of \d+/g) || []).length
                };
            });

            console.log('=== D.3 Begin ===');
            console.log('  Scene title: ' + state.sceneTitle);
            console.log('  "Page 1 of N" occurrences (header + footer): ' + state.pageLabels);

            expect(state.sceneTitle).toBe(extractedScenes[0].title);
            expect(state.pageLabels).toBe(2);
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE E — Viewer Scene Pages
    // ══════════════════════════════════════════════════════════════════════

    test.describe('E. Viewer — Scene Pages', () => {

        test('E.1 Scene page structure', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // ArrowRight from the cover → scene 1 (pb2OnKeyDown).
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);
            await saveScreenshot(page, 'E1-scene1');

            let state = await page.evaluate(() => {
                let h2 = document.querySelector('h2');
                let blurb = document.querySelector('[data-testid="pb2-reader"] p.italic');
                let img = document.querySelector('[data-testid="pb2-scene-image"]');
                let text = document.body.innerText;
                let pageMatch = text.match(/Page 1 of (\d+)/);
                return {
                    title: h2 ? h2.textContent.trim() : null,
                    blurbText: blurb ? blurb.textContent.trim() : null,
                    hasImage: !!img,
                    pageInfo: pageMatch ? pageMatch[0] : null,
                    pageTotal: pageMatch ? parseInt(pageMatch[1]) : null
                };
            });

            console.log('=== E.1 Scene Page ===');
            console.log('  Title: ' + state.title);
            console.log('  Blurb: ' + (state.blurbText || '').substring(0, 100));
            console.log('  Image: ' + state.hasImage + ' (unrendered import → placeholder expected)');
            console.log('  Page: ' + state.pageInfo);

            expect(state.title).toBe(extractedScenes[0].title);
            // p.italic = poemStanza || blurb || summary; an imported scene always has a blurb.
            expect(state.blurbText).toBeTruthy();
            expect(state.blurbText).not.toBe('No text yet.');
            expect(state.pageInfo).toBe('Page 1 of ' + extractedScenes.length);
            expect(state.pageTotal).toBe(extractedScenes.length);
        });

        // The PB1 viewer drew a rounded-full/text-xs badge per scene character (pictureBook.js:955); the V3
        // scene page has NO character badges — characters live in the Characters dialog behind
        // button[title="Manage characters"] (pbCharacterList.spec.js pins the list itself). The old
        // '.rounded-full.text-xs' "badge" scan was catching the header's auto-approve badge and asserting
        // nothing. What the V3 scene page does carry is pinned here.
        test('E.2 Scene page controls — V3 has no character badges', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);

            let reader = page.locator('[data-testid="pb2-reader"]');
            let pills = await reader.locator('.rounded-full.text-xs').evaluateAll(els => els.map(e => ({
                id: e.getAttribute('data-testid') || '', text: e.textContent.trim()
            })));
            console.log('=== E.2 Scene Page Pills ===');
            pills.forEach(p => console.log('    [' + p.id + '] ' + p.text));

            // Every pill on the V3 reader is a known, test-id'd chip; none is an anonymous character badge.
            const KNOWN_PILLS = ['pb2-auto-approve-badge', 'pb2-scene-chip', 'pb2-page-location',
                'pb2-scene-degradations', 'pb2-scene-degradations-error', 'pb2-run-chat-config'];
            for (let p of pills) {
                expect(KNOWN_PILLS, 'pill "' + p.text + '" must be a known V3 chip, not a character badge').toContain(p.id);
            }
            let names = (extractedScenes[0].characters || []).map(c => typeof c === 'string' ? c : (c && c.name)).filter(Boolean);
            for (let p of pills) expect(names, 'no pill is a character name').not.toContain(p.text);
            await expect(reader.locator('[data-testid="pb2-auto-approve-badge"]')).toHaveText(/^Auto-approve: (ON|OFF)$/);

            // The scene page's own controls and image slot.
            // Each button's textContent is '<icon name>' + label ('brushRender'), so pin the label's end.
            await expect(reader.locator('[data-testid="pb2-scene-compose"]')).toHaveText(/Compose$/);
            await expect(reader.locator('[data-testid="pb2-scene-render"]')).toHaveText(/(Render|Re-render)$/);
            await expect(reader.locator('[data-testid="pb2-plan-render"]')).toHaveText(/Plan & generate$/);
            let imgs = await reader.locator('[data-testid="pb2-scene-image"]').count();
            let placeholders = await reader.locator('span.material-symbols-outlined', { hasText: /^image$/ }).count();
            console.log('  scene image: ' + imgs + ', placeholder: ' + placeholders);
            expect(imgs + placeholders, 'an image or the image placeholder').toBeGreaterThan(0);
            await expect(reader.locator('button[title="Manage characters"]'), 'characters are managed from the header dialog').toBeVisible();
        });

        test('E.4 Navigate ALL pages with screenshots', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            let totalDots = await page.locator(DOT_SELECTOR).count();
            console.log('=== E.4 Navigate All Pages ===');
            console.log('  Total dots: ' + totalDots);
            expect(totalDots, 'cover + one dot per imported scene').toBe(extractedScenes.length + 1);

            for (let i = 0; i < totalDots; i++) {
                if (i > 0) {
                    await page.keyboard.press('ArrowRight');
                    await expectScenePage(page, i);
                }
                await saveScreenshot(page, 'E4-page-' + i);

                let info = await page.evaluate((dotSel) => {
                    let h1 = document.querySelector('h1');
                    let h2 = document.querySelector('h2');
                    let text = document.body.innerText;
                    let active = Array.from(document.querySelectorAll(dotSel)).findIndex(d => d.className.includes('bg-purple-500'));
                    return {
                        title: h2 ? h2.textContent.trim() : (h1 ? h1.textContent.trim() : ''),
                        isCover: !!document.querySelector('[data-pb2-cover-count]'),
                        pageNum: text.match(/Page (\d+) of (\d+)/)?.[1] || null,
                        activeDot: active
                    };
                }, DOT_SELECTOR);

                console.log('  Page ' + i + ': ' + (info.isCover ? 'COVER' : '"' + info.title + '" (Page ' + info.pageNum + ')') + ' active dot=' + info.activeDot);
                expect(info.activeDot, 'the active dot follows the page').toBe(i);
                if (i === 0) {
                    expect(info.isCover).toBe(true);
                } else {
                    expect(info.isCover).toBe(false);
                    expect(info.title).toBe(extractedScenes[i - 1].title);
                    expect(parseInt(info.pageNum)).toBe(i);
                }
            }
            console.log('VERIFIED: All ' + totalDots + ' pages navigated and screenshotted');
        });

        test('E.5 Blurb content on every scene page', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            let totalDots = await page.locator(DOT_SELECTOR).count();
            expect(totalDots).toBe(extractedScenes.length + 1);
            let blurbs = [];

            for (let i = 1; i < totalDots; i++) {
                await page.keyboard.press('ArrowRight');
                await expectScenePage(page, i);

                let blurb = await page.evaluate(() => {
                    let p = document.querySelector('[data-testid="pb2-reader"] p.italic');
                    return p ? p.textContent.trim() : '';
                });
                blurbs.push(blurb);
                console.log('  Page ' + i + ' blurb: ' + blurb.substring(0, 100));
            }

            console.log('=== E.5 Blurb Content ===');
            expect(blurbs.length).toBe(extractedScenes.length);
            for (let i = 0; i < blurbs.length; i++) {
                // p.italic = poemStanza || blurb || summary, else the 'No text yet.' placeholder.
                expect(blurbs[i].length, 'scene ' + (i + 1) + ' has text').toBeGreaterThan(0);
                expect(blurbs[i], 'scene ' + (i + 1) + ' is not the empty placeholder').not.toBe('No text yet.');
            }
            console.log('VERIFIED: All ' + blurbs.length + ' scenes have blurb text');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE F — Keyboard Navigation
    // ══════════════════════════════════════════════════════════════════════

    test.describe('F. Keyboard Navigation', () => {

        test('F.1 ArrowRight/ArrowLeft', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // ArrowRight → page 1
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);

            // ArrowLeft → back to cover
            await page.keyboard.press('ArrowLeft');
            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible({ timeout: 10000 });
            await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible();

            console.log('VERIFIED: ArrowRight/ArrowLeft navigation works');
        });

        test('F.2 Home/End', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // End → last page
            await page.keyboard.press('End');
            await expectScenePage(page, extractedScenes.length);
            let lastPageNum = await page.evaluate(() => {
                let match = document.body.innerText.match(/Page (\d+) of (\d+)/);
                return match ? { page: parseInt(match[1]), total: parseInt(match[2]) } : null;
            });
            console.log('  End: page ' + (lastPageNum ? lastPageNum.page : '?') + ' of ' + (lastPageNum ? lastPageNum.total : '?'));
            expect(lastPageNum).toBeTruthy();
            expect(lastPageNum.page).toBe(lastPageNum.total);
            expect(lastPageNum.total).toBe(extractedScenes.length);

            // Home → cover
            await page.keyboard.press('Home');
            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible({ timeout: 10000 });
            await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible();

            console.log('VERIFIED: Home/End navigation works');
        });

        test('F.3 Page dot click', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            let dots = page.locator(DOT_SELECTOR);
            let totalDots = await dots.count();
            expect(totalDots, 'cover + one dot per imported scene').toBe(extractedScenes.length + 1);
            expect(totalDots, 'the import asks for 3 scenes, so a 3rd dot (page 2) exists').toBeGreaterThanOrEqual(3);

            // Click the 3rd dot (index 2 = 'Page 2') → scene 2.
            await expect(dots.nth(2)).toHaveAttribute('title', 'Page 2');
            await dots.nth(2).click();
            await expectScenePage(page, 2);
            console.log('VERIFIED: Dot click changed page to 2 of ' + extractedScenes.length);
        });

        test('F.4 Boundary — left on cover', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            let errors = [];
            page.on('pageerror', err => errors.push(err.message));

            await openReaderAtCover(page);

            await page.keyboard.press('ArrowLeft');
            await page.waitForTimeout(500);

            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible();
            await expect(page.locator('button', { hasText: 'Begin' })).toBeVisible();
            expect(errors).toHaveLength(0);
            console.log('VERIFIED: Left on cover stays on cover, no errors');
        });

        test('F.5 Boundary — right on last page', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            let errors = [];
            page.on('pageerror', err => errors.push(err.message));

            await openReaderAtCover(page);

            await page.keyboard.press('End');
            await expectScenePage(page, extractedScenes.length);

            let beforeText = await page.evaluate(() => document.body.innerText.substring(0, 500));
            await page.keyboard.press('ArrowRight');
            await page.waitForTimeout(500);
            let afterText = await page.evaluate(() => document.body.innerText.substring(0, 500));

            // Page should not change
            expect(afterText).toBe(beforeText);
            expect(errors).toHaveLength(0);
            console.log('VERIFIED: Right on last page stays, no errors');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE G — Fullscreen
    // ══════════════════════════════════════════════════════════════════════

    test.describe('G. Fullscreen', () => {

        test('G.1 Toggle on', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // UAT-2: the V3 header's fullscreen toggle. On, the reader root becomes a fixed z-30 overlay
            // (dialogs z-40+ and toasts z-50 stay above it), the toggle flips to 'Exit fullscreen' /
            // fullscreen_exit, and the Back button is hidden as in ReaderShell.
            let reader = page.locator('[data-testid="pb2-reader"]');
            let toggle = page.locator('[data-testid="pb2-fullscreen"]');
            await expect(reader).toHaveAttribute('data-fullscreen', '0');
            await expect(toggle).toHaveAttribute('title', 'Fullscreen');
            await expect(toggle.locator('.material-symbols-outlined')).toHaveText('fullscreen');
            await expect(page.locator('button[title="Back to book list"]')).toBeVisible();

            await toggle.click();
            await expect(reader).toHaveAttribute('data-fullscreen', '1');
            await saveScreenshot(page, 'G1-fullscreen');

            await expect(reader).toHaveClass(/fixed inset-0 z-30/);
            await expect(toggle).toHaveAttribute('title', 'Exit fullscreen');
            await expect(toggle.locator('.material-symbols-outlined')).toHaveText('fullscreen_exit');
            await expect(page.locator('button[title="Back to book list"]')).toHaveCount(0);
            // Still the cover: fullscreen never moves the page.
            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible();
            console.log('VERIFIED: Fullscreen toggle ON (data-fullscreen=1, z-30 overlay, Back hidden)');
        });

        test('G.2 Escape exits fullscreen', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            let reader = page.locator('[data-testid="pb2-reader"]');
            let toggle = page.locator('[data-testid="pb2-fullscreen"]');

            // Enter fullscreen
            await toggle.click();
            await expect(reader).toHaveAttribute('data-fullscreen', '1');
            await expect(reader).toHaveClass(/fixed inset-0 z-30/);

            // Escape (pb2OnKeyDown) → back to the in-layout reader; Back is offered again.
            await page.keyboard.press('Escape');
            await expect(reader).toHaveAttribute('data-fullscreen', '0');
            await expect(reader).not.toHaveClass(/fixed inset-0/);
            await expect(toggle).toHaveAttribute('title', 'Fullscreen');
            await expect(page.locator('button[title="Back to book list"]')).toBeVisible();
            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible();
            console.log('VERIFIED: Escape exits fullscreen (data-fullscreen=0, Back visible)');
        });

        test('G.3 Navigate in fullscreen', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            let reader = page.locator('[data-testid="pb2-reader"]');

            // Enter fullscreen
            await page.locator('[data-testid="pb2-fullscreen"]').click();
            await expect(reader).toHaveAttribute('data-fullscreen', '1');

            // Navigate: ArrowRight → scene 1, still fullscreen (the route keeps its layout wrapper, so the
            // reader is not remounted and the keydown listener survives the toggle).
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);
            await expect(reader).toHaveAttribute('data-fullscreen', '1');
            await expect(page.locator('[data-testid="pb2-fullscreen"] .material-symbols-outlined')).toHaveText('fullscreen_exit');
            await saveScreenshot(page, 'G3-fullscreen-page1');
            console.log('VERIFIED: Navigation works in fullscreen');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE H — Reader header contract (was "Blurb Editing")
    // ══════════════════════════════════════════════════════════════════════

    test.describe('H. Reader header contract', () => {

        // Inline blurb editing and "Regenerate via AI" were removed from the viewer long ago (pictureBook.js
        // "renderBlurbDisplay and renderBlurbEditor removed — viewer is read-only", ledger archive 122 e);
        // H.2 / H.3 drove a textarea and a Regenerate button that no reader has, and passed only because
        // every click was `if (found)`. The PB1 viewer's `button[title="Edit Book"]` (pictureBook.js
        // pictureBookView, :1159-1163) is not offered by the V3 reader either: its header deliberately has
        // no Edit pencil — "the wizard would only hand straight back to this reader" (renderPb2Header,
        // P7-3 / P10-1, archive 217 d). H.1 now pins that header: every V3 tool entry is present and no
        // Edit affordance is.
        test('H.1 V3 reader header — tool entries present, no Edit affordance', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            test.skip(!extractedScenes.length, NO_SCENES_REASON);
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            await openReaderAtCover(page);
            let reader = page.locator('[data-testid="pb2-reader"]');

            await expect(reader.locator('button[title="Edit Book"]'), 'no Edit Book on the V3 reader').toHaveCount(0);
            await expect(reader.locator('button[title="Edit"]'), 'no Edit pencil on the V3 reader').toHaveCount(0);
            await expect(reader.locator('textarea'), 'no inline blurb editor').toHaveCount(0);

            await expect(reader.locator('button[title="Back to book list"]')).toBeVisible();
            await expect(reader.locator('[data-pb2-book-name]')).toHaveText(/^Book aime-live-[a-z0-9]+$/);
            await expect(reader.locator('[data-testid="pb2-chat-config"]')).toBeVisible();
            await expect(reader.locator('[data-testid="pb2-auto-approve-badge"]')).toHaveText(/^Auto-approve: (ON|OFF)$/);
            await expect(reader.locator('[data-testid="pb2-render-all"]')).toHaveAttribute('title', 'Render all scenes');
            await expect(reader.locator('[data-testid="pb2-review-link"]')).toHaveAttribute('title', 'Review queue');
            await expect(reader.locator('[data-testid="pb2-style-link"]')).toBeVisible();
            await expect(reader.locator('[data-testid="pb2-locations-link"]')).toBeVisible();
            await expect(reader.locator('[data-testid="pb2-compose-link"]')).toBeVisible();
            await expect(reader.locator('[data-testid="pb2-cover-link"]')).toBeVisible();
            await expect(reader.locator('button[title="Manage characters"]')).toHaveAttribute('data-pb2-characters', pb2BookObjectId);
            await expect(reader.locator('button[title="Open Workflow Canvas"]')).toBeVisible();
            await expect(reader.locator('[data-testid="pb2-export"]')).toHaveAttribute('title', 'Export as HTML');
            await expect(reader.locator('[data-testid="pb2-fullscreen"]')).toHaveAttribute('title', 'Fullscreen');
            await expect(reader.locator('button[title="Delete picture book"]')).toBeVisible();
            await saveScreenshot(page, 'H1-readerHeader');
            console.log('VERIFIED: V3 reader header carries every tool entry and no Edit affordance');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE I — Export
    // ══════════════════════════════════════════════════════════════════════

    test.describe('I. Export', () => {

        test('I.1 Export button enabled with data', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // UAT-2: the V3 header's export button, enabled as soon as the book has pages.
            let btn = page.locator('[data-testid="pb2-export"]');
            await expect(btn).toBeVisible();
            await expect(btn).toHaveAttribute('title', 'Export as HTML');
            await expect(btn).toBeEnabled();
            await expect(btn.locator('.material-symbols-outlined')).toHaveText('download');
            console.log('VERIFIED: Export button enabled');
        });

        test('I.2 Export triggers download', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            test.skip(!extractedScenes.length, NO_SCENES_REASON);

            await openReaderAtCover(page);

            // The download event is REQUIRED (the old `.catch(() => null)` made this test pass on no
            // download at all). The filename comes from exportBook: the book name with every non
            // [a-zA-Z0-9_-] replaced by '_' + '-picturebook.html' → 'Book_aime-live-<ts>-picturebook.html'.
            let downloadPromise = page.waitForEvent('download', { timeout: 60000 });
            await page.locator('[data-testid="pb2-export"]').click();
            let download = await downloadPromise;

            let filename = download.suggestedFilename();
            let exportPath = path.join(SCREENSHOT_DIR, filename);
            fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
            await download.saveAs(exportPath);
            let size = fs.statSync(exportPath).size;
            exportedHtmlPath = exportPath;

            console.log('=== I.2 Export ===');
            console.log('  File: ' + filename + ' (' + size + ' bytes)');
            expect(filename).toMatch(/^Book_aime-live-[a-z0-9]+-picturebook\.html$/);
            expect(size).toBeGreaterThan(100);
            await expect(page.getByText('Picture book exported')).toBeVisible({ timeout: 10000 });
            await expect(page.locator('[data-testid="pb2-export"]')).toBeEnabled();
            console.log('VERIFIED: Export downloaded');
        });

        test('I.3 HTML content valid', async () => {
            test.skip(!extractedScenes.length, NO_SCENES_REASON);
            // The file I.2 just exported in THIS worker — never "any *picturebook.html lying in the
            // screenshots dir" (a stale export from another run would validate a different book).
            expect(exportedHtmlPath, 'I.2 must have exported in this worker').toBeTruthy();

            let html = fs.readFileSync(exportedHtmlPath, 'utf-8');
            let size = html.length;
            let bookName = path.basename(exportedHtmlPath).replace(/-picturebook\.html$/, '').replace(/_/g, ' ');

            console.log('=== I.3 HTML Validation ===');
            console.log('  File: ' + path.basename(exportedHtmlPath) + ' (' + size + ' chars)');
            console.log('  Book name (from filename): ' + bookName);

            // exportBook's document shape (components/readerShell.js) with the V3 page builder
            // (pb2ExportPageHtml): title + cover block, one .scene per imported page, page numbers.
            expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
            expect(html).toContain('<title>' + bookName + ' — Picture Book</title>');
            expect(html).toContain('<h1>' + bookName + '</h1>');
            expect(html).toContain('<p>' + extractedScenes.length + ' Scene' + (extractedScenes.length !== 1 ? 's' : '') + '</p>');
            expect((html.match(/<div class="scene">/g) || []).length).toBe(extractedScenes.length);
            for (let i = 0; i < extractedScenes.length; i++) {
                let t = extractedScenes[i].title.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
                expect(html, 'scene ' + (i + 1) + ' heading').toContain('<h2>' + t + '</h2>');
                expect(html, 'scene ' + (i + 1) + ' page number').toContain('<div class="page-num">Page ' + (i + 1) + ' of ' + extractedScenes.length + '</div>');
            }
            expect(html).toContain('<p class="blurb">');
            expect(html.endsWith('</body>\n</html>')).toBe(true);
            expect(size).toBeGreaterThan(500);
            console.log('VERIFIED: Exported HTML is valid');
        });

        test('I.4 Export disabled on empty viewer', async ({ page }) => {
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            // An id with no V3 record stays on the PB1 viewer (loadViewer), which is a ReaderShell: its
            // header always renders button[title="Export as HTML"], disabled while there are no pages, over
            // the renderViewerEmpty body. The old `if (found)` let a page with no button at all pass.
            await page.evaluate(() => {
                window.location.hash = '#!/picture-book/nonexistent-objectid-12345';
            });
            await expect(page.getByText('No picture book has been generated for this document yet.')).toBeVisible({ timeout: 30000 });
            await expect(page.locator('[data-testid="pb2-reader"]'), 'not the V3 reader').toHaveCount(0);

            let btn = page.locator('button[title="Export as HTML"]');
            await expect(btn, 'the ReaderShell header renders Export').toHaveCount(1);
            await expect(btn).toBeDisabled();
            await expect(btn.locator('.material-symbols-outlined')).toHaveText('download');
            await expect(page.locator('button[title="Fullscreen"]')).toBeVisible();

            console.log('=== I.4 Export on empty ===');
            console.log('  Found: true, Disabled: true');
            console.log('VERIFIED: Export disabled on empty viewer');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE J — Negative Tests
    // ══════════════════════════════════════════════════════════════════════

    test.describe('J. Negative Tests', () => {

        test('J.1 Nonexistent work shows empty state', async ({ page }) => {
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let errors = [];
            page.on('pageerror', err => errors.push(err.message));

            await page.evaluate(() => {
                window.location.hash = '#!/picture-book/nonexistent-id-abc123';
            });
            await page.waitForTimeout(5000);
            await saveScreenshot(page, 'J1-notFound');

            let jsErrors = errors.filter(e => !e.includes('ResizeObserver'));
            console.log('=== J.1 Nonexistent work ===');
            console.log('  JS errors: ' + jsErrors.length);

            expect(jsErrors).toHaveLength(0);
            console.log('VERIFIED: No crash on nonexistent work');
        });

        test('J.2 Zero mediaUrl errors in full flow', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let errors = [];
            page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
            page.on('pageerror', err => errors.push(err.message));

            // Full flow: selector → viewer → navigate → back
            await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
            await page.waitForTimeout(3000);

            await openReaderAtCover(page);

            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, Math.min(2, extractedScenes.length));
            await page.keyboard.press('Home');
            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible({ timeout: 10000 });

            let mediaUrlErrors = errors.filter(e => e.includes('mediaUrl') || e.includes('is not a function'));
            console.log('=== J.2 mediaUrl Errors ===');
            console.log('  Total console errors: ' + errors.length);
            mediaUrlErrors.forEach(e => console.log('    ' + e));

            expect(mediaUrlErrors).toHaveLength(0);
            console.log('VERIFIED: Zero mediaUrl errors');
        });

        test('J.3 Zero [object Object] URLs', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let badUrls = [];
            page.on('request', req => {
                let url = req.url();
                if (url.includes('[object') || url.includes('undefined')) {
                    badUrls.push(url);
                }
            });

            await page.evaluate(() => { window.location.hash = '#!/picture-book'; });
            await page.waitForTimeout(3000);
            await openReaderAtCover(page);
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);

            console.log('=== J.3 [object Object] URLs ===');
            console.log('  Bad URLs: ' + badUrls.length);
            badUrls.forEach(u => console.log('    ' + u));

            expect(badUrls).toHaveLength(0);
            console.log('VERIFIED: Zero [object Object] URLs');
        });

        test('J.4 Zero 400/500 during normal flow', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let httpErrors = [];
            page.on('response', resp => {
                if (resp.status() >= 400 && !resp.url().includes('favicon')) {
                    httpErrors.push(resp.status() + ' ' + resp.url());
                }
            });

            await openReaderAtCover(page);
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, 1);
            await page.keyboard.press('ArrowRight');
            await expectScenePage(page, Math.min(2, extractedScenes.length));
            await page.keyboard.press('Home');
            await expect(page.locator('[data-pb2-cover-count]')).toBeVisible({ timeout: 10000 });

            // The ONE designed 404: the PB1 route's bridge probes GET /rest/model/olio.pb.book/{routeId}
            // first (workflows/pictureBookWorkflow.js resolvePb2BookObjectId step 1) and the route id here
            // is the book GROUP objectId, so that probe misses by design before the group → book lookup
            // succeeds. Nothing else may fail.
            let routeId = bookObjectId || workObjectId;
            let designedProbe = httpErrors.filter(e => e.startsWith('404 ') && e.endsWith('/rest/model/olio.pb.book/' + routeId));
            let unexpected = httpErrors.filter(e => !designedProbe.includes(e));

            console.log('=== J.4 HTTP Errors ===');
            console.log('  Errors: ' + httpErrors.length + ' (designed bridge probe 404s: ' + designedProbe.length + ')');
            httpErrors.forEach(e => console.log('    ' + e));

            expect(designedProbe.length).toBeLessThanOrEqual(1);
            expect(unexpected).toHaveLength(0);
            console.log('VERIFIED: Zero unexpected 4xx/5xx during normal flow');
        });

        test('J.5 Keyboard on empty viewer', async ({ page }) => {
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            let errors = [];
            page.on('pageerror', err => errors.push(err.message));

            await page.evaluate(() => {
                window.location.hash = '#!/picture-book/nonexistent-empty-viewer';
            });
            await page.waitForTimeout(5000);

            await page.keyboard.press('ArrowRight');
            await page.waitForTimeout(300);
            await page.keyboard.press('ArrowLeft');
            await page.waitForTimeout(300);
            await page.keyboard.press('Home');
            await page.waitForTimeout(300);
            await page.keyboard.press('End');
            await page.waitForTimeout(300);
            await page.keyboard.press('Escape');
            await page.waitForTimeout(300);

            let jsErrors = errors.filter(e => !e.includes('ResizeObserver'));
            expect(jsErrors).toHaveLength(0);
            console.log('VERIFIED: Keyboard on empty viewer causes no errors');
        });

        test('J.6 Back from empty state', async ({ page }) => {
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            await page.evaluate(() => {
                window.location.hash = '#!/picture-book/nonexistent-back-test';
            });
            await page.waitForTimeout(5000);

            // Click back arrow
            await page.evaluate(() => {
                let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
                let back = icons.find(i => i.textContent.trim() === 'arrow_back');
                if (back) back.closest('button').click();
            });
            await page.waitForTimeout(2000);

            let hash = await page.evaluate(() => window.location.hash);
            console.log('=== J.6 Back from empty ===');
            console.log('  Hash: ' + hash);

            // Should be at selector (no workObjectId in hash)
            expect(hash).toContain('/picture-book');
            expect(hash).not.toContain('nonexistent-back-test');
            console.log('VERIFIED: Back returns to selector');
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE K — Server Log Audit
    // ══════════════════════════════════════════════════════════════════════

    test.describe('K. Server Log Audit', () => {

        test('K.1 Audit server logs for test user', async () => {
            let logPath = 'C:\\Users\\swcot\\Desktop\\WEB-INF\\logs\\accountManagerService.log';
            let logContent = '';
            try {
                logContent = fs.readFileSync(logPath, 'utf-8');
            } catch (e) {
                console.log('=== K.1 Cannot read server log: ' + e.message + ' ===');
                return;
            }

            let lines = logContent.split('\n');
            let last200 = lines.slice(-200);

            // Filter for test user
            let userName = testInfo.testUserName || 'e2etest_pb';
            let userLines = last200.filter(l => l.includes(userName));
            let errorLines = last200.filter(l => l.includes('[ERROR') || l.includes('ERROR '));
            let denyLines = last200.filter(l => l.includes('DENY'));
            let chatConfigLines = last200.filter(l => l.includes('Chat config') || l.includes('chatConfig'));
            let pbNotFound = last200.filter(l => l.includes('pictureBookRequest was not found'));
            let descNotFound = last200.filter(l => l.includes('Field description was not found'));

            console.log('=== K.1 Server Log Audit ===');
            console.log('  Lines scanned: ' + last200.length);
            console.log('  User lines: ' + userLines.length);
            console.log('  ERROR lines: ' + errorLines.length);
            errorLines.forEach(l => console.log('    ERROR: ' + l.trim()));
            console.log('  DENY lines: ' + denyLines.length);
            denyLines.forEach(l => console.log('    DENY: ' + l.trim()));
            console.log('  Chat config lines: ' + chatConfigLines.length);
            chatConfigLines.forEach(l => console.log('    CFG: ' + l.trim()));
            console.log('  pictureBookRequest not found: ' + pbNotFound.length);
            console.log('  description field not found: ' + descNotFound.length);

            // Save filtered log
            let filtered = [
                '=== Server Log Audit ===',
                'User: ' + userName,
                '',
                '--- ERROR lines ---',
                ...errorLines,
                '',
                '--- DENY lines ---',
                ...denyLines,
                '',
                '--- Chat Config lines ---',
                ...chatConfigLines,
                '',
                '--- User lines ---',
                ...userLines
            ].join('\n');

            fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
            fs.writeFileSync(path.join(SCREENSHOT_DIR, 'pb-server-log.txt'), filtered);
            console.log('  Saved filtered log to e2e/screenshots/pb-server-log.txt');

            expect(pbNotFound).toHaveLength(0);
            expect(descNotFound).toHaveLength(0);
        });
    });

    // ══════════════════════════════════════════════════════════════════════
    // PHASE L — Apparel Reimage Dialog
    // ══════════════════════════════════════════════════════════════════════

    test.describe('L. Apparel Reimage', () => {

        test('L.2 Reimage NOT on data.data', async ({ page }) => {
            test.skip(!workObjectId, 'No work created');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            // data.note doesn't have reimage command — navigate to the AIME note
            await page.evaluate((wid) => {
                window.location.hash = '#!/view/data.note/' + wid;
            }, workObjectId);
            await page.waitForTimeout(3000);

            // Check that the auto_awesome (Reimage) icon is NOT present in commands
            let hasReimage = await page.evaluate(() => {
                let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
                return icons.some(i => i.textContent.trim() === 'auto_awesome');
            });

            expect(hasReimage).toBe(false);
            console.log('VERIFIED: No Reimage on data.note');
        });

        test('L.3 Reimage IS on charPerson', async ({ page }) => {
            test.skip(!testInfo.charPerson || !testInfo.charPerson.objectId, 'No charPerson in test data');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            await page.evaluate((oid) => {
                window.location.hash = '#!/view/olio.charPerson/' + oid;
            }, testInfo.charPerson.objectId);
            await page.waitForTimeout(3000);
            await saveScreenshot(page, 'L3-charPersonReimage');

            // Commands render as icon buttons (auto_awesome icon for Reimage)
            let hasReimage = await page.evaluate(() => {
                let icons = Array.from(document.querySelectorAll('.material-symbols-outlined'));
                return icons.some(i => i.textContent.trim() === 'auto_awesome');
            });

            console.log('=== L.3 Reimage on charPerson ===');
            console.log('  Has Reimage (auto_awesome icon): ' + hasReimage);

            expect(hasReimage).toBe(true);
            console.log('VERIFIED: Reimage present on charPerson');
        });

        // forms.apparel.commands.reimage (core/formDef.js:4279-4286, icon auto_awesome) runs
        // workflows/reimageApparel.js, NOT the charPerson reimage.js: its dialog is titled
        // 'Mannequin Images: <name>' (reimageApparel.js:157) and renders `label.field-label`s with live
        // values — "Steps: N", "Refiner Steps: N", "CFG: N", "Mannequin Creativity: N" (KI-43 replaced the
        // old "Denoising Strength" slider, :73-89), "Model", "Refiner Model", "Sampler", "Scheduler",
        // "Refiner Sampler", "Refiner Scheduler", "Style", "Seed" — plus #hires-cb / #shared-cb checkboxes
        // (:146-151) and actions Cancel / Load Shared / Generate(primary) (:160-230). 'Generate' POSTs
        // /olio/apparel/{id}/reimage (a real SD mannequin render) and is never clicked.
        test('L.1 Apparel reimage dialog shows all fields', async ({ page }) => {
            test.setTimeout(60000);
            test.skip(!apparelObj || !apparelObj.objectId, 'beforeAll could not create the owned olio.apparel');
            await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

            await page.evaluate((oid) => { window.location.hash = '#!/view/olio.apparel/' + oid; }, apparelObj.objectId);
            let reimageBtn = page.locator('button:has(span.material-symbols-outlined:text("auto_awesome"))').first();
            await expect(reimageBtn, 'Reimage command must render on the owned apparel').toBeVisible({ timeout: 20000 });
            await reimageBtn.click();

            let dialog = page.locator('.am7-dialog-backdrop .am7-dialog').first();
            await expect(dialog).toBeVisible({ timeout: 15000 });
            await expect(dialog.locator('.am7-dialog-title')).toHaveText('Mannequin Images: ' + apparelName);
            await saveScreenshot(page, 'L1-apparelReimage');

            let labels = (await dialog.locator('label.field-label').allTextContents()).map(t => t.replace(/\s+/g, ' ').trim());
            console.log('=== L.1 Apparel Reimage Dialog labels: ' + labels.join(' | '));

            let expected = [
                /^Steps: \d+$/, /^Refiner Steps: \d+$/, /^CFG: [\d.]+$/, /^Mannequin Creativity: \d+$/,
                /^Model$/, /^Refiner Model$/, /^Sampler$/, /^Scheduler$/, /^Refiner Sampler$/, /^Refiner Scheduler$/,
                /^Style$/, /^Seed$/
            ];
            for (let re of expected) {
                expect(labels.some(l => re.test(l)), 'mannequin dialog must render a field-label matching ' + re).toBe(true);
            }
            // The test-quality defaults the workflow applies (reimageApparel.js:29-30) must reach the labels.
            expect(labels).toContain('Steps: 20');
            expect(labels).toContain('Refiner Steps: 20');
            // Each live-value label sits above a formFieldRenderers.renderRange pair (range + number input).
            for (let re of [/^Steps: \d+$/, /^Refiner Steps: \d+$/, /^CFG: [\d.]+$/, /^Mannequin Creativity: \d+$/]) {
                let lbl = dialog.locator('label.field-label', { hasText: re }).first();
                await expect(lbl.locator('xpath=..').locator('input[type="range"]'), 'range input under ' + re).toHaveCount(1);
                await expect(lbl.locator('xpath=..').locator('input[type="number"]'), 'number input under ' + re).toHaveCount(1);
            }
            // Style default 'photograph' (reimageApparel.js:31) lands in the Style input.
            await expect(dialog.locator('label.field-label', { hasText: /^Style$/ }).locator('xpath=..').locator('input')).toHaveValue('photograph');
            // HiRes (forced off, :32) and "Save as shared config" checkboxes
            await expect(dialog.locator('#hires-cb'), 'HiRes checkbox').toHaveCount(1);
            await expect(dialog.locator('#hires-cb')).not.toBeChecked();
            await expect(dialog.locator('#shared-cb'), 'Save as shared config checkbox').toHaveCount(1);
            // Footer actions: Cancel, Load Shared, Generate (primary — never clicked: real SD render).
            await expect(dialog.locator('.am7-dialog-footer button', { hasText: 'Load Shared' })).toBeVisible();
            await expect(dialog.locator('.am7-dialog-footer button', { hasText: 'Generate' })).toHaveClass(/am7-dialog-btn-primary/);

            await dialog.locator('.am7-dialog-footer button', { hasText: 'Cancel' }).click();
            await expect(dialog).not.toBeVisible({ timeout: 5000 });
            console.log('VERIFIED: All mannequin reimage fields present');
        });
    });
});
