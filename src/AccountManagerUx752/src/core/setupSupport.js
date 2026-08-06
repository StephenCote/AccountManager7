import Base64 from './base64.js';

/// setupSupport.js — pure helpers for the first-run setup page (views/setup.js) and the
/// unauthenticated routing decision in router.js.
///
/// Everything here is dependency-injected (storage, probe, encoder) so it can be unit tested
/// without a DOM, a router, or a live server.

/// The six media/AI servers the setup page can seed. `field` is the client-side model field
/// name (dots are not usable in model field names); `key` is the wire key expected by
/// POST /rest/setup/; `param` is the web.xml context-param the value lands in, shown as a
/// hint so an operator can correlate the form with the deployment descriptor.
/// `placeholder` is shown when a field is empty — which is the normal case when the state probe
/// could not return the boot values (no valid setup token in the URL). Deliberately generic:
/// no internal host names.
const SETUP_SERVERS = [
    { field: "serverSd", key: "sd", label: "Stable Diffusion Server", param: "sd.server", placeholder: "http://host:7801" },
    { field: "serverFace", key: "face", label: "Face Server", param: "face.server", placeholder: "http://host:8003" },
    { field: "serverTag", key: "tag", label: "Tag Server", param: "tag.server", placeholder: "http://host:8000" },
    { field: "serverVoiceTts", key: "voice.tts", label: "Voice Server (TTS)", param: "voice.tts.server", placeholder: "http://host:8001" },
    { field: "serverVoiceStt", key: "voice.stt", label: "Voice Server (STT)", param: "voice.stt.server", placeholder: "http://host:8002" },
    { field: "serverEmbedding", key: "embedding", label: "Embedding Server", param: "embedding.server", placeholder: "http://host:8123" }
];

const SETUP_ORGANIZATIONS = ["/Public", "/Development"];

const SETUP_MIN_PASSWORD_LENGTH = 8;

/// sessionStorage key holding the cached "setup is not needed" result, so the state probe is
/// not a round trip on every unauthenticated page load. Only a definitive
/// initialized === true is cached — a failed/absent probe is deliberately NOT cached, because
/// that can be transient (server still booting) and caching it would hide the setup page for
/// the rest of the browser session.
const SETUP_CACHE_KEY = "am7.setupComplete";

const SERVER_URL_PATTERN = /^https?:\/\/\S+$/i;

function isBlank(v) {
    return (v === undefined || v === null || ("" + v).trim().length === 0);
}

/// Decide which unauthenticated route to show given the (possibly missing) /rest/setup/state
/// response. Only an explicit initialized === false sends the operator to /setup; anything
/// ambiguous (null, undefined, error shape, initialized === true) falls back to /sig.
function decideUnauthenticatedRoute(setupState) {
    if (setupState && setupState.initialized === false) {
        return "/setup";
    }
    return "/sig";
}

function readSetupCache(storage) {
    if (!storage) return null;
    try {
        return (storage.getItem(SETUP_CACHE_KEY) === "1");
    } catch (e) {
        return null;
    }
}

function writeSetupCache(storage) {
    if (!storage) return;
    try {
        storage.setItem(SETUP_CACHE_KEY, "1");
    } catch (e) { /* sessionStorage may be unavailable */ }
}

function clearSetupCache(storage) {
    if (!storage) return;
    try {
        storage.removeItem(SETUP_CACHE_KEY);
    } catch (e) { /* sessionStorage may be unavailable */ }
}

/// Resolve the unauthenticated route, probing /rest/setup/state at most once per browser
/// session. `probe` is an async function returning the state object (or null).
async function resolveUnauthenticatedRoute(probe, storage) {
    if (readSetupCache(storage) === true) {
        return "/sig";
    }
    let state = null;
    try {
        state = await probe();
    } catch (e) {
        state = null;
    }
    let route = decideUnauthenticatedRoute(state);
    if (state && state.initialized === true) {
        writeSetupCache(storage);
    }
    return route;
}

function validateServerUrl(url) {
    if (isBlank(url)) {
        /// Server URLs are optional — an empty field simply leaves the boot value alone.
        return { valid: true };
    }
    if (!SERVER_URL_PATTERN.test(("" + url).trim())) {
        return { valid: false, error: "Must be an http:// or https:// URL" };
    }
    return { valid: true };
}

function validatePassword(pw) {
    if (isBlank(pw)) {
        return { valid: false, error: "Field is required" };
    }
    if (("" + pw).length < SETUP_MIN_PASSWORD_LENGTH) {
        return { valid: false, error: "Must be at least " + SETUP_MIN_PASSWORD_LENGTH + " characters" };
    }
    return { valid: true };
}

/// Validate the whole setup form. `values` is a flat map keyed by model field name.
/// Returns { valid, errors } where errors is keyed by model field name so the caller can
/// drop it straight into inst.validationErrors.
function validateSetupForm(values) {
    let v = values || {};
    let errors = {};

    let pw = validatePassword(v.adminPassword);
    if (!pw.valid) errors.adminPassword = pw.error;

    if (isBlank(v.adminPasswordConfirm)) {
        errors.adminPasswordConfirm = "Field is required";
    } else if (v.adminPassword !== v.adminPasswordConfirm) {
        errors.adminPasswordConfirm = "Passwords do not match";
    }

    if (isBlank(v.setupToken)) {
        errors.setupToken = "Field is required";
    }

    let wantsUser = !isBlank(v.initialUserName) || !isBlank(v.initialUserPassword);
    if (wantsUser) {
        if (isBlank(v.initialUserName)) {
            errors.initialUserName = "Field is required to create a user";
        }
        let upw = validatePassword(v.initialUserPassword);
        if (!upw.valid) errors.initialUserPassword = upw.error;
        if (isBlank(v.initialUserOrganization) || !SETUP_ORGANIZATIONS.includes(v.initialUserOrganization)) {
            errors.initialUserOrganization = "Select an organization";
        }
    }

    SETUP_SERVERS.forEach(s => {
        let r = validateServerUrl(v[s.field]);
        if (!r.valid) errors[s.field] = r.error;
    });

    return { valid: (Object.keys(errors).length === 0), errors };
}

/// Build the POST /rest/setup/ body. `encode` is an optional base64 encoder override (the
/// default delegates to Base64.encode — note Base64's methods use `this`, so they must not be
/// passed unbound). The credential convention matches /rest/login: base64 is transport
/// encoding, NOT encryption. TLS is the only protection.
function buildSetupPayload(values, encode) {
    let v = values || {};
    let enc = encode || function (s) { return Base64.encode(s); };
    let payload = { credential: enc(v.adminPassword) };

    if (!isBlank(v.initialUserName) && !isBlank(v.initialUserPassword)) {
        payload.initialUser = {
            name: ("" + v.initialUserName).trim(),
            credential: enc(v.initialUserPassword),
            organization: (isBlank(v.initialUserOrganization) ? SETUP_ORGANIZATIONS[0] : v.initialUserOrganization)
        };
    }

    let servers = {};
    SETUP_SERVERS.forEach(s => {
        if (!isBlank(v[s.field])) {
            servers[s.key] = ("" + v[s.field]).trim();
        }
    });
    if (Object.keys(servers).length) {
        payload.servers = servers;
    }
    return payload;
}

/// Map the `servers` block of GET /rest/setup/state onto model field names for prefill.
function serversToFields(servers) {
    let out = {};
    if (!servers) return out;
    SETUP_SERVERS.forEach(s => {
        let v = servers[s.key];
        if (typeof v === "string" && v.trim().length) {
            out[s.field] = v.trim();
        }
    });
    return out;
}

/// Pull the setup token out of a URL. Both the query string and the hash-route query are
/// checked, because the app is hash-routed (#!/setup?token=...) and an operator may paste
/// either form.
function tokenFromUrl(search, hash) {
    let read = function (s) {
        if (!s) return null;
        let q = s.indexOf("?");
        let qs = (q >= 0 ? s.substring(q + 1) : (s.charAt(0) === "?" ? s.substring(1) : null));
        if (qs === null) return null;
        let params = new URLSearchParams(qs);
        let t = params.get("token");
        return (t && t.length ? t : null);
    };
    return read(search) || read(hash) || null;
}

export {
    SETUP_SERVERS,
    SETUP_ORGANIZATIONS,
    SETUP_MIN_PASSWORD_LENGTH,
    SETUP_CACHE_KEY,
    decideUnauthenticatedRoute,
    resolveUnauthenticatedRoute,
    readSetupCache,
    writeSetupCache,
    clearSetupCache,
    validateServerUrl,
    validatePassword,
    validateSetupForm,
    buildSetupPayload,
    serversToFields,
    tokenFromUrl
};
