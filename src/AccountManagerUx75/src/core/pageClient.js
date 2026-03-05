import m from 'mithril';
import { applicationPath } from './config.js';
import { am7model } from './model.js';
import { am7view } from './view.js';
import { uwm, am7client } from './am7client.js';
import { Dialog } from '../components/dialogCore.js';

// --- Context ---

function getContextRoles() {
    return {
        api: false,
        userReader: false,
        roleReader: false,
        permissionReader: false,
        author: false,
        user: false,
        systemAdmin: false,
        accountAdmin: false,
        objectAdmin: false,
        dataAdmin: false
    };
}

function newContextModel() {
    return {
        streams: {},
        contextObjects: {},
        entitlements: {},
        tags: {},
        favorites: {},
        controls: {},
        requests: {},
        contextViews: {},
        roles: getContextRoles(),
        notifications: [],
        messages: [],
        pendingEntity: undefined
    };
}

let contextModel = newContextModel();

function clearContextObject(id) {
    delete contextModel.streams[id];
    delete contextModel.contextObjects[id];
    delete contextModel.entitlements[id];
    delete contextModel.tags[id];
    delete contextModel.controls[id];
    delete contextModel.requests[id];
    delete contextModel.contextViews[id];
}

// --- UID ---

let uidStub = "xxxxxxxx-xxxx-9xxx-yxxx-xxxxxxxxxxxx";
let luidStub = "xxxx-xxxx";

function uid(temp) {
    return temp.replace(/[xy]/g, function (c) {
        const r = Math.random() * 16 | 0,
            v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// --- Toast ---

let toast = [];
let toastConfig = {
    info: {
        ico: "info",
        style: "text-gray-500 bg-white dark:bg-black dark:text-gray-300",
        icoStyle: "text-blue-500 dark:text-blue-300",
        timeout: 5000
    },
    success: {
        ico: "check",
        style: "text-gray-500 bg-green-200 dark:bg-green-800 dark:text-gray-300",
        icoStyle: "text-black dark:text-white",
        timeout: 5000
    },
    warn: {
        ico: "warning",
        style: "bg-yellow-200 text-black dark:text-white dark:bg-yellow-800",
        icoStyle: "text-black dark:text-white",
        timeout: 10000
    },
    error: {
        ico: "stop",
        style: "bg-red-200 text-black dark:text-white dark:bg-red-700",
        icoStyle: "text-black dark:text-white",
        timeout: 15000
    }
};

function removeToast(id) {
    toast = toast.filter((t) => t.id != id);
}

function burnToast(nt) {
    let o = document.querySelector("#toast-box-" + nt.id);
    if (o) {
        o.className = "transition transition-0 toast-box " + toastConfig[nt.type].style;
    }
    window.setTimeout(() => {
        nt.visible = false;
        removeToast(nt.id);
        m.redraw();
    }, 300);
}

function clearToast() {
    toast = [];
    m.redraw();
}

function addToast(type, msg, timeout) {
    let nt = {
        id: uid(luidStub),
        type,
        message: msg,
        visible: false,
        expiry: timeout || 5000
    };
    toast.push(nt);
    window.setTimeout(() => {
        nt.visible = true;
        let o = document.querySelector("#toast-box-" + nt.id);
        if (o) {
            o.className = "transition transition-100 toast-box " + toastConfig[nt.type].style;
        }
    }, 10);
    if (nt.expiry >= 0) {
        window.setTimeout(() => {
            burnToast(nt);
        }, nt.expiry);
    }
    m.redraw();
    return nt.id;
}

function loadToast() {
    let items = toast.map((t) => {
        let cfg = toastConfig[t.type];
        let closeBtn = m("button", {
            onclick: () => { burnToast(t); },
            type: "button",
            class: "ms-auto -mx-1.5 -my-1.5 rounded-lg focus:ring-2 focus:ring-gray-300 p-1.5 hover:bg-gray-100 dark:bg-gray-900 dark:text-gray-200 inline-flex items-center justify-center h-8 w-8"
        },
            m("span", { class: "material-symbols-outlined" }, "close")
        );
        return m("div", { id: "toast-box-" + t.id, class: "transition transition-" + (t.visible ? "100" : "0") + " toast-box " + cfg.style }, [
            m("div", { class: cfg.icoStyle + " ico-box" }, [
                m("span", { class: "material-symbols-outlined" }, cfg.ico)
            ]),
            m("div", { class: "toast-text" }, t.message),
            closeBtn
        ]);
    });
    return (items.length == 0 ? "" : m("div", { class: "toast-container" }, [
        items
    ]));
}

// --- WebSocket ---

let webSocket;
let wsReconnectDelay = 1000;
let wsMaxReconnectDelay = 30000;
let wsMaxReconnectAttempts = 10;
let wsReconnectAttempts = 0;
let keepAliveInterval = null;
let wsLastPong = Date.now();
let keepAliveMs = 25000;
let wsStaleThresholdMs = 60000;

function startKeepAlive() {
    stopKeepAlive();
    wsLastPong = Date.now();
    keepAliveInterval = setInterval(function () {
        if (!webSocket || webSocket.readyState !== WebSocket.OPEN) {
            stopKeepAlive();
            return;
        }
        let elapsed = Date.now() - wsLastPong;
        if (elapsed > wsStaleThresholdMs) {
            console.warn("[KeepAlive] WebSocket stale (" + Math.round(elapsed / 1000) + "s since last pong). Forcing reconnect.");
            stopKeepAlive();
            try { webSocket.close(); } catch (e) { /* ignore */ }
            return;
        }
        try {
            webSocket.send(JSON.stringify({ ping: true }));
        } catch (e) {
            console.warn("[KeepAlive] WS ping send failed:", e);
            stopKeepAlive();
        }
    }, keepAliveMs);
}

function stopKeepAlive() {
    if (keepAliveInterval) {
        clearInterval(keepAliveInterval);
        keepAliveInterval = null;
    }
}

function wssClose() {
    if (!webSocket || webSocket.readyState === WebSocket.CLOSED) {
        return;
    }
    webSocket.close();
    webSocket = undefined;
}

function wssConnect() {
    if (webSocket !== undefined && webSocket.readyState !== WebSocket.CLOSED) {
        console.warn("Connection is already open");
        return;
    }
    return new Promise((res, rej) => {
        let wsUrl = applicationPath.replace(/^http[s]*/, "wss") + "/wss";
        // When using Vite proxy with relative paths, construct WS URL from window.location
        if (wsUrl.startsWith("/")) {
            let proto = window.location.protocol === "https:" ? "wss:" : "ws:";
            wsUrl = proto + "//" + window.location.host + wsUrl;
        }
        webSocket = new WebSocket(wsUrl);

        webSocket.onopen = function (event) {
            wsReconnectAttempts = 0;
            wsReconnectDelay = 1000;
            startKeepAlive();
            res(event);
        };

        webSocket.onmessage = function (event) {
            wsLastPong = Date.now();
            if (event.data) {
                let parsed = JSON.parse(event.data);
                if (parsed && parsed.pong) return;
                routeMessage(parsed);
            }
        };

        webSocket.onclose = function (event) {
            console.warn("[WebSocket] Closed (code: " + event.code + ")");
            stopKeepAlive();
            if (wsReconnectAttempts < wsMaxReconnectAttempts) {
                wsReconnectAttempts++;
                console.log("[WebSocket] Reconnecting (attempt " + wsReconnectAttempts + ") in " + wsReconnectDelay + "ms...");
                setTimeout(function () {
                    reconnect(am7client.currentOrganization, page.token, 0);
                }, wsReconnectDelay);
                wsReconnectDelay = Math.min(wsReconnectDelay * 2, wsMaxReconnectDelay);
            } else {
                addToast("error", "Connection lost. Please reload the page.", 0);
            }
        };

        webSocket.onerror = function (event) {
            console.error("[WebSocket] Error", event);
            stopKeepAlive();
        };
    });
}

let maxReconnect = 60;
async function reconnect(sOrg, sTok, iIter) {
    addToast("warn", "Reconnecting ...", 5000);
    let i = 0;
    let iter = (iIter || 0) + 1;
    let org = sOrg || am7client.currentOrganization;
    let tok = sTok || page.token;
    if (!org || !tok) {
        clearToast();
        addToast("error", "Unable to reconnect", 5000);
        return;
    }
    try {
        i = await m.request({ method: "GET", url: applicationPath + "/rest/login/time" });
    } catch (e) {
        // server unavailable
    }
    if (iter >= maxReconnect) {
        addToast("error", "Failed to reconnect", 5000);
        return;
    }
    if (i > 0) {
        await am7client.loginWithPassword(sOrg, "${jwt}", sTok, function (v) {
            clearToast();
            if (v != null) {
                addToast("success", "Reconnected", 5000);
                page.router.refresh();
            } else {
                addToast("error", "Failed to reconnect", 5000);
            }
        });
    } else {
        setTimeout(function () {
            reconnect(sOrg, sTok, iter);
        }, 5000);
    }
}

async function wssSend(name, message, recipient, schema) {
    try {
        let recipientId = null;
        if (typeof recipient == "number") recipientId = recipient;
        else if (typeof recipient == "string") {
            // Simplified for Phase 0 — full implementation in later phases
            console.warn("String recipient lookup not yet implemented in Ux75");
        }
        let msg = {
            data: uwm.base64Encode(message),
            modelType: schema,
            messageId: uid(uidStub),
            name: name,
            recipientId,
            recipientType: (recipientId != null ? "USER" : "UNKNOWN")
        };
        let dat = JSON.stringify({ schema: "message.socketMessage", token: page?.token, message: msg });
        webSocket.send(dat);
    } catch (e) {
        console.error(e);
    }
}

function routeMessage(msg) {
    if (msg.token) {
        page.token = msg.token;
    }
    if (msg.chirps) {
        let c1 = msg.chirps[0] || "";
        if (c1.match(/(chatStart|chatComplete|chatUpdate|chatError)/)) {
            if (!page.chatStream) return;
            page.chatStream["on" + c1.toLowerCase()](msg.chirps[1], msg.chirps[2], msg.chirps[3]);
        } else if (c1.match(/(audioUpdate|audioSTTUpdate|audioError)/)) {
            if (!page.audioStream) return;
            page.audioStream["on" + c1.toLowerCase()](msg.chirps[1], msg.chirps[2]);
        } else if (c1 === "policyEvent" || c1 === "autotuneEvent" || c1 === "evalProgress" || c1 === "interactionEvent") {
            import('../chat/LLMConnector.js').then(mod => {
                mod.LLMConnector.handlePolicyEvent({ type: c1, data: msg.chirps[1] });
            }).catch(() => {});
        } else if (c1 === "memoryEvent") {
            import('../chat/LLMConnector.js').then(mod => {
                mod.LLMConnector.handleMemoryEvent({ type: c1, data: msg.chirps[1] });
            }).catch(() => {});
        } else if (c1.match(/^game\./)) {
            if (page.gameStream) {
                page.gameStream.routePushMessage(c1, ...msg.chirps.slice(1));
            }
        }
        m.redraw();
    }
}

// --- Utility ---

function iconButton(sClass, sIco, sLabel, fHandler) {
    return m("button", { onclick: fHandler, class: sClass }, (sIco ? m("span", { class: "material-symbols-outlined material-icons-24" }, sIco) : ""), (sLabel || ""));
}

function getRawUrl() {
    let url = m.route.get();
    let idx = url.indexOf("?");
    if (idx > -1) url = url.substring(0, idx);
    return url;
}

function contentRouter() {
    let curRout = m.route.get();
    if (curRout != "/sig" && !page.authenticated()) {
        return m.route.set("/sig");
    } else {
        return m(page.components.panel || { view: () => m("div", "Panel not loaded") });
    }
}

function clearPageCache() {
    contextModel = newContextModel();
}

// --- API helpers used by pagination, list, object views ---

function openObject(type, objectId) {
    let q = am7view.viewQuery(am7model.newInstance(type));
    q.field('objectId', objectId);
    return new Promise(function (resolve) {
        am7client.search(q, function (qr) {
            if (qr && qr.results && qr.results.length) {
                resolve(qr.results[0]);
            } else {
                resolve(null);
            }
        });
    });
}

function pageSearch(q) {
    return new Promise(function (resolve) {
        am7client.search(q, function (v) {
            resolve(v);
        });
    });
}

function pageCount(q) {
    return new Promise(function (resolve) {
        am7client.searchCount(q, function (v) {
            resolve(v);
        });
    });
}

function deleteObject(type, objectId) {
    return new Promise(function (resolve) {
        am7client.delete(type, objectId, function (v) {
            resolve(v);
        });
    });
}

function createObject(obj) {
    let type = obj[am7model.jsonModelKey] || obj.schema;
    return new Promise(function (resolve) {
        am7client.create(type, obj, function (v) {
            resolve(v);
        });
    });
}

function patchObject(obj) {
    let type = obj[am7model.jsonModelKey] || obj.schema;
    return new Promise(function (resolve) {
        am7client.patch(type, obj, function (v) {
            resolve(v);
        });
    });
}

function makePath(type, subType, path) {
    return new Promise(function (resolve) {
        am7client.make(type, subType, path, function (v) {
            resolve(v);
        });
    });
}

function findObject(type, subType, path) {
    return am7client.find(type, subType, path);
}

function listObjects(type, parentId, fields, start, count) {
    return am7client.list(type, parentId, fields, start, count);
}

function searchByName(type, parentId, name) {
    return am7client.getByName(type, parentId, name);
}

function listByType(type) {
    let modType = am7model.getModel(type);
    if (!modType) {
        console.warn('listByType: unknown type ' + type);
        m.route.set('/main');
        return;
    }
    if (modType.group) {
        // Group-based types need a container; use the org root
        let orgPath = am7client.currentOrganization;
        if (orgPath) {
            makePath(type, 'data', orgPath).then(function (grp) {
                if (grp) m.route.set('/list/' + type + '/' + grp.objectId);
                else m.route.set('/main');
            });
        } else {
            m.route.set('/main');
        }
    } else {
        m.route.set('/list/' + type);
    }
}

// --- Page Object ---

const page = {
    toast: addToast,
    toasts: () => toast,
    loadToast,
    clearToast,
    clearCache: clearPageCache,
    components: {
        dialog: {
            open: Dialog.open,
            close: Dialog.close,
            closeAll: Dialog.closeAll,
            confirm: Dialog.confirm,
            loadDialogs: Dialog.loadDialogs
        }
    },
    user: null,
    application: undefined,
    navigable: undefined,
    contentRouter: contentRouter,
    authenticated: () => (page.user != null),
    getRawUrl: getRawUrl,
    iconButton: iconButton,
    context: function () { return contextModel; },
    clearContextObject: clearContextObject,
    openObject: openObject,
    findObject: findObject,
    listObjects: listObjects,
    searchByName: searchByName,
    search: pageSearch,
    count: pageCount,
    deleteObject: deleteObject,
    createObject: createObject,
    patchObject: patchObject,
    makePath: makePath,
    listByType: listByType,
    logout: async function () {
        clearPageCache();
        await uwm.logout();
        m.route.set("/sig");
    },
    wss: {
        connect: wssConnect,
        close: wssClose,
        send: wssSend
    },
    reconnect,
    uid: function () { return uid(uidStub); },
    luid: function () { return uid(luidStub); },
    views: {},
    chatStream: undefined,
    audioStream: undefined,
    gameStream: undefined,
    token: undefined,
    router: null,
    testMode: (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get("testMode") === "true"),
    productionMode: (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get("productionMode") === "true"),
    formDef: am7model
};

export { page };
export default page;
