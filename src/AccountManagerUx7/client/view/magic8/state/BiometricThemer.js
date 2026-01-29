/**
 * BiometricThemer - Emotion-driven theme system with smooth transitions
 * Converts facial analysis data into color themes for the Magic8 experience
 */
class BiometricThemer {
    constructor() {
        this.currentTheme = null;
        this.targetTheme = null;
        this.transitionDuration = 2000; // 2 second transitions
        this.smoothingFactor = 0.05; // How fast to lerp
        this.animationFrame = null;
        this.onThemeChange = null;
    }

    /**
     * Emotion to RGB color mapping
     * Each emotion has background, accent, and glow colors
     */
    static emotionThemes = {
        neutral:  { bg: [40, 45, 60],    accent: [80, 90, 120],   glow: [100, 110, 140] },
        happy:    { bg: [60, 50, 20],    accent: [255, 220, 100], glow: [255, 240, 150] },
        sad:      { bg: [30, 30, 70],    accent: [80, 80, 160],   glow: [100, 100, 200] },
        angry:    { bg: [70, 20, 20],    accent: [200, 80, 80],   glow: [255, 100, 100] },
        fear:     { bg: [50, 30, 60],    accent: [160, 80, 200],  glow: [200, 120, 255] },
        surprise: { bg: [60, 40, 50],    accent: [255, 180, 200], glow: [255, 200, 220] },
        disgust:  { bg: [30, 50, 30],    accent: [80, 160, 80],   glow: [100, 200, 100] }
    };

    /**
     * Race-based skin tone colors for particle effects
     */
    static raceColors = {
        white: [255, 220, 180],
        black: [100, 75, 50],
        asian: [255, 235, 160],
        'East Asian': [255, 235, 160],
        'Southeast Asian': [255, 235, 160],
        indian: [200, 160, 120],
        'middle eastern': [220, 190, 150],
        'latino hispanic': [180, 130, 90]
    };

    /**
     * Gender-based accent colors
     */
    static genderColors = {
        Man: [180, 200, 255],
        Woman: [255, 200, 220]
    };

    /**
     * Process biometric data and update theme
     * @param {Object} biometricData - Facial analysis data
     */
    updateFromBiometrics(biometricData) {
        if (!biometricData) return;

        // Get dominant emotion
        const emotion = biometricData.dominant_emotion || 'neutral';
        const baseTheme = BiometricThemer.emotionThemes[emotion] || BiometricThemer.emotionThemes.neutral;

        let blendedTheme = { ...baseTheme };

        // Blend based on emotion intensity scores if available
        if (biometricData.emotion_scores) {
            blendedTheme = this._blendByIntensity(biometricData.emotion_scores);
        }

        // Modulate based on arousal if available
        if (typeof biometricData.arousal !== 'undefined') {
            blendedTheme = this._modulateByArousal(blendedTheme, biometricData.arousal);
        }

        // Calculate particle color from race/gender
        let particleColor = this._calculateParticleColor(biometricData);
        blendedTheme.particle = particleColor;

        // Start transitioning to new theme
        this.transitionTo(blendedTheme);
    }

    /**
     * Blend theme colors based on emotion intensity scores
     * @param {Object} emotionScores - Map of emotion to score (0-1)
     * @returns {Object} Blended theme
     * @private
     */
    _blendByIntensity(emotionScores) {
        let result = {
            bg: [0, 0, 0],
            accent: [0, 0, 0],
            glow: [0, 0, 0]
        };
        let totalWeight = 0;

        for (const [emotion, score] of Object.entries(emotionScores)) {
            const theme = BiometricThemer.emotionThemes[emotion];
            if (!theme) continue;

            const weight = parseFloat(score) || 0;
            totalWeight += weight;

            ['bg', 'accent', 'glow'].forEach(key => {
                result[key] = result[key].map((v, i) => v + theme[key][i] * weight);
            });
        }

        if (totalWeight > 0) {
            ['bg', 'accent', 'glow'].forEach(key => {
                result[key] = result[key].map(v => Math.round(v / totalWeight));
            });
        } else {
            // Fallback to neutral if no weights
            result = { ...BiometricThemer.emotionThemes.neutral };
        }

        return result;
    }

    /**
     * Modulate theme brightness/saturation based on arousal level
     * @param {Object} theme - Current theme
     * @param {number} arousal - Arousal level (0-1)
     * @returns {Object} Modulated theme
     * @private
     */
    _modulateByArousal(theme, arousal) {
        // Higher arousal = more vibrant colors
        const intensity = 0.5 + (arousal * 0.5); // 0.5 to 1.0

        return {
            bg: theme.bg.map(c => Math.round(c * intensity)),
            accent: theme.accent.map(c => Math.round(Math.min(255, c * (0.8 + arousal * 0.4)))),
            glow: theme.glow.map(c => Math.round(Math.min(255, c * (0.8 + arousal * 0.4))))
        };
    }

    /**
     * Calculate particle color from race and gender data
     * @param {Object} data - Biometric data
     * @returns {Array} RGB color array
     * @private
     */
    _calculateParticleColor(data) {
        let raceColor = null;
        let genderColor = null;

        // Blend race colors by score
        if (data.race_scores) {
            let totalScore = 0;
            let r = 0, g = 0, b = 0;

            for (const [race, score] of Object.entries(data.race_scores)) {
                if (score > 0 && BiometricThemer.raceColors[race]) {
                    totalScore += score;
                    const color = BiometricThemer.raceColors[race];
                    r += color[0] * score;
                    g += color[1] * score;
                    b += color[2] * score;
                }
            }

            if (totalScore > 0) {
                raceColor = [r / totalScore, g / totalScore, b / totalScore];
            }
        }

        // Blend gender colors by score
        if (data.gender_scores) {
            let totalScore = 0;
            let r = 0, g = 0, b = 0;

            for (const [gender, score] of Object.entries(data.gender_scores)) {
                if (score > 0 && BiometricThemer.genderColors[gender]) {
                    totalScore += score;
                    const color = BiometricThemer.genderColors[gender];
                    r += color[0] * score;
                    g += color[1] * score;
                    b += color[2] * score;
                }
            }

            if (totalScore > 0) {
                genderColor = [r / totalScore, g / totalScore, b / totalScore];
            }
        }

        // Average race and gender colors
        if (raceColor && genderColor) {
            return [
                Math.round((raceColor[0] + genderColor[0]) / 2),
                Math.round((raceColor[1] + genderColor[1]) / 2),
                Math.round((raceColor[2] + genderColor[2]) / 2)
            ];
        } else if (raceColor) {
            return raceColor.map(Math.round);
        } else if (genderColor) {
            return genderColor.map(Math.round);
        }

        // Default particle color
        return [150, 180, 255];
    }

    /**
     * Begin transitioning to a new theme
     * @param {Object} targetTheme - Target theme to transition to
     */
    transitionTo(targetTheme) {
        this.targetTheme = targetTheme;

        if (!this.animationFrame) {
            this._animateTransition();
        }
    }

    /**
     * Animate theme transition with smooth lerping
     * @private
     */
    _animateTransition() {
        if (!this.currentTheme) {
            // First theme - apply immediately
            this.currentTheme = this._deepCopy(this.targetTheme);
        } else {
            // Lerp toward target
            ['bg', 'accent', 'glow', 'particle'].forEach(key => {
                if (this.targetTheme[key] && this.currentTheme[key]) {
                    this.currentTheme[key] = this.currentTheme[key].map((v, i) =>
                        Math.round(v + (this.targetTheme[key][i] - v) * this.smoothingFactor)
                    );
                }
            });
        }

        // Apply the current theme
        this._applyTheme(this.currentTheme);

        // Continue animation
        this.animationFrame = requestAnimationFrame(() => this._animateTransition());
    }

    /**
     * Apply theme to DOM and notify listeners
     * @param {Object} theme - Theme to apply
     * @private
     */
    _applyTheme(theme) {
        // Update CSS custom properties
        const root = document.documentElement;
        root.style.setProperty('--hypno-bg', `rgb(${theme.bg.join(',')})`);
        root.style.setProperty('--hypno-accent', `rgb(${theme.accent.join(',')})`);
        root.style.setProperty('--hypno-glow', `rgb(${theme.glow.join(',')})`);

        if (theme.particle) {
            root.style.setProperty('--hypno-particle', `rgb(${theme.particle.join(',')})`);
        }

        // Notify callback
        if (this.onThemeChange) {
            this.onThemeChange(theme);
        }
    }

    /**
     * Get the current theme
     * @returns {Object|null}
     */
    getCurrentTheme() {
        return this.currentTheme;
    }

    /**
     * Get CSS-ready color strings from theme
     * @param {Object} theme - Theme object
     * @returns {Object} CSS color strings
     */
    static toCSS(theme) {
        if (!theme) return {};
        return {
            bg: `rgb(${theme.bg.join(',')})`,
            accent: `rgb(${theme.accent.join(',')})`,
            glow: `rgb(${theme.glow.join(',')})`,
            particle: theme.particle ? `rgb(${theme.particle.join(',')})` : null
        };
    }

    /**
     * Set the smoothing factor for transitions
     * @param {number} factor - Smoothing factor (0-1, lower = smoother)
     */
    setSmoothingFactor(factor) {
        this.smoothingFactor = Math.max(0.01, Math.min(1, factor));
    }

    /**
     * Stop animation and clean up
     */
    dispose() {
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
            this.animationFrame = null;
        }
        this.currentTheme = null;
        this.targetTheme = null;
        this.onThemeChange = null;
    }

    /**
     * Deep copy an object
     * @param {Object} obj - Object to copy
     * @returns {Object}
     * @private
     */
    _deepCopy(obj) {
        return JSON.parse(JSON.stringify(obj));
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BiometricThemer;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.BiometricThemer = BiometricThemer;
}
