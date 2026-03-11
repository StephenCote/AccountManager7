import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';

/**
 * sceneExtractor — LLM pipeline utilities for Picture Book.
 * Shared by the pictureBook workflow wizard and the picture-book feature route.
 */

const DEFAULT_SD_CONFIG = {
    steps: 20,
    refinerSteps: 20,
    cfg: 5,
    hires: false,
    style: 'illustration'
};

const MAX_SCENES_DEFAULT = 3;

function pbBase() {
    return applicationPath + '/rest/olio/picture-book';
}

/**
 * Extract scenes only (no character creation). Returns raw scene JSON array.
 * @param {string} workObjectId
 * @param {string|null} chatConfigName
 * @param {number} count
 * @returns {Promise<Array>}
 */
async function extractScenes(workObjectId, chatConfigName, count) {
    let body = { count: count || MAX_SCENES_DEFAULT };
    if (chatConfigName) body.chatConfig = chatConfigName;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/extract-scenes-only', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + am7client.getToken() },
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Extract scenes failed: ' + resp.status);
    return resp.json();
}

/**
 * Full extraction — scenes + characters + charPerson creation.
 * Returns .pictureBookMeta JSON.
 */
async function fullExtract(workObjectId, chatConfigName, count, genre) {
    let body = { count: count || MAX_SCENES_DEFAULT };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (genre) body.genre = genre;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/extract', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + am7client.getToken() },
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Full extract failed: ' + resp.status);
    return resp.json();
}

/**
 * Generate SD image for one scene.
 * @param {string} sceneObjectId
 * @param {object|null} sdConfig  overrides — merged with DEFAULT_SD_CONFIG
 * @param {string|null} chatConfigName
 * @param {string|null} promptOverride  skip LLM prompt build if set
 * @returns {Promise<{imageObjectId: string}>}
 */
async function generateSceneImage(sceneObjectId, sdConfig, chatConfigName, promptOverride) {
    let body = { sdConfig: Object.assign({}, DEFAULT_SD_CONFIG, sdConfig || {}) };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (promptOverride) body.promptOverride = promptOverride;
    let resp = await fetch(pbBase() + '/scene/' + sceneObjectId + '/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + am7client.getToken() },
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Scene image generation failed: ' + resp.status);
    return resp.json();
}

/**
 * Regenerate scene blurb via LLM.
 * @returns {Promise<{blurb: string}>}
 */
async function regenerateBlurb(sceneObjectId, chatConfigName) {
    let body = {};
    if (chatConfigName) body.chatConfig = chatConfigName;
    let resp = await fetch(pbBase() + '/scene/' + sceneObjectId + '/blurb', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + am7client.getToken() },
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Blurb regeneration failed: ' + resp.status);
    return resp.json();
}

/**
 * Load ordered scene list from .pictureBookMeta.
 * @returns {Promise<Array>}
 */
async function loadPictureBook(workObjectId) {
    let resp = await fetch(pbBase() + '/' + workObjectId + '/scenes', {
        headers: { 'Authorization': 'Bearer ' + am7client.getToken() }
    });
    if (!resp.ok) throw new Error('Load picture book failed: ' + resp.status);
    return resp.json();
}

/**
 * Reorder scenes.
 * @param {string} workObjectId
 * @param {string[]} orderedObjectIds
 */
async function reorderScenes(workObjectId, orderedObjectIds) {
    let resp = await fetch(pbBase() + '/' + workObjectId + '/scenes/order', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + am7client.getToken() },
        body: JSON.stringify({ scenes: orderedObjectIds })
    });
    if (!resp.ok) throw new Error('Reorder failed: ' + resp.status);
    return resp.json();
}

/**
 * Reset (delete Scenes/ and Characters/ groups + meta).
 */
async function resetPictureBook(workObjectId) {
    let resp = await fetch(pbBase() + '/' + workObjectId + '/reset', {
        method: 'DELETE',
        headers: { 'Authorization': 'Bearer ' + am7client.getToken() }
    });
    if (!resp.ok) throw new Error('Reset failed: ' + resp.status);
    return resp.json();
}

/**
 * Build .pictureBookMeta structure from scene array (client-side helper).
 */
function buildMeta(workObjectId, workName, scenes) {
    return {
        workObjectId,
        workName: workName || '',
        sceneCount: scenes.length,
        scenes: scenes.map((s, i) => ({
            objectId: s.objectId || null,
            index: i,
            title: s.title || 'Scene ' + i,
            imageObjectId: s.imageObjectId || null,
            characters: s.characters || []
        })),
        extractedAt: new Date().toISOString(),
        generatedAt: null
    };
}

export {
    DEFAULT_SD_CONFIG,
    MAX_SCENES_DEFAULT,
    extractScenes,
    fullExtract,
    generateSceneImage,
    regenerateBlurb,
    loadPictureBook,
    reorderScenes,
    resetPictureBook,
    buildMeta
};
