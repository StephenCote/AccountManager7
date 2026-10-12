// @vitest-environment jsdom
/**
 * PictureBook workflow canvas (P1-10 S6) — stored geometry round trip, status colours, thumb pick.
 *
 * Wire facts (S0): nodeSummary canvasX/Y/W/H are INT columns with DDL default 0, read without
 * wasNull, so "never placed" is 0 on the wire (never null); a stored position is honoured only when
 * W and H are both positive. PUT /node/{id}/canvas writes ALL FOUR columns from the body (an absent
 * key arrives null and clears the column), so the client must send w/h with every drop or the node
 * reads back as unplaced on the next load.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { page } from '../core/pageClient.js';
import { saveCanvas } from '../workflows/pictureBookWorkflow.js';

let cv;
let origFetch;

beforeEach(async () => {
    origFetch = global.fetch;
    cv = await import('../features/pictureBookWorkflow.js');
    vi.spyOn(page, 'toast').mockImplementation(() => {});
});
afterEach(() => {
    cv.__setCanvasStateForTest({ graphData: null, positions: {}, draggingNodeId: null, pb2BookObjectId: null });
    global.fetch = origFetch;
    vi.restoreAllMocks();
});

const G = () => cv.CANVAS_GEOMETRY;

describe('storedPosition — all-zero / non-positive W,H = never placed', () => {
    it('honours a stored position only when W and H are positive', () => {
        expect(cv.storedPosition({ canvasX: 0, canvasY: 0, canvasW: 0, canvasH: 0 })).toBeNull();
        expect(cv.storedPosition({ canvasX: 120, canvasY: 80, canvasW: 0, canvasH: 0 })).toBeNull();
        expect(cv.storedPosition({ canvasX: 120, canvasY: 80, canvasW: 200, canvasH: 0 })).toBeNull();
        expect(cv.storedPosition({ canvasX: 120, canvasY: 80, canvasW: -1, canvasH: 110 })).toBeNull();
        expect(cv.storedPosition({ canvasX: 120, canvasY: 80, canvasW: 200, canvasH: 110 })).toEqual({ x: 120, y: 80, w: 200, h: 110 });
        // x/y of 0 are a legitimate placed position when W/H are positive
        expect(cv.storedPosition({ canvasX: 0, canvasY: 0, canvasW: 200, canvasH: 110 })).toEqual({ x: 0, y: 0, w: 200, h: 110 });
        // projected without the columns → null, not placed
        expect(cv.storedPosition({ objectId: 'n' })).toBeNull();
        expect(cv.storedPosition({ canvasX: null, canvasY: null, canvasW: null, canvasH: null })).toBeNull();
        expect(cv.storedPosition(null)).toBeNull();
    });
});

describe('computePositions — grid for unplaced nodes, stored geometry for placed ones', () => {
    const NODES = [
        { objectId: 'a', sceneIndex: 1, nodeType: 'scene', canvasX: 0, canvasY: 0, canvasW: 0, canvasH: 0 },
        { objectId: 'b', sceneIndex: 1, nodeType: 'portrait', canvasX: 0, canvasY: 0, canvasW: 0, canvasH: 0 },
        { objectId: 'c', sceneIndex: 2, nodeType: 'scene', canvasX: 900, canvasY: 33, canvasW: 200, canvasH: 110 },
        { objectId: 'd', sceneIndex: 2, nodeType: 'landscape', canvasX: 5, canvasY: 5, canvasW: 0, canvasH: 0 }
    ];
    it('lays out unplaced nodes by (sceneIndex column, type-rank row) exactly as before', () => {
        let g = G();
        let pos = cv.computePositions(NODES);
        expect(pos.b).toEqual({ x: g.CANVAS_PAD, y: g.CANVAS_PAD });                       // col 0, portrait rank 0
        expect(pos.a).toEqual({ x: g.CANVAS_PAD, y: g.CANVAS_PAD + g.ROW_GAP });           // col 0, scene rank 3
        expect(pos.d).toEqual({ x: g.CANVAS_PAD + g.COL_GAP, y: g.CANVAS_PAD });           // col 1, landscape rank 1 (x/y 5 with W/H 0 = unplaced)
    });
    it('keeps a placed node where the user left it', () => {
        let pos = cv.computePositions(NODES);
        expect(pos.c).toEqual({ x: 900, y: 33, w: 200, h: 110 });
    });
    it('empty / missing input → {}', () => {
        expect(cv.computePositions([])).toEqual({});
        expect(cv.computePositions(null)).toEqual({});
    });
});

describe('drag-drop → PUT canvas {x,y,w,h} → reload honours it (round trip)', () => {
    it('sends the rounded position WITH the card size, then the saved geometry survives computePositions', async () => {
        let g = G();
        let body = null;
        global.fetch = vi.fn(async (u, init) => {
            body = JSON.parse(init.body);
            return { ok: true, status: 200, json: async () => ({ nodeObjectId: 'n1', canvasX: body.x, canvasY: body.y, canvasW: body.w, canvasH: body.h }) };
        });
        let node = { objectId: 'n1', sceneIndex: 1, nodeType: 'scene', canvasX: 0, canvasY: 0, canvasW: 0, canvasH: 0 };
        cv.__setCanvasStateForTest({ pb2BookObjectId: 'B1', graphData: { nodes: [node] }, draggingNodeId: 'n1', positions: { n1: { x: 412.6, y: 77.2 } } });
        cv.onCanvasMouseUp();
        await new Promise(r => setTimeout(r, 0));
        expect(global.fetch).toHaveBeenCalledTimes(1);
        expect(String(global.fetch.mock.calls[0][0])).toMatch(/\/rest\/olio\/picture-book\/B1\/node\/n1\/canvas$/);
        expect(global.fetch.mock.calls[0][1].method).toBe('PUT');
        expect(body).toEqual({ x: 413, y: 77, w: g.CARD_W, h: g.CARD_H });
        // the in-memory node now carries the stored geometry ...
        expect(node).toMatchObject({ canvasX: 413, canvasY: 77, canvasW: g.CARD_W, canvasH: g.CARD_H });
        // ... and a fresh layout (what loadGraph does after the server echoes it back) keeps it
        expect(cv.computePositions([node]).n1).toEqual({ x: 413, y: 77, w: g.CARD_W, h: g.CARD_H });
        expect(cv.__canvasStateForTest().draggingNodeId).toBeNull();
        expect(page.toast).not.toHaveBeenCalled();
    });
    it('saveCanvas omits keys it is not given (server clears absent columns — so the canvas never omits w/h)', async () => {
        let body = null;
        global.fetch = vi.fn(async (u, init) => { body = JSON.parse(init.body); return { ok: true, status: 200, json: async () => ({}) }; });
        await saveCanvas('B1', 'n1', { x: 1.4, y: 2.6 });
        expect(body).toEqual({ x: 1, y: 3 });
        await saveCanvas('B1', 'n1', { x: 1, y: 2, w: 200, h: 110 });
        expect(body).toEqual({ x: 1, y: 2, w: 200, h: 110 });
    });
    it('a failed save toasts and leaves the dragged position on screen', async () => {
        global.fetch = vi.fn(async () => ({ ok: false, status: 403, text: async () => '{"error":"denied"}', json: async () => ({ error: 'denied' }) }));
        cv.__setCanvasStateForTest({ pb2BookObjectId: 'B1', graphData: { nodes: [{ objectId: 'n1' }] }, draggingNodeId: 'n1', positions: { n1: { x: 10, y: 20 } } });
        cv.onCanvasMouseUp();
        await new Promise(r => setTimeout(r, 0));
        expect(page.toast).toHaveBeenCalledWith('error', 'Save position failed: saveCanvas failed: 403');
        expect(cv.__canvasStateForTest().positions.n1).toEqual({ x: 10, y: 20 });
    });
});

describe('statusColor — RUNNING added, CANCELLED deliberately not', () => {
    it('maps the V3 node statuses, case-insensitively, unknown → UNKNOWN colour', () => {
        expect(cv.STATUS_COLOR.RUNNING).toBeTruthy();
        expect(cv.STATUS_COLOR.DONE_UNVERIFIED).toBeTruthy();
        expect(cv.STATUS_COLOR.CANCELLED).toBeUndefined();
        expect(cv.statusColor('running')).toBe(cv.STATUS_COLOR.RUNNING);
        expect(cv.statusColor('RUNNING')).not.toBe(cv.STATUS_COLOR.READY);
        expect(cv.statusColor('CANCELLED')).toBe(cv.STATUS_COLOR.UNKNOWN);
        expect(cv.statusColor(null)).toBe(cv.STATUS_COLOR.UNKNOWN);
    });
});

describe('pickThumbArtifact — selected, else newest (not list[0])', () => {
    it('prefers the selected artifact, then the highest revision, then the last element', () => {
        let a1 = { objectId: 'a1', revision: 1 }, a2 = { objectId: 'a2', revision: 2 }, a3 = { objectId: 'a3', revision: 3 };
        expect(cv.pickThumbArtifact([a1, a2, a3])).toBe(a3);
        expect(cv.pickThumbArtifact([a3, a1, a2])).toBe(a3);                 // order-independent when revisions are present
        expect(cv.pickThumbArtifact([a1, { objectId: 'a2', revision: 2, selected: true }, a3]).objectId).toBe('a2');
        expect(cv.pickThumbArtifact([{ objectId: 'x' }, { objectId: 'y' }]).objectId).toBe('y'); // no revisions → last
        expect(cv.pickThumbArtifact([])).toBeNull();
        expect(cv.pickThumbArtifact(null)).toBeNull();
    });
});
