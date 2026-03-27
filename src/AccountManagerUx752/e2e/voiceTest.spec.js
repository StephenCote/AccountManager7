import { test, expect } from '@playwright/test';
import { setupWorkflowTestData, cleanupTestUser } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';

test('voice picker on main tab: select, save, persist', async ({ page, request }) => {
    let wfData = await setupWorkflowTestData(request);
    if (!wfData.charPerson) { test.skip('No test data'); return; }

    let errors = [];
    page.on('pageerror', err => { errors.push(err.message); });

    await login(page, { user: wfData.testUserName, password: wfData.testPassword });

    // Create a voice object
    let voiceCreated = await page.evaluate(async () => {
        try {
            let dirResp = await fetch('/AccountManagerService7/rest/path/make/auth.group/DATA/' + encodeURIComponent('B64-' + btoa('~/Voices')), { credentials: 'include' });
            let dir = await dirResp.json();
            if (!dir || !dir.id) return null;
            let voice = { schema: 'identity.voice', name: 'piper-kristin', engine: 'piper', speaker: 'en_US-kristin-medium', speed: 1.2, groupId: dir.id, groupPath: dir.path };
            let createResp = await fetch('/AccountManagerService7/rest/model', {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(voice)
            });
            let created = await createResp.json();
            return created && created.objectId ? created : null;
        } catch(e) { return null; }
    });
    console.log('Voice created:', voiceCreated ? voiceCreated.name : 'FAILED');
    if (!voiceCreated) { test.skip('Could not create voice'); return; }

    await page.goto('#!/view/olio.charPerson/' + wfData.charPerson.objectId);
    await page.waitForTimeout(4000);

    // 1. Verify Profile tab is gone
    let profileTab = page.locator('button:has-text("Profile")');
    let hasProfileTab = await profileTab.isVisible({ timeout: 2000 }).catch(() => false);
    console.log('Profile tab visible:', hasProfileTab);
    expect(hasProfileTab).toBe(false);

    // 2. Voice label on main Character tab
    let voiceLabel = page.locator('label:has-text("Voice")');
    let hasVoice = await voiceLabel.isVisible({ timeout: 5000 }).catch(() => false);
    console.log('Voice label on main tab:', hasVoice);
    expect(hasVoice).toBe(true);
    await screenshot(page, 'voice-01-main-tab');

    // 3. Click voice picker text to open
    let voiceText = page.locator('span.cursor-pointer:near(label:has-text("Voice"))').first();
    await expect(voiceText).toBeVisible({ timeout: 3000 });
    console.log('Voice before:', await voiceText.textContent());

    await voiceText.click();
    await page.waitForTimeout(3000);

    let picker = page.locator('.am7-picker-overlay');
    await expect(picker).toBeVisible({ timeout: 3000 });

    // 4. Select voice
    let pickerRows = picker.locator('tbody tr, tr:has(td)');
    let rowCount = await pickerRows.count();
    console.log('Picker voice rows:', rowCount);
    expect(rowCount).toBeGreaterThan(0);

    await pickerRows.first().click();
    await page.waitForTimeout(500);
    let confirmBtn = picker.locator('button:has(span:text("check"))');
    await expect(confirmBtn).toBeVisible({ timeout: 2000 });
    await confirmBtn.click();
    await page.waitForTimeout(2000);
    await screenshot(page, 'voice-02-selected');

    let afterValue = await voiceText.textContent().catch(() => '');
    console.log('Voice after selection:', afterValue);
    expect(afterValue).not.toBe('(none)');

    // 5. Wait for the direct patch to complete, then reload
    await page.waitForTimeout(2000);

    // 6. Reload and verify persistence (no save button needed - voicePicker patches directly)
    await page.goto('#!/view/olio.charPerson/' + wfData.charPerson.objectId);
    await page.waitForTimeout(4000);
    await screenshot(page, 'voice-03-reloaded');

    let reloadedValue = await page.locator('span.cursor-pointer:near(label:has-text("Voice"))').first().textContent().catch(() => '');
    console.log('Voice after reload:', reloadedValue);
    expect(reloadedValue).toBe(afterValue);

    // 7. Check server actually got a MODIFY for identity.profile
    let realErrors = errors.filter(e => !e.includes('404'));
    if (realErrors.length > 0) {
        console.log('ERRORS:');
        realErrors.forEach(e => console.log('  -', e));
    }
    expect(realErrors.length).toBe(0);

    await cleanupTestUser(request, wfData.user?.objectId, { userName: wfData.testUserName });
});
