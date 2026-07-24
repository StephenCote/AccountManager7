import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import {
    extractScenes, createFromScenes, generateSceneImage, prepareSceneImagePrompts,
    cancelPictureBook, regenerateBlurb, loadPictureBook, getBookSdConfig, setSceneStatus,
    resolveImageUrl, resolveAllImageUrls
} from './sceneExtractor.js';
import { openCharacterManager, initCharacterManager, renderCharacterManagerContent } from './pictureBookCharacters.js';
import { ObjectPicker } from '../components/picker.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';

/**
 * Picture Book workflow — multi-step wizard launched from a data.data or data.note object.
 * Steps:
 *   1 — Source & Method (auto vs manual, chat config, scene count, genre)
 *   2 — Scene Preview (auto path: review/edit extracted scenes). "Continue" creates the book +
 *       real charPerson records (createFromScenes) before advancing — real characters exist by
 *       the time Step 3 renders.
 *   3 — Manage Characters (real charPerson records: portrait, statistics, apparel, "Open Full
 *       Editor" link — renders pictureBookCharacters.js inline, same UI as the steps 4/5 popup)
 *   4 — Image Generation (generate per-scene images)
 *   5 — Picture Book View (read-only gallery preview of the generated scenes; reorder and
 *       blurb editing are NOT here — they live in the standalone viewer, features/pictureBook.js,
 *       reachable via this step's "Open in Viewer" action)
 */

// ── Wizard state ──────────────────────────────────────────────────────

let step = 1;
let workObjectId = null;  // Source document objectId (for extract API)
let bookObjectId = null;  // Book group objectId (for scenes/viewer/reset APIs)
let workName = '';

// Step 1
let method = 'auto';
let bookName = '';
// Object refs (record with .name and .objectId), not just names — resolved from library on open
let chatConfigRef = null;
let genre = '';
let promptMode = 'single';  // 'single' | 'per-prompt'
let promptTemplate = null;   // single mode — applies to all (object ref)
let promptTemplates = {      // per-prompt mode (object refs)
    extractScenes: null,
    extractChunk: null,
    extractCharacter: null,
    sceneBlurb: null,
    landscapePrompt: null
};
let defaultsLoading = false;

// Default prompt template names for each slot — resolved on demand from system library
const DEFAULT_PROMPT_NAMES = {
    extractScenes: 'pictureBook.extract-scenes',
    extractChunk: 'pictureBook.extract-chunk',
    extractCharacter: 'pictureBook.extract-character',
    sceneBlurb: 'pictureBook.scene-blurb',
    landscapePrompt: 'pictureBook.landscape-prompt'
};
const DEFAULT_CHAT_CONFIG_NAME = 'contentAnalysis';
// "single" mode primary template — applied to all prompts if no per-slot override given
const DEFAULT_SINGLE_TEMPLATE = 'pictureBook.extract-scenes';

// Step 2
let extractedScenes = [];
let extracting = false;
let extractError = null;
let blurbRegenerating = {}; // scene index → bool (U3: per-scene "Regenerate blurb" in-flight flag)

// Step 3 (Manage Characters — real charPerson records created at the Step 2→3 transition;
// pictureBookCharacters.js owns its own list/detail state once initCharacterManager() runs)
let creatingChars = false;

// Step 4
let scenes = [];  // from meta or Step 2
let generating = false;
let genProgress = {};  // objectId → 'pending'|'generating'|'done'|'error'|'accepted'|'skipped'
let genCancelled = false;
let currentAbortController = null; // aborts the in-flight generateSceneImage fetch — genCancelled alone only stops the *next* scene from starting
let prepareAbortController = null; // aborts the in-flight prepare-images fetch (KI-10 — the pre-loop landscape-prompt batch)
let sceneErrors = {};   // objectId → error message
let sceneOverrides = {}; // objectId → { promptOverride, steps, cfg, seed } or null
let sceneImageUrls = {}; // objectId → resolved thumbnail URL

// SD configuration — a single plain-config object shared with (and mutated in place by) the
// canonical components/SdConfigPanel.js (KI-13/KI-16 convergence: no bespoke 3rd copy). Keys match
// exactly what the backend SceneGenerationParams consumes (steps/refinerSteps/cfg/hires/seed/model/
// refinerModel/denoisingStrength/sampler/scheduler/refinerSampler/refinerScheduler/loras/style);
// the panel also surfaces width/height/imageCount/refinerCfg for parity with the other call sites.
function defaultSdConfig() {
    return {
        steps: 20,
        refinerSteps: 20,
        cfg: 5,
        refinerCfg: 5,
        hires: false,
        style: 'illustration',
        seed: -1,
        model: '',
        refinerModel: '',
        denoisingStrength: 0.65,
        sampler: 'dpmpp_2m',
        scheduler: 'karras',
        refinerSampler: 'dpmpp_2m',
        refinerScheduler: 'karras',
        width: '1024',
        height: '768',
        imageCount: 1,
        loras: []  // ['loraName:weight', ...]
    };
}
let sdConfig = defaultSdConfig();
let sdModelList = [];   // available SD models from server
let sdLoraList = [];    // available LORAs from server
let sdModelsLoaded = false;
let sdLorasFetched = false;
let lastPrompt = '';    // last LLM-generated image prompt

// Step 5
let metaScenes = [];
let step5ImageUrls = {};  // imageObjectId → resolved media URL

function resetState() {
    step = 1;
    bookObjectId = null;
    method = 'auto';
    bookName = '';
    chatConfigRef = null;
    genre = '';
    promptMode = 'single';
    promptTemplate = null;
    promptTemplates = { extractScenes: null, extractChunk: null, extractCharacter: null, sceneBlurb: null, landscapePrompt: null };
    defaultsLoading = false;
    extractedScenes = [];
    extracting = false;
    extractError = null;
    blurbRegenerating = {};
    creatingChars = false;
    scenes = [];
    generating = false;
    genProgress = {};
    genCancelled = false;
    currentAbortController = null;
    prepareAbortController = null;
    sceneErrors = {};
    sceneOverrides = {};
    sceneImageUrls = {};
    sdConfig = defaultSdConfig();
    sdLoraList = [];
    sdLorasFetched = false;
    sdModelList = [];
    sdModelsLoaded = false;
    lastPrompt = '';
    metaScenes = [];
    step5ImageUrls = {};
}

// ── Step helpers ──────────────────────────────────────────────────────

// Extract a lightweight object ref { name, objectId } from a full record.
// Null-safe — returns null if the record is missing name/objectId.
function toRef(rec) {
    if (!rec || typeof rec !== 'object' || !rec.name || !rec.objectId) return null;
    return { name: rec.name, objectId: rec.objectId };
}

// Look up system defaults from the shared library. Library is populated lazily
// on-demand by the backend, so we re-resolve each time the wizard opens.
// Only fills slots that are still null (user selections are preserved).
async function loadDefaults() {
    defaultsLoading = true;
    m.redraw();
    try {
        // Ensure both libraries exist (idempotent — skips if already populated)
        try { await LLMConnector.ensureLibrary(); } catch (e) { /* non-fatal */ }
        try { await LLMConnector.initPromptLibrary(); } catch (e) { /* non-fatal */ }

        let lookups = [];
        if (!chatConfigRef) {
            lookups.push(LLMConnector.resolveConfig(DEFAULT_CHAT_CONFIG_NAME)
                .then(r => { chatConfigRef = toRef(r); }));
        }
        if (!promptTemplate) {
            lookups.push(LLMConnector.resolveTemplate(DEFAULT_SINGLE_TEMPLATE)
                .then(r => { promptTemplate = toRef(r); }));
        }
        Object.keys(DEFAULT_PROMPT_NAMES).forEach(function (key) {
            if (promptTemplates[key]) return;
            lookups.push(LLMConnector.resolveTemplate(DEFAULT_PROMPT_NAMES[key])
                .then(r => { promptTemplates[key] = toRef(r); }));
        });
        await Promise.all(lookups);
    } catch (e) {
        console.warn('[PictureBook] loadDefaults failed:', e);
    }
    defaultsLoading = false;
    m.redraw();
}

// Prompt "slots" whose template needs completely different vars than the extraction slots
// (setting/action/mood/charNarrations, not {text}/{count}-style story vars). "single" mode's one
// user-picked template is resolved against DEFAULT_SINGLE_TEMPLATE (an extraction template) and
// was never validated against this shape — applying it here leaves the image-prompt template's
// real vars ({text}/{count}) unsubstituted server-side, which the backend now refuses to send to
// the LLM at all (PictureBookUtil.callLlmInternal's placeholder guard) rather than sending garbage,
// but that means "single" mode's override silently no-ops for these slots instead of doing what the
// user picked it for. Found + fixed 2026-07-23 (KI-31 follow-up) after "prompts are still completely
// broken" turned out to be exactly this: a scene-extraction template applied to a landscape/
// scene-image-prompt call. Per-prompt mode is unaffected — it always looks up the slot-specific
// promptTemplates[key], which was never wired to this bug.
const IMAGE_PROMPT_SLOTS = ['landscapePrompt', 'sceneImagePrompt'];

function getPromptTemplate(key) {
    if (promptMode === 'single') {
        // Never let "single" mode's one extraction-shaped template leak into an image-prompt call —
        // it doesn't define the vars those templates need, so applying it can't do anything but
        // leave the real template's placeholders unfilled. Fall through to that operation's own
        // default template (server-side) by returning null, same as if no override were requested.
        if (IMAGE_PROMPT_SLOTS.includes(key)) return null;
        return promptTemplate ? promptTemplate.name : null;
    }
    let ref = promptTemplates[key];
    return ref ? ref.name : null;
}

function chatConfigName() {
    return chatConfigRef ? chatConfigRef.name : null;
}

/**
 * Minimal client-side character hints for createFromScenes — {name, gender, role} only. No
 * appearance/outfit/portraitPrompt: those were dead weight (createCharPerson never read them in
 * production) and omitting appearance is what makes the real per-character LLM enrichment call
 * fire and build real detail from the source text, instead of a hand-typed guess made before any
 * real charPerson (or its statistics/apparel/narrative) exists.
 */
function buildCharacterStubs() {
    let seen = {};
    let stubs = [];
    for (let s of extractedScenes) {
        if (!Array.isArray(s.characters)) continue;
        for (let c of s.characters) {
            let name = typeof c === 'string' ? c : (c.name || '');
            if (!name || seen[name]) continue;
            seen[name] = true;
            let obj = typeof c === 'object' ? c : {};
            stubs.push({ name: name, gender: obj.gender || '', role: obj.role || '' });
        }
    }
    return stubs;
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
        let result = await extractScenes(workObjectId, chatConfigName(), null, getPromptTemplate('extractScenes'));
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
            step = 2;
        }
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

/**
 * U3 — regenerate a scene's blurb via the LLM (POST /scene/{id}/blurb) and write the result back
 * into the Step 2 editor. Only callable for scenes that already exist server-side (a persisted
 * data.note objectId): raw auto-extracted scenes aren't persisted until Step 2→3's
 * createFromScenes, so `oid` is resolved by the caller from the scene itself or its persisted
 * mirror in `scenes`.
 */
async function doRegenerateBlurb(idx, oid) {
    if (!oid) return;
    blurbRegenerating[idx] = true;
    m.redraw();
    try {
        let result = await regenerateBlurb(oid, chatConfigName());
        let blurb = result && result.blurb ? result.blurb : '';
        if (blurb) {
            extractedScenes[idx].blurb = blurb;
            extractedScenes[idx].summary = blurb;
            extractedScenes[idx].userEdited = true;
            // Keep the persisted-scene mirror (used by Step 4) in sync when present.
            if (scenes[idx]) { scenes[idx].blurb = blurb; scenes[idx].summary = blurb; }
        } else {
            page.toast('error', 'Blurb regeneration returned no text');
        }
    } catch (e) {
        page.toast('error', 'Blurb regeneration failed: ' + (e.message || ''));
    }
    blurbRegenerating[idx] = false;
    m.redraw();
}

function buildSdConfig() {
    // Copy so per-scene override tweaks (below, in doGenerateOne) never mutate the shared panel
    // config. Drop "unset" values so the backend falls back to its own defaults rather than
    // receiving an empty string / empty list.
    let cfg = Object.assign({}, sdConfig);
    if (!cfg.model) delete cfg.model;
    if (!cfg.refinerModel) delete cfg.refinerModel;
    if (!cfg.loras || !cfg.loras.length) delete cfg.loras;
    return cfg;
}

/**
 * Pause the batch on a scene error and let the user choose how to proceed.
 * @returns {Promise<'cancel'|'retry'|'resume'>}
 */
function showGenerationErrorDialog(scene, message) {
    return new Promise(function (resolve) {
        Dialog.open({
            title: 'Image Generation Failed',
            size: 'sm',
            closable: false,
            content: m('div', { class: 'space-y-2' }, [
                m('p', { class: 'text-sm' }, 'Scene "' + (scene.title || 'Untitled') + '" failed to generate:'),
                m('p', { class: 'text-red-500 text-xs' }, message || 'Unknown error')
            ]),
            actions: [
                {
                    label: 'Cancel', icon: 'cancel', destructive: true,
                    onclick: function () { Dialog.close(); resolve('cancel'); }
                },
                {
                    label: 'Try Again', icon: 'refresh',
                    onclick: function () { Dialog.close(); resolve('retry'); }
                },
                {
                    label: 'Resume', icon: 'play_arrow', primary: true,
                    onclick: function () { Dialog.close(); resolve('resume'); }
                }
            ]
        });
    });
}

// Let the GPU recover between generations — PictureBook's SD calls run on shared hardware
// that has hit thermal-critical under sustained back-to-back load with no cooldown.
const SCENE_COOLDOWN_MS = 5000;

function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

async function doGenerateAll() {
    generating = true;
    genCancelled = false;
    m.redraw();
    let targets = scenes.length ? scenes : extractedScenes;

    // Batch-resolve every pending scene's landscape prompt (all LLM calls) up front, then flush
    // idle Ollama models once, before any of this run's GPU-heavy SD calls start — instead of
    // letting each scene's own LLM call interleave with the previous/next scene's SD work.
    if (bookObjectId) {
        let pendingOids = targets
            .filter(function (s) { return s.objectId && genProgress[s.objectId] !== 'accepted' && genProgress[s.objectId] !== 'skipped'; })
            .map(function (s) { return s.objectId; });
        if (pendingOids.length) {
            prepareAbortController = new AbortController();
            try {
                await prepareSceneImagePrompts(bookObjectId, pendingOids, chatConfigName(), sdConfig.style, getPromptTemplate('landscapePrompt'), prepareAbortController.signal);
            } catch (e) {
                // AbortError == the user hit Cancel during this phase (see the Cancel action in
                // buildActions step 4). genCancelled is already set, so the per-scene loop below
                // breaks immediately — don't log it as a failure.
                if (e.name !== 'AbortError') {
                    console.warn('[PictureBook] prepareSceneImagePrompts failed (non-fatal, each scene will resolve its own prompt):', e);
                }
            } finally {
                prepareAbortController = null;
            }
        }
    }

    let i = 0;
    let firstGeneration = true;
    while (i < targets.length) {
        if (genCancelled) break;
        let s = targets[i];
        let oid = s.objectId;
        if (!oid || genProgress[oid] === 'accepted' || genProgress[oid] === 'skipped') { i++; continue; }

        if (!firstGeneration) {
            await sleep(SCENE_COOLDOWN_MS);
            if (genCancelled) break;
        }
        firstGeneration = false;

        await doGenerateOne(s);
        if (genProgress[oid] === 'error') {
            let choice = await showGenerationErrorDialog(s, sceneErrors[oid]);
            if (choice === 'cancel') { genCancelled = true; break; }
            if (choice === 'retry') { continue; } // same index — retry this scene, don't advance
            // 'resume' — leave this scene as 'error' (still individually retryable) and move on
        }
        i++;
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
    let controller = new AbortController();
    currentAbortController = controller;
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
        let result = await generateSceneImage(oid, sdCfg, chatConfigName(), promptOvr, getPromptTemplate('landscapePrompt'), controller.signal);
        s.imageObjectId = result.imageObjectId;
        // After the first successful generation with a random seed, lock the resolved seed onto the
        // shared config so the remaining scenes in this book render with a consistent seed.
        if (result.seed && (sdConfig.seed == null || sdConfig.seed < 0)) sdConfig.seed = result.seed;
        if (result.prompt) lastPrompt = result.prompt;
        genProgress[oid] = 'done';
        // Resolve thumbnail
        if (result.imageObjectId) {
            resolveImageUrl(result.imageObjectId).then(function (url) {
                if (url) { sceneImageUrls[oid] = url; m.redraw(); }
            });
        }
    } catch (e) {
        if (e.name === 'AbortError') {
            // Clean cancellation, not a generation failure — back to 'pending' so it's retryable
            // and doesn't show a scary error message the user didn't cause.
            genProgress[oid] = 'pending';
            sceneErrors[oid] = null;
        } else {
            genProgress[oid] = 'error';
            sceneErrors[oid] = e.message || 'Generation failed';
        }
    } finally {
        if (currentAbortController === controller) currentAbortController = null;
    }
    m.redraw();
}

/**
 * Update local progress state immediately (for a responsive UI) and persist the same status
 * to the scene note server-side (fire-and-forget) so it survives a reload/reopen. Server-driven
 * statuses (generating/done/error) are already persisted inside generateSceneImage itself —
 * this covers the purely client-driven decisions (accept/reject/skip/undo).
 */
function persistSceneStatus(oid, status) {
    genProgress[oid] = status;
    m.redraw();
    setSceneStatus(oid, status).catch(function (e) {
        console.warn('[PictureBook] Failed to persist scene status:', e);
    });
}

function acceptScene(oid) {
    persistSceneStatus(oid, 'accepted');
}

function rejectScene(s) {
    let oid = s.objectId;
    s.imageObjectId = null;
    delete sceneImageUrls[oid];
    persistSceneStatus(oid, 'pending');
}

function skipScene(oid) {
    persistSceneStatus(oid, 'skipped');
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
                                    chatConfigRef = { name: item.name, objectId: item.objectId };
                                    m.redraw();
                                }
                            }
                        });
                    }
                }, [
                    m('span', { class: chatConfigRef ? '' : 'text-gray-400' },
                        chatConfigRef ? chatConfigRef.name : (defaultsLoading ? 'Loading default...' : '(click to select)')),
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
                                    if (item && item.name) {
                                        promptTemplate = { name: item.name, objectId: item.objectId };
                                        m.redraw();
                                    }
                                }
                            });
                        }
                    }, [
                        m('span', { class: promptTemplate ? '' : 'text-gray-400' },
                            promptTemplate ? promptTemplate.name : (defaultsLoading ? 'Loading default...' : '(default)')),
                        m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                    ])
                    : null,
                promptMode === 'single'
                    ? m('div', { class: 'text-[10px] text-gray-400' },
                        'Applies to scene/chunk/character extraction and blurb prompts only — '
                        + 'landscape and scene-image prompts need different template vars and always '
                        + 'use their own defaults; switch to "Select per prompt" to customize those.')
                    : m('div', { class: 'space-y-1' },
                        [
                            { key: 'extractScenes', label: 'Scene Extraction' },
                            { key: 'extractChunk', label: 'Chunk Extraction' },
                            { key: 'extractCharacter', label: 'Character Details' },
                            { key: 'sceneBlurb', label: 'Scene Blurb' },
                            { key: 'landscapePrompt', label: 'Landscape Prompt' }
                        ].map(function (p) {
                            let ref = promptTemplates[p.key];
                            return m('div', { key: p.key, class: 'flex items-center gap-2' }, [
                                m('span', { class: 'text-xs text-gray-500 w-28 shrink-0' }, p.label),
                                m('div', {
                                    class: 'text-field-full text-xs cursor-pointer flex-1 flex items-center justify-between',
                                    onclick: function () {
                                        ObjectPicker.openLibrary({
                                            libraryType: 'promptTemplate',
                                            title: 'Select ' + p.label + ' Template',
                                            onSelect: function (item) {
                                                if (item && item.name) {
                                                    promptTemplates[p.key] = { name: item.name, objectId: item.objectId };
                                                    m.redraw();
                                                }
                                            }
                                        });
                                    }
                                }, [
                                    m('span', { class: ref ? '' : 'text-gray-400' },
                                        ref ? ref.name : (defaultsLoading ? 'Loading...' : '(default)')),
                                    m('span', { class: 'material-symbols-outlined text-gray-400 text-xs' }, 'search')
                                ])
                            ]);
                        })
                    )
            ]),

            extracting ? m('div', { class: 'flex items-center gap-2 px-4 py-2 text-sm text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/20 rounded' }, [
                m('span', { class: 'material-symbols-outlined text-base animate-spin' }, 'progress_activity'),
                m('span', 'Extracting scenes...')
            ]) : null,

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
                    // Blurb + regenerate control (U3). The LLM regen needs a persisted scene
                    // (data.note objectId); raw auto-extracted scenes only gain one at Step 2→3, so
                    // the control appears once the book exists (objectId resolvable via the scene or
                    // its persisted mirror in `scenes`).
                    (function () {
                        let blurbOid = s.objectId || (scenes[i] && scenes[i].objectId) || null;
                        let regenerating = !!blurbRegenerating[i];
                        return m('div', { class: 'space-y-1' }, [
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
                            blurbOid ? m('button', {
                                class: 'btn text-xs text-gray-500',
                                disabled: regenerating,
                                onclick: function () { doRegenerateBlurb(i, blurbOid); }
                            }, [
                                m('span', { class: 'material-symbols-outlined text-xs mr-0.5' + (regenerating ? ' animate-spin' : '') },
                                    regenerating ? 'progress_activity' : 'auto_awesome'),
                                regenerating ? 'Regenerating...' : 'Regenerate blurb'
                            ]) : null
                        ]);
                    })(),
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
    // Step 3 IS the Manage Characters screen now — real charPerson records already exist by the
    // time this renders (created at the Step 2→3 transition), so this just renders
    // pictureBookCharacters.js's list/detail UI inline instead of a disconnected pre-creation
    // stub editor. See pictureBookCharacters.js for the actual list/statistics/apparel/portrait
    // panels and the "Open Full Editor →" link to the real charPerson record.
    return renderCharacterManagerContent();
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

function loadSdLoras() {
    if (sdLorasFetched) return;
    sdLorasFetched = true;
    if (am7model._sd && am7model._sd.fetchLoras) {
        am7model._sd.fetchLoras().then(function (list) {
            sdLoraList = Array.isArray(list) ? list : [];
            m.redraw();
        }).catch(function () { sdLoraList = []; });
    }
}

// KI-13/KI-16 convergence: the bespoke per-field SD panel that used to live here (a 3rd near-copy
// of the reimage/scene-generator config form, with Style as a free-text input and no Width/Height/
// Image Count/Refiner CFG) is gone. This now delegates to the canonical components/SdConfigPanel.js,
// which mutates the shared `sdConfig` object in place. Style is a <select> over the same style set
// as everywhere else (SdConfigPanel STYLE_OPTIONS, which mirrors PictureBookUtil.ALLOWED_STYLES).
function renderSdConfig() {
    loadSdModels();
    loadSdLoras();
    return m('div', { class: 'border dark:border-gray-700 rounded p-3 mb-3' }, [
        m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-2' }, 'SD Configuration'),
        m(SdConfigPanel, {
            config: sdConfig,
            models: sdModelList,
            loras: sdLoraList,
            onChange: function () { m.redraw(); }
        }),
        lastPrompt ? m('div', { class: 'mt-2' }, [
            m('div', { class: 'text-xs font-medium text-gray-500' }, 'Last prompt used:'),
            m('div', { class: 'text-xs text-gray-600 dark:text-gray-400 bg-gray-50 dark:bg-gray-800 rounded p-2 mt-1 max-h-20 overflow-y-auto' },
                lastPrompt)
        ]) : null
    ]);
}

function renderStep4() {
    let targets = scenes.length ? scenes : extractedScenes;
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Image Generation'),
            generating ? m('span', { class: 'text-xs text-blue-500' }, 'Generating...') : null
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
                            onclick: function () { persistSceneStatus(oid, 'done'); }
                        }, 'Undo accept') : null,

                        // Skipped — allow undo
                        status === 'skipped' ? m('button', {
                            class: 'text-xs text-gray-400 hover:text-gray-600',
                            onclick: function () { persistSceneStatus(oid, 'pending'); }
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
                                    class: 'text-field-compact text-xs', type: 'number', min: 1, max: 150,
                                    value: ovr.steps || '',
                                    placeholder: String(sdConfig.steps),
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
                                    placeholder: String(sdConfig.cfg),
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
            label: creatingChars ? 'Creating characters...' : 'Continue',
            icon: 'arrow_forward', primary: true,
            disabled: creatingChars,
            onclick: async function () {
                // Characters (and the book/scenes) already exist if the user went Back from
                // Step 3 then Continue again — createCharPerson's by-name dedup only protects
                // against duplicates *within* one createFromScenes call, not across repeated
                // calls, so never re-run it once bookObjectId is set.
                if (bookObjectId) {
                    step = 3;
                    m.redraw();
                    return;
                }
                creatingChars = true;
                m.redraw();
                try {
                    let meta = await createFromScenes(
                        workObjectId, chatConfigName(), genre || null,
                        bookName || workName, extractedScenes, buildCharacterStubs()
                    );
                    bookObjectId = meta.bookObjectId || null;
                    metaScenes = meta.scenes || [];
                    scenes = metaScenes;
                    await initCharacterManager(bookObjectId);
                    step = 3;
                } catch (e) {
                    page.toast('error', 'Failed to create characters: ' + (e.message || ''));
                }
                creatingChars = false;
                m.redraw();
            }
        });
    } else if (step === 3) {
        actions.push({
            label: 'Continue to Images', icon: 'arrow_forward', primary: true,
            onclick: function () { step = 4; m.redraw(); }
        });
    } else if (step === 4) {
        if (bookObjectId) {
            actions.push({
                label: 'Manage Characters', icon: 'group',
                onclick: function () { openCharacterManager(bookObjectId); }
            });
        }
        let targets = scenes.length ? scenes : extractedScenes;
        let allResolved = targets.length > 0 && targets.every(function (s) {
            if (!s.objectId) return true;
            let st = genProgress[s.objectId];
            return st === 'accepted' || st === 'skipped';
        });
        let pendingCount = targets.filter(function (s) {
            let st = s.objectId ? (genProgress[s.objectId] || 'pending') : 'no-id';
            return st !== 'accepted' && st !== 'skipped' && s.objectId;
        }).length;

        if (allResolved) {
            // All done — view the book
            actions.push({
                label: 'View Picture Book', icon: 'auto_stories', primary: true,
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
        } else {
            // Generate All — primary action until all resolved
            actions.push({
                label: generating ? 'Generating...' : 'Generate All (' + pendingCount + ')',
                icon: 'auto_awesome', primary: true,
                disabled: generating || pendingCount === 0,
                onclick: async function () {
                    await doGenerateAll();
                    // Auto-advance: accept all done scenes and go to view
                    let tgts = scenes.length ? scenes : extractedScenes;
                    let allDone = true;
                    for (let s of tgts) {
                        if (!s.objectId) continue;
                        let st = genProgress[s.objectId];
                        if (st === 'done') { genProgress[s.objectId] = 'accepted'; }
                        else if (st !== 'accepted' && st !== 'skipped') { allDone = false; }
                    }
                    if (allDone) {
                        try {
                            metaScenes = await loadPictureBook(bookObjectId || workObjectId);
                        } catch (e) {
                            metaScenes = scenes;
                        }
                        let viewTargets = metaScenes.length ? metaScenes : scenes;
                        step5ImageUrls = await resolveAllImageUrls(viewTargets);
                        step = 5;
                    }
                    m.redraw();
                }
            });
            if (generating) {
                actions.push({
                    label: 'Cancel', icon: 'stop',
                    onclick: function () {
                        genCancelled = true;
                        // Per-scene generateSceneImage fetch, if a scene is mid-flight.
                        if (currentAbortController) currentAbortController.abort();
                        // prepare-images phase (KI-10): stop awaiting the client fetch AND tell the
                        // backend to abort the in-flight landscape-prompt LLM batch. The server keys
                        // its cancel registry on the bookObjectId we passed to prepare-images, which
                        // the client already has — so no separate session/token bookkeeping needed.
                        if (prepareAbortController) {
                            prepareAbortController.abort();
                            if (bookObjectId) {
                                cancelPictureBook(bookObjectId).catch(function (e) {
                                    console.warn('[PictureBook] cancel request failed:', e);
                                });
                            }
                        }
                    }
                });
            }
        }

        // Save button when editing an existing book
        if (bookObjectId) {
            actions.push({
                label: 'Save', icon: 'save',
                onclick: async function () {
                    try {
                        metaScenes = await loadPictureBook(bookObjectId);
                    } catch (e) {}
                    page.toast('success', 'Book saved');
                    m.redraw();
                }
            });
        }
    } else if (step === 5) {
        if (bookObjectId) {
            actions.push({
                label: 'Manage Characters', icon: 'group',
                onclick: function () { openCharacterManager(bookObjectId); }
            });
        }
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

/**
 * If `id` is an existing picture book's group objectId (e.g. "Edit Book"/"Generate" reopened
 * from the viewer), rehydrate scenes/progress/errors from the persisted status/error fields and
 * jump the wizard to the right step instead of restarting blank at step 1. No-ops (leaves the
 * wizard at its fresh step-1 state) when `id` is a genuine source document with no book yet —
 * loadPictureBook() rejects (404) in that case.
 */
/**
 * Apply a book's persisted image generation settings (see getBookSdConfig) onto the wizard's
 * SD state vars, so a resumed/reopened book defaults to the same settings it last generated
 * with instead of the wizard's hardcoded defaults.
 */
function applySdConfig(cfg) {
    if (!cfg) return;
    if (cfg.steps != null) sdConfig.steps = cfg.steps;
    if (cfg.refinerSteps != null) sdConfig.refinerSteps = cfg.refinerSteps;
    if (cfg.cfg != null) sdConfig.cfg = cfg.cfg;
    if (cfg.refinerCfg != null) sdConfig.refinerCfg = cfg.refinerCfg;
    if (cfg.hires != null) sdConfig.hires = cfg.hires;
    if (cfg.style) sdConfig.style = cfg.style;
    if (cfg.sampler) sdConfig.sampler = cfg.sampler;
    if (cfg.scheduler) sdConfig.scheduler = cfg.scheduler;
    if (cfg.refinerSampler) sdConfig.refinerSampler = cfg.refinerSampler;
    if (cfg.refinerScheduler) sdConfig.refinerScheduler = cfg.refinerScheduler;
    if (cfg.model) sdConfig.model = cfg.model;
    if (cfg.refinerModel) sdConfig.refinerModel = cfg.refinerModel;
    if (cfg.denoisingStrength != null) sdConfig.denoisingStrength = cfg.denoisingStrength;
    if (cfg.width != null) sdConfig.width = cfg.width;
    if (cfg.height != null) sdConfig.height = cfg.height;
    if (cfg.imageCount != null) sdConfig.imageCount = cfg.imageCount;
    if (cfg.loras) sdConfig.loras = cfg.loras;
    if (cfg.seed != null && cfg.seed >= 0) sdConfig.seed = cfg.seed;
}

async function tryResumeExistingBook(id) {
    let existingScenes;
    try {
        existingScenes = await loadPictureBook(id);
    } catch (e) {
        return;
    }
    if (!existingScenes || !existingScenes.length) return;

    bookObjectId = id;
    metaScenes = existingScenes;
    scenes = existingScenes;

    // U2: the resume path lands on step 4/5 but a "Back" to step 3 renders the Manage Characters
    // screen — which is empty/stale unless the character manager is initialized against this book
    // (the fresh Step 2→3 path already calls this; the resume path previously did not).
    try {
        await initCharacterManager(bookObjectId);
    } catch (e) { /* non-fatal — Step 3 can still be opened, it'll just re-fetch */ }

    try {
        let savedSdConfig = await getBookSdConfig(id);
        if (savedSdConfig) applySdConfig(savedSdConfig);
    } catch (e) { /* non-fatal — wizard defaults still apply */ }

    existingScenes.forEach(function (s) {
        if (!s.objectId) return;
        if (s.status) genProgress[s.objectId] = s.status;
        else if (s.imageObjectId) genProgress[s.objectId] = 'done';
        if (s.error) sceneErrors[s.objectId] = s.error;
    });

    let allResolved = existingScenes.every(function (s) {
        let st = s.objectId ? genProgress[s.objectId] : null;
        return st === 'accepted' || st === 'skipped';
    });

    if (allResolved) {
        try { step5ImageUrls = await resolveAllImageUrls(existingScenes); } catch (e) { /* non-fatal */ }
        step = 5;
    } else {
        step = 4;
    }
}

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

    // Look up system defaults from the shared library (async — UI renders "Loading default..." meanwhile)
    loadDefaults();

    // Resume detection: the passed id may actually be an existing book's group objectId rather
    // than a fresh source document — see tryResumeExistingBook().
    await tryResumeExistingBook(workObjectId);

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

// Test-only seam: getPromptTemplate() is pure w.r.t. this module's own mutable state
// (promptMode/promptTemplate/promptTemplates), which real usage only ever changes via UI
// interaction. Exporting it plus a minimal setter lets KI-31-follow-up's regression test drive the
// actual function directly, rather than re-implementing its logic in the test or mounting the
// entire multi-step wizard component just to reach one internal branch.
export function __setPromptStateForTest(mode, single, perPrompt) {
    promptMode = mode;
    promptTemplate = single;
    if (perPrompt) Object.assign(promptTemplates, perPrompt);
}
export { getPromptTemplate };
