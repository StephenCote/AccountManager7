// @vitest-environment jsdom
//
// Toast region (core/pageClient.js addToast / loadToast) — P10-D 2, §6.C 267 i.
//
// WHY: §16 gate shots `04` (five green toasts) and `12` (three) showed the top-right toast column piling up
// over the reader's own toolbar; identical messages ("Render failed" ×2, two red 409s in `08b`) stacked as
// separate boxes. The region now collapses an identical live toast into the existing one (counter + timer
// restart) and holds at most three, evicting the oldest expiring non-error toast; errors and sticky
// (expiry < 0) progress toasts are never evicted. No network is touched; timers are faked. Every toast expires
// after `timeout || 5000` ms regardless of type (toastConfig's per-type timeouts are declared but, as in the legacy
// Ux7 pageClient, never read) — the tests use the real 5 s, not the declared 15 s.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

let page;
beforeEach(async () => {
    vi.useFakeTimers();
    ({ page } = await import('../core/pageClient.js'));
    page.clearToast();
});
afterEach(() => { page.clearToast(); vi.useRealTimers(); });

function live() { return page.toasts().filter(t => !t.burning); }
// loadToast() renders m('div.toast-container', [items]); Mithril normalizes the nested array into a fragment vnode.
function boxVnodes() {
    let v = page.loadToast();
    if (!v) return [];
    let first = v.children[0];
    return Array.isArray(first) ? first : (first && first.tag === '[' ? first.children : v.children);
}
function vtext(v) { return Array.isArray(v.children) ? v.children.map(c => (c && c.tag === '#') ? c.children : vtext(c)).join('') : String(v.children); }
function boxes() {
    return boxVnodes().map(b => ({ type: b.attrs['data-toast-type'], count: b.attrs['data-toast-count'], text: vtext(b.children[1]), badge: b.children[2] ? vtext(b.children[2]) : null }));
}

describe('toast region', () => {
    it('distinct messages each get a box, newest last, each with count 1 and no badge', () => {
        page.toast('success', 'Style saved');
        page.toast('success', 'Render settings created');
        expect(boxes().map(b => [b.type, b.text, b.count, b.badge])).toEqual([['success', 'Style saved', '1', null], ['success', 'Render settings created', '1', null]]);
    });
    it('an identical live toast is collapsed into the existing one: same id, counter ×N, timer restarted', () => {
        let id1 = page.toast('error', 'Render failed');
        vi.advanceTimersByTime(4000);                    // 1 s short of the 5 s expiry
        let id2 = page.toast('error', 'Render failed');
        expect(id2).toBe(id1);
        expect(live().length).toBe(1);
        expect(boxes()[0].count).toBe('2');
        expect(boxes()[0].badge).toBe('×2');
        vi.advanceTimersByTime(2000);                    // t=6 s: would have expired without the restart
        expect(live().length).toBe(1);
        page.toast('error', 'Render failed');
        expect(boxes()[0].badge).toBe('×3');
        vi.advanceTimersByTime(5000);
        expect(live().length).toBe(0);
    });
    it('the same message with a different type is a different toast', () => {
        page.toast('info', 'Approved');
        page.toast('success', 'Approved');
        expect(live().length).toBe(2);
    });
    it('the region holds at most three: a fourth distinct toast evicts the oldest expiring non-error one', () => {
        page.toast('success', 'Style saved');
        page.toast('success', 'Render settings created');
        page.toast('success', 'Render settings saved');
        page.toast('success', 'Style locked');
        expect(live().map(t => t.message)).toEqual(['Render settings created', 'Render settings saved', 'Style locked']);
        page.toast('success', 'Style unlocked');
        expect(live().map(t => t.message)).toEqual(['Render settings saved', 'Style locked', 'Style unlocked']);
        vi.advanceTimersByTime(300);                     // the evicted ones finish fading and leave the list
        expect(page.toasts().map(t => t.message)).toEqual(['Render settings saved', 'Style locked', 'Style unlocked']);
    });
    it('errors and sticky progress toasts are never evicted; the region may exceed three for them', () => {
        page.toast('error', 'Could not list models');
        page.toast('info', 'Rendering…', -1);
        page.toast('success', 'Composition saved');
        page.toast('success', 'Approved');                // evicts 'Composition saved', the only expiring non-error
        expect(live().map(t => t.message)).toEqual(['Could not list models', 'Rendering…', 'Approved']);
        page.toast('error', 'Render failed');            // an arriving error still evicts 'Approved' to make room
        expect(live().map(t => t.message)).toEqual(['Could not list models', 'Rendering…', 'Render failed']);
        page.toast('success', 'Revision 6 is now current');  // no expiring non-error toast left to evict → the region grows
        expect(live().map(t => t.message)).toEqual(['Could not list models', 'Rendering…', 'Render failed', 'Revision 6 is now current']);
        page.toast('warn', 'Degraded');                  // evicts 'Revision 6 is now current'
        page.toast('warn', 'Degraded 2');                // evicts 'Degraded'
        expect(live().map(t => t.message)).toEqual(['Could not list models', 'Rendering…', 'Render failed', 'Degraded 2']);
        expect(live().filter(t => t.type === 'error').length).toBe(2);
        expect(live().find(t => t.expiry < 0).message).toBe('Rendering…');
        vi.advanceTimersByTime(20000);
        expect(live().map(t => t.message)).toEqual(['Rendering…']);     // sticky survives every timer
        page.clearToast();
        expect(page.toasts()).toEqual([]);
    });
    it('dismissing a toast clears its timer so a later duplicate starts fresh', () => {
        page.toast('success', 'Approved');
        boxVnodes()[0].children[3].attrs.onclick();     // the close button
        vi.advanceTimersByTime(300);
        expect(page.toasts()).toEqual([]);
        page.toast('success', 'Approved');
        expect(boxes()[0].count).toBe('1');
    });
});
