/**
 * WebAuthn E2E tests — Tests passkey registration, credential listing, and authentication flow.
 *
 * Uses Playwright's built-in CDP virtual authenticator to simulate a platform authenticator.
 * Tests the full key transfer: browser -> WebAuthn API -> backend -> credential storage -> auth.
 *
 * 15a: Backend API integration tests verify the REST endpoints directly against the live backend.
 */
import { test, expect } from './helpers/fixtures.js';
import { login, screenshot, openAppTool } from './helpers/auth.js';
import { ensureSharedTestUser } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';
import { createHash, randomBytes } from 'crypto';

// PLAYWRIGHT_BASE_URL is mandatory for every run (the only valid bases are the Docker stack on 9443
// and the second Vite on 8898); there is deliberately no fallback here.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL;
const REST = BASE_URL + '/AccountManagerService7/rest';

// The server's WebAuthn RP id is 'localhost' (asserted by the 15a API tests below). The browser
// refuses navigator.credentials.create() when the page origin's host does not match the RP id
// (`SecurityError: invalid domain`), so the virtual-authenticator registration test must be served
// from https://localhost:<port> — the same deployment the project's baseURL points at, host-renamed.
// The Docker publish is IPv4-only and `localhost` resolves to ::1 first, so this spec (and only this
// spec) pins localhost -> 127.0.0.1 in chromium; the global config origin is untouched.
test.use({ launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] } });

function localhostOrigin(baseURL) {
    const u = new URL(baseURL);
    u.hostname = 'localhost';
    return u.origin;
}

async function newApiContext() {
    return pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

function b64(str) { return Buffer.from(str).toString('base64'); }

async function apiLogin(ctx, opts = {}) {
    const org = opts.org || '/Development';
    const user = opts.user;
    const password = opts.password;
    return ctx.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: org, name: user, credential: b64(password), type: 'hashed_password' }
    });
}

/**
 * Navigate to the WebAuthn settings page via its App Panel card (flyout → App Panel → Passkeys).
 */
async function goToWebauthn(page) {
    await openAppTool(page, '/webauthn');
}

/**
 * Spec-local login against an explicit origin. helpers/auth.js login() posts to PLAYWRIGHT_BASE_URL
 * (127.0.0.1) and the session cookie is host-scoped, so a page served from https://localhost:<port>
 * would not carry it. This posts the login to the same origin the page is then loaded from.
 */
async function loginAtOrigin(page, origin, creds) {
    const resp = await page.request.post(origin + '/AccountManagerService7/rest/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name: creds.user, credential: b64(creds.password), type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed for ' + creds.user + ' at ' + origin + ': HTTP ' + resp.status());
    }
    // Stub the WebSocket before any navigation (Docker nginx drops the cookie on the WS upgrade and
    // pageClient.reconnect() would forceLogin() -> #!/sig after ~1s).
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url; this.readyState = 0;
                this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
            }
            send() {} close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    });
    await page.goto(origin + '/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        null, { timeout: 30000 }
    );
}

async function addVirtualAuthenticator(page, context) {
    const cdpSession = await context.newCDPSession(page);
    await cdpSession.send('WebAuthn.enable');
    const options = { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true };
    let { authenticatorId } = await cdpSession.send('WebAuthn.addVirtualAuthenticator', { options });
    return {
        /** Replace the device with an empty one (a device holding a key for this user refuses a second registration via excludeCredentials). */
        async swap() {
            await cdpSession.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
            ({ authenticatorId } = await cdpSession.send('WebAuthn.addVirtualAuthenticator', { options }));
        },
        async remove() {
            try {
                await cdpSession.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
                await cdpSession.send('WebAuthn.disable');
            } catch (e) { /* page may already be closed on timeout */ }
        }
    };
}

/** Register a passkey through the Passkeys page (the page must already be on #!/webauthn). */
async function registerPasskeyViaUi(page, name, consoleErrors) {
    const nameInput = page.locator('input[placeholder="e.g. Windows Hello, YubiKey"]');
    await nameInput.clear();
    await nameInput.fill(name);
    await page.locator('button:has-text("Register Passkey")').click();
    const credRow = page.locator('td:has-text("' + name + '")');
    try {
        await expect(credRow, 'registered passkey must appear in the Registered Passkeys table').toBeVisible({ timeout: 15000 });
    } catch (e) {
        const toasts = await page.locator('.toast-box').allTextContents().catch(() => []);
        console.log('[webauthn] registration of "' + name + '" did not land. toasts=' + JSON.stringify(toasts)
            + ' consoleErrors=' + JSON.stringify(consoleErrors || []));
        await screenshot(page, 'webauthn-registration-failed');
        throw e;
    }
}

/** Delete every row named `name` on the Passkeys page (the page must already be on #!/webauthn). */
const dialogAcceptingPages = new WeakSet();
async function deletePasskeysViaUi(page, name) {
    if (!dialogAcceptingPages.has(page)) {
        dialogAcceptingPages.add(page);
        page.on('dialog', dialog => dialog.accept().catch(() => {}));
    }
    const credRow = page.locator('td:has-text("' + name + '")');
    let remaining = await credRow.count();
    console.log('[webauthn] "' + name + '" rows before delete=' + remaining);
    while (remaining > 0) {
        const deleteBtn = page.locator('tr:has-text("' + name + '") button:has-text("Delete")').first();
        await expect(deleteBtn).toBeVisible({ timeout: 5000 });
        await deleteBtn.click();
        await expect(credRow, 'deleting a passkey must remove its row').toHaveCount(remaining - 1, { timeout: 15000 });
        remaining = await credRow.count();
    }
}

/** The stored credential (as the list route returns it) whose name is `name`, read on the page's session. */
async function findStoredCredential(page, origin, name) {
    const resp = await page.request.get(origin + '/AccountManagerService7/rest/credential/webauthn/credentials');
    expect(resp.status(), 'GET /credential/webauthn/credentials on the page session').toBe(200);
    const creds = JSON.parse(await resp.text());
    expect(Array.isArray(creds)).toBe(true);
    const cred = creds.find(c => c.name === name);
    expect(cred, 'credential "' + name + '" must be in the list').toBeTruthy();
    // The record serializer omits unset/default-valued fields, so a never-used key may carry no counter.
    cred.counter = (typeof cred.counter === 'number') ? cred.counter : 0;
    return cred;
}

/** Delete, on the page's session, every stored passkey whose name starts with `prefix` (litter from an earlier failed run). */
async function purgePasskeysViaRest(page, origin, prefix) {
    const rest = origin + '/AccountManagerService7/rest/credential/webauthn';
    const resp = await page.request.get(rest + '/credentials');
    expect(resp.status(), 'GET /credential/webauthn/credentials on the page session').toBe(200);
    const creds = JSON.parse(await resp.text());
    const litter = (Array.isArray(creds) ? creds : []).filter(c => typeof c.name === 'string' && c.name.startsWith(prefix));
    for (const c of litter) {
        const del = await page.request.delete(rest + '/' + encodeURIComponent(c.credentialId));
        console.log('[webauthn] purged leftover passkey "' + c.name + '" status=' + del.status());
        expect(del.status()).toBe(200);
    }
    return litter.length;
}

function b64url(buf) {
    return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A 37-byte authenticatorData: SHA-256(rpId) || flags || big-endian counter. */
function authenticatorData(rpId, flags, counter) {
    const out = Buffer.alloc(37);
    createHash('sha256').update(rpId).digest().copy(out, 0);
    out[32] = flags;
    out.writeUInt32BE(counter, 33);
    return out;
}

let shared = {};
test.beforeAll(async ({ request }) => {
    shared = await ensureSharedTestUser(request);
});

test.describe('WebAuthn passkey flow', () => {

    test('login page shows passkey button when WebAuthn is supported', async ({ page }) => {
        await page.goto('/');
        await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 10000 });

        // The passkey button should be visible on the login page
        let passkeyBtn = page.locator('button:has-text("Sign in with Passkey")');
        await expect(passkeyBtn).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'webauthn-login-page');
    });

    test('passkey button requires username before authenticating', async ({ page }) => {
        await page.goto('/');
        await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 10000 });

        // Click passkey without entering username
        await page.locator('button:has-text("Sign in with Passkey")').click();

        // Should show a warning toast
        await expect(page.locator('.toast-box').first()).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'webauthn-no-username-toast');
    });

    test('webauthn settings page loads after login', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await goToWebauthn(page);

        // Should see the passkey management heading
        await expect(page.locator('text=Passkey Management')).toBeVisible({ timeout: 10000 });
        await expect(page.locator('text=Register New Passkey')).toBeVisible({ timeout: 5000 });
        await expect(page.locator('text=Registered Passkeys')).toBeVisible({ timeout: 5000 });
        await screenshot(page, 'webauthn-settings-page');
    });

    test('webauthn settings shows register button and name input', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });
        await goToWebauthn(page);

        // Name input
        let nameInput = page.locator('input[placeholder="e.g. Windows Hello, YubiKey"]');
        await expect(nameInput).toBeVisible({ timeout: 5000 });
        await expect(nameInput).toHaveValue('My Passkey');

        // Register button
        let registerBtn = page.locator('button:has-text("Register Passkey")');
        await expect(registerBtn).toBeVisible({ timeout: 5000 });
        await expect(registerBtn).toBeEnabled();
        await screenshot(page, 'webauthn-register-form');
    });

    test('webauthn client API methods are available on am7client', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });

        // Verify the am7client WebAuthn methods exist in the browser context
        let methods = await page.evaluate(() => {
            let ac = window.__am7client;
            if (!ac) {
                return { error: 'am7client not found on window' };
            }
            return {
                webauthnSupported: typeof ac.webauthnSupported,
                webauthnRegister: typeof ac.webauthnRegister,
                webauthnAuthenticate: typeof ac.webauthnAuthenticate,
                webauthnListCredentials: typeof ac.webauthnListCredentials,
                webauthnDeleteCredential: typeof ac.webauthnDeleteCredential
            };
        });

        // If am7client isn't on window, verify via page content that the feature loaded
        await goToWebauthn(page);
        await expect(page.locator('text=Passkey Management')).toBeVisible({ timeout: 10000 });
    });

    test('webauthn registration flow with virtual authenticator', async ({ page, context, browserName, baseURL }) => {
        // CDP virtual authenticator is Chromium-only
        test.skip(browserName !== 'chromium', 'CDP WebAuthn virtual authenticator is Chromium-only');

        // Set up a virtual authenticator via CDP
        let cdpSession = await context.newCDPSession(page);
        await cdpSession.send('WebAuthn.enable');
        let { authenticatorId } = await cdpSession.send('WebAuthn.addVirtualAuthenticator', {
            options: {
                protocol: 'ctap2',
                transport: 'internal',
                hasResidentKey: true,
                hasUserVerification: true,
                isUserVerified: true
            }
        });

        // Surface the browser-side failure (e.g. `SecurityError: invalid domain`) in the log.
        const consoleErrors = [];
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
        page.on('pageerror', err => consoleErrors.push(err.message));

        try {
            // Served from https://localhost:<port> so the page origin matches the RP id 'localhost'.
            const origin = localhostOrigin(baseURL);
            console.log('[webauthn] registration test origin=' + origin);
            await loginAtOrigin(page, origin, { user: shared.testUserName, password: shared.testPassword });
            await goToWebauthn(page);

            // Fill in passkey name
            let nameInput = page.locator('input[placeholder="e.g. Windows Hello, YubiKey"]');
            await nameInput.clear();
            await nameInput.fill('Test Virtual Key');

            // Click register — the virtual authenticator auto-approves.
            await page.locator('button:has-text("Register Passkey")').click();

            // Registration must round-trip: browser -> WebAuthn API -> backend -> credential storage,
            // after which features/webauthn.js reloads the list and renders the credential row.
            let credRow = page.locator('td:has-text("Test Virtual Key")');
            try {
                await expect(credRow, 'registered passkey must appear in the Registered Passkeys table').toBeVisible({ timeout: 15000 });
            } catch (e) {
                const toasts = await page.locator('.toast-box').allTextContents().catch(() => []);
                console.log('[webauthn] registration did not land. toasts=' + JSON.stringify(toasts)
                    + ' consoleErrors=' + JSON.stringify(consoleErrors));
                await screenshot(page, 'webauthn-registration-failed');
                throw e;
            }
            await screenshot(page, 'webauthn-after-registration');

            // Delete it again so the shared user does not accumulate virtual keys across runs (an
            // earlier aborted run may have left more than one, so drain every row with that name).
            page.on('dialog', dialog => dialog.accept());
            let remaining = await credRow.count();
            console.log('[webauthn] "Test Virtual Key" rows before delete=' + remaining);
            while (remaining > 0) {
                let deleteBtn = page.locator('tr:has-text("Test Virtual Key") button:has-text("Delete")').first();
                await expect(deleteBtn).toBeVisible({ timeout: 5000 });
                await deleteBtn.click();
                await expect(credRow, 'deleting a passkey must remove its row').toHaveCount(remaining - 1, { timeout: 15000 });
                remaining = await credRow.count();
            }
            await screenshot(page, 'webauthn-after-deletion');
        } finally {
            try {
                await cdpSession.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
                await cdpSession.send('WebAuthn.disable');
            } catch (e) { /* page may already be closed on timeout */ }
        }
    });

    // PF-5b (PictureBookV3Implementation §9): the "Sign in with Passkey" button must log the user in
    // end to end. The server verifies the authenticator's signature over
    // authenticatorData || SHA-256(clientDataJSON) with the stored public key and then establishes the
    // same JAAS session LoginService does, so sig.js's follow-up uwm.getUser() (session cookie, no
    // token) resolves the user and the SPA lands on #!/main. Before the fix the POST answered
    // AUTHENTICATED without a session and the UI toasted "Passkey authentication succeeded but session
    // failed". A replay of the browser's real assertion against a fresh challenge must be refused.
    test('passkey login with virtual authenticator establishes a session (PF-5b)', async ({ page, context, browserName, baseURL }) => {
        test.skip(browserName !== 'chromium', 'CDP WebAuthn virtual authenticator is Chromium-only');
        const KEY_NAME = 'PF-5b Login Key';
        const authenticator = await addVirtualAuthenticator(page, context);
        const consoleErrors = [];
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
        page.on('pageerror', err => consoleErrors.push(err.message));
        let assertionBody = null;
        let assertionResponse = null;
        page.on('request', req => {
            if (req.method() === 'POST' && /\/credential\/webauthn\/auth$/.test(req.url())) {
                assertionBody = req.postData();
            }
        });
        page.on('response', async resp => {
            if (resp.request().method() === 'POST' && /\/credential\/webauthn\/auth$/.test(resp.url())) {
                assertionResponse = resp.status() + ' ' + (await resp.text().catch(() => '')).slice(0, 200);
            }
        });

        try {
            const origin = localhostOrigin(baseURL);
            const rest = origin + '/AccountManagerService7/rest';
            console.log('[webauthn] passkey login test origin=' + origin);
            await loginAtOrigin(page, origin, { user: shared.testUserName, password: shared.testPassword });
            await purgePasskeysViaRest(page, origin, 'PF-5');
            await goToWebauthn(page);
            await registerPasskeyViaUi(page, KEY_NAME, consoleErrors);
            const stored = await findStoredCredential(page, origin, KEY_NAME);
            expect(stored.counter, 'a freshly registered key starts at counter 0').toBe(0);

            // Drop the password session, reload: the SPA must be back on the login form.
            const logout = await page.request.get(rest + '/logout/');
            console.log('[webauthn] logout status=' + logout.status());
            await page.goto(origin + '/', { timeout: 30000 });
            await page.locator('select#selOrganizationList').waitFor({ state: 'visible', timeout: 15000 });
            const whoBefore = await page.request.get(rest + '/principal/');
            expect((await whoBefore.text()).trim(), 'no principal after logout').toMatch(/^(null)?$/);

            await page.locator('select#selOrganizationList').selectOption('/Development');
            await page.locator('input[name="userName"]').fill(shared.testUserName);
            await page.locator('button:has-text("Sign in with Passkey")').click();
            try {
                await page.waitForFunction(
                    () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
                    null, { timeout: 30000 }
                );
            } catch (e) {
                const toasts = await page.locator('.toast-box').allTextContents().catch(() => []);
                console.log('[webauthn] passkey login did not reach #!/main. toasts=' + JSON.stringify(toasts)
                    + ' consoleErrors=' + JSON.stringify(consoleErrors) + ' assertionPosted=' + (assertionBody != null)
                    + ' assertionResponse=' + JSON.stringify(assertionResponse));
                await screenshot(page, 'webauthn-passkey-login-failed');
                throw e;
            }
            await screenshot(page, 'webauthn-passkey-login-main');
            console.log('[webauthn] POST /auth response=' + JSON.stringify(assertionResponse));
            expect(assertionBody, 'the browser must have posted an assertion').toBeTruthy();

            // The session the UI continues on is the passkey-authenticated one.
            const whoAfter = await page.request.get(rest + '/principal/');
            expect(whoAfter.status()).toBe(200);
            const me = JSON.parse(await whoAfter.text());
            expect(me && me.name, 'GET /principal/ on the session cookie after passkey login').toBe(shared.testUserName);
            const used = await findStoredCredential(page, origin, KEY_NAME);
            expect(used.counter, 'the authenticator counter must be persisted from the assertion').toBeGreaterThan(0);

            // Replaying the captured assertion against a fresh challenge must be refused.
            const replay = await pwRequest.newContext({ baseURL: origin, ignoreHTTPSErrors: true });
            try {
                const opts = await replay.get(rest + '/credential/webauthn/auth?user=/Development/' + shared.testUserName);
                expect(opts.status()).toBe(200);
                const r = await replay.post(rest + '/credential/webauthn/auth', {
                    headers: { 'Content-Type': 'application/json' }, data: assertionBody
                });
                const text = await r.text();
                console.log('[webauthn] replay status=' + r.status() + ' body=' + text.slice(0, 200));
                expect(r.status(), 'replayed assertion').toBe(401);
                expect(text).not.toContain('"tokens":[');
                const whoReplay = await replay.get(rest + '/principal/');
                expect((await whoReplay.text()).trim(), 'no principal on the replay session').toMatch(/^(null)?$/);
            } finally {
                await replay.dispose();
            }
            const unchanged = await findStoredCredential(page, origin, KEY_NAME);
            expect(unchanged.counter, 'a refused replay must not move the counter').toBe(used.counter);

            // The passkey session drives the UI: open the Passkeys page and clean up.
            await goToWebauthn(page);
            await expect(page.locator('td:has-text("' + KEY_NAME + '")')).toBeVisible({ timeout: 15000 });
            await deletePasskeysViaUi(page, KEY_NAME);
            await screenshot(page, 'webauthn-passkey-login-cleanup');
        } finally {
            await authenticator.remove();
        }
    });

    // PF-5b: an assertion whose signature was not produced by the registered key must be refused,
    // issue no token and leave the stored counter alone. Before the fix the server never checked the
    // signature, so any caller who knew a credentialId could log in as its owner.
    test('forged passkey assertion is refused by the server (PF-5b)', async ({ page, context, browserName, baseURL }) => {
        test.skip(browserName !== 'chromium', 'CDP WebAuthn virtual authenticator is Chromium-only');
        const KEY_NAME = 'PF-5b Forgery Key';
        const authenticator = await addVirtualAuthenticator(page, context);
        const consoleErrors = [];
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
        page.on('pageerror', err => consoleErrors.push(err.message));

        try {
            const origin = localhostOrigin(baseURL);
            const rest = origin + '/AccountManagerService7/rest';
            await loginAtOrigin(page, origin, { user: shared.testUserName, password: shared.testPassword });
            await purgePasskeysViaRest(page, origin, 'PF-5');
            await goToWebauthn(page);
            await registerPasskeyViaUi(page, KEY_NAME, consoleErrors);
            const stored = await findStoredCredential(page, origin, KEY_NAME);
            expect(stored.credentialId).toBeTruthy();

            const attacker = await pwRequest.newContext({ baseURL: origin, ignoreHTTPSErrors: true });
            try {
                const opts = await attacker.get(rest + '/credential/webauthn/auth?user=/Development/' + shared.testUserName);
                expect(opts.status()).toBe(200);
                const options = JSON.parse(await opts.text());
                expect(options.allowCredentials.map(c => c.id)).toContain(stored.credentialId);

                const forged = {
                    credentialId: stored.credentialId,
                    clientDataJSON: b64url(JSON.stringify({ type: 'webauthn.get', challenge: options.challenge, origin: origin, crossOrigin: false })),
                    authenticatorData: b64url(authenticatorData('localhost', 0x05, 99)),
                    signature: b64url(randomBytes(64)),
                    organizationPath: '/Development'
                };
                const r = await attacker.post(rest + '/credential/webauthn/auth', { data: forged });
                const text = await r.text();
                console.log('[webauthn] forged assertion status=' + r.status() + ' body=' + text.slice(0, 200));
                expect(r.status(), 'forged assertion must be 401').toBe(401);
                expect(text).not.toContain('"tokens":[');
                expect(text).not.toContain('AUTHENTICATED');
                const who = await attacker.get(rest + '/principal/');
                expect((await who.text()).trim(), 'no principal on the attacker session').toMatch(/^(null)?$/);
            } finally {
                await attacker.dispose();
            }
            const after = await findStoredCredential(page, origin, KEY_NAME);
            expect(after.counter, 'a refused assertion must not move the counter').toBe(stored.counter);

            await deletePasskeysViaUi(page, KEY_NAME);
        } finally {
            await authenticator.remove();
        }
    });

    // PF-5c: the credential list and the allowCredentials of GET /auth are read through the server's
    // query cache, which caches any non-empty result for minutes and is never invalidated by a create.
    // With one passkey already registered, a second one therefore did not appear in the Passkeys table
    // and could not be used to sign in until the cache expired. Both must reflect it immediately.
    test('a second passkey is listed and allowed immediately after registration (PF-5c)', async ({ page, context, browserName, baseURL }) => {
        test.skip(browserName !== 'chromium', 'CDP WebAuthn virtual authenticator is Chromium-only');
        const FIRST = 'PF-5c First Key';
        const SECOND = 'PF-5c Second Key';
        const authenticator = await addVirtualAuthenticator(page, context);
        const consoleErrors = [];
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
        page.on('pageerror', err => consoleErrors.push(err.message));

        try {
            const origin = localhostOrigin(baseURL);
            const rest = origin + '/AccountManagerService7/rest';
            await loginAtOrigin(page, origin, { user: shared.testUserName, password: shared.testPassword });
            await purgePasskeysViaRest(page, origin, 'PF-5');
            await goToWebauthn(page);
            await registerPasskeyViaUi(page, FIRST, consoleErrors);
            const first = await findStoredCredential(page, origin, FIRST);

            // The page has now listed a non-empty result; register the second key, from a second device, on top of it.
            await authenticator.swap();
            await registerPasskeyViaUi(page, SECOND, consoleErrors);
            await expect(page.locator('td:has-text("' + FIRST + '")'), 'the first key must still be listed').toBeVisible({ timeout: 5000 });
            await screenshot(page, 'webauthn-second-passkey-listed');
            const second = await findStoredCredential(page, origin, SECOND);
            expect(second.credentialId).not.toBe(first.credentialId);

            const anonymous = await pwRequest.newContext({ baseURL: origin, ignoreHTTPSErrors: true });
            try {
                const opts = await anonymous.get(rest + '/credential/webauthn/auth?user=/Development/' + shared.testUserName);
                expect(opts.status()).toBe(200);
                const ids = JSON.parse(await opts.text()).allowCredentials.map(c => c.id);
                console.log('[webauthn] allowCredentials after second registration=' + JSON.stringify(ids));
                expect(ids, 'GET /auth must offer the second key immediately').toContain(second.credentialId);
                expect(ids, 'GET /auth must still offer the first key').toContain(first.credentialId);
            } finally {
                await anonymous.dispose();
            }

            await deletePasskeysViaUi(page, SECOND);
            await deletePasskeysViaUi(page, FIRST);
        } finally {
            await authenticator.remove();
        }
    });

    test('webauthn base64url encoding roundtrip is correct', async ({ page }) => {
        await login(page, { user: shared.testUserName, password: shared.testPassword });

        // Test the base64url encoding/decoding functions used for key transfer
        let roundtripResult = await page.evaluate(() => {
            let hasWebAuthn = !!(window.PublicKeyCredential && navigator.credentials);

            function b64ToBuffer(b64) {
                let s = b64.replace(/-/g, '+').replace(/_/g, '/');
                while (s.length % 4) s += '=';
                let binary = atob(s);
                let bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                return bytes.buffer;
            }

            function bufferToB64(buffer) {
                let bytes = new Uint8Array(buffer);
                let binary = '';
                for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
            }

            let testCases = [
                { input: new Uint8Array([]).buffer },
                { input: new Uint8Array([1, 2, 3, 4, 5]).buffer },
                { input: new Uint8Array(32).buffer },
                // Bytes that produce special chars in standard base64
                { input: new Uint8Array([251, 239, 190]).buffer },
                // Mixed content simulating a credential ID
                { input: new Uint8Array([0, 1, 127, 128, 255, 64, 32, 16]).buffer },
                // Large buffer (64 bytes like an attestation response)
                { input: crypto.getRandomValues(new Uint8Array(64)).buffer },
            ];

            let results = [];
            for (let tc of testCases) {
                let encoded = bufferToB64(tc.input);
                let decoded = b64ToBuffer(encoded);
                let reencoded = bufferToB64(decoded);

                let originalBytes = new Uint8Array(tc.input);
                let decodedBytes = new Uint8Array(decoded);
                let match = originalBytes.length === decodedBytes.length &&
                    originalBytes.every((b, i) => b === decodedBytes[i]);
                let urlSafe = !encoded.includes('+') && !encoded.includes('/') && !encoded.includes('=');

                results.push({
                    inputLen: originalBytes.length,
                    encoded,
                    roundtripMatch: match,
                    reencodingMatch: reencoded === encoded,
                    urlSafe
                });
            }

            // Random challenge roundtrip (simulates actual WebAuthn flow)
            let challenge = new Uint8Array(32);
            crypto.getRandomValues(challenge);
            let challengeB64 = bufferToB64(challenge.buffer);
            let challengeDecoded = b64ToBuffer(challengeB64);
            let challengeReencoded = bufferToB64(challengeDecoded);
            let challengeBytes = new Uint8Array(challengeDecoded);
            let challengeRoundtrip = challenge.length === challengeBytes.length &&
                challenge.every((b, i) => b === challengeBytes[i]);
            let challengeUrlSafe = !challengeB64.includes('+') && !challengeB64.includes('/');

            return {
                hasWebAuthn,
                challengeRoundtrip,
                challengeUrlSafe,
                results
            };
        });

        expect(roundtripResult.hasWebAuthn).toBe(true);
        expect(roundtripResult.challengeRoundtrip).toBe(true);
        expect(roundtripResult.challengeUrlSafe).toBe(true);

        for (let i = 0; i < roundtripResult.results.length; i++) {
            let r = roundtripResult.results[i];
            expect(r.roundtripMatch).toBe(true);
            expect(r.reencodingMatch).toBe(true);
            expect(r.urlSafe).toBe(true);
        }

        await screenshot(page, 'webauthn-encoding-test');
    });
});

// ── 15a: Backend API integration tests ─────────────────────────────────────

test.describe('WebAuthn backend API integration (15a)', () => {

    test('GET /rest/credential/webauthn/register returns valid options JSON', async () => {
        let ctx = await newApiContext();
        try {
            let loginResp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
            if (!loginResp.ok()) {
                throw new Error('login as ' + shared.testUserName + ' failed: HTTP ' + loginResp.status());
            }

            let resp = await ctx.get(REST + '/credential/webauthn/register');
            expect(resp.ok()).toBe(true);

            let text = await resp.text();
            // Must return JSON, not an HTML error page
            expect(text).not.toMatch(/^</);
            let json = JSON.parse(text);

            // Validate WebAuthn registration options structure (RFC 8809)
            expect(json).toHaveProperty('challenge');
            expect(typeof json.challenge).toBe('string');
            expect(json.challenge.length).toBeGreaterThan(10);

            expect(json).toHaveProperty('rp');
            expect(json.rp).toHaveProperty('name', 'AccountManager7');
            expect(json.rp).toHaveProperty('id', 'localhost');

            expect(json).toHaveProperty('user');
            expect(json.user).toHaveProperty('id');
            expect(json.user).toHaveProperty('name');
            expect(json.user).toHaveProperty('displayName');

            expect(json).toHaveProperty('pubKeyCredParams');
            expect(Array.isArray(json.pubKeyCredParams)).toBe(true);
            expect(json.pubKeyCredParams.length).toBeGreaterThan(0);

            expect(json).toHaveProperty('excludeCredentials');
            expect(Array.isArray(json.excludeCredentials)).toBe(true);

            // Challenge must be URL-safe base64 (no +, /, =)
            expect(json.challenge).not.toMatch(/[+/=]/);

        } finally {
            await ctx.dispose();
        }
    });

    test('GET /rest/credential/webauthn/auth returns valid challenge options', async () => {
        let ctx = await newApiContext();
        try {
            // Auth endpoint is public (no login required) — used for passkey login
            let resp = await ctx.get(REST + '/credential/webauthn/auth?user=/Development/' + shared.testUserName);
            if (!resp.ok()) {
                throw new Error('GET /credential/webauthn/auth for ' + shared.testUserName + ' failed: HTTP ' + resp.status() + ' ' + (await resp.text()).slice(0, 300));
            }

            let text = await resp.text();
            expect(text).not.toMatch(/^</);
            let json = JSON.parse(text);

            // Validate authentication options structure
            expect(json).toHaveProperty('challenge');
            expect(typeof json.challenge).toBe('string');
            expect(json.challenge.length).toBeGreaterThan(10);
            expect(json.challenge).not.toMatch(/[+/=]/);

            expect(json).toHaveProperty('rpId', 'localhost');
            expect(json).toHaveProperty('allowCredentials');
            expect(Array.isArray(json.allowCredentials)).toBe(true);

            // Two consecutive calls must return different challenges (replay protection)
            let resp2 = await ctx.get(REST + '/credential/webauthn/auth?user=/Development/' + shared.testUserName);
            let json2 = JSON.parse(await resp2.text());
            expect(json2.challenge).not.toBe(json.challenge);

        } finally {
            await ctx.dispose();
        }
    });

    test('GET /rest/credential/webauthn/credentials requires authentication', async () => {
        let ctx = await newApiContext();
        try {
            // Unauthenticated request should return 401 or empty list
            let resp = await ctx.get(REST + '/credential/webauthn/credentials');
            // Either 401 (not authenticated) or redirect — either way must not be 500
            expect(resp.status()).not.toBe(500);
            if (!resp.ok()) {
                expect([401, 403, 302]).toContain(resp.status());
            }
        } finally {
            await ctx.dispose();
        }
    });

    test('GET /rest/credential/webauthn/credentials returns list after login', async () => {
        let ctx = await newApiContext();
        try {
            let loginResp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
            if (!loginResp.ok()) {
                throw new Error('login as ' + shared.testUserName + ' failed: HTTP ' + loginResp.status());
            }

            let resp = await ctx.get(REST + '/credential/webauthn/credentials');
            expect(resp.ok()).toBe(true);

            let text = await resp.text();
            expect(text).not.toMatch(/^</);
            // Returns a JSON array (may be empty if no passkeys registered)
            let json = JSON.parse(text);
            expect(Array.isArray(json)).toBe(true);

        } finally {
            await ctx.dispose();
        }
    });

    test('GET /rest/credential/webauthn/register issues new challenge each call', async () => {
        let ctx = await newApiContext();
        try {
            let loginResp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
            if (!loginResp.ok()) {
                throw new Error('login as ' + shared.testUserName + ' failed: HTTP ' + loginResp.status());
            }

            let resp1 = await ctx.get(REST + '/credential/webauthn/register');
            let resp2 = await ctx.get(REST + '/credential/webauthn/register');
            expect(resp1.ok(), 'first /register GET ok').toBe(true);
            expect(resp2.ok(), 'second /register GET ok').toBe(true);

            let j1 = JSON.parse(await resp1.text());
            let j2 = JSON.parse(await resp2.text());

            // Each call must return a fresh random challenge
            expect(j1.challenge).toBeTruthy();
            expect(j2.challenge).toBeTruthy();
            expect(j1.challenge).not.toBe(j2.challenge);

        } finally {
            await ctx.dispose();
        }
    });

    test('POST /rest/credential/webauthn/register rejects missing fields', async () => {
        let ctx = await newApiContext();
        try {
            let loginResp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
            if (!loginResp.ok()) {
                throw new Error('login as ' + shared.testUserName + ' failed: HTTP ' + loginResp.status());
            }

            // First get options to get the session challenge
            await ctx.get(REST + '/credential/webauthn/register');

            // Post incomplete registration data — should get 400
            let resp = await ctx.post(REST + '/credential/webauthn/register', {
                data: { credentialId: null, publicKey: null, clientDataJSON: null }
            });
            // Must not be a 500 server error
            expect(resp.status()).not.toBe(500);
            expect(resp.status()).toBe(400);

        } finally {
            await ctx.dispose();
        }
    });
});
