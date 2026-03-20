/**
 * object_v2.js — Lightweight object view using am7view (ESM)
 * Port of Ux7 client/components/object_v2.js
 *
 * Uses am7view.prepareObjectView() + custom renderers for display.
 * Supports inner (embedded) and outer (carousel) modes.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

function getPage() { return am7model._page; }

let fullMode = false;
let maxMode = false;
let info = false;

function toggleCarouselFull() { fullMode = !fullMode; m.redraw(); }
function toggleCarouselMax() { maxMode = !maxMode; m.redraw(); }
function toggleInfo() { info = !info; m.redraw(); }

function renderInnerObjectView(attrs) {
    let obj = attrs.object;
    let model = attrs[am7model.jsonModelKey] || (obj ? obj[am7model.jsonModelKey] : null) || attrs.model;

    if (!obj || !model) {
        console.warn("Invalid object", obj);
        return "";
    }

    let attrsWithModel = Object.assign({}, attrs);
    attrsWithModel[am7model.jsonModelKey] = model;

    let inst = am7view.prepareObjectView ? am7view.prepareObjectView(obj, attrsWithModel) : null;

    if (!inst) {
        return m("div", { class: "p-4 text-gray-500" }, "Unable to render object: " + (obj.name || obj.objectId));
    }

    let format = inst.entity.viewType;
    let renderer = am7view.customRenderers ? am7view.customRenderers[format] : null;

    if (!renderer) {
        return m("div", { class: "p-4 text-gray-500" }, "No renderer available for: " + (format || "unknown"));
    }

    let content = renderer(inst, "objectId", attrs);
    let cls = attrs.active ? "w-full" : "w-full hidden";
    return m("div", { class: cls }, content);
}

function renderCarouselControls() {
    return m("div", { class: "flex items-center gap-1 absolute top-2 right-2 z-10" }, [
        m("button", { class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700 bg-white/80 dark:bg-gray-800/80", onclick: toggleCarouselFull },
            m("span", { class: "material-symbols-outlined text-sm" }, fullMode ? "close_fullscreen" : "open_in_new")),
        m("button", { class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700 bg-white/80 dark:bg-gray-800/80", onclick: toggleCarouselMax },
            m("span", { class: "material-symbols-outlined text-sm" }, maxMode ? "photo_size_select_small" : "aspect_ratio")),
        m("button", { class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700 bg-white/80 dark:bg-gray-800/80", onclick: toggleInfo },
            m("span", { class: "material-symbols-outlined text-sm" }, "info"))
    ]);
}

function renderObjectView(attrs) {
    let page = getPage();
    return m("div", { class: "flex flex-col h-full" }, [
        fullMode ? null : (page.components.navigation ? m(page.components.navigation) : null),
        m("div", { class: "flex-1 overflow-auto relative" }, [
            renderInnerObjectView(attrs),
            renderCarouselControls()
        ])
    ]);
}

const objectV2Component = {
    onremove: function() {
        fullMode = false;
        maxMode = false;
        info = false;
    },
    view: function(vnode) {
        if (vnode.attrs.inner) return renderInnerObjectView(vnode.attrs);
        return renderObjectView(vnode.attrs);
    }
};

const object_v2 = { component: objectV2Component };

export { object_v2 };
export default object_v2;
