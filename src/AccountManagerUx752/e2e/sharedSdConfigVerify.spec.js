/**
 * Shared SD-config regression guard — proves the components/sdConfig.js `loadConfig` fix (line 116:
 * public `am7client.getFull(...)` instead of the never-exported `getFullByObjectId`, which threw and
 * was swallowed so loadConfig returned NULL for every caller) did NOT break the other consumer
 * of that shared utility beyond ChapBook (already proven in chapBookVerify.spec.js):
 *
 *   REIMAGE (workflows/reimage.js) — the character Reimage dialog loads a per-character saved config
 *     (`sdcfg-<charObjectId>`, ~/Data/.preferences). Before the fix, loadConfig returned null so the
 *     `if (charConfig) am7sd.applyConfig(...)` branch (reimage.js:146) was DEAD. It now FIRES: assert
 *     the Model <select> shows the saved model value (proves the saved config LOADED and APPLIED).
 *
 * Run against the Docker stack (host 9443, 127.0.0.1 required — localhost resolves to IPv6 ::1 which
 * Docker does not map):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/sharedSdConfigVerify.spec.js \
 *     --workers=1 --project=chromium
 *
 * The test reads the live SD catalog (Docker CAN reach the SD host at 192.168.1.39), so the Model
 * control is a real populated <select>. It makes no LLM/SD generation call. (The PB2 wizard step-4
 * SD-config case went with the wizard's step 4 at Phase 10 P10-1.)
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const RESULTS = path.resolve(SPEC_DIR, '../test-results');
const REST = '/AccountManagerService7/rest';

// A REAL model from this stack's live SD catalog (GET /rest/olio/sdModels), chosen because it is NOT
// the schema/random default — so a match on it proves the saved config was loaded, not a default.
const SEEDED_MODEL = 'ponyRealism_V22.safetensors';

function b64(s) { return Buffer.from(s).toString('base64'); }
function encPath(p) { return 'B64-' + b64(p).replace(/=/g, '%3D'); }

// Shared state seeded in beforeAll.
let orgId = null;
let prefsGroupId = null;
let prefsGroupPath = null;
let charObjectId = null;
let charName = null;

async function restLogin(request, name, password) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential', organizationPath: '/Development',
            name, credential: b64(password), type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login failed for ' + name).toBe(true);
}

// Upsert an olio.sd.config record by name in the prefs group with a distinctive model value, so a UI
// match on SEEDED_MODEL proves "loaded my saved config", not the schema/random default.
async function seedSdConfig(request, name) {
    const cfg = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.sd.config',
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId', 'name', 'model'], recordCount: 1, cache: false
        }
    });
    const body = await cfg.json().catch(() => null);
    const existing = body && body.results && body.results[0];
    if (existing && existing.objectId) {
        await request.fetch(REST + '/model', {
            method: 'PATCH',
            data: { schema: 'olio.sd.config', id: existing.id, objectId: existing.objectId, name, model: SEEDED_MODEL }
        });
        return existing.objectId;
    }
    const resp = await request.post(REST + '/model', {
        data: {
            schema: 'olio.sd.config', name,
            groupId: prefsGroupId, groupPath: prefsGroupPath,
            model: SEEDED_MODEL, steps: 24, cfg: 7, width: 1024, height: 1024, style: 'photograph'
        }
    });
    const created = await resp.json().catch(() => null);
    return created && created.objectId;
}

// WebSocket stub — Docker's nginx strips the session cookie on the WS upgrade, so Tomcat closes it,
// which triggers forceLogin() → redirect to #!/sig. Stub it BEFORE goto (addInitScript runs on every
// navigation, including reload).
function wsStub() {
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
}

async function loginAndLoad(page, name, password) {
    await page.context().clearCookies();
    await restLogin(page.request, name, password);
    await page.addInitScript(wsStub);
    await page.goto('about:blank');
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

test.describe('Shared SD config — reimage regression guard', () => {
    test.describe.configure({ timeout: 120000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
        await restLogin(request, 'e2etest_shared', 'password');

        const prefsDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Data/.preferences'));
        const prefsBody = await prefsDir.json();
        expect(prefsBody && prefsBody.id, 'could not ensure ~/Data/.preferences').toBeTruthy();
        prefsGroupId = prefsBody.id;
        prefsGroupPath = prefsBody.path;
        orgId = prefsBody.organizationId;

        // Confirm the seeded model exists in the live catalog — otherwise the <select> can't select it.
        const models = await request.get(REST + '/olio/sdModels');
        const modelArr = await models.json().catch(() => null);
        expect(Array.isArray(modelArr) && modelArr.length > 0, 'live SD catalog empty — Docker cannot reach SD host?').toBe(true);
        expect(modelArr, 'seeded model ' + SEEDED_MODEL + ' not in live SD catalog').toContain(SEEDED_MODEL);

        // ── REIMAGE setup: a charPerson + its per-character saved config (sdcfg-<charObjectId>) ──
        const charDir = await request.get(REST + '/path/make/auth.group/data/' + encPath('~/Characters'));
        const charDirBody = await charDir.json();
        expect(charDirBody && charDirBody.id, 'could not ensure ~/Characters').toBeTruthy();

        charName = 'ReimageCfg' + Date.now().toString(36);
        const charResp = await request.post(REST + '/model', {
            data: {
                schema: 'olio.charPerson', name: charName,
                firstName: 'Test', middleName: 'E2E', lastName: 'Character',
                gender: 'female', alignment: 'neutralgood',
                groupId: charDirBody.id, groupPath: charDirBody.path
            }
        });
        const charRec = await charResp.json().catch(() => null);
        charObjectId = charRec && charRec.objectId;
        expect(charObjectId, 'charPerson not created').toBeTruthy();

        const charCfgOid = await seedSdConfig(request, 'sdcfg-' + charObjectId);
        expect(charCfgOid, 'sdcfg-<charObjectId> not seeded').toBeTruthy();

        await request.get(REST + '/logout');
        fs.mkdirSync(RESULTS, { recursive: true });
    });

    // ── REIMAGE: the now-live per-character config branch loads the saved model into the dialog ──────
    test('reimage: character Reimage dialog loads the saved per-character config (Model <select> = saved model)', async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(String(e)));

        await loginAndLoad(page, 'e2etest_shared', 'password');
        await page.evaluate((oid) => { window.location.hash = '!/view/olio.charPerson/' + oid; }, charObjectId);

        // The Reimage command button is the icon button whose glyph is 'auto_awesome' (charPerson form).
        const reimageBtn = page.locator('button:has(span.material-symbols-outlined:text-is("auto_awesome"))').first();
        await expect(reimageBtn, 'Reimage command button not visible on charPerson view').toBeVisible({ timeout: 20000 });
        await reimageBtn.click();

        // The dialog title is 'Reimage <name>' — wait for it, then for the config panel to settle.
        await expect(page.locator('text=Reimage ' + charName).first()).toBeVisible({ timeout: 20000 });

        const modelSelect = page.locator('xpath=//label[normalize-space()="Model"]/following-sibling::select[1]');
        await expect(modelSelect, 'expected a <select> for Model (live catalog is populated)').toBeVisible({ timeout: 15000 });
        const optionCount = await modelSelect.locator('option').count();
        expect(optionCount, 'model <select> not populated with real options').toBeGreaterThanOrEqual(3);
        // The SELECTED value is the saved per-character config's model — proving the fixed loadConfig
        // returned the real record and reimage.js:146 applied it (dead branch before the fix).
        await expect(modelSelect).toHaveValue(SEEDED_MODEL);

        await page.screenshot({ path: path.join(RESULTS, 'verify-reimage-saved-config.png'), fullPage: true });
        expect(pageErrors, 'uncaught page error(s) during reimage: ' + pageErrors.join(' | ')).toEqual([]);
    });
});
