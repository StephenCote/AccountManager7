import m from 'mithril';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * Outfit Builder workflow — opens outfit builder panel for a character or apparel.
 * Delegates to am7olio.OutfitBuilderPanel and PieceEditorPanel components.
 */

async function outfitBuilder(entity, inst) {
    if (!inst) {
        page.toast('error', 'No instance provided');
        return;
    }

    let am7olio = am7model._olio;
    let modelName = inst.model ? inst.model.name : null;
    let characterId = null;
    let gender = 'female';
    let currentApparel = null;

    if (modelName === 'olio.charPerson') {
        characterId = inst.entity.objectId || inst.entity.id;
        gender = inst.entity.gender || 'female';
        if (inst.entity.store && inst.entity.store.apparel && inst.entity.store.apparel.length > 0) {
            currentApparel = inst.entity.store.apparel.find(a => a.inuse) || inst.entity.store.apparel[0];
        }
    } else if (modelName === 'olio.apparel') {
        if (inst.entity.designer) {
            characterId = inst.entity.designer.objectId || inst.entity.designer.id;
            gender = inst.entity.designer.gender || 'female';
        }
        currentApparel = inst.entity;
    }

    if (am7olio && am7olio.outfitBuilderState) {
        am7olio.outfitBuilderState.characterId = characterId;
        am7olio.outfitBuilderState.gender = gender;
        am7olio.outfitBuilderState.currentApparel = currentApparel;
    }

    function renderContent() {
        if (!am7olio || !am7olio.OutfitBuilderPanel) {
            return m('div', { class: 'p-4 text-gray-500' }, 'Outfit builder component not loaded');
        }
        let builderApparel = am7olio.outfitBuilderState ? am7olio.outfitBuilderState.currentApparel : null;
        return m('div', { class: 'flex gap-4 p-4' }, [
            m('div', { class: 'flex-1' }, [
                m(am7olio.OutfitBuilderPanel, {
                    characterId: characterId,
                    gender: gender,
                    onGenerate: async function (apparel) {
                        if (apparel && am7olio.outfitBuilderState) {
                            am7olio.outfitBuilderState.currentApparel = apparel;
                            m.redraw();
                        }
                    }
                })
            ]),
            builderApparel && builderApparel.wearables ? m('div', { class: 'flex-1' }, [
                m(am7olio.PieceEditorPanel, { apparel: builderApparel })
            ]) : null
        ]);
    }

    Dialog.open({
        title: 'Outfit Builder' + (inst.entity.name ? ' — ' + inst.entity.name : ''),
        size: 'xl',
        content: { view: renderContent },
        actions: [
            {
                label: 'Cancel', icon: 'cancel',
                onclick: function () { Dialog.close(); }
            },
            {
                label: 'Done', icon: 'check', primary: true,
                onclick: function () {
                    Dialog.close();
                    page.clearContextObject(inst.api.objectId());
                    m.redraw();
                }
            }
        ]
    });
}

export { outfitBuilder };
export default outfitBuilder;
