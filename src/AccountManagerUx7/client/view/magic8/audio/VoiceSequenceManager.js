/**
 * VoiceSequenceManager - Text-to-speech sequence playback
 * Loads text from notes/data objects, synthesizes via AudioEngine,
 * plays clips sequentially with random pauses between them.
 */
class VoiceSequenceManager {
    constructor(audioEngine) {
        this.audioEngine = audioEngine;
        this.sequences = [];
        this.currentIndex = 0;
        this.isPlaying = false;
        this.isPaused = false;
        this.loop = true;
        this.voiceProfileId = null;

        this.pauseDurationMin = 500;    // 0.5s minimum pause
        this.pauseDurationMax = 10000;  // 10s maximum pause

        // Map of sequence index → AudioEngine source hash
        this.synthesizedHashes = new Map();
        this.pauseTimeoutId = null;

        // Callbacks
        this.onLineChange = null;
        this.onSynthesisProgress = null;
        this.onComplete = null;
        this.onLoad = null;
    }

    /**
     * Load text sequences from a data.note object
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
            console.error('VoiceSequenceManager: Failed to load from note:', err);
            return 0;
        }
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
            console.error('VoiceSequenceManager: Failed to load from data object:', err);
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
     * Parse content string into sequence array
     * @param {string} content - Raw content
     * @returns {number} Number of lines
     * @private
     */
    _parseContent(content) {
        this.sequences = content
            .split(/\r?\n/)
            .map(line => line.trim())
            .filter(line => line.length > 0 && !line.startsWith('#'));

        if (this.onLoad) {
            this.onLoad(this.sequences.length);
        }

        return this.sequences.length;
    }

    /**
     * Pre-synthesize all lines via AudioEngine
     * Each line is sent to the TTS endpoint and the decoded AudioBuffer is cached.
     * @param {string} voiceProfileId - Voice profile object ID
     * @returns {Promise<number>} Number of successfully synthesized lines
     */
    async synthesizeAll(voiceProfileId) {
        this.voiceProfileId = voiceProfileId;
        let successCount = 0;

        for (let i = 0; i < this.sequences.length; i++) {
            const line = this.sequences[i];

            try {
                const source = await this.audioEngine.createVoiceSource(line, voiceProfileId);
                if (source) {
                    this.synthesizedHashes.set(i, source.id);
                    successCount++;
                }
            } catch (err) {
                console.warn('VoiceSequenceManager: Failed to synthesize line', i, ':', err);
            }

            if (this.onSynthesisProgress) {
                this.onSynthesisProgress(i + 1, this.sequences.length);
            }
        }

        console.log(`VoiceSequenceManager: Synthesized ${successCount}/${this.sequences.length} lines`);
        return successCount;
    }

    /**
     * Start playing voice sequences from the beginning
     */
    start() {
        if (this.sequences.length === 0) {
            console.warn('VoiceSequenceManager: No sequences loaded');
            return;
        }

        this.isPlaying = true;
        this.isPaused = false;
        this.currentIndex = 0;
        this._playNext();
    }

    /**
     * Stop playback completely
     */
    stop() {
        this.isPlaying = false;
        this.isPaused = false;

        if (this.pauseTimeoutId) {
            clearTimeout(this.pauseTimeoutId);
            this.pauseTimeoutId = null;
        }

        // Stop any currently playing clip
        this._stopCurrentClip();
    }

    /**
     * Pause playback (can be resumed)
     */
    pause() {
        if (!this.isPlaying) return;
        this.isPaused = true;

        if (this.pauseTimeoutId) {
            clearTimeout(this.pauseTimeoutId);
            this.pauseTimeoutId = null;
        }

        this._stopCurrentClip();
    }

    /**
     * Resume paused playback
     */
    resume() {
        if (!this.isPlaying || !this.isPaused) return;
        this.isPaused = false;
        this._playNext();
    }

    /**
     * Stop the currently playing audio clip
     * @private
     */
    _stopCurrentClip() {
        // currentIndex has already been advanced, so the playing clip is at currentIndex - 1
        const playingIdx = this.currentIndex - 1;
        if (playingIdx >= 0) {
            const hash = this.synthesizedHashes.get(playingIdx);
            if (hash) {
                const source = this.audioEngine.sources.get(hash);
                if (source && source.started) {
                    source.stop();
                }
            }
        }
    }

    /**
     * Play the next line in sequence
     * @private
     */
    _playNext() {
        if (!this.isPlaying || this.isPaused) return;

        // Check for end of sequence
        if (this.currentIndex >= this.sequences.length) {
            if (this.loop) {
                this.currentIndex = 0;
            } else {
                this.isPlaying = false;
                if (this.onComplete) {
                    this.onComplete();
                }
                return;
            }
        }

        const text = this.sequences[this.currentIndex];
        const hash = this.synthesizedHashes.get(this.currentIndex);

        // Notify listener with current line text (for overlay display)
        if (this.onLineChange) {
            this.onLineChange(text, this.currentIndex, this.sequences.length);
        }

        this.currentIndex++;

        // If no synthesized audio for this line, skip to next after a pause
        if (!hash) {
            this._scheduleNext();
            return;
        }

        const source = this.audioEngine.sources.get(hash);
        if (!source || !source.buffer) {
            this._scheduleNext();
            return;
        }

        // Play the audio clip
        source.play();

        // Listen for playback completion, then schedule next with random pause
        const bufferNode = source.source;
        if (bufferNode) {
            bufferNode.addEventListener('ended', () => {
                this._scheduleNext();
            }, { once: true });
        } else {
            // Fallback: estimate duration from buffer length
            const durationMs = (source.buffer.duration || 3) * 1000;
            setTimeout(() => this._scheduleNext(), durationMs);
        }
    }

    /**
     * Schedule the next clip with a random pause
     * @private
     */
    _scheduleNext() {
        if (!this.isPlaying || this.isPaused) return;

        const pauseMs = this.pauseDurationMin +
            Math.random() * (this.pauseDurationMax - this.pauseDurationMin);

        this.pauseTimeoutId = setTimeout(() => {
            this.pauseTimeoutId = null;
            this._playNext();
        }, pauseMs);
    }

    /**
     * Inject a new voice line at the given position, synthesize it, and
     * shift existing entries so playback picks it up naturally.
     * @param {string} text - Line text to synthesize
     * @param {number} atIndex - Position to insert at (typically currentIndex)
     * @param {string} [voiceProfileId] - Override voice profile (defaults to stored)
     * @returns {Promise<boolean>} true if successfully injected and synthesized
     */
    async injectLine(text, atIndex, voiceProfileId) {
        if (!text || atIndex < 0) return false;
        const idx = Math.min(atIndex, this.sequences.length);

        // Insert text into sequences
        this.sequences.splice(idx, 0, text);

        // Shift synthesized hashes for all entries at or after the insertion point
        const newHashes = new Map();
        for (const [k, v] of this.synthesizedHashes) {
            if (k >= idx) {
                newHashes.set(k + 1, v);
            } else {
                newHashes.set(k, v);
            }
        }
        this.synthesizedHashes = newHashes;

        // If currentIndex is at or past the insertion point, bump it so
        // the currently playing clip isn't disrupted
        if (this.currentIndex > idx) {
            this.currentIndex++;
        }

        // Synthesize the new line
        try {
            const profileId = voiceProfileId || this.voiceProfileId;
            const source = await this.audioEngine.createVoiceSource(text, profileId);
            if (source) {
                this.synthesizedHashes.set(idx, source.id);
                return true;
            }
        } catch (err) {
            console.warn('VoiceSequenceManager: Failed to synthesize injected line:', err);
        }
        return false;
    },

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
     * Get all loaded sequences
     * @returns {Array<string>}
     */
    getAll() {
        return [...this.sequences];
    }

    /**
     * Set pause duration range
     * @param {number} minMs - Minimum pause in milliseconds
     * @param {number} maxMs - Maximum pause in milliseconds
     */
    setPauseDuration(minMs, maxMs) {
        this.pauseDurationMin = Math.max(100, minMs);
        this.pauseDurationMax = Math.max(this.pauseDurationMin, maxMs);
    }

    /**
     * Clean up all resources
     */
    dispose() {
        this.stop();
        this.sequences = [];
        this.synthesizedHashes.clear();
        this.onLineChange = null;
        this.onSynthesisProgress = null;
        this.onComplete = null;
        this.onLoad = null;
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = VoiceSequenceManager;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.VoiceSequenceManager = VoiceSequenceManager;
}
