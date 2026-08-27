/**
 * Regression proof for the previously-"fixed"-but-never-browser-verified UAT issues.
 *
 * Run against the Docker stack (NOTHING here touches the LLM at .42 or SD at .39 — pure UI/REST):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/issues1to5.spec.js --workers=1 --project=chromium
 *
 * Coverage (see src/aiDocs/IssueLog.md):
 *   Issue 1 — list picker "navigate up" works for a group-contained type (data.note).
 *   Issue 2 — importing a poem from a data.data record succeeds WITHOUT a PBAC "Group could not
 *             be found" error (olio.cb.poem created in ~/Poems).
 *   Issue 4 — after import, the "Create ChapBook" button appears AND selectedIds is populated with
 *             the imported poem id (button label reflects selectedIds.size).
 *   Issue 5 — SD config panel fresh-open defaults are covered by a Vitest unit test at
 *             src/test/sdConfigPanelDefaults.test.js (a browser open of the panel would trigger an
 *             SD/config network call, which is barred while the 6C SD-render test runs — see the
 *             report). NOT covered here.
 *
 * Never uses admin — logs in as the shared test user (ensureSharedTestUser). All poem content is
 * real corpus text by Stephen W. Cote from volatile/poemsXml/txt.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REST = '/AccountManagerService7/rest';

// ── Origin bridge (why this test.use block exists) ─────────────────────────────
// The picker flows (Issues 1/2/4) require the app's own in-browser POST /model/search. The Docker
// Service7 stack applies a CSRF/Origin allowlist that only accepts `https://localhost:9443` as a
// browser Origin (a POST with Origin `https://127.0.0.1:9443` is rejected 403; GETs are exempt).
// But Chromium resolves `localhost` to ::1 (IPv6), which the stack does not bind, so a plain
// page.goto('https://localhost:9443') aborts the connection — only 127.0.0.1 is browser-reachable.
// Resolution (test-infra only, no source/Docker change): load the app from the allowlisted
// `localhost:9443` origin, and force Chromium to resolve localhost -> 127.0.0.1 (IPv4, the address
// the stack actually binds). The in-browser POSTs then carry the allowlisted Origin AND connect.
// Verified: browser POST /login = 200 and POST /model/search/count = 200 under this bridge.
test.use({
    baseURL: 'https://localhost:9443',
    ignoreHTTPSErrors: true,
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] }
});

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const CORPUS_DIR = path.resolve(SPEC_DIR, '../../../volatile/poemsXml/txt');

function corpusPoem(rel) {
    return fs.readFileSync(path.join(CORPUS_DIR, rel), 'utf8');
}

// ── WebSocket-stubbed browser login (copied verbatim from chapBook.spec.js) ────
// Docker's nginx strips cookies on the WS upgrade so Tomcat closes the connection, which triggers
// forceLogin() and redirects to #!/sig. The stub keeps the SPA on #!/main.
async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }

    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => {
                    this.readyState = 1;
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;
    });

    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

// ── REST seed helpers (own login on the request fixture = shared-user session) ─
function b64(s) { return Buffer.from(s).toString('base64'); }

async function seedLogin(request) {
    let resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: b64('password'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('seed login failed: ' + resp.status());
}

async function makePath(request, dirPath) {
    let enc = 'B64-' + b64(dirPath).replace(/=/g, '%3D');
    let resp = await request.get(REST + '/path/make/auth.group/data/' + enc);
    let txt = await resp.text();
    try { return JSON.parse(txt); } catch { return null; }
}

async function createModel(request, data) {
    let resp = await request.post(REST + '/model', { data });
    let txt = await resp.text();
    let json = null;
    try { json = JSON.parse(txt); } catch { /* leave null */ }
    return { status: resp.status(), json, txt };
}

// ── Shared state seeded once ──────────────────────────────────────────────────
const RUN = Date.now().toString(36);
let noteName = null;      // data.note in ~/Notes (Issue 1 picker + Issue 4 import)
let dataName = null;      // data.data in ~/Data  (Issue 2 import)

test.describe('UAT regression — Issues 1, 2, 4 (browser, no LLM/SD)', () => {
    test.describe.configure({ timeout: 90000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await seedLogin(request);

        let notesDir = await makePath(request, '~/Notes');
        let dataDir = await makePath(request, '~/Data');
        expect(notesDir && notesDir.id, 'seed: ~/Notes group resolved').toBeTruthy();
        expect(dataDir && dataDir.id, 'seed: ~/Data group resolved').toBeTruthy();

        // data.note — text lives directly on the `text` field.
        noteName = 'Issue4 Maple Leaf ' + RUN;
        let noteText = corpusPoem('leaf/mapleLeaf.txt');
        let noteRes = await createModel(request, {
            schema: 'data.note',
            groupId: notesDir.id,
            groupPath: notesDir.path,
            name: noteName,
            text: noteText
        });
        expect(noteRes.status, 'seed data.note create HTTP: ' + noteRes.txt).toBe(200);

        // data.data — text lives in the dataBytesStore blob (base64 on the wire), contentType text/plain,
        // compression NONE so the import reads it straight back as UTF-8.
        dataName = 'Issue2 Grass Blade ' + RUN + '.txt';
        let dataText = corpusPoem('leaf/grassBlade.txt');
        let dataRes = await createModel(request, {
            schema: 'data.data',
            groupId: dataDir.id,
            groupPath: dataDir.path,
            name: dataName,
            contentType: 'text/plain',
            compressionType: 'none',
            dataBytesStore: b64(dataText)
        });
        expect(dataRes.status, 'seed data.data create HTTP: ' + dataRes.txt).toBe(200);
    });

    async function gotoChapBook(page) {
        await page.goto('/#!/chap-book', { timeout: 30000 });
        await expect(page.getByText('ChapBook — Poem Library')).toBeVisible({ timeout: 30000 });
    }

    // ── Issue 1 — picker navigate-up for a group-contained type ───────────────
    test('Issue 1 — picker "navigate up" changes the listed group contents', async ({ page }, testInfo) => {
        await loginAsSharedUser(page);
        await gotoChapBook(page);

        // Open the data.note picker (group-contained; starts at ~/Notes, which holds notes).
        await page.getByRole('button', { name: /Add from Note/ }).click();
        const picker = page.locator('.am7-picker-overlay');
        await expect(picker).toBeVisible();

        // ~/Notes lists at least one data.note (the seed guarantees this).
        const rows = picker.locator('tr.tabular-row');
        await expect.poll(async () => rows.count(), { timeout: 15000 }).toBeGreaterThan(0);
        const notesCount = await rows.count();

        // The navigate-up control exists (north_west icon) for this group-contained picker.
        const upBtn = picker.locator('button:has(span.material-symbols-outlined:text-is("north_west"))');
        await expect(upBtn).toBeVisible();

        await testInfo.attach('issue1-before-up', {
            body: await page.screenshot(), contentType: 'image/png'
        });

        // Navigate up to the parent group (/home/<user>): it holds NO data.note records directly, so
        // the whole listing must change to zero note rows — proving the up-nav re-listed the parent
        // container rather than no-opping. (Verified live: 10 rows at ~/Notes -> 0 rows at parent.)
        await upBtn.click();
        await expect.poll(async () => rows.count(), { timeout: 15000 }).toBe(0);
        expect(notesCount, 'notes were present at ~/Notes before navigating up').toBeGreaterThan(0);

        const shot = testInfo.outputPath('issue1-after-up.png');
        await page.screenshot({ path: shot });
        await testInfo.attach('issue1-after-up', { path: shot, contentType: 'image/png' });
    });

    // ── Issue 2 — import from data.data without PBAC "Group could not be found" ─
    test('Issue 2 — import a poem from a data.data record succeeds (no PBAC error)', async ({ page }, testInfo) => {
        await loginAsSharedUser(page);
        await gotoChapBook(page);

        // Open the data.data picker (starts at ~/Data), select the seeded data record, confirm.
        await page.getByRole('button', { name: /Add from Data/ }).click();
        const picker = page.locator('.am7-picker-overlay');
        await expect(picker).toBeVisible();

        // Filter the picker to the unique seeded name so it is the only row (robust vs. pagination).
        const filter = picker.locator('input#listFilter');
        await filter.fill(dataName);
        await filter.press('Enter');

        const dataRow = picker.locator('tr.tabular-row', { hasText: dataName });
        await expect(dataRow).toBeVisible({ timeout: 15000 });
        await dataRow.click();                                   // single-click selects (checks) the row
        await expect(dataRow).toHaveClass(/tabular-row-active/);  // confirm it is selected

        // ✓ confirm button in the picker toolbar.
        await picker.locator('button:has(span.material-symbols-outlined:text-is("check"))').click();
        await expect(picker).toBeHidden();

        // The poem-order dialog appears; confirm the import — capture the actual import API response so
        // the assertions bind to the REAL created poem (not a guessed title). The POST to
        // /rest/olio/chap-book/poems is the import call (ChapBookUtil.createPoem lands it in ~/Poems).
        const importRespP = page.waitForResponse(
            r => r.url().includes('/rest/olio/chap-book/poems') && r.request().method() === 'POST',
            { timeout: 30000 }
        );
        await page.getByRole('button', { name: /Import 1 poem\(s\) in this order/ }).click();
        const importResp = await importRespP;

        // The import itself succeeded server-side (no PBAC "Group could not be found" 500) …
        expect(importResp.status(), 'import POST /poems HTTP status').toBe(200);
        const importJson = await importResp.json();
        const importedPoems = importJson.poems || [];
        const importErrors = importJson.errors || [];
        expect(importErrors, 'import returned no errors: ' + JSON.stringify(importErrors)).toHaveLength(0);
        expect(importedPoems.length, 'import created exactly one poem').toBe(1);
        const importedTitle = importedPoems[0].title;
        const importedObjectId = importedPoems[0].objectId;
        expect(importedTitle, 'created poem carries a title').toBeTruthy();
        expect(importedObjectId, 'created poem carries an objectId').toBeTruthy();

        // … the UI reflects it: the "Create ChapBook (N)" button appears (poem auto-selected) …
        await expect(page.getByRole('button', { name: /Create ChapBook \(\d+\)/ })).toBeVisible({ timeout: 30000 });

        // … no PBAC / import error toast surfaces …
        await expect(page.getByText(/Group could not be found|Import failed|No poems were imported/i)).toHaveCount(0);

        // … and — the crux of Issue 2 — the poem actually landed in ~/Poems (the group whose resolution
        // used to fail with "Group could not be found"). Verify against the persisted record via a GET
        // (CSRF-exempt) on /full, which projects groupPath.
        const fullResp = await page.request.get(
            REST + '/model/olio.cb.poem/' + importedObjectId + '/full'
        );
        expect(fullResp.status(), 'GET created poem /full').toBe(200);
        const poemRec = await fullResp.json();
        expect(poemRec.groupPath, 'imported poem groupPath: ' + JSON.stringify(poemRec.groupPath))
            .toMatch(/\/Poems$/);

        const shot = testInfo.outputPath('issue2-data-import.png');
        await page.screenshot({ path: shot });
        await testInfo.attach('issue2-data-import', { path: shot, contentType: 'image/png' });
    });

    // ── Issue 4 — Create ChapBook button appears + selectedIds populated ──────
    test('Issue 4 — after import the "Create ChapBook" button appears with the imported poem selected', async ({ page }, testInfo) => {
        await loginAsSharedUser(page);
        await gotoChapBook(page);

        // Before import there is no selection, so no Create ChapBook button.
        await expect(page.getByRole('button', { name: /Create ChapBook \(/ })).toHaveCount(0);

        // Import the seeded note via the picker.
        await page.getByRole('button', { name: /Add from Note/ }).click();
        const picker = page.locator('.am7-picker-overlay');
        await expect(picker).toBeVisible();

        // Filter the picker to the unique seeded name so it is the only row (robust vs. pagination).
        const filter = picker.locator('input#listFilter');
        await filter.fill(noteName);
        await filter.press('Enter');

        const noteRow = picker.locator('tr.tabular-row', { hasText: noteName });
        await expect(noteRow).toBeVisible({ timeout: 15000 });
        await noteRow.click();
        await expect(noteRow).toHaveClass(/tabular-row-active/);
        await picker.locator('button:has(span.material-symbols-outlined:text-is("check"))').click();
        await expect(picker).toBeHidden();
        await page.getByRole('button', { name: /Import 1 poem\(s\) in this order/ }).click();

        // The fix: importing auto-adds the new poem id to selectedIds, so the button appears and its
        // label reflects a non-zero selection count.
        const createBtn = page.getByRole('button', { name: /Create ChapBook \(\d+\)/ });
        await expect(createBtn).toBeVisible({ timeout: 30000 });
        const label = await createBtn.innerText();
        const m = label.match(/Create ChapBook \((\d+)\)/);
        expect(m, 'button label carries a count: ' + label).toBeTruthy();
        expect(Number(m[1]), 'selectedIds populated (count >= 1)').toBeGreaterThanOrEqual(1);

        const shot = testInfo.outputPath('issue4-create-chapbook-button.png');
        await page.screenshot({ path: shot });
        await testInfo.attach('issue4-create-chapbook-button', { path: shot, contentType: 'image/png' });
    });
});
