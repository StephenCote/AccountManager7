(function () {

    let pickerMode = {
        enabled: false,
        containerId: undefined,
        pickPath: undefined
    };
    let childPickerMode;
    let requesting = false;

    function fieldPicker(inst, field, useName, altEntity, altPath, pickerHandler){
        let entity = inst?.entity;
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
            preparePicker(inst, type, function(data){
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

    function preparePicker(inst, type, handler, altPath){
        requesting = true;
        return new Promise((res, rej)=>{

            let model = am7model.getModel(type);
            if(!model){
                console.error("Invalid model for '" + type + "'");
                rej();
            }
            // pickerMode.tabIndex = tabIndex;
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
    function enablePicker(bNoRefresh, objectPage, caller){
        pickerMode.enabled = true;
        if(caller){
            childPickerMode = objectPage;
        }
        if(!bNoRefresh){
            m.redraw();
        }
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

    const picker = {
        cancelPicker,
        enablePicker,
        validateFormat: function(inst, field, format, useName){
            if(format === "picker" && field.pickerProperty && field.pickerProperty.requiredAttributes){
                let req = field.pickerProperty;
                if(!am7view.showField(inst, req, useName)){
                    format = "text";
                }
            }
            return format;
        },
        inPickMode : function(){
            return (pickerMode.enabled || childPickerMode);
        },
        fieldPicker

    };
  

    page.components.picker = picker;
}());
