import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { uwm, am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';

const signInPage = {};

am7model.models.push({
    name: "login",
    icon: "login",
    label: "Login",
    fields: [
        { name: "organization", default: "/Public", type: "string" },
        { name: "userName", type: "string", "rules": ["$notEmpty"] },
        { name: "password", type: "string", "rules": ["$notEmpty"] },
        { name: "specifyOrganizationPath", type: "string" }
    ]
});

am7model.forms.login = {
    label: "Login",
    commands: {
        login: {
            label: 'Login',
            icon: 'login',
            action: 'login'
        }
    },
    fields: {
        organization: {
            layout: "full",
            format: "list",
            values: ["/Public", "/Development", "/System"]
        },
        userName: {
            layout: "full",
            label: "Name"
        },
        password: {
            layout: "full",
            label: "Password",
            type: "password"
        }
    }
};

let inst = am7model.newInstance("login", am7model.forms.login);
inst.action("login", doLogin);

function doLogin() {
    if (!inst.validate()) {
        page.toast("warn", "Please fill in all fields");
        return;
    }
    uwm.login(inst.api.organization(), inst.api.userName(), inst.api.password(), 0, function (s) {
        if (s == null) {
            page.toast("error", "Login was unsuccessful");
        } else {
            page.toast("info", "Logged in as " + s.name);
            page.router.refresh();
        }
    });
    inst.api.password("");
}

let passkeyLoading = false;

async function doPasskeyLogin() {
    if (passkeyLoading) return;
    if (!am7client.webauthnSupported()) {
        page.toast("error", "WebAuthn is not supported in this browser");
        return;
    }
    passkeyLoading = true;
    m.redraw();

    let org = inst.api.organization();
    let userName = inst.api.userName();
    if (!userName) {
        page.toast("warn", "Enter your username first");
        passkeyLoading = false;
        m.redraw();
        return;
    }

    try {
        let result = await am7client.webauthnAuthenticate(org, userName);
        if (result && result.response === "AUTHENTICATED" && result.tokens && result.tokens.length > 0) {
            am7client.currentOrganization = org;
            am7client.clearCache(0, 1);
            let oU2 = await uwm.getUser();
            if (oU2) {
                page.toast("info", "Logged in as " + oU2.name);
                page.router.refresh();
            } else {
                page.toast("error", "Passkey authentication succeeded but session failed");
            }
        } else {
            page.toast("error", "Passkey authentication failed");
        }
    } catch (e) {
        page.toast("error", "Passkey login failed: " + (e.message || e));
    }
    passkeyLoading = false;
    m.redraw();
}

let organizations = ["Specify ...", "/Public", "/Development", "/System"];

function toggleSpecify() {
    let oSel = document.querySelector("#selOrganizationList");
    let i = oSel.selectedIndex;
    if (i == -1) {
        i = 1;
    }
    document.querySelector("#specifyOrganizationPath").style.display = ((i == 0) ? "block" : "none");
}

function orgFieldDesigner(i, e, v) {
    return m("div", [
        m("label", {
            class: "block",
            for: "organization"
        }, [
            "Organization"
        ]),
        organizationField()
    ]);
}

function organizationField() {
    return [
        m("select", { id: "selOrganizationList", 'aria-label': "Organization", onchange: inst.handleChange("organization", toggleSpecify), class: "w-full page-select reactive-arrow" },
            organizations.map((o, i) => m("option", { value: o, selected: (o == inst.api.organization()) }, o)),
        ),
        m("input", {
            class: "hidden text-field-full",
            type: "text",
            id: "specifyOrganizationPath",
            onchange: inst.handleChange("organization"),
            placeholder: "/Custom"
        })
    ];
}

inst.designer("organization", orgFieldDesigner);
inst.viewProperties("password", { onkeydown: function (e) { if (e.which == 13) doLogin(); } });
inst.viewProperties("userName", { autocapitalize: "off" });

let vnode;
signInPage.view = {
    oninit: function (x) { },
    oncreate: function (x) { },
    onremove: function (x) { },
    view: function () {
        vnode = m("div", {
            class: "screen-center-gray"
        }, [
            m("div", {
                class: "box-shadow-white"
            }, [
                am7view.form(inst),
                am7client.webauthnSupported() ? m("div", { class: "mt-3 pt-3 border-t border-gray-200 dark:border-gray-700 text-center" }, [
                    m("button", {
                        class: "btn btn-secondary w-full flex items-center justify-center gap-2",
                        disabled: passkeyLoading,
                        onclick: doPasskeyLogin,
                        'aria-label': "Sign in with Passkey"
                    }, [
                        m("span", { class: "material-symbols-outlined text-base" }, "passkey"),
                        passkeyLoading ? "Authenticating..." : "Sign in with Passkey"
                    ])
                ]) : null
            ])
        ]);
        return vnode;
    }
};

page.views.sig = signInPage.view;

export default signInPage.view;
