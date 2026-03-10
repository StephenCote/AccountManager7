/**
 * Schema feature — Model schema browser + form definition editor + schema write (admin only)
 * Phase 6: Browse model schemas via /rest/schema endpoints
 * Phase 7: CRUD form definitions via /rest/model (system.formDefinition)
 * Phase 13: Schema write — create/update/delete user-defined models and fields
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { layout, pageLayout } from '../router.js';
import { applicationPath } from '../core/config.js';

// ── Schema API ──────────────────────────────────────────────────────

let sSchemaBase = applicationPath + "/rest/schema";

async function fetchModelNames() {
    return m.request({ method: 'GET', url: sSchemaBase + "/models", withCredentials: true });
}

async function fetchModelSchema(type) {
    return m.request({ method: 'GET', url: sSchemaBase + "/" + type, withCredentials: true });
}

async function fetchModelFields(type) {
    return m.request({ method: 'GET', url: sSchemaBase + "/" + type + "/fields", withCredentials: true });
}

// ── Schema Write API (Phase 13) ─────────────────────────────────────

async function createModelSchema(schemaObj) {
    return m.request({
        method: 'POST',
        url: sSchemaBase,
        body: schemaObj,
        withCredentials: true
    });
}

async function addFieldsToModel(type, fields) {
    return m.request({
        method: 'PUT',
        url: sSchemaBase + "/" + type,
        body: { fields: fields },
        withCredentials: true
    });
}

async function deleteModel(type) {
    return m.request({
        method: 'DELETE',
        url: sSchemaBase + "/" + type,
        withCredentials: true
    });
}

async function deleteField(type, fieldName) {
    return m.request({
        method: 'DELETE',
        url: sSchemaBase + "/" + type + "/field/" + fieldName,
        withCredentials: true
    });
}

// ── State ───────────────────────────────────────────────────────────

let modelNames = [];
let loading = false;
let error = null;
let filterText = "";
let namespaceFilter = "";

// Detail view state
let selectedType = null;
let selectedSchema = null;
let selectedFields = null;
let detailLoading = false;
let detailError = null;
let detailTab = "fields"; // "fields" | "properties" | "inheritance"
let fieldFilter = "";
let showInherited = true;

// Form editor state (Phase 7)
let editorMode = false; // false = browser, true = form editor
let formDefinitions = [];
let selectedFormDef = null;
let formEditorLoading = false;
let formEditorDirty = false;

// Schema write state (Phase 13)
let showCreateModel = false;
let newModelName = "";
let newModelInherits = "data.directory";
let newModelGroup = "";
let createModelLoading = false;

let showAddField = false;
let newFieldName = "";
let newFieldType = "string";
let newFieldDefault = "";
let addFieldLoading = false;

// ── Helpers ─────────────────────────────────────────────────────────

function getNamespaces(names) {
    let ns = new Set();
    names.forEach(function (n) {
        let parts = n.split(".");
        if (parts.length > 1) ns.add(parts.slice(0, -1).join("."));
    });
    return Array.from(ns).sort();
}

function getFilteredModels() {
    let filtered = modelNames;
    if (namespaceFilter) {
        filtered = filtered.filter(function (n) { return n.startsWith(namespaceFilter + "."); });
    }
    if (filterText) {
        let lower = filterText.toLowerCase();
        filtered = filtered.filter(function (n) { return n.toLowerCase().indexOf(lower) !== -1; });
    }
    return filtered;
}

function fieldTypeLabel(field) {
    let label = field.type || "unknown";
    if (field.baseModel) label += " (" + field.baseModel + ")";
    else if (field.baseType) label += " (" + field.baseType + ")";
    else if (field.baseClass) {
        let cls = field.baseClass.split(".");
        label += " (" + cls[cls.length - 1] + ")";
    }
    return label;
}

function fieldBadges(field) {
    let badges = [];
    if (field.system === false) badges.push({ label: "user", color: "text-green-600 bg-green-50 dark:bg-green-900/30" });
    if (field.required) badges.push({ label: "required", color: "text-red-600 bg-red-50 dark:bg-red-900/30" });
    if (field.identity) badges.push({ label: "identity", color: "text-blue-600 bg-blue-50 dark:bg-blue-900/30" });
    if (field.foreign) badges.push({ label: "foreign", color: "text-purple-600 bg-purple-50 dark:bg-purple-900/30" });
    if (field.virtual) badges.push({ label: "virtual", color: "text-gray-500 bg-gray-100 dark:bg-gray-800" });
    if (field.ephemeral) badges.push({ label: "ephemeral", color: "text-yellow-600 bg-yellow-50 dark:bg-yellow-900/30" });
    if (field.restricted) badges.push({ label: "restricted", color: "text-orange-600 bg-orange-50 dark:bg-orange-900/30" });
    return badges;
}

function isSystemModel(schema) {
    return schema && schema.system !== false;
}

function isSystemField(field) {
    return field && field.system !== false;
}

let FIELD_TYPES = ["string", "int", "long", "double", "boolean", "enum", "blob", "timestamp", "zonetime", "list"];

// ── Data loading ────────────────────────────────────────────────────

async function loadModelNames() {
    loading = true;
    error = null;
    try {
        modelNames = await fetchModelNames();
        if (!Array.isArray(modelNames)) modelNames = [];
        modelNames.sort();
    } catch (e) {
        error = "Failed to load model names: " + (e.message || e);
        modelNames = [];
    }
    loading = false;
    m.redraw();
}

async function loadModelDetail(type) {
    selectedType = type;
    detailLoading = true;
    detailError = null;
    detailTab = "fields";
    fieldFilter = "";
    showAddField = false;
    try {
        let results = await Promise.all([fetchModelSchema(type), fetchModelFields(type)]);
        selectedSchema = results[0];
        selectedFields = results[1];
        if (!Array.isArray(selectedFields)) selectedFields = [];
    } catch (e) {
        detailError = "Failed to load schema for " + type + ": " + (e.message || e);
        selectedSchema = null;
        selectedFields = null;
    }
    detailLoading = false;
    m.redraw();
}

function backToList() {
    selectedType = null;
    selectedSchema = null;
    selectedFields = null;
    detailError = null;
    showAddField = false;
}

// ── Schema Write Operations (Phase 13) ──────────────────────────────

async function doCreateModel() {
    if (!newModelName || !newModelName.trim()) {
        page.toast("error", "Model name is required");
        return;
    }
    let name = newModelName.trim();
    if (!/^[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z][a-zA-Z0-9]*)+$/.test(name)) {
        page.toast("error", "Invalid model name. Use namespaced format (e.g. custom.myModel)");
        return;
    }

    createModelLoading = true;
    m.redraw();
    try {
        let inherits = newModelInherits.trim().split(/\s*,\s*/).filter(function (s) { return s.length > 0; });
        let schema = {
            name: name,
            inherits: inherits.length > 0 ? inherits : ["data.directory"],
            group: newModelGroup.trim() || name.split(".").pop(),
            version: "1.0",
            fields: []
        };
        await createModelSchema(schema);
        page.toast("info", "Model '" + name + "' created");
        newModelName = "";
        newModelInherits = "data.directory";
        newModelGroup = "";
        showCreateModel = false;
        await loadModelNames();
        loadModelDetail(name);
    } catch (e) {
        let msg = (e.response && e.response.error) ? e.response.error : (e.message || "Unknown error");
        page.toast("error", "Failed to create model: " + msg);
    }
    createModelLoading = false;
    m.redraw();
}

async function doDeleteModel(type) {
    if (!confirm("Delete model '" + type + "'? This will drop the database table and cannot be undone.")) return;
    try {
        await deleteModel(type);
        page.toast("info", "Model '" + type + "' deleted");
        backToList();
        await loadModelNames();
    } catch (e) {
        let msg = (e.response && e.response.error) ? e.response.error : (e.message || "Unknown error");
        page.toast("error", "Failed to delete model: " + msg);
    }
    m.redraw();
}

async function doAddField() {
    if (!newFieldName || !newFieldName.trim()) {
        page.toast("error", "Field name is required");
        return;
    }
    let name = newFieldName.trim();
    if (!/^[a-zA-Z][a-zA-Z0-9]*$/.test(name)) {
        page.toast("error", "Invalid field name. Use camelCase alphanumeric characters.");
        return;
    }

    addFieldLoading = true;
    m.redraw();
    try {
        let field = {
            name: name,
            type: newFieldType
        };
        if (newFieldDefault.trim()) {
            let dv = newFieldDefault.trim();
            if (newFieldType === "int" || newFieldType === "long") field["default"] = parseInt(dv) || 0;
            else if (newFieldType === "double") field["default"] = parseFloat(dv) || 0.0;
            else if (newFieldType === "boolean") field["default"] = dv === "true";
            else field["default"] = dv;
        }
        await addFieldsToModel(selectedType, [field]);
        page.toast("info", "Field '" + name + "' added to " + selectedType);
        newFieldName = "";
        newFieldType = "string";
        newFieldDefault = "";
        showAddField = false;
        await loadModelDetail(selectedType);
    } catch (e) {
        let msg = (e.response && e.response.error) ? e.response.error : (e.message || "Unknown error");
        page.toast("error", "Failed to add field: " + msg);
    }
    addFieldLoading = false;
    m.redraw();
}

async function doDeleteField(fieldName) {
    if (!confirm("Delete field '" + fieldName + "' from " + selectedType + "? This will drop the database column.")) return;
    try {
        await deleteField(selectedType, fieldName);
        page.toast("info", "Field '" + fieldName + "' removed from " + selectedType);
        await loadModelDetail(selectedType);
    } catch (e) {
        let msg = (e.response && e.response.error) ? e.response.error : (e.message || "Unknown error");
        page.toast("error", "Failed to delete field: " + msg);
    }
    m.redraw();
}

// ── Form Definition CRUD (Phase 7) ─────────────────────────────────

async function loadFormDefinitions() {
    formEditorLoading = true;
    try {
        let dir = await page.makePath("auth.group", "data", "~/FormDefinitions");
        if (dir) {
            let result = await am7client.list("system.formDefinition", dir.objectId, 0, 100);
            formDefinitions = (result && result.results) ? result.results : [];
        } else {
            formDefinitions = [];
        }
    } catch (e) {
        formDefinitions = [];
        page.toast("error", "Failed to load form definitions");
    }
    formEditorLoading = false;
    m.redraw();
}

async function createFormDef(modelType) {
    try {
        let dir = await page.makePath("auth.group", "data", "~/FormDefinitions");
        if (!dir) { page.toast("error", "Failed to create directory"); return; }

        // Populate fields from schema
        let schemaFields = [];
        try {
            schemaFields = await fetchModelFields(modelType);
        } catch (e) { /* schema may not be available for non-admin types */ }

        let fields = [];
        if (Array.isArray(schemaFields)) {
            schemaFields.forEach(function (sf, idx) {
                if (sf.identity || sf.virtual || sf.ephemeral) return;
                fields.push({
                    schema: "system.formField",
                    fieldName: sf.name,
                    label: sf.name,
                    order: idx,
                    visible: true,
                    required: !!sf.required,
                    layout: "half"
                });
            });
        }

        let rec = {
            schema: "system.formDefinition",
            name: modelType + " Form",
            modelType: modelType,
            groupId: dir.id,
            groupPath: dir.path,
            columns: 6,
            fields: fields
        };

        let created = await am7client.create("system.formDefinition", rec);
        if (created) {
            await loadFormDefinitions();
            // Select the newly created one
            let found = formDefinitions.find(function (fd) { return fd.objectId === created.objectId; });
            if (found) {
                await selectFormDef(found);
            }
            page.toast("info", "Form definition created for " + modelType);
        }
    } catch (e) {
        page.toast("error", "Failed to create form definition: " + (e.message || e));
    }
}

async function selectFormDef(fd) {
    formEditorLoading = true;
    try {
        let full = await am7client.getFull("system.formDefinition", fd.objectId);
        selectedFormDef = full || fd;
        if (selectedFormDef.fields && !Array.isArray(selectedFormDef.fields)) {
            selectedFormDef.fields = [];
        }
        formEditorDirty = false;
    } catch (e) {
        selectedFormDef = fd;
    }
    formEditorLoading = false;
    m.redraw();
}

async function saveFormDef() {
    if (!selectedFormDef) return;
    try {
        await am7client.patch("system.formDefinition", selectedFormDef);
        formEditorDirty = false;
        page.toast("info", "Form definition saved");
    } catch (e) {
        page.toast("error", "Failed to save: " + (e.message || e));
    }
    m.redraw();
}

async function deleteFormDef(fd) {
    if (!confirm("Delete form definition '" + (fd.name || fd.objectId) + "'?")) return;
    try {
        await am7client.delete("system.formDefinition", fd);
        if (selectedFormDef && selectedFormDef.objectId === fd.objectId) {
            selectedFormDef = null;
        }
        await loadFormDefinitions();
        page.toast("info", "Form definition deleted");
    } catch (e) {
        page.toast("error", "Failed to delete");
    }
}

function markDirty() {
    formEditorDirty = true;
}

// ── Render: Model List (Phase 6 + Phase 13 create) ──────────────────

function renderModelList() {
    let filtered = getFilteredModels();
    let namespaces = getNamespaces(modelNames);

    return m("div", { class: "space-y-4" }, [
        // Header
        m("div", { class: "flex items-center justify-between" }, [
            m("h1", { class: "text-2xl font-bold text-gray-800 dark:text-white" }, "Model Schema Browser"),
            m("div", { class: "flex items-center gap-2" }, [
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm " + (editorMode ? "bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300" : "bg-blue-600 text-white"),
                    onclick: function () { editorMode = false; }
                }, "Schema"),
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm " + (editorMode ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300"),
                    onclick: function () {
                        editorMode = true;
                        if (formDefinitions.length === 0) loadFormDefinitions();
                    }
                }, "Form Editor"),
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm bg-green-600 text-white hover:bg-green-500",
                    onclick: function () { showCreateModel = !showCreateModel; }
                }, "+ New Model")
            ])
        ]),

        // Create model form (Phase 13)
        showCreateModel ? renderCreateModelForm() : null,

        // Filter bar
        m("div", { class: "flex gap-2" }, [
            m("input", {
                type: "text",
                class: "flex-1 px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm",
                placeholder: "Filter models...",
                value: filterText,
                oninput: function (e) { filterText = e.target.value; }
            }),
            m("select", {
                class: "px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-sm",
                value: namespaceFilter,
                onchange: function (e) { namespaceFilter = e.target.value; }
            }, [
                m("option", { value: "" }, "All namespaces (" + modelNames.length + ")"),
                namespaces.map(function (ns) {
                    let count = modelNames.filter(function (n) { return n.startsWith(ns + "."); }).length;
                    return m("option", { value: ns }, ns + " (" + count + ")");
                })
            ])
        ]),

        // Count
        m("p", { class: "text-xs text-gray-500" }, filtered.length + " models" + (filterText || namespaceFilter ? " (filtered)" : "")),

        // Table
        m("div", { class: "overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-700" },
            m("table", { class: "w-full text-sm" }, [
                m("thead",
                    m("tr", { class: "bg-gray-50 dark:bg-gray-800 text-left" }, [
                        m("th", { class: "px-4 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Model Type"),
                        m("th", { class: "px-4 py-2 font-medium text-gray-600 dark:text-gray-300 w-24" }, "Action")
                    ])
                ),
                m("tbody",
                    filtered.length === 0
                        ? m("tr", m("td", { colspan: 2, class: "px-4 py-8 text-center text-gray-400" }, loading ? "Loading..." : "No models found"))
                        : filtered.map(function (name) {
                            return m("tr", {
                                key: name,
                                class: "border-t border-gray-100 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800/50 cursor-pointer",
                                onclick: function () { loadModelDetail(name); }
                            }, [
                                m("td", { class: "px-4 py-2 text-gray-800 dark:text-gray-200 font-mono" }, name),
                                m("td", { class: "px-4 py-2" },
                                    m("button", {
                                        class: "text-blue-600 hover:text-blue-800 text-xs font-medium",
                                        onclick: function (e) { e.stopPropagation(); loadModelDetail(name); }
                                    }, "View")
                                )
                            ]);
                        })
                )
            ])
        )
    ]);
}

// ── Create Model Form (Phase 13) ────────────────────────────────────

function renderCreateModelForm() {
    return m("div", { class: "p-4 rounded-lg border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20 space-y-3" }, [
        m("h3", { class: "text-sm font-semibold text-green-800 dark:text-green-300" }, "Create New Model"),
        m("div", { class: "flex gap-3 flex-wrap" }, [
            m("div", { class: "flex-1 min-w-48" }, [
                m("label", { class: "text-xs text-gray-600 dark:text-gray-400 block mb-1" }, "Model Name (e.g. custom.myModel)"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm font-mono",
                    placeholder: "custom.myModel",
                    value: newModelName,
                    oninput: function (e) { newModelName = e.target.value; }
                })
            ]),
            m("div", { class: "flex-1 min-w-48" }, [
                m("label", { class: "text-xs text-gray-600 dark:text-gray-400 block mb-1" }, "Inherits (comma-separated)"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm font-mono",
                    placeholder: "data.directory",
                    value: newModelInherits,
                    oninput: function (e) { newModelInherits = e.target.value; }
                })
            ]),
            m("div", { class: "w-40" }, [
                m("label", { class: "text-xs text-gray-600 dark:text-gray-400 block mb-1" }, "Group Name"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                    placeholder: "Auto from name",
                    value: newModelGroup,
                    oninput: function (e) { newModelGroup = e.target.value; }
                })
            ])
        ]),
        m("div", { class: "flex gap-2" }, [
            m("button", {
                class: "px-4 py-1.5 rounded text-sm bg-green-600 text-white hover:bg-green-500" + (createModelLoading ? " opacity-50" : ""),
                disabled: createModelLoading,
                onclick: doCreateModel
            }, createModelLoading ? "Creating..." : "Create Model"),
            m("button", {
                class: "px-4 py-1.5 rounded text-sm bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300",
                onclick: function () { showCreateModel = false; }
            }, "Cancel")
        ])
    ]);
}

// ── Render: Model Detail (Phase 6 + Phase 13 actions) ────────────────

function renderModelDetail() {
    if (detailLoading) {
        return m("div", { class: "flex items-center justify-center py-16" }, [
            m("span", { class: "material-symbols-outlined animate-spin text-gray-400", style: "font-size:24px" }, "progress_activity"),
            m("span", { class: "ml-2 text-gray-500" }, "Loading schema...")
        ]);
    }

    if (detailError) {
        return m("div", { class: "space-y-4" }, [
            renderBackButton(),
            m("div", { class: "p-4 rounded bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-300" }, detailError)
        ]);
    }

    let schema = selectedSchema || {};
    let fields = selectedFields || [];
    let isSys = isSystemModel(schema);

    return m("div", { class: "space-y-4" }, [
        // Header
        m("div", { class: "flex items-center justify-between" }, [
            renderBackButton(),
            m("div", { class: "flex items-center gap-3" }, [
                m("h1", { class: "text-xl font-bold text-gray-800 dark:text-white font-mono" }, selectedType),
                isSys
                    ? m("span", { class: "text-xs px-2 py-0.5 rounded bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300" }, "system")
                    : m("span", { class: "text-xs px-2 py-0.5 rounded bg-green-100 dark:bg-green-900/40 text-green-700 dark:text-green-300" }, "user-defined"),
                !isSys ? m("button", {
                    class: "px-3 py-1 rounded text-xs bg-red-600 text-white hover:bg-red-500",
                    onclick: function () { doDeleteModel(selectedType); }
                }, "Delete Model") : null
            ])
        ]),

        // Tabs
        m("div", { class: "flex gap-1 border-b border-gray-200 dark:border-gray-700" }, [
            detailTabBtn("fields", "Fields (" + fields.length + ")"),
            detailTabBtn("properties", "Properties"),
            detailTabBtn("inheritance", "Inheritance")
        ]),

        // Tab content
        detailTab === "fields" ? renderFieldsTab(fields, schema) : null,
        detailTab === "properties" ? renderPropertiesTab(schema) : null,
        detailTab === "inheritance" ? renderInheritanceTab(schema) : null
    ]);
}

function renderBackButton() {
    return m("button", {
        class: "flex items-center gap-1 text-sm text-blue-600 hover:text-blue-800",
        onclick: backToList
    }, [
        m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "arrow_back"),
        "Models"
    ]);
}

function detailTabBtn(tab, label) {
    let active = detailTab === tab;
    return m("button", {
        class: "px-4 py-2 text-sm font-medium border-b-2 " +
            (active ? "border-blue-600 text-blue-600" : "border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300"),
        onclick: function () { detailTab = tab; }
    }, label);
}

// ── Fields tab (Phase 6 + Phase 13 add/delete field) ────────────────

function renderFieldsTab(fields, schema) {
    let filtered = fields;
    if (fieldFilter) {
        let lower = fieldFilter.toLowerCase();
        filtered = fields.filter(function (f) { return f.name.toLowerCase().indexOf(lower) !== -1; });
    }
    if (!showInherited && schema.inherits) {
        filtered = filtered.filter(function (f) {
            return !f.provider || f.provider === selectedType;
        });
    }

    return m("div", { class: "space-y-3" }, [
        // Field toolbar
        m("div", { class: "flex items-center gap-2" }, [
            m("input", {
                type: "text",
                class: "flex-1 px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                placeholder: "Filter fields...",
                value: fieldFilter,
                oninput: function (e) { fieldFilter = e.target.value; }
            }),
            m("label", { class: "flex items-center gap-1 text-sm text-gray-600 dark:text-gray-400 cursor-pointer" }, [
                m("input", {
                    type: "checkbox",
                    checked: showInherited,
                    onchange: function () { showInherited = !showInherited; }
                }),
                "Show inherited"
            ]),
            m("button", {
                class: "px-3 py-1.5 rounded text-xs bg-green-600 text-white hover:bg-green-500",
                onclick: function () { showAddField = !showAddField; }
            }, showAddField ? "Cancel" : "+ Add Field")
        ]),

        // Add field form (Phase 13)
        showAddField ? renderAddFieldForm() : null,

        // Fields table
        m("div", { class: "overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-700" },
            m("table", { class: "w-full text-sm" }, [
                m("thead",
                    m("tr", { class: "bg-gray-50 dark:bg-gray-800 text-left" }, [
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Name"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Type"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Flags"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Provider"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Description"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300 w-16" }, "")
                    ])
                ),
                m("tbody",
                    filtered.length === 0
                        ? m("tr", m("td", { colspan: 6, class: "px-3 py-6 text-center text-gray-400" }, "No fields match filter"))
                        : filtered.map(function (f) {
                            let isOwn = !f.provider || f.provider === selectedType;
                            let canDelete = !isSystemField(f) && !f.identity && !f.inherited;
                            return m("tr", {
                                key: f.name,
                                class: "border-t border-gray-100 dark:border-gray-800 " + (isOwn ? "" : "opacity-70")
                            }, [
                                m("td", { class: "px-3 py-1.5 font-mono text-gray-800 dark:text-gray-200" }, f.name),
                                m("td", { class: "px-3 py-1.5 text-gray-600 dark:text-gray-400" }, fieldTypeLabel(f)),
                                m("td", { class: "px-3 py-1.5" },
                                    m("div", { class: "flex flex-wrap gap-1" },
                                        fieldBadges(f).map(function (b) {
                                            return m("span", { class: "text-xs px-1.5 py-0.5 rounded " + b.color }, b.label);
                                        })
                                    )
                                ),
                                m("td", { class: "px-3 py-1.5 text-xs text-gray-500 font-mono" }, f.provider || selectedType),
                                m("td", { class: "px-3 py-1.5 text-xs text-gray-500" }, f.description || ""),
                                m("td", { class: "px-3 py-1.5" },
                                    canDelete ? m("button", {
                                        class: "text-red-400 hover:text-red-600",
                                        title: "Delete field",
                                        onclick: function () { doDeleteField(f.name); }
                                    }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "close")) : null
                                )
                            ]);
                        })
                )
            ])
        )
    ]);
}

// ── Add Field Form (Phase 13) ───────────────────────────────────────

function renderAddFieldForm() {
    return m("div", { class: "p-3 rounded-lg border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20 space-y-2" }, [
        m("h4", { class: "text-xs font-semibold text-green-800 dark:text-green-300" }, "Add Field to " + selectedType),
        m("div", { class: "flex gap-3 flex-wrap items-end" }, [
            m("div", { class: "w-48" }, [
                m("label", { class: "text-xs text-gray-600 dark:text-gray-400 block mb-1" }, "Field Name"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm font-mono",
                    placeholder: "myField",
                    value: newFieldName,
                    oninput: function (e) { newFieldName = e.target.value; }
                })
            ]),
            m("div", { class: "w-36" }, [
                m("label", { class: "text-xs text-gray-600 dark:text-gray-400 block mb-1" }, "Type"),
                m("select", {
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                    value: newFieldType,
                    onchange: function (e) { newFieldType = e.target.value; }
                }, FIELD_TYPES.map(function (t) { return m("option", { value: t }, t); }))
            ]),
            m("div", { class: "w-36" }, [
                m("label", { class: "text-xs text-gray-600 dark:text-gray-400 block mb-1" }, "Default Value"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                    placeholder: "(optional)",
                    value: newFieldDefault,
                    oninput: function (e) { newFieldDefault = e.target.value; }
                })
            ]),
            m("button", {
                class: "px-4 py-1.5 rounded text-sm bg-green-600 text-white hover:bg-green-500" + (addFieldLoading ? " opacity-50" : ""),
                disabled: addFieldLoading,
                onclick: doAddField
            }, addFieldLoading ? "Adding..." : "Add Field")
        ])
    ]);
}

// ── Properties tab ──────────────────────────────────────────────────

function renderPropertiesTab(schema) {
    let props = [];
    if (schema.name) props.push(["Name", schema.name]);
    if (schema.system != null) props.push(["System", String(schema.system)]);
    if (schema.inherits) props.push(["Inherits", Array.isArray(schema.inherits) ? schema.inherits.join(", ") : schema.inherits]);
    if (schema.group != null) props.push(["Group", String(schema.group)]);
    if (schema.abstract != null) props.push(["Abstract", String(schema.abstract)]);
    if (schema.limitFields) props.push(["Limit Fields", Array.isArray(schema.limitFields) ? schema.limitFields.join(", ") : String(schema.limitFields)]);
    if (schema.query) props.push(["Default Query Fields", Array.isArray(schema.query) ? schema.query.join(", ") : String(schema.query)]);
    if (schema.io) props.push(["IO", schema.io]);
    if (schema.factory) props.push(["Factory", JSON.stringify(schema.factory)]);
    if (schema.implements) props.push(["Implements", Array.isArray(schema.implements) ? schema.implements.join(", ") : schema.implements]);

    return m("div", { class: "overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-700" },
        m("table", { class: "w-full text-sm" }, [
            m("thead",
                m("tr", { class: "bg-gray-50 dark:bg-gray-800 text-left" }, [
                    m("th", { class: "px-4 py-2 font-medium text-gray-600 dark:text-gray-300 w-48" }, "Property"),
                    m("th", { class: "px-4 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Value")
                ])
            ),
            m("tbody",
                props.map(function (p) {
                    return m("tr", { class: "border-t border-gray-100 dark:border-gray-800" }, [
                        m("td", { class: "px-4 py-2 font-medium text-gray-700 dark:text-gray-300" }, p[0]),
                        m("td", { class: "px-4 py-2 font-mono text-gray-600 dark:text-gray-400" }, p[1])
                    ]);
                })
            )
        ])
    );
}

// ── Inheritance tab ─────────────────────────────────────────────────

function renderInheritanceTab(schema) {
    let chain = [];
    if (schema.inherits && Array.isArray(schema.inherits)) {
        chain = schema.inherits;
    }

    if (chain.length === 0) {
        return m("div", { class: "py-8 text-center text-gray-400" }, "This model does not inherit from any other model.");
    }

    return m("div", { class: "space-y-3" }, [
        m("p", { class: "text-sm text-gray-600 dark:text-gray-400" }, "Inheritance chain:"),
        m("div", { class: "flex flex-wrap items-center gap-2" },
            chain.map(function (parent, idx) {
                return [
                    idx > 0 ? m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:16px" }, "arrow_forward") : null,
                    m("button", {
                        class: "px-3 py-1.5 rounded bg-gray-100 dark:bg-gray-800 text-sm font-mono text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/30",
                        onclick: function () { loadModelDetail(parent); }
                    }, parent)
                ];
            }).flat()
        ),
        m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:16px" }, "arrow_forward"),
        m("span", { class: "px-3 py-1.5 rounded bg-blue-100 dark:bg-blue-900/50 text-sm font-mono text-blue-800 dark:text-blue-200 font-bold" }, selectedType)
    ]);
}

// ── Render: Form Editor (Phase 7) ───────────────────────────────────

function renderFormEditor() {
    return m("div", { class: "space-y-4" }, [
        // Header
        m("div", { class: "flex items-center justify-between" }, [
            m("h1", { class: "text-2xl font-bold text-gray-800 dark:text-white" }, "Form Definition Editor"),
            m("div", { class: "flex items-center gap-2" }, [
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm " + (!editorMode ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300"),
                    onclick: function () { editorMode = false; }
                }, "Schema"),
                m("button", {
                    class: "px-3 py-1.5 rounded text-sm " + (editorMode ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300"),
                    onclick: function () { editorMode = true; }
                }, "Form Editor")
            ])
        ]),

        formEditorLoading
            ? m("div", { class: "flex items-center gap-2 py-8 justify-center text-gray-400" }, [
                m("span", { class: "material-symbols-outlined animate-spin", style: "font-size:20px" }, "progress_activity"),
                "Loading..."
            ])
            : m("div", { class: "flex gap-4" }, [
                // Left: form definition list
                renderFormDefList(),
                // Right: form field editor
                selectedFormDef ? renderFormDefEditor() : renderFormEditorEmpty()
            ])
    ]);
}

function renderFormDefList() {
    return m("div", { class: "w-64 shrink-0 space-y-2" }, [
        m("div", { class: "flex items-center justify-between" }, [
            m("h3", { class: "text-sm font-semibold text-gray-700 dark:text-gray-300" }, "Definitions"),
            m("button", {
                class: "text-xs px-2 py-1 rounded bg-blue-600 text-white hover:bg-blue-500",
                onclick: function () {
                    let type = prompt("Enter model type (e.g. olio.char.person):");
                    if (type && type.trim()) createFormDef(type.trim());
                }
            }, "+ New")
        ]),
        formDefinitions.length === 0
            ? m("p", { class: "text-xs text-gray-400 py-4" }, "No form definitions yet. Create one to get started.")
            : m("div", { class: "flex flex-col gap-1" },
                formDefinitions.map(function (fd) {
                    let active = selectedFormDef && selectedFormDef.objectId === fd.objectId;
                    return m("div", {
                        key: fd.objectId,
                        class: "flex items-center justify-between px-3 py-2 rounded cursor-pointer text-sm " +
                            (active ? "bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300" : "hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300"),
                        onclick: function () { selectFormDef(fd); }
                    }, [
                        m("div", { class: "truncate" }, [
                            m("div", { class: "font-medium" }, fd.name || "Untitled"),
                            m("div", { class: "text-xs opacity-60" }, fd.modelType || "")
                        ]),
                        m("button", {
                            class: "text-red-400 hover:text-red-600 shrink-0",
                            onclick: function (e) { e.stopPropagation(); deleteFormDef(fd); }
                        }, m("span", { class: "material-symbols-outlined", style: "font-size:16px" }, "close"))
                    ]);
                })
            )
    ]);
}

function renderFormEditorEmpty() {
    return m("div", { class: "flex-1 flex items-center justify-center py-16" }, [
        m("div", { class: "text-center text-gray-400" }, [
            m("span", { class: "material-symbols-outlined", style: "font-size:48px" }, "edit_note"),
            m("p", { class: "mt-2" }, "Select or create a form definition")
        ])
    ]);
}

function renderFormDefEditor() {
    let fd = selectedFormDef;
    if (!fd) return null;

    let fields = fd.fields || [];

    return m("div", { class: "flex-1 space-y-4 min-w-0" }, [
        // Form meta
        m("div", { class: "flex items-center gap-3" }, [
            m("div", { class: "flex-1" }, [
                m("label", { class: "text-xs text-gray-500" }, "Form Name"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                    value: fd.name || "",
                    oninput: function (e) { fd.name = e.target.value; markDirty(); }
                })
            ]),
            m("div", { class: "w-48" }, [
                m("label", { class: "text-xs text-gray-500" }, "Model Type"),
                m("input", {
                    type: "text",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm font-mono",
                    value: fd.modelType || "",
                    disabled: true
                })
            ]),
            m("div", { class: "w-20" }, [
                m("label", { class: "text-xs text-gray-500" }, "Columns"),
                m("input", {
                    type: "number",
                    class: "w-full px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
                    value: fd.columns || 6,
                    min: 1, max: 12,
                    oninput: function (e) { fd.columns = parseInt(e.target.value) || 6; markDirty(); }
                })
            ]),
            formEditorDirty ? m("button", {
                class: "px-3 py-1.5 rounded bg-green-600 text-white text-sm hover:bg-green-500 mt-4",
                onclick: saveFormDef
            }, "Save") : null
        ]),

        // Fields table
        m("div", { class: "overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-700" },
            m("table", { class: "w-full text-sm" }, [
                m("thead",
                    m("tr", { class: "bg-gray-50 dark:bg-gray-800 text-left" }, [
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300 w-8" }, "#"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Field"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300" }, "Label"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300 w-28" }, "Layout"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300 w-20 text-center" }, "Visible"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300 w-20 text-center" }, "Required"),
                        m("th", { class: "px-3 py-2 font-medium text-gray-600 dark:text-gray-300 w-16" }, "")
                    ])
                ),
                m("tbody",
                    fields.length === 0
                        ? m("tr", m("td", { colspan: 7, class: "px-3 py-6 text-center text-gray-400" }, "No fields defined"))
                        : fields.map(function (f, idx) {
                            return m("tr", { key: idx, class: "border-t border-gray-100 dark:border-gray-800" }, [
                                m("td", { class: "px-3 py-1.5 text-gray-400 text-xs" }, idx + 1),
                                m("td", { class: "px-3 py-1.5 font-mono text-gray-800 dark:text-gray-200" }, f.fieldName),
                                m("td", { class: "px-3 py-1.5" },
                                    m("input", {
                                        type: "text",
                                        class: "w-full px-2 py-1 rounded border border-gray-200 dark:border-gray-700 bg-transparent text-sm",
                                        value: f.label || "",
                                        oninput: function (e) { f.label = e.target.value; markDirty(); }
                                    })
                                ),
                                m("td", { class: "px-3 py-1.5" },
                                    m("select", {
                                        class: "w-full px-2 py-1 rounded border border-gray-200 dark:border-gray-700 bg-transparent text-sm",
                                        value: f.layout || "half",
                                        onchange: function (e) { f.layout = e.target.value; markDirty(); }
                                    }, [
                                        ["one", "1/6"], ["third", "1/3"], ["half", "1/2"],
                                        ["two-thirds", "2/3"], ["fifth", "5/6"], ["full", "Full"]
                                    ].map(function (o) { return m("option", { value: o[0] }, o[1]); }))
                                ),
                                m("td", { class: "px-3 py-1.5 text-center" },
                                    m("input", {
                                        type: "checkbox",
                                        checked: f.visible !== false,
                                        onchange: function () { f.visible = !f.visible; markDirty(); }
                                    })
                                ),
                                m("td", { class: "px-3 py-1.5 text-center" },
                                    m("input", {
                                        type: "checkbox",
                                        checked: !!f.required,
                                        onchange: function () { f.required = !f.required; markDirty(); }
                                    })
                                ),
                                m("td", { class: "px-3 py-1.5" },
                                    m("div", { class: "flex gap-1" }, [
                                        idx > 0 ? m("button", {
                                            class: "text-gray-400 hover:text-gray-600",
                                            onclick: function () {
                                                let tmp = fields[idx - 1];
                                                fields[idx - 1] = fields[idx];
                                                fields[idx] = tmp;
                                                fields.forEach(function (ff, i) { ff.order = i; });
                                                markDirty();
                                            }
                                        }, m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "arrow_upward")) : null,
                                        idx < fields.length - 1 ? m("button", {
                                            class: "text-gray-400 hover:text-gray-600",
                                            onclick: function () {
                                                let tmp = fields[idx + 1];
                                                fields[idx + 1] = fields[idx];
                                                fields[idx] = tmp;
                                                fields.forEach(function (ff, i) { ff.order = i; });
                                                markDirty();
                                            }
                                        }, m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "arrow_downward")) : null
                                    ])
                                )
                            ]);
                        })
                )
            ])
        ),

        // Preview
        renderFormPreview(fd)
    ]);
}

// ── Form Preview ────────────────────────────────────────────────────

function renderFormPreview(fd) {
    let fields = (fd.fields || []).filter(function (f) { return f.visible !== false; });
    if (fields.length === 0) return null;

    let cols = fd.columns || 6;
    let layoutMap = { "one": 1, "third": 2, "half": 3, "two-thirds": 4, "fifth": 5, "full": 6 };

    return m("div", { class: "space-y-2" }, [
        m("h3", { class: "text-sm font-semibold text-gray-600 dark:text-gray-400" }, "Preview"),
        m("div", {
            class: "p-4 rounded-lg border border-dashed border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-900",
            style: "display:grid;grid-template-columns:repeat(" + cols + ",1fr);gap:12px"
        },
            fields.map(function (f) {
                let span = layoutMap[f.layout] || 3;
                return m("div", { style: "grid-column:span " + span }, [
                    m("label", { class: "text-xs font-medium text-gray-600 dark:text-gray-400 mb-1 block" }, [
                        f.label || f.fieldName,
                        f.required ? m("span", { class: "text-red-500 ml-0.5" }, "*") : null
                    ]),
                    m("div", { class: "w-full h-8 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800" })
                ]);
            })
        )
    ]);
}

// ── Main view component ─────────────────────────────────────────────

const schemaView = {
    oninit: function () {
        if (modelNames.length === 0) loadModelNames();
    },
    view: function () {
        // Admin check
        if (!page.context().roles || !page.context().roles.admin) {
            return m("div", { class: "max-w-4xl mx-auto p-6" }, [
                m("div", { class: "p-4 rounded bg-yellow-50 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-300 flex items-center gap-2" }, [
                    m("span", { class: "material-symbols-outlined", style: "font-size:20px" }, "lock"),
                    "Schema browser requires administrator access."
                ])
            ]);
        }

        if (error) {
            return m("div", { class: "max-w-6xl mx-auto p-6" }, [
                m("div", { class: "p-4 rounded bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-300" }, error),
                m("button", { class: "mt-2 text-sm text-blue-600", onclick: loadModelNames }, "Retry")
            ]);
        }

        return m("div", { class: "max-w-6xl mx-auto p-6" }, [
            editorMode ? renderFormEditor() :
                (selectedType ? renderModelDetail() : renderModelList())
        ]);
    }
};

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/schema": {
        oninit: function () { schemaView.oninit(); },
        view: function () {
            return layout(pageLayout(schemaView.view()));
        }
    }
};

// ── Test exports ────────────────────────────────────────────────────

export {
    createModelSchema, addFieldsToModel, deleteModel, deleteField,
    isSystemModel, isSystemField, FIELD_TYPES,
    fetchModelNames, fetchModelSchema, fetchModelFields
};
