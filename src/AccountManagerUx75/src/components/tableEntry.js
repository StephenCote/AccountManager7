/**
 * tableEntry.js — Table row editing, adding, and removing entries (ESM)
 * Port of Ux7 client/components/tableEntry.js
 *
 * Pure logic component — no rendering. Consumed by tableListEditor.
 * Each function receives a context object with necessary state and callbacks.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

// ── Entry State Management ──────────────────────────────────────────

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

function cancelEntry(ctx, name) {
    if (ctx.resetValuesState) {
        ctx.resetValuesState();
    }
    m.redraw();
}

// ── Entry CRUD Operations ───────────────────────────────────────────

function deleteEntry(ctx, name) {
    let aP = [];
    let entity = ctx.entity;
    let valuesState = ctx.valuesState || {};
    let inst = ctx.inst;
    let am7client = getClient();
    let page = getPage();
    let model = page.context();

    Object.keys(valuesState).forEach(function(k) {
        let state = valuesState[k];
        if (state.selected) {
            if (name === 'episodes' || name === 'messages' || name === 'attributes' || name === 'elementValues') {
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
                        aP.push(new Promise(function(res) {
                            am7client.delete("CONTROL", ctl.objectId, function() { res(); });
                        }));
                    }
                }
            }
            else {
                // Generic: remove from array
                if (entity[name]) {
                    entity[name] = entity[name].filter(function(a, i) {
                        return i !== state.index;
                    });
                    delete valuesState[k];
                    if (inst) inst.change(name);
                }
            }
        }
    });

    return Promise.all(aP).then(function() {
        m.redraw();
    });
}

function newEntry(ctx, name) {
    let entity = ctx.entity;
    let valuesState = ctx.valuesState || {};
    let inst = ctx.inst;
    let am7client = getClient();

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

function checkEntry(ctx, name, field, tableType, tableForm, props) {
    let entity = ctx.entity;
    let valuesState = ctx.valuesState || {};
    let foreignData = ctx.foreignData || {};
    let inst = ctx.inst;
    let am7client = getClient();

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
                if (!e) return;
                if (tableField && tableField.virtual) return;

                let useType = tableField ? tableField.type : 'string';
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
                if (inst) inst.change(name);
            }
        }
    });

    return Promise.all(aP).then(function(aB) {
        if (!aB || !aB.filter(function(b) { return !b; }).length) {
            if (ctx.resetValuesState) ctx.resetValuesState();
            m.redraw();
        } else {
            console.warn("Error updating record: " + aB);
        }
    });
}

// ── Export ───────────────────────────────────────────────────────────

const tableEntry = {
    editEntry,
    deleteEntry,
    newEntry,
    checkEntry,
    cancelEntry
};

export { tableEntry };
export default tableEntry;
