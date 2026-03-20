/**
 * Extended Playwright test fixture with automatic console error capture.
 *
 * Usage — replace '@playwright/test' import with this:
 *   import { test, expect } from './helpers/fixtures.js';
 *
 * Every test automatically captures browser console errors/warnings
 * and fails if unexpected errors are found after the test body runs.
 */
import { test as base, expect } from '@playwright/test';
import { captureConsole, assertNoConsoleErrors, consoleSummary } from './console.js';

export const test = base.extend({
    /**
     * Override the page fixture to automatically capture console output
     * and assert no errors after each test.
     */
    page: async ({ page }, use, testInfo) => {
        let capture = captureConsole(page, { verbose: false });

        // Run the test
        await use(page);

        // After test: attach console log to test report
        if (capture.all.length > 0) {
            await testInfo.attach('browser-console', {
                body: capture.all.map(e => `[${e.type}] ${e.text}`).join('\n'),
                contentType: 'text/plain'
            });
        }

        // Assert no unexpected console errors
        assertNoConsoleErrors(capture);
    }
});

export { expect };
