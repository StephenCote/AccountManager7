import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client, uwm } from '../core/am7client.js';
import { page } from '../core/pageClient.js';

// --- Dashboard preferences (localStorage + server) ---

const PREFS_KEY = 'am7.dashboard';
const SERVER_PREFS_NAME = '.dashboardPrefs';
let prefs = null; // { order: [catName, ...], hidden: [catName, ...] }
let prefsLoaded = false;
let serverPrefsId = null; // objectId of the server-side data.data record
let editing = false;
let dragFrom = -1;
let dragOver = -1;

function loadPrefs() {
    if (prefs) return prefs;
    try {
        let raw = localStorage.getItem(PREFS_KEY);
        if (raw) prefs = JSON.parse(raw);
    } catch (e) { /* ignore */ }
    if (!prefs) prefs = { order: [], hidden: [] };
    // Trigger async server fetch on first load
    if (!prefsLoaded) {
        prefsLoaded = true;
        loadServerPrefs();
    }
    return prefs;
}

async function loadServerPrefs() {
    try {
        if (!page.user || !page.user.homeDirectory) return;
        let homePath = page.user.homeDirectory.path;
        let obj = await page.findObject('data.data', 'data', homePath + '/' + SERVER_PREFS_NAME);
        if (obj && obj.dataBytesStore) {
            try {
                let serverPrefs = JSON.parse(atob(obj.dataBytesStore));
                if (serverPrefs && (serverPrefs.order || serverPrefs.hidden)) {
                    prefs = serverPrefs;
                    serverPrefsId = obj.objectId;
                    // Update localStorage cache
                    try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch (e) { /* ignore */ }
                    m.redraw();
                }
            } catch (e) { /* parse error — use localStorage */ }
        } else if (obj) {
            serverPrefsId = obj.objectId;
        }
    } catch (e) { /* server unreachable — use localStorage */ }
}

function savePrefs() {
    try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch (e) { /* ignore */ }
    saveServerPrefs();
}

async function saveServerPrefs() {
    try {
        if (!page.user || !page.user.homeDirectory) return;
        let encoded = btoa(JSON.stringify(prefs));
        if (serverPrefsId) {
            // Patch existing record
            let patch = am7model.newPrimitive('data.data');
            patch.objectId = serverPrefsId;
            patch.dataBytesStore = encoded;
            await am7client.patch('data.data', patch);
        } else {
            // Create new record in home directory
            let homePath = page.user.homeDirectory.path;
            let homeDir = await page.makePath('auth.group', 'data', homePath);
            if (!homeDir) return;
            let obj = am7model.newPrimitive('data.data');
            obj.name = SERVER_PREFS_NAME;
            obj.groupId = homeDir.id;
            obj.groupPath = homePath;
            obj.mimeType = 'application/json';
            obj.dataBytesStore = encoded;
            let created = await am7client.create('data.data', obj);
            if (created) serverPrefsId = created.objectId;
        }
    } catch (e) { /* server save failed — localStorage still updated */ }
}

function getOrderedCategories() {
    loadPrefs();
    let cats = am7model.categories.slice();
    if (prefs.order && prefs.order.length) {
        cats.sort(function (a, b) {
            let ai = prefs.order.indexOf(a.name);
            let bi = prefs.order.indexOf(b.name);
            if (ai === -1) ai = 999;
            if (bi === -1) bi = 999;
            return ai - bi;
        });
    }
    if (!editing && prefs.hidden && prefs.hidden.length) {
        cats = cats.filter(function (c) { return prefs.hidden.indexOf(c.name) === -1; });
    }
    return cats;
}

function isHidden(name) {
    loadPrefs();
    return prefs.hidden && prefs.hidden.indexOf(name) !== -1;
}

function toggleHidden(name) {
    loadPrefs();
    if (!prefs.hidden) prefs.hidden = [];
    let idx = prefs.hidden.indexOf(name);
    if (idx === -1) prefs.hidden.push(name);
    else prefs.hidden.splice(idx, 1);
    savePrefs();
}

function resetPrefs() {
    prefs = { order: [], hidden: [] };
    savePrefs();
}

// --- Drag-to-reorder ---

function onDragStart(idx, e) {
    dragFrom = idx;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', '' + idx);
}

function onDragOver(idx, e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dragOver !== idx) { dragOver = idx; m.redraw(); }
}

function onDragLeave() {
    dragOver = -1;
}

function onDrop(idx, e) {
    e.preventDefault();
    let cats = getOrderedCategories();
    if (dragFrom >= 0 && dragFrom !== idx && dragFrom < cats.length && idx < cats.length) {
        let names = cats.map(function (c) { return c.name; });
        let moved = names.splice(dragFrom, 1)[0];
        names.splice(idx, 0, moved);
        prefs.order = names;
        savePrefs();
    }
    dragFrom = -1;
    dragOver = -1;
}

function onDragEnd() {
    dragFrom = -1;
    dragOver = -1;
}

// --- Panel rendering ---

function modelPanel() {
    let panels = [];
    let cats = getOrderedCategories();
    cats.forEach(function (cat, idx) {
        let k = cat.name;
        let hidden = isHidden(k);
        let dragAttrs = editing ? {
            draggable: true,
            ondragstart: function (e) { onDragStart(idx, e); },
            ondragover: function (e) { onDragOver(idx, e); },
            ondragleave: onDragLeave,
            ondrop: function (e) { onDrop(idx, e); },
            ondragend: onDragEnd
        } : {};
        let cardClass = 'panel-card' +
            (editing && dragOver === idx ? ' panel-card-drop-target' : '') +
            (editing && hidden ? ' panel-card-hidden' : '');
        panels.push(
            m("div", Object.assign({ class: cardClass }, dragAttrs),
                m("p", { class: "card-title" }, [
                    editing ? m("span", {
                        class: "material-symbols-outlined material-icons-cm mr-1 cursor-grab text-gray-400",
                        style: "font-size: 16px"
                    }, "drag_indicator") : null,
                    cat.icon ? m("span", { class: "material-symbols-outlined material-icons-cm mr-2" }, cat.icon) : "",
                    cat.label || k,
                    editing ? m("button", {
                        class: "ml-2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-200",
                        title: hidden ? 'Show category' : 'Hide category',
                        'aria-label': hidden ? 'Show category' : 'Hide category',
                        onclick: function (e) { e.stopPropagation(); toggleHidden(k); }
                    }, m("span", {
                        class: "material-symbols-outlined",
                        'aria-hidden': 'true',
                        style: "font-size: 16px"
                    }, hidden ? 'visibility_off' : 'visibility')) : null
                ]),
                m("div", { class: "card-contents" }, modelPanelContents(k))
            )
        );
    });
    return panels;
}

function editToolbar() {
    return m("div", { class: "panel-edit-toolbar" }, [
        m("button", {
            class: "panel-edit-btn" + (editing ? " panel-edit-btn-active" : ""),
            title: editing ? 'Done editing' : 'Customize dashboard',
            onclick: function () { editing = !editing; }
        }, [
            m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 16px" },
                editing ? 'check' : 'dashboard_customize'),
            editing ? 'Done' : 'Customize'
        ]),
        editing ? m("button", {
            class: "panel-edit-btn",
            title: 'Reset to defaults',
            onclick: function () { resetPrefs(); }
        }, [
            m("span", { class: "material-symbols-outlined mr-1", style: "font-size: 16px" }, "restart_alt"),
            'Reset'
        ]) : null
    ]);
}

async function clickPanelItem(item, type) {
    let path = am7view.pathForType(type);
    if (item.prototype) {
        type = item.type;
        path = page.user.homeDirectory.path + "/" + item.group;
    }
    let base = item;
    let baseType = type;
    if (item.limit && item.limit.length) {
        baseType = item.limit[0];
        base = am7model.getModel(baseType);
    }
    let listPathBase = "/list/" + baseType;
    if (am7model.isGroup(base) || base === 'auth.group') {
        am7client.make("auth.group", "data", path, function (v) {
            if (v != null) {
                m.route.set(listPathBase + "/" + v.objectId);
            } else {
                console.error("Error: Unable to load path " + path);
            }
        });
    } else if (am7model.inherits(base, "common.parent") && type.match(/^(auth\.role|auth\.permission)$/gi)) {
        am7client.user(type, "user", function (v) {
            uwm.getDefaultParentForType(type, v).then(function (parObj) {
                if (parObj != null) {
                    m.route.set(listPathBase + "/" + parObj.objectId);
                } else {
                    console.error("Handle parent: " + listPathBase);
                }
            });
        });
    } else if (type.match(/^message$/)) {
        let dir = await page.makePath("auth.group", "data", "~/.messages");
        if (dir != null) {
            m.route.set(listPathBase + "/" + dir.objectId);
        } else {
            console.error("Failed to find .messages");
        }
    } else {
        console.warn("Handle non-group/non-parent or type " + type + " / " + listPathBase, base);
        m.route.set(listPathBase);
    }
}

function getProtoCat(cat, name) {
    if (cat && cat.prototypes) {
        let ap = cat.prototypes.filter(function (c) { return c.name === name; });
        if (ap.length) return ap[0];
    }
}

function modelPanelContents(key) {
    let cat = am7model.categories.find(function (o) { return o.name === key; });
    if (!cat) {
        console.error("Failed to find: " + key);
        return;
    }
    let order = cat.order;
    if (!order) {
        order = [];
        am7model.models.forEach(function (mod) {
            if (mod.categories && mod.categories.includes(key)) {
                order.push(mod.name);
            }
        });
    }
    let items = [];
    order.forEach(function (l) {
        let item = getProtoCat(cat, l) || am7model.getModel(l);
        if (item != null) {
            items.push(
                m("div", { class: "card-item" },
                    m("button", {
                        onclick: function () { clickPanelItem(item, l); }
                    }, [
                        item.icon ? m("span", { class: "material-symbols-outlined material-icons-cm mr-2" }, item.icon) : "",
                        item.label || l
                    ])
                )
            );
        }
    });
    return items;
}

// --- Recent items tracking ---

let recentItems = [];
const MAX_RECENT = 8;

function trackRecent(label, route, icon) {
    recentItems = recentItems.filter(function (r) { return r.route !== route; });
    recentItems.unshift({ label, route, icon });
    if (recentItems.length > MAX_RECENT) recentItems.length = MAX_RECENT;
}

function quickNav() {
    if (!recentItems.length) return null;
    return m("div", { class: "panel-quick-nav" }, [
        m("p", { class: "card-title" }, [
            m("span", { class: "material-symbols-outlined material-icons-cm mr-2" }, "history"),
            "Recent"
        ]),
        m("div", { class: "flex flex-wrap gap-2" },
            recentItems.map(function (r) {
                return m("button", {
                    class: "quick-nav-chip",
                    onclick: function () { m.route.set(r.route); }
                }, [
                    r.icon ? m("span", { class: "material-symbols-outlined material-icons-cm mr-1" }, r.icon) : null,
                    r.label
                ]);
            })
        )
    ]);
}

const panel = {
    clickPanelItem,
    trackRecent,
    view: function () {
        return m("div", { class: "panel-container" }, [
            editToolbar(),
            quickNav(),
            m("div", { class: "panel-grid" },
                modelPanel()
            )
        ]);
    }
};

export { panel };
export default panel;
