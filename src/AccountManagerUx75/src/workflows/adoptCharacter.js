import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * Adopt Character workflow — moves a character into the Olio world population.
 * Calls POST /rest/game/adopt/{objectId}
 */

async function adoptCharacter(entity, inst) {
    if (!inst || !inst.entity) {
        page.toast('error', 'No character selected');
        return;
    }

    let character = inst.entity;
    let characterName = character.name || 'Unknown';

    function renderContent() {
        return m('div', { class: 'p-4' }, [
            m('p', { class: 'mb-4' },
                'This will move the character to the world\'s population and make them available for game interactions.'),
            m('div', { class: 'mb-4' }, [
                m('label', { class: 'block mb-2 font-medium' }, 'Target Realm:'),
                m('select', { class: 'select-field-full' }, [
                    m('option', { value: 'default' }, 'Default Realm')
                ])
            ]),
            m('div', { class: 'text-sm text-gray-600 dark:text-gray-400' }, [
                m('p', 'Current location: ' + (character.groupPath || 'Unknown')),
                m('p', 'Character will be added to the realm\'s population group.')
            ])
        ]);
    }

    Dialog.open({
        title: 'Adopt Character to World',
        size: 'md',
        content: { view: renderContent },
        actions: [
            {
                label: 'Cancel', icon: 'cancel',
                onclick: function () { Dialog.close(); }
            },
            {
                label: 'Adopt', icon: 'person_add', primary: true,
                onclick: async function () {
                    Dialog.close();
                    page.toast('info', 'Adopting ' + characterName + '...');
                    try {
                        let result = await m.request({
                            method: 'POST',
                            url: am7client.base() + '/game/adopt/' + (character.objectId || character.id),
                            withCredentials: true,
                            body: {}
                        });
                        if (result && result.adopted) {
                            page.toast('success', characterName + ' has been adopted into the world!');
                            page.clearContextObject(inst.api.objectId());
                            m.redraw();
                        } else {
                            page.toast('error', (result && result.error) || 'Failed to adopt character');
                        }
                    } catch (e) {
                        page.toast('error', 'Failed to adopt character: ' + (e.message || 'Unknown error'));
                    }
                }
            }
        ]
    });
}

export { adoptCharacter };
export default adoptCharacter;
