/**
 * P1-8 / P2-6 / P10-1: the admin feature panel reads the boot-pinned chat scene render-path switch
 * (GET /rest/config/render-path -> {chat}) read-only, and falls back to the server's product default
 * (v3 since P10-A 0) when the route is missing or fails; a 404 from an older Service7 must not break
 * the whole panel. PictureBook and ChapBook are V3-only since P10-1 and have no switch.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const { mockGetFeatureConfig, mockGetAvailableFeatures, mockGetRenderPath } = vi.hoisted(() => ({
    mockGetFeatureConfig: vi.fn(),
    mockGetAvailableFeatures: vi.fn(),
    mockGetRenderPath: vi.fn()
}));

vi.mock('mithril', () => ({
    default: Object.assign(
        (tag, attrs, children) => ({ tag, attrs, children }),
        { redraw: vi.fn(), route: { set: vi.fn(), get: () => '/' }, trust: (s) => s }
    )
}));

vi.mock('../core/am7client.js', () => ({
    am7client: {
        getFeatureConfig: mockGetFeatureConfig,
        getAvailableFeatures: mockGetAvailableFeatures,
        getRenderPath: mockGetRenderPath,
        updateFeatureConfig: vi.fn()
    }
}));

vi.mock('../core/pageClient.js', () => ({ page: { router: null } }));
vi.mock('../router.js', () => ({ layout: (x) => x, pageLayout: (x) => x }));

import { routes, renderPath, loadConfig } from '../features/featureConfig.js';

function findByTestId(node, id, out) {
    out = out || [];
    if (!node) return out;
    if (Array.isArray(node)) { node.forEach((n) => findByTestId(n, id, out)); return out; }
    if (typeof node !== 'object') return out;
    if (node.attrs && node.attrs['data-testid'] === id) out.push(node);
    findByTestId(node.children, id, out);
    return out;
}

describe('featureConfig render-path (P1-8)', () => {
    beforeEach(() => {
        mockGetFeatureConfig.mockReset().mockResolvedValue({ features: ['core', 'featureConfig'], profile: 'custom' });
        mockGetAvailableFeatures.mockReset().mockResolvedValue([]);
        mockGetRenderPath.mockReset();
    });

    it('reports the chat value from the server and renders it read-only; no PictureBook / ChapBook chip', async () => {
        mockGetRenderPath.mockResolvedValue({ chat: 'v3' });
        await loadConfig();
        expect(renderPath()).toEqual({ chat: 'v3' });

        let view = routes['/admin/features'].view();
        let ch = findByTestId(view, 'render-path-chat');
        expect(ch.length).toBe(1);
        expect(ch[0].attrs['data-value']).toBe('v3');
        expect(findByTestId(view, 'render-path-picturebook').length).toBe(0);
        expect(findByTestId(view, 'render-path-chapbook').length).toBe(0);
        // No control can change it: the section carries no button or input.
        let section = findByTestId(view, 'render-path')[0];
        let tags = [];
        (function walk(n) {
            if (!n) return;
            if (Array.isArray(n)) { n.forEach(walk); return; }
            if (typeof n !== 'object') return;
            if (n.tag) tags.push(n.tag);
            walk(n.children);
        })(section);
        expect(tags).not.toContain('button');
        expect(tags).not.toContain('input');
    });

    it('falls back to the product default (chat v3) when the route is absent (older Service7) without failing the panel', async () => {
        mockGetRenderPath.mockRejectedValue(Object.assign(new Error('Not Found'), { code: 404 }));
        await loadConfig();
        expect(renderPath()).toEqual({ chat: 'v3' });
        let view = routes['/admin/features'].view();
        expect(findByTestId(view, 'render-path-chat')[0].attrs['data-value']).toBe('v3');
        // The rest of the panel loaded: the profile line is present, no load error.
        expect(JSON.stringify(view)).not.toContain('Failed to load feature configuration');
    });

    it('treats a null body or an unrecognised value as the product default (chat v3 since P10-A 0)', async () => {
        mockGetRenderPath.mockResolvedValue(null);
        await loadConfig();
        expect(renderPath()).toEqual({ chat: 'v3' });

        mockGetRenderPath.mockResolvedValue({ chat: 'V1' });
        await loadConfig();
        expect(renderPath()).toEqual({ chat: 'v3' });
    });

    it('treats a body with no chat key (stale picturebook/chapbook keys only) as the chat product default v3', async () => {
        mockGetRenderPath.mockResolvedValue({ picturebook: 'v1', chapbook: 'v1' });
        await loadConfig();
        expect(renderPath()).toEqual({ chat: 'v3' });
        let view = routes['/admin/features'].view();
        expect(findByTestId(view, 'render-path-chat')[0].attrs['data-value']).toBe('v3');
    });

    it('reports the chat rollback value (v1) when the server set it explicitly', async () => {
        mockGetRenderPath.mockResolvedValue({ chat: 'v1' });
        await loadConfig();
        expect(renderPath()).toEqual({ chat: 'v1' });
        let view = routes['/admin/features'].view();
        expect(findByTestId(view, 'render-path-chat')[0].attrs['data-value']).toBe('v1');
    });
});
