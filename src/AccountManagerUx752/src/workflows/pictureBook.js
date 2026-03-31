import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import {
    DEFAULT_SD_CONFIG,
    extractScenes, fullExtract, createFromScenes, generateSceneImage,
    regenerateBlurb, loadPictureBook, resetPictureBook,
    resolveImageUrl, resolveAllImageUrls
} from './sceneExtractor.js';
import { ObjectPicker } from '../components/picker.js';
import { LLMConnector } from '../chat/LLMConnector.js';

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
let workObjectId = null;  // Source document objectId (for extract API)
let bookObjectId = null;  // Book group objectId (for scenes/viewer/reset APIs)
let workName = '';

// Step 1
let method = 'auto';
let bookName = '';
let chatConfigName = null;
let genre = '';
let promptMode = 'single';  // 'single' | 'per-prompt'
let promptTemplate = null;   // single mode — applies to all
let promptTemplates = {      // per-prompt mode
    extractScenes: null,
    extractChunk: null,
    extractCharacter: null,
    sceneBlurb: null,
    landscapePrompt: null
};

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
let genProgress = {};  // objectId → 'pending'|'generating'|'done'|'error'|'accepted'|'skipped'
let genCancelled = false;
let sceneErrors = {};   // objectId → error message
let sceneOverrides = {}; // objectId → { promptOverride, steps, cfg, seed } or null
let sceneImageUrls = {}; // objectId → resolved thumbnail URL
let sdSteps = 20;
let sdRefinerSteps = 20;
let sdCfg = 5;
let sdHires = false;
let sdStyle = 'illustration';
let sdSeed = -1;
let sdModelList = [];
let sdModel = '';
let sdRefinerModel = '';
let sdDenoisingStrength = 0.65;
let lastUsedSeed = -1;   // persist seed from first generation
let lastPrompt = '';      // last LLM-generated image prompt
let sdModelsLoaded = false;

// Step 5
let metaScenes = [];
let step5ImageUrls = {};  // imageObjectId → resolved media URL

function resetState() {
    step = 1;
    bookObjectId = null;
    method = 'auto';
    bookName = '';
    chatConfigName = null;
    genre = '';
    promptMode = 'single';
    promptTemplate = null;
    promptTemplates = { extractScenes: null, extractChunk: null, extractCharacter: null, sceneBlurb: null, landscapePrompt: null };
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
    sceneErrors = {};
    sceneOverrides = {};
    sceneImageUrls = {};
    sdSteps = 20;
    sdRefinerSteps = 20;
    sdCfg = 5;
    sdHires = false;
    sdStyle = 'illustration';
    sdSeed = -1;
    sdModel = '';
    sdRefinerModel = '';
    sdDenoisingStrength = 0.65;
    sdModelList = [];
    sdModelsLoaded = false;
    lastUsedSeed = -1;
    lastPrompt = '';
    metaScenes = [];
    step5ImageUrls = {};
}

// ── Step helpers ──────────────────────────────────────────────────────

// chatConfig is now selected via ObjectPicker — no preload needed

function getPromptTemplate(key) {
    if (promptMode === 'single') return promptTemplate;
    return promptTemplates[key] || null;
}

function collectCharacters() {
    let seen = {};
    for (let s of extractedScenes) {
        if (!Array.isArray(s.characters)) continue;
        for (let c of s.characters) {
            let name = typeof c === 'string' ? c : (c.name || '');
            if (!name || seen[name]) continue;
            seen[name] = true;
            let obj = typeof c === 'object' ? c : {};
            let firstName = name, lastName = '';
            let sp = name.lastIndexOf(' ');
            if (sp > 0) { firstName = name.substring(0, sp); lastName = name.substring(sp + 1); }
            characters.push({
                name: name,
                firstName: firstName,
                lastName: lastName,
                gender: obj.gender || '',
                appearance: obj.appearance || obj.physicalDescription || '',
                outfit: obj.outfit || obj.clothing || '',
                role: obj.role || '',
                portraitPrompt: '',
                status: 'pending'
            });
        }
    }
}

function addCharacter() {
    characters.push({
        name: 'New Character', firstName: 'New', lastName: 'Character',
        gender: '', appearance: '', outfit: '', role: '', portraitPrompt: '', status: 'pending'
    });
    m.redraw();
}

function removeCharacter(idx) {
    characters.splice(idx, 1);
    m.redraw();
}

/**
 * Unified extract — backend auto-chunks if text > 8000 chars.
 * Handles both response formats: plain array (short text) or { sceneList, chunked } (long text).
 */
async function doExtract() {
    extracting = true;
    extractError = null;
    m.redraw();
    try {
        let result = await extractScenes(workObjectId, chatConfigName, null, getPromptTemplate('extractScenes'));
        // Backend returns { sceneList, chunked: true } for long text, or plain array for short
        let sceneArray;
        if (result && result.sceneList) {
            sceneArray = result.sceneList;
        } else if (Array.isArray(result)) {
            sceneArray = result;
        } else {
            sceneArray = [];
        }
        if (!sceneArray.length) {
            extractError = 'No scenes returned by LLM';
        } else {
            extractedScenes = sceneArray;
            collectCharacters();
            step = 2;
        }
    } catch (e) {
        extractError = e.message || 'Extraction failed';
    }
    extracting = false;
    m.redraw();
}

async function doFullExtract() {
    extracting = true;
    extractError = null;
    m.redraw();
    try {
        let meta = await fullExtract(workObjectId, chatConfigName, null, genre || null, bookName || workName);
        bookObjectId = meta.bookObjectId || null;
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
        step = 2;
    } catch (e) {
        extractError = e.message || 'Extraction failed';
    }
    extracting = false;
    m.redraw();
}

function addManualScene() {
    extractedScenes.push({
        index: extractedScenes.length,
        title: 'New Scene',
        blurb: '',
        setting: '',
        action: '',
        mood: '',
        characters: [],
        diffusionPrompt: '',
        userEdited: true
    });
    m.redraw();
}

function removeScene(idx) {
    extractedScenes.splice(idx, 1);
    extractedScenes.forEach(function (s, i) { s.index = i; });
    m.redraw();
}

function moveScene(idx, dir) {
    let newIdx = idx + dir;
    if (newIdx < 0 || newIdx >= extractedScenes.length) return;
    let tmp = extractedScenes[idx];
    extractedScenes[idx] = extractedScenes[newIdx];
    extractedScenes[newIdx] = tmp;
    extractedScenes.forEach(function (s, i) { s.index = i; });
    m.redraw();
}

function buildSdConfig() {
    let cfg = { steps: sdSteps, refinerSteps: sdRefinerSteps, cfg: sdCfg, hires: sdHires, style: sdStyle };
    if (sdModel) cfg.model = sdModel;
    if (sdRefinerModel) cfg.refinerModel = sdRefinerModel;
    if (sdDenoisingStrength >= 0) cfg.denoisingStrength = sdDenoisingStrength;
    // After first image, reuse the same seed for consistency
    if (lastUsedSeed > 0) cfg.seed = lastUsedSeed;
    else if (sdSeed > 0) cfg.seed = sdSeed;
    return cfg;
}

async function doGenerateAll() {
    generating = true;
    genCancelled = false;
    m.redraw();
    let targets = scenes.length ? scenes : extractedScenes;
    for (let s of targets) {
        if (genCancelled) break;
        let oid = s.objectId;
        if (!oid || genProgress[oid] === 'accepted' || genProgress[oid] === 'skipped') continue;
        await doGenerateOne(s);
    }
    generating = false;
    m.redraw();
}

async function doGenerateOne(s) {
    let oid = s.objectId;
    if (!oid) return;
    genProgress[oid] = 'generating';
    sceneErrors[oid] = null;
    m.redraw();
    try {
        let overrides = sceneOverrides[oid];
        let sdCfg = buildSdConfig();
        let promptOvr = null;
        if (overrides) {
            if (overrides.steps) sdCfg.steps = overrides.steps;
            if (overrides.cfg) sdCfg.cfg = overrides.cfg;
            if (overrides.seed) sdCfg.seed = overrides.seed;
            if (overrides.promptOverride) promptOvr = overrides.promptOverride;
        }
        let result = await generateSceneImage(oid, sdCfg, chatConfigName, promptOvr, getPromptTemplate('landscapePrompt'));
        s.imageObjectId = result.imageObjectId;
        if (result.seed && lastUsedSeed < 0) lastUsedSeed = result.seed;
        if (result.prompt) lastPrompt = result.prompt;
        genProgress[oid] = 'done';
        // Resolve thumbnail
        if (result.imageObjectId) {
            resolveImageUrl(result.imageObjectId).then(function (url) {
                if (url) { sceneImageUrls[oid] = url; m.redraw(); }
            });
        }
    } catch (e) {
        genProgress[oid] = 'error';
        sceneErrors[oid] = e.message || 'Generation failed';
    }
    m.redraw();
}

function acceptScene(oid) {
    genProgress[oid] = 'accepted';
    m.redraw();
}

function rejectScene(s) {
    let oid = s.objectId;
    s.imageObjectId = null;
    delete sceneImageUrls[oid];
    genProgress[oid] = 'pending';
    m.redraw();
}

function skipScene(oid) {
    genProgress[oid] = 'skipped';
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
            ]),
            // Prompt template config
            m('div', { class: 'border dark:border-gray-700 rounded p-3 space-y-2' }, [
                m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-1' }, 'Prompt Templates'),
                m('div', { class: 'flex gap-4 mb-2' }, [
                    m('label', { class: 'flex items-center gap-1 text-xs cursor-pointer' }, [
                        m('input', {
                            type: 'radio', name: 'promptMode', value: 'single',
                            checked: promptMode === 'single',
                            onchange: function () { promptMode = 'single'; }
                        }),
                        'Use one for all'
                    ]),
                    m('label', { class: 'flex items-center gap-1 text-xs cursor-pointer' }, [
                        m('input', {
                            type: 'radio', name: 'promptMode', value: 'per-prompt',
                            checked: promptMode === 'per-prompt',
                            onchange: function () { promptMode = 'per-prompt'; }
                        }),
                        'Select per prompt'
                    ])
                ]),
                promptMode === 'single'
                    ? m('div', {
                        class: 'text-field-full text-xs cursor-pointer flex items-center justify-between',
                        onclick: function () {
                            ObjectPicker.openLibrary({
                                libraryType: 'promptTemplate',
                                title: 'Select Prompt Template',
                                onSelect: function (item) {
                                    if (item && item.name) { promptTemplate = item.name; m.redraw(); }
                                }
                            });
                        }
                    }, [
                        m('span', { class: promptTemplate ? '' : 'text-gray-400' }, promptTemplate || '(default)'),
                        m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                    ])
                    : m('div', { class: 'space-y-1' },
                        [
                            { key: 'extractScenes', label: 'Scene Extraction' },
                            { key: 'extractChunk', label: 'Chunk Extraction' },
                            { key: 'extractCharacter', label: 'Character Details' },
                            { key: 'sceneBlurb', label: 'Scene Blurb' },
                            { key: 'landscapePrompt', label: 'Landscape Prompt' }
                        ].map(function (p) {
                            return m('div', { key: p.key, class: 'flex items-center gap-2' }, [
                                m('span', { class: 'text-xs text-gray-500 w-28 shrink-0' }, p.label),
                                m('div', {
                                    class: 'text-field-full text-xs cursor-pointer flex-1 flex items-center justify-between',
                                    onclick: function () {
                                        ObjectPicker.openLibrary({
                                            libraryType: 'promptTemplate',
                                            title: 'Select ' + p.label + ' Template',
                                            onSelect: function (item) {
                                                if (item && item.name) { promptTemplates[p.key] = item.name; m.redraw(); }
                                            }
                                        });
                                    }
                                }, [
                                    m('span', { class: promptTemplates[p.key] ? '' : 'text-gray-400' },
                                        promptTemplates[p.key] || '(default)'),
                                    m('span', { class: 'material-symbols-outlined text-gray-400 text-xs' }, 'search')
                                ])
                            ]);
                        })
                    )
            ]),

            extractError ? m('div', { class: 'text-red-500 text-sm' }, extractError) : null
        ]) : m('div', { class: 'text-sm text-gray-500 italic' }, 'Manual scene entry — proceed to add scenes.')
    ]);
}

function renderStep2() {
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Scene List (' + extractedScenes.length + ')'),
            m('div', { class: 'flex gap-2' }, [
                m('button', {
                    class: 'btn text-xs',
                    onclick: function () { addManualScene(); }
                }, [m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'add'), 'Add Scene']),
                m('button', {
                    class: 'btn text-xs',
                    disabled: extracting,
                    onclick: function () { doExtract(); }
                }, extracting ? 'Extracting...' : 'Re-extract')
            ])
        ]),
        extracting ? m('div', { class: 'text-sm text-gray-500' }, 'Extracting scenes...') :
        m('div', { class: 'space-y-2 max-h-[28rem] overflow-y-auto' },
            extractedScenes.map(function (s, i) {
                return m('div', { key: 'scene-' + i, class: 'border dark:border-gray-700 rounded p-3 text-sm space-y-2' }, [
                    // Header: number + title + reorder/remove buttons
                    m('div', { class: 'flex gap-2 items-center' }, [
                        m('span', { class: 'text-gray-400 text-xs w-5 shrink-0' }, String(i + 1) + '.'),
                        m('input', {
                            class: 'text-field-full text-sm font-medium flex-1',
                            value: s.title || '',
                            placeholder: 'Scene title',
                            oninput: function (e) { extractedScenes[i].title = e.target.value; s.userEdited = true; }
                        }),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-600 p-0.5',
                            disabled: i === 0,
                            onclick: function () { moveScene(i, -1); }
                        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'arrow_upward')),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-600 p-0.5',
                            disabled: i === extractedScenes.length - 1,
                            onclick: function () { moveScene(i, 1); }
                        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'arrow_downward')),
                        m('button', {
                            class: 'text-red-400 hover:text-red-600 p-0.5',
                            onclick: function () { removeScene(i); }
                        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'close'))
                    ]),
                    // Blurb
                    m('textarea', {
                        class: 'w-full text-field-full text-xs', rows: 2,
                        value: s.blurb || s.summary || s.description || '',
                        placeholder: 'Scene description/blurb',
                        oninput: function (e) {
                            extractedScenes[i].blurb = e.target.value;
                            extractedScenes[i].summary = e.target.value;
                            s.userEdited = true;
                        }
                    }),
                    // Diffusion prompt (collapsible)
                    m('details', { class: 'text-xs' }, [
                        m('summary', { class: 'cursor-pointer text-gray-500 hover:text-gray-700' }, 'Diffusion Prompt'),
                        m('textarea', {
                            class: 'w-full text-field-full text-xs mt-1', rows: 2,
                            value: s.diffusionPrompt || '',
                            placeholder: 'Stable Diffusion prompt for illustration',
                            oninput: function (e) { extractedScenes[i].diffusionPrompt = e.target.value; s.userEdited = true; }
                        })
                    ]),
                    // Characters
                    m('div', { class: 'text-gray-500 text-xs' }, 'Characters: ' +
                        (Array.isArray(s.characters) ? s.characters.map(function (c) { return typeof c === 'string' ? c : c.name; }).join(', ') : '—'))
                ]);
            })
        )
    ]);
}

function renderStep3() {
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Characters (' + characters.length + ')'),
            m('button', { class: 'btn text-xs', onclick: addCharacter }, [
                m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'add'), 'Add Character'
            ])
        ]),
        characters.length === 0
            ? m('div', { class: 'text-sm text-gray-500 italic' }, 'No characters found. Add manually or proceed to images.')
            : m('div', { class: 'space-y-3 max-h-[32rem] overflow-y-auto' },
                characters.map(function (c, i) {
                    let status = charProgress[c.name] || c.status || 'pending';
                    return m('div', {
                        key: 'char-' + i,
                        class: 'border dark:border-gray-700 rounded p-3 text-sm space-y-2'
                    }, [
                        // Header: name + status + remove
                        m('div', { class: 'flex gap-2 items-center' }, [
                            m('input', {
                                class: 'text-field-full text-sm font-medium flex-1',
                                value: c.name,
                                placeholder: 'Character name',
                                oninput: function (e) {
                                    c.name = e.target.value;
                                    let sp = c.name.lastIndexOf(' ');
                                    if (sp > 0) { c.firstName = c.name.substring(0, sp); c.lastName = c.name.substring(sp + 1); }
                                    else { c.firstName = c.name; c.lastName = ''; }
                                }
                            }),
                            m('select', {
                                class: 'text-field-compact text-xs w-24',
                                value: c.gender,
                                onchange: function (e) { c.gender = e.target.value; }
                            }, [
                                m('option', { value: '' }, 'Gender'),
                                m('option', { value: 'MALE' }, 'Male'),
                                m('option', { value: 'FEMALE' }, 'Female'),
                                m('option', { value: 'OTHER' }, 'Other')
                            ]),
                            m('span', {
                                class: 'text-xs px-2 py-0.5 rounded ' + (
                                    status === 'done' ? 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300' :
                                    status === 'creating' ? 'bg-blue-100 text-blue-600' :
                                    status === 'error' ? 'bg-red-100 text-red-600' :
                                    'bg-gray-100 text-gray-500'
                                )
                            }, status),
                            m('button', {
                                class: 'text-red-400 hover:text-red-600 p-0.5',
                                onclick: function () { removeCharacter(i); }
                            }, m('span', { class: 'material-symbols-outlined text-sm' }, 'close'))
                        ]),
                        // Role
                        m('input', {
                            class: 'w-full text-field-full text-xs',
                            value: c.role,
                            placeholder: 'Role (e.g., protagonist, antagonist)',
                            oninput: function (e) { c.role = e.target.value; }
                        }),
                        // Appearance
                        m('textarea', {
                            class: 'w-full text-field-full text-xs', rows: 2,
                            value: c.appearance,
                            placeholder: 'Physical appearance (hair, skin, build, distinguishing features)',
                            oninput: function (e) { c.appearance = e.target.value; }
                        }),
                        // Outfit
                        m('textarea', {
                            class: 'w-full text-field-full text-xs', rows: 1,
                            value: c.outfit,
                            placeholder: 'Outfit / clothing',
                            oninput: function (e) { c.outfit = e.target.value; }
                        }),
                        // Portrait prompt (collapsible)
                        m('details', { class: 'text-xs' }, [
                            m('summary', { class: 'cursor-pointer text-gray-500 hover:text-gray-700' }, 'Portrait Prompt'),
                            m('textarea', {
                                class: 'w-full text-field-full text-xs mt-1', rows: 2,
                                value: c.portraitPrompt,
                                placeholder: 'SD portrait prompt (auto-generated if empty)',
                                oninput: function (e) { c.portraitPrompt = e.target.value; }
                            })
                        ])
                    ]);
                })
            ),
        creatingChars ? m('div', { class: 'text-sm text-blue-500' }, 'Creating characters...') : null
    ]);
}

function loadSdModels() {
    if (sdModelsLoaded) return;
    sdModelsLoaded = true;
    if (am7model._sd && am7model._sd.fetchModels) {
        am7model._sd.fetchModels().then(function (list) {
            sdModelList = Array.isArray(list) ? list : [];
            m.redraw();
        }).catch(function () { sdModelList = []; });
    }
}

function renderSdConfig() {
    loadSdModels();
    return m('div', { class: 'border dark:border-gray-700 rounded p-3 mb-3 space-y-2' }, [
        m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-1' }, 'SD Configuration'),
        m('div', { class: 'grid grid-cols-3 gap-2' }, [
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Steps'),
                m('input', { class: 'text-field-compact text-xs', type: 'number', min: 1, max: 50,
                    value: sdSteps, oninput: function (e) { sdSteps = parseInt(e.target.value) || 20; } })
            ]),
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'CFG'),
                m('input', { class: 'text-field-compact text-xs', type: 'number', min: 1, max: 30, step: 0.5,
                    value: sdCfg, oninput: function (e) { sdCfg = parseFloat(e.target.value) || 5; } })
            ]),
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Seed'),
                m('div', { class: 'flex gap-1' }, [
                    m('input', { class: 'text-field-compact text-xs', style: 'flex:1', type: 'number',
                        value: lastUsedSeed > 0 ? lastUsedSeed : sdSeed,
                        oninput: function (e) { sdSeed = parseInt(e.target.value) || -1; lastUsedSeed = -1; } }),
                    m('button', { class: 'text-gray-400 hover:text-gray-600', title: 'Random',
                        onclick: function () { sdSeed = -1; lastUsedSeed = -1; } },
                        m('span', { class: 'material-symbols-outlined text-sm' }, 'casino'))
                ])
            ]),
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Style'),
                m('input', { class: 'text-field-compact text-xs', value: sdStyle,
                    oninput: function (e) { sdStyle = e.target.value; } })
            ]),
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Refiner Steps'),
                m('input', { class: 'text-field-compact text-xs', type: 'number', min: 0, max: 50,
                    value: sdRefinerSteps, oninput: function (e) { sdRefinerSteps = parseInt(e.target.value) || 0; } })
            ]),
            m('div', { class: 'flex items-end pb-1' }, [
                m('label', { class: 'flex items-center gap-1 text-xs cursor-pointer' }, [
                    m('input', { type: 'checkbox', checked: sdHires,
                        onchange: function (e) { sdHires = e.target.checked; } }),
                    'HiRes'
                ])
            ]),
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Denoising'),
                m('input', { class: 'text-field-compact text-xs', type: 'number', min: 0, max: 1, step: 0.05,
                    value: sdDenoisingStrength,
                    oninput: function (e) { sdDenoisingStrength = parseFloat(e.target.value) || 0.65; } })
            ])
        ]),
        // Model dropdowns
        m('div', { class: 'grid grid-cols-2 gap-2 mt-2' }, [
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Model'),
                sdModelList.length > 0
                    ? m('select', { class: 'text-field-compact text-xs', value: sdModel,
                        onchange: function (e) { sdModel = e.target.value; } },
                        [m('option', { value: '' }, '-- Default --')].concat(
                            sdModelList.map(function (ml) { return m('option', { value: ml }, ml); })))
                    : m('input', { class: 'text-field-compact text-xs', value: sdModel, placeholder: '(loading...)',
                        oninput: function (e) { sdModel = e.target.value; } })
            ]),
            m('div', [
                m('label', { class: 'field-label text-xs' }, 'Refiner Model'),
                sdModelList.length > 0
                    ? m('select', { class: 'text-field-compact text-xs', value: sdRefinerModel,
                        onchange: function (e) { sdRefinerModel = e.target.value; } },
                        [m('option', { value: '' }, '-- Default --')].concat(
                            sdModelList.map(function (ml) { return m('option', { value: ml }, ml); })))
                    : m('input', { class: 'text-field-compact text-xs', value: sdRefinerModel, placeholder: '(loading...)',
                        oninput: function (e) { sdRefinerModel = e.target.value; } })
            ])
        ]),
        lastUsedSeed > 0 ? m('div', { class: 'text-xs text-gray-500 mt-1' },
            'Seed locked: ' + lastUsedSeed + ' (from first generation)') : null,
        lastPrompt ? m('div', { class: 'mt-2' }, [
            m('div', { class: 'text-xs font-medium text-gray-500' }, 'Last prompt used:'),
            m('div', { class: 'text-xs text-gray-600 dark:text-gray-400 bg-gray-50 dark:bg-gray-800 rounded p-2 mt-1 max-h-20 overflow-y-auto' },
                lastPrompt)
        ]) : null
    ]);
}

function renderStep4() {
    let targets = scenes.length ? scenes : extractedScenes;
    let pendingCount = targets.filter(function (s) {
        let st = s.objectId ? (genProgress[s.objectId] || 'pending') : 'no-id';
        return st !== 'accepted' && st !== 'skipped' && s.objectId;
    }).length;
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Image Generation'),
            m('div', { class: 'flex gap-2' }, [
                generating
                    ? m('button', { class: 'btn text-sm', onclick: function () { genCancelled = true; } }, 'Cancel')
                    : m('button', {
                        class: 'btn btn-primary text-sm',
                        disabled: pendingCount === 0,
                        onclick: doGenerateAll
                    }, 'Generate All (' + pendingCount + ')')
            ])
        ]),

        renderSdConfig(),

        m('div', { class: 'space-y-3 max-h-[32rem] overflow-y-auto' },
            targets.map(function (s) {
                let oid = s.objectId;
                if (!oid) return m('div', { key: s.title, class: 'text-xs text-gray-400 p-2' }, 'Not committed: ' + (s.title || ''));
                let status = genProgress[oid] || (s.imageObjectId ? 'done' : 'pending');
                let thumbUrl = sceneImageUrls[oid] || null;
                let errMsg = sceneErrors[oid] || null;
                let ovr = sceneOverrides[oid] || {};

                let borderClass = status === 'accepted' ? 'border-green-500' :
                    status === 'error' ? 'border-red-500' :
                    status === 'skipped' ? 'border-gray-400 opacity-60' :
                    'dark:border-gray-700';

                return m('div', {
                    key: oid,
                    class: 'border rounded p-3 space-y-2 ' + borderClass
                }, [
                    // Header row: title + status badge
                    m('div', { class: 'flex items-center gap-2' }, [
                        // Thumbnail
                        thumbUrl && (status === 'done' || status === 'accepted')
                            ? m('img', { src: thumbUrl, class: 'w-12 h-12 rounded object-cover shrink-0' })
                            : status === 'generating'
                                ? m('div', { class: 'w-12 h-12 rounded bg-blue-50 dark:bg-blue-900/30 flex items-center justify-center shrink-0' },
                                    m('span', { class: 'material-symbols-outlined text-blue-500 text-sm animate-spin' }, 'progress_activity'))
                                : m('div', { class: 'w-12 h-12 rounded bg-gray-100 dark:bg-gray-800 flex items-center justify-center shrink-0' },
                                    m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'image')),

                        m('div', { class: 'flex-1 min-w-0' }, [
                            m('div', { class: 'font-medium text-sm truncate' }, s.title || 'Untitled'),
                            errMsg ? m('div', { class: 'text-red-500 text-xs' }, errMsg) : null
                        ]),

                        // Status badge
                        m('span', {
                            class: 'text-xs px-2 py-0.5 rounded shrink-0 ' + (
                                status === 'accepted' ? 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300' :
                                status === 'done' ? 'bg-blue-100 text-blue-600' :
                                status === 'generating' ? 'bg-yellow-100 text-yellow-700' :
                                status === 'error' ? 'bg-red-100 text-red-600' :
                                status === 'skipped' ? 'bg-gray-200 text-gray-500' :
                                'bg-gray-100 text-gray-500'
                            )
                        }, status)
                    ]),

                    // Action buttons row
                    m('div', { class: 'flex gap-2 flex-wrap' }, [
                        // Accept/Reject for done images
                        status === 'done' ? [
                            m('button', {
                                class: 'btn text-xs bg-green-50 hover:bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
                                onclick: function () { acceptScene(oid); }
                            }, [m('span', { class: 'material-symbols-outlined text-xs mr-0.5' }, 'check'), 'Accept']),
                            m('button', {
                                class: 'btn text-xs bg-orange-50 hover:bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300',
                                onclick: function () { rejectScene(s); }
                            }, [m('span', { class: 'material-symbols-outlined text-xs mr-0.5' }, 'refresh'), 'Reject'])
                        ] : null,

                        // Retry for errors
                        status === 'error' ? [
                            m('button', {
                                class: 'btn text-xs',
                                disabled: generating,
                                onclick: function () { doGenerateOne(s); }
                            }, [m('span', { class: 'material-symbols-outlined text-xs mr-0.5' }, 'refresh'), 'Retry']),
                            m('button', {
                                class: 'btn text-xs text-gray-500',
                                onclick: function () { skipScene(oid); }
                            }, 'Skip')
                        ] : null,

                        // Generate for pending
                        status === 'pending' ? [
                            m('button', {
                                class: 'btn text-xs',
                                disabled: generating,
                                onclick: function () { doGenerateOne(s); }
                            }, 'Generate'),
                            m('button', {
                                class: 'btn text-xs text-gray-500',
                                onclick: function () { skipScene(oid); }
                            }, 'Skip')
                        ] : null,

                        // Accepted — allow undo
                        status === 'accepted' ? m('button', {
                            class: 'text-xs text-gray-400 hover:text-gray-600',
                            onclick: function () { genProgress[oid] = 'done'; m.redraw(); }
                        }, 'Undo accept') : null,

                        // Skipped — allow undo
                        status === 'skipped' ? m('button', {
                            class: 'text-xs text-gray-400 hover:text-gray-600',
                            onclick: function () { genProgress[oid] = 'pending'; m.redraw(); }
                        }, 'Undo skip') : null
                    ]),

                    // Per-scene overrides (collapsible)
                    status !== 'accepted' && status !== 'skipped' ? m('details', { class: 'text-xs' }, [
                        m('summary', { class: 'cursor-pointer text-gray-500 hover:text-gray-700' }, 'Scene Overrides'),
                        m('div', { class: 'grid grid-cols-3 gap-2 mt-1' }, [
                            m('div', [
                                m('label', { class: 'field-label text-xs' }, 'Prompt Override'),
                                m('textarea', {
                                    class: 'w-full text-field-full text-xs', rows: 2,
                                    value: ovr.promptOverride || '',
                                    placeholder: 'Custom SD prompt (overrides pipeline)',
                                    oninput: function (e) {
                                        if (!sceneOverrides[oid]) sceneOverrides[oid] = {};
                                        sceneOverrides[oid].promptOverride = e.target.value;
                                    }
                                })
                            ]),
                            m('div', [
                                m('label', { class: 'field-label text-xs' }, 'Steps'),
                                m('input', {
                                    class: 'text-field-compact text-xs', type: 'number', min: 1, max: 50,
                                    value: ovr.steps || '',
                                    placeholder: String(sdSteps),
                                    oninput: function (e) {
                                        if (!sceneOverrides[oid]) sceneOverrides[oid] = {};
                                        sceneOverrides[oid].steps = parseInt(e.target.value) || null;
                                    }
                                })
                            ]),
                            m('div', [
                                m('label', { class: 'field-label text-xs' }, 'CFG'),
                                m('input', {
                                    class: 'text-field-compact text-xs', type: 'number', min: 1, max: 30, step: 0.5,
                                    value: ovr.cfg || '',
                                    placeholder: String(sdCfg),
                                    oninput: function (e) {
                                        if (!sceneOverrides[oid]) sceneOverrides[oid] = {};
                                        sceneOverrides[oid].cfg = parseFloat(e.target.value) || null;
                                    }
                                })
                            ]),
                            m('div', [
                                m('label', { class: 'field-label text-xs' }, 'Seed'),
                                m('input', {
                                    class: 'text-field-compact text-xs', type: 'number',
                                    value: ovr.seed || '',
                                    placeholder: '-1',
                                    oninput: function (e) {
                                        if (!sceneOverrides[oid]) sceneOverrides[oid] = {};
                                        sceneOverrides[oid].seed = parseInt(e.target.value) || null;
                                    }
                                })
                            ])
                        ])
                    ]) : null
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

function renderBgActivity() {
    let bg = LLMConnector.bgActivity;
    if (!bg || !bg.label) return null;
    return m('div', { class: 'flex items-center gap-2 px-4 py-2 text-sm text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/20 rounded mx-4 mb-2' }, [
        m('span', { class: 'material-symbols-outlined text-base animate-spin' }, bg.icon || 'progress_activity'),
        m('span', bg.label)
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
                label: extracting ? 'Extracting...' : 'Extract',
                icon: 'auto_awesome',
                primary: true,
                disabled: extracting,
                onclick: doExtract
            });
        } else {
            // Manual mode — go to Step 2 (scene editor) to add scenes
            actions.push({
                label: 'Continue', icon: 'arrow_forward', primary: true,
                onclick: function () { step = 2; m.redraw(); }
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
            label: creatingChars ? 'Creating...' : 'Continue to Images',
            icon: 'arrow_forward', primary: true,
            disabled: creatingChars,
            onclick: async function () {
                creatingChars = true;
                m.redraw();
                try {
                    let meta = await createFromScenes(
                        workObjectId, chatConfigName, genre || null,
                        bookName || workName, extractedScenes, characters
                    );
                    bookObjectId = meta.bookObjectId || null;
                    metaScenes = meta.scenes || [];
                    scenes = metaScenes;
                    step = 4;
                } catch (e) {
                    page.toast('error', 'Failed to create book: ' + (e.message || ''));
                }
                creatingChars = false;
                m.redraw();
            }
        });
    } else if (step === 4) {
        let targets = scenes.length ? scenes : extractedScenes;
        let allResolved = targets.length > 0 && targets.every(function (s) {
            if (!s.objectId) return true;
            let st = genProgress[s.objectId];
            return st === 'accepted' || st === 'skipped';
        });
        actions.push({
            label: 'View Picture Book', icon: 'auto_stories',
            primary: allResolved, disabled: !allResolved,
            onclick: async function () {
                try {
                    metaScenes = await loadPictureBook(bookObjectId || workObjectId);
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
                m.route.set('/picture-book/' + (bookObjectId || workObjectId));
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
                    renderBgActivity(),
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
