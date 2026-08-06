import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import {
    SETUP_SERVERS,
    SETUP_ORGANIZATIONS,
    SETUP_MIN_PASSWORD_LENGTH,
    validateSetupForm,
    buildSetupPayload,
    serversToFields,
    tokenFromUrl,
    writeSetupCache
} from '../core/setupSupport.js';

/// First-run setup page. Modeled on views/sig.js (the reference unauthenticated form page):
/// am7model.models.push -> am7model.forms.<name> -> am7model.newInstance -> inst.action(...).
/// Registered as a CORE route (/setup) in router.js, because lazy feature routes are only
/// loaded when authenticated and would therefore be unreachable here.

const setupPage = {};

am7model.models.push({
    name: "setup",
    icon: "settings",
    label: "First-Run Setup",
    fields: [
        { name: "adminPassword", type: "string", rules: ["$notEmpty"], minLength: SETUP_MIN_PASSWORD_LENGTH },
        { name: "adminPasswordConfirm", type: "string", rules: ["$notEmpty"], minLength: SETUP_MIN_PASSWORD_LENGTH },
        { name: "setupToken", type: "string", rules: ["$notEmpty"] },
        { name: "initialUserName", type: "string" },
        { name: "initialUserPassword", type: "string" },
        { name: "initialUserOrganization", type: "string", default: SETUP_ORGANIZATIONS[0] },
        { name: "serverSd", type: "string" },
        { name: "serverFace", type: "string" },
        { name: "serverTag", type: "string" },
        { name: "serverVoiceTts", type: "string" },
        { name: "serverVoiceStt", type: "string" },
        { name: "serverEmbedding", type: "string" }
    ]
});

am7model.forms.setup = {
    label: "First-Run Setup",
    commands: {
        runSetup: {
            label: 'Complete Setup',
            icon: 'settings',
            action: 'runSetup'
        }
    },
    fields: {
        adminPassword: { layout: "full", label: "Administrator Password", type: "password" },
        adminPasswordConfirm: { layout: "full", label: "Confirm Administrator Password", type: "password" },
        setupToken: { layout: "full", label: "Setup Token", type: "password" },
        initialUserName: { layout: "full", label: "User Name" },
        initialUserPassword: { layout: "full", label: "User Password", type: "password" },
        initialUserOrganization: {
            layout: "full",
            label: "Organization",
            format: "list",
            values: SETUP_ORGANIZATIONS
        },
        serverSd: { layout: "full", label: SETUP_SERVERS[0].label, placeholder: SETUP_SERVERS[0].placeholder },
        serverFace: { layout: "full", label: SETUP_SERVERS[1].label, placeholder: SETUP_SERVERS[1].placeholder },
        serverTag: { layout: "full", label: SETUP_SERVERS[2].label, placeholder: SETUP_SERVERS[2].placeholder },
        serverVoiceTts: { layout: "full", label: SETUP_SERVERS[3].label, placeholder: SETUP_SERVERS[3].placeholder },
        serverVoiceStt: { layout: "full", label: SETUP_SERVERS[4].label, placeholder: SETUP_SERVERS[4].placeholder },
        serverEmbedding: { layout: "full", label: SETUP_SERVERS[5].label, placeholder: SETUP_SERVERS[5].placeholder }
    }
};

let inst = am7model.newInstance("setup", am7model.forms.setup);

let state = {
    checked: false,
    available: undefined,
    submitting: false,
    prefilled: false,
    loadingValues: false,
    message: null
};

function values() {
    return {
        adminPassword: inst.api.adminPassword(),
        adminPasswordConfirm: inst.api.adminPasswordConfirm(),
        setupToken: inst.api.setupToken(),
        initialUserName: inst.api.initialUserName(),
        initialUserPassword: inst.api.initialUserPassword(),
        initialUserOrganization: inst.api.initialUserOrganization(),
        serverSd: inst.api.serverSd(),
        serverFace: inst.api.serverFace(),
        serverTag: inst.api.serverTag(),
        serverVoiceTts: inst.api.serverVoiceTts(),
        serverVoiceStt: inst.api.serverVoiceStt(),
        serverEmbedding: inst.api.serverEmbedding()
    };
}

function clearPasswords() {
    inst.api.adminPassword("");
    inst.api.adminPasswordConfirm("");
    inst.api.initialUserPassword("");
}

async function doSetup() {
    if (state.submitting) return;

    /// Model-level rules first (required / minimum length), then the cross-field and URL
    /// checks that the schema rules can't express.
    let schemaOk = inst.validate();
    let v = validateSetupForm(values());
    Object.keys(v.errors).forEach(k => { inst.validationErrors[k] = v.errors[k]; });
    if (!v.valid || !schemaOk) {
        state.message = null;
        page.toast("warn", "Please correct the highlighted fields");
        m.redraw();
        return;
    }

    state.submitting = true;
    state.message = null;
    m.redraw();

    /// Base64's methods rely on `this`, so let buildSetupPayload use its bound default rather
    /// than passing Base64.encode unbound.
    let payload = buildSetupPayload(values());
    let r = await am7client.runSetup(payload, inst.api.setupToken());
    state.submitting = false;

    if (r && r.ok) {
        let res = r.result || {};
        if (res.warnings && res.warnings.length) {
            res.warnings.forEach(w => page.toast("warn", w));
        }
        clearPasswords();
        inst.api.setupToken("");
        writeSetupCache(typeof sessionStorage !== "undefined" ? sessionStorage : null);
        page.toast("success", "Setup complete" + (res.initialUser ? (" — created user " + res.initialUser) : ""));
        m.route.set("/sig");
        return;
    }

    if (r && r.unavailable) {
        /// The server answers 404 for all three causes below without distinguishing them. That
        /// ambiguity is deliberate (no oracle for an unauthenticated endpoint) — do not try to
        /// work out which one it was client-side.
        state.message = "Setup is unavailable. The server does not say which of these applies, by design:"
            + "\n • setup has already been completed on this deployment"
            + "\n • the setup token is wrong"
            + "\n • too many bad token attempts, so setup is temporarily locked out"
            + "\nCheck the container log and the deployment's setup token file, then try again.";
        page.toast("error", "Setup is unavailable");
    }
    else {
        state.message = "Setup failed" + (r && r.message ? (": " + r.message) : ". See the browser console and server log.");
        page.toast("error", "Setup failed");
    }
    m.redraw();
}

inst.action("runSetup", doSetup);

/// --- Field rendering -------------------------------------------------------------------
/// Designers replace the default field view, so each helper re-renders label + input + the
/// inline validation error (same shape as am7view.fieldView) and adds section headings /
/// hints / warnings.

function fieldBlock(i, fld, hint, extra) {
    return m("div", { class: "mb-3" }, [
        m("label", { class: "field-label", for: fld }, i.label(fld)),
        am7view.field(fld, i),
        i.validationErrors[fld] ? am7view.errorLabel(i.validationErrors[fld]) : null,
        hint ? m("div", { class: "text-xs text-gray-500 dark:text-gray-400 mt-1" }, hint) : null,
        extra || null
    ]);
}

function heading(title, sub) {
    return m("div", { class: "mt-4 mb-2 pt-3 border-t border-gray-200 dark:border-gray-700" }, [
        m("div", { class: "font-semibold" }, title),
        sub ? m("div", { class: "text-xs text-gray-500 dark:text-gray-400 mt-1" }, sub) : null
    ]);
}

function warning(lines) {
    return m("div", {
        class: "mt-2 p-2 rounded text-xs bg-yellow-200 text-black dark:bg-yellow-800 dark:text-white"
    }, lines.map(l => m("div", { class: "mb-1" }, l)));
}

inst.designer("adminPassword", function (i) {
    return m("div", [
        heading("Administrator", "Sets the password for the built-in administrator of this deployment."),
        fieldBlock(i, "adminPassword", "At least " + SETUP_MIN_PASSWORD_LENGTH + " characters."),
        warning([
            "The password is sent base64-encoded, matching the existing sign-in call. Base64 is transport encoding, not encryption — TLS is the only protection on this request."
        ])
    ]);
});

inst.designer("adminPasswordConfirm", function (i) {
    return fieldBlock(i, "adminPasswordConfirm");
});

inst.designer("setupToken", function (i) {
    return m("div", [
        heading("Setup Token",
            "Required. Supplied by this deployment (token file / container log); it can also be passed in the "
            + "URL as ?token=<token>, in which case it is filled in here. The same token is what releases the "
            + "current server URLs below for prefill."),
        fieldBlock(i, "setupToken")
    ]);
});

inst.designer("initialUserName", function (i) {
    return m("div", [
        heading("Initial User (optional)", "Leave the name and password empty to skip creating a user. Do not use the administrator account for day-to-day work."),
        fieldBlock(i, "initialUserName")
    ]);
});

inst.designer("initialUserPassword", function (i) {
    return fieldBlock(i, "initialUserPassword", "At least " + SETUP_MIN_PASSWORD_LENGTH + " characters.");
});

inst.designer("initialUserOrganization", function (i) {
    return fieldBlock(i, "initialUserOrganization");
});

inst.designer("serverSd", function (i) {
    return m("div", [
        heading("Media & AI Servers (optional)",
            "Leave a field empty to keep this deployment's current value. "
            + "The current values are only shown when a valid setup token is supplied, so these fields "
            + "may start empty — that is normal, not an error. They are editable later under System "
            + "Connections (#!/list/system.connection). A saved voice or embedding URL can take up to "
            + "about 30 seconds (one cache expiry) to take effect in the running server."),
        m("div", { class: "mb-2" }, [
            m("button", {
                class: "btn btn-secondary text-sm",
                disabled: state.loadingValues,
                onclick: loadServerValues
            }, state.loadingValues ? "Loading ..." : "Load current values"),
            m("span", { class: "text-xs text-gray-500 dark:text-gray-400 ml-2" },
                state.prefilled
                    ? "Showing the values reported by the server."
                    : "Uses the setup token above; leave the fields empty or type the URLs manually if nothing loads.")
        ]),
        fieldBlock(i, "serverSd", SETUP_SERVERS[0].param)
    ]);
});

inst.designer("serverFace", function (i) { return fieldBlock(i, "serverFace", SETUP_SERVERS[1].param); });
inst.designer("serverTag", function (i) { return fieldBlock(i, "serverTag", SETUP_SERVERS[2].param); });
inst.designer("serverVoiceTts", function (i) { return fieldBlock(i, "serverVoiceTts", SETUP_SERVERS[3].param); });
inst.designer("serverVoiceStt", function (i) { return fieldBlock(i, "serverVoiceStt", SETUP_SERVERS[4].param); });

inst.designer("serverEmbedding", function (i) {
    return fieldBlock(i, "serverEmbedding", SETUP_SERVERS[5].param, warning([
        "Only the embedding server URL is configurable here. The embedding type and vector dimensions "
        + "are pinned at server boot from web.xml (embedding.type, embedding.dimensions) and cannot be changed on this page.",
        "Changing the embedding URL after any content has been ingested invalidates the vectors that already exist: "
        + "there is no record of which embedding model produced a stored vector, so old and new vectors cannot be told "
        + "apart and cannot be selectively re-indexed. Existing vector search results will be wrong until every "
        + "embedded record is re-ingested. Set this correctly now, before ingesting content."
    ]));
});

/// Submit on Enter from the confirm field, matching sig.js's password behavior.
inst.viewProperties("adminPasswordConfirm", { onkeydown: function (e) { if (e.which == 13) doSetup(); } });
inst.viewProperties("initialUserName", { autocapitalize: "off" });

/// --- State probe -----------------------------------------------------------------------

function applyServers(servers) {
    let pre = serversToFields(servers);
    let keys = Object.keys(pre);
    keys.forEach(k => { if (inst.api[k]) inst.api[k](pre[k]); });
    return keys.length > 0;
}

async function checkState() {
    /// Token first: GET /rest/setup/state only returns the boot `servers` block when a valid
    /// X-AM7-Setup-Token is supplied, so the URL token (if any) has to be read before probing.
    let tok = tokenFromUrl(
        (typeof window !== "undefined" && window.location ? window.location.search : null),
        (typeof window !== "undefined" && window.location ? window.location.hash : null)
    );
    if (tok) {
        inst.api.setupToken(tok);
    }

    let st = null;
    try {
        st = await am7client.setupState(tok);
    } catch (e) {
        st = null;
    }
    state.checked = true;
    if (!st || st.initialized !== false) {
        /// Already initialized (or the endpoint isn't there): setup is not available.
        state.available = false;
        m.route.set("/sig");
        return;
    }
    state.available = true;
    /// `servers` is absent whenever no token (or a token the server didn't accept) was sent.
    /// That is a normal, non-error state: the fields simply start empty with placeholders.
    /// A wrong token and no token are indistinguishable here, so nothing is claimed about it.
    state.prefilled = applyServers(st.servers);
    inst.resetChanges();
    m.redraw();
}

/// Re-read the state with whatever token is currently in the Setup Token field, to pull the
/// deployment's current server URLs into the empty fields. This is the same read-only state
/// call as the initial probe — it does not count against the setup lockout.
async function loadServerValues() {
    if (state.loadingValues) return;
    let tok = inst.api.setupToken();
    state.loadingValues = true;
    state.message = null;
    m.redraw();
    let st = null;
    try {
        st = await am7client.setupState(tok);
    } catch (e) {
        st = null;
    }
    state.loadingValues = false;
    if (st && st.servers && applyServers(st.servers)) {
        state.prefilled = true;
        page.toast("info", "Loaded the current server URLs");
    }
    else {
        state.prefilled = false;
        page.toast("warn", "No server URLs were returned. The current values are only released when a valid setup token is supplied; enter the URLs manually if needed.");
    }
    m.redraw();
}

setupPage.view = {
    oninit: function () {
        state.checked = false;
        state.available = undefined;
        state.message = null;
        state.prefilled = false;
        state.loadingValues = false;
        state.submitting = false;
        checkState();
    },
    view: function () {
        if (!state.checked) {
            return m("div", { class: "screen-center-gray" }, [
                m("div", { class: "box-shadow-white" }, "Checking setup state ...")
            ]);
        }
        if (state.available === false) {
            return m("div", { class: "screen-center-gray" }, [
                m("div", { class: "box-shadow-white" }, "Setup is not available. Redirecting to sign-in ...")
            ]);
        }
        return m("div", { class: "screen-center-gray" }, [
            m("div", { class: "box-shadow-white" }, [
                am7view.form(inst),
                state.submitting ? m("div", { class: "mt-2 text-sm" }, "Applying setup ...") : null,
                state.message ? m("div", {
                    class: "mt-2 p-2 rounded text-sm whitespace-pre-line bg-red-200 text-black dark:bg-red-700 dark:text-white"
                }, state.message) : null,
                m("div", { class: "mt-3 pt-3 border-t border-gray-200 dark:border-gray-700 text-center" }, [
                    m("a", {
                        class: "text-sm underline cursor-pointer",
                        onclick: function () { m.route.set("/sig"); }
                    }, "Already configured? Go to sign-in")
                ])
            ])
        ]);
    }
};

page.views.setup = setupPage.view;

export default setupPage.view;
