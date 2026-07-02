/**
 * ISO 42001 feature-local REST client — thin wrappers over the Phase-7 /rest/iso42001/* endpoints plus a
 * generic model search for list views. Kept feature-local (rather than bloating core/am7client.js) so all ISO
 * transport stays together. Auth is the session cookie (withCredentials); the server enforces RBAC.
 */
import m from 'mithril';
import { applicationPath } from '../../core/config.js';
import { page } from '../../core/pageClient.js';

const REST = applicationPath + '/rest';
const ISO = REST + '/iso42001';

function req(method, url, body) {
    let opts = { method: method, url: url, withCredentials: true, background: true };
    if (body !== undefined) {
        opts.body = body;
    }
    return m.request(opts);
}

export const iso42001Client = {
    // ── Library/dashboard ──
    dashboard: () => req('GET', ISO + '/dashboard'),
    modules: () => req('GET', ISO + '/modules'),
    endpoints: () => req('GET', ISO + '/endpoints'),

    // ── Test config (campaign) + run ──
    // Create goes through the ISO service (RBAC-gated, returns the full record). Update/delete are done by the
    // view via the shared page client (page.patchObject / page.deleteObject → am7client, cache-aware) rather
    // than a feature-local REST call, so all model mutation uses one battle-tested transport.
    createConfig: (config) => req('POST', ISO + '/config', config),
    getConfig: (objectId) => req('GET', ISO + '/config/' + objectId),
    startRun: (testConfigId) => req('POST', ISO + '/run', { testConfigId: testConfigId }),
    getRun: (objectId) => req('GET', ISO + '/run/' + objectId),
    getRunResults: (objectId) => req('GET', ISO + '/run/' + objectId + '/results'),

    // ── Reports ──
    generateReport: (name, reportType, testRunIds) =>
        req('POST', ISO + '/report', { name: name, reportType: reportType, testRunIds: testRunIds }),
    getReport: (objectId) => req('GET', ISO + '/report/' + objectId),
    exportPdf: (objectId) => req('POST', ISO + '/report/' + objectId + '/export'),
    pdfUrl: (objectId) => ISO + '/report/' + objectId + '/pdf',

    // ── Certification ──
    requestCertification: (reportId, certifierId, justification) =>
        req('POST', ISO + '/certification/request',
            { reportId: reportId, certifierId: certifierId, justification: justification }),
    approve: (requestId, note) => req('POST', ISO + '/certification/approve/' + requestId, { note: note }),
    deny: (requestId, reason) => req('POST', ISO + '/certification/deny/' + requestId, { reason: reason }),
    getRequest: (objectId) => req('GET', ISO + '/certification/request/' + objectId),
    appendMessage: (requestId, text) => req('POST', ISO + '/certification/request/' + requestId + '/message', { text: text }),
    getCertification: (objectId) => req('GET', ISO + '/certification/' + objectId),
    verify: (objectId) => req('POST', ISO + '/certification/verify/' + objectId),
    revoke: (certId, reason) => req('POST', ISO + '/certification/' + certId + '/revoke', { reason: reason }),

    // Simple user search for the certifier picker (name prefix). RBAC on read applies server-side.
    searchUsers: (nameLike) => {
        let query = {
            schema: 'io.query', type: 'system.user', cache: false,
            fields: [{ name: 'name', comparator: 'like', value: (nameLike || '') + '%' }],
            request: ['objectId', 'name'], startRecord: 0, recordCount: 10
        };
        return req('POST', REST + '/model/search', query);
    },

    // ── Generic org-scoped list (for the browse/list views) ──
    // cache:false — the server caches /model/search by query key; without this the ISO list views (campaigns,
    // runs, reports, requests) serve stale results and miss just-created/edited/deleted records.
    // An explicit organizationId condition is ALWAYS added: PBAC needs an org context for the role lookup, and
    // without it a list of a data.directory type makes the server policy-check a bare {schema} instance
    // ("Group could not be found" log noise). `filters` (optional) adds further conditions (e.g. a groupId to
    // compartmentalize) — combined with AND.
    list: (type, fields, startRecord, recordCount, filters) => {
        let conds = Array.isArray(filters) ? filters.slice() : [];
        let orgId = page && page.user ? page.user.organizationId : null;
        if (orgId != null && !conds.some(c => c.name === 'organizationId')) {
            // Numeric value — organizationId is a long; a string "2" does not match the long field.
            conds.push({ name: 'organizationId', comparator: 'equals', value: Number(orgId) });
        }
        let query = {
            schema: 'io.query',
            type: type,
            cache: false,
            request: fields || ['id', 'objectId', 'name', 'organizationId'],
            startRecord: startRecord || 0,
            recordCount: recordCount || 50
        };
        if (conds.length) {
            query.fields = conds;
        }
        return req('POST', REST + '/model/search', query);
    }
};

export default iso42001Client;
