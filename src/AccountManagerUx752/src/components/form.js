/**
 * form.js — Form viewer component (ESM)
 * Port of Ux7 client/components/form.js
 *
 * Carousel-based form rendering with grid/single-field modes,
 * validation, save, add/remove rows for grid forms.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getPage() { return am7model._page; }
function getClient() { return am7model._client; }

let entity = {};
let changed = false;
let fullMode = false;
let maxMode = false;
let info = false;
let view;

function updateChange() { changed = true; }

function toggleCarouselFull() { fullMode = !fullMode; m.redraw(); }
function toggleCarouselMax() { maxMode = !maxMode; m.redraw(); }
function toggleInfo() { info = !info; m.redraw(); }

// --- Form element rendering ---

function modelElement(object, element, row) {
    row = row || 0;
    let name = "fe-" + element.elementName + "-" + row;
    let el = [];

    let fHandler = function(e) {
        entity[name] = e.target.value;
        updateChange();
    };

    let cls = "w-full px-2 py-1 border rounded dark:bg-gray-900 dark:border-gray-600 text-sm";

    switch (element.elementType) {
        case "SELECT":
        case "MULTIPLE_SELECT":
            el.push(m("select", {
                class: cls, name, onchange: fHandler,
                value: entity[name] || ''
            }, (element.elementValues || []).map(v =>
                m("option", { value: v.textValue }, v.name)
            )));
            break;
        case "BOOLEAN":
            el.push(m("input", {
                type: "checkbox", name, class: "rounded",
                checked: !!entity[name],
                onchange: (e) => { entity[name] = e.target.checked; updateChange(); }
            }));
            break;
        case "DATE":
            el.push(m("input", {
                type: "datetime-local", name, class: cls,
                value: entity[name] || '',
                onchange: fHandler
            }));
            break;
        case "STRING":
        default:
            el.push(m("input", {
                type: "text", name, class: cls,
                value: entity[name] || '',
                oninput: fHandler
            }));
            break;
    }
    return el;
}

function modelElementContainer(object, element, layout) {
    let widthCls = layout === "full" ? "w-full" : "w-2/3";
    return m("div", { class: widthCls + " mb-3" }, [
        m("label", { class: "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1" },
            element.elementLabel || element.name),
        modelElement(object, element)
    ]);
}

// --- Row management for grid forms ---

function getRowCount(object) {
    return (object.elements.length && object.elements[0].elementValues.length
        ? object.elements[0].elementValues.filter(v => v.formId === object.id).length
        : 1);
}

function addFormRow(object) {
    let row = entity.rowCount || 0;
    object.elements.forEach((e, i) => {
        let name = "fe-" + e.elementName + "-" + row;
        entity[name] = "";
    });
    entity.rowCount = row + 1;
    m.redraw();
}

function removeFormRow(object, index) {
    object.elements.forEach((e) => {
        let name = "fe-" + e.elementName + "-" + index;
        delete entity[name];
    });
    m.redraw();
}

// --- Save ---

async function saveForm(object) {
    let page = getPage();
    let am7client = getClient();

    let row = 0;
    let rowCount = entity.rowCount || 1;
    for (let ri = 0; ri < rowCount; ri++) {
        let testEl = object.elements[0];
        if (!testEl) continue;
        let testName = "fe-" + testEl.elementName + "-" + ri;
        if (typeof entity[testName] === "undefined") continue;

        object.elements.forEach((e) => {
            if (row === 0) e.elementValues = [];
            let ename = "fe-" + e.elementName + "-" + ri;
            let val = { name: ename, textValue: entity[ename] || "", formId: object.id };
            e.elementValues[row] = val;
        });
        row++;
    }

    let updated = await page.patchObject(object);
    am7client.clearCache("FORM", true);
    if (object.objectId) page.clearContextObject(object.objectId);

    if (updated) {
        changed = false;
        if (view && view.closeView) view.closeView();
        else m.redraw();
    } else {
        page.toast("error", "Error updating form");
    }
}

// --- Entity binding ---

function setEntityBinding(object, element, row, index) {
    let ename = "fe-" + element.elementName + "-" + row;
    let e2 = object.elements[index] ? Object.assign({}, object.elements[index]) : null;
    let ev = (e2 ? e2.elementValues : []).filter(e3 => e3.formId === object.id);
    let evv = ev.length > row ? ev[row] : undefined;

    switch (element.elementType) {
        case "BOOLEAN":
            entity[ename] = evv ? evv.textValue === "true" : false;
            break;
        case "DATE":
            entity[ename] = evv && evv.textValue && evv.textValue.length > 0
                ? new Date(evv.textValue).toISOString().slice(0, 16) : "";
            break;
        default:
            entity[ename] = evv ? evv.textValue : "";
    }
}

function setEntity(object) {
    if (!object || object.isTemplate || !object.template) return;
    let rows = getRowCount(object);
    entity.rowCount = rows;
    for (let i = 0; i < rows; i++) {
        (object.template || object).elements.forEach((e, i2) => {
            setEntityBinding(object, e, i, i2);
        });
    }
}

// --- Render ---

function renderForm(object) {
    let page = getPage();
    let formView = [];
    let els = (object.isTemplate ? object : object.template).elements;
    let grid = (object.isTemplate ? object : object.template).isGrid;

    let altCls = changed ? ' text-yellow-500' : '';

    formView.push(m("h3", { class: "text-lg font-semibold mb-2 dark:text-white" }, object.name));
    formView.push(m("div", { class: "flex items-center gap-1 mb-3" }, [
        page.iconButton("p-2 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + altCls, "save", "", () => saveForm(object)),
        grid ? page.iconButton("p-2 rounded hover:bg-gray-200 dark:hover:bg-gray-700", "add", "", () => addFormRow(object)) : null
    ]));

    if (object.isTemplate) {
        formView.push(m("div", { class: "text-gray-500" }, "Form Template View Not Currently Supported"));
    } else if (grid) {
        let rows = entity.rowCount || getRowCount(object);
        let rowElements = [];
        for (let i = 0; i < rows; i++) {
            let skipRow = false;
            let cells = els.map((e, i2) => {
                if (skipRow || typeof entity["fe-" + e.elementName + "-" + i] === "undefined") {
                    skipRow = true;
                    return m("td");
                }
                return m("td", { class: "p-1" }, modelElement(object, e, i));
            });
            cells.push(m("td", { class: "p-1" },
                skipRow ? null : page.iconButton("p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700", "delete_outline", "", () => removeFormRow(object, i))
            ));
            rowElements.push(m("tr", cells));
        }
        formView.push(m("table", { class: "w-full border-collapse text-sm" }, [
            m("thead", m("tr", els.map(a => m("th", { class: "text-left p-1 text-gray-600 dark:text-gray-400 text-xs font-medium" }, a.elementLabel)), m("th"))),
            m("tbody", rowElements)
        ]));
    } else {
        els.forEach(e => { formView.push(modelElementContainer(object, e, "two-thirds")); });
    }
    return formView;
}

function renderInnerFormView(attrs) {
    let page = getPage();
    let object = attrs.object;
    let active = attrs.active;
    let ctx = page.context();

    if (!object || !object[am7model.jsonModelKey]) return "";
    if (!ctx.contextObjects[object.objectId]) return [];

    let objDat = ctx.contextObjects[object.objectId];
    let objView = m("div", { class: "p-4 overflow-auto" }, active ? renderForm(objDat) : "");

    let cls = "w-full" + (active ? "" : " hidden");
    return m("div", { class: cls }, objView);
}

function renderFormView(attrs) {
    let page = getPage();
    return m("div", { class: "flex flex-col h-full" }, [
        fullMode ? null : m(page.components.navigation),
        m("div", { class: "flex-1 overflow-auto p-4" }, [
            renderInnerFormView(attrs),
            m("div", { class: "flex items-center gap-1 absolute top-2 right-2" }, [
                m("button", { class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700", onclick: toggleCarouselFull },
                    m("span", { class: "material-symbols-outlined" }, fullMode ? "close_fullscreen" : "open_in_new")),
                m("button", { class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700", onclick: toggleCarouselMax },
                    m("span", { class: "material-symbols-outlined" }, maxMode ? "photo_size_select_small" : "aspect_ratio")),
                m("button", { class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700", onclick: toggleInfo },
                    m("span", { class: "material-symbols-outlined" }, info ? "info" : "info"))
            ])
        ])
    ]);
}

// --- Init ---

function init(vnode) {
    let page = getPage();
    let attrs = vnode.attrs;
    let object = attrs.object;
    let ctx = page.context();

    if (!ctx.contextObjects[object.objectId]) {
        page.openObject(object[am7model.jsonModelKey], object.objectId).then(() => m.redraw());
    } else {
        let objDat = ctx.contextObjects[object.objectId];
        if (!entity.rowCount && objDat && !objDat.isTemplate) {
            setEntity(objDat);
        }
    }
}

// --- Component ---

const formComponent = {
    oncreate: function(vnode) {
        init(vnode);
        if (vnode.attrs && vnode.attrs.view) view = vnode.attrs.view;
    },
    onupdate: function(vnode) { init(vnode); },
    onremove: function() {
        entity = {};
        changed = false;
        fullMode = false;
        maxMode = false;
        info = false;
        view = null;
    },
    view: function(vnode) {
        if (vnode.attrs.inner) return renderInnerFormView(vnode.attrs);
        return renderFormView(vnode.attrs);
    }
};

const form = { component: formComponent };

export { form };
export default form;
