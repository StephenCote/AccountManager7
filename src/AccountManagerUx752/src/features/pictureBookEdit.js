/**
 * PictureBook Edit mode — the workspace and the sections that live on the reader's own state
 * (aiDocs/PictureBookUxConsistencyDesign.md §4.2, §5.2, §5.6, §5.9).
 *
 *   Book · Style · Characters · Locations · Scenes · Cover & Art · Review · Render │ Canvas (advanced)
 *
 * Book / Scenes / Render are defined here over pictureBook.js's module state (pbBookApi — one source of truth for
 * the reader and the editor). Style, Characters, Locations, Cover, Review and Canvas are adapters exported by their
 * own modules. Routes: /picture-book/:bookObjectId/edit[/:section[/:item]] (pictureBookWorkspace.js registers them).
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { createBookWorkspace } from '../components/bookWorkspace.js';
import { masterDetail, field, INPUT, stateBlock } from '../components/bookUi.js';
import { btn, BTN, BTN_PRIMARY, BTN_DANGER } from '../components/buttons.js';
import { chip, metaText } from '../components/pageHeader.js';
import { registerDirty, unregisterDirty, guardedRoute, guardDirty } from '../components/dirtyGuard.js';
import { renderChatConfigControl } from '../components/pbChatConfig.js';
import { summarizeHealth } from '../workflows/pictureBookWorkflow.js';
import { editLink, editRoute, readRoute, ensureSelection } from '../workflows/bookWorkspaceNav.js';
import { pbBookApi } from './pictureBook.js';
import { styleSection, loadStyleStatus } from './pictureBookStyle.js';
import { canvasSection } from './pictureBookWorkflow.js';
import * as locationsMod from './pictureBookLocations.js';
import * as reviewMod from './pictureBookReview.js';
import * as composeMod from './pictureBookCompose.js';
import * as charactersMod from '../workflows/pictureBookCharacters.js';

const st = function () { return pbBookApi.state(); };

/** Scenes that still need work: stale, rejected, or never rendered. The Scenes and Render rail counts. */
export function scenesNeedingRender(pages) {
    return (pages || []).filter(function (p) {
        if (!p) return false;
        let c = pbBookApi.sceneChip(p);
        return c === 'stale' || c === 'rejected' || !p.dataObjectId;
    }).length;
}

const CHIP_TONE = { stale: 'amber', rejected: 'red', approved: 'green', review: 'purple' };
const CHIP_TEXT = { stale: 'stale', rejected: 'rejected', approved: 'approved', review: 'in review' };

// ── Book ────────────────────────────────────────────────────────────────

export const bookSection = {
    init: function (ctx) { pbBookApi.ensureBook(ctx.bookObjectId); },
    status: function (ctx) {
        let s = st();
        if (s.bookObjectId !== ctx.bookObjectId || s.loading) return null;
        if (!s.health) return null;
        let h = summarizeHealth(s.health);
        return h.errors > 0 ? { kind: 'attention', title: h.errors + ' health problem' + (h.errors !== 1 ? 's' : '') } : { kind: 'ok' };
    },
    toolbar: function () {
        let s = st();
        return { title: 'Book', meta: [metaText(s.pages.length + ' page' + (s.pages.length !== 1 ? 's' : '') + ' · ' + s.renderedCount + ' rendered')] , refresh: { testid: 'pb-book-refresh', onclick: pbBookApi.refresh } };
    },
    body: function (ctx) {
        let s = st();
        let block = stateBlock({ loading: s.loading && !s.pages.length, error: s.error, loadingText: 'Loading the book…' });
        if (block) return block;
        let h = summarizeHealth(s.health);
        return m('div', { class: 'max-w-4xl space-y-6', 'data-testid': 'pb-book-section' }, [
            m('section', [
                m('h3', { class: 'text-sm font-semibold mb-2' }, 'Book'),
                m('dl', { class: 'grid grid-cols-[8rem_1fr] gap-y-1 text-sm' }, [
                    m('dt', { class: 'text-gray-500' }, 'Name'), m('dd', { 'data-testid': 'pb-book-name' }, s.bookName || '—'),
                    s.seriesName ? [m('dt', { class: 'text-gray-500' }, 'Series'), m('dd', s.seriesName)] : null,
                    m('dt', { class: 'text-gray-500' }, 'Scenes'), m('dd', (s.sceneTotal != null ? s.sceneTotal : s.pages.length) + ' extracted · ' + s.renderedCount + ' rendered'),
                    s.chapters && s.chapters.length > 1 ? [m('dt', { class: 'text-gray-500' }, 'Chapter'), m('dd', pbBookApi.renderChapterSelect())] : null
                ])
            ]),
            m('section', [
                m('h3', { class: 'text-sm font-semibold mb-2' }, 'Defaults'),
                m('div', { class: 'space-y-3 max-w-md' }, [
                    renderChatConfigControl({
                        testid: 'pb2-chat-config', stacked: true, ref: s.chatConfig, resolving: s.chatConfigResolving,
                        title: 'The chat config every render from this book sends to the LLM (this session)',
                        onPick: pbBookApi.setChatConfig
                    }),
                    m('label', { class: 'flex items-center gap-2 text-sm cursor-pointer' }, [
                        m('input', {
                            type: 'checkbox', 'data-testid': 'pb2-auto-approve-badge', 'data-on': s.autoApprove ? '1' : '0',
                            checked: s.autoApprove, disabled: s.autoApproveSaving,
                            onchange: function (e) { pbBookApi.toggleAutoApprove(!!e.target.checked); }
                        }),
                        m('span', 'Auto-approve rendered results'),
                        m('span', { class: 'text-xs text-gray-500' }, s.autoApprove ? '(approved and pinned)' : '(results wait in the review queue)')
                    ])
                ])
            ]),
            m('section', [
                m('h3', { class: 'text-sm font-semibold mb-2' }, 'Health'),
                s.health ? (h.errors + h.warnings > 0 ? pbBookApi.renderHealthBanner()
                    : m('div', { class: 'text-sm text-green-700 dark:text-green-400', 'data-testid': 'pb-book-healthy' }, 'No health problems found.'))
                    : m('div', { class: 'text-sm text-gray-500' }, 'The health check has not reported.')
            ]),
            m('section', [
                m('h3', { class: 'text-sm font-semibold mb-2' }, 'Chapters, sharing and retention'),
                m('p', { class: 'text-sm text-gray-600 dark:text-gray-400 mb-2' }, 'New chapter, Share and Retention work on the book’s workflow graph and are on the Canvas toolbar.'),
                btn({ label: 'Open the Canvas', icon: 'account_tree', size: 'sm', onclick: function () { ctx.go('canvas'); }, attrs: { 'data-testid': 'pb-book-open-canvas' } })
            ]),
            m('section', { class: 'border border-red-200 dark:border-red-900 rounded p-3' }, [
                m('h3', { class: 'text-sm font-semibold mb-2 text-red-700 dark:text-red-400' }, 'Danger zone'),
                m('button', { 'data-testid': 'pb-book-delete', class: BTN_DANGER, onclick: pbBookApi.deleteBook }, 'Delete this picture book')
            ])
        ]);
    }
};

// ── Scenes ──────────────────────────────────────────────────────────────

// Story edits the scene's own text. Setting / action / mood are NOT here: the compiled plan reads them from the
// composition record (olio.pb.composition settingText / action / mood), which the Composition tab edits — a second
// editor over the same-named olio.pb.scene fields would edit data the renderer does not use.
const SCENE_FIELDS = ['title', 'blurb', 'summary'];
const SCENE_FIELD_LABEL = { title: 'Title', blurb: 'Page text', summary: 'Summary' };
let sceneRecs = {};     // sceneObjectId -> { loading, rec, error }
let storyDrafts = {};   // sceneObjectId -> { field: value }
let storySaving = false;

async function searchScene(oid) {
    let q = am7client.newQuery('olio.pb.scene');
    q.cache(false);
    q.field('objectId', oid);
    q.range(0, 1);
    ['id', 'objectId', 'name', 'userEdited', 'characters'].concat(SCENE_FIELDS).forEach(function (f) {
        if (q.entity.request.indexOf(f) < 0) q.entity.request.push(f);
    });
    let qr = await am7client.search(q);
    return (qr && qr.results && qr.results[0]) || null;
}

async function loadSceneRec(oid, force) {
    if (!oid) return;
    if (!force && sceneRecs[oid] && (sceneRecs[oid].rec || sceneRecs[oid].loading)) return;
    let e = { loading: true, rec: sceneRecs[oid] ? sceneRecs[oid].rec : null, error: null };
    sceneRecs[oid] = e;
    m.redraw();
    try { e.rec = await searchScene(oid); if (!e.rec) e.error = 'Scene not found'; }
    catch (x) { e.error = (x && x.message) || 'Could not read the scene'; }
    e.loading = false;
    m.redraw();
}

function storyDirty(oid) { let d = storyDrafts[oid]; return !!(d && Object.keys(d).length); }
function anyStoryDirty() { return Object.keys(storyDrafts).some(storyDirty); }

/**
 * Story PATCH body (pure, unit-tested): schema + identity + the changed scene fields + userEdited:true. `name` is
 * never sent (common.name's \S rule rejects a blank one, and the Story tab does not edit it — model-api.md).
 */
export function buildStoryPatch(rec, draft) {
    if (!rec || !draft) return null;
    let keys = Object.keys(draft).filter(function (k) { return SCENE_FIELDS.indexOf(k) >= 0; });
    if (!keys.length) return null;
    let body = { schema: 'olio.pb.scene', id: rec.id, objectId: rec.objectId, userEdited: true };
    keys.forEach(function (k) { body[k] = draft[k]; });
    return body;
}

/** Save one scene's Story draft. true when written (or nothing to write), false on any failure — the draft stays. */
export async function saveStory(oid) {
    let e = sceneRecs[oid];
    let body = buildStoryPatch(e && e.rec, storyDrafts[oid]);
    if (!body) { delete storyDrafts[oid]; return true; }
    storySaving = true;
    m.redraw();
    let ok = false;
    try {
        let resp = await fetch(applicationPath + '/rest/model', {
            method: 'PATCH', headers: { 'Content-Type': 'application/json' }, credentials: 'include', body: JSON.stringify(body)
        });
        let text = await resp.text();
        // PATCH /rest/model answers true on success; a 2xx false (or a 4xx/5xx) means nothing was written.
        ok = resp.ok && String(text).trim() !== 'false';
        if (!ok) page.toast('error', 'Scene not saved (' + resp.status + (text ? ': ' + String(text).slice(0, 160) : '') + ')');
    } catch (x) {
        page.toast('error', 'Scene not saved: ' + ((x && x.message) || x));
    }
    storySaving = false;
    if (ok) {
        delete storyDrafts[oid];
        page.toast('success', 'Scene saved');
        am7client.clearCache('olio.pb.scene', true);
        await loadSceneRec(oid, true);
        // Stale is the server's call: re-read the pages and show whatever chip it reports.
        await pbBookApi.refresh();
    }
    m.redraw();
    return ok;
}

function registerStoryGuard() {
    registerDirty('pb-scene-story', {
        label: 'Scene story',
        isDirty: anyStoryDirty,
        save: async function () {
            let ids = Object.keys(storyDrafts).filter(storyDirty);
            for (let i = 0; i < ids.length; i++) { if (!(await saveStory(ids[i]))) return false; }
            return true;
        },
        discard: function () { storyDrafts = {}; }
    });
}

function composeApi() { return composeMod.composeApi || null; }

function selectedScene(ctx) {
    let pages = st().pages;
    let ids = pages.map(function (p) { return p.objectId; });
    let id = ensureSelection(ids, ctx.item, 0);
    let idx = ids.indexOf(id);
    return { scene: idx >= 0 ? pages[idx] : null, index: idx, ids: ids };
}

function sceneLinkRow(ctx, scene, rec) {
    let links = [];
    let chars = rec && Array.isArray(rec.characters) ? rec.characters : [];
    chars.forEach(function (c) {
        if (!c || !c.objectId) return;
        links.push(m('a', {
            key: 'c' + c.objectId,
            href: '#!' + editLink('pb', ctx.bookObjectId, { type: 'character', id: c.objectId }),
            'data-testid': 'pb-page-character-link', 'data-character-id': c.objectId,
            class: 'text-xs px-2 py-0.5 rounded-full border border-indigo-300 text-indigo-800 bg-indigo-50 dark:border-indigo-700 dark:text-indigo-200 dark:bg-indigo-900/30 hover:underline',
            onclick: function (e) { e.preventDefault(); guardedRoute(editLink('pb', ctx.bookObjectId, { type: 'character', id: c.objectId })); }
        }, c.name || 'Character'));
    });
    if (scene && scene.locationObjectId) {
        links.push(m('a', {
            key: 'loc',
            href: '#!' + editLink('pb', ctx.bookObjectId, { type: 'location', id: scene.locationObjectId }),
            'data-testid': 'pb-page-location-link',
            class: 'text-xs underline text-teal-700 dark:text-teal-300',
            onclick: function (e) { e.preventDefault(); guardedRoute(editLink('pb', ctx.bookObjectId, { type: 'location', id: scene.locationObjectId })); }
        }, 'Edit location'));
    }
    return links;
}

function tabButton(ctx, tab, label, icon) {
    return btn({ label: label, icon: icon, size: 'sm', onclick: function () { ctx.setTab(tab); }, attrs: { 'data-testid': 'pb-page-goto-' + tab } });
}

function renderPageTab(ctx, sel) {
    let scene = sel.scene;
    let e = sceneRecs[scene.objectId];
    let prevId = sel.index > 0 ? sel.ids[sel.index - 1] : null;
    let nextId = sel.index >= 0 && sel.index + 1 < sel.ids.length ? sel.ids[sel.index + 1] : null;
    return m('div', { class: 'max-w-3xl mx-auto' }, [
        m('div', { class: 'flex items-center justify-between mb-3' }, [
            btn({ label: 'Previous page', icon: 'chevron_left', size: 'sm', disabled: !prevId, onclick: function () { ctx.select(prevId, { tab: 'page' }); }, attrs: { 'data-testid': 'pb-page-prev' } }),
            m('span', { class: 'text-xs text-gray-500' }, 'Page ' + (sel.index + 1) + ' of ' + sel.ids.length),
            btn({ label: 'Next page', icon: 'chevron_right', size: 'sm', disabled: !nextId, onclick: function () { ctx.select(nextId, { tab: 'page' }); }, attrs: { 'data-testid': 'pb-page-next' } })
        ]),
        pbBookApi.renderScenePage(scene, {
            mode: 'edit',
            number: sel.index + 1,
            titleExtras: sceneLinkRow(ctx, scene, e && e.rec),
            below: m('div', { class: 'flex flex-wrap justify-center gap-2' }, [
                tabButton(ctx, 'story', 'Story', 'edit_note'),
                tabButton(ctx, 'composition', 'Composition', 'view_in_ar'),
                tabButton(ctx, 'render', 'Render', 'brush'),
                tabButton(ctx, 'review', 'Review', 'rate_review')
            ])
        })
    ]);
}

function renderStoryTab(ctx, sel) {
    let oid = sel.scene.objectId;
    let e = sceneRecs[oid];
    let block = stateBlock({ loading: !e || (e.loading && !e.rec), error: e && !e.rec ? e.error : null, loadingText: 'Reading the scene…', align: 'left' });
    if (block) return block;
    let rec = e.rec;
    let d = storyDrafts[oid] || {};
    function val(k) { return k in d ? d[k] : (rec[k] == null ? '' : rec[k]); }
    function set(k, v) {
        let orig = rec[k] == null ? '' : rec[k];
        let nd = Object.assign({}, storyDrafts[oid] || {});
        if (v === orig) delete nd[k]; else nd[k] = v;
        storyDrafts[oid] = nd;
    }
    function input(k, rows) {
        let attrs = { 'data-testid': 'pb-story-' + k, class: INPUT, value: val(k), oninput: function (ev) { set(k, ev.target.value); } };
        return field(SCENE_FIELD_LABEL[k], rows ? m('textarea', Object.assign({ rows: rows }, attrs)) : m('input', Object.assign({ type: 'text' }, attrs)), { dirty: k in d, dirtyTestid: 'pb-story-dirty-' + k });
    }
    let dirty = storyDirty(oid);
    return m('div', { class: 'max-w-3xl', 'data-testid': 'pb-story-tab' }, [
        m('div', { class: 'flex items-center gap-2 mb-3' }, [
            m('h3', { class: 'text-sm font-semibold flex-1' }, 'Story'),
            rec.userEdited ? chip('edited by hand', 'gray', { 'data-testid': 'pb-story-user-edited' }) : null,
            m('button', { 'data-testid': 'pb-story-revert', class: BTN, disabled: !dirty || storySaving, onclick: function () { delete storyDrafts[oid]; } }, 'Revert'),
            m('button', { 'data-testid': 'pb-story-save', class: BTN_PRIMARY, disabled: !dirty || storySaving, onclick: function () { saveStory(oid); } }, storySaving ? 'Saving…' : 'Save')
        ]),
        input('title'),
        input('blurb', 5),
        input('summary', 3),
        m('p', { class: 'text-xs text-gray-500 mb-2' }, [
            'Setting, action, mood and camera are part of the scene’s composition: ',
            m('a', { href: '#', 'data-testid': 'pb-story-to-composition', class: 'underline', onclick: function (ev) { ev.preventDefault(); ctx.setTab('composition'); } }, 'edit them in Composition'), '.'
        ]),
        m('p', { class: 'text-xs text-gray-500' }, 'Saving marks the scene as edited by hand. Whether its image is then out of date is the server’s call: the status chip shows what the server reports after the save.')
    ]);
}

function renderCompositionTab(ctx, sel) {
    let api = composeApi();
    if (!api || !api.renderComposition) return m('div', { class: 'text-sm text-gray-500' }, 'The composition board is not available.');
    return m('div', { 'data-testid': 'pb-composition-tab' }, api.renderComposition(Object.assign({}, ctx, { sceneObjectId: sel.scene.objectId })));
}

function renderRenderTab(ctx, sel) {
    let scene = sel.scene;
    let gate = pbBookApi.renderGate();
    let s = st();
    let imgUrl = pbBookApi.imageUrl(scene);
    let c = pbBookApi.sceneChip(scene);
    return m('div', { class: 'max-w-3xl', 'data-testid': 'pb-render-tab' }, [
        imgUrl ? m('img', { src: imgUrl, class: 'w-full rounded mb-3', style: 'max-height:45vh;object-fit:contain;', 'data-testid': 'pb-render-tab-image' })
            : m('div', { class: 'w-full rounded bg-gray-100 dark:bg-gray-800 flex items-center justify-center mb-3', style: 'height:180px' }, m('span', { class: 'material-symbols-outlined text-gray-400 text-4xl' }, 'image')),
        m('div', { class: 'flex flex-wrap items-center gap-2 mb-2' }, [
            m('button', {
                'data-testid': 'pb2-scene-render', class: BTN_PRIMARY, disabled: !gate.enabled,
                title: gate.enabled ? (c === 'rejected' ? 'Every image of this scene was rejected; render a new one' : c === 'stale' ? 'Re-render this scene' : 'Render this scene') : gate.reason,
                onclick: function () { pbBookApi.renderScene(scene); }
            }, c === 'stale' || imgUrl ? 'Re-render page' : 'Render page'),
            imgUrl ? m('button', {
                'data-testid': 'pb2-scene-regen-background', class: BTN, disabled: !gate.enabled,
                title: gate.enabled ? 'Re-render the background only; each actor keeps the seed it was rendered with' : gate.reason,
                onclick: function () { pbBookApi.regenBackground(scene); }
            }, 'Regenerate background') : null,
            m('button', {
                'data-testid': 'pb2-plan-render', class: BTN, disabled: s.submitting || s.watching,
                title: s.watching ? 'A render is already running' : 'Preview the illustration plan and estimate, then generate',
                onclick: function () { pbBookApi.openPlan(scene, sel.index + 1); }
            }, 'Plan & generate')
        ]),
        gate.needsStyle ? m('div', { class: 'mb-2' }, pbBookApi.renderNeedsStyle('pb2-scene-needs-style')) : (!gate.enabled && gate.reason ? m('div', { class: 'text-xs text-gray-500' }, gate.reason) : null),
        m('p', { class: 'text-xs text-gray-500' }, 'Progress shows in the activity bar at the bottom and in the header.')
    ]);
}

function renderReviewTab(ctx, sel) {
    let scene = sel.scene;
    let nodeOid = scene.sceneNodeObjectId;
    return m('div', { class: 'max-w-3xl text-sm', 'data-testid': 'pb-review-tab' }, [
        m('p', { class: 'mb-2' }, 'Rendered images of this scene are approved, rejected or chosen in the review queue.'),
        btn({ label: 'Open this scene in Review', icon: 'rate_review', size: 'sm', disabled: !nodeOid,
            onclick: function () { ctx.go('review', nodeOid); }, attrs: { 'data-testid': 'pb-review-tab-open' } }),
        !nodeOid ? m('p', { class: 'text-xs text-gray-500 mt-2' }, 'This scene has no workflow node yet, so nothing of it is in review.') : null
    ]);
}

const TABS = [
    { id: 'page', label: 'Page' }, { id: 'story', label: 'Story' }, { id: 'composition', label: 'Composition' },
    { id: 'render', label: 'Render' }, { id: 'review', label: 'Review' }
];

function sceneTabBar(ctx, active) {
    return m('div', { class: 'flex border-b border-gray-200 dark:border-gray-700 mb-4', role: 'tablist', 'data-testid': 'pb-scene-tabs' }, TABS.map(function (t) {
        let on = t.id === active;
        return m('button', {
            key: t.id, role: 'tab', 'aria-selected': on ? 'true' : 'false', 'data-testid': 'pb-scene-tab-' + t.id,
            class: 'px-3 py-1.5 text-sm ' + (on ? 'border-b-2 border-purple-500 text-purple-700 dark:text-purple-300 font-medium' : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'),
            onclick: function () { if (!on) ctx.setTab(t.id); }
        }, [t.label, t.id === 'story' && storyDirty(selectedScene(ctx).scene && selectedScene(ctx).scene.objectId) ? m('span', { class: 'text-amber-500 ml-1' }, '•') : null]);
    }));
}

function syncScene(ctx) {
    let sel = selectedScene(ctx);
    if (!sel.scene) return sel;
    loadSceneRec(sel.scene.objectId);
    pbBookApi.ensureSceneDegradations(sel.scene);
    let api = composeApi();
    if ((ctx.tab || 'page') === 'composition' && api && api.init) api.init(ctx.bookObjectId, sel.scene.objectId);
    return sel;
}

export const scenesSection = {
    dirtyKeys: ['pb-scene-story', 'pb-compose'],
    init: function (ctx) { registerStoryGuard(); pbBookApi.ensureBook(ctx.bookObjectId); },
    remove: function () { unregisterDirty('pb-scene-story'); },
    status: function (ctx) {
        let s = st();
        if (s.bookObjectId !== ctx.bookObjectId || s.loading) return null;
        let n = scenesNeedingRender(s.pages);
        return { kind: 'count', count: n, title: n + ' scene' + (n !== 1 ? 's' : '') + ' stale, rejected or not rendered' };
    },
    toolbar: function (ctx) {
        let s = st();
        let api = composeApi();
        return {
            title: 'Scenes',
            meta: [metaText(s.pages.length + ' scene' + (s.pages.length !== 1 ? 's' : ''))],
            actions: api && api.toolbarActions ? api.toolbarActions(ctx) : [],
            refresh: { testid: 'pb-scenes-refresh', onclick: pbBookApi.refresh }
        };
    },
    body: function (ctx) {
        let s = st();
        let block = stateBlock({ loading: s.loading && !s.pages.length, error: s.error, empty: !s.loading && !s.pages.length,
            loadingText: 'Loading scenes…', emptyText: 'This book has no scene pages yet — they are created when scenes first render (Render section).',
            testids: { empty: 'pb-scenes-empty' } });
        if (block) return block;
        let sel = syncScene(ctx);
        let tab = ctx.tab || 'page';
        return masterDetail({
            testid: 'pb-scenes', listTestid: 'pb-scene-list', rowTestid: 'pb-scene-row', detailTestid: 'pb-scene-detail',
            items: s.pages, selectedId: sel.scene ? sel.scene.objectId : null,
            onSelect: function (id) { ctx.select(id, { tab: tab }); },
            rowView: function (p) {
                let i = s.pages.indexOf(p);
                let c = pbBookApi.sceneChip(p);
                return {
                    title: (i + 1) + '. ' + (p.title || 'Scene ' + (i + 1)),
                    thumb: pbBookApi.imageUrl(p),
                    subtitle: p.locationName || null,
                    dirty: storyDirty(p.objectId),
                    badges: [c ? { text: CHIP_TEXT[c], tone: CHIP_TONE[c] } : (!p.dataObjectId ? { text: 'not rendered', tone: 'amber' } : null), p.pinned ? { text: 'pinned', tone: 'gray' } : null]
                };
            },
            detailView: function () {
                if (!sel.scene) return null;
                let content = tab === 'story' ? renderStoryTab(ctx, sel)
                    : tab === 'composition' ? renderCompositionTab(ctx, sel)
                        : tab === 'render' ? renderRenderTab(ctx, sel)
                            : tab === 'review' ? renderReviewTab(ctx, sel)
                                : renderPageTab(ctx, sel);
                return [sceneTabBar(ctx, tab), content];
            }
        });
    }
};

// ── Render ──────────────────────────────────────────────────────────────

export const renderSection = {
    init: function (ctx) { pbBookApi.ensureBook(ctx.bookObjectId); pbBookApi.loadStyleDescribed(); },
    status: function (ctx) {
        let s = st();
        if (s.bookObjectId !== ctx.bookObjectId || s.loading) return null;
        let n = scenesNeedingRender(s.pages);
        return { kind: 'count', count: n, title: n + ' scene' + (n !== 1 ? 's' : '') + ' to render' };
    },
    toolbar: function () {
        let s = st();
        let gate = pbBookApi.renderGate();
        return {
            title: 'Render',
            meta: [metaText(s.renderedCount + ' of ' + s.pages.length + ' rendered')],
            actions: [m('button', {
                'data-testid': 'pb2-render-all-start',
                class: BTN_PRIMARY,
                disabled: !gate.enabled || s.styleMissing,
                title: gate.enabled ? 'Render every scene whose image is missing, stale or rejected' : gate.reason,
                onclick: pbBookApi.renderAll
            }, s.submitting ? 'Starting…' : 'Render all')]
        };
    },
    body: function (ctx) {
        let s = st();
        let gate = pbBookApi.renderGate();
        return m('div', { class: 'max-w-4xl space-y-4', 'data-testid': 'pb-render-section' }, [
            gate.needsStyle ? m('div', { class: 'p-3 rounded bg-amber-50 dark:bg-amber-900/20' }, pbBookApi.renderNeedsStyle('pb2-render-needs-style')) : null,
            pbBookApi.renderRenderSettings(),
            m('div', { class: 'max-w-md' }, renderChatConfigControl({
                testid: 'pb-render-chat-config', ref: s.chatConfig, resolving: s.chatConfigResolving,
                title: 'The chat config the run sends to the LLM', onPick: pbBookApi.setChatConfig
            })),
            pbBookApi.renderResumeBanner(),
            m('div', [
                m('h3', { class: 'text-sm font-semibold mb-2' }, 'Scenes'),
                m('table', { class: 'w-full text-sm', 'data-testid': 'pb-render-scene-table' }, [
                    m('thead', m('tr', { class: 'text-left text-xs text-gray-500' }, [m('th', { class: 'py-1' }, '#'), m('th', 'Scene'), m('th', 'State'), m('th', '')])),
                    m('tbody', s.pages.map(function (p, i) {
                        let c = pbBookApi.sceneChip(p);
                        let state = c ? CHIP_TEXT[c] : (p.dataObjectId ? 'rendered' : 'not rendered');
                        return m('tr', { key: p.objectId, class: 'border-t border-gray-100 dark:border-gray-800' }, [
                            m('td', { class: 'py-1 pr-2 text-gray-500' }, String(i + 1)),
                            m('td', p.title || ('Scene ' + (i + 1))),
                            m('td', chip(state, c ? CHIP_TONE[c] : (p.dataObjectId ? 'green' : 'amber'))),
                            m('td', { class: 'text-right' }, m('a', {
                                href: '#!' + editRoute('pb', ctx.bookObjectId, 'scenes', p.objectId, { tab: 'render' }),
                                class: 'text-xs underline',
                                onclick: function (e) { e.preventDefault(); ctx.go('scenes', p.objectId, { tab: 'render' }); }
                            }, 'Open'))
                        ]);
                    }))
                ])
            ]),
            m('p', { class: 'text-xs text-gray-500' }, 'The run’s stage list is in the activity panel at the bottom; it stays there while you work in other sections.')
        ]);
    }
};

// ── Workspace ───────────────────────────────────────────────────────────

function adapter(mod, name) { return mod && mod[name] ? mod[name] : null; }

/**
 * Edit › Style as this workspace hosts it. A ChapBook is exempt from the style gate (pb2RenderGate, and the server
 * exempts it too), so its rail shows no ⚠ / ✓ either — the Read header never marked Style on a ChapBook, and a ⚠
 * here would also make D6 open Style first for a book whose look does not come from the style description.
 */
export const pbStyleSection = Object.assign({}, styleSection, {
    status: function (ctx) {
        let s = st();
        if (s.isChapBook && s.bookObjectId === ctx.bookObjectId) return null;
        return styleSection.status(ctx);
    }
});

function pbAdapters() {
    let a = {
        book: bookSection,
        style: pbStyleSection,
        scenes: scenesSection,
        render: renderSection,
        canvas: canvasSection
    };
    let ch = adapter(charactersMod, 'charactersSection');
    let lo = adapter(locationsMod, 'locationsSection');
    let co = adapter(composeMod, 'coverSection');
    let rv = adapter(reviewMod, 'reviewSection');
    if (ch) a.characters = ch;
    if (lo) a.locations = lo;
    if (co) a.cover = co;
    if (rv) a.review = rv;
    return a;
}

export const pbWorkspace = createBookWorkspace({
    kind: 'pb',
    adapters: pbAdapters(),
    onEnter: function (ctx) {
        let id = ctx.bookObjectId;
        pbBookApi.ensureBook(id);
        loadStyleStatus(id);
        let lc = adapter(charactersMod, 'loadCharactersStatus');
        let lr = adapter(reviewMod, 'loadReviewCount');
        if (lc) Promise.resolve(lc(id)).catch(function () {});
        if (lr) Promise.resolve(lr(id)).catch(function () {});
    },
    /** D6 waits for the pages and the style before choosing (bookWorkspace gives up after 4 s). */
    statusesReady: function (ctx) {
        let s = st();
        return s.bookObjectId === ctx.bookObjectId && !s.loading && styleSection.status(ctx) !== null;
    },
    header: function (ctx) {
        // No ensureBook here: the header renders on every redraw, and a load / refresh ends in a redraw — that was a
        // request loop (verifier finding 1). Sections load the book in init(); onEnter loads it once per entry.
        pbBookApi.publishActivity();
        let s = st();
        return {
            title: s.bookName && s.bookName !== 'Loading...' ? s.bookName : 'Picture Book',
            series: s.seriesName,
            chapterSelect: pbBookApi.renderChapterSelect(),
            // Edit → Read keeps the place: the selected scene's page (§4.3).
            onMode: function (mode) {
                if (mode !== 'read') return;
                let sceneId = ctx.section === 'scenes' ? (ctx.item || selectedScene(ctx).ids[0] || null) : null;
                // Move the reader to the page only once the guard lets the user go (Stay must not move it).
                guardDirty(function () {
                    if (sceneId) pbBookApi.goToScene(sceneId);
                    m.route.set(readRoute('pb', ctx.bookObjectId));
                });
            }
        };
    }
});

export function __resetPbEditForTest() { sceneRecs = {}; storyDrafts = {}; storySaving = false; }
export function __setPbEditStateForTest(p) {
    p = p || {};
    if ('sceneRecs' in p) sceneRecs = p.sceneRecs || {};
    if ('storyDrafts' in p) storyDrafts = p.storyDrafts || {};
}
export function __pbEditStateForTest() { return { sceneRecs, storyDrafts, storySaving }; }
