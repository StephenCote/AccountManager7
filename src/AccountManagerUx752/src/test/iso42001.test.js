import { describe, it, expect, beforeEach } from 'vitest';
import {
    features,
    profiles,
    isEnabled,
    enableFeature,
    initFeatures,
    getMenuItems
} from '../features.js';
import { am7model } from '../core/modelDef.js';

/**
 * Phase 8 — ISO 42001 feature gating + the `compliance` deploy profile (design §9.2/§9.3).
 * The compliance appliance ships only core + chat + iso42001 + accessRequests + featureConfig;
 * everything else stays disabled and hidden.
 */
describe('ISO 42001 feature + compliance profile', () => {

    beforeEach(() => {
        initFeatures('minimal');
    });

    it('iso42001 feature manifest metadata', () => {
        expect(features.iso42001).toBeDefined();
        expect(features.iso42001.id).toBe('iso42001');
        expect(features.iso42001.required).toBe(false);
        // chat is required — the bias suite runs LLM tests through LLMConnector
        expect(features.iso42001.deps).toContain('core');
        expect(features.iso42001.deps).toContain('chat');
    });

    it('compliance profile exists with the §9.2 feature set', () => {
        expect(profiles.compliance).toBeDefined();
        expect(profiles.compliance).toEqual(['core', 'chat', 'iso42001', 'accessRequests', 'featureConfig']);
    });

    it('compliance profile enables iso42001 + its onboarding/admin features, and nothing else', () => {
        initFeatures('compliance');
        expect(isEnabled('iso42001')).toBe(true);
        expect(isEnabled('chat')).toBe(true);
        expect(isEnabled('accessRequests')).toBe(true);
        expect(isEnabled('featureConfig')).toBe(true);
        // carve-out: unrelated surfaces stay disabled
        expect(isEnabled('cardGame')).toBe(false);
        expect(isEnabled('games')).toBe(false);
        expect(isEnabled('biometrics')).toBe(false);
        expect(isEnabled('media')).toBe(false);
        expect(isEnabled('schema')).toBe(false);
        expect(isEnabled('pictureBook')).toBe(false);
    });

    it('enabling iso42001 auto-enables its chat dependency', () => {
        initFeatures('minimal');
        enableFeature('iso42001');
        expect(isEnabled('iso42001')).toBe(true);
        expect(isEnabled('chat')).toBe(true);
    });

    it('compliance profile surfaces the Compliance aside menu item', () => {
        initFeatures('compliance');
        let asideItems = getMenuItems('aside');
        expect(asideItems.some(mi => mi.label === 'Compliance' && mi.route === '/compliance')).toBe(true);
    });

    it('iso42001 menu item is hidden when the feature is disabled', () => {
        initFeatures('standard'); // no iso42001
        let asideItems = getMenuItems('aside');
        expect(asideItems.some(mi => mi.label === 'Compliance')).toBe(false);
    });

    it('all 8 iso42001 models are registered in modelDef.js with fields', () => {
        let expected = [
            'iso42001.testConfig', 'iso42001.testRun', 'iso42001.testResult', 'iso42001.report',
            'iso42001.reportSection', 'iso42001.certification', 'iso42001.certificationRequest',
            'iso42001.analysisProfile'
        ];
        let byName = {};
        (am7model.models || []).forEach(m => { if (m && m.name) byName[m.name] = m; });
        for (let name of expected) {
            expect(byName[name], name + ' must be present in modelDef.js').toBeTruthy();
            expect(Array.isArray(byName[name].fields), name + ' must carry a fields array').toBe(true);
        }
    });
});
