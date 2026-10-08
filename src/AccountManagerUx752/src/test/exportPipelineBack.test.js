/**
 * cardGame/designer/exportPipeline.js — card BACK rendering (was a skipped "TODO: Implement card back
 * rendering" branch, carried over from Ux7, until 2026-10-07).
 *
 * html2canvas and JSZip are not shipped by Ux752 (see the module header), so the capture itself cannot
 * run anywhere yet; this test drives the pipeline with a fake html2canvas/JSZip and a recording Mithril
 * `render` and checks what the pipeline now does that it did not before: the back vnode is chosen from
 * the game renderers (character → renderCharacterBackBody with the deck's back art, every other type →
 * CardBack of that type), is rendered offscreen at the print size, captured, and filed as
 * backs/<same file name as the front>, with exportState.total/completed counting both faces.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';

const { rendered, mockRedraw, mockCharBack, mockCardBack, mockLayoutFace, mockGetLayout } = vi.hoisted(() => ({
    rendered: [],
    mockRedraw: vi.fn(),
    mockCharBack: vi.fn((card, bg) => ({ tag: 'div', attrs: { class: 'cg2-card cg2-card-front cg2-char-back', 'data-bg': bg }, children: card.name })),
    mockCardBack: { view: vi.fn() },
    mockLayoutFace: { view: vi.fn() },
    mockGetLayout: vi.fn(() => ({ id: 'layout-stub' }))
}));

vi.mock('mithril', () => ({
    default: Object.assign(
        (tag, attrs, children) => ({ tag, attrs, children }),
        {
            redraw: mockRedraw,
            render: (container, vnode) => { rendered.push({ container, vnode }); },
            route: { set: vi.fn(), get: () => '/' },
            trust: (s) => s
        }
    )
}));

vi.mock('../cardGame/rendering/cardFace.js', () => ({
    renderCharacterBackBody: mockCharBack,
    CardBack: mockCardBack
}));

vi.mock('../cardGame/designer/layoutRenderer.js', () => ({
    layoutRenderer: { LayoutCardFace: mockLayoutFace }
}));

vi.mock('../cardGame/designer/layoutConfig.js', () => ({
    layoutConfig: { getLayout: mockGetLayout }
}));

// ── Minimal DOM: enough for captureVnode() (container create/append/remove, no <img>) and the download <a>.
function fakeElement(tag) {
    let el = {
        tagName: tag.toUpperCase(),
        style: { setProperty(k, v) { this[k] = v; } },
        children: [],
        appendChild(c) { this.children.push(c); return c; },
        removeChild(c) { this.children = this.children.filter((x) => x !== c); return c; },
        querySelectorAll() { return []; },
        click() { el.clicked = true; }
    };
    return el;
}

let exportPipeline;
let captures = [];
let zips = [];

beforeAll(async () => {
    globalThis.document = {
        createElement: (tag) => fakeElement(tag),
        body: fakeElement('body')
    };
    globalThis.window = globalThis.window || {};
    globalThis.URL = globalThis.URL || {};
    globalThis.URL.createObjectURL = () => 'blob:fake';
    globalThis.URL.revokeObjectURL = () => {};
    ({ exportPipeline } = await import('../cardGame/designer/exportPipeline.js'));
});

beforeEach(() => {
    vi.clearAllMocks();
    rendered.length = 0;
    captures = [];
    zips = [];
    globalThis.window.html2canvas = vi.fn(async (container, opts) => {
        let c = { w: opts.width, h: opts.height, container, toBlob(cb, mime) { cb({ size: 1, mime, w: opts.width, h: opts.height }); } };
        captures.push(c);
        return c;
    });
    globalThis.window.JSZip = class {
        constructor() { this.files = {}; zips.push(this); }
        file(path, content) { this.files[path] = content; }
        async generateAsync(opts, progress) { if (progress) progress({ percent: 100 }); return { size: 10 }; }
    };
});

const character = { name: 'Rhea', type: 'character', subtype: 'hero' };
const item = { name: 'Sword', type: 'item', subtype: 'weapon' };
const scenario = { name: 'Ambush', type: 'scenario' };

describe('backVnode — which renderer draws a card back', () => {
    const CF = { renderCharacterBackBody: mockCharBack, CardBack: mockCardBack };

    it('a character back is the stats face (renderCharacterBackBody) with the deck back art', () => {
        let v = exportPipeline.backVnode(CF, character, 'https://x/back.png');
        expect(mockCharBack).toHaveBeenCalledWith(character, 'https://x/back.png');
        expect(v.attrs.class).toContain('cg2-char-back');
    });

    it('a character back with no back art passes null, not undefined', () => {
        exportPipeline.backVnode(CF, character, undefined);
        expect(mockCharBack).toHaveBeenCalledWith(character, null);
    });

    it('every other type is the type-coloured CardBack component for that type', () => {
        let v1 = exportPipeline.backVnode(CF, item, null);
        let v2 = exportPipeline.backVnode(CF, scenario, null);
        expect(mockCharBack).not.toHaveBeenCalled();
        expect(v1.tag).toBe(mockCardBack);
        expect(v1.attrs).toEqual({ type: 'item' });
        expect(v2.attrs).toEqual({ type: 'scenario' });
    });
});

describe('renderCardBackToCanvas — offscreen render at the print size and capture', () => {
    it('renders the back vnode into an offscreen container sized to CARD_SIZES[sizeKey] and captures it', async () => {
        let canvas = await exportPipeline.renderCardBackToCanvas(item, { deckName: 'D' }, 'poker', null);
        expect(canvas.w).toBe(750);
        expect(canvas.h).toBe(1050);
        // First render call draws the card wrapper with the CardBack vnode inside; the final one clears (null).
        expect(rendered.length).toBe(2);
        let wrapper = rendered[0].vnode;
        expect(wrapper.attrs.style.width).toBe('750px');
        expect(wrapper.children.tag).toBe(mockCardBack);
        expect(wrapper.children.attrs).toEqual({ type: 'item' });
        expect(rendered[1].vnode).toBeNull();
        expect(rendered[0].container.style['--card-width']).toBe('750px');
        // The container is detached again after capture.
        expect(globalThis.document.body.children).not.toContain(rendered[0].container);
    });

    it('rejects an unknown size key before touching the DOM', async () => {
        await expect(exportPipeline.renderCardBackToCanvas(item, {}, 'nosuchsize', null)).rejects.toThrow(/Unknown card size/);
        expect(rendered.length).toBe(0);
    });
});

describe('exportDeck with includeBack', () => {
    const deck = {
        deckName: 'KI8 Deck',
        cardBackImageUrl: 'https://x/deck-back.png',
        // duplicate Sword collapses to one unique card
        cards: [character, item, { name: 'Sword', type: 'item', subtype: 'weapon' }, scenario]
    };

    it('files a backs/<name> entry next to every fronts/<name> entry and counts both faces', async () => {
        let result = await exportPipeline.exportDeck(deck, { sizeKey: 'poker', format: 'png', includeBack: true });
        expect(result).toEqual({ count: 3, size: 10 });
        let files = Object.keys(zips[0].files).sort();
        expect(files).toEqual([
            'backs/001-Rhea-character.png',
            'backs/002-Sword-item.png',
            'backs/003-Ambush-scenario.png',
            'fronts/001-Rhea-character.png',
            'fronts/002-Sword-item.png',
            'fronts/003-Ambush-scenario.png',
            'info.txt'
        ]);
        // 3 fronts + 3 backs captured, each at the poker print size.
        expect(captures.length).toBe(6);
        captures.forEach((c) => { expect([c.w, c.h]).toEqual([750, 1050]); });
        // The character back used the deck's back art (options.bgImageBack falls back to deck.cardBackImageUrl).
        expect(mockCharBack).toHaveBeenCalledTimes(1);
        expect(mockCharBack).toHaveBeenCalledWith(character, 'https://x/deck-back.png');
        // Fronts still go through the designer layout renderer, once per unique card.
        expect(mockGetLayout).toHaveBeenCalledTimes(3);
        // Progress was reported for both faces: total was 3*2 while active, then reset on success.
        expect(exportPipeline.exportState.active).toBe(false);
        expect(exportPipeline.exportState.total).toBe(0);
        expect(exportPipeline.exportState.completed).toBe(0);
    });

    it('options.bgImageBack overrides the deck back art', async () => {
        await exportPipeline.exportDeck({ deckName: 'X', cards: [character], cardBackImageUrl: 'https://x/deck.png' },
            { includeBack: true, bgImageBack: 'https://x/override.png' });
        expect(mockCharBack).toHaveBeenCalledWith(character, 'https://x/override.png');
    });

    it('without includeBack no back is rendered and no backs/ entries exist (unchanged behaviour)', async () => {
        let result = await exportPipeline.exportDeck(deck, { sizeKey: 'poker' });
        expect(result.count).toBe(3);
        let files = Object.keys(zips[0].files);
        expect(files.filter((f) => f.startsWith('backs/'))).toEqual([]);
        expect(files.filter((f) => f.startsWith('fronts/')).length).toBe(3);
        expect(captures.length).toBe(3);
        expect(mockCharBack).not.toHaveBeenCalled();
    });

    it('exportState.total counts both faces while the export is active', async () => {
        let seenTotal = null;
        mockRedraw.mockImplementation(() => { if (exportPipeline.exportState.active && seenTotal == null) seenTotal = exportPipeline.exportState.total; });
        await exportPipeline.exportDeck(deck, { includeBack: true });
        expect(seenTotal).toBe(6);
    });

    it('still refuses to run when html2canvas / JSZip are absent (the Ux752 production state)', async () => {
        delete globalThis.window.html2canvas;
        delete globalThis.window.JSZip;
        await expect(exportPipeline.exportDeck(deck, { includeBack: true })).rejects.toThrow(/Missing libraries: html2canvas, JSZip/);
        expect(exportPipeline.checkLibraries()).toEqual(['html2canvas', 'JSZip']);
    });
});
