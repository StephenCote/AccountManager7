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
                    m('label', { class: 'field-label' }, 'Steps'),
                    m('input', { class: 'text-field-compact', type: 'number', value: cinst.api.steps(), oninput: function (e) { cinst.api.steps(parseInt(e.target.value) || 20); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Steps'),
                    m('input', { class: 'text-field-compact', type: 'number', value: cinst.api.refinerSteps(), oninput: function (e) { cinst.api.refinerSteps(parseInt(e.target.value) || 20); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Model'),
                    m('input', { class: 'text-field-compact', value: cinst.api.model ? cinst.api.model() : '', oninput: function (e) { if (cinst.api.model) cinst.api.model(e.target.value); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner Model'),
                    m('input', { class: 'text-field-compact', value: cinst.api.refinerModel ? cinst.api.refinerModel() : '', oninput: function (e) { if (cinst.api.refinerModel) cinst.api.refinerModel(e.target.value); } })
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
                                        await am7client.member('data.tag', selfieTag.objectId, 'data.data', 'data.data', img.objectId, true);
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
