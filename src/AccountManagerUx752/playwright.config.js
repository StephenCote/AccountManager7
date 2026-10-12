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

// Chromium is the gate browser. The Firefox project is opt-in (PLAYWRIGHT_FIREFOX=1): the Firefox
// binary is not installed on the dev box, so a default run that included it produced 424 failures of
// `browserType.launch: Executable doesn't exist` in the 2026-10-10 full gate — noise, not findings.
const INCLUDE_FIREFOX = process.env.PLAYWRIGHT_FIREFOX === '1';

// 2 workers by default (PLAYWRIGHT_WORKERS overrides; the CLI --workers flag still wins). Four
// workers against the single-container Docker stack contended enough to deadlock Postgres during
// the 2026-10-10 full gate; LLM/SD specs must still be run with --workers=1 (see CLAUDE.md).
const WORKERS = process.env.PLAYWRIGHT_WORKERS ? Number(process.env.PLAYWRIGHT_WORKERS) : 2;

// Vite-source specs: the ones that `import('/src/...')` inside page.evaluate. The Docker nginx serves
// the built dist, which has no /src/, so they can only run against a Vite dev server. They cannot share
// the 8899 server when the gate targets Docker either: src/core/config.js:18-21 points any page served
// from port 8899 at https://localhost:8443 — the LOCAL Tomcat, not the stack under test. So when
// PLAYWRIGHT_BASE_URL is set they run in their own project against a second Vite on 8898 that proxies
// to the Docker stack (its origin is in CORS_ALLOWED_ORIGINS, docker-compose.test.yml):
//   AM7_BACKEND=https://127.0.0.1:9443 npx vite --port 8898
// PLAYWRIGHT_VITE_URL overrides that default. With PLAYWRIGHT_BASE_URL unset both projects use 8899.
// Membership is by grep: `rg -l "import\(['\"]/src/" e2e/*.spec.js`.
const VITE_SOURCE_SPECS = [
  'apparelSequence', 'audioMemoryGossipMcp', 'bugfix3-live', 'cardGame', 'cardGameBuilderFlow',
  'charGallery', 'chatCreateDelete', 'chatDeleteE2E', 'chatDeleteMsg', 'chatDeleteMsgDebug',
  'chatDeleteMsgUI', 'chatDuel', 'chatEditSave', 'magic8Connection', 'pendingAnimation',
  'pictureBookLive', 'portraitsAndGallery', 'sdConfigFlow', 'sdConfigLandscapePersist',
  'storyImagingSummarization', 'tagDisplay', 'verifyFixes', 'worldGroupPicker.e2e',
  // testHarness drives the devOnly harness (pageClient.js devMode = import.meta.env.DEV), which the
  // production dist served by Docker nginx does not build in — it only exists on a Vite dev server.
  'testHarness',
].map(n => `**/${n}.spec.js`);
const VITE_URL = process.env.PLAYWRIGHT_VITE_URL
  || (USE_EXTERNAL_SERVER ? 'https://127.0.0.1:8898' : BASE_URL);

export default defineConfig({
  testDir: './e2e',
  timeout: 60000,
  expect: { timeout: 10000 },
  workers: WORKERS,
  retries: 1,
  use: {
    baseURL: BASE_URL,
    ignoreHTTPSErrors: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', testIgnore: VITE_SOURCE_SPECS, use: { ...devices['Desktop Chrome'] } },
    { name: 'chromium-vite', testMatch: VITE_SOURCE_SPECS, use: { ...devices['Desktop Chrome'], baseURL: VITE_URL } },
    // Phase 15c: Firefox cross-browser validation (opt-in, see INCLUDE_FIREFOX).
    // Phase 7 perf fixes (specific CSS transitions, WebSocket redraw debouncing) apply here.
    // CDP-based tests (WebAuthn virtual authenticator) run chromium-only.
    ...(INCLUDE_FIREFOX ? [{ name: 'firefox', testIgnore: VITE_SOURCE_SPECS, use: { ...devices['Desktop Firefox'] } }] : [])
  ],
  webServer: USE_EXTERNAL_SERVER ? undefined : {
    command: 'npm run dev',
    port: 8899,
    reuseExistingServer: true,
  },
  outputDir: 'e2e/results',
});
