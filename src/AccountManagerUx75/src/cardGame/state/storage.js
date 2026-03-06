/**
 * storage.js — Card Game data persistence (ESM)
 * Port of Ux7 client/view/cardGame/state/storage.js
 *
 * Provides deck, game save, and campaign storage using data.data records.
 */
import { am7model } from '../../core/model.js';
import { am7view } from '../../core/view.js';

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

    let q = am7view.viewQuery("data.data");
    q.field("groupId", grp.id);
    q.field("name", fileName);
    let qr = await page.search(q);

    if (qr?.results?.length) {
        let existing = qr.results[0];
        existing.dataBytesStore = encoded;
        existing.compressionType = "NONE";
        return await page.patchObject(existing);
    }
    let obj = am7model.newPrimitive("data.data");
    obj.name = fileName;
    obj.contentType = "application/json";
    obj.compressionType = "NONE";
    obj.groupId = grp.id;
    obj.groupPath = grp.path;
    obj.dataBytesStore = encoded;
    return await page.createObject(obj);
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

// ── Deck Storage ──────────────────────────────────────────────────
const deckStorage = {
    async save(deckName, data) {
        try {
            let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", data);
            console.log("[CardGame] Deck saved:", deckName);
            return saved;
        } catch (e) {
            console.error("[CardGame] Failed to save deck:", deckName, e);
            return null;
        }
    },

    async load(deckName) {
        try {
            return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", false);
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
    deckStorage, gameStorage, campaignStorage,
    createCampaignData, migrateCampaign, saveCampaignProgress,
    serializeGameState, deserializeGameState
};

export { storage, DECK_BASE_PATH, deckStorage, gameStorage, campaignStorage,
    encodeJson, decodeJson, upsertDataRecord, loadDataRecord, listDataRecords,
    createCampaignData, migrateCampaign, saveCampaignProgress,
    serializeGameState, deserializeGameState };
export default storage;
