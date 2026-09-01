// @vitest-environment jsdom
/**
 * ChapBook role-gate + Analyze-persistence unit tests.
 *
 * These exercise the REAL exported helpers from features/chapBook.js — the same predicate the
 * ChapBook components use to BLOCK (button `disabled`) their mutating actions when the AccountUsers
 * role is absent (D6), and the durable read-back-on-init used to restore the reader's Analyze button
 * across a reload (D5). No DOM: `lacksUserRole` is pure and the persistence helpers accept an
 * injectable storage.
 *
 * The role-BLOCK / Analyze-restore behavior in the live browser is not claimed here (a fresh test
 * user is auto-enrolled in AccountUsers, so the block branch is not reachable by fresh-user creation
 * — proving the branch is exactly what this unit test is for).
 */
import { describe, it, expect } from 'vitest';

// A minimal Web-Storage stand-in so the persistence helpers can be tested without a DOM.
function fakeStore() {
    let m = new Map();
    return {
        setItem: (k, v) => { m.set(k, String(v)); },
        getItem: (k) => (m.has(k) ? m.get(k) : null),
        removeItem: (k) => { m.delete(k); },
        _map: m
    };
}

describe('lacksUserRole (D6 — role gate predicate)', () => {
    it('role present → NOT blocked (feature enabled)', async () => {
        let { lacksUserRole } = await import('../features/chapBook.js');
        expect(lacksUserRole({ roles: { user: { id: 1 } } })).toBe(false);
    });

    it('roles object without a user role → blocked', async () => {
        let { lacksUserRole } = await import('../features/chapBook.js');
        expect(lacksUserRole({ roles: {} })).toBe(true);
    });

    it('user role explicitly null/falsy → blocked', async () => {
        let { lacksUserRole } = await import('../features/chapBook.js');
        expect(lacksUserRole({ roles: { user: null } })).toBe(true);
        expect(lacksUserRole({ roles: { user: 0 } })).toBe(true);
    });

    it('missing roles / null / undefined context → blocked', async () => {
        let { lacksUserRole } = await import('../features/chapBook.js');
        expect(lacksUserRole({})).toBe(true);
        expect(lacksUserRole(null)).toBe(true);
        expect(lacksUserRole(undefined)).toBe(true);
    });
});

describe('reader Analyze persistence (D5 — durable read-back on init)', () => {
    it('persist then load round-trips the poem ids for the same book', async () => {
        let { persistReaderPoemIds, loadPersistedReaderPoemIds } = await import('../features/chapBook.js');
        let store = fakeStore();
        persistReaderPoemIds('book-A', ['p1', 'p2', 'p3'], store);
        expect(loadPersistedReaderPoemIds('book-A', store)).toEqual(['p1', 'p2', 'p3']);
    });

    it('a book with nothing persisted loads as an empty list (button hidden, not stale)', async () => {
        let { loadPersistedReaderPoemIds } = await import('../features/chapBook.js');
        let store = fakeStore();
        expect(loadPersistedReaderPoemIds('never-created', store)).toEqual([]);
    });

    it('ids do NOT leak across books — each book re-derives only its own', async () => {
        let { persistReaderPoemIds, loadPersistedReaderPoemIds } = await import('../features/chapBook.js');
        let store = fakeStore();
        persistReaderPoemIds('book-A', ['a1', 'a2'], store);
        // Opening a different book that was never persisted must return [], not book-A's ids.
        expect(loadPersistedReaderPoemIds('book-B', store)).toEqual([]);
        expect(loadPersistedReaderPoemIds('book-A', store)).toEqual(['a1', 'a2']);
    });

    it('is defensive: no bookObjectId / non-array ids are safe no-ops', async () => {
        let { persistReaderPoemIds, loadPersistedReaderPoemIds } = await import('../features/chapBook.js');
        let store = fakeStore();
        persistReaderPoemIds('', ['x'], store);
        persistReaderPoemIds('book-C', 'not-an-array', store);
        expect(store._map.size).toBe(0);
        expect(loadPersistedReaderPoemIds('', store)).toEqual([]);
    });

    it('malformed stored JSON degrades to an empty list rather than throwing', async () => {
        let { loadPersistedReaderPoemIds } = await import('../features/chapBook.js');
        let store = fakeStore();
        store.setItem('cb-poemids-book-D', '{ this is not json');
        expect(loadPersistedReaderPoemIds('book-D', store)).toEqual([]);
    });
});
