import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { am7view } from '../core/view.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { am7sd } from '../components/sdConfig.js';

/**
 * Reimage workflow — opens SD config dialog, generates images for
 * olio.charPerson or data.data objects via /olio/{type}/{id}/reimage.
 *
 * Ported from Ux7 dialog.js reimage() — includes:
 *   - Full form-system SD config (model/refiner dropdowns from server)
 *   - Per-character config save/load
 *   - Shared config save/load
 *   - Apparel sequence generation (all wear levels)
 *   - Reference image selection
 *   - Random config
 *   - Dress up/down buttons
 *   - Style-specific fields with conditional visibility
 *   - Image tagging (wear level, sharing context, character name)
 */

// Social sharing context mapping to wear levels
const socialSharingMap = {
    'NONE': 'nude', 'INTERNAL': 'nude', 'UNDER': 'nude', 'ON': 'nude',
    'BASE': 'intimate',
    'ACCENT': 'public', 'SUIT': 'public', 'GARNITURE': 'public',
    'ACCESSORY': 'public', 'OVER': 'public', 'OUTER': 'public',
    'FULL_BODY': 'public', 'ENCLOSURE': 'public',
    'UNKNOWN': null
};

async function getOrCreateSharingTag(tagName, objectType) {
    let q = am7client.newQuery('data.tag');
    q.field('name', tagName);
    q.field('type', objectType);
    q.range(0, 1);
    let qr = await page.search(q);
    if (qr && qr.results && qr.results.length) return qr.results[0];

    let grp = await page.findObject('auth.group', 'data', '~/Tags');
    if (!grp) return null;
    let newTag = am7model.newPrimitive('data.tag');
    newTag.name = tagName;
    newTag.type = objectType;
    newTag.groupId = grp.id;
    newTag.groupPath = grp.path;
    newTag.description = 'Social sharing context: ' + tagName;
    return await page.createObject(newTag);
}

async function applySharingTag(image, wearLevelName) {
    let ctx = socialSharingMap[wearLevelName];
    if (!ctx) return;
    let tag = await getOrCreateSharingTag(ctx, 'data.data');
    if (!tag) return;
    await am7client.member('data.tag', tag.objectId, null, 'data.data', image.objectId, true);
}

function getCurrentWearLevel(inst) {
    let store = inst.entity.store;
    if (!store || !store.apparel) return null;
    let activeApp = store.apparel.find(function (a) { return a.inuse; }) || store.apparel[0];
    if (!activeApp || !activeApp.wearables) return null;

    let wearLevels = am7model.enums.wearLevelEnumType || [];
    let maxLevel = -1;
    let maxLevelName = 'NONE';

    activeApp.wearables.forEach(function (w) {
        if (!w.inuse) return;
        let lvl = w.level ? w.level.toUpperCase() : 'UNKNOWN';
        let idx = wearLevels.indexOf(lvl);
        if (idx > maxLevel) {
            maxLevel = idx;
            maxLevelName = lvl;
        }
    });

    return { level: maxLevelName, index: maxLevel >= 0 ? maxLevel : 0 };
}

// Tag a generated image with character name, wear level, sharing context, image tags
async function tagImage(x, inst, isCharPerson, wearLevel, cinst) {
    if (wearLevel) {
        await am7client.patchAttribute(x, 'wearLevel', wearLevel.level + '/' + wearLevel.index);
        await applySharingTag(x, wearLevel.level);
    }
    page.toast('info', 'Auto-tagging ' + (x.name || 'image') + '...', 5000);
    await am7client.applyImageTags(x.objectId);
    if (isCharPerson) {
        let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
        if (nameTag) await am7client.member('data.tag', nameTag.objectId, null, 'data.data', x.objectId, true);
        let imageNameTag = await getOrCreateSharingTag(inst.api.name(), 'data.data');
        if (imageNameTag) await am7client.member('data.tag', imageNameTag.objectId, null, 'data.data', x.objectId, true);
    }
    if (cinst.entity.style === 'selfie') {
        let selfieTag = await getOrCreateSharingTag('selfie', 'data.data');
        if (selfieTag) await am7client.member('data.tag', selfieTag.objectId, null, 'data.data', x.objectId, true);
    }
}

let lastReimage = null;

function openGalleryDialog(inst) {
    if (page.imageGallery) {
        page.imageGallery([], inst);
    }
}

async function reimage(entity, inst) {
    let am7olio = am7model._olio;
    let isCharPerson = inst.model.name === 'olio.charPerson';

    // Fetch SD models for dropdowns
    let sdModelList = await am7sd.fetchModels();

    let sdEntity = await am7sd.fetchTemplate(true);
    if (!sdEntity) {
        sdEntity = am7model.newPrimitive('olio.sdConfig');
    }
    let cinst = lastReimage || am7model.prepareInstance(sdEntity, am7model.forms.sdConfig);

    // Preferred defaults — applied before config load, overridden by saved config
    function tempApplyDefaults() {
        cinst.api.steps(40);
        cinst.api.refinerSteps(40);
        cinst.api.cfg(5);
        cinst.api.refinerCfg(5);
        if (cinst.api.denoisingStrength) cinst.api.denoisingStrength(75);
    }

    tempApplyDefaults();
    cinst.entity.style = 'photograph';
    if (cinst.api.hires) cinst.api.hires(false);

    // Load character-specific config
    let charConfigName = inst.api.name() + '-SD.json';
    let charConfig = await am7sd.loadConfig(charConfigName);
    if (charConfig) am7sd.applyConfig(cinst, charConfig);

    // Fill style-specific defaults after style may have changed from template
    am7sd.fillStyleDefaults(cinst.entity);

    lastReimage = cinst;

    if (isCharPerson) {
        if (am7olio) await am7olio.setNarDescription(inst, cinst);
    } else if (inst.model.name === 'data.data' && inst.api.objectId()) {
        // Only set referenceImageId for actual image objects, not for other model types
        cinst.entity.referenceImageId = inst.api.objectId();
    }

    // Apply preferred seed from character attributes
    let cseed = (inst.entity.attributes || []).filter(function (a) { return a.name === 'preferredSeed'; });
    if (cseed.length) cinst.api.seed(cseed[0].value);

    let imageCount = cinst.api.imageCount ? String(cinst.api.imageCount()) : '1';
    let seed = cinst.api.seed ? String(cinst.api.seed()) : '-1';

    // --- Helper: build a select dropdown or text input ---
    function modelSelect(apiField) {
        let val = cinst.api[apiField] ? cinst.api[apiField]() : '';
        if (sdModelList.length > 0) {
            return m('select', {
                class: 'text-field-compact', value: val,
                onchange: function (e) { if (cinst.api[apiField]) cinst.api[apiField](e.target.value); }
            }, [m('option', { value: '' }, '-- Select --')].concat(
                sdModelList.map(function (ml) { return m('option', { value: ml, selected: ml === val }, ml); })
            ));
        }
        return m('input', {
            class: 'text-field-compact', value: val,
            oninput: function (e) { if (cinst.api[apiField]) cinst.api[apiField](e.target.value); }
        });
    }

    // --- Helper: style-conditional field ---
    function styleField(label, apiField, styles) {
        let current = cinst.entity.style || '';
        let show = styles.split('|').indexOf(current) >= 0;
        if (!show) return null;
        let val = cinst.entity[apiField] || '';
        return m('div', [
            m('label', { class: 'field-label' }, label),
            m('input', {
                class: 'text-field-compact', value: val,
                oninput: function (e) { cinst.entity[apiField] = e.target.value; }
            })
        ]);
    }

    function renderContent() {
        return m('div', { class: 'p-4 space-y-3', style: 'max-height: 70vh; overflow-y: auto;' }, [
            // Narration description
            cinst.entity.narDescription ? m('div', { class: 'p-2 bg-gray-50 dark:bg-gray-800 rounded text-sm max-h-24 overflow-y-auto mb-2' }, cinst.entity.narDescription) : '',

            // Row: buttons
            m('div', { class: 'flex flex-wrap gap-2 mb-2' }, [
                isCharPerson ? m('button', {
                    class: 'button', onclick: async function () {
                        if (am7olio) { await am7olio.dressCharacter(inst, false); await am7olio.setNarDescription(inst, cinst); m.redraw(); }
                    }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'remove'), 'Dress Down']) : null,
                isCharPerson ? m('button', {
                    class: 'button', onclick: async function () {
                        if (am7olio) { await am7olio.dressCharacter(inst, true); await am7olio.setNarDescription(inst, cinst); m.redraw(); }
                    }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'add'), 'Dress Up']) : null,
                m('button', {
                    class: 'button', title: 'Random seed', onclick: function () { seed = '-1'; cinst.api.seed(-1); }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'casino'), 'Random Seed']),
                m('button', {
                    class: 'button', title: 'New random config', onclick: async function () {
                        let ncfg = await am7sd.fetchTemplate(true);
                        if (ncfg) {
                            cinst.api.style(ncfg.style);
                            let savedSeed = cinst.api.seed();
                            for (let k in ncfg) {
                                if (k !== 'id' && k !== 'objectId' && k !== 'style') {
                                    if (cinst.api[k]) cinst.api[k](ncfg[k]);
                                }
                            }
                            cinst.api.seed(savedSeed);
                            tempApplyDefaults();
                            am7sd.fillStyleDefaults(cinst.entity);
                            m.redraw();
                        }
                    }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'run_circle'), 'New Config']),
                m('button', {
                    class: 'button', title: 'Load shared config', onclick: async function () {
                        let sharedConfig = await am7sd.loadConfig('sharedSD.json');
                        if (sharedConfig) {
                            let savedSeed = cinst.api.seed();
                            am7sd.applyConfig(cinst, sharedConfig);
                            cinst.api.seed(savedSeed);
                            if (cinst.api.shared) cinst.api.shared(false);
                            m.redraw();
                            page.toast('success', 'Loaded shared SD config');
                        } else {
                            page.toast('warn', 'No shared SD config found');
                        }
                    }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'open_in_new'), 'Load Shared']),
                isCharPerson ? m('button', {
                    class: 'button', title: 'Select reference image', onclick: async function () {
                        await selectReferenceImage(inst, cinst, isCharPerson);
                    }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'image'), 'Reference']) : null,
                isCharPerson ? m('button', {
                    class: 'button', title: 'Generate apparel sequence', onclick: async function () {
                        Dialog.close();
                        await createApparelSequence(inst, cinst, am7olio);
                    }
                }, [m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'checkroom'), 'Sequence']) : null
            ]),

            // Grid: core fields
            m('div', { class: 'grid grid-cols-3 gap-3' }, [
                m('div', [
                    m('label', { class: 'field-label' }, 'Composition'),
                    m('input', { class: 'text-field-compact', value: cinst.entity.bodyStyle || '', oninput: function (e) { cinst.entity.bodyStyle = e.target.value; } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Action'),
                    m('input', { class: 'text-field-compact', value: cinst.entity.imageAction || '', oninput: function (e) { cinst.entity.imageAction = e.target.value; } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Setting'),
                    m('input', { class: 'text-field-compact', value: cinst.entity.imageSetting || '', oninput: function (e) { cinst.entity.imageSetting = e.target.value; } })
                ])
            ]),

            // Style dropdown
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                m('div', [
                    m('label', { class: 'field-label' }, 'Style'),
                    m('select', {
                        class: 'text-field-compact', value: cinst.entity.style || '',
                        onchange: function (e) {
                            cinst.entity.style = e.target.value;
                            am7sd.fillStyleDefaults(cinst.entity);
                        }
                    }, ['', 'art', 'movie', 'photograph', 'selfie', 'anime', 'portrait', 'comic', 'digitalArt', 'fashion', 'vintage', 'custom'].map(function (s) {
                        return m('option', { value: s, selected: s === (cinst.entity.style || '') }, s || '-- Select --');
                    }))
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Denoising: ' + (cinst.api.denoisingStrength ? cinst.api.denoisingStrength() : 75)),
                    m('input', { class: 'text-field-compact', type: 'range', min: 0, max: 100, step: 5,
                        value: String(cinst.api.denoisingStrength ? cinst.api.denoisingStrength() : 75),
                        oninput: function (e) {
                            let v = parseInt(e.target.value);
                            if (cinst.api.denoisingStrength) cinst.api.denoisingStrength(v);
                            else cinst.entity.denoisingStrength = v / 100;
                        }
                    })
                ])
            ]),

            // Style-specific fields
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                styleField('Art Style', 'artStyle', 'art'),
                styleField('Director', 'director', 'movie'),
                styleField('Photographer', 'photographer', 'photograph|portrait|fashion'),
                styleField('Phone', 'selfiePhone', 'selfie'),
                styleField('Angle', 'selfieAngle', 'selfie'),
                styleField('Lighting', 'selfieLighting', 'selfie'),
                styleField('Studio', 'animeStudio', 'anime'),
                styleField('Era', 'animeEra', 'anime'),
                styleField('Lighting', 'portraitLighting', 'portrait'),
                styleField('Backdrop', 'portraitBackdrop', 'portrait'),
                styleField('Publisher', 'comicPublisher', 'comic'),
                styleField('Era', 'comicEra', 'comic'),
                styleField('Coloring', 'comicColoring', 'comic'),
                styleField('Medium', 'digitalMedium', 'digitalArt'),
                styleField('Software', 'digitalSoftware', 'digitalArt'),
                styleField('Artist', 'digitalArtist', 'digitalArt'),
                styleField('Magazine', 'fashionMagazine', 'fashion'),
                styleField('Decade', 'fashionDecade', 'fashion'),
                styleField('Decade', 'vintageDecade', 'vintage'),
                styleField('Processing', 'vintageProcessing', 'vintage'),
                styleField('Camera', 'vintageCamera', 'vintage'),
                styleField('Still Camera', 'stillCamera', 'photograph'),
                styleField('Lens', 'lens', 'photograph'),
                styleField('Film', 'film', 'photograph'),
                styleField('Process', 'colorProcess', 'movie|photograph'),
                styleField('Movie Camera', 'movieCamera', 'movie'),
                styleField('Movie Film', 'movieFilm', 'movie')
            ].filter(Boolean)),

            // Custom prompt
            (cinst.entity.style === 'custom') ? m('div', [
                m('label', { class: 'field-label' }, 'Custom Style Prompt'),
                m('textarea', { class: 'text-field-compact', rows: 3, value: cinst.entity.customPrompt || '', oninput: function (e) { cinst.entity.customPrompt = e.target.value; } })
            ]) : null,

            // Grid: model/refiner/steps/cfg
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                m('div', [
                    m('label', { class: 'field-label' }, 'Model'),
                    modelSelect('model')
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Model'),
                    modelSelect('refinerModel')
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Steps: ' + cinst.api.steps()),
                    m('input', { class: 'w-full', type: 'range', min: 1, max: 100, value: cinst.api.steps(), oninput: function (e) { cinst.api.steps(parseInt(e.target.value) || 20); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Steps: ' + cinst.api.refinerSteps()),
                    m('input', { class: 'w-full', type: 'range', min: 0, max: 100, value: cinst.api.refinerSteps(), oninput: function (e) { cinst.api.refinerSteps(parseInt(e.target.value) || 20); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'CFG: ' + cinst.api.cfg()),
                    m('input', { class: 'w-full', type: 'range', min: 1, max: 30, step: 0.5, value: cinst.api.cfg(), oninput: function (e) { cinst.api.cfg(parseFloat(e.target.value) || 5); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner CFG: ' + cinst.api.refinerCfg()),
                    m('input', { class: 'w-full', type: 'range', min: 1, max: 30, step: 0.5, value: cinst.api.refinerCfg(), oninput: function (e) { cinst.api.refinerCfg(parseFloat(e.target.value) || 5); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Sampler'),
                    m('select', { class: 'text-field-compact', value: cinst.entity.sampler || 'dpmpp_2m', onchange: function (e) { cinst.entity.sampler = e.target.value; } },
                        ['dpmpp_2m', 'dpmpp_2m_sde', 'dpmpp_2s_ancestral', 'dpmpp_3m_sde', 'dpmpp_sde', 'euler', 'euler_ancestral', 'heun', 'lms', 'ddim', 'ddpm', 'dpm_2', 'dpm_2_ancestral', 'dpm_adaptive', 'dpm_fast', 'uni_pc', 'uni_pc_bh2', 'ipndm', 'ipndm_v', 'lcm'].map(function (s) {
                            return m('option', { value: s }, s);
                        }))
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Scheduler'),
                    m('select', { class: 'text-field-compact', value: cinst.entity.scheduler || 'karras', onchange: function (e) { cinst.entity.scheduler = e.target.value; } },
                        ['normal', 'karras', 'exponential', 'sgm_uniform', 'simple', 'ddim_uniform', 'beta', 'linear_quadratic', 'kl_optimal'].map(function (s) {
                            return m('option', { value: s }, s);
                        }))
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Sampler'),
                    m('select', { class: 'text-field-compact', value: cinst.entity.refinerSampler || 'dpmpp_2m', onchange: function (e) { cinst.entity.refinerSampler = e.target.value; } },
                        ['dpmpp_2m', 'dpmpp_2m_sde', 'dpmpp_2s_ancestral', 'dpmpp_3m_sde', 'dpmpp_sde', 'euler', 'euler_ancestral', 'heun', 'lms', 'ddim', 'ddpm', 'dpm_2', 'dpm_2_ancestral', 'dpm_adaptive', 'dpm_fast', 'uni_pc', 'uni_pc_bh2', 'ipndm', 'ipndm_v', 'lcm'].map(function (s) {
                            return m('option', { value: s }, s);
                        }))
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Scheduler'),
                    m('select', { class: 'text-field-compact', value: cinst.entity.refinerScheduler || 'karras', onchange: function (e) { cinst.entity.refinerScheduler = e.target.value; } },
                        ['normal', 'karras', 'exponential', 'sgm_uniform', 'simple', 'ddim_uniform', 'beta', 'linear_quadratic', 'kl_optimal'].map(function (s) {
                            return m('option', { value: s }, s);
                        }))
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Seed'),
                    m('div', { style: 'display:flex;gap:4px;' }, [
                        m('input', {
                            class: 'text-field-compact', style: 'flex:1;', type: 'number', value: seed,
                            oninput: function (e) { seed = e.target.value; cinst.api.seed(parseInt(e.target.value) || -1); }
                        }),
                        m('button', { class: 'button', title: 'Random seed', onclick: function () { seed = '-1'; cinst.api.seed(-1); } },
                            m('span', { class: 'material-symbols-outlined md-18' }, 'casino'))
                    ])
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Image Count'),
                    m('input', {
                        class: 'text-field-compact', type: 'number', value: imageCount,
                        oninput: function (e) { imageCount = e.target.value; }
                    })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Hi-Res'),
                    m('input', {
                        type: 'checkbox', checked: cinst.api.hires ? cinst.api.hires() : false,
                        onchange: function (e) { if (cinst.api.hires) cinst.api.hires(e.target.checked); }
                    })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Save Shared'),
                    m('input', {
                        type: 'checkbox', checked: cinst.entity.shared || false,
                        onchange: function (e) { cinst.entity.shared = e.target.checked; }
                    })
                ])
            ]),

            // LORAs
            m('div', { class: 'mt-2' }, [
                m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-1' }, 'LORAs'),
                (function () {
                    let currentLoras = cinst.entity.loras || [];
                    if (!Array.isArray(currentLoras)) currentLoras = [];
                    let loraMap = {};
                    currentLoras.forEach(function (entry) {
                        let parts = String(entry).split(':');
                        loraMap[parts[0]] = parts.length > 1 ? parseFloat(parts[1]) || 0.8 : 0.8;
                    });
                    function sync() {
                        cinst.entity.loras = Object.keys(loraMap).map(function (k) { return k + ':' + loraMap[k]; });
                    }
                    let available = am7sd.getLoraList();
                    if (!available.length) { am7sd.fetchLoras().then(function () { m.redraw(); }); }
                    return m('div', { class: 'space-y-1' }, [
                        available.map(function (name) {
                            let selected = loraMap.hasOwnProperty(name);
                            return m('div', { key: name, class: 'flex items-center gap-2 text-xs' }, [
                                m('input', { type: 'checkbox', checked: selected, onchange: function () {
                                    if (selected) delete loraMap[name]; else loraMap[name] = 0.8;
                                    sync(); m.redraw();
                                } }),
                                m('span', { class: 'truncate', style: 'max-width:180px', title: name }, name),
                                selected ? m('input', { type: 'number', class: 'text-field-compact text-xs w-16', min: 0, max: 2, step: 0.05, value: loraMap[name], oninput: function (e) { loraMap[name] = parseFloat(e.target.value) || 0.8; sync(); } }) : null
                            ]);
                        }),
                        m('input', { type: 'text', class: 'text-field-compact text-xs mt-1 w-full', placeholder: 'loraName:weight + Enter', onkeydown: function (e) {
                            if (e.key === 'Enter' && e.target.value.trim()) {
                                let parts = e.target.value.trim().split(':');
                                loraMap[parts[0]] = parts.length > 1 ? parseFloat(parts[1]) || 0.8 : 0.8;
                                e.target.value = ''; sync(); m.redraw();
                            }
                        } })
                    ]);
                })()
            ])
        ]);
    }

    Dialog.open({
        title: 'Reimage ' + inst.api.name(),
        size: 'lg',
        content: { view: renderContent },
        actions: [
            {
                label: 'Cancel', icon: 'cancel',
                onclick: function () { Dialog.close(); }
            },
            {
                label: 'Generate', icon: 'image', primary: true,
                onclick: async function () {
                    let count = parseInt(imageCount) || 1;
                    if (count < 1) count = 1;
                    let baseSeed = cinst.api.seed();
                    let images = [];
                    let wearLevel = isCharPerson ? getCurrentWearLevel(inst) : null;

                    // Save config BEFORE closing dialog so values persist
                    let saveEntity = Object.assign({}, cinst.entity);
                    saveEntity.shared = false;
                    await am7sd.saveConfig(charConfigName, saveEntity);
                    if (cinst.entity.shared) {
                        await am7sd.saveConfig('sharedSD.json', cinst.entity);
                        cinst.entity.shared = false;
                    }

                    Dialog.close();

                    // Clear prior toast messages before starting generation
                    page.clearToast();

                    // Call narrate before reimage for charPerson — updates narrative
                    // from NarrativeUtil with current outfit, action, setting
                    if (isCharPerson && am7model.forms && am7model.forms.commands && am7model.forms.commands.narrate) {
                        page.toast('info', 'Updating narrative...', -1);
                        await am7model.forms.commands.narrate(undefined, inst);
                    }

                    for (let i = 0; i < count; i++) {
                        page.toast('info', 'Creating image ' + (i + 1) + ' of ' + count + '...', -1);
                        let imgEntity = Object.assign({}, cinst.entity);
                        imgEntity.seed = baseSeed;

                        try {
                            let x = await m.request({
                                method: 'POST',
                                url: am7client.base() + '/olio/' + inst.model.name + '/' + inst.api.objectId() + '/reimage',
                                body: imgEntity,
                                withCredentials: true
                            });

                            if (!x) {
                                page.toast('error', 'Failed to create image ' + (i + 1));
                                break;
                            }

                            await tagImage(x, inst, isCharPerson, wearLevel, cinst);
                            images.push(x);

                            // First image: update portrait and extract seed
                            if (i === 0) {
                                if (isCharPerson && inst.entity.profile) {
                                    inst.entity.profile.portrait = x;
                                    let od = { id: inst.entity.profile.id, portrait: { id: x.id } };
                                    od[am7model.jsonModelKey] = 'identity.profile';
                                    await page.patchObject(od);
                                }
                                // Seed may not be in the reimage response — fetch full image if needed
                                let seedAttr = (x.attributes || []).filter(function (a) { return a.name === 'seed'; });
                                if (!seedAttr.length && x.objectId) {
                                    try {
                                        let fullImg = await am7client.getFull('data.data', x.objectId);
                                        if (fullImg && fullImg.attributes) {
                                            seedAttr = fullImg.attributes.filter(function (a) { return a.name === 'seed'; });
                                        }
                                    } catch(e) { /* ignore */ }
                                }
                                if (seedAttr.length) {
                                    baseSeed = seedAttr[0].value;
                                    await am7client.patchAttribute(inst.entity, 'preferredSeed', baseSeed);
                                }
                            }
                        } catch (e) {
                            let errMsg = e.message || (typeof e === 'object' ? JSON.stringify(e) : String(e));
                            let status = (e && e.code) || 0;
                            if (status === 404 || (errMsg && errMsg.indexOf('404') > -1)) {
                                console.warn('Reimage: not accessible (404)');
                                page.toast('info', 'Cannot generate images — not accessible');
                            } else {
                                console.warn('Reimage error:', errMsg);
                                page.toast('error', 'Reimage error: ' + errMsg);
                            }
                            break;
                        }
                    }

                    page.clearToast();

                    if (images.length > 0) {
                        page.toast('success', 'Created ' + images.length + ' image(s)');

                        page.clearContextObject(inst.api.objectId());
                        images.forEach(function (img) { if (img.objectId) page.clearContextObject(img.objectId); });
                        if (isCharPerson) openGalleryDialog(inst);
                        m.redraw();
                    } else {
                        page.toast('error', 'Reimage failed');
                    }
                }
            }
        ]
    });
}

// --- Reference image picker ---

async function selectReferenceImage(inst, cinst, isCharPerson) {
    let galleryImages = [];
    if (isCharPerson) {
        let profile = inst.entity.profile;
        let portrait = profile ? profile.portrait : null;
        let profileObjectId = profile ? profile.objectId : null;
        if (profileObjectId && (!portrait || !portrait.groupId)) {
            let pq = am7view.viewQuery('identity.profile');
            pq.field('objectId', profileObjectId);
            pq.entity.request.push('portrait');
            let pqr = await page.search(pq);
            if (pqr && pqr.results && pqr.results.length) {
                portrait = pqr.results[0].portrait;
            }
        }
        if (portrait && portrait.groupId) {
            let q = am7client.newQuery('data.data');
            q.field('groupId', portrait.groupId);
            q.entity.request.push('id', 'objectId', 'name', 'groupId', 'groupPath', 'contentType');
            q.range(0, 100);
            q.sort('createdDate');
            q.order('descending');
            let qr = await page.search(q);
            if (qr && qr.results) {
                galleryImages = qr.results.filter(function (r) { return r.contentType && r.contentType.match(/^image\//i); });
            }
        }
    } else if (inst.entity.groupId) {
        let q = am7client.newQuery('data.data');
        q.field('groupId', inst.entity.groupId);
        q.entity.request.push('id', 'objectId', 'name', 'groupId', 'groupPath', 'contentType');
        q.range(0, 100);
        q.sort('createdDate');
        q.order('descending');
        let qr = await page.search(q);
        if (qr && qr.results) {
            galleryImages = qr.results.filter(function (r) { return r.contentType && r.contentType.match(/^image\//i); });
        }
    }
    if (!galleryImages.length) {
        page.toast('warn', 'No gallery images found');
        return;
    }

    let orgPath = am7client.dotPath(am7client.currentOrganization);
    Dialog.open({
        title: 'Select Reference Image',
        size: 'lg',
        content: {
            view: function () {
                return m('div', { style: 'padding: 12px; display: flex; flex-wrap: wrap; gap: 8px; max-height: 400px; overflow-y: auto;' },
                    galleryImages.map(function (img) {
                        let src = am7client.base().replace('/rest', '') + '/thumbnail/' + orgPath + '/data.data' + img.groupPath + '/' + img.name + '/96x96';
                        let selected = cinst.entity.referenceImageId === img.objectId;
                        return m('img', {
                            src: src, width: 96, height: 96,
                            style: 'object-fit: cover; border-radius: 4px; cursor: pointer; border: 3px solid ' + (selected ? '#2196F3' : 'transparent') + ';',
                            title: img.name,
                            onclick: function () {
                                cinst.entity.referenceImageId = img.objectId;
                                if (!cinst.entity.denoisingStrength || cinst.entity.denoisingStrength === 0.75) {
                                    cinst.entity.denoisingStrength = 0.6;
                                }
                                Dialog.close();
                                page.toast('info', 'Reference image selected: ' + img.name);
                            }
                        });
                    })
                );
            }
        },
        actions: [
            { label: 'Cancel', icon: 'cancel', onclick: function () { Dialog.close(); } }
        ]
    });
}

// --- Apparel sequence generator ---

async function createApparelSequence(inst, cinst, am7olio) {
    page.clearToast();
    if (!am7olio) { page.toast('error', 'Olio module not loaded'); return; }

    let storeRef = inst.api.store();
    if (!storeRef || !storeRef.objectId) {
        page.toast('error', 'No store found for character');
        return;
    }
    await am7client.clearCache('olio.store');
    let sto = await am7client.getFull('olio.store', storeRef.objectId);
    if (!sto || !sto.apparel || !sto.apparel.length) {
        page.toast('error', 'No apparel found in store');
        return;
    }
    let activeAp = sto.apparel.find(function (a) { return a.inuse; }) || sto.apparel[0];

    await am7client.clearCache('olio.apparel');
    let app = await am7client.getFull('olio.apparel', activeAp.objectId);
    if (!app || !app.wearables || !app.wearables.length) {
        page.toast('error', 'No wearables found in apparel');
        return;
    }
    let wears = app.wearables;

    // Sorted unique wear levels
    let wearLevels = am7model.enums.wearLevelEnumType || [];
    let lvls = [];
    let seen = {};
    wears.sort(function (a, b) {
        let aL = wearLevels.indexOf(a.level ? a.level.toUpperCase() : 'UNKNOWN');
        let bL = wearLevels.indexOf(b.level ? b.level.toUpperCase() : 'UNKNOWN');
        return aL - bL;
    }).forEach(function (w) {
        let lvl = w.level ? w.level.toUpperCase() : 'UNKNOWN';
        if (!seen[lvl]) { seen[lvl] = true; lvls.push(lvl); }
    });

    if (!lvls.length) {
        page.toast('error', 'No wear levels found');
        return;
    }

    // Dress down completely
    page.toast('info', 'Dressing down completely...', -1);
    let dressedDown = true;
    while (dressedDown === true) {
        dressedDown = await am7olio.dressApparel(activeAp, false);
    }
    // Update server narrative
    await am7model.forms.commands.narrate(undefined, inst);

    let baseSeed = cinst.api.seed();
    let images = [];

    // Check if lowest level is not NUDE
    let nudeIdx = wearLevels.indexOf('NUDE');
    let lowestIdx = wearLevels.indexOf(lvls[0]);
    let includeNude = (lowestIdx > nudeIdx);
    let totalImages = lvls.length + (includeNude ? 1 : 0);

    // Nude image first if needed
    if (includeNude) {
        page.toast('info', 'Creating image 1 of ' + totalImages + ' (level: NUDE)...', -1);
        let imgEntity = Object.assign({}, cinst.entity);
        imgEntity.seed = baseSeed;
        try {
            let x = await m.request({
                method: 'POST',
                url: am7client.base() + '/olio/' + inst.model.name + '/' + inst.api.objectId() + '/reimage',
                body: imgEntity, withCredentials: true
            });
            if (x) {
                let seedAttr = (x.attributes || []).filter(function (a) { return a.name === 'seed'; });
                if (seedAttr.length) {
                    baseSeed = seedAttr[0].value;
                    await am7client.patchAttribute(inst.entity, 'preferredSeed', baseSeed);
                }
                await am7client.patchAttribute(x, 'wearLevel', 'NUDE/' + nudeIdx);
                await applySharingTag(x, 'NUDE');
                await am7client.applyImageTags(x.objectId);
                let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
                if (nameTag) await am7client.member('data.tag', nameTag.objectId, null, 'data.data', x.objectId, true);
                let imageNameTag = await getOrCreateSharingTag(inst.api.name(), 'data.data');
                if (imageNameTag) await am7client.member('data.tag', imageNameTag.objectId, null, 'data.data', x.objectId, true);
                images.push(x);
            }
        } catch (e) {
            page.toast('error', 'Failed nude image: ' + (e.message || e));
        }
    }

    // Iterate through each level
    for (let i = 0; i < lvls.length; i++) {
        await am7olio.dressApparel(activeAp, true);
        await am7model.forms.commands.narrate(undefined, inst);

        let useSeed = (images.length === 0) ? baseSeed : (parseInt(baseSeed) + images.length);
        cinst.api.seed(useSeed);

        page.toast('info', 'Creating image ' + (images.length + 1) + ' of ' + totalImages + ' (level: ' + lvls[i] + ')...', -1);
        let imgEntity = Object.assign({}, cinst.entity);
        imgEntity.seed = useSeed;

        try {
            let x = await m.request({
                method: 'POST',
                url: am7client.base() + '/olio/' + inst.model.name + '/' + inst.api.objectId() + '/reimage',
                body: imgEntity, withCredentials: true
            });

            if (!x) {
                page.toast('error', 'Failed to create image at level ' + lvls[i]);
                break;
            }

            if (images.length === 0) {
                let seedAttr = (x.attributes || []).filter(function (a) { return a.name === 'seed'; });
                if (seedAttr.length) {
                    baseSeed = seedAttr[0].value;
                    await am7client.patchAttribute(inst.entity, 'preferredSeed', baseSeed);
                }
            }

            let levelIndex = wearLevels.indexOf(lvls[i]);
            await am7client.patchAttribute(x, 'wearLevel', lvls[i] + '/' + levelIndex);
            await applySharingTag(x, lvls[i]);
            await am7client.applyImageTags(x.objectId);
            let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
            if (nameTag) await am7client.member('data.tag', nameTag.objectId, null, 'data.data', x.objectId, true);
            let imageNameTag = await getOrCreateSharingTag(inst.api.name(), 'data.data');
            if (imageNameTag) await am7client.member('data.tag', imageNameTag.objectId, null, 'data.data', x.objectId, true);
            if (cinst.entity.style === 'selfie') {
                let selfieTag = await getOrCreateSharingTag('selfie', 'data.data');
                if (selfieTag) await am7client.member('data.tag', selfieTag.objectId, null, 'data.data', x.objectId, true);
            }
            images.push(x);
        } catch (e) {
            page.toast('error', 'Sequence error at level ' + lvls[i] + ': ' + (e.message || e));
            break;
        }
    }

    // Restore seed
    cinst.api.seed(baseSeed);

    // Save config
    if (images.length > 0) {
        let charConfigName = inst.api.name() + '-SD.json';
        let saveEntity = Object.assign({}, cinst.entity);
        saveEntity.shared = false;
        await am7sd.saveConfig(charConfigName, saveEntity);
        if (cinst.entity.shared) {
            await am7sd.saveConfig('sharedSD.json', cinst.entity);
            cinst.entity.shared = false;
        }
    }

    page.clearToast();
    page.toast('success', 'Created ' + images.length + ' images in apparel sequence');
    page.clearContextObject(inst.api.objectId());
    images.forEach(function (img) { if (img.objectId) page.clearContextObject(img.objectId); });
    openGalleryDialog(inst);
    m.redraw();
}

export { reimage, getOrCreateSharingTag, applySharingTag, socialSharingMap };
export default reimage;
