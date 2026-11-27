import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { renderRange } from '../components/formFieldRenderers.js';
import { listCharacters, tagApparelSceneIndex, mergeCharacters, deleteCharacter, resolveImageUrl, pollJob } from './sceneExtractor.js';
import {
    getAffectedBy, bookPages, renderScene, unpinNode, isPinnedRefusal,
    getStyle, normalizeStyle, styleHasDescription, renderGate, isStyleRequiredRefusal, isChapBookType,
    getWardrobe, addWardrobeEntry, setDefaultApparel, renderApparelReference, migrateSceneTags
} from './pictureBookWorkflow.js';
import { reimage } from './reimage.js';
import { outfitBuilder } from './outfitBuilder.js';
import { registerDirty, unregisterDirty, guardDirty } from '../components/dirtyGuard.js';
import { dirtyMark } from '../components/bookUi.js';
import { chip } from '../components/pageHeader.js';
import { btn } from '../components/buttons.js';
import { editLink, ensureSelection } from './bookWorkspaceNav.js';

/**
 * PictureBook "Manage Characters" workflow — review/edit extracted charPerson records for a book:
 * customize/complete statistics, generate/tag apparel per scene, regenerate a portrait.
 * Rendered inline by the PictureBook wizard's step 3 (initCharacterManager + renderCharacterManagerContent), and as
 * the Edit > Characters section of the book workspace via `charactersSection` (a bookWorkspace SectionAdapter:
 * selection from the route's :item, statistic drafts with explicit Save / Revert under the dirty guard, D4). The
 * PB2 book list / reader header still open the deprecated pop-in, openCharacterManager(bookObjectId,
 * {standalone: true}), until they are migrated (D2) — see openFullEditor for what `standalone` changes.
 *
 * Reuses existing workflows wholesale rather than re-implementing them:
 *  - reimage(entity, inst, opts) — portrait regeneration (same call the generic charPerson
 *                                  "Reimage" command uses, see formDef.js forms.charPerson.commands).
 *  - outfitBuilder(entity, inst) — apparel wizard (same call the generic charPerson
 *                                  "Outfit Builder" command uses).
 * The only genuinely new UI here is the character list, the statistics range-slider panel
 * (via formFieldRenderers.renderRange's plain-contract entry point), and the apparel scene-tag
 * list wired to PictureBookUtil.tagApparelSceneIndex via the new REST endpoint.
 */

const STAT_FIELDS = [
    'physicalStrength', 'physicalEndurance', 'manualDexterity', 'agility', 'speed',
    'mentalStrength', 'mentalEndurance', 'intelligence', 'wisdom', 'perception',
    'creativity', 'spirituality', 'charisma', 'luck'
];

let bookObjectId = null;
let characters = [];
let selectedObjectId = null;
let selectedInst = null;
let loading = false;
// Message of the last failed character-list read (§24 UAT-6): the dialog used to stay on "Loading
// characters…" forever with an unhandled rejection when GET /characters failed (404 for a V3-only book
// before the server fix; any 5xx still). Shown in place of the list; cleared by the next successful read.
let listError = null;
let sceneTagInputs = {}; // apparelObjectId -> string (pending scene index input)
// Merge mode: which duplicates are ticked to fold into the currently-selected character.
let mergeMode = false;
let mergeSelection = {};   // objectId -> true
let merging = false;
let deleting = false;
// Inline name/description edit of the selected character (renderDetail header).
let editingIdentity = false;
let editName = '';
let editDescription = '';
let savingIdentity = false;
// true when the manager was opened on its own (book list / reader header), with no wizard Dialog
// underneath whose in-progress state a route change would discard. Set ONLY from the explicit
// {standalone: true} option passed by the launch site — never inferred from the dialog stack.
let standalone = false;
// V3 (P1-10 S5): "affected scenes" for the selected character. Only in standalone mode (where
// bookObjectId IS the olio.pb.book objectId the V3 routes take).
// getAffectedBy is called once per character-detail open and once after saveIdentity - never per
// row, never on a poll.
let affected = null;          // { forObjectId, loading, data, error }
let lastSaveKind = null;      // 'watched' | 'unwatched' | 'none' after the last saveIdentity (caveat text)
let regen = null;             // { total, done, current, aborted } while "Regenerate" runs
// Whether the book's effective style has a description (null = unknown). A scene render is refused without one
// (server 409), so "Regenerate" is gated on it like the reader's Render; unknown never blocks.
let styleDescribed = null;
let regenPollIntervalMs = undefined; // pollJob default; Vitest shortens it through the test seam
// V3 P4-9: the selected character's wardrobe (GET /{book}/character/{char}/wardrobe), read once per
// detail open and once after each wardrobe write. Only in standalone, like the affected panel.
let wardrobe = null;          // { forObjectId, loading, data: normalizeWardrobe(), error }
let wardrobeBusy = null;      // 'add' | 'migrate' | 'default:<apparelOid>' | 'reference:<apparelOid>' while a write runs
let wardrobeAddText = '';     // the "Add alternate" description input
let wardrobeRuns = {};        // apparelOid -> {jobId, job, error} while a reference render job is watched
let wardrobeAborts = {};      // apparelOid -> AbortController (stops WATCHING only; the server job keeps going)
// Edit › Characters (design §5.4): the manager rendered as a bookWorkspace section. `workspace` implies
// `standalone` (no wizard Dialog underneath) and adds: selection routed through ctx.select (guarded),
// cross-links into the workspace (§4.7), and the dirtyGuard registration of the drafts below.
let workspace = false;
let wsCtx = null;             // the latest SectionAdapter ctx (its select() is what a list click calls)
let pendingItem;              // ctx.item seen before the list finished loading (undefined = none)
// D4: statistic slider drafts, per character. charObjectId -> { name, statsId, statsObjectId, values:{field:v}, orig:{field:v} }.
// A slider move writes here only; Save sends the changed fields in one PATCH; Revert drops the draft.
let statDrafts = {};
let savingStats = false;
// §4.7: the book's /pages rows, read once per workspace section init (only when an affected panel has scenes
// to link), to map the affected-by sceneIndexes onto scene objectIds — affected-by carries indexes only.
let scenePages = null;        // { forBook, loading, data, error }
// Rail status per book: { loaded, count } of characters carrying a warning badge. Survives resetState.
const statusCache = {};
const DIRTY_KEY = 'pb-character';

function resetState() {
    bookObjectId = null;
    characters = [];
    selectedObjectId = null;
    selectedInst = null;
    loading = false;
    listError = null;
    sceneTagInputs = {};
    mergeMode = false;
    mergeSelection = {};
    merging = false;
    deleting = false;
    editingIdentity = false;
    editName = '';
    editDescription = '';
    savingIdentity = false;
    standalone = false;
    affected = null;
    lastSaveKind = null;
    regen = null;
    styleDescribed = null;
    stopWardrobeWatches();
    wardrobe = null;
    wardrobeBusy = null;
    wardrobeAddText = '';
    wardrobeRuns = {};
    workspace = false;
    wsCtx = null;
    pendingItem = undefined;
    statDrafts = {};
    savingStats = false;
    scenePages = null;
    unregisterDirty(DIRTY_KEY);
}

// ── Statistic drafts (D4) ───────────────────────────────────────────────

/** The draft of one character, or null. */
function statDraftOf(charObjectId) {
    let d = charObjectId ? statDrafts[charObjectId] : null;
    return d && Object.keys(d.values).length ? d : null;
}

function anyStatDraft() {
    return Object.keys(statDrafts).some(function (k) { return !!statDraftOf(k); });
}

/** Slider move: record the value against the character's draft; moving back to the stored value clears that field. */
function setStatDraft(entity, field, value) {
    let statistics = entity && entity.statistics;
    if (!statistics) return;
    let oid = entity.objectId;
    let d = statDrafts[oid];
    if (!d) d = statDrafts[oid] = { name: entity.name || oid, statsId: statistics.id, statsObjectId: statistics.objectId, values: {}, orig: {} };
    let stored = statistics[field] != null ? statistics[field] : 0;
    if (value === stored) delete d.values[field];
    else d.values[field] = value;
    m.redraw();
}

function statValue(entity, field) {
    let d = statDraftOf(entity && entity.objectId);
    if (d && field in d.values) return d.values[field];
    let s = entity && entity.statistics;
    return s && s[field] != null ? s[field] : 0;
}

/**
 * Save one character's statistic draft: ONE sparse PATCH of olio.statistics (identity + changed fields only — the
 * same write the slider used to send per move). page.patchObject resolves undefined/false on failure; then the
 * draft is kept and the error toasted (model-api.md: never discard the update result). Returns true on success.
 */
async function saveStatDraft(charObjectId) {
    let d = statDraftOf(charObjectId);
    if (!d) return true;
    if (savingStats) return false;
    let changed = Object.assign({}, d.values);
    let patch = Object.assign({ schema: 'olio.statistics', id: d.statsId, objectId: d.statsObjectId }, changed);
    savingStats = true;
    m.redraw();
    let ok = false;
    try {
        ok = !!(await page.patchObject(patch));
        if (!ok) throw new Error('server rejected the update');
    } catch (e) {
        ok = false;
        page.toast('error', 'Failed to save statistics for ' + d.name + ': ' + ((e && e.message) || e));
    }
    savingStats = false;
    if (ok) {
        // Apply what the server accepted to the loaded record, then drop only those fields from the draft.
        if (selectedInst && selectedInst.entity && selectedInst.entity.objectId === charObjectId && selectedInst.entity.statistics) {
            Object.assign(selectedInst.entity.statistics, changed);
        }
        let cur = statDrafts[charObjectId];
        if (cur) Object.keys(changed).forEach(function (f) { if (cur.values[f] === changed[f]) delete cur.values[f]; });
        if (cur && !Object.keys(cur.values).length) delete statDrafts[charObjectId];
        am7client.clearCache('olio.statistics', true);
        page.toast('success', 'Statistics saved');
    }
    m.redraw();
    return ok;
}

function revertStatDraft(charObjectId) {
    delete statDrafts[charObjectId];
    m.redraw();
}

function identityDirty() {
    return !!(editingIdentity && selectedInst && identityChangeKind(selectedInst.entity, editName, editDescription) !== 'none');
}

function dirtyLabel() {
    let names = Object.keys(statDrafts).filter(function (k) { return !!statDraftOf(k); }).map(function (k) { return statDrafts[k].name; });
    if (identityDirty() && names.indexOf(selectedInst.entity.name) < 0) names.unshift(selectedInst.entity.name);
    return 'Character — ' + (names.length ? names.join(', ') : ((selectedInst && selectedInst.entity && selectedInst.entity.name) || ''));
}

/** dirtyGuard source for Edit › Characters: every unsaved statistic draft plus an open, changed identity edit. */
function registerCharacterDirty() {
    registerDirty(DIRTY_KEY, {
        get label() { return dirtyLabel(); },
        isDirty: function () { return anyStatDraft() || identityDirty(); },
        save: async function () {
            if (identityDirty() && !(await saveIdentity())) return false;
            let ids = Object.keys(statDrafts);
            for (let i = 0; i < ids.length; i++) {
                if (!(await saveStatDraft(ids[i]))) return false;
            }
            return true;
        },
        discard: function () {
            statDrafts = {};
            editingIdentity = false;
            m.redraw();
        }
    });
}

function stopWardrobeWatches() {
    Object.keys(wardrobeAborts).forEach(function (k) {
        try { wardrobeAborts[k].abort(); } catch (_) {}
    });
    wardrobeAborts = {};
}

// ── V3 affected-scenes: pure helpers (exported for Vitest) ──────────────

/** charPerson fields in PbWatchedFields.CHAR_PERSON (watched/v2) - an edit to any of these stales bound nodes. */
const WATCHED_CHAR_FIELDS = ['name', 'firstName', 'lastName', 'gender', 'age', 'race', 'raceLabel', 'ethnicity', 'hairColor', 'eyeColor', 'hairStyle', 'alignment'];

/**
 * What the inline identity editor is about to change: 'watched' (name differs - a watched field),
 * 'unwatched' (only description differs - common.description is NOT in the watched set, nor are
 * narrative.sdPrompt or statistics), or 'none'.
 */
function identityChangeKind(entity, name, description) {
    let ent = entity || {};
    let nameChanged = String(name || '').trim() !== String(ent.name || '');
    let descChanged = String(description || '') !== String(ent.description || '');
    if (nameChanged) return 'watched';
    if (descChanged) return 'unwatched';
    return 'none';
}

/** Caveat line for the affected panel after a save that touched only unwatched fields; null otherwise. */
function affectedCaveat(kind) {
    if (kind !== 'unwatched') return null;
    return 'Description is not a watched field, so this edit does not mark any scene stale. Watched: '
        + WATCHED_CHAR_FIELDS.join(', ') + ' (and the pbDescription attribute). narrative.sdPrompt and statistics are not watched.';
}

/** Normalized GET affected-by result → the numbers the panel shows. */
function affectedSummary(a) {
    a = a || {};
    let idx = Array.isArray(a.affectedSceneIndexes) ? a.affectedSceneIndexes.slice() : [];
    let stale = Array.isArray(a.staleSceneIndexes) ? a.staleSceneIndexes.slice() : [];
    return {
        count: idx.length,
        sceneIndexes: idx,
        staleCount: stale.length,
        boundNodes: a.boundNodes || 0,
        drifted: !!a.drifted,
        driftedHandles: Array.isArray(a.driftedHandles) ? a.driftedHandles : []
    };
}

/** /pages entries for the given scene indexes, in ascending sceneIndex order (missing indexes dropped). */
function scenesForIndexes(pages, indexes) {
    let want = {};
    (Array.isArray(indexes) ? indexes : []).forEach(function (i) { want[Number(i)] = true; });
    return (Array.isArray(pages) ? pages : [])
        .filter(function (p) { return p && p.objectId && want[Number(p.sceneIndex)]; })
        .sort(function (a, b) { return Number(a.sceneIndex) - Number(b.sceneIndex); });
}

function v3Enabled() {
    return (standalone || workspace) && !!bookObjectId;
}

/** Leave for another route through the dirty guard (workspace mode). */
function guardedGo(route) {
    return guardDirty(function () { m.route.set(route); });
}

/** §4.7: the ONE /pages read per workspace section init, so affected sceneIndexes can link to their scenes. */
async function loadScenePages() {
    if (!workspace || !bookObjectId) return;
    if (scenePages && scenePages.forBook === bookObjectId) return;
    let book = bookObjectId;
    scenePages = { forBook: book, loading: true, data: null, error: null };
    try {
        let data = await bookPages(book);
        if (!scenePages || scenePages.forBook !== book) return;
        scenePages.data = Array.isArray(data) ? data : [];
    } catch (e) {
        if (!scenePages || scenePages.forBook !== book) return;
        scenePages.error = (e && e.message) || 'Could not read the book pages';
    }
    scenePages.loading = false;
    m.redraw();
}

/** sceneIndex → scene objectId from the cached /pages rows (null when unknown). */
function sceneObjectIdFor(sceneIndex) {
    if (!scenePages || !Array.isArray(scenePages.data)) return null;
    let hit = scenePages.data.find(function (p) { return p && p.objectId && Number(p.sceneIndex) === Number(sceneIndex); });
    return hit ? hit.objectId : null;
}

function openScene(sceneObjectId) {
    if (!workspace || !sceneObjectId) return;
    guardedGo(editLink('pb', bookObjectId, { type: 'scene', id: sceneObjectId, tab: 'page' }));
}

/** The ONE affected-by read for a character detail (open, or post-save re-open). */
async function loadAffected(charObjectId) {
    if (!v3Enabled() || !charObjectId) { affected = null; return; }
    affected = { forObjectId: charObjectId, loading: true, data: null, error: null };
    // Once per manager session (resetState clears it), not per affected read: the style is the book's, not the character's.
    if (styleDescribed === null) loadStyleDescribed();
    m.redraw();
    try {
        let data = await getAffectedBy(bookObjectId, 'olio.charPerson', charObjectId);
        if (!affected || affected.forObjectId !== charObjectId) return;
        affected.data = data;
    } catch (e) {
        if (!affected || affected.forObjectId !== charObjectId) return;
        affected.error = (e && e.message) || 'Could not load affected scenes';
    }
    affected.loading = false;
    m.redraw();
    if (workspace && affected.data && affectedSummary(affected.data).count) await loadScenePages();
}

/** The book's bookType ('CHAPBOOK' / …) or null when it cannot be read. */
async function readBookType(book) {
    let q = am7client.newQuery('olio.pb.book');
    q.cache(false);
    q.field('objectId', book);
    q.range(0, 1);
    if (q.entity.request.indexOf('bookType') < 0) q.entity.request.push('bookType');
    let qr = await am7client.search(q);
    let rec = qr && qr.results && qr.results[0];
    return rec ? rec.bookType : null;
}

/**
 * Read the book style once per affected read (not awaited; a failure leaves it unknown). A ChapBook is exempt
 * from the style gate (isChapBookType: its look comes from the SD config + per-page overrides), so it reads as
 * unknown (null), which never blocks.
 */
async function loadStyleDescribed() {
    let book = bookObjectId;
    try {
        let typeRead = readBookType(book).catch(function () { return null; });
        let st = normalizeStyle(await getStyle(book));
        if (book !== bookObjectId) return;
        styleDescribed = styleHasDescription(st);
        typeRead.then(function (bt) {
            if (book !== bookObjectId || !isChapBookType(bt)) return;
            styleDescribed = null;
            m.redraw();
        });
    } catch (e) {
        if (book !== bookObjectId) return;
        styleDescribed = null;
    }
    m.redraw();
}

/** Style → the book style view. Standalone only, as openReviewQueue. */
function openBookStyle() {
    if (!v3Enabled()) return;
    if (workspace) { guardedGo(editLink('pb', bookObjectId, { type: 'style' })); return; }
    Dialog.closeAll();
    m.route.set('/picture-book/' + bookObjectId + '/style');
}

/** Review → the book's review queue. Standalone only (the manager is the only Dialog open), as openFullEditor. */
function openReviewQueue() {
    if (!v3Enabled()) return;
    if (workspace) { guardedGo(editLink('pb', bookObjectId, { type: 'review' })); return; }
    Dialog.closeAll();
    m.route.set('/picture-book/' + bookObjectId + '/review');
}

/**
 * Regenerate = serial renderScene over the affected scenes: each POST .../scene/{id}/render?force=true
 * is a 202 + job, and the next scene is submitted only after that job is terminal (the server caps
 * in-flight runs per principal). Force: an affected scene whose node is still current (a
 * description-only edit) is re-rolled rather than refused as "nothing to render". A pinned 409 is
 * shown verbatim with an explicit "Unpin and re-render" confirmation; declining skips that scene. Any
 * other non-2xx (429, in-flight 409, ...) is toasted verbatim and stops the loop - never silently
 * unpins, never retries.
 */
async function regenerateAffected() {
    if (!v3Enabled() || regen || !affected || !affected.data || styleDescribed === false) return;
    let charId = affected.forObjectId;
    let s = affectedSummary(affected.data);
    if (!s.count) return;
    let book = bookObjectId;
    regen = { total: s.count, done: 0, current: null, aborted: false, skipped: 0, failed: 0 };
    m.redraw();
    let scenes = [];
    try {
        scenes = scenesForIndexes(await bookPages(book), s.sceneIndexes);
    } catch (e) {
        page.toast('error', (e && e.message) || 'Could not read the book pages');
        regen = null;
        m.redraw();
        return;
    }
    if (scenes.length !== s.count) {
        page.toast('warning', (s.count - scenes.length) + ' affected scene' + (s.count - scenes.length !== 1 ? 's have' : ' has') + ' no page row and will be skipped');
    }
    regen.total = scenes.length;
    for (let i = 0; i < scenes.length; i++) {
        let pg = scenes[i];
        if (!regen || regen.aborted) break;
        regen.current = pg.sceneIndex;
        m.redraw();
        let submit;
        try {
            submit = await renderScene(book, pg.objectId, { force: true });
        } catch (e) {
            if (!(isPinnedRefusal(e) && pg.sceneNodeObjectId)) {
                if (isStyleRequiredRefusal(e)) styleDescribed = false;
                page.toast('error', (e && e.message) || 'Render failed');
                regen.aborted = true;
                break;
            }
            let ok = await Dialog.confirm({
                title: 'Scene ' + pg.sceneIndex + ' is pinned',
                message: (e.message || '') + ' — Unpin this scene\'s node and re-render it? The pinned image stays in the artifact history.',
                confirmLabel: 'Unpin and re-render', confirmIcon: 'push_pin'
            });
            if (!ok) { regen.skipped++; regen.done++; continue; }
            try {
                await unpinNode(book, pg.sceneNodeObjectId);
                submit = await renderScene(book, pg.objectId, { force: true });
            } catch (e2) {
                page.toast('error', (e2 && e2.message) || 'Render failed');
                regen.aborted = true;
                break;
            }
        }
        if (!submit || !submit.jobId) { regen.failed++; regen.done++; continue; }
        try {
            let job = await pollJob(submit.jobId, { intervalMs: regenPollIntervalMs });
            let r = job && job.result;
            if (job.error || (r && (r.failedNodeCount || String(r.runStatus || '').toUpperCase() === 'FAILED'))) regen.failed++;
        } catch (e3) {
            page.toast('error', (e3 && e3.message) || 'Render polling failed');
            regen.aborted = true;
            break;
        }
        regen.done++;
        m.redraw();
    }
    let r = regen;
    regen = null;
    am7client.clearCache('olio.pb.scene', true);
    if (r && !r.aborted) {
        page.toast(r.failed ? 'warning' : 'success', 'Regenerated ' + (r.done - r.skipped - r.failed) + ' of ' + r.total + ' scene' + (r.total !== 1 ? 's' : '')
            + (r.skipped ? ', ' + r.skipped + ' skipped (pinned)' : '') + (r.failed ? ', ' + r.failed + ' failed' : ''));
    }
    // The run just changed what the scan sees for this character: one re-read, on completion of the
    // user's action (not a poll).
    if (selectedObjectId === charId) await loadAffected(charId);
    m.redraw();
}

/** "Affected scenes: N [Review] [Regenerate]" - V3 + standalone only; null otherwise. */
function renderAffectedPanel() {
    if (!v3Enabled() || !selectedInst) return null;
    let a = affected && affected.forObjectId === selectedInst.entity.objectId ? affected : null;
    let caveat = affectedCaveat(lastSaveKind);
    let body;
    if (!a || a.loading) {
        body = m('span', { class: 'text-xs text-gray-500' }, 'Checking affected scenes…');
    } else if (a.error) {
        body = m('span', { 'data-testid': 'pbchar-affected-error', class: 'text-xs text-red-500' }, a.error);
    } else {
        let s = affectedSummary(a.data);
        let gate = renderGate({ styleDescribed: styleDescribed });
        body = m('div', { class: 'flex items-center gap-2 flex-wrap text-sm' }, [
            workspace && s.count
                ? m('span', { 'data-testid': 'pbchar-affected-count', 'data-count': String(s.count) }, [
                    'Affected scenes: ' + s.count + ' (',
                    s.sceneIndexes.map(function (idx, i) {
                        let sid = sceneObjectIdFor(idx);
                        let sep = i ? ', ' : '';
                        if (!sid) return [sep, m('span', { 'data-testid': 'pbchar-affected-scene-' + idx, title: scenePages && scenePages.loading ? 'Resolving scene…' : 'No page row for this scene' }, String(idx))];
                        return [sep, m('a', {
                            href: '#',
                            'data-testid': 'pbchar-affected-scene-' + idx,
                            'data-scene-objectid': sid,
                            class: 'text-blue-600 dark:text-blue-400 hover:underline',
                            title: 'Open scene ' + idx + ' in Edit › Scenes',
                            onclick: function (e) { e.preventDefault(); openScene(sid); }
                        }, String(idx))];
                    }),
                    ')'
                ])
                : m('span', { 'data-testid': 'pbchar-affected-count', 'data-count': String(s.count) },
                    'Affected scenes: ' + s.count + (s.count ? ' (' + s.sceneIndexes.join(', ') + ')' : '')),
            s.drifted ? m('span', { 'data-testid': 'pbchar-affected-drifted', class: 'text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200', title: 'Nodes: ' + s.driftedHandles.join(', ') },
                s.staleCount + ' stale') : null,
            m('button', {
                'data-testid': 'pbchar-affected-review',
                class: 'btn btn-sm',
                disabled: !!regen,
                onclick: openReviewQueue
            }, 'Review'),
            m('button', {
                'data-testid': 'pbchar-affected-regenerate',
                class: 'btn btn-sm',
                disabled: !!regen || !s.count || !gate.enabled,
                title: !gate.enabled ? gate.reason : (s.count ? 'Re-render every affected scene, one at a time' : 'No scene binds this character'),
                onclick: regenerateAffected
            }, regen ? ('Regenerating ' + (regen.done + 1) + ' of ' + regen.total + (regen.current != null ? ' (scene ' + regen.current + ')' : '') + '…') : 'Regenerate'),
            gate.needsStyle && s.count ? m('button', {
                'data-testid': 'pbchar-affected-needs-style',
                class: 'text-xs text-amber-700 dark:text-amber-300 underline',
                title: 'Open Book style',
                onclick: openBookStyle
            }, gate.reason) : null
        ]);
    }
    return m('div', { 'data-testid': 'pbchar-affected', class: 'p-2 rounded border border-gray-200 dark:border-gray-700' }, [
        body,
        caveat ? m('div', { 'data-testid': 'pbchar-affected-caveat', class: 'text-xs text-gray-500 mt-1' }, caveat) : null
    ]);
}

// ── V3 wardrobe (P4-9) ──────────────────────────────────────────────────

/** Pure: "Default" / "scene N" / "alternate" label of a wardrobe entry. */
function wardrobeEntryKind(e) {
    if (!e) return 'alternate';
    if (e.isDefault) return 'default';
    if (e.sceneIndex != null) return 'scene ' + e.sceneIndex;
    return 'alternate';
}

/** Pure: the wearable names of an entry, comma-joined, or '' when the facade listed none. */
function wearablesText(e) {
    let list = e && Array.isArray(e.wearables) ? e.wearables : [];
    return list.map(function (w) { return w && w.name ? String(w.name) : ''; }).filter(Boolean).join(', ');
}

/** Pure: the "Set default" result sentence (PUT .../default body). */
function setDefaultText(r) {
    r = r || {};
    if (!r.changed) return 'Already the default outfit';
    return 'Default outfit set' + (r.staled ? ' — ' + r.staled + ' scene' + (r.staled !== 1 ? 's' : '') + ' marked stale' : ' — no scene wore the previous default');
}

/** Pure: the migrate-scene-tags result sentence (POST .../wardrobe/migrate-scene-tags body). */
function migrateText(r) {
    r = r || {};
    let skipped = (r.skippedPinned || 0) + (r.skippedNoActor || 0) + (r.skippedNoScene || 0) + (r.skippedNoComposition || 0);
    return 'Scanned ' + (r.scanned || 0) + ' tagged apparel: pinned ' + (r.pinned || 0)
        + (skipped ? ', skipped ' + skipped + ' (' + [r.skippedPinned ? r.skippedPinned + ' already pinned' : '', r.skippedNoActor ? r.skippedNoActor + ' no actor' : '',
            r.skippedNoScene ? r.skippedNoScene + ' no scene' : '', r.skippedNoComposition ? r.skippedNoComposition + ' no composition' : ''].filter(Boolean).join(', ') + ')' : '')
        + (r.ambiguous ? ', ' + r.ambiguous + ' ambiguous' : '');
}

/** The ONE wardrobe read for a character detail (open, or after a wardrobe write). */
async function loadWardrobe(charObjectId) {
    if (!v3Enabled() || !charObjectId) { wardrobe = null; return; }
    wardrobe = { forObjectId: charObjectId, loading: true, data: null, error: null };
    m.redraw();
    try {
        let data = await getWardrobe(bookObjectId, charObjectId);
        if (!wardrobe || wardrobe.forObjectId !== charObjectId) return;
        wardrobe.data = data;
    } catch (e) {
        if (!wardrobe || wardrobe.forObjectId !== charObjectId) return;
        wardrobe.error = (e && e.message) || 'Could not load the wardrobe';
    }
    wardrobe.loading = false;
    m.redraw();
}

/** One wardrobe write under a busy key: toast the result (or the server's message verbatim), then the one re-read. */
async function wardrobeAct(key, fn, successText) {
    if (wardrobeBusy || !v3Enabled() || !selectedObjectId) return null;
    let charId = selectedObjectId;
    wardrobeBusy = key;
    m.redraw();
    let out = null;
    try {
        out = await fn();
        let text = typeof successText === 'function' ? successText(out) : successText;
        if (text) page.toast('success', text);
        if (out && Array.isArray(out.warnings) && out.warnings.length) page.toast('warning', out.warnings.join(' · '));
    } catch (e) {
        page.toast('error', (e && e.message) || String(e));
    }
    wardrobeBusy = null;
    if (selectedObjectId === charId) await loadWardrobe(charId);
    m.redraw();
    return out;
}

function doAddWardrobeEntry() {
    let text = String(wardrobeAddText || '').trim();
    if (!text) { page.toast('warning', 'Describe the outfit first'); return; }
    wardrobeAct('add', function () { return addWardrobeEntry(bookObjectId, selectedObjectId, text); }, function (r) {
        wardrobeAddText = '';
        return r.reused ? 'That outfit is already in the wardrobe: ' + (r.name || '')
            : 'Added ' + (r.name || 'outfit') + ' (' + r.wearables + ' wearable' + (r.wearables !== 1 ? 's' : '') + (r.unmatched.length ? ', unmatched: ' + r.unmatched.join(', ') : '') + ')';
    });
}

function doSetDefault(entry) {
    if (!entry || !entry.objectId) return;
    wardrobeAct('default:' + entry.objectId, function () { return setDefaultApparel(bookObjectId, selectedObjectId, entry.objectId); }, setDefaultText);
}

function doMigrateSceneTags() {
    wardrobeAct('migrate', function () { return migrateSceneTags(bookObjectId); }, migrateText);
}

/** Submit the reference render (202 + job) and watch it; the image lands in apparel.gallery, so the wardrobe is re-read on terminal. */
async function doRenderReference(entry) {
    if (!entry || !entry.objectId || wardrobeRuns[entry.objectId] || !v3Enabled()) return;
    let charId = selectedObjectId;
    let oid = entry.objectId;
    let submit;
    try {
        submit = await renderApparelReference(bookObjectId, charId, oid);
    } catch (e) {
        page.toast('error', (e && e.message) || 'Reference render failed');
        return;
    }
    if (!submit || !submit.jobId) {
        page.toast('error', 'Reference render was accepted but no job id was returned');
        return;
    }
    let ctl = new AbortController();
    wardrobeAborts[oid] = ctl;
    wardrobeRuns[oid] = { jobId: submit.jobId, job: null, error: null };
    m.redraw();
    try {
        let job = await pollJob(submit.jobId, {
            signal: ctl.signal,
            intervalMs: regenPollIntervalMs,
            onProgress: function (j) { if (wardrobeRuns[oid]) { wardrobeRuns[oid].job = j; m.redraw(); } }
        });
        let r = job && job.result;
        let failed = !!(job && (job.error || job.cancelled)) || !!(r && r.error);
        page.toast(failed ? 'warning' : 'success', failed
            ? 'Reference render failed: ' + ((job && job.error) || (r && r.error) || 'cancelled')
            : 'Reference image rendered' + (r && r.imageCount != null ? ' (' + r.imageCount + ' image' + (r.imageCount !== 1 ? 's' : '') + ')' : ''));
    } catch (e) {
        if (!(e && e.name === 'AbortError')) page.toast('error', (e && e.message) || 'Reference render polling failed');
    }
    delete wardrobeRuns[oid];
    if (wardrobeAborts[oid] === ctl) delete wardrobeAborts[oid];
    am7client.clearCache('olio.apparel', true);
    am7client.clearCache('data.data', true);
    if (selectedObjectId === charId) await loadWardrobe(charId);
    m.redraw();
}

/** Reference thumbnail of a wardrobe entry: the newest gallery image, resolved the way portraits are. */
const WardrobeThumb = {
    oninit: function (vnode) {
        vnode.state.url = null;
        vnode.state.forObjectId = vnode.attrs.objectId;
        resolveImageUrl(vnode.attrs.objectId).then(function (u) { vnode.state.url = u; m.redraw(); });
    },
    onupdate: function (vnode) {
        if (vnode.attrs.objectId === vnode.state.forObjectId) return;
        let oid = vnode.attrs.objectId;
        vnode.state.forObjectId = oid;
        vnode.state.url = null;
        resolveImageUrl(oid).then(function (u) { if (vnode.state.forObjectId === oid) { vnode.state.url = u; m.redraw(); } });
    },
    view: function (vnode) {
        return vnode.state.url
            ? m('img', { 'data-testid': 'pb-wd-thumb', src: vnode.state.url, class: 'w-16 h-16 object-cover rounded border border-gray-200 dark:border-gray-700' })
            : m('div', { 'data-testid': 'pb-wd-thumb-loading', class: 'w-16 h-16 rounded bg-gray-100 dark:bg-gray-800 flex items-center justify-center' },
                m('span', { class: 'material-symbols-outlined text-gray-400' }, 'image'));
    }
};

function renderWardrobeEntry(e) {
    let kind = wardrobeEntryKind(e);
    let run = wardrobeRuns[e.objectId];
    let busyDefault = wardrobeBusy === 'default:' + e.objectId;
    let wear = wearablesText(e);
    return m('div', {
        'data-testid': 'pb-wd-entry-' + e.objectId,
        'data-apparel-objectid': e.objectId,
        'data-default': e.isDefault ? '1' : '0',
        class: 'flex items-start gap-3 p-2 rounded border ' + (e.isDefault ? 'border-purple-300 dark:border-purple-700 bg-purple-50/40 dark:bg-purple-900/10' : 'border-gray-200 dark:border-gray-700')
    }, [
        e.referenceObjectId
            ? m(WardrobeThumb, { objectId: e.referenceObjectId })
            : m('div', { 'data-testid': 'pb-wd-thumb-none', class: 'w-16 h-16 rounded bg-gray-100 dark:bg-gray-800 flex items-center justify-center', title: 'No reference image yet' },
                m('span', { class: 'material-symbols-outlined text-gray-400' }, 'checkroom')),
        m('div', { class: 'flex-1 min-w-0 text-sm' }, [
            m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
                m('span', { class: 'font-medium truncate' }, e.name || '(apparel)'),
                e.isDefault ? m('span', { 'data-testid': 'pb-wd-default-chip', class: 'text-xs px-1.5 py-0.5 rounded bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200' }, 'Default')
                    : m('span', { 'data-testid': 'pb-wd-kind-chip', class: 'text-xs px-1.5 py-0.5 rounded bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300' }, kind),
                e.gallery.length ? m('span', { class: 'text-xs text-gray-500' }, e.gallery.length + ' image' + (e.gallery.length !== 1 ? 's' : '')) : null
            ]),
            e.description ? m('div', { class: 'text-xs text-gray-600 dark:text-gray-300 mt-0.5 line-clamp-2' }, e.description) : null,
            m('div', { 'data-testid': 'pb-wd-wearables', class: 'text-xs text-gray-500 mt-0.5' }, wear ? wear : 'No wearables listed'),
            run ? m('div', { 'data-testid': 'pb-wd-render-progress', class: 'text-xs text-blue-600 dark:text-blue-300 mt-0.5' },
                'Rendering reference… ' + (run.job && run.job.phase ? run.job.phase : 'submitted')) : null
        ]),
        m('div', { class: 'flex flex-col gap-1 flex-shrink-0' }, [
            m('button', {
                'data-testid': 'pb-wd-set-default',
                class: 'btn btn-sm',
                disabled: e.isDefault || !!wardrobeBusy,
                title: e.isDefault ? 'This is the default outfit' : 'Make this the outfit scenes wear unless an actor pins another',
                onclick: function () { doSetDefault(e); }
            }, busyDefault ? 'Setting…' : 'Set default'),
            m('button', {
                'data-testid': 'pb-wd-render-ref',
                class: 'btn btn-sm',
                disabled: !!run || !!wardrobeBusy,
                title: 'Render a reference image of this outfit on the character (a job; the image lands in the apparel gallery)',
                onclick: function () { doRenderReference(e); }
            }, run ? 'Rendering…' : (e.referenceObjectId ? 'Re-render reference' : 'Render reference')),
            m('a', {
                href: '#', class: 'text-xs text-blue-600 dark:text-blue-400 hover:underline text-center',
                onclick: function (ev) { ev.preventDefault(); openInNewTab('olio.apparel', e.objectId); }
            }, 'Open →')
        ])
    ]);
}

/**
 * "Wardrobe" panel of a character (V3 + standalone only; null otherwise): the default outfit, the
 * alternates (with their scene tag when they came from the legacy scene-index tags), a reference
 * thumbnail per entry, Set default / Render reference per entry, Add alternate (LLM), and the book-wide
 * "Pin scene tags" migration. References are rendered, never uploaded (no upload route exists), and
 * there is no review/approve step for them (design §6.1: not built).
 */
function renderWardrobePanel() {
    if (!v3Enabled() || !selectedInst) return null;
    let w = wardrobe && wardrobe.forObjectId === selectedInst.entity.objectId ? wardrobe : null;
    let body;
    if (!w || w.loading) {
        body = m('div', { 'data-testid': 'pb-wd-loading', class: 'text-xs text-gray-500' }, 'Loading wardrobe…');
    } else if (w.error) {
        body = m('div', { 'data-testid': 'pb-wd-error', class: 'text-xs text-red-500' }, w.error);
    } else {
        let d = w.data;
        body = m('div', { class: 'flex flex-col gap-2' }, [
            d.entries.length
                ? m('div', { 'data-testid': 'pb-wd-entries', 'data-count': String(d.entries.length), class: 'flex flex-col gap-2' }, d.entries.map(renderWardrobeEntry))
                : m('div', { 'data-testid': 'pb-wd-empty', class: 'text-xs text-gray-500' }, 'No outfits in the wardrobe yet.'),
            d.warnings.length ? m('div', { 'data-testid': 'pb-wd-warnings', class: 'text-xs text-amber-700 dark:text-amber-300' }, d.warnings.join(' · ')) : null,
            m('div', { class: 'flex items-center gap-2 mt-1' }, [
                m('input', {
                    type: 'text',
                    'data-testid': 'pb-wd-add-text',
                    class: 'text-field-compact flex-1',
                    placeholder: 'Describe an alternate outfit (e.g. a grey wool coat over a red dress)',
                    value: wardrobeAddText,
                    disabled: !!wardrobeBusy,
                    oninput: function (ev) { wardrobeAddText = ev.target.value; },
                    onkeydown: function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); doAddWardrobeEntry(); } }
                }),
                m('button', {
                    'data-testid': 'pb-wd-add',
                    class: 'btn btn-sm',
                    disabled: !!wardrobeBusy || !String(wardrobeAddText || '').trim(),
                    title: 'Build the outfit from this description with the LLM and add it as an alternate',
                    onclick: doAddWardrobeEntry
                }, wardrobeBusy === 'add' ? 'Adding…' : 'Add alternate')
            ]),
            m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
                m('button', {
                    'data-testid': 'pb-wd-migrate',
                    class: 'btn btn-sm',
                    disabled: !!wardrobeBusy,
                    title: 'Pin every legacy "scene N" apparel tag of this book onto the matching scene actor (idempotent)',
                    onclick: doMigrateSceneTags
                }, wardrobeBusy === 'migrate' ? 'Pinning…' : 'Pin scene tags to actors'),
                m('span', { 'data-testid': 'pb-wd-note', class: 'text-xs text-gray-500' },
                    'References are rendered on the character, not uploaded; there is no review step for them yet.')
            ])
        ]);
    }
    return m('div', { 'data-testid': 'pb-wd', class: 'p-2 rounded border border-gray-200 dark:border-gray-700' }, [
        m('div', { class: 'font-medium mb-1' }, 'Wardrobe'),
        body
    ]);
}

/** The warning badges a character row carries (no portrait / no apparel / apparel failed / stats failed). */
function characterWarnings(c) {
    c = c || {};
    let w = [];
    if (!c.hasPortrait) w.push('no portrait');
    if (!c.apparelCount) w.push('no apparel');
    if (c.failedApparel) w.push('apparel failed');
    if (c.failedStatistics) w.push('stats failed');
    return w;
}

function warningCount(list) {
    return (Array.isArray(list) ? list : []).filter(function (c) { return characterWarnings(c).length > 0; }).length;
}

function noteStatus(book, list) {
    if (!book) return;
    statusCache[book] = { loaded: true, count: warningCount(list), total: Array.isArray(list) ? list.length : 0 };
}

function charactersStatusFor(theBookObjectId) {
    let st = statusCache[theBookObjectId];
    if (!st || !st.loaded) return null;
    return {
        kind: 'count',
        count: st.count,
        title: st.count ? st.count + ' character' + (st.count !== 1 ? 's need' : ' needs') + ' attention' : 'Every character has a portrait and apparel'
    };
}

/**
 * Rail status for Edit > Characters, for the workspace's onEnter: the same GET /{book}/characters list read the
 * manager makes (no new endpoint). Resolves the status; a failed read leaves it unknown (null) and never throws.
 */
async function loadCharactersStatus(theBookObjectId) {
    if (!theBookObjectId) return null;
    try {
        let list = await listCharacters(theBookObjectId);
        noteStatus(theBookObjectId, list);
    } catch (e) {
        return null;
    }
    m.redraw();
    return charactersStatusFor(theBookObjectId);
}

async function refreshList() {
    try {
        characters = await listCharacters(bookObjectId);
        listError = null;
        noteStatus(bookObjectId, characters);
    } catch (e) {
        // §24 UAT-6: never leave the dialog on "Loading characters…" with an unhandled rejection. The list
        // is emptied (a stale list after a failed refresh would misreport what the server holds), the
        // reason is shown in the list column and toasted once.
        characters = [];
        listError = (e && e.message) || String(e);
        page.toast('error', 'Could not load characters: ' + listError);
    }
    m.redraw();
}

async function selectCharacter(objectId) {
    if (objectId !== selectedObjectId) lastSaveKind = null;   // the caveat belongs to the character just saved
    selectedObjectId = objectId;
    selectedInst = null;
    editingIdentity = false;
    affected = null;
    wardrobe = null;
    wardrobeAddText = '';
    m.redraw();
    let entity = await am7client.getFull('olio.charPerson', objectId);
    if (!entity) {
        page.toast('error', 'Failed to load character');
        return;
    }
    selectedInst = am7model.prepareInstance(entity, am7model.forms.charPerson);
    m.redraw();
    // Once per detail open (saveIdentity re-opens the detail, which is its one post-save read).
    await Promise.all([loadAffected(objectId), loadWardrobe(objectId)]);
}

function beginEditIdentity() {
    if (!selectedInst) return;
    editName = selectedInst.entity.name || '';
    editDescription = selectedInst.entity.description || '';
    editingIdentity = true;
    m.redraw();
}

function cancelEditIdentity() {
    editingIdentity = false;
    m.redraw();
}

/**
 * Save the inline name/description edit. PATCH idiom: schema + identity + changed fields, and `name`
 * is ALWAYS present — charPerson inherits common.nameId whose `\S` rule is validated on the patch
 * record itself, so a patch without name is rejected (model-api.md). page.patchObject resolves
 * undefined on failure (am7client.patch swallows the rejection), so treat a falsy result as failure.
 */
async function saveIdentity() {
    if (!selectedInst || savingIdentity) return false;
    let ent = selectedInst.entity;
    let name = (editName || '').trim();
    if (!name) {
        page.toast('error', 'Name is required');
        return false;
    }
    let desc = editDescription || '';
    let patch = { schema: 'olio.charPerson', id: ent.id, objectId: ent.objectId, name: name };
    if (desc !== (ent.description || '')) patch.description = desc;
    let changeKind = identityChangeKind(ent, name, desc);
    savingIdentity = true;
    m.redraw();
    let saved = false;
    try {
        let ok = await page.patchObject(patch);
        if (!ok) throw new Error('server rejected the update');
        // Drop any cached charPerson reads (search results are keyed per type) before re-reading, so
        // the detail and the list both reflect the new values rather than the cached record.
        am7client.clearCache('olio.charPerson', true);
        editingIdentity = false;
        page.toast('success', 'Character saved');
        // selectCharacter re-reads the detail and makes the ONE post-save affected-by call; the
        // caveat is kept for its panel when only unwatched fields changed.
        affected = null;
        lastSaveKind = changeKind;
        saved = true;
        await selectCharacter(ent.objectId);
        await refreshList();
    } catch (e) {
        page.toast('error', 'Failed to save character: ' + (e.message || e));
    }
    savingIdentity = false;
    m.redraw();
    return saved;
}

async function doReimage() {
    if (!selectedInst) return;
    // reimage() resolves when its dialog OPENS, not when Generate finishes (§6.C 267 i: the list's
    // `no portrait` badge stayed after "Created 1 image(s)"). reimage's Generate action mutates
    // selectedInst.entity.profile.portrait and patches the profile itself; the onImages callback runs
    // after that patch, so this is where the list is re-read for the server's hasPortrait.
    await reimage(selectedInst.entity, selectedInst, {
        onImages: async function () {
            await refreshList();
        }
    });
    m.redraw();
}

async function doOutfitBuilder() {
    if (!selectedInst) return;
    await outfitBuilder(selectedInst.entity, selectedInst);
    // Re-fetch the character so the apparel list picks up the newly-generated outfit.
    await selectCharacter(selectedObjectId);
    await refreshList();
}

/**
 * Fold the ticked duplicates into the selected character.
 *
 * The selected character is the KEEPER — "use just the first version" — so this screen never asks
 * which one survives in the abstract: you pick the good one, then tick the duplicates.
 *
 * Confirmed before firing, because it deletes records and rewrites every scene in the book. The
 * confirmation names the characters rather than saying "these items": a wrong merge moves scenes
 * onto the wrong person and is not undoable from here.
 */
async function doMergeSelected() {
    if (!selectedObjectId || merging) return;
    let dropIds = Object.keys(mergeSelection).filter(function (k) { return mergeSelection[k]; });
    if (!dropIds.length) {
        page.toast('error', 'Tick at least one duplicate to merge');
        return;
    }
    let keepName = (characters.find(function (c) { return c.objectId === selectedObjectId; }) || {}).name
        || selectedObjectId;
    let dropNames = dropIds.map(function (id) {
        let c = characters.find(function (x) { return x.objectId === id; });
        return (c && c.name) || id;
    });
    let ok = await Dialog.confirm({
        title: 'Merge Characters',
        message: 'Merge ' + dropNames.join(', ') + ' into "' + keepName + '"? '
            + 'Every scene that references ' + (dropNames.length > 1 ? 'them' : 'it')
            + ' will be repointed to "' + keepName + '", and '
            + (dropNames.length > 1 ? 'those characters' : 'that character')
            + ' will be deleted along with their portrait, statistics and wardrobe. This cannot be undone.',
        confirmLabel: 'Merge',
        confirmIcon: 'merge',
        destructive: true
    });
    if (!ok) return;

    merging = true;
    m.redraw();
    try {
        let result = await mergeCharacters(bookObjectId, selectedObjectId, dropIds);
        // Report what the server says actually moved — a merge that repointed no scenes is worth
        // seeing, not hiding behind a generic "done".
        let msg = 'Merged ' + (result.mergedNames || dropNames).join(', ') + ' into "'
            + (result.keptName || keepName) + '" (' + (result.scenesRepointed || 0) + ' scene'
            + ((result.scenesRepointed === 1) ? '' : 's') + ' repointed)';
        if (result.failedDeletes && result.failedDeletes.length) {
            page.toast('error', msg + ' — but could not delete: ' + result.failedDeletes.join(', '));
        }
        else {
            page.toast('success', msg);
        }
        mergeMode = false;
        mergeSelection = {};
        dropIds.forEach(function (id) { delete statDrafts[id]; });
        await refreshList();
    } catch (e) {
        page.toast('error', 'Merge failed: ' + (e.message || e));
    }
    merging = false;
    m.redraw();
}

/**
 * Delete the selected character outright and detach it from every scene.
 *
 * Extraction sometimes surfaces an animal, an expression, or a turn of phrase as a "character".
 * Those are not duplicates of anyone, so merge is the wrong tool — there is nothing to fold them
 * into. Delete removes the record and drops it from every scene, meta entry and pipeline binding
 * that referenced it, so a later render does not try to paint a portrait for "a sigh".
 */
async function doDeleteSelected() {
    if (!selectedObjectId || deleting || merging) return;
    let name = (characters.find(function (c) { return c.objectId === selectedObjectId; }) || {}).name
        || (selectedInst && selectedInst.entity && selectedInst.entity.name)
        || selectedObjectId;
    let ok = await Dialog.confirm({
        title: 'Delete Character',
        message: 'Delete "' + name + '"? It will be removed from every scene that references it, and '
            + 'deleted along with its portrait, statistics and wardrobe. This cannot be undone.',
        confirmLabel: 'Delete',
        confirmIcon: 'delete',
        destructive: true
    });
    if (!ok) return;

    deleting = true;
    m.redraw();
    try {
        let result = await deleteCharacter(bookObjectId, selectedObjectId);
        let n = (result && result.scenesDetached) || 0;
        let msg = 'Deleted "' + ((result && result.deletedName) || name) + '" (detached from '
            + n + ' scene' + (n === 1 ? '' : 's') + ')';
        if (result && result.deleted === false) {
            page.toast('error', msg + ' — but the character record itself could not be deleted');
        }
        else {
            page.toast('success', msg);
        }
        delete mergeSelection[selectedObjectId];
        delete statDrafts[selectedObjectId];
        selectedObjectId = null;
        selectedInst = null;
        await refreshList();
        if (workspace) applyItem(null);
    } catch (e) {
        page.toast('error', 'Delete failed: ' + (e.message || e));
    }
    deleting = false;
    m.redraw();
}

function toggleMergeMode() {
    mergeMode = !mergeMode;
    mergeSelection = {};
    m.redraw();
}

/**
 * Open a record's full generic editor in a new tab rather than navigating the current one: when
 * this screen renders inside the PictureBook wizard's Dialog (either inline at Step 3, or as the
 * Steps 4/5 stacked popup) there's no route-based back nav that would restore the in-progress
 * wizard state (extracted scenes, created book/characters, etc.) if we navigated away in place.
 * A new tab leaves the wizard exactly as it is. Used for apparel records and for the character
 * itself (openFullEditor).
 */
function openInNewTab(type, objectId) {
    let url = window.location.origin + window.location.pathname + '#!/view/' + type + '/' + objectId;
    window.open(url, '_blank');
}

/**
 * Open the full generic charPerson editor for a character — gives access to every field
 * (including anything this screen doesn't expose a dedicated panel for, e.g. a manually-edited
 * narrative.sdPrompt/outfitDescription) without duplicating the generic editor here.
 *
 * Always opens in a new tab (Stephen, 2026-11-27), in every launch mode — the Edit > Characters
 * workspace, the standalone manager, and the wizard. The current tab is never navigated, so
 * unsaved stat drafts, the wizard's in-progress state and the open manager all stay as they
 * are, and the unsaved-changes guard is not needed for this link.
 */
function openFullEditor(entity) {
    let type = entity[am7model.jsonModelKey] || 'olio.charPerson';
    openInNewTab(type, entity.objectId);
}

async function tagApparel(apparelObjectId) {
    let raw = sceneTagInputs[apparelObjectId];
    let sceneIndex = parseInt(raw, 10);
    if (isNaN(sceneIndex) || sceneIndex < 0) {
        page.toast('error', 'Enter a valid scene number (0 or higher)');
        return;
    }
    try {
        await tagApparelSceneIndex(selectedObjectId, apparelObjectId, sceneIndex);
        page.toast('success', 'Apparel tagged for scene ' + sceneIndex);
        await selectCharacter(selectedObjectId);
        await refreshList();
    } catch (e) {
        page.toast('error', 'Failed to tag apparel: ' + (e.message || e));
    }
}

/**
 * A list click. In Edit > Characters it goes through ctx.select (routed, so the dirty guard runs and the URL names
 * the character); the section's select() then loads it. Elsewhere (wizard step 3, the deprecated pop-in) it selects
 * directly: statistic drafts are kept per character there, so switching does not lose them.
 */
function pickCharacter(objectId) {
    if (workspace && wsCtx && typeof wsCtx.select === 'function') {
        wsCtx.select(objectId);
        return;
    }
    selectCharacter(objectId);
}

function renderCharacterListItem(c) {
    let isSelected = c.objectId === selectedObjectId;
    let badges = characterWarnings(c).map(function (w) {
        let red = w === 'apparel failed' || w === 'stats failed';
        return m('span', { class: 'text-xs ' + (red ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400') }, w);
    });
    // design 6.2-2: an "unsaved" chip on a row whose character holds a statistic draft.
    if (statDraftOf(c.objectId)) badges.push(chip('unsaved', 'amber', { 'data-testid': 'pbchar-row-unsaved' }));

    // In merge mode the KEEPER is whichever character is selected, so it gets no checkbox — you
    // cannot merge someone into themselves, and offering the box would only invite the attempt.
    let checkbox = (mergeMode && !isSelected)
        ? m('input', {
            type: 'checkbox',
            class: 'mr-2',
            checked: !!mergeSelection[c.objectId],
            onclick: function (e) { e.stopPropagation(); },
            onchange: function (e) {
                mergeSelection[c.objectId] = e.target.checked;
                m.redraw();
            }
        })
        : null;

    return m('div', {
        class: 'px-3 py-2 rounded cursor-pointer border ' +
            (isSelected ? 'border-blue-500 bg-blue-50 dark:bg-blue-950' : 'border-transparent hover:bg-gray-100 dark:hover:bg-gray-800'),
        'data-char-item': c.objectId,
        onclick: function () { if (!mergeMode) pickCharacter(c.objectId); }
    }, [
        m('div', { class: 'flex items-center' }, [
            checkbox,
            m('div', { class: 'font-medium text-sm' }, c.name || '(unnamed)'),
            (mergeMode && isSelected)
                ? m('span', { class: 'ml-2 text-xs text-blue-600 dark:text-blue-400' }, '(keep)')
                : null
        ]),
        m('div', { class: 'flex gap-2 mt-1' }, badges)
    ]);
}

/**
 * Statistics: range sliders over a per-character draft (D4: they no longer save on input). A changed slider gets a
 * dirtyMark; Save commits the changed fields in one sparse PATCH, Revert drops the draft.
 */
function renderStatisticsPanel() {
    let entity = selectedInst.entity;
    let statistics = entity.statistics;
    if (!statistics) return m('div', { class: 'text-sm text-gray-500' }, 'No statistics record.');
    let draft = statDraftOf(entity.objectId);
    return m('div', { class: 'grid grid-cols-2 gap-2', 'data-testid': 'pbchar-stats' }, STAT_FIELDS.map(function (field) {
        let value = statValue(entity, field);
        let changed = !!(draft && field in draft.values);
        return m('div', { class: 'flex flex-col', 'data-stat': field }, [
            m('label', { class: 'text-xs text-gray-500' }, [field + ': ' + value, dirtyMark(changed, 'pbchar-stat-dirty-' + field)]),
            renderRange({
                value: value,
                min: 0, max: 20, step: 1,
                name: field,
                onInput: function (e) {
                    let v = parseInt(e.target.value, 10);
                    if (isNaN(v)) return;
                    setStatDraft(entity, field, v);
                }
            })
        ]);
    }));
}

function renderStatisticsActions() {
    let entity = selectedInst && selectedInst.entity;
    if (!entity || !entity.statistics) return null;
    let dirty = !!statDraftOf(entity.objectId);
    return m('div', { class: 'flex items-center gap-2' }, [
        btn({ label: 'Revert', icon: 'undo', size: 'sm', disabled: !dirty || savingStats, onclick: function () { revertStatDraft(entity.objectId); }, attrs: { 'data-testid': 'pbchar-stats-revert' } }),
        btn({ label: savingStats ? 'Saving…' : 'Save', icon: 'save', size: 'sm', kind: 'primary', disabled: !dirty || savingStats, onclick: function () { saveStatDraft(entity.objectId); }, attrs: { 'data-testid': 'pbchar-stats-save' } })
    ]);
}

function renderApparelPanel() {
    let store = selectedInst.entity.store;
    let apparelList = (store && store.apparel) ? store.apparel : [];
    if (!apparelList.length) {
        return m('div', { class: 'text-sm text-gray-500' }, 'No apparel yet.');
    }
    return m('div', { class: 'flex flex-col gap-2' }, apparelList.map(function (a) {
        let sceneIndex = null;
        if (a.attributes) {
            let attr = a.attributes.find(function (x) { return x.name === 'sceneIndex'; });
            if (attr) sceneIndex = attr.value;
        }
        return m('div', { class: 'flex items-center gap-2 p-2 border border-gray-200 dark:border-gray-700 rounded' }, [
            m('div', { class: 'flex-1 text-sm' }, [
                m('div', a.name || '(apparel)'),
                m('div', { class: 'text-xs text-gray-500' },
                    (a.inuse ? 'in use' : 'not in use') + (sceneIndex != null ? ' — scene ' + sceneIndex : ' — untagged'))
            ]),
            m('a', {
                href: '#', class: 'text-xs text-blue-600 dark:text-blue-400 hover:underline whitespace-nowrap',
                // Apparel list items may not carry their own "schema" field (list serialization
                // only guarantees it on the first entry) — the model is always olio.apparel here,
                // so use it directly rather than am7model.jsonModelKey off the item.
                onclick: function (e) { e.preventDefault(); openInNewTab('olio.apparel', a.objectId); }
            }, 'Open →'),
            m('input', {
                type: 'number', min: '0', placeholder: 'scene #',
                class: 'text-field-compact w-20',
                value: sceneTagInputs[a.objectId] || '',
                oninput: function (e) { sceneTagInputs[a.objectId] = e.target.value; }
            }),
            m('button', { class: 'btn', onclick: function () { tagApparel(a.objectId); } }, 'Tag')
        ]);
    }));
}

function renderPortraitPanel() {
    let profile = selectedInst.entity.profile;
    return m('div', { class: 'flex flex-col gap-2' }, [
        profile && profile.portrait
            ? m(PortraitImage, { objectId: profile.portrait.objectId })
            : m('div', { class: 'text-sm text-gray-500' }, 'No portrait yet.'),
        m('button', { class: 'btn btn-primary', onclick: doReimage }, 'Regenerate Portrait')
    ]);
}

// Small component that resolves+caches its own image URL — avoids re-triggering a fetch on
// every parent redraw (resolveImageUrl already memoizes by objectId internally).
const PortraitImage = {
    url: null,
    oninit: function (vnode) {
        resolveImageUrl(vnode.attrs.objectId).then(function (u) {
            vnode.state.url = u;
            m.redraw();
        });
    },
    view: function (vnode) {
        return vnode.state.url
            ? m('img', { src: vnode.state.url, class: 'max-w-[200px] max-h-[200px] rounded' })
            : m('div', { class: 'text-sm text-gray-500' }, 'Loading portrait…');
    }
};

// Inline name/description editor shown in place of the detail header. Uses the same text-field
// classes as the rest of the manager (scene-tag input) and the standard button classes.
function renderIdentityEditor() {
    return m('div', { class: 'flex flex-col gap-2 flex-1 min-w-0', 'data-char-identity-editor': true }, [
        m('input', {
            type: 'text',
            class: 'text-field-compact w-full',
            placeholder: 'Name',
            'data-char-name-input': true,
            value: editName,
            disabled: savingIdentity,
            oninput: function (e) { editName = e.target.value; },
            onkeydown: function (e) {
                if (e.key === 'Enter') { e.preventDefault(); saveIdentity(); }
                else if (e.key === 'Escape') { e.preventDefault(); cancelEditIdentity(); }
            }
        }),
        m('textarea', {
            class: 'text-field-compact w-full',
            rows: 3,
            placeholder: 'Description',
            'data-char-description-input': true,
            value: editDescription,
            disabled: savingIdentity,
            oninput: function (e) { editDescription = e.target.value; }
        }),
        m('div', { class: 'flex items-center gap-2' }, [
            m('button', {
                class: 'btn btn-sm btn-primary',
                'data-char-save': true,
                disabled: savingIdentity || !(editName || '').trim(),
                onclick: saveIdentity
            }, savingIdentity ? 'Saving…' : 'Save'),
            m('button', { class: 'btn btn-sm', disabled: savingIdentity, onclick: cancelEditIdentity }, 'Cancel')
        ])
    ]);
}

function renderDetail() {
    if (!selectedObjectId) {
        return m('div', { class: 'text-sm text-gray-500 p-4' }, 'Select a character from the list.');
    }
    if (!selectedInst) {
        return m('div', { class: 'text-sm text-gray-500 p-4' }, 'Loading…');
    }
    return m('div', { class: 'flex flex-col gap-4 p-4' }, [
        m('div', { class: 'flex items-start justify-between gap-4' }, [
            editingIdentity ? renderIdentityEditor() : m('div', { class: 'min-w-0' }, [
                m('div', { class: 'flex items-center gap-2' }, [
                    m('div', { 'data-char-name': true, class: 'text-lg font-semibold' }, selectedInst.entity.name),
                    m('button', {
                        class: 'text-gray-400 hover:text-blue-600',
                        title: 'Edit name and description',
                        'data-char-edit': true,
                        onclick: beginEditIdentity
                    }, m('span', { class: 'material-symbols-outlined text-base' }, 'edit'))
                ]),
                m('div', { class: 'text-sm text-gray-500' }, 'Gender: ' + (selectedInst.entity.gender || 'UNKNOWN')),
                selectedInst.entity.description
                    ? m('div', { 'data-char-description': true, class: 'text-sm text-gray-600 dark:text-gray-300 mt-1 whitespace-pre-wrap' },
                        selectedInst.entity.description)
                    : null
            ]),
            m('div', { class: 'flex items-center gap-3 flex-shrink-0' }, [
                m('a', {
                    href: '#!/view/' + (selectedInst.entity[am7model.jsonModelKey] || 'olio.charPerson') + '/' + selectedInst.entity.objectId,
                    target: '_blank', rel: 'noopener',
                    class: 'text-sm text-blue-600 dark:text-blue-400 hover:underline',
                    'data-char-full-editor': selectedInst.entity.objectId,
                    onclick: function (e) { e.preventDefault(); openFullEditor(selectedInst.entity); }
                }, 'Open Full Editor →'),
                m('button', {
                    class: 'px-3 py-1 rounded text-xs bg-red-600 text-white hover:bg-red-500 disabled:opacity-50',
                    title: 'Delete this character and detach it from every scene. Use for extractions that are not really characters (animals, expressions).',
                    disabled: deleting || merging,
                    onclick: doDeleteSelected
                }, deleting ? 'Deleting…' : 'Delete character')
            ])
        ]),
        renderAffectedPanel(),
        renderWardrobePanel(),
        m('div', [
            m('div', { class: 'font-medium mb-1' }, 'Portrait'),
            renderPortraitPanel()
        ]),
        m('div', [
            m('div', { class: 'flex items-center justify-between mb-1' }, [
                m('div', { class: 'font-medium' }, ['Statistics', dirtyMark(!!statDraftOf(selectedInst.entity.objectId), 'pbchar-stats-dirty')]),
                renderStatisticsActions()
            ]),
            renderStatisticsPanel()
        ]),
        m('div', [
            m('div', { class: 'flex items-center justify-between mb-1' }, [
                m('div', { class: 'font-medium' }, 'Apparel'),
                m('button', { class: 'btn', onclick: doOutfitBuilder }, 'Generate New Outfit')
            ]),
            renderApparelPanel()
        ])
    ]);
}

// Merge toolbar. Sits above the character list because merging is a LIST operation — it is about
// the relationship between two rows, not about the selected character's own fields.
function renderMergeToolbar() {
    if (!characters.length) return null;
    let selectedCount = Object.keys(mergeSelection).filter(function (k) { return mergeSelection[k]; }).length;
    if (!mergeMode) {
        // In Edit > Characters the section toolbar carries "Merge duplicates"; only the merge-mode controls stay here.
        if (workspace) return null;
        return m('div', { class: 'flex items-center justify-between pb-2 mb-1 border-b border-gray-200 dark:border-gray-700' }, [
            m('span', { class: 'text-xs text-gray-500 uppercase tracking-wide' }, 'Characters'),
            m('a', {
                href: '#', class: 'text-xs text-blue-600 dark:text-blue-400 hover:underline',
                'data-testid': 'pbchar-merge-toggle',
                title: 'Fold duplicate extractions of the same person into one character. Extraction can name an unnamed character differently in different parts of a long work.',
                onclick: function (e) { e.preventDefault(); toggleMergeMode(); }
            }, 'Merge duplicates')
        ]);
    }
    return m('div', { class: 'flex flex-col gap-1 pb-2 mb-1 border-b border-gray-200 dark:border-gray-700' }, [
        m('div', { class: 'text-xs text-gray-600 dark:text-gray-300' },
            selectedObjectId
                ? 'Tick the duplicates to fold into the selected character.'
                : 'Select the character to KEEP first, then tick its duplicates.'),
        m('div', { class: 'flex gap-2' }, [
            m('button', {
                class: 'btn btn-sm btn-primary',
                disabled: merging || !selectedObjectId || !selectedCount,
                onclick: doMergeSelected
            }, merging ? 'Merging…' : ('Merge ' + selectedCount + ' →')),
            m('button', { class: 'btn btn-sm', disabled: merging, onclick: toggleMergeMode }, 'Cancel')
        ])
    ]);
}

function renderContent() {
    if (loading) return m('div', { class: 'p-4 text-sm text-gray-500' }, 'Loading characters…');
    return m('div', { class: 'flex gap-4', style: 'min-height: 400px;' }, [
        m('div', { class: 'w-56 flex flex-col border-r border-gray-200 dark:border-gray-700 pr-2 overflow-y-auto' }, [
            renderMergeToolbar(),
            m('div', { class: 'flex flex-col gap-1' },
                characters.length
                    ? characters.map(renderCharacterListItem)
                    : listError
                        ? m('div', { 'data-testid': 'pb-chars-error', class: 'text-sm text-red-600 p-2' }, 'Could not load characters: ' + listError)
                        : m('div', { 'data-testid': 'pb-chars-empty', class: 'text-sm text-gray-500 p-2' }, 'No characters extracted yet.'))
        ]),
        m('div', { class: 'flex-1 overflow-y-auto' }, renderDetail())
    ]);
}

/**
 * Initialize character-manager state for a book WITHOUT opening a Dialog — used by the PictureBook
 * wizard's Step 3, which renders renderCharacterManagerContent() inline inside its own already-open
 * Dialog. Fires the initial character-list fetch.
 * @param {string} theBookObjectId - book group objectId
 * @param {{standalone?: boolean}} [opts] - standalone: no wizard Dialog underneath (see openFullEditor)
 */
async function initCharacterManager(theBookObjectId, opts) {
    resetState();
    bookObjectId = theBookObjectId;
    standalone = !!(opts && opts.standalone);
    workspace = !!(opts && opts.workspace);
    if (workspace) registerCharacterDirty();
    loading = true;
    m.redraw();
    // Standalone = launched with the olio.pb.book objectId (PB2), the id the V3 routes take; the
    // wizard passes the data.group id, so the affected panel never applies there.
    await refreshList();
    loading = false;
    m.redraw();
}

/** Dialog-agnostic list+detail view — usable inline (wizard step 3) or inside a Dialog (popup). */
function renderCharacterManagerContent() {
    return renderContent();
}

/**
 * @deprecated D2 (aiDocs/PictureBookUxConsistencyDesign.md 5.4): the pop-in is retired in favour of the
 * Edit > Characters workspace section (`charactersSection`, route /picture-book/:id/edit/characters[/:charObjectId]).
 * Kept working only until its remaining callers (features/pictureBook.js library row / reader) are migrated; remove
 * it with them.
 *
 * Open Manage Characters as its own Dialog — used by the PB2 book list / reader header, which pass
 * {standalone: true} because nothing is open underneath; independent of the wizard Step 3's inline
 * rendering of the exact same content.
 * @param {string} theBookObjectId - book group objectId
 * @param {{standalone?: boolean}} [opts] - standalone: no wizard Dialog underneath (see openFullEditor)
 */
async function openCharacterManager(theBookObjectId, opts) {
    Dialog.open({
        title: 'Manage Characters',
        size: 'xl',
        content: { view: renderCharacterManagerContent },
        actions: [
            { label: 'Close', icon: 'close', primary: true, onclick: function () { Dialog.close(); } }
        ],
        onClose: stopWardrobeWatches
    });
    await initCharacterManager(theBookObjectId, opts);
}

// ── Edit > Characters: the bookWorkspace SectionAdapter (design 4.2, 5.4) ───────────

/** Select ctx.item (or, when absent / no longer in the list, the first character). Deferred while the list loads. */
function applyItem(item) {
    if (loading) { pendingItem = item; return; }
    let target = ensureSelection(characters.map(function (c) { return c.objectId; }), item);
    if (!target) { selectedObjectId = null; selectedInst = null; m.redraw(); return; }
    if (target === selectedObjectId && selectedInst) return;
    selectCharacter(target);
}

async function initWorkspaceSection(ctx) {
    wsCtx = ctx;
    let book = ctx.bookObjectId;
    await initCharacterManager(book, { standalone: true, workspace: true });
    if (bookObjectId !== book || !workspace) return;   // left, or re-entered for another book, while loading
    wsCtx = wsCtx || ctx;
    let item = pendingItem !== undefined ? pendingItem : ctx.item;
    pendingItem = undefined;
    applyItem(item);
}

/** Refresh: re-read the list (badges, rail count) and the selected detail. Statistic drafts survive; an open, changed
 * identity edit is not re-read over. */
async function refreshSection() {
    await refreshList();
    if (selectedObjectId && !identityDirty()) await selectCharacter(selectedObjectId);
}

const charactersSection = {
    dirtyKeys: [DIRTY_KEY],
    init: function (ctx) { return initWorkspaceSection(ctx); },
    select: function (ctx) {
        wsCtx = ctx;
        if (!workspace || bookObjectId !== ctx.bookObjectId) return;
        applyItem(ctx.item);
    },
    remove: function () {
        stopWardrobeWatches();
        resetState();
    },
    toolbar: function (ctx) {
        wsCtx = ctx;
        let n = characters.length;
        let warn = warningCount(characters);
        let meta = [];
        if (!loading && !listError) {
            meta.push(chip(n + ' character' + (n !== 1 ? 's' : ''), 'gray', { 'data-testid': 'pbchar-ws-count', 'data-count': String(n) }));
            if (warn) meta.push(chip(warn + ' need' + (warn === 1 ? 's' : '') + ' attention', 'amber', { 'data-testid': 'pbchar-ws-warn', 'data-count': String(warn) }));
        }
        return {
            title: 'Characters',
            testid: 'pbchar-ws-toolbar',
            meta: meta,
            actions: [
                btn({
                    label: mergeMode ? 'Cancel merge' : 'Merge duplicates', icon: 'merge', size: 'sm',
                    title: 'Fold duplicate extractions of the same person into one character. Extraction can name an unnamed character differently in different parts of a long work.',
                    disabled: loading || merging || !n,
                    onclick: toggleMergeMode,
                    attrs: { 'data-testid': 'pbchar-merge-toggle' }
                })
            ],
            refresh: { testid: 'pbchar-ws-refresh', disabled: loading, onclick: refreshSection }
        };
    },
    body: function (ctx) {
        wsCtx = ctx;
        return renderCharacterManagerContent();
    },
    status: function (ctx) { return charactersStatusFor(ctx.bookObjectId); }
};

// ── Vitest seams (P1-10 S5) — not for production callers ─────────────
export function __pbCharStateForTest() {
    return {
        bookObjectId: bookObjectId, standalone: standalone,
        selectedObjectId: selectedObjectId, selectedInst: selectedInst,
        affected: affected, lastSaveKind: lastSaveKind, regen: regen, styleDescribed: styleDescribed,
        editingIdentity: editingIdentity, editName: editName, editDescription: editDescription,
        wardrobe: wardrobe, wardrobeBusy: wardrobeBusy, wardrobeAddText: wardrobeAddText, wardrobeRuns: wardrobeRuns,
        workspace: workspace, loading: loading, characters: characters, statDrafts: statDrafts, savingStats: savingStats,
        scenePages: scenePages, mergeMode: mergeMode
    };
}
export function __setPbCharStateForTest(patch) {
    patch = patch || {};
    if ('bookObjectId' in patch) bookObjectId = patch.bookObjectId;
    if ('standalone' in patch) standalone = patch.standalone;
    if ('selectedObjectId' in patch) selectedObjectId = patch.selectedObjectId;
    if ('selectedInst' in patch) selectedInst = patch.selectedInst;
    if ('affected' in patch) affected = patch.affected;
    if ('lastSaveKind' in patch) lastSaveKind = patch.lastSaveKind;
    if ('regen' in patch) regen = patch.regen;
    if ('styleDescribed' in patch) styleDescribed = patch.styleDescribed;
    if ('editingIdentity' in patch) editingIdentity = patch.editingIdentity;
    if ('editName' in patch) editName = patch.editName;
    if ('editDescription' in patch) editDescription = patch.editDescription;
    if ('regenPollIntervalMs' in patch) regenPollIntervalMs = patch.regenPollIntervalMs;
    if ('wardrobe' in patch) wardrobe = patch.wardrobe;
    if ('wardrobeBusy' in patch) wardrobeBusy = patch.wardrobeBusy;
    if ('wardrobeAddText' in patch) wardrobeAddText = patch.wardrobeAddText;
    if ('characters' in patch) characters = patch.characters;
    if ('mergeSelection' in patch) mergeSelection = patch.mergeSelection;
    if ('statDrafts' in patch) statDrafts = patch.statDrafts || {};
    if ('scenePages' in patch) scenePages = patch.scenePages;
    if ('wardrobeRuns' in patch) wardrobeRuns = patch.wardrobeRuns && typeof patch.wardrobeRuns === 'object' ? patch.wardrobeRuns : {};
}
export const __pbCharSelectCharacterForTest = selectCharacter;
export const __pbCharSaveIdentityForTest = saveIdentity;
export const __pbCharRegenerateAffectedForTest = regenerateAffected;
export const __pbCharLoadWardrobeForTest = loadWardrobe;
export const __pbCharAddWardrobeEntryForTest = doAddWardrobeEntry;
export const __pbCharSetDefaultForTest = doSetDefault;
export const __pbCharRenderReferenceForTest = doRenderReference;
export const __pbCharMigrateSceneTagsForTest = doMigrateSceneTags;
export const __pbCharDoReimageForTest = doReimage;
export const __pbCharMergeSelectedForTest = doMergeSelected;
export const __pbCharDeleteSelectedForTest = doDeleteSelected;
export const __pbCharSaveStatDraftForTest = saveStatDraft;
export function __resetPbCharStatusForTest() { Object.keys(statusCache).forEach(function (k) { delete statusCache[k]; }); }

export {
    openCharacterManager, initCharacterManager, renderCharacterManagerContent,
    // Edit > Characters (design 5.4)
    charactersSection, loadCharactersStatus, characterWarnings,
    // V3 affected-scenes (pure helpers + panel), exported for Vitest
    WATCHED_CHAR_FIELDS, identityChangeKind, affectedCaveat, affectedSummary, scenesForIndexes,
    renderAffectedPanel,
    // V3 wardrobe (P4-9) pure helpers + panel, exported for Vitest
    wardrobeEntryKind, wearablesText, setDefaultText, migrateText, renderWardrobePanel
};
export default openCharacterManager;
