/**
 * pictureBookSeries — pure helpers for the N4 whole-series canvas view.
 *
 * Deliberately dependency-free (no mithril, no router, no DOM) so the grouping logic is unit-testable
 * in the vitest node environment without dragging in the canvas module's UI imports.
 */

/**
 * Group a flat book list into series for the whole-series view (N4).
 *
 * Returns { hasSeriesInfo, groups: [{seriesKey, seriesName, chapters:[book,...]}] }.
 *   - hasSeriesInfo is true only when at least one book carries a series linkage
 *     (`series` — an objectId string or a {objectId} record — or `seriesObjectId`).
 *   - When present, books are grouped by that key and each group's chapters are ordered by the
 *     `chapter` ordinal (name as a tiebreak).
 *   - When absent (the current /books DTO exposes neither `series` nor `chapter`), every book falls
 *     into one "All books" group so the view still functions as a cross-book navigator — the caller
 *     surfaces an honest banner that true series grouping needs the backend to expose those fields.
 */
export function groupBooksBySeries(books) {
    let list = Array.isArray(books) ? books : [];
    function seriesKeyOf(b) {
        if (!b) return null;
        let s = b.series;
        if (s && typeof s === 'object') return s.objectId || s.id || null;
        if (typeof s === 'string' && s.length) return s;
        if (b.seriesObjectId) return b.seriesObjectId;
        return null;
    }
    function seriesNameOf(b) {
        let s = b && b.series;
        if (s && typeof s === 'object') return s.name || s.slug || null;
        return b && (b.seriesName || null);
    }
    let hasSeriesInfo = list.some(function (b) { return seriesKeyOf(b) != null; });
    if (!hasSeriesInfo) {
        return { hasSeriesInfo: false, groups: [{ seriesKey: null, seriesName: 'All books', chapters: list.slice() }] };
    }
    let byKey = {};
    let order = [];
    for (let b of list) {
        let k = seriesKeyOf(b) || '__standalone__';
        if (!byKey[k]) { byKey[k] = { seriesKey: k, seriesName: seriesNameOf(b) || (k === '__standalone__' ? 'Standalone' : k), chapters: [] }; order.push(k); }
        if (!byKey[k].seriesName && seriesNameOf(b)) byKey[k].seriesName = seriesNameOf(b);
        byKey[k].chapters.push(b);
    }
    for (let k of order) {
        byKey[k].chapters.sort(function (a, b) {
            let ca = a.chapter != null ? a.chapter : 9999;
            let cb = b.chapter != null ? b.chapter : 9999;
            if (ca !== cb) return ca - cb;
            return String(a.name || a.slug || '').localeCompare(String(b.name || b.slug || ''));
        });
    }
    return { hasSeriesInfo: true, groups: order.map(function (k) { return byKey[k]; }) };
}
