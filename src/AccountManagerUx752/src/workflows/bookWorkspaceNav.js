/**
 * bookWorkspaceNav — pure navigation rules for the PictureBook / ChapBook Read | Edit modes
 * (aiDocs/PictureBookUxConsistencyDesign.md §3, §4.3, §4.7, §10 D6 / D8). No Mithril, no network: every
 * function here is a mapping from inputs to a route or a section id, so it is unit-tested directly.
 *
 *   kind: 'pb' (PictureBook) | 'cb' (ChapBook)
 *   status: { kind: 'ok' | 'attention' | 'count' | 'running' | null, count?: number, title?: string }
 */

/** Edit sections, in rail order (the wizard's order). `advanced` sections sit under a divider. */
export const PB_SECTIONS = [
    { id: 'book', label: 'Book', icon: 'menu_book' },
    { id: 'style', label: 'Style', icon: 'palette' },
    { id: 'characters', label: 'Characters', icon: 'group' },
    { id: 'locations', label: 'Locations', icon: 'location_on' },
    { id: 'scenes', label: 'Scenes', icon: 'view_carousel' },
    { id: 'cover', label: 'Cover & Art', icon: 'imagesmode' },
    { id: 'review', label: 'Review', icon: 'rate_review' },
    { id: 'render', label: 'Render', icon: 'brush' },
    { id: 'canvas', label: 'Canvas', icon: 'account_tree', advanced: true }
];

export const CB_SECTIONS = [
    { id: 'book', label: 'Book', icon: 'menu_book' },
    { id: 'style', label: 'Style', icon: 'palette' },
    { id: 'locations', label: 'Locations', icon: 'location_on' },
    { id: 'pages', label: 'Pages', icon: 'view_carousel' },
    { id: 'cover', label: 'Cover & Art', icon: 'imagesmode' },
    { id: 'review', label: 'Review', icon: 'rate_review' },
    { id: 'render', label: 'Render', icon: 'brush' },
    // A ChapBook has a workflow graph too (its pages are nodes; variants, retention and QA live there) — the canvas was
    // reachable for ChapBooks before the Read | Edit split, so it stays, as for PictureBook, under "Advanced".
    { id: 'canvas', label: 'Canvas', icon: 'account_tree', advanced: true }
];

export function sectionsFor(kind) { return kind === 'cb' ? CB_SECTIONS : PB_SECTIONS; }

export function sectionDef(kind, id) {
    let all = sectionsFor(kind);
    for (let i = 0; i < all.length; i++) if (all[i].id === id) return all[i];
    return null;
}

/** The section that holds the book's pages ('scenes' / 'pages'). */
export function pagesSection(kind) { return kind === 'cb' ? 'pages' : 'scenes'; }

/** The library route for a product. */
export function libraryRoute(kind) { return kind === 'cb' ? '/chap-book' : '/picture-book'; }

/** Read-mode route. The PB reader keeps its historical /v2/ path (D8: it is still referenced by readerRoute()). */
export function readRoute(kind, bookObjectId) {
    if (!bookObjectId) return libraryRoute(kind);
    return kind === 'cb' ? '/chap-book/read/' + bookObjectId : '/picture-book/v2/' + bookObjectId;
}

function queryString(query) {
    if (!query) return '';
    let parts = [];
    Object.keys(query).forEach(function (k) {
        let v = query[k];
        if (v === undefined || v === null || v === '' || v === false) return;
        parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v === true ? '1' : String(v)));
    });
    return parts.length ? '?' + parts.join('&') : '';
}

/** Edit-mode route: /picture-book/:id/edit[/:section[/:item]] or /chap-book/edit/:id[/:section[/:item]]. */
export function editRoute(kind, bookObjectId, section, item, query) {
    let base = kind === 'cb' ? '/chap-book/edit/' + bookObjectId : '/picture-book/' + bookObjectId + '/edit';
    if (section) {
        base += '/' + encodeURIComponent(section);
        if (item) base += '/' + encodeURIComponent(item);
    }
    return base + queryString(query);
}

/** A status is "incomplete" when it needs the user: attention, or an outstanding count. */
export function isIncomplete(status) {
    if (!status) return false;
    if (status.kind === 'attention') return true;
    if (status.kind === 'count') return (status.count | 0) > 0;
    return false;
}

/**
 * D6: where Edit opens with no explicit target. The first section in rail order (advanced sections excluded)
 * whose status is incomplete; if none, Style when the book has no style description (statuses.style is
 * 'attention' — already caught above — so this only matters when style is unknown), else the pages section.
 * While statuses are still loading (unknown), the pages section — never a guess.
 *
 * @param kind 'pb' | 'cb'
 * @param statuses { [sectionId]: status }  missing = unknown
 * @returns section id
 */
export function nextSection(kind, statuses) {
    statuses = statuses || {};
    let order = sectionsFor(kind).filter(function (s) { return !s.advanced; });
    for (let i = 0; i < order.length; i++) {
        if (isIncomplete(statuses[order[i].id])) return order[i].id;
    }
    return pagesSection(kind);
}

/**
 * Guided mode's Next: the next INCOMPLETE section after `current` (rail order, advanced excluded); if none
 * after it is incomplete, the adjacent next section; null at the end (the footer then offers "Done — Read").
 */
export function nextAfter(kind, statuses, current) {
    statuses = statuses || {};
    let order = sectionsFor(kind).filter(function (s) { return !s.advanced; });
    let idx = -1;
    for (let i = 0; i < order.length; i++) if (order[i].id === current) idx = i;
    if (idx < 0) return order.length ? order[0].id : null;
    for (let j = idx + 1; j < order.length; j++) {
        if (isIncomplete(statuses[order[j].id])) return order[j].id;
    }
    return idx + 1 < order.length ? order[idx + 1].id : null;
}

/** Plain adjacency (the footer's non-guided Back / Next). Advanced sections are only adjacent to each other. */
export function adjacent(kind, current) {
    let all = sectionsFor(kind);
    let cur = sectionDef(kind, current);
    let pool = all.filter(function (s) { return !!s.advanced === !!(cur && cur.advanced); });
    let idx = -1;
    for (let i = 0; i < pool.length; i++) if (pool[i].id === current) idx = i;
    return {
        prev: idx > 0 ? pool[idx - 1].id : null,
        next: idx >= 0 && idx + 1 < pool.length ? pool[idx + 1].id : null
    };
}

/**
 * D8: the pre-workspace PictureBook sub-routes → their Edit-mode equivalents. Returns the new route, or null
 * when the path is not a legacy sub-route. `tab` travels as a query param on scene routes.
 */
export function legacyRedirect(path) {
    if (typeof path !== 'string') return null;
    let q = '';
    let qi = path.indexOf('?');
    if (qi >= 0) { q = path.substring(qi); path = path.substring(0, qi); }
    let mm = /^\/picture-book\/([^/]+)\/(style|locations|review|compose|cover|workflow)(?:\/([^/]+))?$/.exec(path);
    if (mm) {
        let id = mm[1], sub = mm[2], item = mm[3] ? decodeURIComponent(mm[3]) : null;
        if (id === 'v2') return null;
        let map = { style: 'style', locations: 'locations', review: 'review', compose: 'scenes', cover: 'cover', workflow: 'canvas' };
        let query = null;
        if (sub === 'compose' && item) query = { tab: 'composition' };
        // style / locations / review / workflow never carried an item
        if (sub !== 'compose' && sub !== 'cover') item = null;
        let r = editRoute('pb', id, map[sub], item, query);
        // keep the original query string (merged after our own ?tab= when there is one)
        return q ? r + (query ? '&' + q.substring(1) : q) : r;
    }
    let cm = /^\/chap-book\/review\/([^/]+)$/.exec(path);
    if (cm) return editRoute('cb', cm[1], 'pages') + q;
    return null;
}

/**
 * §8.2: one book type has one Ux. A CHAPBOOK book reached on a PictureBook route goes to the ChapBook route that
 * means the same thing: Read → Read; Edit › scenes → Edit › pages; sections ChapBook has too keep their item;
 * PictureBook-only sections (characters, canvas) → the bare Edit route (next incomplete section). null when the
 * path is not a PictureBook Read / Edit route for that book.
 */
export function chapBookRouteFor(path, bookObjectId) {
    if (typeof path !== 'string' || !bookObjectId) return null;
    let q = '';
    let qi = path.indexOf('?');
    if (qi >= 0) { q = path.substring(qi); path = path.substring(0, qi); }
    if (path === '/picture-book/v2/' + bookObjectId) return readRoute('cb', bookObjectId);
    let mm = /^\/picture-book\/([^/]+)\/edit(?:\/([^/]+)(?:\/([^/]+))?)?$/.exec(path);
    if (!mm || mm[1] !== bookObjectId) return null;
    let section = mm[2] ? decodeURIComponent(mm[2]) : null;
    let item = mm[3] ? decodeURIComponent(mm[3]) : null;
    if (!section) return editRoute('cb', bookObjectId) + q;
    if (section === 'scenes') section = 'pages';
    if (!sectionDef('cb', section)) return editRoute('cb', bookObjectId);
    return editRoute('cb', bookObjectId, section, item) + q;
}

/**
 * §4.7: one place that knows where each kind of entity is edited.
 *   target: { type: 'character' | 'location' | 'scene' | 'page' | 'cover' | 'review' | 'style' | 'book' | 'render',
 *             id?, tab? }
 */
export function editLink(kind, bookObjectId, target) {
    target = target || {};
    switch (target.type) {
        case 'character': return editRoute(kind, bookObjectId, 'characters', target.id);
        case 'location': return editRoute(kind, bookObjectId, 'locations', target.id);
        case 'scene':
        case 'page': return editRoute(kind, bookObjectId, pagesSection(kind), target.id, target.tab ? { tab: target.tab } : null);
        case 'cover': return editRoute(kind, bookObjectId, 'cover', target.id);
        case 'review': return editRoute(kind, bookObjectId, 'review', target.id);
        case 'style': return editRoute(kind, bookObjectId, 'style');
        case 'book': return editRoute(kind, bookObjectId, 'book');
        case 'render': return editRoute(kind, bookObjectId, 'render');
        default: return editRoute(kind, bookObjectId);
    }
}

/** Parse the `?tab=` / `?guided=` query of an edit route (Mithril hands these over as vnode.attrs). */
export function editQuery(attrs) {
    attrs = attrs || {};
    return {
        tab: attrs.tab || null,
        guided: attrs.guided === '1' || attrs.guided === 1 || attrs.guided === true
    };
}

/**
 * The dirty guard's decision table (§6.2-3). choice: 'save' | 'discard' | 'stay'.
 * Returns { save: bool, proceed: bool }. Not dirty → proceed without asking.
 */
export function guardDecision(dirty, choice) {
    if (!dirty) return { save: false, proceed: true };
    if (choice === 'save') return { save: true, proceed: true };
    if (choice === 'discard') return { save: false, proceed: true };
    return { save: false, proceed: false };
}

/**
 * masterDetail re-seat rule (§4.4): keep the selection if it still exists; else the item at the same index;
 * else the first; null for an empty list. `prevIndex` is the selection's index in the previous list.
 */
export function ensureSelection(ids, selectedId, prevIndex) {
    if (!Array.isArray(ids) || !ids.length) return null;
    if (selectedId != null && ids.indexOf(selectedId) >= 0) return selectedId;
    if (typeof prevIndex === 'number' && prevIndex >= 0) return ids[Math.min(prevIndex, ids.length - 1)];
    return ids[0];
}
