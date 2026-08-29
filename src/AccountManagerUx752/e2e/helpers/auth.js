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
    const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
    const REST = BASE_URL + '/AccountManagerService7/rest';

    // Log in via the REST API using Playwright's request context so the session
    // cookie is established BEFORE page.goto(). Cookies set here are shared with
    // the page context, so the SPA loads already authenticated and stays on #!/main
    // rather than bouncing to #!/sig.
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: org,
            name: user,
            credential: Buffer.from(password).toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed for ' + user + ': HTTP ' + resp.status());
    }

    // Stub WebSocket — Docker's nginx strips session cookies on the WS upgrade so
    // Tomcat closes the connection immediately, then after 1000ms forceLogin() fires
    // and redirects to #!/sig. The stub fires onopen but never onclose.
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {} close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });

    // Navigate to the app — session cookie is already set so the SPA initializes
    // as an authenticated user and routes to #!/main.
    await page.goto('/', { timeout: 30000 });

    // Wait for the main dashboard to render
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
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
