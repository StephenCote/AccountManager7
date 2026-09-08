/**
 * ISO 42001 "Quick Start" campaign wizard — pure state/step behavior (features/iso42001/campaignWizard.js).
 *
 * Two layers, both deterministic and network-free (environment: 'node'):
 *
 *  1. Pure form helpers exported by campaignsView.js (blankForm / formValues) — the wizard seeds _form from
 *     blankForm() and every wire value is cast exactly once by formValues at create time. These assert the
 *     shape blankForm() produces and the create-body mapping (tier/samplesPerGroup Number-cast, moduleId,
 *     testIds → array, name trim) the wizard depends on.
 *
 *  2. The wizard's real step-gating + create wiring, exercised through the actual WizardView.view() vnode
 *     tree and the component's own onclick handlers — NOT a re-implementation. mithril is mocked as a plain
 *     hyperscript so the returned tree can be walked; iso42001Client + pageClient are mocked so no HTTP is
 *     issued (createConfig / startRun / makePath resolve locally). This proves, without a live server:
 *       - Endpoint step: "Next" is disabled until an endpoint is actually selected.
 *       - Depth step: tier options are exactly {1, 2} — tier 0 is intentionally absent.
 *       - Name & Review: an empty name blocks the create action (no createConfig call, error shown); once a
 *         name is set, "Create campaign" calls createConfig with the mapped body and routes to the campaign.
 *       - "Create & launch" additionally calls startRun(objectId) after the create.
 *
 * setup.js provides the RAF shim (m.redraw fires through the mocked mithril here, so RAF is not exercised,
 * but the shim stays harmless). Do NOT import mithril in setup.js.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

// ── mithril: minimal hyperscript so WizardView.view() yields a walkable {tag, attrs, children} tree ──
vi.mock('mithril', () => {
    const flatten = (arr) => arr.reduce((acc, x) => {
        if (Array.isArray(x)) return acc.concat(flatten(x));
        if (x == null || x === false) return acc;
        return acc.concat([x]);
    }, []);
    const isVnode = (x) => x && typeof x === 'object' && 'tag' in x;
    function h(tag, attrs, ...rest) {
        let a = {};
        let kids;
        if (attrs != null && typeof attrs === 'object' && !Array.isArray(attrs) && !isVnode(attrs)) {
            a = attrs;
            kids = rest;
        } else {
            kids = attrs === undefined ? rest : [attrs, ...rest];
        }
        return { tag, attrs: a, children: flatten(kids) };
    }
    h.redraw = vi.fn();
    h.request = vi.fn(() => Promise.resolve(null));
    h.route = { set: vi.fn(), param: vi.fn(() => null) };
    h.trust = (s) => s;
    return { default: h };
});

// ── feature-local REST client: no network; controllable resolutions per test ──
vi.mock('../features/iso42001/iso42001Client.js', () => {
    const iso42001Client = {
        endpoints: vi.fn(() => Promise.resolve([])),
        modules: vi.fn(() => Promise.resolve({})),
        createConfig: vi.fn(() => Promise.resolve(null)),
        startRun: vi.fn(() => Promise.resolve(null)),
        getConfig: vi.fn(() => Promise.resolve(null)),
        list: vi.fn(() => Promise.resolve({ results: [] })),
        listAnalysisProfiles: vi.fn(() => Promise.resolve([]))
    };
    return { iso42001Client, default: iso42001Client };
});

// ── page client: makePath / toast / context are the only surfaces the create path touches ──
vi.mock('../core/pageClient.js', () => ({
    page: {
        user: { organizationId: 2 },
        context: () => ({ roles: { iso42001Tester: true } }),
        toast: vi.fn(),
        makePath: vi.fn(() => Promise.resolve({ id: 5, organizationId: 2 })),
        patchObject: vi.fn(),
        deleteObject: vi.fn()
    }
}));

import m from 'mithril';
import { iso42001Client } from '../features/iso42001/iso42001Client.js';
import { page } from '../core/pageClient.js';
import { campaignWizard } from '../features/iso42001/campaignWizard.js';
import { blankForm, formValues } from '../features/iso42001/campaignsView.js';

// ── tree-walking helpers over the mocked hyperscript vnodes ──
const flush = () => new Promise((r) => setTimeout(r, 0));

function render() {
    return campaignWizard.WizardView.view();
}
function collect(node, pred, out = []) {
    if (node == null || typeof node !== 'object') return out;
    if (Array.isArray(node)) {
        node.forEach((n) => collect(n, pred, out));
        return out;
    }
    if (pred(node)) out.push(node);
    if (node.children) collect(node.children, pred, out);
    return out;
}
function textOf(node) {
    if (node == null || node === false) return '';
    if (typeof node === 'string' || typeof node === 'number') return String(node);
    if (Array.isArray(node)) return node.map(textOf).join('');
    if (node.children) return textOf(node.children);
    return '';
}
function buttons() {
    return collect(render(), (v) => v.tag === 'button');
}
function buttonByText(t) {
    return buttons().find((b) => textOf(b).trim() === t);
}
function inputByPlaceholder(p) {
    return collect(render(), (v) => v.tag === 'input' && v.attrs && v.attrs.placeholder && v.attrs.placeholder.includes(p))[0];
}
function currentStepIndex() {
    let match = textOf(render()).match(/Step (\d) of 4/);
    return match ? Number(match[1]) - 1 : -1;
}
async function showAndLoad() {
    campaignWizard.show();
    await flush();
    await flush();
}
function selectEndpoint(name) {
    let b = buttonByText(name);
    expect(b, 'endpoint button "' + name + '" should render').toBeTruthy();
    b.attrs.onclick();
}
function clickNext() {
    let b = buttonByText('Next');
    expect(b, '"Next" button should render').toBeTruthy();
    b.attrs.onclick();
}

beforeEach(() => {
    iso42001Client.endpoints.mockReset().mockResolvedValue([{ objectId: 'ep-1', name: 'generalChat' }]);
    iso42001Client.modules.mockReset().mockResolvedValue({});
    iso42001Client.createConfig.mockReset().mockResolvedValue({ objectId: 'new-cfg-1', urn: 'urn:iso:new', id: 99 });
    iso42001Client.startRun.mockReset().mockResolvedValue({ objectId: 'run-1' });
    page.makePath.mockReset().mockResolvedValue({ id: 5, organizationId: 2 });
    page.toast.mockReset();
    m.route.set.mockClear();
});

describe('campaign wizard — pure form helpers (blankForm / formValues)', () => {
    it('blankForm() shape is what the wizard seeds _form from', () => {
        let f = blankForm();
        expect(f.name).toBe('');
        expect(f.moduleId).toBe('BIAS');
        expect(f.testIds).toBe('');
        expect(f.endpointName).toBe('');
        expect(f.endpointType).toBe('ollama');
        expect(f.tier).toBe(1);
        expect(f.samplesPerGroup).toBe(30);
    });

    it('formValues() maps wizard state into the create body (tier/samples Number-cast, ids→array, name trim)', () => {
        let f = blankForm();
        f.name = '  Gender bias — GPT-4o  ';
        f.endpointName = ' generalChat ';
        f.moduleId = 'BIAS';
        f.testIds = 'GENDER-01, RACE-02 , RELIGION-03';
        f.tier = '2';            // wizard sets Number from TIERS, but a string must still cast
        f.samplesPerGroup = '5'; // number input yields a string
        let v = formValues(f);
        expect(v.name).toBe('Gender bias — GPT-4o');
        expect(v.endpointName).toBe('generalChat');
        expect(v.moduleId).toBe('BIAS');
        expect(v.testIds).toEqual(['GENDER-01', 'RACE-02', 'RELIGION-03']);
        expect(v.tier).toBe(2);
        expect(typeof v.tier).toBe('number');
        expect(v.samplesPerGroup).toBe(5);
        expect(typeof v.samplesPerGroup).toBe('number');
    });

    it('formValues() empty testIds → [] (empty = all tests in the module)', () => {
        let f = blankForm();
        f.name = 'X';
        f.testIds = '';
        expect(formValues(f).testIds).toEqual([]);
    });
});

describe('campaign wizard — step gating & create wiring (real WizardView tree)', () => {
    it('WizardView renders null until show() is called', () => {
        campaignWizard.hide();
        expect(campaignWizard.WizardView.view()).toBeNull();
    });

    it('Endpoint step: "Next" is disabled until an endpoint is selected', async () => {
        await showAndLoad();
        expect(campaignWizard.isVisible()).toBe(true);
        expect(currentStepIndex()).toBe(0);

        let nextBefore = buttonByText('Next');
        expect(nextBefore.attrs.disabled).toBe(true); // blockNext: endpoint list present but none chosen

        selectEndpoint('generalChat');

        let nextAfter = buttonByText('Next');
        expect(nextAfter.attrs.disabled).toBe(false);
    });

    it('Depth step: tier options are exactly {1, 2} (tier 0 intentionally absent)', async () => {
        await showAndLoad();
        selectEndpoint('generalChat');
        clickNext(); // → Scope
        clickNext(); // → Depth
        expect(currentStepIndex()).toBe(2);

        let tierButtons = buttons().filter((b) => /Tier\s*\d/.test(textOf(b)));
        let tierNumbers = tierButtons
            .map((b) => {
                let mm = textOf(b).match(/Tier\s*(\d)/);
                return mm ? Number(mm[1]) : null;
            })
            .sort();
        expect(tierNumbers).toEqual([1, 2]);
        expect(tierNumbers).not.toContain(0);
    });

    it('Name & Review: empty name blocks create (no createConfig call, error shown)', async () => {
        await showAndLoad();
        selectEndpoint('generalChat');
        clickNext(); // Scope
        clickNext(); // Depth
        clickNext(); // Review
        expect(currentStepIndex()).toBe(3);

        // Name left blank → clicking "Create campaign" must not create.
        buttonByText('Create campaign').attrs.onclick();
        await flush();

        expect(iso42001Client.createConfig).not.toHaveBeenCalled();
        expect(m.route.set).not.toHaveBeenCalled();
        expect(textOf(render())).toContain('Campaign name is required');
    });

    it('Name & Review: with a name, "Create campaign" creates and routes to the campaign', async () => {
        await showAndLoad();
        selectEndpoint('generalChat');
        clickNext();
        clickNext();
        clickNext();
        expect(currentStepIndex()).toBe(3);

        let nameInput = inputByPlaceholder('Gender bias');
        expect(nameInput, 'campaign name input should render on Review').toBeTruthy();
        nameInput.attrs.oninput({ target: { value: 'Wizard E2E Campaign' } });

        buttonByText('Create campaign').attrs.onclick();
        await flush();
        await flush();

        expect(iso42001Client.createConfig).toHaveBeenCalledTimes(1);
        let body = iso42001Client.createConfig.mock.calls[0][0];
        expect(body.schema).toBe('iso42001.testConfig');
        expect(body.name).toBe('Wizard E2E Campaign');
        expect(body.endpointName).toBe('generalChat');
        expect(body.moduleId).toBe('BIAS');
        expect(body.tier).toBe(1);
        expect(body.samplesPerGroup).toBe(30);
        expect(body.groupId).toBe(5);            // from page.makePath resolution
        expect(body.organizationId).toBe(2);

        expect(iso42001Client.startRun).not.toHaveBeenCalled(); // "Create campaign" != launch
        expect(m.route.set).toHaveBeenCalledWith('/iso42001/campaigns/new-cfg-1');
    });

    it('Name & Review: "Create & launch" creates, then launches a run, then routes', async () => {
        await showAndLoad();
        selectEndpoint('generalChat');
        clickNext();
        clickNext();
        clickNext();

        inputByPlaceholder('Gender bias').attrs.oninput({ target: { value: 'Wizard Launch Campaign' } });

        buttonByText('Create & launch').attrs.onclick();
        await flush();
        await flush();

        expect(iso42001Client.createConfig).toHaveBeenCalledTimes(1);
        expect(iso42001Client.startRun).toHaveBeenCalledTimes(1);
        expect(iso42001Client.startRun.mock.calls[0][0]).toBe('new-cfg-1'); // launch uses the created objectId
        expect(m.route.set).toHaveBeenCalledWith('/iso42001/campaigns/new-cfg-1');
    });
});
