// @vitest-environment jsdom
/**
 * Regression tests for three P1 overlay/dialog breakages (2026-09-26).
 *
 * P1-1  Card Game "Exit game" / "Delete theme" used the LEGACY string form
 *       `dialog.confirm("msg", function(ok){ if (ok) ... })`. dialogCore invokes that callback with NO
 *       argument, so `ok` is always undefined and the body never ran. Fixed by switching both callers to
 *       the Promise form. The contract tests below pin BOTH halves: the string form passes nothing, and the
 *       Promise form resolves true on Confirm / false on Cancel or backdrop.
 *
 * P1-3  pictureBookWorkflow's Share / New Chapter modals were hand-rolled `position:fixed` overlays at
 *       z-index:500, which sat ON TOP of ObjectPicker.PickerView (z-[60]) — "Choose manuscript" opened
 *       underneath the modal. Both now go through Dialog.open (shared stack, z = 40 + 10*depth), so the
 *       picker (60) and toasts (50) always paint above them. The tests open the real dialogs from the real
 *       feature module, render them via Dialog.loadDialogs(), and check the fields, data-* hooks,
 *       validation toast, picker hand-off and z-index.
 *
 * P1-2  (duplicate toast/dialog hosts on /cardGame) is a mount-tree property of the router layout and is
 *       covered by e2e/cardGameOverlays.spec.js, not here.
 *
 * Also pins the new `attrs` pass-through on Dialog action buttons, which is what lets the New Chapter
 * dialog keep its `data-create-chapter` test hook while using the standard footer.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { Dialog } from '../components/dialogCore.js';
import { page } from '../core/pageClient.js';
import { am7model } from '../core/model.js';
import { ObjectPicker } from '../components/picker.js';

function renderDialogsInto() {
    let container = document.createElement('div');
    document.body.appendChild(container);
    m.render(container, Dialog.loadDialogs());
    return container;
}

function rerender(container) {
    m.render(container, Dialog.loadDialogs());
    return container;
}

function buttonWithText(container, re) {
    return [...container.querySelectorAll('.am7-dialog-btn')].find(b => re.test(b.textContent));
}

// Flush microtasks so `await Dialog.confirm(...)` continuations run.
const tick = () => new Promise(r => setTimeout(r, 0));

describe('P1-1 — Dialog.confirm contract that broke the Card Game confirms', () => {
    beforeEach(() => { Dialog._reset(); document.body.innerHTML = ''; });

    it('STRING form invokes the callback with NO argument — `function(ok){ if (ok) ... }` never runs its body', () => {
        let body = vi.fn();
        // This is the exact shape gameView.js / themes.js used before the fix.
        Dialog.confirm('Exit game? Progress will be lost.', function (ok) { if (ok) body(); });
        let container = renderDialogsInto();
        buttonWithText(container, /Confirm/).click();
        expect(body, 'the guarded body ran — the string form must not be passing a truthy arg').not.toHaveBeenCalled();
    });

    it('PROMISE form resolves TRUE on the confirm button (the fixed Exit/Delete path executes)', async () => {
        let body = vi.fn();
        (async function handler() {
            let ok = await Dialog.confirm({ title: 'Exit Game', message: 'Exit game? Progress will be lost.', confirmLabel: 'Exit', destructive: true });
            if (!ok) return;
            body();
        })();
        let container = renderDialogsInto();
        let btn = container.querySelector('.am7-dialog-btn-destructive');
        expect(btn, 'destructive confirm button not rendered').toBeTruthy();
        expect(btn.textContent).toContain('Exit');
        btn.click();
        await tick();
        expect(body).toHaveBeenCalledTimes(1);
        expect(Dialog.stackDepth()).toBe(0);
    });

    it('PROMISE form resolves FALSE on Cancel and on backdrop click (nothing executes)', async () => {
        let body = vi.fn();
        let p1 = Dialog.confirm({ title: 'Delete Theme', message: "Delete theme 'x'?", confirmLabel: 'Delete', destructive: true });
        let container = renderDialogsInto();
        buttonWithText(container, /Cancel/).click();
        expect(await p1).toBe(false);

        let p2 = Dialog.confirm({ title: 'Delete Theme', message: "Delete theme 'x'?", confirmLabel: 'Delete', destructive: true });
        rerender(container);
        let backdrop = container.querySelector('.am7-dialog-backdrop');
        expect(backdrop).toBeTruthy();
        backdrop.dispatchEvent(new MouseEvent('click', { bubbles: true })); // target === currentTarget → close
        expect(await p2).toBe(false);
        expect(body).not.toHaveBeenCalled();
    });
});

describe('Dialog action `attrs` pass-through (used for data-* test hooks on footer buttons)', () => {
    beforeEach(() => { Dialog._reset(); document.body.innerHTML = ''; });

    it('merges attrs onto the button but keeps class/disabled/onclick managed by dialogCore', () => {
        let clicked = vi.fn();
        Dialog.open({
            title: 'T',
            content: 'body',
            actions: [
                { label: 'Go', primary: true, onclick: clicked, attrs: { 'data-go': 'yes', class: 'HACK', disabled: true } }
            ]
        });
        let container = renderDialogsInto();
        let btn = container.querySelector('[data-go="yes"]');
        expect(btn, 'data-* attr not passed through').toBeTruthy();
        expect(btn.className).toContain('am7-dialog-btn-primary');
        expect(btn.className).not.toContain('HACK');
        expect(btn.disabled, 'attrs.disabled must not override act.disabled').toBe(false);
        btn.click();
        expect(clicked).toHaveBeenCalledTimes(1);
    });

    it('actions without attrs still render exactly as before', () => {
        Dialog.open({ title: 'T', content: 'body', actions: [{ label: 'Only', onclick: () => {} }] });
        let container = renderDialogsInto();
        let btn = buttonWithText(container, /Only/);
        expect(btn).toBeTruthy();
        expect(btn.className).toContain('am7-dialog-btn-secondary');
    });
});

describe('P1-3 — pictureBookWorkflow Share / New Chapter dialogs live on the shared dialog stack', () => {
    let feature;
    let fetchMock;

    beforeEach(async () => {
        // closeAll() runs each dialog's onClose so the feature's "open" flags are cleared;
        // _reset() alone would leave chapterDialog/memberDialog stuck at true.
        Dialog.closeAll();
        Dialog._reset();
        document.body.innerHTML = '';
        if (!am7model._page) am7model._page = page;
        fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ slug: 'my-chapter-2', enrolled: 1, requested: 1 }) }));
        global.fetch = fetchMock;
        if (typeof page.toast !== 'function') page.toast = () => {};
        vi.spyOn(page, 'toast').mockImplementation(() => {});
        feature = await import('../features/pictureBookWorkflow.js');
    });

    afterEach(() => {
        vi.restoreAllMocks();
        Dialog.closeAll();
        Dialog._reset();
    });

    it('New Chapter: opens ONE dialog on the shared stack at z-index 40 (below the picker overlay z-[60])', () => {
        feature.openChapterDialog();
        expect(Dialog.stackDepth()).toBe(1);
        let container = renderDialogsInto();
        let backdrop = container.querySelector('.am7-dialog-backdrop[role="dialog"]');
        expect(backdrop).toBeTruthy();
        expect(String(backdrop.style.zIndex)).toBe('40');
        // The picker overlay is a fixed Tailwind z-[60] layer — it must stay above the dialog.
        expect(ObjectPicker.PickerView.view.toString()).toContain('z-[60]');
        expect(container.querySelector('.am7-dialog-title').textContent).toBe('New Chapter');
        // A second click must NOT push a duplicate.
        feature.openChapterDialog();
        expect(Dialog.stackDepth()).toBe(1);
    });

    it('New Chapter: keeps every field, label and data-* hook the e2e suite relies on', () => {
        feature.openChapterDialog();
        let container = renderDialogsInto();
        expect(container.querySelector('input[placeholder="my-chapter-2"]')).toBeTruthy();
        expect(container.querySelector('input[placeholder="Chapter Two"]')).toBeTruthy();
        let pick = container.querySelector('[data-pick-source]');
        expect(pick).toBeTruthy();
        expect(pick.textContent).toContain('Choose manuscript');
        let create = container.querySelector('[data-create-chapter]');
        expect(create, 'data-create-chapter hook must survive the move to the dialog footer').toBeTruthy();
        expect(create.closest('.am7-dialog-footer'), 'Create must be a standard footer action').toBeTruthy();
        expect(create.textContent).toContain('Create');
        expect(create.disabled).toBe(false);
        expect(buttonWithText(container, /Cancel/)).toBeTruthy();
        // Boundary controls only appear once a manuscript is chosen — none yet.
        expect(container.querySelector('[data-detect-boundaries]')).toBeNull();
    });

    it('New Chapter: "Choose manuscript" hands off to ObjectPicker.open for data.data', () => {
        let openSpy = vi.spyOn(ObjectPicker, 'open').mockImplementation(async () => {});
        feature.openChapterDialog();
        let container = renderDialogsInto();
        container.querySelector('[data-pick-source]').click();
        expect(openSpy).toHaveBeenCalledTimes(1);
        let opts = openSpy.mock.calls[0][0];
        expect(opts.type).toBe('data.data');
        expect(opts.title).toBe('Select the source manuscript for this chapter');
        expect(typeof opts.onSelect).toBe('function');
        // The dialog must remain open underneath the picker.
        expect(Dialog.stackDepth()).toBe(1);

        // Simulate the picker returning a manuscript: the dialog now shows the name + detect button.
        opts.onSelect({ objectId: 'ms-1', name: 'The Verse.docx' });
        rerender(container);
        expect(container.querySelector('[data-pick-source]').textContent).toContain('Change manuscript');
        expect(container.textContent).toContain('The Verse.docx');
        expect(container.querySelector('[data-detect-boundaries]')).toBeTruthy();
    });

    it('New Chapter: Create with an empty slug toasts "Slug is required", sends nothing, stays open', async () => {
        feature.openChapterDialog();
        let container = renderDialogsInto();
        container.querySelector('[data-create-chapter]').click();
        await tick();
        let errToasts = page.toast.mock.calls.filter(c => c[0] === 'error');
        expect(errToasts.length).toBe(1);
        expect(errToasts[0][1]).toBe('Slug is required');
        expect(fetchMock).not.toHaveBeenCalled();
        expect(Dialog.stackDepth()).toBe(1);
    });

    it('New Chapter: Create with a slug POSTs the chapter and closes the dialog', async () => {
        feature.openChapterDialog();
        let container = renderDialogsInto();
        let slug = container.querySelector('input[placeholder="my-chapter-2"]');
        slug.value = 'my-chapter-2';
        slug.dispatchEvent(new Event('input', { bubbles: true }));
        container.querySelector('[data-create-chapter]').click();
        await tick(); await tick();
        let posts = fetchMock.mock.calls.filter(c => c[1] && c[1].method === 'POST');
        expect(posts.length).toBe(1);
        expect(posts[0][0]).toContain('/rest/olio/picture-book/chapter');
        expect(JSON.parse(posts[0][1].body).slug).toBe('my-chapter-2');
        let okToasts = page.toast.mock.calls.filter(c => c[0] === 'success');
        expect(okToasts.length).toBe(1);
        expect(okToasts[0][1]).toContain('Chapter created: my-chapter-2');
        expect(Dialog.stackDepth(), 'dialog must close after a successful create').toBe(0);
        rerender(container);
        expect(container.querySelector('[data-create-chapter]')).toBeNull();
    });

    it('New Chapter: Cancel closes the dialog and resets the form (reopen shows an empty slug)', () => {
        feature.openChapterDialog();
        let container = renderDialogsInto();
        let slug = container.querySelector('input[placeholder="my-chapter-2"]');
        slug.value = 'draft-slug';
        slug.dispatchEvent(new Event('input', { bubbles: true }));
        buttonWithText(container, /Cancel/).click();
        expect(Dialog.stackDepth()).toBe(0);
        feature.openChapterDialog();
        rerender(container);
        expect(container.querySelector('input[placeholder="my-chapter-2"]').value).toBe('');
    });

    it('Share Book: renders the username textarea, posts the names and closes', async () => {
        feature.openMemberDialog();
        let container = renderDialogsInto();
        expect(container.querySelector('.am7-dialog-title').textContent).toBe('Share Book');
        let ta = container.querySelector('textarea[placeholder="user1, user2"]');
        expect(ta).toBeTruthy();
        expect(String(container.querySelector('.am7-dialog-backdrop').style.zIndex)).toBe('40');
        ta.value = 'alice, bob';
        ta.dispatchEvent(new Event('input', { bubbles: true }));
        container.querySelector('[data-share-submit]').click();
        await tick(); await tick();
        let posts = fetchMock.mock.calls.filter(c => c[1] && c[1].method === 'POST');
        expect(posts.length).toBe(1);
        expect(posts[0][0]).toContain('/members');
        expect(JSON.parse(posts[0][1].body).userNames).toEqual(['alice', 'bob']);
        expect(Dialog.stackDepth()).toBe(0);
    });

    it('Share Book: Cancel closes without posting', () => {
        feature.openMemberDialog();
        let container = renderDialogsInto();
        buttonWithText(container, /Cancel/).click();
        expect(Dialog.stackDepth()).toBe(0);
        expect(fetchMock).not.toHaveBeenCalled();
    });
});
