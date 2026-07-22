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
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { setupWorkflowTestData, cleanupTestUser, apiLogin, apiLogout, ensurePath, createNote } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

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
    test.describe.configure({ timeout: 900000 });
    let testInfo = {};
    let docName = '';
    let noteObjectId = null;

    test.beforeAll(async ({ request }) => {
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
        let chatDir = await ensurePath(request, 'auth.group', 'data', '~/Chat');
        if (!chatDir || !chatDir.id) {
            throw new Error('beforeAll: could not ensure ~/Chat directory — chatDir=' + JSON.stringify(chatDir));
        }
        let connResp = await request.post(REST + '/model', {
            data: {
                schema: 'system.connection',
                name: 'contentAnalysis Connection',
                groupId: chatDir.id,
                groupPath: chatDir.path,
                serverUrl: 'http://192.168.1.42:11434',
                requestTimeout: 120
            }
        });
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
                model: 'qwen3-vl:8b-instruct',
                analyzeModel: 'qwen3-vl:8b-instruct',
                serviceType: 'ollama',
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
        await cleanupTestUser(request, testInfo.user && testInfo.user.objectId, { userName: testInfo.testUserName });
    });

    test('extract -> continue creates real characters -> Manage Characters shows them', async ({ page }) => {
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
        // (renderStep2's "Scene List (N)" heading) on success.
        await expect(page.locator('text=/Scene List \\(\\d+\\)/')).toBeVisible({ timeout: 300000 });
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
        let dialog = page.getByRole('dialog');
        let emptyState = dialog.locator('text=No characters extracted yet.');
        let anyCharacterCard = dialog.locator('div.font-medium.text-sm').first();
        await Promise.race([
            emptyState.waitFor({ state: 'visible', timeout: 480000 }),
            anyCharacterCard.waitFor({ state: 'visible', timeout: 480000 })
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
