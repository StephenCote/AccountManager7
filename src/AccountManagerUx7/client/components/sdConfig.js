// sdConfig.js — Common SD config utility for olio.sd.config model
// Provides shared functions for fetching templates, loading/saving configs,
// building entities, and filling style defaults across dialog.js, cardGame-v2.js,
// and ImageGenerationManager.js.
//
// Registers as window.am7sd

(function () {

    let _templateCache = null;

    // ── Style-specific field names ────────────────────────────────────
    const STYLE_FIELDS = [
        "artStyle",
        "stillCamera", "film", "lens", "colorProcess", "photographer",
        "movieCamera", "movieFilm", "director",
        "selfiePhone", "selfieAngle", "selfieLighting",
        "animeStudio", "animeEra",
        "portraitLighting", "portraitBackdrop",
        "comicPublisher", "comicEra", "comicColoring",
        "digitalMedium", "digitalSoftware", "digitalArtist",
        "fashionMagazine", "fashionDecade",
        "vintageDecade", "vintageProcessing", "vintageCamera",
        "customPrompt", "photoStyle"
    ];

    // ── Transient fields excluded from save/apply operations ─────────
    const SAVE_EXCLUDE = ["narration", "id", "objectId", "ownerId"];
    const APPLY_EXCLUDE = ["narration", "id", "objectId", "groupId", "organizationId", "ownerId", "groupPath", "organizationPath", "seed"];

    // ── Fallback data for style-specific fields (subset of sdConfigData.json) ──
    const SD_FALLBACKS = {
        stillCameras: ["Leica M3", "Canon AE-1 SLR", "Hasselblad 500C", "Rolleiflex 2.8F", "Nikon F", "Pentax 67", "Mamiya RZ67", "Contax 645", "Polaroid SX-70", "Kodak Brownie box"],
        films: ["Kodak Tri-X", "Kodak Ektachrome", "Kodak Kodachrome", "Fujifilm Velvia", "Kodak Portra 400", "Ilford HP5 Plus", "Kodak Ektar 100", "Polaroid Instant"],
        lenses: ["50mm Summicron", "Carl Zeiss Planar 80mm f/2.8", "Nikkor 50mm f/1.4", "Canon FD 50mm f/1.8", "Large format"],
        colorProcesses: ["Technicolor", "Kodachrome", "Kodacolor", "Fujicolor", "Zone System Black and White"],
        photographers: ["Ansel Adams", "Annie Leibovitz", "Steve McCurry", "Henri Cartier-Bresson", "Richard Avedon", "Irving Penn", "David LaChapelle", "Helmut Newton", "Peter Lindbergh"],
        movieCameras: ["ARRI ALEXA", "RED ONE", "Super Panavision 70", "Bolex H16", "Mitchell BNC camera"],
        movieFilms: ["Super 8", "16 mm", "35 mm", "65 mm"],
        directors: ["Stanley Kubrick", "Steven Spielberg", "Ridley Scott", "David Lynch", "Wes Anderson", "Christopher Nolan", "Akira Kurosawa"],
        selfiePhones: ["iPhone 15 Pro", "Samsung Galaxy S24 Ultra", "Google Pixel 8 Pro", "iPhone 14", "Samsung Galaxy S23"],
        selfieAngles: ["high angle", "low angle", "straight-on", "dutch angle", "mirror reflection", "arm's length"],
        selfieLightings: ["golden hour", "ring light", "natural window light", "neon lights", "blue hour twilight", "studio softbox"],
        animeStudios: ["Studio Ghibli", "Madhouse", "Ufotable", "Kyoto Animation", "MAPPA", "Trigger", "Wit Studio"],
        animeEras: ["1990s golden age", "2010s modern", "1980s OVA era", "2020s hyperdetailed", "retro 80s cyberpunk"],
        portraitLightings: ["Rembrandt lighting", "butterfly lighting", "split lighting", "rim lighting", "high key", "low key", "natural ambient"],
        portraitBackdrops: ["seamless white", "dark charcoal muslin", "natural outdoor bokeh", "studio gradient", "textured brick wall"],
        comicPublishers: ["Marvel Comics", "DC Comics", "Image Comics", "Dark Horse Comics", "2000 AD"],
        comicEras: ["Golden Age 1930s-1940s", "Silver Age 1950s-1960s", "Modern Age late 1980s-1990s", "2010s indie renaissance"],
        comicColorings: ["four-color process", "digital cel shading", "Ben-Day dots halftone", "fully painted oils", "ink wash grayscale"],
        digitalMediums: ["concept art illustration", "3D render", "matte painting", "digital oil painting", "environment concept art"],
        digitalSoftwares: ["Photoshop", "Blender 3D", "Unreal Engine 5", "Procreate", "Octane Render"],
        digitalArtists: ["Greg Rutkowski", "Artgerm", "Craig Mullins", "Simon Stalenhag", "James Gurney", "Wlop"],
        fashionMagazines: ["Vogue editorial", "Harper's Bazaar spread", "GQ editorial", "Vanity Fair portrait", "W Magazine avant-garde"],
        fashionDecades: ["1950s New Look elegance", "1960s mod revolution", "1970s bohemian chic", "1980s power dressing", "2010s streetwear luxury"],
        vintageDecades: ["1920s jazz age", "1940s wartime", "1950s Americana", "1960s counterculture", "1970s Polaroid era"],
        vintageProcessings: ["daguerreotype", "cyanotype print", "Kodachrome slide", "sepia toned", "cross-processed E6", "expired film effect"],
        vintageCameras: ["Kodak Brownie No. 2", "Polaroid Land Camera 95", "Holga 120", "Lomo LC-A", "Graflex Speed Graphic"],
        artStyles: ["Impressionist painting with soft brush strokes and vibrant colors", "Surrealist artwork with dream-like elements", "Pop art piece with bold colors and iconic imagery", "Renaissance painting with classical composition", "Art Nouveau piece with flowing lines and floral motifs"]
    };

    function pick(arr) {
        return arr[Math.floor(Math.random() * arr.length)];
    }

    // ── fetchTemplate ─────────────────────────────────────────────────
    // Cached fetch of /rest/olio/randomImageConfig.
    // Returns a fresh copy (Object.assign) so callers can mutate safely.
    async function fetchTemplate(forceRefresh) {
        if (!_templateCache || forceRefresh) {
            try {
                _templateCache = await m.request({
                    method: "GET",
                    url: am7client.base() + "/olio/randomImageConfig",
                    withCredentials: true
                });
            } catch (e) {
                console.warn("[am7sd] Could not fetch randomImageConfig:", e);
                _templateCache = null;
            }
        }
        return _templateCache ? Object.assign({}, _templateCache) : null;
    }

    // ── loadConfig ────────────────────────────────────────────────────
    // Load a named SD config from the server (base64-encoded JSON in data.data).
    // groupPath defaults to ~/Data/.preferences.
    async function loadConfig(name, groupPath) {
        let gp = groupPath || "~/Data/.preferences";
        let grp = await page.makePath("auth.group", "DATA", gp);
        if (!grp) return null;
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("groupId", grp.id);
            q.field("name", name);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                let obj = qr.results[0];
                if (obj.dataBytesStore && obj.dataBytesStore.length) {
                    return JSON.parse(uwm.base64Decode(obj.dataBytesStore));
                }
            }
        } catch (e) {
            console.warn("[am7sd] Failed to load config: " + name, e);
        }
        return null;
    }

    // ── saveConfig ────────────────────────────────────────────────────
    // Save a named SD config to the server.
    // groupPath defaults to ~/Data/.preferences.
    async function saveConfig(name, config, groupPath) {
        let gp = groupPath || "~/Data/.preferences";
        let grp = await page.makePath("auth.group", "DATA", gp);
        if (!grp) return false;

        let saveObj = {};
        for (let k in config) {
            if (!SAVE_EXCLUDE.includes(k)) {
                saveObj[k] = config[k];
            }
        }

        let obj;
        try {
            obj = await page.searchByName("data.data", grp.objectId, name);
        } catch (e) {
            // Object doesn't exist yet
        }

        if (!obj) {
            obj = am7model.newPrimitive("data.data");
            obj.name = name;
            obj.mimeType = "application/json";
            obj.groupId = grp.id;
            obj.groupPath = grp.path;
            obj.dataBytesStore = uwm.base64Encode(JSON.stringify(saveObj));
            await page.createObject(obj);
        } else {
            let patch = { id: obj.id, compressionType: "none", dataBytesStore: uwm.base64Encode(JSON.stringify(saveObj)) };
            patch[am7model.jsonModelKey] = "data.data";
            await page.patchObject(patch);
        }
        return true;
    }

    // ── applyConfig ───────────────────────────────────────────────────
    // Apply a config object to a form instance (cinst.api.field(value)).
    function applyConfig(cinst, config) {
        if (!config) return;
        for (let k in config) {
            if (!APPLY_EXCLUDE.includes(k) && cinst.api[k]) {
                cinst.api[k](config[k]);
            }
        }
    }

    // ── fillStyleDefaults ─────────────────────────────────────────────
    // Fill null style-specific fields with random fallback values.
    // Prevents "(null)" in server-built prompts from SDUtil.getSDConfigPrompt().
    function fillStyleDefaults(entity) {
        let style = entity.style;
        if (!style) return;

        switch (style) {
            case "photograph":
                if (!entity.stillCamera) entity.stillCamera = pick(SD_FALLBACKS.stillCameras);
                if (!entity.film) entity.film = pick(SD_FALLBACKS.films);
                if (!entity.lens) entity.lens = pick(SD_FALLBACKS.lenses);
                if (!entity.colorProcess) entity.colorProcess = pick(SD_FALLBACKS.colorProcesses);
                if (!entity.photographer) entity.photographer = pick(SD_FALLBACKS.photographers);
                break;
            case "movie":
                if (!entity.movieCamera) entity.movieCamera = pick(SD_FALLBACKS.movieCameras);
                if (!entity.movieFilm) entity.movieFilm = pick(SD_FALLBACKS.movieFilms);
                if (!entity.colorProcess) entity.colorProcess = pick(SD_FALLBACKS.colorProcesses);
                if (!entity.director) entity.director = pick(SD_FALLBACKS.directors);
                break;
            case "selfie":
                if (!entity.selfiePhone) entity.selfiePhone = pick(SD_FALLBACKS.selfiePhones);
                if (!entity.selfieAngle) entity.selfieAngle = pick(SD_FALLBACKS.selfieAngles);
                if (!entity.selfieLighting) entity.selfieLighting = pick(SD_FALLBACKS.selfieLightings);
                break;
            case "anime":
                if (!entity.animeStudio) entity.animeStudio = pick(SD_FALLBACKS.animeStudios);
                if (!entity.animeEra) entity.animeEra = pick(SD_FALLBACKS.animeEras);
                break;
            case "portrait":
                if (!entity.portraitLighting) entity.portraitLighting = pick(SD_FALLBACKS.portraitLightings);
                if (!entity.portraitBackdrop) entity.portraitBackdrop = pick(SD_FALLBACKS.portraitBackdrops);
                if (!entity.photographer) entity.photographer = pick(SD_FALLBACKS.photographers);
                break;
            case "comic":
                if (!entity.comicPublisher) entity.comicPublisher = pick(SD_FALLBACKS.comicPublishers);
                if (!entity.comicEra) entity.comicEra = pick(SD_FALLBACKS.comicEras);
                if (!entity.comicColoring) entity.comicColoring = pick(SD_FALLBACKS.comicColorings);
                break;
            case "digitalArt":
                if (!entity.digitalMedium) entity.digitalMedium = pick(SD_FALLBACKS.digitalMediums);
                if (!entity.digitalSoftware) entity.digitalSoftware = pick(SD_FALLBACKS.digitalSoftwares);
                if (!entity.digitalArtist) entity.digitalArtist = pick(SD_FALLBACKS.digitalArtists);
                break;
            case "fashion":
                if (!entity.fashionMagazine) entity.fashionMagazine = pick(SD_FALLBACKS.fashionMagazines);
                if (!entity.fashionDecade) entity.fashionDecade = pick(SD_FALLBACKS.fashionDecades);
                if (!entity.photographer) entity.photographer = pick(SD_FALLBACKS.photographers);
                break;
            case "vintage":
                if (!entity.vintageDecade) entity.vintageDecade = pick(SD_FALLBACKS.vintageDecades);
                if (!entity.vintageProcessing) entity.vintageProcessing = pick(SD_FALLBACKS.vintageProcessings);
                if (!entity.vintageCamera) entity.vintageCamera = pick(SD_FALLBACKS.vintageCameras);
                break;
            case "art":
                if (!entity.artStyle) entity.artStyle = pick(SD_FALLBACKS.artStyles);
                break;
        }
    }

    // ── applyOverrides ────────────────────────────────────────────────
    // Apply an override object onto an entity.
    // Override entities use model defaults, so all fields carry real values.
    function applyOverrides(entity, ov) {
        if (!ov) return;
        if (ov.model) entity.model = ov.model;
        if (ov.refinerModel) entity.refinerModel = ov.refinerModel;
        if (ov.cfg > 0) entity.cfg = ov.cfg;
        if (ov.refinerCfg > 0) entity.refinerCfg = ov.refinerCfg;
        if (ov.steps > 0) entity.steps = ov.steps;
        if (ov.refinerSteps > 0) entity.refinerSteps = ov.refinerSteps;
        if (ov.width > 0) entity.width = ov.width;
        if (ov.height > 0) entity.height = ov.height;
        if (ov.hires !== null && ov.hires !== undefined) entity.hires = ov.hires;
        if (ov.sampler) entity.sampler = ov.sampler;
        if (ov.scheduler) entity.scheduler = ov.scheduler;
        if (ov.style) entity.style = ov.style;
        if (ov.denoisingStrength > 0) entity.denoisingStrength = ov.denoisingStrength;
        if (ov.bodyStyle) entity.bodyStyle = ov.bodyStyle;
        if (ov.imageSetting) entity.imageSetting = ov.imageSetting;
    }

    // ── buildEntity ───────────────────────────────────────────────────
    // Build a complete SD config entity from the random template plus overrides.
    //
    // options.fillDefaults (default true) — call fillStyleDefaults on the result
    // options.skipFields — array of field names to exclude from overrides merge
    //
    // Returns a ready-to-POST entity with all style fields intact.
    async function buildEntity(overrides, options) {
        let opts = options || {};
        let entity = await fetchTemplate();
        if (!entity) throw new Error("No SD config template available");

        if (overrides) {
            let skipSet = opts.skipFields ? new Set(opts.skipFields) : null;
            for (let key in overrides) {
                if (overrides[key] != null && (!skipSet || !skipSet.has(key))) {
                    entity[key] = overrides[key];
                }
            }
        }

        if (opts.fillDefaults !== false) {
            fillStyleDefaults(entity);
        }

        return entity;
    }

    // ── Public API ────────────────────────────────────────────────────
    if (typeof window !== "undefined") {
        window.am7sd = {
            fetchTemplate: fetchTemplate,
            loadConfig: loadConfig,
            saveConfig: saveConfig,
            applyConfig: applyConfig,
            fillStyleDefaults: fillStyleDefaults,
            applyOverrides: applyOverrides,
            buildEntity: buildEntity,
            STYLE_FIELDS: STYLE_FIELDS,
            SD_FALLBACKS: SD_FALLBACKS
        };
    }

})();
