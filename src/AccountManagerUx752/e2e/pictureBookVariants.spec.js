/**
 * PictureBook V3 Phase 6 (Variants) — P6-4 live spec
 *
 * Exercises the artifact strip (grouped by attempt, v{n}/seed badges, status tint, archived toggle, two-up
 * compare) and the Variant Retention dialog (dry run → apply) on the workflow canvas against the live stack,
 * plus the GET/POST /{book}/retention routes they sit on.
 *
 * No SD: the fixture is TEXT artifacts created through the generic model route on a fresh PORTRAIT node of a
 * seeded ChapBook, carrying the P6-1 fields (attempt / variantIndex / seed / byteLength) directly, so the
 * UI and the retention policy are what is exercised. The live SD leg (Scenario F, two finals of one attempt
 * with distinct seeds on .39) is owed separately (aiDocs/PictureBookV3Implementation.md §13.6).
 *
 * Fixture (role portrait0 on one node), newest attempt first as the strip shows it:
 *   attempt B: r4 v0 seed 200 GENERATED selected ●, r5 v1 seed 201 GENERATED
 *   attempt A: r2 v0 seed 100 GENERATED,            r3 v1 seed 101 REJECTED
 *   no attempt (pre-P6 row): r1 ARCHIVED, no seed
 * With K=1 the policy keeps attempt B (r4 selected, r5 within the last attempt), archives A (r2, r3) and does
 * nothing to r1 (archived, TEXT → no bytes to delete).
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin.
 * Run against the Docker stack, single-threaded:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookVariants.spec.js --workers=1 --project=chromium
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { randomUUID } from 'crypto';

function b64(str) { return Buffer.from(str).toString('base64'); }

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';
const ROLE = 'portrait0';

const POEM_A = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.`;

const POEM_B = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green.`;

// Seeded in beforeAll.
let bookObjectId = null;      // olio.pb.book objectId (the workflow route accepts it directly — B7)
let nodeObjectId = null;
let nodeHandle = null;
let attemptA = null;
let attemptB = null;
const art = {};               // r1..r5 → artifact objectId

async function loginRequest(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: b64('password'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login: HTTP ' + resp.status()).toBe(true);
}

async function loginAsSharedUser(page) {
    // Same pattern as pictureBookWorkflow.spec.js: REST login through page.request (shares the browser cookie
    // jar), WebSocket stubbed before page load so nginx's cookie-less WS upgrade can't bounce us to #!/sig.
    await loginRequest(page.request);
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

/** Open the workflow canvas, click the seeded node card and wait for its artifact strip. */
async function openNodeStrip(page) {
    await page.evaluate((oid) => { window.location.hash = '!/picture-book/' + oid + '/workflow'; }, bookObjectId);
    const card = page.locator('[data-node-id="' + nodeObjectId + '"]');
    await expect(card, 'seeded node card on the canvas').toBeVisible({ timeout: 30000 });
    await card.click();
    await expect(page.getByTestId('pbwf-art-history'), 'artifact strip loaded').toBeVisible({ timeout: 20000 });
}

async function findOrCreatePoem(request, orgId, poemsGroupId, name, title, text) {
    const search = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.cb.poem',
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId'], recordCount: 1, cache: false
        }
    });
    const body = await search.json().catch(() => null);
    if (body && body.results && body.results.length) return body.results[0].objectId;
    const resp = await request.post(REST + '/model', {
        data: { schema: 'olio.cb.poem', name, title, author: 'Variants Spec', groupId: poemsGroupId, text }
    });
    const created = await resp.json().catch(() => null);
    return created && created.objectId;
}

test.describe('PictureBook Variants — strip + retention (V3 P6-4)', () => {
    test.describe.configure({ timeout: 150000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await loginRequest(request);

        const principalResp = await request.get(REST + '/login/principal');
        const principal = await principalResp.json().catch(() => null);
        let orgId = principal && principal.organizationId;

        const poemsDir = await request.get(
            REST + '/path/make/auth.group/data/B64-' + b64('~/Poems').replace(/=/g, '%3D'));
        const poemsDirBody = await poemsDir.json().catch(() => null);
        const poemsGroupId = poemsDirBody && poemsDirBody.id;
        if (orgId == null && poemsDirBody) orgId = poemsDirBody.organizationId;
        expect(poemsGroupId, '~/Poems group').toBeTruthy();

        const pa = await findOrCreatePoem(request, orgId, poemsGroupId, 'variants-spec-poem-alpha', 'Falling Leaves (Variants)', POEM_A);
        const pb = await findOrCreatePoem(request, orgId, poemsGroupId, 'variants-spec-poem-beta', 'Winter (Variants)', POEM_B);
        expect(pa && pb, 'poems seeded').toBeTruthy();

        // A ChapBook is a native PB2 olio.pb.book with a workflow; the canvas route takes its objectId directly.
        const ts = Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug: 'variants-spec-' + ts, title: 'Variants Spec ChapBook', poemObjectIds: [pa, pb], maxLinesPerPage: 20 }
        });
        expect(createResp.ok(), 'ChapBook create: HTTP ' + createResp.status()).toBe(true);
        const created = await createResp.json();
        bookObjectId = created && created.objectId;
        expect(bookObjectId, 'ChapBook objectId').toBeTruthy();

        const PB = REST + '/olio/picture-book/' + bookObjectId;
        const wfResp = await request.get(PB + '/workflow');
        expect(wfResp.ok(), 'GET /workflow: HTTP ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        const wfSearch = await request.post(REST + '/model/search', {
            data: {
                schema: 'io.query', type: 'olio.pb.workflow',
                fields: [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }],
                request: ['id', 'objectId', 'groupId', 'groupPath'], recordCount: 1, cache: false
            }
        });
        const wfBody = await wfSearch.json();
        const wfRec = wfBody && wfBody.results && wfBody.results[0];
        expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

        nodeHandle = 'p64-' + ts;
        const nodeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.pb.node',
                name: 'Node ' + nodeHandle,
                groupId: wfRec.groupId,
                groupPath: wfRec.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId },
                handle: nodeHandle,
                nodeType: 'portrait',
                nodeStatus: 'done',
                ordinal: 920
            }
        });
        expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
        const node = await nodeResp.json();
        nodeObjectId = node && node.objectId;
        expect(nodeObjectId && node.id, 'node identity').toBeTruthy();

        attemptA = randomUUID();
        attemptB = randomUUID();
        const seed = async (key, fields) => {
            const resp = await request.post(REST + '/model', {
                data: Object.assign({
                    schema: 'olio.pb.artifact',
                    name: 'Artifact ' + nodeHandle + ' ' + ROLE + ' ' + key,
                    groupId: wfRec.groupId,
                    groupPath: wfRec.groupPath,
                    producedByNode: { schema: 'olio.pb.node', id: node.id, objectId: node.objectId },
                    role: ROLE,
                    artifactType: 'text',
                    artifactText: 'p6-4 ' + key,
                    byteLength: 1024,
                    selected: false,
                    reviewStatus: 'generated'
                }, fields)
            });
            expect(resp.ok(), 'create artifact ' + key + ': HTTP ' + resp.status()).toBe(true);
            const rec = await resp.json();
            expect(rec && rec.objectId, 'artifact ' + key + ' objectId').toBeTruthy();
            art[key] = rec.objectId;
        };
        // r1 models a pre-Phase-6 archived TEXT row: no attempt, no seed, and byteLength 0 — persistArtifact
        // sets byteLength only when it was handed bytes (PbArtifactUtil), so a real archived TEXT artifact never
        // satisfies the server's bytesDeleted test (ARCHIVED && data == null && byteLength > 0).
        await seed('r1', { revision: 1, reviewStatus: 'archived', byteLength: 0 });
        await seed('r2', { revision: 2, attempt: attemptA, variantIndex: 0, seed: 100 });
        await seed('r3', { revision: 3, attempt: attemptA, variantIndex: 1, seed: 101, reviewStatus: 'rejected' });
        await seed('r4', { revision: 4, attempt: attemptB, variantIndex: 0, seed: 200, selected: true });
        await seed('r5', { revision: 5, attempt: attemptB, variantIndex: 1, seed: 201 });

        await request.get(REST + '/logout');
    });

    // ── REST: the retention routes the dialog drives ───────────────────────────────────────────────

    test('GET /retention dry run decides the fixture per §23 and rejects an invalid K', async ({ request }) => {
        await loginRequest(request);
        const PB = REST + '/olio/picture-book/' + bookObjectId;

        const resp = await request.get(PB + '/retention?keepAttempts=1&deleteBytesAfterDays=30');
        const text = await resp.text();
        expect(resp.status(), 'GET /retention: HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(200);
        const sweep = JSON.parse(text);
        expect(sweep.scope).toBe('book');
        expect(sweep.bookObjectId).toBe(bookObjectId);
        expect(sweep.keepAttempts).toBe(1);
        expect(sweep.deleteBytesAfterDays).toBe(30);
        expect(sweep.checkedAt, 'dry run carries checkedAt').toBeTruthy();
        expect(sweep.appliedAt, 'dry run never carries appliedAt').toBeUndefined();

        const mine = (sweep.items || []).filter(it => it.nodeObjectId === nodeObjectId);
        const byId = Object.fromEntries(mine.map(it => [it.objectId, it]));
        expect(mine.length, 'fixture rows in the sweep: ' + JSON.stringify(mine).slice(0, 600)).toBe(5);
        expect(byId[art.r4].action).toBe('KEEP');
        expect(byId[art.r4].reason).toBe('selected');
        expect(byId[art.r4].selected).toBe(true);
        expect(byId[art.r5].action).toBe('KEEP');
        expect(byId[art.r5].reason).toContain('within the last 1 attempt');
        expect(byId[art.r2].action).toBe('ARCHIVE');
        expect(byId[art.r3].action).toBe('ARCHIVE');
        expect(byId[art.r3].reviewStatus).toBe('REJECTED');
        expect(byId[art.r1].action).toBe('NONE');
        expect(byId[art.r1].hasBytes, 'TEXT artifact holds no data.data').toBe(false);
        expect(byId[art.r2].attempt).toBe(attemptA);
        expect(byId[art.r2].variantIndex).toBe(0);
        expect(byId[art.r3].variantIndex).toBe(1);
        expect(byId[art.r4].nodeHandle).toBe(nodeHandle);
        // Totals are book-wide; the fixture is the floor.
        expect(sweep.counts.archive).toBeGreaterThanOrEqual(2);
        expect(sweep.counts.keep).toBeGreaterThanOrEqual(2);
        expect(sweep.counts.none).toBeGreaterThanOrEqual(1);
        expect(sweep.total).toBe(sweep.counts.keep + sweep.counts.archive + sweep.counts.deleteBytes + sweep.counts.none);

        const bad = await request.get(PB + '/retention?keepAttempts=0');
        expect(bad.status(), 'keepAttempts=0 is a 400').toBe(400);
        const badText = await bad.text();
        expect(badText).toContain('keepAttempts');

        await request.get(REST + '/logout');
    });

    // ── UI: the artifact strip ──────────────────────────────────────────────────────────────────

    test('strip groups variants by attempt with v/seed badges, status tint, archived toggle and compare', async ({ page }) => {
        await loginAsSharedUser(page);
        await openNodeStrip(page);
        const strip = page.locator('[data-testid="pbwf-art-role"][data-role="' + ROLE + '"]');
        await expect(strip).toBeVisible();

        // Two attempt groups shown (the pre-P6 archived row is hidden), newest attempt first.
        const groups = strip.getByTestId('pbwf-art-attempt');
        await expect(groups).toHaveCount(2);
        await expect(groups.nth(0)).toHaveAttribute('data-attempt', attemptB);
        await expect(groups.nth(1)).toHaveAttribute('data-attempt', attemptA);
        await expect(groups.nth(0)).toContainText('Attempt ' + attemptB.slice(0, 8) + ' · 2 variants');

        // Four visible tiles; v/seed badges per variant; selected + rejected tints by status.
        const tiles = strip.getByTestId('pbwf-art-tile');
        await expect(tiles).toHaveCount(4);
        const tileB0 = strip.locator('[data-testid="pbwf-art-tile"][data-artifact-id="' + art.r4 + '"]');
        const tileB1 = strip.locator('[data-testid="pbwf-art-tile"][data-artifact-id="' + art.r5 + '"]');
        const tileA1 = strip.locator('[data-testid="pbwf-art-tile"][data-artifact-id="' + art.r3 + '"]');
        await expect(tileB0.getByTestId('pbwf-art-variant-badge')).toHaveText('v0');
        await expect(tileB0.getByTestId('pbwf-art-seed-badge')).toHaveText('· s200');
        await expect(tileB1.getByTestId('pbwf-art-variant-badge')).toHaveText('v1');
        await expect(tileB1.getByTestId('pbwf-art-seed-badge')).toHaveText('· s201');
        await expect(tileB0).toHaveAttribute('data-review-status', 'generated');
        await expect(tileB0).toHaveAttribute('title', /Rev 4 · v0 · seed 200 · current · generated/);
        await expect(tileA1).toHaveAttribute('data-review-status', 'rejected');
        await expect(tileA1.getByTestId('pbwf-art-seed-badge')).toHaveText('· s101');
        // Selected tile has the blue frame, rejected the grey one (artifactTileStyle).
        await expect(tileB0.locator('div').first()).toHaveCSS('border-top-color', 'rgb(59, 130, 246)');
        await expect(tileA1.locator('div').first()).toHaveCSS('border-top-color', 'rgb(156, 163, 175)');

        // Archived toggle reveals the pre-P6 row as its own single-variant group (no attempt → no v badge).
        const toggle = strip.getByTestId('pbwf-art-archived-toggle');
        await expect(toggle).toHaveText('Show archived (1)');
        await toggle.click();
        await expect(toggle).toHaveText('Hide archived (1)');
        await expect(groups).toHaveCount(3);
        await expect(tiles).toHaveCount(5);
        const tileR1 = strip.locator('[data-testid="pbwf-art-tile"][data-artifact-id="' + art.r1 + '"]');
        await expect(tileR1).toHaveAttribute('data-review-status', 'archived');
        await expect(tileR1.getByTestId('pbwf-art-variant-badge')).toHaveCount(0);
        // The server sends `seed: 0` for this row (long column, DDL default 0) — that must not become a badge.
        await expect(tileR1.getByTestId('pbwf-art-seed-badge')).toHaveCount(0);
        // byteLength 0 → the server does not flag it bytesDeleted, so no "bytes deleted" placeholder either.
        await expect(tileR1.getByTestId('pbwf-art-bytes-deleted')).toHaveCount(0);
        await expect(groups.nth(2)).toHaveAttribute('data-attempt', '');
        await expect(groups.nth(2)).toContainText('Rev 1');
        await toggle.click();
        await expect(groups).toHaveCount(2);

        // Compare: A then B, captions carry role/rev/variant/seed/status/bytes; Clear empties the pane.
        await expect(page.getByTestId('pbwf-art-compare')).toHaveCount(0);
        await tileB0.click();
        await expect(page.getByTestId('pbwf-art-compare')).toBeVisible();
        await expect(page.getByTestId('pbwf-art-compare')).toContainText('Pick a second tile to compare');
        await expect(tileB0).toHaveAttribute('data-compare', '1');
        await tileA1.click();
        const items = page.getByTestId('pbwf-art-compare-item');
        await expect(items).toHaveCount(2);
        await expect(items.nth(0)).toContainText('A ' + ROLE + ' r4');
        await expect(items.nth(0)).toContainText('v0 · seed 200 · generated · 1.0 KB');
        await expect(items.nth(1)).toContainText('B ' + ROLE + ' r3');
        await expect(items.nth(1)).toContainText('v1 · seed 101 · rejected');
        await expect(tileA1).toHaveAttribute('data-compare', '2');
        await page.getByTestId('pbwf-art-compare-clear').click();
        await expect(page.getByTestId('pbwf-art-compare')).toHaveCount(0);
        await page.screenshot({ path: 'e2e/screenshots/variants-strip.png' });
    });

    // ── UI: Variant Retention dialog, dry run → apply, strip reload ────────────────────────────

    test('Retention dialog: dry run (K=1) → Apply archives the older attempt and the strip reloads', async ({ page }) => {
        // With a node selected, the fixed node detail panel (right:16px, top:80px, width 280px, z-index 200)
        // overlays the right end of the canvas toolbar at the default 1280px viewport, and its status badge
        // intercepts the click on the rightmost toolbar button — the new Retention button. Pre-existing
        // panel/toolbar geometry, recorded for Stephen in the ledger (§6.C 212 n), not changed here; a wider
        // viewport keeps the live selectNode → reloadNodeDetails path in the test.
        await page.setViewportSize({ width: 1600, height: 900 });
        await loginAsSharedUser(page);
        await openNodeStrip(page);
        const strip = page.locator('[data-testid="pbwf-art-role"][data-role="' + ROLE + '"]');
        await expect(strip.getByTestId('pbwf-art-attempt')).toHaveCount(2);

        // The same dry run over REST — the dialog must show these numbers.
        const PB = REST + '/olio/picture-book/' + bookObjectId;
        const restResp = await page.request.get(PB + '/retention?keepAttempts=1');
        expect(restResp.status(), 'REST dry run').toBe(200);
        const restSweep = await restResp.json();
        const expectArchive = (restSweep.items || []).filter(it => it.action === 'ARCHIVE').length;
        expect(expectArchive).toBeGreaterThanOrEqual(2);

        await page.getByTestId('pbwf-retention-open').click();
        const dialog = page.getByTestId('pbwf-retention-dialog');
        await expect(dialog).toBeVisible();
        const scanBtn = page.getByTestId('pbwf-retention-scan');
        const applyBtn = page.getByTestId('pbwf-retention-apply');
        await expect(applyBtn, 'Apply disabled before any dry run').toBeDisabled();

        await page.getByTestId('pbwf-retention-keep').fill('1');
        await scanBtn.click();
        const summary = page.getByTestId('pbwf-retention-summary');
        await expect(summary).toBeVisible({ timeout: 20000 });
        await expect(summary).toContainText('K=1, D=30');
        await expect(page.getByTestId('pbwf-retention-archive-count')).toHaveText(expectArchive + ' archive');
        await expect(page.getByTestId('pbwf-retention-delete-count')).toHaveText(restSweep.counts.deleteBytes + ' delete bytes');
        const archiveRows = page.locator('[data-testid="pbwf-retention-item"][data-action="ARCHIVE"]');
        await expect(archiveRows).toHaveCount(expectArchive);
        await expect(archiveRows.filter({ hasText: nodeHandle + ' · ' + ROLE + ' r2 v0' })).toHaveCount(1);
        await expect(archiveRows.filter({ hasText: nodeHandle + ' · ' + ROLE + ' r3 v1' })).toHaveCount(1);
        await expect(page.locator('[data-testid="pbwf-retention-item"][data-action="KEEP"]'), 'only actionable rows are listed').toHaveCount(0);
        await expect(applyBtn).toBeEnabled();

        // Editing K discards the scan: Apply goes dark until a fresh dry run of the new inputs.
        await page.getByTestId('pbwf-retention-keep').fill('2');
        await expect(summary).toHaveCount(0);
        await expect(applyBtn).toBeDisabled();
        await page.getByTestId('pbwf-retention-keep').fill('1');
        await scanBtn.click();
        await expect(summary).toBeVisible({ timeout: 20000 });
        await expect(applyBtn).toBeEnabled();

        await applyBtn.click();
        const result = page.getByTestId('pbwf-retention-result');
        await expect(result).toBeVisible({ timeout: 30000 });
        await expect(page.getByTestId('pbwf-retention-archived')).toHaveText(expectArchive + ' archived');
        await expect(page.getByTestId('pbwf-retention-bytes-deleted')).toHaveText('0 bytes-deleted');
        const archivedRows = page.locator('[data-testid="pbwf-retention-item"][data-outcome="archived"]');
        await expect(archivedRows).toHaveCount(expectArchive);
        await expect(archivedRows.filter({ hasText: nodeHandle + ' · ' + ROLE + ' r2 v0' })).toHaveCount(1);
        await expect(applyBtn, 'the scan is consumed by Apply').toBeDisabled();
        await page.screenshot({ path: 'e2e/screenshots/variants-retention-applied.png' });

        // A second dry run of the same inputs finds nothing left to do.
        await scanBtn.click();
        await expect(page.getByTestId('pbwf-retention-nothing')).toBeVisible({ timeout: 20000 });
        await expect(applyBtn).toBeDisabled();
        await page.locator('.am7-dialog-footer button.am7-dialog-btn', { hasText: 'Close' }).click();
        await expect(dialog).toHaveCount(0);

        // The selected node's strip was re-read: attempt A is archived, so only B is shown and the toggle counts 3.
        await expect(strip.getByTestId('pbwf-art-archived-toggle')).toHaveText('Show archived (3)', { timeout: 20000 });
        await expect(strip.getByTestId('pbwf-art-attempt')).toHaveCount(1);
        await expect(strip.getByTestId('pbwf-art-attempt').first()).toHaveAttribute('data-attempt', attemptB);
        await expect(strip.getByTestId('pbwf-art-tile')).toHaveCount(2);
        await strip.getByTestId('pbwf-art-archived-toggle').click();
        await expect(strip.getByTestId('pbwf-art-tile')).toHaveCount(5);
        await expect(strip.locator('[data-testid="pbwf-art-tile"][data-review-status="archived"]')).toHaveCount(3);

        // Raw REST confirms the persisted state the strip is showing.
        const nodeResp = await page.request.get(PB + '/workflow/node/' + nodeObjectId);
        expect(nodeResp.status()).toBe(200);
        const nv = await nodeResp.json();
        const chain = (nv.artifacts && nv.artifacts[ROLE]) || [];
        const byId = Object.fromEntries(chain.map(a => [a.objectId, a]));
        expect(Object.keys(byId).length).toBe(5);
        expect(byId[art.r2].reviewStatus).toBe('ARCHIVED');
        expect(byId[art.r2].archivedAt, 'archive() stamps archivedAt').toBeTruthy();
        expect(byId[art.r3].reviewStatus).toBe('ARCHIVED');
        expect(byId[art.r4].reviewStatus).toBe('GENERATED');
        expect(byId[art.r4].selected).toBe(true);
        expect(byId[art.r5].reviewStatus).toBe('GENERATED');
        expect(byId[art.r1].reviewStatus).toBe('ARCHIVED');
        const again = await (await page.request.get(PB + '/retention?keepAttempts=1')).json();
        expect((again.items || []).filter(it => it.nodeObjectId === nodeObjectId && it.action === 'ARCHIVE').length).toBe(0);
    });
});
