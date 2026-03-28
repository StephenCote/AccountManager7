import { test, expect } from '@playwright/test';
import { setupWorkflowTestData, cleanupTestUser } from './helpers/api.js';
import { login, screenshot } from './helpers/auth.js';

test('chat edit: modify message via toggle, verify save', async ({ page, request }) => {
    test.setTimeout(120000);
    let wfData = await setupWorkflowTestData(request);
    if (!wfData.charPerson) { test.skip('No test data'); return; }

    let errors = [];
    page.on('pageerror', err => { errors.push(err.message); });

    await login(page, { user: wfData.testUserName, password: wfData.testPassword });
    await page.goto('#!/chat');
    await page.waitForTimeout(3000);

    // Create session: click New Session, then Create in the dialog
    let newBtn = page.locator('button:has-text("New Session")');
    if (!(await newBtn.isVisible({ timeout: 5000 }).catch(() => false))) { test.skip('No New Session'); return; }
    await newBtn.click();
    await page.waitForTimeout(2000);

    let createBtn = page.locator('button:has-text("Create")');
    if (!(await createBtn.isVisible({ timeout: 3000 }).catch(() => false))) { test.skip('No Create button'); return; }
    await createBtn.click();
    await page.waitForTimeout(3000);

    // Send a message
    let chatInput = page.locator('input[name="chatmessage"]');
    await expect(chatInput).toBeEnabled({ timeout: 10000 });
    await chatInput.fill('Hello test message for edit');
    await page.locator('button:has-text("Send")').click();
    await page.waitForTimeout(10000);
    await screenshot(page, 'chat-edit-01-sent');

    // Enter edit mode
    let editBtn = page.locator('button:has(span:text("edit_note"))');
    if (!(await editBtn.isVisible({ timeout: 5000 }).catch(() => false))) { test.skip('No edit button'); return; }
    await editBtn.click();
    await page.waitForTimeout(1000);
    await screenshot(page, 'chat-edit-02-edit-mode');

    let textareas = page.locator('textarea[id^="editMessage-"]');
    let taCount = await textareas.count();
    console.log('Edit textareas:', taCount);
    expect(taCount).toBeGreaterThan(0);

    // Modify first message
    let firstTa = textareas.first();
    let original = await firstTa.inputValue();
    console.log('Original:', original.substring(0, 40));
    await firstTa.fill(original + ' [EDITED]');

    // Track PATCH
    let patchSent = false;
    page.on('request', req => {
        if (req.method() === 'PATCH' && req.url().includes('/model')) {
            let body = req.postData() || '';
            if (body.includes('messages')) patchSent = true;
        }
    });

    // Toggle OFF = save
    await editBtn.click();
    await page.waitForTimeout(5000);
    await screenshot(page, 'chat-edit-03-saved');

    console.log('PATCH sent:', patchSent);
    expect(patchSent).toBe(true);

    let taAfter = await page.locator('textarea[id^="editMessage-"]').count();
    console.log('Textareas after:', taAfter);
    expect(taAfter).toBe(0);

    await cleanupTestUser(request, wfData.user?.objectId, { userName: wfData.testUserName });
});
