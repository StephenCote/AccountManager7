/**
 * PictureBook health check, repair, orphan cleanup and complete delete
 * (route #!/picture-book + #!/picture-book/v2/:id, src/features/pictureBook.js,
 *  REST /olio/picture-book/health, /{id}/health, /orphans, /orphans/purge, /orphans/org, /{id}/reset).
 *
 * Driver: fixes had been landing as per-environment DB edits, so other environments stayed broken.
 * Everything here heals through the server's own code paths and is asserted against the live stack:
 *
 *  - a fresh book is CLEAN: GET /{id}/health has no errors/warnings and the PB2 reader shows no banner;
 *  - a real healable finding is manufactured (an extraction checkpoint whose source document is then
 *    deleted → CHECKPOINT_DANGLING), the list view's "Check health" shows it, "Repair" removes it;
 *  - the same manufactured leftover is also an ORPHAN_CHECKPOINT: "Clean up orphans" lists it in the
 *    destructive confirm and purges it;
 *  - deleting a book through DELETE /{id}/reset leaves nothing of it behind in GET /orphans, and the
 *    second reset 404s;
 *  - the whole-organization orphan routes are admin-only (403 for the shared user).
 *
 * The shared user accumulates leftovers from earlier sessions, so nothing here asserts "clean" or a
 * total count — every assertion is about the records this spec created. Note that "Repair" and
 * "Clean up orphans" act on ALL of the shared user's own findings/orphans (that is their contract);
 * the leftovers are, by definition, junk with no live book behind them.
 *
 * LLM/SD-free (POST /chapter creates an empty book; checkpoints are forged notes), so these run in
 * the default suite. They FAIL — never skip — when the stack predates the health routes.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookHealth.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, deleteObject } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const GROUP_PATH = '~/PbHealthE2E';

// A POST /login on a context that already holds a session does NOT switch users — the first
// session wins. Always log out before logging in on the same context.
async function restLogin(ctx) {
    await ctx.get(REST + '/logout');
    const resp = await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

// Canonical WS-stub + login pattern (chapBook.spec.js) — Docker's nginx strips the session cookie on
// the WS upgrade, so without this stub Tomcat closes the socket, forceLogin() fires, and the app
// redirects to #!/sig.
async function loginAsSharedUser(page) {
    await restLogin(page.request);
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
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function openSelector(page) {
    await loginAsSharedUser(page);
    await page.evaluate(() => { window.location.hash = '!/picture-book'; });
    // The PB2 list only renders when the user has books; the health panel always does.
    await expect(page.locator('[data-pb-health-panel]')).toBeVisible({ timeout: 30000 });
}

async function openReader(page, bookObjectId) {
    await loginAsSharedUser(page);
    await page.evaluate((id) => { window.location.hash = '!/picture-book/v2/' + id; }, bookObjectId);
}

const toCleanupBooks = [];
const toCleanupNotes = [];

async function createBook(request, slug, title) {
    const resp = await request.post(PB + '/chapter', { data: { slug, title } });
    expect(resp.ok(), 'POST /chapter ' + slug + ' failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const b = await resp.json();
    expect(b && b.bookObjectId, 'POST /chapter ' + slug + ' returned no bookObjectId').toBeTruthy();
    toCleanupBooks.push(b.bookObjectId);
    return b.bookObjectId;
}

async function createNoteIn(request, dir, name, text) {
    const resp = await request.post(REST + '/model', {
        data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name, text }
    });
    expect(resp.ok(), 'POST data.note ' + name + ' failed: ' + resp.status() + ' ' + await resp.text()).toBe(true);
    const created = await resp.json();
    expect(created && created.objectId, 'create response carried no objectId for ' + name).toBeTruthy();
    toCleanupNotes.push(created.objectId);
    return created;
}

/** The same field set saveExtractCheckpoint persists (scene payloads stripped of sourceText). */
function checkpointJson(workObjectId) {
    return JSON.stringify({
        workObjectId,
        textHash: 'e2e-health-' + workObjectId,
        chunkSize: 8000, overlap: 400, totalChunks: 6, chunksProcessed: 2,
        startOffset: null, endOffset: null,
        updatedAt: new Date().toISOString(),
        scenes: [{ title: 'Scene 1', summary: 'E2E health seeded scene', characters: ['A'], sourceChunk: 0 }],
        failedExtractions: []
    });
}

/**
 * Manufacture a real dangling checkpoint: a source note, its .pbExtractProgress.<oid> checkpoint, then
 * the source note is deleted. GET /extract-checkpoints must report it as workMissing before any
 * health/orphan assertion is made on it, so a later failure is about health, not the fixture.
 */
async function forgeDanglingCheckpoint(request, tag) {
    const dir = await ensurePath(request, 'auth.group', 'data', GROUP_PATH);
    expect(dir && dir.id, 'could not make ' + GROUP_PATH).toBeTruthy();
    const doomed = await createNoteIn(request, dir, 'pb-health-e2e-' + tag + '.txt', 'to be deleted');
    const ckpt = await createNoteIn(request, dir, '.pbExtractProgress.' + doomed.objectId, checkpointJson(doomed.objectId));
    await deleteObject(request, 'data.note', doomed.objectId);

    const list = await (await request.get(PB + '/extract-checkpoints')).json();
    const row = list.find(r => r.workObjectId === doomed.objectId);
    expect(row, 'forged checkpoint not listed by GET /extract-checkpoints').toBeTruthy();
    expect(row.workMissing, 'forged checkpoint must be workMissing').toBe(true);
    return { docObjectId: doomed.objectId, noteObjectId: ckpt.objectId };
}

async function getJson(request, path, expectStatus) {
    const resp = await request.get(path);
    expect(resp.status(), 'GET ' + path).toBe(expectStatus === undefined ? 200 : expectStatus);
    return resp.status() === 200 ? resp.json() : null;
}

function findingsFor(report, code, pred) {
    return (report.findings || []).filter(f => f && f.code === code && (!pred || pred(f)));
}

function orphanItems(scan, code) {
    const cat = (scan.categories || []).find(c => c && c.code === code);
    return cat && Array.isArray(cat.items) ? cat.items : [];
}

/** Every orphan item (any category) that mentions the given text in slug/name/path/reason. */
function orphanItemsMentioning(scan, text) {
    const out = [];
    for (const cat of (scan.categories || [])) {
        for (const it of (cat.items || [])) {
            const hay = [it.slug, it.name, it.path, it.reason].filter(Boolean).join(' ');
            if (hay.includes(text)) out.push(Object.assign({ category: cat.code }, it));
        }
    }
    return out;
}

test.describe.serial('PictureBook — health check, repair, orphan cleanup, complete delete', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request);
        // Fail loudly, not silently, when the deployed stack predates the health routes (an old image
        // 404s with Tomcat's HTML page, which would otherwise read like a product bug further down).
        // Probe the admin-only org scope: it answers 403 in milliseconds when the routes exist, where
        // GET /health runs the full per-book audit (tens of seconds once the shared user has accumulated
        // books) and blew the 60 s hook timeout on 2026-10-07.
        const probe = await request.get(PB + '/orphans/org');
        if (probe.status() === 404) {
            throw new Error('GET ' + PB + '/orphans/org returned 404 — the running Service7 image predates the health/orphan routes. Rebuild and redeploy the Docker image, then re-run.');
        }
        expect(probe.status(), 'GET /orphans/org as the shared user').toBe(403);
    });

    test.afterAll(async ({ request }) => {
        try {
            await restLogin(request);
            for (const oid of toCleanupBooks) {
                try { await request.delete(PB + '/' + oid + '/reset'); } catch (_) {}
            }
            for (const oid of toCleanupNotes) {
                await deleteObject(request, 'data.note', oid).catch(() => {});
            }
        } catch (_) {}
    });

    // ── 1. A freshly created book is clean, over REST and in the reader. ────────────────────────
    test('a fresh book reports no errors or warnings and the reader shows no health banner', async ({ page, request }) => {
        await restLogin(request);
        const tag = Date.now().toString(36);
        const slug = 'e2e-pbhealth-clean-' + tag;
        const oid = await createBook(request, slug, 'PBHealth Clean ' + tag);

        const report = await getJson(request, PB + '/' + oid + '/health');
        expect(report.scope).toBe('book');
        expect(report.bookObjectId).toBe(oid);
        expect(report.slug).toBe(slug);
        expect(Array.isArray(report.findings), 'findings array').toBe(true);
        expect(report.summary && typeof report.summary.errors === 'number', 'summary.errors').toBe(true);
        const problems = (report.findings || []).filter(f => f && String(f.severity).toUpperCase() !== 'INFO');
        expect(problems, 'a fresh book must have no ERROR/WARN findings: ' + JSON.stringify(problems)).toEqual([]);
        expect(report.summary.errors).toBe(0);
        expect(report.summary.warnings).toBe(0);

        // The org view runs the same audit per book and tags findings with the slug — ours must be clean there too.
        const org = await getJson(request, PB + '/health');
        expect(org.scope).toBe('org');
        const ours = (org.findings || []).filter(f => f && f.refs && f.refs.slug === slug && String(f.severity).toUpperCase() !== 'INFO');
        expect(ours, 'org view flagged the fresh book: ' + JSON.stringify(ours)).toEqual([]);

        // Repairing a clean book is a no-op, not an error.
        const heal = await request.post(PB + '/' + oid + '/health/heal', { data: {} });
        expect(heal.status(), 'POST /{id}/health/heal on a clean book').toBe(200);
        const healed = await heal.json();
        // JSONUtil.exportObject serializes with Include.NON_EMPTY, so an empty `healed` list is OMITTED on
        // the wire (the UI's "N repairs applied" toast guards for this the same way). Absent == nothing healed.
        expect(healed.healed || [], 'nothing to heal on a fresh book').toEqual([]);
        expect(healed.skipped || [], 'nothing skipped on a fresh book').toEqual([]);
        expect(healed.summary.errors).toBe(0);

        // An unreadable / nonexistent book is a 404, not a report.
        await getJson(request, PB + '/00000000-0000-0000-0000-000000000000/health', 404);

        await openReader(page, oid);
        const empty = page.locator('[data-pb2-empty]');
        await expect(empty, 'reader empty state').toBeVisible({ timeout: 30000 });
        await expect(empty).toHaveText('No scenes in this book yet.');
        await expect(page.locator('[data-pb2-empty-stale]'), 'no stale-workflow wording on a clean book').toHaveCount(0);
        await expect(page.locator('[data-pb2-health-banner]'), 'no health banner on a clean book').toHaveCount(0);
        await page.screenshot({ path: 'test-results/pb-health-reader-clean.png' });
    });

    // ── 2. A real healable finding shows up in the list view's health check and Repair removes it. ──
    test('Check health lists a dangling checkpoint and Repair discards it through the server', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'repair-' + Date.now().toString(36);
        const { docObjectId, noteObjectId } = await forgeDanglingCheckpoint(request, tag);

        // REST first: the org report carries the finding, healable, pointing at the checkpoint note.
        const before = await getJson(request, PB + '/health');
        const mine = findingsFor(before, 'CHECKPOINT_DANGLING', f => f.refs && f.refs.workObjectId === docObjectId);
        expect(mine.length, 'CHECKPOINT_DANGLING for ' + docObjectId + ' in GET /health').toBe(1);
        expect(mine[0].severity).toBe('WARN');
        expect(mine[0].healable).toBe(true);
        expect(mine[0].refs.objectId).toBe(noteObjectId);
        expect(before.summary.healable, 'summary.healable counts it').toBeGreaterThan(0);

        await openSelector(page);
        // Nothing is shown until the user asks; Repair does not exist yet.
        await expect(page.locator('[data-pb-health-findings], [data-pb-health-clean]')).toHaveCount(0);
        await expect(page.locator('[data-pb-health-repair]')).toHaveCount(0);

        await page.locator('[data-pb-health-check]').click();
        const row = page.locator('[data-pb-health-finding="CHECKPOINT_DANGLING"]').filter({ hasText: docObjectId });
        await expect(row, 'dangling-checkpoint finding row for ' + docObjectId).toBeVisible({ timeout: 60000 });
        await expect(row).toHaveAttribute('data-pb-health-severity', 'WARN');
        await expect(row).toContainText('repairable');
        const repair = page.locator('[data-pb-health-repair]');
        await expect(repair).toBeVisible();
        await expect(repair, 'Repair must be enabled when a healable finding exists').toBeEnabled();
        await page.screenshot({ path: 'test-results/pb-health-list-finding.png' });

        // Repair → non-destructive confirm → server heals → the post-heal re-audit replaces the list.
        await repair.click();
        const dialog = page.locator('[role="dialog"]');
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog).toContainText('Repair Picture Books');
        await dialog.locator('.am7-dialog-footer button.am7-dialog-btn-primary').click();

        const toast = page.locator('.toast-box').filter({ hasText: /\d+ repairs? applied/ });
        await expect(toast, 'no "N repairs applied" toast').toBeVisible({ timeout: 60000 });
        await expect(page.locator('.toast-box').filter({ hasText: 'Repair failed' })).toHaveCount(0);
        await expect(row, 'the dangling checkpoint is still reported after Repair').toHaveCount(0, { timeout: 15000 });
        await page.screenshot({ path: 'test-results/pb-health-list-after-repair.png' });

        // The server agrees: the checkpoint note is gone, the finding is gone.
        const list = await (await request.get(PB + '/extract-checkpoints')).json();
        expect(list.find(r => r.workObjectId === docObjectId), 'checkpoint still listed after Repair').toBeUndefined();
        const after = await getJson(request, PB + '/health');
        expect(findingsFor(after, 'CHECKPOINT_DANGLING', f => f.refs && f.refs.workObjectId === docObjectId),
            'CHECKPOINT_DANGLING still in GET /health after Repair').toEqual([]);
    });

    // ── 3. The same leftover is an orphan: scan lists it, the destructive confirm names it, purge removes it. ──
    test('Clean up orphans lists a dangling checkpoint and purges it', async ({ page, request }) => {
        await restLogin(request);
        const tag = 'orphan-' + Date.now().toString(36);
        const { docObjectId, noteObjectId } = await forgeDanglingCheckpoint(request, tag);

        const scan = await getJson(request, PB + '/orphans');
        expect(scan.scope).toBe('own');
        expect(Array.isArray(scan.categories)).toBe(true);
        const mine = orphanItems(scan, 'ORPHAN_CHECKPOINT').filter(it => it.objectId === noteObjectId);
        expect(mine.length, 'ORPHAN_CHECKPOINT item for note ' + noteObjectId + ' in GET /orphans').toBe(1);
        expect(mine[0].model).toBe('data.note');
        expect(mine[0].name).toBe('.pbExtractProgress.' + docObjectId);
        expect(mine[0].reason).toContain(docObjectId);
        expect(scan.total, 'scan.total counts it').toBeGreaterThan(0);

        await openSelector(page);
        await page.locator('[data-pb-orphans-scan]').click();
        const dialog = page.locator('[role="dialog"]');
        await expect(dialog, 'destructive confirm after the scan').toBeVisible({ timeout: 60000 });
        await expect(dialog).toContainText('Clean Up Orphans');
        await expect(dialog).toContainText('ORPHAN_CHECKPOINT');
        await expect(dialog).toContainText('Live books are never touched');
        await page.screenshot({ path: 'test-results/pb-health-orphans-confirm.png' });
        await dialog.locator('.am7-dialog-footer button.am7-dialog-btn-destructive').click();

        const toast = page.locator('.toast-box').filter({ hasText: /\d+ orphans? deleted/ });
        await expect(toast, 'no "N orphans deleted" toast').toBeVisible({ timeout: 60000 });
        await expect(page.locator('.toast-box').filter({ hasText: 'Orphan cleanup failed' })).toHaveCount(0);
        await page.screenshot({ path: 'test-results/pb-health-orphans-after-purge.png' });

        const after = await getJson(request, PB + '/orphans');
        expect(orphanItems(after, 'ORPHAN_CHECKPOINT').filter(it => it.objectId === noteObjectId),
            'the purged checkpoint is still an orphan').toEqual([]);
        const list = await (await request.get(PB + '/extract-checkpoints')).json();
        expect(list.find(r => r.workObjectId === docObjectId), 'checkpoint still listed after purge').toBeUndefined();
    });

    // ── 4. Delete removes every known created entity: nothing of the book is left for the orphan scan. ──
    test('deleting a book leaves no orphans behind and a second delete 404s', async ({ request }) => {
        await restLogin(request);
        const tag = Date.now().toString(36);
        const slug = 'e2e-pbhealth-del-' + tag;
        const oid = await createBook(request, slug, 'PBHealth Del ' + tag);

        // Present before: a live book is never an orphan.
        const scanBefore = await getJson(request, PB + '/orphans');
        expect(orphanItemsMentioning(scanBefore, slug), 'a live book must not be flagged as an orphan').toEqual([]);

        const del = await request.delete(PB + '/' + oid + '/reset');
        expect(del.ok(), 'DELETE /{id}/reset failed: ' + del.status() + ' ' + (await del.text())).toBe(true);
        const body = await del.json();
        expect(body.reset, 'reset:true').toBe(true);

        const scanAfter = await getJson(request, PB + '/orphans');
        const leftovers = orphanItemsMentioning(scanAfter, slug);
        expect(leftovers, 'delete left orphans behind: ' + JSON.stringify(leftovers)).toEqual([]);
        const org = await getJson(request, PB + '/health');
        expect((org.findings || []).filter(f => f && f.refs && f.refs.slug === slug), 'deleted book still audited').toEqual([]);

        await getJson(request, PB + '/' + oid + '/health', 404);
        const again = await request.delete(PB + '/' + oid + '/reset');
        expect(again.status(), 'a second reset of the deleted book should 404').toBe(404);
    });

    // ── 5. Whole-organization orphan scope is admin-only. ───────────────────────────────────────
    test('the whole-organization orphan routes are forbidden for a non-admin user', async ({ request }) => {
        await restLogin(request);
        const scan = await request.get(PB + '/orphans/org');
        expect(scan.status(), 'GET /orphans/org as the shared user').toBe(403);
        const purge = await request.post(PB + '/orphans/org/purge', { data: {} });
        expect(purge.status(), 'POST /orphans/org/purge as the shared user').toBe(403);
        // Own scope stays available.
        await getJson(request, PB + '/orphans');
    });
});
