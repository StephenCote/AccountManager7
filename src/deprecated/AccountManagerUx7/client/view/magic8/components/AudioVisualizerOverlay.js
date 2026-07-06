/**
 * AudioVisualizerOverlay - Translucent audio frequency visualizer overlay
 * Uses AudioMotionAnalyzer to display frequency data from the AudioEngine
 * Renders as a screen-blended layer over the visual effects
 */
const AudioVisualizerOverlay = {
    oninit(vnode) {
        this.audioMotion = null;
    },

    oncreate(vnode) {
        this._createVisualizer(vnode);
    },

    onupdate(vnode) {
        if (this.audioMotion) {
            const gradient = vnode.attrs.gradient || 'prism';
            const mode = vnode.attrs.mode != null ? vnode.attrs.mode : 2;

            if (this.audioMotion.gradient !== gradient) {
                try { this.audioMotion.gradient = gradient; } catch (e) { /* gradient may not exist */ }
            }
            if (this.audioMotion.mode !== mode) {
                this.audioMotion.mode = mode;
            }
        }
    },

    onremove(vnode) {
        if (this.audioMotion) {
            try {
                this.audioMotion.destroy();
            } catch (e) {
                // Ignore cleanup errors
            }
            this.audioMotion = null;
        }
    },

    /**
     * Create the AudioMotionAnalyzer instance
     * @param {Object} vnode - Mithril vnode
     * @private
     */
    _createVisualizer(vnode) {
        const audioEngine = vnode.attrs.audioEngine;
        if (!audioEngine) {
            console.warn('AudioVisualizerOverlay: No audioEngine provided');
            return;
        }

        // Ensure AudioMotionAnalyzer is available
        if (typeof AudioMotionAnalyzer === 'undefined') {
            console.warn('AudioVisualizerOverlay: AudioMotionAnalyzer not loaded yet, retrying...');
            setTimeout(() => this._createVisualizer(vnode), 500);
            return;
        }

        const container = vnode.dom;
        if (!container) return;

        try {
            const ctx = audioEngine.getContext();

            this.audioMotion = new AudioMotionAnalyzer(container, {
                audioCtx: ctx,
                connectSpeakers: false,
                overlay: true,
                bgAlpha: 0,
                showBgColor: false,
                showScaleY: false,
                showScaleX: false,
                gradient: vnode.attrs.gradient || 'prism',
                mode: vnode.attrs.mode != null ? vnode.attrs.mode : 2,
                reflexRatio: 0.3,
                showPeaks: true,
                smoothing: 0.8,
                barSpace: 0.2,
                lumiBars: false
            });

            // Connect our master audio output to the visualizer's input
            this.audioMotion.connectInput(audioEngine.masterGain);

            console.log('AudioVisualizerOverlay: Visualizer created successfully');
        } catch (err) {
            console.error('AudioVisualizerOverlay: Failed to create visualizer:', err);
        }
    },

    view(vnode) {
        return m('.audio-visualizer-overlay', {
            style: {
                position: 'absolute',
                top: 0,
                left: 0,
                right: 0,
                bottom: 0,
                zIndex: 7,
                opacity: vnode.attrs.opacity || 0.35,
                pointerEvents: 'none',
                mixBlendMode: 'screen'
            }
        });
    }
};

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = AudioVisualizerOverlay;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.AudioVisualizerOverlay = AudioVisualizerOverlay;
}
