/**
 * D2 drift gate — the LIVE contract test the design doc asks for (§3.2 "Fix (minimum, cheap, real)",
 * §4a D2 "Minimum acceptable"). `media` rotted out of the server-side feature list and nobody noticed,
 * because nothing ever compared the two lists against a running server.
 *
 * HOW THE FULL CONTRACT IS PROVEN, and why it is split across two suites. This file CANNOT import
 * ../src/features.js: that module does `import localManifest from './features.manifest.json'`, and
 * Playwright loads src/ files as native Node ESM, where a JSON import without
 * `with { type: 'json' }` is a hard TypeError. So the chain is:
 *
 *   link 1 (here, live)   : GET /rest/config/features/available  ===(bytes)  the Objects7 resource
 *   link 2 (here, files)  : src/features.manifest.json           ===(bytes)  the Objects7 resource
 *   link 3 (Vitest)       : client WIRING  <-> src/features.manifest.json, field-wise, BOTH directions
 *                           (src/test/featureFlags.test.js "D2 — one manifest": the id counts must be
 *                           equal, every manifest entry must have wiring, and every wiring id must
 *                           have a manifest entry or getManifestErrors() is non-empty)
 *
 * Links 1+2+3 give: served ids/deps/required/labels === the client wiring, in both directions. Each
 * link is independently asserted; none of them is assumed. In particular the "server id with NO client
 * wiring" direction — which features.js itself ignores, because mergeManifest only iterates
 * Object.keys(featureWiring) (src/features.js:125) — is covered by link 3's count + entry checks.
 *
 * This file additionally asserts the served payload's own internal consistency (every dep names a real
 * id; core is required with no deps), which no file-level test can do.
 *
 * Runs as the shared non-admin test user — GET /features/available is @RolesAllowed({"user"}).
 */
import fs from 'fs';
import path from 'path';
import { test, expect } from './helpers/fixtures.js';
import { ensureSharedTestUser, getAvailableFeatures } from './helpers/api.js';

const OBJECTS7_MANIFEST = path.resolve('../AccountManagerObjects7/src/main/resources/features/uxFeatureManifest.json');
const CLIENT_MIRROR = path.resolve('src/features.manifest.json');

test.describe('Feature manifest contract (D2)', () => {

    test.beforeAll(async ({ request }) => {
        await ensureSharedTestUser(request);
    });

    test('link 2: client mirror is byte-identical to the Objects7 manifest resource', async () => {
        expect(fs.existsSync(OBJECTS7_MANIFEST), OBJECTS7_MANIFEST + ' not found').toBe(true);
        expect(fs.existsSync(CLIENT_MIRROR), CLIENT_MIRROR + ' not found').toBe(true);
        expect(fs.readFileSync(CLIENT_MIRROR).equals(fs.readFileSync(OBJECTS7_MANIFEST)),
            'src/features.manifest.json must be a byte-for-byte copy of the Objects7 resource — copy the resource over it, never hand-edit'
        ).toBe(true);
    });

    test('link 1: the live endpoint serves the Objects7 resource verbatim', async ({ request }) => {
        let { status, body, manifest } = await getAvailableFeatures(request);
        expect(status, 'GET /rest/config/features/available must be 200 for a non-admin user').toBe(200);
        expect(manifest, 'response did not parse as JSON: ' + body).not.toBeNull();
        expect(Array.isArray(manifest), 'the manifest must be a JSON array').toBe(true);
        expect(body.trim(), 'the endpoint must serve the Objects7 manifest resource verbatim — if this '
            + 'differs, the deployed image is stale or the service is re-serializing instead of streaming the resource')
            .toBe(fs.readFileSync(OBJECTS7_MANIFEST, 'utf8').trim());
    });

    test('served ids, deps, required and labels match the client mirror exactly, both directions', async ({ request }) => {
        let { status, manifest } = await getAvailableFeatures(request);
        expect(status).toBe(200);
        let mirror = JSON.parse(fs.readFileSync(CLIENT_MIRROR, 'utf8'));

        let serverIds = manifest.map(f => f.id).sort();
        let clientIds = mirror.map(f => f.id).sort();
        expect(clientIds.filter(id => !serverIds.includes(id)),
            'client ids the server does not serve').toEqual([]);
        expect(serverIds.filter(id => !clientIds.includes(id)),
            'server ids the client does not know — features.js would silently ignore these, so an admin '
            + 'could enable a feature that does nothing').toEqual([]);
        expect(serverIds).toEqual(clientIds);

        for (let f of manifest) {
            let c = mirror.find(e => e.id === f.id);
            expect(c, 'no client entry for server id ' + f.id).toBeTruthy();
            expect(!!f.required, 'required mismatch for ' + f.id).toBe(!!c.required);
            expect((f.deps || []).slice().sort(), 'deps mismatch for ' + f.id).toEqual((c.deps || []).slice().sort());
            expect(f.label, 'label mismatch for ' + f.id).toBe(c.label);
        }
    });

    test('served payload is internally consistent, and media is present', async ({ request }) => {
        let { status, manifest } = await getAvailableFeatures(request);
        expect(status).toBe(200);
        let ids = manifest.map(f => f.id);

        expect(ids, "'media' is the id that rotted out of the old server-side list").toContain('media');
        // Drift guard on the count documented in aiDocs/UxFeatureFlagDesign.md. Also the freshness
        // check: the PRE-change service served 12 ids and no `media`, so 12 here means a stale image.
        expect(ids.length, 'expected the 13 documented features; 12 without media means a stale deployment').toBe(13);

        for (let f of manifest) {
            expect(typeof f.label, 'feature ' + f.id + ' has no label').toBe('string');
            expect(Array.isArray(f.deps), 'feature ' + f.id + ' has no deps array').toBe(true);
            for (let d of (f.deps || [])) {
                expect(ids, 'feature ' + f.id + ' declares an unknown dep ' + d).toContain(d);
            }
        }
        let core = manifest.find(f => f.id === 'core');
        expect(core, 'the manifest must declare core').toBeTruthy();
        expect(core.required, 'core must be required:true').toBe(true);
        expect(core.deps, 'core must declare no deps').toEqual([]);
    });
});
