/**
 * Object-picker WIDTH / CLIPPING measurement — visual verification of the coordinator's hypothesis
 * that the picker dialog's OLD width (`max-w-4xl` ≈ 56rem on the inner box, picker.js:347) clipped the
 * embedded list's toolbar-nav and pagination controls off the right edge on a wide desktop, so a human
 * could not click them (while a selector-driven Playwright test clicks by DOM regardless of clipping).
 *
 * The current picker.js is at the NEW width (inline `width:80vw;max-width:80vw`). This test does NOT
 * edit picker.js. It measures the SAME live picker in two states, on a realistic desktop viewport:
 *
 *   BEFORE — inject a `<style> !important` rule forcing `.am7-picker-overlay .rounded-lg` back to
 *            `width:56rem;max-width:56rem` (simulating the old `max-w-4xl`), then measure + screenshot.
 *   AFTER  — remove that override (real 80vw), then measure + screenshot.
 *
 * For each state it captures getBoundingClientRect() of the dialog box, the toolbar-nav row
 * (.result-nav.tab-container), the pagination bar (nav.result-nav), and the individual UP/DOWN nav
 * buttons + pagination arrow buttons, plus the picker content wrapper's scrollWidth vs clientWidth
 * (horizontal-overflow signal). "Clipped" = a control's right edge extends past the dialog box's right
 * edge, or the point at the control's center does not hit the control itself (painted-off / covered).
 *
 * Viewport is 1536×864 (a common wide-desktop size), NOT Playwright's default 1280 — the whole point of
 * the hypothesis is that on a WIDE screen 56rem is ~half the width, leaving room for the right-aligned
 * pagination bar to fall outside the narrow dialog.
 *
 * Honest-verdict rule: if the controls are NOT clipped even at 56rem, this test SAYS SO and asserts the
 * hypothesis is false — it does not force a "clipping proven" conclusion.
 *
 * Requires the Docker test stack (am7test-am7-1) live on host :9443. Run:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/pickerClip.spec.js \
 *       --project=chromium --workers=1
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser } from './helpers/api.js';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'https://127.0.0.1:9443';
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

test.describe('Object picker — width/clipping measurement (56rem vs 80vw)', () => {
    test.describe.configure({ timeout: 90000 });
    // Realistic wide-desktop viewport — the hypothesis is specifically about wide screens.
    test.use({ viewport: { width: 1536, height: 864 } });

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
        // Let the embedded list's first search + redraw settle so the toolbar/pagination row exists.
        await page.locator('.am7-picker-overlay .result-nav-outer').first()
            .waitFor({ state: 'visible', timeout: 15000 });
        await page.waitForTimeout(500);
    }

    // Measure the picker box + its toolbar/pagination controls, all in one evaluate so every rect is
    // read at the same layout. Returns null-safe descriptors; "clip" is computed against the box rect.
    async function measure(page, label) {
        return await page.evaluate((lbl) => {
            const overlay = document.querySelector('.am7-picker-overlay');
            if (!overlay) return { label: lbl, error: 'no overlay' };
            const box = overlay.querySelector('.rounded-lg');
            if (!box) return { label: lbl, error: 'no box' };
            const boxR = box.getBoundingClientRect();
            const content = box.children[1] || null; // the flex-1 overflow-auto wrapper (picker.js:368)

            const rect = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return { left: Math.round(r.left), right: Math.round(r.right), top: Math.round(r.top), bottom: Math.round(r.bottom), width: Math.round(r.width) };
            };
            // Does the point at el's center hit el (or a descendant)? False ⇒ painted-off / clipped / covered.
            const centerHitsSelf = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                const cx = Math.round(r.left + r.width / 2);
                const cy = Math.round(r.top + r.height / 2);
                if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return false;
                const hit = document.elementFromPoint(cx, cy);
                return !!(hit && (el === hit || el.contains(hit) || hit.contains(el)));
            };
            const rightOverflow = (el) => {
                if (!el) return null;
                return Math.round(el.getBoundingClientRect().right - boxR.right);
            };
            // Find a button in the overlay whose material-symbol span text is exactly `ico`.
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
                    rightOverflowPx: rightOverflow(el),   // >0 ⇒ past box right edge
                    clippedRight: (el.getBoundingClientRect().right > boxR.right + 1),
                    centerReachable: centerHitsSelf(el)
                } : { present: false };
            }

            return {
                label: lbl,
                viewport: { w: window.innerWidth, h: window.innerHeight },
                box: rect(box),
                content: content ? {
                    rect: rect(content),
                    scrollWidth: content.scrollWidth,
                    clientWidth: content.clientWidth,
                    horizontalOverflowPx: content.scrollWidth - content.clientWidth
                } : null,
                toolbar: toolbar ? { rect: rect(toolbar), rightOverflowPx: rightOverflow(toolbar), clippedRight: (toolbar.getBoundingClientRect().right > boxR.right + 1) } : null,
                pager: pager ? { rect: rect(pager), rightOverflowPx: rightOverflow(pager), clippedRight: (pager.getBoundingClientRect().right > boxR.right + 1), centerReachable: centerHitsSelf(pager) } : null,
                controls
            };
        }, label);
    }

    test('measure toolbar/pagination reachability at 56rem vs 80vw', async ({ page }) => {
        await loginBrowser(page);
        await openImportPicker(page);

        // ── BEFORE: force the box back to the OLD max-w-4xl width (56rem) via an !important override
        //           (beats the inline style="width:80vw"), WITHOUT touching picker.js. ──────────────
        await page.evaluate(() => {
            const s = document.createElement('style');
            s.id = 'pnclip-override';
            s.textContent = '.am7-picker-overlay .rounded-lg{width:56rem !important;max-width:56rem !important;}';
            document.head.appendChild(s);
        });
        await page.waitForTimeout(400);
        const before = await measure(page, '56rem (simulated max-w-4xl)');
        await page.screenshot({ path: 'test-results/picker-clip-BEFORE-56rem.png' });

        // ── AFTER: remove the override → real 80vw from picker.js. ────────────────────────────────
        await page.evaluate(() => {
            const s = document.getElementById('pnclip-override');
            if (s) s.remove();
        });
        await page.waitForTimeout(400);
        const after = await measure(page, '80vw (current picker.js)');
        await page.screenshot({ path: 'test-results/picker-clip-AFTER-80vw.png' });

        // Emit the raw measurements so the verdict is grounded in numbers, not adjectives.
        console.log('\n===== PICKER CLIP MEASUREMENTS =====');
        console.log('BEFORE (56rem):\n' + JSON.stringify(before, null, 2));
        console.log('AFTER  (80vw):\n' + JSON.stringify(after, null, 2));
        console.log('===== END MEASUREMENTS =====\n');

        // Sanity: the box really is narrower in BEFORE than AFTER (the override took effect).
        expect(before.box.width, 'box width at 56rem (~896px)').toBeLessThan(after.box.width);
        // 56rem = 896px; allow a little slack for borders.
        expect(before.box.width, 'box width ≈ 56rem').toBeLessThan(920);

        // BEFORE (56rem) — the measured regression: the right-aligned pagination bar overflows the
        // narrow dialog. Its Next/Last page buttons fall OUTSIDE the box and are not reachable. This is
        // asserted from measurement (not forced) so this spec is a real before/after width proof — if a
        // future change shrinks the dialog back, this fails. (Toolbar + up/down stay left-aligned and
        // in-box even at 56rem — the clip is specifically the pagination controls.)
        expect(before.pager, 'pagination bar present at 56rem').toBeTruthy();
        expect(before.pager.clippedRight, 'pagination bar IS clipped past dialog right edge at 56rem').toBeTruthy();
        expect(before.pager.centerReachable, 'pagination bar center NOT reachable at 56rem').toBeFalsy();
        expect(before.controls.pgLast.present && before.controls.pgLast.clippedRight,
            'LAST-page button clipped off-dialog at 56rem').toBeTruthy();
        expect(before.controls.pgNext.present && before.controls.pgNext.clippedRight,
            'NEXT-page button clipped off-dialog at 56rem').toBeTruthy();

        // The AFTER (80vw) state MUST have the toolbar + pagination fully inside the dialog and reachable —
        // this is the state we ship, and it is the actual assertion of the fix.
        expect(after.toolbar, 'toolbar present at 80vw').toBeTruthy();
        expect(after.toolbar.clippedRight, 'toolbar NOT clipped at 80vw').toBeFalsy();
        if (after.pager) {
            expect(after.pager.clippedRight, 'pagination bar NOT clipped at 80vw').toBeFalsy();
        }
        expect(after.content.horizontalOverflowPx, 'no horizontal overflow in picker content at 80vw')
            .toBeLessThanOrEqual(2);
        // Every present nav/pagination control must be reachable (center hits itself) at 80vw.
        for (const [k, c] of Object.entries(after.controls)) {
            if (c.present) {
                expect(c.clippedRight, `control ${k} NOT clipped at 80vw`).toBeFalsy();
                expect(c.centerReachable, `control ${k} center reachable at 80vw`).toBeTruthy();
            }
        }
    });
});
