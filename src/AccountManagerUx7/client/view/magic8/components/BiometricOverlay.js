/**
 * BiometricOverlay - Animated floating text labels styled by biometric theme
 * Shows text sequence lines as floating labels with randomized positions and animations.
 * Face analysis data drives the color palette applied to labels, not displayed as text.
 * Animation types: drift, pulse, scale, float
 * Mithril.js component
 */
const BiometricOverlay = {
    oninit(vnode) {
        this.labels = [];
        this.nextId = 0;
        this.lastText = '';
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

            const now = Date.now();
            const before = this.labels.length;
            this.labels = this.labels.filter(l => {
                const age = (now - l.born) / 1000;
                return age < l.duration;
            });

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
        const { text } = vnode.attrs;
        if (text && text !== this.lastText) {
            this.lastText = text;

            const now = Date.now();
            const animTypes = ['drift', 'pulse', 'scale', 'float'];

            this.labels.push({
                id: this.nextId++,
                text: text,
                x: 5 + Math.random() * 85,
                y: 10 + Math.random() * 75,
                anim: animTypes[Math.floor(Math.random() * animTypes.length)],
                size: 0.7 + Math.random() * 0.6,
                duration: 8 + Math.random() * 10,
                driftX: (Math.random() - 0.5) * 50,
                driftY: (Math.random() - 0.5) * 40,
                born: now
            });

            if (this.labels.length > 20) {
                this.labels = this.labels.slice(-20);
            }
        }

        // Handle session labels from director
        const sessionLabels = vnode.attrs.sessionLabels;
        if (sessionLabels && sessionLabels.length > 0) {
            const existing = new Set(this.labels.filter(l => l.isSession).map(l => l.text));
            const animTypes = ['drift', 'pulse', 'scale', 'float'];
            for (const label of sessionLabels) {
                if (!existing.has(label)) {
                    this.labels.push({
                        id: this.nextId++,
                        text: label,
                        x: 5 + Math.random() * 85,
                        y: 10 + Math.random() * 75,
                        anim: animTypes[Math.floor(Math.random() * 4)],
                        size: 0.5 + Math.random() * 0.4,
                        duration: 120,
                        driftX: (Math.random() - 0.5) * 30,
                        driftY: (Math.random() - 0.5) * 20,
                        born: Date.now(),
                        isSession: true
                    });
                }
            }
            // Remove session labels no longer in the list
            this.labels = this.labels.filter(l => !l.isSession || sessionLabels.includes(l.text));
        } else {
            // If no session labels provided, remove any existing session labels
            this.labels = this.labels.filter(l => !l.isSession);
        }
    },

    view(vnode) {
        if (!this.labels.length) return null;

        const theme = vnode.attrs.theme;

        // Derive colors from biometric theme
        const accentColor = theme?.accent
            ? `rgb(${theme.accent.join(',')})`
            : 'rgba(255,255,255,0.7)';
        const glowColor = theme?.glow
            ? `rgb(${theme.glow.join(',')})`
            : 'rgba(255,255,255,0.3)';

        // Filter expired labels before rendering to avoid Mithril key mismatch
        const visibleLabels = this.labels.filter(label => {
            const age = (Date.now() - label.born) / 1000;
            const remaining = label.duration - age;
            const fadeOut = remaining < 4 ? Math.max(0, remaining / 4) : 1;
            return Math.min(1, age / 1.5) * fadeOut * 0.45 > 0;
        });

        if (!visibleLabels.length) return null;

        return m('.biometric-overlay.absolute.inset-0.pointer-events-none.overflow-hidden', {
            style: { zIndex: 15 }
        }, visibleLabels.map(label => {
            const age = (Date.now() - label.born) / 1000;
            const fadeIn = Math.min(1, age / 1.5);
            const remaining = label.duration - age;
            const fadeOut = remaining < 4 ? Math.max(0, remaining / 4) : 1;
            const opacity = fadeIn * fadeOut * 0.45;

            const style = {
                position: 'absolute',
                left: label.x + '%',
                top: label.y + '%',
                opacity: opacity,
                fontSize: (label.size * 5) + 'rem',
                fontFamily: "'Playfair Display', Georgia, serif",
                letterSpacing: '0.05em',
                lineHeight: '1.4',
                maxWidth: '40%',
                color: accentColor,
                textShadow: `0 0 12px ${glowColor}, 0 0 24px ${glowColor}`,
                willChange: 'transform, opacity'
            };

            // LLM session labels get stylistic emphasis
            if (label.isSession) {
                style.fontStyle = 'italic';
                style.letterSpacing = '0.12em';
                style.textShadow = `0 0 18px ${glowColor}, 0 0 36px ${glowColor}, 0 0 56px ${glowColor}`;
                style.borderBottom = `1px solid ${glowColor}`;
                style.paddingBottom = '2px';
            }

            if (label.anim === 'drift') {
                const progress = age / label.duration;
                style.transform = `translate(${label.driftX * progress}px, ${label.driftY * progress}px)`;
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

            return m('.bio-label', { key: label.id, style: style }, label.text);
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
