/**
 * Shared rendering + RBAC helpers for the ISO 42001 views. Client-side gating is for UX only — the server
 * (ISO42001Service @RolesAllowed + model access.roles) is the real boundary (design §9A.10).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';

/** The ISO 42001 context-role flags (see router.js setContextRoles). Safe defaults if context is bare. */
export function isoRoles() {
    let r = (page.context && page.context() && page.context().roles) ? page.context().roles : {};
    return {
        tester: !!r.iso42001Tester,
        reporter: !!r.iso42001Reporter,
        certifier: !!r.iso42001Certifier,
        reader: !!r.iso42001Reader,
        auditor: !!r.iso42001Auditor,
        admin: !!r.iso42001Admin || !!r.admin,
        any: !!r.iso42001Any || !!r.admin
    };
}

const VERDICTS = {
    PASS: { icon: 'check_circle', cls: 'text-green-500' },
    FLAG: { icon: 'warning', cls: 'text-amber-500' },
    FAIL: { icon: 'cancel', cls: 'text-red-500' },
    ERROR: { icon: 'error', cls: 'text-gray-500' }
};

export function verdictBadge(verdict) {
    let v = VERDICTS[verdict] || { icon: 'help', cls: 'text-gray-400' };
    return m('span', { class: 'inline-flex items-center gap-1 ' + v.cls }, [
        m('span', { class: 'material-symbols-outlined', style: 'font-size:16px' }, v.icon),
        m('span', { class: 'text-sm font-medium' }, verdict || '—')
    ]);
}

export function statusPill(status) {
    let cls = 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200';
    if (status === 'CERTIFIED' || status === 'VALID' || status === 'APPROVE' || status === 'COMPLETED') {
        cls = 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300';
    } else if (status === 'RUNNING' || status === 'PENDING' || status === 'REQUEST' || status === 'DRAFT') {
        cls = 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300';
    } else if (status === 'FAILED' || status === 'REVOKED' || status === 'DENY' || status === 'EXPIRED') {
        cls = 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300';
    }
    return m('span', { class: 'px-2 py-0.5 rounded text-xs font-medium ' + cls }, status || '—');
}

export function sectionHeader(title, right) {
    return m('div', { class: 'flex items-center justify-between mb-2' }, [
        m('h2', { class: 'text-lg font-semibold text-gray-700 dark:text-gray-200' }, title),
        right || null
    ]);
}

export function loadingOrEmpty(loading, isEmpty, emptyText) {
    if (loading) {
        return m('div', { class: 'text-center py-8 text-gray-400' }, 'Loading…');
    }
    if (isEmpty) {
        return m('div', { class: 'text-center py-8 text-gray-400' }, emptyText || 'Nothing to show.');
    }
    return null;
}

export function btn(label, icon, onclick, opts) {
    opts = opts || {};
    let base = 'inline-flex items-center gap-1 px-3 py-1.5 rounded text-sm font-medium ';
    let style = opts.primary
        ? 'bg-blue-600 text-white hover:bg-blue-700'
        : 'bg-gray-100 text-gray-700 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600';
    if (opts.danger) {
        style = 'bg-red-600 text-white hover:bg-red-700';
    }
    return m('button', {
        class: base + style + (opts.disabled ? ' opacity-50 cursor-not-allowed' : ''),
        disabled: !!opts.disabled,
        onclick: opts.disabled ? null : onclick
    }, [
        icon ? m('span', { class: 'material-symbols-outlined', style: 'font-size:16px' }, icon) : null,
        label
    ]);
}
