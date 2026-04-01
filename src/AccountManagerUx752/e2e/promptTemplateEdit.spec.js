/**
 * Test prompt template viewing/editing in the object form.
 * Uses shared test user (NEVER admin).
 */
import { test, expect } from '@playwright/test';
import { ensureSharedTestUser, apiLogin, apiLogout } from './helpers/api.js';
import { request as pwRequest } from '@playwright/test';

const REST = 'https://localhost:8899/AccountManagerService7/rest';
function b64(str) { return Buffer.from(str).toString('base64'); }
async function newCtx() { return await pwRequest.newContext({ baseURL: 'https://localhost:8899', ignoreHTTPSErrors: true }); }
async function safeJson(resp) { if (!resp.ok()) return null; try { return JSON.parse(await resp.text()); } catch { return null; } }

test('View prompt template sections', async ({ page, request }) => {
    let shared = await ensureSharedTestUser(null);
    await apiLogin(request, { user: shared.testUserName, password: shared.testPassword });

    // Ensure templates are initialized
    let ctx = await newCtx();
    await ctx.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development',
            name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
    });
    await ctx.post(REST + '/chat/library/prompt/init');

    // Find a pictureBook template
    let resp = await ctx.post(REST + '/model/search', {
        data: { schema: 'io.query', type: 'olio.llm.promptTemplate',
            fields: [{ name: 'name', comparator: 'equals', value: 'pictureBook.extract-scenes' }],
            request: ['id', 'objectId', 'name', 'sections'], recordCount: 1 }
    });
    let sr = await safeJson(resp);
    let template = (sr && sr.results && sr.results.length) ? sr.results[0] : null;
    await ctx.get(REST + '/logout');
    await ctx.dispose();

    if (!template) {
        console.log('No pictureBook.extract-scenes template found — skip');
        return;
    }

    console.log('Template objectId:', template.objectId);
    console.log('Template sections:', JSON.stringify(template.sections));

    // Login via API and transfer cookies to browser
    let loginCtx = await newCtx();
    await loginCtx.post(REST + '/login', {
        data: { schema: 'auth.credential', organizationPath: '/Development',
            name: shared.testUserName, credential: b64(shared.testPassword), type: 'hashed_password' }
    });
    let cookies = (await loginCtx.storageState()).cookies;
    await page.context().addCookies(cookies);
    await loginCtx.dispose();

    await page.goto('/');
    await page.waitForTimeout(1500);

    // Capture errors
    let errors = [];
    page.on('console', msg => {
        if (msg.type() === 'error') errors.push(msg.text());
    });

    // Navigate to the template object view — route is /view/:type/:objectId
    await page.goto('#!/view/olio.llm.promptTemplate/' + template.objectId);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'e2e/screenshots/prompt-template-view.png' });

    // Check what's visible
    let bodyText = await page.locator('body').innerText();
    console.log('Page text (first 500):', bodyText.substring(0, 500));

    // Look for section content
    let hasSections = bodyText.includes('system') || bodyText.includes('user') || bodyText.includes('Section');
    console.log('Has section-related text:', hasSections);

    // Check for errors
    let formErrors = errors.filter(e => e.includes('Invalid type') || e.includes('Invalid field') || e.includes('promptSection') || e.includes('prepareInstance'));
    console.log('Form errors:', formErrors.length ? formErrors.slice(0, 5).join(' | ') : 'none');
    expect(formErrors.length, 'No Invalid type/field errors').toBe(0);

    // Try clicking on sections table if visible
    let sectionRows = page.locator('table tbody tr, [class*="border"][class*="rounded"]');
    let rowCount = await sectionRows.count();
    console.log('Section rows visible:', rowCount);

    if (rowCount > 0) {
        // Click the edit (pencil) button on the first section
        let editBtn = page.locator('button:has(span:text("edit")), span:text("edit")').first();
        if (await editBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
            await editBtn.click();
            await page.waitForTimeout(1500);
            await page.screenshot({ path: 'e2e/screenshots/prompt-template-section-edit.png' });

            let editText = await page.locator('body').innerText();
            let hasLines = editText.includes('lines') || editText.includes('Lines');
            let hasCondition = editText.includes('Condition') || editText.includes('condition');
            console.log('Edit mode shows lines field:', hasLines);
            console.log('Edit mode shows condition field:', hasCondition);

            // Check for textareas (lines should be a textlist/textarea)
            let textareas = await page.locator('textarea').count();
            console.log('Textareas visible in edit mode:', textareas);

            // Scroll the form content area to see the lines field
            await page.evaluate(() => {
                let el = document.querySelector('.object-content, [class*="overflow-y-auto"], main');
                if (el) el.scrollTop = el.scrollHeight;
                else window.scrollTo(0, document.body.scrollHeight);
            });
            await page.waitForTimeout(1000);
            await page.screenshot({ path: 'e2e/screenshots/prompt-template-section-scrolled.png' });

            // Check if any textarea has prompt content
            for (let i = 0; i < textareas; i++) {
                let val = await page.locator('textarea').nth(i).inputValue();
                if (val && val.length > 20) {
                    console.log('Textarea ' + i + ' has content (' + val.length + ' chars): ' + val.substring(0, 80) + '...');
                } else {
                    console.log('Textarea ' + i + ': ' + JSON.stringify(val || '(empty)'));
                }
            }
        } else {
            console.log('No edit button found on section rows');
        }
    }

    await apiLogout(request);
});
