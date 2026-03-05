import { test, expect } from '@playwright/test';
import { login, screenshot } from './helpers/auth.js';

test.describe('Login flow', () => {

    test('login page loads with org selector', async ({ page }) => {
        await page.goto('/');
        await expect(page.locator('select#selOrganizationList')).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'login-page');
    });

    test('login with valid credentials reaches dashboard', async ({ page }) => {
        await login(page);
        // Should see the panel dashboard
        await expect(page.locator('.panel-container').first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'dashboard-after-login');
    });

    test('login with invalid credentials shows error', async ({ page }) => {
        await page.goto('/');
        await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 10000 });
        await page.locator('select#selOrganizationList').selectOption('/Development');
        await page.locator('input[name="userName"]').fill('baduser');
        await page.locator('input[name="password"]').fill('wrongpassword');
        await page.locator('button:has-text("Login")').click();
        // Should see an error toast or stay on login page
        await expect(page.locator('.toast-box').first()).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'login-error');
    });

    test('logout returns to login page', async ({ page }) => {
        await login(page);
        await page.locator('button[title="Logout"]').click();
        await expect(page.locator('select#selOrganizationList')).toBeVisible({ timeout: 10000 });
        await screenshot(page, 'after-logout');
    });

});
