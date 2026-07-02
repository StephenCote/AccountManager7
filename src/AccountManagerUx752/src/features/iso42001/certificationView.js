/**
 * ISO 42001 Certification Management (design §9A.8) — request queue, request detail (justification, message
 * thread with append, approve & sign / deny), and certification detail (verify, revoke, download).
 * Routes: /iso42001/cert, /iso42001/cert/request/:requestId, /iso42001/cert/view/:certId.
 *
 * Phase 2: single-request read via GET /certification/request/{id} (was a brittle re-list hack); message
 * append via POST .../message (certifier/admin — server gates the update to those roles); a reusable text
 * modal replaces window.prompt for approve/deny/revoke; Revoke wired to POST /certification/{id}/revoke.
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
let msgText = '';

// Reusable text/confirm modal. { title, label?, value, placeholder, confirmLabel, danger?, warning?, onConfirm }
// If `label` is omitted the modal is a plain confirm (no input).
let modal = null;

function openModal(cfg) {
    modal = Object.assign({ value: '', confirmLabel: 'OK' }, cfg);
    m.redraw();
}
function closeModal() { modal = null; m.redraw(); }

/** Decode a message.spool entry's text (server stores FIELD_DATA as bytes → base64 on the wire). */
function messageText(msg) {
    if (!msg) return '';
    if (typeof msg.text === 'string') return msg.text;
    if (typeof msg.data === 'string') {
        try { return decodeURIComponent(escape(window.atob(msg.data))); }
        catch (e) { return msg.data; }
    }
    return JSON.stringify(msg);
}

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
    loading = true; current = null; msgText = ''; m.redraw();
    try { current = await iso42001Client.getRequest(id); } catch (e) { current = null; }
    loading = false; m.redraw();
}

async function loadCert(id) {
    loading = true; verification = null; cert = null; m.redraw();
    try { cert = await iso42001Client.getCertification(id); } catch (e) { cert = null; }
    loading = false; m.redraw();
}

// ── Actions ──────────────────────────────────────────────────────────────

function askApprove(id) {
    openModal({
        title: 'Approve & Sign',
        label: 'Your title for this certification',
        value: 'Compliance Officer',
        confirmLabel: 'Sign & Certify',
        warning: 'This is irreversible. Your digital signature (SHA256WithRSA over the report hash) will be ' +
            'permanently attached to the report. The certification is valid for 1 year.',
        onConfirm: (note) => reallyApprove(id, note)
    });
}

async function reallyApprove(id, note) {
    if (busy) return;
    busy = true; m.redraw();
    try {
        let c = await iso42001Client.approve(id, note || 'Approved');
        if (c && c.objectId) {
            page.toast && page.toast('success', 'Approved & signed.');
            closeModal();
            m.route.set('/iso42001/cert/view/' + c.objectId);
        } else {
            page.toast && page.toast('error', 'Approve failed (need Certifier role).');
        }
    } catch (e) { page.toast && page.toast('error', 'Approve failed: ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

function askDeny(id) {
    openModal({
        title: 'Deny Request', label: 'Reason for denial', confirmLabel: 'Deny', danger: true,
        onConfirm: (reason) => reallyDeny(id, reason)
    });
}

async function reallyDeny(id, reason) {
    if (busy) return;
    busy = true; m.redraw();
    try {
        await iso42001Client.deny(id, reason || 'Denied');
        page.toast && page.toast('success', 'Denied.');
        closeModal();
        m.route.set('/iso42001/cert');
    } catch (e) { page.toast && page.toast('error', 'Deny failed: ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

function askDelete(id, name) {
    openModal({
        title: 'Delete Request', confirmLabel: 'Delete', danger: true,
        warning: 'Delete certification request "' + (name || id) + '"? This cannot be undone.',
        onConfirm: () => reallyDelete(id)
    });
}

async function reallyDelete(id) {
    if (busy) return;
    busy = true; m.redraw();
    try {
        await page.deleteObject('iso42001.certificationRequest', id);
        page.toast && page.toast('success', 'Request deleted.');
        closeModal();
        m.route.set('/iso42001/cert');
    } catch (e) { page.toast && page.toast('error', 'Delete failed (need ISO Admin role): ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

async function sendMessage(id) {
    if (busy || !msgText.trim()) return;
    busy = true; m.redraw();
    try {
        let updated = await iso42001Client.appendMessage(id, msgText.trim());
        if (updated && updated.objectId) {
            msgText = '';
            current = updated;
            page.toast && page.toast('success', 'Message added.');
        } else {
            page.toast && page.toast('error', 'Add message failed (need Certifier role).');
        }
    } catch (e) { page.toast && page.toast('error', 'Add message failed: ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

function askRevoke(certId) {
    openModal({
        title: 'Revoke Certification', label: 'Reason for revocation', confirmLabel: 'Revoke', danger: true,
        warning: 'Revocation is permanent. Verification of this certification will fail afterward.',
        onConfirm: (reason) => reallyRevoke(certId, reason)
    });
}

async function reallyRevoke(certId, reason) {
    if (busy) return;
    busy = true; m.redraw();
    try {
        let c = await iso42001Client.revoke(certId, reason || 'Revoked');
        if (c && c.objectId) {
            cert = c;
            verification = null;
            page.toast && page.toast('success', 'Certification revoked.');
            closeModal();
        } else {
            page.toast && page.toast('error', 'Revoke failed (need ISO Admin role).');
        }
    } catch (e) { page.toast && page.toast('error', 'Revoke failed: ' + (e && e.message ? e.message : e)); }
    busy = false; m.redraw();
}

async function doVerify(id) {
    busy = true; m.redraw();
    try { verification = await iso42001Client.verify(id); }
    catch (e) { verification = { valid: false, reason: String(e) }; }
    busy = false; m.redraw();
}

// ── Rendering ──────────────────────────────────────────────────────────────

function modalView() {
    if (!modal) return null;
    return m('div', {
        class: 'fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4',
        onclick: e => { if (e.target === e.currentTarget) closeModal(); }
    }, [
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg p-6 w-full max-w-md space-y-3' }, [
            m('h3', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, modal.title),
            modal.warning ? m('div', { class: 'text-xs rounded p-2 bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300' }, modal.warning) : null,
            modal.label ? m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                m('span', { class: 'text-gray-600 dark:text-gray-300' }, modal.label),
                m('input', {
                    name: 'modal_value', type: 'text',
                    class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                    placeholder: modal.placeholder || '', value: modal.value,
                    oninput: e => { modal.value = e.target.value; }
                })
            ]) : null,
            m('div', { class: 'flex justify-end gap-2 pt-2' }, [
                btn('Cancel', null, closeModal),
                btn(busy ? '…' : modal.confirmLabel, modal.danger ? 'warning' : 'check',
                    () => modal.onConfirm(modal.value), { primary: !modal.danger, danger: modal.danger, disabled: busy })
            ])
        ])
    ]);
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

function messageThread() {
    let roles = isoRoles();
    let messages = (current && Array.isArray(current.messages)) ? current.messages : [];
    let canAppend = roles.certifier || roles.admin;
    return m('div', { class: 'space-y-2' }, [
        sectionHeader('Messages'),
        messages.length
            ? m('div', { class: 'flex flex-col gap-1' }, messages.map((msg, i) =>
                m('div', { key: i, class: 'text-sm px-3 py-2 rounded bg-gray-50 dark:bg-gray-800 text-gray-600 dark:text-gray-300 whitespace-pre-wrap' },
                    messageText(msg))))
            : m('div', { class: 'text-sm text-gray-400' }, 'No messages yet.'),
        canAppend
            ? m('div', { class: 'flex gap-2 pt-1' }, [
                m('input', {
                    name: 'cert_message', type: 'text',
                    class: 'flex-1 px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm',
                    placeholder: 'Add a message…', value: msgText,
                    oninput: e => { msgText = e.target.value; },
                    onkeydown: e => { if (e.key === 'Enter') sendMessage(current.objectId); }
                }),
                btn(busy ? '…' : 'Send', 'send', () => sendMessage(current.objectId), { primary: true, disabled: busy || !msgText.trim() })
            ])
            : m('div', { class: 'text-xs text-gray-400 pt-1' }, 'Only a certifier or administrator can add messages.')
    ]);
}

function requestDetail() {
    let roles = isoRoles();
    let canAct = (roles.certifier || roles.admin) && current && current.approvalStatus === 'REQUEST';
    let report = current && current.report;
    return m('div', { class: 'max-w-3xl mx-auto p-6 space-y-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, 'Certification Request'),
            btn('Queue', 'arrow_back', () => m.route.set('/iso42001/cert'))
        ]),
        loading ? m('div', { class: 'text-gray-400' }, 'Loading…') : null,
        current ? m('div', { class: 'flex items-center gap-2' }, [
            statusPill(current.approvalStatus),
            (report && report.objectId) ? btn('View Report', 'summarize', () => m.route.set('/iso42001/report/' + report.objectId)) : null
        ]) : (!loading ? m('div', { class: 'text-gray-400' }, 'Request not found.') : null),
        current ? m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4 text-sm space-y-1' }, [
            m('div', { class: 'font-semibold' }, 'Justification'),
            m('div', { class: 'text-gray-600 dark:text-gray-300 whitespace-pre-wrap' }, current.justification || '—')
        ]) : null,
        current ? messageThread() : null,
        canAct ? m('div', { class: 'flex gap-2 pt-2' }, [
            btn('Approve & Sign', 'verified', () => askApprove(current.objectId), { primary: true, disabled: busy }),
            btn('Deny', 'cancel', () => askDeny(current.objectId), { danger: true, disabled: busy })
        ]) : null,
        (current && roles.admin) ? m('div', { class: 'pt-2 border-t border-gray-100 dark:border-gray-800' }, [
            btn('Delete Request', 'delete', () => askDelete(current.objectId, current.name), { danger: true, disabled: busy })
        ]) : null,
        modalView()
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
        loading ? m('div', { class: 'text-gray-400' }, 'Loading…') : null,
        cert ? m('div', { class: 'space-y-2' }, [
            m('div', { class: 'flex items-center gap-2' }, [statusPill(cert.status)]),
            m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4 text-sm space-y-1' }, [
                m('div', 'Certified by: ' + (cert.certifier && cert.certifier.name ? cert.certifier.name : '—') + (cert.certifierTitle ? ' (' + cert.certifierTitle + ')' : '')),
                m('div', 'Signature algorithm: ' + (cert.signatureAlgorithm || '—')),
                m('div', { class: 'break-all' }, 'Report hash: ' + (cert.reportHash || '—')),
                cert.notes ? m('div', 'Notes: ' + cert.notes) : null
            ]),
            m('div', { class: 'flex gap-2' }, [
                btn(busy ? '…' : 'Verify Now', 'fingerprint', () => doVerify(cert.objectId), { disabled: busy }),
                (roles.admin && cert.status === 'VALID')
                    ? btn('Revoke', 'block', () => askRevoke(cert.objectId), { danger: true, disabled: busy }) : null
            ]),
            verificationBlock()
        ]) : (!loading ? m('div', { class: 'text-gray-400' }, 'Certification not found.') : null),
        modalView()
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
