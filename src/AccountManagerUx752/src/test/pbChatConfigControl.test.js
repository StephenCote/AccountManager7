/**
 * @vitest-environment jsdom
 *
 * components/pbChatConfig.renderChatConfigControl — the ONE chat-config picker for PictureBook and
 * ChapBook (it replaced the wizard's private copy and ChapBook's four hand-rolled ObjectPicker boxes).
 * Rendered with the real Mithril renderer; ObjectPicker.openLibrary is spied so a click is observable.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import m from 'mithril';
import fs from 'fs';
import path from 'path';
import { ObjectPicker } from '../components/picker.js';
import { renderChatConfigControl, chatConfigLabel } from '../components/pbChatConfig.js';

function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div.firstChild;
}

afterEach(function () { vi.restoreAllMocks(); });

describe('renderChatConfigControl', () => {
    it('inline: label + box; click opens the chatConfig library and onPick gets {name, objectId}', () => {
        let spy = vi.spyOn(ObjectPicker, 'openLibrary').mockImplementation(function (o) { o.onSelect({ name: 'cfgA', objectId: 'oid-a' }); });
        let picked = null;
        let el = render(renderChatConfigControl({ testid: 'cc', ref: null, onPick: function (r) { picked = r; } }));
        expect(el.querySelector('label').textContent).toBe('Chat Config');
        let box = el.querySelector('[data-testid="cc"]');
        expect(box.textContent).toContain('(click to select)');
        expect(box.getAttribute('role')).toBe('button');
        box.click();
        expect(spy).toHaveBeenCalledTimes(1);
        expect(spy.mock.calls[0][0].libraryType).toBe('chatConfig');
        expect(picked).toEqual({ name: 'cfgA', objectId: 'oid-a' });
    });

    it('stacked keeps the "label + div" shape the e2e locators use, with a custom label/placeholder', () => {
        let el = render(renderChatConfigControl({ stacked: true, label: 'Default chat config', placeholder: '(default — click to select)', ref: null, onPick: function () {} }));
        expect(el.children[0].tagName).toBe('LABEL');
        expect(el.children[0].textContent).toBe('Default chat config');
        expect(el.children[1].tagName).toBe('DIV');
        expect(el.children[1].className).toContain('cursor-pointer');
        expect(el.children[1].className).toContain('w-full');
        expect(el.children[1].textContent).toContain('(default — click to select)');
    });

    it('label:null renders the box only; shows the picked name and the resolving text', () => {
        let el = render(renderChatConfigControl({ label: null, ref: { name: 'picked' }, onPick: function () {}, attrs: { 'data-pb-retry-chatconfig': '1' } }));
        expect(el.querySelector('label')).toBeNull();
        let box = el.querySelector('[data-pb-retry-chatconfig]');
        expect(box).not.toBeNull();
        expect(box.textContent).toContain('picked');
        expect(chatConfigLabel(null, true)).toBe('Resolving default…');
        expect(chatConfigLabel(null, false, 'Config')).toBe('Config');
    });

    it('disabled does not open the picker (click or Enter)', () => {
        let spy = vi.spyOn(ObjectPicker, 'openLibrary').mockImplementation(function () {});
        let el = render(renderChatConfigControl({ testid: 'cc', ref: null, disabled: true, onPick: function () {} }));
        let box = el.querySelector('[data-testid="cc"]');
        box.click();
        box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
        expect(spy).not.toHaveBeenCalled();
        expect(box.getAttribute('aria-disabled')).toBe('true');
    });
});

describe('one picker: no PB / ChapBook file opens the chatConfig library itself', () => {
    it('only components/pbChatConfig.js calls openLibrary with libraryType chatConfig', () => {
        let SRC = path.resolve(__dirname, '..');
        let files = ['workflows/pictureBook.js', 'features/chapBook.js', 'features/pictureBook.js', 'features/pictureBookCompose.js',
            'features/pictureBookLocations.js', 'features/pictureBookStyle.js', 'features/pictureBookReview.js', 'features/pictureBookWorkflow.js'];
        let offenders = files.filter(function (f) {
            return /libraryType:\s*['"]chatConfig/.test(fs.readFileSync(path.join(SRC, f), 'utf8'));
        });
        expect(offenders).toEqual([]);
    });
});
