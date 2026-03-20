import { describe, it, expect, beforeEach, vi } from 'vitest';

// Mock Mithril — m() must be callable as a vnode factory, plus m.redraw
function mockM(tag, attrs, children) {
    return { tag, attrs, children };
}
mockM.redraw = vi.fn();
mockM.trust = (s) => s;

vi.mock('mithril', () => ({ default: mockM }));

import { Dialog } from '../components/dialogCore.js';

beforeEach(() => {
    Dialog._reset();
});

describe('Dialog', () => {
    it('should start with empty stack', () => {
        expect(Dialog.stackDepth()).toBe(0);
    });

    it('should add to stack on open', () => {
        Dialog.open({ title: 'Test' });
        expect(Dialog.stackDepth()).toBe(1);
    });

    it('should remove from stack on close', () => {
        Dialog.open({ title: 'Test' });
        Dialog.close();
        expect(Dialog.stackDepth()).toBe(0);
    });

    it('should support stacking multiple dialogs', () => {
        Dialog.open({ title: 'First' });
        Dialog.open({ title: 'Second' });
        Dialog.open({ title: 'Third' });
        expect(Dialog.stackDepth()).toBe(3);
    });

    it('should close top dialog only (LIFO)', () => {
        Dialog.open({ title: 'First' });
        Dialog.open({ title: 'Second' });
        Dialog.close();
        expect(Dialog.stackDepth()).toBe(1);
    });

    it('should call onClose when closing', () => {
        let closed = false;
        Dialog.open({ title: 'Test', onClose: () => { closed = true; } });
        Dialog.close();
        expect(closed).toBe(true);
    });

    it('should not error when closing empty stack', () => {
        expect(() => Dialog.close()).not.toThrow();
    });

    it('should closeAll', () => {
        Dialog.open({ title: 'A' });
        Dialog.open({ title: 'B' });
        Dialog.open({ title: 'C' });
        Dialog.closeAll();
        expect(Dialog.stackDepth()).toBe(0);
    });

    it('should return empty string from loadDialogs when stack is empty', () => {
        expect(Dialog.loadDialogs()).toBe('');
    });

    it('should return vnodes from loadDialogs when dialogs open', () => {
        Dialog.open({ title: 'Test', content: 'Hello' });
        let result = Dialog.loadDialogs();
        expect(result).toBeDefined();
        expect(Array.isArray(result)).toBe(true);
        expect(result.length).toBe(1);
    });

    it('confirm should add to stack and resolve false on close', async () => {
        let p = Dialog.confirm({ title: 'Delete?', message: 'Are you sure?' });
        expect(Dialog.stackDepth()).toBe(1);
        // Close resolves with false (via onClose)
        Dialog.close();
        let result = await p;
        expect(result).toBe(false);
    });

    it('should call m.redraw on open and close', () => {
        mockM.redraw.mockClear();
        Dialog.open({ title: 'Test' });
        expect(mockM.redraw).toHaveBeenCalled();
        mockM.redraw.mockClear();
        Dialog.close();
        expect(mockM.redraw).toHaveBeenCalled();
    });
});
