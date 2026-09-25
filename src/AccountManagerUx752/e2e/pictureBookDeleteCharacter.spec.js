/**
 * pictureBookDeleteCharacter.spec.js — real-browser proof of the Manage Characters "Delete character"
 * action (DELETE /rest/olio/picture-book/{bookObjectId}/character/{objectId}).
 *
 * Extraction sometimes yields non-characters (animals, expressions, crowds: "pixies", "a sigh"). The
 * manage-characters view now has a red "Delete character" button in the detail header that deletes the
 * charPerson and detaches it from every scene / meta entry / pipeline binding. This spec drives the
 * rendered UI on an already-extracted book belonging to the shared test user:
 *
 *   viewer route -> "Edit Book" -> wizard resumes on step 4/5 -> "Manage Characters" -> click the
 *   bogus extract -> "Delete character" -> accept window.confirm -> success toast -> list refreshed
 *   without it -> GET /characters (REST) confirms it is gone -> no scene still names it.
 *
 * It does NOT run an extraction (minutes of LLM time): it looks through the shared user's existing
 * books for a character whose name is in PB_DELETE_CHAR_NAMES (a list of known bogus extracts) and
 * fails plainly if none is left, rather than deleting a real character.
 *
 * Each run permanently deletes one record from the shared user's book, so it is gated behind
 * PB_DELETE_CHAR_E2E and must run single-threaded on one browser — never as part of the default suite:
 *   PB_DELETE_CHAR_E2E=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookDeleteCharacter.spec.js --workers=1 --project=chromium
 *
 * Shared test user only (never admin). No DB reset; app writes/reads only.
 */
import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { ensureSharedTestUser } from './helpers/api.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE + '/AccountManagerService7/rest';
const PB = REST + '/olio/picture-book';

const BOGUS_NAMES = (process.env.PB_DELETE_CHAR_NAMES
    || 'pixies,factory guards,mine workers,foreman,the undead archivist,winged employee')
    .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);

const SHOTS = path.resolve(__dirname, 'screenshots');
function shot(name) { fs.mkdirSync(SHOTS, { recursive: true }); return path.join(SHOTS, name + '.png'); }

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

async function loginSharedUser(page, creds) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: creds.testUserName, credential: Buffer.from(creds.testPassword).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) throw new Error('Shared-user REST login failed: HTTP ' + resp.status());
    await installWsStub(page);
    await page.goto('/', { timeout: 45000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 45000 }
    );
}

async function listCharacters(page, bookOid) {
    const r = await page.request.get(PB + '/' + bookOid + '/characters', { timeout: 60000 });
    if (!r.ok()) return null;
    const body = await r.json();
    return Array.isArray(body) ? body : null;
}

async function listScenes(page, bookOid) {
    const r = await page.request.get(PB + '/' + bookOid + '/scenes', { timeout: 60000 });
    if (!r.ok()) return [];
    const body = await r.json();
    return Array.isArray(body) ? body : (Array.isArray(body.scenes) ? body.scenes : []);
}

// GET /scenes reflects .pictureBookMeta: `characters` is a list of charPerson objectIds (strings).
// Tolerate {name, objectId} objects too so the check stays valid if the projection ever widens.
function sceneReferencesCharacter(scene, nameLc, oid) {
    const chars = Array.isArray(scene && scene.characters) ? scene.characters : [];
    return chars.some((c) => typeof c === 'string'
        ? c === oid
        : ((c && c.objectId) === oid || ((c && c.name) || '').trim().toLowerCase() === nameLc));
}

test.describe('PictureBook — Manage Characters delete', () => {
    test.describe.configure({ mode: 'serial', timeout: 300000 });
    // Describe-level so the browser fixture is never even launched without the flag.
    test.skip(!process.env.PB_DELETE_CHAR_E2E,
        'deletes a real record from the shared user\'s book — set PB_DELETE_CHAR_E2E=1 (and run --workers=1 --project=chromium) to execute');
    let creds;

    test.beforeAll(async ({ request }) => {
        creds = await ensureSharedTestUser(request);
        expect(creds && creds.testUserName, 'shared test user').toBeTruthy();
    });

    test('Delete character removes the extract from the list, the book and its scenes', async ({ page }) => {
        page.on('console', (msg) => {
            if (msg.type() === 'error') console.log('[PAGE-ERROR] ' + msg.text());
        });
        page.on('response', async (resp) => {
            if (resp.request().method() === 'DELETE' && resp.url().includes('/picture-book/')) {
                let body = '';
                try { body = (await resp.text()).substring(0, 500); } catch (e) { body = '<unreadable>'; }
                console.log('[NETWORK] DELETE ' + resp.status() + ' ' + resp.url() + ' ' + body);
            }
        });

        await loginSharedUser(page, creds);

        // ── Pick a book + a known bogus extract via REST (no extraction run).
        const booksResp = await page.request.get(PB + '/books', { timeout: 60000 });
        expect(booksResp.ok(), 'GET /books').toBeTruthy();
        const books = await booksResp.json();
        expect(Array.isArray(books) && books.length > 0, 'shared user has at least one book').toBeTruthy();

        let bookOid = null, target = null, before = null, scenesBefore = [];
        for (const b of books) {
            const oid = b.objectId || b.bookObjectId;
            if (!oid) continue;
            const chars = await listCharacters(page, oid);
            if (!chars || !chars.length) continue;
            const hit = chars.find((c) => BOGUS_NAMES.includes(((c && c.name) || '').trim().toLowerCase()));
            if (!hit) continue;
            const scenes = await listScenes(page, oid);
            if (!scenes.length) continue;   // the viewer's "Edit Book" button needs scenes
            bookOid = oid; target = hit; before = chars; scenesBefore = scenes;
            break;
        }
        console.log('[pick] book=' + bookOid + ' target=' + (target && target.name) + ' (' + (target && target.objectId) + ')'
            + ' chars=' + (before && before.length) + ' scenes=' + scenesBefore.length);
        expect(bookOid, 'no book of the shared user still has a character named one of: ' + BOGUS_NAMES.join(', ')
            + ' — set PB_DELETE_CHAR_NAMES to another bogus extract').toBeTruthy();
        const targetName = target.name;
        const targetLc = targetName.trim().toLowerCase();
        const scenesNamingTarget = scenesBefore.filter((s) => sceneReferencesCharacter(s, targetLc, target.objectId)).length;
        console.log('[pick] scenes referencing target before: ' + scenesNamingTarget);

        // ── Viewer -> Edit Book -> wizard resumes on the existing book.
        await page.goto('/#!/picture-book/' + bookOid, { timeout: 45000 });
        const editBtn = page.locator('button[title="Edit Book"]');
        await expect(editBtn, 'viewer shows Edit Book (book has scenes)').toBeVisible({ timeout: 60000 });
        await editBtn.click();
        await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: 30000 });

        // ── Manage Characters (steps 4/5 action) opens the stacked dialog.
        const manageBtn = page.locator('button:has-text("Manage Characters")').first();
        await expect(manageBtn).toBeVisible({ timeout: 60000 });
        await manageBtn.click();
        const dialogs = page.getByRole('dialog');
        const mgr = dialogs.filter({ hasText: 'Manage Characters' }).last();
        await expect(mgr).toBeVisible({ timeout: 30000 });

        // Before: the bogus extract is listed.
        const targetCard = mgr.locator('div.font-medium.text-sm', { hasText: new RegExp('^' + targetName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i') }).first();
        await expect(targetCard, '"' + targetName + '" is listed before delete').toBeVisible({ timeout: 60000 });
        await page.screenshot({ path: shot('pb-delete-char-1-listed'), fullPage: true });

        // Select it -> detail header shows the red Delete button.
        await targetCard.click();
        const deleteBtn = mgr.locator('button:has-text("Delete character")');
        await expect(deleteBtn, 'Delete character button rendered in detail header').toBeVisible({ timeout: 30000 });
        await expect(deleteBtn).toBeEnabled();
        await expect(mgr.locator('div.text-lg.font-semibold', { hasText: targetName })).toBeVisible();
        await page.screenshot({ path: shot('pb-delete-char-2-detail'), fullPage: true });

        // First pass: dismiss the confirm — nothing must be deleted.
        page.once('dialog', async (d) => {
            expect(d.type()).toBe('confirm');
            expect(d.message()).toContain('Delete "' + targetName + '"');
            await d.dismiss();
        });
        await deleteBtn.click();
        await page.waitForTimeout(1500);
        const afterDismiss = await listCharacters(page, bookOid);
        expect(afterDismiss.some((c) => c.objectId === target.objectId), 'dismissing the confirm deletes nothing').toBeTruthy();
        await expect(targetCard, 'still listed after dismiss').toBeVisible();

        // Second pass: accept -> DELETE fires -> toast -> list refreshes without the extract.
        page.once('dialog', async (d) => { await d.accept(); });
        await deleteBtn.click();
        const toast = page.locator('.toast-box', { hasText: 'Deleted "' + targetName + '"' });
        await expect(toast, 'success toast names the deleted character').toBeVisible({ timeout: 60000 });
        const toastText = await toast.textContent();
        console.log('[toast] ' + toastText);
        expect(toastText).toMatch(/detached from \d+ scene/);
        await page.screenshot({ path: shot('pb-delete-char-3-toast'), fullPage: true });

        await expect(targetCard, '"' + targetName + '" no longer listed').toHaveCount(0, { timeout: 60000 });
        await expect(mgr.locator('text=Select a character from the list.')).toBeVisible({ timeout: 10000 });
        await page.screenshot({ path: shot('pb-delete-char-4-after'), fullPage: true });

        // ── REST confirms: gone from the book, gone from every scene, second delete 404s.
        const after = await listCharacters(page, bookOid);
        expect(after, 'GET /characters after delete').toBeTruthy();
        expect(after.length, 'character count dropped by exactly one').toBe(before.length - 1);
        expect(after.some((c) => c.objectId === target.objectId), 'deleted objectId absent').toBeFalsy();
        expect(after.some((c) => ((c.name || '').trim().toLowerCase()) === targetLc), 'deleted name absent').toBeFalsy();

        const scenesAfter = await listScenes(page, bookOid);
        expect(scenesAfter.length, 'no scene was removed').toBe(scenesBefore.length);
        for (const s of scenesAfter) {
            expect(sceneReferencesCharacter(s, targetLc, target.objectId),
                'scene "' + (s.title || s.objectId) + '" still references the deleted character').toBeFalsy();
        }

        const again = await page.request.delete(PB + '/' + bookOid + '/character/' + target.objectId, { timeout: 60000 });
        expect(again.status(), 'second delete is 404').toBe(404);

        console.log('VERIFIED: "' + targetName + '" deleted via the UI; ' + before.length + ' -> ' + after.length
            + ' characters; scenes referencing it ' + scenesNamingTarget + ' -> 0');
    });
});
