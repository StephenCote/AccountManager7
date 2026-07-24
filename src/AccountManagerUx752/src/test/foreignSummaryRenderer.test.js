/**
 * KI-19 follow-up — `data.groupExport`'s sourceGroup/archive foreign single-model fields
 * previously displayed literally "[object Object]" (the default renderer just stringifies the
 * nested BaseRecord). format:"foreign-summary" (components/formFieldRenderers.js) fixes this by
 * reading the field's own value via ctx.defVal — NOT ctx.entity/ctx.useEntity, which is what
 * format:"object-link" incorrectly does for a nested field (it resolves to the *containing*
 * record instead, a real bug found live while first fixing KI-19 — see e2e/objectLinkFix.spec.js
 * and aiDocs/KnownIssues.md's KI-19 entry).
 *
 * These are real behavioral checks against the actual returned Mithril vnode tree, not source
 * text greps.
 */
import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';

beforeAll(() => {
    am7model._client = { base: () => 'https://localhost:8443/AccountManagerService7/rest' };
});

function findByTag(vnode, tag) {
    let out = [];
    (function walk(n) {
        if (Array.isArray(n)) { n.forEach(walk); return; }
        if (!n) return;
        if (n.tag === tag) out.push(n);
        if (n.children) walk(n.children);
    })(vnode);
    return out;
}

describe('formFieldRenderers "foreign-summary" — read-only foreign single-model field (KI-19 follow-up)', () => {
    it('renders the foreign record\'s own name and a link built from its own objectId/model — not the container\'s', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');

        // The containing data.groupExport record — must NOT be what the link/label are built from.
        let containerEntity = { schema: 'data.groupExport', objectId: 'container-oid', name: 'Container Should Not Appear' };
        // The field's own foreign value (what sourceGroup/archive actually resolve to).
        let sourceGroup = { schema: 'auth.group', objectId: 'group-oid-123', name: 'My Gallery Group' };

        let out = formFieldRenderers.render('foreign-summary', {
            entity: containerEntity, useEntity: containerEntity, defVal: sourceGroup
        });

        let links = findByTag(out, 'a');
        expect(links.length).toBe(1);
        expect(links[0].attrs.href).toBe('https://localhost:8443/AccountManagerService7/rest/model/auth.group/group-oid-123/full');
        expect(links[0].attrs.href).not.toContain('container-oid');

        // Label text is the second child of the <a> (after the icon span)
        let labelText = links[0].children.find(c => typeof c === 'string' || (c && c.tag === undefined));
        expect(JSON.stringify(out)).toContain('My Gallery Group');
        expect(JSON.stringify(out)).not.toContain('Container Should Not Appear');
    });

    it('renders a placeholder, not "[object Object]" or a broken link, when the field has no value', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let out = formFieldRenderers.render('foreign-summary', { entity: {}, useEntity: {}, defVal: null });

        expect(findByTag(out, 'a').length).toBe(0);
        expect(JSON.stringify(out)).not.toContain('[object Object]');
        expect(JSON.stringify(out)).toContain('(none)');
    });

    it('falls back to objectId as the label when the foreign record has no name', async () => {
        const { formFieldRenderers } = await import('../components/formFieldRenderers.js');
        let out = formFieldRenderers.render('foreign-summary', {
            entity: {}, useEntity: {},
            defVal: { schema: 'data.data', objectId: 'archive-oid-456' }
        });

        let links = findByTag(out, 'a');
        expect(links.length).toBe(1);
        expect(links[0].attrs.href).toContain('/model/data.data/archive-oid-456/full');
        expect(JSON.stringify(out)).toContain('archive-oid-456');
    });
});
