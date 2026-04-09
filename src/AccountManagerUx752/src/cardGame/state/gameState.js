/**
 * gameState.js — Card Game state management (ESM)
 * Port of Ux7 client/view/cardGame/state/gameState.js (2,993 lines)
 *
 * Manages game state lifecycle: creation, phase transitions, initiative,
 * threat combat, resolution, narration, and round management.
 */
import m from 'mithril';
import { am7model } from '../../core/model.js';
import { am7view } from '../../core/view.js';
import { gameConstants } from '../constants/gameConstants.js';
import { storage } from './storage.js';

function getPage() { return am7model._page; }
function getClient() { return am7model._client; }

// ── Late-bound sibling module refs (wired by CardGameApp) ─────────
let _engine = null;
let _characters = null;
let _ai = null;
let _themes = null;

function wireModules(refs) {
    if (refs.engine) _engine = refs.engine;
    if (refs.characters) _characters = refs.characters;
    if (refs.ai) _ai = refs.ai;
    if (refs.themes) _themes = refs.themes;
}

const C = () => gameConstants;
const E = () => _engine || {};
const Ch = () => _characters || {};
const AI = () => _ai || {};
const Th = () => _themes || {};
const St = () => storage;

// ── LLM Connectivity ────────────────────────────────────────────────
let llmStatus = { checked: false, available: false, error: null };

async function checkLlmConnectivity() {
    llmStatus = { checked: false, available: false, error: null };
    m.redraw();
    try {
        let client = getClient();
        let base = client ? client.base() : '';
        await m.request({
            method: 'GET',
            url: base + "/chat/prompt",
            withCredentials: true
        });
        llmStatus = { checked: true, available: true, error: null };
    } catch (err) {
        llmStatus = { checked: true, available: false, error: err.message || "LLM service unavailable" };
    }
    m.redraw();
    return llmStatus.available;
}

// ── Game State Model ────────────────────────────────────────────────
let _gameState = null;
let gameCharSelection = null;

// ── Initiative Animation State ──────────────────────────────────────
let initAnimState = {
    countdown: 3, rolling: false, rollComplete: false,
    playerDiceFace: 1, opponentDiceFace: 1, animInterval: null
};

function resetInitAnimState() {
    if (initAnimState.animInterval) clearInterval(initAnimState.animInterval);
    initAnimState = {
        countdown: 3, rolling: false, rollComplete: false,
        playerDiceFace: 1, opponentDiceFace: 1, animInterval: null
    };
}

function startInitiativeAnimation() {
    resetInitAnimState();
    let countdownInterval = setInterval(() => {
        initAnimState.countdown--;
        m.redraw();
        if (initAnimState.countdown <= 0) {
            clearInterval(countdownInterval);
            initAnimState.rolling = true;
            initAnimState.animInterval = setInterval(() => {
                initAnimState.playerDiceFace = Math.floor(Math.random() * 20) + 1;
                initAnimState.opponentDiceFace = Math.floor(Math.random() * 20) + 1;
                m.redraw();
            }, 80);
            setTimeout(() => {
                clearInterval(initAnimState.animInterval);
                initAnimState.rolling = false;
                initAnimState.rollComplete = true;
                runInitiativePhase();
                m.redraw();
            }, 2000);
        }
    }, 1000);
}

// ── Game State Creation ─────────────────────────────────────────────
async function createGameState(deck, selectedCharacter) {
    const shuffle = Ch().shuffle;
    const dealInitialStack = E().dealInitialStack;
    const getActionsForActor = E().getActionsForActor;
    const GAME_PHASES = C().GAME_PHASES;

    if (C().loadActionDefinitions) await C().loadActionDefinitions();
    if (E().loadEncounterData) await E().loadEncounterData();

    let cards = deck.cards || [];
    let allCharacters = cards.filter(c => c.type === "character");
    let playerCharCard = selectedCharacter || allCharacters[0];
    if (!playerCharCard) { console.error("[CardGame] No character card found in deck"); return null; }

    let otherChars = allCharacters.filter(c => c !== playerCharCard);
    let opponentCharCard = otherChars.length > 0 ? otherChars[Math.floor(Math.random() * otherChars.length)] : null;

    let playerStats = playerCharCard.stats || {};
    let rawPlayerEnd = playerStats.END ?? playerStats.end ?? playerStats.endurance;
    let playerEnd = (typeof rawPlayerEnd === "number" && rawPlayerEnd > 0) ? rawPlayerEnd : 12;
    let playerAp = Math.max(2, Math.floor(playerEnd / 5) + 1);
    let rawPlayerMag = playerStats.MAG ?? playerStats.mag ?? playerStats.magic;
    let playerMag = (typeof rawPlayerMag === "number" && rawPlayerMag > 0) ? rawPlayerMag : 12;

    let opponentStats = opponentCharCard ? Object.assign({}, opponentCharCard.stats || {}) : Object.assign({}, playerStats);
    let rawOppEnd = opponentStats.END ?? opponentStats.end ?? opponentStats.endurance;
    let opponentEnd = (typeof rawOppEnd === "number" && rawOppEnd > 0) ? rawOppEnd : 12;
    let opponentAp = Math.max(2, Math.floor(opponentEnd / 5) + 1);
    let rawOppMag = opponentStats.MAG ?? opponentStats.mag ?? opponentStats.magic;
    let opponentMag = (typeof rawOppMag === "number" && rawOppMag > 0) ? rawOppMag : 12;

    let actionCards = cards.filter(c => c.type === "action");
    let talkCards = cards.filter(c => c.type === "talk");
    let itemCards = shuffle(cards.filter(c => c.type === "item"));
    let apparelCards = shuffle(cards.filter(c => c.type === "apparel"));
    let skillCards = cards.filter(c => c.type === "skill");
    let magicCards = cards.filter(c => c.type === "magic");
    let encounterCards = cards.filter(c => c.type === "encounter");

    let consumableCards = itemCards.filter(c => c.subtype === "consumable");
    let equipmentItems = itemCards.filter(c => c.subtype !== "consumable");

    let itemMid = Math.ceil(equipmentItems.length / 2);
    let apparelMid = Math.ceil(apparelCards.length / 2);
    let playerItems = equipmentItems.slice(0, itemMid);
    let opponentItems = equipmentItems.slice(itemMid);
    let playerApparel = apparelCards.slice(0, apparelMid);
    let opponentApparel = apparelCards.slice(apparelMid);

    let playableCards = [...skillCards, ...magicCards, ...consumableCards];

    if (playableCards.length === 0) {
        playableCards = [
            { type: "skill", name: "Quick Reflexes", category: "Defense", modifier: "+2 to Flee and initiative", tier: "COMMON" },
            { type: "skill", name: "Swordsmanship", category: "Combat", modifier: "+2 to Attack rolls with Slashing weapons", tier: "COMMON" },
            { type: "skill", name: "Survival", category: "Survival", modifier: "+1 to Investigate and Rest", tier: "COMMON" }
        ];
    }

    let shuffledPool = shuffle([...playableCards, ...playableCards]);
    let midPoint = Math.floor(shuffledPool.length / 2);
    let playerDrawPile = shuffledPool.slice(0, midPoint);
    let opponentDrawPile = shuffledPool.slice(midPoint);

    let initialHandSize = 5;
    let playerHand = playerDrawPile.splice(0, initialHandSize);
    let opponentHand = opponentDrawPile.splice(0, initialHandSize);

    let starterWeapon = { type: "item", subtype: "weapon", name: "Starter Blade", slot: "Hand (1H)", rarity: "COMMON", atk: 1, def: 0, durability: 8, range: "Melee", damageType: "Slashing", effect: "+1 ATK", artPrompt: "a simple iron short sword" };
    let starterArmor = { type: "apparel", name: "Starter Tunic", slot: "Body", rarity: "COMMON", atk: 0, def: 1, durability: 8, effect: "+1 DEF", artPrompt: "a basic padded cloth tunic" };
    playerHand.push(Object.assign({}, starterWeapon));
    playerHand.push(Object.assign({}, starterArmor));
    opponentHand.push(Object.assign({}, starterWeapon));
    opponentHand.push(Object.assign({}, starterArmor));

    let page = getPage();
    let state = {
        deckName: deck.storageName || deck.deckName,
        themeId: deck.themeId,
        startedAt: Date.now(),
        round: 1,
        phase: GAME_PHASES.INITIATIVE,
        initiative: { playerRoll: null, opponentRoll: null, winner: null, playerPositions: [], opponentPositions: [] },
        actionBar: { totalPositions: 0, positions: [], resolveIndex: -1 },
        player: {
            character: playerCharCard, hp: 20, maxHp: 20, energy: playerMag, maxEnergy: playerMag,
            morale: 20, maxMorale: 20, ap: playerAp, maxAp: playerAp, apUsed: 0,
            hand: playerHand, drawPile: playerDrawPile, discardPile: [],
            cardStack: dealInitialStack(playerApparel, playerItems),
            equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
            roundPoints: 0, roundXp: 0, totalGameXp: 0, statusEffects: [], typesPlayedThisRound: {}
        },
        opponent: {
            name: opponentCharCard ? (opponentCharCard.name || "Opponent") : "Challenger",
            character: opponentCharCard || {
                type: "character", name: "Challenger", race: "UNKNOWN", alignment: "NEUTRAL", level: 1,
                stats: opponentStats, needs: { hp: 20, energy: opponentMag, morale: 20 },
                equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
                activeSkills: [null, null, null, null], portraitUrl: null
            },
            hp: 20, maxHp: 20, energy: opponentMag, maxEnergy: opponentMag,
            morale: 20, maxMorale: 20, ap: opponentAp, maxAp: opponentAp, apUsed: 0,
            hand: opponentHand, drawPile: opponentDrawPile, discardPile: [],
            cardStack: dealInitialStack(opponentApparel, opponentItems),
            equipped: { head: null, body: null, handL: null, handR: null, feet: null, ring: null, back: null },
            roundPoints: 0, statusEffects: [], typesPlayedThisRound: {}
        },
        encounterDeck: shuffle([...encounterCards]),
        pot: [], roundLoot: [],
        turnTimer: null, isPaused: false,
        currentTurn: null, turnActions: [],
        beginningThreats: [], carriedThreats: [],
        threatResponse: { active: false, type: null, threats: [], responder: null, bonusAP: 0, defenseStack: null, resolved: false },
        llmBusy: null,
        narration: deck.narration || (Th()?.getActiveTheme?.()?.narration) || null,
        narrationReady: false,
        chat: { active: false, unlocked: false, messages: [], npcName: null, inputText: "", pending: false, talkCard: null, talkPosition: null },
        pokerFace: {
            enabled: (deck?.gameConfig?.pokerFaceEnabled === true) && !!page?.components?.moodRing?.enabled?.(),
            banterLevel: deck?.gameConfig?.banterLevel || "moderate",
            currentEmotion: "neutral", emotionHistory: [], dominantTrend: "neutral",
            lastTransition: null, commentary: null
        }
    };

    state.player.availableActions = getActionsForActor(state.player);
    state.opponent.availableActions = getActionsForActor(state.opponent);

    let activeThemeConfig = Th()?.getActiveTheme?.() || null;
    if (activeThemeConfig && activeThemeConfig.treasureVault && E().assembleTreasureVault) {
        state.treasureVault = { deck: E().assembleTreasureVault(activeThemeConfig), drawn: [] };
    } else {
        state.treasureVault = { deck: [], drawn: [] };
    }

    autoEquipFromStack(state.player);
    autoEquipFromStack(state.opponent);
    resetInitAnimState();
    return state;
}

// ── LLM Component State ─────────────────────────────────────────────
let gameDirector = null;
let gameNarrator = null;
let gameChatManager = null;
let gameVoice = null;
let gameAnnouncerVoice = null;

function cleanupAudio() {
    if (gameVoice) { gameVoice.stop(); gameVoice = null; }
    if (gameAnnouncerVoice) { gameAnnouncerVoice.stop(); gameAnnouncerVoice = null; }
    let page = getPage();
    if (page?.components?.audio) {
        if (typeof page.components.audio.stopAll === "function") page.components.audio.stopAll();
        if (typeof page.components.audio.disableLoop === "function") page.components.audio.disableLoop();
    }
    gameChatManager = null;
    gameDirector = null;
    gameNarrator = null;
}

async function resolveVoiceProfile(profileId) {
    if (!profileId) return null;
    try {
        let client = getClient();
        const profile = await client.getFull("identity.voice", profileId);
        if (!profile) return null;
        let resolved = { engine: profile.engine || "piper", speaker: profile.speaker || "en_GB-alba-medium", speakerId: profile.speakerId ?? -1, speed: profile.speed || 1.2 };
        if (resolved.engine === "xtts" && profile.voiceSample) {
            let sampleId = profile.voiceSample.objectId || profile.voiceSample;
            let sinst = am7model.newInstance("data.data");
            let q = client.newQuery("data.data");
            q.entity.request = am7view.viewFields(sinst);
            let page = getPage();
            q.field("organizationId", page.user.organizationId);
            q.field("objectId", sampleId);
            let sdr = await page.search(q);
            if (sdr?.results?.length > 0 && sdr.results[0].dataBytesStore) {
                resolved.voiceSample = sdr.results[0].dataBytesStore;
            }
        }
        return resolved;
    } catch (err) {
        console.warn("[CardGame] Failed to resolve voice profile:", profileId, err);
        return null;
    }
}

async function initializeLLMComponents(state, deck, options) {
    cleanupAudio();
    const activeTheme = Th()?.getActiveTheme?.() || { themeId: "high-fantasy" };
    const themeId = deck?.themeId || activeTheme?.themeId || "high-fantasy";
    const opponentChar = state?.opponent?.character;
    const gc = deck?.gameConfig || {};
    const narrationEnabled = gc.narrationEnabled !== false;
    const opponentVoiceEnabled = gc.opponentVoiceEnabled === true;
    const opponentVoiceProfileId = gc.opponentVoiceProfileId || null;
    const announcerEnabled = gc.announcerEnabled === true;
    const announcerProfile = activeTheme?.narration?.announcerProfile || "arena-announcer";
    const announcerVoiceEnabled = gc.announcerVoiceEnabled === true;
    const announcerVoiceProfileId = gc.announcerVoiceProfileId || null;

    const CardGameDirector = AI()?.CardGameDirector;
    const CardGameNarrator = AI()?.CardGameNarrator;
    const CardGameChatManager = AI()?.CardGameChatManager;
    const CardGameVoice = AI()?.CardGameVoice;

    const opponentVoiceProps = opponentVoiceProfileId ? await resolveVoiceProfile(opponentVoiceProfileId) : null;
    const announcerVoiceProps = announcerVoiceProfileId ? await resolveVoiceProfile(announcerVoiceProfileId) : null;

    const voiceDeckName = deck?.deckName || "";
    if (CardGameVoice) {
        try {
            gameVoice = new CardGameVoice();
            await gameVoice.initialize({ subtitlesOnly: !opponentVoiceEnabled, voiceProps: opponentVoiceProps, volume: 1.0, deckName: voiceDeckName });
        } catch (err) { gameVoice = null; }
    }
    if (announcerEnabled && announcerVoiceEnabled && CardGameVoice) {
        try {
            gameAnnouncerVoice = new CardGameVoice();
            await gameAnnouncerVoice.initialize({ subtitlesOnly: false, voiceProps: announcerVoiceProps, volume: 1.0, deckName: voiceDeckName });
        } catch (err) { gameAnnouncerVoice = null; }
    }
    if (gameVoice && gameAnnouncerVoice) {
        gameVoice.siblingVoice = gameAnnouncerVoice;
        gameAnnouncerVoice.siblingVoice = gameVoice;
    }

    if (!options?.skipNarration) {
        narrateGameStart();
        if (_gameState) { _gameState.narrationReady = true; m.redraw(); }
    }

    const deckName = deck?.deckName || "deck";
    const sessionSuffix = deckName + "-" + Math.random().toString(36).substring(2, 8);

    if (announcerEnabled && narrationEnabled && CardGameNarrator) {
        try {
            gameNarrator = new CardGameNarrator();
            const narratorOk = await gameNarrator.initialize(announcerProfile, themeId, sessionSuffix);
            if (!narratorOk) gameNarrator = null;
        } catch (err) { gameNarrator = null; }
    } else { gameNarrator = null; }

    if (CardGameDirector) {
        try {
            gameDirector = new CardGameDirector();
            const directorOk = await gameDirector.initialize(opponentChar, themeId, sessionSuffix);
            if (!directorOk) gameDirector = null;
        } catch (err) { gameDirector = null; }
    } else { gameDirector = null; }

    const playerChar = state?.player?.character;
    if (narrationEnabled && CardGameChatManager) {
        try {
            gameChatManager = new CardGameChatManager();
            const chatOk = await gameChatManager.initialize(opponentChar, playerChar, themeId, sessionSuffix);
            if (!chatOk) gameChatManager = null;
        } catch (err) { gameChatManager = null; }
    } else { gameChatManager = null; }
}

// ── Unified Narration System ────────────────────────────────────────
const emotionDescriptions = {
    happy: "appears pleased", sad: "looks dejected", angry: "seems frustrated",
    fear: "appears nervous", surprise: "looks startled", disgust: "seems unimpressed"
};

function updatePokerFace() {
    if (!_gameState?.pokerFace?.enabled) return;
    let page = getPage();
    const moodRing = page?.components?.moodRing;
    if (!moodRing?.enabled?.()) return;
    const emotion = moodRing.emotion() || "neutral";
    const pf = _gameState.pokerFace;
    const prev = pf.currentEmotion;
    if (emotion !== prev) pf.lastTransition = { from: prev, to: emotion, time: Date.now() };
    pf.currentEmotion = emotion;
    pf.emotionHistory.push({ emotion, timestamp: Date.now() });
    if (pf.emotionHistory.length > 10) pf.emotionHistory.shift();
    let counts = {};
    pf.emotionHistory.forEach(h => { counts[h.emotion] = (counts[h.emotion] || 0) + 1; });
    pf.dominantTrend = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0] || "neutral";
}

function buildEmotionContext() {
    updatePokerFace();
    let page = getPage();
    const pf = _gameState?.pokerFace;
    const emotion = pf?.enabled ? pf.currentEmotion : ((page?.components?.moodRing?.enabled?.()) ? page.components.moodRing.emotion() : null);
    if (!emotion || emotion === "neutral") return null;
    return { emotion, description: emotionDescriptions[emotion] || null, trend: pf?.dominantTrend || null, transition: pf?.lastTransition || null, banterLevel: pf?.banterLevel || null };
}

async function triggerNarration(trigger, extraContext = {}) {
    if (!_gameState) return;
    const playerName = _gameState.player?.character?.name || "Player";
    const opponentName = _gameState.opponent?.character?.name || "Opponent";
    const baseContext = { playerName, opponentName };
    let deckNarration = _gameState.narration;
    let context, fallbackText;

    switch (trigger) {
        case "game_start":
            context = { ...baseContext, round: 1 };
            fallbackText = deckNarration?.gameStart || `The arena awaits! ${playerName} faces ${opponentName} in a battle of wits and steel. Let the cards decide your fate!`;
            break;
        case "game_end": {
            const isVictory = extraContext.winner === "player";
            const winnerName = isVictory ? playerName : opponentName;
            const loserName = isVictory ? opponentName : playerName;
            context = { winner: winnerName, loser: loserName, isPlayerVictory: isVictory, rounds: _gameState.round };
            fallbackText = isVictory
                ? `Victory! ${playerName} stands triumphant over ${opponentName} after ${_gameState.round} rounds of fierce combat!`
                : `Defeat... ${opponentName} has bested ${playerName}. The arena falls silent after ${_gameState.round} rounds.`;
            break;
        }
        case "round_start":
            context = { ...baseContext, roundNumber: _gameState.round, playerHp: _gameState.player.hp, opponentHp: _gameState.opponent.hp, playerEnergy: _gameState.player.energy, opponentEnergy: _gameState.opponent.energy };
            if (_gameState.round <= 1) return;
            { let lowHp = (_gameState.player.hp < 10 || _gameState.opponent.hp < 10);
            if (lowHp && deckNarration?.lowHpTension?.length) { fallbackText = deckNarration.lowHpTension[Math.floor(Math.random() * deckNarration.lowHpTension.length)]; }
            else if (deckNarration?.roundStart && Array.isArray(deckNarration.roundStart)) { fallbackText = deckNarration.roundStart[Math.floor(Math.random() * deckNarration.roundStart.length)]; }
            else { fallbackText = `Round ${_gameState.round} begins!${lowHp ? " The tension mounts as both combatants show signs of wear." : ""}`; } }
            break;
        case "round_end": {
            const roundWinner = extraContext.roundWinner || "tie";
            const winName = roundWinner === "player" ? playerName : roundWinner === "opponent" ? opponentName : "Neither";
            context = { ...baseContext, roundNumber: _gameState.round, roundWinner: winName, playerHp: _gameState.player.hp, opponentHp: _gameState.opponent.hp };
            if (roundWinner === "tie" && deckNarration?.roundEndTie?.length) { fallbackText = deckNarration.roundEndTie[Math.floor(Math.random() * deckNarration.roundEndTie.length)]; }
            else if (roundWinner === "tie") { fallbackText = `Round ${_gameState.round} ends in a stalemate!`; }
            else { fallbackText = `${winName} takes Round ${_gameState.round}!`; }
            break;
        }
        case "resolution": {
            const attackerName = extraContext.attackerName || (extraContext.isPlayerAttack ? playerName : opponentName);
            const defenderName = extraContext.defenderName || (extraContext.isPlayerAttack ? opponentName : playerName);
            const outcome = extraContext.outcome || "Hit";
            const damage = extraContext.damage || 0;
            context = { ...baseContext, attackerName, defenderName, outcome, damage, roundNumber: _gameState.round };
            if (outcome === "CRIT" && deckNarration?.criticalHit?.length) { fallbackText = deckNarration.criticalHit[Math.floor(Math.random() * deckNarration.criticalHit.length)]; }
            else if (outcome === "CRIT") { fallbackText = `Critical hit! ${attackerName} devastates ${defenderName} for ${damage} damage!`; }
            else if ((outcome === "MISS" || damage <= 0) && deckNarration?.criticalMiss?.length) { fallbackText = deckNarration.criticalMiss[Math.floor(Math.random() * deckNarration.criticalMiss.length)]; }
            else if (outcome === "MISS" || damage <= 0) { fallbackText = `${defenderName} deflects ${attackerName}'s assault!`; }
            else { fallbackText = `${attackerName} strikes ${defenderName} for ${damage} damage!`; }
            break;
        }
        default:
            context = { ...baseContext, ...extraContext };
            fallbackText = null;
    }

    const emotionCtx = buildEmotionContext();
    if (emotionCtx) { context.playerEmotion = emotionCtx.emotion; context.playerEmotionDesc = emotionCtx.description; }

    if (trigger === "game_start") {
        if (fallbackText) {
            showNarrationSubtitle(fallbackText);
            if (gameAnnouncerVoice?.enabled) gameAnnouncerVoice.speak(fallbackText);
        }
        if (gameNarrator?.initialized) {
            gameNarrator.narrate(trigger, context).then(narration => {
                if (narration?.text && _gameState) {
                    showNarrationSubtitle(narration.text);
                    if (gameAnnouncerVoice?.enabled) { gameAnnouncerVoice.stopCurrent(); gameAnnouncerVoice.speak(narration.text); }
                }
            }).catch(() => {});
        }
        return;
    }

    let isBlocking = (trigger === "round_end" || trigger === "game_end");
    _gameState.llmBusy = "Narrating...";
    if (isBlocking) _gameState.narrationBusy = true;
    m.redraw();

    let narrationText = null;
    if (gameNarrator?.initialized) {
        try {
            const narration = await gameNarrator.narrate(trigger, context);
            if (narration?.text) narrationText = narration.text;
        } catch (e) {}
    }

    const finalText = narrationText || fallbackText;
    if (!finalText) {
        if (isBlocking && _gameState) { _gameState.narrationBusy = false; m.redraw(); }
        return;
    }
    if (_gameState) { _gameState.llmBusy = null; m.redraw(); }
    showNarrationSubtitle(finalText);

    if (gameAnnouncerVoice?.enabled && !gameAnnouncerVoice.subtitlesOnly) {
        try { await gameAnnouncerVoice.speak(finalText); } catch (e) {}
    } else if (isBlocking) {
        let wordCount = finalText.split(/\s+/).length;
        await new Promise(resolve => setTimeout(resolve, Math.max(3000, wordCount * 50)));
    }
    if (isBlocking && _gameState) { _gameState.narrationBusy = false; m.redraw(); }
}

function narrateGameStart() { return triggerNarration("game_start"); }
function narrateGameEnd(winner) { return triggerNarration("game_end", { winner }); }
function narrateRoundStart() { return triggerNarration("round_start"); }
function narrateRoundEnd(roundWinner) { return triggerNarration("round_end", { roundWinner }); }

async function triggerOpponentBanter(event, extraContext) {
    if (!_gameState || !gameChatManager?.initialized) return;
    const emotionCtx = buildEmotionContext();
    const banterCtx = {
        event, round: _gameState.round, playerHp: _gameState.player.hp, opponentHp: _gameState.opponent.hp,
        playerAction: extraContext?.playerAction || null, opponentAction: extraContext?.opponentAction || null,
        emotion: emotionCtx?.emotion || null, emotionDesc: emotionCtx?.description || null,
        banterLevel: _gameState.pokerFace?.banterLevel || "moderate"
    };
    try {
        const banter = await gameChatManager.generateBanter(banterCtx);
        if (banter?.text && _gameState) {
            _gameState.pokerFace.commentary = banter.text;
            showNarrationSubtitle(banter.text);
            if (gameVoice?.enabled && !gameVoice.subtitlesOnly) gameVoice.speak(banter.text);
            m.redraw();
        }
    } catch (e) {}
}

function skipNarration() {
    if (gameAnnouncerVoice) gameAnnouncerVoice.stopCurrent();
    if (gameVoice) gameVoice.stopCurrent();
    if (_gameState) { _gameState.narrationText = null; _gameState.narrationBusy = false; m.redraw(); }
}

function showNarrationSubtitle(text) {
    if (!text || !_gameState) return;
    _gameState.narrationText = text;
    _gameState.narrationTime = Date.now();
    m.redraw();
    setTimeout(() => {
        if (_gameState && _gameState.narrationTime && Date.now() - _gameState.narrationTime >= 7900) {
            let GAME_PHASES = C().GAME_PHASES;
            if (_gameState.phase !== GAME_PHASES.CLEANUP) { _gameState.narrationText = null; m.redraw(); }
        }
    }, 8000);
}

// ── Initiative Phase ────────────────────────────────────────────────
function runInitiativePhase() {
    if (!_gameState) return;
    const GAME_PHASES = C().GAME_PHASES;
    const rollInitiative = E().rollInitiative;
    const checkNat1Threats = E().checkNat1Threats;
    const insertBeginningThreats = E().insertBeginningThreats;

    let playerStats = _gameState.player.character.stats || {};
    let opponentStats = _gameState.opponent.character.stats || {};
    _gameState.initiative.playerRoll = rollInitiative(playerStats);
    _gameState.initiative.opponentRoll = rollInitiative(opponentStats);

    let pTotal = _gameState.initiative.playerRoll.total;
    let oTotal = _gameState.initiative.opponentRoll.total;
    if (pTotal > oTotal) _gameState.initiative.winner = "player";
    else if (oTotal > pTotal) _gameState.initiative.winner = "opponent";
    else _gameState.initiative.winner = Math.random() < 0.5 ? "player" : "opponent";

    let playerAp = _gameState.player.ap;
    let opponentAp = _gameState.opponent.ap;
    let totalPositions = playerAp + opponentAp;
    let winnerAp = _gameState.initiative.winner === "player" ? playerAp : opponentAp;
    let loserAp = _gameState.initiative.winner === "player" ? opponentAp : playerAp;
    let winnerPositions = [], loserPositions = [];
    let pos = 1, wCount = 0, lCount = 0;
    while (wCount < winnerAp || lCount < loserAp) {
        if (wCount < winnerAp) { winnerPositions.push(pos++); wCount++; }
        if (lCount < loserAp) { loserPositions.push(pos++); lCount++; }
    }
    if (_gameState.initiative.winner === "player") {
        _gameState.initiative.playerPositions = winnerPositions;
        _gameState.initiative.opponentPositions = loserPositions;
    } else {
        _gameState.initiative.playerPositions = loserPositions;
        _gameState.initiative.opponentPositions = winnerPositions;
    }

    _gameState.actionBar.totalPositions = totalPositions;
    _gameState.actionBar.positions = [];
    for (let i = 1; i <= totalPositions; i++) {
        let owner = _gameState.initiative.playerPositions.includes(i) ? "player" : "opponent";
        _gameState.actionBar.positions.push({ index: i, owner, stack: null, resolved: false });
    }

    let beginningThreats = checkNat1Threats(_gameState);
    if (beginningThreats.length > 0) insertBeginningThreats(_gameState, beginningThreats);
    m.redraw();
}

// ── Equipment Management ──────────────────────────────────────────────
function isEquippable(card) {
    return (card.type === "item" && (card.subtype === "weapon" || card.subtype === "armor")) || card.type === "apparel";
}

function getSlotForCard(card) {
    let EQUIP_SLOT_MAP = C().EQUIP_SLOT_MAP;
    let cardSlot = card.slot || (card.type === "apparel" || (card.type === "item" && card.subtype === "armor") ? "Body" : null);
    if (!cardSlot || !EQUIP_SLOT_MAP[cardSlot]) return null;
    return EQUIP_SLOT_MAP[cardSlot];
}

function equipCard(actor, card, slotKey) {
    if (!actor || !card || !slotKey) return;
    let currentItem = actor.equipped[slotKey];
    if (currentItem) {
        if (currentItem.slot === "Hand (2H)") {
            if (slotKey === "handR") actor.hand.push(currentItem);
            actor.equipped.handL = null; actor.equipped.handR = null;
        } else { actor.hand.push(currentItem); actor.equipped[slotKey] = null; }
    }
    if (card.slot === "Hand (2H)") {
        if (actor.equipped.handL && actor.equipped.handL !== currentItem) actor.hand.push(actor.equipped.handL);
        if (actor.equipped.handR && actor.equipped.handR !== currentItem) actor.hand.push(actor.equipped.handR);
        actor.equipped.handL = card; actor.equipped.handR = card;
    } else { actor.equipped[slotKey] = card; }
    let handIdx = actor.hand.indexOf(card);
    if (handIdx >= 0) actor.hand.splice(handIdx, 1);
    let stackIdx = (actor.cardStack || []).indexOf(card);
    if (stackIdx >= 0) actor.cardStack.splice(stackIdx, 1);
}

function unequipCard(actor, slotKey) {
    if (!actor || !slotKey) return;
    let item = actor.equipped[slotKey];
    if (!item) return;
    if (item.slot === "Hand (2H)") { actor.equipped.handL = null; actor.equipped.handR = null; }
    else { actor.equipped[slotKey] = null; }
    actor.hand.push(item);
}

function autoEquipFromStack(actor) {
    if (!actor || !actor.cardStack) return;
    let toEquip = actor.cardStack.filter(c => isEquippable(c));
    toEquip.sort((a, b) => ((b.atk || 0) + (b.def || 0)) - ((a.atk || 0) + (a.def || 0)));
    for (let card of toEquip) {
        let slots = getSlotForCard(card);
        if (!slots) continue;
        let targetSlot = slots.find(s => !actor.equipped[s]);
        if (targetSlot) equipCard(actor, card, targetSlot);
    }
}

function aiAutoEquip(actor) {
    if (!actor) return;
    let equippable = [...(actor.hand || []), ...(actor.cardStack || [])].filter(c =>
        isEquippable(c) && !Object.values(actor.equipped || {}).includes(c)
    );
    equippable.sort((a, b) => ((b.atk || 0) + (b.def || 0)) - ((a.atk || 0) + (a.def || 0)));
    for (let card of equippable) {
        let slots = getSlotForCard(card);
        if (!slots) continue;
        let targetSlot = slots.find(s => !actor.equipped[s]);
        if (targetSlot) equipCard(actor, card, targetSlot);
    }
}

function enterEquipPhase() {
    const GAME_PHASES = C().GAME_PHASES;
    _gameState.phase = GAME_PHASES.EQUIP;
    aiAutoEquip(_gameState.opponent);
}

// ── Phase Transitions ───────────────────────────────────────────────
function advancePhase() {
    if (!_gameState) return;
    const GAME_PHASES = C().GAME_PHASES;
    console.log("[CardGame v2] advancePhase from:", _gameState.phase, "round:", _gameState.round);

    if (_gameState.phase === GAME_PHASES.INITIATIVE) {
        if (_gameState.beginningThreats && _gameState.beginningThreats.length > 0) enterThreatResponsePhase("beginning");
        else enterDrawPlacementPhase();
    } else if (_gameState.phase === GAME_PHASES.THREAT_RESPONSE) {
        resolveThreatCombat();
    } else if (_gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
        _gameState.phase = GAME_PHASES.RESOLUTION;
        _gameState.actionBar.resolveIndex = 0;
        resolutionTotals = { playerDamageDealt: 0, playerDamageTaken: 0, opponentDamageDealt: 0, opponentDamageTaken: 0 };
        setTimeout(() => { if (_gameState && _gameState.phase === GAME_PHASES.RESOLUTION) advanceResolution(); }, 500);
    } else if (_gameState.phase === GAME_PHASES.RESOLUTION) {
        _gameState.phase = GAME_PHASES.CLEANUP;
        resolutionFastForward = false;
    } else if (_gameState.phase === GAME_PHASES.CLEANUP) {
        if (_gameState.endThreatResult && _gameState.endThreatResult.threat && !_gameState.endThreatResult.responded) enterEndThreatPhase();
        else startNextRound();
    } else if (_gameState.phase === GAME_PHASES.END_THREAT) {
        resolveEndThreatCombat();
    }
    m.redraw();
}

function enterDrawPlacementPhase() {
    const GAME_PHASES = C().GAME_PHASES;
    const aiPlaceCards = AI()?.aiPlaceCards;
    const hasStatusEffect = E().hasStatusEffect;

    aiAutoEquip(_gameState.opponent);
    _gameState.phase = GAME_PHASES.DRAW_PLACEMENT;
    _gameState.currentTurn = _gameState.initiative.winner;

    if (hasStatusEffect) {
        if (hasStatusEffect(_gameState.player, "stunned")) _gameState.player.apUsed = _gameState.player.ap;
        if (hasStatusEffect(_gameState.opponent, "stunned")) _gameState.opponent.apUsed = _gameState.opponent.ap;
    }

    if (_gameState.currentTurn === "opponent") {
        setTimeout(async () => {
            if (_gameState && _gameState.phase === GAME_PHASES.DRAW_PLACEMENT) {
                if (aiPlaceCards) await aiPlaceCards();
                m.redraw();
            }
        }, 500);
    }
}

function enterThreatResponsePhase(type) {
    const GAME_PHASES = C().GAME_PHASES;
    _gameState.phase = GAME_PHASES.THREAT_RESPONSE;
    let responders = [];
    _gameState.beginningThreats.forEach(threat => { if (!responders.includes(threat.target)) responders.push(threat.target); });
    _gameState.threatResponse = { active: true, type, threats: _gameState.beginningThreats.slice(), responder: responders[0] || "player", responderQueue: responders, bonusAP: 2, defenseStack: [], resolved: false };
    let responder = _gameState.threatResponse.responder === "player" ? _gameState.player : _gameState.opponent;
    responder.threatResponseAP = _gameState.threatResponse.bonusAP;
}

function enterEndThreatPhase() {
    const GAME_PHASES = C().GAME_PHASES;
    _gameState.phase = GAME_PHASES.END_THREAT;
    let threat = _gameState.endThreatResult.threat;
    let responder = threat.target;
    _gameState.threatResponse = { active: true, type: "end", threats: [threat], responder, bonusAP: 2, defenseStack: [], resolved: false };
    let actor = responder === "player" ? _gameState.player : _gameState.opponent;
    actor.threatResponseAP = _gameState.threatResponse.bonusAP;
}

// ── Threat Combat ───────────────────────────────────────────────────
function resolveThreatCombat() {
    const rollAttack = E().rollAttack;
    const rollDefense = E().rollDefense;
    const getCombatOutcome = E().getCombatOutcome;
    const calculateDamage = E().calculateDamage;
    const applyDamage = E().applyDamage;
    const checkGameOver = E().checkGameOver;

    if (!_gameState || !_gameState.threatResponse) return;
    let threats = _gameState.threatResponse.threats;
    let defenseStack = _gameState.threatResponse.defenseStack || [];

    if (!threats || threats.length === 0) {
        _gameState.threatResponse.resolved = true;
        _gameState.threatResponse.active = false;
        _gameState.player.threatResponseAP = 0;
        _gameState.opponent.threatResponseAP = 0;
        enterDrawPlacementPhase();
        return;
    }

    threats.forEach(threat => {
        let targetActor = threat.target === "player" ? _gameState.player : _gameState.opponent;
        let threatAttacker = { name: threat.name, character: { name: threat.name, stats: { STR: threat.atk, END: threat.def } }, hp: threat.hp, maxHp: threat.maxHp, cardStack: [{ type: "item", subtype: "weapon", atk: threat.atk }] };
        let defenderStack = defenseStack.filter(card => card.target === threat.target);
        targetActor.cardStack = defenderStack;
        let attackRoll = rollAttack(threatAttacker, []);
        let defenseRoll = rollDefense(targetActor);
        let outcome = getCombatOutcome(attackRoll, defenseRoll);

        if (!_gameState.threatResults) _gameState.threatResults = [];
        if (outcome.damageMultiplier > 0) {
            let damage = calculateDamage(threatAttacker, outcome);
            applyDamage(targetActor, damage.finalDamage);
            _gameState.threatResults.push({ threatName: threat.name, target: threat.target, outcome: "hit", damage: damage.finalDamage, attackRoll, defenseRoll, combatOutcome: outcome });
        } else if (outcome.bothTakeDamage) {
            let clashDmg = outcome.bothTakeDamage;
            applyDamage(targetActor, clashDmg);
            threat.hp = Math.max(0, (threat.hp || threat.maxHp) - clashDmg);
            _gameState.threatResults.push({ threatName: threat.name, target: threat.target, outcome: "clash", damage: clashDmg, threatDamage: clashDmg, attackRoll, defenseRoll, combatOutcome: outcome });
        } else {
            let lootItems = threat.lootItems || [];
            let lootCard = lootItems.length > 0 ? Object.assign({}, lootItems[0]) : { type: "loot", name: "Spoils of " + threat.name, rarity: threat.lootRarity || "COMMON", effect: threat.lootRarity === "RARE" ? "Restore 5 HP" : "Restore 3 HP", flavor: "Spoils from defeating " + threat.name };
            targetActor.hand.push(lootCard);
            _gameState.threatResults.push({ threatName: threat.name, target: threat.target, outcome: "defeated", loot: lootCard, attackRoll, defenseRoll, combatOutcome: outcome });
        }
    });

    _gameState.threatResponse.resolved = true;
    _gameState.threatResponse.active = false;
    _gameState.player.threatResponseAP = 0;
    _gameState.opponent.threatResponseAP = 0;
    let winner = checkGameOver(_gameState);
    if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
    enterDrawPlacementPhase();
    m.redraw();
}

function resolveEndThreatCombat() {
    const rollAttack = E().rollAttack;
    const rollDefense = E().rollDefense;
    const getCombatOutcome = E().getCombatOutcome;
    const calculateDamage = E().calculateDamage;
    const applyDamage = E().applyDamage;
    const checkGameOver = E().checkGameOver;
    const GAME_PHASES = C().GAME_PHASES;

    if (!_gameState || !_gameState.endThreatResult || !_gameState.endThreatResult.threat) { startNextRound(); return; }

    let threat = _gameState.endThreatResult.threat;
    let responder = _gameState.threatResponse?.responder || threat.target || _gameState.roundWinner;
    let responderActor = responder === "player" ? _gameState.player : _gameState.opponent;
    let defenseStack = _gameState.threatResponse?.defenseStack || [];

    let threatAttacker = { name: threat.name, character: { name: threat.name, stats: { STR: threat.atk, END: threat.def } }, hp: threat.hp, maxHp: threat.maxHp, cardStack: [{ type: "item", subtype: "weapon", atk: threat.atk }] };
    responderActor.cardStack = defenseStack;
    let attackRoll = rollAttack(threatAttacker, []);
    let defenseRoll = rollDefense(responderActor);
    let outcome = getCombatOutcome(attackRoll, defenseRoll);

    if (outcome.damageMultiplier > 0) {
        let damage = calculateDamage(threatAttacker, outcome);
        applyDamage(responderActor, damage.finalDamage);
        threat.hp = threat.hp || threat.maxHp;
        _gameState.endThreatResult.damageDealt = damage.finalDamage;
        _gameState.endThreatResult.threatDamage = 0;
        _gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, damage, resultType: "hit" };
        let otherSide = responder === "player" ? "opponent" : "player";
        let otherActor = otherSide === "player" ? _gameState.player : _gameState.opponent;
        if (_gameState.pot && _gameState.pot.length > 0) {
            _gameState.pot.forEach(c => otherActor.discardPile.push(c));
            _gameState.endThreatResult.potForfeited = _gameState.pot.length;
            _gameState.endThreatResult.potForfeitedTo = otherSide;
            _gameState.pot = [];
        }
    } else if (outcome.bothTakeDamage) {
        let clashDmg = outcome.bothTakeDamage;
        applyDamage(responderActor, clashDmg);
        threat.hp = Math.max(0, (threat.hp || threat.maxHp) - clashDmg);
        _gameState.endThreatResult.damageDealt = clashDmg;
        _gameState.endThreatResult.threatDamage = clashDmg;
        _gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, resultType: "clash" };
        let otherSideClash = responder === "player" ? "opponent" : "player";
        let otherActorClash = otherSideClash === "player" ? _gameState.player : _gameState.opponent;
        if (_gameState.pot && _gameState.pot.length > 0) {
            _gameState.pot.forEach(c => otherActorClash.discardPile.push(c));
            _gameState.endThreatResult.potForfeited = _gameState.pot.length;
            _gameState.endThreatResult.potForfeitedTo = otherSideClash;
            _gameState.pot = [];
        }
    } else {
        _gameState.endThreatResult.damageDealt = 0;
        let counterDmg = 0;
        if (outcome.damageMultiplier < 0) counterDmg = Math.max(1, Math.floor(Math.abs(outcome.damageMultiplier) * (responderActor.character?.stats?.STR || 8)));
        threat.hp = Math.max(0, (threat.hp || threat.maxHp) - counterDmg);
        _gameState.endThreatResult.threatDamage = counterDmg;
        _gameState.endThreatResult.combatResult = { attackRoll, defenseRoll, outcome, defended: true, counterDmg, resultType: "defended" };

        let lootItems = threat.lootItems || [];
        let lootCard;
        if (lootItems.length > 0) {
            lootCard = Object.assign({}, lootItems[0]);
        } else {
            lootCard = { type: "loot", name: "Spoils of " + threat.name, rarity: threat.lootRarity || "COMMON", effect: "Restore 4 HP", flavor: "Spoils from defeating " + threat.name };
        }
        responderActor.hand.push(lootCard);
        _gameState.endThreatResult.loot = lootCard;
    }

    let threatSurvived = (threat.hp || 0) > 0;
    if (threatSurvived) {
        if (!_gameState.carriedThreats) _gameState.carriedThreats = [];
        let carriedThreat = Object.assign({}, threat);
        carriedThreat.atk = (carriedThreat.atk || 0) + 2;
        carriedThreat._carriedOver = true;
        carriedThreat._previousRound = _gameState.round;
        _gameState.carriedThreats.push(carriedThreat);
        _gameState.endThreatResult.carriedOver = true;
    }
    _gameState.endThreatResult.responded = true;
    if (_gameState.threatResponse) { _gameState.threatResponse.active = false; _gameState.threatResponse.resolved = true; }
    _gameState.player.threatResponseAP = 0;
    _gameState.opponent.threatResponseAP = 0;

    let winner = checkGameOver(_gameState);
    if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
    _gameState.cleanupApplied = true;
    _gameState.phase = GAME_PHASES.CLEANUP;
    m.redraw();
}

function placeThreatDefenseCard(card) {
    if (!_gameState || !_gameState.threatResponse || !_gameState.threatResponse.active) return false;
    let responder = _gameState.threatResponse.responder;
    let actor = responder === "player" ? _gameState.player : _gameState.opponent;
    if ((actor.threatResponseAP || 0) < 1) return false;
    actor.threatResponseAP -= 1;
    _gameState.threatResponse.defenseStack.push({ ...card, target: _gameState.threatResponse.threats[0]?.target || responder });
    let handIndex = actor.hand.indexOf(card);
    if (handIndex >= 0) actor.hand.splice(handIndex, 1);
    m.redraw();
    return true;
}

function skipThreatResponse() {
    if (!_gameState || !_gameState.threatResponse) return;
    const GAME_PHASES = C().GAME_PHASES;
    if (_gameState.phase === GAME_PHASES.THREAT_RESPONSE) resolveThreatCombat();
    else if (_gameState.phase === GAME_PHASES.END_THREAT) resolveEndThreatCombat();
}

function fleeFromThreat() {
    if (!_gameState || !_gameState.threatResponse || !_gameState.threatResponse.active) return;
    const rollD20 = E().rollD20;
    const applyDamage = E().applyDamage;
    const checkGameOver = E().checkGameOver;
    const GAME_PHASES = C().GAME_PHASES;

    let isEndThreat = _gameState.phase === GAME_PHASES.END_THREAT;
    let threats = _gameState.threatResponse.threats || [];
    let threat = threats[0];
    if (!threat) return;

    let responder = _gameState.threatResponse.responder;
    let actor = responder === "player" ? _gameState.player : _gameState.opponent;
    let stats = actor.character?.stats || {};
    let agiMod = stats.AGI ?? stats.agi ?? 0;
    let difficulty = threat.difficulty || ((threat.atk || 0) + (threat.def || 0));
    let raw = rollD20();
    let total = raw + agiMod;
    let fleeResult = { success: total >= difficulty, raw, agiMod, total, difficulty };

    if (fleeResult.success) {
        fleeResult.message = "Escaped! But the pot is forfeited.";
    } else {
        let atkRoll1 = rollD20(), atkRoll2 = rollD20();
        let bestRoll = Math.max(atkRoll1, atkRoll2);
        let threatAtk = bestRoll + (threat.atk || 0);
        let defRoll = rollD20();
        let actorDef = stats.END ?? stats.end ?? 0;
        let defTotal = defRoll + actorDef;
        let damage = Math.max(1, threatAtk - defTotal);
        applyDamage(actor, damage);
        fleeResult.message = "Failed to flee! " + threat.name + " strikes for " + damage + " damage.";
        fleeResult.damageTaken = damage;
        fleeResult.advantageRolls = [atkRoll1, atkRoll2];
    }

    let otherSide = responder === "player" ? "opponent" : "player";
    let otherActor = otherSide === "player" ? _gameState.player : _gameState.opponent;
    if (_gameState.pot && _gameState.pot.length > 0) {
        _gameState.pot.forEach(card => otherActor.discardPile.push(card));
        fleeResult.potForfeited = _gameState.pot.length;
        _gameState.pot = [];
    }

    if (!_gameState.carriedThreats) _gameState.carriedThreats = [];
    let carriedThreat = Object.assign({}, threat);
    carriedThreat.atk = (carriedThreat.atk || 0) + 2;
    carriedThreat._carriedOver = true;
    carriedThreat._previousRound = _gameState.round;
    _gameState.carriedThreats.push(carriedThreat);
    fleeResult.threatCarried = true;

    if (isEndThreat && _gameState.endThreatResult) {
        _gameState.endThreatResult.fleeResult = fleeResult;
        _gameState.endThreatResult.responded = true;
        _gameState.endThreatResult.damageDealt = fleeResult.damageTaken || 0;
        _gameState.endThreatResult.carriedOver = true;
    }
    _gameState.threatResponse.fleeResult = fleeResult;
    _gameState.threatResponse.active = false;
    _gameState.threatResponse.resolved = true;
    _gameState.player.threatResponseAP = 0;
    _gameState.opponent.threatResponseAP = 0;

    let winner = checkGameOver(_gameState);
    if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
    if (isEndThreat) { _gameState.cleanupApplied = true; _gameState.phase = GAME_PHASES.CLEANUP; }
    else { _gameState.phase = GAME_PHASES.DRAW_PLACEMENT; }
    m.redraw();
}

// ── Round Management ────────────────────────────────────────────────
function startNextRound() {
    if (!_gameState) return;
    const GAME_PHASES = C().GAME_PHASES;
    const checkGameOver = E().checkGameOver;
    const tickStatusEffects = E().tickStatusEffects;
    const processStatusEffectsTurnStart = E().processStatusEffectsTurnStart;
    const drawCardsForActor = E().drawCardsForActor;
    const anteCard = E().anteCard;

    let winner = checkGameOver(_gameState);
    if (winner) {
        console.log("[CardGame v2] GAME OVER — Winner:", winner, "Round:", _gameState.round);
        _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return;
    }
    console.log("[CardGame v2] Starting round", _gameState.round + 1,
        "| Player HP:", _gameState.player?.hp, "| Opponent HP:", _gameState.opponent?.hp);

    tickStatusEffects(_gameState.player);
    tickStatusEffects(_gameState.opponent);
    _gameState.player.cardStack = (_gameState.player.cardStack || []).filter(c => !c._temporary);
    _gameState.opponent.cardStack = (_gameState.opponent.cardStack || []).filter(c => !c._temporary);
    processStatusEffectsTurnStart(_gameState.player);
    processStatusEffectsTurnStart(_gameState.opponent);

    winner = checkGameOver(_gameState);
    if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }

    _gameState.round++;
    _gameState.phase = GAME_PHASES.INITIATIVE;
    _gameState.player.apUsed = 0;
    _gameState.opponent.apUsed = 0;
    _gameState.player.roundPoints = 0;
    _gameState.opponent.roundPoints = 0;
    _gameState.player.typesPlayedThisRound = {};
    _gameState.opponent.typesPlayedThisRound = {};
    _gameState.exhaustedThisRound = [];
    _gameState.playerLethargy = [];
    _gameState.opponentLethargy = [];
    _gameState.actionBar.positions = [];
    _gameState.actionBar.resolveIndex = -1;
    _gameState.beginningThreats = [];

    if (_gameState.carriedThreats && _gameState.carriedThreats.length > 0) {
        _gameState.carriedThreats.forEach(ct => { ct.carried = true; _gameState.beginningThreats.push(ct); });
        _gameState.carriedThreats = [];
    }

    _gameState.pot = [];
    _gameState.roundLoot = [];

    let targetHandSize = 5;
    let playerDraw = Math.max(0, targetHandSize - _gameState.player.hand.length);
    let opponentDraw = Math.max(0, targetHandSize - _gameState.opponent.hand.length);
    if (playerDraw > 0) drawCardsForActor(_gameState.player, playerDraw);
    if (opponentDraw > 0) drawCardsForActor(_gameState.opponent, opponentDraw);

    anteCard(_gameState.player, "Player");
    anteCard(_gameState.opponent, "Opponent");
    resetInitAnimState();
    _gameState.initiative.playerRoll = null;
    _gameState.initiative.opponentRoll = null;
    _gameState.initiative.winner = null;
    m.redraw();

    if (_gameState.round > 1) narrateRoundStart();
    setTimeout(() => { if (_gameState && _gameState.phase === GAME_PHASES.INITIATIVE) startInitiativeAnimation(); }, 100);
}

// ── Campaign State ──────────────────────────────────────────────────
let activeCampaign = null;

// ── Resolution Animation State ──────────────────────────────────────
let resolutionAnimating = false;
let resolutionPhase = "idle";
let resolutionDiceFaces = { attack: 1, defense: 1 };
let resolutionDiceInterval = null;
let currentCombatResult = null;
let currentMagicResult = null;
let resolutionFastForward = false;
let resolutionTotals = { playerDamageDealt: 0, playerDamageTaken: 0, opponentDamageDealt: 0, opponentDamageTaken: 0 };

function rDelay(ms) { return resolutionFastForward ? 0 : ms; }

function toggleFastForward() {
    resolutionFastForward = !resolutionFastForward;
    if (resolutionFastForward) skipNarration();
    m.redraw();
}

// ── Resolution Driver ───────────────────────────────────────────────
function advanceResolution() {
    if (!_gameState) return;
    const GAME_PHASES = C().GAME_PHASES;
    if (_gameState.phase !== GAME_PHASES.RESOLUTION) return;
    if (resolutionAnimating) return;

    const rollD20 = E().rollD20;
    const rollAttack = E().rollAttack;
    const rollDefense = E().rollDefense;
    const getCombatOutcome = E().getCombatOutcome;
    const calculateDamage = E().calculateDamage;
    const applyDamage = E().applyDamage;
    const resolveCombat = E().resolveCombat;
    const checkGameOver = E().checkGameOver;
    const checkExhausted = E().checkExhausted;
    const drawCardsForActor = E().drawCardsForActor;
    const applyStatusEffect = E().applyStatusEffect;
    const parseEffect = E().parseEffect;
    const applyParsedEffects = E().applyParsedEffects;
    const getStackSkillMod = E().getStackSkillMod;
    const openTalkChat = _ai?.openTalkChat;

    let bar = _gameState.actionBar;
    if (bar.resolveIndex >= bar.positions.length) {
        let winner = checkGameOver(_gameState);
        if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
        advancePhase();
        return;
    }

    let pos = bar.positions[bar.resolveIndex];
    if (pos.resolved) { bar.resolveIndex++; advanceResolution(); return; }

    let isThreat = pos.isThreat && pos.threat;
    const hasStatusEffect = E().hasStatusEffect;
    if (!isThreat && pos.owner && pos.stack) {
        let actor = pos.owner === "player" ? _gameState.player : _gameState.opponent;
        if (hasStatusEffect && hasStatusEffect(actor, "stunned")) {
            pos.resolved = true; pos.skipped = true; pos.skipReason = "Stunned";
            if (pos.stack.coreCard) {
                let owner = pos.owner === "player" ? _gameState.player : _gameState.opponent;
                if (pos.stack.coreCard.type === "magic" && pos.stack.coreCard.reusable !== false) owner.hand.push(pos.stack.coreCard);
                else owner.discardPile.push(pos.stack.coreCard);
                if (pos.stack.modifiers) pos.stack.modifiers.forEach(mod => owner.discardPile.push(mod));
            }
            bar.resolveIndex++;
            m.redraw();
            setTimeout(() => advanceResolution(), 800);
            return;
        }
    }

    let card = pos.stack?.coreCard;
    let isAttack = (card && card.name === "Attack") || isThreat;
    let isMagicAction = card && (card.name === "Channel" || card.type === "magic");

    if (isAttack) {
        resolutionAnimating = true;
        resolutionPhase = "rolling";
        currentCombatResult = null;
        resolutionDiceInterval = setInterval(() => { resolutionDiceFaces.attack = rollD20(); resolutionDiceFaces.defense = rollD20(); m.redraw(); }, 80);
        m.redraw();

        setTimeout(() => {
            clearInterval(resolutionDiceInterval);
            resolutionDiceInterval = null;

            let attacker, defender;
            if (isThreat) {
                attacker = { name: pos.threat.name, character: { name: pos.threat.name, stats: { STR: pos.threat.atk, END: pos.threat.def } }, hp: pos.threat.hp, maxHp: pos.threat.maxHp, cardStack: [{ type: "item", subtype: "weapon", atk: pos.threat.atk }] };
                defender = pos.target === "player" ? _gameState.player : _gameState.opponent;
            } else {
                attacker = pos.owner === "player" ? _gameState.player : _gameState.opponent;
                defender = pos.owner === "player" ? _gameState.opponent : _gameState.player;
            }

            currentCombatResult = resolveCombat(attacker, defender, pos.stack);
            console.log("[CardGame v2] Combat:", attacker.name || attacker.character?.name, "vs", defender.name || defender.character?.name,
                "| Outcome:", currentCombatResult?.outcome?.label, "| Damage:", currentCombatResult?.damageResult?.finalDamage || 0);
            resolutionDiceFaces.attack = currentCombatResult.attackRoll.raw;
            resolutionDiceFaces.defense = currentCombatResult.defenseRoll.raw;
            resolutionPhase = "result";

            if (currentCombatResult) {
                let dmgDealt = currentCombatResult.damageResult?.finalDamage || 0;
                let selfDmg = currentCombatResult.selfDamageResult?.finalDamage || 0;
                if (isThreat) { if (pos.target === "player") resolutionTotals.playerDamageTaken += dmgDealt; else resolutionTotals.opponentDamageTaken += dmgDealt; }
                else if (pos.owner === "player") { resolutionTotals.playerDamageDealt += dmgDealt; resolutionTotals.playerDamageTaken += selfDmg; resolutionTotals.opponentDamageTaken += dmgDealt; resolutionTotals.opponentDamageDealt += selfDmg; }
                else { resolutionTotals.opponentDamageDealt += dmgDealt; resolutionTotals.opponentDamageTaken += selfDmg; resolutionTotals.playerDamageTaken += dmgDealt; resolutionTotals.playerDamageDealt += selfDmg; }

                triggerNarration("resolution", {
                    isPlayerAttack: pos.owner === "player",
                    attackerName: isThreat ? pos.threat?.name : (pos.owner === "player" ? _gameState.player.character?.name : _gameState.opponent.character?.name),
                    defenderName: isThreat ? (pos.target === "player" ? _gameState.player.character?.name : _gameState.opponent.character?.name) : (pos.owner === "player" ? _gameState.opponent.character?.name : _gameState.player.character?.name),
                    outcome: currentCombatResult.outcome?.label || "Hit",
                    damage: currentCombatResult.damageDealt || 0
                });
                if (pos.owner === "opponent") triggerOpponentBanter("attack_resolved", { opponentAction: card?.name || "Attack", damage: currentCombatResult.damageDealt || 0 });
            }
            m.redraw();

            setTimeout(() => {
                pos.resolved = true;
                pos.combatResult = currentCombatResult;
                resolutionAnimating = false;
                resolutionPhase = "done";
                bar.resolveIndex++;

                if (checkExhausted && currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
                    let ownerActor = pos.owner === "player" ? _gameState.player : _gameState.opponent;
                    let exhaustedResult = checkExhausted(ownerActor, pos.owner === "player" ? "Player" : "Opponent", pos.stack?.coreCard?.name);
                    if (exhaustedResult) {
                        if (!_gameState.exhaustedThisRound) _gameState.exhaustedThisRound = [];
                        _gameState.exhaustedThisRound.push({ owner: pos.owner, actionType: exhaustedResult.actionType, stripped: exhaustedResult.stripped });
                    }
                }

                if (card && card.name === "Attack" && pos.stack?.modifiers) {
                    let magicMods = pos.stack.modifiers.filter(mm => mm.type === "magic");
                    if (magicMods.length > 0) {
                        let owner = pos.owner === "player" ? _gameState.player : _gameState.opponent;
                        let target = pos.owner === "player" ? _gameState.opponent : _gameState.player;
                        magicMods.forEach(magicMod => {
                            let parsed = parseEffect(magicMod.effect || "");
                            if (Object.keys(parsed).length > 0) applyParsedEffects(parsed, owner, target, magicMod.name);
                        });
                    }
                }

                if (isThreat && pos.threat) {
                    let targetActor = pos.target === "player" ? _gameState.player : _gameState.opponent;
                    if (currentCombatResult && currentCombatResult.outcome.damageMultiplier <= 0) {
                        let lootCard = { type: "item", subtype: "consumable", name: "Threat Loot (" + pos.threat.lootRarity + ")", rarity: pos.threat.lootRarity, effect: pos.threat.lootRarity === "RARE" ? "Restore 5 HP" : "Restore 3 HP", flavor: "Spoils from defeating " + pos.threat.name };
                        targetActor.hand.push(lootCard);
                        if (!_gameState.threatResults) _gameState.threatResults = [];
                        _gameState.threatResults.push({ threatName: pos.threat.name, target: pos.target, outcome: "defeated", loot: lootCard.name });
                    } else {
                        if (!_gameState.threatResults) _gameState.threatResults = [];
                        _gameState.threatResults.push({ threatName: pos.threat.name, target: pos.target, outcome: "hit", damage: currentCombatResult?.damageResult?.finalDamage || 0 });
                    }
                }

                if (pos.stack && pos.stack.coreCard && !isThreat) {
                    let owner = pos.owner === "player" ? _gameState.player : _gameState.opponent;
                    let coreCard = pos.stack.coreCard;
                    if (coreCard.type === "magic" && coreCard.reusable !== false) owner.hand.push(coreCard);
                    else owner.discardPile.push(coreCard);
                    pos.stack.modifiers.forEach(mod => {
                        if (mod.type === "item" && mod.subtype === "consumable") _gameState.pot.push(mod);
                        else owner.discardPile.push(mod);
                    });
                }

                let winner = checkGameOver(_gameState);
                if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
                m.redraw();
                if (bar.resolveIndex < bar.positions.length) setTimeout(() => advanceResolution(), rDelay(1500));
                else setTimeout(() => advancePhase(), rDelay(1000));
            }, rDelay(2500));
        }, rDelay(1500));
    } else if (isMagicAction) {
        resolutionAnimating = true;
        currentCombatResult = null;
        currentMagicResult = null;
        resolutionPhase = "rolling";
        resolutionDiceInterval = setInterval(() => { resolutionDiceFaces.attack = rollD20(); m.redraw(); }, 80);
        m.redraw();

        setTimeout(() => {
            clearInterval(resolutionDiceInterval);
            resolutionDiceInterval = null;
            let owner = pos.owner === "player" ? _gameState.player : _gameState.opponent;
            let target = pos.owner === "player" ? _gameState.opponent : _gameState.player;
            let ownerStats = owner.character.stats || {};
            let magStat = ownerStats.MAG || 8;
            let result = { spellName: "", roll: { raw: 0, magStat, skillMod: 0, total: 0 }, fizzled: false, effects: [], damage: 0, healing: 0 };

            if (card.name === "Channel") {
                let magicMod = pos.stack.modifiers?.find(mm => mm.type === "magic");
                let skillMod = getStackSkillMod(pos.stack, "magic");
                result.roll.skillMod = skillMod;
                if (magicMod) {
                    result.spellName = magicMod.name;
                    let fizzled = false;
                    if (magicMod.requires) {
                        for (let stat in magicMod.requires) {
                            if ((ownerStats[stat] || 0) < magicMod.requires[stat]) { fizzled = true; result.fizzled = true; result.effects.push("Fizzled! Requires " + stat + " " + magicMod.requires[stat]); break; }
                        }
                    }
                    if (!fizzled) {
                        let rawRoll = rollD20();
                        let channelRoll = rawRoll + magStat + skillMod;
                        result.roll.raw = rawRoll; result.roll.total = channelRoll;
                        let parsed = parseEffect(magicMod.effect || "");
                        let bonus = Math.max(0, Math.floor((channelRoll - 10) / 5));
                        if (parsed.damage) parsed.damage += bonus;
                        if (parsed.healHp) parsed.healHp += bonus;
                        result.damage = parsed.damage || 0;
                        result.healing = parsed.healHp || 0;
                        result.effects = applyParsedEffects(parsed, owner, target, magicMod.name);
                        if (parsed.damage && target.hp <= 0) { _gameState.winner = pos.owner; _gameState.phase = "GAME_OVER"; }
                    }
                } else {
                    result.spellName = "Arcane Burst";
                    let rawRoll = rollD20();
                    let channelRoll = rawRoll + magStat + skillMod;
                    result.roll.raw = rawRoll; result.roll.total = channelRoll;
                    let baseDmg = Math.max(1, Math.floor(magStat / 3));
                    let bonus = Math.max(0, Math.floor((channelRoll - 10) / 5));
                    let totalDmg = baseDmg + bonus;
                    target.hp = Math.max(0, target.hp - totalDmg);
                    result.damage = totalDmg;
                    result.effects.push(totalDmg + " arcane damage");
                    if (target.hp <= 0) { _gameState.winner = pos.owner; _gameState.phase = "GAME_OVER"; }
                }
            }

            if (card.type === "magic") {
                result.spellName = card.name;
                let fizzled = false;
                if (card.requires) {
                    for (let stat in card.requires) {
                        if ((ownerStats[stat] || 0) < card.requires[stat]) { fizzled = true; result.fizzled = true; result.effects.push("Fizzled! Requires " + stat + " " + card.requires[stat]); break; }
                    }
                }
                if (!fizzled) {
                    let rawRoll = rollD20();
                    let potencyRoll = rawRoll + magStat;
                    result.roll.raw = rawRoll; result.roll.total = potencyRoll;
                    let parsed = parseEffect(card.effect || "");
                    let bonus = Math.max(0, Math.floor((potencyRoll - 10) / 5));
                    if (parsed.damage) parsed.damage += bonus;
                    if (parsed.healHp) parsed.healHp += bonus;
                    result.damage = parsed.damage || 0;
                    result.healing = parsed.healHp || 0;
                    result.effects = applyParsedEffects(parsed, owner, target, card.name);
                    if (parsed.damage && target.hp <= 0) { _gameState.winner = pos.owner; _gameState.phase = "GAME_OVER"; }
                }
            }

            resolutionDiceFaces.attack = result.roll.raw || 1;
            currentMagicResult = result;
            resolutionPhase = "result";
            m.redraw();

            if (_gameState.phase === "GAME_OVER") { resolutionAnimating = false; resolutionPhase = "done"; return; }

            setTimeout(() => {
                pos.resolved = true;
                pos.magicResult = currentMagicResult;
                resolutionAnimating = false;
                resolutionPhase = "done";
                bar.resolveIndex++;
                if (card.type === "magic" && card.reusable !== false) owner.hand.push(card);
                else owner.discardPile.push(card);
                if (pos.stack?.modifiers) {
                    pos.stack.modifiers.forEach(mod => {
                        if (mod.type === "item" && mod.subtype === "consumable") _gameState.pot.push(mod);
                        else owner.discardPile.push(mod);
                    });
                }
                let winner = checkGameOver(_gameState);
                if (winner) { _gameState.winner = winner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
                m.redraw();
                if (bar.resolveIndex < bar.positions.length) setTimeout(() => advanceResolution(), rDelay(1500));
                else setTimeout(() => advancePhase(), rDelay(1000));
            }, rDelay(2000));
        }, rDelay(1200));
    } else {
        // Non-combat action resolution
        resolutionAnimating = true;
        currentCombatResult = null;
        setTimeout(() => {
            pos.resolved = true;
            resolutionAnimating = false;
            bar.resolveIndex++;

            if (card) {
                let owner = pos.owner === "player" ? _gameState.player : _gameState.opponent;
                let target = pos.owner === "player" ? _gameState.opponent : _gameState.player;

                if (card.name === "Rest") {
                    owner.hp = Math.min(owner.maxHp, owner.hp + 2);
                    owner.energy = Math.min(owner.maxEnergy, owner.energy + 3);
                    owner.morale = Math.min(owner.maxMorale, owner.morale + 2);
                }

                if (card.name === "Guard" || card.name === "Defend") {
                    let skillMod = getStackSkillMod(pos.stack, "defense");
                    applyStatusEffect(owner, "shielded", card.name);
                    if (skillMod > 0) applyStatusEffect(owner, "fortified", card.name + " (skill)");
                }

                if (card.name === "Flee" || card.name === "Escape") {
                    let ownerAgi = owner.character.stats?.AGI || 10;
                    let skillMod = getStackSkillMod(pos.stack, "flee");
                    let fleeRoll = rollD20() + ownerAgi + skillMod;
                    let fleeDC = 12;
                    if (fleeRoll >= fleeDC) {
                        if (_gameState.pot.length > 0) { let other = pos.owner === "player" ? _gameState.opponent : _gameState.player; _gameState.pot.forEach(c => other.discardPile.push(c)); _gameState.pot = []; }
                        bar.resolveIndex = bar.positions.length;
                    } else { owner.morale = Math.max(0, owner.morale - 2); }
                }

                if (card.name === "Investigate" || card.name === "Search") {
                    let ownerInt = owner.character.stats?.INT || 10;
                    let skillMod = getStackSkillMod(pos.stack, "investigate");
                    let investRoll = rollD20() + ownerInt + skillMod;
                    if (investRoll >= 10) drawCardsForActor(owner, 1);
                }

                if (card.name === "Use Item") {
                    let consumable = pos.stack.modifiers.find(mm => mm.type === "item" && mm.subtype === "consumable");
                    if (consumable) { let parsed = parseEffect(consumable.effect || ""); applyParsedEffects(parsed, owner, target, consumable.name); }
                }
                if (card.type === "item" && card.subtype === "consumable") { let parsed = parseEffect(card.effect || ""); applyParsedEffects(parsed, owner, target, card.name); }

                if (card.name === "Feint") applyStatusEffect(target, "weakened", card.name);

                if (card.name === "Trade") {
                    let stats = owner.character.stats || {};
                    let tradeStat = Math.round(((stats.PER || 8) + (stats.CRE || 8) + (stats.WIS || 8)) / 3);
                    let skillMod = getStackSkillMod(pos.stack, "craft");
                    let rawRoll = rollD20();
                    let tradeRoll = rawRoll + tradeStat + skillMod;
                    let tradeDC = 12;
                    let eligibleCards = target.hand.filter(c => c.type !== "character");
                    if (rawRoll === 20 && eligibleCards.length > 0) {
                        let rarityOrder = { LEGENDARY: 5, EPIC: 4, RARE: 3, UNCOMMON: 2, COMMON: 1 };
                        eligibleCards.sort((a, b) => (rarityOrder[b.rarity] || 0) - (rarityOrder[a.rarity] || 0));
                        let stolen = eligibleCards[0]; target.hand = target.hand.filter(c => c !== stolen); owner.hand.push(stolen);
                        pos.tradeResult = { accepted: true, critical: true, roll: rawRoll, total: tradeRoll, dc: tradeDC, received: [stolen] };
                    } else if (tradeRoll >= tradeDC && eligibleCards.length > 0) {
                        let stolen = eligibleCards[Math.floor(Math.random() * eligibleCards.length)]; target.hand = target.hand.filter(c => c !== stolen); owner.hand.push(stolen);
                        pos.tradeResult = { accepted: true, roll: rawRoll, total: tradeRoll, dc: tradeDC, received: [stolen] };
                    } else {
                        let reason = eligibleCards.length === 0 ? "No tradeable cards" : "Trade failed";
                        pos.tradeResult = { accepted: false, roll: rawRoll, total: tradeRoll, dc: tradeDC, reason };
                    }
                }

                if (card.name === "Steal") {
                    let stats = owner.character.stats || {};
                    let stealStat = Math.round(((stats.PER || 8) + (stats.CRE || 8) + (stats.WIS || 8)) / 3);
                    let skillMod = getStackSkillMod(pos.stack, "craft");
                    let alignMods = { "CHAOTICEVIL": 4, "CHAOTICNEUTRAL": 2, "NEUTRALEVIL": 2, "CHAOTICGOOD": 0, "LAWFULEVIL": 0, "NEUTRAL": 0, "NEUTRALGOOD": -2, "LAWFULNEUTRAL": -2, "LAWFULGOOD": -4 };
                    let alignment = (owner.character.alignment || "NEUTRAL").toUpperCase();
                    let alignMod = alignMods[alignment] || 0;
                    let rawRoll = rollD20();
                    let stealRoll = rawRoll + stealStat + skillMod + alignMod;
                    let stealDC = 14;
                    let handCards = target.hand.filter(c => c.type !== "character");
                    let stackCards = (target.cardStack || []).filter(c => c.type !== "character");
                    let allEligible = [...handCards, ...stackCards];
                    if (rawRoll === 20 && allEligible.length > 0) {
                        let rarityOrder = { LEGENDARY: 5, EPIC: 4, RARE: 3, UNCOMMON: 2, COMMON: 1 };
                        allEligible.sort((a, b) => (rarityOrder[b.rarity] || 0) - (rarityOrder[a.rarity] || 0));
                        let stolen = allEligible[0];
                        if (stackCards.includes(stolen)) target.cardStack = target.cardStack.filter(c => c !== stolen);
                        else target.hand = target.hand.filter(c => c !== stolen);
                        owner.hand.push(stolen);
                        pos.stealResult = { success: true, critical: true, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, stolen };
                    } else if (stealRoll >= stealDC && allEligible.length > 0) {
                        let stolen = allEligible[Math.floor(Math.random() * allEligible.length)];
                        if (stackCards.includes(stolen)) target.cardStack = target.cardStack.filter(c => c !== stolen);
                        else target.hand = target.hand.filter(c => c !== stolen);
                        owner.hand.push(stolen);
                        pos.stealResult = { success: true, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, stolen };
                    } else if (rawRoll === 1) {
                        owner.morale = Math.max(0, owner.morale - 3);
                        target.morale = Math.min(target.maxMorale, target.morale + 2);
                        pos.stealResult = { success: false, critical: true, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod, reason: "Caught!" };
                    } else { pos.stealResult = { success: false, roll: rawRoll, total: stealRoll, dc: stealDC, alignMod }; }
                }

                if (card.name === "Craft") {
                    let stats = owner.character.stats || {};
                    let craftStat = Math.round(((stats.CRE || 8) + (stats.WIS || 8) + (stats.DEX || 8)) / 3);
                    let skillMod = getStackSkillMod(pos.stack, "craft");
                    let rawRoll = rollD20();
                    let craftRoll = rawRoll + craftStat + skillMod;
                    let craftDC = 12;
                    let commonItems = [
                        { type: "item", subtype: "consumable", name: "Health Potion", effect: "Restore 6 HP", rarity: "COMMON" },
                        { type: "item", subtype: "consumable", name: "Energy Tonic", effect: "Restore 4 Energy", rarity: "COMMON" },
                        { type: "item", subtype: "consumable", name: "Bandage", effect: "Restore 4 HP", rarity: "COMMON" },
                        { type: "item", subtype: "consumable", name: "Sharpening Stone", effect: "+2 to Attack rolls this round", rarity: "COMMON" },
                        { type: "item", subtype: "consumable", name: "Shield Polish", effect: "+2 to Defense this round", rarity: "COMMON" }
                    ];
                    let rareItems = [
                        { type: "item", subtype: "consumable", name: "Greater Health Potion", effect: "Restore 12 HP", rarity: "RARE" },
                        { type: "item", subtype: "consumable", name: "Elixir of Vigor", effect: "Restore 6 HP and 4 Energy", rarity: "RARE" },
                        { type: "item", subtype: "weapon", name: "Crafted Blade", atk: 3, damageType: "Slashing", slot: "Hand (1H)", rarity: "RARE" },
                        { type: "apparel", subtype: "armor", name: "Reinforced Vest", def: 2, hpBonus: 3, slot: "Body", rarity: "RARE" }
                    ];
                    let uniqueItems = [
                        { type: "item", subtype: "consumable", name: "Phoenix Feather", effect: "Restore to full HP when defeated (auto-use)", rarity: "LEGENDARY" },
                        { type: "item", subtype: "weapon", name: "Masterwork Blade", atk: 5, damageType: "Slashing", slot: "Hand (1H)", rarity: "EPIC", special: "Keen: +2 crit range" },
                        { type: "apparel", subtype: "armor", name: "Aegis Plate", def: 4, hpBonus: 5, slot: "Body", rarity: "EPIC", special: "Fortified: -1 incoming damage" }
                    ];
                    if (rawRoll === 20) {
                        let item = uniqueItems[Math.floor(Math.random() * uniqueItems.length)];
                        item.flavor = (item.flavor || "") + " [Crafted by " + owner.name + "]";
                        owner.hand.push(item);
                        console.log("[CardGame v2] Craft critical! Created unique:", item.name, "Roll:", craftRoll);
                        pos.craftResult = { success: true, critical: true, roll: rawRoll, total: craftRoll, dc: craftDC, created: item };
                    } else if (craftRoll >= craftDC + 5) {
                        let item = rareItems[Math.floor(Math.random() * rareItems.length)]; owner.hand.push(item);
                        pos.craftResult = { success: true, roll: rawRoll, total: craftRoll, dc: craftDC, created: item };
                    } else if (craftRoll >= craftDC) {
                        let item = commonItems[Math.floor(Math.random() * commonItems.length)]; owner.hand.push(item);
                        pos.craftResult = { success: true, roll: rawRoll, total: craftRoll, dc: craftDC, created: item };
                    } else { pos.craftResult = { success: false, roll: rawRoll, total: craftRoll, dc: craftDC }; }
                }

                if (card.type === "talk") {
                    if (pos.owner === "player") {
                        if (openTalkChat) openTalkChat(card, bar.resolveIndex);
                        return;
                    } else {
                        let oStats = owner.character.stats || {};
                        let tStats = target.character.stats || {};
                        let talkStat = Math.round(((oStats.INT || 8) + (oStats.CHA || 8)) / 2);
                        let targetDefense = Math.round(((tStats.INT || 8) + (tStats.CHA || 8)) / 2);
                        let skillMod = getStackSkillMod(pos.stack, "talk");
                        let rawRoll = rollD20();
                        let talkRoll = rawRoll + talkStat + skillMod;
                        let defRoll = rollD20() + targetDefense;
                        if (rawRoll === 20 || talkRoll >= defRoll + 10) { target.morale = 0; _gameState.winner = pos.owner; _gameState.phase = "GAME_OVER"; m.redraw(); return; }
                        else if (talkRoll > defRoll) { let moraleDmg = Math.max(1, Math.floor((talkRoll - defRoll) / 2)); target.morale = Math.max(0, target.morale - moraleDmg); owner.morale = Math.min(owner.maxMorale, owner.morale + 1); if (target.morale <= 0) { _gameState.winner = pos.owner; _gameState.phase = "GAME_OVER"; m.redraw(); return; } }
                        else if (rawRoll === 1 || talkRoll <= defRoll - 10) { owner.morale = Math.max(0, owner.morale - 3); target.morale = Math.min(target.maxMorale, target.morale + 2); drawCardsForActor(target, 1); }
                    }
                }

                owner.discardPile.push(card);
                if (pos.stack.modifiers) pos.stack.modifiers.forEach(mod => owner.discardPile.push(mod));
            }

            m.redraw();
            if (bar.resolveIndex < bar.positions.length) setTimeout(() => advanceResolution(), rDelay(2000));
            else setTimeout(() => advancePhase(), rDelay(1000));
        }, rDelay(1000));
    }
    m.redraw();
}

// ── End Turn / Auto-End Turn ────────────────────────────────────────
function endTurn() {
    if (!_gameState) return;
    const GAME_PHASES = C().GAME_PHASES;
    if (_gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;
    _gameState.currentTurn = _gameState.currentTurn === "player" ? "opponent" : "player";
    checkAutoEndTurn();
}

function checkAutoEndTurn() {
    if (!_gameState) return;
    const GAME_PHASES = C().GAME_PHASES;
    if (_gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;

    let currentTurn = _gameState.currentTurn;
    let current = currentTurn === "player" ? _gameState.player : _gameState.opponent;
    let positions = currentTurn === "player" ? _gameState.initiative.playerPositions : _gameState.initiative.opponentPositions;
    let apRemaining = current.ap - current.apUsed;

    if (apRemaining > 0 && currentTurn === "player") {
        let isCoreCardType = E().isCoreCardType;
        let coreCards = current.hand.filter(c => isCoreCardType(c.type));
        let playableCores = coreCards.filter(c => !c.energyCost || c.energyCost <= current.energy);
        let emptyPositions = positions.filter(posIdx => {
            let p = _gameState.actionBar.positions.find(pp => pp.index === posIdx);
            return p && !p.stack;
        });
        if (playableCores.length === 0 || emptyPositions.length === 0) {
            current.apUsed = current.ap;
            apRemaining = 0;
        }
    }

    if (apRemaining <= 0) {
        let checkPlacementComplete = AI()?.checkPlacementComplete;
        if (checkPlacementComplete) checkPlacementComplete();
        if (_gameState.phase !== GAME_PHASES.DRAW_PLACEMENT) return;
        let other = currentTurn === "player" ? _gameState.opponent : _gameState.player;
        if (other.apUsed < other.ap) {
            _gameState.currentTurn = currentTurn === "player" ? "opponent" : "player";
            setTimeout(() => { checkAutoEndTurn(); m.redraw(); }, 300);
        }
    } else if (currentTurn === "opponent") {
        let aiPlaceCards = AI()?.aiPlaceCards;
        setTimeout(() => { if (aiPlaceCards) aiPlaceCards(); m.redraw(); }, 500);
    }
    m.redraw();
}

// ── Export ───────────────────────────────────────────────────────────
const gameState = {
    wireModules,
    state: {
        get gameState() { return _gameState; },
        set gameState(v) { _gameState = v; },
        get gameCharSelection() { return gameCharSelection; },
        set gameCharSelection(v) { gameCharSelection = v; },
        get initAnimState() { return initAnimState; },
        set initAnimState(v) { initAnimState = v; },
        get llmStatus() { return llmStatus; },
        set llmStatus(v) { llmStatus = v; },
        get gameDirector() { return gameDirector; },
        set gameDirector(v) { gameDirector = v; },
        get gameNarrator() { return gameNarrator; },
        set gameNarrator(v) { gameNarrator = v; },
        get gameChatManager() { return gameChatManager; },
        set gameChatManager(v) { gameChatManager = v; },
        get gameVoice() { return gameVoice; },
        set gameVoice(v) { gameVoice = v; },
        get gameAnnouncerVoice() { return gameAnnouncerVoice; },
        set gameAnnouncerVoice(v) { gameAnnouncerVoice = v; },
        get activeCampaign() { return activeCampaign; },
        set activeCampaign(v) { activeCampaign = v; },
        get resolutionAnimating() { return resolutionAnimating; },
        set resolutionAnimating(v) { resolutionAnimating = v; },
        get resolutionPhase() { return resolutionPhase; },
        set resolutionPhase(v) { resolutionPhase = v; },
        get resolutionDiceFaces() { return resolutionDiceFaces; },
        set resolutionDiceFaces(v) { resolutionDiceFaces = v; },
        get currentCombatResult() { return currentCombatResult; },
        set currentCombatResult(v) { currentCombatResult = v; },
        get currentMagicResult() { return currentMagicResult; },
        set currentMagicResult(v) { currentMagicResult = v; },
        get resolutionTotals() { return resolutionTotals; },
        advancePhase,
        placeCard: null,
        checkGameOver: null
    },
    getGameState: () => _gameState,
    setGameState: (s) => { _gameState = s; },
    getGameCharSelection: () => gameCharSelection,
    setGameCharSelection: (s) => { gameCharSelection = s; },
    checkLlmConnectivity,
    createGameState,
    initializeLLMComponents,
    triggerNarration, triggerOpponentBanter, narrateGameStart, narrateGameEnd, narrateRoundStart, narrateRoundEnd,
    showNarrationSubtitle, skipNarration, buildEmotionContext,
    resetInitAnimState, startInitiativeAnimation, runInitiativePhase,
    advancePhase, enterEquipPhase, enterDrawPlacementPhase, enterThreatResponsePhase, enterEndThreatPhase,
    isEquippable, getSlotForCard, equipCard, unequipCard, autoEquipFromStack, aiAutoEquip,
    resolveThreatCombat, resolveEndThreatCombat, placeThreatDefenseCard, skipThreatResponse, fleeFromThreat,
    startNextRound, cleanupAudio,
    advanceResolution, toggleFastForward,
    get resolutionFastForward() { return resolutionFastForward; },
    endTurn, checkAutoEndTurn
};

export { gameState, wireModules };
export default gameState;
