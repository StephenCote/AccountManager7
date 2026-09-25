import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import { renderRange } from '../components/formFieldRenderers.js';
import { listCharacters, tagApparelSceneIndex, mergeCharacters, deleteCharacter, resolveImageUrl } from './sceneExtractor.js';
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
// Merge mode: which duplicates are ticked to fold into the currently-selected character.
let mergeMode = false;
let mergeSelection = {};   // objectId -> true
let merging = false;
let deleting = false;

function resetState() {
    bookObjectId = null;
    characters = [];
    selectedObjectId = null;
    selectedInst = null;
    loading = false;
    sceneTagInputs = {};
    mergeMode = false;
    mergeSelection = {};
    merging = false;
    deleting = false;
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
 * Fold the ticked duplicates into the selected character.
 *
 * The selected character is the KEEPER — "use just the first version" — so this screen never asks
 * which one survives in the abstract: you pick the good one, then tick the duplicates.
 *
 * Confirmed before firing, because it deletes records and rewrites every scene in the book. The
 * confirmation names the characters rather than saying "these items": a wrong merge moves scenes
 * onto the wrong person and is not undoable from here.
 */
async function doMergeSelected() {
    if (!selectedObjectId || merging) return;
    let dropIds = Object.keys(mergeSelection).filter(function (k) { return mergeSelection[k]; });
    if (!dropIds.length) {
        page.toast('error', 'Tick at least one duplicate to merge');
        return;
    }
    let keepName = (characters.find(function (c) { return c.objectId === selectedObjectId; }) || {}).name
        || selectedObjectId;
    let dropNames = dropIds.map(function (id) {
        let c = characters.find(function (x) { return x.objectId === id; });
        return (c && c.name) || id;
    });
    let ok = window.confirm('Merge ' + dropNames.join(', ') + ' into "' + keepName + '"?\n\n'
        + 'Every scene that references ' + (dropNames.length > 1 ? 'them' : 'it')
        + ' will be repointed to "' + keepName + '", and '
        + (dropNames.length > 1 ? 'those characters' : 'that character')
        + ' will be deleted along with their portrait, statistics and wardrobe. This cannot be undone.');
    if (!ok) return;

    merging = true;
    m.redraw();
    try {
        let result = await mergeCharacters(bookObjectId, selectedObjectId, dropIds);
        // Report what the server says actually moved — a merge that repointed no scenes is worth
        // seeing, not hiding behind a generic "done".
        let msg = 'Merged ' + (result.mergedNames || dropNames).join(', ') + ' into "'
            + (result.keptName || keepName) + '" (' + (result.scenesRepointed || 0) + ' scene'
            + ((result.scenesRepointed === 1) ? '' : 's') + ' repointed)';
        if (result.failedDeletes && result.failedDeletes.length) {
            page.toast('error', msg + ' — but could not delete: ' + result.failedDeletes.join(', '));
        }
        else {
            page.toast('success', msg);
        }
        mergeMode = false;
        mergeSelection = {};
        await refreshList();
    } catch (e) {
        page.toast('error', 'Merge failed: ' + (e.message || e));
    }
    merging = false;
    m.redraw();
}

/**
 * Delete the selected character outright and detach it from every scene.
 *
 * Extraction sometimes surfaces an animal, an expression, or a turn of phrase as a "character".
 * Those are not duplicates of anyone, so merge is the wrong tool — there is nothing to fold them
 * into. Delete removes the record and drops it from every scene, meta entry and pipeline binding
 * that referenced it, so a later render does not try to paint a portrait for "a sigh".
 */
async function doDeleteSelected() {
    if (!selectedObjectId || deleting || merging) return;
    let name = (characters.find(function (c) { return c.objectId === selectedObjectId; }) || {}).name
        || (selectedInst && selectedInst.entity && selectedInst.entity.name)
        || selectedObjectId;
    let ok = window.confirm('Delete "' + name + '"?\n\n'
        + 'It will be removed from every scene that references it, and deleted along with its '
        + 'portrait, statistics and wardrobe. This cannot be undone.');
    if (!ok) return;

    deleting = true;
    m.redraw();
    try {
        let result = await deleteCharacter(bookObjectId, selectedObjectId);
        let n = (result && result.scenesDetached) || 0;
        let msg = 'Deleted "' + ((result && result.deletedName) || name) + '" (detached from '
            + n + ' scene' + (n === 1 ? '' : 's') + ')';
        if (result && result.deleted === false) {
            page.toast('error', msg + ' — but the character record itself could not be deleted');
        }
        else {
            page.toast('success', msg);
        }
        delete mergeSelection[selectedObjectId];
        selectedObjectId = null;
        selectedInst = null;
        await refreshList();
    } catch (e) {
        page.toast('error', 'Delete failed: ' + (e.message || e));
    }
    deleting = false;
    m.redraw();
}

function toggleMergeMode() {
    mergeMode = !mergeMode;
    mergeSelection = {};
    m.redraw();
}

/**
 * Open a record's full generic editor in a new tab rather than navigating the current one: this
 * screen renders inside the PictureBook wizard's Dialog (either inline at Step 3, or as the Steps
 * 4/5 stacked popup), and there's no route-based back nav that would restore the in-progress
 * wizard state (extracted scenes, created book/characters, etc.) if we navigated away in place.
 * A new tab leaves the wizard exactly as it is.
 */
function openInNewTab(type, objectId) {
    let url = window.location.origin + window.location.pathname + '#!/view/' + type + '/' + objectId;
    window.open(url, '_blank');
}

/**
 * Open the full generic charPerson editor for a character — gives access to every field
 * (including anything this screen doesn't expose a dedicated panel for, e.g. a manually-edited
 * narrative.sdPrompt/outfitDescription) without duplicating the generic editor here.
 */
function openFullEditor(entity) {
    openInNewTab(entity[am7model.jsonModelKey], entity.objectId);
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

    // In merge mode the KEEPER is whichever character is selected, so it gets no checkbox — you
    // cannot merge someone into themselves, and offering the box would only invite the attempt.
    let checkbox = (mergeMode && !isSelected)
        ? m('input', {
            type: 'checkbox',
            class: 'mr-2',
            checked: !!mergeSelection[c.objectId],
            onclick: function (e) { e.stopPropagation(); },
            onchange: function (e) {
                mergeSelection[c.objectId] = e.target.checked;
                m.redraw();
            }
        })
        : null;

    return m('div', {
        class: 'px-3 py-2 rounded cursor-pointer border ' +
            (isSelected ? 'border-blue-500 bg-blue-50 dark:bg-blue-950' : 'border-transparent hover:bg-gray-100 dark:hover:bg-gray-800'),
        onclick: function () { if (!mergeMode) selectCharacter(c.objectId); }
    }, [
        m('div', { class: 'flex items-center' }, [
            checkbox,
            m('div', { class: 'font-medium text-sm' }, c.name || '(unnamed)'),
            (mergeMode && isSelected)
                ? m('span', { class: 'ml-2 text-xs text-blue-600 dark:text-blue-400' }, '(keep)')
                : null
        ]),
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
            m('a', {
                href: '#', class: 'text-xs text-blue-600 dark:text-blue-400 hover:underline whitespace-nowrap',
                // Apparel list items may not carry their own "schema" field (list serialization
                // only guarantees it on the first entry) — the model is always olio.apparel here,
                // so use it directly rather than am7model.jsonModelKey off the item.
                onclick: function (e) { e.preventDefault(); openInNewTab('olio.apparel', a.objectId); }
            }, 'Open →'),
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
            m('div', { class: 'flex items-center gap-3' }, [
                m('a', {
                    href: '#', class: 'text-sm text-blue-600 dark:text-blue-400 hover:underline',
                    onclick: function (e) { e.preventDefault(); openFullEditor(selectedInst.entity); }
                }, 'Open Full Editor →'),
                m('button', {
                    class: 'px-3 py-1 rounded text-xs bg-red-600 text-white hover:bg-red-500 disabled:opacity-50',
                    title: 'Delete this character and detach it from every scene. Use for extractions that are not really characters (animals, expressions).',
                    disabled: deleting || merging,
                    onclick: doDeleteSelected
                }, deleting ? 'Deleting…' : 'Delete character')
            ])
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

// Merge toolbar. Sits above the character list because merging is a LIST operation — it is about
// the relationship between two rows, not about the selected character's own fields.
function renderMergeToolbar() {
    if (!characters.length) return null;
    let selectedCount = Object.keys(mergeSelection).filter(function (k) { return mergeSelection[k]; }).length;
    if (!mergeMode) {
        return m('div', { class: 'flex items-center justify-between pb-2 mb-1 border-b border-gray-200 dark:border-gray-700' }, [
            m('span', { class: 'text-xs text-gray-500 uppercase tracking-wide' }, 'Characters'),
            m('a', {
                href: '#', class: 'text-xs text-blue-600 dark:text-blue-400 hover:underline',
                title: 'Fold duplicate extractions of the same person into one character. Extraction can name an unnamed character differently in different parts of a long work.',
                onclick: function (e) { e.preventDefault(); toggleMergeMode(); }
            }, 'Merge duplicates')
        ]);
    }
    return m('div', { class: 'flex flex-col gap-1 pb-2 mb-1 border-b border-gray-200 dark:border-gray-700' }, [
        m('div', { class: 'text-xs text-gray-600 dark:text-gray-300' },
            selectedObjectId
                ? 'Tick the duplicates to fold into the selected character.'
                : 'Select the character to KEEP first, then tick its duplicates.'),
        m('div', { class: 'flex gap-2' }, [
            m('button', {
                class: 'button primary text-xs',
                disabled: merging || !selectedObjectId || !selectedCount,
                onclick: doMergeSelected
            }, merging ? 'Merging…' : ('Merge ' + selectedCount + ' →')),
            m('button', { class: 'button text-xs', disabled: merging, onclick: toggleMergeMode }, 'Cancel')
        ])
    ]);
}

function renderContent() {
    if (loading) return m('div', { class: 'p-4 text-sm text-gray-500' }, 'Loading characters…');
    return m('div', { class: 'flex gap-4', style: 'min-height: 400px;' }, [
        m('div', { class: 'w-56 flex flex-col border-r border-gray-200 dark:border-gray-700 pr-2 overflow-y-auto' }, [
            renderMergeToolbar(),
            m('div', { class: 'flex flex-col gap-1' },
                characters.length
                    ? characters.map(renderCharacterListItem)
                    : m('div', { class: 'text-sm text-gray-500 p-2' }, 'No characters extracted yet.'))
        ]),
        m('div', { class: 'flex-1 overflow-y-auto' }, renderDetail())
    ]);
}

/**
 * Initialize character-manager state for a book WITHOUT opening a Dialog — used by the PictureBook
 * wizard's Step 3, which renders renderCharacterManagerContent() inline inside its own already-open
 * Dialog. Fires the initial character-list fetch.
 * @param {string} theBookObjectId - book group objectId
 */
async function initCharacterManager(theBookObjectId) {
    resetState();
    bookObjectId = theBookObjectId;
    loading = true;
    m.redraw();
    await refreshList();
    loading = false;
    m.redraw();
}

/** Dialog-agnostic list+detail view — usable inline (wizard step 3) or inside a Dialog (popup). */
function renderCharacterManagerContent() {
    return renderContent();
}

/**
 * Open Manage Characters as its own stacked Dialog — used by the wizard's steps 4/5 "Manage
 * Characters" button (a quick one-off tweak while generating/viewing, on top of the wizard's own
 * open Dialog), independent of Step 3's inline rendering of the exact same content.
 * @param {string} theBookObjectId - book group objectId
 */
async function openCharacterManager(theBookObjectId) {
    Dialog.open({
        title: 'Manage Characters',
        size: 'xl',
        content: { view: renderCharacterManagerContent },
        actions: [
            { label: 'Close', icon: 'close', primary: true, onclick: function () { Dialog.close(); } }
        ]
    });
    await initCharacterManager(theBookObjectId);
}

export { openCharacterManager, initCharacterManager, renderCharacterManagerContent };
export default openCharacterManager;
