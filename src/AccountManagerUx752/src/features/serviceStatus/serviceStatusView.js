/**
 * Service Status (/admin/status, feature id `serviceStatus`) — design of record:
 * aiDocs/ServiceStatusDesign.md (§3 checks, §6 propagation, §7 REST, §8 Ux).
 *
 *   Header:  overall status chip, last checked, Deep checkbox, Run, the propagation note, and an
 *            incomplete-view banner when the report is complete:false.
 *   Table 1: Deployment services — db, embedding, embedding.config, vectors, sd, face, tag, voice.tts,
 *            voice.stt. The server removes `vectors` (and redacts the URLs) for callers who are not
 *            /System administrators; this view only renders what it was given and never synthesizes a row.
 *   Table 2: Connections — every system.connection the caller can read, with per-row Test / Deep.
 *
 * Status strings are compared case-insensitively (enums are lowercase on the wire, but list projections
 * and hand-built maps may differ). The client gate here is cosmetic, as in systemAdminView: the server's
 * @RolesAllowed admin + requireAdministrator is the boundary.
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { serviceStatusClient } from './serviceStatusClient.js';

export const DEPLOYMENT_ORDER = ['db', 'embedding', 'embedding.config', 'vectors', 'sd', 'face', 'tag', 'voice.tts', 'voice.stt'];

export const MSG_FORBIDDEN = 'Administrator role required';
export const MSG_BUSY = 'A deep test is already running';

// Shown only when the report does not carry its own `propagation` text (design §6).
export const DEFAULT_PROPAGATION = 'Edits made through the generic model API, the console or another server instance '
    + 'appear here after at most 30 s (server configuration cache) + 60 s (this report), or on the next Run.';

// ── Pure helpers (exported for tests) ───────────────────────────────

/** Lowercased status, or 'unknown'. */
export function normStatus(s) {
    if (s === null || s === undefined || s === '') return 'unknown';
    return String(s).toLowerCase();
}

/** Tailwind classes for a status chip. */
export function statusClass(s) {
    switch (normStatus(s)) {
        case 'ok': return 'bg-green-100 dark:bg-green-900 text-green-800 dark:text-green-200';
        case 'warn':
        case 'degraded': return 'bg-amber-100 dark:bg-amber-900 text-amber-800 dark:text-amber-200';
        case 'fail': return 'bg-red-100 dark:bg-red-900 text-red-800 dark:text-red-200';
        case 'skip': return 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300';
        default: return 'bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300';
    }
}

/** Human label for the overall report status. */
export function overallLabel(s) {
    switch (normStatus(s)) {
        case 'ok': return 'All services OK';
        case 'warn':
        case 'degraded': return 'Degraded';
        case 'fail': return 'Failing';
        case 'skip': return 'Skipped';
        default: return 'Unknown';
    }
}

/** The deployment checks array, whichever key the report uses. */
export function deploymentChecks(report) {
    if (!report) return [];
    let list = report.deployment || report.checks || report.services || [];
    return Array.isArray(list) ? list : [];
}

/**
 * Deployment rows in the design's fixed order (unknown extra names appended in report order). Only rows
 * present in the report are returned — a missing `vectors` row (non-/System admin) stays missing.
 */
export function buildRows(report) {
    let checks = deploymentChecks(report).filter(c => c && c.name);
    let rank = n => { let i = DEPLOYMENT_ORDER.indexOf(String(n)); return i < 0 ? DEPLOYMENT_ORDER.length : i; };
    return checks
        .map((c, i) => ({ c, i }))
        .sort((a, b) => (rank(a.c.name) - rank(b.c.name)) || (a.i - b.i))
        .map(x => x.c);
}

/** Connection rows from a report or from the GET /connections body (array or {connections:[]}). */
export function buildConnectionRows(body) {
    if (!body) return [];
    if (Array.isArray(body)) return body.filter(Boolean);
    return Array.isArray(body.connections) ? body.connections.filter(Boolean) : [];
}

export function isRedacted(check) {
    return !!(check && (check.urlRedacted || (check.target && check.target.urlRedacted)));
}

/** URL cell text: the target URL, or "configured in /System" when the server redacted it. */
export function urlText(check) {
    if (isRedacted(check)) return 'configured in /System';
    let t = check && check.target;
    return (t && t.url) ? String(t.url) : '';
}

export function latencyText(ms) {
    if (ms === null || ms === undefined || ms === '' || isNaN(Number(ms))) return '';
    return Math.round(Number(ms)) + ' ms';
}

export function formatDate(ms) {
    if (ms === null || ms === undefined || ms === 0 || ms === '') return '';
    let d = new Date(typeof ms === 'number' ? ms : (isNaN(Number(ms)) ? ms : Number(ms)));
    return isNaN(d.getTime()) ? String(ms) : d.toLocaleString();
}

/** "Used by" cell: referencedBy may be strings or {name}/{objectId} records. */
export function usedByText(conn) {
    let refs = conn && (conn.referencedBy || conn.usedBy);
    if (!Array.isArray(refs) || !refs.length) return '';
    return refs.map(r => (r && typeof r === 'object') ? (r.name || r.objectId || ('#' + r.id)) : String(r)).join(', ');
}

/** The incomplete banner's list: undetermined may be strings or {name, objectId, id, reason}. */
export function undeterminedNames(report) {
    let u = report && report.undetermined;
    if (!Array.isArray(u)) return [];
    return u.map(x => (x && typeof x === 'object') ? (x.name || x.objectId || (x.id !== undefined ? '#' + x.id : '')) : String(x)).filter(Boolean);
}

export function hasApiKeyText(conn) {
    if (!conn || conn.hasApiKey === undefined || conn.hasApiKey === null) return '';
    return conn.hasApiKey ? 'set' : 'none';
}

/** Detail cell text, including the bounded:false note. Never anything but what the server sent. */
export function detailText(check) {
    if (!check) return '';
    let parts = [];
    if (check.detail) parts.push(String(check.detail));
    else if (check.reason) parts.push(String(check.reason));
    if (check.bounded === false) parts.push('(unbounded: this check may wait past its timeout)');
    return parts.join(' ');
}

/** Map a rejected m.request to the message shown to the admin. */
export function errorText(e, fallback) {
    let code = e && typeof e === 'object' ? e.code : undefined;
    if (code === 403) return MSG_FORBIDDEN;
    if (code === 409) return MSG_BUSY;
    if (code === 404) return 'Not found or not readable';
    if (code === 400) return 'Unknown check name';
    if (e && typeof e === 'object') {
        if (e.error && typeof e.error === 'string') return e.error;
        if (code === 401) return 'Not signed in';
        if (e.message) return e.message;
    }
    if (typeof e === 'string') return e;
    return fallback;
}

/** Merge one probe result over an existing row, keeping the row's descriptive fields. */
export function mergeResult(row, result) {
    if (!result || typeof result !== 'object') return row;
    return Object.assign({}, row || {}, result);
}

// ── State ───────────────────────────────────────────────────────────

let state = freshState();

function freshState() {
    return {
        report: null,
        connections: [],
        loading: false,
        running: false,
        error: null,
        deep: false,
        busy: {},       // key ('svc:<name>' | 'conn:<objectId>') -> 'test' | 'deep'
        rowError: {}    // same keys -> message
    };
}

/** Test hooks: reset / read / seed the module state. */
export function resetState() { state = freshState(); }
export function getState() { return state; }
export function setReport(report) {
    state.report = report || null;
    state.connections = buildConnectionRows(report);
}

function isAdmin() {
    let ctx = page.context && page.context();
    return !!(ctx && ctx.roles && ctx.roles.admin);
}

// ── API ─────────────────────────────────────────────────────────────

export async function loadLast() {
    state.loading = true;
    state.error = null;
    m.redraw();
    try {
        let r = await serviceStatusClient.last();
        setReport(r);
        if (!r || !Array.isArray(r.connections)) {
            state.connections = buildConnectionRows(await serviceStatusClient.connections());
        }
    } catch (e) {
        state.error = errorText(e, 'Could not load the service status.');
    }
    state.loading = false;
    m.redraw();
}

export async function runCheck(deep) {
    if (state.running) return;
    state.running = true;
    state.error = null;
    m.redraw();
    try {
        let r = await serviceStatusClient.run(!!deep);
        setReport(r);
        if (!r || !Array.isArray(r.connections)) {
            state.connections = buildConnectionRows(await serviceStatusClient.connections());
        }
    } catch (e) {
        state.error = errorText(e, 'The status run failed.');
    }
    state.running = false;
    m.redraw();
}

export async function testConnection(objectId, deep) {
    let key = 'conn:' + objectId;
    if (state.busy[key]) return;
    state.busy[key] = deep ? 'deep' : 'test';
    delete state.rowError[key];
    m.redraw();
    try {
        let r = await serviceStatusClient.testConnection(objectId, !!deep);
        state.connections = state.connections.map(c => (c && c.objectId === objectId) ? mergeResult(c, r) : c);
    } catch (e) {
        state.rowError[key] = errorText(e, 'Test failed.');
    }
    delete state.busy[key];
    m.redraw();
}

export async function testServer(name, deep) {
    let key = 'svc:' + name;
    if (state.busy[key]) return;
    state.busy[key] = deep ? 'deep' : 'test';
    delete state.rowError[key];
    m.redraw();
    try {
        let r = await serviceStatusClient.testServer(name, !!deep);
        if (state.report) {
            let field = state.report.deployment ? 'deployment' : (state.report.checks ? 'checks' : (state.report.services ? 'services' : 'deployment'));
            let list = deploymentChecks(state.report);
            state.report = Object.assign({}, state.report, {
                [field]: list.map(c => (c && c.name === name) ? mergeResult(c, r) : c)
            });
        }
    } catch (e) {
        state.rowError[key] = errorText(e, 'Test failed.');
    }
    delete state.busy[key];
    m.redraw();
}

// ── Rendering ───────────────────────────────────────────────────────

function chip(s, attrs) {
    return m('span', Object.assign({ class: 'text-xs px-1.5 py-0.5 rounded font-mono ' + statusClass(s), 'data-status': normStatus(s) }, attrs || {}),
        normStatus(s).toUpperCase());
}

function rowButton(label, icon, onclick, opts) {
    let busy = !!opts.busy;
    return m('button', {
        'data-testid': opts.testid,
        class: 'inline-flex items-center gap-1 px-2 py-1 rounded text-xs font-medium '
            + (busy || opts.disabled ? 'bg-blue-400 text-white opacity-70 cursor-not-allowed' : 'bg-blue-600 text-white hover:bg-blue-700'),
        disabled: busy || !!opts.disabled,
        onclick: (busy || opts.disabled) ? null : onclick
    }, [
        m('span', { class: 'material-symbols-outlined' + (busy ? ' animate-spin' : ''), style: 'font-size:14px' }, busy ? 'progress_activity' : icon),
        label
    ]);
}

function errorBox(msg, testid) {
    return msg ? m('div', { 'data-testid': testid, class: 'p-2 rounded text-sm bg-red-200 text-black dark:bg-red-700 dark:text-white' }, msg) : null;
}

const TH = 'py-1 pr-2';
const TD = 'py-1 pr-2 align-top';

function header() {
    let r = state.report;
    let undetermined = undeterminedNames(r);
    return m('div', { class: 'space-y-3' }, [
        m('div', { class: 'flex flex-wrap items-center gap-3' }, [
            m('h1', { class: 'text-2xl font-bold text-gray-800 dark:text-white' }, 'Service Status'),
            r ? m('span', { 'data-testid': 'svc-overall', 'data-status': normStatus(r.status),
                class: 'text-sm px-2 py-0.5 rounded font-medium ' + statusClass(r.status) }, overallLabel(r.status)) : null
        ]),
        m('div', { class: 'flex flex-wrap items-center gap-4 text-sm text-gray-700 dark:text-gray-300' }, [
            m('span', { 'data-testid': 'svc-checked-at' }, r && r.checkedAt ? 'Last checked ' + formatDate(r.checkedAt) : 'Not checked yet'),
            m('label', { class: 'flex items-center gap-1 cursor-pointer' }, [
                m('input', { type: 'checkbox', id: 'serviceStatusDeep', checked: state.deep, onchange: e => { state.deep = !!e.target.checked; } }),
                'Deep (one real request per check, 30 s timeout)'
            ]),
            m('button', {
                id: 'serviceStatusRun',
                class: 'inline-flex items-center gap-2 px-3 py-2 rounded text-sm font-medium '
                    + (state.running ? 'bg-blue-400 text-white opacity-70 cursor-not-allowed' : 'bg-blue-600 text-white hover:bg-blue-700'),
                disabled: state.running,
                onclick: state.running ? null : () => runCheck(state.deep)
            }, [
                m('span', { class: 'material-symbols-outlined' + (state.running ? ' animate-spin' : ''), style: 'font-size:18px' }, state.running ? 'progress_activity' : 'play_arrow'),
                'Run'
            ])
        ]),
        m('p', { 'data-testid': 'svc-propagation', class: 'text-xs text-gray-500 dark:text-gray-400' }, (r && r.propagation) ? String(r.propagation) : DEFAULT_PROPAGATION),
        (r && r.complete === false) ? m('div', {
            'data-testid': 'svc-incomplete',
            class: 'p-3 rounded text-sm bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-800 text-amber-800 dark:text-amber-200'
        }, [
            m('div', { class: 'font-medium' }, 'Incomplete view: some referenced connections could not be read by you, so their status is undetermined.'),
            undetermined.length ? m('div', { 'data-testid': 'svc-undetermined' }, 'Undetermined: ' + undetermined.join(', ')) : null
        ]) : null,
        errorBox(state.error, 'svc-error')
    ]);
}

function deploymentTable() {
    let rows = buildRows(state.report);
    return m('div', { class: 'p-4 rounded-lg bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 space-y-2' }, [
        m('h2', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, 'Deployment services'),
        !rows.length ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, state.loading ? 'Loading…' : 'No deployment checks reported. Press Run.') :
        m('div', { class: 'overflow-x-auto' }, m('table', { id: 'serviceStatusDeployment', class: 'w-full text-xs' }, [
            m('thead', m('tr', { class: 'text-left text-gray-500 dark:text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                m('th', { class: TH }, 'Service'),
                m('th', { class: TH }, 'Status'),
                m('th', { class: TH }, 'Type'),
                m('th', { class: TH }, 'URL'),
                m('th', { class: TH }, 'Detail'),
                m('th', { class: TH }, 'Latency'),
                m('th', { class: TH }, '')
            ])),
            m('tbody', rows.map(c => {
                let key = 'svc:' + c.name;
                let busy = state.busy[key];
                let t = c.target || {};
                return m('tr', { key: c.name, 'data-testid': 'svc-row-' + c.name, class: 'border-b border-gray-100 dark:border-gray-800' }, [
                    m('td', { class: TD + ' font-mono' }, c.name),
                    m('td', { class: TD, 'data-testid': 'svc-status-' + c.name, 'data-status': normStatus(c.status) }, chip(c.status)),
                    m('td', { class: TD + ' font-mono' }, t.type || c.type || ''),
                    m('td', { class: TD + ' font-mono break-all', 'data-testid': 'svc-url-' + c.name }, urlText(c)),
                    m('td', { class: TD + ' break-words', 'data-testid': 'svc-detail-' + c.name }, [
                        detailText(c),
                        state.rowError[key] ? m('div', { class: 'text-red-700 dark:text-red-300', 'data-testid': 'svc-error-' + c.name }, state.rowError[key]) : null
                    ]),
                    m('td', { class: TD + ' whitespace-nowrap' }, latencyText(c.latencyMs)),
                    m('td', { class: TD + ' whitespace-nowrap space-x-1' }, [
                        rowButton('Test', 'network_check', () => testServer(c.name, false), { busy: busy === 'test', disabled: !!busy, testid: 'svc-test-' + c.name }),
                        rowButton('Deep', 'troubleshoot', () => testServer(c.name, true), { busy: busy === 'deep', disabled: !!busy, testid: 'svc-deep-' + c.name })
                    ])
                ]);
            }))
        ]))
    ]);
}

function connectionTable() {
    let rows = state.connections;
    return m('div', { class: 'p-4 rounded-lg bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 space-y-2' }, [
        m('h2', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, 'Connections'),
        !rows.length ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, state.loading ? 'Loading…' : 'No readable connections.') :
        m('div', { class: 'overflow-x-auto' }, m('table', { id: 'serviceStatusConnections', class: 'w-full text-xs' }, [
            m('thead', m('tr', { class: 'text-left text-gray-500 dark:text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                m('th', { class: TH }, 'Name'),
                m('th', { class: TH }, 'Group'),
                m('th', { class: TH }, 'Dialect'),
                m('th', { class: TH }, 'Upstream'),
                m('th', { class: TH }, 'API key'),
                m('th', { class: TH }, 'Used by'),
                m('th', { class: TH }, 'Status'),
                m('th', { class: TH }, 'Detail'),
                m('th', { class: TH }, 'Latency'),
                m('th', { class: TH }, '')
            ])),
            m('tbody', rows.map(c => {
                let oid = c.objectId;
                let key = 'conn:' + oid;
                let busy = state.busy[key];
                return m('tr', { key: oid || c.name, 'data-testid': 'conn-row-' + oid, class: 'border-b border-gray-100 dark:border-gray-800' }, [
                    m('td', { class: TD }, c.name || ''),
                    m('td', { class: TD + ' font-mono' }, c.groupPath || c.group || ''),
                    m('td', { class: TD + ' font-mono' }, c.dialect ? String(c.dialect).toUpperCase() : ''),
                    m('td', { class: TD + ' font-mono' }, c.upstream ? String(c.upstream).toUpperCase() : ''),
                    m('td', { class: TD, 'data-testid': 'conn-key-' + oid }, hasApiKeyText(c)),
                    m('td', { class: TD }, usedByText(c)),
                    m('td', { class: TD, 'data-testid': 'conn-status-' + oid, 'data-status': normStatus(c.status) }, c.status ? chip(c.status) : m('span', { class: 'text-gray-400' }, 'not tested')),
                    m('td', { class: TD + ' break-words', 'data-testid': 'conn-detail-' + oid }, [
                        detailText(c),
                        state.rowError[key] ? m('div', { class: 'text-red-700 dark:text-red-300', 'data-testid': 'conn-error-' + oid }, state.rowError[key]) : null
                    ]),
                    m('td', { class: TD + ' whitespace-nowrap' }, latencyText(c.latencyMs)),
                    m('td', { class: TD + ' whitespace-nowrap space-x-1' }, [
                        rowButton('Test', 'network_check', () => testConnection(oid, false), { busy: busy === 'test', disabled: !!busy || !oid, testid: 'conn-test-' + oid }),
                        rowButton('Deep', 'troubleshoot', () => testConnection(oid, true), { busy: busy === 'deep', disabled: !!busy || !oid, testid: 'conn-deep-' + oid })
                    ])
                ]);
            }))
        ]))
    ]);
}

/** The panel body (exported so tests can render it without the route/layout). */
export function renderPanel() {
    if (!isAdmin()) {
        return m('div', { class: 'max-w-5xl mx-auto p-6' }, [
            m('div', {
                'data-testid': 'svc-forbidden',
                class: 'p-4 rounded-lg bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-800 text-amber-800 dark:text-amber-200 text-sm'
            }, MSG_FORBIDDEN + '.')
        ]);
    }
    return m('div', { id: 'serviceStatusPanel', class: 'max-w-6xl mx-auto p-6 space-y-6' }, [
        header(),
        deploymentTable(),
        connectionTable()
    ]);
}

export const serviceStatusView = {
    oninit: function () {
        let deep = state.deep;
        resetState();
        state.deep = deep;
        if (isAdmin()) loadLast();
    },
    view: function () {
        return renderPanel();
    }
};

export default serviceStatusView;
