/**
 * BiometricOverlay - Animated floating labels showing face analysis data & color palette
 * Labels spawn at random positions with various animation effects:
 *   drift (slow movement), pulse (fade in/out), scale (grow/shrink), float (gentle bob)
 * Mithril.js component
 */
const BiometricOverlay = {
    oninit(vnode) {
        this.labels = [];
        this.nextId = 0;
        this.lastDataHash = '';
    },

    onremove() {
        this.labels = [];
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
                x: 5 + Math.random() * 85,       // 5-90% from left
                y: 10 + Math.random() * 75,       // 10-85% from top
                anim: animTypes[Math.floor(Math.random() * animTypes.length)],
                size: 0.7 + Math.random() * 0.6,  // 0.7x - 1.3x
                delay: Math.random() * 2,          // 0-2s stagger
                duration: 6 + Math.random() * 8,   // 6-14s lifetime
                driftX: (Math.random() - 0.5) * 40, // -20 to +20 vw drift
                driftY: (Math.random() - 0.5) * 30, // -15 to +15 vh drift
                born: now
            });
        }

        // Prune old labels (keep max ~30)
        const maxAge = 20000;
        this.labels = this.labels
            .filter(l => now - l.born < maxAge)
            .slice(-30);
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
            const age = (Date.now() - label.born) / 1000; // seconds alive
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
                willChange: 'transform, opacity',
                transition: 'opacity 0.5s ease'
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
