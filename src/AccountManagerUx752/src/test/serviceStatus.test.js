// @vitest-environment jsdom
/**
 * Service Status (/admin/status, aiDocs/ServiceStatusDesign.md): the feature-local client's request shapes
 * against /rest/system/check, the pure helpers, rendering of both tables from a fixture report, the per-row
 * Test action, the 403/409 messages, the degraded banner's show/hide/dismiss/throttle logic, and the
 * feature wiring (manifest entry, menu visibility, route registration, profiles).
 *
 * The backend is not deployed yet: these are client-side tests against the §7 contract. They do not
 * exercise the server; the Playwright spec (design §9) is what will.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

function fixtureReport(opts) {
    let o = opts || {};
    let deployment = [
        // deliberately out of order: the view sorts into the design's fixed order
        { name: 'sd', status: 'WARN', reason: 'unreachable', detail: 'Server is unreachable', latencyMs: 12.4, source: 'record',
            target: o.redact ? { type: 'swarm', urlRedacted: true } : { type: 'swarm', url: 'http://sd.example:7801' }, urlRedacted: !!o.redact },
        { name: 'db', status: 'ok', reason: 'ok', detail: 'postgres', latencyMs: 3, target: { type: 'POSTGRESQL' } },
        { name: 'embedding', status: 'Fail', reason: 'timeout', detail: 'Timed out', latencyMs: 5001, bounded: false,
            target: o.redact ? { type: 'LOCAL', urlRedacted: true } : { type: 'LOCAL', url: 'http://emb.example:8123' }, urlRedacted: !!o.redact },
        { name: 'embedding.config', status: 'ok', reason: 'ok', detail: 'Type matches URL' },
        { name: 'voice.stt', status: 'skip', reason: 'not_configured', detail: 'Not configured' }
    ];
    if (!o.redact) deployment.push({ name: 'vectors', status: 'ok', detail: 'Changing the embedding provider invalidates 42 stored vectors' });
    return {
        status: 'fail',
        checkedAt: 1700000000000,
        complete: o.complete === undefined ? true : o.complete,
        undetermined: o.undetermined || [],
        propagation: 'Edits elsewhere show up within 30 s + 60 s, or on the next Run.',
        deployment: deployment,
        connections: [
            { objectId: 'c-1', id: 11, name: 'litellm', groupPath: '/home/admin/Connections', dialect: 'openai_compat', upstream: 'ollama',
                hasApiKey: true, referencedBy: [{ name: 'chatA' }, 'chatB'], status: 'ok', detail: 'Models listed', latencyMs: 40 },
            { objectId: 'c-2', id: 12, name: 'remote-ollama', groupPath: '/home/admin/Connections', dialect: 'ollama',
                hasApiKey: false, referencedBy: [] }
        ]
    };
}

async function withMockedRequest(handler, fn) {
    let m = (await import('mithril')).default;
    let seen = [];
    let orig = m.request;
    m.request = (opts) => { seen.push(opts); return handler(opts); };
    try {
        await fn(seen);
    } finally {
        m.request = orig;
    }
    return seen;
}

function httpError(code, body) {
    let e = new Error('HTTP ' + code);
    e.code = code;
    if (body) Object.assign(e, body);
    return e;
}

async function setAdmin(on) {
    let { page } = await import('../core/pageClient.js');
    page.context().roles.admin = !!on;
}

async function render(root) {
    let m = (await import('mithril')).default;
    let view = await import('../features/serviceStatus/serviceStatusView.js');
    m.render(root, view.renderPanel());
}

const flush = () => new Promise(r => setTimeout(r, 0));

// ── Client ──────────────────────────────────────────────────────────

describe('serviceStatusClient request shapes', () => {
    it('hits /rest/system/check with the right methods, encodes ids, and always sends an explicit deep flag', async () => {
        let seen = await withMockedRequest(() => Promise.resolve({}), async () => {
            let { serviceStatusClient: c } = await import('../features/serviceStatus/serviceStatusClient.js');
            await c.summary();
            await c.last();
            await c.run(false);
            await c.run(true);
            await c.connections();
            await c.testConnection('abc 123/x', false);
            await c.testConnection('c-1', true);
            await c.testServer('voice.tts', true);
            await c.testServer('db', false);
        });
        expect(seen.map(s => s.method)).toEqual(['GET', 'GET', 'POST', 'POST', 'GET', 'POST', 'POST', 'POST', 'POST']);
        expect(seen[0].url).toMatch(/\/rest\/system\/check\/summary$/);
        expect(seen[1].url).toMatch(/\/rest\/system\/check$/);
        expect(seen[2].url).toMatch(/\/rest\/system\/check\?deep=false$/);
        expect(seen[3].url).toMatch(/\/rest\/system\/check\?deep=true$/);
        expect(seen[4].url).toMatch(/\/rest\/system\/check\/connections$/);
        expect(seen[5].url).toMatch(/\/rest\/system\/check\/connection\/abc%20123%2Fx\?deep=false$/);
        expect(seen[6].url).toMatch(/\/rest\/system\/check\/connection\/c-1\?deep=true$/);
        expect(seen[7].url).toMatch(/\/rest\/system\/check\/server\/voice\.tts\?deep=true$/);
        expect(seen[8].url).toMatch(/\/rest\/system\/check\/server\/db\?deep=false$/);
        for (let s of seen) {
            expect(s.withCredentials).toBe(true);
            expect(s.background).toBe(true);
            // The service never accepts a URL from the client (design §2): no body, no url parameter.
            expect(s.body).toBeUndefined();
            expect(s.url).not.toMatch(/[?&]url=/);
        }
    });
});

// ── Pure helpers ────────────────────────────────────────────────────

describe('pure helpers', () => {
    it('compares statuses case-insensitively', async () => {
        let v = await import('../features/serviceStatus/serviceStatusView.js');
        expect(v.normStatus('OK')).toBe('ok');
        expect(v.normStatus(null)).toBe('unknown');
        expect(v.statusClass('OK')).toBe(v.statusClass('ok'));
        expect(v.statusClass('Fail')).toMatch(/red/);
        expect(v.statusClass('WARN')).toMatch(/amber/);
        expect(v.overallLabel('OK')).toBe('All services OK');
        expect(v.overallLabel('warn')).toBe('Degraded');
        expect(v.overallLabel('FAIL')).toBe('Failing');
        expect(v.overallLabel(undefined)).toBe('Unknown');
    });

    it('buildRows sorts into the design order and never synthesizes a missing row', async () => {
        let v = await import('../features/serviceStatus/serviceStatusView.js');
        expect(v.buildRows(fixtureReport()).map(r => r.name)).toEqual(['db', 'embedding', 'embedding.config', 'vectors', 'sd', 'voice.stt']);
        expect(v.buildRows(fixtureReport({ redact: true })).map(r => r.name)).toEqual(['db', 'embedding', 'embedding.config', 'sd', 'voice.stt']);
        expect(v.buildRows({ checks: [{ name: 'zzz' }, { name: 'tag' }] }).map(r => r.name)).toEqual(['tag', 'zzz']);
        expect(v.buildRows(null)).toEqual([]);
    });

    it('urlText / detailText / usedBy / apiKey / undetermined / errorText', async () => {
        let v = await import('../features/serviceStatus/serviceStatusView.js');
        expect(v.urlText({ target: { url: 'http://x:1' } })).toBe('http://x:1');
        expect(v.urlText({ urlRedacted: true, target: { url: 'http://x:1' } })).toBe('configured in /System');
        expect(v.urlText({ target: { urlRedacted: true } })).toBe('configured in /System');
        expect(v.detailText({ detail: 'Timed out', bounded: false })).toMatch(/Timed out \(unbounded/);
        expect(v.detailText({ reason: 'auth' })).toBe('auth');
        expect(v.usedByText({ referencedBy: [{ name: 'a' }, 'b', { id: 3 }] })).toBe('a, b, #3');
        expect(v.hasApiKeyText({ hasApiKey: true })).toBe('set');
        expect(v.hasApiKeyText({ hasApiKey: false })).toBe('none');
        expect(v.undeterminedNames({ undetermined: ['x', { name: 'y' }, { objectId: 'z' }, { id: 4 }] })).toEqual(['x', 'y', 'z', '#4']);
        expect(v.errorText(httpError(403), 'f')).toBe('Administrator role required');
        expect(v.errorText(httpError(409), 'f')).toBe('A deep test is already running');
        expect(v.errorText(httpError(500, { error: 'boom' }), 'f')).toBe('boom');
        expect(v.latencyText(12.6)).toBe('13 ms');
        expect(v.buildConnectionRows({ connections: [{ objectId: 'a' }] })).toEqual([{ objectId: 'a' }]);
        expect(v.buildConnectionRows([{ objectId: 'b' }])).toEqual([{ objectId: 'b' }]);
    });
});

// ── Rendering + actions ─────────────────────────────────────────────

describe('serviceStatusView rendering', () => {
    let view;
    beforeEach(async () => {
        view = await import('../features/serviceStatus/serviceStatusView.js');
        view.resetState();
        await setAdmin(true);
    });
    afterEach(async () => { await setAdmin(false); });

    it('renders both tables from a /System-admin report (URLs + vectors row)', async () => {
        view.setReport(fixtureReport());
        let root = document.createElement('div');
        await render(root);
        expect(root.querySelector('h1').textContent).toBe('Service Status');
        let overall = root.querySelector('[data-testid="svc-overall"]');
        expect(overall.getAttribute('data-status')).toBe('fail');
        expect(overall.textContent).toBe('Failing');
        expect(root.querySelector('#serviceStatusDeep')).not.toBe(null);
        expect(root.querySelector('#serviceStatusRun')).not.toBe(null);
        expect(root.querySelector('[data-testid="svc-propagation"]').textContent).toMatch(/30 s \+ 60 s/);
        expect(root.querySelector('[data-testid="svc-incomplete"]')).toBe(null);

        let rows = [...root.querySelectorAll('#serviceStatusDeployment tbody tr')].map(r => r.getAttribute('data-testid'));
        expect(rows).toEqual(['svc-row-db', 'svc-row-embedding', 'svc-row-embedding.config', 'svc-row-vectors', 'svc-row-sd', 'svc-row-voice.stt']);
        expect(root.querySelector('[data-testid="svc-status-embedding"]').getAttribute('data-status')).toBe('fail');
        expect(root.querySelector('[data-testid="svc-status-sd"]').getAttribute('data-status')).toBe('warn');
        expect(root.querySelector('[data-testid="svc-url-sd"]').textContent).toBe('http://sd.example:7801');
        expect(root.querySelector('[data-testid="svc-detail-embedding"]').textContent).toMatch(/unbounded/);

        let c1 = root.querySelector('[data-testid="conn-row-c-1"]');
        expect(c1.textContent).toContain('litellm');
        expect(c1.textContent).toContain('/home/admin/Connections');
        expect(c1.textContent).toContain('OPENAI_COMPAT');
        expect(c1.textContent).toContain('OLLAMA');
        expect(c1.textContent).toContain('chatA, chatB');
        expect(root.querySelector('[data-testid="conn-key-c-1"]').textContent).toBe('set');
        expect(root.querySelector('[data-testid="conn-key-c-2"]').textContent).toBe('none');
        expect(root.querySelector('[data-testid="conn-status-c-1"]').getAttribute('data-status')).toBe('ok');
        expect(root.querySelector('[data-testid="conn-status-c-2"]').getAttribute('data-status')).toBe('unknown');
        expect(root.querySelector('[data-testid="conn-test-c-2"]')).not.toBe(null);
        expect(root.querySelector('[data-testid="conn-deep-c-2"]')).not.toBe(null);
    });

    it('a non-/System admin report shows "configured in /System" and has no vectors row', async () => {
        view.setReport(fixtureReport({ redact: true }));
        let root = document.createElement('div');
        await render(root);
        expect(root.querySelector('[data-testid="svc-row-vectors"]')).toBe(null);
        expect(root.querySelector('[data-testid="svc-url-sd"]').textContent).toBe('configured in /System');
        expect(root.querySelector('[data-testid="svc-url-embedding"]').textContent).toBe('configured in /System');
        expect(root.textContent).not.toContain('://');
    });

    it('shows the incomplete banner listing undetermined connections', async () => {
        view.setReport(fixtureReport({ complete: false, undetermined: [{ objectId: 'c-9', name: 'hidden-conn' }, 'other'] }));
        let root = document.createElement('div');
        await render(root);
        let b = root.querySelector('[data-testid="svc-incomplete"]');
        expect(b).not.toBe(null);
        expect(root.querySelector('[data-testid="svc-undetermined"]').textContent).toBe('Undetermined: hidden-conn, other');
    });

    it('a non-admin sees "Administrator role required" and no tables', async () => {
        await setAdmin(false);
        view.setReport(fixtureReport());
        let root = document.createElement('div');
        await render(root);
        expect(root.querySelector('[data-testid="svc-forbidden"]').textContent).toMatch(/Administrator role required/);
        expect(root.querySelector('#serviceStatusDeployment')).toBe(null);
    });

    it('Test calls testConnection for that row, shows busy, then merges the result into the row', async () => {
        view.setReport(fixtureReport());
        let resolveReq;
        let root = document.createElement('div');
        let seen = await withMockedRequest(() => new Promise(r => { resolveReq = r; }), async () => {
            await render(root);
            root.querySelector('[data-testid="conn-test-c-2"]').click();
            await render(root);
            expect(view.getState().busy['conn:c-2']).toBe('test');
            expect(root.querySelector('[data-testid="conn-test-c-2"]').disabled).toBe(true);
            expect(root.querySelector('[data-testid="conn-deep-c-2"]').disabled).toBe(true);
            // other rows stay usable
            expect(root.querySelector('[data-testid="conn-test-c-1"]').disabled).toBe(false);
            resolveReq({ objectId: 'c-2', status: 'FAIL', reason: 'unreachable', detail: 'Server is unreachable', latencyMs: 5000 });
            await flush(); await flush();
            await render(root);
        });
        expect(seen.length).toBe(1);
        expect(seen[0].method).toBe('POST');
        expect(seen[0].url).toMatch(/\/rest\/system\/check\/connection\/c-2\?deep=false$/);
        expect(view.getState().busy['conn:c-2']).toBeUndefined();
        let cell = root.querySelector('[data-testid="conn-status-c-2"]');
        expect(cell.getAttribute('data-status')).toBe('fail');
        expect(root.querySelector('[data-testid="conn-detail-c-2"]').textContent).toContain('Server is unreachable');
        // descriptive fields survive the merge
        expect(root.querySelector('[data-testid="conn-row-c-2"]').textContent).toContain('remote-ollama');
        expect(root.querySelector('[data-testid="conn-test-c-2"]').disabled).toBe(false);
    });

    it('Deep on a row sends deep=true and a 409 shows "A deep test is already running"', async () => {
        view.setReport(fixtureReport());
        let root = document.createElement('div');
        let seen = await withMockedRequest(() => Promise.reject(httpError(409, { error: 'in_progress' })), async () => {
            await render(root);
            root.querySelector('[data-testid="conn-deep-c-1"]').click();
            await flush(); await flush();
            await render(root);
        });
        expect(seen[0].url).toMatch(/\/connection\/c-1\?deep=true$/);
        expect(root.querySelector('[data-testid="conn-error-c-1"]').textContent).toBe('A deep test is already running');
        // the row's previous status is kept
        expect(root.querySelector('[data-testid="conn-status-c-1"]').getAttribute('data-status')).toBe('ok');
    });

    it('a 403 on Run shows "Administrator role required"; Run honours the Deep checkbox', async () => {
        view.setReport(fixtureReport());
        let root = document.createElement('div');
        let seen = await withMockedRequest(() => Promise.reject(httpError(403)), async () => {
            await render(root);
            let deep = root.querySelector('#serviceStatusDeep');
            deep.checked = true;
            deep.dispatchEvent(new Event('change'));
            root.querySelector('#serviceStatusRun').click();
            await flush(); await flush();
            await render(root);
        });
        expect(seen[0].method).toBe('POST');
        expect(seen[0].url).toMatch(/\/rest\/system\/check\?deep=true$/);
        expect(root.querySelector('[data-testid="svc-error"]').textContent).toBe('Administrator role required');
    });

    it('a deployment Test updates only that row', async () => {
        view.setReport(fixtureReport());
        let root = document.createElement('div');
        let seen = await withMockedRequest(() => Promise.resolve({ name: 'sd', status: 'ok', detail: 'Reachable', latencyMs: 9 }), async () => {
            await render(root);
            root.querySelector('[data-testid="svc-test-sd"]').click();
            await flush(); await flush();
            await render(root);
        });
        expect(seen[0].url).toMatch(/\/rest\/system\/check\/server\/sd\?deep=false$/);
        expect(root.querySelector('[data-testid="svc-status-sd"]').getAttribute('data-status')).toBe('ok');
        expect(root.querySelector('[data-testid="svc-url-sd"]').textContent).toBe('http://sd.example:7801');
        expect(root.querySelector('[data-testid="svc-status-embedding"]').getAttribute('data-status')).toBe('fail');
    });

    it('loadLast falls back to GET /connections when the report carries no connections', async () => {
        let seen = await withMockedRequest((o) => /\/connections$/.test(o.url)
            ? Promise.resolve({ connections: [{ objectId: 'c-5', name: 'only' }] })
            : Promise.resolve({ status: 'ok', checkedAt: 1, deployment: [{ name: 'db', status: 'ok' }] }), async () => {
            await view.loadLast();
        });
        expect(seen.map(s => s.method + ' ' + s.url.replace(/^.*\/rest/, ''))).toEqual(['GET /system/check', 'GET /system/check/connections']);
        expect(view.getState().connections.map(c => c.objectId)).toEqual(['c-5']);
    });
});

// ── Banner ──────────────────────────────────────────────────────────

describe('serviceStatusBanner decision logic', () => {
    it('pure: degraded detection (case-insensitive), signature, wording', async () => {
        let b = await import('../components/serviceStatusBanner.js');
        expect(b.isDegraded(null)).toBe(false);
        expect(b.isDegraded({ status: 'OK', degraded: [] })).toBe(false);
        expect(b.isDegraded({ status: 'ok' })).toBe(false);
        expect(b.isDegraded({ status: 'DEGRADED', degraded: ['sd'] })).toBe(true);
        expect(b.isDegraded({ status: 'fail' })).toBe(true);
        expect(b.isDegraded({ status: 'ok', degraded: ['tag'] })).toBe(true);
        expect(b.summarySignature({ status: 'WARN', degraded: ['sd', 'embedding'] }))
            .toBe(b.summarySignature({ status: 'warn', degraded: ['embedding', 'sd'] }));
        let text = b.bannerText({ status: 'warn', degraded: ['embedding', 'sd'] });
        expect(text).toBe('Some services are degraded: embedding, sd. Ask an administrator to open Service Status.');
        expect(text).not.toMatch(/#!|\/admin\/status/);
        expect(b.shouldRefetch(1000, 0)).toBe(true);
        expect(b.shouldRefetch(60999, 1000)).toBe(false);
        expect(b.shouldRefetch(61000, 1000)).toBe(true);
    });

    function fakeWin() {
        let listeners = {};
        return {
            addEventListener: (n, f) => { (listeners[n] = listeners[n] || []).push(f); },
            removeEventListener: (n, f) => { listeners[n] = (listeners[n] || []).filter(x => x !== f); },
            fire: (n) => (listeners[n] || []).map(f => f()),
            count: (n) => (listeners[n] || []).length
        };
    }

    it('shows when degraded, hides when ok, dismiss hides until the degraded set changes', async () => {
        let { createServiceStatusBanner } = await import('../components/serviceStatusBanner.js');
        let next = { status: 'warn', degraded: ['sd'] };
        let t = 100000;
        let win = fakeWin();
        let ctl = createServiceStatusBanner({ fetchSummary: () => Promise.resolve(next), now: () => t, win, redraw: () => {} });
        expect(ctl.isVisible()).toBe(false);
        await ctl.start();
        expect(ctl.isVisible()).toBe(true);
        expect(win.count('focus')).toBe(1);

        ctl.dismiss();
        expect(ctl.isVisible()).toBe(false);

        // same state after a refetch stays dismissed
        t += 60000;
        await ctl.onFocus();
        expect(ctl.isVisible()).toBe(false);

        // a different degraded set re-shows
        next = { status: 'warn', degraded: ['sd', 'embedding'] };
        t += 60000;
        await ctl.onFocus();
        expect(ctl.isVisible()).toBe(true);

        // recovery hides it
        next = { status: 'ok', degraded: [] };
        t += 60000;
        await ctl.onFocus();
        expect(ctl.isVisible()).toBe(false);

        ctl.stop();
        expect(win.count('focus')).toBe(0);
        expect(ctl.isVisible()).toBe(false);
    });

    it('refetches on focus at most once per 60 s', async () => {
        let { createServiceStatusBanner } = await import('../components/serviceStatusBanner.js');
        let calls = 0;
        let t = 500000;
        let win = fakeWin();
        let ctl = createServiceStatusBanner({ fetchSummary: () => { calls++; return Promise.resolve({ status: 'ok', degraded: [] }); }, now: () => t, win, redraw: () => {} });
        await ctl.start();
        expect(calls).toBe(1);
        t += 1000;  await Promise.all(win.fire('focus').filter(Boolean));
        t += 30000; await Promise.all(win.fire('focus').filter(Boolean));
        expect(calls).toBe(1);
        t += 29000; await Promise.all(win.fire('focus').filter(Boolean));
        expect(calls).toBe(2);
        t += 59999; await Promise.all(win.fire('focus').filter(Boolean));
        expect(calls).toBe(2);
        // starting twice does not register a second focus listener
        await ctl.start();
        expect(win.count('focus')).toBe(1);
    });

    it('fails silently on 401/403/5xx/network and keeps the previous state; never throws', async () => {
        let { createServiceStatusBanner } = await import('../components/serviceStatusBanner.js');
        let mode = 'ok';
        let t = 0;
        let ctl = createServiceStatusBanner({
            fetchSummary: () => {
                if (mode === 'ok') return Promise.resolve({ status: 'fail', degraded: ['db'] });
                if (mode === 'throw') throw new Error('sync');
                return Promise.reject(httpError(mode));
            },
            now: () => t, win: fakeWin(), redraw: () => {}
        });
        await ctl.start();
        expect(ctl.isVisible()).toBe(true);
        for (let code of [401, 403, 500, 503, 'throw']) {
            mode = code;
            t += 60000;
            await expect(ctl.onFocus()).resolves.toBe(null);
            expect(ctl.isVisible()).toBe(true);
        }
        // a cold start that fails shows nothing
        let cold = createServiceStatusBanner({ fetchSummary: () => Promise.reject(httpError(401)), now: () => 0, win: fakeWin(), redraw: () => {} });
        await cold.start();
        expect(cold.isVisible()).toBe(false);
    });

    it('the default banner fetches /summary through m.request (not am7client, so no forceLogin on 401)', async () => {
        let seen = await withMockedRequest(() => Promise.reject(httpError(401)), async () => {
            let { createServiceStatusBanner } = await import('../components/serviceStatusBanner.js');
            let ctl = createServiceStatusBanner({ win: null, redraw: () => {} });
            await ctl.start();
            expect(ctl.isVisible()).toBe(false);
            ctl.stop();
        });
        expect(seen.length).toBe(1);
        expect(seen[0].method).toBe('GET');
        expect(seen[0].url).toMatch(/\/rest\/system\/check\/summary$/);
    });

    it('renders a dismissible banner naming the degraded services', async () => {
        let m = (await import('mithril')).default;
        let { createServiceStatusBanner } = await import('../components/serviceStatusBanner.js');
        let ctl = createServiceStatusBanner({ fetchSummary: () => Promise.resolve({ status: 'warn', degraded: ['tag', 'voice.tts'] }), now: () => 0, win: fakeWin(), redraw: () => {} });
        await ctl.start();
        let root = document.createElement('div');
        m.render(root, m(ctl.component));
        expect(root.querySelector('[data-testid="svc-banner-text"]').textContent)
            .toBe('Some services are degraded: tag, voice.tts. Ask an administrator to open Service Status.');
        root.querySelector('#serviceStatusBannerDismiss').click();
        m.render(root, m(ctl.component));
        expect(root.querySelector('#serviceStatusBanner')).toBe(null);
    });

    it('router.js starts the banner in the logged-in branch and stops it when signed out', () => {
        let src = readFileSync(resolve(here, '../router.js'), 'utf-8');
        let appIdx = src.indexOf('await am7client.application()');
        let startIdx = src.indexOf('serviceStatusBanner.start()');
        let stopIdx = src.indexOf('serviceStatusBanner.stop()');
        let nullIdx = src.indexOf('if (usr == null)');
        expect(appIdx).toBeGreaterThan(0);
        expect(startIdx).toBeGreaterThan(appIdx);
        expect(stopIdx).toBeGreaterThan(nullIdx);
        expect(stopIdx).toBeLessThan(appIdx);
    });
});

// ── Wiring ──────────────────────────────────────────────────────────

describe('serviceStatus feature wiring', () => {
    it('manifest entry exists in both byte-identical manifests with the design data', () => {
        let ux = readFileSync(resolve(here, '../features.manifest.json'));
        let obj = readFileSync(resolve(here, '../../../AccountManagerObjects7/src/main/resources/features/uxFeatureManifest.json'));
        expect(Buffer.compare(ux, obj)).toBe(0);
        let entry = JSON.parse(ux.toString('utf-8')).find(e => e.id === 'serviceStatus');
        expect(entry).toEqual({
            id: 'serviceStatus', label: 'Service Status',
            description: 'Live status of the database, deployment servers and readable connections (admin only)',
            required: false, deps: ['core']
        });
    });

    it('is a tab of the Admin panel (no menu item of its own), the route registers at /admin/status, and the prefix owns it', async () => {
        let { features, initFeatures, getMenuItems, featureForPath, getManifestErrors } = await import('../features.js');
        let { visibleAdminTabs } = await import('../features/systemAdmin/adminTabs.js');
        let f = features.serviceStatus;
        expect(f).toBeDefined();
        expect(f.required).toBe(false);
        expect(f.deps).toEqual(['core']);
        expect(f.routePrefixes).toEqual(['/admin/status']);
        expect(f.menuItems).toEqual([]);
        expect(featureForPath('/admin/status')).toBe('serviceStatus');
        expect(featureForPath('/admin/system')).toBe('core');
        expect(getManifestErrors()).toEqual([]);

        initFeatures('minimal');
        expect(getMenuItems('aside').some(i => i.route === '/admin/status')).toBe(false);
        expect(visibleAdminTabs().some(t => t.route === '/admin/status')).toBe(false);
        initFeatures(['core', 'serviceStatus']);
        expect(getMenuItems('aside').some(i => i.route === '/admin/status')).toBe(false);
        expect(visibleAdminTabs().some(t => t.route === '/admin/status')).toBe(true);
        initFeatures('standard');

        let routes = (await import('../features/serviceStatus/routes.js')).routes;
        expect(Object.keys(routes)).toEqual(['/admin/status']);
        expect(typeof routes['/admin/status'].view).toBe('function');
    });

    it('is in the enterprise and compliance profiles (and full), not in standard/minimal', async () => {
        let { profiles } = await import('../features.js');
        expect(profiles.enterprise).toContain('serviceStatus');
        expect(profiles.compliance).toContain('serviceStatus');
        expect(profiles.full).toContain('serviceStatus');
        expect(profiles.standard).not.toContain('serviceStatus');
        expect(profiles.minimal).not.toContain('serviceStatus');
    });
});
