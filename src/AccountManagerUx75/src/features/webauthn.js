/**
 * WebAuthn feature — Passkey credential management (Phase 8)
 * Settings UI for registering/managing WebAuthn credentials.
 * Login integration is in sig.js.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { layout, pageLayout } from '../router.js';

// ── State ───────────────────────────────────────────────────────────

let credentials = [];
let loading = false;
let registering = false;
let error = null;
let newKeyName = "My Passkey";

// ── API helpers ─────────────────────────────────────────────────────

async function loadCredentials() {
    loading = true;
    error = null;
    try {
        let result = await am7client.webauthnListCredentials();
        credentials = Array.isArray(result) ? result : [];
    } catch (e) {
        error = "Failed to load passkeys";
        credentials = [];
    }
    loading = false;
    m.redraw();
}

async function registerPasskey() {
    if (registering) return;
    if (!am7client.webauthnSupported()) {
        page.toast("error", "WebAuthn is not supported in this browser");
        return;
    }
    registering = true;
    error = null;
    m.redraw();

    try {
        let result = await am7client.webauthnRegister(newKeyName);
        if (result && result.success) {
            page.toast("info", "Passkey registered successfully");
            newKeyName = "My Passkey";
            await loadCredentials();
        } else {
            error = result && result.error ? result.error : "Registration failed";
            page.toast("error", error);
        }
    } catch (e) {
        error = "Registration failed: " + (e.message || e);
        page.toast("error", error);
    }
    registering = false;
    m.redraw();
}

async function deletePasskey(credentialId, name) {
    if (!confirm("Delete passkey \"" + name + "\"?")) return;
    try {
        let result = await am7client.webauthnDeleteCredential(credentialId);
        if (result && result.success) {
            page.toast("info", "Passkey deleted");
            await loadCredentials();
        } else {
            page.toast("error", "Failed to delete passkey");
        }
    } catch (e) {
        page.toast("error", "Failed to delete passkey");
    }
}

// ── View ────────────────────────────────────────────────────────────

let webauthnView = {
    oninit: function () {
        loadCredentials();
    },
    view: function () {
        let supported = am7client.webauthnSupported();

        return m("div", { class: "p-4 max-w-2xl" }, [
            m("h2", { class: "text-xl font-semibold mb-4" }, "Passkey Management"),

            !supported ? m("div", { class: "bg-yellow-100 dark:bg-yellow-900 p-3 rounded mb-4 text-sm" },
                "WebAuthn is not supported in this browser. Use a modern browser with platform authenticator support."
            ) : null,

            // Register section
            m("div", { class: "mb-6 p-4 border border-gray-200 dark:border-gray-700 rounded" }, [
                m("h3", { class: "text-lg font-medium mb-3" }, "Register New Passkey"),
                m("div", { class: "flex gap-3 items-end" }, [
                    m("div", { class: "flex-1" }, [
                        m("label", { class: "block text-sm mb-1" }, "Passkey Name"),
                        m("input", {
                            type: "text",
                            class: "w-full text-field-full",
                            value: newKeyName,
                            oninput: function (e) { newKeyName = e.target.value; },
                            placeholder: "e.g. Windows Hello, YubiKey"
                        })
                    ]),
                    m("button", {
                        class: "btn btn-primary h-10 px-4 whitespace-nowrap",
                        disabled: !supported || registering,
                        onclick: registerPasskey
                    }, registering ? "Registering..." : "Register Passkey")
                ])
            ]),

            // Credentials list
            m("div", [
                m("h3", { class: "text-lg font-medium mb-3" }, "Registered Passkeys"),

                loading ? m("div", { class: "text-sm text-gray-500" }, "Loading...") :
                credentials.length === 0 ? m("div", { class: "text-sm text-gray-500 italic" }, "No passkeys registered yet.") :
                m("table", { class: "w-full text-sm" }, [
                    m("thead", m("tr", { class: "border-b dark:border-gray-700" }, [
                        m("th", { class: "text-left py-2 pr-4" }, "Name"),
                        m("th", { class: "text-left py-2 pr-4" }, "Created"),
                        m("th", { class: "text-left py-2 pr-4" }, "Last Used"),
                        m("th", { class: "text-left py-2" }, "")
                    ])),
                    m("tbody", credentials.map(function (cred) {
                        let created = cred.createdDate ? new Date(cred.createdDate).toLocaleDateString() : "—";
                        let lastUsed = cred.lastUsed ? new Date(cred.lastUsed).toLocaleDateString() : "Never";
                        return m("tr", { key: cred.credentialId || cred.objectId, class: "border-b dark:border-gray-800" }, [
                            m("td", { class: "py-2 pr-4" }, [
                                m("span", { class: "material-symbols-outlined text-base align-middle mr-1" }, "passkey"),
                                cred.name || "Unnamed"
                            ]),
                            m("td", { class: "py-2 pr-4 text-gray-500" }, created),
                            m("td", { class: "py-2 pr-4 text-gray-500" }, lastUsed),
                            m("td", { class: "py-2" },
                                m("button", {
                                    class: "text-red-500 hover:text-red-700 text-sm",
                                    onclick: function () { deletePasskey(cred.credentialId, cred.name); }
                                }, "Delete")
                            )
                        ]);
                    }))
                ]),

                error ? m("div", { class: "mt-3 text-red-500 text-sm" }, error) : null
            ])
        ]);
    }
};

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/webauthn": {
        oninit: function () { webauthnView.oninit(); },
        view: function () {
            return layout(pageLayout(webauthnView.view()));
        }
    }
};
