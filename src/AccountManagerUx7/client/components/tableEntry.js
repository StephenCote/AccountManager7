(function() {
    /// Table Entry Management Component
    /// Handles table row editing, adding, and removing entries
    ///
    /// This component provides functions for managing table entries that can be
    /// used by objectPage or other components. Each function receives a context object
    /// with the necessary state and callbacks.

    /// ========================================
    /// ENTRY STATE MANAGEMENT
    /// ========================================

    /// Edit selected entries (switch to edit mode)
    /// ctx: { valuesState }
    function editEntry(ctx) {
        let valuesState = ctx.valuesState || {};

        Object.keys(valuesState).forEach(function(k) {
            let state = valuesState[k];
            if (state.selected) {
                state.mode = 'edit';
            }
        });
        m.redraw();
    }

    /// Cancel entry editing (reset state)
    /// ctx: { resetValuesState }
    function cancelEntry(ctx, name) {
        if (ctx.resetValuesState) {
            ctx.resetValuesState();
        }
        m.redraw();
    }

    /// ========================================
    /// ENTRY CRUD OPERATIONS
    /// ========================================

    /// Delete selected entries
    /// ctx: { entity, valuesState, inst }
    function deleteEntry(ctx, name) {
        let aP = [];
        let entity = ctx.entity;
        let valuesState = ctx.valuesState || {};
        let inst = ctx.inst;
        let model = page.context();

        Object.keys(valuesState).forEach(function(k) {
            let state = valuesState[k];
            if (state.selected) {
                /// Handle specific entry types
                if (name === 'episodes' || name === 'messages' || name === 'attributes' || name === 'elementValues') {
                    /// Need to confirm the attribute type, this assumes it's an array
                    let ov = entity[state.attribute][state.index];
                    entity[state.attribute] = entity[state.attribute].filter(function(a, i) {
                        return i !== state.index;
                    });
                    delete valuesState[k];
                    if (ov && am7model.hasIdentity(ov)) {
                        aP.push(page.deleteObject(ov[am7model.jsonModelKey], ov.objectId));
                    } else if (inst) {
                        inst.change(name);
                    }
                }
                else if (name === 'controls') {
                    if (model.controls && model.controls[entity.objectId]) {
                        let ctl = model.controls[entity.objectId][state.index];
                        delete model.controls[entity.objectId][state.index];
                        if (ctl && ctl.objectId) {
                            aP.push(new Promise(function(res, rej) {
                                am7client.delete("CONTROL", ctl.objectId, function(s, v) {
                                    res();
                                });
                            }));
                        }
                        console.log("DELETE", ctl);
                    }
                }
                else {
                    console.log("Handle delete " + name, state);
                }
            }
        });

        return Promise.all(aP).then(function() {
            m.redraw();
        });
    }

    /// Create a new entry in a list field
    /// ctx: { entity, valuesState, inst }
    function newEntry(ctx, name) {
        let entity = ctx.entity;
        let valuesState = ctx.valuesState || {};
        let inst = ctx.inst;

        if (!entity || !entity[am7model.jsonModelKey]) {
            console.error("Entity not available");
            return;
        }

        let mf = am7model.getModelField(entity[am7model.jsonModelKey], name);
        if (!mf) {
            console.error("Object does not define '" + name + "'");
            return;
        }

        if (mf.type === 'list' && mf.baseModel) {
            if (!entity[name]) entity[name] = [];
            if (inst && !inst.changes.includes(name)) inst.changes.push(name);

            let ne;
            if (name === 'attributes') {
                ne = am7client.newAttribute("", "");
            } else {
                ne = am7model.newPrimitive(mf.baseModel);
            }

            entity[name].push(ne);
            let idx = entity[name].length - 1;
            valuesState[name + "-" + idx] = {
                attribute: name,
                index: idx,
                selected: true,
                mode: 'edit'
            };
            m.redraw();
        } else {
            console.error("Unhandled new entry type: " + mf.type);
        }
    }

    /// Check/save entry changes
    /// ctx: { entity, valuesState, inst, foreignData }
    function checkEntry(ctx, name, field, tableType, tableForm, props) {
        let entity = ctx.entity;
        let valuesState = ctx.valuesState || {};
        let foreignData = ctx.foreignData || {};
        let inst = ctx.inst;

        if (!entity || !entity[am7model.jsonModelKey]) {
            console.error("Entity not available");
            return Promise.resolve();
        }

        let formName = tableType.substring(tableType.lastIndexOf(".") + 1);
        let fieldView = am7model.forms[formName];
        let parentField = am7model.getModelField(entity[am7model.jsonModelKey], name);
        let aP = [];

        Object.keys(valuesState).forEach(function(k) {
            let state = valuesState[k];
            if (state.mode === 'edit') {
                let idx = state.index;
                let entry;
                if (field.foreign) {
                    entry = foreignData[state.fieldName || name][idx];
                } else {
                    entry = entity[state.fieldName || name][idx];
                }

                let einst = am7model.prepareInstance(entry, tableForm);

                Object.keys(tableForm.fields).forEach(function(fk) {
                    let tableField = am7model.getModelField(tableType, fk);
                    let e = document.querySelector("[name='" + fk + "-" + idx + "']");

                    if (!e) {
                        console.warn("Element not found for field: " + fk + "-" + idx);
                        return;
                    }

                    if (tableField.virtual) {
                        console.log("Skipping virtual field");
                    } else {
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
                                einst.api[fk](e.value);
                                break;
                            default:
                                console.warn("Unhandled entry type: " + useType);
                                break;
                        }
                    }
                });

                if ((parentField?.foreign || parentField?.referenced) && !fieldView?.standardUpdate && am7model.hasIdentity(entity)) {
                    console.log("Patching object: " + name, entry);
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
                    if (inst) inst.change(name);
                }
                else {
                    console.warn("Foreign data, not updating: " + name, entry);
                }
            }
        });

        return Promise.all(aP).then(function(aB) {
            if (!aB || !aB.filter(function(b) { return !b; }).length) {
                if (ctx.resetValuesState) {
                    ctx.resetValuesState();
                }
                m.redraw();
            } else {
                console.warn("Error updating record: " + aB);
            }
        });
    }

    /// ========================================
    /// EXPORT
    /// ========================================

    let tableEntry = {
        editEntry: editEntry,
        deleteEntry: deleteEntry,
        newEntry: newEntry,
        checkEntry: checkEntry,
        cancelEntry: cancelEntry
    };

    /// Register with page.components
    if (typeof page !== "undefined" && page.components) {
        page.components.tableEntry = tableEntry;
    }

}());
