import { defineConfig, devices } from '@playwright/test';

// Where the Ux is served from.
//
// DEFAULT IS UNCHANGED: the local Vite dev server on 8899, started by the `webServer` block below.
// Nothing changes for that workflow when PLAYWRIGHT_BASE_URL is unset.
//
// Set PLAYWRIGHT_BASE_URL to test an already-running deployment instead — notably the isolated Docker
// stack from docker-compose.test.yml, whose nginx serves BOTH the Ux and /AccountManagerService7 on one
// origin (docker/nginx.conf:39-63), published on host 9443 so it cannot collide with a local Tomcat on
// 8443 or a local Vite on 8899:
//   PLAYWRIGHT_BASE_URL=https://localhost:9443 npx playwright test ... --workers=1
// When it is set, `webServer` is omitted entirely so no Vite dev server is started on 8899.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const USE_EXTERNAL_SERVER = !!process.env.PLAYWRIGHT_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  timeout: 60000,
  expect: { timeout: 10000 },
  workers: 4,
  retries: 1,
  use: {
    baseURL: BASE_URL,
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
  webServer: USE_EXTERNAL_SERVER ? undefined : {
    command: 'npm run dev',
    port: 8899,
    reuseExistingServer: true,
  },
  outputDir: 'e2e/results',
});
