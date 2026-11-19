/**
 * Admin panel (/admin/system, part of core) — administrative maintenance commands over /rest/admin:
 *
 *   Thumbnails: count / wipe every data.thumbnail (they are a derived cache and regenerate on demand).
 *   Audit:      list / search / view system.audit records; trim to the last N days; purge.
 *
 * Scope: "This organization" or, for administrators of the /System organization only, "All
 * organizations" (a per-organization role - even SystemAdministrators - reaches no further than its own
 * organization). The
 * organization is derived server-side from the principal; the client sends ONE boolean and never an
 * organization id. Whether the all-organizations option is offered is taken from GET /rest/admin/scope,
 * not from the client role context — the server is the authority (the client gate here is cosmetic,
 * as in olioAdminView).
 *
 * Every destructive command is confirmed through page.components.dialog.confirm({destructive:true}) and
 * the server journals the command itself as a PERMIT audit (so a purge leaves exactly one record: the
 * one that says who purged it).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { systemAdminClient } from './systemAdminClient.js';

const ACTIONS = ['', 'ADD', 'EXECUTE', 'DELETE', 'MODIFY', 'PATCH', 'READ', 'REQUEST', 'RESPONSE', 'GRANT', 'DENY',
    'REJECT', 'APPROVE', 'AUTHENTICATE', 'AUTHORIZE', 'CLOSE', 'OPEN', 'REOPEN', 'VECTORIZE', 'PAGE_INDEX', 'EXPORT', 'UNKNOWN'];
const RESPONSES = ['', 'PERMIT', 'DENY', 'VALID', 'INVALID', 'INFO', 'PENDING', 'UNKNOWN'];
const PAGE_SIZES = [25, 50, 100];

// ── State ───────────────────────────────────────────────────────────

let scope = null;            // GET /scope body
let scopeError = null;
let allOrganizations = false;

let thumbs = { count: null, loading: false, result: null, error: null };

let audit = {
    count: null,
    countLoading: false,
    filter: { text: '', action: '', response: '', resourceType: '', since: '', until: '' },
    pageSize: 25,
    startRecord: 0,
    results: null,           // array of audit records (list projection)
    totalCount: 0,
    searching: false,
    searchError: null,
    trimDays: 30,
    working: false,          // trim / purge in flight
    result: null,            // last trim/purge result
    error: null,
    detail: null,
    detailLoading: false
};

function isAdmin() {
    let ctx = page.context && page.context();
    return !!(ctx && ctx.roles && ctx.roles.admin);
}

function errorText(e, fallback) {
    // Mithril extends the rejected Error with the JSON body's fields, so {"error":"..."} surfaces as e.error.
    if (e && typeof e === 'object') {
        if (e.error && typeof e.error === 'string') return e.error;
        if (e.message) return e.message;
        if (e.code === 401 || e.code === 403) return 'Not permitted.';
    }
    if (typeof e === 'string') return e;
    return fallback;
}

/** Reset every scope-dependent result when the scope changes. Exported for tests. */
export function resetScopedState() {
    thumbs = { count: null, loading: false, result: null, error: null };
    audit.count = null;
    audit.results = null;
    audit.totalCount = 0;
    audit.startRecord = 0;
    audit.result = null;
    audit.error = null;
    audit.searchError = null;
}

/** Build the POST /audit/search body from the panel state. Exported for tests. */
export function buildAuditSearch(state, all) {
    let f = state.filter;
    let body = {
        allOrganizations: !!all,
        startRecord: state.startRecord,
        recordCount: state.pageSize
    };
    if (f.text && f.text.trim()) body.text = f.text.trim();
    if (f.action) body.action = f.action;
    if (f.response) body.response = f.response;
    if (f.resourceType && f.resourceType.trim()) body.resourceType = f.resourceType.trim();
    // <input type="date"> yields "YYYY-MM-DD" in local time; since = start of that day, until = end of it.
    if (f.since) body.since = new Date(f.since + 'T00:00:00').getTime();
    if (f.until) body.until = new Date(f.until + 'T23:59:59.999').getTime();
    return body;
}

export function formatDate(ms) {
    if (ms === null || ms === undefined || ms === 0) return '';
    let d = new Date(typeof ms === 'number' ? ms : Number(ms));
    return isNaN(d.getTime()) ? String(ms) : d.toLocaleString();
}

// ── API ─────────────────────────────────────────────────────────────

async function loadScope() {
    scopeError = null;
    try {
        scope = await systemAdminClient.scope();
        if (!scope || !scope.allOrganizationsAllowed) allOrganizations = false;
    } catch (e) {
        scope = null;
        scopeError = errorText(e, 'Could not load the maintenance scope.');
    }
    m.redraw();
}

async function loadThumbnailCount() {
    thumbs.loading = true;
    thumbs.error = null;
    m.redraw();
    try {
        let r = await systemAdminClient.countThumbnails(allOrganizations);
        thumbs.count = r;
    } catch (e) {
        thumbs.error = errorText(e, 'Could not count thumbnails.');
    }
    thumbs.loading = false;
    m.redraw();
}

async function wipeThumbnails() {
    if (thumbs.loading) return;
    let ok = await page.components.dialog.confirm({
        title: 'Wipe thumbnails',
        message: 'Delete every thumbnail in ' + scopeLabel() + '? They are regenerated on demand the next time an image is viewed.',
        confirmLabel: 'Wipe thumbnails',
        confirmIcon: 'delete_sweep',
        destructive: true
    });
    if (!ok) return;
    thumbs.loading = true;
    thumbs.error = null;
    thumbs.result = null;
    m.redraw();
    try {
        thumbs.result = await systemAdminClient.cleanupThumbnails(allOrganizations);
        thumbs.count = await systemAdminClient.countThumbnails(allOrganizations);
    } catch (e) {
        thumbs.error = errorText(e, 'Thumbnail cleanup failed.');
    }
    thumbs.loading = false;
    m.redraw();
}

async function loadAuditCount() {
    audit.countLoading = true;
    audit.error = null;
    m.redraw();
    try {
        audit.count = await systemAdminClient.countAudit(allOrganizations);
    } catch (e) {
        audit.error = errorText(e, 'Could not count audit records.');
    }
    audit.countLoading = false;
    m.redraw();
}

async function searchAudit(resetPage) {
    if (resetPage) audit.startRecord = 0;
    audit.searching = true;
    audit.searchError = null;
    m.redraw();
    try {
        let qr = await systemAdminClient.searchAudit(buildAuditSearch(audit, allOrganizations));
        let results = (qr && Array.isArray(qr.results)) ? qr.results : [];
        // Lists may carry `schema` only on the first item — restore it (model-api.md).
        if (results.length && results[0].schema) {
            let s = results[0].schema;
            results.forEach(r => { if (!r.schema) r.schema = s; });
        }
        audit.results = results;
        audit.totalCount = (qr && typeof qr.totalCount === 'number') ? qr.totalCount : results.length;
    } catch (e) {
        audit.results = null;
        audit.searchError = errorText(e, 'Audit search failed.');
    }
    audit.searching = false;
    m.redraw();
}

async function openAuditDetail(objectId) {
    audit.detailLoading = true;
    audit.detail = null;
    m.redraw();
    try {
        audit.detail = await systemAdminClient.getAudit(objectId, allOrganizations);
        page.components.dialog.open({
            title: 'Audit record',
            size: 'lg',
            content: m('div', { id: 'systemAdminAuditDetail' }, renderAuditDetail(audit.detail)),
            closable: true,
            actions: [{ label: 'Close', icon: 'close', onclick: function () { page.components.dialog.close(); } }]
        });
    } catch (e) {
        audit.searchError = errorText(e, 'Could not load the audit record.');
    }
    audit.detailLoading = false;
    m.redraw();
}

async function trimAudit() {
    if (audit.working) return;
    let days = parseInt(audit.trimDays, 10);
    if (!(days >= 1)) {
        audit.error = 'Days must be a whole number of at least 1.';
        m.redraw();
        return;
    }
    let ok = await page.components.dialog.confirm({
        title: 'Trim audit records',
        message: 'Delete every audit record older than ' + days + ' day' + (days === 1 ? '' : 's') + ' in ' + scopeLabel() + '? This cannot be undone.',
        confirmLabel: 'Trim',
        confirmIcon: 'content_cut',
        destructive: true
    });
    if (!ok) return;
    await runAuditDelete(() => systemAdminClient.trimAudit(allOrganizations, days), 'Audit trim failed.');
}

async function purgeAudit() {
    if (audit.working) return;
    let ok = await page.components.dialog.confirm({
        title: 'Purge audit records',
        message: 'Delete EVERY audit record in ' + scopeLabel() + '? This cannot be undone. The purge itself is recorded as the one remaining audit entry.',
        confirmLabel: 'Purge all',
        confirmIcon: 'delete_forever',
        destructive: true
    });
    if (!ok) return;
    await runAuditDelete(() => systemAdminClient.purgeAudit(allOrganizations), 'Audit purge failed.');
}

async function runAuditDelete(fn, failMsg) {
    audit.working = true;
    audit.error = null;
    audit.result = null;
    m.redraw();
    try {
        audit.result = await fn();
        audit.count = await systemAdminClient.countAudit(allOrganizations);
        if (audit.results) await searchAudit(true);
    } catch (e) {
        audit.error = errorText(e, failMsg);
    }
    audit.working = false;
    m.redraw();
}

function setScope(all) {
    if (allOrganizations === all) return;
    allOrganizations = all;
    resetScopedState();
    m.redraw();
}

// ── Rendering ───────────────────────────────────────────────────────

function scopeLabel() {
    if (allOrganizations && scope) {
        let n = Array.isArray(scope.organizations) ? scope.organizations.length : 0;
        return 'all ' + n + ' organization' + (n === 1 ? '' : 's');
    }
    return (scope && scope.organizationPath) ? scope.organizationPath : 'this organization';
}

function card(title, icon, children) {
    return m('div', { class: 'p-4 rounded-lg bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 space-y-3' }, [
        m('h2', { class: 'text-lg font-semibold text-gray-800 dark:text-white flex items-center gap-2' }, [
            m('span', { class: 'material-symbols-outlined', style: 'font-size:20px' }, icon),
            title
        ]),
        children
    ]);
}

function errorBox(msg) {
    return msg ? m('div', { class: 'p-2 rounded text-sm bg-red-200 text-black dark:bg-red-700 dark:text-white' }, msg) : null;
}

function successBox(msg) {
    return msg ? m('div', { class: 'p-2 rounded text-sm bg-green-200 text-black dark:bg-green-800 dark:text-white' }, msg) : null;
}

function button(label, icon, onclick, opts) {
    opts = opts || {};
    let busy = !!opts.busy;
    let base = opts.destructive
        ? (busy ? 'bg-red-400 text-white opacity-70 cursor-not-allowed' : 'bg-red-600 text-white hover:bg-red-700')
        : (busy ? 'bg-blue-400 text-white opacity-70 cursor-not-allowed' : 'bg-blue-600 text-white hover:bg-blue-700');
    return m('button', {
        id: opts.id,
        class: 'inline-flex items-center gap-2 px-3 py-2 rounded text-sm font-medium ' + base,
        disabled: busy || !!opts.disabled,
        onclick: (busy || opts.disabled) ? null : onclick
    }, [
        m('span', { class: 'material-symbols-outlined' + (busy ? ' animate-spin' : ''), style: 'font-size:18px' }, busy ? 'progress_activity' : icon),
        label
    ]);
}

function perOrgTable(result) {
    if (!result || !Array.isArray(result.organizations)) return null;
    if (result.organizations.length <= 1) return null;
    return m('table', { class: 'text-xs w-full mt-1' }, [
        m('thead', m('tr', { class: 'text-left text-gray-500 dark:text-gray-400' }, [m('th', 'Organization'), m('th', { class: 'text-right' }, 'Count')])),
        m('tbody', result.organizations.map(o => m('tr', { key: o.id }, [
            m('td', { class: 'pr-2' }, o.path || ('#' + o.id)),
            m('td', { class: 'text-right' }, String(o.count == null ? 0 : o.count))
        ])))
    ]);
}

function scopeControl() {
    if (!scope) return null;
    let canAll = !!scope.allOrganizationsAllowed;
    let n = Array.isArray(scope.organizations) ? scope.organizations.length : 0;
    return m('div', { class: 'flex flex-wrap items-center gap-4 text-sm text-gray-700 dark:text-gray-300' }, [
        m('span', { class: 'font-medium' }, 'Scope:'),
        m('label', { class: 'flex items-center gap-1 cursor-pointer' }, [
            m('input', { type: 'radio', name: 'systemAdminScope', id: 'systemAdminScopeOrg', checked: !allOrganizations, onchange: () => setScope(false) }),
            'This organization (' + (scope.organizationPath || '') + ')'
        ]),
        m('label', {
            class: 'flex items-center gap-1 ' + (canAll ? 'cursor-pointer' : 'opacity-50 cursor-not-allowed'),
            title: canAll ? '' : 'Only administrators of the /System organization can act across organizations'
        }, [
            m('input', { type: 'radio', name: 'systemAdminScope', id: 'systemAdminScopeAll', checked: allOrganizations, disabled: !canAll, onchange: () => setScope(true) }),
            'All organizations' + (canAll ? ' (' + n + ')' : '')
        ])
    ]);
}

function thumbnailSection() {
    let c = thumbs.count;
    return card('Thumbnails', 'image', [
        m('p', { class: 'text-sm text-gray-500 dark:text-gray-400' },
            'Thumbnails are a derived cache of image data. Wiping them frees storage; each is regenerated the next time its image is viewed.'),
        m('div', { class: 'flex flex-wrap items-center gap-3' }, [
            button('Count thumbnails', 'refresh', loadThumbnailCount, { id: 'systemAdminThumbCount', busy: thumbs.loading }),
            button('Wipe thumbnails', 'delete_sweep', wipeThumbnails, { id: 'systemAdminThumbWipe', busy: thumbs.loading, destructive: true }),
            c ? m('span', { id: 'systemAdminThumbTotal', class: 'text-sm text-gray-700 dark:text-gray-300' },
                String(c.total) + ' thumbnail' + (c.total === 1 ? '' : 's') + ' in ' + scopeLabel()) : null
        ]),
        perOrgTable(c),
        errorBox(thumbs.error),
        thumbs.result ? successBox('Wiped ' + thumbs.result.total + ' thumbnail' + (thumbs.result.total === 1 ? '' : 's') + '.') : null,
        thumbs.result ? perOrgTable(thumbs.result) : null
    ]);
}

function select(id, value, options, onchange) {
    return m('select', {
        id: id,
        class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm',
        value: value,
        onchange: e => onchange(e.target.value)
    }, options.map(o => m('option', { value: o, key: o || '_any' }, o || 'Any')));
}

function input(id, type, value, placeholder, oninput, extra) {
    return m('input', Object.assign({
        id: id,
        type: type,
        class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm',
        placeholder: placeholder,
        value: value,
        oninput: e => oninput(e.target.value)
    }, extra || {}));
}

function auditFilters() {
    let f = audit.filter;
    return m('form', {
        class: 'grid grid-cols-1 md:grid-cols-3 gap-2 items-end',
        onsubmit: e => { e.preventDefault(); searchAudit(true); }
    }, [
        m('label', { class: 'text-xs text-gray-600 dark:text-gray-400 md:col-span-3' }, ['Text (description or message)',
            input('systemAdminAuditText', 'text', f.text, 'contains…', v => { f.text = v; }, { class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm' })]),
        m('label', { class: 'text-xs text-gray-600 dark:text-gray-400 flex flex-col' }, ['Action', select('systemAdminAuditAction', f.action, ACTIONS, v => { f.action = v; })]),
        m('label', { class: 'text-xs text-gray-600 dark:text-gray-400 flex flex-col' }, ['Response', select('systemAdminAuditResponse', f.response, RESPONSES, v => { f.response = v; })]),
        m('label', { class: 'text-xs text-gray-600 dark:text-gray-400 flex flex-col' }, ['Resource type',
            input('systemAdminAuditResourceType', 'text', f.resourceType, 'e.g. data.data', v => { f.resourceType = v; })]),
        m('label', { class: 'text-xs text-gray-600 dark:text-gray-400 flex flex-col' }, ['From', input('systemAdminAuditSince', 'date', f.since, '', v => { f.since = v; })]),
        m('label', { class: 'text-xs text-gray-600 dark:text-gray-400 flex flex-col' }, ['To', input('systemAdminAuditUntil', 'date', f.until, '', v => { f.until = v; })]),
        m('div', { class: 'flex gap-2 items-center' }, [
            m('button', { id: 'systemAdminAuditSearch', type: 'submit', class: 'inline-flex items-center gap-2 px-3 py-2 rounded text-sm font-medium bg-blue-600 text-white hover:bg-blue-700', disabled: audit.searching }, [
                m('span', { class: 'material-symbols-outlined' + (audit.searching ? ' animate-spin' : ''), style: 'font-size:18px' }, audit.searching ? 'progress_activity' : 'search'),
                'Search'
            ]),
            select('systemAdminAuditPageSize', String(audit.pageSize), PAGE_SIZES.map(String), v => { audit.pageSize = parseInt(v, 10); })
        ])
    ]);
}

function responseChip(resp) {
    let r = String(resp || '').toUpperCase();
    let cls = r === 'PERMIT' || r === 'VALID' ? 'bg-green-100 dark:bg-green-900 text-green-800 dark:text-green-200'
        : (r === 'DENY' || r === 'INVALID' ? 'bg-red-100 dark:bg-red-900 text-red-800 dark:text-red-200'
            : 'bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300');
    return m('span', { class: 'text-xs px-1.5 py-0.5 rounded font-mono ' + cls }, r);
}

function auditResults() {
    if (audit.searchError) return errorBox(audit.searchError);
    if (!audit.results) return null;
    if (!audit.results.length) {
        return m('div', { id: 'systemAdminAuditEmpty', class: 'text-sm text-gray-500 dark:text-gray-400 py-2' }, 'No audit records match.');
    }
    let from = audit.startRecord + 1;
    let to = audit.startRecord + audit.results.length;
    let hasPrev = audit.startRecord > 0;
    let hasNext = to < audit.totalCount;
    return m('div', { class: 'space-y-2' }, [
        m('div', { class: 'flex items-center justify-between text-xs text-gray-600 dark:text-gray-400' }, [
            m('span', { id: 'systemAdminAuditRange' }, 'Showing ' + from + '–' + to + ' of ' + audit.totalCount),
            m('div', { class: 'flex gap-1' }, [
                m('button', { id: 'systemAdminAuditPrev', class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 disabled:opacity-40', disabled: !hasPrev || audit.searching,
                    onclick: () => { audit.startRecord = Math.max(0, audit.startRecord - audit.pageSize); searchAudit(false); } }, 'Prev'),
                m('button', { id: 'systemAdminAuditNext', class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 disabled:opacity-40', disabled: !hasNext || audit.searching,
                    onclick: () => { audit.startRecord = audit.startRecord + audit.pageSize; searchAudit(false); } }, 'Next')
            ])
        ]),
        m('div', { class: 'overflow-x-auto' }, m('table', { id: 'systemAdminAuditTable', class: 'w-full text-xs' }, [
            m('thead', m('tr', { class: 'text-left text-gray-500 dark:text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                m('th', { class: 'py-1 pr-2' }, 'When'),
                m('th', { class: 'py-1 pr-2' }, 'Response'),
                m('th', { class: 'py-1 pr-2' }, 'Action'),
                m('th', { class: 'py-1 pr-2' }, 'Resource'),
                m('th', { class: 'py-1 pr-2' }, 'Summary')
            ])),
            m('tbody', audit.results.map(r => m('tr', {
                key: r.objectId,
                class: 'border-b border-gray-100 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer',
                'data-object-id': r.objectId,
                onclick: () => openAuditDetail(r.objectId)
            }, [
                m('td', { class: 'py-1 pr-2 whitespace-nowrap' }, formatDate(r.createdDate)),
                m('td', { class: 'py-1 pr-2' }, responseChip(r.response)),
                m('td', { class: 'py-1 pr-2 font-mono' }, String(r.action || '').toUpperCase()),
                m('td', { class: 'py-1 pr-2 font-mono' }, r.resourceType || ''),
                m('td', { class: 'py-1 pr-2 break-words' }, [
                    m('div', r.description || ''),
                    r.message ? m('div', { class: 'text-gray-500 dark:text-gray-400' }, r.message) : null
                ])
            ])))
        ]))
    ]);
}

function refRow(label, ref) {
    if (!ref) return m('div', { class: 'text-sm' }, [m('span', { class: 'text-gray-500 dark:text-gray-400' }, label + ': '), m('span', '—')]);
    let text = (ref.schema || '') + ' #' + ref.id + (ref.name ? ' "' + ref.name + '"' : '') + (ref.found === false ? ' (no longer exists)' : '');
    return m('div', { class: 'text-sm' }, [m('span', { class: 'text-gray-500 dark:text-gray-400' }, label + ': '), m('span', { class: 'font-mono' }, text)]);
}

function kv(label, value) {
    return m('div', { class: 'text-sm' }, [m('span', { class: 'text-gray-500 dark:text-gray-400' }, label + ': '), m('span', value == null ? '—' : String(value))]);
}

/** Render the GET /audit/{id} body. Exported for tests. */
export function renderAuditDetail(d) {
    let a = (d && d.audit) ? d.audit : {};
    return m('div', { class: 'space-y-1' }, [
        kv('Object id', a.objectId),
        kv('When', formatDate(a.createdDate)),
        kv('Organization', d.organizationPath),
        m('div', { class: 'text-sm' }, [m('span', { class: 'text-gray-500 dark:text-gray-400' }, 'Response: '), responseChip(a.response)]),
        kv('Action', String(a.action || '').toUpperCase()),
        refRow('Context user', d.contextUser),
        refRow('Subject', d.subject),
        refRow('Resource', d.resource),
        kv('Description', a.description),
        kv('Message', a.message),
        a.query ? m('div', { class: 'text-sm' }, [
            m('div', { class: 'text-gray-500 dark:text-gray-400' }, 'Query:'),
            m('pre', { class: 'text-xs whitespace-pre-wrap break-words p-2 rounded bg-gray-100 dark:bg-gray-800 max-h-64 overflow-auto' }, a.query)
        ]) : null,
        kv('Expires', formatDate(a.expiryDate))
    ]);
}

function auditSection() {
    let c = audit.count;
    return card('Audit', 'policy', [
        m('p', { class: 'text-sm text-gray-500 dark:text-gray-400' },
            'Every authorization decision is journaled as an audit record. Search and inspect them here, trim the journal to a retention window, or purge it. Trim and purge are themselves journaled.'),
        m('div', { class: 'flex flex-wrap items-center gap-3' }, [
            button('Count records', 'refresh', loadAuditCount, { id: 'systemAdminAuditCount', busy: audit.countLoading }),
            c ? m('span', { id: 'systemAdminAuditTotal', class: 'text-sm text-gray-700 dark:text-gray-300' },
                String(c.total) + ' audit record' + (c.total === 1 ? '' : 's') + ' in ' + scopeLabel()) : null
        ]),
        perOrgTable(c),
        errorBox(audit.error),
        audit.result ? successBox((audit.result.days ? 'Trimmed ' : 'Purged ') + audit.result.total + ' audit record' + (audit.result.total === 1 ? '' : 's')
            + (audit.result.days ? ' older than ' + audit.result.days + ' day' + (audit.result.days === 1 ? '' : 's') : '') + '.') : null,
        audit.result ? perOrgTable(audit.result) : null,

        m('h3', { class: 'text-sm font-semibold text-gray-700 dark:text-gray-300 pt-2' }, 'Search'),
        auditFilters(),
        auditResults(),

        m('h3', { class: 'text-sm font-semibold text-gray-700 dark:text-gray-300 pt-2' }, 'Retention'),
        m('div', { class: 'flex flex-wrap items-center gap-3' }, [
            m('label', { class: 'flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300' }, [
                'Keep the last',
                input('systemAdminAuditTrimDays', 'number', audit.trimDays, '', v => { audit.trimDays = v; }, { min: 1, step: 1, class: 'w-20 px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm' }),
                'days'
            ]),
            button('Trim older records', 'content_cut', trimAudit, { id: 'systemAdminAuditTrim', busy: audit.working, destructive: true }),
            button('Purge all', 'delete_forever', purgeAudit, { id: 'systemAdminAuditPurge', busy: audit.working, destructive: true })
        ])
    ]);
}

export const systemAdminView = {
    oninit: function () {
        scope = null;
        loadScope();
    },
    view: function () {
        if (!isAdmin()) {
            return m('div', { class: 'max-w-4xl mx-auto p-6' }, [
                m('div', {
                    class: 'p-4 rounded-lg bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-800 text-amber-800 dark:text-amber-200 text-sm'
                }, 'Admin requires an administrator role.')
            ]);
        }
        return m('div', { class: 'max-w-4xl mx-auto p-6 space-y-6' }, [
            m('div', [
                m('h1', { class: 'text-2xl font-bold text-gray-800 dark:text-white' }, 'Admin'),
                m('p', { class: 'text-sm text-gray-500 dark:text-gray-400 mt-1' }, 'Administrative maintenance commands for this deployment.')
            ]),
            errorBox(scopeError),
            scope ? scopeControl() : (scopeError ? null : m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'Loading scope…')),
            scope ? thumbnailSection() : null,
            scope ? auditSection() : null
        ]);
    }
};

export default systemAdminView;
