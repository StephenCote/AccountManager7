/**
 * HypnoticTextDisplay - Fading text overlay for hypnotic sequences
 * Mithril.js component for displaying scripted text with fade transitions
 */
const HypnoticTextDisplay = {
    oninit(vnode) {
        this.opacity = 0;
        this.currentText = '';
        this.displayText = '';
        this.isTransitioning = false;
        this.transitionTimeout = null;
        this._glowPhase = 0;
        this._glowRafId = null;
    },

    oncreate(vnode) {
        // Continuous subtle glow breathing on the center text (~10fps to stay light)
        this._lastGlowRedraw = 0;
        const animateGlow = () => {
            this._glowPhase = (Date.now() % 6000) / 6000;
            const now = Date.now();
            if (now - this._lastGlowRedraw >= 100) {
                this._lastGlowRedraw = now;
                m.redraw();
            }
            this._glowRafId = requestAnimationFrame(animateGlow);
        };
        this._glowRafId = requestAnimationFrame(animateGlow);
    },

    onupdate(vnode) {
        const { text } = vnode.attrs;

        // Handle text change with fade transition
        if (text !== this.currentText) {
            this.currentText = text;
            this._fadeToText(text);
        }
    },

    onremove(vnode) {
        if (this.transitionTimeout) {
            clearTimeout(this.transitionTimeout);
        }
        if (this._glowRafId) {
            cancelAnimationFrame(this._glowRafId);
            this._glowRafId = null;
        }
    },

    /**
     * Fade out current text and fade in new text
     * @param {string} newText - Text to display
     * @private
     */
    _fadeToText(newText) {
        if (this.isTransitioning) {
            clearTimeout(this.transitionTimeout);
        }

        this.isTransitioning = true;

        // Fade out
        this.opacity = 0;
        m.redraw();

        // After fade out, change text and fade in
        this.transitionTimeout = setTimeout(() => {
            this.displayText = newText;
            this.opacity = 1;
            this.isTransitioning = false;
            m.redraw();
        }, 500); // Half of transition duration
    },

    view(vnode) {
        const {
            theme,
            fontSize = 'text-4xl md:text-6xl',
            fontFamily = "'Playfair Display', Georgia, serif",
            textAlign = 'center',
            padding = 'px-8'
        } = vnode.attrs;

        // Get colors from theme or defaults
        const accentColor = theme?.accent
            ? `rgb(${theme.accent.join(',')})`
            : 'rgb(255, 255, 255)';

        const glowColor = theme?.glow
            ? `rgb(${theme.glow.join(',')})`
            : 'rgb(255, 255, 255)';

        // Subtle breathing glow: oscillate text-shadow radii
        const glowWave = 0.5 + 0.5 * Math.sin(this._glowPhase * Math.PI * 2);
        const r1 = Math.round(20 + 20 * glowWave);
        const r2 = Math.round(40 + 30 * glowWave);
        const r3 = Math.round(60 + 40 * glowWave);

        return m('.hypnotic-text-container', {
            class: 'absolute inset-0 flex items-center justify-center pointer-events-none z-20'
        }, [
            m('.hypnotic-text', {
                class: `${fontSize} font-light ${textAlign} ${padding} transition-opacity duration-500`,
                style: {
                    opacity: this.opacity,
                    color: accentColor,
                    textShadow: `
                        0 0 ${r1}px ${glowColor},
                        0 0 ${r2}px ${glowColor},
                        0 0 ${r3}px ${glowColor}
                    `,
                    fontFamily: fontFamily,
                    letterSpacing: '0.05em',
                    lineHeight: '1.4',
                    maxWidth: '80%'
                }
            }, this.displayText)
        ]);
    }
};

/**
 * Factory function to create independent instances
 * @returns {Object} New HypnoticTextDisplay instance
 */
function createHypnoticTextDisplay() {
    return {
        oninit(vnode) {
            this.opacity = 0;
            this.currentText = '';
            this.displayText = '';
            this.isTransitioning = false;
            this.transitionTimeout = null;
            this._glowPhase = 0;
            this._glowRafId = null;
        },

        oncreate(vnode) {
            this._lastGlowRedraw = 0;
            const animateGlow = () => {
                this._glowPhase = (Date.now() % 6000) / 6000;
                const now = Date.now();
                if (now - this._lastGlowRedraw >= 100) {
                    this._lastGlowRedraw = now;
                    m.redraw();
                }
                this._glowRafId = requestAnimationFrame(animateGlow);
            };
            this._glowRafId = requestAnimationFrame(animateGlow);
        },

        onupdate(vnode) {
            const { text } = vnode.attrs;

            if (text !== this.currentText) {
                this.currentText = text;
                this._fadeToText(text);
            }
        },

        onremove(vnode) {
            if (this.transitionTimeout) {
                clearTimeout(this.transitionTimeout);
            }
            if (this._glowRafId) {
                cancelAnimationFrame(this._glowRafId);
                this._glowRafId = null;
            }
        },

        _fadeToText(newText) {
            if (this.isTransitioning) {
                clearTimeout(this.transitionTimeout);
            }

            this.isTransitioning = true;
            this.opacity = 0;
            m.redraw();

            this.transitionTimeout = setTimeout(() => {
                this.displayText = newText;
                this.opacity = 1;
                this.isTransitioning = false;
                m.redraw();
            }, 500);
        },

        view: HypnoticTextDisplay.view
    };
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { HypnoticTextDisplay, createHypnoticTextDisplay };
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.HypnoticTextDisplay = HypnoticTextDisplay;
    window.Magic8.createHypnoticTextDisplay = createHypnoticTextDisplay;
}
