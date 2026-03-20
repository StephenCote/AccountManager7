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
import { am7decorator, defaultHeaderMap, getHeaders, getIcon, getFileTypeIcon, getTabularView, getGridListView, getCarouselView, renderMediaPreview } from '../components/decorator.js';

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
    it('should export am7decorator object with required methods', () => {
        expect(typeof am7decorator.map).toBe('function');
        expect(typeof am7decorator.icon).toBe('function');
        expect(typeof am7decorator.fileIcon).toBe('function');
        expect(typeof am7decorator.tabularView).toBe('function');
        expect(typeof am7decorator.gridListView).toBe('function');
        expect(typeof am7decorator.carouselView).toBe('function');
        expect(typeof am7decorator.carouselItem).toBe('function');
        expect(typeof am7decorator.renderMediaPreview).toBe('function');
    });

    it('map() should return defaultHeaderMap', () => {
        let map = am7decorator.map();
        expect(Array.isArray(map)).toBe(true);
        expect(map).toContain('_rowNum');
        expect(map).toContain('_icon');
        expect(map).toContain('name');
        expect(map).toContain('_tags');
        expect(map).toContain('_favorite');
        expect(map).toBe(defaultHeaderMap);
    });

    it('should export named functions', () => {
        expect(typeof getHeaders).toBe('function');
        expect(typeof getIcon).toBe('function');
        expect(typeof getFileTypeIcon).toBe('function');
        expect(typeof getTabularView).toBe('function');
        expect(typeof getGridListView).toBe('function');
        expect(typeof getCarouselView).toBe('function');
        expect(typeof renderMediaPreview).toBe('function');
    });

    it('tabularView should return memoized component vnode for empty results', () => {
        let result = getTabularView({ pagination: { pages: () => ({}) }, listType: 'data.data', onscroll: null }, []);
        // Now returns a Mithril component vnode (TabularMemo wrapper) — empty check is inside view()
        expect(result).toBeDefined();
        expect(result.tag).toBeDefined();
        expect(typeof result.tag.onbeforeupdate).toBe('function');
    });

    it('renderMediaPreview should return empty for null item', () => {
        let result = renderMediaPreview(null);
        expect(result).toBe('');
    });

    it('modelHeaderMap should have per-model overrides', () => {
        expect(am7decorator.modelHeaderMap['data.data']).toBeDefined();
        expect(am7decorator.modelHeaderMap['auth.group']).toBeDefined();
        expect(am7decorator.modelHeaderMap['data.data']).toContain('contentType');
    });
});
