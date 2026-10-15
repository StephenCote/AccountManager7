/**
 * PictureBook V3 P2-5 — POST /scene/{id}/render-plan on the Docker stack (design §12, §16, §20).
 *
 * P2-5: a scene renders FROM ITS PLAN. The route builds the scene's RenderPlan (TEXT_ONLY, the only
 * Phase 2 strategy), persists it as the node's `renderPlan` artifact BEFORE anything is sent, resolves the
 * BASE-stage checkpoint through ModelResolver, sends exactly the BASE stage to Swarm through the renderer
 * adapter and records the image as the node's `base` artifact. The RENDER_PLAN node (handle
 * render_plan@<shortRef(scene)>) and the book's workflow are created on first use; scene.sceneNode is
 * not touched. "A missing model is reported before rendering" is a 4xx carrying the resolver's reason.
 *
 * What this spec proves on the LIVE stack (REST as e2etest_shared; no LLM; two Swarm renders on .39):
 *   1. fresh STORY book + one scene with setting/action/mood text, no workflow → render-plan → 200,
 *      executed:true, renderPath:v3, strategy:TEXT_ONLY, stagesRendered:[BASE], nodeStatus
 *      DONE_UNVERIFIED, a `base` artifact revision 1 whose PNG is fetched and byte-checked, a `renderPlan`
 *      artifact whose JSON names the resolved checkpoint and the compiled prompt built from the scene text;
 *      the workflow now exists (GET /workflow 404 → 200) with exactly one RENDER_PLAN node for the scene;
 *      the page's sceneNodeObjectId stays null.
 *   2. unknown scene → 404 "Scene … not found in this book"; unknown book → 404 "Book not found"; a
 *      scene with NO text → 409 "has no text to render" (the plan is still persisted: the 409 names the plan
 *      artifact) and the node is left FAILED. All refusals happen before any render.
 *   3. a second render-plan on the same scene re-executes the SAME node (still one RENDER_PLAN node) and
 *      records `base` revision 2 superseding revision 1.
 *   4. the durable record of both renders (no SD): GET /run/{runObjectId} for each run is COMPLETED with
 *      executedNodeCount 1 / failedNodeCount 0 and names exactly the RENDER_PLAN node; the node view's
 *      `base` and `renderPlan` chains are the two ascending revisions; the base artifact's bytes
 *      (data.data) live in the book's own Artifacts group, owned by the rendering user - not the
 *      caller's home or another book (PbPipelineUtil.nodeGraph: node executions write bytes there).
 *   5. a stranger (ensureIso42001TestUser, a real second non-admin principal with no grant on the book)
 *      gets 404 "Book not found" from render-plan and from GET /workflow — not 403 (which would confirm
 *      the book exists) and not 200 — and, checked as the owner, no node/artifact/revision was created.
 *   6. approving `base` revision 2 (PUT /artifact/{id}/approve) pins the node; render-plan on a pinned
 *      node is refused 409 "Node render_plan@… is pinned. Unpin it first - …" BEFORE any render: the
 *      chains, the approved selection and the node's status are unchanged; unpin restores pinned:false.
 *
 * NOT reachable on the live stack, and therefore not asserted here: the 409 NO_CANDIDATE refusal (the
 * Docker deployment default is `sd.model` = juggernautXL_ragnarokBy.safetensors — `sd.default.model` is
 * empty in the container — which is installed on .39, so a fresh book always has a candidate; the
 * sd_xl_base checkpoint is only the Objects7 JUnit default, `test.swarm.model`) and the 503
 * INVENTORY_UNAVAILABLE refusal (Swarm on .39 is up). Both are proven by
 * TestPbRenderPlanExecutor (Objects7, am7db) with an unreachable SD server / a nonsense model override.
 * Likewise the 409 "render path is v1" refusal: PbRenderPath is a process-global, boot-pinned switch
 * (PICTUREBOOK_RENDER_PATH=v3 / CHAPBOOK_RENDER_PATH=v3 in the container), so no book on this stack can
 * be v1; TestPbRenderPlanExecutor#strangerAndV1AreRefusedBeforeAnyWork covers it. `lastError` is NOT
 * asserted cleared after a successful re-render: the product never clears it on success
 * (PbPipelineUtil.completeNode writes inputHash/status/lastRunAt only - PictureBookV3Implementation.md
 * items 50/57), so test 4 only logs it.
 *
 * Live SD on .39 — gated behind PB_SD_TESTS=1 and run serially:
 *
 *   cd src/AccountManagerUx752
 *   PB_SD_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/pictureBookV3RenderPlan.spec.js --workers=1 --project=chromium --retries=0
 *
 * Uses ensureSharedTestUser (e2etest_shared / password) as the owner and ensureIso42001TestUser
 * (e2etest_iso42001 / password) as the stranger — never admin. Timings are logged, never asserted.
 * Every generated PNG is fetched from the media servlet, byte-checked and written to
 * test-results/p25-render-plan/ so it can be opened and looked at.
 */
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, ensureIso42001TestUser } from './helpers/api.js';
import { gunzipSync } from 'zlib';
import { mkdirSync, writeFileSync } from 'fs';

const SD_ENABLED = process.env.PB_SD_TESTS === '1';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_ROOT = REST + '/olio/picture-book';
const OUT_DIR = 'test-results/p25-render-plan';
const NIL_UUID = '00000000-0000-0000-0000-000000000000';
const SETTING = 'a weathered stone lighthouse on a rocky headland at dusk, waves breaking below, gulls wheeling overhead';
const ACTION = 'the lamp has just been lit and its beam sweeps across the darkening water';
const MOOD = 'windswept, solitary';

function b64(str) { return Buffer.from(str).toString('base64'); }
function upper(v) { return (v == null ? null : String(v).toUpperCase()); }
function shortRef(oid) { return String(oid).replace(/-/g, '').slice(0, 8); }

// The only credential sources: what ensureSharedTestUser() / ensureIso42001TestUser() return (set in
// beforeAll), never a literal. `shared` owns the book; `stranger` has no grant on it.
let shared = null;
let stranger = null;

function credentialFor(who, label) {
    if (!who) throw new Error('login before ensure*TestUser() — the beforeAll must set the ' + label + ' user first');
    return {
        schema: 'auth.credential',
        organizationPath: '/Development',
        name: who.testUserName,
        credential: b64(who.testPassword),
        type: 'hashed_password'
    };
}

async function loginAs(ctx, who, label) {
    const resp = await ctx.post(REST + '/login', { data: credentialFor(who, label) });
    expect(resp.ok() || resp.status() === 204, 'login as ' + who.testUserName + ': HTTP ' + resp.status()).toBe(true);
}

async function login(request) {
    await loginAs(request, shared, 'shared');
}

async function search(request, type, fields, requestFields) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, fields, request: requestFields, recordCount: 1, cache: false }
    });
    expect(resp.ok(), 'search ' + type + ': HTTP ' + resp.status()).toBe(true);
    const body = await resp.json();
    return body && body.results && body.results[0];
}

/** Fetch an artifact's PNG bytes through data.data → the media servlet; byte-check; write to OUT_DIR. */
async function savePng(request, PB, artifactObjectId, fileName) {
    const artResp = await request.get(PB + '/artifact/' + artifactObjectId);
    expect(artResp.ok(), 'GET artifact ' + artifactObjectId + ': HTTP ' + artResp.status()).toBe(true);
    const art = await artResp.json();
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
    expect(raw.subarray(0, 8).toString('hex'), fileName + ' bytes are a PNG').toBe('89504e470d0a1a0a');
    const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
    expect(dims[0] > 0 && dims[1] > 0, 'PNG has dimensions').toBe(true);
    mkdirSync(OUT_DIR, { recursive: true });
    const pngPath = OUT_DIR + '/' + fileName;
    writeFileSync(pngPath, raw);
    await test.info().attach(fileName, { path: pngPath, contentType: 'image/png' });
    console.log('[p25-png] ' + fileName + ' ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) role=' + art.role
        + ' revision=' + art.revision + ' selected=' + art.selected + ' supersedes=' + art.supersedesObjectId
        + ' backend=' + art.backend + ' seed=' + art.seed + ' durationMs=' + art.durationMs + ' reviewStatus=' + art.reviewStatus
        + ' -> ' + pngPath);
    return { art, dims, pngPath, raw };
}

/** POST /scene/{id}/render-plan (synchronous); returns {status, text, ms}. */
async function postRenderPlan(request, bookOid, sceneOid) {
    const started = Date.now();
    const resp = await request.post(PB_ROOT + '/' + bookOid + '/scene/' + sceneOid + '/render-plan', { timeout: 900000 });
    const text = await resp.text();
    return { status: resp.status(), text, ms: Date.now() - started };
}

function errorText(text) {
    try { const j = JSON.parse(text); return j.message || j.error || text; } catch (e) { return text; }
}

/** The book's workflow DTO (GET /workflow) → {status, view}. */
async function workflow(request, PB) {
    const resp = await request.get(PB + '/workflow');
    const text = await resp.text();
    return { status: resp.status(), text, view: (resp.ok() ? JSON.parse(text) : null) };
}

function renderPlanNodesOf(view, sceneOid) {
    return (view.nodes || []).filter(n => upper(n.nodeType) === 'RENDER_PLAN' && n.scopeRef === sceneOid);
}

/** GET /workflow/node/{id}: nodeSummary + bindings + per-role ascending revision chains (`artifacts`). */
async function nodeView(request, PB, nodeOid) {
    const resp = await request.get(PB + '/workflow/node/' + nodeOid);
    const text = await resp.text();
    expect(resp.ok(), 'GET /workflow/node/' + nodeOid + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    return JSON.parse(text);
}

/** The two artifact chains a RENDER_PLAN node carries after N renders, as [objectId...] ascending by revision. */
function chainIds(view, role) {
    const chain = (view.artifacts && view.artifacts[role]) || [];
    return chain.map(a => a.objectId);
}

/** GET /run/{id} → {status, text, dto}. */
async function run(request, PB, runOid) {
    const resp = await request.get(PB + '/run/' + runOid);
    const text = await resp.text();
    return { status: resp.status(), text, dto: (resp.ok() ? JSON.parse(text) : null) };
}

async function seedScene(request, book, bookRef, slug, index, extra) {
    const resp = await request.post(REST + '/model', {
        data: Object.assign({ schema: 'olio.pb.scene', name: 'Scene ' + slug + ' ' + index, groupId: book.groupId, groupPath: book.groupPath,
            book: bookRef, sceneIndex: index }, extra)
    });
    const text = await resp.text();
    expect(resp.ok(), 'create olio.pb.scene ' + index + ': HTTP ' + resp.status() + ' ' + text.slice(0, 300)).toBe(true);
    const scene = JSON.parse(text);
    expect(scene && scene.id && scene.objectId, 'scene identity').toBeTruthy();
    return { id: scene.id, objectId: scene.objectId, index };
}

// Seeded in beforeAll. The fixture rows are left in place as the record of the run (nodes/artifacts
// reference them by FK); afterAll records what was created.
let bookObjectId = null;
let slug = null;
const fx = { book: null, scene: null, emptyScene: null, handle: null };
const r1 = { nodeObjectId: null, artifactObjectId: null, planArtifactObjectId: null, planContentHash: null, runObjectId: null };
const r2 = { artifactObjectId: null, planArtifactObjectId: null, runObjectId: null };

test.describe('PictureBook V3 P2-5 — render from the plan over REST', () => {
    // Serial: every test needs the state the previous one produced. 15 minutes per test: one .39 render
    // plus REST round trips. Never a threshold.
    test.describe.configure({ mode: 'serial', timeout: 900000, retries: 0 });

    test.beforeAll(async ({ request }) => {
        if (!SD_ENABLED) return;
        shared = await ensureSharedTestUser(request);
        // The stranger for test 5: provisioned (idempotently) by the shared helper; its ISO roles have no
        // bearing on olio.pb.* PBAC, and it is never granted anything on this book.
        stranger = await ensureIso42001TestUser(request);
        expect(stranger.testUserName !== shared.testUserName, 'the stranger is a different principal').toBe(true);
        await login(request);
        const ts = Date.now().toString(36);

        // A fresh STORY book: /chapter creates the book row, its own world and the Book/Workflow/Artifacts
        // groups under /Olio/Universes/Books/Worlds/{slug} - but NO workflow and NO scenes.
        slug = 'p25-plan-' + ts;
        const createResp = await request.post(PB_ROOT + '/chapter', { data: { slug, title: 'P2-5 Render Plan ' + ts }, timeout: 120000 });
        const createText = await createResp.text();
        expect(createResp.ok(), 'POST /chapter: HTTP ' + createResp.status() + ' ' + createText.slice(0, 300)).toBe(true);
        const chapter = JSON.parse(createText);
        bookObjectId = chapter.bookObjectId || chapter.objectId;
        expect(bookObjectId, 'seeded STORY book objectId: ' + createText.slice(0, 300)).toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;

        const book = await search(request, 'olio.pb.book', [{ name: 'objectId', comparator: 'equals', value: bookObjectId }],
            ['id', 'objectId', 'name', 'slug', 'groupId', 'groupPath', 'organizationId', 'bookType']);
        expect(book && book.id && book.groupId && book.groupPath, 'book row: ' + JSON.stringify(book)).toBeTruthy();
        expect(upper(book.bookType), 'seeded book is STORY').toBe('STORY');
        fx.book = { id: book.id, objectId: book.objectId, name: book.name, slug: book.slug, groupId: book.groupId, groupPath: book.groupPath };
        const bookRef = { schema: 'olio.pb.book', id: book.id, objectId: book.objectId };

        const wfBefore = await workflow(request, PB);
        console.log('[p25-seed] GET /workflow before the first render-plan: HTTP ' + wfBefore.status + ' ' + wfBefore.text.slice(0, 200));
        expect(wfBefore.status, 'a fresh STORY book has no workflow (the route creates it on first use)').toBe(404);

        // Scene 0: setting/action/mood prose, no sdPrompt - the TEXT_ONLY compile joins the three.
        fx.scene = await seedScene(request, fx.book, bookRef, slug, 0, { title: 'The lighthouse at dusk', setting: SETTING, action: ACTION, mood: MOOD });
        // Scene 1: no title/setting/action/mood/sdPrompt/summary at all (the record name is not scene text)
        // - the TEXT_ONLY compile has nothing to say, which is test 2's 409.
        fx.emptyScene = await seedScene(request, fx.book, bookRef, slug, 1, {});
        fx.handle = 'render_plan@' + shortRef(fx.scene.objectId);

        const pages = await (await request.get(PB + '/pages')).json();
        const pg0 = pages.find(p => p.objectId === fx.scene.objectId);
        expect(pg0, 'GET /pages lists the seeded scene: ' + JSON.stringify(pages).slice(0, 300)).toBeTruthy();
        expect(pg0.sceneNodeObjectId == null && pg0.artifactObjectId == null, 'no node, no image yet: ' + JSON.stringify(pg0)).toBe(true);
        console.log('[p25-seed] ' + JSON.stringify({ book: bookObjectId, slug, ...fx }));
        await request.get(REST + '/logout');
    });

    test.afterAll(async ({ request }) => {
        if (!SD_ENABLED || !bookObjectId) return;
        console.log('[p25-cleanup] left in place (nodes + artifacts reference them): ' + JSON.stringify({ book: bookObjectId, slug, ...fx, r1, r2 }));
    });

    test('[SD] render-plan 1: a fresh STORY scene renders BASE through TEXT_ONLY; the workflow and its RENDER_PLAN node are created on first use; base + renderPlan artifacts recorded', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — skips the live SD renders');
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const r = await postRenderPlan(request, bookObjectId, fx.scene.objectId);
        console.log('[p25-r1] HTTP ' + r.status + ' in ' + r.ms + 'ms (logged, never asserted): ' + r.text.slice(0, 1200));
        expect(r.status, 'POST /scene/{id}/render-plan: ' + r.text.slice(0, 600)).toBe(200);
        const dto = JSON.parse(r.text);
        expect(dto.executed, 'executed').toBe(true);
        expect(dto.renderPath).toBe('v3');
        expect(dto.sceneObjectId).toBe(fx.scene.objectId);
        expect(dto.sceneIndex).toBe(0);
        expect(dto.handle, 'the RENDER_PLAN node handle').toBe(fx.handle);
        expect(upper(dto.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(dto.strategy, 'Phase 2 strategy').toBe('TEXT_ONLY');
        expect(dto.stagesRendered, 'exactly the BASE stage is rendered').toEqual(['BASE']);
        expect(Array.isArray(dto.stagesPlanned) && dto.stagesPlanned.includes('BASE'), 'stagesPlanned: ' + JSON.stringify(dto.stagesPlanned)).toBe(true);
        expect(dto.nodeObjectId, 'nodeObjectId').toBeTruthy();
        expect(dto.artifactObjectId, 'artifactObjectId').toBeTruthy();
        expect(dto.artifactRevision, 'first revision').toBe(1);
        expect(dto.byteLength, 'byteLength').toBeGreaterThan(0);
        expect(dto.planArtifactObjectId, 'planArtifactObjectId').toBeTruthy();
        expect(dto.planContentHash, 'planContentHash').toBeTruthy();
        // JSONUtil.exportObject is Include.NON_EMPTY: an empty `degradations` / `downstreamHandles` list is
        // dropped from the wire DTO entirely (measured 2026-10-14), so "no degradations" is absent-or-empty.
        const degradations = dto.degradations || [];
        expect(Array.isArray(degradations), 'degradations, when present, is a list: ' + JSON.stringify(dto.degradations)).toBe(true);
        expect(degradations.some(d => String(d).startsWith('MODEL_SUBSTITUTED')), 'no substitution on the deployment default: ' + JSON.stringify(degradations)).toBe(false);
        expect(dto.downstreamMarked, 'a fresh RENDER_PLAN node has no downstream consumers').toBe(0);
        expect(upper(dto.runStatus)).toBe('COMPLETED');
        expect(dto.executedNodeCount).toBe(1);
        expect(dto.failedNodeCount).toBe(0);
        expect(dto.runObjectId, 'the sync route still records a run').toBeTruthy();
        r1.nodeObjectId = dto.nodeObjectId;
        r1.artifactObjectId = dto.artifactObjectId;
        r1.planArtifactObjectId = dto.planArtifactObjectId;
        r1.planContentHash = dto.planContentHash;
        r1.runObjectId = dto.runObjectId;

        // Durable state 1: the workflow now exists and carries exactly one RENDER_PLAN node for the scene.
        const wf = await workflow(request, PB);
        expect(wf.status, 'GET /workflow after the first render-plan: ' + wf.text.slice(0, 300)).toBe(200);
        const nodes = renderPlanNodesOf(wf.view, fx.scene.objectId);
        console.log('[p25-r1-node] ' + JSON.stringify(nodes));
        expect(nodes.length, 'one RENDER_PLAN node for the scene: ' + JSON.stringify(wf.view.nodes)).toBe(1);
        expect(nodes[0].objectId).toBe(dto.nodeObjectId);
        expect(nodes[0].handle).toBe(fx.handle);
        expect(upper(nodes[0].storedStatus)).toBe('DONE_UNVERIFIED');
        expect(upper(nodes[0].scope)).toBe('SCENE');
        expect(nodes[0].executable, 'RENDER_PLAN is an executable type').toBe(true);
        expect(!!nodes[0].pinned).toBe(false);

        // Durable state 2: scene.sceneNode is NOT touched by the render-plan route (that is the landscape /
        // composite path's pointer); the page therefore still carries no sceneNode.
        const pg = (await (await request.get(PB + '/pages')).json()).find(p => p.objectId === fx.scene.objectId);
        console.log('[p25-r1-page] ' + JSON.stringify(pg));
        expect(pg.sceneNodeObjectId == null, 'sceneNode untouched: ' + JSON.stringify(pg)).toBe(true);

        // Durable state 3: the renderPlan artifact carries the plan that was rendered from.
        const planResp = await request.get(PB + '/artifact/' + dto.planArtifactObjectId);
        expect(planResp.ok(), 'GET renderPlan artifact: HTTP ' + planResp.status()).toBe(true);
        const planArt = await planResp.json();
        expect(upper(planArt.role)).toBe('RENDERPLAN');
        expect(planArt.artifactText, 'renderPlan artifact carries the plan JSON').toBeTruthy();
        const plan = JSON.parse(planArt.artifactText);
        expect(plan.strategy && plan.strategy.name).toBe('TEXT_ONLY');
        expect(plan.models && plan.models.location, 'plan.models.location: ' + JSON.stringify(plan.models)).toBeTruthy();
        expect(plan.models.location.model, 'resolved BASE checkpoint').toBeTruthy();
        expect(plan.models.location.reason).toBe('OK');
        expect(plan.models.location.degraded).toBe(false);
        expect(plan.compiled && plan.compiled.BASE && plan.compiled.BASE.prompt, 'compiled BASE prompt: ' + JSON.stringify(plan.compiled)).toBeTruthy();
        expect(plan.compiled.BASE.prompt, 'the compiled prompt is built from the scene setting').toContain('stone lighthouse');
        expect(plan.compiled.BASE.prompt).toContain('lamp has just been lit');
        expect(plan.canvas && plan.canvas.width > 0 && plan.canvas.height > 0, 'canvas: ' + JSON.stringify(plan.canvas)).toBe(true);
        console.log('[p25-r1-plan] model=' + plan.models.location.model + ' step=' + plan.models.location.step + ' canvas=' + plan.canvas.width + 'x' + plan.canvas.height
            + ' preset=' + JSON.stringify(plan.preset) + ' prompt=' + plan.compiled.BASE.prompt.slice(0, 200));

        // Durable state 4: the base artifact is a real PNG at the plan's canvas size.
        const base = await savePng(request, PB, dto.artifactObjectId, 'r1-base.png');
        expect(upper(base.art.role)).toBe('BASE');
        expect(base.art.revision, 'first revision').toBe(1);
        expect(base.art.selected).toBe(true);
        expect(base.art.supersedesObjectId == null, 'revision 1 supersedes nothing: ' + base.art.supersedesObjectId).toBe(true);
        expect(base.art.byteLength, 'artifact.byteLength is what the route reported').toBe(dto.byteLength);
        expect(base.raw.length, 'byteLength reported by the route matches the stored PNG').toBe(dto.byteLength);
        expect([base.art.imageWidth, base.art.imageHeight], 'artifact image size is the plan canvas').toEqual([plan.canvas.width, plan.canvas.height]);
        expect(base.dims, 'PNG dimensions are the plan canvas').toEqual([plan.canvas.width, plan.canvas.height]);
        await request.get(REST + '/logout');
    });

    test('refusals before any render: unknown scene 404, unknown book 404, scene without text 409 naming the persisted plan artifact (node FAILED)', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the fixture from beforeAll');
        expect(bookObjectId, 'STORY book seeded in beforeAll').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const wfBefore = await workflow(request, PB);
        expect(wfBefore.status).toBe(200);
        const nodeCountBefore = (wfBefore.view.nodes || []).length;

        const noScene = await postRenderPlan(request, bookObjectId, NIL_UUID);
        console.log('[p25-404-scene] HTTP ' + noScene.status + ' ' + noScene.text.slice(0, 300));
        expect(noScene.status, 'unknown scene: ' + noScene.text).toBe(404);
        expect(errorText(noScene.text)).toBe('Scene ' + NIL_UUID + ' not found in this book');

        const noBook = await postRenderPlan(request, NIL_UUID, fx.scene.objectId);
        console.log('[p25-404-book] HTTP ' + noBook.status + ' ' + noBook.text.slice(0, 300));
        expect(noBook.status, 'unknown book: ' + noBook.text).toBe(404);
        expect(errorText(noBook.text)).toBe('Book not found');

        // Scene 1 carries no text at all; the plan is still built and persisted, then the empty compiled
        // prompt is refused 409 (the route names the plan artifact it persisted).
        const emptyRow = await search(request, 'olio.pb.scene', [{ name: 'objectId', comparator: 'equals', value: fx.emptyScene.objectId }],
            ['id', 'objectId', 'title', 'setting', 'action', 'mood', 'sdPrompt', 'summary']);
        console.log('[p25-409-row] ' + JSON.stringify(emptyRow));
        expect(!emptyRow.title && !emptyRow.setting && !emptyRow.action && !emptyRow.mood && !emptyRow.sdPrompt && !emptyRow.summary,
            'precondition: scene 1 has no text: ' + JSON.stringify(emptyRow)).toBe(true);
        const noText = await postRenderPlan(request, bookObjectId, fx.emptyScene.objectId);
        console.log('[p25-409-empty] HTTP ' + noText.status + ' ' + noText.text.slice(0, 400));
        expect(noText.status, 'scene without text: ' + noText.text).toBe(409);
        const msg = errorText(noText.text);
        expect(msg).toMatch(/^Scene 1 has no text to render: set its setting\/action or sdPrompt \(plan artifact [0-9a-f\-]{36}\)$/);
        const planOid = msg.match(/plan artifact ([0-9a-f\-]{36})/)[1];
        const planResp = await request.get(PB + '/artifact/' + planOid);
        expect(planResp.ok(), 'the plan named by the 409 was persisted: HTTP ' + planResp.status()).toBe(true);
        const planArt = await planResp.json();
        expect(upper(planArt.role)).toBe('RENDERPLAN');
        const plan = JSON.parse(planArt.artifactText);
        expect(plan.compiled && plan.compiled.BASE, 'BASE stage compiled: ' + JSON.stringify(plan.compiled)).toBeTruthy();
        expect((plan.compiled.BASE.prompt || '').trim(), 'the compiled prompt is empty').toBe('');

        // The refusal left scene 1's RENDER_PLAN node FAILED (created on first use, nothing rendered); scene 0's
        // node is untouched.
        const wfAfter = await workflow(request, PB);
        expect(wfAfter.status).toBe(200);
        const emptyNodes = renderPlanNodesOf(wfAfter.view, fx.emptyScene.objectId);
        console.log('[p25-409-node] ' + JSON.stringify(emptyNodes));
        expect(emptyNodes.length, 'one RENDER_PLAN node for scene 1').toBe(1);
        expect(upper(emptyNodes[0].storedStatus)).toBe('FAILED');
        expect(emptyNodes[0].handle).toBe('render_plan@' + shortRef(fx.emptyScene.objectId));
        expect((wfAfter.view.nodes || []).length, 'exactly one node was added (scene 1); the 404s added none').toBe(nodeCountBefore + 1);
        const s0 = renderPlanNodesOf(wfAfter.view, fx.scene.objectId);
        expect(s0.length).toBe(1);
        expect(upper(s0[0].storedStatus), 'scene 0 node untouched by the refusals').toBe('DONE_UNVERIFIED');
        await request.get(REST + '/logout');
    });

    test('[SD] render-plan 2: the same scene again re-executes the SAME node (still one RENDER_PLAN node) and records base revision 2 superseding revision 1', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the render from test 1');
        expect(r1.artifactObjectId, 'render-plan 1 produced an artifact').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        const r = await postRenderPlan(request, bookObjectId, fx.scene.objectId);
        console.log('[p25-r2] HTTP ' + r.status + ' in ' + r.ms + 'ms (logged, never asserted): ' + r.text.slice(0, 1200));
        expect(r.status, 'second POST /scene/{id}/render-plan: ' + r.text.slice(0, 600)).toBe(200);
        const dto = JSON.parse(r.text);
        expect(dto.executed).toBe(true);
        expect(dto.nodeObjectId, 'the same node, not a second one').toBe(r1.nodeObjectId);
        expect(dto.handle).toBe(fx.handle);
        expect(upper(dto.nodeStatus)).toBe('DONE_UNVERIFIED');
        expect(dto.strategy).toBe('TEXT_ONLY');
        expect(dto.stagesRendered).toEqual(['BASE']);
        expect(dto.artifactObjectId, 'a new base artifact').not.toBe(r1.artifactObjectId);
        expect(dto.artifactRevision, 'second revision').toBe(2);
        expect(dto.planArtifactObjectId, 'a new renderPlan artifact for this attempt').not.toBe(r1.planArtifactObjectId);
        expect(dto.planContentHash, 'same inputs, same plan hash').toBe(r1.planContentHash);
        expect(dto.runObjectId, 'the second execution records its own run').toBeTruthy();
        expect(dto.runObjectId, 'a new run, not the first one re-used').not.toBe(r1.runObjectId);
        r2.artifactObjectId = dto.artifactObjectId;
        r2.planArtifactObjectId = dto.planArtifactObjectId;
        r2.runObjectId = dto.runObjectId;

        const wf = await workflow(request, PB);
        expect(wf.status).toBe(200);
        const nodes = renderPlanNodesOf(wf.view, fx.scene.objectId);
        expect(nodes.length, 'still exactly one RENDER_PLAN node for the scene: ' + JSON.stringify(nodes)).toBe(1);
        expect(nodes[0].objectId).toBe(r1.nodeObjectId);

        const second = await savePng(request, PB, dto.artifactObjectId, 'r2-base.png');
        expect(upper(second.art.role)).toBe('BASE');
        expect(second.art.revision, 'revision 2').toBe(2);
        expect(second.art.selected).toBe(true);
        const prior = await (await request.get(PB + '/artifact/' + r1.artifactObjectId)).json();
        console.log('[p25-r2-revision] prior=' + prior.revision + ' selected=' + prior.selected + ' reviewStatus=' + prior.reviewStatus
            + ' -> second=' + second.art.revision + ' selected=' + second.art.selected + ' supersedes=' + second.art.supersedesObjectId);
        expect(prior.objectId, 'revision 1 is kept in the history').toBe(r1.artifactObjectId);
        expect(second.art.supersedesObjectId, 'revision 2 supersedes revision 1').toBe(r1.artifactObjectId);
        expect(prior.selected, 'revision 1 is kept but no longer selected').toBe(false);
        await request.get(REST + '/logout');
    });

    test('durable record of both renders: each run is COMPLETED naming exactly the RENDER_PLAN node; the node view carries the two ascending base + renderPlan revisions; the base bytes live in the book\'s Artifacts group', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the renders from tests 1 and 3');
        expect(r1.runObjectId && r2.runObjectId, 'both renders recorded a run').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        // The run rows: the sync route still opens and closes a run per execution (V3 §21). Each names
        // exactly the one node that was asked for and reports the executed/failed split the DTO echoed.
        for (const [label, oid] of [['r1', r1.runObjectId], ['r2', r2.runObjectId]]) {
            const rr = await run(request, PB, oid);
            console.log('[p25-run-' + label + '] HTTP ' + rr.status + ' ' + rr.text.slice(0, 600));
            expect(rr.status, 'GET /run/' + oid + ': ' + rr.text.slice(0, 300)).toBe(200);
            expect(rr.dto.runObjectId).toBe(oid);
            expect(upper(rr.dto.runStatus), label + ' runStatus').toBe('COMPLETED');
            expect(rr.dto.executedNodeCount, label + ' executedNodeCount').toBe(1);
            expect(rr.dto.failedNodeCount, label + ' failedNodeCount').toBe(0);
            expect(rr.dto.error == null || rr.dto.error === '', label + ' carries no error: ' + rr.dto.error).toBe(true);
            expect(rr.dto.resumable, 'a COMPLETED run is not resumable').toBe(false);
            expect(rr.dto.inFlightInThisProcess, 'nothing in flight after a sync return').toBe(false);
            expect(Array.isArray(rr.dto.requestedNodeIds) && rr.dto.requestedNodeIds.length === 1,
                label + ' requested exactly one node: ' + JSON.stringify(rr.dto.requestedNodeIds)).toBe(true);
            expect(Array.isArray(rr.dto.nodes) && rr.dto.nodes.length === 1, label + ' nodes: ' + JSON.stringify(rr.dto.nodes)).toBe(true);
            expect(rr.dto.nodes[0].nodeObjectId, label + ' names the RENDER_PLAN node').toBe(r1.nodeObjectId);
            expect(rr.dto.nodes[0].handle).toBe(fx.handle);
            expect(upper(rr.dto.nodes[0].nodeStatus), label + ' node status as of now').toBe('DONE_UNVERIFIED');
            expect(rr.dto.startedAt && rr.dto.completedAt, label + ' carries start/complete stamps').toBeTruthy();
        }
        // A run of another book is not addressable through this one.
        const notHere = await run(request, PB, NIL_UUID);
        expect(notHere.status, 'unknown run: ' + notHere.text).toBe(404);
        expect(errorText(notHere.text)).toBe('Run ' + NIL_UUID + ' not found in this book');

        // The node view: both chains, ascending, revision 2 selected, revision 1 kept.
        const nv = await nodeView(request, PB, r1.nodeObjectId);
        console.log('[p25-node-view] ' + JSON.stringify({ storedStatus: nv.storedStatus, status: nv.status, pinned: nv.pinned, lastError: nv.lastError,
            inputHash: nv.inputHash, roles: Object.keys(nv.artifacts || {}), base: nv.artifacts && nv.artifacts.base, renderPlan: nv.artifacts && nv.artifacts.renderPlan }));
        expect(nv.objectId).toBe(r1.nodeObjectId);
        expect(nv.handle).toBe(fx.handle);
        expect(upper(nv.storedStatus)).toBe('DONE_UNVERIFIED');
        expect(!!nv.pinned, 'not pinned yet (test 6 pins it)').toBe(false);
        expect(nv.inputHash, 'the executor stamped the node inputHash').toBeTruthy();
        expect(chainIds(nv, 'base'), 'base chain = [rev1, rev2]').toEqual([r1.artifactObjectId, r2.artifactObjectId]);
        expect(chainIds(nv, 'renderPlan'), 'renderPlan chain = [plan1, plan2]').toEqual([r1.planArtifactObjectId, r2.planArtifactObjectId]);
        expect(nv.artifacts.base.map(a => a.revision)).toEqual([1, 2]);
        expect(nv.artifacts.base.map(a => a.selected)).toEqual([false, true]);
        expect(nv.artifacts.base[1].supersedesObjectId).toBe(r1.artifactObjectId);
        // autoApprove is off on a fresh book (bookModel.json: boolean, no schema default), so nothing
        // approved or pinned itself - the state test 6 starts from.
        expect(upper(nv.artifacts.base[1].reviewStatus), 'a fresh render is GENERATED, not auto-approved').toBe('GENERATED');
        expect(upper(nv.artifacts.base[0].reviewStatus)).toBe('GENERATED');
        expect(nv.artifacts.base[1].approvedBy, 'approvedBy is only emitted once APPROVED').toBeUndefined();
        expect(nv.artifacts.renderPlan.map(a => a.revision)).toEqual([1, 2]);
        // lastError: the refusals test touched scene 1's node, never this one; logged only - the product
        // never clears it on success (implementation items 50/57), so it is not a pass/fail here.
        expect(nv.lastError == null || nv.lastError === '', 'this node never failed: ' + nv.lastError).toBe(true);

        // Where the bytes went: the artifact's data.data is in the book's own compartment, owned by the
        // rendering user - not the caller's home directory and not another book. For a NODE execution
        // (this route, /test, the async run) PbPipelineUtil.nodeGraph opens the graph with the Artifacts
        // group as the byte destination (it passes artifactPath for both artifactGroupPath and
        // galleryGroupPath); the world Gallery is the destination only for the openSceneGraph path.
        expect(fx.book.groupPath.endsWith('/Book'), 'the book row lives in {container}/Book: ' + fx.book.groupPath).toBe(true);
        const container = fx.book.groupPath.slice(0, -'/Book'.length);
        expect(shared.user && shared.user.id, 'ensureSharedTestUser returned the user row: ' + JSON.stringify(shared.user)).toBeTruthy();
        for (const [label, oid] of [['r1', r1.artifactObjectId], ['r2', r2.artifactObjectId]]) {
            const art = await (await request.get(PB + '/artifact/' + oid)).json();
            const dataResp = await request.get(REST + '/model/data.data/' + art.dataObjectId);
            expect(dataResp.ok(), label + ' GET data.data: HTTP ' + dataResp.status()).toBe(true);
            const dataRec = await dataResp.json();
            console.log('[p25-bytes-' + label + '] data.data ' + dataRec.objectId + ' groupPath=' + dataRec.groupPath + ' name=' + dataRec.name
                + ' contentType=' + dataRec.contentType + ' ownerId=' + dataRec.ownerId);
            expect(dataRec.groupPath, label + ' bytes are in the book\'s Artifacts group').toBe(container + '/Artifacts');
            expect(dataRec.name, label + ' data.data is named for the render-plan route').toMatch(/^render_plan_0_\d+$/);
            expect(dataRec.ownerId, label + ' bytes are owned by the rendering user').toBe(shared.user.id);
        }
        await request.get(REST + '/logout');
    });

    test('stranger: a second real principal with no grant on the book gets 404 "Book not found" from render-plan and /workflow (not 403, not 200); as the owner, nothing was created', async ({ request, playwright }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the fixture and the renders');
        expect(stranger && stranger.testUserName, 'stranger provisioned in beforeAll').toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;

        // Owner's view before.
        await login(request);
        const wfBefore = await workflow(request, PB);
        expect(wfBefore.status).toBe(200);
        const nvBefore = await nodeView(request, PB, r1.nodeObjectId);
        await request.get(REST + '/logout');

        // The stranger, in its own session. Not a mock: a real login as e2etest_iso42001.
        const sctx = await playwright.request.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            await loginAs(sctx, stranger, 'stranger');
            const rp = await postRenderPlan(sctx, bookObjectId, fx.scene.objectId);
            console.log('[p25-stranger-render-plan] HTTP ' + rp.status + ' in ' + rp.ms + 'ms: ' + rp.text.slice(0, 300));
            expect(rp.status, 'stranger render-plan: ' + rp.text.slice(0, 300)).toBe(404);
            expect(errorText(rp.text), 'absent and denied collapse to the same 404').toBe('Book not found');
            const wf = await workflow(sctx, PB);
            console.log('[p25-stranger-workflow] HTTP ' + wf.status + ' ' + wf.text.slice(0, 300));
            expect(wf.status, 'stranger GET /workflow: ' + wf.text.slice(0, 300)).toBe(404);
            expect(errorText(wf.text)).toBe('Book not found');
            const nvResp = await sctx.get(PB + '/workflow/node/' + r1.nodeObjectId);
            expect(nvResp.status(), 'stranger GET /workflow/node: ' + (await nvResp.text()).slice(0, 300)).toBe(404);
            const artResp = await sctx.get(PB + '/artifact/' + r2.artifactObjectId);
            expect(artResp.status(), 'stranger GET /artifact: ' + (await artResp.text()).slice(0, 300)).toBe(404);
            await sctx.get(REST + '/logout');
        } finally {
            await sctx.dispose();
        }

        // Owner's view after: identical. No node, no artifact, no revision, no status change.
        await login(request);
        const wfAfter = await workflow(request, PB);
        expect(wfAfter.status).toBe(200);
        expect((wfAfter.view.nodes || []).length, 'node count unchanged').toBe((wfBefore.view.nodes || []).length);
        expect(renderPlanNodesOf(wfAfter.view, fx.scene.objectId).length).toBe(1);
        const nvAfter = await nodeView(request, PB, r1.nodeObjectId);
        expect(chainIds(nvAfter, 'base'), 'base chain unchanged').toEqual(chainIds(nvBefore, 'base'));
        expect(chainIds(nvAfter, 'renderPlan'), 'renderPlan chain unchanged').toEqual(chainIds(nvBefore, 'renderPlan'));
        expect(nvAfter.storedStatus).toBe(nvBefore.storedStatus);
        expect(nvAfter.inputHash).toBe(nvBefore.inputHash);
        expect(!!nvAfter.pinned).toBe(!!nvBefore.pinned);
        await request.get(REST + '/logout');
    });

    test('pinned: approving base revision 2 pins the node; render-plan on the pinned node is refused 409 before any render, leaving chains/selection/status untouched; unpin restores pinned:false', async ({ request }) => {
        test.skip(!SD_ENABLED, 'PB_SD_TESTS!=1 — needs the renders from tests 1 and 3');
        expect(r2.artifactObjectId, 'render-plan 2 produced revision 2').toBeTruthy();
        expect(shared.user && shared.user.objectId, 'ensureSharedTestUser returned the user record: ' + JSON.stringify(shared.user)).toBeTruthy();
        const PB = PB_ROOT + '/' + bookObjectId;
        await login(request);

        // Approve revision 2 (V3 §10 rule 2): selected, APPROVED, approvedBy = the caller, node pinned.
        const apResp = await request.put(PB + '/artifact/' + r2.artifactObjectId + '/approve', { data: {} });
        const apText = await apResp.text();
        console.log('[p25-approve] HTTP ' + apResp.status() + ' ' + apText.slice(0, 500));
        expect(apResp.ok(), 'PUT /approve: HTTP ' + apResp.status() + ' ' + apText.slice(0, 300)).toBe(true);
        const ap = JSON.parse(apText);
        expect(ap.objectId).toBe(r2.artifactObjectId);
        expect(upper(ap.reviewStatus)).toBe('APPROVED');
        expect(ap.selected).toBe(true);
        expect(ap.approvedBy, 'approvedBy is the approving user, not AUTO').toBe(shared.user.objectId);
        expect(ap.approvedAt, 'approvedAt emitted once APPROVED').toBeTruthy();
        expect(ap.nodeObjectId).toBe(r1.nodeObjectId);
        const pinnedView = await nodeView(request, PB, r1.nodeObjectId);
        expect(pinnedView.pinned, 'approve pins the producing node').toBe(true);
        expect(upper(pinnedView.storedStatus)).toBe('DONE_UNVERIFIED');
        const baseBefore = pinnedView.artifacts.base;
        const planBefore = chainIds(pinnedView, 'renderPlan');
        expect(baseBefore.length, 'two base revisions going in').toBe(2);
        expect(planBefore.length, 'two plan revisions going in').toBe(2);

        // The refusal: 409 with the exact pinned message, before the plan is rebuilt or anything is sent.
        const refused = await postRenderPlan(request, bookObjectId, fx.scene.objectId);
        console.log('[p25-pinned-409] HTTP ' + refused.status + ' in ' + refused.ms + 'ms (logged, never asserted): ' + refused.text.slice(0, 400));
        expect(refused.status, 'render-plan on a pinned node: ' + refused.text.slice(0, 300)).toBe(409);
        expect(errorText(refused.text)).toBe('Node ' + fx.handle + ' is pinned. Unpin it first - pinning means do not replace this artifact.');

        // Nothing moved: same two base revisions, same two plans (the pinned gate sits BEFORE the plan is
        // persisted, unlike the no-text 409 which persists its plan first), revision 2 still the approved
        // selection, node status and inputHash unchanged, still pinned, workflow node count unchanged.
        const after = await nodeView(request, PB, r1.nodeObjectId);
        console.log('[p25-pinned-after] ' + JSON.stringify({ pinned: after.pinned, storedStatus: after.storedStatus, base: after.artifacts.base, renderPlan: chainIds(after, 'renderPlan') }));
        expect(after.pinned).toBe(true);
        expect(upper(after.storedStatus), 'the refusal did not FAIL or RUN the node').toBe('DONE_UNVERIFIED');
        expect(after.inputHash).toBe(pinnedView.inputHash);
        expect(chainIds(after, 'base'), 'no base revision 3').toEqual([r1.artifactObjectId, r2.artifactObjectId]);
        expect(chainIds(after, 'renderPlan'), 'no third renderPlan artifact').toEqual(planBefore);
        expect(after.artifacts.base[1].selected).toBe(true);
        expect(upper(after.artifacts.base[1].reviewStatus)).toBe('APPROVED');
        expect(after.artifacts.base[1].approvedBy).toBe(shared.user.objectId);
        expect(after.artifacts.base[0].selected).toBe(false);
        const wf = await workflow(request, PB);
        expect(wf.status).toBe(200);
        expect((wf.view.nodes || []).length, 'scene 0 + scene 1 nodes only').toBe(2);
        const pages = await (await request.get(PB + '/pages')).json();
        const pg = pages.find(p => p.objectId === fx.scene.objectId);
        expect(pg.sceneNodeObjectId == null, 'sceneNode still untouched: ' + JSON.stringify(pg)).toBe(true);

        // Unpin (the only way past the gate): pinned:false, approval untouched.
        const unpinResp = await request.post(PB + '/node/' + r1.nodeObjectId + '/pin', { data: { pinned: false } });
        const unpinText = await unpinResp.text();
        console.log('[p25-unpin] HTTP ' + unpinResp.status() + ' ' + unpinText.slice(0, 300));
        expect(unpinResp.ok(), 'POST /pin {pinned:false}: HTTP ' + unpinResp.status() + ' ' + unpinText.slice(0, 300)).toBe(true);
        const unpin = JSON.parse(unpinText);
        expect(unpin.nodeObjectId).toBe(r1.nodeObjectId);
        expect(unpin.handle).toBe(fx.handle);
        expect(unpin.pinned).toBe(false);
        const unpinned = await nodeView(request, PB, r1.nodeObjectId);
        expect(unpinned.pinned, 'node view agrees').toBe(false);
        expect(upper(unpinned.artifacts.base[1].reviewStatus), 'unpin does not revoke the approval').toBe('APPROVED');
        expect(unpinned.artifacts.base[1].selected).toBe(true);
        await request.get(REST + '/logout');
    });
});
