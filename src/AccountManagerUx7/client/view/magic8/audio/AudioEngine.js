/**
 * AudioEngine - Unified audio context management for Magic8
 * Handles voice synthesis, binaural beats, and audio source management
 */
class AudioEngine {
    constructor() {
        this.context = null;
        this.sources = new Map();
        this.binauralEngine = null;
        this.masterGain = null;
        this.destinationNode = null;
        this.isInitialized = false;
    }

    /**
     * Get or create the AudioContext (singleton pattern)
     * @returns {AudioContext}
     */
    getContext() {
        if (!this.context || this.context.state === 'closed') {
            this.context = new (window.AudioContext || window.webkitAudioContext)();
            this._setupMasterChain();
        }
        return this.context;
    }

    /**
     * Setup the master audio chain for recording support
     * @private
     */
    _setupMasterChain() {
        if (this.masterGain) return;

        this.masterGain = this.context.createGain();
        this.masterGain.gain.value = 1.0;

        // Create a destination for recording (MediaStreamDestination)
        if (this.context.createMediaStreamDestination) {
            this.destinationNode = this.context.createMediaStreamDestination();
            this.masterGain.connect(this.destinationNode);
        }

        // Also connect to speakers
        this.masterGain.connect(this.context.destination);
        this.isInitialized = true;
    }

    /**
     * Get the audio destination for recording
     * @returns {MediaStreamAudioDestinationNode|null}
     */
    getDestination() {
        if (!this.isInitialized) {
            this.getContext();
        }
        return this.destinationNode;
    }

    /**
     * Resume audio context (needed after user interaction)
     * @returns {Promise<void>}
     */
    async resume() {
        const ctx = this.getContext();
        if (ctx.state === 'suspended') {
            await ctx.resume();
        }
    }

    /**
     * Create a hash for content caching
     * @param {string} content - Content to hash
     * @returns {string}
     */
    hashContent(content) {
        let hash = 0;
        for (let i = 0; i < content.length; i++) {
            const char = content.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash;
        }
        return 'audio_' + hash.toString(16);
    }

    /**
     * Create a voice audio source from text content
     * @param {string} content - Text content to synthesize
     * @param {string} profileId - Voice profile ID
     * @returns {Promise<Object>} Audio source object
     */
    async createVoiceSource(content, profileId) {
        const hash = this.hashContent(content + profileId);

        // Return cached source if exists
        if (this.sources.has(hash)) {
            return this.sources.get(hash);
        }

        // Clean content for synthesis
        let cleanContent = content
            .replace(/([\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF])/g, "")
            .replace(/["\*]+/g, "");

        if (!cleanContent || cleanContent.length === 0) {
            console.warn('AudioEngine: No content to synthesize');
            return null;
        }

        try {
            // Build voice request
            const vprops = {
                text: cleanContent,
                speed: 1.2
            };

            if (profileId) {
                vprops.voiceProfileId = profileId;
            } else {
                vprops.engine = "piper";
                vprops.speaker = "en_GB-alba-medium";
            }

            // Synthesize voice
            const response = await m.request({
                method: 'POST',
                url: `${g_application_path}/rest/voice/${hash}`,
                withCredentials: true,
                body: vprops
            });

            if (!response || !response.dataBytesStore) {
                throw new Error('No audio data received');
            }

            // Decode audio
            const ctx = this.getContext();
            const arrayBuffer = this._base64ToArrayBuffer(response.dataBytesStore);
            const audioBuffer = await ctx.decodeAudioData(arrayBuffer);

            // Create source object
            const source = {
                id: hash,
                buffer: audioBuffer,
                context: ctx,
                started: false,
                source: null,

                play: () => this._playSource(hash),
                stop: () => this._stopSource(hash),
                isPlaying: () => this._isSourcePlaying(hash)
            };

            this.sources.set(hash, source);
            return source;

        } catch (err) {
            console.error('AudioEngine: Error creating voice source:', err);
            return null;
        }
    }

    /**
     * Play an audio source
     * @param {string} hash - Source hash
     * @private
     */
    _playSource(hash) {
        const source = this.sources.get(hash);
        if (!source) return;

        // Stop any currently playing sources
        this.stopAllSources();

        const ctx = this.getContext();

        // Create new buffer source node (required for each playback)
        source.source = ctx.createBufferSource();
        source.source.buffer = source.buffer;
        source.source.connect(this.masterGain);

        source.source.onended = () => {
            source.started = false;
        };

        if (ctx.state === 'suspended') {
            ctx.resume().then(() => {
                source.source.start(0);
                source.started = true;
            });
        } else {
            source.source.start(0);
            source.started = true;
        }
    }

    /**
     * Stop an audio source
     * @param {string} hash - Source hash
     * @private
     */
    _stopSource(hash) {
        const source = this.sources.get(hash);
        if (!source || !source.started) return;

        try {
            source.source.stop();
        } catch (e) {
            // Source may already be stopped
        }
        source.started = false;
    }

    /**
     * Check if a source is playing
     * @param {string} hash - Source hash
     * @returns {boolean}
     * @private
     */
    _isSourcePlaying(hash) {
        const source = this.sources.get(hash);
        return source ? source.started : false;
    }

    /**
     * Stop all playing audio sources
     */
    stopAllSources() {
        this.sources.forEach((source, hash) => {
            if (source.started) {
                this._stopSource(hash);
            }
        });
    }

    /**
     * Start binaural beats
     * @param {Object} config - Binaural configuration
     */
    startBinaural(config = {}) {
        const ctx = this.getContext();

        const baseFreq = config.baseFreq || 440;
        const minBeat = config.minBeat || 2;
        const maxBeat = config.maxBeat || 8;
        const sweepDurationMin = config.sweepDurationMin || 5;

        // Create oscillators
        this.binauralEngine = {
            leftOsc: ctx.createOscillator(),
            rightOsc: ctx.createOscillator(),
            leftGain: ctx.createGain(),
            rightGain: ctx.createGain(),
            binauralGain: ctx.createGain(),
            merger: ctx.createChannelMerger(2),
            dcBlocker: ctx.createBiquadFilter(),
            baseFreq,
            minBeat,
            maxBeat,
            sweepDurationMin,
            sweepInterval: null
        };

        const be = this.binauralEngine;

        // Configure DC blocker
        be.dcBlocker.type = "highpass";
        be.dcBlocker.frequency.value = 20;

        // Set initial gain to 0 for smooth ramp-up
        be.binauralGain.gain.setValueAtTime(0, ctx.currentTime);

        // Connect audio graph
        be.leftOsc.connect(be.leftGain).connect(be.merger, 0, 0);
        be.rightOsc.connect(be.rightGain).connect(be.merger, 0, 1);
        be.merger.connect(be.binauralGain);
        be.binauralGain.connect(be.dcBlocker);
        be.dcBlocker.connect(this.masterGain);

        // Set frequencies
        be.leftOsc.frequency.setValueAtTime(baseFreq, ctx.currentTime);
        be.rightOsc.frequency.setValueAtTime(baseFreq + maxBeat, ctx.currentTime);

        // Start oscillators
        be.leftOsc.start();
        be.rightOsc.start();

        // Ramp up volume smoothly
        const rampTime = 0.5;
        const targetVolume = 0.35;
        const delay = 0.05;
        const startTime = ctx.currentTime + delay;

        be.binauralGain.gain.cancelScheduledValues(ctx.currentTime);
        be.binauralGain.gain.setValueAtTime(0, ctx.currentTime);
        be.binauralGain.gain.linearRampToValueAtTime(targetVolume, startTime + rampTime);

        // Start frequency sweep
        this._scheduleBinauralSweep();
        be.sweepInterval = setInterval(() => this._scheduleBinauralSweep(), sweepDurationMin * 60 * 1000);
    }

    /**
     * Schedule binaural frequency sweep
     * @private
     */
    _scheduleBinauralSweep() {
        if (!this.binauralEngine) return;

        const be = this.binauralEngine;
        const ctx = this.context;
        const now = ctx.currentTime;
        const halfCycle = (be.sweepDurationMin * 60) / 2;

        be.rightOsc.frequency.cancelScheduledValues(now);
        be.rightOsc.frequency.setValueAtTime(be.baseFreq + be.maxBeat, now);
        be.rightOsc.frequency.linearRampToValueAtTime(be.baseFreq + be.minBeat, now + halfCycle);
        be.rightOsc.frequency.linearRampToValueAtTime(be.baseFreq + be.maxBeat, now + 2 * halfCycle);
    }

    /**
     * Stop binaural beats
     */
    stopBinaural() {
        if (!this.binauralEngine) return;

        const be = this.binauralEngine;
        const ctx = this.context;

        if (be.sweepInterval) {
            clearInterval(be.sweepInterval);
        }

        if (!ctx) {
            // Context already closed or never created - just clean up references
            try { be.leftOsc.stop(); } catch (e) {}
            try { be.rightOsc.stop(); } catch (e) {}
            try { be.leftOsc.disconnect(); } catch (e) {}
            try { be.rightOsc.disconnect(); } catch (e) {}
            try { be.binauralGain.disconnect(); } catch (e) {}
            this.binauralEngine = null;
            return;
        }

        // Ramp down volume
        const now = ctx.currentTime;
        be.binauralGain.gain.cancelScheduledValues(now);
        be.binauralGain.gain.setValueAtTime(be.binauralGain.gain.value, now);
        be.binauralGain.gain.linearRampToValueAtTime(0, now + 0.3);

        setTimeout(() => {
            try {
                be.leftOsc.stop();
                be.rightOsc.stop();
            } catch (e) {}

            try {
                be.leftOsc.disconnect();
                be.rightOsc.disconnect();
                be.binauralGain.disconnect();
            } catch (e) {}

            this.binauralEngine = null;
        }, 320);
    }

    /**
     * Convert base64 string to ArrayBuffer
     * @param {string} base64 - Base64 encoded string
     * @returns {ArrayBuffer}
     * @private
     */
    _base64ToArrayBuffer(base64) {
        const cleanedBase64 = base64.replace(/^data:audio\/\w+;base64,/, '');
        const binaryString = atob(cleanedBase64);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);

        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }

        return bytes.buffer;
    }

    /**
     * Set master volume
     * @param {number} volume - Volume level (0-1)
     */
    setVolume(volume) {
        if (this.masterGain) {
            this.masterGain.gain.value = Math.max(0, Math.min(1, volume));
        }
    }

    /**
     * Clean up and dispose all resources
     */
    dispose() {
        this.stopBinaural();
        this.stopAllSources();

        if (this.masterGain) {
            this.masterGain.disconnect();
        }

        if (this.destinationNode) {
            this.destinationNode.disconnect();
        }

        if (this.context && this.context.state !== 'closed') {
            this.context.close();
        }

        this.sources.clear();
        this.context = null;
        this.masterGain = null;
        this.destinationNode = null;
        this.isInitialized = false;
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = AudioEngine;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.AudioEngine = AudioEngine;
}
