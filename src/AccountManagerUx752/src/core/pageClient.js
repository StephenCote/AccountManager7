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
            'aria-label': "Dismiss notification",
            class: "ms-auto -mx-1.5 -my-1.5 rounded-lg focus:ring-2 focus:ring-gray-300 p-1.5 hover:bg-gray-100 dark:bg-gray-900 dark:text-gray-200 inline-flex items-center justify-center h-8 w-8"
        },
            m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, "close")
        );
        return m("div", { id: "toast-box-" + t.id, class: "transition transition-" + (t.visible ? "100" : "0") + " toast-box " + cfg.style, role: "status" }, [
            m("div", { class: cfg.icoStyle + " ico-box" }, [
                m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, cfg.ico)
            ]),
            m("div", { class: "toast-text" }, t.message),
            closeBtn
        ]);
    });
    return (items.length == 0 ? "" : m("div", { class: "toast-container", 'aria-live': "polite", 'aria-atomic': "false" }, [
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

let _wsRedrawTimer = 0;
function _scheduleWsRedraw() {
    if (!_wsRedrawTimer) {
        _wsRedrawTimer = requestAnimationFrame(() => { _wsRedrawTimer = 0; m.redraw(); });
    }
}

function routeMessage(msg) {
    if (msg.token) {
        page.token = msg.token;
    }
    if (msg.chirps) {
        let c1 = msg.chirps[0] || "";
        let handled = true;
        if (c1.match(/(chatStart|chatComplete|chatUpdate|chatError)/)) {
            if (!page.chatStream) return;
            page.chatStream["on" + c1.toLowerCase()](msg.chirps[1], msg.chirps[2], msg.chirps[3]);
        } else if (c1.match(/(audioUpdate|audioSTTUpdate|audioError)/)) {
            if (!page.audioStream) return;
            page.audioStream["on" + c1.toLowerCase()](msg.chirps[1], msg.chirps[2]);
        } else if (c1 === "policyEvent" || c1 === "autotuneEvent" || c1 === "evalProgress" || c1 === "interactionEvent") {
            import('../chat/LLMConnector.js').then(mod => {
                mod.LLMConnector.handlePolicyEvent({ type: c1, data: msg.chirps[1] });
                _scheduleWsRedraw();
            }).catch(() => {});
            return;
        } else if (c1 === "memoryEvent") {
            import('../chat/LLMConnector.js').then(mod => {
                mod.LLMConnector.handleMemoryEvent({ type: c1, data: msg.chirps[1] });
                _scheduleWsRedraw();
            }).catch(() => {});
            return;
        } else if (c1 === "chainEvent") {
            let eventType = msg.chirps[1];
            let eventData = null;
            try { eventData = JSON.parse(msg.chirps[2]); } catch(e) { eventData = msg.chirps[2]; }
            if (page.chainStream && page.chainStream["on" + eventType]) {
                page.chainStream["on" + eventType](eventData);
            }
        } else if (c1.match(/^game\./)) {
            if (page.gameStream) {
                page.gameStream.routePushMessage(c1, ...msg.chirps.slice(1));
            }
        } else if (c1 === "clearCache") {
            // Server-initiated cache clear — purge local caches only (no server call back)
            let cacheType = msg.chirps[1] || "all";
            console.log("[WebSocket] Server requested cache clear:", cacheType);
            am7client.clearCache(cacheType === "all" ? 0 : cacheType, true);
            // Also clear localStorage cache
            try {
                if (typeof localStorage !== 'undefined') {
                    if (cacheType === "all") {
                        let keysToRemove = [];
                        for (let i = 0; i < localStorage.length; i++) {
                            let key = localStorage.key(i);
                            if (key && key.startsWith("am7_")) keysToRemove.push(key);
                        }
                        keysToRemove.forEach(function(k) { localStorage.removeItem(k); });
                    }
                }
            } catch(e) { /* localStorage not available */ }
        } else if (c1 === "accessRequestUpdate") {
            // Access request status change notification — notify registered listeners
            let eventData = null;
            try { eventData = JSON.parse(msg.chirps[1]); } catch(e) { eventData = msg.chirps[1]; }
            if (page.accessRequestListeners) {
                page.accessRequestListeners.forEach(fn => { try { fn(eventData); } catch(e) {} });
            }
        } else {
            handled = false;
        }
        if (handled) _scheduleWsRedraw();
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

function openObjectByName(type, parentId, name) {
    return am7client.getByName(type, parentId, name);
}

async function searchFirst(model, groupId, name, objectId) {
    let q = am7view.viewQuery(am7model.newInstance(model));
    if (groupId) {
        q.field("groupId", groupId);
    }
    if (name) {
        q.field("name", name);
    }
    if (objectId) {
        q.field("objectId", objectId);
    }
    let qr = await pageSearch(q);
    if (qr && qr.results && qr.results.length) {
        return qr.results[0];
    }
    return undefined;
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

// --- Favorites ---

let favGroup = null;

async function favorites() {
    if (!page.user) return null;
    if (favGroup) return favGroup;
    let origin = page.user.homeDirectory;
    let grp = await searchByName('auth.group', origin.objectId, 'Favorites');
    if (!grp) {
        grp = am7model.newPrimitive('auth.group');
        grp.type = 'bucket';
        grp.parentId = origin.id;
        grp.name = 'Favorites';
        await createObject(grp);
        am7client.clearCache('auth.group', true);
        grp = await searchByName('auth.group', origin.objectId, 'Favorites');
    }
    favGroup = grp;
    return grp;
}

function isFavorite(obj) {
    let type = obj[am7model.jsonModelKey];
    return (contextModel.favorites[type] !== undefined &&
        contextModel.favorites[type].filter(function (o) { return o.objectId === obj.objectId; }).length > 0);
}

async function toggleFavorite(obj) {
    let set = !isFavorite(obj);
    let fav = await favorites();
    if (!fav) return false;
    let type = obj[am7model.jsonModelKey];
    return new Promise(function (resolve) {
        am7client.member(fav[am7model.jsonModelKey], fav.objectId, 'null', type, obj.objectId, set, function (r) {
            contextModel.favorites[type] = undefined;
            am7client.clearCache(fav[am7model.jsonModelKey], true);
            checkFavorites(type);
            resolve(!!r);
        });
    });
}

async function checkFavorites(itype) {
    let type = itype || m.route.param('type');
    if (type && contextModel.favorites[type] === undefined) {
        contextModel.favorites[type] = [];
        let fav = await favorites();
        if (!fav) return;
        am7client.members(fav[am7model.jsonModelKey], fav.objectId, type, 0, 100, function (v) {
            contextModel.favorites[type] = v || [];
            m.redraw();
        });
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
        },
        picker: null,
        dnd: null,
        formFieldRenderers: null,
        tableEntry: null,
        tableListEditor: null,
        membership: null,
        pdf: null
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
    openObjectByName: openObjectByName,
    searchFirst: searchFirst,
    search: pageSearch,
    count: pageCount,
    deleteObject: deleteObject,
    createObject: createObject,
    patchObject: patchObject,
    makePath: makePath,
    listByType: listByType,
    favorites: favorites,
    isFavorite: isFavorite,
    toggleFavorite: toggleFavorite,
    checkFavorites: checkFavorites,
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
    chainStream: undefined,
    audioStream: undefined,
    gameStream: undefined,
    accessRequestListeners: [],
    onAccessRequestUpdate: function (fn) { page.accessRequestListeners.push(fn); return fn; },
    offAccessRequestUpdate: function (fn) { page.accessRequestListeners = page.accessRequestListeners.filter(f => f !== fn); },
    token: undefined,
    router: null,
    testMode: (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get("testMode") === "true"),
    productionMode: (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get("productionMode") === "true"),
    formDef: am7model,
    imageGallery: async function(images, charInst) {
        let galleryImages = images || [];
        if (charInst && charInst.entity) {
            let entity = charInst.entity;
            let profile = entity.profile;
            let portrait = profile ? profile.portrait : null;
            let profileOid = profile ? profile.objectId : null;
            if (profileOid && (!portrait || !portrait.groupId)) {
                let pq = am7view.viewQuery('identity.profile');
                pq.field('objectId', profileOid);
                pq.entity.request.push('portrait');
                let pqr = await pageSearch(pq);
                if (pqr && pqr.results && pqr.results.length) {
                    portrait = pqr.results[0].portrait;
                    if (entity.profile) entity.profile.portrait = portrait;
                }
            }
            if (portrait && portrait.groupId) {
                let q = am7client.newQuery('data.data');
                q.field('groupId', portrait.groupId);
                q.entity.request.push('id', 'objectId', 'name', 'groupId', 'groupPath', 'contentType', 'organizationPath', 'attributes', 'description', 'tags');
                q.range(0, 200);
                q.sort('modifiedDate');
                q.order('descending');
                q.cache(false);
                let qr = await am7client.search(q);
                if (qr && qr.results) {
                    let existingIds = new Set(qr.results.map(function(r) { return r.id; }));
                    let newImgs = galleryImages.filter(function(img) { return !existingIds.has(img.id); });
                    galleryImages = newImgs.concat(qr.results.filter(function(r) { return r.contentType && r.contentType.match(/^image\//i); }));
                }
            }
        }
        let idx = 0;
        let viewMode = 'grid';
        let fullView = false;
        let tagsCache = {};

        function loadTags(img) {
            if (!img || tagsCache[img.objectId]) return;
            // Use tags from query if available
            if (Array.isArray(img.tags) && img.tags.length) {
                tagsCache[img.objectId] = img.tags;
                return;
            }
            tagsCache[img.objectId] = [];
            // Fall back to member listing (participation lookup)
            try {
                let p = am7client.members('data.data', img.objectId, 'data.tag', 0, 50, function(tags) {
                    tagsCache[img.objectId] = (Array.isArray(tags) ? tags : []);
                    m.redraw();
                });
                if (p && p.catch) p.catch(function() { tagsCache[img.objectId] = []; });
            } catch(e) { /* ignore */ }
        }

        let galleryRef = null;
        function refocusGallery() { if (galleryRef) galleryRef.focus(); }

        function getImageAttrs(img) {
            if (!img) return [];
            let attrs = [];
            if (img.attributes && img.attributes.length) {
                img.attributes.forEach(function(a) {
                    if (a.name && a.values && a.values.length) attrs.push({ name: a.name, value: a.values[0] });
                });
            }
            return attrs;
        }

        function renderGallery() {
            let sel = galleryImages[idx];
            if (sel) loadTags(sel);
            let tags = sel ? (tagsCache[sel.objectId] || []) : [];
            let attrs = getImageAttrs(sel);
            let previewUrl = sel ? am7client.mediaDataPath(sel, true, '512x512') : '';
            return m('div', {
                class: 'outline-none overflow-hidden w-full',
                tabindex: 0,
                oncreate: function(vn) { galleryRef = vn.dom; vn.dom.focus(); },
                onremove: function() { galleryRef = null; },
                onkeydown: function(e) {
                    if (e.key === 'Escape' && fullView) { fullView = false; m.redraw(); e.preventDefault(); return; }
                    if (e.key === 'ArrowLeft' && idx > 0) { idx--; fullView = false; m.redraw(); e.preventDefault(); }
                    else if (e.key === 'ArrowRight' && idx < galleryImages.length - 1) { idx++; fullView = false; m.redraw(); e.preventDefault(); }
                    else if (e.key === 'Delete' && sel) {
                        Dialog.confirm('Delete ' + sel.name + '?', async function() {
                            await deleteObject('data.data', sel.objectId);
                            galleryImages.splice(idx, 1);
                            if (idx >= galleryImages.length) idx = Math.max(0, galleryImages.length - 1);
                            m.redraw();
                        });
                        e.preventDefault();
                    }
                }
            }, [
                // Toolbar
                m('div', { class: 'flex items-center gap-2 text-sm mb-2' }, [
                    m('span', { class: 'text-gray-500' }, galleryImages.length + ' image(s)'),
                    m('span', { class: 'text-gray-400 text-xs' }, '(' + (idx + 1) + '/' + galleryImages.length + ')'),
                    idx > 0 ? m('button', {
                        class: 'p-1 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800',
                        title: 'Previous (←)',
                        onclick: function() { idx--; refocusGallery(); }
                    }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'arrow_back')) : null,
                    idx < galleryImages.length - 1 ? m('button', {
                        class: 'p-1 rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800',
                        title: 'Next (→)',
                        onclick: function() { idx++; refocusGallery(); }
                    }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'arrow_forward')) : null,
                    m('button', {
                        class: 'p-1 rounded ' + (viewMode === 'grid' ? 'bg-blue-100 dark:bg-blue-900 text-blue-600' : 'text-gray-500 hover:bg-gray-100'),
                        onclick: function() { viewMode = 'grid'; refocusGallery(); }
                    }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'grid_view')),
                    m('button', {
                        class: 'p-1 rounded ' + (viewMode === 'list' ? 'bg-blue-100 dark:bg-blue-900 text-blue-600' : 'text-gray-500 hover:bg-gray-100'),
                        onclick: function() { viewMode = 'list'; refocusGallery(); }
                    }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'view_list'))
                ]),
                // Preview + info
                // Full-view overlay: image sized to fit dialog
                fullView && sel ? m('div', { class: 'flex flex-col items-center mb-3' }, [
                    m('img', {
                        src: am7client.mediaDataPath(sel, true, '1024x1024'),
                        class: 'max-w-full rounded shadow cursor-pointer',
                        style: 'object-fit:contain;max-height:70vh',
                        title: 'Click to return to gallery',
                        onclick: function() { fullView = false; }
                    }),
                    m('div', { class: 'flex gap-2 mt-2' }, [
                        m('button', { class: 'text-xs px-2 py-1 rounded bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300 hover:bg-gray-200', onclick: function() { fullView = false; } },
                            [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px;vertical-align:middle' }, 'arrow_back'), ' Back']),
                        m('a', { class: 'text-xs px-2 py-1 rounded bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300 hover:bg-gray-200 no-underline inline-flex items-center gap-1', href: am7client.mediaDataPath(sel, false), target: '_blank' },
                            [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'open_in_new'), 'New Tab'])
                    ])
                ]) : null,
                // Normal preview + info (hidden in full-view)
                sel && !fullView ? m('div', { class: 'flex gap-3 mb-3 overflow-hidden min-w-0' }, [
                    m('div', { class: 'flex-shrink-0', style: 'max-width:50%' },
                        m('img', {
                            src: previewUrl, class: 'max-w-full max-h-80 rounded shadow cursor-pointer', style: 'object-fit:contain',
                            title: 'Click for full view',
                            onclick: function() { fullView = true; }
                        })
                    ),
                    m('div', { class: 'flex-1 min-w-0 overflow-hidden' }, [
                        m('div', { class: 'text-sm font-medium text-gray-800 dark:text-white mb-1' }, sel.name),
                        m('div', { class: 'flex flex-wrap gap-1 mb-2' }, [
                            m('a', {
                                class: 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-200 no-underline',
                                href: '#!/view/data.data/' + sel.objectId,
                                target: '_blank',
                                onclick: function(e) { e.stopPropagation(); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'open_in_new'), 'Open']),
                            m('button', { class: 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-300 hover:bg-blue-200', onclick: function() {
                                let profile = charInst && charInst.entity ? charInst.entity.profile : null;
                                if (!profile) { addToast('error', 'No profile'); return; }
                                patchObject({ schema: 'identity.profile', id: profile.id, portrait: { id: sel.id } }).then(function() {
                                    addToast('success', 'Portrait set');
                                    am7client.clearCache('identity.profile');
                                });
                                refocusGallery();
                            }}, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'account_circle'), 'Set Profile']),
                            m('button', { class: 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded bg-green-100 dark:bg-green-900 text-green-700 dark:text-green-300 hover:bg-green-200', onclick: function() {
                                addToast('info', 'Auto-tagging...');
                                am7client.applyImageTags(sel.objectId, function() {
                                    delete tagsCache[sel.objectId];
                                    addToast('success', 'Tags applied');
                                    m.redraw();
                                });
                                refocusGallery();
                            }}, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'label'), 'Auto-Tag']),
                            m('button', { class: 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded bg-red-100 dark:bg-red-900 text-red-700 dark:text-red-300 hover:bg-red-200', onclick: function() {
                                Dialog.confirm('Delete ' + sel.name + '?', async function() {
                                    await deleteObject('data.data', sel.objectId);
                                    galleryImages.splice(idx, 1);
                                    if (idx >= galleryImages.length) idx = Math.max(0, galleryImages.length - 1);
                                    m.redraw();
                                });
                            }}, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'delete'), 'Delete'])
                        ]),
                        sel.description ? m('div', { class: 'text-xs text-gray-500 italic mb-1 overflow-hidden text-ellipsis', style: 'display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;word-break:break-all' }, sel.description) : null,
                        tags.length > 0 ? m('div', { class: 'mb-1' }, [
                            m('div', { class: 'flex flex-wrap gap-1' }, tags.map(function(t) {
                                let label = (t && typeof t === 'object') ? (t.name || t.objectId || '') : String(t || '');
                                return m('span', { class: 'px-2 py-0.5 rounded-full text-xs bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300' }, label);
                            }))
                        ]) : null,
                        attrs.length > 0 ? m('div', { class: 'flex flex-wrap gap-1 mb-1' }, attrs.map(function(a) {
                            return m('span', { class: 'px-2 py-0.5 rounded-full text-xs bg-indigo-100 dark:bg-indigo-900 text-indigo-700 dark:text-indigo-300' }, String(a.name || '') + ': ' + String(a.value || ''));
                        })) : null,
                        m('div', { class: 'text-xs text-gray-400 mt-2' }, '← → to navigate, Delete to remove')
                    ])
                ]) : null,
                // Grid or list
                viewMode === 'list' ? m('div', { class: 'divide-y divide-gray-200 dark:divide-gray-700 max-h-60 overflow-y-auto' },
                    galleryImages.map(function(img, i) {
                        let thumbUrl = am7client.mediaDataPath(img, true, '64x64');
                        return m('div', { key: 'gal-' + (img.objectId || i), class: 'flex items-center gap-2 px-2 py-1 cursor-pointer ' + (i === idx ? 'bg-blue-50 dark:bg-blue-900/30' : 'hover:bg-gray-50 dark:hover:bg-gray-800'), onclick: function() { idx = i; refocusGallery(); } }, [
                            m('img', { src: thumbUrl, class: 'w-10 h-10 rounded object-cover flex-shrink-0' }),
                            m('span', { class: 'text-sm text-gray-700 dark:text-gray-300 truncate' }, img.name),
                            i === idx ? m('span', { class: 'material-symbols-outlined text-blue-500 ml-auto', style: 'font-size:16px' }, 'check_circle') : null
                        ]);
                    })
                ) : m('div', { class: 'grid grid-cols-6 sm:grid-cols-8 md:grid-cols-10 gap-1 max-h-60 overflow-y-auto' },
                    galleryImages.map(function(img, i) {
                        let thumbUrl = am7client.mediaDataPath(img, true, '96x96');
                        return m('div', { key: 'gal-' + (img.objectId || i), class: 'cursor-pointer rounded overflow-hidden border-2 ' + (i === idx ? 'border-blue-500' : 'border-transparent hover:border-gray-300'), onclick: function() { idx = i; refocusGallery(); } },
                            m('img', { src: thumbUrl, class: 'w-full aspect-square object-cover' }));
                    })
                )
            ]);
        }

        Dialog.open({
            title: 'Gallery (' + galleryImages.length + ' images)',
            size: 'xl',
            content: { view: renderGallery },
            closable: true,
            actions: [{ label: 'Close', icon: 'close', onclick: function() { Dialog.close(); } }]
        });
    },
    imageView: function(imageObj) {
        if (!imageObj) return;
        let org = am7client.dotPath(am7client.currentOrganization);
        let fullUrl = applicationPath + "/media/" + org + "/data.data" + (imageObj.groupPath || "") + "/" + (imageObj.name || "");
        Dialog.open({
            title: imageObj.name || 'Image',
            size: 'lg',
            content: m('div', { class: 'flex flex-col items-center' }, [
                m('img', { src: fullUrl, class: 'max-w-full max-h-[70vh] rounded shadow', style: 'object-fit:contain' })
            ]),
            closable: true,
            actions: [{ label: 'Close', icon: 'close', onclick: function() { Dialog.close(); } }]
        });
    }
};

export { page };
export default page;
