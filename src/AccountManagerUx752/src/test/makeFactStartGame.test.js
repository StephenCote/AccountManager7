/**
 * views/object.js command handlers that were "not yet implemented" toast stubs until 2026-10-07:
 * makeFact (data/user/person/account/role/permission/group forms) and startGameWithCharacter
 * (charPerson form). Both are ports of the Ux7 reference — see the module headers for the exact
 * origin lines and the two documented departures.
 *
 * Real am7model + formDef + view.js are used (newPrimitive('policy.fact') and am7view.path() are
 * the pieces under test); only the browser seams are mocked: mithril's router, pageClient (toast,
 * makePath, context), the dialog, and the adopt workflow.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';

const { mockRouteSet, mockToast, mockMakePath, mockConfirm, mockAdopt, ctx } = vi.hoisted(() => ({
    mockRouteSet: vi.fn(),
    mockToast: vi.fn(),
    mockMakePath: vi.fn(),
    mockConfirm: vi.fn(),
    mockAdopt: vi.fn(),
    ctx: { pendingEntity: undefined }
}));

vi.mock('mithril', () => ({
    default: Object.assign(
        (tag, attrs, children) => ({ tag, attrs, children }),
        { redraw: vi.fn(), route: { set: mockRouteSet, get: () => '/' }, trust: (s) => s }
    )
}));

vi.mock('../core/pageClient.js', () => ({
    page: {
        toast: mockToast,
        makePath: mockMakePath,
        context: () => ctx
    }
}));

vi.mock('../components/dialogCore.js', () => ({
    Dialog: { confirm: mockConfirm, open: vi.fn(), close: vi.fn() }
}));

vi.mock('../workflows/adoptCharacter.js', () => ({
    adoptCharacter: mockAdopt,
    default: mockAdopt
}));

import { am7model } from '../core/model.js';
import '../core/formDef.js';

let makeFact, buildFact, startGameWithCharacter, isInWorld, SELECTED_CHARACTER_KEY, CARD_GAME_ROUTE;

beforeAll(async () => {
    am7model._view = { path: () => '', pathForType: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
    ({ makeFact, buildFact } = await import('../workflows/makeFact.js'));
    ({ startGameWithCharacter, isInWorld, SELECTED_CHARACTER_KEY, CARD_GAME_ROUTE } = await import('../workflows/startGameWithCharacter.js'));
});

beforeEach(() => {
    vi.clearAllMocks();
    ctx.pendingEntity = undefined;
    mockMakePath.mockResolvedValue({ objectId: 'FACTS-GROUP-OID', id: 42, path: '/home/tester/Facts' });
    globalThis.sessionStorage = {
        _s: {},
        setItem(k, v) { this._s[k] = String(v); },
        getItem(k) { return k in this._s ? this._s[k] : null; },
        removeItem(k) { delete this._s[k]; }
    };
});

const group = {
    schema: 'auth.group', objectId: 'G1', urn: 'am:group:tester:Data', name: 'Data',
    type: 'DATA', path: '/home/tester/Data', organizationPath: '/Development'
};
const role = {
    schema: 'auth.role', objectId: 'R1', urn: 'am:role:tester:Readers', name: 'Readers',
    type: 'USER', path: '/home/tester/Roles/Readers', organizationPath: '/Development'
};
const permission = {
    schema: 'auth.permission', objectId: 'P1', urn: 'am:permission:tester:Read', name: 'Read',
    type: 'USER', path: '/home/tester/Permissions/Read', organizationPath: '/Development'
};
const person = {
    schema: 'identity.person', objectId: 'PE1', urn: 'am:person:tester:Alice', name: 'Alice',
    organizationPath: '/Development'
};

describe('makeFact — buildFact maps the record onto the AM7 policy.fact model', () => {
    it('is a real policy.fact primitive carrying every field the backend resolves from', () => {
        let f = buildFact(person);
        expect(f[am7model.jsonModelKey]).toBe('policy.fact');
        expect(f.type).toBe('FACTORY');
        expect(f.modelType).toBe('identity.person');
        expect(f.sourceUrn).toBe('am:person:tester:Alice');
        expect(f.sourceType).toBe('unknown');
        expect(f.name).toBe('Alice Fact');
        expect(f.description).toContain('am:person:tester:Alice');
        expect(f.description).toContain('/Development');
        // The Ux7 text wrote these two; neither exists on the AM7 model (factType is deprecated,
        // factoryType is not a field), so they must not leak into the primitive.
        expect('factoryType' in f).toBe(false);
        expect(f.factType == null || f.factType === '').toBe(true);
    });

    it('a role becomes a ROLE fact resolvable by path (sourceUrl + sourceType)', () => {
        let f = buildFact(role);
        expect(f.type).toBe('ROLE');
        expect(f.sourceType).toBe('USER');
        expect(f.sourceUrl).toBe('/home/tester/Roles/Readers');
        expect(f.modelType).toBe('auth.role');
    });

    it('a permission becomes a PERMISSION fact; a group stays FACTORY with its path', () => {
        expect(buildFact(permission).type).toBe('PERMISSION');
        expect(buildFact(permission).sourceUrl).toBe('/home/tester/Permissions/Read');
        let g = buildFact(group);
        expect(g.type).toBe('FACTORY');
        expect(g.sourceType).toBe('DATA');
        expect(g.sourceUrl).toBe('/home/tester/Data');
    });

    it('every value it sets is a declared field of the client policy.fact model (enum values included)', () => {
        let names = new Set(am7model.getModelFields('policy.fact').map(f => f.name));
        let f = buildFact(role);
        Object.keys(f).filter(k => k !== am7model.jsonModelKey && f[k] != null && f[k] !== '').forEach(k => {
            expect(names.has(k), k + ' is not a policy.fact field').toBe(true);
        });
        ['FACTORY', 'ROLE', 'PERMISSION'].forEach(v => expect(am7model.enums.factEnumType).toContain(v));
    });
});

describe('makeFact — the command handler (entity, inst, cmd)', () => {
    it('resolves ~/Facts with create-if-missing, parks the fact on pendingEntity and routes to /new/policy.fact/<group>', async () => {
        let ok = await makeFact(group, { entity: group }, {});
        expect(ok).toBe(true);
        expect(mockMakePath).toHaveBeenCalledWith('auth.group', 'DATA', '~/Facts');
        expect(ctx.pendingEntity).toBeTruthy();
        expect(ctx.pendingEntity[am7model.jsonModelKey]).toBe('policy.fact');
        expect(ctx.pendingEntity.sourceUrn).toBe(group.urn);
        expect(mockRouteSet).toHaveBeenCalledTimes(1);
        let [route, params] = mockRouteSet.mock.calls[0];
        expect(route).toBe('/new/policy.fact/FACTS-GROUP-OID');
        expect(typeof params.key).toBe('number');
    });

    it('refuses an unsupported model with an error toast and no navigation', async () => {
        let ok = await makeFact({ schema: 'olio.charPerson', urn: 'am:x', name: 'x' }, {}, {});
        expect(ok).toBe(false);
        expect(mockToast).toHaveBeenCalledWith('error', expect.stringContaining('olio.charPerson'));
        expect(mockRouteSet).not.toHaveBeenCalled();
        expect(ctx.pendingEntity).toBeUndefined();
    });

    it('refuses an unsaved record (no urn) — the toolbar also greys the button via requiredAttribute objectId', async () => {
        let ok = await makeFact({ schema: 'auth.group', name: 'unsaved' }, {}, {});
        expect(ok).toBe(false);
        expect(mockRouteSet).not.toHaveBeenCalled();
    });

    it('reports a Facts-directory failure instead of routing to a dead /new page', async () => {
        mockMakePath.mockResolvedValue(null);
        let ok = await makeFact(group, { entity: group }, {});
        expect(ok).toBe(false);
        expect(mockToast).toHaveBeenCalledWith('error', expect.stringContaining('Facts'));
        expect(mockRouteSet).not.toHaveBeenCalled();
    });

    it('formDef wires makeFact on exactly the forms Ux7 did', () => {
        let withFact = Object.keys(am7model.forms).filter(k => {
            let c = am7model.forms[k] && am7model.forms[k].commands;
            return c && Object.values(c).some(cmd => cmd.function === 'makeFact');
        }).sort();
        expect(withFact).toEqual(['account', 'data', 'group', 'permission', 'person', 'role', 'user']);
    });
});

describe('startGameWithCharacter — Ux7 dialog.js:1805 port', () => {
    const inWorld = { schema: 'olio.charPerson', objectId: 'C1', name: 'Rhea', groupPath: '/home/tester/World Building/Earth/Population' };
    const outside = { schema: 'olio.charPerson', objectId: 'C2', name: 'Loose', groupPath: '/home/tester/Characters' };

    it('isInWorld is decided by the group path, as in Ux7', () => {
        expect(isInWorld(inWorld)).toBe(true);
        expect(isInWorld({ groupPath: '/x/Worlds/y' })).toBe(true);
        expect(isInWorld({ groupPath: '/x/Population' })).toBe(true);
        expect(isInWorld(outside)).toBe(false);
        expect(isInWorld({})).toBe(false);
        expect(isInWorld(null)).toBe(false);
    });

    it('a world character: writes the sessionStorage hand-off and routes to the card game with the id', async () => {
        let ok = await startGameWithCharacter(inWorld, { entity: inWorld }, {});
        expect(ok).toBe(true);
        expect(sessionStorage.getItem(SELECTED_CHARACTER_KEY)).toBe('C1');
        expect(mockRouteSet).toHaveBeenCalledWith(CARD_GAME_ROUTE, { character: 'C1' });
        expect(CARD_GAME_ROUTE).toBe('/cardGame');
        expect(mockToast).toHaveBeenCalledWith('info', 'Starting game with Rhea');
        expect(mockConfirm).not.toHaveBeenCalled();
    });

    it('a character outside the world: prompts, and on "Adopt Character" runs the adopt workflow instead of routing', async () => {
        mockConfirm.mockResolvedValue(true);
        let ok = await startGameWithCharacter(outside, { entity: outside }, {});
        expect(ok).toBe(false);
        expect(mockConfirm).toHaveBeenCalledWith(expect.objectContaining({
            title: 'Character Not in World', confirmLabel: 'Adopt Character'
        }));
        expect(mockAdopt).toHaveBeenCalledWith(outside, { entity: outside });
        expect(mockRouteSet).not.toHaveBeenCalled();
        expect(sessionStorage.getItem(SELECTED_CHARACTER_KEY)).toBeNull();
    });

    it('a character outside the world: cancelling the prompt does nothing', async () => {
        mockConfirm.mockResolvedValue(false);
        let ok = await startGameWithCharacter(outside, { entity: outside }, {});
        expect(ok).toBe(false);
        expect(mockAdopt).not.toHaveBeenCalled();
        expect(mockRouteSet).not.toHaveBeenCalled();
    });

    it('no instance: error toast, nothing else', async () => {
        let ok = await startGameWithCharacter(null, null, {});
        expect(ok).toBe(false);
        expect(mockToast).toHaveBeenCalledWith('error', 'No character selected');
        expect(mockRouteSet).not.toHaveBeenCalled();
    });

    it('the charPerson form is the one form wired to it, and the card game route exists in the feature wiring', async () => {
        let wired = Object.keys(am7model.forms).filter(k => {
            let c = am7model.forms[k] && am7model.forms[k].commands;
            return c && Object.values(c).some(cmd => cmd.function === 'startGameWithCharacter');
        });
        expect(wired).toEqual(['charPerson']);
        const { features } = await import('../features.js');
        expect(features.cardGame.routePrefixes).toContain(CARD_GAME_ROUTE);
    });
});
