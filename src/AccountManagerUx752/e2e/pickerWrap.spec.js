/**
 * Object-picker pagination WRAP verification — confirms the viewport-INDEPENDENT clipping fix.
 *
 * Prior finding (e2e/pickerClip.spec.js): making the picker box 80vw fixed the pagination Next/Last
 * clipping at 1536, but was width-dependent — below ~1510px the right-aligned pagination bar still
 * overflowed the dialog's right edge, and at 1280 it clipped badly.
 *
 * The follow-up fix (main.css `.result-nav-inner` → `sm:flex-wrap ... gap-y-1`) makes the toolbar +
 * pagination row WRAP the pagination onto its own line when both can't fit, instead of overflowing off
 * the right edge. This spec measures the REAL deployed bundle (NO style overrides) at two viewports and
 * asserts every pagination + up/down-nav control is fully inside the dialog's client rect AND passes
 * Playwright actionability — whether it stays on row 1 or wraps to row 2.
 *
 *   1) 1280×800  — the case that clipped worst before the wrap fix.
 *   2) 1536×864  — the wide-desktop case.
 *
 * "Inside the box" = the control's bounding rect is within the dialog box's client rect (right ≤ box
 * right, left ≥ box left, bottom ≤ box bottom), with a 1–2px slack for borders. "Reachable" = the point
 * at the control's center hits the control itself (elementFromPoint), i.e. it is painted and not clipped
 * or covered. For the enabled controls (UP, Next, Last) we additionally run a Playwright trial click,
 * which performs the full actionability check (visible/stable/receives-events/enabled) WITHOUT clicking.
 *
 * Honest-verdict rule: if anything still clips at either viewport, the test SAYS SO (assertions fail) —
 * numbers and screenshots are emitted before the assertions so the report is grounded regardless.
 *
 * Requires the Docker test stack (am7test-am7-1) live on host :9443. Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pickerWrap.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const REST = '/AccountManagerService7/rest';

function sharedLoginBody() {
    return {
        schema: 'auth.credential',
        organizationPath: '/Development',
        name: 'e2etest_shared',
        credential: Buffer.from('password').toString('base64'),
        type: 'hashed_password'
    };
}

test.describe('Object picker — pagination wrap (viewport-independent, no overrides)', () => {
    test.describe.configure({ timeout: 90000 });

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
    });

    async function loginBrowser(page) {
        const resp = await page.request.post(REST + '/login', { data: sharedLoginBody() });
        if (!resp.ok() && resp.status() !== 204) {
            throw new Error('browser API login failed: HTTP ' + resp.status());
        }
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

    async function openImportPicker(page) {
        await page.waitForFunction(
            () => window.am7page && window.am7page.components && window.am7page.components.picker,
            { timeout: 15000 }
        );
        await page.evaluate(() => {
            window.__pickerSelected = null;
            window.am7page.components.picker.open({
                type: 'data.note',
                title: 'Import Data',
                multiSelect: true,
                onSelect: function (items) { window.__pickerSelected = items; }
            });
        });
        await expect(page.locator('.am7-picker-overlay')).toBeVisible({ timeout: 15000 });
        await page.locator('.am7-picker-overlay .result-nav-outer').first()
            .waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(500);
    }

    // Measure the picker box + toolbar/pagination controls in a single evaluate (one consistent layout).
    async function measure(page, label) {
        return await page.evaluate((lbl) => {
            const overlay = document.querySelector('.am7-picker-overlay');
            if (!overlay) return { label: lbl, error: 'no overlay' };
            const box = overlay.querySelector('.rounded-lg');
            if (!box) return { label: lbl, error: 'no box' };
            const boxR = box.getBoundingClientRect();

            const rect = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return { left: Math.round(r.left), right: Math.round(r.right), top: Math.round(r.top), bottom: Math.round(r.bottom), width: Math.round(r.width), height: Math.round(r.height) };
            };
            const insideBox = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return (r.right <= boxR.right + 2) && (r.left >= boxR.left - 2) && (r.top >= boxR.top - 2) && (r.bottom <= boxR.bottom + 2);
            };
            const centerHitsSelf = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                const cx = Math.round(r.left + r.width / 2);
                const cy = Math.round(r.top + r.height / 2);
                if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return false;
                const hit = document.elementFromPoint(cx, cy);
                return !!(hit && (el === hit || el.contains(hit) || hit.contains(el)));
            };
            const rightOverflow = (el) => el ? Math.round(el.getBoundingClientRect().right - boxR.right) : null;
            const btnByIcon = (ico) => {
                const spans = overlay.querySelectorAll('span.material-symbols-outlined, span.material-icons-24');
                for (const s of spans) {
                    if (s.textContent.trim() === ico) {
                        const b = s.closest('button');
                        if (b) return b;
                    }
                }
                return null;
            };

            const toolbar = overlay.querySelector('.result-nav.tab-container');
            const pager = overlay.querySelector('nav.result-nav');
            const inner = overlay.querySelector('.result-nav-inner');

            const controlNames = {
                upBtn: 'north_west',
                downBtn: 'south_east',
                pgFirst: 'keyboard_double_arrow_left',
                pgPrev: 'chevron_left',
                pgNext: 'chevron_right',
                pgLast: 'keyboard_double_arrow_right'
            };
            const controls = {};
            for (const [k, ico] of Object.entries(controlNames)) {
                const el = btnByIcon(ico);
                controls[k] = el ? {
                    present: true,
                    rect: rect(el),
                    rightOverflowPx: rightOverflow(el),
                    clippedRight: (el.getBoundingClientRect().right > boxR.right + 2),
                    insideBox: insideBox(el),
                    centerReachable: centerHitsSelf(el)
                } : { present: false };
            }

            // Wrap detection: if the pagination bar's top sits below the toolbar's top by more than a few
            // px, the pagination has wrapped onto its own row.
            let wrapped = null;
            if (toolbar && pager) {
                wrapped = (pager.getBoundingClientRect().top - toolbar.getBoundingClientRect().top) > 8;
            }

            return {
                label: lbl,
                viewport: { w: window.innerWidth, h: window.innerHeight },
                box: rect(box),
                innerHeight: inner ? Math.round(inner.getBoundingClientRect().height) : null,
                toolbar: toolbar ? { rect: rect(toolbar), insideBox: insideBox(toolbar), clippedRight: (toolbar.getBoundingClientRect().right > boxR.right + 2) } : null,
                pager: pager ? { rect: rect(pager), rightOverflowPx: rightOverflow(pager), insideBox: insideBox(pager), clippedRight: (pager.getBoundingClientRect().right > boxR.right + 2), centerReachable: centerHitsSelf(pager) } : null,
                pagerWrappedToSecondRow: wrapped,
                controls
            };
        }, label);
    }

    // A Playwright trial click runs full actionability checks (visible/stable/receives-events/enabled)
    // WITHOUT performing the click, so it does not change the page/pagination state.
    async function trialActionable(page, icon) {
        const loc = page.locator('.am7-picker-overlay')
            .locator(`button:has(span.material-symbols-outlined:text-is("${icon}"))`).first();
        try {
            await loc.click({ trial: true, timeout: 4000 });
            return true;
        } catch (e) {
            return false;
        }
    }

    async function runAtViewport(page, w, h, shotName) {
        await page.setViewportSize({ width: w, height: h });
        await loginBrowser(page);
        await openImportPicker(page);

        const meas = await measure(page, `${w}x${h} (deployed, no override)`);
        await page.screenshot({ path: `test-results/${shotName}` });

        // Trial-click actionability for the ENABLED controls (up-nav + Next + Last page). Down-nav is
        // 'inactive' without a selection, so we assert its reachability geometrically, not via click.
        const act = {
            upBtn: await trialActionable(page, 'north_west'),
            pgNext: await trialActionable(page, 'chevron_right'),
            pgLast: await trialActionable(page, 'keyboard_double_arrow_right')
        };

        console.log(`\n===== PICKER WRAP MEASUREMENT @ ${w}x${h} =====`);
        console.log(JSON.stringify(meas, null, 2));
        console.log('trial-click actionable: ' + JSON.stringify(act));
        console.log(`===== END @ ${w}x${h} =====\n`);

        // Assertions: box exists; pagination + up/down controls fully inside the box and reachable.
        expect(meas.box, 'dialog box present').toBeTruthy();
        expect(meas.pager, 'pagination bar present').toBeTruthy();
        expect(meas.pager.insideBox, `pagination bar fully inside dialog @ ${w}`).toBeTruthy();
        expect(meas.pager.clippedRight, `pagination bar NOT clipped right @ ${w}`).toBeFalsy();
        expect(meas.pager.centerReachable, `pagination bar center reachable @ ${w}`).toBeTruthy();

        for (const k of ['upBtn', 'downBtn', 'pgFirst', 'pgPrev', 'pgNext', 'pgLast']) {
            const c = meas.controls[k];
            if (c.present) {
                expect(c.insideBox, `control ${k} fully inside dialog @ ${w}`).toBeTruthy();
                expect(c.clippedRight, `control ${k} NOT clipped right @ ${w}`).toBeFalsy();
                expect(c.centerReachable, `control ${k} center reachable @ ${w}`).toBeTruthy();
            }
        }

        // Enabled controls must pass Playwright actionability (clickable without force).
        expect(act.upBtn, `UP folder-nav actionable @ ${w}`).toBeTruthy();
        expect(act.pgNext, `NEXT-page button actionable @ ${w}`).toBeTruthy();
        expect(act.pgLast, `LAST-page button actionable @ ${w}`).toBeTruthy();

        return meas;
    }

    test('all pagination + nav controls inside dialog & reachable at 1280x800', async ({ page }) => {
        await runAtViewport(page, 1280, 800, 'picker-wrap-1280.png');
    });

    test('all pagination + nav controls inside dialog & reachable at 1536x864', async ({ page }) => {
        await runAtViewport(page, 1536, 864, 'picker-wrap-1536.png');
    });
});
