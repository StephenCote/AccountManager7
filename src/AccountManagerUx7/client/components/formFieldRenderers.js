(function() {
    /// Form Field Renderers for Object Edit Views
    /// These handle input fields in the object editor (view/object.js)
    ///
    /// Each renderer receives a context object with:
    ///   - inst: the prepared instance with API methods
    ///   - entity: the current entity being edited
    ///   - field: the model field definition
    ///   - fieldView: the form field configuration
    ///   - name: the field name
    ///   - useName: the actual input name (may differ for arrays)
    ///   - defVal: the default/current value
    ///   - fieldClass: CSS classes to apply
    ///   - disabled: whether the field is disabled
    ///   - fHandler: the change handler function
    ///   - show: whether the field should be visible

    let renderers = {};
    let audioRecord = false;

    /// ========================================
    /// SIMPLE RENDERERS - No dependencies
    /// ========================================

    /// Blank placeholder field
    renderers.blank = function(ctx) {
        return [m("span", {class: "blank"})];
    };

    /// Read-only print display
    renderers.print = function(ctx) {
        let val = ctx.defVal;
        if (!val && ctx.entity) val = ctx.entity[ctx.useName];
        return [m("span", {class: ctx.fieldClass + " inline-block text-field-full text-content"}, val)];
    };

    /// Read-only print with alert styling
    renderers["print-alert"] = function(ctx) {
        let val = ctx.defVal;
        if (!val && ctx.entity) val = ctx.entity[ctx.useName];
        return [m("span", {class: "text-content-alert"}, val)];
    };

    /// Checkbox field
    renderers.checkbox = function(ctx) {
        let inputAttrs = {
            type: "checkbox",
            class: ctx.fieldClass + " check-field",
            name: ctx.useName,
            checked: ctx.defVal,
            oninput: ctx.fHandler
        };
        if (ctx.disabled) {
            inputAttrs.disabled = true;
        }
        return [m("input", inputAttrs)];
    };

    /// Color picker field
    renderers.color = function(ctx) {
        let inputAttrs = {
            type: "color",
            class: ctx.fieldClass + " color-field",
            name: ctx.useName,
            value: ctx.defVal,
            oninput: ctx.fHandler
        };
        if (ctx.disabled) {
            inputAttrs.disabled = true;
        }
        return [m("input", inputAttrs)];
    };

    /// ========================================
    /// SELECT AND DROPDOWN RENDERERS
    /// ========================================

    /// Select dropdown field
    /// Uses am7view.defaultValuesForField to get enum/list values
    renderers.select = function(ctx) {
        let vals = am7view.defaultValuesForField(ctx.field);
        let selectAttrs = {
            class: ctx.fieldClass + " select-field-full",
            name: ctx.useName,
            oninput: ctx.fHandler
        };
        let fprops = ctx.inst.viewProperties(ctx.name);
        if (fprops) {
            Object.assign(selectAttrs, fprops);
        }

        let options = vals.map(function(v) {
            let vs = String(v);
            let selected = ctx.defVal != null && String(ctx.defVal).toLowerCase() === vs.toLowerCase();
            let optAttrs = {value: vs.toLowerCase()};
            if (selected) optAttrs.selected = "true";
            return m("option", optAttrs, v);
        });

        return [m("select", selectAttrs, options)];
    };

    /// ========================================
    /// RANGE SLIDER RENDERER
    /// ========================================

    /// Range slider field with min/max display
    renderers.range = function(ctx) {
        let min = 0;
        let max = 100;

        if (typeof ctx.field.minValue == "number" && typeof ctx.field.maxValue == "number") {
            if (ctx.field.type == "double" && ctx.field.maxValue >= 1) {
                max = ctx.field.maxValue * 100;
                if (min != 0) {
                    min = ctx.field.minValue * 100;
                }
            } else {
                min = ctx.field.minValue;
                max = ctx.field.maxValue;
            }
        }

        let inputAttrs = {
            type: "range",
            class: ctx.fieldClass + " range-field-full",
            name: ctx.useName,
            value: ctx.defVal,
            min: min,
            max: max,
            oninput: ctx.fHandler
        };
        if (ctx.disabled) {
            inputAttrs.disabled = true;
        }

        return [m("div", {class: "relative mb-5"}, [
            m("label", {class: "sr-only"}, "Range"),
            m("input", inputAttrs),
            m("span", {class: "text-sm text-gray-500 absolute start-0 -bottom-6"}, min),
            m("span", {class: "text-sm text-gray-500 absolute start-1/2 -translate-x-1/2 -bottom-6"}, ctx.defVal),
            m("span", {class: "text-sm text-gray-500 absolute end-0 -bottom-6"}, max)
        ])];
    };

    /// ========================================
    /// PROGRESS BAR RENDERER
    /// ========================================

    /// Progress bar field
    renderers.progress = function(ctx) {
        let progressAttrs = {
            class: ctx.fieldClass + " progress-field-full",
            name: ctx.useName,
            value: ctx.defVal,
            max: ctx.field.maxValue || 100,
            min: ctx.field.minValue || 0
        };
        if (ctx.disabled) {
            progressAttrs.disabled = true;
        }
        return [m("progress", progressAttrs)];
    };

    /// ========================================
    /// BUTTON RENDERER
    /// ========================================

    /// Button field with optional icon
    renderers.button = function(ctx) {
        let buttonHandler = function() {
            if (ctx.field.command) {
                ctx.field.command(ctx.objectPage, ctx.inst, ctx.name);
            }
        };

        let buttonContent = [];
        if (ctx.fieldView.icon) {
            buttonContent.push(m("span", {class: "material-symbols-outlined material-icons-24"}, ctx.fieldView.icon));
        }
        if (ctx.field.label) {
            buttonContent.push(ctx.field.label);
        }

        let buttonAttrs = {
            class: ctx.fieldClass + " button-field-full",
            onclick: buttonHandler
        };
        if (ctx.disabled) {
            buttonAttrs.disabled = true;
        }

        return [m("button", buttonAttrs, buttonContent)];
    };

    /// ========================================
    /// TEXT INPUT RENDERERS
    /// ========================================

    /// Helper to create audio recording button for text fields
    function createAudioRecordButton(ctx) {
        if (typeof page === "undefined" || !page.components || !page.components.audio) {
            return "";
        }
        return page.components.audio.recordField(function(text) {
            if (text !== null) {
                ctx.inst.api[ctx.name](text);
            } else {
                return ctx.inst.api[ctx.name]() || "";
            }
        });
    }

    /// Helper to apply drag-and-drop props to a field
    function applyDragAndDrop(ctx, props) {
        if (ctx.entity && !ctx.entity.objectId && ctx.fieldView.dragAndDrop) {
            props.class += " border-dotted";
            if (typeof page !== "undefined" && page.components && page.components.dnd) {
                let dnd = page.components.dnd.dropProps(ctx.inst);
                Object.assign(props, dnd);
            }
            props.placeholder = "{ Type Text or Drop File Here }";
        }
        return props;
    }

    /// Text input field with audio recording support
    renderers.text = function(ctx) {
        let inputAttrs = {
            type: "text",
            class: ctx.fieldClass + " text-field-full pr-8",
            name: ctx.useName,
            value: ctx.defVal,
            oninput: ctx.fHandler
        };
        if (ctx.disabled) {
            inputAttrs.disabled = true;
        }

        inputAttrs = applyDragAndDrop(ctx, inputAttrs);

        return [m("div", {class: "relative w-full"},
            m("input", inputAttrs),
            (audioRecord ? m("div", {class: "absolute inset-y-0 right-0 flex items-center"},
                createAudioRecordButton(ctx)
            ) : "")
        )];
    };

    /// Datetime-local field (shares most logic with text)
    renderers["datetime-local"] = function(ctx) {
        let inputAttrs = {
            type: "datetime-local",
            class: ctx.fieldClass + " text-field-full pr-8",
            name: ctx.useName,
            value: ctx.defVal,
            oninput: ctx.fHandler
        };
        if (ctx.disabled) {
            inputAttrs.disabled = true;
        }

        return [m("div", {class: "relative w-full"},
            m("input", inputAttrs),
            (audioRecord ? m("div", {class: "absolute inset-y-0 right-0 flex items-center"},
                createAudioRecordButton(ctx)
            ) : "")
        )];
    };

    /// Textarea field with audio recording support
    renderers.textarea = function(ctx) {
        let textareaAttrs = {
            class: ctx.fieldClass + " textarea-field-full w-full pr-8",
            name: ctx.useName,
            onchange: ctx.fHandler
        };

        textareaAttrs = applyDragAndDrop(ctx, textareaAttrs);

        return [m("div", {class: "relative w-full"},
            m("textarea", textareaAttrs, ctx.defVal),
            (audioRecord ? m("div", {class: "absolute top-2 right-0 flex items-start"},
                createAudioRecordButton(ctx)
            ) : "")
        )];
    };

    /// Textlist is an alias for textarea
    renderers.textlist = renderers.textarea;

    /// ========================================
    /// MEDIA RENDERERS (image, audio, pdf)
    /// ========================================

    /// Helper to build media path
    function buildMediaPath(entity) {
        return g_application_path + "/media/" +
               am7client.dotPath(am7client.currentOrganization) +
               "/data.data" + entity.groupPath + "/" + entity.name;
    }

    /// Helper to build thumbnail path
    function buildThumbnailPath(entity, size) {
        size = size || "96x96";
        return g_application_path + "/thumbnail/" +
               am7client.dotPath(am7client.currentOrganization) +
               "/data.data" + entity.groupPath + "/" + entity.name + "/" + size;
    }

    /// Image field renderer
    /// Handles multiple entity types: imageView, data.data, profile portraits
    renderers.image = function(ctx) {
        let useEntity = ctx.useEntity || ctx.entity;
        let fieldClass = ctx.fieldClass + " image-field";
        let dataUrl;
        let clickHandler;

        if (!useEntity) {
            return [m("span", "No image data")];
        }

        let modelKey = useEntity[am7model.jsonModelKey];

        /// imageView type - show thumbnail
        if (modelKey && modelKey.match(/^imageView$/gi)) {
            let ui = useEntity.image;
            if (ui) {
                fieldClass = "carousel-item-img";
                dataUrl = buildThumbnailPath(ui, "512x512");
            }
        }
        /// data.data type - show base64 or stream
        else if (modelKey && modelKey.match(/^data\.data$/gi)) {
            if (useEntity.stream) {
                if (ctx.streamSeg) {
                    dataUrl = "data:" + useEntity.contentType + ";base64," + ctx.streamSeg.stream;
                } else if (ctx.loadStream) {
                    /// Caller must provide loadStream callback to fetch stream data
                    ctx.loadStream(useEntity.stream);
                    return [m("span", "Loading...")];
                }
            } else {
                dataUrl = "data:" + useEntity.contentType + ";base64," + useEntity.dataBytesStore;
            }
        }
        /// Profile portrait
        else if (useEntity.profile && useEntity.profile.portrait && useEntity.profile.portrait.contentType) {
            let pp = useEntity.profile.portrait;
            let charInst = ctx.inst;
            clickHandler = function() {
                if (typeof page !== "undefined") {
                    // Open gallery with character instance to allow profile selection
                    page.imageGallery([], charInst);
                }
            };
            dataUrl = buildThumbnailPath(pp, "96x96");
        }
        /// Direct portrait
        else if (useEntity.portrait && useEntity.portrait.contentType) {
            let pp = useEntity.portrait;
            clickHandler = function() {
                if (typeof page !== "undefined") page.imageView(pp);
            };
            dataUrl = buildThumbnailPath(pp, "96x96");
        }

        if (!dataUrl) {
            return [m("span", "No image source")];
        }

        return [m("img", {
            class: fieldClass,
            name: ctx.useName,
            onclick: clickHandler,
            src: dataUrl
        })];
    };

    /// Gallery field renderer for multiple images with navigation
    renderers.gallery = function(ctx) {
        let useEntity = ctx.useEntity || ctx.entity;
        let fieldClass = ctx.fieldClass + " gallery-field";

        // Images can come from ctx.defVal (field value), useEntity.gallery, useEntity.images, or ctx.entity directly
        let images = ctx.defVal;
        if(!images || !images.length) {
            images = useEntity && (useEntity.gallery || useEntity.images);
        }
        if(!images || !images.length) {
            images = ctx.entity && (ctx.entity.gallery || ctx.entity.images);
        }
        if (!images || !images.length) {
            return [m("span", "No images")];
        }

        // Get character instance if available (for set-as-profile)
        let charInst = useEntity && useEntity.characterInstance;

        // Store currentIndex on the entity for state persistence
        if(useEntity && typeof useEntity.currentIndex === 'undefined'){
            useEntity.currentIndex = 0;
        }
        let currentIndex = useEntity ? (useEntity.currentIndex || 0) : 0;

        // Social sharing tags list
        let sharingTags = (typeof window !== "undefined" && window.am7sharingTags)
            ? window.am7sharingTags.tags
            : ["inappropriate", "intimate", "sexy", "private", "casual", "public", "professional"];

        function navigate(delta) {
            if(useEntity){
                useEntity.currentIndex = (currentIndex + delta + images.length) % images.length;
            }
            m.redraw();
        }

        async function deleteImage() {
            if(typeof page === "undefined") return;
            let img = images[currentIndex];
            if(!img || !img.objectId) return;

            if(!confirm("Delete this image?")) return;

            await page.deleteObject("data.data", img.objectId);
            images.splice(currentIndex, 1);
            if(currentIndex >= images.length && images.length > 0){
                if(useEntity){
                    useEntity.currentIndex = images.length - 1;
                }
                currentIndex = images.length - 1;
            }
            page.toast("success", "Image deleted");
            m.redraw();
        }

        async function setAsProfile() {
            if(typeof page === "undefined" || !charInst) return;
            let img = images[currentIndex];
            if(!img || !img.id) return;

            // Update the character's profile portrait
            charInst.entity.profile.portrait = img;
            let od = {id: charInst.entity.profile.id, portrait: {id: img.id}};
            od[am7model.jsonModelKey] = "identity.profile";
            await page.patchObject(od);

            page.toast("success", "Profile image updated");
            m.redraw();
        }

        async function toggleTag(tagName) {
            if(typeof page === "undefined" || typeof window === "undefined" || !window.am7sharingTags) return;
            let img = images[currentIndex];
            if(!img || !img.objectId) return;

            // Check if tag is currently applied
            let imgTags = img.tags || [];
            let hasTag = imgTags.some(t => t.name === tagName);

            // Toggle the tag
            let success = await window.am7sharingTags.toggle(img, tagName, !hasTag);
            if(success){
                // Update local tags array
                if(hasTag){
                    img.tags = imgTags.filter(t => t.name !== tagName);
                } else {
                    let tag = await window.am7sharingTags.getOrCreate(tagName, "data.data");
                    if(tag){
                        if(!img.tags) img.tags = [];
                        img.tags.push(tag);
                    }
                }
                m.redraw();
            }
        }

        async function applyImageTags() {
            if(typeof page === "undefined") return;
            let img = images[currentIndex];
            if(!img || !img.objectId) return;

            page.toast("info", "Applying image tags...");
            try {
                let tags = await page.applyImageTags(img.objectId);
                if(tags && Array.isArray(tags) && tags.length){
                    page.toast("success", "Applied " + tags.length + " tags");
                }
                else if(tags){
                    page.toast("success", "Tags applied");
                }
                else {
                    page.toast("warn", "No tags were applied");
                }
            }
            catch(e){
                console.error(e);
                page.toast("error", "Failed to apply tags");
            }
        }

        function openImageInNewTab() {
            let img = images[currentIndex];
            if(!img || !img.objectId) return;
            let uri = "#/view/data.data/" + img.objectId;
            window.open(uri, "_blank");
        }

        let currentImage = images[currentIndex];
        let dataUrl = buildThumbnailPath(currentImage, "512x512");

        // Get current image's tags (filter to only sharing tags)
        let currentTags = (currentImage.tags || [])
            .filter(t => sharingTags.includes(t.name))
            .map(t => t.name);

        // Build navigation buttons
        let navButtons = [
            page.iconButton("button", "arrow_back", "", function() { navigate(-1); }, images.length <= 1),
            m("span", { class: "button" }, (currentIndex + 1) + " / " + images.length),
            page.iconButton("button mr-4", "arrow_forward", "", function() { navigate(1); }, images.length <= 1),
            page.iconButton("button", "delete", "", deleteImage),
            page.iconButton("button", "sell", "", applyImageTags),
            page.iconButton("button", "open_in_new", "", openImageInNewTab)
        ];

        // Only show set-as-profile button if we have a character instance
        if(charInst){
            navButtons.push(page.iconButton("button", "account_circle", "", setAsProfile));
        }

        // Build sharing tags vertical list
        let tagsList = sharingTags.map(function(tagName){
            let isActive = currentTags.includes(tagName);
            return m("button", {
                class: "gallery-tag-btn" + (isActive ? " active" : ""),
                onclick: function(){ toggleTag(tagName); }
            }, tagName);
        });

        return [
            m("div", { class: "gallery-container gallery-with-tags" }, [
                m("div", { class: "result-nav-outer" }, [
                    m("div", { class: "result-nav-inner" }, [
                        m("div", { class: "result-nav tab-container" }, navButtons)
                    ])
                ]),
                m("div", { class: "gallery-content" }, [
                    m("img", {
                        class: "carousel-item-img",
                        src: dataUrl,
                        onclick: function() {
                            if (typeof page !== "undefined") page.imageView(currentImage);
                        }
                    }),
                    m("div", { class: "gallery-tags-panel" }, tagsList)
                ])
            ])
        ];
    };

    /// Audio player field renderer
    renderers.audio = function(ctx) {
        let val = ctx.defVal;
        if (!val || typeof val !== 'object') {
            return [m("span", "No audio data")];
        }

        let apath = g_application_path + "/media/" +
                    am7client.dotPath(am7client.currentOrganization) +
                    "/data.data" + val.groupPath + "/" + val.name;
        let amt = val.contentType;
        if (amt.match(/mpeg3$/)) amt = "audio/mpeg";

        return [m("audio", {
            class: "",
            id: ctx.useName,
            preload: "auto",
            controls: "controls",
            autoplay: "autoplay"
        }, m("source", {src: apath, type: amt}))];
    };

    /// PDF viewer field renderer
    renderers.pdf = function(ctx) {
        if (ctx.pdfViewer) {
            return [ctx.pdfViewer.container()];
        }
        return [m("span", "Missing PDF viewer")];
    };

    /// ========================================
    /// OBJECT-LINK RENDERER
    /// ========================================

    /// Object link field - shows link to REST endpoint and JSON viewer
    renderers["object-link"] = function(ctx) {
        let useEntity = ctx.useEntity || ctx.entity;
        let uri = "about:blank";
        let label = "";

        if (useEntity && useEntity.objectId) {
            let modelKey = useEntity[am7model.jsonModelKey];
            if (modelKey && modelKey.match(/^data\.data$/gi)) {
                uri = g_application_path + "/media/" +
                      am7client.dotPath(am7client.currentOrganization) + "/" +
                      modelKey + useEntity.groupPath + "/" + useEntity.name;
            } else {
                uri = g_application_path + "/rest/model/" + modelKey + "/" + useEntity.objectId;
            }
            label = useEntity.name;
        }

        let printHandler = function() {
            if (ctx.printJSONToWindow) {
                ctx.printJSONToWindow();
            }
        };

        return [
            m("a", {target: "new", href: uri, class: "text-blue-600"}, [
                m("span", {class: "material-icons-outlined mr-2"}, "link"),
                label
            ]),
            m("span", {onclick: printHandler, class: "cursor-pointer material-symbols-outlined ml-2"}, "file_json")
        ];
    };

    /// ========================================
    /// PICKER RENDERER
    /// ========================================

    /// Picker field - text input with context menu for object selection
    /// Context must provide:
    ///   - doFieldPicker(field, useName, useEntity): function to open picker
    ///   - doFieldOpen(field): function to navigate to selected object
    ///   - doFieldClear(field): function to clear selection
    ///   - updateChange(evt, field): function to handle manual changes
    renderers.picker = function(ctx) {
        let useEntity = ctx.useEntity || ctx.entity;
        let field = ctx.field;
        let fieldClass = ctx.fieldClass + " text-field-full";
        let defVal = ctx.defVal;
        let fHandler = ctx.fHandler;

        /// Check if this should be a picker or just text input
        let valPick = false;
        if (typeof page !== "undefined" && page.components && page.components.picker) {
            valPick = (page.components.picker.validateFormat(ctx.inst, field, "picker", ctx.useName) === "picker");
        }

        /// Resolve picker value to display name
        if (valPick && useEntity && field.pickerProperty) {
            let findVal = useEntity[field.pickerProperty.entity];
            let pageCtx = (typeof page !== "undefined") ? page.context() : null;

            if (findVal) {
                if (typeof findVal === 'object') {
                    defVal = findVal.name;
                } else if (pageCtx && !pageCtx.contextObjects[findVal]) {
                    /// Need to load the referenced object
                    let type = field.pickerType;
                    if (!type || type === 'self') type = useEntity[am7model.jsonModelKey];
                    if (type.match(/^\./)) {
                        type = useEntity[field.pickerType.slice(1)];
                    }
                    if (typeof page !== "undefined") {
                        page.openObject(type, findVal).then(function() {
                            m.redraw();
                        });
                    }
                } else if (pageCtx) {
                    defVal = pageCtx.contextObjects[findVal].name;
                }
            }
        } else if (useEntity && field.pickerProperty) {
            defVal = useEntity[field.pickerProperty.entity];
        }

        let pickerName = ctx.useName + '-picker';
        let mid = pickerName + "-menu";

        if (valPick) {
            fieldClass += " menu-icon";
        } else {
            /// Not a picker, just handle as text with change tracking
            fHandler = function(e) {
                if (ctx.updateChange) {
                    ctx.updateChange(e, field);
                }
            };
        }

        let view = [];

        /// Input field
        let inputAttrs = {
            type: "text",
            class: fieldClass,
            name: ctx.useName,
            id: pickerName,
            value: defVal,
            autocomplete: "off",
            avoid: 1,
            onchange: fHandler
        };
        if (ctx.disabled) {
            inputAttrs.disabled = true;
        }
        view.push(m("input", inputAttrs));

        /// Context menu for picker mode
        if (valPick && typeof page !== "undefined" && page.navigable) {
            view.push(
                m("div", {class: "context-menu-container"}, [
                    m("div", {class: "transition transition-0 context-menu", id: mid}, [
                        page.navigable.contextMenuButton(pickerName, "Find", "search", function() {
                            if (ctx.doFieldPicker) ctx.doFieldPicker(field, ctx.useName, useEntity);
                        }),
                        page.navigable.contextMenuButton(pickerName, "View", "file_open", function() {
                            if (ctx.doFieldOpen) ctx.doFieldOpen(field);
                        }),
                        page.navigable.contextMenuButton(pickerName, "Clear", "backspace", function() {
                            if (ctx.doFieldClear) ctx.doFieldClear(field);
                        }),
                        page.navigable.contextMenuButton(pickerName, "Cancel", "cancel")
                    ])
                ])
            );

            /// Register context menu (this needs to be done after render)
            page.navigable.pendContextMenu("#" + mid, "#" + pickerName, null, "context-menu-96");
        }

        return view;
    };

    /// ========================================
    /// TABLE RENDERER
    /// ========================================

    /// Table field renderer - displays array data in a table format
    /// This is a complex renderer that requires significant context:
    ///   - valuesState: object tracking row selection/edit state
    ///   - foreignData: object for storing foreign relationship data
    ///   - getFormCommandView: function to render table commands
    ///   - modelField: function to render individual fields (for edit mode)
    ///
    /// Due to the complexity and tight coupling with object page state,
    /// this renderer delegates to the provided callback functions
    renderers.table = function(ctx) {
        /// Delegate to the table renderer function if provided
        if (ctx.getValuesForTableField) {
            return ctx.getValuesForTableField(
                ctx.useName,
                ctx.fieldView,
                ctx.field,
                ctx.fieldClass + " table-field",
                !ctx.show
            );
        }

        /// Fallback: simple table rendering without edit support
        let field = ctx.field;
        let entity = ctx.entity;
        let fieldView = ctx.fieldView;

        if (!entity || !field.baseModel || !fieldView.form) {
            return [m("span", "Invalid table configuration")];
        }

        let tableType = field.baseModel;
        if (tableType === "$self") {
            tableType = entity[am7model.jsonModelKey];
        }

        let tableForm = fieldView.form;
        let rows = [];

        /// Build header row
        let headers = Object.keys(tableForm.fields).map(function(k) {
            let tableFieldView = tableForm.fields[k];
            let tableField = am7model.getModelField(tableType, k);
            return m("th", tableFieldView?.label || tableField?.label || k);
        });
        rows.push(m("tr", headers));

        /// Get values
        let vals;
        if (ctx.inst && ctx.inst.api[ctx.name]) {
            vals = ctx.inst.api[ctx.name]();
        } else if (entity[ctx.name]) {
            vals = entity[ctx.name];
        }

        /// Build data rows
        if (vals && Array.isArray(vals)) {
            vals.forEach(function(v, i) {
                let cells = Object.keys(tableForm.fields).map(function(k) {
                    let val = v[k];
                    if (val === undefined || val === null) val = "";
                    return m("td", "" + val);
                });
                rows.push(m("tr", {class: "row-field"}, cells));
            });
        }

        return [m("table", {class: ctx.fieldClass + " table-field"}, rows)];
    };

    /// ========================================
    /// FORM RENDERER (nested form)
    /// ========================================

    /// Form field renderer - delegates to a callback for nested form rendering
    /// Context must provide:
    ///   - getValuesForFormField: function to render nested form content
    renderers.form = function(ctx) {
        if (ctx.getValuesForFormField) {
            return ctx.getValuesForFormField(
                ctx.useName,
                ctx.fieldView,
                ctx.field,
                "table-field",
                !ctx.show
            );
        }

        /// Fallback
        return [m("span", "Nested form rendering requires getValuesForFormField callback")];
    };

    /// ========================================
    /// REGISTRY AND EXPORT
    /// ========================================

    /// Check if a renderer exists for a format
    function hasRenderer(format) {
        return renderers.hasOwnProperty(format);
    }

    /// Get a renderer by format name
    function getRenderer(format) {
        return renderers[format];
    }

    /// Render a field using the appropriate renderer
    function render(format, ctx) {
        if (!hasRenderer(format)) {
            console.warn("No form field renderer for format: " + format);
            return null;
        }
        return renderers[format](ctx);
    }

    /// Register a new renderer
    function register(format, fn) {
        renderers[format] = fn;
    }

    /// Export
    let formFieldRenderers = {
        render: render,
        has: hasRenderer,
        get: getRenderer,
        register: register
    };

    /// Register with page.components
    if (typeof page !== "undefined" && page.components) {
        page.components.formFieldRenderers = formFieldRenderers;
    }

}());
