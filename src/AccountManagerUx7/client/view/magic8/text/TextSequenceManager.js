/**
 * TextSequenceManager - Manages hypnotic text sequences loaded from notes/data objects
 * Cycles through text lines with configurable timing and fade effects
 */
class TextSequenceManager {
    constructor() {
        this.sequences = [];
        this.currentIndex = 0;
        this.isPlaying = false;
        this.loop = true;
        this.displayDuration = 5000; // ms per text
        this.fadeDuration = 1000;    // ms for fade transition
        this.timeoutId = null;
        this.shuffled = false;

        this.onTextChange = null;
        this.onComplete = null;
        this.onLoad = null;
    }

    /**
     * Load text sequences from a data.data object
     * @param {string} objectId - Object ID of the data object
     * @returns {Promise<number>} Number of lines loaded
     */
    async loadFromDataObject(objectId) {
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.entity.request.push("description");
            q.field("objectId", objectId);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                let obj = qr.results[0];
                let content = '';
                if (obj.dataBytesStore && obj.dataBytesStore.length) {
                    content = uwm.base64Decode(obj.dataBytesStore);
                } else if (obj.description) {
                    content = obj.description;
                }
                return this._parseContent(content);
            }
            return 0;
        } catch (err) {
            console.error('TextSequenceManager: Failed to load from data object:', err);
            return 0;
        }
    }

    /**
     * Load text sequences from a note object
     * @param {string} noteObjectId - Object ID of the note
     * @returns {Promise<number>} Number of lines loaded
     */
    async loadFromNote(noteObjectId) {
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.note"));
            q.entity.request.push("text");
            q.field("objectId", noteObjectId);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                let obj = qr.results[0];
                const content = obj.text || '';
                return this._parseContent(content);
            }
            return 0;
        } catch (err) {
            console.error('TextSequenceManager: Failed to load from note:', err);
            return 0;
        }
    }

    /**
     * Load text from a raw string
     * @param {string} content - Multi-line text content
     * @returns {number} Number of lines loaded
     */
    loadFromString(content) {
        return this._parseContent(content);
    }

    /**
     * Load from an array of strings
     * @param {Array<string>} lines - Array of text lines
     * @returns {number} Number of lines loaded
     */
    loadFromArray(lines) {
        this.sequences = lines
            .map(line => line.trim())
            .filter(line => line.length > 0);

        if (this.onLoad) {
            this.onLoad(this.sequences.length);
        }

        return this.sequences.length;
    }

    /**
     * Parse content string into sequence array
     * @param {string} content - Raw content
     * @returns {number} Number of lines
     * @private
     */
    _parseContent(content) {
        this.sequences = content
            .split(/\r?\n/)
            .map(line => line.trim())
            .filter(line => line.length > 0 && !line.startsWith('#')); // Allow comments

        if (this.onLoad) {
            this.onLoad(this.sequences.length);
        }

        return this.sequences.length;
    }

    /**
     * Start playing the text sequence
     */
    start() {
        if (this.sequences.length === 0) {
            console.warn('TextSequenceManager: No sequences loaded');
            return;
        }

        this.isPlaying = true;
        this.currentIndex = 0;

        if (this.shuffled) {
            this._shuffle();
        }

        this._displayNext();
    }

    /**
     * Stop playing
     */
    stop() {
        this.isPlaying = false;
        if (this.timeoutId) {
            clearTimeout(this.timeoutId);
            this.timeoutId = null;
        }
    }

    /**
     * Pause playback
     */
    pause() {
        if (this.timeoutId) {
            clearTimeout(this.timeoutId);
            this.timeoutId = null;
        }
    }

    /**
     * Resume playback
     */
    resume() {
        if (this.isPlaying && !this.timeoutId) {
            this._displayNext();
        }
    }

    /**
     * Display the next text in sequence
     * @private
     */
    _displayNext() {
        if (!this.isPlaying) return;

        if (this.currentIndex >= this.sequences.length) {
            if (this.loop && this.sequences.length > 0) {
                this.currentIndex = 0;
                if (this.shuffled) {
                    this._shuffle();
                }
            } else {
                this.isPlaying = false;
                if (this.onComplete) {
                    this.onComplete();
                }
                return;
            }
        }

        const text = this.sequences[this.currentIndex];

        if (this.onTextChange) {
            this.onTextChange(text, this.currentIndex, this.sequences.length);
        }

        this.currentIndex++;

        this.timeoutId = setTimeout(() => this._displayNext(), this.displayDuration);
    }

    /**
     * Skip to next text immediately
     */
    next() {
        if (this.timeoutId) {
            clearTimeout(this.timeoutId);
        }
        this._displayNext();
    }

    /**
     * Go back to previous text
     */
    previous() {
        if (this.timeoutId) {
            clearTimeout(this.timeoutId);
        }

        this.currentIndex = Math.max(0, this.currentIndex - 2);
        this._displayNext();
    }

    /**
     * Jump to a specific index
     * @param {number} index - Index to jump to
     */
    jumpTo(index) {
        if (index >= 0 && index < this.sequences.length) {
            if (this.timeoutId) {
                clearTimeout(this.timeoutId);
            }
            this.currentIndex = index;
            this._displayNext();
        }
    }

    /**
     * Get current text
     * @returns {string}
     */
    getCurrent() {
        const idx = Math.max(0, this.currentIndex - 1);
        return this.sequences[idx] || '';
    }

    /**
     * Get all sequences
     * @returns {Array<string>}
     */
    getAll() {
        return [...this.sequences];
    }

    /**
     * Get sequence count
     * @returns {number}
     */
    getCount() {
        return this.sequences.length;
    }

    /**
     * Get current progress
     * @returns {Object} { current, total, percentage }
     */
    getProgress() {
        return {
            current: this.currentIndex,
            total: this.sequences.length,
            percentage: this.sequences.length > 0
                ? Math.round((this.currentIndex / this.sequences.length) * 100)
                : 0
        };
    }

    /**
     * Shuffle sequences
     * @private
     */
    _shuffle() {
        for (let i = this.sequences.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [this.sequences[i], this.sequences[j]] = [this.sequences[j], this.sequences[i]];
        }
    }

    /**
     * Enable/disable shuffling
     * @param {boolean} enabled - Whether to shuffle
     */
    setShuffle(enabled) {
        this.shuffled = enabled;
    }

    /**
     * Set display duration per text
     * @param {number} ms - Duration in milliseconds
     */
    setDisplayDuration(ms) {
        this.displayDuration = Math.max(500, ms); // Minimum 500ms
    }

    /**
     * Set fade duration
     * @param {number} ms - Fade duration in milliseconds
     */
    setFadeDuration(ms) {
        this.fadeDuration = Math.max(100, ms);
    }

    /**
     * Set loop mode
     * @param {boolean} enabled - Whether to loop
     */
    setLoop(enabled) {
        this.loop = enabled;
    }

    /**
     * Add a text line dynamically
     * @param {string} text - Text to add
     */
    addText(text) {
        if (text && text.trim()) {
            this.sequences.push(text.trim());
        }
    }

    /**
     * Remove a text line by index
     * @param {number} index - Index to remove
     */
    removeText(index) {
        if (index >= 0 && index < this.sequences.length) {
            this.sequences.splice(index, 1);
            if (this.currentIndex > index) {
                this.currentIndex--;
            }
        }
    }

    /**
     * Clear all sequences
     */
    clear() {
        this.stop();
        this.sequences = [];
        this.currentIndex = 0;
    }

    /**
     * Clean up resources
     */
    dispose() {
        this.stop();
        this.clear();
        this.onTextChange = null;
        this.onComplete = null;
        this.onLoad = null;
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = TextSequenceManager;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.TextSequenceManager = TextSequenceManager;
}
