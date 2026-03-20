import { describe, it, expect, beforeEach } from 'vitest';
import {
    features,
    profiles,
    isEnabled,
    enableFeature,
    disableFeature,
    initFeatures,
    getMenuItems,
    getEnabledFeatures
} from '../features.js';

describe('Feature Config Integration', () => {

    beforeEach(() => {
        initFeatures('minimal');
    });

    it('featureConfig exists in feature manifest', () => {
        expect(features.featureConfig).toBeDefined();
        expect(features.featureConfig.id).toBe('featureConfig');
        expect(features.featureConfig.label).toBe('Feature Config');
        expect(features.featureConfig.deps).toContain('core');
        expect(features.featureConfig.required).toBe(false);
    });

    it('featureConfig has admin-only aside menu item', () => {
        expect(features.featureConfig.menuItems.length).toBe(1);
        let mi = features.featureConfig.menuItems[0];
        expect(mi.route).toBe('/admin/features');
        expect(mi.section).toBe('aside');
        expect(mi.adminOnly).toBe(true);
        expect(mi.icon).toBe('tune');
    });

    it('featureConfig is included in full profile', () => {
        initFeatures('full');
        expect(isEnabled('featureConfig')).toBe(true);
    });

    it('featureConfig is included in enterprise profile', () => {
        initFeatures('enterprise');
        expect(isEnabled('featureConfig')).toBe(true);
    });

    it('featureConfig is not in minimal profile', () => {
        initFeatures('minimal');
        expect(isEnabled('featureConfig')).toBe(false);
    });

    it('featureConfig can be enabled independently', () => {
        let result = enableFeature('featureConfig');
        expect(result).toBe(true);
        expect(isEnabled('featureConfig')).toBe(true);
        expect(isEnabled('core')).toBe(true); // dep auto-enabled
    });

    it('featureConfig can be disabled when no dependents', () => {
        initFeatures('full');
        let result = disableFeature('featureConfig');
        expect(result).toBe(true);
        expect(isEnabled('featureConfig')).toBe(false);
    });

    it('featureConfig appears in aside menu when enabled', () => {
        initFeatures('full');
        let asideItems = getMenuItems('aside');
        let configItem = asideItems.find(mi => mi.label === 'Features');
        expect(configItem).toBeDefined();
        expect(configItem.route).toBe('/admin/features');
    });

    it('featureConfig does not appear in aside menu when disabled', () => {
        initFeatures('minimal');
        let asideItems = getMenuItems('aside');
        let configItem = asideItems.find(mi => mi.label === 'Features');
        expect(configItem).toBeUndefined();
    });

    it('initFeatures accepts server feature array', () => {
        // Simulates what router.js does with server config
        let serverFeatures = ['core', 'chat', 'schema', 'featureConfig'];
        initFeatures(serverFeatures);
        expect(isEnabled('core')).toBe(true);
        expect(isEnabled('chat')).toBe(true);
        expect(isEnabled('schema')).toBe(true);
        expect(isEnabled('featureConfig')).toBe(true);
        expect(isEnabled('cardGame')).toBe(false);
        expect(isEnabled('games')).toBe(false);
    });

    it('profiles.full includes featureConfig', () => {
        expect(profiles.full).toContain('featureConfig');
    });

    it('profiles.enterprise includes featureConfig', () => {
        expect(profiles.enterprise).toContain('featureConfig');
    });
});

describe('Feature Config Dependency Validation', () => {

    beforeEach(() => {
        initFeatures('full');
    });

    it('disabling core is blocked (required)', () => {
        let result = disableFeature('core');
        expect(result).toBe(false);
    });

    it('disabling chat is blocked when cardGame is enabled', () => {
        // cardGame depends on chat
        expect(isEnabled('cardGame')).toBe(true);
        let result = disableFeature('chat');
        expect(result).toBe(false);
    });

    it('disabling chat succeeds after disabling its dependents', () => {
        // Disable all features that depend on chat
        disableFeature('cardGame');
        disableFeature('iso42001');
        disableFeature('pictureBook');
        let result = disableFeature('chat');
        expect(result).toBe(true);
        expect(isEnabled('chat')).toBe(false);
    });

    it('enabling cardGame auto-enables chat dependency', () => {
        initFeatures('minimal');
        enableFeature('cardGame');
        expect(isEnabled('chat')).toBe(true);
        expect(isEnabled('core')).toBe(true);
    });

    it('getEnabledFeatures returns array of enabled feature ids', () => {
        initFeatures(['core', 'featureConfig']);
        let enabled = getEnabledFeatures();
        expect(enabled).toContain('core');
        expect(enabled).toContain('featureConfig');
        expect(enabled.length).toBe(2);
    });
});
