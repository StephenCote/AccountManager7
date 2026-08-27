/**
 * Issue 5 — SdConfigPanel fresh-open defaults.
 *
 * A freshly opened SD config panel (empty config, e.g. a brand-new scene) must show the real
 * defaults, NOT 0/empty: Denoising 0.75 and Steps 20. This renders the ACTUAL component view
 * (SdConfigPanel.view) against an empty config object and asserts on the real Mithril vnode tree it
 * returns — the same slider+spinner widgets the panel draws — plus the live-value labels.
 *
 * Browser note: opening the panel in a real page mounts it inside SceneGenerator/reimage/pictureBook,
 * all of which fire an SD/config-server network call (192.168.1.39) on open. That server was off-limits
 * while the concurrent 6C SD-render Playwright test was running, so Issue 5 is proven here at the
 * component level (zero network) rather than through a browser open. This exercises the exact fixed
 * code in SdConfigPanel.js:224-225 (denoise) and :279-280 (steps).
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';

beforeAll(() => {
    // Same minimal stubs the canonical rangeSliderConverge test uses — the view only touches am7model
    // inside change handlers, not during render, but keep the shape valid.
    am7model._view = { path: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
    am7model._sd = { fillStyleDefaults: () => {} };
});

function findByTag(vnode, tag) {
    let out = [];
    (function walk(n) {
        if (Array.isArray(n)) { n.forEach(walk); return; }
        if (!n) return;
        if (n.tag === tag) out.push(n);
        if (n.children) walk(n.children);
    })(vnode);
    return out;
}

// Collect every text fragment in the tree so we can assert on the live-value labels.
function textOf(vnode) {
    let parts = [];
    (function walk(n) {
        if (Array.isArray(n)) { n.forEach(walk); return; }
        if (n == null || n === false) return;
        if (typeof n === 'string' || typeof n === 'number') { parts.push(String(n)); return; }
        if (n.children != null) walk(n.children);
    })(vnode);
    return parts;
}

describe('SdConfigPanel fresh-open defaults (Issue 5)', () => {
    it('renders Denoising default 0.75 on the real range widget for an empty config', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let tree = SdConfigPanel.view({ attrs: { config: {} } });

        let ranges = findByTag(tree, 'input').filter(i => i.attrs.type === 'range');
        // Denoise is the unique range with bounds min=0, max=1, step=0.05.
        let denoise = ranges.find(r => r.attrs.min === 0 && r.attrs.max === 1 && r.attrs.step === 0.05);
        expect(denoise, 'denoise range widget present').toBeTruthy();
        expect(denoise.attrs.value).toBe(0.75);
    });

    it('renders Steps default 20 on the real range widget for an empty config', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let tree = SdConfigPanel.view({ attrs: { config: {} } });

        let ranges = findByTag(tree, 'input').filter(i => i.attrs.type === 'range');
        // Steps is the unique range with bounds min=1, max=100, step=1 (refinerSteps is min=0).
        let steps = ranges.find(r => r.attrs.min === 1 && r.attrs.max === 100 && r.attrs.step === 1);
        expect(steps, 'steps range widget present').toBeTruthy();
        expect(steps.attrs.value).toBe(20);
    });

    it('shows the live-value labels "Denoising: 0.75" and "Steps: 20"', async () => {
        const { SdConfigPanel } = await import('../components/SdConfigPanel.js');
        let tree = SdConfigPanel.view({ attrs: { config: {} } });
        let texts = textOf(tree);
        expect(texts).toContain('Denoising: 0.75');
        expect(texts).toContain('Steps: 20');
    });
});
