/**
 * KI-35 — Store.apparel and Apparel.wearable pickers must DEFAULT their landing container to the
 * parent record's WORLD-relative group, not the acting user's ~/type path.
 *
 * ⚠ FALSE-CONFIDENCE WARNING — read before trusting a green run here.
 * These are UNIT tests with the network fully MOCKED. The `page.findObject` mock (below) SYNTHESIZES
 * a world `auth.group` record for any path under `.../Worlds/<slug>/...`, so it reports SUCCESS
 * regardless of the real PBAC outcome. It therefore CANNOT catch the two things that actually caused
 * (and now guard against) the reported bug:
 *   1. A POST /rest/model/search on `auth.group` by a NON-OWNING user returns an EMPTY body against
 *      an Olio-principal-owned world group (measured live: acting user id=16 vs group ownerId=17 →
 *      0 rows). The OLD implementation resolved the world group via that search and so ALWAYS got
 *      null, silently falling back to the user path — the exact reported bug. The fix switched to
 *      GET /rest/path/find (`page.findObject`), which IS authorized for the acting user. A stub can't
 *      exercise that authorization difference.
 *   2. `groupPath` is a VIRTUAL provider-computed field (PathProvider walks the parent chain); it is
 *      projectable but NOT SQL-filterable. A stub can't exercise that either.
 * The REAL, load-bearing verification is the Playwright e2e:
 *   e2e/worldGroupPicker.e2e.spec.js  (drives the real resolveWorldContainer / ObjectPicker.open
 *   against the live backend with a real book-world apparel loaded via getFull, and asserts the
 *   picker lands on the world Wearables group owned by the Olio principal, not the user home path).
 * Keep these unit tests as a fast shape check of the branch logic (does resolveWorldContainer derive
 * the right world-subgroup path from the parent's groupPath, and does open() prefer it over the user
 * fallback?), but DO NOT treat them as proof the fix works end-to-end.
 *
 * Stephen's report: "If I add a wearable from the user default group, Olio User won't have access to
 * set/unset the `inuse` bit, so it's stuck on or off when it comes time to narrate or reimage."
 * A wearable/apparel created under the user's own group is owned there; the Olio principal that runs
 * narrate/reimage cannot toggle its `inuse` bit (KI-35). Landing the picker on the parent's world
 * group puts new records where the Olio principal already holds grants.
 *
 * These are behavioral checks against the REAL picker logic (isWorldGroupPicker / resolveWorldContainer
 * and ObjectPicker.open). Only the module boundaries are stubbed: model/view metadata, the network
 * (page.search / makePath / findObject / favorites), the query builder, and the embedded list control
 * (so open() records what container it was told to land on). The user / home / library / favorites
 * toggle containers are asserted to still be resolved, proving navigation is preserved.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('mithril', () => ({ default: { redraw: vi.fn() } }));

// --- Model/view metadata: every type here is a groupId-bearing directory (usesContainer === true).
vi.mock('../core/model.js', () => ({
    am7model: {
        getModel: (t) => (t ? { name: t } : null),
        hasField: (t, f) => f === 'groupId',
        isGroup: () => false,
        isParent: () => false,
        system: { library: { 'olio.wearable': '/Library/Wearables', 'olio.store': '/Library/Store', 'olio.apparel': '/Library/Apparel', 'data.color': '/Library/Colors' } }
    }
}));

vi.mock('../core/view.js', () => ({
    am7view: {
        typeToModel: (t) => t,
        pathForType: (t) => '~/' + (t || 'auth.group')
    }
}));

// --- Query builder stub: records the fields set on any lookup.
function stubQuery(type) {
    let q = {
        entity: { type: type, request: [], fields: [], cache: true },
        field: function (name, value) { let fld = { name: name, value: value }; q.entity.fields.push(fld); return fld; }
    };
    return q;
}

vi.mock('../core/am7client.js', () => ({
    am7client: {
        base: () => 'https://localhost:8443/rest',
        clearCache: vi.fn(),
        currentOrganization: '/Test',
        newQuery: (t) => stubQuery(t),
        user: vi.fn()
    }
}));

// A world group lives at /Olio/Universes/Books/Worlds/<slug>/<SubGroup>, owned by the Olio principal.
const WORLD_ROOT = '/Olio/Universes/Books/Worlds/testworld';
const APPAREL_WORLD_GROUP_PATH = WORLD_ROOT + '/Apparel';   // parent (olio.store / olio.apparel) lives here
const WORLD_GROUP = { schema: 'auth.group', objectId: 'world-grp-oid', name: 'Apparel', groupPath: WORLD_ROOT + '/Apparel' };

vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: vi.fn(),
        // In production a model/search on auth.group by a NON-OWNING user returns empty for an
        // Olio-principal-owned world group — which is exactly why resolveWorldContainer no longer
        // uses search. Mirror that here so nothing accidentally resolves a world group via search.
        search: async () => ({ results: [] }),
        makePath: async () => ({ objectId: 'user-grp-oid' }),
        // resolveWorldContainer resolves the world subgroup via GET /rest/path/find (page.findObject).
        // Any path under .../Worlds/<slug>/... is a world group; Library paths are the shared library;
        // everything else is the user's home root.
        findObject: async (type, sub, path) => {
            let p = String(path || '');
            if (p.indexOf('/Worlds/') !== -1) return { objectId: 'world-grp-oid', groupPath: p };
            if (p.indexOf('Library') !== -1) return { objectId: 'lib-grp-oid' };
            return { objectId: 'home-grp-oid' };
        },
        favorites: async () => ({ objectId: 'fav-grp-oid' })
    }
}));

// --- Embedded list control: capture what container the picker was told to open on.
let openedWith = null;
vi.mock('../views/list.js', () => ({
    newListControl: () => ({
        openForPicker: (o) => { openedWith = o; },
        closePickerMode: vi.fn(),
        renderContent: vi.fn(),
        pagination: () => ({ pages: () => ({}) })
    })
}));

// document is absent in the node env; open() registers an Escape keydown handler.
if (typeof globalThis.document === 'undefined') {
    globalThis.document = { addEventListener: () => {}, removeEventListener: () => {} };
}

const { ObjectPicker, isWorldGroupPicker, resolveWorldContainer } = await import('../components/picker.js');

describe('KI-35 world-group pickers', () => {
    beforeEach(() => { openedWith = null; });

    it('isWorldGroupPicker identifies exactly Store.apparel and Apparel.wearable', () => {
        expect(isWorldGroupPicker('olio.store', 'apparel')).toBe(true);
        expect(isWorldGroupPicker('olio.apparel', 'wearables')).toBe(true);
        // Not these:
        expect(isWorldGroupPicker('olio.store', 'wearables')).toBe(false);
        expect(isWorldGroupPicker('olio.apparel', 'apparel')).toBe(false);
        expect(isWorldGroupPicker('data.color', 'apparel')).toBe(false);
        expect(isWorldGroupPicker(null, 'apparel')).toBe(false);
        expect(isWorldGroupPicker('olio.store', null)).toBe(false);
    });

    it('resolveWorldContainer derives the world subgroup from the parent groupPath (Store.apparel → .../Apparel)', async () => {
        // Parent (olio.store) lives under a world; picker targets the sibling world "Apparel" subgroup.
        let oid = await resolveWorldContainer('olio.store', 'apparel', { groupPath: APPAREL_WORLD_GROUP_PATH });
        expect(oid).toBe('world-grp-oid');
    });

    it('resolveWorldContainer derives the SIBLING subgroup for a different field (Apparel.wearables → .../Wearables)', async () => {
        // The parent apparel lives in .../Apparel, but the wearables picker must land on .../Wearables.
        // Capture the path findObject was asked for to prove the field→subgroup mapping is applied.
        let askedPath = null;
        let mod = await import('../core/pageClient.js');
        let orig = mod.page.findObject;
        mod.page.findObject = async (type, sub, path) => { askedPath = path; return orig(type, sub, path); };
        try {
            let oid = await resolveWorldContainer('olio.apparel', 'wearables', { groupPath: APPAREL_WORLD_GROUP_PATH });
            expect(oid).toBe('world-grp-oid');
            expect(askedPath).toBe(WORLD_ROOT + '/Wearables');
        } finally {
            mod.page.findObject = orig;
        }
    });

    it('resolveWorldContainer returns null when not a world-group picker (falls back to user path)', async () => {
        let oid = await resolveWorldContainer('data.color', 'foo', { groupPath: APPAREL_WORLD_GROUP_PATH });
        expect(oid).toBe(null);
    });

    it('resolveWorldContainer returns null when the parent has no groupPath', async () => {
        let oid = await resolveWorldContainer('olio.store', 'apparel', {});
        expect(oid).toBe(null);
    });

    it('resolveWorldContainer returns null when the parent is NOT in a world (user home groupPath)', async () => {
        // This is the reported bug scenario: apparel created under the user's own group. There is no
        // /Worlds/<slug>/ segment, so no world group applies and the caller keeps the user-path landing.
        let oid = await resolveWorldContainer('olio.apparel', 'wearables', { groupPath: '/home/e2etest_shared/Apparel' });
        expect(oid).toBe(null);
    });

    it('Store.apparel picker LANDS on the world group, toggle containers still resolved', async () => {
        await ObjectPicker.open({
            type: 'olio.wearable',
            parentModel: 'olio.store',
            parentField: 'apparel',
            parentEntity: { groupPath: APPAREL_WORLD_GROUP_PATH }
        });
        expect(openedWith).not.toBe(null);
        // The DEFAULT landing container is the parent's world group.
        expect(openedWith.containerId).toBe('world-grp-oid');
        // Navigation preserved: user / home / library / favorites toggle sources are all resolved.
        expect(openedWith.userContainerId).toBe('user-grp-oid');
        expect(openedWith.homeContainerId).toBe('home-grp-oid');
        expect(openedWith.libraryContainerId).toBe('lib-grp-oid');
        expect(openedWith.favoritesContainerId).toBe('fav-grp-oid');
    });

    it('Apparel.wearable picker LANDS on the world group', async () => {
        await ObjectPicker.open({
            type: 'olio.wearable',
            parentModel: 'olio.apparel',
            parentField: 'wearables',
            parentEntity: { groupPath: APPAREL_WORLD_GROUP_PATH }
        });
        expect(openedWith.containerId).toBe('world-grp-oid');
    });

    it('a world-picker parent OUTSIDE a world falls back to the user path (reported bug scenario)', async () => {
        // Same picker (Apparel.wearables), but the parent apparel lives under the user's home group,
        // not a world. resolveWorldContainer returns null and the picker lands on the user path.
        await ObjectPicker.open({
            type: 'olio.wearable',
            parentModel: 'olio.apparel',
            parentField: 'wearables',
            parentEntity: { groupPath: '/home/e2etest_shared/Apparel' }
        });
        expect(openedWith.containerId).toBe('user-grp-oid');
    });

    it('a non-world-group picker is unaffected: lands on the user path, toggles intact', async () => {
        await ObjectPicker.open({
            type: 'data.color',
            parentModel: 'data.color',
            parentField: 'foo',
            parentEntity: { groupPath: APPAREL_WORLD_GROUP_PATH }
        });
        // No world default → falls back to the user's own path (unchanged behavior).
        expect(openedWith.containerId).toBe('user-grp-oid');
        expect(openedWith.userContainerId).toBe('user-grp-oid');
        expect(openedWith.libraryContainerId).toBe('lib-grp-oid');
    });

    it('an explicit containerId opt still wins (world default only applies to the fallback)', async () => {
        await ObjectPicker.open({
            type: 'olio.wearable',
            containerId: 'explicit-oid',
            parentModel: 'olio.store',
            parentField: 'apparel',
            parentEntity: { groupPath: APPAREL_WORLD_GROUP_PATH }
        });
        expect(openedWith.containerId).toBe('explicit-oid');
    });
});
