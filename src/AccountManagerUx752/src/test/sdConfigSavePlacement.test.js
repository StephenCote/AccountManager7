/**
 * am7sd.saveConfig must place a config where its arguments say (name + group), never where the config
 * object's own directory fields say.
 *
 * Since the client olio.sd.config block inherits data.directory (parity with the server, PF-15b),
 * am7model.newPrimitive('olio.sd.config') seeds name:null, groupId:0, organizationId:0, groupPath and
 * ownerId:0 like every other directory record. saveConfig copies every key of the config it is handed
 * AFTER setting obj.name / obj.groupId / obj.groupPath from its own arguments, so the sites that build a
 * config from newPrimitive without stripping identity (reimage.js, reimageApparel.js, chat/imageTokens.js,
 * cardGame artPipeline.js) would otherwise save "name":null in group 0. /olio/randomImageConfig already
 * returns name:null, so the primary path had the same shape.
 *
 * These tests drive the REAL saveConfig with a stub page/client and assert on the exact object sent.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { am7model } from '../core/model.js';
import { am7sd } from '../components/sdConfig.js';

const grp = { id: 4242, objectId: 'grp-oid', path: '~/Data/.preferences' };
let created, patched, searchResults;

const stubPage = {
    makePath: async () => grp,
    search: async () => ({ results: searchResults }),
    createObject: async (o) => { created = o; return { objectId: 'new-oid' }; },
    patchObject: async (p) => { patched = p; return true; }
};
const stubClient = {
    newQuery: () => ({ field() { return this; }, cache() { return this; } }),
    clearCache: () => {}
};

beforeEach(() => {
    created = null;
    patched = null;
    searchResults = [];
    am7model._page = stubPage;
    am7model._client = stubClient;
});

describe('premise: a client olio.sd.config primitive now carries directory fields', () => {
    it('newPrimitive seeds name/groupId/organizationId like any data.directory record', () => {
        const prim = am7model.newPrimitive('olio.sd.config');
        expect(prim).toHaveProperty('name');
        expect(prim).toHaveProperty('groupId');
        expect(prim).toHaveProperty('organizationId');
        expect(prim.name).toBeNull();
        expect(prim.groupId).toBe(0);
    });
});

describe('saveConfig places the record by its own arguments', () => {
    it('CREATE: a newPrimitive config is created under the given name and group, not name:null in group 0', async () => {
        const prim = am7model.newPrimitive('olio.sd.config');
        prim.steps = 33;
        const ok = await am7sd.saveConfig('myConfig', prim);
        expect(ok).toBe(true);
        expect(created).not.toBeNull();
        expect(created.schema).toBe('olio.sd.config');
        expect(created.name).toBe('myConfig');
        expect(created.groupId).toBe(grp.id);
        expect(created.groupPath).toBe(grp.path);
        expect(created).not.toHaveProperty('organizationId');
        expect(created).not.toHaveProperty('organizationPath');
        expect(created).not.toHaveProperty('ownerId');
        expect(created.steps).toBe(33);
    });

    it('PATCH: an existing record keeps its identity and name; the config cannot move it to another group', async () => {
        searchResults = [{ schema: 'olio.sd.config', id: 77, objectId: 'existing-oid', name: 'myConfig', groupId: grp.id }];
        const prim = am7model.newPrimitive('olio.sd.config');
        prim.cfg = 6.5;
        const ok = await am7sd.saveConfig('myConfig', prim);
        expect(ok).toBe(true);
        expect(created).toBeNull();
        expect(patched).not.toBeNull();
        expect(patched.id).toBe(77);
        expect(patched.objectId).toBe('existing-oid');
        expect(patched.name).toBe('myConfig');
        expect(patched).not.toHaveProperty('groupId');
        expect(patched).not.toHaveProperty('organizationId');
        expect(patched).not.toHaveProperty('groupPath');
        expect(patched.cfg).toBe(6.5);
    });

    it('a config loaded from ANOTHER group and saved under a new name lands in the requested group', async () => {
        const loaded = { schema: 'olio.sd.config', id: 5, objectId: 'src-oid', name: 'sourceCfg',
            groupId: 999, groupPath: '~/Data/elsewhere', organizationId: 2, ownerId: 31, steps: 20 };
        await am7sd.saveConfig('copyCfg', loaded);
        expect(created.name).toBe('copyCfg');
        expect(created.groupId).toBe(grp.id);
        expect(created.groupPath).toBe(grp.path);
        expect(created).not.toHaveProperty('id');
        expect(created).not.toHaveProperty('objectId');
        expect(created).not.toHaveProperty('organizationId');
        expect(created.steps).toBe(20);
    });
});
