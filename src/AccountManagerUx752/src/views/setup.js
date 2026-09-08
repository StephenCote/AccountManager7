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
/// The feature manifest module (client-only wiring + the client `profiles` catalogue). The setup
/// wizard reuses its `profiles` table and the pure `resolveFeatures` dependency-closure resolver
/// rather than keeping a second copy of the profile->ids mapping. Feature flags are a UX-visibility
/// mechanism, not an authorization boundary.
import { features as featureCatalog, profiles, resolveFeatures } from '../features.js';

/// First-run setup page. Modeled on views/sig.js (the reference unauthenticated form page):
/// am7model.models.push -> am7model.forms.<name> -> am7model.newInstance -> inst.action(...).
/// Registered as a CORE route (/setup) in router.js, because lazy feature routes are only
/// loaded when authenticated and would therefore be unreachable here.
///
/// The page is a multi-step wizard (see `wizardSteps` below). It reuses the model/form system
/// (am7model + per-field designers + am7view.field) exactly as the single-page form did; the
/// only difference is that the fields are rendered a step at a time instead of all at once.
/// The step list is an ordered array of { title, fields, render, validate } definitions so that
/// inserting another step later (a "Features" step is planned before Review) is a one-line change.

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
        /// Optional-user confirm: NO schema rules, mirroring initialUserPassword. The "matches"
        /// requirement is conditional (only when a user is being created), which the schema rule
        /// system cannot express — it is enforced in setupSupport.validateSetupForm instead.
        { name: "initialUserPasswordConfirm", type: "string" },
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
        initialUserPasswordConfirm: { layout: "full", label: "Confirm User Password", type: "password" },
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
    message: null,
    step: 0,
    /// Default to the bare-minimum profile: setup starts from the smallest legal set (core only)
    /// and the admin opts in to more. This is the explicit ask — previously setup enabled everything.
    featureProfile: "minimal"
};

function values() {
    return {
        adminPassword: inst.api.adminPassword(),
        adminPasswordConfirm: inst.api.adminPasswordConfirm(),
        setupToken: inst.api.setupToken(),
        initialUserName: inst.api.initialUserName(),
        initialUserPassword: inst.api.initialUserPassword(),
        initialUserPasswordConfirm: inst.api.initialUserPasswordConfirm(),
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
    inst.api.initialUserPasswordConfirm("");
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
        /// In the wizard the offending field may be on a step other than Review, so land the
        /// operator on the first step that carries a highlighted field.
        let bad = wizardSteps.findIndex(s => (s.fields || []).some(f => inst.validationErrors[f]));
        if (bad >= 0) state.step = bad;
        page.toast("warn", "Please correct the highlighted fields");
        m.redraw();
        return;
    }

    state.submitting = true;
    state.message = null;
    m.redraw();

    /// Resolve the chosen starting profile to its full feature-id list (core + transitive deps
    /// force-included) via the manifest module's pure resolver, and hand it to buildSetupPayload so
    /// the payload carries `features: [...ids]`. Base64's methods rely on `this`, so let
    /// buildSetupPayload use its bound default rather than passing Base64.encode unbound.
    let featureIds = resolveFeatures(state.featureProfile);
    let payload = buildSetupPayload(values(), featureIds);
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

inst.designer("initialUserPasswordConfirm", function (i) {
    return fieldBlock(i, "initialUserPasswordConfirm");
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

/// Enter in a password/confirm field advances to the next step rather than submitting (this is
/// a wizard now; submit only happens from the terminal Review step).
inst.viewProperties("adminPasswordConfirm", { onkeydown: function (e) { if (e.which == 13) nextStep(); } });
inst.viewProperties("initialUserPasswordConfirm", { onkeydown: function (e) { if (e.which == 13) nextStep(); } });
inst.viewProperties("initialUserName", { autocapitalize: "off" });

/// --- Wizard step machinery -------------------------------------------------------------

/// Render a step's fields by reusing the registered per-field designers (falling back to the
/// default field view). This is the same rendering am7view.form uses; the wizard just renders a
/// subset per step.
function renderStepFields(fields) {
    return fields.map(f => inst.design(f) || am7view.fieldView(f, inst));
}

/// Validate ONLY the given step's fields: schema rules first (which set/clear
/// inst.validationErrors per field), then the cross-field / conditional checks from
/// validateSetupForm, applied only to this step's fields. Returns true when the step is clean.
function validateStepFields(fields) {
    let ok = true;
    fields.forEach(f => {
        if (inst.validateField[f] && inst.validateField[f]() === false) ok = false;
    });
    let v = validateSetupForm(values());
    fields.forEach(f => {
        if (v.errors[f]) { inst.validationErrors[f] = v.errors[f]; ok = false; }
    });
    return ok;
}

function summaryRow(k, val) {
    return m("div", { class: "flex justify-between text-sm gap-4" }, [
        m("span", { class: "text-gray-500 dark:text-gray-400" }, k),
        m("span", { class: "text-right break-all" }, val)
    ]);
}

/// --- Features step ---------------------------------------------------------------------
/// Presentation-only copy for the profile selector. This is UI text, NOT a second profile->ids
/// table — the actual id lists come from the manifest module (resolveFeatures). The bare-minimum
/// and compliance ("ISO 42001 only") profiles are the two the operator was told to expect, so they
/// carry the most explicit copy; any other profile the module defines still renders with a generic
/// hint and its resolved feature list shown beneath it.
const PROFILE_HINTS = {
    minimal: "Bare minimum — core object management, navigation, forms and lists only. Recommended default; switch more on later under Feature Configuration.",
    compliance: "ISO 42001 only — the compliance appliance surface (adds chat, access requests and feature config to support it).",
    standard: "Core plus media processing and LLM chat.",
    enterprise: "Compliance, schema tools, passkeys and access management on top of the standard set.",
    full: "Everything available in this build.",
    gaming: "Core plus media, chat, the card game, mini games and biometrics."
};

/// Show minimal first (the default) and compliance second (the motivating example); any profile the
/// manifest module defines that is not listed here is appended so the module stays the source of truth.
const PROFILE_DISPLAY_ORDER = ["minimal", "compliance", "standard", "enterprise", "full", "gaming"];

function orderedProfileNames() {
    let names = Object.keys(profiles);
    let ordered = PROFILE_DISPLAY_ORDER.filter(n => names.includes(n));
    names.forEach(n => { if (!ordered.includes(n)) ordered.push(n); });
    return ordered;
}

function profileLabel(name) {
    return name.charAt(0).toUpperCase() + name.slice(1);
}

/// Map resolved feature ids to their human labels via the manifest catalogue (fall back to the id).
function featureLabels(ids) {
    return ids.map(id => (featureCatalog[id] && featureCatalog[id].label) ? featureCatalog[id].label : id);
}

function renderFeatures() {
    return m("div", [
        heading("Features",
            "Choose the feature set this deployment starts with. This only controls which parts of the "
            + "UI are switched on — it is not an access-control or security boundary — and every choice can "
            + "be changed later under Feature Configuration. The bare-minimum set is selected by default."),
        m("div", { class: "flex flex-col gap-2 mt-2" },
            orderedProfileNames().map(function (name) {
                let ids = resolveFeatures(name);
                let selected = state.featureProfile === name;
                return m("label", {
                    key: name,
                    class: "block rounded p-3 cursor-pointer border "
                        + (selected
                            ? "border-blue-500 bg-blue-50/50 dark:bg-blue-900/20"
                            : "border-gray-200 dark:border-gray-700")
                }, [
                    m("div", { class: "flex items-center gap-2" }, [
                        m("input", {
                            type: "radio",
                            name: "featureProfile",
                            value: name,
                            checked: selected,
                            onchange: function () { state.featureProfile = name; }
                        }),
                        m("span", { class: "font-medium" }, profileLabel(name)),
                        m("span", { class: "text-xs text-gray-400 dark:text-gray-500 font-mono ml-auto" },
                            ids.length + (ids.length === 1 ? " feature" : " features"))
                    ]),
                    PROFILE_HINTS[name]
                        ? m("div", { class: "text-xs text-gray-500 dark:text-gray-400 mt-1 ml-6" }, PROFILE_HINTS[name])
                        : null,
                    m("div", { class: "text-xs text-gray-400 dark:text-gray-500 mt-1 ml-6" },
                        featureLabels(ids).join(", "))
                ]);
            }))
    ]);
}

/// Terminal step: a read-only summary of the operator's choices plus the submit status/error
/// blocks. The Complete Setup button itself lives in the wizard nav bar.
function renderReview() {
    let v = values();
    let wantsUser = (v.initialUserName && ("" + v.initialUserName).trim().length)
        || (v.initialUserPassword && ("" + v.initialUserPassword).length);
    let serverRows = SETUP_SERVERS
        .filter(s => v[s.field] && ("" + v[s.field]).trim().length)
        .map(s => summaryRow(s.label, ("" + v[s.field]).trim()));
    let featureIds = resolveFeatures(state.featureProfile);
    return m("div", [
        heading("Review & Complete Setup",
            "Confirm the choices below, then complete setup. Go Back to change anything."),
        m("div", { class: "rounded p-3 bg-gray-100 dark:bg-gray-800 flex flex-col gap-1" }, [
            summaryRow("Administrator password", v.adminPassword ? "Set" : "Not set"),
            summaryRow("Setup token", v.setupToken ? "Provided" : "Not provided"),
            summaryRow("Initial user", wantsUser
                ? (("" + (v.initialUserName || "")).trim() + " (" + v.initialUserOrganization + ")")
                : "None — skipped"),
            summaryRow("Feature set", profileLabel(state.featureProfile) + " — " + featureLabels(featureIds).join(", ")),
            serverRows.length
                ? m("div", { class: "mt-2 pt-2 border-t border-gray-200 dark:border-gray-700 flex flex-col gap-1" }, [
                    m("div", { class: "text-xs text-gray-500 dark:text-gray-400" }, "Media & AI servers"),
                    serverRows
                ])
                : summaryRow("Media & AI servers", "Using deployment defaults")
        ]),
        state.submitting ? m("div", { class: "mt-2 text-sm" }, "Applying setup ...") : null,
        state.message ? m("div", {
            class: "mt-2 p-2 rounded text-sm whitespace-pre-line bg-red-200 text-black dark:bg-red-700 dark:text-white"
        }, state.message) : null
    ]);
}

/// Ordered step definitions. Adding a step (e.g. an upcoming "Features" step before Review) is
/// a single entry here — the stepper below iterates this array and needs no other change.
const wizardSteps = [
    {
        title: "Administrator",
        fields: ["adminPassword", "adminPasswordConfirm", "setupToken"],
        render: function () { return renderStepFields(this.fields); },
        validate: function () { return validateStepFields(this.fields); }
    },
    {
        title: "Initial User (optional)",
        fields: ["initialUserName", "initialUserPassword", "initialUserPasswordConfirm", "initialUserOrganization"],
        render: function () { return renderStepFields(this.fields); },
        validate: function () { return validateStepFields(this.fields); }
    },
    {
        title: "Media & AI Servers (optional)",
        fields: ["serverSd", "serverFace", "serverTag", "serverVoiceTts", "serverVoiceStt", "serverEmbedding"],
        render: function () { return renderStepFields(this.fields); },
        validate: function () { return validateStepFields(this.fields); }
    },
    {
        /// Features step: pick the starting UX feature set. It has no am7model fields (the selector
        /// is a custom radio group over the manifest's `profiles`), so `fields` is empty and
        /// `validate` always passes — a profile is always selected (defaults to minimal), so this
        /// step can never block submit.
        title: "Features",
        fields: [],
        render: function () { return renderFeatures(); },
        validate: function () { return true; }
    },
    {
        title: "Review & Complete Setup",
        fields: [],
        render: function () { return renderReview(); }
        /// No validate: this terminal step submits via doSetup, which runs the FULL validation
        /// (inst.validate + validateSetupForm) exactly as the single-page form did.
    }
];

function nextStep() {
    let step = wizardSteps[state.step];
    if (step.validate && !step.validate()) {
        state.message = null;
        page.toast("warn", "Please correct the highlighted fields");
        m.redraw();
        return;
    }
    if (state.step < wizardSteps.length - 1) state.step++;
    m.redraw();
}

function prevStep() {
    /// Back never validates.
    if (state.step > 0) state.step--;
    m.redraw();
}

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

/// Progress indicator: "Step X of Y" plus a dot per step (filled up to the current one).
function stepIndicator() {
    return m("div", { class: "mt-2 flex items-center gap-2" }, [
        m("span", { class: "text-xs text-gray-500 dark:text-gray-400" },
            "Step " + (state.step + 1) + " of " + wizardSteps.length),
        m("div", { class: "flex gap-1 ml-auto" },
            wizardSteps.map((s, i) => m("span", {
                key: i,
                title: s.title,
                class: "inline-block w-2.5 h-2.5 rounded-full "
                    + (i === state.step
                        ? "bg-blue-500"
                        : (i < state.step ? "bg-blue-300 dark:bg-blue-700" : "bg-gray-300 dark:bg-gray-600"))
            })))
    ]);
}

setupPage.view = {
    oninit: function () {
        state.checked = false;
        state.available = undefined;
        state.message = null;
        state.prefilled = false;
        state.loadingValues = false;
        state.submitting = false;
        state.step = 0;
        state.featureProfile = "minimal";
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
        let step = wizardSteps[state.step];
        let isFirst = state.step === 0;
        let isLast = state.step === wizardSteps.length - 1;
        return m("div", { class: "screen-center-gray" }, [
            m("div", { class: "box-shadow-white" }, [
                m("h3", { class: "box-title" }, [
                    m("span", { class: "material-symbols-outlined mr-4" }, "settings"),
                    m("span", {}, "First-Run Setup")
                ]),
                stepIndicator(),
                m("div", { class: "mt-4" }, step.render()),
                m("div", {
                    class: "mt-4 pt-3 border-t border-gray-200 dark:border-gray-700 flex justify-between items-center"
                }, [
                    !isFirst
                        ? m("button", {
                            class: "btn btn-secondary text-sm",
                            disabled: state.submitting,
                            onclick: prevStep
                        }, "Back")
                        : m("span"),
                    isLast
                        ? m("button", {
                            class: "btn btn-primary text-sm",
                            disabled: state.submitting,
                            onclick: doSetup
                        }, [
                            m("span", { class: "material-symbols-outlined md-18 mr-4" }, "settings"),
                            m("span", {}, state.submitting ? "Applying ..." : "Complete Setup")
                        ])
                        : m("button", {
                            class: "btn btn-primary text-sm",
                            onclick: nextStep
                        }, "Next")
                ]),
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
