/**
 * storage.js — Card Game data persistence (ESM)
 * Port of Ux7 client/view/cardGame/state/storage.js
 *
 * Provides deck, game save, and campaign storage using data.data records.
 */
import { am7model } from '../../core/model.js';
import { am7view } from '../../core/view.js';
import { splitGameDef, resolveGameConfig } from './gameDefTransform.js';

function getPage() { return am7model._page; }
function getClient() { return am7model._client; }

// ── data.data CRUD Helpers ────────────────────────────────────────
const DECK_BASE_PATH = "~/CardGame";

function encodeJson(data) {
    return btoa(unescape(encodeURIComponent(JSON.stringify(data))));
}
function decodeJson(encoded) {
    return JSON.parse(decodeURIComponent(escape(atob(encoded))));
}

async function upsertDataRecord(groupPath, fileName, data) {
    let page = getPage();
    let grp = await page.makePath("auth.group", "DATA", groupPath);
    if (!grp) { console.error("[CardGame] Could not create group:", groupPath); return null; }
    let encoded = encodeJson(data);

    // The presence-check search below caches its result; if the record doesn't
    // yet exist, an empty-results entry lands in the SQLite cache and the very
    // next load of the same record returns null. Wipe the type cache first so
    // both the search and the immediately-following load hit the server fresh.
    getClient().clearCache("data.data");

    let q = am7view.viewQuery("data.data");
    q.field("groupId", grp.id);
    q.field("name", fileName);
    let qr = await page.search(q);

    let result;
    if (qr?.results?.length) {
        let existing = qr.results[0];
        existing.dataBytesStore = encoded;
        existing.compressionType = "NONE";
        result = await page.patchObject(existing);
    } else {
        let obj = am7model.newPrimitive("data.data");
        obj.name = fileName;
        obj.contentType = "application/json";
        obj.compressionType = "NONE";
        obj.groupId = grp.id;
        obj.groupPath = grp.path;
        obj.dataBytesStore = encoded;
        result = await page.createObject(obj);
    }

    // Invalidate the now-stale empty-results cache entry from the presence check
    // so the post-save load sees the record we just created/updated.
    getClient().clearCache("data.data");
    return result;
}

async function loadDataRecord(groupPath, fileName, createGroup) {
    let page = getPage();
    let grp = createGroup
        ? await page.makePath("auth.group", "DATA", groupPath)
        : await page.findObject("auth.group", "DATA", groupPath);
    if (!grp) return null;

    let q = am7view.viewQuery("data.data");
    q.field("groupId", grp.id);
    q.field("name", fileName);
    if (q.entity.request.indexOf("dataBytesStore") === -1) {
        q.entity.request.push("dataBytesStore");
    }
    let qr = await page.search(q);
    if (qr?.results?.length && qr.results[0].dataBytesStore) {
        return decodeJson(qr.results[0].dataBytesStore);
    }
    return null;
}

async function listDataRecords(groupPath) {
    let page = getPage();
    let grp = await page.findObject("auth.group", "DATA", groupPath);
    if (!grp) return [];
    let q = am7view.viewQuery("data.data");
    q.field("groupId", grp.id);
    let qr = await page.search(q);
    return qr?.results || [];
}

// ── Game Definition Storage ───────────────────────────────────────
// A card GAME definition (play-time knobs: narration/voice/announcer/poker-face/
// banter) persisted as `game.json` under the SAME deck group as `deck.json`, so a
// deck and its game config share one directory. The game record references its deck
// by a STABLE id — the deck group's `objectId` (deckRef) — never the mutable
// deckName, so a rename can't orphan the link.
const gameDefStorage = {
    async save(deckName, data) {
        try {
            let toSave = Object.assign({ version: 1 }, data || {});
            // Stamp the stable deckRef (deck group objectId) when the caller didn't
            // supply one. makePath is create-or-get, so this is safe on save.
            if (!toSave.deckRef) {
                let grp = await getPage().makePath("auth.group", "DATA", DECK_BASE_PATH + "/" + deckName);
                if (grp && grp.objectId) toSave.deckRef = grp.objectId;
            }
            let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "game.json", toSave);
            console.log("[CardGame] Game def saved:", deckName);
            return saved;
        } catch (e) {
            console.error("[CardGame] Failed to save game def:", deckName, e);
            return null;
        }
    },

    async load(deckName) {
        try {
            return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "game.json", false);
        } catch (e) {
            console.error("[CardGame] Failed to load game def:", deckName, e);
            return null;
        }
    }
};

// Non-destructive, read-time migration (modeled on migrateCampaign): when a legacy
// deck still carries inline `gameConfig` and no `game.json` exists yet, write
// `game.json` once (extracting gameConfig + a stable deckRef). NEVER delete
// `deck.json`; `deck.gameConfig` is left intact for this session as a one-release
// fallback, and stripped from the deck blob on the next deckStorage.save.
async function migrateInlineGameConfig(deckName, deck) {
    if (!deck || !deck.gameConfig || Object.keys(deck.gameConfig).length === 0) return;
    try {
        let existing = await gameDefStorage.load(deckName);
        if (existing && existing.gameConfig && Object.keys(existing.gameConfig).length > 0) return;
        let grp = await getPage().findObject("auth.group", "DATA", DECK_BASE_PATH + "/" + deckName);
        let deckRef = grp && grp.objectId ? grp.objectId : null;
        let split = splitGameDef(deck, deckRef);
        await gameDefStorage.save(deckName, split.gameDef);
        console.log("[CardGame] Migrated inline gameConfig -> game.json for deck:", deckName);
    } catch (e) {
        console.warn("[CardGame] gameConfig migration failed for deck:", deckName, e);
    }
}

// ── Deck Storage ──────────────────────────────────────────────────
const deckStorage = {
    async save(deckName, data) {
        try {
            let toSave = data;
            // Strip game concerns out of the persisted deck blob. Seed game.json from
            // the inline config only if it isn't already present (never clobber newer
            // edits saved via the Game Config panel). The caller's object is NOT
            // mutated, so an in-memory deck keeps gameConfig as a session fallback.
            if (data && data.gameConfig && Object.keys(data.gameConfig).length > 0) {
                try {
                    let existing = await gameDefStorage.load(deckName);
                    if (!existing || !existing.gameConfig || Object.keys(existing.gameConfig).length === 0) {
                        await gameDefStorage.save(deckName, { gameConfig: data.gameConfig });
                    }
                } catch (e) {
                    console.warn("[CardGame] game def seed during deck save failed:", deckName, e);
                }
                toSave = Object.assign({}, data);
                delete toSave.gameConfig;
            }
            let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", toSave);
            console.log("[CardGame] Deck saved:", deckName);
            return saved;
        } catch (e) {
            console.error("[CardGame] Failed to save deck:", deckName, e);
            return null;
        }
    },

    async load(deckName) {
        try {
            let deck = await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", false);
            if (deck) await migrateInlineGameConfig(deckName, deck);
            return deck;
        } catch (e) {
            console.error("[CardGame] Failed to load deck:", deckName, e);
            return null;
        }
    },

    async list() {
        try {
            let page = getPage();
            let grp = await page.makePath("auth.group", "DATA", DECK_BASE_PATH);
            if (!grp) return [];
            let q = am7view.viewQuery("auth.group");
            q.field("parentId", grp.id);
            q.range(0, 200);
            let qr = await page.search(q);
            let names = (qr?.results || []).map(g => g.name);
            return [...new Set(names)];
        } catch (e) {
            console.error("[CardGame] Failed to list decks:", e);
            return [];
        }
    },

    async remove(deckName) {
        try {
            let page = getPage();
            let grp = await page.findObject("auth.group", "DATA", DECK_BASE_PATH + "/" + deckName);
            if (!grp) return false;
            await page.deleteObject("auth.group", grp.objectId);
            console.log("[CardGame] Deck removed:", deckName);
            return true;
        } catch (e) {
            console.error("[CardGame] Failed to remove deck:", deckName, e);
            return false;
        }
    }
};

// ── Game Save Storage ─────────────────────────────────────────────

function serializeGameState(state) {
    let s = JSON.parse(JSON.stringify(state));
    delete s.turnTimer;
    delete s.narrationText;
    delete s.narrationTime;
    return s;
}

function deserializeGameState(saveData) {
    let state = saveData.gameState;
    state.turnTimer = null;
    state.narrationText = null;
    state.narrationTime = null;
    state.isPaused = false;
    return state;
}

const gameStorage = {
    _savesPath(deckName) { return DECK_BASE_PATH + "/" + deckName + "/saves"; },

    async save(deckName, state) {
        try {
            let page = getPage();
            let saveData = {
                version: 1,
                timestamp: new Date().toISOString(),
                deckName,
                roundNumber: state.round,
                phase: state.phase,
                gameState: serializeGameState(state)
            };
            let groupPath = this._savesPath(deckName);
            let grp = await page.makePath("auth.group", "data", groupPath);
            if (!grp) { console.error("[CardGame] gameStorage.save: could not create group:", groupPath); return null; }

            let obj = am7model.newPrimitive("data.data");
            obj.name = "save-" + Date.now() + ".json";
            obj.contentType = "application/json";
            obj.compressionType = "none";
            obj.groupId = grp.id;
            obj.groupPath = grp.path;
            obj.dataBytesStore = encodeJson(saveData);
            let saved = await page.createObject(obj);

            await this.cleanupOldSaves(deckName, 3);
            return saved;
        } catch (e) {
            console.error("[CardGame] Failed to save game:", deckName, e);
            return null;
        }
    },

    async load(deckName) {
        try {
            let saves = await this.list(deckName);
            if (saves.length === 0) return null;
            let latest = saves[0];
            return await loadDataRecord(this._savesPath(deckName), latest.name, false);
        } catch (e) {
            console.error("[CardGame] Failed to load game save:", deckName, e);
            return null;
        }
    },

    async list(deckName) {
        try {
            let records = await listDataRecords(this._savesPath(deckName));
            return records
                .filter(r => r.name?.startsWith("save-"))
                .sort((a, b) => b.name.localeCompare(a.name));
        } catch (e) {
            console.error("[CardGame] Failed to list saves:", deckName, e);
            return [];
        }
    },

    async deleteAll(deckName) {
        try {
            let page = getPage();
            let client = getClient();
            let saves = await this.list(deckName);
            for (let save of saves) {
                await page.deleteObject("data.data", save.objectId);
            }
            if (client) client.clearCache("data.data");
        } catch (e) {
            console.warn("[CardGame] Failed to delete saves:", deckName, e);
        }
    },

    async cleanupOldSaves(deckName, keepCount) {
        try {
            let page = getPage();
            let saves = await this.list(deckName);
            for (let i = keepCount; i < saves.length; i++) {
                await page.deleteObject("data.data", saves[i].objectId);
            }
        } catch (e) {
            console.warn("[CardGame] Save cleanup failed:", e);
        }
    }
};

// ── Campaign Storage ──────────────────────────────────────────────

function createCampaignData(characterCard) {
    return {
        version: 3,
        characterId: characterCard.sourceId || characterCard._tempId || null,
        characterName: characterCard.name || "Unknown",
        totalGamesPlayed: 0,
        wins: 0,
        losses: 0,
        xp: 0,
        level: 1,
        totalXpEarned: 0,
        statGains: { STR: 0, AGI: 0, END: 0, INT: 0, MAG: 0, CHA: 0 },
        pendingLevelUps: 0
    };
}

function migrateCampaign(campaign) {
    if (campaign.xp === undefined) campaign.xp = 0;
    if (campaign.level === undefined) campaign.level = 1;
    if (campaign.totalXpEarned === undefined) campaign.totalXpEarned = 0;
    if (!campaign.statGains) campaign.statGains = { STR: 0, AGI: 0, END: 0, INT: 0, MAG: 0, CHA: 0 };
    if (campaign.pendingLevelUps === undefined) campaign.pendingLevelUps = 0;
    campaign.version = 3;
    return campaign;
}

const campaignStorage = {
    async save(deckName, campaignData) {
        try {
            let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "campaign.json", campaignData);
            console.log("[CardGame] Campaign saved:", deckName);
            return saved;
        } catch (e) {
            console.error("[CardGame] Failed to save campaign:", deckName, e);
            return null;
        }
    },

    async load(deckName) {
        try {
            return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "campaign.json", false);
        } catch (e) {
            console.error("[CardGame] Failed to load campaign:", deckName, e);
            return null;
        }
    }
};

async function saveCampaignProgress(state, isVictory) {
    let deckName = state.deckName;
    let campaign = await campaignStorage.load(deckName);
    if (!campaign) {
        campaign = createCampaignData(state.player.character);
    }
    migrateCampaign(campaign);

    campaign.totalGamesPlayed++;
    if (isVictory) campaign.wins++;
    else campaign.losses++;

    let gameXp = state.player.totalGameXp || 0;
    let hpBonus = Math.max(0, state.player.hp) * 2;
    let victoryBonus = isVictory ? 50 : 0;
    let totalEarned = gameXp + hpBonus + victoryBonus;

    campaign.xp += totalEarned;
    campaign.totalXpEarned += totalEarned;

    let levelUpThreshold = campaign.level * 100;
    while (campaign.xp >= levelUpThreshold && campaign.level < 10) {
        campaign.xp -= levelUpThreshold;
        campaign.level++;
        campaign.pendingLevelUps++;
        levelUpThreshold = campaign.level * 100;
    }

    campaign._lastGameXp = totalEarned;
    await campaignStorage.save(deckName, campaign);
    return campaign;
}

// ── Export ─────────────────────────────────────────────────────────
const storage = {
    DECK_BASE_PATH,
    encodeJson, decodeJson,
    upsertDataRecord, loadDataRecord, listDataRecords,
    deckStorage, gameStorage, gameDefStorage, campaignStorage,
    createCampaignData, migrateCampaign, saveCampaignProgress,
    migrateInlineGameConfig, splitGameDef, resolveGameConfig,
    serializeGameState, deserializeGameState
};

export { storage, DECK_BASE_PATH, deckStorage, gameStorage, gameDefStorage, campaignStorage,
    encodeJson, decodeJson, upsertDataRecord, loadDataRecord, listDataRecords,
    createCampaignData, migrateCampaign, saveCampaignProgress,
    migrateInlineGameConfig, splitGameDef, resolveGameConfig,
    serializeGameState, deserializeGameState };
export default storage;
