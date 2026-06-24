/**
 * ISO 42001 Certification Management (design §9A.8) — request queue, request detail (justification, message
 * thread read-only, approve & sign / deny), and certification detail (verify, revoke, download).
 * Routes: /iso42001/cert, /iso42001/cert/request/:requestId, /iso42001/cert/view/:certId.
 *
 * Note: message-append over REST is not part of the Phase-7 shim, so the thread is rendered read-only here.
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, statusPill, sectionHeader, loadingOrEmpty, btn } from './iso42001Common.js';

let requests = [];
let current = null;
let cert = null;
let verification = null;
let loading = false;
let busy = false;
let loadedKey = null;

async function loadQueue() {
    loading = true; m.redraw();
    try {
        let r = await iso42001Client.list('iso42001.certificationRequest',
            ['objectId', 'name', 'approvalStatus', 'justification'], 0, 50);
        requests = (r && r.results) ? r.results : [];
    } catch (e) { requests = []; }
    loading = false; m.redraw();
}

async function loadRequest(id) {
    loading = true; m.redraw();
    try { current = await iso42001Client.list('iso42001.certificationRequest', ['objectId', 'name', 'approvalStatus', 'justification', 'messages'], 0, 1); } catch (e) { current = null; }
    // The generic list won't fetch one-by-id with messages reliably; fall back to a direct read is not exposed,
    // so we filter the queue. Keep it simple: re-list and find.
    try {
        let all = await iso42001Client.list('iso42001.certificationRequest', ['objectId', 'name', 'approvalStatus', 'justification'], 0, 200);
        current = (all && all.results) ? all.results.find(x => x.objectId === id) : null;
    } catch (e) { current = null; }
    loading = false; m.redraw();
}

async function loadCert(id) {
    loading = true; verification = null; m.redraw();
    try { cert = await iso42001Client.getCertification(id); } catch (e) { cert = null; }
    loading = false; m.redraw();
}

async function doApprove(id) {
    if (busy) return;
    let note = window.prompt('Approval note (your signing title/notes):', 'Compliance Officer');
    if (note === null) return;
    busy = true; m.redraw();
    try {
        let c = await iso42001Client.approve(id, note);
        if (c && c.objectId) { page.toast && page.toast('success', 'Approved & signed.'); m.route.set('/iso42001/cert/view/' + c.objectId); }
        else page.toast && page.toast('error', 'Approve failed (need Certifier role).');
    } catch (e) { page.toast && page.toast('error', 'Approve failed: ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

async function doDeny(id) {
    if (busy) return;
    let reason = window.prompt('Reason for denial:');
    if (reason === null) return;
    busy = true; m.redraw();
    try { await iso42001Client.deny(id, reason); page.toast && page.toast('success', 'Denied.'); m.route.set('/iso42001/cert'); }
    catch (e) { page.toast && page.toast('error', 'Deny failed: ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

async function doVerify(id) {
    busy = true; m.redraw();
    try { verification = await iso42001Client.verify(id); }
    catch (e) { verification = { valid: false, reason: String(e) }; }
    busy = false; m.redraw();
}

function queue() {
    return m('div', { class: 'max-w-5xl mx-auto p-6 space-y-4' }, [
        sectionHeader('Certification Queue'),
        loadingOrEmpty(loading, requests.length === 0, 'No certification requests.') ||
        m('table', { class: 'w-full' }, [
            m('thead', m('tr', { class: 'text-left text-xs text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                m('th', { class: 'px-3 py-2' }, 'Request'),
                m('th', { class: 'px-3 py-2' }, 'Status')
            ])),
            m('tbody', requests.map(r => m('tr', {
                key: r.objectId,
                class: 'border-b border-gray-100 dark:border-gray-800 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800',
                onclick: () => m.route.set('/iso42001/cert/request/' + r.objectId)
            }, [
                m('td', { class: 'px-3 py-2 text-sm truncate' }, r.name),
                m('td', { class: 'px-3 py-2' }, statusPill(r.approvalStatus))
            ])))
        ])
    ]);
}

function requestDetail() {
    let roles = isoRoles();
    let canAct = (roles.certifier || roles.admin) && current && current.approvalStatus === 'REQUEST';
    let messages = (current && Array.isArray(current.messages)) ? current.messages : [];
    return m('div', { class: 'max-w-3xl mx-auto p-6 space-y-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, 'Certification Request'),
            btn('Queue', 'arrow_back', () => m.route.set('/iso42001/cert'))
        ]),
        current ? m('div', { class: 'flex items-center gap-2' }, [statusPill(current.approvalStatus)]) : null,
        current ? m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4 text-sm space-y-1' }, [
            m('div', { class: 'font-semibold' }, 'Justification'),
            m('div', { class: 'text-gray-600 dark:text-gray-300 whitespace-pre-wrap' }, current.justification || '—')
        ]) : m('div', { class: 'text-gray-400' }, 'Request not found.'),
        messages.length ? m('div', { class: 'space-y-1' }, [
            sectionHeader('Messages'),
            m('div', { class: 'flex flex-col gap-1' }, messages.map((msg, i) =>
                m('div', { key: i, class: 'text-sm px-3 py-2 rounded bg-gray-50 dark:bg-gray-800 text-gray-600 dark:text-gray-300' }, msg.text || msg.data || JSON.stringify(msg))))
        ]) : null,
        canAct ? m('div', { class: 'flex gap-2 pt-2' }, [
            btn(busy ? '…' : 'Approve & Sign', 'verified', () => doApprove(current.objectId), { primary: true, disabled: busy }),
            btn('Deny', 'cancel', () => doDeny(current.objectId), { danger: true, disabled: busy })
        ]) : null
    ]);
}

function verificationBlock() {
    if (!verification) return null;
    let ok = verification.valid;
    return m('div', { class: 'rounded-lg p-3 text-sm ' + (ok ? 'bg-green-50 text-green-700 dark:bg-green-900/20 dark:text-green-300' : 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300') }, [
        m('div', { class: 'font-semibold' }, ok ? '✓ Signature valid' : '✕ Verification failed'),
        m('div', { class: 'text-xs mt-1' }, [
            'status:' + verification.statusValid + ' · cert:' + verification.certificateValid + ' · notExpired:' + verification.notExpired +
            ' · hash:' + verification.hashMatches + ' · sig:' + verification.signatureValid,
            verification.reason ? m('div', verification.reason) : null
        ])
    ]);
}

function certDetail() {
    let roles = isoRoles();
    return m('div', { class: 'max-w-3xl mx-auto p-6 space-y-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, 'Certification'),
            btn('Queue', 'arrow_back', () => m.route.set('/iso42001/cert'))
        ]),
        cert ? m('div', { class: 'space-y-2' }, [
            m('div', { class: 'flex items-center gap-2' }, [statusPill(cert.status)]),
            m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4 text-sm space-y-1' }, [
                m('div', 'Certified by: ' + (cert.certifier && cert.certifier.name ? cert.certifier.name : '—') + (cert.certifierTitle ? ' (' + cert.certifierTitle + ')' : '')),
                m('div', 'Signature algorithm: ' + (cert.signatureAlgorithm || '—')),
                m('div', { class: 'break-all' }, 'Report hash: ' + (cert.reportHash || '—'))
            ]),
            m('div', { class: 'flex gap-2' }, [
                btn(busy ? '…' : 'Verify Now', 'fingerprint', () => doVerify(cert.objectId), { disabled: busy }),
                (roles.admin && cert.status === 'VALID') ? btn('Revoke', 'block', () => page.toast && page.toast('info', 'Revoke is an admin server action (not yet wired in the shim UI).'), { danger: true }) : null
            ]),
            verificationBlock()
        ]) : m('div', { class: 'text-gray-400' }, 'Certification not found.')
    ]);
}

export const certificationView = {
    oninit: function () {
        let reqId = m.route.param('requestId');
        let certId = m.route.param('certId');
        loadedKey = reqId ? 'r:' + reqId : (certId ? 'c:' + certId : 'q');
        if (reqId) loadRequest(reqId); else if (certId) loadCert(certId); else loadQueue();
    },
    view: function () {
        let reqId = m.route.param('requestId');
        let certId = m.route.param('certId');
        let key = reqId ? 'r:' + reqId : (certId ? 'c:' + certId : 'q');
        if (key !== loadedKey && !loading) {
            loadedKey = key;
            if (reqId) loadRequest(reqId); else if (certId) loadCert(certId); else loadQueue();
        }
        if (reqId) return requestDetail();
        if (certId) return certDetail();
        return queue();
    }
};

export default certificationView;
