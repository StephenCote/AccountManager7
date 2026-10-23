import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { renderRange } from '../components/formFieldRenderers.js';
import { listCharacters, tagApparelSceneIndex, mergeCharacters, deleteCharacter, resolveImageUrl, pollJob } from './sceneExtractor.js';
import {
    getAffectedBy, bookPages, renderScene, unpinNode, isPinnedRefusal,
    getWardrobe, addWardrobeEntry, setDefaultApparel, renderApparelReference, migrateSceneTags
} from './pictureBookWorkflow.js';
import { reimage } from './reimage.js';
import { outfitBuilder } from './outfitBuilder.js';

/**
 * PictureBook "Manage Characters" workflow — review/edit extracted charPerson records for a book:
 * customize/complete statistics, generate/tag apparel per scene, regenerate a portrait.
 * Launched from the PictureBook wizard (step 4/5) via openCharacterManager(bookObjectId), and
 * directly from the PB2 book list / reader header via openCharacterManager(bookObjectId,
 * {standalone: true}) — see openFullEditor for what `standalone` changes.
 *
 * Reuses existing workflows wholesale rather than re-implementing them:
 *  - reimage(entity, inst)       — portrait regeneration (same call the generic charPerson
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
// bookObjectId IS the olio.pb.book objectId the V3 routes take) and only on the v3 render path.
// getAffectedBy is called once per character-detail open and once after saveIdentity - never per
// row, never on a poll.
let renderPath = 'v1';
let affected = null;          // { forObjectId, loading, data, error }
let lastSaveKind = null;      // 'watched' | 'unwatched' | 'none' after the last saveIdentity (caveat text)
let regen = null;             // { total, done, current, aborted } while "Regenerate" runs
let regenPollIntervalMs = undefined; // pollJob default; Vitest shortens it through the test seam
// V3 P4-9: the selected character's wardrobe (GET /{book}/character/{char}/wardrobe), read once per
// detail open and once after each wardrobe write. Only in standalone + v3, like the affected panel.
let wardrobe = null;          // { forObjectId, loading, data: normalizeWardrobe(), error }
let wardrobeBusy = null;      // 'add' | 'migrate' | 'default:<apparelOid>' | 'reference:<apparelOid>' while a write runs
let wardrobeAddText = '';     // the "Add alternate" description input
let wardrobeRuns = {};        // apparelOid -> {jobId, job, error} while a reference render job is watched
let wardrobeAborts = {};      // apparelOid -> AbortController (stops WATCHING only; the server job keeps going)

function resetState() {
    bookObjectId = null;
    characters = [];
    selectedObjectId = null;
    selectedInst = null;
    loading = false;
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
    renderPath = 'v1';
    affected = null;
    lastSaveKind = null;
    regen = null;
    stopWardrobeWatches();
    wardrobe = null;
    wardrobeBusy = null;
    wardrobeAddText = '';
    wardrobeRuns = {};
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
    return standalone && renderPath === 'v3' && !!bookObjectId;
}

/** The ONE affected-by read for a character detail (open, or post-save re-open). */
async function loadAffected(charObjectId) {
    if (!v3Enabled() || !charObjectId) { affected = null; return; }
    affected = { forObjectId: charObjectId, loading: true, data: null, error: null };
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
}

/** Review → the book's review queue. Standalone only (the manager is the only Dialog open), as openFullEditor. */
function openReviewQueue() {
    if (!v3Enabled()) return;
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
    if (!v3Enabled() || regen || !affected || !affected.data) return;
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
        body = m('div', { class: 'flex items-center gap-2 flex-wrap text-sm' }, [
            m('span', { 'data-testid': 'pbchar-affected-count', 'data-count': String(s.count) },
                'Affected scenes: ' + s.count + (s.count ? ' (' + s.sceneIndexes.join(', ') + ')' : '')),
            s.drifted ? m('span', { 'data-testid': 'pbchar-affected-drifted', class: 'text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200', title: 'Nodes: ' + s.driftedHandles.join(', ') },
                s.staleCount + ' stale') : null,
            m('button', {
                'data-testid': 'pbchar-affected-review',
                class: 'button text-xs',
                disabled: !!regen,
                onclick: openReviewQueue
            }, 'Review'),
            m('button', {
                'data-testid': 'pbchar-affected-regenerate',
                class: 'button text-xs',
                disabled: !!regen || !s.count,
                title: s.count ? 'Re-render every affected scene, one at a time' : 'No scene binds this character',
                onclick: regenerateAffected
            }, regen ? ('Regenerating ' + (regen.done + 1) + ' of ' + regen.total + (regen.current != null ? ' (scene ' + regen.current + ')' : '') + '…') : 'Regenerate')
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
                class: 'button text-xs',
                disabled: e.isDefault || !!wardrobeBusy,
                title: e.isDefault ? 'This is the default outfit' : 'Make this the outfit scenes wear unless an actor pins another',
                onclick: function () { doSetDefault(e); }
            }, busyDefault ? 'Setting…' : 'Set default'),
            m('button', {
                'data-testid': 'pb-wd-render-ref',
                class: 'button text-xs',
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
                    class: 'button text-xs',
                    disabled: !!wardrobeBusy || !String(wardrobeAddText || '').trim(),
                    title: 'Build the outfit from this description with the LLM and add it as an alternate',
                    onclick: doAddWardrobeEntry
                }, wardrobeBusy === 'add' ? 'Adding…' : 'Add alternate')
            ]),
            m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
                m('button', {
                    'data-testid': 'pb-wd-migrate',
                    class: 'button text-xs',
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

async function refreshList() {
    characters = await listCharacters(bookObjectId);
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

async function patchStatField(statistics, field, value) {
    await page.patchObject({
        schema: 'olio.statistics',
        id: statistics.id,
        objectId: statistics.objectId,
        [field]: value
    });
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
    if (!selectedInst || savingIdentity) return;
    let ent = selectedInst.entity;
    let name = (editName || '').trim();
    if (!name) {
        page.toast('error', 'Name is required');
        return;
    }
    let desc = editDescription || '';
    let patch = { schema: 'olio.charPerson', id: ent.id, objectId: ent.objectId, name: name };
    if (desc !== (ent.description || '')) patch.description = desc;
    let changeKind = identityChangeKind(ent, name, desc);
    savingIdentity = true;
    m.redraw();
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
        await selectCharacter(ent.objectId);
        await refreshList();
    } catch (e) {
        page.toast('error', 'Failed to save character: ' + (e.message || e));
    }
    savingIdentity = false;
    m.redraw();
}

async function doReimage() {
    if (!selectedInst) return;
    await reimage(selectedInst.entity, selectedInst);
    // reimage's own Dialog mutates selectedInst.entity.profile.portrait directly on success —
    // just refresh the list so the "hasPortrait" badge picks it up too.
    await refreshList();
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
    let ok = window.confirm('Merge ' + dropNames.join(', ') + ' into "' + keepName + '"?\n\n'
        + 'Every scene that references ' + (dropNames.length > 1 ? 'them' : 'it')
        + ' will be repointed to "' + keepName + '", and '
        + (dropNames.length > 1 ? 'those characters' : 'that character')
        + ' will be deleted along with their portrait, statistics and wardrobe. This cannot be undone.');
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
    let ok = window.confirm('Delete "' + name + '"?\n\n'
        + 'It will be removed from every scene that references it, and deleted along with its '
        + 'portrait, statistics and wardrobe. This cannot be undone.');
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
        selectedObjectId = null;
        selectedInst = null;
        await refreshList();
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
 * A new tab leaves the wizard exactly as it is. Used for apparel records, and for the character
 * itself unless the manager is `standalone`.
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
 * Where it opens depends on how the manager was launched:
 *  - standalone (book list / reader header, `openCharacterManager(id, {standalone: true})`): there
 *    is no wizard underneath, so close the manager and navigate in place to the generic editor
 *    route (/view/<type>/:objectId, router.js); the editor's Cancel returns to the launching page.
 *  - inside the wizard (Step 3 inline, or the Steps 4/5 stacked popup): open a new tab via
 *    openInNewTab so the wizard's in-progress state stays exactly as it is.
 */
function openFullEditor(entity) {
    let type = entity[am7model.jsonModelKey] || 'olio.charPerson';
    if (standalone) {
        Dialog.closeAll();
        m.route.set('/view/' + type + '/' + entity.objectId);
    } else {
        openInNewTab(type, entity.objectId);
    }
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

function renderCharacterListItem(c) {
    let isSelected = c.objectId === selectedObjectId;
    let badges = [];
    if (!c.hasPortrait) badges.push(m('span', { class: 'text-xs text-amber-600 dark:text-amber-400' }, 'no portrait'));
    if (!c.apparelCount) badges.push(m('span', { class: 'text-xs text-amber-600 dark:text-amber-400' }, 'no apparel'));
    if (c.failedApparel) badges.push(m('span', { class: 'text-xs text-red-600 dark:text-red-400' }, 'apparel failed'));
    if (c.failedStatistics) badges.push(m('span', { class: 'text-xs text-red-600 dark:text-red-400' }, 'stats failed'));

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
        onclick: function () { if (!mergeMode) selectCharacter(c.objectId); }
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

function renderStatisticsPanel() {
    let statistics = selectedInst.entity.statistics;
    if (!statistics) return m('div', { class: 'text-sm text-gray-500' }, 'No statistics record.');
    return m('div', { class: 'grid grid-cols-2 gap-2' }, STAT_FIELDS.map(function (field) {
        let value = statistics[field] != null ? statistics[field] : 0;
        return m('div', { class: 'flex flex-col' }, [
            m('label', { class: 'text-xs text-gray-500' }, field + ': ' + value),
            renderRange({
                value: value,
                min: 0, max: 20, step: 1,
                name: field,
                onInput: function (e) {
                    let v = parseInt(e.target.value, 10);
                    statistics[field] = v;
                    m.redraw();
                    patchStatField(statistics, field, v).catch(function (err) {
                        page.toast('error', 'Failed to save ' + field + ': ' + (err.message || err));
                    });
                }
            })
        ]);
    }));
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
            m('button', { class: 'button', onclick: function () { tagApparel(a.objectId); } }, 'Tag')
        ]);
    }));
}

function renderPortraitPanel() {
    let profile = selectedInst.entity.profile;
    return m('div', { class: 'flex flex-col gap-2' }, [
        profile && profile.portrait
            ? m(PortraitImage, { objectId: profile.portrait.objectId })
            : m('div', { class: 'text-sm text-gray-500' }, 'No portrait yet.'),
        m('button', { class: 'button primary', onclick: doReimage }, 'Regenerate Portrait')
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
                class: 'button primary text-xs',
                'data-char-save': true,
                disabled: savingIdentity || !(editName || '').trim(),
                onclick: saveIdentity
            }, savingIdentity ? 'Saving…' : 'Save'),
            m('button', { class: 'button text-xs', disabled: savingIdentity, onclick: cancelEditIdentity }, 'Cancel')
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
                    href: '#', class: 'text-sm text-blue-600 dark:text-blue-400 hover:underline',
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
            m('div', { class: 'font-medium mb-1' }, 'Statistics'),
            renderStatisticsPanel()
        ]),
        m('div', [
            m('div', { class: 'flex items-center justify-between mb-1' }, [
                m('div', { class: 'font-medium' }, 'Apparel'),
                m('button', { class: 'button', onclick: doOutfitBuilder }, 'Generate New Outfit')
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
        return m('div', { class: 'flex items-center justify-between pb-2 mb-1 border-b border-gray-200 dark:border-gray-700' }, [
            m('span', { class: 'text-xs text-gray-500 uppercase tracking-wide' }, 'Characters'),
            m('a', {
                href: '#', class: 'text-xs text-blue-600 dark:text-blue-400 hover:underline',
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
                class: 'button primary text-xs',
                disabled: merging || !selectedObjectId || !selectedCount,
                onclick: doMergeSelected
            }, merging ? 'Merging…' : ('Merge ' + selectedCount + ' →')),
            m('button', { class: 'button text-xs', disabled: merging, onclick: toggleMergeMode }, 'Cancel')
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
                    : m('div', { class: 'text-sm text-gray-500 p-2' }, 'No characters extracted yet.'))
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
    loading = true;
    m.redraw();
    let work = [refreshList()];
    if (standalone) {
        // Standalone = launched with the olio.pb.book objectId (PB2), the id the V3 routes take; the
        // wizard passes the data.group id, so the affected panel never applies there. Missing/failed
        // render-path read = v1 (same rule as pictureBook.js pb2IsV3).
        work.push(Promise.resolve().then(function () { return am7client.getRenderPath(); })
            .then(function (rp) { renderPath = (rp && typeof rp === 'object' && rp.picturebook === 'v3') ? 'v3' : 'v1'; })
            .catch(function () { renderPath = 'v1'; }));
    }
    await Promise.all(work);
    loading = false;
    m.redraw();
}

/** Dialog-agnostic list+detail view — usable inline (wizard step 3) or inside a Dialog (popup). */
function renderCharacterManagerContent() {
    return renderContent();
}

/**
 * Open Manage Characters as its own Dialog — used by the wizard's steps 4/5 "Manage Characters"
 * button (a quick one-off tweak while generating/viewing, stacked on top of the wizard's own open
 * Dialog), independent of Step 3's inline rendering of the exact same content; and by the PB2 book
 * list / reader header, which pass {standalone: true} because nothing is open underneath.
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

// ── Vitest seams (P1-10 S5) — not for production callers ─────────────
export function __pbCharStateForTest() {
    return {
        bookObjectId: bookObjectId, standalone: standalone, renderPath: renderPath,
        selectedObjectId: selectedObjectId, selectedInst: selectedInst,
        affected: affected, lastSaveKind: lastSaveKind, regen: regen,
        editingIdentity: editingIdentity, editName: editName, editDescription: editDescription,
        wardrobe: wardrobe, wardrobeBusy: wardrobeBusy, wardrobeAddText: wardrobeAddText, wardrobeRuns: wardrobeRuns
    };
}
export function __setPbCharStateForTest(patch) {
    patch = patch || {};
    if ('bookObjectId' in patch) bookObjectId = patch.bookObjectId;
    if ('standalone' in patch) standalone = patch.standalone;
    if ('renderPath' in patch) renderPath = patch.renderPath;
    if ('selectedObjectId' in patch) selectedObjectId = patch.selectedObjectId;
    if ('selectedInst' in patch) selectedInst = patch.selectedInst;
    if ('affected' in patch) affected = patch.affected;
    if ('lastSaveKind' in patch) lastSaveKind = patch.lastSaveKind;
    if ('regen' in patch) regen = patch.regen;
    if ('editingIdentity' in patch) editingIdentity = patch.editingIdentity;
    if ('editName' in patch) editName = patch.editName;
    if ('editDescription' in patch) editDescription = patch.editDescription;
    if ('regenPollIntervalMs' in patch) regenPollIntervalMs = patch.regenPollIntervalMs;
    if ('wardrobe' in patch) wardrobe = patch.wardrobe;
    if ('wardrobeBusy' in patch) wardrobeBusy = patch.wardrobeBusy;
    if ('wardrobeAddText' in patch) wardrobeAddText = patch.wardrobeAddText;
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

export {
    openCharacterManager, initCharacterManager, renderCharacterManagerContent,
    // V3 affected-scenes (pure helpers + panel), exported for Vitest
    WATCHED_CHAR_FIELDS, identityChangeKind, affectedCaveat, affectedSummary, scenesForIndexes,
    renderAffectedPanel,
    // V3 wardrobe (P4-9) pure helpers + panel, exported for Vitest
    wardrobeEntryKind, wearablesText, setDefaultText, migrateText, renderWardrobePanel
};
export default openCharacterManager;
