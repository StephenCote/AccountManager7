/**
 * DynamicImageGallery - Image gallery with crossfade and generated image splicing
 * Manages base images from media groups and dynamically splices in AI-generated images
 */
class DynamicImageGallery {
    constructor(config = {}) {
        this.baseImages = [];
        this.generatedImages = [];
        this.allImages = [];
        this.currentIndex = 0;
        this.cycleInterval = config.cycleInterval || 5000;
        this.crossfadeDuration = config.crossfadeDuration || 1000;
        this.includeGenerated = config.includeGenerated !== false;
        this.maxGeneratedImages = config.maxGeneratedImages || 20;
        this.generatedRatio = config.generatedRatio || 0.3; // 30% generated images

        this.intervalId = null;
        this.onImageChange = null;

        // Crossfade state
        this.currentImageUrl = null;
        this.nextImageUrl = null;
        this.isTransitioning = false;
    }

    /**
     * Load base images from media group IDs
     * @param {Array<number>} groupIds - Array of group IDs to load from
     * @returns {Promise<number>} Number of images loaded
     */
    async loadBaseImages(groupIds) {
        if (!groupIds || groupIds.length === 0) {
            console.warn('DynamicImageGallery: No group IDs provided');
            return 0;
        }

        console.log('DynamicImageGallery: Loading images for group IDs:', groupIds);

        try {
            // Load images from each group using search (avoids loading dataBytesStore)
            let allResults = [];
            for (const gid of groupIds) {
                try {
                    console.log('DynamicImageGallery: Searching data.data in group', gid);
                    // Resolve group objectId to numeric id
                    let gq = am7view.viewQuery(am7model.newInstance("auth.group"));
                    gq.field("objectId", gid);
                    let gqr = await page.search(gq);
                    if (!gqr?.results?.length) {
                        console.warn('DynamicImageGallery: Group not found:', gid);
                        continue;
                    }
                    const groupNumericId = gqr.results[0].id;

                    // Search data.data by numeric groupId (won't load dataBytesStore)
                    //let q = am7view.viewQuery(am7model.newInstance("data.data"));
                    let q = am7client.newQuery("data.data");
                    q.range(0, 0);
                    q.field("groupId", groupNumericId);
                    let qr = await page.search(q);
                    const items = qr?.results || [];
                    console.log('DynamicImageGallery: Group', gid, '(id:', groupNumericId, ') returned', items.length, 'items');
                    if (items.length) {
                        allResults = allResults.concat(items);
                    }
                } catch (groupErr) {
                    console.warn('DynamicImageGallery: Failed to load group', gid, ':', groupErr);
                }
            }

            console.log('DynamicImageGallery: Total items loaded:', allResults.length);

            if (allResults.length > 0) {
                this.baseImages = allResults
                    .filter(r => r.name && r.contentType && r.contentType.match(/^image\//i))
                    .map(r => ({
                        url: `${g_application_path}/media/Public/data.data${encodeURI(r.groupPath)}/${encodeURIComponent(r.name)}`,
                        objectId: r.objectId,
                        type: 'base',
                        name: r.name
                    }));

                console.log('DynamicImageGallery: Image entries after filtering:', this.baseImages.length);
                if (this.baseImages.length > 0) {
                    console.log('DynamicImageGallery: Sample URL:', this.baseImages[0].url);
                }

                this._rebuildPool();

                // Set initial images
                if (this.allImages.length > 0) {
                    this.currentImageUrl = this.allImages[0].url;
                    if (this.allImages.length > 1) {
                        this.nextImageUrl = this.allImages[1].url;
                    }
                }
            }

            return this.baseImages.length;
        } catch (err) {
            console.error('DynamicImageGallery: Error loading images:', err);
            return 0;
        }
    }

    /**
     * Add a generated image to the pool
     * @param {Object} imageData - Generated image data { url, objectId }
     */
    spliceGeneratedImage(imageData) {
        if (!imageData || !imageData.url) {
            console.warn('DynamicImageGallery: Invalid image data');
            return;
        }

        this.generatedImages.push({
            url: imageData.url,
            objectId: imageData.objectId,
            type: 'generated',
            timestamp: Date.now()
        });

        // Trim old generated images if over limit
        if (this.generatedImages.length > this.maxGeneratedImages) {
            this.generatedImages = this.generatedImages.slice(-this.maxGeneratedImages);
        }

        this._rebuildPool();
    }

    /**
     * Rebuild the combined image pool with interleaved generated images
     * @private
     */
    _rebuildPool() {
        if (!this.includeGenerated || this.generatedImages.length === 0) {
            this.allImages = [...this.baseImages];
        } else {
            // Interleave generated images with base images
            this.allImages = [];
            let baseIdx = 0;
            let genIdx = 0;

            while (baseIdx < this.baseImages.length || genIdx < this.generatedImages.length) {
                // Add base images
                if (baseIdx < this.baseImages.length) {
                    this.allImages.push(this.baseImages[baseIdx++]);
                }

                // Occasionally insert generated image
                if (genIdx < this.generatedImages.length && Math.random() < this.generatedRatio) {
                    this.allImages.push(this.generatedImages[genIdx++]);
                }
            }

            // Add any remaining generated images
            while (genIdx < this.generatedImages.length) {
                this.allImages.push(this.generatedImages[genIdx++]);
            }
        }

        // Shuffle for variety
        this._shuffle(this.allImages);
    }

    /**
     * Fisher-Yates shuffle
     * @param {Array} array - Array to shuffle in place
     * @private
     */
    _shuffle(array) {
        for (let i = array.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [array[i], array[j]] = [array[j], array[i]];
        }
    }

    /**
     * Get the next image in rotation
     * @returns {Object|null} Next image object
     */
    getNext() {
        if (this.allImages.length === 0) return null;

        this.currentIndex = (this.currentIndex + 1) % this.allImages.length;
        return this.allImages[this.currentIndex];
    }

    /**
     * Get the current image
     * @returns {Object|null} Current image object
     */
    getCurrent() {
        return this.allImages[this.currentIndex] || null;
    }

    /**
     * Get current image URL
     * @returns {string|null}
     */
    getCurrentUrl() {
        const current = this.getCurrent();
        return current ? current.url : null;
    }

    /**
     * Start automatic image cycling
     */
    start() {
        if (this.intervalId) return;

        this.intervalId = setInterval(() => {
            this._cycle();
        }, this.cycleInterval);
    }

    /**
     * Stop automatic image cycling
     */
    stop() {
        if (this.intervalId) {
            clearInterval(this.intervalId);
            this.intervalId = null;
        }
    }

    /**
     * Perform image cycle with crossfade
     * @private
     */
    _cycle() {
        if (this.allImages.length === 0 || this.isTransitioning) return;

        this.isTransitioning = true;
        const next = this.getNext();

        if (next && this.onImageChange) {
            this.onImageChange(next, this.currentIndex, this.allImages.length);
        }

        // Reset transition flag after crossfade duration
        setTimeout(() => {
            this.isTransitioning = false;
        }, this.crossfadeDuration);
    }

    /**
     * Show a specific image immediately (bypasses cycle rotation)
     * Also adds it to the generated pool if not already present.
     * @param {Object} imageData - Image data { url, objectId }
     */
    showImmediate(imageData) {
        if (!imageData || !imageData.url) return;

        // Add to generated pool if not already there
        const exists = this.generatedImages.some(g => g.objectId === imageData.objectId);
        if (!exists) {
            this.spliceGeneratedImage(imageData);
        }

        // Reset cycle timer so this image gets a full display period
        const wasRunning = !!this.intervalId;
        if (wasRunning) {
            this.stop();
        }

        // Trigger onImageChange directly to display this image now
        if (this.onImageChange) {
            this.onImageChange({
                url: imageData.url,
                objectId: imageData.objectId,
                type: 'generated'
            }, this.currentIndex, this.allImages.length);
        }

        // Restart cycling so the image stays for a full interval
        if (wasRunning) {
            this.start();
        }
    }

    /**
     * Manually trigger next image
     */
    next() {
        this._cycle();
    }

    /**
     * Manually trigger previous image
     */
    previous() {
        if (this.allImages.length === 0) return;

        this.currentIndex = (this.currentIndex - 1 + this.allImages.length) % this.allImages.length;
        const current = this.getCurrent();

        if (current && this.onImageChange) {
            this.onImageChange(current, this.currentIndex, this.allImages.length);
        }
    }

    /**
     * Set cycle interval
     * @param {number} interval - Interval in milliseconds
     */
    setCycleInterval(interval) {
        this.cycleInterval = interval;
        if (this.intervalId) {
            this.stop();
            this.start();
        }
    }

    /**
     * Get statistics about the gallery
     * @returns {Object}
     */
    getStats() {
        return {
            baseCount: this.baseImages.length,
            generatedCount: this.generatedImages.length,
            totalCount: this.allImages.length,
            currentIndex: this.currentIndex,
            isRunning: !!this.intervalId
        };
    }

    /**
     * Clear all generated images
     */
    clearGenerated() {
        this.generatedImages = [];
        this._rebuildPool();
    }

    /**
     * Clean up resources
     */
    dispose() {
        this.stop();
        this.baseImages = [];
        this.generatedImages = [];
        this.allImages = [];
        this.onImageChange = null;
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = DynamicImageGallery;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.DynamicImageGallery = DynamicImageGallery;
}
