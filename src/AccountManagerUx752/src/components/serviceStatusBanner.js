/**
 * User-facing degraded-services banner (aiDocs/ServiceStatusDesign.md §8, D6).
 *
 * Started from router.js's logged-in branch next to the am7client.application() await. It fetches
 * GET /rest/system/check/summary ({status, degraded:[names], checkedAt}; open to role user, names only)
 * and, when anything is degraded, shows a dismissible top banner naming the degraded services.
 *
 * Rules (design §8):
 *   - The fetch fails SILENTLY on any error (401, 403, 5xx, network) and never goes through forceLogin —
 *     it uses m.request directly via serviceStatusClient, not am7client (whose 401 handler logs out).
 *   - Refetch on window focus at most once per 60 s; never on route changes.
 *   - The wording names no feature route, so it stays correct in organizations without `serviceStatus`.
 *   - Core, not gated by the `serviceStatus` feature id: plain users need it and cannot open the app.
 *
 * The decision logic is pure and exported for tests; createServiceStatusBanner takes its fetcher, clock
 * and window as arguments so a test can drive it without a DOM or a server.
 */
import m from 'mithril';
import { serviceStatusClient } from '../features/serviceStatus/serviceStatusClient.js';

export const REFETCH_MIN_MS = 60000;
export const BANNER_ADVICE = 'Ask an administrator to open Service Status.';

// ── Pure decision logic ─────────────────────────────────────────────

export function degradedNames(summary) {
    if (!summary || !Array.isArray(summary.degraded)) return [];
    return summary.degraded.filter(n => n !== null && n !== undefined && n !== '').map(String);
}

/** True when the summary reports anything other than an all-clear. Status compared case-insensitively. */
export function isDegraded(summary) {
    if (!summary || typeof summary !== 'object') return false;
    if (degradedNames(summary).length) return true;
    let s = (summary.status === null || summary.status === undefined) ? '' : String(summary.status).toLowerCase();
    return s !== '' && s !== 'ok';
}

/** A stable key for "this particular degraded state" — dismissing hides it until the state changes. */
export function summarySignature(summary) {
    if (!summary) return '';
    let s = (summary.status === null || summary.status === undefined) ? '' : String(summary.status).toLowerCase();
    return s + '|' + degradedNames(summary).slice().sort().join(',');
}

export function shouldShowBanner(summary, dismissedSignature) {
    if (!isDegraded(summary)) return false;
    return summarySignature(summary) !== dismissedSignature;
}

export function bannerText(summary) {
    let names = degradedNames(summary);
    let lead = names.length
        ? 'Some services are degraded: ' + names.join(', ') + '.'
        : 'Some services are degraded.';
    return lead + ' ' + BANNER_ADVICE;
}

export function shouldRefetch(now, lastFetchAt, minMs) {
    let gap = (minMs === undefined) ? REFETCH_MIN_MS : minMs;
    if (!lastFetchAt) return true;
    return (now - lastFetchAt) >= gap;
}

// ── Controller ──────────────────────────────────────────────────────

/**
 * opts: { fetchSummary: () => Promise<summary>, now: () => ms, win: window-like (addEventListener /
 *         removeEventListener), redraw: () => void, minMs }
 */
export function createServiceStatusBanner(opts) {
    let o = opts || {};
    let fetchSummary = o.fetchSummary || (() => serviceStatusClient.summary());
    let now = o.now || (() => Date.now());
    let win = (o.win !== undefined) ? o.win : (typeof window !== 'undefined' ? window : null);
    let redraw = o.redraw || (() => { try { m.redraw(); } catch (e) { /* no mounted root */ } });
    let minMs = (o.minMs === undefined) ? REFETCH_MIN_MS : o.minMs;

    let st = { active: false, summary: null, dismissed: '', lastFetchAt: 0, inflight: null };

    function refresh() {
        if (!st.active) return Promise.resolve(null);
        if (st.inflight) return st.inflight;
        st.lastFetchAt = now();
        let p;
        try {
            p = Promise.resolve(fetchSummary());
        } catch (e) {
            p = Promise.reject(e);
        }
        st.inflight = p.then(function (s) {
            if (st.active) {
                st.summary = (s && typeof s === 'object') ? s : null;
                redraw();
            }
            return st.summary;
        }, function () {
            // Fail silently (design §8): no toast, no forceLogin, keep whatever was shown before.
            return null;
        }).then(function (v) { st.inflight = null; return v; });
        return st.inflight;
    }

    function onFocus() {
        if (!st.active) return null;
        if (!shouldRefetch(now(), st.lastFetchAt, minMs)) return null;
        return refresh();
    }

    function start() {
        if (!st.active) {
            st.active = true;
            if (win && win.addEventListener) win.addEventListener('focus', onFocus);
        }
        return refresh();
    }

    function stop() {
        if (st.active && win && win.removeEventListener) win.removeEventListener('focus', onFocus);
        st.active = false;
        st.summary = null;
        st.dismissed = '';
        st.lastFetchAt = 0;
        st.inflight = null;
    }

    function dismiss() {
        st.dismissed = summarySignature(st.summary);
        redraw();
    }

    function isVisible() {
        return st.active && shouldShowBanner(st.summary, st.dismissed);
    }

    const component = {
        view: function () {
            if (!isVisible()) return null;
            return m('div', {
                id: 'serviceStatusBanner',
                role: 'status',
                class: 'fixed top-1 left-2 right-2 mx-auto z-50 max-w-3xl flex items-start gap-2 px-3 py-2 rounded shadow '
                    + 'bg-amber-100 dark:bg-amber-900 text-amber-900 dark:text-amber-100 border border-amber-300 dark:border-amber-700 text-sm'
            }, [
                m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'warning'),
                m('span', { class: 'flex-1', 'data-testid': 'svc-banner-text' }, bannerText(st.summary)),
                m('button', {
                    id: 'serviceStatusBannerDismiss',
                    'aria-label': 'Dismiss',
                    class: 'material-symbols-outlined hover:opacity-70',
                    style: 'font-size:18px',
                    onclick: dismiss
                }, 'close')
            ]);
        }
    };

    return { start, stop, refresh, onFocus, dismiss, isVisible, component, _state: () => st };
}

/** The application's single banner instance (router.js starts/stops it; OverlayGuard renders it). */
export const serviceStatusBanner = createServiceStatusBanner();

export default serviceStatusBanner;
