/**
 * REST-level tests for three Service7 routes touched on 2026-10-07 (LLM lane items 13/14/15):
 *
 *   13. POST /rest/chat/chain?async=true  +  GET /rest/chat/chain/status/{planId}
 *       The status route used to be a placeholder; it now reads the AsyncJobRegistry. Also covers
 *       the escJson fix: the chain result embeds an MCP block with literal newlines, which used to
 *       produce an unparseable body.
 *   14. POST /rest/chat/{objectId}/generateScene — an unrecognized compositeMode is rejected with
 *       400 BEFORE any session/config lookup (previously silently downgraded to "classic").
 *   15. POST /rest/olio/olio.charPerson/{objectId}/reimage — the literal "((DEPRECATED))" prompt
 *       argument was removed end-to-end (OlioService → SDUtil → SWUtil → NarrativeUtil). This hits
 *       the live Swarm SD server configured on the stack and is gated behind E2E_SD_LIVE=1 because
 *       it generates a real image (30-120 s) and must run serially. Also asserts the rendered PNG is
 *       the 512x512 the posted sdConfig asked for (SWUtil.newTxt2Img now honors width/height).
 *   14-live. POST /rest/chat/{objectId}/generateScene, the full live path as the shared user: own
 *       connection+chatConfig (ensureChatConfig, LiteLLM route), two characters, a prompt config, a
 *       session via /chat/new, two real chat turns via /chat/text so the scene-description LLM call
 *       has history, then two renders against the stack SD server:
 *         (a) compositeMode:'classic' — the classic img2img branch; the PNG is the posted 512x512 and
 *             the container log shows `Scene composite [classic]`, no `[kontext]`/`[flux2]`.
 *         (b) compositeMode and useKontext ABSENT — the olio.sd.config schema default (`flux2`,
 *             configModel.json) is materialized by the deserializer, so the body resolves to FLUX2
 *             at the flux2Defaults.json size (1024x768), not classic. The Phase 0
 *             legacyKontextDefault=false flip (V3 design §37.3) is only reachable for hand-built
 *             configs with a null compositeMode; a REST body is never one (design §1709-1716).
 *       Gated behind E2E_SD_LIVE=1 like item 15.
 *       Two turns, not one: Chat.generateSceneDescription formats history from index pruneSkip+2 = 3
 *       (the assist-template layout: system, user-template, assistant-template). The chatConfig
 *       schema default is assist=false, so the session is system(0)+user(1)+assistant(2) after one
 *       turn and the first exchange is invisible — the description silently falls back to
 *       "two people facing each other" with no LLM call. Measured 2026-10-10 on am7test.
 *
 * All calls run as the shared non-admin test user (ensureSharedTestUser), never admin.
 * Run: PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chatChainSceneReimageRoutes.spec.js --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import { request as pwRequest } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout, ensurePath, ensureChatConfig, resolveChatRoute } from './helpers/api.js';
import { randomUUID } from 'crypto';
import { gunzipSync } from 'node:zlib';
import { execSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const APP_CONTAINER = process.env.E2E_APP_CONTAINER || 'am7test-am7-1';

async function userContext(request) {
    const shared = await ensureSharedTestUser(request);
    const ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    const login = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(login.ok(), 'shared test user login').toBe(true);
    return { ctx, shared };
}

async function pollChainStatus(ctx, jobId, maxMs = 30000) {
    const started = Date.now();
    let last = null;
    while (Date.now() - started < maxMs) {
        const resp = await ctx.get(REST + '/chat/chain/status/' + jobId);
        expect(resp.status(), 'status route should answer 200 for an owned job').toBe(200);
        const text = await resp.text();
        /// JSON.parse is the point: the result embeds an MCP block containing newlines, and the
        /// old escJson did not escape control characters, so this used to throw.
        last = JSON.parse(text);
        if (last.terminal) return last;
        await new Promise(r => setTimeout(r, 500));
    }
    return last;
}

test.describe('chat chain async status route (item 13)', () => {

    test('unknown planId answers 404 with status "unknown"', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const resp = await ctx.get(REST + '/chat/chain/status/' + randomUUID());
            expect(resp.status()).toBe(404);
            const body = await resp.json();
            expect(body.status).toBe('unknown');
            expect(body.error).toContain('No chain job');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('unauthenticated status request is rejected', async () => {
        const ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const resp = await ctx.get(REST + '/chat/chain/status/' + randomUUID());
            expect([401, 403]).toContain(resp.status());
        } finally {
            await ctx.dispose();
        }
    });

    test('missing planQuery answers 400', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const resp = await ctx.post(REST + '/chat/chain', {
                data: { plan: { schema: 'tool.plan', name: 'e2e-no-query', executed: true, steps: [] } }
            });
            expect(resp.status()).toBe(400);
            const body = await resp.json();
            expect(body.error).toContain('planQuery');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('async chain with an already-executed plan completes and status returns a parseable result', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const name = 'e2e-chain-' + randomUUID().slice(0, 8);
            const submit = await ctx.post(REST + '/chat/chain?async=true', {
                data: {
                    planQuery: 'e2e async chain ' + name,
                    /// executed:true makes ChainExecutor return early (no LLM, no tool calls), so the
                    /// test exercises the job plumbing deterministically.
                    plan: { schema: 'tool.plan', name: name, executed: true, steps: [] }
                }
            });
            expect(submit.status(), 'async submit should be accepted').toBe(202);
            const accepted = await submit.json();
            expect(accepted.jobId).toBeTruthy();

            const status = await pollChainStatus(ctx, accepted.jobId);
            expect(status, 'job should reach a terminal state').toBeTruthy();
            expect(status.planId).toBe(accepted.jobId);
            expect(status.jobId).toBe(accepted.jobId);
            expect(status.kind).toBe('chat.chain');
            expect(status.terminal).toBe(true);
            expect(status.status).toBe('completed');
            expect(status.cancelled).toBe(false);
            expect(status.result).toBeTruthy();
            expect(status.result.status).toBe('complete');
            expect(status.result.planQuery).toBe('e2e async chain ' + name);
            expect(status.result.mcpContext).toContain('am7://chain/' + name);
            expect(status.result.mcpContext).toContain('\n');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('async chain with an empty unexecuted plan reports failed with the executor error', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const name = 'e2e-chain-fail-' + randomUUID().slice(0, 8);
            const submit = await ctx.post(REST + '/chat/chain?async=true', {
                data: {
                    planQuery: 'e2e failing chain ' + name,
                    plan: { schema: 'tool.plan', name: name, executed: false, steps: [] }
                }
            });
            expect(submit.status()).toBe(202);
            const accepted = await submit.json();

            const status = await pollChainStatus(ctx, accepted.jobId);
            expect(status.terminal).toBe(true);
            expect(status.status).toBe('failed');
            expect(status.error).toContain('No steps in plan');
            expect(status.result).toBeUndefined();
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('a job submitted by another user is not visible', async ({ request }) => {
        const { ctx } = await userContext(request);
        const other = await ensureSharedTestUser(request, { name: 'e2etest_chain_other' });
        const otherCtx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        try {
            const submit = await ctx.post(REST + '/chat/chain?async=true', {
                data: { planQuery: 'e2e owner scope', plan: { schema: 'tool.plan', name: 'e2e-scope', executed: true, steps: [] } }
            });
            expect(submit.status()).toBe(202);
            const accepted = await submit.json();

            const login = await apiLogin(otherCtx, { user: other.testUserName, password: other.testPassword });
            expect(login.ok()).toBe(true);
            const resp = await otherCtx.get(REST + '/chat/chain/status/' + accepted.jobId);
            expect(resp.status(), 'foreign job must look unknown').toBe(404);
        } finally {
            await apiLogout(otherCtx);
            await otherCtx.dispose();
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});

test.describe('generateScene composite mode validation (item 14)', () => {

    test('unsupported compositeMode answers 400 before any session lookup', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            const resp = await ctx.post(REST + '/chat/' + randomUUID() + '/generateScene', {
                data: { schema: 'olio.sd.config', compositeMode: 'bogus' }
            });
            expect(resp.status()).toBe(400);
            const body = await resp.json();
            expect(body.error).toBe("Unsupported composite mode 'bogus'; expected flux2|kontext|classic");
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });

    test('each supported compositeMode passes validation and reaches the session lookup', async ({ request }) => {
        const { ctx } = await userContext(request);
        try {
            for (const mode of ['flux2', 'kontext', 'classic', 'KONTEXT']) {
                const resp = await ctx.post(REST + '/chat/' + randomUUID() + '/generateScene', {
                    data: { schema: 'olio.sd.config', compositeMode: mode }
                });
                expect(resp.status(), 'mode ' + mode + ' should not be rejected as unsupported').toBe(404);
                const body = await resp.json();
                expect(body.error).toBe('Chat request not found');
            }
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});

test.describe('charPerson reimage without the deprecated prompt argument (item 15)', () => {
    test.skip(!process.env.E2E_SD_LIVE, 'set E2E_SD_LIVE=1 to generate a real image on the stack SD server');

    test('POST /olio/olio.charPerson/{id}/reimage returns a new portrait', async ({ request }) => {
        test.setTimeout(400000);
        const { ctx, shared } = await userContext(request);
        let profileId = null;
        let originalPortraitId = null;
        try {
            /// ensureSharedTestUser only projects id/objectId/name; the principal route carries the
            /// numeric organizationId the search condition needs (typed long, so a number).
            const princResp = await ctx.get(REST + '/principal');
            expect(princResp.ok()).toBe(true);
            const principal = await princResp.json();
            expect(principal.name).toBe(shared.testUserName);

            /// Any character the shared user owns (PictureBook/ChapBook test data, or the bare
            /// "Mira SceneE2E"/"Theo SceneE2E" fixtures from item 14-live). A bare person still renders;
            /// its prompt just carries the literal-null hair/eye tokens and the no-apparel clause.
            const search = await ctx.post(REST + '/model/search', {
                data: {
                    schema: 'io.query',
                    type: 'olio.charPerson',
                    request: ['id', 'objectId', 'name', 'ownerId'],
                    fields: [
                        { name: 'organizationId', comparator: 'EQUALS', value: principal.organizationId },
                        { name: 'ownerId', comparator: 'EQUALS', value: principal.id }
                    ],
                    recordCount: 1,
                    cache: false
                }
            });
            expect(search.ok()).toBe(true);
            const found = (await search.json()).results || [];
            test.skip(found.length === 0, 'shared test user owns no olio.charPerson to reimage');
            const person = found[0];

            const full = await ctx.get(REST + '/model/olio.charPerson/' + person.objectId + '/full');
            expect(full.ok()).toBe(true);
            const fullPerson = await full.json();
            profileId = fullPerson.profile ? fullPerson.profile.id : null;
            originalPortraitId = fullPerson.profile && fullPerson.profile.portrait ? fullPerson.profile.portrait.id : null;

            /// Same template the Ux uses (sdConfig.fetchTemplate → /olio/randomImageConfig), so the
            /// checkpoint name is the one this deployment actually has installed.
            const tmplResp = await ctx.get(REST + '/olio/randomImageConfig');
            expect(tmplResp.ok()).toBe(true);
            const sdConfig = await tmplResp.json();
            sdConfig.hires = false;
            sdConfig.steps = 12;
            sdConfig.width = 512;
            sdConfig.height = 512;
            sdConfig.seed = -1;
            sdConfig.imageSetting = 'a quiet city park at noon';
            sdConfig.bodyStyle = 'full body';
            sdConfig.imageAction = 'standing';
            delete sdConfig.style;
            delete sdConfig.landscapeSetting;
            delete sdConfig.referenceImageId;

            const started = Date.now();
            const resp = await ctx.post(REST + '/olio/olio.charPerson/' + person.objectId + '/reimage', {
                data: sdConfig, timeout: 380000
            });
            const elapsed = Date.now() - started;
            expect(resp.status()).toBe(200);
            const text = await resp.text();
            console.log('[item15] reimage ' + person.name + ' took ' + elapsed + 'ms, body chars=' + text.length);
            expect(text.length, 'reimage should return the new portrait record, not {}').toBeGreaterThan(2);
            const image = JSON.parse(text);
            expect(image.schema).toBe('data.data');
            expect(image.objectId).toBeTruthy();
            expect(image.contentType).toContain('image');
            console.log('[item15] new portrait objectId=' + image.objectId + ' name=' + image.name + ' group=' + image.groupPath);

            /// The response is the only read path the acting user has for this record (it is created
            /// as the olio principal in the world gallery), so inspect the bytes here: a real PNG whose
            /// IHDR carries the 512x512 the posted sdConfig asked for. The Swarm portrait path used to
            /// drop width/height and render at the request object's 1024x1024 default.
            const { dims } = await capturePng(image, 'reimage-portrait.png', '[item15]');
            expect(dims, 'rendered dimensions follow the posted sdConfig width/height').toEqual([512, 512]);
        } finally {
            /// Put the character's portrait back the way we found it (reimage re-points profile.portrait).
            if (profileId && originalPortraitId) {
                await ctx.patch(REST + '/model', {
                    data: { schema: 'identity.profile', id: profileId, portrait: { schema: 'data.data', id: originalPortraitId } }
                }).catch(() => {});
            }
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});

/// Name+org+group search with cache:false. Group-scoped on purpose: the same fixture names exist under
/// several test users' home dirs, and a name+org query that also matches another user's record is
/// DENIED as a whole by AccessPoint.find (looks like "no such record").
async function findNamed(ctx, type, name, organizationId, groupId, fields) {
    const resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: type,
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: organizationId },
                { name: 'groupId', comparator: 'equals', value: Number(groupId) }
            ],
            request: fields || ['id', 'objectId', 'name'],
            recordCount: 1,
            cache: false
        }
    });
    expect(resp.ok(), 'search ' + type + ' ' + name).toBe(true);
    const found = (await resp.json()).results || [];
    return found.length ? found[0] : null;
}

async function findOrCreateNamed(ctx, type, name, organizationId, dir, body, fields) {
    let rec = await findNamed(ctx, type, name, organizationId, dir.id, fields);
    if (!rec) {
        const create = await ctx.post(REST + '/model', {
            data: Object.assign({ schema: type, name: name, groupId: dir.id, groupPath: dir.path }, body)
        });
        expect(create.ok(), 'create ' + type + ' ' + name + ' HTTP ' + create.status()).toBe(true);
        rec = await findNamed(ctx, type, name, organizationId, dir.id, fields);
    }
    expect(rec && rec.objectId, type + ' ' + name + ' resolvable after create').toBeTruthy();
    return rec;
}

/// Decode a generateScene response body into PNG bytes, write it to the test output dir and attach it,
/// then return the bytes + IHDR dimensions. Written BEFORE any dimension assertion so the image is
/// always captured for inspection.
async function capturePng(image, fileName, logTag) {
    let raw = Buffer.from(image.dataBytesStore || '', 'base64');
    if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw);
    const pngPath = test.info().outputPath(fileName);
    writeFileSync(pngPath, raw);
    await test.info().attach(fileName, { path: pngPath, contentType: 'image/png' });
    expect(raw.subarray(0, 8).toString('hex'), fileName + ' bytes are a PNG').toBe('89504e470d0a1a0a');
    const dims = [raw.readUInt32BE(16), raw.readUInt32BE(20)];
    console.log(logTag + ' ' + fileName + ' rendered ' + dims[0] + 'x' + dims[1] + ' (' + raw.length + ' bytes) -> ' + pngPath);
    return { raw, dims };
}

async function chatTurn(ctx, session, message) {
    const started = Date.now();
    const turn = await ctx.post(REST + '/chat/text', {
        data: Object.assign({}, session, { uid: randomUUID(), message: message }),
        timeout: 400000
    });
    const ms = Date.now() - started;
    const body = await turn.text();
    console.log('[item14-live] chat turn HTTP ' + turn.status() + ' durationMs=' + ms
        + ' body=' + body.slice(0, 300).replace(/\s+/g, ' '));
    expect(turn.status(), 'chat turn must get a real LLM reply').toBe(200);
    const rep = JSON.parse(body);
    expect(Array.isArray(rep.messages) && rep.messages.length > 0, 'chat turn returned messages').toBe(true);
    return rep;
}

function dockerLogsSince(sinceIso) {
    return execSync('docker logs --since ' + sinceIso + ' ' + APP_CONTAINER + ' 2>&1',
        { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

async function generateScene(ctx, session, sdConfig, tag) {
    /// Isolate this request's log lines by snapshotting the container log before and after with the
    /// SAME absolute --since, then taking the suffix. A host-clock "--since now-5s" window is not
    /// reliable here: the Docker VM clock is skewed from the host, so the previous render's lines leaked
    /// into the next request's window (measured 2026-10-10: a 17:10:41 classic line appeared in a
    /// window that opened at host 17:10:56).
    const sinceAbs = new Date(Date.now() - 30 * 60 * 1000).toISOString();
    const before = dockerLogsSince(sinceAbs);
    const started = Date.now();
    const resp = await ctx.post(REST + '/chat/' + session.objectId + '/generateScene', {
        data: sdConfig, timeout: 400000
    });
    const durationMs = Date.now() - started;
    const text = await resp.text();
    console.log('[item14-live] ' + tag + ' generateScene HTTP ' + resp.status() + ' durationMs=' + durationMs + ' body chars=' + text.length);
    expect(resp.status(), tag + ' generateScene: ' + text.slice(0, 300)).toBe(200);
    const image = JSON.parse(text);
    expect(image.schema).toBe('data.data');
    expect(image.objectId).toBeTruthy();
    expect(image.contentType).toContain('image');
    expect(image.name, 'scene label is "<system firstName> and <user firstName>"').toContain('Mira and Theo');
    console.log('[item14-live] ' + tag + ' scene objectId=' + image.objectId + ' name=' + image.name + ' group=' + image.groupPath);
    const png = await capturePng(image, 'scene-' + tag + '.png', '[item14-live]');
    /// The pipeline choice is only visible in the server log.
    const after = dockerLogsSince(sinceAbs);
    expect(after.startsWith(before), tag + ': container log snapshot is a prefix of the later snapshot').toBe(true);
    const log = after.slice(before.length);
    const compositeLine = log.split('\n').find(l => l.includes('Scene composite ['));
    console.log('[item14-live] ' + tag + ' ' + (compositeLine ? compositeLine.trim() : 'NO "Scene composite [" line found'));
    const sceneDescLine = log.split('\n').find(l => l.includes('Scene description:'));
    console.log('[item14-live] ' + tag + ' ' + (sceneDescLine ? sceneDescLine.trim() : 'NO scene description line found'));
    return { image, raw: png.raw, dims: png.dims, log, compositeLine, sceneDescLine, durationMs };
}

test.describe('generateScene live composite modes (item 14 live)', () => {
    test.skip(!process.env.E2E_SD_LIVE, 'set E2E_SD_LIVE=1 to generate a real scene on the stack SD server');

    test('explicit classic honors the posted size; absent compositeMode resolves to the schema default flux2', async ({ request }) => {
        /// Two real chat turns + two scene-description LLM calls + two SD renders. The container Ollama
        /// may be CPU-only; timings are recorded, never asserted.
        test.setTimeout(1500000);
        const { ctx, shared } = await userContext(request);
        try {
            const princResp = await ctx.get(REST + '/principal');
            expect(princResp.ok()).toBe(true);
            const principal = await princResp.json();
            expect(principal.name).toBe(shared.testUserName);
            const orgId = principal.organizationId;

            const route = await resolveChatRoute();
            console.log('[item14-live] [e2e-llm] route=' + route.route + ' server=' + route.serverUrl
                + ' analysis=' + route.analysisModel + ' pb=' + route.pbModel);

            /// A connection + chatConfig the shared user OWNS. generateScene reads the chatRequest's own
            /// chatConfig for the scene-description call (Chat.generateSceneDescription), so the library
            /// contentAnalysis config is not involved here.
            const cfgName = await ensureChatConfig(request, orgId, {
                configName: 'e2e-scene-classic', connectionName: 'e2e-scene-classic-conn'
            });
            expect(cfgName, 'ensureChatConfig resolved a user-owned chatConfig').toBe('e2e-scene-classic');
            const chatDir = await ensurePath(ctx, 'auth.group', 'data', '~/Chat');
            expect(chatDir && chatDir.id, '~/Chat').toBeTruthy();
            let cfg = await findNamed(ctx, 'olio.llm.chatConfig', cfgName, orgId, chatDir.id,
                ['id', 'objectId', 'name', 'model', 'analyzeModel', 'systemCharacter', 'userCharacter']);
            expect(cfg && cfg.objectId, 'chatConfig ' + cfgName).toBeTruthy();

            /// Two characters in the shared user's ~/Characters (stable names, reused across runs).
            const charDir = await ensurePath(ctx, 'auth.group', 'data', '~/Characters');
            expect(charDir && charDir.id, '~/Characters').toBeTruthy();
            const sysChar = await findOrCreateNamed(ctx, 'olio.charPerson', 'Mira SceneE2E', orgId, charDir,
                { firstName: 'Mira', lastName: 'SceneE2E', gender: 'female', age: 30, race: ['E'] });
            const usrChar = await findOrCreateNamed(ctx, 'olio.charPerson', 'Theo SceneE2E', orgId, charDir,
                { firstName: 'Theo', lastName: 'SceneE2E', gender: 'male', age: 30, race: ['E'] });

            const sysId = cfg.systemCharacter && cfg.systemCharacter.id;
            const usrId = cfg.userCharacter && cfg.userCharacter.id;
            if (sysId !== sysChar.id || usrId !== usrChar.id) {
                const patch = await ctx.patch(REST + '/model', {
                    data: {
                        schema: 'olio.llm.chatConfig', id: cfg.id, objectId: cfg.objectId, name: cfg.name,
                        systemCharacter: { schema: 'olio.charPerson', id: sysChar.id, objectId: sysChar.objectId },
                        userCharacter: { schema: 'olio.charPerson', id: usrChar.id, objectId: usrChar.objectId }
                    }
                });
                const patchText = (await patch.text()).trim();
                expect(patch.ok() && patchText === 'true', 'PATCH chatConfig characters: HTTP ' + patch.status() + ' ' + patchText).toBe(true);
                cfg = await findNamed(ctx, 'olio.llm.chatConfig', cfgName, orgId, chatDir.id,
                    ['id', 'objectId', 'name', 'systemCharacter', 'userCharacter']);
                expect(cfg.systemCharacter && cfg.systemCharacter.id, 'systemCharacter persisted').toBe(sysChar.id);
                expect(cfg.userCharacter && cfg.userCharacter.id, 'userCharacter persisted').toBe(usrChar.id);
            }

            /// generateScene requires a promptConfig (promptTemplate alone is not accepted there).
            const promptDir = await ensurePath(ctx, 'auth.group', 'data', '~/Prompts');
            expect(promptDir && promptDir.id, '~/Prompts').toBeTruthy();
            const promptCfg = await findOrCreateNamed(ctx, 'olio.llm.promptConfig', 'e2e-scene-classic-prompt', orgId, promptDir,
                { system: ['You are a brief roleplay partner. Reply in one short sentence, in character, no preamble.'] });

            /// Session via the chat service's own create route (puts it where the Ux expects it).
            const sessionName = 'e2e-scene-classic-' + randomUUID().slice(0, 8);
            const newResp = await ctx.post(REST + '/chat/new', {
                data: {
                    schema: 'olio.llm.chatRequest', name: sessionName,
                    chatConfig: { schema: 'olio.llm.chatConfig', id: cfg.id, objectId: cfg.objectId },
                    promptConfig: { schema: 'olio.llm.promptConfig', id: promptCfg.id, objectId: promptCfg.objectId }
                }
            });
            expect(newResp.status(), '/chat/new').toBe(200);
            const session = await newResp.json();
            expect(session.objectId, 'session objectId').toBeTruthy();
            console.log('[item14-live] session ' + sessionName + ' ' + session.objectId);

            /// Two real turns (see the header: with assist=false the first exchange is below the
            /// pruneSkip+2 start index, so one turn leaves generateSceneDescription with no history and
            /// the LLM leg of this route unexercised).
            await chatTurn(ctx, session, 'Walk with me along the harbor wall at sunset and tell me about the voyage.');
            await chatTurn(ctx, session, 'Then let us climb the lighthouse stairs and watch the ships come in.');

            /// Same template the Ux uses. Strip the mode fields and the optional refs so each case
            /// below controls the mode explicitly.
            const tmplResp = await ctx.get(REST + '/olio/randomImageConfig');
            expect(tmplResp.ok()).toBe(true);
            const template = await tmplResp.json();
            delete template.compositeMode;
            delete template.useKontext;
            delete template.style;
            delete template.landscapeSetting;
            delete template.referenceImageId;
            template.hires = false;
            template.steps = 12;
            template.width = 512;
            template.height = 512;
            template.seed = -1;
            template.skipLandscape = true;

            /// (a) explicit classic: img2img over a composited canvas; honors the posted width/height.
            const classic = await generateScene(ctx, session, Object.assign({}, template, { compositeMode: 'classic' }), 'classic');
            expect(classic.compositeLine, 'classic pipeline ran').toContain('Scene composite [classic]');
            expect(classic.log, 'kontext pipeline must not run for compositeMode=classic').not.toContain('Scene composite [kontext]');
            expect(classic.log, 'flux2 pipeline must not run for compositeMode=classic').not.toContain('Scene composite [flux2]');
            expect(classic.log).toContain('generateScene Stage 2: Skipped (skipLandscape=true)');
            expect(classic.dims, 'classic honors the posted sdConfig width/height').toEqual([512, 512]);
            expect(classic.sceneDescLine, 'scene description was produced').toBeTruthy();
            expect(classic.sceneDescLine, 'scene description came from the LLM, not the no-history fallback')
                .not.toContain('two people facing each other');

            /// (b) absent compositeMode/useKontext: the deserializer materializes the schema default
            /// (compositeMode="flux2"), so this is the FLUX2 branch at the flux2Defaults.json size —
            /// NOT classic. Pins the contract so a future "absent means classic" assumption fails here.
            const dflt = await generateScene(ctx, session, Object.assign({}, template), 'schema-default');
            expect(dflt.compositeLine, 'absent compositeMode resolves to the schema default flux2').toContain('Scene composite [flux2]');
            expect(dflt.log, 'kontext pipeline must not run for an absent compositeMode').not.toContain('Scene composite [kontext]');
            expect(dflt.log, 'classic pipeline must not run for an absent compositeMode').not.toContain('Scene composite [classic]');
            expect(dflt.log).toContain('generateScene Stage 2: Skipped (skipLandscape=true)');
            expect(dflt.dims, 'flux2 renders at the flux2Defaults.json size, overriding the posted 512x512').toEqual([1024, 768]);
            expect(dflt.sceneDescLine, 'scene description was produced').toBeTruthy();
            expect(dflt.sceneDescLine, 'scene description came from the LLM, not the no-history fallback')
                .not.toContain('two people facing each other');
        } finally {
            await apiLogout(ctx);
            await ctx.dispose();
        }
    });
});
