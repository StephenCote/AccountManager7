/**
 * viewExtensions.js — Extensions to am7view for custom field/object renderers (ESM)
 * Port of Ux7 client/viewExtensions.js
 *
 * Adds: registerCustomRenderer(), selectObjectRenderer(), prepareObjectView()
 * Wraps: am7view.field() and am7view.fieldView() to check custom renderers first
 */
import m from 'mithril';
import { am7model } from './model.js';
import { am7view } from './view.js';

// ── Custom Renderer Registry ────────────────────────────────────────

if (!am7view.customRenderers) {
    am7view.customRenderers = {};
}

am7view.registerCustomRenderer = function(formatName, renderFunction) {
    am7view.customRenderers[formatName] = renderFunction;
};

// ── Wrap field() to dispatch to custom renderers ────────────────────

let originalGetField = am7view.field;

am7view.field = function(fld, inst) {
    let format = inst.formField(fld, "format");
    if (format && am7view.customRenderers[format]) {
        return am7view.customRenderers[format](inst, fld);
    }
    return originalGetField(fld, inst);
};

// ── Wrap fieldView() to dispatch to custom renderers ────────────────

let originalFieldView = am7view.fieldView;

am7view.fieldView = function(fld, inst) {
    let f = inst.field(fld);
    let ff = inst.formField(fld);

    if (ff && ff.hide) {
        return m("span", "");
    }

    let format = inst.formField(fld, "format");
    if (format && am7view.customRenderers[format]) {
        let annot = "";
        if (inst.validationErrors[fld]) {
            annot = am7view.errorLabel(inst.validationErrors[fld]);
        }

        let showLabel = ff ? (ff.showLabel !== false) : true;

        if (showLabel) {
            return m("div", [
                m("label", { class: "block", for: fld }, inst.label(fld)),
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

    return originalFieldView(fld, inst);
};

// ── Object Renderer Selection ───────────────────────────────────────

am7view.selectObjectRenderer = function(object) {
    if (!object) return null;
    // Check for portrait
    if (object.profile && object.profile.portrait && object.profile.portrait.contentType) {
        return "portrait";
    }

    // Check model
    let model = object[am7model.jsonModelKey];
    if (model) {
        switch (model) {
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
    if (mt.match(/^image/) || mt.match(/webp$/)) return "image";
    if (mt.match(/^video/)) return "video";
    if (mt.match(/^audio/)) return "audio";
    if (mt.match(/^text/) || mt.match(/(css|javascript)$/)) return "text";
    if (mt.match(/pdf$/)) return "pdf";

    return null;
};

// ── Prepare Object View Instance ────────────────────────────────────

am7view.prepareObjectView = function(object, attrs) {
    let model = (attrs && attrs[am7model.jsonModelKey]) || object[am7model.jsonModelKey];

    if (!model) {
        console.warn("No model specified for object view");
        return null;
    }

    let rendererType = am7view.selectObjectRenderer(object);
    if (!rendererType) {
        console.warn("No renderer found for object", object);
        return null;
    }

    let entity = {
        [am7model.jsonModelKey]: "view.objectDisplay",
        objectId: object.objectId,
        active: attrs ? (attrs.active !== false) : true,
        maxMode: attrs ? (attrs.maxMode || false) : false,
        viewType: rendererType,
        _sourceObject: object
    };

    Object.assign(entity, object);

    let formName = rendererType + "View";
    let form = am7model.forms[formName];
    if (!form) {
        console.warn("No form found for renderer type:", rendererType);
        return null;
    }

    return am7model.prepareInstance(entity, form);
};

export { am7view };
