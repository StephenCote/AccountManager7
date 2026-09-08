/**
 * Olio admin feature-local REST client — thin wrapper over the Deliverable-B corpus-provisioning
 * endpoint. Mirrors iso42001Client's transport (m.request + session cookie; the server enforces
 * @RolesAllowed admin). The org is derived server-side from the principal, so nothing org-related is
 * sent; the only optional payload is { includeLocations }.
 */
import m from 'mithril';
import { applicationPath } from '../../core/config.js';

const REST = applicationPath + '/rest';
const OLIO = REST + '/olio';

function req(method, url, body) {
    let opts = { method: method, url: url, withCredentials: true, background: true };
    if (body !== undefined) {
        opts.body = body;
    }
    return m.request(opts);
}

export const olioAdminClient = {
    // Synchronous, potentially slow corpus load into the CURRENT org (org derived server-side).
    // includeLocations defaults false; the large location corpus is opt-in.
    loadData: (includeLocations) => req('POST', OLIO + '/loadData', { includeLocations: !!includeLocations })
};

export default olioAdminClient;
