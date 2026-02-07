(function() {
    "use strict";

    // ── data.data CRUD Helpers ────────────────────────────────────────
    // Shared utilities for all storage objects (deck, game saves, campaign)
    const DECK_BASE_PATH = "~/CardGame";

    function encodeJson(data) {
        return btoa(unescape(encodeURIComponent(JSON.stringify(data))));
    }
    function decodeJson(encoded) {
        return JSON.parse(decodeURIComponent(escape(atob(encoded))));
    }

    // Upsert a named data.data record in a group (create-or-update pattern)
    async function upsertDataRecord(groupPath, fileName, data) {
        let grp = await page.makePath("auth.group", "DATA", groupPath);
        if (!grp) { console.error("[CardGame v2] Could not create group:", groupPath); return null; }
        let encoded = encodeJson(data);

        let q = am7view.viewQuery("data.data");
        q.field("groupId", grp.id);
        q.field("name", fileName);
        let qr = await page.search(q);

        if (qr?.results?.length) {
            let existing = qr.results[0];
            existing.dataBytesStore = encoded;
            existing.compressionType = "none";
            return await page.patchObject(existing);
        }
        let obj = am7model.newPrimitive("data.data");
        obj.name = fileName;
        obj.contentType = "application/json";
        obj.compressionType = "none";
        obj.groupId = grp.id;
        obj.groupPath = grp.path;
        obj.dataBytesStore = encoded;
        return await page.createObject(obj);
    }

    // Load a named data.data record from a group
    async function loadDataRecord(groupPath, fileName, createGroup) {
        let grp = createGroup
            ? await page.makePath("auth.group", "DATA", groupPath)
            : await page.findObject("auth.group", "DATA", groupPath);
        if (!grp) return null;
        let q = am7view.viewQuery("data.data");
        q.field("groupId", grp.id);
        q.field("name", fileName);
        let qr = await page.search(q);
        if (qr?.results?.length && qr.results[0].dataBytesStore) {
            return decodeJson(qr.results[0].dataBytesStore);
        }
        return null;
    }

    // Query all data.data records in a group
    async function listDataRecords(groupPath) {
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
                console.log("[CardGame v2] Deck saved:", deckName);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save deck:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", true);
            } catch (e) {
                console.error("[CardGame v2] Failed to load deck:", deckName, e);
                return null;
            }
        },

        async list() {
            try {
                let grp = await page.makePath("auth.group", "DATA", DECK_BASE_PATH);
                if (!grp) return [];
                let q = am7view.viewQuery("auth.group");
                q.field("parentId", grp.id);
                let qr = await page.search(q);
                return (qr?.results || []).map(g => g.name);
            } catch (e) {
                console.error("[CardGame v2] Failed to list decks:", e);
                return [];
            }
        },

        async remove(deckName) {
            try {
                let grp = await page.findObject("auth.group", "DATA", DECK_BASE_PATH + "/" + deckName);
                if (!grp) return false;
                await page.deleteObject("auth.group", grp.objectId);
                console.log("[CardGame v2] Deck removed:", deckName);
                return true;
            } catch (e) {
                console.error("[CardGame v2] Failed to remove deck:", deckName, e);
                return false;
            }
        }
    };

    // ── Game Save Storage ─────────────────────────────────────────────
    // Auto-saves game state to ~/CardGame/{deckName}/saves/ with rolling backups

    function serializeGameState(state) {
        let s = JSON.parse(JSON.stringify(state));
        // Strip non-serializable / transient fields
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
                let saveData = {
                    version: 1,
                    timestamp: new Date().toISOString(),
                    deckName,
                    roundNumber: state.round,
                    phase: state.phase,
                    gameState: serializeGameState(state)
                };
                let groupPath = this._savesPath(deckName);
                let grp = await page.makePath("auth.group", "DATA", groupPath);
                if (!grp) return null;

                let obj = am7model.newPrimitive("data.data");
                obj.name = "save-" + Date.now() + ".json";
                obj.contentType = "application/json";
                obj.compressionType = "none";
                obj.groupId = grp.id;
                obj.groupPath = grp.path;
                obj.dataBytesStore = encodeJson(saveData);
                let saved = await page.createObject(obj);

                // Keep only last 3 saves
                await this.cleanupOldSaves(deckName, 3);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save game:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                let saves = await this.list(deckName);
                if (saves.length === 0) return null;
                let latest = saves[0];
                if (latest.dataBytesStore) {
                    return decodeJson(latest.dataBytesStore);
                }
                return null;
            } catch (e) {
                console.error("[CardGame v2] Failed to load game save:", deckName, e);
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
                console.error("[CardGame v2] Failed to list saves:", deckName, e);
                return [];
            }
        },

        async deleteAll(deckName) {
            try {
                let grp = await page.findObject("auth.group", "DATA", this._savesPath(deckName));
                if (grp) await page.deleteObject("auth.group", grp.objectId);
            } catch (e) {
                console.warn("[CardGame v2] Failed to delete saves:", deckName, e);
            }
        },

        async cleanupOldSaves(deckName, keepCount) {
            try {
                let saves = await this.list(deckName);
                for (let i = keepCount; i < saves.length; i++) {
                    await page.deleteObject("data.data", saves[i].objectId);
                }
            } catch (e) {
                console.warn("[CardGame v2] Save cleanup failed:", e);
            }
        }
    };

    // ── Campaign Storage ──────────────────────────────────────────────
    // Persists character progression across games at ~/CardGame/{deckName}/campaign.json

    function createCampaignData(characterCard) {
        return {
            version: 1,
            characterId: characterCard.sourceId || characterCard._tempId || null,
            characterName: characterCard.name || "Unknown",
            level: 1,
            xp: 0,
            totalGamesPlayed: 0,
            wins: 0,
            losses: 0,
            statGains: {},
            pendingLevelUps: 0
        };
    }

    const campaignStorage = {
        async save(deckName, campaignData) {
            try {
                let saved = await upsertDataRecord(DECK_BASE_PATH + "/" + deckName, "campaign.json", campaignData);
                console.log("[CardGame v2] Campaign saved:", deckName, "Level", campaignData.level, "XP", campaignData.xp);
                return saved;
            } catch (e) {
                console.error("[CardGame v2] Failed to save campaign:", deckName, e);
                return null;
            }
        },

        async load(deckName) {
            try {
                return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "campaign.json", false);
            } catch (e) {
                console.error("[CardGame v2] Failed to load campaign:", deckName, e);
                return null;
            }
        }
    };

    // ── Campaign XP & Level System ────────────────────────────────────
    function calculateXP(state, isVictory) {
        let xp = 0;
        xp += state.round * 10;                    // 10 XP per round played
        if (isVictory) xp += 50;                    // Victory bonus
        xp += Math.max(0, state.player.hp) * 2;    // HP bonus (surviving health)
        return xp;
    }

    async function saveCampaignProgress(state, isVictory, xpGain) {
        let deckName = state.deckName;
        let campaign = await campaignStorage.load(deckName);
        if (!campaign) {
            campaign = createCampaignData(state.player.character);
        }
        campaign.totalGamesPlayed++;
        if (isVictory) campaign.wins++;
        else campaign.losses++;
        campaign.xp += xpGain;

        // Check for level up (every 100 XP, max level 10)
        let newLevel = Math.min(10, Math.floor(campaign.xp / 100) + 1);
        if (newLevel > campaign.level) {
            campaign.pendingLevelUps = (campaign.pendingLevelUps || 0) + (newLevel - campaign.level);
            campaign.level = newLevel;
        }

        await campaignStorage.save(deckName, campaign);
        return campaign;
    }

    // Apply campaign stat gains to a game state's player character
    function applyCampaignBonuses(state, campaign) {
        if (!campaign?.statGains || !state?.player?.character?.stats) return;
        let stats = state.player.character.stats;
        for (let [stat, gain] of Object.entries(campaign.statGains)) {
            if (typeof stats[stat] === "number") {
                stats[stat] += gain;
            }
        }
        // Recalculate derived values from updated stats
        let end = stats.END ?? stats.end ?? 12;
        state.player.ap = Math.max(2, Math.floor(end / 5) + 1);
        state.player.maxAp = state.player.ap;
        let mag = stats.MAG ?? stats.mag ?? 12;
        state.player.energy = mag;
        state.player.maxEnergy = mag;
        console.log("[CardGame v2] Campaign bonuses applied:", JSON.stringify(campaign.statGains),
            "-> AP:", state.player.ap, "Energy:", state.player.energy);
    }

    window.CardGame = window.CardGame || {};
    window.CardGame.Storage = {
        DECK_BASE_PATH,
        encodeJson, decodeJson,
        upsertDataRecord, loadDataRecord, listDataRecords,
        deckStorage, gameStorage, campaignStorage,
        createCampaignData, calculateXP, saveCampaignProgress, applyCampaignBonuses,
        serializeGameState, deserializeGameState
    };
})();
