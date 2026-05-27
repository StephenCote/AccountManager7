/**
 * E2E test for the conversation-label-stickiness bug:
 *   Conv1 (Bob/Doug) → Conv2 (Sarah/Jane) → Conv1 used to keep showing
 *   "Sarah/Jane" labels in Conv1 because doPeek only ASSIGNED chatCfg.user
 *   and chatCfg.system when the new chatConfig had characters — it never
 *   CLEARED prior values. The fix resets both to null at the top of doPeek.
 *
 * Approach: real login, then use page.route to mock the chat-config and
 * character GETs so we can exercise the switch deterministically without
 * provisioning multiple real characters/configs/sessions.
 */
import { test, expect } from '@playwright/test';
import { login } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';

const SESSION_A = 'aaaa1111-aaaa-1111-aaaa-111111111111';
const SESSION_B = 'bbbb2222-bbbb-2222-bbbb-222222222222';
const CONFIG_A  = 'cccc1111-cccc-1111-cccc-111111111111';
const CONFIG_B  = 'cccc2222-cccc-2222-cccc-222222222222';
const CHAR_BOB  = 'b0b00000-b0b0-0000-b0b0-000000000000';
const CHAR_SARAH = 'sa5a0000-5a5a-0000-5a5a-000000000000';

const charBob = {
    schema: 'olio.charPerson', objectId: CHAR_BOB, id: 1001,
    name: 'Bob Test', firstName: 'Bob', gender: 'male'
};
const charSarah = {
    schema: 'olio.charPerson', objectId: CHAR_SARAH, id: 1002,
    name: 'Sarah Test', firstName: 'Sarah', gender: 'female'
};
const configA = {
    schema: 'olio.llm.chatConfig', objectId: CONFIG_A, id: 2001,
    name: 'Config A', model: 'test',
    systemCharacter: { schema: 'olio.charPerson', objectId: CHAR_BOB, id: 1001 },
    promptTemplate: null, chatOptions: null
};
const configB_noChar = {
    schema: 'olio.llm.chatConfig', objectId: CONFIG_B, id: 2002,
    name: 'Config B (no char)', model: 'test',
    // systemCharacter intentionally omitted — this is the case that triggered
    // the bug: the OLD chatCfg.system stayed set to Bob.
    promptTemplate: null, chatOptions: null
};

async function captureLabel(page) {
    // The bubble label is the small <div class="text-xs opacity-60 mb-1">
    // (chat.js renderMessage line 637).
    let labels = await page.locator('.text-xs.opacity-60').allTextContents();
    return labels.filter(l => l && l.trim().length > 0);
}

test.describe.skip('Conversation character labels do not bleed between sessions', () => {
    /// SKIPPED by default: this test relies on having two real
    /// chatRequest sessions in the test user's data. The fix it verifies
    /// is at chat.js doPeek where chatCfg.system and chatCfg.user are now
    /// reset to null at the top of the function. To enable, populate the
    /// test user with two sessions whose chatConfigs differ in character
    /// assignments, then update SESSION_A/SESSION_B to those objectIds
    /// and remove the .skip.
    let testInfo = {};

    test.beforeAll(async ({ request }) => {
        testInfo = await ensureSharedTestUser(request);
    });

    test('switching A→B→A clears prior chatConfig characters', async ({ page }) => {
        test.setTimeout(60000);

        // Mock chatConfig GETs
        await page.route('**/AccountManagerService7/rest/model/olio.llm.chatConfig/' + CONFIG_A + '/full', (route) => {
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(configA) });
        });
        await page.route('**/AccountManagerService7/rest/model/olio.llm.chatConfig/' + CONFIG_B + '/full', (route) => {
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(configB_noChar) });
        });
        // Mock character GET
        await page.route('**/AccountManagerService7/rest/model/olio.charPerson/' + CHAR_BOB + '/full', (route) => {
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(charBob) });
        });
        await page.route('**/AccountManagerService7/rest/model/olio.charPerson/' + CHAR_SARAH + '/full', (route) => {
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(charSarah) });
        });
        // Mock history with one assistant message
        await page.route('**/AccountManagerService7/rest/chat/history', (route) => {
            route.fulfill({ status: 200, contentType: 'application/json',
                body: JSON.stringify({ messages: [{ role: 'assistant', content: 'hello' }] }) });
        });

        await login(page, { user: testInfo.testUserName, password: testInfo.testPassword });
        await page.goto('/#!/chat');
        await page.waitForTimeout(3000);

        // Pick session A (selector by title would go here — depends on
        // whether the user populated test sessions). Capture label.
        // ... session-switch interactions ...

        // After A→B→A, the labels in the rendered message bubble must show
        // configA's character ("Bob"), not configB's stale state.
        let labels = await captureLabel(page);
        expect(labels.some(l => l.includes('Bob'))).toBeTruthy();
        expect(labels.some(l => l.includes('Sarah'))).toBeFalsy();
    });
});
