/**
 * dirtyGuard — the one "you have unsaved edits" guard for the book workspace
 * (aiDocs/PictureBookUxConsistencyDesign.md §6.2-3).
 *
 * Surfaces register a draft source while they hold edits:
 *   registerDirty('pb-style', { label: 'Book style', isDirty: () => bool, save: async () => bool, discard: () => {} })
 *   unregisterDirty('pb-style')
 *
 * Anything that would drop drafts (select another item, switch section / tab / mode, change chapter, leave to
 * the library, a route change) calls:
 *   guardDirty(next[, keys])   → Promise<boolean>   true when `next` ran
 * With no dirty source it runs `next` at once. Otherwise it asks Save / Discard / Stay. Save runs every dirty
 * source's save(); a save that returns false (or throws) keeps the user where they are — a failed write never
 * proceeds (model-api.md: never discard the update result).
 *
 * `keys` limits the guard to some sources (e.g. selecting another scene only guards that scene's draft).
 */
import m from 'mithril';
import { Dialog } from './dialogCore.js';
import { guardDecision } from '../workflows/bookWorkspaceNav.js';

const sources = new Map();

export function registerDirty(key, src) {
    if (!key || !src || typeof src.isDirty !== 'function') return;
    sources.set(key, src);
}

export function unregisterDirty(key) { sources.delete(key); }

function dirtyEntries(keys) {
    let out = [];
    sources.forEach(function (src, key) {
        if (keys && keys.indexOf(key) < 0) return;
        let d = false;
        try { d = !!src.isDirty(); } catch (e) { d = false; }
        if (d) out.push({ key: key, src: src });
    });
    return out;
}

export function anyDirty(keys) { return dirtyEntries(keys).length > 0; }

/** Labels of the dirty sources (for the dialog and the rail's "•"). */
export function dirtyLabels(keys) {
    return dirtyEntries(keys).map(function (e) { return e.src.label || e.key; });
}

function ask(labels) {
    return new Promise(function (resolve) {
        let settled = false;
        function done(choice) {
            if (settled) return;
            settled = true;
            resolve(choice);
        }
        Dialog.open({
            title: 'Unsaved changes',
            size: 'sm',
            content: m('div', { 'data-testid': 'dirty-guard' }, [
                m('p', { class: 'text-gray-700 dark:text-gray-300 mb-2' }, 'You have unsaved changes:'),
                m('ul', { class: 'list-disc ml-6 text-sm text-gray-600 dark:text-gray-400' }, labels.map(function (l, i) { return m('li', { key: i }, l); }))
            ]),
            onClose: function () { done('stay'); },
            actions: [
                { label: 'Stay', icon: 'undo', attrs: { 'data-testid': 'dirty-guard-stay' }, onclick: function () { done('stay'); Dialog.close(); } },
                { label: 'Discard', icon: 'delete_sweep', destructive: true, attrs: { 'data-testid': 'dirty-guard-discard' }, onclick: function () { done('discard'); Dialog.close(); } },
                { label: 'Save', icon: 'save', primary: true, attrs: { 'data-testid': 'dirty-guard-save' }, onclick: function () { done('save'); Dialog.close(); } }
            ]
        });
    });
}

export async function guardDirty(next, keys) {
    let entries = dirtyEntries(keys);
    let choice = entries.length ? await ask(entries.map(function (e) { return e.src.label || e.key; })) : null;
    let d = guardDecision(entries.length > 0, choice);
    if (d.save) {
        for (let i = 0; i < entries.length; i++) {
            let ok = false;
            try { ok = entries[i].src.save ? (await entries[i].src.save()) !== false : false; } catch (e) { ok = false; }
            if (!ok) { m.redraw(); return false; }
        }
    } else if (d.proceed && entries.length) {
        entries.forEach(function (e) { try { if (e.src.discard) e.src.discard(); } catch (x) { /* a failed discard still leaves */ } });
    }
    if (!d.proceed) return false;
    if (typeof next === 'function') await next();
    m.redraw();
    return true;
}

/** Route-change helper: guard, then m.route.set. */
export function guardedRoute(route, keys) {
    return guardDirty(function () { m.route.set(route); }, keys);
}

/** beforeunload hook — the browser's own prompt while anything is dirty. Installed once. */
let unloadInstalled = false;
export function installUnloadGuard() {
    if (unloadInstalled || typeof window === 'undefined') return;
    unloadInstalled = true;
    window.addEventListener('beforeunload', function (e) {
        if (!anyDirty()) return;
        e.preventDefault();
        e.returnValue = '';
    });
}

// ── Leaving the workspace by any route (design §6.2-3) ──────────────────
// ctx.go / guardedRoute ask BEFORE they navigate. Everything else that changes the route — browser Back / Forward,
// a plain "#!" link, the app menu's own m.route.set — would skip the question and the drafts would be dropped on the
// next entry. The workspace sets a scope ("is this route still inside me?"); while it is set and something is dirty,
// a route that leaves the scope is reverted and the user is asked Save / Discard / Stay, then sent on.

let leaveScope = null;      // (route) => true while the route is inside the active workspace
let lastInside = null;      // the last route seen inside that scope
let leaveInstalled = false;
let origRouteSet = null;
let passThrough = false;    // our own navigation after the user answered

function hashRoute() {
    if (typeof window === 'undefined' || !window.location) return null;
    let h = window.location.hash || '';
    return decodeURIComponent(h.replace(/^#!/, ''));
}

/** The workspace's scope test; null clears it (the workspace was left). */
export function setLeaveScope(fn) {
    leaveScope = fn || null;
    if (leaveScope) {
        let r = (m.route && typeof m.route.get === 'function') ? m.route.get() : hashRoute();
        if (r && leaveScope(r)) lastInside = r;
    } else {
        lastInside = null;
    }
}

/** Pure: should a change to `to` be stopped and asked about? (unit-tested) */
export function shouldAskOnLeave(scope, to, dirty) {
    if (!scope || !dirty || to == null) return false;
    return !scope(to);
}

function askThenGo(to, params, opts) {
    guardDirty(function () {
        passThrough = true;
        try { (origRouteSet || m.route.set).call(m.route, to, params, opts); } finally { passThrough = false; }
    });
}

export function installLeaveGuard() {
    if (leaveInstalled || typeof window === 'undefined' || !m.route || typeof m.route.set !== 'function') return;
    leaveInstalled = true;
    origRouteSet = m.route.set;
    // Programmatic navigation from anywhere in the app.
    m.route.set = function (route, params, opts) {
        if (!passThrough && shouldAskOnLeave(leaveScope, route, anyDirty())) {
            askThenGo(route, params, opts);
            return;
        }
        if (leaveScope && route && leaveScope(route)) lastInside = route;
        return origRouteSet.call(m.route, route, params, opts);
    };
    // Back / Forward / plain links: the browser has already moved; revert synchronously (Mithril resolves the
    // popstate asynchronously, so it then resolves the reverted location) and ask.
    window.addEventListener('popstate', function () {
        let to = hashRoute();
        if (!shouldAskOnLeave(leaveScope, to, anyDirty()) || !lastInside) {
            if (leaveScope && to && leaveScope(to)) lastInside = to;
            return;
        }
        let back = lastInside;
        passThrough = true;
        try { origRouteSet.call(m.route, back, null, { replace: true }); } finally { passThrough = false; }
        askThenGo(to, null, null);
    });
}

/** Vitest: replace the router call the leave guard delegates to (the wrapper itself stays installed). */
export function __setRouteSetForTest(fn) { origRouteSet = fn; }

export function __resetDirtyForTest() { sources.clear(); leaveScope = null; lastInside = null; }

// Installed once at module load — before anything else (a test spy, another wrapper) takes m.route.set — so the leave
// guard always sits directly over the router. The workspace's own call is then a no-op.
installLeaveGuard();
