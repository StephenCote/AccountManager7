/**
 * KI-35 — Store.apparel and Apparel.wearable pickers must DEFAULT their landing container to the
 * parent record's WORLD-relative group, not the acting user's ~/type path.
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

// --- Query builder stub: records the fields set on the auth.group lookup.
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

const WORLD_GROUP = { schema: 'auth.group', id: 5001, objectId: 'world-grp-oid', name: 'Apparel', groupPath: '/World/Apparel' };

vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: vi.fn(),
        // resolveWorldContainer queries auth.group by numeric id
        search: async (q) => {
            let type = q.entity ? q.entity.type : q.type;
            if (type === 'auth.group') {
                let idf = (q.entity.fields || []).find((f) => f.name === 'id');
                if (idf && idf.value === WORLD_GROUP.id) return { results: [JSON.parse(JSON.stringify(WORLD_GROUP))] };
                return { results: [] };
            }
            return { results: [] };
        },
        makePath: async () => ({ objectId: 'user-grp-oid' }),
        findObject: async (type, sub, path) => {
            if (path && String(path).indexOf('Library') !== -1) return { objectId: 'lib-grp-oid' };
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

    it('resolveWorldContainer returns the parent group objectId, queried by NUMERIC id', async () => {
        let oid = await resolveWorldContainer('olio.store', 'apparel', { groupId: 5001 });
        expect(oid).toBe('world-grp-oid');
    });

    it('resolveWorldContainer returns null when not a world-group picker (falls back to user path)', async () => {
        let oid = await resolveWorldContainer('data.color', 'foo', { groupId: 5001 });
        expect(oid).toBe(null);
    });

    it('resolveWorldContainer returns null when the parent has no groupId', async () => {
        let oid = await resolveWorldContainer('olio.store', 'apparel', {});
        expect(oid).toBe(null);
    });

    it('Store.apparel picker LANDS on the world group, toggle containers still resolved', async () => {
        await ObjectPicker.open({
            type: 'olio.wearable',
            parentModel: 'olio.store',
            parentField: 'apparel',
            parentEntity: { groupId: 5001 }
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
            parentEntity: { groupId: 5001 }
        });
        expect(openedWith.containerId).toBe('world-grp-oid');
    });

    it('a non-world-group picker is unaffected: lands on the user path, toggles intact', async () => {
        await ObjectPicker.open({
            type: 'data.color',
            parentModel: 'data.color',
            parentField: 'foo',
            parentEntity: { groupId: 5001 }
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
            parentEntity: { groupId: 5001 }
        });
        expect(openedWith.containerId).toBe('explicit-oid');
    });
});
