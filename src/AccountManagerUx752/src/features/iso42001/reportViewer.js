/**
 * ISO 42001 Report Viewer (design §9A.7) — report list + detail (sections, certification block),
 * export PDF, request certification. Visible to Reporter/Reader/Certifier/Admin.
 * Routes: /iso42001/report (list), /iso42001/report/:reportId (detail).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, verdictBadge, statusPill, sectionHeader, loadingOrEmpty, btn } from './iso42001Common.js';

let reports = [];
let report = null;
let loading = false;
let loadedId = null;
let busy = false;
// Certification-request modal: { justification, certifierQuery, certifierResults, certifierId, certifierName }
let reqModal = null;

async function loadList() {
    loading = true; m.redraw();
    try {
        let r = await iso42001Client.list('iso42001.report',
            ['objectId', 'name', 'overallVerdict', 'status', 'reportVersion'], 0, 50);
        reports = (r && r.results) ? r.results : [];
    } catch (e) { reports = []; }
    loading = false; m.redraw();
}

async function loadOne(id) {
    loading = true; loadedId = id; m.redraw();
    try { report = await iso42001Client.getReport(id); } catch (e) { report = null; }
    loading = false; m.redraw();
}

async function exportPdf(id) {
    if (busy) return;
    busy = true; m.redraw();
    try {
        await iso42001Client.exportPdf(id);
        page.toast && page.toast('success', 'PDF exported.');
        window.open(iso42001Client.pdfUrl(id), '_blank');
    } catch (e) {
        page.toast && page.toast('error', 'Export failed: ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

function openRequestModal() {
    reqModal = { justification: '', certifierQuery: '', certifierResults: [], certifierId: null, certifierName: '' };
    m.redraw();
}

async function searchCertifiers() {
    if (!reqModal) return;
    if (!reqModal.certifierQuery.trim()) { reqModal.certifierResults = []; m.redraw(); return; }
    try {
        let r = await iso42001Client.searchUsers(reqModal.certifierQuery.trim());
        reqModal.certifierResults = (r && r.results) ? r.results : [];
    } catch (e) { reqModal.certifierResults = []; }
    m.redraw();
}

function pickCertifier(u) {
    reqModal.certifierId = u.objectId;
    reqModal.certifierName = u.name;
    reqModal.certifierResults = [];
    m.redraw();
}

async function submitRequest(id) {
    if (busy || !reqModal) return;
    busy = true; m.redraw();
    try {
        let req = await iso42001Client.requestCertification(id, reqModal.certifierId,
            reqModal.justification.trim() || 'Certification requested');
        if (req && req.objectId) {
            page.toast && page.toast('success', 'Certification requested.');
            reqModal = null;
            m.route.set('/iso42001/cert/request/' + req.objectId);
        } else {
            page.toast && page.toast('error', 'Request failed (need ISO Reporter role).');
        }
    } catch (e) {
        page.toast && page.toast('error', 'Request failed: ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

function requestModal() {
    if (!reqModal) return null;
    return m('div', {
        class: 'fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4',
        onclick: e => { if (e.target === e.currentTarget) reqModal = null; }
    }, [
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg p-6 w-full max-w-md space-y-3' }, [
            m('h3', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, 'Request Certification'),
            m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Justification'),
                m('textarea', {
                    name: 'req_justification', rows: 3,
                    class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                    value: reqModal.justification, oninput: e => { reqModal.justification = e.target.value; }
                })
            ]),
            m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Requested certifier (optional)'),
                reqModal.certifierId
                    ? m('div', { class: 'flex items-center gap-2' }, [
                        m('span', { class: 'text-sm px-2 py-1 rounded bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300' }, reqModal.certifierName),
                        btn('Clear', null, () => { reqModal.certifierId = null; reqModal.certifierName = ''; })
                    ])
                    : m('input', {
                        name: 'req_certifier', type: 'text', placeholder: 'Search users by name…',
                        class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                        value: reqModal.certifierQuery,
                        oninput: e => { reqModal.certifierQuery = e.target.value; searchCertifiers(); }
                    })
            ]),
            (reqModal.certifierResults && reqModal.certifierResults.length)
                ? m('div', { class: 'max-h-32 overflow-y-auto border border-gray-200 dark:border-gray-700 rounded' },
                    reqModal.certifierResults.map(u => m('div', {
                        key: u.objectId,
                        class: 'px-2 py-1 text-sm cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-800',
                        onclick: () => pickCertifier(u)
                    }, u.name)))
                : null,
            m('div', { class: 'flex justify-end gap-2 pt-2' }, [
                btn('Cancel', null, () => { reqModal = null; }),
                btn(busy ? '…' : 'Request', 'approval', () => submitRequest(report.objectId), { primary: true, disabled: busy })
            ])
        ])
    ]);
}

function reportRow(r) {
    return m('tr', {
        key: r.objectId,
        class: 'border-b border-gray-100 dark:border-gray-800 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800',
        onclick: () => m.route.set('/iso42001/report/' + r.objectId)
    }, [
        m('td', { class: 'px-3 py-2 text-sm truncate' }, r.name),
        m('td', { class: 'px-3 py-2' }, verdictBadge(r.overallVerdict)),
        m('td', { class: 'px-3 py-2' }, statusPill(r.status))
    ]);
}

function renderSections() {
    let sections = (report && report.sections) ? report.sections : [];
    if (!Array.isArray(sections) || sections.length === 0) {
        return m('div', { class: 'text-sm text-gray-400' }, 'No sections.');
    }
    let ordered = sections.slice().sort((a, b) => (a.sectionOrder || 0) - (b.sectionOrder || 0));
    return ordered.map(s => m('div', { key: s.objectId || s.sectionType, class: 'space-y-1' }, [
        m('h3', { class: 'text-md font-semibold text-gray-700 dark:text-gray-200 border-b border-gray-200 dark:border-gray-700 pb-1' }, s.title || s.sectionType),
        m('div', { class: 'text-sm text-gray-600 dark:text-gray-300 whitespace-pre-wrap' }, s.content || '')
    ]));
}

function certBlock() {
    let cert = report && report.certification;
    if (report && report.status === 'CERTIFIED' && cert) {
        return m('div', { class: 'rounded-lg bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 p-4 space-y-1 text-sm' }, [
            m('div', { class: 'font-semibold text-green-700 dark:text-green-300' }, '✓ CERTIFIED'),
            m('div', 'Signed by: ' + (cert.certifier && cert.certifier.name ? cert.certifier.name : '—') + (cert.certifierTitle ? ' (' + cert.certifierTitle + ')' : '')),
            cert.objectId ? btn('View Certificate', 'verified', () => m.route.set('/iso42001/cert/view/' + cert.objectId)) : null
        ]);
    }
    return m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4 text-sm text-gray-500' }, 'NOT CERTIFIED');
}

function detail() {
    let roles = isoRoles();
    let canExport = roles.reporter || roles.admin;
    let canRequest = roles.reporter && !(report && report.certification);
    return m('div', { class: 'max-w-4xl mx-auto p-6 space-y-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('div', [
                m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, (report && report.name) || 'Report'),
                m('div', { class: 'flex items-center gap-3 mt-1' }, [
                    report ? statusPill(report.status) : null,
                    report ? verdictBadge(report.overallVerdict) : null
                ])
            ]),
            btn('Reports', 'arrow_back', () => m.route.set('/iso42001/report'))
        ]),
        m('div', { class: 'flex gap-2' }, [
            canExport ? btn(busy ? '…' : 'Export PDF', 'picture_as_pdf', () => exportPdf(report.objectId), { disabled: busy }) : null,
            canRequest ? btn('Request Certification', 'approval', openRequestModal, { disabled: busy }) : null
        ]),
        m('div', { class: 'space-y-4' }, renderSections()),
        sectionHeader('Certification'),
        certBlock(),
        requestModal()
    ]);
}

export const reportView = {
    oninit: function () {
        let id = m.route.param('reportId');
        if (id) loadOne(id); else loadList();
    },
    view: function () {
        let id = m.route.param('reportId');
        if (id) {
            if (id !== loadedId && !loading) loadOne(id);
            if (loading) return m('div', { class: 'p-6 text-gray-400' }, 'Loading…');
            return report ? detail() : m('div', { class: 'p-6 text-gray-400' }, 'Report not found.');
        }
        if (loadedId) { loadedId = null; loadList(); }
        let roles = isoRoles();
        return m('div', { class: 'max-w-5xl mx-auto p-6 space-y-4' }, [
            sectionHeader('Compliance Reports',
                (roles.reporter || roles.admin) ? m('span', { class: 'text-xs text-gray-400' }, 'Generate a report from the Test Runner') : null),
            loadingOrEmpty(loading, reports.length === 0, 'No reports yet.') ||
            m('table', { class: 'w-full' }, [
                m('thead', m('tr', { class: 'text-left text-xs text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                    m('th', { class: 'px-3 py-2' }, 'Report'),
                    m('th', { class: 'px-3 py-2' }, 'Verdict'),
                    m('th', { class: 'px-3 py-2' }, 'Status')
                ])),
                m('tbody', reports.map(reportRow))
            ])
        ]);
    }
};

export default reportView;
