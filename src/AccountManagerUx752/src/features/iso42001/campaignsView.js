/**
 * ISO 42001 Campaign Management (Phase 1 of the Ux gap plan) — a "campaign" is a persisted
 * iso42001.testConfig (module + endpoint + tier + samples + statistical knobs) that runs are launched
 * against. This view provides the list / create / edit / delete / launch that the original testRunner
 * lacked (it minted a throwaway config per launch), plus a detail page that lists the campaign's runs and
 * lets a Reporter generate a report from a COMPLETED run.
 *
 * Routes: /iso42001/campaigns (list), /iso42001/campaigns/:configId (detail).
 * RBAC: create/edit = Tester|Admin, delete = Admin, launch = Tester|Admin, generate report = Reporter|Admin.
 * The server (model access.roles + facade) is the real boundary; the client gating is UX only.
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, statusPill, sectionHeader, loadingOrEmpty, btn } from './iso42001Common.js';

const CONFIG_HOME = '~/ISO42001';
const CONFIG_FIELDS = ['objectId', 'name', 'moduleId', 'endpointName', 'endpointType', 'tier', 'samplesPerGroup'];

let campaigns = [];
let current = null;
let runs = [];
let modules = [];
let endpoints = [];
let loading = false;
let busy = false;
let loadedKey = null;

// Editor modal state. When updating, `editingRecord` holds the full loaded record so the PATCH can carry its
// identity fields (id/objectId/urn) + group identity (groupId/organizationId). A patch that omits the numeric
// id / groupId can't be resolved server-side (PolicyUtil "Group could not be found") — see model.js inst.patch,
// which likewise includes every identity field that has a value.
let showEditor = false;
let editing = null;
let editingRecord = null;
let form = blankForm();

const IDENTITY_FIELDS = ['id', 'objectId', 'urn', 'groupId', 'organizationId'];

function identityFrom(rec) {
    let out = {};
    IDENTITY_FIELDS.forEach(f => { if (rec && rec[f] != null) out[f] = rec[f]; });
    return out;
}

function blankForm() {
    return {
        name: '', description: '', moduleId: 'BIAS', testIds: '',
        endpointName: '', endpointType: 'ollama', tier: 1,
        samplesPerGroup: 30, temperature: 1.0, alpha: 0.05, randomSeed: 0,
        chatConfigName: '', promptConfigName: ''
    };
}

async function loadList() {
    loading = true; m.redraw();
    try {
        let r = await iso42001Client.list('iso42001.testConfig', CONFIG_FIELDS, 0, 100);
        campaigns = (r && r.results) ? r.results : [];
    } catch (e) { campaigns = []; }
    loading = false; m.redraw();
}

async function loadOne(id) {
    loading = true; current = null; runs = []; m.redraw();
    try { current = await iso42001Client.getConfig(id); } catch (e) { current = null; }
    // The campaign's runs: list runs and keep those whose testConfig points back here. (No server-side
    // foreign-key filter is exposed on the shim, so filter client-side; the projection includes testConfig.)
    try {
        let r = await iso42001Client.list('iso42001.testRun',
            ['objectId', 'name', 'status', 'modelEndpoint', 'passCount', 'flagCount', 'failCount', 'testConfig'], 0, 100);
        let all = (r && r.results) ? r.results : [];
        runs = all.filter(x => x.testConfig && x.testConfig.objectId === id);
    } catch (e) { runs = []; }
    loading = false; m.redraw();
}

async function ensureLookups() {
    if (!modules.length) {
        try { let mo = await iso42001Client.modules(); modules = mo ? Object.keys(mo) : []; } catch (e) { modules = []; }
    }
    if (!endpoints.length) {
        try { let ep = await iso42001Client.endpoints(); endpoints = Array.isArray(ep) ? ep : []; } catch (e) { endpoints = []; }
    }
}

function fillForm(cfg) {
    form = {
        name: cfg.name || '',
        description: cfg.description || '',
        moduleId: cfg.moduleId || 'BIAS',
        testIds: Array.isArray(cfg.testIds) ? cfg.testIds.join(', ') : '',
        endpointName: cfg.endpointName || '',
        endpointType: cfg.endpointType || 'ollama',
        tier: cfg.tier != null ? cfg.tier : 1,
        samplesPerGroup: cfg.samplesPerGroup != null ? cfg.samplesPerGroup : 30,
        temperature: cfg.temperature != null ? cfg.temperature : 1.0,
        alpha: cfg.alpha != null ? cfg.alpha : 0.05,
        randomSeed: cfg.randomSeed != null ? cfg.randomSeed : 0,
        chatConfigName: cfg.chatConfigName || '',
        promptConfigName: cfg.promptConfigName || ''
    };
}

function openCreate() {
    editing = null;
    editingRecord = null;
    form = blankForm();
    showEditor = true;
    ensureLookups().then(m.redraw);
}

async function openEdit(cfg) {
    editing = cfg.objectId;
    // List-row projections are partial (no id/urn/groupId), so load the full record to patch against.
    editingRecord = cfg;
    fillForm(cfg);
    showEditor = true;
    m.redraw();
    await ensureLookups();
    if (!cfg.id || cfg.groupId == null) {
        try {
            let full = await iso42001Client.getConfig(cfg.objectId);
            if (full && full.objectId) { editingRecord = full; fillForm(full); }
        } catch (e) { /* keep partial; save will warn if identity is missing */ }
    }
    m.redraw();
}

function parseTestIds(s) {
    if (!s) return [];
    return s.split(',').map(x => x.trim()).filter(x => x.length);
}

/** Build the value fields common to create + patch (no schema/identity). */
function formValues() {
    return {
        name: form.name.trim(),
        description: form.description || '',
        moduleId: form.moduleId,
        testIds: parseTestIds(form.testIds),
        endpointName: form.endpointName.trim(),
        endpointType: form.endpointType,
        tier: Number(form.tier) || 0,
        samplesPerGroup: Number(form.samplesPerGroup) || 30,
        temperature: Number(form.temperature),
        alpha: Number(form.alpha),
        randomSeed: Number(form.randomSeed) || 0,
        chatConfigName: form.chatConfigName.trim(),
        promptConfigName: form.promptConfigName.trim()
    };
}

async function save() {
    if (busy) return;
    if (!form.name.trim()) { page.toast && page.toast('error', 'Name is required.'); return; }
    if (!form.endpointName.trim()) { page.toast && page.toast('error', 'LLM Endpoint (chatConfig name) is required.'); return; }
    busy = true; m.redraw();
    try {
        if (editing) {
            // PATCH via the shared page client. Carry the record's identity + group (id/objectId/urn/groupId/
            // organizationId) so the server can resolve the record and its group for policy — see model.js patch.
            let patch = Object.assign({ schema: 'iso42001.testConfig' }, identityFrom(editingRecord), formValues());
            let ok = await page.patchObject(patch);
            if (ok) {
                page.toast && page.toast('success', 'Campaign updated.');
                showEditor = false;
                await loadOne(editing);
                await loadList();
            } else {
                page.toast && page.toast('error', 'Update failed (need ISO Tester role).');
            }
        } else {
            // Create needs a home group for placement (the acting user owns it; the Tester role authorizes create).
            let group = await page.makePath('auth.group', 'data', CONFIG_HOME);
            if (!group || !group.id) {
                page.toast && page.toast('error', 'Could not resolve the ' + CONFIG_HOME + ' group.');
                busy = false; m.redraw(); return;
            }
            let cfg = Object.assign({
                schema: 'iso42001.testConfig',
                groupId: group.id,
                organizationId: group.organizationId
            }, formValues());
            let created = await iso42001Client.createConfig(cfg);
            if (created && created.objectId) {
                page.toast && page.toast('success', 'Campaign created.');
                showEditor = false;
                await loadList();
                m.route.set('/iso42001/campaigns/' + created.objectId);
            } else {
                page.toast && page.toast('error', 'Create failed (need ISO Tester role).');
            }
        }
    } catch (e) {
        page.toast && page.toast('error', 'Save failed: ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

async function remove(cfg) {
    if (busy) return;
    if (!window.confirm('Delete campaign "' + (cfg.name || cfg.objectId) + '"? This cannot be undone.')) return;
    busy = true; m.redraw();
    try {
        await page.deleteObject('iso42001.testConfig', cfg.objectId);
        page.toast && page.toast('success', 'Campaign deleted.');
        if (current && current.objectId === cfg.objectId) {
            m.route.set('/iso42001/campaigns');
        }
        await loadList();
    } catch (e) {
        page.toast && page.toast('error', 'Delete failed (need ISO Admin role): ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

async function launch(cfg) {
    if (busy) return;
    busy = true; m.redraw();
    try {
        let run = await iso42001Client.startRun(cfg.objectId);
        if (run && run.objectId) {
            page.toast && page.toast('success', 'Run launched.');
            m.route.set('/iso42001/results/' + run.objectId);
        } else {
            page.toast && page.toast('error', 'Launch failed (config not found, endpoint unresolved, or access denied).');
        }
    } catch (e) {
        page.toast && page.toast('error', 'Launch failed: ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

async function generateReport(run) {
    if (busy) return;
    let name = window.prompt('Report name:', (current ? current.name : 'Campaign') + ' report ' + new Date().toISOString().slice(0, 10));
    if (name === null) return;
    busy = true; m.redraw();
    try {
        let rpt = await iso42001Client.generateReport(name, 'COMPLIANCE', [run.objectId]);
        if (rpt && rpt.objectId) {
            page.toast && page.toast('success', 'Report generated.');
            m.route.set('/iso42001/report/' + rpt.objectId);
        } else {
            page.toast && page.toast('error', 'Report generation failed (need ISO Reporter role).');
        }
    } catch (e) {
        page.toast && page.toast('error', 'Report generation failed: ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

// ── Rendering ──────────────────────────────────────────────────────────────

function textField(label, key, type) {
    return m('label', { class: 'flex flex-col gap-1 text-sm' }, [
        m('span', { class: 'text-gray-600 dark:text-gray-300' }, label),
        m('input', {
            type: type || 'text',
            name: 'campaign_' + key,
            class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
            value: form[key],
            oninput: e => { form[key] = e.target.value; }
        })
    ]);
}

function editorModal() {
    let roles = isoRoles();
    let canSave = roles.tester || roles.admin;
    let moduleOptions = ['BIAS'].concat(modules).filter((v, i, a) => a.indexOf(v) === i);
    return m('div', {
        class: 'fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4 overflow-y-auto',
        onclick: e => { if (e.target === e.currentTarget) showEditor = false; }
    }, [
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg p-6 w-full max-w-lg space-y-3 my-8' }, [
            m('h3', { class: 'text-lg font-semibold text-gray-800 dark:text-white' },
                editing ? 'Edit Campaign' : 'New Campaign'),
            !canSave ? m('div', { class: 'text-sm text-amber-600' }, 'You need the ISO 42001 Tester role to save campaigns.') : null,
            textField('Name', 'name'),
            textField('Description', 'description'),
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                    m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Test Module'),
                    m('select', {
                        class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                        value: form.moduleId, onchange: e => { form.moduleId = e.target.value; }
                    }, moduleOptions.map(mo => m('option', { value: mo }, mo)))
                ]),
                m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                    m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Endpoint Type'),
                    m('select', {
                        class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                        value: form.endpointType, onchange: e => { form.endpointType = e.target.value; }
                    }, ['ollama', 'openai', 'anthropic', 'azure'].map(t => m('option', { value: t }, t)))
                ])
            ]),
            m('label', { class: 'flex flex-col gap-1 text-sm' }, [
                m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'LLM Endpoint (chatConfig name)'),
                m('input', {
                    name: 'campaign_endpointName',
                    class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800',
                    list: 'iso-endpoint-list', value: form.endpointName,
                    oninput: e => { form.endpointName = e.target.value; }
                }),
                m('datalist', { id: 'iso-endpoint-list' }, endpoints.map(ep => m('option', { value: ep.name })))
            ]),
            textField('Test IDs (comma-separated, empty = all in module)', 'testIds'),
            m('div', { class: 'grid grid-cols-3 gap-3' }, [
                textField('Tier (0/1/2)', 'tier', 'number'),
                textField('Samples/Group', 'samplesPerGroup', 'number'),
                textField('Random Seed', 'randomSeed', 'number')
            ]),
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                textField('Temperature', 'temperature', 'number'),
                textField('Significance α', 'alpha', 'number')
            ]),
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                textField('Chat Config Name (optional)', 'chatConfigName'),
                textField('Prompt Config Name (optional)', 'promptConfigName')
            ]),
            m('div', { class: 'flex justify-end gap-2 pt-2' }, [
                btn('Cancel', null, () => { showEditor = false; }),
                btn(busy ? 'Saving…' : (editing ? 'Save Changes' : 'Create Campaign'), 'save', save,
                    { primary: true, disabled: busy || !canSave })
            ])
        ])
    ]);
}

function campaignRow(cfg) {
    let roles = isoRoles();
    return m('tr', {
        key: cfg.objectId,
        class: 'border-b border-gray-100 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800'
    }, [
        m('td', {
            class: 'px-3 py-2 text-sm truncate cursor-pointer text-blue-600 dark:text-blue-400',
            onclick: () => m.route.set('/iso42001/campaigns/' + cfg.objectId)
        }, cfg.name),
        m('td', { class: 'px-3 py-2 text-sm' }, cfg.moduleId || '—'),
        m('td', { class: 'px-3 py-2 text-sm' }, cfg.endpointName || '—'),
        m('td', { class: 'px-3 py-2 text-sm text-gray-500' }, 'tier ' + (cfg.tier != null ? cfg.tier : '—') + ' · n=' + (cfg.samplesPerGroup != null ? cfg.samplesPerGroup : '—')),
        m('td', { class: 'px-3 py-2 text-right whitespace-nowrap' }, [
            (roles.tester || roles.admin) ? btn('Launch', 'play_circle', () => launch(cfg), { primary: true, disabled: busy }) : null,
            (roles.tester || roles.admin) ? m('span', { class: 'inline-block w-1' }) : null,
            (roles.tester || roles.admin) ? btn('Edit', 'edit', () => openEdit(cfg), { disabled: busy }) : null,
            roles.admin ? m('span', { class: 'inline-block w-1' }) : null,
            roles.admin ? btn('', 'delete', () => remove(cfg), { danger: true, disabled: busy }) : null
        ])
    ]);
}

function listView() {
    let roles = isoRoles();
    return m('div', { class: 'max-w-5xl mx-auto p-6 space-y-4' }, [
        sectionHeader('Test Campaigns',
            (roles.tester || roles.admin) ? btn('New Campaign', 'add', openCreate, { primary: true }) : null),
        m('p', { class: 'text-sm text-gray-500 dark:text-gray-400' },
            'A campaign is a reusable test configuration (module, endpoint, tier, samples, statistics). Launch runs against it and generate reports from completed runs.'),
        loadingOrEmpty(loading, campaigns.length === 0, 'No campaigns yet. Create one to get started.') ||
        m('table', { class: 'w-full' }, [
            m('thead', m('tr', { class: 'text-left text-xs text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                m('th', { class: 'px-3 py-2' }, 'Campaign'),
                m('th', { class: 'px-3 py-2' }, 'Module'),
                m('th', { class: 'px-3 py-2' }, 'Endpoint'),
                m('th', { class: 'px-3 py-2' }, 'Params'),
                m('th', { class: 'px-3 py-2' }, '')
            ])),
            m('tbody', campaigns.map(campaignRow))
        ]),
        showEditor ? editorModal() : null
    ]);
}

function runRow(r) {
    let roles = isoRoles();
    let pf = (r.status === 'COMPLETED')
        ? (r.passCount || 0) + '/' + (r.flagCount || 0) + '/' + (r.failCount || 0) : '—';
    return m('tr', { key: r.objectId, class: 'border-b border-gray-100 dark:border-gray-800' }, [
        m('td', {
            class: 'px-3 py-2 text-sm truncate cursor-pointer text-blue-600 dark:text-blue-400',
            onclick: () => m.route.set('/iso42001/results/' + r.objectId)
        }, r.name),
        m('td', { class: 'px-3 py-2' }, statusPill(r.status)),
        m('td', { class: 'px-3 py-2 text-sm text-gray-500' }, pf),
        m('td', { class: 'px-3 py-2 text-right' },
            (r.status === 'COMPLETED' && (roles.reporter || roles.admin))
                ? btn('Generate Report', 'summarize', () => generateReport(r), { disabled: busy }) : null)
    ]);
}

function detailView() {
    let roles = isoRoles();
    if (loading && !current) return m('div', { class: 'p-6 text-gray-400' }, 'Loading…');
    if (!current) return m('div', { class: 'max-w-4xl mx-auto p-6' }, [
        btn('Campaigns', 'arrow_back', () => m.route.set('/iso42001/campaigns')),
        m('div', { class: 'text-gray-400 mt-4' }, 'Campaign not found.')
    ]);
    let field = (label, val) => m('div', { class: 'flex justify-between text-sm py-1 border-b border-gray-100 dark:border-gray-800' }, [
        m('span', { class: 'text-gray-500' }, label),
        m('span', { class: 'text-gray-800 dark:text-gray-200' }, val == null || val === '' ? '—' : String(val))
    ]);
    return m('div', { class: 'max-w-4xl mx-auto p-6 space-y-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, current.name),
            btn('Campaigns', 'arrow_back', () => m.route.set('/iso42001/campaigns'))
        ]),
        m('div', { class: 'flex gap-2' }, [
            (roles.tester || roles.admin) ? btn(busy ? '…' : 'Launch Run', 'play_circle', () => launch(current), { primary: true, disabled: busy }) : null,
            (roles.tester || roles.admin) ? btn('Edit', 'edit', () => openEdit(current), { disabled: busy }) : null,
            roles.admin ? btn('Delete', 'delete', () => remove(current), { danger: true, disabled: busy }) : null
        ]),
        current.description ? m('p', { class: 'text-sm text-gray-600 dark:text-gray-300' }, current.description) : null,
        m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4' }, [
            field('Module', current.moduleId),
            field('Test IDs', Array.isArray(current.testIds) && current.testIds.length ? current.testIds.join(', ') : 'all'),
            field('Endpoint', (current.endpointName || '—') + ' (' + (current.endpointType || '—') + ')'),
            field('Tier', current.tier),
            field('Samples / Group', current.samplesPerGroup),
            field('Temperature', current.temperature),
            field('Significance α', current.alpha),
            field('Random Seed', current.randomSeed || 'auto'),
            field('Chat Config', current.chatConfigName),
            field('Prompt Config', current.promptConfigName)
        ]),
        m('div', { class: 'space-y-2' }, [
            sectionHeader('Runs'),
            (runs.length === 0)
                ? m('div', { class: 'text-sm text-gray-400 py-2' }, 'No runs for this campaign yet. Launch one above.')
                : m('table', { class: 'w-full' }, [
                    m('thead', m('tr', { class: 'text-left text-xs text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                        m('th', { class: 'px-3 py-2' }, 'Run'),
                        m('th', { class: 'px-3 py-2' }, 'Status'),
                        m('th', { class: 'px-3 py-2' }, 'P/Fl/F'),
                        m('th', { class: 'px-3 py-2' }, '')
                    ])),
                    m('tbody', runs.map(runRow))
                ])
        ]),
        showEditor ? editorModal() : null
    ]);
}

export const campaignsView = {
    oninit: function () {
        let id = m.route.param('configId');
        loadedKey = id ? 'c:' + id : 'list';
        if (id) loadOne(id); else loadList();
    },
    view: function () {
        let id = m.route.param('configId');
        let key = id ? 'c:' + id : 'list';
        if (key !== loadedKey && !loading) {
            loadedKey = key;
            if (id) loadOne(id); else loadList();
        }
        return id ? detailView() : listView();
    }
};

export default campaignsView;
