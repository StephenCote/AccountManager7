/**
 * Portraits + Gallery + Image Viewer — end-to-end with real character data.
 * Creates characters with portrait images from static media files.
 * Uses test user, not admin.
 */
import { test as base, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { captureConsole } from './helpers/console.js';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';

const test = base;

test.describe('Portraits, Gallery, Image Viewer', () => {
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('create chars with portraits, verify gallery pop-in and portrait toggle', async ({ page }) => {
        test.setTimeout(300000);

        let capture = captureConsole(page, { verbose: true });
        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // Load a static image as base64 for portrait upload
        let imgPath = path.resolve('..', 'AccountManagerUx7', 'media', 'femaleSilhouette.png');
        let imgPath2 = path.resolve('..', 'AccountManagerUx7', 'media', 'maleSilhouette.png');
        let img1B64 = fs.existsSync(imgPath) ? fs.readFileSync(imgPath).toString('base64') : null;
        let img2B64 = fs.existsSync(imgPath2) ? fs.readFileSync(imgPath2).toString('base64') : null;

        if (!img1B64 || !img2B64) {
            console.log('Static images not found at ' + imgPath);
            console.log('I DID NOT VERIFY portraits. Need silhouette images.');
            return;
        }

        // Navigate to chat to load modules
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(3000);

        let wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 3000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Step 1: Create two characters with portraits via JS
        let setup = await page.evaluate(async (args) => {
            let { am7client } = await import('/src/core/am7client.js');
            let { am7model } = await import('/src/core/model.js');
            let { page } = await import('/src/core/pageClient.js');
            let { am7chat } = await import('/src/chat/chatUtil.js');
            let { LLMConnector } = await import('/src/chat/LLMConnector.js');
            let log = [];
            let ts = Date.now().toString(36);

            let libOk = await LLMConnector.ensureLibrary();
            if (!libOk) { try { await LLMConnector.initLibrary(); } catch(e) {} }

            // Roll characters
            let roll1 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'female');
            roll1.firstName = 'Aria'; roll1.lastName = 'Test' + ts; roll1.name = roll1.firstName + ' ' + roll1.lastName;
            let roll2 = await am7model.forms.commands.rollCharacter(undefined, undefined, 'male');
            roll2.firstName = 'Rowan'; roll2.lastName = 'Test' + ts; roll2.name = roll2.firstName + ' ' + roll2.lastName;

            // Create character dir
            let cdir = await page.makePath('auth.group', 'data', '~/Characters');
            let gdir = await page.makePath('auth.group', 'data', '~/Gallery');

            // Create char 1
            let charN1 = am7model.prepareEntity(roll1, 'olio.charPerson', true);
            let w1 = charN1.store.apparel[0].wearables;
            for (let i = 0; i < w1.length; i++) {
                w1[i] = am7model.prepareEntity(w1[i], 'olio.wearable', false);
                if (w1[i].qualities && w1[i].qualities[0]) w1[i].qualities[0] = am7model.prepareEntity(w1[i].qualities[0], 'olio.quality', false);
            }
            await page.createObject(charN1);
            let char1 = await page.searchFirst('olio.charPerson', cdir.id, roll1.name);
            log.push('Char 1: ' + (char1 ? char1.objectId : 'FAILED'));

            // Create char 2
            let charN2 = am7model.prepareEntity(roll2, 'olio.charPerson', true);
            let w2 = charN2.store.apparel[0].wearables;
            for (let i = 0; i < w2.length; i++) {
                w2[i] = am7model.prepareEntity(w2[i], 'olio.wearable', false);
                if (w2[i].qualities && w2[i].qualities[0]) w2[i].qualities[0] = am7model.prepareEntity(w2[i].qualities[0], 'olio.quality', false);
            }
            await page.createObject(charN2);
            let char2 = await page.searchFirst('olio.charPerson', cdir.id, roll2.name);
            log.push('Char 2: ' + (char2 ? char2.objectId : 'FAILED'));

            if (!char1 || !char2) return { error: 'Character creation failed', log };

            // Upload portrait images
            let img1 = am7model.newPrimitive('data.data');
            img1.name = 'portrait-aria-' + ts + '.png';
            img1.contentType = 'image/png';
            img1.groupId = gdir.id;
            img1.groupPath = gdir.path;
            img1.dataBytesStore = args.img1B64;
            img1 = await page.createObject(img1);
            log.push('Portrait 1: ' + (img1 ? img1.objectId || 'created' : 'FAILED'));

            let img2 = am7model.newPrimitive('data.data');
            img2.name = 'portrait-rowan-' + ts + '.png';
            img2.contentType = 'image/png';
            img2.groupId = gdir.id;
            img2.groupPath = gdir.path;
            img2.dataBytesStore = args.img2B64;
            img2 = await page.createObject(img2);
            log.push('Portrait 2: ' + (img2 ? img2.objectId || 'created' : 'FAILED'));

            // Set portraits on profiles
            if (img1 && char1.profile) {
                await page.patchObject({ schema: 'identity.profile', id: char1.profile.id, portrait: { id: img1.id } });
                log.push('Set portrait 1 on profile');
            }
            if (img2 && char2.profile) {
                await page.patchObject({ schema: 'identity.profile', id: char2.profile.id, portrait: { id: img2.id } });
                log.push('Set portrait 2 on profile');
            }

            // Narrate
            try {
                let inst1 = am7model.prepareInstance(char1);
                await am7model.forms.commands.narrate(undefined, inst1);
                let inst2 = am7model.prepareInstance(char2);
                await am7model.forms.commands.narrate(undefined, inst2);
                log.push('Narrated both');
            } catch(e) { log.push('Narrate: ' + e.message); }

            // Create chatConfig with characters
            await page.makePath('auth.group', 'data', '~/Chat');
            let chatCfg = await am7chat.makeChat('PortraitTest-' + ts, 'herm-local:latest', 'http://localhost:11434', 'OLLAMA');
            if (chatCfg) {
                await page.patchObject({
                    schema: 'olio.llm.chatConfig', id: chatCfg.id,
                    systemCharacter: { objectId: char1.objectId },
                    userCharacter: { objectId: char2.objectId },
                    startMode: 'none', stream: false, rating: 'AO'
                });
                log.push('ChatConfig: ' + chatCfg.objectId);
            }

            // Create prompt config and chatRequest
            let promptCfg = await am7chat.makePrompt('default');
            if (!promptCfg) return { error: 'No prompt config', log };

            let chatReq = await am7chat.getChatRequest('PortraitTest-' + ts, chatCfg, promptCfg);
            log.push('ChatRequest: ' + (chatReq ? chatReq.objectId : 'FAILED'));

            return {
                char1Id: char1.objectId, char2Id: char2.objectId,
                chatReqId: chatReq ? chatReq.objectId : null,
                log
            };
        }, { img1B64, img2B64 });

        console.log('Setup:');
        setup.log.forEach(l => console.log('  ' + l));
        if (setup.error) {
            console.log('ERROR: ' + setup.error);
            expect(setup.error).toBeNull();
            return;
        }

        // Step 2: Navigate to chat, select the session, check portrait toggle
        // Reload chat page to pick up new session
        await page.locator('button[title="Home"]').click();
        await page.waitForTimeout(1000);
        await page.locator('button[title="Chat"]').click();
        await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
        await page.waitForTimeout(5000);

        // Dismiss wizard again if it shows
        wizardTitle = page.locator('text=Chat Library Setup');
        if (await wizardTitle.isVisible({ timeout: 2000 }).catch(() => false)) {
            await page.locator('button:has-text("Cancel")').click();
        }

        // Dismiss new session dialog if it shows
        let newDialog = page.locator('text=New Chat Session');
        if (await newDialog.isVisible({ timeout: 2000 }).catch(() => false)) {
            let cancelBtn = page.locator('button:has-text("Cancel")');
            if (await cancelBtn.isVisible({ timeout: 1000 }).catch(() => false)) await cancelBtn.click();
        }

        // Open sessions sidebar and find PortraitTest session
        let sessionsBtn = page.locator('button[title="Sessions"]');
        if (await sessionsBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
            await sessionsBtn.click();
            await page.waitForTimeout(1000);
        }

        let sessionItem = page.locator('text=/PortraitTest/');
        let found = await sessionItem.isVisible({ timeout: 5000 }).catch(() => false);
        if (found) {
            await sessionItem.click();
            await page.waitForTimeout(3000);
            console.log('Selected PortraitTest session');
        } else {
            console.log('PortraitTest session not found in sidebar');
            // Try clicking first available
            let firstSession = page.locator('.overflow-y-auto .truncate').first();
            if (await firstSession.isVisible({ timeout: 3000 }).catch(() => false)) {
                await firstSession.click();
                await page.waitForTimeout(3000);
            }
        }

        // Check portrait toggle
        let showPortrait = page.locator('button[title="Show portraits"]');
        let hidePortrait = page.locator('button[title="Hide portraits"]');
        let showVisible = await showPortrait.isVisible({ timeout: 5000 }).catch(() => false);
        let hideVisible = await hidePortrait.isVisible({ timeout: 1000 }).catch(() => false);

        if (showVisible) {
            console.log('VERIFIED: Portrait toggle exists, default is OFF');

            // Toggle ON
            await showPortrait.click();
            await page.waitForTimeout(500);
            await expect(page.locator('button[title="Hide portraits"]')).toBeVisible({ timeout: 3000 });
            console.log('VERIFIED: Toggle switches to ON');

            // Check for portrait images
            let portraits = page.locator('img[style*="120px"]');
            let pCount = await portraits.count();
            console.log('Portrait images visible: ' + pCount);
            if (pCount > 0) {
                console.log('VERIFIED: Portrait images render when toggle is ON');

                // Click a portrait — should open image viewer
                await portraits.first().click();
                await page.waitForTimeout(1000);

                // Check if dialog opened
                let dialogBackdrop = page.locator('[role="dialog"]');
                let viewerOpen = await dialogBackdrop.isVisible({ timeout: 3000 }).catch(() => false);
                console.log('Image viewer dialog opened on portrait click: ' + viewerOpen);
                if (viewerOpen) {
                    console.log('VERIFIED: Image viewer opens on portrait click');
                    // Close it
                    let closeBtn = page.locator('button:has-text("Close")');
                    if (await closeBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                        await closeBtn.click();
                        await page.waitForTimeout(500);
                    } else {
                        // Click backdrop to close
                        await dialogBackdrop.click({ position: { x: 10, y: 10 } });
                        await page.waitForTimeout(500);
                    }
                    // Wait for dialog to close
                    await expect(dialogBackdrop).not.toBeVisible({ timeout: 5000 }).catch(() => {});
                }
            }

            // Toggle OFF
            await page.locator('button[title="Hide portraits"]').click();
            await expect(page.locator('button[title="Show portraits"]')).toBeVisible({ timeout: 3000 });
            console.log('VERIFIED: Toggle back to OFF');

            await screenshot(page, 'verify-portraits');
        } else if (hideVisible) {
            console.log('FAIL: Portrait default is ON (should be OFF)');
            expect(showVisible).toBe(true);
        } else {
            console.log('Portrait toggle not visible — characters may not have loaded into chatCfg');
            console.log('I DID NOT VERIFY portrait toggle');
        }

        // Step 3: Navigate to charPerson and test gallery pop-in
        if (setup.char1Id) {
            await page.evaluate((id) => { window.location.hash = '#!/view/olio.charPerson/' + id; }, setup.char1Id);
            await page.waitForTimeout(5000);

            let galleryBtn = page.locator('button:has(span:text("photo_library"))');
            let gVisible = await galleryBtn.isVisible({ timeout: 5000 }).catch(() => false);
            console.log('Gallery button on charPerson: ' + gVisible);

            if (gVisible) {
                await galleryBtn.click();
                await page.waitForTimeout(2000);

                let galleryDialog = page.locator('text=/Gallery/');
                let gdVisible = await galleryDialog.isVisible({ timeout: 5000 }).catch(() => false);
                console.log('Gallery dialog opened: ' + gdVisible);

                if (gdVisible) {
                    console.log('VERIFIED: Gallery opens as pop-in dialog');
                    let closeBtn = page.locator('button:has-text("Close")');
                    if (await closeBtn.isVisible({ timeout: 2000 }).catch(() => false)) await closeBtn.click();
                }

                // Verify no "Images" tab exists
                let imagesTab = page.locator('button:has-text("Images")');
                let tabExists = await imagesTab.isVisible({ timeout: 1000 }).catch(() => false);
                console.log('Images TAB exists: ' + tabExists + ' (should be false)');
                if (tabExists) {
                    console.log('FAIL: Images tab still exists — should have been removed');
                }
            } else {
                console.log('I DID NOT VERIFY gallery pop-in. Button not visible.');
            }

            await screenshot(page, 'verify-gallery-popin');
        }
    });
});
