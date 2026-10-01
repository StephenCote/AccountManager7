import { describe, it, expect, vi, afterEach } from 'vitest';
import { classifyWorkflowNotFound, workflowView } from '../workflows/pictureBookWorkflow.js';

// GET /{id}/workflow returns two DIFFERENT 404s (PbServiceFacade.requireBook / requireWorkflow) that
// the canvas must render differently: "Book not found" (not a PB2 book → PB1 bridge) versus "no
// workflow yet" (a PB2 book whose graph is created at first render → scene list + "Open in wizard to
// render"). Bodies
// captured live from the Docker stack on 2026-09-30.

const NO_WORKFLOW_BODY = '{"error":"This book has no workflow yet - generate a scene first"}';
const NOT_FOUND_BODY = '{"error":"Book not found"}';

function mock404(bodyText) {
    global.fetch = vi.fn(async () => ({
        ok: false, status: 404,
        text: async () => bodyText,
        json: async () => JSON.parse(bodyText),
    }));
}

afterEach(() => { vi.restoreAllMocks(); });

describe('classifyWorkflowNotFound', () => {
    it('recognises the "no workflow yet" facade message from a JSON string body', () => {
        expect(classifyWorkflowNotFound(NO_WORKFLOW_BODY)).toBe('no-workflow');
    });
    it('recognises it from an already-parsed object body', () => {
        expect(classifyWorkflowNotFound({ error: 'This book has no workflow yet - generate a scene first' })).toBe('no-workflow');
        expect(classifyWorkflowNotFound({ message: 'No workflow for this book' })).toBe('no-workflow');
    });
    it('classifies "Book not found" as not-found', () => {
        expect(classifyWorkflowNotFound(NOT_FOUND_BODY)).toBe('not-found');
        expect(classifyWorkflowNotFound({ error: 'Book not found' })).toBe('not-found');
    });
    it('falls back to not-found for empty, null, non-JSON or unknown bodies (pre-existing behaviour)', () => {
        expect(classifyWorkflowNotFound('')).toBe('not-found');
        expect(classifyWorkflowNotFound(null)).toBe('not-found');
        expect(classifyWorkflowNotFound(undefined)).toBe('not-found');
        expect(classifyWorkflowNotFound('<html>Not Found</html>')).toBe('not-found');
        expect(classifyWorkflowNotFound('{"error":"Something else"}')).toBe('not-found');
    });
    it('is case-insensitive on the message text', () => {
        expect(classifyWorkflowNotFound('{"error":"NO WORKFLOW yet"}')).toBe('no-workflow');
    });
});

describe('workflowView 404 handling', () => {
    it('returns null for "Book not found" so the canvas falls back to the PB1 bridge', async () => {
        mock404(NOT_FOUND_BODY);
        expect(await workflowView('some-pb1-group-oid')).toBeNull();
    });
    it('returns {noWorkflow:true, bookObjectId} for a PB2 book with no graph yet', async () => {
        mock404(NO_WORKFLOW_BODY);
        let r = await workflowView('pb2-oid');
        expect(r).toEqual({ noWorkflow: true, bookObjectId: 'pb2-oid' });
    });
    it('still throws on non-404 failures', async () => {
        global.fetch = vi.fn(async () => ({ ok: false, status: 500, text: async () => 'boom' }));
        await expect(workflowView('x')).rejects.toThrow('workflowView failed: 500');
    });
    it('returns the parsed graph on 200', async () => {
        let graph = { bookObjectId: 'x', nodes: [{ objectId: 'n1' }], nodeCount: 0 };
        global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => graph }));
        expect(await workflowView('x')).toEqual(graph);
    });
});
