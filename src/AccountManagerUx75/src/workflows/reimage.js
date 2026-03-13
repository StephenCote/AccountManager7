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
    // Search for existing tag
    let q = am7client.newQuery('data.tag');
    q.field('name', tagName);
    q.field('type', objectType);
    q.range(0, 1);
    let qr = await page.search(q);
    if (qr && qr.results && qr.results.length) return qr.results[0];

    // Create tag
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
    await am7client.member('data.tag', tag.objectId, 'data.data', 'data.data', image.objectId, true);
}

function getCurrentWearLevel(inst) {
    let store = inst.entity.store;
    if (!store || !store.apparel) return null;
    let activeApp = store.apparel.find(a => a.inuse) || store.apparel[0];
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

let lastReimage = null;

async function reimage(entity, inst) {
    let am7olio = am7model._olio;
    let isCharPerson = inst.model.name === 'olio.charPerson';

    let sdEntity = await am7sd.fetchTemplate(true);
    if (!sdEntity) {
        sdEntity = am7model.newPrimitive('olio.sdConfig');
    }
    let cinst = lastReimage || am7model.prepareInstance(sdEntity, am7model.forms.sdConfig);

    // Quality defaults (lower for testing)
    cinst.api.steps(20);
    cinst.api.refinerSteps(20);
    cinst.api.cfg(5);
    cinst.api.refinerCfg(5);
    cinst.entity.style = 'photograph';
    if (cinst.api.hires) cinst.api.hires(false);

    // Load character-specific config
    let charConfigName = inst.api.name() + '-SD.json';
    let charConfig = await am7sd.loadConfig(charConfigName);
    if (charConfig) am7sd.applyConfig(cinst, charConfig);

    lastReimage = cinst;

    if (isCharPerson && am7olio) {
        await am7olio.setNarDescription(inst, cinst);
    } else if (inst.api.objectId()) {
        cinst.entity.referenceImageId = inst.api.objectId();
    }

    // Apply preferred seed from attributes
    let cseed = (inst.entity.attributes || []).filter(a => a.name === 'preferredSeed');
    if (cseed.length) cinst.api.seed(cseed[0].value);

    // Build form content using object view for SD config
    let imageCount = cinst.api.imageCount ? String(cinst.api.imageCount()) : '1';
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
                    m('label', { class: 'field-label' }, 'CFG Scale'),
                    m('input', { class: 'text-field-compact', type: 'number', value: cinst.api.cfg(), oninput: function (e) { cinst.api.cfg(parseFloat(e.target.value) || 5); } })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Refiner CFG'),
                    m('input', { class: 'text-field-compact', type: 'number', value: cinst.api.refinerCfg(), oninput: function (e) { cinst.api.refinerCfg(parseFloat(e.target.value) || 5); } })
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
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Image Count'),
                    m('input', { class: 'text-field-compact', type: 'number', value: imageCount, oninput: function (e) { imageCount = e.target.value; } })
                ])
            ]),
            isCharPerson ? m('div', { class: 'flex gap-2 mt-2' }, [
                m('button', { class: 'button', onclick: async function () { if (am7olio) { await am7olio.dressCharacter(inst, false); await am7olio.setNarDescription(inst, cinst); m.redraw(); } } }, [
                    m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'arrow_downward'), 'Dress Down'
                ]),
                m('button', { class: 'button', onclick: async function () { if (am7olio) { await am7olio.dressCharacter(inst, true); await am7olio.setNarDescription(inst, cinst); m.redraw(); } } }, [
                    m('span', { class: 'material-symbols-outlined md-18 mr-1' }, 'arrow_upward'), 'Dress Up'
                ])
            ]) : '',
            cinst.entity.narDescription ? m('div', { class: 'mt-2 p-2 bg-gray-50 dark:bg-gray-800 rounded text-sm max-h-32 overflow-y-auto' }, cinst.entity.narDescription) : ''
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

                    Dialog.close();

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

                            // Tag with wear level and sharing context
                            if (wearLevel) {
                                await am7client.patchAttribute(x, 'wearLevel', wearLevel.level + '/' + wearLevel.index);
                                await applySharingTag(x, wearLevel.level);
                            }

                            await am7client.applyImageTags(x.objectId);

                            if (isCharPerson) {
                                let nameTag = await getOrCreateSharingTag(inst.api.name(), inst.model.name);
                                if (nameTag) await am7client.member('data.tag', nameTag.objectId, 'data.data', 'data.data', x.objectId, true);
                                let imageNameTag = await getOrCreateSharingTag(inst.api.name(), 'data.data');
                                if (imageNameTag) await am7client.member('data.tag', imageNameTag.objectId, 'data.data', 'data.data', x.objectId, true);
                            }

                            if (cinst.entity.style === 'selfie') {
                                let selfieTag = await getOrCreateSharingTag('selfie', 'data.data');
                                if (selfieTag) await am7client.member('data.tag', selfieTag.objectId, 'data.data', 'data.data', x.objectId, true);
                            }

                            images.push(x);

                            // First image: update portrait and extract seed
                            if (i === 0) {
                                if (isCharPerson && inst.entity.profile) {
                                    inst.entity.profile.portrait = x;
                                    let od = { id: inst.entity.profile.id, portrait: { id: x.id } };
                                    od[am7model.jsonModelKey] = 'identity.profile';
                                    await page.patchObject(od);
                                }
                                let seedAttr = (x.attributes || []).filter(a => a.name === 'seed');
                                if (seedAttr.length) {
                                    await am7client.patchAttribute(inst.entity, 'preferredSeed', seedAttr[0].value);
                                }
                            }
                        } catch (e) {
                            page.toast('error', 'Reimage error: ' + (e.message || e));
                            break;
                        }
                    }

                    page.clearToast();

                    if (images.length > 0) {
                        page.toast('success', 'Created ' + images.length + ' image(s)');
                        // Save SD config
                        am7sd.saveConfig(charConfigName, Object.assign({}, cinst.entity, { shared: false }));
                        if (cinst.api.shared && cinst.api.shared()) {
                            am7sd.saveConfig('sharedSD.json', cinst.entity);
                            cinst.api.shared(false);
                        }
                        page.clearContextObject(inst.api.objectId());
                        images.forEach(function (img) { if (img.objectId) page.clearContextObject(img.objectId); });
                        m.redraw();
                    } else {
                        page.toast('error', 'Reimage failed');
                    }
                }
            }
        ]
    });
}

export { reimage, getOrCreateSharingTag, applySharingTag, socialSharingMap };
export default reimage;
