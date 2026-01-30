/**
 * AudioEngine - Unified audio context management for Magic8
 * Handles voice synthesis, binaural beats, isochronic tones, Q-sound panning,
 * and audio source management with analyser node for visualizers
 */
class AudioEngine {
    constructor() {
        this.context = null;
        this.sources = new Map();
        this.binauralEngine = null;
        this.isochronicEngine = null;
        this.masterGain = null;
        this.destinationNode = null;
        this.analyserNode = null;
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
     * Get or create an AnalyserNode connected to the master output.
     * Used by AudioVisualizerOverlay to display frequency data.
     * @returns {AnalyserNode}
     */
    getAnalyser() {
        if (this.analyserNode) return this.analyserNode;

        const ctx = this.getContext();
        this.analyserNode = ctx.createAnalyser();
        this.analyserNode.fftSize = 2048;
        this.analyserNode.smoothingTimeConstant = 0.85;

        // Insert analyser between masterGain and destinations
        // Disconnect masterGain from current destinations
        this.masterGain.disconnect();

        // Reconnect through analyser
        this.masterGain.connect(this.analyserNode);
        this.analyserNode.connect(this.context.destination);
        if (this.destinationNode) {
            this.analyserNode.connect(this.destinationNode);
        }

        return this.analyserNode;
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
     * Start binaural beats with configurable sweep, fades, and Q-sound panning
     * @param {Object} config - Binaural configuration
     * @param {number} [config.baseFreq=440] - Base carrier frequency (Hz)
     * @param {number} [config.sweepStart=8] - Beat frequency at start of sweep cycle (Hz)
     * @param {number} [config.sweepTrough=2] - Beat frequency at midpoint of sweep cycle (Hz)
     * @param {number} [config.sweepEnd=8] - Beat frequency at end of sweep cycle (Hz)
     * @param {number} [config.sweepDurationMin=5] - Full sweep cycle duration (minutes)
     * @param {number} [config.fadeInSec=2] - Fade-in duration (seconds)
     * @param {number} [config.fadeOutSec=3] - Fade-out duration (seconds)
     * @param {boolean} [config.panEnabled=false] - Enable Q-sound spatial panning
     * @param {number} [config.panSpeed=0.1] - Pan oscillation speed (radians/sec)
     * @param {number} [config.panDepth=0.8] - Pan sweep depth (-1 to 1 range)
     */
    startBinaural(config = {}) {
        if (this.binauralEngine) {
            this.stopBinaural();
        }

        const ctx = this.getContext();

        const baseFreq = config.baseFreq || 440;
        // Support new 3-point sweep or fall back to legacy minBeat/maxBeat
        const sweepStart = config.sweepStart || config.maxBeat || 8;
        const sweepTrough = config.sweepTrough || config.minBeat || 2;
        const sweepEnd = config.sweepEnd || config.maxBeat || 8;
        const sweepDurationMin = config.sweepDurationMin || 5;
        const fadeInSec = config.fadeInSec || 2;
        const fadeOutSec = config.fadeOutSec || 3;
        const panEnabled = config.panEnabled || false;
        const panSpeed = config.panSpeed || 0.1;
        const panDepth = config.panDepth || 0.8;

        // Create oscillators and nodes
        this.binauralEngine = {
            leftOsc: ctx.createOscillator(),
            rightOsc: ctx.createOscillator(),
            leftGain: ctx.createGain(),
            rightGain: ctx.createGain(),
            binauralGain: ctx.createGain(),
            merger: ctx.createChannelMerger(2),
            dcBlocker: ctx.createBiquadFilter(),
            panner: null,
            panAnimFrame: null,
            baseFreq,
            sweepStart,
            sweepTrough,
            sweepEnd,
            sweepDurationMin,
            fadeInSec,
            fadeOutSec,
            panEnabled,
            panSpeed,
            panDepth,
            sweepInterval: null
        };

        const be = this.binauralEngine;

        // Configure DC blocker
        be.dcBlocker.type = "highpass";
        be.dcBlocker.frequency.value = 20;

        // Set initial gain to 0 for smooth ramp-up
        be.binauralGain.gain.setValueAtTime(0, ctx.currentTime);

        // Build audio graph
        be.leftOsc.connect(be.leftGain).connect(be.merger, 0, 0);
        be.rightOsc.connect(be.rightGain).connect(be.merger, 0, 1);

        if (panEnabled) {
            // Q-sound: insert StereoPannerNode after merger
            be.panner = ctx.createStereoPanner();
            be.panner.pan.value = 0;
            be.merger.connect(be.panner);
            be.panner.connect(be.binauralGain);
        } else {
            be.merger.connect(be.binauralGain);
        }

        be.binauralGain.connect(be.dcBlocker);
        be.dcBlocker.connect(this.masterGain);

        // Set initial frequencies (start of sweep)
        be.leftOsc.frequency.setValueAtTime(baseFreq, ctx.currentTime);
        be.rightOsc.frequency.setValueAtTime(baseFreq + sweepStart, ctx.currentTime);

        // Start oscillators
        be.leftOsc.start();
        be.rightOsc.start();

        // Smooth fade-in
        const targetVolume = 0.35;
        const delay = 0.05;
        const startTime = ctx.currentTime + delay;

        be.binauralGain.gain.cancelScheduledValues(ctx.currentTime);
        be.binauralGain.gain.setValueAtTime(0, ctx.currentTime);
        be.binauralGain.gain.linearRampToValueAtTime(targetVolume, startTime + fadeInSec);

        // Start frequency sweep
        this._scheduleBinauralSweep();
        be.sweepInterval = setInterval(
            () => this._scheduleBinauralSweep(),
            sweepDurationMin * 60 * 1000
        );

        // Start Q-sound panning animation if enabled
        if (panEnabled) {
            this._startPanAnimation();
        }

        console.log('AudioEngine: Binaural started — base:', baseFreq, 'Hz, sweep:',
            sweepStart, '→', sweepTrough, '→', sweepEnd, 'Hz, cycle:', sweepDurationMin, 'min',
            panEnabled ? ', Q-sound enabled' : '');
    }

    /**
     * Schedule binaural frequency sweep (3-point: start → trough → end)
     * @private
     */
    _scheduleBinauralSweep() {
        if (!this.binauralEngine) return;

        const be = this.binauralEngine;
        const ctx = this.context;
        const now = ctx.currentTime;
        const halfCycle = (be.sweepDurationMin * 60) / 2;

        be.rightOsc.frequency.cancelScheduledValues(now);
        be.rightOsc.frequency.setValueAtTime(be.baseFreq + be.sweepStart, now);
        be.rightOsc.frequency.linearRampToValueAtTime(be.baseFreq + be.sweepTrough, now + halfCycle);
        be.rightOsc.frequency.linearRampToValueAtTime(be.baseFreq + be.sweepEnd, now + 2 * halfCycle);
    }

    /**
     * Animate Q-sound stereo panning using requestAnimationFrame
     * Creates slow spatial movement for an immersive effect
     * @private
     */
    _startPanAnimation() {
        if (!this.binauralEngine || !this.binauralEngine.panner) return;

        const be = this.binauralEngine;
        const startTime = performance.now();

        const animate = (now) => {
            if (!this.binauralEngine || !be.panner) return;

            const elapsed = (now - startTime) / 1000;
            const panValue = Math.sin(elapsed * be.panSpeed) * be.panDepth;
            be.panner.pan.value = Math.max(-1, Math.min(1, panValue));

            be.panAnimFrame = requestAnimationFrame(animate);
        };

        be.panAnimFrame = requestAnimationFrame(animate);
    }

    /**
     * Stop binaural beats with configurable fade-out
     */
    stopBinaural() {
        if (!this.binauralEngine) return;

        const be = this.binauralEngine;
        const ctx = this.context;

        if (be.sweepInterval) {
            clearInterval(be.sweepInterval);
        }

        if (be.panAnimFrame) {
            cancelAnimationFrame(be.panAnimFrame);
        }

        if (!ctx) {
            // Context already closed or never created - just clean up references
            try { be.leftOsc.stop(); } catch (e) {}
            try { be.rightOsc.stop(); } catch (e) {}
            try { be.leftOsc.disconnect(); } catch (e) {}
            try { be.rightOsc.disconnect(); } catch (e) {}
            try { be.binauralGain.disconnect(); } catch (e) {}
            if (be.panner) try { be.panner.disconnect(); } catch (e) {}
            this.binauralEngine = null;
            return;
        }

        // Ramp down volume using configured fade-out
        const fadeOutSec = be.fadeOutSec || 3;
        const now = ctx.currentTime;
        be.binauralGain.gain.cancelScheduledValues(now);
        be.binauralGain.gain.setValueAtTime(be.binauralGain.gain.value, now);
        be.binauralGain.gain.linearRampToValueAtTime(0, now + fadeOutSec);

        setTimeout(() => {
            try {
                be.leftOsc.stop();
                be.rightOsc.stop();
            } catch (e) {}

            try {
                be.leftOsc.disconnect();
                be.rightOsc.disconnect();
                be.binauralGain.disconnect();
                be.dcBlocker.disconnect();
                if (be.panner) be.panner.disconnect();
            } catch (e) {}

            this.binauralEngine = null;
        }, (fadeOutSec * 1000) + 50);
    }

    /**
     * Start isochronic tones — amplitude-modulated rhythmic pulsing
     * @param {Object} config - Isochronic configuration
     * @param {number} [config.isochronicFreq=200] - Tone frequency (Hz)
     * @param {number} [config.isochronicRate=6] - Pulse rate (Hz, e.g. 4-10)
     * @param {number} [config.isochronicVolume=0.15] - Volume (0-1)
     */
    startIsochronic(config = {}) {
        if (this.isochronicEngine) {
            this.stopIsochronic();
        }

        const ctx = this.getContext();
        const freq = config.isochronicFreq || 200;
        const rate = config.isochronicRate || 6;
        const volume = config.isochronicVolume || 0.15;

        // Create oscillator for the tone
        const osc = ctx.createOscillator();
        osc.type = 'sine';
        osc.frequency.value = freq;

        // Create gain node for amplitude modulation (the pulsing)
        const pulseGain = ctx.createGain();
        pulseGain.gain.value = 0;

        // Create master gain for overall volume
        const isoGain = ctx.createGain();
        isoGain.gain.value = volume;

        // Connect: osc → pulseGain → isoGain → masterGain
        osc.connect(pulseGain);
        pulseGain.connect(isoGain);
        isoGain.connect(this.masterGain);

        osc.start();

        // Schedule repeating pulse pattern
        const pulseDuration = 1.0 / rate;
        const onDuration = pulseDuration * 0.5;
        const offDuration = pulseDuration * 0.5;
        const rampTime = 0.01; // sharp on/off transitions

        this.isochronicEngine = {
            osc,
            pulseGain,
            isoGain,
            rate,
            interval: null
        };

        // Schedule pulses using a recurring function
        const schedulePulses = () => {
            if (!this.isochronicEngine) return;

            const now = ctx.currentTime;
            const scheduleAhead = 2.0; // schedule 2 seconds ahead

            for (let t = 0; t < scheduleAhead; t += pulseDuration) {
                const pulseStart = now + t;
                // On: ramp up
                pulseGain.gain.setValueAtTime(0, pulseStart);
                pulseGain.gain.linearRampToValueAtTime(1, pulseStart + rampTime);
                // Stay on
                pulseGain.gain.setValueAtTime(1, pulseStart + onDuration - rampTime);
                // Off: ramp down
                pulseGain.gain.linearRampToValueAtTime(0, pulseStart + onDuration);
            }
        };

        schedulePulses();
        this.isochronicEngine.interval = setInterval(schedulePulses, 1500);

        console.log('AudioEngine: Isochronic started — freq:', freq, 'Hz, rate:', rate, 'Hz, vol:', volume);
    }

    /**
     * Stop isochronic tones
     */
    stopIsochronic() {
        if (!this.isochronicEngine) return;

        const ie = this.isochronicEngine;
        if (ie.interval) {
            clearInterval(ie.interval);
        }

        try {
            ie.pulseGain.gain.cancelScheduledValues(0);
            ie.isoGain.gain.value = 0;
        } catch (e) {}

        setTimeout(() => {
            try {
                ie.osc.stop();
                ie.osc.disconnect();
                ie.pulseGain.disconnect();
                ie.isoGain.disconnect();
            } catch (e) {}
        }, 100);

        this.isochronicEngine = null;
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
        this.stopIsochronic();
        this.stopAllSources();

        if (this.analyserNode) {
            try { this.analyserNode.disconnect(); } catch (e) {}
            this.analyserNode = null;
        }

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
