// @vitest-environment jsdom
/**
 * PictureBook V3 book style editor (P2-9, design §26) — /picture-book/:id/style.
 *
 * Why: the style editor must show server state only (own vs effective, "Inherited from <hop>"
 * strictly from effective.sources), save a SPARSE PUT of the touched keys (JSON null clears), toast
 * every server message verbatim (enum 400, lock 409, lock-404), probe the installed-model list ONCE
 * when the inventory is unavailable, and manage the style-owned olio.sd.config through the generic
 * model routes (POST /rest/model from the template, sparse PATCH of inst.changes, never `name`).
 * Shapes below are the live captures from the Docker stack (C:\tmp\p29-put-*.json, 2026-10-15).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7sd } from '../components/sdConfig.js';
import { normalizeStyle, STYLE_TASKS } from '../workflows/pictureBookWorkflow.js';
import {
    TEXT_FIELDS, SD_CREATE_EXCLUDE, SD_PATCH_EXCLUDE,
    hopName, styleFieldValue, inheritedFrom, taskModelInheritedFrom, taskModelValue, draftValue, draftTaskModel,
    buildStylePut, sdConfigMode, sdPatchBody, sdCreateBody, taskAvailability,
    loadStyle, saveStyle, toggleLock, saveSdConfig, createSdConfig, createSdOverride, useBookSdConfig, unlinkSdConfig,
    renderStyleHeader, renderStyleBody, __styleStateForTest, __setStyleStateForTest
} from '../features/pictureBookStyle.js';

function jsonResponse(status, body) {
    let text = typeof body === 'string' ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, json: async () => JSON.parse(text) };
}

function flushPromises() { return new Promise(r => setTimeout(r, 0)); }

function findByTestId(vnode, testid) {
    let found = null;
    (function walk(v) {
        if (!v || found) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-testid'] === testid) { found = v; return; }
        walk(v.children);
    })(vnode);
    return found;
}

// Fresh book: no own style, effective carries only the two defaults (measured GET /{book}/style).
const FRESH = { bookObjectId: 'B1', effective: { qualityPreset: 'STANDARD', pageFormat: 'SQUARE' }, cycleDetected: false, complete: true };

// After PUT {description, taskModels:{portrait:'x'}} on a book whose parent sets palette + sdConfig.
const OWN_OID = '40ae4508-d24d-4460-b4a0-2bc5c342dc55';
const PARENT_OID = 'parent-oid-1';
const WITH_OWN = {
    bookObjectId: 'B1',
    own: { objectId: OWN_OID, name: 'p29-probe-049158-style', scope: 'BOOK', locked: false, fields: { description: 'ink wash', taskModels: { portrait: 'x.safetensors' } } },
    effective: {
        fields: { description: 'ink wash', palette: 'sepia', sdConfig: 'sd-parent', taskModels: { portrait: 'x.safetensors', location: 'y.safetensors' } },
        taskModels: { portrait: 'x.safetensors', location: 'y.safetensors' },
        taskModelSources: { portrait: OWN_OID, location: PARENT_OID },
        qualityPreset: 'standard', pageFormat: 'square', sdConfigName: 'parent-sd',
        sources: { description: OWN_OID, palette: PARENT_OID, sdConfig: PARENT_OID, taskModels: OWN_OID }
    },
    chain: [{ objectId: OWN_OID, name: 'p29-probe-049158-style', scope: 'BOOK', locked: false }, { objectId: PARENT_OID, name: 'house-style', scope: 'SERIES', locked: true }],
    cycleDetected: false, complete: true
};

const INV_DOWN = { task: 'portrait', inventory: { fetchedAt: '2026-10-15T07:00:00Z', error: 'Could not read the installed-model list from the SD server', count: 0 },
    deploymentDefault: { model: 'juggernautXL_v9.safetensors', flux2Model: null },
    resolution: { reason: 'INVENTORY_UNAVAILABLE', degraded: true, degradationText: 'x' } };
const INV_OK = { task: 'portrait', inventory: { fetchedAt: '2026-10-15T07:00:00Z', error: null, count: 2 },
    deploymentDefault: { model: 'juggernautXL_v9.safetensors' }, models: ['juggernautXL_v9.safetensors', 'flux1-dev.safetensors'],
    resolution: { reason: 'STYLE', degraded: false, model: 'x.safetensors' } };

let origFetch;

beforeEach(() => {
    origFetch = global.fetch;
    __setStyleStateForTest({ bookObjectId: 'B1', style: null, loading: false, error: null, draft: {}, saving: false, lockBusy: false, taskInfo: {}, book: null, sd: {}, sdModels: [], sdLoras: [] });
    vi.spyOn(page, 'toast').mockImplementation(() => {});
});

afterEach(() => {
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

// ─────────────────────────────── pure derivations ───────────────────────────────

describe('normalizeStyle (workflow helper)', () => {
    it('fresh book: own null, empty maps, enums uppercased, chain [], complete', () => {
        let st = normalizeStyle(FRESH);
        expect(st.own).toBeNull();
        expect(st.effective.fields).toEqual({});
        expect(st.effective.sources).toEqual({});
        expect(st.effective.taskModels).toEqual({});
        expect(st.effective.qualityPreset).toBe('STANDARD');
        expect(st.effective.pageFormat).toBe('SQUARE');
        expect(st.chain).toEqual([]);
        expect(st.complete).toBe(true);
        expect(st.cycleDetected).toBe(false);
    });
    it('lowercase wire enums → uppercase; own.fields kept; missing complete → true; cycle → bool', () => {
        let st = normalizeStyle(WITH_OWN);
        expect(st.effective.qualityPreset).toBe('STANDARD');
        expect(st.effective.pageFormat).toBe('SQUARE');
        expect(st.own.fields.description).toBe('ink wash');
        expect(normalizeStyle({ cycleDetected: 1 }).cycleDetected).toBe(true);
        expect(normalizeStyle({ complete: false }).complete).toBe(false);
        expect(normalizeStyle(null).own).toBeNull();
    });
});

describe('field / inheritance derivations', () => {
    const st = normalizeStyle(WITH_OWN);
    it('hopName resolves a chain hop; unknown oid falls back to the oid', () => {
        expect(hopName(st, PARENT_OID)).toBe('house-style');
        expect(hopName(st, 'nope')).toBe('nope');
        expect(hopName(st, null)).toBeNull();
    });
    it('styleFieldValue: own first, then effective.fields, then the two effective defaults', () => {
        expect(styleFieldValue(st, 'description')).toBe('ink wash');
        expect(styleFieldValue(st, 'palette')).toBe('sepia');
        expect(styleFieldValue(st, 'qualityPreset')).toBe('STANDARD');
        expect(styleFieldValue(st, 'pageFormat')).toBe('SQUARE');
        expect(styleFieldValue(st, 'exclusions')).toBeUndefined();
        expect(styleFieldValue(null, 'x')).toBeUndefined();
    });
    it('inheritedFrom is strictly effective.sources: own source → null, parent → hop name, unset → null', () => {
        expect(inheritedFrom(st, 'description')).toBeNull();
        expect(inheritedFrom(st, 'palette')).toBe('house-style');
        expect(inheritedFrom(st, 'sdConfig')).toBe('house-style');
        expect(inheritedFrom(st, 'exclusions')).toBeNull();
        expect(inheritedFrom(normalizeStyle(FRESH), 'qualityPreset')).toBeNull();
    });
    it('taskModelInheritedFrom / taskModelValue follow taskModelSources and own.fields.taskModels', () => {
        expect(taskModelInheritedFrom(st, 'portrait')).toBeNull();
        expect(taskModelInheritedFrom(st, 'location')).toBe('house-style');
        expect(taskModelInheritedFrom(st, 'upscale')).toBeNull();
        expect(taskModelValue(st, 'portrait')).toBe('x.safetensors');
        expect(taskModelValue(st, 'location')).toBe('y.safetensors');
        expect(taskModelValue(st, 'upscale')).toBeNull();
    });
    it('draftValue / draftTaskModel prefer the touched draft (including null = clear)', () => {
        expect(draftValue({ description: null }, st, 'description')).toBeNull();
        expect(draftValue({}, st, 'description')).toBe('ink wash');
        expect(draftTaskModel({ taskModels: { portrait: '' } }, st, 'portrait')).toBe('');
        expect(draftTaskModel({}, st, 'portrait')).toBe('x.safetensors');
    });
});

describe('buildStylePut — sparse body', () => {
    it('nothing touched → null', () => {
        expect(buildStylePut({})).toBeNull();
        expect(buildStylePut(null)).toBeNull();
        expect(buildStylePut({ taskModels: {} })).toBeNull();
    });
    it('text fields: "" → null (clear); custom dims → Number or null; sdConfig → {objectId} or null', () => {
        expect(buildStylePut({ description: '', palette: 'sepia', exclusions: undefined }))
            .toEqual({ description: null, palette: 'sepia', exclusions: null });
        expect(buildStylePut({ customWidth: '1024', customHeight: '' })).toEqual({ customWidth: 1024, customHeight: null });
        expect(buildStylePut({ customWidth: 'abc' })).toEqual({ customWidth: null });
        expect(buildStylePut({ sdConfig: { objectId: 'sd1', name: 'ignored' } })).toEqual({ sdConfig: { objectId: 'sd1' } });
        expect(buildStylePut({ sdConfig: null })).toEqual({ sdConfig: null });
        expect(TEXT_FIELDS).toEqual(['description', 'exclusions', 'palette']);
    });
    it('taskModels merge into one map with "" → null; enums/booleans pass through', () => {
        expect(buildStylePut({ taskModels: { portrait: 'x', location: '' }, qualityPreset: 'DRAFT', photoreal: true }))
            .toEqual({ taskModels: { portrait: 'x', location: null }, qualityPreset: 'DRAFT', photoreal: true });
    });
});

describe('sdConfigMode', () => {
    it('own when effective.sources.sdConfig is the own style; own when own.fields.sdConfig matches with no source', () => {
        let own = normalizeStyle({ own: { objectId: 'o', fields: { sdConfig: 'sd1' } }, effective: { fields: { sdConfig: 'sd1' }, sources: { sdConfig: 'o' } } });
        expect(sdConfigMode(own, null)).toBe('own');
        let ownNoSrc = normalizeStyle({ own: { objectId: 'o', fields: { sdConfig: 'sd1' } }, effective: { fields: { sdConfig: 'sd1' } } });
        expect(sdConfigMode(ownNoSrc, null)).toBe('own');
    });
    it('inherited when a parent hop sets it; book when only the book record carries an FK; none otherwise', () => {
        expect(sdConfigMode(normalizeStyle(WITH_OWN), null)).toBe('inherited');
        expect(sdConfigMode(normalizeStyle(FRESH), { sdConfig: { objectId: 'sdb', id: 180 } })).toBe('book');
        expect(sdConfigMode(normalizeStyle(FRESH), { sdConfig: {} })).toBe('none');
        expect(sdConfigMode(normalizeStyle(FRESH), null)).toBe('none');
        expect(sdConfigMode(null, null)).toBe('none');
    });
});

describe('sdPatchBody / sdCreateBody', () => {
    it('sdPatchBody: only inst.changes minus identity/name, plus schema + objectId + id; null when nothing changed', () => {
        let inst = { entity: { objectId: 'sd1', id: 180, name: 'keep-me', steps: 30, cfg: 6.5, model: 'm.safetensors' }, changes: ['steps', 'steps', 'name', 'cfg', 'id'] };
        expect(sdPatchBody(inst)).toEqual({ schema: 'olio.sd.config', objectId: 'sd1', id: 180, steps: 30, cfg: 6.5 });
        expect(sdPatchBody({ entity: { objectId: 'sd1' }, changes: [] })).toBeNull();
        expect(sdPatchBody({ entity: { objectId: 'sd1' }, changes: ['name'] })).toBeNull();
        expect(sdPatchBody({ entity: {}, changes: ['steps'] })).toBeNull();
        expect(sdPatchBody(null)).toBeNull();
        expect(SD_PATCH_EXCLUDE).toContain('schema');
        expect(SD_PATCH_EXCLUDE).toContain('name');
    });
    it('sdCreateBody: strips the randomImageConfig SAVE_EXCLUDE set (+urn), sets schema/name/group of the book', () => {
        let tpl = { schema: 'olio.sd.config', id: 1, objectId: 'tpl', ownerId: 2, name: 'tpl', groupId: 9, groupPath: '/t', organizationId: 3, organizationPath: '/o', urn: 'u', narration: 'n',
            steps: 25, cfg: 7, model: 'm.safetensors', width: 1024, height: 1024, sampler: 'euler' };
        let body = sdCreateBody(tpl, 'p29-style-sd-abc', { groupId: 32789, groupPath: '/Olio/Universes/Books/Worlds/p29/Book' });
        expect(body).toEqual({ schema: 'olio.sd.config', name: 'p29-style-sd-abc', groupId: 32789, groupPath: '/Olio/Universes/Books/Worlds/p29/Book',
            steps: 25, cfg: 7, model: 'm.safetensors', width: 1024, height: 1024, sampler: 'euler' });
        SD_CREATE_EXCLUDE.forEach(k => { if (k !== 'name' && k !== 'groupId' && k !== 'groupPath') expect(body).not.toHaveProperty(k); });
        expect(sdCreateBody(null, 'x', {})).toBeNull();
    });
});

describe('taskAvailability', () => {
    it('inventory.error → unavailable with the server message and the deployment default', () => {
        let av = taskAvailability({ loading: false, data: INV_DOWN, error: null });
        expect(av.unavailable).toBe(true);
        expect(av.text).toBe('installed-model list unavailable: Could not read the installed-model list from the SD server');
        expect(av.deploymentDefault).toEqual({ model: 'juggernautXL_v9.safetensors', flux2Model: null });
    });
    it('a transport error → unavailable with that message; nothing yet → empty', () => {
        expect(taskAvailability({ error: '(500) SD server unconfigured' })).toEqual({ text: '(500) SD server unconfigured', unavailable: true, deploymentDefault: null });
        expect(taskAvailability(null)).toEqual({ text: '', unavailable: false, deploymentDefault: null });
    });
    it('a successful read → count · reason → model and the model list', () => {
        let av = taskAvailability({ data: INV_OK });
        expect(av.unavailable).toBe(false);
        expect(av.text).toBe('2 installed · STYLE → x.safetensors');
        expect(av.models).toEqual(['juggernautXL_v9.safetensors', 'flux1-dev.safetensors']);
    });
});

// ─────────────────────────────── rendering ───────────────────────────────

describe('renderStyleHeader', () => {
    it('no own style: Lock disabled with the reason as title; Save disabled until the draft is dirty', () => {
        __setStyleStateForTest({ style: normalizeStyle(FRESH), draft: {} });
        let lock = findByTestId(renderStyleHeader(), 'pb2-style-lock');
        expect(lock.attrs.disabled).toBe(true);
        expect(lock.attrs.title).toBe('The book has no style of its own to lock; save a field first');
        expect(findByTestId(renderStyleHeader(), 'pb2-style-save').attrs.disabled).toBe(true);
        expect(findByTestId(renderStyleHeader(), 'pb2-style-own-name')).toBeNull();
        __setStyleStateForTest({ draft: { description: 'x' } });
        expect(findByTestId(renderStyleHeader(), 'pb2-style-save').attrs.disabled).toBe(false);
    });
    it('own style: name shown, Lock enabled; locked → badge with lockedBy and "Unlock"', () => {
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN) });
        let text = JSON.stringify(renderStyleHeader());
        expect(text).toContain('"data-testid":"pb2-style-own-name"');
        expect(text).toContain('p29-probe-049158-style');
        expect(findByTestId(renderStyleHeader(), 'pb2-style-lock').attrs.disabled).toBe(false);
        expect(text).not.toContain('pb2-style-locked');
        // lockedBy is the objectId of the locking style (PbServiceFacade.styleDto); the badge names the hop.
        let locked = normalizeStyle(Object.assign({}, WITH_OWN, { own: Object.assign({}, WITH_OWN.own, { locked: true }), lockedBy: OWN_OID }));
        __setStyleStateForTest({ style: locked });
        text = JSON.stringify(renderStyleHeader());
        expect(text).toContain('"data-testid":"pb2-style-locked"');
        expect(text).toContain('Locked by p29-probe-049158-style');
        expect(text).not.toContain('Locked by ' + OWN_OID);
        expect(text).toContain('Unlock');
        let parentLocked = normalizeStyle(Object.assign({}, WITH_OWN, { lockedBy: PARENT_OID }));
        __setStyleStateForTest({ style: parentLocked });
        expect(JSON.stringify(renderStyleHeader())).not.toContain('pb2-style-locked');
        let unknownHop = normalizeStyle(Object.assign({}, WITH_OWN, { own: Object.assign({}, WITH_OWN.own, { locked: true }), lockedBy: 'not-in-chain' }));
        __setStyleStateForTest({ style: unknownHop });
        expect(JSON.stringify(renderStyleHeader())).toContain('Locked by not-in-chain');
        expect(JSON.stringify(renderStyleHeader())).toContain('"data-testid":"pb2-style-back"');
    });
});

describe('renderStyleBody', () => {
    it('loading / error / null states', () => {
        __setStyleStateForTest({ style: null, loading: true });
        expect(JSON.stringify(renderStyleBody())).toContain('Loading book style...');
        __setStyleStateForTest({ loading: false, error: '(404) Book not found' });
        let text = JSON.stringify(renderStyleBody());
        expect(text).toContain('"data-testid":"pb2-style-error"');
        expect(text).toContain('(404) Book not found');
        __setStyleStateForTest({ error: null });
        expect(renderStyleBody()).toBeNull();
    });
    it('fresh book: empty-chain hint, defaults in the selects, no custom dims, SD "none" with Create, Phase-4 placeholder', () => {
        __setStyleStateForTest({ style: normalizeStyle(FRESH), sd: { mode: 'none' } });
        let body = renderStyleBody();
        let text = JSON.stringify(body);
        expect(text).toContain('No style of its own yet');
        expect(findByTestId(body, 'pb2-style-qualityPreset').attrs.value).toBe('STANDARD');
        expect(findByTestId(body, 'pb2-style-pageFormat').attrs.value).toBe('SQUARE');
        expect(text).not.toContain('pb2-style-customWidth');
        expect(text).toContain('"data-testid":"pb2-style-sd-none"');
        expect(text).toContain('"data-testid":"pb2-style-sd-create"');
        expect(text).toContain('no style-level reference-image routes exist yet (Phase 4)');
        STYLE_TASKS.forEach(t => expect(text).toContain('"data-testid":"pb2-style-taskModel-' + t + '"'));
    });
    it('CUSTOM page format (draft) shows the custom width/height inputs', () => {
        __setStyleStateForTest({ style: normalizeStyle(FRESH), draft: { pageFormat: 'CUSTOM' } });
        let text = JSON.stringify(renderStyleBody());
        expect(text).toContain('"data-testid":"pb2-style-customWidth"');
        expect(text).toContain('"data-testid":"pb2-style-customHeight"');
        expect(text).toContain('"data-testid":"pb2-style-dirty-pageFormat"');
    });
    it('own + parent chain: hops with data-own, "Inherited from house-style" only for parent-sourced fields, SD inherited with override', () => {
        let st = normalizeStyle(WITH_OWN);
        __setStyleStateForTest({ style: st, sd: { mode: 'inherited', oid: 'sd-parent', name: 'parent-sd', fromName: inheritedFrom(st, 'sdConfig') } });
        let body = renderStyleBody();
        let text = JSON.stringify(body);
        expect(text).toContain('"data-testid":"pb2-style-hop","data-own":"1"');
        expect(text).toContain('"data-testid":"pb2-style-hop","data-own":"0"');
        expect(text).toContain('"data-testid":"pb2-style-inherited-palette"');
        expect(text).toContain('Inherited from house-style');
        expect(text).not.toContain('pb2-style-inherited-description');
        expect(text).toContain('"data-testid":"pb2-style-inherited-taskModels-location"');
        expect(text).not.toContain('pb2-style-inherited-taskModels-portrait');
        expect(findByTestId(body, 'pb2-style-description').attrs.value).toBe('ink wash');
        expect(findByTestId(body, 'pb2-style-palette').attrs.value).toBe('sepia');
        expect(text).toContain('"data-testid":"pb2-style-sd-inherited"');
        expect(text).toContain('"data-testid":"pb2-style-inherited-sdConfig"');
        expect(text).toContain('"data-testid":"pb2-style-sd-override"');
        expect(text).not.toContain('pb2-style-cycle');
        expect(text).not.toContain('pb2-style-incomplete');
    });
    it('locked: banner, every input disabled, SD override/unlink/create disabled', () => {
        let locked = normalizeStyle(Object.assign({}, WITH_OWN, { own: Object.assign({}, WITH_OWN.own, { locked: true }) }));
        __setStyleStateForTest({ style: locked, sd: { mode: 'inherited', oid: 'sd-parent' } });
        let body = renderStyleBody();
        expect(JSON.stringify(body)).toContain('"data-testid":"pb2-style-locked-banner"');
        ['pb2-style-description', 'pb2-style-exclusions', 'pb2-style-palette', 'pb2-style-photoreal', 'pb2-style-qualityPreset', 'pb2-style-pageFormat', 'pb2-style-taskModel-portrait', 'pb2-style-clear-description', 'pb2-style-sd-override']
            .forEach(id => expect(findByTestId(body, id).attrs.disabled, id).toBe(true));
        // own render settings under the lock: the panel's fieldset is disabled, so are Save render settings and Unlink
        let inst = { entity: { objectId: 'sd1', id: 180, steps: 25 }, changes: ['seed'], resetChanges() { this.changes = []; } };
        __setStyleStateForTest({ sd: { mode: 'own', oid: 'sd1', name: 'my-sd', inst: inst } });
        body = renderStyleBody();
        expect(findByTestId(body, 'pb2-style-sd-fields').attrs.disabled).toBe(true);
        expect(findByTestId(body, 'pb2-style-sd-save').attrs.disabled, 'pending changes do not re-enable Save under a lock').toBe(true);
        expect(findByTestId(body, 'pb2-style-sd-unlink').attrs.disabled).toBe(true);
        // a draft made before the lock keeps its dirty mark but cannot be saved
        __setStyleStateForTest({ draft: { palette: 'pre-lock draft' } });
        expect(findByTestId(renderStyleBody(), 'pb2-style-dirty-palette')).not.toBeNull();
        expect(findByTestId(renderStyleHeader(), 'pb2-style-save').attrs.disabled).toBe(true);
        // unlocked: the fieldset is enabled and Save follows the pending changes
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN), draft: {} });
        body = renderStyleBody();
        expect(findByTestId(body, 'pb2-style-sd-fields').attrs.disabled).toBe(false);
        expect(findByTestId(body, 'pb2-style-sd-save').attrs.disabled).toBe(false);
    });
    it('cycle / truncated / incomplete flags render their notices', () => {
        __setStyleStateForTest({ style: normalizeStyle(Object.assign({}, WITH_OWN, { cycleDetected: true, truncatedAt: 3, truncatedModel: 'olio.pb.style', complete: false })) });
        let text = JSON.stringify(renderStyleBody());
        expect(text).toContain('"data-testid":"pb2-style-cycle"');
        expect(text).toContain('"data-testid":"pb2-style-truncated"');
        expect(text).toContain('The chain was truncated at hop 3 (olio.pb.style).');
        expect(text).toContain('"data-testid":"pb2-style-incomplete"');
    });
    it('task rows: unavailable inventory → data-unavailable=1 + deployment default; a model not in a live list → "Not in the installed-model list"', () => {
        let st = normalizeStyle(WITH_OWN);
        let info = {};
        STYLE_TASKS.forEach(t => { info[t] = { loading: false, data: Object.assign({}, INV_DOWN, { task: t }), error: null }; });
        __setStyleStateForTest({ style: st, taskInfo: info });
        let body = renderStyleBody();
        let av = findByTestId(body, 'pb2-style-task-availability-portrait');
        expect(av.attrs['data-unavailable']).toBe('1');
        let text = JSON.stringify(av);
        expect(text).toContain('installed-model list unavailable: Could not read the installed-model list from the SD server');
        expect(text).toContain('deployment default: juggernautXL_v9.safetensors');
        expect(text).not.toContain('pb2-style-model-missing');
        // live inventory: portrait's own model x.safetensors is not in the list → missing notice; location's y is not either
        info = {};
        STYLE_TASKS.forEach(t => { info[t] = { loading: false, data: Object.assign({}, INV_OK, { task: t }), error: null }; });
        __setStyleStateForTest({ taskInfo: info, draft: { taskModels: { location: 'juggernautXL_v9.safetensors' } } });
        body = renderStyleBody();
        expect(findByTestId(body, 'pb2-style-task-availability-portrait').attrs['data-unavailable']).toBe('0');
        expect(findByTestId(body, 'pb2-style-model-missing-portrait')).not.toBeNull();
        expect(findByTestId(body, 'pb2-style-model-missing-location')).toBeNull();
        expect(findByTestId(body, 'pb2-style-dirty-taskModels-location')).not.toBeNull();
        expect(JSON.stringify(findByTestId(body, 'pb2-style-task-availability-upscale'))).toContain('2 installed · STYLE → x.safetensors');
    });
    it('SD modes: own (panel + Save disabled until changes + Unlink), book (Use book + Create), error', () => {
        let st = normalizeStyle({ own: { objectId: 'o', name: 'o-style', fields: { sdConfig: 'sd1' } }, effective: { fields: { sdConfig: 'sd1' }, sources: { sdConfig: 'o' }, sdConfigName: 'my-sd' } });
        let inst = { entity: { objectId: 'sd1', id: 180, steps: 25 }, changes: [], resetChanges() { this.changes = []; } };
        __setStyleStateForTest({ style: st, sd: { mode: 'own', oid: 'sd1', name: 'my-sd', inst: inst } });
        let body = renderStyleBody();
        let text = JSON.stringify(body);
        expect(text).toContain('"data-testid":"pb2-style-sd-own"');
        expect(text).toContain('my-sd');
        expect(text).toContain('"hideFields":["steps"]');
        expect(findByTestId(body, 'pb2-style-sd-save').attrs.disabled).toBe(true);
        expect(findByTestId(body, 'pb2-style-sd-unlink').attrs.disabled).toBe(false);
        inst.changes = ['steps'];
        expect(findByTestId(renderStyleBody(), 'pb2-style-sd-save').attrs.disabled).toBe(false);

        __setStyleStateForTest({ style: normalizeStyle(FRESH), book: { sdConfig: { objectId: 'sdb', name: 'book-sd' } }, sd: { mode: 'book', oid: 'sdb', name: 'book-sd' } });
        text = JSON.stringify(renderStyleBody());
        expect(text).toContain('"data-testid":"pb2-style-sd-book"');
        expect(text).toContain('book-sd');
        expect(text).toContain('"data-testid":"pb2-style-sd-use-book"');
        expect(text).toContain('"data-testid":"pb2-style-sd-create"');

        __setStyleStateForTest({ sd: { mode: 'own', error: 'The style\'s render settings (sd1) could not be read' } });
        text = JSON.stringify(renderStyleBody());
        expect(text).toContain('"data-testid":"pb2-style-sd-error"');
        expect(text).toContain('could not be read');
    });
});

// ─────────────────────────────── REST flows ───────────────────────────────

describe('loadStyle', () => {
    it('GET /{book}/style, then the book /full, then ONE illustration/models probe when the inventory is unavailable (copied to all six tasks)', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            let url = String(u);
            calls.push(((o && o.method) || 'GET') + ' ' + url.replace(/^.*\/rest\//, '/rest/'));
            if (/\/illustration\/models\?task=portrait$/.test(url)) return jsonResponse(200, INV_DOWN);
            if (/\/illustration\/models/.test(url)) throw new Error('must not probe the other tasks when the inventory is unavailable');
            return jsonResponse(200, FRESH);
        });
        vi.spyOn(am7client, 'getFull').mockResolvedValue({ objectId: 'B1', name: 'p29', groupId: 32789, groupPath: '/Olio/Universes/Books/Worlds/p29/Book' });
        await loadStyle();
        await flushPromises(); await flushPromises(); await flushPromises();
        let st = __styleStateForTest();
        expect(st.error).toBeNull();
        expect(st.style.own).toBeNull();
        expect(st.style.effective.qualityPreset).toBe('STANDARD');
        expect(st.draft).toEqual({});
        expect(st.book.groupId).toBe(32789);
        expect(st.sd.mode).toBe('none');
        expect(calls.filter(c => c.indexOf('/illustration/models') >= 0)).toEqual(['GET /rest/olio/illustration/models?task=portrait']);
        expect(calls[0]).toBe('GET /rest/olio/picture-book/B1/style');
        STYLE_TASKS.forEach(t => {
            expect(st.taskInfo[t].loading, t).toBe(false);
            expect(st.taskInfo[t].data.inventory.error, t).toBe('Could not read the installed-model list from the SD server');
            expect(st.taskInfo[t].data.task, t).toBe(t);
        });
        expect(st.taskInfo.portrait.data.resolution.reason).toBe('INVENTORY_UNAVAILABLE');
        expect(st.taskInfo.location.data.resolution).toBeNull();
    });
    it('when the inventory reads, every task is probed (six calls)', async () => {
        let probes = [];
        global.fetch = vi.fn(async (u) => {
            let url = String(u);
            let mm = url.match(/\/illustration\/models\?task=(\w+)$/);
            if (mm) { probes.push(mm[1]); return jsonResponse(200, Object.assign({}, INV_OK, { task: mm[1] })); }
            return jsonResponse(200, FRESH);
        });
        vi.spyOn(am7client, 'getFull').mockResolvedValue({ objectId: 'B1', groupId: 1, groupPath: '/x' });
        await loadStyle();
        await flushPromises(); await flushPromises(); await flushPromises();
        expect(probes.slice().sort()).toEqual(STYLE_TASKS.slice().sort());
        expect(__styleStateForTest().taskInfo.upscale.data.task).toBe('upscale');
    });
    it('a non-2xx on the style read shows the server body and clears the style', async () => {
        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'Book not found' }));
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN) });
        await loadStyle();
        let st = __styleStateForTest();
        expect(st.style).toBeNull();
        expect(st.error).toBe('(404) Book not found');
        expect(st.loading).toBe(false);
    });
});

describe('saveStyle / toggleLock', () => {
    it('PUTs the sparse body only, replaces the style from the response, clears the draft, toasts', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            calls.push({ url: String(u).replace(/^.*\/rest\//, '/rest/'), method: o && o.method, body: o && o.body ? JSON.parse(o.body) : null });
            return jsonResponse(200, WITH_OWN);
        });
        __setStyleStateForTest({ style: normalizeStyle(FRESH), draft: { description: 'ink wash', palette: '', taskModels: { portrait: 'x.safetensors' }, customWidth: '' } });
        await saveStyle();
        expect(calls).toEqual([{ url: '/rest/olio/picture-book/B1/style', method: 'PUT', body: { description: 'ink wash', palette: null, taskModels: { portrait: 'x.safetensors' }, customWidth: null } }]);
        let st = __styleStateForTest();
        expect(st.style.own.objectId).toBe(OWN_OID);
        expect(st.draft).toEqual({});
        expect(st.saving).toBe(false);
        expect(st.sd.mode).toBe('inherited');
        expect(page.toast).toHaveBeenCalledWith('success', 'Style saved');
    });
    it('empty draft → "Nothing to save" and no request; a 400 is toasted verbatim and the draft is kept', async () => {
        global.fetch = vi.fn(async () => jsonResponse(400, { error: 'pageFormat must be one of PORTRAIT_PAGE, LANDSCAPE_PAGE, SQUARE, CUSTOM' }));
        __setStyleStateForTest({ style: normalizeStyle(FRESH), draft: {} });
        await saveStyle();
        expect(global.fetch).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('warn', 'Nothing to save');
        __setStyleStateForTest({ draft: { pageFormat: 'circle' } });
        await saveStyle();
        expect(page.toast).toHaveBeenCalledWith('error', '(400) pageFormat must be one of PORTRAIT_PAGE, LANDSCAPE_PAGE, SQUARE, CUSTOM');
        expect(__styleStateForTest().draft).toEqual({ pageFormat: 'circle' });
    });
    it('a lock 409 from PUT is toasted verbatim', async () => {
        const MSG = "Style 'p29-probe-049158-style' (BOOK, " + OWN_OID + ') is locked - unlock it before editing (POST /{book}/style/lock {"locked":false})';
        global.fetch = vi.fn(async () => jsonResponse(409, { error: MSG }));
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN), draft: { description: 'x' } });
        await saveStyle();
        expect(page.toast).toHaveBeenCalledWith('error', '(409) ' + MSG);
    });
    it('toggleLock POSTs /style/lock {locked:!own.locked}; no-op without an own style; 404 verbatim', async () => {
        let calls = [];
        global.fetch = vi.fn(async (u, o) => {
            calls.push({ url: String(u).replace(/^.*\/rest\//, '/rest/'), method: o && o.method, body: JSON.parse(o.body) });
            return jsonResponse(200, Object.assign({}, WITH_OWN, { own: Object.assign({}, WITH_OWN.own, { locked: true }), lockedBy: OWN_OID }));
        });
        __setStyleStateForTest({ style: normalizeStyle(FRESH) });
        await toggleLock();
        expect(calls.length).toBe(0);
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN) });
        await toggleLock();
        expect(calls).toEqual([{ url: '/rest/olio/picture-book/B1/style/lock', method: 'POST', body: { locked: true } }]);
        expect(__styleStateForTest().style.own.locked).toBe(true);
        expect(__styleStateForTest().style.lockedBy).toBe(OWN_OID);
        expect(page.toast).toHaveBeenCalledWith('success', 'Style locked');
        global.fetch = vi.fn(async () => jsonResponse(404, { error: 'The book has no style of its own to lock; PUT /{book}/style creates one' }));
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN) });
        await toggleLock();
        expect(page.toast).toHaveBeenCalledWith('error', '(404) The book has no style of its own to lock; PUT /{book}/style creates one');
    });
});

describe('style-owned sd.config flows (generic model routes)', () => {
    it('saveSdConfig PATCHes only inst.changes (never name) and resets the changes on true', async () => {
        let patch = vi.spyOn(page, 'patchObject').mockResolvedValue(true);
        let inst = { entity: { objectId: 'sd1', id: 180, name: 'keep', steps: 30 }, changes: ['steps', 'name'], resetChanges() { this.changes = []; } };
        __setStyleStateForTest({ sd: { mode: 'own', oid: 'sd1', inst: inst } });
        await saveSdConfig();
        expect(patch).toHaveBeenCalledWith({ schema: 'olio.sd.config', objectId: 'sd1', id: 180, steps: 30 });
        expect(inst.changes).toEqual([]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Render settings saved');
    });
    it('saveSdConfig: a non-true result is an error, the changes are kept; nothing changed → warn, no PATCH', async () => {
        let patch = vi.spyOn(page, 'patchObject').mockResolvedValue(null);
        let inst = { entity: { objectId: 'sd1', steps: 30 }, changes: ['steps'], resetChanges() { this.changes = []; } };
        __setStyleStateForTest({ sd: { mode: 'own', oid: 'sd1', inst: inst } });
        await saveSdConfig();
        expect(page.toast).toHaveBeenCalledWith('error', 'The server did not accept the render-settings change');
        expect(inst.changes).toEqual(['steps']);
        inst.changes = [];
        await saveSdConfig();
        expect(patch).toHaveBeenCalledTimes(1);
        expect(page.toast).toHaveBeenCalledWith('warn', 'No render-setting changes to save');
    });
    it('createSdConfig: template → POST /rest/model into the book group → PUT /style {sdConfig:{objectId}}', async () => {
        vi.spyOn(am7sd, 'buildEntity').mockResolvedValue({ schema: 'olio.sd.config', id: 1, objectId: 'tpl', name: 'tpl', steps: 25, model: 'm.safetensors', narration: 'n' });
        let create = vi.spyOn(page, 'createObject').mockResolvedValue({ objectId: 'sd-new', id: 181 });
        let puts = [];
        global.fetch = vi.fn(async (u, o) => {
            puts.push({ url: String(u).replace(/^.*\/rest\//, '/rest/'), method: o && o.method, body: JSON.parse(o.body) });
            return jsonResponse(200, { bookObjectId: 'B1', own: { objectId: 'o', name: 'p29-style', fields: { sdConfig: 'sd-new' } }, effective: { fields: { sdConfig: 'sd-new' }, sources: { sdConfig: 'o' }, sdConfigName: 'p29-style-sd-x' }, chain: [{ objectId: 'o', name: 'p29-style', scope: 'BOOK' }] });
        });
        vi.spyOn(am7client, 'getFull').mockResolvedValue(null);
        __setStyleStateForTest({ style: normalizeStyle(FRESH), book: { objectId: 'B1', slug: 'p29', name: 'P29', groupId: 32789, groupPath: '/g' }, sd: { mode: 'none' } });
        await createSdConfig(null);
        expect(create).toHaveBeenCalledTimes(1);
        let body = create.mock.calls[0][0];
        expect(body.schema).toBe('olio.sd.config');
        expect(body.name).toMatch(/^p29-style-sd-[0-9a-z]+$/);
        expect(body.groupId).toBe(32789);
        expect(body.groupPath).toBe('/g');
        expect(body.steps).toBe(25);
        expect(body).not.toHaveProperty('id');
        expect(body).not.toHaveProperty('objectId');
        expect(body).not.toHaveProperty('narration');
        expect(puts).toEqual([{ url: '/rest/olio/picture-book/B1/style', method: 'PUT', body: { sdConfig: { objectId: 'sd-new' } } }]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Render settings created');
        expect(__styleStateForTest().sd.mode).toBe('own');
    });
    it('createSdConfig: a null create result (the 200-with-empty-body validation failure) is an error, no PUT', async () => {
        vi.spyOn(am7sd, 'buildEntity').mockResolvedValue({ steps: 25 });
        vi.spyOn(page, 'createObject').mockResolvedValue(null);
        global.fetch = vi.fn(async () => jsonResponse(200, FRESH));
        __setStyleStateForTest({ style: normalizeStyle(FRESH), book: { groupId: 1, groupPath: '/g', name: 'b' }, sd: { mode: 'none' } });
        await createSdConfig(null);
        expect(global.fetch).not.toHaveBeenCalled();
        expect(page.toast).toHaveBeenCalledWith('error', 'The server returned no record for the new render settings (validation failed on the server; see the server log)');
        expect(__styleStateForTest().sd.busy).toBe(false);
    });
    it('createSdOverride copies the inherited record (getFull) and names it -style-override-', async () => {
        vi.spyOn(am7client, 'getFull').mockResolvedValue({ objectId: 'sd-parent', id: 5, name: 'parent-sd', steps: 40, cfg: 5 });
        let create = vi.spyOn(page, 'createObject').mockResolvedValue({ objectId: 'sd-copy' });
        let puts = [];
        global.fetch = vi.fn(async (u, o) => { puts.push(JSON.parse(o.body)); return jsonResponse(200, WITH_OWN); });
        __setStyleStateForTest({ style: normalizeStyle(WITH_OWN), book: { slug: 'p29', groupId: 1, groupPath: '/g' }, sd: { mode: 'inherited', oid: 'sd-parent' } });
        await createSdOverride();
        let body = create.mock.calls[0][0];
        expect(body.name).toMatch(/^p29-style-override-/);
        expect(body.steps).toBe(40);
        expect(body.cfg).toBe(5);
        expect(body).not.toHaveProperty('objectId');
        expect(puts).toEqual([{ sdConfig: { objectId: 'sd-copy' } }]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Book override created');
    });
    it('useBookSdConfig PUTs the book FK; unlinkSdConfig PUTs {sdConfig:null}', async () => {
        let puts = [];
        global.fetch = vi.fn(async (u, o) => { puts.push({ method: o.method, body: JSON.parse(o.body) }); return jsonResponse(200, FRESH); });
        __setStyleStateForTest({ style: normalizeStyle(FRESH), book: { sdConfig: { objectId: 'sdb' } }, sd: { mode: 'book', oid: 'sdb' } });
        await useBookSdConfig();
        __setStyleStateForTest({ sd: { mode: 'own', oid: 'sdb' } });
        await unlinkSdConfig();
        expect(puts).toEqual([{ method: 'PUT', body: { sdConfig: { objectId: 'sdb' } } }, { method: 'PUT', body: { sdConfig: null } }]);
        expect(page.toast).toHaveBeenCalledWith('success', 'Style now uses the book render settings');
        expect(page.toast).toHaveBeenCalledWith('success', 'Render settings unlinked');
    });
});
