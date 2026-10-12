/**
 * Chaptered-manuscript PictureBook flow, driven THROUGH THE BROWSER against the live Docker stack.
 *
 * Stephen's report (2026-09-24): after the last chapter the wizard threw a script error and vanished,
 * more than half the chapters were missing, and the chapters were listed as separate books. Three
 * acceptance criteria, each asserted here in the real Ux:
 *   (a) a list of books — a chaptered manuscript is ONE bundled series card, not N books;
 *   (b) opening a book with chapters lets you SELECT which chapter (reader chapter <select>);
 *   (c) characters are defined from the text — the cast is non-empty and every persisted
 *       olio.charPerson.race entry is a RaceEnumType constant name. Race/ethnicity/raceLabel are
 *       PRINTED for inspection against the manuscript; which labels the live LLM picks is
 *       non-deterministic content and is NOT asserted here (the content contract is pinned by the
 *       recorded-fixture spec, pictureBookChapteredManuscriptEmulated.spec.js).
 * Plus: NO uncaught page error anywhere in the run (the "script error and disappears" symptom).
 *
 * Nothing is stubbed: live LLM, the real HarlotsEight_Vol1_SM.docx, the shared non-admin test user.
 * The LLM is the spec's OWN chatConfig (`000-pbux-llm`, ensureChatConfig -> resolveChatRoute():
 * LiteLLM-in-stack first, else the direct Ollama fallback) on the spec's OWN `000-pbux-conn`
 * connection, whose requestTimeout is sized for a whole chapter passage (PB_UX_REQUEST_TIMEOUT,
 * default 900s), picked in the wizard's Step 1 "Chat Config" ObjectPicker instead of whatever
 * `contentAnalysis` resolves to for the shared user. The '000-' prefix is the same device
 * chapBookChatConfigPicker.spec.js uses: the picker shows ONE name-ascending page of ~/Chat with no
 * paging control, and the shared user's ~/Chat holds dozens of leftover e2e configs, so a name that
 * sorts first is the only way to make the row reachable. The route in use is printed once per run as
 * "[e2e-llm] route=... server=... analysis=... pb=...". When the fan-out report lists ONLY
 * per-passage request timeouts (a live-LLM sampling loop), the spec presses the wizard's own
 * "Retry N failed passages" once (PB_UX_MAX_PASSAGE_RETRIES) and then "Open series" — the product's
 * recovery path, used as a user would; every other problem kind fails the test. The ONLY other
 * test-side hook is an optional truncation of the detect-boundaries RESPONSE to the first
 * PB_UX_MAX_CHAPTERS chapters, so a first run is bounded to hours rather than a day;
 * PB_UX_MAX_CHAPTERS=0 runs the whole manuscript.
 *
 * GATE: PB_LLM_TESTS=1 (live LLM; run it alone, --workers=1). Timings are recorded, never thresholds.
 *
 *   cd src/AccountManagerUx752
 *   PB_LLM_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test \
 *       e2e/pictureBookChapteredManuscriptUx.spec.js --workers=1 --retries=0 --project=chromium
 *   PB_UX_MAX_CHAPTERS=2 ... (first two chapters — the repair-run shape)
 *   PB_UX_MAX_CHAPTERS=0 ... (full manuscript)
 *   PB_UX_EXISTING_SERIES="<seriesName or seriesObjectId>" ... (skip extraction; re-check an existing series)
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, ensureChatConfig } from './helpers/api.js';
import { screenshot } from './helpers/auth.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

const LLM_ENABLED = process.env.PB_LLM_TESTS === '1';
const ORG_PATH = '/Development';
// The run's chatConfig name. '000-' sorts it onto the picker's single name-ascending page (see header).
const PICKER_CONFIG_NAME = '000-pbux-llm';
// The spec's OWN system.connection (same resolved route as everyone else; only the per-call budget
// differs). The shared e2e-chapbook-conn carries requestTimeout=300, and a Harlot's Eight passage on
// the CPU-only container tier streamed 2954-4470 chars in those 300s and was cut off mid-generation
// (3 of 7 passages, run of 2026-10-12). The budget is a per-call ceiling, not a threshold: it only
// has to exceed the slowest honest passage. 900s stays under the 1200s HTTP read backstop.
const OWN_CONNECTION_NAME = '000-pbux-conn';
const OWN_REQUEST_TIMEOUT = parseInt(process.env.PB_UX_REQUEST_TIMEOUT || '900', 10);
// How many times the spec may press the wizard's own "Retry N failed passages" when EVERY reported
// problem is a per-passage request timeout (see the wait loop). 0 = never retry.
const MAX_PASSAGE_RETRIES = parseInt(process.env.PB_UX_MAX_PASSAGE_RETRIES || '1', 10);
// FAILURE_KIND_TEXT['timeout'] in src/workflows/pictureBook.js — keyed on the server's kind contract.
const TIMEOUT_PROBLEM_RE = /the model did not finish within the request timeout/;

const DOCX_PATH = process.env.PB_UX_DOCX
    || path.resolve(__dirname, '../../AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx');
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

const MAX_CHAPTERS = parseInt(process.env.PB_UX_MAX_CHAPTERS || '6', 10); // 0 = whole manuscript
const PER_CHAPTER_MIN = parseInt(process.env.PB_UX_PER_CHAPTER_MIN || '60', 10); // budget per chapter, not a threshold
// Resume mode: seriesName or seriesObjectId of a series an earlier run already extracted. Skips the
// upload + wizard fan-out and runs only the list / reader / cast assertions against it.
const EXISTING_SERIES = process.env.PB_UX_EXISTING_SERIES || null;

// RaceEnumType constant NAMES (Objects7 olio/RaceEnumType.java) — the shape olio.charPerson.race stores.
const RACE_CODES = ['A', 'B', 'C', 'D', 'E', 'L', 'R', 'M', 'S', 'U', 'V', 'W', 'X', 'Y', 'Z', 'O'];

function b64(str) { return Buffer.from(str).toString('base64'); }
function fmtMin(ms) { return (ms / 60000).toFixed(1) + ' min'; }
function fmtMs(ms) { return ms + 'ms (' + fmtMin(ms) + ')'; }

async function restLogin(request, creds) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: ORG_PATH,
            name: creds.testUserName,
            credential: b64(creds.testPassword),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

async function searchOne(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, cache: false, fields, request: requestFields, recordCount: 5 }
    });
    if (!resp.ok()) return null;
    const body = await resp.json();
    const results = Array.isArray(body) ? body : (body && body.results) || [];
    return results.length ? results[0] : null;
}

async function uploadManuscript(request, name) {
    const dir = await ensurePath(request, 'auth.group', 'data', '~/Manuscripts');
    expect(dir && dir.id, 'could not ensure ~/Manuscripts').toBeTruthy();
    const bytes = fs.readFileSync(DOCX_PATH);
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'data.data',
            name,
            groupId: dir.id,
            groupPath: dir.path,
            contentType: DOCX_MIME,
            dataBytesStore: bytes.toString('base64')
        }
    });
    expect(resp.ok(), 'manuscript upload failed: ' + resp.status()).toBe(true);
    const rec = await resp.json();
    expect(rec && rec.objectId, 'manuscript create returned no objectId').toBeTruthy();
    return rec.objectId;
}

async function getJson(request, url) {
    const resp = await request.get(url, { headers: { Accept: 'application/json' }, timeout: 60000 });
    expect(resp.ok(), 'GET ' + url + ' -> ' + resp.status()).toBe(true);
    return resp.json();
}

// Stub WebSocket BEFORE the first goto: Docker's nginx does not forward the session cookie on the WS
// upgrade, Tomcat closes it, and pageClient.js would reconnect -> forceLogin -> #!/sig mid-run.
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

// REST login on the page's own context (cookie shared with the SPA), stub, load, wait for #!/main.
async function loginSharedUser(page, creds) {
    await restLogin(page.request, creds);
    await installWsStub(page);
    await page.goto('/', { timeout: 45000 });
    // page.waitForFunction(fn, arg, options): options is the THIRD argument — passing it second makes
    // it the page-function argument and the wait unbounded.
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null,
        { timeout: 45000 }
    );
}

/** Pick the run's chatConfig through the real ObjectPicker on Step 1's "Chat Config" field. */
async function pickChatConfig(page, dialog, configName) {
    const cfgField = dialog.locator('label.field-label:has-text("Chat Config") + div');
    // loadDefaults() overwrites chatConfigRef when its own lookup lands; let it finish first.
    await expect(cfgField).not.toContainText('Loading default...', { timeout: 30000 });
    console.log('[ux-chapters] Step 1 default chatConfig field: "' + ((await cfgField.textContent()) || '').trim() + '"');
    await cfgField.click();
    const overlay = page.locator('.am7-picker-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });
    await expect(overlay.locator('h3')).toHaveText('Select Chat Config');
    const row = overlay.locator('tr.tabular-row', { hasText: configName });
    await expect(row, 'picker lists the shared user\'s ~/Chat/' + configName).toBeVisible({ timeout: 20000 });
    await row.click();
    await expect(row).toHaveClass(/tabular-row-active/);
    // Toolbar confirm: pagination.button('button','check',...) -> <button class="button"><span class="material-symbols-outlined">check</span></button>
    await overlay.locator('button:has(span:text-is("check"))').first().click();
    await expect(overlay).toHaveCount(0, { timeout: 10000 });
    await expect(cfgField).toContainText(configName);
}

test.describe('PictureBook chaptered manuscript — real browser flow on Docker', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    let creds = null;
    let chatConfigName = null;
    let docName = null;
    let docFileName = null;     // the uploaded data.data name — what the wizard adopts as the series title
    let docObjectId = null;
    let orgId = null;
    let expectedChapters = 0;   // how many chapters the wizard will be handed
    let totalChapters = 0;      // how many the manuscript really has
    const pageErrors = [];
    const consoleErrors = [];
    const failedReads = [];

    test.beforeAll(async ({ request }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS!=1 — skips the live-LLM chaptered extraction (run alone with --workers=1)');
        test.setTimeout(240000);
        creds = await ensureSharedTestUser(request);
        // Own config under a picker-reachable name, on the spec's own connection with a passage-sized
        // per-call budget; route/dialect/upstream/models all come from resolveChatRoute().
        chatConfigName = await ensureChatConfig(request, null, {
            configName: PICKER_CONFIG_NAME,
            connectionName: OWN_CONNECTION_NAME,
            requestTimeout: OWN_REQUEST_TIMEOUT
        });    // prints "[e2e-llm] route=…"
        expect(chatConfigName, 'chatConfig provisioned via resolveChatRoute()').toBe(PICKER_CONFIG_NAME);
        console.log('[ux-chapters] connection ' + OWN_CONNECTION_NAME + ' requestTimeout=' + OWN_REQUEST_TIMEOUT + 's');
        await restLogin(request, creds);
        const chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
        orgId = chatDir && chatDir.organizationId;
        expect(typeof orgId, '~/Chat returned no numeric organizationId').toBe('number');
        console.log('[ux-chapters] shared user = ' + creds.testUserName + ' | chatConfig = ' + chatConfigName + ' | orgId = ' + orgId);

        if (EXISTING_SERIES) {
            const allBooks = await getJson(request, PB_REST + '/books');
            const mine = allBooks.filter(b => b.seriesName === EXISTING_SERIES || b.seriesObjectId === EXISTING_SERIES);
            expect(mine.length, 'no books found for PB_UX_EXISTING_SERIES=' + EXISTING_SERIES
                + ' (series seen: ' + JSON.stringify([...new Set(allBooks.map(b => b.seriesName))]) + ')').toBeGreaterThan(0);
            docFileName = mine[0].seriesName;
            docName = docFileName.replace(/\.docx$/i, '');
            expectedChapters = mine.length;
            totalChapters = mine.length;
            console.log('[ux-chapters] RESUME against series "' + docFileName + '" (' + mine[0].seriesObjectId
                + ') with ' + expectedChapters + ' chapter book(s); skipping upload + wizard');
            return;
        }

        docName = 'HarlotsEight-UX-' + Date.now().toString(36);
        docFileName = docName + '.docx';
        docObjectId = await uploadManuscript(request, docFileName);

        const ranges = await getJson(request, PB_REST + '/chapter/detect-boundaries?sourceDataObjectId='
            + encodeURIComponent(docObjectId));
        expect(Array.isArray(ranges) && ranges.length >= 2, 'manuscript did not detect as chaptered').toBe(true);
        totalChapters = ranges.length;
        expectedChapters = (MAX_CHAPTERS > 0 && MAX_CHAPTERS < ranges.length) ? MAX_CHAPTERS : ranges.length;
        console.log('[ux-chapters] manuscript=' + docName + ' oid=' + docObjectId + ' detected='
            + totalChapters + ' chapters; wizard will process ' + expectedChapters);
        console.log('[ux-chapters] titles: ' + ranges.map(r => r.title).join(' | '));
        expect(ranges.filter(r => r.title == null).length, 'no untitled front-matter range expected').toBe(0);
    });

    test('wizard extracts every chapter, list bundles them, reader selects chapters, cast persisted with valid races',
        async ({ page, request }) => {
        test.skip(!LLM_ENABLED, 'PB_LLM_TESTS!=1 — skips the live-LLM chaptered extraction (run alone with --workers=1)');
        const budgetMs = (expectedChapters * PER_CHAPTER_MIN + 30) * 60 * 1000;
        test.setTimeout(budgetMs + 30 * 60 * 1000);
        console.log('[ux-chapters] extraction budget ' + fmtMin(budgetMs) + ' for ' + expectedChapters + ' chapter(s)');
        const testStart = Date.now();

        page.on('pageerror', (err) => {
            pageErrors.push(String(err && err.stack || err));
            console.log('[PAGE-ERROR] ' + (err && err.message));
        });
        page.on('console', (msg) => {
            if (msg.type() === 'error') {
                consoleErrors.push(msg.text());
                console.log('[PAGE-CONSOLE-ERROR] ' + msg.text());
            }
        });
        page.on('response', async (resp) => {
            const u = resp.url();
            if (u.includes('/olio/picture-book/') && resp.status() >= 400) {
                let body = '';
                try { body = (await resp.text()).substring(0, 500); } catch (_) { body = '<unreadable>'; }
                console.log('[NETWORK ' + resp.status() + '] ' + u + ' ' + body);
            }
            // The reader must never fetch a PictureBook record through a route that cannot serve it
            // (/full on olio.pb.book 404s on the 100-argument limit). Any failing REST read is a defect.
            if (u.includes('/rest/model/') && resp.request().method() === 'GET' && resp.status() >= 400) {
                failedReads.push(resp.status() + ' ' + u);
            }
        });
        // Evidence: the chatConfig name the wizard actually sends on each extraction / create call.
        page.on('request', (req) => {
            if (req.method() === 'POST' && /\/(extract-scenes-only|create-from-scenes)/.test(req.url())) {
                let cfg = '?';
                try { cfg = JSON.parse(req.postData() || '{}').chatConfig; } catch (_) { cfg = '<unparsed>'; }
                console.log('[ux-chapters] wizard POST ' + req.url().replace(/.*\/olio\/picture-book\//, '') .replace(/\?.*$/, '')
                    + ' chatConfig=' + cfg);
            }
        });

        // Test-side bound on the run: hand the wizard only the first N detected chapters.
        if (MAX_CHAPTERS > 0 && !EXISTING_SERIES) {
            await page.route('**/chapter/detect-boundaries*', async (route) => {
                const resp = await route.fetch();
                let json = await resp.json();
                if (Array.isArray(json) && json.length > MAX_CHAPTERS) json = json.slice(0, MAX_CHAPTERS);
                await route.fulfill({ response: resp, json });
            });
        }

        await loginSharedUser(page, creds);

        if (!EXISTING_SERIES) {
        // ── Step 1: open the wizard on the uploaded manuscript, pick the run's chatConfig, Extract ──
        await page.goto('/#!/picture-book/' + docObjectId, { timeout: 45000 });
        const generateBtn = page.locator('button:has-text("Generate Picture Book")');
        await expect(generateBtn).toBeVisible({ timeout: 30000 });
        await generateBtn.click();
        await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: 10000 });
        const dialog = page.locator('[role="dialog"]').first();
        // The wizard must be seeded with the manuscript's real name — opening this route on a fresh
        // document used to hand it the 'Loading...' placeholder, which then became the series title.
        await expect(dialog).toContainText('Source: ' + docFileName, { timeout: 10000 });
        await expect(dialog).not.toContainText('Loading...');
        await pickChatConfig(page, dialog, chatConfigName);
        await screenshot(page, 'ux-chapters-step1');

        const extractBtn = page.locator('button:has-text("Extract")').first();
        await expect(extractBtn).toBeEnabled({ timeout: 5000 });
        const startedAt = Date.now();
        await extractBtn.click();

        // ── Wait for the fan-out to finish: the wizard closes and lands on the workflow route.
        //    Fail FAST on a rendered extraction error, or on the dialog vanishing without navigating
        //    (that is exactly the reported "script error and disappears").
        const errorRe = /Extraction stopped at chapter|No chapters were saved|Extraction was cancelled|No scenes were extracted from any chapter|Extraction failed/;
        let lastProgress = '';
        let landed = false;
        let passageRetries = 0;
        for (;;) {
            if (/\/picture-book\/[^/]+\/workflow/.test(page.url())) { landed = true; break; }
            const dlg = page.locator('[role="dialog"]').first();
            const dialogVisible = await dlg.isVisible().catch(() => false);
            if (dialogVisible) {
                // A run that finished with ANY missing/incomplete chapter now stays in the wizard on a
                // persistent report instead of navigating. For this spec that is a failure with the
                // report's own text — never a silent wait until the budget runs out — with ONE
                // exception, the product's own recovery path: when every reported problem is a
                // per-passage REQUEST TIMEOUT (a live-LLM sampling loop, e.g. 17 369 chars of
                // paraphrased scene repeats in 900 s on 2026-10-12 — content non-determinism, not a
                // product fault), click the wizard's "Retry N failed passages" button, at most
                // PB_UX_MAX_PASSAGE_RETRIES times (default 1), exactly as a user would. Any other
                // problem kind (refusal, no-json, parse, stopped-early, ...) still fails here.
                const summary = page.locator('[data-pb-chapter-summary]');
                if (await summary.isVisible().catch(() => false)) {
                    const headline = ((await page.locator('[data-pb-chapter-summary-headline]').textContent({ timeout: 3000 }).catch(() => '')) || '').trim();
                    const problems = await page.locator('[data-pb-chapter-problem]').allTextContents().catch(() => []);
                    const openSeries = page.locator('[data-pb-open-series]');
                    const busy = await openSeries.isDisabled().catch(() => false);   // `extracting` disables every summary button
                    if (busy) {
                        const txt = (await dlg.textContent({ timeout: 3000 }).catch(() => '')) || '';
                        const prog = (txt.match(/Retrying failed passages[^.]{0,80}/) || [''])[0].trim();
                        if (prog && prog !== lastProgress) {
                            lastProgress = prog;
                            console.log('[ux-chapters] +' + fmtMin(Date.now() - startedAt) + ' ' + prog);
                        }
                    } else if (problems.length === 0) {
                        console.log('[ux-chapters] +' + fmtMin(Date.now() - startedAt) + ' summary clear after '
                            + passageRetries + ' retry(ies): "' + headline + '" — opening the series');
                        await screenshot(page, 'ux-chapters-summary-recovered');
                        await openSeries.click();
                    } else {
                        const allTimeouts = problems.every(p => TIMEOUT_PROBLEM_RE.test(p));
                        const retryBtn = page.locator('[data-pb-retry-failed-chapters]');
                        if (allTimeouts && passageRetries < MAX_PASSAGE_RETRIES && (await retryBtn.count()) > 0) {
                            passageRetries++;
                            console.log('[ux-chapters] +' + fmtMin(Date.now() - startedAt) + ' "' + headline + '" — every problem is a '
                                + 'request timeout; clicking "' + ((await retryBtn.textContent()) || '').trim() + '" (retry '
                                + passageRetries + '/' + MAX_PASSAGE_RETRIES + ') :: ' + problems.join(' | '));
                            await screenshot(page, 'ux-chapters-summary-retry-' + passageRetries);
                            await retryBtn.click();
                            lastProgress = '';
                        } else {
                            await screenshot(page, 'ux-chapters-summary-problems');
                            throw new Error('fan-out finished with problems (passage retries used: ' + passageRetries + '/'
                                + MAX_PASSAGE_RETRIES + '): ' + headline + ' :: ' + problems.join(' | '));
                        }
                    }
                } else {
                    // Bounded: the dialog can close between isVisible() and textContent().
                    const txt = (await dlg.textContent({ timeout: 3000 }).catch(() => '')) || '';
                    if (!txt && /\/picture-book\/[^/]+\/workflow/.test(page.url())) continue;
                    const m = txt.match(errorRe);
                    if (m) {
                        await screenshot(page, 'ux-chapters-extract-error');
                        throw new Error('wizard reported an extraction error: ' + txt.substring(txt.indexOf(m[0]), txt.indexOf(m[0]) + 600));
                    }
                    const prog = (txt.match(/Chapter\s+\d+\s*\/\s*\d+[^.]{0,80}/) || [''])[0].trim();
                    if (prog && prog !== lastProgress) {
                        lastProgress = prog;
                        console.log('[ux-chapters] +' + fmtMin(Date.now() - startedAt) + ' ' + prog);
                    }
                }
            } else if (Date.now() - startedAt > 15000) {
                await screenshot(page, 'ux-chapters-dialog-vanished');
                throw new Error('wizard dialog disappeared without navigating to the workflow route; url=' + page.url()
                    + ' pageErrors=' + JSON.stringify(pageErrors));
            }
            if (Date.now() - startedAt > budgetMs) {
                await screenshot(page, 'ux-chapters-extract-timeout');
                throw new Error('extraction did not finish within ' + fmtMin(budgetMs) + '; last progress: ' + lastProgress);
            }
            await page.waitForTimeout(15000);
        }
        console.log('[ux-chapters] fan-out durationMs = ' + fmtMs(Date.now() - startedAt) + ' (passage retries: '
            + passageRetries + ') -> ' + page.url());
        expect(landed).toBe(true);
        await expect(page.locator('[data-view-mode-toggle]')).toBeVisible({ timeout: 30000 });
        await screenshot(page, 'ux-chapters-workflow-landing');
        await page.locator('[data-view-mode="series"]').click();
        await page.waitForTimeout(3000);
        await screenshot(page, 'ux-chapters-workflow-series-view');
        } // !EXISTING_SERIES

        // ── REST truth: the series has every chapter, none failed, each with scenes ────────────
        await restLogin(request, creds);
        const allBooks = await getJson(request, PB_REST + '/books');
        const mine = allBooks.filter(b => b.seriesName === docFileName);
        expect(mine.length, 'books in series "' + docFileName + '" (series names seen: '
            + JSON.stringify([...new Set(allBooks.map(b => b.seriesName))]) + ')').toBe(expectedChapters);
        const seriesOid = mine[0].seriesObjectId;
        expect(seriesOid, 'seriesObjectId on listBooks DTO').toBeTruthy();
        const seriesBooks = await getJson(request, PB_REST + '/series/' + seriesOid + '/books');
        console.log('[ux-chapters] series books: ' + JSON.stringify(seriesBooks.map(b =>
            ({ ch: b.chapter, slug: b.slug, status: b.bookStatus, title: b.title || b.description }))));
        expect(seriesBooks.length).toBe(expectedChapters);
        const chapterNums = seriesBooks.map(b => Number(b.chapter)).sort((a, b) => a - b);
        expect(chapterNums).toEqual(Array.from({ length: expectedChapters }, (_, i) => i + 1));

        const sceneCounts = {};
        for (const b of seriesBooks) {
            const scenes = await getJson(request, PB_REST + '/' + b.objectId + '/scenes');
            const list = Array.isArray(scenes) ? scenes : (scenes && scenes.sceneList) || [];
            sceneCounts[b.chapter] = list.length;
            // PbBookUtil creates every book as DRAFT and nothing in the wizard pipeline promotes it;
            // the only statuses that mean the chapter did not make it are FAILED / UNKNOWN.
            expect(['failed', 'unknown'], 'chapter ' + b.chapter + ' status ' + b.bookStatus)
                .not.toContain(String(b.bookStatus).toLowerCase());
            expect(list.length, 'chapter ' + b.chapter + ' has scenes').toBeGreaterThan(0);
        }
        console.log('[ux-chapters] scenes per chapter: ' + JSON.stringify(sceneCounts));

        // ── (a) Book list: ONE bundled series card, N chapters inside ───────────────────────────
        await page.goto('/#!/picture-book', { timeout: 45000 });
        await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });
        const card = page.locator('[data-pb2-series="' + seriesOid + '"]');
        await expect(card).toBeVisible({ timeout: 30000 });
        const header = card.locator('[data-pb2-series-header]');
        const headerText = (await header.textContent()) || '';
        console.log('[ux-chapters] series card header: ' + headerText.trim());
        expect(headerText).toContain(expectedChapters + ' chapter');
        // "· M with scenes" is rendered ONLY when some chapter has no scenes — its absence means all do.
        expect(headerText, 'every chapter should have scenes').not.toContain('with scenes');
        // Chapters must NOT appear as standalone top-level books: while the series card is collapsed,
        // no chapter row exists anywhere in the list.
        for (const b of seriesBooks) {
            await expect(page.locator('[data-pb2-book-list] [data-pb2-book="' + b.objectId + '"]'),
                'chapter ' + b.chapter + ' listed as an individual book').toHaveCount(0);
        }
        await screenshot(page, 'ux-chapters-book-list-collapsed');

        const chaptersBox = card.locator('[data-pb2-series-chapters]');
        await header.click();
        await expect(chaptersBox).toBeVisible({ timeout: 10000 });
        const rows = chaptersBox.locator('[data-pb2-book]');
        await expect(rows).toHaveCount(expectedChapters, { timeout: 10000 });
        for (const b of seriesBooks) {
            await expect(chaptersBox.locator('[data-pb2-book="' + b.objectId + '"][data-pb2-chapter="' + b.chapter + '"]'))
                .toHaveCount(1);
        }
        console.log('[ux-chapters] chapter rows: ' + JSON.stringify(await rows.locator('div.font-medium').allTextContents()));
        await screenshot(page, 'ux-chapters-book-list-bundled');

        // ── (b) Reader: chapter <select> lists every chapter and switches between them ─────────
        await rows.first().click();
        await page.waitForURL(/\/picture-book\/v2\//, { timeout: 30000 });
        const select = page.locator('select[data-pb2-chapter-select]');
        await expect(select).toBeVisible({ timeout: 30000 });
        const options = select.locator('option');
        await expect(options).toHaveCount(expectedChapters, { timeout: 10000 });
        const labels = await options.allTextContents();
        console.log('[ux-chapters] chapter select: ' + JSON.stringify(labels));
        const firstUrl = page.url();
        const secondOid = await options.nth(1).getAttribute('value');
        await select.selectOption(secondOid);
        await page.waitForURL((u) => u.toString().includes(secondOid), { timeout: 30000 });
        expect(page.url()).not.toBe(firstUrl);
        await expect(page.locator('select[data-pb2-chapter-select]')).toHaveValue(secondOid, { timeout: 30000 });
        await screenshot(page, 'ux-chapters-reader-chapter2');

        // ── (c) Cast: real characters persisted; race/ethnicity read straight off the records ──
        const roster = new Map();
        for (const b of seriesBooks) {
            const chars = await getJson(request, PB_REST + '/' + b.objectId + '/characters');
            for (const c of (Array.isArray(chars) ? chars : [])) {
                if (!roster.has(c.objectId)) roster.set(c.objectId, { name: c.name, chapters: [] });
                roster.get(c.objectId).chapters.push(b.chapter);
            }
        }
        console.log('[ux-chapters] distinct characters across ' + expectedChapters + ' chapter(s): ' + roster.size);
        expect(roster.size, 'characters were created').toBeGreaterThan(0);

        const raceTally = {};
        const invalidRace = [];
        for (const [oid, info] of roster) {
            const rec = await searchOne(request, 'olio.charPerson', [
                { name: 'objectId', comparator: 'EQUALS', value: oid },
                { name: 'organizationId', comparator: 'EQUALS', value: orgId }
            ], ['id', 'objectId', 'name', 'race', 'raceLabel', 'ethnicity', 'gender', 'age']);
            expect(rec, 'charPerson ' + info.name + ' readable as the test user').toBeTruthy();
            const race = Array.isArray(rec.race) ? rec.race : [];
            const eth = Array.isArray(rec.ethnicity) ? rec.ethnicity : [];
            // Structural contract: charPerson.race holds RaceEnumType constant NAMES, nothing else.
            for (const r of race) if (!RACE_CODES.includes(String(r))) invalidRace.push(rec.name + ':' + r);
            const key = race.length ? race.join('+') : '(none stated)';
            raceTally[key] = (raceTally[key] || 0) + 1;
            console.log('[ux-chapters] CHAR ' + rec.name + ' | gender=' + rec.gender + ' age=' + rec.age
                + ' | race=' + JSON.stringify(race) + (rec.raceLabel ? ' raceLabel=' + JSON.stringify(rec.raceLabel) : '')
                + ' ethnicity=' + JSON.stringify(eth) + ' | ch ' + info.chapters.join(','));
        }
        console.log('[ux-chapters] race tally: ' + JSON.stringify(raceTally)
            + ' (which labels the live LLM chose is logged for inspection, not asserted)');
        expect(invalidRace, 'race entries that are not RaceEnumType constant names').toEqual([]);

        // ── No uncaught script error anywhere in the run ───────────────────────────────────────
        if (consoleErrors.length) console.log('[ux-chapters] console errors (' + consoleErrors.length + '): '
            + JSON.stringify(consoleErrors.slice(0, 20)));
        expect(failedReads, 'REST reads that failed while using the reader').toEqual([]);
        expect(pageErrors, 'uncaught page errors during the run').toEqual([]);
        console.log('[ux-chapters] total durationMs = ' + fmtMs(Date.now() - testStart));
    });
});
