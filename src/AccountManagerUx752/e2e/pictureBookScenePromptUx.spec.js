/**
 * Per-scene SD prompt in the PictureBook wizard (Step 4), driven THROUGH THE BROWSER against the live
 * Docker stack with the real SD server (192.168.1.39) and Ollama (192.168.1.42).
 *
 * Stephen's report (2026-09-25): "Prompt scene overrides need to include the ACTUAL prompt used for
 * THAT scene and allow override when recreating. Right now it only shows the last prompt used as a
 * global, not for the scene." Asserted here in the real Ux:
 *   1. Generating scene A shows "Prompt used:" ON scene A's card with the prompt SD actually received
 *      (== the scene's persisted compositePrompt — the sent request's getPrompt(), in any composite
 *      mode — served back by GET /{book}/scenes as `prompt`).
 *   2. Generating scene B shows B's OWN prompt on B, and A's card STILL shows A's prompt (per scene,
 *      not a book-wide "last prompt").
 *   3. Reject A, open its Scene Overrides: the Prompt field is pre-filled with A's prompt. Edit it and
 *      Generate again: the card shows the edited prompt, the server persisted it as A's
 *      compositePrompt, and B's prompt is untouched.
 *
 * What this spec does NOT prove on its own: that the override drove the IMAGE. It first passed on
 * 2026-09-25 while the override was being silently ignored in flux2/kontext mode (only classic mode
 * read it), because display + persistence were correct and the image was never looked at. That defect
 * is pinned at the request level by TestFlux2Composite (flux2BuilderIgnoresClassicPrompt /
 * promptOverrideReplaces*), and the rendered override image must still be decoded and LOOKED AT.
 *
 * Runs against an existing book (a chapter of the HarlotsEight series an earlier
 * pictureBookChapteredManuscriptUx run extracted). Nothing is stubbed.
 *
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 PB_UX_BOOK="<book objectId | seriesName | seriesObjectId>" \
 *       npx playwright test e2e/pictureBookScenePromptUx.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';
const ORG_PATH = '/Development';

const BOOK_REF = process.env.PB_UX_BOOK || 'HarlotsEight-UX-muhpnuzi.docx';
const GEN_MIN = parseInt(process.env.PB_UX_GEN_MIN || '12', 10);   // per-scene render budget

function b64(str) { return Buffer.from(str).toString('base64'); }

async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: ORG_PATH, name: SHARED_USER,
            credential: b64(SHARED_PASSWORD), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'shared-user login failed: ' + resp.status()).toBe(true);
}

async function getJson(request, url) {
    const resp = await request.get(url, { headers: { Accept: 'application/json' } });
    expect(resp.ok(), 'GET ' + url + ' -> ' + resp.status()).toBe(true);
    return resp.json();
}

async function listScenes(request, bookOid) {
    const scenes = await getJson(request, PB_REST + '/' + bookOid + '/scenes');
    return Array.isArray(scenes) ? scenes : (scenes && scenes.sceneList) || [];
}

async function resolveBook(request) {
    const all = await getJson(request, PB_REST + '/books');
    let hit = all.find(b => b.objectId === BOOK_REF || b.bookObjectId === BOOK_REF);
    if (hit) return hit.objectId || hit.bookObjectId;
    const inSeries = all.filter(b => b.seriesName === BOOK_REF || b.seriesObjectId === BOOK_REF);
    expect(inSeries.length, 'no book matches PB_UX_BOOK=' + BOOK_REF + ' (series seen: '
        + JSON.stringify([...new Set(all.map(b => b.seriesName))]) + ')').toBeGreaterThan(0);
    const seriesBooks = await getJson(request, PB_REST + '/series/' + inSeries[0].seriesObjectId + '/books');
    seriesBooks.sort((a, b) => Number(a.chapter) - Number(b.chapter));
    return seriesBooks[0].objectId;
}

// The wizard renders Step 4's scene cards inside the `space-y-3 max-h-[32rem]` scroller, in the
// same order GET /{book}/scenes returns them. Cards are `div.border.rounded.p-3.space-y-2`; the
// common SD config panel above the list is also `border rounded p-3` (with mb-3), so scope to the
// scroller's direct children. Uncommitted scenes render as a plain text div, not a card.
function sceneCards(dialog) {
    return dialog.locator('div.space-y-3.max-h-\\[32rem\\] > div.border.rounded.p-3');
}
function sceneCard(dialog, idx) {
    return sceneCards(dialog).nth(idx);
}
// Status badge = the last `text-xs px-2 py-0.5 rounded shrink-0` span in the card's header row (the
// override indicator is px-1.5 and never matches).
function statusBadge(card) {
    return card.locator('div.flex.items-center.gap-2').first().locator('span.text-xs.px-2.py-0\\.5.rounded.shrink-0').last();
}
async function status(card) {
    return ((await statusBadge(card).textContent().catch(() => '')) || '').trim();
}
// Action buttons carry a material-symbols glyph span (textContent "refreshRetry"), so match by
// has-text, not exact text.
function actionButton(card, label) {
    return card.locator('div.flex.gap-2.flex-wrap').first().locator('button:has-text("' + label + '")');
}
async function promptShown(card, oid) {
    const el = card.locator('[data-pb-scene-prompt="' + oid + '"]');
    await expect(el, 'Prompt used shown for scene ' + oid).toBeVisible({ timeout: 10000 });
    await expect(el).toContainText('Prompt used:');
    return ((await el.locator('span').nth(1).textContent()) || '').trim();
}
async function waitForRender(card, label, t0) {
    await expect.poll(async () => status(card), {
        timeout: GEN_MIN * 60 * 1000, intervals: [3000],
        message: label + ': scene render did not reach done/error'
    }).toMatch(/^(done|error)$/);
    const st = await status(card);
    const err = st === 'error' ? ((await card.locator('div.text-red-500').first().textContent().catch(() => '')) || '') : '';
    console.log('[scene-prompt] ' + label + ' -> ' + st + ' in ' + ((Date.now() - t0) / 1000).toFixed(0) + 's' + (err ? ' ERROR: ' + err : ''));
    expect(st, label + ' render failed: ' + err).toBe('done');
}
// Click Generate (pending) or Retry (error) on this card and wait for the render to finish.
async function generateAndWait(card, label) {
    const st = await status(card);
    const btn = actionButton(card, st === 'error' ? 'Retry' : 'Generate');
    await expect(btn, label + ': ' + (st === 'error' ? 'Retry' : 'Generate') + ' button (status=' + st + ')').toBeVisible({ timeout: 10000 });
    await expect(btn).toBeEnabled({ timeout: 30000 });
    const t0 = Date.now();
    await btn.click();
    await waitForRender(card, label, t0);
}

test.describe('PictureBook wizard — per-scene prompt shown and overridable', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    test('each scene shows its own prompt; override on regenerate is used and persisted', async ({ page, request }) => {
        test.setTimeout((3 * GEN_MIN + 15) * 60 * 1000);
        const pageErrors = [];
        page.on('pageerror', (err) => { pageErrors.push(String(err && err.stack || err)); console.log('[PAGE-ERROR] ' + (err && err.message)); });
        page.on('response', async (resp) => {
            const u = resp.url();
            if (u.includes('/olio/picture-book/') && resp.status() >= 400) {
                let body = ''; try { body = (await resp.text()).substring(0, 300); } catch (_) { body = '<unreadable>'; }
                console.log('[NETWORK ' + resp.status() + '] ' + u + ' ' + body);
            }
        });

        await ensureSharedTestUser(request);
        await restLoginShared(request);
        const bookOid = await resolveBook(request);
        const before = await listScenes(request, bookOid);
        console.log('[scene-prompt] book ' + bookOid + ' has ' + before.length + ' scene(s)');
        const committed = before.filter(s => s.objectId);
        // Two scenes that are still open for generation (pending / done / error, not accepted/skipped).
        const open = committed.map((s, i) => ({ s, i }))
            .filter(x => !['accepted', 'skipped'].includes(String(x.s.status || '').toLowerCase()));
        expect(open.length, 'need two generatable scenes').toBeGreaterThanOrEqual(2);
        const A = open[0], B = open[1];
        console.log('[scene-prompt] A=#' + A.i + ' "' + A.s.title + '" (' + A.s.objectId + ') B=#' + B.i + ' "' + B.s.title + '" (' + B.s.objectId + ')');

        await login(page, { org: ORG_PATH, user: SHARED_USER, password: SHARED_PASSWORD });
        await page.goto('/#!/picture-book/' + bookOid);
        const editBtn = page.locator('button[title="Edit Book"]');
        await expect(editBtn).toBeVisible({ timeout: 30000 });
        await editBtn.click();
        const dialog = page.locator('[role="dialog"]').first();
        await expect(dialog).toBeVisible({ timeout: 10000 });
        await expect(dialog.locator('h3:has-text("Image Generation")')).toBeVisible({ timeout: 30000 });   // Step 4
        await expect(sceneCards(dialog)).toHaveCount(committed.length, { timeout: 30000 });

        const cardA = sceneCard(dialog, A.i), cardB = sceneCard(dialog, B.i);
        await expect(cardA.locator('div.font-medium.text-sm.truncate')).toHaveText(A.s.title || 'Untitled');
        await expect(cardB.locator('div.font-medium.text-sm.truncate')).toHaveText(B.s.title || 'Untitled');
        // Start from a generatable state on both (a 'done' scene offers Reject instead of Generate).
        for (const [c, label] of [[cardA, 'A'], [cardB, 'B']]) {
            const st = await status(c);
            console.log('[scene-prompt] ' + label + ' initial status: ' + st);
            if (st === 'done') {
                await actionButton(c, 'Reject').click();
                await expect(statusBadge(c)).toHaveText('pending', { timeout: 10000 });
            }
        }
        await screenshot(page, 'scene-prompt-step4-before');

        // ── 1. Scene A: its own prompt is shown and matches what the server persisted ──────────
        await generateAndWait(cardA, 'A first render');
        const promptA1 = await promptShown(cardA, A.s.objectId);
        console.log('[scene-prompt] A prompt (' + promptA1.length + ' chars): ' + promptA1.substring(0, 160));
        expect(promptA1.length).toBeGreaterThan(10);
        await expect.poll(async () => {
            const s = (await listScenes(request, bookOid)).find(x => x.objectId === A.s.objectId);
            return s ? (s.prompt || null) : null;
        }, { timeout: 20000, message: 'A.prompt persisted' }).toBe(promptA1);
        await expect(cardB.locator('[data-pb-scene-prompt]'), 'B shows no prompt before its own render').toHaveCount(
            B.s.prompt ? 1 : 0);
        await screenshot(page, 'scene-prompt-A-rendered');

        // ── 2. Scene B: its own prompt; A keeps A's ─────────────────────────────────────────────
        await generateAndWait(cardB, 'B first render');
        const promptB1 = await promptShown(cardB, B.s.objectId);
        console.log('[scene-prompt] B prompt (' + promptB1.length + ' chars): ' + promptB1.substring(0, 160));
        expect(promptB1, 'B has its own prompt, not A\'s').not.toBe(promptA1);
        expect(await promptShown(cardA, A.s.objectId), 'A still shows A\'s prompt after B rendered').toBe(promptA1);
        await screenshot(page, 'scene-prompt-B-rendered');

        // ── 3. Reject A, override its prompt, regenerate: override used + persisted ─────────────
        await actionButton(cardA, 'Reject').click();
        await expect(statusBadge(cardA)).toHaveText('pending', { timeout: 10000 });
        expect(await promptShown(cardA, A.s.objectId), 'A keeps showing its prompt after Reject').toBe(promptA1);

        await cardA.locator('button:has-text("Scene Overrides")').click();
        const promptField = cardA.locator('input[name="description"], textarea[name="description"]').first();
        await expect(promptField).toBeVisible({ timeout: 20000 });
        await expect(promptField, 'override Prompt pre-filled with THIS scene\'s prompt').toHaveValue(promptA1);

        const override = 'e2e per-scene override ' + Date.now()
            + ': a lone fairy with translucent wings sitting on a moonlit market stall, soft watercolor, muted teal and gold';
        await promptField.fill(override);
        await expect(promptField).toHaveValue(override);
        await screenshot(page, 'scene-prompt-A-override-filled');

        await generateAndWait(cardA, 'A override render');
        const promptA2 = await promptShown(cardA, A.s.objectId);
        expect(promptA2, 'card shows the override as the prompt used').toBe(override);
        await expect.poll(async () => {
            const s = (await listScenes(request, bookOid)).find(x => x.objectId === A.s.objectId);
            return s ? (s.prompt || null) : null;
        }, { timeout: 20000, message: 'override persisted as A.compositePrompt' }).toBe(override);
        expect(await promptShown(cardB, B.s.objectId), 'B untouched by A\'s override').toBe(promptB1);
        const bPersisted = (await listScenes(request, bookOid)).find(x => x.objectId === B.s.objectId);
        expect(bPersisted && bPersisted.prompt, 'B.compositePrompt untouched').toBe(promptB1);
        await screenshot(page, 'scene-prompt-A-override-rendered');

        expect(pageErrors, 'no uncaught page errors').toEqual([]);
    });
});
