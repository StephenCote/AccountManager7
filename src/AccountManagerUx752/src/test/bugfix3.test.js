/**
 * Bug Fix Sprint #3 — Tests for Issues #31 and #33
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

describe('Issue #33: Clear Cache / Cleanup toast', () => {

    it('asideMenu exports and has a view function', async () => {
        const mod = await import('../components/asideMenu.js');
        expect(mod.asideMenu).toBeDefined();
        expect(typeof mod.asideMenu.view).toBe('function');
    });

    it('page.toast is a function', async () => {
        const mod = await import('../core/pageClient.js');
        expect(typeof mod.page.toast).toBe('function');
    });

    it('asideMenu closes drawer before toast for Clear Cache', () => {
        const src = readFileSync(resolve(__dirname, '..', 'components', 'asideMenu.js'), 'utf-8');
        // Find the Clear Cache onclick handler — should close drawer then toast
        let cacheIdx = src.indexOf('"Clear Cache"');
        expect(cacheIdx).toBeGreaterThan(-1);
        // Get the onclick handler block before "Clear Cache" text
        let handlerBlock = src.substring(Math.max(0, cacheIdx - 800), cacheIdx);
        expect(handlerBlock).toContain('navigable');
        expect(handlerBlock).toContain('drawer(true)');
        expect(handlerBlock).toContain('clearCache');
        expect(handlerBlock).toContain('page.toast');
    });

    it('asideMenu closes drawer before toast for Cleanup', () => {
        const src = readFileSync(resolve(__dirname, '..', 'components', 'asideMenu.js'), 'utf-8');
        let cleanupIdx = src.indexOf('"Cleanup"');
        expect(cleanupIdx).toBeGreaterThan(-1);
        let handlerBlock = src.substring(Math.max(0, cleanupIdx - 800), cleanupIdx);
        expect(handlerBlock).toContain('navigable');
        expect(handlerBlock).toContain('drawer(true)');
        expect(handlerBlock).toContain('cleanup');
        expect(handlerBlock).toContain('page.toast');
    });
});

describe('Issue #31: 401 redirect to login', () => {

    it('am7client source contains _handle401 function', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'am7client.js'), 'utf-8');
        expect(src).toContain('function _handle401');
        expect(src).toContain('e.code === 401');
        expect(src).toContain('am7.returnRoute');
        expect(src).toContain('m.route.set("/sig")');
    });

    it('_handle401 is called in get, post, patch, del catch handlers', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'am7client.js'), 'utf-8');
        let matches = src.match(/_handle401/g);
        expect(matches).not.toBeNull();
        // 1 definition + 4 calls (get, post, patch, del)
        expect(matches.length).toBeGreaterThanOrEqual(5);
    });

    it('router.js reads am7.returnRoute from sessionStorage on refresh', () => {
        const src = readFileSync(resolve(__dirname, '..', 'router.js'), 'utf-8');
        expect(src).toContain('am7.returnRoute');
        expect(src).toContain('sessionStorage.getItem');
        expect(src).toContain('sessionStorage.removeItem');
    });

    it('patch function now has a catch handler with 401 check', () => {
        const src = readFileSync(resolve(__dirname, '..', 'core', 'am7client.js'), 'utf-8');
        // Find the patch function and verify it has a .catch() handler
        let patchIdx = src.indexOf('function patch(url, data, fH)');
        expect(patchIdx).toBeGreaterThan(-1);
        let patchBlock = src.substring(patchIdx, patchIdx + 400);
        expect(patchBlock).toContain('.catch(');
        expect(patchBlock).toContain('_handle401');
    });
});
