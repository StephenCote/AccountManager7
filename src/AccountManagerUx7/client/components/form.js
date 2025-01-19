(function(){
    let form = {};
    let entity = {};
    let entityName = "sig";
    let changed = false;
    let app;
    let view;

    function updateChange(evt){
        changed = true;
    }

    function renderFormView(attrs){
            return m("div",{class : "content-outer"},[ (fullMode ? "" : m(page.components.navigation)),
              m("div", {class: "content-main"}, [
                m("div", {class: "carousel"}, [
                  m("div", {class : "carousel-inner"}, [
                    renderInnerFormView(attrs),
                    m("span", {onclick : toggleCarouselFull, class : "carousel-full"}, [m("span",{class : "material-symbols-outlined"}, (fullMode ? "close_fullscreen" : "open_in_new"))]),
                    m("span", {onclick : toggleCarouselMax, class : "carousel-max"}, [m("span",{class : "material-symbols-outlined"}, (maxMode ? "photo_size_select_small" : "aspect_ratio"))]),
                    m("span", {onclick : toggleInfo, class : "carousel-info"}, [m("span",{class : "material-symbols-outlined" + (info ? "" : "-outlined")}, (info ? "info" : "info"))]),
                    m("span", {onclick : function(){editItem(pr[pages.currentItem]);}, class : "carousel-edit"}, [m("span",{class : "material-symbols-outlined"}, "edit")]),
                    m("span", {onclick : toggleCarousel, class : "carousel-exit"}, [m("span",{class : "material-symbols-outlined"},"close")])
                  ])
                ])
              ])
            ]);
    }
    function renderInnerFormView(attrs){
        
        let cls = 'carousel-item';
        let object = attrs.object;
        let active = attrs.active;
        //let app = attrs.app;

        let ctx = page.context();

        if(active) cls += ' carousel-item-abs';
        let maxMode = attrs.maxMode;
        if(!object || !object.model){
            console.warn("Invalid object");
            return "";
        }

        let type = am7model.getModel(object.model);
        let objView = "";
        
        if(ctx.contextObjects[object.objectId] && !ctx.contextObjects[object.objectId].populated){
            logger.warn("Form is cached unpopulated.  This shouldn't have happened.");
            delete ctx.contextObjects[object.objectId];
        }
        if(!ctx.contextObjects[object.objectId]){
           return [];
        }
        else{
            let objDat = ctx.contextObjects[object.objectId];
            objView = m("div", {class: "carousel-article-outer"}, m("div", {class: "carousel-article carousel-article-margin"}, [
                active ? renderForm(objDat) : ""
            ]));
        }

        let useCls = cls + " carousel-item-" + (active ? "100" : "0");
        return m("div", {class : useCls}, objView);
    }

    function getRowCount(object){
        return (object.elements.length && object.elements[0].elementValues.length ? getMaxElementValue(object, object.elements[0].elementValues) : 1);
    }
    function addFormRow(object){
        let row = entity.rowCount;
        object.elements.forEach((e, i)=>{
            let val = new org.cote.objects.formElementValueType();
            val.formId = object.id;
            val.textValue = "";
            e.elementValues.push(val);
            setEntityBinding(object, e, row, i);
        });
        console.log("Add row " + entity.rowCount);
        entity.rowCount++;
    }

    function removeFormRow(object, index){
        form = page.form(app);
        object.elements.forEach((e, i)=>{
            let name = "fe-" + e.elementName + "-" + index;
            delete entity[name];
            form.removeElement(form.getElementByName(name));
        });
    }

    let patterns = [];
    function getPatternType(rule){
		var s = "";
		switch(rule.validationType){
			case "BOOLEAN":
				s = "bool";
				break;
			case "REPLACEMENT":
				s = "replace";
				break;
			case "NONE":
				s = "none";
				break;
		}
		return s;
	}

    let invalid = [];
    function validateForm(object){
        
        invalid = [];
        var oF = getAppForm();
        let formVal = Hemi.data.form.service.validateForm(oF.i, 0);

        let row = 0;
        if(!formVal){
            object.template.elements.forEach((e)=>{
                let name = "fe-" + e.elementName + "-" + row;
                let el = document.querySelector("[name=" + name + "]");
                let pid = el.getAttribute("pattern-id");
                if(pid){
                    let vres = Hemi.data.validator.service.validateField(el, pid);
                    if(!vres){
                        invalid.push({name, label: e.name, pattern: pid});
                    }
                }
            });
        }
        return !invalid.length;
    }

    function getAppForm(){
        return Hemi.data.form.service.getFormByName(app.getTemplateSpace().space_id);
    }
	function isSupportedBinaryType(type) {
		var out_bool = false;
		switch(type){
			case "resource":
			case "schedule":
			case "estimate":
			case "time":
			case "note":
			case "data":
			case "form":
			case "task":
			case "tickt":
			case "model":
			case "artifact":
			case "stage":
			case "case":
			case "work":
			case "goal":
			case "budget":
			case "cost":
				out_bool = true;
				break;
			default:
				break;
		}
		return out_bool;
	}
    let entityMap = [];
    async function saveForm(object){
        let xtr = entity;
        window.dbgEntityMap = entityMap;
        window.dbgObject = object;
        window.dbgEntity = entity;
        if(!validateForm(object)){
            m.redraw();
            return;
        }
        let row = 0;
        for(let ri = 0; ri < entity.rowCount; ri++){
            let testEl = object.elements[0];
            if(!testEl){
                console.warn("No element exists");
                continue;
            }
            let testName = "fe-" + testEl.elementName + "-" + ri;
            if(typeof entity[testName] === "undefined"){
                console.log("Skip", testName, entity[testName]);
                continue;
            }
            else{
                console.log("Continue at " + ri, testName, entity[testName]);
            }
            object.elements.forEach((e, i)=>{
                if(row == 0) e.elementValues = [];
                let ename = "fe-" + e.elementName + "-" + ri;

                let val = new org.cote.objects.formElementValueType();
                val.name = ename;
                val.isBinary = isSupportedBinaryType(e.elementType);

                
                if(entityMap[e.elementName]){
                    if(val.isBinary) val.binaryId = parseInt(entityMap[ename]);
                    else val.textValue = entityMap[ename];
                }
                else{
                    val.textValue = entity[ename];
                }
                //console.log(i, object.elements);
                console.log("Assign value: " + ename + " == " + val.textValue + " at row " + row, val);
                /*
                e2.elementValues.forEach((v2)=>{
                    if(v2.textData === val.textData) val.name = v2.name;
                });
                */                
                e.elementValues[row] = val;
            });
            row++;
        }

        let updated = await page.patchObject(object);
        am7client.clearCache("FORM", true);
        if(object.objectId){
            page.clearContextObject(object.objectId);
        }
        if(updated){
            console.log("Saved the form");
            changed = false;
            if(view && view.closeView){
                view.closeView();
            }
            else{
                console.log("Note: There is a caching issue with the form refreshing after an update where the values aren't correctly populated");
                m.redraw();
            }
        }
        else{
            console.log("Error updating form");
        }
    }

    function modelRule(rule){
        return new Promise((res, rej)=>{
            if(!rule) rej("Invalid rule");
            let rid = rule.urn;
            if(Hemi.data.validator.service.getPattern(rid)){
                res();
            }
            (rule.populated ? Promise.resolve(rule) : new Promise((res2, rej2)=>{
                am7client.get("VALIDATIONRULE",rule.objectId, function(v){
                    res2(v);
                });
            })).then((rule2)=>{
                var oDV = Hemi.data.validator.definitions.service;
                var aInc;
                let aP = [];
                rule.rules.forEach((r)=>{
                    if(!aInc) aInc = [];
                    let oP = (r.populated ? Promise.resolve(r) : new Promise((res3, rej3)=>{
                        am7client.get("VALIDATIONRULE", r.objectId, function(s2, v2){
                            if(v2 && v2.json) v2 = v2.json;
                            res3(v2);
                        });
                    }));
                    aP.push(oP);
                    oP.then((crule)=>{
                        aInc.push(crule.urn);
                        aP.push(modelRule(crule));
                    });
                });
                patterns.push(rid);
                oDV.addNewPattern(
                    rid,
                    getPatternType(rule),
                    (rule.validationType == "BOOLEAN" ? (rule.comparison ? "true" : "false") : 0),
                    rule.expression,
                    (rule.validationType == "REPLACEMENT" ? rule.replacementValue : 0),
                    rule.allowNull, rule.errorMessage, aInc
                );
                Promise.all(aP).then(()=>{
                    res();
                });
            });
        });
    }
    function modelElement(object, element, row){
        let el = [];
        row = row || 0;
        let name = "fe-" + element.elementName + "-" + row;
        let component = "";
        let type;
        let patternId;
        if(element.validationRule){
            modelRule(element.validationRule);
            patternId = element.validationRule.urn;
        }
        
        let fHandler = function(e){
            updateChange(e);
        };

        let err = invalid.filter((i)=>i.name===name);
        let errCls = "";
        if(err.length){
            errCls = " field-error";
        }
        let cls = "text-field-full" + errCls;
        switch(element.elementType){
            case "SELECT":
            case "MULTIPLE_SELECT":
                cls = "select-field-full" + errCls;
                el.push(m("select", {onchange: fHandler, class: cls, name: name}, element.elementValues.map((v)=>{
                    return m("option",{value: v.textValue}, v.name);
                })));

                break;
            case "BOOLEAN":
                cls = "check-field" + errCls;
                el.push(m("input", {onchange: updateChange, name: name, type: "checkbox", class: cls, "pattern-id": patternId} ));
                break;
            case "DATE":
                component = "calendar";
                type = "datetime-local";
            case "STRING":
                if(!type) type = "text";
                el.push(m("input", {onchange: updateChange, name, type: type, component, class: cls, "pattern-id": patternId} ));
                break;
            default:
                console.warn("Unhandled: " + element.elementType);
                break;
        }
        return el;
    }
    function modelElementContainer(object, element, layout){
        let className = "field-grid-item-" + (layout || "full")
        let labelClass = "field-label";
        if(object.template.isGrid){
            console.warn("Not applicable to a grid layout");
           return "";
        }
        else{
            return m("div",{class: className},[
                m("label",{class : labelClass, for: element.elementName}, element.elementLabel || element.name),
                modelElement(object, element)
            ]);
        }
    }
    function renderForm(object){
        let formView = [];
        let els = (object.isTemplate ? object : object.template).elements;
        let grid = (object.isTemplate ? object : object.template).isGrid;
        let altCls = '';
        if(changed) altCls = ' warn';
        formView.push(m("h3", object.name));
        formView.push(m("div",{class: "result-nav-outer"},[
            m("div",{class: "result-nav-inner"},[
                m("div",{class: "result-nav tab-container"},[
                    page.iconButton("button" + altCls,"save", "", function(){saveForm(object);}),
                    (grid ? page.iconButton("button","add", "", function(){addFormRow(object);}) : ""),
                    //(grid ? page.iconButton("button","delete_outline", "", function(){removeFormRow(object);}) : "")
                    //, page.iconButton("button","cancel", "", function(){saveForm(object);}),
                ])
            ])
        ]));
        if(object.isTemplate){
            formView.push(m("h2", "Form Template View Not Currently Supported"));
        }
        else if(object.template.isGrid){
            let rowE = [];
            let rows = getRowCount(object);
            let skipDel = false;
            if(entity && entity.rowCount){
                rows = entity.rowCount;
                skipDel = true;
            }
            for(let i = 0; i < rows; i++){

                let skipRow = false;
                let cells = els.map((e, i2)=>{
                    let cell;
                    if(skipRow || (skipDel && typeof entity["fe-" + e.elementName + "-" + i] === "undefined")){
                        // console.log("Skip " + e.elementName + " at row " + i, entity);
                        cell = "";
                        skipRow = true;
                    }
                    else{
                        cell = modelElement(object, e, i);
                    }
                    return m("td", cell);
                });
                //if(!skipRow){
                    cells.push(m("td",(skipRow ? "" : page.iconButton("button ml-4","delete_outline", "", function(){removeFormRow(object, i)}))));
                    rowE.push(
                        m("tr", cells)
                    );
                //}
            }
            formView.push(m("table", {class: "table-field"},[
                m("tr", els.map((a)=>{
                    return m("th", a.elementLabel)
                }), m("th","")),
                rowE
            ]));
        }
        else{
            els.forEach((e, i)=>{
                formView.push(modelElementContainer(object, e, "two-thirds"));
            });
        }
        return formView;
    }
    function init(vnode){
        let attrs = vnode.attrs;
        let object = attrs.object;
        let active = attrs.active;
        let ctx = page.context();
        if(!ctx.contextObjects[object.objectId]){
            page.openObject(object.model, object.objectId).then((a)=>{
                /// don't set the app here - let the form render
                m.redraw();
            });
        }
        else{
            setApp(vnode, ctx.contextObjects[object.objectId]);
        }
    }
    function setApp(vnode, object){
        if(vnode.dom){
            if(!app){
                //console.log("Init form entity");
                // console.log("Form DOM", vnode);
                entity = {uid:Hemi.guid()};
                setEntity(object);
                //app = page.space(entityName, vnode, entity);

                window.dbgApp = app;
                window.dbgEntity = entity;
                window.dbgObject = object;
            }
            else{
                //page.extendSpace(app.getTemplateSpace(), vnode.dom);
                let rows = vnode.dom.querySelectorAll("tr");
                let bind = false;
                rows.forEach((row)=>{
                    if(row.firstChild && row.firstChild.getAttribute("hemi-id") == null){
                        bind = true;
                        page.extendSpace(app, row);
                    }
                });
                /// Due to how space parsing works, there is a promise buried inside that doesn't lead to an arbitrary notification for the app component to pick up when binding to dynamically added inputs
                /// Therefore, wait a moment and then bind
                if(bind){
                    setTimeout(function(){
                        page.bindEntity(app, entity);
                    }, 1);
                }
            }
        }
    }
    function getMaxElementValue(form, vals){
		return Math.max(vals.length - getDefaultOffset(form, vals), 1);
	}
    function getDefaultOffset(form, vals){
		let i = 0, v = 0;
		for(;i < vals.length; i++){
			if(vals[i].formId != form.id) v++;
		}
		return v;
	}
    function setEntityBinding(object, element, row, index){
        let ename = "fe-" + element.elementName + "-" + row;
        let e2 = Object.assign({},object.elements[index]);

        /// Filter off the defaults
        let ev = (e2 ? e2.elementValues : []).filter((e3)=>{
            return e3.formId == object.id;
        });
        let evv = ev.length > row ? ev[row] : undefined;
        switch(element.elementType){
            case "BOOLEAN":
                entity[ename] = evv ? evv.textValue === "true" : false;
                break;
            case "DATE":
                entity[ename] = evv && evv.textValue && evv.textValue.length > 0 ? new Date(evv.textValue) : new Date();
                // console.log(ename, entity[ename]);
                break;

            default:
                entity[ename] = evv ? evv.textValue : "";
        }
    }
    
    function setEntity(object){
        /// copy template fields into the entity object
        if(!object){
            console.error("Form is null");
            return;
        }
        else if(object.isTemplate){
            console.log("Skip template");
            return;
        }
        else if(object.template == null){
            console.error("Form template is null");
            return;
        }
        let rows = getRowCount(object);
        entity.rowCount = rows;
        for(let i = 0; i < rows; i++){
            object.template.elements.forEach((e, i2)=>{
                setEntityBinding(object, e, i, i2);
            });
        }
    }

    form.component = {
        oncreate : function(vnode){
            init(vnode);
            if(vnode.attrs && vnode.attrs.view) view = vnode.attrs.view;
        },
        onupdate : function(vnode){
           init(vnode);
        },
        onremove : function(vnode){
            if(app) app.destroy();
            app = undefined;
        },
        view : function(vnode){
            if(vnode.attrs.inner) return renderInnerFormView(vnode.attrs);
            else return renderFormView(vnode.attrs);
        }
    };

    page.components.form = form.component;

}());
