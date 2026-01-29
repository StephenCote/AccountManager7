/**
 * ImageGenerationManager - Stable Diffusion img2img generation from camera captures
 * Captures user images and transforms them using AI image generation
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
            prompt: "ethereal dreamlike portrait, soft lighting, mystical atmosphere",
            negative_prompt: "blurry, distorted, ugly, deformed, nsfw",
            strength: 0.65,
            steps: 30,
            cfg_scale: 7.5,
            width: 512,
            height: 512,
            sampler: "euler_a",
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

            // Build prompt with emotion awareness
            let prompt = this.sdConfig.prompt;
            if (biometricData && biometricData.dominant_emotion) {
                const emotionPrompt = this.sdConfig.emotionPromptMapping?.[biometricData.dominant_emotion];
                if (emotionPrompt) {
                    prompt = `${emotionPrompt}, ${prompt}`;
                }
            }

            // Build generation request
            const genRequest = {
                prompt: prompt,
                negative_prompt: this.sdConfig.negative_prompt || "",
                init_image: base64Image,
                strength: this.sdConfig.strength || 0.65,
                steps: this.sdConfig.steps || 30,
                cfg_scale: this.sdConfig.cfg_scale || 7.5,
                width: this.sdConfig.width || 512,
                height: this.sdConfig.height || 512,
                sampler: this.sdConfig.sampler || "euler_a",
                seed: this.sdConfig.seed || -1
            };

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
     * Process the generation queue
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
            const response = await m.request({
                method: 'POST',
                url: `${g_application_path}/rest/sd/img2img`,
                withCredentials: true,
                body: pendingJob.request
            });

            if (response.images && response.images.length > 0) {
                // Save generated image to server
                const savedImage = await this._saveGeneratedImage(response.images[0], pendingJob.id);

                pendingJob.status = 'complete';
                pendingJob.result = savedImage;

                // Add to generated images list
                this.generatedImages.push({
                    id: pendingJob.id,
                    url: savedImage.url,
                    objectId: savedImage.objectId,
                    timestamp: Date.now()
                });

                // Notify listeners
                if (this.onImageGenerated) {
                    this.onImageGenerated(savedImage);
                }
            } else {
                throw new Error('No images returned from generation');
            }

        } catch (err) {
            console.error('ImageGenerationManager: Generation failed:', err);
            pendingJob.status = 'failed';
            pendingJob.error = err.message;

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
     * Save generated image to server
     * @param {string} base64Image - Base64 encoded image
     * @param {string} jobId - Job ID for naming
     * @returns {Promise<Object>} Saved image info
     * @private
     */
    async _saveGeneratedImage(base64Image, jobId) {
        try {
            const response = await m.request({
                method: 'POST',
                url: `${g_application_path}/rest/media/upload/base64`,
                withCredentials: true,
                body: {
                    data: base64Image,
                    name: `generated-${jobId}.png`,
                    contentType: 'image/png',
                    groupPath: '~/Magic8/Generated'
                }
            });

            return {
                objectId: response.objectId,
                url: response.url || `${g_application_path}/media/Public/data.data${response.groupPath}/${response.name}`,
                name: response.name
            };

        } catch (err) {
            console.error('ImageGenerationManager: Failed to save image:', err);
            // Return a data URL as fallback
            return {
                objectId: null,
                url: `data:image/png;base64,${base64Image}`,
                name: `generated-${jobId}.png`
            };
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
