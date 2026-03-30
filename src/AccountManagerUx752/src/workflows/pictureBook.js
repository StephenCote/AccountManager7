import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import {
    MAX_SCENES_DEFAULT, DEFAULT_SD_CONFIG,
    extractScenes, fullExtract, generateSceneImage,
    regenerateBlurb, loadPictureBook, resetPictureBook,
    resolveImageUrl, resolveAllImageUrls
} from './sceneExtractor.js';
import { ObjectPicker } from '../components/picker.js';

/**
 * Picture Book workflow — multi-step wizard launched from a data.data or data.note object.
 * Steps:
 *   1 — Source & Method (auto vs manual, chat config, scene count, genre)
 *   2 — Scene Preview (auto path: review/edit extracted scenes)
 *   3 — Character Review (list characters, confirm charPerson creation)
 *   4 — Image Generation (generate per-scene images)
 *   5 — Picture Book View (gallery with reorder + blurb edit)
 */

// ── Wizard state ──────────────────────────────────────────────────────

let step = 1;
let workObjectId = null;
let workName = '';

// Step 1
let method = 'auto';
let bookName = '';
let chatConfigName = null;
let sceneCount = MAX_SCENES_DEFAULT;
let genre = '';

// Step 2
let extractedScenes = [];
let extracting = false;
let extractError = null;

// Step 3
let characters = [];
let charProgress = {};  // name → 'pending'|'creating'|'done'|'error'
let creatingChars = false;

// Step 4
let scenes = [];  // from meta or Step 2
let generating = false;
let genProgress = {};  // objectId → 'pending'|'generating'|'done'|'error'
let genCancelled = false;

// Step 5
let metaScenes = [];
let step5ImageUrls = {};  // imageObjectId → resolved media URL

function resetState() {
    step = 1;
    method = 'auto';
    bookName = '';
    chatConfigName = null;
    sceneCount = MAX_SCENES_DEFAULT;
    genre = '';
    extractedScenes = [];
    extracting = false;
    extractError = null;
    characters = [];
    charProgress = {};
    creatingChars = false;
    scenes = [];
    generating = false;
    genProgress = {};
    genCancelled = false;
    metaScenes = [];
    step5ImageUrls = {};
}

// ── Step helpers ──────────────────────────────────────────────────────

// chatConfig is now selected via ObjectPicker — no preload needed

function collectCharacters() {
    let seen = {};
    for (let s of extractedScenes) {
        if (!Array.isArray(s.characters)) continue;
        for (let c of s.characters) {
            let name = typeof c === 'string' ? c : (c.name || '');
            if (name && !seen[name]) {
                seen[name] = true;
                characters.push({ name, role: typeof c === 'object' ? (c.role || '') : '' });
            }
        }
    }
}

async function doFullExtract() {
    extracting = true;
    extractError = null;
    m.redraw();
    try {
        let meta = await fullExtract(workObjectId, chatConfigName, sceneCount, genre || null, bookName || workName);
        metaScenes = meta.scenes || [];
        scenes = metaScenes;
        extractedScenes = metaScenes.map(s => ({
            title: s.title || '',
            summary: '',
            setting: '',
            action: '',
            mood: '',
            characters: (s.characters || []).map(id => ({ name: id })),
            objectId: s.objectId
        }));
        collectCharacters();
        step = 4;  // Skip to image generation (backend already created chars)
    } catch (e) {
        extractError = e.message || 'Extraction failed';
    }
    extracting = false;
    m.redraw();
}

async function doExtractScenesOnly() {
    extracting = true;
    extractError = null;
    m.redraw();
    try {
        extractedScenes = await extractScenes(workObjectId, chatConfigName, sceneCount);
        if (!extractedScenes || !extractedScenes.length) {
            extractError = 'No scenes returned by LLM';
        } else {
            collectCharacters();
            step = 2;
        }
    } catch (e) {
        extractError = e.message || 'Extraction failed';
    }
    extracting = false;
    m.redraw();
}

async function doGenerateAll() {
    generating = true;
    genCancelled = false;
    m.redraw();
    let targets = scenes.length ? scenes : extractedScenes;
    for (let s of targets) {
        if (genCancelled) break;
        let oid = s.objectId;
        if (!oid) continue;
        genProgress[oid] = 'generating';
        m.redraw();
        try {
            let result = await generateSceneImage(oid, DEFAULT_SD_CONFIG, chatConfigName, null);
            s.imageObjectId = result.imageObjectId;
            genProgress[oid] = 'done';
        } catch (e) {
            genProgress[oid] = 'error';
        }
        m.redraw();
    }
    generating = false;
    m.redraw();
}

async function doGenerateOne(s) {
    let oid = s.objectId;
    if (!oid) return;
    genProgress[oid] = 'generating';
    m.redraw();
    try {
        let result = await generateSceneImage(oid, DEFAULT_SD_CONFIG, chatConfigName, null);
        s.imageObjectId = result.imageObjectId;
        genProgress[oid] = 'done';
    } catch (e) {
        genProgress[oid] = 'error';
        page.toast('error', 'Image generation failed');
    }
    m.redraw();
}

// ── Render functions ──────────────────────────────────────────────────

function renderStep1() {
    return m('div', { class: 'p-4 space-y-4' }, [
        m('div', { class: 'text-sm text-gray-600 dark:text-gray-400 mb-2' }, 'Source: ' + workName),

        // Picture book name
        m('div', [
            m('label', { class: 'field-label' }, 'Picture Book Name'),
            m('input', {
                class: 'text-field-full text-sm',
                placeholder: workName || 'My Picture Book',
                value: bookName,
                oninput: function (e) { bookName = e.target.value; }
            })
        ]),

        // Method toggle
        m('div', { class: 'flex gap-4 mb-3' }, [
            m('label', { class: 'flex items-center gap-2 cursor-pointer' }, [
                m('input', {
                    type: 'radio', name: 'method', value: 'auto',
                    checked: method === 'auto',
                    onchange: function () { method = 'auto'; }
                }),
                m('span', { class: 'text-sm' }, 'Auto-extract from text')
            ]),
            m('label', { class: 'flex items-center gap-2 cursor-pointer' }, [
                m('input', {
                    type: 'radio', name: 'method', value: 'manual',
                    checked: method === 'manual',
                    onchange: function () { method = 'manual'; }
                }),
                m('span', { class: 'text-sm' }, 'Enter scenes manually')
            ])
        ]),

        method === 'auto' ? m('div', { class: 'space-y-3' }, [
            m('div', [
                m('label', { class: 'field-label' }, 'Chat Config'),
                m('div', {
                    class: 'text-field-full text-sm cursor-pointer flex items-center justify-between',
                    onclick: function () {
                        ObjectPicker.openLibrary({
                            libraryType: 'chatConfig',
                            title: 'Select Chat Config',
                            onSelect: function (item) {
                                if (item && item.name) {
                                    chatConfigName = item.name;
                                    m.redraw();
                                }
                            }
                        });
                    }
                }, [
                    m('span', { class: chatConfigName ? '' : 'text-gray-400' }, chatConfigName || '(click to select)'),
                    m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                ])
            ]),
            m('div', { class: 'grid grid-cols-2 gap-3' }, [
                m('div', [
                    m('label', { class: 'field-label' }, 'Scene Count (3–12)'),
                    m('input', {
                        type: 'number', class: 'text-field-compact', min: 1, max: 12,
                        value: sceneCount,
                        oninput: function (e) {
                            let v = parseInt(e.target.value) || MAX_SCENES_DEFAULT;
                            sceneCount = Math.min(12, Math.max(1, v));
                        }
                    })
                ]),
                m('div', [
                    m('label', { class: 'field-label' }, 'Genre Hint'),
                    m('select', {
                        class: 'text-field-compact',
                        value: genre,
                        onchange: function (e) { genre = e.target.value; }
                    }, [
                        m('option', { value: '' }, 'None'),
                        m('option', { value: 'fantasy' }, 'Fantasy'),
                        m('option', { value: 'sci-fi' }, 'Sci-Fi'),
                        m('option', { value: 'contemporary' }, 'Contemporary'),
                        m('option', { value: 'historical' }, 'Historical')
                    ])
                ])
            ]),
            extractError ? m('div', { class: 'text-red-500 text-sm' }, extractError) : null
        ]) : m('div', { class: 'text-sm text-gray-500 italic' }, 'Manual scene entry — proceed to Step 3.')
    ]);
}

function renderStep2() {
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Extracted Scenes (' + extractedScenes.length + ')'),
            m('button', {
                class: 'btn text-sm',
                disabled: extracting,
                onclick: function () { doExtractScenesOnly(); }
            }, extracting ? 'Re-extracting...' : 'Re-extract')
        ]),
        extracting ? m('div', { class: 'text-sm text-gray-500' }, 'Extracting scenes...') :
        m('div', { class: 'space-y-2 max-h-80 overflow-y-auto' },
            extractedScenes.map(function (s, i) {
                return m('div', { key: i, class: 'border dark:border-gray-700 rounded p-3 text-sm space-y-1' }, [
                    m('div', { class: 'flex gap-2 items-start' }, [
                        m('span', { class: 'text-gray-400 text-xs mt-0.5 w-4' }, String(i + 1) + '.'),
                        m('input', {
                            class: 'text-field-full text-sm font-medium',
                            value: s.title || '',
                            placeholder: 'Scene title',
                            oninput: function (e) { extractedScenes[i].title = e.target.value; }
                        })
                    ]),
                    m('textarea', {
                        class: 'w-full text-field-full text-xs', rows: 2,
                        value: s.summary || s.description || '',
                        placeholder: 'Scene summary',
                        oninput: function (e) { extractedScenes[i].summary = e.target.value; }
                    }),
                    m('div', { class: 'text-gray-500 text-xs' }, 'Characters: ' +
                        (Array.isArray(s.characters) ? s.characters.map(c => (typeof c === 'string' ? c : c.name)).join(', ') : '—'))
                ]);
            })
        )
    ]);
}

function renderStep3() {
    return m('div', { class: 'p-4 space-y-3' }, [
        m('h3', { class: 'font-medium mb-2' }, 'Characters (' + characters.length + ')'),
        characters.length === 0
            ? m('div', { class: 'text-sm text-gray-500 italic' }, 'No characters found. Proceed to generate images.')
            : m('div', { class: 'space-y-1 max-h-64 overflow-y-auto' },
                characters.map(function (c) {
                    let status = charProgress[c.name] || 'pending';
                    return m('div', {
                        key: c.name,
                        class: 'flex items-center justify-between border dark:border-gray-700 rounded px-3 py-2 text-sm'
                    }, [
                        m('div', [
                            m('span', { class: 'font-medium' }, c.name),
                            c.role ? m('span', { class: 'text-gray-500 ml-2 text-xs' }, c.role) : null
                        ]),
                        m('span', {
                            class: 'text-xs px-2 py-0.5 rounded ' + (
                                status === 'done' ? 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300' :
                                status === 'creating' ? 'bg-blue-100 text-blue-600' :
                                status === 'error' ? 'bg-red-100 text-red-600' :
                                'bg-gray-100 text-gray-500'
                            )
                        }, status)
                    ]);
                })
            ),
        creatingChars ? m('div', { class: 'text-sm text-blue-500' }, 'Creating characters...') : null
    ]);
}

function renderStep4() {
    let targets = scenes.length ? scenes : extractedScenes;
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Image Generation'),
            m('div', { class: 'flex gap-2' }, [
                generating
                    ? m('button', {
                        class: 'btn text-sm',
                        onclick: function () { genCancelled = true; }
                    }, 'Cancel')
                    : m('button', {
                        class: 'btn btn-primary text-sm',
                        disabled: targets.length === 0,
                        onclick: doGenerateAll
                    }, 'Generate All (' + targets.filter(s => s.objectId).length + ')')
            ])
        ]),
        m('div', { class: 'space-y-2 max-h-80 overflow-y-auto' },
            targets.map(function (s) {
                let oid = s.objectId;
                let status = oid ? (genProgress[oid] || (s.imageObjectId ? 'done' : 'pending')) : 'no-id';
                return m('div', {
                    key: oid || s.title,
                    class: 'flex items-center gap-3 border dark:border-gray-700 rounded p-3'
                }, [
                    m('div', { class: 'flex-1' }, [
                        m('div', { class: 'font-medium text-sm' }, s.title || 'Untitled scene'),
                        status === 'error' ? m('div', { class: 'text-red-500 text-xs' }, 'Generation failed') : null
                    ]),
                    s.imageObjectId && status === 'done'
                        ? m('span', { class: 'material-symbols-outlined text-green-500' }, 'check_circle')
                        : status === 'generating'
                            ? m('span', { class: 'text-xs text-blue-500' }, 'Generating...')
                            : oid ? m('button', {
                                class: 'btn text-xs',
                                disabled: generating,
                                onclick: function () { doGenerateOne(s); }
                            }, s.imageObjectId ? 'Regenerate' : 'Generate')
                            : m('span', { class: 'text-xs text-gray-400' }, 'Not committed')
                ]);
            })
        )
    ]);
}

function renderStep5() {
    let targets = metaScenes.length ? metaScenes : (scenes.length ? scenes : extractedScenes);
    return m('div', { class: 'p-4 space-y-3' }, [
        m('h3', { class: 'font-medium mb-2' }, 'Picture Book — ' + workName),
        m('div', { class: 'text-sm text-gray-500 mb-3' },
            targets.length + ' scene' + (targets.length !== 1 ? 's' : '') + ' generated.'),
        m('div', { class: 'grid grid-cols-2 gap-4 max-h-96 overflow-y-auto' },
            targets.map(function (s) {
                let imgUrl = s.imageObjectId ? step5ImageUrls[s.imageObjectId] : null;
                return m('div', {
                    key: s.objectId || s.title,
                    class: 'border dark:border-gray-700 rounded overflow-hidden'
                }, [
                    imgUrl
                        ? m('img', {
                            src: imgUrl,
                            class: 'w-full object-cover',
                            style: 'max-height:160px'
                        })
                        : m('div', { class: 'w-full bg-gray-100 dark:bg-gray-800 flex items-center justify-center', style: 'height:160px' },
                            m('span', { class: 'material-symbols-outlined text-gray-400 text-4xl' }, 'image')
                        ),
                    m('div', { class: 'p-2' }, [
                        m('div', { class: 'font-medium text-sm mb-1' }, s.title || 'Untitled'),
                        m('div', { class: 'text-xs text-gray-500' }, s.description || s.summary || '')
                    ])
                ]);
            })
        )
    ]);
}

function renderStepContent() {
    if (step === 1) return renderStep1();
    if (step === 2) return renderStep2();
    if (step === 3) return renderStep3();
    if (step === 4) return renderStep4();
    if (step === 5) return renderStep5();
    return null;
}

function renderProgressBar() {
    let labels = ['Source', 'Scenes', 'Characters', 'Images', 'View'];
    return m('div', { class: 'flex items-center gap-1 px-4 pt-3 pb-1' },
        labels.map(function (label, i) {
            let n = i + 1;
            let active = step === n;
            let done = step > n;
            return m('div', { key: n, class: 'flex items-center gap-1' }, [
                m('div', {
                    class: 'w-6 h-6 rounded-full text-xs flex items-center justify-center font-medium ' + (
                        done ? 'bg-green-500 text-white' :
                        active ? 'bg-blue-500 text-white' :
                        'bg-gray-200 dark:bg-gray-700 text-gray-500'
                    )
                }, done ? m('span', { class: 'material-symbols-outlined text-sm' }, 'check') : String(n)),
                m('span', { class: 'text-xs ' + (active ? 'text-blue-500 font-medium' : 'text-gray-400') }, label),
                n < 5 ? m('span', { class: 'text-gray-300 dark:text-gray-600 mx-1' }, '›') : null
            ]);
        })
    );
}

// ── Action builders ───────────────────────────────────────────────────

function buildActions() {
    let actions = [];

    // Back
    if (step > 1) {
        actions.push({
            label: 'Back', icon: 'arrow_back',
            onclick: function () { step--; m.redraw(); }
        });
    }

    // Cancel
    actions.push({
        label: 'Cancel', icon: 'cancel',
        onclick: function () { Dialog.close(); }
    });

    // Step-specific primary actions
    if (step === 1) {
        if (method === 'auto') {
            actions.push({
                label: extracting ? 'Extracting...' : 'Extract Scenes',
                icon: 'auto_awesome',
                primary: true,
                disabled: extracting,
                onclick: doExtractScenesOnly
            });
            actions.push({
                label: extracting ? 'Extracting...' : 'Extract Everything',
                icon: 'done_all',
                disabled: extracting,
                onclick: doFullExtract
            });
        } else {
            actions.push({
                label: 'Continue', icon: 'arrow_forward', primary: true,
                onclick: function () { step = 3; m.redraw(); }
            });
        }
    } else if (step === 2) {
        actions.push({
            label: 'Continue', icon: 'arrow_forward', primary: true,
            onclick: function () {
                characters = [];
                collectCharacters();
                step = 3;
                m.redraw();
            }
        });
    } else if (step === 3) {
        actions.push({
            label: 'Continue to Images', icon: 'arrow_forward', primary: true,
            onclick: function () { step = 4; m.redraw(); }
        });
    } else if (step === 4) {
        actions.push({
            label: 'View Picture Book', icon: 'auto_stories', primary: true,
            onclick: async function () {
                try {
                    metaScenes = await loadPictureBook(workObjectId);
                } catch (e) {
                    metaScenes = scenes;
                }
                let targets = metaScenes.length ? metaScenes : scenes;
                step5ImageUrls = await resolveAllImageUrls(targets);
                step = 5;
                m.redraw();
            }
        });
    } else if (step === 5) {
        actions.push({
            label: 'Open in Viewer', icon: 'open_in_new',
            onclick: function () {
                Dialog.close();
                m.route.set('/picture-book/' + workObjectId);
            }
        });
        actions.push({
            label: 'Done', icon: 'check', primary: true,
            onclick: function () { Dialog.close(); }
        });
    }

    return actions;
}

// ── Entry point ───────────────────────────────────────────────────────

async function pictureBook(entity, inst) {
    if (!inst) {
        page.toast('error', 'No instance provided');
        return;
    }

    resetState();
    workObjectId = inst.api.objectId ? inst.api.objectId() : (entity ? entity.objectId : null);
    workName = inst.api.name ? inst.api.name() : (entity ? entity.name : 'Untitled');
    bookName = workName; // default to source name, user can edit

    if (!workObjectId) {
        page.toast('error', 'Cannot open Picture Book: no objectId');
        return;
    }

    Dialog.open({
        title: 'Picture Book — ' + workName,
        size: 'xl',
        closable: false,
        content: {
            view: function () {
                return m('div', [
                    renderProgressBar(),
                    renderStepContent()
                ]);
            }
        },
        actions: { view: function () { return buildActions(); } }
    });
}

/**
 * Simplified entry point — opens the wizard with just an objectId and name.
 * Used by the viewer empty state when no inst/entity is available.
 */
async function pictureBookFromId(objectId, name) {
    console.log('[PictureBook] pictureBookFromId called: objectId=' + objectId + ' name=' + name);
    if (!objectId) {
        page.toast('error', 'No document selected');
        return;
    }
    let fakeInst = {
        api: {
            objectId: function () { return objectId; },
            name: function () { return name || 'Untitled'; }
        }
    };
    await pictureBook(null, fakeInst);
}

export { pictureBook, pictureBookFromId };
export default pictureBook;
