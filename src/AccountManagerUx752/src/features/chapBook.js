/**
 * ChapBook feature — Poem library, analysis, and landscape-illustrated poetry-book creation.
 *
 * Routes:
 *   /chap-book   — Poem library + ChapBook creation
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { layout, pageLayout } from '../router.js';
import { Dialog } from '../components/dialogCore.js';
import { ObjectPicker } from '../components/picker.js';
import { bookPages } from '../workflows/pictureBookWorkflow.js';
import { resolveImageUrl } from '../workflows/sceneExtractor.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';
import { am7sd } from '../components/sdConfig.js';
import { am7model } from '../core/model.js';
import { LLMConnector } from '../chat/LLMConnector.js';

// ── REST base ─────────────────────────────────────────────────────────

function cbBase() {
    return applicationPath + '/rest/olio/chap-book';
}

// ── API helpers ───────────────────────────────────────────────────────

async function fetchPoems(themeFilter) {
    let url = cbBase() + '/poems';
    if (themeFilter) url += '?theme=' + encodeURIComponent(themeFilter);
    let resp = await fetch(url, { credentials: 'include', cache: 'no-store' });
    if (!resp.ok) throw new Error('Failed to load poems: ' + resp.status);
    return resp.json();
}

async function analyzePoem(poemObjectId, chatConfigName) {
    // Issue 2c: send the chosen chat config NAME so a re-analysis re-runs theme extraction against the
    // user's selected LLM config. Omitted → backend falls back to its deterministic default.
    let body = {};
    if (chatConfigName) body.chatConfig = chatConfigName;
    let resp = await fetch(cbBase() + '/analyze/' + poemObjectId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Analysis failed: ' + resp.status);
    return resp.json();
}

async function createChapBook(slug, title, poemObjectIds, maxLinesPerPage, chatConfigName) {
    let body = { slug: slug, title: title, poemObjectIds: poemObjectIds, maxLinesPerPage: maxLinesPerPage || 8 };
    // Issue 2b: include the chosen chat config NAME (a chatConfig record name) so the backend contacts
    // the user's selected LLM config for theme analysis. Omitted → backend applies its deterministic
    // default (contentAnalysis → generalChat → library → user config).
    if (chatConfigName) body.chatConfig = chatConfigName;
    let resp = await fetch(cbBase() + '/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Create ChapBook failed: ' + resp.status);
    return resp.json();
}

// TODO(ChapBook Phase 2): set membership for ChapBook creation — select poems by set rather than individual checkbox.
// Set creation and listing are in place (GET /sets, POST /set); add/remove poem endpoints and set-selection UI are deferred.
async function fetchSets() {
    let resp = await fetch(cbBase() + '/sets', { credentials: 'include' });
    if (!resp.ok) throw new Error('Failed to load sets: ' + resp.status);
    return resp.json();
}

async function createPoem(title, author, text) {
    let body = { title, author, text };
    let resp = await fetch(cbBase() + '/poem', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Create poem failed: ' + resp.status);
    return resp.json();
}

// Issue 7: pass chatConfigName (enables LLM-based landscape-prompt generation) and sdConfig.
// The backend olio.pictureBookRequest model declares both fields; chatConfig triggers
// ChapBookUtil.renderChapBook's LLM path when resolved. sdConfig is an ephemeral field
// forwarded for future use.
async function renderChapBook(bookObjectId, chatConfigName, sdConfig) {
    let body = {};
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (sdConfig && Object.keys(sdConfig).length > 0) body.sdConfig = sdConfig;
    let resp = await fetch(cbBase() + '/render/' + bookObjectId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Render failed: ' + resp.status);
    return resp.json();
}

// Gap 6: render ONE ChapBook scene via the new per-scene async endpoint. Mirrors PB2's
// generateSceneImage (workflows/sceneExtractor.js): one HTTP call per scene, same
// olio.pictureBookRequest body shape (chatConfig + sdConfig) the bulk /render path sent, so no
// single request runs long enough to time out at the gateway on a multi-page book.
// Returns { imageObjectId, rendered, skipped, llmUnavailable, llmDegraded }.
//   rendered:true            → an image was produced (imageObjectId is the new image objectId).
//   skipped:true             → the scene was un-prompted (LLM double-blank), so the backend produced
//                              NO image and left it for explicit regeneration (imageObjectId null).
//   rendered:false&&skipped:false → a genuine failure (SD error, patch failure, exception).
//   llmUnavailable:true      → the LLM landscape-prompt STEP could not run at all (no usable chat
//                              config, or the LLM was unreachable / hard-failed). This is a HARD
//                              config/infra fault, NOT a normal per-stanza soft refusal or blank.
//                              It accompanies EITHER a degraded render (llmDegraded) OR a hard skip
//                              (skipped:true) — see below.
//   llmDegraded:true         → the scene DID render (rendered:true) but on the STORED prompt because
//                              the LLM prompt step was unavailable (implies llmUnavailable:true).
// A benign un-prompted skip is skipped:true && llmUnavailable:false (poem just needs Analyze).
async function renderChapBookScene(sceneObjectId, chatConfigName, sdConfig, sdPrompt) {
    let body = { schema: 'olio.pictureBookRequest' };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (sdConfig && Object.keys(sdConfig).length > 0) body.sdConfig = sdConfig;
    // Per-scene landscape-prompt override: when a non-blank prompt is supplied the backend persists it
    // AND renders it verbatim (no LLM regeneration). Omitted / blank → the body carries no sdPrompt and
    // the call behaves exactly as before (backend resolves a landscape prompt itself). Sent verbatim
    // (never trimmed) so what the user typed is exactly what is persisted and rendered.
    if (sdPrompt != null && String(sdPrompt).trim()) body.sdPrompt = sdPrompt;
    let resp = await fetch(cbBase() + '/scene/' + sceneObjectId + '/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) {
        let err = null;
        try { err = await resp.json(); } catch (_) {}
        throw new Error('Scene render failed: ' + ((err && err.error) || resp.status));
    }
    let result = (await resp.json()) || {};
    return {
        imageObjectId: result.imageObjectId != null ? result.imageObjectId : null,
        rendered: !!result.rendered,
        skipped: !!result.skipped,
        llmUnavailable: !!result.llmUnavailable,
        llmDegraded: !!result.llmDegraded
    };
}

// Issue 7 / FIX A: resolve the DEFAULT chat config name for LLM-based landscape prompt
// generation. The old behavior blindly returned results[0].name from an org-wide search — a
// random USER-owned config with no owner filter and no user choice. Instead, prefer a SYSTEM
// library config (contentAnalysis, then generalChat) via LLMConnector — the same system-library
// resolution PB2's wizard uses — and only fall back to an org-wide search that prefers a
// /Library/ config over an arbitrary user one. The user can always override via the picker
// (renderChatConfigRef); this function only supplies the auto-default when they have not.
const CB_DEFAULT_CHAT_CONFIG_NAMES = ['contentAnalysis', 'generalChat'];

async function resolveSystemChatConfig() {
    try { await LLMConnector.ensureLibrary(); } catch (_) {}
    try { await LLMConnector.initPromptLibrary(); } catch (_) {}
    for (let name of CB_DEFAULT_CHAT_CONFIG_NAMES) {
        try {
            let rec = await LLMConnector.resolveConfig(name);
            if (rec && rec.name) return rec;
        } catch (_) {}
    }
    return null;
}

async function fetchDefaultChatConfigName() {
    // 1) Prefer a system library config (contentAnalysis → generalChat).
    let sys = await resolveSystemChatConfig();
    if (sys && sys.name) return sys.name;
    // 2) Fall back to an org-wide search, preferring a /Library/-scoped config over an
    //    arbitrary user-owned one rather than taking results[0] blindly.
    try {
        let resp = await fetch(applicationPath + '/rest/model/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({
                schema: 'io.query',
                type: 'olio.llm.chatConfig',
                cache: false,
                request: ['id', 'objectId', 'name', 'groupPath', 'organizationId', 'ownerId'],
                fields: page?.user?.organizationId
                    ? [{ name: 'organizationId', comparator: 'equals', value: page.user.organizationId }]
                    : []
            })
        });
        if (!resp.ok) return null;
        let arr = await resp.json();
        if (!Array.isArray(arr) || !arr.length) return null;
        let lib = arr.find(function (c) { return c && typeof c.groupPath === 'string' && c.groupPath.indexOf('/Library') === 0; });
        let pick = lib || arr[0];
        return pick && pick.name ? pick.name : null;
    } catch (e) {
        return null;
    }
}

async function importPoemsFromSources(sources) {
    let body = { sources: sources };
    let resp = await fetch(cbBase() + '/poems', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Import failed: ' + resp.status);
    return resp.json();
}

async function deletePoem(poemObjectId) {
    let resp = await fetch(applicationPath + '/rest/model/olio.cb.poem/' + poemObjectId, {
        method: 'DELETE',
        credentials: 'include'
    });
    if (!resp.ok) throw new Error('Delete poem failed: ' + resp.status);
}

// ── renderChapBookPage — landscape page with text overlay ─────────────

/**
 * Render a single ChapBook page: full-bleed landscape image with a poem stanza
 * overlaid on a semi-transparent panel. Follows the same pattern as the PB viewer
 * but tuned for portrait-prose / stanza text.
 *
 * @param {object} scene  — {imageUrl, poemStanza, blurb, title}
 * @param {number} overlayOpacity — 0–1, default 0.4 (background image dimming)
 */
function renderChapBookPage(scene, overlayOpacity) {
    // Images are served by MediaServlet at /media/{orgDotPath}/data.data{groupPath}/{name}
    // (the canonical path-based route — there is no objectId-based /rest/resource route).
    // bookPageView supplies imageGroupPath + imageName for exactly this.
    let imageUrl = scene.imageUrl
        || ((scene.imageGroupPath && scene.imageName)
            ? applicationPath + '/media/' + am7client.dotPath(am7client.currentOrganization)
                + '/data.data' + scene.imageGroupPath + '/' + scene.imageName
            : null);
    let stanzaText = scene.poemStanza || scene.blurb || '';
    let poemTitle = scene.title || '';
    let opacity = overlayOpacity != null ? overlayOpacity : 0.4;
    return m('div.relative.overflow-hidden', { style: 'min-height: 70vh' }, [
        imageUrl ? m('img.absolute.inset-0.w-full.h-full.object-cover', {
            src: imageUrl,
            style: 'opacity: ' + opacity
        }) : null,
        m('div.relative.z-10.flex.items-center.justify-center', { style: 'min-height: 70vh' },
            m('div.bg-black.bg-opacity-40.rounded.p-6.max-w-xl.text-center', [
                m('p', { style: 'font-family: Georgia, serif; line-height: 1.9; color: white; white-space: pre-wrap;' }, stanzaText),
                poemTitle ? m('p.text-xs.text-gray-300.mt-4', poemTitle) : null
            ])
        )
    ]);
}

// ── Poem library state ────────────────────────────────────────────────

let poems = [];
let loading = false;
let loadError = null;
let selectedIds = new Set();
let themeFilter = '';
let sortField = 'title';
let sortAsc = true;
let analyzingIds = new Set();

// Create ChapBook dialog state
let showCreateDialog = false;
let createSlug = '';
let createTitle = '';
let createMaxLines = 8;
let creating = false;
// Issue 2b: chat config chosen (or auto-resolved) BEFORE the create request is sent, so the LLM the
// backend contacts for theme/landscape work is the user's choice rather than a deterministic default.
// { name, objectId } once chosen/resolved; null until the auto-default resolves or the user picks.
let createChatConfigRef = null;
let _createChatConfigResolving = false;

// Add Poems from Notes — multi-select + order before import
let addingPoem = false;
let pendingNotes = [];       // [{objectId, name}] — notes selected, awaiting order confirmation
let showNoteOrderDialog = false;

// Add Poem (direct text entry) dialog state
let showAddPoemDialog = false;
let addPoemTitle = '';
let addPoemAuthor = '';
let addPoemText = '';

// Render state
let renderingBook = false;
let lastRenderResult = null;
let lastCreatedBook = null;

// Gap 6: per-scene render progress (mirrors PB2's genProgress/sceneImageUrls maps in
// workflows/pictureBook.js). renderTotal/renderDone drive the "Rendering N/M..." button label.
let renderProgress = {};    // sceneObjectId → 'generating' | 'done' | 'error'
let renderSceneUrls = {};   // sceneObjectId → resolved media URL (page image refreshed as each completes)
let renderTotal = 0;
let renderDone = 0;

// Landscape-prompt review: resolved preview-image URL per scene for the review card. Populated on load
// from each scene's existing imageObjectId and refreshed after a single-page re-render. Edited prompt
// text is tracked directly on scene.sdPrompt (same convention as scene.title / scene.poemStanza), so no
// separate edit map is needed — reviewScenes entries are already keyed by scene objectId.
let reviewSceneImageUrls = {};   // sceneObjectId → resolved media URL for the review card preview

// Let the SD GPU recover between generations — same rationale as PB2's SCENE_COOLDOWN_MS: the
// shared hardware has hit thermal-critical under sustained back-to-back load with no cooldown.
const SCENE_COOLDOWN_MS = 5000;

function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

// Pre-render dialog state (Issue 8 — SD config form before render)
let showRenderDialog = false;
let pendingRenderBookId = null;
let pendingRenderCallback = null;
// SD config — mirrors pictureBook.js: uses am7sd to load saved defaults and model list
let renderSdCfg = {};
let renderSdConfigInst = null;
let renderSdModelList = [];
let renderSdLoraList = [];
let _renderSdModelsLoaded = false;
let _renderSdLorasFetched = false;
let _renderSdConfigPromise = null;
// FIX A: user-selected (or auto-resolved) chat config for LLM landscape-prompt generation.
// { name, objectId } once chosen/resolved; null until the auto-default resolves or the user picks.
let renderChatConfigRef = null;
let _renderChatConfigResolving = false;

// Role-check warning (Issue 9)
let roleWarning = false;

// My ChapBooks list state
let myBooks = [];
let myBooksLoading = false;
let myBooksError = null;

// ── Helpers ───────────────────────────────────────────────────────────

function slugify(name) {
    return (name || '').toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .substring(0, 64) || 'chapbook-' + Date.now().toString(36);
}

// ── D6 role gate (mirrors pictureBook.js buildActions(): roleWarning = !(pbRoles && pbRoles.user)) ──
// The AccountUsers ("user") role is required to create/analyze/render ChapBook content. Without it
// the feature's mutating actions are BLOCKED (button `disabled`), not merely warned — matching the
// PB2 wizard, which disables Extract/Continue on the same predicate. Pure + exported for unit tests.
function lacksUserRole(ctx) {
    return !(ctx && ctx.roles && ctx.roles.user);
}

// ── D5 Analyze-button persistence (mirrors pictureBook.js "re-derive persisted state on oninit") ──
// The backend keeps no book↔poem link (poems become olio.pb.scene stanza chunks) and the book is
// owned by the olio principal — the client cannot PATCH an attribute onto it — so, like the PB2
// reader which re-loads all state from the persisted record by objectId, the reader re-derives the
// source poem ids on every init from durable localStorage rather than carrying them in module memory.
// `store` is injectable so this stays testable without a DOM.
function cbPoemIdsKey(bookObjectId) { return 'cb-poemids-' + bookObjectId; }

function persistReaderPoemIds(bookObjectId, ids, store) {
    store = store || (typeof localStorage !== 'undefined' ? localStorage : null);
    if (!store || !bookObjectId || !Array.isArray(ids)) return;
    try { store.setItem(cbPoemIdsKey(bookObjectId), JSON.stringify(ids)); } catch (_) {}
}

function loadPersistedReaderPoemIds(bookObjectId, store) {
    store = store || (typeof localStorage !== 'undefined' ? localStorage : null);
    if (!store || !bookObjectId) return [];
    try {
        let raw = store.getItem(cbPoemIdsKey(bookObjectId));
        let parsed = raw ? JSON.parse(raw) : null;
        return Array.isArray(parsed) ? parsed : [];
    } catch (_) { return []; }
}

async function loadPoems() {
    loading = true;
    loadError = null;
    m.redraw();
    try {
        let result = await fetchPoems(themeFilter || null);
        poems = Array.isArray(result) ? result : [];
    } catch (e) {
        loadError = e.message || 'Failed to load';
        poems = [];
    }
    loading = false;
    m.redraw();
}

function sortedPoems() {
    let list = poems.slice();
    list.sort(function (a, b) {
        let av = (a[sortField] || '').toString().toLowerCase();
        let bv = (b[sortField] || '').toString().toLowerCase();
        if (av < bv) return sortAsc ? -1 : 1;
        if (av > bv) return sortAsc ? 1 : -1;
        return 0;
    });
    return list;
}

function filteredPoems() {
    let list = sortedPoems();
    if (themeFilter) {
        let f = themeFilter.toLowerCase();
        list = list.filter(function (p) {
            return (p.theme || '').toLowerCase().indexOf(f) >= 0 ||
                   (p.title || '').toLowerCase().indexOf(f) >= 0;
        });
    }
    return list;
}

function thSort(field) {
    if (sortField === field) {
        sortAsc = !sortAsc;
    } else {
        sortField = field;
        sortAsc = true;
    }
}

function thClass(field) {
    let base = 'px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide cursor-pointer select-none hover:text-gray-800 dark:hover:text-gray-200';
    if (sortField === field) base += ' text-blue-600 dark:text-blue-400';
    return base;
}

function sortIndicator(field) {
    if (sortField !== field) return null;
    return m('span', { class: 'ml-1 material-symbols-outlined', style: 'font-size:12px;vertical-align:middle' },
        sortAsc ? 'arrow_upward' : 'arrow_downward');
}

// ── Create dialog ─────────────────────────────────────────────────────

// Issue 2b: resolve the auto-default chat config for the create dialog, unless the user already
// picked one. Prefers a SYSTEM library config (contentAnalysis → generalChat) via a library lookup
// only — NO LLM call. Does NOT overwrite a user pick. Purely advisory: if it hasn't finished and the
// user picked nothing, the backend still applies its deterministic default at create time.
function ensureCreateChatConfigDefault() {
    if (createChatConfigRef || _createChatConfigResolving) return;
    _createChatConfigResolving = true;
    resolveSystemChatConfig().then(function (rec) {
        _createChatConfigResolving = false;
        if (rec && rec.name && !createChatConfigRef) {
            createChatConfigRef = { name: rec.name, objectId: rec.objectId };
            m.redraw();
        }
    }).catch(function () { _createChatConfigResolving = false; });
}

function openCreateDialog() {
    // Pre-fill title from first selected poem
    let first = poems.find(function (p) { return selectedIds.has(p.objectId); });
    createTitle = first ? (first.title || '') : '';
    createSlug = slugify(createTitle);
    createMaxLines = 8;
    // Issue 2b: fresh chat-config resolution per open (auto-default; user can override via picker).
    createChatConfigRef = null;
    _createChatConfigResolving = false;
    ensureCreateChatConfigDefault();
    showCreateDialog = true;
    m.redraw();
}

function closeCreateDialog() {
    showCreateDialog = false;
    m.redraw();
}

function openSourcePicker(sourceType) {
    let isData = sourceType === 'data.data';
    ObjectPicker.open({
        type: sourceType,
        title: 'Select ' + (isData ? 'data objects' : 'notes') + ' to import as poems (use checkboxes, then ✓)',
        multiSelect: true,
        onSelect: function (items) {
            if (!items || !items.length) return;
            pendingNotes = items.map(function (it) {
                return { objectId: it.objectId, name: it.name || 'Untitled', sourceType: sourceType };
            });
            showNoteOrderDialog = true;
            m.redraw();
        }
    });
}

function moveNoteUp(idx) {
    if (idx <= 0) return;
    let tmp = pendingNotes[idx - 1];
    pendingNotes[idx - 1] = pendingNotes[idx];
    pendingNotes[idx] = tmp;
    m.redraw();
}

function moveNoteDown(idx) {
    if (idx >= pendingNotes.length - 1) return;
    let tmp = pendingNotes[idx + 1];
    pendingNotes[idx + 1] = pendingNotes[idx];
    pendingNotes[idx] = tmp;
    m.redraw();
}

function removeNote(idx) {
    pendingNotes.splice(idx, 1);
    if (!pendingNotes.length) showNoteOrderDialog = false;
    m.redraw();
}

async function doImportNotes() {
    if (!pendingNotes.length) return;
    addingPoem = true;
    showNoteOrderDialog = false;
    m.redraw();
    let sources = pendingNotes.map(function (n) {
        return { type: n.sourceType, objectId: n.objectId, title: n.name };
    });
    let toImport = pendingNotes.length;
    pendingNotes = [];
    try {
        let result = await importPoemsFromSources(sources);
        let imported = (result.poems || []).length;
        if (imported) {
            page.toast('success', 'Imported ' + imported + ' of ' + toImport + ' poem(s)');
            await loadPoems();
            // Auto-select the newly imported poems so "Create ChapBook" button appears immediately.
            // Reset first so prior selections don't accumulate on top of the new batch.
            selectedIds = new Set();
            let newIds = (result.poems || []).map(function (p) { return p.objectId; }).filter(Boolean);
            newIds.forEach(function (id) { selectedIds.add(id); });
        } else if (!result.errors || !result.errors.length) {
            page.toast('warn', 'No poems were imported');
        }
        if (result.errors && result.errors.length) {
            page.toast('error', result.errors.join('; '));
        }
    } catch (e) {
        page.toast('error', 'Import failed: ' + (e.message || ''));
    }
    addingPoem = false;
    m.redraw();
}

async function doAddPoem() {
    if (!addPoemTitle.trim()) {
        page.toast('warn', 'Title is required');
        return;
    }
    if (!addPoemText.trim()) {
        page.toast('warn', 'Poem text is required');
        return;
    }
    addingPoem = true;
    m.redraw();
    try {
        await createPoem(addPoemTitle.trim(), addPoemAuthor.trim() || null, addPoemText.trim());
        page.toast('success', 'Poem added: ' + addPoemTitle.trim());
        showAddPoemDialog = false;
        addPoemTitle = '';
        addPoemAuthor = '';
        addPoemText = '';
        await loadPoems();
    } catch (e) {
        page.toast('error', 'Failed to add poem: ' + (e.message || ''));
    }
    addingPoem = false;
    m.redraw();
}

function loadRenderSdModels() {
    if (_renderSdModelsLoaded && renderSdModelList.length) return;
    _renderSdModelsLoaded = true;
    am7sd.fetchModels().then(function (list) {
        renderSdModelList = Array.isArray(list) ? list : [];
        // A failed/empty first fetch must not lock the panel into the text fallback forever:
        // clear the guard so the next dialog open re-attempts the catalog load.
        if (!renderSdModelList.length) _renderSdModelsLoaded = false;
        m.redraw();
    }).catch(function () { renderSdModelList = []; _renderSdModelsLoaded = false; m.redraw(); });
}

function loadRenderSdLoras() {
    if (_renderSdLorasFetched && renderSdLoraList.length) return;
    _renderSdLorasFetched = true;
    am7sd.fetchLoras().then(function (list) {
        renderSdLoraList = Array.isArray(list) ? list : [];
        if (!renderSdLoraList.length) _renderSdLorasFetched = false;
        m.redraw();
    }).catch(function () { renderSdLoraList = []; _renderSdLorasFetched = false; m.redraw(); });
}

// FIX B: seed the render dialog's SD config EXACTLY like PB2's ensureSdConfig (pictureBook.js).
// Two prior defects addressed here:
//   1) Wrong Model value in the <select>: the old strip removed only a partial identity set and
//      set entity.schema literally; mirroring PB2 (strip the full SD_CONFIG_IDENTITY, use
//      am7model.newPrimitive fallback, key on am7model.jsonModelKey) makes the selected option ===
//      the saved default (sdcfg-default.model) when the dialog opens.
//   2) Stuck "Loading SD configuration…" on retry: _renderSdConfigPromise cached a resolved-null
//      promise on any failure, so a second open returned that null forever. Reset it to null when
//      the attempt did not yield an instance, so the next open re-attempts.
function ensureRenderSdConfig() {
    if (renderSdConfigInst) return Promise.resolve(renderSdConfigInst);
    if (_renderSdConfigPromise) return _renderSdConfigPromise;
    _renderSdConfigPromise = (async function () {
        try {
            let savedConfig = null;
            try {
                savedConfig = await am7sd.loadConfig('sdcfg-default', '~/Data/.preferences');
            } catch (e) { /* non-fatal — fall through to buildEntity */ }
            let entity;
            if (savedConfig) {
                entity = Object.assign({}, savedConfig);
                SD_CONFIG_IDENTITY.forEach(function (k) { delete entity[k]; });
            } else {
                entity = await am7sd.buildEntity();
                if (!entity) entity = am7model.newPrimitive('olio.sd.config');
            }
            if (!entity[am7model.jsonModelKey]) entity[am7model.jsonModelKey] = 'olio.sd.config';
            SD_CONFIG_IDENTITY.forEach(function (k) { delete entity[k]; });
            renderSdConfigInst = am7model.prepareInstance(entity, am7model.forms.sdConfig);
        } catch (e) {
            console.warn('[ChapBook] Failed to build SD config:', e);
        }
        // Stuck-spinner fix: if we did not end up with an instance, clear the cached promise so a
        // subsequent open re-attempts instead of returning this resolved-null promise forever.
        if (!renderSdConfigInst) _renderSdConfigPromise = null;
        m.redraw();
        return renderSdConfigInst;
    })();
    return _renderSdConfigPromise;
}

// FIX A: resolve the auto-default chat config for the render dialog, unless the user already
// picked one. Prefers a SYSTEM library config (contentAnalysis → generalChat); does NOT overwrite
// a user pick. Purely advisory — the confirm handler re-resolves if this hasn't finished yet.
function ensureRenderChatConfigDefault() {
    if (renderChatConfigRef || _renderChatConfigResolving) return;
    _renderChatConfigResolving = true;
    resolveSystemChatConfig().then(function (rec) {
        _renderChatConfigResolving = false;
        if (rec && rec.name && !renderChatConfigRef) {
            renderChatConfigRef = { name: rec.name, objectId: rec.objectId };
            m.redraw();
        }
    }).catch(function () { _renderChatConfigResolving = false; });
}

// Issue 8: open the pre-render SD config dialog; callback is invoked on confirm.
function openRenderConfigDialog(bookObjectId, callback) {
    pendingRenderBookId = bookObjectId;
    pendingRenderCallback = callback || null;
    showRenderDialog = true;
    // FIX A: fresh chat-config resolution per open (auto-default; user can override via picker).
    renderChatConfigRef = null;
    _renderChatConfigResolving = false;
    loadRenderSdModels();
    loadRenderSdLoras();
    ensureRenderSdConfig();
    ensureRenderChatConfigDefault();
    m.redraw();
}

// Issue 7+8: confirm handler — fetch default chatConfig name then call the pending callback.
async function doRenderFromDialog() {
    if (!pendingRenderBookId) return;
    showRenderDialog = false;
    let bookId = pendingRenderBookId;
    let sdCfg = renderSdConfigInst ? Object.assign({}, renderSdConfigInst.entity) : Object.assign({}, renderSdCfg);
    let cb = pendingRenderCallback;
    pendingRenderBookId = null;
    pendingRenderCallback = null;
    m.redraw();
    // FIX A: use the user's chosen config if any; otherwise the resolved system default; otherwise
    // resolve one now (the auto-default kickoff may not have finished when the user clicked Render).
    let chatConfigName = (renderChatConfigRef && renderChatConfigRef.name)
        ? renderChatConfigRef.name
        : await fetchDefaultChatConfigName();
    if (!chatConfigName) {
        page.toast('warn', 'No chat config found — landscape prompts will use stored values');
    }
    if (cb) await cb(bookId, chatConfigName, sdCfg);
}

// Issue 8: pre-render SD config dialog rendered in both PoemLibrary and ChapBookReader views.
function renderRenderDialog() {
    if (!showRenderDialog) return null;
    return m('div', {
        class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
        onclick: function (e) { if (e.target === e.currentTarget) { showRenderDialog = false; m.redraw(); } }
    },
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[90vh] overflow-y-auto' }, [
            m('div', { class: 'flex items-center justify-between mb-4' }, [
                m('h3', { class: 'text-lg font-semibold dark:text-white flex items-center gap-2' }, [
                    m('span', { class: 'material-symbols-outlined text-orange-500' }, 'image'),
                    'Render Settings'
                ]),
                m('button', {
                    class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200',
                    onclick: function () { showRenderDialog = false; m.redraw(); }
                }, m('span', { class: 'material-symbols-outlined' }, 'close'))
            ]),
            m('p', { class: 'text-xs text-gray-500 dark:text-gray-400 mb-4' },
                'Adjust SD settings before rendering. The chat config drives LLM landscape-prompt generation.'),
            // FIX A: visible Chat Config control — opens the library picker (system + accessible
            // chat configs), shows the chosen/auto-resolved name, and the chosen name is sent as the
            // chatConfig field on the render POSTs (via doRenderFromDialog → renderChapBookScenes).
            m('div', { class: 'mb-4' }, [
                m('label', { class: 'field-label' }, 'Chat Config'),
                m('div', {
                    class: 'text-field-full text-sm cursor-pointer flex items-center justify-between',
                    onclick: function () {
                        ObjectPicker.openLibrary({
                            libraryType: 'chatConfig',
                            title: 'Select Chat Config',
                            onSelect: function (item) {
                                if (item && item.name) {
                                    renderChatConfigRef = { name: item.name, objectId: item.objectId };
                                    m.redraw();
                                }
                            }
                        });
                    }
                }, [
                    m('span', { class: renderChatConfigRef ? '' : 'text-gray-400' },
                        renderChatConfigRef ? renderChatConfigRef.name
                            : (_renderChatConfigResolving ? 'Resolving default…' : '(click to select)')),
                    m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                ])
            ]),
            renderSdConfigInst
                ? m(SdConfigPanel, {
                    inst: renderSdConfigInst,
                    models: renderSdModelList,
                    loras: renderSdLoraList,
                    onChange: function () { m.redraw(); }
                })
                : m('div', { class: 'flex items-center gap-2 text-sm text-gray-500 py-4' }, [
                    m('span', { class: 'material-symbols-outlined text-base animate-spin' }, 'progress_activity'),
                    'Loading SD configuration…'
                ]),
            m('div', { class: 'flex justify-end gap-2 mt-4' }, [
                m('button', {
                    class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                    onclick: function () { showRenderDialog = false; m.redraw(); }
                }, 'Cancel'),
                m('button', {
                    class: 'px-4 py-1.5 rounded bg-orange-600 text-white text-sm hover:bg-orange-700 flex items-center gap-1',
                    onclick: doRenderFromDialog
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'image'),
                    ' Render'
                ])
            ])
        ])
    );
}

// Gap 6: the serial per-scene render core — pure and dependency-injected so it is unit-testable
// without a DOM, fetch, or the 5s SD cooldown. This is the exact loop shape as PB2's doGenerateAll
// (workflows/pictureBook.js): iterate scenes ONE AT A TIME (never parallel — avoids hammering the
// shared SD server), record per-scene progress, surface each image as its call returns, and
// aggregate rendered/failed counts.
//   sceneOids     — ordered scene objectIds
//   generateOne   — async (oid) → { imageObjectId, rendered }  (one HTTP call per scene)
//   hooks.sleep   — async (ms) → cooldown between scenes (injected so tests run instantly)
//   hooks.onProgress(oid, status, done, total)
//   hooks.onImage(oid, imageObjectId)
async function renderScenesSerially(sceneOids, generateOne, hooks) {
    hooks = hooks || {};
    let sleepFn = hooks.sleep || function () { return Promise.resolve(); };
    let total = sceneOids.length;
    let rendered = 0, skipped = 0, failed = 0, llmUnavailable = 0, llmDegraded = 0, done = 0;
    let firstGeneration = true;
    for (let i = 0; i < sceneOids.length; i++) {
        let oid = sceneOids[i];
        if (!firstGeneration) await sleepFn(SCENE_COOLDOWN_MS);
        firstGeneration = false;
        if (hooks.onProgress) hooks.onProgress(oid, 'generating', done, total);
        try {
            let result = await generateOne(oid);
            // A skipped scene (un-prompted LLM double-blank) produced NO image but is NOT a failure —
            // tally it separately so it neither vanishes from the count nor is reported as an error.
            // A HARD LLM/config fault (llmUnavailable) is tallied on its OWN counter — distinct from a
            // benign "need prompts" skip — so the bulk summary can flag it separately. Only a BENIGN
            // skip (skipped && !llmUnavailable) counts toward `skipped`; a hard skip counts only under
            // llmUnavailable, and a degraded render counts under BOTH rendered and llmUnavailable.
            if (result && result.rendered) rendered++;
            else if (result && result.skipped && !result.llmUnavailable) skipped++;
            if (result && result.llmUnavailable) llmUnavailable++;
            if (result && result.llmDegraded) llmDegraded++;
            if (hooks.onProgress) hooks.onProgress(oid, 'done', done + 1, total);
            if (result && result.imageObjectId && hooks.onImage) hooks.onImage(oid, result.imageObjectId);
        } catch (e) {
            failed++;
            if (hooks.onProgress) hooks.onProgress(oid, 'error', done + 1, total);
        }
        done++;
    }
    return { rendered: rendered, failed: failed, skipped: skipped, llmUnavailable: llmUnavailable, llmDegraded: llmDegraded, total: total };
}

// Gap 6: fetch the book's scenes then drive the per-scene endpoint serially. Replaces the single
// bulk /render/{bookObjectId} call (which renders every scene on one server thread and times out at
// the gateway for multi-page books). onSceneImage(oid, url) lets a view refresh a page's image in
// place as each scene completes.
async function renderChapBookScenes(bookObjectId, chatConfigName, sdConfig, onSceneImage) {
    let pages = await bookPages(bookObjectId);
    pages = Array.isArray(pages) ? pages : [];
    let sceneOids = pages.map(function (p) { return p.objectId; }).filter(Boolean);
    renderProgress = {};
    renderSceneUrls = {};
    renderTotal = sceneOids.length;
    renderDone = 0;
    m.redraw();
    return renderScenesSerially(sceneOids, function (oid) {
        return renderChapBookScene(oid, chatConfigName, sdConfig);
    }, {
        sleep: sleep,
        onProgress: function (oid, status, done, total) {
            renderProgress[oid] = status;
            renderDone = done;
            renderTotal = total;
            m.redraw();
        },
        onImage: function (oid, imageObjectId) {
            resolveImageUrl(imageObjectId).then(function (url) {
                if (url) {
                    renderSceneUrls[oid] = url;
                    if (onSceneImage) onSceneImage(oid, url);
                    m.redraw();
                }
            });
        }
    });
}

// "Rendering N/M..." while a per-scene render is in flight; plain "Rendering..." before scenes load.
function renderProgressLabel() {
    if (renderTotal > 0) return 'Rendering ' + Math.min(renderDone + 1, renderTotal) + '/' + renderTotal + '...';
    return 'Rendering...';
}

function resetRenderProgress() {
    renderProgress = {};
    renderSceneUrls = {};
    renderTotal = 0;
    renderDone = 0;
}

// The two DISTINCT hard-fault messages, kept as constants so the per-scene handler and the unit
// tests reference the exact same text. They must read plainly differently from the benign
// "un-prompted / run Analyze" skip and from success — the whole point of this signal is that the
// user can tell an unreachable LLM / missing chat config apart from a poem that just needs Analyze.
const CB_LLM_DEGRADED_MSG = 'Rendered using the stored prompt — the LLM prompt step was unavailable (no usable chat config or the LLM is unreachable).';
const CB_LLM_UNAVAILABLE_SKIP_MSG = 'LLM prompt step unavailable (no usable chat config or the LLM is unreachable) — scene not rendered.';

// Map a per-scene render result to the DISTINCT hard-fault signal it should raise, or null when the
// scene's outcome keeps its existing (benign-skip / plain-success / plain-failure) messaging. This is
// the per-scene branch the task calls for, factored out pure so it is unit-testable without a DOM or
// page.toast:
//   llmDegraded            → { level:'warn',  ... } rendered on the STORED prompt (LLM step down)
//   llmUnavailable&&skipped→ { level:'error', ... } LLM step down AND no usable prompt → not rendered
//   otherwise              → null (caller keeps existing behavior; a benign skip is NOT this signal)
// Pure — unit-tested.
function sceneLlmSignal(result) {
    let r = result || {};
    if (r.llmDegraded) return { level: 'warn', message: CB_LLM_DEGRADED_MSG };
    if (r.llmUnavailable && r.skipped) return { level: 'error', message: CB_LLM_UNAVAILABLE_SKIP_MSG };
    return null;
}

// Compose the render-complete toast text. Skipped scenes (the backend's un-prompted LLM
// double-blank outcome — no image produced, left for regeneration) are surfaced separately from
// genuine failures so the user knows some pages still need prompts. Scenes affected by a HARD
// LLM/chat-config fault (llmUnavailable) get their OWN distinct clause — plainly different from the
// benign "need prompts" skip — so the user can tell an unreachable LLM / missing config apart from a
// poem that just needs Analyze. Each clause only appears when its count is > 0, preserving the
// original success wording when everything rendered. Pure — unit-tested.
function renderResultMessage(result) {
    let r = result || {};
    let rendered = r.rendered || 0;
    let skipped = r.skipped || 0;
    let failed = r.failed || 0;
    let llmUnavailable = r.llmUnavailable || 0;
    if (!skipped && !failed && !llmUnavailable) {
        return 'Render complete: ' + rendered + ' scene(s) generated';
    }
    let msg = 'Render complete: ' + rendered + ' generated';
    if (skipped > 0) msg += ', ' + skipped + ' skipped (need prompts)';
    if (failed > 0) msg += ', ' + failed + ' failed';
    if (llmUnavailable > 0) msg += ' — ' + llmUnavailable + ' scene(s) affected by an unavailable LLM/chat config';
    return msg;
}

// Severity for the render-complete toast. Escalate to 'error' when any scene hit a hard LLM/chat-config
// fault (llmUnavailable) — that is an infra/config problem the user must act on, not a benign skip.
// Otherwise warn when anything was skipped or failed, else success. Pure — unit-tested.
function renderResultLevel(result) {
    let r = result || {};
    if ((r.llmUnavailable || 0) > 0) return 'error';
    if ((r.failed || 0) > 0 || (r.skipped || 0) > 0) return 'warn';
    return 'success';
}

// Emit the render-complete toast at the right severity. Uses the file's existing page.toast helper.
function toastRenderResult(result) {
    page.toast(renderResultLevel(result), renderResultMessage(result));
}

async function renderBook(bookObjectId, chatConfigName, sdConfig) {
    renderingBook = true;
    lastRenderResult = null;
    m.redraw();
    try {
        let result = await renderChapBookScenes(bookObjectId, chatConfigName, sdConfig);
        lastRenderResult = result;
        toastRenderResult(result);
    } catch (e) {
        page.toast('error', 'Render failed: ' + (e.message || ''));
    }
    renderingBook = false;
    m.redraw();
}

function renderNoteOrderDialog() {
    if (!showNoteOrderDialog || !pendingNotes.length) return null;
    return m('div', {
        class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
        onclick: function (e) { if (e.target === e.currentTarget) { showNoteOrderDialog = false; m.redraw(); } }
    },
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-lg mx-4' }, [
            m('div', { class: 'flex items-center justify-between mb-4' }, [
                m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'Set poem order (' + pendingNotes.length + ' notes)'),
                m('button', {
                    class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200',
                    onclick: function () { showNoteOrderDialog = false; pendingNotes = []; m.redraw(); }
                }, m('span', { class: 'material-symbols-outlined' }, 'close'))
            ]),
            m('p', { class: 'text-xs text-gray-500 dark:text-gray-400 mb-3' },
                'Use ↑ / ↓ to reorder. Poems will be imported in this sequence.'),
            m('ol', { class: 'space-y-1 mb-4 max-h-72 overflow-y-auto' },
                pendingNotes.map(function (note, idx) {
                    return m('li', { key: note.objectId, class: 'flex items-center gap-2 px-2 py-1 rounded bg-gray-50 dark:bg-gray-800' }, [
                        m('span', { class: 'text-xs text-gray-400 w-6 text-right flex-shrink-0' }, (idx + 1) + '.'),
                        m('span', { class: 'flex-1 text-sm dark:text-white truncate', title: note.name }, note.name),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 disabled:opacity-30',
                            title: 'Move up',
                            disabled: idx === 0,
                            onclick: function (e) { e.stopPropagation(); moveNoteUp(idx); }
                        }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'arrow_upward')),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 disabled:opacity-30',
                            title: 'Move down',
                            disabled: idx === pendingNotes.length - 1,
                            onclick: function (e) { e.stopPropagation(); moveNoteDown(idx); }
                        }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'arrow_downward')),
                        m('button', {
                            class: 'text-red-400 hover:text-red-600 ml-1',
                            title: 'Remove',
                            onclick: function (e) { e.stopPropagation(); removeNote(idx); }
                        }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'close'))
                    ]);
                })
            ),
            m('div', { class: 'flex justify-end gap-2' }, [
                m('button', {
                    class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                    onclick: function () { showNoteOrderDialog = false; pendingNotes = []; m.redraw(); }
                }, 'Cancel'),
                m('button', {
                    class: 'px-4 py-1.5 rounded bg-green-600 text-white text-sm hover:bg-green-700',
                    onclick: doImportNotes
                }, 'Import ' + pendingNotes.length + ' poem(s) in this order')
            ])
        ])
    );
}

async function doCreateChapBook() {
    if (!createSlug || !createTitle) {
        page.toast('warn', 'Slug and title are required');
        return;
    }
    let ids = Array.from(selectedIds);
    if (!ids.length) {
        page.toast('warn', 'Select at least one poem');
        return;
    }
    creating = true;
    m.redraw();
    try {
        let result = await createChapBook(createSlug, createTitle, ids, createMaxLines, createChatConfigRef && createChatConfigRef.name);
        lastCreatedBook = result;
        page.toast('success', 'ChapBook created: ' + (result.slug || createSlug));
        // Carry the source poem objectIds forward: analyze is per-poem and the book does not
        // retain poem references, so the reader's Analyze button needs the ids from this step.
        readerPoemIds = ids.slice();
        selectedIds = new Set();
        showCreateDialog = false;
        let navObjectId = result.objectId || result.bookObjectId;
        if (navObjectId) {
            // D5: persist durably (localStorage) so the reader's Analyze button survives a reload —
            // the book keeps no poem references, so the reader re-derives these ids on oninit.
            persistReaderPoemIds(navObjectId, readerPoemIds);
            m.route.set('/chap-book/read/' + navObjectId);
        }
    } catch (e) {
        page.toast('error', 'Failed to create: ' + (e.message || ''));
    }
    creating = false;
    m.redraw();
}

// ── My ChapBooks API helpers ──────────────────────────────────────────

async function fetchMyBooks() {
    let resp = await fetch(cbBase() + '/books', { credentials: 'include' });
    if (!resp.ok) throw new Error('Failed to load chapbooks: ' + resp.status);
    return resp.json();
}

async function deleteBook(bookObjectId) {
    let resp = await fetch(cbBase() + '/' + bookObjectId, {
        method: 'DELETE',
        credentials: 'include'
    });
    if (!resp.ok) {
        // Issue 1: the backend returns { error:'…' } with a concrete, status-appropriate message
        // (404 not found, 403 PBAC / not a CHAPBOOK, 500 persistence). Surface that message so the
        // toast tells the user why the delete failed instead of a bare status code.
        let msg = null;
        try { let body = await resp.json(); msg = body && (body.error || body.message || body.reason); } catch (_) {}
        throw new Error(msg || ('Delete failed: ' + resp.status));
    }
    return resp.json();
}

async function loadMyBooks() {
    myBooksLoading = true;
    myBooksError = null;
    m.redraw();
    try {
        let result = await fetchMyBooks();
        myBooks = Array.isArray(result) ? result : [];
    } catch (e) {
        myBooksError = e.message || 'Failed to load';
        myBooks = [];
    }
    myBooksLoading = false;
    m.redraw();
}

async function doDeleteBook(book) {
    let ok = await Dialog.confirm({
        title: 'Delete ChapBook',
        message: 'Delete "' + (book.name || book.slug) + '"? This cannot be undone.',
        confirmLabel: 'Delete',
        confirmIcon: 'delete',
        destructive: true
    });
    if (!ok) return;
    try {
        await deleteBook(book.objectId);
        page.toast('success', 'Deleted: ' + (book.name || book.slug));
        await loadMyBooks();
    } catch (e) {
        page.toast('error', 'Delete failed: ' + (e.message || ''));
    }
}

async function doDeleteSelected() {
    let ids = Array.from(selectedIds);
    if (!ids.length) return;
    let ok = await Dialog.confirm({
        title: 'Remove from queue',
        message: 'Remove ' + ids.length + ' poem(s) from the queue? The source notes and documents are not affected.',
        confirmLabel: 'Remove',
        confirmIcon: 'playlist_remove',
        destructive: true
    });
    if (!ok) return;
    let failed = 0;
    for (let id of ids) {
        try {
            await deletePoem(id);
        } catch (e) {
            failed++;
        }
    }
    selectedIds = new Set();
    if (failed) {
        page.toast('error', 'Failed to remove ' + failed + ' poem(s) from queue');
    } else {
        page.toast('success', 'Removed ' + ids.length + ' poem(s) from queue');
    }
    await loadPoems();
}

// ── PoemLibrary component ─────────────────────────────────────────────

const PoemLibrary = {
    oninit: function () {
        poems = [];
        selectedIds = new Set();
        loading = false;
        loadError = null;
        addingPoem = false;
        pendingNotes = [];
        showNoteOrderDialog = false;
        showCreateDialog = false;
        showAddPoemDialog = false;
        addPoemTitle = '';
        addPoemAuthor = '';
        addPoemText = '';
        myBooks = [];
        myBooksLoading = false;
        myBooksError = null;
        // Issue 8: reset render dialog state
        showRenderDialog = false;
        pendingRenderBookId = null;
        pendingRenderCallback = null;
        renderSdCfg = {};
        // Gap 6: clear any per-scene render progress from a prior book/session
        resetRenderProgress();
        // Issue 9 / D6: block ChapBook actions when the AccountUsers role is absent
        roleWarning = lacksUserRole(page.context && page.context());
        loadPoems();
        loadMyBooks();
    },
    view: function () {
        let list = filteredPoems();
        let allSelected = list.length > 0 && list.every(function (p) { return selectedIds.has(p.objectId); });

        return m('div', { class: 'p-4 max-w-5xl' }, [
            // Issue 9: role warning banner
            roleWarning ? m('div', { class: 'mb-4 p-3 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-sm text-yellow-800 dark:text-yellow-200 flex items-center gap-2' }, [
                m('span', { class: 'material-symbols-outlined text-yellow-500' }, 'warning'),
                'You need the AccountUsers role to use ChapBook features.'
            ]) : null,

            // Header
            m('div', { class: 'flex items-center gap-2 mb-4' }, [
                m('span', { class: 'material-symbols-outlined text-2xl text-purple-500' }, 'menu_book'),
                m('h2', { class: 'text-xl font-semibold dark:text-white' }, 'ChapBook — Poem Library')
            ]),

            // Filter row
            m('div', { class: 'flex flex-wrap items-center gap-3 mb-4' }, [
                m('input', {
                    type: 'text',
                    placeholder: 'Filter by theme or title...',
                    class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white w-56',
                    value: themeFilter,
                    oninput: function (e) { themeFilter = e.target.value; m.redraw(); }
                }),
                m('button', {
                    class: 'px-3 py-1 rounded bg-gray-100 dark:bg-gray-700 text-sm dark:text-white hover:bg-gray-200 dark:hover:bg-gray-600',
                    onclick: loadPoems
                }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'refresh'), ' Refresh']),
                m('button', {
                    class: 'px-3 py-1 rounded bg-green-600 text-white text-sm hover:bg-green-700 flex items-center gap-1 disabled:opacity-50',
                    onclick: function () { openSourcePicker('data.note'); },
                    disabled: addingPoem || roleWarning
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, addingPoem ? 'hourglass_empty' : 'note_add'),
                    addingPoem ? ' Importing...' : ' Add from Note'
                ]),
                m('button', {
                    class: 'px-3 py-1 rounded bg-teal-600 text-white text-sm hover:bg-teal-700 flex items-center gap-1 disabled:opacity-50',
                    onclick: function () { openSourcePicker('data.data'); },
                    disabled: addingPoem || roleWarning
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'description'),
                    ' Add from Data'
                ]),
                m('button', {
                    class: 'px-3 py-1 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700 flex items-center gap-1 disabled:opacity-50',
                    onclick: function () { showAddPoemDialog = true; addPoemTitle = ''; addPoemAuthor = ''; addPoemText = ''; m.redraw(); },
                    disabled: roleWarning
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'add'),
                    ' New Poem'
                ]),
                selectedIds.size > 0 ? m('button', {
                    class: 'px-3 py-1 rounded bg-purple-600 text-white text-sm hover:bg-purple-700 disabled:opacity-50',
                    onclick: openCreateDialog,
                    disabled: roleWarning
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'auto_stories'),
                    ' Create ChapBook (' + selectedIds.size + ')'
                ]) : null,
                selectedIds.size > 0 ? m('button', {
                    class: 'px-3 py-1 rounded bg-gray-400 text-white text-sm hover:bg-gray-500 flex items-center gap-1',
                    title: 'Remove selected poems from queue',
                    onclick: function() { doDeleteSelected(); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'playlist_remove'),
                    ' Remove from Queue (' + selectedIds.size + ')'
                ]) : null
            ]),

            // Status
            loading ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-4' }, 'Loading poems...') :
            loadError ? m('div', { class: 'text-sm text-red-500 py-4' }, 'Error: ' + loadError) :
            poems.length === 0 ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-4' }, 'No poems found.') :

            // Table
            m('div', { class: 'overflow-x-auto' },
                m('table', { class: 'w-full text-sm' }, [
                    m('thead', m('tr', { class: 'border-b border-gray-200 dark:border-gray-700' }, [
                        m('th', { class: 'px-3 py-2 w-8' },
                            m('input', {
                                type: 'checkbox',
                                checked: allSelected,
                                onchange: function (e) {
                                    if (e.target.checked) {
                                        list.forEach(function (p) { selectedIds.add(p.objectId); });
                                    } else {
                                        selectedIds = new Set();
                                    }
                                    m.redraw();
                                }
                            })
                        ),
                        m('th', { class: thClass('title'), onclick: function () { thSort('title'); m.redraw(); } },
                            ['Title', sortIndicator('title')]),
                        m('th', { class: thClass('author'), onclick: function () { thSort('author'); m.redraw(); } },
                            ['Author', sortIndicator('author')]),
                        m('th', { class: thClass('theme'), onclick: function () { thSort('theme'); m.redraw(); } },
                            ['Theme', sortIndicator('theme')]),
                        m('th', { class: thClass('mood'), onclick: function () { thSort('mood'); m.redraw(); } },
                            ['Mood', sortIndicator('mood')]),
                        m('th', { class: 'px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide' }, 'Actions')
                    ])),
                    m('tbody',
                        list.map(function (p) {
                            let sel = selectedIds.has(p.objectId);
                            let analyzing = analyzingIds.has(p.objectId);
                            return m('tr', {
                                // Issue 3: key includes selection state so Mithril recreates the row
                                // (and its checkbox) when Clear is clicked — avoids stale checked state
                                // on reused DOM nodes.
                                key: p.objectId + '-' + (sel ? '1' : '0'),
                                class: 'border-b border-gray-100 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800/50 cursor-pointer ' + (sel ? 'bg-purple-50 dark:bg-purple-900/20' : ''),
                                onclick: function () {
                                    if (sel) selectedIds.delete(p.objectId);
                                    else selectedIds.add(p.objectId);
                                    m.redraw();
                                }
                            }, [
                                m('td', { class: 'px-3 py-2', onclick: function (e) { e.stopPropagation(); } },
                                    m('input', {
                                        type: 'checkbox',
                                        checked: sel,
                                        onchange: function () {
                                            if (sel) selectedIds.delete(p.objectId);
                                            else selectedIds.add(p.objectId);
                                            m.redraw();
                                        }
                                    })
                                ),
                                m('td', { class: 'px-3 py-2 font-medium dark:text-white' }, p.title || '—'),
                                m('td', { class: 'px-3 py-2 text-gray-600 dark:text-gray-400' }, p.author || '—'),
                                m('td', { class: 'px-3 py-2 text-gray-600 dark:text-gray-400' }, p.theme || '—'),
                                m('td', { class: 'px-3 py-2 text-gray-600 dark:text-gray-400' }, p.mood || '—'),
                                m('td', { class: 'px-3 py-2', onclick: function (e) { e.stopPropagation(); } },
                                    m('button', {
                                        class: 'px-2 py-1 rounded text-xs bg-gray-100 dark:bg-gray-700 dark:text-white hover:bg-gray-200 dark:hover:bg-gray-600 disabled:opacity-50',
                                        disabled: analyzing,
                                        onclick: async function () {
                                            analyzingIds.add(p.objectId);
                                            m.redraw();
                                            try {
                                                await analyzePoem(p.objectId);
                                                page.toast('success', 'Analysis complete: ' + (p.title || p.objectId));
                                                await loadPoems();
                                            } catch (e) {
                                                page.toast('error', 'Analyze failed: ' + (e.message || ''));
                                            }
                                            analyzingIds.delete(p.objectId);
                                            m.redraw();
                                        }
                                    }, analyzing ? 'Analyzing...' : 'Analyze')
                                )
                            ]);
                        })
                    )
                ])
            ),

            // Note order dialog — shown after multi-select pick, before import.
            renderNoteOrderDialog(),

            // ObjectPicker renders itself as a portal — no inline dialog needed here.

            // Last created book — render + review buttons available when a book was just created
            lastCreatedBook ? m('div', { class: 'mt-4 p-3 rounded bg-purple-50 dark:bg-purple-900/20 border border-purple-200 dark:border-purple-800 flex items-center gap-3' }, [
                m('span', { class: 'material-symbols-outlined text-purple-500' }, 'auto_stories'),
                m('span', { class: 'flex-1 text-sm dark:text-white' }, 'ChapBook created: ' + (lastCreatedBook.slug || lastCreatedBook.objectId || '')),
                m('button', {
                    class: 'px-3 py-1 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700 flex items-center gap-1',
                    onclick: function () { m.route.set('/chap-book/review/' + (lastCreatedBook.objectId || lastCreatedBook.bookObjectId)); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'edit_note'),
                    ' Review'
                ]),
                m('button', {
                    class: 'px-3 py-1 rounded bg-orange-600 text-white text-sm hover:bg-orange-700 flex items-center gap-1 disabled:opacity-50',
                    disabled: renderingBook || roleWarning,
                    // Issue 8: open SD config dialog before rendering
                    onclick: function () { openRenderConfigDialog(lastCreatedBook.objectId || lastCreatedBook.bookObjectId, renderBook); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, renderingBook ? 'hourglass_empty' : 'image'),
                    renderingBook ? (' ' + renderProgressLabel()) : ' Render'
                ])
            ]) : null,

            // My ChapBooks section
            m('div', { class: 'mt-6' }, [
                m('div', { class: 'flex items-center gap-2 mb-3' }, [
                    m('span', { class: 'material-symbols-outlined text-purple-500' }, 'auto_stories'),
                    m('h3', { class: 'text-base font-semibold dark:text-white' }, 'My ChapBooks'),
                    m('button', {
                        class: 'ml-auto px-2 py-1 rounded bg-gray-100 dark:bg-gray-700 text-xs dark:text-white hover:bg-gray-200',
                        onclick: loadMyBooks
                    }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'refresh'), ' Refresh'])
                ]),
                myBooksLoading ? m('div', { class: 'text-sm text-gray-400' }, 'Loading...') :
                myBooksError ? m('div', { class: 'text-sm text-red-500' }, myBooksError) :
                myBooks.length === 0 ? m('div', { class: 'text-sm text-gray-400 dark:text-gray-500' }, 'No ChapBooks yet.') :
                m('div', { class: 'space-y-2' },
                    myBooks.map(function(b) {
                        return m('div', {
                            key: b.objectId,
                            class: 'flex items-center gap-3 px-3 py-2 rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 hover:bg-purple-50 dark:hover:bg-purple-900/20'
                        }, [
                            m('span', { class: 'material-symbols-outlined text-purple-400' }, 'menu_book'),
                            m('span', {
                                class: 'flex-1 text-sm font-medium dark:text-white truncate cursor-pointer hover:text-purple-600',
                                onclick: function() { m.route.set('/chap-book/read/' + b.objectId); }
                            }, b.name || b.slug || b.objectId),
                            m('span', { class: 'text-xs text-gray-400' }, b.bookStatus ? b.bookStatus.toLowerCase() : ''),
                            m('button', {
                                class: 'ml-auto px-2 py-1 rounded bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300 text-xs hover:bg-indigo-200 flex items-center gap-1',
                                title: 'Review and edit pages',
                                onclick: function() { m.route.set('/chap-book/review/' + b.objectId); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'edit_note'), ' Review']),
                            m('button', {
                                class: 'px-2 py-1 rounded bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 text-xs hover:bg-purple-200',
                                onclick: function() { m.route.set('/chap-book/read/' + b.objectId); }
                            }, 'Open'),
                            m('button', {
                                class: 'px-2 py-1 rounded bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 text-xs hover:bg-red-200',
                                onclick: function() { doDeleteBook(b); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'delete')])
                        ]);
                    })
                )
            ]),

            // Create ChapBook dialog — inline overlay following the Dialog pattern.
            // Thread 3: NON-dismissible by backdrop/background click or mouse-out (a click that
            // begins inside a field and ends on the backdrop must not discard an in-progress create).
            // Mirrors the PictureBook wizard's `closable:false` intent — closes only via the explicit
            // X / Cancel buttons or a successful create; no backdrop onclick dismissal.
            showCreateDialog ? m('div', {
                class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50'
            },
                m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-md mx-4' }, [
                    m('div', { class: 'flex items-center justify-between mb-4' }, [
                        m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'Create ChapBook'),
                        m('button', { class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200', onclick: closeCreateDialog },
                            m('span', { class: 'material-symbols-outlined' }, 'close'))
                    ]),
                    m('div', { class: 'space-y-3' }, [
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Title'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: createTitle,
                                oninput: function (e) {
                                    createTitle = e.target.value;
                                    createSlug = slugify(createTitle);
                                }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Slug (URL-safe ID)'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: createSlug,
                                oninput: function (e) { createSlug = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' },
                                'Max Lines per Page'),
                            m('input', {
                                type: 'number',
                                min: 1, max: 32,
                                class: 'w-24 px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: createMaxLines,
                                oninput: function (e) { createMaxLines = parseInt(e.target.value) || 8; }
                            })
                        ]),
                        // Issue 2b: choose the chat config BEFORE the create request is sent — the
                        // backend contacts the LLM server-side only after the POST, so this is the
                        // user's chance to pick the config that will drive theme analysis. Same library
                        // picker the render dialog uses. Auto-resolved to a system default; overridable.
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Chat Config'),
                            m('div', {
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white cursor-pointer flex items-center justify-between',
                                onclick: function () {
                                    ObjectPicker.openLibrary({
                                        libraryType: 'chatConfig',
                                        title: 'Select Chat Config',
                                        onSelect: function (item) {
                                            if (item && item.name) {
                                                createChatConfigRef = { name: item.name, objectId: item.objectId };
                                                m.redraw();
                                            }
                                        }
                                    });
                                }
                            }, [
                                m('span', { class: createChatConfigRef ? '' : 'text-gray-400' },
                                    createChatConfigRef ? createChatConfigRef.name
                                        : (_createChatConfigResolving ? 'Resolving default…' : '(default — click to select)')),
                                m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                            ])
                        ]),
                        m('div', { class: 'text-xs text-gray-500 dark:text-gray-400' },
                            selectedIds.size + ' poem(s) selected')
                    ]),
                    m('div', { class: 'flex justify-end gap-2 mt-4' }, [
                        m('button', {
                            class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                            onclick: closeCreateDialog
                        }, 'Cancel'),
                        m('button', {
                            class: 'px-4 py-1.5 rounded bg-purple-600 text-white text-sm hover:bg-purple-700 disabled:opacity-50',
                            disabled: creating || !createSlug || !createTitle,
                            onclick: doCreateChapBook
                        }, creating ? 'Creating...' : 'Create')
                    ])
                ])
            ) : null,

            // Add Poem (direct text entry) dialog
            showAddPoemDialog ? m('div', {
                class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
                onclick: function (e) { if (e.target === e.currentTarget) { showAddPoemDialog = false; m.redraw(); } }
            },
                m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-lg mx-4' }, [
                    m('div', { class: 'flex items-center justify-between mb-4' }, [
                        m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'New Poem'),
                        m('button', { class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200', onclick: function () { showAddPoemDialog = false; m.redraw(); } },
                            m('span', { class: 'material-symbols-outlined' }, 'close'))
                    ]),
                    m('div', { class: 'space-y-3' }, [
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Title'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: addPoemTitle,
                                oninput: function (e) { addPoemTitle = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Author (optional)'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: addPoemAuthor,
                                oninput: function (e) { addPoemAuthor = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Poem text'),
                            m('textarea', {
                                rows: 10,
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white font-mono',
                                value: addPoemText,
                                oninput: function (e) { addPoemText = e.target.value; }
                            })
                        ])
                    ]),
                    m('div', { class: 'flex justify-end gap-2 mt-4' }, [
                        m('button', {
                            class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                            onclick: function () { showAddPoemDialog = false; m.redraw(); }
                        }, 'Cancel'),
                        m('button', {
                            class: 'px-4 py-1.5 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700 disabled:opacity-50',
                            disabled: addingPoem || !addPoemTitle.trim() || !addPoemText.trim(),
                            onclick: doAddPoem
                        }, addingPoem ? 'Adding...' : 'Add Poem')
                    ])
                ])
            ) : null,

            // Issue 8: pre-render SD config dialog
            renderRenderDialog()
        ]);
    }
};

// ── ChapBook reader state ─────────────────────────────────────────────

let readerBookObjectId = null;
let readerBook = null;
let readerPages = [];
let readerLoading = false;
let readerError = null;
let readerAnalyzing = false;
let readerRendering = false;
// Issue 2c: chat config selected (or auto-resolved) for the reader's Re-analyze pass — lets the user
// re-run theme analysis against a chosen LLM config during EDIT rather than the deterministic default.
// { name, objectId } once chosen/resolved; null until the auto-default resolves or the user picks.
let reanalyzeChatConfigRef = null;
let _reanalyzeChatConfigResolving = false;
// Poem objectIds for the book's Analyze pass (a PER-POEM endpoint). Two sources, unioned:
//   1) SERVER-derived (FIX C): GET /poems?bookObjectId — poems that carry this book's `book` FK.
//      This is the durable source and works for a book opened in a different browser, after
//      localStorage was cleared, or created by another user.
//   2) localStorage (cb-poemids-<bookObjectId>) — poems carried from the create flow that the
//      backend does not (yet) stamp with the book FK. Kept as a best-effort fallback.
let readerPoemIds = [];

// FIX C: server-derived, localStorage-independent poem ids scoped to this book via the `book` FK.
async function fetchBookScopedPoemIds(bookObjectId) {
    if (!bookObjectId) return [];
    try {
        let resp = await fetch(cbBase() + '/poems?bookObjectId=' + encodeURIComponent(bookObjectId), {
            credentials: 'include', cache: 'no-store'
        });
        if (!resp.ok) return [];
        let arr = await resp.json();
        if (!Array.isArray(arr)) return [];
        return arr.map(function (p) { return p && p.objectId; }).filter(Boolean);
    } catch (_) {
        return [];
    }
}

async function loadReaderBook(bookObjectId) {
    readerLoading = true;
    readerError = null;
    m.redraw();
    try {
        try {
            let resp = await fetch(applicationPath + '/rest/model/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({
                    schema: 'io.query',
                    type: 'olio.pb.book',
                    cache: false,
                    request: ['id','objectId','name','urn','groupId','organizationId','ownerId','description','slug','bookStatus','createdByObjectId'],
                    fields: [{ name: 'objectId', comparator: 'EQUALS', value: bookObjectId }]
                })
            });
            let arr = resp.ok ? await resp.json() : [];
            readerBook = Array.isArray(arr) && arr.length ? arr[0] : null;
        } catch (_) {
            readerBook = null;
        }
        // bookPages returns [{objectId, sceneIndex, title, blurb, summary, poemStanza, dataObjectId}, ...]
        // — poemStanza is visible immediately (6D) and dataObjectId is the render fallback image.
        let pages = await bookPages(bookObjectId);
        readerPages = Array.isArray(pages) ? pages : [];
        // FIX C: union server-derived (durable, book-FK-scoped) poem ids with any carried in
        // localStorage, so Analyze is available for books opened without local create-flow state.
        let serverIds = await fetchBookScopedPoemIds(bookObjectId);
        let union = readerPoemIds.slice();
        serverIds.forEach(function (id) { if (union.indexOf(id) === -1) union.push(id); });
        readerPoemIds = union;
    } catch (e) {
        readerError = e.message || 'Failed to load book';
        readerPages = [];
    }
    readerLoading = false;
    m.redraw();
}

// Issue 2c: resolve the auto-default chat config for the reader's Re-analyze control, unless the user
// already picked one. Prefers a SYSTEM library config (contentAnalysis → generalChat) via a library
// lookup only — NO LLM call. Does NOT overwrite a user pick.
function ensureReanalyzeChatConfigDefault() {
    if (reanalyzeChatConfigRef || _reanalyzeChatConfigResolving) return;
    _reanalyzeChatConfigResolving = true;
    resolveSystemChatConfig().then(function (rec) {
        _reanalyzeChatConfigResolving = false;
        if (rec && rec.name && !reanalyzeChatConfigRef) {
            reanalyzeChatConfigRef = { name: rec.name, objectId: rec.objectId };
            m.redraw();
        }
    }).catch(function () { _reanalyzeChatConfigResolving = false; });
}

async function analyzeReaderPoems() {
    if (!readerPoemIds.length) {
        page.toast('warn', 'No poems are associated with this session — analyze poems from the Poem Library, then create the ChapBook.');
        return;
    }
    readerAnalyzing = true;
    m.redraw();
    let ok = 0, fail = 0;
    // Issue 2c: re-run theme analysis against the user's chosen (or auto-resolved) chat config.
    let chatConfigName = reanalyzeChatConfigRef && reanalyzeChatConfigRef.name;
    for (let pid of readerPoemIds) {
        try {
            await analyzePoem(pid, chatConfigName);
            ok++;
        } catch (e) {
            fail++;
        }
    }
    readerAnalyzing = false;
    if (fail) page.toast('warn', 'Analyzed ' + ok + ' poem(s); ' + fail + ' failed');
    else page.toast('success', 'Analyzed ' + ok + ' poem(s)');
    m.redraw();
}

// Issue 8: open the SD config dialog then run the reader-specific render flow.
function renderReaderBook() {
    if (!readerBookObjectId) return;
    openRenderConfigDialog(readerBookObjectId, async function (bookId, chatConfigName, sdConfig) {
        readerRendering = true;
        m.redraw();
        try {
            // Gap 6: per-scene serial render. Each page's image is refreshed in place the moment its
            // scene call returns (onSceneImage), so the reader shows progress page-by-page rather than
            // waiting for one long bulk request.
            let result = await renderChapBookScenes(bookId, chatConfigName, sdConfig, function (oid, url) {
                let pg = readerPages.find(function (p) { return p.objectId === oid; });
                if (pg) pg.imageUrl = url;
            });
            toastRenderResult(result);
            // Reload so the persisted images (dataObjectId path) reconcile with the inline refresh (6C).
            await loadReaderBook(readerBookObjectId);
        } catch (e) {
            page.toast('error', 'Render failed: ' + (e.message || ''));
        }
        readerRendering = false;
        m.redraw();
    });
}

// ── ChapBookReader component — dedicated poem-book reader (6B/6C/6D) ──

const ChapBookReader = {
    oninit: function (vnode) {
        readerBookObjectId = vnode.attrs.bookObjectId || null;
        // D5: re-derive the source poem ids fresh on every init from durable storage keyed by this
        // book (not module memory), so a reload restores the Analyze button and navigating between
        // books never leaks the previous book's poem ids into this one.
        readerPoemIds = loadPersistedReaderPoemIds(readerBookObjectId);
        readerBook = null;
        readerPages = [];
        readerError = null;
        readerAnalyzing = false;
        readerRendering = false;
        // Issue 2c: fresh chat-config resolution per book open (auto-default; user can override via the
        // Re-analyze picker in the header).
        reanalyzeChatConfigRef = null;
        _reanalyzeChatConfigResolving = false;
        ensureReanalyzeChatConfigDefault();
        // Issue 8: reset render dialog state
        showRenderDialog = false;
        pendingRenderBookId = null;
        pendingRenderCallback = null;
        renderSdCfg = {};
        // Gap 6: clear any per-scene render progress from a prior book/session
        resetRenderProgress();
        // Issue 9 / D6: block ChapBook actions when the AccountUsers role is absent
        roleWarning = lacksUserRole(page.context && page.context());
        if (readerBookObjectId) loadReaderBook(readerBookObjectId);
    },
    view: function () {
        let title = (readerBook && (readerBook.name || readerBook.slug)) || 'ChapBook';
        let busy = readerAnalyzing || readerRendering;
        return m('div', { class: 'p-4 max-w-4xl mx-auto' }, [
            // Issue 9: role warning banner
            roleWarning ? m('div', { class: 'mb-4 p-3 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-sm text-yellow-800 dark:text-yellow-200 flex items-center gap-2' }, [
                m('span', { class: 'material-symbols-outlined text-yellow-500' }, 'warning'),
                'You need the AccountUsers role to use ChapBook features.'
            ]) : null,

            // Header — back, title, Analyze + Render controls
            m('div', { class: 'flex flex-wrap items-center gap-3 mb-6' }, [
                m('button', {
                    class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
                    title: 'Back to Poem Library',
                    onclick: function () { m.route.set('/chap-book'); }
                }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
                m('span', { class: 'material-symbols-outlined text-2xl text-purple-500' }, 'menu_book'),
                m('h2', { class: 'flex-1 text-xl font-semibold dark:text-white truncate', title: title }, title),
                m('button', {
                    class: 'px-3 py-1.5 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700 flex items-center gap-1',
                    onclick: function () { m.route.set('/chap-book/review/' + readerBookObjectId); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'edit_note'),
                    ' Review'
                ]),
                // Issue 2c: pick the chat config used for the Re-analyze pass. Opens the same library
                // picker as create/render; auto-resolved to a system default, overridable per book.
                readerPoemIds.length > 0 ? m('button', {
                    class: 'px-3 py-1.5 rounded border border-blue-300 dark:border-blue-700 text-blue-700 dark:text-blue-300 text-sm hover:bg-blue-50 dark:hover:bg-blue-900/20 flex items-center gap-1 disabled:opacity-50',
                    title: 'Choose the chat config used to re-analyze poem themes',
                    disabled: busy || roleWarning,
                    onclick: function () {
                        ObjectPicker.openLibrary({
                            libraryType: 'chatConfig',
                            title: 'Select Chat Config for Re-analysis',
                            onSelect: function (item) {
                                if (item && item.name) {
                                    reanalyzeChatConfigRef = { name: item.name, objectId: item.objectId };
                                    m.redraw();
                                }
                            }
                        });
                    }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'tune'),
                    m('span', { class: 'max-w-[10rem] truncate' },
                        reanalyzeChatConfigRef ? reanalyzeChatConfigRef.name
                            : (_reanalyzeChatConfigResolving ? 'Resolving…' : 'Config'))
                ]) : null,
                readerPoemIds.length > 0 ? m('button', {
                    class: 'px-3 py-1.5 rounded bg-blue-600 text-white text-sm hover:bg-blue-700 flex items-center gap-1 disabled:opacity-50',
                    title: 'Re-analyze poem themes with the selected chat config',
                    disabled: busy || roleWarning,
                    onclick: analyzeReaderPoems
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, readerAnalyzing ? 'hourglass_empty' : 'psychology'),
                    readerAnalyzing ? ' Analyzing...' : ' Analyze'
                ]) : null,
                m('button', {
                    class: 'px-3 py-1.5 rounded bg-orange-600 text-white text-sm hover:bg-orange-700 flex items-center gap-1 disabled:opacity-50',
                    disabled: busy || roleWarning,
                    onclick: renderReaderBook
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, readerRendering ? 'hourglass_empty' : 'image'),
                    readerRendering ? (' ' + renderProgressLabel()) : ' Render'
                ])
            ]),

            // Body — status or pages
            readerLoading ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-12 text-center' }, 'Loading book...') :
            readerError ? m('div', { class: 'text-sm text-red-500 py-12 text-center' }, 'Error: ' + readerError) :
            readerPages.length === 0 ? m('div', { class: 'text-center py-12' }, [
                m('span', { class: 'material-symbols-outlined text-5xl text-gray-300 mb-4' }, 'auto_stories'),
                m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'No pages in this book yet.')
            ]) :

            m('div', { class: 'space-y-6' },
                readerPages.map(function (pg, idx) {
                    return m('div', { key: pg.objectId || idx, class: 'rounded-lg overflow-hidden border border-gray-200 dark:border-gray-700' }, [
                        renderChapBookPage(pg),
                        m('div', { class: 'px-3 py-1.5 text-xs text-gray-400 text-center bg-gray-50 dark:bg-gray-800/50' },
                            'Page ' + (idx + 1) + ' of ' + readerPages.length)
                    ]);
                })
            ),

            // Issue 8: pre-render SD config dialog
            renderRenderDialog()
        ]);
    }
};

// ── ChapBookReview — pre-render editing panel ─────────────────────────

const FONT_OPTIONS = [
    { value: '', label: 'Default' },
    { value: 'system-ui, sans-serif', label: 'System UI' },
    { value: 'Georgia, serif', label: 'Serif' },
    { value: '"Courier New", monospace', label: 'Monospace' },
    { value: 'cursive', label: 'Cursive' },
    { value: 'Impact, fantasy', label: 'Display' }
];

let reviewBookObjectId = null;
let reviewBook = null;
let reviewScenes = [];
let reviewLoading = false;
let reviewError = null;
let reviewRendering = false;
let reviewGroupId = null;

// ── Per-scene SD-config overrides (Gap 8) ─────────────────────────────
// Mirrors PB2's sceneOverrides (workflows/pictureBook.js): each is a real olio.sd.config edited
// through the standard form system (forms.sdConfigOverrides via the generic object view). Only the
// SPARSE delta (persisted override + fields the user just edited) is sent — never a materialized
// full record, which would make "overridden" indistinguishable from "default". Unlike PB2, which
// sends the delta inline at generate time, a ChapBook scene has no sceneNode, so the override is
// PERSISTED on the scene via PUT /rest/olio/picture-book/scene/{oid}/config-override.
let sceneOverrideInsts = {};    // objectId → am7model instance (forms.sdConfigOverrides)
let sceneOverrideViews = {};    // objectId → generic object-view component
let sceneOverrideExpanded = {}; // objectId → bool (mount the heavy override form only when open)

// Identity/transient fields never diffed into a per-scene delta nor sent as part of the override.
const SD_CONFIG_IDENTITY = ['id', 'objectId', 'urn', 'ownerId', 'groupId', 'organizationId', 'groupPath', 'organizationPath', 'narration'];

async function patchScene(sceneObjectId, changes) {
    let body = Object.assign({ schema: 'olio.pb.scene', objectId: sceneObjectId }, changes);
    let resp = await fetch(applicationPath + '/rest/model', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Patch scene failed: ' + resp.status);
    return resp.json();
}

async function deleteScene(sceneObjectId) {
    let resp = await fetch(applicationPath + '/rest/model/olio.pb.scene/' + sceneObjectId, {
        method: 'DELETE',
        credentials: 'include'
    });
    if (!resp.ok) throw new Error('Delete scene failed: ' + resp.status);
    return resp.json();
}

async function createSceneRecord(sceneData) {
    let resp = await fetch(applicationPath + '/rest/model/olio.pb.scene', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(sceneData)
    });
    if (!resp.ok) throw new Error('Create scene failed: ' + resp.status);
    return resp.json();
}

// Fetch only the fields needed by the review panel — avoids /full's planMost(true)
// which chains olio.pb.scene → sceneNode → workflow → run → chatConfig → charPerson → ...
// and exceeds BaseRecord's max depth.
async function loadSceneFields(sceneObjectId) {
    let resp = await fetch(applicationPath + '/rest/model/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
            schema: 'io.query',
            type: 'olio.pb.scene',
            cache: false,
            request: ['id', 'objectId', 'groupId', 'pageFont', 'pageBgColor', 'pageTextAlign', 'sceneIndex'],
            fields: [{ name: 'objectId', comparator: 'EQUALS', value: sceneObjectId }],
            recordCount: 1
        })
    });
    if (!resp.ok) throw new Error('Load scene failed: ' + resp.status);
    let body = await resp.json();
    if (Array.isArray(body)) return body[0] || null;
    if (body && body.results) return body.results[0] || null;
    return null;
}

// Gap 8 + skip-render: batch-read every scene's persisted configOverride, sdPrompt and imageObjectId
// in one query. bookPages()/pages projects none of these, so they are fetched here scoped by
// groupId + organizationId (both NUMBERS — a data.directory-derived list query needs an explicit
// organizationId or PBAC denies), cache:false for a fresh read. sdPrompt + imageObjectId let the
// review card flag un-prompted scenes (see isSceneUnprompted); promptLocked marks a user-authoritative
// prompt so a saved edit is never flagged for regeneration. Returns a map
// { objectId → { configOverride: string|null, sdPrompt: string, imageObjectId: string|null, promptLocked: bool } }.
async function loadSceneOverrides(groupId) {
    let orgId = page && page.user ? page.user.organizationId : null;
    if (groupId == null || orgId == null) return {};
    let resp = await fetch(applicationPath + '/rest/model/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
            schema: 'io.query',
            type: 'olio.pb.scene',
            cache: false,
            request: ['id', 'objectId', 'configOverride', 'sdPrompt', 'imageObjectId', 'promptLocked'],
            fields: [
                { name: 'groupId', comparator: 'EQUALS', value: Number(groupId) },
                { name: 'organizationId', comparator: 'EQUALS', value: Number(orgId) }
            ],
            recordCount: 500
        })
    });
    if (!resp.ok) throw new Error('Load overrides failed: ' + resp.status);
    let body = await resp.json();
    let rows = Array.isArray(body) ? body : (body && body.results) ? body.results : [];
    let map = {};
    rows.forEach(function (r) {
        if (r && r.objectId) {
            map[r.objectId] = {
                configOverride: r.configOverride || null,
                sdPrompt: r.sdPrompt || '',
                imageObjectId: r.imageObjectId || null,
                promptLocked: !!r.promptLocked
            };
        }
    });
    return map;
}

// Gap 8: persist (or clear) a scene's sparse override. A null/blank body clears the field; a
// non-blank body must be sparse olio.sd.config JSON carrying its schema, or the backend returns 400.
async function putSceneConfigOverride(sceneObjectId, configOverrideString) {
    let resp = await fetch(applicationPath + '/rest/olio/picture-book/scene/' + sceneObjectId + '/config-override', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ configOverride: configOverrideString == null ? null : configOverrideString })
    });
    if (!resp.ok) throw new Error('Save override failed: ' + resp.status);
    return resp.json();
}

// Landscape prompt: persist (or clear) a scene's LLM landscape prompt verbatim. Modeled on
// putSceneConfigOverride — same fetch idiom, PUT to the fixed backend contract at
// /rest/olio/chap-book/scene/{oid}/prompt with body { sdPrompt }. A null / blank value CLEARS the
// stored prompt (the backend treats absent / JSON-null / blank as a clear); a non-blank value is stored
// exactly as given. Returns the { updated: bool } contract body.
async function putSceneLandscapePrompt(sceneObjectId, sdPrompt) {
    let value = (sdPrompt == null || !String(sdPrompt).trim()) ? null : sdPrompt;
    let resp = await fetch(cbBase() + '/scene/' + sceneObjectId + '/prompt', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ sdPrompt: value })
    });
    if (!resp.ok) throw new Error('Save prompt failed: ' + resp.status);
    return resp.json();
}

// Resolve (and cache-bust) a scene's current image into the review-card preview map. Each render
// produces a NEW data.data objectId, so re-reading scene.imageObjectId through resolveImageUrl (keyed
// by objectId) yields the fresh URL — the same refresh the book-render flow does via renderSceneUrls.
async function refreshSceneImage(scene) {
    if (!scene || !scene.objectId || !scene.imageObjectId) return;
    try {
        let url = await resolveImageUrl(scene.imageObjectId);
        if (url) {
            reviewSceneImageUrls[scene.objectId] = url;
            m.redraw();
        }
    } catch (_) {}
}

async function loadReviewBook(bookObjectId) {
    reviewLoading = true;
    reviewError = null;
    reviewScenes = [];
    reviewGroupId = null;
    // Gap 8: drop cached override forms/views from any prior book so they rebuild from fresh data.
    sceneOverrideInsts = {};
    sceneOverrideViews = {};
    sceneOverrideExpanded = {};
    // Landscape-prompt review: drop any prior book's resolved preview URLs.
    reviewSceneImageUrls = {};
    m.redraw();
    try {
        try {
            let resp = await fetch(applicationPath + '/rest/model/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({
                    schema: 'io.query',
                    type: 'olio.pb.book',
                    cache: false,
                    request: ['id', 'objectId', 'name', 'slug', 'bookStatus'],
                    fields: [{ name: 'objectId', comparator: 'EQUALS', value: bookObjectId }]
                })
            });
            let arr = resp.ok ? await resp.json() : [];
            reviewBook = Array.isArray(arr) && arr.length ? arr[0] : null;
        } catch (_) {
            reviewBook = null;
        }
        // bookPages returns ordered scenes with poemStanza + title already populated (6D verified).
        let pages = await bookPages(bookObjectId);
        reviewScenes = (Array.isArray(pages) ? pages : []).map(function (pg) {
            return {
                objectId: pg.objectId,
                id: pg.id,
                sceneIndex: pg.sceneIndex,
                title: pg.title || '',
                poemStanza: pg.poemStanza || pg.blurb || '',
                pageFont: pg.pageFont || '',
                pageBgColor: pg.pageBgColor || '',
                pageTextAlign: pg.pageTextAlign || '',
                configOverride: null,
                sdPrompt: pg.sdPrompt || '',
                imageObjectId: pg.imageObjectId || pg.dataObjectId || null,
                _saving: false
            };
        });
        // Load first scene fully to get groupId (needed for split / new scene creation)
        // and to backfill style fields that bookPages may not project.
        if (reviewScenes.length > 0 && reviewScenes[0].objectId) {
            try {
                let fields = await loadSceneFields(reviewScenes[0].objectId);
                if (fields) {
                    reviewGroupId = fields.groupId || null;
                    if (fields.pageFont || fields.pageBgColor || fields.pageTextAlign) {
                        reviewScenes[0].pageFont = fields.pageFont || '';
                        reviewScenes[0].pageBgColor = fields.pageBgColor || '';
                        reviewScenes[0].pageTextAlign = fields.pageTextAlign || '';
                    }
                }
            } catch (_) {}
        }
        // Gap 8 + skip-render: batch-read persisted per-scene overrides, sdPrompt and imageObjectId
        // (bookPages projects none of these) so the review card can flag un-prompted scenes.
        if (reviewGroupId != null) {
            try {
                let ovMap = await loadSceneOverrides(reviewGroupId);
                reviewScenes.forEach(function (s) {
                    let row = s.objectId ? ovMap[s.objectId] : null;
                    if (row) {
                        s.configOverride = row.configOverride;
                        s.sdPrompt = row.sdPrompt || s.sdPrompt;
                        s.imageObjectId = row.imageObjectId || s.imageObjectId;
                        s.promptLocked = row.promptLocked;
                    }
                });
            } catch (_) {}
        }
        // Landscape-prompt review: prefill each card's preview with its already-rendered image (if any)
        // so the user reviews prompts and current images together. Resolved lazily; scenes with no image
        // simply show no preview.
        reviewScenes.forEach(function (s) { if (s.imageObjectId) refreshSceneImage(s); });
    } catch (e) {
        reviewError = e.message || 'Failed to load book';
    }
    reviewLoading = false;
    m.redraw();
}

async function doPatchSceneField(idx, field, value) {
    let scene = reviewScenes[idx];
    if (!scene) return;
    scene[field] = value;
    scene._saving = true;
    m.redraw();
    try {
        let changes = {};
        changes[field] = value;
        await patchScene(scene.objectId, changes);
    } catch (e) {
        page.toast('error', 'Save failed: ' + (e.message || ''));
    }
    scene._saving = false;
    m.redraw();
}

async function doSplitScene(idx) {
    let scene = reviewScenes[idx];
    if (!scene) return;
    let text = scene.poemStanza || '';
    let lines = text.split('\n');
    let mid = Math.ceil(lines.length / 2);
    let firstHalf = lines.slice(0, mid).join('\n');
    let secondHalf = lines.slice(mid).join('\n');
    if (!secondHalf.trim()) {
        page.toast('warn', 'Not enough text to split');
        return;
    }
    scene._saving = true;
    m.redraw();
    try {
        await patchScene(scene.objectId, { poemStanza: firstHalf });
        scene.poemStanza = firstHalf;
        let newScene = {
            schema: 'olio.pb.scene',
            name: 'scene-split-' + Date.now(),
            sceneIndex: scene.sceneIndex + 0.5,
            poemStanza: secondHalf,
            title: (scene.title ? scene.title + ' (cont.)' : '')
        };
        if (reviewGroupId) newScene.groupId = reviewGroupId;
        await createSceneRecord(newScene);
        page.toast('success', 'Scene split');
        await loadReviewBook(reviewBookObjectId);
    } catch (e) {
        page.toast('error', 'Split failed: ' + (e.message || ''));
        scene._saving = false;
        m.redraw();
    }
}

async function doMergeScene(idx) {
    let scene = reviewScenes[idx];
    let next = reviewScenes[idx + 1];
    if (!scene || !next) return;
    scene._saving = true;
    m.redraw();
    try {
        let merged = (scene.poemStanza || '') + '\n' + (next.poemStanza || '');
        await patchScene(scene.objectId, { poemStanza: merged });
        scene.poemStanza = merged;
        await deleteScene(next.objectId);
        page.toast('success', 'Scenes merged');
        await loadReviewBook(reviewBookObjectId);
    } catch (e) {
        page.toast('error', 'Merge failed: ' + (e.message || ''));
        scene._saving = false;
        m.redraw();
    }
}

async function doDeleteScene(idx) {
    let scene = reviewScenes[idx];
    if (!scene) return;
    let ok = await Dialog.confirm({
        title: 'Remove page',
        message: 'Remove page ' + (idx + 1) + '? This cannot be undone.',
        confirmLabel: 'Remove',
        confirmIcon: 'delete',
        destructive: true
    });
    if (!ok) return;
    scene._saving = true;
    m.redraw();
    try {
        await deleteScene(scene.objectId);
        reviewScenes.splice(idx, 1);
        page.toast('success', 'Page removed');
    } catch (e) {
        page.toast('error', 'Delete failed: ' + (e.message || ''));
        scene._saving = false;
    }
    m.redraw();
}

// Issue 8: open the SD config dialog before rendering, then execute render with chatConfig + sdConfig.
function renderReviewBook() {
    if (!reviewBookObjectId) return;
    openRenderConfigDialog(reviewBookObjectId, async function (bookId, chatConfigName, sdConfig) {
        reviewRendering = true;
        m.redraw();
        try {
            // Gap 6: per-scene serial render (one /scene/{oid}/generate call at a time) — replaces the
            // single bulk /render call that timed out at the gateway for multi-page books.
            let result = await renderChapBookScenes(bookId, chatConfigName, sdConfig);
            toastRenderResult(result);
        } catch (e) {
            page.toast('error', 'Render failed: ' + (e.message || ''));
        }
        reviewRendering = false;
        m.redraw();
    });
}

// ── Per-scene SD-config override helpers (Gap 8) ──────────────────────

function parseConfigOverride(s) {
    if (!s || typeof s !== 'string' || !s.trim()) return null;
    try {
        let o = JSON.parse(s);
        return (o && typeof o === 'object') ? o : null;
    } catch (_) {
        return null;
    }
}

// Build (once) the override entity + instance + view for a scene. Pre-fills from any persisted
// configOverride so the form shows the current override values; the generic object view renders
// forms.sdConfigOverrides (the SAME form PB2 and CardGame use — no new field set is defined here).
function getSceneOverrideInst(scene) {
    let oid = scene.objectId;
    if (!sceneOverrideInsts[oid]) {
        let base = parseConfigOverride(scene.configOverride);
        if (!base) base = am7model.newPrimitive('olio.sd.config');
        base[am7model.jsonModelKey] = 'olio.sd.config';
        SD_CONFIG_IDENTITY.forEach(function (k) { delete base[k]; });
        let entity = am7model.prepareEntity(base, 'olio.sd.config');
        sceneOverrideInsts[oid] = am7model.prepareInstance(entity, am7model.forms.sdConfigOverrides);
        sceneOverrideViews[oid] = page.views.object();
    }
    return sceneOverrideInsts[oid];
}

function resetSceneOverride(oid) {
    delete sceneOverrideInsts[oid];
    delete sceneOverrideViews[oid];
}

// The SPARSE override to persist: previously-saved override fields overlaid with the fields the user
// just edited (inst.changes). Never serializes the whole entity — that would materialize every
// defaulted field and defeat the sparse design the backend enforces. Carries schema olio.sd.config
// (jsonModelKey === 'schema') so the backend's parseOverride accepts it. Returns null when empty.
function computeSceneOverrideDelta(scene) {
    let delta = {};
    let prev = parseConfigOverride(scene.configOverride);
    if (prev) {
        Object.keys(prev).forEach(function (k) {
            if (k === am7model.jsonModelKey || SD_CONFIG_IDENTITY.includes(k)) return;
            delta[k] = prev[k];
        });
    }
    let inst = sceneOverrideInsts[scene.objectId];
    if (inst && inst.changes) {
        inst.changes.forEach(function (k) {
            if (k === am7model.jsonModelKey || SD_CONFIG_IDENTITY.includes(k)) return;
            let v = inst.entity[k];
            if (v !== undefined) delta[k] = v;
        });
    }
    if (!Object.keys(delta).length) return null;
    delta[am7model.jsonModelKey] = 'olio.sd.config';
    return delta;
}

function sceneHasOverride(scene) {
    if (parseConfigOverride(scene.configOverride)) return true;
    let inst = sceneOverrideInsts[scene.objectId];
    return !!(inst && inst.changes && inst.changes.length > 0);
}

async function doSaveSceneOverride(idx) {
    let scene = reviewScenes[idx];
    if (!scene) return;
    let delta = computeSceneOverrideDelta(scene);
    if (!delta) { page.toast('info', 'No overrides set to save'); return; }
    scene._saving = true;
    m.redraw();
    try {
        let payload = JSON.stringify(delta);
        await putSceneConfigOverride(scene.objectId, payload);
        scene.configOverride = payload;
        resetSceneOverride(scene.objectId);   // rebuild the form from the persisted override
        page.toast('success', 'Overrides saved');
    } catch (e) {
        page.toast('error', 'Save failed: ' + (e.message || ''));
    }
    scene._saving = false;
    m.redraw();
}

async function doClearSceneOverride(idx) {
    let scene = reviewScenes[idx];
    if (!scene) return;
    scene._saving = true;
    m.redraw();
    try {
        await putSceneConfigOverride(scene.objectId, null);
        scene.configOverride = null;
        resetSceneOverride(scene.objectId);
        page.toast('success', 'Overrides cleared');
    } catch (e) {
        page.toast('error', 'Clear failed: ' + (e.message || ''));
    }
    scene._saving = false;
    m.redraw();
}

// A ChapBook scene is "un-prompted" when the render pipeline produced NO image (imageObjectId
// blank) AND its stored sdPrompt is blank or the "landscape, " no-LLM fallback shape. A user-locked
// prompt (promptLocked, set when the user saves/edits the landscape prompt) is authoritative and is
// NEVER treated as un-prompted regardless of shape — it mirrors the backend's explicit provenance in
// resolveScenePrompt, so a saved edit that happens to start "landscape, " is not flagged for
// regeneration. Otherwise this mirrors the backend fallback test (isMeaningful(prompt) &&
// !prompt.startsWith("landscape, ")). Such a scene was SKIPPED on the last render and needs the
// user to explicitly regenerate it. Pure — unit-tested.
function isSceneUnprompted(scene) {
    if (!scene) return false;
    if (scene.promptLocked) return false;
    if (scene.imageObjectId) return false;
    let p = (scene.sdPrompt || '').trim();
    if (!p) return true;
    return p.indexOf('landscape, ') === 0;
}

// Regenerate a single un-prompted scene through the per-scene generate path (the same endpoint the
// book-level render drives). A chatConfig is resolved first so the LLM can produce a real landscape
// prompt — the whole point of regenerating a scene that was skipped for want of one. On success the
// scene gains an imageObjectId, which clears the "needs prompt" affordance on the next redraw; a
// repeat skip is surfaced so the user knows the LLM still couldn't produce a usable prompt.
async function doRegenerateScene(idx) {
    let scene = reviewScenes[idx];
    if (!scene || !scene.objectId) return;
    scene._saving = true;
    m.redraw();
    try {
        let chatConfigName = await fetchDefaultChatConfigName();
        let result = await renderChapBookScene(scene.objectId, chatConfigName, {});
        let signal = sceneLlmSignal(result);
        if (result && result.rendered) {
            scene.imageObjectId = result.imageObjectId;
            // A degraded render (rendered on the STORED prompt because the LLM step was down) still
            // produced an image, but the user must know it did NOT use a fresh prompt — warn distinctly.
            if (signal) page.toast(signal.level, signal.message);
            else page.toast('success', 'Scene regenerated');
        } else if (signal) {
            // llmUnavailable && skipped: the LLM step could not run AND there was no usable stored
            // prompt, so nothing was rendered — a distinct hard error, NOT the benign "run Analyze".
            page.toast(signal.level, signal.message);
        } else if (result && result.skipped) {
            page.toast('warn', 'Still no usable prompt — Analyze the poem or edit the stanza, then regenerate');
        } else {
            page.toast('error', 'Regenerate failed');
        }
    } catch (e) {
        page.toast('error', 'Regenerate failed: ' + (e.message || ''));
    }
    scene._saving = false;
    m.redraw();
}

// Landscape prompt: persist the prompt currently shown/edited in this scene's textarea (tracked on
// scene.sdPrompt). A blank value clears the stored prompt — the backend contract handles that. This is
// a save-only action; it does NOT render (the user re-renders separately once prompts look right).
async function doSaveSceneLandscapePrompt(idx) {
    let scene = reviewScenes[idx];
    if (!scene || !scene.objectId) return;
    scene._saving = true;
    m.redraw();
    try {
        let val = scene.sdPrompt || '';
        await putSceneLandscapePrompt(scene.objectId, val);
        // Reflect the persisted state locally: a blank save clears the field. A real edit LOCKS the
        // prompt (mirrors the backend), so isSceneUnprompted stops flagging this card as "needs prompt"
        // immediately, without waiting for a view reload to re-read promptLocked from the backend.
        scene.sdPrompt = val.trim() ? val : '';
        scene.promptLocked = !!val.trim();
        page.toast('success', val.trim() ? 'Landscape prompt saved' : 'Landscape prompt cleared');
    } catch (e) {
        page.toast('error', 'Save prompt failed: ' + (e.message || ''));
    }
    scene._saving = false;
    m.redraw();
}

// Landscape prompt: re-render a SINGLE page using the prompt currently shown/edited in its textarea —
// no need to re-render the whole book. A non-blank prompt is sent as the verbatim sdPrompt (persisted +
// rendered, no LLM regeneration), so the edit is used for this render even if it was not separately
// saved first. A blank prompt omits it, so the backend resolves a landscape prompt via the LLM exactly
// as the book render does — hence a chat config is resolved only in that case. On success the card's
// preview image is refreshed from the new imageObjectId.
async function doRerenderScene(idx) {
    let scene = reviewScenes[idx];
    if (!scene || !scene.objectId) return;
    scene._saving = true;
    m.redraw();
    try {
        let prompt = (scene.sdPrompt || '').trim();
        let chatConfigName = prompt ? null : await fetchDefaultChatConfigName();
        let result = await renderChapBookScene(scene.objectId, chatConfigName, {}, scene.sdPrompt);
        let signal = sceneLlmSignal(result);
        if (result && result.rendered) {
            scene.imageObjectId = result.imageObjectId;
            await refreshSceneImage(scene);
            // A degraded render (rendered on the stored prompt because the LLM step was down) still
            // produced an image, but the user must know it did NOT use a fresh prompt — warn distinctly.
            if (signal) page.toast(signal.level, signal.message);
            else page.toast('success', 'Page re-rendered');
        } else if (signal) {
            // llmUnavailable && skipped: the LLM step could not run AND there was no usable prompt.
            page.toast(signal.level, signal.message);
        } else if (result && result.skipped) {
            page.toast('warn', 'Still no usable prompt — edit the landscape prompt above, then re-render');
        } else {
            page.toast('error', 'Re-render failed');
        }
    } catch (e) {
        page.toast('error', 'Re-render failed: ' + (e.message || ''));
    }
    scene._saving = false;
    m.redraw();
}

function renderSceneCard(scene, idx) {
    let isLast = idx === reviewScenes.length - 1;
    let alignOptions = ['left', 'center', 'right'];
    let alignIcons = { left: 'format_align_left', center: 'format_align_center', right: 'format_align_right' };
    let oid = scene.objectId;
    let overrideOpen = !!sceneOverrideExpanded[oid];
    let overridden = sceneHasOverride(scene);
    return m('div', {
        key: scene.objectId || idx,
        class: 'rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 p-4 space-y-3'
    }, [
        // Scene header row
        m('div', { class: 'flex items-center gap-2' }, [
            m('span', { class: 'text-xs text-gray-400 dark:text-gray-500 font-mono flex-shrink-0' },
                'Page ' + (idx + 1) + ' of ' + reviewScenes.length),
            scene._saving ? m('span', { class: 'ml-2 text-xs text-blue-500' }, 'Saving...') : null,
            // Skip-render: an un-prompted scene produced no image on the last render because there was
            // no usable landscape prompt (LLM double-blank). Offer an explicit per-scene regenerate.
            isSceneUnprompted(scene) ? m('div', { class: 'ml-auto flex items-center gap-2' }, [
                m('span', { class: 'text-xs text-amber-600 dark:text-amber-400 flex items-center gap-1' }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'warning'),
                    'Needs prompt'
                ]),
                m('button', {
                    class: 'px-2 py-1 rounded bg-orange-600 text-white text-xs hover:bg-orange-700 disabled:opacity-40 flex items-center gap-1',
                    title: 'Regenerate this page — resolves a landscape prompt via the LLM, then renders an image',
                    disabled: scene._saving || roleWarning,
                    onclick: function () { doRegenerateScene(idx); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'refresh'),
                    ' Regenerate'
                ])
            ]) : null
        ]),
        // Title
        m('div', [
            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Title'),
            m('input', {
                type: 'text',
                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                value: scene.title,
                oninput: function (e) { scene.title = e.target.value; m.redraw(); },
                onblur: function (e) { doPatchSceneField(idx, 'title', e.target.value); }
            })
        ]),
        // Stanza
        m('div', [
            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Stanza text'),
            m('textarea', {
                rows: 6,
                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white font-mono resize-y',
                value: scene.poemStanza,
                oninput: function (e) { scene.poemStanza = e.target.value; m.redraw(); },
                onblur: function (e) { doPatchSceneField(idx, 'poemStanza', e.target.value); }
            })
        ]),
        // Style controls + actions
        m('div', { class: 'flex flex-wrap items-center gap-3' }, [
            // Font
            m('div', { class: 'flex items-center gap-1.5' }, [
                m('label', { class: 'text-xs text-gray-500 dark:text-gray-400' }, 'Font'),
                m('select', {
                    class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-xs dark:text-white',
                    value: scene.pageFont,
                    onchange: function (e) {
                        let v = e.target.value;
                        scene.pageFont = v;
                        doPatchSceneField(idx, 'pageFont', v);
                    }
                }, FONT_OPTIONS.map(function (opt) {
                    return m('option', { value: opt.value }, opt.label);
                }))
            ]),
            // Background color
            m('div', { class: 'flex items-center gap-1.5' }, [
                m('label', { class: 'text-xs text-gray-500 dark:text-gray-400' }, 'Bg color'),
                m('input', {
                    type: 'color',
                    class: 'w-8 h-7 rounded border border-gray-300 dark:border-gray-600 cursor-pointer',
                    value: scene.pageBgColor || '#000000',
                    onchange: function (e) {
                        let v = e.target.value;
                        scene.pageBgColor = v;
                        doPatchSceneField(idx, 'pageBgColor', v);
                    }
                })
            ]),
            // Text alignment
            m('div', { class: 'flex items-center gap-1' }, [
                m('span', { class: 'text-xs text-gray-500 dark:text-gray-400 mr-1' }, 'Align'),
                alignOptions.map(function (align) {
                    let active = scene.pageTextAlign === align;
                    return m('button', {
                        key: align,
                        class: 'px-1.5 py-1 rounded text-xs ' + (active
                            ? 'bg-purple-600 text-white'
                            : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600'),
                        title: align.charAt(0).toUpperCase() + align.slice(1),
                        onclick: function () {
                            let newVal = active ? '' : align;
                            scene.pageTextAlign = newVal;
                            doPatchSceneField(idx, 'pageTextAlign', newVal);
                        }
                    }, m('span', {
                        class: 'material-symbols-outlined',
                        style: 'font-size:14px;vertical-align:middle'
                    }, alignIcons[align]));
                })
            ]),
            // Split / Merge buttons
            m('div', { class: 'ml-auto flex items-center gap-2' }, [
                m('button', {
                    class: 'px-2 py-1 rounded bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 text-xs hover:bg-blue-200 disabled:opacity-40 flex items-center gap-1',
                    title: 'Split stanza at midpoint into two pages',
                    disabled: scene._saving || roleWarning || (scene.poemStanza || '').split('\n').filter(function (l) { return l.trim(); }).length < 2,
                    onclick: function () { doSplitScene(idx); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'call_split'),
                    ' Split'
                ]),
                m('button', {
                    class: 'px-2 py-1 rounded bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300 text-xs hover:bg-amber-200 disabled:opacity-40 flex items-center gap-1',
                    title: 'Merge this page with the next',
                    disabled: scene._saving || roleWarning || isLast,
                    onclick: function () { doMergeScene(idx); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'merge'),
                    ' Merge'
                ]),
                m('button', {
                    class: 'px-2 py-1 rounded bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 text-xs hover:bg-red-200 disabled:opacity-40 flex items-center gap-1',
                    title: 'Remove this page',
                    disabled: scene._saving || roleWarning,
                    onclick: function () { doDeleteScene(idx); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'delete'),
                    ' Remove'
                ])
            ])
        ]),
        // Editable landscape prompt — the LLM-generated SD prompt for this page, stored on the scene at
        // book-create time. Surfaced here (pre-filled from scene.sdPrompt) so every landscape prompt can
        // be reviewed / edited / overridden BEFORE rendering, instead of having to render first to find
        // out what it was. "Save prompt" persists it verbatim (blank clears it); "Re-render this page"
        // renders JUST this page using the current text — no need to re-render the whole book.
        m('div', { class: 'text-xs border-t border-gray-100 dark:border-gray-800 pt-2', 'data-scene-oid': oid }, [
            m('div', { class: 'flex items-center justify-between mb-1' }, [
                m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400' }, 'Landscape prompt'),
                reviewSceneImageUrls[oid] ? m('span', { class: 'text-xs text-green-600 dark:text-green-400' }, 'image ready') : null
            ]),
            m('textarea', {
                class: 'cb-scene-prompt w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-xs dark:text-white font-mono resize-y',
                'data-scene-oid': oid,
                rows: 3,
                placeholder: 'Landscape image prompt — edit to override before rendering. Leave blank to let the LLM generate one.',
                value: scene.sdPrompt || '',
                disabled: scene._saving || roleWarning,
                oninput: function (e) { scene.sdPrompt = e.target.value; m.redraw(); }
            }),
            reviewSceneImageUrls[oid] ? m('img', {
                class: 'cb-scene-image mt-2 rounded border border-gray-200 dark:border-gray-700 max-h-40 object-cover',
                'data-scene-oid': oid,
                src: reviewSceneImageUrls[oid],
                alt: 'Page ' + (idx + 1) + ' image'
            }) : null,
            m('div', { class: 'flex items-center gap-2 mt-1' }, [
                m('button', {
                    class: 'cb-save-prompt px-2 py-0.5 rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-40',
                    'data-scene-oid': oid,
                    title: 'Save this page\'s landscape prompt (blank clears it)',
                    disabled: scene._saving || roleWarning,
                    onclick: function () { doSaveSceneLandscapePrompt(idx); }
                }, 'Save prompt'),
                m('button', {
                    class: 'cb-rerender-page px-2 py-0.5 rounded bg-orange-600 text-white hover:bg-orange-700 disabled:opacity-40 flex items-center gap-1',
                    'data-scene-oid': oid,
                    title: 'Re-render just this page using the prompt above — no need to re-render the whole book',
                    disabled: scene._saving || roleWarning,
                    onclick: function () { doRerenderScene(idx); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'refresh'),
                    ' Re-render this page'
                ])
            ])
        ]),
        // Per-scene SD-config overrides (Gap 8) — reuses forms.sdConfigOverrides via the generic
        // object view, exactly like PB2 and CardGame. Collapsed by default; the heavy form is mounted
        // lazily only while expanded. Save persists the sparse delta; Clear removes the override.
        m('div', { class: 'text-xs border-t border-gray-100 dark:border-gray-800 pt-2' }, [
            m('div', { class: 'flex items-center justify-between' }, [
                m('button', {
                    class: 'flex items-center gap-1 cursor-pointer text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
                    onclick: function () { sceneOverrideExpanded[oid] = !overrideOpen; m.redraw(); }
                }, [
                    m('span', {
                        class: 'material-symbols-outlined',
                        style: 'font-size:16px;transition:transform 0.15s;' + (overrideOpen ? 'transform:rotate(90deg);' : '')
                    }, 'chevron_right'),
                    m('span', 'Image config overrides'),
                    overridden ? m('span', {
                        class: 'ml-1 px-1.5 rounded bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300'
                    }, 'set') : null
                ]),
                m('div', { class: 'flex items-center gap-2' }, [
                    m('button', {
                        class: 'px-2 py-0.5 rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-40',
                        title: 'Save this page\'s image-config overrides',
                        disabled: scene._saving || roleWarning,
                        onclick: function () { doSaveSceneOverride(idx); }
                    }, 'Save'),
                    overridden ? m('button', {
                        class: 'px-2 py-0.5 rounded bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600 disabled:opacity-40',
                        title: 'Clear this page\'s overrides (revert to the book config)',
                        disabled: scene._saving || roleWarning,
                        onclick: function () { doClearSceneOverride(idx); }
                    }, 'Clear') : null
                ])
            ]),
            overrideOpen ? (function () {
                let ovInst = getSceneOverrideInst(scene);
                let ovView = sceneOverrideViews[oid];
                if (!ovView || !ovView.view) {
                    return m('div', { class: 'mt-1 text-gray-400' }, 'Config editor unavailable.');
                }
                return m('div', { class: 'mt-2' }, m(ovView.view, {
                    freeForm: true,
                    freeFormType: 'olio.sd.config',
                    freeFormEntity: ovInst.entity,
                    freeFormInstance: ovInst
                }));
            })() : null
        ])
    ]);
}

const ChapBookReview = {
    oninit: function (vnode) {
        reviewBookObjectId = vnode.attrs.bookObjectId || null;
        reviewBook = null;
        reviewScenes = [];
        reviewLoading = false;
        reviewError = null;
        reviewRendering = false;
        reviewGroupId = null;
        // Gap 8: reset per-scene override caches so a re-entered review starts fresh
        sceneOverrideInsts = {};
        sceneOverrideViews = {};
        sceneOverrideExpanded = {};
        // Landscape-prompt review: reset per-scene preview URLs so a re-entered review starts fresh
        reviewSceneImageUrls = {};
        // Issue 8: reset render dialog state so the SD config modal starts fresh
        showRenderDialog = false;
        pendingRenderBookId = null;
        pendingRenderCallback = null;
        renderSdCfg = {};
        // Gap 6: clear any per-scene render progress from a prior book/session
        resetRenderProgress();
        // Issue 9 / D6: block scene edits + render when the AccountUsers role is absent
        roleWarning = lacksUserRole(page.context && page.context());
        if (reviewBookObjectId) loadReviewBook(reviewBookObjectId);
    },
    view: function () {
        let title = (reviewBook && (reviewBook.name || reviewBook.slug)) || 'ChapBook';
        return m('div', { class: 'p-4 max-w-3xl mx-auto' }, [
            // Issue 9 / D6: role warning banner (mirrors PoemLibrary / ChapBookReader)
            roleWarning ? m('div', { class: 'mb-4 p-3 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-sm text-yellow-800 dark:text-yellow-200 flex items-center gap-2' }, [
                m('span', { class: 'material-symbols-outlined text-yellow-500' }, 'warning'),
                'You need the AccountUsers role to use ChapBook features.'
            ]) : null,
            // Header
            m('div', { class: 'flex flex-wrap items-center gap-3 mb-4' }, [
                m('button', {
                    class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
                    title: 'Back to Poem Library',
                    onclick: function () { m.route.set('/chap-book'); }
                }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
                m('span', { class: 'material-symbols-outlined text-2xl text-purple-500' }, 'edit_note'),
                m('h2', { class: 'flex-1 text-xl font-semibold dark:text-white truncate', title: title },
                    title + ' — Review'),
                m('button', {
                    class: 'px-3 py-1.5 rounded bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 text-sm hover:bg-purple-200 flex items-center gap-1',
                    onclick: function () { m.route.set('/chap-book/read/' + reviewBookObjectId); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'auto_stories'),
                    ' Read'
                ]),
                m('button', {
                    class: 'px-3 py-1.5 rounded bg-orange-600 text-white text-sm hover:bg-orange-700 flex items-center gap-1 disabled:opacity-50',
                    disabled: reviewRendering || reviewLoading || roleWarning,
                    onclick: renderReviewBook
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' },
                        reviewRendering ? 'hourglass_empty' : 'image'),
                    reviewRendering ? (' ' + renderProgressLabel()) : ' Render'
                ])
            ]),
            m('p', { class: 'text-xs text-gray-400 dark:text-gray-500 mb-4' },
                'Changes auto-save when you leave a field (blur). Use Render to generate images. Remove deletes a page permanently.'),
            // Body
            reviewLoading
                ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-12 text-center' }, 'Loading scenes...')
                : reviewError
                    ? m('div', { class: 'text-sm text-red-500 py-12 text-center' }, 'Error: ' + reviewError)
                    : reviewScenes.length === 0
                        ? m('div', { class: 'text-center py-12' }, [
                            m('span', { class: 'material-symbols-outlined text-5xl text-gray-300 block mb-2' }, 'auto_stories'),
                            m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' },
                                'No pages yet. Create a ChapBook from the Poem Library first.')
                          ])
                        : m('div', { class: 'space-y-4' },
                            reviewScenes.map(function (scene, idx) {
                                return renderSceneCard(scene, idx);
                            })
                          ),
            // Issue 8: pre-render SD config dialog (same as PoemLibrary and ChapBookReader)
            renderRenderDialog()
        ]);
    }
};

// ── ChapBookFeature — top-level route component ───────────────────────

const ChapBookFeature = {
    view: function () {
        return m(PoemLibrary);
    }
};

// ── Routes ────────────────────────────────────────────────────────────

export const routes = {
    '/chap-book': {
        view: function () {
            return layout(pageLayout(m(ChapBookFeature)));
        }
    },
    '/chap-book/read/:bookObjectId': {
        view: function (vnode) {
            return layout(pageLayout(m(ChapBookReader, { bookObjectId: vnode.attrs.bookObjectId })));
        }
    },
    '/chap-book/review/:bookObjectId': {
        view: function (vnode) {
            return layout(pageLayout(m(ChapBookReview, { bookObjectId: vnode.attrs.bookObjectId })));
        }
    }
};

export { renderChapBookPage, ChapBookFeature, ChapBookReader, ChapBookReview, PoemLibrary, openRenderConfigDialog, renderRenderDialog, lacksUserRole, persistReaderPoemIds, loadPersistedReaderPoemIds, renderScenesSerially, renderChapBookScene, renderChapBookScenes, renderResultMessage, renderResultLevel, sceneLlmSignal, isSceneUnprompted, doDeleteBook, createChapBook, analyzePoem };
export default ChapBookFeature;
