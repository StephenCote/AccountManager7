/**
 * Reopening an existing picture book showed every previously rendered scene as the grey "no image"
 * placeholder, even though each row's status badge correctly read "done".
 *
 * Cause: step 4 reads thumbnails from `sceneImageUrls` (keyed by SCENE objectId, so a regenerate can
 * invalidate one scene) and step 5 from `step5ImageUrls` (keyed by IMAGE objectId). Both were
 * written in exactly ONE place — the per-scene resolve that runs right after a successful generation
 * in the current session. The resume path populated neither when it landed on step 4, so a reopened
 * book had no URLs to render.
 *
 * Verified against the running "BWO 3" book: 41 scenes, 41 persisted imageObjectIds, every one
 * resolving to a real data.data row in the book's Scenes group. The images were always there; the
 * client simply never asked for their URLs.
 *
 * These drive the real `hydrateSceneThumbnails` through the module's test seam, with
 * `resolveAllImageUrls` mocked at the module boundary (it is a network call). The assertion that
 * matters is the KEYING: a map filled under the wrong key renders nothing, which is precisely how
 * this shipped.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const { mockResolveAllImageUrls } = vi.hoisted(() => ({
    mockResolveAllImageUrls: vi.fn()
}));

vi.mock('../workflows/sceneExtractor.js', async function (importOriginal) {
    const actual = await importOriginal();
    return Object.assign({}, actual, { resolveAllImageUrls: mockResolveAllImageUrls });
});

// Mirrors what loadPictureBook returns for a reopened book: listScenes merges imageObjectId and
// status onto each meta scene entry.
const RESUMED_SCENES = [
    { objectId: 'scene-1', title: 'The Final Goodbye', status: 'done', imageObjectId: 'img-1' },
    { objectId: 'scene-2', title: 'Loading the Car', status: 'done', imageObjectId: 'img-2' },
    { objectId: 'scene-3', title: 'Not Yet Rendered', status: 'pending' }
];

let pb;

beforeEach(async function () {
    vi.clearAllMocks();
    pb = await import('../workflows/pictureBook.js');
    pb.__resetThumbnailStateForTest();
});

describe('reopened book scene thumbnails', function () {
    it('keys step 4 thumbnails by SCENE objectId, not image objectId', async function () {
        mockResolveAllImageUrls.mockResolvedValue({
            'img-1': 'https://host/media/org/data.data/Books/BWO/Scenes/one.png',
            'img-2': 'https://host/media/org/data.data/Books/BWO/Scenes/two.png'
        });

        await pb.hydrateSceneThumbnails(RESUMED_SCENES);
        const { sceneImageUrls } = pb.__thumbnailStateForTest();

        // Step 4 renders `sceneImageUrls[scene.objectId]`. Keyed any other way, the row falls back
        // to the placeholder — which is the bug.
        expect(sceneImageUrls['scene-1']).toBe('https://host/media/org/data.data/Books/BWO/Scenes/one.png');
        expect(sceneImageUrls['scene-2']).toBe('https://host/media/org/data.data/Books/BWO/Scenes/two.png');
    });

    it('also fills the step 5 map, so advancing to the book view needs no re-fetch', async function () {
        mockResolveAllImageUrls.mockResolvedValue({ 'img-1': 'url-1', 'img-2': 'url-2' });

        await pb.hydrateSceneThumbnails(RESUMED_SCENES);
        const { step5ImageUrls } = pb.__thumbnailStateForTest();

        // Step 5 renders `step5ImageUrls[scene.imageObjectId]` — the other keying.
        expect(step5ImageUrls['img-1']).toBe('url-1');
        expect(step5ImageUrls['img-2']).toBe('url-2');
    });

    it('leaves a never-rendered scene without a thumbnail', async function () {
        mockResolveAllImageUrls.mockResolvedValue({ 'img-1': 'url-1', 'img-2': 'url-2' });

        await pb.hydrateSceneThumbnails(RESUMED_SCENES);
        const { sceneImageUrls } = pb.__thumbnailStateForTest();

        // scene-3 has no imageObjectId: it must stay absent so step 4 shows the placeholder and the
        // "pending" badge, rather than inheriting a neighbour's image.
        expect(sceneImageUrls['scene-3']).toBeUndefined();
        expect(Object.keys(sceneImageUrls)).toHaveLength(2);
    });

    it('skips an image whose URL could not be resolved', async function () {
        // resolveImageUrl returns null when the data.data record is gone or unreadable.
        mockResolveAllImageUrls.mockResolvedValue({ 'img-1': 'url-1', 'img-2': null });

        await pb.hydrateSceneThumbnails(RESUMED_SCENES);
        const { sceneImageUrls } = pb.__thumbnailStateForTest();

        expect(sceneImageUrls['scene-1']).toBe('url-1');
        // A null URL must not be stored — `thumbUrl && ...` would be falsy anyway, but storing it
        // would mask a later successful resolve.
        expect(sceneImageUrls['scene-2']).toBeUndefined();
    });

    it('is a no-op for an empty or missing scene list, and never throws', async function () {
        await expect(pb.hydrateSceneThumbnails([])).resolves.toBeUndefined();
        await expect(pb.hydrateSceneThumbnails(null)).resolves.toBeUndefined();
        expect(mockResolveAllImageUrls).not.toHaveBeenCalled();
        expect(Object.keys(pb.__thumbnailStateForTest().sceneImageUrls)).toHaveLength(0);
    });
});
