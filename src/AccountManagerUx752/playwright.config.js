import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60000,
  expect: { timeout: 10000 },
  workers: 4,
  retries: 1,
  use: {
    baseURL: 'https://localhost:8899',
    ignoreHTTPSErrors: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // Phase 15c: Firefox cross-browser validation
    // Phase 7 perf fixes (specific CSS transitions, WebSocket redraw debouncing) apply here.
    // CDP-based tests (WebAuthn virtual authenticator) run chromium-only.
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } }
  ],
  webServer: {
    command: 'npm run dev',
    port: 8899,
    reuseExistingServer: true,
  },
  outputDir: 'e2e/results',
});
