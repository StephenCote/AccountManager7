/**
 * Picture Book Wizard — real UX click-through of the reordered flow
 * (extract scenes -> create characters at Step 2->3 -> Manage Characters -> Images).
 *
 * Unlike pictureBookLive.spec.js (which drives the backend via page.evaluate/fetch),
 * this test drives the actual rendered wizard: real login, real button clicks, real
 * picker selection, real Dialog state transitions. It exists specifically to prove
 * the olio.pictureBookCharacterStub baseModel fix (characters were silently dropped
 * during deserialization -> zero charPerson records ever created -> "Manage Characters"
 * screen rendered blank) actually works end-to-end through the UI, not just via a
 * direct backend call.
 *
 * Live-LLM test (scene extraction + character creation, up to 60 min on the local-container tier),
 * so it is gated like its siblings and must run serially:
 *   PB_ASYNC_TESTS=1 PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pictureBookWizardUx.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser, apiLogin, apiLogout, ensurePath, createNote, resolveChatRoute } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';
const LLM_ENABLED = process.env.PB_ASYNC_TESTS === '1';

// Same text pictureBookLive.spec.js already uses successfully with this model/server
// combination — ruling out "story too short for the extractor" as a variable while
// testing the reordered wizard flow.
const STORY_TEXT = `AIME

Valentines Day singles events were blissfully epicaricacious. Schadenfreudian. Delightfully delectable. Lonely adults trodding out a lifetime's worth of emotional baggage casually dressed to such fantasy as trophies enshrined behind the thin plate glass of a secondhand curio; Of course one or more cats sprayed the back, and occasionally a few exotic bugs crept from the darkened hollows of the rear right leg.

The AI And Me singles event promised to be unlike any other; perhaps had they simply spoke plain, for the price of a geriatric rock star's off-Broadway casino theatre ticket, one could spend a Valentines Day evening taking the very same date they'd been on every day for as long as they could remember; This special evening your phone and you will immerse in a peaceful atmosphere and draw reverie from the curated bar.

Introverts slouching in their date's romantic glow formed a wilted bouquet, long ago plucked buds desperately clutching leathery petals against stems thickened with unnatural fertilizers. An eery somber echo rang for every clink against rectangular glass. And, as if scripted to specific times or events, sometimes in a rippling sequence, lips momentarily touched glass and a tear gleamed silver as it melted through heavy foundation.

With Rejects popping off in a slow though consistent rolling thunder, Break-Ups were streaks and bolts and chains of lightning that when striking close set the spine to shiver. An unsettling crash and crunch, a sudden hush making audible room for the imminent gasp of a dejected soliloquy. Quick-change service staff carry the remains upon a silver platter hoisted betwixt outstretched hands, pallbearers leading a somber procession towards the always available cry room.

Momentarily the shock unchokes the perception of reality. Screens going dark are infrequent, and screens going plaid hint at an appreciation for Mel Brooks. Ciao. That's all that's left behind when Abandoned: The heartless sayonara of an emotionally binary ex.

A vacuum forms between the two tables, two singles abandoned at a Valentines Day singles event, forced into eye contact; the following sequence of events from table, to check, to door proceeded with all the predictability of a B-list romantic comedy.

Outside, the rain began to fall, light and misty, fog churning like a smoldering fire through the streets. Bathed in the bright neon lights advertising the very explicit fantasies so secretly craved, the walk across the slick street through choking fog appeared programmatic, hypnotic, and the way the doors whisper open and greet with a pleasant warm puff of air is resplendent, only to be greeted by a solemn faced caretaker who prepares an arrangement of new vessels into which you must pour your soul.`;

test.describe('Picture Book Wizard — real UX flow', () => {
    // Per-call LLM request timeout on the test's system.connection (seconds) — see the beforeAll
    // comment for the measurements behind it.
    const EXTRACT_REQUEST_TIMEOUT_S = 600;
    // 60 min: up to two extraction generations (the Scene List wait below, 2 x 600s + margin) plus
    // the character-creation wait (1800s, see CREATE_CHARACTERS_WAIT_MS) plus the Step 3/4 UI
    // checks, on the slow local-container LLM tier. Playwright retries once (playwright.config.js).
    test.describe.configure({ timeout: 3600000 });
    let testInfo = {};
    let docName = '';
    let noteObjectId = null;

    test.beforeAll(async ({ request }) => {
        test.skip(!LLM_ENABLED, 'PB_ASYNC_TESTS!=1 — skips the live LLM wizard click-through');
        testInfo = await setupWorkflowTestData(request, { suffix: 'pbux' + Date.now().toString(36) });

        await apiLogin(request, { user: testInfo.testUserName, password: testInfo.testPassword });

        let ts = Date.now().toString(36);
        docName = 'AIME-pbux-' + ts;
        let note = await createNote(request, '~/Notes', docName, STORY_TEXT);
        noteObjectId = note && note.objectId;

        // Named "contentAnalysis" so pictureBook.js's loadDefaults() auto-resolves it via
        // ChatUtil.resolveConfig() checking the user's own ~/Chat before the shared library —
        // this lets the real Step 1 UI populate the Chat Config field without driving the
        // nested ObjectPicker.openLibrary() modal.
        //
        // Connection info (serverUrl/apiKey/requestTimeout) lives on a separate system.connection
        // record referenced via chatConfig's "connection" FK -- a flat serverUrl field directly on
        // chatConfig is silently ignored by Chat.configureChat(), which only reads it off the
        // linked connection. Create the connection first, then link it on chatConfig create.
        //
        // The connection shape and model names come from resolveChatRoute() (local container ->
        // LiteLLM -> .42), exactly like ensureChatConfig(). The per-call timeout is 600s, not the
        // 300s ensureChatConfig uses: the 10-scene extract of the AIME passage is a ~1800-token
        // reply, and the local-container tier (qwen3:8b-jos-ctr, CPU) measured 191s, 223s, 254s and
        // then >300s for it across four runs on 2026-10-07 -- the 300s run hit Chat's buffer-mode
        // timeout ("Aborted the outbound LLM exchange (buffer-mode timeout after 300s)" -> "Null LLM
        // response"), the job COMPLETED with zero scenes and the wizard never reached Step 2.
        // This used to hardcode .42 + qwen3-vl:8b-instruct + requestTimeout 120: the identical
        // request measured 81s against an idle .42 (1793 tokens at ~24 tok/s), so with .42 shared
        // across concurrent test lanes the 120s deadline fired the same way.
        const route = await resolveChatRoute();
        let chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
        if (!chatDir || !chatDir.id) {
            throw new Error('beforeAll: could not ensure ~/Chat directory — chatDir=' + JSON.stringify(chatDir));
        }
        let connBodyReq = {
            schema: 'system.connection',
            name: 'contentAnalysis Connection',
            groupId: chatDir.id,
            groupPath: chatDir.path,
            serverUrl: route.serverUrl,
            requestTimeout: EXTRACT_REQUEST_TIMEOUT_S
        };
        if (route.dialect) connBodyReq.dialect = route.dialect;
        if (route.upstream) connBodyReq.upstream = route.upstream;
        if (route.apiKey) connBodyReq.apiKey = route.apiKey;
        let connResp = await request.post(REST + '/model', { data: connBodyReq });
        let connBody = await connResp.text();
        if (!connResp.ok()) {
            throw new Error('beforeAll: system.connection create failed (' + connResp.status() + '): ' + connBody);
        }
        let conn = JSON.parse(connBody);
        if (!conn || !conn.objectId) {
            throw new Error('beforeAll: system.connection create returned no objectId: ' + connBody);
        }
        let cfgResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.llm.chatConfig',
                name: 'contentAnalysis',
                groupId: chatDir.id,
                groupPath: chatDir.path,
                model: route.pbModel,
                analyzeModel: route.analysisModel,
                serviceType: route.dialect || 'ollama',
                stream: false,
                connection: { schema: 'system.connection', id: conn.id, objectId: conn.objectId }
            }
        });
        let cfgBody = await cfgResp.text();
        if (!cfgResp.ok()) {
            throw new Error('beforeAll: olio.llm.chatConfig create failed (' + cfgResp.status() + '): ' + cfgBody);
        }

        await apiLogout(request);
    });

    test.afterAll(async ({ request }) => {
        if (!LLM_ENABLED) return;
        await cleanupTestUser(request, testInfo.user && testInfo.user.objectId, { userName: testInfo.testUserName });
    });

    test('extract -> continue creates real characters -> Manage Characters shows them', async ({ page }) => {
        test.skip(!LLM_ENABLED, 'PB_ASYNC_TESTS!=1 — skips the live LLM wizard click-through');
        expect(noteObjectId, 'note was not created in beforeAll').toBeTruthy();

        page.on('response', async (resp) => {
            if ((resp.url().includes('/picture-book/') && resp.url().includes('extract'))
                || resp.url().includes('/chat/library/')) {
                let body = '';
                try { body = (await resp.text()).substring(0, 2000); } catch (e) { body = '<unreadable>'; }
                console.log('[NETWORK] ' + resp.status() + ' ' + resp.url() + '\n' + body);
            }
        });
        page.on('console', (msg) => {
            if (msg.type() === 'error' || msg.type() === 'warning') {
                console.log('[PAGE-' + msg.type().toUpperCase() + '] ' + msg.text());
            }
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Navigate straight to the picture-book viewer route for this document. With no scenes
        // yet, pictureBookView renders the empty state with a "Generate Picture Book" button that
        // calls pictureBookFromId(...) — the exact same wizard entry point the document-picker
        // flow uses, without needing to drive the ObjectPicker's list/table UI.
        await page.goto('/#!/picture-book/' + noteObjectId);
        let generateBtn = page.locator('button:has-text("Generate Picture Book")');
        await expect(generateBtn).toBeVisible({ timeout: 15000 });
        await generateBtn.click();

        // Wizard dialog should now be open on Step 1
        await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'wizardux-step1-open');

        // Wait for loadDefaults() to auto-resolve the "contentAnalysis" chat config
        let chatConfigField = page.locator('text=contentAnalysis').first();
        await expect(chatConfigField).toBeVisible({ timeout: 20000 });

        // Step 1 -> Extract
        let extractBtn = page.locator('button:has-text("Extract")').first();
        await expect(extractBtn).toBeEnabled({ timeout: 5000 });
        await extractBtn.click();

        // Extraction is a real LLM call — allow several minutes. Wizard auto-advances to Step 2
        // (renderStep2's "Scene List (N)" heading) on success. Budget: the local-container tier
        // measured 191s to >300s per generation on 2026-10-07 (see the connection comment above),
        // and the single-shot extractor retries ONCE with a corrective instruction when the first
        // reply is malformed JSON (PictureBookUtil.extractSingleShot), so one worst-case run is two
        // generations, each bounded by the connection's 600s request timeout — 1320s covers that.
        await expect(page.locator('text=/Scene List \\(\\d+\\)/')).toBeVisible({ timeout: 2 * EXTRACT_REQUEST_TIMEOUT_S * 1000 + 120000 });
        await screenshot(page, 'wizardux-step2-scenes');

        let sceneHeading = await page.locator('text=/Scene List \\(\\d+\\)/').first().textContent();
        let sceneCount = parseInt((sceneHeading.match(/\((\d+)\)/) || [])[1] || '0', 10);
        expect(sceneCount).toBeGreaterThan(0);

        // Step 2 -> Continue: this is the exact transition that used to silently create zero
        // charPerson records because pictureBookRequestModel's characters field deserialized
        // against the wrong baseModel (olio.pictureBookScene instead of a character shape).
        let continueBtn = page.locator('button:has-text("Continue")').first();
        await continueBtn.click();

        // "Creating characters..." button label should appear while createFromScenes runs
        let creatingLabel = page.locator('button:has-text("Creating characters...")');
        await creatingLabel.isVisible({ timeout: 5000 }).catch(() => false);

        // Step 3 renders pictureBookCharacters.js inline. Wait (real per-character LLM
        // enrichment calls) for either a real character name or the "no characters" empty state.
        // Scoped to the dialog: an unscoped 'div.font-medium.text-sm' also matches the top-nav
        // user-avatar badge (first in DOM order, rendered behind the modal backdrop), which is a
        // real match but the wrong element entirely -- not a duplicate-dialog bug (confirmed via
        // .am7-dialog-backdrop count == 1), just an under-scoped locator.
        // Budget: createFromScenes makes two LLM calls per named character (extract-character
        // ~65s + guess-apparel ~22s on the local-container tier, measured 2026-10-07 via LiteLLM),
        // and the number of named characters is whatever the LLM put in the 10 scenes: one run
        // produced 5, the next produced 16 (Adult 1, Cat, Attendee 1, AI Assistant, ... Attendee 3),
        // i.e. ~24 min of sequential LLM time. The earlier 480s and 900s waits both expired while
        // the server was still (correctly) creating characters; when a wait expires, afterAll's
        // cleanupTestUser deletes the user and connection out from under the still-running
        // createFromScenes request, which then logs "createCharPerson failed ... AUDIT DENY" and
        // "URI with undefined scheme" for every remaining character -- test-harness noise, not a
        // product defect. 30 min covers ~20 named characters on the local tier.
        const CREATE_CHARACTERS_WAIT_MS = 1800000;
        let dialog = page.getByRole('dialog');
        let emptyState = dialog.locator('text=No characters extracted yet.');
        let anyCharacterCard = dialog.locator('div.font-medium.text-sm').first();
        await Promise.race([
            emptyState.waitFor({ state: 'visible', timeout: CREATE_CHARACTERS_WAIT_MS }),
            anyCharacterCard.waitFor({ state: 'visible', timeout: CREATE_CHARACTERS_WAIT_MS })
        ]).catch(() => {});
        await screenshot(page, 'wizardux-step3-managecharacters');

        let isEmpty = await emptyState.isVisible().catch(() => false);
        expect(isEmpty, 'Manage Characters showed the empty state — characters were not created').toBe(false);

        let characterCount = await dialog.locator('div.font-medium.text-sm').count();
        expect(characterCount).toBeGreaterThan(0);

        // Select the first character to reveal the detail panel, then confirm "Open Full Editor ->"
        // opens a NEW TAB (not an in-place navigation) -- there's no back nav that would restore
        // the in-progress wizard state (extracted scenes, created book/characters) otherwise.
        await dialog.locator('div.font-medium.text-sm').first().click();
        let openEditorLink = dialog.locator('text=Open Full Editor');
        await expect(openEditorLink).toBeVisible({ timeout: 10000 });
        let [editorPopup] = await Promise.all([
            page.context().waitForEvent('page'),
            openEditorLink.click()
        ]);
        await editorPopup.waitForLoadState();
        expect(editorPopup.url()).toMatch(/#!\/view\/olio\.charPerson\//);
        await screenshot(page, 'wizardux-step3-open-full-editor-newtab');
        await editorPopup.close();
        // Original tab/wizard must still be on Step 3 with the wizard dialog intact -- proving the
        // new-tab open didn't navigate the current tab away from the in-progress wizard.
        await expect(page.locator('text=Picture Book —').first()).toBeVisible({ timeout: 5000 });
        await expect(openEditorLink).toBeVisible({ timeout: 5000 });

        // Continue to Images (Step 4) confirms Step 3 -> Step 4 advance still works post-reorder
        await page.locator('button:has-text("Continue to Images")').click();
        await expect(page.locator('text=Image Generation')).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'wizardux-step4-images');
    });
});
