/**
 * API helpers for E2E test data setup/teardown.
 *
 * Each setup/teardown function creates its own APIRequestContext (own cookie jar)
 * so that parallel workers don't share and clobber each other's server sessions.
 */
import { request as pwRequest } from '@playwright/test';

// Default unchanged (the local Vite dev server). Set PLAYWRIGHT_BASE_URL to point the API setup calls
// at an already-running deployment — e.g. the docker-compose.test.yml stack on https://localhost:9443,
// which serves the Ux and /AccountManagerService7 from the same origin. Kept in sync with
// playwright.config.js, which reads the same variable.
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';
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

async function analyzeFaceCtx(ctx, imageDataUrl) {
    let resp = await ctx.post(REST + '/face/analyze', {
        headers: { 'Content-Type': 'application/json' },
        data: { image_data: imageDataUrl }
    });
    return await safeJson(resp);
}

// ── Public exports (backward-compatible, use shared request fixture) ───

export async function apiLogin(request, opts) { return loginCtx(request, opts); }
export async function analyzeFace(request, imageDataUrl) { return analyzeFaceCtx(request, imageDataUrl); }
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
export async function createObject(request, schema, data) {
    return createObjectCtx(request, schema, data);
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

    // Phase 1: Admin creates user and sets credential
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

    // Phase 2: Test user logs in to initialize home directory, then creates own notes
    let userCtx = await newApiContext();
    let notes = [];
    try {
        await loginCtx(userCtx, { org, user: testUserName, password: testPassword });
        for (let i = 1; i <= noteCount; i++) {
            let note = await createNoteCtx(userCtx, '~/Notes', notePrefix + ' Note ' + i, 'Test content ' + i);
            if (note && note.objectId) notes.push(note);
        }
        await logoutCtx(userCtx);
    } finally {
        await userCtx.dispose();
    }

    return { user, testUserName, testPassword, notes };
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
            // auth.group: use path find (works, PathProvider "name" warning is cosmetic)
            // auth.role/permission: use search by name (avoids PathProvider errors)
            let homeGroup = await findPathCtx(ctx, 'auth.group', 'data', '/home/' + userName).catch(() => null);
            if (homeGroup && homeGroup.objectId) {
                let childPaths = ['~/Notes', '~/Characters', '~/Data', '~/Colors', '~/Tags', '~/Apparel'];
                for (let cp of childPaths) {
                    let resolved = cp.replace('~', '/home/' + userName);
                    let child = await findPathCtx(ctx, 'auth.group', 'data', resolved).catch(() => null);
                    if (child && child.objectId) {
                        await deleteObjectCtx(ctx, 'auth.group', child.objectId).catch(() => {});
                    }
                }
                await deleteObjectCtx(ctx, 'auth.group', homeGroup.objectId).catch(() => {});
            }

            let homeRole = await searchCtx(ctx, 'auth.role', 'name', userName, ['id', 'objectId', 'name']).catch(() => null);
            if (homeRole && homeRole.objectId) {
                await deleteObjectCtx(ctx, 'auth.role', homeRole.objectId).catch(() => {});
            }

            let homePerm = await searchCtx(ctx, 'auth.permission', 'name', userName, ['id', 'objectId', 'name']).catch(() => null);
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

    // Phase 1: Admin creates the user if needed
    let ctx = await newApiContext();
    let user;
    try {
        await loginCtx(ctx, { org });

        user = await searchCtx(ctx, 'system.user', 'name', SHARED_USER);
        if (!user || !user.objectId) {
            user = await createUserCtx(ctx, SHARED_USER);
            if (user && user.objectId) {
                await setCredentialCtx(ctx, user.objectId, SHARED_PASSWORD);
            }
        }

        await logoutCtx(ctx);
    } finally {
        await ctx.dispose();
    }

    // Phase 2: Log in as shared user to initialize home directory
    let userCtx = await newApiContext();
    try {
        await loginCtx(userCtx, { org, user: SHARED_USER, password: SHARED_PASSWORD });
        await logoutCtx(userCtx);
    } finally {
        await userCtx.dispose();
    }

    return { user, testUserName: SHARED_USER, testPassword: SHARED_PASSWORD };
}

// ── ChapBook / LLM chatConfig provisioning ─────────────────────────────
//
// The ChapBook analyze (6B) and render (6C) endpoints resolve a default
// olio.llm.chatConfig via ChapBookUtil.resolveDefaultChatConfig(user), which
// filters by organizationId AND ownerId. A clean Docker test DB has none, so
// analyze/render 503 with "No chatConfig is configured for this organization".
// This helper provisions one — owned by the shared test user so the ownerId
// filter matches — mirroring the canonical creation path in
// AccountManagerObjects7 ChatLibraryUtil.createLibraryConnection /
// createLibraryChatConfig: a system.connection holding the serverUrl, and an
// olio.llm.chatConfig with serviceType=ollama + model, referencing that
// connection by FK. Idempotent: searches by stable name (cache:false) first.
const CHATCONFIG_NAME = 'e2e-chapbook-llm';
const CHATCONN_NAME = 'e2e-chapbook-conn';
// CHAT models on the Ollama server at the DGX Spark (192.168.1.42:11434).
// qwen3:8b is the fast CHAT model — adequate for JSON theme/mood analysis.
const CHAT_SERVER_URL = 'http://192.168.1.42:11434';
const CHAT_MODEL = 'qwen3:8b';

/** Search a data.directory-derived model by name within an org (cache:false so freshly-created records are seen). */
async function searchByNameOrgCtx(ctx, type, name, orgId, fields) {
    let resp = await ctx.post(REST + '/model/search', {
        data: {
            schema: 'io.query',
            type: type,
            fields: [
                { name: 'name', comparator: 'equals', value: name },
                { name: 'organizationId', comparator: 'equals', value: orgId }
            ],
            request: fields || ['id', 'objectId', 'name'],
            recordCount: 1,
            cache: false
        }
    });
    let result = await safeJson(resp);
    return (result && result.results && result.results.length > 0) ? result.results[0] : null;
}

/**
 * Ensure an olio.llm.chatConfig (named `e2e-chapbook-llm`) owned by the shared test user exists in
 * the given org, pointing at the Ollama CHAT server via a system.connection, so ChapBook analyze/render
 * can resolve a default chatConfig. Idempotent — safe to call repeatedly.
 *
 * @param request Playwright APIRequestContext (unused directly; helper opens its own session so the
 *                chatConfig is owned by the shared user regardless of the caller's session state).
 * @param orgId   numeric organizationId (e.g. 2 for /Development on the Docker stack).
 * @returns the chatConfig name to pass in the analyze/render body ({chatConfig: name}), or null on failure.
 */
export async function ensureChatConfig(request, orgId, opts = {}) {
    const org = opts.org || '/Development';
    const user = opts.user || SHARED_USER;
    const password = opts.password || SHARED_PASSWORD;
    const serverUrl = opts.serverUrl || CHAT_SERVER_URL;
    const model = opts.model || CHAT_MODEL;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org, user, password });

        // A data.directory group to hold the connection + config (mirrors the UI's ~/Chat dir).
        // The path/make response also carries organizationId — there is NO /login/principal route
        // (it 404s), so resolve orgId from this group rather than a phantom principal endpoint.
        let chatDir = await ensurePathCtx(ctx, 'auth.group', 'data', '~/Chat');
        let groupId = chatDir && chatDir.id;
        let groupPath = chatDir && chatDir.path;
        if (!groupId) { await logoutCtx(ctx); return null; }

        // Resolve orgId if the caller did not supply it (from the group we just ensured).
        let resolvedOrgId = orgId || (chatDir && chatDir.organizationId);
        if (!resolvedOrgId) { await logoutCtx(ctx); return null; }

        // 1. system.connection holding the Ollama serverUrl (find-or-create).
        let conn = await searchByNameOrgCtx(ctx, 'system.connection', CHATCONN_NAME, resolvedOrgId,
            ['id', 'objectId', 'name', 'serverUrl']);
        if (!conn) {
            await ctx.post(REST + '/model', {
                data: {
                    schema: 'system.connection',
                    name: CHATCONN_NAME,
                    groupId: groupId,
                    groupPath: groupPath,
                    serverUrl: serverUrl,
                    requestTimeout: 300
                }
            });
            conn = await searchByNameOrgCtx(ctx, 'system.connection', CHATCONN_NAME, resolvedOrgId,
                ['id', 'objectId', 'name', 'serverUrl']);
        }
        if (!conn || !conn.id) { await logoutCtx(ctx); return null; }

        // 2. olio.llm.chatConfig referencing that connection by FK (find-or-create).
        //    serviceType enum is lowercase on the wire ("ollama" → LLMServiceEnumType.OLLAMA).
        let cfg = await searchByNameOrgCtx(ctx, 'olio.llm.chatConfig', CHATCONFIG_NAME, resolvedOrgId,
            ['id', 'objectId', 'name']);
        if (!cfg) {
            await ctx.post(REST + '/model', {
                data: {
                    schema: 'olio.llm.chatConfig',
                    name: CHATCONFIG_NAME,
                    groupId: groupId,
                    groupPath: groupPath,
                    serviceType: 'ollama',
                    model: model,
                    analyzeModel: model,
                    connection: { schema: 'system.connection', id: conn.id, objectId: conn.objectId }
                }
            });
            cfg = await searchByNameOrgCtx(ctx, 'olio.llm.chatConfig', CHATCONFIG_NAME, resolvedOrgId,
                ['id', 'objectId', 'name']);
        }

        await logoutCtx(ctx);
        return cfg ? cfg.name : null;
    } finally {
        await ctx.dispose();
    }
}

/**
 * Ensure a persistent test user that holds the AccountAdministrators role, so tests can exercise
 * @RolesAllowed({"admin"}) endpoints WITHOUT ever logging in as `admin`.
 *
 * AccountManagerService7/src/main/webapp/WEB-INF/resource/roleMap.json maps the JAAS role `admin` onto
 * the AccountAdministrators role, so membership in that role is what the annotation actually checks.
 * As with ensureSharedTestUser / ensureIso42001TestUser, the admin session here is confined to
 * provisioning (creating the user, setting its credential, granting the role) — every assertion in the
 * calling test runs as this non-admin user.
 *
 * Idempotent. Returns { user, testUserName, testPassword, roleAssigned }.
 */
const ADMIN_ROLE_USER = 'e2etest_featadmin';
const ADMIN_ROLE_PASSWORD = 'password';

export async function ensureAdminRoleTestUser(request, opts = {}) {
    const org = opts.org || '/Development';

    let ctx = await newApiContext();
    let user;
    let roleAssigned = false;
    try {
        await loginCtx(ctx, { org });

        user = await searchCtx(ctx, 'system.user', 'name', ADMIN_ROLE_USER);
        if (!user || !user.objectId) {
            user = await createUserCtx(ctx, ADMIN_ROLE_USER);
        }
        if (user && user.objectId) {
            await setCredentialCtx(ctx, user.objectId, ADMIN_ROLE_PASSWORD);
            let role = await searchCtx(ctx, 'auth.role', 'name', 'AccountAdministrators', ['objectId', 'name']);
            if (role && role.objectId) {
                await memberCtx(ctx, 'auth.role', role.objectId, 'system.user', user.objectId, true);
                roleAssigned = true;
            }
        }
        await logoutCtx(ctx);
    } finally {
        await ctx.dispose();
    }

    // Log in once as the user so its home directory is initialized.
    let userCtx = await newApiContext();
    try {
        await loginCtx(userCtx, { org, user: ADMIN_ROLE_USER, password: ADMIN_ROLE_PASSWORD });
        await logoutCtx(userCtx);
    } finally {
        await userCtx.dispose();
    }

    return { user, testUserName: ADMIN_ROLE_USER, testPassword: ADMIN_ROLE_PASSWORD, roleAssigned };
}

/**
 * Set the organization's enabled feature set through the REST API as the admin-role TEST user
 * (never as `admin`). Returns the stored feature array, or null on failure.
 */
export async function setOrgFeatures(request, features, opts = {}) {
    const org = opts.org || '/Development';
    const userName = opts.userName || ADMIN_ROLE_USER;
    const password = opts.password || ADMIN_ROLE_PASSWORD;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org, user: userName, password: password });
        let resp = await ctx.put(REST + '/config/features', { data: { features: features } });
        let out = null;
        if (resp.ok()) {
            let json = await safeJson(resp);
            out = (json && Array.isArray(json.features)) ? json.features : null;
        }
        await logoutCtx(ctx);
        return out;
    } finally {
        await ctx.dispose();
    }
}

/**
 * Read the organization's enabled feature set as a given (non-admin) test user.
 * Returns { status, features, profile }.
 */
export async function getOrgFeatures(request, opts = {}) {
    const org = opts.org || '/Development';
    const userName = opts.userName || SHARED_USER;
    const password = opts.password || SHARED_PASSWORD;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org, user: userName, password: password });
        let resp = await ctx.get(REST + '/config/features');
        let json = await safeJson(resp);
        await logoutCtx(ctx);
        return { status: resp.status(), features: (json ? json.features : null), profile: (json ? json.profile : null) };
    } finally {
        await ctx.dispose();
    }
}

/** Fetch GET /rest/config/features/available as a given (non-admin) test user. */
export async function getAvailableFeatures(request, opts = {}) {
    const org = opts.org || '/Development';
    const userName = opts.userName || SHARED_USER;
    const password = opts.password || SHARED_PASSWORD;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org, user: userName, password: password });
        let resp = await ctx.get(REST + '/config/features/available');
        let text = await resp.text();
        await logoutCtx(ctx);
        let parsed = null;
        try { parsed = JSON.parse(text); } catch { /* left null for the caller to assert on */ }
        return { status: resp.status(), body: text, manifest: parsed };
    } finally {
        await ctx.dispose();
    }
}

/** Attempt PUT /rest/config/features as a plain (non-admin) test user. Returns the HTTP status. */
export async function putOrgFeaturesStatus(request, features, opts = {}) {
    const org = opts.org || '/Development';
    const userName = opts.userName || SHARED_USER;
    const password = opts.password || SHARED_PASSWORD;

    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org, user: userName, password: password });
        let resp = await ctx.put(REST + '/config/features', { data: { features: features } });
        let status = resp.status();
        await logoutCtx(ctx);
        return status;
    } finally {
        await ctx.dispose();
    }
}

/** Add an actor to a container (role/group) via the authorization member endpoint (null field = default participation). */
async function memberCtx(ctx, containerType, containerObjectId, actorType, actorObjectId, enable) {
    let resp = await ctx.get(sAuthZ(containerType, containerObjectId, actorType, actorObjectId, enable));
    let text = await resp.text();
    return text === 'true';
}

function sAuthZ(containerType, containerObjectId, actorType, actorObjectId, enable) {
    return REST + '/authorization/' + containerType + '/' + containerObjectId
        + '/member/null/' + actorType + '/' + actorObjectId + '/' + (enable ? 'true' : 'false');
}

/**
 * Ensure a persistent ISO 42001 test user that is a member of the requested ISO roles (default: the full
 * working set Testers/Reporters/Certifiers/Administrators so the positive create→run→report→certify flow is
 * exercisable). Admin-only is used to create the user + assign roles; the roles themselves are provisioned by
 * Service7 at startup (ISO42001Provisioning.ensureRoles). Idempotent.
 *
 * @returns { user, testUserName, testPassword, rolesAssigned }
 */
const ISO_USER = 'e2etest_iso42001';
const ISO_PASSWORD = 'password';
const ISO_ROLES_DEFAULT = ['ISO42001Testers', 'ISO42001Reporters', 'ISO42001Certifiers', 'ISO42001Administrators'];

export async function ensureIso42001TestUser(request, opts = {}) {
    const org = opts.org || '/Development';
    const roleNames = opts.roles || ISO_ROLES_DEFAULT;

    let ctx = await newApiContext();
    let user;
    let rolesAssigned = [];
    try {
        await loginCtx(ctx, { org });

        user = await searchCtx(ctx, 'system.user', 'name', ISO_USER);
        if (!user || !user.objectId) {
            user = await createUserCtx(ctx, ISO_USER);
            if (user && user.objectId) {
                await setCredentialCtx(ctx, user.objectId, ISO_PASSWORD);
            }
        }

        // Assign the ISO roles (provisioned at Service7 startup). Search by name → member via authZ endpoint.
        // The member-add returns false when the user is ALREADY a member (idempotent no-op), so we record the
        // role as assigned whenever it resolves and we attempted the add — rolesAssigned reflects the
        // post-condition (the user is a member), not just roles added on this specific run.
        for (let roleName of roleNames) {
            let role = await searchCtx(ctx, 'auth.role', 'name', roleName, ['objectId', 'name']);
            if (role && role.objectId && user && user.objectId) {
                await memberCtx(ctx, 'auth.role', role.objectId, 'system.user', user.objectId, true);
                rolesAssigned.push(roleName);
            }
        }

        await logoutCtx(ctx);
    } finally {
        await ctx.dispose();
    }

    // Log in as the ISO user once to initialize its home directory.
    let userCtx = await newApiContext();
    try {
        await loginCtx(userCtx, { org, user: ISO_USER, password: ISO_PASSWORD });
        await logoutCtx(userCtx);
    } finally {
        await userCtx.dispose();
    }

    return { user, testUserName: ISO_USER, testPassword: ISO_PASSWORD, rolesAssigned };
}

/**
 * Assign an existing user (e.g. the shared test user) to a named system role, using an admin context
 * purely for the provisioning step (mirrors ensureIso42001TestUser's role-assignment loop) — the caller
 * still performs the actual UI-driven test as the non-admin user. Idempotent (member-add is a no-op if
 * already a member).
 * @returns {boolean} true if the role was found and the member-add call was made.
 */
export async function addUserToRole(request, userObjectId, roleName, opts = {}) {
    const org = opts.org || '/Development';
    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org });
        let role = await searchCtx(ctx, 'auth.role', 'name', roleName, ['objectId', 'name']);
        let ok = false;
        if (role && role.objectId && userObjectId) {
            await memberCtx(ctx, 'auth.role', role.objectId, 'system.user', userObjectId, true);
            ok = true;
        }
        await logoutCtx(ctx);
        return ok;
    } finally {
        await ctx.dispose();
    }
}

/**
 * Remove a user from a named system role using an admin context. Idempotent —
 * member-remove is a no-op if the user is not a member.
 * @returns {boolean} true if the role was found and the member-remove call was made.
 */
export async function removeUserFromRole(request, userObjectId, roleName, opts = {}) {
    const org = opts.org || '/Development';
    let ctx = await newApiContext();
    try {
        await loginCtx(ctx, { org });
        let role = await searchCtx(ctx, 'auth.role', 'name', roleName, ['objectId', 'name']);
        let ok = false;
        if (role && role.objectId && userObjectId) {
            await memberCtx(ctx, 'auth.role', role.objectId, 'system.user', userObjectId, false);
            ok = true;
        }
        await logoutCtx(ctx);
        return ok;
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

    // Phase 1: Admin creates the test user
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

    // Phase 2: Login as test user to initialize their home directory
    let initCtx = await newApiContext();
    try {
        await loginCtx(initCtx, { org, user: testUserName, password: testPassword });
        await logoutCtx(initCtx);
    } finally {
        await initCtx.dispose();
    }

    // Phase 3: Test user creates their own test data (owns objects = can read them)
    let userCtx = await newApiContext();
    let charPerson = null, dataObject = null, note = null;
    let charDirId = null, dataDirId = null;
    try {
        await loginCtx(userCtx, { org, user: testUserName, password: testPassword });

        let charDir = await ensurePathCtx(userCtx, 'auth.group', 'data', '~/Characters');
        if (charDir && charDir.id) {
            charDirId = charDir.objectId;
            charPerson = await createObjectCtx(userCtx, 'olio.charPerson', {
                name: testUserName + '_char',
                firstName: 'Test',
                middleName: 'E2E',
                lastName: 'Character',
                gender: 'female',
                alignment: 'neutralgood',
                groupId: charDir.id,
                groupPath: charDir.path
            });
        }

        let dataDir = await ensurePathCtx(userCtx, 'auth.group', 'data', '~/Data');
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
