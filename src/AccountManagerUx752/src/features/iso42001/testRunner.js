/**
 * ISO 42001 Test Runner (design §9A.5) — list test runs, configure + launch a new run.
 * Visible to iso42001Tester / iso42001Admin (server enforces; the menu item is role-gated too).
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, statusPill, sectionHeader, loadingOrEmpty, btn } from './iso42001Common.js';

let runs = [];
let loading = false;
let showNew = false;
let modules = [];
let endpoints = [];
let busy = false;
let form = { moduleId: 'BIAS', endpointName: '', tier: 1, samplesPerGroup: 30, randomSeed: 0 };

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
    try { let mo = await iso42001Client.modules(); modules = mo ? Object.keys(mo) : []; } catch (e) { modules = []; }
    try { let ep = await iso42001Client.endpoints(); endpoints = Array.isArray(ep) ? ep : []; } catch (e) { endpoints = []; }
    m.redraw();
}

async function launch() {
    if (busy) return;
    busy = true; m.redraw();
    try {
        // Create the test config, then start a run against it (the shim resolves module + endpoint).
        let cfg = {
            schema: 'iso42001.testConfig',
            name: 'run-' + Date.now().toString(36),
            moduleId: form.moduleId,
            endpointName: form.endpointName,
            endpointType: 'ollama',
            tier: Number(form.tier) || 0,
            samplesPerGroup: Number(form.samplesPerGroup) || 30,
            randomSeed: Number(form.randomSeed) || 0
        };
        let created = await iso42001Client.createConfig(cfg);
        if (!created || !created.objectId) {
            page.toast && page.toast('error', 'Could not create test config (check ISO Tester role).');
            busy = false; m.redraw(); return;
        }
        let run = await iso42001Client.startRun(created.objectId);
        showNew = false; busy = false;
        await loadRuns();
        if (run && run.objectId) m.route.set('/iso42001/results/' + run.objectId);
    } catch (e) {
        busy = false;
        page.toast && page.toast('error', 'Launch failed: ' + (e && e.message ? e.message : e));
        m.redraw();
    }
}

function field(label, key, type) {
    return m('label', { class: 'flex flex-col gap-1 text-sm' }, [
        m('span', { class: 'text-gray-600 dark:text-gray-300' }, label),
        m('input', {
            type: type || 'text',
            class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
            value: form[key],
            oninput: e => { form[key] = e.target.value; }
        })
    ]);
}

function newRunModal() {
    return m('div', { class: 'fixed inset-0 bg-black/40 flex items-center justify-center z-50', onclick: e => { if (e.target === e.currentTarget) showNew = false; } }, [
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg p-6 w-96 max-w-full space-y-3' }, [
            m('h3', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, 'New Test Run'),
            m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Test ID / Module'),
                m('select', { class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800', value: form.moduleId, onchange: e => { form.moduleId = e.target.value; } },
                    ['BIAS'].concat(modules).filter((v, i, a) => a.indexOf(v) === i).map(mo => m('option', { value: mo }, mo)))
            ]),
            field('LLM Endpoint (chatConfig name)', 'endpointName'),
            field('Tier (0=both,1=system,2=conversation)', 'tier', 'number'),
            field('Samples / Group', 'samplesPerGroup', 'number'),
            field('Random Seed (0=auto)', 'randomSeed', 'number'),
            m('div', { class: 'flex justify-end gap-2 pt-2' }, [
                btn('Cancel', null, () => { showNew = false; }),
                btn(busy ? 'Launching…' : 'Launch Run', 'play_circle', launch, { primary: true, disabled: busy })
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
            sectionHeader('Test Runs', canRun ? btn('New Run', 'add', openNew, { primary: true }) : null),
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
