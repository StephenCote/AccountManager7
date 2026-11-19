/**
 * pictureBookLocations — PB2 V3 locations library (design §26/§27, P3-7).
 *
 * Surfaces:
 *   locationsSection — the bookWorkspace Edit › Locations SectionAdapter (aiDocs/PictureBookUxConsistencyDesign.md
 *     §5.5): master/detail (a pinned "Proposals" pseudo-row, then one row per location), :item = location objectId
 *     or PROPOSALS_ITEM; render jobs go to workflows/bookActivity.js (the dock / pill), never into the body.
 *   /picture-book/:bookObjectId/locations   — the legacy full page (same body + a back arrow), until it redirects.
 *   (bookObjectId = olio.pb.book objectId; PictureBook or ChapBook)
 *
 * One read: GET /{book}/locations (PbServiceFacade.listLocations) — every card is a locationDto with its
 * variants, usedByScenes and (once rendered) its LOCATION node + artifact chain. Every write goes through
 * the facade routes and is followed by a refetch, so the grid only ever shows server state. A location's
 * name is not editable (a rename is a merge); description, kind and aliases are PATCHed as the changed
 * keys only. Render is asynchronous (202 {jobId}) and watched with pollJob; so is the location pass
 * (POST /locations/propose → 202 {jobId}, the pass result collected from the terminal job with
 * locationPassFromJob); a pinned node is a 409 the server words itself ("Node … is pinned. Unpin it
 * first …") and is answered with an Unpin button. Every server error is toasted verbatim.
 *
 * The location pass sends a chat config NAME like every other PB LLM surface (§6.C 267): the book's
 * carried config, else the library default (contentAnalysis, then generalChat) resolved through
 * components/pbChatConfig.js, and a "Chat Config" pick in the header wins over both. Without a name the
 * server falls to its generalChat default, which is not necessarily a config the user can reach.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { layout, pageLayout } from '../router.js';
import { Dialog } from '../components/dialogCore.js';
import { chatConfigName, resolveDefaultChatConfig, renderChatConfigControl } from '../components/pbChatConfig.js';
import { pollJob, resolveImageUrl } from '../workflows/sceneExtractor.js';
import { orderNewestFirst, reviewStatusOf, isAutoApproved, artifactActions } from './pictureBookReview.js';
import { BTN, BTN_PRIMARY, BTN_DANGER } from '../components/buttons.js';
import { pageHeader, metaText } from '../components/pageHeader.js';
import { masterDetail, stateBlock, INPUT, CHIP, field } from '../components/bookUi.js';
import { registerDirty, unregisterDirty, guardDirty } from '../components/dirtyGuard.js';
import { setActivity, clearActivity } from '../workflows/bookActivity.js';
import { editLink, ensureSelection } from '../workflows/bookWorkspaceNav.js';
import {
    listLocations, createLocation, updateLocation, deleteLocation, createLocationVariant, lockLocation,
    mergeLocations, renderLocation, isPinnedNodeRefusal, proposeLocations, locationPassFromJob, listLocationProposals,
    acceptLocationProposal, rejectLocationProposal, selectArtifact, approveArtifact, rejectArtifact, unpinNode,
    deriveStageList, runSummaryText, carriedBookChatConfig
} from '../workflows/pictureBookWorkflow.js';

let bookObjectId = null;
let chatConfigRef = null;       // {name, objectId} the location pass sends as chatConfigName; a pick wins
let chatConfigResolving = false;
let chatConfigPending = null;   // the in-flight default resolution, awaited by propose() so a quick click still carries a name
let locations = [];
let worldObjectId = null;
let loading = false;
let error = null;
let loadSeq = 0;
let proposals = [];
let proposalFilter = 'PROPOSED';
let proposalsLoading = false;
let proposalsError = null;
let proposing = false;
let proposeJob = null;      // latest /rest/job payload of the running location pass, null when idle
let busy = {};              // subject/proposal/artifact objectId → true while a write is in flight
let edits = {};             // location objectId → {description, kind, aliases} inline draft
let runs = {};              // subject objectId → {jobId, runObjectId, planned, job, label, error}
let runAborts = {};         // subject objectId → AbortController
let pinnedRefusals = {};    // subject objectId → {message, nodeObjectId}
let overwrite = {};         // proposal objectId → accept with {overwrite:true}
let imageUrls = {};
let pollIntervalMs = 2000;
let openProposalCount = null; // PROPOSED proposals awaiting a decision, null when not known (the rail status never guesses)
let selectedId = null;      // master/detail selection: a location objectId, PROPOSALS_ITEM, or null (= the first location)
let selectedIndex = -1;     // the selection's index when last seen, for ensureSelection's same-index re-seat
let wsKind = null;          // 'pb' | 'cb' while mounted as the workspace section (scene links route there); null on the legacy route

/** The pinned "Proposals" pseudo-row's item id (a location objectId is a UUID, so it cannot collide). */
export const PROPOSALS_ITEM = 'proposals';
/** dirtyGuard key held while any inline location edit is open. */
export const LOCATION_EDIT_DIRTY_KEY = 'pb-location-edit';

export const LOCATION_KINDS = ['interior', 'exterior', 'mixed', 'abstract'];
export const VARIANT_DELTAS = ['timeOfDay', 'weather', 'season', 'viewpoint', 'extra'];
export const PROPOSAL_STATUSES = ['PROPOSED', 'ACCEPTED', 'REJECTED'];

// ── Pure derivations (exported for Vitest) ─────────────────────────────

/** "Used by scenes 3, 7, 12" from usedByScenes[].sceneIndex (sorted, de-duplicated); null-index entries count but do not list. */
export function usedByText(usedBy) {
    let list = Array.isArray(usedBy) ? usedBy : [];
    if (!list.length) return 'Not used by any scene';
    let idx = {};
    list.forEach(function (u) { if (u && u.sceneIndex != null) idx[u.sceneIndex] = true; });
    let nums = Object.keys(idx).map(Number).sort(function (a, b) { return a - b; });
    if (!nums.length) return 'Used by ' + list.length + ' scene' + (list.length === 1 ? '' : 's');
    return 'Used by scene' + (nums.length === 1 ? ' ' : 's ') + nums.join(', ');
}

/** Distinct scenes in usedByScenes (by sceneObjectId, else sceneIndex; an entry with neither counts once on its own). */
export function usedByCount(usedBy) {
    let list = Array.isArray(usedBy) ? usedBy : [];
    let seen = {};
    let n = 0;
    list.forEach(function (u) {
        let k = u && u.sceneObjectId != null ? 'o:' + u.sceneObjectId : (u && u.sceneIndex != null ? 'i:' + u.sceneIndex : null);
        if (k == null) { n++; return; }
        if (!seen[k]) { seen[k] = true; n++; }
    });
    return n;
}

/** sceneIndex → the first sceneObjectId carried for it (only indices that have an objectId are linkable). */
export function usedBySceneLinks(usedBy) {
    let out = {};
    (Array.isArray(usedBy) ? usedBy : []).forEach(function (u) {
        if (u && u.sceneIndex != null && u.sceneObjectId && out[u.sceneIndex] === undefined) out[u.sceneIndex] = u.sceneObjectId;
    });
    return out;
}

/** Comma/newline separated alias text → trimmed, de-duplicated list. */
export function parseAliases(text) {
    let seen = {};
    return String(text || '').split(/[,\n]/).map(function (s) { return s.trim(); }).filter(function (s) {
        if (!s || seen[s.toLowerCase()]) return false;
        seen[s.toLowerCase()] = true;
        return true;
    });
}

/** The PATCH body for an inline edit: only description / kind / aliases that differ from the location. */
export function editPatch(loc, draft) {
    let body = {};
    if (!loc || !draft) return body;
    let desc = draft.description != null ? String(draft.description) : '';
    if (desc !== String(loc.description || '')) body.description = desc;
    let kind = draft.kind ? String(draft.kind).toLowerCase() : '';
    if (kind && kind !== String(loc.kind || '').toLowerCase()) body.kind = kind;
    let aliases = parseAliases(draft.aliases);
    let cur = Array.isArray(loc.aliases) ? loc.aliases : [];
    if (aliases.length !== cur.length || aliases.some(function (a, i) { return a !== cur[i]; })) body.aliases = aliases;
    return body;
}

/** The variant's delta summary: "dusk · rain" from the non-empty delta fields, else its name. */
export function variantDeltaText(v) {
    if (!v) return '';
    let parts = VARIANT_DELTAS.map(function (k) { return v[k]; }).filter(Boolean);
    return parts.length ? parts.join(' · ') : (v.name || '');
}

/** Locations whose name or an alias equals `name` (case-insensitive), excluding `excludeObjectId`. */
export function matchLocations(list, name, excludeObjectId) {
    let n = String(name || '').trim().toLowerCase();
    if (!n) return [];
    return (Array.isArray(list) ? list : []).filter(function (l) {
        if (!l || l.objectId === excludeObjectId) return false;
        if (String(l.name || '').toLowerCase() === n) return true;
        return (l.aliases || []).some(function (a) { return String(a || '').toLowerCase() === n; });
    });
}

/** The busy/run/refusal key of a render subject: the variant's objectId when one is given, else the location's. */
export function subjectKey(loc, variant) {
    return variant ? variant.objectId : (loc ? loc.objectId : null);
}

// ── Data ───────────────────────────────────────────────────────────────

export async function loadLocations() {
    if (!bookObjectId) return;
    let seq = ++loadSeq;
    loading = true;
    error = null;
    m.redraw();
    try {
        let r = await listLocations(bookObjectId);
        if (seq !== loadSeq) return;
        locations = r.locations;
        worldObjectId = r.worldObjectId || null;
        reseatSelection();
        resolveThumbs(locations);
    } catch (e) {
        if (seq !== loadSeq) return;
        locations = [];
        error = (e && e.message) || 'Failed to load the locations';
    }
    loading = false;
    m.redraw();
    loadProposals(seq);
}

export async function loadProposals(seq) {
    if (!bookObjectId) return;
    if (seq == null) seq = loadSeq;
    proposalsLoading = true;
    proposalsError = null;
    m.redraw();
    try {
        let r = await listLocationProposals(bookObjectId, proposalFilter);
        if (seq !== loadSeq) return;
        proposals = r.proposals;
        openProposalCount = proposalFilter === 'PROPOSED' ? proposals.length : openProposalCount;
    } catch (e) {
        if (seq !== loadSeq) return;
        proposals = [];
        if (proposalFilter === 'PROPOSED') openProposalCount = null;
        proposalsError = (e && e.message) || 'Failed to load the proposals';
    }
    proposalsLoading = false;
    m.redraw();
}

function resolveThumbs(list) {
    function chainOf(node) { return node && Array.isArray(node.chain) ? node.chain : []; }
    (list || []).forEach(function (l) {
        let all = chainOf(l.node);
        (l.variants || []).forEach(function (v) { all = all.concat(chainOf(v.node)); });
        all.forEach(function (a) {
            let id = a && a.dataObjectId;
            if (!id || imageUrls[id] !== undefined) return;
            imageUrls[id] = null;
            resolveImageUrl(id).then(function (u) { imageUrls[id] = u || null; m.redraw(); });
        });
    });
}

/** After a reload: keep the selection if it still exists, else the location at the same index, else the first (§4.4). */
function reseatSelection() {
    if (selectedId == null || selectedId === PROPOSALS_ITEM) return;
    let ids = locations.map(function (l) { return l.objectId; });
    selectedId = ensureSelection(ids, selectedId, selectedIndex) || null;
}

async function refetch() {
    // a write may have accepted / dismissed a proposal: the open count is only re-learned from a PROPOSED read
    if (proposalFilter !== 'PROPOSED') openProposalCount = null;
    am7client.clearCache('olio.pb.artifact', true);
    am7client.clearCache('olio.pb.node', true);
    am7client.clearCache('olio.pb.scene', true);
    await loadLocations();
}

/** Run one write under a busy key; the server's message is toasted verbatim and the library is refetched either way. */
async function act(key, fn, successMsg) {
    if (busy[key]) return null;
    busy[key] = true;
    m.redraw();
    let out = null;
    let ok = false;
    try {
        out = await fn();
        ok = true;
        if (successMsg) page.toast('success', typeof successMsg === 'function' ? successMsg(out) : successMsg);
    } catch (e) {
        page.toast('error', (e && e.message) || 'The request failed');
    }
    delete busy[key];
    m.redraw();
    await refetch();
    return ok ? (out == null ? true : out) : null;
}

// ── Writes ─────────────────────────────────────────────────────────────

export function openNewLocationDialog() {
    let draft = { name: '', description: '', kind: '', aliases: '' };
    let saving = false;
    let create = async function () {
        if (saving) return;
        saving = true;
        m.redraw();
        try {
            let loc = await createLocation(bookObjectId, {
                name: draft.name.trim(),
                description: draft.description.trim() || undefined,
                kind: draft.kind || undefined,
                aliases: parseAliases(draft.aliases)
            });
            Dialog.close();
            page.toast('success', 'Created ' + (loc.name || draft.name.trim()));
            await refetch();
        } catch (e) {
            page.toast('error', (e && e.message) || 'Could not create the location');
        }
        saving = false;
        m.redraw();
    };
    Dialog.open({
        title: 'New location',
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-loc-new-form', class: 'space-y-3 text-sm' }, [
                    field('Name', m('input', { 'data-testid': 'pb-loc-new-name', class: INPUT, value: draft.name, placeholder: 'The Drowned Anchor', oninput: function (e) { draft.name = e.target.value; } })),
                    field('Description', m('textarea', { 'data-testid': 'pb-loc-new-description', class: INPUT, rows: 3, value: draft.description, oninput: function (e) { draft.description = e.target.value; } })),
                    field('Kind', kindSelect('pb-loc-new-kind', draft.kind, function (v) { draft.kind = v; })),
                    field('Aliases (comma separated)', m('input', { 'data-testid': 'pb-loc-new-aliases', class: INPUT, value: draft.aliases, placeholder: 'the anchor, anchor inn', oninput: function (e) { draft.aliases = e.target.value; } }))
                ]);
            }
        },
        actions: {
            view: function () {
                return [
                    { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
                    { label: saving ? 'Creating…' : 'Create', icon: 'add_location', primary: true, disabled: saving, attrs: { 'data-testid': 'pb-loc-new-create' }, onclick: create }
                ];
            }
        }
    });
}

export function openVariantDialog(loc) {
    let draft = { name: '', timeOfDay: '', weather: '', season: '', viewpoint: '', extra: '' };
    let saving = false;
    let create = async function () {
        if (saving) return;
        saving = true;
        m.redraw();
        let body = {};
        if (draft.name.trim()) body.name = draft.name.trim();
        VARIANT_DELTAS.forEach(function (k) { if (draft[k].trim()) body[k] = draft[k].trim(); });
        try {
            let v = await createLocationVariant(bookObjectId, loc.objectId, body);
            Dialog.close();
            page.toast('success', 'Added variant ' + (v.name || variantDeltaText(v)));
            await refetch();
        } catch (e) {
            page.toast('error', (e && e.message) || 'Could not add the variant');
        }
        saving = false;
        m.redraw();
    };
    Dialog.open({
        title: 'Add variant of ' + loc.name,
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-loc-variant-form', class: 'space-y-3 text-sm' }, [
                    field('Name (optional; the server derives one from the deltas)', m('input', { 'data-testid': 'pb-loc-variant-name', class: INPUT, value: draft.name, oninput: function (e) { draft.name = e.target.value; } })),
                    m('div', { class: 'grid grid-cols-2 gap-3' }, VARIANT_DELTAS.map(function (k) {
                        return field(k, m('input', { 'data-testid': 'pb-loc-variant-' + k, class: INPUT, value: draft[k], oninput: function (e) { draft[k] = e.target.value; } }));
                    })),
                    m('div', { class: 'text-xs text-gray-500' }, 'At least one of time of day, weather, season, viewpoint or extra must be set.')
                ]);
            }
        },
        actions: {
            view: function () {
                return [
                    { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
                    { label: saving ? 'Adding…' : 'Add variant', icon: 'add', primary: true, disabled: saving, attrs: { 'data-testid': 'pb-loc-variant-create' }, onclick: create }
                ];
            }
        }
    });
}

function findLocation(objectId) {
    for (let i = 0; i < locations.length; i++) if (locations[i].objectId === objectId) return locations[i];
    return null;
}

/** True when the open draft for this location would PATCH something (an opened, untouched editor is not dirty). */
export function isEditDirty(objectId) {
    let loc = findLocation(objectId);
    let draft = edits[objectId];
    return !!(loc && draft && Object.keys(editPatch(loc, draft)).length);
}

/** Hold the dirtyGuard source while any inline edit is open (§6.2); drop it when none is. */
function syncDirtyRegistration() {
    if (!Object.keys(edits).length) { unregisterDirty(LOCATION_EDIT_DIRTY_KEY); return; }
    registerDirty(LOCATION_EDIT_DIRTY_KEY, {
        get label() {
            let names = Object.keys(edits).filter(isEditDirty).map(function (id) { let l = findLocation(id); return l ? l.name : id; });
            return 'Location edits' + (names.length ? ': ' + names.join(', ') : '');
        },
        isDirty: function () { return Object.keys(edits).some(isEditDirty); },
        save: async function () {
            let ids = Object.keys(edits).filter(isEditDirty);
            for (let i = 0; i < ids.length; i++) {
                let loc = findLocation(ids[i]);
                if (!loc || !(await saveEdit(loc))) return false;
            }
            return true;
        },
        discard: function () { edits = {}; syncDirtyRegistration(); }
    });
}

export function beginEdit(loc) {
    edits[loc.objectId] = { description: loc.description || '', kind: loc.kind ? String(loc.kind).toLowerCase() : '', aliases: (loc.aliases || []).join(', ') };
    syncDirtyRegistration();
}

export function cancelEdit(objectId) {
    delete edits[objectId];
    syncDirtyRegistration();
}

/** Saves the draft's changed keys only. true = nothing to save or saved (editor closed); false = the PATCH failed (draft kept). */
export async function saveEdit(loc) {
    let draft = edits[loc.objectId];
    if (!draft) return true;
    let body = editPatch(loc, draft);
    if (!Object.keys(body).length) { cancelEdit(loc.objectId); m.redraw(); return true; }
    let r = await act(loc.objectId, function () { return updateLocation(bookObjectId, loc.objectId, body); }, 'Saved ' + loc.name);
    if (r) cancelEdit(loc.objectId);
    m.redraw();
    return !!r;
}

export function toggleLock(loc) {
    return act(loc.objectId, function () { return lockLocation(bookObjectId, loc.objectId, !loc.locked); },
        loc.locked ? 'Unlocked ' + loc.name : 'Locked ' + loc.name);
}

export async function confirmDelete(loc) {
    let ok = await Dialog.confirm({
        title: 'Delete location',
        message: 'Delete "' + loc.name + '" with its ' + (loc.variants || []).length + ' variant(s) and this book\'s renders of it? Scenes still pointing at it block the delete.',
        confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true
    });
    if (!ok) return;
    await act(loc.objectId, function () { return deleteLocation(bookObjectId, loc.objectId); }, 'Deleted ' + loc.name);
}

export function openMergeDialog(duplicate, preselectName) {
    let candidates = locations.filter(function (l) { return l.objectId !== duplicate.objectId; });
    let pre = matchLocations(candidates, preselectName, duplicate.objectId);
    let survivorOid = pre.length ? pre[0].objectId : (candidates.length ? candidates[0].objectId : null);
    let merging = false;
    let merge = async function () {
        if (merging || !survivorOid) return;
        merging = true;
        m.redraw();
        let survivor = candidates.find(function (l) { return l.objectId === survivorOid; });
        Dialog.close();
        await act(duplicate.objectId, function () { return mergeLocations(bookObjectId, duplicate.objectId, survivorOid); },
            'Merged ' + duplicate.name + ' into ' + (survivor ? survivor.name : survivorOid));
        merging = false;
    };
    Dialog.open({
        title: 'Merge ' + duplicate.name + ' into…',
        size: 'md',
        content: {
            view: function () {
                return m('div', { 'data-testid': 'pb-loc-merge-form', class: 'space-y-3 text-sm' }, [
                    m('p', { class: 'text-gray-600 dark:text-gray-300' }, 'The survivor keeps its name and render; the duplicate\'s name and aliases fold into the survivor\'s aliases and every scene is repointed.'),
                    candidates.length ? field('Survivor', m('select', {
                        'data-testid': 'pb-loc-merge-survivor', class: INPUT, value: survivorOid || '',
                        onchange: function (e) { survivorOid = e.target.value || null; }
                    }, candidates.map(function (l) {
                        return m('option', { value: l.objectId, selected: l.objectId === survivorOid }, l.name + (l.variants && l.variants.length ? ' (' + l.variants.length + ' variants)' : ''));
                    }))) : m('div', { 'data-testid': 'pb-loc-merge-none', class: 'text-amber-700' }, 'There is no other location to merge into.')
                ]);
            }
        },
        actions: {
            view: function () {
                return [
                    { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
                    { label: merging ? 'Merging…' : 'Merge', icon: 'merge', primary: true, disabled: merging || !survivorOid, attrs: { 'data-testid': 'pb-loc-merge-confirm' }, onclick: merge }
                ];
            }
        }
    });
}

// Library-only resolution (no LLM call), as the board does; never overwrites a pick. Returns the in-flight
// promise so propose() can wait for it.
export function ensureChatConfigDefault() {
    if (chatConfigRef) return Promise.resolve(chatConfigRef);
    if (chatConfigPending) return chatConfigPending;
    chatConfigResolving = true;
    let bookId = bookObjectId;
    chatConfigPending = resolveDefaultChatConfig().then(function (found) {
        chatConfigResolving = false;
        chatConfigPending = null;
        if (found && !chatConfigRef && bookId === bookObjectId) chatConfigRef = found;
        m.redraw();
        return chatConfigRef;
    });
    return chatConfigPending;
}

export function pickChatConfig(ref) {
    chatConfigRef = ref;
    m.redraw();
}

export async function propose() {
    if (proposing) return;
    proposing = true;
    proposeJob = null;
    m.redraw();
    try {
        await ensureChatConfigDefault();
        let r = await proposeLocations(bookObjectId, chatConfigName(chatConfigRef));
        if (!r.jobId) {
            page.toast('error', 'The location pass was accepted but no job id was returned');
        } else {
            let job = await pollJob(r.jobId, { intervalMs: pollIntervalMs, onProgress: function (j) { proposeJob = j; m.redraw(); } });
            if (job.error) {
                page.toast('error', 'Location pass failed: ' + job.error);
            } else {
                let pass = locationPassFromJob(job);
                if (pass.cancelled) {
                    page.toast('warn', 'Location pass cancelled');
                } else if (pass.hardFailure) {
                    page.toast('error', 'Location pass failed: ' + (pass.warnings[0] || 'the LLM returned nothing usable'));
                } else {
                    page.toast('success', 'Proposed ' + pass.proposed + ' location' + (pass.proposed === 1 ? '' : 's') + ' from ' + pass.scenesConsidered + ' scene' + (pass.scenesConsidered === 1 ? '' : 's')
                        + (pass.scenesSkipped ? ' (' + pass.scenesSkipped + ' skipped)' : '') + (pass.warnings.length ? ' · ' + pass.warnings.length + ' warning(s)' : ''));
                }
            }
            proposalFilter = 'PROPOSED';
        }
    } catch (e) {
        page.toast('error', (e && e.message) || 'The location pass failed');
    }
    proposing = false;
    proposeJob = null;
    m.redraw();
    await refetch();
}

export function setProposalFilter(f) {
    f = String(f || 'PROPOSED').toUpperCase();
    if (PROPOSAL_STATUSES.indexOf(f) < 0 || f === proposalFilter) return;
    proposalFilter = f;
    loadProposals();
}

export function acceptProposal(p) {
    return act(p.objectId, function () { return acceptLocationProposal(bookObjectId, p.objectId, !!overwrite[p.objectId]); }, function (r) {
        return 'Accepted ' + p.proposedName + ': ' + (r.locationCreated ? 'created' : 'matched an existing location') + ', ' + r.scenesRepointed + ' scene' + (r.scenesRepointed === 1 ? '' : 's') + ' repointed'
            + (r.scenesSkipped ? ', ' + r.scenesSkipped + ' skipped' : '') + (r.aliasesFolded ? ', ' + r.aliasesFolded + ' alias(es) folded' : '');
    });
}

export function openDismissDialog(p) {
    let reason = '';
    Dialog.open({
        title: 'Dismiss proposal ' + p.proposedName,
        size: 'sm',
        content: {
            view: function () {
                return field('Reason (optional)', m('textarea', { 'data-testid': 'pb-loc-dismiss-reason', class: INPUT, rows: 2, value: reason, oninput: function (e) { reason = e.target.value; } }));
            }
        },
        actions: [
            { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } },
            {
                label: 'Dismiss', icon: 'block', destructive: true, attrs: { 'data-testid': 'pb-loc-dismiss-confirm' },
                onclick: function () {
                    Dialog.close();
                    act(p.objectId, function () { return rejectLocationProposal(bookObjectId, p.objectId, reason.trim()); }, 'Dismissed ' + p.proposedName);
                }
            }
        ]
    });
}

/** "Merge into…" = accept (so the proposal becomes a location) then pick the survivor, pre-selecting the sameAs match. */
export async function acceptAndMerge(p) {
    let r = await acceptProposal(p);
    if (!r || !r.locationObjectId) return;
    let dup = locations.find(function (l) { return l.objectId === r.locationObjectId; });
    if (!dup) { page.toast('warn', 'Accepted, but the new location is not in the library yet; refresh and merge from its card'); return; }
    openMergeDialog(dup, p.sameAs || p.proposedName);
}

// ── Render / run watching ──────────────────────────────────────────────

/** bookActivity key of a render subject: 'location-render:<location>' (a variant adds ':<variant>'). */
export function activityKeyFor(loc, variant) {
    if (!loc) return null;
    return 'location-render:' + loc.objectId + (variant ? ':' + variant.objectId : '');
}

/**
 * Stop watching (the server job carries on). A run that was still being watched leaves the activity store with
 * it: nothing would update it any more, and a stale "running" entry would mark the section running for good.
 */
function stopWatching(key) {
    let keys = key ? [key] : Object.keys(runAborts);
    keys.forEach(function (k) {
        if (runAborts[k]) {
            try { runAborts[k].abort(); } catch (_) {}
            let run = runs[k];
            if (run && run.activityKey && !(run.job && run.job.terminal)) clearActivity(run.bookObjectId, run.activityKey);
        }
        delete runAborts[k];
    });
}

/** The jobRunPanel opts of one run — published to bookActivity (the workspace dock / header pill), never into the body. */
export function runPanelOpts(key) {
    let run = runs[key];
    if (!run) return null;
    let job = run.job;
    let terminal = !!(job && job.terminal);
    let stages = deriveStageList(run.planned, job);
    let total = job && typeof job.total === 'number' ? job.total : stages.length;
    let current = job && typeof job.current === 'number' ? job.current : 0;
    // a watch that ended in an error is over (nothing polls it any more): terminal + failed, so the dock / pill stop "running"
    return {
        compact: true, testid: 'pb-loc-run', terminal: terminal || !!run.error, failed: !!(job && (job.error || job.failed)) || !!run.error,
        label: run.label,
        counter: terminal ? runSummaryText(job) : (current + ' of ' + total + (job && job.phase ? ' · ' + job.phase : '')),
        counterTestid: 'pb-loc-run-counter',
        error: run.error,
        onDismiss: function () { dismissRun(key); }, dismissTestid: 'pb-loc-run-dismiss',
        stages: stages, stageTestid: 'pb-loc-run-stage', emptyText: 'Waiting for the first poll…',
        section: 'locations'
    };
}

function publishRun(key) {
    let run = runs[key];
    if (!run || !run.activityKey) return;
    setActivity(run.bookObjectId, run.activityKey, runPanelOpts(key));
}

export async function startRender(loc, variant) {
    let key = subjectKey(loc, variant);
    if (!key || busy[key]) return;
    let node = variant ? variant.node : loc.node;
    busy[key] = true;
    delete pinnedRefusals[key];
    m.redraw();
    let submit = null;
    try {
        submit = await renderLocation(bookObjectId, loc.objectId, variant ? variant.objectId : null);
    } catch (e) {
        if (isPinnedNodeRefusal(e)) pinnedRefusals[key] = { message: e.message, nodeObjectId: node ? node.objectId : null };
        page.toast('error', (e && e.message) || 'Render was refused');
    }
    delete busy[key];
    m.redraw();
    if (submit) watchRun(key, submit, 'Render ' + (variant ? loc.name + ' / ' + (variant.name || variantDeltaText(variant)) : loc.name), activityKeyFor(loc, variant));
}

async function watchRun(key, submit, label, activityKey) {
    stopWatching(key);
    if (!submit.jobId) {
        page.toast('error', 'Render was accepted but no job id was returned');
        return;
    }
    let ctl = new AbortController();
    runAborts[key] = ctl;
    let bookId = bookObjectId;
    let run = { jobId: submit.jobId, runObjectId: submit.runObjectId, planned: submit.planned, job: null, label: label, error: null, bookObjectId: bookId, activityKey: activityKey };
    runs[key] = run;
    publishRun(key);
    m.redraw();
    try {
        let job = await pollJob(submit.jobId, {
            signal: ctl.signal,
            intervalMs: pollIntervalMs,
            onProgress: function (j) { if (runs[key] === run) { run.job = j; publishRun(key); m.redraw(); } }
        });
        run.job = job;
        if (runs[key] === run) publishRun(key);
        if (bookId === bookObjectId) {
            let r = job && job.result;
            let failed = !!(job && (job.error || job.cancelled)) || !!(r && (r.failedNodeCount || String(r.runStatus || '').toUpperCase() === 'FAILED'));
            page.toast(failed ? 'warn' : 'success', runSummaryText(job));
            await refetch();
        }
    } catch (e) {
        if (e && e.name === 'AbortError') return;
        run.error = (e && e.message) || String(e);
        if (runs[key] === run) publishRun(key);
        page.toast('error', run.error);
    }
    if (runAborts[key] === ctl) delete runAborts[key];
    m.redraw();
}

export async function unpinAndRender(loc, variant) {
    let key = subjectKey(loc, variant);
    let ref = pinnedRefusals[key];
    let nodeOid = (ref && ref.nodeObjectId) || ((variant ? variant.node : loc.node) || {}).objectId;
    if (!nodeOid) { page.toast('error', 'The pinned node is not known to this view; refresh and try again'); return; }
    busy[key] = true;
    m.redraw();
    let ok = false;
    try {
        await unpinNode(bookObjectId, nodeOid);
        ok = true;
    } catch (e) {
        page.toast('error', (e && e.message) || 'Unpin failed');
    }
    delete busy[key];
    if (ok) delete pinnedRefusals[key];
    m.redraw();
    if (ok) await startRender(loc, variant);
}

function dismissRun(key) {
    stopWatching(key);
    let run = runs[key];
    if (run && run.activityKey) clearActivity(run.bookObjectId, run.activityKey);
    delete runs[key];
    m.redraw();
}

function artifactAct(label, fn, artifactObjectId) {
    return act(artifactObjectId, function () { return fn(bookObjectId, artifactObjectId); }, label);
}

// ── Rendering ──────────────────────────────────────────────────────────

function kindSelect(testid, value, onchange) {
    return m('select', { 'data-testid': testid, class: INPUT, value: value || '', onchange: function (e) { onchange(e.target.value); } }, [
        m('option', { value: '' }, '(unset)')
    ].concat(LOCATION_KINDS.map(function (k) { return m('option', { value: k, selected: k === value }, k); })));
}

function statusClass(rs) {
    switch (rs) {
        case 'APPROVED': return 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200';
        case 'REJECTED': return 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200';
        case 'ARCHIVED': return 'bg-gray-200 text-gray-600 dark:bg-gray-700 dark:text-gray-300';
        case 'IN_REVIEW': return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-200';
        default: return 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-200';
    }
}

/** One artifact of a location/variant chain; the actions offered are exactly artifactActions(a, node.pinned). */
function renderChainArtifact(loc, node, a) {
    let rs = reviewStatusOf(a);
    let acts = artifactActions(a, !!node.pinned);
    let isBusy = !!busy[a.objectId] || loc.locked;
    let url = a.dataObjectId ? imageUrls[a.dataObjectId] : null;
    return m('div', {
        key: a.objectId,
        'data-testid': 'pb-loc-artifact',
        'data-artifact-id': a.objectId,
        'data-review-status': rs,
        'data-selected': a.selected ? '1' : '0',
        class: 'flex items-center gap-2 p-1.5 rounded border dark:border-gray-700 ' + (a.selected ? 'border-teal-400 bg-teal-50/40 dark:bg-teal-900/10' : '')
    }, [
        url
            ? m('img', { src: url, class: 'w-12 h-12 object-cover rounded flex-shrink-0', loading: 'lazy' })
            : m('div', { class: 'w-12 h-12 rounded bg-gray-100 dark:bg-gray-800 flex items-center justify-center flex-shrink-0' },
                m('span', { class: 'material-symbols-outlined text-gray-400' }, 'image')),
        m('div', { class: 'flex-1 min-w-0 text-[11px]' }, [
            m('div', { class: 'flex items-center gap-1.5 flex-wrap' }, [
                m('span', { class: 'font-mono' }, 'rev ' + (a.revision != null ? a.revision : '?')),
                m('span', { class: 'px-1.5 py-0.5 rounded ' + statusClass(rs) }, rs),
                a.selected ? m('span', { 'data-testid': 'pb-loc-artifact-current', class: 'px-1.5 py-0.5 rounded bg-teal-100 text-teal-800 dark:bg-teal-900/40 dark:text-teal-200' }, 'current') : null,
                isAutoApproved(a) ? m('span', { class: 'text-gray-500' }, 'auto-approved') : null
            ]),
            a.seed != null || a.backend ? m('div', { class: 'text-gray-500 truncate' }, [a.backend || '', a.seed != null ? (a.backend ? ' · ' : '') + 'seed ' + a.seed : ''].join('')) : null
        ]),
        m('div', { class: 'flex flex-col gap-0.5' }, [
            acts.canUse ? m('button', {
                'data-testid': 'pb-loc-use', class: BTN, disabled: isBusy, title: 'Make this revision the location\'s image',
                onclick: function () { artifactAct('Revision ' + a.revision + ' is now current', selectArtifact, a.objectId); }
            }, 'Use this') : null,
            acts.canApprove ? m('button', {
                'data-testid': 'pb-loc-approve', class: BTN, disabled: isBusy, title: loc.locked ? 'Unlock the location to approve a render' : 'Approve this render for the location',
                onclick: function () { artifactAct('Approved', approveArtifact, a.objectId); }
            }, 'Approve') : null,
            acts.canReject ? m('button', {
                'data-testid': 'pb-loc-reject', class: BTN_DANGER, disabled: isBusy, title: loc.locked ? 'Unlock the location to reject a render' : 'Reject this render',
                onclick: function () { artifactAct('Rejected', rejectArtifact, a.objectId); }
            }, 'Reject') : null
        ])
    ]);
}

function renderChain(loc, node) {
    if (!node) return m('div', { 'data-testid': 'pb-loc-not-rendered', class: 'text-xs text-gray-400 italic' }, 'Not rendered yet');
    let chain = orderNewestFirst(node.chain);
    return m('div', { 'data-testid': 'pb-loc-chain', 'data-node-id': node.objectId || '', 'data-node-pinned': node.pinned ? '1' : '0', class: 'space-y-1' }, [
        m('div', { class: 'flex items-center gap-2 text-[11px] text-gray-500' }, [
            m('span', { class: 'font-mono' }, node.handle || ''),
            node.storedStatus ? m('span', { class: CHIP }, String(node.storedStatus)) : null,
            node.pinned ? m('span', { 'data-testid': 'pb-loc-node-pinned', class: 'px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200' }, 'pinned') : null,
            node.lastError ? m('span', { class: 'text-red-500 truncate', title: node.lastError }, node.lastError) : null
        ]),
        chain.length ? chain.map(function (a) { return renderChainArtifact(loc, node, a); })
            : m('div', { 'data-testid': 'pb-loc-chain-empty', class: 'text-xs text-gray-400 italic' }, 'No artifact yet')
    ]);
}

function renderPinnedRefusal(loc, variant) {
    let key = subjectKey(loc, variant);
    let ref = pinnedRefusals[key];
    if (!ref) return null;
    return m('div', { 'data-testid': 'pb-loc-pinned', class: 'mt-2 flex items-center gap-2 text-xs px-2 py-1.5 rounded bg-amber-50 dark:bg-amber-900/20 text-amber-800 dark:text-amber-200' }, [
        m('span', { class: 'material-symbols-outlined text-base' }, 'push_pin'),
        m('span', { class: 'flex-1' }, ref.message),
        m('button', { 'data-testid': 'pb-loc-unpin', class: BTN, disabled: !!busy[key], onclick: function () { unpinAndRender(loc, variant); } }, 'Unpin and render')
    ]);
}

function renderButton(loc, variant) {
    let key = subjectKey(loc, variant);
    let watching = !!runAborts[key];
    return m('button', {
        'data-testid': variant ? 'pb-loc-variant-render' : 'pb-loc-render',
        class: BTN_PRIMARY,
        disabled: loc.locked || !!busy[key] || watching,
        title: loc.locked ? 'Unlock the location to render it' : watching ? 'A render is being watched' : 'Render on the server\'s SD backend',
        onclick: function () { startRender(loc, variant); }
    }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, 'brush'), watching ? 'Rendering…' : 'Render']);
}

function renderVariant(loc, v) {
    return m('div', { key: v.objectId, 'data-testid': 'pb-loc-variant', 'data-variant-id': v.objectId, class: 'p-2 rounded border border-dashed dark:border-gray-700 space-y-1' }, [
        m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
            m('span', { 'data-testid': 'pb-loc-variant-name', class: 'text-xs font-medium' }, v.name || variantDeltaText(v)),
            v.name && variantDeltaText(v) !== v.name ? m('span', { class: 'text-[11px] text-gray-500' }, variantDeltaText(v)) : null,
            v.approvedRenderObjectId ? m('span', { class: CHIP, title: 'Approved render ' + v.approvedRenderObjectId }, 'approved') : null,
            m('span', { class: 'flex-1' }),
            renderButton(loc, v)
        ]),
        m('div', { 'data-testid': 'pb-loc-variant-used-by', class: 'text-[11px] text-gray-500' }, usedByView(v.usedByScenes)),
        renderChain(loc, v.node),
        renderPinnedRefusal(loc, v)
    ]);
}

/**
 * "Used by scenes 3, 7, 12" — inside the workspace each number links to that scene's Page tab (§4.7) through the
 * dirty guard; an index whose usedBy entry carries no sceneObjectId, and the legacy route, stay plain text.
 */
function usedByView(usedBy) {
    let text = usedByText(usedBy);
    let links = usedBySceneLinks(usedBy);
    if (!wsKind || !bookObjectId || !Object.keys(links).length || !/^Used by scenes? /.test(text)) return text;
    let prefix = /^Used by scenes? /.exec(text)[0];
    let nums = text.substring(prefix.length).split(', ');
    let kind = wsKind;
    let book = bookObjectId;
    let out = [prefix];
    nums.forEach(function (n, i) {
        if (i) out.push(', ');
        let oid = links[n];
        if (!oid) { out.push(n); return; }
        let href = editLink(kind, book, { type: 'scene', id: oid, tab: 'page' });
        out.push(m('a', {
            href: '#!' + href,
            'data-testid': 'pb-loc-scene-link',
            'data-scene-id': oid,
            class: 'text-blue-600 dark:text-blue-400 hover:underline',
            title: 'Open scene ' + n,
            onclick: function (e) {
                if (e && e.preventDefault) e.preventDefault();
                guardDirty(function () { m.route.set(href); });
            }
        }, n));
    });
    return out;
}

function renderEditor(loc) {
    let draft = edits[loc.objectId];
    let isBusy = !!busy[loc.objectId];
    let patch = editPatch(loc, draft);
    return m('div', { 'data-testid': 'pb-loc-editor', 'data-dirty': Object.keys(patch).length ? '1' : '0', class: 'space-y-2' }, [
        field('Description', m('textarea', { 'data-testid': 'pb-loc-edit-description', class: INPUT, rows: 3, value: draft.description, oninput: function (e) { draft.description = e.target.value; } }), { dirty: 'description' in patch }),
        m('div', { class: 'grid grid-cols-2 gap-3' }, [
            field('Kind', kindSelect('pb-loc-edit-kind', draft.kind, function (v) { draft.kind = v; }), { dirty: 'kind' in patch }),
            field('Aliases (comma separated)', m('input', { 'data-testid': 'pb-loc-edit-aliases', class: INPUT, value: draft.aliases, oninput: function (e) { draft.aliases = e.target.value; } }), { dirty: 'aliases' in patch })
        ]),
        m('div', { class: 'flex items-center gap-2' }, [
            m('button', { 'data-testid': 'pb-loc-save', class: BTN_PRIMARY, disabled: isBusy, onclick: function () { saveEdit(loc); } }, isBusy ? 'Saving…' : 'Save'),
            m('button', { 'data-testid': 'pb-loc-cancel-edit', class: BTN, disabled: isBusy, onclick: function () { cancelEdit(loc.objectId); } }, 'Cancel')
        ])
    ]);
}

export function renderLocationCard(loc) {
    let isBusy = !!busy[loc.objectId];
    let editing = !!edits[loc.objectId];
    return m('div', {
        key: loc.objectId,
        'data-testid': 'pb-loc-card',
        'data-location-id': loc.objectId,
        'data-location-name': loc.name || '',
        'data-locked': loc.locked ? '1' : '0',
        class: 'flex flex-col gap-3'
    }, [
        m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
            m('span', { 'data-testid': 'pb-loc-name', class: 'font-semibold text-lg', title: 'Names are not edited here: a rename is a merge' }, loc.name),
            loc.kind ? m('span', { 'data-testid': 'pb-loc-kind', class: CHIP }, String(loc.kind).toLowerCase()) : null,
            loc.locked ? m('span', { 'data-testid': 'pb-loc-locked', class: 'text-[11px] px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200' }, 'Locked') : null,
            loc.approvedRenderObjectId ? m('span', { class: CHIP, title: 'Approved render ' + loc.approvedRenderObjectId }, 'approved') : null,
            m('span', { class: 'flex-1' }),
            m('button', {
                'data-testid': 'pb-loc-lock', class: BTN, disabled: isBusy,
                title: loc.locked ? 'Unlock the location' : 'Lock the location against edits, renders and deletion',
                onclick: function () { toggleLock(loc); }
            }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, loc.locked ? 'lock_open' : 'lock'), loc.locked ? 'Unlock' : 'Lock']),
            renderButton(loc, null)
        ]),
        editing ? renderEditor(loc) : m('div', { class: 'space-y-1' }, [
            m('div', { 'data-testid': 'pb-loc-description', class: 'text-sm text-gray-700 dark:text-gray-300 whitespace-pre-wrap' }, loc.description || m('span', { class: 'italic text-gray-400' }, 'No description')),
            m('div', { class: 'flex items-center gap-1 flex-wrap' }, [
                (loc.aliases || []).map(function (a) { return m('span', { key: a, 'data-testid': 'pb-loc-alias', class: CHIP }, a); }),
                m('button', {
                    'data-testid': 'pb-loc-edit', class: 'text-[11px] text-gray-500 hover:text-gray-700 disabled:opacity-40', disabled: loc.locked || isBusy,
                    title: loc.locked ? 'Unlock the location to edit it' : 'Edit description, kind and aliases (a rename is a merge)',
                    onclick: function () { beginEdit(loc); }
                }, 'edit')
            ])
        ]),
        m('div', { 'data-testid': 'pb-loc-used-by', class: 'text-xs text-gray-500' }, usedByView(loc.usedByScenes)),
        renderChain(loc, loc.node),
        renderPinnedRefusal(loc, null),
        m('div', { class: 'flex items-center gap-2 mt-1' }, [
            m('span', { class: 'text-xs font-medium text-gray-600 dark:text-gray-300' }, 'Variants (' + (loc.variants || []).length + ')'),
            m('span', { class: 'flex-1' }),
            m('button', {
                'data-testid': 'pb-loc-add-variant', class: BTN, disabled: loc.locked || isBusy,
                title: loc.locked ? 'Unlock the location to add a variant' : 'Add a time-of-day / weather / season / viewpoint variant',
                onclick: function () { openVariantDialog(loc); }
            }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, 'add'), 'Add variant'])
        ]),
        (loc.variants || []).length ? m('div', { class: 'space-y-2' }, loc.variants.map(function (v) { return renderVariant(loc, v); })) : null,
        m('div', { class: 'flex items-center gap-2 pt-1 border-t dark:border-gray-800' }, [
            m('span', { class: 'flex-1' }),
            m('button', {
                'data-testid': 'pb-loc-merge', class: BTN, disabled: loc.locked || isBusy || locations.length < 2,
                title: locations.length < 2 ? 'There is no other location to merge into' : loc.locked ? 'Unlock the location to merge it' : 'Merge this location into another (its name becomes an alias of the survivor)',
                onclick: function () { openMergeDialog(loc, null); }
            }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, 'merge'), 'Merge into…']),
            m('button', {
                'data-testid': 'pb-loc-delete', class: BTN_DANGER, disabled: loc.locked || isBusy,
                title: loc.locked ? 'Unlock the location to delete it' : 'Delete the location, its variants and this book\'s renders of it',
                onclick: function () { confirmDelete(loc); }
            }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, 'delete'), 'Delete'])
        ])
    ]);
}

function renderProposal(p) {
    let isBusy = !!busy[p.objectId];
    let open = p.proposalStatus === 'PROPOSED';
    return m('div', { key: p.objectId, 'data-testid': 'pb-loc-proposal', 'data-proposal-id': p.objectId, 'data-proposal-status': p.proposalStatus || '', class: 'p-2 rounded border dark:border-gray-700 text-xs space-y-1' }, [
        m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
            m('span', { 'data-testid': 'pb-loc-proposal-name', class: 'font-semibold' }, p.proposedName),
            p.kind ? m('span', { class: CHIP }, String(p.kind).toLowerCase()) : null,
            m('span', { class: 'text-gray-500' }, p.sceneObjectIds.length + ' scene' + (p.sceneObjectIds.length === 1 ? '' : 's')),
            p.sameAs ? m('span', { 'data-testid': 'pb-loc-proposal-sameas', class: 'text-amber-700 dark:text-amber-300' }, 'same as: ' + p.sameAs) : null,
            !open ? m('span', { class: CHIP }, String(p.proposalStatus || '').toLowerCase()) : null,
            p.locationObjectId ? m('span', { class: 'text-gray-400 font-mono', title: 'Location ' + p.locationObjectId }, '→ location') : null
        ]),
        p.description ? m('div', { class: 'text-gray-600 dark:text-gray-300' }, p.description) : null,
        p.aliases.length ? m('div', { class: 'flex items-center gap-1 flex-wrap' }, p.aliases.map(function (a) { return m('span', { key: a, class: CHIP }, a); })) : null,
        p.reason ? m('div', { class: 'text-gray-500 italic' }, p.reason) : null,
        open ? m('div', { class: 'flex items-center gap-2 flex-wrap pt-1' }, [
            m('label', { class: 'flex items-center gap-1 text-[11px]' }, [
                m('input', { 'data-testid': 'pb-loc-proposal-overwrite', type: 'checkbox', checked: !!overwrite[p.objectId], onchange: function (e) { overwrite[p.objectId] = e.target.checked; } }),
                'overwrite description/kind of an existing match'
            ]),
            m('span', { class: 'flex-1' }),
            m('button', { 'data-testid': 'pb-loc-proposal-accept', class: BTN_PRIMARY, disabled: isBusy, onclick: function () { acceptProposal(p); } }, 'Accept'),
            m('button', { 'data-testid': 'pb-loc-proposal-merge', class: BTN, disabled: isBusy || !locations.length, title: 'Accept, then merge the result into the location it duplicates', onclick: function () { acceptAndMerge(p); } }, 'Merge into…'),
            m('button', { 'data-testid': 'pb-loc-proposal-dismiss', class: BTN_DANGER, disabled: isBusy, onclick: function () { openDismissDialog(p); } }, 'Dismiss')
        ]) : null
    ]);
}

export function renderProposalsPanel() {
    return m('div', { 'data-testid': 'pb-loc-proposals', class: 'space-y-2' }, [
        m('div', { class: 'flex items-center gap-2 mb-2' }, [
            m('span', { class: 'font-semibold text-sm' }, 'Proposals'),
            m('span', { 'data-testid': 'pb-loc-proposal-count', class: CHIP }, String(proposals.length)),
            m('span', { class: 'flex-1' }),
            m('select', { 'data-testid': 'pb-loc-proposal-filter', class: 'text-xs px-1 py-0.5 rounded border dark:border-gray-600 bg-white dark:bg-gray-800', value: proposalFilter, onchange: function (e) { setProposalFilter(e.target.value); } },
                PROPOSAL_STATUSES.map(function (s) { return m('option', { value: s, selected: s === proposalFilter }, s.toLowerCase()); }))
        ]),
        proposalsError ? m('div', { 'data-testid': 'pb-loc-proposals-error', class: 'text-xs text-red-500' }, proposalsError) : null,
        proposalsLoading && !proposals.length ? m('div', { class: 'text-xs text-gray-500' }, 'Loading proposals…') : null,
        !proposalsLoading && !proposalsError && !proposals.length ? m('div', { 'data-testid': 'pb-loc-proposals-empty', class: 'text-xs text-gray-400 italic' },
            proposalFilter === 'PROPOSED' ? 'No open proposals. "Propose from scenes" clusters every scene\'s setting into candidates.' : 'Nothing ' + proposalFilter.toLowerCase() + '.') : null,
        proposals.length ? m('div', { class: 'space-y-2' }, proposals.map(renderProposal)) : null
    ]);
}

/** The section toolbar's pageHeader opts (no back: the workspace rail replaces it, §4.2). */
export function locationsToolbarOpts() {
    return {
        title: 'Locations',
        meta: [metaText(locations.length + ' location' + (locations.length === 1 ? '' : 's'), { 'data-testid': 'pb-loc-count' })],
        refresh: { testid: 'pb-loc-refresh', disabled: loading, onclick: function () { loadLocations(); } },
        actions: [renderChatConfigControl({
            testid: 'pb-loc-chatconfig', ref: chatConfigRef, resolving: chatConfigResolving,
            title: 'The chat config "Propose from scenes" sends to the LLM',
            onPick: pickChatConfig
        }),
        m('button', {
            'data-testid': 'pb-loc-propose', class: BTN, disabled: proposing || loading,
            title: 'Cluster every scene\'s setting into location proposals with the chat config shown (one LLM call per 40 scenes; runs as a background job)',
            onclick: propose
        }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' + (proposing ? ' animate-spin' : '') }, proposing ? 'progress_activity' : 'auto_awesome'),
            proposing ? 'Proposing…' + (proposeJob && proposeJob.phase ? ' ' + proposeJob.phase : '') : 'Propose from scenes']),
        m('button', {
            'data-testid': 'pb-loc-new', class: BTN_PRIMARY, disabled: loading,
            onclick: openNewLocationDialog
        }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, 'add_location'), 'New location'])]
    };
}

/** The legacy full-page route's header: the same toolbar plus its back arrow. */
export function renderLocationsHeader() {
    return pageHeader(Object.assign({
        back: { testid: 'pb-loc-back', title: 'Back to book', onclick: function () { m.route.set('/picture-book/v2/' + bookObjectId); } }
    }, locationsToolbarOpts()));
}

/** The item the detail pane shows: the stored selection re-seated by ensureSelection; the Proposals row when there is no location. */
export function effectiveSelection() {
    if (selectedId === PROPOSALS_ITEM) return PROPOSALS_ITEM;
    let ids = locations.map(function (l) { return l.objectId; });
    let sel = ensureSelection(ids, selectedId, selectedIndex);
    if (sel != null) selectedIndex = ids.indexOf(sel);
    return sel != null ? sel : PROPOSALS_ITEM;
}

/** Select a row: through the workspace (ctx.select → dirty guard → route) when mounted there, else locally behind the guard. */
function selectRow(ctx, id) {
    if (ctx && typeof ctx.select === 'function') return ctx.select(id);
    return guardDirty(function () { selectedId = id; });
}

function locationRow(l) {
    let n = usedByCount(l.usedByScenes);
    let dirty = isEditDirty(l.objectId);
    return {
        title: l.name,
        subtitle: 'Used by ' + n + ' scene' + (n === 1 ? '' : 's'),
        dirty: dirty,
        attrs: { 'data-location-name': l.name || '', 'data-locked': l.locked ? '1' : '0' },
        badges: [
            l.kind ? { text: String(l.kind).toLowerCase(), tone: 'gray', attrs: { 'data-testid': 'pb-loc-row-kind' } } : null,
            l.locked ? { text: 'Locked', tone: 'amber', attrs: { 'data-testid': 'pb-loc-row-locked' } } : null,
            dirty ? { text: 'unsaved', tone: 'amber', attrs: { 'data-testid': 'pb-loc-row-unsaved' } } : null
        ].filter(Boolean)
    };
}

function proposalsRow() {
    let n = openProposalCount;
    return {
        title: 'Proposals',
        subtitle: n == null ? null : (n ? n + ' awaiting a decision' : 'none open'),
        attrs: { 'data-testid': 'pb-loc-proposals-row' },
        badges: n ? [{ text: String(n), tone: 'purple', attrs: { 'data-testid': 'pb-loc-proposals-row-count' } }] : []
    };
}

/** §5.5: master/detail — the pinned Proposals pseudo-row, then one row per location; the detail is the location page. */
export function renderLocationsBody(ctx) {
    let st = stateBlock({
        loading: loading && !locations.length && !error, error: error,
        loadingText: 'Loading locations...', testids: { loading: 'pb-loc-loading', error: 'pb-loc-error' }
    });
    if (st) return st;
    let items = [{ objectId: PROPOSALS_ITEM, __proposals: true }].concat(locations);
    return masterDetail({
        testid: 'pb-loc-md', listTestid: 'pb-loc-list', rowTestid: 'pb-loc-row', detailTestid: 'pb-loc-detail',
        items: items,
        selectedId: effectiveSelection(),
        onSelect: function (id) { selectRow(ctx, id); },
        rowView: function (it) { return it.__proposals ? proposalsRow() : locationRow(it); },
        detailView: function (it) {
            if (!it.__proposals) return renderLocationCard(it);
            return m('div', { class: 'space-y-3' }, [
                locations.length ? null : stateBlock({ empty: true, align: 'left', emptyText: 'No locations yet. Create one, or propose them from the scenes.', testids: { empty: 'pb-loc-empty' } }),
                renderProposalsPanel()
            ]);
        }
    });
}

/** Reset for a new book (the old route oninit's body); a re-entry of the same book only reloads. */
function enterBook(id) {
    if (id && id !== bookObjectId) {
        stopWatching();
        bookObjectId = id;
        locations = [];
        worldObjectId = null;
        error = null;
        proposals = [];
        proposalsError = null;
        proposalFilter = 'PROPOSED';
        openProposalCount = null;
        busy = {};
        edits = {};
        syncDirtyRegistration();
        runs = {};
        pinnedRefusals = {};
        overwrite = {};
        selectedId = null;
        selectedIndex = -1;
        // The book's carried config (a ChapBook's own screens register one; null for a PictureBook),
        // else the library default resolves below — the board's order (P9-8 240 d).
        chatConfigRef = carriedBookChatConfig(id);
        chatConfigResolving = false;
        chatConfigPending = null;
    }
    if (bookObjectId) {
        // drafts kept for this book (the user chose Stay / left and came back) are guarded again
        syncDirtyRegistration();
        loadLocations();
        ensureChatConfigDefault();
    }
}

function leave() {
    stopWatching();
    unregisterDirty(LOCATION_EDIT_DIRTY_KEY);
}

const locationsView = {
    oninit: function (vnode) { wsKind = null; enterBook(vnode.attrs.bookObjectId); },
    onremove: function () { leave(); },
    view: function () {
        return m('div', { 'data-testid': 'pb-loc', class: 'p-4 flex flex-col h-full' }, [
            renderLocationsHeader(),
            m('div', { class: 'flex-1 min-h-0 flex flex-col mt-2' }, renderLocationsBody(null))
        ]);
    }
};

/**
 * Edit › Locations — the bookWorkspace SectionAdapter (components/bookWorkspace.js header comment). Works for any
 * olio.pb.book objectId; ctx.kind ('pb' | 'cb') only decides where the scene links route.
 */
export const locationsSection = {
    dirtyKeys: [LOCATION_EDIT_DIRTY_KEY],
    init: function (ctx) {
        wsKind = ctx.kind || 'pb';
        enterBook(ctx.bookObjectId);
        selectedId = ctx.item || null;
    },
    select: function (ctx) { selectedId = ctx.item || null; },
    remove: function () { leave(); wsKind = null; },
    toolbar: function () { return locationsToolbarOpts(); },
    body: function (ctx) { return renderLocationsBody(ctx); },
    status: function (ctx) {
        if (!ctx || ctx.bookObjectId !== bookObjectId || openProposalCount == null) return null;
        return { kind: 'count', count: openProposalCount, title: openProposalCount + ' location proposal' + (openProposalCount === 1 ? '' : 's') + ' awaiting a decision' };
    }
};

// ── Test seams ─────────────────────────────────────────────────────────

export function __locationsStateForTest() {
    return { bookObjectId, locations, worldObjectId, loading, error, proposals, proposalFilter, proposalsLoading, proposalsError, proposing, proposeJob, busy, edits, runs, runAborts, pinnedRefusals, overwrite, imageUrls, pollIntervalMs, chatConfigRef, chatConfigResolving, openProposalCount, selectedId, selectedIndex, wsKind };
}

export function __setLocationsStateForTest(s) {
    s = s || {};
    if ('bookObjectId' in s) bookObjectId = s.bookObjectId;
    if ('chatConfigRef' in s) chatConfigRef = s.chatConfigRef || null;
    if ('chatConfigResolving' in s) chatConfigResolving = !!s.chatConfigResolving;
    if ('chatConfigPending' in s) chatConfigPending = s.chatConfigPending || null;
    if ('locations' in s) locations = s.locations || [];
    if ('worldObjectId' in s) worldObjectId = s.worldObjectId;
    if ('loading' in s) loading = s.loading;
    if ('error' in s) error = s.error;
    if ('proposals' in s) proposals = s.proposals || [];
    if ('proposalFilter' in s) proposalFilter = s.proposalFilter || 'PROPOSED';
    if ('proposalsLoading' in s) proposalsLoading = s.proposalsLoading;
    if ('proposalsError' in s) proposalsError = s.proposalsError;
    if ('proposing' in s) proposing = s.proposing;
    if ('proposeJob' in s) proposeJob = s.proposeJob || null;
    if ('busy' in s) busy = s.busy || {};
    if ('edits' in s) edits = s.edits || {};
    if ('runs' in s) runs = s.runs || {};
    if ('pinnedRefusals' in s) pinnedRefusals = s.pinnedRefusals || {};
    if ('overwrite' in s) overwrite = s.overwrite || {};
    if ('imageUrls' in s) imageUrls = s.imageUrls || {};
    if ('pollIntervalMs' in s) pollIntervalMs = s.pollIntervalMs;
    if ('loadSeq' in s) loadSeq = s.loadSeq;
    if ('openProposalCount' in s) openProposalCount = s.openProposalCount == null ? null : s.openProposalCount;
    if ('selectedId' in s) selectedId = s.selectedId || null;
    if ('selectedIndex' in s) selectedIndex = s.selectedIndex == null ? -1 : s.selectedIndex;
    if ('wsKind' in s) wsKind = s.wsKind || null;
    if ('edits' in s) syncDirtyRegistration();
}

export const routes = {
    '/picture-book/:bookObjectId/locations': {
        oninit: function (vnode) { locationsView.oninit(vnode); },
        onupdate: function (vnode) {
            let id = vnode.attrs.bookObjectId;
            if (id && id !== bookObjectId) locationsView.oninit(vnode);
        },
        view: function () { return layout(pageLayout(m(locationsView))); }
    }
};
