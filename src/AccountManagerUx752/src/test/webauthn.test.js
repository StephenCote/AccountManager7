import { describe, it, expect, vi, beforeEach } from 'vitest';
import { features, isEnabled, initFeatures, getMenuItems, getEnabledFeatures } from '../features.js';

describe('WebAuthn Feature', () => {

    beforeEach(() => {
        initFeatures('minimal');
    });

    it('webauthn feature is defined in features manifest', () => {
        expect(features.webauthn).toBeDefined();
        expect(features.webauthn.id).toBe('webauthn');
        expect(features.webauthn.label).toBe('Passkeys');
        expect(features.webauthn.deps).toContain('core');
    });

    it('webauthn feature has correct menu item', () => {
        initFeatures(['core', 'webauthn']);
        let asideItems = getMenuItems('aside');
        let passkeyItem = asideItems.find(mi => mi.label === 'Passkeys');
        expect(passkeyItem).toBeDefined();
        expect(passkeyItem.icon).toBe('passkey');
        expect(passkeyItem.route).toBe('/webauthn');
        expect(passkeyItem.section).toBe('aside');
    });

    it('webauthn is enabled in full profile', () => {
        initFeatures('full');
        expect(isEnabled('webauthn')).toBe(true);
    });

    it('webauthn is enabled in enterprise profile', () => {
        initFeatures('enterprise');
        expect(isEnabled('webauthn')).toBe(true);
    });

    it('webauthn can be individually enabled', () => {
        expect(isEnabled('webauthn')).toBe(false);
        initFeatures(['core', 'webauthn']);
        expect(isEnabled('webauthn')).toBe(true);
        expect(isEnabled('core')).toBe(true);
    });

    it('webauthn has lazy-loaded routes', () => {
        expect(typeof features.webauthn.routes).toBe('function');
    });
});

describe('WebAuthn Client API', () => {

    it('am7client exports webauthn methods', async () => {
        // Dynamic import to avoid Mithril DOM dependency issues in test
        const mod = await import('../core/am7client.js');
        const { am7client } = mod;
        expect(typeof am7client.webauthnSupported).toBe('function');
        expect(typeof am7client.webauthnRegister).toBe('function');
        expect(typeof am7client.webauthnAuthenticate).toBe('function');
        expect(typeof am7client.webauthnListCredentials).toBe('function');
        expect(typeof am7client.webauthnDeleteCredential).toBe('function');
    });
});
