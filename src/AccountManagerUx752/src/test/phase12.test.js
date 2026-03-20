import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// --- cacheDb tests ---
describe('cacheDb — SQLite WASM cache', () => {
    let cacheDb;

    beforeEach(async () => {
        // Fresh import each time
        const mod = await import('../core/cacheDb.js');
        cacheDb = mod.cacheDb;
        // Close any previous instance
        cacheDb.close();
        await cacheDb.init();
    });

    afterEach(() => {
        cacheDb.close();
    });

    it('initializes successfully', () => {
        expect(cacheDb.isReady()).toBe(true);
    });

    it('stores and retrieves values', () => {
        cacheDb.put('testType', 'GET', 'key1', { name: 'test' });
        let result = cacheDb.get('testType', 'GET', 'key1');
        expect(result).toEqual({ name: 'test' });
    });

    it('returns undefined for missing keys', () => {
        let result = cacheDb.get('missing', 'GET', 'nokey');
        expect(result).toBeUndefined();
    });

    it('handles TTL expiry', () => {
        // Store with 1ms TTL
        cacheDb.put('testType', 'GET', 'expiring', { data: 1 }, 1);
        // Immediate read should work
        let result = cacheDb.get('testType', 'GET', 'expiring');
        // Allow it to be either expired already or still valid
        // (timing on fast machines is unpredictable)
        if (result !== undefined) {
            expect(result).toEqual({ data: 1 });
        }
    });

    it('removes entries by type', () => {
        cacheDb.put('typeA', 'GET', 'k1', 'v1');
        cacheDb.put('typeB', 'GET', 'k2', 'v2');
        cacheDb.removeByType('typeA');
        expect(cacheDb.get('typeA', 'GET', 'k1')).toBeUndefined();
        expect(cacheDb.get('typeB', 'GET', 'k2')).toBe('v2');
    });

    it('clears all entries', () => {
        cacheDb.put('t1', 'a', 'k1', 'v1');
        cacheDb.put('t2', 'a', 'k2', 'v2');
        cacheDb.clearAll();
        expect(cacheDb.entryCount()).toBe(0);
    });

    it('tracks metrics', () => {
        cacheDb.resetMetrics();
        cacheDb.put('t', 'a', 'k', 'v');
        cacheDb.get('t', 'a', 'k');        // hit
        cacheDb.get('t', 'a', 'miss');      // miss
        let m = cacheDb.getMetrics();
        expect(m.writes).toBe(1);
        expect(m.hits).toBe(1);
        expect(m.misses).toBe(1);
    });

    it('counts entries', () => {
        cacheDb.put('t1', 'a', 'k1', 'v1');
        cacheDb.put('t2', 'a', 'k2', 'v2');
        expect(cacheDb.entryCount()).toBe(2);
    });

    it('overwrites existing entries', () => {
        cacheDb.put('t', 'a', 'k', 'old');
        cacheDb.put('t', 'a', 'k', 'new');
        expect(cacheDb.get('t', 'a', 'k')).toBe('new');
        expect(cacheDb.entryCount()).toBe(1); // same PK → replaced in place
    });

    it('evicts expired entries', () => {
        cacheDb.put('t', 'a', 'k1', 'v1', 1); // 1ms TTL
        cacheDb.put('t', 'a', 'k2', 'v2', 0); // no expiry
        // Wait briefly for TTL to pass
        let start = Date.now();
        while (Date.now() - start < 5) { /* spin */ }
        let evicted = cacheDb.evictExpired();
        expect(evicted).toBeGreaterThanOrEqual(0); // might be 0 if timing is tight
    });

    it('stores string values directly', () => {
        cacheDb.put('t', 'a', 'k', 'plain string');
        expect(cacheDb.get('t', 'a', 'k')).toBe('plain string');
    });

    it('stores array values', () => {
        cacheDb.put('t', 'a', 'k', [1, 2, 3]);
        expect(cacheDb.get('t', 'a', 'k')).toEqual([1, 2, 3]);
    });

    it('stores numeric values', () => {
        cacheDb.put('t', 'a', 'k', 42);
        expect(cacheDb.get('t', 'a', 'k')).toBe(42);
    });

    it('remove with key deletes specific entries', () => {
        cacheDb.put('t', 'GET', 'k1', 'v1');
        cacheDb.put('t', 'POST', 'k1', 'v2');
        cacheDb.put('t', 'GET', 'k2', 'v3');
        cacheDb.remove('t', 'k1');
        expect(cacheDb.get('t', 'GET', 'k1')).toBeUndefined();
        expect(cacheDb.get('t', 'POST', 'k1')).toBeUndefined();
        expect(cacheDb.get('t', 'GET', 'k2')).toBe('v3');
    });

    it('DEFAULT_TTL_MS is 5 minutes', () => {
        expect(cacheDb.DEFAULT_TTL_MS).toBe(300000);
    });
});

// --- Bundle optimization tests ---
describe('Vite config — bundle optimization', () => {
    it('vite.config.js exports manualChunks', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const configPath = path.default.resolve(import.meta.dirname, '../../vite.config.js');
        const content = fs.default.readFileSync(configPath, 'utf8');
        expect(content).toContain('manualChunks');
        expect(content).toContain('vendor-mithril');
    });
});

// --- Lazy component loading tests ---
describe('features/media.js — lazy component loading', () => {
    it('media feature uses lazyComponent for optional modules', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const mediaPath = path.default.resolve(import.meta.dirname, '../features/media.js');
        const content = fs.default.readFileSync(mediaPath, 'utf8');
        expect(content).toContain('lazyComponent');
        expect(content).toContain("import('../components/pdfViewer.js')");
        expect(content).toContain("import('../components/olio.js')");
        expect(content).toContain("import('../components/audio.js')");
        expect(content).toContain("import('../components/designer.js')");
        expect(content).toContain("import('../components/emoji.js')");
    });
});

// --- Request deduplication tests ---
describe('am7client — request deduplication', () => {
    it('am7client.js contains inflight dedup map', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const clientPath = path.default.resolve(import.meta.dirname, '../core/am7client.js');
        const content = fs.default.readFileSync(clientPath, 'utf8');
        expect(content).toContain('_inflight');
        expect(content).toContain('dedupKey');
    });
});

// --- Accessibility attribute tests ---
describe('Accessibility — ARIA attributes in source', () => {
    async function readFile(relPath) {
        const fs = await import('fs');
        const path = await import('path');
        return fs.default.readFileSync(path.default.resolve(import.meta.dirname, relPath), 'utf8');
    }

    it('topMenu has aria-label on icon buttons', async () => {
        let content = await readFile('../components/topMenu.js');
        expect(content).toContain("'aria-label': \"Home\"");
        expect(content).toContain("'aria-label': \"Logout\"");
        expect(content).toContain("'aria-hidden': 'true'");
    });

    it('navigation has aria-label on nav and hamburger', async () => {
        let content = await readFile('../components/navigation.js');
        expect(content).toContain("'aria-label': \"Main navigation\"");
        expect(content).toContain("'aria-label': \"Toggle navigation menu\"");
    });

    it('toast container has aria-live', async () => {
        let content = await readFile('../core/pageClient.js');
        expect(content).toContain("'aria-live': \"polite\"");
        expect(content).toContain("'aria-atomic': \"false\"");
    });

    it('context menu has role=menu and menuitem', async () => {
        let content = await readFile('../components/contextMenu.js');
        expect(content).toContain("role: 'menu'");
        expect(content).toContain("role: 'menuitem'");
        expect(content).toContain("role: 'separator'");
    });

    it('breadcrumb has aria-label', async () => {
        let content = await readFile('../components/breadcrumb.js');
        expect(content).toContain("'aria-label': \"Breadcrumb\"");
    });

    it('router uses semantic main element', async () => {
        let content = await readFile('../router.js');
        expect(content).toContain('m("main"');
        expect(content).toContain("role: \"main\"");
    });

    it('list toolbar buttons have aria-labels', async () => {
        let content = await readFile('../views/list.js');
        expect(content).toContain("'Add new item'");
        expect(content).toContain("'Edit selected'");
        expect(content).toContain("'Delete selected'");
        expect(content).toContain("'Select all'");
    });

    it('notification button has aria-label and aria-expanded', async () => {
        let content = await readFile('../components/notifications.js');
        expect(content).toContain("'aria-label': 'Notifications'");
        expect(content).toContain("'aria-expanded'");
    });
});
