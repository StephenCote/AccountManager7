// @vitest-environment jsdom
/**
 * Admin panel (/admin/system): the feature-local client's request shapes against /rest/admin, the audit
 * search body the view builds from its filter state, and the feature wiring/manifest entry.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

describe('systemAdminClient request shapes', () => {
    it('sends the scope boolean, never an organization id, and keeps purge an explicit flag', async () => {
        let m = (await import('mithril')).default;
        let seen = [];
        let orig = m.request;
        m.request = (opts) => { seen.push(opts); return Promise.resolve({}); };
        try {
            let { systemAdminClient } = await import('../features/systemAdmin/systemAdminClient.js');
            await systemAdminClient.scope();
            await systemAdminClient.countThumbnails(false);
            await systemAdminClient.cleanupThumbnails(true);
            await systemAdminClient.countAudit(true);
            await systemAdminClient.searchAudit({ allOrganizations: false, text: 'x', recordCount: 10 });
            await systemAdminClient.getAudit('abc-123', true);
            await systemAdminClient.trimAudit(false, 30);
            await systemAdminClient.purgeAudit(true);
        } finally {
            m.request = orig;
        }
        expect(seen.map(s => s.method)).toEqual(['GET', 'GET', 'DELETE', 'GET', 'POST', 'GET', 'DELETE', 'DELETE']);
        expect(seen[0].url).toMatch(/\/rest\/admin\/scope$/);
        expect(seen[1].url).toMatch(/\/rest\/admin\/thumbnails\/count\?allOrganizations=false$/);
        expect(seen[2].url).toMatch(/\/rest\/admin\/thumbnails\?allOrganizations=true$/);
        expect(seen[3].url).toMatch(/\/rest\/admin\/audit\/count\?allOrganizations=true$/);
        expect(seen[4].url).toMatch(/\/rest\/admin\/audit\/search$/);
        expect(seen[4].body).toEqual({ allOrganizations: false, text: 'x', recordCount: 10 });
        expect(seen[5].url).toMatch(/\/rest\/admin\/audit\/abc-123\?allOrganizations=true$/);
        expect(seen[6].url).toMatch(/\/rest\/admin\/audit\?allOrganizations=false&days=30$/);
        expect(seen[7].url).toMatch(/\/rest\/admin\/audit\?allOrganizations=true&purge=true$/);
        // Trim never carries purge, purge never carries days: omitting a parameter is never the destructive case.
        expect(seen[6].url).not.toMatch(/purge/);
        expect(seen[7].url).not.toMatch(/days/);
        for (let s of seen) {
            expect(s.withCredentials).toBe(true);
            expect(s.url).not.toMatch(/organizationId/);
        }
    });
});

describe('buildAuditSearch', () => {
    it('drops blank filters, trims text, and turns date inputs into epoch-millis day bounds', async () => {
        let { buildAuditSearch } = await import('../features/systemAdmin/systemAdminView.js');
        let state = { filter: { text: '  hello ', action: '', response: 'DENY', resourceType: ' ', since: '2026-01-02', until: '' }, pageSize: 50, startRecord: 100 };
        let body = buildAuditSearch(state, true);
        expect(body.allOrganizations).toBe(true);
        expect(body.startRecord).toBe(100);
        expect(body.recordCount).toBe(50);
        expect(body.text).toBe('hello');
        expect(body.response).toBe('DENY');
        expect(body).not.toHaveProperty('action');
        expect(body).not.toHaveProperty('resourceType');
        expect(body).not.toHaveProperty('until');
        expect(body.since).toBe(new Date('2026-01-02T00:00:00').getTime());

        let empty = buildAuditSearch({ filter: { text: '', action: '', response: '', resourceType: '', since: '', until: '2026-01-02' }, pageSize: 25, startRecord: 0 }, false);
        expect(empty).toEqual({ allOrganizations: false, startRecord: 0, recordCount: 25, until: new Date('2026-01-02T23:59:59.999').getTime() });
    });

    it('renders an audit detail with resolved references and a missing-target marker', async () => {
        let m = (await import('mithril')).default;
        let { renderAuditDetail } = await import('../features/systemAdmin/systemAdminView.js');
        let root = document.createElement('div');
        m.render(root, renderAuditDetail({
            audit: { objectId: 'o-1', createdDate: 1700000000000, action: 'read', response: 'permit', description: 'PERMIT x to READ y', message: 'msg', query: '{"k":1}' },
            organizationPath: '/Development',
            contextUser: { schema: 'system.user', id: 7, name: 'alice', found: true },
            subject: { schema: 'system.user', id: 7, name: 'alice', found: true },
            resource: { schema: 'data.data', id: 99, found: false }
        }));
        let text = root.textContent;
        expect(text).toContain('o-1');
        expect(text).toContain('/Development');
        expect(text).toContain('PERMIT');
        expect(text).toContain('READ');
        expect(text).toContain('system.user #7 "alice"');
        expect(text).toContain('data.data #99 (no longer exists)');
        expect(text).toContain('{"k":1}');
    });
});

describe('Admin panel wiring (part of core)', () => {
    it("core carries the adminOnly aside item at /admin/system, so every organization's admins get it", async () => {
        let { features, isMenuItemVisible, initFeatures, getMenuItems } = await import('../features.js');
        let core = features.core;
        expect(core.required).toBe(true);
        expect(typeof core.routes).toBe('function');
        expect(core.routePrefixes).toEqual(['/admin/system']);
        expect(core.menuItems.length).toBe(1);
        let mi = core.menuItems[0];
        expect(mi.route).toBe('/admin/system');
        expect(mi.section).toBe('aside');
        expect(mi.adminOnly).toBe(true);
        expect(isMenuItemVisible(mi, { roles: { admin: false } })).toBe(false);
        expect(isMenuItemVisible(mi, { roles: { admin: true } })).toBe(true);

        // A stored MINIMAL set (the case that hid the panel when it was its own feature id) still offers it.
        initFeatures('minimal');
        expect(getMenuItems('aside').some(i => i.route === '/admin/system')).toBe(true);

        let routes = (await import('../features/systemAdmin/routes.js')).routes;
        expect(Object.keys(routes)).toEqual(['/admin/system']);
    });

    it('is not a manifest feature id (it cannot be toggled off per organization)', () => {
        let ux = readFileSync(resolve(here, '../features.manifest.json'));
        let obj = readFileSync(resolve(here, '../../../AccountManagerObjects7/src/main/resources/features/uxFeatureManifest.json'));
        expect(Buffer.compare(ux, obj)).toBe(0);
        let ids = JSON.parse(ux.toString('utf-8')).map(e => e.id);
        expect(ids).not.toContain('systemAdmin');
    });
});
