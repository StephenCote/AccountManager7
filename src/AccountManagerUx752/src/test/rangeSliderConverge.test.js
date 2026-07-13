/**
 * KI-16 Finding C — converge divergent range-slider implementations onto one canonical widget.
 *
 * components/formFieldRenderers.js's schema-driven `renderers.range` (used by every `formDef.js`
 * field with format:"range") is now built on a small shared `renderRangeSliderSpinner` helper, also
 * exposed as `formFieldRenderers.renderRange({value, onInput, min, max, step, label, disabled,
 * fieldClass, name})` for callers that edit plain config objects rather than am7model instances.
 * Ported onto it: workflows/reimage.js, workflows/reimageApparel.js, workflows/pictureBook.js, and
 * components/SdConfigPanel.js's own `rangeInput()` helper (previously 4 bespoke, schema-blind,
 * single-input-with-no-spinner variants — see aiDocs/KnownIssues.md KI-16 Finding C).
 *
 * These are real behavioral checks against the actual returned Mithril vnode tree and the actual
 * `oninput` handlers — not source-text greps.
 */
import { describe, it, expect, vi, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';

beforeAll(() => {
    am7model._view = { path: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
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

describe('formFieldRenderers.renderRange — plain-contract canonical slider (KI-16 Finding C)', () => {
    it('renders exactly one range input and one companion number spinner, with matching bounds', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let onInput = vi.fn();
        let out = formFieldRenderers.renderRange({ value: 42, min: 0, max: 100, step: 5, label: 'Steps', onInput });

        let inputs = findByTag(out, 'input');
        let rangeInputs = inputs.filter(i => i.attrs.type === 'range');
        let numberInputs = inputs.filter(i => i.attrs.type === 'number');
        expect(rangeInputs.length).toBe(1);
        expect(numberInputs.length).toBe(1);

        for (let inp of [rangeInputs[0], numberInputs[0]]) {
            expect(inp.attrs.min).toBe(0);
            expect(inp.attrs.max).toBe(100);
            expect(inp.attrs.step).toBe(5);
            expect(inp.attrs.value).toBe(42);
        }
    });

    it('the sr-only label carries the given label text', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let out = formFieldRenderers.renderRange({ value: 1, min: 0, max: 10, step: 1, label: 'CFG', onInput: vi.fn() });
        let labels = findByTag(out, 'label');
        expect(labels.length).toBe(1);
        // Mithril's hyperscript normalizes a `class` attr into `className` on the vnode.
        expect(labels[0].attrs.className).toContain('sr-only');
        expect(labels[0].children[0].children).toBe('CFG');
    });

    it('dragging the range input (oninput) actually invokes the caller-supplied handler', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let received = null;
        let onInput = function (e) { received = e.target.value; };
        let out = formFieldRenderers.renderRange({ value: 5, min: 0, max: 10, step: 1, onInput });

        let rangeInput = findByTag(out, 'input').find(i => i.attrs.type === 'range');
        // Simulate the browser firing oninput while dragging — same call shape decorator/workflow
        // callers rely on.
        rangeInput.attrs.oninput({ target: { value: '7' } });
        expect(received).toBe('7');
    });

    it('the spinner input uses the SAME handler, so typing a number updates the value too', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let calls = [];
        let onInput = function (e) { calls.push(e.target.value); };
        let out = formFieldRenderers.renderRange({ value: 5, min: 0, max: 10, step: 1, onInput });

        let numberInput = findByTag(out, 'input').find(i => i.attrs.type === 'number');
        numberInput.attrs.oninput({ target: { value: '9' } });
        expect(calls).toEqual(['9']);
    });

    it('disabled propagates to both the slider and the spinner', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let out = formFieldRenderers.renderRange({ value: 1, min: 0, max: 10, step: 1, onInput: vi.fn(), disabled: true });
        let inputs = findByTag(out, 'input');
        expect(inputs.every(i => i.attrs.disabled === true)).toBe(true);
    });
});

describe('renderers.range (schema-driven) is unchanged behaviorally after the refactor', () => {
    it('still emits one range + one number input honoring minValue/maxValue from the field schema', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let ctx = {
            field: { type: 'int', minValue: 1, maxValue: 20 },
            defVal: 10,
            useName: 'physicalStrength',
            fHandler: vi.fn()
        };
        let out = formFieldRenderers.render('range', ctx);
        let inputs = findByTag(out, 'input');
        let rangeInput = inputs.find(i => i.attrs.type === 'range');
        let numberInput = inputs.find(i => i.attrs.type === 'number');
        expect(rangeInput.attrs.min).toBe(1);
        expect(rangeInput.attrs.max).toBe(20);
        expect(rangeInput.attrs.value).toBe(10);
        expect(numberInput.attrs.min).toBe(1);
        expect(numberInput.attrs.max).toBe(20);
    });
});
