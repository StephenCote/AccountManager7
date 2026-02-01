/**
 * ImageGenerationManager - Stable Diffusion img2img generation from camera captures
 * Captures user images and transforms them using the olio reimage API
 */
class ImageGenerationManager {
    constructor(sdConfigObjectId = null, inlineConfig = null) {
        this.sdConfigId = sdConfigObjectId;
        this.inlineConfig = inlineConfig;
        this.sdConfig = null;
        this.pendingGenerations = [];
        this.generatedImages = [];
        this.maxPendingJobs = 3;
        this.isProcessing = false;
        this.sessionName = null;

        this.onImageGenerated = null;
        this.onGenerationStarted = null;
        this.onGenerationFailed = null;
        this.onQueueChanged = null;
        this.onCaptureCreated = null;

        // Cached server resources
        this._captureDir = null;
        this._generatedDir = null;
        this._baseEntity = null;
    }

    /**
     * Load SD configuration from server
     * @returns {Promise<Object>} Configuration object
     */
    async loadConfig() {
        // Use inline config if provided (created via SessionConfigEditor "Create New")
        if (this.inlineConfig) {
            this.sdConfig = { ...this._getDefaultConfig(), ...this.inlineConfig };
            return this.sdConfig;
        }

        if (!this.sdConfigId) {
            this.sdConfig = this._getDefaultConfig();
            return this.sdConfig;
        }

        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("objectId", this.sdConfigId);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                let obj = qr.results[0];
                if (obj.dataBytesStore && obj.dataBytesStore.length) {
                    this.sdConfig = { ...this._getDefaultConfig(), ...JSON.parse(uwm.base64Decode(obj.dataBytesStore)) };
                    return this.sdConfig;
                }
            }
            this.sdConfig = this._getDefaultConfig();
            return this.sdConfig;
        } catch (err) {
            console.error('ImageGenerationManager: Failed to load config:', err);
            this.sdConfig = this._getDefaultConfig();
            return this.sdConfig;
        }
    }

    /**
     * Get default SD configuration
     * @returns {Object}
     * @private
     */
    _getDefaultConfig() {
        return {
            style: "art",
            description: "ethereal dreamlike portrait, soft lighting, mystical atmosphere",
            imageAction: "posing in a surreal setting",
            denoisingStrength: 0.65,
            steps: 30,
            cfg: 7,
            seed: -1,
            sampler: "dpmpp_2m",
            scheduler: "Karras",
            width: 512,
            height: 512,
            captureInterval: 30000,
            emotionPromptMapping: {
                happy: "joyful radiant golden light",
                sad: "melancholic blue ethereal mist",
                angry: "intense fiery dramatic shadows",
                fear: "mysterious shadowy surreal",
                surprise: "magical sparkling wonder",
                neutral: "serene peaceful calm atmosphere"
            }
        };
    }

    /**
     * Set custom configuration
     * @param {Object} config - SD configuration
     */
    setConfig(config) {
        this.sdConfig = { ...this._getDefaultConfig(), ...config };
    }

    /**
     * Capture frame from camera and generate image
     * @param {Object} cameraComponent - Camera component with captureFrame method
     * @param {Object} biometricData - Current biometric data for emotion-aware prompts
     * @returns {Promise<string>} Job ID
     */
    async captureAndGenerate(cameraComponent, biometricData = null) {
        if (!this.sdConfig) {
            await this.loadConfig();
        }

        const activeJobs = this.pendingGenerations.filter(
            j => j.status === 'pending' || j.status === 'processing'
        ).length;
        if (activeJobs >= this.maxPendingJobs) {
            console.warn('ImageGenerationManager: Max pending jobs reached (' + activeJobs + ' active)');
            return null;
        }

        try {
            // Capture current frame
            let imageData;
            if (typeof cameraComponent.captureFrame === 'function') {
                imageData = await cameraComponent.captureFrame();
            } else if (typeof cameraComponent.capture === 'function') {
                imageData = await cameraComponent.capture();
            } else {
                throw new Error('Camera component does not have capture method');
            }

            // Extract base64 data
            let base64Image = imageData;
            if (imageData.includes(',')) {
                base64Image = imageData.split(',')[1];
            }

            // Build description with emotion awareness
            let description = this.sdConfig.description || "";
            if (biometricData && biometricData.dominant_emotion) {
                const emotionPrompt = this.sdConfig.emotionPromptMapping?.[biometricData.dominant_emotion];
                if (emotionPrompt) {
                    description = `${emotionPrompt}, ${description}`;
                }
            }

            // Build generation request using real olio.sd.config field names
            const genRequest = { ...this.sdConfig };
            genRequest.description = description;
            genRequest.init_image = base64Image;

            return this.queueGeneration(genRequest);

        } catch (err) {
            console.error('ImageGenerationManager: Failed to capture and generate:', err);
            if (this.onGenerationFailed) {
                this.onGenerationFailed(null, err);
            }
            return null;
        }
    }

    /**
     * Queue a generation request
     * @param {Object} request - Generation request
     * @returns {string} Job ID
     */
    queueGeneration(request) {
        const jobId = `gen-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

        const job = {
            id: jobId,
            request: request,
            status: 'pending',
            createdAt: Date.now(),
            result: null,
            error: null
        };

        this.pendingGenerations.push(job);

        if (this.onQueueChanged) {
            this.onQueueChanged(this.pendingGenerations.length);
        }

        // Start processing if not already
        this._processQueue();

        return jobId;
    }

    /**
     * Process the generation queue using olio reimage API
     * Flow: save camera frame -> build SD config -> call reimage -> handle response
     * @private
     */
    async _processQueue() {
        if (this.isProcessing) return;

        const pendingJob = this.pendingGenerations.find(j => j.status === 'pending');
        if (!pendingJob) return;

        this.isProcessing = true;
        pendingJob.status = 'processing';

        if (this.onGenerationStarted) {
            this.onGenerationStarted(pendingJob.id);
        }

        try {
            // Step 1: Save camera frame to ~/Magic8/Captures
            const captureObj = await this._saveCameraFrame(
                pendingJob.request.init_image,
                pendingJob.id
            );
            if (!captureObj || !captureObj.objectId) {
                throw new Error('Failed to save camera capture');
            }
            console.log('ImageGenerationManager: Camera frame saved:', captureObj.objectId);

            // Step 2: Build olio.sd.config entity for the reimage API
            const sdEntity = await this._buildSdConfigEntity(pendingJob.request, captureObj.objectId);

            // Step 3: Call reimage endpoint (same API used by dialog.js)
            const reimageUrl = `${g_application_path}/rest/olio/data.data/${captureObj.objectId}/reimage`;
            console.log('ImageGenerationManager: Calling reimage for', captureObj.objectId);
            let response;
            try {
                response = await m.request({
                    method: 'POST',
                    url: reimageUrl,
                    withCredentials: true,
                    body: sdEntity
                });
            } catch (reqErr) {
                const detail = reqErr?.message || (reqErr != null ? JSON.stringify(reqErr) : 'server returned null/empty error');
                console.warn('ImageGenerationManager: reimage response error:', reqErr);
                throw new Error(`Reimage request failed (${reimageUrl}): ${detail}`);
            }

            if (response && response.objectId) {
                // Rename to distinguish from captures
                if (response.name && !response.name.startsWith('generated-')) {
                    response.name = 'generated-' + Date.now() + '.' + (response.name.split('.').pop() || 'png');
                }
                // Move generated image to ~/Magic8/Generated
                const movedImage = await this._moveToGeneratedGroup(response);
                const imageUrl = `${g_application_path}/media/Public/data.data${encodeURI(movedImage.groupPath)}/${encodeURIComponent(movedImage.name)}`;

                const savedImage = {
                    objectId: movedImage.objectId,
                    url: imageUrl,
                    name: movedImage.name,
                    groupPath: movedImage.groupPath
                };

                pendingJob.status = 'complete';
                pendingJob.result = savedImage;

                this.generatedImages.push({
                    id: pendingJob.id,
                    url: savedImage.url,
                    objectId: savedImage.objectId,
                    timestamp: Date.now()
                });

                if (this.onImageGenerated) {
                    this.onImageGenerated(savedImage);
                }
            } else {
                throw new Error('No image returned from generation');
            }

        } catch (err) {
            console.error('ImageGenerationManager: Generation failed:', err);
            pendingJob.status = 'failed';
            pendingJob.error = err.message || String(err);

            if (this.onGenerationFailed) {
                this.onGenerationFailed(pendingJob.id, err);
            }
        }

        // Remove completed/failed jobs older than 5 minutes
        const cutoff = Date.now() - 5 * 60 * 1000;
        this.pendingGenerations = this.pendingGenerations.filter(
            j => j.status === 'pending' || j.status === 'processing' || j.createdAt > cutoff
        );

        if (this.onQueueChanged) {
            this.onQueueChanged(this.pendingGenerations.length);
        }

        this.isProcessing = false;

        // Process next job
        this._processQueue();
    }

    /**
     * Save camera frame as data.data in ~/Magic8/Captures
     * @param {string} base64Image - Base64 encoded image data
     * @param {string} jobId - Job ID for naming
     * @returns {Promise<Object>} Saved data.data object
     * @private
     */
    async _saveCameraFrame(base64Image, jobId) {
        if (!this._captureDir) {
            const capturePath = this.sessionName
                ? '~/Magic8/Captures/' + this.sessionName
                : '~/Magic8/Captures';
            this._captureDir = await page.findObject("auth.group", "DATA", capturePath);
            if (!this._captureDir || !this._captureDir.objectId) {
                this._captureDir = await page.makePath("auth.group", "data", capturePath);
            }
        }

        let obj = am7model.newPrimitive("data.data");
        obj.name = `capture-${Date.now()}.png`;
        obj.contentType = 'image/png';
        obj.compressionType = 'none';
        obj.groupId = this._captureDir.id;
        obj.groupPath = this._captureDir.path;
        obj.dataBytesStore = base64Image;

        const created = await page.createObject(obj);

        if (created && this.onCaptureCreated) {
            this.onCaptureCreated({
                objectId: created.objectId,
                groupPath: created.groupPath || this._captureDir.path,
                name: created.name || obj.name
            });
        }

        return created;
    }

    /**
     * Move a generated image to ~/Magic8/Generated group
     * @param {Object} imageObj - data.data object returned from reimage API
     * @returns {Promise<Object>} Updated object with new groupPath
     * @private
     */
    async _moveToGeneratedGroup(imageObj) {
        if (!this._generatedDir) {
            const genPath = this.sessionName
                ? '~/Magic8/Generated/' + this.sessionName
                : '~/Magic8/Generated';
            this._generatedDir = await page.findObject("auth.group", "DATA", genPath);
            if (!this._generatedDir || !this._generatedDir.objectId) {
                this._generatedDir = await page.makePath("auth.group", "data", genPath);
            }
        }

        // If already in the right group, skip
        if (imageObj.groupId === this._generatedDir.id) {
            return imageObj;
        }

        await page.moveObject(imageObj, this._generatedDir);
        imageObj.groupId = this._generatedDir.id;
        imageObj.groupPath = this._generatedDir.path;
        console.log('ImageGenerationManager: Moved generated image to ~/Magic8/Generated:', imageObj.objectId);
        return imageObj;
    }

    /**
     * Build an olio.sd.config entity for the reimage API
     * Maps internal config fields to the server entity format
     * @param {Object} request - Internal generation request
     * @param {string} referenceImageId - ObjectId of the saved camera frame
     * @returns {Promise<Object>} SD config entity
     * @private
     */
    async _buildSdConfigEntity(request, referenceImageId) {
        // Fetch base entity structure from server (cached)
        if (!this._baseEntity) {
            try {
                this._baseEntity = await m.request({
                    method: 'GET',
                    url: `${g_application_path}/rest/olio/randomImageConfig`,
                    withCredentials: true
                });
            } catch (e) {
                console.warn('ImageGenerationManager: Could not fetch randomImageConfig:', e);
                this._baseEntity = null;
            }
        }
        if (!this._baseEntity) {
            throw new Error('No SD config template available (randomImageConfig endpoint failed)');
        }

        let entity = Object.assign({}, this._baseEntity);

        // Apply config fields directly (already using real olio.sd.config field names)
        // Skip internal-only fields that aren't part of the server entity
        const skipFields = new Set(['init_image', 'captureInterval', 'emotionPromptMapping']);
        for (const [key, value] of Object.entries(request)) {
            if (!skipFields.has(key) && value != null) {
                entity[key] = value;
            }
        }

        // If a refiner model is configured, force hires mode
        if (entity.refinerModel) {
            entity.hires = true;
        }

        // Fill null style-specific fields with random defaults
        this._fillStyleDefaults(entity);

        // Set reference image for img2img
        entity.referenceImageId = referenceImageId;

        return entity;
    }

    /**
     * Fill null style-specific fields with random fallback values.
     * Prevents "(null)" from appearing in server-built prompts when
     * randomImageConfig omits style-specific fields.
     * @param {Object} entity - SD config entity
     * @private
     */
    _fillStyleDefaults(entity) {
        const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
        const style = entity.style;
        if (!style) return;

        // Fallback lists (subset of olio/sd/sdConfigData.json)
        const data = ImageGenerationManager._SD_FALLBACKS;

        switch (style) {
            case 'photograph':
                if (!entity.stillCamera) entity.stillCamera = pick(data.stillCameras);
                if (!entity.film) entity.film = pick(data.films);
                if (!entity.lens) entity.lens = pick(data.lenses);
                if (!entity.colorProcess) entity.colorProcess = pick(data.colorProcesses);
                if (!entity.photographer) entity.photographer = pick(data.photographers);
                break;
            case 'movie':
                if (!entity.movieCamera) entity.movieCamera = pick(data.movieCameras);
                if (!entity.movieFilm) entity.movieFilm = pick(data.movieFilms);
                if (!entity.colorProcess) entity.colorProcess = pick(data.colorProcesses);
                if (!entity.director) entity.director = pick(data.directors);
                break;
            case 'selfie':
                if (!entity.selfiePhone) entity.selfiePhone = pick(data.selfiePhones);
                if (!entity.selfieAngle) entity.selfieAngle = pick(data.selfieAngles);
                if (!entity.selfieLighting) entity.selfieLighting = pick(data.selfieLightings);
                break;
            case 'anime':
                if (!entity.animeStudio) entity.animeStudio = pick(data.animeStudios);
                if (!entity.animeEra) entity.animeEra = pick(data.animeEras);
                break;
            case 'portrait':
                if (!entity.portraitLighting) entity.portraitLighting = pick(data.portraitLightings);
                if (!entity.portraitBackdrop) entity.portraitBackdrop = pick(data.portraitBackdrops);
                if (!entity.photographer) entity.photographer = pick(data.photographers);
                break;
            case 'comic':
                if (!entity.comicPublisher) entity.comicPublisher = pick(data.comicPublishers);
                if (!entity.comicEra) entity.comicEra = pick(data.comicEras);
                if (!entity.comicColoring) entity.comicColoring = pick(data.comicColorings);
                break;
            case 'digitalArt':
                if (!entity.digitalMedium) entity.digitalMedium = pick(data.digitalMediums);
                if (!entity.digitalSoftware) entity.digitalSoftware = pick(data.digitalSoftwares);
                if (!entity.digitalArtist) entity.digitalArtist = pick(data.digitalArtists);
                break;
            case 'fashion':
                if (!entity.fashionMagazine) entity.fashionMagazine = pick(data.fashionMagazines);
                if (!entity.fashionDecade) entity.fashionDecade = pick(data.fashionDecades);
                if (!entity.photographer) entity.photographer = pick(data.photographers);
                break;
            case 'vintage':
                if (!entity.vintageDecade) entity.vintageDecade = pick(data.vintageDecades);
                if (!entity.vintageProcessing) entity.vintageProcessing = pick(data.vintageProcessings);
                if (!entity.vintageCamera) entity.vintageCamera = pick(data.vintageCameras);
                break;
            case 'art':
                if (!entity.artStyle) entity.artStyle = pick(data.artStyles);
                break;
        }
    }

    /**
     * Get job status
     * @param {string} jobId - Job ID
     * @returns {Object|null} Job info
     */
    getJob(jobId) {
        return this.pendingGenerations.find(j => j.id === jobId) || null;
    }

    /**
     * Get all pending jobs
     * @returns {Array}
     */
    getPendingJobs() {
        return this.pendingGenerations.filter(j => j.status === 'pending' || j.status === 'processing');
    }

    /**
     * Get all generated images
     * @returns {Array}
     */
    getGeneratedImages() {
        return [...this.generatedImages];
    }

    /**
     * Cancel a pending job
     * @param {string} jobId - Job ID
     */
    cancelJob(jobId) {
        const job = this.pendingGenerations.find(j => j.id === jobId);
        if (job && job.status === 'pending') {
            job.status = 'cancelled';
        }
    }

    /**
     * Clear all generated images
     */
    clearGeneratedImages() {
        this.generatedImages = [];
    }

    /**
     * Set maximum pending jobs
     * @param {number} max - Maximum number of pending jobs
     */
    setMaxPendingJobs(max) {
        this.maxPendingJobs = Math.max(1, max);
    }

    /**
     * Clean up resources
     */
    dispose() {
        this.pendingGenerations = [];
        this.generatedImages = [];
        this.sdConfig = null;
        this._captureDir = null;
        this._generatedDir = null;
        this._baseEntity = null;
        this.onImageGenerated = null;
        this.onGenerationStarted = null;
        this.onGenerationFailed = null;
        this.onQueueChanged = null;
        this.onCaptureCreated = null;
    }
}

// Fallback values for null style-specific fields (subset of olio/sd/sdConfigData.json)
ImageGenerationManager._SD_FALLBACKS = {
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

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = ImageGenerationManager;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.ImageGenerationManager = ImageGenerationManager;
}
