/**
 * ISO 42001 feature-local REST client — thin wrappers over the Phase-7 /rest/iso42001/* endpoints plus a
 * generic model search for list views. Kept feature-local (rather than bloating core/am7client.js) so all ISO
 * transport stays together. Auth is the session cookie (withCredentials); the server enforces RBAC.
 */
import m from 'mithril';
import { applicationPath } from '../../core/config.js';

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
    getCertification: (objectId) => req('GET', ISO + '/certification/' + objectId),
    verify: (objectId) => req('POST', ISO + '/certification/verify/' + objectId),

    // ── Generic org-scoped list (for the browse/list views) ──
    // cache:false — the server caches /model/search by query key; without this the ISO list views (campaigns,
    // runs, reports, requests) serve stale results and miss just-created/edited/deleted records.
    list: (type, fields, startRecord, recordCount) => {
        let query = {
            schema: 'io.query',
            type: type,
            cache: false,
            request: fields || ['id', 'objectId', 'name', 'organizationId'],
            startRecord: startRecord || 0,
            recordCount: recordCount || 50
        };
        return req('POST', REST + '/model/search', query);
    }
};

export default iso42001Client;
