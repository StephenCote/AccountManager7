/**
 * Navigation through the real UI. Chat, Picture Book, ChapBook, Magic 8, the games and the dev Tests harness
 * are App Panel cards (top bar `apps` button → #!/app → card), not top-bar or flyout buttons.
 */
import { expect } from '@playwright/test';

/** Click the top bar's App Panel button, then the App Panel card for `route` (e.g. '/chat'). */
export async function openAppTool(page, route) {
    const appBtn = page.locator('button[data-top="app"]');
    await expect(appBtn, 'top bar App Panel button').toBeVisible({ timeout: 10000 });
    await appBtn.click();
    const card = page.locator('[data-tool="' + route + '"]');
    await expect(card, 'App Panel card for ' + route).toBeVisible({ timeout: 10000 });
    await card.click();
}

/** Open Chat the way a user does now: top bar → App Panel → Chat. Waits for the #!/chat route. */
export async function openChat(page) {
    await openAppTool(page, '/chat');
    await page.waitForURL(/.*#!\/chat/, { timeout: 10000 });
}
