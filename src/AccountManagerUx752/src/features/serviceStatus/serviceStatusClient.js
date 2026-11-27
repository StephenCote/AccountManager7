/**
 * Service Status feature-local REST client — thin wrapper over /rest/system/check (SystemCheckService,
 * aiDocs/ServiceStatusDesign.md §7). Same transport as systemAdminClient: m.request + session cookie,
 * background (the view redraws itself). The server enforces @RolesAllowed admin on everything except
 * /summary and re-checks the role in-process (AdminMaintenanceUtil.requireAdministrator).
 *
 * The client never sends a URL: the only inputs are a connection objectId, a deployment check name
 * from the server's fixed whitelist, and the `deep` boolean (design §2).
 *
 * These calls deliberately go through m.request, NOT am7client: am7client's 401 handler calls
 * forceLogin, and the degraded banner's /summary fetch must fail silently (design §8).
 */
import m from 'mithril';
import { applicationPath } from '../../core/config.js';

const CHECK = applicationPath + '/rest/system/check';

function req(method, url) {
    return m.request({ method: method, url: url, withCredentials: true, background: true });
}

function deepQs(deep) {
    return '?deep=' + (deep ? 'true' : 'false');
}

export const serviceStatusClient = {
    // {status, degraded:[names], checkedAt} — open to role user; names only, no URLs.
    summary: () => req('GET', CHECK + '/summary'),
    // The last report for the caller (admin).
    last: () => req('GET', CHECK),
    // A forced full run (admin). 409 while a deep run is already in progress.
    run: (deep) => req('POST', CHECK + deepQs(deep)),
    // Readable connections, without probing (admin).
    connections: () => req('GET', CHECK + '/connections'),
    // Test one connection (admin). 404 when it is not readable by the caller.
    testConnection: (objectId, deep) => req('POST', CHECK + '/connection/' + encodeURIComponent(objectId) + deepQs(deep)),
    // Test one deployment check (admin). 400 for a name outside the server whitelist.
    testServer: (name, deep) => req('POST', CHECK + '/server/' + encodeURIComponent(name) + deepQs(deep))
};

export default serviceStatusClient;
