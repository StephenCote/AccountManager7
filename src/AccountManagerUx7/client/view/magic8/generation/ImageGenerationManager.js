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

        this.onImageGenerated = null;
        this.onGenerationStarted = null;
        this.onGenerationFailed = null;
        this.onQueueChanged = null;

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

        if (this.pendingGenerations.length >= this.maxPendingJobs) {
            console.warn('ImageGenerationManager: Max pending jobs reached');
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
            console.log('ImageGenerationManager: Calling reimage for', captureObj.objectId);
            const response = await m.request({
                method: 'POST',
                url: `${g_application_path}/rest/olio/data.data/${captureObj.objectId}/reimage`,
                withCredentials: true,
                body: sdEntity
            });

            if (response && response.objectId) {
                // Move generated image to ~/Magic8/Generated
                const movedImage = await this._moveToGeneratedGroup(response);
                const imageUrl = `${g_application_path}/media/Public/data.data${encodeURI(movedImage.groupPath)}/${encodeURIComponent(movedImage.name)}`;

                const savedImage = {
                    objectId: movedImage.objectId,
                    url: imageUrl,
                    name: movedImage.name
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
            this._captureDir = await page.findObject("auth.group", "DATA", "~/Magic8/Captures");
            if (!this._captureDir || !this._captureDir.objectId) {
                this._captureDir = await page.makePath("auth.group", "data", "~/Magic8/Captures");
            }
        }

        let obj = am7model.newPrimitive("data.data");
        obj.name = `capture-${Date.now()}.png`;
        obj.contentType = 'image/png';
        obj.groupId = this._captureDir.id;
        obj.groupPath = this._captureDir.path;
        obj.dataBytesStore = base64Image;

        return await page.createObject(obj);
    }

    /**
     * Move a generated image to ~/Magic8/Generated group
     * @param {Object} imageObj - data.data object returned from reimage API
     * @returns {Promise<Object>} Updated object with new groupPath
     * @private
     */
    async _moveToGeneratedGroup(imageObj) {
        if (!this._generatedDir) {
            this._generatedDir = await page.findObject("auth.group", "DATA", "~/Magic8/Generated");
            if (!this._generatedDir || !this._generatedDir.objectId) {
                this._generatedDir = await page.makePath("auth.group", "data", "~/Magic8/Generated");
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
                this._baseEntity = {};
            }
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

        // Set reference image for img2img
        entity.referenceImageId = referenceImageId;

        return entity;
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
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = ImageGenerationManager;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.ImageGenerationManager = ImageGenerationManager;
}
