// @vitest-environment jsdom
/**
 * PictureBook V3 style presets + style-control cleanup (2026-11-05).
 *
 * Why: the V3 plan path compiles a book's look ONLY from the book style's description / photoreal /
 * exclusions (SdxlCompiler / Flux2Compiler); the SD config's own `style` select is never applied there
 * (PbNodeExecutor NOT_APPLIED_STYLE_CLAUSE). A blank description therefore left every image to pick its own
 * look while the visible Style dropdowns did nothing. These tests pin: the presets fill exactly those three
 * fields as unsaved drafts with no "null"/SD-weighting debris, the no-description warnings, and that
 * SdConfigPanel's hideFields:['style'] removes the style select and all its detail fields.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { am7sd } from '../components/sdConfig.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';
import { normalizeStyle } from '../workflows/pictureBookWorkflow.js';
import {
    STYLE_PRESETS, PRESET_EXCLUSIONS_PHOTO, PRESET_EXCLUSIONS_ART,
    presetDescription, presetFields, applyPreset, missingDescription, buildStylePut,
    renderStyleBody, __styleStateForTest, __setStyleStateForTest
} from '../features/pictureBookStyle.js';
import { styleHasDescription } from '../features/pictureBook.js';

function findByTestId(vnode, testid) {
    let found = null;
    (function walk(v) {
        if (!v || found) return;
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.attrs && v.attrs['data-testid'] === testid) { found = v; return; }
        walk(v.children);
    })(vnode);
    return found;
}

function allText(vnode) {
    let out = [];
    (function walk(v) {
        if (v == null || v === false) return;
        if (typeof v === 'string' || typeof v === 'number') { out.push(String(v)); return; }
        if (Array.isArray(v)) { v.forEach(walk); return; }
        if (v.text != null) out.push(String(v.text));
        walk(v.children);
    })(vnode);
    return out.join(' | ');
}

const FRESH = { bookObjectId: 'B1', effective: { qualityPreset: 'STANDARD', pageFormat: 'SQUARE' }, cycleDetected: false, complete: true };
const DESCRIBED = { bookObjectId: 'B1', own: { objectId: 'S1', name: 's', scope: 'BOOK', locked: false, fields: { description: 'ink wash' } },
    effective: { fields: { description: 'ink wash' }, sources: { description: 'S1' }, qualityPreset: 'STANDARD', pageFormat: 'SQUARE' },
    chain: [{ objectId: 'S1', name: 's', scope: 'BOOK' }], cycleDetected: false, complete: true };

describe('style presets', () => {
    it('every preset yields a clean description from the real fillStyleDefaults vocabulary', () => {
        STYLE_PRESETS.forEach(function (p) {
            let cfg = { style: p.value };
            am7sd.fillStyleDefaults(cfg);
            let f = presetFields(cfg);
            expect(f, p.value).not.toBeNull();
            expect(f.description.length, p.value).toBeGreaterThan(8);
            expect(f.description, p.value).not.toMatch(/null|undefined|\(|\)/);
        });
    });

    it('photographic presets are photoreal with art exclusions; art presets the reverse', () => {
        let photo = presetFields({ style: 'photograph', stillCamera: 'Leica M6', lens: '35mm Summicron', film: 'Kodak Portra 400', colorProcess: 'C-41', photographer: 'Annie Leibovitz' });
        expect(photo).toEqual({
            description: 'Photograph taken with a Leica M6 camera and 35mm Summicron lens on Kodak Portra 400 film processed with C-41 by Annie Leibovitz',
            photoreal: true, exclusions: PRESET_EXCLUSIONS_PHOTO
        });
        let anime = presetFields({ style: 'anime', animeStudio: 'Studio Ghibli', animeEra: '1990s' });
        expect(anime).toEqual({ description: 'Anime illustration in the style of Studio Ghibli with 1990s aesthetics', photoreal: false, exclusions: PRESET_EXCLUSIONS_ART });
        expect(presetFields({ style: 'comic' }).photoreal).toBe(false);
        expect(presetFields({ style: 'vintage' }).photoreal).toBe(true);
    });

    it('drops missing or literal-"null" details rather than printing them, and rejects unknown styles', () => {
        expect(presetDescription({ style: 'photograph', stillCamera: 'null', lens: '' })).toBe('Photograph');
        expect(presetDescription({ style: 'custom' })).toBeNull();
        expect(presetFields({ style: 'nope' })).toBeNull();
    });

    it('applyPreset writes description/photoreal/exclusions as draft edits only, ready for the sparse PUT', () => {
        __setStyleStateForTest({ bookObjectId: 'B1', style: normalizeStyle(FRESH), draft: {} });
        let fill = function (c) { c.animeStudio = 'Madhouse'; c.animeEra = '2000s'; };
        let f = applyPreset('anime', fill);
        expect(f.description).toBe('Anime illustration in the style of Madhouse with 2000s aesthetics');
        let d = __styleStateForTest().draft;
        expect(Object.keys(d).sort()).toEqual(['description', 'exclusions', 'photoreal']);
        expect(buildStylePut(d)).toEqual({ description: f.description, photoreal: false, exclusions: PRESET_EXCLUSIONS_ART });
    });
});

// 2026-11-16 (Stephen): olio.pb.style stores no preset, so a pick that "reset to Choose a style" after F5 read as a
// lost save. The preset is a one-shot fill: the select always shows its placeholder, and picking again re-rolls.
describe('preset is a one-shot fill, not a stored setting', () => {
    beforeEach(() => { __setStyleStateForTest({ bookObjectId: 'B1', style: normalizeStyle(FRESH), draft: {} }); });

    it('the select always shows its placeholder; there is no separate Re-roll and no preset is claimed', () => {
        let v = renderStyleBody();
        let sel = findByTestId(v, 'pb2-style-preset');
        expect(sel.attrs.value).toBe('');
        expect(allText(sel)).toContain('Fill from a preset…');
        expect(allText(sel)).not.toContain('Choose a style');
        expect(findByTestId(v, 'pb2-style-preset-reroll')).toBeNull();
        expect(allText(v)).toContain('The preset itself is not saved.');
    });

    it('a pick fills the draft and returns the select to its placeholder; picking again re-rolls', () => {
        let sel = findByTestId(renderStyleBody(), 'pb2-style-preset');
        let target = { value: 'anime' };
        sel.attrs.onchange({ target });
        expect(target.value).toBe('');
        let first = __styleStateForTest().draft.description;
        expect(first).toMatch(/^Anime illustration/);
        expect(findByTestId(renderStyleBody(), 'pb2-style-preset').attrs.value).toBe('');
        let draws = new Set([first]);
        for (let i = 0; i < 12 && draws.size < 2; i++) {
            let t = { value: 'anime' };
            findByTestId(renderStyleBody(), 'pb2-style-preset').attrs.onchange({ target: t });
            expect(t.value).toBe('');
            let d = __styleStateForTest().draft.description;
            expect(d).toMatch(/^Anime illustration/);
            draws.add(d);
        }
        expect(draws.size, 're-picking the same preset draws new details').toBeGreaterThan(1);
    });

    it('the placeholder itself changes nothing', () => {
        let t = { value: '' };
        findByTestId(renderStyleBody(), 'pb2-style-preset').attrs.onchange({ target: t });
        expect(__styleStateForTest().draft).toEqual({});
    });
});

describe('no-description warnings', () => {
    beforeEach(() => { __setStyleStateForTest({ bookObjectId: 'B1', style: null, draft: {} }); });

    it('the style page warns when the effective style has no description, and offers the preset', () => {
        __setStyleStateForTest({ bookObjectId: 'B1', style: normalizeStyle(FRESH), draft: {} });
        let v = renderStyleBody();
        expect(findByTestId(v, 'pb2-style-no-description')).not.toBeNull();
        expect(findByTestId(v, 'pb2-style-preset')).not.toBeNull();
        expect(missingDescription(normalizeStyle(FRESH), {})).toBe(true);
    });

    it('no warning once a description exists, or once a preset fills the draft', () => {
        __setStyleStateForTest({ bookObjectId: 'B1', style: normalizeStyle(DESCRIBED), draft: {} });
        expect(findByTestId(renderStyleBody(), 'pb2-style-no-description')).toBeNull();
        __setStyleStateForTest({ bookObjectId: 'B1', style: normalizeStyle(FRESH), draft: { description: 'Comic book panel' } });
        expect(findByTestId(renderStyleBody(), 'pb2-style-no-description')).toBeNull();
    });

    it('styleHasDescription (render dialog) reads the EFFECTIVE description', () => {
        expect(styleHasDescription(normalizeStyle(FRESH))).toBe(false);
        expect(styleHasDescription(normalizeStyle(DESCRIBED))).toBe(true);
        expect(styleHasDescription(normalizeStyle({ effective: { fields: { description: '   ' } } }))).toBe(false);
        expect(styleHasDescription(null)).toBe(false);
    });
});

describe('SdConfigPanel hideFields style', () => {
    const cfg = () => ({ style: 'photograph', stillCamera: 'Leica M6', lens: '50mm', film: 'Portra', colorProcess: 'C-41', photographer: 'X', denoisingStrength: 0.7, steps: 20 });

    it('shows the style select and photograph details by default', () => {
        let t = allText(SdConfigPanel.view({ attrs: { config: cfg() } }));
        expect(t).toContain('Style');
        expect(t).toContain('Still Camera');
        expect(t).toContain('Photographer');
    });

    it("hideFields:['style'] removes the style select and every per-style field but keeps the rest", () => {
        let t = allText(SdConfigPanel.view({ attrs: { config: cfg(), hideFields: ['style'] } }));
        expect(t).not.toMatch(/(^|\| )Style( \||$)/);
        expect(t).not.toContain('Still Camera');
        expect(t).not.toContain('Photographer');
        expect(t).toContain('Denoising');
        expect(t).toContain('Model');
    });

    it("hideFields:['style'] also hides the custom style prompt", () => {
        let c = cfg(); c.style = 'custom'; c.customPrompt = 'zz';
        expect(allText(SdConfigPanel.view({ attrs: { config: c } }))).toContain('Custom Style Prompt');
        expect(allText(SdConfigPanel.view({ attrs: { config: c, hideFields: ['style'] } }))).not.toContain('Custom Style Prompt');
    });
});
