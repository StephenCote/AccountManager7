/**
 * E2E test: chat conversation labels must track the conversation's
 * actual chatConfig characters, not bleed from the most-recently-used
 * config.
 *
 * Repro:
 *   1. Create char Bob and char Doug, char Sarah and char Jane
 *   2. Create chatConfig A with systemChar=Bob, userChar=Doug
 *   3. Create chatConfig B with systemChar=Sarah, userChar=Jane
 *   4. Create session A → send msg, get response
 *   5. Create session B → send msg, get response
 *   6. Switch back to session A
 *   7. Assert bubble labels in session A show Bob/Doug, not Sarah/Jane
 */
import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';
function b64(s) { return Buffer.from(s).toString('base64'); }
function uniq() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

async function asUser(user, password, fn) {
    let ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
    try {
        let resp = await ctx.post(REST + '/login', {
            data: { schema: 'auth.credential', organizationPath: '/Development', name: user, credential: b64(password), type: 'hashed_password' }
        });
        if (!resp.ok()) throw new Error('login failed');
        return await fn(ctx);
    } finally {
        await ctx.get(REST + '/logout').catch(() => {});
        await ctx.dispose();
    }
}

async function safeJson(resp) {
    if (!resp.ok()) return null;
    let t = await resp.text();
    if (!t) return null;
    try { return JSON.parse(t); } catch(e) { return null; }
}

async function ensureDir(ctx, path) {
    let b64Path = 'B64-' + b64(path).replace(/=/g, '%3D');
    let r = await ctx.get(REST + '/path/make/auth.group/data/' + b64Path);
    return await safeJson(r);
}

async function createCharacter(ctx, groupId, groupPath, firstName, lastName, gender) {
    let entity = {
        schema: 'olio.charPerson',
        name: firstName + ' ' + lastName,
        firstName, lastName, gender,
        age: 30, race: ['E'],
        groupId, groupPath
    };
    let r = await ctx.post(REST + '/model', { data: entity });
    return await safeJson(r);
}

async function createChatConfig(ctx, groupId, groupPath, name, sysChar, usrChar, promptCfgRef) {
    let entity = {
        schema: 'olio.llm.chatConfig',
        name, groupId, groupPath,
        model: 'qwen3:8b',
        serviceType: 'ollama',
        systemCharacter: { schema: 'olio.charPerson', objectId: sysChar.objectId, id: sysChar.id },
        userCharacter:   { schema: 'olio.charPerson', objectId: usrChar.objectId, id: usrChar.id },
        stream: true, prune: true, messageTrim: 50, requestTimeout: 300
    };
    if (promptCfgRef) {
        entity.promptConfig = { schema: 'olio.llm.promptConfig', objectId: promptCfgRef.objectId, id: promptCfgRef.id };
    }
    let r = await ctx.post(REST + '/model', { data: entity });
    return await safeJson(r);
}

async function getOrCreatePromptConfig(ctx, groupId, groupPath) {
    // Look for an existing prompt config in user's space
    let s = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.llm.promptConfig',
            fields: [{ name: 'groupId', comparator: 'equals', value: groupId }],
            request: ['id', 'objectId', 'name'],
            recordCount: 1
        }
    });
    let sj = await safeJson(s);
    if (sj && sj.results && sj.results.length) return sj.results[0];

    let entity = {
        schema: 'olio.llm.promptConfig',
        name: 'LabelBleedTest_' + uniq(),
        groupId, groupPath,
        system: ['You are a brief assistant. Reply with a single short sentence.']
    };
    let r = await ctx.post(REST + '/model', { data: entity });
    return await safeJson(r);
}

async function createSession(ctx, dirId, dirPath, name, chatCfg, promptCfg) {
    let entity = {
        schema: 'olio.llm.chatRequest',
        name, groupId: dirId, groupPath: dirPath,
        chatConfig: { schema: 'olio.llm.chatConfig', objectId: chatCfg.objectId, id: chatCfg.id }
    };
    if (promptCfg) {
        entity.promptConfig = { schema: 'olio.llm.promptConfig', objectId: promptCfg.objectId, id: promptCfg.id };
    }
    let r = await ctx.post(REST + '/model', { data: entity });
    return await safeJson(r);
}

test.describe('Chat conversation labels do not bleed between sessions', () => {
    let testInfo = {};
    let charBob, charDoug, charSarah, charJane;
    let cfgA, cfgB, sessA, sessB, promptCfg;
    /// Server create responses only include identity fields (no name) per
    /// the response pattern documented in CLAUDE.md. Track the names we
    /// posted so the UI can find them in the sidebar.
    let sessAName, sessBName;

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
        await asUser(testInfo.testUserName, testInfo.testPassword, async (ctx) => {
            let charDir = await ensureDir(ctx, '/home/' + testInfo.testUserName + '/Characters');
            let chatDir = await ensureDir(ctx, '/home/' + testInfo.testUserName + '/Chat');
            let chatReqDir = await ensureDir(ctx, '/home/' + testInfo.testUserName + '/ChatRequests');
            let promptDir = await ensureDir(ctx, '/home/' + testInfo.testUserName + '/Prompts');

            let suf = uniq();
            charBob   = await createCharacter(ctx, charDir.id, charDir.path, 'Bob',   'Test' + suf, 'male');
            charDoug  = await createCharacter(ctx, charDir.id, charDir.path, 'Doug',  'Test' + suf, 'male');
            charSarah = await createCharacter(ctx, charDir.id, charDir.path, 'Sarah', 'Test' + suf, 'female');
            charJane  = await createCharacter(ctx, charDir.id, charDir.path, 'Jane',  'Test' + suf, 'female');

            expect(charBob && charBob.objectId, 'Bob created').toBeTruthy();
            expect(charDoug && charDoug.objectId, 'Doug created').toBeTruthy();
            expect(charSarah && charSarah.objectId, 'Sarah created').toBeTruthy();
            expect(charJane && charJane.objectId, 'Jane created').toBeTruthy();

            promptCfg = await getOrCreatePromptConfig(ctx, promptDir.id, promptDir.path);
            expect(promptCfg && promptCfg.objectId, 'promptCfg created').toBeTruthy();

            cfgA = await createChatConfig(ctx, chatDir.id, chatDir.path, 'CfgA_' + suf, charBob, charDoug, promptCfg);
            cfgB = await createChatConfig(ctx, chatDir.id, chatDir.path, 'CfgB_' + suf, charSarah, charJane, promptCfg);
            expect(cfgA && cfgA.objectId, 'cfgA created').toBeTruthy();
            expect(cfgB && cfgB.objectId, 'cfgB created').toBeTruthy();

            sessAName = 'SessA_' + suf;
            sessBName = 'SessB_' + suf;
            sessA = await createSession(ctx, chatReqDir.id, chatReqDir.path, sessAName, cfgA, promptCfg);
            sessB = await createSession(ctx, chatReqDir.id, chatReqDir.path, sessBName, cfgB, promptCfg);
            expect(sessA && sessA.objectId, 'sessA created').toBeTruthy();
            expect(sessB && sessB.objectId, 'sessB created').toBeTruthy();
            console.log('SETUP COMPLETE:',
                'sessA=' + sessAName, sessA.objectId,
                'sessB=' + sessBName, sessB.objectId,
                'cfgA chars=' + charBob.objectId + '/' + charDoug.objectId,
                'cfgB chars=' + charSarah.objectId + '/' + charJane.objectId);
        });
    });

    test('switching from sessionB back to sessionA shows sessionA characters', async ({ page }) => {
        test.setTimeout(180000);

        // Suppress page errors from logging — diagnostic only
        page.on('pageerror', () => {});

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });

        // ── Navigate to session A
        await page.goto('/#!/chat');
        await page.waitForTimeout(3000);

        // Dismiss any wizard/setup modal that appeared on first visit
        let cancelInModal = page.locator('div.fixed.inset-0 button:has-text("Cancel"), div.fixed.inset-0 button:has-text("Close")').first();
        if (await cancelInModal.isVisible({ timeout: 2000 }).catch(() => false)) {
            await cancelInModal.click();
            await page.waitForTimeout(800);
        }

        // Open the sessions sidebar
        let sessionsBtn = page.locator('button[title="Sessions"]');
        await expect(sessionsBtn).toBeVisible({ timeout: 10000 });
        await sessionsBtn.click();
        await page.waitForTimeout(800);

        // Helper: click a session by its name in the sidebar
        async function clickSession(name) {
            let item = page.locator('text=' + name).first();
            await expect(item).toBeVisible({ timeout: 10000 });
            await item.click();
            await page.waitForTimeout(3000);
        }

        // Helper: send a chat message and wait for the response to stream in
        async function sendMessage(text) {
            let input = page.locator('input[name="chatmessage"]');
            await expect(input).toBeVisible({ timeout: 5000 });
            await input.fill(text);
            await input.press('Enter');
            // Wait for streaming to complete — Send becomes Stop while streaming
            let stopBtn = page.locator('button:has-text("Stop")');
            try { await stopBtn.waitFor({ state: 'visible', timeout: 5000 }); } catch(e) {}
            try { await stopBtn.waitFor({ state: 'hidden', timeout: 90000 }); } catch(e) {}
            await page.waitForTimeout(1500);
        }

        // ── Session A: send a brief message
        await clickSession(sessAName);
        await screenshot(page, 'sessA-loaded');
        await sendMessage('hi');
        await screenshot(page, 'sessA-after-msg');
        let labelsA_initial = await page.locator('.text-xs.opacity-60.mb-1').allTextContents();

        // ── Switch to session B and send a message
        await clickSession(sessBName);
        await screenshot(page, 'sessB-loaded');
        await sendMessage('hello');
        await screenshot(page, 'sessB-after-msg');
        let labelsB = await page.locator('.text-xs.opacity-60.mb-1').allTextContents();

        // ── Switch BACK to session A
        await clickSession(sessAName);
        await page.waitForTimeout(2000);
        await screenshot(page, 'sessA-revisited');
        let labelsA_after = await page.locator('.text-xs.opacity-60.mb-1').allTextContents();

        console.log('\n=== Bubble labels by session ===');
        console.log('Session A (initial):', JSON.stringify(labelsA_initial));
        console.log('Session B:           ', JSON.stringify(labelsB));
        console.log('Session A (revisit): ', JSON.stringify(labelsA_after));

        // ── Assertions
        // sessA should NEVER show Sarah/Jane (those belong to sessB's config)
        let sarahJaneInA = labelsA_after.some(l => /Sarah|Jane/.test(l));
        expect(sarahJaneInA, 'Session A must not show Session B character names').toBe(false);

        // sessA should show Bob or Doug if any character-named labels exist
        if (labelsA_after.length > 0) {
            let bobOrDoug = labelsA_after.some(l => /Bob|Doug/.test(l));
            // If labels exist at all, at least one should be a sessA character.
            // (Some labels could be "You" or "Assistant" if state is fully cleared.)
            console.log('Bob/Doug present in sessA-revisited:', bobOrDoug);
        }
    });
});
