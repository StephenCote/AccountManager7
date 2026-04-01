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

// Scene count: -1 = no max (backend decides), positive int = explicit cap
const MAX_SCENES_DEFAULT = -1;

function pbBase() {
    return applicationPath + '/rest/olio/picture-book';
}

/**
 * Extract scenes only (no character creation). Returns raw scene JSON array.
 * @param {string} workObjectId - source document objectId
 * @param {string|null} chatConfigName
 * @param {number} count
 * @returns {Promise<Array>}
 */
async function extractScenes(workObjectId, chatConfigName, count, promptTemplateOverride) {
    let body = { schema: 'olio.pictureBookRequest' };
    if (count != null && count > 0) body.count = count;
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (promptTemplateOverride) body.promptTemplate = promptTemplateOverride;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/extract-scenes-only', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Extract scenes failed: ' + resp.status);
    return resp.json();
}

/**
 * Full extraction — scenes + characters + charPerson creation.
 * Creates ~/PictureBooks/{bookName}/ group. Returns .pictureBookMeta JSON with bookObjectId.
 * @param {string} workObjectId - source document objectId
 */
async function fullExtract(workObjectId, chatConfigName, count, genre, bookName, promptTemplateOverride) {
    let body = { schema: 'olio.pictureBookRequest' };
    if (count != null && count > 0) body.count = count;
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (promptTemplateOverride) body.promptTemplate = promptTemplateOverride;
    if (genre) body.genre = genre;
    if (bookName) body.bookName = bookName;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/extract', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Full extract failed: ' + resp.status);
    return resp.json();
}

/**
 * Chunked scene extraction — processes full text in chunks with running context.
 * Returns { sceneList: [...], extractionComplete: true, chunksProcessed: N }
 * @param {string} workObjectId - source document objectId
 * @param {string|null} chatConfigName
 * @returns {Promise<{sceneList: Array, extractionComplete: boolean, chunksProcessed: number}>}
 */
async function extractChunked(workObjectId, chatConfigName) {
    let body = { schema: 'olio.pictureBookRequest' };
    if (chatConfigName) body.chatConfig = chatConfigName;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/extract-chunked', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Chunked extract failed: ' + resp.status);
    return resp.json();
}

/**
 * Create book from user-curated scenes — creates book group, scene notes, characters, meta.
 * @param {string} workObjectId - source document objectId
 * @param {string|null} chatConfigName
 * @param {string|null} genre
 * @param {string|null} bookName
 * @param {Array} sceneList - user-curated scenes from Step 2
 * @param {Array|null} characters - user-edited character data from Step 3
 * @returns {Promise<Object>} meta with bookObjectId
 */
async function createFromScenes(workObjectId, chatConfigName, genre, bookName, sceneList, characters) {
    let body = { schema: 'olio.pictureBookRequest', sceneList: sceneList };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (genre) body.genre = genre;
    if (bookName) body.bookName = bookName;
    if (characters && characters.length) body.characters = characters;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/create-from-scenes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Create from scenes failed: ' + resp.status);
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
async function generateSceneImage(sceneObjectId, sdConfig, chatConfigName, promptOverride, promptTemplateOverride) {
    let body = { schema: 'olio.pictureBookRequest', sdConfig: Object.assign({}, DEFAULT_SD_CONFIG, sdConfig || {}) };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (promptOverride) body.promptOverride = promptOverride;
    if (promptTemplateOverride) body.promptTemplate = promptTemplateOverride;
    let resp = await fetch(pbBase() + '/scene/' + sceneObjectId + '/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
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
    let body = { schema: 'olio.pictureBookRequest' };
    if (chatConfigName) body.chatConfig = chatConfigName;
    let resp = await fetch(pbBase() + '/scene/' + sceneObjectId + '/blurb', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Blurb regeneration failed: ' + resp.status);
    return resp.json();
}

/**
 * Load ordered scene list from .pictureBookMeta.
 * @param {string} bookObjectId - book group objectId (under ~/PictureBooks/)
 * @returns {Promise<Array>}
 */
async function loadPictureBook(bookObjectId) {
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/scenes', {
        credentials: 'include'
    });
    if (!resp.ok) return [];
    return resp.json();
}

/**
 * Reorder scenes.
 * @param {string} bookObjectId - book group objectId
 * @param {string[]} orderedObjectIds
 */
async function reorderScenes(bookObjectId, orderedObjectIds) {
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/scenes/order', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify({ scenes: orderedObjectIds })
    });
    if (!resp.ok) throw new Error('Reorder failed: ' + resp.status);
    return resp.json();
}

/**
 * Reset (delete entire book group under ~/PictureBooks/).
 * @param {string} bookObjectId - book group objectId
 */
async function resetPictureBook(bookObjectId) {
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/reset', {
        method: 'DELETE',
        credentials: 'include'
    });
    if (!resp.ok) throw new Error('Reset failed: ' + resp.status);
    return resp.json();
}

// ── Image URL resolution ─────────────────────────────────────────────
// Scene meta stores imageObjectId (UUID) but media URLs require groupPath + name.
// Fetch the image record once, cache it, build URL using am7client.currentOrganization.

const imageRecordCache = {};

/**
 * Resolve an image objectId to a displayable media URL.
 * Fetches the data.data record to get groupPath + name, caches result.
 * @param {string} objectId
 * @returns {Promise<string|null>} media URL or null
 */
async function resolveImageUrl(objectId) {
    if (!objectId || typeof objectId !== 'string') return null;
    if (imageRecordCache[objectId]) return buildImageUrl(imageRecordCache[objectId]);
    try {
        // Use GET (not search) — groupPath is a virtual field computed by PathProvider,
        // not a DB column. Search query with groupPath in request causes 500.
        let rec = await new Promise(function (resolve) {
            am7client.get('data.data', objectId, function (v) { resolve(v || null); });
        });
        if (rec && typeof rec.groupPath === 'string' && typeof rec.name === 'string') {
            imageRecordCache[objectId] = rec;
            return buildImageUrl(rec);
        }
    } catch (e) {
        console.warn('resolveImageUrl failed for ' + objectId, e);
    }
    return null;
}

/**
 * Build media URL from a data.data record with groupPath + name.
 */
function buildImageUrl(rec) {
    if (!rec || typeof rec.groupPath !== 'string' || typeof rec.name !== 'string') return null;
    let org = am7client.dotPath(am7client.currentOrganization);
    if (!org) return null;
    return applicationPath + '/media/' + org + '/data.data' + rec.groupPath + '/' + rec.name;
}

/**
 * Resolve all imageObjectIds in a scenes array. Returns map: objectId → URL.
 */
async function resolveAllImageUrls(scenes) {
    let urls = {};
    let promises = scenes
        .filter(s => s.imageObjectId)
        .map(async s => {
            urls[s.imageObjectId] = await resolveImageUrl(s.imageObjectId);
        });
    await Promise.all(promises);
    return urls;
}

function clearImageCache() {
    Object.keys(imageRecordCache).forEach(k => delete imageRecordCache[k]);
}

/**
 * Build .pictureBookMeta structure from scene array (client-side helper).
 * @param {string} sourceObjectId - source document objectId
 * @param {string} bookObjectId - book group objectId
 * @param {string} workName - book display name
 * @param {Array} scenes
 */
function buildMeta(sourceObjectId, bookObjectId, workName, scenes) {
    return {
        sourceObjectId,
        bookObjectId,
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
    extractChunked,
    fullExtract,
    createFromScenes,
    generateSceneImage,
    regenerateBlurb,
    loadPictureBook,
    reorderScenes,
    resetPictureBook,
    buildMeta,
    resolveImageUrl,
    resolveAllImageUrls,
    clearImageCache,
    buildImageUrl
};
