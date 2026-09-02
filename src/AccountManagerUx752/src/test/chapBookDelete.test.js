// @vitest-environment jsdom
/**
 * ChapBook delete-bug regression tests (Bug A / Bug B / Issue-3 root cause).
 *
 * THE BUG: `Dialog.confirm(cfg, callback)` (components/dialogCore.js) honors the 2nd `callback`
 * argument ONLY when `cfg` is a STRING (backward-compat form). When `cfg` is an OBJECT it returns a
 * `Promise<boolean>` and SILENTLY IGNORES the callback. Three ChapBook handlers called it as
 *   Dialog.confirm({title:'…', destructive:true}, async (ok) => { …DELETE… })
 * so the delete body never ran — "Remove from Queue", "Delete ChapBook" and "Remove page" all no-oped
 * with no error, warning, or log. The fix awaits the Promise instead:
 *   let ok = await Dialog.confirm({…}); if (!ok) return; …DELETE…
 *
 * Two layers of coverage here:
 *   1. dialogCore contract — proves the REAL primitive's object form returns a Promise and ignores a
 *      2nd callback arg (the exact trap), resolves true on confirm and false on cancel.
 *   2. doDeleteBook handler — proves the FIXED handler actually fires the DELETE fetch on confirm and
 *      fires nothing on cancel. On the old callback-form code the DELETE never fires, so this fails.
 *
 * These are unit-level; the end-to-end behavior against the live backend is covered by the three
 * Playwright cases added to e2e/chapBook.spec.js.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';

function renderDialogsInto() {
    let container = document.createElement('div');
    document.body.appendChild(container);
    m.render(container, Dialog.loadDialogs());
    return container;
}

describe('Dialog.confirm dual-API contract (root cause of the ChapBook delete bug)', () => {
    beforeEach(() => { Dialog._reset(); document.body.innerHTML = ''; });

    it('OBJECT form returns a Promise and IGNORES a 2nd callback arg (the exact trap)', async () => {
        let cb = vi.fn();
        let ret = Dialog.confirm({ title: 'Delete ChapBook', message: 'Sure?', confirmLabel: 'Delete', destructive: true }, cb);

        // It is a thenable — NOT undefined — so a caller passing a callback gets no callback invocation.
        expect(ret && typeof ret.then).toBe('function');
        expect(cb).not.toHaveBeenCalled();

        // Click the destructive confirm button in the rendered dialog.
        let container = renderDialogsInto();
        let btn = container.querySelector('.am7-dialog-btn-destructive');
        expect(btn, 'destructive confirm button not rendered').toBeTruthy();
        btn.click();

        let resolved = await ret;
        expect(resolved).toBe(true);
        // The callback is STILL never called even after confirm — this is what broke the handlers.
        expect(cb).not.toHaveBeenCalled();
    });

    it('OBJECT form resolves FALSE when cancelled', async () => {
        let ret = Dialog.confirm({ title: 'Delete ChapBook', message: 'Sure?', destructive: true });
        let container = renderDialogsInto();
        let cancelBtn = [...container.querySelectorAll('.am7-dialog-btn')].find(b => /Cancel/.test(b.textContent));
        expect(cancelBtn, 'cancel button not rendered').toBeTruthy();
        cancelBtn.click(); // Dialog.close() → cfg.onClose() → resolve(false)
        let resolved = await ret;
        expect(resolved).toBe(false);
    });

    it('STRING form (backward-compat) DOES invoke the callback', () => {
        let cb = vi.fn();
        let ret = Dialog.confirm('Are you sure?', cb);
        expect(ret).toBeUndefined(); // string form returns nothing
        let container = renderDialogsInto();
        let confirmBtn = [...container.querySelectorAll('.am7-dialog-btn')].find(b => /Confirm/.test(b.textContent));
        expect(confirmBtn, 'confirm button not rendered').toBeTruthy();
        confirmBtn.click();
        expect(cb).toHaveBeenCalledTimes(1);
    });
});

describe('doDeleteBook — fires DELETE only when the confirm resolves true', () => {
    let fetchMock;

    beforeEach(() => {
        Dialog._reset();
        // Every fetch (deleteBook DELETE, then loadMyBooks GET /books) succeeds; /books returns [].
        fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] }));
        global.fetch = fetchMock;
        if (typeof page.toast !== 'function') page.toast = () => {};
        vi.spyOn(page, 'toast').mockImplementation(() => {});
    });

    it('confirm → true: issues DELETE /rest/olio/chap-book/{objectId}', async () => {
        let { doDeleteBook } = await import('../features/chapBook.js');
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(true);

        await doDeleteBook({ objectId: 'book-abc-123', name: 'My ChapBook' });

        let deleteCalls = fetchMock.mock.calls.filter(c => c[1] && c[1].method === 'DELETE');
        expect(deleteCalls.length, 'exactly one DELETE should fire on confirm').toBe(1);
        expect(deleteCalls[0][0]).toContain('/rest/olio/chap-book/book-abc-123');
    });

    it('confirm → false: issues NO DELETE at all', async () => {
        let { doDeleteBook } = await import('../features/chapBook.js');
        vi.spyOn(Dialog, 'confirm').mockResolvedValue(false);

        await doDeleteBook({ objectId: 'book-abc-456', name: 'My ChapBook' });

        let deleteCalls = fetchMock.mock.calls.filter(c => c[1] && c[1].method === 'DELETE');
        expect(deleteCalls.length, 'no DELETE should fire when cancelled').toBe(0);
    });
});
