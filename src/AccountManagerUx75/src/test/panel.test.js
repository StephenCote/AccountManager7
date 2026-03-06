import { describe, it, expect, beforeAll, vi } from 'vitest';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

// Mock mithril
function mockM(tag, attrs, children) {
    if (typeof attrs === 'string' || Array.isArray(attrs)) {
        children = attrs;
        attrs = {};
    }
    return { tag, attrs: attrs || {}, children };
}
mockM.redraw = vi.fn();
mockM.trust = (s) => s;
mockM.route = { set: vi.fn(), get: () => '/main' };

vi.mock('mithril', () => ({ default: mockM }));

// Wire late-bound refs before importing panel
am7model._view = am7view;
am7model._page = { user: null, context: () => ({ roles: {} }) };
am7model._client = {
    newQuery: () => ({ entity: { request: [] }, field: () => {} }),
    make: vi.fn(),
    search: vi.fn()
};

// Mock page
vi.mock('../core/pageClient.js', () => {
    return {
        page: {
            user: { name: 'admin', organizationPath: '/Development', homeDirectory: { path: '~/home' } },
            authenticated: () => true,
            components: { dialog: { open: vi.fn(), close: vi.fn(), closeAll: vi.fn(), confirm: vi.fn(), loadDialogs: () => '' } },
            navigable: null,
            iconButton: vi.fn(),
            toast: vi.fn(),
            makePath: vi.fn(),
            context: () => ({ roles: {}, contextObjects: {} }),
            views: {}
        }
    };
});

// Mock am7client for panel imports
vi.mock('../core/am7client.js', () => ({
    am7client: {
        make: vi.fn((type, sub, path, cb) => cb && cb({ objectId: 'test-id' })),
        user: vi.fn(),
        search: vi.fn()
    },
    uwm: {
        getDefaultParentForType: vi.fn()
    }
}));

import { panel } from '../components/panel.js';
import { decorator } from '../components/decorator.js';

describe('panel component', () => {
    it('should have a view function', () => {
        expect(typeof panel.view).toBe('function');
    });

    it('should render panel-container', () => {
        let vnode = panel.view();
        expect(vnode).toBeDefined();
        expect(vnode.attrs.class).toContain('panel-container');
    });

    it('should render panel-grid inside container', () => {
        let vnode = panel.view();
        let children = Array.isArray(vnode.children) ? vnode.children : [vnode.children];
        let grid = children.find(function (c) { return c && c.attrs && c.attrs.class && c.attrs.class.includes('panel-grid'); });
        expect(grid).toBeDefined();
        expect(grid.attrs.class).toContain('panel-grid');
    });

    it('should render categories from am7model', () => {
        let vnode = panel.view();
        let children = Array.isArray(vnode.children) ? vnode.children : [vnode.children];
        let grid = children.find(function (c) { return c && c.attrs && c.attrs.class && c.attrs.class.includes('panel-grid'); });
        // grid.children is the array of panel cards from modelPanel()
        let cards = grid.children;
        expect(Array.isArray(cards)).toBe(true);
        // Should have at least the number of categories defined
        expect(cards.length).toBe(am7model.categories.length);
    });
});

describe('decorator', () => {
    it('should export tabularView function', () => {
        expect(typeof decorator.tabularView).toBe('function');
    });

    it('should export listItemView function', () => {
        expect(typeof decorator.listItemView).toBe('function');
    });

    it('should export getDisplayFields function', () => {
        expect(typeof decorator.getDisplayFields).toBe('function');
    });

    it('should return default fields when no inst', () => {
        let fields = decorator.getDisplayFields(null);
        expect(fields).toEqual(['name', 'objectId']);
    });

    it('should return "No results" for empty items in tabularView', () => {
        let result = decorator.tabularView([], null, null);
        expect(result).toBeDefined();
        expect(result.children).toBe('No results');
    });

    it('should return "No results" for empty items in listItemView', () => {
        let result = decorator.listItemView([], null, null);
        expect(result).toBeDefined();
        expect(result.children).toBe('No results');
    });
});
