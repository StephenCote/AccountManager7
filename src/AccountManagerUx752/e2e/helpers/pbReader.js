/**
 * PictureBook V3 reader toolbar helpers.
 *
 * Since 2026-11-16 the reader toolbar is grouped (reading · setup [Style, Characters] · Render · Tools · Export /
 * Fullscreen / Delete). Locations, Composition board, Cover art, Review queue, Workflow canvas, the chat config
 * pick and the auto-approve toggle live in the "Tools" overflow — the shared components/contextMenu.js menu,
 * mounted at the app root (not inside [data-testid="pb2-reader"]). Each item keeps the data-testid / title its
 * old toolbar control had, so a spec opens the menu first and then uses the same locator on `page`.
 */
import { expect } from '@playwright/test';

/** Open the reader's Tools overflow and wait for its menu. */
export async function openReaderTools(page, opts) {
    const timeout = (opts && opts.timeout) || 30000;
    const btn = page.getByTestId('pb2-tools-menu');
    await expect(btn).toBeVisible({ timeout });
    await btn.click();
    await expect(page.locator('.context-menu[role="menu"]')).toBeVisible({ timeout });
}

/** Close the Tools overflow without choosing an item. */
export async function closeReaderTools(page) {
    await page.keyboard.press('Escape');
    await expect(page.locator('.context-menu[role="menu"]')).toHaveCount(0);
}

/** Open the Tools overflow and click one of its items by data-testid. */
export async function clickReaderTool(page, testid, opts) {
    await openReaderTools(page, opts);
    await page.getByTestId(testid).click();
}

/** The description e2e fixtures give a book whose test is not about style (the render gate needs one). */
export const E2E_STYLE_DESCRIPTION = 'soft watercolor storybook illustration, warm muted palette';

/**
 * Since 2026-11-16 scene render / render-plan / regenerate-background / render-all refuse (409, and the
 * reader disables Render) until the book's EFFECTIVE style has a description. A fixture book that is
 * not testing style gets one here: GET /{book}/style, and PUT {description} only when the effective
 * description is blank, so a book that inherits one from its series is left alone.
 * `pbRoot` is the …/rest/olio/picture-book root.
 */
export async function ensureBookStyleDescription(request, pbRoot, bookOid, description) {
    const url = pbRoot + '/' + bookOid + '/style';
    const get = await request.get(url);
    const getText = await get.text();
    expect(get.ok(), 'GET /style: HTTP ' + get.status() + ' ' + getText.slice(0, 300)).toBe(true);
    const st = JSON.parse(getText);
    const cur = st && st.effective && st.effective.fields ? st.effective.fields.description : null;
    if (cur != null && String(cur).trim() !== '') return st;
    const put = await request.put(url, { data: { description: description || E2E_STYLE_DESCRIPTION } });
    const putText = await put.text();
    expect(put.ok(), 'PUT /style {description}: HTTP ' + put.status() + ' ' + putText.slice(0, 300)).toBe(true);
    return JSON.parse(putText);
}

/**
 * Wizard steps 3 → 4 → reader: "Continue to Style", then fill and save the step-4 style description
 * ("Continue to Book" is disabled until the SAVED effective description is non-blank), then
 * "Continue to Book". Returns once the click is made; callers wait for the reader route themselves.
 */
export async function completeWizardStyleStep(page, description, opts) {
    const timeout = (opts && opts.timeout) || 60000;
    await page.getByTestId('pb-wizard-continue-style').click();
    await expect(page.getByTestId('pb-wizard-style')).toBeVisible({ timeout });
    const toBook = page.getByTestId('pb-wizard-continue-book');
    const desc = page.getByTestId('pb2-style-description');
    await expect(desc).toBeVisible({ timeout });
    if (((await desc.inputValue()) || '').trim() === '') {
        await expect(toBook, 'Continue to Book is disabled while the book has no style description').toBeDisabled();
        await desc.fill(description || E2E_STYLE_DESCRIPTION);
        await page.getByTestId('pb2-style-save').click();
    }
    await expect(toBook).toBeEnabled({ timeout });
    await toBook.click();
}
