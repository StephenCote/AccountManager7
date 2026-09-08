/**
 * Olio Admin panel (Deliverable B) — a per-org admin surface with one action: load the Olio corpus
 * reference data into the CURRENT organization. The org is derived server-side from the principal, so
 * there is nothing to choose/send for org. The load is SYNCHRONOUS and can be slow; the button shows a
 * spinner/disabled state for its duration.
 *
 * Client-side admin gating here is UX-only — POST /rest/olio/loadData is @RolesAllowed admin, which is
 * the real boundary (mirrors the iso42001 views' "client gate is cosmetic" note).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { olioAdminClient } from './olioAdminClient.js';

let includeLocations = false;
let loading = false;
let counts = null;      // per-corpus counts object on success
let errorMsg = null;    // server error message on failure

function isAdmin() {
    let ctx = page.context && page.context();
    return !!(ctx && ctx.roles && ctx.roles.admin);
}

async function loadCorpus() {
    if (loading) return;
    loading = true;
    counts = null;
    errorMsg = null;
    m.redraw();
    try {
        let res = await olioAdminClient.loadData(includeLocations);
        counts = (res && typeof res === 'object') ? res : {};
    } catch (e) {
        // Mithril extends the rejected Error with the JSON body's fields, so a 409/400
        // { "message": "..." } surfaces as e.message.
        errorMsg = (e && e.message) ? e.message : (typeof e === 'string' ? e : 'Load failed.');
    }
    loading = false;
    m.redraw();
}

function loadButton() {
    return m('button', {
        class: 'inline-flex items-center gap-2 px-4 py-2 rounded text-sm font-medium '
            + (loading
                ? 'bg-blue-400 text-white opacity-70 cursor-not-allowed'
                : 'bg-blue-600 text-white hover:bg-blue-700'),
        disabled: loading,
        onclick: loading ? null : loadCorpus
    }, [
        loading
            ? m('span', {
                class: 'material-symbols-outlined animate-spin',
                style: 'font-size:18px'
            }, 'progress_activity')
            : m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'download'),
        loading ? 'Loading Olio data…' : 'Load Olio data into this org'
    ]);
}

function includeLocationsToggle() {
    return m('label', {
        class: 'flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300 select-none '
            + (loading ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer')
    }, [
        m('input', {
            type: 'checkbox',
            class: 'h-4 w-4',
            checked: includeLocations,
            disabled: loading,
            onchange: e => { includeLocations = e.target.checked; }
        }),
        'Include location data (large)'
    ]);
}

function countsCard(label, value) {
    return m('div', {
        key: label,
        class: 'rounded-lg p-4 bg-gray-50 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
    }, [
        m('div', { class: 'text-2xl font-bold' }, '' + (value == null ? 0 : value)),
        m('div', { class: 'text-sm break-words' }, label)
    ]);
}

function renderResult() {
    if (loading) {
        return m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-4' },
            'Loading the Olio corpus into this organization. This can take a while — please leave the page open.');
    }
    if (errorMsg) {
        return m('div', {
            class: 'p-4 rounded-lg bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800'
        }, [
            m('div', { class: 'flex items-center gap-2 text-red-700 dark:text-red-300 font-semibold text-sm' }, [
                m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'error'),
                'Load failed'
            ]),
            m('div', { class: 'text-sm text-red-700 dark:text-red-300 mt-1 break-words' }, errorMsg)
        ]);
    }
    if (counts) {
        let entries = Object.keys(counts).map(k => [k, counts[k]]);
        return m('div', { class: 'space-y-3' }, [
            m('div', { class: 'flex items-center gap-2 text-green-700 dark:text-green-300 font-semibold text-sm' }, [
                m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'check_circle'),
                'Olio corpus loaded into this organization.'
            ]),
            entries.length
                ? m('div', { class: 'grid grid-cols-2 md:grid-cols-4 gap-3' },
                    entries.map(([k, v]) => countsCard(k, v)))
                : m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'The load reported no counts.')
        ]);
    }
    return null;
}

export const olioAdminView = {
    view: function () {
        if (!isAdmin()) {
            return m('div', { class: 'max-w-3xl mx-auto p-6' }, [
                m('div', {
                    class: 'p-4 rounded-lg bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-800 text-amber-800 dark:text-amber-200 text-sm'
                }, 'Olio Admin requires an administrator role. Ask an administrator to load the Olio corpus for this organization.')
            ]);
        }
        return m('div', { class: 'max-w-3xl mx-auto p-6 space-y-6' }, [
            m('div', [
                m('h1', { class: 'text-2xl font-bold text-gray-800 dark:text-white' }, 'Olio Admin'),
                m('p', { class: 'text-sm text-gray-500 dark:text-gray-400 mt-1' },
                    'Load the Olio corpus reference data (names, surnames, colors, and more) into the current organization. '
                    + 'The load runs synchronously on the server and can be slow.')
            ]),
            m('div', {
                class: 'p-4 rounded-lg bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 space-y-4'
            }, [
                includeLocationsToggle(),
                loadButton()
            ]),
            renderResult()
        ]);
    }
};

export default olioAdminView;
