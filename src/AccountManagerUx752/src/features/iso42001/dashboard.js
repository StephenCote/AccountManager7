/**
 * ISO 42001 Dashboard (design §9A.4) — pass/flag/fail summary, recent runs/reports, pending certification
 * requests (certifiers), plus the original library-status + live policy-violation monitor from the stub.
 * Visible to any ISO role.
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { LLMConnector } from '../../chat/LLMConnector.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, verdictBadge, statusPill, sectionHeader, btn } from './iso42001Common.js';

let violations = [];
let filterText = '';
let libraryStatus = null;
let summary = null;
let recentReports = [];
let pendingRequests = [];
let loading = false;
let listenerRegistered = false;

function registerPolicyListener() {
    if (listenerRegistered) return;
    listenerRegistered = true;
    LLMConnector.onPolicyEvent(function (evt) {
        let entry = { timestamp: new Date().toISOString(), type: 'policy_violation', details: '' };
        if (evt && evt.data) {
            try {
                let parsed = typeof evt.data === 'string' ? JSON.parse(evt.data) : evt.data;
                entry.details = parsed.details || parsed.message || JSON.stringify(parsed);
                entry.severity = parsed.severity || 'warn';
            } catch (e) {
                entry.details = String(evt.data);
            }
        }
        violations.unshift(entry);
        if (violations.length > 200) violations.length = 200;
        m.redraw();
    });
}

async function loadAll() {
    loading = true;
    m.redraw();
    try { libraryStatus = await LLMConnector.checkLibrary(); } catch (e) { libraryStatus = null; }
    try { summary = await iso42001Client.dashboard(); } catch (e) { summary = null; }
    try {
        let r = await iso42001Client.list('iso42001.report',
            ['objectId', 'name', 'overallVerdict', 'status'], 0, 5);
        recentReports = (r && r.results) ? r.results : [];
    } catch (e) { recentReports = []; }
    if (isoRoles().certifier || isoRoles().admin) {
        try {
            let q = await iso42001Client.list('iso42001.certificationRequest',
                ['objectId', 'name', 'approvalStatus'], 0, 5);
            pendingRequests = (q && q.results) ? q.results.filter(r => r.approvalStatus === 'REQUEST') : [];
        } catch (e) { pendingRequests = []; }
    }
    loading = false;
    m.redraw();
}

function summaryCards() {
    let s = summary && summary.verdicts ? summary.verdicts : { PASS: 0, FLAG: 0, FAIL: 0 };
    let total = summary ? summary.reportCount : 0;
    let card = (label, val, cls) => m('div', { class: 'flex-1 rounded-lg p-4 ' + cls }, [
        m('div', { class: 'text-2xl font-bold' }, '' + (val || 0)),
        m('div', { class: 'text-sm' }, label)
    ]);
    return m('div', { class: 'flex gap-3' }, [
        card('PASS', s.PASS, 'bg-green-50 text-green-700 dark:bg-green-900/30 dark:text-green-300'),
        card('FLAG', s.FLAG, 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'),
        card('FAIL', s.FAIL, 'bg-red-50 text-red-700 dark:bg-red-900/30 dark:text-red-300'),
        card('Reports', total, 'bg-gray-50 text-gray-700 dark:bg-gray-800 dark:text-gray-300')
    ]);
}

function statusBadge(label, ok) {
    return m('div', { class: 'flex items-center gap-2 px-3 py-2 rounded bg-gray-50 dark:bg-gray-800' }, [
        m('span', { class: 'material-symbols-outlined ' + (ok ? 'text-green-500' : 'text-gray-400'), style: 'font-size:18px' }, ok ? 'check_circle' : 'cancel'),
        m('span', { class: 'text-sm text-gray-700 dark:text-gray-300' }, label)
    ]);
}

function renderReports() {
    if (recentReports.length === 0) {
        return m('div', { class: 'text-sm text-gray-400 py-2' }, 'No reports yet.');
    }
    return m('div', { class: 'flex flex-col gap-1' }, recentReports.map(r =>
        m('div', {
            key: r.objectId,
            class: 'flex items-center justify-between px-3 py-2 rounded bg-gray-50 dark:bg-gray-800 cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-700',
            onclick: () => m.route.set('/iso42001/report/' + r.objectId)
        }, [
            m('span', { class: 'text-sm text-gray-700 dark:text-gray-300 truncate' }, r.name),
            m('span', { class: 'flex items-center gap-3' }, [verdictBadge(r.overallVerdict), statusPill(r.status)])
        ])
    ));
}

function renderPending() {
    if (pendingRequests.length === 0) {
        return m('div', { class: 'text-sm text-gray-400 py-2' }, 'No pending certification requests.');
    }
    return m('div', { class: 'flex flex-col gap-1' }, pendingRequests.map(r =>
        m('div', {
            key: r.objectId,
            class: 'flex items-center justify-between px-3 py-2 rounded bg-blue-50 dark:bg-blue-900/30 cursor-pointer',
            onclick: () => m.route.set('/iso42001/cert/request/' + r.objectId)
        }, [
            m('span', { class: 'text-sm text-gray-700 dark:text-gray-300 truncate' }, r.name),
            statusPill(r.approvalStatus)
        ])
    ));
}

function renderViolations() {
    let filtered = violations;
    if (filterText) {
        let lower = filterText.toLowerCase();
        filtered = violations.filter(v => (v.details || '').toLowerCase().indexOf(lower) !== -1);
    }
    if (filtered.length === 0) {
        return m('div', { class: 'text-center py-6 text-gray-400 text-sm' }, 'No policy violations recorded in this session.');
    }
    return m('div', { class: 'flex flex-col gap-1' }, filtered.map((v, i) =>
        m('div', { key: i, class: 'flex items-start gap-2 px-3 py-2 rounded bg-gray-50 dark:bg-gray-800 text-sm' }, [
            m('span', { class: 'material-symbols-outlined shrink-0 ' + (v.severity === 'error' ? 'text-red-500' : 'text-yellow-500'), style: 'font-size:16px' }, v.severity === 'error' ? 'error' : 'warning'),
            m('div', { class: 'flex-1 min-w-0' }, [
                m('div', { class: 'text-xs text-gray-400' }, v.timestamp),
                m('div', { class: 'text-gray-700 dark:text-gray-300' }, v.details)
            ])
        ])
    ));
}

export const dashboardView = {
    oninit: function () {
        registerPolicyListener();
        loadAll();
    },
    view: function () {
        let ls = libraryStatus || {};
        let roles = isoRoles();
        return m('div', { class: 'max-w-5xl mx-auto p-6 space-y-6' }, [
            m('div', { class: 'flex items-center justify-between' }, [
                m('h1', { class: 'text-2xl font-bold text-gray-800 dark:text-white' }, 'ISO 42001 Compliance Dashboard'),
                m('div', { class: 'flex gap-2' }, [
                    (roles.tester || roles.admin) ? btn('Test Runner', 'play_circle', () => m.route.set('/iso42001/run')) : null,
                    btn('Refresh', 'refresh', loadAll)
                ])
            ]),
            m('p', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'AI system compliance evaluation, bias detection, and policy violation monitoring.'),

            summaryCards(),

            m('div', { class: 'space-y-2' }, [
                sectionHeader('System Status'),
                m('div', { class: 'grid grid-cols-2 md:grid-cols-4 gap-2' }, [
                    statusBadge('Chat Library', ls.initialized),
                    statusBadge('Prompt Library', ls.promptInitialized),
                    statusBadge('Template Library', ls.promptTemplateInitialized),
                    statusBadge('Policy Library', ls.policyInitialized)
                ])
            ]),

            m('div', { class: 'p-4 rounded-lg bg-blue-50 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800' }, [
                m('h3', { class: 'text-sm font-semibold text-blue-800 dark:text-blue-200 mb-2' }, 'Training Bias Overcorrection Active'),
                m('p', { class: 'text-xs text-blue-700 dark:text-blue-300' },
                    'All LLM call paths include the 10-area overcorrection directive per CLAUDE.md policy. ' +
                    'The swap test is applied to all character generation, narration, and compliance evaluation.')
            ]),

            m('div', { class: 'grid md:grid-cols-2 gap-6' }, [
                m('div', { class: 'space-y-2' }, [
                    sectionHeader('Recent Reports', btn('View All', 'arrow_forward', () => m.route.set('/iso42001/report'))),
                    renderReports()
                ]),
                (roles.certifier || roles.admin)
                    ? m('div', { class: 'space-y-2' }, [sectionHeader('Pending Certification Requests'), renderPending()])
                    : null
            ]),

            m('div', { class: 'space-y-2' }, [
                m('div', { class: 'flex items-center justify-between' }, [
                    m('h2', { class: 'text-lg font-semibold text-gray-700 dark:text-gray-200' }, 'Policy Violations (' + violations.length + ')'),
                    m('input', {
                        type: 'text',
                        class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm w-48',
                        placeholder: 'Filter violations...',
                        value: filterText,
                        oninput: e => { filterText = e.target.value; }
                    })
                ]),
                m('div', { class: 'max-h-96 overflow-y-auto' }, renderViolations())
            ])
        ]);
    }
};

export default dashboardView;
