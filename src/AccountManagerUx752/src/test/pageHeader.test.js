/**
 * @vitest-environment jsdom
 *
 * components/pageHeader.js — the shared back · title · meta … actions · refresh header now used by
 * PictureBook Review / Style / Locations / Compose / Workflow and ChapBook Review. Rendered with the
 * real Mithril renderer; also renders the real Review / Locations headers to prove they use it.
 */
import { describe, it, expect } from 'vitest';
import m from 'mithril';
import { pageHeader, chip, metaText } from '../components/pageHeader.js';

function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div.firstChild;
}

describe('pageHeader', () => {
    it('renders back, title, meta, actions and refresh in order and wires the handlers', () => {
        let backs = 0, refreshes = 0;
        let el = render(pageHeader({
            testid: 'hdr',
            back: { testid: 'hdr-back', title: 'Back to book', onclick: function () { backs++; } },
            title: 'Locations', titleTestid: 'hdr-title', titleAttrs: { 'data-wf-title': true },
            meta: [metaText('3 locations', { 'data-testid': 'hdr-count' }), null, chip('Locked', 'amber', { 'data-testid': 'hdr-lock' })],
            actions: [m('button', { 'data-testid': 'hdr-act' }, 'Act')],
            refresh: { testid: 'hdr-refresh', disabled: false, onclick: function () { refreshes++; } }
        }));
        expect(el.getAttribute('data-testid')).toBe('hdr');
        expect(el.getAttribute('data-page-header')).toBe('1');
        let ids = Array.from(el.querySelectorAll('[data-testid]')).map(function (n) { return n.getAttribute('data-testid'); });
        expect(ids).toEqual(['hdr-back', 'hdr-title', 'hdr-count', 'hdr-lock', 'hdr-act', 'hdr-refresh']);
        let back = el.querySelector('[data-testid="hdr-back"]');
        expect(back.getAttribute('title')).toBe('Back to book');
        expect(back.getAttribute('aria-label')).toBe('Back to book');
        back.click();
        el.querySelector('[data-testid="hdr-refresh"]').click();
        expect(backs).toBe(1);
        expect(refreshes).toBe(1);
        expect(el.querySelector('h2').textContent).toBe('Locations');
        expect(el.querySelector('h2').hasAttribute('data-wf-title')).toBe(true);
        expect(el.querySelector('[data-testid="hdr-lock"]').className).toContain('bg-amber-100');
    });

    it('omits back / refresh when not given; disabled refresh is disabled; bar variant has no bottom margin', () => {
        let el = render(pageHeader({ title: 'T', refresh: { testid: 'r', disabled: true, onclick: function () {} }, bar: true }));
        expect(el.querySelector('[title="Back"]')).toBeNull();
        expect(el.querySelectorAll('button').length).toBe(1);
        expect(el.querySelector('[data-testid="r"]').disabled).toBe(true);
        expect(el.className).toContain('border-b');
        expect(el.className).not.toContain('mb-4');
        let plain = render(pageHeader({ title: 'T' }));
        expect(plain.querySelectorAll('button').length).toBe(0);
        expect(plain.className).toContain('mb-4');
    });
});

describe('feature headers use pageHeader', () => {
    it('Review and Locations headers render as a pageHeader with their testids intact', async () => {
        const review = await import('../features/pictureBookReview.js');
        const loc = await import('../features/pictureBookLocations.js');
        let r = render(review.renderReviewHeader());
        expect(r.getAttribute('data-page-header')).toBe('1');
        expect(r.querySelector('[data-testid="pb2-review-back"]')).not.toBeNull();
        expect(r.querySelector('[data-testid="pb2-review-refresh"]')).not.toBeNull();
        expect(r.querySelector('h2').textContent).toBe('Review queue');
        let l = render(loc.renderLocationsHeader());
        expect(l.getAttribute('data-page-header')).toBe('1');
        expect(l.querySelector('[data-testid="pb-loc-back"]')).not.toBeNull();
        expect(l.querySelector('[data-testid="pb-loc-new"]')).not.toBeNull();
        expect(l.querySelector('[data-testid="pb-loc-refresh"]')).not.toBeNull();
    });
});
