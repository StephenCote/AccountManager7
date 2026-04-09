/**
 * Unit tests for infinite scroll pagination support.
 *
 * Tests the new pagination methods: allResults(), loadNextPage(),
 * hasMorePages(), setInfiniteScroll()/isInfiniteScroll().
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock minimal dependencies before importing
vi.mock('mithril', () => ({
    default: {
        redraw: vi.fn(),
        route: { set: vi.fn(), param: vi.fn() }
    }
}));

vi.mock('../core/model.js', () => ({
    am7model: {
        jsonModelKey: 'schema',
        getModel: vi.fn(() => null),
        hasField: vi.fn(() => false),
        queryFields: vi.fn(() => ['id', 'objectId', 'name']),
        isGroup: vi.fn(() => false),
        isParent: vi.fn(() => false),
        updateListModel: vi.fn()
    }
}));

vi.mock('../core/view.js', () => ({
    am7view: {}
}));

vi.mock('../core/am7client.js', () => ({
    am7client: {
        newQuery: vi.fn(() => ({
            entity: { type: 'data.note', request: [], cache: true },
            field: vi.fn(),
            range: vi.fn(),
            sort: vi.fn(),
            order: vi.fn(),
            key: vi.fn(() => 'test-key'),
            cache: vi.fn()
        })),
        search: vi.fn(),
        searchCount: vi.fn(),
        get: vi.fn(),
        members: vi.fn(),
        countMembers: vi.fn()
    }
}));

vi.mock('../core/pageClient.js', () => ({
    page: {
        uid: vi.fn(() => 'test-uid-' + Math.random()),
        user: { organizationId: 'test-org' },
        getRawUrl: vi.fn(() => '#!/list/data.note'),
        uriStem: vi.fn(() => ''),
        application: {},
        iconButton: vi.fn(),
        checkFavorites: vi.fn(),
        components: { decorator: null }
    }
}));

import { newPaginationControl } from '../components/pagination.js';

describe('Pagination infinite scroll API', () => {
    let pagination;

    beforeEach(() => {
        pagination = newPaginationControl();
    });

    it('exposes setInfiniteScroll / isInfiniteScroll', () => {
        expect(typeof pagination.setInfiniteScroll).toBe('function');
        expect(typeof pagination.isInfiniteScroll).toBe('function');
        expect(pagination.isInfiniteScroll()).toBe(false);

        pagination.setInfiniteScroll(true);
        expect(pagination.isInfiniteScroll()).toBe(true);

        pagination.setInfiniteScroll(false);
        expect(pagination.isInfiniteScroll()).toBe(false);
    });

    it('exposes allResults()', () => {
        expect(typeof pagination.allResults).toBe('function');
        // With no pages loaded, returns empty
        let results = pagination.allResults();
        expect(results).toEqual([]);
    });

    it('exposes hasMorePages()', () => {
        expect(typeof pagination.hasMorePages).toBe('function');
        // With pages loaded at last page, should be false
        let pages = pagination.pages();
        pages.currentPage = 3;
        pages.pageCount = 3;
        expect(pagination.hasMorePages()).toBe(false);
    });

    it('exposes loadNextPage()', () => {
        expect(typeof pagination.loadNextPage).toBe('function');
        // At last page, should return false
        let pages = pagination.pages();
        pages.currentPage = 1;
        pages.pageCount = 1;
        let result = pagination.loadNextPage();
        expect(result).toBe(false);
    });

    it('allResults() concatenates multiple pages', () => {
        let pages = pagination.pages();
        pages.pageResults[1] = [{ id: 1, name: 'A' }, { id: 2, name: 'B' }];
        pages.pageResults[2] = [{ id: 3, name: 'C' }, { id: 4, name: 'D' }];
        pages.pageResults[3] = [{ id: 5, name: 'E' }];
        pages.currentPage = 3;
        pages.pageCount = 5;

        let all = pagination.allResults();
        expect(all).toHaveLength(5);
        expect(all[0].name).toBe('A');
        expect(all[2].name).toBe('C');
        expect(all[4].name).toBe('E');
    });

    it('allResults() only includes up to currentPage', () => {
        let pages = pagination.pages();
        pages.pageResults[1] = [{ id: 1 }];
        pages.pageResults[2] = [{ id: 2 }];
        pages.pageResults[3] = [{ id: 3 }];
        pages.currentPage = 2;

        let all = pagination.allResults();
        expect(all).toHaveLength(2);
    });

    it('hasMorePages() returns true when more pages exist', () => {
        let pages = pagination.pages();
        pages.currentPage = 2;
        pages.pageCount = 5;
        expect(pagination.hasMorePages()).toBe(true);
    });

    it('hasMorePages() returns false at last page', () => {
        let pages = pagination.pages();
        pages.currentPage = 5;
        pages.pageCount = 5;
        expect(pagination.hasMorePages()).toBe(false);
    });

    it('new() resets infinite scroll state', () => {
        pagination.setInfiniteScroll(true);
        pagination.new();
        // Infinite scroll flag persists across resets (it's a mode, not page state)
        expect(pagination.isInfiniteScroll()).toBe(true);
        // But page data is reset
        expect(pagination.allResults()).toEqual([]);
    });

    it('allResults() handles sparse pageResults', () => {
        let pages = pagination.pages();
        pages.pageResults[1] = [{ id: 1 }];
        // page 2 not loaded yet (undefined)
        pages.pageResults[3] = [{ id: 3 }];
        pages.currentPage = 3;

        let all = pagination.allResults();
        // Should skip missing page 2
        expect(all).toHaveLength(2);
    });
});

describe('GridPreview and FullCanvasViewer infinite scroll props', () => {
    it('imageViewer exports GridPreview and FullCanvasViewer', async () => {
        // Verify the components exist in the module
        let mod = await import('../components/imageViewer.js');
        expect(typeof mod.GridPreview).toBe('function');
        expect(typeof mod.FullCanvasViewer).toBe('function');
    });
});
