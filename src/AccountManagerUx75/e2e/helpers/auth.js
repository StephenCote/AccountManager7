import fs from 'fs';
import path from 'path';

/**
 * Log into the application via the UI login form.
 * @param {import('@playwright/test').Page} page
 * @param {object} opts
 * @param {string} opts.org - Organization path (default: '/Development')
 * @param {string} opts.user - Username (default: 'admin')
 * @param {string} opts.password - Password (default: 'password')
 */
export async function login(page, opts = {}) {
    const org = opts.org || '/Development';
    const user = opts.user || 'admin';
    const password = opts.password || 'password';

    await page.goto('/');

    // Wait for the login form's organization selector
    await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 10000 });

    // Select organization
    await page.locator('select#selOrganizationList').selectOption(org);

    // Fill credentials — form fields have name attributes from model
    await page.locator('input[name="userName"]').fill(user);
    await page.locator('input[name="password"]').fill(password);

    // Click the Login button (rendered by form command system)
    await page.locator('button:has-text("Login")').click();

    // Wait for navigation to main dashboard — Mithril uses #!/ hash prefix
    await page.waitForURL(/.*#!\/main/, { timeout: 15000 });
}

/**
 * Take a named screenshot for visual reference.
 * @param {import('@playwright/test').Page} page
 * @param {string} name - Screenshot filename (without extension)
 */
export async function screenshot(page, name) {
    let dir = path.resolve('e2e/screenshots');
    fs.mkdirSync(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, name + '.png'), fullPage: true });
}
