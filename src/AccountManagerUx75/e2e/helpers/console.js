/**
 * Console capture utilities for Playwright E2E tests.
 *
 * Usage option 1 — Per-test manual capture:
 *   import { captureConsole, assertNoConsoleErrors } from './helpers/console.js';
 *   test('my test', async ({ page }) => {
 *       let logs = captureConsole(page);
 *       // ... test actions ...
 *       assertNoConsoleErrors(logs);
 *   });
 *
 * Usage option 2 — Automatic via extended test fixture (preferred):
 *   import { test } from './helpers/fixtures.js';
 *   test('my test', async ({ page }) => { ... });
 *   // Console errors automatically checked after each test
 */
import { expect } from '@playwright/test';

// Known warnings that are safe to ignore (add patterns as needed)
const IGNORED_PATTERNS = [
    /\[vite\]/,                          // Vite HMR messages
    /Download the React DevTools/,        // React dev tools suggestion
    /\[HMR\]/,                           // Hot module reload
    /favicon\.ico.*404/,                 // Missing favicon
    /ERR_CONNECTION_REFUSED/,            // Backend not ready during startup
];

/**
 * Start capturing console messages on a page.
 * Call this BEFORE page.goto() for full coverage.
 * @param {import('@playwright/test').Page} page
 * @param {object} opts
 * @param {boolean} opts.verbose - Log all messages to stdout (default: false)
 * @returns {{ errors: Array, warnings: Array, all: Array }}
 */
export function captureConsole(page, opts = {}) {
    let capture = { errors: [], warnings: [], all: [] };

    page.on('console', msg => {
        let entry = {
            type: msg.type(),
            text: msg.text(),
            location: msg.location(),
            timestamp: Date.now()
        };
        capture.all.push(entry);

        if (msg.type() === 'error') {
            capture.errors.push(entry);
        } else if (msg.type() === 'warning') {
            capture.warnings.push(entry);
        }

        if (opts.verbose) {
            let prefix = msg.type() === 'error' ? 'ERR' : msg.type() === 'warning' ? 'WRN' : 'LOG';
            console.log(`  [${prefix}] ${msg.text()}`);
        }
    });

    page.on('pageerror', err => {
        let entry = {
            type: 'pageerror',
            text: err.message,
            stack: err.stack,
            timestamp: Date.now()
        };
        capture.errors.push(entry);
        capture.all.push(entry);

        if (opts.verbose) {
            console.log(`  [PAGEERROR] ${err.message}`);
        }
    });

    return capture;
}

/**
 * Filter out known safe warnings/errors from captured logs.
 * @param {Array} entries
 * @returns {Array} entries not matching any ignored pattern
 */
function filterIgnored(entries) {
    return entries.filter(entry => {
        return !IGNORED_PATTERNS.some(pattern => pattern.test(entry.text));
    });
}

/**
 * Assert no unexpected console errors occurred.
 * Call at the end of a test to fail on browser console errors.
 * @param {{ errors: Array, warnings: Array }} capture - from captureConsole()
 * @param {object} opts
 * @param {boolean} opts.failOnWarnings - Also fail on warnings (default: false)
 * @param {RegExp[]} opts.ignore - Additional patterns to ignore
 */
export function assertNoConsoleErrors(capture, opts = {}) {
    let errors = filterIgnored(capture.errors);
    if (opts.ignore) {
        errors = errors.filter(e => !opts.ignore.some(p => p.test(e.text)));
    }

    if (errors.length > 0) {
        let details = errors.map(e => `  [${e.type}] ${e.text}`).join('\n');
        expect(errors, `Unexpected browser console errors:\n${details}`).toHaveLength(0);
    }

    if (opts.failOnWarnings) {
        let warnings = filterIgnored(capture.warnings);
        if (opts.ignore) {
            warnings = warnings.filter(e => !opts.ignore.some(p => p.test(e.text)));
        }
        if (warnings.length > 0) {
            let details = warnings.map(e => `  [${e.type}] ${e.text}`).join('\n');
            expect(warnings, `Unexpected browser console warnings:\n${details}`).toHaveLength(0);
        }
    }
}

/**
 * Get a summary of captured console output for reporting.
 * @param {{ errors: Array, warnings: Array, all: Array }} capture
 * @returns {string}
 */
export function consoleSummary(capture) {
    let real = filterIgnored(capture.errors);
    if (real.length === 0 && capture.warnings.length === 0) {
        return `Console: ${capture.all.length} messages, 0 errors, 0 warnings`;
    }
    let lines = [`Console: ${capture.all.length} messages, ${real.length} errors, ${capture.warnings.length} warnings`];
    for (let e of real) {
        lines.push(`  ERROR: ${e.text}`);
    }
    return lines.join('\n');
}
