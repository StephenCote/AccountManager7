(function() {
    /// Membership Component
    /// Handles object relationships: members, entities, children, and participations
    ///
    /// This component provides functions for managing object memberships that can be
    /// used by objectPage or other components. Each function receives a context object
    /// with the necessary state and callbacks.

    /// ========================================
    /// MEMBER OPERATIONS
    /// Uses am7client.member() for membership relationships
    /// ========================================

    /// Pick members from picker and add them to the entity
    /// ctx: { entity, field, foreignData, cancelPicker, updateChange, fieldName }
    function pickMember(ctx, members) {
        let aP = [];
        let entity = ctx.entity;
        let field = ctx.field;
        let fieldName = ctx.fieldName || field.name;

        console.log("pickMember", fieldName, field, members);

        if (am7model.hasIdentity(entity)) {
            members.forEach(function(t) {
                aP.push(new Promise(function(res, rej) {
                    /// Differentiate which is the container
                    /// For tags, the tag participates in the entity, not vice versa
                    let e2 = entity;
                    let a2 = t;
                    if ("data.tag" === field.baseModel) {
                        e2 = t;
                        a2 = entity;
                    }

                    /// For tags and virtual fields, don't pass field name
                    let fn = (field.baseModel !== "data.tag" && !field.virtual) ? field.name : null;
                    console.log("member call:", e2[am7model.jsonModelKey], e2.objectId, fn, a2[am7model.jsonModelKey], a2.objectId, true);
                    am7client.member(
                        e2[am7model.jsonModelKey], e2.objectId,
                        fn,
                        a2[am7model.jsonModelKey], a2.objectId,
                        true,
                        function(v) {
                            console.log("Member result: " + v);
                            res(v);
                        }
                    );
                }));
            });
        } else {
            console.warn("Entity has no identity, cannot add member");
        }

        if (!aP.length) {
            console.warn("Nothing picked!");
        }

        return Promise.all(aP).then(function() {
            /// Clear foreign data for the field to force refresh
            if (ctx.foreignData && fieldName) {
                delete ctx.foreignData[fieldName];
            }
            if (ctx.cancelPicker) ctx.cancelPicker();
        });
    }

    /// Add member via picker
    /// ctx: { entity, inst, field, preparePicker }
    /// Returns Promise
    function addMember(ctx, name, tableType, tableForm, props) {
        let type = tableType;
        let field = ctx.field;

        console.log("addMember", name, tableType, props);

        if (type === "$flex") {
            if (field.foreignType && ctx.inst) {
                type = ctx.inst.api[field.foreignType]();
            } else {
                console.warn("Cannot reference a flex field without a foreign type");
                return Promise.resolve(null);
            }
        }

        if (props.typeAttribute && ctx.entity) {
            type = am7view.typeToModel(ctx.entity[props.typeAttribute]);
        }

        if (props.picker && ctx.preparePicker) {
            /// Add fieldName to context for pickMember to use
            let pickCtx = Object.assign({}, ctx, { fieldName: name });
            return ctx.preparePicker(type, function(members) {
                pickMember(pickCtx, members);
            });
        }

        return Promise.resolve(null);
    }

    /// Delete selected members
    /// ctx: { entity, field, valuesState, foreignData }
    function deleteMember(ctx, name, field, tableType, tableForm) {
        let aP = [];
        let entity = ctx.entity;
        let valuesState = ctx.valuesState || {};
        let foreignData = ctx.foreignData || {};

        console.log("deleteMember", name, field, valuesState);

        Object.keys(valuesState).forEach(function(k) {
            let state = valuesState[k];
            if (state.selected) {
                console.log("Processing selected state:", k, state);
                if (foreignData[state.attribute]) {
                    let obj = foreignData[state.attribute][state.index];
                    console.log("Delete from foreignData:", obj);
                    /// For virtual/ephemeral fields, don't pass field name to member()
                    let fn = (field.virtual || field.ephemeral) ? null : name;
                    console.log("Delete member call (foreignData):", entity[am7model.jsonModelKey], entity.objectId, fn, obj[am7model.jsonModelKey], obj.objectId, false);
                    aP.push(new Promise(function(res, rej) {
                        am7client.member(
                            entity[am7model.jsonModelKey], entity.objectId,
                            fn,
                            obj[am7model.jsonModelKey], obj.objectId,
                            false,
                            function(v) {
                                console.log("Deleted member result:", v);
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
                    console.log("Delete from vProp:", per);

                    if (am7model.hasIdentity(entity)) {
                        /// For tags and virtual fields, don't pass field name
                        let fn = (field.baseModel !== "data.tag" && !field.virtual) ? field.name : null;
                        let obj = entity;
                        let act = per;
                        /// For tags, reverse the relationship
                        if (per[am7model.jsonModelKey] === "data.tag") {
                            obj = per;
                            act = entity;
                        }
                        console.log("Delete member call:", obj[am7model.jsonModelKey], obj.objectId, fn, act[am7model.jsonModelKey], act.objectId, false);
                        aP.push(am7client.member(
                            obj[am7model.jsonModelKey], obj.objectId,
                            fn,
                            act[am7model.jsonModelKey], act.objectId,
                            false
                        ));
                    }
                    vProp[name] = vProp[name].filter(function(o) {
                        return o.objectId !== per.objectId;
                    });
                }
            }
        });

        if (!aP.length) {
            console.warn("No members selected for deletion");
        }

        return Promise.all(aP).then(function() {
            if (ctx.foreignData) delete ctx.foreignData[name];
            m.redraw();
        });
    }

    /// Get members of an object (object is the container)
    /// ctx: { entity }
    function objectMembers(ctx, name, field) {
        return new Promise(function(res, rej) {
            let entity = ctx.entity;
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

    /// Get participations where object is a participant
    /// ctx: { entity }
    function memberObjects(ctx, name, field) {
        return new Promise(function(res, rej) {
            let entity = ctx.entity;

            if (!entity || !entity.objectId) {
                res([]);
                return;
            }
            if (!field || !field.participantType) {
                res([]);
                return;
            }

            let ftype = field.factoryType;
            let paftype = field.participationFactoryType;
            if (!ftype) ftype = paftype;
            let pftype = field.participantFactoryType;
            let ptype = field.participantType;

            /// Resolve dynamic type references
            if (paftype && paftype.match(/^\./)) paftype = entity[paftype.slice(1)];
            if (ftype && ftype.match(/^\./)) ftype = entity[ftype.slice(1)];
            if (pftype && pftype.match(/^\./)) pftype = entity[pftype.slice(1)];
            if (ptype && ptype.match(/^\./)) ptype = entity[ptype.slice(1)];

            let search = new org.cote.objects.participationSearchRequest();
            search.startRecord = 0;
            search.recordCount = 100;
            search.participantList = [entity.objectId];
            search.participantFactoryType = pftype;
            search.participationFactoryType = paftype;
            search.participantType = ptype;

            am7client.listParticipations(ftype, search, function(v) {
                am7model.updateListModel(v);
                res(v);
            });
        });
    }

    /// ========================================
    /// ENTITY OPERATIONS
    /// Direct entity list manipulation with optional member() calls
    /// ========================================

    /// Pick entities and add them to a field
    /// ctx: { entity, field, caller, objectPage, inst, parentProperty, cancelPicker }
    function pickEntity(ctx, name, data) {
        let entity = ctx.entity;
        let field = ctx.field;
        let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);

        if (!vProp[name] && field.type === "list") {
            vProp[name] = [];
        }
        vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");

        if (ctx.caller) {
            ctx.caller.callback('update', ctx.objectPage, ctx.inst, name, ctx.parentProperty);
        } else {
            /// For existing entities, add memberships immediately
            if (am7model.hasIdentity(entity)) {
                let aP = [];
                data.forEach(function(v) {
                    aP.push(am7client.member(
                        entity[am7model.jsonModelKey], entity.objectId,
                        name,
                        v[am7model.jsonModelKey], v.objectId,
                        true
                    ));
                });
                Promise.all(aP);
            }
        }

        if (ctx.cancelPicker) ctx.cancelPicker();
    }

    /// Add entity via picker
    /// ctx: { preparePicker, field }
    function addEntity(ctx, name, tableType, tableForm, props) {
        if (props.picker && ctx.preparePicker) {
            return ctx.preparePicker(tableType, function(data) {
                pickEntity(ctx, name, data);
            });
        }
        return Promise.resolve(null);
    }

    /// Open selected entity in a new view
    /// ctx: { entity, field, valuesState, foreignData }
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
            m.route.set(uri, {key: ent.objectId});
        } else {
            console.log("Didn't find entity to open");
        }
    }

    /// Delete selected entities
    /// ctx: { entity, field, valuesState, updateChange }
    function deleteEntity(ctx, name, field, tableType, tableForm, props) {
        let aP = [];
        let entity = ctx.entity;
        let valuesState = ctx.valuesState || {};

        Object.keys(valuesState).forEach(function(k) {
            let state = valuesState[k];
            let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);

            if (state.selected && vProp[name]) {
                let per = vProp[name][state.index];

                if (am7model.hasIdentity(entity)) {
                    aP.push(am7client.member(
                        entity[am7model.jsonModelKey], entity.objectId,
                        name,
                        per[am7model.jsonModelKey], per.objectId,
                        false
                    ));
                } else {
                    console.warn("Cannot delete entity without identity: " + entity[am7model.jsonModelKey]);
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

    /// ========================================
    /// CHILD/PARENT OPERATIONS
    /// Uses parentId for hierarchical relationships
    /// ========================================

    /// Reparent children to a new parent
    function reparent(parent, children) {
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

    /// Pick children and add them to parent
    /// ctx: { entity, field, cancelPicker }
    function pickChild(ctx, name, data) {
        let entity = ctx.entity;
        let field = ctx.field;

        reparent(entity, data).then(function() {
            let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
            vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");

            if (ctx.cancelPicker) ctx.cancelPicker(true);

            /// Don't update because the new lineage is already persisted
            am7client.clearCache(entity[am7model.jsonModelKey], false, function(s, v) {
                m.redraw();
            });
        });
    }

    /// Add child via picker
    /// ctx: { preparePicker, field }
    function addChild(ctx, name, tableType, tableForm, props) {
        if (props.picker && ctx.preparePicker) {
            return ctx.preparePicker(tableType, function(data) {
                pickChild(ctx, name, data);
            });
        }
        return Promise.resolve(null);
    }

    /// Delete selected children (removes parent relationship)
    /// ctx: { entity, field, valuesState }
    function deleteChild(ctx, name, field, tableType, tableForm, props) {
        let entity = ctx.entity;
        let valuesState = ctx.valuesState || {};
        let aC = [];

        Object.keys(valuesState).forEach(function(k) {
            let state = valuesState[k];
            let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);

            if (state.selected && vProp[name]) {
                let obj = vProp[name][state.index];
                aC.push(obj);
                /// Update UI immediately
                vProp[name] = vProp[name].filter(function(o) {
                    return o.objectId !== obj.objectId;
                });
            }
        });

        return reparent(null, aC).then(function() {
            am7client.clearCache(entity[am7model.jsonModelKey], false, function(s, v) {
                m.redraw();
            });
        });
    }

    /// ========================================
    /// SPECIAL OPERATIONS
    /// ========================================

    /// Get controls for an object
    /// ctx: { entity }
    function objectControls(ctx, name, field) {
        return new Promise(function(res, rej) {
            let entity = ctx.entity;
            if (!entity || !entity.objectId) {
                res([]);
                return;
            }
            page.controls(entity[am7model.jsonModelKey], entity.objectId).then(function(v) {
                res(v);
            });
        });
    }

    /// Get requests for an object (incomplete implementation)
    /// ctx: { entity }
    function objectRequests(ctx, name, field) {
        return new Promise(function(res, rej) {
            let entity = ctx.entity;
            if (!entity || !entity.objectId) {
                res([]);
                return;
            }
            page.requests(entity[am7model.jsonModelKey], entity.objectId).then(function(v) {
                res(v);
            });
        });
    }

    /// ========================================
    /// EXPORT
    /// ========================================

    let membership = {
        /// Member operations
        pickMember: pickMember,
        addMember: addMember,
        deleteMember: deleteMember,
        objectMembers: objectMembers,
        memberObjects: memberObjects,

        /// Entity operations
        pickEntity: pickEntity,
        addEntity: addEntity,
        openEntity: openEntity,
        deleteEntity: deleteEntity,

        /// Child/Parent operations
        reparent: reparent,
        pickChild: pickChild,
        addChild: addChild,
        deleteChild: deleteChild,

        /// Special operations
        objectControls: objectControls,
        objectRequests: objectRequests
    };

    /// Register with page.components
    if (typeof page !== "undefined" && page.components) {
        page.components.membership = membership;
    }

}());
