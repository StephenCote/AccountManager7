import { describe, it, expect, vi, beforeEach } from 'vitest';
import { features, isEnabled, initFeatures, getMenuItems, getEnabledFeatures } from '../features.js';

describe('Access Requests Feature', () => {

    beforeEach(() => {
        initFeatures('minimal');
    });

    it('accessRequests feature is defined in features manifest', () => {
        expect(features.accessRequests).toBeDefined();
        expect(features.accessRequests.id).toBe('accessRequests');
        expect(features.accessRequests.label).toBe('Access Requests');
        expect(features.accessRequests.deps).toContain('core');
    });

    it('accessRequests feature has correct menu item', () => {
        initFeatures(['core', 'accessRequests']);
        let asideItems = getMenuItems('aside');
        let arItem = asideItems.find(mi => mi.label === 'Access Requests');
        expect(arItem).toBeDefined();
        expect(arItem.icon).toBe('switch_access_shortcut');
        expect(arItem.route).toBe('/accessRequests');
        expect(arItem.section).toBe('aside');
    });

    it('accessRequests is enabled in full profile', () => {
        initFeatures('full');
        expect(isEnabled('accessRequests')).toBe(true);
    });

    it('accessRequests is enabled in enterprise profile', () => {
        initFeatures('enterprise');
        expect(isEnabled('accessRequests')).toBe(true);
    });

    it('accessRequests can be individually enabled', () => {
        expect(isEnabled('accessRequests')).toBe(false);
        initFeatures(['core', 'accessRequests']);
        expect(isEnabled('accessRequests')).toBe(true);
        expect(isEnabled('core')).toBe(true);
    });

    it('accessRequests has lazy-loaded routes', () => {
        expect(typeof features.accessRequests.routes).toBe('function');
    });

    it('accessRequests is not enabled in minimal profile', () => {
        initFeatures('minimal');
        expect(isEnabled('accessRequests')).toBe(false);
    });
});

describe('Access Request Client API', () => {

    it('am7client exports access request methods', async () => {
        const mod = await import('../core/am7client.js');
        const { am7client } = mod;
        expect(typeof am7client.accessRequestList).toBe('function');
        expect(typeof am7client.accessRequestSubmit).toBe('function');
        expect(typeof am7client.accessRequestUpdate).toBe('function');
        expect(typeof am7client.accessRequestableList).toBe('function');
        expect(typeof am7client.accessRequestNotify).toBe('function');
    });
});
