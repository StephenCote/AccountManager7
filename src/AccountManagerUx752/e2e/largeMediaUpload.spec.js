/**
 * Large media upload through /mediaForm (the path dnd.uploadFiles uses for data.data).
 *
 * Regression for 2026-11-25: a 114 MB dashcam MP4 failed with a client-side error and nothing in the
 * Tomcat log, because nginx's client_max_body_size (100m) answered 413 itself
 * ("client intended to send too large body" in /var/log/nginx/error.log). Raised to 2g in
 * src/docker/nginx.conf.
 *
 * Opt-in: the file is not a committed fixture (too large). Point AM7_LARGE_UPLOAD_FILE at any file
 * over 100 MiB with a known extension and run against the Docker stack:
 *   PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 AM7_LARGE_UPLOAD_FILE=C:\path\video.mp4 \
 *     npx playwright test e2e/largeMediaUpload.spec.js --workers=1 --project=chromium
 *
 * Runs as the shared test user, never admin. Deletes the record it created.
 */
import { test, expect, request as pwRequest } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { ensureSharedTestUser } from './helpers/api.js';

const FILE = process.env.AM7_LARGE_UPLOAD_FILE;
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
const APP = BASE_URL + '/AccountManagerService7';
const REST = APP + '/rest';
const ORG = '/Development';
const OLD_NGINX_LIMIT = 100 * 1024 * 1024;

const b64 = (s) => Buffer.from(s).toString('base64');

test.describe('large media upload via /mediaForm', () => {
    test.skip(!FILE, 'set AM7_LARGE_UPLOAD_FILE to a >100 MiB file to run');

    test('uploads a file larger than the old 100m nginx limit and persists it', async ({ request }) => {
        test.setTimeout(600000);
        const size = fs.statSync(FILE).size;
        expect(size, 'file must exceed the old nginx limit to be a meaningful regression').toBeGreaterThan(OLD_NGINX_LIMIT);

        const info = await ensureSharedTestUser(request);
        const ctx = await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
        let created = null;
        try {
            const login = await ctx.post(REST + '/login', {
                data: { schema: 'auth.credential', organizationPath: ORG, name: info.testUserName,
                        credential: b64(info.testPassword), type: 'hashed_password' }
            });
            expect(login.ok(), 'login as shared test user').toBeTruthy();

            const dirResp = await ctx.get(REST + '/path/make/auth.group/data/B64-' + b64('~/Data').replace(/=/g, '%3D'));
            expect(dirResp.ok()).toBeTruthy();
            const dir = await dirResp.json();

            const name = 'largeUpload-' + Date.now().toString(36) + path.extname(FILE).toLowerCase();
            const started = Date.now();
            const up = await ctx.post(APP + '/mediaForm', {
                timeout: 600000,
                multipart: {
                    organizationPath: ORG,
                    groupId: String(dir.id),
                    groupPath: dir.groupPath || '~/Data',
                    name,
                    dataFile: { name, mimeType: 'application/octet-stream', buffer: fs.readFileSync(FILE) }
                }
            });
            console.log(`[upload] ${size} bytes -> HTTP ${up.status()} in ${Date.now() - started} ms`);
            expect(up.status(), 'nginx must not 413 and the servlet must answer').toBe(200);
            // MediaFormServlet always answers 200; success is in the body script's status flag.
            expect(await up.text()).toContain('status:true');

            const search = await ctx.post(REST + '/model/search', {
                data: { schema: 'io.query', type: 'data.data', cache: false, recordCount: 1,
                        request: ['id', 'objectId', 'name', 'contentType', 'groupId'],
                        fields: [{ name: 'groupId', comparator: 'equals', value: dir.id },
                                 { name: 'name', comparator: 'equals', value: name }] }
            });
            expect(search.ok()).toBeTruthy();
            const res = await search.json();
            created = res && res.results && res.results[0];
            console.log('[upload] persisted record:', JSON.stringify(created));
            expect(created && created.objectId, 'uploaded data.data must exist').toBeTruthy();
            expect(created.name).toBe(name);
        } finally {
            if (created && created.objectId) {
                const del = await ctx.delete(REST + '/model/data.data/' + created.objectId);
                console.log('[upload] cleanup delete HTTP ' + del.status());
            }
            await ctx.dispose();
        }
    });
});
