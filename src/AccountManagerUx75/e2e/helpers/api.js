/**
 * API helpers for E2E test data setup/teardown.
 * Uses Playwright's request context to call the backend REST API.
 *
 * Flow: login as admin → create test user → set credential → logout → login as test user → create test data
 */

const BASE = 'http://localhost:8899/AccountManagerService7/rest';

function b64(str) {
    return Buffer.from(str).toString('base64');
}

/**
 * Login via REST API.
 */
export async function apiLogin(request, opts = {}) {
    const org = opts.org || '/Development';
    const user = opts.user || 'admin';
    const password = opts.password || 'password';

    let resp = await request.post(BASE + '/login', {
        data: {
            schema: 'auth.credential',
            organizationPath: org,
            name: user,
            credential: b64(password),
            type: 'hashed_password'
        }
    });
    return resp;
}

/**
 * Logout via REST API.
 */
export async function apiLogout(request) {
    await request.get(BASE + '/logout');
}

/**
 * Search for objects by type and field value.
 */
export async function searchByField(request, type, fieldName, fieldValue, fields) {
    let resp = await request.post(BASE + '/model/search', {
        data: {
            schema: 'io.query',
            type: type,
            fields: [{ name: fieldName, comparator: 'equals', value: fieldValue }],
            request: fields || ['id', 'objectId', 'name'],
            recordCount: 1
        }
    });
    let result = await resp.json();
    return (result && result.results && result.results.length > 0) ? result.results[0] : null;
}

/**
 * Encode a path for the path service (base64 with B64- prefix if needed).
 */
function encodePath(dirPath) {
    if (dirPath.startsWith('/') || dirPath.includes('.')) {
        return 'B64-' + b64(dirPath).replace(/=/g, '%3D');
    }
    return dirPath;
}

/**
 * Ensure a directory path exists (make endpoint).
 */
export async function ensurePath(request, type, subType, dirPath) {
    let resp = await request.get(BASE + '/path/make/' + type + '/' + subType + '/' + encodePath(dirPath));
    let text = await resp.text();
    if (!text) return null;
    return JSON.parse(text);
}

/**
 * Find an object by path (does NOT create if missing).
 */
export async function findPath(request, type, subType, dirPath) {
    let resp = await request.get(BASE + '/path/find/' + type + '/' + subType + '/' + encodePath(dirPath));
    let text = await resp.text();
    if (!text) return null;
    try { return JSON.parse(text); } catch { return null; }
}

/**
 * Create a system.user. UserWriter handles group assignment automatically.
 * Must be logged in as admin.
 */
export async function createUser(request, name) {
    await request.post(BASE + '/model', {
        data: { schema: 'system.user', name: name }
    });
    // UserWriter returns minimal response; search to get the full object
    return await searchByField(request, 'system.user', 'name', name);
}

/**
 * Set password credential on a user via CredentialService.
 * Must be logged in as admin. Admin can set credentials without supplying the old password.
 */
export async function setCredential(request, userObjectId, password) {
    let resp = await request.post(BASE + '/credential/system.user/' + userObjectId, {
        data: {
            schema: 'auth.authenticationRequest',
            credential: b64(password),
            credentialType: 'hashed_password'
        }
    });
    let text = await resp.text();
    return text === 'true';
}

/**
 * Create a data.note in the given group directory.
 */
export async function createNote(request, groupPath, name, text) {
    let dir = await ensurePath(request, 'data.note', 'data', groupPath);
    if (!dir || !dir.id) return null;

    let resp = await request.post(BASE + '/model', {
        data: {
            schema: 'data.note',
            groupId: dir.id,
            groupPath: dir.path,
            name: name,
            text: text || 'E2E test note content'
        }
    });
    let body = await resp.text();
    if (!body) return null;
    return JSON.parse(body);
}

/**
 * Delete an object by type and objectId.
 */
export async function deleteObject(request, type, objectId) {
    await request.delete(BASE + '/model/' + type + '/' + objectId);
}

/**
 * Full test setup: login as admin, create test user + credential + test data, logout, login as test user.
 * Returns { user, testUserName, testPassword, notes }.
 */
export async function setupTestUser(request, opts = {}) {
    const org = opts.org || '/Development';
    const suffix = opts.suffix || Date.now().toString(36);
    const testUserName = 'e2etest_' + suffix;
    const testPassword = 'password';
    const noteCount = opts.noteCount || 3;
    const notePrefix = opts.notePrefix || testUserName;

    // 1. Login as admin
    await apiLogin(request, { org });

    // 2. Create test user
    let user = await createUser(request, testUserName);

    // 3. Set credential
    if (user && user.objectId) {
        await setCredential(request, user.objectId, testPassword);
    }

    // 4. Create test data as admin (test user may not have directory creation permissions)
    let notes = [];
    let orgPath = org.replace(/^\//, '');
    for (let i = 1; i <= noteCount; i++) {
        let note = await createNote(request, '/' + orgPath + '/Notes', notePrefix + ' Note ' + i, 'Test content ' + i);
        if (note && note.objectId) notes.push(note);
    }

    // 5. Logout admin, login as test user
    await apiLogout(request);
    await apiLogin(request, { org, user: testUserName, password: testPassword });

    return { user, testUserName, testPassword, notes };
}

/**
 * Cleanup test user and all associated objects.
 * Mirrors pageClient.cleanupUserRemains: home group, home role, home permission, person object.
 * Then calls /rest/model/cleanup to prune orphaned DB records.
 */
export async function cleanupTestUser(request, userObjectId, opts = {}) {
    const org = opts.org || '/Development';
    const userName = opts.userName;
    const TIMEOUT = 10000;

    await apiLogout(request).catch(() => {});
    await apiLogin(request, { org });

    if (userName) {
        let homeGroup = await findPath(request, 'auth.group', 'data', '/home/' + userName).catch(() => null);
        if (homeGroup && homeGroup.objectId) {
            await deleteObject(request, 'auth.group', homeGroup.objectId).catch(() => {});
        }

        let homeRole = await findPath(request, 'auth.role', 'user', '/home/' + userName).catch(() => null);
        if (homeRole && homeRole.objectId) {
            await deleteObject(request, 'auth.role', homeRole.objectId).catch(() => {});
        }

        let homePerm = await findPath(request, 'auth.permission', 'user', '/home/' + userName).catch(() => null);
        if (homePerm && homePerm.objectId) {
            await deleteObject(request, 'auth.permission', homePerm.objectId).catch(() => {});
        }

        let person = await searchByField(request, 'identity.person', 'name', userName).catch(() => null);
        if (person && person.objectId) {
            await deleteObject(request, 'identity.person', person.objectId).catch(() => {});
        }
    }

    if (userObjectId) {
        await deleteObject(request, 'system.user', userObjectId).catch(() => {});
    }

    // Skip /model/cleanup here — it includes a Postgres VACUUM that can be slow.
    // Use cleanupTestUserFull() if you want orphan pruning + vacuum.

    await apiLogout(request).catch(() => {});
}

/**
 * Full cleanup including orphan pruning + Postgres VACUUM.
 * Same as cleanupTestUser but also calls /rest/model/cleanup at the end.
 * This can take minutes on large databases — do not use in afterAll hooks with tight timeouts.
 */
export async function cleanupTestUserFull(request, userObjectId, opts = {}) {
    await cleanupTestUser(request, userObjectId, opts);

    const org = opts.org || '/Development';
    await apiLogin(request, { org });
    await request.get(BASE + '/model/cleanup').catch(() => {});
    await apiLogout(request).catch(() => {});
}
