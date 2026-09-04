import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';

/**
 * sceneExtractor — LLM pipeline utilities for Picture Book.
 * Shared by the pictureBook workflow wizard and the picture-book feature route.
 */

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
 * Create book from user-curated scenes — creates book group, scene notes, characters, meta.
 * @param {string} workObjectId - source document objectId
 * @param {string|null} chatConfigName
 * @param {string|null} genre
 * @param {string|null} bookName
 * @param {Array} sceneList - user-curated scenes from Step 2
 * @param {Array|null} characters - user-edited character data from Step 3
 * @param {string|null} pb2BookObjectId - optional PB2 olio.pb.book objectId; when provided the
 *   server links the PB1 group to the existing PB2 universe/world and returns the pb2BookObjectId
 *   in the response so the caller can use it as the canonical book identity.
 * @returns {Promise<Object>} meta with bookObjectId (and pb2BookObjectId when linked)
 */
async function createFromScenes(workObjectId, chatConfigName, genre, bookName, sceneList, characters, pb2BookObjectId) {
    let body = { schema: 'olio.pictureBookRequest', sceneList: sceneList };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (genre) body.genre = genre;
    if (bookName) body.bookName = bookName;
    if (characters && characters.length) body.characters = characters;
    if (pb2BookObjectId) body.pb2BookObjectId = pb2BookObjectId;
    let resp = await fetch(pbBase() + '/' + workObjectId + '/create-from-scenes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) {
        let body = null;
        try { body = await resp.json(); } catch (_) { try { body = { message: await resp.text() }; } catch (_2) {} }
        let errMsg = (body && (body.error || body.message)) || resp.status;
        if (body && body.cause && body.cause !== errMsg) errMsg = errMsg + ' (cause: ' + body.cause + ')';
        throw new Error('Create from scenes failed: ' + errMsg);
    }
    return resp.json();
}

/**
 * Create a new PB2 olio.pb.book + universe + world via the /chapter endpoint.
 * This is the first step in the wizard's Step-2 Continue path: the PB2 record must
 * exist before createFromScenes is called so the server can link the PB1 group into
 * the PB2 universe/world rather than leaving characters in a PB1-only group.
 * @param {string} slug - URL-safe identifier (e.g. 'my-story-2026')
 * @param {string} title - human-readable title
 * @returns {Promise<{bookObjectId: string, slug: string}>}
 */
async function createChapBookRecord(slug, title) {
    let body = { slug: slug, title: title };
    let resp = await fetch(pbBase() + '/chapter', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Create ChapBook record failed: ' + resp.status);
    return resp.json();
}

/**
 * Generate SD image for one scene. All SD generation params now live ON a real olio.sd.config
 * record: the book's COMMON config (opts.sdConfig) plus an optional SPARSE per-scene DELTA
 * (opts.sdConfigOverride) the backend overlays via SDUtil.applyOverrides. An optional ALTERNATE
 * config for the composite/Kontext step is sent as opts.compositeSdConfig. Each of these must be a
 * full olio.sd.config entity (carrying `schema:'olio.sd.config'`) so the server types it as a
 * BaseRecord; anything without a schema is silently ignored by the transport layer.
 * @param {string} sceneObjectId
 * @param {object} opts
 * @param {object|null} opts.sdConfig          common olio.sd.config entity
 * @param {object|null} opts.sdConfigOverride   per-scene delta olio.sd.config entity (schema-tagged)
 * @param {object|null} opts.compositeSdConfig  optional alternate config for the composite/Kontext step
 * @param {string|null} opts.chatConfig
 * @param {string|null} opts.promptOverride     skip LLM prompt build if set
 * @param {string|null} opts.promptTemplate
 * @param {AbortSignal|null} signal  lets the caller actually cancel an in-flight request
 * @returns {Promise<{imageObjectId: string}>}
 */
async function generateSceneImage(sceneObjectId, opts, signal) {
    opts = opts || {};
    let body = { schema: 'olio.pictureBookRequest' };
    if (opts.sdConfig) body.sdConfig = opts.sdConfig;
    if (opts.sdConfigOverride) body.sdConfigOverride = opts.sdConfigOverride;
    if (opts.compositeSdConfig) body.compositeSdConfig = opts.compositeSdConfig;
    if (opts.chatConfig) body.chatConfig = opts.chatConfig;
    if (opts.promptOverride) body.promptOverride = opts.promptOverride;
    if (opts.promptTemplate) body.promptTemplate = opts.promptTemplate;
    let resp = await fetch(pbBase() + '/scene/' + sceneObjectId + '/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body), signal
    });
    if (!resp.ok) {
        let body = null;
        try { body = await resp.json(); } catch (_) { try { body = { message: await resp.text() }; } catch (_2) {} }
        throw new Error('Scene image generation failed: ' + ((body && (body.error || body.message)) || resp.status));
    }
    return resp.json();
}

/**
 * Batch-resolve (and cache) the landscape prompt for a set of scenes, then flush idle Ollama
 * models once. Call this before looping per-scene generateSceneImage() calls in a "Generate All"
 * run, so every LLM call for the batch happens before any GPU-heavy SD call — avoids a large
 * model sitting loaded in VRAM across the whole batch (see PictureBookUtil.prepareSceneImagePrompts).
 * @param {string} bookObjectId - book group objectId
 * @param {string[]} sceneObjectIds
 * @param {string|null} chatConfigName
 * @param {object|null} sdConfig  the book's COMMON olio.sd.config entity (schema-tagged) — its style
 *                                is the single seam baked into each pre-resolved landscape prompt.
 *                                Must be the full config, not a bare {style} object, or the server
 *                                won't type it as a BaseRecord and will fall back to scene defaults.
 * @param {string|null} promptTemplateOverride
 * @param {AbortSignal|null} signal  lets the caller stop awaiting this fetch when the batch is cancelled
 */
async function prepareSceneImagePrompts(bookObjectId, sceneObjectIds, chatConfigName, sdConfig, promptTemplateOverride, signal) {
    let body = { schema: 'olio.pictureBookRequest', sceneObjectIds: sceneObjectIds };
    if (chatConfigName) body.chatConfig = chatConfigName;
    if (promptTemplateOverride) body.promptTemplate = promptTemplateOverride;
    if (sdConfig) body.sdConfig = sdConfig;
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/prepare-images', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body), signal
    });
    if (!resp.ok) throw new Error('Prepare image prompts failed: ' + resp.status);
    return resp.json();
}

/**
 * Cancel an in-flight extraction / prepare-images call (KI-10). The server keys its cancel
 * registry on the exact workObjectId (extract-scenes-only / extract-chunked) or bookObjectId
 * (prepare-images) the client passed to the call being cancelled — so `key` is always a value the
 * caller already has. Returns { cancelled: true } if a matching in-flight call was signalled to
 * stop, { cancelled: false } (not an error) if nothing was in-flight for that key (already
 * finished, or the cancel raced ahead of the call being registered).
 * @param {string} key - the same workObjectId/bookObjectId passed to the call being cancelled
 * @returns {Promise<{cancelled: boolean}>}
 */
async function cancelPictureBook(key) {
    let resp = await fetch(pbBase() + '/' + key + '/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include'
    });
    if (!resp.ok) {
        let body = null;
        try { body = await resp.json(); } catch (_) { try { body = { message: await resp.text() }; } catch (_2) {} }
        throw new Error('Cancel failed: ' + ((body && (body.error || body.message)) || resp.status));
    }
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
 * Load the last-used image generation settings for a book (auto-captured server-side on every
 * scene generation), so a resumed/reopened wizard can default to the same settings instead of
 * the wizard's hardcoded defaults. Returns null if the book has never generated an image.
 * @param {string} bookObjectId - book group objectId
 * @returns {Promise<object|null>}
 */
async function getBookSdConfig(bookObjectId) {
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/settings', {
        credentials: 'include'
    });
    if (!resp.ok) return null;
    let sdConfig = await resp.json();
    return (sdConfig && Object.keys(sdConfig).length) ? sdConfig : null;
}

/**
 * Persist the book's COMMON olio.sd.config (and optional ALTERNATE composite config) once, up
 * front — the new PUT /{bookObjectId}/settings endpoint. Both records must be full olio.sd.config
 * entities carrying `schema:'olio.sd.config'` so the server types them as BaseRecords.
 * @param {string} bookObjectId - book group objectId
 * @param {object} sdConfig - common olio.sd.config entity
 * @param {object|null} compositeSdConfig - optional alternate config for the composite/Kontext step
 * @returns {Promise<object>} the stored config
 */
async function setBookSdConfig(bookObjectId, sdConfig, compositeSdConfig) {
    let body = { schema: 'olio.pictureBookRequest' };
    if (sdConfig) body.sdConfig = sdConfig;
    if (compositeSdConfig) body.compositeSdConfig = compositeSdConfig;
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Save book settings failed: ' + resp.status);
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
 * Persist a client-driven scene status (accepted/skipped/pending/...) so wizard progress
 * survives a reload/reopen. Server-driven statuses (generating/done/error) are written
 * automatically inside generateSceneImage — this is only for pure UI decisions.
 * @param {string} sceneObjectId
 * @param {string} status
 */
async function setSceneStatus(sceneObjectId, status) {
    let resp = await fetch(pbBase() + '/scene/' + sceneObjectId + '/status', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify({ status: status })
    });
    if (!resp.ok) throw new Error('Set scene status failed: ' + resp.status);
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
    // Issue 1: the backend always describes the outcome in the body. A success is { reset:true };
    // an EXPLAINED failure is { reset:false, reason:'…' } returned with HTTP 200; an exception path
    // is { error:'…' } with a non-2xx status. Parse the body even on a non-ok response so callers can
    // surface the concrete reason instead of a bare "Failed to delete". Only synthesize a
    // status-derived reason when there is no parseable body at all (e.g. a true network error).
    // Expose the HTTP status so callers can treat an already-gone book (404 {"error":"Book not
    // found"}) as an idempotent success rather than a hard failure.
    let body = null;
    try { body = await resp.json(); } catch (_) { /* no body / non-JSON */ }
    if (body && typeof body === 'object') {
        let reset = body.reset === true;
        let reason = reset ? null : (body.reason || body.error || body.message || ('Reset failed: ' + resp.status));
        return { reset: reset, reason: reason, status: resp.status };
    }
    return { reset: resp.ok, reason: resp.ok ? null : ('Reset failed: ' + resp.status), status: resp.status };
}

/**
 * List a book's extracted characters (for the "Manage Characters" review/edit screen).
 * @param {string} bookObjectId - book group objectId
 * @returns {Promise<Array>}
 */
async function listCharacters(bookObjectId) {
    let resp = await fetch(pbBase() + '/' + bookObjectId + '/characters', {
        credentials: 'include'
    });
    if (!resp.ok) throw new Error('List characters failed: ' + resp.status);
    return resp.json();
}

/**
 * Tag an apparel entry with the scene index it should first apply from (see
 * PictureBookUtil.selectSceneApparel) — used after generating a new outfit via the outfit
 * builder, to retroactively mark which scene it belongs to.
 * @param {string} characterObjectId
 * @param {string} apparelObjectId
 * @param {number} sceneIndex
 */
async function tagApparelSceneIndex(characterObjectId, apparelObjectId, sceneIndex) {
    let resp = await fetch(pbBase() + '/character/' + characterObjectId + '/apparel/' + apparelObjectId + '/scene-tag', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify({ sceneIndex: sceneIndex })
    });
    if (!resp.ok) throw new Error('Scene-tag apparel failed: ' + resp.status);
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
    MAX_SCENES_DEFAULT,
    extractScenes,
    createFromScenes,
    createChapBookRecord,
    generateSceneImage,
    prepareSceneImagePrompts,
    cancelPictureBook,
    regenerateBlurb,
    loadPictureBook,
    getBookSdConfig,
    setBookSdConfig,
    reorderScenes,
    setSceneStatus,
    resetPictureBook,
    listCharacters,
    tagApparelSceneIndex,
    buildMeta,
    resolveImageUrl,
    resolveAllImageUrls,
    clearImageCache,
    buildImageUrl
};
