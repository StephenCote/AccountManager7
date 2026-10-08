/**
 * ISO 42001 Approve & Sign terms (UX gap §3.6 — "validity period is static text, not sent").
 *
 * Pure client logic for iso42001Client.approve(requestId, note, terms): the wire body must carry the
 * dialog's title / validityMonths (as an INTEGER — the server rejects non-integers with 400) / notes, and
 * must OMIT each term when it is absent so the server applies its defaults (12 months, "ISO 42001 Certifier").
 *
 * Node-env safe: mithril + pageClient are mocked so importing the client pulls no DOM/network.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const requestMock = vi.fn(() => Promise.resolve({ objectId: 'cert-1' }));

vi.mock('mithril', () => ({
    default: {
        redraw: vi.fn(),
        request: (...args) => requestMock(...args),
        route: { set: vi.fn(), param: vi.fn() }
    }
}));

vi.mock('../core/pageClient.js', () => ({
    page: { user: { organizationId: 2 }, context: () => ({ roles: {} }), toast: vi.fn() }
}));

import { iso42001Client } from '../features/iso42001/iso42001Client.js';

function lastBody() {
    const call = requestMock.mock.calls[requestMock.mock.calls.length - 1];
    return call[0].body;
}

describe('iso42001Client.approve — Approve & Sign terms on the wire', () => {

    beforeEach(() => { requestMock.mockClear(); });

    it('posts to /certification/approve/{id} with the full term set', async () => {
        await iso42001Client.approve('req-1', 'Approved by Compliance Officer',
            { title: 'Compliance Officer', validityMonths: '24', notes: 'Scope: hiring prompts only.' });
        const opts = requestMock.mock.calls[0][0];
        expect(opts.method).toBe('POST');
        expect(opts.url).toMatch(/\/rest\/iso42001\/certification\/approve\/req-1$/);
        expect(lastBody()).toEqual({
            note: 'Approved by Compliance Officer',
            title: 'Compliance Officer',
            validityMonths: 24,
            notes: 'Scope: hiring prompts only.'
        });
    });

    it('sends validityMonths as an integer even when the <select> hands over a string', async () => {
        await iso42001Client.approve('req-2', 'ok', { validityMonths: '36' });
        expect(lastBody().validityMonths).toBe(36);
        expect(typeof lastBody().validityMonths).toBe('number');
    });

    it('omits absent terms so the server applies its defaults', async () => {
        await iso42001Client.approve('req-3', 'ok', { title: '', validityMonths: '', notes: '' });
        expect(lastBody()).toEqual({ note: 'ok' });
        await iso42001Client.approve('req-4', 'ok');
        expect(lastBody()).toEqual({ note: 'ok' });
    });
});
