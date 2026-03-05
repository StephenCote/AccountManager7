/**
 * membership.js — Object relationship management (ESM)
 * Port of Ux7 client/components/membership.js
 *
 * Handles members, entities, children, and participations.
 * Provides functions for managing object memberships consumed by
 * objectPage, tableListEditor, and other components.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

// ── Member Operations ───────────────────────────────────────────────

function pickMember(ctx, members) {
    let aP = [];
    let entity = ctx.entity;
    let field = ctx.field;
    let fieldName = ctx.fieldName || field.name;
    let am7client = getClient();

    if (am7model.hasIdentity(entity)) {
        members.forEach(function(t) {
            aP.push(new Promise(function(res, rej) {
                let e2 = entity;
                let a2 = t;
                // For tags, the tag participates in the entity
                if ("data.tag" === field.baseModel) {
                    e2 = t;
                    a2 = entity;
                }
                // For tags and virtual fields, don't pass field name
                let fn = (field.baseModel !== "data.tag" && !field.virtual) ? field.name : null;
                am7client.member(
                    e2[am7model.jsonModelKey], e2.objectId,
                    fn,
                    a2[am7model.jsonModelKey], a2.objectId,
                    true,
                    function(v) { res(v); }
                );
            }));
        });
    }

    return Promise.all(aP).then(function() {
        if (ctx.foreignData && fieldName) {
            delete ctx.foreignData[fieldName];
        }
        if (ctx.cancelPicker) ctx.cancelPicker();
    });
}

function addMember(ctx, name, tableType, tableForm, props) {
    let type = tableType;
    let field = ctx.field;

    if (type === "$flex") {
        if (field.foreignType && ctx.inst) {
            type = ctx.inst.api[field.foreignType]();
        } else {
            return Promise.resolve(null);
        }
    }

    if (props.typeAttribute && ctx.entity) {
        type = am7view.typeToModel(ctx.entity[props.typeAttribute]);
    }

    if (props.picker && ctx.preparePicker) {
        let pickCtx = Object.assign({}, ctx, { fieldName: name });
        return ctx.preparePicker(type, function(members) {
            pickMember(pickCtx, members);
        });
    }

    return Promise.resolve(null);
}

function deleteMember(ctx, name, field, tableType, tableForm) {
    let aP = [];
    let entity = ctx.entity;
    let valuesState = ctx.valuesState || {};
    let foreignData = ctx.foreignData || {};
    let am7client = getClient();

    Object.keys(valuesState).forEach(function(k) {
        let state = valuesState[k];
        if (state.selected) {
            if (foreignData[state.attribute]) {
                let obj = foreignData[state.attribute][state.index];
                let fn = (field.virtual || field.ephemeral) ? null : name;
                aP.push(new Promise(function(res) {
                    am7client.member(
                        entity[am7model.jsonModelKey], entity.objectId,
                        fn,
                        obj[am7model.jsonModelKey], obj.objectId,
                        false,
                        function(v) {
                            if (entity[name]) {
                                entity[name] = entity[name].filter(function(m) {
                                    return m.objectId !== obj.objectId;
                                });
                            }
                            res(v);
                        }
                    );
                }));
            } else {
                let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
                let per = vProp[name][state.index];

                if (am7model.hasIdentity(entity)) {
                    let fn = (field.baseModel !== "data.tag" && !field.virtual) ? field.name : null;
                    let obj = entity;
                    let act = per;
                    if (per[am7model.jsonModelKey] === "data.tag") {
                        obj = per;
                        act = entity;
                    }
                    aP.push(new Promise(function(res) {
                        am7client.member(
                            obj[am7model.jsonModelKey], obj.objectId,
                            fn,
                            act[am7model.jsonModelKey], act.objectId,
                            false,
                            function(v) { res(v); }
                        );
                    }));
                }
                vProp[name] = vProp[name].filter(function(o) {
                    return o.objectId !== per.objectId;
                });
            }
        }
    });

    return Promise.all(aP).then(function() {
        if (ctx.foreignData) delete ctx.foreignData[name];
        m.redraw();
    });
}

function objectMembers(ctx, name, field) {
    return new Promise(function(res) {
        let entity = ctx.entity;
        let am7client = getClient();
        let ftype = field ? (field.typeAttribute || field.foreignType) : null;

        if (!entity || !entity.objectId || !field || !ftype) {
            res([]);
            return;
        }

        let type = am7view.typeToModel(entity[ftype]);
        am7client.members(
            entity[am7model.jsonModelKey], entity.objectId,
            type, 0, 100,
            function(v) {
                am7model.updateListModel(v);
                res(v);
            }
        );
    });
}

function memberObjects(ctx, name, field) {
    return new Promise(function(res) {
        let entity = ctx.entity;
        let am7client = getClient();

        if (!entity || !entity.objectId || !field || !field.participantType) {
            res([]);
            return;
        }

        let ftype = field.factoryType || field.participationFactoryType;
        let paftype = field.participationFactoryType;
        let pftype = field.participantFactoryType;
        let ptype = field.participantType;

        // Resolve dynamic type references
        if (paftype && paftype.match(/^\./)) paftype = entity[paftype.slice(1)];
        if (ftype && ftype.match(/^\./)) ftype = entity[ftype.slice(1)];
        if (pftype && pftype.match(/^\./)) pftype = entity[pftype.slice(1)];
        if (ptype && ptype.match(/^\./)) ptype = entity[ptype.slice(1)];

        let search = {
            startRecord: 0,
            recordCount: 100,
            participantList: [entity.objectId],
            participantFactoryType: pftype,
            participationFactoryType: paftype,
            participantType: ptype
        };

        am7client.listParticipations(ftype, search, function(v) {
            am7model.updateListModel(v);
            res(v);
        });
    });
}

// ── Entity Operations ───────────────────────────────────────────────

function pickEntity(ctx, name, data) {
    let entity = ctx.entity;
    let field = ctx.field;
    let page = getPage();
    let am7client = getClient();
    let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);

    if (!vProp[name] && field.type === "list") {
        vProp[name] = [];
    }
    vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");

    if (ctx.caller) {
        ctx.caller.callback('update', ctx.objectPage, ctx.inst, name, ctx.parentProperty);
    } else {
        if (am7model.hasIdentity(entity)) {
            let aP = [];
            data.forEach(function(v) {
                aP.push(new Promise(function(res) {
                    am7client.member(
                        entity[am7model.jsonModelKey], entity.objectId,
                        name,
                        v[am7model.jsonModelKey], v.objectId,
                        true,
                        function(r) { res(r); }
                    );
                }));
            });
            Promise.all(aP);
        }
    }

    if (ctx.cancelPicker) ctx.cancelPicker();
}

function addEntity(ctx, name, tableType, tableForm, props) {
    if (props.picker && ctx.preparePicker) {
        return ctx.preparePicker(tableType, function(data) {
            pickEntity(ctx, name, data);
        });
    }
    return Promise.resolve(null);
}

function openEntity(ctx, name, field, tableType, tableForm, props) {
    let ent;
    let valuesState = ctx.valuesState || {};
    let foreignData = ctx.foreignData || {};
    let entity = ctx.entity;

    Object.keys(valuesState).forEach(function(k) {
        let state = valuesState[k];
        let vProp;
        if (field.foreign && foreignData[name]) {
            vProp = foreignData;
        } else {
            vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
        }

        if (!ent && state.selected && vProp[name]) {
            ent = vProp[name][state.index];
        }
    });

    if (ent) {
        let uri = "/view/" + ent[am7model.jsonModelKey] + "/" + ent.objectId;
        m.route.set(uri, { key: ent.objectId });
    }
}

function deleteEntity(ctx, name, field, tableType, tableForm, props) {
    let aP = [];
    let entity = ctx.entity;
    let valuesState = ctx.valuesState || {};
    let am7client = getClient();

    Object.keys(valuesState).forEach(function(k) {
        let state = valuesState[k];
        let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);

        if (state.selected && vProp[name]) {
            let per = vProp[name][state.index];

            if (am7model.hasIdentity(entity)) {
                aP.push(new Promise(function(res) {
                    am7client.member(
                        entity[am7model.jsonModelKey], entity.objectId,
                        name,
                        per[am7model.jsonModelKey], per.objectId,
                        false,
                        function(v) { res(v); }
                    );
                }));
            }
            vProp[name] = vProp[name].filter(function(o) {
                return o.objectId !== per.objectId;
            });
        }
    });

    return Promise.all(aP).then(function() {
        if (ctx.updateChange) ctx.updateChange();
        m.redraw();
    });
}

// ── Child/Parent Operations ─────────────────────────────────────────

function reparent(parent, children) {
    let am7client = getClient();
    let aP = [];
    children.forEach(function(c) {
        aP.push(new Promise(function(res, rej) {
            if (c[am7model.jsonModelKey].match(/^auth\.group$/gi) && !parent) {
                rej("Unable to reparent a group to nothing");
            } else {
                c.parentId = parent ? parent.id : 0;
                am7client.patch(c[am7model.jsonModelKey], c, function(v) {
                    if (v) res();
                    else rej();
                });
            }
        }));
    });
    return Promise.all(aP);
}

function pickChild(ctx, name, data) {
    let entity = ctx.entity;
    let field = ctx.field;
    let am7client = getClient();

    reparent(entity, data).then(function() {
        let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
        let page = getPage();
        vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");

        if (ctx.cancelPicker) ctx.cancelPicker(true);

        am7client.clearCache(entity[am7model.jsonModelKey], false, function() {
            m.redraw();
        });
    });
}

function addChild(ctx, name, tableType, tableForm, props) {
    if (props.picker && ctx.preparePicker) {
        return ctx.preparePicker(tableType, function(data) {
            pickChild(ctx, name, data);
        });
    }
    return Promise.resolve(null);
}

function deleteChild(ctx, name, field, tableType, tableForm, props) {
    let entity = ctx.entity;
    let valuesState = ctx.valuesState || {};
    let am7client = getClient();
    let aC = [];

    Object.keys(valuesState).forEach(function(k) {
        let state = valuesState[k];
        let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);

        if (state.selected && vProp[name]) {
            let obj = vProp[name][state.index];
            aC.push(obj);
            vProp[name] = vProp[name].filter(function(o) {
                return o.objectId !== obj.objectId;
            });
        }
    });

    return reparent(null, aC).then(function() {
        am7client.clearCache(entity[am7model.jsonModelKey], false, function() {
            m.redraw();
        });
    });
}

// ── Special Operations ──────────────────────────────────────────────

function objectControls(ctx, name, field) {
    return new Promise(function(res) {
        let entity = ctx.entity;
        let page = getPage();
        if (!entity || !entity.objectId) { res([]); return; }
        page.controls(entity[am7model.jsonModelKey], entity.objectId).then(function(v) {
            res(v);
        });
    });
}

function objectRequests(ctx, name, field) {
    return new Promise(function(res) {
        let entity = ctx.entity;
        let page = getPage();
        if (!entity || !entity.objectId) { res([]); return; }
        page.requests(entity[am7model.jsonModelKey], entity.objectId).then(function(v) {
            res(v);
        });
    });
}

// ── Export ───────────────────────────────────────────────────────────

const membership = {
    // Member operations
    pickMember,
    addMember,
    deleteMember,
    objectMembers,
    memberObjects,

    // Entity operations
    pickEntity,
    addEntity,
    openEntity,
    deleteEntity,

    // Child/Parent operations
    reparent,
    pickChild,
    addChild,
    deleteChild,

    // Special operations
    objectControls,
    objectRequests
};

export { membership };
export default membership;
