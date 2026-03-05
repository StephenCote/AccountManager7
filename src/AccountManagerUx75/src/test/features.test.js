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

describe('Feature System', () => {

    beforeEach(() => {
        initFeatures('minimal');
    });

    it('minimal profile enables only core', () => {
        expect(isEnabled('core')).toBe(true);
        expect(isEnabled('chat')).toBe(false);
        expect(isEnabled('cardGame')).toBe(false);
        expect(getEnabledFeatures()).toEqual(['core']);
    });

    it('full profile enables all features', () => {
        initFeatures('full');
        let all = Object.keys(features);
        for (let id of all) {
            expect(isEnabled(id)).toBe(true);
        }
    });

    it('standard profile enables core and chat', () => {
        initFeatures('standard');
        expect(isEnabled('core')).toBe(true);
        expect(isEnabled('chat')).toBe(true);
        expect(isEnabled('cardGame')).toBe(false);
    });

    it('enableFeature auto-enables dependencies', () => {
        enableFeature('cardGame');
        expect(isEnabled('core')).toBe(true);
        expect(isEnabled('chat')).toBe(true);
        expect(isEnabled('cardGame')).toBe(true);
    });

    it('disableFeature refuses for required features', () => {
        let result = disableFeature('core');
        expect(result).toBe(false);
        expect(isEnabled('core')).toBe(true);
    });

    it('disableFeature refuses when other features depend on it', () => {
        initFeatures('full');
        // chat is depended on by cardGame and iso42001
        let result = disableFeature('chat');
        expect(result).toBe(false);
        expect(isEnabled('chat')).toBe(true);
    });

    it('disableFeature succeeds for leaf features', () => {
        initFeatures('full');
        let result = disableFeature('biometrics');
        expect(result).toBe(true);
        expect(isEnabled('biometrics')).toBe(false);
    });

    it('initFeatures accepts an array of feature ids', () => {
        initFeatures(['core', 'games']);
        expect(isEnabled('core')).toBe(true);
        expect(isEnabled('games')).toBe(true);
        expect(isEnabled('chat')).toBe(false);
    });

    it('getMenuItems returns items for enabled features only', () => {
        initFeatures('minimal');
        let topItems = getMenuItems('top');
        expect(topItems.length).toBe(0); // core has no menu items

        initFeatures('full');
        let allTopItems = getMenuItems('top');
        expect(allTopItems.length).toBeGreaterThan(0);
        // Verify chat button is in the list
        expect(allTopItems.some(mi => mi.label === 'Chat')).toBe(true);
    });

    it('getMenuItems filters by section', () => {
        initFeatures('full');
        let topItems = getMenuItems('top');
        let asideItems = getMenuItems('aside');
        // Compliance is aside, chat is top
        expect(asideItems.some(mi => mi.label === 'Compliance')).toBe(true);
        expect(topItems.some(mi => mi.label === 'Compliance')).toBe(false);
    });

    it('profiles object contains expected profiles', () => {
        expect(profiles.minimal).toContain('core');
        expect(profiles.full.length).toBe(Object.keys(features).length);
        expect(profiles.enterprise).toContain('iso42001');
    });

    it('enableFeature returns false for unknown features', () => {
        let result = enableFeature('nonexistent');
        expect(result).toBe(false);
    });
});
