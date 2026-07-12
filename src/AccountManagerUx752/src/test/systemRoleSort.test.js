/**
 * KI-7 (remaining OPEN half) — system-role/permission list sort has no effect.
 *
 * When pages.listSystem is true for auth.role/auth.permission, pagination.js's updatePage() served
 * the list straight from page.application.systemRoles.slice(start, start+count) without ever applying
 * pages.sort/pages.order — so clicking the column-sort icon (which only mutates pages.sort/order and
 * re-triggers the query) had no visible effect for this branch. Fixed in components/pagination.js by
 * sorting the array client-side (sortClientList) before slicing.
 *
 * Pure client logic (no live backend needed) — Vitest, following the existing infiniteScroll.test.js
 * mocking pattern for pagination.js's dependencies.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('mithril', () => ({
    default: {
        redraw: vi.fn(),
        route: { set: vi.fn(), param: vi.fn() }
    }
}));

vi.mock('../core/model.js', () => ({
    am7model: {
        jsonModelKey: 'schema',
        getModel: vi.fn(() => ({ name: 'auth.role' })),
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
            entity: { type: 'auth.role', request: [], cache: true },
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
        getRawUrl: vi.fn(() => '#!/list/auth.role'),
        uriStem: vi.fn(() => ''),
        // The systemRoles array pagination.js reads from page.application["system" + fType] —
        // unsorted by design (mirrors real server insertion order, which is creation order, not
        // alphabetical).
        application: {
            systemRoles: [
                { id: 1, name: 'RoleReaders' },
                { id: 2, name: 'AccountAdministrators' },
                { id: 3, name: 'ZetaRole' },
                { id: 4, name: 'MidRole' }
            ]
        },
        iconButton: vi.fn(),
        checkFavorites: vi.fn(),
        components: { decorator: null }
    }
}));

import { newPaginationControl } from '../components/pagination.js';
import { page } from '../core/pageClient.js';
const systemRoles = page.application.systemRoles;

describe('System-role/permission list — client-side sort (KI-7)', () => {
    let pagination;

    beforeEach(() => {
        pagination = newPaginationControl();
    });

    it('sorts the pre-loaded systemRoles slice ascending by name (default sort)', () => {
        pagination.update('auth.role', null, false, null, 0, 10, true);

        let pages = pagination.pages();
        expect(pages.listSystem).toBe(true);
        let names = pages.pageResults[pages.currentPage].map(r => r.name);

        // Sanity: this is a real re-order of the unsorted fixture, not an accidental match.
        expect(names).not.toEqual(systemRoles.map(r => r.name));
        expect(names).toEqual(['AccountAdministrators', 'MidRole', 'RoleReaders', 'ZetaRole']);
    });

    it('re-sorts the slice when sort field/order changes and resort() is called (regression: used to no-op)', () => {
        pagination.update('auth.role', null, false, null, 0, 10, true);
        let pages = pagination.pages();
        // Simulate the column-header click handler (decorator.js): pages.sort already == 'name', so
        // clicking flips the order.
        pages.sort = 'name';
        pages.order = 'descending';
        pagination.resort();

        pages = pagination.pages();
        let names = pages.pageResults[pages.currentPage].map(r => r.name);
        expect(names).toEqual(['ZetaRole', 'RoleReaders', 'MidRole', 'AccountAdministrators']);
    });

    it('sorts by an arbitrary field (id) and order, proving the sort is generic, not name-specific', () => {
        pagination.update('auth.role', null, false, null, 0, 10, true);
        let pages = pagination.pages();
        pages.sort = 'id';
        pages.order = 'descending';
        pagination.resort();

        pages = pagination.pages();
        let ids = pages.pageResults[pages.currentPage].map(r => r.id);
        expect(ids).toEqual([4, 3, 2, 1]);
    });
});
