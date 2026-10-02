/**
 * Unfinished extractions — surfacing and discarding a PictureBook extraction checkpoint that has
 * no book behind it, end to end against the Docker stack as the shared (non-admin) test user.
 *
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookCheckpoints.spec.js --workers=1 --project=chromium
 *
 * Why: a single-document extraction persists ONLY its `.pbExtractProgress.<workObjectId>` note
 * until createFromScenes runs, so an interrupted run was invisible in every book list and the only
 * way to remove it was `?fresh=true` — another LLM run. This proves the fix at every layer:
 *   REST   GET  /olio/picture-book/extract-checkpoints            (user-wide list, owner-scoped)
 *          GET  /olio/picture-book/{workObjectId}/extract-checkpoints  (per-document, for the wizard)
 *          DELETE /olio/picture-book/{workObjectId}/extract-checkpoints[?startOffset&endOffset]
 *   UI     the "Unfinished Extractions" section of #!/picture-book, its Discard button, and the
 *          Step 1 banner of the wizard opened on the document, with its own Discard.
 *
 * The checkpoint is seeded by writing the note in the exact JSON shape PictureBookUtil
 * .saveExtractCheckpoint writes (that writer and the resume semantics are covered by the Objects7
 * JUnit TestExtractCheckpoint). Seeding it directly keeps this spec deterministic and LLM-free; the
 * Resume / "Review saved scenes" click, which starts a real extraction run, is deliberately NOT
 * exercised here.
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath, deleteObject } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';
const GROUP_PATH = '~/PbCheckpointE2E';

// A POST /login on a context that already holds a session does NOT switch users — the first
// session wins. Always log out before logging in as someone else on the same context.
async function apiLogin(request, name, password) {
    await request.get(REST + '/logout');
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name,
            credential: Buffer.from(password).toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'API login failed for ' + name + ': ' + resp.status()).toBeTruthy();
}

async function apiLoginShared(request) {
    await apiLogin(request, 'e2etest_shared', 'password');
}

/** Log in as the shared test user (page.request shares the browser cookie jar), then boot the SPA. */
async function loginAsSharedUser(page) {
    await apiLoginShared(page.request);
    // Docker's nginx strips cookies on the WS upgrade so Tomcat closes the socket, which triggers
    // forceLogin() and a redirect to #!/sig. Stub it before the first navigation.
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
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
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function createNoteIn(request, dir, name, text) {
    const resp = await request.post(REST + '/model', {
        data: { schema: 'data.note', groupId: dir.id, groupPath: dir.path, name, text }
    });
    expect(resp.ok(), 'POST data.note ' + name + ' failed: ' + resp.status() + ' ' + await resp.text()).toBe(true);
    const created = await resp.json();
    expect(created && created.objectId, 'create response carried no objectId for ' + name).toBeTruthy();
    return created;
}

/** The same field set saveExtractCheckpoint persists, with scene payloads stripped of sourceText. */
function checkpointJson(workObjectId, opts) {
    return JSON.stringify({
        workObjectId,
        textHash: 'e2e-forged-' + workObjectId,
        chunkSize: 8000,
        overlap: 400,
        totalChunks: opts.totalChunks,
        chunksProcessed: opts.chunksProcessed,
        startOffset: opts.startOffset === undefined ? null : opts.startOffset,
        endOffset: opts.endOffset === undefined ? null : opts.endOffset,
        updatedAt: opts.updatedAt || new Date().toISOString(),
        scenes: Array.from({ length: opts.sceneCount }, (_, i) => ({
            title: 'Scene ' + (i + 1), summary: 'E2E seeded scene ' + (i + 1), characters: ['A'], sourceChunk: i
        })),
        failedExtractions: opts.failedExtractions || []
    });
}

let workNote = null;
let workName = null;
let wholeDocNote = null;
let rangeNote = null;
let orphanNote = null;
let olderUpdatedAt = null;
let newerUpdatedAt = null;

test.describe.serial('PictureBook — unfinished extractions (checkpoints with no book)', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await apiLoginShared(request);

        const dir = await ensurePath(request, 'auth.group', 'data', GROUP_PATH);
        expect(dir && dir.id, 'could not make ' + GROUP_PATH).toBeTruthy();

        const stamp = Date.now().toString(36);
        workName = 'pb-ckpt-e2e-' + stamp + '.txt';
        workNote = await createNoteIn(request, dir, workName,
            'Chapter One. The harbor lights went out one by one as the tide turned. '.repeat(40));

        // Two checkpoints on the same document, exactly as a whole-document run and a chapter-range
        // run would leave them: one interrupted at 5/17 with one retryable failure, one complete at
        // 4/4 but with two refusals that still need a retry.
        // saveExtractCheckpoint writes ZonedDateTime.toString(), which carries a bracketed zone id
        // (…Z[GMT]) that Date.parse rejects — the list must still render it as a date.
        olderUpdatedAt = new Date(Date.now() - 60 * 60 * 1000).toISOString().replace(/\.\d{3}Z$/, '.202843679Z[GMT]');
        newerUpdatedAt = new Date().toISOString();
        wholeDocNote = await createNoteIn(request, dir, '.pbExtractProgress.' + workNote.objectId,
            checkpointJson(workNote.objectId, {
                totalChunks: 17, chunksProcessed: 5, sceneCount: 3, updatedAt: olderUpdatedAt,
                failedExtractions: [
                    JSON.stringify({ context: 'extract-scenes-chunk:3/17', kind: 'unparseable' }),
                    JSON.stringify({ context: 'extract-scenes-chunk:6/17', stoppedEarly: true })
                ]
            }));
        rangeNote = await createNoteIn(request, dir, '.pbExtractProgress.' + workNote.objectId + '.0-1000',
            checkpointJson(workNote.objectId, {
                totalChunks: 4, chunksProcessed: 4, sceneCount: 7, startOffset: 0, endOffset: 1000, updatedAt: newerUpdatedAt,
                failedExtractions: [
                    JSON.stringify({ context: 'extract-scenes-chunk:2/4', kind: 'refusal' }),
                    JSON.stringify({ context: 'extract-scenes-chunk:4/4', kind: 'refusal' })
                ]
            }));
        console.log('[pb-ckpt] work=' + workNote.objectId + ' whole=' + wholeDocNote.objectId + ' range=' + rangeNote.objectId);
    });

    test.afterAll(async ({ request }) => {
        await apiLoginShared(request);
        for (const n of [wholeDocNote, rangeNote, orphanNote, workNote]) {
            if (n && n.objectId) await deleteObject(request, 'data.note', n.objectId).catch(() => {});
        }
    });

    test('REST: the user-wide list and the per-document describe report both checkpoints without scene payloads', async ({ request }) => {
        await apiLoginShared(request);

        const listResp = await request.get(PB + '/extract-checkpoints');
        expect(listResp.status(), 'GET /extract-checkpoints').toBe(200);
        const list = await listResp.json();
        expect(Array.isArray(list)).toBe(true);
        const mine = list.filter(r => r.workObjectId === workNote.objectId);
        console.log('[pb-ckpt] list rows: ' + JSON.stringify(mine));
        expect(mine, 'both seeded checkpoints listed for the shared user').toHaveLength(2);

        // The server's JSON omits null keys, so a whole-document row has NO startOffset/endOffset.
        const whole = mine.find(r => r.startOffset == null && r.endOffset == null);
        const range = mine.find(r => r.startOffset === 0 && r.endOffset === 1000);
        expect(whole, 'whole-document row').toBeTruthy();
        expect(range, 'chapter-range row').toBeTruthy();

        expect(whole.workName).toBe(workName);
        expect(whole.workMissing).toBe(false);
        expect(whole.noteObjectId).toBe(wholeDocNote.objectId);
        expect(whole.chunksProcessed).toBe(5);
        expect(whole.totalChunks).toBe(17);
        expect(whole.sceneCount).toBe(3);
        expect(whole.failedCount, 'stoppedEarly entries are not retryable failures').toBe(1);
        expect(whole.complete).toBe(false);
        expect(whole.updatedAt).toBe(olderUpdatedAt);
        expect(whole).not.toHaveProperty('scenes');

        expect(range.noteObjectId).toBe(rangeNote.objectId);
        expect(range.chunksProcessed).toBe(4);
        expect(range.totalChunks).toBe(4);
        expect(range.sceneCount).toBe(7);
        expect(range.failedCount).toBe(2);
        expect(range.complete).toBe(true);
        expect(range).not.toHaveProperty('scenes');

        // Newest first.
        expect(mine.indexOf(range)).toBeLessThan(mine.indexOf(whole));

        const descResp = await request.get(PB + '/' + workNote.objectId + '/extract-checkpoints');
        expect(descResp.status(), 'GET /{work}/extract-checkpoints').toBe(200);
        const desc = await descResp.json();
        expect(desc.map(r => r.noteObjectId).sort()).toEqual([wholeDocNote.objectId, rangeNote.objectId].sort());
        for (const r of desc) {
            expect(r.workName).toBe(workName);
            expect(r.workMissing).toBe(false);
            expect(r).not.toHaveProperty('scenes');
        }

        // An unknown document has nothing to describe — and is not an error.
        const noneResp = await request.get(PB + '/00000000-0000-4000-8000-000000000000/extract-checkpoints');
        expect(noneResp.status()).toBe(200);
        expect(await noneResp.json()).toEqual([]);
    });

    test('REST: another same-org user sees neither checkpoint and cannot discard them', async ({ request }) => {
        const other = await ensureSharedTestUser(request, { name: 'e2etest_pbckpt_other', password: 'password' });
        await apiLogin(request, other.testUserName, other.testPassword);

        const list = await (await request.get(PB + '/extract-checkpoints')).json();
        expect(list.filter(r => r.workObjectId === workNote.objectId), 'other user must not see the shared user\'s checkpoints').toHaveLength(0);

        // The work note lives in the shared user's home; the other user cannot resolve it, so the
        // discard falls to the orphan sweep. That sweep's prefix list is org-wide (AccessPoint.list is
        // not a per-record boundary), so this relies on AccessPoint.delete refusing each note —
        // the server log shows "AUDIT DENY ... to DELETE data.note .pbExtractProgress..." for both.
        const del = await request.delete(PB + '/' + workNote.objectId + '/extract-checkpoints');
        expect([200, 403, 404]).toContain(del.status());
        if (del.status() === 200) {
            expect((await del.json()).deleted, 'other user deleted the shared user\'s checkpoint').toBe(0);
        }

        // Both notes still exist for the owner.
        await apiLoginShared(request);
        const mine = (await (await request.get(PB + '/' + workNote.objectId + '/extract-checkpoints')).json());
        expect(mine).toHaveLength(2);
    });

    test('UI: the PictureBook list shows the unfinished extractions and Discard removes the chapter-range one', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book');
        await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });

        const section = page.locator('[data-pb-checkpoint-list]');
        await expect(section, 'Unfinished Extractions section').toBeVisible({ timeout: 30000 });
        await expect(page.locator('text=Unfinished Extractions')).toBeVisible();

        const rows = section.locator('[data-pb-checkpoint-row][data-pb-checkpoint-work="' + workNote.objectId + '"]');
        await expect(rows).toHaveCount(2);
        // Newest first: the complete-with-failures chapter range sits above the interrupted whole-document run.
        await expect(rows.nth(0)).toContainText(workName);
        await expect(rows.nth(0)).toContainText('chars 0–1000 · 4 of 4 passages · 7 scenes saved · 2 failed passages');
        await expect(rows.nth(0)).toContainText('last saved');
        await expect(rows.nth(1)).toContainText('5 of 17 passages · 3 scenes saved · 1 failed passage');
        // The server's ZonedDateTime string renders as a formatted date, not raw with its [GMT] suffix.
        await expect(rows.nth(1)).toContainText('last saved');
        await expect(rows.nth(1)).not.toContainText('[GMT]');
        await expect(rows.nth(1)).not.toContainText('202843679');
        await section.locator('xpath=..').screenshot({ path: 'test-results/pb-ckpt-list-before.png' });

        // Discard the chapter-range checkpoint through the real affordance: Discard → Dialog.confirm (destructive).
        await rows.nth(0).locator('[data-pb-checkpoint-discard]').click();
        const confirmBtn = page.locator('button.am7-dialog-btn-destructive');
        await expect(confirmBtn, 'discard confirm dialog').toBeVisible({ timeout: 10000 });
        await expect(page.locator('[role="dialog"]').first()).toContainText('Discard unfinished extraction');
        await confirmBtn.click();

        await expect(rows, 'range row removed after discard').toHaveCount(1, { timeout: 20000 });
        await expect(rows.nth(0)).toContainText('5 of 17 passages');
        await section.locator('xpath=..').screenshot({ path: 'test-results/pb-ckpt-list-after.png' });

        // The server agrees: only the whole-document note remains; the range note itself is gone.
        const remaining = await (await page.request.get(PB + '/' + workNote.objectId + '/extract-checkpoints')).json();
        expect(remaining.map(r => r.noteObjectId)).toEqual([wholeDocNote.objectId]);
        const gone = await page.request.get(REST + '/model/data.note/' + rangeNote.objectId);
        expect(gone.status(), 'range checkpoint note should no longer be readable').not.toBe(200);
        rangeNote = null;
    });

    test('UI: opening the document from the list shows the Step 1 banner, and its Discard clears the last checkpoint', async ({ page }) => {
        await loginAsSharedUser(page);
        await page.goto('/#!/picture-book');
        const row = page.locator('[data-pb-checkpoint-list] [data-pb-checkpoint-row][data-pb-checkpoint-work="' + workNote.objectId + '"]');
        await expect(row).toHaveCount(1, { timeout: 30000 });

        await row.locator('[data-pb-checkpoint-open]').click();
        const dialog = page.locator('[role="dialog"]').first();
        await expect(dialog).toContainText('Picture Book — ' + workName, { timeout: 15000 });
        await expect(dialog).toContainText('Source: ' + workName);

        const banner = dialog.locator('[data-pb-checkpoint-banner]');
        await expect(banner, 'Step 1 checkpoint banner').toBeVisible({ timeout: 15000 });
        await expect(banner).toContainText('An earlier extraction of this document was interrupted before a book was created');
        const bannerRows = banner.locator('[data-pb-checkpoint-row]');
        await expect(bannerRows).toHaveCount(1);
        await expect(bannerRows.first()).toContainText('5 of 17 passages · 3 scenes saved · 1 failed passage');
        await expect(bannerRows.first().locator('[data-pb-checkpoint-resume]')).toHaveText(/Resume/);
        await page.screenshot({ path: 'test-results/pb-ckpt-banner.png' });

        await bannerRows.first().locator('[data-pb-checkpoint-discard]').click();
        const confirmBtn = page.locator('button.am7-dialog-btn-destructive');
        await expect(confirmBtn, 'banner discard confirm').toBeVisible({ timeout: 10000 });
        await confirmBtn.click();

        await expect(banner, 'banner gone once nothing is pending').toHaveCount(0, { timeout: 20000 });
        await expect(dialog).toContainText('Source: ' + workName);

        const remaining = await (await page.request.get(PB + '/' + workNote.objectId + '/extract-checkpoints')).json();
        expect(remaining).toEqual([]);
        const gone = await page.request.get(REST + '/model/data.note/' + wholeDocNote.objectId);
        expect(gone.status(), 'whole-document checkpoint note should no longer be readable').not.toBe(200);
        wholeDocNote = null;

        // Closing the wizard (its footer Cancel) must refresh the list underneath — it only loads
        // on oninit, so without the onClose hook the discarded row would still be shown here.
        await dialog.locator('.am7-dialog-footer button.am7-dialog-btn', { hasText: 'Cancel' }).click();
        await expect(page.locator('[role="dialog"]')).toHaveCount(0, { timeout: 10000 });
        await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });
        // Only this document's row is asserted gone — the shared user may legitimately have other
        // unfinished extractions (it does: leftovers from earlier sessions), so the section itself may stay.
        await expect(page.locator('[data-pb-checkpoint-list] [data-pb-checkpoint-work="' + workNote.objectId + '"]'),
            'list row removed after the in-wizard discard').toHaveCount(0, { timeout: 15000 });
        await page.locator('[data-pb-checkpoint-list]').first().scrollIntoViewIfNeeded().catch(function () {});
        await page.screenshot({ path: 'test-results/pb-ckpt-list-after-wizard-discard.png' });
    });

    test('REST: discarding a checkpoint whose source document is gone sweeps it as an orphan', async ({ request }) => {
        await apiLoginShared(request);
        const dir = await ensurePath(request, 'auth.group', 'data', GROUP_PATH);
        const doomed = await createNoteIn(request, dir, 'pb-ckpt-e2e-doomed-' + Date.now().toString(36) + '.txt', 'to be deleted');
        const orphan = await createNoteIn(request, dir, '.pbExtractProgress.' + doomed.objectId,
            checkpointJson(doomed.objectId, { totalChunks: 6, chunksProcessed: 2, sceneCount: 1 }));
        orphanNote = orphan;
        await deleteObject(request, 'data.note', doomed.objectId);

        const list = await (await request.get(PB + '/extract-checkpoints')).json();
        const row = list.find(r => r.workObjectId === doomed.objectId);
        expect(row, 'orphan listed').toBeTruthy();
        expect(row.workMissing).toBe(true);
        // JSONUtil.exportObject omits null keys, so a missing work has no workName key at all.
        expect(row.workName == null, 'no work name for a missing work').toBe(true);
        expect(row.noteObjectId).toBe(orphan.objectId);

        const del = await request.delete(PB + '/' + doomed.objectId + '/extract-checkpoints');
        expect(del.status()).toBe(200);
        expect((await del.json()).deleted).toBe(1);

        const again = await request.delete(PB + '/' + doomed.objectId + '/extract-checkpoints');
        expect(again.status()).toBe(200);
        expect((await again.json()).deleted, 'second discard finds nothing').toBe(0);

        const after = await (await request.get(PB + '/extract-checkpoints')).json();
        expect(after.find(r => r.workObjectId === doomed.objectId)).toBeUndefined();
        orphanNote = null;
    });
});
