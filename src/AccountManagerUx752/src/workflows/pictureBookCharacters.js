import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { renderRange } from '../components/formFieldRenderers.js';
import { listCharacters, tagApparelSceneIndex, resolveImageUrl } from './sceneExtractor.js';
import { reimage } from './reimage.js';
import { outfitBuilder } from './outfitBuilder.js';

/**
 * PictureBook "Manage Characters" workflow — review/edit extracted charPerson records for a book:
 * customize/complete statistics, generate/tag apparel per scene, regenerate a portrait.
 * Launched from the PictureBook wizard (step 4/5) via openCharacterManager(bookObjectId).
 *
 * Reuses existing workflows wholesale rather than re-implementing them:
 *  - reimage(entity, inst)       — portrait regeneration (same call the generic charPerson
 *                                  "Reimage" command uses, see formDef.js forms.charPerson.commands).
 *  - outfitBuilder(entity, inst) — apparel wizard (same call the generic charPerson
 *                                  "Outfit Builder" command uses).
 * The only genuinely new UI here is the character list, the statistics range-slider panel
 * (via formFieldRenderers.renderRange's plain-contract entry point), and the apparel scene-tag
 * list wired to PictureBookUtil.tagApparelSceneIndex via the new REST endpoint.
 */

const STAT_FIELDS = [
    'physicalStrength', 'physicalEndurance', 'manualDexterity', 'agility', 'speed',
    'mentalStrength', 'mentalEndurance', 'intelligence', 'wisdom', 'perception',
    'creativity', 'spirituality', 'charisma', 'luck'
];

let bookObjectId = null;
let characters = [];
let selectedObjectId = null;
let selectedInst = null;
let loading = false;
let sceneTagInputs = {}; // apparelObjectId -> string (pending scene index input)

function resetState() {
    bookObjectId = null;
    characters = [];
    selectedObjectId = null;
    selectedInst = null;
    loading = false;
    sceneTagInputs = {};
}

async function refreshList() {
    characters = await listCharacters(bookObjectId);
    m.redraw();
}

async function selectCharacter(objectId) {
    selectedObjectId = objectId;
    selectedInst = null;
    m.redraw();
    let entity = await am7client.getFull('olio.charPerson', objectId);
    if (!entity) {
        page.toast('error', 'Failed to load character');
        return;
    }
    selectedInst = am7model.prepareInstance(entity, am7model.forms.charPerson);
    m.redraw();
}

async function patchStatField(statistics, field, value) {
    await page.patchObject({
        schema: 'olio.statistics',
        id: statistics.id,
        objectId: statistics.objectId,
        [field]: value
    });
}

async function doReimage() {
    if (!selectedInst) return;
    await reimage(selectedInst.entity, selectedInst);
    // reimage's own Dialog mutates selectedInst.entity.profile.portrait directly on success —
    // just refresh the list so the "hasPortrait" badge picks it up too.
    await refreshList();
    m.redraw();
}

async function doOutfitBuilder() {
    if (!selectedInst) return;
    await outfitBuilder(selectedInst.entity, selectedInst);
    // Re-fetch the character so the apparel list picks up the newly-generated outfit.
    await selectCharacter(selectedObjectId);
    await refreshList();
}

/**
 * Open the full generic charPerson editor for a character — gives access to every field
 * (including anything this screen doesn't expose a dedicated panel for, e.g. a manually-edited
 * narrative.sdPrompt/outfitDescription) without duplicating the generic editor here. Closing this
 * Dialog first matches the convention used elsewhere for leaving a Dialog to navigate
 * (see pictureBook.js's "Open in Viewer" action).
 */
function openFullEditor(entity) {
    Dialog.close();
    m.route.set('/view/' + entity[am7model.jsonModelKey] + '/' + entity.objectId, { key: entity.objectId });
}

async function tagApparel(apparelObjectId) {
    let raw = sceneTagInputs[apparelObjectId];
    let sceneIndex = parseInt(raw, 10);
    if (isNaN(sceneIndex) || sceneIndex < 0) {
        page.toast('error', 'Enter a valid scene number (0 or higher)');
        return;
    }
    try {
        await tagApparelSceneIndex(selectedObjectId, apparelObjectId, sceneIndex);
        page.toast('success', 'Apparel tagged for scene ' + sceneIndex);
        await selectCharacter(selectedObjectId);
        await refreshList();
    } catch (e) {
        page.toast('error', 'Failed to tag apparel: ' + (e.message || e));
    }
}

function renderCharacterListItem(c) {
    let isSelected = c.objectId === selectedObjectId;
    let badges = [];
    if (!c.hasPortrait) badges.push(m('span', { class: 'text-xs text-amber-600 dark:text-amber-400' }, 'no portrait'));
    if (!c.apparelCount) badges.push(m('span', { class: 'text-xs text-amber-600 dark:text-amber-400' }, 'no apparel'));
    if (c.failedApparel) badges.push(m('span', { class: 'text-xs text-red-600 dark:text-red-400' }, 'apparel failed'));
    if (c.failedStatistics) badges.push(m('span', { class: 'text-xs text-red-600 dark:text-red-400' }, 'stats failed'));
    return m('div', {
        class: 'px-3 py-2 rounded cursor-pointer border ' +
            (isSelected ? 'border-blue-500 bg-blue-50 dark:bg-blue-950' : 'border-transparent hover:bg-gray-100 dark:hover:bg-gray-800'),
        onclick: function () { selectCharacter(c.objectId); }
    }, [
        m('div', { class: 'font-medium text-sm' }, c.name || '(unnamed)'),
        m('div', { class: 'flex gap-2 mt-1' }, badges)
    ]);
}

function renderStatisticsPanel() {
    let statistics = selectedInst.entity.statistics;
    if (!statistics) return m('div', { class: 'text-sm text-gray-500' }, 'No statistics record.');
    return m('div', { class: 'grid grid-cols-2 gap-2' }, STAT_FIELDS.map(function (field) {
        let value = statistics[field] != null ? statistics[field] : 0;
        return m('div', { class: 'flex flex-col' }, [
            m('label', { class: 'text-xs text-gray-500' }, field + ': ' + value),
            renderRange({
                value: value,
                min: 0, max: 20, step: 1,
                name: field,
                onInput: function (e) {
                    let v = parseInt(e.target.value, 10);
                    statistics[field] = v;
                    m.redraw();
                    patchStatField(statistics, field, v).catch(function (err) {
                        page.toast('error', 'Failed to save ' + field + ': ' + (err.message || err));
                    });
                }
            })
        ]);
    }));
}

function renderApparelPanel() {
    let store = selectedInst.entity.store;
    let apparelList = (store && store.apparel) ? store.apparel : [];
    if (!apparelList.length) {
        return m('div', { class: 'text-sm text-gray-500' }, 'No apparel yet.');
    }
    return m('div', { class: 'flex flex-col gap-2' }, apparelList.map(function (a) {
        let sceneIndex = null;
        if (a.attributes) {
            let attr = a.attributes.find(function (x) { return x.name === 'sceneIndex'; });
            if (attr) sceneIndex = attr.value;
        }
        return m('div', { class: 'flex items-center gap-2 p-2 border border-gray-200 dark:border-gray-700 rounded' }, [
            m('div', { class: 'flex-1 text-sm' }, [
                m('div', a.name || '(apparel)'),
                m('div', { class: 'text-xs text-gray-500' },
                    (a.inuse ? 'in use' : 'not in use') + (sceneIndex != null ? ' — scene ' + sceneIndex : ' — untagged'))
            ]),
            m('input', {
                type: 'number', min: '0', placeholder: 'scene #',
                class: 'text-field-compact w-20',
                value: sceneTagInputs[a.objectId] || '',
                oninput: function (e) { sceneTagInputs[a.objectId] = e.target.value; }
            }),
            m('button', { class: 'button', onclick: function () { tagApparel(a.objectId); } }, 'Tag')
        ]);
    }));
}

function renderPortraitPanel() {
    let profile = selectedInst.entity.profile;
    return m('div', { class: 'flex flex-col gap-2' }, [
        profile && profile.portrait
            ? m(PortraitImage, { objectId: profile.portrait.objectId })
            : m('div', { class: 'text-sm text-gray-500' }, 'No portrait yet.'),
        m('button', { class: 'button primary', onclick: doReimage }, 'Regenerate Portrait')
    ]);
}

// Small component that resolves+caches its own image URL — avoids re-triggering a fetch on
// every parent redraw (resolveImageUrl already memoizes by objectId internally).
const PortraitImage = {
    url: null,
    oninit: function (vnode) {
        resolveImageUrl(vnode.attrs.objectId).then(function (u) {
            vnode.state.url = u;
            m.redraw();
        });
    },
    view: function (vnode) {
        return vnode.state.url
            ? m('img', { src: vnode.state.url, class: 'max-w-[200px] max-h-[200px] rounded' })
            : m('div', { class: 'text-sm text-gray-500' }, 'Loading portrait…');
    }
};

function renderDetail() {
    if (!selectedObjectId) {
        return m('div', { class: 'text-sm text-gray-500 p-4' }, 'Select a character from the list.');
    }
    if (!selectedInst) {
        return m('div', { class: 'text-sm text-gray-500 p-4' }, 'Loading…');
    }
    return m('div', { class: 'flex flex-col gap-4 p-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('div', [
                m('div', { class: 'text-lg font-semibold' }, selectedInst.entity.name),
                m('div', { class: 'text-sm text-gray-500' }, 'Gender: ' + (selectedInst.entity.gender || 'UNKNOWN'))
            ]),
            m('a', {
                href: '#', class: 'text-sm text-blue-600 dark:text-blue-400 hover:underline',
                onclick: function (e) { e.preventDefault(); openFullEditor(selectedInst.entity); }
            }, 'Open Full Editor →')
        ]),
        m('div', [
            m('div', { class: 'font-medium mb-1' }, 'Portrait'),
            renderPortraitPanel()
        ]),
        m('div', [
            m('div', { class: 'font-medium mb-1' }, 'Statistics'),
            renderStatisticsPanel()
        ]),
        m('div', [
            m('div', { class: 'flex items-center justify-between mb-1' }, [
                m('div', { class: 'font-medium' }, 'Apparel'),
                m('button', { class: 'button', onclick: doOutfitBuilder }, 'Generate New Outfit')
            ]),
            renderApparelPanel()
        ])
    ]);
}

function renderContent() {
    if (loading) return m('div', { class: 'p-4 text-sm text-gray-500' }, 'Loading characters…');
    return m('div', { class: 'flex gap-4', style: 'min-height: 400px;' }, [
        m('div', { class: 'w-56 flex flex-col gap-1 border-r border-gray-200 dark:border-gray-700 pr-2 overflow-y-auto' },
            characters.length ? characters.map(renderCharacterListItem) : m('div', { class: 'text-sm text-gray-500 p-2' }, 'No characters extracted yet.')),
        m('div', { class: 'flex-1 overflow-y-auto' }, renderDetail())
    ]);
}

/**
 * Open the Manage Characters dialog for a book.
 * @param {string} theBookObjectId - book group objectId
 */
async function openCharacterManager(theBookObjectId) {
    resetState();
    bookObjectId = theBookObjectId;
    loading = true;
    Dialog.open({
        title: 'Manage Characters',
        size: 'xl',
        content: { view: renderContent },
        actions: [
            { label: 'Close', icon: 'close', primary: true, onclick: function () { Dialog.close(); } }
        ]
    });
    await refreshList();
    loading = false;
    m.redraw();
}

export { openCharacterManager };
export default openCharacterManager;
