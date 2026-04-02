/**
 * tableListEditor.js — Compact list + pop-in editor panel for array/table fields (ESM)
 * Port of Ux7 client/components/tableListEditor.js
 *
 * Replaces inline table editing with a compact list, per-row edit/delete icons,
 * search/filter, client-side pagination, and a full-width editor panel.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

// ── Per-field State ─────────────────────────────────────────────────

let fieldStates = {};
let standardFuncs = ['newEntry', 'editEntry', 'checkEntry', 'deleteEntry', 'cancelEntry'];

function isStandardCrudForm(tableForm) {
    if (!tableForm || !tableForm.commands) return true;
    return Object.values(tableForm.commands).every(function(cmd) {
        return standardFuncs.includes(cmd.function) || standardFuncs.includes(cmd.altFunction);
    });
}

function getFieldState(fieldName) {
    if (!fieldStates[fieldName]) {
        fieldStates[fieldName] = {
            activeId: null,
            mode: 'closed',
            filterText: '',
            currentPage: 0,
            pageSize: 20,
            editEntry: null,
            editIndex: null,
            editInstance: null,
            highlightId: null
        };
    }
    return fieldStates[fieldName];
}

function getEntryId(entry) {
    if (entry.objectId) return entry.objectId;
    if (entry._localId) return entry._localId;
    let page = getPage();
    entry._localId = page ? page.luid() : ('_' + Math.random().toString(36).slice(2));
    return entry._localId;
}

function getSummaryFields(tableForm) {
    let keys = Object.keys(tableForm.fields);
    let nameIdx = keys.indexOf('name');
    if (nameIdx < 0) nameIdx = keys.indexOf('sectionName');
    if (nameIdx > 0) {
        let nm = keys.splice(nameIdx, 1)[0];
        keys.unshift(nm);
    }
    return keys.slice(0, 3);
}

function formatSummaryValue(val) {
    if (val === undefined || val === null) return '';
    if (Array.isArray(val)) return val.length + ' items';
    val = '' + val;
    if (val.length > 50) return val.substring(0, 47) + '...';
    return val;
}

// ── Data Access ─────────────────────────────────────────────────────

function getValues(ctx) {
    let name = ctx.useName;
    let field = ctx.field;
    let entity = ctx.entity;

    if (field.foreign) {
        if (ctx.foreignData && ctx.foreignData[name]) {
            return ctx.foreignData[name];
        }
        if (field.function && ctx.objectPage && ctx.objectPage[field.function]) {
            ctx.objectPage[field.function](name, field).then(function(data) {
                if (ctx.foreignData) ctx.foreignData[name] = data;
                m.redraw();
            });
            return null;
        }
        return null;
    }

    // Referenced fields (e.g., attributes): getFull doesn't populate them.
    // Re-query with explicit field request, cache in foreignData.
    if (field.referenced && entity && entity.objectId) {
        let cacheKey = '_ref_' + name;
        if (ctx.foreignData && ctx.foreignData[cacheKey] !== undefined) {
            return ctx.foreignData[cacheKey];
        }
        if (ctx.foreignData) {
            ctx.foreignData[cacheKey] = null;
            let entityType = entity[am7model.jsonModelKey];
            let q = getClient().newQuery(entityType);
            q.field('objectId', entity.objectId);
            q.entity.request = ['id', 'objectId', name];
            getPage().search(q).then(function(qr) {
                if (qr && qr.results && qr.results.length && Array.isArray(qr.results[0][name])) {
                    ctx.foreignData[cacheKey] = qr.results[0][name];
                    // Also set on entity so prepareInstance sees it
                    entity[name] = ctx.foreignData[cacheKey];
                } else {
                    ctx.foreignData[cacheKey] = [];
                }
                m.redraw();
            }).catch(function() { ctx.foreignData[cacheKey] = []; m.redraw(); });
        }
        return null;
    }

    if (ctx.inst && ctx.inst.api[name]) return ctx.inst.api[name]();
    if (field.parentProperty && entity[field.parentProperty]) {
        return entity[field.parentProperty][name];
    }
    return entity ? entity[name] : null;
}

function resolveTableType(ctx) {
    let tableType = ctx.field.baseModel;
    if (tableType === '$self') {
        tableType = ctx.entity[am7model.jsonModelKey];
    }
    return tableType;
}

// ── Search + Pagination ─────────────────────────────────────────────

function getFilteredItems(items, fs) {
    if (!fs.filterText || !fs.filterText.trim()) return items;
    let ft = fs.filterText.toLowerCase().trim();
    return items.filter(function(item) {
        return Object.keys(item).some(function(k) {
            if (k.startsWith('_') || k === am7model.jsonModelKey) return false;
            let v = item[k];
            if (v === null || v === undefined) return false;
            return ('' + v).toLowerCase().indexOf(ft) >= 0;
        });
    });
}

function getPagedItems(items, fs) {
    if (items.length <= fs.pageSize) return items;
    let start = fs.currentPage * fs.pageSize;
    return items.slice(start, start + fs.pageSize);
}

function renderPagination(filteredItems, fs) {
    let page = getPage();
    let totalPages = Math.ceil(filteredItems.length / fs.pageSize);
    if (totalPages <= 1) return '';

    return m("div", { class: "flex items-center justify-between px-2 py-1 text-xs text-gray-500 dark:text-gray-400" }, [
        m("span", "Page " + (fs.currentPage + 1) + " of " + totalPages + " (" + filteredItems.length + " items)"),
        m("div", { class: "flex gap-1" }, [
            m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (fs.currentPage > 0 ? "" : " opacity-30 pointer-events-none"),
                onclick: function() { if (fs.currentPage > 0) { fs.currentPage--; m.redraw(); } }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "chevron_left")),
            m("button", {
                class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700" + (fs.currentPage < totalPages - 1 ? "" : " opacity-30 pointer-events-none"),
                onclick: function() { if (fs.currentPage < totalPages - 1) { fs.currentPage++; m.redraw(); } }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "chevron_right"))
        ])
    ]);
}

// ── Editor Operations ───────────────────────────────────────────────

function openEditor(fs, entry, index, ctx) {
    let tableForm = ctx.fieldView.form;
    // Ensure schema is set on the entry (list serialization may strip it after first item)
    if (!entry[am7model.jsonModelKey]) {
        let tableType = resolveTableType(ctx);
        if (tableType) entry[am7model.jsonModelKey] = tableType;
    }
    fs.editEntry = entry;
    fs.editIndex = index;
    fs.activeId = getEntryId(entry);
    fs.highlightId = fs.activeId;
    fs.editInstance = am7model.prepareInstance(entry, tableForm);
    fs.mode = 'editing';
    m.redraw();
}

function openNewEditor(fs, ctx) {
    let name = ctx.useName;
    let entity = ctx.entity;
    let tableType = resolveTableType(ctx);
    let tableForm = ctx.fieldView.form;
    let am7client = getClient();
    let page = getPage();

    if (!entity || !entity[am7model.jsonModelKey]) {
        console.error("Entity not available");
        return;
    }

    let mf = am7model.getModelField(entity[am7model.jsonModelKey], name);
    if (!mf) {
        console.error("Object does not define '" + name + "'");
        return;
    }

    let ne;
    if (name === 'attributes') {
        ne = am7client.newAttribute("", "");
    } else {
        ne = am7model.newPrimitive(mf.baseModel || tableType);
    }
    ne._localId = page ? page.luid() : ('_' + Math.random().toString(36).slice(2));

    if (!entity[name]) entity[name] = [];
    entity[name].push(ne);
    if (ctx.inst && !ctx.inst.changes.includes(name)) ctx.inst.changes.push(name);

    fs.editEntry = ne;
    fs.editIndex = entity[name].length - 1;
    fs.activeId = ne._localId;
    fs.highlightId = fs.activeId;
    fs.editInstance = am7model.prepareInstance(ne, tableForm);
    fs.mode = 'creating';
    m.redraw();
}

function closeEditor(fs) {
    fs.activeId = null;
    fs.highlightId = null;
    fs.editEntry = null;
    fs.editIndex = null;
    fs.editInstance = null;
    fs.mode = 'closed';
    m.redraw();
}

function cancelEditor(fs, ctx) {
    if (fs.mode === 'creating' && fs.editEntry) {
        let name = ctx.useName;
        let entity = ctx.entity;
        if (entity && entity[name] && fs.editIndex !== null) {
            entity[name].splice(fs.editIndex, 1);
        }
    }
    closeEditor(fs);
}

function extractEditorValues(fs, tableForm, tableType) {
    let entry = fs.editEntry;
    let einst = fs.editInstance;

    Object.keys(tableForm.fields).forEach(function(fk) {
        let tableFieldView = tableForm.fields[fk];
        let tableField = tableFieldView.field || am7model.getModelField(tableType, fk);
        if (!tableField) return;

        let inputName = fk + "-tle-" + (fs.activeId || 'new');
        let e = document.querySelector("[name='" + inputName + "']");
        if (!e) return;
        if (tableField.virtual) return;

        let useType = tableField.type;
        if (useType === 'flex' && entry.valueType) {
            useType = entry.valueType.toLowerCase();
        }

        switch (useType) {
            case 'boolean':
                entry[fk] = (e.value.match(/^true$/gi) != null);
                break;
            case 'int':
                entry[fk] = parseInt(e.value);
                break;
            case 'double':
            case 'long':
                entry[fk] = parseFloat(e.value);
                break;
            case 'enum':
            case 'string':
                entry[fk] = e.value;
                break;
            case 'list':
            case 'array':
                if (einst && einst.api && einst.api[fk]) {
                    einst.api[fk](e.value);
                }
                break;
            default:
                if (e.value !== undefined) entry[fk] = e.value;
                break;
        }
    });
}

function persistEntry(ctx, tableType, entry, tableForm) {
    let name = ctx.useName;
    let entity = ctx.entity;
    let am7client = getClient();
    let formName = tableType.substring(tableType.lastIndexOf(".") + 1);
    let fieldView = am7model.forms[formName];
    let parentField = am7model.getModelField(entity[am7model.jsonModelKey], name);
    let aP = [];

    if ((parentField?.foreign || parentField?.referenced) && !fieldView?.standardUpdate && am7model.hasIdentity(entity)) {
        if (entry.objectId) {
            aP.push(am7client.patch(entry));
        } else {
            entry[am7model.jsonModelKey] = tableType;
            if (am7model.inherits(tableType, "common.reference")) {
                entry.referenceId = entity.id;
                entry.referenceModel = entity[am7model.jsonModelKey];
            }
            aP.push(am7client.create(tableType, entry));
        }
    }
    else if ((!parentField?.foreign && !parentField?.referenced) || fieldView?.standardUpdate) {
        if (ctx.inst) ctx.inst.change(name);
    }

    return Promise.all(aP);
}

function saveEntry(fs, ctx) {
    let tableType = resolveTableType(ctx);
    let tableForm = ctx.fieldView.form;
    extractEditorValues(fs, tableForm, tableType);
    persistEntry(ctx, tableType, fs.editEntry, tableForm).then(function() {
        closeEditor(fs);
    });
}

function deleteEntryDirect(fs, entry, index, ctx) {
    let name = ctx.useName;
    let entity = ctx.entity;
    let page = getPage();
    let aP = [];

    // Close editor if deleting the entry being edited
    if (fs.activeId === getEntryId(entry)) {
        fs.activeId = null;
        fs.highlightId = null;
        fs.editEntry = null;
        fs.editIndex = null;
        fs.editInstance = null;
        fs.mode = 'closed';
    }

    if (name === 'controls') {
        let model = page.context();
        if (model.controls && model.controls[entity.objectId]) {
            let ctl = model.controls[entity.objectId][index];
            delete model.controls[entity.objectId][index];
            if (ctl && ctl.objectId) {
                aP.push(new Promise(function(res) {
                    getClient().delete("CONTROL", ctl.objectId, function() { res(); });
                }));
            }
        }
    } else {
        // Generic array-based delete
        if (entity[name]) {
            entity[name] = entity[name].filter(function(a, i) {
                return i !== index;
            });
        }
        if (entry && am7model.hasIdentity(entry)) {
            aP.push(page.deleteObject(entry[am7model.jsonModelKey], entry.objectId));
        } else if (ctx.inst) {
            ctx.inst.change(name);
        }
    }

    return Promise.all(aP).then(function() {
        m.redraw();
    });
}

// ── Rendering ───────────────────────────────────────────────────────

function renderListRow(entry, index, fs, tableForm, ctx) {
    let entryId = getEntryId(entry);
    let isHighlighted = (fs.highlightId === entryId);
    let summaryFields = getSummaryFields(tableForm);
    let cmds = tableForm.commands || {};
    let hasEdit = !!cmds.edit;
    let hasDelete = !!cmds.delete;

    let summaryParts = summaryFields.map(function(fk) {
        return formatSummaryValue(entry[fk]);
    }).filter(function(v) { return v.length > 0; });

    let summaryText = summaryParts.join(' \u00b7 ');

    let actions = [];
    if (hasEdit) {
        actions.push(m("button", {
            class: "p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300",
            title: "Edit",
            onclick: function(e) {
                e.stopPropagation();
                if (fs.mode !== 'closed') return;
                openEditor(fs, entry, index, ctx);
            }
        }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "edit")));
    }
    if (hasDelete) {
        actions.push(m("button", {
            class: "p-0.5 rounded hover:bg-red-100 dark:hover:bg-red-900/30 text-gray-400 hover:text-red-500",
            title: "Delete",
            onclick: function(e) {
                e.stopPropagation();
                if (fs.mode !== 'closed' && fs.activeId !== entryId) return;
                deleteEntryDirect(fs, entry, index, ctx);
            }
        }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "delete")));
    }

    return m("div", {
        class: "flex items-center justify-between px-2 py-1.5 border-b border-gray-100 dark:border-gray-700/50 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800/50"
            + (isHighlighted ? " bg-blue-50 dark:bg-blue-900/20" : ""),
        ondblclick: function(e) {
            e.preventDefault();
            if (!hasEdit || fs.mode !== 'closed') return;
            openEditor(fs, entry, index, ctx);
        },
        onclick: function() {
            if (fs.mode !== 'closed') return;
            fs.highlightId = (fs.highlightId === entryId) ? null : entryId;
            m.redraw();
        }
    }, [
        m("span", { class: "text-sm text-gray-700 dark:text-gray-300 truncate flex-1" }, summaryText || "(empty)"),
        actions.length ? m("span", { class: "flex gap-0.5 shrink-0 ml-2" }, actions) : null
    ]);
}

function renderEditorPanel(fs, tableForm, tableType, ctx) {
    let isOpen = (fs.mode === 'editing' || fs.mode === 'creating');
    if (!isOpen || !fs.editEntry) {
        return null;
    }

    let entry = fs.editEntry;
    let formFields = [];

    Object.keys(tableForm.fields).forEach(function(fk) {
        let tableFieldView = tableForm.fields[fk];
        let tableField = tableFieldView.field || am7model.getModelField(tableType, fk);
        if (!tableField && ctx.field.foreignType && ctx.entity[ctx.field.foreignType]) {
            tableField = am7model.getModelField(tableType, fk, am7view.typeToModel(ctx.entity[ctx.field.foreignType]));
        }
        if (!tableField) return;

        let layout = tableFieldView.layout || "full";
        let layoutClass = layout === "half" ? "col-span-3"
            : layout === "third" ? "col-span-2"
            : layout === "two-thirds" ? "col-span-4"
            : "col-span-6";

        let fieldLabel = tableFieldView.label || tableField.label || fk;
        let inputName = fk + "-tle-" + (fs.activeId || 'new');

        let fieldContent;
        if (tableFieldView.format === 'textlist') {
            // Direct textarea rendering for textlist fields (list<string> displayed as multiline text)
            let val = entry[fk];
            if (Array.isArray(val)) val = val.join('\n');
            else if (val === undefined || val === null) val = '';
            fieldContent = m("textarea", {
                class: "w-full px-2 py-1 text-sm border border-gray-300 dark:border-gray-600 rounded bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100",
                name: inputName, rows: 6,
                value: val,
                oninput: function(e) { entry[fk] = e.target.value.split('\n'); }
            });
        } else if (ctx.modelField) {
            let cv = fs.editInstance;
            let defVal = (cv && cv.api && cv.api[fk]) ? cv.api[fk]() : entry[fk];
            fieldContent = ctx.modelField(fk, tableFieldView, tableField, inputName, defVal, true, entry, tableForm);
        } else {
            // Fallback: simple text input
            let val = entry[fk];
            if (val === undefined || val === null) val = '';
            if (Array.isArray(val)) val = val.join('\n');
            fieldContent = m("input", {
                class: "w-full px-2 py-1 text-sm border border-gray-300 dark:border-gray-600 rounded bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100",
                name: inputName,
                type: "text",
                value: '' + val
            });
        }

        formFields.push(m("div", { class: layoutClass }, [
            m("label", { class: "block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5", for: inputName }, fieldLabel),
            fieldContent
        ]));
    });

    return m("div", {
        class: "border border-blue-200 dark:border-blue-800 rounded-b bg-blue-50/30 dark:bg-blue-900/10 p-3 mb-1"
    }, [
        m("div", { class: "grid grid-cols-6 gap-2" }, formFields),
        m("div", { class: "flex gap-2 mt-2 pt-2 border-t border-gray-200 dark:border-gray-700" }, [
            m("button", {
                class: "inline-flex items-center gap-1 px-3 py-1 text-xs font-medium text-white bg-blue-600 hover:bg-blue-700 rounded",
                onclick: function() { saveEntry(fs, ctx); }
            }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "check"),
                "Save"
            ]),
            m("button", {
                class: "inline-flex items-center gap-1 px-3 py-1 text-xs font-medium text-gray-600 dark:text-gray-300 bg-gray-200 dark:bg-gray-700 hover:bg-gray-300 dark:hover:bg-gray-600 rounded",
                onclick: function() { cancelEditor(fs, ctx); }
            }, [
                m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "close"),
                "Cancel"
            ])
        ])
    ]);
}

function renderToolbar(fs, items, ctx, hidden) {
    if (hidden) return null;

    let hasCommands = ctx.fieldView && ctx.fieldView.form && ctx.fieldView.form.commands;
    let hasNew = hasCommands && ctx.fieldView.form.commands.new;
    let totalCount = items ? items.length : 0;

    let toolbarItems = [];

    if (hasNew) {
        toolbarItems.push(m("button", {
            class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-500 hover:text-gray-700 dark:hover:text-gray-300"
                + (fs.mode !== 'closed' ? " opacity-30 pointer-events-none" : ""),
            title: "Add new",
            onclick: function() {
                if (fs.mode !== 'closed') return;
                openNewEditor(fs, ctx);
            }
        }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "add")));
    }

    if (totalCount >= 10) {
        toolbarItems.push(m("input", {
            class: "flex-1 px-2 py-1 text-xs border border-gray-300 dark:border-gray-600 rounded bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100",
            type: "text",
            placeholder: "Filter...",
            value: fs.filterText,
            oninput: function(e) {
                fs.filterText = e.target.value;
                fs.currentPage = 0;
                m.redraw();
            }
        }));
    }

    if (totalCount > 0) {
        toolbarItems.push(m("span", { class: "text-xs text-gray-400 dark:text-gray-500 whitespace-nowrap" },
            totalCount + " item" + (totalCount !== 1 ? "s" : "")
        ));
    }

    return m("div", { class: "flex items-center gap-2 px-2 py-1 border-b border-gray-200 dark:border-gray-700" }, toolbarItems);
}

// ── Main Render ─────────────────────────────────────────────────────

function render(ctx) {
    let name = ctx.useName;
    let field = ctx.field;
    let fieldView = ctx.fieldView;
    let entity = ctx.entity;
    let hidden = !ctx.show;

    if (!entity || !field || !field.baseModel || !fieldView || !fieldView.form) {
        return [];
    }

    let tableType = resolveTableType(ctx);
    let tableForm = fieldView.form;
    let fs = getFieldState(name);

    // Reset state if entity changed (navigated to different object)
    let entityKey = entity.objectId || entity._localId || entity.id;
    if (fs._entityKey && fs._entityKey !== entityKey) {
        fieldStates[name] = null;
        fs = getFieldState(name);
    }
    fs._entityKey = entityKey;

    let items = getValues(ctx);
    if (items === null) {
        return [m("div", { class: "rounded border border-gray-200 dark:border-gray-700" },
            m("div", { class: "px-3 py-2 text-sm text-gray-400" }, "Loading...")
        )];
    }
    if (!items) items = [];
    // Restore schema on list items (list serialization may strip after first)
    if (items.length > 0 && tableType) {
        for (let i = 0; i < items.length; i++) {
            if (items[i] && !items[i][am7model.jsonModelKey]) {
                items[i][am7model.jsonModelKey] = tableType;
            }
        }
    }

    let filtered = getFilteredItems(items, fs);
    let paged = getPagedItems(filtered, fs);

    let totalPages = Math.ceil(filtered.length / fs.pageSize);
    if (totalPages > 0 && fs.currentPage >= totalPages) {
        fs.currentPage = totalPages - 1;
    }

    // Build list rows, inject editor panel after active row
    let listRows = [];
    if (paged.length === 0) {
        listRows.push(m("div", { class: "px-3 py-2 text-sm text-gray-400 italic" },
            fs.filterText ? "No matching items" : "No items"
        ));
    } else {
        paged.forEach(function(entry, pageIdx) {
            let realIndex = items.indexOf(entry);
            listRows.push(renderListRow(entry, realIndex, fs, tableForm, ctx));
            if (fs.activeId === getEntryId(entry)) {
                listRows.push(renderEditorPanel(fs, tableForm, tableType, ctx));
            }
        });
    }

    let parts = [];
    parts.push(renderToolbar(fs, items, ctx, hidden));
    if (!hidden) {
        parts.push(m("div", { class: "max-h-64 overflow-y-auto" }, listRows));
        parts.push(renderPagination(filtered, fs));
        // New entry editor goes after list if not already inline
        if (fs.mode === 'creating' && !paged.some(function(e) { return getEntryId(e) === fs.activeId; })) {
            parts.push(renderEditorPanel(fs, tableForm, tableType, ctx));
        }
    }

    return [m("div", { class: "rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900" }, parts)];
}

// ── Export ───────────────────────────────────────────────────────────

const tableListEditor = {
    isStandardCrud: isStandardCrudForm,
    resetState: function(fieldName) {
        if (fieldName) delete fieldStates[fieldName];
        else fieldStates = {};
    },
    render
};

export { tableListEditor };
export default tableListEditor;
