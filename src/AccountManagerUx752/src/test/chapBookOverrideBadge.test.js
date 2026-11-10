// @vitest-environment jsdom
/**
 * ChapBook page "Image config overrides" status (2026-11-10).
 *
 * Why: the old "set" pill sat inside the expand toggle and lit up for saved AND unsaved overrides, so it read like
 * a button you had to click. It is now two plain statuses - "overridden" (the page has SAVED overrides) and
 * "unsaved" (edits in the panel not yet committed by the card's Save) - and the collapsed row summarises the
 * saved values. No network: the review is seeded through __setReviewStateForTest and rendered as a vnode tree
 * (the chapBookReviewMasterDetail harness).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { page } from '../core/pageClient.js';

const BOOK = 'cb-ov-book';
const SAVED = '{"refinerModel":"lustifySDXLNSFW_endgame.safetensors","steps":40,"refinerSteps":60,"refinerCfg":7,"cfg":5,"schema":"olio.sd.config"}';

function scene(extra) {
    return Object.assign({
        objectId: 'ov1', id: 11, name: 'Scene 1', sceneIndex: 0, title: 'Title', poemStanza: 'Stanza',
        pageFont: '', pageBgColor: '', pageBgOpacity: null, pageTextAlign: '', pageTextColor: '',
        configOverride: null, sdPrompt: 'a quiet harbour at dusk', imageObjectId: null, imageStale: false,
        sceneNodeObjectId: null, pinned: false, mood: '', location: null, locationVariant: null, promptLocked: true,
        _dirty: new Set(), _saving: false
    }, extra || {});
}

function findAll(vnode, pred, out) {
    out = out || [];
    if (vnode == null || vnode === false) return out;
    if (Array.isArray(vnode)) { vnode.forEach(v => findAll(v, pred, out)); return out; }
    if (typeof vnode !== 'object') return out;
    if (pred(vnode)) out.push(vnode);
    findAll(vnode.children, pred, out);
    return out;
}
function textOf(vnode) {
    if (vnode == null || vnode === false) return '';
    if (typeof vnode === 'string' || typeof vnode === 'number') return String(vnode);
    if (Array.isArray(vnode)) return vnode.map(textOf).join('');
    if (vnode.text != null) return String(vnode.text);
    return textOf(vnode.children);
}
function byTestId(view, id) { return findAll(view, v => v.attrs && v.attrs['data-testid'] === id); }

let cb;

function render(s) {
    cb.__setReviewStateForTest({
        reviewBookObjectId: BOOK, reviewBook: { objectId: BOOK, name: 'Tidewater', slug: 'tidewater', fixPageHeight: false },
        reviewScenes: [s], reviewSelectedOid: s.objectId, reviewSceneImageUrls: {}, reviewCovers: null, reviewExporting: false, roleWarning: false
    });
    return cb.ChapBookReview.view();
}

beforeEach(async () => {
    cb = await import('../features/chapBook.js');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
    vi.spyOn(m, 'redraw').mockImplementation(() => {});
});

afterEach(() => {
    cb.__setReviewStateForTest({ reviewBookObjectId: null, reviewBook: null, reviewScenes: [], reviewSelectedOid: null, reviewSceneImageUrls: {}, reviewCovers: null, reviewExporting: false });
    vi.restoreAllMocks();
});

describe('overrideSummary', () => {
    it('lists the saved settings with readable labels, model names shortened, carriers skipped', () => {
        expect(cb.overrideSummary(SAVED)).toBe('refiner lustifySDXLNSFW_endgame · steps 40 · refiner steps 60 · refiner cfg 7 · cfg 5');
    });
    it('caps the list and says how many more', () => {
        expect(cb.overrideSummary(SAVED, 2)).toBe('refiner lustifySDXLNSFW_endgame · steps 40 · +3 more');
    });
    it('is empty for no / blank / unparseable / carrier-only overrides', () => {
        expect(cb.overrideSummary(null)).toBe('');
        expect(cb.overrideSummary('  ')).toBe('');
        expect(cb.overrideSummary('not json')).toBe('');
        expect(cb.overrideSummary('{"schema":"olio.sd.config"}')).toBe('');
    });
    it('shortens a path-qualified model and long values', () => {
        expect(cb.overrideSummary('{"model":"OfficialStableDiffusion/sd_xl_base_1.0.safetensors","schema":"olio.sd.config"}')).toBe('model sd_xl_base_1.0');
    });
});

describe('Image config overrides status', () => {
    it('no override: neither status, no summary, and the old "set" pill is gone', () => {
        let v = render(scene());
        expect(byTestId(v, 'cb-override-saved').length).toBe(0);
        expect(byTestId(v, 'cb-override-unsaved').length).toBe(0);
        expect(byTestId(v, 'cb-override-summary').length).toBe(0);
        expect(findAll(v, x => x.tag === 'span' && textOf(x) === 'set').length).toBe(0);
    });

    it('a saved override shows "overridden" and the summary of what it sets', () => {
        let v = render(scene({ configOverride: SAVED }));
        expect(textOf(byTestId(v, 'cb-override-saved')[0])).toBe('overridden');
        expect(byTestId(v, 'cb-override-unsaved').length).toBe(0);
        expect(textOf(byTestId(v, 'cb-override-summary')[0])).toBe('refiner lustifySDXLNSFW_endgame · steps 40 · refiner steps 60 · refiner cfg 7 · cfg 5');
    });

    it('pending override edits show "unsaved" (with or without a saved override)', () => {
        let fresh = render(scene({ _dirty: new Set(['override']) }));
        expect(textOf(byTestId(fresh, 'cb-override-unsaved')[0])).toBe('unsaved');
        expect(byTestId(fresh, 'cb-override-saved').length).toBe(0);
        let both = render(scene({ configOverride: SAVED, _dirty: new Set(['override']) }));
        expect(byTestId(both, 'cb-override-saved').length).toBe(1);
        expect(byTestId(both, 'cb-override-unsaved').length).toBe(1);
    });
});
