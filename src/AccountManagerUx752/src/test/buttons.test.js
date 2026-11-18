/**
 * @vitest-environment jsdom
 *
 * Shared button vocabulary (components/buttons.js + main.css `.btn*`). The guard test at the bottom
 * is the regression that motivated it: `btn` / `btn-primary` / `button` were used on ~40 buttons
 * across PictureBook, webauthn, featureConfig and accessRequests with NO CSS definition anywhere, so
 * they rendered as bare text. Any `btn-*` class used in src/ must now be defined in main.css.
 */
import { describe, it, expect } from 'vitest';
import m from 'mithril';
import fs from 'fs';
import path from 'path';
import { btnClass, btn, iconBtn, BTN, BTN_PRIMARY, BTN_DANGER, BTN_ICON } from '../components/buttons.js';

const SRC = path.resolve(__dirname, '..');
const CSS = fs.readFileSync(path.join(SRC, 'styles', 'main.css'), 'utf8');

function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div.firstChild;
}

describe('btnClass', () => {
    it('builds kind and size classes', () => {
        expect(btnClass()).toBe('btn');
        expect(btnClass('primary')).toBe('btn btn-primary');
        expect(btnClass('danger', 'sm')).toBe('btn btn-danger btn-sm');
        expect(btnClass('icon', null, 'ml-2')).toBe('btn btn-icon ml-2');
        expect(BTN).toBe('btn btn-sm');
        expect(BTN_PRIMARY).toBe('btn btn-primary btn-sm');
        expect(BTN_DANGER).toBe('btn btn-danger btn-sm');
        expect(BTN_ICON).toBe('btn btn-icon');
    });
    it('rejects an unknown kind instead of silently emitting an undefined class', () => {
        expect(function () { btnClass('purple'); }).toThrow(/unknown kind/);
    });
});

describe('btn / iconBtn', () => {
    it('renders label, icon, disabled and click', () => {
        let clicked = 0;
        let el = render(btn({ label: 'Save', icon: 'save', kind: 'primary', size: 'sm', onclick: function () { clicked++; } }));
        expect(el.tagName).toBe('BUTTON');
        expect(el.className).toBe('btn btn-primary btn-sm');
        expect(el.textContent).toContain('Save');
        expect(el.querySelector('.material-symbols-outlined').textContent).toBe('save');
        expect(el.getAttribute('title')).toBe('Save');
        el.click();
        expect(clicked).toBe(1);
        let dis = render(btn({ label: 'X', disabled: true }));
        expect(dis.disabled).toBe(true);
    });
    it('iconBtn is icon-only with an accessible name', () => {
        let el = render(iconBtn('refresh', 'Refresh', function () {}, { 'data-testid': 'r' }));
        expect(el.className).toBe('btn btn-icon');
        expect(el.getAttribute('aria-label')).toBe('Refresh');
        expect(el.getAttribute('title')).toBe('Refresh');
        expect(el.getAttribute('data-testid')).toBe('r');
    });
});

describe('CSS defines the vocabulary', () => {
    it('main.css defines btn, button and every btn-* kind', () => {
        expect(CSS).toMatch(/\.btn, \.button \{/);
        expect(CSS).toMatch(/\.btn-primary, \.button\.primary \{/);
        ['btn-secondary', 'btn-danger', 'btn-icon', 'btn-sm'].forEach(function (c) {
            expect(CSS).toMatch(new RegExp('\\.' + c + ' \\{'));
        });
    });

    it('every btn-* class used in src/ is defined in main.css (no more bare-text buttons)', () => {
        let used = new Set();
        (function walk(dir) {
            fs.readdirSync(dir, { withFileTypes: true }).forEach(function (d) {
                let p = path.join(dir, d.name);
                if (d.isDirectory()) { if (d.name !== 'test') walk(p); return; }
                if (!/\.js$/.test(d.name)) return;
                let text = fs.readFileSync(p, 'utf8');
                // class strings only: quoted values containing a standalone "btn" token
                let re = /['"`]([^'"`\n]*\bbtn\b[^'"`\n]*)['"`]/g, mm;
                while ((mm = re.exec(text))) {
                    mm[1].split(/\s+/).forEach(function (tok) { if (/^btn-[a-z]+$/.test(tok)) used.add(tok); });
                }
            });
        })(SRC);
        expect(used.size).toBeGreaterThan(0);
        let missing = Array.from(used).filter(function (c) { return !new RegExp('\\.' + c + '[ ,{:]').test(CSS); });
        expect(missing).toEqual([]);
    });
});
