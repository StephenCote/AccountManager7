/**
 * system.connection editor — Dialect / Upstream selectors
 * (route #!/view/system.connection/{oid}, src/views/object.js generic editor fed by
 *  src/core/formDef.js forms.connection; renderers.select in components/formFieldRenderers.js).
 *
 * Until now forms.connection listed only name/description/serverUrl/apiKey/requestTimeout, so the
 * server's authoritative `dialect` (wire protocol) and `upstream` (model-server family) enums were
 * unreachable from the UI and operators were left with the deprecated chatConfig "Service Type
 * (legacy)" fallback. This proves both selectors render from the enum registry, and that a change
 * made through the editor's Save is what the server persists.
 *
 *   1. create a connection over REST as the shared (non-admin) user (dialect/upstream unset);
 *   2. open the editor: `select[name=dialect]` offers unknown/ollama/openai/openai_compat and
 *      `select[name=upstream]` offers unknown/ollama/openai (values lowercase, labels UPPERCASE);
 *   3. pick OPENAI_COMPAT + OLLAMA (the LiteLLM-in-front-of-Ollama shape), Save -> "Saved!";
 *   4. read the record back over REST with cache:false and see openai_compat / ollama;
 *   5. reload the editor: both selects show the persisted values.
 * LLM/SD-free. Never runs as admin. Cleans up the connection it created.
 *
 * Run (Windows / Docker stack — MUST use 127.0.0.1, localhost resolves to unmapped IPv6 ::1):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/connectionDialectSelector.spec.js --workers=1 --project=chromium
 */
import { test, expect } from '@playwright/test';
import { apiLogin, ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

let shared = null;

function b64(str) { return Buffer.from(str).toString('base64'); }

async function restLogin(ctx) {
    expect(shared, 'shared test user must be provisioned before any login').toBeTruthy();
    const resp = await apiLogin(ctx, { user: shared.testUserName, password: shared.testPassword });
    expect(resp.ok() || resp.status() === 204, shared.testUserName + ' login failed: ' + resp.status()).toBe(true);
}

// WS stub + login, canonical pattern (Docker's nginx drops the session cookie on the WS upgrade; without
// the stub the app's reconnect path forces a redirect to #!/sig).
async function loginAsSharedUser(page) {
    await restLogin(page.request);
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
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

async function readConnection(request, conn) {
    const resp = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: 'system.connection',
            request: ['id', 'objectId', 'name', 'dialect', 'upstream', 'serverUrl'],
            cache: false,
            fields: [
                { name: 'objectId', comparator: 'EQUALS', value: conn.objectId },
                { name: 'organizationId', comparator: 'EQUALS', value: conn.organizationId }
            ]
        }
    });
    expect(resp.ok(), 'POST /model/search failed: ' + resp.status() + ' ' + (await resp.text())).toBe(true);
    const body = await resp.json();
    expect(body && body.results && body.results.length, 'connection not found by objectId').toBe(1);
    return body.results[0];
}

function lower(v) { return v == null ? v : String(v).toLowerCase(); }

test.describe('Connection editor — Dialect and Upstream selectors', () => {
    test.describe.configure({ timeout: 180000 });

    const tag = Date.now().toString(36);
    let conn = null;

    test.beforeAll(async ({ request }) => {
        shared = await ensureSharedTestUser(request);
        await restLogin(request);

        const dirResp = await request.get(REST + '/path/make/auth.group/data/B64-' + b64('~/Connections').replace(/=/g, '%3D'));
        expect(dirResp.ok(), 'path/make ~/Connections failed: ' + dirResp.status()).toBe(true);
        const dir = await dirResp.json();
        expect(dir && dir.id, '~/Connections group has no id').toBeTruthy();

        const createResp = await request.post(REST + '/model', {
            data: {
                schema: 'system.connection', groupId: dir.id, groupPath: dir.path,
                name: 'E2E Dialect ' + tag, serverUrl: 'http://litellm:4000', requestTimeout: 90
            }
        });
        expect(createResp.ok(), 'POST /model system.connection failed: ' + createResp.status() + ' ' + (await createResp.text())).toBe(true);
        conn = await createResp.json();
        expect(conn && conn.objectId, 'create returned no identity').toBeTruthy();
        // Create returns identity fields only (no organizationId); the group carries the org.
        conn.organizationId = dir.organizationId;
        expect(conn.organizationId, '~/Connections group has no organizationId').toBeTruthy();

        // Precondition: neither axis is asserted yet (null column or UNKNOWN default) — the test must
        // change them, not observe values the server already had.
        const before = await readConnection(request, conn);
        expect(['unknown', null, undefined]).toContain(lower(before.dialect));
        expect(['unknown', null, undefined]).toContain(lower(before.upstream));
    });

    test.afterAll(async ({ request }) => {
        try {
            if (conn && conn.objectId) {
                await restLogin(request);
                await request.delete(REST + '/model/system.connection/' + conn.objectId);
            }
        } catch (_) {}
    });

    test('operator sets Dialect=OPENAI_COMPAT and Upstream=OLLAMA through the editor; server persists both', async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));

        await loginAsSharedUser(page);
        await page.evaluate((oid) => { window.location.hash = '!/view/system.connection/' + oid; }, conn.objectId);

        const dialect = page.locator('select[name="dialect"]');
        const upstream = page.locator('select[name="upstream"]');
        await expect(dialect, 'Dialect selector missing from the connection form').toBeVisible({ timeout: 30000 });
        await expect(upstream, 'Upstream selector missing from the connection form').toBeVisible({ timeout: 10000 });
        await expect(page.locator('label[for="dialect"]')).toHaveText('Dialect');
        await expect(page.locator('label[for="upstream"]')).toHaveText('Upstream');
        // The legacy chatConfig fallback must not have leaked onto the connection form.
        await expect(page.locator('select[name="serviceType"]')).toHaveCount(0);

        // Options come from am7model.enums.connection{Dialect,Upstream}EnumType: value lowercase, label as-is.
        const dialectOpts = dialect.locator('option');
        await expect(dialectOpts).toHaveCount(4);
        expect(await dialectOpts.evaluateAll((os) => os.map((o) => o.value))).toEqual(['unknown', 'ollama', 'openai', 'openai_compat']);
        expect(await dialectOpts.evaluateAll((os) => os.map((o) => o.textContent))).toEqual(['UNKNOWN', 'OLLAMA', 'OPENAI', 'OPENAI_COMPAT']);
        const upstreamOpts = upstream.locator('option');
        await expect(upstreamOpts).toHaveCount(3);
        expect(await upstreamOpts.evaluateAll((os) => os.map((o) => o.value))).toEqual(['unknown', 'ollama', 'openai']);

        await dialect.selectOption('openai_compat');
        await upstream.selectOption('ollama');
        await expect(dialect).toHaveValue('openai_compat');
        await expect(upstream).toHaveValue('ollama');

        const saveBtn = page.locator('button:has(span.material-symbols-outlined:text("save"))').first();
        await expect(saveBtn, 'editor save button not found').toBeVisible({ timeout: 5000 });
        await saveBtn.click();
        await expect(page.locator('.toast-box').filter({ hasText: 'Saved!' }), 'no "Saved!" toast after save').toBeVisible({ timeout: 20000 });
        expect(pageErrors, 'uncaught page errors during the flow: ' + pageErrors.join(' | ')).toEqual([]);

        // Server truth, fresh read (search is cached by query key; cache:false bypasses it).
        const after = await readConnection(page.request, conn);
        expect(lower(after.dialect), 'dialect not persisted').toBe('openai_compat');
        expect(lower(after.upstream), 'upstream not persisted').toBe('ollama');
        expect(after.name, 'name must survive the PATCH (writer validates the patch record itself)').toBe('E2E Dialect ' + tag);
        expect(after.serverUrl, 'serverUrl must be untouched by a dialect/upstream edit').toBe('http://litellm:4000');

        // A fresh editor load shows the persisted values selected.
        await page.reload({ timeout: 30000 });
        await expect(page.locator('select[name="dialect"]')).toHaveValue('openai_compat', { timeout: 30000 });
        await expect(page.locator('select[name="upstream"]')).toHaveValue('ollama');
    });
});
