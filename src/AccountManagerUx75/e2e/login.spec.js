import { test, expect } from '@playwright/test';

test('login page loads', async ({ page }) => {
    await page.goto('/');
    // Should redirect to login (sig) route — wait for the form to appear
    await expect(page.locator('select#selOrganizationList')).toBeVisible({ timeout: 10000 });
});
