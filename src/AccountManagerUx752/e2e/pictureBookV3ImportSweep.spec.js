/**
 * PictureBook P7-4 — verify check 2 (design §30 row 7, verify stage): "every existing book in the test
 * databases opens and migrates in V3 with no errors, and its images still show", measured against the
 * Docker stack's pre-V3 (PB1-only) books, as their owners. A measurement, not a fixture: it imports the
 * books that are actually there and prints one row per book.
 *
 * Gate: PB_MIGRATE_SWEEP=1 (off by default — it imports every PB1 book of every listed owner and calls
 * the LLM through each import's location pass). Owners: PB_MIGRATE_SWEEP_USERS, a comma list; default is
 * the shared test user only. Password: PB_MIGRATE_SWEEP_PASSWORD, default 'password' (what every e2e
 * user is created with — helpers/api.js). Org: PB_MIGRATE_SWEEP_ORG, default /Development. Never admin.
 *
 * Per owner: REST login → ~/Data/PictureBooks children (the PB1 book groups) → for each group holding a
 * .pictureBookMeta note: GET /{group}/pb2 (200 = already V3, recorded, skipped; 404 = PB1-only) →
 * POST /migrate-v1 {groupObjectId} → GET /{book}/pages (pages, pages with an image) → every imaged page's
 * /media URL answers 200 image/* → GET /{book}/health (problems) → the V3 reader opens on the book in the
 * browser as that owner ([data-pb2-book-name] + the cover count). Imports run back-to-back with no reset
 * in between so cross-owner slug collisions surface the way they would in use. At the end every book this
 * run imported is reset (DELETE /{book}/reset, as its owner) so the stack is left as found and the sweep
 * can be re-run; PB_MIGRATE_SWEEP_KEEP=1 leaves them in place for inspection.
 *
 * Verdict: the test fails when any measured PB1-only book did not import 200, any imported page image did
 * not answer 200 image/*, or the reader did not open on an imported book. Login failures and already-V3
 * books are reported, not failed. Nothing timed is a threshold. Nothing here calls the SD server.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, apiLogin, pb1Retired } from './helpers/api.js';
import { screenshot } from './helpers/auth.js';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';
const MEDIA = BASE_URL + '/AccountManagerService7/media';
const SWEEP = process.env.PB_MIGRATE_SWEEP === '1';
const KEEP = process.env.PB_MIGRATE_SWEEP_KEEP === '1';
const ORG = process.env.PB_MIGRATE_SWEEP_ORG || '/Development';
const PASSWORD = process.env.PB_MIGRATE_SWEEP_PASSWORD || 'password';
const USERS_ENV = (process.env.PB_MIGRATE_SWEEP_USERS || '').split(',').map((s) => s.trim()).filter(Boolean);
const V1_SKIP = 'picturebook.renderPath=v1: the V3 import under sweep only exists under v3 (P7-4).';
const GATE_SKIP = 'PB_MIGRATE_SWEEP=1 not set: the verify-check-2 migration sweep is opt-in.';
const TAG = '[p74-sweep]';

let shared = null;
let v3 = true;

function dotPath(orgPath) {
    return String(orgPath || '').replace(/^\/+/, '').replace(/\//g, '.');
}

function wsStubInit() {
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
}

async function loginOwner(ctx, user) {
    const resp = await apiLogin(ctx, { org: ORG, user, password: PASSWORD });
    const body = (await resp.text().catch(() => '')).trim();
    if (!(resp.ok() || resp.status() === 204) || body === 'false') {
        return 'login ' + resp.status() + (body === 'false' ? ' (credential rejected)' : '');
    }
    return null;
}

async function search(ctx, data) {
    const resp = await ctx.post(REST + '/model/search', { data: Object.assign({ schema: 'io.query', cache: false }, data) });
    if (!resp.ok()) return { error: 'search ' + data.type + ' -> ' + resp.status() };
    const j = await resp.json().catch(() => null);
    const rows = Array.isArray(j) ? j : (j && j.results) || [];
    if (rows.length && rows[0].schema) rows.forEach((r) => { if (!r.schema) r.schema = rows[0].schema; });
    return { rows };
}

function encodePath(dirPath) {
    return 'B64-' + Buffer.from(dirPath).toString('base64').replace(/=/g, '%3D');
}

// The owner's PB1 book groups: the children of ~/Data/PictureBooks that hold a .pictureBookMeta note.
async function listPb1Books(ctx) {
    const dirResp = await ctx.get(REST + '/path/find/auth.group/data/' + encodePath('~/Data/PictureBooks'));
    if (dirResp.status() === 404) return { books: [] };
    if (!dirResp.ok()) return { error: 'find ~/Data/PictureBooks -> ' + dirResp.status() };
    const dir = await dirResp.json().catch(() => null);
    if (!dir || !dir.id) return { books: [] };
    const orgId = Number(dir.organizationId);
    const groups = await search(ctx, {
        type: 'auth.group', request: ['id', 'objectId', 'name', 'path'], recordCount: 500,
        fields: [
            { name: 'parentId', comparator: 'EQUALS', value: Number(dir.id) },
            { name: 'organizationId', comparator: 'EQUALS', value: orgId }
        ]
    });
    if (groups.error) return groups;
    const books = [];
    for (const g of groups.rows) {
        const meta = await search(ctx, {
            type: 'data.note', request: ['id', 'objectId', 'name', 'text'], recordCount: 1,
            fields: [
                { name: 'name', comparator: 'EQUALS', value: '.pictureBookMeta' },
                { name: 'groupId', comparator: 'EQUALS', value: Number(g.id) },
                { name: 'organizationId', comparator: 'EQUALS', value: orgId }
            ]
        });
        if (meta.error) return meta;
        if (!meta.rows.length) continue;
        let sceneCount = null;
        let hasImages = false;
        try {
            const m = JSON.parse(meta.rows[0].text || '{}');
            sceneCount = Array.isArray(m.scenes) ? m.scenes.length : (m.sceneCount == null ? null : Number(m.sceneCount));
        } catch (e) { sceneCount = 'unparsable'; }
        // Whether any PB1 scene note names a rendered image (the only image importV1Book carries to a page).
        const scenes = await ctx.get(PB_REST + '/' + g.objectId + '/scenes');
        if (scenes.ok()) {
            const list = await scenes.json().catch(() => []);
            hasImages = Array.isArray(list) && list.some((s) => s && (s.imageObjectId || s.dataObjectId));
        }
        books.push({ group: g, orgId, sceneCount, hasImages, scenesStatus: scenes.status() });
    }
    return { books };
}

async function openReader(context, owner, bookOid, shot) {
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e && e.message)));
    try {
        await page.goto('/', { timeout: 30000 });
        await page.waitForFunction(
            () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
            { timeout: 30000 }
        );
        await page.evaluate((h) => { window.location.hash = h; }, '!/picture-book/v2/' + bookOid);
        await expect(page.locator('[data-pb2-book-name]')).toBeVisible({ timeout: 30000 });
        const name = (await page.locator('[data-pb2-book-name]').first().innerText()).trim();
        const count = (await page.locator('[data-pb2-cover-count]').first().innerText().catch(() => '')).trim();
        if (shot) await screenshot(page, 'p74-sweep-' + owner.replace(/[^A-Za-z0-9_-]/g, '_'));
        return { opened: true, name, count, errors };
    } catch (e) {
        return { opened: false, error: String(e && e.message).split('\n')[0], errors };
    } finally {
        await page.close().catch(() => {});
    }
}

test.describe('PictureBook V3 migration sweep of the stack\'s pre-V3 books (P7-4 verify check 2)', () => {
    test.describe.configure({ timeout: 60 * 60 * 1000 });

    test.beforeAll(async ({ request }) => {
        if (!SWEEP) return;
        shared = await ensureSharedTestUser(request);
        v3 = await pb1Retired(request, { userName: shared.testUserName, password: shared.testPassword });
    });

    test('every PB1-only book of each owner imports, its pages and images read back, and the reader opens', async ({ browser }) => {
        test.skip(!SWEEP, GATE_SKIP);
        test.skip(!v3, V1_SKIP);
        const owners = USERS_ENV.length ? USERS_ENV : [shared.testUserName];
        expect(owners.includes('admin'), 'the sweep never runs as admin').toBe(false);
        console.log(TAG + ' owners=' + owners.length + ' org=' + ORG + ' keep=' + KEEP);

        const rows = [];            // one per PB1 book seen
        const imported = [];        // {owner, bookOid} to reset at the end
        const unmeasured = [];      // owners whose login or listing failed

        for (const owner of owners) {
            const context = await browser.newContext({ ignoreHTTPSErrors: true });
            await context.addInitScript(wsStubInit);
            const ctx = context.request;
            try {
                const loginErr = await loginOwner(ctx, owner);
                if (loginErr) { unmeasured.push(owner + ': ' + loginErr); console.log(TAG + ' ' + owner + ' ' + loginErr); continue; }
                const listed = await listPb1Books(ctx);
                if (listed.error) { unmeasured.push(owner + ': ' + listed.error); console.log(TAG + ' ' + owner + ' ' + listed.error); continue; }
                if (!listed.books.length) { console.log(TAG + ' ' + owner + ': no PB1 books'); continue; }
                let shot = true;
                for (const b of listed.books) {
                    const row = { owner, book: b.group.name, groupOid: b.group.objectId, pb1Scenes: b.sceneCount, pb1Images: b.hasImages };
                    rows.push(row);
                    const bridge = await ctx.get(PB_REST + '/' + b.group.objectId + '/pb2');
                    if (bridge.status() === 200) {
                        const j = await bridge.json().catch(() => ({}));
                        row.status = 'already-v3';
                        row.slug = j.slug;
                        continue;
                    }
                    if (bridge.status() !== 404) { row.status = 'pb2 ' + bridge.status(); continue; }

                    const t0 = Date.now();
                    const mig = await ctx.post(PB_REST + '/migrate-v1', { data: { groupObjectId: b.group.objectId }, timeout: 600000 });
                    const migText = await mig.text();
                    row.importMs = Date.now() - t0;
                    if (mig.status() !== 200) {
                        row.status = 'import ' + mig.status();
                        row.error = migText.slice(0, 200);
                        console.log(TAG + ' ' + owner + ' "' + b.group.name + '" import ' + mig.status() + ' ' + migText.slice(0, 200));
                        continue;
                    }
                    const r = JSON.parse(migText);
                    row.status = 'imported';
                    row.bookOid = r.bookObjectId;
                    row.slug = r.slug;
                    row.scenesImported = r.scenesImported;
                    row.scenesFailed = r.scenesFailed || 0;
                    row.landscapesImported = r.landscapesImported || 0;
                    row.warnings = (r.warnings || []).length;
                    row.locationHardFailure = !!(r.locationPass && r.locationPass.hardFailure);
                    imported.push({ owner, bookOid: r.bookObjectId, context });

                    // Pages + images, as the reader reads them.
                    const pagesResp = await ctx.get(PB_REST + '/' + r.bookObjectId + '/pages');
                    if (!pagesResp.ok()) { row.pages = 'pages ' + pagesResp.status(); }
                    else {
                        const pages = await pagesResp.json();
                        row.pages = pages.length;
                        const imaged = pages.filter((p) => p && p.imageGroupPath && p.imageName);
                        row.imagedPages = imaged.length;
                        row.mediaOk = 0; row.mediaBad = [];
                        for (const p of imaged) {
                            const url = MEDIA + '/' + dotPath(ORG) + '/data.data' + p.imageGroupPath + '/' + p.imageName;
                            const m = await ctx.get(url);
                            const ct = String(m.headers()['content-type'] || '');
                            if (m.status() === 200 && ct.includes('image/')) row.mediaOk++;
                            else row.mediaBad.push(p.imageName + ' -> ' + m.status() + ' ' + ct);
                        }
                    }
                    const health = await ctx.get(PB_REST + '/' + r.bookObjectId + '/health');
                    if (health.ok()) {
                        const h = await health.json();
                        const findings = Array.isArray(h.findings) ? h.findings : [];
                        row.healthErrors = findings.filter((f) => String(f.severity).toUpperCase() === 'ERROR').map((f) => f.code);
                        row.healthWarns = findings.filter((f) => String(f.severity).toUpperCase() === 'WARN').map((f) => f.code);
                    } else {
                        row.healthErrors = ['health ' + health.status()];
                    }

                    // The reader, in the browser, as the owner (one screenshot per owner).
                    const opened = await openReader(context, owner, r.bookObjectId, shot);
                    shot = false;
                    row.reader = opened.opened ? ('opened "' + opened.name + '" ' + opened.count) : ('NOT OPENED: ' + opened.error);
                    row.pageErrors = opened.errors.length;
                    console.log(TAG + ' ' + owner + ' "' + b.group.name + '" -> ' + row.slug + ' scenes=' + row.scenesImported
                        + '/' + row.scenesFailed + ' landscapes=' + row.landscapesImported + ' pages=' + row.pages + ' imaged=' + row.imagedPages
                        + ' mediaOk=' + row.mediaOk + ' health=' + JSON.stringify(row.healthErrors) + ' warnings=' + row.warnings
                        + ' locHard=' + row.locationHardFailure + ' ' + row.reader + ' (' + row.importMs + ' ms)');
                }
            } finally {
                // Contexts are kept open until the reset so each book is removed as its own owner.
                if (!imported.some((i) => i.context === context)) await context.close().catch(() => {});
            }
        }

        // ── Cleanup: every book this run imported, as its owner, unless asked to keep them ──
        const resets = [];
        if (!KEEP) {
            for (const i of imported) {
                try {
                    const rr = await i.context.request.delete(PB_REST + '/' + i.bookOid + '/reset', { timeout: 300000 });
                    const t = await rr.text().catch(() => '');
                    resets.push(i.owner + ' ' + i.bookOid + ' -> ' + rr.status() + (rr.status() === 200 ? '' : ' ' + t.slice(0, 120)));
                } catch (e) { resets.push(i.owner + ' ' + i.bookOid + ' threw ' + (e && e.message)); }
            }
        }
        for (const c of new Set(imported.map((i) => i.context))) await c.close().catch(() => {});

        // ── Report ──
        const measured = rows.filter((r) => r.status !== 'already-v3');
        const ok = measured.filter((r) => r.status === 'imported');
        console.log(TAG + ' === RESULT TABLE (' + rows.length + ' PB1 books across ' + owners.length + ' owners) ===');
        console.log(TAG + ' owner | book | pb1Scenes | pb1Images | status | slug | scenes ok/failed | landscapes | pages/imaged/mediaOk | healthErrors | warnings | locHard | reader');
        for (const r of rows) {
            console.log(TAG + ' ' + [r.owner, r.book, r.pb1Scenes, r.pb1Images, r.status, r.slug || '',
                r.status === 'imported' ? (r.scenesImported + '/' + r.scenesFailed) : (r.error || ''),
                r.landscapesImported == null ? '' : r.landscapesImported,
                r.pages == null ? '' : (r.pages + '/' + r.imagedPages + '/' + r.mediaOk),
                r.healthErrors ? JSON.stringify(r.healthErrors) : '', r.warnings == null ? '' : r.warnings,
                r.locationHardFailure == null ? '' : r.locationHardFailure, r.reader || ''].join(' | '));
        }
        console.log(TAG + ' already-v3=' + rows.filter((r) => r.status === 'already-v3').length + ' imported=' + ok.length
            + ' failed=' + measured.filter((r) => r.status !== 'imported').length + ' unmeasuredOwners=' + unmeasured.length);
        if (unmeasured.length) console.log(TAG + ' unmeasured: ' + unmeasured.join('; '));
        if (resets.length) console.log(TAG + ' resets: ' + resets.join('; '));

        // ── Verdict ──
        const importFailures = measured.filter((r) => r.status !== 'imported').map((r) => r.owner + ' "' + r.book + '": ' + r.status + ' ' + (r.error || ''));
        expect(importFailures, 'PB1-only books that did not import').toEqual([]);
        const mediaFailures = ok.filter((r) => (r.mediaBad && r.mediaBad.length) || typeof r.pages === 'string').map((r) => r.owner + ' "' + r.book + '": ' + (typeof r.pages === 'string' ? r.pages : r.mediaBad.join(', ')));
        expect(mediaFailures, 'imported pages whose image did not answer 200 image/*').toEqual([]);
        const readerFailures = ok.filter((r) => !/^opened /.test(r.reader || '') || r.pageErrors).map((r) => r.owner + ' "' + r.book + '": ' + r.reader + (r.pageErrors ? ' pageErrors=' + r.pageErrors : ''));
        expect(readerFailures, 'imported books the V3 reader did not open cleanly').toEqual([]);
        const resetFailures = resets.filter((s) => !/-> 200$/.test(s));
        expect(resetFailures, 'imported books that could not be reset (the stack is not as found)').toEqual([]);
    });
});
