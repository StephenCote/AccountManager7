/**
 * API helpers for E2E test data setup/teardown.
 *
 * Each setup/teardown function creates its own APIRequestContext (own cookie jar)
 * so that parallel workers don't share and clobber each other's server sessions.
 */
import { request as pwRequest } from '@playwright/test';

const BASE_URL = 'https://localhost:8899';
const REST = BASE_URL + '/AccountManagerService7/rest';

function b64(str) {
    return Buffer.from(str).toString('base64');
}

/**
 * Create an isolated API request context (own cookie jar = own server session).
 */
async function newApiContext() {
    return await pwRequest.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}

/**
 * Parse a response as JSON, returning null if it fails or the status is not OK.
 */
async function safeJson(resp) {
    if (!resp.ok()) return null;
    try {
        let text = await resp.text();
        if (!text || text.startsWith('<!') || text.startsWith('<html')) return null;
        return JSON.parse(text);
    } catch {
        return null;
    }
}

// ── Internal helpers that take an explicit context ──────────────────────

async function loginCtx(ctx, opts = {}) {
    const org = opts.org || '/Development';
    const user = opts.user || 'admin';
    const password = opts.password || 'password';

    return await ctx.post(REST + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: org,
            name: user,
            credential: b64(password),
            type: 'hashed_password'
        }
    });
}

async function logoutCtx(ctx) {
    await ctx.get(REST + '/logout');
}

async function searchCtx(ctx, type, fieldName, fieldValue, fields) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: type,
            fields: [{ name: fieldName, comparator: 'equals', value: fieldValue }],
            request: fields || ['id', 'objectId', 'name'],
            recordCount: 1
        }
    });
    let result = await safeJson(resp);
    return (result && result.results && result.results.length > 0) ? result.results[0] : null;
}

function encodePath(dirPath) {
    if (dirPath.startsWith('/') || dirPath.startsWith('~') || dirPath.includes('.')) {
        return 'B64-' + b64(dirPath).replace(/=/g, '%3D');
    }
    return dirPath;
}

async function ensurePathCtx(ctx, type, subType, dirPath) {
    let resp = await ctx.get(REST + '/path/make/' + type + '/' + subType + '/' + encodePath(dirPath));
    return await safeJson(resp);
}

async function findPathCtx(ctx, type, subType, dirPath) {
    let resp = await ctx.get(REST + '/path/find/' + type + '/' + subType + '/' + encodePath(dirPath));
    return await safeJson(resp);
}

async function createUserCtx(ctx, name) {
    await ctx.post(REST + '/model', {
        data: { schema: 'system.user', name: name }
    });
    return await searchCtx(ctx, 'system.user', 'name', name);
}

async function setCredentialCtx(ctx, userObjectId, password) {
    let resp = await ctx.post(REST + '/credential/system.user/' + userObjectId, {
        data: {
            schema: 'auth.authenticationRequest',
            credential: b64(password),
            credentialType: 'hashed_password'
        }
    });
    let text = await resp.text();
    return text === 'true';
}

async function createNoteCtx(ctx, groupPath, name, text) {
    let existing = await searchCtx(ctx, 'data.note', 'name', name);
    if (existing && existing.objectId) return existing;

    let dir = await ensurePathCtx(ctx, 'auth.group', 'data', groupPath);
    if (!dir || !dir.id) return null;

    let resp = await ctx.post(REST + '/model', {
        data: {
            schema: 'data.note',
            groupId: dir.id,
            groupPath: dir.path,
            name: name,
            text: text || 'E2E test note content'
        }
    });
    return await safeJson(resp);
}

async function deleteObjectCtx(ctx, type, objectId) {
    await ctx.delete(REST + '/model/' + type + '/' + objectId);
}

async function createObjectCtx(ctx, schema, data) {
    let existing = data.name ? await searchCtx(ctx, schema, 'name', data.name) : null;
    if (existing && existing.objectId) return existing;

    let resp = await ctx.post(REST + '/model', {
        data: Object.assign({ schema }, data)
    });
    return await safeJson(resp);
}

async function listObjectsCtx(ctx, type, groupId, count) {
    let fields = [{ name: 'groupId', comparator: 'equals', value: String(groupId) }];
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: type,
            fields: fields,
            request: ['id', 'objectId', 'name', 'groupPath'],
            recordCount: count || 10
        }
    });
    let result = await safeJson(resp);
    return (result && result.results) ? result.results : [];
}

// ── Public exports (backward-compatible, use shared request fixture) ───

export async function apiLogin(request, opts) { return loginCtx(request, opts); }
export async function apiLogout(request) { return logoutCtx(request); }
export async function searchByField(request, type, fieldName, fieldValue, fields) {
    return searchCtx(request, type, fieldName, fieldValue, fields);
}
export async function ensurePath(request, type, subType, dirPath) {
    return ensurePathCtx(request, type, subType, dirPath);
}
export async function findPath(request, type, subType, dirPath) {
    return findPathCtx(request, type, subType, dirPath);
}
export async function createNote(request, groupPath, name, text) {
    return createNoteCtx(request, groupPath, name, text);
}
export async function deleteObject(request, type, objectId) {
    return deleteObjectCtx(request, type, objectId);
}

// ── Composite helpers (each creates its own isolated session) ──────────

/**
 * Full test setup: login as admin, create test user + credential + test data.
 * Uses its own isolated APIRequestContext so parallel workers don't conflict.
 * Returns { user, testUserName, testPassword, notes }.
 */
export async function setupTestUser(request, opts = {}) {
    const org = opts.org || '/Development';
    const suffix = opts.suffix || Date.now().toString(36);
    const testUserName = 'e2etest_' + suffix;
    const testPassword = 'password';
    const noteCount = opts.noteCount || 3;
    const notePrefix = opts.notePrefix || testUserName;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org });

        let user = await searchCtx(ctx, 'system.user', 'name', testUserName);
        if (!user || !user.objectId) {
            user = await createUserCtx(ctx, testUserName);
        }

        if (user && user.objectId) {
            await setCredentialCtx(ctx, user.objectId, testPassword);
        }

        let notes = [];
        let orgPath = org.replace(/^\//, '');
        for (let i = 1; i <= noteCount; i++) {
            let note = await createNoteCtx(ctx, '/' + orgPath + '/Notes', notePrefix + ' Note ' + i, 'Test content ' + i);
            if (note && note.objectId) notes.push(note);
        }

        await logoutCtx(ctx);
        return { user, testUserName, testPassword, notes };
    } finally {
        await ctx.dispose();
    }
}

/**
 * Cleanup test user and all associated objects.
 * Uses its own isolated APIRequestContext.
 */
export async function cleanupTestUser(request, userObjectId, opts = {}) {
    const org = opts.org || '/Development';
    const userName = opts.userName;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org });

        if (userName) {
            let homeGroup = await findPathCtx(ctx, 'auth.group', 'data', '/home/' + userName).catch(() => null);
            if (homeGroup && homeGroup.objectId) {
                await deleteObjectCtx(ctx, 'auth.group', homeGroup.objectId).catch(() => {});
            }

            let homeRole = await findPathCtx(ctx, 'auth.role', 'user', '/home/' + userName).catch(() => null);
            if (homeRole && homeRole.objectId) {
                await deleteObjectCtx(ctx, 'auth.role', homeRole.objectId).catch(() => {});
            }

            let homePerm = await findPathCtx(ctx, 'auth.permission', 'user', '/home/' + userName).catch(() => null);
            if (homePerm && homePerm.objectId) {
                await deleteObjectCtx(ctx, 'auth.permission', homePerm.objectId).catch(() => {});
            }

            let person = await searchCtx(ctx, 'identity.person', 'name', userName).catch(() => null);
            if (person && person.objectId) {
                await deleteObjectCtx(ctx, 'identity.person', person.objectId).catch(() => {});
            }
        }

        if (userObjectId) {
            await deleteObjectCtx(ctx, 'system.user', userObjectId).catch(() => {});
        }

        await logoutCtx(ctx);
    } finally {
        await ctx.dispose();
    }
}

/**
 * Full cleanup including orphan pruning + Postgres VACUUM.
 */
export async function cleanupTestUserFull(request, userObjectId, opts = {}) {
    await cleanupTestUser(request, userObjectId, opts);

    let ctx = await newApiContext();
    try {
        const org = opts.org || '/Development';
        await loginCtx(ctx, { org });
        await ctx.get(REST + '/model/cleanup').catch(() => {});
        await logoutCtx(ctx);
    } finally {
        await ctx.dispose();
    }
}

/**
 * Ensure a shared persistent test user exists (idempotent — no cleanup needed).
 * Uses its own isolated APIRequestContext.
 */
const SHARED_USER = 'e2etest_shared';
const SHARED_PASSWORD = 'password';

export async function ensureSharedTestUser(request, opts = {}) {
    const org = opts.org || '/Development';

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org });

        let user = await searchCtx(ctx, 'system.user', 'name', SHARED_USER);
        if (!user || !user.objectId) {
            user = await createUserCtx(ctx, SHARED_USER);
            if (user && user.objectId) {
                await setCredentialCtx(ctx, user.objectId, SHARED_PASSWORD);
            }
        }

        await logoutCtx(ctx);
        return { user, testUserName: SHARED_USER, testPassword: SHARED_PASSWORD };
    } finally {
        await ctx.dispose();
    }
}

/**
 * Setup workflow test data: create test user + charPerson + data.data objects.
 * Uses its own isolated APIRequestContext.
 * Returns { user, testUserName, testPassword, charPerson, dataObject, note }.
 */
export async function setupWorkflowTestData(request, opts = {}) {
    const org = opts.org || '/Development';
    const suffix = opts.suffix || 'wf' + Date.now().toString(36);
    const testUserName = 'e2etest_' + suffix;
    const testPassword = 'password';

    // Phase 1: Admin creates and configures the test user
    let adminCtx = await newApiContext();
    let user;
    try {
        await loginCtx(adminCtx, { org });
        user = await searchCtx(adminCtx, 'system.user', 'name', testUserName);
        if (!user || !user.objectId) {
            user = await createUserCtx(adminCtx, testUserName);
        }
        if (user && user.objectId) {
            await setCredentialCtx(adminCtx, user.objectId, testPassword);
        }
        await logoutCtx(adminCtx);
    } finally {
        await adminCtx.dispose();
    }

    // Phase 2: Login as test user to create data in their home directory
    let userCtx = await newApiContext();
    let charPerson = null, dataObject = null, note = null;
    let charDirId = null, dataDirId = null;
    try {
        await loginCtx(userCtx, { org, user: testUserName, password: testPassword });

        // Ensure home subdirectories exist (~ = user's home dir)
        let charDir = await ensurePathCtx(userCtx, 'auth.group', 'data', '~/Characters');
        let dataDir = await ensurePathCtx(userCtx, 'auth.group', 'data', '~/Data');

        if (charDir && charDir.id) {
            charDirId = charDir.objectId;
            charPerson = await createObjectCtx(userCtx, 'olio.charPerson', {
                name: testUserName + '_char',
                firstName: 'Test',
                middleName: 'E2E',
                lastName: 'Character',
                gender: 'female',
                alignment: 'NEUTRAL_GOOD',
                groupId: charDir.id,
                groupPath: charDir.path
            });
        }

        if (dataDir && dataDir.id) {
            dataDirId = dataDir.objectId;
            dataObject = await createObjectCtx(userCtx, 'data.data', {
                name: testUserName + '_data.txt',
                contentType: 'text/plain',
                groupId: dataDir.id,
                groupPath: dataDir.path
            });
        }

        note = await createNoteCtx(userCtx, '~/Notes', testUserName + '_note', 'Workflow test content');

        await logoutCtx(userCtx);
    } finally {
        await userCtx.dispose();
    }

    return {
        user, testUserName, testPassword,
        charPerson, dataObject, note,
        charDirId, dataDirId
    };
}
