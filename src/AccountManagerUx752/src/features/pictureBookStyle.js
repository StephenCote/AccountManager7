/**
 * pictureBookStyle — PB2 V3 book style editor (design §26, P2-9).
 *
 * Route:
 *   /picture-book/:bookObjectId/style   (bookObjectId = olio.pb.book objectId)
 *
 * One read: GET /{book}/style (PbServiceFacade.getStyle) — `own` is the book's own style (absent on
 * a fresh book), `effective` the resolved chain with per-field `sources`. Save is a SPARSE
 * PUT /{book}/style carrying only the keys the user changed (JSON null clears); lock/unlock is
 * POST /{book}/style/lock. Every server error is toasted verbatim (lock 409, enum 400, unknown key 400).
 *
 * The style's own SD config is a plain olio.sd.config read through /rest/model/{type}/{oid}/full,
 * edited with the shared SdConfigPanel (steps hidden: the quality preset owns the step count) and
 * saved as a sparse PATCH /rest/model of the changed fields only, never `name`. No server route
 * creates a style-owned sd.config, so "Create render settings" is POST /rest/model from the
 * randomImageConfig template into the book's group, followed by PUT /style {sdConfig:{objectId}}.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { layout, pageLayout } from '../router.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';
import { am7sd } from '../components/sdConfig.js';
import {
    getStyle, putStyle, lockStyle, listModelsForTask, normalizeStyle,
    STYLE_PAGE_FORMATS, STYLE_QUALITY_PRESETS, STYLE_TASKS
} from '../workflows/pictureBookWorkflow.js';

let bookObjectId = null;
let style = null;          // normalizeStyle(dto)
let loading = false;
let error = null;
let draft = {};            // sparse PUT body under construction: key → value (null = clear); taskModels is a nested map
let saving = false;
let lockBusy = false;
let loadSeq = 0;
let taskInfo = {};         // task → {loading, data, error}
let book = null;           // olio.pb.book /full (name, groupId, groupPath, sdConfig FK)
let sd = { mode: 'none', oid: null, name: null, fromName: null, inst: null, loading: false, error: null, busy: false };
let sdModels = [];
let sdLoras = [];

export const TEXT_FIELDS = ['description', 'exclusions', 'palette'];
export const SD_CREATE_EXCLUDE = ['narration', 'id', 'objectId', 'ownerId', 'name', 'groupId', 'organizationId', 'groupPath', 'organizationPath', 'urn'];
export const SD_PATCH_EXCLUDE = SD_CREATE_EXCLUDE.concat(['schema']);

// ── Pure derivations (exported for Vitest) ─────────────────────────────

/** Name of the chain hop a style objectId belongs to; the oid itself when the hop is not in the chain. */
export function hopName(st, styleOid) {
    if (!styleOid) return null;
    let hop = (st && st.chain ? st.chain : []).find(function (h) { return h && h.objectId === styleOid; });
    return hop ? (hop.name || styleOid) : styleOid;
}

/** The value the editor shows for a field: the book's own value when it has one, else the effective (inherited) value. */
export function styleFieldValue(st, key) {
    if (!st) return undefined;
    if (st.own && st.own.fields && Object.prototype.hasOwnProperty.call(st.own.fields, key)) return st.own.fields[key];
    if (st.effective && st.effective.fields && Object.prototype.hasOwnProperty.call(st.effective.fields, key)) return st.effective.fields[key];
    if (key === 'qualityPreset' || key === 'pageFormat') return st.effective ? st.effective[key] : undefined;
    return undefined;
}

/** The chain hop a field is inherited from (null when the book sets it itself or nothing sets it). Strictly from effective.sources. */
export function inheritedFrom(st, key) {
    if (!st || !st.effective || !st.effective.sources) return null;
    let src = st.effective.sources[key];
    if (!src) return null;
    if (st.own && st.own.objectId === src) return null;
    return hopName(st, src);
}

/** Same for a task model, from effective.taskModelSources. */
export function taskModelInheritedFrom(st, task) {
    if (!st || !st.effective || !st.effective.taskModelSources) return null;
    let src = st.effective.taskModelSources[task];
    if (!src) return null;
    if (st.own && st.own.objectId === src) return null;
    return hopName(st, src);
}

/** The task model the editor shows: own first, else effective. */
export function taskModelValue(st, task) {
    if (!st) return null;
    if (st.own && st.own.fields && st.own.fields.taskModels && Object.prototype.hasOwnProperty.call(st.own.fields.taskModels, task)) return st.own.fields.taskModels[task];
    if (st.effective && st.effective.taskModels && Object.prototype.hasOwnProperty.call(st.effective.taskModels, task)) return st.effective.taskModels[task];
    return null;
}

/** What the input shows: the draft value when the key was touched, else the style value. */
export function draftValue(d, st, key) {
    if (d && Object.prototype.hasOwnProperty.call(d, key)) return d[key];
    return styleFieldValue(st, key);
}

export function draftTaskModel(d, st, task) {
    if (d && d.taskModels && Object.prototype.hasOwnProperty.call(d.taskModels, task)) return d.taskModels[task];
    return taskModelValue(st, task);
}

/**
 * The sparse PUT body: only touched keys. Numbers for the custom dimensions; task models as one map;
 * empty strings become null (clear) for the text fields. Returns null when nothing was touched.
 */
export function buildStylePut(d) {
    if (!d || typeof d !== 'object') return null;
    let body = {};
    Object.keys(d).forEach(function (k) {
        let v = d[k];
        if (k === 'taskModels') {
            if (v && typeof v === 'object' && Object.keys(v).length) {
                let tm = {};
                Object.keys(v).forEach(function (t) { tm[t] = v[t] === '' || v[t] === undefined ? null : v[t]; });
                body.taskModels = tm;
            }
            return;
        }
        if (k === 'customWidth' || k === 'customHeight') {
            let n = v === '' || v === null || v === undefined ? null : Number(v);
            body[k] = n === null || isNaN(n) ? null : n;
            return;
        }
        if (TEXT_FIELDS.indexOf(k) >= 0) { body[k] = v === '' || v === undefined ? null : v; return; }
        if (k === 'sdConfig') { body.sdConfig = v && v.objectId ? { objectId: v.objectId } : null; return; }
        body[k] = v;
    });
    return Object.keys(body).length ? body : null;
}

/**
 * Which SD section to show: 'own' (the book's style sets sdConfig), 'inherited' (a parent hop sets
 * it), 'book' (nothing in the chain, but the book record carries its own render settings FK), 'none'.
 */
export function sdConfigMode(st, bookRec) {
    let oid = st && st.effective && st.effective.fields ? st.effective.fields.sdConfig : null;
    if (oid) {
        let src = st.effective.sources ? st.effective.sources.sdConfig : null;
        if (st.own && src && src === st.own.objectId) return 'own';
        if (st.own && !src && st.own.fields && st.own.fields.sdConfig === oid) return 'own';
        return 'inherited';
    }
    let fk = bookRec && bookRec.sdConfig;
    if (fk && (fk.objectId || fk.id)) return 'book';
    return 'none';
}

/** Sparse PATCH of the fields the panel changed (inst.changes); never identity, never `name`. Null when nothing changed. */
export function sdPatchBody(inst) {
    if (!inst || !inst.entity || !inst.entity.objectId) return null;
    let skip = new Set(SD_PATCH_EXCLUDE);
    let body = {};
    Array.from(new Set(inst.changes || [])).forEach(function (k) {
        if (skip.has(k)) return;
        let v = inst.entity[k];
        if (v === undefined) return;
        body[k] = v;
    });
    if (!Object.keys(body).length) return null;
    body.schema = 'olio.sd.config';
    body.objectId = inst.entity.objectId;
    if (inst.entity.id != null) body.id = inst.entity.id;
    return body;
}

/** POST /rest/model body for a style-owned sd.config from a template/source record into the book's group. */
export function sdCreateBody(source, name, bookRec) {
    if (!source || typeof source !== 'object') return null;
    let skip = new Set(SD_CREATE_EXCLUDE);
    let body = {};
    Object.keys(source).forEach(function (k) {
        if (skip.has(k)) return;
        if (source[k] === undefined) return;
        body[k] = source[k];
    });
    body.schema = 'olio.sd.config';
    body.name = name;
    if (bookRec && bookRec.groupId != null) body.groupId = bookRec.groupId;
    if (bookRec && bookRec.groupPath) body.groupPath = bookRec.groupPath;
    return body;
}

/** Availability text for a task row from GET /olio/illustration/models?task=… (shown as received). */
export function taskAvailability(info) {
    if (!info) return { text: '', unavailable: false, deploymentDefault: null };
    if (info.error) return { text: info.error, unavailable: true, deploymentDefault: null };
    let d = info.data || {};
    let inv = d.inventory || {};
    let dd = d.deploymentDefault || null;
    if (inv.error) {
        return { text: 'installed-model list unavailable: ' + inv.error, unavailable: true, deploymentDefault: dd };
    }
    let count = inv.count != null ? inv.count : (Array.isArray(d.models) ? d.models.length : null);
    let res = d.resolution || {};
    let text = (count != null ? count + ' installed' : 'inventory read') + (res.reason ? ' · ' + res.reason : '') + (res.model ? ' → ' + res.model : '');
    return { text: text, unavailable: false, deploymentDefault: dd, models: Array.isArray(d.models) ? d.models : [] };
}

// ── Data ───────────────────────────────────────────────────────────────

export async function loadStyle() {
    if (!bookObjectId) return;
    let seq = ++loadSeq;
    loading = true;
    error = null;
    m.redraw();
    try {
        let dto = await getStyle(bookObjectId);
        if (seq !== loadSeq) return;
        style = normalizeStyle(dto);
        draft = {};
    } catch (e) {
        if (seq !== loadSeq) return;
        style = null;
        error = (e && e.message) || 'Failed to load the book style';
    }
    loading = false;
    m.redraw();
    if (style) {
        loadBook(seq);
        loadTaskInfo(seq);
    }
}

async function loadBook(seq) {
    try {
        let rec = await am7client.getFull('olio.pb.book', bookObjectId);
        if (seq !== loadSeq) return;
        book = rec || null;
    } catch (e) {
        if (seq !== loadSeq) return;
        book = null;
    }
    await loadSdSection(seq);
    m.redraw();
}

// One probe first: when the inventory cannot be read the server says so once and the five other
// tasks would only re-probe the SD server, so they get the same answer without a request.
async function loadTaskInfo(seq) {
    taskInfo = {};
    STYLE_TASKS.forEach(function (t) { taskInfo[t] = { loading: true, data: null, error: null }; });
    m.redraw();
    let first = STYLE_TASKS[0];
    let firstData = null;
    try {
        firstData = await listModelsForTask(first);
        if (seq !== loadSeq) return;
        taskInfo[first] = { loading: false, data: firstData, error: null };
    } catch (e) {
        if (seq !== loadSeq) return;
        taskInfo[first] = { loading: false, data: null, error: (e && e.message) || 'Could not list the installed models' };
    }
    let invError = firstData && firstData.inventory && firstData.inventory.error;
    if (!firstData || invError) {
        STYLE_TASKS.slice(1).forEach(function (t) {
            taskInfo[t] = firstData
                ? { loading: false, data: Object.assign({}, firstData, { task: t, resolution: null }), error: null }
                : { loading: false, data: null, error: taskInfo[first].error };
        });
        m.redraw();
        return;
    }
    await Promise.all(STYLE_TASKS.slice(1).map(async function (t) {
        try {
            let d = await listModelsForTask(t);
            if (seq !== loadSeq) return;
            taskInfo[t] = { loading: false, data: d, error: null };
        } catch (e) {
            if (seq !== loadSeq) return;
            taskInfo[t] = { loading: false, data: null, error: (e && e.message) || 'Could not list the installed models' };
        }
    }));
    m.redraw();
}

async function loadSdSection(seq) {
    let mode = sdConfigMode(style, book);
    let oid = style && style.effective && style.effective.fields ? style.effective.fields.sdConfig : null;
    sd = { mode: mode, oid: oid || null, name: style && style.effective ? style.effective.sdConfigName : null,
        fromName: mode === 'inherited' ? inheritedFrom(style, 'sdConfig') : null, inst: null, loading: mode === 'own', error: null, busy: false };
    if (mode === 'book') {
        sd.oid = book.sdConfig.objectId || null;
        sd.name = book.sdConfig.name || null;
    }
    if (mode !== 'own') return;
    m.redraw();
    try {
        let rec = await am7client.getFull('olio.sd.config', oid);
        if (seq !== loadSeq) return;
        if (!rec || !rec.objectId) throw new Error('The style\'s render settings (' + oid + ') could not be read');
        if (!rec[am7model.jsonModelKey]) rec[am7model.jsonModelKey] = 'olio.sd.config';
        let inst = am7model.prepareInstance(rec, am7model.forms.sdConfig);
        inst.resetChanges();
        sd.inst = inst;
        sd.name = rec.name || sd.name;
        ensureSdLists();
    } catch (e) {
        if (seq !== loadSeq) return;
        sd.error = (e && e.message) || 'Could not load the render settings';
    }
    sd.loading = false;
    m.redraw();
}

function ensureSdLists() {
    if (!sdModels.length) am7sd.fetchModels().then(function (list) { sdModels = list || []; m.redraw(); }).catch(function () {});
    if (!sdLoras.length) am7sd.fetchLoras().then(function (list) { sdLoras = list || []; m.redraw(); }).catch(function () {});
}

function touch(key, value) {
    draft[key] = value;
}

function touchTaskModel(task, value) {
    if (!draft.taskModels) draft.taskModels = {};
    draft.taskModels[task] = value;
}

export async function saveStyle() {
    if (saving || !bookObjectId) return;
    let body = buildStylePut(draft);
    if (!body) { page.toast('warn', 'Nothing to save'); return; }
    saving = true;
    m.redraw();
    try {
        let dto = await putStyle(bookObjectId, body);
        style = normalizeStyle(dto);
        draft = {};
        page.toast('success', 'Style saved');
        await loadSdSection(loadSeq);
    } catch (e) {
        page.toast('error', (e && e.message) || 'Save failed');
    }
    saving = false;
    m.redraw();
}

export async function toggleLock() {
    if (lockBusy || !bookObjectId || !style || !style.own) return;
    lockBusy = true;
    m.redraw();
    let next = !style.own.locked;
    try {
        let dto = await lockStyle(bookObjectId, next);
        style = normalizeStyle(dto);
        page.toast('success', next ? 'Style locked' : 'Style unlocked');
    } catch (e) {
        page.toast('error', (e && e.message) || 'Lock change failed');
    }
    lockBusy = false;
    m.redraw();
}

/** PATCH the style-owned sd.config with the panel's changes only. */
export async function saveSdConfig() {
    if (sd.busy || !sd.inst) return;
    let body = sdPatchBody(sd.inst);
    if (!body) { page.toast('warn', 'No render-setting changes to save'); return; }
    sd.busy = true;
    m.redraw();
    try {
        let ok = await page.patchObject(body);
        if (ok !== true) throw new Error('The server did not accept the render-settings change' + (ok && ok.error ? ': ' + ok.error : ''));
        sd.inst.resetChanges();
        page.toast('success', 'Render settings saved');
    } catch (e) {
        page.toast('error', (e && e.message) || 'Render settings save failed');
    }
    sd.busy = false;
    m.redraw();
}

function sdName(suffix) {
    let base = (book && (book.slug || book.name)) || (style && style.own && style.own.name) || bookObjectId;
    return base + '-' + suffix + '-' + Date.now().toString(36);
}

/** Create a style-owned sd.config (from the template, or copied from `source`) in the book's group, then point the style at it. */
export async function createSdConfig(source) {
    if (sd.busy || !bookObjectId) return;
    sd.busy = true;
    m.redraw();
    try {
        if (!book || book.groupId == null) throw new Error('The book record could not be read, so its group is unknown');
        let src = source;
        if (!src) {
            src = await am7sd.buildEntity();
            if (!src) throw new Error('No SD config template available');
        }
        let body = sdCreateBody(src, sdName(source ? 'style-override' : 'style-sd'), book);
        let created = await page.createObject(body);
        if (!created || !created.objectId) throw new Error('The server returned no record for the new render settings (validation failed on the server; see the server log)');
        let dto = await putStyle(bookObjectId, { sdConfig: { objectId: created.objectId } });
        style = normalizeStyle(dto);
        draft = {};
        page.toast('success', source ? 'Book override created' : 'Render settings created');
        await loadSdSection(loadSeq);
    } catch (e) {
        page.toast('error', (e && e.message) || 'Could not create the render settings');
    }
    sd.busy = false;
    m.redraw();
}

/** Copy the inherited sd.config into a book-owned one. */
export async function createSdOverride() {
    if (sd.busy || !sd.oid) return;
    sd.busy = true;
    m.redraw();
    let rec = null;
    try {
        rec = await am7client.getFull('olio.sd.config', sd.oid);
        if (!rec || !rec.objectId) throw new Error('The inherited render settings (' + sd.oid + ') could not be read');
    } catch (e) {
        page.toast('error', (e && e.message) || 'Could not read the inherited render settings');
        sd.busy = false;
        m.redraw();
        return;
    }
    sd.busy = false;
    await createSdConfig(rec);
}

/** Point the style at the book record's existing render settings. */
export async function useBookSdConfig() {
    if (sd.busy || !book || !book.sdConfig || !book.sdConfig.objectId) return;
    sd.busy = true;
    m.redraw();
    try {
        let dto = await putStyle(bookObjectId, { sdConfig: { objectId: book.sdConfig.objectId } });
        style = normalizeStyle(dto);
        page.toast('success', 'Style now uses the book render settings');
        await loadSdSection(loadSeq);
    } catch (e) {
        page.toast('error', (e && e.message) || 'Could not link the book render settings');
    }
    sd.busy = false;
    m.redraw();
}

/** Clear the style's own sdConfig (PUT {sdConfig:null}); the record itself is left in place. */
export async function unlinkSdConfig() {
    if (sd.busy || !bookObjectId) return;
    sd.busy = true;
    m.redraw();
    try {
        let dto = await putStyle(bookObjectId, { sdConfig: null });
        style = normalizeStyle(dto);
        page.toast('success', 'Render settings unlinked');
        await loadSdSection(loadSeq);
    } catch (e) {
        page.toast('error', (e && e.message) || 'Could not unlink the render settings');
    }
    sd.busy = false;
    m.redraw();
}

// ── Rendering ──────────────────────────────────────────────────────────

const INPUT = 'w-full text-sm px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 disabled:opacity-60';
const BTN = 'text-xs px-2 py-1 rounded border dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed';

function isLocked() { return !!(style && style.own && style.own.locked); }

function inheritedTag(name, testid) {
    if (!name) return null;
    return m('span', { 'data-testid': testid, class: 'ml-2 text-[11px] text-blue-700 dark:text-blue-300' }, 'Inherited from ' + name);
}

function dirtyMark(key) {
    return draft && Object.prototype.hasOwnProperty.call(draft, key)
        ? m('span', { 'data-testid': 'pb2-style-dirty-' + key, class: 'ml-1 text-amber-600', title: 'Unsaved change' }, '•') : null;
}

function textField(key, label, rows) {
    let v = draftValue(draft, style, key);
    let locked = isLocked();
    return m('div', { class: 'mb-3' }, [
        m('div', { class: 'flex items-center text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, [label, dirtyMark(key), inheritedTag(inheritedFrom(style, key), 'pb2-style-inherited-' + key),
            m('span', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb2-style-clear-' + key, class: 'text-[11px] text-gray-400 hover:text-red-500 disabled:opacity-30', disabled: locked, title: 'Clear (the parent value, if any, applies again)', onclick: function () { touch(key, null); } }, 'clear')]),
        rows > 1
            ? m('textarea', { 'data-testid': 'pb2-style-' + key, class: INPUT, rows: rows, disabled: locked, value: v == null ? '' : v, oninput: function (e) { touch(key, e.target.value); } })
            : m('input', { 'data-testid': 'pb2-style-' + key, class: INPUT, type: 'text', disabled: locked, value: v == null ? '' : v, oninput: function (e) { touch(key, e.target.value); } })
    ]);
}

function selectField(key, label, options) {
    let v = draftValue(draft, style, key);
    let cur = v ? String(v).toUpperCase() : '';
    return m('div', { class: 'mb-3' }, [
        m('div', { class: 'flex items-center text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, [label, dirtyMark(key), inheritedTag(inheritedFrom(style, key), 'pb2-style-inherited-' + key)]),
        m('select', { 'data-testid': 'pb2-style-' + key, class: INPUT, disabled: isLocked(), value: cur, onchange: function (e) { touch(key, e.target.value); } },
            options.map(function (o) { return m('option', { value: o, selected: o === cur }, o); }))
    ]);
}

function numberField(key, label) {
    let v = draftValue(draft, style, key);
    return m('div', { class: 'mb-3' }, [
        m('div', { class: 'flex items-center text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, [label, dirtyMark(key), inheritedTag(inheritedFrom(style, key), 'pb2-style-inherited-' + key)]),
        m('input', { 'data-testid': 'pb2-style-' + key, class: INPUT, type: 'number', min: 0, step: 8, disabled: isLocked(), value: v == null || v === 0 ? '' : v, oninput: function (e) { touch(key, e.target.value); } })
    ]);
}

function renderTaskModels() {
    return m('div', { 'data-testid': 'pb2-style-task-models', class: 'mb-3' }, [
        m('div', { class: 'text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, 'Models by task'),
        m('table', { class: 'w-full text-xs' }, [
            m('thead', m('tr', { class: 'text-left text-gray-500' }, [m('th', { class: 'py-1' }, 'Task'), m('th', 'Model'), m('th', 'Availability')])),
            m('tbody', STYLE_TASKS.map(function (task) {
                let v = draftTaskModel(draft, style, task);
                let info = taskInfo[task];
                let av = taskAvailability(info);
                let dirty = draft.taskModels && Object.prototype.hasOwnProperty.call(draft.taskModels, task);
                let listed = av.models && av.models.length ? av.models.map(function (x) { return typeof x === 'string' ? x : (x && (x.name || x.title)) || ''; }).filter(Boolean) : [];
                return m('tr', { 'data-task': task, class: 'border-t border-gray-100 dark:border-gray-700 align-top' }, [
                    m('td', { class: 'py-1 pr-2 font-medium whitespace-nowrap' }, [task, dirty ? m('span', { 'data-testid': 'pb2-style-dirty-taskModels-' + task, class: 'ml-1 text-amber-600' }, '•') : null,
                        inheritedTag(taskModelInheritedFrom(style, task), 'pb2-style-inherited-taskModels-' + task)]),
                    m('td', { class: 'py-1 pr-2' }, [
                        m('input', { 'data-testid': 'pb2-style-taskModel-' + task, class: INPUT, type: 'text', list: 'pb2-style-models-' + task, placeholder: 'default', disabled: isLocked(),
                            value: v == null ? '' : v, oninput: function (e) { touchTaskModel(task, e.target.value); } }),
                        listed.length ? m('datalist', { id: 'pb2-style-models-' + task }, listed.map(function (n) { return m('option', { value: n }); })) : null
                    ]),
                    m('td', { 'data-testid': 'pb2-style-task-availability-' + task, 'data-unavailable': av.unavailable ? '1' : '0', class: 'py-1 text-gray-600 dark:text-gray-300' }, [
                        info && info.loading ? 'checking…' : (av.text || ''),
                        av.deploymentDefault && av.deploymentDefault.model ? m('div', { class: 'text-[11px] text-gray-400' }, 'deployment default: ' + av.deploymentDefault.model) : null,
                        v && listed.length && listed.indexOf(v) < 0 ? m('div', { 'data-testid': 'pb2-style-model-missing-' + task, class: 'text-[11px] text-amber-700 dark:text-amber-400' }, 'Not in the installed-model list') : null
                    ])
                ]);
            }))
        ])
    ]);
}

function renderChain() {
    if (!style) return null;
    let own = style.own;
    return m('div', { 'data-testid': 'pb2-style-chain', class: 'mb-4 text-xs' }, [
        m('div', { class: 'font-medium text-gray-600 dark:text-gray-300 mb-1' }, 'Resolution chain'),
        style.chain.length
            ? m('ol', { class: 'space-y-0.5' }, style.chain.map(function (h, i) {
                let isOwn = own && own.objectId === h.objectId;
                return m('li', { 'data-testid': 'pb2-style-hop', 'data-own': isOwn ? '1' : '0', class: 'flex items-center gap-2' }, [
                    m('span', { class: 'text-gray-400' }, (i + 1) + '.'),
                    m('span', { class: isOwn ? 'font-semibold' : '' }, h.name || h.objectId),
                    m('span', { class: 'px-1.5 rounded bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300' }, h.scope || ''),
                    h.locked ? m('span', { class: 'material-symbols-outlined text-sm text-amber-600', title: 'locked' }, 'lock') : null,
                    isOwn ? m('span', { class: 'text-gray-400' }, '(this book)') : null
                ]);
            }))
            : m('div', { class: 'text-gray-400 italic' }, 'No style of its own yet — defaults apply. Save any field to create one.'),
        style.cycleDetected ? m('div', { 'data-testid': 'pb2-style-cycle', class: 'mt-1 text-amber-700 dark:text-amber-400' }, 'The parent chain contains a cycle; resolution stopped where it repeated.') : null,
        style.truncatedAt != null || style.truncatedModel ? m('div', { 'data-testid': 'pb2-style-truncated', class: 'mt-1 text-amber-700 dark:text-amber-400' },
            'The chain was truncated' + (style.truncatedAt != null ? ' at hop ' + style.truncatedAt : '') + (style.truncatedModel ? ' (' + style.truncatedModel + ')' : '') + '.') : null,
        !style.complete ? m('div', { 'data-testid': 'pb2-style-incomplete', class: 'mt-1 text-amber-700 dark:text-amber-400' }, 'Some hops could not be read; the effective values may be incomplete.') : null
    ]);
}

function renderSdSection() {
    let locked = isLocked();
    let head = m('div', { class: 'flex items-center gap-2 text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, ['Render settings (SD config)', dirtyMark('sdConfig')]);
    let body;
    if (sd.loading) {
        body = m('div', { class: 'text-xs text-gray-500' }, 'Loading render settings…');
    } else if (sd.error) {
        body = m('div', { 'data-testid': 'pb2-style-sd-error', class: 'text-xs text-red-500' }, sd.error);
    } else if (sd.mode === 'own') {
        body = [
            m('div', { 'data-testid': 'pb2-style-sd-own', class: 'text-xs text-gray-600 dark:text-gray-300 mb-2' }, ['This book\'s own: ', m('span', { class: 'font-medium' }, sd.name || sd.oid),
                m('span', { class: 'text-gray-400 ml-2' }, 'Steps come from the quality preset above.')]),
            sd.inst ? m('fieldset', { 'data-testid': 'pb2-style-sd-fields', disabled: locked, class: locked ? 'opacity-60' : '' },
                m(SdConfigPanel, { inst: sd.inst, models: sdModels, loras: sdLoras, hideFields: ['steps'], onChange: function () { m.redraw(); } })) : null,
            m('div', { class: 'flex gap-2 mt-2' }, [
                m('button', { 'data-testid': 'pb2-style-sd-save', class: BTN, disabled: sd.busy || locked || !sd.inst || !(sd.inst.changes && sd.inst.changes.length), onclick: saveSdConfig }, 'Save render settings'),
                m('button', { 'data-testid': 'pb2-style-sd-unlink', class: BTN, disabled: sd.busy || locked, title: 'Clear sdConfig on this style (the parent value, if any, applies again)', onclick: unlinkSdConfig }, 'Unlink')
            ])
        ];
    } else if (sd.mode === 'inherited') {
        body = [
            m('div', { 'data-testid': 'pb2-style-sd-inherited', class: 'text-xs text-gray-600 dark:text-gray-300 mb-2' }, [
                m('span', { class: 'font-medium' }, sd.name || sd.oid), inheritedTag(sd.fromName, 'pb2-style-inherited-sdConfig'), ' — read-only here.'
            ]),
            m('button', { 'data-testid': 'pb2-style-sd-override', class: BTN, disabled: sd.busy || locked, onclick: createSdOverride }, 'Create book override')
        ];
    } else if (sd.mode === 'book') {
        body = [
            m('div', { 'data-testid': 'pb2-style-sd-book', class: 'text-xs text-gray-600 dark:text-gray-300 mb-2' }, ['No style sets render settings; the book record has its own: ', m('span', { class: 'font-medium' }, sd.name || sd.oid)]),
            m('div', { class: 'flex gap-2' }, [
                m('button', { 'data-testid': 'pb2-style-sd-use-book', class: BTN, disabled: sd.busy || locked, onclick: useBookSdConfig }, 'Use book render settings'),
                m('button', { 'data-testid': 'pb2-style-sd-create', class: BTN, disabled: sd.busy || locked, onclick: function () { createSdConfig(null); } }, 'Create render settings')
            ])
        ];
    } else {
        body = [
            m('div', { 'data-testid': 'pb2-style-sd-none', class: 'text-xs text-gray-500 mb-2' }, 'No render settings in the style chain or on the book; the deployment defaults apply.'),
            m('button', { 'data-testid': 'pb2-style-sd-create', class: BTN, disabled: sd.busy || locked, onclick: function () { createSdConfig(null); } }, 'Create render settings')
        ];
    }
    return m('div', { 'data-testid': 'pb2-style-sd', class: 'mb-4 p-3 rounded border border-gray-200 dark:border-gray-700' }, [head, body]);
}

export function renderStyleHeader() {
    let own = style && style.own;
    let locked = isLocked();
    let dirty = !!(draft && Object.keys(draft).length);
    return m('div', { class: 'flex items-center gap-3 mb-4 flex-wrap' }, [
        m('button', {
            'data-testid': 'pb2-style-back',
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Back to book',
            onclick: function () { m.route.set('/picture-book/v2/' + bookObjectId); }
        }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
        m('span', { class: 'font-semibold text-sm' }, 'Book style'),
        own ? m('span', { 'data-testid': 'pb2-style-own-name', class: 'text-xs text-gray-500' }, own.name) : null,
        locked ? m('span', { 'data-testid': 'pb2-style-locked', class: 'text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200' },
            'Locked' + (style.lockedBy ? ' by ' + hopName(style, style.lockedBy) : '')) : null,
        m('span', { class: 'flex-1' }),
        m('button', {
            'data-testid': 'pb2-style-lock',
            class: BTN,
            disabled: lockBusy || !own,
            title: own ? (locked ? 'Unlock the style' : 'Lock the style against edits') : 'The book has no style of its own to lock; save a field first',
            onclick: toggleLock
        }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, locked ? 'lock_open' : 'lock'), locked ? 'Unlock' : 'Lock']),
        m('button', {
            'data-testid': 'pb2-style-save',
            class: BTN + ' bg-purple-600 text-white border-purple-600 hover:bg-purple-700',
            disabled: saving || !dirty || locked,
            onclick: saveStyle
        }, saving ? 'Saving…' : 'Save'),
        m('button', {
            'data-testid': 'pb2-style-refresh',
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            title: 'Refresh', disabled: loading,
            onclick: function () { loadStyle(); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'refresh'))
    ]);
}

export function renderStyleBody() {
    if (loading && !style) return m('div', { class: 'text-sm text-gray-500 text-center py-12' }, 'Loading book style...');
    if (error) return m('div', { 'data-testid': 'pb2-style-error', class: 'text-red-500 text-sm text-center py-12' }, error);
    if (!style) return null;
    let pf = draftValue(draft, style, 'pageFormat');
    let locked = isLocked();
    return m('div', { class: 'max-w-3xl mx-auto w-full' }, [
        locked ? m('div', { 'data-testid': 'pb2-style-locked-banner', class: 'mb-3 text-xs px-3 py-2 rounded bg-amber-50 dark:bg-amber-900/20 text-amber-800 dark:text-amber-200' },
            'This style is locked — unlock it before editing.') : null,
        renderChain(),
        m('div', { class: 'grid grid-cols-1 md:grid-cols-2 gap-x-6' }, [
            m('div', [
                textField('description', 'Description', 3),
                textField('exclusions', 'Exclusions (negative)', 2),
                textField('palette', 'Palette', 1),
                m('label', { class: 'flex items-center gap-2 text-xs mb-3' }, [
                    m('input', { 'data-testid': 'pb2-style-photoreal', type: 'checkbox', disabled: locked, checked: !!draftValue(draft, style, 'photoreal'), onchange: function (e) { touch('photoreal', e.target.checked); } }),
                    'Photoreal', dirtyMark('photoreal'), inheritedTag(inheritedFrom(style, 'photoreal'), 'pb2-style-inherited-photoreal')
                ])
            ]),
            m('div', [
                selectField('qualityPreset', 'Quality preset', STYLE_QUALITY_PRESETS),
                selectField('pageFormat', 'Page format', STYLE_PAGE_FORMATS),
                String(pf || '').toUpperCase() === 'CUSTOM' ? m('div', { class: 'grid grid-cols-2 gap-3' }, [numberField('customWidth', 'Custom width'), numberField('customHeight', 'Custom height')]) : null
            ])
        ]),
        renderTaskModels(),
        renderSdSection(),
        m('div', { 'data-testid': 'pb2-style-reference-images', class: 'mb-4 text-xs text-gray-400 italic' }, 'Reference images: no style-level reference-image routes exist yet (Phase 4).')
    ]);
}

const styleView = {
    oninit: function (vnode) {
        let id = vnode.attrs.bookObjectId;
        if (id && id !== bookObjectId) {
            bookObjectId = id;
            style = null;
            error = null;
            draft = {};
            book = null;
            taskInfo = {};
            sd = { mode: 'none', oid: null, name: null, fromName: null, inst: null, loading: false, error: null, busy: false };
        }
        if (bookObjectId) loadStyle();
    },
    view: function () {
        return m('div', { 'data-testid': 'pb2-style', class: 'p-4 flex flex-col h-full' }, [
            renderStyleHeader(),
            m('div', { class: 'flex-1 overflow-y-auto' }, renderStyleBody())
        ]);
    }
};

// ── Test seams ─────────────────────────────────────────────────────────

export function __styleStateForTest() {
    return { bookObjectId, style, loading, error, draft, saving, lockBusy, taskInfo, book, sd, sdModels, sdLoras };
}

export function __setStyleStateForTest(s) {
    s = s || {};
    if ('bookObjectId' in s) bookObjectId = s.bookObjectId;
    if ('style' in s) style = s.style;
    if ('loading' in s) loading = s.loading;
    if ('error' in s) error = s.error;
    if ('draft' in s) draft = s.draft || {};
    if ('saving' in s) saving = s.saving;
    if ('lockBusy' in s) lockBusy = s.lockBusy;
    if ('taskInfo' in s) taskInfo = s.taskInfo || {};
    if ('book' in s) book = s.book;
    if ('sd' in s) sd = Object.assign({ mode: 'none', oid: null, name: null, fromName: null, inst: null, loading: false, error: null, busy: false }, s.sd || {});
    if ('sdModels' in s) sdModels = s.sdModels || [];
    if ('sdLoras' in s) sdLoras = s.sdLoras || [];
}

export const routes = {
    '/picture-book/:bookObjectId/style': {
        oninit: function (vnode) { styleView.oninit(vnode); },
        onupdate: function (vnode) {
            let id = vnode.attrs.bookObjectId;
            if (id && id !== bookObjectId) styleView.oninit(vnode);
        },
        view: function () { return layout(pageLayout(m(styleView))); }
    }
};
