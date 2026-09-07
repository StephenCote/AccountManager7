// @vitest-environment jsdom
/**
 * ChapBook fixPageHeight PATCH-body unit tests.
 *
 * Exercises the REAL exported buildBookPatchBody from features/chapBook.js — the pure function the
 * book-level "Fixed page height" toggle uses to construct its PATCH /rest/model body. The whole point
 * of testing it is the validated-name PATCH trap (model-api.md): olio.pb.book inherits common.nameId's
 * `\S` rule on `name`, so a PATCH that omits `name` fails validation and the write SILENTLY no-ops
 * (ModelService returns HTTP 200 with body `false`). So the body MUST carry schema + id + objectId +
 * name + the changed fixPageHeight field, with name taken from the already-loaded book.
 *
 * The live PATCH round-trip and the DOM checkbox → clamped-page-wrapper behavior are a Playwright
 * concern (a fresh browser + live backend), not claimed here — this guards the body construction.
 */
import { describe, it, expect } from 'vitest';

describe('buildBookPatchBody (fixPageHeight book toggle — validated-name PATCH trap)', () => {
    it('includes schema + id + objectId + name + fixPageHeight', async () => {
        let { buildBookPatchBody } = await import('../features/chapBook.js');
        let book = { id: 42, objectId: 'abc-123', name: 'My ChapBook', slug: 'my-chapbook', fixPageHeight: false };
        let body = buildBookPatchBody(book, true);
        expect(body.schema).toBe('olio.pb.book');
        expect(body.id).toBe(42);
        expect(body.objectId).toBe('abc-123');
        // The validated name MUST be present and taken from the loaded book — omitting it makes the
        // PATCH fail validation and silently no-op.
        expect(body.name).toBe('My ChapBook');
        expect(body.fixPageHeight).toBe(true);
    });

    it('carries the changed value both ways (on and off)', async () => {
        let { buildBookPatchBody } = await import('../features/chapBook.js');
        let book = { id: 7, objectId: 'oid-7', name: 'Book' };
        expect(buildBookPatchBody(book, true).fixPageHeight).toBe(true);
        expect(buildBookPatchBody(book, false).fixPageHeight).toBe(false);
    });

    it('coerces fixPageHeight to a real boolean (never a truthy string / null)', async () => {
        let { buildBookPatchBody } = await import('../features/chapBook.js');
        let book = { id: 1, objectId: 'oid-1', name: 'Book' };
        expect(buildBookPatchBody(book, 'yes').fixPageHeight).toBe(true);
        expect(buildBookPatchBody(book, 0).fixPageHeight).toBe(false);
        expect(buildBookPatchBody(book, null).fixPageHeight).toBe(false);
        expect(buildBookPatchBody(book, undefined).fixPageHeight).toBe(false);
    });

    it('never emits an undefined name — a nameless book yields "" (which the server still rejects, but the client does not send undefined)', async () => {
        let { buildBookPatchBody } = await import('../features/chapBook.js');
        let body = buildBookPatchBody({ id: 9, objectId: 'oid-9' }, true);
        expect(body.name).toBe('');
        expect('name' in body).toBe(true);
    });
});
