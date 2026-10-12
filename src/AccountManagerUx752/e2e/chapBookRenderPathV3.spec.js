/**
 * ChapBook on the v3 render path (PictureBook V3 Phase 1, P1-9) — LIVE SD end-to-end over REST.
 *
 * With chapbook.renderPath=v3 (the Docker test stack), a ChapBook page render is a graph node run through
 * the same pb.render job the PictureBook pipeline uses, and the result is an olio.pb.artifact in the node's
 * version chain with a review status. This spec proves the three v3 job-shape bodies off H2 — against the
 * real SD server (192.168.1.39) through Docker Tomcat — and then reads the artifact row, the review
 * transitions, the stale mapping and the reader back over REST and in the browser:
 *
 *   TEST A (SD): POST /scene/{id}/generate on v3 → 200 (or 202 PENDING, then polled) carrying renderPath=v3,
 *     jobId/runObjectId/nodeObjectId/artifactObjectId and nodeStatus; GET /rest/job/{jobId} is a completed
 *     pb.render job naming that node; the artifact is the selected landscape revision 1, GENERATED, whose
 *     dataObjectId is the scene's imageObjectId; approve pins the node (a re-render is then 409 "pinned");
 *     reject keeps the lone revision selected but REJECTED; unpin + a prompt edit mark the node STALE; a
 *     re-render supersedes revision 1 with a selected revision 2 and clears imageStale; the reader shows the
 *     image (naturalWidth > 0) and is screenshotted for visual inspection.
 *   TEST B (SD): the book's auto-approve switch: a render with autoApprove=true answers autoApproved=true /
 *     APPROVED, the artifact's approvedBy is AUTO, the node is pinned, and the review queue's
 *     auto-approved filter lists it.
 *   TEST C (SD): POST /render/{book}?async=true → 202 {pending:true, jobId, runObjectId, plannedNodeCount}
 *     for one pb.renderAll job; polled to completion every page carries an image.
 *   TEST D (SD): the synchronous bulk POST /render/{book} → 200 with settled counts on v3.
 *   TEST E (no SD): the v3 review surface without a render — settings/auto-approve GET/PUT contract and the
 *     review-queue filter validation. Skips itself on a v1 deployment (settings answers 409 there).
 *
 * A–D are GATED behind CB_LIVE_GEN so the default suite never fires the GPU; run them SINGLE-THREADED:
 *   CB_LIVE_GEN=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/chapBookRenderPathV3.spec.js --project=chromium --workers=1
 *
 * Every page is given an explicit, locked landscape prompt (PUT /scene/{id}/prompt) before a bulk render so
 * the v1 prompt pre-step never calls the LLM: these tests are SD-only. Timings are recorded (durationMs on
 * the bodies, logged) and never asserted; the only bounds are the generous test timeouts.
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages, sceneByObjectId,
    REST, CB_REST, PB_REST, POEM_1, POEM_2
} from './helpers/chapbook.js';

const LIVE = !!process.env.CB_LIVE_GEN;
const RENDER_TEST_TIMEOUT_MS = 45 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 60 * 60 * 1000;

const PROMPT_A = 'a wide misty autumn landscape at dawn, ancient oak trees, drifting falling leaves, '
    + 'soft golden light, cinematic, highly detailed, no people, no text';
const PROMPT_B = 'a quiet winter lake at dusk, snow-covered pines, pale violet sky, thin ice, '
    + 'soft diffuse light, cinematic, highly detailed, no people, no text';
const PROMPT_EDIT = 'a sunlit summer meadow, wildflowers, distant blue hills, cumulus clouds, '
    + 'warm afternoon light, cinematic, highly detailed, no people, no text';

const DONE_STATUSES = ['DONE', 'DONE_UNVERIFIED'];

async function seed(request, slug, poemSpecs, maxLinesPerPage) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const poemOids = [];
    for (const p of poemSpecs) {
        poemOids.push(await ensurePoem(request, groupId, organizationId, p.name, p.title, p.text));
    }
    const bookOid = await createChapBook(request, slug + '-' + Date.now(), 'E2E v3 Render Book', poemOids, maxLinesPerPage);
    const pages = await bookPages(request, bookOid);
    expect(pages.length, 'book has pages').toBeGreaterThanOrEqual(1);
    return { bookOid, pages };
}

const POEMS_BOTH = [
    { name: 'E2E-CB-Poem-1', title: 'Memory', text: POEM_1 },
    { name: 'E2E-CB-Poem-2', title: 'Winter', text: POEM_2 }
];

async function jsonOf(r, what) {
    const text = await r.text();
    expect(r.ok(), what + ' failed: HTTP ' + r.status() + ' ' + text).toBe(true);
    return JSON.parse(text);
}

async function getJob(request, jobId) {
    return jsonOf(await request.get(REST + '/job/' + jobId), 'GET /job/' + jobId);
}

// Poll GET /rest/job/{jobId} until the registry reports it terminal. The loop is bounded only by the
// test timeout: a render's duration is never a pass/fail criterion here.
async function awaitJob(request, jobId) {
    for (;;) {
        const j = await getJob(request, jobId);
        if (j.terminal) return j;
        await new Promise(resolve => setTimeout(resolve, 3000));
    }
}

async function nodeView(request, bookOid, nodeOid) {
    return jsonOf(await request.get(PB_REST + '/' + bookOid + '/workflow/node/' + nodeOid), 'GET workflow/node');
}

async function artifactView(request, bookOid, artifactOid) {
    return jsonOf(await request.get(PB_REST + '/' + bookOid + '/artifact/' + artifactOid), 'GET artifact');
}

async function setPrompt(request, sceneOid, sdPrompt) {
    const b = await jsonOf(await request.put(CB_REST + '/scene/' + sceneOid + '/prompt', { data: { sdPrompt } }), 'PUT scene prompt');
    expect(b.updated, 'prompt persisted on ' + sceneOid).toBe(true);
}

async function setAutoApprove(request, bookOid, autoApprove) {
    const b = await jsonOf(await request.put(PB_REST + '/' + bookOid + '/settings/auto-approve', { data: { autoApprove } }), 'PUT settings/auto-approve');
    expect(b.bookObjectId).toBe(bookOid);
    expect(b.autoApprove, 'auto-approve switch re-read after the write').toBe(autoApprove);
    return b;
}

// Render one page on v3. A 200 answers the settled result; a 202 means the page's pb.render job ran past
// the server's synchronous bound, so the job is polled and the node/artifact read back the same way the
// Ux will. Returns the settled generate body plus the node view.
async function renderPage(request, bookOid, sceneOid, sdPrompt) {
    const data = { schema: 'olio.pictureBookRequest' };
    if (sdPrompt) data.sdPrompt = sdPrompt;
    const started = Date.now();
    const r = await request.post(CB_REST + '/scene/' + sceneOid + '/generate', { data, timeout: REQUEST_TIMEOUT_MS });
    const text = await r.text();
    expect([200, 202].includes(r.status()), 'generate on v3 answered HTTP ' + r.status() + ' ' + text).toBe(true);
    const body = JSON.parse(text);
    console.log('[v3 generate] HTTP ' + r.status() + ' in ' + (Date.now() - started) + 'ms →', JSON.stringify(body));
    expect(body.renderPath, 'the page went down the v3 path').toBe('v3');
    expect(body.jobId, 'a pb.render job id').toBeTruthy();
    expect(body.runObjectId, 'an olio.pb.run objectId').toBeTruthy();
    expect(body.nodeObjectId, 'the page node objectId').toBeTruthy();
    expect(body.skipped).toBe(false);
    if (r.status() === 202) {
        expect(body.pending, 'a 202 is the PENDING shape').toBe(true);
        const job = await awaitJob(request, body.jobId);
        console.log('[v3 generate] polled job →', JSON.stringify(job));
        expect(job.status).toBe('completed');
        const nv = await nodeView(request, bookOid, body.nodeObjectId);
        const chain = (nv.artifacts && nv.artifacts.landscape) || [];
        expect(chain.length, 'the polled node has a landscape artifact').toBeGreaterThanOrEqual(1);
        const sel = chain.find(a => a.selected) || chain[0];
        return {
            status: r.status(), body, node: nv,
            artifactObjectId: sel.objectId, imageObjectId: sel.dataObjectId,
            nodeStatus: nv.storedStatus, reviewStatus: sel.reviewStatus, autoApproved: body.autoApproved
        };
    }
    expect(body.pending).toBe(false);
    expect(body.rendered, 'the page rendered (job COMPLETED, node DONE, image present)').toBe(true);
    expect(body.imageObjectId, 'the selected artifact data objectId').toBeTruthy();
    expect(body.artifactObjectId, 'the selected artifact objectId').toBeTruthy();
    expect(DONE_STATUSES, 'node status after a render: ' + body.nodeStatus).toContain(body.nodeStatus);
    const nv = await nodeView(request, bookOid, body.nodeObjectId);
    return {
        status: r.status(), body, node: nv,
        artifactObjectId: body.artifactObjectId, imageObjectId: body.imageObjectId,
        nodeStatus: body.nodeStatus, reviewStatus: body.reviewStatus, autoApproved: body.autoApproved
    };
}

test.describe('ChapBook on the v3 render path — LIVE SD over REST', () => {

    test('A: page render is a pb.render job with an artifact; approve/reject/stale/re-render apply; reader shows it', async ({ page, request }) => {
        test.skip(!LIVE, 'set CB_LIVE_GEN=1 to run the live SD v3 render tests');
        test.setTimeout(RENDER_TEST_TIMEOUT_MS);

        const { bookOid, pages } = await seed(request, 'e2e-cb-v3-page', POEMS_BOTH, 4);
        const scene0 = pages[0].objectId;
        expect(pages[0].imageName, 'a fresh page has no image yet').toBeFalsy();

        // ── 1. The per-page body, moved off H2: 200/202 with the v3 job shape ──
        const first = await renderPage(request, bookOid, scene0, PROMPT_A);
        expect(first.autoApproved, 'auto-approve is off on a fresh book').toBe(false);
        expect(first.reviewStatus.toUpperCase(), 'a fresh render is GENERATED').toBe('GENERATED');

        // ── 2. The job the body names is the completed pb.render job for this node ──
        const job = await getJob(request, first.body.jobId);
        console.log('[v3 job] →', JSON.stringify(job));
        expect(job.jobId).toBe(first.body.jobId);
        expect(job.kind).toBe('pb.render');
        expect(job.status).toBe('completed');
        expect(job.terminal).toBe(true);
        expect(job.result, 'the job carries its parsed result').toBeTruthy();
        expect(job.result.runObjectId).toBe(first.body.runObjectId);
        expect(job.result.plannedNodeCount).toBe(1);
        expect(job.result.executedNodeCount).toBe(1);
        expect(job.result.failedNodeCount).toBe(0);
        const jobNode = (job.result.nodes || []).find(n => n.nodeObjectId === first.body.nodeObjectId);
        expect(jobNode, 'the job result names the page node').toBeTruthy();
        expect(DONE_STATUSES).toContain(String(jobNode.status).toUpperCase());
        console.log('[v3 job] node durationMs=' + jobNode.durationMs + ' total durationMs=' + job.result.durationMs);

        // ── 3. The artifact row: selected landscape revision 1, GENERATED, data = the scene image ──
        const art1 = await artifactView(request, bookOid, first.artifactObjectId);
        console.log('[v3 artifact 1] →', JSON.stringify(Object.assign({}, art1, { generatorRequest: undefined, sdConfigSnapshot: undefined })));
        expect(art1.objectId).toBe(first.artifactObjectId);
        expect(art1.role).toBe('landscape');
        expect(art1.selected).toBe(true);
        expect(art1.revision).toBe(1);
        expect(String(art1.reviewStatus).toUpperCase()).toBe('GENERATED');
        expect(art1.dataObjectId, 'the artifact data is the scene image').toBe(first.imageObjectId);
        expect(art1.byteLength, 'real image bytes').toBeGreaterThan(0);
        expect(art1.imageWidth).toBeGreaterThan(0);
        expect(art1.imageHeight).toBeGreaterThan(0);
        expect(art1.supersedesObjectId, 'revision 1 supersedes nothing').toBeFalsy();

        // ── 4. The node and run views agree with the body ──
        expect(DONE_STATUSES).toContain(String(first.node.storedStatus).toUpperCase());
        expect(first.node.pinned).toBe(false);
        expect(first.node.artifacts && first.node.artifacts.landscape, 'node view groups the chain by role').toBeTruthy();
        expect(first.node.artifacts.landscape.length).toBe(1);
        expect(first.node.artifacts.landscape[0].objectId).toBe(first.artifactObjectId);
        const run = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/run/' + first.body.runObjectId), 'GET run');
        console.log('[v3 run] →', JSON.stringify(run));
        expect(run.runStatus).toBe('COMPLETED');
        expect(run.executedNodeCount).toBe(1);
        expect(run.failedNodeCount).toBe(0);
        expect(run.resumable).toBe(false);

        // ── 5. The page view: image fields surfaced, imageStale cleared by the executor ──
        let scene = await sceneByObjectId(request, scene0);
        expect(scene.imageStale, 'the executor clears imageStale').toBe(false);
        let after = (await bookPages(request, bookOid)).find(p => p.objectId === scene0);
        expect(after.imageName, 'pages payload carries the rendered image name').toBeTruthy();
        expect(after.dataObjectId).toBe(first.imageObjectId);
        expect(after.imageStale).toBe(false);

        // ── 6. Approve: APPROVED by the caller, node pinned; a re-render of a pinned page is 409 ──
        const approved = await jsonOf(await request.put(PB_REST + '/' + bookOid + '/artifact/' + first.artifactObjectId + '/approve', { data: {} }), 'PUT approve');
        console.log('[v3 approve] →', JSON.stringify(approved));
        expect(String(approved.reviewStatus).toUpperCase()).toBe('APPROVED');
        expect(approved.selected).toBe(true);
        expect(approved.approvedBy, 'a human approval stamps the approver').toBeTruthy();
        expect(approved.approvedBy).not.toBe('AUTO');
        expect(approved.nodeObjectId).toBe(first.body.nodeObjectId);
        let nv = await nodeView(request, bookOid, first.body.nodeObjectId);
        expect(nv.pinned, 'approve pins the node').toBe(true);

        const pinnedRender = await request.post(CB_REST + '/scene/' + scene0 + '/generate', {
            data: { schema: 'olio.pictureBookRequest', sdPrompt: PROMPT_A }, timeout: REQUEST_TIMEOUT_MS
        });
        const pinnedText = await pinnedRender.text();
        console.log('[v3 pinned re-render] HTTP ' + pinnedRender.status() + ' ' + pinnedText);
        expect(pinnedRender.status(), 'a pinned page refuses a re-render').toBe(409);
        expect(pinnedText).toContain('pinned');

        // ── 7. Reject: the lone revision stays selected but REJECTED, approver cleared ──
        const rejected = await jsonOf(await request.put(PB_REST + '/' + bookOid + '/artifact/' + first.artifactObjectId + '/reject', { data: {} }), 'PUT reject');
        console.log('[v3 reject] →', JSON.stringify(rejected));
        expect(String(rejected.reviewStatus).toUpperCase()).toBe('REJECTED');
        expect(rejected.selected, 'with no other revision the rejected one stays selected').toBe(true);
        expect(rejected.approvedBy).toBeFalsy();
        nv = await nodeView(request, bookOid, first.body.nodeObjectId);
        expect(nv.pinned, 'reject does not touch the pin').toBe(true);

        // ── 8. Unpin, then a prompt edit marks the page node STALE ──
        const unpinned = await jsonOf(await request.post(PB_REST + '/' + bookOid + '/node/' + first.body.nodeObjectId + '/pin', {
            data: { schema: 'olio.pictureBookRequest', pinned: false }
        }), 'POST node pin=false');
        expect(unpinned.pinned).toBe(false);
        await setPrompt(request, scene0, PROMPT_EDIT);
        scene = await sceneByObjectId(request, scene0);
        expect(scene.sdPrompt).toBe(PROMPT_EDIT);
        expect(scene.promptLocked, 'a human prompt edit locks the prompt').toBe(true);
        nv = await nodeView(request, bookOid, first.body.nodeObjectId);
        expect(String(nv.storedStatus).toUpperCase(), 'a prompt edit marks the page node STALE').toBe('STALE');
        expect(nv.promptText, 'the node mirrors the edited prompt').toBe(PROMPT_EDIT);

        // ── 9. Re-render (no override: the locked prompt is used) supersedes revision 1 ──
        const second = await renderPage(request, bookOid, scene0, null);
        expect(second.body.nodeObjectId, 'the page keeps its one node').toBe(first.body.nodeObjectId);
        expect(second.body.jobId).not.toBe(first.body.jobId);
        expect(second.artifactObjectId).not.toBe(first.artifactObjectId);
        expect(second.imageObjectId).not.toBe(first.imageObjectId);
        const art2 = await artifactView(request, bookOid, second.artifactObjectId);
        console.log('[v3 artifact 2] →', JSON.stringify(Object.assign({}, art2, { generatorRequest: undefined, sdConfigSnapshot: undefined })));
        expect(art2.revision).toBe(2);
        expect(art2.selected).toBe(true);
        expect(String(art2.reviewStatus).toUpperCase()).toBe('GENERATED');
        expect(art2.supersedesObjectId, 'revision 2 supersedes the rejected revision 1').toBe(first.artifactObjectId);
        expect(DONE_STATUSES).toContain(String(second.node.storedStatus).toUpperCase());
        // The chain is ascending by revision (PbArtifactUtil.listChain), measured 2026-10-11: revision 1 first.
        const chain = second.node.artifacts.landscape;
        expect(chain.length).toBe(2);
        expect(chain.map(a => a.revision), 'chain is ascending by revision').toEqual([1, 2]);
        expect(chain[0].objectId).toBe(first.artifactObjectId);
        expect(chain[0].selected).toBe(false);
        expect(String(chain[0].reviewStatus).toUpperCase()).toBe('REJECTED');
        expect(chain[1].objectId).toBe(second.artifactObjectId);
        expect(chain[1].selected).toBe(true);
        scene = await sceneByObjectId(request, scene0);
        expect(scene.imageStale, 're-render clears imageStale').toBe(false);
        after = (await bookPages(request, bookOid)).find(p => p.objectId === scene0);
        expect(after.dataObjectId, 'the page now shows revision 2').toBe(second.imageObjectId);

        // ── 10. The review queue lists the node with both revisions ──
        const queue = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/review-queue'), 'GET review-queue');
        console.log('[v3 review-queue] →', JSON.stringify(queue));
        const qn = (queue.nodes || []).find(n => n.nodeObjectId === first.body.nodeObjectId);
        expect(qn, 'the rendered page node is in the queue').toBeTruthy();
        expect(qn.artifacts.length).toBe(2);
        expect(queue.artifactCount).toBeGreaterThanOrEqual(2);

        // ── 11. Visual proof: the reader loads and decodes the v3 artifact image ──
        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
        await page.locator('button:has-text("Begin")').click();
        const img = page.locator('img.object-cover').first();
        await expect(img).toBeVisible({ timeout: 30000 });
        await page.waitForFunction(() => {
            const i = document.querySelector('img.object-cover');
            return i && i.complete && i.naturalWidth > 0;
        }, { timeout: 60000 });
        const dims = await img.evaluate(el => ({ w: el.naturalWidth, h: el.naturalHeight, src: el.src }));
        console.log('[v3 reader] rendered image:', JSON.stringify(dims));
        expect(dims.w, 'the v3 artifact image decoded in the browser').toBeGreaterThan(0);
        expect(dims.w).toBeGreaterThanOrEqual(dims.h);
        await page.screenshot({ path: 'test-results/chapbook-render-path-v3.png', fullPage: true });
    });

    test('B: auto-approve approves and pins the rendered page and the queue filter lists it', async ({ request }) => {
        test.skip(!LIVE, 'set CB_LIVE_GEN=1 to run the live SD v3 render tests');
        test.setTimeout(RENDER_TEST_TIMEOUT_MS);

        const { bookOid, pages } = await seed(request, 'e2e-cb-v3-auto', [POEMS_BOTH[0]], 12);
        const scene0 = pages[0].objectId;

        const before = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/settings/auto-approve'), 'GET settings/auto-approve');
        expect(before.autoApprove, 'a fresh book does not auto-approve').toBe(false);
        await setAutoApprove(request, bookOid, true);

        const res = await renderPage(request, bookOid, scene0, PROMPT_B);
        expect(res.autoApproved, 'the render body reports the auto-approval').toBe(true);
        expect(String(res.reviewStatus).toUpperCase()).toBe('APPROVED');
        expect(res.body.autoApproveSkipped).toBeFalsy();

        const art = await artifactView(request, bookOid, res.artifactObjectId);
        console.log('[v3 auto-approved artifact] →', JSON.stringify(Object.assign({}, art, { generatorRequest: undefined, sdConfigSnapshot: undefined })));
        expect(String(art.reviewStatus).toUpperCase()).toBe('APPROVED');
        expect(art.approvedBy, 'auto-approval is stamped AUTO').toBe('AUTO');
        expect(art.selected).toBe(true);
        expect(res.node.pinned, 'auto-approve pins the node').toBe(true);

        const autoQueue = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/review-queue?filter=auto-approved'), 'GET review-queue auto-approved');
        console.log('[v3 review-queue auto-approved] →', JSON.stringify(autoQueue));
        const qn = (autoQueue.nodes || []).find(n => n.nodeObjectId === res.body.nodeObjectId);
        expect(qn, 'the auto-approved node is listed by the filter').toBeTruthy();
        expect(qn.artifacts.every(a => a.approvedBy === 'AUTO'), 'the filter lists only AUTO approvals').toBe(true);
        expect(autoQueue.autoApprove).toBe(true);

        const job = await getJob(request, res.body.jobId);
        const jobNode = (job.result.nodes || []).find(n => n.nodeObjectId === res.body.nodeObjectId);
        expect(jobNode && jobNode.autoApproved, 'the job result records the auto-approval').toBe(true);

        await setAutoApprove(request, bookOid, false);
    });

    test('C: async bulk render is one pb.renderAll job answered 202 and every page gets an image', async ({ request }) => {
        test.skip(!LIVE, 'set CB_LIVE_GEN=1 to run the live SD v3 render tests');
        test.setTimeout(RENDER_TEST_TIMEOUT_MS);

        const { bookOid, pages } = await seed(request, 'e2e-cb-v3-bulk-async', [POEMS_BOTH[0]], 12);
        for (const p of pages) await setPrompt(request, p.objectId, PROMPT_A);

        const started = Date.now();
        const r = await request.post(CB_REST + '/render/' + bookOid + '?async=true', { data: {}, timeout: REQUEST_TIMEOUT_MS });
        const text = await r.text();
        console.log('[v3 bulk async] HTTP ' + r.status() + ' in ' + (Date.now() - started) + 'ms → ' + text);
        expect(r.status(), 'async bulk on v3 is a 202 submission').toBe(202);
        const body = JSON.parse(text);
        expect(body.pending).toBe(true);
        expect(body.renderPath).toBe('v3');
        expect(body.jobId).toBeTruthy();
        expect(body.runObjectId).toBeTruthy();
        expect(body.plannedNodeCount, 'every prompted page is planned').toBe(pages.length);
        expect(body.rendered).toBe(0);
        expect(body.skipped, 'no page is un-prompted').toBe(0);
        expect(body.failed).toBe(0);

        const job = await awaitJob(request, body.jobId);
        console.log('[v3 bulk async] job →', JSON.stringify(job));
        expect(job.kind).toBe('pb.renderAll');
        expect(job.status).toBe('completed');
        expect(job.result.runObjectId).toBe(body.runObjectId);
        expect(job.result.plannedNodeCount).toBe(pages.length);
        expect(job.result.executedNodeCount).toBe(pages.length);
        expect(job.result.failedNodeCount).toBe(0);
        expect((job.result.nodes || []).length).toBe(pages.length);
        for (const n of job.result.nodes) {
            expect(DONE_STATUSES, 'node ' + n.handle + ' status ' + n.status).toContain(String(n.status).toUpperCase());
        }

        const run = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/run/' + body.runObjectId), 'GET run');
        expect(run.runStatus).toBe('COMPLETED');
        expect(run.executedNodeCount).toBe(pages.length);

        const after = await bookPages(request, bookOid);
        for (const p of after) {
            expect(p.imageName, 'page ' + p.sceneIndex + ' carries its rendered image').toBeTruthy();
            expect(p.imageStale, 'page ' + p.sceneIndex + ' is not stale').toBe(false);
        }
        const queue = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/review-queue'), 'GET review-queue');
        expect(queue.nodes.length, 'one node per rendered page in the queue').toBe(pages.length);
        expect(queue.artifactCount).toBe(pages.length);
    });

    test('D: synchronous bulk render settles on v3 with real counts', async ({ request }) => {
        test.skip(!LIVE, 'set CB_LIVE_GEN=1 to run the live SD v3 render tests');
        test.setTimeout(RENDER_TEST_TIMEOUT_MS);

        const { bookOid, pages } = await seed(request, 'e2e-cb-v3-bulk-sync', [POEMS_BOTH[1]], 12);
        for (const p of pages) await setPrompt(request, p.objectId, PROMPT_B);

        const started = Date.now();
        const r = await request.post(CB_REST + '/render/' + bookOid, { data: {}, timeout: REQUEST_TIMEOUT_MS });
        const text = await r.text();
        console.log('[v3 bulk sync] HTTP ' + r.status() + ' in ' + (Date.now() - started) + 'ms → ' + text);
        expect(r.status(), 'the synchronous bulk render settled').toBe(200);
        const body = JSON.parse(text);
        expect(body.renderPath).toBe('v3');
        expect(body.pending).toBe(false);
        expect(body.jobId, 'the sync path still ran as a job').toBeTruthy();
        expect(body.runObjectId).toBeTruthy();
        expect(body.plannedNodeCount).toBe(pages.length);
        expect(body.rendered, 'every prompted page rendered').toBe(pages.length);
        expect(body.failed).toBe(0);
        expect(body.skipped).toBe(0);

        const job = await getJob(request, body.jobId);
        expect(job.kind).toBe('pb.renderAll');
        expect(job.status).toBe('completed');
        expect(job.result.executedNodeCount).toBe(pages.length);

        const after = await bookPages(request, bookOid);
        for (const p of after) {
            expect(p.imageName, 'page ' + p.sceneIndex + ' carries its rendered image').toBeTruthy();
            expect(p.imageStale).toBe(false);
        }
    });

    test('E: the v3 review surface answers without a render (settings + queue filter contract)', async ({ request }) => {
        const { bookOid } = await seed(request, 'e2e-cb-v3-surface', [POEMS_BOTH[0]], 12);

        const probe = await request.get(PB_REST + '/' + bookOid + '/settings/auto-approve');
        test.skip(probe.status() === 409, 'this deployment serves ChapBook on the v1 render path');
        const before = await jsonOf(probe, 'GET settings/auto-approve');
        expect(before.bookObjectId).toBe(bookOid);
        expect(before.autoApprove).toBe(false);

        const bad = await request.put(PB_REST + '/' + bookOid + '/settings/auto-approve', { data: { autoApprove: 'yes' } });
        expect(bad.status(), 'a non-boolean autoApprove is a 400').toBe(400);
        const missing = await request.put(PB_REST + '/' + bookOid + '/settings/auto-approve', { data: {} });
        expect(missing.status(), 'a missing autoApprove is a 400').toBe(400);
        await setAutoApprove(request, bookOid, true);
        const on = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/settings/auto-approve'), 'GET settings/auto-approve');
        expect(on.autoApprove).toBe(true);
        await setAutoApprove(request, bookOid, false);

        // The wire mapper is Include.NON_EMPTY (JSONUtil.java:159): an empty `nodes` list is omitted, not
        // serialized as [] - consumers must default it. Measured 2026-10-11 against the Docker stack.
        const empty = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/review-queue'), 'GET review-queue');
        console.log('[v3 review-queue empty] →', JSON.stringify(empty));
        expect(empty.bookObjectId).toBe(bookOid);
        expect(empty.filter).toBe('all');
        expect(empty.autoApprove).toBe(false);
        expect(empty.nodes || [], 'no render, no queue entries').toEqual([]);
        expect(empty.artifactCount).toBe(0);
        expect(empty.pendingCount).toBe(0);
        const auto = await jsonOf(await request.get(PB_REST + '/' + bookOid + '/review-queue?filter=auto-approved'), 'GET review-queue auto-approved');
        expect(auto.filter).toBe('auto-approved');
        expect(auto.nodes || []).toEqual([]);
        const bogus = await request.get(PB_REST + '/' + bookOid + '/review-queue?filter=bogus');
        expect(bogus.status(), 'an unknown review filter is a 400').toBe(400);
        expect(await bogus.text()).not.toContain('bogus');
    });
});
