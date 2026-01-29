/**
 * BiometricOverlay - Animated floating labels showing face analysis data & color palette
 * Labels spawn at random positions with various animation effects:
 *   drift (slow movement), pulse (fade in/out), scale (grow/shrink), float (gentle bob)
 * Uses requestAnimationFrame for smooth continuous animation.
 * Mithril.js component
 */
const BiometricOverlay = {
    oninit(vnode) {
        this.labels = [];
        this.nextId = 0;
        this.lastDataHash = '';
        this._rafId = null;
    },

    oncreate(vnode) {
        this._startAnimLoop();
    },

    onremove() {
        this._stopAnimLoop();
        this.labels = [];
    },

    _startAnimLoop() {
        const tick = () => {
            this._rafId = requestAnimationFrame(tick);

            // Prune expired labels
            const now = Date.now();
            const before = this.labels.length;
            this.labels = this.labels.filter(l => {
                const age = (now - l.born) / 1000;
                return age < l.duration;
            });

            // Only redraw if there are active labels
            if (this.labels.length > 0 || before > 0) {
                m.redraw();
            }
        };
        this._rafId = requestAnimationFrame(tick);
    },

    _stopAnimLoop() {
        if (this._rafId) {
            cancelAnimationFrame(this._rafId);
            this._rafId = null;
        }
    },

    onupdate(vnode) {
        const { biometricData, theme } = vnode.attrs;
        if (!biometricData) return;

        // Only spawn new labels when data changes
        const hash = (biometricData.dominant_emotion || '') + '|' +
            (biometricData.age || '') + '|' +
            (biometricData.dominant_gender || '');
        if (hash === this.lastDataHash) return;
        this.lastDataHash = hash;

        console.log('BiometricOverlay: New data -', hash);

        // Build label entries from biometric data
        const entries = this._extractEntries(biometricData, theme);

        // Spawn labels with random positions & animation types
        const now = Date.now();
        const animTypes = ['drift', 'pulse', 'scale', 'float'];

        for (const entry of entries) {
            this.labels.push({
                id: this.nextId++,
                text: entry.text,
                color: entry.color || null,
                swatch: entry.swatch || null,
                x: 5 + Math.random() * 85,
                y: 10 + Math.random() * 75,
                anim: animTypes[Math.floor(Math.random() * animTypes.length)],
                size: 0.7 + Math.random() * 0.6,
                delay: Math.random() * 2,
                duration: 6 + Math.random() * 8,
                driftX: (Math.random() - 0.5) * 40,
                driftY: (Math.random() - 0.5) * 30,
                born: now
            });
        }

        // Keep max ~30 labels
        if (this.labels.length > 30) {
            this.labels = this.labels.slice(-30);
        }
    },

    /**
     * Extract display entries from biometric data
     * @private
     */
    _extractEntries(data, theme) {
        const entries = [];

        // Dominant emotion
        if (data.dominant_emotion) {
            entries.push({ text: data.dominant_emotion });
        }

        // Emotion scores (show top 3)
        if (data.emotion_scores) {
            const sorted = Object.entries(data.emotion_scores)
                .map(([k, v]) => [k, parseFloat(v) || 0])
                .sort((a, b) => b[1] - a[1])
                .slice(0, 3);
            for (const [emotion, score] of sorted) {
                if (score > 0.05) {
                    entries.push({ text: `${emotion} ${(score * 100).toFixed(0)}%` });
                }
            }
        }

        // Age
        if (data.age) {
            entries.push({ text: `age ${data.age}` });
        }

        // Gender
        if (data.dominant_gender) {
            entries.push({ text: data.dominant_gender.toLowerCase() });
        }

        // Color palette swatches from theme
        if (theme) {
            if (theme.bg) {
                entries.push({ text: 'bg', swatch: theme.bg });
            }
            if (theme.accent) {
                entries.push({ text: 'accent', swatch: theme.accent });
            }
            if (theme.glow) {
                entries.push({ text: 'glow', swatch: theme.glow });
            }
            if (theme.particle) {
                entries.push({ text: 'particle', swatch: theme.particle });
            }
        }

        return entries;
    },

    view(vnode) {
        if (!this.labels.length) return null;

        return m('.biometric-overlay.absolute.inset-0.pointer-events-none.overflow-hidden', {
            style: { zIndex: 15 }
        }, this.labels.map(label => {
            const age = (Date.now() - label.born) / 1000;
            // Fade in first 1s, hold, fade out in last 3s
            const fadeIn = Math.min(1, age / 1);
            const remaining = label.duration - age;
            const fadeOut = remaining < 3 ? Math.max(0, remaining / 3) : 1;
            const opacity = fadeIn * fadeOut * 0.55;

            if (opacity <= 0) return null;

            const style = {
                position: 'absolute',
                left: label.x + '%',
                top: label.y + '%',
                opacity: opacity,
                fontSize: (label.size * 0.75) + 'rem',
                fontFamily: "'Courier New', monospace",
                letterSpacing: '0.08em',
                whiteSpace: 'nowrap',
                willChange: 'transform, opacity'
            };

            // Apply animation style
            if (label.anim === 'drift') {
                const progress = age / label.duration;
                const tx = label.driftX * progress;
                const ty = label.driftY * progress;
                style.transform = `translate(${tx}px, ${ty}px)`;
            } else if (label.anim === 'pulse') {
                const pulse = 0.5 + 0.5 * Math.sin(age * 1.5);
                style.opacity = opacity * (0.3 + pulse * 0.7);
            } else if (label.anim === 'scale') {
                const scale = 0.8 + 0.4 * Math.sin(age * 0.8);
                style.transform = `scale(${scale})`;
            } else if (label.anim === 'float') {
                const bobY = Math.sin(age * 0.6) * 12;
                const bobX = Math.cos(age * 0.4) * 6;
                style.transform = `translate(${bobX}px, ${bobY}px)`;
            }

            const textColor = label.swatch
                ? `rgb(${label.swatch.join(',')})`
                : 'rgba(255,255,255,0.7)';

            return m('.bio-label', { key: label.id, style: style }, [
                // Color swatch dot
                label.swatch && m('span', {
                    style: {
                        display: 'inline-block',
                        width: '8px',
                        height: '8px',
                        borderRadius: '50%',
                        backgroundColor: `rgb(${label.swatch.join(',')})`,
                        marginRight: '6px',
                        verticalAlign: 'middle',
                        boxShadow: `0 0 6px rgb(${label.swatch.join(',')})`
                    }
                }),
                m('span', {
                    style: {
                        color: textColor,
                        textShadow: `0 0 8px ${textColor}`
                    }
                }, label.text)
            ]);
        }));
    }
};

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BiometricOverlay;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.BiometricOverlay = BiometricOverlay;
}
