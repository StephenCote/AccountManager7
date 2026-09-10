import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';
import { formFieldRenderers } from '../components/formFieldRenderers.js';

// charPerson's trades/race/ethnicity are list<string> fields shown as `textlist`
// (a textarea, one element per line). The instance's textListDecorator owns the
// array<->string conversion: the api getter joins on newlines for display, the api
// setter (via handleChange -> decorateIn) splits the newline string back to an array.
//
// Regression: the textlist renderer used to ALSO split the string and then assign the
// resulting array to e.target.value before calling fHandler. A real DOM element coerces
// an assigned array to a comma-joined string, so the decorator then split a string with
// no newlines and collapsed the whole list into ONE element:
//   ["trade1","trade2"] typed as two lines -> saved as ["trade1,trade2"].
// That also corrupts values that later map to enums (race ["R","V"] -> ["R,V"]).

const TEXTLIST_FIELDS = ['trades', 'race', 'ethnicity'];

beforeAll(() => {
    // Register the textListDecorator on these fields by reporting their form format.
    am7model._view = {
        path: () => '',
        pathForType: () => '',
        formField: (form, name) => (TEXTLIST_FIELDS.includes(name) ? { format: 'textlist' } : null)
    };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

function makeInstance() {
    let entity = am7model.newPrimitive('olio.charPerson');
    let form = {
        fields: {
            trades: { format: 'textlist' },
            race: { format: 'textlist' },
            ethnicity: { format: 'textlist' }
        }
    };
    return am7model.prepareInstance(entity, form);
}

// Mimic a real <textarea> DOM node: assigning any value to `.value` coerces it to a
// string, which is exactly the behavior the old renderer tripped over.
function textareaEvent(value) {
    let store = value;
    return {
        redraw: true,
        target: {
            type: 'textarea',
            get value() { return store; },
            set value(x) { store = String(x); }
        }
    };
}

// Build the same ctx object.js hands to the renderer for one field.
function rendererCtx(inst, name) {
    return {
        inst,
        name,
        useName: name,
        defVal: inst.api[name](),
        fieldClass: '',
        fHandler: inst.handleChange(name)
    };
}

function textlistOnchange(ctx) {
    let vnodes = formFieldRenderers.get('textlist')(ctx);
    let div = vnodes[0];
    let textarea = Array.isArray(div.children) ? div.children[0] : div.children;
    // Mithril normalizes the textarea's text child into a "#" text vnode.
    let child = Array.isArray(textarea.children) ? textarea.children[0] : textarea.children;
    let value = (child && child.tag === '#') ? child.children : child;
    return { onchange: textarea.attrs.onchange, value };
}

describe('textlist field (charPerson trades/race/ethnicity)', () => {
    it('registers the array<->string decorator via the api getter/setter', () => {
        let inst = makeInstance();
        inst.entity.trades = ['smith', 'baker'];
        // getter joins on newlines for display
        expect(inst.api.trades()).toBe('smith\r\nbaker');
        // setter splits a newline string back into an array
        inst.api.trades('smith\nbaker\nmason');
        expect(inst.entity.trades).toEqual(['smith', 'baker', 'mason']);
    });

    it('renders existing values one-per-line in the textarea', () => {
        let inst = makeInstance();
        inst.entity.trades = ['smith', 'baker'];
        let { value } = textlistOnchange(rendererCtx(inst, 'trades'));
        expect(value).toBe('smith\r\nbaker');
    });

    it('saves a multi-line edit as a proper array, not a single comma-joined element', () => {
        let inst = makeInstance();
        inst.entity.trades = ['trade1', 'trade2'];
        let { onchange } = textlistOnchange(rendererCtx(inst, 'trades'));

        // User appends a third line in the textarea and blurs.
        onchange(textareaEvent('trade1\ntrade2\ntrade3'));

        expect(inst.entity.trades).toEqual(['trade1', 'trade2', 'trade3']);
        // The regression produced a length-1 array whose single element was comma-joined.
        expect(inst.entity.trades.length).toBe(3);
        expect(inst.entity.trades[0]).toBe('trade1');
    });

    it('keeps enum-mapped race values as separate elements (vampire robot: R + V)', () => {
        let inst = makeInstance();
        inst.entity.race = ['R'];
        let { onchange } = textlistOnchange(rendererCtx(inst, 'race'));

        onchange(textareaEvent('R\nV'));

        expect(inst.entity.race).toEqual(['R', 'V']);
        // The bug would have produced ["R,V"], an invalid enum for NarrativeUtil/SDUtil.
        expect(inst.entity.race).not.toContain('R,V');
    });

    it('treats an emptied textarea as an empty list', () => {
        let inst = makeInstance();
        inst.entity.ethnicity = ['x', 'y'];
        let { onchange } = textlistOnchange(rendererCtx(inst, 'ethnicity'));

        onchange(textareaEvent(''));

        expect(inst.entity.ethnicity).toEqual([]);
    });
});
