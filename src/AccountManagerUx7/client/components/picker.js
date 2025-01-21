(function () {

    let pickerMode = {
        enabled: false,
        containerId: undefined,
        pickPath: undefined
    };
    let childPickerMode;
    let requesting = false;
    const picker = {
        cancelPicker: function(bNoRefresh){
            if(childPickerMode){
                childPickerMode.cancelPicker(bNoRefresh);
                childPickerMode = undefined;
            }
            pickerMode.enabled = false;
            if(!bNoRefresh){
                m.redraw();
            }
        },
        enablePicker: function(bNoRefresh, objectPage, caller){
            pickerMode.enabled = true;
            if(caller){
                caller.setChildPickerMode(objectPage);
            }
            if(!bNoRefresh){
                m.redraw();
            }
        }
    };
  

    page.components.picker = picker;
}());
