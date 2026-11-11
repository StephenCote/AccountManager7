// @vitest-environment jsdom
/**
 * PictureBook health check + self-heal + orphan cleanup — client half.
 *
 * Why: fixes had been landing as per-environment DB edits, so other environments stayed broken. The
 * server now exposes read-only health/orphan reports and explicit heal/purge routes; these tests pin
 * the REST request shapes the server expects (GET never mutates; POST carries codes/overwriteTemplates),
 * the pure report summaries the views key off, the stale-workflow empty state that replaces the
 * misleading "none rendered yet", and the Repair / Clean-up flows (confirm → POST → toast → reload).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import {
    bookHealth, healBook, orgHealth, healOrg, summarizeHealth,
    listOrphans, purgeOrphans, countOrphans,
    HEALTH_STALE_GRAPH, HEALTH_TEMPLATE_DRIFT
} from '../workflows/pictureBookWorkflow.js';

function jsonResponse(status, body) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const F_STALE = { code: HEALTH_STALE_GRAPH, severity: 'ERROR', healable: true,
    message: '3 stale workflow/graph rows from a previous copy of this book', refs: { slug: 'ourselves-doc', bookObjectId: 'B1' } };
const F_SCENE = { code: 'SCENE_ROW_MISSING', severity: 'ERROR', healable: true,
    message: 'scene 4 has a rendered image but no scene row', refs: { slug: 'ourselves-doc', bookObjectId: 'B1', sceneIndex: 4 } };
const F_DRIFT = { code: HEALTH_TEMPLATE_DRIFT, severity: 'WARN', healable: false,
    message: "prompt template 'pictureBook.sceneExtract' differs from the shipped resource (reported only)", refs: { model: 'olio.llm.promptTemplate', name: 'pictureBook.sceneExtract' } };
const F_INFO = { code: 'WORKFLOW_MISSING', severity: 'INFO', healable: false,
    message: 'no workflow yet (created at first render)', refs: { slug: 'ourselves-doc' } };

function report(findings, extra) {
    let errors = findings.filter(f => f.severity === 'ERROR').length;
    let warnings = findings.filter(f => f.severity === 'WARN').length;
    let infos = findings.filter(f => f.severity === 'INFO').length;
    let healable = findings.filter(f => f.healable).length;
    return Object.assign({
        scope: 'org', checkedAt: '2026-10-06T12:00:00Z[GMT]', findings,
        summary: { errors, warnings, infos, healable }, healed: [], skipped: []
    }, extra || {});
}

const SCAN_EMPTY = { scope: 'own', categories: [
    { code: 'ORPHAN_CHECKPOINT', count: 0, items: [] }, { code: 'ORPHAN_WORLD', count: 0, items: [] }
], total: 0, warnings: [] };
const SCAN_SOME = { scope: 'own', categories: [
    { code: 'ORPHAN_CHECKPOINT', count: 0, items: [] },
    { code: 'ORPHAN_META_NOTE', count: 2, items: [{ model: 'data.note', objectId: 'n1' }, { model: 'data.note', objectId: 'n2' }] },
    { code: 'ORPHAN_WORLD', count: 1, items: [{ model: 'olio.world', objectId: 'w1', slug: 'old-book' }] }
], total: 3, warnings: [] };

let origFetch;
let pbf;

beforeEach(async () => {
    origFetch = global.fetch;
    pbf = await import('../features/pictureBook.js');
    pbf.__setPbHealthStateForTest({
        pbHealth: null, pbHealthLoading: false, pbHealthError: null, pbHealthRepairing: false,
        pbHealthOverwriteTemplates: false, pbOrphanScanning: false, pbOrphanPurging: false, pbOrphanOrgWide: false,
        pb2Health: null, pb2HealthRepairing: false, pb2BookObjectId: null, pb2SceneTotal: null, pb2Pages: []
    });
});

afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── REST client request shapes ───────────────────────────────

describe('health REST helpers — request shapes', () => {
    it('bookHealth GETs /{id}/health with credentials and returns the report', async () => {
        let call = null;
        global.fetch = vi.fn(async (u, o) => { call = { url: String(u), opts: o }; return jsonResponse(200, report([F_STALE])); });
        let r = await bookHealth('B1');
        expect(call.url).toMatch(/\/rest\/olio\/picture-book\/B1\/health$/);
        expect(call.opts.credentials).toBe('include');
        expect(call.opts.method).toBeUndefined();   // a GET: the check must never mutate
        expect(r.findings[0].code).toBe(HEALTH_STALE_GRAPH);
    });

    it('bookHealth resolves null on 404 (book gone) and throws on other failures', async () => {
        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'Book not found' }));
        expect(await bookHealth('gone')).toBeNull();
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        await expect(bookHealth('B1')).rejects.toThrow(/bookHealth failed: 500/);
    });

    it('healBook POSTs JSON to /{id}/health/heal; an empty body is sent as {}', async () => {
        let call = null;
        global.fetch = vi.fn(async (u, o) => { call = { url: String(u), opts: o }; return jsonResponse(200, report([])); });
        await healBook('B1');
        expect(call.url).toMatch(/\/picture-book\/B1\/health\/heal$/);
        expect(call.opts.method).toBe('POST');
        expect(call.opts.headers['Content-Type']).toBe('application/json');
        expect(JSON.parse(call.opts.body)).toEqual({});
        await healBook('B1', { codes: [HEALTH_STALE_GRAPH] });
        expect(JSON.parse(call.opts.body)).toEqual({ codes: [HEALTH_STALE_GRAPH] });
    });

    it('healBook surfaces 403 (not entitled) as a thrown error', async () => {
        global.fetch = vi.fn(async () => jsonResponse(403, { error: 'Not authorized to repair this book' }));
        await expect(healBook('B1', {})).rejects.toThrow(/healBook failed: 403/);
    });

    it('orgHealth GETs /health; healOrg POSTs overwriteTemplates verbatim', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push({ url: String(u), opts: o }); return jsonResponse(200, report([F_DRIFT])); });
        await orgHealth();
        expect(calls[0].url).toMatch(/\/rest\/olio\/picture-book\/health$/);
        expect(calls[0].opts.method).toBeUndefined();
        await healOrg({ overwriteTemplates: true });
        expect(calls[1].url).toMatch(/\/picture-book\/health\/heal$/);
        expect(calls[1].opts.method).toBe('POST');
        expect(JSON.parse(calls[1].opts.body)).toEqual({ overwriteTemplates: true });
    });
});

describe('orphan REST helpers — scope → path', () => {
    it("listOrphans('own') and default GET /orphans; 'org' GETs the admin-gated /orphans/org path", async () => {
        let urls = [];
        global.fetch = vi.fn(async (u) => { urls.push(String(u)); return jsonResponse(200, SCAN_EMPTY); });
        await listOrphans();
        await listOrphans('own');
        await listOrphans('org');
        expect(urls[0]).toMatch(/\/rest\/olio\/picture-book\/orphans$/);
        expect(urls[1]).toMatch(/\/picture-book\/orphans$/);
        expect(urls[2]).toMatch(/\/picture-book\/orphans\/org$/);
    });

    it('purgeOrphans POSTs to the matching /purge path with the codes body', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => { calls.push({ url: String(u), opts: o }); return jsonResponse(200, { deleted: 1, denied: 0, failed: 0 }); });
        await purgeOrphans('own', { codes: ['ORPHAN_WORLD'] });
        await purgeOrphans('org', {});
        expect(calls[0].url).toMatch(/\/picture-book\/orphans\/purge$/);
        expect(calls[0].opts.method).toBe('POST');
        expect(JSON.parse(calls[0].opts.body)).toEqual({ codes: ['ORPHAN_WORLD'] });
        expect(calls[1].url).toMatch(/\/picture-book\/orphans\/org\/purge$/);
        expect(JSON.parse(calls[1].opts.body)).toEqual({});
    });

    it('a non-admin org-wide purge (403) throws rather than reporting success', async () => {
        global.fetch = vi.fn(async () => jsonResponse(403, { error: 'requires AccountAdministrators' }));
        await expect(purgeOrphans('org', {})).rejects.toThrow(/purgeOrphans failed: 403/);
    });
});

// ─────────────────────────────── pure summaries ───────────────────────────────

describe('summarizeHealth', () => {
    it('reads the server summary and flags STALE_GRAPH / PROMPT_TEMPLATE_DRIFT by code', () => {
        let s = summarizeHealth(report([F_STALE, F_SCENE, F_DRIFT, F_INFO]));
        expect(s).toEqual({ errors: 2, warnings: 1, healable: 2, hasStaleGraph: true, hasDrift: true });
    });

    it('falls back to counting findings when the summary block is absent', () => {
        let s = summarizeHealth({ findings: [F_SCENE, F_DRIFT] });
        expect(s.errors).toBe(1);
        expect(s.warnings).toBe(1);
        expect(s.healable).toBe(1);
        expect(s.hasStaleGraph).toBe(false);
        expect(s.hasDrift).toBe(true);
    });

    it('null / malformed reports summarize to zero without throwing', () => {
        let zero = { errors: 0, warnings: 0, healable: 0, hasStaleGraph: false, hasDrift: false };
        expect(summarizeHealth(null)).toEqual(zero);
        expect(summarizeHealth('nope')).toEqual(zero);
        expect(summarizeHealth({ findings: 'x', summary: 7 })).toEqual(zero);
    });
});

describe('countOrphans', () => {
    it('prefers the server total, else sums category counts, else 0', () => {
        expect(countOrphans(SCAN_SOME)).toBe(3);
        expect(countOrphans({ categories: SCAN_SOME.categories })).toBe(3);
        expect(countOrphans(null)).toBe(0);
        expect(countOrphans({})).toBe(0);
    });
});

// ─────────────────────────────── reader empty state ───────────────────────────────

describe('pb2EmptyStateText', () => {
    it('no scenes → "No scenes in this book yet."', () => {
        expect(pbf.pb2EmptyStateText(0, null)).toBe('No scenes in this book yet.');
        expect(pbf.pb2EmptyStateText(null, report([F_STALE]))).toBe('No scenes in this book yet.');
    });

    it('scenes but no stale graph → "none rendered yet"', () => {
        expect(pbf.pb2EmptyStateText(12, null)).toBe('12 scenes extracted — none rendered yet.');
        expect(pbf.pb2EmptyStateText(1, report([F_SCENE]))).toBe('1 scene extracted — none rendered yet.');
    });

    it('scenes + STALE_GRAPH → names the stale workflow and points at Repair', () => {
        expect(pbf.pb2EmptyStateText(12, report([F_STALE]))).toBe(
            '12 scenes extracted — rendering was blocked by a stale workflow left by a previously deleted copy of this book. Repair, then render.');
    });
});

// ─────────────────────────────── reader health banner ───────────────────────────────

describe('renderPb2HealthBanner', () => {
    it('renders nothing before a check, and nothing when only INFO findings exist', () => {
        expect(pbf.renderPb2HealthBanner()).toBeNull();
        pbf.__setPbHealthStateForTest({ pb2Health: report([F_INFO]) });
        expect(pbf.renderPb2HealthBanner()).toBeNull();
        pbf.__setPbHealthStateForTest({ pb2Health: report([]) });
        expect(pbf.renderPb2HealthBanner()).toBeNull();
    });

    it('lists ERROR/WARN findings (not INFO) with a Repair button enabled when something is healable', () => {
        pbf.__setPbHealthStateForTest({ pb2Health: report([F_STALE, F_SCENE, F_INFO]), pb2BookObjectId: 'B1' });
        let vnode = pbf.renderPb2HealthBanner();
        expect(vnode).not.toBeNull();
        expect(vnode.attrs['data-pb2-health-banner']).toBe('1');
        expect(vnode.attrs['data-pb2-health-errors']).toBe(2);
        let text = JSON.stringify(vnode);
        expect(text).toContain('This book has 2 health problems (2 repairable).');
        expect(text).toContain('"data-pb2-health-finding":"STALE_GRAPH"');
        expect(text).toContain('"data-pb2-health-finding":"SCENE_ROW_MISSING"');
        expect(text).not.toContain('"data-pb2-health-finding":"WORKFLOW_MISSING"');
        expect(text).toContain('"data-pb2-health-repair":"1"');
        expect(text).toContain('"disabled":false');
    });

    it('disables Repair when nothing is healable (e.g. drift reported only)', () => {
        pbf.__setPbHealthStateForTest({ pb2Health: report([F_DRIFT]), pb2BookObjectId: 'B1' });
        let text = JSON.stringify(pbf.renderPb2HealthBanner());
        expect(text).toContain('This book has 1 health problem.');
        expect(text).toContain('"disabled":true');
    });

    // loadPb2Pages reads the book/world context through am7client.search (XHR), not fetch.
    function stubBookContextSearch() {
        vi.spyOn(am7client, 'newQuery').mockImplementation(() => ({
            entity: { request: [] }, cache() {}, field() {}, range() {}
        }));
        vi.spyOn(am7client, 'search').mockResolvedValue({ results: [] });
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
    }

    it('repairPb2Book: confirm → POST /{id}/health/heal → toast → pages reloaded', async () => {
        pbf.__setPbHealthStateForTest({ pb2Health: report([F_STALE, F_SCENE]), pb2BookObjectId: 'B1' });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        stubBookContextSearch();
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            calls.push({ url, method: (o && o.method) || 'GET' });
            if (url.endsWith('/B1/health/heal')) {
                return jsonResponse(200, report([], { healed: [{ code: HEALTH_STALE_GRAPH }, { code: 'SCENE_ROW_MISSING' }] }));
            }
            if (url.endsWith('/B1/health')) return jsonResponse(200, report([]));
            if (url.endsWith('/B1/pages')) return jsonResponse(200, [{ objectId: 's1', sceneIndex: 1, dataObjectId: 'd1' }]);
            // loadPb2BookContext / loadPictureBook / chapter lookups: any JSON keeps the reload moving.
            return jsonResponse(200, []);
        });

        await pbf.repairPb2Book();

        let heal = calls.find(c => c.url.endsWith('/B1/health/heal'));
        expect(heal).toBeTruthy();
        expect(heal.method).toBe('POST');
        expect(toast).toHaveBeenCalledWith('success', '2 repairs applied');
        // The reload re-read pages AND the health report, so the banner reflects the healed state.
        expect(calls.some(c => c.url.endsWith('/B1/pages') && c.method === 'GET')).toBe(true);
        expect(calls.some(c => c.url.endsWith('/B1/health') && c.method === 'GET')).toBe(true);
        let st = pbf.__pbHealthStateForTest();
        expect(st.pb2HealthRepairing).toBe(false);
        expect(summarizeHealth(st.pb2Health).errors).toBe(0);
    });

    it('repairPb2Book: a declined confirm sends nothing; a 403 toasts an error', async () => {
        pbf.__setPbHealthStateForTest({ pb2Health: report([F_STALE]), pb2BookObjectId: 'B1' });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        global.fetch = vi.fn(async () => jsonResponse(200, report([])));
        await pbf.repairPb2Book();
        expect(global.fetch).not.toHaveBeenCalled();

        vi.restoreAllMocks();
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        stubBookContextSearch();
        global.fetch = vi.fn(async (u, o) => (o && o.method === 'POST') ? jsonResponse(403, {}) : jsonResponse(200, []));
        await pbf.repairPb2Book();
        expect(toast).toHaveBeenCalledWith('error', expect.stringMatching(/403/));
    });
});

// ─────────────────────────────── list-view health panel ───────────────────────────────

describe('renderHealthPanel + checkOrgHealth', () => {
    it('before any check: only "Check health" and "Clean up orphans"; no Repair, no findings', () => {
        let text = JSON.stringify(pbf.renderHealthPanel());
        expect(text).toContain('"data-pb-health-check":"1"');
        expect(text).toContain('Check health');
        expect(text).toContain('"data-pb-orphans-scan":"1"');
        expect(text).not.toContain('"data-pb-health-repair"');
        expect(text).not.toContain('"data-pb-health-finding"');
        expect(text).not.toContain('"data-pb-health-overwrite-templates"');
    });

    it('checkOrgHealth GETs /health and renders one row per finding, Repair enabled when healable', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, report([F_STALE, F_SCENE, F_INFO])));
        await pbf.checkOrgHealth();
        let st = pbf.__pbHealthStateForTest();
        expect(st.pbHealthLoading).toBe(false);
        expect(st.pbHealthError).toBeNull();
        let text = JSON.stringify(pbf.renderHealthPanel());
        expect(text).toContain('"data-pb-health-finding":"STALE_GRAPH"');
        expect(text).toContain('"data-pb-health-finding":"SCENE_ROW_MISSING"');
        expect(text).toContain('"data-pb-health-finding":"WORKFLOW_MISSING"');
        expect(text).toContain('ourselves-doc: 3 stale workflow/graph rows');
        expect(text).toContain('Re-check health');
        expect(text).toContain('"data-pb-health-repair":"1"');
        // No drift → the overwrite checkbox is not offered.
        expect(text).not.toContain('"data-pb-health-overwrite-templates"');
    });

    it('a clean report shows "No problems found." and a disabled Repair', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, report([])));
        await pbf.checkOrgHealth();
        let text = JSON.stringify(pbf.renderHealthPanel());
        expect(text).toContain('"data-pb-health-clean":"1"');
        expect(text).toContain('No problems found.');
        expect(text).toMatch(/"data-pb-health-repair":"1","disabled":true/);
    });

    it('a failed check shows the error and leaves no stale report behind', async () => {
        pbf.__setPbHealthStateForTest({ pbHealth: report([F_STALE]) });
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        await pbf.checkOrgHealth();
        let st = pbf.__pbHealthStateForTest();
        expect(st.pbHealth).toBeNull();
        expect(st.pbHealthError).toMatch(/500/);
        expect(JSON.stringify(pbf.renderHealthPanel())).toContain('"data-pb-health-error":"1"');
    });

    it('drift only: Repair is disabled until the overwrite checkbox is ticked', async () => {
        global.fetch = vi.fn(async () => jsonResponse(200, report([F_DRIFT])));
        await pbf.checkOrgHealth();
        let text = JSON.stringify(pbf.renderHealthPanel());
        expect(text).toContain('"data-pb-health-overwrite-templates":"1"');
        expect(text).toMatch(/"data-pb-health-repair":"1","disabled":true/);
        pbf.__setPbHealthStateForTest({ pbHealthOverwriteTemplates: true });
        text = JSON.stringify(pbf.renderHealthPanel());
        expect(text).toMatch(/"data-pb-health-repair":"1","disabled":false/);
    });

    it('the "Whole organization" toggle is offered only to admins', () => {
        let ctx = vi.spyOn(page, 'context');
        ctx.mockReturnValue({ roles: { user: { id: 1 } } });
        expect(JSON.stringify(pbf.renderHealthPanel())).not.toContain('"data-pb-orphans-org-wide"');
        ctx.mockReturnValue({ roles: { user: { id: 1 }, admin: { id: 2 } } });
        expect(JSON.stringify(pbf.renderHealthPanel())).toContain('"data-pb-orphans-org-wide":"1"');
    });
});

describe('repairOrgHealth', () => {
    it('confirm → POST /health/heal with overwriteTemplates → toast → report replaced by the post-heal one', async () => {
        pbf.__setPbHealthStateForTest({ pbHealth: report([F_STALE, F_SCENE, F_DRIFT]), pbHealthOverwriteTemplates: true });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            calls.push({ url, opts: o });
            if (url.endsWith('/health/heal')) {
                return jsonResponse(200, report([F_INFO], {
                    healed: [{ code: HEALTH_STALE_GRAPH }, { code: 'SCENE_ROW_MISSING' }, { code: HEALTH_TEMPLATE_DRIFT }],
                    skipped: []
                }));
            }
            return jsonResponse(200, []);   // reloadSelectorLists: /books + /extract-checkpoints
        });

        await pbf.repairOrgHealth();

        let heal = calls.find(c => c.url.endsWith('/health/heal'));
        expect(heal).toBeTruthy();
        expect(heal.opts.method).toBe('POST');
        expect(JSON.parse(heal.opts.body)).toEqual({ overwriteTemplates: true });
        expect(toast).toHaveBeenCalledWith('success', '3 repairs applied');
        let st = pbf.__pbHealthStateForTest();
        expect(st.pbHealthRepairing).toBe(false);
        expect(summarizeHealth(st.pbHealth).errors).toBe(0);
        expect(calls.some(c => c.url.endsWith('/books'))).toBe(true);
    });

    it('does nothing when there is no report or nothing repairable; a declined confirm sends nothing', async () => {
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        global.fetch = vi.fn(async () => jsonResponse(200, report([])));
        await pbf.repairOrgHealth();                                   // no report
        pbf.__setPbHealthStateForTest({ pbHealth: report([F_DRIFT]) }); // drift, overwrite unticked
        await pbf.repairOrgHealth();
        expect(global.fetch).not.toHaveBeenCalled();
        expect(Dialog.confirm).not.toHaveBeenCalled();

        vi.restoreAllMocks();
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        global.fetch = vi.fn(async () => jsonResponse(200, report([])));
        pbf.__setPbHealthStateForTest({ pbHealth: report([F_STALE]) });
        await pbf.repairOrgHealth();
        expect(global.fetch).not.toHaveBeenCalled();
    });

    it('a partial heal reports skipped and remaining errors as info', async () => {
        pbf.__setPbHealthStateForTest({ pbHealth: report([F_STALE, F_SCENE]) });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        global.fetch = vi.fn(async (u, o) => (o && o.method === 'POST')
            ? jsonResponse(200, report([F_SCENE], { healed: [{ code: HEALTH_STALE_GRAPH }], skipped: [{ code: 'SCENE_ROW_MISSING', reason: 'image missing' }] }))
            : jsonResponse(200, []));
        await pbf.repairOrgHealth();
        expect(toast).toHaveBeenCalledWith('info', '1 repair applied, 1 skipped; 1 error remains');
    });
});

describe('cleanupOrphans', () => {
    function wire(scan, purgeResult) {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            let method = (o && o.method) || 'GET';
            calls.push({ url, method, body: o && o.body });
            if (/\/orphans(\/org)?$/.test(url)) return jsonResponse(200, scan);
            if (/\/orphans(\/org)?\/purge$/.test(url)) return jsonResponse(200, purgeResult);
            return jsonResponse(200, []);
        });
        return calls;
    }

    it('an empty own-scope scan toasts info and never asks to confirm or purge', async () => {
        let calls = wire(SCAN_EMPTY, null);
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        await pbf.cleanupOrphans();
        expect(calls.map(c => c.url).some(u => u.endsWith('/orphans'))).toBe(true);
        expect(confirm).not.toHaveBeenCalled();
        expect(calls.some(c => c.url.endsWith('/purge'))).toBe(false);
        expect(toast).toHaveBeenCalledWith('info', expect.stringMatching(/No orphaned picture book records/));
    });

    it('scan → destructive confirm listing category counts → POST /orphans/purge → toast → lists reloaded', async () => {
        let calls = wire(SCAN_SOME, { deleted: 3, denied: 0, failed: 0, cleanupOrphansRan: true });
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        let clear = vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        await pbf.cleanupOrphans();
        let cfg = confirm.mock.calls[0][0];
        expect(cfg.destructive).toBe(true);
        expect(cfg.message).toContain('3 orphaned records');
        expect(cfg.message).toContain('2 × ORPHAN_META_NOTE');
        expect(cfg.message).toContain('1 × ORPHAN_WORLD');
        expect(cfg.message).not.toContain('ORPHAN_CHECKPOINT');   // zero-count categories are not listed
        let purge = calls.find(c => c.url.endsWith('/orphans/purge'));
        expect(purge).toBeTruthy();
        expect(purge.method).toBe('POST');
        expect(toast).toHaveBeenCalledWith('success', '3 orphans deleted');
        expect(clear).toHaveBeenCalled();
        expect(calls.some(c => c.url.endsWith('/books'))).toBe(true);
        let st = pbf.__pbHealthStateForTest();
        expect(st.pbOrphanScanning).toBe(false);
        expect(st.pbOrphanPurging).toBe(false);
    });

    it('a declined confirm purges nothing', async () => {
        let calls = wire(SCAN_SOME, { deleted: 3, denied: 0, failed: 0 });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);
        await pbf.cleanupOrphans();
        expect(calls.some(c => c.url.endsWith('/purge'))).toBe(false);
    });

    it('denied/failed outcomes are reported as info, not success', async () => {
        wire(SCAN_SOME, { deleted: 1, denied: 1, failed: 1 });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        await pbf.cleanupOrphans();
        expect(toast).toHaveBeenCalledWith('info', '1 orphan deleted, 1 denied, 1 failed');
    });

    it('the org-wide toggle routes an ADMIN to /orphans/org(+/purge); a non-admin with the flag set stays on own scope', async () => {
        let ctx = vi.spyOn(page, 'context');
        ctx.mockReturnValue({ roles: { user: { id: 1 }, admin: { id: 2 } } });
        pbf.__setPbHealthStateForTest({ pbOrphanOrgWide: true });
        let calls = wire(Object.assign({}, SCAN_SOME, { scope: 'org' }), { deleted: 3, denied: 0, failed: 0 });
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        vi.spyOn(am7client, 'clearCache').mockImplementation(() => {});
        await pbf.cleanupOrphans();
        expect(calls.some(c => c.url.endsWith('/orphans/org') && c.method === 'GET')).toBe(true);
        expect(calls.some(c => c.url.endsWith('/orphans/org/purge') && c.method === 'POST')).toBe(true);

        ctx.mockReturnValue({ roles: { user: { id: 1 } } });
        calls = wire(SCAN_SOME, { deleted: 3, denied: 0, failed: 0 });
        await pbf.cleanupOrphans();
        expect(calls.some(c => /\/orphans\/org/.test(c.url))).toBe(false);
        expect(calls.some(c => c.url.endsWith('/orphans') && c.method === 'GET')).toBe(true);
    });

    it('a failed scan toasts an error and purges nothing', async () => {
        global.fetch = vi.fn(async () => jsonResponse(500, {}));
        let confirm = vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);
        let toast = vi.spyOn(page, 'toast').mockImplementation(() => {});
        await pbf.cleanupOrphans();
        expect(confirm).not.toHaveBeenCalled();
        expect(toast).toHaveBeenCalledWith('error', expect.stringMatching(/500/));
        expect(pbf.__pbHealthStateForTest().pbOrphanScanning).toBe(false);
    });
});
