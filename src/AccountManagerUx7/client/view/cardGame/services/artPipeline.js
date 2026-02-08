/**
 * CardGame Art Pipeline Service -- SD prompt building, art generation queue,
 * background/tabletop generation, template art, and image sequence generation.
 *
 * Extracted from the monolithic cardGame-v2.js (lines ~2400-3614).
 * Handles all image generation operations for the card game.
 *
 * Exposes: window.CardGame.ArtPipeline
 *
 * Dependencies:
 *   - window.CardGame.Constants (DEFAULT_THEME)
 *   - window.CardGame.ctx (shared mutable context: viewingDeck, gameState, activeTheme)
 *   - window.CardGame.Storage (deckStorage)
 *   - window.CardGame.Characters (refreshCharacterCard)
 *   - window.am7sd (global from sdConfig.js)
 *   - AM7 globals: am7view, am7client, am7model, am7olio, page, g_application_path, m
 */
(function() {

    window.CardGame = window.CardGame || {};

    const DECK_BASE_PATH = "~/CardGame";

    // ── Convenience accessors for shared context ─────────────────────
    function getActiveTheme() {
        return (CardGame.ctx && CardGame.ctx.activeTheme) || CardGame.Constants.DEFAULT_THEME;
    }
    function getViewingDeck() {
        return CardGame.ctx ? CardGame.ctx.viewingDeck : null;
    }
    function getDeckStorage() {
        return CardGame.Storage ? CardGame.Storage.deckStorage : null;
    }
    function getGameState() {
        return CardGame.ctx ? CardGame.ctx.gameState : null;
    }

    // ── Prompt Builder ──────────────────────────────────────────────
    // Build SD prompts from card data + theme art style

    function styleLabel(style) {
        switch (style) {
            case "photograph": return "photograph";
            case "movie":      return "movie still";
            case "selfie":     return "selfie";
            case "anime":      return "anime illustration";
            case "portrait":   return "studio portrait";
            case "comic":      return "comic book illustration";
            case "digitalArt": return "digital artwork";
            case "fashion":    return "fashion photograph";
            case "vintage":    return "vintage photograph";
            case "art":        return "painting";
            default:           return "illustration";
        }
    }

    // Descriptive prompt snippets for action cards by name (hardcoded fallbacks)
    let ACTION_PROMPTS = {
        "Attack":      "a warrior mid-strike with weapon raised",
        "Strike":      "a warrior mid-strike with weapon raised",
        "Flee":        "a person running away from danger, motion blur",
        "Investigate": "a person with lantern searching for clues in shadows",
        "Trade":       "two merchants exchanging goods at a market stall",
        "Rest":        "a weary traveler resting by a warm campfire",
        "Use Item":    "hands holding a glowing magical item",
        "Craft":       "hands crafting an item at a workbench with tools",
        "Guard":       "a warrior in defensive stance with shield raised",
        "Feint":       "a cunning fighter making a deceptive move",
        "Steal":       "a shadowy figure pickpocketing in a dark alley"
    };

    // Descriptive prompt snippets for talk cards by name (hardcoded fallbacks)
    let TALK_PROMPTS = {
        "Talk":     "two people in animated conversation",
        "Taunt":    "a confident figure taunting an opponent with a smirk",
        "Persuade": "a charming speaker convincing a skeptical listener"
    };

    // Attempt to load extended prompts from external JSON
    (function loadExternalPrompts() {
        m.request({
            method: "GET",
            url: "media/cardGame/prompts/art-prompts.json",
            withCredentials: false,
            deserialize: function(v) { try { return JSON.parse(v); } catch(e) { return null; } }
        }).then(function(data) {
            if (data) {
                if (data.actionPrompts && typeof data.actionPrompts === "object") {
                    Object.assign(ACTION_PROMPTS, data.actionPrompts);
                    console.log("[CardGame ArtPipeline] Loaded external ACTION_PROMPTS:", Object.keys(data.actionPrompts).length, "entries");
                }
                if (data.talkPrompts && typeof data.talkPrompts === "object") {
                    Object.assign(TALK_PROMPTS, data.talkPrompts);
                    console.log("[CardGame ArtPipeline] Loaded external TALK_PROMPTS:", Object.keys(data.talkPrompts).length, "entries");
                }
            }
        }).catch(function(e) {
            console.warn("[CardGame ArtPipeline] Could not load art-prompts.json, using hardcoded fallbacks:", e.message || e);
        });
    })();

    function buildCardPrompt(card, theme, style, customSuffix) {
        let suffix = customSuffix ||
            (theme && theme.artStyle && theme.artStyle.promptSuffix) ||
            "high fantasy, vibrant colors, detailed illustration";

        // Character cards -- server builds its own prompt from person data
        if (card.type === "character") {
            let prompt = "Portrait of " + (card.name || "a warrior") + ", " +
                (card.race || "human").toLowerCase() + " " +
                (card.alignment || "").toLowerCase() +
                (card.age ? ", age " + card.age : "") +
                ", RPG character card art, facing viewer";
            return prompt + ", " + suffix;
        }

        let sLabel = styleLabel(style);
        let baseName = card.name || card.type;
        let typeName = (card.fabric || card.material) ? (card.fabric || card.material) + " " + baseName : baseName;

        // Action cards - use descriptive scene prompts
        if (card.type === "action") {
            let scene = ACTION_PROMPTS[card.name] || "a dramatic action scene depicting " + baseName.toLowerCase();
            return "Close-up " + sLabel + " of ((" + scene + ")), " + suffix;
        }

        // Talk cards - use conversation/speech prompts
        if (card.type === "talk") {
            let scene = TALK_PROMPTS[card.name] || "two people engaged in " + baseName.toLowerCase();
            return "Close-up " + sLabel + " of ((" + scene + ")), " + suffix;
        }

        // Skill cards - mystical glyph/ability representation
        if (card.type === "skill") {
            return "Close-up " + sLabel + " of ((mystical glowing glyph representing " + baseName.toLowerCase() + " ability)), ornate magical symbol, " + suffix;
        }

        // Item cards - show the item itself
        if (card.type === "item") {
            if (card.subtype === "weapon") {
                return "Close-up " + sLabel + " of ((" + typeName + ")), detailed weapon, " + suffix;
            } else if (card.subtype === "armor") {
                return "Close-up " + sLabel + " of ((" + typeName + ")), detailed armor piece, " + suffix;
            } else if (card.subtype === "consumable") {
                return "Close-up " + sLabel + " of ((" + typeName + ")), magical potion or item on a table, " + suffix;
            } else if (card.subtype === "material") {
                return "Close-up " + sLabel + " of ((raw " + typeName + ")), crafting material, " + suffix;
            }
            return "Close-up " + sLabel + " of ((" + typeName + ")), detailed item, " + suffix;
        }

        // Default fallback for any other card type
        return "Close-up " + sLabel + " of ((" + typeName + ")), " + suffix;
    }

    // ── Image Generation Queue State ────────────────────────────────
    let artQueue = [];          // { card, cardIndex, status, result, error }
    let artProcessing = false;
    let artCompleted = 0;
    let artTotal = 0;
    let artPaused = false;
    let artDir = null;          // cached ~/CardGame/{deckName}/Art group
    let backgroundImageId = null;   // objectId of generated background (used as img2img basis)
    let backgroundThumbUrl = null;  // thumbnail URL of background
    let backgroundPrompt = null;    // prompt used for the background (used as imageSetting for character cards)
    let backgroundGenerating = false;
    let tabletopImageId = null;     // objectId of tabletop surface image
    let tabletopThumbUrl = null;    // thumbnail URL of tabletop
    let tabletopGenerating = false;
    let flippedCards = {};          // { cardIndex: true } for character cards showing back
    let cardFrontImageUrl = null;   // background image for card faces
    let cardBackImageUrl = null;    // background image for card backs
    let cardFrontGenerating = false; // status for card front template generation
    let cardBackGenerating = false;  // status for card back template generation

    // ── Image Sequence State ────────────────────────────────────────
    let sequenceCardId = null;   // sourceId of card currently sequencing
    let sequenceProgress = "";
    // Batch sequence state (Generate All Sequences)
    let batchSequenceActive = false;
    let batchSequenceTotal = 0;
    let batchSequenceCompleted = 0;
    let batchSequenceCurrentName = "";

    // ── SD Config Overrides ─────────────────────────────────────────
    // Each override tab is a proper olio.sd.config instance with model defaults,
    // rendered via the standard form system (am7model.prepareInstance + forms.sdConfigOverrides).
    // Card-type tabs inherit from _default -- only fields the user explicitly
    // changed (different from _default) are applied as type-specific overrides.

    function newSdOverride() {
        return am7model.newPrimitive("olio.sd.config");
    }
    function defaultSdOverrides() {
        // Only _default is pre-populated; other tabs lazily inherit from _default
        // when first visited (see getSdOverrideInst)
        return { _default: newSdOverride() };
    }
    let sdOverrides = defaultSdOverrides();
    let sdConfigExpanded = false;
    let sdConfigTab = "_default";

    // ── Per-deck Game Config (LLM, Voice) ───────────────────────────
    let gameConfigExpanded = false;
    let voiceProfiles = [];       // Cached identity.voice records
    let voiceProfilesLoaded = false;

    async function loadVoiceProfiles() {
        if (voiceProfilesLoaded) return voiceProfiles;
        try {
            let q = am7view.viewQuery(am7model.newInstance("identity.voice"));
            let qr = await page.search(q);
            voiceProfiles = qr?.results || [];
            voiceProfilesLoaded = true;
            console.log("[CardGame ArtPipeline] Voice profiles loaded:", voiceProfiles.length);
            m.redraw();
        } catch (e) {
            console.warn("[CardGame ArtPipeline] Failed to load voice profiles:", e);
            voiceProfiles = [];
        }
        return voiceProfiles;
    }

    function getDeckGameConfig(deck) {
        if (!deck) return {};
        if (!deck.gameConfig) deck.gameConfig = {};
        return deck.gameConfig;
    }

    // Return only fields where the card-type override differs from _default.
    // This prevents card-type model defaults from overwriting _default settings.
    function getCardTypeDelta(key) {
        let ov = sdOverrides[key];
        let def = sdOverrides._default;
        if (!ov || !def || key === "_default") return ov;
        let delta = {};
        let hasChanges = false;
        for (let k in ov) {
            if (k === am7model.jsonModelKey) continue;
            if (ov[k] !== def[k]) {
                delta[k] = ov[k];
                hasChanges = true;
            }
        }
        return hasChanges ? delta : null;
    }

    // Prepared instances for form rendering -- keyed by tab name
    let sdOverrideInsts = {};
    let sdOverrideViews = {};
    function getSdOverrideInst(key) {
        if (!sdOverrideInsts[key]) {
            let entity = sdOverrides[key];
            // For non-default tabs without saved data, start from current _default
            if (!entity && key !== "_default" && sdOverrides._default) {
                entity = JSON.parse(JSON.stringify(sdOverrides._default));
                entity[am7model.jsonModelKey] = "olio.sd.config";
                sdOverrides[key] = entity;
            }
            if (!entity || !entity[am7model.jsonModelKey]) {
                entity = am7model.prepareEntity(entity || {}, "olio.sd.config");
                sdOverrides[key] = entity;
            }
            sdOverrideInsts[key] = am7model.prepareInstance(entity, am7model.forms.sdConfigOverrides);
            sdOverrideViews[key] = page.views.object();
        }
        return sdOverrideInsts[key];
    }
    function resetSdOverrideInsts() {
        sdOverrideInsts = {};
        sdOverrideViews = {};
    }

    // ── Art Directory & Deck Helpers ────────────────────────────────

    function currentDeckSafeName() {
        let viewingDeck = getViewingDeck();
        return viewingDeck && viewingDeck.deckName
            ? viewingDeck.deckName.replace(/[^a-zA-Z0-9_\-]/g, "_")
            : null;
    }

    async function ensureArtDir(themeId) {
        if (artDir) return artDir;
        let deckKey = currentDeckSafeName();
        let subdir = deckKey || (themeId || "high-fantasy");
        let path = DECK_BASE_PATH + "/" + subdir + "/Art";
        artDir = await page.makePath("auth.group", "DATA", path);
        return artDir;
    }

    // ── Background Generation ───────────────────────────────────────
    // Generate a background image to use as img2img basis for card art
    async function generateBackground(theme) {
        let am7sd = window.am7sd;
        let activeTheme = getActiveTheme();
        let t = theme || activeTheme;
        let bgOv = sdOverrides.background;
        // Use the background override prompt if set, otherwise fall back to theme or default
        let bgPrompt = (bgOv && bgOv.description) || (t.artStyle && t.artStyle.backgroundPrompt) || "Fantasy landscape, epic panoramic view, vibrant colors";

        backgroundGenerating = true;
        m.redraw();

        try {
            let dir = await ensureArtDir(t.themeId);
            if (!dir) throw new Error("Could not create art directory");

            let entity = await am7sd.buildEntity({
                description: bgPrompt,
                bodyStyle: "landscape",
                seed: -1,
                groupPath: dir.path,
                imageName: "background-" + t.themeId + "-" + Date.now() + ".png"
            });
            am7sd.applyOverrides(entity, sdOverrides._default);
            am7sd.applyOverrides(entity, getCardTypeDelta("background"));
            am7sd.fillStyleDefaults(entity);

            let result = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/generateArt",
                body: entity,
                withCredentials: true,
                extract: function(xhr) {
                    let body = null;
                    try { body = JSON.parse(xhr.responseText); } catch(e) {}
                    if (xhr.status !== 200) {
                        throw new Error((body && body.error) || "HTTP " + xhr.status);
                    }
                    return body;
                }
            });

            if (!result || !result.objectId) throw new Error("Background generation returned no result");

            backgroundImageId = result.objectId;
            backgroundPrompt = bgPrompt;
            backgroundThumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + (result.groupPath || dir.path) + "/" + result.name + "/256x256";

            // Save background reference on the deck
            let viewingDeck = getViewingDeck();
            let deckStorage = getDeckStorage();
            if (viewingDeck) {
                viewingDeck.backgroundImageId = backgroundImageId;
                viewingDeck.backgroundPrompt = backgroundPrompt;
                viewingDeck.backgroundThumbUrl = backgroundThumbUrl;
                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                if (deckStorage) await deckStorage.save(safeName, viewingDeck);
            }

            page.toast("success", "Background generated");
            console.log("[CardGame ArtPipeline] Background generated:", backgroundImageId);
        } catch (e) {
            console.error("[CardGame ArtPipeline] Background generation failed:", e);
            page.toast("error", "Background failed: " + (e.message || "Unknown error"));
        }

        backgroundGenerating = false;
        m.redraw();
    }

    // ── Tabletop Generation ─────────────────────────────────────────
    // Generate a tabletop surface image for the game play area
    async function generateTabletop(theme) {
        let am7sd = window.am7sd;
        let activeTheme = getActiveTheme();
        let t = theme || activeTheme;
        let ttOv = sdOverrides.tabletop;
        // Use tabletop override prompt if set, otherwise use theme setting or default
        let ttPrompt = (ttOv && ttOv.description) ||
            (t.artStyle && t.artStyle.tabletopPrompt) ||
            "Wooden table surface with subtle texture, warm lighting, top-down view, game table";

        tabletopGenerating = true;
        m.redraw();

        try {
            let dir = await ensureArtDir(t.themeId);
            if (!dir) throw new Error("Could not create art directory");

            let entity = await am7sd.buildEntity({
                description: ttPrompt,
                bodyStyle: "landscape",
                seed: -1,
                groupPath: dir.path,
                imageName: "tabletop-" + t.themeId + "-" + Date.now() + ".png"
            });
            am7sd.applyOverrides(entity, sdOverrides._default);
            am7sd.applyOverrides(entity, getCardTypeDelta("tabletop"));
            am7sd.fillStyleDefaults(entity);

            let result = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/generateArt",
                body: entity,
                withCredentials: true,
                extract: function(xhr) {
                    let body = null;
                    try { body = JSON.parse(xhr.responseText); } catch(e) {}
                    if (xhr.status !== 200) {
                        throw new Error((body && body.error) || "HTTP " + xhr.status);
                    }
                    return body;
                }
            });

            if (!result || !result.objectId) throw new Error("Tabletop generation returned no result");

            tabletopImageId = result.objectId;
            tabletopThumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + (result.groupPath || dir.path) + "/" + result.name + "/256x256";

            // Save tabletop reference on the deck
            let viewingDeck = getViewingDeck();
            let deckStorage = getDeckStorage();
            if (viewingDeck) {
                viewingDeck.tabletopImageId = tabletopImageId;
                viewingDeck.tabletopThumbUrl = tabletopThumbUrl;
                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                if (deckStorage) await deckStorage.save(safeName, viewingDeck);
            }

            page.toast("success", "Tabletop generated");
            console.log("[CardGame ArtPipeline] Tabletop generated:", tabletopImageId);
        } catch (e) {
            console.error("[CardGame ArtPipeline] Tabletop generation failed:", e);
            page.toast("error", "Tabletop failed: " + (e.message || "Unknown error"));
        }

        tabletopGenerating = false;
        m.redraw();
    }

    // ── SD Entity Builder ───────────────────────────────────────────
    // Build a SD config entity for a card, merging theme per-type overrides.
    // Uses am7sd to fetch the random template (with full style fields intact)
    // and apply overrides without destroying style-specific prompt composition.
    async function buildSdEntity(card, theme) {
        let am7sd = window.am7sd;
        let activeTheme = getActiveTheme();
        let entity = await am7sd.buildEntity();
        let t = theme || activeTheme;

        // Apply theme sdConfig defaults, then type-specific overrides
        let artCfg = t.artStyle && t.artStyle.sdConfig;
        if (artCfg) {
            if (artCfg["default"]) Object.assign(entity, artCfg["default"]);
            if (artCfg[card.type])  Object.assign(entity, artCfg[card.type]);
        }

        entity.seed = -1;

        // Apply user SD overrides first so style is final before building prompt
        am7sd.applyOverrides(entity, sdOverrides._default);
        am7sd.applyOverrides(entity, getCardTypeDelta(card.type));
        am7sd.fillStyleDefaults(entity);

        // Tab-specific or default prompt used as the "in ___" suffix
        let typeOv = sdOverrides[card.type];
        let defOv = sdOverrides._default;
        let customSuffix = (typeOv && typeOv.description) || (defOv && defOv.description) || null;
        entity.description = buildCardPrompt(card, t, entity.style, customSuffix);

        // For character cards, the server builds its own prompt from person data
        // and uses imageSetting for the scene/location.
        // Default to the theme's background prompt so characters match the setting.
        if (card.type === "character") {
            let setting = backgroundPrompt ||
                          (t.artStyle && t.artStyle.backgroundPrompt);
            if (setting) {
                entity.imageSetting = setting;
            }
        }

        // Attach background reference image if available
        if (backgroundImageId) {
            entity.referenceImageId = backgroundImageId;
            if (!entity.denoisingStrength || entity.denoisingStrength >= 1.0) {
                entity.denoisingStrength = 0.7;
            }
        }

        console.log("[CardGame ArtPipeline] buildSdEntity:", card.type, card.name,
            "style:", entity.style, "imageSetting:", entity.imageSetting,
            "cfg:", entity.cfg, "denoise:", entity.denoisingStrength,
            "model:", entity.model, "ref:", entity.referenceImageId || "none",
            "w:", entity.width, "h:", entity.height);

        return entity;
    }

    // ── Single Card Art Generation ──────────────────────────────────
    // Generate art for a single card -- character portraits use the reimage endpoint
    async function generateCardArt(card, theme) {
        let am7sd = window.am7sd;
        let activeTheme = getActiveTheme();

        // Character portraits: use the charPerson/reimage endpoint with SD config
        // Set groupPath/imageName so the server creates the image in the deck art dir
        if (card.type === "character" && card.sourceId) {
            let dir = await ensureArtDir((theme || activeTheme).themeId);
            if (!dir) throw new Error("Could not create art directory");

            let sdEntity = await buildSdEntity(card, theme);
            sdEntity.groupPath = dir.path;
            sdEntity.imageName = (card.name || "character").replace(/[^a-zA-Z0-9_\-]/g, "_") + "-" + Date.now() + ".png";

            let result = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/olio.charPerson/" + card.sourceId + "/reimage",
                body: sdEntity,
                withCredentials: true
            });
            console.log("[CardGame ArtPipeline] Reimage result:", JSON.stringify({
                objectId: result?.objectId, groupPath: result?.groupPath, name: result?.name
            }));

            let thumbUrl = null;
            let gp = result?.groupPath || dir.path;
            let rname = result?.name || sdEntity.imageName;
            if (gp && rname) {
                let orgPath = am7client.dotPath(am7client.currentOrganization);
                thumbUrl = g_application_path + "/thumbnail/" + orgPath +
                    "/data.data" + gp + "/" + encodeURIComponent(rname) + "/256x256?t=" + Date.now();
            }
            return { reimaged: true, sourceId: card.sourceId, thumbUrl: thumbUrl, objectId: result?.objectId, groupPath: gp, name: rname };
        }

        // All other card types: use the generateArt endpoint (txt2img from prompt)
        let dir = await ensureArtDir((theme || activeTheme).themeId);
        if (!dir) throw new Error("Could not create art directory");

        let sdEntity = await buildSdEntity(card, theme);
        // Add groupPath and imageName for the generateArt endpoint
        sdEntity.groupPath = dir.path;
        sdEntity.imageName = (card.name || "card").replace(/[^a-zA-Z0-9_\-]/g, "_") + "-" + Date.now() + ".png";

        let result = await m.request({
            method: "POST",
            url: g_application_path + "/rest/olio/generateArt",
            body: sdEntity,
            withCredentials: true,
            extract: function(xhr) {
                let body = null;
                try { body = JSON.parse(xhr.responseText); } catch(e) {}
                if (xhr.status !== 200) {
                    throw new Error((body && body.error) || "HTTP " + xhr.status);
                }
                return body;
            }
        });

        console.log("[CardGame ArtPipeline] generateArt response:", JSON.stringify({
            objectId: result && result.objectId, name: result && result.name,
            groupPath: result && result.groupPath, groupId: result && result.groupId
        }));

        if (!result || !result.objectId) throw new Error("generateArt returned no result");

        // Move result into art directory if server put it elsewhere
        if (result.groupId && dir.id && result.groupId !== dir.id) {
            await page.moveObject(result, dir);
            result.groupId = dir.id;
            result.groupPath = dir.path;
        }

        // Build thumbnail URL for card use -- use dir.path as fallback
        let gp = result.groupPath || dir.path;
        let rname = result.name || sdEntity.imageName;
        let thumbUrl = g_application_path + "/thumbnail/" +
            am7client.dotPath(am7client.currentOrganization) +
            "/data.data" + gp + "/" + encodeURIComponent(rname) + "/256x256?t=" + Date.now();

        console.log("[CardGame ArtPipeline] Card art URL:", thumbUrl);
        return { objectId: result.objectId, name: rname, groupPath: gp, thumbUrl: thumbUrl };
    }

    // ── Template Art Generation ─────────────────────────────────────
    // Generate template art for card front/back backgrounds
    async function generateTemplateArt(side) {
        let am7sd = window.am7sd;
        let activeTheme = getActiveTheme();
        let theme = activeTheme;
        let configKey = side === "front" ? "cardFront" : "cardBack";
        let ov = sdOverrides[configKey];
        let defOv = sdOverrides._default;

        // Use tab-specific prompt, then _default prompt, then hardcoded fallback
        let prompt;
        if (ov && ov.description) {
            prompt = ov.description;
        } else if (defOv && defOv.description) {
            prompt = defOv.description;
        } else {
            let suffix = (theme.artStyle && theme.artStyle.promptSuffix) || "fantasy illustration";
            prompt = side === "front"
                ? "ornate card face design, decorative parchment border, golden filigree frame, card game template, " + suffix
                : "ornate card back design, intricate repeating pattern, card game template, " + suffix;
        }

        // Set generating status for this side
        if (side === "front") cardFrontGenerating = true;
        else cardBackGenerating = true;
        m.redraw();

        try {
            let dir = await ensureArtDir(theme.themeId);
            if (!dir) throw new Error("Could not create art directory");
            let sdEntity = await am7sd.buildEntity({
                description: prompt,
                seed: -1,
                imageName: "card-" + side + "-" + (theme.themeId || "default") + "-" + Date.now() + ".png",
                groupPath: dir.path
            });
            am7sd.applyOverrides(sdEntity, sdOverrides._default);
            am7sd.applyOverrides(sdEntity, getCardTypeDelta(configKey));
            am7sd.fillStyleDefaults(sdEntity);
            if (backgroundImageId) {
                sdEntity.referenceImageId = backgroundImageId;
                if (!sdEntity.denoisingStrength || sdEntity.denoisingStrength >= 1.0) sdEntity.denoisingStrength = 0.7;
            }

            console.log("[CardGame ArtPipeline] generateTemplateArt:", side, configKey, sdEntity);

            let result = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/generateArt",
                body: sdEntity,
                withCredentials: true,
                extract: function(xhr) {
                    let body = null;
                    try { body = JSON.parse(xhr.responseText); } catch(e) {}
                    if (xhr.status !== 200) {
                        throw new Error((body && body.error) || "HTTP " + xhr.status);
                    }
                    return body;
                }
            });

            if (!result || !result.objectId) throw new Error("Template art returned no result");

            let gp = result.groupPath || dir.path;
            let rname = result.name || sdEntity.imageName;
            let thumbUrl = g_application_path + "/thumbnail/" +
                am7client.dotPath(am7client.currentOrganization) +
                "/data.data" + gp + "/" + encodeURIComponent(rname) + "/512x768?t=" + Date.now();
            let viewingDeck = getViewingDeck();
            let deckStorage = getDeckStorage();
            if (side === "front") {
                cardFrontImageUrl = thumbUrl;
                if (viewingDeck) viewingDeck.cardFrontImageUrl = thumbUrl;
            } else {
                cardBackImageUrl = thumbUrl;
                if (viewingDeck) viewingDeck.cardBackImageUrl = thumbUrl;
            }
            // Auto-save deck with new template URLs
            if (viewingDeck && deckStorage) {
                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                await deckStorage.save(safeName, viewingDeck);
            }
            page.toast("success", "Card " + side + " art generated");
        } catch (e) {
            console.error("[CardGame ArtPipeline] Template art failed:", e);
            page.toast("error", "Card " + side + " art failed: " + (e.message || "Unknown error"));
        }

        // Clear generating status
        if (side === "front") cardFrontGenerating = false;
        else cardBackGenerating = false;
        m.redraw();
    }

    // ── Art Queue Processing ────────────────────────────────────────
    // Process the art queue one item at a time
    async function processArtQueue() {
        let activeTheme = getActiveTheme();
        let viewingDeck = getViewingDeck();
        let deckStorage = getDeckStorage();
        let gameState = getGameState();

        if (artProcessing || artPaused) return;
        let next = artQueue.find(j => j.status === "pending");
        if (!next) {
            artProcessing = false;
            if (artCompleted > 0) {
                page.toast("success", "Art generation complete: " + artCompleted + " of " + artTotal + " images");
                // Save deck with updated image URLs
                if (viewingDeck && deckStorage) {
                    let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                    await deckStorage.save(safeName, viewingDeck);
                    console.log("[CardGame ArtPipeline] Deck saved with updated art URLs");
                }
            }
            m.redraw();
            return;
        }

        artProcessing = true;
        next.status = "processing";
        m.redraw();

        try {
            let result = await generateCardArt(next.card, activeTheme);
            next.status = "complete";
            next.result = result;
            artCompleted++;

            // Update the card in the deck with the image URL
            // Re-fetch viewingDeck in case it was replaced during async generation
            viewingDeck = getViewingDeck();
            if (viewingDeck && viewingDeck.cards && viewingDeck.cards[next.cardIndex]) {
                let deckCard = viewingDeck.cards[next.cardIndex];
                if (result.thumbUrl) {
                    // For character cards, set portraitUrl; for others, set imageUrl
                    if (result.reimaged) {
                        deckCard.portraitUrl = result.thumbUrl;
                        deckCard.artObjectId = result.objectId;
                        // Also update the queue's card ref in case it diverged
                        next.card.portraitUrl = result.thumbUrl;
                        next.card.artObjectId = result.objectId;
                    } else {
                        deckCard.imageUrl = result.thumbUrl;
                        deckCard.artObjectId = result.objectId;
                        next.card.imageUrl = result.thumbUrl;
                        next.card.artObjectId = result.objectId;
                    }
                } else if (result.reimaged && deckCard.sourceId) {
                    // Fallback: re-fetch the charPerson to get the updated portrait
                    if (CardGame.Characters && CardGame.Characters.refreshCharacterCard) {
                        await CardGame.Characters.refreshCharacterCard(deckCard);
                    }
                }

                // Propagate art to duplicate cards in deck
                if (next.duplicateIndices && next.duplicateIndices.length > 0 && result.thumbUrl) {
                    next.duplicateIndices.forEach(dupeIdx => {
                        let dupeCard = viewingDeck.cards[dupeIdx];
                        if (dupeCard) {
                            if (result.reimaged) {
                                dupeCard.portraitUrl = result.thumbUrl;
                            } else {
                                dupeCard.imageUrl = result.thumbUrl;
                            }
                            dupeCard.artObjectId = result.objectId;
                        }
                    });
                    console.log("[CardGame ArtPipeline] Shared art with " + next.duplicateIndices.length + " duplicate(s) of " + deckCard.name);
                }

                // Propagate art to cards in active game state (hand, drawPile, discardPile)
                if (gameState && result.thumbUrl) {
                    propagateArtToGameState(deckCard.name, deckCard.type, result.thumbUrl, result.reimaged);
                }

                // Save deck immediately after each card's art completes
                if (deckStorage) {
                    let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                    deckStorage.save(safeName, viewingDeck).catch(e =>
                        console.warn("[CardGame ArtPipeline] Incremental save failed:", e)
                    );
                }
                m.redraw();
            }
        } catch (e) {
            console.error("[CardGame ArtPipeline] Art generation failed for:", next.card.name, e);
            next.status = "failed";
            next.error = e.message || "Unknown error";
        }

        artProcessing = false;
        m.redraw();

        // Process next in queue
        processArtQueue();
    }

    // ── Game State Art Propagation ──────────────────────────────────
    // Propagate generated art to cards in the active game state
    function propagateArtToGameState(cardName, cardType, thumbUrl, isPortrait) {
        let gameState = getGameState();
        if (!gameState) return;

        let updated = 0;
        const updateCard = (card) => {
            if (card && card.name === cardName && card.type === cardType) {
                if (isPortrait) {
                    card.portraitUrl = thumbUrl;
                } else {
                    card.imageUrl = thumbUrl;
                }
                updated++;
            }
        };

        // Update player cards
        if (gameState.player) {
            (gameState.player.hand || []).forEach(updateCard);
            (gameState.player.drawPile || []).forEach(updateCard);
            (gameState.player.discardPile || []).forEach(updateCard);
            (gameState.player.cardStack || []).forEach(updateCard);
        }

        // Update opponent cards
        if (gameState.opponent) {
            (gameState.opponent.hand || []).forEach(updateCard);
            (gameState.opponent.drawPile || []).forEach(updateCard);
            (gameState.opponent.discardPile || []).forEach(updateCard);
            (gameState.opponent.cardStack || []).forEach(updateCard);
        }

        // Update action bar cards
        if (gameState.actionBar?.positions) {
            gameState.actionBar.positions.forEach(pos => {
                if (pos.stack?.coreCard) updateCard(pos.stack.coreCard);
                (pos.stack?.modifiers || []).forEach(updateCard);
            });
        }

        // Update pot
        (gameState.pot || []).forEach(updateCard);

        if (updated > 0) {
            console.log("[CardGame ArtPipeline] Propagated art to", updated, "card(s) in game state:", cardName);
        }
    }

    // ── Card Deduplication ──────────────────────────────────────────
    // Build a unique signature for a card to detect duplicates
    function cardSignature(card) {
        if (card.type === "character") {
            // Characters are unique by sourceId or _sourceChar._tempId
            return "char:" + (card.sourceId || (card._sourceChar && card._sourceChar._tempId) || card.name);
        }
        // Items/powers: type + name + material/fabric
        return (card.type || "") + ":" + (card.name || "") + ":" + (card.fabric || card.material || "");
    }

    // ── Deck Art Queue ──────────────────────────────────────────────
    // Queue art generation for all cards in a deck (including front/back templates)
    async function queueDeckArt(deck) {
        let activeTheme = getActiveTheme();

        if (!deck || !deck.cards) return;
        artQueue = [];
        artCompleted = 0;
        artTotal = 0;
        artPaused = false;
        artDir = null;

        // First, generate missing templates, background, and tabletop
        let needFront = !deck.cardFrontImageUrl && !cardFrontImageUrl;
        let needBack = !deck.cardBackImageUrl && !cardBackImageUrl;
        let needBg = !deck.backgroundImageId && !backgroundImageId;
        let needTabletop = !deck.tabletopImageId && !tabletopImageId;
        let preGenCount = (needFront ? 1 : 0) + (needBack ? 1 : 0) + (needBg ? 1 : 0) + (needTabletop ? 1 : 0);

        if (preGenCount > 0) {
            console.log("[CardGame ArtPipeline] Generating " + preGenCount + " missing template/environment images first...");
            if (needFront) {
                artTotal++; m.redraw();
                console.log("[CardGame ArtPipeline] Generating card front template...");
                await generateTemplateArt("front");
                artCompleted++; m.redraw();
            }
            if (needBack) {
                artTotal++; m.redraw();
                console.log("[CardGame ArtPipeline] Generating card back template...");
                await generateTemplateArt("back");
                artCompleted++; m.redraw();
            }
            if (needBg) {
                artTotal++; m.redraw();
                console.log("[CardGame ArtPipeline] Generating background...");
                await generateBackground(activeTheme);
                artCompleted++; m.redraw();
            }
            if (needTabletop) {
                artTotal++; m.redraw();
                console.log("[CardGame ArtPipeline] Generating tabletop...");
                await generateTabletop(activeTheme);
                artCompleted++; m.redraw();
            }
        }

        // Track unique cards and their duplicates
        let uniqueCards = {};  // signature -> { cardIndex, duplicateIndices: [] }

        deck.cards.forEach((card, i) => {
            // Skip cards that already have art
            if (card.type === "character" && card.portraitUrl) return;
            if (card.type !== "character" && card.imageUrl) return;

            let sig = cardSignature(card);
            if (uniqueCards[sig]) {
                // This is a duplicate - will share art with the first one
                uniqueCards[sig].duplicateIndices.push(i);
            } else {
                // First occurrence - queue for generation
                uniqueCards[sig] = { cardIndex: i, duplicateIndices: [] };
                artQueue.push({ card: card, cardIndex: i, status: "pending", result: null, error: null, duplicateIndices: [] });
                artTotal++;
            }
        });

        // Attach duplicate indices to queued items
        artQueue.forEach(job => {
            let sig = cardSignature(job.card);
            if (uniqueCards[sig]) {
                job.duplicateIndices = uniqueCards[sig].duplicateIndices;
            }
        });

        let dupeCount = Object.values(uniqueCards).reduce((sum, u) => sum + u.duplicateIndices.length, 0);
        if (dupeCount > 0) {
            console.log("[CardGame ArtPipeline] Found " + dupeCount + " duplicate cards that will share art");
        }

        if (artTotal === 0) {
            page.toast("info", "All cards already have art");
            return;
        }

        console.log("[CardGame ArtPipeline] Queued " + artTotal + " unique cards for art generation");
        processArtQueue();
    }

    // ── Art Progress Save / Pause / Resume / Cancel ─────────────────
    async function saveArtProgress() {
        let viewingDeck = getViewingDeck();
        let deckStorage = getDeckStorage();
        if (viewingDeck && deckStorage && artCompleted > 0) {
            let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
            await deckStorage.save(safeName, viewingDeck);
            console.log("[CardGame ArtPipeline] Deck saved with " + artCompleted + " completed art images");
        }
    }

    async function pauseArtQueue() {
        artPaused = true;
        await saveArtProgress();
        page.toast("info", "Art generation paused. Progress saved (" + artCompleted + " of " + artTotal + ")");
        m.redraw();
    }

    function resumeArtQueue() {
        artPaused = false;
        m.redraw();
        processArtQueue();
    }

    async function cancelArtQueue() {
        artQueue.forEach(j => { if (j.status === "pending") j.status = "cancelled"; });
        artPaused = false;
        artProcessing = false;
        await saveArtProgress();
        if (artCompleted > 0) {
            page.toast("info", "Art generation cancelled. Progress saved (" + artCompleted + " of " + artTotal + ")");
        }
        m.redraw();
    }

    // ── Image Sequence Generation (apparel dress-up progression) ───
    async function generateImageSequence(card, options) {
        let activeTheme = getActiveTheme();
        let viewingDeck = getViewingDeck();
        let deckStorage = getDeckStorage();

        if (!card || sequenceCardId) return;
        if (!card.sourceId && card._sourceChar) {
            page.toast("info", "Save deck first to generate images for generated characters");
            return;
        }
        if (!card.sourceId) return;
        sequenceCardId = card.sourceId;
        sequenceProgress = "Loading character...";
        page.toast("info", "Image Sequence: Loading character...", -1);
        m.redraw();

        try {
            // Load character to get store reference
            am7client.clearCache("olio.charPerson");
            let char = await page.searchFirst("olio.charPerson", undefined, undefined, card.sourceId);
            if (!char || !char.store || !char.store.objectId) throw new Error("Could not load character store");

            // Use getFull on store to get apparel with proper inuse flags
            await am7client.clearCache("olio.store");
            let sto = await am7client.getFull("olio.store", char.store.objectId);
            if (!sto || !sto.apparel || !sto.apparel.length) {
                throw new Error("No apparel found in store");
            }

            let activeApparel = sto.apparel.find(a => a.inuse) || sto.apparel[0];

            // Use getFull on apparel to get wearables with proper inuse flags
            await am7client.clearCache("olio.apparel");
            let fullApparel = await am7client.getFull("olio.apparel", activeApparel.objectId);
            if (!fullApparel || !fullApparel.wearables || !fullApparel.wearables.length) {
                throw new Error("No wearables found in apparel");
            }
            let wears = fullApparel.wearables;

            // Get sorted unique wear levels
            let lvls = [...new Set(wears.sort((a, b) => {
                let aL = am7model.enums.wearLevelEnumType.findIndex(we => we == a.level.toUpperCase());
                let bL = am7model.enums.wearLevelEnumType.findIndex(we => we == b.level.toUpperCase());
                return aL < bL ? -1 : aL > bL ? 1 : 0;
            }).map(w => w.level.toUpperCase()))];

            if (!lvls.length) throw new Error("No wear levels found");

            // Dress down completely
            sequenceProgress = "Dressing down...";
            page.toast("info", "Image Sequence: Dressing down...", -1);
            m.redraw();
            let dressedDown = true;
            while (dressedDown === true) {
                dressedDown = await am7olio.dressApparel(activeApparel, false);
            }

            // Update narrative to reflect fully undressed state
            am7client.clearCache("olio.charPerson");
            await m.request({ method: 'GET', url: am7client.base() + "/olio/olio.charPerson/" + card.sourceId + "/narrate", withCredentials: true });

            let images = [];
            let totalImages = lvls.length + 1; // +1 for undressed
            let baseSeed = -1;

            // Build base SD entity for character reimage
            let sdBase = await buildSdEntity(card, activeTheme);

            // Generate first image fully undressed (NONE level)
            sequenceProgress = "Generating image 1 of " + totalImages + " (NONE)...";
            page.toast("info", "Image Sequence: Generating 1 of " + totalImages + " (NONE)...", -1);
            m.redraw();

            let sdEntity = Object.assign({}, sdBase);
            sdEntity.seed = baseSeed;

            let x = await m.request({
                method: "POST",
                url: g_application_path + "/rest/olio/olio.charPerson/" + card.sourceId + "/reimage",
                body: sdEntity,
                withCredentials: true
            });

            if (x) {
                let seedAttr = (x.attributes || []).filter(a => a.name == "seed");
                if (seedAttr.length) baseSeed = seedAttr[0].value;
                await am7client.patchAttribute(x, "wearLevel", "NONE/0");
                images.push(x);
            }

            // Generate image at each wear level (dressing up progressively)
            for (let i = 0; i < lvls.length; i++) {
                await am7olio.dressApparel(activeApparel, true);

                // Update narrative so reimage reflects the new clothing state
                am7client.clearCache("olio.charPerson");
                await m.request({ method: 'GET', url: am7client.base() + "/olio/olio.charPerson/" + card.sourceId + "/narrate", withCredentials: true });

                sequenceProgress = "Generating image " + (images.length + 1) + " of " + totalImages + " (" + lvls[i] + ")...";
                page.toast("info", "Image Sequence: Generating " + (images.length + 1) + " of " + totalImages + " (" + lvls[i] + ")...", -1);
                m.redraw();

                let useSeed = (parseInt(baseSeed) + images.length);
                sdEntity = Object.assign({}, sdBase);
                sdEntity.seed = useSeed;

                let x = await m.request({
                    method: "POST",
                    url: g_application_path + "/rest/olio/olio.charPerson/" + card.sourceId + "/reimage",
                    body: sdEntity,
                    withCredentials: true
                });

                if (!x) {
                    page.toast("error", "Failed to create image at level " + lvls[i]);
                    break;
                }

                // Tag image with wear level
                let levelIndex = am7model.enums.wearLevelEnumType.findIndex(we => we == lvls[i]);
                await am7client.patchAttribute(x, "wearLevel", lvls[i] + "/" + levelIndex);

                images.push(x);
            }

            sequenceProgress = "";
            sequenceCardId = null;
            m.redraw();

            page.clearToast();  // Clear indefinite progress toast
            if (images.length > 0) {
                page.toast("success", "Created " + images.length + " images for " + card.name);

                // Update card portrait to the LAST generated image (fully dressed)
                let lastImage = images[images.length - 1];
                if (lastImage && lastImage.groupPath && lastImage.name) {
                    let orgPath = am7client.dotPath(am7client.currentOrganization);
                    card.portraitUrl = g_application_path + "/thumbnail/" + orgPath +
                        "/data.data" + lastImage.groupPath + "/" + encodeURIComponent(lastImage.name) + "/256x256?t=" + Date.now();
                    console.log("[CardGame ArtPipeline] Updated card portrait to last sequence image:", card.portraitUrl);

                    // Save the deck with updated portrait
                    if (viewingDeck && deckStorage) {
                        let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                        await deckStorage.save(safeName, viewingDeck);
                    }
                }

                m.redraw();

                // Open gallery picker so user can select from generated images
                // Skip during batch generation to avoid blocking on each character
                if (!options?.skipGallery) {
                    let openGallery = window.CardGame.Rendering?.openGalleryPicker;
                    if (openGallery) {
                        openGallery(card);
                    }
                }
            } else {
                page.toast("warn", "No images were generated");
            }

        } catch (e) {
            console.error("[CardGame ArtPipeline] Image sequence failed:", e);
            page.clearToast();  // Clear indefinite progress toast
            page.toast("error", "Image sequence failed: " + e.message);
            sequenceProgress = "";
            sequenceCardId = null;
            m.redraw();
        }
    }

    // ── Generate All Character Sequences ────────────────────────────
    async function generateAllSequences() {
        let viewingDeck = getViewingDeck();
        if (!viewingDeck || !viewingDeck.cards) return;
        if (batchSequenceActive || sequenceCardId) {
            page.toast("warn", "A sequence generation is already in progress");
            return;
        }

        // Filter to character cards with sourceId
        let charCards = viewingDeck.cards.filter(c =>
            c.type === "character" && c.sourceId
        );
        if (!charCards.length) {
            page.toast("warn", "No saved character cards found in deck");
            return;
        }

        batchSequenceActive = true;
        batchSequenceTotal = charCards.length;
        batchSequenceCompleted = 0;
        batchSequenceCurrentName = "";
        m.redraw();

        page.toast("info", "Generating sequences for " + charCards.length + " character(s)...");

        for (let i = 0; i < charCards.length; i++) {
            let card = charCards[i];
            batchSequenceCurrentName = card.name || ("Character " + (i + 1));
            m.redraw();

            try {
                await generateImageSequence(card, { skipGallery: true });
            } catch (e) {
                console.warn("[ArtPipeline] Batch sequence failed for", card.name, e);
            }

            batchSequenceCompleted = i + 1;
            m.redraw();
        }

        batchSequenceActive = false;
        batchSequenceCurrentName = "";
        m.redraw();
        page.toast("success", "All character sequences complete (" + batchSequenceCompleted + "/" + batchSequenceTotal + ")");
    }

    // ── Art Queue Progress Bar Component ────────────────────────────
    function ArtQueueProgress() {
        return {
            view() {
                // Show progress for any active generation (queue, background, tabletop, sequence, batch)
                let pending = artQueue.filter(j => j.status === "pending").length;
                let processing = artQueue.filter(j => j.status === "processing").length;
                let failed = artQueue.filter(j => j.status === "failed").length;
                let done = artQueue.filter(j => j.status === "complete").length;
                let queueActive = pending > 0 || processing > 0;
                let envActive = backgroundGenerating || tabletopGenerating || cardFrontGenerating || cardBackGenerating;
                let seqActive = !!sequenceCardId;
                let batchActive = batchSequenceActive;
                let anyActive = queueActive || envActive || seqActive || batchActive;

                // Nothing to show if no work has been started
                if (artTotal === 0 && !envActive && !seqActive && !batchActive) return null;

                // Calculate progress percentage based on what's active
                let pct = 0;
                let countText = "";
                if (batchActive) {
                    // Batch sequence: show batch-level progress
                    pct = batchSequenceTotal > 0 ? Math.min(100, Math.round((batchSequenceCompleted / batchSequenceTotal) * 100)) : 0;
                    countText = batchSequenceCompleted + " / " + batchSequenceTotal + " characters";
                } else if (envActive && artTotal === 0) {
                    // Environment-only generation (no queue): show indeterminate-style
                    pct = 50;
                    countText = "";
                } else if (seqActive && artTotal === 0) {
                    // Single sequence (no queue): parse progress from sequenceProgress
                    let seqMatch = sequenceProgress.match(/(\d+) of (\d+)/);
                    if (seqMatch) {
                        pct = Math.round((parseInt(seqMatch[1]) / parseInt(seqMatch[2])) * 100);
                        countText = seqMatch[1] + " / " + seqMatch[2] + " images";
                    } else {
                        pct = 50;
                        countText = "";
                    }
                } else {
                    // Standard queue progress
                    let totalDone = artCompleted + done;
                    pct = artTotal > 0 ? Math.min(100, Math.round((totalDone / artTotal) * 100)) : 0;
                    countText = totalDone + " / " + artTotal + (failed > 0 ? " (" + failed + " failed)" : "");
                }

                // Build current status message
                let currentMsg = null;
                if (backgroundGenerating) currentMsg = "Generating background...";
                else if (tabletopGenerating) currentMsg = "Generating tabletop...";
                else if (cardFrontGenerating) currentMsg = "Generating card front template...";
                else if (cardBackGenerating) currentMsg = "Generating card back template...";
                else if (batchActive && seqActive) currentMsg = (batchSequenceCurrentName || "Character") + ": " + (sequenceProgress || "preparing...");
                else if (batchActive) currentMsg = "Batch sequence: " + (batchSequenceCurrentName || "preparing...");
                else if (seqActive) currentMsg = sequenceProgress || "Generating image sequence...";
                else if (processing > 0) {
                    let current = artQueue.find(j => j.status === "processing");
                    currentMsg = current ? (current.card.name || "Card") : "Processing...";
                }

                let statusText = anyActive
                    ? (batchActive ? "Generating All Sequences..." : "Generating Art...")
                    : (failed > 0 ? "Generation Complete (with errors)" : "Generation Complete");

                return m("div", { class: "cg2-art-progress" }, [
                    m("div", { class: "cg2-art-progress-header" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "13px" } }, statusText),
                        countText ? m("span", { style: { fontSize: "12px", color: "#888" } }, countText) : null
                    ]),
                    m("div", { class: "cg2-art-progress-bar" }, [
                        m("div", { class: "cg2-art-progress-fill" + (anyActive ? " cg2-art-progress-fill-active" : ""),
                            style: { width: pct + "%" } })
                    ]),
                    currentMsg ? m("div", { class: "cg2-art-progress-current" }, [
                        m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px" } }, "progress_activity"),
                        " " + currentMsg
                    ]) : null,
                    queueActive ? m("div", { class: "cg2-art-progress-actions" }, [
                        artPaused
                            ? m("button", { class: "cg2-btn", onclick: resumeArtQueue }, "Resume")
                            : m("button", { class: "cg2-btn", onclick: pauseArtQueue }, "Pause"),
                        m("button", { class: "cg2-btn cg2-btn-danger", onclick: cancelArtQueue }, "Cancel")
                    ]) : null,
                    // Show failed items with retry
                    failed > 0 ? m("div", { class: "cg2-art-failures" },
                        artQueue.filter(j => j.status === "failed").map(j =>
                            m("div", { class: "cg2-art-fail-item" }, [
                                m("span", j.card.name + ": " + (j.error || "Failed")),
                                m("button", {
                                    class: "cg2-btn",
                                    style: { fontSize: "10px", padding: "1px 6px" },
                                    onclick() { j.status = "pending"; j.error = null; processArtQueue(); }
                                }, "Retry")
                            ])
                        )
                    ) : null
                ]);
            }
        };
    }

    // ── Public API ──────────────────────────────────────────────────
    window.CardGame.ArtPipeline = {
        state: {
            get artQueue() { return artQueue; },
            set artQueue(v) { artQueue = v; },
            get artProcessing() { return artProcessing; },
            set artProcessing(v) { artProcessing = v; },
            get artCompleted() { return artCompleted; },
            set artCompleted(v) { artCompleted = v; },
            get artTotal() { return artTotal; },
            set artTotal(v) { artTotal = v; },
            get artPaused() { return artPaused; },
            set artPaused(v) { artPaused = v; },
            get artDir() { return artDir; },
            set artDir(v) { artDir = v; },
            get backgroundImageId() { return backgroundImageId; },
            set backgroundImageId(v) { backgroundImageId = v; },
            get backgroundThumbUrl() { return backgroundThumbUrl; },
            set backgroundThumbUrl(v) { backgroundThumbUrl = v; },
            get backgroundPrompt() { return backgroundPrompt; },
            set backgroundPrompt(v) { backgroundPrompt = v; },
            get backgroundGenerating() { return backgroundGenerating; },
            set backgroundGenerating(v) { backgroundGenerating = v; },
            get tabletopImageId() { return tabletopImageId; },
            set tabletopImageId(v) { tabletopImageId = v; },
            get tabletopThumbUrl() { return tabletopThumbUrl; },
            set tabletopThumbUrl(v) { tabletopThumbUrl = v; },
            get tabletopGenerating() { return tabletopGenerating; },
            set tabletopGenerating(v) { tabletopGenerating = v; },
            get cardFrontImageUrl() { return cardFrontImageUrl; },
            set cardFrontImageUrl(v) { cardFrontImageUrl = v; },
            get cardBackImageUrl() { return cardBackImageUrl; },
            set cardBackImageUrl(v) { cardBackImageUrl = v; },
            get cardFrontGenerating() { return cardFrontGenerating; },
            set cardFrontGenerating(v) { cardFrontGenerating = v; },
            get cardBackGenerating() { return cardBackGenerating; },
            set cardBackGenerating(v) { cardBackGenerating = v; },
            get sequenceCardId() { return sequenceCardId; },
            set sequenceCardId(v) { sequenceCardId = v; },
            get sequenceProgress() { return sequenceProgress; },
            set sequenceProgress(v) { sequenceProgress = v; },
            get batchSequenceActive() { return batchSequenceActive; },
            get batchSequenceTotal() { return batchSequenceTotal; },
            get batchSequenceCompleted() { return batchSequenceCompleted; },
            get batchSequenceCurrentName() { return batchSequenceCurrentName; },
            get sdOverrides() { return sdOverrides; },
            set sdOverrides(v) { sdOverrides = v; },
            get sdConfigExpanded() { return sdConfigExpanded; },
            set sdConfigExpanded(v) { sdConfigExpanded = v; },
            get sdConfigTab() { return sdConfigTab; },
            set sdConfigTab(v) { sdConfigTab = v; },
            get gameConfigExpanded() { return gameConfigExpanded; },
            set gameConfigExpanded(v) { gameConfigExpanded = v; },
            get voiceProfiles() { return voiceProfiles; },
            set voiceProfiles(v) { voiceProfiles = v; },
            get voiceProfilesLoaded() { return voiceProfilesLoaded; },
            set voiceProfilesLoaded(v) { voiceProfilesLoaded = v; },
            get flippedCards() { return flippedCards; },
            set flippedCards(v) { flippedCards = v; },
            get sdOverrideInsts() { return sdOverrideInsts; },
            set sdOverrideInsts(v) { sdOverrideInsts = v; },
            get sdOverrideViews() { return sdOverrideViews; },
            set sdOverrideViews(v) { sdOverrideViews = v; }
        },
        // Prompt building
        styleLabel,
        buildCardPrompt,
        // SD entity builder
        buildSdEntity,
        // Art directory
        getArtDir: function() { return artDir; },
        ensureArtDir,
        // Environment generation
        generateBackground,
        generateTabletop,
        // Card art generation
        generateCardArt,
        generateTemplateArt,
        // Queue management
        processArtQueue,
        queueDeckArt,
        saveArtProgress,
        pauseArtQueue,
        resumeArtQueue,
        cancelArtQueue,
        // Game state propagation
        propagateArtToGameState,
        cardSignature,
        // Image sequence
        generateImageSequence,
        generateAllSequences,
        // SD config overrides
        newSdOverride,
        defaultSdOverrides,
        getCardTypeDelta,
        getSdOverrideInst,
        resetSdOverrideInsts,
        // Game config
        loadVoiceProfiles,
        getDeckGameConfig,
        currentDeckSafeName,
        // UI Component
        ArtQueueProgress
    };

    console.log('[CardGame] ArtPipeline service loaded (' +
        Object.keys(window.CardGame.ArtPipeline).length + ' exports)');

}());
