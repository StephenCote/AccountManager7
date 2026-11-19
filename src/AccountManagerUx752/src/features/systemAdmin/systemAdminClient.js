/**
 * System Admin feature-local REST client — thin wrapper over /rest/admin (AdminService). Mirrors
 * olioAdminClient's transport (m.request + session cookie; the server enforces @RolesAllowed admin and
 * AdminMaintenanceUtil re-checks the role in-process). The organization is derived server-side from the
 * principal; the only scope input is the boolean `allOrganizations`, and whether the caller may use it
 * (SystemAdministrators only) is decided by the server — see GET /scope.
 */
import m from 'mithril';
import { applicationPath } from '../../core/config.js';

const REST = applicationPath + '/rest';
const ADMIN = REST + '/admin';

function req(method, url, body) {
    let opts = { method: method, url: url, withCredentials: true, background: true };
    if (body !== undefined) {
        opts.body = body;
    }
    return m.request(opts);
}

function scoped(url, allOrganizations, extra) {
    let params = Object.assign({ allOrganizations: !!allOrganizations }, extra || {});
    let qs = Object.keys(params)
        .filter(k => params[k] !== undefined && params[k] !== null)
        .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k]))
        .join('&');
    return url + (qs ? '?' + qs : '');
}

export const systemAdminClient = {
    // {organizationId, organizationPath, systemAdministrator, organizations:[{id,path}]}
    scope: () => req('GET', ADMIN + '/scope'),

    // {scope, total, organizations:[{id,path,count}]}
    countThumbnails: (all) => req('GET', scoped(ADMIN + '/thumbnails/count', all)),
    cleanupThumbnails: (all) => req('DELETE', scoped(ADMIN + '/thumbnails', all)),

    countAudit: (all) => req('GET', scoped(ADMIN + '/audit/count', all)),
    // filter: {allOrganizations, startRecord, recordCount, action, response, resourceType, subjectType, text, since, until}
    // dates are epoch millis. Returns an io.queryResult: {results:[...], count, totalCount}.
    searchAudit: (filter) => req('POST', ADMIN + '/audit/search', filter || {}),
    // {audit:{...}, organizationPath, contextUser, subject, resource}
    getAudit: (objectId, all) => req('GET', scoped(ADMIN + '/audit/' + encodeURIComponent(objectId), all)),
    // Trim: delete records older than `days` days (days >= 1).
    trimAudit: (all, days) => req('DELETE', scoped(ADMIN + '/audit', all, { days: days })),
    // Purge: delete every record in scope. A separate explicit flag, never "days omitted".
    purgeAudit: (all) => req('DELETE', scoped(ADMIN + '/audit', all, { purge: true }))
};

export default systemAdminClient;
