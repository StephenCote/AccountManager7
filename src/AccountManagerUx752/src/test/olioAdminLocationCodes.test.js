// @vitest-environment jsdom
/**
 * Olio Admin "Countries" input: the ISO-code text is parsed client-side into the `features` array
 * sent to POST /rest/olio/loadData. Blank means "every staged country" (no `features` key at all).
 */
import { describe, it, expect } from 'vitest';

describe('parseLocationCodes', () => {
    it('blank input → [] (server loads every staged country)', async () => {
        let { parseLocationCodes } = await import('../features/olioAdmin/olioAdminView.js');
        expect(parseLocationCodes('')).toEqual([]);
        expect(parseLocationCodes('   ')).toEqual([]);
        expect(parseLocationCodes(null)).toEqual([]);
        expect(parseLocationCodes(undefined)).toEqual([]);
    });

    it('splits on commas, semicolons and whitespace and uppercases', async () => {
        let { parseLocationCodes } = await import('../features/olioAdmin/olioAdminView.js');
        expect(parseLocationCodes('as, ie;gb\nmx  ca')).toEqual(['AS', 'IE', 'GB', 'MX', 'CA']);
        expect(parseLocationCodes(',, AS ,')).toEqual(['AS']);
    });

    it('keeps invalid tokens so the view can name them in its error', async () => {
        let { parseLocationCodes } = await import('../features/olioAdmin/olioAdminView.js');
        expect(parseLocationCodes('bad1, ie')).toEqual(['BAD1', 'IE']);
    });
});

describe('olioAdminClient.loadData body', () => {
    it('omits features when blank and sends the code list otherwise', async () => {
        let m = (await import('mithril')).default;
        let seen = [];
        let orig = m.request;
        m.request = (opts) => { seen.push(opts); return Promise.resolve({}); };
        try {
            let { olioAdminClient } = await import('../features/olioAdmin/olioAdminClient.js');
            await olioAdminClient.loadData(true, []);
            await olioAdminClient.loadData(true, ['AS', 'IE']);
            await olioAdminClient.loadData(false, ['AS']);
        } finally {
            m.request = orig;
        }
        expect(seen.length).toBe(3);
        expect(seen[0].method).toBe('POST');
        expect(seen[0].url).toMatch(/\/rest\/olio\/loadData$/);
        expect(seen[0].body).toEqual({ includeLocations: true });
        expect(seen[1].body).toEqual({ includeLocations: true, features: ['AS', 'IE'] });
        expect(seen[2].body).toEqual({ includeLocations: false, features: ['AS'] });
    });
});
