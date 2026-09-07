/**
 * ChapBook E2E shared helpers — used by the ChapBook Review Redesign specs
 * (chapBookMerge / chapBookFontColor / chapBookSave / chapBookSdConfig / chapBookReader /
 *  pictureBookReaderParity / chapBookRenderLive).
 *
 * Run against the Docker test stack (nginx serves Ux + /AccountManagerService7 on one origin):
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBook<Name>.spec.js \
 *       --project=chromium --workers=1
 *
 * Never uses the admin user — everything runs as ensureSharedTestUser()'s e2etest_shared.
 */
import { expect } from '@playwright/test';
import { ensureSharedTestUser } from './api.js';

export const REST = '/AccountManagerService7/rest';
export const CB_REST = REST + '/olio/chap-book';
export const PB_REST = REST + '/olio/picture-book';

// Real poems by Stephen W. Cote — stanza text only (identical to chapBook.spec.js so the
// same seeded olio.cb.poem rows are reused). Each has 2 blank-line-separated stanzas; with a
// small maxLinesPerPage the create path chunks them into several scenes.
export const POEM_1 = `Memory, do not fail me;
A majestic oak's leaves
Tumbling and falling.
A precarious branch
Mourning its creased skein's blanch
Weeps spirals of methodical floating
Falling through a brisk wind pirouette
Upon an earthen collet,
The crumpled remains are fleeting.

Memory, do not forget me;
You, sir, have betrayed me.
Languishing in rivulets
Of pollen speckled rain,
Spattering the falling
Offspring moments of magnificence,
Are mere minutes of memories failing
To recall your pitch black heart.
Revel in those falling leaves.`;

export const POEM_2 = `Outside, all is pristine,
From cobalt skies of charcoal unity
Descending upon snow canvassed green
To silver veins of icy sheens,
Born of spells and sorcery.

Inside hearts and hearths and homes,
Ochre embers and ebon cinders,
Faded life stirred by motherly crones,
Dry damp clothes and warm cold bones
And illuminate the age-old spellbound tomes.`;

// ── REST auth (request-fixture context) ───────────────────────────────────────

export async function restLoginShared(request) {
    const resp = await request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    expect(resp.ok() || resp.status() === 204, 'REST login failed: ' + resp.status()).toBe(true);
}

export async function restLogout(request) {
    await request.get(REST + '/logout');
}

// ── Browser login (page context) — WS stub + hash SPA ready ────────────────────
// Canonical pattern (chapBook.spec.js): Docker's nginx strips the session cookie on the WS
// upgrade so Tomcat closes it → pageClient.reconnect() → forceLogin() → #!/sig. Stub WebSocket
// BEFORE goto so it fires onopen and never onclose.
export async function loginAsSharedUser(page) {
    const resp = await page.request.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: '/Development',
            name: 'e2etest_shared',
            credential: Buffer.from('password').toString('base64'),
            type: 'hashed_password'
        }
    });
    if (!resp.ok() && resp.status() !== 204) {
        throw new Error('API login failed: HTTP ' + resp.status());
    }
    await page.addInitScript(() => {
        window.WebSocket = class StubWS {
            constructor(url) {
                this.url = url;
                this.readyState = 0;
                this.onopen = null; this.onclose = null;
                this.onmessage = null; this.onerror = null;
                this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
                setTimeout(() => {
                    this.readyState = 1;
                    if (this.onopen) this.onopen({ type: 'open', target: this });
                }, 50);
            }
            send() {}
            close() { this.readyState = 3; }
            addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
        };
        window.WebSocket.CONNECTING = 0;
        window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2;
        window.WebSocket.CLOSED = 3;
    });
    await page.goto('/', { timeout: 30000 });
    await page.waitForFunction(
        () => window.location.hash.includes('/main') && document.querySelector('[role="main"]'),
        { timeout: 30000 }
    );
}

// ── Seeding + book building (request-fixture context) ──────────────────────────

export async function ensurePoemsGroup(request) {
    const dir = await request.get(REST + '/path/make/auth.group/data/B64-'
        + Buffer.from('~/Poems').toString('base64').replace(/=/g, '%3D'));
    const body = await dir.json();
    expect(body && body.id, 'could not ensure ~/Poems group').toBeTruthy();
    return { groupId: body.id, organizationId: body.organizationId };
}

// Idempotent: return an existing olio.cb.poem objectId by name, else create it.
export async function ensurePoem(request, groupId, orgId, name, title, text) {
    const s = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.cb.poem',
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: ['id', 'objectId', 'name'], recordCount: 1, cache: false
        }
    });
    const b = await s.json().catch(() => null);
    if (b && b.results && b.results.length) return b.results[0].objectId;
    const r = await request.post(REST + '/model', {
        data: { schema: 'olio.cb.poem', name, title, author: 'E2E', groupId, text }
    });
    expect(r.ok(), 'create poem failed: ' + r.status() + ' ' + await r.text()).toBe(true);
    const c = await r.json();
    expect(c && c.objectId, 'poem create returned no objectId').toBeTruthy();
    return c.objectId;
}

export async function createChapBook(request, slug, title, poemObjectIds, maxLinesPerPage) {
    const r = await request.post(CB_REST + '/create', {
        data: { slug, title, poemObjectIds, maxLinesPerPage }
    });
    expect(r.ok(), 'create ChapBook failed: ' + r.status() + ' ' + await r.text()).toBe(true);
    const c = await r.json();
    const oid = c && (c.bookObjectId || c.objectId);
    expect(oid, 'no bookObjectId in create response').toBeTruthy();
    return oid;
}

// Ordered scene pages for a book (the exact endpoint the reader + review views load from).
export async function bookPages(request, bookOid) {
    const r = await request.get(PB_REST + '/' + bookOid + '/pages');
    expect(r.ok(), 'pages fetch failed: ' + r.status()).toBe(true);
    return r.json();
}

// Authoritative single-scene read by objectId. olio.pb.scene is group-scoped so a by-objectId read
// gets the group-only access shortcut and does not require an organizationId condition (this mirrors
// the app's own loadSceneFields). cache:false so post-edit reads are fresh.
export async function sceneByObjectId(request, objectId) {
    const r = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.scene', cache: false,
            request: ['id', 'objectId', 'groupId', 'name', 'title', 'sceneIndex', 'poemStanza',
                'imageStale', 'sdPrompt', 'promptLocked', 'configOverride', 'pageTextColor', 'pageFont',
                'pageBgColor', 'pageTextAlign'],
            fields: [{ name: 'objectId', comparator: 'EQUALS', value: objectId }],
            recordCount: 1
        }
    });
    expect(r.ok(), 'scene fetch failed: ' + r.status()).toBe(true);
    const b = await r.json();
    if (Array.isArray(b)) return b[0] || null;
    if (b && b.results) return b.results[0] || null;
    return null;
}

// Read one olio.cb.poem by objectId, projecting the LLM-extracted theme fields (theme/mood/keywords)
// which are NOT in the model's query defaults — used to prove analyzePoemTheme actually ran the LLM
// (the endpoint returns a bare {success:true} and persists the analysis onto the poem).
export async function poemByObjectId(request, objectId) {
    const r = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.cb.poem', cache: false,
            request: ['id', 'objectId', 'name', 'theme', 'mood', 'keywords'],
            fields: [{ name: 'objectId', comparator: 'EQUALS', value: objectId }],
            recordCount: 1
        }
    });
    expect(r.ok(), 'poem fetch failed: ' + r.status()).toBe(true);
    const b = await r.json();
    if (Array.isArray(b)) return b[0] || null;
    if (b && b.results) return b.results[0] || null;
    return null;
}

// All scenes in a group, ascending by sceneIndex. A data.directory-derived list query needs an
// explicit organizationId (numeric) or PBAC denies. groupId/organizationId are longs → numbers.
export async function scenesByGroup(request, groupId, orgId) {
    const r = await request.post(REST + '/model/search', {
        data: {
            schema: 'io.query', type: 'olio.pb.scene', cache: false,
            request: ['id', 'objectId', 'groupId', 'name', 'sceneIndex', 'poemStanza',
                'imageStale', 'pageTextColor', 'pageFont', 'pageBgColor', 'pageTextAlign'],
            fields: [
                { name: 'groupId', comparator: 'EQUALS', value: Number(groupId) },
                { name: 'organizationId', comparator: 'EQUALS', value: Number(orgId) }
            ],
            recordCount: 500
        }
    });
    expect(r.ok(), 'scenes-by-group fetch failed: ' + r.status()).toBe(true);
    const b = await r.json();
    const rows = Array.isArray(b) ? b : (b && b.results) ? b.results : [];
    rows.sort((a, c) => (a.sceneIndex || 0) - (c.sceneIndex || 0));
    return rows;
}

// PATCH per-scene style fields directly via REST — mirrors the app's patchScene shape
// (PATCH /rest/model with schema + objectId + changed fields) but ALSO carries the validated `name`
// (common.nameId's \S rule rejects a patch that omits it). Used by the reader specs to set font/align
// on a scene without re-driving the whole review Save UI. Returns after asserting the PATCH succeeded.
export async function patchSceneStyle(request, objectId, changed) {
    const scene = await sceneByObjectId(request, objectId);
    expect(scene && scene.name, 'scene has a name to satisfy validation on patch').toBeTruthy();
    const r = await request.patch(REST + '/model', {
        data: Object.assign({ schema: 'olio.pb.scene', objectId, name: scene.name }, changed)
    });
    expect(r.ok(), 'scene style patch failed: ' + r.status() + ' ' + await r.text()).toBe(true);
}

// #rrggbb → "rgb(r, g, b)" as getComputedStyle reports it (used by the font-color assertions).
export function hexToRgb(hex) {
    const h = hex.replace('#', '');
    const r = parseInt(h.substring(0, 2), 16);
    const g = parseInt(h.substring(2, 4), 16);
    const b = parseInt(h.substring(4, 6), 16);
    return `rgb(${r}, ${g}, ${b})`;
}

export { ensureSharedTestUser };
