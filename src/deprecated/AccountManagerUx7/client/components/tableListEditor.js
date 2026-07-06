(function() {
    /// Table List Editor Component
    /// Replaces inline table editing with a compact list + pop-in editor panel.
    /// Provides per-row edit/delete icons, search/filter, client-side pagination,
    /// and a full-width editor panel that slides open below the list.

    let tableListEditor = {};

    /// Per-field state map (keyed by field name)
    let fieldStates = {};

    /// Standard CRUD function names that this component handles
    let standardFuncs = ['newEntry', 'editEntry', 'checkEntry', 'deleteEntry', 'cancelEntry'];

    /// ========================================
    /// UTILITY FUNCTIONS
    /// ========================================

    /// Check if a form uses only standard CRUD commands (not entitylist/memberlist/childlist)
    function isStandardCrudForm(tableForm) {
        if (!tableForm || !tableForm.commands) return true;
        return Object.values(tableForm.commands).every(function(cmd) {
            return standardFuncs.includes(cmd.function) || standardFuncs.includes(cmd.altFunction);
        });
    }
    tableListEditor.isStandardCrud = isStandardCrudForm;

    /// Get or create field state for a given field name
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

    /// Reset field state (e.g., when navigating away)
    tableListEditor.resetState = function(fieldName) {
        if (fieldName) {
            delete fieldStates[fieldName];
        } else {
            fieldStates = {};
        }
    };

    /// Get a stable identity key for an entry
    function getEntryId(entry) {
        if (entry.objectId) return entry.objectId;
        if (entry._localId) return entry._localId;
        entry._localId = page.luid();
        return entry._localId;
    }

    /// Pick the best summary fields from a form definition (up to 3, prioritize 'name')
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

    /// Format a summary value for display
    function formatSummaryValue(val) {
        if (val === undefined || val === null) return '';
        if (Array.isArray(val)) {
            return val.length + ' items';
        }
        val = '' + val;
        if (val.length > 50) return val.substring(0, 47) + '...';
        return val;
    }

    /// ========================================
    /// DATA ACCESS
    /// ========================================

    /// Get the values array for the field, handling foreign data, parentProperty, etc.
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

        if (ctx.inst && ctx.inst.api[name]) return ctx.inst.api[name]();
        if (field.parentProperty && entity[field.parentProperty]) {
            return entity[field.parentProperty][name];
        }
        return entity ? entity[name] : null;
    }

    /// Resolve the table type, handling $self
    function resolveTableType(ctx) {
        let tableType = ctx.field.baseModel;
        if (tableType === '$self') {
            tableType = ctx.entity[am7model.jsonModelKey];
        }
        return tableType;
    }

    /// ========================================
    /// SEARCH AND PAGINATION
    /// ========================================

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
        let totalPages = Math.ceil(filteredItems.length / fs.pageSize);
        if (totalPages <= 1) return '';

        return m("div", {class: "result-nav-outer"}, [
            m("div", {class: "result-nav-inner"}, [
                m("span", {class: "count-label"},
                    "Page " + (fs.currentPage + 1) + " of " + totalPages +
                    " (" + filteredItems.length + " items)"
                ),
                m("nav", {class: "result-nav"}, [
                    page.iconButton(
                        "button" + (fs.currentPage > 0 ? "" : " inactive"),
                        "chevron_left", "",
                        function() {
                            if (fs.currentPage > 0) {
                                fs.currentPage--;
                                m.redraw();
                            }
                        }
                    ),
                    page.iconButton(
                        "button" + (fs.currentPage < totalPages - 1 ? "" : " inactive"),
                        "chevron_right", "",
                        function() {
                            if (fs.currentPage < totalPages - 1) {
                                fs.currentPage++;
                                m.redraw();
                            }
                        }
                    )
                ])
            ])
        ]);
    }

    /// ========================================
    /// EDITOR OPERATIONS
    /// ========================================

    function openEditor(fs, entry, index, ctx) {
        let tableForm = ctx.fieldView.form;
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
        ne._localId = page.luid();

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
        /// If we were creating a new entry, remove it from the array
        if (fs.mode === 'creating' && fs.editEntry) {
            let name = ctx.useName;
            let entity = ctx.entity;
            if (entity && entity[name] && fs.editIndex !== null) {
                entity[name].splice(fs.editIndex, 1);
            }
        }
        closeEditor(fs);
    }

    /// Extract values from the editor DOM and write to the entry
    function extractEditorValues(fs, tableForm, tableType) {
        let entry = fs.editEntry;
        let einst = fs.editInstance;

        Object.keys(tableForm.fields).forEach(function(fk) {
            let tableFieldView = tableForm.fields[fk];
            let tableField = tableFieldView.field || am7model.getModelField(tableType, fk);
            if (!tableField) return;

            let inputName = fk + "-tle-" + (fs.activeId || 'new');
            let e = document.querySelector("[name='" + inputName + "']");
            if (!e) {
                return;
            }
            if (tableField.virtual) return;

            let useType = tableField.type;
            if (useType === 'flex') {
                if (entry.valueType) {
                    useType = entry.valueType.toLowerCase();
                }
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
                    if (e.value !== undefined) {
                        entry[fk] = e.value;
                    }
                    break;
            }
        });
    }

    /// Persist the entry (patch or create on server, or mark parent as changed)
    function persistEntry(ctx, tableType, entry, tableForm) {
        let name = ctx.useName;
        let entity = ctx.entity;
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
        let aP = [];

        /// Close editor if the deleted entry is being edited
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
                        am7client.delete("CONTROL", ctl.objectId, function() { res(); });
                    }));
                }
            }
        } else {
            /// Generic array-based delete
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

    /// ========================================
    /// RENDERING
    /// ========================================

    /// Render a single compact list row
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
                class: "tle-action-btn",
                title: "Edit",
                onclick: function(e) {
                    e.stopPropagation();
                    if (fs.mode !== 'closed') return;
                    openEditor(fs, entry, index, ctx);
                }
            }, m("span", {class: "material-symbols-outlined"}, "edit")));
        }
        if (hasDelete) {
            actions.push(m("button", {
                class: "tle-action-btn",
                title: "Delete",
                onclick: function(e) {
                    e.stopPropagation();
                    if (fs.mode !== 'closed') {
                        if (fs.activeId === entryId) return;
                    }
                    deleteEntryDirect(fs, entry, index, ctx);
                }
            }, m("span", {class: "material-symbols-outlined"}, "delete")));
        }

        return m("div", {
            class: "tle-row" + (isHighlighted ? " tle-row-active" : ""),
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
            m("span", {class: "tle-row-summary"}, summaryText || "(empty)"),
            actions.length ? m("span", {class: "tle-row-actions"}, actions) : ''
        ]);
    }

    /// Render the pop-in editor panel
    function renderEditorPanel(fs, tableForm, tableType, ctx) {
        let isOpen = (fs.mode === 'editing' || fs.mode === 'creating');

        if (!isOpen && !fs.editEntry) {
            return m("div", {class: "tle-editor tle-editor-closed"});
        }

        let entry = fs.editEntry;
        if (!entry) {
            return m("div", {class: "tle-editor tle-editor-closed"});
        }

        let formFields = [];

        Object.keys(tableForm.fields).forEach(function(fk) {
            let tableFieldView = tableForm.fields[fk];
            let tableField = tableFieldView.field || am7model.getModelField(tableType, fk);
            if (!tableField && ctx.field.foreignType && ctx.entity[ctx.field.foreignType]) {
                tableField = am7model.getModelField(tableType, fk, am7view.typeToModel(ctx.entity[ctx.field.foreignType]));
            }
            if (!tableField) return;

            let layout = tableFieldView.layout || "full";
            let layoutClass = "field-grid-item-full";
            if (layout === "half") layoutClass = "field-grid-item-half";
            else if (layout === "third") layoutClass = "field-grid-item-third";
            else if (layout === "one") layoutClass = "field-grid-item-one";
            else if (layout === "two-thirds") layoutClass = "field-grid-item-two-thirds";
            else if (layout === "fifth") layoutClass = "field-grid-item-fifth";

            let fieldLabel = tableFieldView.label || tableField.label || fk;
            let inputName = fk + "-tle-" + (fs.activeId || 'new');

            let fieldContent;
            if (ctx.modelField) {
                let cv = fs.editInstance;
                let defVal = (cv && cv.api && cv.api[fk]) ? cv.api[fk]() : entry[fk];
                fieldContent = ctx.modelField(fk, tableFieldView, tableField, inputName, defVal, true, entry, tableForm);
            } else {
                /// Fallback: simple text input
                let val = entry[fk];
                if (val === undefined || val === null) val = '';
                if (Array.isArray(val)) val = val.join('\n');
                fieldContent = m("input", {
                    class: "text-field-full",
                    name: inputName,
                    type: "text",
                    value: '' + val
                });
            }

            formFields.push(m("div", {class: layoutClass}, [
                m("label", {class: "field-label", for: inputName}, fieldLabel),
                fieldContent
            ]));
        });

        return m("div", {
            class: "tle-editor " + (isOpen ? "tle-editor-open" : "tle-editor-closed")
        }, [
            m("div", {class: "field-grid-6"}, formFields),
            m("div", {class: "tle-editor-toolbar"}, [
                page.iconButton("button", "check", " Save", function() {
                    saveEntry(fs, ctx);
                }),
                page.iconButton("button", "close", " Cancel", function() {
                    cancelEditor(fs, ctx);
                })
            ])
        ]);
    }

    /// Render the toolbar (New button, search, count)
    function renderToolbar(fs, items, ctx, hidden) {
        if (hidden) return '';

        let hasCommands = ctx.fieldView && ctx.fieldView.form && ctx.fieldView.form.commands;
        let hasNew = hasCommands && ctx.fieldView.form.commands.new;
        let totalCount = items ? items.length : 0;

        let toolbarItems = [];

        /// New button
        if (hasNew) {
            toolbarItems.push(
                m("div", {class: "result-nav"}, [
                    page.iconButton(
                        "button" + (fs.mode !== 'closed' ? " inactive" : ""),
                        "add", "",
                        function() {
                            if (fs.mode !== 'closed') return;
                            openNewEditor(fs, ctx);
                        }
                    )
                ])
            );
        }

        /// Search input (shown when 10+ items)
        if (totalCount >= 10) {
            toolbarItems.push(
                m("input", {
                    class: "tle-search",
                    type: "text",
                    placeholder: "Filter...",
                    value: fs.filterText,
                    oninput: function(e) {
                        fs.filterText = e.target.value;
                        fs.currentPage = 0;
                        m.redraw();
                    }
                })
            );
        }

        /// Count label
        if (totalCount > 0) {
            toolbarItems.push(
                m("span", {class: "tle-count"}, totalCount + " item" + (totalCount !== 1 ? "s" : ""))
            );
        }

        return m("div", {class: "tle-toolbar"}, toolbarItems);
    }

    /// ========================================
    /// MAIN RENDER FUNCTION
    /// ========================================

    tableListEditor.render = function(ctx) {
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

        /// Reset state if the entity changed (navigation to different object)
        let entityKey = entity.objectId || entity._localId || entity.id;
        if (fs._entityKey && fs._entityKey !== entityKey) {
            fieldStates[name] = null;
            fs = getFieldState(name);
        }
        fs._entityKey = entityKey;

        /// Get the values array
        let items = getValues(ctx);
        if (items === null) {
            /// Still loading (foreign data)
            return [m("div", {class: "tle-container"}, [
                m("div", {class: "tle-empty"}, "Loading...")
            ])];
        }
        if (!items) items = [];

        /// Apply filter and pagination
        let filtered = getFilteredItems(items, fs);
        let paged = getPagedItems(filtered, fs);

        /// Clamp page if items changed
        let totalPages = Math.ceil(filtered.length / fs.pageSize);
        if (totalPages > 0 && fs.currentPage >= totalPages) {
            fs.currentPage = totalPages - 1;
        }

        /// Build the list rows, injecting editor panel directly after the active row
        let listRows = [];
        if (paged.length === 0) {
            listRows.push(m("div", {class: "tle-empty"},
                fs.filterText ? "No matching items" : "No items"
            ));
        } else {
            paged.forEach(function(entry, pageIdx) {
                /// Calculate real index in the original array
                let realIndex = items.indexOf(entry);
                listRows.push(renderListRow(entry, realIndex, fs, tableForm, ctx));
                /// Insert editor panel directly after the row being edited
                if (fs.activeId === getEntryId(entry)) {
                    listRows.push(renderEditorPanel(fs, tableForm, tableType, ctx));
                }
            });
        }

        /// Assemble the component
        let parts = [];
        parts.push(renderToolbar(fs, items, ctx, hidden));
        if (!hidden) {
            parts.push(m("div", {class: "tle-list"}, listRows));
            parts.push(renderPagination(filtered, fs));
            /// If creating a new entry, editor goes after the list (new entry is at the end)
            if (fs.mode === 'creating' && !paged.some(function(e) { return getEntryId(e) === fs.activeId; })) {
                parts.push(renderEditorPanel(fs, tableForm, tableType, ctx));
            }
        }

        return [m("div", {class: "tle-container"}, parts)];
    };

    /// ========================================
    /// EXPORT
    /// ========================================

    if (typeof page !== "undefined" && page.components) {
        page.components.tableListEditor = tableListEditor;
    }

}());
