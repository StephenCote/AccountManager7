/**
 * SdConfigPanel — Shared SD config form used by all features (ESM)
 * One authoritative sdConfig form: chat scene gen, pictureBook, reimage dialog.
 *
 * Usage:
 *   import { SdConfigPanel } from '../components/SdConfigPanel.js';
 *   m(SdConfigPanel, { config: sdConfig, models: sdModels, loras: loraList, onChange: saveConfig })
 */
import m from 'mithril';

const STYLE_OPTIONS = ['art', 'movie', 'photograph', 'selfie', 'anime', 'portrait', 'comic', 'digitalArt', 'fashion', 'vintage', 'custom'];
const SAMPLER_OPTIONS = ['dpmpp_2m', 'euler_a', 'euler', 'dpmpp_sde', 'ddim'];
const SCHEDULER_OPTIONS = ['Karras', 'Normal', 'Exponential'];
const DIMENSION_OPTIONS = ['512', '640', '768', '832', '896', '1024', '1152', '1280', '1536', '2048'];

function inputClass() {
    return "w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-xs";
}

function fieldRow(label, content) {
    return m("div", { class: "flex items-center justify-between gap-2 mb-2" }, [
        m("label", { class: "text-xs text-gray-500 dark:text-gray-400 shrink-0 w-28" }, label),
        m("div", { class: "flex-1" }, content)
    ]);
}

function halfRow(left, right) {
    return m("div", { class: "flex gap-2 mb-2" }, [
        m("div", { class: "flex-1" }, left),
        m("div", { class: "flex-1" }, right)
    ]);
}

function selectField(config, key, options, onChange) {
    return m("select", {
        class: inputClass(),
        value: config[key] || "",
        onchange: function(e) { config[key] = e.target.value; if (onChange) onChange(); }
    }, [
        m("option", { value: "" }, "Default"),
        ...options.map(function(opt) {
            let val = typeof opt === "string" ? opt : (opt.name || opt.title || "");
            return m("option", { value: val, selected: config[key] === val }, val);
        })
    ]);
}

function numberField(config, key, min, max, step, onChange) {
    return m("input", {
        type: "number",
        min: min,
        max: max,
        step: step || 1,
        class: inputClass(),
        value: config[key],
        onchange: function(e) {
            config[key] = step && step < 1 ? parseFloat(e.target.value) : parseInt(e.target.value);
            if (onChange) onChange();
        }
    });
}

function checkboxField(config, key, label, onChange) {
    return m("label", { class: "flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300 cursor-pointer" }, [
        m("input", {
            type: "checkbox",
            checked: !!config[key],
            onchange: function(e) { config[key] = e.target.checked; if (onChange) onChange(); }
        }),
        label
    ]);
}

/**
 * Shared SdConfigPanel component.
 * attrs:
 *   config  — sdConfig object (mutated in-place)
 *   models  — array of SD model names/objects (from /rest/olio/sdModels)
 *   onChange — callback after any field changes
 *   compact — if true, render in compact mode (fewer fields)
 */
const SdConfigPanel = {
    view: function(vnode) {
        let config = vnode.attrs.config;
        let models = vnode.attrs.models || [];
        let onChange = vnode.attrs.onChange;
        let compact = vnode.attrs.compact;

        if (!config) return null;

        let modelNames = models.map(function(mdl) {
            return typeof mdl === "string" ? mdl : (mdl.name || mdl.title || "");
        }).filter(Boolean);

        let fields = [];

        // Model + Refiner Model
        fields.push(fieldRow("Model", selectField(config, "model", modelNames, onChange)));
        fields.push(fieldRow("Refiner", selectField(config, "refinerModel", modelNames, onChange)));

        // Style
        fields.push(fieldRow("Style", selectField(config, "style", STYLE_OPTIONS, onChange)));

        // Steps + Refiner Steps
        fields.push(halfRow(
            fieldRow("Steps", numberField(config, "steps", 1, 150, 1, onChange)),
            fieldRow("Ref Steps", numberField(config, "refinerSteps", 0, 150, 1, onChange))
        ));

        // CFG + Refiner CFG
        fields.push(halfRow(
            fieldRow("CFG", numberField(config, "cfg", 1, 30, 0.5, onChange)),
            fieldRow("Ref CFG", numberField(config, "refinerCfg", 1, 30, 0.5, onChange))
        ));

        // Denoising
        fields.push(fieldRow("Denoising", numberField(config, "denoisingStrength", 0, 1, 0.05, onChange)));

        // Sampler + Scheduler
        fields.push(halfRow(
            fieldRow("Sampler", selectField(config, "sampler", SAMPLER_OPTIONS, onChange)),
            fieldRow("Scheduler", selectField(config, "scheduler", SCHEDULER_OPTIONS, onChange))
        ));

        // Dimensions
        fields.push(halfRow(
            fieldRow("Width", selectField(config, "width", DIMENSION_OPTIONS, onChange)),
            fieldRow("Height", selectField(config, "height", DIMENSION_OPTIONS, onChange))
        ));

        // Seed + Hi-Res
        fields.push(halfRow(
            fieldRow("Seed", numberField(config, "seed", -1, 999999999, 1, onChange)),
            m("div", { class: "flex items-center pt-3 pl-2" }, checkboxField(config, "hires", "Hi-Res", onChange))
        ));

        if (!compact) {
            // Composition fields
            fields.push(fieldRow("Composition", m("input", {
                type: "text", class: inputClass(), value: config.bodyStyle || "",
                oninput: function(e) { config.bodyStyle = e.target.value; if (onChange) onChange(); }
            })));
            fields.push(fieldRow("Action", m("input", {
                type: "text", class: inputClass(), value: config.imageAction || "",
                oninput: function(e) { config.imageAction = e.target.value; if (onChange) onChange(); }
            })));
            fields.push(fieldRow("Setting", m("input", {
                type: "text", class: inputClass(), value: config.imageSetting || "",
                oninput: function(e) { config.imageSetting = e.target.value; if (onChange) onChange(); }
            })));
        }

        // LoRA section — available when loras list is provided
        let loraList = vnode.attrs.loras || [];
        if (loraList.length || (config.loras && config.loras.length)) {
            let currentLoras = config.loras || [];
            if (!Array.isArray(currentLoras)) currentLoras = [];
            let loraMap = {};
            currentLoras.forEach(function(entry) {
                let parts = String(entry).split(":");
                loraMap[parts[0]] = parts.length > 1 ? parseFloat(parts[1]) || 0.8 : 0.8;
            });
            function syncLoras() {
                config.loras = Object.keys(loraMap).map(function(k) { return k + ":" + loraMap[k]; });
                if (onChange) onChange();
            }
            fields.push(m("div", { class: "mt-2" }, [
                m("div", { class: "text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide mb-1" }, "LORAs"),
                m("div", { class: "space-y-1" }, [
                    loraList.map(function(name) {
                        let selected = loraMap.hasOwnProperty(name);
                        return m("div", { key: name, class: "flex items-center gap-2 text-xs" }, [
                            m("input", { type: "checkbox", checked: selected, onchange: function() {
                                if (selected) delete loraMap[name]; else loraMap[name] = 0.8;
                                syncLoras(); m.redraw();
                            } }),
                            m("span", { class: "truncate", style: "max-width:180px", title: name }, name),
                            selected ? m("input", { type: "number", class: inputClass() + " w-16", min: 0, max: 2, step: 0.05,
                                value: loraMap[name], oninput: function(e) { loraMap[name] = parseFloat(e.target.value) || 0.8; syncLoras(); }
                            }) : null
                        ]);
                    }),
                    m("input", { type: "text", class: inputClass() + " mt-1 w-full", placeholder: "loraName:weight + Enter",
                        onkeydown: function(e) {
                            if (e.key === "Enter" && e.target.value.trim()) {
                                let parts = e.target.value.trim().split(":");
                                loraMap[parts[0]] = parts.length > 1 ? parseFloat(parts[1]) || 0.8 : 0.8;
                                e.target.value = ""; syncLoras(); m.redraw();
                            }
                        }
                    })
                ])
            ]));
        }

        return m("div", { class: "space-y-0" }, fields);
    }
};

export { SdConfigPanel, STYLE_OPTIONS, SAMPLER_OPTIONS, SCHEDULER_OPTIONS, DIMENSION_OPTIONS };
export default SdConfigPanel;
