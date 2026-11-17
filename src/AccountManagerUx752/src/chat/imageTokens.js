/**
 * Image Token Library — resolves ${image.tag1,tag2} tokens in LLM responses (ESM)
 * Full port of Ux7 am7imageTokens pattern including find-or-generate pipeline.
 *
 * Token formats:
 *   ${image.tag1,tag2}        — tag-based search, then generate if not found
 *   ${image.UUID.tag1,tag2}   — direct objectId reference + tags
 *   ${image.UUID}             — direct objectId reference only
 */
import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';
import { getOrCreateSharingTag } from '../workflows/reimage.js';

const TOKEN_REGEX = /\$\{image\.([^}]+)\}/g;
const OBJECTID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const chatImageTags = ["selfie", "nude", "intimate", "sexy", "private", "casual", "public", "professional"];

const tagToWearLevel = {
    "nude": "NONE",
    "intimate": "BASE",
    "sexy": "BASE",
    "private": "ACCENT",
    "casual": "SUIT",
    "public": "SUIT",
    "professional": "SUIT",
    "selfie": null
};

// Cache for resolved image URLs: key -> { image, url }
let cache = {};

/**
 * Parse image tokens from content string.
 * Handles UUID.tags, UUID-only, and tag-only formats (matching Ux7 dialog.js:354-385).
 */
function parse(content) {
    if (!content) return [];
    let tokens = [];
    let match;
    let regex = new RegExp(TOKEN_REGEX.source, 'g');
    while ((match = regex.exec(content)) !== null) {
        let inner = match[1];
        let parts = inner.split(".");
        let id = null;
        let tagStr;
        if (parts.length > 1 && parts[0].indexOf("-") > -1) {
            // ID + tags: ${image.UUID.tag1,tag2}
            id = parts[0];
            tagStr = parts.slice(1).join(".");
        } else if (parts.length === 1 && parts[0].indexOf("-") > -1) {
            // ID-only token (e.g., scene image): ${image.UUID}
            id = parts[0];
            tagStr = "";
        } else {
            tagStr = inner;
        }
        let tags = tagStr.split(",").map(function(t) { return t.trim(); }).filter(function(t) { return t.length > 0; });
        tokens.push({
            match: match[0],
            key: inner,
            id: id,
            tags: tags,
            start: match.index,
            end: match.index + match[0].length,
            full: match[0]
        });
    }
    return tokens;
}

function buildThumbnailUrl(obj, size) {
    if (!obj || !obj.groupPath || !obj.name) return null;
    let org = am7client.dotPath(am7client.currentOrganization);
    return applicationPath + "/thumbnail/" + org + "/data.data" + obj.groupPath + "/" + obj.name + "/" + (size || "256x256");
}

/**
 * Find existing image matching character name tag + image tags.
 * Port of Ux7 dialog.js:393-427.
 */
async function findImageForTags(character, tags) {
    if (!character || !tags || !tags.length) return null;

    let charName = character.name || ((character.firstName || "") + " " + (character.lastName || "")).trim();
    if (!charName) return null;

    // Find the character name tag
    let nameTagQ = am7client.newQuery("data.tag");
    nameTagQ.field("name", charName);
    nameTagQ.field("type", "data.data");
    nameTagQ.range(0, 1);
    let nameTagQr = await page.search(nameTagQ);
    if (!nameTagQr || !nameTagQr.results || !nameTagQr.results.length) return null;
    let nameTag = nameTagQr.results[0];

    // Get all images tagged with this character's name
    let charImages = await new Promise(function(resolve) {
        am7client.members("data.tag", nameTag.objectId, "data.data", 0, 100, function(v) { resolve(v); });
    });
    if (!charImages || !charImages.length) return null;

    let candidateIds = new Set(charImages.map(function(img) { return img.objectId; }));

    // Intersect with each requested tag's members
    for (let tagName of tags) {
        if (tagName === "selfie") continue;
        let tagQ = am7client.newQuery("data.tag");
        tagQ.field("name", tagName);
        tagQ.field("type", "data.data");
        tagQ.range(0, 1);
        let tagQr = await page.search(tagQ);
        if (!tagQr || !tagQr.results || !tagQr.results.length) {
            candidateIds.clear();
            break;
        }
        let tag = tagQr.results[0];
        let tagMembers = await new Promise(function(resolve) {
            am7client.members("data.tag", tag.objectId, "data.data", 0, 100, function(v) { resolve(v); });
        });
        if (!tagMembers || !tagMembers.length) {
            candidateIds.clear();
            break;
        }
        let memberIds = new Set(tagMembers.map(function(img) { return img.objectId; }));
        for (let cid of candidateIds) {
            if (!memberIds.has(cid)) candidateIds.delete(cid);
        }
    }

    if (candidateIds.size === 0) return null;
    let ids = Array.from(candidateIds);
    let picked = ids[Math.floor(Math.random() * ids.length)];
    return charImages.find(function(img) { return img.objectId === picked; }) || null;
}

/**
 * Generate a new image for character with given tags.
 * Port of Ux7 dialog.js:432-603.
 */
async function generateImageForTags(character, tags, options) {
    if (!character) return null;

    page.toast("info", "Generating image for " + (character.name || "character") + "...", -1);

    try {
        // Determine target wear level from tags
        let targetLevel = "SUIT";
        for (let tag of tags) {
            if (tagToWearLevel[tag] && tagToWearLevel[tag] !== null) {
                let lvl = tagToWearLevel[tag];
                let levelOrder = ["NONE", "BASE", "ACCENT", "SUIT"];
                if (levelOrder.indexOf(lvl) < levelOrder.indexOf(targetLevel)) {
                    targetLevel = lvl;
                }
            }
        }

        // Load SD config template
        let am7sd = am7model._sd;
        let entity;
        if (am7sd && am7sd.fetchTemplate) {
            entity = await am7sd.fetchTemplate(true);
        }
        if (!entity) {
            entity = am7model.newPrimitive("olio.sd.config");
        }

        // Pass landscape setting if provided
        if (options && options.landscapeSetting) {
            entity.landscapeSetting = options.landscapeSetting;
        }

        // Set style to selfie if tags include "selfie"
        if (tags.indexOf("selfie") >= 0) {
            entity.style = "selfie";
        }

        let charType = character[am7model.jsonModelKey] || "olio.charPerson";
        let charName = character.name || ((character.firstName || "") + " " + (character.lastName || "")).trim();

        // Get character record for profile info
        let charRecord = await am7client.getFull(charType, character.objectId);
        let originalPortraitId = charRecord && charRecord.profile && charRecord.profile.portrait ? charRecord.profile.portrait.id : null;
        let profileId = charRecord && charRecord.profile ? charRecord.profile.id : null;

        // Dress character to target wear level
        let originalWearStates = [];
        if (charRecord && charRecord.store && charRecord.store.objectId) {
            await am7client.clearCache("olio.store");
            let sto = await am7client.getFull("olio.store", charRecord.store.objectId);
            if (sto && sto.apparel && sto.apparel.length) {
                let activeAp = sto.apparel.find(function(a) { return a.inuse; }) || sto.apparel[0];
                await am7client.clearCache("olio.apparel");
                let app = await am7client.getFull("olio.apparel", activeAp.objectId);
                if (app && app.wearables && app.wearables.length) {
                    let wears = app.wearables;
                    let wearLevelEnum = am7model.enums.wearLevelEnumType || [];
                    let targetIdx = wearLevelEnum.indexOf(targetLevel);
                    let patches = [];
                    wears.forEach(function(w) {
                        if (w.level) {
                            originalWearStates.push({ id: w.id, inuse: w.inuse });
                            let lvl = wearLevelEnum.indexOf(w.level.toUpperCase());
                            let shouldWear = (targetLevel !== "NONE" && lvl <= targetIdx);
                            if (w.inuse !== shouldWear) {
                                patches.push({ schema: "olio.wearable", id: w.id, inuse: shouldWear });
                            }
                        }
                    });
                    if (patches.length) {
                        am7client.clearCache();
                        await Promise.all(patches.map(function(p) { return page.patchObject(p); }));
                    }
                }
            }
        }

        // Call reimage endpoint
        let image = await m.request({
            method: "POST",
            url: am7client.base() + "/olio/" + charType + "/" + character.objectId + "/reimage",
            body: entity,
            withCredentials: true
        });

        if (!image) {
            page.toast("error", "Failed to generate image");
            return null;
        }

        // Tag with sharing context
        let sharingTag = targetLevel === "NONE" ? "nude" :
                        targetLevel === "BASE" ? "intimate" : "public";
        let stag = await getOrCreateSharingTag(sharingTag, "data.data");
        if (stag) {
            await am7client.member("data.tag", stag.objectId, null, "data.data", image.objectId, true);
        }

        // Apply user-specified tags
        for (let tagName of tags) {
            if (tagName !== sharingTag) {
                let t = await getOrCreateSharingTag(tagName, "data.data");
                if (t) {
                    await am7client.member("data.tag", t.objectId, null, "data.data", image.objectId, true);
                }
            }
        }

        // Apply character name tag
        let nameTag = await getOrCreateSharingTag(charName, "data.data");
        if (nameTag) {
            await am7client.member("data.tag", nameTag.objectId, null, "data.data", image.objectId, true);
        }

        // Apply auto-tags
        await new Promise(function(resolve) {
            am7client.applyImageTags(image.objectId, function() { resolve(); });
        });

        // Restore original portrait (reimage sets it to the new image)
        if (profileId && originalPortraitId) {
            let od = { id: profileId, portrait: { id: originalPortraitId } };
            od[am7model.jsonModelKey] = "identity.profile";
            await page.patchObject(od);
        }

        // Restore original wear states
        if (originalWearStates.length) {
            let restorePatches = originalWearStates.map(function(s) {
                return { schema: "olio.wearable", id: s.id, inuse: s.inuse };
            });
            am7client.clearCache();
            await Promise.all(restorePatches.map(function(p) { return page.patchObject(p); }));
        }

        page.clearToast();
        page.toast("success", "Image generated");
        return image;
    } catch (e) {
        console.error("generateImageForTags error:", e);
        page.clearToast();
        page.toast("error", "Image generation failed");
        return null;
    }
}

/**
 * Resolve an image token: find by ID or tags, generate if needed.
 * Port of Ux7 dialog.js:605-650.
 */
async function resolve(token, character, options) {
    // If token has an ID, try to find it directly
    if (token.id) {
        if (cache[token.id]) {
            return cache[token.id];
        }
        // Try character nameTag lookup first
        let charName = character ? (character.name || ((character.firstName || "") + " " + (character.lastName || "")).trim()) : null;
        if (charName) {
            let nameTagQ = am7client.newQuery("data.tag");
            nameTagQ.field("name", charName);
            nameTagQ.field("type", "data.data");
            nameTagQ.range(0, 1);
            let nameTagQr = await page.search(nameTagQ);
            if (nameTagQr && nameTagQr.results && nameTagQr.results.length) {
                let nameTag = nameTagQr.results[0];
                let members = await new Promise(function(resolve) {
                    am7client.members("data.tag", nameTag.objectId, "data.data", 0, 100, function(v) { resolve(v); });
                });
                if (members) {
                    let img = members.find(function(mi) { return mi.objectId === token.id; });
                    if (img) {
                        let url = buildThumbnailUrl(img, "256x256");
                        let entry = { image: img, url: url };
                        cache[token.id] = entry;
                        if (token.key) cache[token.key] = entry;
                        return entry;
                    }
                }
            }
        }
        // Fallback: direct data lookup
        try {
            let directImg = await am7client.getFull("data.data", token.id);
            if (directImg) {
                let url = buildThumbnailUrl(directImg, "256x256");
                if (url) {
                    let entry = { image: directImg, url: url };
                    cache[token.id] = entry;
                    if (token.key) cache[token.key] = entry;
                    return entry;
                }
            }
        } catch (e) { /* ignore lookup failure */ }
        return null;
    }

    // Tag-based: find existing, then generate
    let image = await findImageForTags(character, token.tags);
    if (!image) {
        image = await generateImageForTags(character, token.tags, options);
    }
    if (image) {
        let url = buildThumbnailUrl(image, "256x256");
        let entry = { image: image, url: url };
        cache[image.objectId] = entry;
        // Also cache by token key so ChatTokenRenderer.processImageTokens can find it
        if (token.key) cache[token.key] = entry;
        return entry;
    }
    return null;
}

/**
 * Process content string, replacing image tokens with HTML.
 * Initiates async resolution for unresolved tokens.
 */
function processContent(content) {
    if (!content) return content;
    let tokens = parse(content);
    if (tokens.length === 0) return content;

    let result = content;
    for (let i = tokens.length - 1; i >= 0; i--) {
        let token = tokens[i];
        let cacheKey = token.id || token.key;
        let cached = cache[cacheKey];

        if (cached && cached.url) {
            let img = '<img src="' + cached.url + '" class="inline-block max-w-[256px] max-h-[256px] rounded my-1" alt="' + token.key + '" />';
            result = result.substring(0, token.start) + img + result.substring(token.end);
        } else {
            let placeholder = '<span class="inline-block w-16 h-16 bg-gray-200 dark:bg-gray-700 rounded animate-pulse" title="Loading image..."></span>';
            result = result.substring(0, token.start) + placeholder + result.substring(token.end);
        }
    }

    return result;
}

// ── Image action notes (aiDocs/ChatWardrobeTokensDesign.md §3.4b) ────────
//
// A resolved image token in history says nothing to the LLM about what was shared. The resolver
// appends a short system-written note IMMEDIATELY AFTER the token:
//   ${image.<oid>.selfie,casual}<private>[image] Rebecca shares a picture of herself (selfie, casual): <caption></private>
// Appended, never wrapped: ChatTokenRenderer strips <private> BEFORE resolving tokens, so a wrapped
// token would vanish from display. The server keeps <private> in the stored content (it strips it only
// from the ephemeral displayContent, ChatUtil.populateDisplayFields), so the note reaches the model on
// every later turn while staying hidden in the chat. The "[image]" marker distinguishes the system's
// notes from the model's own <private> notes and makes annotation idempotent.

const IMAGE_NOTE_PREFIX = "<private>[image]";
const NOTE_CAPTION_MAX = 240;

function escapeRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** True when this exact token occurrence is already followed by a system image note. */
function hasImageNote(content, tokenStr) {
    if (!content || !tokenStr) return false;
    let re = new RegExp(escapeRegex(tokenStr) + "\\s*" + escapeRegex(IMAGE_NOTE_PREFIX));
    return re.test(content);
}

/** True when some occurrence of tokenStr in content still lacks a note. */
function needsImageNote(content, tokenStr) {
    if (!content || !tokenStr) return false;
    let re = new RegExp(escapeRegex(tokenStr) + "(?!\\s*" + escapeRegex(IMAGE_NOTE_PREFIX) + ")");
    return re.test(content);
}

/**
 * Replace the first occurrence of `from` that is NOT already followed by a note with `to`.
 * Returns the new content, or the original content unchanged when every occurrence is annotated.
 */
function replaceUnannotated(content, from, to) {
    if (!content || !from) return content;
    let re = new RegExp(escapeRegex(from) + "(?!\\s*" + escapeRegex(IMAGE_NOTE_PREFIX) + ")");
    return content.replace(re, function() { return to; });
}

function reflexive(character) {
    let g = character && character.gender ? String(character.gender).toLowerCase() : "";
    if (g === "female" || g === "f") return "herself";
    if (g === "male" || g === "m") return "himself";
    return "themselves";
}

/**
 * Build the note text. Pure (no I/O). caption may be null; the tags then carry the meaning
 * (the "I pose for you" fallback, written in the third person so it reads the same in user and
 * assistant turns).
 */
function buildImageNote(character, tags, caption) {
    let name = character ? (character.firstName || character.name || "") : "";
    name = String(name).trim() || "They";
    let tagList = (tags || []).filter(function(t) { return t && t.length; });
    let note = IMAGE_NOTE_PREFIX + " " + name + " shares a picture of " + reflexive(character);
    if (tagList.length) note += " (" + tagList.join(", ") + ")";
    let cap = caption ? String(caption).replace(/\s+/g, " ").replace(/[<>]/g, "").trim() : "";
    if (cap && !/^(null|n\/a|none|unknown)$/i.test(cap)) {
        if (cap.length > NOTE_CAPTION_MAX) cap = cap.substring(0, NOTE_CAPTION_MAX - 1).trim() + "…";
        note += ": " + cap;
    }
    else if (tagList.length) {
        note += ", posing for the camera";
    }
    return note + ".</private>";
}

async function readDescription(objectId) {
    let q = am7client.newQuery("data.data");
    q.field("objectId", objectId);
    q.cache(false);
    q.entity.request.push("id", "objectId", "description");
    let qr = await page.search(q);
    let rec = qr && qr.results && qr.results.length ? qr.results[0] : null;
    let d = rec ? rec.description : null;
    return (d && String(d).trim().length) ? String(d).trim() : null;
}

/**
 * Auto-tagger caption for an image. Reads data.data.description fresh (the resolver's image object
 * can predate the caption); when it is empty, runs GET /tag/apply/{id}, which stores the caption
 * on description (TagService.applyImageTags), and reads again. Never throws: returns null when the
 * record is unreadable or the tag service is unavailable.
 */
async function describeImage(objectId) {
    if (!objectId) return null;
    try {
        let d = await readDescription(objectId);
        if (d) return d;
        await new Promise(function(resolve) {
            let done = false;
            let finish = function() { if (!done) { done = true; resolve(); } };
            try { am7client.applyImageTags(objectId, finish); } catch (e) { finish(); }
            setTimeout(finish, 60000);
        });
        return await readDescription(objectId);
    } catch (e) {
        console.warn("describeImage failed for " + objectId, e);
        return null;
    }
}

const am7imageTokens = {
    tags: chatImageTags,
    noteMarker: IMAGE_NOTE_PREFIX,
    hasImageNote,
    needsImageNote,
    replaceUnannotated,
    buildImageNote,
    describeImage,
    tagToWearLevel: tagToWearLevel,
    parse,
    processContent,
    resolve,
    findForTags: findImageForTags,
    generateForTags: generateImageForTags,
    cache,
    buildThumbnailUrl,
    clearCache: function() {
        for (let k in cache) delete cache[k];
    }
};

export { am7imageTokens };
export default am7imageTokens;
