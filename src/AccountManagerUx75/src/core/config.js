// config.js — replaces g_application_path and %AM7_SERVER% token
// Dev: VITE_AM7_SERVER is empty, relative URLs work via Vite proxy
// Production: set VITE_AM7_SERVER to backend URL (e.g., https://production.example.com)
const server = import.meta.env.VITE_AM7_SERVER || '';
const service = import.meta.env.VITE_AM7_SERVICE || '/AccountManagerService7';
const applicationPath = server + service;

export { applicationPath };
export default { applicationPath };
