/**
 * Chaptered-manuscript PictureBook flow, driven THROUGH THE BROWSER against the live Docker stack.
 *
 * Stephen's report (2026-09-24): after the last chapter the wizard threw a script error and vanished,
 * more than half the chapters were missing, and the chapters were listed as separate books. Three
 * acceptance criteria, each asserted here in the real Ux:
 *   (a) a list of books — a chaptered manuscript is ONE bundled series card, not N books;
 *   (b) opening a book with chapters lets you SELECT which chapter (reader chapter <select>);
 *   (c) characters are defined from the text — race/ethnicity are read back off the persisted
 *       olio.charPerson records and printed for inspection against the manuscript.
 * Plus: NO uncaught page error anywhere in the run (the "script error and disappears" symptom).
 *
 * Nothing is stubbed: live Ollama (192.168.1.42, goekdenizguelmez/JOSIEFIED-Qwen3:8b), the real
 * HarlotsEight_Vol1_SM.docx, the shared non-admin test user. The ONLY test-side hook is an optional
 * truncation of the detect-boundaries RESPONSE to the first PB_UX_MAX_CHAPTERS chapters, so a first
 * run is bounded to hours rather than a day; PB_UX_MAX_CHAPTERS=0 runs the whole manuscript.
 *
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test \
 *       e2e/pictureBookChapteredManuscriptUx.spec.js --workers=1 --project=chromium
 *   PB_UX_MAX_CHAPTERS=0 ... (full manuscript)
 *   PB_UX_EXISTING_SERIES="<seriesName or seriesObjectId>" ... (skip extraction; re-check an existing series)
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';
const ORG_PATH = '/Development';

const DOCX_PATH = process.env.PB_UX_DOCX
    || path.resolve(__dirname, '../../AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx');
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

const LLM_SERVER = process.env.PB_UX_LLM_SERVER || 'http://192.168.1.42:11434';
const LLM_MODEL = process.env.PB_UX_LLM_MODEL || 'goekdenizguelmez/JOSIEFIED-Qwen3:8b';
const CHAT_CONFIG_NAME = 'contentAnalysis';

const MAX_CHAPTERS = parseInt(process.env.PB_UX_MAX_CHAPTERS || '6', 10); // 0 = whole manuscript
const PER_CHAPTER_MIN = parseInt(process.env.PB_UX_PER_CHAPTER_MIN || '45', 10);
// Resume mode: seriesName or seriesObjectId of a series an earlier run already extracted. Skips the
// upload + wizard fan-out and runs only the list / reader / cast assertions against it.
const EXISTING_SERIES = process.env.PB_UX_EXISTING_SERIES || null;

function b64(str) { return Buffer.from(str).toString('base64'); }
function fmtMin(ms) { return (ms / 60000).toFixed(1) + ' min'; }

async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: ORG_PATH,
            name: SHARED_USER,
            credential: b64(SHARED_PASSWORD),
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

/**
 * The wizard's Step 1 auto-resolves a chatConfig named "contentAnalysis"; ChatUtil.resolveConfig
 * checks the user's OWN ~/Chat before the shared library, so a config owned by the shared user wins
 * over admin's (which points at a dead LiteLLM). Idempotent: reuses an existing one, re-pointing its
 * model if it differs.
 */
async function ensureContentAnalysisConfig(request) {
    const chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
    expect(chatDir && chatDir.id, 'could not ensure ~/Chat: ' + JSON.stringify(chatDir)).toBeTruthy();
    const orgId = chatDir.organizationId;
    expect(typeof orgId, '~/Chat returned no numeric organizationId').toBe('number');

    let existing = await searchOne(request, 'olio.llm.chatConfig', [
        { name: 'name', comparator: 'EQUALS', value: CHAT_CONFIG_NAME },
        { name: 'groupId', comparator: 'EQUALS', value: chatDir.id },
        { name: 'organizationId', comparator: 'EQUALS', value: orgId }
    ], ['id', 'objectId', 'name', 'model', 'analyzeModel', 'serviceType']);

    if (existing) {
        console.log('[ux-chapters] reusing ' + CHAT_CONFIG_NAME + ' chatConfig id=' + existing.id
            + ' model=' + existing.model);
        if (existing.model !== LLM_MODEL || existing.analyzeModel !== LLM_MODEL) {
            const patch = await request.patch(REST + '/model', {
                data: {
                    schema: 'olio.llm.chatConfig', id: existing.id, objectId: existing.objectId,
                    name: existing.name, model: LLM_MODEL, analyzeModel: LLM_MODEL
                }
            });
            expect(patch.ok(), 'chatConfig model patch failed: ' + patch.status()).toBe(true);
            console.log('[ux-chapters] re-pointed chatConfig model -> ' + LLM_MODEL);
        }
        return { orgId, chatDir };
    }

    const connResp = await request.post(REST + '/model', {
        data: {
            schema: 'system.connection',
            name: CHAT_CONFIG_NAME + ' Connection',
            groupId: chatDir.id,
            groupPath: chatDir.path,
            serverUrl: LLM_SERVER,
            requestTimeout: 900
        }
    });
    const connBody = await connResp.text();
    expect(connResp.ok(), 'system.connection create failed (' + connResp.status() + '): ' + connBody).toBe(true);
    const conn = JSON.parse(connBody);
    expect(conn && conn.objectId, 'connection create returned no objectId').toBeTruthy();

    const cfgResp = await request.post(REST + '/model', {
        data: {
            schema: 'olio.llm.chatConfig',
            name: CHAT_CONFIG_NAME,
            groupId: chatDir.id,
            groupPath: chatDir.path,
            model: LLM_MODEL,
            analyzeModel: LLM_MODEL,
            serviceType: 'ollama',
            stream: false,
            connection: { schema: 'system.connection', id: conn.id, objectId: conn.objectId }
        }
    });
    const cfgBody = await cfgResp.text();
    expect(cfgResp.ok(), 'chatConfig create failed (' + cfgResp.status() + '): ' + cfgBody).toBe(true);
    console.log('[ux-chapters] created ' + CHAT_CONFIG_NAME + ' chatConfig -> ' + LLM_SERVER + ' ' + LLM_MODEL);
    return { orgId, chatDir };
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
    const resp = await request.get(url, { headers: { Accept: 'application/json' } });
    expect(resp.ok(), 'GET ' + url + ' -> ' + resp.status()).toBe(true);
    return resp.json();
}

test.describe('PictureBook chaptered manuscript — real browser flow on Docker', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

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
        test.setTimeout(240000);
        await ensureSharedTestUser(request);
        await restLoginShared(request);
        ({ orgId } = await ensureContentAnalysisConfig(request));

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

    test('wizard extracts every chapter, list bundles them, reader selects chapters, cast has text races',
        async ({ page, request }) => {
        const budgetMs = (expectedChapters * PER_CHAPTER_MIN + 30) * 60 * 1000;
        test.setTimeout(budgetMs + 30 * 60 * 1000);
        console.log('[ux-chapters] extraction budget ' + fmtMin(budgetMs) + ' for ' + expectedChapters + ' chapter(s)');

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

        // Test-side bound on the run: hand the wizard only the first N detected chapters.
        if (MAX_CHAPTERS > 0 && !EXISTING_SERIES) {
            await page.route('**/chapter/detect-boundaries*', async (route) => {
                const resp = await route.fetch();
                let json = await resp.json();
                if (Array.isArray(json) && json.length > MAX_CHAPTERS) json = json.slice(0, MAX_CHAPTERS);
                await route.fulfill({ response: resp, json });
            });
        }

        await login(page, { org: ORG_PATH, user: SHARED_USER, password: SHARED_PASSWORD });

        if (!EXISTING_SERIES) {
        // ── Step 1: open the wizard on the uploaded manuscript and Extract ─────────────────────
        await page.goto('/#!/picture-book/' + docObjectId);
        const generateBtn = page.locator('button:has-text("Generate Picture Book")');
        await expect(generateBtn).toBeVisible({ timeout: 20000 });
        await generateBtn.click();
        await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: 10000 });
        // The wizard must be seeded with the manuscript's real name — opening this route on a fresh
        // document used to hand it the 'Loading...' placeholder, which then became the series title.
        await expect(page.locator('[role="dialog"]').first()).toContainText('Source: ' + docFileName, { timeout: 10000 });
        await expect(page.locator('[role="dialog"]').first()).not.toContainText('Loading...');
        await expect(page.locator('text=' + CHAT_CONFIG_NAME).first()).toBeVisible({ timeout: 20000 });
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
        for (;;) {
            if (/\/picture-book\/[^/]+\/workflow/.test(page.url())) { landed = true; break; }
            const dialog = page.locator('[role="dialog"]').first();
            const dialogVisible = await dialog.isVisible().catch(() => false);
            if (dialogVisible) {
                // A run that finished with ANY missing/incomplete chapter now stays in the wizard on a
                // persistent report instead of navigating. For this spec that is a failure with the
                // report's own text — never a silent wait until the budget runs out.
                const summary = page.locator('[data-pb-chapter-summary]');
                if (await summary.isVisible().catch(() => false)) {
                    const headline = (await page.locator('[data-pb-chapter-summary-headline]').textContent().catch(() => '')) || '';
                    const problems = await page.locator('[data-pb-chapter-problem]').allTextContents().catch(() => []);
                    await screenshot(page, 'ux-chapters-summary-problems');
                    throw new Error('fan-out finished with problems: ' + headline.trim() + ' :: ' + problems.join(' | '));
                }
                const txt = (await dialog.textContent().catch(() => '')) || '';
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
        console.log('[ux-chapters] fan-out finished in ' + fmtMin(Date.now() - startedAt) + ' -> ' + page.url());
        expect(landed).toBe(true);
        await expect(page.locator('[data-view-mode-toggle]')).toBeVisible({ timeout: 30000 });
        await screenshot(page, 'ux-chapters-workflow-landing');
        await page.locator('[data-view-mode="series"]').click();
        await page.waitForTimeout(3000);
        await screenshot(page, 'ux-chapters-workflow-series-view');
        } // !EXISTING_SERIES

        // ── REST truth: the series has every chapter, none failed, each with scenes ────────────
        await restLoginShared(request);
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
        await page.goto('/#!/picture-book');
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

        // ── (c) Cast: real characters, race/ethnicity read straight off the persisted records ──
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
        const rosterRecords = [];
        for (const [oid, info] of roster) {
            const rec = await searchOne(request, 'olio.charPerson', [
                { name: 'objectId', comparator: 'EQUALS', value: oid },
                { name: 'organizationId', comparator: 'EQUALS', value: orgId }
            ], ['id', 'objectId', 'name', 'race', 'ethnicity', 'gender', 'age']);
            expect(rec, 'charPerson ' + info.name + ' readable as the test user').toBeTruthy();
            const race = Array.isArray(rec.race) ? rec.race : [];
            const eth = Array.isArray(rec.ethnicity) ? rec.ethnicity : [];
            rosterRecords.push({ name: rec.name, race, ethnicity: eth });
            const key = race.length ? race.join('+') : '(none stated)';
            raceTally[key] = (raceTally[key] || 0) + 1;
            console.log('[ux-chapters] CHAR ' + rec.name + ' | gender=' + rec.gender + ' age=' + rec.age
                + ' | race=' + JSON.stringify(race) + ' ethnicity=' + JSON.stringify(eth)
                + ' | ch ' + info.chapters.join(','));
        }
        console.log('[ux-chapters] race tally: ' + JSON.stringify(raceTally));

        // ── (c) Text-faithfulness: only labels the manuscript literally uses may be persisted ──
        // Ground truth for HarlotsEight_Vol1_SM.docx (grep of the extracted text, 2026-09-25): the
        // manuscript names "fairy"/"fairies" 191 times, "monster" twice, and the colour words
        // "white"/"black"; it contains NO ethnicity label word and no elf/dwarf/vampire/robot/
        // succubus/lunatic/extraterrestrial. charPerson.race persists RaceEnumType constant NAMES.
        if (path.basename(DOCX_PATH) === 'HarlotsEight_Vol1_SM.docx') {
            const ALLOWED_RACE = { Z: 'Fairy', E: 'White', C: 'Black', M: 'Monster' };
            const offRace = [], offEth = [];
            let fairies = 0;
            for (const rec of rosterRecords) {
                for (const r of rec.race) {
                    if (!ALLOWED_RACE[r]) offRace.push(rec.name + ':' + r);
                    if (r === 'Z') fairies++;
                }
                if (rec.ethnicity.length) offEth.push(rec.name + ':' + rec.ethnicity.join('+'));
            }
            expect(offRace, 'race labels the manuscript never states').toEqual([]);
            expect(offEth, 'ethnicity labels the manuscript never states').toEqual([]);
            expect(fairies, 'at least one fairy (the text names them 191 times)').toBeGreaterThan(0);
        }

        // ── No uncaught script error anywhere in the run ───────────────────────────────────────
        if (consoleErrors.length) console.log('[ux-chapters] console errors (' + consoleErrors.length + '): '
            + JSON.stringify(consoleErrors.slice(0, 20)));
        expect(failedReads, 'REST reads that failed while using the reader').toEqual([]);
        expect(pageErrors, 'uncaught page errors during the run').toEqual([]);
    });
});
