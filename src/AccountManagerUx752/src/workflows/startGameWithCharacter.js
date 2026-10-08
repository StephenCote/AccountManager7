import m from 'mithril';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { adoptCharacter } from './adoptCharacter.js';

/**
 * Start Game With Character workflow — the "Start Game" toolbar command on the charPerson form
 * (formDef.js forms.charPerson commands, function 'startGameWithCharacter').
 *
 * Ported from the Ux7 reference (deprecated/AccountManagerUx7/client/components/dialog.js:1805-1852):
 *   1. a character whose groupPath is not inside the Olio world (World Building / Worlds /
 *      Population) gets the "Character Not in World" prompt and, on confirm, the Adopt Character
 *      workflow instead;
 *   2. otherwise the character id is parked in sessionStorage under SELECTED_CHARACTER_KEY and the
 *      app routes to the card game with `character` as a route param.
 *
 * The route target is Ux752's "/cardGame" (features/cardGame.js) rather than Ux7's
 * "/app/game/cardGame-v2". KNOWN GAP, carried over from Ux7 and not invented here: the v2 card game
 * never consumed this hand-off — only the pre-v2 game (Ux7 view/bak/cardGame.js:3184) read the key —
 * and Ux752's CardGameApp builds decks from ~/CardGame/<deck>/Characters, not from the Olio
 * population. How the v2 game should claim a pre-selected character is a product decision; until
 * it is made, the key is written and the route is taken, exactly as Ux7 did.
 */

const SELECTED_CHARACTER_KEY = 'olio_selected_character';
const CARD_GAME_ROUTE = '/cardGame';
const WORLD_PATH = /World Building|Worlds|Population/;

/** Pure: is this character already part of the Olio world (by its group path)? */
function isInWorld(entity) {
    return WORLD_PATH.test((entity && entity.groupPath) || '');
}

async function startGameWithCharacter(entity, inst) {
    if (!inst || !inst.entity) {
        page.toast('error', 'No character selected');
        return false;
    }
    let character = inst.entity;
    let characterId = character.objectId || character.id;
    let characterName = character.name || 'Unknown';

    if (!isInWorld(character)) {
        let adopt = await Dialog.confirm({
            title: 'Character Not in World',
            message: 'This character is not part of the Olio world. Would you like to adopt them into the world first?',
            confirmLabel: 'Adopt Character',
            confirmIcon: 'person_add'
        });
        if (adopt) await adoptCharacter(entity, inst);
        return false;
    }

    page.toast('info', 'Starting game with ' + characterName);
    if (typeof sessionStorage !== 'undefined') sessionStorage.setItem(SELECTED_CHARACTER_KEY, characterId);
    m.route.set(CARD_GAME_ROUTE, { character: characterId });
    return true;
}

export { startGameWithCharacter, isInWorld, SELECTED_CHARACTER_KEY, CARD_GAME_ROUTE };
export default startGameWithCharacter;
