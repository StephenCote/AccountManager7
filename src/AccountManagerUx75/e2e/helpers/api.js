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
    if (dirPath.startsWith('/') || dirPath.includes('.')) {
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
