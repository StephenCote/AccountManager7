(function(){

    function getDefaultValuesForField(field){
        let v = [];
        switch(field.type){
            case "list":
                v = field.limit || [];
                break;
            case "enum":
                if(field.filter) v = field.enum.filter( (v) => v.match(field.filter));
                else if(field.baseClass){
                    let jsName = field.baseClass.substring(field.baseClass.lastIndexOf(".") + 1);
                    v = am7model.enums[jsName.substring(0,1).toLowerCase() + jsName.substring(1)];
                }
                else v = field.enum;
                
                break;
        }
        return v;
    }

    function getActionView(fld, inst){
        let a = inst.form.commands[fld];
        return m("div",[
            m("button",{
            class : "dialog-button",
            onclick : function(){
                inst.action(a.action);
            }
            },[
                m("span",{
                    class : "material-symbols-outlined material-symbols-outlined-white md-18 mr-4"
                },[a.icon]),
            m("span",{
                class: ""
            },[a.label])                         ,
            ])
        ])
    }

    function getFieldView(fld, inst){
        let f = inst.field(fld);
        return m("div",[
            m("label",{
                class : "block",
                for : fld
            },[
                inst.label(fld)
            ]),
           (f ? getField(fld, inst)  : m("span", {class: "bold"}, "Invalid field: " + fld))
        ]);
    }
    function getField(fld, inst){
        let props = {
            oninput: inst.handleChange(fld),
            placeholder : inst.placeholder(fld),
            name : fld
        };
        let fprops = inst.viewProperties(fld);
        if(fprops){
            props = Object.assign(fprops, props);
        }
        let defVal;
        if(inst.api[fld]){
            defVal = inst.api[fld]();
        }

        let type = inst.formField(fld, "type") || "text";

        let cls = "text-field-full";
        let useType = type;
        if(type == "list"){
            return m("select", {onchange: inst.handleChange(fld), class: "select-field-full"},
                (inst.formField(fld, "values") || []).map((o, i)=>m("option",{value:o,selected:(o==inst.api[fld]())}, o)),
            );
        }
        if(type == "boolean"){
            useType = "checkbox";
            cls = "check-field";
        }
        let iprops = {
            class : cls,
            type: useType,
            value: defVal
        };
        if(useType == "checkbox"){
            props.oninput = undefined;
            props.onchange = inst.handleChange(fld);
            iprops.checked = defVal;
        }
        return m("input", Object.assign(iprops, props));
    }
    
    function form(inst){

        let vf = inst.form;

        let el = Array.from(Object.entries(vf.fields).map( ([k, f]) => {
            let x = inst.design(k);
            if(!x){
                x = getFieldView(k, inst);
            }
            return [k,x];
        }), ([key, value]) => value);
        let el2 = Array.from(Object.entries(vf.commands || {}).map(([k, c]) => {
            return [k, getActionView(k, inst)];
        }), ([key, value]) => value);
        el.push(...el2);

        let v = [m("h3",{
            class : "box-title"
        },[
            icon(inst),
            label(inst)
        ]),
        m("div",{
                class : "mt-4"
            },
            el
        )
        ];
        return v;
    }

    function icon(v){
        if(v.icon()){
            return m("span",{
                class : "material-symbols-outlined mr-4"
            },[v.icon()]);
        }
        return "";
    }
    function label(v){
        if(v.label()){
            return m("span",{},[v.label()]);
        }
        return "";
    }

    let basePath = "~";

    function getBasePathByType(s){
        let m = am7model.getModel(s);
        if(!m || !m.group) return basePath;
        return basePath + "/" + m.group;
	}
    let worldView = {
        "Population": "olio.charPerson"
    }
	function getTypeByPath(sPath){
        let subPath = sPath.substring(sPath.lastIndexOf("/") + 1);
        let outT;
        let aP = am7model.models.filter(m => m.group && m.group == subPath);
        if(aP.length) outT = aP[0].name;
        if(!outT && worldView[subPath]){
            outT = worldView[subPath];
        }
        return outT;
    }

    function getPathForType(type){
        let path = getBasePathByType(type);
        if(path != null) path = path.replace(/^~/, page.user.homeDirectory.path);
        return path;
    }

    function viewQuery(inst){
        if(typeof inst == "string") inst = am7model.newInstance(inst);
        let q = am7client.newQuery(inst.model.name);
        q.entity.request = getViewFields(inst);

        let oidf = inst.fields.filter(f => f.name == "objectId");
        let idf = inst.fields.filter(f => f.name == "id");
        let id;
        if(oidf.length){
            id = oidf[0];
        }
        else if(idf.length){
            id = idf[0];
        }
        let idv;
        if(id && inst.api[id.name] && (idv = inst.api[id.name]()) && idv != null){
            q.field(id.name, inst.api[id.name]());
        }
        /*
        if(page.user){
            q.field("organizationId", page.user.organizationId);
        }
        */
        return q;
    }

    function getViewFields(inst){
        if(typeof inst == "string") inst = am7model.newInstance(inst);
        return getFormFieldNames(inst.model.name, inst.form);

    }
    function getFormFieldNames(model, form){
        let a = [];
        if(!form || !form.fields) return a;
        a = Object.keys(form.fields).filter(f => {
            let mf = am7model.getModelField(model, f);
            return (mf && !mf.ephemeral);
        }).map(k => k);
        if(form.query) a.push(...form.query);

        if(am7model.inherits(model, "system.organizationExt")){
			a.push("organizationPath");
            a.push("organizationId");
		}
        if(am7model.inherits(model, "system.primaryKey")){
			a.push("id");
		}

        if(am7model.isParent(model)){
			if(am7model.hasField(model, "path")) a.push("path");
            a.push("parentId");
		}
		if(am7model.isGroup(model) && model != 'auth.group'){
			a.push("groupPath");
            a.push("groupId");
		}
        if(form.forms){
            form.forms.forEach(f => {
                a.push(...getFormFieldNames(model, am7model.forms[f]));
            });
        }
        return a.filter( (v, i, z) => z.indexOf(v) == i);
    }

    function getFormField(form, name){
        let field;  
        if(form){
            field = form.fields[name];
            if(!field){
                let ff = (form.forms || []);
                for(let fo in ff){
                    let f = ff[fo];
                    field = am7model.forms[f].fields[name];
                    if(field){
                        break;
                    }
                };
            }
        }
        return field;
    }

    function typeToModel(type){
        let ot = type;
        if(!type){
            return type;
        }
        switch(type.toLowerCase()){
            case "account":
                ot = "identity.account";
                break;
            case "user":
                ot = "system.user";
                break;
            case "person":
                ot = "identity.person";
                break;
            case "unknown":
                ot = "common.nameId";
                break;
        }
        return ot;
    }

    let am7view = {
        form,
        fieldView:getFieldView,
        formField:getFormField,
        field:getField,
        label,
        icon,
        viewQuery,
        fieldNames: getFormFieldNames,
        path: getBasePathByType,
        pathForType: getPathForType,
        typeByPath: getTypeByPath,
        typeToModel,
        defaultValuesForField: getDefaultValuesForField,
        basePath: (s) => {
            if(s){
                basePath = s;
            }
            return basePath;
        }
    };

    if (typeof module != "undefined") {
        module.am7view = am7view;
    } else {
        window.am7view = am7view;
    }
}());