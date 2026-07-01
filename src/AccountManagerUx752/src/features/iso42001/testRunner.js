/**
 * ISO 42001 Test Runner (design §9A.5) — list test runs and launch a new run against a saved campaign
 * (iso42001.testConfig). Campaign create/edit/delete lives in campaignsView.js; this view no longer mints a
 * throwaway config per launch. Visible to iso42001Tester / iso42001Admin (server enforces; menu is gated too).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, statusPill, sectionHeader, loadingOrEmpty, btn } from './iso42001Common.js';

let runs = [];
let loading = false;
let showNew = false;
let campaigns = [];
let selectedConfigId = '';
let busy = false;

async function loadRuns() {
    loading = true; m.redraw();
    try {
        let r = await iso42001Client.list('iso42001.testRun',
            ['objectId', 'name', 'status', 'modelEndpoint', 'passCount', 'flagCount', 'failCount', 'totalTrials'], 0, 50);
        runs = (r && r.results) ? r.results : [];
    } catch (e) { runs = []; }
    loading = false; m.redraw();
}

async function openNew() {
    showNew = true;
    try {
        let r = await iso42001Client.list('iso42001.testConfig',
            ['objectId', 'name', 'moduleId', 'endpointName'], 0, 100);
        campaigns = (r && r.results) ? r.results : [];
    } catch (e) { campaigns = []; }
    if (!selectedConfigId && campaigns.length) selectedConfigId = campaigns[0].objectId;
    m.redraw();
}

async function launch() {
    if (busy || !selectedConfigId) return;
    busy = true; m.redraw();
    try {
        let run = await iso42001Client.startRun(selectedConfigId);
        showNew = false; busy = false;
        await loadRuns();
        if (run && run.objectId) m.route.set('/iso42001/results/' + run.objectId);
        else page.toast && page.toast('error', 'Launch failed (config not found, endpoint unresolved, or access denied).');
    } catch (e) {
        busy = false;
        page.toast && page.toast('error', 'Launch failed: ' + (e && e.message ? e.message : e));
        m.redraw();
    }
}

function newRunModal() {
    return m('div', { class: 'fixed inset-0 bg-black/40 flex items-center justify-center z-50', onclick: e => { if (e.target === e.currentTarget) showNew = false; } }, [
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg p-6 w-96 max-w-full space-y-3' }, [
            m('h3', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, 'New Test Run'),
            campaigns.length === 0
                ? m('div', { class: 'space-y-3' }, [
                    m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'No campaigns exist yet. Create one first, then launch runs against it.'),
                    m('div', { class: 'flex justify-end gap-2 pt-2' }, [
                        btn('Cancel', null, () => { showNew = false; }),
                        btn('Manage Campaigns', 'campaign', () => { showNew = false; m.route.set('/iso42001/campaigns'); }, { primary: true })
                    ])
                ])
                : m('div', { class: 'space-y-3' }, [
                    m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                        m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Campaign'),
                        m('select', {
                            class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                            value: selectedConfigId, onchange: e => { selectedConfigId = e.target.value; }
                        }, campaigns.map(c => m('option', { value: c.objectId },
                            c.name + ' — ' + (c.moduleId || '?') + ' / ' + (c.endpointName || '?'))))
                    ]),
                    m('div', { class: 'flex justify-between items-center pt-2' }, [
                        btn('Manage', 'campaign', () => { showNew = false; m.route.set('/iso42001/campaigns'); }),
                        m('div', { class: 'flex gap-2' }, [
                            btn('Cancel', null, () => { showNew = false; }),
                            btn(busy ? 'Launching…' : 'Launch Run', 'play_circle', launch, { primary: true, disabled: busy })
                        ])
                    ])
                ])
        ])
    ]);
}

function runRow(r) {
    let pf = (r.status === 'COMPLETED')
        ? (r.passCount || 0) + '/' + (r.flagCount || 0) + '/' + (r.failCount || 0)
        : '—';
    return m('tr', {
        key: r.objectId,
        class: 'border-b border-gray-100 dark:border-gray-800 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800',
        onclick: () => m.route.set('/iso42001/results/' + r.objectId)
    }, [
        m('td', { class: 'px-3 py-2 text-sm truncate' }, r.name),
        m('td', { class: 'px-3 py-2 text-sm' }, r.modelEndpoint || '—'),
        m('td', { class: 'px-3 py-2' }, statusPill(r.status)),
        m('td', { class: 'px-3 py-2 text-sm text-gray-500' }, pf)
    ]);
}

export const testRunnerView = {
    oninit: loadRuns,
    view: function () {
        let roles = isoRoles();
        let canRun = roles.tester || roles.admin;
        return m('div', { class: 'max-w-5xl mx-auto p-6 space-y-4' }, [
            sectionHeader('Test Runs', m('div', { class: 'flex gap-2' }, [
                btn('Campaigns', 'campaign', () => m.route.set('/iso42001/campaigns')),
                canRun ? btn('New Run', 'add', openNew, { primary: true }) : null
            ])),
            (!canRun) ? m('div', { class: 'text-sm text-amber-600' }, 'You need the ISO 42001 Tester role to launch runs; runs are shown read-only.') : null,
            loadingOrEmpty(loading, runs.length === 0, 'No test runs yet.') ||
            m('table', { class: 'w-full' }, [
                m('thead', m('tr', { class: 'text-left text-xs text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                    m('th', { class: 'px-3 py-2' }, 'Run'),
                    m('th', { class: 'px-3 py-2' }, 'Endpoint'),
                    m('th', { class: 'px-3 py-2' }, 'Status'),
                    m('th', { class: 'px-3 py-2' }, 'P/Fl/F')
                ])),
                m('tbody', runs.map(runRow))
            ]),
            showNew ? newRunModal() : null
        ]);
    }
};

export default testRunnerView;
