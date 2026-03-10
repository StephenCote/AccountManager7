import { describe, it, expect, beforeEach, vi } from 'vitest';

// --- Context Menu tests ---
import { contextMenu } from '../components/contextMenu.js';

describe('Context Menu', () => {
    it('exports show and dismiss functions', () => {
        expect(typeof contextMenu.show).toBe('function');
        expect(typeof contextMenu.dismiss).toBe('function');
    });

    it('component exports a view function', () => {
        expect(typeof contextMenu.component.view).toBe('function');
    });

    it('component renders null when not visible', () => {
        let result = contextMenu.component.view();
        expect(result).toBeNull();
    });
});

// --- FullscreenManager tests (static only — constructor needs document) ---
import { FullscreenManager } from '../components/FullscreenManager.js';

describe('FullscreenManager', () => {
    it('isSupported returns true (CSS fallback always available)', () => {
        expect(FullscreenManager.isSupported()).toBe(true);
    });

    it('exports FullscreenManager class', () => {
        expect(typeof FullscreenManager).toBe('function');
        expect(typeof FullscreenManager.isNativeSupported).toBe('function');
    });
});

// --- DnD blending tests ---
import { dnd } from '../components/dnd.js';

describe('DnD checkBlend', () => {
    beforeEach(() => {
        dnd.clear();
    });

    it('returns null for less than 2 items', () => {
        dnd.startDrag({ schema: 'data.tag', objectId: '1', name: 'tag1' });
        expect(dnd.checkBlend()).toBeNull();
    });

    it('detects chatBlend for chatConfig + promptConfig', () => {
        dnd.startDrag([
            { schema: 'olio.llm.chatConfig', objectId: '1', name: 'chat' },
            { schema: 'olio.llm.promptConfig', objectId: '2', name: 'prompt' }
        ]);
        let result = dnd.checkBlend();
        expect(result).not.toBeNull();
        expect(result.type).toBe('chatBlend');
    });

    it('detects tagBlend for tag + other types', () => {
        dnd.startDrag([
            { schema: 'data.tag', objectId: '1', name: 'tag1' },
            { schema: 'data.data', objectId: '2', name: 'item1' }
        ]);
        let result = dnd.checkBlend();
        expect(result).not.toBeNull();
        expect(result.type).toBe('tagBlend');
    });

    it('detects roleBlend for role + actors', () => {
        dnd.startDrag([
            { schema: 'auth.role', objectId: '1', name: 'role1' },
            { schema: 'identity.person', objectId: '2', name: 'person1' }
        ]);
        let result = dnd.checkBlend();
        expect(result).not.toBeNull();
        expect(result.type).toBe('roleBlend');
    });

    it('detects groupBlend for group + items', () => {
        dnd.startDrag([
            { schema: 'auth.group', objectId: '1', name: 'group1' },
            { schema: 'data.data', objectId: '2', name: 'item1' }
        ]);
        let result = dnd.checkBlend();
        expect(result).not.toBeNull();
        expect(result.type).toBe('groupBlend');
    });

    it('detects characterBlend for charPerson + charPerson', () => {
        dnd.startDrag([
            { schema: 'olio.charPerson', objectId: '1', name: 'char1' },
            { schema: 'olio.charPerson', objectId: '2', name: 'char2' }
        ]);
        let result = dnd.checkBlend();
        expect(result).not.toBeNull();
        expect(result.type).toBe('characterBlend');
    });

    it('returns null for unrecognized combinations', () => {
        dnd.startDrag([
            { schema: 'data.data', objectId: '1', name: 'item1' },
            { schema: 'data.data', objectId: '2', name: 'item2' }
        ]);
        expect(dnd.checkBlend()).toBeNull();
    });
});
