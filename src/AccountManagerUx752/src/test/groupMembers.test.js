/**
 * KI-1 — auth.group member picker + list for PERSON / ACCOUNT / USER.
 *
 * auth.role has one `$flex` members field whose participant model is derived from the role's `type`
 * enum. auth.group has no such discriminator (a group can hold all three participant types at once),
 * so formDef.js injects three virtual list fields — userMembers / accountMembers / personMembers —
 * each pinned to a concrete participant model, surfaced on a "Members" tab (forms.groupmembers).
 * membership.objectMembers() now loads a fixed-baseModel field via am7client.members(container,
 * objectId, <baseModel>, ...) instead of returning [] when there is no type attribute.
 *
 * These are real behavioural checks against the actual membership module (the am7client stub records
 * the call it receives); the live add/see/remove flow is e2e/groupMembers.spec.js.
 */
import { describe, it, expect, beforeAll, vi } from 'vitest';
import { am7model } from '../core/model.js';
import '../core/formDef.js';

let membersCalls = [];

beforeAll(() => {
    am7model._view = { path: () => '', pathForType: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = {
        newQuery: () => ({ entity: { request: [] }, field: () => {} }),
        members: (schema, objectId, actorType, start, count, cb) => {
            membersCalls.push({ schema, objectId, actorType, start, count });
            cb([{ schema: actorType, objectId: 'm-1', name: 'member-one' }]);
        }
    };
});

function groupField(name) {
    return am7model.getModelFields('auth.group').filter(f => f.name === name)[0];
}

describe('KI-1 auth.group per-type member fields (formDef.js)', () => {
    it('declares virtual userMembers/accountMembers/personMembers list fields pinned to their participant models', () => {
        let expected = { userMembers: 'system.user', accountMembers: 'identity.account', personMembers: 'identity.person' };
        Object.keys(expected).forEach(name => {
            let f = groupField(name);
            expect(f, name).toBeDefined();
            expect(f.type).toBe('list');
            expect(f.baseModel).toBe(expected[name]);
            expect(f.function).toBe('objectMembers');
            expect(f.virtual).toBe(true);
            expect(f.ephemeral).toBe(true);
            // No enum discriminator — unlike auth.role.members ($flex + foreignType:'type').
            expect(f.foreignType).toBeUndefined();
        });
    });

    it('forms.group shows a Members tab (forms.groupmembers) with one table per participant type, each with add/remove', () => {
        expect(am7model.forms.group.forms).toContain('groupmembers');
        let gm = am7model.forms.groupmembers;
        expect(gm).toBeDefined();
        expect(gm.requiredAttributes).toEqual(['objectId']);
        ['userMembers', 'accountMembers', 'personMembers'].forEach(name => {
            let fv = gm.fields[name];
            expect(fv, name).toBeDefined();
            expect(fv.form).toBe(am7model.forms.groupmember);
        });
        let cmds = am7model.forms.groupmember.commands;
        expect(cmds.new.function).toBe('addMember');
        expect(cmds.new.properties.picker).toBe(true);
        // The participant model comes from the field's baseModel, not an entity attribute.
        expect(cmds.new.properties.typeAttribute).toBeUndefined();
        expect(cmds.delete.function).toBe('deleteMember');
    });

    it('auth.role.members is untouched ($flex + foreignType:type)', () => {
        let f = am7model.getModelFields('auth.role').filter(x => x.name === 'members')[0];
        expect(f.baseModel).toBe('$flex');
        expect(f.foreignType).toBe('type');
    });
});

describe('KI-1 membership.objectMembers with a fixed-baseModel field', () => {
    it('loads members via am7client.members(auth.group, objectId, <baseModel>) for each per-type field', async () => {
        const { membership } = await import('../components/membership.js');
        let entity = { schema: 'auth.group', objectId: 'grp-1', type: 'USER' };
        membersCalls = [];
        let out = await membership.objectMembers({ entity }, 'personMembers', groupField('personMembers'));
        expect(membersCalls).toEqual([{ schema: 'auth.group', objectId: 'grp-1', actorType: 'identity.person', start: 0, count: 100 }]);
        expect(out.length).toBe(1);
        expect(out[0].name).toBe('member-one');

        membersCalls = [];
        await membership.objectMembers({ entity }, 'accountMembers', groupField('accountMembers'));
        expect(membersCalls[0].actorType).toBe('identity.account');

        membersCalls = [];
        await membership.objectMembers({ entity }, 'userMembers', groupField('userMembers'));
        expect(membersCalls[0].actorType).toBe('system.user');
    });

    it('still resolves auth.role.members from the role type enum (regression guard for KI-2/KI-3)', async () => {
        const { membership } = await import('../components/membership.js');
        let roleField = am7model.getModelFields('auth.role').filter(x => x.name === 'members')[0];
        membersCalls = [];
        await membership.objectMembers({ entity: { schema: 'auth.role', objectId: 'role-1', type: 'USER' } }, 'members', roleField);
        expect(membersCalls[0].actorType).toBe('system.user');
    });

    it('returns [] without calling the server when neither a type attribute nor a concrete baseModel is available', async () => {
        const { membership } = await import('../components/membership.js');
        membersCalls = [];
        let out = await membership.objectMembers({ entity: { schema: 'auth.group', objectId: 'grp-1' } }, 'x',
            { name: 'x', type: 'list', baseModel: '$flex' });
        expect(out).toEqual([]);
        expect(membersCalls.length).toBe(0);
    });
});
