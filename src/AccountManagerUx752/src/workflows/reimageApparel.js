import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { getOrCreateSharingTag } from './reimage.js';

/**
 * Reimage Apparel workflow — generates mannequin images for apparel items.
 * Calls POST /olio/apparel/{id}/reimage
 */

async function reimageApparel(entity, inst) {
    if (!inst || inst.model.name !== 'olio.apparel') {
        page.toast('error', 'Not an apparel record');
        return;
    }

    let am7sd = am7model._sd;
    let sdModelList = await am7sd.fetchModels();
    let sdEntity = await am7sd.fetchTemplate(true);
    if (!sdEntity) {
        sdEntity = am7model.newPrimitive('olio.sdConfig');
    }
    let cinst = am7model.prepareInstance(sdEntity, am7model.forms.sdMannequinConfig || am7model.forms.sdConfig);

    // Quality defaults (lower for testing)
    cinst.api.steps(20);
    cinst.api.refinerSteps(20);
    cinst.entity.style = 'photograph';
    if (cinst.api.hires) cinst.api.hires(false);

    // Load shared apparel config
    let sharedConfig = await am7sd.loadConfig('sharedApparelSD.json');
    if (sharedConfig) am7sd.applyConfig(cinst, sharedConfig);

    let seed = cinst.api.seed ? String(cinst.api.seed()) : '-1';

    function renderContent() {
        return m('div', { class: 'p-4 space-y-3' }, [
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                m('div', [
                    m('label', { class: 'field-label' }, 'Steps: ' + cinst.api.steps()),
                    m('input', { class: 'w-full', type: 'range', min: 1, max: 150, value: cinst.api.steps(), oninput: function (e) { cinst.api.steps(parseInt(e.target.value) || 20); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Steps: ' + cinst.api.refinerSteps()),
                    m('input', { class: 'w-full', type: 'range', min: 0, max: 150, value: cinst.api.refinerSteps(), oninput: function (e) { cinst.api.refinerSteps(parseInt(e.target.value) || 20); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'CFG: ' + (cinst.api.cfg ? cinst.api.cfg() : 5)),
                    m('input', { class: 'w-full', type: 'range', min: 1, max: 30, step: 0.5,
                        value: cinst.api.cfg ? cinst.api.cfg() : 5,
                        oninput: function (e) { if (cinst.api.cfg) cinst.api.cfg(parseFloat(e.target.value) || 5); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Denoising: ' + (cinst.api.denoisingStrength ? cinst.api.denoisingStrength() : 0.75)),
                    m('input', { class: 'w-full', type: 'range', min: 0, max: 1, step: 0.05,
                        value: cinst.api.denoisingStrength ? cinst.api.denoisingStrength() : 0.75,
                        oninput: function (e) { if (cinst.api.denoisingStrength) cinst.api.denoisingStrength(parseFloat(e.target.value) || 0.75); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Model'),
                    sdModelList.length > 0
                        ? m('select', { class: 'text-field-compact', value: cinst.api.model ? cinst.api.model() : '', onchange: function (e) { if (cinst.api.model) cinst.api.model(e.target.value); } },
                            [m('option', { value: '' }, '-- Select --')].concat(sdModelList.map(function (ml) { return m('option', { value: ml }, ml); })))
                        : m('input', { class: 'text-field-compact', value: cinst.api.model ? cinst.api.model() : '', oninput: function (e) { if (cinst.api.model) cinst.api.model(e.target.value); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Model'),
                    sdModelList.length > 0
                        ? m('select', { class: 'text-field-compact', value: cinst.api.refinerModel ? cinst.api.refinerModel() : '', onchange: function (e) { if (cinst.api.refinerModel) cinst.api.refinerModel(e.target.value); } },
                            [m('option', { value: '' }, '-- Select --')].concat(sdModelList.map(function (ml) { return m('option', { value: ml }, ml); })))
                        : m('input', { class: 'text-field-compact', value: cinst.api.refinerModel ? cinst.api.refinerModel() : '', oninput: function (e) { if (cinst.api.refinerModel) cinst.api.refinerModel(e.target.value); } })
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
                    m('label', { class: 'field-label' }, 'Style'),
                    m('input', { class: 'text-field-compact', value: cinst.entity.style || '', oninput: function (e) { cinst.entity.style = e.target.value; } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Seed'),
                    m('div', { style: 'display:flex;gap:4px;' }, [
                        m('input', { class: 'text-field-compact', style: 'flex:1;', type: 'number', value: seed, oninput: function (e) { seed = e.target.value; cinst.api.seed(parseInt(e.target.value) || -1); } }),
                        m('button', { class: 'button', title: 'Random seed', onclick: function () { seed = '-1'; cinst.api.seed(-1); } },
                            m('span', { class: 'material-symbols-outlined md-18' }, 'casino'))
                    ])
                ])
            ]),
            m('div', { class: 'flex items-center gap-2 mt-2' }, [
                m('input', { type: 'checkbox', id: 'hires-cb', checked: cinst.api.hires ? cinst.api.hires() : false, onchange: function (e) { if (cinst.api.hires) cinst.api.hires(e.target.checked); } }),
                m('label', { for: 'hires-cb', class: 'text-sm' }, 'HiRes')
            ]),
            m('div', { class: 'flex items-center gap-2 mt-1' }, [
                m('input', { type: 'checkbox', id: 'shared-cb', checked: cinst.entity.shared || false, onchange: function (e) { cinst.entity.shared = e.target.checked; } }),
                m('label', { for: 'shared-cb', class: 'text-sm' }, 'Save as shared config')
            ])
        ]);
    }

    Dialog.open({
        title: 'Mannequin Images: ' + inst.api.name(),
        size: 'md',
        content: { view: renderContent },
        actions: [
            {
                label: 'Cancel', icon: 'cancel',
                onclick: function () { Dialog.close(); }
            },
            {
                label: 'Load Shared', icon: 'download',
                onclick: async function () {
                    let config = await am7sd.loadConfig('sharedApparelSD.json');
                    if (config) {
                        am7sd.applyConfig(cinst, config);
                        page.toast('success', 'Loaded shared apparel config');
                        m.redraw();
                    } else {
                        page.toast('warn', 'No shared apparel config found');
                    }
                }
            },
            {
                label: 'Generate', icon: 'image', primary: true,
                onclick: async function () {
                    let baseSeed = cinst.api.seed();
                    let hires = cinst.api.hires ? cinst.api.hires() : false;

                    Dialog.close();
                    page.toast('info', 'Creating mannequin images...', -1);

                    try {
                        let imgEntity = Object.assign({}, cinst.entity);
                        imgEntity.seed = baseSeed;
                        imgEntity.hires = hires;

                        let images = await m.request({
                            method: 'POST',
                            url: am7client.base() + '/olio/apparel/' + inst.api.objectId() + '/reimage',
                            body: imgEntity,
                            withCredentials: true
                        });

                        page.clearToast();

                        if (images && images.length > 0) {
                            page.toast('success', 'Created ' + images.length + ' mannequin image(s)');
                            page.clearContextObject(inst.api.objectId());
                            images.forEach(function (img) { if (img.objectId) page.clearContextObject(img.objectId); });

                            if (cinst.entity.style === 'selfie') {
                                let selfieTag = await getOrCreateSharingTag('selfie', 'data.data');
                                if (selfieTag) {
                                    for (let img of images) {
                                        await am7client.member('data.tag', selfieTag.objectId, null, 'data.data', img.objectId, true);
                                    }
                                }
                            }

                            if (cinst.entity.shared) {
                                am7sd.saveConfig('sharedApparelSD.json', cinst.entity);
                                cinst.entity.shared = false;
                            }

                            m.redraw();
                        } else {
                            page.toast('error', 'Mannequin imaging failed');
                        }
                    } catch (e) {
                        page.clearToast();
                        page.toast('error', 'Mannequin imaging failed: ' + (e.message || e));
                    }
                }
            }
        ]
    });
}

export { reimageApparel };
export default reimageApparel;
