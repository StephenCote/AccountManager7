/**
 * SdConfigPanel — Shared SD config form (ESM)
 * Layout mirrors the reimage dialog so the same fields/options are exposed
 * everywhere: scene generation, picture book, reimage, etc.
 *
 * Usage:
 *   import { SdConfigPanel } from '../components/SdConfigPanel.js';
 *   m(SdConfigPanel, { config: sdConfig, models: sdModels, loras: loraList, onChange: saveConfig })
 */
import m from 'mithril';

const STYLE_OPTIONS = ['art', 'movie', 'photograph', 'selfie', 'anime', 'portrait', 'comic', 'digitalArt', 'fashion', 'vintage', 'custom'];

/// Full sampler list matched to the reimage dialog (workflows/reimage.js).
const SAMPLER_OPTIONS = [
    'dpmpp_2m', 'dpmpp_2m_sde', 'dpmpp_2s_ancestral', 'dpmpp_3m_sde', 'dpmpp_sde',
    'euler', 'euler_ancestral', 'heun', 'lms', 'ddim', 'ddpm',
    'dpm_2', 'dpm_2_ancestral', 'dpm_adaptive', 'dpm_fast',
    'uni_pc', 'uni_pc_bh2', 'ipndm', 'ipndm_v', 'lcm'
];

/// Full scheduler list matched to the reimage dialog.
const SCHEDULER_OPTIONS = [
    'normal', 'karras', 'exponential', 'sgm_uniform', 'simple',
    'ddim_uniform', 'beta', 'linear_quadratic', 'kl_optimal'
];

const DIMENSION_OPTIONS = ['512', '640', '768', '832', '896', '1024', '1152', '1280', '1536', '2048'];

function inputClass() {
    return "w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-800 dark:text-white text-xs";
}

function labelClass() {
    return "block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5";
}

/// Stacked field: label sits above the input. Used inside a grid cell.
function field(label, inputNode) {
    return m("div", [
        m("label", { class: labelClass() }, label),
        inputNode
    ]);
}

function modelSelectInput(config, key, modelNames, onChange) {
    let val = config[key] || "";
    if (modelNames.length > 0) {
        return m("select", {
            class: inputClass(),
            value: val,
            onchange: function(e) { config[key] = e.target.value; if (onChange) onChange(); }
        }, [m("option", { value: "" }, "-- Select --")].concat(
            modelNames.map(function(ml) {
                return m("option", { value: ml, selected: ml === val }, ml);
            })
        ));
    }
    return m("input", {
        type: "text",
        class: inputClass(),
        value: val,
        oninput: function(e) { config[key] = e.target.value; if (onChange) onChange(); }
    });
}

function selectInput(config, key, options, onChange) {
    let val = config[key] != null ? config[key] : "";
    return m("select", {
        class: inputClass(),
        value: val,
        onchange: function(e) { config[key] = e.target.value; if (onChange) onChange(); }
    }, options.map(function(opt) {
        return m("option", { value: opt, selected: String(val) === String(opt) }, opt);
    }));
}

function numberInput(config, key, min, max, step, onChange) {
    return m("input", {
        type: "number",
        min: min,
        max: max,
        step: step || 1,
        class: inputClass(),
        value: config[key] != null ? config[key] : "",
        onchange: function(e) {
            let raw = e.target.value;
            if (raw === "") { config[key] = null; }
            else { config[key] = (step && step < 1) ? parseFloat(raw) : parseInt(raw); }
            if (onChange) onChange();
        }
    });
}

function rangeInput(config, key, min, max, step, onChange) {
    return m("input", {
        type: "range",
        min: min,
        max: max,
        step: step || 1,
        class: "w-full",
        value: config[key] != null ? config[key] : min,
        oninput: function(e) {
            config[key] = (step && step < 1) ? parseFloat(e.target.value) : parseInt(e.target.value);
            if (onChange) onChange();
        }
    });
}

function checkboxInput(config, key, onChange) {
    return m("input", {
        type: "checkbox",
        checked: !!config[key],
        onchange: function(e) { config[key] = e.target.checked; if (onChange) onChange(); }
    });
}

function textInput(config, key, onChange, placeholder) {
    return m("input", {
        type: "text",
        class: inputClass(),
        value: config[key] || "",
        placeholder: placeholder || "",
        oninput: function(e) { config[key] = e.target.value; if (onChange) onChange(); }
    });
}

/// Style-specific field appears only when current style matches one of
/// the pipe-delimited styles (mirrors styleField in workflows/reimage.js).
function styleField(config, label, key, styles, onChange) {
    let current = config.style || "";
    if (styles.split('|').indexOf(current) < 0) return null;
    return field(label, textInput(config, key, onChange));
}

/**
 * Shared SdConfigPanel component.
 * attrs:
 *   config  — sdConfig object (mutated in-place)
 *   models  — array of SD model names/objects (from /rest/olio/sdModels)
 *   loras   — optional array of LoRA names
 *   onChange — callback after any field changes
 *   compact — kept for back-compat; layout is identical (single source of truth)
 */
const SdConfigPanel = {
    view: function(vnode) {
        let config = vnode.attrs.config;
        let models = vnode.attrs.models || [];
        let loraList = vnode.attrs.loras || [];
        let onChange = vnode.attrs.onChange;

        if (!config) return null;

        let modelNames = models.map(function(mdl) {
            return typeof mdl === "string" ? mdl : (mdl.name || mdl.title || "");
        }).filter(Boolean);

        let sections = [];

        /// Composition / Action / Setting — prompt-shaping text (3-up).
        sections.push(m("div", { class: "grid grid-cols-3 gap-3" }, [
            field("Composition", textInput(config, "bodyStyle", onChange)),
            field("Action", textInput(config, "imageAction", onChange)),
            field("Setting", textInput(config, "imageSetting", onChange))
        ]));

        /// Style + Denoising (range with live value in label).
        sections.push(m("div", { class: "grid grid-cols-2 gap-3" }, [
            field("Style", selectInput(config, "style", [""].concat(STYLE_OPTIONS), onChange)),
            field("Denoising: " + (config.denoisingStrength != null ? config.denoisingStrength : 0.65),
                rangeInput(config, "denoisingStrength", 0, 1, 0.05, onChange))
        ]));

        /// Style-specific fields — only those matching the current style appear.
        let styleFields = [
            styleField(config, 'Art Style', 'artStyle', 'art', onChange),
            styleField(config, 'Director', 'director', 'movie', onChange),
            styleField(config, 'Photographer', 'photographer', 'photograph|portrait|fashion', onChange),
            styleField(config, 'Phone', 'selfiePhone', 'selfie', onChange),
            styleField(config, 'Angle', 'selfieAngle', 'selfie', onChange),
            styleField(config, 'Lighting', 'selfieLighting', 'selfie', onChange),
            styleField(config, 'Studio', 'animeStudio', 'anime', onChange),
            styleField(config, 'Era', 'animeEra', 'anime', onChange),
            styleField(config, 'Lighting', 'portraitLighting', 'portrait', onChange),
            styleField(config, 'Backdrop', 'portraitBackdrop', 'portrait', onChange),
            styleField(config, 'Publisher', 'comicPublisher', 'comic', onChange),
            styleField(config, 'Era', 'comicEra', 'comic', onChange),
            styleField(config, 'Coloring', 'comicColoring', 'comic', onChange),
            styleField(config, 'Medium', 'digitalMedium', 'digitalArt', onChange),
            styleField(config, 'Software', 'digitalSoftware', 'digitalArt', onChange),
            styleField(config, 'Artist', 'digitalArtist', 'digitalArt', onChange),
            styleField(config, 'Magazine', 'fashionMagazine', 'fashion', onChange),
            styleField(config, 'Decade', 'fashionDecade', 'fashion', onChange),
            styleField(config, 'Decade', 'vintageDecade', 'vintage', onChange),
            styleField(config, 'Processing', 'vintageProcessing', 'vintage', onChange),
            styleField(config, 'Camera', 'vintageCamera', 'vintage', onChange),
            styleField(config, 'Still Camera', 'stillCamera', 'photograph', onChange),
            styleField(config, 'Lens', 'lens', 'photograph', onChange),
            styleField(config, 'Film', 'film', 'photograph', onChange),
            styleField(config, 'Process', 'colorProcess', 'movie|photograph', onChange),
            styleField(config, 'Movie Camera', 'movieCamera', 'movie', onChange),
            styleField(config, 'Movie Film', 'movieFilm', 'movie', onChange)
        ].filter(Boolean);
        if (styleFields.length) {
            sections.push(m("div", { class: "grid grid-cols-2 gap-3" }, styleFields));
        }

        /// Custom prompt — only when style=custom.
        if (config.style === 'custom') {
            sections.push(field("Custom Style Prompt", m("textarea", {
                class: inputClass(),
                rows: 3,
                value: config.customPrompt || "",
                oninput: function(e) { config.customPrompt = e.target.value; if (onChange) onChange(); }
            })));
        }

        /// Model / Refiner / Steps / Refiner Steps / CFG / Refiner CFG /
        /// Sampler / Scheduler / Refiner Sampler / Refiner Scheduler /
        /// Width / Height / Seed / Image Count / Hi-Res / Hi-Res checkbox.
        let coreFields = [
            field("Model", modelSelectInput(config, "model", modelNames, onChange)),
            field("Refiner Model", modelSelectInput(config, "refinerModel", modelNames, onChange)),

            field("Steps: " + (config.steps != null ? config.steps : 30),
                rangeInput(config, "steps", 1, 100, 1, onChange)),
            field("Refiner Steps: " + (config.refinerSteps != null ? config.refinerSteps : 20),
                rangeInput(config, "refinerSteps", 0, 100, 1, onChange)),

            field("CFG: " + (config.cfg != null ? config.cfg : 7),
                rangeInput(config, "cfg", 1, 30, 0.5, onChange)),
            field("Refiner CFG: " + (config.refinerCfg != null ? config.refinerCfg : 7),
                rangeInput(config, "refinerCfg", 1, 30, 0.5, onChange)),

            field("Sampler", selectInput(config, "sampler", SAMPLER_OPTIONS, onChange)),
            field("Scheduler", selectInput(config, "scheduler", SCHEDULER_OPTIONS, onChange)),

            field("Refiner Sampler", selectInput(config, "refinerSampler", SAMPLER_OPTIONS, onChange)),
            field("Refiner Scheduler", selectInput(config, "refinerScheduler", SCHEDULER_OPTIONS, onChange)),

            field("Width", selectInput(config, "width", DIMENSION_OPTIONS, onChange)),
            field("Height", selectInput(config, "height", DIMENSION_OPTIONS, onChange)),

            field("Seed", m("div", { class: "flex gap-1" }, [
                m("input", {
                    type: "number",
                    class: inputClass(),
                    style: "flex:1",
                    value: config.seed != null ? config.seed : -1,
                    oninput: function(e) {
                        config.seed = parseInt(e.target.value);
                        if (isNaN(config.seed)) config.seed = -1;
                        if (onChange) onChange();
                    }
                }),
                m("button", {
                    class: "px-2 py-1 rounded border border-gray-300 dark:border-gray-600 text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700",
                    title: "Random seed",
                    onclick: function() { config.seed = -1; if (onChange) onChange(); }
                }, m("span", { class: "material-symbols-outlined", style: "font-size:14px" }, "casino"))
            ])),
            field("Image Count", numberInput(config, "imageCount", 1, 16, 1, onChange)),

            m("div", { class: "flex items-end h-full pb-1" }, [
                m("label", { class: "flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300 cursor-pointer" }, [
                    checkboxInput(config, "hires", onChange),
                    m("span", "Hi-Res")
                ])
            ])
        ];
        sections.push(m("div", { class: "grid grid-cols-2 gap-3" }, coreFields));

        /// LoRA section — checkbox per LoRA + per-LoRA weight + free-form add.
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
            sections.push(m("div", { class: "mt-2" }, [
                m("div", { class: "text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide mb-1" }, "LORAs"),
                m("div", { class: "space-y-1" }, [
                    loraList.map(function(name) {
                        let selected = loraMap.hasOwnProperty(name);
                        return m("div", { key: name, class: "flex items-center gap-2 text-xs" }, [
                            m("input", { type: "checkbox", checked: selected, onchange: function() {
                                if (selected) delete loraMap[name]; else loraMap[name] = 0.8;
                                syncLoras(); m.redraw();
                            } }),
                            m("span", { class: "truncate", style: "max-width:240px", title: name }, name),
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

        return m("div", { class: "space-y-3" }, sections);
    }
};

export { SdConfigPanel, STYLE_OPTIONS, SAMPLER_OPTIONS, SCHEDULER_OPTIONS, DIMENSION_OPTIONS };
export default SdConfigPanel;
