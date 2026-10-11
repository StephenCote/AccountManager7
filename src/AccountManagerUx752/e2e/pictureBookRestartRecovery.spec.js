/**
 * PictureBook V3 P1-7 — restart recovery + resume, end to end against the Docker stack.
 *
 * REST-level only (no Ux): a live PORTRAIT render is submitted through POST /scene/{id}/render,
 * the Service7 container is killed (docker restart -t 0) while the run and its node are RUNNING,
 * and after the stack is back the test proves that
 *   - the in-memory job is gone (GET /rest/job/{jobId} -> 404),
 *   - the boot-time sweep (PbRecoveryUtil.sweepInterrupted) closed the run INTERRUPTED with the
 *     "resume to continue" hint, marked it resumable and returned the node to READY,
 *   - POST /run/{id}/resume opens a NEW pb.resume run that renders only the unfinished node and
 *     leaves the interrupted run as the record of what happened,
 *   - the resumed render's artifact is a real PNG,
 *   - the container log carries the recovery line with non-zero counts.
 *
 * Gated behind PB_RESTART_TESTS=1 because it bounces the container (every other spec running
 * against the stack would fail mid-flight) and renders on the SD backend. Run serially:
 *
 *   cd src/AccountManagerUx752
 *   PB_RESTART_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookRestartRecovery.spec.js --workers=1 --project=chromium
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) — never admin. All request contexts derive
 * from PLAYWRIGHT_BASE_URL; the Docker container name defaults to am7test-am7-1
 * (PB_RESTART_CONTAINER overrides). Timings are logged, never asserted.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { execSync } from 'child_process';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const ENABLED = process.env.PB_RESTART_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const CB_REST = REST + '/olio/chap-book';
const CONTAINER = process.env.PB_RESTART_CONTAINER || 'am7test-am7-1';

// The exact prefix PbRecoveryUtil logs at boot. The 05:25 build appends ", N write(s) threw" after
// "write(s) refused"; matching the common prefix keeps the assertion valid for both.
const RECOVERY_LINE = /PB restart recovery \(one Service7 JVM per database assumed\): (\d+) RUNNING and (\d+) PENDING run\(s\) closed INTERRUPTED, (\d+) RUNNING node\(s\) returned to READY/g;
const RENDER_PATH_REFUSAL = 'render path is v1';

function b64(str) { return Buffer.from(str).toString('base64'); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }

async function login(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: b64('password'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'login as e2etest_shared: HTTP ' + resp.status()).toBe(true);
}

async function search(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false }
    });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}

/**
 * The P1-8 switch (PbRenderPath) refuses every Phase 1 route with a 409 whose message starts with
 * "render path is v1" when the deployed Service7 pins the product to v1. That is an environment
 * problem, not a product defect — fail loudly and name the fix.
 */
function failIfRenderPathRefusal(status, text, what) {
    if (status !== 409) return;
    let err = null;
    try { err = JSON.parse(text).error; } catch (e) { err = text; }
    if (err && String(err).startsWith(RENDER_PATH_REFUSAL)) {
        throw new Error(what + ' was refused because the deployed Service7 pins the ChapBook render path to v1. '
            + 'Set CHAPBOOK_RENDER_PATH=v3 on the Docker stack (docker-compose / .env), restart the container '
            + 'and re-run this spec. Server said: ' + err);
    }
}

async function getRun(request, PB, runOid) {
    const resp = await request.get(PB + '/run/' + runOid);
    const text = await resp.text();
    failIfRenderPathRefusal(resp.status(), text, 'GET /run/{id}');
    expect(resp.status(), 'GET /run/' + runOid + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(200);
    return JSON.parse(text);
}

const POEM_A = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.
A precarious branch
Mourning its creased skein's blanch.`;

const POEM_B = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green
To silver veins of icy sheens.`;

async function findOrCreatePoem(request, orgId, groupId, name, title, text) {
    const existing = await search(request, 'olio.cb.poem', [
        { name: 'name', comparator: 'equals', value: name },
        { name: 'organizationId', comparator: 'equals', value: orgId }
    ], ['id', 'objectId']);
    if (existing && existing.objectId) return existing.objectId;
    const resp = await request.post(REST + '/model', {
        data: { schema: 'olio.cb.poem', name, title, author: 'P17 Test', groupId, text }
    });
    expect(resp.ok(), 'create poem ' + name + ': HTTP ' + resp.status()).toBe(true);
    const created = await resp.json();
    expect(created && created.objectId, 'poem ' + name + ' objectId').toBeTruthy();
    return created.objectId;
}

// Seeded in beforeAll; the fixture rows the test binds are restored / recorded in afterAll.
let bookObjectId = null;
let orgId = null;
let sceneRecord = null;          // { id, objectId, sceneNodeBefore: {id, objectId} | null }
let fixture = { narrative: null, charPerson: null, node: null };

test.describe('PictureBook V3 P1-7 restart recovery + resume (Docker stack)', () => {
    // 15 minutes: a .39 portrait (20-60 s) twice, plus a container restart (30-180 s). Never a threshold.
    test.describe.configure({ timeout: 900000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!ENABLED) return;
        if (/:8899(\/|$)/.test(BASE_URL)) {
            throw new Error('PB_RESTART_TESTS=1 needs PLAYWRIGHT_BASE_URL pointing at the Docker stack (https://127.0.0.1:9443), not the Vite dev server on :8899 — the spec restarts ' + CONTAINER);
        }
        await ensureSharedTestUser(request);
        await login(request);

        // There is no /login/principal route; the path/make response carries organizationId.
        const poemsDir = await request.get(REST + '/path/make/auth.group/data/B64-' + b64('~/Poems').replace(/=/g, '%3D'));
        expect(poemsDir.ok(), 'ensure ~/Poems: HTTP ' + poemsDir.status()).toBe(true);
        const poemsGroup = await poemsDir.json();
        expect(poemsGroup && poemsGroup.id, '~/Poems group id').toBeTruthy();
        orgId = poemsGroup.organizationId;
        expect(orgId, '~/Poems organizationId').toBeTruthy();

        const paObjectId = await findOrCreatePoem(request, orgId, poemsGroup.id, 'p17-restart-poem-alpha', 'Falling Leaves (P17)', POEM_A);
        const pbObjectId = await findOrCreatePoem(request, orgId, poemsGroup.id, 'p17-restart-poem-beta', 'Winter (P17)', POEM_B);

        const slug = 'p17-restart-' + Date.now().toString(36);
        const createResp = await request.post(CB_REST + '/create', {
            data: { slug, title: 'P1-7 Restart Recovery ChapBook', poemObjectIds: [paObjectId, pbObjectId], maxLinesPerPage: 20 }
        });
        const createText = await createResp.text();
        expect(createResp.ok(), 'ChapBook create: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const created = JSON.parse(createText);
        bookObjectId = created && created.objectId;
        expect(bookObjectId, 'seeded ChapBook objectId').toBeTruthy();
        console.log('[p17-seed] book ' + bookObjectId + ' slug ' + slug);
        await request.get(REST + '/logout');
    });

    test.afterAll(async ({ request }) => {
        if (!ENABLED || !bookObjectId) return;
        // Best-effort: put the scene back the way the ChapBook created it. The node, charPerson,
        // narrative, run rows and artifact are left as the record of the run (the run rows reference
        // the node by id and the artifact references it by FK; deleting them would orphan both).
        try {
            await login(request);
            if (sceneRecord && sceneRecord.id) {
                const before = sceneRecord.sceneNodeBefore;
                const restoreResp = await request.patch(REST + '/model', {
                    data: {
                        schema: 'olio.pb.scene', id: sceneRecord.id, objectId: sceneRecord.objectId,
                        sceneNode: (before && before.id ? { schema: 'olio.pb.node', id: before.id, objectId: before.objectId } : null)
                    }
                });
                const after = await search(request, 'olio.pb.scene',
                    [{ name: 'objectId', comparator: 'equals', value: sceneRecord.objectId }], ['id', 'objectId', 'sceneNode']);
                console.log('[p17-cleanup] restore scene.sceneNode -> ' + (before && before.id ? before.id : 'null')
                    + ': PATCH HTTP ' + restoreResp.status() + '; read back sceneNode=' + (after && after.sceneNode ? after.sceneNode.id : null));
            }
            console.log('[p17-cleanup] left in place: ' + JSON.stringify({
                book: bookObjectId, narrative: fixture.narrative, charPerson: fixture.charPerson, node: fixture.node
            }));
            await request.get(REST + '/logout');
        } catch (e) {
            console.warn('[p17-cleanup] ' + (e && e.message ? e.message : e));
        }
    });

    test('[SD] P1-7 a RUNNING render survives a hard container restart as INTERRUPTED/READY and resumes to a real PNG', async ({ request }) => {
        test.skip(!ENABLED, 'PB_RESTART_TESTS!=1 — skips the container restart + live SD render');
        expect(bookObjectId, 'ChapBook seeded in beforeAll').toBeTruthy();

        await login(request);
        const ts = Date.now().toString(36);
        const PB = REST + '/olio/picture-book/' + bookObjectId;

        // 1. Character fixture with a hand-written sdPrompt: no LLM stage, clothed subject.
        const charDirResp = await request.get(REST + '/path/make/auth.group/data/B64-' + b64('~/P16Chars').replace(/=/g, '%3D'));
        expect(charDirResp.ok(), 'ensure ~/P16Chars: HTTP ' + charDirResp.status()).toBe(true);
        const charDir = await charDirResp.json();
        expect(charDir && charDir.id, 'character group id').toBeTruthy();

        const narrativeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.narrative',
                name: 'p17-restart-narrative-' + ts,
                groupId: charDir.id,
                groupPath: charDir.path,
                sdPrompt: 'a tall woman in her forties with close-cropped grey hair and a calm, watchful face, wearing a charcoal wool coat buttoned to the collar over a cream turtleneck, head and shoulders portrait, overcast daylight',
                physicalDescription: 'tall, close-cropped grey hair, calm watchful face',
                outfitDescription: 'charcoal wool coat buttoned to the collar over a cream turtleneck'
            }
        });
        expect(narrativeResp.ok(), 'create olio.narrative: HTTP ' + narrativeResp.status()).toBe(true);
        const narrative = await narrativeResp.json();
        expect(narrative && narrative.objectId && narrative.id, 'narrative identity').toBeTruthy();
        fixture.narrative = narrative.objectId;

        const cpResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.charPerson',
                name: 'p17-restart-char-' + ts,
                firstName: 'Restart', middleName: 'Recovery', lastName: 'Rest',
                gender: 'female', alignment: 'neutralgood',
                groupId: charDir.id, groupPath: charDir.path
            }
        });
        expect(cpResp.ok(), 'create olio.charPerson: HTTP ' + cpResp.status()).toBe(true);
        const cp = await cpResp.json();
        expect(cp && cp.objectId && cp.id, 'charPerson identity').toBeTruthy();
        fixture.charPerson = cp.objectId;

        const linkResp = await request.patch(REST + '/model', {
            data: {
                schema: 'olio.charPerson', id: cp.id, objectId: cp.objectId,
                narrative: { schema: 'olio.narrative', id: narrative.id, objectId: narrative.objectId }
            }
        });
        expect(linkResp.ok(), 'PATCH charPerson.narrative: HTTP ' + linkResp.status()).toBe(true);

        // 2. A PORTRAIT node in the book's workflow, bound to the character.
        const wfResp = await request.get(PB + '/workflow');
        expect(wfResp.ok(), 'GET /workflow: HTTP ' + wfResp.status()).toBe(true);
        const wf = await wfResp.json();
        expect(wf && wf.workflowObjectId, 'workflowObjectId on the workflow view').toBeTruthy();
        const wfRec = await search(request, 'olio.pb.workflow',
            [{ name: 'objectId', comparator: 'equals', value: wf.workflowObjectId }], ['id', 'objectId', 'groupId', 'groupPath']);
        expect(wfRec && wfRec.id && wfRec.groupId, 'workflow record: ' + JSON.stringify(wfRec)).toBeTruthy();

        const handle = 'p17-restart-' + ts;
        const nodeResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.pb.node',
                name: 'Node ' + handle,
                groupId: wfRec.groupId,
                groupPath: wfRec.groupPath,
                workflow: { schema: 'olio.pb.workflow', id: wfRec.id, objectId: wfRec.objectId },
                handle,
                nodeType: 'portrait',
                nodeStatus: 'pending',
                ordinal: 917,
                scope: 'character',
                scopeRef: cp.objectId
            }
        });
        expect(nodeResp.ok(), 'create olio.pb.node: HTTP ' + nodeResp.status()).toBe(true);
        const node = await nodeResp.json();
        expect(node && node.objectId && node.id, 'node identity').toBeTruthy();
        fixture.node = node.objectId;

        // 3. Point the seeded book's first page at the node (the scene route renders scene.sceneNode; a
        //    ChapBook-created scene carries none). Remember what was there for afterAll.
        const pagesResp = await request.get(PB + '/pages');
        expect(pagesResp.ok(), 'GET /pages: HTTP ' + pagesResp.status()).toBe(true);
        const pages = await pagesResp.json();
        expect(Array.isArray(pages) && pages.length > 0, 'seeded ChapBook has at least one page').toBe(true);
        const sceneOid = pages[0].objectId;
        const scene = await search(request, 'olio.pb.scene',
            [{ name: 'objectId', comparator: 'equals', value: sceneOid }], ['id', 'objectId', 'name', 'sceneIndex', 'sceneNode']);
        expect(scene && scene.id, 'scene record: ' + JSON.stringify(scene)).toBeTruthy();
        sceneRecord = {
            id: scene.id, objectId: scene.objectId,
            sceneNodeBefore: (scene.sceneNode && scene.sceneNode.id ? { id: scene.sceneNode.id, objectId: scene.sceneNode.objectId } : null)
        };
        console.log('[p17-scene] ' + JSON.stringify(sceneRecord));

        const bindResp = await request.patch(REST + '/model', {
            data: {
                schema: 'olio.pb.scene', id: scene.id, objectId: scene.objectId,
                sceneNode: { schema: 'olio.pb.node', id: node.id, objectId: node.objectId }
            }
        });
        expect(bindResp.ok(), 'PATCH scene.sceneNode: HTTP ' + bindResp.status()).toBe(true);
        const sceneRead = await search(request, 'olio.pb.scene',
            [{ name: 'objectId', comparator: 'equals', value: sceneOid }], ['id', 'objectId', 'sceneNode']);
        expect(sceneRead && sceneRead.sceneNode && sceneRead.sceneNode.id, 'scene.sceneNode linked: ' + JSON.stringify(sceneRead)).toBe(node.id);

        // 4. Submit the render (202, pb.render) and wait until BOTH the run and the node are RUNNING,
        //    i.e. the worker is inside the SD stage. That is the state the restart must catch.
        const submittedAt = Date.now();
        const submitResp = await request.post(PB + '/scene/' + sceneOid + '/render');
        const submitText = await submitResp.text();
        failIfRenderPathRefusal(submitResp.status(), submitText, 'POST /scene/{id}/render');
        console.log('[p17-submit] HTTP ' + submitResp.status() + ' ' + submitText.slice(0, 600));
        expect(submitResp.status(), '/scene/{id}/render HTTP ' + submitResp.status() + ' ' + submitText.slice(0, 400)).toBe(202);
        const dto = JSON.parse(submitText);
        expect(dto.jobId, 'jobId').toBeTruthy();
        expect(dto.runObjectId, 'runObjectId').toBeTruthy();
        expect(dto.kind).toBe('pb.render');
        expect(dto.plannedNodeCount).toBe(1);
        expect(dto.planned).toContain(handle);
        const runOid = dto.runObjectId;

        let runBefore = null;
        let inStage = false;
        const stageDeadline = Date.now() + 180000;
        while (Date.now() < stageDeadline) {
            runBefore = await getRun(request, PB, runOid);
            const n0 = (runBefore.nodes || [])[0];
            if (upper(runBefore.runStatus) === 'RUNNING' && n0 && upper(n0.nodeStatus) === 'RUNNING') { inStage = true; break; }
            if (['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(upper(runBefore.runStatus))) break;
            await sleep(250);
        }
        console.log('[p17-stage] +' + (Date.now() - submittedAt) + 'ms before restart: ' + JSON.stringify({
            runStatus: runBefore && runBefore.runStatus, nodes: runBefore && runBefore.nodes,
            inFlightInThisProcess: runBefore && runBefore.inFlightInThisProcess, resumable: runBefore && runBefore.resumable
        }));
        expect(inStage, 'run and node both RUNNING before the restart (the render finished or failed first?): ' + JSON.stringify(runBefore)).toBe(true);
        expect(runBefore.inFlightInThisProcess, 'inFlightInThisProcess while the job holds the run').toBe(true);
        expect(runBefore.resumable, 'a RUNNING run is not resumable').toBe(false);
        expect(runBefore.requestedNodeIds, 'requestedNodeIds recorded at open').toEqual([node.id]);

        // 5. Kill the container mid-stage. -t 0 means SIGKILL: no graceful shutdown, no beginShutdown(),
        //    the run and node rows stay RUNNING in the database until the boot sweep finds them.
        const restartAt = Date.now();
        const sinceIso = new Date(restartAt - 5000).toISOString();
        const restartOut = execSync('docker restart -t 0 ' + CONTAINER, { encoding: 'utf8', timeout: 180000, stdio: ['ignore', 'pipe', 'pipe'] });
        console.log('[p17-restart] docker restart -t 0 ' + CONTAINER + ' -> ' + restartOut.trim() + ' (' + (Date.now() - restartAt) + 'ms)');

        let up = false;
        let lastProbe = null;
        const upDeadline = Date.now() + 360000;
        while (Date.now() < upDeadline) {
            try {
                const r = await request.get(REST + '/setup/state', { timeout: 10000 });
                const body = r.status() === 200 ? await r.json().catch(() => null) : null;
                lastProbe = 'HTTP ' + r.status() + (body ? ' ' + JSON.stringify(body) : '');
                if (r.status() === 200 && body && body.initialized === true) { up = true; break; }
            } catch (e) {
                lastProbe = String(e && e.message ? e.message : e).split('\n')[0].slice(0, 160);
            }
            await sleep(3000);
        }
        const upAt = Date.now();
        console.log('[p17-restart] stack ' + (up ? 'back' : 'NOT back') + ' after ' + (upAt - restartAt) + 'ms; last probe: ' + lastProbe);
        expect(up, 'Service7 answered /rest/setup/state initialized:true after the restart; last probe: ' + lastProbe).toBe(true);

        // 6. The in-memory job did not survive; the sweep reconciled the durable rows.
        await login(request);
        const jobGone = await request.get(REST + '/job/' + dto.jobId);
        expect(jobGone.status(), 'GET /rest/job/' + dto.jobId + ' after restart (registry is in-memory): ' + (await jobGone.text()).slice(0, 200)).toBe(404);

        const runAfter = await getRun(request, PB, runOid);
        console.log('[p17-swept] ' + JSON.stringify(runAfter));
        expect(upper(runAfter.runStatus), 'run after restart').toBe('INTERRUPTED');
        expect(runAfter.resumable, 'resumable').toBe(true);
        expect(runAfter.inFlightInThisProcess, 'inFlightInThisProcess after restart').toBe(false);
        expect(String(runAfter.error || ''), 'run.error').toContain('Interrupted: the server stopped while this run was RUNNING');
        expect(String(runAfter.error || ''), 'run.error carries the resume hint').toContain('resume to continue');
        expect(runAfter.requestedNodeIds, 'requestedNodeIds preserved').toEqual([node.id]);
        expect(runAfter.executedNodeCount).toBe(0);
        expect(runAfter.failedNodeCount).toBe(0);
        expect((runAfter.nodes || []).length).toBe(1);
        expect(runAfter.nodes[0].nodeObjectId).toBe(node.objectId);
        expect(upper(runAfter.nodes[0].nodeStatus), 'node status in the run view').toBe('READY');

        const nodeRow = await search(request, 'olio.pb.node',
            [{ name: 'objectId', comparator: 'equals', value: node.objectId }], ['id', 'objectId', 'handle', 'nodeStatus', 'lastError']);
        console.log('[p17-node] ' + JSON.stringify(nodeRow));
        expect(nodeRow && nodeRow.id, 'node row').toBe(node.id);
        expect(upper(nodeRow.nodeStatus), 'node returned to READY by the sweep').toBe('READY');

        // 7. Resume: a NEW pb.resume run for the recorded execution set.
        const resumeAt = Date.now();
        const resumeResp = await request.post(PB + '/run/' + runOid + '/resume');
        const resumeText = await resumeResp.text();
        failIfRenderPathRefusal(resumeResp.status(), resumeText, 'POST /run/{id}/resume');
        console.log('[p17-resume] HTTP ' + resumeResp.status() + ' ' + resumeText.slice(0, 600));
        expect(resumeResp.status(), '/run/{id}/resume HTTP ' + resumeResp.status() + ' ' + resumeText.slice(0, 400)).toBe(202);
        const resumed = JSON.parse(resumeText);
        expect(resumed.jobId, 'resume jobId').toBeTruthy();
        expect(resumed.jobId).not.toBe(dto.jobId);
        expect(resumed.kind).toBe('pb.resume');
        expect(resumed.resumedFromRunObjectId).toBe(runOid);
        expect(resumed.runObjectId, 'resume runObjectId').toBeTruthy();
        expect(resumed.runObjectId).not.toBe(runOid);
        expect(resumed.plannedNodeCount).toBe(1);
        expect(resumed.planned).toContain(handle);
        expect(['queued', 'running']).toContain(String(resumed.jobStatus).toLowerCase());

        let job = null;
        let lastPhase = null;
        const jobDeadline = Date.now() + 480000;
        while (Date.now() < jobDeadline) {
            const jobResp = await request.get(REST + '/job/' + resumed.jobId);
            expect(jobResp.ok(), 'GET /job/{id}: HTTP ' + jobResp.status()).toBe(true);
            job = await jobResp.json();
            const phase = job.status + '/' + job.phase + '/' + job.current + '/' + job.total;
            if (phase !== lastPhase) {
                console.log('[p17-job] +' + (Date.now() - resumeAt) + 'ms ' + phase);
                lastPhase = phase;
            }
            if (job.terminal) break;
            await sleep(3000);
        }
        console.log('[p17-job] terminal after ' + (Date.now() - resumeAt) + 'ms: ' + JSON.stringify({
            status: job && job.status, cancelled: job && job.cancelled, error: job && job.error,
            result: job && job.result
        }));
        expect(job && job.terminal, 'resume job reached a terminal state: ' + JSON.stringify(job)).toBe(true);
        expect(job.status, 'resume job status: ' + JSON.stringify(job)).toBe('completed');
        expect(job.cancelled).toBe(false);
        expect(job.result && job.result.runObjectId).toBe(resumed.runObjectId);
        expect(upper(job.result.runStatus), 'result.runStatus').toBe('COMPLETED');
        expect(job.result.executedNodeCount).toBe(1);
        expect(job.result.failedNodeCount).toBe(0);
        expect(typeof job.result.durationMs).toBe('number');
        const outcome = (job.result.nodes || []).find(n => n.nodeObjectId === node.objectId);
        expect(outcome, 'outcome for the node: ' + JSON.stringify(job.result.nodes)).toBeTruthy();
        expect(upper(outcome.status)).toBe('DONE');
        expect(outcome.artifactObjectId, 'outcome.artifactObjectId').toBeTruthy();

        // 8. Durable state: the new run COMPLETED, the interrupted run untouched, the node DONE.
        const newRun = await getRun(request, PB, resumed.runObjectId);
        console.log('[p17-newrun] ' + JSON.stringify(newRun));
        expect(upper(newRun.runStatus)).toBe('COMPLETED');
        expect(newRun.executedNodeCount).toBe(1);
        expect(newRun.failedNodeCount).toBe(0);
        expect(newRun.requestedNodeIds).toEqual([node.id]);
        expect(newRun.resumable).toBe(false);
        expect(newRun.error == null, 'new run has no error: ' + newRun.error).toBe(true);

        const oldRun = await getRun(request, PB, runOid);
        expect(upper(oldRun.runStatus), 'interrupted run is left as the record').toBe('INTERRUPTED');
        expect(oldRun.error).toBe(runAfter.error);
        expect(oldRun.executedNodeCount).toBe(0);
        expect(oldRun.resumable, 'the interrupted run still reports resumable (its set is unchanged)').toBe(true);
        expect(upper(oldRun.nodes[0].nodeStatus), 'node is DONE through the old run view too').toBe('DONE');

        const nodeDone = await search(request, 'olio.pb.node',
            [{ name: 'objectId', comparator: 'equals', value: node.objectId }], ['id', 'objectId', 'nodeStatus', 'inputHash', 'lastError']);
        expect(upper(nodeDone.nodeStatus), 'node DONE: ' + JSON.stringify(nodeDone)).toBe('DONE');
        expect(nodeDone.inputHash, 'inputHash stamped on DONE').toBeTruthy();

        // 9. The artifact exists, is selected, and its bytes are a PNG.
        const artRow = await search(request, 'olio.pb.artifact',
            [{ name: 'objectId', comparator: 'equals', value: outcome.artifactObjectId }],
            ['id', 'objectId', 'role', 'revision', 'selected', 'reviewStatus', 'producedByNode']);
        console.log('[p17-artifact-row] ' + JSON.stringify(artRow));
        expect(artRow && artRow.objectId, 'artifact row').toBe(outcome.artifactObjectId);
        expect(upper(artRow.role)).toBe('PORTRAIT');
        expect(artRow.selected).toBe(true);
        expect(artRow.producedByNode && artRow.producedByNode.id, 'artifact.producedByNode').toBe(node.id);

        const artResp = await request.get(PB + '/artifact/' + outcome.artifactObjectId);
        expect(artResp.ok(), 'GET artifact: HTTP ' + artResp.status()).toBe(true);
        const art = await artResp.json();
        console.log('[p17-artifact] ' + JSON.stringify({
            objectId: art.objectId, role: art.role, revision: art.revision, seed: art.seed, byteLength: art.byteLength,
            imageWidth: art.imageWidth, imageHeight: art.imageHeight, backend: art.backend, durationMs: art.durationMs,
            reviewStatus: art.reviewStatus, selected: art.selected, dataObjectId: art.dataObjectId
        }));
        expect(art.dataObjectId, 'artifact.dataObjectId').toBeTruthy();
        const dataResp = await request.get(REST + '/model/data.data/' + art.dataObjectId);
        expect(dataResp.ok(), 'GET data.data: HTTP ' + dataResp.status()).toBe(true);
        const dataRec = await dataResp.json();
        expect(dataRec && dataRec.groupPath && dataRec.name, 'data.data carries groupPath + name').toBeTruthy();
        const mediaUrl = BASE_URL + '/AccountManagerService7/media/Development/data.data' + dataRec.groupPath + '/' + encodeURIComponent(dataRec.name);
        const media = await request.get(mediaUrl);
        expect(media.ok(), 'GET media ' + mediaUrl + ' HTTP ' + media.status()).toBe(true);
        let raw = await media.body();
        if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
        expect(raw.subarray(0, 8).toString('hex'), 'artifact bytes are a PNG').toBe('89504e470d0a1a0a');
        const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
        expect(dims[0] > 0 && dims[1] > 0, 'PNG has dimensions').toBe(true);
        mkdirSync('e2e/screenshots', { recursive: true });
        const pngPath = 'e2e/screenshots/p17-resumed-portrait.png';
        writeFileSync(pngPath, raw);
        await test.info().attach('p17-resumed-portrait.png', { path: pngPath, contentType: 'image/png' });
        console.log('[p17-portrait] ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) -> ' + pngPath);

        // 10. The boot log of the restarted container carries the recovery line with our run/node in it.
        const logs = execSync('docker logs --since ' + sinceIso + ' ' + CONTAINER + ' 2>&1',
            { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, timeout: 120000, stdio: ['ignore', 'pipe', 'pipe'] });
        let match = null;
        let m;
        RECOVERY_LINE.lastIndex = 0;
        while ((m = RECOVERY_LINE.exec(logs)) !== null) match = m;
        expect(match, 'recovery line in docker logs since ' + sinceIso + ' (' + logs.length + ' chars)').toBeTruthy();
        const [running, pending, nodesReset] = [Number(match[1]), Number(match[2]), Number(match[3])];
        console.log('[p17-log] ' + match[0] + ' (pending=' + pending + ')');
        expect(running, 'at least our RUNNING run was closed INTERRUPTED').toBeGreaterThanOrEqual(1);
        expect(nodesReset, 'at least our RUNNING node was returned to READY').toBeGreaterThanOrEqual(1);

        console.log('[p17-timing] submit->inStage ' + (restartAt - submittedAt) + 'ms; restart->up ' + (upAt - restartAt)
            + 'ms; resume->terminal ' + (Date.now() - resumeAt) + 'ms (logged, never asserted)');
        await request.get(REST + '/logout');
    });
});
