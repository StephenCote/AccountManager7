/**
 * Chaptered-manuscript PictureBook flow through the REAL browser + REAL Docker stack, with the LLM
 * answered IN-PROCESS by the LlmEmulator (system.connection dialect=emulator, serverUrl=emulator://<set>,
 * fixtures bind-mounted at /fixtures/llm/<set>/). Nothing else is stubbed: real Tika text extraction,
 * real chapter detection, real async extraction jobs, real olio.pb.book/series/charPerson persistence,
 * real Ux wizard, list, reader. The ONLY test-side network hook is the optional truncation of the
 * detect-boundaries RESPONSE to the first PB_UX_MAX_CHAPTERS chapters (same hook the live spec uses).
 *
 * Derived from pictureBookChapteredManuscriptUx.spec.js (live LLM). This spec is deterministic and fast,
 * and runs in one of two modes, decided by whether llm-fixtures/harlots-eight/ holds recorded
 * completions next to its manifest — committed as `<sha256>.json` entries inside `fixtures.zip`, with
 * optional loose `<sha256>.json` recorder drops (which win over a same-named zip entry); both are read
 * through helpers/llmFixtures.js:
 *
 *   REPLAY MODE (recordings present) — the static cache Stephen asked for. The fixtures are the real
 *   model's answers (goekdenizguelmez/JOSIEFIED-Qwen3:8b on 192.168.1.42), captured by running the live
 *   spec with LLM_EMULATOR_RECORD_DIR set. The emulator's request key hashes model + every message, so
 *   a HIT proves the pipeline rebuilt a request byte-identical to one the recording run sent. Parity
 *   proof: every persisted scene title must appear in some recorded completion (a synthesized title
 *   never can — it starts with a chapter heading). Cast assertions are ground truth about the manuscript
 *   (fairies exist; no off-race labels; no ethnicity) and hold for the real model's output.
 *
 *   RECORDING GAPS (replay mode) — the manifest may declare `recordingGaps`: [{chapter (1-based),
 *   fromChunk (0-based), reason}]. The recording run lost chapter 2 from chunk 15 (Ollama stalled past
 *   the 905 s latch), and because each chunk's prompt carries the previous chunk's scene list, every
 *   later chunk of that chapter builds a request the recording never saw — the synthesizer answers the
 *   rest of the chapter. So a scene is EXEMPT from the recorded-title check iff its chapter has a gap
 *   and its `sourceChunk` >= that gap's fromChunk, and the gap is PINNED: the emulator's per-kind
 *   extract-chunk SYNTH count must equal Σ(K − fromChunk) over the gap chapters actually processed, with
 *   K the chapter's chunk count. With no gap declared for any processed chapter, replay demands synth == 0.
 *
 *   SYNTH MODE (manifest only) — the emulator's synthesized first scene title for a passage begins with
 *   the passage's first non-blank line, so "first scene of chapter N starts with 'Chapter N'" proves
 *   chapter N's own text reached the extraction prompt.
 *
 *   KNOWN, DOCUMENTED EXCEPTION (synth mode) — chapter 1. PbChapterBoundaryUtil folds a short lead
 *   (< 1000 chars before the first heading) into chapter 1, so chapter 1's range starts at offset 0 and
 *   its first non-blank line is the manuscript's title line ("Harlot's Eight"), not "Chapter 1". Measured
 *   on HarlotsEight_Vol1_SM.docx: "Chapter 1" sits at offset 16. The assertion therefore accepts EITHER
 *   "Chapter 1" OR the manuscript's own lead line for chapter 1 — every other chapter must start with
 *   its heading, exactly.
 *
 * Dedicated NON-ADMIN user `e2etest_pbemu` (provisioned through ensureSharedTestUser({name})). Its own
 * ~/Chat/contentAnalysis chatConfig points at emulator://harlots-eight; the live-LLM spec's
 * e2etest_shared/contentAnalysis is NEVER touched. ChatUtil.resolveConfig checks the user's own ~/Chat
 * first, so the wizard's default "contentAnalysis" resolves to THIS user's emulator config.
 *
 * Four serial tests (a failure skips the ones after it, so the regression guard is LAST):
 *   1. N chapters, clean set  — detect-boundaries, wizard, REST parity, reader, cast, stats.
 *   2. harlots-eight-faults   — one emulated HTTP 500 mid-chapter; the retry absorbs it.
 *   3. harlots-eight-unreachable — two emulated ConnectExceptions; breaker stops at chapter 1,
 *      wizard stays open naming the cause, nothing is falsely saved.
 *   4. re-run on the same manuscript — 409 reuse, no duplicate series/books, and the first run's
 *      scenes must SURVIVE. Regression guard for a defect this spec exposed on 2026-09-26: the
 *      re-run's create-from-scenes hit the data.note (name, groupId, organizationId) unique key for
 *      every scene, persisted none, and saveMeta overwrote .pictureBookMeta with scenes: [] — every
 *      chapter book read back with 0 scenes. Fixed the same day (PictureBookUtil.createSceneNote now
 *      patches a same-named note in place; createFromScenes fails with 500 instead of saving an empty
 *      list); this test is green against that fix.
 *
 * Notes on two things that look like failures and are not: RecordSerializer omits INT fields equal
 * to their schema default, so a persisted sourceRange with startOffset 0 arrives with the key MISSING
 * (readSourceRange normalizes it); and the race-grounding gate (PictureBookUtil.resolveTextRace)
 * leaves race EMPTY unless the passages literally contain the label, so most charPersons have none.
 *
 *   cd src/AccountManagerUx752
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test \
 *       e2e/pictureBookChapteredManuscriptEmulated.spec.js --workers=1 --project=chromium
 *   PB_UX_MAX_CHAPTERS=21 ...            (whole manuscript; 0 also means "all")
 *   PB_EMU_FAULT_CHAPTERS=2 ...          (chapters handed to the fault-injection test)
 *   PB_EMU_CONTAINER=am7test-am7-1 ...   (docker container whose logs are read for evidence)
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, ensurePath } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';
import { listFixtureNames, readFixture } from './helpers/llmFixtures.js';
import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
const PB_REST = REST + '/olio/picture-book';

const EMU_USER = 'e2etest_pbemu';
const EMU_PASSWORD = 'password';
const ORG_PATH = '/Development';

const DOCX_PATH = process.env.PB_UX_DOCX
    || path.resolve(__dirname, '../../AccountManagerObjects7/media/HarlotsEight_Vol1_SM.docx');
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const IS_HARLOTS = path.basename(DOCX_PATH) === 'HarlotsEight_Vol1_SM.docx';
// First non-blank line of the manuscript (folded title page) — see the chapter-1 note in the header.
const HARLOTS_LEAD_LINE = "Harlot's Eight";
const HARLOTS_CHAPTERS = 21;

// The model string is PART of the emulator's request key (LlmEmulator.requestKey hashes model + every
// message), so it must equal the string the RECORDING run sent, byte for byte. The harlots-eight
// fixtures were captured through pictureBookChapteredManuscriptUx.spec.js against Ollama at
// 192.168.1.42 with this exact model name; a different string here replays nothing (all synth).
const EMU_MODEL = process.env.PB_EMU_MODEL || 'goekdenizguelmez/JOSIEFIED-Qwen3:8b';
const MAIN_SET = 'harlots-eight';
// Fixture sets are bind-mounted read-only into the container from this repo directory, so the host-side
// spec can see whether the main set holds real recordings or only its manifest. Recordings are the
// `<sha256>.json` entries of <set>/fixtures.zip plus any loose `<sha256>.json` recorder drops (loose
// wins); helpers/llmFixtures.js reads both and THROWS on a corrupt zip rather than reporting an empty set.
const FIXTURE_ROOT = path.resolve(__dirname, '../../AccountManagerObjects7/src/test/resources/llm-fixtures');
function realFixtureFiles(set) {
    return listFixtureNames(path.join(FIXTURE_ROOT, set));
}
/** The set's manifest.json (loose, always), or {} when the set directory does not exist. */
function readManifest(set) {
    const p = path.join(FIXTURE_ROOT, set, 'manifest.json');
    if (!fs.existsSync(p)) return {};
    return JSON.parse(fs.readFileSync(p, 'utf8')); // malformed manifest = loud failure, by design
}
/**
 * Manifest-declared `recordingGaps`, validated and normalized to Map<chapter (1-based), fromChunk (0-based)>.
 * Several gaps for one chapter collapse to the smallest fromChunk (everything after the first lost
 * chunk cascades anyway). Anything not of the shape {chapter: int >= 1, fromChunk: int >= 0} fails the
 * spec — a typo here would silently exempt nothing or everything.
 */
function recordingGaps(set) {
    const raw = readManifest(set).recordingGaps;
    const gaps = new Map();
    if (raw === undefined) return gaps;
    if (!Array.isArray(raw)) throw new Error(set + '/manifest.json recordingGaps must be an array, got ' + JSON.stringify(raw));
    raw.forEach((g, i) => {
        const ok = g && Number.isInteger(g.chapter) && g.chapter >= 1 && Number.isInteger(g.fromChunk) && g.fromChunk >= 0;
        if (!ok) throw new Error(set + '/manifest.json recordingGaps[' + i + '] must be {chapter: int>=1, fromChunk: int>=0}, got ' + JSON.stringify(g));
        gaps.set(g.chapter, gaps.has(g.chapter) ? Math.min(gaps.get(g.chapter), g.fromChunk) : g.fromChunk);
    });
    return gaps;
}
// Replay mode: the main set carries real recorded completions, so the emulator answers from the cache
// (HIT) and only falls back to the synthesizer for a request whose key no fixture matches. Synth mode:
// no recordings, every answer is synthesized — the chapter-parity proof below relies on that.
const REPLAY = realFixtureFiles(MAIN_SET).length > 0;
const FAULT_SET = 'harlots-eight-faults';
const UNREACH_SET = 'harlots-eight-unreachable';     // two consecutive emulated ConnectExceptions
const MAIN_CONFIG_NAME = 'contentAnalysis';          // the wizard's default
const FAULT_CONFIG_NAME = 'contentAnalysisFaults';   // picked through the Chat Config picker
const UNREACH_CONFIG_NAME = 'contentAnalysisUnreachable';

const MAX_CHAPTERS = parseInt(process.env.PB_UX_MAX_CHAPTERS || '10', 10); // 0 = whole manuscript
const FAULT_CHAPTERS = parseInt(process.env.PB_EMU_FAULT_CHAPTERS || '2', 10);
const PER_CHAPTER_MIN = parseInt(process.env.PB_UX_PER_CHAPTER_MIN || '8', 10);
const CONTAINER = process.env.PB_EMU_CONTAINER || 'am7test-am7-1';

function b64(str) { return Buffer.from(str).toString('base64'); }
function fmtMin(ms) { return (ms / 60000).toFixed(1) + ' min'; }

// ─────────────────────────────────────────────────────────────────────────────────────────────
// REST helpers (all as the dedicated non-admin user)
// ─────────────────────────────────────────────────────────────────────────────────────────────

async function restLogin(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: ORG_PATH, name: EMU_USER,
            credential: b64(EMU_PASSWORD), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, EMU_USER + ' login failed: ' + resp.status()).toBe(true);
}

async function searchAll(request, type, fields, requestFields, recordCount) {
    const resp = await request.post(REST + '/model/search', {
        data: { schema: 'io.query', type, cache: false, fields, request: requestFields, recordCount: recordCount || 50 }
    });
    if (!resp.ok()) return [];
    const text = await resp.text();
    if (!text || !text.trim()) return []; // a no-match search can come back 200 with an empty body
    const body = JSON.parse(text);
    return Array.isArray(body) ? body : (body && body.results) || [];
}

async function searchOne(request, type, fields, requestFields) {
    const list = await searchAll(request, type, fields, requestFields, 5);
    return list.length ? list[0] : null;
}

async function getJson(request, url) {
    const resp = await request.get(url, { headers: { Accept: 'application/json' } });
    expect(resp.ok(), 'GET ' + url + ' -> ' + resp.status()).toBe(true);
    return resp.json();
}

async function emulatorStats(request, reset) {
    const resp = await request.get(REST + '/chat/emulator/stats' + (reset ? '?reset=true' : ''),
        { headers: { Accept: 'application/json' } });
    const text = await resp.text();
    expect(resp.ok(), 'GET /chat/emulator/stats -> ' + resp.status() + ' ' + text
        + ' (the LLM emulator is not configured on this deployment: llm.emulator.fixtureRoot unset?)').toBe(true);
    const stats = JSON.parse(text);
    expect(stats.configured, 'emulator stats.configured').toBe(true);
    return stats;
}

/**
 * Lines from the container log since `sinceIso` that match any of `patterns`. Evidence only — if
 * docker is not on PATH this returns a single explanatory line rather than failing the test.
 */
function dockerLogLines(sinceIso, patterns) {
    let raw = '';
    try {
        raw = execSync('docker logs --since ' + sinceIso + ' ' + CONTAINER + ' 2>&1',
            { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
        return ['<docker logs unavailable: ' + ((e && e.message) || e).toString().split('\n')[0] + '>'];
    }
    return raw.split(/\r?\n/).filter(l => patterns.some(p => l.includes(p)));
}

/**
 * Per-kind count of how the emulator answered each request in `set` since `sinceIso`, from the
 * `LLM emulator HIT: set=<set> kind=<kind> key=...` / `LLM emulator SYNTH: ...` log lines.
 * Result shape: { "extract-chunk": { hit: 12, synth: 0 }, "reduce-character": {...}, ... }.
 */
function emulatorKindTally(sinceIso, set) {
    const tally = {};
    const re = /LLM emulator (HIT|SYNTH): set=(\S+) kind=(\S+)/;
    for (const line of dockerLogLines(sinceIso, ['LLM emulator HIT:', 'LLM emulator SYNTH:'])) {
        const m = re.exec(line);
        if (!m || m[2] !== set) continue;
        const k = tally[m[3]] || (tally[m[3]] = { hit: 0, synth: 0 });
        k[m[1] === 'HIT' ? 'hit' : 'synth']++;
    }
    return tally;
}

/**
 * Every scene title that appears in any recorded completion of `set` (the "title" string values in each
 * fixture's response.content, which is the model's raw JSON). Used in replay mode to prove that the
 * persisted scenes came out of the cache and not the synthesizer.
 */
function recordedSceneTitles(set) {
    const titles = new Set();
    const titleRe = /"title"\s*:\s*"((?:[^"\\]|\\.)*)"/g;
    const setDir = path.join(FIXTURE_ROOT, set);
    for (const f of realFixtureFiles(set)) {
        let content = '';
        const text = readFixture(setDir, f, 'utf8'); // loose drop first, else the fixtures.zip entry; a CRC/inflate failure THROWS
        try {
            const fx = JSON.parse(text);
            content = String((fx.response && fx.response.content) || '');
        } catch (_) { continue; }
        let m;
        while ((m = titleRe.exec(content)) !== null) {
            let t = m[1];
            try { t = JSON.parse('"' + m[1] + '"'); } catch (_) { /* keep the raw escaped form */ }
            titles.add(String(t).trim());
        }
    }
    return titles;
}

/**
 * Group-scoped find-or-create of THIS user's ~/Chat/<configName> chatConfig, pointing at the emulator set.
 * Deliberately NOT helpers/api.js ensureChatConfig(): that one finds a config org-wide by name and would
 * happily return (and mutate) e2etest_shared's live-LLM contentAnalysis. Here the lookup is pinned to the
 * dedicated user's own ~/Chat groupId, so nothing outside this user's home is ever read or written.
 *
 * If an existing config's connection does not point at the requested emulator set (dialect/serverUrl
 * differ), the stale config is deleted and recreated — it is this spec's own record under this spec's
 * own user, so self-healing is safe. The connection itself is left in place (chat sessions may reference
 * it); a fresh, distinctly-named connection is created alongside.
 */
async function ensureEmulatorChatConfig(request, opts) {
    const configName = opts.configName;
    const set = opts.set;
    const serverUrl = 'emulator://' + set;
    const chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
    expect(chatDir && chatDir.id, 'could not ensure ~/Chat for ' + EMU_USER + ': ' + JSON.stringify(chatDir)).toBeTruthy();
    const orgId = chatDir.organizationId;
    expect(typeof orgId, '~/Chat returned no numeric organizationId').toBe('number');

    const existing = await searchOne(request, 'olio.llm.chatConfig', [
        { name: 'name', comparator: 'EQUALS', value: configName },
        { name: 'groupId', comparator: 'EQUALS', value: chatDir.id },
        { name: 'organizationId', comparator: 'EQUALS', value: orgId }
    ], ['id', 'objectId', 'name', 'model', 'analyzeModel', 'connection']);

    if (existing) {
        let conn = null;
        if (existing.connection && existing.connection.id) {
            conn = await searchOne(request, 'system.connection', [
                { name: 'id', comparator: 'EQUALS', value: existing.connection.id },
                { name: 'organizationId', comparator: 'EQUALS', value: orgId }
            ], ['id', 'objectId', 'name', 'serverUrl', 'dialect']);
        }
        const dialectOk = conn && String(conn.dialect || '').toLowerCase() === 'emulator';
        const urlOk = conn && conn.serverUrl === serverUrl;
        if (dialectOk && urlOk) {
            console.log('[pb-emu] reusing ' + EMU_USER + ' ~/Chat/' + configName + ' (id=' + existing.id
                + ') -> ' + conn.serverUrl + ' dialect=' + conn.dialect);
            if (existing.model !== EMU_MODEL || existing.analyzeModel !== EMU_MODEL) {
                const patch = await request.patch(REST + '/model', {
                    data: { schema: 'olio.llm.chatConfig', id: existing.id, objectId: existing.objectId,
                        name: existing.name, model: EMU_MODEL, analyzeModel: EMU_MODEL }
                });
                expect(patch.ok(), 'chatConfig model patch failed: ' + patch.status()).toBe(true);
            }
            return { orgId, chatDir, chatConfigObjectId: existing.objectId, connectionObjectId: conn.objectId };
        }
        console.log('[pb-emu] ' + configName + ' exists but its connection is ' + JSON.stringify(conn)
            + ' (want ' + serverUrl + ' dialect=emulator) — deleting the stale chatConfig and recreating');
        const del = await request.delete(REST + '/model/olio.llm.chatConfig/' + existing.objectId);
        expect(del.ok(), 'could not delete stale chatConfig ' + configName + ' (' + del.status()
            + '); delete it by hand as ' + EMU_USER + ' and re-run').toBe(true);
    }

    const connResp = await request.post(REST + '/model', {
        data: {
            schema: 'system.connection',
            name: configName + ' Emulator Connection ' + Date.now().toString(36),
            groupId: chatDir.id,
            groupPath: chatDir.path,
            serverUrl,
            dialect: 'emulator',
            requestTimeout: 120
        }
    });
    const connBody = await connResp.text();
    expect(connResp.ok(), 'system.connection create failed (' + connResp.status() + '): ' + connBody).toBe(true);
    const conn = JSON.parse(connBody);
    expect(conn && conn.objectId, 'connection create returned no objectId').toBeTruthy();

    // Read the dialect back: an enum the schema does not know would be silently dropped, and then the
    // config would be talking to a non-existent Ollama at "emulator://..." rather than the emulator.
    const connRead = await searchOne(request, 'system.connection', [
        { name: 'id', comparator: 'EQUALS', value: conn.id },
        { name: 'organizationId', comparator: 'EQUALS', value: orgId }
    ], ['id', 'objectId', 'serverUrl', 'dialect']);
    expect(connRead && String(connRead.dialect || '').toLowerCase(), 'persisted connection.dialect').toBe('emulator');
    expect(connRead.serverUrl, 'persisted connection.serverUrl').toBe(serverUrl);

    const cfgResp = await request.post(REST + '/model', {
        data: {
            schema: 'olio.llm.chatConfig',
            name: configName,
            groupId: chatDir.id,
            groupPath: chatDir.path,
            model: EMU_MODEL,
            analyzeModel: EMU_MODEL,
            serviceType: 'ollama',   // fallback only — the connection's dialect=emulator wins in resolveServiceType
            stream: false,
            connection: { schema: 'system.connection', id: conn.id, objectId: conn.objectId }
        }
    });
    const cfgBody = await cfgResp.text();
    expect(cfgResp.ok(), 'chatConfig create failed (' + cfgResp.status() + '): ' + cfgBody).toBe(true);
    const cfg = JSON.parse(cfgBody);
    // ChatUtil caches chatConfig lookups; a stale negative/positive entry must not outlive this create.
    await request.post(REST + '/chat/clear').catch(() => {});
    console.log('[pb-emu] created ' + EMU_USER + ' ~/Chat/' + configName + ' -> ' + serverUrl + ' model=' + EMU_MODEL);
    return { orgId, chatDir, chatConfigObjectId: cfg.objectId, connectionObjectId: conn.objectId };
}

async function uploadManuscript(request, name) {
    const dir = await ensurePath(request, 'auth.group', 'data', '~/Manuscripts');
    expect(dir && dir.id, 'could not ensure ~/Manuscripts').toBeTruthy();
    expect(fs.existsSync(DOCX_PATH), 'manuscript not found at ' + DOCX_PATH
        + ' (Objects7/media/HarlotsEight_Vol1_SM.docx is not tracked in git; set PB_UX_DOCX to another .docx)').toBe(true);
    const bytes = fs.readFileSync(DOCX_PATH);
    const resp = await request.post(REST + '/model', {
        data: { schema: 'data.data', name, groupId: dir.id, groupPath: dir.path, contentType: DOCX_MIME,
            dataBytesStore: bytes.toString('base64') }
    });
    expect(resp.ok(), 'manuscript upload failed: ' + resp.status()).toBe(true);
    const rec = await resp.json();
    expect(rec && rec.objectId, 'manuscript create returned no objectId').toBeTruthy();
    return rec.objectId;
}

/**
 * olio.pb.book.sourceRange for a chapter book, read back through /rest/model/search as the test user.
 * RecordSerializer omits INT fields equal to their schema default (RecordSerializer.java:238-242, "skip
 * when equals default" compaction), so chapter 1's persisted startOffset=0 arrives as a MISSING key.
 * A missing int is therefore read as the schema default 0 — the same rule every Ux client applies.
 */
async function readSourceRange(request, bookObjectId, orgId) {
    const book = await searchOne(request, 'olio.pb.book', [
        { name: 'objectId', comparator: 'EQUALS', value: bookObjectId },
        { name: 'organizationId', comparator: 'EQUALS', value: orgId }
    ], ['id', 'objectId', 'slug', 'chapter', 'sourceRange']);
    if (!book) return { error: 'book not readable via /rest/model/search' };
    const sr = book.sourceRange;
    if (!sr) return { error: 'book.sourceRange is null', book };
    const norm = (r) => ({ startOffset: r.startOffset == null ? 0 : r.startOffset,
        endOffset: r.endOffset == null ? 0 : r.endOffset, title: r.title, wireKeys: Object.keys(r) });
    if (sr.endOffset != null || sr.title != null) return norm(sr);
    if (!sr.id) return { error: 'book.sourceRange carries no id: ' + JSON.stringify(sr), book };
    const full = await searchOne(request, 'olio.pb.sourceRange', [
        { name: 'id', comparator: 'EQUALS', value: sr.id },
        { name: 'organizationId', comparator: 'EQUALS', value: orgId }
    ], ['id', 'objectId', 'startOffset', 'endOffset', 'title']);
    if (!full) return { error: 'olio.pb.sourceRange id=' + sr.id + ' not readable', book };
    return norm(full);
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Browser driving
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Wire page-level evidence capture: uncaught errors, failed REST reads, extract-scenes job payloads
 * (so chunk totals / failedExtractions per chapter are visible in the report).
 */
function wireCapture(page, cap) {
    page.on('pageerror', (err) => {
        cap.pageErrors.push(String(err && err.stack || err));
        console.log('[PAGE-ERROR] ' + (err && err.message));
    });
    page.on('console', (msg) => {
        if (msg.type() === 'error') cap.consoleErrors.push(msg.text());
    });
    page.on('response', async (resp) => {
        const u = resp.url();
        const method = resp.request().method();
        if (u.includes('/olio/picture-book/') && resp.status() >= 400) {
            let body = '';
            try { body = (await resp.text()).substring(0, 500); } catch (_) { body = '<unreadable>'; }
            cap.pbErrors.push(method + ' ' + resp.status() + ' ' + u + ' ' + body);
            console.log('[NETWORK ' + resp.status() + '] ' + method + ' ' + u + ' ' + body);
        }
        if (u.includes('/rest/model/') && method === 'GET' && resp.status() >= 400) {
            cap.failedReads.push(resp.status() + ' ' + u);
        }
        try {
            if (method === 'POST' && u.includes('/extract-scenes-only') && resp.ok()) {
                const j = await resp.json();
                if (j && j.jobId) cap.jobs.push({ jobId: j.jobId, url: u });
            } else if (method === 'GET' && /\/rest\/job\/[^/?]+$/.test(u) && resp.ok()) {
                const j = await resp.json();
                if (j && j.jobId) cap.jobPayloads.set(j.jobId, j);
            }
        } catch (_) { /* non-JSON or already consumed */ }
    });
}

function newCapture() {
    return { pageErrors: [], consoleErrors: [], failedReads: [], pbErrors: [], jobs: [], jobPayloads: new Map() };
}

async function installChapterTruncation(page, maxChapters) {
    if (!(maxChapters > 0)) return;
    await page.route('**/chapter/detect-boundaries*', async (route) => {
        const resp = await route.fetch();
        let json = await resp.json();
        if (Array.isArray(json) && json.length > maxChapters) json = json.slice(0, maxChapters);
        await route.fulfill({ response: resp, json });
    });
}

/** Open the wizard on `docObjectId`, verify Step 1 is seeded from the real document, return the dialog. */
async function openWizard(page, docObjectId, docFileName, expectedConfigName) {
    await page.goto('/#!/picture-book/' + docObjectId);
    const generateBtn = page.locator('button:has-text("Generate Picture Book")');
    await expect(generateBtn).toBeVisible({ timeout: 30000 });
    await generateBtn.click();
    await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: 10000 });
    const dialog = page.locator('[role="dialog"]').first();
    await expect(dialog).toContainText('Source: ' + docFileName, { timeout: 10000 });
    await expect(dialog).not.toContainText('Loading...');
    const cfgField = dialog.locator('label.field-label:has-text("Chat Config") + div');
    await expect(cfgField).toContainText(expectedConfigName, { timeout: 20000 });
    return dialog;
}

/** Pick a differently-named chatConfig for the run through the real ObjectPicker (Step 1 "Chat Config"). */
async function pickChatConfig(page, dialog, configName) {
    const cfgField = dialog.locator('label.field-label:has-text("Chat Config") + div');
    await cfgField.click();
    const overlay = page.locator('.am7-picker-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });
    await expect(overlay.locator('h3')).toHaveText('Select Chat Config');
    const row = overlay.locator('tr.tabular-row', { hasText: configName });
    await expect(row, 'picker lists ' + EMU_USER + ' ~/Chat/' + configName).toBeVisible({ timeout: 20000 });
    await row.click();
    await expect(row).toHaveClass(/tabular-row-active/);
    // Toolbar confirm: pagination.button('button','check',...) -> <button class="button"><span class="material-symbols-outlined">check</span></button>
    await overlay.locator('button:has(span:text-is("check"))').first().click();
    await expect(overlay).toHaveCount(0, { timeout: 10000 });
    await expect(cfgField).toContainText(configName);
}

// The wizard's progress label (pictureBook.js onExtractProgress): "Chapter i/N — Extracting scenes cur/K".
// K is the chapter's chunk count (the job's `total`, set by the chunked extraction path). Tolerates
// em dash / en dash / hyphen between the two halves.
const PROGRESS_RE = /Chapter\s+(\d+)\s*\/\s*(\d+)\s*[—–-]\s*Extracting scenes\s+(\d+)\s*\/\s*(\d+)/;

/**
 * Click Extract and wait for the fan-out to end. Returns
 *   { outcome: 'landed' | 'summary' | 'error', url, elapsedMs, headline, problems, errorText, chunkTotals }.
 * 'landed' = clean run (wizard closed, workflow route). 'summary' = wizard stayed open on the persistent
 * per-chapter report. 'error' = extractError rendered. Throws if the dialog vanished without navigating
 * (the reported "script error and disappears" symptom) or the budget ran out.
 *
 * `chunkTotals` is { [chapter]: K } for every chapter whose "Extracting scenes cur/K" label this 2-second
 * poll happened to observe. It is EVIDENCE, not the authority: in emulator mode a whole chapter can finish
 * between two polls, so chapters may be missing from it. The stats block cross-checks it against the
 * captured job payloads (the same `total` the label renders) and fails on any disagreement.
 */
async function runExtract(page, budgetMs, tag) {
    const extractBtn = page.locator('button:has-text("Extract")').first();
    await expect(extractBtn).toBeEnabled({ timeout: 5000 });
    const startedAt = Date.now();
    await extractBtn.click();

    const errorRe = /Extraction stopped at chapter|No chapters were saved|Extraction was cancelled|No scenes were extracted from any chapter|Extraction failed/;
    let lastProgress = '';
    const chunkTotals = {};
    for (;;) {
        if (/\/picture-book\/[^/]+\/workflow/.test(page.url())) {
            return { outcome: 'landed', url: page.url(), elapsedMs: Date.now() - startedAt, problems: [], chunkTotals };
        }
        const dialog = page.locator('[role="dialog"]').first();
        const dialogVisible = await dialog.isVisible().catch(() => false);
        if (dialogVisible) {
            const summary = page.locator('[data-pb-chapter-summary]');
            if (await summary.isVisible().catch(() => false)) {
                const headline = (await page.locator('[data-pb-chapter-summary-headline]').textContent({ timeout: 3000 }).catch(() => '')) || '';
                const problems = await page.locator('[data-pb-chapter-problem]').allTextContents().catch(() => []);
                await screenshot(page, 'pb-emu-' + tag + '-summary');
                return { outcome: 'summary', url: page.url(), elapsedMs: Date.now() - startedAt,
                    headline: headline.trim(), problems: problems.map(p => p.trim()), chunkTotals };
            }
            // Bounded: the dialog can close (clean landing) between isVisible() and textContent(), and an
            // unbounded textContent() would then wait forever for an element that is never coming back.
            const txt = (await dialog.textContent({ timeout: 3000 }).catch(() => '')) || '';
            if (!txt && /\/picture-book\/[^/]+\/workflow/.test(page.url())) continue;
            const m = txt.match(errorRe);
            if (m) {
                await screenshot(page, 'pb-emu-' + tag + '-error');
                return { outcome: 'error', url: page.url(), elapsedMs: Date.now() - startedAt, problems: [],
                    errorText: txt.substring(txt.indexOf(m[0]), txt.indexOf(m[0]) + 800), chunkTotals };
            }
            const prog = (txt.match(/Chapter\s+\d+\s*\/\s*\d+[^.]{0,80}/) || [''])[0].trim();
            if (prog && prog !== lastProgress) {
                lastProgress = prog;
                console.log('[pb-emu:' + tag + '] +' + fmtMin(Date.now() - startedAt) + ' ' + prog);
            }
            const pm = PROGRESS_RE.exec(txt);
            if (pm) {
                const chapter = parseInt(pm[1], 10), k = parseInt(pm[4], 10);
                if (chunkTotals[chapter] !== undefined && chunkTotals[chapter] !== k) {
                    throw new Error('[' + tag + '] progress label changed chapter ' + chapter + "'s chunk count from "
                        + chunkTotals[chapter] + ' to ' + k + ' mid-run: ' + JSON.stringify(txt.substring(0, 300)));
                }
                chunkTotals[chapter] = k;
            }
        } else if (Date.now() - startedAt > 15000) {
            await screenshot(page, 'pb-emu-' + tag + '-dialog-vanished');
            throw new Error('[' + tag + '] wizard dialog disappeared without navigating to the workflow route; url=' + page.url());
        }
        if (Date.now() - startedAt > budgetMs) {
            await screenshot(page, 'pb-emu-' + tag + '-timeout');
            throw new Error('[' + tag + '] extraction did not finish within ' + fmtMin(budgetMs) + '; last progress: ' + lastProgress);
        }
        await page.waitForTimeout(2000);
    }
}

/** Per-chapter job summary from the captured async-job payloads (chunk totals, failed passages). */
function summarizeJobs(cap) {
    return cap.jobs.map((j, i) => {
        const p = cap.jobPayloads.get(j.jobId) || {};
        const r = p.result || {};
        return { n: i + 1, status: p.status, chunks: p.total, done: p.current,
            complete: r.extractionComplete, scenes: Array.isArray(r.sceneList) ? r.sceneList.length : undefined,
            failed: Array.isArray(r.failedExtractions) ? r.failedExtractions.length : 0 };
    });
}

// ─────────────────────────────────────────────────────────────────────────────────────────────

test.describe('PictureBook chaptered manuscript — emulated LLM, real Ux + Docker stack', () => {
    test.describe.configure({ mode: 'serial', retries: 0 });

    let orgId = null;
    // Set by the first test; consumed by the re-run test at the end of the serial block.
    let rerun = null;

    test.beforeAll(async ({ request }) => {
        test.setTimeout(240000);
        await ensureSharedTestUser(request, { name: EMU_USER, password: EMU_PASSWORD });
        await restLogin(request);
        ({ orgId } = await ensureEmulatorChatConfig(request, { configName: MAIN_CONFIG_NAME, set: MAIN_SET }));
    });

    test('N chapters through the wizard: parity, bundling, reader, cast, re-run reuse, emulator stats',
        async ({ page, request }) => {
        const cap = newCapture();
        wireCapture(page, cap);
        const runStartedIso = new Date().toISOString();

        // ── Upload + untruncated detect-boundaries (assertion 1) ─────────────────────────────
        await restLogin(request);
        const docName = 'HarlotsEight-EMU-' + Date.now().toString(36);
        const docFileName = docName + '.docx';
        const docObjectId = await uploadManuscript(request, docFileName);
        const ranges = await getJson(request, PB_REST + '/chapter/detect-boundaries?sourceDataObjectId='
            + encodeURIComponent(docObjectId));
        expect(Array.isArray(ranges), 'detect-boundaries returns an array').toBe(true);
        console.log('[pb-emu] detect-boundaries: ' + ranges.length + ' ranges; first="' + (ranges[0] && ranges[0].title)
            + '" last="' + (ranges[ranges.length - 1] && ranges[ranges.length - 1].title) + '"');
        console.log('[pb-emu] ranges: ' + JSON.stringify(ranges.map(r => [r.title, r.startOffset, r.endOffset])));
        const nullTitles = ranges.map((r, i) => r.title == null ? i + 1 : null).filter(x => x != null);
        expect(nullTitles, 'ranges with null title (1-based positions)').toEqual([]);
        if (IS_HARLOTS) {
            expect(ranges.length, 'HarlotsEight_Vol1_SM.docx has 21 chapters').toBe(HARLOTS_CHAPTERS);
            const expectedTitles = Array.from({ length: HARLOTS_CHAPTERS }, (_, i) => 'Chapter ' + (i + 1));
            expect(ranges.map(r => r.title), 'chapter titles in order').toEqual(expectedTitles);
        }
        expect(new Set(ranges.map(r => r.title)).size, 'chapter titles unique').toBe(ranges.length);
        expect(ranges[0].startOffset, 'first range starts at offset 0 (short lead folded into chapter 1)').toBe(0);
        const gaps = [];
        for (let i = 0; i < ranges.length; i++) {
            const r = ranges[i];
            expect(typeof r.startOffset === 'number' && typeof r.endOffset === 'number',
                r.title + ': numeric offsets').toBe(true);
            expect(r.endOffset, r.title + ': endOffset > startOffset').toBeGreaterThan(r.startOffset);
            if (i > 0) {
                expect(r.startOffset, r.title + ': startOffset strictly increasing over ' + ranges[i - 1].title)
                    .toBeGreaterThan(ranges[i - 1].startOffset);
                if (ranges[i - 1].endOffset !== r.startOffset) {
                    gaps.push(ranges[i - 1].title + '.end=' + ranges[i - 1].endOffset + ' vs ' + r.title + '.start=' + r.startOffset);
                }
            }
        }
        // PbChapterBoundaryUtil's contract is a contiguous partition of [0, text.length()).
        expect(gaps, 'non-contiguous chapter ranges (end[i] != start[i+1])').toEqual([]);

        const N = (MAX_CHAPTERS > 0 && MAX_CHAPTERS < ranges.length) ? MAX_CHAPTERS : ranges.length;
        const handed = ranges.slice(0, N);
        console.log('[pb-emu] wizard will be handed ' + N + ' of ' + ranges.length + ' chapters; per-chapter chars: '
            + JSON.stringify(handed.map(r => r.endOffset - r.startOffset)));

        // ── Emulator: reset counters, confirm configured ────────────────────────────────────
        const stats0 = await emulatorStats(request, true);
        console.log('[pb-emu] emulator stats after reset: ' + JSON.stringify(stats0));

        // ── Wizard run #1 (assertion 2) ───────────────────────────────────────────────────────
        const budgetMs = (N * PER_CHAPTER_MIN + 10) * 60 * 1000;
        test.setTimeout(2 * budgetMs + 30 * 60 * 1000);
        await installChapterTruncation(page, N < ranges.length ? N : 0);
        await login(page, { org: ORG_PATH, user: EMU_USER, password: EMU_PASSWORD });
        const dialog = await openWizard(page, docObjectId, docFileName, MAIN_CONFIG_NAME);
        await screenshot(page, 'pb-emu-step1');
        const run1 = await runExtract(page, budgetMs, 'run1');
        console.log('[pb-emu] run1: ' + JSON.stringify({ ...run1, jobs: summarizeJobs(cap) }));
        if (run1.outcome !== 'landed') {
            throw new Error('run1 did not complete cleanly: ' + run1.outcome + ' :: ' + (run1.headline || '')
                + ' :: ' + (run1.problems || []).join(' | ') + (run1.errorText || '')
                + ' :: PB errors=' + JSON.stringify(cap.pbErrors.slice(0, 5)));
        }
        expect(await page.locator('[data-pb-chapter-problem]').count(), 'no chapter problems rendered').toBe(0);
        await expect(page.locator('[data-view-mode-toggle]')).toBeVisible({ timeout: 30000 });
        await screenshot(page, 'pb-emu-workflow-landing');

        // ── REST truth (assertion 3) ─────────────────────────────────────────────────────────
        await restLogin(request);
        const allBooks1 = await getJson(request, PB_REST + '/books');
        const mine1 = allBooks1.filter(b => b.seriesName === docFileName);
        expect(mine1.length, 'chapter books in series "' + docFileName + '" (series seen: '
            + JSON.stringify([...new Set(allBooks1.map(b => b.seriesName))]) + ')').toBe(N);
        const seriesOids = [...new Set(mine1.map(b => b.seriesObjectId))];
        expect(seriesOids.length, 'exactly one series for this run: ' + JSON.stringify(seriesOids)).toBe(1);
        const seriesOid = seriesOids[0];
        const seriesBooks1 = await getJson(request, PB_REST + '/series/' + seriesOid + '/books');
        expect(seriesBooks1.length, '/series/{oid}/books count').toBe(N);
        const byChapter = new Map(seriesBooks1.map(b => [Number(b.chapter), b]));
        expect([...byChapter.keys()].sort((a, b) => a - b), 'chapter ordinals 1..N')
            .toEqual(Array.from({ length: N }, (_, i) => i + 1));
        // The landing route must be the FIRST chapter's workflow.
        expect(run1.url, 'landed on chapter 1 workflow').toContain('/picture-book/' + byChapter.get(1).objectId + '/workflow');

        const titleByOid = new Map(mine1.map(b => [b.objectId, b.title]));
        const rangeMismatches = [];
        const persistedRanges = [];
        for (let n = 1; n <= N; n++) {
            const b = byChapter.get(n);
            const want = handed[n - 1];
            expect(titleByOid.get(b.objectId), 'chapter ' + n + ' book title == detected heading').toBe(want.title);
            const sr = await readSourceRange(request, b.objectId, orgId);
            persistedRanges.push(sr.error ? [n, sr.error] : [n, sr.startOffset, sr.endOffset, sr.title]);
            if (sr.error) {
                rangeMismatches.push('chapter ' + n + ': ' + sr.error);
            } else if (sr.startOffset !== want.startOffset || sr.endOffset !== want.endOffset || sr.title !== want.title) {
                rangeMismatches.push('chapter ' + n + ': persisted [' + sr.startOffset + ',' + sr.endOffset + ') title="' + sr.title
                    + '" != detected [' + want.startOffset + ',' + want.endOffset + ') "' + want.title + '" (wire keys ' + JSON.stringify(sr.wireKeys) + ')');
            }
        }
        console.log('[pb-emu] persisted sourceRanges [chapter,start,end,title]: ' + JSON.stringify(persistedRanges));
        expect(rangeMismatches, 'persisted sourceRange == detected range, per chapter').toEqual([]);

        // ── Chapter parity (assertion 4) + scene counts ──────────────────────────────────────
        // Synth mode: the synthesizer's first scene title begins with the chunk's first non-blank line,
        // so "chapter N's first scene starts with 'Chapter N'" proves chapter N's own text reached the
        // prompt. Replay mode: titles are the real model's, so the proof is instead that EVERY persisted
        // scene title came out of a recorded completion (a synthesized title never can — it starts with
        // a chapter heading, which no recorded response does). A scene the cache did not produce means a
        // request whose key no fixture matched, i.e. the pipeline built a request the recording run
        // never sent.
        const recordedTitles = REPLAY ? recordedSceneTitles(MAIN_SET) : null;
        if (REPLAY) console.log('[pb-emu] replay mode: ' + realFixtureFiles(MAIN_SET).length + ' recorded fixtures, '
            + recordedTitles.size + ' distinct recorded scene titles');
        // Manifest-declared recording gaps (see the header). Only gaps for chapters this run actually
        // processed (1..N) matter; a gap declared for chapter 15 is inert under PB_UX_MAX_CHAPTERS=10.
        const gapsDeclared = recordingGaps(MAIN_SET);
        const gapsProcessed = new Map([...gapsDeclared].filter(([ch]) => ch >= 1 && ch <= N));
        console.log('[pb-emu] recordingGaps declared=' + JSON.stringify([...gapsDeclared]) + ' processed=' + JSON.stringify([...gapsProcessed]));
        const sceneCounts1 = {};
        const firstTitles = {};
        const parityFailures = [];
        const crossChapter = [];
        const unrecorded = [];
        const badSourceChunk = [];
        const exemptCounts = {};
        const maxSourceChunk = {}; // per chapter, for the weaker K fallback in the stats block
        const allHeadings = ranges.map(r => r.title);
        for (let n = 1; n <= N; n++) {
            const b = byChapter.get(n);
            const scenes = await getJson(request, PB_REST + '/' + b.objectId + '/scenes');
            const list = Array.isArray(scenes) ? scenes : (scenes && scenes.sceneList) || [];
            sceneCounts1[n] = list.length;
            expect(['failed', 'unknown'], 'chapter ' + n + ' status ' + b.bookStatus).not.toContain(String(b.bookStatus).toLowerCase());
            expect(list.length, 'chapter ' + n + ' (' + handed[n - 1].title + ') has scenes').toBeGreaterThan(0);
            // Every persisted scene must say which 0-based extraction chunk added it (PictureBookUtil
            // .mergeChunkResult stamps it; the scene read path must keep it). Holds in both modes.
            for (const s of list) {
                const sc = s.sourceChunk;
                if (!(Number.isInteger(sc) && sc >= 0)) {
                    badSourceChunk.push('chapter ' + n + ' scene "' + String(s.title || '') + '": sourceChunk=' + JSON.stringify(sc));
                } else {
                    maxSourceChunk[n] = Math.max(maxSourceChunk[n] === undefined ? -1 : maxSourceChunk[n], sc);
                }
            }
            const first = String((list[0] && list[0].title) || '');
            firstTitles[n] = first;
            const heading = handed[n - 1].title;
            if (REPLAY) {
                const fromChunk = gapsProcessed.has(n) ? gapsProcessed.get(n) : null;
                for (const s of list) {
                    const t = String(s.title || '').trim();
                    if (recordedTitles.has(t)) continue;
                    // EXEMPT iff the chapter has a declared gap and the scene came from a chunk at/after it.
                    if (fromChunk !== null && Number.isInteger(s.sourceChunk) && s.sourceChunk >= fromChunk) {
                        exemptCounts[n] = (exemptCounts[n] || 0) + 1;
                        continue;
                    }
                    unrecorded.push('chapter ' + n + ': "' + t + '"' + (fromChunk !== null
                        ? ' (sourceChunk ' + JSON.stringify(s.sourceChunk) + ' is before the declared gap at chunk ' + fromChunk + ')' : ''));
                }
            } else {
                const okOwn = first.startsWith(heading);
                const okLead = (n === 1 && IS_HARLOTS && first.startsWith(HARLOTS_LEAD_LINE));
                if (!okOwn && !okLead) {
                    parityFailures.push('chapter ' + n + ': first scene title "' + first + '" does not start with "' + heading + '"'
                        + (n === 1 && IS_HARLOTS ? ' (or the folded lead line "' + HARLOTS_LEAD_LINE + '")' : ''));
                }
            }
            // No scene in chapter n may carry ANOTHER chapter's heading as its title prefix.
            for (const s of list) {
                const t = String(s.title || '');
                for (const h of allHeadings) {
                    if (h === heading) continue;
                    // "Chapter 1" is a prefix of "Chapter 10".. — match the heading as a whole token.
                    if (new RegExp('^' + h.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?!\\d)').test(t)) {
                        crossChapter.push('chapter ' + n + ' scene "' + t + '" begins with ' + h);
                    }
                }
            }
        }
        console.log('[pb-emu] scenes per chapter: ' + JSON.stringify(sceneCounts1));
        console.log('[pb-emu] first scene title per chapter: ' + JSON.stringify(firstTitles));
        console.log('[pb-emu] max sourceChunk per chapter: ' + JSON.stringify(maxSourceChunk));
        if (REPLAY) console.log('[pb-emu] unrecorded scenes exempted by declared recording gaps, per chapter: '
            + JSON.stringify(exemptCounts) + (gapsProcessed.size ? '' : ' (no gap declared for chapters 1..' + N + ')'));
        expect(badSourceChunk, 'scenes without a non-negative integer sourceChunk (0-based extraction chunk that added the scene)').toEqual([]);
        expect(parityFailures, 'first scene of each chapter starts with its own heading').toEqual([]);
        expect(unrecorded, 'scene titles not found in any recorded completion and not covered by a declared recordingGap '
            + '(request key drifted from the recording run)').toEqual([]);
        expect(crossChapter, 'scene titles that begin with a DIFFERENT chapter heading').toEqual([]);

        // ── Emulator stats (assertion 8): configured, no misses, cache hit / synthesis by mode ──
        const stats1 = await emulatorStats(request, false);
        console.log('[pb-emu] emulator stats after run1: ' + JSON.stringify(stats1));
        const missLines = dockerLogLines(runStartedIso, ['LLM emulator MISS', 'LLM emulator: invalid emulator set',
            'resolves outside the fixture root', 'No LLM content for', 'LLM emulator not configured']);
        if (stats1.miss !== 0 || missLines.length) {
            console.log('[pb-emu] docker log evidence:\n' + missLines.join('\n'));
        }
        const kindTally = emulatorKindTally(runStartedIso, MAIN_SET);
        console.log('[pb-emu] emulator answers by kind: ' + JSON.stringify(kindTally));
        expect(stats1.miss, 'emulator fixture misses (docker logs: ' + missLines.slice(0, 5).join(' || ') + ')').toBe(0);
        if (REPLAY) {
            expect(stats1.hit, 'replay mode: recorded completions were served').toBeGreaterThan(0);
        } else {
            expect(stats1.synth, 'synth mode: emulator synthesized at least one response').toBeGreaterThan(0);
        }
        expect(stats1.fault, 'no faults in the ' + MAIN_SET + ' set').toBe(0);

        // ── Pin the recording gap (replay mode) ─────────────────────────────────────────────
        // fanOutChaptersExtract is sequential — one POST /extract-scenes-only job per chapter, awaited
        // before the next — so cap.jobs[i] is chapter i+1's job.
        expect(cap.jobs.length, 'one extract-scenes-only job per chapter handed to the wizard').toBe(N);
        // K (chunk count) per chapter. Authority: the captured job payload's `total`, which is the
        // number the wizard renders as K in "Extracting scenes cur/K" (JobService.describe always
        // emits it; the chunked extraction path sets it to chunks.size()). The DOM observations from
        // runExtract are cross-checked against it — in emulator mode a chapter can finish inside one
        // 2-second poll, so the DOM alone may miss chapters, but where it saw a K it must be the same K.
        const chunkCount = {};
        const kDisagreements = [];
        for (let n = 1; n <= N; n++) {
            const payload = cap.jobPayloads.get(cap.jobs[n - 1].jobId) || {};
            const fromJob = Number.isInteger(payload.total) && payload.total > 0 ? payload.total : null;
            const fromDom = run1.chunkTotals[n];
            if (fromJob !== null && fromDom !== undefined && fromDom !== fromJob) {
                kDisagreements.push('chapter ' + n + ': wizard label K=' + fromDom + ' vs job total=' + fromJob);
            }
            if (fromJob !== null) chunkCount[n] = fromJob;
            else if (fromDom !== undefined) chunkCount[n] = fromDom;
            else if (maxSourceChunk[n] !== undefined) {
                // Weaker fallback: max(sourceChunk)+1 is a LOWER bound on K — trailing chunks that
                // yielded no scenes are invisible to it — so it can only make expectedExtractSynth
                // too small, never too large. Only reached if neither the job payload nor the label
                // exposed a chunk count for this chapter.
                chunkCount[n] = maxSourceChunk[n] + 1;
                console.log('[pb-emu] chapter ' + n + ': chunk count from max(sourceChunk)+1 = ' + chunkCount[n] + ' (weaker: lower bound)');
            }
        }
        console.log('[pb-emu] chunk count per chapter: ' + JSON.stringify(chunkCount) + ' (wizard label saw ' + JSON.stringify(run1.chunkTotals) + ')');
        expect(kDisagreements, 'wizard progress label K vs job payload total').toEqual([]);
        if (REPLAY) {
            const extractTally = kindTally['extract-chunk'] || { hit: 0, synth: 0 };
            const dockerUnavailable = dockerLogLines(runStartedIso, ['<docker logs unavailable']).length > 0;
            if (gapsProcessed.size === 0) {
                // No gap declared for any processed chapter: the cache must have answered EVERYTHING.
                expect(stats1.synth, 'replay mode with no declared recordingGaps in chapters 1..' + N
                    + ': the emulator must synthesize nothing').toBe(0);
            } else {
                // Each gap chapter loses chunks fromChunk..K-1 to the synthesizer (the lost chunk plus
                // every chained chunk after it). Nothing else may be synthesized.
                let expectedExtractSynth = 0;
                const perChapter = {};
                for (const [ch, fromChunk] of gapsProcessed) {
                    expect(chunkCount[ch], 'chunk count K for gap chapter ' + ch + ' could not be determined').toBeGreaterThan(0);
                    expect(fromChunk, 'gap chapter ' + ch + ': fromChunk ' + fromChunk + ' must be < K=' + chunkCount[ch]).toBeLessThan(chunkCount[ch]);
                    perChapter[ch] = chunkCount[ch] - fromChunk;
                    expectedExtractSynth += perChapter[ch];
                }
                console.log('[pb-emu] expected extract-chunk SYNTH from declared gaps: ' + expectedExtractSynth
                    + ' ' + JSON.stringify(perChapter) + '; emulator extract-chunk tally: ' + JSON.stringify(extractTally)
                    + '; reduce-character: ' + JSON.stringify(kindTally['reduce-character'] || { hit: 0, synth: 0 })
                    + '; guess-apparel: ' + JSON.stringify(kindTally['guess-apparel'] || { hit: 0, synth: 0 }));
                // The per-kind tally comes from the container log; without it the gap cannot be pinned,
                // and an unpinned gap is exactly the hole this block exists to close — so fail, do not skip.
                expect(dockerUnavailable, 'docker logs are required to pin the declared recording gap (PB_EMU_CONTAINER=' + CONTAINER + ')').toBe(false);
                expect(extractTally.synth, 'emulator extract-chunk SYNTH count == Σ(K − fromChunk) over processed gap chapters').toBe(expectedExtractSynth);
                // Exempted scenes must actually live in the synthesized region: at least one per gap chapter
                // (a synthesized chunk always yields additions), else the exemption was never exercised.
                for (const ch of gapsProcessed.keys()) {
                    expect(exemptCounts[ch] || 0, 'gap chapter ' + ch + ': scenes exempted by the declared gap').toBeGreaterThan(0);
                }
            }
        }

        // ── Series card bundling (assertion 6a) ─────────────────────────────────────────────
        await page.goto('/#!/picture-book');
        await expect(page.locator('[data-pb2-book-list]')).toBeVisible({ timeout: 30000 });
        const card = page.locator('[data-pb2-series="' + seriesOid + '"]');
        await expect(card).toBeVisible({ timeout: 30000 });
        const header = card.locator('[data-pb2-series-header]');
        const headerText = (await header.textContent()) || '';
        console.log('[pb-emu] series card header: ' + headerText.trim());
        expect(headerText).toContain(N + ' chapter');
        expect(headerText, 'every chapter should have scenes').not.toContain('with scenes');
        for (const b of seriesBooks1) {
            await expect(page.locator('[data-pb2-book-list] [data-pb2-book="' + b.objectId + '"]'),
                'chapter ' + b.chapter + ' listed as an individual book').toHaveCount(0);
        }
        const chaptersBox = card.locator('[data-pb2-series-chapters]');
        await header.click();
        await expect(chaptersBox).toBeVisible({ timeout: 10000 });
        const rows = chaptersBox.locator('[data-pb2-book]');
        await expect(rows).toHaveCount(N, { timeout: 10000 });
        for (const b of seriesBooks1) {
            await expect(chaptersBox.locator('[data-pb2-book="' + b.objectId + '"][data-pb2-chapter="' + b.chapter + '"]')).toHaveCount(1);
        }
        await screenshot(page, 'pb-emu-book-list-bundled');

        // ── Reader chapter select (assertion 6b) ────────────────────────────────────────────
        await rows.first().click();
        await page.waitForURL(/\/picture-book\/v2\//, { timeout: 30000 });
        const select = page.locator('select[data-pb2-chapter-select]');
        await expect(select).toBeVisible({ timeout: 30000 });
        const options = select.locator('option');
        await expect(options).toHaveCount(N, { timeout: 10000 });
        console.log('[pb-emu] chapter select: ' + JSON.stringify(await options.allTextContents()));
        const firstUrl = page.url();
        const secondOid = await options.nth(1).getAttribute('value');
        await select.selectOption(secondOid);
        await page.waitForURL((u) => u.toString().includes(secondOid), { timeout: 30000 });
        expect(page.url()).not.toBe(firstUrl);
        await expect(page.locator('select[data-pb2-chapter-select]')).toHaveValue(secondOid, { timeout: 30000 });
        await screenshot(page, 'pb-emu-reader-chapter2');

        // ── Cast (assertion 7): every chapter has characters; race present, ethnicity absent ─
        const roster = new Map();
        for (let n = 1; n <= N; n++) {
            const b = byChapter.get(n);
            const chars = await getJson(request, PB_REST + '/' + b.objectId + '/characters');
            expect(Array.isArray(chars) && chars.length, 'chapter ' + n + ' /characters non-empty').toBeTruthy();
            for (const c of chars) {
                if (!roster.has(c.objectId)) roster.set(c.objectId, { name: c.name, chapters: [] });
                roster.get(c.objectId).chapters.push(n);
            }
        }
        console.log('[pb-emu] distinct characters across ' + N + ' chapter(s): ' + roster.size);
        const noRace = [], withEth = [], unreadable = [];
        const raceTally = {};
        for (const [oid, info] of roster) {
            const rec = await searchOne(request, 'olio.charPerson', [
                { name: 'objectId', comparator: 'EQUALS', value: oid },
                { name: 'organizationId', comparator: 'EQUALS', value: orgId }
            ], ['id', 'objectId', 'name', 'race', 'ethnicity', 'gender', 'age']);
            if (!rec) { unreadable.push(info.name); continue; }
            const race = Array.isArray(rec.race) ? rec.race : [];
            const eth = Array.isArray(rec.ethnicity) ? rec.ethnicity : [];
            if (!race.length) noRace.push(rec.name);
            if (eth.length) withEth.push(rec.name + ':' + eth.join('+'));
            const key = race.length ? race.join('+') : '(none)';
            raceTally[key] = (raceTally[key] || 0) + 1;
        }
        console.log('[pb-emu] race tally: ' + JSON.stringify(raceTally) + ' noRace=' + noRace.length + ' withEthnicity=' + withEth.length);
        expect(unreadable, 'charPerson records not readable as ' + EMU_USER).toEqual([]);
        expect(withEth, 'charPersons carrying an ethnicity (the manuscript states none; the grounding gate must drop any the model invents)').toEqual([]);
        // Text-faithfulness (PictureBookUtil race/ethnicity grounding gate): whatever race the model (or,
        // in synth mode, the hash-picking synthesizer) proposes, the product KEEPS it only when the passages
        // sent to the model literally contain that label's word — otherwise race is left unset by design.
        // So "race present on every character" is NOT the contract; "only text-stated labels persist" is.
        // Ground truth for HarlotsEight_Vol1_SM.docx (same as the live spec): fairy/fairies, monster,
        // white, black occur; no elf/dwarf/vampire/robot/... and no ethnicity label word.
        if (IS_HARLOTS) {
            const ALLOWED_RACE = { Z: 'Fairy', E: 'White', C: 'Black', M: 'Monster' };
            const offRace = [];
            for (const [code, count] of Object.entries(raceTally)) {
                if (code === '(none)') continue;
                for (const r of code.split('+')) if (!ALLOWED_RACE[r]) offRace.push(r + ' x' + count);
            }
            expect(offRace, 'race labels the manuscript never states').toEqual([]);
            expect(raceTally.Z || 0, 'at least one fairy (the text names them 191 times)').toBeGreaterThan(0);
        } else {
            expect(roster.size - noRace.length, 'at least one character with a text-grounded race').toBeGreaterThan(0);
        }

        // ── No uncaught script error, no failed REST reads (assertion 9) ────────────────────
        if (cap.consoleErrors.length) console.log('[pb-emu] console errors (' + cap.consoleErrors.length + '): '
            + JSON.stringify(cap.consoleErrors.slice(0, 10)));
        expect(cap.failedReads, 'REST GET /rest/model/ reads that failed during the run').toEqual([]);
        expect(cap.pageErrors, 'uncaught page errors during the run').toEqual([]);

        // Hand the run-1 state to the re-run test (last in this serial describe so a re-run
        // defect does not skip the fault-injection test).
        rerun = { docObjectId, docFileName, N, total: ranges.length, budgetMs, seriesOid,
            seriesBooks1, sceneCounts1: { ...sceneCounts1 } };
    });

    test('fault injection (harlots-eight-faults): an emulated HTTP 500 mid-chapter is retried and the chapter still lands',
        async ({ page, request }) => {
        const cap = newCapture();
        wireCapture(page, cap);
        const runStartedIso = new Date().toISOString();

        await restLogin(request);
        await ensureEmulatorChatConfig(request, { configName: FAULT_CONFIG_NAME, set: FAULT_SET });

        const docName = 'HarlotsEight-EMUFAULT-' + Date.now().toString(36);
        const docFileName = docName + '.docx';
        const docObjectId = await uploadManuscript(request, docFileName);
        const ranges = await getJson(request, PB_REST + '/chapter/detect-boundaries?sourceDataObjectId='
            + encodeURIComponent(docObjectId));
        expect(Array.isArray(ranges) && ranges.length >= FAULT_CHAPTERS, 'manuscript detected as chaptered').toBe(true);
        // The fault is "3rd extract-chunk call": only chapters longer than MAX_EXTRACTION_TEXT_CHARS (8000)
        // take the chunked path, and with EXTRACT_CHUNK_SIZE/OVERLAP = 8000/400 a chunked chapter
        // yields at least 1 + ceil((len - 8000) / 7600) chunk calls (break-on-period only shortens
        // chunks, so this is a floor). Hand the wizard enough leading chapters that the chunked
        // ones add up to >= 3 calls, or the fault never fires.
        const CHUNK_SIZE = 8000, CHUNK_OVERLAP = 400;
        const chunkCalls = r => {
            const len = r.endOffset - r.startOffset;
            return len > 8000 ? 1 + Math.ceil((len - CHUNK_SIZE) / (CHUNK_SIZE - CHUNK_OVERLAP)) : 0;
        };
        let N = FAULT_CHAPTERS;
        while (N < ranges.length && ranges.slice(0, N).reduce((a, r) => a + chunkCalls(r), 0) < 3) N++;
        const handed = ranges.slice(0, N);
        console.log('[pb-emu:faults] chapters handed: ' + N + ' chars=' + JSON.stringify(handed.map(r => r.endOffset - r.startOffset)));

        // Reset: zeroes hit/miss/synth/fault AND re-arms the per-set fault occurrence counters.
        const stats0 = await emulatorStats(request, true);
        expect(stats0.fault, 'fault counter after reset').toBe(0);

        const budgetMs = (N * PER_CHAPTER_MIN + 10) * 60 * 1000;
        test.setTimeout(budgetMs + 20 * 60 * 1000);
        await installChapterTruncation(page, N < ranges.length ? N : 0);
        await login(page, { org: ORG_PATH, user: EMU_USER, password: EMU_PASSWORD });
        const dialog = await openWizard(page, docObjectId, docFileName, MAIN_CONFIG_NAME);
        await pickChatConfig(page, dialog, FAULT_CONFIG_NAME);
        await screenshot(page, 'pb-emu-faults-step1');
        const run = await runExtract(page, budgetMs, 'faults');
        const jobs = summarizeJobs(cap);
        console.log('[pb-emu:faults] run: ' + JSON.stringify({ ...run, jobs }));

        const stats1 = await emulatorStats(request, false);
        console.log('[pb-emu:faults] emulator stats: ' + JSON.stringify(stats1));
        const evidence = dockerLogLines(runStartedIso, ['LLM emulator FAULT fired', 'No LLM content for extract-scenes-chunk',
            'LLM emulator MISS', 'LLM emulator: invalid emulator set']);
        console.log('[pb-emu:faults] docker log evidence (' + evidence.length + '):\n' + evidence.slice(0, 12).join('\n'));

        expect(stats1.miss, 'emulator fixture misses').toBe(0);
        expect(stats1.fault, 'the injected fault fired at least once (set ' + FAULT_SET + ' selected via ' + FAULT_CONFIG_NAME + ')')
            .toBeGreaterThanOrEqual(1);
        // A fast HTTP 500 is retried once by the chunk loop and the retry succeeds: the run is CLEAN.
        if (run.outcome !== 'landed') {
            throw new Error('faults run did not complete cleanly: ' + run.outcome + ' :: ' + (run.headline || '')
                + ' :: ' + (run.problems || []).join(' | ') + (run.errorText || ''));
        }

        await restLogin(request);
        const allBooks = await getJson(request, PB_REST + '/books');
        const mine = allBooks.filter(b => b.seriesName === docFileName);
        expect(mine.length, 'chapter books for the faults run').toBe(N);
        const seriesBooks = await getJson(request, PB_REST + '/series/' + mine[0].seriesObjectId + '/books');
        expect(seriesBooks.length).toBe(N);
        const sceneCounts = {};
        for (const b of seriesBooks) {
            const scenes = await getJson(request, PB_REST + '/' + b.objectId + '/scenes');
            const list = Array.isArray(scenes) ? scenes : (scenes && scenes.sceneList) || [];
            sceneCounts[Number(b.chapter)] = list.length;
            expect(list.length, 'chapter ' + b.chapter + ' (' + handed[Number(b.chapter) - 1].title + ') has scenes after the fault').toBeGreaterThan(0);
        }
        console.log('[pb-emu:faults] scenes per chapter: ' + JSON.stringify(sceneCounts));
        // The retried chunk must not have been recorded as a failed passage.
        const failedTotal = jobs.reduce((a, j) => a + (j.failed || 0), 0);
        expect(failedTotal, 'failedExtractions across chapters (retry should absorb the 500)').toBe(0);
        expect(cap.pageErrors, 'uncaught page errors during the run').toEqual([]);
    });

    // Optional third set: two consecutive emulated ConnectExceptions on the first two extract-chunk
    // calls. Same breaker shape as pictureBookUnreachableLlmUx.spec.js (real unroutable host), but
    // in-process and instant, so the "wizard stays open, names the cause, saves nothing false" path is
    // covered without waiting on TCP connect timeouts.
    test('unreachable injection (harlots-eight-unreachable): breaker trips at chapter 1, wizard stays open, no false save',
        async ({ page, request }) => {
        const cap = newCapture();
        wireCapture(page, cap);
        const runStartedIso = new Date().toISOString();

        await restLogin(request);
        await ensureEmulatorChatConfig(request, { configName: UNREACH_CONFIG_NAME, set: UNREACH_SET });

        const docName = 'HarlotsEight-EMUDOWN-' + Date.now().toString(36);
        const docFileName = docName + '.docx';
        const docObjectId = await uploadManuscript(request, docFileName);
        const ranges = await getJson(request, PB_REST + '/chapter/detect-boundaries?sourceDataObjectId='
            + encodeURIComponent(docObjectId));
        const N = Math.min(FAULT_CHAPTERS, ranges.length);
        expect(N, 'at least two chapters so "no later chapter is created" is a real check').toBeGreaterThanOrEqual(2);
        const firstTitle = String(ranges[0].title || '').trim();

        const stats0 = await emulatorStats(request, true);
        expect(stats0.fault, 'fault counter after reset').toBe(0);

        const budgetMs = 6 * 60 * 1000;
        test.setTimeout(budgetMs + 10 * 60 * 1000);
        await installChapterTruncation(page, N < ranges.length ? N : 0);
        await login(page, { org: ORG_PATH, user: EMU_USER, password: EMU_PASSWORD });
        const dialog = await openWizard(page, docObjectId, docFileName, MAIN_CONFIG_NAME);
        await pickChatConfig(page, dialog, UNREACH_CONFIG_NAME);
        await screenshot(page, 'pb-emu-unreach-step1');
        const run = await runExtract(page, budgetMs, 'unreach');
        const jobs = summarizeJobs(cap);
        console.log('[pb-emu:unreach] run: ' + JSON.stringify({ ...run, jobs }));

        const stats1 = await emulatorStats(request, false);
        console.log('[pb-emu:unreach] emulator stats: ' + JSON.stringify(stats1));
        const evidence = dockerLogLines(runStartedIso, ['LLM emulator FAULT fired', 'consecutive chunks could not reach',
            'model server unreachable after', 'LLM emulator MISS']);
        console.log('[pb-emu:unreach] docker log evidence (' + evidence.length + '):\n'
            + evidence.slice(0, 8).map(l => l.slice(0, 300)).join('\n'));

        expect(stats1.miss, 'emulator fixture misses').toBe(0);
        expect(stats1.fault, 'both unreachable faults fired').toBeGreaterThanOrEqual(2);

        // The wizard must STOP and STAY OPEN — not land, not render a partial summary, not vanish.
        expect(run.outcome, 'wizard outcome on an unreachable model server (url=' + run.url + ')').toBe('error');
        const shown = (run.errorText || '').replace(/\s+/g, ' ');
        console.log('[pb-emu:unreach] wizard message: ' + shown.slice(0, 600));
        expect(shown).toMatch(new RegExp('Extraction stopped at chapter 1/' + N));
        expect(shown).toContain(firstTitle);
        expect(shown).toMatch(/consecutive chunks could not reach the model server/);
        expect(shown).toContain(UNREACH_SET);
        expect(shown).not.toMatch(/Request timed out after/);
        expect(/\/picture-book\/[^/]+\/workflow/.test(page.url()), 'did not navigate to the workflow route').toBe(false);
        await expect(page.locator('[data-pb-extract-error]')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('button:has-text("Extract")').first()).toBeEnabled({ timeout: 10000 });

        // Server's own account: ONE job, terminal, not complete, no scenes, typed stoppedEarly entry.
        expect(cap.jobs.length, 'exactly one extraction job started (fan-out stopped at chapter 1); saw '
            + JSON.stringify(cap.jobs.map(j => j.jobId))).toBe(1);
        const job = cap.jobPayloads.get(cap.jobs[0].jobId);
        expect(job, 'terminal payload polled for job ' + cap.jobs[0].jobId).toBeTruthy();
        expect(job.terminal).toBe(true);
        const result = job.result || {};
        expect(result.extractionComplete, 'a run that never reached the model is NOT complete').toBe(false);
        expect((Array.isArray(result.sceneList) ? result.sceneList : []).length, 'no scenes from an unreachable model').toBe(0);
        const failed = (Array.isArray(result.failedExtractions) ? result.failedExtractions : []).map(f => {
            if (typeof f !== 'string') return f;
            try { return JSON.parse(f); } catch (_) { return { error: f }; }
        });
        const breaker = failed.find(f => f && f.stoppedEarly === true);
        expect(breaker, 'typed stoppedEarly entry: ' + JSON.stringify(failed).slice(0, 800)).toBeTruthy();
        expect(String(breaker.error)).toMatch(/could not reach the model server/);
        expect(String(breaker.error)).toContain(UNREACH_SET);
        expect(String(breaker.context)).toMatch(/^extract-scenes-chunk:\d+\/\d+$/);

        // REST truth: one chapter book, no scenes, no later chapter.
        await restLogin(request);
        const allBooks = await getJson(request, PB_REST + '/books');
        const mine = allBooks.filter(b => b.seriesName === docFileName);
        expect(mine.length, 'books in series "' + docFileName + '": ' + JSON.stringify(mine.map(b =>
            ({ ch: b.chapter, sceneCount: b.sceneCount })))).toBe(1);
        expect(Number(mine[0].chapter)).toBe(1);
        expect(Number(mine[0].sceneCount || 0), 'chapter 1 must not report scenes').toBe(0);
        const seriesBooks = await getJson(request, PB_REST + '/series/' + mine[0].seriesObjectId + '/books');
        expect(seriesBooks.length, 'series has only chapter 1').toBe(1);
        expect(cap.pageErrors, 'uncaught page errors during the run').toEqual([]);
    });

    // Assertion 5: re-running the wizard on the SAME manuscript must reuse the series and the
    // chapter books (POST /olio/picture-book/chapter -> 409 reuse path), mint no duplicates, and
    // must not destroy the scenes the first run persisted. The scene-count check is a hard
    // assertion: a re-run that leaves every chapter book with 0 scenes is a product defect, not
    // something to log and move on from.
    test('re-run on the same manuscript: 409 reuse, no duplicate series/books, first-run scenes survive',
        async ({ page, request }) => {
        test.skip(!rerun, 'first test did not complete; nothing to re-run against');
        const { docObjectId, docFileName, N, total, budgetMs, seriesOid, seriesBooks1, sceneCounts1 } = rerun;
        const cap = newCapture();
        wireCapture(page, cap);
        const runStartedIso = new Date().toISOString();
        test.setTimeout(budgetMs + 20 * 60 * 1000);

        await restLogin(request);
        await emulatorStats(request, true);
        await installChapterTruncation(page, N < total ? N : 0);
        await login(page, { org: ORG_PATH, user: EMU_USER, password: EMU_PASSWORD });
        const dialog2 = await openWizard(page, docObjectId, docFileName, MAIN_CONFIG_NAME);
        void dialog2;
        const run2 = await runExtract(page, budgetMs, 'run2');
        console.log('[pb-emu] run2: ' + JSON.stringify({ ...run2, jobs: summarizeJobs(cap) }));
        const chapter409s = cap.pbErrors.filter(e => /^POST 409 \S*\/picture-book\/chapter(\?|\s)/.test(e));
        console.log('[pb-emu] re-run 409s on POST /olio/picture-book/chapter: ' + chapter409s.length);

        await restLogin(request);
        const allBooks2 = await getJson(request, PB_REST + '/books');
        const mine2 = allBooks2.filter(b => b.seriesName === docFileName);
        const seriesOids2 = [...new Set(mine2.map(b => b.seriesObjectId))];
        expect(seriesOids2, 're-run reused the same single series').toEqual([seriesOid]);
        expect(mine2.length, 're-run did not mint duplicate chapter books (/books)').toBe(N);
        const seriesBooks2 = await getJson(request, PB_REST + '/series/' + seriesOid + '/books');
        expect(seriesBooks2.length, 're-run did not mint duplicate chapter books (/series/{oid}/books)').toBe(N);
        expect(seriesBooks2.map(b => b.objectId).sort(), 'same chapter book objectIds after re-run')
            .toEqual(seriesBooks1.map(b => b.objectId).sort());
        expect(run2.outcome, 're-run must not end in an extraction error: ' + (run2.errorText || '')).not.toBe('error');

        const scenesAfter = {};
        for (const b of seriesBooks2) {
            const scenes = await getJson(request, PB_REST + '/' + b.objectId + '/scenes');
            const list = Array.isArray(scenes) ? scenes : (scenes && scenes.sceneList) || [];
            scenesAfter[Number(b.chapter)] = list.length;
        }
        console.log('[pb-emu] RE-RUN scene counts before=' + JSON.stringify(sceneCounts1) + ' after=' + JSON.stringify(scenesAfter)
            + ' outcome=' + run2.outcome + (run2.problems && run2.problems.length ? ' problems=' + JSON.stringify(run2.problems) : ''));
        const wiped = Object.keys(sceneCounts1).filter(k => sceneCounts1[k] > 0 && !(scenesAfter[k] > 0));
        if (wiped.length) {
            const evidence = dockerLogLines(runStartedIso, ['was not persisted and is absent from the book',
                'already exists', 'Failed to add record']);
            console.log('[pb-emu] re-run scene-wipe docker evidence (' + evidence.length + '):\n'
                + evidence.slice(0, 8).map(l => l.slice(0, 300)).join('\n'));
        }
        // Route: wizard re-run -> POST /olio/picture-book/{workOid}/create-from-scenes (PictureBookService).
        // Mechanism seen live: createFromScenes reuses the slug book group + Scenes sub-group, every
        // createSceneNote hits the data.note (name, groupId, organizationId) unique key and returns
        // null, metaScenes ends up empty, and saveMeta overwrites .pictureBookMeta with scenes: [].
        expect(wiped, 'chapters whose first-run scenes were wiped by the re-run (before=' + JSON.stringify(sceneCounts1)
            + ' after=' + JSON.stringify(scenesAfter) + ')').toEqual([]);
        expect(cap.pageErrors, 'uncaught page errors during the re-run').toEqual([]);
    });
});
