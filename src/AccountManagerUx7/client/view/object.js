(function(){
    function newObjectPage(){
        let objectPage = {};
        let entity;
        let inst;
        let pinst = {};
        let oinst = {};
        let app;
        let tabIndex = 0;
        let pickerMode = {};
        let childPickerMode;
        let fullMode = false;
        let designMode = false;
        let changed = false;
        let caller;
        let callerIndex;
        let callerActive;

        let pendingResponse;
        let valuesState = {};
        let foreignData = {};
        let definition;
        let listView = page.views.list().view;
        let requesting = false;
        let pendingRequest;

        let embeddedMode = false;
        let embeddedController;
        let showFooter = false;

        let objectType;
        let objectId;
        
        let objectNew;
        let parentNew;
        let streamSeg;

        let parentProperty;

        objectPage.pinst = function(){
            return pinst;
        };
        objectPage.oinst = function(){
            return oinst;
        };



        function toggleFullMode(){
            fullMode = !fullMode;
            if(embeddedController){
                embeddedController.toggleFullMode();
            }
            else{
                m.redraw();
            }
        }

        async function toggleDesignMode(){
            if(!designMode){
                /// Refresh the entity byte array when temporarily shifting views in the event it changed
                /// Note: this is necessary because the field is not directly associated to the view, and isn't picked up by synchronizing the component.
                let mt = document.querySelector("[name='contentType']").value;
                let txtEl = document.querySelector("[name='dataBytesStore-mt']");
                if(mt.match(/javascript/gi) && txtEl && txtEl.value === ""){
                    let v = await m.request({method: "GET", responseType: "text", withCredentials: true, url: g_application_path + "/rest/script/template"});
                    console.log(g_application_path + "/rest/script/template", v);
                    if(v && v != null){
                        txtEl.value = v;
                        toggleDesignMode();
                        return;
                    }
                    
                }
                if(txtEl){
                    inst.api.dataBytesStore(txtEl.value);
                    if(inst.api.compressionType){
                        inst.api.compressionType("none");
                    }
                }
            }
            else if(designMode && objectPage.transitionHandler){
                objectPage.transitionHandler(objectPage, entity);
            }
            designMode = !designMode;
            m.redraw();
        }
        /*
        function textField(sClass, id, fKeyHandler){
            return m("input",{onkeydown: fKeyHandler, rid: id, type: "text", class: sClass});
        }
        */
        function isBinary(mt){
            let contentType = mt;
            if(contentType || (entity && (contentType = entity.contentType))){
                return (
                    !contentType.match(/^text/gi)
                    &&
                    !contentType.match(/application\/json/)
                    &&
                    !contentType.match(/javascript$/gi)
                );
            }
            return false;
        }
        function doUpdate(){
            if(!entity){
                console.error("No entity defined");
                return;
            }
            if(!entity.model || entity.model === 'UNKNOWN'){
                console.error("Unknown entity type");
                return;
            }
            let syncText = true;
            /// If clicking 'update' while in design mode, transfer the rich edit buffer back to the entity
            ///
            if(designMode && objectPage.transitionHandler){
                console.log("Consume design buffer");
                objectPage.transitionHandler(objectPage, entity);
                syncText = false;
                designMode = false;
            }

            /// Synchronize the form with the entity
            /// 
            if(entity.model.match(/^data\.data$/i)){
                if(isBinary()){
                    console.log("Don't update binary content via form.");
                    inst.api.dataBytesStore("");
                    inst.unchange("dataBytesStore")
                }
                else if(syncText){
                    if(!inst.changes.includes("dataBytesStore")) inst.changes.push("dataBytesStore");
                    let txtEl = document.querySelector("[name='dataBytesStore-mt']");
                    if(txtEl){
                        inst.api.dataBytesStore(txtEl.value);
                        if(inst.api.compressionType){
                            inst.api.compressionType("none");
                        }
                    }
                    else{
                        console.warn("Did not locate textarea"); 
                    }
                }
            }
            else if(entity.model.match(/^message$/i)){
                let txtEl = document.querySelector("[name='data-mt']");
                if(!inst.changes.includes("data")) inst.changes.push("data");
                if(txtEl) entity.data = Base64.encode(txtEl.value);
                else{
                    console.warn("Did not locate textarea"); 
                }
            }
            let bpatch = am7model.hasIdentity(entity);


            /// check for updates via nested object references
            /// Eg: Object.childObject.property
            let aP = [];
            Object.keys(pinst).forEach(k => {
                let e = pinst[k];
                let upatch = e.hasIdentity();
                let puint = (upatch ? e.patch() : e.entity);
                /// Make sure to create the object, even if it has no changes
                /// OR - if nothing has changed, it needs to be stripped off the entity, pinst, and oinst 
                //if(puint && (!upatch || e.changes.length)){
                
                if(puint && bpatch && (!upatch || e.changes.length)){
  
                    aP.push(am7client[upatch ? "patch" : "create"](e.entity.model, puint, function(v){
                        e.resetChanges();
                        if(!upatch){
                            for(let i in v){
                                e.entity[i] = v[i];
                            }
                            /// For create operations, substitute the whole object for the minimal id reference received in the response
                            // e.entity
                            inst.api[k](v);
                        }
                        else{
                            inst.unchange(k);
                        }
                    }));
                }
            });
            if(aP.length){
                Promise.all(aP).then(() => {
                    console.log("Continue with base update");
                    doUpdate();
                });
                return;
            }

            if(!inst.changes.length){
                console.warn("Nothing to update");
                changed = false;
                return;
            }
            
            let uint = (bpatch ? inst.patch() : entity);
            if(!uint){
                console.warn("Nothing to update");
                return;
            }

            am7client[bpatch ? "patch" : "create"](entity.model, uint, function(v){
                if(v != null){
                    let bNew = objectNew || parentNew;
                    changed = false;
                    inst.resetChanges();
                    if(entity.model.match(/message/i)){
                        history.back();
                    }
                    else{
                        am7client.clearCache(entity.model, false, function(){
                            if(!bpatch){
                                entity = v;
                            }
                            page.clearContextObject(entity.objectId);
                            objectNew = false;
                            parentNew = false;
                            if(embeddedMode && embeddedController){
                                embeddedController.editItem(entity);
                            }
                            else if(bNew){
                                let uri = "/view/" + entity.model + "/" + entity.objectId;
                                m.route.set(uri, {key: entity.objectId});
                            }
                            else{
                                m.redraw();
                            }
                        });
                    }
                }
                else{
                    console.log("What's that?");
                    console.error(v);
                }
            });
        }
        function cancelPicker(bNoRefresh){
            if(childPickerMode){
                childPickerMode.cancelPicker(bNoRefresh);
                childPickerMode = undefined;
            }
            pickerMode.enabled = false;
            if(!bNoRefresh){
                m.redraw();
            }
        }
        function enablePicker(bNoRefresh){
            console.log("Enable picker");
            pickerMode.enabled = true;
            if(caller){
                caller.setChildPickerMode(objectPage);
            }
            if(!bNoRefresh){
                m.redraw();
            }
        }

        function doCancel(){
            if(pickerMode.enabled || childPickerMode){
                cancelPicker();
            }
            else if(embeddedMode && embeddedController){
                embeddedController.cancelView();
            }
            else{
                history.back();
                m.redraw();
            }
        }
        async function doCopy(){
            if(!entity || !entity.objectId) return;
            if(am7model.hasField(entity.model, "name")){
                entity.name += " Copy";
            }
            entity.objectId = undefined;
            entity.id = undefined;
            entity.urn = undefined;
            resetEntity(entity);
            m.redraw();
        }
        async function doDelete(){
            if(!entity || !entity.objectId) return;
            let type = entity.model;
            let path = entity.groupPath;
            page.confirm("Delete this object?", async function(){
                await page.deleteObject(entity.model, entity.objectId);
                // page.pagination.new();
                history.back();
            });
        }
        function buttonTab(active, label, fHandler){
            let icoName = (active ? "tab" : "tab_unselected");
            let cls = "button-tab" + (active ? " button-tab-active" : "");
            return m("button", {class : cls, onclick : fHandler}, m("span",{class : "material-icons-outlined mr-2"}, icoName), label);
        }
        function switchTab(i){
            tabIndex = i;
            cancelPicker();
        }

        function modelFormTab(active, type, form){
            return m("div",{class : "tab-panel" + (!active ? " hidden" : "")}, modelForm(active, type, form));
        }

        function getFormatForType(type){
            let format = "text";
            switch(type){
                case "list":
                case "enum":
                    format = "select";
                    break;
                case "zonetime":
                case "timestamp":
                case "datetime":
                    format = "datetime-local";
                    break;
                case "boolean":
                    format = "checkbox";
                    break;
                default:
                    /// console.log("Default " + type + " to " + format);
                    break;
            }
            return format;
        }
        function getFormatForContentType(format){
            if(!entity || !entity.contentType){
                return "textarea";
            }
            /*
            if(!entity.contentType){
                console.log("Entity does not define a contentType");
                return format;
            }
            */
            let mt = entity.contentType;
            if(!isBinary(mt)) format = 'textarea';
            else if(mt.match(/^image/)) format = 'image';
            else{
                console.warn("Unhandled contentType: " + mt);
            }

            return format;
        }


        function getFormCommands(name, form, field, bSel, bEdit, bAdjust){
            if(!form.commands) return [];
            let iter = 0;
            return Object.keys(form.commands).map((k)=>{
                let cmd = form.commands[k];
                let active = false;
                /// check for a condition to restrict activation
                if(cmd.requiredAttribute && !cmd.condition){
                    if(entity && entity[cmd.requiredAttribute]){
                        active = true;
                    }
                }
                else if(!cmd.condition || (bSel && cmd.condition.includes("select")) || (bEdit && cmd.condition.includes("edit"))) active = true;

                let icon = cmd.icon;
                let func = cmd.function;
                if(cmd.altCondition && bEdit && cmd.altCondition.includes("edit")){
                    func = cmd.altFunction;
                    icon = cmd.altIcon;
                }
                iter++;
                let cls = "button" + (active ? "" : " inactive") + (bAdjust && iter == 1 ? " ml-4" : "");
                return page.iconButton(cls, icon, "", function(){
                    objectPage[func](name, field, (field ? field.baseModel : name), form, cmd.properties);
                });
            })
        }
        function getFormCommandView(name, form, field, bSel, bEdit){
            if(!form.commands) return [];
            return [m("div",{class: "result-nav-outer"},[
                m("div",{class: "result-nav-inner"},[   
                    m("div",{class: "result-nav"},
                        getFormCommands(name, form, field, bSel, bEdit)
                    )
                ])
        ])];
        }

        function getValuesForFormField(name, fieldView, field, fieldClass, hidden){
            let cmds = [];
            let rows = [];
            /// Get the field for the viewModel off the itemType of the supplied field
            if(!entity) return rows;

            if(!field.function){
                console.error("Expected the field for " + name + " to specify a function");
                //console.log(field);
                return rows;
            }

            return objectPage[field.function](name, fieldView, field, fieldClass, hidden);
        }

        function getValuesForTableField(name, fieldView, field, fieldClass, hidden){
            let cmds = [];
            let rows = [];
            //console.log(name, fieldView, field);
            /// Get the field for the viewModel off the itemType of the supplied field
            if(!entity) return rows;
            if(!field.baseModel){
                console.error("Expected the itemType to include field definitions for formatting the table");
                return rows;
            }
            if(!fieldView.form){
                console.error("Expected the field view for " + name + " to specify an additional form");
                console.log(fieldView);
                return rows;
            }
            let tableType = field.baseModel;
            if(tableType == "$self"){
                tableType = entity.model;
            }
            let tableForm = fieldView.form;
            let bEdit = false;
            let bSel = false;

            if(field.foreign){
                if(!foreignData[name]){
                    if(field.function){
                        objectPage[field.function](name, field).then((data) => {
                            //let fData = data || [];
                            foreignData[name] = data;
                            m.redraw();
                        });
                        return rows;
                    }
                }
            }

            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                //console.log(name, state);
                if(state.attribute === name){
                    if(state.mode === 'edit') bEdit = true;
                    if(state.selected) bSel = true;
                }
            });
            if(!hidden && tableForm.commands){
                cmds = getFormCommandView(name, tableForm, field, bSel, bEdit);
            }

            let rowModel = [];
            Object.keys(tableForm.fields).forEach((k)=>{
                let tableField = am7model.getModelField(tableType, k, am7view.typeToModel(entity[field.foreignType]));
                let tableFieldView = tableForm.fields[k];
                rowModel.push({name: k, fieldView : tableFieldView, field : tableField});
            });
            
            if(!hidden){
                rows.push(m("tr",rowModel.map((r)=>{
                    if(!r.field){
                        r.field = am7model.getModelField(tableType, r.name);
                        //console.error(field.baseModel, r);
                    }
                    return m("th", r.fieldView?.label || r.field?.label);
                })));
            }

            if(entity){
                
                let vals;
                if(inst.api[name]) vals = inst.api[name]();
                else if(field.foreign) vals = foreignData[name];
                else if(field.parentProperty){
                    if(entity[field.parentProperty]){
                        vals = entity[field.parentProperty][name];
                    }
                }
                else vals = entity[name];
                if(vals && field.type.match(/^(array|list)$/)){
                    vals.forEach((v, i) => {
                        if(!valuesState[name + "-" + i]){
                            //if(name === 'elementValues' || name === 'attribute'){
                                valuesState[name + "-" + i] = {attribute: name, foreign: field.foreign, index : i, selected: false, mode: 'view'};
                            //}
                            /*
                            else if(name === 'selectOption'){
                                valuesState[name + "-" + i] = {attribute: name, fieldName: 'elementValues', foreign: field.foreign, index : i, selected: false, mode: 'view'};
                            }
                            
                            else{
                                console.error("Handle: " + name);
                            }
                            */
                        }
                        let state = valuesState[name + "-" + i];
                        let rowClass = "";
                        if(state.selected) rowClass = "row-field-active";
                        let selectHandler;
                        if(state.mode === 'view'){
                            rowClass += " row-field";
                            //if(state.selected) rowClass += " row-field-active";
                            selectHandler = function(){
                                valuesState[name + "-" + i].selected = !valuesState[name + "-" + i].selected;
                                m.redraw();
                            }
                        }
                        rows.push(m("tr", {class: rowClass, onclick : selectHandler},
                            rowModel.map((rm)=>{
                                if(state.mode == 'edit') return m("td", modelField(rm.name, rm.fieldView, rm.field, rm.name + "-" + i, v[rm.name], true, v));
                                else{
                                    let val;
                                    if(rm.field.pickerProperty){
                                        let findVal = v[rm.field.pickerProperty.entity];
                                        let ctx = page.context();
                    
                                        if(findVal){
                                            if(typeof findVal === 'object'){
                                                val = findVal.name;
                                            }
                                            else if(!ctx.contextObjects[findVal]){
                                                let type = rm.field.pickerType;
                                                if(!type || type === 'self') type = v.model;
                                                else type = type;
                                                if(type.match(/^\./)){
                                                    type = v[rm.field.pickerType.slice(1)];
                                                }
                                                page.openObject(type, findVal).then((a)=>{
                                                    m.redraw();
                                                });
                                            }
                                            else {
                                                val = ctx.contextObjects[findVal].name;
                                            }
                                        }
                                    }
                                    else{
                                        val = v[rm.name];
                                    }
                                    return m("td", "" + val);
                                }
                            })
                        ))
                    })
                }
                else{
                    // console.log("What to do: " + name + " / " + field.type + " / " + vals);
                }
            }

            return [cmds, m("table",{class: fieldClass}, rows)];
        }

        let spans = ["row-span-1","row-span-2", "row-span-3", "row-span-4", "row-span-5"];
        let spans2 = ["row-span-auto","row-span-full"];

        function modelFieldContainer(name, fieldView, field){
            let className = "field-grid-item-full";
            if(fieldView.layout == "half") className = "field-grid-item-half";
            else if(fieldView.layout == "fifth") className = "field-grid-item-fifth";
            else if(fieldView.layout == "two-thirds") className = "field-grid-item-two-thirds";
            else if(fieldView.layout == "third") className = "field-grid-item-third";
            else if(fieldView.layout == "one") className = "field-grid-item-one";
            if(fieldView.rows) className += " " + spans[fieldView.rows-1];
            let labelClass = "field-label";
            let show = showField(fieldView);
            
            if(!show){
                labelClass += " text-gray-300"
            }
            let lbl = (fieldView.label || field.label || field.name);

            return m("div",{class: className},[
                m("label",{class : labelClass, for: name}, lbl), //  || name
                modelField(name, fieldView, field)
            ]);

        }

        function getObjectProperty(name, field){
            let val;
            if(!entity || entity === null) return val;

            if(field.baseModel && field.typeAttribute){
                let vVal = entity[field.typeAttribute]
                if(!vVal){
                    console.log("Entity doesn't define " + field.typeAttribute);
                    return val;
                }
                if(field.property){
                    let vProp = vVal[field.property];
                    if(!vProp){
                        console.warn(field.typeAttribute + " doesn't define " + field.property);
                        return val;
                    }
                    val = vProp;
                }
            }

            return val;
        }

        function updateChange(evt, field){
            changed = true;
            if(entity && field && field.pickerProperty && field.pickerProperty.entity){
                entity[field.pickerProperty.entity] = (evt.srcElement || evt.target).value;
            }
        }

        /// TODO: There's currently a bug where if a picker is based on a type, and restricted by the type value, then if the type list displays initially without being redrawn, the type
        /// value will still be the default - currently the work around is display these fields on a secondary tab
        function showField(ref, useName){
            let show = true;
            let refer = ref.referField;
            if(useName && useName.match(/(sender|recipient)/)){
                //console.log(useName, ref);
            }
            /*
            if(useName){
                refer = false;
            }
            */
            if(ref.requiredRoles){
                show = false;
                let rctx = page.context().roles;
                ref.requiredRoles.forEach((a, i) =>{
                    if(rctx[a] === true) show = true;
                });
            }
            if(show && entity && ref.requiredAttributes){
                let showCount = 0;
                ref.requiredAttributes.forEach((a, i)=>{
                    let a2 = a;
                    let invert = (a2.match(/^!/) != null);
                    if(invert){
                        a2 = a2.replace(/^!/,"");
                    }

                    let reqVal;
                    if(ref.requiredValues){
                        reqVal = ref.requiredValues[i];
                    }
                    if(ref.referField){
                        let mat;
                        if(useName && (mat = useName.match(/(-\d+)$/)) != null){
                            a2 = a2 + mat[1];
                        }
                        let e = document.querySelector('[name=' + a2 + ']');
                        if(e && e.type == 'checkbox'){
                            //show = e.checked;
                            if(e.checked || invert) showCount++;
                        }
                        else if(e) {
                            let reqVals = (reqVal ? reqVal.split("|") : [undefined]);
                            let showTotal = 0;
                            reqVals.forEach((v2)=>{
                                //console.log(a2, "Scan: " + v2, e.value);
                                if(
                                    (v2 && e.value == v2)
                                    ||
                                    (!v2 && e.value)
                                ){
                                    showTotal++;
                                }
                            });
                            // show = (reqVal && reqVal.indexOf("|") > -1 ? showTotal > 0 : showTotal == reqVals.length);
                            //showCount += (reqVal && reqVal.indexOf("|") > -1 ? showTotal > 0 : showTotal == reqVals.length) ? 1 : 0;
                            if(reqVal){
                                if(reqVal.indexOf("|") > -1 && showTotal > 0) showCount++;
                                else if(showTotal == reqVals.length) showCount++;
                            }
                            else if(showTotal > 0){
                                showCount++;
                            }
                            //console.log(a2, showCount, showTotal, ref.requiredAttributes.length);
                        }
                        else{
                            //console.log("Else: " + a2);
                        }
                        //if(invert) show = !show;
                        //console.log("Match " + a2 + " == " + reqVal + " / " + entity[a] + " == " + show);
                    }
                    else if((reqVal && entity[a2] != reqVal) || (!reqVal && ((!invert && !entity[a2]) || (invert && entity[a2])))){
                        //console.log("Else...");
                        //show = false;
                    }
                    else{
                        showCount++;
                    }
                });
                show = ref.requiredAttributes.length == showCount;
            }
            //console.log(show, ref);
            return show;
        }
        function validateFormat(field, format, useName){
            if(format === "picker" && field.pickerProperty && field.pickerProperty.requiredAttributes){
                let req = field.pickerProperty;
                if(!showField(req, useName)){
                    format = "text";
                }
                //console.log("Test show field", showField(req, useName));
            }
            return format;
        }

        function handleDragLeave(evt) {
            evt.preventDefault();
        }
        function handleDragEnter(evt) {
            evt.preventDefault();
        }
        function handleDragOver(evt) {
            evt.preventDefault();
        }
        function handleDrop(evt) {
            evt.preventDefault();
            uploadFiles(evt.dataTransfer.files);
        }

        let totalUpCount = 0;
        let currentUpCount = 0;
        let maxUpload = 10;
        async function uploadFiles(files) {

            if(!entity){
                return;
            }
            totalUpCount = files.length;
            currentUpCount = 0;
            let aP = [];
            for (var i = 0; i < files.length; i++) {
                var formData = new FormData();
                formData.append("organizationPath", am7client.currentOrganization);
                formData.append("groupPath", entity.groupPath);
                //formData.append("groupId", oGroup.id);
                let fname = files[i].name;
                formData.append("name", files[i].name);
                formData.append("dataFile", files[i]);
                aP.push(new Promise((res, rej)=>{
                    var xhr = new XMLHttpRequest();
                    xhr.withCredentials = true;
                    console.log("Posting: " + g_application_path + '/mediaForm');
                    xhr.open('POST', g_application_path + '/mediaForm');
                    xhr.onload = function(v){
                        res(fname);
                    };
                    xhr.upload.onprogress = function(event){
                        ///ctl.updateWaitingCount();
                    };
                    xhr.send(formData);
                }));
                if(i > 0 && (i % maxUpload) == 0){
                    await Promise.all(aP);
                    aP = [];
                }

            }
            return new Promise((res2, rej2)=>{
                Promise.all(aP).then((aD)=>{
                    if(aD.length == 1){
                        page.findObject("auth.group", "data", entity.groupPath).then((oG)=>{
                            if(!oG){
                                console.warn("Failed to lookup group", oG);
                            }
                            else{
                                page.openObjectByName("data.data", oG.objectId, aD[0]).then((oD)=>{
                                    if(!oD){
                                        console.warn("Failed to lookup data");
                                    }
                                    else{
                                        let uri = "/view/data.data/" + oD.objectId;
                                        m.route.set(uri, {key: oD.objectId});
                                        res2();
                                    }
                                });
                            }
                        });
                    }
                    else{
                        res2();
                    }
                });
            });
        }
        

        function modelField(name, fieldView, field, altName, altVal, noChange, altEntity){
            let useEntity = altEntity || entity;

            let format = (fieldView?.form?.format || fieldView.format || field.format || getFormatForType(field.type));
            let useName = altName || name;
            let view = [];
            let disabled = false;
            let fieldClass = "";
            if(field.readOnly || fieldView.readOnly){
                disabled = true;
                fieldClass += "field-disabled";
            }
            else if(field.required){
                fieldClass += "field-required";
            }

            if(field.foreign && field.type.match(/^(model|list)$/) && fieldView.autocreate && (!entity[name] || !entity[name].length)){
                let aom = getPrimitive(field.baseModel);
                let aname = "Auto " + field.baseModel + " " + page.luid();
                if(am7model.hasField(field.baseModel, "name")){
                    aom.name = aname;
                }
                if(field.type == 'model'){
                    entity[field.name] = aom;
                }
                else if(field.type == 'list'){
                    entity[field.name] = [aom];
                }
            }

            /*
            if(inst && !inst.api[name]){
                console.warn("Missing " + name);
            }
            */
            let defVal = (inst && !altEntity && inst.api[name] ? inst.api[name]() : undefined);
            if(field.function && objectPage[field.function] && !field.foreign){
                if(field.promise){
                    defVal = foreignData[name];
                    if(!defVal){

                        objectPage[field.function](name, field).then((v) => {
                            foreignData[name] = v;
                            inst.api[name](v);
                            m.redraw();
                        });
                    }
                }
                else{
                    defVal = objectPage[field.function](name, field);
                }
            }
            else if(altVal){
                defVal = altVal;
            }

            let fHandler;
            if(!noChange && !disabled && !field.command){
                fHandler = inst.handleChange(name, updateChange)
            }

            if(format === 'contentType'){

                useName = name + "-mt";

                /*
                if(useEntity && !isBinary(useEntity.contentType)){
                    if(useEntity[name]){
                        defVal = Base64.decode(useEntity[name]);
                    }
                }
                */

                format = getFormatForContentType(format);
                if(!noChange && !disabled){
                    /*
                    fHandler = function(e){
                        useEntity[name] = Base64.encode((e.srcElement || e.target).value);
                        if(!inst.changes.includes[name]) inst.changes.push(name);
                        / *
                        if(useEntity.model.match(/^data$/i))
                            useEntity.dataBytesStore = enc; 
                        else if(useEntity.model.match(/^message$/)){
                            useEntity.data = enc;
                        }
                        * /
                        updateChange(e);
                    }
                    */
                }
            }
            if(field.property && !defVal){
                defVal = getObjectProperty(name, field);
            }

            let dnd = {
                ondrop: handleDrop,
                ondragover: handleDragOver,
                ondragenter: handleDragEnter,
                ondragleave: handleDragLeave,
            };
            let show = showField(fieldView);

            if(!show) fieldClass += " hidden";
            switch(format){
                case "blank":
                    view.push(m("span", {class: "blank"}));

                    
                    break;
                case "button":
                    fieldClass += " button-field-full";
                    let bfHandler = function(){field.command(objectPage, inst, name);};
                    view.push(
                        m("button[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {onclick : bfHandler, class: fieldClass}, (fieldView.icon ? m("span",{class: "material-symbols-outlined material-icons-24"}, fieldView.icon) : ""), (field.label || ""))
                    );
                    break;
                case "select":
                    let vals = am7view.defaultValuesForField(field);
                    fieldClass += " select-field-full";
                    // console.log(field);
                    // console.log(vals);
                    view.push(m("select", {oninput: fHandler, class: fieldClass, name : useName}, vals.map((v)=>{
                        let selected;
                        if(defVal && (defVal === v || (typeof defVal == "string" && defVal.toLowerCase() === v.toLowerCase()))) selected = true;
                        return m("option[" + (selected ? "selected = 'true'," : "") + "value = '" + v.toLowerCase() + "']", v);
                    })));
                    break;
                case "range":
                    fieldClass += " range-field-full";
                    let min = 0;
                    let max = 100;

                    if(typeof field.minValue == "number" && typeof field.maxValue == "number"){
                        if(field.type == "double" && field.maxValue == 1){
                            max = field.maxValue * 100;
                            if(min != 0){
                                min = field.minValue * 100;
                            }
                        }
                        else{
                            min = field.minValue;
                            max = field.maxValue;
                        }
                    }
                    // view.push(m("span",{class: 'label inline w-1/4'}, defVal));
                    //view.push(m("input[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {oninput: fHandler, value: defVal, type: format, class : fieldClass, name : useName, min, max}));

                    view.push(m("div", {class: 'relative mb-5'}, [
                        m("label", {class: "sr-only"},"Range"),
                        m("input[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {oninput: fHandler, value: defVal, type: format, class : fieldClass, name : useName, min, max}),
                        m("span", {class: "text-sm text-gray-500 absolute start-0 -bottom-6"}, min),
                        m("span", {class: "text-sm text-gray-500 absolute start-1/2 -translate-x-1/2 -bottom-6"}, defVal),
                        m("span", {class: "text-sm text-gray-500 absolute end-0 -bottom-6"}, max)
                    ]));
/*
    <span class="text-sm text-gray-500 dark:text-gray-400 absolute start-1/3 -translate-x-1/2 rtl:translate-x-1/2 -bottom-6">$500</span>
    <span class="text-sm text-gray-500 dark:text-gray-400 absolute start-2/3 -translate-x-1/2 rtl:translate-x-1/2 -bottom-6">$1000</span>
*/


                    break;
                case "color":
                    fieldClass += " color-field"
                    view.push(m("input[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {oninput: fHandler, value: defVal, type: format, class : fieldClass, name : useName}));
                    break;
                case "datetime-local":
                case "text":
                    fieldClass += " text-field-full";
                    view.push(m("input[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {oninput: fHandler, value: defVal, type: format, class : fieldClass, name : useName}));
                    break;
                case "checkbox":
                    fieldClass += " check-field";
                    view.push(m("input[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {oninput: fHandler, checked: defVal, type: format, class : fieldClass, name : useName}));
                    break;
                case "print-alert":
                    if(!defVal && entity) defVal = entity[useName];
                    view.push(m("span", {"class": "text-content-alert"}, defVal));
                    break;
                case "print":
                    if(!defVal && entity) defVal = entity[useName];
                    view.push(m("span", {"class": "text-content"}, defVal));
                    break;
                case "textlist":
                case "textarea":
                    fieldClass += " textarea-field-full w-full";
                    let props = {onchange: fHandler, class : fieldClass, name : useName};
                    if(entity && !entity.objectId && fieldView.dragAndDrop){
                        props.class += " border-dotted";
                        props = Object.assign(props, dnd);
                        props.placeholder = "{ Type Text or Drop File Here }";
                    }
                    /*
                    if(name == "dataBytesStore"){
                        console.log(name, format, defVal);
                    }
                    */
                    view.push(m("textarea", props, defVal));
                    break;
                case "image":
                    fieldClass += " image-field";
                    let dataUrl;
                    let clickF;
                    if(useEntity.model.match(/^imageView$/gi)){
                        let ui = useEntity.image;
                        fieldClass = "carousel-item-img";
                        /*
                        if(ui.contentType && ui.dataBytesStore){

                            dataUrl = "data:" + ui.contentType + ";base64," + ui.dataBytesStore;
                        }
                        else{
                        */
                            dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + ui.groupPath + "/" + ui.name + "/512x512";
                        //}
                    }

                    else if(useEntity.model.match(/^data\.data$/gi)){
                        if(useEntity.stream){
                            if(streamSeg){
                                dataUrl = "data:" + useEntity.contentType + ";base64," + streamSeg.stream;    
                            }
                            else{
                                page.stream(useEntity.stream).then((o)=>{
                                    streamSeg = o;
                                    m.redraw();
                                });
                            }
                        }
                        else{
                            dataUrl = "data:" + useEntity.contentType + ";base64," + useEntity.dataBytesStore;
                        }
                    }
                    else if(useEntity.profile && useEntity.profile.portrait && useEntity.profile.portrait.contentType){
                        let pp = useEntity.profile.portrait;
                        //clickF = function(){ page.imageView(inst.api.profile().portrait);};
                        clickF = function(){ page.imageView(pp); };
                        dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/96x96";
                    }
                    else if(useEntity.portrait && useEntity.portrait.contentType){
                        let pp = useEntity.portrait;
                        //clickF = function(){ page.imageView(inst.api.profile().portrait);};
                        clickF = function(){ page.imageView(pp); };
                        dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/96x96";
                    }
                    view.push(m("img", {class: fieldClass, name : useName, onclick: clickF, src: dataUrl}));
                    break;
                case "object-link":
                    let uri = "about:blank";
                    let label = "";
                    if(useEntity && useEntity.objectId){
                        if(useEntity.model.match(/^data\.data$/gi)){
                            uri = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/" + useEntity.model + useEntity.groupPath + "/" + useEntity.name;
                        }
                        else{
                            uri = g_application_path + "/rest/model/" + useEntity.model + "/" + useEntity.objectId;
                        }
                        label = uri;
                    }
                    view.push(m("a",{href: uri}, label));
                    break;
                case 'table':
                    fieldClass += " table-field";
                    let rows = getValuesForTableField(useName, fieldView, field, fieldClass, !show);
                    view.push(rows);
                    break;
                case 'form':
                    fieldClass = "table-field";
                    let rows2 = getValuesForFormField(useName, fieldView, field, fieldClass, !show);
                    view.push(rows2);
                    break;
                case 'picker':
                    fieldClass += " text-field-full";
                    let valPick = (validateFormat(field, format, useName) === "picker");
                    if(valPick && useEntity && field.pickerProperty){
                        let findVal = useEntity[field.pickerProperty.entity];
                        let ctx = page.context();
                        if(findVal){
                            if(typeof findVal === 'object'){
                                defVal = findVal.name;
                            }
                            else if(!ctx.contextObjects[findVal]){
                                let type = field.pickerType;
                                if(!type || type === 'self') type = useEntity.model;

                                if(type.match(/^\./)){
                                    type = useEntity[field.pickerType.slice(1)];
                                }
                                page.openObject(type, findVal).then((a)=>{
                                    m.redraw();
                                });
                            }
                            else {
                                defVal = ctx.contextObjects[findVal].name;
                            }
                        }
                    }
                    else if(useEntity && field.pickerProperty){
                        defVal = useEntity[field.pickerProperty.entity];
                    }

                    let pickerName = useName + '-picker';
                    let mid = pickerName + "-menu";
                    let bid = pickerName;;

                    if(valPick){
                        fieldClass += " menu-icon";
                    }
                    else{
                        fHandler = function(e){
                            updateChange(e, field);
                        };
                    }
                    view.push(m("input[" + (disabled ? "disabled='" + disabled + "'" : "") + "]", {autocomplete:"off", onchange: fHandler, avoid: 1, value: defVal, type: 'text', class : fieldClass, name: useName, id: pickerName}));
                    if(valPick){
                        view.push(
                            m("div", {class : "context-menu-container"}, [
                                //m("button",{class: "context-menu-button", id: bid}, m("span", {class: "material-icons"}, "menu")),
                                m("div", {class : "transition transition-0 context-menu", id: mid}, [
                                    page.navigable.contextMenuButton(pickerName, "Find","search", function(){ doFieldPicker(field, useName, useEntity);}),
                                    page.navigable.contextMenuButton(pickerName, "View","file_open", function(){ doFieldOpen(field);}),
                                    page.navigable.contextMenuButton(pickerName, "Clear","backspace", function(){ doFieldClear(field);}),
                                    page.navigable.contextMenuButton(pickerName, "Cancel","cancel")
                                ])
                            ])
                        );

                        page.navigable.pendContextMenu("#" + mid, "#" + bid, null, "context-menu-96");
                    }
                    break;
                default:
                    console.error("Handle: " + format);
                    break;
            }
            return view;
        }
        async function doFieldOpen(field){
            if(!entity || !field.pickerProperty) return;
            let id;
            let type = (entity.model ? entity.model : undefined);
            let prop = field.pickerProperty.entity;
            if(field.pickerType && !field.pickerType.match(/^self$/)) type = field.pickerType;
            if(type.match(/^\./)){
                type = entity[type.slice(1)];
            }
            if(field.pickerProperty.selected === '{object}') id = entity[prop].objectId;
            else id = entity[prop];
            //console.log(id, type);
            if(id && type){
                if(typeof id == "number" || id.match(/^am:/)){
                    let obj2 = await page.openObject(type, id);
                    if(obj2 != null){
                        id = obj2.objectId;
                    }
                }
                let uri = "/view/" + type + "/" + id;
                m.route.set(uri, {key: id});            
            }
            else{
                console.warn("Missing id or type: " + id + ", " + type);
            }
        }

        function doFieldClear(field){
            if(!entity || !field.pickerProperty) return;
            let prop = field.pickerProperty.entity;
            if(field.pickerProperty.selected === '{object}') entity[prop] = null;
            else if(typeof entity[prop] == "string") entity[prop] = null;
            else if(typeof entity[prop] == "number") entity[prop] = 0;
            updateChange();

            /// Clear the server cache because the parent may have a cached copy of the child
            ///
            if(prop === 'parentId'){
                am7client.clearCache(entity.model, false, function(){});
            }
        }
        function doFieldPicker(field, useName, altEntity, altPath, pickerHandler){
            let useEntity = altEntity || entity;
            if(!useEntity || !field.pickerProperty){
                console.log("Invalid entity or field property",useEntity,field);
                return;
            }
            let mat;
            let type = (entity.model ? entity.model : undefined);
            let setType = false;
            /// Special handling for embedding pickers in disconnected tables
            ///
            if(
                field.pickerProperty.foreign
                &&
                useName
                &&
                (mat = useName.match(/(\-\d+)$/)) != null
                &&
                field.pickerType
                &&
                field.pickerType.match(/^\./)
            ){
                console.log(field.pickerType.slice(1) + mat[1]);
                let el = document.querySelector("[name='" + field.pickerType.slice(1) + mat[1] + "']");
                type = el.value.toLowerCase();
                setType = true;
            }
            else{
                if(field.pickerType && field.pickerType !== 'self') type = field.pickerType;
                if(type.match(/^\./)){
                    type = useEntity[type.slice(1)].toLowerCase();
                }
            }
            if(!type.match(/^unknown$/gi)){
                preparePicker(type, function(data){
                    if(data && data.length){
                        if(useEntity == inst.entity){
                            
                            if(field.pickerProperty.selected === '{object}'){
                                // inst.api[field.pickerProperty.entity](data[0]);

                                if(field.pickerProperty.entity.match(/\./)){
                                    let pv = field.pickerProperty.entity.split(".");
                                    if(pinst[pv[0]]){
                                        inst[pv[0]].api(data[0]);
                                        pinst[pv[0]].api[pv[1]](data[0]);
                                    }
                                    else{
                                        console.warn("didn't find " + pv[0]);
                                    }
                                    //inst.dapi(field.pickerProperty.entity, data[0]);
                                    
                                }
                                else{
                                    console.log("Update pick");
                                    inst.api[field.pickerProperty.entity](data[0]);
                                }
                            }
                            else{
                                console.log("Update", field.pickerProperty.entity);
                                //inst.api[field.pickerProperty.entity](data[0][field.pickerProperty.selected]);
                                inst.api[field.pickerProperty.entity](data[0][field.pickerProperty.selected]);
                            }
                            cancelPicker(true);
                            if(setType){
                                inst.api[field.pickerType.slice(1)](type);
                            }

                        }
                        else{
                            if(field.pickerProperty.selected === '{object}') useEntity[field.pickerProperty.entity] = data[0];
                            else useEntity[field.pickerProperty.entity] = data[0][field.pickerProperty.selected];
                            cancelPicker(true);
                            if(setType){
                                useEntity[field.pickerType.slice(1)] = type;
                            }
                        }
                        /*
                        if(field.pickerProperty.selected === '{object}') useEntity[field.pickerProperty.entity] = data[0];
                        else useEntity[field.pickerProperty.entity] = data[0][field.pickerProperty.selected];
                        cancelPicker(true);
                        if(setType){
                            useEntity[field.pickerType.slice(1)] = type;
                        }
                        */
                        updateChange();
                        if(pickerHandler){
                            pickerHandler(objectPage, inst, field, useName, data[0]);
                        }
                        /// Clear the server cache because the parent may have a cached copy of the child
                        ///
                        if(!useEntity.model || useEntity.model.match(/message/i)) m.redraw();
                        else{
                            am7client.clearCache(useEntity.model, false, function(){
                                m.redraw();
                            });
                        }
                    }
                }, altPath || field.pickerProperty.path);
            }
        }
        function modelForm(active, type, form){
            
            if(pickerMode.enabled && active){
                /// force the current form to inactive to make way for the list
                active = false;
            }
            
            if(form.model){
                if(!inst){
                    return "";
                }
                if(!form.property){
                    console.warn("A property is required");
                    return m("div", "Property name required for form");
                }
                //return m("div", "Model def");
                let minst;
                
                if(!pinst[form.property]){

                    let mf = am7model.getModelField(type, form.property);
                    if(!mf || !mf.baseModel){
                        console.warn("Property does not exist or does not define a baseModel");
                        return m("div", "Incorrect property cited for a model form");
                    }
                    let mo = inst.api[form.property]();
                    let oid;
                    let cinst;
                    if(mo){
                        oid = mo.objectId;
                    }
                    else{
                        // mo = am7model.newPrimitive(mf.baseModel);
                        mo = getPrimitive(mf.baseModel);
                        // console.log(objectType, mf.baseModel, mo);
                        inst.api[form.property](mo);
                        /// force the model def because certain settings will result in an empty object
                        mo.model = mf.baseModel;

                    }
                    //let mio = (mo || am7model.newPrimitive(mf.baseModel));
                    minst = am7model.prepareInstance(mo);
                    pinst[form.property] = minst;
                    oinst[form.property] = page.views.object();
                }
                else{
                    minst = pinst[form.property];
                }
                let ooinst = oinst[form.property];
                let props = {
                    freeForm: true,
                    freeFormEntity: minst.entity,
                    freeFormType: minst.model.name,
                    parentProperty: form.property,
                   // pickerCfg: pickerMode,
                    objectId: minst.entity.objectId,
                    callerIndex: tabIndex,
                    caller: objectPage,
                    callerActive: active
                  };
                return m(ooinst.view, props);
            }
            


            let outF = [];
            Object.keys(form.fields).forEach((e)=>{
                let field = form.fields[e].field || am7model.getModelField(type, e);
                if(!field){
                    console.warn("Failed to find field " + e + " for " + type);
                    console.warn(form);
                }
                else{
                    outF.push(modelFieldContainer(e, form.fields[e], field));
                }
            });
            return m("div", {class: "field-grid-6" + (!active ? " hidden" : "")}, outF);

        }
        function modelPicker(active){
            let pickList = "";
            if(pickerMode.enabled && active){
                pickList = m(listView, {
                    type: pickerMode.type,
                    objectId: pickerMode.containerId,
                    startRecord: 0,
                    recordCount: 0,
                    pickerMode: true,
                    pickerHandler : pickerMode.handler
                });
            }
            return pickList;
        }
        function getForm(){
            let type = objectType;
            let form = inst.form;
            if(!form){
                console.error("Invalid form for " + type);
                return;
            }
            /// Need to keep pick list separate from the form for style reasons
            ///
            let forms = [];
            let pickList;

            let cactive = (callerIndex == undefined || callerActive);

            let active = (tabIndex == 0) && cactive;
            //console.log(objectType, caller?.tabIndex() + " " + callerIndex);
            let mForm = modelForm(active, type, form);
            if(active) pickList = modelPicker(active);
            forms.push(mForm);
            (form.forms || []).forEach((f, i)=>{
                active = (tabIndex == (i + 1)) && cactive;
                let cForm = am7model.forms[f];
                if(!cForm){
                    console.warn("Failed to find form " + f);
                }
                else{
                    mForm = modelForm(active, type, cForm);
                    if(active) pickList = modelPicker(active);
                    forms.push(mForm);
                }
            });
            return [m("div",{class: "results-overflow" + (pickerMode.enabled || designMode ? " hidden" : "") + (caller ? " p-0 m-0": "")}, forms), pickList];
        }

        function getFormTabs(){
            if(!entity) return "";
            let type = objectType;
            let modType = inst.model;
            if(!inst){
                return "";
            }
            let form = inst.form;
            if(!form){
                console.warn("Invalid form for " + type);
                return;
            }

            let tabs = [];
            let active = (tabIndex == 0);
            tabs.push(buttonTab(active, form.label || type, function(){ switchTab(0);}));
            form.forms.forEach((f, i)=>{
                active = (tabIndex == (i+1));
                let cForm = am7model.forms[f];
                if(!cForm){
                    console.warn("Failed to find form to construct tab " + f);
                }
                else{
                    let show = showField(cForm);
                    if(show) tabs.push(buttonTab(active, cForm.label || f, function(){ switchTab(i + 1);}));
                }
            });
            return tabs;
        }

        function getFooter(){
            let footer = "";
            if(showFooter){
                footer = m("div",{class: "result-nav-outer"},[
                m("div",{class: "result-nav-inner"},[
                    m("div",{class: "count-label"},"..."),
                        m("nav",{class: "result-nav"},[
                        "Bottom"
                        ])
                    ])
                ]);
            }
            return footer;
        }

        function getObjectViewInner(){
            /// let bNew = m.route.get().match(/^\/new/gi);
            let bNew = objectNew || parentNew;
            let designable = (entity && entity.contentType && entity.contentType.match(/(^text\/(css|plain)$|\/x-javascript$)/gi)) ? true : false;
            // console.log("Obj Inner", designable, entity);
            let altCls = '';
            if(inst.changes.length){
                altCls = ' warn';
            }

            let type = objectType;
            let form = am7model.forms[type.substring(type.lastIndexOf(".") + 1)];
            if(!form){
                console.warn("Invalid form for " + type);
                return "";
            }

            let footer = getFooter();
            return m("div",{class : "list-results-container"},[
                m("div",{class : "list-results"},[
                m("div",{class: "result-nav-outer"},[
                    m("div",{class: "result-nav-inner"},[
                        m("div",{class: "result-nav tab-container"},[
                            page.iconButton("button mr-4", (fullMode ? "close_fullscreen" : "open_in_new"), "", toggleFullMode),
                            page.iconButton("button" + altCls,"save", "", doUpdate),
                            (designable ? page.iconButton("button" + (designMode ? " active" : ""),"design_services", "", toggleDesignMode) : ""),
                            page.iconButton("button","cancel", "", doCancel),
                            (bNew ? "" : page.iconButton("button","content_copy", "", doCopy)),
                            (bNew ? "" : page.iconButton("button","delete_outline", "", doDelete)),
                            getFormCommands(type, form, null, false, false, true)
                        ]),
                        m("div",{class: (designMode ? " hidden" : "result-nav tab-container")},
                            getFormTabs()
                        )
                    ])
                ]),
                getForm(),
                (designable ? m(page.components.designer, {entity:entity, visible: designMode, caller: objectPage}) : ""),
                footer
            ])
        ]);
        }
        function getObjectView(){

            return m("div",{class : "content-outer"},[
                (!entity || fullMode ? "" : m(page.components.navigation)),
                m("div",{class : "content-main"},[
                    getObjectViewInner()
            ])
            ]);
            
        }
        function getPrimitive(type){
            //let objectId = m.route.param("objectId");
            let model = page.context();
            let bNew = objectNew;//m.route.get().match(/^\/new/gi);
            let modType = am7model.getModel(type);
            let primitive = am7model.newPrimitive(type);
            let cobj;

            //console.log(bNew, type, modType, objectId, am7model.isGroup(modType), cobj?.path);
            if(bNew && (cobj = model.contextObjects[objectId])){
                if(am7model.isGroup(modType)){
                    
                    if(type === 'auth.group'){
                        primitive.parentId = cobj.id;
                    }
                    else{
                        primitive.groupPath = cobj.path;
                        primitive.groupId = cobj.id;
                    }
                    /// Note: org path is ignored on create, instead using the authenticated principle's organization, so this is only set for display purposes
                    if(page.authenticated()) primitive.organizationPath = cobj.organizationPath;
                }
                else if(am7model.isParent(modType) && type.match(/^(auth\.role|auth\.permission)$/gi)){
                    primitive.parentId = cobj.id;
                    primitive.path = cobj.path;
                }
                else if(type.match(/^(request)$/gi)){
                    //console.log(cobj);
                    //primitive.parentId = cobj.id;
                    //primitive.parentPath = cobj.parentPath + "/" + cobj.name;
                }
                else{
                    console.warn("Handle not a group for " + type);
                }
            }
            if(model.pendingEntity){
                changed = true;
                Object.keys(model.pendingEntity).forEach((i) => {
                    if(!i.match(/^(parentId|parentPath|groupPath)$/)){
                        primitive[i] = model.pendingEntity[i];
                    }
                });
                model.pendingEntity = undefined;
            }

            return primitive;
        }

        function setEntity(vnode){
            let type = objectType;
            valuesState = {};
            foreignData = {};
            pickerMode = {};
            entity = null;
            tabIndex = 0;

            //let objectId = m.route.param("objectId");
            let model = page.context();
            let bNew = objectNew;//(m.route.get().match(/^\/new/gi) != null);
            let bPNew = parentNew; //(m.route.get().match(/^\/pnew/gi) != null);
            let modType = am7model.getModel(type);

            if(vnode && vnode.attrs.freeForm){
                entity = vnode.attrs.freeFormEntity || {};
                if(entity.model && am7model.hasIdentity(entity)){
                    //console.log(entity);
                    let q = am7view.viewQuery(am7model.newInstance(type));
                    if(entity.id){
                        q.field("id", entity.id);    
                    }
                    else if(entity.objectId){
                        q.field("objectId", entity.objectId);
                    }
                    am7client.search(q, function(qr){
                        if(qr && qr.count){
                            let v = qr.results[0];
                            entity = v;
                            setApp();
                        }
                        else{
                            console.warn("Failed to search for " + q.key());
                        }
                    });
                }
                else{
                    // console.log("Set app", vnode.attrs);
                    setApp();
                }

            }
            else if(objectId){
                if(!model.contextObjects[objectId]){
                    let useType = type;
                    if(bNew && am7model.isGroup(modType)) useType = 'auth.group';
                    let q = am7view.viewQuery(am7model.newInstance(useType));
                    q.field("objectId", objectId);
                    am7client.search(q, function(qr){
                        if(qr && qr.count){
                            let v = qr.results[0];
                            model.contextObjects[objectId] = v;
                            resetEntity((bPNew || bNew ? getPrimitive(type) : v));
                            if(bPNew && am7model.isParent(modType)) entity.parentId = model.contextObjects[objectId].id;
                            setApp();
                        }
                        else{
                            console.warn("Failed to search for " + q.key());
                        }
                    });
                }
                else{
                    console.log("Got it: " + objectId);
                    entity = ((bPNew || bNew) ? getPrimitive(type) : model.contextObjects[objectId]);
                    if(bPNew && am7model.isParent(modType)) entity.parentId = model.contextObjects[objectId].id;
                    setApp();
                }
            }
            else{
                console.warn("Should only hit this for non group / parent objects!!!");
                entity = getPrimitive(type);
                setApp();
            }
        }
        function resetEntity(e){
            if(e && e.model){
                entity = e;
            }
            pinst = {};
            oinst = {};
            setInst();
        }

        function setInst(){
            inst = undefined;

            if(entity){
                let fname = entity.model.substring(entity.model.lastIndexOf(".") + 1);
                inst = am7model.prepareInstance(entity, am7model.forms[fname]);
                inst.observe(objectPage);
            }

        }

        

        function setApp(){
            let ldraw = !inst;
            if(entity.model){
                /*
                let fname = entity.model.substring(entity.model.lastIndexOf(".") + 1);
                inst = am7model.prepareInstance(entity, am7model.forms[fname]);
                */
                setInst();
                if(!caller){
                    window.dbgObj = objectPage;
                    window.dbgInst = inst;
                    window.dbgPinst = pinst;
                }
            }
            if(caller){
                caller.callback('set', objectPage, inst, undefined, parentProperty);
                // m.redraw();
            }
            else{
                m.redraw();
            }
        }

        function preparePicker(type, handler, altPath){
            requesting = true;
            return new Promise((res, rej)=>{

                let model = am7model.getModel(type);
                if(!model){
                    console.error("Invalid model for '" + type + "'");
                    rej();
                }
                pickerMode.tabIndex = tabIndex;
                pickerMode.type = type;
                pickerMode.handler = handler;
                if(am7model.isGroup(model) || am7model.isParent(model)) pickerMode.pickPath = altPath || am7view.path(type);
                if(am7model.isGroup(model)){
                    console.warn("PICK PATH", pickerMode.pickPath);
                    am7client.make("auth.group","data", pickerMode.pickPath, function(v){
                        pickerMode.containerId = v.objectId;
                        requesting = false;
                        enablePicker();
                        res();
                    });
                }
                else if(type.match(/^(auth\.role|auth\.permission)$/)){
                    am7client.user(type, "user", function(v){
                        uwm.getDefaultParentForType(type, v).then((parObj) =>{
                            pickerMode.containerId = parObj.objectId;
                            requesting = false;
                            enablePicker();
                            res();
                        });
                    });

                }
                else if(type.match(/^user$/)){
                    enablePicker();
                    res();
                }
                else{
                    console.warn("Handle pick " + type);
                }
            });
        }
        
        function applyModelNames(o){
            let af = am7model.getModelFields(o.model);
            af.forEach(f => {
                if(f.type == "model" && o[f.name] && !o[f.name].model){
                    console.log("Set: " + f.baseModel);
                    o[f.name].model = f.baseModel;
                    applyModelNames(o[f.name]);
                }
            });
        }

        /*
        function mergeDeep(t, ...s) {
            if (!s.length) return t;
            let src = s.shift();
          
            if (isObject(target) && isObject(source)) {
              for (const key in source) {
                if (isObject(source[key])) {
                  if (!target[key]) Object.assign(target, { [key]: {} });
                  mergeDeep(target[key], source[key]);
                } else {
                  Object.assign(target, { [key]: source[key] });
                }
              }
            }
          
            return mergeDeep(target, ...sources);
          }
          */

        objectPage.mergeEntity = function(e, s, baseModel){
            let ue = s || entity;
            if(!e){
                return;
            }
            if(!e.model) e.model = ue.model || baseModel;
            if(!e.model){
                console.warn("Cannot find model", e);
                return;
            }

            let x = Object.assign(ue, e);
            let af = am7model.getModelFields(e.model);
            af.forEach(f => {
                if((f.type == 'list' || f.type == 'model') && f.foreign && f.baseModel){
                    if(f.type == 'model' && !ue[f.name] && e[f.name]){
                        ue[f.name] = getPrimitive(f.baseModel);
                    }
                    else if(f.type == 'list'){
                        ue[f.name] = [];
                        for(let c in e[f.name]){
                            console.log(f.name);
                            ue[f.name].push(getPrimitive(f.baseModel));
                        }
                    }
                    if(e[f.name] ){
                        console.log(f.name, e[f.name]);
                        objectPage.mergeEntity(entity[f.name], e[f.name], f.baseModel);
                        //e[f.name] = undefined;
                    }
                }
            });

            
            applyModelNames(x);

            if(!s) resetEntity(x);
            // m.redraw();
        };
        objectPage.resetEntity = resetEntity;
        objectPage.getModel = function(name, fieldView, field, fieldClass){
            console.log(name, fieldView, field, fieldClass);
            return entity.model;
        }
        
        objectPage.objectControls = function(name, field){
            return new Promise((res, rej) => {
                if(!entity || !entity.objectId){
                    res([]);
                }
                else{
                    page.controls(entity.model, entity.objectId).then((v)=>{res(v);});
                }
            });
        };
        objectPage.objectRequests = function(name, field){
            return new Promise((res, rej) => {
                if(!entity || !entity.objectId){
                    res([]);
                }
                else{
                    page.requests(entity.model, entity.objectId).then((v)=>{res(v);});
                }
            });
        };

        function pickMember(members){
            let aP = [];
            if(am7model.hasIdentity(entity)){
                members.forEach((t)=>{
                    aP.push(new Promise((res, rej)=>{
                        am7client.member(entity.model, entity.objectId, t.model, t.objectId, true, function(v){
                            res(v);
                        })
                    }));
                });
            }
            Promise.all(aP).then(()=>{
                delete foreignData['members'];
                cancelPicker();
            });
        }
        objectPage.deleteChild = function(name, field, tableType, tableForm, props){
            let aP = [];
            let aC = [];
            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
                if(state.selected && vProp[name]){
                    let obj = vProp[name][state.index];
                    aC.push(obj);
                    /// only for the UI
                    vProp[name] = vProp[name].filter((o)=> o.objectId != obj.objectId);
                }
            });
            reparent(null, aC).then(()=>{
                am7client.clearCache(entity.model, false, function(s, v){
                    console.log("Complete clear cache: " + v);
                    m.redraw();
                });
            });
        };
        objectPage.addChild = function(name, field, tableType, tableForm, props){
            if(props.picker){
                return preparePicker(tableType, function(data){ pickChild(name, field, data);});
            }
            return Promise.resolve(null);
        };

        function reparent(parent, children){
            let aP = [];
            children.forEach((c)=>{
                aP.push(new Promise((res, rej)=>{
                    if(c.model.match(/^auth\.group$/gi) && !parent){
                        rej("Unable to reparent a group to nothing");
                    }
                    else{
                        c.parentId = (parent ? parent.id : 0);
                        am7client.patch(c.model, c, function(v){
                            if(v) res();
                            else rej();
                        })
                    }

                }));
            });
            return Promise.all(aP);
        }
        function pickChild(name, field, data){
            // let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
            // vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");
            reparent(entity, data).then(()=>{
                let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
                vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");
                cancelPicker(true);
                // don't update because the new lineage is already persisted
                //updateChange();
                am7client.clearCache(entity.model, false, function(s, v){
                    m.redraw();
                });
            });
        }

        function pickEntity(name, field, data){
            console.log("Pick entity", data, field);
            let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
            if(!vProp[name] && field.type == "list") vProp[name] = [];
            /// TODO - need to save/patch inner object if new/changed, doesn't currently bubble up to caller
            /// Plan - bubble up change to caller's model property, then the patch should include that object, and if new, would need to create as new sub because current AM7 system won't auto-create on patch, only on create.
            ///
            vProp[name] = page.removeDuplicates(vProp[name].concat(data), "objectId");
            
            //let bpatch = am7model.hasIdentity(entity);
            //am7client[bpatch ? "patch" : "create"](entity.model, (bpatch ? inst.patch() : entity), function(v){

            /// This will handle save/patch on a caller
            if(caller){
                caller.callback('update', objectPage, inst, name, parentProperty);
            }
            else{
                /// Handle quirk where lists of memberships are only handled on create, otherwise updates and deletes must be handled separately
                ///
                if(am7model.hasIdentity(entity)){
                    let aP = [];
                    data.forEach(v => {
                        aP.push(am7client.member(entity.model, entity.objectId, v.model, v.objectId, true));
                    });
                    Promise.all(aP);
                }
            }

            
            cancelPicker();

        }

        objectPage.addEntity = function(name, field, tableType, tableForm, props){
            console.log("Add entity " + tableType, field);
            if(props.picker){
                return preparePicker(tableType, function(data){ pickEntity(name, field, data);});
            }
            return Promise.resolve(null);
        };

        objectPage.openEntity = function(name, field, tableType, tableForm, props){
            let ent;

            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                let vProp;
                if(field.foreign && foreignData[name]) vProp = foreignData;
                else{
                    vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
                }
                console.log(ent, name, state, vProp);
                if(!ent && state.selected && vProp[name]){

                    ent = vProp[name][state.index];
                }
            });
            if(ent){
                let uri = "/view/" + ent.model + "/" + ent.objectId;
                m.route.set(uri, {key: ent.objectId});
            }
            else{
                console.log("Didn't find entity");
            }
        };

        objectPage.deleteEntity = function(name, field, tableType, tableForm, props){
            let aP = [];
            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                let vProp = (field.parentProperty ? entity[field.parentProperty] : entity);
                if(state.selected && vProp[name]){
                let per = vProp[name][state.index];
                if(am7model.hasIdentity(entity)){
                    aP.push(am7client.member(entity.model, entity.objectId, per.model, per.objectId, false));
                }
                vProp[name] = vProp[name].filter((o)=> o.objectId != per.objectId);
                }
            });

            Promise.all(aP).then(()=>{
                updateChange();
                m.redraw();
            });
        };


        objectPage.deleteMember = function(name, field, tableType, tableForm){
            let aP = [];
            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];

                /// state.foreign && 
                if(state.selected && foreignData[state.attribute]){
                    let obj = foreignData[state.attribute][state.index];
                    aP.push(new Promise((res, rej)=>{
                        am7client.member(entity.model, entity.objectId, obj.model, obj.objectId, false, function(v){
                            entity[name] = entity[name].filter(m => m.objectId != obj.objectId);
                            console.log("Deleted: " + v, entity[name]);
                            res(v);
                        });
                    }));
                }
            });
            Promise.all(aP).then(()=>{
                delete foreignData[name];
                m.redraw();
            });
        };

        objectPage.addMember = function(name, field, tableType, tableForm, props){
            let type = tableType;
            //console.log("Add entity " + tableType, field);
            if(type == "$flex"){
                if(field.foreignType){
                    type = inst.api[field.foreignType]();
                }
                else{
                    console.warn("Cannot reference a flex field without a foreign type");
                }
            }
            if(props.typeAttribute){
                type = am7view.typeToModel(entity[props.typeAttribute]);
            }
            if(props.picker){

                return preparePicker(type, pickMember);
            }
            else{
                console.log("Not a picker");
            }
            return Promise.resolve(null);
        }

        /// Need to paginate this
        objectPage.memberObjects = function(name, field){
            return new Promise((res, rej)=>{
                if(!entity || !entity.objectId){
                    //console.warn("Object not available or ready for members");
                    res([]);
                }
                else if(!field || !field.participantType){
                    res([]);
                }
                else {
                    let ftype = field.factoryType;
                    let paftype = field.participationFactoryType;
                    if(!ftype) ftype = paftype;
                    let pftype = field.participantFactoryType;
                    let ptype = field.participantType;
                    if(paftype.match(/^\./)) paftype = entity[paftype.slice(1)];
                    if(ftype.match(/^\./)) ftype = entity[ftype.slice(1)];
                    if(pftype.match(/^\./)) pftype = entity[pftype.slice(1)];
                    if(ptype.match(/^\./)) ptype = entity[ptype.slice(1)];
                    let search = new org.cote.objects.participationSearchRequest();
                    search.startRecord = 0;
                    search.recordCount = 100;
                    search.participantList = [entity.objectId];
                    search.participantFactoryType = pftype;
                    search.participationFactoryType = paftype;
                    search.participantType = ptype;
                    // console.log(search);
                    am7client.listParticipations(ftype, search, function(v){
                        res(v);
                    });
                }
            });
        };

        /// Need to paginate this
        objectPage.objectMembers = function(name, field){
            
            return new Promise((res, rej)=>{
                let ftype;
                if(field){
                    ftype = field.typeAttribute || field.foreignType;
                }
                if(!entity || !entity.objectId || !field || !ftype){
                    res([]);
                }
                else{
                    let type = am7view.typeToModel(entity[ftype]);
                    /*
                    if(type == "$flex"){
                        if(field.foreignType){
                            type = inst.api[field.foreignType]();
                        }
                        else{
                            console.warn("Cannot reference a flex field without a foreign type");
                        }
                    }
                    */
                    am7client.members(entity.model, entity.objectId, type, 0, 100, function(v){
                        res(v);
                    });
                }
            });
        };

        objectPage.parentPath = function(){
            let path;
            //let objectId = m.route.param("objectId");

            let model = page.context();
            let bNew = objectNew || parentNew; //m.route.get().match(/^\/(new|pnew)/gi);
            if(!entity || (!entity.objectId && !objectId)) return path;
            switch(entity.model){
                case 'auth.role':
                case 'auth.permission':
                case 'auth.group':
                    if(bNew){
                        if(model.contextObjects[objectId]){
                            path = model.contextObjects[objectId].path;
                        }
                    }
                    else{
                        if(entity.model === 'auth.group') path = entity.path.substring(0, entity.path.lastIndexOf("/"));
                        else path = entity.path;
                    }
                    break;
                case 'data.data':
                    break;
                default:
                    console.error('Handle type: ' + entity.model);
                    break;
            }
            return path;
        };

        objectPage.editEntry = function(){
            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                if(state.selected){
                    state.mode = 'edit';
                }
            });
            m.redraw();
        };

        objectPage.deleteEntry = function(name){
            let aP = [];
            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                if(state.selected){
                    if(name === 'attributes' || name === 'elementValues'){
                        /// Need to confirm the attribute type, this assumes it's an array 
                        //entity[state.attribute][state.index] = null;
                        entity[state.attribute] = entity[state.attribute].filter((a,i) => i != state.index);
                        delete valuesState[k];
                        updateChange();
                    }
                    else if(name === 'controls'){
                        let model = page.context();
                        if(model.controls[entity.objectId]){
                            let ctl = model.controls[entity.objectId][state.index];
                            //model.controls[entity.objectId].slice(state.index);
                            delete model.controls[entity.objectId][state.index];
                            if(ctl.objectId){
                                aP.push(new Promise((res, rej)=>{
                                    am7client.delete("CONTROL", ctl.objectId, function(s, v){
                                        res();
                                    });
                                }));
                            }
                            console.log("DELETE", ctl);
                        }
                    }
                }
            });
            Promise.all(aP).then(()=>{
                m.redraw();
            });
        };

        objectPage.newEntry = function(name){
            let mf = am7model.getModelField(entity.model, name);
            if(!mf){
                console.error("Object does not define '" + name + "'");
                return;
            }
            if(mf.type === 'list' && mf.baseModel){
                if(!entity[name]) entity[name] = [];
                if(!inst.changes.includes(name)) inst.changes.push(name);
                let ne;
                if(name === 'attributes'){
                    ne = am7client.newAttribute("","");
                }
                
                else{
                    ne = am7model.newPrimitive(mf.baseModel);
                }
                entity[name].push(ne);
                let idx = entity[name].length - 1;
                valuesState[name + "-" + idx] = {attribute: name, index : idx, selected: true, mode: 'edit'};
                m.redraw();
            }
            else{
                console.error("Unhandled new entry type: " + mf.type);
            }

        };

        objectPage.checkEntry = function(name, field, tableType, tableForm, props){
            console.log("check entry: " + name);
            let fieldView = am7model.forms[name];
            //console.log(name, fieldView);
            
            let tableFieldView = fieldView.form;
            /// console.log(tableFieldView);
            let aP = [];
            Object.keys(valuesState).forEach((k)=>{
                let state = valuesState[k];
                if(state.mode === 'edit'){

                    //let attr = state.attribute;
                    let idx = state.index;
                    let entry;
                    if(field.foreign) entry = foreignData[state.fieldName || name][idx]
                    else entry = entity[state.fieldName || name][idx];
                    console.log(entry);
                    Object.keys(tableForm.fields).forEach((k)=>{
                        let tableField = am7model.getModelField(tableType, k);

                        let e = document.querySelector("[name='" + k + "-" + idx + "']");
                        if(tableField.virtual){
                            console.log("Skipping virtual field");
                        }
                        else{
                            let useType = tableField.type;
                            if(useType == 'flex'){
                                if(entry.valueType){
                                    useType = entry.valueType.toLowerCase();
                                }
                            }
                            switch(useType){
                                case 'boolean':
                                    entry[k] = (e.value.match(/^true$/gi) != null);
                                    break;
                                case 'enum':
                                case 'string':
                                    entry[k] = e.value;
                                    break;
                                case 'list':
                                case 'array':
                                    entry[k] = [e.value];
                                    break;
                                default:
                                    console.warn("Unhandled entry type: " + useType);
                                    break;
                            }
                        }
                    });
                    if(!fieldView.standardUpdate && am7model.hasIdentity(entity)){
                        if(entry.objectId) aP.push(am7client.patch(entry));
                        else{
                            entry.model = tableType;
                            if(am7model.inherits(tableType, "common.reference")){
                                entry.referenceId = entity.id;
                                entry.referenceModel = entity.model;
                            }
                            window.dbgEntry = entry;
                            aP.push(am7client.create(tableType, entry));
                        }
                    }
                    /*
                    if(name.match(/^(controls|requestsList)$/gi)){
                        aP.push(page.updateObject(entry));
                    }
                    */

                }
            });

            
            Promise.all(aP).then((aB)=>{
                if(!aB || !aB.filter((b)=>{if(!b) return true;}).length){
                    console.log("Saved foreign data: " + name, foreignData[name]);
                    valuesState = {};
                    m.redraw();
                }
                else{
                    console.warn("Error updating record: " + aB);
                }
            });
        };

        objectPage.cancelEntry = function(name){

            valuesState = {};
            m.redraw();
        };

        objectPage.getEntity = function(){
            return entity;
        };
        objectPage.getInstance = function(){
            return inst;
        };
        objectPage.preparePicker = preparePicker;
        objectPage.pickerMode = function(){ return pickerMode;};
        objectPage.enablePicker = enablePicker;
        objectPage.cancelPicker = cancelPicker;
        objectPage.setChildPickerMode = function(p){
            console.log(p);
            childPickerMode = p;
        };
        objectPage.pickerEnabled = function(){
            return pickerMode.enabled;
        };
        objectPage.endPicker = function(){
            pickerMode.enabled = false;
            m.redraw();
        };
        objectPage.clear = function(){

            /// console.warn("Clear!");

            if(app) app.destroy();
            entity = null;
            inst = null;
            app = null;

            /// don't clear pendingEntity
            /// pendingEntity = undefined;
            pendingRequest = undefined;
            pendingResponse = undefined;
            definition = undefined;
            requesting = false;

            tabIndex = 0;
            pickerMode = {clear:true};
            fullMode = false;
            designMode = false;
        };

        objectPage.makeFact = async function(name, field, tableType, tableForm, props){
            let path = am7view.path("fact");
            let group = await page.findObject("auth.group", "data", path);
            let gpath = "/new/fact/" + group.objectId;

            if(!name.match(/^(auth\.group|auth\.role|auth\.permission|identity\.person|identity\.account|system\.user|data\.data|policy\.function|policy\.operation)$/gi)){
                console.error("Unsupported fact import type " + (entity ? entity.urn : "null"));
                return;
            }
            let fact = am7model.newPrimitive("fact");
            fact.factType = "FACTORY";
            fact.sourceType = "unknown";
            if(name.match(/^role$/gi)){
                fact.factType = "role";
                fact.sourceType = entity.type;
            }
            else if(name.match(/^permission$/gi)){
                fact.factType = "static";
                fact.sourceType = entity.type;
            }
            else if(name.match(/^auth\.group$/gi)){
                fact.factType = "permission";
                fact.sourceType = entity.type;
            }
            fact.sourceUrn = entity.urn;
            fact.factoryType = entity.model;
            fact.name = entity.name + " Fact";
            fact.description = "Fact representing a relative link to " + entity.name + " as " + entity.urn + " in organization " + entity.organizationPath;

            /// Note: Adding a key because cycling through the same view with different objects needs a full DOM rebuild to correctly disconnect the sync code
            ///
            page.context().pendingEntity = fact;
            console.log("Pend Out", fact);
            m.route.set(gpath, {key: Date.now()});
        };

        objectPage.execute = function(name, field, type, form, properties){
            pendingResponse = undefined;
            am7client.executeScript(entity.urn, function(v){
                pendingResponse = v;
                m.redraw();
            });
        };

        objectPage.evaluate = function(name, field, type, form, properties){
            if(!definition){
                console.error("Expected definition");
                return;
            }
            let prq = azn.createPolicyRequest(definition);
            prq.facts.forEach((p, i)=>{
                let e = document.querySelector("input[name=parameter-" + i + "]");
                p.factData = e.value;
                p.sourceUrn = e.value;
            });
            console.log(prq);
            am7client.evaluate(prq, function(v){
                if(v === null) pendingResponse = "Error";
                else pendingResponse = v.response;
                console.log(v);
                m.redraw();
            });
        };

        objectPage.define = function(){
            if(definition){
                return definition.parameters.map((p, i)=>{
                    return m("tr", [
                        m("td", p.name),
                        m("td", p.factoryType),
                        m("td", m("input", {name: "parameter-" + i, type: "text", class: "text-field-full", placeholder: ""}))
                    ]);
                });
            }
            else if(definition === null){
                return [m("tr",m("td","Error"))];
            }
            else{
                am7client.define(entity.objectId, function(v){

                    definition = v;
                    // console.log(v);
                    m.redraw();
                });
                return [];
            }
        };

        objectPage.objectExecute = function(name, fieldView, field, fieldClass, hidden){
            let cmds = [];
            let rows = [];
            if(!field){
                //console.warn("Skip unavailable field");
                return rows;
            }
            let form = fieldView.form;
            if(!form){
                console.warn("Expected form");
                return rows;
            }
            if(fieldView.properties && fieldView.properties.prepare){
                rows.push(objectPage[fieldView.properties.prepare]());
            }

            cmds = getFormCommandView(name, fieldView.form, field, false, false);

            if(pendingResponse){
                rows.push(m("tr", m("td", 
                    (typeof pendingResponse == "object" ? JSON.stringify(pendingResponse) : pendingResponse)
                )));
            }
            return [cmds, m("table",{class: fieldClass}, rows)];
        };
        objectPage.tabIndex = function(){
            return tabIndex;
        };
        objectPage.caller = function(){
            return caller;
        };
        objectPage.report = function(stype, obj, cinst){
            if(caller){
                caller.callback(stype, obj, cinst, undefined, parentProperty);
            }
        };

        objectPage.callback = function(stype, obj, cinst, cname, pname){
            if(pname && !inst.changes.includes(pname)){
                inst.changes.push(pname);
            }
            if(stype == 'set'){
                if(cinst){
                    pinst[pname] = cinst;
                }
            }
            else if(stype == 'update'){
                /// If the child object doesn't have an identity, and the current object doesn't have an identity, then let the initial create handle it

                if(!am7model.hasIdentity(cinst.entity)){
                    if(am7model.hasIdentity(inst.entity)){
                        /// create cinst
                        /// check for reference
                        /// console.warn("Create " + pname + ". not currently implemented");
                    }
                    else{
                        /// nothing to do, let it be picked up by default nested create
                        ///
                        console.log("Handle with entity create " + pname);
                    }
                }
                else{
                    /// Handled as patch operation in doUpdate
                }
                
            }
        };
        objectPage.picker = doFieldPicker;
        objectPage.view = {
            debug: function(){
                return foreignData;
            },
            oninit : function(x){
                // window.dbgObj = objectPage;
                fullMode = x.attrs.fullMode || fullMode;
                embeddedMode = x.attrs.embeddedMode;
                embeddedController = x.attrs.embeddedController;
                objectType = x.attrs.freeFormType || x.attrs.type || m.route.param("type");
                objectId = x.attrs.objectId || m.route.param("objectId");
                objectNew = x.attrs.new || m.route.get().match(/^\/new/gi);
                parentNew = x.attrs.parentNew || m.route.get().match(/^\/pnew/gi);
                parentProperty = x.attrs.parentProperty;
                caller = x.attrs.caller;
                if(x.attrs.pickerCfg){
                    pickerMode = x.attrs.pickerCfg;
                }
                // console.log("init", objectType, inst);
                setEntity(x);

            },
            sync: function(){
                
            },
            oncreate : function (x) {

                // setEntity(x);
                // console.log("create", objectType, inst);
                page.navigable.setupPendingContextMenus();
            },
            onupdate : function(x){
                page.navigable.setupPendingContextMenus();
            },
            onunload : function(x){
                // console.log("Unloading ...");
            },
            onremove : function(x){
                objectPage.clear();
                page.navigable.cleanupContextMenus();
            },

            view: function (vnode) {
                // console.warn("view", objectType, inst);
                callerIndex = vnode.attrs.callerIndex;
                callerActive = vnode.attrs.callerActive;

                if(!page.authenticated() || !inst) return m("");
                if(vnode.attrs.freeForm){
                    //console.log("Free Form", vnode.attrs, inst);
                    return getForm();
                }                    
                else if(embeddedMode) return [getObjectViewInner(), page.loadDialog()];
                else return [getObjectView(), page.loadDialog()];
            }
        };
        return objectPage;
    }
    page.views.object = newObjectPage;//objectPage.view;
}());
