/**
 * Access Requests E2E tests — Tests navigation, tab switching, and new request form.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot } from './helpers/auth.js';

/**
 * Navigate to the Access Requests page via the aside menu button.
 */
async function goToAccessRequests(page) {
    await page.waitForFunction(() => {
        let buttons = Array.from(document.querySelectorAll('button'));
        return buttons.some(b => b.textContent.trim().includes('Access Requests'));
    }, { timeout: 10000 });

    await page.evaluate(() => {
        let buttons = Array.from(document.querySelectorAll('button'));
        let btn = buttons.find(b => b.textContent.trim().includes('Access Requests'));
        if (btn) btn.click();
    });
    await page.waitForTimeout(1500);
}

test.describe('Access Requests feature', () => {

    test('access requests page loads after login', async ({ page }) => {
        await login(page);
        await goToAccessRequests(page);

        await expect(page.locator('h2:has-text("Access Requests")')).toBeVisible({ timeout: 10000 });
        await expect(page.locator('text=My Requests')).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'access-requests-page');
    });

    test('tab switching works', async ({ page }) => {
        await login(page);
        await goToAccessRequests(page);

        // Default tab is "My Requests"
        await expect(page.locator('text=My Requests')).toBeVisible({ timeout: 5000 });

        // Switch to "Pending My Approval"
        await page.locator('button:has-text("Pending My Approval")').click();
        await page.waitForTimeout(1000);

        // Switch to "All Requests"
        await page.locator('button:has-text("All Requests")').click();
        await page.waitForTimeout(1000);

        // Switch back to "My Requests"
        await page.locator('button:has-text("My Requests")').click();
        await page.waitForTimeout(1000);

        await screenshot(page, 'access-requests-tabs');
    });

    test('new request form opens and shows type selector', async ({ page }) => {
        await login(page);
        await goToAccessRequests(page);

        // Click "New Request" button
        await page.locator('button:has-text("+ New Request")').click();
        await page.waitForTimeout(500);

        // Should see the form
        await expect(page.locator('text=New Access Request')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Request Type')).toBeVisible({ timeout: 3000 });
        await expect(page.locator('text=Available')).toBeVisible({ timeout: 3000 });
        await expect(page.locator('text=Cart (0)')).toBeVisible({ timeout: 3000 });
        await expect(page.locator('text=Justification')).toBeVisible({ timeout: 3000 });

        await screenshot(page, 'access-requests-new-form');
    });

    test('cancel hides the new request form', async ({ page }) => {
        await login(page);
        await goToAccessRequests(page);

        await page.locator('button:has-text("+ New Request")').click();
        await page.waitForTimeout(500);
        await expect(page.locator('text=New Access Request')).toBeVisible({ timeout: 5000 });

        // Click Cancel
        await page.locator('button:has-text("Cancel")').click();
        await page.waitForTimeout(500);

        // Form should be hidden, "New Request" button visible again
        await expect(page.locator('button:has-text("+ New Request")')).toBeVisible({ timeout: 3000 });
    });
});
