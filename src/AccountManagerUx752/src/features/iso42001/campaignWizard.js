/**
 * ISO 42001 "Quick Start" campaign wizard — a guided, curated happy-path for creating a campaign (a
 * persisted iso42001.testConfig). The full editorModal in campaignsView.js exposes ~14 fields with no
 * guidance and a silent prerequisite (you must have configured an LLM endpoint first); this wizard walks
 * that prerequisite explicitly and curates the rest.
 *
 * Pattern mirrors chat/ChatSetupWizard.js: module-level step state + step renderers + Back/Next + a final
 * action, and a `WizardView` Mithril component (renders null unless shown) mounted by the host view.
 *
 * Steps: Endpoint → Scope → Depth → Name & Review.
 *
 * The create itself is delegated to campaignsView.createCampaignFromForm (shared CREATE path: group
 * resolve + formValues + createConfig + success toast; returns the record, does NOT navigate). Navigation
 * and the optional launch are this module's job. Wire semantics come entirely from formValues — the form
 * is seeded from campaignsView.blankForm(), so tier/samplesPerGroup/endpointType/moduleId are cast/lowered
 * exactly once, by formValues, at create time.
 *
 * NOTE on the import cycle: campaignsView.js imports { campaignWizard } from here, and here we import
 * { blankForm, createCampaignFromForm } from campaignsView.js. Safe because every cross-reference is used
 * at call time (inside functions), never at module-init.
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { blankForm, createCampaignFromForm } from './campaignsView.js';

// Curated: only tier 1 and tier 2. Tier 0 is intentionally omitted — there is a known backend
// tier=0 → Tier-1 correctness defect (Iso42001UxGapAnalysis.md). The full editorModal still exposes tier 0,
// so this is curation of the happy-path, not a capability removal.
const TIERS = [
    { value: 1, label: 'Tier 1 — Standard', hint: 'Core bias checks' },
    { value: 2, label: 'Tier 2 — Extended', hint: 'Adds deeper / statistical checks' }
];

// Endpoint types mirror the editorModal select. Endpoints (chat configs) come back as {objectId,name} only,
// carrying no type, so the type is chosen separately here just like the full editor.
const ENDPOINT_TYPES = ['ollama', 'openai', 'anthropic', 'azure'];

const STEP_TITLES = ['LLM Endpoint', 'Scope', 'Depth', 'Name & Review'];

const INPUT_CLS = 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white';
const SELECT_CLS = INPUT_CLS;
const ACTIVE_BTN = 'border-blue-500 bg-blue-50 dark:bg-blue-900 text-blue-800 dark:text-white';
const IDLE_BTN = 'border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700';

let _visible = false;
let _step = 0;
let _busy = false;
let _error = null;
let _form = blankForm();
let _endpoints = [];
let _modules = [];
let _lookupsLoaded = false;

async function ensureLookups() {
    if (_lookupsLoaded) return;
    try { let ep = await iso42001Client.endpoints(); _endpoints = Array.isArray(ep) ? ep : []; } catch (e) { _endpoints = []; }
    try { let mo = await iso42001Client.modules(); _modules = mo ? Object.keys(mo) : []; } catch (e) { _modules = []; }
    _lookupsLoaded = true;
    m.redraw();
}

/** Create the campaign via the shared helper, then navigate (and optionally launch a run first). */
async function finish(launch) {
    if (_busy) return;
    if (!_form.name.trim()) { _error = 'Campaign name is required.'; m.redraw(); return; }
    if (!_form.endpointName.trim()) { _error = 'Select an LLM endpoint on step 1.'; _step = 0; m.redraw(); return; }
    _busy = true; _error = null; m.redraw();
    try {
        // Shared CREATE path: shows its own success/error toast, returns the record or null. No navigation.
        let created = await createCampaignFromForm(_form);
        if (!created || !created.objectId) { _busy = false; m.redraw(); return; }
        if (launch) {
            try {
                let run = await iso42001Client.startRun(created.objectId);
                if (run && run.objectId) {
                    page.toast && page.toast('success', 'Run launched.');
                } else {
                    page.toast && page.toast('error', 'Campaign created, but launch failed (endpoint unresolved or access denied).');
                }
            } catch (e) {
                page.toast && page.toast('error', 'Campaign created, but launch failed: ' + (e && e.message ? e.message : e));
            }
        }
        _visible = false;
        m.route.set('/iso42001/campaigns/' + created.objectId);
    } catch (e) {
        _error = 'Create failed: ' + (e && e.message ? e.message : e);
    }
    _busy = false;
    m.redraw();
}

function renderEndpointStep() {
    if (!_endpoints.length) {
        return m('div', { class: 'p-4' }, [
            m('p', { class: 'mb-3 text-sm text-gray-600 dark:text-gray-300' },
                'A campaign tests an LLM endpoint, but none are configured yet.'),
            m('div', { class: 'p-3 rounded bg-amber-50 dark:bg-amber-900/30 text-sm text-amber-700 dark:text-amber-300' }, [
                m('p', { class: 'mb-2' }, 'You must configure an LLM endpoint (a chat config) before you can start a campaign.'),
                m('button', {
                    class: 'px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500',
                    onclick: () => { _visible = false; m.route.set('/chat'); }
                }, 'Configure an endpoint')
            ])
        ]);
    }
    return m('div', { class: 'p-4' }, [
        m('p', { class: 'mb-3 text-sm text-gray-600 dark:text-gray-300' }, 'Choose the LLM endpoint this campaign will test:'),
        m('div', { class: 'flex flex-col gap-2 max-h-56 overflow-y-auto' },
            _endpoints.map(ep => {
                let active = _form.endpointName === ep.name;
                return m('button', {
                    key: ep.objectId || ep.name,
                    class: 'p-2 text-left rounded border ' + (active ? ACTIVE_BTN : IDLE_BTN),
                    onclick: () => { _form.endpointName = ep.name; m.redraw(); }
                }, ep.name);
            })
        ),
        m('label', { class: 'flex flex-col gap-1 text-sm mt-3' }, [
            m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Endpoint type'),
            m('select', {
                class: SELECT_CLS,
                value: _form.endpointType,
                onchange: e => { _form.endpointType = e.target.value; }
            }, ENDPOINT_TYPES.map(t => m('option', { value: t }, t)))
        ])
    ]);
}

function renderScopeStep() {
    let moduleOptions = ['BIAS'].concat(_modules).filter((v, i, a) => a.indexOf(v) === i);
    return m('div', { class: 'p-4 flex flex-col gap-3' }, [
        m('p', { class: 'text-sm text-gray-600 dark:text-gray-300' }, 'What should this campaign test?'),
        m('label', { class: 'flex flex-col gap-1 text-sm' }, [
            m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Test module'),
            m('select', {
                class: SELECT_CLS, value: _form.moduleId,
                onchange: e => { _form.moduleId = e.target.value; }
            }, moduleOptions.map(mo => m('option', { value: mo }, mo)))
        ]),
        m('label', { class: 'flex flex-col gap-1 text-sm' }, [
            m('span', { class: 'text-gray-600 dark:text-gray-300' },
                'Test IDs (optional, comma-separated — empty = all tests in the module)'),
            m('input', {
                type: 'text', class: INPUT_CLS, value: _form.testIds,
                placeholder: 'e.g. GENDER-01, RACE-02',
                oninput: e => { _form.testIds = e.target.value; }
            })
        ])
    ]);
}

function renderDepthStep() {
    return m('div', { class: 'p-4 flex flex-col gap-3' }, [
        m('p', { class: 'text-sm text-gray-600 dark:text-gray-300' }, 'How thoroughly should it test?'),
        m('div', { class: 'flex flex-col gap-1 text-sm' }, [
            m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Tier'),
            m('div', { class: 'flex gap-2' }, TIERS.map(t => {
                let active = Number(_form.tier) === t.value;
                return m('button', {
                    class: 'flex-1 p-2 rounded border text-left ' + (active ? ACTIVE_BTN : IDLE_BTN),
                    onclick: () => { _form.tier = t.value; m.redraw(); }
                }, [
                    m('div', { class: 'text-sm font-medium' }, t.label),
                    m('div', { class: 'text-xs opacity-70' }, t.hint)
                ]);
            }))
        ]),
        m('label', { class: 'flex flex-col gap-1 text-sm' }, [
            m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Samples per group'),
            m('input', {
                type: 'number', class: INPUT_CLS, value: _form.samplesPerGroup,
                oninput: e => { _form.samplesPerGroup = e.target.value; }
            })
        ])
    ]);
}

function renderReviewStep() {
    let row = (k, v) => m('div', { class: 'flex justify-between gap-4' }, [
        m('span', { class: 'text-gray-500' }, k),
        m('span', { class: 'text-gray-800 dark:text-white text-right' }, v == null || v === '' ? '—' : String(v))
    ]);
    return m('div', { class: 'p-4 flex flex-col gap-3' }, [
        m('label', { class: 'flex flex-col gap-1 text-sm' }, [
            m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'Campaign name'),
            m('input', {
                type: 'text', class: INPUT_CLS, value: _form.name,
                placeholder: 'e.g. Gender bias — GPT-4o',
                oninput: e => { _form.name = e.target.value; }
            })
        ]),
        m('div', { class: 'flex flex-col gap-2 p-3 rounded bg-gray-50 dark:bg-gray-800 text-sm' }, [
            row('Endpoint', _form.endpointName + ' (' + _form.endpointType + ')'),
            row('Module', _form.moduleId),
            row('Test IDs', _form.testIds && _form.testIds.trim() ? _form.testIds : 'all'),
            row('Tier', _form.tier),
            row('Samples / group', _form.samplesPerGroup)
        ])
    ]);
}

const _stepRenderers = [renderEndpointStep, renderScopeStep, renderDepthStep, renderReviewStep];

export const campaignWizard = {
    show: function () {
        _visible = true;
        _step = 0;
        _error = null;
        _busy = false;
        _form = blankForm();
        ensureLookups();
        m.redraw();
    },

    hide: () => { _visible = false; m.redraw(); },
    isVisible: () => _visible,

    WizardView: {
        view: function () {
            if (!_visible) return null;
            let isFirst = _step === 0;
            let isLast = _step === STEP_TITLES.length - 1;
            // On the endpoint step, block Next until an endpoint exists AND one is selected.
            let blockNext = _step === 0 && (!_endpoints.length || !_form.endpointName);
            let primaryBtn = 'px-3 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50';

            return m('div', { class: 'fixed inset-0 z-50 flex items-center justify-center' }, [
                m('div', {
                    class: 'absolute inset-0 bg-black/50',
                    onclick: () => { if (!_busy) { _visible = false; m.redraw(); } }
                }),
                m('div', { class: 'relative bg-white dark:bg-gray-900 rounded-lg shadow-xl w-full max-w-md mx-4' }, [
                    m('div', { class: 'p-4 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between' }, [
                        m('h3', { class: 'text-lg font-semibold text-gray-800 dark:text-white' }, 'Quick Start — New Campaign'),
                        m('span', { class: 'text-xs text-gray-400' }, 'Step ' + (_step + 1) + ' of ' + STEP_TITLES.length)
                    ]),
                    m('h4', { class: 'text-sm font-bold px-4 pt-3 text-gray-700 dark:text-gray-200' }, STEP_TITLES[_step]),
                    _stepRenderers[_step](),
                    _error ? m('p', { class: 'px-4 text-red-500 text-sm' }, _error) : null,
                    m('div', { class: 'p-4 border-t border-gray-200 dark:border-gray-700 flex justify-between' }, [
                        !isFirst ? m('button', {
                            class: 'px-3 py-1.5 rounded text-sm border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-800',
                            disabled: _busy,
                            onclick: () => { _step--; _error = null; m.redraw(); }
                        }, 'Back') : m('span'),
                        m('div', { class: 'flex gap-2' }, [
                            m('button', {
                                class: 'px-3 py-1.5 rounded text-sm border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-800',
                                disabled: _busy,
                                onclick: () => { _visible = false; m.redraw(); }
                            }, 'Cancel'),
                            isLast
                                ? [
                                    m('button', {
                                        class: primaryBtn,
                                        disabled: _busy,
                                        onclick: () => finish(false)
                                    }, _busy ? 'Creating…' : 'Create campaign'),
                                    m('button', {
                                        class: 'px-3 py-1.5 rounded text-sm bg-green-600 text-white hover:bg-green-500 disabled:opacity-50',
                                        disabled: _busy,
                                        onclick: () => finish(true)
                                    }, _busy ? 'Working…' : 'Create & launch')
                                ]
                                : m('button', {
                                    class: primaryBtn + (blockNext ? ' opacity-50 cursor-not-allowed' : ''),
                                    disabled: blockNext,
                                    onclick: () => { if (!blockNext) { _step++; _error = null; m.redraw(); } }
                                }, 'Next')
                        ])
                    ])
                ])
            ]);
        }
    }
};

export default campaignWizard;
