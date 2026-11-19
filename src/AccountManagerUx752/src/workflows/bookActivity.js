/**
 * bookActivity — the one store for long-running work on a book (render runs, location renders, compose-book,
 * ChapBook renders). aiDocs/PictureBookUxConsistencyDesign.md §4.5: no surface renders job progress into the
 * page flow any more; the header pill and the workspace dock read this store.
 *
 *   setActivity(bookObjectId, key, {
 *     label, counter, terminal, failed, error,
 *     stages: [{handle, state, error, autoApproved}],   // deriveStageList rows (jobRunPanel)
 *     chips: [vnode], body: [vnode],
 *     onCancel, onDismiss, section                       // section: the Edit section that owns it ('render', ...)
 *   })
 *   clearActivity(bookObjectId, key)
 *   activitiesFor(bookObjectId) → [{key, ...}]  newest first
 *   summaryFor(bookObjectId)    → { label, counter, running, failed, terminal, count } | null
 *
 * Plain module state (like the feature modules' own state); callers m.redraw().
 */

const store = new Map(); // bookObjectId -> Map(key -> activity)
let seq = 0;

export function setActivity(bookObjectId, key, activity) {
    if (!bookObjectId || !key) return;
    let per = store.get(bookObjectId);
    if (!per) { per = new Map(); store.set(bookObjectId, per); }
    let prev = per.get(key);
    per.set(key, Object.assign({}, activity || {}, { key: key, order: prev ? prev.order : ++seq }));
}

export function clearActivity(bookObjectId, key) {
    let per = store.get(bookObjectId);
    if (!per) return;
    per.delete(key);
    if (!per.size) store.delete(bookObjectId);
}

export function getActivity(bookObjectId, key) {
    let per = store.get(bookObjectId);
    return per ? per.get(key) || null : null;
}

export function activitiesFor(bookObjectId) {
    let per = store.get(bookObjectId);
    if (!per) return [];
    return Array.from(per.values()).sort(function (a, b) { return b.order - a.order; });
}

/** One line for the header pill: the running activity if any (newest), else the newest finished one. */
export function summaryFor(bookObjectId) {
    let list = activitiesFor(bookObjectId);
    if (!list.length) return null;
    let running = list.filter(function (a) { return !a.terminal; });
    let a = running.length ? running[0] : list[0];
    return {
        key: a.key,
        label: a.label || 'Working',
        counter: a.counter || '',
        running: running.length > 0,
        failed: !!a.failed,
        terminal: !!a.terminal,
        count: list.length,
        section: a.section || 'render'
    };
}

export function __resetActivityForTest() { store.clear(); seq = 0; }
