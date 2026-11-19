/**
 * bookWorkspace — the Edit-mode shell shared by PictureBook and ChapBook
 * (aiDocs/PictureBookUxConsistencyDesign.md §4.2). One component, two section configurations.
 *
 *   ┌ bookHeader (Edit) ───────────────────────────────────────────────┐
 *   │ rail (numbered sections + status) │ section toolbar / section body │
 *   ├ footer: ◂ Back: <prev>      [activity dock]        Next: <next> ▸ ┤
 *
 * A host creates one workspace per product:
 *
 *   const ws = createBookWorkspace({
 *     kind: 'pb' | 'cb',
 *     adapters: { [sectionId]: SectionAdapter },
 *     header(ctx) → bookHeader opts (title, series, chapterSelect) — mode/kind/bookObjectId are filled in,
 *     onEnter(ctx)  — once per book: load whatever the rail's statuses need
 *   });
 *   routes: ws.routes(prefixes)   // Mithril route entries for every arity
 *
 *   SectionAdapter {
 *     init(ctx)            — the section became active for this book (load its data)
 *     select(ctx)          — the :item / ?tab changed while the section stayed active (optional)
 *     remove(ctx)          — the section is being left (optional)
 *     toolbar(ctx)         → pageHeader opts (title, meta, actions, refresh) — no `back`
 *     body(ctx)            → vnode
 *     status(ctx)          → { kind:'ok'|'attention'|'count'|'running'|null, count, title } (optional)
 *     dirtyKeys            → dirtyGuard keys this section registers (rail "•"; guarded on section switch)
 *     fullBleed, hideFooter, available(ctx) → bool (hide e.g. Cover on a book type that has none)
 *   }
 *
 *   ctx = { kind, bookObjectId, section, item, tab, guided, dock, go(section, item, query), select(item, query),
 *           setTab(tab), read() }
 */
import m from 'mithril';
import { layout, pageLayout } from '../router.js';
import { bookHeader } from './bookHeader.js';
import { pageHeader } from './pageHeader.js';
import { jobRunPanel } from './jobRunPanel.js';
import { btn } from './buttons.js';
import { anyDirty, guardDirty, installUnloadGuard, installLeaveGuard, setLeaveScope } from './dirtyGuard.js';
import { activitiesFor, summaryFor } from '../workflows/bookActivity.js';
import {
    sectionsFor, sectionDef, nextSection, nextAfter, adjacent, editRoute, readRoute, editQuery
} from '../workflows/bookWorkspaceNav.js';

function statusMark(st) {
    if (!st || !st.kind) return null;
    if (st.kind === 'ok') return m('span', { class: 'material-symbols-outlined text-green-600', style: 'font-size:16px', 'data-status': 'ok', title: st.title || 'Done' }, 'check_circle');
    if (st.kind === 'attention') return m('span', { class: 'material-symbols-outlined text-amber-500', style: 'font-size:16px', 'data-status': 'attention', title: st.title || 'Needs attention' }, 'error');
    if (st.kind === 'running') return m('span', { class: 'material-symbols-outlined text-blue-500 animate-spin', style: 'font-size:16px', 'data-status': 'running', title: st.title || 'Running' }, 'progress_activity');
    if (st.kind === 'count') {
        if (!(st.count > 0)) return m('span', { class: 'material-symbols-outlined text-green-600', style: 'font-size:16px', 'data-status': 'ok', title: st.title || 'Nothing outstanding' }, 'check_circle');
        return m('span', { class: 'text-[11px] min-w-[1.25rem] text-center px-1 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200', 'data-status': 'count', 'data-count': st.count, title: st.title || '' }, String(st.count));
    }
    return null;
}

export function createBookWorkspace(cfg) {
    let kind = cfg.kind;
    let adapters = cfg.adapters || {};
    let active = { bookObjectId: null, section: null, item: null, tab: null };
    let enteredBook = null;
    let dockOpen = false;

    function ctxFor(attrs) {
        let q = editQuery(attrs);
        let section = attrs.section || null;
        let ctx = {
            kind: kind,
            bookObjectId: attrs.bookObjectId,
            section: section,
            item: attrs.item ? decodeURIComponent(attrs.item) : null,
            tab: q.tab,
            guided: q.guided,
            dock: attrs.dock === '1'
        };
        ctx.go = function (sec, item, query) {
            let qq = Object.assign({}, query || {});
            if (ctx.guided && qq.guided === undefined) qq.guided = 1;
            let route = editRoute(kind, ctx.bookObjectId, sec, item, qq);
            // Inside one section (another item, another tab) drafts are kept per item and survive the switch (§6.2-4;
            // the row "unsaved" mark shows them), so there is nothing to lose and nothing to ask. Leaving the section
            // guards that section's dirty sources.
            if (sec === ctx.section) { m.route.set(route); return Promise.resolve(true); }
            let keys = (adapters[ctx.section] && adapters[ctx.section].dirtyKeys) || null;
            return guardDirty(function () { m.route.set(route); }, keys);
        };
        ctx.select = function (item, query) { return ctx.go(ctx.section, item, query); };
        ctx.setTab = function (tab) { return ctx.go(ctx.section, ctx.item, { tab: tab }); };
        ctx.read = function () { return guardDirty(function () { m.route.set(readRoute(kind, ctx.bookObjectId)); }); };
        return ctx;
    }

    function statuses(ctx) {
        let out = {};
        sectionsFor(kind).forEach(function (s) {
            let a = adapters[s.id];
            if (a && a.status) { try { out[s.id] = a.status(ctx); } catch (e) { out[s.id] = null; } }
        });
        // a running activity marks its owning section
        let sum = summaryFor(ctx.bookObjectId);
        if (sum && sum.running) out[sum.section || 'render'] = { kind: 'running', title: sum.label };
        return out;
    }

    function available(ctx, id) {
        let a = adapters[id];
        if (!a) return false;
        return a.available ? a.available(ctx) !== false : true;
    }

    function enter(ctx) {
        if (enteredBook === ctx.bookObjectId) return;
        enteredBook = ctx.bookObjectId;
        installUnloadGuard();
        installLeaveGuard();
        if (cfg.onEnter) cfg.onEnter(ctx);
    }

    /** True while the hash route is still inside this book's Edit workspace. */
    function insideWorkspace(bookObjectId, route) {
        let base = editRoute(kind, bookObjectId);
        let r = route == null ? (m.route.get() || '') : route;
        return r === base || r.indexOf(base + '/') === 0 || r.indexOf(base + '?') === 0;
    }

    /**
     * The workspace was really left (not a remount between route arities inside it): run the active section's
     * remove(), forget the entry, so returning re-runs onEnter (fresh rail statuses) and the section's init().
     */
    function leave(bookObjectId) {
        if (!bookObjectId || insideWorkspace(bookObjectId)) return;
        let prev = adapters[active.section];
        if (prev && prev.remove && active.section && active.bookObjectId === bookObjectId) {
            try { prev.remove({ kind: kind, bookObjectId: bookObjectId, section: active.section }); } catch (e) { /* leaving never throws */ }
        }
        if (active.bookObjectId === bookObjectId) active = { bookObjectId: null, section: null, item: null, tab: null };
        if (enteredBook === bookObjectId) enteredBook = null;
        setLeaveScope(null);
    }

    /** Run the section lifecycle when the route's book / section / item / tab moves. */
    function sync(ctx) {
        if (!ctx.bookObjectId) return;
        enter(ctx);
        // The leave guard (dirtyGuard) asks before any route change that LEAVES this workspace — browser Back /
        // Forward, the app menu, a plain link — none of which go through ctx.go (§6.2-3).
        setLeaveScope(function (route) { return insideWorkspace(ctx.bookObjectId, route); });
        if (active.bookObjectId !== ctx.bookObjectId || active.section !== ctx.section) {
            let prev = adapters[active.section];
            if (prev && prev.remove && active.section) { try { prev.remove(Object.assign({}, ctx, { section: active.section })); } catch (e) { /* leaving never throws */ } }
            active = { bookObjectId: ctx.bookObjectId, section: ctx.section, item: ctx.item, tab: ctx.tab };
            let a = adapters[ctx.section];
            if (a && a.init) a.init(ctx);
            return;
        }
        if (active.item !== ctx.item || active.tab !== ctx.tab) {
            active.item = ctx.item;
            active.tab = ctx.tab;
            let a = adapters[ctx.section];
            if (a && a.select) a.select(ctx);
        }
    }

    function renderRail(ctx, st) {
        let all = sectionsFor(kind).filter(function (s) { return available(ctx, s.id); });
        let n = 0;
        let rows = [];
        let dividerDone = false;
        all.forEach(function (s) {
            if (s.advanced && !dividerDone) {
                dividerDone = true;
                rows.push(m('div', { key: '__adv', class: 'px-3 pt-3 pb-1 text-[10px] uppercase tracking-wide text-gray-400' }, 'Advanced'));
            }
            if (!s.advanced) n++;
            let isActive = s.id === ctx.section;
            let a = adapters[s.id];
            let dirty = a && a.dirtyKeys ? anyDirty(a.dirtyKeys) : false;
            rows.push(m('button', {
                key: s.id,
                type: 'button',
                'data-testid': 'ws-rail-' + s.id,
                'data-active': isActive ? '1' : '0',
                'data-status': st[s.id] && st[s.id].kind ? st[s.id].kind : '',
                class: 'w-full flex items-center gap-2 px-3 py-2 text-sm text-left '
                    + (isActive ? 'bg-purple-50 dark:bg-purple-900/30 text-purple-800 dark:text-purple-200 font-medium border-l-[3px] border-purple-500'
                        : 'text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 border-l-[3px] border-transparent'),
                onclick: function () { if (!isActive) ctx.go(s.id); }
            }, [
                m('span', { class: 'w-4 text-xs text-gray-400 text-right' }, s.advanced ? '' : String(n)),
                m('span', { class: 'material-symbols-outlined text-gray-500', style: 'font-size:18px', 'aria-hidden': 'true' }, s.icon),
                m('span', { class: 'flex-1 truncate' }, s.label),
                dirty ? m('span', { class: 'text-amber-500', title: 'Unsaved changes', 'data-rail-dirty': '1' }, '•') : null,
                statusMark(st[s.id])
            ]));
        });
        return m('nav', { 'data-testid': 'ws-rail', class: 'hidden lg:flex flex-col w-48 flex-shrink-0 border-r border-gray-200 dark:border-gray-700 overflow-y-auto bg-white dark:bg-gray-800' }, [
            ctx.guided ? m('div', { 'data-testid': 'ws-guided', class: 'px-3 pt-3 pb-1 text-xs font-medium text-purple-700 dark:text-purple-300' }, 'New book setup') : null,
            m('div', { class: 'px-3 pt-3 pb-1 text-[10px] uppercase tracking-wide text-gray-400' }, 'Sections'),
            rows
        ]);
    }

    function renderNarrowRail(ctx) {
        let all = sectionsFor(kind).filter(function (s) { return available(ctx, s.id); });
        return m('div', { class: 'lg:hidden px-4 pt-2' }, m('select', {
            'data-testid': 'ws-rail-select',
            class: 'w-full text-sm rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-2 py-1',
            value: ctx.section,
            onchange: function (e) { ctx.go(e.target.value); }
        }, all.map(function (s) { return m('option', { value: s.id }, s.label); })));
    }

    function renderDock(ctx) {
        let list = activitiesFor(ctx.bookObjectId);
        let sum = summaryFor(ctx.bookObjectId);
        if (!list.length) return m('div', { class: 'flex-1' });
        let open = dockOpen || ctx.dock || ctx.section === 'render';
        return m('div', { class: 'flex-1 relative min-w-0 mx-3', 'data-testid': 'ws-dock', 'data-open': open ? '1' : '0' }, [
            open ? m('div', {
                'data-testid': 'ws-dock-panel',
                class: 'absolute bottom-full left-0 right-0 mb-2 max-h-64 overflow-y-auto rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 shadow-lg p-2 z-10'
            }, list.map(function (a) { return m('div', { key: a.key }, jobRunPanel(a)); })) : null,
            m('button', {
                type: 'button',
                'data-testid': 'ws-dock-toggle',
                class: 'w-full flex items-center gap-2 text-xs px-3 py-1.5 rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 hover:bg-gray-50 dark:hover:bg-gray-700',
                onclick: function () { dockOpen = !open; }
            }, [
                m('span', { class: 'material-symbols-outlined' + (sum && sum.running ? ' animate-spin text-blue-500' : (sum && sum.failed ? ' text-red-600' : ' text-green-600')), style: 'font-size:16px' },
                    sum && sum.running ? 'progress_activity' : (sum && sum.failed ? 'error' : 'check_circle')),
                m('span', { class: 'truncate flex-1 text-left' }, (sum ? sum.label : '') + (sum && sum.counter ? ' · ' + sum.counter : '')),
                list.length > 1 ? m('span', { class: 'text-gray-400' }, list.length + ' jobs') : null,
                m('span', { class: 'material-symbols-outlined', style: 'font-size:16px' }, open ? 'expand_more' : 'expand_less')
            ])
        ]);
    }

    function renderFooter(ctx, st) {
        let a = adapters[ctx.section];
        if (a && a.hideFooter) return null;
        let prevId, nextId;
        if (ctx.guided) {
            prevId = adjacent(kind, ctx.section).prev;
            nextId = nextAfter(kind, st, ctx.section);
        } else {
            let adj = adjacent(kind, ctx.section);
            prevId = adj.prev;
            nextId = adj.next;
        }
        while (prevId && !available(ctx, prevId)) prevId = adjacent(kind, prevId).prev;
        while (nextId && !available(ctx, nextId)) nextId = ctx.guided ? nextAfter(kind, st, nextId) : adjacent(kind, nextId).next;
        let prev = prevId ? sectionDef(kind, prevId) : null;
        let next = nextId ? sectionDef(kind, nextId) : null;
        return m('div', { 'data-testid': 'ws-footer', class: 'flex items-center px-4 py-2 border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 flex-shrink-0' }, [
            prev ? btn({ label: 'Back: ' + prev.label, icon: 'chevron_left', size: 'sm', onclick: function () { ctx.go(prev.id); }, attrs: { 'data-testid': 'ws-back' } })
                : m('span', { class: 'w-24' }),
            renderDock(ctx),
            next ? btn({ label: 'Next: ' + next.label, icon: 'chevron_right', size: 'sm', kind: ctx.guided ? 'primary' : 'default', onclick: function () { ctx.go(next.id); }, attrs: { 'data-testid': 'ws-next' } })
                : btn({ label: 'Done — Read the book', icon: 'auto_stories', size: 'sm', kind: 'primary', onclick: function () { ctx.read(); }, attrs: { 'data-testid': 'ws-done' } })
        ]);
    }

    function renderPane(ctx) {
        let a = adapters[ctx.section];
        if (!a || !available(ctx, ctx.section)) {
            return m('div', { class: 'flex-1 p-8 text-sm text-gray-500 text-center', 'data-testid': 'ws-unknown-section' }, 'This section is not available for this book.');
        }
        let tb = a.toolbar ? a.toolbar(ctx) : null;
        let toolbar = tb ? (tb.tag ? tb : pageHeader(Object.assign({ bar: true }, tb, { back: null }))) : null;
        return m('div', { class: 'flex-1 min-w-0 flex flex-col min-h-0', 'data-testid': 'ws-pane', 'data-section': ctx.section }, [
            toolbar,
            m('div', { class: 'flex-1 min-h-0 flex flex-col ' + (a.fullBleed ? '' : 'overflow-y-auto p-4'), 'data-testid': 'ws-body' }, a.body ? a.body(ctx) : null)
        ]);
    }

    const view = {
        onremove: function (vnode) {
            let id = vnode.attrs.bookObjectId;
            // A remount between this workspace's own route arities (/edit/:section → /edit/:section/:item) also
            // removes this instance; only a real exit runs the section's remove(). Decide once the new route is live.
            setTimeout(function () { leave(id); }, 0);
        },
        view: function (vnode) {
            let ctx = ctxFor(vnode.attrs);
            if (!ctx.section) return m('div', { class: 'p-8 text-sm text-gray-500' }, 'Opening…');
            sync(ctx);
            let st = statuses(ctx);
            let h = (cfg.header ? cfg.header(ctx) : null) || {};
            return m('div', { 'data-testid': 'book-workspace', 'data-book-kind': kind, class: 'flex-1 flex flex-col h-full min-h-0 bg-gray-50 dark:bg-gray-900' }, [
                bookHeader(Object.assign({}, h, { kind: kind, bookObjectId: ctx.bookObjectId, mode: 'edit' })),
                renderNarrowRail(ctx),
                m('div', { class: 'flex-1 flex min-h-0' }, [renderRail(ctx, st), renderPane(ctx)]),
                renderFooter(ctx, st)
            ]);
        }
    };

    /** Bare /edit: wait (briefly) for statuses, then replace with the D6 section. */
    const entryView = {
        oninit: function (vnode) {
            let ctx = ctxFor(vnode.attrs);
            enter(ctx);
            let started = Date.now();
            let q = editQuery(vnode.attrs);
            let entryRoute = m.route.get();
            let state = vnode.state;
            state.cancelled = false;
            function decide() {
                // Navigated away before the statuses arrived (Back, the menu): never pull the user back into Edit.
                if (state.cancelled || m.route.get() !== entryRoute) return;
                let st = statuses(ctx);
                let known = cfg.statusesReady ? cfg.statusesReady(ctx) : true;
                if (!known && Date.now() - started < 4000) { state.timer = setTimeout(decide, 150); return; }
                let sec = nextSection(kind, st);
                if (!available(ctx, sec)) sec = sectionsFor(kind)[0].id;
                m.route.set(editRoute(kind, ctx.bookObjectId, sec, null, q.guided ? { guided: 1 } : null), null, { replace: true });
            }
            decide();
        },
        onremove: function (vnode) {
            vnode.state.cancelled = true;
            if (vnode.state.timer) clearTimeout(vnode.state.timer);
            let id = vnode.attrs.bookObjectId;
            setTimeout(function () { leave(id); }, 0);
        },
        view: function () { return m('div', { class: 'p-8 text-sm text-gray-500', 'data-testid': 'ws-opening' }, 'Opening the editor…'); }
    };

    /** prefixes: e.g. { base: '/picture-book/:bookObjectId/edit' } or { base: '/chap-book/edit/:bookObjectId' }. */
    function routes(base) {
        let r = {};
        r[base] = { render: function (vnode) { return layout(pageLayout(m(entryView, Object.assign({ key: 'entry-' + vnode.attrs.bookObjectId }, vnode.attrs)))); } };
        r[base + '/:section'] = { render: function (vnode) { return layout(pageLayout(m(view, vnode.attrs))); } };
        r[base + '/:section/:item'] = { render: function (vnode) { return layout(pageLayout(m(view, vnode.attrs))); } };
        return r;
    }

    return {
        routes: routes,
        view: view,
        /** for hosts that need the current active section (e.g. to refresh after a write) */
        activeSection: function () { return active.section; },
        __resetForTest: function () { active = { bookObjectId: null, section: null, item: null, tab: null }; enteredBook = null; dockOpen = false; }
    };
}

export default createBookWorkspace;
