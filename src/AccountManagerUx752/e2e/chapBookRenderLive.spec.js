/**
 * ChapBook Review Redesign — LIVE generative end-to-end (STEP 3)
 *
 * The ONLY spec that touches the live GPU/LLM hardware. It runs the two real generative paths the
 * ChapBook review + reader depend on, end-to-end, and VISUALLY inspects the result:
 *
 *   TEST 1 (SD, LLM-independent): RENDER a scene through Stable Diffusion (192.168.1.39) with an
 *     explicit sdPrompt (rendered verbatim, no LLM landscape step). Assert rendered:true + a real
 *     imageObjectId, that the /pages payload then surfaces the rendered image name, then open the
 *     reader and wait for the page's <img> to actually LOAD and DECODE (naturalWidth > 0) — i.e. real
 *     image bytes reached and rendered in the browser — and screenshot it for visual inspection.
 *
 *   TEST 2 (LLM): RE-ANALYZE a poem through the LLM (192.168.1.42): POST /analyze/{poemObjectId}. The
 *     endpoint returns a bare {success:true} and is SUPPOSED to PERSIST theme/mood/keywords onto the
 *     poem — so we read the poem back and assert the LLM actually populated a real theme (proof it
 *     ran, not a no-op). This is kept strict on purpose: see the ENV/DEFECT note below.
 *
 * GATED behind CB_LIVE_GEN so the default suite never fires the GPU. Run SINGLE-THREADED:
 *   CB_LIVE_GEN=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 \
 *     npx playwright test e2e/chapBookRenderLive.spec.js --project=chromium --workers=1
 *
 * HOSTS: SD (192.168.1.39) and LLM (192.168.1.42) must be reachable from the backend that serves the
 * base URL. Verified 2026-09-06 from inside am7test-am7-1: SD :7801 → 302, LLM :11434/api/tags → 200.
 *
 * KNOWN ENVIRONMENT MISCONFIG (affects TEST 2, not the redesign): the am7test backend's LLM
 * system.connection (id=15) has dialect=OPENAI (Azure), so Chat.getServiceUrl() builds
 *   http://192.168.1.42:11434/openai/deployments/{model}/chat/completions  → HTTP 404
 * The server IS Ollama; its OpenAI-compat endpoint /v1/chat/completions returns 200 (curl-verified),
 * so the correct dialect is OPENAI_COMPAT (or OLLAMA). Until the connection dialect is corrected
 * (a deployment-config change), every LLM call 404s. SEPARATELY this exposes a real CODE DEFECT:
 * POST /analyze returns {success:true} even though the LLM 404'd and NOTHING was persisted
 * (ChapBookUtil.analyzePoemTheme logs "LLM returned no result" and returns void; the service reports
 * success regardless). TEST 2 is deliberately NOT weakened to hide this — it asserts the correct
 * behavior and will fail here, which is the honest signal.
 */
import { test, expect } from '@playwright/test';
import {
    ensureSharedTestUser, restLoginShared, loginAsSharedUser,
    ensurePoemsGroup, ensurePoem, createChapBook, bookPages, poemByObjectId,
    CB_REST, POEM_1, POEM_2
} from './helpers/chapbook.js';

// A concrete, image-friendly landscape prompt so SD renders deterministically without needing the LLM
// landscape step (the LLM is exercised separately by TEST 2).
const SD_PROMPT = 'a wide misty autumn landscape at dawn, ancient oak trees, drifting falling leaves, '
    + 'soft golden light, cinematic, highly detailed, no people, no text';

async function seed(request, slug) {
    await ensureSharedTestUser(request);
    await restLoginShared(request);
    const { groupId, organizationId } = await ensurePoemsGroup(request);
    const p1 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-1', 'Memory', POEM_1);
    const p2 = await ensurePoem(request, groupId, organizationId, 'E2E-CB-Poem-2', 'Winter', POEM_2);
    const bookOid = await createChapBook(request, slug + '-' + Date.now(), 'E2E Render Book', [p1, p2], 4);
    const pages = await bookPages(request, bookOid);
    expect(pages.length, 'book has pages').toBeGreaterThanOrEqual(1);
    return { bookOid, pages, p1 };
}

test.describe('ChapBook — LIVE SD + LLM generative end-to-end', () => {

    test('SD: render a scene verbatim and prove the image loads in the reader', async ({ page, request }) => {
        test.skip(!process.env.CB_LIVE_GEN, 'set CB_LIVE_GEN=1 to run the live SD generative test');
        test.setTimeout(360000);

        const { bookOid, pages } = await seed(request, 'e2e-cb-render-sd');
        const scene0Oid = pages[0].objectId;

        // Render scene 0 verbatim from an explicit landscape prompt (no LLM step).
        const rr = await request.post(CB_REST + '/scene/' + scene0Oid + '/generate', {
            data: { schema: 'olio.pictureBookRequest', sdPrompt: SD_PROMPT },
            timeout: 300000
        });
        expect(rr.ok(), 'generate endpoint failed: ' + rr.status() + ' ' + await rr.text()).toBe(true);
        const rbody = await rr.json();
        console.log('SD generate →', JSON.stringify(rbody));
        expect(rbody.rendered, 'scene actually rendered through SD (rendered:true)').toBe(true);
        expect(rbody.imageObjectId, 'render returned a real image objectId').toBeTruthy();

        // The /pages payload must now surface the rendered image path fields (bookPageView resolves
        // imageGroupPath/imageName from the scene's imageObjectId) — the reader has no other source.
        const afterPages = await bookPages(request, bookOid);
        const p0 = afterPages.find(p => p.objectId === scene0Oid);
        expect(p0 && p0.imageName, 'pages payload carries the rendered image name').toBeTruthy();

        // ── Visual proof: open the reader, advance to the rendered page, wait for the <img> to LOAD ──
        await loginAsSharedUser(page);
        await page.goto('/#!/chap-book/read/' + bookOid, { timeout: 30000 });
        await page.locator('button:has-text("Begin")').click();

        // renderChapBookPage puts the landscape image as an absolutely-positioned object-cover <img>.
        const img = page.locator('img.object-cover').first();
        await expect(img).toBeVisible({ timeout: 30000 });
        await page.waitForFunction(() => {
            const i = document.querySelector('img.object-cover');
            return i && i.complete && i.naturalWidth > 0;
        }, { timeout: 60000 });

        const dims = await img.evaluate(el => ({ w: el.naturalWidth, h: el.naturalHeight, src: el.src }));
        console.log('reader rendered image:', JSON.stringify(dims));
        expect(dims.w, 'the SD image actually loaded and decoded in the browser').toBeGreaterThan(0);
        // Landscape orientation — ChapBook renders wide scenes.
        expect(dims.w).toBeGreaterThanOrEqual(dims.h);

        await page.screenshot({ path: 'test-results/chapbook-render-live.png', fullPage: true });
    });

    test('LLM: re-analyze a poem and prove a real theme persisted', async ({ request }) => {
        test.skip(!process.env.CB_LIVE_GEN, 'set CB_LIVE_GEN=1 to run the live LLM analyze test');
        test.setTimeout(300000);

        const { p1 } = await seed(request, 'e2e-cb-render-llm');

        // The endpoint returns a bare {success:true}; the real assertion is the persisted theme below.
        const ar = await request.post(CB_REST + '/analyze/' + p1, { data: {}, timeout: 180000 });
        expect(ar.ok(), 'analyze endpoint HTTP failed: ' + ar.status() + ' ' + await ar.text()).toBe(true);

        const poem = await poemByObjectId(request, p1);
        expect(poem, 'poem re-read after analyze').toBeTruthy();
        const theme = (poem.theme || '').trim();
        console.log('LLM analyze → theme="' + theme + '" mood="' + (poem.mood || '') + '" keywords="' + (poem.keywords || '') + '"');

        // A real LLM theme — non-empty (proof the LLM ran and analyzePoemTheme persisted it).
        // NOTE: this currently fails against am7test because system.connection id=15 has dialect=OPENAI
        // (Azure /openai/deployments path → 404) while the host is Ollama. Not weakened on purpose.
        expect(theme.length, 'LLM populated a non-empty theme (proof the LLM ran and persisted it)').toBeGreaterThan(0);
        expect(['null', 'n/a', 'none', 'unknown', 'unspecified'].includes(theme.toLowerCase()),
            'theme is a real value, not a literal null-ish string').toBe(false);
    });
});
