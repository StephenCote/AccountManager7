/**
 * Phase 12d — WCAG 2.1 AA Accessibility E2E tests
 * Uses axe-core to scan pages for accessibility violations.
 */
import { test, expect } from './helpers/fixtures.js';
import { login } from './helpers/auth.js';
import AxeBuilder from '@axe-core/playwright';

test.describe('Accessibility — axe-core audit', () => {

    test('login page has no critical a11y violations', async ({ page }) => {
        await page.goto('/');
        await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 10000 });

        const results = await new AxeBuilder({ page })
            .withTags(['wcag2a', 'wcag2aa'])
            .analyze();

        let critical = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious');
        if (critical.length > 0) {
            console.log('Login a11y violations:', JSON.stringify(critical.map(v => ({
                id: v.id, impact: v.impact, description: v.description,
                nodes: v.nodes.length
            })), null, 2));
        }
        expect(critical.length).toBe(0);
    });

    test('dashboard has no critical a11y violations', async ({ page }) => {
        await login(page);
        await page.locator('.panel-container').first().waitFor({ state: 'visible', timeout: 10000 });

        const results = await new AxeBuilder({ page })
            .withTags(['wcag2a', 'wcag2aa'])
            .analyze();

        let critical = results.violations.filter(v => v.impact === 'critical');
        if (critical.length > 0) {
            console.log('Dashboard a11y violations:', JSON.stringify(critical.map(v => ({
                id: v.id, impact: v.impact, description: v.description,
                nodes: v.nodes.length
            })), null, 2));
        }
        expect(critical.length).toBe(0);
    });

    test('main navigation has correct ARIA attributes', async ({ page }) => {
        await login(page);
        await page.locator('.panel-container').first().waitFor({ state: 'visible', timeout: 10000 });

        // Main nav has aria-label
        let nav = page.locator('nav[aria-label="Main navigation"]');
        await expect(nav).toBeVisible();

        // Toolbar has aria-label
        let toolbar = page.locator('[role="toolbar"][aria-label="Main toolbar"]');
        await expect(toolbar).toBeVisible();

        // Main content area is a <main> element
        let main = page.locator('main[role="main"]');
        await expect(main).toBeVisible();
    });

    test('icon-only buttons have aria-label', async ({ page }) => {
        await login(page);
        await page.locator('.panel-container').first().waitFor({ state: 'visible', timeout: 10000 });

        // Home button
        let homeBtn = page.locator('button[aria-label="Home"]');
        await expect(homeBtn).toBeVisible();

        // Logout button
        let logoutBtn = page.locator('button[aria-label="Logout"]');
        await expect(logoutBtn).toBeVisible();

        // Dark mode toggle
        let darkBtn = page.locator('button[aria-label="Toggle dark mode"]');
        await expect(darkBtn).toBeVisible();
    });

    test('toast container has aria-live when visible', async ({ page }) => {
        await page.goto('/');
        await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 10000 });

        // Trigger a toast by attempting bad login
        await page.locator('select#selOrganizationList').selectOption('/Development');
        await page.locator('input[name="userName"]').fill('baduser');
        await page.locator('input[name="password"]').fill('wrong');
        await page.locator('button:has-text("Login")').click();
        await page.locator('.toast-container').waitFor({ state: 'visible', timeout: 10000 });

        let toastContainer = page.locator('.toast-container[aria-live="polite"]');
        await expect(toastContainer).toBeVisible();
    });
});
