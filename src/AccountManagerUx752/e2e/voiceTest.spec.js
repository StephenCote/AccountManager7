import { test, expect } from '@playwright/test';
import { setupWorkflowTestData, cleanupTestUser } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';

test('voice profile: select, save, reload, verify persistence', async ({ page, request }) => {
    let wfData = await setupWorkflowTestData(request);
    if (!wfData.charPerson) { test.skip('No test data'); return; }

    let errors = [];
    page.on('pageerror', err => { errors.push(err.message); });
    page.on('console', msg => {
        if (msg.text().includes('[save]') || msg.text().includes('[picker]'))
            console.log('PAGE:', msg.text());
        if (msg.type() === 'error' && !msg.text().includes('404') && !msg.text().includes('Favorites'))
            console.log('ERR:', msg.text());
    });

    await login(page, { user: wfData.testUserName, password: wfData.testPassword });

    // Create a voice object via the app's REST API (as the logged-in user)
    let voiceCreated = await page.evaluate(async () => {
        try {
            // Ensure ~/Voices directory
            let dirResp = await fetch('/AccountManagerService7/rest/path/make/auth.group/DATA/' + encodeURIComponent('B64-' + btoa('~/Voices')), { credentials: 'include' });
            let dir = null;
            try { dir = await dirResp.json(); } catch(e2) { return { error: 'dir parse failed: ' + dirResp.status }; }
            if (!dir || !dir.id) return { error: 'no dir id', dir: dir };

            // Create voice object
            let voice = { schema: 'identity.voice', name: 'piper-kristin', engine: 'piper', speaker: 'en_US-kristin-medium', speed: 1.2, groupId: dir.id, groupPath: dir.path };
            let createResp = await fetch('/AccountManagerService7/rest/model', {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(voice)
            });
            let created = null;
            try { created = await createResp.json(); } catch(e2) { return { error: 'create parse failed: ' + createResp.status }; }
            return created && created.objectId ? created : { error: 'no objectId', created: created, status: createResp.status };
        } catch(e) { return { error: e.message }; }
    });
    console.log('Voice created:', voiceCreated ? (voiceCreated.name || JSON.stringify(voiceCreated)) : 'null');
    if (!voiceCreated || voiceCreated.error) { console.log('Create failed:', JSON.stringify(voiceCreated)); test.skip('Could not create voice object'); return; }

    await page.goto('#!/view/olio.charPerson/' + wfData.charPerson.objectId);
    await page.waitForTimeout(4000);

    // Navigate to Profile tab
    let profileTab = page.locator('button:has-text("Profile")');
    await expect(profileTab).toBeVisible({ timeout: 5000 });
    await profileTab.click();
    await page.waitForTimeout(2000);

    // Click voice picker
    let voiceText = page.locator('span.cursor-pointer:near(label:has-text("Voice"))').first();
    await expect(voiceText).toBeVisible({ timeout: 3000 });
    console.log('Voice before:', await voiceText.textContent());

    await voiceText.click();
    await page.waitForTimeout(3000);

    let picker = page.locator('.am7-picker-overlay');
    await expect(picker).toBeVisible({ timeout: 3000 });

    let pickerRows = picker.locator('tbody tr, tr:has(td)');
    let rowCount = await pickerRows.count();
    console.log('Picker rows:', rowCount);
    expect(rowCount).toBeGreaterThan(0);

    // Select the voice we created
    await pickerRows.first().click();
    await page.waitForTimeout(500);
    let confirmBtn = picker.locator('button:has(span:text("check"))');
    await expect(confirmBtn).toBeVisible({ timeout: 2000 });
    await confirmBtn.click();
    await page.waitForTimeout(2000);
    await screenshot(page, 'voice-03-selected');

    let afterValue = await voiceText.textContent().catch(() => '');
    console.log('Voice after selection:', afterValue);
    expect(afterValue).not.toBe('(none)');

    // Monitor network for PATCH requests
    let patchRequests = [];
    page.on('request', req => {
        if (req.method() === 'PATCH' || (req.method() === 'POST' && req.url().includes('/model'))) {
            let body = req.postData();
            if (body && (body.includes('voice') || body.includes('profile'))) {
                patchRequests.push({ url: req.url(), method: req.method(), body: body.substring(0, 500) });
            }
        }
    });

    // Save
    let saveBtn = page.locator('button:has(span:text("save"))');
    await expect(saveBtn).toBeVisible({ timeout: 3000 });
    await saveBtn.click();
    await page.waitForTimeout(3000);
    await screenshot(page, 'voice-04-saved');
    console.log('PATCH requests during save:', JSON.stringify(patchRequests, null, 2));

    // Check voice still shows after save (not cleared)
    let postSaveValue = await voiceText.textContent().catch(() => '');
    console.log('Voice after save:', postSaveValue);

    // Reload and verify persistence
    await page.goto('#!/view/olio.charPerson/' + wfData.charPerson.objectId);
    await page.waitForTimeout(4000);
    await page.locator('button:has-text("Profile")').click();
    await page.waitForTimeout(2000);
    await screenshot(page, 'voice-05-reloaded');

    let reloadedValue = await page.locator('span.cursor-pointer:near(label:has-text("Voice"))').first().textContent().catch(() => '');
    console.log('Voice after reload:', reloadedValue);
    expect(reloadedValue).toBe(afterValue);

    let realErrors = errors.filter(e => !e.includes('404'));
    if (realErrors.length > 0) {
        console.log('ERRORS:');
        realErrors.forEach(e => console.log('  -', e));
    }
    expect(realErrors.length).toBe(0);

    await cleanupTestUser(request, wfData.user?.objectId, { userName: wfData.testUserName });
});
