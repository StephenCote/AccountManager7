/**
 * formFieldRenderers.js — Pluggable field renderer registry for object edit views (ESM)
 * Port of Ux7 client/components/formFieldRenderers.js
 *
 * Each renderer receives a context object (rendererCtx) with:
 *   inst, entity, useEntity, field, fieldView, name, useName, defVal,
 *   fieldClass, disabled, fHandler, show, objectPage
 *
 * Registry API: has(format), render(format, ctx), get(format), register(format, fn)
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { FileUpload } from './fileUpload.js';

let renderers = {};

// ── Helpers ─────────────────────────────────────────────────────────

function getClient() {
    return am7model._client;
}

function getPage() {
    return am7model._page;
}

function buildMediaPath(entity) {
    let client = getClient();
    if (!client || !entity) return "";
    return client.mediaDataPath(entity);
}

function buildThumbnailPath(entity, size) {
    let client = getClient();
    if (!client || !entity) return "";
    size = size || "96x96";
    return client.mediaDataPath(entity, true, size);
}

function formatBytes(bytes) {
    if (!bytes || bytes === 0) return "0 B";
    let k = 1024;
    let sizes = ["B", "KB", "MB", "GB"];
    let i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}

function applyDragAndDrop(ctx, props) {
    let page = getPage();
    if (ctx.entity && !ctx.entity.objectId && ctx.fieldView && ctx.fieldView.dragAndDrop) {
        props.class += " border-dotted";
        if (page && page.components && page.components.dnd && page.components.dnd.dropProps) {
            let dnd = page.components.dnd.dropProps(ctx.inst);
            Object.assign(props, dnd);
        }
        props.placeholder = "{ Type Text or Drop File Here }";
    }
    return props;
}

// ── Simple Renderers ────────────────────────────────────────────────

renderers.blank = function(ctx) {
    return [m("span", { class: "blank" })];
};

renderers.print = function(ctx) {
    let val = ctx.defVal;
    if (!val && ctx.entity) val = ctx.entity[ctx.useName];
    return [m("span", { class: (ctx.fieldClass || "") + " inline-block text-field-full text-content" }, val)];
};

renderers["print-alert"] = function(ctx) {
    let val = ctx.defVal;
    if (!val && ctx.entity) val = ctx.entity[ctx.useName];
    return [m("span", { class: "text-content-alert" }, val)];
};

renderers.checkbox = function(ctx) {
    let inputAttrs = {
        type: "checkbox",
        class: (ctx.fieldClass || "") + " check-field",
        name: ctx.useName,
        checked: ctx.defVal,
        oninput: ctx.fHandler
    };
    if (ctx.disabled) inputAttrs.disabled = true;
    return [m("input", inputAttrs)];
};

renderers.color = function(ctx) {
    let inputAttrs = {
        type: "color",
        class: (ctx.fieldClass || "") + " color-field",
        name: ctx.useName,
        value: ctx.defVal || "#000000",
        oninput: ctx.fHandler
    };
    if (ctx.disabled) inputAttrs.disabled = true;
    return [m("input", inputAttrs)];
};

// ── Select ──────────────────────────────────────────────────────────

renderers.select = function(ctx) {
    let vals = am7view.defaultValuesForField(ctx.field);
    let selectAttrs = {
        class: (ctx.fieldClass || "") + " select-field-full",
        name: ctx.useName,
        oninput: ctx.fHandler
    };
    let fprops = ctx.inst ? ctx.inst.viewProperties(ctx.name) : null;
    if (fprops) Object.assign(selectAttrs, fprops);

    let options = (vals || []).map(function(v) {
        let vs = String(v);
        let selected = ctx.defVal != null && String(ctx.defVal).toLowerCase() === vs.toLowerCase();
        let optAttrs = { value: vs.toLowerCase() };
        if (selected) optAttrs.selected = "true";
        return m("option", optAttrs, v);
    });

    return [m("select", selectAttrs, options)];
};

// ── Range Slider ────────────────────────────────────────────────────

renderers.range = function(ctx) {
    let min = 0, max = 100;
    if (ctx.field && typeof ctx.field.minValue === "number" && typeof ctx.field.maxValue === "number") {
        if (ctx.field.type === "double" && ctx.field.maxValue >= 1) {
            max = ctx.field.maxValue * 100;
            if (min !== 0) min = ctx.field.minValue * 100;
        } else {
            min = ctx.field.minValue;
            max = ctx.field.maxValue;
        }
    }

    let inputAttrs = {
        type: "range",
        class: (ctx.fieldClass || "") + " range-field-full",
        name: ctx.useName,
        value: ctx.defVal,
        min: min,
        max: max,
        oninput: ctx.fHandler
    };
    if (ctx.disabled) inputAttrs.disabled = true;

    return [m("div", { class: "relative mb-5" }, [
        m("label", { class: "sr-only" }, "Range"),
        m("input", inputAttrs),
        m("span", { class: "text-sm text-gray-500 absolute start-0 -bottom-6" }, min),
        m("span", { class: "text-sm text-gray-500 absolute start-1/2 -translate-x-1/2 -bottom-6" }, ctx.defVal),
        m("span", { class: "text-sm text-gray-500 absolute end-0 -bottom-6" }, max)
    ])];
};

// ── Progress Bar ────────────────────────────────────────────────────

renderers.progress = function(ctx) {
    let progressAttrs = {
        class: (ctx.fieldClass || "") + " progress-field-full",
        name: ctx.useName,
        value: ctx.defVal,
        max: (ctx.field && ctx.field.maxValue) || 100,
        min: (ctx.field && ctx.field.minValue) || 0
    };
    if (ctx.disabled) progressAttrs.disabled = true;
    return [m("progress", progressAttrs)];
};

// ── Button ──────────────────────────────────────────────────────────

renderers.button = function(ctx) {
    let buttonHandler = function() {
        if (ctx.field && ctx.field.command) {
            ctx.field.command(ctx.objectPage, ctx.inst, ctx.name);
        }
    };

    let buttonContent = [];
    if (ctx.fieldView && ctx.fieldView.icon) {
        buttonContent.push(m("span", { class: "material-symbols-outlined material-icons-24" }, ctx.fieldView.icon));
    }
    if (ctx.field && ctx.field.label) {
        buttonContent.push(ctx.field.label);
    }

    let buttonAttrs = {
        class: (ctx.fieldClass || "") + " button-field-full",
        onclick: buttonHandler
    };
    if (ctx.disabled) buttonAttrs.disabled = true;

    return [m("button", buttonAttrs, buttonContent)];
};

// ── Text Inputs ─────────────────────────────────────────────────────

renderers.text = function(ctx) {
    let inputAttrs = {
        type: "text",
        class: (ctx.fieldClass || "") + " text-field-full pr-8",
        name: ctx.useName,
        value: ctx.defVal,
        oninput: ctx.fHandler
    };
    if (ctx.disabled) inputAttrs.disabled = true;
    inputAttrs = applyDragAndDrop(ctx, inputAttrs);

    return [m("div", { class: "relative w-full" },
        m("input", inputAttrs)
    )];
};

renderers["datetime-local"] = function(ctx) {
    let inputAttrs = {
        type: "datetime-local",
        class: (ctx.fieldClass || "") + " text-field-full pr-8",
        name: ctx.useName,
        value: ctx.defVal,
        oninput: ctx.fHandler
    };
    if (ctx.disabled) inputAttrs.disabled = true;

    return [m("div", { class: "relative w-full" },
        m("input", inputAttrs)
    )];
};

renderers.textarea = function(ctx) {
    let textareaAttrs = {
        class: (ctx.fieldClass || "") + " textarea-field-full w-full pr-8",
        name: ctx.useName,
        onchange: ctx.fHandler
    };
    textareaAttrs = applyDragAndDrop(ctx, textareaAttrs);

    return [m("div", { class: "relative w-full" },
        m("textarea", textareaAttrs, ctx.defVal)
    )];
};

renderers.textlist = renderers.textarea;

// ── Media Renderers ─────────────────────────────────────────────────

renderers.image = function(ctx) {
    let useEntity = ctx.useEntity || ctx.entity;
    let fieldClass = (ctx.fieldClass || "") + " image-field";

    if (!useEntity) return [m("span", "No image data")];

    let modelKey = useEntity[am7model.jsonModelKey];
    let dataUrl;
    let clickHandler;

    // imageView type
    if (modelKey && modelKey.match(/^imageView$/i)) {
        let ui = useEntity.image;
        if (ui) {
            fieldClass = "carousel-item-img";
            dataUrl = buildThumbnailPath(ui, "512x512");
        }
    }
    // data.data type
    else if (modelKey && modelKey.match(/^data\.data$/i)) {
        if (useEntity.stream) {
            if (ctx.streamSeg) {
                dataUrl = "data:" + useEntity.contentType + ";base64," + ctx.streamSeg.stream;
            } else if (ctx.loadStream) {
                ctx.loadStream(useEntity.stream);
                return [m("span", "Loading...")];
            }
        } else if (useEntity.dataBytesStore) {
            dataUrl = "data:" + useEntity.contentType + ";base64," + useEntity.dataBytesStore;
        } else {
            dataUrl = buildMediaPath(useEntity);
        }
    }
    // Profile portrait — click opens full-size image view
    else if (useEntity.profile && useEntity.profile.portrait && useEntity.profile.portrait.contentType) {
        let pp = useEntity.profile.portrait;
        dataUrl = buildThumbnailPath(pp, "96x96");
        clickHandler = function() { let p = getPage(); if (p && p.imageView) p.imageView(pp); };
    }
    // Direct portrait
    else if (useEntity.portrait && useEntity.portrait.contentType) {
        let pt = useEntity.portrait;
        dataUrl = buildThumbnailPath(pt, "96x96");
        clickHandler = function() { let p = getPage(); if (p && p.imageView) p.imageView(pt); };
    }

    if (!dataUrl) return [m("span", "No image source")];

    return [m("img", {
        class: fieldClass + (clickHandler ? " cursor-pointer hover:opacity-80" : ""),
        name: ctx.useName,
        onclick: clickHandler,
        src: dataUrl
    })];
};

renderers.gallery = function(ctx) {
    let useEntity = ctx.useEntity || ctx.entity;
    let page = getPage();

    let images = ctx.defVal;
    if (!images || !images.length) images = useEntity && (useEntity.gallery || useEntity.images);
    if (!images || !images.length) images = ctx.entity && (ctx.entity.gallery || ctx.entity.images);
    if (!images || !images.length) return [m("span", "No images")];

    if (useEntity && typeof useEntity.currentIndex === 'undefined') useEntity.currentIndex = 0;
    let currentIndex = useEntity ? (useEntity.currentIndex || 0) : 0;

    function navigate(delta) {
        if (useEntity) {
            useEntity.currentIndex = (currentIndex + delta + images.length) % images.length;
        }
        m.redraw();
    }

    let currentImage = images[currentIndex];
    let dataUrl = buildThumbnailPath(currentImage, "512x512");

    return [m("div", { class: "gallery-container" }, [
        m("div", { class: "flex items-center gap-2 mb-2" }, [
            m("button", {
                class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                onclick: function() { navigate(-1); },
                disabled: images.length <= 1
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "arrow_back")),
            m("span", { class: "text-xs text-gray-500" }, (currentIndex + 1) + " / " + images.length),
            m("button", {
                class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                onclick: function() { navigate(1); },
                disabled: images.length <= 1
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "arrow_forward"))
        ]),
        m("img", {
            class: "max-w-full rounded shadow cursor-pointer hover:opacity-90",
            src: dataUrl,
            onclick: function() {
                if (page && page.components && page.components.dialog && currentImage) {
                    let fullUrl = buildMediaPath(currentImage);
                    page.components.dialog.open({
                        title: currentImage.name || ('Image ' + (currentIndex + 1)),
                        size: 'lg',
                        content: m('div', { class: 'flex flex-col items-center' }, [
                            m('img', { src: fullUrl, class: 'max-w-full max-h-[70vh] rounded shadow', style: 'object-fit:contain' })
                        ]),
                        closable: true,
                        actions: [{ label: 'Close', icon: 'close', onclick: function() { page.components.dialog.close(); } }]
                    });
                }
            }
        })
    ])];
};

renderers.audio = function(ctx) {
    let val = ctx.defVal;
    if (!val || typeof val !== 'object') return [m("span", "No audio data")];

    let apath = buildMediaPath(val);
    let amt = val.contentType || "audio/mpeg";
    if (amt.match(/mpeg3$/)) amt = "audio/mpeg";

    return [m("audio", {
        class: "",
        id: ctx.useName,
        preload: "auto",
        controls: "controls"
    }, m("source", { src: apath, type: amt }))];
};

renderers.pdf = function(ctx) {
    if (ctx.pdfViewer) return [ctx.pdfViewer.container()];
    return [m("span", "PDF viewer not loaded")];
};

// ── Object Link ─────────────────────────────────────────────────────

renderers["object-link"] = function(ctx) {
    let useEntity = ctx.useEntity || ctx.entity;
    let client = getClient();
    let uri = "about:blank";
    let label = "";

    if (useEntity && useEntity.objectId && client) {
        let modelKey = useEntity[am7model.jsonModelKey];
        if (modelKey && modelKey.match(/^data\.data$/i)) {
            uri = client.base() + "/media/" +
                client.dotPath(client.currentOrganization) + "/" +
                modelKey + useEntity.groupPath + "/" + useEntity.name;
        } else {
            uri = client.base() + "/rest/model/" + modelKey + "/" + useEntity.objectId;
        }
        label = useEntity.name;
    }

    let printHandler = function() {
        if (ctx.printJSONToWindow) ctx.printJSONToWindow();
    };

    return [
        m("a", { target: "new", href: uri, class: "text-blue-600" }, [
            m("span", { class: "material-symbols-outlined mr-2" }, "link"),
            label
        ]),
        m("span", { onclick: printHandler, class: "cursor-pointer material-symbols-outlined ml-2" }, "file_json")
    ];
};

// ── Picker ──────────────────────────────────────────────────────────

renderers.picker = function(ctx) {
    let useEntity = ctx.useEntity || ctx.entity;
    let field = ctx.field;
    let fieldClass = (ctx.fieldClass || "") + " text-field-full";
    let defVal = ctx.defVal;
    let fHandler = ctx.fHandler;
    let page = getPage();

    // Check if this should be a picker or text
    let valPick = false;
    if (page && page.components && page.components.picker && page.components.picker.validateFormat) {
        valPick = (page.components.picker.validateFormat(ctx.inst, field, "picker", ctx.useName) === "picker");
    }

    // Resolve picker value to display name
    if (valPick && useEntity && field && field.pickerProperty) {
        let findVal = useEntity[field.pickerProperty.entity];
        if (findVal && typeof findVal === 'object') {
            defVal = findVal.name;
        }
    } else if (useEntity && field && field.pickerProperty) {
        defVal = useEntity[field.pickerProperty.entity];
    }

    let view = [];

    if (valPick) {
        // Picker fields render as clickable label + icon buttons (not text inputs)
        let openPicker = function() { if (ctx.doFieldPicker) ctx.doFieldPicker(field, ctx.useName, useEntity); };
        view.push(m("div", { class: "w-full flex items-center gap-1 px-4 py-2 mt-2 border rounded-md border-gray-300 dark:border-gray-600 bg-white dark:bg-black" }, [
            m("span", {
                class: "flex-1 text-sm text-gray-800 dark:text-white truncate cursor-pointer hover:text-blue-600 dark:hover:text-blue-400",
                onclick: openPicker,
                title: defVal || "(none)"
            }, defVal || "(none)"),
            m("button", {
                class: "p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
                title: "Find",
                onclick: openPicker
            }, m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:18px" }, "search")),
            m("button", {
                class: "p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
                title: "View",
                onclick: function() { if (ctx.doFieldOpen) ctx.doFieldOpen(field); }
            }, m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:18px" }, "file_open")),
            m("button", {
                class: "p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700",
                title: "Clear",
                onclick: function() { if (ctx.doFieldClear) ctx.doFieldClear(field); }
            }, m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:18px" }, "backspace"))
        ]));
    } else {
        // Non-picker text field fallback
        let inputAttrs = {
            type: "text",
            class: (ctx.fieldClass || "") + " text-field-full",
            name: ctx.useName,
            value: defVal,
            autocomplete: "off",
            onchange: fHandler
        };
        if (ctx.disabled) inputAttrs.disabled = true;
        view.push(m("input", inputAttrs));
    }

    return view;
};

// ── Table ───────────────────────────────────────────────────────────

renderers.table = function(ctx) {
    let page = getPage();

    // Use table list editor for standard CRUD
    if (page && page.components && page.components.tableListEditor
        && ctx.fieldView && ctx.fieldView.form
        && page.components.tableListEditor.isStandardCrud
        && page.components.tableListEditor.isStandardCrud(ctx.fieldView.form)) {
        return page.components.tableListEditor.render(ctx);
    }

    // Delegate to legacy table renderer
    if (ctx.getValuesForTableField) {
        return ctx.getValuesForTableField(
            ctx.useName, ctx.fieldView, ctx.field,
            (ctx.fieldClass || "") + " table-field", !ctx.show
        );
    }

    // Fallback: simple table rendering
    let field = ctx.field;
    let entity = ctx.entity;
    let fieldView = ctx.fieldView;

    if (!entity || !field || !field.baseModel || !fieldView || !fieldView.form) {
        return [m("span", "Invalid table configuration")];
    }

    let tableType = field.baseModel;
    if (tableType === "$self") tableType = entity[am7model.jsonModelKey];

    let tableForm = fieldView.form;
    let rows = [];

    // Header
    let headers = Object.keys(tableForm.fields).map(function(k) {
        let tfv = tableForm.fields[k];
        let tf = am7model.getModelField(tableType, k);
        return m("th", { class: "px-2 py-1 text-left text-xs font-medium text-gray-500 uppercase" },
            (tfv && tfv.label) || (tf && tf.label) || k);
    });
    rows.push(m("tr", headers));

    // Data
    let vals;
    if (ctx.inst && ctx.inst.api[ctx.name]) vals = ctx.inst.api[ctx.name]();
    else if (entity[ctx.name]) vals = entity[ctx.name];

    if (vals && Array.isArray(vals)) {
        vals.forEach(function(v) {
            let cells = Object.keys(tableForm.fields).map(function(k) {
                let val = v[k];
                if (val === undefined || val === null) val = "";
                return m("td", { class: "px-2 py-1 text-sm" }, "" + val);
            });
            rows.push(m("tr", { class: "border-t border-gray-200 dark:border-gray-700" }, cells));
        });
    }

    return [m("table", { class: "w-full border-collapse" }, rows)];
};

// ── Form (nested) ───────────────────────────────────────────────────

renderers.form = function(ctx) {
    if (ctx.getValuesForFormField) {
        return ctx.getValuesForFormField(
            ctx.useName, ctx.fieldView, ctx.field, "table-field", !ctx.show
        );
    }
    return [m("span", "Nested form rendering requires getValuesForFormField callback")];
};

// ── Content Type Renderer (for data.data) ───────────────────────────

renderers.contentType = function(ctx) {
    let useEntity = ctx.useEntity || ctx.entity;
    if (!useEntity || !useEntity.contentType) {
        return renderers.text(ctx);
    }

    let ct = useEntity.contentType;
    if (ct.match(/^image/)) return renderers.image(ctx);
    if (ct.match(/^audio/)) return renderers.audio(ctx);
    if (ct.match(/^video/)) {
        let vpath = buildMediaPath(useEntity);
        return [m("video", {
            class: "max-w-full rounded",
            controls: true,
            preload: "metadata"
        }, m("source", { src: vpath, type: ct }))];
    }
    if (ct.match(/pdf$/)) return renderers.pdf(ctx);

    // Text content types — show as editable textarea
    if (ct.match(/^text\//) || ct.match(/json$/) || ct.match(/xml$/) || ct.match(/javascript$/)) {
        return renderers.textarea(ctx);
    }

    // Binary/unknown content types — show info + download link + upload zone
    return renderers.binaryContent(ctx);
};

renderers.binaryContent = function(ctx) {
    let useEntity = ctx.useEntity || ctx.entity;
    let ct = (useEntity && useEntity.contentType) || "application/octet-stream";
    let client = getClient();
    let page = getPage();
    let downloadUrl = buildMediaPath(useEntity);
    let modelKey = useEntity ? useEntity[am7model.jsonModelKey] : null;

    return [m("div", { class: "space-y-3" }, [
        // Content type info
        m("div", { class: "flex items-center gap-2 text-sm text-gray-600 dark:text-gray-300" }, [
            m("span", { class: "material-symbols-outlined", style: "font-size:20px" }, "description"),
            m("span", ct),
            useEntity && useEntity.size ? m("span", { class: "text-gray-400" }, "(" + formatBytes(useEntity.size) + ")") : null
        ]),
        // Download link
        downloadUrl ? m("a", {
            href: downloadUrl,
            target: "_blank",
            class: "inline-flex items-center gap-1 text-sm text-blue-600 hover:text-blue-800 dark:text-blue-400"
        }, [
            m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "download"),
            "Download"
        ]) : null,
        // Upload replacement
        useEntity && useEntity.objectId ? m(FileUpload.View, {
            type: modelKey,
            objectId: useEntity.objectId,
            field: ctx.useName,
            onComplete: function() {
                if (client) client.clearCache(modelKey);
                if (page) page.clearContextObject(useEntity.objectId);
                m.redraw();
            }
        }) : null
    ])];
};

// ── Registry API ────────────────────────────────────────────────────

function hasRenderer(format) {
    return renderers.hasOwnProperty(format);
}

function getRenderer(format) {
    return renderers[format];
}

function render(format, ctx) {
    if (!hasRenderer(format)) {
        console.warn("No form field renderer for format: " + format);
        return null;
    }
    return renderers[format](ctx);
}

function register(format, fn) {
    renderers[format] = fn;
}

const formFieldRenderers = {
    render: render,
    has: hasRenderer,
    get: getRenderer,
    register: register
};

export { formFieldRenderers };
export default formFieldRenderers;
