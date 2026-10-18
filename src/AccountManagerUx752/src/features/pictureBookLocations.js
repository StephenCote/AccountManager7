/**
 * pictureBookLocations — PB2 V3 locations library (design §26/§27, P3-7).
 *
 * Route:
 *   /picture-book/:bookObjectId/locations   (bookObjectId = olio.pb.book objectId)
 *
 * One read: GET /{book}/locations (PbServiceFacade.listLocations) — every card is a locationDto with its
 * variants, usedByScenes and (once rendered) its LOCATION node + artifact chain. Every write goes through
 * the facade routes and is followed by a refetch, so the grid only ever shows server state. A location's
 * name is not editable (a rename is a merge); description, kind and aliases are PATCHed as the changed
 * keys only. Render is asynchronous (202 {jobId}) and watched with pollJob; a pinned node is a 409 the
 * server words itself ("Node … is pinned. Unpin it first …") and is answered with an Unpin button.
 * Every server error is toasted verbatim.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { layout, pageLayout } from '../router.js';
import { Dialog } from '../components/dialogCore.js';
import { pollJob, resolveImageUrl } from '../workflows/sceneExtractor.js';
import { orderNewestFirst, reviewStatusOf, isAutoApproved, artifactActions } from './pictureBookReview.js';
import {
    listLocations, createLocation, updateLocation, deleteLocation, createLocationVariant, lockLocation,
    mergeLocations, renderLocation, isPinnedNodeRefusal, proposeLocations, listLocationProposals,
    acceptLocationProposal, rejectLocationProposal, selectArtifact, approveArtifact, rejectArtifact, unpinNode,
    deriveStageList, runSummaryText
} from '../workflows/pictureBookWorkflow.js';

let bookObjectId = null;
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
let busy = {};              // subject/proposal/artifact objectId → true while a write is in flight
let edits = {};             // location objectId → {description, kind, aliases} inline draft
let runs = {};              // subject objectId → {jobId, runObjectId, planned, job, label, error}
let runAborts = {};         // subject objectId → AbortController
let pinnedRefusals = {};    // subject objectId → {message, nodeObjectId}
let overwrite = {};         // proposal objectId → accept with {overwrite:true}
let imageUrls = {};
let pollIntervalMs = 2000;

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
    } catch (e) {
        if (seq !== loadSeq) return;
        proposals = [];
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

async function refetch() {
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

export function beginEdit(loc) {
    edits[loc.objectId] = { description: loc.description || '', kind: loc.kind ? String(loc.kind).toLowerCase() : '', aliases: (loc.aliases || []).join(', ') };
}

export function cancelEdit(objectId) {
    delete edits[objectId];
}

export async function saveEdit(loc) {
    let draft = edits[loc.objectId];
    if (!draft) return;
    let body = editPatch(loc, draft);
    if (!Object.keys(body).length) { cancelEdit(loc.objectId); m.redraw(); return; }
    let r = await act(loc.objectId, function () { return updateLocation(bookObjectId, loc.objectId, body); }, 'Saved ' + loc.name);
    if (r) cancelEdit(loc.objectId);
    m.redraw();
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

export async function propose() {
    if (proposing) return;
    proposing = true;
    m.redraw();
    try {
        let r = await proposeLocations(bookObjectId);
        if (r.hardFailure) {
            page.toast('error', 'Location pass failed: ' + (r.warnings[0] || 'the LLM returned nothing usable'));
        } else {
            page.toast('success', 'Proposed ' + r.proposed + ' location' + (r.proposed === 1 ? '' : 's') + ' from ' + r.scenesConsidered + ' scene' + (r.scenesConsidered === 1 ? '' : 's')
                + (r.scenesSkipped ? ' (' + r.scenesSkipped + ' skipped)' : '') + (r.warnings.length ? ' · ' + r.warnings.length + ' warning(s)' : ''));
        }
        proposalFilter = 'PROPOSED';
    } catch (e) {
        page.toast('error', (e && e.message) || 'The location pass failed');
    }
    proposing = false;
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

function stopWatching(key) {
    let keys = key ? [key] : Object.keys(runAborts);
    keys.forEach(function (k) {
        if (runAborts[k]) { try { runAborts[k].abort(); } catch (_) {} }
        delete runAborts[k];
    });
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
    if (submit) watchRun(key, submit, 'Render ' + (variant ? loc.name + ' / ' + (variant.name || variantDeltaText(variant)) : loc.name));
}

async function watchRun(key, submit, label) {
    stopWatching(key);
    if (!submit.jobId) {
        page.toast('error', 'Render was accepted but no job id was returned');
        return;
    }
    let ctl = new AbortController();
    runAborts[key] = ctl;
    let run = { jobId: submit.jobId, runObjectId: submit.runObjectId, planned: submit.planned, job: null, label: label, error: null };
    runs[key] = run;
    let bookId = bookObjectId;
    m.redraw();
    try {
        let job = await pollJob(submit.jobId, {
            signal: ctl.signal,
            intervalMs: pollIntervalMs,
            onProgress: function (j) { if (runs[key] === run) { run.job = j; m.redraw(); } }
        });
        run.job = job;
        if (bookId === bookObjectId) {
            let r = job && job.result;
            let failed = !!(job && (job.error || job.cancelled)) || !!(r && (r.failedNodeCount || String(r.runStatus || '').toUpperCase() === 'FAILED'));
            page.toast(failed ? 'warn' : 'success', runSummaryText(job));
            await refetch();
        }
    } catch (e) {
        if (e && e.name === 'AbortError') return;
        run.error = (e && e.message) || String(e);
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
    delete runs[key];
}

function artifactAct(label, fn, artifactObjectId) {
    return act(artifactObjectId, function () { return fn(bookObjectId, artifactObjectId); }, label);
}

// ── Rendering ──────────────────────────────────────────────────────────

const INPUT = 'w-full text-sm px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 disabled:opacity-60';
const BTN = 'text-xs px-2 py-1 rounded border dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed';
const CHIP = 'text-[11px] px-1.5 py-0.5 rounded-full bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-200';

function field(label, control) {
    return m('label', { class: 'block' }, [m('div', { class: 'text-xs font-medium text-gray-600 dark:text-gray-300 mb-1' }, label), control]);
}

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

function stageIcon(state) {
    switch (state) {
        case 'running': return 'progress_activity';
        case 'done': return 'check_circle';
        case 'failed': return 'error';
        case 'skipped': return 'push_pin';
        case 'unrun': return 'remove_circle_outline';
        default: return 'radio_button_unchecked';
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
                'data-testid': 'pb-loc-reject', class: BTN + ' text-red-600', disabled: isBusy, title: loc.locked ? 'Unlock the location to reject a render' : 'Reject this render',
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

function renderRun(key) {
    let run = runs[key];
    if (!run) return null;
    let job = run.job;
    let terminal = !!(job && job.terminal);
    let stages = deriveStageList(run.planned, job);
    let total = job && typeof job.total === 'number' ? job.total : stages.length;
    let current = job && typeof job.current === 'number' ? job.current : 0;
    return m('div', { 'data-testid': 'pb-loc-run', 'data-run-terminal': terminal ? '1' : '0', class: 'mt-2 p-2 rounded border dark:border-gray-700 bg-white dark:bg-gray-900 text-xs' }, [
        m('div', { class: 'flex items-center gap-2 mb-1' }, [
            m('span', { class: 'material-symbols-outlined text-base ' + (terminal ? 'text-gray-500' : 'text-teal-500 animate-spin') }, terminal ? 'task_alt' : 'progress_activity'),
            m('span', { class: 'font-semibold' }, run.label),
            m('span', { 'data-testid': 'pb-loc-run-counter', class: 'text-gray-500' }, terminal ? runSummaryText(job) : (current + ' of ' + total + (job && job.phase ? ' · ' + job.phase : ''))),
            m('span', { class: 'flex-1' }),
            terminal || run.error ? m('button', { 'data-testid': 'pb-loc-run-dismiss', class: 'text-gray-400 hover:text-gray-600', title: 'Dismiss', onclick: function () { dismissRun(key); } },
                m('span', { class: 'material-symbols-outlined text-base' }, 'close')) : null
        ]),
        run.error ? m('div', { class: 'text-red-500 mb-1' }, run.error) : null,
        stages.length ? m('ul', { class: 'space-y-0.5' }, stages.map(function (s) {
            return m('li', {
                key: s.handle, 'data-testid': 'pb-loc-run-stage', 'data-stage-state': s.state,
                class: 'flex items-center gap-1 ' + (s.state === 'failed' ? 'text-red-600' : s.state === 'running' ? 'text-teal-600' : s.state === 'done' ? 'text-green-700' : 'text-gray-500')
            }, [
                m('span', { class: 'material-symbols-outlined text-sm' + (s.state === 'running' ? ' animate-spin' : '') }, stageIcon(s.state)),
                m('span', { class: 'font-mono' }, s.handle),
                s.error ? m('span', { class: 'ml-1 truncate' }, '— ' + s.error) : null
            ]);
        })) : m('div', { class: 'text-gray-500' }, 'Waiting for the first poll…')
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
        class: BTN + ' bg-teal-600 text-white border-teal-600 hover:bg-teal-700',
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
        m('div', { 'data-testid': 'pb-loc-variant-used-by', class: 'text-[11px] text-gray-500' }, usedByText(v.usedByScenes)),
        renderChain(loc, v.node),
        renderPinnedRefusal(loc, v),
        renderRun(v.objectId)
    ]);
}

function renderEditor(loc) {
    let draft = edits[loc.objectId];
    let isBusy = !!busy[loc.objectId];
    return m('div', { 'data-testid': 'pb-loc-editor', class: 'space-y-2' }, [
        field('Description', m('textarea', { 'data-testid': 'pb-loc-edit-description', class: INPUT, rows: 3, value: draft.description, oninput: function (e) { draft.description = e.target.value; } })),
        m('div', { class: 'grid grid-cols-2 gap-3' }, [
            field('Kind', kindSelect('pb-loc-edit-kind', draft.kind, function (v) { draft.kind = v; })),
            field('Aliases (comma separated)', m('input', { 'data-testid': 'pb-loc-edit-aliases', class: INPUT, value: draft.aliases, oninput: function (e) { draft.aliases = e.target.value; } }))
        ]),
        m('div', { class: 'flex items-center gap-2' }, [
            m('button', { 'data-testid': 'pb-loc-save', class: BTN + ' bg-purple-600 text-white border-purple-600 hover:bg-purple-700', disabled: isBusy, onclick: function () { saveEdit(loc); } }, isBusy ? 'Saving…' : 'Save'),
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
        class: 'p-3 rounded border dark:border-gray-700 bg-white dark:bg-gray-900 flex flex-col gap-2'
    }, [
        m('div', { class: 'flex items-center gap-2 flex-wrap' }, [
            m('span', { 'data-testid': 'pb-loc-name', class: 'font-semibold text-sm', title: 'Names are not edited here: a rename is a merge' }, loc.name),
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
            m('div', { 'data-testid': 'pb-loc-description', class: 'text-xs text-gray-700 dark:text-gray-300 whitespace-pre-wrap' }, loc.description || m('span', { class: 'italic text-gray-400' }, 'No description')),
            m('div', { class: 'flex items-center gap-1 flex-wrap' }, [
                (loc.aliases || []).map(function (a) { return m('span', { key: a, 'data-testid': 'pb-loc-alias', class: CHIP }, a); }),
                m('button', {
                    'data-testid': 'pb-loc-edit', class: 'text-[11px] text-gray-500 hover:text-gray-700 disabled:opacity-40', disabled: loc.locked || isBusy,
                    title: loc.locked ? 'Unlock the location to edit it' : 'Edit description, kind and aliases (a rename is a merge)',
                    onclick: function () { beginEdit(loc); }
                }, 'edit')
            ])
        ]),
        m('div', { 'data-testid': 'pb-loc-used-by', class: 'text-xs text-gray-500' }, usedByText(loc.usedByScenes)),
        renderChain(loc, loc.node),
        renderPinnedRefusal(loc, null),
        renderRun(loc.objectId),
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
                'data-testid': 'pb-loc-delete', class: BTN + ' text-red-600', disabled: loc.locked || isBusy,
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
            m('button', { 'data-testid': 'pb-loc-proposal-accept', class: BTN + ' bg-teal-600 text-white border-teal-600 hover:bg-teal-700', disabled: isBusy, onclick: function () { acceptProposal(p); } }, 'Accept'),
            m('button', { 'data-testid': 'pb-loc-proposal-merge', class: BTN, disabled: isBusy || !locations.length, title: 'Accept, then merge the result into the location it duplicates', onclick: function () { acceptAndMerge(p); } }, 'Merge into…'),
            m('button', { 'data-testid': 'pb-loc-proposal-dismiss', class: BTN + ' text-red-600', disabled: isBusy, onclick: function () { openDismissDialog(p); } }, 'Dismiss')
        ]) : null
    ]);
}

export function renderProposalsPanel() {
    return m('div', { 'data-testid': 'pb-loc-proposals', class: 'mt-6 p-3 rounded border dark:border-gray-700' }, [
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

export function renderLocationsHeader() {
    return m('div', { class: 'flex items-center gap-3 mb-4 flex-wrap' }, [
        m('button', {
            'data-testid': 'pb-loc-back',
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Back to book',
            onclick: function () { m.route.set('/picture-book/v2/' + bookObjectId); }
        }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
        m('span', { class: 'font-semibold text-sm' }, 'Locations'),
        m('span', { 'data-testid': 'pb-loc-count', class: 'text-xs text-gray-500' }, locations.length + ' location' + (locations.length === 1 ? '' : 's')),
        m('span', { class: 'flex-1' }),
        m('button', {
            'data-testid': 'pb-loc-propose', class: BTN, disabled: proposing || loading,
            title: 'Cluster every scene\'s setting into location proposals (one LLM call per 40 scenes; synchronous)',
            onclick: propose
        }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' + (proposing ? ' animate-spin' : '') }, proposing ? 'progress_activity' : 'auto_awesome'), proposing ? 'Proposing…' : 'Propose from scenes']),
        m('button', {
            'data-testid': 'pb-loc-new', class: BTN + ' bg-purple-600 text-white border-purple-600 hover:bg-purple-700', disabled: loading,
            onclick: openNewLocationDialog
        }, [m('span', { class: 'material-symbols-outlined text-sm align-middle mr-1' }, 'add_location'), 'New location']),
        m('button', {
            'data-testid': 'pb-loc-refresh',
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            title: 'Refresh', disabled: loading,
            onclick: function () { loadLocations(); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'refresh'))
    ]);
}

export function renderLocationsBody() {
    if (loading && !locations.length && !error) return m('div', { class: 'text-sm text-gray-500 text-center py-12' }, 'Loading locations...');
    if (error) return m('div', { 'data-testid': 'pb-loc-error', class: 'text-red-500 text-sm text-center py-12' }, error);
    return m('div', { class: 'max-w-6xl mx-auto w-full' }, [
        locations.length
            ? m('div', { 'data-testid': 'pb-loc-grid', class: 'grid grid-cols-1 lg:grid-cols-2 gap-3' }, locations.map(renderLocationCard))
            : m('div', { 'data-testid': 'pb-loc-empty', class: 'text-sm text-gray-400 italic text-center py-8' }, 'No locations yet. Create one, or propose them from the scenes.'),
        renderProposalsPanel()
    ]);
}

const locationsView = {
    oninit: function (vnode) {
        let id = vnode.attrs.bookObjectId;
        if (id && id !== bookObjectId) {
            stopWatching();
            bookObjectId = id;
            locations = [];
            worldObjectId = null;
            error = null;
            proposals = [];
            proposalsError = null;
            proposalFilter = 'PROPOSED';
            busy = {};
            edits = {};
            runs = {};
            pinnedRefusals = {};
            overwrite = {};
        }
        if (bookObjectId) loadLocations();
    },
    onremove: function () { stopWatching(); },
    view: function () {
        return m('div', { 'data-testid': 'pb-loc', class: 'p-4 flex flex-col h-full' }, [
            renderLocationsHeader(),
            m('div', { class: 'flex-1 overflow-y-auto' }, renderLocationsBody())
        ]);
    }
};

// ── Test seams ─────────────────────────────────────────────────────────

export function __locationsStateForTest() {
    return { bookObjectId, locations, worldObjectId, loading, error, proposals, proposalFilter, proposalsLoading, proposalsError, proposing, busy, edits, runs, runAborts, pinnedRefusals, overwrite, imageUrls, pollIntervalMs };
}

export function __setLocationsStateForTest(s) {
    s = s || {};
    if ('bookObjectId' in s) bookObjectId = s.bookObjectId;
    if ('locations' in s) locations = s.locations || [];
    if ('worldObjectId' in s) worldObjectId = s.worldObjectId;
    if ('loading' in s) loading = s.loading;
    if ('error' in s) error = s.error;
    if ('proposals' in s) proposals = s.proposals || [];
    if ('proposalFilter' in s) proposalFilter = s.proposalFilter || 'PROPOSED';
    if ('proposalsLoading' in s) proposalsLoading = s.proposalsLoading;
    if ('proposalsError' in s) proposalsError = s.proposalsError;
    if ('proposing' in s) proposing = s.proposing;
    if ('busy' in s) busy = s.busy || {};
    if ('edits' in s) edits = s.edits || {};
    if ('runs' in s) runs = s.runs || {};
    if ('pinnedRefusals' in s) pinnedRefusals = s.pinnedRefusals || {};
    if ('overwrite' in s) overwrite = s.overwrite || {};
    if ('imageUrls' in s) imageUrls = s.imageUrls || {};
    if ('pollIntervalMs' in s) pollIntervalMs = s.pollIntervalMs;
    if ('loadSeq' in s) loadSeq = s.loadSeq;
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
