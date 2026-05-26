// config.js — replaces g_application_path and %AM7_SERVER% token
//
// Defaults:
//   Ux dev server: https://localhost:8899
//   App server:    https://localhost:8443
//
// Some endpoints (e.g. /olio/*) need an absolute URL that points at the app
// server directly — relying on the Vite proxy was producing 404s in some
// deployments. So when no VITE_AM7_SERVER override is provided AND we're
// served from the Vite dev port, default the API base to the app server's
// origin. Production builds set VITE_AM7_SERVER explicitly.
function defaultServer() {
    if (typeof window === 'undefined' || !window.location) return '';
    let loc = window.location;
    // If the page is served from a different host:port than the app server,
    // build an absolute API base so requests don't bounce off the Ux server.
    // Standard pairing: Ux on 8899, app server on 8443 (same host, https).
    if (loc.port === '8899') {
        return loc.protocol + '//' + loc.hostname + ':8443';
    }
    return '';
}

const server = import.meta.env.VITE_AM7_SERVER || defaultServer();
const service = import.meta.env.VITE_AM7_SERVICE || '/AccountManagerService7';
const applicationPath = server + service;

export { applicationPath };
export default { applicationPath };
