/**
 * PictureBook V3 composition board (design §26): one scene's composition — camera, actor rows with the
 * resolved outfit chip and wardrobe picker, element rows with "Promote to item", a neutral canvas with the
 * placement boxes, the prompt override + lock, the sparse image-config override, Re-compose and the book-level
 * compose job with live progress — beside the compiled render plan (PbPlanInspector) for the same scene.
 *
 * Every edit is a row-level PATCH against the P4-8 routes (pictureBookWorkflow boardDelta → patchComposition /
 * patchActor / patchElement); the client composes no prompt and infers nothing about what the server dropped.
 *
 * Every compose (empty-state compose, Re-compose, Compose book…) sends the header's "Chat Config" pick by NAME
 * as chatConfigName, as the ChapBook render dialog and the PictureBook wizard do: a compose without one falls
 * to the organization's generalChat, which on the Docker stack has no LLM key. The default is resolved
 * library-only (contentAnalysis, then generalChat) and a user pick always wins; the pick is per book. A
 * ChapBook opens with the config its own screens carry (carriedBookChatConfig, P9-8 240 d) before that default.
 *
 * Cover mode (V3 §40, P9-4): the same board over a cover-type composition keyed by purpose instead of scene
 * (COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART). Every renderer below is composition-keyed, so the only
 * differences are the purpose tabs in place of the scene stepper, the cover-keyed GET / PATCH / plan routes,
 * Create (auto / from a scene / manual) in place of Compose, the title-safe band on the camera section, and a
 * cover-art strip (render state, the approved and latest render, Render / Re-render / Approve) above the board.
 *
 * Routes:
 *   /picture-book/:bookObjectId/compose                  — the board on the book's first page
 *   /picture-book/:bookObjectId/compose/:sceneObjectId   — the board on one scene
 *   /picture-book/:bookObjectId/cover                    — cover mode on the COVER purpose
 *   /picture-book/:bookObjectId/cover/:purpose           — cover mode on one purpose (cover, title-page, …)
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import am7client from '../core/am7client.js';
import am7model from '../core/model.js';
import am7sd from '../components/sdConfig.js';
import { layout, pageLayout } from '../router.js';
import { Dialog } from '../components/dialogCore.js';
import { ObjectPicker } from '../components/picker.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';
import { PbPlanInspector } from '../components/PbPlanInspector.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { applicationPath } from '../core/config.js';
import { pollJob, listCharacters } from '../workflows/sceneExtractor.js';
import {
    COMPOSITION_ENUMS, enumOptions, boardDelta, getSceneComposition, patchComposition,
    addActor, patchActor, deleteActor, addElement, patchElement, deleteElement, promoteElement,
    pinActorApparel, getCompositionOutfits, outfitChipText, getWardrobe, isNoCompositionYet,
    composeBook, composeScene, normalizeComposeJobResult, isComposeJobRefusal,
    bookPages, planScene, normalizePlan, degradationSummary,
    COVER_PURPOSES, coverPurposeOf, coverPurposeLabel, coverPathWord, getCovers, getCover, createCover,
    renderCover, patchCover, planCover, approveArtifact, isPinnedNodeRefusal, carriedBookChatConfig
} from '../workflows/pictureBookWorkflow.js';

// ── Module state ───────────────────────────────────────────────────────

let bookObjectId = null;
let sceneObjectId = null;
let pages = [];
let board = null;
let noComposition = false;
let noCompositionMessage = null;
let loading = false;
let error = null;
let loadSeq = 0;
let outfits = {};
let outfitsError = null;
let plan = null;
let planError = null;
let planLoading = false;
let busy = {};
let edits = { composition: null, actors: {}, elements: {} };
let lastEdit = null;
let characters = [];
let composeRun = null;
let composeAbort = null;
let drag = null;
let overrideInst = null;
let overrideExpanded = false;
let sdModels = [];
let sdLoras = [];
let pollIntervalMs = 2000;
let chatConfigRef = null;
let chatConfigResolving = false;
// Cover mode (V3 §40): null = scene mode; else the purpose the board is keyed by ('COVER', 'TITLE_PAGE', …).
let coverPurpose = null;
// The covers-list entry of the current purpose (render state + approved/latest refs), from getCover.
let cover = null;
// getCovers(book).covers — which purposes exist, for the purpose tabs; null until loaded.
let covers = null;
// The cover render job being watched: {jobId, purpose, status, progress, message, done, error, result}.
let renderRun = null;
let renderAbort = null;

// ── Pure helpers (exported for Vitest) ─────────────────────────────────

/** Library names tried, in order, for the board's default chat config (the ChapBook editor's order). */
export const DEFAULT_CHAT_CONFIG_NAMES = ['contentAnalysis', 'generalChat'];

/** Pure: compose request options with the picked chat config NAME added; nothing is added without a pick. */
export function withChatConfig(opts, ref) {
    let o = Object.assign({}, opts || {});
    if (ref && typeof ref.name === 'string' && ref.name.trim()) o.chatConfigName = ref.name.trim();
    return o;
}

/** Pure: the "Chat Config" control's label. */
export function chatConfigLabel(ref, resolving) {
    if (ref && ref.name) return ref.name;
    return resolving ? 'Resolving default…' : '(click to select)';
}

export const COMPOSITION_KEYS = ['settingText', 'action', 'mood', 'shotType', 'viewpoint', 'cameraText', 'props', 'focalActorObjectId'];
/** Cover mode saves the camera keys plus the title-safe band (PATCH /{book}/covers/{purpose} accepts it; the scene PATCH 400s it). */
export const COVER_COMPOSITION_KEYS = COMPOSITION_KEYS.concat(['titleSafeBand']);
export const PROMPT_KEYS = ['promptOverride', 'promptLocked'];

/**
 * Pure: the MediaServlet URL of a cover image ref ({imageGroupPath, imageName} - the page-view shape, the same route
 * pictureBook.js pb2ImageUrl / chapBook.js chapImageUrl build); null without both path fields.
 */
export function coverImageUrl(ref) {
    if (!ref || !ref.imageGroupPath || !ref.imageName) return null;
    return applicationPath + '/media/' + am7client.dotPath(am7client.currentOrganization)
        + '/data.data' + ref.imageGroupPath + '/' + ref.imageName;
}

/** Pure: true when a cover entry's lastError says the composition has nothing to render from (an empty MANUAL cover). */
export function isNothingToRenderError(entry) {
    return !!(entry && entry.lastError && /no description to render from/i.test(String(entry.lastError)));
}

/**
 * Pure: a node status that means "a render finished": DONE, or the DONE_UNVERIFIED every RENDER_PLAN / scene
 * executor closes with (verification is the review step, not the render) — the chapBook reader's rule.
 */
export function isRenderedStatus(status) {
    let s = String(status || '').toUpperCase();
    return s === 'DONE' || s === 'DONE_UNVERIFIED';
}

/**
 * Pure: the render-state line of the cover strip from a normalized cover entry: "Not rendered yet" without a node,
 * else the node status with the chain length and the selected artifact's review status.
 */
export function coverStateText(entry) {
    if (!entry) return 'No composition';
    if (!entry.nodeObjectId) return 'Not rendered yet';
    let bits = [String(entry.nodeStatus || 'UNKNOWN')];
    if (entry.pinned) bits.push('pinned');
    bits.push(entry.chainLength === 1 ? '1 render' : (entry.chainLength || 0) + ' renders');
    if (entry.reviewStatus) bits.push(String(entry.reviewStatus).toLowerCase());
    return bits.join(' · ');
}

/** Pure: the live counter for a cover render job; the summary line once terminal. */
export function renderCounterText(job) {
    if (!job) return 'Submitting…';
    if (job.terminal) {
        if (job.cancelled) return 'Cancelled';
        if (job.error) return 'Failed: ' + job.error;
        return 'Rendered';
    }
    let cur = job.current != null ? job.current : 0;
    let tot = job.total != null ? job.total : 0;
    return (tot ? cur + ' of ' + tot : 'Rendering') + (job.phase ? ' · ' + job.phase : '');
}

/** Pure: the toast for a createCover result; `label` is the purpose label. */
export function createCoverText(res, label) {
    let l = label || 'Cover';
    if (!res) return l + ' created';
    if (res.existingReturned) return l + ' already exists - showing it';
    if (!res.written) return l + ' was not created' + (res.failureReason ? ': ' + res.failureReason : '');
    let s = l + ' created';
    let mode = String(res.mode || '').toLowerCase();
    if (mode === 'auto') {
        let actors = Array.isArray(res.actors) ? res.actors.length : 0;
        let elements = Array.isArray(res.elements) ? res.elements.length : 0;
        s += ' (' + actors + ' actors, ' + elements + ' elements' + (res.castTier ? ', cast ' + String(res.castTier).toLowerCase() : '') + ')';
    } else if (mode === 'from_scene') {
        s += ' from the scene';
    } else if (mode === 'manual') {
        s += ' - fill in the setting and camera, then Render';
    }
    let warnings = Array.isArray(res.warnings) ? res.warnings : [];
    if (warnings.length) s += ' — ' + warnings.join('; ');
    return s;
}
export const ACTOR_KEYS = ['role', 'priority', 'pose', 'expression', 'action', 'facing', 'x', 'y', 'w', 'h', 'depth', 'description', 'count', 'referencePolicy'];
export const ELEMENT_KEYS = ['elementType', 'description', 'x', 'y', 'w', 'h', 'depth', 'importance'];

export const USER_EDITED_WARNING = 'This composition was edited by hand. Re-composing overwrites every actor, element and camera field with what the LLM proposes; pinned outfits and the prompt override are kept only if the server keeps them. Continue?';

/** Pure: the canvas aspect (width / height) from a normalized plan's canvas, 4:3 when absent or degenerate. */
export function canvasAspect(p) {
    let c = p && p.canvas ? p.canvas : null;
    let w = c && c.width != null ? Number(c.width) : 0;
    let h = c && c.height != null ? Number(c.height) : 0;
    if (!(w > 0) || !(h > 0)) return 4 / 3;
    return w / h;
}

function pct(v) {
    let n = Number(v);
    if (!isFinite(n)) n = 0;
    if (n < 0) n = 0;
    if (n > 1) n = 1;
    return (n * 100).toFixed(2) + '%';
}

/** Pure: absolute percent box style for one actor/element row (x, y, w, h in 0..1; depth is the model's int layer order, 0 = front-most, so z = 1000 - depth). */
export function boxStyle(row) {
    let r = row || {};
    let depth = Math.round(Number(r.depth));
    if (!isFinite(depth) || depth < 0) depth = 0;
    let w = Number(r.w); if (!isFinite(w) || w <= 0) w = 0.1;
    let h = Number(r.h); if (!isFinite(h) || h <= 0) h = 0.1;
    return {
        position: 'absolute',
        left: pct(r.x),
        top: pct(r.y),
        width: pct(w),
        height: pct(h),
        zIndex: String(Math.max(0, 1000 - depth))
    };
}

/** Pure: a drag by (dx, dy) in canvas fractions → {x, y} clamped so the box stays inside 0..1, rounded to 3 dp. */
export function dragMove(orig, dx, dy) {
    let o = orig || {};
    let w = Number(o.w); if (!isFinite(w) || w < 0) w = 0;
    let h = Number(o.h); if (!isFinite(h) || h < 0) h = 0;
    let x = Number(o.x); if (!isFinite(x)) x = 0;
    let y = Number(o.y); if (!isFinite(y)) y = 0;
    let nx = x + (isFinite(Number(dx)) ? Number(dx) : 0);
    let ny = y + (isFinite(Number(dy)) ? Number(dy) : 0);
    let maxX = Math.max(0, 1 - w);
    let maxY = Math.max(0, 1 - h);
    if (nx < 0) nx = 0; if (nx > maxX) nx = maxX;
    if (ny < 0) ny = 0; if (ny > maxY) ny = maxY;
    return { x: Math.round(nx * 1000) / 1000, y: Math.round(ny * 1000) / 1000 };
}

/** Pure: the scene selector label for a /pages DTO. */
export function sceneLabel(pg) {
    if (!pg) return '';
    let idx = pg.sceneIndex != null ? String(pg.sceneIndex) : '?';
    let title = pg.title ? String(pg.title) : '';
    return idx + (title ? ' · ' + title : '');
}

/** Pure: the live counter for a running compose job ("3 of 12 · scene 3"); the summary line once terminal. */
export function composeCounterText(job) {
    if (!job) return 'Submitting…';
    if (job.terminal) {
        if (job.cancelled) return 'Cancelled';
        if (job.error) return 'Failed: ' + job.error;
        let r = job.result && typeof job.result === 'object' ? job.result : null;
        if (!r) return 'Done';
        let bits = ['composed ' + (r.composed != null ? r.composed : 0)];
        if (r.current) bits.push('current ' + r.current);
        if (r.skippedUserEdited) bits.push('kept ' + r.skippedUserEdited + ' hand-edited');
        let migrated = (r.migrated || 0) + (r.migration && r.migration.created ? r.migration.created : 0);
        if (migrated) bits.push('migrated ' + migrated);
        if (r.hardFailures) bits.push('failed ' + r.hardFailures);
        if (r.staled) bits.push('renders staled ' + r.staled);
        if (r.pagesSkipped) bits.push('pages skipped ' + r.pagesSkipped);
        if (r.quickStartRendered) bits.push('drafts rendered ' + r.quickStartRendered);
        if (r.quickStartFailed) bits.push('drafts failed ' + r.quickStartFailed);
        return 'Done: ' + bits.join(', ') + (r.interrupted ? ' (interrupted)' : '');
    }
    let cur = job.current != null ? job.current : 0;
    let tot = job.total != null ? job.total : 0;
    return cur + ' of ' + tot + (job.phase ? ' · ' + job.phase : '');
}

/** Pure: the toast for one per-scene compose result; `verb` is "composed" or "re-composed"; counts only when `withCounts`. */
export function composeSceneText(res, verb, withCounts) {
    let v = verb || 'composed';
    if (!res) return 'Scene ' + v;
    if (!res.written) return 'Scene was not ' + v + (res.failureReason ? ': ' + res.failureReason : '');
    let s = 'Scene ' + v;
    if (withCounts) {
        let actors = Array.isArray(res.actors) ? res.actors.length : 0;
        let elements = Array.isArray(res.elements) ? res.elements.length : 0;
        s += ' (' + (res.source || 'llm') + ', ' + actors + ' actors, ' + elements + ' elements)';
    }
    let notes = [];
    if (res.migratedFirst) notes.push(res.migratedFallback ? 'legacy prompt migrated; the LLM compose did not land, so it stands' : 'legacy prompt migrated first');
    if (res.nodeStale) notes.push('render marked stale');
    if (notes.length) s += ' — ' + notes.join('; ');
    let warnings = Array.isArray(res.warnings) ? res.warnings : [];
    if (warnings.length) s += ' — ' + warnings.join('; ');
    return s;
}

/** Pure: a composition's configOverride text → the sparse olio.sd.config object it carries, null when blank or invalid. */
export function parseConfigOverride(s) {
    if (s == null) return null;
    let t = String(s).trim();
    if (!t) return null;
    try {
        let o = JSON.parse(t);
        return o && typeof o === 'object' && !Array.isArray(o) ? o : null;
    } catch (e) {
        return null;
    }
}

const SD_CONFIG_IDENTITY = ['id', 'objectId', 'urn', 'ownerId', 'groupId', 'organizationId', 'groupPath', 'organizationPath', 'narration'];

/**
 * Pure: the sparse delta to send as configOverride — the previously stored override keys merged with every key the
 * SdConfigPanel instance changed (values read from inst.entity). null when nothing is set.
 */
export function computeOverrideDelta(prevText, inst) {
    let prev = parseConfigOverride(prevText) || {};
    let delta = {};
    Object.keys(prev).forEach(function (k) {
        if (k === am7model.jsonModelKey || SD_CONFIG_IDENTITY.indexOf(k) >= 0) return;
        delta[k] = prev[k];
    });
    let changes = inst && inst.changes ? inst.changes : [];
    let entity = inst && inst.entity ? inst.entity : {};
    (Array.isArray(changes) ? changes : Object.keys(changes)).forEach(function (k) {
        if (k === am7model.jsonModelKey || SD_CONFIG_IDENTITY.indexOf(k) >= 0) return;
        let v = entity[k];
        if (v === undefined || v === null || v === '') { delete delta[k]; return; }
        delta[k] = v;
    });
    if (!Object.keys(delta).length) return null;
    delta[am7model.jsonModelKey] = 'olio.sd.config';
    return delta;
}

function overrideValues(o) {
    let r = {};
    Object.keys(o || {}).forEach(function (k) {
        if (k === am7model.jsonModelKey || SD_CONFIG_IDENTITY.indexOf(k) >= 0) return;
        r[k] = o[k];
    });
    return r;
}

/** Pure: true when the delta computeOverrideDelta produced carries exactly what prevText already stores (a Save would write nothing new). */
export function overrideUnchanged(prevText, delta) {
    let prev = overrideValues(parseConfigOverride(prevText));
    let next = overrideValues(delta);
    let pk = Object.keys(prev).sort();
    let nk = Object.keys(next).sort();
    if (pk.length !== nk.length) return false;
    for (let i = 0; i < pk.length; i++) {
        if (pk[i] !== nk[i] || JSON.stringify(prev[pk[i]]) !== JSON.stringify(next[nk[i]])) return false;
    }
    return true;
}

/** Pure: the user-edited warning text when a re-compose would overwrite a hand-edited composition, else null. */
export function userEditedWarning(composition) {
    return composition && composition.userEdited ? USER_EDITED_WARNING : null;
}

/**
 * Pure: the headline prompt of a normalized plan — the first compiled stage with a non-blank prompt
 * (`compiled` is a stage map on the wire: {stage: {prompt, negative, estimatedTokens}}, CompiledPrompt.toMap);
 * a flat {prompt, negative} is tolerated. → {stage, prompt, negative} or null when nothing was compiled.
 */
export function planHeadlinePrompt(p) {
    let c = p && p.compiled && typeof p.compiled === 'object' ? p.compiled : null;
    if (!c) return null;
    if (typeof c.prompt === 'string' && c.prompt.trim()) {
        return { stage: null, prompt: c.prompt, negative: typeof c.negative === 'string' ? c.negative : '' };
    }
    let keys = Object.keys(c);
    for (let i = 0; i < keys.length; i++) {
        let s = c[keys[i]];
        if (s && typeof s === 'object' && typeof s.prompt === 'string' && s.prompt.trim()) {
            return { stage: keys[i], prompt: s.prompt, negative: typeof s.negative === 'string' ? s.negative : '' };
        }
    }
    return null;
}

// ── Enum options (memoized; am7model guarded, COMPOSITION_ENUMS fallback) ──

let enumCache = {};

function modelLimit(model, field) {
    let md = am7model.getModel(model);
    if (!md) return null;
    let f = am7model.getModelField(md, field);
    return f && Array.isArray(f.limit) ? f.limit : null;
}

function options(model, field, key) {
    if (!enumCache[key]) enumCache[key] = enumOptions(modelLimit(model, field), COMPOSITION_ENUMS[key]);
    return enumCache[key];
}

const shotTypeOptions = () => options('olio.pb.composition', 'shotType', 'shotType');
const viewpointOptions = () => options('olio.pb.composition', 'viewpoint', 'viewpoint');
const roleOptions = () => options('olio.pb.sceneActor', 'role', 'role');
const facingOptions = () => options('olio.pb.sceneActor', 'facing', 'facing');
const policyOptions = () => options('olio.pb.sceneActor', 'referencePolicy', 'referencePolicy');
const elementTypeOptions = () => options('olio.pb.sceneElement', 'elementType', 'elementType');
const importanceOptions = () => options('olio.pb.sceneElement', 'importance', 'importance');
const titleBandOptions = () => options('olio.pb.composition', 'titleSafeBand', 'titleSafeBand');

// ── Loading ────────────────────────────────────────────────────────────

function currentPage() {
    for (let i = 0; i < pages.length; i++) if (pages[i].objectId === sceneObjectId) return pages[i];
    return null;
}

function isChapBookPage() {
    return !!(board && board.composition && board.composition.purpose === 'chapbook_page');
}

function isCoverMode() {
    return coverPurpose != null;
}

/** The board's key: the purpose in cover mode, the scene otherwise; null when there is nothing to load yet. */
function boardKey() {
    return isCoverMode() ? coverPurpose : sceneObjectId;
}

async function loadPlan() {
    if (!bookObjectId || !boardKey()) return;
    let seq = loadSeq;
    planLoading = true;
    planError = null;
    m.redraw();
    try {
        let p = await (isCoverMode() ? planCover(bookObjectId, coverPurpose, {}) : planScene(bookObjectId, sceneObjectId, {}));
        if (seq !== loadSeq) return;
        plan = normalizePlan(p);
    } catch (e) {
        if (seq !== loadSeq) return;
        plan = null;
        planError = (e && e.message) || 'The render plan could not be compiled';
    }
    planLoading = false;
    m.redraw();
}

/** Cover mode: the covers list for the purpose tabs (find-only on the server). A failure leaves the tabs showing every purpose. */
async function loadCovers() {
    if (!bookObjectId) return;
    let seq = loadSeq;
    try {
        let v = await getCovers(bookObjectId);
        if (seq !== loadSeq) return;
        covers = v.covers;
    } catch (e) {
        if (seq !== loadSeq) return;
        covers = null;
    }
}

async function loadOutfits() {
    outfitsError = null;
    if (!board || !board.compositionObjectId) { outfits = {}; return; }
    let seq = loadSeq;
    try {
        let r = await getCompositionOutfits(bookObjectId, board.compositionObjectId);
        if (seq !== loadSeq) return;
        let map = {};
        r.actors.forEach(function (o) { if (o.actorObjectId) map[o.actorObjectId] = o; });
        outfits = map;
    } catch (e) {
        if (seq !== loadSeq) return;
        outfits = {};
        outfitsError = (e && e.message) || 'The outfits could not be read';
    }
}

async function loadBoard(keepEdits) {
    if (!bookObjectId || !boardKey()) return;
    let seq = ++loadSeq;
    loading = true;
    error = null;
    m.redraw();
    try {
        let b = await (isCoverMode() ? getCover(bookObjectId, coverPurpose) : getSceneComposition(bookObjectId, sceneObjectId));
        if (seq !== loadSeq) return;
        board = b;
        cover = isCoverMode() ? (b.cover || null) : null;
        noComposition = false;
        noCompositionMessage = null;
        if (!keepEdits) edits = { composition: null, actors: {}, elements: {} };
        await loadOutfits();
        if (seq !== loadSeq) return;
    } catch (e) {
        if (seq !== loadSeq) return;
        board = null;
        cover = null;
        outfits = {};
        edits = { composition: null, actors: {}, elements: {} };
        if (isNoCompositionYet(e)) {
            noComposition = true;
            noCompositionMessage = (e && e.message) || (isCoverMode() ? 'This book has no ' + coverPurposeLabel(coverPurpose).toLowerCase() + ' composition yet' : 'This scene has no composition yet');
        } else {
            noComposition = false;
            error = (e && e.message) || 'The composition could not be loaded';
        }
    }
    loading = false;
    m.redraw();
    if (isCoverMode()) loadCovers().then(function () { m.redraw(); });
    loadPlan();
}

async function loadPages() {
    if (!bookObjectId) return;
    let seq = loadSeq;
    try {
        let list = await bookPages(bookObjectId);
        if (seq !== loadSeq) return;
        pages = Array.isArray(list) ? list : [];
        if (!isCoverMode() && !sceneObjectId && pages.length) sceneObjectId = pages[0].objectId;
    } catch (e) {
        if (seq !== loadSeq) return;
        pages = [];
        error = (e && e.message) || 'The book pages could not be loaded';
        m.redraw();
    }
}

// keepEdits: a refused write saved nothing, so the user's drafts (and the open config panel) survive the re-read.
async function refetch(keepEdits) {
    am7client.clearCache('olio.pb.artifact', true);
    am7client.clearCache('olio.pb.node', true);
    am7client.clearCache('olio.pb.scene', true);
    if (!keepEdits) overrideInst = null;
    await loadBoard(keepEdits);
}

async function act(key, fn, successMsg) {
    if (busy[key]) return null;
    busy[key] = true;
    m.redraw();
    let ok = false;
    let out = null;
    try {
        out = await fn();
        ok = true;
        if (successMsg) page.toast('success', typeof successMsg === 'function' ? successMsg(out) : successMsg);
    } catch (e) {
        page.toast('error', (e && e.message) || 'The request failed');
    }
    delete busy[key];
    m.redraw();
    await refetch(!ok);
    return ok ? (out == null ? true : out) : null;
}

function noteEdit(res) {
    if (!res) return;
    lastEdit = { revision: res.revision, nodeStale: res.nodeStale, nodeStatus: res.nodeStatus };
}

// ── Drafts ─────────────────────────────────────────────────────────────

function compositionDraft() {
    if (!board) return {};
    if (!edits.composition) edits.composition = Object.assign({}, board.composition);
    return edits.composition;
}

function actorDraft(a) {
    if (!edits.actors[a.objectId]) edits.actors[a.objectId] = Object.assign({}, a);
    return edits.actors[a.objectId];
}

function elementDraft(el) {
    if (!edits.elements[el.objectId]) edits.elements[el.objectId] = Object.assign({}, el);
    return edits.elements[el.objectId];
}

function replaceRow(list, row) {
    if (!row || !row.objectId) return;
    for (let i = 0; i < list.length; i++) {
        if (list[i].objectId === row.objectId) { list[i] = row; return; }
    }
}

// ── Writes (one row-level PATCH each) ──────────────────────────────────

/** The composition-level PATCH of the current board: cover-keyed in cover mode, scene-keyed otherwise. */
function patchCurrent(delta) {
    return isCoverMode() ? patchCover(bookObjectId, coverPurpose, delta) : patchComposition(bookObjectId, sceneObjectId, delta);
}

async function saveCompositionKeys(keys, busyKey, successMsg) {
    if (!board || busy[busyKey]) return;
    let delta = boardDelta(board.composition, edits.composition, keys);
    if (!Object.keys(delta).length) { page.toast('info', 'Nothing changed'); return; }
    await act(busyKey, async function () {
        let res = await patchCurrent(delta);
        noteEdit(res);
        return res;
    }, successMsg);
}

async function saveConfigOverride() {
    if (!board || busy.config) return;
    let delta = computeOverrideDelta(board.composition.configOverride, overrideInst);
    if (overrideUnchanged(board.composition.configOverride, delta)) { page.toast('info', 'Nothing changed'); return; }
    await act('config', async function () {
        let res = await patchCurrent({ configOverride: delta ? JSON.stringify(delta) : '' });
        noteEdit(res);
        return res;
    }, delta ? 'Image config override saved' : 'Image config override cleared');
}

async function clearConfigOverride() {
    if (!board || busy.config) return;
    await act('config', async function () {
        let res = await patchCurrent({ configOverride: '' });
        noteEdit(res);
        return res;
    }, 'Image config override cleared');
}

async function saveActor(oid) {
    if (!board || busy['actor:' + oid]) return;
    let orig = null;
    board.actors.forEach(function (a) { if (a.objectId === oid) orig = a; });
    let delta = boardDelta(orig, edits.actors[oid], ACTOR_KEYS);
    if (!Object.keys(delta).length) { page.toast('info', 'Nothing changed'); return; }
    await act('actor:' + oid, async function () {
        let res = await patchActor(bookObjectId, board.compositionObjectId, oid, delta);
        noteEdit(res);
        if (res.actor) { replaceRow(board.actors, res.actor); delete edits.actors[oid]; }
        return res;
    }, 'Actor saved');
}

async function removeActor(a) {
    if (!board) return;
    let ok = await Dialog.confirm({ title: 'Remove actor', message: 'Remove ' + (a.characterName || a.name || 'this actor') + ' from the composition?', confirmLabel: 'Remove', destructive: true });
    if (!ok) return;
    await act('actor:' + a.objectId, async function () {
        let res = await deleteActor(bookObjectId, board.compositionObjectId, a.objectId);
        noteEdit(res);
        return res;
    }, function (res) { return res && res.focalActorCleared ? 'Actor removed; the focal actor was cleared' : 'Actor removed'; });
}

async function saveElement(oid) {
    if (!board || busy['element:' + oid]) return;
    let orig = null;
    board.elements.forEach(function (e) { if (e.objectId === oid) orig = e; });
    let delta = boardDelta(orig, edits.elements[oid], ELEMENT_KEYS);
    if (!Object.keys(delta).length) { page.toast('info', 'Nothing changed'); return; }
    await act('element:' + oid, async function () {
        let res = await patchElement(bookObjectId, board.compositionObjectId, oid, delta);
        noteEdit(res);
        if (res.element) { replaceRow(board.elements, res.element); delete edits.elements[oid]; }
        return res;
    }, 'Element saved');
}

async function removeElement(el) {
    if (!board) return;
    let ok = await Dialog.confirm({ title: 'Remove element', message: 'Remove "' + (el.description || el.elementType || 'this element') + '" from the composition?', confirmLabel: 'Remove', destructive: true });
    if (!ok) return;
    await act('element:' + el.objectId, async function () {
        let res = await deleteElement(bookObjectId, board.compositionObjectId, el.objectId);
        noteEdit(res);
        return res;
    }, 'Element removed');
}

async function promote(el) {
    if (!board) return;
    await act('promote:' + el.objectId, function () { return promoteElement(bookObjectId, board.compositionObjectId, el.objectId); },
        function (res) {
            let s = res && res.promoted
                ? ((res.reused ? 'Linked existing item ' : 'Created item ') + (res.itemName || res.itemObjectId || ''))
                : 'Element was not promoted';
            if (res && res.warnings && res.warnings.length) s += ' — ' + res.warnings.join('; ');
            return s;
        });
}

async function pinOutfit(actor, apparelObjectId) {
    if (!board) return;
    await act('pin:' + actor.objectId, function () { return pinActorApparel(bookObjectId, board.compositionObjectId, actor.objectId, apparelObjectId); },
        function (res) {
            if (!res) return 'Outfit updated';
            let s = res.pinned ? 'Outfit pinned' : 'Outfit unpinned';
            if (res.affectedSceneIndexes && res.affectedSceneIndexes.length) s += '; scenes ' + res.affectedSceneIndexes.join(', ') + ' marked stale';
            return s;
        });
}

// ── Chat config (the name every compose path sends; library default, user pick wins) ──

// Library-only resolution (no LLM call), as the ChapBook render dialog does; never overwrites a pick.
function ensureChatConfigDefault() {
    if (chatConfigRef || chatConfigResolving) return;
    chatConfigResolving = true;
    let bookId = bookObjectId;
    (async function () {
        let found = null;
        for (let i = 0; i < DEFAULT_CHAT_CONFIG_NAMES.length && !found; i++) {
            try {
                let rec = await LLMConnector.resolveConfig(DEFAULT_CHAT_CONFIG_NAMES[i]);
                if (rec && rec.name) found = { name: rec.name, objectId: rec.objectId };
            } catch (e) { /* try the next name */ }
        }
        chatConfigResolving = false;
        if (found && !chatConfigRef && bookId === bookObjectId) chatConfigRef = found;
        m.redraw();
    })();
}

function openChatConfigPicker() {
    ObjectPicker.openLibrary({
        libraryType: 'chatConfig',
        title: 'Select Chat Config',
        onSelect: function (item) {
            if (item && item.name) {
                chatConfigRef = { name: item.name, objectId: item.objectId };
                m.redraw();
            }
        }
    });
}

function renderChatConfigControl() {
    return m('div', { class: 'flex items-center gap-1' }, [
        m('label', { class: 'field-label text-xs whitespace-nowrap mb-0' }, 'Chat Config'),
        m('div', {
            'data-testid': 'pb-cmp-chatconfig',
            class: 'text-sm px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 cursor-pointer flex items-center gap-1 max-w-xs',
            title: 'The chat config every compose on this board sends to the LLM',
            onclick: openChatConfigPicker
        }, [
            m('span', { class: 'truncate' + (chatConfigRef ? '' : ' text-gray-400') }, chatConfigLabel(chatConfigRef, chatConfigResolving)),
            icon('search', 'text-sm text-gray-400')
        ])
    ]);
}

async function recomposeScene() {
    if (!board || busy.recompose) return;
    let warn = userEditedWarning(board.composition);
    if (warn) {
        let ok = await Dialog.confirm({ title: 'Re-compose this scene', message: warn, confirmLabel: 'Re-compose', destructive: true });
        if (!ok) return;
    }
    await act('recompose', function () { return composeScene(bookObjectId, sceneObjectId, withChatConfig({ force: true }, chatConfigRef)); },
        function (res) { return composeSceneText(res, 're-composed', true); });
}

async function composeFirst() {
    if (!bookObjectId || !sceneObjectId || busy.compose) return;
    await act('compose', function () { return composeScene(bookObjectId, sceneObjectId, withChatConfig({}, chatConfigRef)); },
        function (res) { return composeSceneText(res, 'composed'); });
}

// ── Cover mode: create, render, approve (V3 §40) ───────────────────────

/**
 * Create the current purpose's composition. `mode` is 'auto' (the LLM stages it from the book; sends the chat
 * config pick by name), 'from_scene' (clone the scene composition of `sceneOid`) or 'manual' (an empty composition).
 * The server is idempotent (existingReturned) so a double click never 409s.
 */
async function createCurrentCover(mode, sceneOid) {
    if (!bookObjectId || !coverPurpose || busy.create) return;
    let opts = { mode: mode };
    if (mode === 'from_scene') opts.sceneObjectId = sceneOid;
    if (mode === 'auto') opts = withChatConfig(opts, chatConfigRef);
    let label = coverPurposeLabel(coverPurpose);
    await act('create', function () { return createCover(bookObjectId, coverPurpose, opts); },
        function (res) { return createCoverText(res, label); });
}

/** The Create dialog of the empty state: Auto (LLM) / From a scene (select over the book's pages) / Manual. */
function openCreateCoverDialog() {
    if (!bookObjectId || !coverPurpose || busy.create) return;
    let st = { mode: 'auto', sceneObjectId: pages.length ? pages[0].objectId : null, error: null };
    let label = coverPurposeLabel(coverPurpose);
    Dialog.open({
        title: 'Create ' + label.toLowerCase() + ' art',
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-cmp-create-cover', class: 'space-y-3 text-sm' }, [
                    m('p', { class: 'text-gray-600 dark:text-gray-300' }, 'How should the ' + label.toLowerCase() + ' composition start?'),
                    m('label', { class: 'flex items-start gap-2' }, [
                        m('input', { type: 'radio', name: 'pb-cmp-create-mode', 'data-testid': 'pb-cmp-create-auto', checked: st.mode === 'auto', onchange: function () { st.mode = 'auto'; } }),
                        m('span', ['Auto', m('span', { class: 'block text-xs text-gray-500' }, 'The LLM stages it from the book: its hero cast, a setting and a camera. Chat config: ' + (chatConfigRef && chatConfigRef.name ? chatConfigRef.name : '(none picked — the server uses its default)'))])
                    ]),
                    m('label', { class: 'flex items-start gap-2' }, [
                        m('input', { type: 'radio', name: 'pb-cmp-create-mode', 'data-testid': 'pb-cmp-create-from-scene', checked: st.mode === 'from_scene', disabled: !pages.length, onchange: function () { st.mode = 'from_scene'; } }),
                        m('span', ['From a scene', m('span', { class: 'block text-xs text-gray-500' }, pages.length ? 'Clone one page\'s composition, actors and elements included.' : 'The book has no pages to clone from.')])
                    ]),
                    st.mode === 'from_scene' ? m('select', {
                        'data-testid': 'pb-cmp-create-scene', class: INPUT + ' ml-6', value: st.sceneObjectId || '',
                        onchange: function (e) { st.sceneObjectId = e.target.value || null; }
                    }, pages.map(function (pg) { return m('option', { value: pg.objectId, selected: pg.objectId === st.sceneObjectId }, sceneLabel(pg)); })) : null,
                    m('label', { class: 'flex items-start gap-2' }, [
                        m('input', { type: 'radio', name: 'pb-cmp-create-mode', 'data-testid': 'pb-cmp-create-manual', checked: st.mode === 'manual', onchange: function () { st.mode = 'manual'; } }),
                        m('span', ['Manual', m('span', { class: 'block text-xs text-gray-500' }, 'An empty composition: fill in the setting and camera yourself, then Render.')])
                    ]),
                    st.error ? m('div', { class: 'text-xs text-red-600' }, st.error) : null
                ]);
            }
        },
        actions: [
            { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
            {
                label: 'Create', icon: 'add_photo_alternate', primary: true, attrs: { 'data-testid': 'pb-cmp-create-submit' },
                onclick: function () {
                    if (st.mode === 'from_scene' && !st.sceneObjectId) { st.error = 'Pick a scene to clone'; m.redraw(); return; }
                    Dialog.close();
                    createCurrentCover(st.mode, st.sceneObjectId);
                }
            }
        ]
    });
}

function stopWatchingRender() {
    if (renderAbort) { try { renderAbort.abort(); } catch (e) { /* already aborted */ } }
    renderAbort = null;
}

/** Follow a cover render job (202 → /rest/job) until terminal, then re-read the board so the strip shows the new render. */
async function watchRender(submit) {
    stopWatchingRender();
    let ctl = new AbortController();
    renderAbort = ctl;
    let run = { jobId: submit.jobId, purpose: coverPurpose, job: null, error: null, nodeObjectId: submit.nodeObjectId, runObjectId: submit.runObjectId };
    renderRun = run;
    let bookId = bookObjectId;
    m.redraw();
    try {
        let job = await pollJob(submit.jobId, {
            signal: ctl.signal,
            intervalMs: pollIntervalMs,
            onProgress: function (j) { if (renderRun === run) { run.job = j; m.redraw(); } }
        });
        run.job = job;
        if (bookId === bookObjectId) {
            let failed = !!(job && (job.error || job.cancelled));
            page.toast(failed ? 'warn' : 'success', coverPurposeLabel(run.purpose) + ': ' + renderCounterText(job));
            await refetch();
        }
    } catch (e) {
        if (e && e.name === 'AbortError') return;
        run.error = (e && e.message) || 'The render job could not be followed';
        page.toast('error', run.error);
    }
    if (renderAbort === ctl) renderAbort = null;
    m.redraw();
}

/**
 * Render (or re-render) the current cover. `force` re-renders a current node; the server 409s a current node
 * without it, a pinned node always (isPinnedNodeRefusal - unpin from the review queue first), and a node already
 * being rendered. The SD backend is the server's; the client sends no URL.
 */
async function renderCurrentCover(force) {
    if (!bookObjectId || !coverPurpose || busy.render || !board) return;
    busy.render = true;
    m.redraw();
    try {
        let submit = await renderCover(bookObjectId, coverPurpose, !!force);
        page.toast('info', coverPurposeLabel(coverPurpose) + ' render started' + (submit.nodeMaterialized ? ' (render node created)' : ''));
        watchRender(submit);
    } catch (e) {
        page.toast(isPinnedNodeRefusal(e) || (e && e.status === 409) ? 'warn' : 'error', (e && e.message) || 'The render request failed');
    }
    delete busy.render;
    m.redraw();
}

/** Approve the latest render as the cover (PUT /{book}/artifact/{oid}/approve) - the readers show the APPROVED one first. */
async function approveLatestCover() {
    if (!board || !cover || !cover.latest || !cover.latest.artifactObjectId) return;
    let oid = cover.latest.artifactObjectId;
    await act('approve', function () { return approveArtifact(bookObjectId, oid); }, coverPurposeLabel(coverPurpose) + ' approved');
}

function dismissRenderRun() {
    stopWatchingRender();
    renderRun = null;
}

// ── Compose job (book-level) ───────────────────────────────────────────

function stopWatching() {
    if (composeAbort) { try { composeAbort.abort(); } catch (e) { /* already aborted */ } }
    composeAbort = null;
}

async function watchCompose(submit) {
    stopWatching();
    let ctl = new AbortController();
    composeAbort = ctl;
    let run = { jobId: submit.jobId, job: null, label: 'Compose', error: null, quickStart: !!submit.quickStart, force: !!submit.force, result: null, scenesToCompose: submit.scenesToCompose, sceneCount: submit.sceneCount, pagesSkipped: submit.pagesSkipped };
    composeRun = run;
    let bookId = bookObjectId;
    m.redraw();
    try {
        let job = await pollJob(submit.jobId, {
            signal: ctl.signal,
            intervalMs: pollIntervalMs,
            onProgress: function (j) { if (composeRun === run) { run.job = j; m.redraw(); } }
        });
        run.job = job;
        if (job && job.result) run.result = normalizeComposeJobResult(job.result);
        if (bookId === bookObjectId) {
            let failed = !!(job && (job.error || job.cancelled)) || !!(run.result && run.result.hardFailures);
            page.toast(failed ? 'warn' : 'success', composeCounterText(job));
            await refetch();
        }
    } catch (e) {
        if (e && e.name === 'AbortError') return;
        run.error = (e && e.message) || 'The compose job could not be followed';
        page.toast('error', run.error);
    }
    if (composeAbort === ctl) composeAbort = null;
    m.redraw();
}

async function startComposeBook(opts) {
    if (busy.composeBook) return;
    busy.composeBook = true;
    m.redraw();
    try {
        let submit = await composeBook(bookObjectId, withChatConfig({ quickStart: !!opts.quickStart, force: !!opts.force }, chatConfigRef));
        if (!submit.submitted) {
            page.toast('info', submit.reason || 'Nothing to compose');
        } else {
            page.toast('info', (submit.reused ? 'Joined the running compose job' : 'Compose job started') + ' (' + submit.scenesToCompose + ' of ' + submit.sceneCount + ' scenes)');
            watchCompose(submit);
        }
    } catch (e) {
        page.toast(isComposeJobRefusal(e) ? 'warn' : 'error', (e && e.message) || 'The compose request failed');
    }
    delete busy.composeBook;
    m.redraw();
}

function dismissRun() {
    stopWatching();
    composeRun = null;
}

// ── Dialogs ────────────────────────────────────────────────────────────

function openComposeDialog() {
    let st = { quickStart: false, force: false };
    let warn = USER_EDITED_WARNING;
    Dialog.open({
        title: 'Compose book',
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-cmp-compose-dialog', class: 'space-y-3 text-sm' }, [
                    m('p', { class: 'text-gray-600 dark:text-gray-300' }, 'Composes every scene that has no composition yet, in scene order. Scenes with a hand-edited composition are kept unless you force them.'),
                    m('p', { 'data-testid': 'pb-cmp-compose-chatconfig', class: 'text-xs text-gray-500 dark:text-gray-400' },
                        'Chat config: ' + (chatConfigRef && chatConfigRef.name ? chatConfigRef.name : '(none picked — the server uses its default)')),
                    m('label', { class: 'flex items-start gap-2' }, [
                        m('input', { type: 'checkbox', 'data-testid': 'pb-cmp-quickstart', checked: st.quickStart, onchange: function (e) { st.quickStart = e.target.checked; } }),
                        m('span', 'Quick start — render a draft of each scene after composing it')
                    ]),
                    m('label', { class: 'flex items-start gap-2' }, [
                        m('input', { type: 'checkbox', 'data-testid': 'pb-cmp-force', checked: st.force, onchange: function (e) { st.force = e.target.checked; } }),
                        m('span', 'Re-compose in order — overwrite every existing composition, including hand-edited ones')
                    ]),
                    st.force ? m('div', { 'data-testid': 'pb-cmp-force-warning', class: 'text-xs text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-700 rounded px-2 py-1' }, warn) : null
                ]);
            }
        },
        actions: [
            { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
            { label: 'Start', icon: 'auto_awesome', primary: true, attrs: { 'data-testid': 'pb-cmp-compose-start' }, onclick: function () { Dialog.close(); startComposeBook(st); } }
        ]
    });
}

async function openAddActorDialog() {
    if (!board || isChapBookPage()) return;
    let st = { characterObjectId: '', description: '', role: 'supporting', loading: true, error: null };
    let list = characters;
    Dialog.open({
        title: 'Add actor',
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-cmp-actor-add-dialog', class: 'space-y-3 text-sm' }, [
                    field('Character', m('select', { 'data-testid': 'pb-cmp-add-actor-character', class: INPUT, value: st.characterObjectId, onchange: function (e) { st.characterObjectId = e.target.value; } }, [
                        m('option', { value: '' }, st.loading ? '(loading characters…)' : '(unnamed — describe below)')
                    ].concat(list.map(function (c) { return m('option', { value: c.objectId, selected: c.objectId === st.characterObjectId }, c.name || c.objectId); })))),
                    field('Description (for an unnamed actor)', m('input', { 'data-testid': 'pb-cmp-add-actor-description', class: INPUT, value: st.description, placeholder: 'a passing fisherman', oninput: function (e) { st.description = e.target.value; } })),
                    field('Role', enumSelect('pb-cmp-add-actor-role', roleOptions(), st.role, function (v) { st.role = v; })),
                    st.error ? m('div', { class: 'text-xs text-red-600' }, st.error) : null
                ]);
            }
        },
        actions: [
            { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
            {
                label: 'Add', icon: 'person_add', primary: true, attrs: { 'data-testid': 'pb-cmp-add-actor-submit' },
                onclick: function () {
                    let body = {};
                    if (st.characterObjectId) body.characterObjectId = st.characterObjectId;
                    else if (st.description.trim()) body.description = st.description.trim();
                    else { st.error = 'Pick a character or describe the actor'; m.redraw(); return; }
                    if (st.role) body.role = st.role;
                    Dialog.close();
                    act('addActor', async function () {
                        let res = await addActor(bookObjectId, board.compositionObjectId, body);
                        noteEdit(res);
                        return res;
                    }, 'Actor added');
                }
            }
        ]
    });
    try {
        if (!characters.length) characters = await listCharacters(bookObjectId);
        list = characters;
    } catch (e) {
        st.error = (e && e.message) || 'The book characters could not be listed';
    }
    st.loading = false;
    m.redraw();
}

async function openOutfitDialog(actor) {
    if (!board) return;
    if (!actor.characterObjectId) { page.toast('info', 'Unnamed actors have no wardrobe'); return; }
    let st = { loading: true, error: null, wardrobe: null };
    let current = outfits[actor.objectId] || null;
    Dialog.open({
        title: 'Outfit for ' + (actor.characterName || actor.name || 'actor'),
        size: 'md',
        content: {
            view: function () {
                let w = st.wardrobe;
                return m('div', { 'data-testid': 'pb-cmp-outfit-dialog', class: 'space-y-2 text-sm' }, [
                    m('div', { class: 'text-xs text-gray-500' }, ['Resolved: ', m('span', { 'data-testid': 'pb-cmp-outfit-current' }, outfitChipText(current))]),
                    st.loading ? m('div', { class: 'text-gray-500' }, 'Loading wardrobe…') : null,
                    st.error ? m('div', { class: 'text-red-600 text-xs' }, st.error) : null,
                    w && !w.entries.length ? m('div', { class: 'text-gray-500' }, 'This character has no wardrobe entries yet. Add alternates on the character\'s wardrobe panel.') : null,
                    w ? m('ul', { class: 'divide-y dark:divide-gray-700' }, w.entries.map(function (en) {
                        let pinned = !!(actor.apparelObjectId && actor.apparelObjectId === en.objectId);
                        return m('li', { 'data-testid': 'pb-cmp-outfit-entry-' + en.objectId, class: 'py-1 flex items-center gap-2' }, [
                            m('div', { class: 'flex-1 min-w-0' }, [
                                m('div', { class: 'truncate' }, [en.name || en.objectId, en.isDefault ? m('span', { class: CHIP + ' ml-1' }, 'default') : null, en.sceneIndex != null ? m('span', { class: CHIP + ' ml-1' }, 'scene ' + en.sceneIndex) : null]),
                                en.description ? m('div', { class: 'text-xs text-gray-500 truncate' }, en.description) : null
                            ]),
                            pinned
                                ? m('span', { class: CHIP }, 'pinned')
                                : m('button', { 'data-testid': 'pb-cmp-outfit-pin-' + en.objectId, class: BTN, onclick: function () { Dialog.close(); pinOutfit(actor, en.objectId); } }, 'Pin')
                        ]);
                    })) : null,
                    w && w.warnings.length ? m('div', { class: 'text-xs text-amber-700' }, w.warnings.join('; ')) : null
                ]);
            }
        },
        actions: [
            { label: 'Unpin', icon: 'link_off', disabled: !actor.apparelObjectId, attrs: { 'data-testid': 'pb-cmp-outfit-unpin' }, onclick: function () { Dialog.close(); pinOutfit(actor, null); } },
            { label: 'Close', icon: 'close', primary: true, onclick: function () { Dialog.close(); } }
        ]
    });
    try {
        st.wardrobe = await getWardrobe(bookObjectId, actor.characterObjectId);
    } catch (e) {
        st.error = (e && e.message) || 'The wardrobe could not be loaded';
    }
    st.loading = false;
    m.redraw();
}

function openAddElementDialog() {
    if (!board) return;
    let st = { elementType: 'prop', description: '', importance: 'medium', error: null };
    Dialog.open({
        title: 'Add element',
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-cmp-element-add-dialog', class: 'space-y-3 text-sm' }, [
                    field('Type', enumSelect('pb-cmp-add-element-type', elementTypeOptions(), st.elementType, function (v) { st.elementType = v; })),
                    field('Description', m('input', { 'data-testid': 'pb-cmp-add-element-description', class: INPUT, value: st.description, placeholder: 'a brass lantern on the table', oninput: function (e) { st.description = e.target.value; } })),
                    field('Importance', enumSelect('pb-cmp-add-element-importance', importanceOptions(), st.importance, function (v) { st.importance = v; })),
                    st.error ? m('div', { class: 'text-xs text-red-600' }, st.error) : null
                ]);
            }
        },
        actions: [
            { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
            {
                label: 'Add', icon: 'add', primary: true, attrs: { 'data-testid': 'pb-cmp-add-element-submit' },
                onclick: function () {
                    if (!st.elementType || !st.description.trim()) { st.error = 'Type and description are required'; m.redraw(); return; }
                    Dialog.close();
                    act('addElement', async function () {
                        let res = await addElement(bookObjectId, board.compositionObjectId, { elementType: st.elementType, description: st.description.trim(), importance: st.importance || undefined });
                        noteEdit(res);
                        return res;
                    }, 'Element added');
                }
            }
        ]
    });
}

// ── Rendering ──────────────────────────────────────────────────────────

const INPUT = 'w-full text-sm px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 disabled:opacity-60';
const BTN = 'text-xs px-2 py-1 rounded border dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed';
const PRIMARY = BTN + ' bg-purple-600 text-white border-purple-600 hover:bg-purple-700';
const CHIP = 'text-[11px] px-1.5 py-0.5 rounded-full bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-200';
const WARN_CHIP = 'text-[11px] px-1.5 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-800 dark:text-amber-200';

function field(label, control) {
    return m('label', { class: 'block' }, [
        m('div', { class: 'text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, label),
        control
    ]);
}

function enumSelect(testid, list, value, onchange, disabled) {
    let v = value != null ? String(value).toLowerCase() : '';
    if (v === 'unknown') v = '';
    return m('select', { 'data-testid': testid, class: INPUT, value: v, disabled: !!disabled, onchange: function (e) { onchange(e.target.value); } },
        [m('option', { value: '' }, '(unset)')].concat((list || []).map(function (k) { return m('option', { value: k, selected: k === v }, k); })));
}

function numInput(testid, value, onchange, opts) {
    let o = opts || {};
    return m('input', Object.assign({
        type: 'number', 'data-testid': testid, class: INPUT, value: value != null ? value : '',
        oninput: function (e) { onchange(e.target.value === '' ? null : Number(e.target.value)); }
    }, o));
}

function textInput(testid, value, onchange, placeholder) {
    return m('input', { 'data-testid': testid, class: INPUT, value: value != null ? value : '', placeholder: placeholder || '', oninput: function (e) { onchange(e.target.value); } });
}

function icon(name, cls) { return m('span', { class: 'material-symbols-outlined ' + (cls || 'text-base') }, name); }

function sceneIndexOf(oid) {
    for (let i = 0; i < pages.length; i++) if (pages[i].objectId === oid) return i;
    return -1;
}

function goScene(oid) {
    if (!oid || oid === sceneObjectId) return;
    m.route.set('/picture-book/' + bookObjectId + '/compose/' + oid);
}

function goCover(purpose) {
    let p = coverPurposeOf(purpose);
    if (!p || p === coverPurpose) return;
    m.route.set('/picture-book/' + bookObjectId + '/cover/' + coverPathWord(p));
}

/** Cover mode: one tab per cover purpose; a dot marks the purposes that have a composition (from getCovers). */
function renderPurposeTabs() {
    return m('div', { 'data-testid': 'pb-cmp-purpose-tabs', class: 'flex items-center gap-1 ml-2' }, COVER_PURPOSES.map(function (p) {
        let active = p === coverPurpose;
        let exists = !!(covers && covers[p]);
        return m('button', {
            'data-testid': 'pb-cmp-purpose-' + coverPathWord(p),
            'data-active': active ? 'true' : 'false',
            'data-exists': exists ? 'true' : 'false',
            class: BTN + (active ? ' bg-purple-100 dark:bg-purple-900/40 border-purple-400 font-semibold' : ''),
            title: exists ? 'Has a composition' : 'No composition yet',
            onclick: function () { goCover(p); }
        }, [coverPurposeLabel(p), exists ? m('span', { class: 'ml-1 text-green-600' }, '●') : null]);
    }));
}

function renderHeader() {
    let idx = sceneIndexOf(sceneObjectId);
    let prev = idx > 0 ? pages[idx - 1].objectId : null;
    let next = idx >= 0 && idx < pages.length - 1 ? pages[idx + 1].objectId : null;
    let coverMode = isCoverMode();
    return m('div', { class: 'flex flex-wrap items-center gap-2 mb-3' }, [
        m('button', { 'data-testid': 'pb-cmp-back', class: 'text-gray-500 hover:text-purple-600', title: 'Back to the book', onclick: function () { m.route.set('/picture-book/v2/' + bookObjectId); } }, icon('arrow_back', 'text-lg')),
        m('h2', { 'data-testid': 'pb-cmp-title', class: 'text-base font-semibold' }, coverMode ? 'Cover art' : 'Composition board'),
        coverMode ? renderPurposeTabs() : m('div', { class: 'flex items-center gap-1 ml-2' }, [
            m('button', { 'data-testid': 'pb-cmp-scene-prev', class: BTN, disabled: !prev, onclick: function () { goScene(prev); } }, icon('chevron_left')),
            m('select', { 'data-testid': 'pb-cmp-scene-select', class: INPUT + ' w-auto max-w-xs', value: sceneObjectId || '', onchange: function (e) { goScene(e.target.value); } },
                pages.map(function (pg) { return m('option', { value: pg.objectId, selected: pg.objectId === sceneObjectId }, sceneLabel(pg)); })),
            m('button', { 'data-testid': 'pb-cmp-scene-next', class: BTN, disabled: !next, onclick: function () { goScene(next); } }, icon('chevron_right'))
        ]),
        m('div', { class: 'flex-1' }),
        renderChatConfigControl(),
        coverMode
            ? m('button', { 'data-testid': 'pb-cmp-scenes-link', class: BTN, title: 'The scene composition board', onclick: function () { m.route.set('/picture-book/' + bookObjectId + '/compose'); } }, [icon('view_in_ar', 'text-sm align-middle mr-1'), 'Scenes'])
            : m('button', { 'data-testid': 'pb-cmp-compose-book', class: PRIMARY, disabled: !!busy.composeBook || !!(composeRun && composeRun.job && !composeRun.job.terminal), onclick: openComposeDialog }, [icon('auto_awesome', 'text-sm align-middle mr-1'), 'Compose book…']),
        coverMode ? null : m('button', { 'data-testid': 'pb-cmp-cover-link', class: BTN, title: 'Cover art', onclick: function () { m.route.set('/picture-book/' + bookObjectId + '/cover'); } }, [icon('menu_book', 'text-sm align-middle mr-1'), 'Covers']),
        m('button', { 'data-testid': 'pb-cmp-refresh', class: BTN, title: 'Refresh', disabled: loading, onclick: function () { refetch(); } }, icon('refresh'))
    ]);
}

/** Cover mode: the render job being followed (the compose-run panel's shape). */
function renderRenderRun() {
    if (!renderRun) return null;
    let run = renderRun;
    let job = run.job;
    let terminal = !!(job && job.terminal);
    return m('div', { 'data-testid': 'pb-cmp-render-run', 'data-run-terminal': terminal ? 'true' : 'false', class: 'mb-3 border rounded dark:border-gray-700 p-2 text-sm' }, [
        m('div', { class: 'flex items-center gap-2' }, [
            terminal ? icon(job && (job.error || job.cancelled) ? 'error' : 'task_alt', 'text-base ' + (job && (job.error || job.cancelled) ? 'text-red-600' : 'text-green-600')) : icon('progress_activity', 'text-base animate-spin'),
            m('span', { class: 'font-medium' }, 'Render ' + coverPurposeLabel(run.purpose).toLowerCase()),
            m('span', { 'data-testid': 'pb-cmp-render-counter', class: 'text-gray-600 dark:text-gray-300' }, run.error ? run.error : renderCounterText(job)),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-render-dismiss', class: BTN, title: terminal ? 'Dismiss' : 'Stop following (the job keeps running)', onclick: dismissRenderRun }, icon('close'))
        ])
    ]);
}

/**
 * Cover mode: the cover-art strip above the board - the render state line, the approved and the latest render
 * (MediaServlet URLs from the page-view refs), Render / Re-render, Approve latest, and the "nothing to render from"
 * guidance when the last run failed on an empty composition.
 */
function renderCoverStrip() {
    let e = cover;
    let approvedUrl = e && e.approved ? coverImageUrl(e.approved) : null;
    let latestUrl = e && e.latest ? coverImageUrl(e.latest) : null;
    let latestIsApproved = !!(e && e.approved && e.latest && e.approved.artifactObjectId === e.latest.artifactObjectId);
    let rendering = !!(renderRun && renderRun.job && !renderRun.job.terminal) || !!(renderRun && !renderRun.job && !renderRun.error);
    let canApprove = !!(e && e.latest && e.latest.artifactObjectId && !latestIsApproved);
    // A RENDER_PLAN node closes DONE_UNVERIFIED (verification is the review step), the same "rendered" the
    // ChapBook reader and the graph's Regenerate button accept; a Re-render of a current node sends force=true.
    let current = isRenderedStatus(e && e.nodeStatus);
    function tile(testid, label, url, ref) {
        return m('div', { 'data-testid': testid, 'data-artifact': ref ? (ref.artifactObjectId || '') : '', class: 'flex flex-col items-center gap-1 text-xs' }, [
            m('div', { class: 'text-gray-500' }, label + (ref ? ' · rev ' + ref.revision + ' · ' + String(ref.reviewStatus || '').toLowerCase() : '')),
            url
                ? m('img', { src: url, alt: label, class: 'max-h-48 rounded border dark:border-gray-700 object-contain bg-black/5' })
                : m('div', { class: 'h-24 w-36 rounded border border-dashed dark:border-gray-700 flex items-center justify-center text-gray-400' }, ref ? 'image unavailable' : 'none')
        ]);
    }
    return m('section', { 'data-testid': 'pb-cmp-cover', 'data-node-status': e && e.nodeStatus ? e.nodeStatus : '', 'data-pinned': e && e.pinned ? 'true' : 'false', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex flex-wrap items-center gap-2 mb-2' }, [
            m('h3', { class: 'text-sm font-semibold' }, coverPurposeLabel(coverPurpose) + ' art'),
            m('span', { 'data-testid': 'pb-cmp-cover-state', class: e && e.pinned ? WARN_CHIP : CHIP }, coverStateText(e)),
            e && e.titleSafeBand && e.titleSafeBand !== 'UNKNOWN' ? m('span', { 'data-testid': 'pb-cmp-cover-band', class: CHIP, title: 'Title-safe band' }, 'band ' + e.titleSafeBand.toLowerCase()) : null,
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-render-cover', class: PRIMARY, disabled: !!busy.render || rendering || !!(e && e.pinned), title: e && e.pinned ? 'Unpin the node from the review queue first' : (current ? 'The last render is current; re-render anyway' : 'Render this cover'), onclick: function () { renderCurrentCover(current); } },
                [icon('brush', 'text-sm align-middle mr-1'), busy.render ? 'Submitting…' : (rendering ? 'Rendering…' : (current ? 'Re-render' : 'Render'))]),
            m('button', { 'data-testid': 'pb-cmp-approve-cover', class: BTN, disabled: !canApprove || !!busy.approve, title: canApprove ? 'Approve the latest render as the cover' : (latestIsApproved ? 'The latest render is approved' : 'Nothing to approve yet'), onclick: approveLatestCover },
                [icon('verified', 'text-sm align-middle mr-1'), busy.approve ? 'Approving…' : 'Approve latest'])
        ]),
        e && e.lastError ? m('div', { 'data-testid': 'pb-cmp-cover-error', class: 'text-xs text-red-600 mb-2' }, [
            'Last render: ' + e.lastError,
            isNothingToRenderError(e) ? m('div', { 'data-testid': 'pb-cmp-cover-hint', class: 'text-amber-700 dark:text-amber-300 mt-1' }, 'Fill in a setting, an action or a prompt override below and save, then Render again.') : null
        ]) : null,
        m('div', { class: 'flex flex-wrap gap-4' }, [
            tile('pb-cmp-cover-approved', 'Approved', approvedUrl, e ? e.approved : null),
            latestIsApproved ? null : tile('pb-cmp-cover-latest', 'Latest', latestUrl, e ? e.latest : null)
        ])
    ]);
}

function renderComposeRun() {
    if (!composeRun) return null;
    let run = composeRun;
    let job = run.job;
    let terminal = !!(job && job.terminal);
    let res = run.result;
    return m('div', { 'data-testid': 'pb-cmp-compose-run', 'data-run-terminal': terminal ? 'true' : 'false', class: 'mb-3 border rounded dark:border-gray-700 p-2 text-sm' }, [
        m('div', { class: 'flex items-center gap-2' }, [
            terminal ? icon('task_alt', 'text-base text-green-600') : icon('progress_activity', 'text-base animate-spin'),
            m('span', { class: 'font-medium' }, run.label + (run.force ? ' (re-compose in order)' : '') + (run.quickStart ? ' + quick start' : '')),
            m('span', { 'data-testid': 'pb-cmp-compose-counter', class: 'text-gray-600 dark:text-gray-300' }, run.error ? run.error : composeCounterText(job)),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-compose-dismiss', class: BTN, title: terminal ? 'Dismiss' : 'Stop following (the job keeps running)', onclick: dismissRun }, icon('close'))
        ]),
        terminal && res && res.scenes.length ? m('ul', { class: 'mt-1 space-y-0.5 text-xs' }, res.scenes.map(function (s) {
            return m('li', { 'data-testid': 'pb-cmp-compose-scene-' + s.sceneIndex, 'data-status': s.status || '', class: 'flex items-center gap-2' }, [
                m('span', { class: CHIP }, s.status || '?'),
                m('span', 'Scene ' + s.sceneIndex + (s.title ? ' · ' + s.title : '')),
                s.actorCount || s.elementCount ? m('span', { class: 'text-gray-500' }, s.actorCount + ' actors, ' + s.elementCount + ' elements') : null,
                s.error ? m('span', { class: 'text-red-600' }, s.error) : null,
                s.draftRenderError ? m('span', { class: 'text-amber-700' }, 'draft: ' + s.draftRenderError) : null,
                s.warnings.length ? m('span', { class: 'text-amber-700' }, s.warnings.join('; ')) : null,
                s.sceneObjectId ? m('button', { class: BTN, onclick: function () { goScene(s.sceneObjectId); } }, 'Open') : null
            ]);
        })) : null,
        terminal && res && res.note ? m('div', { class: 'mt-1 text-xs text-gray-500' }, res.note) : null
    ]);
}

function renderEmpty() {
    if (isCoverMode()) {
        return m('div', { 'data-testid': 'pb-cmp-empty', class: 'border rounded dark:border-gray-700 p-4 text-sm' }, [
            m('p', { 'data-testid': 'pb-cmp-empty-message', class: 'text-gray-600 dark:text-gray-300 mb-2' }, noCompositionMessage || ('This book has no ' + coverPurposeLabel(coverPurpose).toLowerCase() + ' composition yet')),
            m('button', { 'data-testid': 'pb-cmp-create-cover-open', class: PRIMARY, disabled: !!busy.create, onclick: openCreateCoverDialog }, [icon('add_photo_alternate', 'text-sm align-middle mr-1'), busy.create ? 'Creating…' : 'Create ' + coverPurposeLabel(coverPurpose).toLowerCase() + '…'])
        ]);
    }
    return m('div', { 'data-testid': 'pb-cmp-empty', class: 'border rounded dark:border-gray-700 p-4 text-sm' }, [
        m('p', { 'data-testid': 'pb-cmp-empty-message', class: 'text-gray-600 dark:text-gray-300 mb-2' }, noCompositionMessage || 'This scene has no composition yet'),
        m('button', { 'data-testid': 'pb-cmp-compose-scene', class: PRIMARY, disabled: !!busy.compose, onclick: composeFirst }, [icon('auto_awesome', 'text-sm align-middle mr-1'), busy.compose ? 'Composing…' : 'Compose this scene'])
    ]);
}

function renderStatus() {
    let c = board.composition;
    let stale = lastEdit && lastEdit.nodeStale;
    let coverMode = isCoverMode();
    return m('div', { 'data-testid': 'pb-cmp-status', class: 'flex flex-wrap items-center gap-1 mb-2' }, [
        m('span', { 'data-testid': 'pb-cmp-revision', class: CHIP }, 'rev ' + (lastEdit && lastEdit.revision ? lastEdit.revision : c.revision)),
        m('span', { 'data-testid': 'pb-cmp-source', class: CHIP }, c.source || 'unknown'),
        m('span', { 'data-testid': 'pb-cmp-purpose', class: CHIP }, c.purpose || 'scene'),
        c.userEdited ? m('span', { 'data-testid': 'pb-cmp-user-edited', class: WARN_CHIP, title: coverMode ? 'Edited by hand' : 'Edited by hand; Re-compose overwrites it' }, 'user-edited') : null,
        c.promptLocked ? m('span', { 'data-testid': 'pb-cmp-locked', class: WARN_CHIP }, 'prompt locked') : null,
        stale ? m('span', { 'data-testid': 'pb-cmp-stale', class: WARN_CHIP, title: coverMode ? 'The cover render node is stale after this edit' : 'The scene render node is stale after this edit' }, 'render stale' + (lastEdit.nodeStatus ? ' · ' + lastEdit.nodeStatus : '')) : null,
        coverMode ? null : (c.locationObjectId ? m('span', { class: CHIP, title: 'Scene location' }, 'location set') : m('span', { class: WARN_CHIP }, 'no location'))
    ]);
}

function renderDegradations() {
    let s = degradationSummary(plan);
    if (!plan || !s.count) return null;
    return m('div', { 'data-testid': 'pb-cmp-degradations', 'data-count': String(s.count), class: 'mb-2 text-xs border border-amber-300 dark:border-amber-700 rounded px-2 py-1' }, [
        m('div', { class: 'flex flex-wrap gap-1 items-center' }, [m('span', { class: 'font-medium' }, 'Plan degradations:')].concat(s.codes.map(function (code) { return m('span', { 'data-code': code, class: WARN_CHIP }, code); }))),
        s.truncatedDropped.length ? m('ul', { 'data-testid': 'pb-cmp-truncated', class: 'mt-1 list-disc ml-4' }, s.truncatedDropped.map(function (t) { return m('li', t); })) : null
    ]);
}

function renderCamera() {
    let d = compositionDraft();
    let actors = board.actors;
    let k = 'camera';
    let coverMode = isCoverMode();
    return m('section', { 'data-testid': 'pb-cmp-camera', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex items-center gap-2 mb-2' }, [m('h3', { class: 'text-sm font-semibold' }, 'Camera & scene'),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-save-camera', class: PRIMARY, disabled: !!busy[k], onclick: function () { saveCompositionKeys(coverMode ? COVER_COMPOSITION_KEYS : COMPOSITION_KEYS, k, 'Composition saved'); } }, busy[k] ? 'Saving…' : 'Save')]),
        m('div', { class: 'grid grid-cols-1 md:grid-cols-3 gap-2' }, [
            field('Shot', enumSelect('pb-cmp-shot', shotTypeOptions(), d.shotType, function (v) { d.shotType = v; })),
            field('Viewpoint', enumSelect('pb-cmp-viewpoint', viewpointOptions(), d.viewpoint, function (v) { d.viewpoint = v; })),
            field('Focal actor', m('select', { 'data-testid': 'pb-cmp-focal', class: INPUT, value: d.focalActorObjectId || '', onchange: function (e) { d.focalActorObjectId = e.target.value || ''; } },
                [m('option', { value: '' }, '(none)')].concat(actors.map(function (a) { return m('option', { value: a.objectId, selected: a.objectId === d.focalActorObjectId }, a.characterName || a.name || a.description || a.objectId); })))),
            coverMode ? field('Title-safe band', enumSelect('pb-cmp-title-band', titleBandOptions(), d.titleSafeBand, function (v) { d.titleSafeBand = v; })) : null
        ]),
        coverMode ? m('div', { class: 'text-[11px] text-gray-500 mt-1' }, 'The band (top or bottom quarter) is reserved for the title: the reader places the title block there and the render plan reports it as reserved.') : null,
        m('div', { class: 'grid grid-cols-1 md:grid-cols-2 gap-2 mt-2' }, [
            field('Camera text', textInput('pb-cmp-camera-text', d.cameraText, function (v) { d.cameraText = v; }, 'low angle, looking up the stairs')),
            field('Mood', textInput('pb-cmp-mood', d.mood, function (v) { d.mood = v; }, 'tense, dusk')),
            field('Setting', textInput('pb-cmp-setting', d.settingText, function (v) { d.settingText = v; })),
            field('Action', textInput('pb-cmp-action', d.action, function (v) { d.action = v; })),
            field('Props', textInput('pb-cmp-props', d.props, function (v) { d.props = v; }, 'comma-separated'))
        ])
    ]);
}

function renderPrompt() {
    let d = compositionDraft();
    let k = 'prompt';
    return m('section', { 'data-testid': 'pb-cmp-prompt', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex items-center gap-2 mb-2' }, [m('h3', { class: 'text-sm font-semibold' }, 'Prompt override'),
            m('label', { class: 'flex items-center gap-1 text-xs' }, [m('input', { type: 'checkbox', 'data-testid': 'pb-cmp-prompt-locked', checked: !!d.promptLocked, onchange: function (e) { d.promptLocked = e.target.checked; } }), 'Locked']),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-save-prompt', class: PRIMARY, disabled: !!busy[k], onclick: function () { saveCompositionKeys(PROMPT_KEYS, k, 'Prompt saved'); } }, busy[k] ? 'Saving…' : 'Save')]),
        m('textarea', { 'data-testid': 'pb-cmp-prompt-override', class: INPUT + ' font-mono', rows: 3, value: d.promptOverride || '', placeholder: 'Leave blank to use the compiled prompt', oninput: function (e) { d.promptOverride = e.target.value; } }),
        m('div', { class: 'text-[11px] text-gray-500 mt-1' }, 'A non-blank override replaces the compiled prompt; "Locked" keeps it through Re-compose.')
    ]);
}

async function ensureOverrideInst() {
    if (overrideInst || !board) return;
    let base = parseConfigOverride(board.composition.configOverride) || am7model.newPrimitive('olio.sd.config');
    base = Object.assign({}, base);
    base[am7model.jsonModelKey] = 'olio.sd.config';
    SD_CONFIG_IDENTITY.forEach(function (k) { delete base[k]; });
    let entity = am7model.prepareEntity(base, 'olio.sd.config');
    overrideInst = am7model.prepareInstance(entity, am7model.forms.sdConfigOverrides);
    if (!sdModels.length) {
        try { await am7sd.fetchModels(); sdModels = am7sd.getModelList() || []; } catch (e) { sdModels = []; }
    }
    if (!sdLoras.length) {
        try { await am7sd.fetchLoras(); sdLoras = am7sd.getLoraList() || []; } catch (e) { sdLoras = []; }
    }
    m.redraw();
}

function renderConfigOverride() {
    let has = !!parseConfigOverride(board.composition.configOverride);
    return m('section', { 'data-testid': 'pb-cmp-config', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex items-center gap-2' }, [
            m('button', { 'data-testid': 'pb-cmp-config-override', class: 'text-sm font-semibold flex items-center gap-1', onclick: function () { overrideExpanded = !overrideExpanded; if (overrideExpanded) ensureOverrideInst(); } }, [icon(overrideExpanded ? 'expand_more' : 'chevron_right'), 'Image config override', has ? m('span', { 'data-testid': 'pb-cmp-config-set', class: 'ml-1 px-1.5 rounded bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 text-[11px]' }, 'set') : null]),
            m('div', { class: 'flex-1' }),
            has ? m('button', { 'data-testid': 'pb-cmp-config-clear', class: BTN, disabled: !!busy.config, onclick: clearConfigOverride }, 'Clear') : null,
            overrideExpanded ? m('button', { 'data-testid': 'pb-cmp-config-save', class: PRIMARY, disabled: !!busy.config || !overrideInst, onclick: saveConfigOverride }, busy.config ? 'Saving…' : 'Save') : null
        ]),
        overrideExpanded ? m('div', { class: 'mt-2' }, [
            m('div', { class: 'text-[11px] text-gray-500 mb-1' }, 'Only the fields you change are sent, as a sparse olio.sd.config diff applied between the scene\'s and the node\'s overrides.'),
            overrideInst ? m(SdConfigPanel, { inst: overrideInst, models: sdModels, loras: sdLoras, onChange: function () { m.redraw(); } }) : m('div', { class: 'text-xs text-gray-500' }, 'Loading…')
        ]) : null
    ]);
}

function renderActorRow(a) {
    let d = actorDraft(a);
    let k = 'actor:' + a.objectId;
    let outfit = outfits[a.objectId] || null;
    let tid = function (s) { return 'pb-cmp-actor-' + s; };
    return m('li', { 'data-testid': 'pb-cmp-actor-' + a.objectId, 'data-actor-objectid': a.objectId, class: 'border rounded dark:border-gray-700 p-2' }, [
        m('div', { class: 'flex flex-wrap items-center gap-2 mb-1' }, [
            m('span', { class: 'font-medium text-sm' }, a.characterName || a.name || a.description || '(unnamed)'),
            a.characterObjectId ? null : m('span', { class: CHIP }, 'unnamed'),
            m('span', { 'data-testid': tid('outfit-chip'), 'data-source': outfit ? outfit.source : 'none', class: CHIP, title: outfit && outfit.apparelDescription ? outfit.apparelDescription : '' }, outfitChipText(outfit)),
            outfit && outfit.warning ? m('span', { class: WARN_CHIP }, outfit.warning) : null,
            m('button', { 'data-testid': tid('outfit-pick'), class: BTN, disabled: !a.characterObjectId || !!busy['pin:' + a.objectId], title: a.characterObjectId ? 'Pick an outfit from the wardrobe' : 'Unnamed actors have no wardrobe', onclick: function () { openOutfitDialog(a); } }, 'Outfit…'),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': tid('save'), class: PRIMARY, disabled: !!busy[k], onclick: function () { saveActor(a.objectId); } }, busy[k] ? 'Saving…' : 'Save'),
            m('button', { 'data-testid': tid('delete'), class: BTN, disabled: !!busy[k], onclick: function () { removeActor(a); } }, icon('delete'))
        ]),
        m('div', { class: 'grid grid-cols-2 md:grid-cols-4 gap-2' }, [
            field('Role', enumSelect(tid('role'), roleOptions(), d.role, function (v) { d.role = v; })),
            field('Priority', numInput(tid('priority'), d.priority, function (v) { d.priority = v; }, { min: 0, step: 1 })),
            field('Facing', enumSelect(tid('facing'), facingOptions(), d.facing, function (v) { d.facing = v; })),
            field('Reference', enumSelect(tid('policy'), policyOptions(), d.referencePolicy, function (v) { d.referencePolicy = v; })),
            field('Pose', textInput(tid('pose'), d.pose, function (v) { d.pose = v; })),
            field('Expression', textInput(tid('expression'), d.expression, function (v) { d.expression = v; })),
            field('Action', textInput(tid('action'), d.action, function (v) { d.action = v; })),
            field('Count', numInput(tid('count'), d.count, function (v) { d.count = v; }, { min: 1, max: 500, step: 1 })),
            field('x', numInput(tid('x'), d.x, function (v) { d.x = v; }, { min: 0, max: 1, step: 0.01 })),
            field('y', numInput(tid('y'), d.y, function (v) { d.y = v; }, { min: 0, max: 1, step: 0.01 })),
            field('w', numInput(tid('w'), d.w, function (v) { d.w = v; }, { min: 0, max: 1, step: 0.01 })),
            field('h', numInput(tid('h'), d.h, function (v) { d.h = v; }, { min: 0, max: 1, step: 0.01 })),
            field('Depth', numInput(tid('depth'), d.depth, function (v) { d.depth = v; }, { min: 0, step: 1, title: 'Layer order, 0 = front-most' }))
        ]),
        a.characterObjectId ? null : field('Description', textInput(tid('description'), d.description, function (v) { d.description = v; }))
    ]);
}

function renderActors() {
    let chap = isChapBookPage();
    return m('section', { 'data-testid': 'pb-cmp-actors', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex items-center gap-2 mb-2' }, [m('h3', { class: 'text-sm font-semibold' }, 'Actors (' + board.actors.length + ')'),
            outfitsError ? m('span', { 'data-testid': 'pb-cmp-outfits-error', class: WARN_CHIP, title: outfitsError }, 'Outfits not read: ' + outfitsError) : null,
            m('div', { class: 'flex-1' }),
            chap ? m('span', { 'data-testid': 'pb-cmp-actors-disabled', class: 'text-xs text-gray-500' }, 'Actors are not enabled on ChapBook pages')
                : m('button', { 'data-testid': 'pb-cmp-actor-add', class: BTN, disabled: !!busy.addActor, onclick: openAddActorDialog }, [icon('person_add', 'text-sm align-middle mr-1'), 'Add actor'])]),
        board.actors.length ? m('ul', { class: 'space-y-2' }, board.actors.map(renderActorRow)) : m('div', { class: 'text-xs text-gray-500' }, chap ? '' : 'No actors on this composition.')
    ]);
}

function renderElementRow(el) {
    let d = elementDraft(el);
    let k = 'element:' + el.objectId;
    let tid = function (s) { return 'pb-cmp-element-' + s; };
    return m('li', { 'data-testid': 'pb-cmp-element-' + el.objectId, 'data-element-objectid': el.objectId, class: 'border rounded dark:border-gray-700 p-2' }, [
        m('div', { class: 'flex flex-wrap items-center gap-2 mb-1' }, [
            m('span', { class: 'font-medium text-sm' }, el.description || '(no description)'),
            el.itemObjectId ? m('span', { 'data-testid': tid('item'), class: CHIP, title: el.itemObjectId }, 'item') : null,
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': tid('promote'), class: BTN, disabled: !!busy['promote:' + el.objectId] || !!el.itemObjectId, title: el.itemObjectId ? 'Already linked to an item' : 'Create or link a location item for this element', onclick: function () { promote(el); } }, 'Promote to item'),
            m('button', { 'data-testid': tid('save'), class: PRIMARY, disabled: !!busy[k], onclick: function () { saveElement(el.objectId); } }, busy[k] ? 'Saving…' : 'Save'),
            m('button', { 'data-testid': tid('delete'), class: BTN, disabled: !!busy[k], onclick: function () { removeElement(el); } }, icon('delete'))
        ]),
        m('div', { class: 'grid grid-cols-2 md:grid-cols-4 gap-2' }, [
            field('Type', enumSelect(tid('type'), elementTypeOptions(), d.elementType, function (v) { d.elementType = v; })),
            field('Importance', enumSelect(tid('importance'), importanceOptions(), d.importance, function (v) { d.importance = v; })),
            m('div', { class: 'col-span-2' }, field('Description', textInput(tid('description'), d.description, function (v) { d.description = v; }))),
            field('x', numInput(tid('x'), d.x, function (v) { d.x = v; }, { min: 0, max: 1, step: 0.01 })),
            field('y', numInput(tid('y'), d.y, function (v) { d.y = v; }, { min: 0, max: 1, step: 0.01 })),
            field('w', numInput(tid('w'), d.w, function (v) { d.w = v; }, { min: 0, max: 1, step: 0.01 })),
            field('h', numInput(tid('h'), d.h, function (v) { d.h = v; }, { min: 0, max: 1, step: 0.01 })),
            field('Depth', numInput(tid('depth'), d.depth, function (v) { d.depth = v; }, { min: 0, step: 1, title: 'Layer order, 0 = front-most' }))
        ])
    ]);
}

function renderElements() {
    return m('section', { 'data-testid': 'pb-cmp-elements', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex items-center gap-2 mb-2' }, [m('h3', { class: 'text-sm font-semibold' }, 'Elements (' + board.elements.length + ')'),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-element-add', class: BTN, disabled: !!busy.addElement, onclick: openAddElementDialog }, [icon('add', 'text-sm align-middle mr-1'), 'Add element'])]),
        board.elements.length ? m('ul', { class: 'space-y-2' }, board.elements.map(renderElementRow)) : m('div', { class: 'text-xs text-gray-500' }, 'No elements on this composition.')
    ]);
}

function canvasBox(kind, row, draft, label) {
    let tid = 'pb-cmp-box-' + kind + '-' + row.objectId;
    let moved = draft && (draft.x !== row.x || draft.y !== row.y);
    return m('div', {
        'data-testid': tid,
        'data-kind': kind,
        class: 'absolute rounded border-2 text-[10px] leading-tight px-1 overflow-hidden select-none cursor-move ' +
            (kind === 'actor' ? 'border-purple-500 bg-purple-500/20' : 'border-emerald-500 bg-emerald-500/20') +
            (moved ? ' ring-2 ring-amber-400' : ''),
        style: boxStyle(draft || row),
        title: label,
        onpointerdown: function (e) {
            let rect = e.currentTarget.parentNode.getBoundingClientRect();
            drag = { kind: kind, objectId: row.objectId, startX: e.clientX, startY: e.clientY, orig: Object.assign({}, draft || row), rect: rect };
            try { e.currentTarget.setPointerCapture(e.pointerId); } catch (ex) { /* jsdom */ }
            e.preventDefault();
        },
        onpointermove: function (e) {
            if (!drag || drag.objectId !== row.objectId || !drag.rect.width || !drag.rect.height) return;
            let dx = (e.clientX - drag.startX) / drag.rect.width;
            let dy = (e.clientY - drag.startY) / drag.rect.height;
            let pos = dragMove(drag.orig, dx, dy);
            let d = kind === 'actor' ? actorDraft(row) : elementDraft(row);
            d.x = pos.x;
            d.y = pos.y;
        },
        onpointerup: function () { drag = null; },
        onpointercancel: function () { drag = null; }
    }, label);
}

function renderCanvas() {
    let aspect = canvasAspect(plan);
    return m('section', { 'data-testid': 'pb-cmp-canvas-section', class: 'border rounded dark:border-gray-700 p-2 mb-3' }, [
        m('div', { class: 'flex items-center gap-2 mb-1' }, [m('h3', { class: 'text-sm font-semibold' }, 'Placement'),
            m('span', { class: 'text-[11px] text-gray-500' }, 'Drag a box to move it, then Save its row. Boxes are fractions of the page (' + (plan && plan.canvas && plan.canvas.width ? plan.canvas.width + '×' + plan.canvas.height : '4:3') + ').')]),
        m('div', { 'data-testid': 'pb-cmp-canvas', class: 'relative w-full bg-gray-100 dark:bg-gray-800 border border-dashed border-gray-300 dark:border-gray-600 rounded overflow-hidden', style: { aspectRatio: String(aspect), touchAction: 'none' } },
            board.actors.map(function (a) { return canvasBox('actor', a, edits.actors[a.objectId], a.characterName || a.name || a.description || 'actor'); })
                .concat(board.elements.map(function (el) { return canvasBox('element', el, edits.elements[el.objectId], el.description || el.elementType || 'element'); })))
    ]);
}

function renderPlanSide() {
    let hp = planHeadlinePrompt(plan);
    return m('aside', { 'data-testid': 'pb-cmp-plan-side', class: 'border rounded dark:border-gray-700 p-2' }, [
        m('div', { class: 'flex items-center gap-2 mb-2' }, [m('h3', { class: 'text-sm font-semibold' }, 'Compiled render plan'),
            m('div', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-cmp-plan-refresh', class: BTN, disabled: planLoading, onclick: loadPlan }, planLoading ? 'Compiling…' : 'Recompile')]),
        m('div', { class: 'text-[11px] text-gray-500 mb-2' }, 'The prompt the renderer would use right now for this scene, compiled on the server from the scene, its location and its style — nothing is persisted by viewing it.'),
        planError ? m('div', { 'data-testid': 'pb-cmp-plan-error', class: 'text-xs text-red-600 mb-2' }, planError) : null,
        hp ? m('div', { class: 'mb-2' }, [
            m('div', { class: 'text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, 'Prompt' + (hp.stage ? ' · ' + hp.stage : '')),
            m('pre', { 'data-testid': 'pb-cmp-plan-prompt', 'data-stage': hp.stage || '', class: 'text-[11px] whitespace-pre-wrap break-words bg-gray-50 dark:bg-gray-900 rounded p-2 max-h-48 overflow-y-auto' }, hp.prompt),
            hp.negative ? m('pre', { 'data-testid': 'pb-cmp-plan-negative', class: 'text-[11px] whitespace-pre-wrap break-words bg-gray-50 dark:bg-gray-900 rounded p-2 mt-1 max-h-24 overflow-y-auto text-gray-500' }, hp.negative) : null
        ]) : null,
        plan ? m(PbPlanInspector, { plan: plan }) : (planLoading ? m('div', { class: 'text-xs text-gray-500' }, 'Compiling…') : null)
    ]);
}

function renderBoard() {
    let c = board.composition;
    return m('div', [
        renderStatus(),
        renderDegradations(),
        // Cover creation is idempotent on the server (a second create returns the existing composition), so there is
        // no "re-create" in cover mode: edit the fields, or render again.
        isCoverMode() ? null : m('div', { class: 'flex flex-wrap items-center gap-2 mb-3' }, [
            m('button', { 'data-testid': 'pb-cmp-recompose', class: BTN, disabled: !!busy.recompose, title: c.userEdited ? 'Overwrites the hand edits' : 'Ask the LLM to compose this scene again', onclick: recomposeScene }, [icon('auto_awesome', 'text-sm align-middle mr-1'), busy.recompose ? 'Re-composing…' : 'Re-compose scene']),
            c.userEdited ? m('span', { 'data-testid': 'pb-cmp-recompose-warning', class: 'text-[11px] text-amber-700 dark:text-amber-300' }, 'Hand-edited: re-composing overwrites it') : null
        ]),
        renderCamera(),
        renderCanvas(),
        renderActors(),
        renderElements(),
        renderPrompt(),
        renderConfigOverride()
    ]);
}

function renderBody() {
    if (loading && !board) return m('div', { 'data-testid': 'pb-cmp-loading', class: 'text-sm text-gray-500 p-4' }, 'Loading…');
    if (error) return m('div', { 'data-testid': 'pb-cmp-error', class: 'text-sm text-red-600 p-4' }, error);
    if (!isCoverMode() && !sceneObjectId) return m('div', { 'data-testid': 'pb-cmp-no-scenes', class: 'text-sm text-gray-500 p-4' }, 'This book has no pages to compose.');
    let grid = m('div', { class: 'grid grid-cols-1 lg:grid-cols-3 gap-3' }, [
        m('div', { class: 'lg:col-span-2' }, noComposition ? renderEmpty() : (board ? renderBoard() : null)),
        renderPlanSide()
    ]);
    if (!isCoverMode()) return grid;
    // Cover mode: the render-job panel and the cover-art strip sit above the board; the strip needs a loaded board.
    return [renderRenderRun(), board ? renderCoverStrip() : null, grid];
}

// ── View & routes ──────────────────────────────────────────────────────

function resetState() {
    stopWatching();
    stopWatchingRender();
    board = null;
    cover = null;
    renderRun = null;
    noComposition = false;
    noCompositionMessage = null;
    error = null;
    outfits = {};
    outfitsError = null;
    plan = null;
    planError = null;
    planLoading = false;
    busy = {};
    edits = { composition: null, actors: {}, elements: {} };
    lastEdit = null;
    composeRun = null;
    drag = null;
    overrideInst = null;
    overrideExpanded = false;
}

/**
 * attrs: bookObjectId, and either sceneObjectId (scene mode) or mode:'cover' + purpose (cover mode; an unknown or
 * missing purpose means COVER). A purpose change is treated like a scene change: the board state is reset and
 * re-read; the covers list (per book) survives it.
 */
async function init(vnode) {
    let a = vnode.attrs || {};
    let bid = a.bookObjectId;
    let coverMode = a.mode === 'cover' || a.purpose != null;
    let sid = coverMode ? null : (a.sceneObjectId || null);
    let cp = coverMode ? (coverPurposeOf(a.purpose) || 'COVER') : null;
    if (!bid) return;
    let bookChanged = bid !== bookObjectId;
    let modeChanged = coverMode !== isCoverMode();
    let purposeChanged = coverMode && cp !== coverPurpose;
    let sceneChanged = !coverMode && sid ? sid !== sceneObjectId : false;
    if (!bookChanged && !modeChanged && !purposeChanged && !sceneChanged && board) return;
    if (bookChanged) {
        bookObjectId = bid;
        pages = [];
        characters = [];
        covers = null;
        sceneObjectId = sid;
        coverPurpose = cp;
        resetState();
        // P9-8 (240 d): a ChapBook opens with the chat config its own screens carry (registered by the ChapBook
        // feature; null for a PictureBook or an unknown book), else the library default resolves below.
        chatConfigRef = carriedBookChatConfig(bid);
        chatConfigResolving = false;
        loadSeq++;
        ensureChatConfigDefault();
        await loadPages();
    } else if (modeChanged || purposeChanged || sceneChanged) {
        coverPurpose = cp;
        if (sid) sceneObjectId = sid;
        resetState();
    }
    if (!coverMode && !sceneObjectId) sceneObjectId = sid || (pages.length ? pages[0].objectId : null);
    if (bookObjectId === bid && coverPurpose === cp && (!sid || sceneObjectId === sid)) await loadBoard();
    m.redraw();
}

const composeView = {
    oninit: function (vnode) { init(vnode); },
    onremove: function () { stopWatching(); stopWatchingRender(); },
    view: function () {
        return m('div', { 'data-testid': 'pb-cmp', class: 'p-4 flex flex-col h-full' }, [
            renderHeader(),
            renderComposeRun(),
            m('div', { class: 'flex-1 overflow-y-auto' }, renderBody())
        ]);
    }
};

// ── Test seams ─────────────────────────────────────────────────────────

export function __composeStateForTest() {
    return { bookObjectId, sceneObjectId, pages, board, noComposition, noCompositionMessage, loading, error, loadSeq, outfits, outfitsError, plan, planError, planLoading, busy, edits, lastEdit, characters, composeRun, composeAbort, drag, overrideInst, overrideExpanded, sdModels, sdLoras, pollIntervalMs, chatConfigRef, chatConfigResolving, coverPurpose, cover, covers, renderRun, renderAbort };
}

export function __setComposeStateForTest(s) {
    s = s || {};
    if ('bookObjectId' in s) bookObjectId = s.bookObjectId;
    if ('sceneObjectId' in s) sceneObjectId = s.sceneObjectId;
    if ('coverPurpose' in s) coverPurpose = s.coverPurpose || null;
    if ('cover' in s) cover = s.cover || null;
    if ('covers' in s) covers = s.covers || null;
    if ('renderRun' in s) renderRun = s.renderRun || null;
    if ('pages' in s) pages = s.pages || [];
    if ('board' in s) board = s.board || null;
    if ('noComposition' in s) noComposition = !!s.noComposition;
    if ('noCompositionMessage' in s) noCompositionMessage = s.noCompositionMessage;
    if ('loading' in s) loading = !!s.loading;
    if ('error' in s) error = s.error;
    if ('loadSeq' in s) loadSeq = s.loadSeq;
    if ('outfits' in s) outfits = s.outfits || {};
    if ('outfitsError' in s) outfitsError = s.outfitsError || null;
    if ('plan' in s) plan = s.plan || null;
    if ('planError' in s) planError = s.planError;
    if ('planLoading' in s) planLoading = !!s.planLoading;
    if ('busy' in s) busy = s.busy || {};
    if ('edits' in s) edits = s.edits || { composition: null, actors: {}, elements: {} };
    if ('lastEdit' in s) lastEdit = s.lastEdit || null;
    if ('characters' in s) characters = s.characters || [];
    if ('composeRun' in s) composeRun = s.composeRun || null;
    if ('drag' in s) drag = s.drag || null;
    if ('overrideInst' in s) overrideInst = s.overrideInst || null;
    if ('overrideExpanded' in s) overrideExpanded = !!s.overrideExpanded;
    if ('sdModels' in s) sdModels = s.sdModels || [];
    if ('sdLoras' in s) sdLoras = s.sdLoras || [];
    if ('pollIntervalMs' in s) pollIntervalMs = s.pollIntervalMs;
    if ('chatConfigRef' in s) chatConfigRef = s.chatConfigRef || null;
    if ('chatConfigResolving' in s) chatConfigResolving = !!s.chatConfigResolving;
}

export { composeView };

export const routes = {
    '/picture-book/:bookObjectId/compose': {
        oninit: function (vnode) { composeView.oninit(vnode); },
        onupdate: function (vnode) { if (vnode.attrs.bookObjectId && vnode.attrs.bookObjectId !== bookObjectId) composeView.oninit(vnode); },
        view: function () { return layout(pageLayout(m(composeView))); }
    },
    '/picture-book/:bookObjectId/compose/:sceneObjectId': {
        oninit: function (vnode) { composeView.oninit(vnode); },
        onupdate: function (vnode) {
            let a = vnode.attrs;
            if ((a.bookObjectId && a.bookObjectId !== bookObjectId) || (a.sceneObjectId && a.sceneObjectId !== sceneObjectId)) composeView.oninit(vnode);
        },
        view: function () { return layout(pageLayout(m(composeView))); }
    },
    '/picture-book/:bookObjectId/cover': {
        oninit: function (vnode) { composeView.oninit(coverVnode(vnode)); },
        onupdate: function (vnode) {
            let a = vnode.attrs;
            if (a.bookObjectId && (a.bookObjectId !== bookObjectId || coverPurpose !== 'COVER')) composeView.oninit(coverVnode(vnode));
        },
        view: function () { return layout(pageLayout(m(composeView))); }
    },
    '/picture-book/:bookObjectId/cover/:purpose': {
        oninit: function (vnode) { composeView.oninit(coverVnode(vnode)); },
        onupdate: function (vnode) {
            let a = vnode.attrs;
            if (a.bookObjectId && (a.bookObjectId !== bookObjectId || (coverPurposeOf(a.purpose) || 'COVER') !== coverPurpose)) composeView.oninit(coverVnode(vnode));
        },
        view: function () { return layout(pageLayout(m(composeView))); }
    }
};

/** The cover routes' vnode for init(): the route params plus mode:'cover' (the bare /cover route has no purpose param = COVER). */
function coverVnode(vnode) {
    let a = vnode.attrs || {};
    return { attrs: { bookObjectId: a.bookObjectId, mode: 'cover', purpose: a.purpose || 'COVER' } };
}
