(function () {
    /// Extensions to am7view to support custom field renderers
    /// This allows formats like "image", "video", "pdf" etc. to use custom rendering logic

    /// Storage for custom renderers
    if (!am7view.customRenderers) {
        am7view.customRenderers = {};
    }

    /// Register a custom renderer for a format type
    am7view.registerCustomRenderer = function(formatName, renderFunction) {
        am7view.customRenderers[formatName] = renderFunction;
    };

    /// Enhanced getField that checks for custom renderers
    let originalGetField = am7view.field;

    am7view.field = function(fld, inst) {
        // Get the format for this field
        let format = inst.formField(fld, "format");

        // Check if there's a custom renderer for this format
        if (format && am7view.customRenderers[format]) {
            return am7view.customRenderers[format](inst, fld);
        }

        // Fall back to original implementation
        return originalGetField(fld, inst);
    };

    /// Enhanced fieldView that supports custom renderers
    let originalFieldView = am7view.fieldView;

    am7view.fieldView = function(fld, inst) {
        let f = inst.field(fld);
        let ff = inst.formField(fld);

        if (ff && ff.hide) {
            return m("span", "");
        }

        // Check for custom renderer
        let format = inst.formField(fld, "format");
        if (format && am7view.customRenderers[format]) {
            let annot = "";
            if (inst.validationErrors[fld]) {
                annot = am7view.errorLabel(inst.validationErrors[fld]);
            }

            // For custom renderers, wrap in a div with label if showLabel is not false
            let showLabel = ff ? (ff.showLabel !== false) : true;

            if (showLabel) {
                return m("div", [
                    m("label", {
                        class: "block",
                        for: fld
                    }, [
                        inst.label(fld)
                    ]),
                    am7view.customRenderers[format](inst, fld),
                    annot
                ]);
            } else {
                return m("div", [
                    am7view.customRenderers[format](inst, fld),
                    annot
                ]);
            }
        }

        // Fall back to original implementation
        return originalFieldView(fld, inst);
    };

    /// Helper to determine renderer based on object properties
    am7view.selectObjectRenderer = function(object) {
        // Check for portrait
        if (object.profile && object.profile.portrait && object.profile.portrait.contentType) {
            return "portrait";
        }

        // Check model
        let model = object[am7model.jsonModelKey];
        if (model) {
            switch(model) {
                case 'note':
                    return "markdown";
                case 'form':
                    return "form";
                case 'message':
                    return "messageContent";
                case 'tool.memory':
                    return "memory";
            }
        }

        // Check content type
        let mt = object.contentType || "";
        if (mt.match(/^image/) || mt.match(/webp$/)) {
            return "image";
        }
        if (mt.match(/^video/)) {
            return "video";
        }
        if (mt.match(/^audio/)) {
            return "audio";
        }
        if (mt.match(/^text/)) {
            return "text";
        }
        if (mt.match(/pdf$/)) {
            return "pdf";
        }

        return null;
    };

    /// Create an instance for object viewing
    am7view.prepareObjectView = function(object, attrs) {
        let model = attrs[am7model.jsonModelKey] || object[am7model.jsonModelKey];

        if (!model) {
            console.warn("No model specified for object view");
            return null;
        }

        // Determine the appropriate renderer
        let rendererType = am7view.selectObjectRenderer(object);

        if (!rendererType) {
            console.warn("No renderer found for object", object);
            return null;
        }

        // Create a synthetic entity for the view
        let entity = {
            [am7model.jsonModelKey]: "view.objectDisplay",
            objectId: object.objectId,
            active: attrs.active !== false,
            maxMode: attrs.maxMode || false,
            viewType: rendererType,
            _sourceObject: object
        };

        // Merge object properties
        Object.assign(entity, object);

        // Get appropriate form
        let formName = rendererType + "View";
        let form = am7model.forms[formName];

        if (!form) {
            console.warn("No form found for renderer type:", rendererType);
            return null;
        }

        // Prepare instance
        let inst = am7model.prepareInstance(entity, form);

        return inst;
    };

}());
