import { describe, it, expect } from 'vitest';
import { buildPageIndexTree, PageIndexTree } from '../components/pageIndexTree.js';

describe('buildPageIndexTree', () => {
    it('reconstructs a ROOT -> SECTION -> CHUNK tree from a flat node list', () => {
        // Mirrors the server contract: GET /pageIndex/tree/{type}/{objectId} returns ALL nodes
        // flat, sorted by level then ordinal; parentId (number) matches another node's id (number).
        let flat = [
            { id: 2, objectId: 'sec-1', parentId: 1, nodeType: 'section', title: 'Section 1', summary: 'sec summary', level: 1, ordinal: 0 },
            { id: 1, objectId: 'root-1', parentId: 0, nodeType: 'root', title: 'Doc Root', summary: 'root summary', level: 0, ordinal: 0 },
            { id: 4, objectId: 'chunk-2', parentId: 2, nodeType: 'chunk', content: 'chunk two text', level: 2, ordinal: 1 },
            { id: 3, objectId: 'chunk-1', parentId: 2, nodeType: 'chunk', content: 'chunk one text', level: 2, ordinal: 0 }
        ];

        let tree = buildPageIndexTree(flat);

        expect(tree.length).toBe(1);
        let root = tree[0];
        expect(root.objectId).toBe('root-1');
        expect(root._children.length).toBe(1);

        let section = root._children[0];
        expect(section.objectId).toBe('sec-1');
        expect(section._children.length).toBe(2);

        // Sorted by ordinal within the same level.
        expect(section._children[0].objectId).toBe('chunk-1');
        expect(section._children[1].objectId).toBe('chunk-2');
    });

    it('compares nodeType case-insensitively (enums are lowercase on the wire)', () => {
        // Some list projections may return raw case as stored — root detection must not depend on exact case.
        let flat = [
            { id: 1, objectId: 'root-1', parentId: null, nodeType: 'ROOT', level: 0, ordinal: 0 },
            { id: 2, objectId: 'chunk-1', parentId: 1, nodeType: 'Chunk', level: 1, ordinal: 0 }
        ];
        let tree = buildPageIndexTree(flat);
        expect(tree.length).toBe(1);
        expect(tree[0]._children.length).toBe(1);
    });

    it('surfaces orphan nodes (parentId set but parent missing from the list) as top-level rather than dropping them', () => {
        let flat = [
            { id: 1, objectId: 'root-1', parentId: 0, nodeType: 'root', level: 0, ordinal: 0 },
            { id: 5, objectId: 'orphan-1', parentId: 999, nodeType: 'section', level: 1, ordinal: 0 }
        ];
        let tree = buildPageIndexTree(flat);
        expect(tree.length).toBe(2);
        let ids = tree.map(n => n.objectId);
        expect(ids).toContain('root-1');
        expect(ids).toContain('orphan-1');
    });

    it('sorts root-level nodes by level then ordinal', () => {
        let flat = [
            { id: 2, objectId: 'b', parentId: null, nodeType: 'root', level: 0, ordinal: 1 },
            { id: 1, objectId: 'a', parentId: null, nodeType: 'root', level: 0, ordinal: 0 }
        ];
        let tree = buildPageIndexTree(flat);
        expect(tree.map(n => n.objectId)).toEqual(['a', 'b']);
    });

    it('returns an empty array for an empty or non-array input (no PageIndex built yet)', () => {
        expect(buildPageIndexTree([])).toEqual([]);
        expect(buildPageIndexTree(null)).toEqual([]);
        expect(buildPageIndexTree(undefined)).toEqual([]);
    });

    it('does not confuse parentId (number) with objectId (uuid string)', () => {
        // A node whose objectId happens to look numeric-ish must still only be matched by id/parentId.
        let flat = [
            { id: 10, objectId: '2', parentId: 0, nodeType: 'root', level: 0, ordinal: 0 },
            { id: 20, objectId: 'chunk-a', parentId: 10, nodeType: 'chunk', level: 1, ordinal: 0 }
        ];
        let tree = buildPageIndexTree(flat);
        expect(tree.length).toBe(1);
        expect(tree[0]._children.length).toBe(1);
        expect(tree[0]._children[0].objectId).toBe('chunk-a');
    });
});

describe('PageIndexTree component', () => {
    it('is exported as a Mithril closure-component factory', () => {
        expect(typeof PageIndexTree).toBe('function');
        let instance = PageIndexTree();
        expect(typeof instance.view).toBe('function');
        expect(typeof instance.oninit).toBe('function');
    });
});
