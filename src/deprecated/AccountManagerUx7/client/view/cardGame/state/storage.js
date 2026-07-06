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

    // Load a named data.data record from a group
    async function loadDataRecord(groupPath, fileName, createGroup) {
        let grp = createGroup
            ? await page.makePath("auth.group", "DATA", groupPath)
            : await page.findObject("auth.group", "DATA", groupPath);
        if (!grp) {
            console.warn("[CardGame] loadDataRecord: group not found:", groupPath);
            return null;
        }
        let q = am7view.viewQuery("data.data");
        q.field("groupId", grp.id);
        q.field("name", fileName);
        // Ensure blob data is in the request (may not be in default query fields)
        if (q.entity.request.indexOf("dataBytesStore") === -1) {
            q.entity.request.push("dataBytesStore");
        }
        let qr = await page.search(q);
        if (qr?.results?.length && qr.results[0].dataBytesStore) {
            return decodeJson(qr.results[0].dataBytesStore);
        }
        if (qr?.results?.length) {
            console.warn("[CardGame] loadDataRecord: record found but dataBytesStore empty:", groupPath, fileName);
        }
        else{
            // console.warn("uncaught condition: " + qr?.results?.length);
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
                return await loadDataRecord(DECK_BASE_PATH + "/" + deckName, "deck.json", false);
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
                q.range(0, 200);
                let qr = await page.search(q);
                let names = (qr?.results || []).map(g => g.name);
                // Deduplicate (server may return multiple groups with same name)
                return [...new Set(names)];
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
                console.log("[CardGame v2] gameStorage.save: path=" + groupPath, "round=" + state.round, "phase=" + state.phase);
                let grp = await page.makePath("auth.group", "data", groupPath);
                if (!grp) {
                    console.error("[CardGame v2] gameStorage.save: could not create group:", groupPath);
                    return null;
                }
                console.log("[CardGame v2] gameStorage.save: group resolved, id=" + grp.id);

                let obj = am7model.newPrimitive("data.data");
                obj.name = "save-" + Date.now() + ".json";
                obj.contentType = "application/json";
                obj.compressionType = "none";
                obj.groupId = grp.id;
                obj.groupPath = grp.path;

                obj.dataBytesStore = encodeJson(saveData);
                console.log("[CardGame v2] gameStorage.save: creating object", {
                    schema: obj.schema, name: obj.name, groupId: obj.groupId,
                    groupPath: obj.groupPath, compressionType: obj.compressionType,
                    contentType: obj.contentType, blobSize: obj.dataBytesStore?.length || 0
                });
                let saved = await page.createObject(obj);
                console.log("[CardGame v2] gameStorage.save: record created:", saved ? (saved.objectId || saved.id || "ok") : "FAILED");

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
                let latest = saves[0]; // sorted newest-first
                // Use loadDataRecord to fetch with dataBytesStore included in query
                return await loadDataRecord(this._savesPath(deckName), latest.name, false);
            } catch (e) {
                console.error("[CardGame v2] Failed to load game save:", deckName, e);
                return null;
            }
        },

        async list(deckName) {
            try {
                let savesPath = this._savesPath(deckName);
                console.log("[CardGame v2] gameStorage.list: searching", savesPath);
                let records = await listDataRecords(savesPath);
                console.log("[CardGame v2] gameStorage.list: raw records found:", records.length,
                    records.length > 0 ? records.map(r => r.name) : "(empty)");
                let filtered = records
                    .filter(r => r.name?.startsWith("save-"))
                    .sort((a, b) => b.name.localeCompare(a.name));
                console.log("[CardGame v2] gameStorage.list: after filter:", filtered.length, "saves");
                return filtered;
            } catch (e) {
                console.error("[CardGame v2] Failed to list saves:", deckName, e);
                return [];
            }
        },

        async deleteAll(deckName) {
            try {
                let saves = await this.list(deckName);
                for (let save of saves) {
                    await page.deleteObject("data.data", save.objectId);
                }
                am7client.clearCache("data.data");
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

    // Migrate old campaign data (v2 → v3) by adding missing XP fields
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
                console.log("[CardGame v2] Campaign saved:", deckName, campaignData.wins + "W/" + campaignData.losses + "L");
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

    // ── Campaign Progress ──────────────────────────────────────────────
    // Track wins/losses and XP progression across games.
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

        // Calculate game XP: accumulated round XP + HP bonus + victory bonus
        let gameXp = state.player.totalGameXp || 0;
        let hpBonus = Math.max(0, state.player.hp) * 2;
        let victoryBonus = isVictory ? 50 : 0;
        let totalEarned = gameXp + hpBonus + victoryBonus;

        campaign.xp += totalEarned;
        campaign.totalXpEarned += totalEarned;

        // Check level-ups: threshold = level * 100
        let levelUpThreshold = campaign.level * 100;
        while (campaign.xp >= levelUpThreshold && campaign.level < 10) {
            campaign.xp -= levelUpThreshold;
            campaign.level++;
            campaign.pendingLevelUps++;
            levelUpThreshold = campaign.level * 100;
            console.log("[CardGame v2] Level up! Now level", campaign.level, "(" + campaign.pendingLevelUps + " pending)");
        }

        // Store XP earned this game for display
        campaign._lastGameXp = totalEarned;

        await campaignStorage.save(deckName, campaign);
        return campaign;
    }

    window.CardGame = window.CardGame || {};
    window.CardGame.Storage = {
        DECK_BASE_PATH,
        encodeJson, decodeJson,
        upsertDataRecord, loadDataRecord, listDataRecords,
        deckStorage, gameStorage, campaignStorage,
        createCampaignData, migrateCampaign, saveCampaignProgress,
        serializeGameState, deserializeGameState
    };
})();
